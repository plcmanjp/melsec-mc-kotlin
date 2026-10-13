// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.transport

import melsecmckotlin.ConnectionException
import melsecmckotlin.FrameException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * PlcResponseReader 부분 수신 누적 단위 테스트
 *
 * 실제 TCP 소켓 없이 InputStream 구현체를 사용해 분할 수신 시나리오를 재현한다.
 * 실물 PLC 없이 프레임 파싱 정확성을 검증하는 1차 방어선.
 *
 * 3E Binary 응답 구조 (MC 프로토콜 참조):
 *   Subheader(2: D0 00) + AccessRoute(5) + RespDataLen(2 LE) + Body(RespDataLen bytes)
 *   총 = 9 (고정 헤더) + RespDataLen
 *
 * 테스트 골든 프레임 — D100 5 word Batch Read 정상 응답 (MC 프로토콜 참조):
 *   D0 00  |  00 FF FF 03 00  |  0E 00  |  00 00  |  <5 word × 2 byte>
 *   Subhdr    AccessRoute(5)   RspLen   EndCode    10 byte data
 *   RespDataLen = 0x000E = 14 = EndCode(2) + data(10×... wait: 5 word = 10 byte → 2+10=12)
 *   실제: 0x000C = 12 (EndCode 2 + 5×2 data 10)  →  0C 00
 *
 * 골든 계산 (재확인):
 *   5 word → 5 × 2 byte = 10 byte data
 *   RespDataLen = EndCode(2) + data(10) = 12 = 0x000C → LE: 0C 00
 *   전체 응답 = 9 + 12 = 21 byte
 */
class PartialReadTest {

    // ── 골든 응답 프레임 ─────────────────────────────────────────────────────

    /**
     * D100 5 word 정상 응답 골든 프레임 (21 byte)
     *
     * D0 00 | 00 FF FF 03 00 | 0C 00 | 00 00 | 01 00 02 00 03 00 04 00 05 00
     * Subhdr  AccessRoute(5)  RspLen  EndCode  word1 word2 word3 word4 word5
     * RespDataLen = 0x000C = 12
     */
    private val goldenFrame: ByteArray = byteArrayOf(
        // Subheader
        0xD0.toByte(), 0x00.toByte(),
        // AccessRoute (5 byte)
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
        // RespDataLen: 12 = 0x000C → LE: 0C 00
        0x0C, 0x00,
        // EndCode: 0x0000 정상 → LE: 00 00
        0x00, 0x00,
        // Data: word1=1, word2=2, word3=3, word4=4, word5=5 (각 LE 2 byte)
        0x01, 0x00,  // 1
        0x02, 0x00,  // 2
        0x03, 0x00,  // 3
        0x04, 0x00,  // 4
        0x05, 0x00   // 5
    )

    // ── TC1: 한 번에 전체 수신 ────────────────────────────────────────────────

    @Test
    fun `TC1 단일 read 전체 프레임 수신`() {
        val result = PlcResponseReader.readFrame(ByteArrayInputStream(goldenFrame))

        assertEquals(goldenFrame.size, result.size, "전체 프레임 크기 일치")
        assertArrayEquals(goldenFrame, result, "바이트 내용 일치")
    }

    // ── TC2: 1 byte씩 쪼개서 수신 ────────────────────────────────────────────

    @Test
    fun `TC2 1 byte 씩 쪼개서 수신 — 21조각`() {
        val stream = OneBytAtATimeInputStream(goldenFrame)
        val result = PlcResponseReader.readFrame(stream)

        assertEquals(goldenFrame.size, result.size, "전체 크기 일치")
        assertArrayEquals(goldenFrame, result, "1 byte 분할 재조립 정확")
    }

    // ── TC3: 헤더와 바디를 2조각으로 수신 ────────────────────────────────────

    @Test
    fun `TC3 헤더 9 byte 후 바디 12 byte 2조각 수신`() {
        // 헤더 9 byte 먼저, 나머지 12 byte 나중에 — 두 chunk
        val stream = ChunkedInputStream(goldenFrame, listOf(9, 12))
        val result = PlcResponseReader.readFrame(stream)

        assertArrayEquals(goldenFrame, result, "2조각 재조립 정확")
    }

    // ── TC4: 3조각 분할 수신 ────────────────────────────────────────────────

    @Test
    fun `TC4 3조각 분할 수신 — 4+5+12`() {
        val stream = ChunkedInputStream(goldenFrame, listOf(4, 5, 12))
        val result = PlcResponseReader.readFrame(stream)

        assertArrayEquals(goldenFrame, result, "3조각 재조립 정확")
    }

    // ── TC5: 바디를 여러 작은 조각으로 ──────────────────────────────────────

    @Test
    fun `TC5 헤더 이후 바디를 3 byte 씩 4조각으로 수신`() {
        // 헤더(9) + 3+3+3+3 = 헤더 9, 바디 3,3,3,3
        val stream = ChunkedInputStream(goldenFrame, listOf(9, 3, 3, 3, 3))
        val result = PlcResponseReader.readFrame(stream)

        assertArrayEquals(goldenFrame, result, "바디 4조각 재조립 정확")
    }

    // ── TC6: EOF — ConnectionException ──────────────────────────────────────

    @Test
    fun `TC6 헤더 수신 중 EOF 시 ConnectionException`() {
        // 5 byte만 보내고 끊기 (헤더 9 byte 미만)
        val truncated = goldenFrame.copyOf(5)
        val stream = EofAfterInputStream(truncated)

        assertThrows(ConnectionException::class.java) {
            PlcResponseReader.readFrame(stream)
        }
    }

    @Test
    fun `TC7 바디 수신 중 EOF 시 ConnectionException`() {
        // 헤더(9) + 바디 3 byte만 보내고 끊기 (바디 12 byte 필요)
        val truncated = goldenFrame.copyOf(12)
        val stream = EofAfterInputStream(truncated)

        assertThrows(ConnectionException::class.java) {
            PlcResponseReader.readFrame(stream)
        }
    }

    // ── TC8: Subheader 불일치 — FrameException ───────────────────────────────

    @Test
    fun `TC8 Subheader 불일치 시 FrameException`() {
        val bad = goldenFrame.copyOf()
        bad[0] = 0x50.toByte() // 요청 Subheader가 잘못 들어옴
        bad[1] = 0x00.toByte()

        assertThrows(FrameException::class.java) {
            PlcResponseReader.readFrame(ByteArrayInputStream(bad))
        }
    }

    // ── TC9: readExactly 정확성 ───────────────────────────────────────────────

    @Test
    fun `TC9 readExactly 길이 0 빈 배열 반환`() {
        val result = PlcResponseReader.readExactly(ByteArrayInputStream(goldenFrame), 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `TC10 readExactly 1 byte 씩 쪼개 n byte 정확히 읽기`() {
        val target = 7
        val stream = OneBytAtATimeInputStream(goldenFrame)
        val result = PlcResponseReader.readExactly(stream, target)

        assertEquals(target, result.size)
        assertArrayEquals(goldenFrame.copyOf(target), result)
    }

    // ── TC11: RespDataLen LE 해석 정확성 ─────────────────────────────────────

    @Test
    fun `TC11 RespDataLen 0x000C를 리틀엔디안으로 정확히 파싱`() {
        // goldenFrame[7] = 0x0C (L byte), goldenFrame[8] = 0x00 (H byte)
        // → respDataLen = 12
        assertEquals(0x0C, goldenFrame[7].toInt() and 0xFF, "L byte = 0x0C")
        assertEquals(0x00, goldenFrame[8].toInt() and 0xFF, "H byte = 0x00")

        // readFrame 성공 = 12 byte 바디 수신 = LE 파싱 정확
        val result = PlcResponseReader.readFrame(ByteArrayInputStream(goldenFrame))
        assertEquals(PlcResponseReader.HEADER_SIZE + 12, result.size)
    }

    // ── 헬퍼 InputStream 구현체 ───────────────────────────────────────────────

    /**
     * 지정한 ByteArray에서 1 byte씩 반환 — TCP 최악 단편화 시뮬레이션.
     */
    private class OneBytAtATimeInputStream(private val data: ByteArray) : InputStream() {
        private var pos = 0

        override fun read(): Int =
            if (pos >= data.size) -1 else (data[pos++].toInt() and 0xFF)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            // 한 번에 최대 1 byte만 반환
            b[off] = data[pos++]
            return 1
        }
    }

    /**
     * 지정한 chunk 크기 목록대로 순서에 맞게 반환 — N조각 수신 시뮬레이션.
     * 각 chunk는 한 번의 read(buf, off, len) 호출 내에서 최대 chunk 크기만큼 반환.
     */
    private class ChunkedInputStream(
        private val data: ByteArray,
        private val chunks: List<Int>
    ) : InputStream() {
        private var pos = 0
        private var chunkIdx = 0
        private var chunkRemaining = 0

        init {
            require(chunks.sum() == data.size) {
                "chunks sum ${chunks.sum()} != data.size ${data.size}"
            }
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
            // 현재 chunk를 다 소비했으면 다음으로
            while (chunkRemaining == 0 && chunkIdx + 1 < chunks.size) {
                chunkIdx++
                chunkRemaining = chunks[chunkIdx]
            }
        }
    }

    /**
     * 지정한 데이터를 반환 후 EOF(-1) 반환 — 수신 도중 연결 끊김 시뮬레이션.
     */
    private class EofAfterInputStream(private val data: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(data)

        override fun read(): Int = inner.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
    }
}
