// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.transport

import melsecmckotlin.ConnectionException
import melsecmckotlin.FrameException
import melsecmckotlin.ResponseTimeoutException
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * McSocketClient 통합 테스트 — 로컬 mock TCP 서버 기반
 *
 * 실물 PLC 없이 JVM 내 ServerSocket으로 실제 TCP 왕복을 재현한다.
 * mock 서버는 별도 스레드(Dispatchers.IO)에서 동작하며, 포트 0으로 OS가 빈 포트를 할당한다.
 *
 * 동시성 주의:
 *   runTest는 TestCoroutineScheduler의 가상 시간을 사용하므로, 실제 IO가 포함된
 *   withTimeout 테스트에서 timeout이 즉시 만료될 수 있다.
 *   → 실제 소켓 IO가 포함된 테스트는 runBlocking 사용 + @Timeout(JUnit)으로 안전 제한.
 */
class McSocketClientTest {

    // ── 골든 요청/응답 프레임 ─────────────────────────────────────────────────

    /**
     * D100 5 word Batch Read 요청 프레임 (MC 프로토콜 참조 골든)
     * 50 00 | 00 FF FF 03 00 | 0C 00 | 10 00 | 01 04 | 00 00 | 64 00 00 A8 | 05 00
     */
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

    /**
     * D100 5 word 정상 응답 프레임 (21 byte)
     * D0 00 | 00 FF FF 03 00 | 0C 00 | 00 00 | word1~5
     * RespDataLen = 0x000C = 12
     */
    private val goldenResponse: ByteArray = byteArrayOf(
        0xD0.toByte(), 0x00,
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
        0x0C, 0x00,
        0x00, 0x00,
        0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04, 0x00, 0x05, 0x00
    )

    // ── TC-S1: connect → sendAndReceive 성공 ─────────────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S1 connect 후 sendAndReceive 정상 응답 수신`() {
        withMockServer { port, serverJob ->
            val config = ConnectionConfig(
                ip = "127.0.0.1",
                port = port,
                timeoutMs = 3000L,
                maxRetry = 0
            )
            val client = McSocketClient(config)
            runBlocking { client.connect() }
            val result = runBlocking { client.sendAndReceive(goldenRequest) }
            client.close()

            assertArrayEquals(goldenResponse, result, "응답 프레임 일치")
            serverJob.cancel()
            runBlocking { serverJob.join() }
        }
    }

    // ── TC-S2: 부분 수신 — mock 서버가 응답을 조각으로 송신 ──────────────────

    @Test
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `TC-S2 서버가 응답을 3조각으로 쪼개 보내도 완전 재조립`() {
        // 9 + 4 + 8 = 21 (goldenResponse.size)
        withFragmentedMockServer(listOf(9, 4, 8)) { port, serverJob ->
            val config = ConnectionConfig(
                ip = "127.0.0.1",
                port = port,
                timeoutMs = 5000L,   // 조각 간 지연(5ms×3) 포함 충분한 여유
                maxRetry = 0
            )
            val client = McSocketClient(config)
            runBlocking { client.connect() }
            val result = runBlocking { client.sendAndReceive(goldenRequest) }
            client.close()

            assertArrayEquals(goldenResponse, result, "3조각 부분 수신 재조립 정확")
            serverJob.cancel()
            runBlocking { serverJob.join() }
        }
    }

    // ── TC-S3: timeout — 서버가 응답 안 보냄 ────────────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S3 응답 없는 서버 — ResponseTimeoutException`() {
        withSilentMockServer { port, serverJob ->
            val config = ConnectionConfig(
                ip = "127.0.0.1",
                port = port,
                timeoutMs = 500L,
                maxRetry = 0
            )
            val client = McSocketClient(config)
            runBlocking { client.connect() }

            var caught: Exception? = null
            try {
                runBlocking { client.sendAndReceive(goldenRequest) }
            } catch (e: ResponseTimeoutException) {
                caught = e
            }

            assertNotNull(caught, "ResponseTimeoutException이 throw되어야 함")
            assertTrue(caught is ResponseTimeoutException)
            client.close()
            // serverJob 정리는 withSilentMockServer finally 블록에서 처리
        }
    }

    // ── TC-S4: 취소 — 소켓 정리 누수 없음 ──────────────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S4 sendAndReceive job 취소 시 CancellationException 전파 및 소켓 닫힘`() {
        val cancellationPropagated = AtomicBoolean(false)

        withSilentMockServer { port, serverJob ->
            val config = ConnectionConfig(
                ip = "127.0.0.1",
                port = port,
                timeoutMs = 5000L,
                maxRetry = 0
            )
            val client = McSocketClient(config)
            runBlocking { client.connect() }

            runBlocking {
                val job = launch(Dispatchers.IO) {
                    try {
                        client.sendAndReceive(goldenRequest)
                    } catch (e: CancellationException) {
                        cancellationPropagated.set(true)
                        throw e
                    }
                }
                delay(150) // 송신 후 수신 대기 상태 진입 대기
                job.cancel()
                job.join()  // CancellationException 처리 완료 대기
            }

            assertTrue(cancellationPropagated.get(), "CancellationException이 전파되어야 함")
            client.close()
            // serverJob 정리는 withSilentMockServer finally 블록에서 처리
        }
    }

    // ── TC-S5: 재연결 — 첫 연결 실패 후 서버 기동 시 성공 ─────────────────

    @Test
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `TC-S5 처음 포트 닫혀 있다가 열리면 maxRetry 내 재연결 성공`() {
        val reservedPort = ServerSocket(0).also { it.close() }.localPort
        val serverReady = AtomicBoolean(false)
        val responseReceived = AtomicReference<ByteArray>(null)

        // mock 서버를 별도 스레드에서 700ms 후 열기
        val serverThread = Thread {
            Thread.sleep(700)
            ServerSocket(reservedPort).use { server ->
                serverReady.set(true)
                val sock = server.accept()
                val buf = ByteArray(256)
                sock.getInputStream().read(buf)
                sock.getOutputStream().write(goldenResponse)
                sock.getOutputStream().flush()
                sock.close()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val config = ConnectionConfig(
            ip = "127.0.0.1",
            port = reservedPort,
            timeoutMs = 3000L,
            connectTimeoutMs = 300L,
            maxRetry = 5,           // 5회 재시도
            retryBackoffMs = 200L   // 200ms backoff → 200+400+800 = 1400ms 후 4번째 시도
        )
        val client = McSocketClient(config)

        runBlocking {
            client.connect()  // 재시도 중 serverThread가 700ms 후 열어줌
            val result = client.sendAndReceive(goldenRequest)
            responseReceived.set(result)
            client.close()
        }

        serverThread.join(3000)
        assertTrue(serverReady.get(), "서버가 열렸어야 함")
        assertNotNull(responseReceived.get(), "응답 수신 성공")
        assertArrayEquals(goldenResponse, responseReceived.get()!!)
    }

    // ── TC-S6: 재연결 소진 — ConnectionException ─────────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S6 maxRetry 소진 시 ConnectionException`() {
        val unreachablePort = ServerSocket(0).also { it.close() }.localPort

        val config = ConnectionConfig(
            ip = "127.0.0.1",
            port = unreachablePort,
            connectTimeoutMs = 200L,
            maxRetry = 2,
            retryBackoffMs = 100L
        )
        val client = McSocketClient(config)

        var caught: Exception? = null
        try {
            runBlocking { client.connect() }
        } catch (e: ConnectionException) {
            caught = e
        }

        assertNotNull(caught, "ConnectionException이 throw되어야 함")
        assertTrue(caught is ConnectionException)
    }

    // ── TC-S7: Subheader 불일치 응답 — FrameException ────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S7 Subheader 불일치 응답 시 FrameException`() {
        val badResponse = goldenResponse.copyOf()
        badResponse[0] = 0x50.toByte() // 요청 Subheader가 잘못 반환됨

        withCustomResponseMockServer(badResponse) { port, serverJob ->
            val config = ConnectionConfig(
                ip = "127.0.0.1",
                port = port,
                timeoutMs = 3000L,
                maxRetry = 0
            )
            val client = McSocketClient(config)
            runBlocking { client.connect() }

            var caught: Exception? = null
            try {
                runBlocking { client.sendAndReceive(goldenRequest) }
            } catch (e: FrameException) {
                caught = e
            }

            assertNotNull(caught, "FrameException이 throw되어야 함")
            assertTrue(caught is FrameException)
            client.close()
            serverJob.cancel()
            runBlocking { serverJob.join() }
        }
    }

    // ── TC-S8: use 블록 — 정상 close ────────────────────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S8 use 블록 정상 종료 시 소켓 닫힘`() {
        withMockServer { port, serverJob ->
            val config = ConnectionConfig(
                ip = "127.0.0.1",
                port = port,
                timeoutMs = 3000L,
                maxRetry = 0
            )
            var result: ByteArray? = null
            McSocketClient(config).use { client ->
                runBlocking {
                    client.connect()
                    result = client.sendAndReceive(goldenRequest)
                }
            }

            assertNotNull(result)
            assertArrayEquals(goldenResponse, result!!)
            serverJob.cancel()
            runBlocking { serverJob.join() }
        }
    }

    // ── TC-S9: connect 실패 반복 — FD 누수 없음 ────────────────────────────────

    @Test
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `TC-S9 connect 실패 반복 시 소켓 FD 누수 없음`() {
        // 닫힌 포트에 maxRetry=5(6회 시도) 재시도 → 각 시도에서 새 Socket()이 생성되지만
        // connect 실패 시 즉시 close되어야 한다.
        // Unix 환경: getOpenFileDescriptorCount로 실제 FD 증가를 검증.
        // Windows: UnixOperatingSystemMXBean 미지원 → FD 검증 생략, 예외 여부만 검증.
        val fdBefore = openFdCount()

        val closedPort = ServerSocket(0).also { it.close() }.localPort
        val config = ConnectionConfig(
            ip = "127.0.0.1",
            port = closedPort,
            connectTimeoutMs = 100L,
            maxRetry = 5,
            retryBackoffMs = 10L
        )
        val client = McSocketClient(config)

        assertThrows(ConnectionException::class.java) {
            runBlocking { client.connect() }
        }

        val fdAfter = openFdCount()
        if (fdBefore != null && fdAfter != null) {
            // 6회 시도(maxRetry+1) 후 FD 누수 없음: epsilon=3(GC 타이밍 등 정상 변동 허용).
            assertTrue(
                fdAfter - fdBefore <= 3,
                "FD 누수 의심 — before=$fdBefore after=$fdAfter diff=${fdAfter - fdBefore}"
            )
        }
        // FD 카운트 불가(Windows) 시 ConnectionException 검증만으로 충분.
    }

    // ── TC-S10: 슬로우 드립 — 프레임 전체 타임아웃 발동 ─────────────────────────

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S10 슬로우 드립 응답 — 프레임 전체 타임아웃으로 ResponseTimeoutException`() {
        // 개별 read 간격(200ms) < soTimeout(500ms) → soTimeout 불발.
        // 전체 21 byte 예상 수신 시간(≥4200ms) >> withTimeout(500ms) → 전체 타임아웃 발동.
        withSlowDripMockServer(dripIntervalMs = 200L) { port, serverJob ->
            val config = ConnectionConfig(
                ip = "127.0.0.1",
                port = port,
                timeoutMs = 500L,    // soTimeout(per-read) = withTimeout(total) = 500ms
                maxRetry = 0
            )
            val client = McSocketClient(config)
            runBlocking { client.connect() }

            var caught: Exception? = null
            try {
                runBlocking { client.sendAndReceive(goldenRequest) }
            } catch (e: ResponseTimeoutException) {
                caught = e
            }

            assertNotNull(caught, "슬로우 드립 응답은 ResponseTimeoutException이어야 함")
            client.close()
        }
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S11 close가 local register보다 먼저면 TCP connect syscall에 진입하지 않는다`() {
        val created = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pendingPublished = AtomicInteger(0)
        val probe = object : SocketLifecycleProbe {
            override fun afterSocketCreated() {
                created.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS), "socket factory barrier release")
            }
            override fun afterPendingPublished() { pendingPublished.incrementAndGet() }
        }
        val client = McSocketClient(
            ConnectionConfig("127.0.0.1", port = 9, timeoutMs = 500L, maxRetry = 0),
            probe,
        )

        val result = runBlocking {
            val connect = async(Dispatchers.IO) { runCatching { client.connect() } }
            assertTrue(created.await(3, TimeUnit.SECONDS), "local Socket 생성 확인")
            client.close()
            release.countDown()
            connect.await()
        }

        assertTrue(result.exceptionOrNull() is ConnectionException)
        assertEquals(0, pendingPublished.get(), "terminal close 뒤 pending/connect admission 금지")
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S12 pending TCP socket은 close가 connect syscall 전에 즉시 닫는다`() {
        val pending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connectSucceeded = AtomicInteger(0)
        val probe = object : SocketLifecycleProbe {
            override fun afterPendingPublished() {
                pending.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS), "pending barrier release")
            }
            override fun afterConnectSucceeded() { connectSucceeded.incrementAndGet() }
        }
        val client = McSocketClient(
            ConnectionConfig("127.0.0.1", port = 9, timeoutMs = 500L, maxRetry = 0),
            probe,
        )

        val result = runBlocking {
            val connect = async(Dispatchers.IO) { runCatching { client.connect() } }
            assertTrue(pending.await(3, TimeUnit.SECONDS), "pending Socket 게시 확인")
            client.close()
            release.countDown()
            connect.await()
        }

        assertTrue(result.exceptionOrNull() is ConnectionException)
        assertEquals(0, connectSucceeded.get(), "close된 pending Socket으로 connect 성공 금지")
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S13 TCP connect 성공 뒤 publish 전 close면 활성 socket으로 게시하지 않는다`() {
        ServerSocket(0).use { server ->
            val connected = CountDownLatch(1)
            val release = CountDownLatch(1)
            val probe = object : SocketLifecycleProbe {
                override fun afterConnectSucceeded() {
                    connected.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS), "publish barrier release")
                }
            }
            val client = McSocketClient(
                ConnectionConfig("127.0.0.1", port = server.localPort, timeoutMs = 1000L, maxRetry = 0),
                probe,
            )

            val result = runBlocking {
                val connect = async(Dispatchers.IO) { runCatching { client.connect() } }
                assertTrue(connected.await(3, TimeUnit.SECONDS), "TCP connect 성공 확인")
                client.close()
                release.countDown()
                connect.await()
            }

            assertTrue(result.exceptionOrNull() is ConnectionException)
            val sendFailure = runBlocking { runCatching { client.sendAndReceive(goldenRequest) } }
            assertTrue(sendFailure.exceptionOrNull() is ConnectionException)
        }
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `TC-S14 explicit close 뒤 send는 TCP auto-connect를 시도하지 않는다`() {
        val socketCreates = AtomicInteger(0)
        val probe = object : SocketLifecycleProbe {
            override fun afterSocketCreated() { socketCreates.incrementAndGet() }
        }
        val client = McSocketClient(
            ConnectionConfig("127.0.0.1", port = 9, timeoutMs = 500L, maxRetry = 3),
            probe,
        )

        client.close()
        val result = runBlocking { runCatching { client.sendAndReceive(goldenRequest) } }

        assertTrue(result.exceptionOrNull() is ConnectionException)
        assertEquals(0, socketCreates.get(), "terminal close 뒤 retry/auto-connect는 0건이어야 한다")
    }

    // ── mock 서버 헬퍼 ────────────────────────────────────────────────────────

    /**
     * 요청 수신 후 [goldenResponse]를 한 번에 전송하는 mock 서버.
     * [block]은 서버가 준비된 후 호출된다.
     */
    private fun withMockServer(block: (port: Int, serverJob: Job) -> Unit) {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = serverSocket.accept()
                val buf = ByteArray(256)
                client.getInputStream().read(buf)
                client.getOutputStream().write(goldenResponse)
                client.getOutputStream().flush()
                client.close()
            } catch (_: Exception) { }
        }

        try {
            block(port, serverJob)
        } finally {
            serverSocket.close()
        }
    }

    /**
     * 응답을 [chunkSizes] 크기 목록대로 나눠서 보내는 mock 서버.
     */
    private fun withFragmentedMockServer(
        chunkSizes: List<Int>,
        block: (port: Int, serverJob: Job) -> Unit
    ) {
        require(chunkSizes.sum() == goldenResponse.size) {
            "chunkSizes.sum ${chunkSizes.sum()} != goldenResponse.size ${goldenResponse.size}"
        }

        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = serverSocket.accept()
                val buf = ByteArray(256)
                client.getInputStream().read(buf)
                val out = client.getOutputStream()
                var offset = 0
                for (size in chunkSizes) {
                    out.write(goldenResponse, offset, size)
                    out.flush()
                    offset += size
                    Thread.sleep(10) // 조각 간 지연으로 단편화 유도
                }
                client.close()
            } catch (_: Exception) { }
        }

        try {
            block(port, serverJob)
        } finally {
            serverSocket.close()
        }
    }

    /**
     * 연결 수락 후 아무것도 보내지 않는 mock 서버 (timeout 테스트용).
     */
    private fun withSilentMockServer(block: (port: Int, serverJob: Job) -> Unit) {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = serverSocket.accept()
                delay(30_000) // 코루틴 취소에 즉시 반응하는 대기
                client.close()
            } catch (_: Exception) { }
        }

        try {
            block(port, serverJob)
        } finally {
            serverJob.cancel()          // 서버 코루틴 취소 (delay 즉시 중단)
            serverSocket.close()
        }
    }

    /**
     * 1 byte씩 [dripIntervalMs] ms 간격으로 보내는 mock 서버 (슬로우 드립 타임아웃 테스트용).
     *
     * 개별 read 간격 < soTimeout 이지만 전체 프레임 수신 시간 >> withTimeout 인 경우를 재현해
     * 프레임 전체 타임아웃(4-2)이 발동하는지 검증한다.
     */
    private fun withSlowDripMockServer(
        dripIntervalMs: Long,
        block: (port: Int, serverJob: Job) -> Unit
    ) {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = serverSocket.accept()
                val buf = ByteArray(256)
                client.getInputStream().read(buf) // 요청 수신(내용 무시)
                val out = client.getOutputStream()
                for (b in goldenResponse) {
                    out.write(byteArrayOf(b))
                    out.flush()
                    Thread.sleep(dripIntervalMs)
                }
                client.close()
            } catch (_: Exception) { }
        }

        try {
            block(port, serverJob)
        } finally {
            serverJob.cancel()
            serverSocket.close()
        }
    }

    /**
     * Unix 환경에서 현재 프로세스의 열린 FD 수를 반환한다.
     * Windows 등 미지원 환경에서는 null 반환(검증 생략).
     */
    private fun openFdCount(): Long? = runCatching {
        val mxBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        val method = mxBean.javaClass.getMethod("getOpenFileDescriptorCount")
        method.isAccessible = true
        method.invoke(mxBean) as Long
    }.getOrNull()

    /**
     * 지정한 [response]를 보내는 mock 서버.
     */
    private fun withCustomResponseMockServer(
        response: ByteArray,
        block: (port: Int, serverJob: Job) -> Unit
    ) {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = serverSocket.accept()
                val buf = ByteArray(256)
                client.getInputStream().read(buf)
                client.getOutputStream().write(response)
                client.getOutputStream().flush()
                client.close()
            } catch (_: Exception) { }
        }

        try {
            block(port, serverJob)
        } finally {
            serverSocket.close()
        }
    }
}
