// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

/**
 * 3E Frame Access Route 5 byte 파라미터
 *
 * 출처: MC 프로토콜 참조 (공식 매뉴얼)
 *
 * 기본값: 자국(0x00) / Host station(0xFF) / CPU(0x03FF) / Sta.0
 * 멀티 네트워크·멀티드롭·특정 모듈 접근 시 변경.
 *
 * [networkNo]      Network No. (0x00 = 자국, 0x01~0xEF = Network 1~239)
 * [pcNo]           PC No.      (0xFF = Host, 0x00~0xFE = 특정 station)
 * [moduleIoNo]     Module I/O No. (0x03FF = CPU 자체, little-endian 2 byte)
 * [moduleStationNo] Module Station No. (0x00 = 자국)
 */
data class AccessRoute(
    val networkNo: Int = 0x00,
    val pcNo: Int = 0xFF,
    val moduleIoNo: Int = 0x03FF,
    val moduleStationNo: Int = 0x00
) {
    /**
     * Access Route를 5 byte little-endian ByteArray로 직렬화한다.
     *
     * MC 프로토콜 참조: Module I/O No.는 2 byte little-endian.
     * 예: 0x03FF → FFH 03H
     */
    fun toBytes(): ByteArray = byteArrayOf(
        networkNo.toByte(),
        pcNo.toByte(),
        (moduleIoNo and 0xFF).toByte(),          // Module I/O No. low byte
        ((moduleIoNo shr 8) and 0xFF).toByte(),  // Module I/O No. high byte
        moduleStationNo.toByte()
    )

    companion object {
        /** 자국 CPU 직결 기본 Access Route */
        val DEFAULT = AccessRoute()
    }
}
