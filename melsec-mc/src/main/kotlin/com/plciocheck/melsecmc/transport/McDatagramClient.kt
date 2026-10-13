// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.transport

import com.plciocheck.melsecmc.ConnectionException
import com.plciocheck.melsecmc.FrameException
import com.plciocheck.melsecmc.ResponseTimeoutException
import com.plciocheck.melsecmc.frame.DataMode
import com.plciocheck.melsecmc.frame.FrameType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Coroutine 기반 MC Protocol UDP 클라이언트
 *
 * 역할: UDP datagram 송수신 + 4조합(3E/4E × Binary/ASCII) 프레임 운반.
 * 프레임 인코딩/디코딩은 이 클래스의 책임 밖 — McFrameEncoder/Decoder가 담당.
 * 요청/응답 페이로드는 TCP([McSocketClient])와 완전히 동일하다.
 *
 * TCP([McSocketClient])와 다른 점:
 *   - **비연결형**: TCP 핸드셰이크가 없다. [connect]는 로컬 DatagramSocket을 바인딩만 한다.
 *     목적지 IP/Port는 매 송신 datagram에 지정한다.
 *   - **datagram 단위 수신**: UDP는 1 datagram = 1 메시지. TCP의 스트림 부분 수신 누적
 *     ([PlcResponseReader])이 불필요하다. 1 요청 = 1 datagram, 1 응답 = 1 datagram 가정.
 *   - **타임아웃**: 최대 100ms의 SO_TIMEOUT으로 종료 의도를 재검사한다.
 *     전체 응답 제한은 단조 시계 기반 [ConnectionConfig.timeoutMs]이며,
 *     중간 수신 timeout은 재송신 없이 대기하고 전체 제한이 끝날 때만
 *     [ResponseTimeoutException]으로 변환한다.
 *   - **순서/재전송 부재**: UDP는 순서·도착 보장이 없다. 이 단계에서는
 *     1 요청 = 1 응답 단순 모델만 검증한다. 멀티 요청 매칭/재전송은 범위 밖.
 *   - **재시도 미적용**(회귀 검증): [connect]는 [ConnectionConfig.maxRetry]/
 *     [ConnectionConfig.retryBackoffMs]를 전혀 참조하지 않는다 — 단일 시도만 수행하고
 *     실패 시 즉시 [ConnectionException]을 던진다. 두 필드는 [McSocketClient.connect]의
 *     재시도 루프(지수 backoff)에서만 소비되는 TCP 전용 값이다. UDP 경로에서 이 값을
 *     조정해도 재시도 동작에는 영향이 없다.
 *
 * 수신 버퍼: [receiveBufferSize] (기본 8192B).
 *   Binary 최대: 3E Batch 960워드 응답 ≈ 9(헤더)+2(EndCode)+1920(데이터) = 1931B.
 *   ASCII 최대: 960워드 응답 ≈ 18(헤더)+4(EndCode)+3840(데이터) = 3862B.
 *   ASCII 배수(Binary 대비 2배)를 수용하기 위해 기본값을 8192B로 설정한다.
 *   IP 프래그멘테이션은 OS 레이어에서 재조립되어 단일 datagram으로 도착한다.
 *
 * 동시성 주의:
 *   - [sendAndReceive]는 동시 호출 안전 보장 없음. 단일 스레드에서 순차 호출할 것.
 *   - 취소(CancellationException) 시 소켓을 닫고 짧은 수신 timeout 안에서 종료를 재검사한다.
 *
 * 로컬 소스 포트 바인딩 ([localPort]):
 *   기본(null)은 OS가 임시 포트를 할당한다. CPU 다이렉트처럼 모듈이 요청 송신처(source)로
 *   응답하는 경우 임시 포트로 충분하다.
 *   QJ71E71-100 모듈의 UDP "Procedure Exist" open처럼 **응답 목적지가 파트너 IP:고정포트로
 *   설정**된 경우, 그 고정 포트를 로컬 소스 포트로 바인딩해야 응답을 수신한다.
 *   (실측 실기 검증: 모듈 UDP open 목적지 192.0.2.10:1025 → localPort=1025 바인딩 필요)
 */
internal interface DatagramLifecycleProbe {
    fun afterSocketCreated() = Unit
    fun afterPendingPublished() = Unit
    fun afterConnectSucceeded() = Unit
}

private object NoOpDatagramLifecycleProbe : DatagramLifecycleProbe

class McDatagramClient internal constructor(
    private val config: ConnectionConfig,
    private val receiveBufferSize: Int = DEFAULT_RX_BUFFER,
    private val localPort: Int? = null,
    private val lifecycleProbe: DatagramLifecycleProbe,
    /** Internal deterministic seams; public clients use real sockets and the monotonic clock. */
    private val nanoTime: () -> Long = System::nanoTime,
    private val socketFactory: (Int?) -> DatagramSocket = { port ->
        if (port != null) DatagramSocket(port) else DatagramSocket()
    },
) : Closeable {

    constructor(
        config: ConnectionConfig,
        receiveBufferSize: Int = DEFAULT_RX_BUFFER,
        localPort: Int? = null,
    ) : this(config, receiveBufferSize, localPort, NoOpDatagramLifecycleProbe)

    private val lifecycleLock = Any()

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    var connectionGeneration: Long = 0L
        private set

    val isTransportConnected: Boolean
        get() = socket?.isClosed == false

    /** bind 후 목적지 connect/publish 전인 datagram socket. */
    private var pendingSocket: DatagramSocket? = null

    /** 명시적 [close]는 terminal이며 내부 timeout/I/O 실패와 구분한다. */
    @Volatile
    private var explicitlyClosed: Boolean = false

    /** 목적지 주소 — connect()에서 1회 해석 후 캐시 */
    @Volatile
    private var target: InetSocketAddress? = null

    /**
     * 로컬 DatagramSocket을 바인딩하고 목적지 주소를 해석한다.
     *
     * UDP는 비연결형이므로 TCP 핸드셰이크가 없다 — 호스트 미존재/네트워크 단절은
     * connect() 시점이 아니라 receive() 타임아웃으로 드러난다.
     *
     * 재시도 없음(회귀 검증): [ConnectionConfig.maxRetry]/[ConnectionConfig.retryBackoffMs]는
     * 사용하지 않는다 — 실패하면 단일 시도로 즉시 예외를 던진다(재시도는 TCP 전용,
     * [McSocketClient.connect] 참조).
     *
     * @throws ConnectionException 소켓 바인딩 실패 / 호스트명 해석 실패
     */
    suspend fun connect() {
        ensureNotExplicitlyClosed()
        try {
            val addr = withContext(Dispatchers.IO) {
                // DNS lookup에 타임아웃을 적용하기 위해 connectTimeoutMs 래핑 적용
                val inetAddress = kotlinx.coroutines.withTimeout(config.connectTimeoutMs) {
                    InetAddress.getByName(config.ip)
                }
                val resolved = InetSocketAddress(inetAddress, config.port)
                // localPort 지정 시 해당 소스 포트로 바인딩(모듈 고정 목적지 대응), null이면 임시 포트.
                val sock = socketFactory(localPort).also { s ->
                    lifecycleProbe.afterSocketCreated()
                    synchronized(lifecycleLock) {
                        if (explicitlyClosed) {
                            s.closeSilently()
                            throw explicitlyClosedFailure()
                        }
                        pendingSocket = s
                    }
                    lifecycleProbe.afterPendingPublished()
                    s.soTimeout = minOf(config.timeoutMs, RECEIVE_POLL_INTERVAL_MS).toInt()
                    // OS 레벨 destination filter: connect()를 호출하여 다른 발신처의 패킷 수신을 커널 단에서 차단
                    s.connect(resolved)
                    lifecycleProbe.afterConnectSucceeded()
                }
                Pair(sock, resolved)
            }
            synchronized(lifecycleLock) {
                if (explicitlyClosed || pendingSocket !== addr.first) {
                    addr.first.closeSilently()
                    throw explicitlyClosedFailure()
                }
                socket?.closeSilently()
                socket = addr.first
                target = addr.second
                pendingSocket = null
                connectionGeneration = Math.incrementExact(connectionGeneration)
            }
        } catch (e: CancellationException) {
            closePendingSocket()
            throw e
        } catch (e: Exception) {
            if (explicitlyClosed) {
                closePendingSocket()
                throw explicitlyClosedFailure(e)
            }
            closePendingSocket()
            clearActiveSocket()
            throw ConnectionException.connectFailed(config.ip, config.port, e)
        }
    }

    /**
     * [request] 바이트 배열을 PLC로 datagram 송신하고 응답 datagram을 반환한다.
     *
     * 소켓이 없으면 자동으로 [connect]를 1회 시도한다.
     * 응답 datagram을 그대로 반환한다 — 디코딩은 McFrameDecoder가 담당.
     * [validateSubheader]가 config.frameType × config.dataMode 4조합 기준으로 1차 검증한다.
     *
     * @return 응답 datagram 바이트 배열 (실제 수신 길이만큼 트림)
     * @throws ResponseTimeoutException 전체 응답 제한 초과 (무응답)
     * @throws ConnectionException      소켓 I/O 오류
     * @throws FrameException           응답 길이 부족 / Subheader 불일치
     */
    suspend fun sendAndReceive(request: ByteArray): ByteArray {
        ensureNotExplicitlyClosed()
        if (socket == null || socket?.isClosed == true) {
            connect()
        }

        ensureNotExplicitlyClosed()

        val sock = socket ?: throw ConnectionException("DatagramSocket is null after connect()")
        val dest = target ?: throw ConnectionException("Target address is null after connect()")

        return try {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation {
                        sock.closeSilently()
                        socket = null
                    }

                    try {
                        // 송신 — 단일 datagram
                        val txPacket = DatagramPacket(request, request.size, dest)
                        sock.send(txPacket)

                        val frame = receiveDatagram(sock)
                        validateSubheader(frame)
                        cont.resume(frame)
                    } catch (e: SocketTimeoutException) {
                        sock.closeSilently()
                        socket = null
                        cont.resumeWithException(ResponseTimeoutException(config.timeoutMs))
                    } catch (e: FrameException) {
                        sock.closeSilently()
                        socket = null
                        cont.resumeWithException(e)
                    } catch (e: Exception) {
                        if (cont.isCancelled) {
                            socket = null
                        } else {
                            sock.closeSilently()
                            socket = null
                            cont.resumeWithException(
                                ConnectionException("UDP I/O error: ${e.message}", e)
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    override fun close() {
        val active: DatagramSocket?
        val pending: DatagramSocket?
        synchronized(lifecycleLock) {
            explicitlyClosed = true
            active = socket
            pending = pendingSocket
            socket = null
            pendingSocket = null
            target = null
        }
        active?.closeSilently()
        if (pending !== active) pending?.closeSilently()
    }

    suspend fun closeAsync() = withContext(Dispatchers.IO) { close() }

    // ── 내부 유틸 ──────────────────────────────────────────────────────────

    private fun receiveDatagram(sock: DatagramSocket): ByteArray {
        val startedNanos = nanoTime()
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(config.timeoutMs)
        val rxBuf = ByteArray(receiveBufferSize)
        val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
        while (true) {
            ensureReceiveOpen(sock)
            val remainingNanos = timeoutNanos - (nanoTime() - startedNanos)
            if (remainingNanos <= 0L) throw SocketTimeoutException("UDP response deadline exceeded")
            // Round a positive fractional millisecond up: SO_TIMEOUT=0 means infinite wait.
            val remainingMillis = remainingNanos / 1_000_000L +
                if (remainingNanos % 1_000_000L == 0L) 0L else 1L
            sock.soTimeout = minOf(remainingMillis, RECEIVE_POLL_INTERVAL_MS).toInt()
            rxPacket.length = rxBuf.size
            try {
                sock.receive(rxPacket)
            } catch (_: SocketTimeoutException) {
                // Android native poll can outlive close(). Its short timeout returns control
                // here; terminal close wins over deadline on the next iteration. Never resend.
                continue
            }
            ensureReceiveOpen(sock)
            if (nanoTime() - startedNanos >= timeoutNanos) {
                throw SocketTimeoutException("UDP response deadline exceeded")
            }
            return rxBuf.copyOf(rxPacket.length)
        }
    }

    private fun ensureReceiveOpen(sock: DatagramSocket) {
        ensureNotExplicitlyClosed()
        if (sock.isClosed) throw ConnectionException("UDP socket is closed")
    }

    private fun ensureNotExplicitlyClosed() {
        if (explicitlyClosed) throw explicitlyClosedFailure()
    }

    private fun explicitlyClosedFailure(cause: Throwable? = null): ConnectionException =
        ConnectionException("UDP client is explicitly closed", cause)

    private fun closePendingSocket() {
        val pending = synchronized(lifecycleLock) {
            val value = pendingSocket
            pendingSocket = null
            value
        }
        pending?.closeSilently()
    }

    private fun clearActiveSocket() {
        val active = synchronized(lifecycleLock) {
            val value = socket
            socket = null
            target = null
            value
        }
        active?.closeSilently()
    }

    /**
     * 응답 datagram의 최소 길이와 Subheader를 config.frameType × config.dataMode 4분기로 검증한다.
     *
     * 잘못된 포트로 보낸 ICMP/엉뚱한 응답을 조기에 거른다.
     *
     * 4조합 기준:
     *   3E Binary : 선두 2 byte = D0 00, 최소 길이 HEADER_SIZE_3E(9)
     *   4E Binary : 선두 2 byte = D4 00, 최소 길이 HEADER_SIZE_4E(13)
     *   3E ASCII  : 선두 4 byte를 ASCII 디코드 = "D000", 최소 길이 HEADER_SIZE_3E_ASCII(18)
     *   4E ASCII  : 선두 4 byte를 ASCII 디코드 = "D400", 최소 길이 HEADER_SIZE_4E_ASCII(26)
     *
     * PlcResponseReader의 기존 상수(HEADER_SIZE_*, EXPECTED_SUBHEADER_*_ASCII)를 재사용한다.
     *
     * P3(회귀 검증): 최소 길이·Subheader 검증에 더해, 헤더가 선언한 RespDataLen과
     * 실제 datagram 길이(frame.size)의 정합을 검증한다. TCP([PlcResponseReader.readExactly])는
     * RespDataLen만큼 정확히 누적 수신해 정합이 항상 보장되지만, UDP는 1 datagram을 그대로
     * 수신하므로(잘리거나 위조된 datagram 포함) 이 보장이 없다 — 여기서 명시적으로 확인한다.
     * 정상 8조합 응답은 항상 `HEADER_SIZE_* + RespDataLen == frame.size`를 만족하므로
     * (송신측이 정확히 그 길이만큼만 datagram을 구성) 이 검증은 정상 경로를 거부하지 않는다.
     */
    private fun validateSubheader(frame: ByteArray) {
        when {
            config.frameType == FrameType.FRAME_3E && config.dataMode == DataMode.BINARY -> {
                val minSize = PlcResponseReader.HEADER_SIZE_3E
                if (frame.size < minSize) {
                    throw FrameException(
                        "UDP 3E Binary response too short: " +
                            "expected >= $minSize bytes, got ${frame.size}"
                    )
                }
                if (frame[0] != SUBHEADER_3E_HI || frame[1] != SUBHEADER_3E_LO) {
                    throw FrameException(
                        "Invalid UDP 3E Binary subheader: " +
                            "expected D0 00 (3E Binary), got " +
                            "%02X %02X".format(frame[0].toInt() and 0xFF, frame[1].toInt() and 0xFF)
                    )
                }
                // RespDataLen 2 byte LE (offset 7=L, 8=H) — PlcResponseReader.readFrame3E와 동일 파싱
                val respDataLen =
                    (frame[8].toInt() and 0xFF shl 8) or (frame[7].toInt() and 0xFF)
                validateLengthParity("UDP 3E Binary", minSize, respDataLen, frame.size)
            }

            config.frameType == FrameType.FRAME_4E && config.dataMode == DataMode.BINARY -> {
                val minSize = PlcResponseReader.HEADER_SIZE_4E
                if (frame.size < minSize) {
                    throw FrameException(
                        "UDP 4E Binary response too short: " +
                            "expected >= $minSize bytes, got ${frame.size}"
                    )
                }
                if (frame[0] != SUBHEADER_4E_HI || frame[1] != SUBHEADER_4E_LO) {
                    throw FrameException(
                        "Invalid UDP 4E Binary subheader: " +
                            "expected D4 00 (4E Binary), got " +
                            "%02X %02X".format(frame[0].toInt() and 0xFF, frame[1].toInt() and 0xFF)
                    )
                }
                // RespDataLen 2 byte LE (offset 11=L, 12=H) — PlcResponseReader.readFrame4E와 동일 파싱
                val respDataLen =
                    (frame[12].toInt() and 0xFF shl 8) or (frame[11].toInt() and 0xFF)
                validateLengthParity("UDP 4E Binary", minSize, respDataLen, frame.size)
            }

            config.frameType == FrameType.FRAME_3E && config.dataMode == DataMode.ASCII -> {
                val minSize = PlcResponseReader.HEADER_SIZE_3E_ASCII
                if (frame.size < minSize) {
                    throw FrameException(
                        "UDP 3E ASCII response too short: " +
                            "expected >= $minSize bytes, got ${frame.size}"
                    )
                }
                val actualSub = frame.copyOf(4).toString(Charsets.US_ASCII)
                if (!actualSub.equals(PlcResponseReader.EXPECTED_SUBHEADER_3E_ASCII, ignoreCase = true)) {
                    throw FrameException(
                        "Invalid UDP 3E ASCII subheader: " +
                            "expected \"${PlcResponseReader.EXPECTED_SUBHEADER_3E_ASCII}\" (3E ASCII), " +
                            "got \"$actualSub\" (hex: " +
                            frame.copyOf(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } +
                            ")"
                    )
                }
                // RespDataLen 4 hex char (offset 14..17) — PlcResponseReader.readFrame3EAscii와 동일 파싱
                val respDataLen = parseAsciiRespDataLen("UDP 3E ASCII", frame, 14, 18)
                validateLengthParity("UDP 3E ASCII", minSize, respDataLen, frame.size)
            }

            config.frameType == FrameType.FRAME_4E && config.dataMode == DataMode.ASCII -> {
                val minSize = PlcResponseReader.HEADER_SIZE_4E_ASCII
                if (frame.size < minSize) {
                    throw FrameException(
                        "UDP 4E ASCII response too short: " +
                            "expected >= $minSize bytes, got ${frame.size}"
                    )
                }
                val actualSub = frame.copyOf(4).toString(Charsets.US_ASCII)
                if (!actualSub.equals(PlcResponseReader.EXPECTED_SUBHEADER_4E_ASCII, ignoreCase = true)) {
                    throw FrameException(
                        "Invalid UDP 4E ASCII subheader: " +
                            "expected \"${PlcResponseReader.EXPECTED_SUBHEADER_4E_ASCII}\" (4E ASCII), " +
                            "got \"$actualSub\" (hex: " +
                            frame.copyOf(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } +
                            ")"
                    )
                }
                // RespDataLen 4 hex char (offset 22..25) — PlcResponseReader.readFrame4EAscii와 동일 파싱
                val respDataLen = parseAsciiRespDataLen("UDP 4E ASCII", frame, 22, 26)
                validateLengthParity("UDP 4E ASCII", minSize, respDataLen, frame.size)
            }

            else -> throw UnsupportedOperationException(
                "Unsupported UDP frame combination: " +
                    "frameType=${config.frameType}, dataMode=${config.dataMode}"
            )
        }
    }

    /**
     * ASCII 헤더의 RespDataLen 4 hex char 구간(frame[fromIndex, toIndex))을 파싱한다.
     * 파싱 불가/음수는 여기서 즉시 [FrameException]으로 거부한다(PlcResponseReader P2와 동일 근거).
     */
    private fun parseAsciiRespDataLen(label: String, frame: ByteArray, fromIndex: Int, toIndex: Int): Int {
        val str = frame.copyOfRange(fromIndex, toIndex).toString(Charsets.US_ASCII)
        val value = str.toIntOrNull(16)
            ?: throw FrameException("$label invalid RespDataLen: '$str'")
        if (value < 0) {
            throw FrameException("$label 응답 길이 음수: $value")
        }
        return value
    }

    /**
     * 헤더가 선언한 길이(headerSize + respDataLen)와 실제 datagram 길이(actualSize)의 정합을
     * 검증한다. 불일치 시 잘리거나 위조된 datagram으로 간주해 [FrameException]을 throw한다.
     */
    private fun validateLengthParity(label: String, headerSize: Int, respDataLen: Int, actualSize: Int) {
        val expectedTotal = headerSize + respDataLen
        if (actualSize != expectedTotal) {
            throw FrameException(
                "$label response length mismatch: header declares RespDataLen=$respDataLen " +
                    "(expected total $expectedTotal bytes), got $actualSize bytes"
            )
        }
    }

    private fun DatagramSocket.closeSilently() = try { close() } catch (_: Exception) { }

    companion object {
        private const val RECEIVE_POLL_INTERVAL_MS = 100L
        /**
         * 기본 수신 버퍼 (ASCII 960워드 응답 ≈ 3862B 수용, Binary 최대 1931B의 2배).
         * ASCII 응답은 Binary 대비 데이터가 2배이므로 4096 → 8192로 증액.
         */
        const val DEFAULT_RX_BUFFER = 8192

        // 3E Binary 응답 Subheader 바이트
        private const val SUBHEADER_3E_HI = 0xD0.toByte()
        private const val SUBHEADER_3E_LO = 0x00.toByte()

        // 4E Binary 응답 Subheader 바이트
        private const val SUBHEADER_4E_HI = 0xD4.toByte()
        private const val SUBHEADER_4E_LO = 0x00.toByte()
    }
}
