// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

import melsecmckotlin.FrameException
import melsecmckotlin.McProtocolException
import melsecmckotlin.device.ModuleBufferAddress
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Wire profiles, not a declaration that every CPU/port/module combination is accessible. */
enum class ModuleBufferProfile { Q, IQ_R, FX5 }

/** A request keeps its own serial, length and codec identity; mutable buffers are never shared. */
class ModuleBufferReadRequest internal constructor(
    val address: ModuleBufferAddress,
    val wordCount: Int,
    val serial: Int,
    internal val owner: Any,
    bytes: ByteArray,
) {
    private val encoded = bytes.copyOf()
    val bytes: ByteArray get() = encoded.copyOf()
}

/**
 * Read-only 0401/0080 and 0401/0082 module access.
 * SH-080956ENG-N Appendix 1 pp.212-214. FX5 Appendix 8 (JY997D56001K) documents 3E only.
 * FX5 binary F8/F9 authority is unresolved and remains disabled. No ordinary G fallback exists.
 */
class ModuleBufferCodec(
    private val profile: ModuleBufferProfile,
    private val frameType: FrameType,
    private val dataMode: DataMode,
    private val route: AccessRoute = AccessRoute.DEFAULT,
    private val monitoringTimer: Int = 16,
) {
    private val serials = AtomicInteger(0)
    private val identity = Any()
    internal var nextSerial: () -> Int = { serials.getAndIncrement() and 0xFFFF }

    init {
        require(route.networkNo in 0..255 && route.pcNo in 0..255 &&
            route.moduleIoNo in 0..65535 && route.moduleStationNo in 0..255)
        require(monitoringTimer in 1..65535) { "A bounded monitoring timer is required" }
    }

    /** Validate the whole selected range before any partial request is admitted. No serial is consumed. */
    fun validateRange(address: ModuleBufferAddress, wordCount: Long) {
        if (profile == ModuleBufferProfile.FX5 &&
            (frameType != FrameType.FRAME_3E || dataMode != DataMode.ASCII)) {
            throw FrameException("FX5 module access requires the verified 3E ASCII layout")
        }
        val wide = profile == ModuleBufferProfile.IQ_R
        val limit = when (dataMode) {
            DataMode.BINARY -> if (wide) 0xFFFF_FFFFL else 0xFF_FFFFL
            DataMode.ASCII -> if (wide) 99_999_999L else 999_999L
        }
        if (wordCount <= 0 || address.number > limit || wordCount - 1L > limit - address.number) {
            throw FrameException("Module buffer read exceeds its wire address field")
        }
        if (address.bitIndex != null && wordCount != 1L) {
            throw FrameException("A module bit selection requires one word")
        }
    }

    fun encodeRead(address: ModuleBufferAddress, wordCount: Int): ModuleBufferReadRequest {
        if (wordCount !in 1..960 || (address.bitIndex != null && wordCount != 1)) {
            throw FrameException("Module read requires 1..960 words; a bit selection requires one word")
        }
        validateRange(address, wordCount.toLong())
        val wide = profile == ModuleBufferProfile.IQ_R
        val serial = if (frameType == FrameType.FRAME_4E) nextSerial() else 0
        val subcommand = if (wide) 0x0082 else 0x0080
        val bytes = when (dataMode) {
            DataMode.BINARY -> {
                val payload = ByteArrayOutputStream()
                payload.le(monitoringTimer.toLong(), 2)
                payload.le(0x0401, 2)
                payload.le(subcommand.toLong(), 2)
                payload.le(0, 2)
                payload.le(address.number, if (wide) 4 else 3)
                payload.le(0xAB, if (wide) 2 else 1)
                payload.le(0, 2)
                payload.le(address.module.toLong(), 2)
                payload.write(0xF8)
                payload.le(wordCount.toLong(), 2)
                val body = payload.toByteArray()
                val out = ByteArrayOutputStream()
                out.write(if (frameType == FrameType.FRAME_4E) 0x54 else 0x50)
                out.write(0)
                if (frameType == FrameType.FRAME_4E) {
                    out.le(serial.toLong(), 2)
                    out.le(0, 2)
                }
                out.write(route.toBytes())
                out.le(body.size.toLong(), 2)
                out.write(body)
                out.toByteArray()
            }
            DataMode.ASCII -> {
                val zeroes = if (wide) "0000" else "000"
                val body = hex(monitoringTimer, 4) + "0401" + hex(subcommand, 4) +
                    "00U" + hex(address.module, 3) + zeroes + (if (wide) "G***" else "G*") +
                    address.number.toString().padStart(if (wide) 8 else 6, '0') + zeroes + hex(wordCount, 4)
                val header = if (frameType == FrameType.FRAME_4E) "5400" + hex(serial, 4) + "0000" else "5000"
                (header + asciiRoute() + hex(body.length, 4) + body).toByteArray(Charsets.US_ASCII)
            }
        }
        return ModuleBufferReadRequest(address.wordAddress, wordCount, serial, identity, bytes)
    }

    /** Reject truncated, extra, misrouted and wrong-serial replies before returning any values. */
    fun decodeRead(request: ModuleBufferReadRequest, response: ByteArray): IntArray {
        if (request.owner !== identity) throw FrameException("Module response belongs to another codec")
        return when (dataMode) {
            DataMode.BINARY -> decodeBinary(request, response)
            DataMode.ASCII -> decodeAscii(request, response)
        }
    }

    private fun decodeBinary(request: ModuleBufferReadRequest, bytes: ByteArray): IntArray {
        val four = frameType == FrameType.FRAME_4E
        val prefix = if (four) 6 else 2
        val endCodeOffset = prefix + 7
        if (bytes.size < endCodeOffset + 2) throw FrameException("Module response is truncated")
        if (u8(bytes[0]) != (if (four) 0xD4 else 0xD0) || bytes[1] != 0.toByte()) {
            throw FrameException("Module response has an invalid subheader")
        }
        if (four && (u16(bytes, 2) != request.serial || u16(bytes, 4) != 0)) {
            throw FrameException("Module response serial or reserved field does not match")
        }
        if (!bytes.copyOfRange(prefix, prefix + 5).contentEquals(route.toBytes())) {
            throw FrameException("Module response route does not match")
        }
        if (u16(bytes, prefix + 5) != bytes.size - endCodeOffset) {
            throw FrameException("Module response declared length does not match")
        }
        val code = u16(bytes, endCodeOffset)
        val start = endCodeOffset + 2
        if (code != 0) throw McProtocolException.plcError(code, bytes.copyOfRange(start, bytes.size))
        if (bytes.size - start != request.wordCount * 2) {
            throw FrameException("Module response word count does not match")
        }
        return IntArray(request.wordCount) { u16(bytes, start + it * 2) }
    }

    private fun decodeAscii(request: ModuleBufferReadRequest, bytes: ByteArray): IntArray {
        val four = frameType == FrameType.FRAME_4E
        val prefix = if (four) 12 else 4
        val endCodeOffset = prefix + 14
        if (bytes.size < endCodeOffset + 4 || bytes.any { u8(it) > 127 }) {
            throw FrameException("Module ASCII response is truncated or not ASCII")
        }
        val text = bytes.toString(Charsets.US_ASCII)
        if (text.take(4) != (if (four) "D400" else "D000")) {
            throw FrameException("Module ASCII response has an invalid subheader")
        }
        fun field(start: Int, length: Int): Int {
            val part = text.substring(start, start + length)
            if (part.any { it !in '0'..'9' && it !in 'A'..'F' && it !in 'a'..'f' }) {
                throw FrameException("Module ASCII response contains a non-hexadecimal field")
            }
            return part.toInt(16)
        }
        if (four && (field(4, 4) != request.serial || field(8, 4) != 0)) {
            throw FrameException("Module ASCII response serial or reserved field does not match")
        }
        if (!text.substring(prefix, prefix + 10).equals(asciiRoute(), ignoreCase = true)) {
            throw FrameException("Module ASCII response route does not match")
        }
        if (field(prefix + 10, 4) != bytes.size - endCodeOffset) {
            throw FrameException("Module ASCII response declared length does not match")
        }
        val code = field(endCodeOffset, 4)
        val start = endCodeOffset + 4
        if (code != 0) throw McProtocolException.plcError(code, bytes.copyOfRange(start, bytes.size))
        if (bytes.size - start != request.wordCount * 4) {
            throw FrameException("Module ASCII response word count does not match")
        }
        return IntArray(request.wordCount) { field(start + it * 4, 4) }
    }

    private fun asciiRoute() = hex(route.networkNo, 2) + hex(route.pcNo, 2) +
        hex(route.moduleIoNo, 4) + hex(route.moduleStationNo, 2)
    private fun hex(value: Int, width: Int) = value.toString(16).uppercase(Locale.ROOT).padStart(width, '0')
    private fun u8(value: Byte) = value.toInt() and 0xFF
    private fun u16(bytes: ByteArray, offset: Int) = u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8)
    private fun ByteArrayOutputStream.le(value: Long, width: Int) {
        repeat(width) { write(((value ushr (it * 8)) and 0xFF).toInt()) }
    }
}
