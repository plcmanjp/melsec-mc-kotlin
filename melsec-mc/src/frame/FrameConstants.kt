// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

/**
 * MC Protocol 프레임 고정값 상수 — 3E/4E × Binary/ASCII
 *
 * 출처: MC 프로토콜 참조 (3E Binary), (3E ASCII), (3E 응답), (Access Route),
 * (4E Frame, 공식 매뉴얼)
 * PDF: MELSEC Communication Protocol Reference Manual SH-080008-AB CH 5
 *
 * 모든 Binary 바이트는 send order 기준 (little-endian 이미 반영).
 * ASCII 문자열은 대문자 16진수 (예: "5000" = 0x35 0x30 0x30 0x30).
 */
object FrameConstants {

    // ── 3E Binary Subheader ───────────────────────────────────────────────
    /** 3E 요청 Subheader: 50H 00H (MC 프로토콜 참조) */
    val SUBHEADER_3E_REQUEST: ByteArray = byteArrayOf(0x50, 0x00)

    /** 3E 응답 Subheader: D0H 00H (MC 프로토콜 참조) */
    val SUBHEADER_3E_RESPONSE: ByteArray = byteArrayOf(0xD0.toByte(), 0x00)

    // ── 4E Binary Subheader / Fixed (MC 프로토콜 참조, 공식 매뉴얼) ─────────────
    /** 4E 요청 Subheader: 54H 00H (MC 프로토콜 참조) */
    val SUBHEADER_4E_REQUEST: ByteArray = byteArrayOf(0x54, 0x00)

    /** 4E 응답 Subheader: D4H 00H (MC 프로토콜 참조, 검증 기록 추가 확인) */
    val SUBHEADER_4E_RESPONSE: ByteArray = byteArrayOf(0xD4.toByte(), 0x00)

    /** 4E Fixed 필드: 00H 00H (MC 프로토콜 참조 — Subheader 직후 Serial No. 다음 고정값) */
    val FIXED_4E_BYTES: ByteArray = byteArrayOf(0x00, 0x00)

    // ── 3E ASCII Subheader (MC 프로토콜 참조) ──────────────────────────────
    /** 3E ASCII 요청 Subheader: "5000" 4 char (MC 프로토콜 참조) */
    const val SUBHEADER_3E_REQUEST_ASCII: String = "5000"

    /** 3E ASCII 응답 Subheader: "D000" 4 char (MC 프로토콜 참조) */
    const val SUBHEADER_3E_RESPONSE_ASCII: String = "D000"

    // ── 4E ASCII Subheader / Fixed (MC 프로토콜 참조 ASCII 예시) ─────────────
    /** 4E ASCII 요청 Subheader: "5400" 4 char (MC 프로토콜 참조) */
    const val SUBHEADER_4E_REQUEST_ASCII: String = "5400"

    /** 4E ASCII 응답 Subheader: "D400" 4 char (MC 프로토콜 참조, 검증 기록 확인) */
    const val SUBHEADER_4E_RESPONSE_ASCII: String = "D400"

    /** 4E ASCII Fixed 필드: "0000" 4 char (MC 프로토콜 참조) */
    const val FIXED_4E_ASCII: String = "0000"

    // ── Access Route 기본값 (자국, CPU 직결) ──────────────────────────────
    /**
     * Access Route 5 byte 기본값 (MC 프로토콜 참조)
     *   Network No.     = 00H (자국)
     *   PC No.          = FFH (Host station)
     *   Module I/O No.  = FFH 03H (0x03FF = CPU 자체, little-endian)
     *   Module Sta. No. = 00H
     */
    val ACCESS_ROUTE_DEFAULT: ByteArray = byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00)

    // ── Monitoring Timer ──────────────────────────────────────────────────
    /**
     * Monitoring Timer 기본값: 10H 00H = 0x0010 = 16 → 4000ms (MC 프로토콜 참조)
     * 단위: 250ms. 16 × 250ms = 4000ms.
     * little-endian: 10 00
     */
    val MONITORING_TIMER_DEFAULT: ByteArray = byteArrayOf(0x10, 0x00)

    // ── 헤더 크기 상수 ────────────────────────────────────────────────────
    /**
     * 3E Binary 응답 고정 헤더 크기 (MC 프로토콜 참조)
     *   Subheader(2) + AccessRoute(5) + RespDataLen(2) = 9 byte
     *   EndCode offset: 9, ResponseData 시작: 11
     */
    const val HEADER_SIZE_3E_BINARY: Int = 9

    /**
     * 4E Binary 응답 고정 헤더 크기 (MC 프로토콜 참조, 공식 매뉴얼)
     *   Subheader(2) + Serial(2) + Fixed(2) + AccessRoute(5) + RespDataLen(2) = 13 byte
     *   EndCode offset: 13, ResponseData 시작: 15
     */
    const val HEADER_SIZE_4E_BINARY: Int = 13
}
