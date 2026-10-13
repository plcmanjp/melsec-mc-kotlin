// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.transport

import melsecmckotlin.FrameException
import melsecmckotlin.frame.DataMode
import melsecmckotlin.frame.FrameType
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * PlcResponseReader 응답 길이 상한(MAX_PAYLOAD=8192) 검증 테스트 (4-3)
 *
 * 깨진 헤더가 대형 RespDataLen을 담아 보낼 때 65KB 버퍼+대기를 막고
 * 즉시 FrameException을 throw하는지 확인한다.
 *
 * 테스트 입력: 헤더만 생성(완전한 Body 불필요 — 상한 검사는 Body 수신 전에 수행).
 */
class PlcResponseReaderBoundsTest {

    // MAX_PAYLOAD = 8192 (PlcResponseReader 내부 상수)
    // 초과 값으로 0x3000 = 12288 사용

    // ── TC-R1: 3E Binary — 과대 RespDataLen ─────────────────────────────────

    @Test
    fun `TC-R1 3E Binary 과대 RespDataLen(12288) — FrameException`() {
        // 3E 고정 헤더 9 byte: D0 00 | AccessRoute(5) | RespDataLen LE
        // RespDataLen = 0x3000 = 12288 → LE: 00 30
        val header = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x00, 0x30   // RespDataLen LE: 0x3000 = 12288
        )
        assertThrows(FrameException::class.java) {
            PlcResponseReader.readFrame(ByteArrayInputStream(header))
        }
    }

    @Test
    fun `TC-R1b 3E Binary 상한 초과 경계 8193(MAX_PAYLOAD+1) — FrameException`() {
        // 상한 검사는 `> MAX_PAYLOAD`(8192는 허용 상한, inclusive) — 거부 경계는 8193부터.
        // RespDataLen = 8193 = 0x2001 → LE: 01 20
        val header = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x01, 0x20   // RespDataLen LE: 0x2001 = 8193
        )
        assertThrows(FrameException::class.java) {
            PlcResponseReader.readFrame(ByteArrayInputStream(header))
        }
    }

    @Test
    fun `TC-R1c 3E Binary 정상 범위(12) — 정상 처리(FrameException 아님)`() {
        // RespDataLen = 12(0x0C) → 상한 검사 통과 후 Body 12 byte 읽기 시도.
        // Body가 없으면 ConnectionException(EOF)이 발생하지만 FrameException은 아님.
        val header = byteArrayOf(
            0xD0.toByte(), 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,
            0x0C, 0x00   // RespDataLen LE: 12
        )
        // FrameException이 아닌 ConnectionException(EOF)이 발생해야 함
        val ex = assertThrows(Exception::class.java) {
            PlcResponseReader.readFrame(ByteArrayInputStream(header))
        }
        assert(ex !is FrameException) {
            "상한 내 길이에서 FrameException 발생 — 상한 검사가 잘못된 범위를 차단하고 있음"
        }
    }

    // ── TC-R2: 4E Binary — 과대 RespDataLen ─────────────────────────────────

    @Test
    fun `TC-R2 4E Binary 과대 RespDataLen(12288) — FrameException`() {
        // 4E 고정 헤더 13 byte: D4 00 | Serial(2) | Fixed(2) | AccessRoute(5) | RespDataLen(2 LE)
        // RespDataLen = 0x3000 = 12288 → LE: 00 30
        val header = byteArrayOf(
            0xD4.toByte(), 0x00,
            0x01, 0x00,   // Serial No.
            0x00, 0x00,   // Fixed
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00,  // AccessRoute
            0x00, 0x30    // RespDataLen LE: 0x3000 = 12288
        )
        assertThrows(FrameException::class.java) {
            PlcResponseReader.readFrame(
                ByteArrayInputStream(header),
                frameType = FrameType.FRAME_4E,
                dataMode = DataMode.BINARY
            )
        }
    }

    // ── TC-R3: 3E ASCII — 과대 RespDataLen ──────────────────────────────────

    @Test
    fun `TC-R3 3E ASCII 과대 RespDataLen(FFFF=65535) — FrameException`() {
        // 3E ASCII 고정 헤더 18 char: D000 | AccessRoute(10) | RespDataLen(4 hex char)
        // RespDataLen = "FFFF" = 65535
        val headerStr = "D000" + "00FF03FF00" + "FFFF"
        val header = headerStr.toByteArray(Charsets.US_ASCII)
        assertThrows(FrameException::class.java) {
            PlcResponseReader.readFrame(
                ByteArrayInputStream(header),
                frameType = FrameType.FRAME_3E,
                dataMode = DataMode.ASCII
            )
        }
    }

    // ── TC-R4: 4E ASCII — 과대 RespDataLen ──────────────────────────────────

    @Test
    fun `TC-R4 4E ASCII 과대 RespDataLen(FFFF=65535) — FrameException`() {
        // 4E ASCII 고정 헤더 26 char: D400 | Serial(4) | Fixed(4) | AccessRoute(10) | RespDataLen(4)
        val headerStr = "D400" + "0001" + "0000" + "00FF03FF00" + "FFFF"
        val header = headerStr.toByteArray(Charsets.US_ASCII)
        assertThrows(FrameException::class.java) {
            PlcResponseReader.readFrame(
                ByteArrayInputStream(header),
                frameType = FrameType.FRAME_4E,
                dataMode = DataMode.ASCII
            )
        }
    }
}
