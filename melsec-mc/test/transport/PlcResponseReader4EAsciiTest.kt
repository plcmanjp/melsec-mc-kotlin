// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.transport

import com.plciocheck.melsecmc.ConnectionException
import com.plciocheck.melsecmc.FrameException
import com.plciocheck.melsecmc.frame.DataMode
import com.plciocheck.melsecmc.frame.FrameType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * PlcResponseReader 4E ASCII 수신 단위 테스트 (Phase 4 신규)
 *
 * 4E ASCII 응답 레이아웃 (MC 프로토콜 참조, 프레임 검증):
 *   Subheader(4)      "D400"
 *   Serial(4)         echo
 *   Fixed(4)          "0000"
 *   Access Route(10)  "00FF03FF00"
 *   RespDataLen(4)    hex char 수 (char 단위)
 *   ── 고정 헤더 26 byte ──
 *   EndCode(4)        ┐
 *   ResponseData(N)   ┘ RespDataLen char 분량
 *
 * 테스트 골든 프레임 — D100 2 word 정상 응답 (Serial=0x0001):
 *   "D400" "0001" "0000" "00FF03FF00" "000C" "0000" "12345678"
 *   RespDataLen = 0x000C = 12 = EndCode(4) + 2word×4 = 4+8
 *   전체 = 26 + 12 = 38 char/byte
 */
class PlcResponseReader4EAsciiTest {

    private val goldenStr =
        "D400" +          // Subheader
        "0001" +          // Serial echo
        "0000" +          // Fixed
        "00FF03FF00" +    // Access Route
        "000C" +          // RespDataLen = 12 char
        "0000" +          // EndCode
        "1234" + "5678"   // 2 words

    private val goldenFrame: ByteArray = goldenStr.toByteArray(Charsets.US_ASCII)

    // ── TC1: 단일 read 전체 수신 ─────────────────────────────────────────
    @Test
    fun `TC1_4EAscii_single_read_full_frame`() {
        val result = PlcResponseReader.readFrame(
            ByteArrayInputStream(goldenFrame),
            FrameType.FRAME_4E,
            DataMode.ASCII
        )

        assertEquals(goldenFrame.size, result.size, "전체 프레임 크기 일치")
        assertArrayEquals(goldenFrame, result, "골든 프레임과 byte-by-byte 일치")
    }

    // ── TC2: 부분 수신 누적 (헤더 1 byte씩 전달) ────────────────────────
    /**
     * TCP 부분 수신 시뮬레이션: 1 byte씩 읽도록 CustomInputStream으로 강제.
     * readExactly() 루프가 올바르게 누적함을 확인.
     */
    @Test
    fun `TC2_4EAscii_partial_read_accumulates_correctly`() {
        // 1 byte씩 반환하는 스트림 래퍼
        val singleByteStream = object : java.io.InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < goldenFrame.size) goldenFrame[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= goldenFrame.size) return -1
                b[off] = goldenFrame[pos++]
                return 1  // 1 byte씩만 반환
            }
        }

        val result = PlcResponseReader.readFrame(
            singleByteStream,
            FrameType.FRAME_4E,
            DataMode.ASCII
        )

        assertEquals(goldenFrame.size, result.size, "부분 수신 후 전체 프레임 크기 일치")
        assertArrayEquals(goldenFrame, result, "부분 수신 후 byte-by-byte 일치")
    }

    // ── TC3: Subheader 불일치 → FrameException ───────────────────────────
    @Test
    fun `TC3_4EAscii_wrong_subheader_throws_FrameException`() {
        // "D000" (3E ASCII) 대신 "D400" 이어야 함
        val wrongSubheader = "D000" + "0001" + "0000" + "00FF03FF00" + "0004" + "0000"
        val response = wrongSubheader.toByteArray(Charsets.US_ASCII)

        assertThrows(FrameException::class.java) {
            PlcResponseReader.readFrame(
                ByteArrayInputStream(response),
                FrameType.FRAME_4E,
                DataMode.ASCII
            )
        }
    }

    // ── TC4: EOF → ConnectionException ───────────────────────────────────
    @Test
    fun `TC4_4EAscii_eof_mid_header_throws_ConnectionException`() {
        // 헤더 3 byte만 전달 → readExactly 26 byte 시 EOF
        val truncated = "D40".toByteArray(Charsets.US_ASCII)

        assertThrows(ConnectionException::class.java) {
            PlcResponseReader.readFrame(
                ByteArrayInputStream(truncated),
                FrameType.FRAME_4E,
                DataMode.ASCII
            )
        }
    }

    // ── TC5: RespDataLen 크기 검증 ────────────────────────────────────────
    /**
     * RespDataLen = 0 → body 없음. 헤더 26 byte만 반환.
     */
    @Test
    fun `TC5_4EAscii_zero_respDataLen_returns_header_only`() {
        val s = "D400" + "0001" + "0000" + "00FF03FF00" + "0000"
        val response = s.toByteArray(Charsets.US_ASCII)

        val result = PlcResponseReader.readFrame(
            ByteArrayInputStream(response),
            FrameType.FRAME_4E,
            DataMode.ASCII
        )

        assertEquals(PlcResponseReader.HEADER_SIZE_4E_ASCII, result.size,
            "Zero-body frame must be exactly HEADER_SIZE_4E_ASCII bytes")
    }

    // ── TC6: HEADER_SIZE_4E_ASCII 상수 값 검증 ───────────────────────────
    /**
     * HEADER_SIZE_4E_ASCII == 26 (Subheader4+Serial4+Fixed4+Route10+RespDataLen4)
     */
    @Test
    fun `TC6_HEADER_SIZE_4E_ASCII_is_26`() {
        assertEquals(26, PlcResponseReader.HEADER_SIZE_4E_ASCII,
            "4E ASCII header size must be 26 chars")
    }

    // ── TC7: 4종 매트릭스 readFrame — 각 조합 정상 반환 ─────────────────
    /**
     * 3E Binary readFrame 회귀 — 4E ASCII 추가 후 3E Binary 수신 경로 불변.
     */
    @Test
    fun `TC7_regression_3E_Binary_readFrame_unchanged`() {
        // 3E Binary 정상 응답: D0 00 + Route(5) + RespLen 0x0006 LE + EndCode 0x0000 + 2 word
        val response = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x06, 0x00,        // RespDataLen LE = 6
            0x00, 0x00,        // EndCode
            0x34, 0x12,        // D100 = 0x1234
            0x78, 0x56         // D101 = 0x5678
        )

        val result = PlcResponseReader.readFrame(
            ByteArrayInputStream(response),
            FrameType.FRAME_3E,
            DataMode.BINARY
        )

        assertEquals(response.size, result.size, "3E Binary regression FAIL")
        assertArrayEquals(response, result, "3E Binary regression byte-by-byte FAIL")
    }

    /**
     * 4E Binary readFrame 회귀 — 4E ASCII 추가 후 4E Binary 수신 경로 불변.
     */
    @Test
    fun `TC8_regression_4E_Binary_readFrame_unchanged`() {
        val response = byteArrayOf(
            0xD4.toByte(), 0x00,
            0x01, 0x00,        // Serial
            0x00, 0x00,        // Fixed
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x06, 0x00,        // RespDataLen LE = 6
            0x00, 0x00,        // EndCode
            0x34, 0x12,
            0x78, 0x56
        )

        val result = PlcResponseReader.readFrame(
            ByteArrayInputStream(response),
            FrameType.FRAME_4E,
            DataMode.BINARY
        )

        assertEquals(response.size, result.size, "4E Binary regression FAIL")
        assertArrayEquals(response, result, "4E Binary regression byte-by-byte FAIL")
    }

    /**
     * 3E ASCII readFrame 회귀 — 4E ASCII 추가 후 3E ASCII 수신 경로 불변.
     */
    @Test
    fun `TC9_regression_3E_ASCII_readFrame_unchanged`() {
        val goldenStr3E = "D000" + "00FF03FF00" + "0018" + "0000" +
            "1234567800010000ABCD"  // 5 words
        val response = goldenStr3E.toByteArray(Charsets.US_ASCII)

        val result = PlcResponseReader.readFrame(
            ByteArrayInputStream(response),
            FrameType.FRAME_3E,
            DataMode.ASCII
        )

        assertEquals(response.size, result.size, "3E ASCII regression FAIL")
        assertArrayEquals(response, result, "3E ASCII regression byte-by-byte FAIL")
    }
}
