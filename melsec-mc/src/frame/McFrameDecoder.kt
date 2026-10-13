// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

import melsecmckotlin.FrameException
import melsecmckotlin.McProtocolException

/**
 * 3E/4E Frame Binary/ASCII 응답 메시지 파서
 *
 * 출처:
 *   - MC 프로토콜 참조 (3E 응답 메시지 구조: 정상/이상)
 *   - MC 프로토콜 참조 (4E 응답 메시지 구조: Subheader+Serial+Fixed 추가)
 *   - MC 프로토콜 참조 (byte order: little-endian)
 *   - MC 프로토콜 참조 (3E ASCII 응답 구조)
 *   - MC 프로토콜 참조 (Error Information 9 byte)
 *   - 설계 검토 (ASCII 비트 응답: 각 비트 2 char "00"/"01")
 *
 * 3E Binary 응답 구조 (정상, MC 프로토콜 참조):
 *   Subheader(2: D0 00) + AccessRoute(5) + RespLen(2) + EndCode(2) + ResponseData(가변)
 *   고정 헤더: 9 byte  |  EndCode offset: 9  |  Data 시작: 11
 *
 * 4E Binary 응답 구조 (정상, MC 프로토콜 참조, 공식 매뉴얼):
 *   Subheader(2: D4 00) + Serial(2) + Fixed(2: 00 00) + AccessRoute(5) + RespLen(2)
 *   + EndCode(2) + ResponseData(가변)
 *   고정 헤더: 13 byte  |  EndCode offset: 13  |  Data 시작: 15
 *
 * 3E ASCII 응답 구조 (정상, MC 프로토콜 참조, 프레임 검증):
 *   Subheader(4: "D000") + AccessRoute(10) + RespDataLen(4) + EndCode(4) + ResponseData(가변)
 *   char 0-3: "D000" / char 4-13: AccessRoute / char 14-17: RespDataLen / char 18-21: EndCode
 *   Data 시작: char 22
 *   워드: 4 char hex 단위 / 비트: 1 char per bit ('1'=ON·'0'=OFF, 실 PLC 확정)
 */
class McFrameDecoder {

    /**
     * 전체 응답 파싱 결과
     *
     * [endCode] PLC 응답 End Code (LE 2 byte를 정수로)
     * [words]   정상 완료 시 워드 데이터 배열 (각 워드 LE 2 byte → Int). 이상 완료 시 빈 배열.
     * [errorInfo] 이상 완료 시 Error Information 9 byte. 정상 완료 시 null.
     */
    data class McResponse(
        val endCode: Int,
        val words: IntArray = IntArray(0),
        val errorInfo: ByteArray? = null
    ) {
        val isNormal: Boolean get() = EndCode.isNormal(endCode)
        val errorCategory: EndCode.Category get() = EndCode.classify(endCode)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is McResponse) return false
            return endCode == other.endCode &&
                words.contentEquals(other.words) &&
                (errorInfo == null && other.errorInfo == null ||
                    errorInfo != null && other.errorInfo != null && errorInfo.contentEquals(other.errorInfo))
        }

        override fun hashCode(): Int {
            var result = endCode
            result = 31 * result + words.contentHashCode()
            result = 31 * result + (errorInfo?.contentHashCode() ?: 0)
            return result
        }
    }

    // ── 3E Binary 응답 파싱 (기존 경로 — 불변) ────────────────────────────

    /**
     * 3E Binary 응답 바이트 배열을 파싱한다.
     *
     * 최소 크기: Subheader(2) + AccessRoute(5) + RespLen(2) + EndCode(2) = 11 byte
     *
     * @throws McProtocolException Subheader 불일치 / 길이 부족 / PLC 이상 완료 시
     */
    fun decodeResponse(bytes: ByteArray): McResponse {
        // 최소 11 byte 확인
        if (bytes.size < 11) {
            throw McProtocolException.responseTooShort(11, bytes.size)
        }

        // Subheader 검증: D0H 00H (MC 프로토콜 참조)
        if (bytes[0] != 0xD0.toByte() || bytes[1] != 0x00.toByte()) {
            throw McProtocolException.invalidSubheader(bytes.copyOf(2))
        }

        // AccessRoute 5 byte skip (bytes[2..6])

        // RespLen 2 byte LE (bytes[7..8]) — 검증용으로 읽기만 함
        // val respLen = readUShortLE(bytes, 7)

        // EndCode 2 byte LE (bytes[9..10])
        val endCode = readUShortLE(bytes, 9)

        return if (EndCode.isNormal(endCode)) {
            // 정상 완료: 이후 데이터를 2 byte LE 워드 배열로 파싱
            val dataStart = 11
            val dataLength = bytes.size - dataStart
            val wordCount = dataLength / 2
            val words = IntArray(wordCount) { i ->
                readUShortLE(bytes, dataStart + i * 2)
            }
            McResponse(endCode = endCode, words = words)
        } else {
            // 이상 완료: Error Information 9 byte (MC 프로토콜 참조)
            // 최소 크기: 11 (헤더) + 9 (ErrorInfo) = 20 byte
            val errorInfo = if (bytes.size >= 20) {
                bytes.copyOfRange(11, 20)
            } else {
                bytes.copyOfRange(11, bytes.size) // 짧더라도 가능한 범위까지
            }
            throw McProtocolException.plcError(endCode, errorInfo)
        }
    }

    // ── 4E Binary 응답 파싱 ───────────────────────────────────────────────

    /**
     * 4E Binary 응답 바이트 배열을 파싱한다.
     *
     * 최소 크기: 고정 헤더(13) + EndCode(2) = 15 byte
     *
     * 응답 레이아웃 (MC 프로토콜 참조, 공식 매뉴얼):
     *   offset 0-1:  Subheader D4H 00H
     *   offset 2-3:  Serial No. (요청 echo — expectedSerial과 일치 검증)
     *   offset 4-5:  Fixed 00H 00H
     *   offset 6-10: Access Route 5 byte
     *   offset 11-12: RespDataLen 2 byte LE
     *   offset 13-14: EndCode 2 byte LE   ← 3E의 9와 달리 +4
     *   offset 15+:  ResponseData
     *
     * @param bytes          전체 응답 바이트 배열
     * @param expectedSerial 요청 시 사용한 Serial No. (McFrameEncoder.lastSerialNo). echo 검증.
     * @throws FrameException Subheader 불일치 / Serial echo 불일치 / 길이 부족
     * @throws McProtocolException PLC 이상 완료
     */
    fun decodeResponse4E(bytes: ByteArray, expectedSerial: Int): McResponse {
        // 최소 15 byte 확인
        if (bytes.size < 15) {
            throw FrameException(
                "4E response too short: expected at least 15 bytes, got ${bytes.size}"
            )
        }

        // Subheader 검증: D4H 00H (MC 프로토콜 참조)
        if (bytes[0] != 0xD4.toByte() || bytes[1] != 0x00.toByte()) {
            throw FrameException(
                "4E invalid response subheader: expected D4 00, " +
                "got ${bytes[0].toHex()} ${bytes[1].toHex()}"
            )
        }

        // Serial No. echo 검증 (offset 2-3, LE)
        val gotSerial = readUShortLE(bytes, 2)
        if (gotSerial != expectedSerial) {
            throw FrameException(
                "4E Serial mismatch: expected ${expectedSerial.toHex4()}, got ${gotSerial.toHex4()}"
            )
        }

        // Fixed 00 00 (offset 4-5) — 검증 생략 (명세 상 고정값이나 일부 구현에서 비어있을 수 있음)

        // RespDataLen 2 byte LE (offset 11-12)
        // val respDataLen = readUShortLE(bytes, 11)

        // EndCode 2 byte LE (offset 13-14)
        val endCode = readUShortLE(bytes, 13)

        return if (EndCode.isNormal(endCode)) {
            // 정상 완료: offset 15부터 ResponseData
            val dataStart = 15
            val dataLength = bytes.size - dataStart
            val wordCount = dataLength / 2
            val words = IntArray(wordCount) { i ->
                readUShortLE(bytes, dataStart + i * 2)
            }
            McResponse(endCode = endCode, words = words)
        } else {
            // 이상 완료: Error Information 9 byte (MC 프로토콜 참조)
            val errorInfoStart = 15
            val errorInfo = if (bytes.size >= errorInfoStart + 9) {
                bytes.copyOfRange(errorInfoStart, errorInfoStart + 9)
            } else {
                bytes.copyOfRange(errorInfoStart, bytes.size)
            }
            throw McProtocolException.plcError(endCode, errorInfo)
        }
    }

    // ── 비트 읽기 응답 파싱 (3E Binary) ───────────────────────────────────

    /**
     * 비트 읽기 응답의 압축 nibble 데이터를 파싱한다. (3E Binary)
     *
     * 출처: MC 프로토콜 참조 (공식 매뉴얼 응답 인코딩 규칙)
     * 응답 byte 수 공식: (bitCount + 1) / 2 (MC 프로토콜 참조)
     *
     * 압축 형식 (각 byte = 2 device, **upper-first** — 실물 QJ71E71-100 확정 2026-06-02):
     *   - 상위 nibble(bits 4-7): 선행(짝수 번째) device
     *   - 하위 nibble(bits 0-3): 후속(홀수 번째) device
     *
     * 실측 근거: M0 워드=0x0005(M0=1,M2=1) ↔ 비트 응답 RX DATA `10 10` →
     *   상위 nibble을 선행 device로 해석해야 M0=1,M2=1과 일치 (upper-first).
     *   규격 참조: MELSEC Communication Protocol Reference Manual (SH-080008).
     *
     * @param responseBytes 전체 응답 (11 byte 헤더 포함)
     * @param bitCount      요청한 비트 수
     * @return ON/OFF 배열 (true=ON, false=OFF), 크기 = bitCount
     */
    fun decodeBitReadResponse(responseBytes: ByteArray, bitCount: Int): BooleanArray {
        decodeResponseRaw(responseBytes) // EndCode 검증 (이상 완료 시 throw)

        // raw data bytes from response (EndCode 이후)
        val dataStart = 11
        return decodeBitNibbles(responseBytes, dataStart, bitCount)
    }

    /**
     * 비트 읽기 응답의 압축 nibble 데이터를 파싱한다. (4E Binary)
     *
     * 4E의 데이터 시작 offset은 15 (3E의 11 + 4).
     *
     * @param responseBytes  전체 응답 (4E: 15 byte 헤더 포함)
     * @param bitCount       요청한 비트 수
     * @param expectedSerial 요청 시 Serial No. — echo 검증
     * @return ON/OFF 배열 (true=ON, false=OFF), 크기 = bitCount
     */
    fun decodeBitReadResponse4E(responseBytes: ByteArray, bitCount: Int, expectedSerial: Int): BooleanArray {
        decodeResponseRaw4E(responseBytes, expectedSerial) // EndCode 검증

        val dataStart = 15
        return decodeBitNibbles(responseBytes, dataStart, bitCount)
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────────────

    /**
     * nibble 압축 데이터 → BooleanArray 변환 (3E/4E 공용 로직)
     *
     * upper-first: 짝수 index = 상위 nibble(선행), 홀수 index = 하위 nibble(후속)
     */
    private fun decodeBitNibbles(bytes: ByteArray, dataStart: Int, bitCount: Int): BooleanArray {
        val rawData = bytes.copyOfRange(dataStart, bytes.size)
        val expectedBytes = (bitCount + 1) / 2
        // P6(회귀 검증): 표준 require()는 IllegalArgumentException을 던져 파일 상단
        // @throws FrameException 계약과 어긋난다 — FrameException으로 교체(메시지 보존).
        if (rawData.size < expectedBytes) {
            throw FrameException(
                "Bit response data too short: expected $expectedBytes bytes, got ${rawData.size}"
            )
        }

        val result = BooleanArray(bitCount)
        for (i in 0 until bitCount) {
            val b = rawData[i / 2].toInt() and 0xFF
            // upper-first: 짝수=상위 nibble(선행), 홀수=하위 nibble(후속)
            val nibble = if (i % 2 == 0) (b shr 4) and 0x0F else b and 0x0F
            result[i] = nibble != 0
        }
        return result
    }

    /**
     * 3E 응답 파싱 내부 헬퍼 — EndCode만 검증하고 raw bytes 반환 (throw 없음 버전)
     * 비트 읽기 응답처럼 raw data가 필요한 경우 사용.
     */
    private fun decodeResponseRaw(bytes: ByteArray): McResponse {
        if (bytes.size < 11) throw McProtocolException.responseTooShort(11, bytes.size)
        if (bytes[0] != 0xD0.toByte() || bytes[1] != 0x00.toByte()) {
            throw McProtocolException.invalidSubheader(bytes.copyOf(2))
        }
        val endCode = readUShortLE(bytes, 9)
        if (!EndCode.isNormal(endCode)) {
            val errorInfo = if (bytes.size >= 20) bytes.copyOfRange(11, 20)
            else bytes.copyOfRange(11, bytes.size)
            throw McProtocolException.plcError(endCode, errorInfo)
        }
        val dataStart = 11
        val dataLength = bytes.size - dataStart
        val wordCount = dataLength / 2
        val words = IntArray(wordCount) { i -> readUShortLE(bytes, dataStart + i * 2) }
        return McResponse(endCode = endCode, words = words)
    }

    /**
     * 4E 응답 파싱 내부 헬퍼 — EndCode만 검증하고 raw bytes 반환 (비트 응답용)
     */
    private fun decodeResponseRaw4E(bytes: ByteArray, expectedSerial: Int): McResponse {
        if (bytes.size < 15) throw FrameException(
            "4E response too short: expected at least 15 bytes, got ${bytes.size}"
        )
        if (bytes[0] != 0xD4.toByte() || bytes[1] != 0x00.toByte()) {
            throw FrameException(
                "4E invalid response subheader: expected D4 00, " +
                "got ${bytes[0].toHex()} ${bytes[1].toHex()}"
            )
        }
        val gotSerial = readUShortLE(bytes, 2)
        if (gotSerial != expectedSerial) {
            throw FrameException(
                "4E Serial mismatch: expected ${expectedSerial.toHex4()}, got ${gotSerial.toHex4()}"
            )
        }
        val endCode = readUShortLE(bytes, 13)
        if (!EndCode.isNormal(endCode)) {
            val errorInfoStart = 15
            val errorInfo = if (bytes.size >= errorInfoStart + 9)
                bytes.copyOfRange(errorInfoStart, errorInfoStart + 9)
            else bytes.copyOfRange(errorInfoStart, bytes.size)
            throw McProtocolException.plcError(endCode, errorInfo)
        }
        val dataStart = 15
        val dataLength = bytes.size - dataStart
        val wordCount = dataLength / 2
        val words = IntArray(wordCount) { i -> readUShortLE(bytes, dataStart + i * 2) }
        return McResponse(endCode = endCode, words = words)
    }

    // ── 통합 진입점 (4종 분기 — 0-1) ────────────────────────────────────

    /**
     * 4종 분기 통합 워드 읽기 응답 파싱.
     *
     * 내부 분기:
     *   (3E, BINARY)  → `decodeResponse(bytes)`
     *   (4E, BINARY)  → `decodeResponse4E(bytes, expectedSerial)`
     *   (3E, ASCII)   → `decodeResponse3EAscii(bytes)`
     *   (4E, ASCII)   → `decodeResponse4EAscii(bytes, expectedSerial)`
     *
     * 기존 3E Binary 무인자 `decodeResponse(bytes)` 시그니처는 보존(회귀 없음).
     *
     * @param bytes          전체 응답 바이트 배열
     * @param frameType      [FrameType.FRAME_3E] 또는 [FrameType.FRAME_4E]
     * @param dataMode       [DataMode.BINARY] 또는 [DataMode.ASCII]
     * @param expectedSerial 4E 모드에서 echo 검증에 사용하는 요청 Serial No.
     *                       3E 모드에서는 무시된다.
     */
    fun decodeResponse(
        bytes: ByteArray,
        frameType: FrameType,
        dataMode: DataMode,
        expectedSerial: Int
    ): McResponse = when {
        frameType == FrameType.FRAME_3E && dataMode == DataMode.BINARY ->
            decodeResponse(bytes)
        frameType == FrameType.FRAME_4E && dataMode == DataMode.BINARY ->
            decodeResponse4E(bytes, expectedSerial)
        frameType == FrameType.FRAME_3E && dataMode == DataMode.ASCII ->
            decodeResponse3EAscii(bytes)
        else -> // FRAME_4E && ASCII
            decodeResponse4EAscii(bytes, expectedSerial)
    }

    /**
     * 4종 분기 통합 비트 읽기 응답 파싱.
     *
     * 내부 분기:
     *   (3E, BINARY)  → `decodeBitReadResponse(bytes, bitCount)`
     *   (4E, BINARY)  → `decodeBitReadResponse4E(bytes, bitCount, expectedSerial)`
     *   (3E, ASCII)   → `decodeBitReadResponse3EAscii(bytes, bitCount)`
     *   (4E, ASCII)   → `decodeBitReadResponse4EAscii(bytes, bitCount, expectedSerial)`
     *
     * 기존 3E Binary 무인자 `decodeBitReadResponse(bytes, count)` 시그니처는 보존(회귀 없음).
     *
     * @param responseBytes  전체 응답 바이트 배열
     * @param bitCount       요청한 비트 수
     * @param frameType      [FrameType.FRAME_3E] 또는 [FrameType.FRAME_4E]
     * @param dataMode       [DataMode.BINARY] 또는 [DataMode.ASCII]
     * @param expectedSerial 4E 모드에서 echo 검증에 사용하는 요청 Serial No.
     *                       3E 모드에서는 무시된다.
     */
    fun decodeBitReadResponse(
        responseBytes: ByteArray,
        bitCount: Int,
        frameType: FrameType,
        dataMode: DataMode,
        expectedSerial: Int
    ): BooleanArray = when {
        frameType == FrameType.FRAME_3E && dataMode == DataMode.BINARY ->
            decodeBitReadResponse(responseBytes, bitCount)
        frameType == FrameType.FRAME_4E && dataMode == DataMode.BINARY ->
            decodeBitReadResponse4E(responseBytes, bitCount, expectedSerial)
        frameType == FrameType.FRAME_3E && dataMode == DataMode.ASCII ->
            decodeBitReadResponse3EAscii(responseBytes, bitCount)
        else -> // FRAME_4E && ASCII
            decodeBitReadResponse4EAscii(responseBytes, bitCount, expectedSerial)
    }

    // ── 3E ASCII 응답 파싱 ───────────────────────────────────────────────

    // ── 4E ASCII 응답 파싱 ───────────────────────────────────────────────

    /**
     * 4E ASCII 응답 바이트 배열을 파싱한다. (워드 읽기)
     *
     * 4E ASCII 응답 구조 (MC 프로토콜 참조, 프레임 검증):
     *   char 0-3:   Subheader "D400"
     *   char 4-7:   Serial echo (요청 Serial과 일치 검증)
     *   char 8-11:  Fixed "0000"
     *   char 12-21: Access Route 10 char
     *   char 22-25: RespDataLen 4 char (hex string, char 단위)
     *   char 26-29: EndCode 4 char (hex string)
     *   char 30+:   ResponseData — 워드당 4 char hex
     *
     * 최소 길이: 30 char (Subheader4+Serial4+Fixed4+Route10+RespLen4+EndCode4) = 30 byte
     *
     * @param bytes          전체 응답 바이트 배열 (US-ASCII 인코딩된 4E ASCII 응답 프레임)
     * @param expectedSerial 요청 시 사용한 Serial No. (McFrameEncoder.lastSerialNo). echo 검증.
     * @throws FrameException Subheader 불일치 / Serial echo 불일치 / 길이 부족
     * @throws McProtocolException PLC 이상 완료
     */
    fun decodeResponse4EAscii(bytes: ByteArray, expectedSerial: Int): McResponse {
        val s = parseAscii4EResponseString(bytes, expectedSerial)

        // EndCode 4 char hex (char 26-29)
        // 4-4: toIntOrNull — 비-hex 응답이 NumberFormatException 대신 FrameException이 되도록.
        val endCode = s.substring(26, 30).toIntOrNull(16)
            ?: throw FrameException("4E ASCII invalid EndCode: '${s.substring(26, 30)}'")

        return if (EndCode.isNormal(endCode)) {
            // 정상 완료: char 30 이후 ResponseData. 워드당 4 char.
            val dataStr = s.substring(30)
            val wordCount = dataStr.length / 4
            val words = IntArray(wordCount) { i ->
                dataStr.substring(i * 4, i * 4 + 4).toIntOrNull(16)
                    ?: throw FrameException(
                        "4E ASCII invalid word data at index $i: '${dataStr.substring(i * 4, i * 4 + 4)}'"
                    )
            }
            McResponse(endCode = endCode, words = words)
        } else {
            // 이상 완료: ASCII 응답에서 EndCode만 전달
            throw McProtocolException.plcError(endCode, ByteArray(0))
        }
    }

    /**
     * 4E ASCII 비트 읽기 응답의 비트 데이터를 파싱한다.
     *
     * 3E ASCII decodeBitReadResponse3EAscii와 동일 형식 (각 비트 1 char "0"/"1").
     * dataStr 시작 위치만 char 30 (3E ASCII의 char 22와 다름).
     *
     * @param bytes          전체 응답 바이트 배열 (4E ASCII 프레임)
     * @param bitCount       요청한 비트 수
     * @param expectedSerial 요청 시 Serial No. — echo 검증
     * @return ON/OFF 배열 (true=ON, false=OFF), 크기 = bitCount
     */
    fun decodeBitReadResponse4EAscii(bytes: ByteArray, bitCount: Int, expectedSerial: Int): BooleanArray {
        val s = parseAscii4EResponseString(bytes, expectedSerial)

        // EndCode 검증 — 4-4: toIntOrNull으로 비-hex → FrameException
        val endCode = s.substring(26, 30).toIntOrNull(16)
            ?: throw FrameException("4E ASCII bit response invalid EndCode: '${s.substring(26, 30)}'")
        if (!EndCode.isNormal(endCode)) {
            throw McProtocolException.plcError(endCode, ByteArray(0))
        }

        // ResponseData: char 30 이후, 1 char per bit (실 PLC 검증: '1'=ON, '0'=OFF)
        val dataStr = s.substring(30)
        // P6(회귀 검증): require() → FrameException (계약 정합, 메시지 보존)
        if (dataStr.length < bitCount) {
            throw FrameException(
                "4E ASCII bit response data too short: expected $bitCount chars, got ${dataStr.length}"
            )
        }

        return BooleanArray(bitCount) { i -> dataStr[i] == '1' }
    }

    /**
     * 4E ASCII 응답 바이트 배열을 US-ASCII 문자열로 파싱하고 기본 검증을 수행하는 내부 헬퍼.
     *
     * - 최소 30 char 검증
     * - Subheader "D400" 검증
     * - Serial echo 검증 (char 4-7)
     *
     * @return 전체 응답 문자열
     * @throws FrameException Subheader 불일치 / 길이 부족 / Serial 불일치
     */
    private fun parseAscii4EResponseString(bytes: ByteArray, expectedSerial: Int): String {
        if (bytes.size < 30) {
            throw FrameException(
                "4E ASCII response too short: expected at least 30 chars, got ${bytes.size}"
            )
        }
        val s = bytes.toString(Charsets.US_ASCII)

        // Subheader "D400" 검증 (대소문자 허용)
        val subheader = s.substring(0, 4)
        if (!subheader.equals(FrameConstants.SUBHEADER_4E_RESPONSE_ASCII, ignoreCase = true)) {
            throw FrameException(
                "4E ASCII invalid response subheader: expected '${FrameConstants.SUBHEADER_4E_RESPONSE_ASCII}', " +
                "got '${subheader.take(4)}'"
            )
        }

        // Serial echo 검증 (char 4-7)
        val serialEchoStr = s.substring(4, 8)
        val serialEcho = serialEchoStr.toIntOrNull(16)
            ?: throw FrameException("4E ASCII invalid Serial echo: '$serialEchoStr'")
        if (serialEcho != expectedSerial) {
            throw FrameException(
                "4E ASCII Serial mismatch: expected ${"%04X".format(expectedSerial)}, " +
                "got ${"%04X".format(serialEcho)}"
            )
        }

        return s
    }

    // ── 3E ASCII 응답 파싱 ───────────────────────────────────────────────

    /**
     * 3E ASCII 응답 바이트 배열을 파싱한다. (워드 읽기)
     *
     * ASCII 응답 구조 (MC 프로토콜 참조, 프레임 검증):
     *   char 0-3:   Subheader "D000"
     *   char 4-13:  Access Route 10 char
     *   char 14-17: RespDataLen 4 char (hex string)
     *   char 18-21: EndCode 4 char (hex string)
     *   char 22+:   ResponseData — 워드당 4 char hex
     *
     * 최소 길이: 22 char (Subheader4+Route10+RespLen4+EndCode4) = 22 byte (US-ASCII)
     *
     * @param bytes 전체 응답 바이트 배열 (US-ASCII 인코딩된 ASCII 응답 프레임)
     * @throws FrameException Subheader 불일치 / 길이 부족
     * @throws McProtocolException PLC 이상 완료
     */
    fun decodeResponse3EAscii(bytes: ByteArray): McResponse {
        val s = parseAsciiResponseString(bytes)

        // EndCode 4 char hex (char 18-21), 대소문자 허용
        // 4-4: toIntOrNull — 비-hex 응답이 NumberFormatException 대신 FrameException이 되도록.
        val endCode = s.substring(18, 22).toIntOrNull(16)
            ?: throw FrameException("3E ASCII invalid EndCode: '${s.substring(18, 22)}'")

        return if (EndCode.isNormal(endCode)) {
            // 정상 완료: char 22 이후 ResponseData. 워드당 4 char.
            val dataStr = s.substring(22)
            val wordCount = dataStr.length / 4
            val words = IntArray(wordCount) { i ->
                dataStr.substring(i * 4, i * 4 + 4).toIntOrNull(16)
                    ?: throw FrameException(
                        "3E ASCII invalid word data at index $i: '${dataStr.substring(i * 4, i * 4 + 4)}'"
                    )
            }
            McResponse(endCode = endCode, words = words)
        } else {
            // 이상 완료: ASCII 응답에서 EndCode만 전달 (ErrorInfo는 Binary와 달리 별도 처리)
            throw McProtocolException.plcError(endCode, ByteArray(0))
        }
    }

    /**
     * 3E ASCII 비트 읽기 응답의 비트 데이터를 파싱한다. (설계 검토 확정)
     *
     * Binary upper-first nibble(회귀 검증)과 다름:
     * ASCII 비트 응답은 각 비트가 "0"(OFF) 또는 "1"(ON) 형태로 **1 char씩** 전송됨.
     * bitCount=8이면 응답 데이터 8 char.
     *
     * @param bytes    전체 응답 바이트 배열 (3E ASCII 프레임)
     * @param bitCount 요청한 비트 수
     * @return ON/OFF 배열 (true=ON, false=OFF), 크기 = bitCount
     */
    fun decodeBitReadResponse3EAscii(bytes: ByteArray, bitCount: Int): BooleanArray {
        val s = parseAsciiResponseString(bytes)

        // EndCode 검증 — 4-4: toIntOrNull으로 비-hex → FrameException
        val endCode = s.substring(18, 22).toIntOrNull(16)
            ?: throw FrameException("3E ASCII bit response invalid EndCode: '${s.substring(18, 22)}'")
        if (!EndCode.isNormal(endCode)) {
            throw McProtocolException.plcError(endCode, ByteArray(0))
        }

        // ResponseData: char 22 이후, 1 char per bit (실 PLC 검증: '1'=ON, '0'=OFF)
        // MC ASCII 비트 read 응답은 비트당 1 char. (구 "2 char per bit"는 오해 — 실 PLC X0/X1 ON 미표시로 검출)
        val dataStr = s.substring(22)
        // P6(회귀 검증): require() → FrameException (계약 정합, 메시지 보존)
        if (dataStr.length < bitCount) {
            throw FrameException(
                "ASCII bit response data too short: expected $bitCount chars, got ${dataStr.length}"
            )
        }

        return BooleanArray(bitCount) { i -> dataStr[i] == '1' }
    }

    /**
     * 3E ASCII 응답 바이트 배열을 US-ASCII 문자열로 파싱하고 기본 검증을 수행하는 내부 헬퍼.
     *
     * - 최소 22 char 검증
     * - Subheader "D000" 검증
     *
     * @return 전체 응답 문자열 (대소문자 원본 유지)
     * @throws FrameException Subheader 불일치 / 길이 부족
     */
    private fun parseAsciiResponseString(bytes: ByteArray): String {
        if (bytes.size < 22) {
            throw FrameException(
                "3E ASCII response too short: expected at least 22 chars, got ${bytes.size}"
            )
        }
        val s = bytes.toString(Charsets.US_ASCII)

        // Subheader "D000" 검증 (대소문자 허용 — 실 PLC 응답 확인 전 방어)
        val subheader = s.substring(0, 4)
        if (!subheader.equals(FrameConstants.SUBHEADER_3E_RESPONSE_ASCII, ignoreCase = true)) {
            throw FrameException(
                "3E ASCII invalid response subheader: expected '${FrameConstants.SUBHEADER_3E_RESPONSE_ASCII}', " +
                "got '${subheader.take(4)}'"
            )
        }
        return s
    }

    private fun Byte.toHex(): String = "%02X".format(this.toInt() and 0xFF)
    private fun Int.toHex4(): String = "%04X".format(this)

    companion object {
        /**
         * offset 위치의 2 byte를 little-endian unsigned short (0~65535)로 읽는다.
         *
         * MC 프로토콜 참조: "send 2-byte numerical value from the lower byte (L: bits 0 to 7)"
         * 따라서 파싱도 동일: bytes[offset] = L byte, bytes[offset+1] = H byte
         */
        fun readUShortLE(bytes: ByteArray, offset: Int): Int {
            val lo = bytes[offset].toInt() and 0xFF
            val hi = bytes[offset + 1].toInt() and 0xFF
            return (hi shl 8) or lo
        }
    }
}
