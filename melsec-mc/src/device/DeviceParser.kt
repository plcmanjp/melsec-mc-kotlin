// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.device

import melsecmckotlin.McProtocolException

/**
 * PLC 디바이스 주소 문자열 파서
 *
 * 출처: MC 프로토콜 참조 (Head Device Number), (Notation — Decimal/Hex)
 *
 * 입력 예:
 *   "D100"  → DeviceRef(D, 100)      10진수 (D.isHexNotation = false)
 *   "X1F"   → DeviceRef(X, 31)       16진수 (X.isHexNotation = true, 0x1F = 31)
 *   "M100"  → DeviceRef(M, 100)      10진수
 *   "Y3F"   → DeviceRef(Y, 63)       16진수
 *   "W10"   → DeviceRef(W, 16)       16진수 (0x10 = 16)
 *   "SD100" → DeviceRef(SD, 100)     10진수 (긴 접두어 우선)
 *
 * 파싱 규칙:
 *   1. 긴 접두어 우선 매칭 (STN/SD/SM 등 2~4자 접두어가 1자보다 우선)
 *   2. 숫자부 진법은 DeviceCode.isHexNotation 기준
 *   3. 대소문자 무시
 *   4. 잘못된 입력 → McProtocolException.invalidDevice
 */
object DeviceParser {

    /**
     * 디바이스 코드 접두어를 길이 내림차순으로 정렬한 목록.
     * 긴 접두어 우선 매칭 (예: "STN" before "S", "SD" before "S")
     */
    private val sortedEntries: List<DeviceCode> =
        DeviceCode.entries.sortedByDescending { it.name.length }

    /**
     * 디바이스 주소 문자열을 [DeviceRef]로 파싱한다.
     *
     * @param input "D100", "X1F", "M100", "Y3F" 형식 문자열
     * @throws McProtocolException 입력이 유효하지 않을 때
     */
    fun parse(input: String): DeviceRef {
        val trimmed = input.trim().uppercase()
        if (trimmed.isEmpty()) throw McProtocolException.invalidDevice(input)

        // 긴 접두어 우선으로 매칭
        val matchedCode = sortedEntries.firstOrNull { entry ->
            trimmed.startsWith(entry.name)
        } ?: throw McProtocolException.invalidDevice(input)

        val numberStr = trimmed.removePrefix(matchedCode.name)
        if (numberStr.isEmpty()) throw McProtocolException.invalidDevice(input)

        val number = try {
            if (matchedCode.isHexNotation) {
                // 16진수 표기: X1F, Y3F, W10 등 (0x 접두어 없이 그대로 파싱)
                numberStr.toInt(16)
            } else {
                // 10진수 표기: D100, M100 등
                numberStr.toInt(10)
            }
        } catch (e: NumberFormatException) {
            throw McProtocolException.invalidDevice(input)
        }

        if (number < 0) throw McProtocolException.invalidDevice(input)

        return DeviceRef(code = matchedCode, number = number)
    }
}
