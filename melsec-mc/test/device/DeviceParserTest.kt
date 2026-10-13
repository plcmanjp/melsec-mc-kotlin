// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.device

import melsecmckotlin.McProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DeviceParser 테스트
 *
 * 출처:
 *   - MC 프로토콜 참조 (Notation: Decimal/Hex 기준)
 *   - MC 프로토콜 참조, (워드/비트 디바이스 코드)
 *
 * 핵심 검증:
 *   "D100" → Decimal 10진수 파싱 → number=100
 *   "X1F"  → Hex 16진수 파싱 → number=31 (0x1F)
 *   "M100" → Decimal → number=100
 *   긴 접두어 우선 매칭 (SD/SM/STN 등)
 */
class DeviceParserTest {

    // ── 기본 파싱 ─────────────────────────────────────────────────────────
    @Test
    fun `parse_D100_decimal_notation`() {
        val ref = DeviceParser.parse("D100")
        assertEquals(DeviceCode.D, ref.code)
        assertEquals(100, ref.number)
        assertFalse(ref.isHexNotation, "D is decimal notation")
        assertFalse(ref.isBit, "D is word device")
    }

    @Test
    fun `parse_X1F_hex_notation`() {
        val ref = DeviceParser.parse("X1F")
        assertEquals(DeviceCode.X, ref.code)
        assertEquals(0x1F, ref.number, "X1F must parse as 0x1F = 31")
        assertTrue(ref.isHexNotation, "X is hex notation")
        assertTrue(ref.isBit, "X is bit device")
    }

    @Test
    fun `parse_M100_decimal_notation`() {
        val ref = DeviceParser.parse("M100")
        assertEquals(DeviceCode.M, ref.code)
        assertEquals(100, ref.number)
        assertFalse(ref.isHexNotation, "M is decimal notation")
        assertTrue(ref.isBit, "M is bit device")
    }

    @Test
    fun `parse_Y3F_hex_notation`() {
        val ref = DeviceParser.parse("Y3F")
        assertEquals(DeviceCode.Y, ref.code)
        assertEquals(0x3F, ref.number, "Y3F must parse as 0x3F = 63")
        assertTrue(ref.isHexNotation)
    }

    @Test
    fun `parse_W10_hex_notation`() {
        val ref = DeviceParser.parse("W10")
        assertEquals(DeviceCode.W, ref.code)
        assertEquals(0x10, ref.number, "W10 must parse as 0x10 = 16 (hex notation)")
    }

    // ── 긴 접두어 우선 매칭 ───────────────────────────────────────────────
    @Test
    fun `parse_SD100_prefers_SD_over_S`() {
        val ref = DeviceParser.parse("SD100")
        assertEquals(DeviceCode.SD, ref.code, "SD must match before S")
        assertEquals(100, ref.number)
    }

    @Test
    fun `parse_SM100_prefers_SM_over_S`() {
        val ref = DeviceParser.parse("SM100")
        assertEquals(DeviceCode.SM, ref.code, "SM must match before S")
        assertEquals(100, ref.number)
    }

    @Test
    fun `parse_STN100_prefers_STN_over_S`() {
        val ref = DeviceParser.parse("STN100")
        assertEquals(DeviceCode.STN, ref.code, "STN must match before ST or S")
        assertEquals(100, ref.number)
    }

    @Test
    fun `parse_DX1F_hex_prefers_DX_over_D`() {
        val ref = DeviceParser.parse("DX1F")
        assertEquals(DeviceCode.DX, ref.code, "DX must match before D")
        assertEquals(0x1F, ref.number)
    }

    @Test
    fun `parse_DY1F_prefers_DY_over_D`() {
        val ref = DeviceParser.parse("DY1F")
        assertEquals(DeviceCode.DY, ref.code, "DY must match before D")
        assertEquals(0x1F, ref.number)
    }

    @Test
    fun `parse_ZR_hex`() {
        val ref = DeviceParser.parse("ZR1A")
        assertEquals(DeviceCode.ZR, ref.code, "ZR must match before Z")
        assertEquals(0x1A, ref.number)
    }

    @Test
    fun `parse_SB_hex`() {
        val ref = DeviceParser.parse("SB10")
        assertEquals(DeviceCode.SB, ref.code, "SB must match before S")
        assertEquals(0x10, ref.number)
    }

    // ── 대소문자 무시 ─────────────────────────────────────────────────────
    @Test
    fun `parse_lowercase_d100`() {
        val ref = DeviceParser.parse("d100")
        assertEquals(DeviceCode.D, ref.code)
        assertEquals(100, ref.number)
    }

    @Test
    fun `parse_mixed_case_x1f`() {
        val ref = DeviceParser.parse("x1f")
        assertEquals(DeviceCode.X, ref.code)
        assertEquals(0x1F, ref.number)
    }

    // ── 잘못된 입력 → McProtocolException ──────────────────────────────────
    @Test
    fun `parse_empty_string_throws`() {
        assertFailsWith<McProtocolException> { DeviceParser.parse("") }
    }

    @Test
    fun `parse_number_only_throws`() {
        assertFailsWith<McProtocolException> { DeviceParser.parse("100") }
    }

    @Test
    fun `parse_unknown_prefix_throws`() {
        assertFailsWith<McProtocolException> { DeviceParser.parse("Q100") }
    }

    @Test
    fun `parse_prefix_only_throws`() {
        assertFailsWith<McProtocolException> { DeviceParser.parse("D") }
    }

    @Test
    fun `parse_D_with_invalid_number_throws`() {
        // D 디바이스(10진수)에 16진 문자 → 파싱 오류
        assertFailsWith<McProtocolException> { DeviceParser.parse("D1F") }
    }

    // ── DeviceCode 바이트 코드 검증 (MC 프로토콜 참조, 직접 인용) ───────────
    @Test
    fun `D_qlCode_is_0xA8`() {
        val ref = DeviceParser.parse("D100")
        assertEquals(0xA8.toByte(), ref.qlCodeByte, "D qlCode must be 0xA8 (detail-03 §2)")
    }

    @Test
    fun `M_qlCode_is_0x90`() {
        val ref = DeviceParser.parse("M100")
        assertEquals(0x90.toByte(), ref.qlCodeByte, "M qlCode must be 0x90 (detail-03 §3)")
    }

    @Test
    fun `X_qlCode_is_0x9C`() {
        val ref = DeviceParser.parse("X1F")
        assertEquals(0x9C.toByte(), ref.qlCodeByte, "X qlCode must be 0x9C (detail-03 §3)")
    }

    @Test
    fun `Y_qlCode_is_0x9D`() {
        val ref = DeviceParser.parse("Y0")
        assertEquals(0x9D.toByte(), ref.qlCodeByte, "Y qlCode must be 0x9D (detail-03 §3)")
    }

    // ── D0 (0번 디바이스) 파싱 ──────────────────────────────────────────────
    @Test
    fun `parse_D0_number_is_0`() {
        val ref = DeviceParser.parse("D0")
        assertEquals(DeviceCode.D, ref.code)
        assertEquals(0, ref.number)
    }
}
