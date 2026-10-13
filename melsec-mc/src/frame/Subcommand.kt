// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

/**
 * MC Protocol Subcommand
 *
 * 출처: MC 프로토콜 참조 Subcommand 체계 (공식 매뉴얼)
 *
 * 단일 Subcommand 0000 경로: Q/L/iQ-R 호환 디바이스 모두 처리 가능
 * (PDF 원문: "Even when an access target is a MELSEC iQ-R or MELSEC iQ-L series module,
 *  devices whose types and ranges are equivalent to MELSEC-Q/L series can be accessed
 *  by using subcommands for MELSEC-L/Q series.")
 *
 * MVP 경로: WordUnitsQL(0000), BitUnitsQL(0001)
 * iQ-R 확장: WordUnitsIqR(0002) — 인터페이스만, MVP 미구현
 */
enum class Subcommand(val code: Int) {
    /** Q/L 기본 frame, 워드 단위 (MC 프로토콜 참조) */
    WordUnitsQL(0x0000),

    /** Q/L 기본 frame, 비트 단위 (MC 프로토콜 참조,) */
    BitUnitsQL(0x0001),

    /** iQ-R 기본 frame, 워드 단위 — MVP 미구현, 인터페이스만 (MC 프로토콜 참조) */
    WordUnitsIqR(0x0002),

    /** iQ-R 기본 frame, 비트 단위 — MVP 미구현 (MC 프로토콜 참조) */
    BitUnitsIqR(0x0003),

    /** 확장 frame Q/L+iQ-L+QnA 호환, 워드 단위 (MC 프로토콜 참조) */
    WordUnitsExtended(0x0080),

    /** 확장 frame, 비트 단위 (MC 프로토콜 참조) */
    BitUnitsExtended(0x0081),

    /** iQ-R 확장 frame, 워드 단위 (MC 프로토콜 참조) */
    WordUnitsIqRExtended(0x0082),

    /** iQ-R 확장 frame, 비트 단위 (MC 프로토콜 참조) */
    BitUnitsIqRExtended(0x0083);

    /**
     * 2 byte little-endian ByteArray로 변환
     * 예: WordUnitsQL(0x0000) → byteArrayOf(0x00, 0x00)
     */
    fun toBytes(): ByteArray = byteArrayOf(
        (code and 0xFF).toByte(),
        ((code shr 8) and 0xFF).toByte()
    )
}
