// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.transport

import melsecmckotlin.ConnectionException
import melsecmckotlin.FrameException
import melsecmckotlin.frame.DataMode
import melsecmckotlin.frame.FrameType
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * 3E/4E Binary/ASCII 응답 부분 수신 누적 리더
 *
 * TCP는 단일 read() 호출로 전체 프레임이 도착하지 않을 수 있다.
 * 이 클래스는 응답 헤더에서 전체 길이를 파악하고, 그만큼 누적 수신한다.
 *
 * 3E Binary 응답 레이아웃 (MC 프로토콜 참조):
 *   [Subheader]    2 byte  D0H 00H
 *   [Access Route] 5 byte
 *   [RespDataLen]  2 byte  LE — 이후 수신할 byte 수 (EndCode 2 + ResponseData N)
 *   ─ 여기까지 9 byte = 고정 헤더 ─
 *   [EndCode]      2 byte  LE  ┐
 *   [ResponseData] N byte      ┘ RespDataLen byte 분량
 *
 * 4E Binary 응답 레이아웃 (MC 프로토콜 참조, 공식 매뉴얼):
 *   [Subheader]    2 byte  D4H 00H
 *   [Serial No.]   2 byte  (요청 Serial echo)
 *   [Fixed]        2 byte  00H 00H
 *   [Access Route] 5 byte
 *   [RespDataLen]  2 byte  LE  ← offset 11-12 (3E는 7-8)
 *   ─ 여기까지 13 byte = 고정 헤더 ─
 *   [EndCode]      2 byte  LE  ┐
 *   [ResponseData] N byte      ┘ RespDataLen byte 분량
 *
 * 3E ASCII 응답 레이아웃 (MC 프로토콜 참조, 프레임 검증):
 *   [Subheader]    4 char  "D000" = 4 byte (US-ASCII)
 *   [Access Route] 10 char
 *   [RespDataLen]  4 char  hex 문자열 (char 단위 — 이후 수신할 char 수)
 *   ─ 여기까지 18 char/byte = 고정 헤더 ─
 *   [EndCode]      4 char  ┐
 *   [ResponseData] N char  ┘ RespDataLen char 분량
 *
 * 주의:
 *   - 단일 read() 결과를 완전 프레임으로 가정하지 않는다.
 *   - read() 반환값이 -1 이면 서버가 연결을 끊은 것 → ConnectionException
 *   - Subheader 불일치 → FrameException
 */
object PlcResponseReader {

    /** 3E Binary 응답 고정 헤더 크기 (MC 프로토콜 참조) */
    const val HEADER_SIZE_3E = 9

    /** 4E Binary 응답 고정 헤더 크기 (MC 프로토콜 참조) */
    const val HEADER_SIZE_4E = 13

    /** 3E ASCII 응답 고정 헤더 크기 (char = byte, MC 프로토콜 참조) */
    const val HEADER_SIZE_3E_ASCII = 18

    /**
     * 4E ASCII 응답 고정 헤더 크기 (char = byte, MC 프로토콜 참조, 프레임 검증):
     *   Subheader(4) + Serial(4) + Fixed(4) + Access Route(10) + RespDataLen(4) = 26 char
     */
    const val HEADER_SIZE_4E_ASCII = 26

    /** 하위 호환: 기존 코드가 참조하던 HEADER_SIZE = 3E 크기 */
    const val HEADER_SIZE = HEADER_SIZE_3E

    /**
     * 응답 데이터 길이 상한 (byte 단위). 4-3: 깨진 헤더가 65535를 주면
     * 65 KB 버퍼 + timeoutMs 대기로 IO 스레드를 장기 점유하는 문제를 방어한다.
     *
     * MC Protocol 실제 최대 페이로드 (RespDataLen 기준):
     *   워드 일괄 읽기 최대 960점 × 2 byte = 1,920 byte (+ EndCode 2 = 1,922)
     *   비트 일괄 읽기 최대 7,168점 / 8 ≈ 896 byte (Binary nibble)
     *   ASCII 배율 최대: 워드 960점 × 4 char = 3,840 char (+ EndCode 4 = 3,844)
     *   4E ASCII: 3E ASCII와 같은 데이터 길이 (헤더 크기만 다름)
     * 8,192를 여유 상한으로 설정 (실제 최대의 약 2배, 65535의 1/8).
     */
    private const val MAX_PAYLOAD = 8192

    /** 3E Binary 응답 Subheader: D0H 00H */
    private val EXPECTED_SUBHEADER_3E = byteArrayOf(0xD0.toByte(), 0x00.toByte())

    /** 4E Binary 응답 Subheader: D4H 00H */
    private val EXPECTED_SUBHEADER_4E = byteArrayOf(0xD4.toByte(), 0x00.toByte())

    /** 3E ASCII 응답 Subheader: "D000" — McDatagramClient에서도 참조 */
    internal const val EXPECTED_SUBHEADER_3E_ASCII = "D000"

    /** 4E ASCII 응답 Subheader: "D400" — McDatagramClient에서도 참조 */
    internal const val EXPECTED_SUBHEADER_4E_ASCII = "D400"

    /**
     * [stream]에서 완전한 응답 한 프레임을 읽어 반환한다.
     *
     * [frameType] × [dataMode] 조합에 따라 수신 로직을 선택한다:
     *   3E Binary: 기존 3E 수신 로직 (변경 없음)
     *   4E Binary: 4E 수신 로직 (HEADER_SIZE=13, RespDataLen offset=11)
     *   3E ASCII:  ASCII 수신 로직 (고정 헤더 18 char, RespDataLen = char 수)
     *   4E ASCII:  ASCII 수신 로직 (고정 헤더 26 char, RespDataLen = char 수)
     *
     * Dispatchers.IO 블록 안에서 호출되어야 한다.
     *
     * @return 전체 응답 바이트 배열 (고정 헤더 + RespDataLen 분량)
     * @throws ConnectionException   read() == -1 (서버 종료 / EOF)
     * @throws FrameException        Subheader 불일치
     */
    fun readFrame(
        stream: InputStream,
        frameType: FrameType = FrameType.FRAME_3E,
        dataMode: DataMode = DataMode.BINARY
    ): ByteArray = when {
        frameType == FrameType.FRAME_3E && dataMode == DataMode.BINARY -> readFrame3E(stream)
        frameType == FrameType.FRAME_4E && dataMode == DataMode.BINARY -> readFrame4E(stream)
        frameType == FrameType.FRAME_3E && dataMode == DataMode.ASCII  -> readFrame3EAscii(stream)
        frameType == FrameType.FRAME_4E && dataMode == DataMode.ASCII  -> readFrame4EAscii(stream)
        else -> throw UnsupportedOperationException(
            "Unsupported frame combination: frameType=$frameType, dataMode=$dataMode"
        )
    }

    // ── 3E Binary 수신 (기존 로직, 불변) ─────────────────────────────────

    /**
     * 3E Binary 응답 수신.
     *
     * 이 함수는 골든 기준이다. 4E 추가로 인해 이 경로가 변경되면 안 된다.
     */
    private fun readFrame3E(stream: InputStream): ByteArray {
        // 1단계: 고정 헤더 9 byte 누적 수신
        val header = readExactly(stream, HEADER_SIZE_3E)

        // 2단계: Subheader D0H 00H 검증
        if (header[0] != EXPECTED_SUBHEADER_3E[0] || header[1] != EXPECTED_SUBHEADER_3E[1]) {
            throw FrameException(
                "Invalid 3E response subheader: expected D0 00, " +
                "got ${header[0].toHex()} ${header[1].toHex()}"
            )
        }

        // 3단계: RespDataLen 2 byte LE (header[7], header[8])
        //   little-endian: L byte = header[7], H byte = header[8]
        val respDataLen = (header[8].toInt() and 0xFF shl 8) or (header[7].toInt() and 0xFF)

        // 4-3: 상한 검증 (Binary 2-byte LE → 0~65535, 음수 불가이지만 상한만 체크로 충분)
        if (respDataLen > MAX_PAYLOAD) {
            throw FrameException("3E 응답 길이 초과: $respDataLen (상한: $MAX_PAYLOAD)")
        }

        // 4단계: RespDataLen 만큼 나머지 바이트 누적 수신
        val body = if (respDataLen > 0) {
            readExactly(stream, respDataLen)
        } else {
            ByteArray(0)
        }

        // 5단계: 헤더 + 바디 합쳐 반환
        val frame = ByteArray(HEADER_SIZE_3E + respDataLen)
        header.copyInto(frame, 0)
        body.copyInto(frame, HEADER_SIZE_3E)
        return frame
    }

    // ── 4E Binary 수신 ───────────────────────────────────────────────────

    /**
     * 4E Binary 응답 수신 (MC 프로토콜 참조, 공식 매뉴얼).
     *
     * 4E 고정 헤더: 13 byte.
     * RespDataLen 위치: header[11], header[12] (3E는 header[7], header[8]).
     */
    private fun readFrame4E(stream: InputStream): ByteArray {
        // 1단계: 고정 헤더 13 byte 누적 수신
        val header = readExactly(stream, HEADER_SIZE_4E)

        // 2단계: Subheader D4H 00H 검증
        if (header[0] != EXPECTED_SUBHEADER_4E[0] || header[1] != EXPECTED_SUBHEADER_4E[1]) {
            throw FrameException(
                "Invalid 4E response subheader: expected D4 00, " +
                "got ${header[0].toHex()} ${header[1].toHex()}"
            )
        }

        // 3단계: RespDataLen 2 byte LE (header[11], header[12])
        //   4E 레이아웃: Subhdr(2)+Serial(2)+Fixed(2)+Route(5)+RespDataLen(2) = 13 byte
        //   offset 11 = L byte, offset 12 = H byte
        val respDataLen = (header[12].toInt() and 0xFF shl 8) or (header[11].toInt() and 0xFF)

        // 4-3: 상한 검증 (Binary 2-byte LE → 0~65535)
        if (respDataLen > MAX_PAYLOAD) {
            throw FrameException("4E 응답 길이 초과: $respDataLen (상한: $MAX_PAYLOAD)")
        }

        // 4단계: RespDataLen 만큼 나머지 바이트 누적 수신
        val body = if (respDataLen > 0) {
            readExactly(stream, respDataLen)
        } else {
            ByteArray(0)
        }

        // 5단계: 헤더 + 바디 합쳐 반환
        val frame = ByteArray(HEADER_SIZE_4E + respDataLen)
        header.copyInto(frame, 0)
        body.copyInto(frame, HEADER_SIZE_4E)
        return frame
    }

    // ── 3E ASCII 수신 ─────────────────────────────────────────────────────

    /**
     * 3E ASCII 응답 수신 (MC 프로토콜 참조, 프레임 검증).
     *
     * ASCII 응답은 US-ASCII 바이트 스트림으로 전달된다 (소켓은 byte 스트림).
     * 1 char = 1 byte (US-ASCII 기준).
     *
     * 수신 절차:
     *   1단계: 고정 헤더 18 byte 수신
     *          Subheader(4) + AccessRoute(10) + RespDataLen(4) = 18 char/byte
     *   2단계: Subheader "D000" 검증
     *   3단계: RespDataLen 4 char 파싱 (hex string → int = body byte 수)
     *   4단계: body (RespDataLen 바이트) 추가 수신
     *   5단계: 헤더 + 바디 합산 반환
     *
     * RespDataLen: ASCII char 수 단위 (= byte 수, 1 char = 1 byte in US-ASCII)
     */
    private fun readFrame3EAscii(stream: InputStream): ByteArray {
        // 1단계: 고정 헤더 18 byte 수신
        val header = readExactly(stream, HEADER_SIZE_3E_ASCII)

        // 2단계: Subheader "D000" 검증 (byte 0-3)
        val subheaderStr = header.copyOf(4).toString(Charsets.US_ASCII)
        if (!subheaderStr.equals(EXPECTED_SUBHEADER_3E_ASCII, ignoreCase = true)) {
            throw FrameException(
                "Invalid 3E ASCII response subheader: expected '$EXPECTED_SUBHEADER_3E_ASCII', " +
                "got '$subheaderStr'"
            )
        }

        // 3단계: RespDataLen 4 char (header[14..17] = byte 14-17)
        val respDataLenStr = header.copyOfRange(14, 18).toString(Charsets.US_ASCII)
        val respDataLen = respDataLenStr.toIntOrNull(16)
            ?: throw FrameException("3E ASCII invalid RespDataLen: '$respDataLenStr'")

        // 4-2b(P2): 음수 길이 가드. toIntOrNull(16)은 선두 '-'를 포함한 문자열도 음수로
        // 정상 파싱한다(방어하지 않음) — 손상/위조 응답이 "-001" 등을 주면 ByteArray(HEADER+음수)
        // → NegativeArraySizeException(McProtocolException 계층 밖)으로 이어질 수 있으므로 여기서 차단.
        if (respDataLen < 0) {
            throw FrameException("3E ASCII 응답 길이 음수: $respDataLen")
        }

        // 4-3: 상한 검증 (ASCII hex 4자리 → 0~65535)
        if (respDataLen > MAX_PAYLOAD) {
            throw FrameException("3E ASCII 응답 길이 초과: $respDataLen (상한: $MAX_PAYLOAD)")
        }

        // 4단계: body 수신 (RespDataLen byte)
        val body = if (respDataLen > 0) {
            readExactly(stream, respDataLen)
        } else {
            ByteArray(0)
        }

        // 5단계: 헤더 + 바디 합산 반환
        val frame = ByteArray(HEADER_SIZE_3E_ASCII + respDataLen)
        header.copyInto(frame, 0)
        body.copyInto(frame, HEADER_SIZE_3E_ASCII)
        return frame
    }

    // ── 4E ASCII 수신 ─────────────────────────────────────────────────────

    /**
     * 4E ASCII 응답 수신 (MC 프로토콜 참조, 프레임 검증).
     *
     * 4E ASCII 응답은 US-ASCII 바이트 스트림으로 전달된다 (1 char = 1 byte).
     *
     * 수신 절차:
     *   1단계: 고정 헤더 26 byte 수신
     *          Subheader(4) + Serial(4) + Fixed(4) + AccessRoute(10) + RespDataLen(4) = 26 char/byte
     *   2단계: Subheader "D400" 검증
     *   3단계: RespDataLen 4 char 파싱 (hex string → int = body char/byte 수)
     *   4단계: body (RespDataLen byte) 추가 수신
     *   5단계: 헤더 + 바디 합산 반환
     *
     * RespDataLen: ASCII char 수 단위 (= byte 수, 1 char = 1 byte in US-ASCII)
     * body = EndCode(4) + ResponseData(가변) char
     */
    private fun readFrame4EAscii(stream: InputStream): ByteArray {
        // 1단계: 고정 헤더 26 byte 수신
        val header = readExactly(stream, HEADER_SIZE_4E_ASCII)

        // 2단계: Subheader "D400" 검증 (byte 0-3)
        val subheaderStr = header.copyOf(4).toString(Charsets.US_ASCII)
        if (!subheaderStr.equals(EXPECTED_SUBHEADER_4E_ASCII, ignoreCase = true)) {
            throw FrameException(
                "Invalid 4E ASCII response subheader: expected '$EXPECTED_SUBHEADER_4E_ASCII', " +
                "got '$subheaderStr'"
            )
        }

        // 3단계: RespDataLen 4 char (header[22..25] = byte 22-25)
        val respDataLenStr = header.copyOfRange(22, 26).toString(Charsets.US_ASCII)
        val respDataLen = respDataLenStr.toIntOrNull(16)
            ?: throw FrameException("4E ASCII invalid RespDataLen: '$respDataLenStr'")

        // 4-2b(P2): 음수 길이 가드 (3E ASCII와 동일 근거 — toIntOrNull(16)은 '-' 선두 문자열도
        // 음수로 정상 파싱하므로 여기서 명시적으로 차단해야 한다).
        if (respDataLen < 0) {
            throw FrameException("4E ASCII 응답 길이 음수: $respDataLen")
        }

        // 4-3: 상한 검증
        if (respDataLen > MAX_PAYLOAD) {
            throw FrameException("4E ASCII 응답 길이 초과: $respDataLen (상한: $MAX_PAYLOAD)")
        }

        // 4단계: body 수신 (RespDataLen byte)
        val body = if (respDataLen > 0) {
            readExactly(stream, respDataLen)
        } else {
            ByteArray(0)
        }

        // 5단계: 헤더 + 바디 합산 반환
        val frame = ByteArray(HEADER_SIZE_4E_ASCII + respDataLen)
        header.copyInto(frame, 0)
        body.copyInto(frame, HEADER_SIZE_4E_ASCII)
        return frame
    }

    // ── 공용 유틸 ─────────────────────────────────────────────────────────

    /**
     * [stream]에서 정확히 [length] byte를 읽어 반환한다.
     *
     * read()가 부분 반환하거나 0을 반환하는 경우를 루프로 누적한다.
     * read() == -1 이면 ConnectionException.eof() throw.
     *
     * @throws ConnectionException EOF 감지 시
     */
    fun readExactly(stream: InputStream, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)

        val buf = ByteArray(length)
        var offset = 0

        while (offset < length) {
            val remaining = length - offset
            val n = try {
                stream.read(buf, offset, remaining)
            } catch (e: SocketTimeoutException) {
                // 부분 수신 중 타임아웃 — 누적 상태를 캡처한 뒤 동일 예외를 rethrow.
                // rethrow로 예외 타입·스택 불변 → McSocketClient catch 분기(SocketTimeoutException) 그대로 적용.
                PlcDiag.log("PlcDiag") {
                    "partial-timeout offset=$offset expected=$length" +
                    " head=${PlcDiag.hex(buf, minOf(offset, 32))}"
                }
                throw e   // 동작 무변경: 동일 예외 rethrow
            }
            when {
                n == -1 -> {
                    // EOF — 누적 상태 캡처 후 ConnectionException throw (기존 동작 동일).
                    PlcDiag.log("PlcDiag") {
                        "partial-eof offset=$offset expected=$length" +
                        " head=${PlcDiag.hex(buf, minOf(offset, 32))}"
                    }
                    throw ConnectionException.eof()
                }
                n > 0   -> offset += n
                // n == 0: 잠깐 데이터 없음 — 루프 계속 (InputStream 규약상 드물지만 허용)
            }
        }

        return buf
    }

    private fun Byte.toHex(): String = "%02X".format(this.toInt() and 0xFF)
}
