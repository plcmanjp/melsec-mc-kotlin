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
 * McFrameDecoder 4E ASCII 응답 파싱 테스트 (Phase 4 신규)
 *
 * 4E ASCII 응답 구조 (MC 프로토콜 참조, 프레임 검증):
 *   char 0-3:   Subheader "D400"
 *   char 4-7:   Serial echo
 *   char 8-11:  Fixed "0000"
 *   char 12-21: Access Route "00FF03FF00"
 *   char 22-25: RespDataLen 4 char hex (char 단위)
 *   char 26-29: EndCode 4 char hex
 *   char 30+:   ResponseData
 *
 * 최소 길이: 30 char = 30 byte (US-ASCII)
 */
class McFrameDecoder4EAsciiTest {

    private val decoder = McFrameDecoder()

    // ── 헬퍼: 4E ASCII 응답 프레임 조립 ─────────────────────────────────

    /**
     * 4E ASCII 정상 응답 프레임 문자열을 조립한다.
     *
     * @param serial    Serial echo (int, 4 char hex 대문자)
     * @param wordData  응답 워드 값 배열 (각 워드 4 char hex)
     */
    private fun buildNormal4EAsciiResponse(serial: Int, wordData: IntArray): ByteArray {
        val serialStr = "%04X".format(serial)
        val dataStr = wordData.joinToString("") { "%04X".format(it) }
        // EndCode(4) + ResponseData = body
        val body = "0000" + dataStr
        val respDataLen = body.length
        val respDataLenStr = "%04X".format(respDataLen)
        val s = "D400" +            // Subheader
            serialStr +             // Serial echo
            "0000" +                // Fixed
            "00FF03FF00" +          // Access Route
            respDataLenStr +        // RespDataLen
            body                    // EndCode + ResponseData
        return s.toByteArray(Charsets.US_ASCII)
    }

    /**
     * 4E ASCII 이상 완료 응답 프레임 (EndCode 비-0)
     */
    private fun buildError4EAsciiResponse(serial: Int, endCode: Int): ByteArray {
        val serialStr = "%04X".format(serial)
        val endCodeStr = "%04X".format(endCode)
        val body = endCodeStr  // EndCode only, no data
        val respDataLen = body.length
        val respDataLenStr = "%04X".format(respDataLen)
        val s = "D400" +
            serialStr +
            "0000" +
            "00FF03FF00" +
            respDataLenStr +
            body
        return s.toByteArray(Charsets.US_ASCII)
    }

    // ── TC1: 정상 응답 파싱 — 2 word ────────────────────────────────────

    /**
     * 4E ASCII 정상 응답 — Serial echo 일치, EndCode 0000, 2 word 파싱
     *
     * 응답 문자열 (Serial=0x1234):
     *   "D400" "1234" "0000" "00FF03FF00" "000C" "0000" "12345678"
     *   RespDataLen = 0x000C = 12 = EndCode(4) + 2word×4 = 4+8
     */
    @Test
    fun `TC1_4E_ASCII_normal_2words_parsed_correctly`() {
        val serial = 0x1234
        val response = buildNormal4EAsciiResponse(serial, intArrayOf(0x1234, 0x5678))

        val result = decoder.decodeResponse4EAscii(response, serial)

        assertTrue(result.isNormal, "4E ASCII EndCode must be normal (0x0000)")
        assertEquals(2, result.words.size, "Must have 2 words")
        assertContentEquals(
            intArrayOf(0x1234, 0x5678),
            result.words,
            "4E ASCII words must be parsed correctly from hex string"
        )
    }

    // ── TC2: 빈 ResponseData (EndCode만) ─────────────────────────────────
    @Test
    fun `TC2_4E_ASCII_normal_no_data_words_empty`() {
        val serial = 0x0001
        val response = buildNormal4EAsciiResponse(serial, intArrayOf())

        val result = decoder.decodeResponse4EAscii(response, serial)

        assertTrue(result.isNormal)
        assertEquals(0, result.words.size, "No data words expected")
    }

    // ── TC3: Serial echo 불일치 → FrameException ──────────────────────────

    /**
     * 요청 Serial=0x0001, 응답 Serial=0x0002 → 불일치 → FrameException
     * 오류 메시지에 "Serial mismatch" 포함 확인
     */
    @Test
    fun `TC3_4E_ASCII_serial_mismatch_throws_FrameException`() {
        val requestSerial = 0x0001
        val responseMismatch = 0x0002
        val response = buildNormal4EAsciiResponse(responseMismatch, intArrayOf())
        // 응답에 responseMismatch Serial이 들어있지만 requestSerial로 검증 요청

        val ex = assertFailsWith<FrameException> {
            decoder.decodeResponse4EAscii(response, requestSerial)
        }
        assertTrue(
            ex.message?.contains("Serial mismatch") == true,
            "Exception must mention 'Serial mismatch', got: ${ex.message}"
        )
    }

    // ── TC4: Subheader 불일치 → FrameException ────────────────────────────
    @Test
    fun `TC4_4E_ASCII_wrong_subheader_throws_FrameException`() {
        // "D000" (3E ASCII subheader) 대신 "D400" 이어야 함
        val wrongSubheader = "D000" + "0000" + "0000" + "00FF03FF00" + "0004" + "0000"
        val response = wrongSubheader.toByteArray(Charsets.US_ASCII)

        assertFailsWith<FrameException> {
            decoder.decodeResponse4EAscii(response, 0)
        }
    }

    // ── TC5: 너무 짧은 응답 → FrameException ─────────────────────────────
    @Test
    fun `TC5_4E_ASCII_too_short_throws_FrameException`() {
        val tooShort = "D400123456".toByteArray(Charsets.US_ASCII)  // 10 char < 30

        assertFailsWith<FrameException> {
            decoder.decodeResponse4EAscii(tooShort, 0x1234)
        }
    }

    // ── TC6: EndCode 비-0 → McProtocolException ───────────────────────────
    @Test
    fun `TC6_4E_ASCII_error_endCode_C051_throws_McProtocolException`() {
        val serial = 0x0001
        val response = buildError4EAsciiResponse(serial, 0xC051)

        assertFailsWith<McProtocolException> {
            decoder.decodeResponse4EAscii(response, serial)
        }
    }

    // ── TC7: EndCode offset 26-29 — offset 검증 ──────────────────────────
    /**
     * EndCode가 char 26-29에 위치하는지 확인.
     * char 18-21(3E ASCII EndCode 위치)이 아닌 char 26-29에 있어야 한다.
     *
     * 정상 응답에서 EndCode = 0x0000:
     *   char 26-29 = "0000" → isNormal = true
     */
    @Test
    fun `TC7_4E_ASCII_endCode_at_offset_26_not_18`() {
        val serial = 0x0005
        val response = buildNormal4EAsciiResponse(serial, intArrayOf())

        val result = decoder.decodeResponse4EAscii(response, serial)
        assertTrue(result.isNormal,
            "4E ASCII EndCode must be read from offset 26-29, result: endCode=${result.endCode}")
    }

    // ── TC8: 비트 읽기 응답 파싱 ─────────────────────────────────────────
    /**
     * 4E ASCII 비트 읽기 응답: 1 char per bit (실 PLC 확정), char 30 이후
     * M0=ON, M1=OFF, M2=ON, M3=OFF → '1' '0' '1' '0' = "1010" (4 bits, 4 char)
     */
    @Test
    fun `TC8_4E_ASCII_bit_read_M0_M2_on`() {
        val serial = 0x0003
        // 1 char per bit: M0=ON('1'), M1=OFF('0'), M2=ON('1'), M3=OFF('0') = "1010"
        val bitDataStr = "1010"
        val body = "0000" + bitDataStr  // EndCode(4) + bit data(4) = 8 char
        val respDataLen = body.length   // 4 + 4 = 8
        val s = "D400" +
            "%04X".format(serial) +
            "0000" +
            "00FF03FF00" +
            "%04X".format(respDataLen) +
            body
        val response = s.toByteArray(Charsets.US_ASCII)

        val bits = decoder.decodeBitReadResponse4EAscii(response, 4, serial)

        assertEquals(4, bits.size)
        assertTrue(bits[0], "M0 must be ON ('1')")
        assertTrue(!bits[1], "M1 must be OFF ('0')")
        assertTrue(bits[2], "M2 must be ON ('1')")
        assertTrue(!bits[3], "M3 must be OFF ('0')")
    }

    // ── TC9: 4종 매트릭스 Decoder — 회귀 0 확인 ─────────────────────────
    /**
     * 3E Binary decodeResponse 회귀 — 4E ASCII 추가 후 3E Binary 경로가 불변임을 확인.
     */
    @Test
    fun `TC9_regression_3E_Binary_decode_unchanged`() {
        val response = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x06, 0x00,        // RespLen = 6 (EndCode2 + Data4)
            0x00, 0x00,        // EndCode 0x0000
            0x34, 0x12,        // D100 = 0x1234
            0x78, 0x56         // D101 = 0x5678
        )

        val result = decoder.decodeResponse(response)
        assertTrue(result.isNormal)
        assertContentEquals(intArrayOf(0x1234, 0x5678), result.words,
            "3E Binary decodeResponse regression FAIL")
    }

    /**
     * 4E Binary decodeResponse4E 회귀 — 4E ASCII 추가 후 불변임을 확인.
     */
    @Test
    fun `TC10_regression_4E_Binary_decode_unchanged`() {
        val serial = 0x0001
        val response = byteArrayOf(
            0xD4.toByte(), 0x00,
            0x01, 0x00,                          // Serial echo 0x0001 LE
            0x00, 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x06, 0x00,
            0x00, 0x00,                          // EndCode 0x0000
            0x34, 0x12,
            0x78, 0x56
        )

        val result = decoder.decodeResponse4E(response, serial)
        assertTrue(result.isNormal)
        assertContentEquals(intArrayOf(0x1234, 0x5678), result.words,
            "4E Binary decodeResponse4E regression FAIL")
    }

    /**
     * 3E ASCII decodeResponse3EAscii 회귀 — 4E ASCII 추가 후 불변임을 확인.
     */
    @Test
    fun `TC11_regression_3E_ASCII_decode_unchanged`() {
        // D100 5 word 응답
        val s = "D000" + "00FF03FF00" + "0018" + "0000" + "1234567800010000ABCD"
        val response = s.toByteArray(Charsets.US_ASCII)

        val result = decoder.decodeResponse3EAscii(response)
        assertTrue(result.isNormal)
        assertEquals(5, result.words.size)
        assertContentEquals(
            intArrayOf(0x1234, 0x5678, 0x0001, 0x0000, 0xABCD),
            result.words,
            "3E ASCII decodeResponse3EAscii regression FAIL"
        )
    }

    // ── 4-4: 비-hex 4E ASCII 응답 → FrameException ────────────────────────

    /**
     * 4E ASCII EndCode 필드에 비-hex 문자("ZZZZ") — 수정 전 NumberFormatException,
     * 수정 후 FrameException(호출부가 명시적으로 catch하는 타입).
     */
    @Test
    fun `TC-D3 4E ASCII 비-hex EndCode ZZZZ — FrameException`() {
        // 정상 헤더에 EndCode = "ZZZZ" 삽입
        val responseStr = "D400" + "0001" + "0000" + "00FF03FF00" + "000E" + "ZZZZ"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        assertFailsWith<FrameException> {
            decoder.decodeResponse4EAscii(response, expectedSerial = 0x0001)
        }
    }

    @Test
    fun `TC-D3b 4E ASCII 비트 응답 비-hex EndCode XXXX — FrameException`() {
        val responseStr = "D400" + "0001" + "0000" + "00FF03FF00" + "0008" + "XXXX" + "1010"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        assertFailsWith<FrameException> {
            decoder.decodeBitReadResponse4EAscii(response, 4, expectedSerial = 0x0001)
        }
    }

    /**
     * 4E ASCII 워드 데이터에 비-hex 문자("GHJK") — 정상 EndCode 이후 FrameException.
     */
    @Test
    fun `TC-D4 4E ASCII 워드 데이터 비-hex GHJK — FrameException`() {
        val responseStr = "D400" + "0001" + "0000" + "00FF03FF00" + "0008" + "0000" + "GHJK"
        val response = responseStr.toByteArray(Charsets.US_ASCII)
        assertFailsWith<FrameException> {
            decoder.decodeResponse4EAscii(response, expectedSerial = 0x0001)
        }
    }
}
