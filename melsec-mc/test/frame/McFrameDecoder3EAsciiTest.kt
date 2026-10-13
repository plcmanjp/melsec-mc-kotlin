// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.frame

import com.plciocheck.melsecmc.FrameException
import com.plciocheck.melsecmc.McProtocolException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * McFrameDecoder 3E ASCII 응답 파싱 테스트 (Phase 3 신규)
 *
 * 3E ASCII 응답 구조 (MC 프로토콜 참조, 프레임 검증):
 *   char 0-3:   Subheader "D000"
 *   char 4-13:  Access Route 10 char
 *   char 14-17: RespDataLen 4 char (hex)
 *   char 18-21: EndCode 4 char (hex)
 *   char 22+:   ResponseData — 워드당 4 char hex / 비트당 1 char ('1'=ON, '0'=OFF, 실 PLC 확정)
 *
 * 모든 파싱은 대소문자 허용 (toInt(16) 기반).
 */
class McFrameDecoder3EAsciiTest {

    private val decoder = McFrameDecoder()

    // ── 정상 워드 응답 파싱 ───────────────────────────────────────────────

    /**
     * D100 5 word 정상 응답 ASCII:
     *   "D000" + "00FF03FF00" + "000E" + "0000" + "12345678000100000000ABCD"
     *   RespDataLen = "000E" = 14 = EndCode(4) + 5word×4 = 4+20 = 24 char... 아니면:
     *
     * 프레임 검증에서 RespDataLen은 4 char hex 표기인 char 수를 나타낸다.
     * EndCode(4) + 5word×4char = 4+20 = 24 char → "0018"
     * 아니면 EndCode를 포함하지 않고 ResponseData만? 명세 재확인:
     *
     * MC 프로토콜 참조: RespDataLen = EndCode(2 byte) + ResponseData byte 수 (Binary)
     * ASCII에서는 = EndCode(4 char) + ResponseData char 수
     * 따라서 5 word = 4+5*4 = 24 → "0018"
     *
     * 단위 테스트에서 RespDataLen은 PlcResponseReader가 사용하고 Decoder는 파싱에 사용 안 함.
     * Decoder는 bytes 전체를 받아 char 22 이후를 WordData로 파싱한다.
     */
    @Test
    fun `decodeResponse3EAscii_normal_5words_parsed_correctly`() {
        // D100=0x1234, D101=0x5678, D102=0x0001, D103=0x0000, D104=0xABCD
        val responseStr = "D000" +           // Subheader
            "00FF03FF00" +                   // Access Route
            "0018" +                         // RespDataLen (EndCode4 + 5word*4 = 24 = 0x18)
            "0000" +                         // EndCode 0x0000 (정상)
            "1234" + "5678" + "0001" + "0000" + "ABCD"  // 5 word data

        val response = responseStr.toByteArray(Charsets.US_ASCII)
        val result = decoder.decodeResponse3EAscii(response)

        assertTrue(result.isNormal, "EndCode must be normal (0x0000)")
        assertEquals(5, result.words.size, "Must have 5 words")
        assertContentEquals(
            intArrayOf(0x1234, 0x5678, 0x0001, 0x0000, 0xABCD),
            result.words,
            "ASCII words must be parsed correctly from 4-char hex groups"
        )
    }

    /**
     * 단일 워드 응답: EndCode "0000", 데이터 "FFFF" → 0xFFFF
     */
    @Test
    fun `decodeResponse3EAscii_single_word_FFFF`() {
        val responseStr = "D000" + "00FF03FF00" + "0008" + "0000" + "FFFF"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        val result = decoder.decodeResponse3EAscii(response)

        assertTrue(result.isNormal)
        assertEquals(1, result.words.size)
        assertEquals(0xFFFF, result.words[0])
    }

    /**
     * 빈 ResponseData (EndCode만): words 배열 크기 = 0
     */
    @Test
    fun `decodeResponse3EAscii_empty_response_data`() {
        val responseStr = "D000" + "00FF03FF00" + "0004" + "0000"  // EndCode(4)만
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        val result = decoder.decodeResponse3EAscii(response)

        assertTrue(result.isNormal)
        assertEquals(0, result.words.size)
    }

    /**
     * 소문자 응답 허용: "d000" Subheader, "abcd" 데이터
     */
    @Test
    fun `decodeResponse3EAscii_lowercase_subheader_allowed`() {
        val responseStr = "d000" + "00ff03ff00" + "0008" + "0000" + "abcd"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        val result = decoder.decodeResponse3EAscii(response)

        assertTrue(result.isNormal)
        assertEquals(1, result.words.size)
        assertEquals(0xABCD, result.words[0])
    }

    // ── EndCode 파싱 검증 ────────────────────────────────────────────────

    /**
     * EndCode "C051" → 0xC051 → PLC 에러 → McProtocolException
     */
    @Test
    fun `decodeResponse3EAscii_error_endCode_C051_throws`() {
        val responseStr = "D000" + "00FF03FF00" + "0004" + "C051"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        val ex = assertFailsWith<McProtocolException> {
            decoder.decodeResponse3EAscii(response)
        }
        assertEquals(0xC051, ex.endCode, "EndCode must be 0xC051")
    }

    // ── Subheader 불일치 ─────────────────────────────────────────────────

    @Test
    fun `decodeResponse3EAscii_wrong_subheader_throws_FrameException`() {
        val badResponse = "5000" + "00FF03FF00" + "0004" + "0000"  // Request subheader
        val response = badResponse.toByteArray(Charsets.US_ASCII)
        assertFailsWith<FrameException> {
            decoder.decodeResponse3EAscii(response)
        }
    }

    // ── 길이 부족 ────────────────────────────────────────────────────────

    @Test
    fun `decodeResponse3EAscii_too_short_throws_FrameException`() {
        val tooShort = "D000".toByteArray(Charsets.US_ASCII)  // 4 byte, 최소 22 필요
        assertFailsWith<FrameException> {
            decoder.decodeResponse3EAscii(tooShort)
        }
    }

    // ── ASCII 비트 응답 파싱 (설계 검토) ─────────────────────────────────

    /**
     * ASCII 비트 응답: 각 비트가 '1'(ON) 또는 '0'(OFF), 1 char per bit (실 PLC 확정)
     *
     * 테스트: M0=ON, M1=OFF, M2=ON, M3=OFF (4 bit)
     *   데이터: '1' '0' '1' '0' = "1010" (4 char)
     *
     * 실 PLC 기준: X0~X7 ON = "11000000" (비트당 1 char)
     */
    @Test
    fun `decodeBitReadResponse3EAscii_M0_ON_M1_OFF_M2_ON_M3_OFF`() {
        // bitCount=4, ResponseData = "1010" (각 비트 1 char: '1'=ON, '0'=OFF)
        val responseStr = "D000" +
            "00FF03FF00" +
            "0008" +         // RespDataLen: EndCode(4) + 4bit*1char = 8 = 0x8 → "0008"
            "0000" +         // EndCode 정상
            "1010"           // M0=ON, M1=OFF, M2=ON, M3=OFF (1 char per bit)
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        val bits = decoder.decodeBitReadResponse3EAscii(response, 4)

        assertEquals(4, bits.size)
        assertTrue(bits[0], "M0 must be ON ('1')")
        assertTrue(!bits[1], "M1 must be OFF ('0')")
        assertTrue(bits[2], "M2 must be ON ('1')")
        assertTrue(!bits[3], "M3 must be OFF ('0')")
    }

    /**
     * ASCII 비트 "10" → [true, false]:
     *   1 char per bit 기준 — '1'=ON, '0'=OFF
     *   첫 비트 ON, 두 번째 OFF
     */
    @Test
    fun `decodeBitReadResponse3EAscii_0100_is_true_false`() {
        // 1 char per bit: bit0=ON('1'), bit1=OFF('0') → "10"
        val responseStr = "D000" + "00FF03FF00" + "0006" + "0000" + "10"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        val bits = decoder.decodeBitReadResponse3EAscii(response, 2)

        assertEquals(2, bits.size)
        assertTrue(bits[0], "First bit '1' must be ON")
        assertTrue(!bits[1], "Second bit '0' must be OFF")
    }

    /**
     * ASCII 비트 응답과 Binary nibble 응답의 차이 확인.
     *
     * Binary upper-first nibble: 2 bit → 1 byte (상위 nibble=bit0, 하위 nibble=bit1)
     *   M0=ON, M1=OFF → nibble byte = 0x10
     *
     * ASCII 1 char per bit (실 PLC 확정): M0=ON → '1', M1=OFF → '0' → "10"
     *   (다른 인코딩 형식)
     */
    @Test
    fun `3EAscii_bit_encoding_differs_from_binary_nibble`() {
        // Binary nibble: 0x10 (M0=ON, M1=OFF, upper-first)
        // ASCII 1 char/bit: '1' + '0' = "10" → M0=ON, M1=OFF
        val asciiResponse = "D000" + "00FF03FF00" + "0006" + "0000" + "10"
        val response = asciiResponse.toByteArray(Charsets.US_ASCII)
        val bits = decoder.decodeBitReadResponse3EAscii(response, 2)

        assertTrue(bits[0], "ASCII bit: first '1' = ON")
        assertTrue(!bits[1], "ASCII bit: second '0' = OFF")
    }

    /**
     * 모두 OFF (8 bit)
     */
    @Test
    fun `decodeBitReadResponse3EAscii_all_off_8bits`() {
        // 1 char per bit: '0' × 8 = "00000000"
        val data = "0".repeat(8)
        val responseStr = "D000" + "00FF03FF00" + "000C" + "0000" + data
        // RespDataLen: EndCode(4) + 8bit*1char = 12 = 0xC → "000C"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        val bits = decoder.decodeBitReadResponse3EAscii(response, 8)

        assertEquals(8, bits.size)
        bits.forEachIndexed { i, b -> assertTrue(!b, "bit[$i] must be OFF") }
    }

    /**
     * 모두 ON (8 bit) — 실 PLC 계측 기준: X0~X7 ON = "11111111"
     */
    @Test
    fun `decodeBitReadResponse3EAscii_all_on_8bits`() {
        // 1 char per bit: '1' × 8 = "11111111"
        val data = "1".repeat(8)
        val responseStr = "D000" + "00FF03FF00" + "000C" + "0000" + data
        // RespDataLen: EndCode(4) + 8bit*1char = 12 = 0xC → "000C"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        val bits = decoder.decodeBitReadResponse3EAscii(response, 8)

        assertEquals(8, bits.size)
        bits.forEachIndexed { i, b -> assertTrue(b, "bit[$i] must be ON") }
    }

    // ── 4-4: 비-hex ASCII 응답 → FrameException (toIntOrNull 보강) ──────────

    /**
     * EndCode 필드에 비-hex 문자("ZZZZ") — 이전 toInt(16)이면 NumberFormatException,
     * 수정 후 toIntOrNull → FrameException.
     *
     * FrameException은 호출부(McSocketClient)가 명시적으로 catch하지만,
     * NumberFormatException은 RuntimeException으로 분류되어 catch 우회 가능.
     */
    @Test
    fun `TC-D1 3E ASCII 비-hex EndCode ZZZZ — FrameException`() {
        val responseStr = "D000" + "00FF03FF00" + "0004" + "ZZZZ"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        assertFailsWith<FrameException> {
            decoder.decodeResponse3EAscii(response)
        }
    }

    @Test
    fun `TC-D1b 3E ASCII 비트 응답 비-hex EndCode XXXX — FrameException`() {
        val responseStr = "D000" + "00FF03FF00" + "0008" + "XXXX" + "1010"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        assertFailsWith<FrameException> {
            decoder.decodeBitReadResponse3EAscii(response, 4)
        }
    }

    /**
     * 워드 데이터 필드에 비-hex 문자("GHJK") — 정상 EndCode 이후 데이터 파싱에서 FrameException.
     */
    @Test
    fun `TC-D2 3E ASCII 워드 데이터 비-hex GHJK — FrameException`() {
        val responseStr = "D000" + "00FF03FF00" + "0008" + "0000" + "GHJK"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        assertFailsWith<FrameException> {
            decoder.decodeResponse3EAscii(response)
        }
    }

    // ── 3E Binary 회귀 — ASCII 추가 후 3E Binary 파싱 경로 불변 ──────────

    /**
     * 3E Binary decodeResponse가 ASCII 분기 추가 후에도 정상 동작해야 한다.
     */
    @Test
    fun `regression_3E_binary_decode_unchanged_after_ascii_addition`() {
        val response = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x0E, 0x00,
            0x00, 0x00,
            0x34, 0x12,  // 0x1234
            0x78, 0x56   // 0x5678
        )

        val result = decoder.decodeResponse(response)
        assertTrue(result.isNormal)
        assertEquals(2, result.words.size)
        assertEquals(0x1234, result.words[0])
        assertEquals(0x5678, result.words[1])
    }
}
