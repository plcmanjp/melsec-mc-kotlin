// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.device

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DeviceRef ASCII 주소 인코딩 테스트 (Phase 1 신규)
 *
 * 출처:
 *   - MC 프로토콜 참조: Q/L ASCII HeadDevice = 6-digit (예: D100 → "000100")
 *   - MC 프로토콜 참조: 16진 notation 디바이스(X/Y/B/W/SB/SW/DX/DY/ZR)는 16진 표기
 *                     (X1F(number=31) → "00001F", D100 → "000100")
 *   - MC 프로토콜 참조: Q/L ASCII 디바이스 코드 = 2 char
 *
 * 검증 케이스:
 *   toAsciiAddress6Digit() 16진: X(31=0x1F) → "00001F", Y(16=0x10) → "000010",
 *                                B(1023=0x3FF) → "0003FF", ZR(256=0x100) → "000100"
 *   toAsciiAddress6Digit() 10진: D100 → "000100", M0 → "000000"
 *   대비: number=31 → X "00001F" vs D "000031"
 *   asciiCode: D → "D ", M → "M ", TS → "TS"
 */
class DeviceRefAsciiTest {

    // ── toAsciiAddress6Digit() ────────────────────────────────────────────

    @Test
    fun `D100_toAsciiAddress6Digit_returns_000100`() {
        val ref = DeviceRef(DeviceCode.D, 100)
        assertEquals("000100", ref.toAsciiAddress6Digit(),
            "D100 ASCII HeadDevice must be '000100' (detail-03 §6-2)")
    }

    @Test
    fun `M0_toAsciiAddress6Digit_returns_000000`() {
        val ref = DeviceRef(DeviceCode.M, 0)
        assertEquals("000000", ref.toAsciiAddress6Digit(),
            "M0 ASCII HeadDevice must be '000000' (detail-03 §6-2)")
    }

    @Test
    fun `X1F_toAsciiAddress6Digit_returns_00001F`() {
        // X1F = 0x1F = 31 (16진 notation — MC 프로토콜 참조)
        val ref = DeviceRef(DeviceCode.X, 31)
        assertEquals("00001F", ref.toAsciiAddress6Digit(),
            "X with number=31(=0x1F) ASCII HeadDevice must be '00001F' (detail-03 §6-3 hex notation)")
    }

    @Test
    fun `Y10_toAsciiAddress6Digit_returns_000010`() {
        // Y10(hex) = Y with number=16(=0x10)
        val ref = DeviceRef(DeviceCode.Y, 16)
        assertEquals("000010", ref.toAsciiAddress6Digit(),
            "Y with number=16(=0x10) must be '000010' (detail-03 §6-3 hex notation)")
    }

    @Test
    fun `B3FF_toAsciiAddress6Digit_returns_0003FF`() {
        // B3FF(hex) = B with number=1023(=0x3FF)
        val ref = DeviceRef(DeviceCode.B, 1023)
        assertEquals("0003FF", ref.toAsciiAddress6Digit(),
            "B with number=1023(=0x3FF) must be '0003FF' (detail-03 §6-3 hex notation)")
    }

    @Test
    fun `ZR100_toAsciiAddress6Digit_returns_000100`() {
        // ZR100(hex) = ZR with number=256(=0x100)
        val ref = DeviceRef(DeviceCode.ZR, 256)
        assertEquals("000100", ref.toAsciiAddress6Digit(),
            "ZR with number=256(=0x100) must be '000100' (detail-03 §6-3 hex notation)")
    }

    @Test
    fun `hex_vs_decimal_same_number_differs`() {
        // number=31: X(16진 notation) → "00001F", D(10진 notation) → "000031"
        val xRef = DeviceRef(DeviceCode.X, 31)
        val dRef = DeviceRef(DeviceCode.D, 31)
        assertEquals("00001F", xRef.toAsciiAddress6Digit(),
            "X number=31 must be '00001F' (hex notation)")
        assertEquals("000031", dRef.toAsciiAddress6Digit(),
            "D number=31 must be '000031' (decimal notation)")
    }

    @Test
    fun `D999999_toAsciiAddress6Digit_returns_999999`() {
        val ref = DeviceRef(DeviceCode.D, 999999)
        assertEquals("999999", ref.toAsciiAddress6Digit(),
            "D999999 ASCII HeadDevice must be '999999' (6-digit max for Q/L)")
    }

    @Test
    fun `D1_toAsciiAddress6Digit_pads_to_6_digits`() {
        val ref = DeviceRef(DeviceCode.D, 1)
        assertEquals("000001", ref.toAsciiAddress6Digit(),
            "D1 ASCII HeadDevice must be '000001' (zero-padded to 6 digits)")
    }

    // ── asciiCode (DeviceRef 위임 → DeviceCode.asciiCode) ────────────────

    @Test
    fun `DeviceRef_D_asciiCode_is_D_space`() {
        val ref = DeviceRef(DeviceCode.D, 100)
        assertEquals("D ", ref.asciiCode, "DeviceRef D asciiCode must delegate to DeviceCode.D.asciiCode")
    }

    @Test
    fun `DeviceRef_M_asciiCode_is_M_space`() {
        val ref = DeviceRef(DeviceCode.M, 0)
        assertEquals("M ", ref.asciiCode)
    }

    @Test
    fun `DeviceRef_TS_asciiCode_is_TS`() {
        val ref = DeviceRef(DeviceCode.TS, 10)
        assertEquals("TS", ref.asciiCode, "TS is 2-char device code, must be 'TS'")
    }

    @Test
    fun `DeviceRef_STN_asciiCode_is_ST`() {
        val ref = DeviceRef(DeviceCode.STN, 5)
        assertEquals("ST", ref.asciiCode, "STN ASCII code is 'ST' per detail-03 §5")
    }

    @Test
    fun `DeviceRef_LTN_asciiCode_is_empty`() {
        val ref = DeviceRef(DeviceCode.LTN, 0)
        assertEquals("", ref.asciiCode, "iQ-R exclusive device LTN must have empty asciiCode")
    }

    // ── asciiCode 길이 불변 (6-digit 반환값) ─────────────────────────────

    @Test
    fun `toAsciiAddress6Digit_always_returns_6_chars`() {
        listOf(0, 1, 100, 9999, 999999).forEach { n ->
            val result = DeviceRef(DeviceCode.D, n).toAsciiAddress6Digit()
            assertEquals(6, result.length,
                "toAsciiAddress6Digit must always return exactly 6 chars, number=$n got='$result'")
        }
    }
}
