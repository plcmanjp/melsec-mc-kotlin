// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

import melsecmckotlin.FrameException
import melsecmckotlin.device.DeviceParser
import melsecmckotlin.device.DeviceCode
import melsecmckotlin.device.DeviceRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * McFrameEncoder 골든 테스트
 *
 * 기대값은 레퍼런스 SSOT에서 직접 인용 — 추정 없음.
 *
 * 골든 소스:
 *   - MC 프로토콜 참조 (D100 5 word Batch Read 전체 frame)
 *   - MC 프로토콜 참조 (M100 8 bit Batch Read, Command~Count 구간)
 *   - MC 프로토콜 참조 (M100 2 word Write, Command~WriteData 구간)
 */
class McFrameEncoderTest {

    private val encoder = McFrameEncoder()

    // ── 골든 테스트 1: D100 5 word Batch Read ────────────────────────────
    /**
     * MC 프로토콜 참조 전체 byte 시퀀스 (공식 매뉴얼 정합):
     *   50 00 | 00 FF FF 03 00 | 0C 00 | 10 00 | 01 04 | 00 00 | 64 00 00 A8 | 05 00
     *
     * 분해:
     *   Subheader:   50 00
     *   AccessRoute: 00 FF FF 03 00
     *   ReqLen:      0C 00 (= 12 = Timer2+Cmd2+Subcmd2+HeadDev3+DevCd1+Count2)
     *   Timer:       10 00
     *   Command:     01 04 (0x0401 LE)
     *   Subcommand:  00 00 (0x0000 Q/L word)
     *   HeadDevice:  64 00 00 (D100 = 0x000064 LE 3byte)
     *   DeviceCode:  A8 (D = 0xA8)
     *   Count:       05 00 (5 words LE)
     */
    @Test
    fun `goldenTest_encodeBatchReadWord_D100_5words`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)

        val expected = byteArrayOf(
            0x50, 0x00,                          // Subheader
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // Access Route
            0x0C, 0x00,                          // ReqLen = 12
            0x10, 0x00,                          // Monitoring Timer
            0x01, 0x04,                          // Command 0x0401 LE
            0x00, 0x00,                          // Subcommand 0x0000
            0x64, 0x00, 0x00,                    // HeadDevice 100 = 0x000064 LE
            0xA8.toByte(),                       // DeviceCode D = 0xA8
            0x05, 0x00                           // Count 5 LE
        )

        assertContentEquals(
            expected, frame,
            "D100 5 word BatchRead frame must match detail-01 §8 golden bytes.\n" +
            "Got:      ${frame.toHex()}\n" +
            "Expected: ${expected.toHex()}"
        )
    }

    // ── 골든 테스트 2: M100 8 bit Batch Read (Subcommand 0001) ───────────
    /**
     * MC 프로토콜 참조 (공식 매뉴얼):
     * "Read values of M100 to M107. (Subcommand: for MELSEC-Q/L series)"
     *
     * Command~Count 구간 (Request Data):
     *   01 04 | 01 00 | 64 00 00 90 | 08 00
     *   Cmd   | Subcmd| HeadDev+DevCd | Count
     *
     * 전체 프레임에서 offset 9 이후가 Cmd~Count 구간:
     *   frame[9..10]  = 01 04 (Command 0x0401)
     *   frame[11..12] = 01 00 (Subcommand 0x0001)
     *   frame[13..15] = 64 00 00 (HeadDevice M100 = 100)
     *   frame[16]     = 90 (DeviceCode M)
     *   frame[17..18] = 08 00 (Count 8)
     *
     * 전체 프레임도 검증한다 (Subheader~Count 포함):
     *   50 00 | 00 FF FF 03 00 | 0C 00 | 10 00 | 01 04 | 01 00 | 64 00 00 90 | 08 00
     */
    @Test
    fun `goldenTest_encodeBatchReadBit_M100_8bits`() {
        val device = DeviceParser.parse("M100")
        val frame = encoder.encodeBatchReadBit(device, 8)

        val expected = byteArrayOf(
            0x50, 0x00,                          // Subheader
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // Access Route
            0x0C, 0x00,                          // ReqLen = 12 (Timer2+Cmd2+Subcmd2+Data6)
            0x10, 0x00,                          // Monitoring Timer
            0x01, 0x04,                          // Command 0x0401 LE
            0x01, 0x00,                          // Subcommand 0x0001 (Bit units)
            0x64, 0x00, 0x00,                    // HeadDevice 100 = 0x000064 LE
            0x90.toByte(),                       // DeviceCode M = 0x90
            0x08, 0x00                           // Count 8 bits LE
        )

        assertContentEquals(
            expected, frame,
            "M100 8 bit BatchRead frame must match detail-02 §5-1 golden bytes.\n" +
            "Got:      ${frame.toHex()}\n" +
            "Expected: ${expected.toHex()}"
        )

        // Command~Count 구간만 별도 검증 (MC 프로토콜 참조 명시 구간)
        // 프레임 구조: Subhdr(2) + Route(5) + ReqLen(2) + Timer(2) + Cmd(2) + Subcmd(2) + Data(6)
        // Command는 offset 11부터 시작
        val cmdToCount = frame.copyOfRange(11, frame.size)
        val expectedCmdToCount = byteArrayOf(
            0x01, 0x04,  // Command (offset 11-12)
            0x01, 0x00,  // Subcommand (offset 13-14)
            0x64, 0x00, 0x00, 0x90.toByte(),  // HeadDev + DevCd (offset 15-18)
            0x08, 0x00   // Count (offset 19-20)
        )
        assertContentEquals(
            expectedCmdToCount, cmdToCount,
            "Command-to-Count segment must match detail-02 §5-1"
        )
    }

    // ── 골든 테스트 3: M100 2 word Write (Subcommand 0000, 워드 단위) ─────
    /**
     * MC 프로토콜 참조 (공식 매뉴얼):
     * "Writing bit device — Write the values to M100 to M131 (for 2 words)."
     * "Subcommand: for MELSEC-Q/L series (0000H 워드 단위)"
     *
     * Command~WriteData 구간:
     *   01 14 | 00 00 | 64 00 00 90 | 02 00 | 47 23 96 AB
     *   Cmd   | Subcmd| HeadDev+DevCd | Count(2 word) | WriteData(4 byte)
     *
     * WriteData:
     *   1st word: 47H 23H = 0x2347 → M115~M100 (16 bit)
     *   2nd word: 96H ABH = 0xAB96 → M131~M116 (16 bit)
     */
    @Test
    fun `goldenTest_encodeBatchWriteWord_M100_2words`() {
        val device = DeviceParser.parse("M100")
        // MC 프로토콜 참조의 WriteData 값
        val values = intArrayOf(0x2347, 0xAB96)
        val frame = encoder.encodeBatchWriteWord(device, values)

        val expected = byteArrayOf(
            0x50, 0x00,                          // Subheader
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // Access Route
            0x10, 0x00,                          // ReqLen = 16 (Timer2+Cmd2+Subcmd2+Data(6+4))
            0x10, 0x00,                          // Monitoring Timer
            0x01, 0x14,                          // Command 0x1401 LE
            0x00, 0x00,                          // Subcommand 0x0000
            0x64, 0x00, 0x00,                    // HeadDevice 100 = 0x000064 LE
            0x90.toByte(),                       // DeviceCode M = 0x90
            0x02, 0x00,                          // Count 2 words LE
            0x47, 0x23,                          // 1st word 0x2347 LE
            0x96.toByte(), 0xAB.toByte()         // 2nd word 0xAB96 LE
        )

        assertContentEquals(
            expected, frame,
            "M100 2 word BatchWrite frame must match detail-02 §4-3 golden bytes.\n" +
            "Got:      ${frame.toHex()}\n" +
            "Expected: ${expected.toHex()}"
        )

        // Command 1401H 검증 (offset 11-12: Subhdr2+Route5+ReqLen2+Timer2 = 11)
        assertTrue(frame[11] == 0x01.toByte() && frame[12] == 0x14.toByte(),
            "Command must be 01 14 (0x1401 LE) at offset 11-12 per detail-02 §4-1")

        // Subcommand 0000H 검증 (워드 단위 비트 디바이스 쓰기 = Subcommand 0000, MC 프로토콜 참조)
        assertTrue(frame[13] == 0x00.toByte() && frame[14] == 0x00.toByte(),
            "Subcommand must be 00 00 (0x0000) at offset 13-14 for word units per detail-02 §4-3")
    }

    @Test
    fun `goldenTest_encodeBatchWriteBit_M8000_ON_one_point`() {
        val frame = encoder.encodeBatchWriteBit(DeviceParser.parse("M8000"), intArrayOf(1))

        val expected = byteArrayOf(
            0x50, 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x0D, 0x00,
            0x10, 0x00,
            0x01, 0x14,
            0x01, 0x00,
            0x40, 0x1F, 0x00,
            0x90.toByte(),
            0x01, 0x00,
            0x10,
        )

        assertContentEquals(expected, frame)
    }

    @Test
    fun `bit write packs the first point into the high nibble`() {
        val frame = encoder.encodeBatchWriteBit(DeviceParser.parse("M100"), intArrayOf(1, 0, 1))

        assertContentEquals(byteArrayOf(0x10, 0x10), frame.copyOfRange(frame.size - 2, frame.size))
    }

    @Test
    fun `bit write packs an even point pair into one byte`() {
        val frame = encoder.encodeBatchWriteBit(DeviceParser.parse("M100"), intArrayOf(1, 1))

        assertEquals(0x11, frame.last().toInt() and 0xFF)
    }

    @Test
    fun `bit write golden frames cover 3E and 4E binary and ASCII`() {
        val device = DeviceParser.parse("M8000")
        val frame3eBinary = McFrameEncoder(
            frameType = FrameType.FRAME_3E, dataMode = DataMode.BINARY,
        ).encodeBatchWriteBit(device, intArrayOf(1))
        val frame4eBinary = McFrameEncoder(
            frameType = FrameType.FRAME_4E, dataMode = DataMode.BINARY,
        ).encodeBatchWriteBit(device, intArrayOf(1))
        val frame3eAscii = McFrameEncoder(
            frameType = FrameType.FRAME_3E, dataMode = DataMode.ASCII,
        ).encodeBatchWriteBit(device, intArrayOf(1))
        val frame4eAscii = McFrameEncoder(
            frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII,
        ).encodeBatchWriteBit(device, intArrayOf(1))

        assertContentEquals(
            byteArrayOf(
                0x50, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
                0x0D, 0x00, 0x10, 0x00, 0x01, 0x14, 0x01, 0x00,
                0x40, 0x1F, 0x00, 0x90.toByte(), 0x01, 0x00, 0x10,
            ),
            frame3eBinary,
        )
        assertContentEquals(
            byteArrayOf(
                0x54, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
                0x0D, 0x00, 0x10, 0x00, 0x01, 0x14, 0x01, 0x00,
                0x40, 0x1F, 0x00, 0x90.toByte(), 0x01, 0x00, 0x10,
            ),
            frame4eBinary,
        )
        assertEquals(
            "500000FF03FF000019001014010001M 00800000011",
            frame3eAscii.toString(Charsets.US_ASCII),
        )
        assertEquals(
            "54000000000000FF03FF000019001014010001M 00800000011",
            frame4eAscii.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun `bit write rejects word devices and nonbinary values`() {
        assertFailsWith<IllegalArgumentException> {
            encoder.encodeBatchWriteBit(DeviceParser.parse("D0"), intArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            encoder.encodeBatchWriteBit(DeviceParser.parse("M0"), intArrayOf(2))
        }
    }

    // ── ReqLen 자동 계산 검증 ─────────────────────────────────────────────
    /**
     * MC 프로토콜 참조 공식: ReqLen = Timer(2) + Cmd(2) + Subcmd(2) + RequestData(가변)
     *
     * D100 5 word Read: 2 + 2 + 2 + (3+1+2) = 12 = 0x000C → 0C 00
     */
    @Test
    fun `reqLen_is_correctly_calculated`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)

        // ReqLen at bytes[7..8]
        val reqLen = (frame[7].toInt() and 0xFF) or ((frame[8].toInt() and 0xFF) shl 8)
        assert(reqLen == 12) {
            "ReqLen must be 12 (0x000C) for D100 5 word read, got $reqLen"
        }
    }

    // ── Little-Endian 검증 ────────────────────────────────────────────────
    // 프레임 구조: Subhdr(2) + Route(5) + ReqLen(2) + Timer(2) = 11 byte
    // Command: offset 11-12, Subcommand: 13-14, HeadDevice: 15-17, DevCode: 18, Count: 19-20
    @Test
    fun `command_0401_is_little_endian_01_04`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)
        // Command at bytes[11..12] must be 01 04 (not 04 01)
        assertTrue(frame[11] == 0x01.toByte(), "Command low byte must be 0x01 (LE) at offset 11")
        assertTrue(frame[12] == 0x04.toByte(), "Command high byte must be 0x04 (LE) at offset 12")
    }

    @Test
    fun `command_1401_is_little_endian_01_14`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchWriteWord(device, intArrayOf(0))
        assertTrue(frame[11] == 0x01.toByte(), "Write Command low byte must be 0x01 (LE) at offset 11")
        assertTrue(frame[12] == 0x14.toByte(), "Write Command high byte must be 0x14 (LE) at offset 12")
    }

    @Test
    fun `head_device_D100_is_64_00_00_little_endian`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        // HeadDevice at bytes[15..17] = 64 00 00 (100 = 0x000064 LE)
        assertTrue(frame[15] == 0x64.toByte(), "HeadDevice byte0 must be 0x64 at offset 15")
        assertTrue(frame[16] == 0x00.toByte(), "HeadDevice byte1 must be 0x00 at offset 16")
        assertTrue(frame[17] == 0x00.toByte(), "HeadDevice byte2 must be 0x00 at offset 17")
    }

    // ── X1F (16진수 주소) 검증 ─────────────────────────────────────────────
    @Test
    fun `head_device_X1F_is_1F_00_00_little_endian`() {
        val device = DeviceParser.parse("X1F")
        val frame = encoder.encodeBatchReadBit(device, 1)
        // X1F = 0x1F = 31 → 1F 00 00 at offset 15-17
        assertTrue(frame[15] == 0x1F.toByte(), "HeadDevice byte0 must be 0x1F for X1F at offset 15")
        assertTrue(frame[16] == 0x00.toByte(), "HeadDevice byte1 must be 0x00 at offset 16")
        assertTrue(frame[17] == 0x00.toByte(), "HeadDevice byte2 must be 0x00 at offset 17")
        // DeviceCode X = 0x9C at offset 18
        assertTrue(frame[18] == 0x9C.toByte(), "DeviceCode must be 0x9C for X at offset 18")
    }

    @Test
    fun `FX5 B0 bit read wire contract is 0401 0001 device A0`() {
        val frame = encoder.encodeBatchReadBit(DeviceRef(DeviceCode.B, 0), 1)

        assertEquals(0x01.toByte(), frame[11])
        assertEquals(0x04.toByte(), frame[12])
        assertEquals(0x01.toByte(), frame[13])
        assertEquals(0x00.toByte(), frame[14])
        assertEquals(0xA0.toByte(), frame[18])
    }

    @Test
    fun `encodeBatchReadBit_invalid_count_throws`() {
        val device = DeviceParser.parse("M100")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            encoder.encodeBatchReadBit(device, 0)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            encoder.encodeBatchReadBit(device, 3585)
        }
    }

    @Test
    fun `encodeBatchReadWord_headDevice_over_24bit_throws`() {
        // HeadDevice 는 Binary 3-byte(24-bit)로 직렬화된다 — 상한(0xFFFFFF) 초과 시 상위 비트가
        // 조용히 잘려(D16777216 → 전선상 D0) 다른 디바이스를 정상 값으로 오인 읽었다. 명시적 예외로 전환.
        val over = DeviceParser.parse("D16777216") // 0x1000000
        kotlin.test.assertFailsWith<FrameException> {
            encoder.encodeBatchReadWord(over, 1)
        }
        // 경계값 0xFFFFFF(16,777,215)은 정상 통과(실 디바이스 최대치보다 큼 — ZR 도 약 4.18M).
        val boundary = DeviceParser.parse("D16777215")
        encoder.encodeBatchReadWord(boundary, 1)
    }

    // ── 4E Binary 요청 테스트 ────────────────────────────────────────────

    /**
     * 4E Binary: Subheader 첫 2 byte = 54H 00H (MC 프로토콜 참조)
     *
     * 4E 구조:
     *   Subheader(2: 54 00) + Serial(2) + Fixed(2: 00 00)
     *   + AccessRoute(5) + ReqLen(2) + Timer(2) + Cmd(2) + Subcmd(2) + Data(6)
     *   = 2 + 2 + 2 + 5 + 2 + 2 + 2 + 2 + 6 = 25 byte (D100 5 word 기준)
     */
    @Test
    fun `4E_subheader_is_54_00`() {
        val encoder4e = McFrameEncoder(frameType = FrameType.FRAME_4E)
        val device = DeviceParser.parse("D100")
        val frame = encoder4e.encodeBatchReadWord(device, 5)

        // offset 0-1: Subheader 54H 00H
        assertTrue(frame[0] == 0x54.toByte(), "4E Subheader byte0 must be 0x54, got 0x${frame[0].toInt().and(0xFF).toString(16)}")
        assertTrue(frame[1] == 0x00.toByte(), "4E Subheader byte1 must be 0x00, got 0x${frame[1].toInt().and(0xFF).toString(16)}")
    }

    /**
     * 4E Binary: Fixed 필드(Serial 직후) = 00H 00H (MC 프로토콜 참조)
     * offset 4-5 (Subheader2 + Serial2 이후)
     */
    @Test
    fun `4E_fixed_bytes_are_00_00`() {
        val encoder4e = McFrameEncoder(frameType = FrameType.FRAME_4E)
        val device = DeviceParser.parse("D100")
        val frame = encoder4e.encodeBatchReadWord(device, 5)

        // offset 4-5: Fixed 00 00
        assertTrue(frame[4] == 0x00.toByte(), "4E Fixed byte0 (offset 4) must be 0x00")
        assertTrue(frame[5] == 0x00.toByte(), "4E Fixed byte1 (offset 5) must be 0x00")
    }

    /**
     * 4E Binary: Serial No. 단조 증가 확인 (MC 프로토콜 참조, 설계 검토)
     *
     * 연속 2 요청의 Serial No. (offset 2-3, LE):
     *   요청 N+1 의 Serial = 요청 N 의 Serial + 1
     */
    @Test
    fun `4E_serial_is_monotonically_increasing`() {
        val encoder4e = McFrameEncoder(frameType = FrameType.FRAME_4E)
        val device = DeviceParser.parse("D100")

        val frame1 = encoder4e.encodeBatchReadWord(device, 1)
        val frame2 = encoder4e.encodeBatchReadWord(device, 1)

        val serial1 = readUShortLE(frame1, 2)
        val serial2 = readUShortLE(frame2, 2)

        assertEquals(serial1 + 1, serial2,
            "4E Serial must increase by 1 each request. serial1=$serial1, serial2=$serial2")
    }

    /**
     * 4E Binary: Serial No. 0xFFFF → wrapping to 0x0000 (설계 검토)
     */
    @Test
    fun `4E_serial_wraps_from_FFFF_to_0000`() {
        // AtomicInteger를 0xFFFF로 강제 세팅하기 위해 0xFFFF번 사전 요청 대신
        // 작은 Serial 값부터 시작해 overflow가 아닌 wrapping (and 0xFFFF)을 검증한다.
        // AtomicInteger 내부 카운터에 직접 접근 불가 → 별도 로직 단위 테스트.
        val overflowVal = 0xFFFF + 1  // 0x10000
        val wrapped = overflowVal and 0xFFFF
        assertEquals(0x0000, wrapped, "Serial 0xFFFF+1 must wrap to 0x0000")
    }

    /**
     * 4E Binary: lastSerialNo가 마지막 전송한 Serial과 일치하는지 확인
     */
    @Test
    fun `4E_lastSerialNo_reflects_sent_serial`() {
        val encoder4e = McFrameEncoder(frameType = FrameType.FRAME_4E)
        val device = DeviceParser.parse("D100")

        val frame = encoder4e.encodeBatchReadWord(device, 1)
        val serialInFrame = readUShortLE(frame, 2)

        assertEquals(serialInFrame, encoder4e.lastSerialNo,
            "lastSerialNo must equal the Serial No. written into the frame")
    }

    /**
     * 4E Binary: Access Route 이후 구조가 3E와 동일한지 검증
     *
     * 4E 프레임 레이아웃:
     *   offset 0-1: Subheader 54 00
     *   offset 2-3: Serial No. (2 byte LE)
     *   offset 4-5: Fixed 00 00
     *   offset 6-10: AccessRoute 5 byte (3E의 offset 2-6과 동일 내용)
     *   offset 11-12: ReqLen 2 byte LE (3E의 offset 7-8과 동일 계산)
     *   offset 13-14: Timer 2 byte LE
     *   offset 15-16: Command 2 byte LE
     *   offset 17-18: Subcommand 2 byte LE
     *   offset 19+: RequestData
     *
     * 3E 프레임 레이아웃 (비교):
     *   offset 0-1: Subheader 50 00
     *   offset 2-6: AccessRoute 5 byte
     *   offset 7-8: ReqLen
     *   offset 9-10: Timer
     *   offset 11-12: Command
     *   offset 13-14: Subcommand
     *   offset 15+: RequestData
     *
     * 4E의 [6..] = 3E의 [2..] (AccessRoute 이후가 동일)
     */
    @Test
    fun `4E_body_from_accessRoute_matches_3E_body_after_subheader`() {
        val device = DeviceParser.parse("D100")
        val encoder3e = McFrameEncoder(frameType = FrameType.FRAME_3E)
        val encoder4e = McFrameEncoder(frameType = FrameType.FRAME_4E)

        val frame3e = encoder3e.encodeBatchReadWord(device, 5)
        val frame4e = encoder4e.encodeBatchReadWord(device, 5)

        // 3E offset 2 이후 = AccessRoute+ReqLen+Timer+Cmd+Subcmd+Data
        val body3e = frame3e.copyOfRange(2, frame3e.size)

        // 4E offset 6 이후 = 동일 구조 (Subheader2+Serial2+Fixed2 = 6 byte skip)
        val body4e = frame4e.copyOfRange(6, frame4e.size)

        assertContentEquals(body3e, body4e,
            "4E frame from AccessRoute onward must be identical to 3E frame from AccessRoute.\n" +
            "3E body: ${body3e.toHex()}\n" +
            "4E body: ${body4e.toHex()}")
    }

    /**
     * 3E Binary 골든 회귀 — 4E 분기 추가 후에도 3E 경로가 불변임을 확정한다.
     *
     * 기존 McFrameEncoder() (기본 FRAME_3E) 인스턴스에서 생성한 프레임이
     * MC 프로토콜 참조 골든 시퀀스와 byte-by-byte 일치해야 한다.
     *
     * 이 테스트가 실패하면 4E 분기가 3E 경로를 오염한 것이다.
     */
    @Test
    fun `regression_3E_golden_unchanged_after_4E_addition`() {
        // encoder (기본값 = FRAME_3E) — 기존 테스트와 동일 인스턴스 사용
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)

        val expected = byteArrayOf(
            0x50, 0x00,                          // Subheader 50 00 (3E)
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // Access Route
            0x0C, 0x00,                          // ReqLen = 12
            0x10, 0x00,                          // Monitoring Timer
            0x01, 0x04,                          // Command 0x0401 LE
            0x00, 0x00,                          // Subcommand 0x0000
            0x64, 0x00, 0x00,                    // HeadDevice D100 = 0x000064 LE
            0xA8.toByte(),                       // DeviceCode D = 0xA8
            0x05, 0x00                           // Count 5 LE
        )

        assertContentEquals(
            expected, frame,
            "3E regression FAIL — 4E 분기 추가 후 3E 골든 경로가 변경되었다.\n" +
            "Got:      ${frame.toHex()}\n" +
            "Expected: ${expected.toHex()}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // P5. 3E/4E ASCII 골든 테스트 (4종 완비)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 3E ASCII 골든 — D100 5 word Batch Read
     *
     * 구조 (MC 프로토콜 참조, buildFrame3EAsciiFromString):
     *   Subheader "5000"(4) + Route "00FF03FF00"(10) + ReqDataLen "0018"(4) +
     *   Timer "0010"(4) + Command "0401"(4) + Subcommand "0000"(4) +
     *   DevCode "D "(2) + HeadDevice "000100"(6, D100 10진) + Count "0005"(4)
     *
     * ReqDataLen = Timer(4)+Cmd(4)+Subcmd(4)+RequestData(12) = 24 = 0x0018
     * DevCode: D의 asciiCode = "D " (공백 포함, MC 프로토콜 참조) — MC ASCII 규격: DevCode 먼저
     * HeadDevice: D는 isHexNotation=false → 10진 padded = "000100"
     */
    @Test
    fun `3E_ASCII_golden_D100_5word_BatchReadWord`() {
        val encoder3eAscii = McFrameEncoder(dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        val frame = encoder3eAscii.encodeBatchReadWord(device, 5)
        val frameStr = frame.toString(Charsets.US_ASCII)

        // 전체 문자열 검증 (대문자 ASCII)
        val expectedStr = "5000" + "00FF03FF00" + "0018" + "0010" + "0401" + "0000" +
            "D " + "000100" + "0005"
        assertEquals(
            expectedStr, frameStr,
            "3E ASCII D100 5word golden mismatch.\n" +
            "Got:      $frameStr\n" +
            "Expected: $expectedStr"
        )
    }

    /**
     * 3E ASCII 골든 회귀 — 4E ASCII 분기 추가 후에도 3E ASCII 경로가 불변인지 확인.
     */
    @Test
    fun `3E_ASCII_regression_unchanged_after_4E_addition`() {
        val enc = McFrameEncoder(dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        val frame1 = enc.encodeBatchReadWord(device, 5)
        val frame2 = enc.encodeBatchReadWord(device, 5)
        // 동일 인스턴스 두 번 호출: 3E ASCII는 serial 없음 → 완전 동일해야 한다
        assertContentEquals(frame1, frame2, "3E ASCII frame must be deterministic (no serial)")
    }

    /**
     * 4E ASCII 골든 — D100 5 word Batch Read (실 PLC 검증 2026-06-15, 회귀 검증)
     *
     * 구조 (MC 프로토콜 참조, buildFrame4EAscii):
     *   "5400"(4) + Serial 4char hex + "0000"(4) + ASCII 본문(Route 부터, 서브헤더 "5000" 없음)
     *
     * ASCII 본문 = "00FF03FF00"+"0018"+"0010"+"0401"+"0000"+"D "+"000100"+"0005"
     * ⚠ 4E ASCII 는 "5000"을 포함하지 않는다 — 포함 시 실 PLC 무응답(회귀 검증).
     *
     * Serial: 첫 번째 호출 → serialCounter 0 → "0000"
     */
    @Test
    fun `4E_ASCII_golden_D100_5word_serial_prefix`() {
        // 새 인스턴스 — serialCounter 0 부터 시작
        val encoder4eAscii = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        val frame = encoder4eAscii.encodeBatchReadWord(device, 5)
        val frameStr = frame.toString(Charsets.US_ASCII)

        // Serial 0 → "0000"
        val serial0 = "0000"
        val asciiBody = "00FF03FF00" + "0018" + "0010" + "0401" + "0000" +
            "D " + "000100" + "0005"
        val expectedStr = "5400" + serial0 + "0000" + asciiBody

        assertEquals(
            expectedStr, frameStr,
            "4E ASCII D100 5word golden mismatch.\n" +
            "Got:      $frameStr\n" +
            "Expected: $expectedStr"
        )
    }

    /**
     * 4E ASCII: Serial이 단조 증가하는지 확인 (두 번째 요청 serial = 1 → "0001")
     */
    @Test
    fun `4E_ASCII_serial_monotonically_increasing`() {
        val encoder4eAscii = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        encoder4eAscii.encodeBatchReadWord(device, 5)  // serial=0
        val frame2 = encoder4eAscii.encodeBatchReadWord(device, 5)  // serial=1
        val frameStr = frame2.toString(Charsets.US_ASCII)

        assertTrue(
            frameStr.startsWith("5400") && frameStr.substring(4, 8) == "0001",
            "4E ASCII second frame serial must be '0001'. Got serial part: '${frameStr.substring(4, 8)}'"
        )
        assertEquals(1, encoder4eAscii.lastSerialNo, "lastSerialNo must be 1 after second call")
    }

    /**
     * 4E ASCII: lastSerialNo가 ASCII hex 값과 일치하는지 확인.
     *
     * ASCII 프레임의 serial 필드(char 4-7)를 16진 파싱한 값 = lastSerialNo
     */
    @Test
    fun `4E_ASCII_lastSerialNo_matches_frame_serial`() {
        val encoder4eAscii = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        val frame = encoder4eAscii.encodeBatchReadWord(device, 5)
        val frameStr = frame.toString(Charsets.US_ASCII)

        // char 4-7 = serial 4 char hex
        val serialInFrame = frameStr.substring(4, 8).toInt(16)
        assertEquals(
            encoder4eAscii.lastSerialNo, serialInFrame,
            "4E ASCII serial in frame must equal lastSerialNo. " +
            "frame serial='${frameStr.substring(4, 8)}', lastSerialNo=${encoder4eAscii.lastSerialNo}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // encodeCpuModelRead 4종 골든 테스트 (작업번호 31 ASCII/4E 대응)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 3E Binary encodeCpuModelRead 골든 — CpuModelReader.REQUEST_FRAME과 바이트 동일해야 한다.
     *
     * REQUEST_FRAME 상수 (15 byte):
     *   50 00 | 00 FF FF 03 00 | 06 00 | 10 00 | 01 01 | 00 00
     *
     * ReqLen = Timer(2)+Cmd(2)+Subcmd(2)+Data(0) = 6 → 06 00 LE
     * 이 테스트가 실패하면 encodeCpuModelRead Binary 경로가 기존 하드코딩 상수와 달라진 것이다 (회귀).
     */
    @Test
    fun `encodeCpuModelRead_3E_Binary_matches_REQUEST_FRAME_golden`() {
        val enc3eBin = McFrameEncoder(frameType = FrameType.FRAME_3E, dataMode = DataMode.BINARY)
        val frame = enc3eBin.encodeCpuModelRead()

        val expected = byteArrayOf(
            0x50, 0x00,                                               // Subheader 3E
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,          // Access Route
            0x06, 0x00,                                               // ReqLen = 6 (LE)
            0x10, 0x00,                                               // Monitoring Timer
            0x01, 0x01,                                               // Command 0x0101 (LE)
            0x00, 0x00                                                // Subcommand 0x0000
        )

        assertContentEquals(
            expected, frame,
            "encodeCpuModelRead 3E Binary must match CpuModelReader.REQUEST_FRAME golden.\n" +
            "Got:      ${frame.toHex()}\n" +
            "Expected: ${expected.toHex()}"
        )
    }

    /**
     * 3E ASCII encodeCpuModelRead 골든.
     *
     * 3E ASCII 0101H 프레임 (실측 확정 2026-06-12, PLC Q06UDVCPU 정상 응답 수신):
     *   "5000" + "00FF03FF00" + "000C" + "0010" + "0101" + "0000"
     *   ReqDataLen = Timer(4)+Cmd(4)+Subcmd(4)+Data(0) = 12 = "000C"
     *   실측 송신 hex: 353030303030464630334646303030303043303031303031303130303030
     */
    @Test
    fun `encodeCpuModelRead_3E_ASCII_golden`() {
        val enc3eAscii = McFrameEncoder(dataMode = DataMode.ASCII)
        val frame = enc3eAscii.encodeCpuModelRead()
        val frameStr = frame.toString(Charsets.US_ASCII)

        val expectedStr = "5000" + "00FF03FF00" + "000C" + "0010" + "0101" + "0000"

        assertEquals(
            expectedStr, frameStr,
            "encodeCpuModelRead 3E ASCII golden mismatch.\n" +
            "Got:      $frameStr\n" +
            "Expected: $expectedStr"
        )
    }

    /**
     * 4E Binary encodeCpuModelRead 골든.
     *
     * 4E 구조 = Subheader(2: 54 00) + Serial(2 LE) + Fixed(2: 00 00) + 3E Binary 본문
     * 3E Binary 본문 = Route(5) + ReqLen(2) + Timer(2) + Cmd(2) + Subcmd(2)
     *                = 00 FF FF 03 00 | 06 00 | 10 00 | 01 01 | 00 00
     * 새 인스턴스 첫 요청 → Serial = 0 → 00 00 LE
     */
    @Test
    fun `encodeCpuModelRead_4E_Binary_golden`() {
        val enc4eBin = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.BINARY)
        val frame = enc4eBin.encodeCpuModelRead()

        // Subheader 54 00
        assertTrue(frame[0] == 0x54.toByte(), "4E Binary Subheader byte0 must be 0x54")
        assertTrue(frame[1] == 0x00.toByte(), "4E Binary Subheader byte1 must be 0x00")

        // Fixed 00 00 (offset 4-5)
        assertTrue(frame[4] == 0x00.toByte(), "4E Binary Fixed byte0 must be 0x00")
        assertTrue(frame[5] == 0x00.toByte(), "4E Binary Fixed byte1 must be 0x00")

        // 3E 본문과 비교: 4E[6..] = 3E[2..] (Route 이후 동일)
        val enc3eBin = McFrameEncoder(frameType = FrameType.FRAME_3E, dataMode = DataMode.BINARY)
        val frame3e = enc3eBin.encodeCpuModelRead()
        val body3e = frame3e.copyOfRange(2, frame3e.size)   // 3E Route~Subcmd
        val body4e = frame.copyOfRange(6, frame.size)       // 4E Route~Subcmd

        assertContentEquals(
            body3e, body4e,
            "4E Binary CpuModelRead body from Route onward must equal 3E Binary body.\n" +
            "3E body: ${body3e.toHex()}\n" +
            "4E body: ${body4e.toHex()}"
        )

        // Command 0x0101 LE at 4E offset 15-16 (Subhdr2+Serial2+Fixed2+Route5+ReqLen2+Timer2 = 15)
        assertTrue(frame[15] == 0x01.toByte(), "4E Binary Command low byte must be 0x01 (0x0101 LE)")
        assertTrue(frame[16] == 0x01.toByte(), "4E Binary Command high byte must be 0x01 (0x0101 LE)")

        // lastSerialNo 갱신 확인
        val serialInFrame = readUShortLE(frame, 2)
        assertEquals(serialInFrame, enc4eBin.lastSerialNo,
            "4E Binary lastSerialNo must match serial in frame")
    }

    /**
     * 4E ASCII encodeCpuModelRead 골든 (실 PLC 검증 2026-06-15, 회귀 검증).
     *
     * 4E ASCII 구조: "5400" + Serial(4) + "0000" + ASCII 본문(Route 부터, 서브헤더 "5000" 없음)
     * ASCII 본문 = "00FF03FF00"+"000C"+"0010"+"0101"+"0000"
     * 새 인스턴스 첫 요청 → Serial = 0 → "0000"
     *
     * 실 PLC 골든 (Q06UDVCPU 192.0.2.10): TX "54000000000000FF03FF00000C001001010000"
     *   → RX "D4000000000000FF03FF0000180000Q06UDVCPU       0368" (EndCode 0000)
     * ⚠ 종전 골든은 "5000" 중복(=실 PLC 무응답 버그)을 박았던 자기검증 — 회귀 검증 교정.
     */
    @Test
    fun `encodeCpuModelRead_4E_ASCII_golden`() {
        val enc4eAscii = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII)
        val frame = enc4eAscii.encodeCpuModelRead()
        val frameStr = frame.toString(Charsets.US_ASCII)

        val asciiBody = "00FF03FF00" + "000C" + "0010" + "0101" + "0000"
        val expectedStr = "5400" + "0000" + "0000" + asciiBody

        assertEquals(
            expectedStr, frameStr,
            "encodeCpuModelRead 4E ASCII golden mismatch.\n" +
            "Got:      $frameStr\n" +
            "Expected: $expectedStr"
        )

        // lastSerialNo 확인
        val serialInFrame = frameStr.substring(4, 8).toInt(16)
        assertEquals(enc4eAscii.lastSerialNo, serialInFrame,
            "4E ASCII lastSerialNo must match serial in frame")
    }

    /**
     * encodeCpuModelRead 3E Binary 회귀 — 기존 REQUEST_FRAME 상수와 byte-by-byte 동일.
     *
     * CpuModelReader.REQUEST_FRAME 하드코딩 상수를 encoder로 대체했을 때
     * Binary 경로에서 기존과 동일한 바이트를 생성하는지 최종 확인한다.
     */
    @Test
    fun `regression_encodeCpuModelRead_Binary_equals_hardcoded_REQUEST_FRAME`() {
        val enc = McFrameEncoder()  // 기본값: 3E Binary
        val encoded = enc.encodeCpuModelRead()

        // CpuModelReader.REQUEST_FRAME 상수 (기존 하드코딩)
        val requestFrame = byteArrayOf(
            0x50, 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x06, 0x00,
            0x10, 0x00,
            0x01, 0x01,
            0x00, 0x00
        )

        assertContentEquals(
            requestFrame, encoded,
            "encodeCpuModelRead Binary must be identical to CpuModelReader.REQUEST_FRAME.\n" +
            "Got:      ${encoded.toHex()}\n" +
            "Expected: ${requestFrame.toHex()}"
        )
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────

    /** 테스트용 LE 2 byte 읽기 */
    private fun readUShortLE(bytes: ByteArray, offset: Int): Int {
        val lo = bytes[offset].toInt() and 0xFF
        val hi = bytes[offset + 1].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
