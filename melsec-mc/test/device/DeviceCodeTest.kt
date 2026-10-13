// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DeviceCode enum 테스트
 *
 * 출처:
 *   - MC 프로토콜 참조 (워드 디바이스 코드 표)
 *   - MC 프로토콜 참조 (비트 디바이스 코드 표)
 *   - MC 프로토콜 참조 (iQ-R Long 디바이스 코드)
 *
 * 각 코드 값은 공식 매뉴얼 표에서 직접 인용 — 추정 없음.
 */
class DeviceCodeTest {

    // ── 워드 디바이스 Q/L 코드 검증 (MC 프로토콜 참조) ──────────────────────
    @Test
    fun `D_code_is_0xA8`() = assertDeviceCode(DeviceCode.D, qlCode = 0xA8, iqrCode = 0x00A8, isBit = false, isHex = false)

    @Test
    fun `W_code_is_0xB4_hex_notation`() = assertDeviceCode(DeviceCode.W, qlCode = 0xB4, iqrCode = 0x00B4, isBit = false, isHex = true)

    @Test
    fun `SD_code_is_0xA9`() = assertDeviceCode(DeviceCode.SD, qlCode = 0xA9, iqrCode = 0x00A9, isBit = false, isHex = false)

    @Test
    fun `SW_code_is_0xB5_hex_notation`() = assertDeviceCode(DeviceCode.SW, qlCode = 0xB5, iqrCode = 0x00B5, isBit = false, isHex = true)

    @Test
    fun `TN_code_is_0xC2`() = assertDeviceCode(DeviceCode.TN, qlCode = 0xC2, iqrCode = 0x00C2, isBit = false, isHex = false)

    @Test
    fun `CN_code_is_0xC5`() = assertDeviceCode(DeviceCode.CN, qlCode = 0xC5, iqrCode = 0x00C5, isBit = false, isHex = false)

    @Test
    fun `STN_code_is_0xC8`() = assertDeviceCode(DeviceCode.STN, qlCode = 0xC8, iqrCode = 0x00C8, isBit = false, isHex = false)

    @Test
    fun `R_code_is_0xAF`() = assertDeviceCode(DeviceCode.R, qlCode = 0xAF, iqrCode = 0x00AF, isBit = false, isHex = false)

    @Test
    fun `ZR_code_is_0xB0_hex_notation`() = assertDeviceCode(DeviceCode.ZR, qlCode = 0xB0, iqrCode = 0x00B0, isBit = false, isHex = true)

    @Test
    fun `Z_code_is_0xCC`() = assertDeviceCode(DeviceCode.Z, qlCode = 0xCC, iqrCode = 0x00CC, isBit = false, isHex = false)

    @Test
    fun `G_code_is_0xAB`() = assertDeviceCode(DeviceCode.G, qlCode = 0xAB, iqrCode = 0x00AB, isBit = false, isHex = false)

    // ── 비트 디바이스 Q/L 코드 검증 (MC 프로토콜 참조) ──────────────────────
    @Test
    fun `M_code_is_0x90`() = assertDeviceCode(DeviceCode.M, qlCode = 0x90, iqrCode = 0x0090, isBit = true, isHex = false)

    @Test
    fun `X_code_is_0x9C_hex_notation`() = assertDeviceCode(DeviceCode.X, qlCode = 0x9C, iqrCode = 0x009C, isBit = true, isHex = true)

    @Test
    fun `Y_code_is_0x9D_hex_notation`() = assertDeviceCode(DeviceCode.Y, qlCode = 0x9D, iqrCode = 0x009D, isBit = true, isHex = true)

    @Test
    fun `L_code_is_0x92`() = assertDeviceCode(DeviceCode.L, qlCode = 0x92, iqrCode = 0x0092, isBit = true, isHex = false)

    @Test
    fun `F_code_is_0x93`() = assertDeviceCode(DeviceCode.F, qlCode = 0x93, iqrCode = 0x0093, isBit = true, isHex = false)

    @Test
    fun `V_code_is_0x94`() = assertDeviceCode(DeviceCode.V, qlCode = 0x94, iqrCode = 0x0094, isBit = true, isHex = false)

    @Test
    fun `B_code_is_0xA0_hex_notation`() = assertDeviceCode(DeviceCode.B, qlCode = 0xA0, iqrCode = 0x00A0, isBit = true, isHex = true)

    @Test
    fun `SM_code_is_0x91`() = assertDeviceCode(DeviceCode.SM, qlCode = 0x91, iqrCode = 0x0091, isBit = true, isHex = false)

    @Test
    fun `SB_code_is_0xA1_hex_notation`() = assertDeviceCode(DeviceCode.SB, qlCode = 0xA1, iqrCode = 0x00A1, isBit = true, isHex = true)

    @Test
    fun `S_code_is_0x98`() = assertDeviceCode(DeviceCode.S, qlCode = 0x98, iqrCode = 0x0098, isBit = true, isHex = false)

    @Test
    fun `DX_code_is_0xA2_hex_notation`() = assertDeviceCode(DeviceCode.DX, qlCode = 0xA2, iqrCode = 0x00A2, isBit = true, isHex = true)

    @Test
    fun `DY_code_is_0xA3_hex_notation`() = assertDeviceCode(DeviceCode.DY, qlCode = 0xA3, iqrCode = 0x00A3, isBit = true, isHex = true)

    @Test
    fun `TS_code_is_0xC1`() = assertDeviceCode(DeviceCode.TS, qlCode = 0xC1, iqrCode = 0x00C1, isBit = true, isHex = false)

    @Test
    fun `TC_code_is_0xC0`() = assertDeviceCode(DeviceCode.TC, qlCode = 0xC0, iqrCode = 0x00C0, isBit = true, isHex = false)

    @Test
    fun `CS_code_is_0xC4`() = assertDeviceCode(DeviceCode.CS, qlCode = 0xC4, iqrCode = 0x00C4, isBit = true, isHex = false)

    @Test
    fun `CC_code_is_0xC3`() = assertDeviceCode(DeviceCode.CC, qlCode = 0xC3, iqrCode = 0x00C3, isBit = true, isHex = false)

    // ── iQ-R Long 디바이스 (MC 프로토콜 참조, qlCode = -1) ──────────────────
    @Test
    fun `LTN_is_iqr_exclusive_not_ql_accessible`() {
        assertFalse(DeviceCode.LTN.isQlAccessible, "LTN must not be accessible via Q/L subcommand")
        assertEquals(0x0052, DeviceCode.LTN.iqrCode)
    }

    @Test
    fun `LCN_iqr_code_is_0x0056`() {
        assertEquals(0x0056, DeviceCode.LCN.iqrCode)
        assertFalse(DeviceCode.LCN.isQlAccessible)
    }

    @Test
    fun `RD_iqr_code_is_0x002C`() {
        assertEquals(0x002C, DeviceCode.RD.iqrCode)
        assertFalse(DeviceCode.RD.isQlAccessible)
    }

    // ── 총 디바이스 수 검증 ─────────────────────────────────────────────
    @Test
    fun `total_11_word_devices`() {
        val wordDevices = DeviceCode.entries.filter { !it.isBit && it.isQlAccessible }
        assertEquals(11, wordDevices.size,
            "Must have 11 word devices (D,W,SD,SW,TN,CN,STN,R,ZR,Z,G)")
    }

    @Test
    fun `total_16_bit_devices`() {
        val bitDevices = DeviceCode.entries.filter { it.isBit && it.isQlAccessible }
        assertEquals(16, bitDevices.size,
            "Must have 16 bit devices (M,X,Y,L,F,V,B,SM,SB,S,DX,DY,TS,TC,CS,CC)")
    }

    // ── fromPrefix 테스트 ──────────────────────────────────────────────
    @Test
    fun `fromPrefix_D_returns_D`() {
        assertEquals(DeviceCode.D, DeviceCode.fromPrefix("D"))
    }

    @Test
    fun `fromPrefix_case_insensitive`() {
        assertNotNull(DeviceCode.fromPrefix("d"))
        assertNotNull(DeviceCode.fromPrefix("m"))
    }

    // ── ASCII 코드 검증 (MC 프로토콜 참조, 공식 매뉴얼 ASCII 컬럼) ─────────────
    // 단일 문자 디바이스: 1char + 공백 → 2char 총합
    @Test
    fun `D_asciiCode_is_D_space`() = assertEquals("D ", DeviceCode.D.asciiCode, "D ASCII code must be 'D ' (2 char)")

    @Test
    fun `M_asciiCode_is_M_space`() = assertEquals("M ", DeviceCode.M.asciiCode, "M ASCII code must be 'M ' (2 char)")

    @Test
    fun `X_asciiCode_is_X_space`() = assertEquals("X ", DeviceCode.X.asciiCode, "X ASCII code must be 'X ' (2 char)")

    @Test
    fun `Y_asciiCode_is_Y_space`() = assertEquals("Y ", DeviceCode.Y.asciiCode, "Y ASCII code must be 'Y ' (2 char)")

    @Test
    fun `W_asciiCode_is_W_space`() = assertEquals("W ", DeviceCode.W.asciiCode, "W ASCII code must be 'W ' (2 char)")

    @Test
    fun `R_asciiCode_is_R_space`() = assertEquals("R ", DeviceCode.R.asciiCode, "R ASCII code must be 'R ' (2 char)")

    @Test
    fun `Z_asciiCode_is_Z_space`() = assertEquals("Z ", DeviceCode.Z.asciiCode, "Z ASCII code must be 'Z ' (2 char)")

    @Test
    fun `L_asciiCode_is_L_space`() = assertEquals("L ", DeviceCode.L.asciiCode, "L ASCII code must be 'L ' (2 char)")

    @Test
    fun `B_asciiCode_is_B_space`() = assertEquals("B ", DeviceCode.B.asciiCode, "B ASCII code must be 'B ' (2 char)")

    @Test
    fun `S_asciiCode_is_S_space`() = assertEquals("S ", DeviceCode.S.asciiCode, "S ASCII code must be 'S ' (2 char)")

    @Test
    fun `F_asciiCode_is_F_space`() = assertEquals("F ", DeviceCode.F.asciiCode, "F ASCII code must be 'F ' (2 char)")

    @Test
    fun `V_asciiCode_is_V_space`() = assertEquals("V ", DeviceCode.V.asciiCode, "V ASCII code must be 'V ' (2 char)")

    @Test
    fun `G_asciiCode_is_G_space`() = assertEquals("G ", DeviceCode.G.asciiCode, "G ASCII code must be 'G ' (2 char)")

    // 2문자 디바이스: 그대로 2char
    @Test
    fun `SD_asciiCode_is_SD`() = assertEquals("SD", DeviceCode.SD.asciiCode, "SD ASCII code must be 'SD' (2 char)")

    @Test
    fun `SW_asciiCode_is_SW`() = assertEquals("SW", DeviceCode.SW.asciiCode, "SW ASCII code must be 'SW' (2 char)")

    @Test
    fun `TN_asciiCode_is_TN`() = assertEquals("TN", DeviceCode.TN.asciiCode, "TN ASCII code must be 'TN' (2 char)")

    @Test
    fun `CN_asciiCode_is_CN`() = assertEquals("CN", DeviceCode.CN.asciiCode, "CN ASCII code must be 'CN' (2 char)")

    @Test
    fun `STN_asciiCode_is_ST`() = assertEquals("ST", DeviceCode.STN.asciiCode, "STN ASCII code must be 'ST' (2 char, detail-03 §5)")

    @Test
    fun `ZR_asciiCode_is_ZR`() = assertEquals("ZR", DeviceCode.ZR.asciiCode, "ZR ASCII code must be 'ZR' (2 char)")

    @Test
    fun `SM_asciiCode_is_SM`() = assertEquals("SM", DeviceCode.SM.asciiCode, "SM ASCII code must be 'SM' (2 char)")

    @Test
    fun `SB_asciiCode_is_SB`() = assertEquals("SB", DeviceCode.SB.asciiCode, "SB ASCII code must be 'SB' (2 char)")

    @Test
    fun `DX_asciiCode_is_DX`() = assertEquals("DX", DeviceCode.DX.asciiCode, "DX ASCII code must be 'DX' (2 char)")

    @Test
    fun `DY_asciiCode_is_DY`() = assertEquals("DY", DeviceCode.DY.asciiCode, "DY ASCII code must be 'DY' (2 char)")

    @Test
    fun `TS_asciiCode_is_TS`() = assertEquals("TS", DeviceCode.TS.asciiCode, "TS ASCII code must be 'TS' (2 char)")

    @Test
    fun `TC_asciiCode_is_TC`() = assertEquals("TC", DeviceCode.TC.asciiCode, "TC ASCII code must be 'TC' (2 char)")

    @Test
    fun `CS_asciiCode_is_CS`() = assertEquals("CS", DeviceCode.CS.asciiCode, "CS ASCII code must be 'CS' (2 char)")

    @Test
    fun `CC_asciiCode_is_CC`() = assertEquals("CC", DeviceCode.CC.asciiCode, "CC ASCII code must be 'CC' (2 char)")

    // iQ-R 전용: asciiCode = "" (Q/L ASCII 미지원)
    @Test
    fun `LTN_asciiCode_is_empty`() = assertEquals("", DeviceCode.LTN.asciiCode, "LTN is iQ-R exclusive, asciiCode must be empty")

    @Test
    fun `LCN_asciiCode_is_empty`() = assertEquals("", DeviceCode.LCN.asciiCode, "LCN is iQ-R exclusive, asciiCode must be empty")

    @Test
    fun `RD_asciiCode_is_empty`() = assertEquals("", DeviceCode.RD.asciiCode, "RD is iQ-R exclusive, asciiCode must be empty")

    // asciiCode 길이 불변: Q/L 접근 가능 디바이스는 반드시 2 char
    @Test
    fun `all_ql_accessible_asciiCode_length_is_2`() {
        val violations = DeviceCode.entries
            .filter { it.isQlAccessible }
            .filter { it.asciiCode.length != 2 }
        assertEquals(0, violations.size,
            "All Q/L accessible devices must have asciiCode.length == 2, violations: ${violations.map { it.name }}")
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────
    private fun assertDeviceCode(
        code: DeviceCode,
        qlCode: Int,
        iqrCode: Int,
        isBit: Boolean,
        isHex: Boolean
    ) {
        assertEquals(qlCode, code.qlCode, "${code.name} qlCode mismatch")
        assertEquals(iqrCode, code.iqrCode, "${code.name} iqrCode mismatch")
        assertEquals(isBit, code.isBit, "${code.name} isBit mismatch")
        assertEquals(isHex, code.isHexNotation, "${code.name} isHexNotation mismatch")
    }
}
