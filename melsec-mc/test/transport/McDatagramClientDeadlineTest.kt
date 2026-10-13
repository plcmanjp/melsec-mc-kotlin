// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.transport

import com.plciocheck.melsecmc.ConnectionException
import com.plciocheck.melsecmc.ResponseTimeoutException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class McDatagramClientDeadlineTest {
    private val request = hex("50 00 00 FF FF 03 00 0C 00 10 00 01 04 00 00 64 00 00 A8 01 00")
    private val response = hex("D0 00 00 FF FF 03 00 04 00 00 00 2A 00")

    @Test
    @Timeout(5)
    fun `intermediate receive timeouts retain one request and consume only the original total deadline`() {
        val clock = AtomicLong(123_000_000_000L)
        val socket = ScriptedSocket { sock, _ ->
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(sock.soTimeout.toLong()))
            throw SocketTimeoutException("Intermediate poll")
        }
        val client = client(socket, clock, timeoutMs = 250)
        try {
            val failure = runBlocking { runCatching { client.sendAndReceive(request) }.exceptionOrNull() }
            assertTrue(failure is ResponseTimeoutException)
            assertEquals(listOf(100, 100, 50), socket.receiveTimeouts)
            assertEquals(123_250_000_000L, clock.get())
            assertEquals(1, socket.sent.size, "Polling must never retransmit a device request")
            assertArrayEquals(request, socket.sent.single())
            assertFalse(client.isTransportConnected)
        } finally {
            client.close()
        }
    }

    @Test
    @Timeout(5)
    fun `response one nanosecond before total deadline succeeds after multiple polls`() {
        responseBoundary(finalReceiveNanos = 49_999_999L, expectTimeout = false)
    }

    @Test
    @Timeout(5)
    fun `response at total deadline is rejected rather than extending the response window`() {
        responseBoundary(finalReceiveNanos = 50_000_000L, expectTimeout = true)
    }

    private fun responseBoundary(finalReceiveNanos: Long, expectTimeout: Boolean) {
        val clock = AtomicLong(0)
        var receives = 0
        val socket = ScriptedSocket { _, packet ->
            receives++
            if (receives < 3) {
                clock.addAndGet(100_000_000L)
                throw SocketTimeoutException("Intermediate poll")
            }
            clock.addAndGet(finalReceiveNanos)
            response.copyInto(packet.data, packet.offset)
            packet.length = response.size
        }
        val client = client(socket, clock, timeoutMs = 250)
        try {
            val result = runBlocking { runCatching { client.sendAndReceive(request) } }
            if (expectTimeout) {
                assertTrue(result.exceptionOrNull() is ResponseTimeoutException)
            } else {
                assertArrayEquals(response, result.getOrThrow())
                assertTrue(client.isTransportConnected)
            }
            assertEquals(listOf(100, 100, 50), socket.receiveTimeouts)
            assertEquals(1, socket.sent.size)
        } finally {
            client.close()
        }
    }

    @Test
    @Timeout(5)
    fun `monotonic nanosecond rollover preserves the total response deadline`() {
        val clock = AtomicLong(Long.MAX_VALUE - 50_000_000L)
        val socket = ScriptedSocket { sock, _ ->
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(sock.soTimeout.toLong()))
            throw SocketTimeoutException("Intermediate poll")
        }
        val client = client(socket, clock, timeoutMs = 250)
        try {
            val failure = runBlocking { runCatching { client.sendAndReceive(request) }.exceptionOrNull() }
            assertTrue(failure is ResponseTimeoutException)
            assertEquals(listOf(100, 100, 50), socket.receiveTimeouts)
            assertEquals(1, socket.sent.size)
        } finally {
            client.close()
        }
    }

    @Test
    @Timeout(5)
    fun `fractional millisecond remainder remains finite and cannot admit a late response`() {
        val clock = AtomicLong(0)
        var receives = 0
        val socket = ScriptedSocket { _, packet ->
            receives++
            if (receives == 1) {
                clock.set(4_999_999L)
                throw SocketTimeoutException("Early poll return")
            }
            clock.set(5_000_000L)
            response.copyInto(packet.data, packet.offset)
            packet.length = response.size
        }
        val client = client(socket, clock, timeoutMs = 5)
        try {
            val failure = runBlocking { runCatching { client.sendAndReceive(request) }.exceptionOrNull() }
            assertTrue(failure is ResponseTimeoutException)
            assertEquals(listOf(5, 1), socket.receiveTimeouts, "SO_TIMEOUT=0 would mean infinite wait")
            assertEquals(1, socket.sent.size)
        } finally {
            client.close()
        }
    }

    @Test
    @Timeout(5)
    fun `terminal close wins over intermediate timeout even when receive ignores close wakeup`() = runBlocking {
        val entered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val creates = AtomicInteger(0)
        val socket = ScriptedSocket { sock, _ ->
            val timeout = sock.soTimeout
            entered.countDown()
            // Model the observed Android poll: close does not wake this in-progress wait.
            Thread.sleep(timeout.toLong())
            throw SocketTimeoutException("Closed native poll returned on its short timeout")
        }
        val client = McDatagramClient(
            config = ConnectionConfig("127.0.0.1", port = 9, timeoutMs = 5_000, maxRetry = 0),
            lifecycleProbe = object : DatagramLifecycleProbe {},
            socketFactory = { creates.incrementAndGet(); socket },
        )
        try {
            client.connect()
            val exchange = launch(Dispatchers.IO) {
                withContext(NonCancellable) {
                    try {
                        client.sendAndReceive(request)
                    } catch (cause: Exception) {
                        failure.set(cause)
                    }
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            exchange.cancel() // Preview cancellation must not cancel the admitted receive.
            client.close()
            withTimeout(1_000) { exchange.join() }
            assertTrue(exchange.isCancelled)
            assertTrue(failure.get() is ConnectionException, "Close must not become a response timeout")
            assertEquals(listOf(100), socket.receiveTimeouts)
            assertEquals(1, socket.sent.size)
            assertFalse(client.isTransportConnected)
            val afterClose = runCatching { client.sendAndReceive(request) }.exceptionOrNull()
            assertTrue(afterClose is ConnectionException)
            assertEquals(1, creates.get(), "Terminal close must never auto-connect")
            assertEquals(1, socket.sent.size)
        } finally {
            client.close()
        }
    }

    @Test
    @Timeout(5)
    fun `actual UDP response delayed beyond several poll intervals preserves exact frame bytes`() = runBlocking {
        withTimeout(4_000) {
            coroutineScope {
                DatagramSocket(0).use { serverSocket ->
                    val client = McDatagramClient(ConnectionConfig(
                        "127.0.0.1", port = serverSocket.localPort, timeoutMs = 2_000, maxRetry = 0,
                    ))
                    val server = async(Dispatchers.IO) {
                        val packet = DatagramPacket(ByteArray(4_096), 4_096)
                        serverSocket.receive(packet)
                        assertArrayEquals(request, packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                        delay(250)
                        serverSocket.send(DatagramPacket(response, response.size, packet.socketAddress))
                    }
                    try {
                        client.connect()
                        val generation = client.connectionGeneration
                        assertArrayEquals(response, client.sendAndReceive(request))
                        assertEquals(generation, client.connectionGeneration)
                        assertTrue(client.isTransportConnected)
                        server.await()
                    } finally {
                        client.close()
                        serverSocket.close()
                        server.cancelAndJoin()
                    }
                }
            }
        }
    }

    private fun client(socket: ScriptedSocket, clock: AtomicLong, timeoutMs: Long) = McDatagramClient(
        config = ConnectionConfig("127.0.0.1", port = 9, timeoutMs = timeoutMs, maxRetry = 0),
        lifecycleProbe = object : DatagramLifecycleProbe {},
        nanoTime = clock::get,
        socketFactory = { socket },
    )

    private class ScriptedSocket(
        private val receiveAction: (ScriptedSocket, DatagramPacket) -> Unit,
    ) : DatagramSocket(null as SocketAddress?) {
        val sent = CopyOnWriteArrayList<ByteArray>()
        val receiveTimeouts = CopyOnWriteArrayList<Int>()
        override fun connect(address: SocketAddress) = Unit
        override fun send(packet: DatagramPacket) {
            sent += packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        }
        override fun receive(packet: DatagramPacket) {
            receiveTimeouts += soTimeout
            receiveAction(this, packet)
        }
    }

    private fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
