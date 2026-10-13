// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.transport

import com.plciocheck.melsecmc.ConnectionException
import com.plciocheck.melsecmc.McProtocolException
import com.plciocheck.melsecmc.ResponseTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Coroutine 기반 MC Protocol TCP 클라이언트
 *
 * 역할: TCP 연결 유지 + 3E Binary 프레임 송수신.
 * 프레임 인코딩/디코딩은 이 클래스의 책임 밖 — McFrameEncoder/Decoder가 담당.
 *
 * 사용 패턴:
 * ```kotlin
 * val client = McSocketClient(ConnectionConfig("192.0.2.10"))
 * client.connect()
 * val response = client.sendAndReceive(requestFrame)
 * client.close()
 * ```
 *
 * 또는 use 블록:
 * ```kotlin
 * McSocketClient(config).use { client ->
 *     client.connect()
 *     client.sendAndReceive(frame)
 * }
 * ```
 *
 * 동시성 주의:
 *   - [sendAndReceive]는 동시 호출 안전 보장 없음. 단일 스레드에서 순차 호출할 것.
 *   - 취소(CancellationException) 시 소켓을 닫아 블로킹 read()를 강제 인터럽트한다.
 *   - soTimeout = timeoutMs: 개별 read() 단위 타임아웃 → SocketTimeoutException → ResponseTimeoutException.
 *   - withTimeout(timeoutMs): 프레임 전체 타임아웃(슬로우 드립 방어) — soTimeout이 각 read를 커버해도
 *     총 수신 시간이 초과하면 invokeOnCancellation으로 소켓을 닫아 blocking read()를 인터럽트한다.
 *
 * 재연결 정책 (MC 프로토콜 참조 FrequencyError backoff와 일관):
 *   연결 실패/끊김 시 [config.retryBackoffMs] 대기 후 지수 backoff 재시도.
 *   [config.maxRetry] 초과 시 ConnectionException throw.
 */
internal interface SocketLifecycleProbe {
    fun afterSocketCreated() = Unit
    fun afterPendingPublished() = Unit
    fun afterConnectSucceeded() = Unit
}

private object NoOpSocketLifecycleProbe : SocketLifecycleProbe

class McSocketClient internal constructor(
    private val config: ConnectionConfig,
    private val lifecycleProbe: SocketLifecycleProbe,
) : Closeable {

    constructor(config: ConnectionConfig) : this(config, NoOpSocketLifecycleProbe)

    private val lifecycleLock = Any()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    var connectionGeneration: Long = 0L
        private set

    val isTransportConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    /** connect syscall 전/중이지만 아직 활성 소켓으로 발행되지 않은 인스턴스. */
    private var pendingSocket: Socket? = null

    /** 명시적 [close]는 이 인스턴스의 terminal 전이다. 내부 I/O 실패와 구분한다. */
    @Volatile
    private var explicitlyClosed: Boolean = false

    /** retry backoff를 explicit close 즉시 깨우는 terminal signal. */
    private val explicitCloseSignal = CompletableDeferred<Unit>()

    /**
     * PLC에 TCP 연결을 맺는다.
     *
     * [config.maxRetry]번 재시도. 각 시도 실패 시 지수 backoff 대기.
     * 모두 실패하면 [ConnectionException] throw.
     *
     * @throws ConnectionException 재시도 소진 후 연결 불가
     */
    suspend fun connect() {
        ensureNotExplicitlyClosed()
        var lastCause: Throwable? = null
        var backoffMs = config.retryBackoffMs

        for (attempt in 0..config.maxRetry) {
            ensureNotExplicitlyClosed()
            if (attempt > 0) {
                val closedDuringBackoff = withTimeoutOrNull(backoffMs) {
                    explicitCloseSignal.await()
                    true
                } == true
                if (closedDuringBackoff) throw explicitlyClosedFailure()
                ensureNotExplicitlyClosed()
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L) // 최대 30초
            }

            try {
                val sock = withContext(Dispatchers.IO) {
                    val s = Socket()
                    lifecycleProbe.afterSocketCreated()
                    try {
                        synchronized(lifecycleLock) {
                            if (explicitlyClosed) {
                                s.closeSilently()
                                throw explicitlyClosedFailure()
                            }
                            pendingSocket = s
                        }
                        lifecycleProbe.afterPendingPublished()
                        s.connect(
                            InetSocketAddress(config.ip, config.port),
                            config.connectTimeoutMs.toInt()
                        )
                        lifecycleProbe.afterConnectSucceeded()
                        // soTimeout: 블로킹 read()가 timeoutMs 후 SocketTimeoutException throw
                        s.soTimeout = config.timeoutMs.toInt()
                        s.tcpNoDelay = true
                        s
                    } catch (t: Throwable) {
                        // 4-1: connect 실패 시 이미 할당된 FD 해제.
                        // 상위 catch의 socket?.closeSilently()는 멤버 필드(보통 null)를 닫으므로
                        // 지역 변수 s(새 소켓)를 직접 닫아야 한다.
                        runCatching { s.close() }
                        throw t
                    }
                }
                synchronized(lifecycleLock) {
                    if (explicitlyClosed || pendingSocket !== sock) {
                        sock.closeSilently()
                        throw explicitlyClosedFailure()
                    }
                    socket?.closeSilently()
                    socket = sock
                    pendingSocket = null
                    connectionGeneration = Math.incrementExact(connectionGeneration)
                }
                return // 연결 성공
            } catch (e: CancellationException) {
                closePendingSocket()
                throw e // 코루틴 취소 — 재시도하지 않고 전파
            } catch (e: Exception) {
                if (explicitlyClosed) {
                    closePendingSocket()
                    throw explicitlyClosedFailure(e)
                }
                lastCause = e
                // 진단 계측(회귀 검증) — connect 재시도 가시화. sink=null(release)이면 무비용.
                // PlcDiag 가 read 만 계측해 connect 재시도가 안 보이던 사각 해소(가설5 슬롯 stale 진단).
                PlcDiag.log("PlcDiag-Connect") {
                    "connect-fail attempt=$attempt type=${e.javaClass.simpleName} msg=${e.message}" +
                    " ep=${config.ip}:${config.port}"
                }
                closePendingSocket()
                clearActiveSocket()
            }
        }

        PlcDiag.log("PlcDiag-Connect") {
            "connect-exhausted maxRetry=${config.maxRetry} ep=${config.ip}:${config.port}"
        }
        throw ConnectionException.retriesExhausted(
            config.ip,
            config.port,
            config.maxRetry,
            lastCause ?: RuntimeException("Unknown error")
        )
    }

    /**
     * [request] 바이트 배열을 PLC로 보내고 완전한 응답 프레임을 반환한다.
     *
     * 응답 수신은 [PlcResponseReader.readFrame]으로 처리 — 부분 수신 누적 포함.
     * [config.timeoutMs] 내에 응답이 완료되지 않으면 [ResponseTimeoutException] throw.
     *
     * 소켓이 없으면 자동으로 [connect]를 1회 시도한다.
     * 취소(CancellationException) 시 소켓을 닫고 예외를 전파한다.
     *
     * @return 완전한 3E Binary 응답 바이트 배열
     * @throws ResponseTimeoutException 타임아웃
     * @throws ConnectionException      소켓 I/O 오류 / EOF
     * @throws FrameException           Subheader 불일치 등 프레임 파싱 오류
     */
    suspend fun sendAndReceive(request: ByteArray): ByteArray {
        ensureNotExplicitlyClosed()
        if (socket?.isConnected != true || socket?.isClosed == true) {
            connect()
        }

        ensureNotExplicitlyClosed()

        val sock = socket ?: throw ConnectionException("Socket is null after connect()")

        // soTimeout(개별 read 단위) + withTimeout(프레임 전체) 이중 보호:
        //   soTimeout — connect()에서 설정. 각 read()가 timeoutMs 내 데이터를 받지 못하면
        //               SocketTimeoutException → ResponseTimeoutException.
        //   withTimeout — 슬로우 드립 방어. 각 read는 soTimeout 내 완료되지만
        //                 총 수신 시간이 timeoutMs 초과 시 코루틴 취소 →
        //                 invokeOnCancellation에서 소켓 close → blocking read 인터럽트 →
        //                 TimeoutCancellationException → ResponseTimeoutException.
        return try {
            withTimeout(config.timeoutMs) {    // 4-2: 프레임 전체 타임아웃(슬로우 드립 방어)
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    // 취소 시 소켓 close → 블로킹 read() 즉시 중단
                    cont.invokeOnCancellation {
                        sock.closeSilently()
                        socket = null
                    }

                    // 진단용 송신 시각 기준점 — catch에서 경과 ms를 계산하기 위해 try 밖에 선언.
                    // 동작(송수신 흐름)에 영향 없음. nanoTime은 순수 Kotlin 허용.
                    val t0 = System.nanoTime()

                    try {
                        // 송신
                        sock.getOutputStream().write(request)
                        sock.getOutputStream().flush()

                        // 수신 — 부분 수신 누적 (soTimeout 기반 블로킹 IO 타임아웃 적용)
                        // frameType × dataMode를 전달해 3E/4E × Binary/ASCII 분기
                        val result = PlcResponseReader.readFrame(
                            sock.getInputStream(),
                            config.frameType,
                            config.dataMode
                        )
                        cont.resume(result)
                    } catch (e: SocketTimeoutException) {
                        // 진단 계측 — 로그 삽입만, 동작 무변경
                        PlcDiag.log("PlcDiag") {
                            "type=SocketTimeoutException msg=${e.message}" +
                            " sentLen=${request.size} sentHex=${PlcDiag.hex(request)}" +
                            " elapsedMs=${(System.nanoTime() - t0) / 1_000_000}" +
                            " conn=${sock.isConnected} closed=${sock.isClosed} inShut=${sock.isInputShutdown}" +
                            " ft=${config.frameType} dm=${config.dataMode} to=${config.timeoutMs}" +
                            " ep=${config.ip}:${config.port}"
                        }
                        sock.closeSilently()
                        socket = null
                        cont.resumeWithException(ResponseTimeoutException(config.timeoutMs))
                    } catch (e: McProtocolException) {
                        PlcDiag.log("PlcDiag") {
                            "type=McProtocolException msg=${e.message}" +
                            " sentLen=${request.size} sentHex=${PlcDiag.hex(request)}" +
                            " elapsedMs=${(System.nanoTime() - t0) / 1_000_000}" +
                            " conn=${sock.isConnected} closed=${sock.isClosed} inShut=${sock.isInputShutdown}" +
                            " ft=${config.frameType} dm=${config.dataMode} to=${config.timeoutMs}" +
                            " ep=${config.ip}:${config.port}"
                        }
                        sock.closeSilently()
                        socket = null
                        cont.resumeWithException(e)
                    } catch (e: Exception) {
                        if (cont.isCancelled) {
                            // 취소로 인한 SocketException 등 — CancellationException으로 처리.
                            // 통신 오류와 구분하기 위해 별도 태그 사용.
                            PlcDiag.log("PlcDiag-Cancel") {
                                "type=${e.javaClass.simpleName} msg=${e.message}" +
                                " sentLen=${request.size} sentHex=${PlcDiag.hex(request)}" +
                                " elapsedMs=${(System.nanoTime() - t0) / 1_000_000}" +
                                " conn=${sock.isConnected} closed=${sock.isClosed} inShut=${sock.isInputShutdown}" +
                                " ft=${config.frameType} dm=${config.dataMode} to=${config.timeoutMs}" +
                                " ep=${config.ip}:${config.port}"
                            }
                            socket = null
                        } else {
                            PlcDiag.log("PlcDiag") {
                                "type=${e.javaClass.simpleName} msg=${e.message}" +
                                " sentLen=${request.size} sentHex=${PlcDiag.hex(request)}" +
                                " elapsedMs=${(System.nanoTime() - t0) / 1_000_000}" +
                                " conn=${sock.isConnected} closed=${sock.isClosed} inShut=${sock.isInputShutdown}" +
                                " ft=${config.frameType} dm=${config.dataMode} to=${config.timeoutMs}" +
                                " ep=${config.ip}:${config.port}"
                            }
                            sock.closeSilently()
                            socket = null
                            cont.resumeWithException(ConnectionException("Socket I/O error: ${e.message}", e))
                        }
                    }
                }
            }
            }    // withTimeout 닫기
        } catch (e: TimeoutCancellationException) {
            // 4-2: 프레임 전체 타임아웃 — 슬로우 드립으로 soTimeout이 발동하지 않았지만
            // 총 수신 시간이 config.timeoutMs 초과. 소켓은 invokeOnCancellation에서 이미 닫힘.
            throw ResponseTimeoutException(config.timeoutMs)
        } catch (e: CancellationException) {
            // 외부 코루틴 취소 전파 — 소켓은 invokeOnCancellation에서 이미 닫힘
            throw e
        }
    }

    /**
     * 소켓을 닫는다. 이미 닫혀 있으면 무시.
     *
     * suspend 없음 — [Closeable.close]와 use 블록 호환.
     */
    override fun close() {
        val active: Socket?
        val pending: Socket?
        synchronized(lifecycleLock) {
            explicitlyClosed = true
            explicitCloseSignal.complete(Unit)
            active = socket
            pending = pendingSocket
            socket = null
            pendingSocket = null
        }
        active?.closeSilently()
        if (pending !== active) pending?.closeSilently()
    }

    /**
     * 소켓을 닫는다 (suspend 버전, 코루틴 컨텍스트에서 명시적으로 사용할 때).
     */
    suspend fun closeAsync() = withContext(Dispatchers.IO) { close() }

    // ── 내부 유틸 ──────────────────────────────────────────────────────────

    private fun ensureNotExplicitlyClosed() {
        if (explicitlyClosed) throw explicitlyClosedFailure()
    }

    private fun explicitlyClosedFailure(cause: Throwable? = null): ConnectionException =
        ConnectionException("TCP client is explicitly closed", cause)

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
            value
        }
        active?.closeSilently()
    }

    private fun Socket.closeSilently() = try { close() } catch (_: Exception) { }
}
