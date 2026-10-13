// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.frame

import kotlin.test.Test

class ModuleBufferCodecTest {
    @Test fun addressIdentity() = ModuleBufferContractCases.addressIdentity()
    @Test fun literalFrames() = ModuleBufferContractCases.literalFrames()
    @Test fun exactReplies() = ModuleBufferContractCases.exactReplies()
    @Test fun malformedReplies() = ModuleBufferContractCases.malformedReplies()
    @Test fun fieldBounds() = ModuleBufferContractCases.fieldBounds()
    @Test fun ordinaryGAndSerials() = ModuleBufferContractCases.ordinaryGAndSerials()
}
