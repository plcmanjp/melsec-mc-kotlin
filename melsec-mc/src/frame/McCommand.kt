// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.frame

/**
 * MC Protocol 커맨드 코드
 *
 * 출처: MC 프로토콜 참조 명령 코드 표 (PDF CH 7.1, p.60~)
 * 값: little-endian 2 byte 기준 원래 hex 값 (빌더에서 LE 변환)
 *
 * 예: BatchReadWord = 0x0401 → send order 01H 04H (MC 프로토콜 참조)
 */
enum class McCommand(val code: Int) {
    /** Read CPU model name (0101H) — MC Protocol §CPU model read */
    CpuModelRead(0x0101),

    /** Batch read in word/bit units (0401H) — MC 프로토콜 참조, */
    BatchRead(0x0401),

    /** Batch write in word/bit units (1401H) — MC 프로토콜 참조 */
    BatchWrite(0x1401),

    /** Random read (word/dword) (0403H) — MC 프로토콜 참조 */
    RandomRead(0x0403),

    /** Random write (word/dword/bit) (1402H) — MC 프로토콜 참조 */
    RandomWrite(0x1402),

    /** Batch read multiple blocks (0406H) — MC 프로토콜 참조 */
    BatchReadMultiBlock(0x0406),

    /** Batch write multiple blocks (1406H) — MC 프로토콜 참조 */
    BatchWriteMultiBlock(0x1406),

    /** Monitor data register (0801H) — MC 프로토콜 참조 */
    MonitorRegister(0x0801),

    /** Monitor read registered (0802H) — MC 프로토콜 참조 */
    Monitor(0x0802);

    /**
     * 2 byte little-endian ByteArray로 변환
     * 예: 0x0401 → byteArrayOf(0x01, 0x04)
     */
    fun toBytes(): ByteArray = byteArrayOf(
        (code and 0xFF).toByte(),
        ((code shr 8) and 0xFF).toByte()
    )
}
