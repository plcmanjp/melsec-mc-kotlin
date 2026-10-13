// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.transport

import melsecmckotlin.ConnectionException
import melsecmckotlin.FrameException
import melsecmckotlin.frame.DataMode
import melsecmckotlin.frame.FrameType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * PlcResponseReader 3E ASCII 수신 단위 테스트 (Phase 3 신규)
 *
 * ASCII 응답은 US-ASCII 바이트 스트림으로 전달됨 (1 char = 1 byte).
 *
 * 3E ASCII 응답 레이아웃 (MC 프로토콜 참조, 프레임 검증):
 *   Subheader(4)      "D000"
 *   Access Route(10)  "00FF03FF00"
 *   RespDataLen(4)    hex 문자 수 (char 단위)
 *   ── 고정 헤더 18 byte ──
 *   EndCode(4)        ┐
 *   ResponseData(N)   ┘ RespDataLen char 분량
 *
 * 테스트 골든 프레임 — D100 5 word 정상 응답:
 *   "D000" "00FF03FF00" "0018" "0000" "1234567800010000ABCD"
 *   RespDataLen = 0x18 = 24 = EndCode(4) + 5word×4 = 4+20
 *   전체 = 18 + 24 = 42 char/byte
 */
class PlcResponseReader3EAsciiTest {

    private val goldenResponseStr =
        "D000" +          // Subheader
        "00FF03FF00" +    // Access Route
        "0018" +          // RespDataLen = 24 char
        "0000" +          // EndCode
        "1234" + "5678" + "0001" + "0000" + "ABCD"  // 5 words

    private val goldenFrame: ByteArray = goldenResponseStr.toByteArray(Charsets.US_ASCII)

    // ── TC1: 단일 read 전체 수신 ─────────────────────────────────────────
    @Test
    fun `TC1_3EAscii_single_read_full_frame`() {
        val result = PlcResponseReader.readFrame(
            ByteArrayInputStream(goldenFrame),
            FrameType.FRAME_3E,
            DataMode.ASCII
        )

        assertEquals(goldenFrame.size, result.size, "전체 프레임 크기 일치")
        assertArrayEquals(goldenFrame, result, "바이트 내용 일치")
    }

    // ── TC2: 1 byte씩 분할 수신 ─────────────────────────────────────────
    @Test
    fun `TC2_3EAscii_one_byte_at_a_time`() {
        val stream = OneBytAtATimeInputStream(goldenFrame)
        val result = PlcResponseReader.readFrame(stream, FrameType.FRAME_3E, DataMode.ASCII)

        assertEquals(goldenFrame.size, result.size)
        assertArrayEquals(goldenFrame, result, "1 byte 분할 재조립 정확")
    }

    // ── TC3: 헤더 + 바디 2조각 수신 ─────────────────────────────────────
    @Test
    fun `TC3_3EAscii_header_then_body_chunks`() {
        // 헤더 18 byte 먼저, 바디 24 byte 나중
        val stream = ChunkedInputStream(goldenFrame, listOf(18, 24))
        val result = PlcResponseReader.readFrame(stream, FrameType.FRAME_3E, DataMode.ASCII)

        assertArrayEquals(goldenFrame, result, "2조각 재조립 정확")
    }

    // ── TC4: Subheader 검증 ──────────────────────────────────────────────
    @Test
    fun `TC4_3EAscii_wrong_subheader_throws_FrameException`() {
        val badStr = "5000" + "00FF03FF00" + "0004" + "0000"  // Request subheader
        val bad = badStr.toByteArray(Charsets.US_ASCII)

        assertThrows(FrameException::class.java) {
            PlcResponseReader.readFrame(ByteArrayInputStream(bad), FrameType.FRAME_3E, DataMode.ASCII)
        }
    }

    // ── TC5: EOF 처리 ────────────────────────────────────────────────────
    @Test
    fun `TC5_3EAscii_eof_during_header_throws_ConnectionException`() {
        val truncated = goldenFrame.copyOf(10)  // 18 byte 헤더 미만
        assertThrows(ConnectionException::class.java) {
            PlcResponseReader.readFrame(
                EofAfterInputStream(truncated),
                FrameType.FRAME_3E,
                DataMode.ASCII
            )
        }
    }

    @Test
    fun `TC6_3EAscii_eof_during_body_throws_ConnectionException`() {
        val truncated = goldenFrame.copyOf(20)  // 헤더 18 + 2 byte만 (바디 24 byte 필요)
        assertThrows(ConnectionException::class.java) {
            PlcResponseReader.readFrame(
                EofAfterInputStream(truncated),
                FrameType.FRAME_3E,
                DataMode.ASCII
            )
        }
    }

    // ── TC7: RespDataLen hex 파싱 ────────────────────────────────────────
    /**
     * "0018" = 24 char → body 24 byte 수신
     * 전체 = 18 (헤더) + 24 (바디) = 42 byte
     */
    @Test
    fun `TC7_3EAscii_respDataLen_parsed_from_hex_string`() {
        val result = PlcResponseReader.readFrame(
            ByteArrayInputStream(goldenFrame),
            FrameType.FRAME_3E,
            DataMode.ASCII
        )
        // 헤더 18 + RespDataLen(24) = 42
        assertEquals(42, result.size, "3E ASCII frame total = HEADER(18) + RespDataLen(24) = 42")
    }

    // ── TC8: 3E Binary 회귀 — ASCII 추가 후 Binary 수신 경로 불변 ─────────
    @Test
    fun `TC8_regression_3E_binary_readFrame_unchanged`() {
        val binaryFrame = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x04, 0x00,   // RespDataLen = 4 (EndCode2 + 0 data)
            0x00, 0x00,   // EndCode
            0x34, 0x12    // 1 word data
        )

        val result = PlcResponseReader.readFrame(
            ByteArrayInputStream(binaryFrame),
            FrameType.FRAME_3E,
            DataMode.BINARY
        )

        assertArrayEquals(binaryFrame, result, "3E Binary readFrame must be unchanged")
    }

    // ── TC9: 소문자 Subheader 허용 ───────────────────────────────────────
    @Test
    fun `TC9_3EAscii_lowercase_subheader_d000_accepted`() {
        val lowerStr = "d000" + "00FF03FF00" + "0004" + "0000"
        val lower = lowerStr.toByteArray(Charsets.US_ASCII)

        // FrameException 없이 수신 성공해야 함
        val result = PlcResponseReader.readFrame(
            ByteArrayInputStream(lower),
            FrameType.FRAME_3E,
            DataMode.ASCII
        )
        // 헤더(18) + 바디(4) = 22
        assertEquals(22, result.size)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────
    private class OneBytAtATimeInputStream(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos >= data.size) -1 else (data[pos++].toInt() and 0xFF)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            b[off] = data[pos++]
            return 1
        }
    }

    private class ChunkedInputStream(
        private val data: ByteArray,
        private val chunks: List<Int>
    ) : InputStream() {
        private var pos = 0
        private var chunkIdx = 0
        private var chunkRemaining = 0
        init {
            require(chunks.sum() == data.size) { "chunks sum != data.size" }
            chunkRemaining = chunks[0]
        }
        override fun read(): Int {
            if (pos >= data.size) return -1
            advanceChunk()
            chunkRemaining--
            return data[pos++].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            advanceChunk()
            val canRead = minOf(len, chunkRemaining)
            data.copyInto(b, off, pos, pos + canRead)
            pos += canRead
            chunkRemaining -= canRead
            return canRead
        }
        private fun advanceChunk() {
            while (chunkRemaining == 0 && chunkIdx + 1 < chunks.size) {
                chunkIdx++
                chunkRemaining = chunks[chunkIdx]
            }
        }
    }

    private class EofAfterInputStream(private val data: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
    }
}
