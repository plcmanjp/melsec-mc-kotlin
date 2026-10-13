// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.frame

import com.plciocheck.melsecmc.FrameException
import com.plciocheck.melsecmc.McProtocolException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * McFrameDecoder 테스트
 *
 * 출처:
 *   - MC 프로토콜 참조 (응답 메시지 구조)
 *   - MC 프로토콜 참조 (응답 예시: D100~D104 5 word)
 *   - MC 프로토콜 참조, (End Code byte order, Error Information 9 byte)
 */
class McFrameDecoderTest {

    private val decoder = McFrameDecoder()

    // ── 정상 응답 파싱 — D100~D104 5 word ────────────────────────────────
    /**
     * MC 프로토콜 참조 응답 예시:
     *   D0 00 | 00 FF FF 03 00 | 0E 00 | 00 00 | <w1> <w2> <w3> <w4> <w5>
     *   Subhdr  AccessRoute      RspLen   EndCode  5 × 2 byte (LE)
     *
     * 워드 데이터 예시 (MC 프로토콜 참조):
     *   D100 = 0x1234 → 34 12
     *   D101 = 0x5678 → 78 56
     *   D102 = 0x0001 → 01 00
     *   D103 = 0x0000 → 00 00
     *   D104 = 0xABCD → CD AB
     */
    @Test
    fun `decodeResponse_normal_5words_LE_decoded_correctly`() {
        val response = byteArrayOf(
            0xD0.toByte(), 0x00,                 // Subheader D0 00
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // AccessRoute
            0x0E, 0x00,                          // RespLen = 14
            0x00, 0x00,                          // EndCode = 0x0000 (정상)
            // 5 word data (LE)
            0x34, 0x12,                          // D100 = 0x1234
            0x78, 0x56,                          // D101 = 0x5678
            0x01, 0x00,                          // D102 = 0x0001
            0x00, 0x00,                          // D103 = 0x0000
            0xCD.toByte(), 0xAB.toByte()         // D104 = 0xABCD
        )

        val result = decoder.decodeResponse(response)

        assertTrue(result.isNormal, "EndCode must be normal (0x0000)")
        assertEquals(5, result.words.size, "Must have 5 words")
        assertContentEquals(
            intArrayOf(0x1234, 0x5678, 0x0001, 0x0000, 0xABCD),
            result.words,
            "Words must be LE-decoded correctly"
        )
    }

    // ── EndCode 0x0000 파싱 ─────────────────────────────────────────────
    @Test
    fun `decodeResponse_endCode_0000_is_normal`() {
        val response = buildMinimalNormalResponse(0x0000, emptyArray())
        val result = decoder.decodeResponse(response)
        assertTrue(result.isNormal)
        assertEquals(EndCode.Category.Normal, result.errorCategory)
    }

    // ── 이상 완료: End Code 파싱 + McProtocolException ─────────────────
    /**
     * MC 프로토콜 참조: 0xC051 → send order 51H C0H (LE)
     * 파싱 시: bytes[9]=0x51, bytes[10]=0xC0 → readUShortLE → 0xC051
     */
    @Test
    fun `decodeResponse_error_endCode_C051_throws_McProtocolException`() {
        val response = buildErrorResponse(0xC051)
        val ex = assertFailsWith<McProtocolException> {
            decoder.decodeResponse(response)
        }
        assertEquals(0xC051, ex.endCode, "endCode must be 0xC051")
        assertEquals(EndCode.Category.RangeError, ex.errorCategory,
            "0xC051 must be RangeError (detail-04 §4)")
    }

    // ── Subheader 불일치 ─────────────────────────────────────────────────
    @Test
    fun `decodeResponse_wrong_subheader_throws`() {
        val badResponse = byteArrayOf(
            0x50, 0x00,  // Wrong: request subheader instead of D0 00
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x02, 0x00,
            0x00, 0x00
        )
        assertFailsWith<McProtocolException> {
            decoder.decodeResponse(badResponse)
        }
    }

    // ── 너무 짧은 응답 ────────────────────────────────────────────────────
    @Test
    fun `decodeResponse_too_short_throws`() {
        val tooShort = byteArrayOf(0xD0.toByte(), 0x00, 0x00, 0x00)
        assertFailsWith<McProtocolException> {
            decoder.decodeResponse(tooShort)
        }
    }

    // ── LE 파싱 검증 — readUShortLE ────────────────────────────────────────
    /**
     * MC 프로토콜 참조 + MC 프로토콜 참조:
     *   0xC051 → send order 51H C0H
     *   readUShortLE([51H, C0H], 0) → 0xC051
     */
    @Test
    fun `readUShortLE_C051_from_51_C0`() {
        val bytes = byteArrayOf(0x51, 0xC0.toByte())
        val result = McFrameDecoder.readUShortLE(bytes, 0)
        assertEquals(0xC051, result, "readUShortLE must read 51 C0 as 0xC051")
    }

    @Test
    fun `readUShortLE_0401_from_01_04`() {
        val bytes = byteArrayOf(0x01, 0x04)
        val result = McFrameDecoder.readUShortLE(bytes, 0)
        assertEquals(0x0401, result, "readUShortLE must read 01 04 as 0x0401")
    }

    @Test
    fun `readUShortLE_0000_from_00_00`() {
        val bytes = byteArrayOf(0x00, 0x00)
        val result = McFrameDecoder.readUShortLE(bytes, 0)
        assertEquals(0x0000, result, "readUShortLE must read 00 00 as 0x0000")
    }

    // ── 비트 read 응답 디코딩 — upper-first (실물 QJ71E71-100 확정 2026-06-02) ──
    /**
     * 실물 캡처: M0 워드=0x0005 (M0=1, M2=1) ↔ 비트 read RX DATA `10 10 00 00 00 00 00 00`
     * 상위 nibble = 선행 device 로 해석해야 M0=ON, M2=ON 과 일치 (upper-first).
     * 응답: D0 00 | 00 FF FF 03 00 | 0A 00 | 00 00 | 10 10 00 00 00 00 00 00
     */
    @Test
    fun `decodeBitReadResponse_upper_first_M0_M2_on`() {
        val response = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x0A, 0x00,                          // RespLen = 10 (EndCode2 + 8 data)
            0x00, 0x00,                          // EndCode normal
            0x10, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00  // 16 bits, M0=ON M2=ON
        )
        val bits = decoder.decodeBitReadResponse(response, 16)
        assertEquals(16, bits.size)
        assertTrue(bits[0], "M0 must be ON (upper nibble of byte0=0x10)")
        assertTrue(!bits[1], "M1 must be OFF")
        assertTrue(bits[2], "M2 must be ON (upper nibble of byte1=0x10)")
        assertTrue(!bits[3], "M3 must be OFF")
        for (i in 4 until 16) assertTrue(!bits[i], "M$i must be OFF")
    }

    // ── 4E Binary 응답 파싱 테스트 ──────────────────────────────────────

    /**
     * 4E Binary 정상 응답 파싱 — Serial echo 일치, EndCode 0x0000, 워드 데이터 파싱
     *
     * 4E 응답 구조 (MC 프로토콜 참조, 공식 매뉴얼):
     *   offset 0-1:  D4 00 (Subheader)
     *   offset 2-3:  34 12 (Serial echo: 0x1234 LE → Serial No.=0x1234)
     *   offset 4-5:  00 00 (Fixed)
     *   offset 6-10: 00 FF FF 03 00 (AccessRoute)
     *   offset 11-12: 06 00 (RespDataLen = 6: EndCode2+Data4)
     *   offset 13-14: 00 00 (EndCode = 0x0000 정상)
     *   offset 15-18: 34 12 78 56 (2 word: D100=0x1234, D101=0x5678)
     */
    @Test
    fun `4E_decodeResponse4E_normal_2words_parsed_correctly`() {
        val expectedSerial = 0x1234
        val response = byteArrayOf(
            0xD4.toByte(), 0x00,                 // Subheader D4 00
            0x34, 0x12,                          // Serial echo 0x1234 LE
            0x00, 0x00,                          // Fixed
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // AccessRoute
            0x06, 0x00,                          // RespDataLen = 6
            0x00, 0x00,                          // EndCode 0x0000 (정상)
            0x34, 0x12,                          // D100 = 0x1234 LE
            0x78, 0x56                           // D101 = 0x5678 LE
        )

        val result = decoder.decodeResponse4E(response, expectedSerial)

        assertTrue(result.isNormal, "4E EndCode must be normal (0x0000)")
        assertEquals(2, result.words.size, "Must have 2 words")
        assertContentEquals(
            intArrayOf(0x1234, 0x5678),
            result.words,
            "4E words must be LE-decoded correctly"
        )
    }

    /**
     * 4E Binary: Serial echo 불일치 → FrameException throw (설계 검토 확정)
     *
     * 요청 Serial=0x0001, 응답 Serial=0x0002 → 불일치 → FrameException
     */
    @Test
    fun `4E_serial_mismatch_throws_FrameException`() {
        val requestSerial = 0x0001
        val responseMismatchSerial = 0x0002  // 의도적 불일치

        val response = byteArrayOf(
            0xD4.toByte(), 0x00,                 // Subheader D4 00
            0x02, 0x00,                          // Serial echo 0x0002 LE (불일치)
            0x00, 0x00,                          // Fixed
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x02, 0x00,                          // RespDataLen = 2
            0x00, 0x00                           // EndCode 0x0000
        )

        val ex = assertFailsWith<FrameException> {
            decoder.decodeResponse4E(response, requestSerial)
        }
        assertTrue(
            ex.message?.contains("Serial mismatch") == true,
            "Exception message must mention 'Serial mismatch', got: ${ex.message}"
        )
    }

    /**
     * 4E Binary: Subheader 불일치 → FrameException throw
     */
    @Test
    fun `4E_wrong_subheader_throws_FrameException`() {
        val response = byteArrayOf(
            0xD0.toByte(), 0x00,                 // Wrong: 3E subheader instead of D4 00
            0x01, 0x00,
            0x00, 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x02, 0x00,
            0x00, 0x00
        )

        assertFailsWith<FrameException> {
            decoder.decodeResponse4E(response, 0x0001)
        }
    }

    /**
     * 4E Binary: 응답이 15 byte 미만 → FrameException throw
     */
    @Test
    fun `4E_too_short_throws_FrameException`() {
        val tooShort = byteArrayOf(0xD4.toByte(), 0x00, 0x01, 0x00)
        assertFailsWith<FrameException> {
            decoder.decodeResponse4E(tooShort, 0x0001)
        }
    }

    /**
     * 4E Binary: EndCode offset = 13 (3E는 9) 검증
     *
     * 동일 EndCode 값(0xC051)이 offset 13에 있을 때만 파싱되는지 확인한다.
     * offset 9에 있으면 파싱 결과가 달라야 한다.
     */
    @Test
    fun `4E_endCode_is_at_offset_13_not_9`() {
        val serial = 0x0001
        // 정상 응답 — EndCode는 offset 13-14에 0x0000, offset 9-12는 AccessRoute 일부
        val response = byteArrayOf(
            0xD4.toByte(), 0x00,                 // offset 0-1: Subheader
            0x01, 0x00,                          // offset 2-3: Serial 0x0001 LE
            0x00, 0x00,                          // offset 4-5: Fixed
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // offset 6-10: AccessRoute
            0x02, 0x00,                          // offset 11-12: RespDataLen = 2
            0x00, 0x00                           // offset 13-14: EndCode 0x0000 (정상)
            // ResponseData 없음 (RespDataLen = EndCode2만)
        )

        val result = decoder.decodeResponse4E(response, serial)
        assertTrue(result.isNormal, "4E EndCode at offset 13 must be parsed as normal (0x0000)")
    }

    /**
     * 4E Binary 비트 읽기 응답 파싱 — upper-first nibble, 4E offset=15
     *
     * 3E decodeBitReadResponse와 동일 nibble 규칙, dataStart만 15로 다름.
     * M0=ON, M2=ON (nibble 10 10) 을 4E 헤더로 감싼 응답.
     */
    @Test
    fun `4E_decodeBitReadResponse4E_upper_first_M0_M2_on`() {
        val serial = 0x0005
        val response = byteArrayOf(
            0xD4.toByte(), 0x00,                 // Subheader D4 00
            0x05, 0x00,                          // Serial echo 0x0005 LE
            0x00, 0x00,                          // Fixed
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // AccessRoute
            0x0A, 0x00,                          // RespDataLen = 10 (EndCode2 + 8 data)
            0x00, 0x00,                          // EndCode 0x0000 (정상)
            // 4 bit data: M0=ON, M2=ON (upper-first: 10 10)
            0x10, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00  // 16 bits
        )

        val bits = decoder.decodeBitReadResponse4E(response, 16, serial)
        assertEquals(16, bits.size)
        assertTrue(bits[0], "4E: M0 must be ON (upper nibble of byte0=0x10)")
        assertTrue(!bits[1], "4E: M1 must be OFF")
        assertTrue(bits[2], "4E: M2 must be ON (upper nibble of byte1=0x10)")
        assertTrue(!bits[3], "4E: M3 must be OFF")
        for (i in 4 until 16) assertTrue(!bits[i], "4E: M$i must be OFF")
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────
    /** 정상 완료 최소 응답 (11 byte + data) */
    private fun buildMinimalNormalResponse(endCode: Int, wordValues: Array<Int>): ByteArray {
        val header = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            ((2 + wordValues.size * 2) and 0xFF).toByte(),
            (((2 + wordValues.size * 2) shr 8) and 0xFF).toByte(),
            (endCode and 0xFF).toByte(),
            ((endCode shr 8) and 0xFF).toByte()
        )
        val data = ByteArray(wordValues.size * 2)
        wordValues.forEachIndexed { i, v ->
            data[i * 2] = (v and 0xFF).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return header + data
    }

    /**
     * 이상 완료 응답 — EndCode + 9 byte ErrorInfo (MC 프로토콜 참조)
     * EndCode는 little-endian으로 직렬화
     */
    private fun buildErrorResponse(endCode: Int): ByteArray {
        return byteArrayOf(
            0xD0.toByte(), 0x00,                 // Subheader
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // AccessRoute
            0x0B, 0x00,                          // RespLen = 11 (EndCode2 + ErrorInfo9)
            (endCode and 0xFF).toByte(),         // EndCode low byte (LE)
            ((endCode shr 8) and 0xFF).toByte(), // EndCode high byte (LE)
            // ErrorInfo 9 byte (AccessRoute5 + Command2 + Subcommand2)
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x01, 0x04,
            0x00, 0x00
        )
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        this.copyInto(result, 0)
        other.copyInto(result, this.size)
        return result
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 통합 진입점 4종 분기 테스트 (P5 0-1)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * (3E, BINARY) 분기 — 기존 3E Binary 응답을 통합 진입점으로 파싱.
     *
     * 동일 응답 바이트를 decodeResponse(bytes)와 decodeResponse(bytes, 3E, BIN, 0) 양쪽으로
     * 파싱했을 때 결과가 동일해야 한다(기존 메서드 위임 확인).
     */
    @Test
    fun `통합진입점 3E-BIN 분기 - 기존 decodeResponse와 동일 결과`() {
        val response = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x06, 0x00,          // RespLen = 6: EndCode2 + 2 word data
            0x00, 0x00,          // EndCode 정상
            0x34, 0x12,          // word0 = 0x1234
            0x78, 0x56           // word1 = 0x5678
        )

        val legacy = decoder.decodeResponse(response)
        val unified = decoder.decodeResponse(response, FrameType.FRAME_3E, DataMode.BINARY, 0)

        assertEquals(legacy.endCode, unified.endCode)
        assertContentEquals(legacy.words, unified.words)
    }

    /**
     * (4E, BINARY) 분기 — 통합 진입점이 decodeResponse4E로 위임하는지 확인.
     *
     * expectedSerial=0x0003 으로 응답을 구성. 통합 진입점이 4E 디코더로 분기해야 정상 파싱된다.
     * 3E 디코더로 잘못 분기하면 Subheader D4 00 오류로 throw된다.
     */
    @Test
    fun `통합진입점 4E-BIN 분기 - decodeResponse4E에 위임`() {
        val serial = 0x0003
        val response = byteArrayOf(
            0xD4.toByte(), 0x00,                            // Subheader D4 00
            0x03, 0x00,                                     // Serial echo 0x0003 LE
            0x00, 0x00,                                     // Fixed
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00, // AccessRoute
            0x06, 0x00,                                     // RespDataLen = 6
            0x00, 0x00,                                     // EndCode 정상
            0x01, 0x00,                                     // word0 = 0x0001
            0x02, 0x00                                      // word1 = 0x0002
        )

        val result = decoder.decodeResponse(response, FrameType.FRAME_4E, DataMode.BINARY, serial)

        assertTrue(result.isNormal)
        assertContentEquals(intArrayOf(0x0001, 0x0002), result.words)
    }

    /**
     * (4E, BINARY) 분기 — Serial 불일치 시 FrameException throw 전파 확인.
     *
     * 통합 진입점이 expectedSerial을 그대로 decodeResponse4E에 전달해야 한다.
     */
    @Test
    fun `통합진입점 4E-BIN 분기 - serial 불일치 FrameException 전파`() {
        val wrongSerial = 0x0099  // 응답 serial=0x0003, 기대값=0x0099 → 불일치
        val response = byteArrayOf(
            0xD4.toByte(), 0x00,
            0x03, 0x00,          // 응답 serial=3
            0x00, 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x02, 0x00,
            0x00, 0x00
        )

        assertFailsWith<FrameException> {
            decoder.decodeResponse(response, FrameType.FRAME_4E, DataMode.BINARY, wrongSerial)
        }
    }

    /**
     * (3E, ASCII) 분기 — 통합 진입점이 decodeResponse3EAscii로 위임하는지 확인.
     *
     * 3E ASCII 정상 응답 구조 (MC 프로토콜 참조):
     *   D000(4) + Route(10) + RespLen(4) + EndCode(4) + Data
     *   RespLen = EndCode(4) + Data("0123"+"0456" = 8) = 12 char → hex "000C"
     *   EndCode = "0000" (정상)
     *   Data: 워드 0x0123, 0x0456
     */
    @Test
    fun `통합진입점 3E-ASCII 분기 - decodeResponse3EAscii에 위임`() {
        // D000(4) + Route(10) + RespLen "000C"(4) + EndCode "0000"(4) + Data "0123""0456"
        val responseStr = "D000" + "00FF03FF00" + "000C" + "0000" + "0123" + "0456"
        val response = responseStr.toByteArray(Charsets.US_ASCII)

        val result = decoder.decodeResponse(response, FrameType.FRAME_3E, DataMode.ASCII, 0)

        assertTrue(result.isNormal)
        assertContentEquals(intArrayOf(0x0123, 0x0456), result.words)
    }

    /**
     * (4E, ASCII) 분기 — 통합 진입점이 decodeResponse4EAscii로 위임하는지 확인.
     *
     * 4E ASCII 응답: D400(4)+Serial4+Fixed4+Route10+RespLen4+EndCode4("0000")+Data4×1
     * Serial echo="0007", Data="ABCD" → word=0xABCD
     */
    @Test
    fun `통합진입점 4E-ASCII 분기 - decodeResponse4EAscii에 위임`() {
        // D400(4) + Serial "0007"(4) + Fixed "0000"(4) + Route(10) + RespLen "0008"(4) + EndCode "0000"(4) + Data "ABCD"(4)
        val responseStr = "D400" + "0007" + "0000" + "00FF03FF00" + "0008" + "0000" + "ABCD"
        val response = responseStr.toByteArray(Charsets.US_ASCII)

        val result = decoder.decodeResponse(response, FrameType.FRAME_4E, DataMode.ASCII, 0x0007)

        assertTrue(result.isNormal)
        assertContentEquals(intArrayOf(0xABCD), result.words)
    }

    /**
     * (4E, ASCII) 분기 — Serial 불일치 시 FrameException 전파.
     */
    @Test
    fun `통합진입점 4E-ASCII 분기 - serial 불일치 FrameException 전파`() {
        val responseStr = "D400" + "0007" + "0000" + "00FF03FF00" + "0004" + "0000"
        val response = responseStr.toByteArray(Charsets.US_ASCII)

        assertFailsWith<FrameException> {
            decoder.decodeResponse(response, FrameType.FRAME_4E, DataMode.ASCII, 0x0099)
        }
    }

    // ── 통합 비트 4종 분기 테스트 ─────────────────────────────────────────

    /**
     * decodeBitReadResponse 통합 진입점 (3E, BINARY) 분기 —
     * 기존 무인자 decodeBitReadResponse와 동일 결과.
     */
    @Test
    fun `통합비트진입점 3E-BIN 분기 - 기존 decodeBitReadResponse와 동일 결과`() {
        val response = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x04, 0x00,  // RespLen = 4: EndCode2 + 2 nibble byte (4 bits)
            0x00, 0x00,  // EndCode 정상
            0x10, 0x10   // bit0=ON, bit2=ON (upper-first)
        )

        val legacy = decoder.decodeBitReadResponse(response, 4)
        val unified = decoder.decodeBitReadResponse(response, 4, FrameType.FRAME_3E, DataMode.BINARY, 0)

        assertContentEquals(legacy, unified)
    }

    /**
     * decodeBitReadResponse 통합 진입점 (4E, BINARY) 분기 —
     * 4E 헤더(dataStart=15)를 올바르게 처리하는지 확인.
     * 3E 분기(dataStart=11)로 잘못 위임하면 nibble 오파싱.
     */
    @Test
    fun `통합비트진입점 4E-BIN 분기 - 4E dataStart=15 올바르게 처리`() {
        val serial = 0x0002
        val response = byteArrayOf(
            0xD4.toByte(), 0x00,                            // Subheader D4 00
            0x02, 0x00,                                     // Serial echo LE
            0x00, 0x00,                                     // Fixed
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00, // AccessRoute
            0x04, 0x00,                                     // RespDataLen = 4: EndCode2 + 2 byte nibble
            0x00, 0x00,                                     // EndCode 정상
            0x10, 0x00                                      // bit0=ON, bit1=OFF (upper-first, 2 bits)
        )

        val bits = decoder.decodeBitReadResponse(response, 2, FrameType.FRAME_4E, DataMode.BINARY, serial)

        assertEquals(2, bits.size)
        assertTrue(bits[0], "4E: bit0 ON")
        assertFalse(bits[1], "4E: bit1 OFF")
    }

    /**
     * decodeBitReadResponse 통합 진입점 (3E, ASCII) 분기.
     *
     * 3E ASCII 비트 응답: D000 + Route(10) + RespLen(4) + EndCode(4) + "10"(2비트: ON/OFF)
     * 1 char per bit (실 PLC 확정): bit0=ON('1'), bit1=OFF('0') → "10"
     */
    @Test
    fun `통합비트진입점 3E-ASCII 분기 - 1 char per bit 올바르게 처리`() {
        // RespLen = EndCode(4) + data "10"(2) = 6 → "0006"
        val responseStr = "D000" + "00FF03FF00" + "0006" + "0000" + "10"
        val response = responseStr.toByteArray(Charsets.US_ASCII)

        val bits = decoder.decodeBitReadResponse(response, 2, FrameType.FRAME_3E, DataMode.ASCII, 0)

        assertEquals(2, bits.size)
        assertTrue(bits[0], "3E ASCII: bit0 ON ('1')")
        assertFalse(bits[1], "3E ASCII: bit1 OFF ('0')")
    }

    /**
     * decodeBitReadResponse 통합 진입점 (4E, ASCII) 분기.
     *
     * 4E ASCII 비트 응답: D400+Serial4+Fixed4+Route10+RespLen4+EndCode4+data
     * 1 char per bit (실 PLC 확정): bit0=OFF('0'), bit1=ON('1') → "01"
     */
    @Test
    fun `통합비트진입점 4E-ASCII 분기 - 1 char per bit 올바르게 처리`() {
        // D400(4) + "0005"(serial) + "0000"(fixed) + Route(10) + RespLen("0006") + EndCode("0000") + "01"
        // RespDataLen: EndCode(4) + 2bit*1char = 6 → "0006"
        val responseStr = "D400" + "0005" + "0000" + "00FF03FF00" + "0006" + "0000" + "01"
        val response = responseStr.toByteArray(Charsets.US_ASCII)

        val bits = decoder.decodeBitReadResponse(response, 2, FrameType.FRAME_4E, DataMode.ASCII, 0x0005)

        assertEquals(2, bits.size)
        assertFalse(bits[0], "4E ASCII: bit0 OFF ('0')")
        assertTrue(bits[1], "4E ASCII: bit1 ON ('1')")
    }
}
