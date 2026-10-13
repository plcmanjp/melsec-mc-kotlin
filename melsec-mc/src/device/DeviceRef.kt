// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.device

import melsecmckotlin.FrameException

/**
 * 파싱된 PLC 디바이스 참조
 *
 * 출처: MC 프로토콜 참조 (Head Device Number), (DeviceRef 모델 예시)
 *
 * [code]   : DeviceCode enum (D, M, X, Y, ...)
 * [number] : 디바이스 번호 (10진 또는 16진 파싱 결과, 내부는 항상 정수)
 *            X1F → 0x1F = 31, D100 → 100
 *
 * MC 프로토콜 참조: Notation(Decimal/Hex)은 DeviceCode.isHexNotation 참조.
 */
data class DeviceRef(
    val code: DeviceCode,
    val number: Int
) {
    /** true = 비트 디바이스 */
    val isBit: Boolean get() = code.isBit

    /** true = 주소 16진수 표기 (X, Y, B, W, SB, SW, DX, DY, ZR) */
    val isHexNotation: Boolean get() = code.isHexNotation

    /**
     * Q/L 1-byte 디바이스 코드 (Subcommand 0000 경로 전용)
     * iQ-R Long 디바이스이면 예외 발생 — 호출 전 [DeviceCode.isQlAccessible] 확인
     *
     * P5(회귀 검증): 종전 `require(...)`는 IllegalArgumentException을 던져
     * McProtocolException 계층 밖이었다 — FrameException(McProtocolException 계층 내)으로 교체.
     * [McFrameEncoder]가 encode 진입점에서 선제 가드하므로(대칭 P4) 정상 경로에서는
     * 이 분기에 도달하지 않는다 — 여기 남은 검사는 방어적 2차 안전망이다.
     */
    val qlCodeByte: Byte get() {
        if (code == DeviceCode.G) throw FrameException("G requires a qualified module buffer read")
        if (!code.isQlAccessible) {
            throw FrameException(
                "Device ${code.name} is iQ-R exclusive (qlCode not available). Use Subcommand 0002."
            )
        }
        return code.qlCode.toByte()
    }

    // ── ASCII 모드 헬퍼 (MC 프로토콜 참조,,) ──────────────────────

    /**
     * Q/L ASCII 모드 HeadDevice 6-digit 문자열.
     * MC 프로토콜 참조: Q/L ASCII = 6-digit (D100 → "000100", M0 → "000000")
     * MC 프로토콜 참조: 16진 notation 디바이스(X/Y/B/W/SB/SW/DX/DY/ZR)는 16진 표기.
     *   X1F(number=31=0x1F) → "00001F", D100 → "000100"
     */
    fun toAsciiAddress6Digit(): String {
        if (code == DeviceCode.G) throw FrameException("G requires a qualified module buffer read")
        val digits = if (code.isHexNotation) number.toString(16).uppercase() else number.toString()
        // 6-digit 고정폭 필드 초과 시 padStart 는 자르지 않아 프레임 레이아웃(Count 필드)이 밀려
        // 조용히 오염된다 — 명시적 예외로 전환한다(McFrameEncoder 24-bit 가드가 Binary 를 선제 차단하고,
        // 여기서는 ASCII 10진 디바이스의 6-digit(999999) 초과를 방어).
        if (digits.length > 6) {
            throw FrameException(
                "HeadDevice ${code.name}$number exceeds 6-digit ASCII field (got '$digits')"
            )
        }
        return digits.padStart(6, '0')
    }

    /**
     * Q/L ASCII 디바이스 코드 2 char.
     * MC 프로토콜 참조: 단일 문자 디바이스는 "D " 형태 (1char + 공백), 2문자는 그대로.
     * iQ-R 전용 디바이스(qlCode == -1)는 "" — Q/L ASCII 미지원.
     */
    val asciiCode: String get() = code.asciiCode

    override fun toString(): String {
        return if (isHexNotation) "${code.name}${number.toString(16).uppercase()}"
        else "${code.name}$number"
    }
}
