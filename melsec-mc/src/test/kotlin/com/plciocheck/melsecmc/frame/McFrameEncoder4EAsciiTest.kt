// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.frame

import com.plciocheck.melsecmc.device.DeviceParser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * McFrameEncoder 4E ASCII 골든 테스트 (Phase 4 신규, 회귀 검증 실 PLC 교정 2026-06-15)
 *
 * 4E ASCII 프레임 규격 (MC 프로토콜 참조, 실 PLC 골든):
 *   Subheader(4):  "5400"
 *   Serial No.(4): 4 char hex 대문자 (단조 증가, 0~FFFF wrapping)
 *   Fixed(4):      "0000"
 *   ASCII 본문(Subheader 제외): Route(10) + ReqDataLen(4) + Timer(4) + Cmd(4) + Subcmd(4) + ReqData
 *   ⚠ 4E ASCII 는 3E 서브헤더 "5000"을 포함하지 않는다 — 포함 시 실 PLC 무응답(회귀 검증).
 *
 * 골든 계산 (D100 5 word, Serial=0x0000):
 *   "5400"              4 char Subheader 4E
 *   "0000"              4 char Serial (첫 요청 = 0)
 *   "0000"              4 char Fixed
 *   "00FF03FF00"        10 char Access Route
 *   "0018"              4 char ReqDataLen = 4+4+4+12 = 24 = 0x18
 *   "0010"              4 char Timer
 *   "0401"              4 char Command
 *   "0000"              4 char Subcommand
 *   "D "                2 char DeviceCode (코드 먼저, 회귀 검증)
 *   "000100"            6 char HeadDevice (D100 = 100 = 10진)
 *   "0005"              4 char Count
 *   = 4+4+4+10+4+4+4+4+12 = 50 char
 */
class McFrameEncoder4EAsciiTest {

    // ── 골든 테스트 1: D100 5 word Batch Read (4E ASCII, Serial=0) ──────────
    /**
     * 4E ASCII 첫 요청(Serial=0) 전체 문자열 검증.
     *
     * 전체 ASCII 문자열:
     *   "5400"            Subheader 4E
     *   "0000"            Serial No. 0 (첫 요청)
     *   "0000"            Fixed
     *   "5000"            3E ASCII Subheader
     *   "00FF03FF00"      Access Route
     *   "0018"            ReqDataLen = 24
     *   "0010"            Timer
     *   "0401"            Command BatchRead
     *   "0000"            Subcommand Word Q/L
     *   "000100"          HeadDevice D100 = 100 십진 6-digit
     *   "D "              DeviceCode D
     *   "0005"            Count 5
     */
    @Test
    fun `4E_ASCII_golden_D100_5words_serial0`() {
        val encoder = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)
        val frameStr = frame.toString(Charsets.US_ASCII)

        // Subheader: "5400"
        assertEquals("5400", frameStr.substring(0, 4),
            "4E ASCII Subheader must be '5400'")

        // Serial No.: "0000" (첫 요청, Serial=0)
        assertEquals("0000", frameStr.substring(4, 8),
            "4E ASCII Serial No. must be '0000' for first request")

        // Fixed: "0000"
        assertEquals("0000", frameStr.substring(8, 12),
            "4E ASCII Fixed must be '0000'")

        // ⚠ 4E 헤더(12 char) 직후는 Route — "5000" 3E 서브헤더가 중복되면 안 됨(실 PLC 무응답, 회귀 검증)
        assertEquals("00FF03FF00", frameStr.substring(12, 22),
            "4E ASCII body must start with Access Route (NO duplicate '5000' subheader)")

        // ReqDataLen (offset 22-25): "0018" = 24
        assertEquals("0018", frameStr.substring(22, 26),
            "4E ASCII ReqDataLen must be '0018' (24 chars)")

        // Timer (offset 26-29)
        assertEquals("0010", frameStr.substring(26, 30),
            "4E ASCII Timer must be '0010'")

        // Command (offset 30-33)
        assertEquals("0401", frameStr.substring(30, 34),
            "4E ASCII Command must be '0401'")

        // Subcommand (offset 34-37)
        assertEquals("0000", frameStr.substring(34, 38),
            "4E ASCII Subcommand must be '0000' (word units)")

        // 전체 길이: 4+4+4 + (10+4+4+4+4+12) = 12 + 38 = 50 char
        assertEquals(50, frameStr.length,
            "4E ASCII D100 5word frame must be 50 chars, got ${frameStr.length}")
    }

    // ── Serial 단조 증가 검증 ──────────────────────────────────────────────
    /**
     * 4E ASCII Serial No. 단조 증가:
     *   첫 요청 Serial = 0, 두 번째 = 1, ...
     *   offset 4-7 (char 4~7) 에 4 char hex 대문자로 직렬화
     */
    @Test
    fun `4E_ASCII_serial_increases_monotonically`() {
        val encoder = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")

        val frame1 = encoder.encodeBatchReadWord(device, 1)
        val frame2 = encoder.encodeBatchReadWord(device, 1)

        val serial1 = frame1.toString(Charsets.US_ASCII).substring(4, 8).toInt(16)
        val serial2 = frame2.toString(Charsets.US_ASCII).substring(4, 8).toInt(16)

        assertEquals(serial1 + 1, serial2,
            "4E ASCII Serial must increase by 1 each request. serial1=$serial1, serial2=$serial2")
    }

    /**
     * 4E ASCII Serial No. offset 4-7 = 대문자 hex 4 char
     * lastSerialNo가 프레임에 기록된 Serial과 일치해야 한다.
     */
    @Test
    fun `4E_ASCII_lastSerialNo_matches_frame_serial`() {
        val encoder = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        val serialInFrame = frame.toString(Charsets.US_ASCII).substring(4, 8).toInt(16)

        assertEquals(serialInFrame, encoder.lastSerialNo,
            "lastSerialNo must equal Serial in frame")
    }

    // ── 4종 매트릭스 커버 — 각 조합이 UnsupportedOperationException 없이 반환 ──
    /**
     * 4종 조합 모두 encodeBatchReadWord를 정상 호출 가능하다 (예외 없음).
     *
     * 매트릭스:
     *   3E Binary  / 3E ASCII
     *   4E Binary  / 4E ASCII
     */
    @Test
    fun `matrix_3E_Binary_encodeBatchReadWord_succeeds`() {
        val encoder = McFrameEncoder(frameType = FrameType.FRAME_3E, dataMode = DataMode.BINARY)
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        assertTrue(frame.isNotEmpty(), "3E Binary frame must not be empty")
        assertEquals(0x50.toByte(), frame[0], "3E Binary Subheader byte0 must be 0x50")
    }

    @Test
    fun `matrix_3E_ASCII_encodeBatchReadWord_succeeds`() {
        val encoder = McFrameEncoder(frameType = FrameType.FRAME_3E, dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        val s = frame.toString(Charsets.US_ASCII)
        assertTrue(s.startsWith("5000"), "3E ASCII frame must start with '5000'")
    }

    @Test
    fun `matrix_4E_Binary_encodeBatchReadWord_succeeds`() {
        val encoder = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.BINARY)
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        assertTrue(frame.isNotEmpty(), "4E Binary frame must not be empty")
        assertEquals(0x54.toByte(), frame[0], "4E Binary Subheader byte0 must be 0x54")
    }

    @Test
    fun `matrix_4E_ASCII_encodeBatchReadWord_succeeds`() {
        val encoder = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        val s = frame.toString(Charsets.US_ASCII)
        assertTrue(s.startsWith("5400"), "4E ASCII frame must start with '5400'")
    }

    // ── 3E 회귀 — 4E ASCII 추가 후 3E ASCII 경로가 불변임을 확정 ─────────────
    /**
     * buildFrameAsciiForType 분기 추가 후에도 3E ASCII 경로가 변경되지 않았음을 확인.
     *
     * D100 5 word 3E ASCII 골든:
     *   "500000FF03FF000018001004010000000100D 0005"
     */
    @Test
    fun `regression_3E_ASCII_unchanged_after_4E_ASCII_addition`() {
        val encoder = McFrameEncoder(frameType = FrameType.FRAME_3E, dataMode = DataMode.ASCII)
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)
        val s = frame.toString(Charsets.US_ASCII)

        // 3E ASCII는 "5000"으로 시작해야 한다 (4E ASCII "5400" 아님)
        assertTrue(s.startsWith("5000"),
            "3E ASCII regression FAIL — frame must start with '5000', got '${s.take(4)}'")

        // 4E prefix("5400")가 삽입되지 않았는지 확인
        assertTrue(!s.startsWith("5400"),
            "3E ASCII regression FAIL — frame must NOT start with '5400'")
    }

    /**
     * 3E Binary 회귀 — 4E ASCII 추가 후 3E Binary 골든이 불변임을 확인.
     *
     * MC 프로토콜 참조: D100 5 word → 50 00 | 00 FF FF 03 00 | 0C 00 | ...
     */
    @Test
    fun `regression_3E_Binary_unchanged_after_4E_ASCII_addition`() {
        val encoder = McFrameEncoder(frameType = FrameType.FRAME_3E, dataMode = DataMode.BINARY)
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)

        assertEquals(0x50.toByte(), frame[0], "3E Binary regression FAIL — byte0 must be 0x50")
        assertEquals(0x00.toByte(), frame[1], "3E Binary regression FAIL — byte1 must be 0x00")
    }

    // ── 4E ASCII 구조 세부 검증 ────────────────────────────────────────────
    /**
     * 4E ASCII 헤더(12 char: Subheader+Serial+Fixed) 직후 본문이
     * 3E ASCII 프레임의 '서브헤더 제외 본문'(3E frame[4:])과 동일하다.
     *
     * 4E ASCII = "5400" + Serial(4) + "0000" + [본문 = Route 부터]
     * 3E ASCII = "5000" + [동일 본문]
     * 따라서 frame4e[12:] == frame3e[4:].
     * ⚠ frame4e[12:] 가 "5000"으로 시작하면(=3E 전체 재사용) 중복 서브헤더 버그 — 실 PLC 무응답(회귀 검증).
     */
    @Test
    fun `4E_ASCII_body_after_header_equals_3E_ASCII_body_without_subheader`() {
        val device = DeviceParser.parse("D100")
        val encoder3e = McFrameEncoder(frameType = FrameType.FRAME_3E, dataMode = DataMode.ASCII)
        val encoder4e = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.ASCII)

        val frame3e = encoder3e.encodeBatchReadWord(device, 5).toString(Charsets.US_ASCII)
        val frame4e = encoder4e.encodeBatchReadWord(device, 5).toString(Charsets.US_ASCII)

        // 3E ASCII 서브헤더("5000", 4 char) 제외 본문
        val body3eNoSubheader = frame3e.substring(4)
        // 4E ASCII 헤더(Subheader+Serial+Fixed = 12 char) 제외 본문
        val body4e = frame4e.substring(12)

        assertEquals(body3eNoSubheader, body4e,
            "4E ASCII body (after 12-char header) must equal 3E ASCII body WITHOUT '5000' subheader.\n" +
            "3E[4:]: $body3eNoSubheader\n4E[12:]: $body4e")

        // 회귀 가드: 4E 본문이 "5000"으로 시작하면 중복 서브헤더 버그(회귀 검증)
        assertTrue(!body4e.startsWith("5000"),
            "4E ASCII body must NOT start with '5000' (duplicate subheader bug, TS-050)")
    }
}
