// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

/**
 * MC Protocol End Code 분류
 *
 * 출처: MC 프로토콜 참조 (006 에러 핸들링 카테고리)
 * PDF: MELSEC Communication Protocol Reference Manual SH-080008-AB CH 5.3 (p.43~44)
 *
 * byte order: Binary 모드 little-endian (MC 프로토콜 참조)
 * 예: 0xC051 → send order 51H C0H → 파싱 시 LE로 읽으면 0xC051
 */
object EndCode {

    /**
     * End Code 카테고리 (MC 프로토콜 참조 그대로 이식)
     */
    enum class Category {
        /** 0000H — 정상 완료 */
        Normal,

        /** C050H — ASCII 코드 수신 (ASCII/Binary 모드 mismatch) */
        AsciiError,

        /** C051H~C054H — Read/Write 워드 수 범위 초과 또는 데이터 길이 오류 */
        RangeError,

        /** C056H — 디바이스 주소 범위 초과 */
        AddressError,

        /** C058H, C061H — ReqLen 필드 불일치 */
        LengthError,

        /** C059H, C05CH — Command/Subcommand 미지원 */
        CommandError,

        /** C05BH, C0A0H — CPU/디바이스 액세스 불가 */
        AccessError,

        /** C05FH — 요청 빈도 초과 (자동 backoff 대상) */
        FrequencyError,

        /** C060H — 비트/워드 디바이스 mismatch */
        MismatchError,

        /** C200H — 원격 비밀번호 인증 필요 */
        AuthError,

        /** 미분류 (위 표에 없는 코드) */
        Unknown
    }

    /**
     * endCode 정수값을 [Category]로 분류한다.
     *
     * [endCode]: PLC 응답에서 LE로 읽은 정수 (예: 0xC051)
     *
     * 출처: MC 프로토콜 참조 카테고리 표 그대로 이식
     */
    fun classify(endCode: Int): Category = when (endCode) {
        0x0000 -> Category.Normal
        0xC050 -> Category.AsciiError
        in 0xC051..0xC054 -> Category.RangeError
        0xC056 -> Category.AddressError
        0xC058, 0xC061 -> Category.LengthError
        0xC059, 0xC05C -> Category.CommandError
        0xC05B, 0xC0A0 -> Category.AccessError
        0xC05F -> Category.FrequencyError
        0xC060 -> Category.MismatchError
        0xC200 -> Category.AuthError
        else -> Category.Unknown
    }

    /** End Code가 정상 완료(0x0000)인지 */
    fun isNormal(endCode: Int): Boolean = endCode == 0x0000
}
