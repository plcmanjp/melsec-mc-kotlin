// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

import melsecmckotlin.FrameException
import melsecmckotlin.device.DeviceParser
import melsecmckotlin.device.ModuleBufferAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McFrameEncoderAsciiConfigurationTest {
    private val customRoute = AccessRoute(networkNo = 1, pcNo = 2, moduleIoNo = 0x1234, moduleStationNo = 3)
    private val customTimer = byteArrayOf(0x20, 0x02)

    private fun ordinaryCommands(encoder: McFrameEncoder): List<() -> ByteArray> = listOf(
        { encoder.encodeBatchReadWord(DeviceParser.parse("D100"), 5) },
        { encoder.encodeBatchReadBit(DeviceParser.parse("M100"), 3) },
        { encoder.encodeBatchWriteWord(DeviceParser.parse("D100"), intArrayOf(123)) },
        { encoder.encodeBatchWriteBit(DeviceParser.parse("M100"), intArrayOf(1, 0, 1)) },
        { encoder.encodeCpuModelRead() },
    )

    @Test
    fun ordinaryThreeEAndFourEAsciiRejectEachCustomRouteField() {
        val routes = listOf(
            AccessRoute(networkNo = 1), AccessRoute(pcNo = 2),
            AccessRoute(moduleIoNo = 0x1234), AccessRoute(moduleStationNo = 3), customRoute,
        )
        for (frame in FrameType.entries) {
            for (route in routes) {
                val encoder = McFrameEncoder(accessRoute = route, frameType = frame, dataMode = DataMode.ASCII)
                ordinaryCommands(encoder).forEach { build -> assertFailsWith<FrameException> { build() } }
            }
        }
    }

    @Test
    fun ordinaryThreeEAndFourEAsciiRejectCustomAndMalformedTimers() {
        val timers = listOf(customTimer, byteArrayOf(0x11, 0), byteArrayOf(0x10, 1),
            byteArrayOf(), byteArrayOf(0x10), byteArrayOf(0x10, 0, 0))
        for (frame in FrameType.entries) {
            for (timer in timers) {
                val encoder = McFrameEncoder(monitoringTimer = timer, frameType = frame, dataMode = DataMode.ASCII)
                ordinaryCommands(encoder).forEach { build -> assertFailsWith<FrameException> { build() } }
            }
        }
    }

    @Test
    fun directOrdinaryThreeEAsciiBuilderAlsoRejectsCustomSettings() {
        val encoder = McFrameEncoder(accessRoute = customRoute, dataMode = DataMode.ASCII)
        assertFailsWith<FrameException> {
            encoder.buildFrame3EAsciiFromString(McCommand.CpuModelRead, Subcommand.WordUnitsQL, "")
        }
    }

    @Test
    fun defaultAsciiGoldenFramesRemainUnchanged() {
        for (frame in FrameType.entries) {
            // 기본 timer는 배열 identity가 아니라 값으로 비교합니다.
            val encoder = McFrameEncoder(accessRoute = AccessRoute(), monitoringTimer = byteArrayOf(0x10, 0),
                frameType = frame, dataMode = DataMode.ASCII)
            val header = if (frame == FrameType.FRAME_3E) "5000" else "540000000000"
            assertEquals(header + "00FF03FF000018001004010000D 0001000005",
                encoder.encodeBatchReadWord(DeviceParser.parse("D100"), 5).toString(Charsets.US_ASCII))
        }
    }

    @Test
    fun defaultAsciiBitWriteUsesOneCharacterPerBit() {
        val encoder = McFrameEncoder(dataMode = DataMode.ASCII)
        val frame = encoder.encodeBatchWriteBit(DeviceParser.parse("M0"), IntArray(16) { it % 2 })
            .toString(Charsets.US_ASCII)
        assertEquals("500000FF03FF000028001014010001M 00000000100101010101010101", frame)
    }

    @Test
    fun ordinaryBinaryRetainsCustomRouteAndTimer() {
        for (frame in FrameType.entries) {
            val encoder = McFrameEncoder(accessRoute = customRoute, monitoringTimer = customTimer,
                frameType = frame, dataMode = DataMode.BINARY)
            val encoded = encoder.encodeBatchReadWord(DeviceParser.parse("D100"), 5)
            val prefix = if (frame == FrameType.FRAME_3E) 2 else 6
            assertContentEquals(byteArrayOf(1, 2, 0x34, 0x12, 3), encoded.copyOfRange(prefix, prefix + 5))
            assertContentEquals(byteArrayOf(0x20, 0x02), encoded.copyOfRange(prefix + 7, prefix + 9))
        }
    }

    @Test
    fun directAndEncoderModuleCodecsHonorCustomAsciiAndBinarySettings() {
        for (frame in FrameType.entries) {
            for (mode in DataMode.entries) {
                val encoder = McFrameEncoder(accessRoute = customRoute, monitoringTimer = customTimer,
                    frameType = frame, dataMode = mode)
                // 생성자 전면 차단 또는 일반 ASCII 실패 후 모듈 경로 차단을 방지합니다.
                if (mode == DataMode.ASCII) {
                    assertFailsWith<FrameException> { encoder.encodeCpuModelRead() }
                }
                val direct = ModuleBufferCodec(ModuleBufferProfile.Q, frame, mode, customRoute, 0x0220)
                val viaEncoder = encoder.moduleBufferCodec(ModuleBufferProfile.Q)
                for (codec in listOf(direct, viaEncoder)) {
                    val encoded = codec.encodeRead(ModuleBufferAddress(1, 100), 1).bytes
                    if (mode == DataMode.ASCII) {
                        val text = encoded.toString(Charsets.US_ASCII)
                        val prefix = if (frame == FrameType.FRAME_3E) 4 else 12
                        assertEquals("0102123403", text.substring(prefix, prefix + 10))
                        assertEquals("0220", text.substring(prefix + 14, prefix + 18))
                    } else {
                        val prefix = if (frame == FrameType.FRAME_3E) 2 else 6
                        assertContentEquals(byteArrayOf(1, 2, 0x34, 0x12, 3), encoded.copyOfRange(prefix, prefix + 5))
                        assertContentEquals(byteArrayOf(0x20, 0x02), encoded.copyOfRange(prefix + 7, prefix + 9))
                    }
                }
            }
        }
    }
}
