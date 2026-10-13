// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.cli

import melsecmckotlin.McProtocolException
import melsecmckotlin.ResponseTimeoutException
import melsecmckotlin.device.DeviceParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTest {
    private fun read(vararg options: String) = arrayOf("--ip", "192.0.2.10", "read", "--device", "D0", *options)
    private fun write(device: String = "D0", values: String = "1", allow: Boolean = true, vararg options: String): Array<String> =
        arrayOf("--ip", "192.0.2.10", "write", "--device", device, "--values", values,
            *(if (allow) arrayOf("--allow-write") else emptyArray()), *options)

    private fun response(vararg data: Int): ByteArray {
        val length = data.size + 2
        return byteArrayOf(0xD0.toByte(), 0, 0, 0xFF.toByte(), 0xFF.toByte(), 3, 0,
            (length and 0xFF).toByte(), (length ushr 8).toByte(), 0, 0) + data.map { it.toByte() }.toByteArray()
    }

    @Test
    fun parsesReadDefaultsAndHexAddress() {
        val args = parseArgs(arrayOf("read", "--ip", "192.0.2.10", "--device", "X1F", "--bit"))
        assertEquals(1025, args.port)
        assertEquals(1, args.count)
        assertEquals(31, args.device.number)
        assertTrue(args.bit)
        assertFalse(args.allowWrite)
        assertFalse(args.udp)
    }

    @Test
    fun parsesUdpLocalPortAndLiteralIpv6() {
        val args = parseArgs(arrayOf("--ip", "2001:db8::10", "--port", "5002", "--udp",
            "--localport", "1028", "read", "--device", "M100", "--bit", "--count", "3"))
        assertTrue(args.udp)
        assertEquals(1028, args.localPort)
        assertEquals(5002, args.port)
        assertEquals(3, args.count)
    }

    @Test
    fun rejectsMalformedArgumentsConsistently() {
        val cases = listOf(
            emptyArray<String>(), arrayOf("read"), arrayOf("--ip"), read("--port"), read("--port", "abc"),
            read("--count", "abc"), read("--count", "0"), read("--port", "0"), read("--port", "65536"),
            read("--count", "961"), read("--unknown"), read("--ascii"), read("--frame", "4e"),
            read("read"), read("write"), read("junk"), read("--device", "D1"), read("--bit", "--bit"),
            read("--values", "1"), read("--allow-write"), read("--localport", "1028"),
            read("--udp", "--localport", "0"), read("--udp", "--localport", "65536"),
            read("--udp", "--localport", "x"), read("--help"), read("--bit"),
            write(allow = false), write(values = "-1"), write(values = "65536"), write(values = "0x10000"),
            write(values = "2147483648"), write(values = ""), write(values = "1,"), write(values = ",1"),
            write(values = "0x"), write(values = "1,no"), write(values = "+1"),
            write(options = arrayOf("--count", "1")), write("M0", "2", options = arrayOf("--bit")),
            write("D0", "1", options = arrayOf("--bit")),
        )
        cases.forEach { tokens ->
            assertFailsWith<IllegalArgumentException>(tokens.joinToString(" ")) { parseArgs(tokens) }
        }
    }

    @Test
    fun rejectsInvalidIpAndDeviceBeforeExchange() {
        val cases = listOf(
            arrayOf("--ip", "plc.example", "read", "--device", "D0"),
            arrayOf("--ip", "192.0.2.256", "read", "--device", "D0"),
            arrayOf("--ip", "192.0.02.10", "read", "--device", "D0"),
            arrayOf("--ip", ":::1", "read", "--device", "D0"),
            arrayOf("--ip", "2001:db8::10%eth0", "read", "--device", "D0"),
        ) + listOf("NO0", "D-1", "D+1", "X+1", "D16777216", "X1000000", "LTN0", "LTS0", "G0").map {
            arrayOf("--ip", "192.0.2.10", "read", "--device", it)
        }
        cases.forEach { tokens ->
            var exchanges = 0
            val errors = mutableListOf<String>()
            val status = runBlocking {
                runCli(tokens, output = {}, errorOutput = { errors.add(it) }, exchange = { _, _ ->
                    exchanges++
                    response()
                })
            }
            assertEquals(2, status, tokens.joinToString(" "))
            assertEquals(0, exchanges)
            assertTrue(errors.first().startsWith("[ERROR]"))
        }
    }

    @Test
    fun invalidCombinationsAndWriteWithoutApprovalNeverEnterExchange() {
        val cases = listOf(read("--unknown"), read("--values", "1"), read("--localport", "1028"),
            read("--bit"), write(allow = false), write(values = "65536"), write("M0", "2", options = arrayOf("--bit")))
        cases.forEach { tokens ->
            var exchanges = 0
            val output = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val status = runBlocking {
                runCli(tokens, output = { output.add(it) }, errorOutput = { errors.add(it) }, exchange = { _, _ ->
                    exchanges++
                    response()
                })
            }
            assertEquals(2, status)
            assertEquals(0, exchanges)
            assertTrue(output.isEmpty())
            assertFalse(errors.isEmpty())
        }
    }

    @Test
    fun directlyConstructedWriteCannotBypassApproval() {
        val args = CliArgs("192.0.2.10", 1025, "write", DeviceParser.parse("D0"), 1, false, intArrayOf(1))
        assertFailsWith<IllegalArgumentException> { buildTxFrame(args) }
    }

    @Test
    fun validatesEntireAddressSpanIncludingWordAccessToBitDevice() {
        val maxWord = parseArgs(arrayOf("--ip", "192.0.2.10", "read", "--device", "D16777215"))
        buildTxFrame(maxWord)
        assertFailsWith<IllegalArgumentException> { buildTxFrame(maxWord.copy(count = 2)) }
        val maxBitWord = maxWord.copy(device = DeviceParser.parse("XFFFFF0"))
        buildTxFrame(maxBitWord)
        assertFailsWith<IllegalArgumentException> { buildTxFrame(maxBitWord.copy(count = 2)) }
        assertFailsWith<IllegalArgumentException> { buildTxFrame(maxWord.copy(device = DeviceParser.parse("XFFFFFF"))) }
    }

    @Test
    fun enforcesPointLimitsForReadsAndWrites() {
        val word = parseArgs(read("--count", "960"))
        buildTxFrame(word)
        val bit = word.copy(device = DeviceParser.parse("M0"), bit = true, count = 3584)
        buildTxFrame(bit)
        assertFailsWith<IllegalArgumentException> { buildTxFrame(bit.copy(count = 3585)) }
        val wordWrite = word.copy(command = "write", count = 1, values = IntArray(960), allowWrite = true)
        buildTxFrame(wordWrite)
        assertFailsWith<IllegalArgumentException> { buildTxFrame(wordWrite.copy(values = IntArray(961))) }
        buildTxFrame(wordWrite.copy(device = DeviceParser.parse("M0"), bit = true, values = IntArray(3584)))
        assertFailsWith<IllegalArgumentException> {
            buildTxFrame(wordWrite.copy(device = DeviceParser.parse("M0"), bit = true, values = IntArray(3585)))
        }
    }

    @Test
    fun wordReadUsesKnownThreeEBinaryFrame() {
        val args = parseArgs(arrayOf("--ip", "192.0.2.10", "read", "--device", "D100", "--count", "5"))
        assertEquals("50 00 00 FF FF 03 00 0C 00 10 00 01 04 00 00 64 00 00 A8 05 00", buildTxFrame(args).toHexDump())
    }

    @Test
    fun bitReadRouteUses04010001WithoutNetwork() {
        val args = parseArgs(arrayOf("--ip", "192.0.2.10", "read", "--device", "M100", "--count", "3", "--bit"))
        assertEquals("50 00 00 FF FF 03 00 0C 00 10 00 01 04 01 00 64 00 00 90 03 00", buildTxFrame(args).toHexDump())
    }

    @Test
    fun bitWriteRouteUsesUpperFirstNibblesWithoutNetwork() {
        val frame = buildTxFrame(parseArgs(write("M8000", "1,0,1", options = arrayOf("--bit"))))
        assertContentEquals(byteArrayOf(0x01, 0x14, 0x01, 0x00), frame.copyOfRange(11, 15))
        assertContentEquals(byteArrayOf(0x03, 0x00, 0x10, 0x10), frame.copyOfRange(19, 23))
    }

    @Test
    fun wordWriteKeepsUnsignedLittleEndianValues() {
        val args = parseArgs(write(values = "0,65535,0x1234"))
        val frame = buildTxFrame(args)
        assertContentEquals(byteArrayOf(0x01, 0x14, 0x00, 0x00), frame.copyOfRange(11, 15))
        assertContentEquals(byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0x34, 0x12), frame.copyOfRange(21, 27))
    }

    @Test
    fun printsOnlyDecodedBitsAndUsesHexRadixAcrossBoundary() {
        val args = parseArgs(arrayOf("--ip", "192.0.2.10", "read", "--device", "X1F", "--count", "3", "--bit"))
        val lines = formatResponse(args, response(0x10, 0x10))
        assertEquals("[BIT] X1F=1 X20=0 X21=1", lines.last())
        assertFalse(lines.any { "lower-first" in it || "upper-first" in it })
    }

    @Test
    fun printsHexWordAndDecimalWordAddresses() {
        val args = parseArgs(read("--count", "2"))
        assertEquals("[PARSED] D0=0x1234(4660) D1=0xFFFF(65535)", formatResponse(args, response(0x34, 0x12, 0xFF, 0xFF)).last())
        assertEquals("[PARSED] WF=0x0001(1) W10=0x0002(2)",
            formatResponse(args.copy(device = DeviceParser.parse("WF")), response(1, 0, 2, 0)).last())
    }

    @Test
    fun wordUnitsOnBitDeviceAdvanceSixteenAddresses() {
        val args = parseArgs(arrayOf("--ip", "192.0.2.10", "read", "--device", "X1F", "--count", "2"))
        assertEquals("[PARSED] X1F=0x0001(1) X2F=0x0002(2)", formatResponse(args, response(1, 0, 2, 0)).last())
    }

    @Test
    fun rejectsTruncatedMalformedAndWrongCountResponses() {
        val args = parseArgs(read())
        assertFailsWith<McProtocolException> { formatResponse(args, byteArrayOf()) }
        assertFailsWith<McProtocolException> { formatResponse(args, response(1, 0).also { it[0] = 0 }) }
        assertFailsWith<McProtocolException> { formatResponse(args, response(1, 0).also { it[7] = 9 }) }
        assertFailsWith<McProtocolException> { formatResponse(args, response(1)) }
        assertFailsWith<McProtocolException> { formatResponse(args, response(1, 0, 2, 0)) }
        val bit = args.copy(device = DeviceParser.parse("M0"), bit = true, count = 3)
        assertFailsWith<McProtocolException> { formatResponse(bit, response(0x10)) }
        assertFailsWith<McProtocolException> { formatResponse(bit, response(0x10, 0x10, 0)) }
        val writeArgs = parseArgs(write())
        assertFailsWith<McProtocolException> { formatResponse(writeArgs, response(1, 0)) }
    }

    @Test
    fun helpHasNoExchangeAndDocumentsWriteUncertainty() {
        for (tokens in listOf(emptyArray(), arrayOf("--help"), arrayOf("-h"))) {
            val lines = mutableListOf<String>()
            val status = runBlocking { runCli(tokens, output = { lines.add(it) }, errorOutput = { error(it) },
                exchange = { _, _ -> error("도움말은 네트워크를 사용하면 안 됩니다.") }) }
            assertEquals(0, status)
            assertTrue(lines.single().contains("--allow-write"))
            assertTrue(lines.single().contains("PLC에 적용되었을 수 있습니다."))
            assertTrue(lines.single().contains("192.0.2.10"))
            assertFalse(lines.single().any { it in "\u2014\u2013\u2015\u00B7\u30FB\u2022" })
        }
    }

    @Test
    fun successfulReadAndApprovedWriteUseExactlyOneExchange() {
        for (tokens in listOf(read(), write())) {
            var exchanges = 0
            val lines = mutableListOf<String>()
            val status = runBlocking { runCli(tokens, output = { lines.add(it) }, errorOutput = { error(it) },
                exchange = { args, _ -> exchanges++; if (args.command == "read") response(42, 0) else response() }) }
            assertEquals(0, status)
            assertEquals(1, exchanges)
            assertTrue(lines.any { it.startsWith("[PARSED]") })
        }
    }

    @Test
    fun writeTimeoutReturnsFailureAndUncertainOutcomeWithoutRetry() {
        var exchanges = 0
        val errors = mutableListOf<String>()
        val status = runBlocking { runCli(write(), output = {}, errorOutput = { errors.add(it) },
            exchange = { _, _ -> exchanges++; throw ResponseTimeoutException(4000L) }) }
        assertEquals(1, status)
        assertEquals(1, exchanges)
        assertTrue(errors.any { "PLC에 적용되었을 수 있습니다." in it })
        assertTrue(errors.any { "재실행 전에 PLC 상태를 확인하세요." in it })
    }

    @Test
    fun plcErrorDoesNotPrintParsedSuccess() {
        val lines = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val status = runBlocking { runCli(read(), output = { lines.add(it) }, errorOutput = { errors.add(it) },
            exchange = { _, _ -> response().also { it[9] = 0x50; it[10] = 0xC0.toByte() } }) }
        assertEquals(1, status)
        assertFalse(lines.any { it.startsWith("[PARSED]") })
        assertTrue(errors.any { "C050H" in it })
    }

    @Test
    fun cancellationPropagates() {
        assertFailsWith<CancellationException> {
            runBlocking { runCli(read(), output = {}, errorOutput = { error(it) },
                exchange = { _, _ -> throw CancellationException("취소 테스트") }) }
        }
    }
    @Test
    fun invalidRequestedBitNibblesFailWithoutSuccessOutput() {
        val cases = listOf(
            1 to intArrayOf(0x20),
            2 to intArrayOf(0x02),
            3 to intArrayOf(0x10, 0x20),
        )
        for ((count, data) in cases) {
            val lines = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val tokens = arrayOf("--ip", "192.0.2.10", "read", "--device", "M0", "--bit", "--count", count.toString())
            val status = runBlocking {
                runCli(tokens, output = { lines.add(it) }, errorOutput = { errors.add(it) },
                    exchange = { _, _ -> response(*data) })
            }
            assertEquals(1, status)
            assertFalse(lines.any { it.startsWith("[BIT]") || it.startsWith("[PARSED]") || it.startsWith("[END CODE]") })
            assertTrue(errors.any { "응답 검증에 실패했습니다." in it })
        }
    }

    @Test
    fun oddBitCountIgnoresUnusedLowPaddingNibble() {
        val args = parseArgs(arrayOf("--ip", "192.0.2.10", "read", "--device", "M0", "--bit", "--count", "3"))
        assertEquals("[BIT] M0=1 M1=0 M2=1", formatResponse(args, response(0x10, 0x1F)).last())
        assertEquals("[BIT] M0=1", formatResponse(args.copy(count = 1), response(0x12)).last())
        assertFailsWith<melsecmckotlin.FrameException> {
            formatResponse(args, response(0x10, 0x2F))
        }
    }

    @Test
    fun helpUsesGeneratedExecutableName() {
        val help = usageText()
        assertTrue(help.startsWith("melsec-cli:"))
        assertTrue(help.contains("melsec-cli --ip 192.0.2.10"))
        assertFalse(help.contains("melsec-mc-cli"))
    }

}
