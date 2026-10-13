// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.frame

import com.plciocheck.melsecmc.device.DeviceParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * McFrameEncoder 3E ASCII 골든 테스트 (Phase 3 신규)
 *
 * 3E ASCII 프레임 규격 (MC 프로토콜 참조, 프레임 검증, 설계 검토):
 *   Subheader(4): "5000"
 *   Access Route(10): "00FF03FF00"
 *   ReqDataLen(4): Timer(4)+Cmd(4)+Subcmd(4)+ReqData char 수
 *   Timer(4): "0010"
 *   Command(4): "0401" 또는 "1401"
 *   Subcommand(4): "0000" 또는 "0001"
 *   RequestData: DeviceCode(2) + HeadDevice(6) + Count(4)
 *
 * 모든 수치 값은 대문자 hex 표기 (little-endian 아님).
 *
 * 골든 계산 (D100 5 word):
 *   DeviceCode: "D " (단일 문자 + 공백)
 *   HeadDevice: "000100" (D100 = 100 = 10진)
 *   Count: "0005" (5 = 0x0005)
 *   RequestData: "D 000100" + "0005" = 12 char
 *   ReqDataLen: 4(Timer)+4(Cmd)+4(Subcmd)+12(ReqData) = 24 = 0x18 → "0018"
 *
 * 전체 프레임 (US-ASCII 바이트):
 *   "5000" "00FF03FF00" "0018" "0010" "0401" "0000" "D " "000100" "0005"
 *   = "500000FF03FF000018001004010000D 0001000005" (42 char)
 */
class McFrameEncoder3EAsciiTest {

    private val encoder = McFrameEncoder(dataMode = DataMode.ASCII)

    @Test
    fun `FX ASCII X와 Y는 내부 canonical 번호를 8진 HeadDevice로 자동 인코딩한다`() {
        val fxEncoder = McFrameEncoder(
            dataMode = DataMode.ASCII,
            asciiXyAddressFormat = AsciiXyAddressFormat.OCTAL,
        )

        val xRead = fxEncoder.encodeBatchReadBit(DeviceParser.parse("X8"), 1)
            .toString(Charsets.US_ASCII)
        val yRead = fxEncoder.encodeBatchReadBit(DeviceParser.parse("Y8"), 1)
            .toString(Charsets.US_ASCII)

        assertTrue(xRead.contains("X 0000100001"), xRead)
        assertTrue(yRead.contains("Y 0000100001"), yRead)
    }

    @Test
    fun `FX ASCII 자동 8진은 X와 Y 이외 디바이스 표기를 바꾸지 않는다`() {
        val fxEncoder = McFrameEncoder(
            dataMode = DataMode.ASCII,
            asciiXyAddressFormat = AsciiXyAddressFormat.OCTAL,
        )

        val bRead = fxEncoder.encodeBatchReadBit(DeviceParser.parse("B10"), 1)
            .toString(Charsets.US_ASCII)
        val dRead = fxEncoder.encodeBatchReadWord(DeviceParser.parse("D100"), 1)
            .toString(Charsets.US_ASCII)

        assertTrue(bRead.contains("B 0000100001"), bRead)
        assertTrue(dRead.contains("D 0001000001"), dRead)
    }

    @Test
    fun `FX ASCII 8진 정책은 읽기와 쓰기 요청에 동일하게 적용된다`() {
        val fxEncoder = McFrameEncoder(
            dataMode = DataMode.ASCII,
            asciiXyAddressFormat = AsciiXyAddressFormat.OCTAL,
        )

        val wordWrite = fxEncoder.encodeBatchWriteWord(DeviceParser.parse("X8"), intArrayOf(1))
            .toString(Charsets.US_ASCII)
        val bitWrite = fxEncoder.encodeBatchWriteBit(DeviceParser.parse("Y8"), intArrayOf(1))
            .toString(Charsets.US_ASCII)

        assertTrue(wordWrite.contains("X 00001000010001"), wordWrite)
        assertTrue(bitWrite.contains("Y 00001000011"), bitWrite)
    }

    // ── 골든 테스트 1: D100 5 word Batch Read (3E ASCII) ─────────────────
    /**
     * D100 5 word BatchReadWord 3E ASCII 골든 검증.
     *
     * 전체 ASCII 문자열 검증:
     *   "5000"            (Subheader)
     *   "00FF03FF00"      (Access Route)
     *   "0018"            (ReqDataLen = 24 = 0x18)
     *   "0010"            (Monitoring Timer)
     *   "0401"            (Command BatchRead)
     *   "0000"            (Subcommand Word Q/L)
     *   "D "              (DeviceCode D)
     *   "000100"          (HeadDevice D100 = 100, 10진 6-digit)
     *   "0005"            (Count 5)
     */
    @Test
    fun `golden_3EAscii_encodeBatchReadWord_D100_5words`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)
        val frameStr = frame.toString(Charsets.US_ASCII)

        val expected = "500000FF03FF000018001004010000D 0001000005"
        assertEquals(
            expected, frameStr,
            "D100 5 word BatchRead 3E ASCII frame must match golden string.\n" +
            "Got:      '$frameStr'\n" +
            "Expected: '$expected'"
        )
    }

    // ── Subheader 검증 ───────────────────────────────────────────────────
    @Test
    fun `3EAscii_subheader_is_5000`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        val subheader = frame.copyOf(4).toString(Charsets.US_ASCII)
        assertEquals("5000", subheader, "3E ASCII subheader must be '5000'")
    }

    // ── Access Route 검증 ────────────────────────────────────────────────
    @Test
    fun `3EAscii_access_route_is_00FF03FF00`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        val route = frame.copyOfRange(4, 14).toString(Charsets.US_ASCII)
        assertEquals("00FF03FF00", route,
            "3E ASCII Access Route must be '00FF03FF00' (detail-01 §4 ASCII 표기)")
    }

    // ── ReqDataLen 계산 검증 (설계 검토) ─────────────────────────────────
    /**
     * D100 5 word: Timer(4)+Cmd(4)+Subcmd(4)+ReqData(12) = 24 = 0x18 → "0018"
     * ReqDataLen은 프레임의 char 14-17 (byte 14-17)에 위치
     */
    @Test
    fun `3EAscii_reqDataLen_is_0018_for_D100_5words`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)
        val reqDataLen = frame.copyOfRange(14, 18).toString(Charsets.US_ASCII)
        assertEquals("0018", reqDataLen,
            "3E ASCII ReqDataLen for D100 5 word = 24 (0x18) must be '0018' (S0013 Q-2)")
    }

    // ── DeviceCode 2char 검증 ────────────────────────────────────────────
    /**
     * D → "D " (단일 문자 + 공백, MC 프로토콜 참조)
     * ASCII 프레임 offset: Subhdr(4)+Route(10)+ReqLen(4)+Timer(4)+Cmd(4)+Subcmd(4) = 30
     * DeviceCode: char 30-31 (MC ASCII 규격: DevCode 먼저, 실 PLC EndCode 0000 확인)
     */
    @Test
    fun `3EAscii_deviceCode_D_is_D_space`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)
        val devCode = frame.copyOfRange(30, 32).toString(Charsets.US_ASCII)
        assertEquals("D ", devCode, "3E ASCII DeviceCode for D must be 'D ' (1char+space, detail-03 §5)")
    }

    /**
     * M → "M " (단일 문자 + 공백)
     */
    @Test
    fun `3EAscii_deviceCode_M_is_M_space`() {
        val device = DeviceParser.parse("M100")
        val frame = encoder.encodeBatchReadBit(device, 8)
        val devCode = frame.copyOfRange(30, 32).toString(Charsets.US_ASCII)
        assertEquals("M ", devCode, "3E ASCII DeviceCode for M must be 'M '")
    }

    /**
     * TS → "TS" (2문자 디바이스, MC 프로토콜 참조)
     */
    @Test
    fun `3EAscii_deviceCode_TS_is_TS`() {
        val device = DeviceParser.parse("TS10")
        val frame = encoder.encodeBatchReadBit(device, 1)
        val devCode = frame.copyOfRange(30, 32).toString(Charsets.US_ASCII)
        assertEquals("TS", devCode, "3E ASCII DeviceCode for TS must be 'TS' (2-char code)")
    }

    // ── HeadDevice 6-digit 검증 ──────────────────────────────────────────
    /**
     * D100 → "000100" (10진 6-digit, MC 프로토콜 참조)
     * ASCII 프레임 offset: Subhdr(4)+Route(10)+ReqLen(4)+Timer(4)+Cmd(4)+Subcmd(4)+DevCode(2) = 32
     * HeadDevice: char 32-37
     */
    @Test
    fun `3EAscii_headDevice_D100_is_000100`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)
        val headDev = frame.copyOfRange(32, 38).toString(Charsets.US_ASCII)
        assertEquals("000100", headDev,
            "3E ASCII HeadDevice for D100 must be '000100' (10진 6-digit, detail-03 §6-2)")
    }

    /**
     * X1F(number=31=0x1F) → "00001F" (16진 notation, MC 프로토콜 참조)
     */
    @Test
    fun `3EAscii_headDevice_X1F_is_00001F`() {
        val device = DeviceParser.parse("X1F")
        val frame = encoder.encodeBatchReadBit(device, 1)
        val headDev = frame.copyOfRange(32, 38).toString(Charsets.US_ASCII)
        assertEquals("00001F", headDev,
            "3E ASCII HeadDevice for X1F(number=31=0x1F) must be '00001F' (16진 notation)")
    }

    // ── Count 4char 검증 ─────────────────────────────────────────────────
    /**
     * Count=5 → "0005" (4 char hex, char 38-41)
     */
    @Test
    fun `3EAscii_count_5_is_0005`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 5)
        val count = frame.copyOfRange(38, 42).toString(Charsets.US_ASCII)
        assertEquals("0005", count, "3E ASCII Count=5 must be '0005'")
    }

    /**
     * Count=960 (최대) → "03C0"
     */
    @Test
    fun `3EAscii_count_960_is_03C0`() {
        val device = DeviceParser.parse("D0")
        val frame = encoder.encodeBatchReadWord(device, 960)
        val count = frame.copyOfRange(38, 42).toString(Charsets.US_ASCII)
        assertEquals("03C0", count, "3E ASCII Count=960(=0x3C0) must be '03C0'")
    }

    // ── 대문자 고정 검증 ─────────────────────────────────────────────────
    /**
     * 모든 hex 문자는 대문자 (A~F, 소문자 a~f 금지)
     */
    @Test
    fun `3EAscii_all_hex_chars_are_uppercase`() {
        // D 디바이스: 0xA8이 포함되지 않음 (DeviceCode는 "D "). M(0x90)도 동일.
        // M100 bit read — ResponseData에 0xA8 등 hex 포함 가능한 어드레스 사용
        val device = DeviceParser.parse("X1F")  // number=0x1F → "00001F" (F 포함)
        val frame = encoder.encodeBatchReadBit(device, 1)
        val frameStr = frame.toString(Charsets.US_ASCII)

        // 소문자 hex 문자 검사
        val lowerHex = frameStr.filter { it in 'a'..'f' }
        assertEquals("", lowerHex,
            "3E ASCII frame must not contain lowercase hex chars. Found: '$lowerHex' in '$frameStr'")
    }

    // ── Command 검증 ─────────────────────────────────────────────────────
    /**
     * BatchRead 0x0401 → "0401" (값 그대로 hex, NOT little-endian "0104")
     */
    @Test
    fun `3EAscii_command_BatchRead_is_0401`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        // Timer(4) 이후 = offset 22부터 Command
        val cmd = frame.copyOfRange(22, 26).toString(Charsets.US_ASCII)
        assertEquals("0401", cmd,
            "3E ASCII BatchRead command must be '0401' (value, not LE byte order)")
    }

    /**
     * BatchWrite 0x1401 → "1401"
     */
    @Test
    fun `3EAscii_command_BatchWrite_is_1401`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchWriteWord(device, intArrayOf(0x1234))
        val cmd = frame.copyOfRange(22, 26).toString(Charsets.US_ASCII)
        assertEquals("1401", cmd,
            "3E ASCII BatchWrite command must be '1401'")
    }

    // ── Subcommand 검증 ──────────────────────────────────────────────────
    /**
     * WordUnitsQL(0x0000) → "0000"
     */
    @Test
    fun `3EAscii_subcommand_word_is_0000`() {
        val device = DeviceParser.parse("D100")
        val frame = encoder.encodeBatchReadWord(device, 1)
        val subcmd = frame.copyOfRange(26, 30).toString(Charsets.US_ASCII)
        assertEquals("0000", subcmd, "3E ASCII word read subcommand must be '0000'")
    }

    /**
     * BitUnitsQL(0x0001) → "0001"
     */
    @Test
    fun `3EAscii_subcommand_bit_is_0001`() {
        val device = DeviceParser.parse("M100")
        val frame = encoder.encodeBatchReadBit(device, 8)
        val subcmd = frame.copyOfRange(26, 30).toString(Charsets.US_ASCII)
        assertEquals("0001", subcmd, "3E ASCII bit read subcommand must be '0001'")
    }

    // ── BatchWriteWord ASCII 검증 ─────────────────────────────────────────
    /**
     * BatchWriteWord: M100 2 word, values=[0x1234, 0xABCD]
     *   DeviceCode: "M " (MC ASCII 규격: DevCode 먼저)
     *   HeadDevice: "000100" (M100=100, 10진)
     *   Count: "0002"
     *   WriteData: "1234" "ABCD"
     *   WriteData ASCII: value.toAscii4Hex()
     */
    @Test
    fun `3EAscii_encodeBatchWriteWord_M100_2words`() {
        val device = DeviceParser.parse("M100")
        val values = intArrayOf(0x1234, 0xABCD)
        val frame = encoder.encodeBatchWriteWord(device, values)
        val frameStr = frame.toString(Charsets.US_ASCII)

        // Subheader 검증
        assertTrue(frameStr.startsWith("5000"),
            "BatchWrite 3E ASCII must start with '5000'")

        // Command 1401
        val cmd = frame.copyOfRange(22, 26).toString(Charsets.US_ASCII)
        assertEquals("1401", cmd, "BatchWrite command must be '1401'")

        // DeviceCode offset=30, "M " (MC ASCII 규격: DevCode 먼저, 실 PLC EndCode 0000 확인)
        val devCode = frame.copyOfRange(30, 32).toString(Charsets.US_ASCII)
        assertEquals("M ", devCode, "BatchWrite DeviceCode M must be 'M '")

        // HeadDevice offset=32, "000100"
        val headDev = frame.copyOfRange(32, 38).toString(Charsets.US_ASCII)
        assertEquals("000100", headDev, "BatchWrite HeadDevice M100='000100'")

        // Count offset=38, "0002"
        val count = frame.copyOfRange(38, 42).toString(Charsets.US_ASCII)
        assertEquals("0002", count, "BatchWrite Count=2 must be '0002'")

        // WriteData offset=42: "1234" + "ABCD"
        val writeData = frame.copyOfRange(42, 50).toString(Charsets.US_ASCII)
        assertEquals("1234ABCD", writeData,
            "BatchWrite WriteData must be '1234ABCD' (each word 4 char uppercase hex)")
    }

    // ── 3E Binary 회귀 — ASCII 추가 후 Binary 경로 불변 ──────────────────
    /**
     * 기본값(Binary) Encoder가 MC 프로토콜 참조 골든 바이트와 여전히 일치해야 한다.
     * ASCII 분기 추가 후 Binary 경로 회귀 0 확인.
     */
    @Test
    fun `regression_3E_binary_golden_unchanged_after_ascii_addition`() {
        val binaryEncoder = McFrameEncoder() // 기본값 = BINARY
        val device = DeviceParser.parse("D100")
        val frame = binaryEncoder.encodeBatchReadWord(device, 5)

        val expected = byteArrayOf(
            0x50, 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x0C, 0x00,
            0x10, 0x00,
            0x01, 0x04,
            0x00, 0x00,
            0x64, 0x00, 0x00,
            0xA8.toByte(),
            0x05, 0x00
        )

        assertContentEquals(
            expected, frame,
            "3E Binary regression FAIL — ASCII 분기 추가 후 3E Binary 골든 경로가 변경되었다.\n" +
            "Got:      ${frame.toHex()}\n" +
            "Expected: ${expected.toHex()}"
        )
    }

    /**
     * 4E Binary 회귀 — ASCII 추가 후에도 4E Binary 경로가 정상 동작해야 한다.
     * 4E Subheader = 54H 00H
     */
    @Test
    fun `regression_4E_binary_subheader_54_00_after_ascii_addition`() {
        val encoder4e = McFrameEncoder(frameType = FrameType.FRAME_4E, dataMode = DataMode.BINARY)
        val device = DeviceParser.parse("D100")
        val frame = encoder4e.encodeBatchReadWord(device, 5)

        assertEquals(0x54.toByte(), frame[0], "4E Binary regression: Subheader byte0 must be 0x54")
        assertEquals(0x00.toByte(), frame[1], "4E Binary regression: Subheader byte1 must be 0x00")
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────
    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
