// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EndCode 분류 테스트
 *
 * 출처: MC 프로토콜 참조 (006 에러 핸들링 카테고리)
 * 기대값은 PDF 표에서 직접 인용.
 */
class EndCodeTest {

    // ── 정상 완료 ────────────────────────────────────────────────────────
    @Test
    fun `0x0000_is_Normal`() {
        assertEquals(EndCode.Category.Normal, EndCode.classify(0x0000))
        assertTrue(EndCode.isNormal(0x0000))
    }

    // ── AsciiError ───────────────────────────────────────────────────────
    @Test
    fun `0xC050_is_AsciiError`() {
        assertEquals(EndCode.Category.AsciiError, EndCode.classify(0xC050))
    }

    // ── RangeError (C051~C054) ────────────────────────────────────────────
    @Test
    fun `0xC051_is_RangeError`() {
        assertEquals(EndCode.Category.RangeError, EndCode.classify(0xC051),
            "0xC051 must be RangeError (detail-04 §4, PDF p.44 예시 코드)")
    }

    @Test
    fun `0xC052_is_RangeError`() {
        assertEquals(EndCode.Category.RangeError, EndCode.classify(0xC052))
    }

    @Test
    fun `0xC053_is_RangeError`() {
        assertEquals(EndCode.Category.RangeError, EndCode.classify(0xC053))
    }

    @Test
    fun `0xC054_is_RangeError`() {
        assertEquals(EndCode.Category.RangeError, EndCode.classify(0xC054))
    }

    // ── AddressError ──────────────────────────────────────────────────────
    @Test
    fun `0xC056_is_AddressError`() {
        assertEquals(EndCode.Category.AddressError, EndCode.classify(0xC056))
    }

    // ── LengthError ───────────────────────────────────────────────────────
    @Test
    fun `0xC058_is_LengthError`() {
        assertEquals(EndCode.Category.LengthError, EndCode.classify(0xC058))
    }

    @Test
    fun `0xC061_is_LengthError`() {
        assertEquals(EndCode.Category.LengthError, EndCode.classify(0xC061))
    }

    // ── CommandError ──────────────────────────────────────────────────────
    @Test
    fun `0xC059_is_CommandError`() {
        assertEquals(EndCode.Category.CommandError, EndCode.classify(0xC059))
    }

    @Test
    fun `0xC05C_is_CommandError`() {
        assertEquals(EndCode.Category.CommandError, EndCode.classify(0xC05C))
    }

    // ── AccessError ───────────────────────────────────────────────────────
    @Test
    fun `0xC05B_is_AccessError`() {
        assertEquals(EndCode.Category.AccessError, EndCode.classify(0xC05B))
    }

    @Test
    fun `0xC0A0_is_AccessError`() {
        assertEquals(EndCode.Category.AccessError, EndCode.classify(0xC0A0))
    }

    // ── FrequencyError ────────────────────────────────────────────────────
    @Test
    fun `0xC05F_is_FrequencyError`() {
        assertEquals(EndCode.Category.FrequencyError, EndCode.classify(0xC05F),
            "0xC05F must be FrequencyError (detail-04 §4)")
    }

    // ── MismatchError ─────────────────────────────────────────────────────
    @Test
    fun `0xC060_is_MismatchError`() {
        assertEquals(EndCode.Category.MismatchError, EndCode.classify(0xC060))
    }

    // ── AuthError ─────────────────────────────────────────────────────────
    @Test
    fun `0xC200_is_AuthError`() {
        assertEquals(EndCode.Category.AuthError, EndCode.classify(0xC200))
    }

    // ── Unknown ───────────────────────────────────────────────────────────
    @Test
    fun `0xFFFF_is_Unknown`() {
        assertEquals(EndCode.Category.Unknown, EndCode.classify(0xFFFF))
    }

    @Test
    fun `0xC055_is_Unknown`() {
        // C055H는 표에 없으므로 Unknown
        assertEquals(EndCode.Category.Unknown, EndCode.classify(0xC055))
    }

    // ── isNormal 검증 ─────────────────────────────────────────────────────
    @Test
    fun `isNormal_returns_false_for_non_zero`() {
        assertTrue(!EndCode.isNormal(0xC051))
        assertTrue(!EndCode.isNormal(0x0001))
    }

    // ── Phase1 완료 기준: 0xC051 RangeError, 0xC05F FrequencyError ────────
    @Test
    fun `phase1_completion_criteria_C051_RangeError`() {
        assertEquals(
            EndCode.Category.RangeError,
            EndCode.classify(0xC051),
            "Phase 1 완료 기준: 0xC051 → RangeError (detail-04 §4)"
        )
    }

    @Test
    fun `phase1_completion_criteria_C05F_FrequencyError`() {
        assertEquals(
            EndCode.Category.FrequencyError,
            EndCode.classify(0xC05F),
            "Phase 1 완료 기준: 0xC05F → FrequencyError (detail-04 §4)"
        )
    }
}
