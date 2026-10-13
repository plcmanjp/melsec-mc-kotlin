// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.device

/**
 * MC Protocol 디바이스 코드 정의
 *
 * 출처: MC 프로토콜 참조 (워드 디바이스), (비트 디바이스), (iQ-R Long), (ASCII 코드)
 * PDF: MELSEC Communication Protocol Reference Manual SH-080008-AB CH 8.1 (p.68)
 *
 * Q/L 1-byte 코드 + iQ-R 2-byte 코드 대응.
 * notation: Decimal = 10진수 주소 표기, Hex = 16진수 주소 표기 (X10 = 16, Y3F = 63)
 * asciiCode: Q/L ASCII 모드 2 char 디바이스 코드 (MC 프로토콜 참조).
 *            단일 문자 디바이스 = 코드 1char + 공백 1char (예: "D ").
 *            2문자 디바이스 = 그대로 2char (예: "SD", "TN").
 *            iQ-R 전용(qlCode == -1) = "" (Q/L ASCII 미지원).
 */
enum class DeviceCode(
    /** Q/L 시리즈 Binary 1-byte 디바이스 코드 (MC 프로토콜 참조,) */
    val qlCode: Int,
    /** iQ-R 시리즈 Binary 2-byte 디바이스 코드 (MC 프로토콜 참조,) */
    val iqrCode: Int,
    /** true = 비트 디바이스, false = 워드 디바이스 */
    val isBit: Boolean,
    /** true = 주소 표기 16진수(X/Y/B/W 계열), false = 10진수(D/M 계열) */
    val isHexNotation: Boolean,
    /** Q/L ASCII 모드 2 char 디바이스 코드 (MC 프로토콜 참조, 공식 매뉴얼 ASCII 컬럼) */
    val asciiCode: String
) {
    // ── 워드 디바이스 (MC 프로토콜 참조) ─────────────────────────────────────
    /** Data Register (주력 워드 디바이스, 10진수) */
    D(qlCode = 0xA8, iqrCode = 0x00A8, isBit = false, isHexNotation = false, asciiCode = "D "),

    /** Link Register (16진수) */
    W(qlCode = 0xB4, iqrCode = 0x00B4, isBit = false, isHexNotation = true, asciiCode = "W "),

    /** Special Register (10진수) */
    SD(qlCode = 0xA9, iqrCode = 0x00A9, isBit = false, isHexNotation = false, asciiCode = "SD"),

    /** Link Special Register (16진수) */
    SW(qlCode = 0xB5, iqrCode = 0x00B5, isBit = false, isHexNotation = true, asciiCode = "SW"),

    /** Timer Current Value (10진수) */
    TN(qlCode = 0xC2, iqrCode = 0x00C2, isBit = false, isHexNotation = false, asciiCode = "TN"),

    /** Counter Current Value (10진수) */
    CN(qlCode = 0xC5, iqrCode = 0x00C5, isBit = false, isHexNotation = false, asciiCode = "CN"),

    /** Retentive Timer Current Value (10진수) */
    // asciiCode "ST": MC 프로토콜 참조 표에 STN 행 미수록 — 실 PLC QC 확인 필요(레퍼런스 미수록)
    STN(qlCode = 0xC8, iqrCode = 0x00C8, isBit = false, isHexNotation = false, asciiCode = "ST"),

    /** File Register Block (10진수) */
    R(qlCode = 0xAF, iqrCode = 0x00AF, isBit = false, isHexNotation = false, asciiCode = "R "),

    /** File Register Serial (16진수) */
    ZR(qlCode = 0xB0, iqrCode = 0x00B0, isBit = false, isHexNotation = true, asciiCode = "ZR"),

    /** Index Register (10진수) */
    Z(qlCode = 0xCC, iqrCode = 0x00CC, isBit = false, isHexNotation = false, asciiCode = "Z "),

    /** Module Access Device / Buffer Memory (10진수) */
    G(qlCode = 0xAB, iqrCode = 0x00AB, isBit = false, isHexNotation = false, asciiCode = "G "),

    // ── 비트 디바이스 (MC 프로토콜 참조) ─────────────────────────────────────
    /** Internal Relay (주력 비트 디바이스, 10진수) */
    M(qlCode = 0x90, iqrCode = 0x0090, isBit = true, isHexNotation = false, asciiCode = "M "),

    /** Input (주력 비트 디바이스, 16진수: X1F = 31) */
    X(qlCode = 0x9C, iqrCode = 0x009C, isBit = true, isHexNotation = true, asciiCode = "X "),

    /** Output (주력 비트 디바이스, 16진수: Y3F = 63) */
    Y(qlCode = 0x9D, iqrCode = 0x009D, isBit = true, isHexNotation = true, asciiCode = "Y "),

    /** Latch Relay (10진수) */
    L(qlCode = 0x92, iqrCode = 0x0092, isBit = true, isHexNotation = false, asciiCode = "L "),

    /** Annunciator (10진수) */
    F(qlCode = 0x93, iqrCode = 0x0093, isBit = true, isHexNotation = false, asciiCode = "F "),

    /** Edge Relay (10진수) */
    V(qlCode = 0x94, iqrCode = 0x0094, isBit = true, isHexNotation = false, asciiCode = "V "),

    /** Link Relay (16진수) */
    B(qlCode = 0xA0, iqrCode = 0x00A0, isBit = true, isHexNotation = true, asciiCode = "B "),

    /** Special Relay (10진수) */
    SM(qlCode = 0x91, iqrCode = 0x0091, isBit = true, isHexNotation = false, asciiCode = "SM"),

    /** Link Special Relay (16진수) */
    SB(qlCode = 0xA1, iqrCode = 0x00A1, isBit = true, isHexNotation = true, asciiCode = "SB"),

    /** Step Relay (10진수) */
    S(qlCode = 0x98, iqrCode = 0x0098, isBit = true, isHexNotation = false, asciiCode = "S "),

    /** Direct Access Input (16진수) */
    DX(qlCode = 0xA2, iqrCode = 0x00A2, isBit = true, isHexNotation = true, asciiCode = "DX"),

    /** Direct Access Output (16진수) */
    DY(qlCode = 0xA3, iqrCode = 0x00A3, isBit = true, isHexNotation = true, asciiCode = "DY"),

    /** Timer Contact (10진수) */
    TS(qlCode = 0xC1, iqrCode = 0x00C1, isBit = true, isHexNotation = false, asciiCode = "TS"),

    /** Timer Coil (10진수) */
    TC(qlCode = 0xC0, iqrCode = 0x00C0, isBit = true, isHexNotation = false, asciiCode = "TC"),

    /** Counter Contact (10진수) */
    CS(qlCode = 0xC4, iqrCode = 0x00C4, isBit = true, isHexNotation = false, asciiCode = "CS"),

    /** Counter Coil (10진수) */
    CC(qlCode = 0xC3, iqrCode = 0x00C3, isBit = true, isHexNotation = false, asciiCode = "CC"),

    // ── iQ-R Long 타이머/카운터 (MC 프로토콜 참조, 2-byte code 전용) ───────────
    // Q/L Subcommand 0000 경로에서는 접근 불가. iQ-R 전용 (Subcommand 0002)
    // MVP 읽기 경로 미지원 — enum에 포함하되 실제 send 시 Subcommand 0002 필요
    // asciiCode = "" : Q/L ASCII 미지원 (MC 프로토콜 참조)

    /** Long Timer Current Value (iQ-R 전용, Double word) */
    LTN(qlCode = -1, iqrCode = 0x0052, isBit = false, isHexNotation = false, asciiCode = ""),

    /** Long Counter Current Value (iQ-R 전용, Double word) */
    LCN(qlCode = -1, iqrCode = 0x0056, isBit = false, isHexNotation = false, asciiCode = ""),

    /** Long Retentive Timer Current Value (iQ-R 전용, Double word) */
    LSTN(qlCode = -1, iqrCode = 0x005A, isBit = false, isHexNotation = false, asciiCode = ""),

    /** Long Timer Contact (iQ-R 전용, Bit) */
    LTS(qlCode = -1, iqrCode = 0x0051, isBit = true, isHexNotation = false, asciiCode = ""),

    /** Long Timer Coil (iQ-R 전용, Bit) */
    LTC(qlCode = -1, iqrCode = 0x0050, isBit = true, isHexNotation = false, asciiCode = ""),

    /** Long Retentive Timer Contact (iQ-R 전용, Bit) */
    LSTS(qlCode = -1, iqrCode = 0x0059, isBit = true, isHexNotation = false, asciiCode = ""),

    /** Long Retentive Timer Coil (iQ-R 전용, Bit) */
    LSTC(qlCode = -1, iqrCode = 0x0058, isBit = true, isHexNotation = false, asciiCode = ""),

    /** Long Counter Contact (iQ-R 전용, Bit) */
    LCS(qlCode = -1, iqrCode = 0x0055, isBit = true, isHexNotation = false, asciiCode = ""),

    /** Long Counter Coil (iQ-R 전용, Bit) */
    LCC(qlCode = -1, iqrCode = 0x0054, isBit = true, isHexNotation = false, asciiCode = ""),

    /** Long Index Register (iQ-R 전용, Double word) */
    LZ(qlCode = -1, iqrCode = 0x0062, isBit = false, isHexNotation = false, asciiCode = ""),

    /** Refresh Data Register (iQ-R 전용, Word) */
    RD(qlCode = -1, iqrCode = 0x002C, isBit = false, isHexNotation = false, asciiCode = "");

    /** Q/L Subcommand 0000 경로에서 사용 가능한 디바이스인지 확인 */
    val isQlAccessible: Boolean get() = qlCode != -1

    companion object {
        /**
         * 접두어 문자열로 DeviceCode를 찾는다.
         * 긴 접두어 우선 매칭 (STN, SD, SM 등 2글자 우선)
         */
        fun fromPrefix(prefix: String): DeviceCode? =
            entries.filter { it.name.equals(prefix, ignoreCase = true) }
                .firstOrNull()
    }
}
