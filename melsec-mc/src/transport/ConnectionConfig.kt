// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.transport

import melsecmckotlin.frame.DataMode
import melsecmckotlin.frame.FrameType

/**
 * PLC TCP/UDP 연결 설정
 *
 * TCP timeoutMs와 connectTimeoutMs는 호출자가 1..Int.MAX_VALUE 밀리초로 제한해야 한다.
 * Java 소켓에 전달할 때 Int로 변환하므로 Long의 초과 값은 지원하지 않는다.
 *
 * [ip]          PLC Ethernet 모듈 IP 주소
 * [port]        통신 포트 (기본 1025, 통신 기본값). 실제 포트는 PLC의 Procedure Exist 연결 포트와 일치시킨다.
 * [timeoutMs]   응답 대기 제한 (ms). Monitoring Timer 4000ms와 정합 (MC 프로토콜 참조)
 * [connectTimeoutMs] 소켓 connect() 제한 (ms)
 * [maxRetry]    연결 실패 시 최대 재시도 횟수 (0 = 재시도 없음).
 *               **TCP([McSocketClient]) 전용** — UDP([McDatagramClient])는 이 값을
 *               참조하지 않는다(단일 시도, 회귀 검증).
 * [retryBackoffMs] 재시도 간 최소 대기 시간 (ms). 지수 backoff의 기본값.
 *               **TCP([McSocketClient]) 전용** — UDP([McDatagramClient])는 이 값을
 *               참조하지 않는다(회귀 검증).
 * [frameType]   MC Protocol 프레임 타입 (기본 FRAME_3E). MC 프로토콜 참조.
 * [dataMode]    데이터 코드 모드 (기본 BINARY). MC 프로토콜 참조.
 */
data class ConnectionConfig(
    val ip: String,
    val port: Int = 1025,
    val timeoutMs: Long = 4000L,
    val connectTimeoutMs: Long = 4000L,
    val maxRetry: Int = 3,
    val retryBackoffMs: Long = 500L,
    val frameType: FrameType = FrameType.FRAME_3E,
    val dataMode: DataMode = DataMode.BINARY
) {
    init {
        require(ip.isNotBlank()) { "IP address must not be blank" }
        require(port in 1..65535) { "Port must be 1..65535, got $port" }
        require(timeoutMs > 0) { "timeoutMs must be > 0, got $timeoutMs" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be > 0, got $connectTimeoutMs" }
        require(maxRetry >= 0) { "maxRetry must be >= 0, got $maxRetry" }
        require(retryBackoffMs >= 0) { "retryBackoffMs must be >= 0, got $retryBackoffMs" }
    }
}
