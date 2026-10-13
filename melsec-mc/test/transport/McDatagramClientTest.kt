// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.transport

import com.plciocheck.melsecmc.FrameException
import com.plciocheck.melsecmc.ResponseTimeoutException
import com.plciocheck.melsecmc.frame.DataMode
import com.plciocheck.melsecmc.frame.FrameType
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * McDatagramClient 통합 테스트 — 로컬 mock UDP 서버 기반
 *
 * 실물 PLC 없이 JVM 내 DatagramSocket으로 실제 UDP 왕복을 재현한다.
 * mock 서버는 별도 스레드에서 동작하며, 포트 0으로 OS가 빈 포트를 할당한다.
 *
 * 동시성 주의: 실제 소켓 IO가 포함되므로 runBlocking + @Timeout(JUnit)으로 안전 제한.
 */
class McDatagramClientTest {

    // ── 골든 요청/응답 프레임 (TCP 테스트와 동일 페이로드) ────────────────────

    /** D100 5 word Batch Read 요청 프레임 (MC 프로토콜 참조 골든) */
    private val goldenRequest: ByteArray = byteArrayOf(
        0x50, 0x00,
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
        0x0C, 0x00,
        0x10, 0x00,
        0x01, 0x04,
        0x00, 0x00,
        0x64, 0x00, 0x00, 0xA8.toByte(),
        0x05, 0x00
    )

    /** D100 5 word 정상 응답 프레임 (21 byte) */
    private val goldenResponse: ByteArray = byteArrayOf(
        0xD0.toByte(), 0x00,
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
        0x0C, 0x00,
        0x00, 0x00,
        0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04, 0x00, 0x05, 0x00
    )

    // ── TC-U1: connect → sendAndReceive 성공 ─────────────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U1 connect 후 sendAndReceive 정상 datagram 응답 수신`() {
        withEchoServer(goldenResponse) { port, serverJob ->
            val config = ConnectionConfig("127.0.0.1", port = port, timeoutMs = 3000L, maxRetry = 0)
            val client = McDatagramClient(config)
            runBlocking { client.connect() }
            val result = runBlocking { client.sendAndReceive(goldenRequest) }
            client.close()

            assertArrayEquals(goldenResponse, result, "UDP 응답 datagram 일치")
            serverJob.cancel()
        }
    }

    // ── TC-U2: timeout — 서버가 응답 안 보냄 ──────────────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U2 응답 없는 서버 — ResponseTimeoutException`() {
        withSilentServer { port, serverJob ->
            val config = ConnectionConfig("127.0.0.1", port = port, timeoutMs = 500L, maxRetry = 0)
            val client = McDatagramClient(config)
            runBlocking { client.connect() }

            var caught: Exception? = null
            try {
                runBlocking { client.sendAndReceive(goldenRequest) }
            } catch (e: ResponseTimeoutException) {
                caught = e
            }
            assertTrue(caught is ResponseTimeoutException, "ResponseTimeoutException이 throw되어야 함")
            client.close()
            serverJob.cancel()
        }
    }

    // ── TC-U3: Subheader 불일치 응답 — FrameException ─────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U3 Subheader 불일치 datagram — FrameException`() {
        val bad = goldenResponse.copyOf().also { it[0] = 0x50.toByte() }
        withEchoServer(bad) { port, serverJob ->
            val config = ConnectionConfig("127.0.0.1", port = port, timeoutMs = 3000L, maxRetry = 0)
            val client = McDatagramClient(config)
            runBlocking { client.connect() }

            var caught: Exception? = null
            try {
                runBlocking { client.sendAndReceive(goldenRequest) }
            } catch (e: FrameException) {
                caught = e
            }
            assertTrue(caught is FrameException, "FrameException이 throw되어야 함")
            client.close()
            serverJob.cancel()
        }
    }

    // ── TC-U4: 대용량 datagram — 960워드 응답 재조립 ──────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U4 대용량 datagram(960워드 상당) 단일 수신 완전성`() {
        // 헤더 9 + RespDataLen(EndCode2 + 1920) → 전체 1931 byte
        val dataLen = 2 + 1920
        val big = ByteArray(9 + dataLen)
        big[0] = 0xD0.toByte(); big[1] = 0x00
        big[7] = (dataLen and 0xFF).toByte()
        big[8] = ((dataLen ushr 8) and 0xFF).toByte()
        // EndCode 0000, 이후 1920 byte는 0으로 채워진 더미 데이터
        for (i in 11 until big.size) big[i] = (i and 0xFF).toByte()

        withEchoServer(big) { port, serverJob ->
            val config = ConnectionConfig("127.0.0.1", port = port, timeoutMs = 3000L, maxRetry = 0)
            val client = McDatagramClient(config)
            runBlocking { client.connect() }
            val result = runBlocking { client.sendAndReceive(goldenRequest) }
            client.close()

            assertEquals(big.size, result.size, "대용량 datagram 전체 길이 일치")
            assertArrayEquals(big, result, "대용량 datagram 내용 일치")
            serverJob.cancel()
        }
    }

    // ── TC-U5: 취소 — 소켓 정리 ───────────────────────────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U5 sendAndReceive 취소 시 CancellationException 전파`() {
        val cancelled = AtomicBoolean(false)
        withSilentServer { port, serverJob ->
            val config = ConnectionConfig("127.0.0.1", port = port, timeoutMs = 5000L, maxRetry = 0)
            val client = McDatagramClient(config)
            runBlocking { client.connect() }

            runBlocking {
                val job = launch(Dispatchers.IO) {
                    try {
                        client.sendAndReceive(goldenRequest)
                    } catch (e: CancellationException) {
                        cancelled.set(true)
                        throw e
                    }
                }
                delay(150)
                job.cancel()
                job.join()
            }
            assertTrue(cancelled.get(), "CancellationException이 전파되어야 함")
            client.close()
            serverJob.cancel()
        }
    }

    // ── TC-U6: 로컬 소스 포트 바인딩 — 지정 포트로 송신 ──────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U6 localPort 지정 시 해당 소스 포트로 바인딩되어 송수신`() {
        val boundSrcPort = AtomicReference<Int>(0)
        // OS가 비워 둔 포트를 받아 localPort로 사용 (충돌 회피)
        val freePort = DatagramSocket(0).use { it.localPort }

        val serverSocket = DatagramSocket(0)
        val port = serverSocket.localPort
        val serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val buf = ByteArray(4096)
                val rx = DatagramPacket(buf, buf.size)
                serverSocket.receive(rx)
                boundSrcPort.set(rx.port) // 클라이언트 소스 포트 캡처
                serverSocket.send(DatagramPacket(goldenResponse, goldenResponse.size, rx.address, rx.port))
            } catch (_: Exception) { }
        }

        try {
            val config = ConnectionConfig("127.0.0.1", port = port, timeoutMs = 3000L, maxRetry = 0)
            val client = McDatagramClient(config, localPort = freePort)
            runBlocking { client.connect() }
            val result = runBlocking { client.sendAndReceive(goldenRequest) }
            client.close()

            assertArrayEquals(goldenResponse, result, "localPort 바인딩 응답 일치")
            assertEquals(freePort, boundSrcPort.get(), "송신 소스 포트가 지정한 localPort와 일치")
        } finally {
            serverJob.cancel()
            serverSocket.close()
        }
    }

    // ── TC-U7 ~ TC-U11: 4E/ASCII validateSubheader 4분기 검증 ────────────────

    /**
     * TC-U7: 4E Binary config — D4 00 subheader 응답을 정상 수용한다.
     *
     * 합성 응답(D4 00 + 더미 13바이트 헤더 + EndCode 0000)을 mock 서버가 반환하면
     * validateSubheader가 거부하지 않고 frame을 그대로 전달해야 한다.
     */
    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U7 4E Binary config — D4 00 subheader 정상 수용`() {
        // 4E Binary 최소 응답: 헤더 13 byte + EndCode 2 byte = 15 byte
        val golden4EBin = ByteArray(15).also {
            it[0] = 0xD4.toByte(); it[1] = 0x00  // Subheader D4 00
            // [2..3] Serial echo, [4..5] Fixed 00 00, [6..10] Access Route (all 0)
            // [11..12] RespDataLen = 2 (EndCode only), LE
            it[11] = 0x02; it[12] = 0x00
            // [13..14] EndCode = 0000
            it[13] = 0x00; it[14] = 0x00
        }

        withEchoServer(golden4EBin) { port, serverJob ->
            val config = ConnectionConfig(
                "127.0.0.1", port = port, timeoutMs = 3000L, maxRetry = 0,
                frameType = FrameType.FRAME_4E, dataMode = DataMode.BINARY
            )
            val client = McDatagramClient(config)
            runBlocking { client.connect() }
            val result = runBlocking { client.sendAndReceive(goldenRequest) }
            client.close()

            assertArrayEquals(golden4EBin, result, "4E Binary: D4 00 응답 datagram 수용")
            serverJob.cancel()
        }
    }

    /**
     * TC-U8: 3E ASCII config — "D000" subheader 응답을 정상 수용한다.
     *
     * ASCII 최소 응답 18 char = Subheader(4)+"0000000000"(AccessRoute 10)+"0004"(RespDataLen 4)
     * 뒤에 body 4 char(EndCode "0000") 추가 = 22 byte.
     */
    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U8 3E ASCII config — D000 subheader 정상 수용`() {
        // 3E ASCII 최소 응답: 헤더 18 char + EndCode "0000" 4 char = 22 byte
        // "D000" + AccessRoute "0000000000"(10char) + RespDataLen "0004" + EndCode "0000"
        val golden3EAscii = "D000" + "0000000000" + "0004" + "0000"
        val frame = golden3EAscii.toByteArray(Charsets.US_ASCII)

        withEchoServer(frame) { port, serverJob ->
            val config = ConnectionConfig(
                "127.0.0.1", port = port, timeoutMs = 3000L, maxRetry = 0,
                frameType = FrameType.FRAME_3E, dataMode = DataMode.ASCII
            )
            val client = McDatagramClient(config)
            runBlocking { client.connect() }
            val result = runBlocking { client.sendAndReceive(goldenRequest) }
            client.close()

            assertArrayEquals(frame, result, "3E ASCII: D000 응답 datagram 수용")
            serverJob.cancel()
        }
    }

    /**
     * TC-U9: 4E ASCII config — "D400" subheader 응답을 정상 수용한다.
     *
     * 4E ASCII 최소 응답 26 char = Subheader(4)+Serial(4)+Fixed(4)+AccessRoute(10)+RespDataLen(4)
     * 뒤에 body 4 char(EndCode "0000") = 30 byte.
     */
    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U9 4E ASCII config — D400 subheader 정상 수용`() {
        // "D400" + Serial "1234" + Fixed "0000" + AccessRoute "0000000000" + RespDataLen "0004"
        // + EndCode "0000"
        val golden4EAscii = "D400" + "1234" + "0000" + "0000000000" + "0004" + "0000"
        val frame = golden4EAscii.toByteArray(Charsets.US_ASCII)

        withEchoServer(frame) { port, serverJob ->
            val config = ConnectionConfig(
                "127.0.0.1", port = port, timeoutMs = 3000L, maxRetry = 0,
                frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII
            )
            val client = McDatagramClient(config)
            runBlocking { client.connect() }
            val result = runBlocking { client.sendAndReceive(goldenRequest) }
            client.close()

            assertArrayEquals(frame, result, "4E ASCII: D400 응답 datagram 수용")
            serverJob.cancel()
        }
    }

    /**
     * TC-U10: subheader 조합 불일치 — 4E Binary config인데 D0 00(3E Binary) 응답 수신.
     *
     * validateSubheader가 FrameException으로 거부해야 한다.
     */
    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U10 조합 불일치 — 4E Binary config에 D0 00 응답 FrameException`() {
        // 4E Binary config, 그러나 mock 서버가 3E Binary subheader(D0 00)를 응답
        val mismatch = ByteArray(15).also {
            it[0] = 0xD0.toByte(); it[1] = 0x00  // 3E Binary subheader — 4E config와 불일치
            it[11] = 0x02; it[12] = 0x00
        }

        withEchoServer(mismatch) { port, serverJob ->
            val config = ConnectionConfig(
                "127.0.0.1", port = port, timeoutMs = 3000L, maxRetry = 0,
                frameType = FrameType.FRAME_4E, dataMode = DataMode.BINARY
            )
            val client = McDatagramClient(config)
            runBlocking { client.connect() }

            var caught: Exception? = null
            try {
                runBlocking { client.sendAndReceive(goldenRequest) }
            } catch (e: FrameException) {
                caught = e
            }
            assertTrue(caught is FrameException, "4E Binary config + D0 00 응답 → FrameException")
            val msg = (caught as FrameException).message ?: ""
            assertTrue(msg.contains("D4 00"), "에러 메시지에 기대 subheader(D4 00) 명시: $msg")
            client.close()
            serverJob.cancel()
        }
    }

    /**
     * TC-U11: subheader 조합 불일치 — 3E ASCII config인데 Binary D0 00 응답 수신.
     *
     * ASCII config에서 Binary subheader(D0 00)를 수신하면 "D000" 문자열 불일치로
     * FrameException이 throw되어야 한다.
     */
    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U11 조합 불일치 — 3E ASCII config에 Binary D0 00 응답 FrameException`() {
        // 3E ASCII config, 그러나 mock 서버가 Binary 3E 응답(D0 00)을 반환
        val mismatch = goldenResponse.copyOf()  // goldenResponse는 D0 00 시작 Binary 21 byte

        withEchoServer(mismatch) { port, serverJob ->
            val config = ConnectionConfig(
                "127.0.0.1", port = port, timeoutMs = 3000L, maxRetry = 0,
                frameType = FrameType.FRAME_3E, dataMode = DataMode.ASCII
            )
            val client = McDatagramClient(config)
            runBlocking { client.connect() }

            var caught: Exception? = null
            try {
                runBlocking { client.sendAndReceive(goldenRequest) }
            } catch (e: FrameException) {
                caught = e
            }
            assertTrue(caught is FrameException, "3E ASCII config + Binary D0 00 응답 → FrameException")
            val msg = (caught as FrameException).message ?: ""
            assertTrue(
                msg.contains("D000"),
                "에러 메시지에 기대 subheader(D000) 명시: $msg"
            )
            client.close()
            serverJob.cancel()
        }
    }

    // ── mock UDP 서버 헬퍼 ────────────────────────────────────────────────────

    /** 요청 datagram 수신 후 [response]를 송신처로 되돌려 보내는 echo 서버. */
    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U12 close가 local register보다 먼저면 UDP destination connect에 진입하지 않는다`() {
        val created = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pendingPublished = AtomicInteger(0)
        val probe = object : DatagramLifecycleProbe {
            override fun afterSocketCreated() {
                created.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS), "datagram factory barrier release")
            }
            override fun afterPendingPublished() { pendingPublished.incrementAndGet() }
        }
        val client = McDatagramClient(
            ConnectionConfig("127.0.0.1", port = 9, timeoutMs = 500L, maxRetry = 0),
            McDatagramClient.DEFAULT_RX_BUFFER,
            null,
            probe,
        )

        val result = runBlocking {
            val connect = async(Dispatchers.IO) { runCatching { client.connect() } }
            assertTrue(created.await(3, TimeUnit.SECONDS), "local DatagramSocket 생성 확인")
            client.close()
            release.countDown()
            connect.await()
        }

        assertTrue(result.exceptionOrNull() is com.plciocheck.melsecmc.ConnectionException)
        assertEquals(0, pendingPublished.get(), "terminal close 뒤 pending/connect admission 금지")
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U13 pending UDP socket은 close가 destination connect 전에 즉시 닫는다`() {
        val pending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connectSucceeded = AtomicInteger(0)
        val probe = object : DatagramLifecycleProbe {
            override fun afterPendingPublished() {
                pending.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS), "pending barrier release")
            }
            override fun afterConnectSucceeded() { connectSucceeded.incrementAndGet() }
        }
        val client = McDatagramClient(
            ConnectionConfig("127.0.0.1", port = 9, timeoutMs = 500L, maxRetry = 0),
            McDatagramClient.DEFAULT_RX_BUFFER,
            null,
            probe,
        )

        val result = runBlocking {
            val connect = async(Dispatchers.IO) { runCatching { client.connect() } }
            assertTrue(pending.await(3, TimeUnit.SECONDS), "pending DatagramSocket 게시 확인")
            client.close()
            release.countDown()
            connect.await()
        }

        assertTrue(result.exceptionOrNull() is com.plciocheck.melsecmc.ConnectionException)
        assertEquals(0, connectSucceeded.get(), "close된 pending DatagramSocket connect 성공 금지")
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U14 UDP destination connect 성공 뒤 publish 전 close면 활성 socket으로 게시하지 않는다`() {
        val connected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probe = object : DatagramLifecycleProbe {
            override fun afterConnectSucceeded() {
                connected.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS), "UDP publish barrier release")
            }
        }
        val client = McDatagramClient(
            ConnectionConfig("127.0.0.1", port = 9, timeoutMs = 500L, maxRetry = 0),
            McDatagramClient.DEFAULT_RX_BUFFER,
            null,
            probe,
        )

        val result = runBlocking {
            val connect = async(Dispatchers.IO) { runCatching { client.connect() } }
            assertTrue(connected.await(3, TimeUnit.SECONDS), "UDP destination connect 성공 확인")
            client.close()
            release.countDown()
            connect.await()
        }

        assertTrue(result.exceptionOrNull() is com.plciocheck.melsecmc.ConnectionException)
        val sendFailure = runBlocking { runCatching { client.sendAndReceive(goldenRequest) } }
        assertTrue(sendFailure.exceptionOrNull() is com.plciocheck.melsecmc.ConnectionException)
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-U15 explicit close 뒤 send는 UDP auto-connect를 시도하지 않는다`() {
        val socketCreates = AtomicInteger(0)
        val probe = object : DatagramLifecycleProbe {
            override fun afterSocketCreated() { socketCreates.incrementAndGet() }
        }
        val client = McDatagramClient(
            ConnectionConfig("127.0.0.1", port = 9, timeoutMs = 500L, maxRetry = 3),
            McDatagramClient.DEFAULT_RX_BUFFER,
            null,
            probe,
        )

        client.close()
        val result = runBlocking { runCatching { client.sendAndReceive(goldenRequest) } }

        assertTrue(result.exceptionOrNull() is com.plciocheck.melsecmc.ConnectionException)
        assertEquals(0, socketCreates.get(), "terminal close 뒤 UDP auto-connect는 0건이어야 한다")
    }

    private fun withEchoServer(response: ByteArray, block: (port: Int, serverJob: Job) -> Unit) {
        val serverSocket = DatagramSocket(0)
        val port = serverSocket.localPort
        val serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val buf = ByteArray(4096)
                val rx = DatagramPacket(buf, buf.size)
                serverSocket.receive(rx)
                val tx = DatagramPacket(response, response.size, rx.address, rx.port)
                serverSocket.send(tx)
            } catch (_: Exception) { }
        }
        try {
            block(port, serverJob)
        } finally {
            serverJob.cancel()
            serverSocket.close()
        }
    }

    /** 요청을 수신만 하고 응답하지 않는 서버 (timeout 테스트용). */
    private fun withSilentServer(block: (port: Int, serverJob: Job) -> Unit) {
        val serverSocket = DatagramSocket(0)
        val port = serverSocket.localPort
        val serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val buf = ByteArray(4096)
                serverSocket.receive(DatagramPacket(buf, buf.size))
                delay(30_000)
            } catch (_: Exception) { }
        }
        try {
            block(port, serverJob)
        } finally {
            serverJob.cancel()
            serverSocket.close()
        }
    }

    // DNS 조회 타임아웃(5-5)에 대한 결정적 단위테스트는 두지 않는다 — 이유(회귀 검증 검수):
    //  - withTimeout 은 블로킹 InetAddress.getByName 을 실제로 중단하지 못한다(협조적 취소 지점 없음).
    //    getByName 반환 후에야 timeout 판정 → best-effort. 진짜 hang DNS 는 OS 타임아웃까지 블록된다.
    //  - 실호스트(www.google.com)+1ms 테스트는 DNS 캐시 속도에 따라 timeout 발동 여부가 갈려 비결정적(플레이키)이라 제거.
    //  - 결정적 검증은 resolver 주입 리팩터가 전제(낮음 우선순위 — 본 차수 범위 밖).
    // s.connect(resolved) UDP 소스 필터는 기존 송수신 테스트(TC-U*)가 회귀 방어한다.
}
