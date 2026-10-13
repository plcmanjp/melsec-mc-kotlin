// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.frame

/**
 * MC Protocol 데이터 코드 모드
 *
 * 출처: MC 프로토콜 참조 (Binary), (ASCII), 공식 매뉴얼~
 *
 * [BINARY] Binary 코드. 바이트 직렬화, 데이터량 1배. 006 기본값.
 * [ASCII]  ASCII 코드. 대문자 16진수 문자열, 데이터량 Binary 대비 2배.
 */
enum class DataMode { BINARY, ASCII }
