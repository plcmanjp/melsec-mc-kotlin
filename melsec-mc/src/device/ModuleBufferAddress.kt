// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.device

import java.util.Locale

/** Un is the upper three hexadecimal digits of the module start I/O number, not a byte offset. */
data class ModuleBufferAddress(val module: Int, val number: Long, val bitIndex: Int? = null) {
    init {
        require(module in 0..0xFFF) { "Module selector must fit three hexadecimal digits" }
        require(number in 0..0xFFFF_FFFFL) { "Buffer number must fit an unsigned 32-bit field" }
        require(bitIndex == null || bitIndex in 0..15) { "Buffer bit must be 0..F" }
    }

    val wordAddress: ModuleBufferAddress get() = if (bitIndex == null) this else copy(bitIndex = null)

    override fun toString(): String = buildString {
        append('U').append(module.toString(16).uppercase(Locale.ROOT)).append("\\G").append(number)
        bitIndex?.let { append('.').append(it.toString(16).uppercase(Locale.ROOT)) }
    }

    companion object {
        private val syntax = Regex("^U([0-9A-F]{1,3})\\\\G([0-9]{1,10})(?:\\.([0-9A-F]))?$")

        /** Full-token parsing only. Index/indirect/digit modifiers are not static buffer addresses. */
        fun parseOrNull(raw: String): ModuleBufferAddress? {
            if (raw.length > 32) return null
            val token = raw.trim().uppercase(Locale.ROOT).replace('₩', '\\').replace('¥', '\\')
            val match = syntax.matchEntire(token) ?: return null
            val number = match.groupValues[2].toLongOrNull() ?: return null
            if (number > 0xFFFF_FFFFL) return null
            return ModuleBufferAddress(match.groupValues[1].toInt(16), number,
                match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt(16))
        }
    }
}
