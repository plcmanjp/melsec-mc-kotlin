// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc

import com.plciocheck.melsecmc.frame.EndCode

/**
 * MC Protocol 통신/파싱 예외 — 기반 클래스
 *
 * [message] : 사람이 읽을 수 있는 설명
 * [endCode] : PLC 응답 End Code (이상 완료 시). 정상 완료 예외는 null.
 * [cause]   : 원인 예외 (IOException 래핑 등)
 */
open class McProtocolException(
    message: String,
    val endCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** End Code 카테고리 (endCode가 있을 때만 유효) */
    val errorCategory: EndCode.Category?
        get() = endCode?.let { EndCode.classify(it) }

    companion object {
        fun invalidDevice(input: String): McProtocolException =
            McProtocolException("Invalid device address: '$input'")

        fun invalidSubheader(got: ByteArray): McProtocolException =
            FrameException(
                "Invalid response subheader: expected D0 00, got ${got.toHexString()}"
            )

        fun responseTooShort(expected: Int, got: Int): McProtocolException =
            FrameException(
                "Response too short: expected at least $expected bytes, got $got"
            )

        fun plcError(endCode: Int, errorInfo: ByteArray): McProtocolException =
            McProtocolException(
                "PLC error: endCode=0x${endCode.toString(16).uppercase().padStart(4, '0')}" +
                " category=${EndCode.classify(endCode)}" +
                " errorInfo=${errorInfo.toHexString()}",
                endCode = endCode
            )

        internal fun ByteArray.toHexString(): String =
            joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }
}

// ── transport 예외 (카테고리별 분리 — 호출측 catch 가능) ──────────────────

/**
 * TCP 연결 실패 / EOF / 소켓 I/O 오류
 *
 * [cause] 원인 IOException
 */
class ConnectionException(
    message: String,
    cause: Throwable? = null
) : McProtocolException(message, endCode = null, cause = cause) {

    companion object {
        fun eof(): ConnectionException =
            ConnectionException("Connection closed by remote (EOF)")

        fun connectFailed(ip: String, port: Int, cause: Throwable): ConnectionException =
            ConnectionException("Cannot connect to $ip:$port: ${cause.message}", cause)

        fun retriesExhausted(ip: String, port: Int, maxRetry: Int, lastCause: Throwable): ConnectionException =
            ConnectionException(
                "Connection failed after $maxRetry retries to $ip:$port: ${lastCause.message}",
                lastCause
            )
    }
}

/**
 * PLC 응답 수신 타임아웃
 *
 * [timeoutMs] 설정된 타임아웃 (ms)
 */
class ResponseTimeoutException(
    val timeoutMs: Long
) : McProtocolException("PLC response timeout after ${timeoutMs}ms")

/**
 * 3E 응답 프레임 구조 오류 (Subheader 불일치, 길이 부족 등)
 *
 * frame 레이어에서 파싱 실패 시 throw — McProtocolException.invalidSubheader /
 * responseTooShort 팩토리가 이 타입으로 생성됨.
 */
class FrameException(
    message: String,
    cause: Throwable? = null
) : McProtocolException(message, endCode = null, cause = cause)
