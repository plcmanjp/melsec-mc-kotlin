// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

/**
 * MC Protocol 프레임 타입
 *
 * 출처: MC 프로토콜 참조 (Frame Type 4종, 공식 매뉴얼)
 *
 * [FRAME_3E] 3E Frame — Q/L/iQ-R SLMP 표준. Subheader 2 byte.
 * [FRAME_4E] 4E Frame — 3E + Serial No.(2 byte) + Fixed(2 byte) 추가.
 *            다중 패킷 추적 시 사용 (Serial echo 검증).
 */
enum class FrameType { FRAME_3E, FRAME_4E }
