// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package com.plciocheck.melsecmc.cli

import com.plciocheck.melsecmc.ConnectionException
import com.plciocheck.melsecmc.FrameException
import com.plciocheck.melsecmc.McProtocolException
import com.plciocheck.melsecmc.ResponseTimeoutException
import com.plciocheck.melsecmc.device.DeviceCode
import com.plciocheck.melsecmc.device.DeviceParser
import com.plciocheck.melsecmc.device.DeviceRef
import com.plciocheck.melsecmc.frame.EndCode
import com.plciocheck.melsecmc.frame.McFrameDecoder
import com.plciocheck.melsecmc.frame.McFrameEncoder
import com.plciocheck.melsecmc.transport.ConnectionConfig
import com.plciocheck.melsecmc.transport.McDatagramClient
import com.plciocheck.melsecmc.transport.McSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val status = runBlocking { runCli(args) }
    if (status != 0) exitProcess(status)
}

data class CliArgs(
    val ip: String,
    val port: Int,
    val command: String,
    val device: DeviceRef,
    val count: Int,
    val bit: Boolean,
    val values: IntArray = IntArray(0),
    val udp: Boolean = false,
    val localPort: Int? = null,
    val allowWrite: Boolean = false,
)

/** 모든 인수 오류는 IllegalArgumentException으로 반환하며 네트워크를 사용하지 않습니다. */
fun parseArgs(args: Array<String>): CliArgs {
    val options = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    var command: String? = null
    val valueOptions = setOf("--ip", "--port", "--device", "--count", "--values", "--localport")
    val flagOptions = setOf("--bit", "--udp", "--allow-write")
    var i = 0
    while (i < args.size) {
        val token = args[i++]
        when {
            token == "read" || token == "write" -> {
                require(command == null) { "read 또는 write 명령을 하나만 지정하세요." }
                command = token
            }
            token in valueOptions -> {
                require(token !in options) { "$token 옵션은 한 번만 지정하세요." }
                val value = args.getOrNull(i)
                require(value != null && value.isNotBlank() && !value.startsWith("--")) {
                    "$token 옵션의 값을 지정하세요."
                }
                options[token] = value
                i++
            }
            token in flagOptions -> require(flags.add(token)) { "$token 옵션은 한 번만 지정하세요." }
            else -> throw IllegalArgumentException("알 수 없는 인수 '$token'입니다. --help를 확인하세요.")
        }
    }
    fun required(key: String): String = requireNotNull(options[key]) { "$key 옵션을 지정하세요." }
    fun integer(key: String, default: Int): Int = options[key]?.let {
        requireNotNull(it.toIntOrNull()) { "$key 옵션에는 정수를 지정하세요." }
    } ?: default

    val selectedCommand = requireNotNull(command) { "read 또는 write 명령을 지정하세요." }
    val ip = required("--ip")
    val deviceText = required("--device")
    val device = try {
        DeviceParser.parse(deviceText)
    } catch (_: McProtocolException) {
        throw IllegalArgumentException("디바이스 주소 '$deviceText'를 확인하세요.")
    }
    // 부호를 허용하는 코어 파서와 달리 CLI 주소는 정규 표기만 받습니다.
    val addressDigits = deviceText.trim().substring(device.code.name.length)
    require(addressDigits.matches(if (device.isHexNotation) Regex("[0-9a-fA-F]+") else Regex("[0-9]+"))) {
        "디바이스 주소 '$deviceText'를 확인하세요."
    }
    val bit = "--bit" in flags
    val values = if (selectedCommand == "write") {
        required("--values").split(',').mapIndexed { index, token ->
            val text = token.trim()
            val hex = text.startsWith("0x", ignoreCase = true)
            val digits = if (hex) text.substring(2) else text
            require(digits.matches(if (hex) Regex("[0-9a-fA-F]+") else Regex("[0-9]+"))) {
                "--values의 ${index + 1}번째 값에는 10진수 또는 0x 16진수를 지정하세요."
            }
            requireNotNull(digits.toIntOrNull(if (hex) 16 else 10)) {
                "--values의 ${index + 1}번째 값이 허용 범위를 초과합니다."
            }
        }.toIntArray()
    } else IntArray(0)
    require(selectedCommand != "read" || "--values" !in options) { "--values는 write에만 지정하세요." }
    require(selectedCommand != "read" || "--allow-write" !in flags) { "--allow-write는 write에만 지정하세요." }
    require(selectedCommand != "write" || "--count" !in options) {
        "--count는 read에만 지정하세요. write 점수는 --values 개수로 결정합니다."
    }
    return CliArgs(
        ip, integer("--port", 1025), selectedCommand, device, integer("--count", 1), bit,
        values, "--udp" in flags, options["--localport"]?.let { integer("--localport", 0) },
        "--allow-write" in flags,
    ).also(::validateArgs)
}

private fun isLiteralIp(ip: String): Boolean {
    if (':' in ip) {
        // 콜론과 숫자 주소 문자만 허용하므로 호스트 이름의 DNS 조회는 발생하지 않습니다.
        if (!ip.matches(Regex("[0-9a-fA-F:.]+"))) return false
        return try { InetAddress.getByName(ip); true } catch (_: java.net.UnknownHostException) { false }
    }
    val parts = ip.split('.')
    return parts.size == 4 && parts.all {
        it.matches(Regex("0|[1-9][0-9]{0,2}")) && (it.toIntOrNull() ?: 256) in 0..255
    }
}

/** 직접 생성한 CliArgs도 프레임 생성과 소켓 생성 전에 검증합니다. */
internal fun validateArgs(args: CliArgs) {
    require(args.command == "read" || args.command == "write") { "read 또는 write 명령을 지정하세요." }
    require(isLiteralIp(args.ip)) { "--ip에는 유효한 IPv4 또는 IPv6 숫자 주소를 지정하세요." }
    require(args.port in 1..65535) { "--port는 1..65535 범위로 지정하세요." }
    require(args.localPort == null || args.udp) { "--localport는 --udp와 함께 지정하세요." }
    require(args.localPort == null || args.localPort in 1..65535) { "--localport는 1..65535 범위로 지정하세요." }
    require(args.device.code.isQlAccessible && args.device.code != DeviceCode.G) {
        "${args.device.code.name} 디바이스는 이 CLI의 Q/L 디바이스 접근 경로에서 지원하지 않습니다."
    }
    require(!args.bit || args.device.isBit) { "--bit에는 비트 디바이스를 지정하세요." }
    if (args.command == "write") {
        require(args.allowWrite) { "write는 PLC 값을 변경합니다. 실행하려면 --allow-write를 명시하세요." }
        require(args.count == 1) { "write 점수는 --values 개수로 결정합니다. --count는 read에만 지정하세요." }
        require(args.values.isNotEmpty()) { "write에는 --values를 지정하세요." }
        require(args.values.all { if (args.bit) it in 0..1 else it in 0..65535 }) {
            if (args.bit) "비트 쓰기 값은 0 또는 1로 지정하세요." else "워드 쓰기 값은 0..65535 범위로 지정하세요."
        }
    } else {
        require(args.values.isEmpty() && !args.allowWrite) { "read에는 쓰기 옵션을 지정하지 마세요." }
    }
    val points = if (args.command == "write") args.values.size else args.count
    val limit = if (args.bit) 3584 else 960
    require(points in 1..limit) { "${if (args.bit) "비트" else "워드"} 점수는 1..$limit 범위로 지정하세요." }
    val span = if (!args.bit && args.device.isBit) points.toLong() * 16 else points.toLong()
    require(args.device.number >= 0 && args.device.number.toLong() + span - 1 <= 0xFFFFFF) {
        "요청 주소 전체를 24비트 주소 범위 0..16777215 안에 지정하세요."
    }
}

internal fun buildTxFrame(args: CliArgs, encoder: McFrameEncoder = McFrameEncoder()): ByteArray {
    validateArgs(args)
    return when (args.command) {
        "read" -> if (args.bit) encoder.encodeBatchReadBit(args.device, args.count)
            else encoder.encodeBatchReadWord(args.device, args.count)
        else -> if (args.bit) encoder.encodeBatchWriteBit(args.device, args.values)
            else encoder.encodeBatchWriteWord(args.device, args.values)
    }
}

/** 교환 함수 주입으로 오프라인에서 네트워크 진입 전 가드를 검증합니다. */
internal suspend fun runCli(
    tokens: Array<String>,
    output: (String) -> Unit = { println(it) },
    errorOutput: (String) -> Unit = { System.err.println(it) },
    exchange: suspend (CliArgs, ByteArray) -> ByteArray = ::exchangeFrame,
): Int {
    if (tokens.isEmpty() || (tokens.size == 1 && tokens[0] in setOf("--help", "-h"))) {
        output(usageText())
        return 0
    }
    val args: CliArgs
    val txFrame: ByteArray
    try {
        args = parseArgs(tokens)
        txFrame = buildTxFrame(args)
    } catch (e: IllegalArgumentException) {
        errorOutput("[ERROR] ${e.message}")
        errorOutput("사용법은 --help를 확인하세요.")
        return 2
    } catch (_: McProtocolException) {
        errorOutput("[ERROR] 요청 프레임을 생성할 수 없습니다. 디바이스와 범위를 확인하세요.")
        return 2
    }
    return try {
        output("[TX] ${txFrame.toHexDump()} (${if (args.udp) "UDP" else "TCP"} ${args.ip}:${args.port})")
        val rxFrame = exchange(args, txFrame)
        output("[RX] ${rxFrame.toHexDump()}")
        formatResponse(args, rxFrame).forEach(output)
        0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        when {
            e is McProtocolException && e.endCode != null -> {
                errorOutput("[END CODE] %04XH (%s)".format(e.endCode, EndCode.classify(e.endCode!!)))
                Regex("errorInfo=([0-9A-F ]+)").find(e.message.orEmpty())?.let {
                    errorOutput("[ERROR INFO] ${it.groupValues[1]}")
                }
                errorOutput("[ERROR] PLC가 오류를 응답했습니다. 설정과 디바이스 범위를 확인하세요.")
            }
            e is ResponseTimeoutException -> errorOutput("[ERROR] PLC 응답 대기 시간이 초과했습니다. 통신 설정을 확인하세요.")
            e is ConnectionException -> errorOutput("[ERROR] PLC 연결 또는 데이터 교환에 실패했습니다. IP와 포트를 확인하세요. (${args.ip}:${args.port})")
            e is McProtocolException -> errorOutput("[ERROR] 통신 또는 응답 검증에 실패했습니다. 설정과 RX 프레임을 확인하세요. (${e.javaClass.simpleName})")
            else -> errorOutput("[ERROR] 명령 실행에 실패했습니다. 통신 설정을 확인하세요. (${e.javaClass.simpleName})")
        }
        if (args.command == "write") errorOutput(writeUncertaintyText())
        1
    }
}

private suspend fun exchangeFrame(args: CliArgs, frame: ByteArray): ByteArray {
    val config = ConnectionConfig(args.ip, args.port, timeoutMs = 4000L, connectTimeoutMs = 4000L, maxRetry = 0)
    return if (args.udp) {
        McDatagramClient(config, localPort = args.localPort).use { client ->
            client.connect()
            client.sendAndReceive(frame)
        }
    } else {
        McSocketClient(config).use { client ->
            client.connect()
            client.sendAndReceive(frame)
        }
    }
}

internal fun formatResponse(args: CliArgs, frame: ByteArray): List<String> {
    validateArgs(args)
    if (frame.size < 11) throw FrameException("응답 헤더가 부족합니다.")
    val declaredLength = (frame[7].toInt() and 0xFF) or ((frame[8].toInt() and 0xFF) shl 8)
    if (frame.size != 9 + declaredLength) throw FrameException("응답의 선언 길이와 실제 길이가 다릅니다.")
    val decoder = McFrameDecoder()
    val parsed: String
    if (args.command == "read" && args.bit) {
        val bits = decoder.decodeBitReadResponse(frame, args.count)
        if (frame.size != 11 + (args.count + 1) / 2) throw FrameException("비트 응답 점수가 요청과 다릅니다.")
        // 홀수 점수의 마지막 하위 nibble은 요청하지 않은 padding이므로 검사하거나 표시하지 않습니다.
        for (index in bits.indices) {
            val packed = frame[11 + index / 2].toInt() and 0xFF
            val nibble = if (index % 2 == 0) (packed ushr 4) and 0x0F else packed and 0x0F
            if (nibble !in 0..1) throw FrameException("비트 응답 값은 0 또는 1이어야 합니다.")
        }
        parsed = "[BIT] " + bits.mapIndexed { index, value ->
            "${addressAt(args, index)}=${if (value) 1 else 0}"
        }.joinToString(" ")
    } else {
        val response = decoder.decodeResponse(frame)
        val expectedDataBytes = if (args.command == "read") args.count * 2 else 0
        if (frame.size != 11 + expectedDataBytes) throw FrameException("응답 데이터 길이가 요청과 다릅니다.")
        parsed = if (args.command == "read") "[PARSED] " + response.words.mapIndexed { index, word ->
            "${addressAt(args, index)}=0x%04X(%d)".format(word, word)
        }.joinToString(" ") else "[PARSED] ${args.device}부터 ${args.values.size} ${if (args.bit) "비트" else "워드"} 쓰기가 정상 응답되었습니다."
    }
    return listOf("[END CODE] 0000H (${EndCode.classify(0)})", parsed)
}

private fun addressAt(args: CliArgs, index: Int): String {
    val stride = if (!args.bit && args.device.isBit) 16 else 1
    return args.device.copy(number = args.device.number + index * stride).toString()
}

fun ByteArray.toHexDump(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

internal fun writeUncertaintyText(): String =
    "쓰기 요청 후 응답을 확인하지 못하면 PLC에 적용되었을 수 있습니다. 자동 재전송하지 않습니다. 재실행 전에 PLC 상태를 확인하세요."

internal fun usageText(): String = """
melsec-cli: Mitsubishi MELSEC MC Protocol CLI (3E Binary TCP/UDP)

사용법:
  melsec-cli --ip <IP> [--port <PORT>] [--udp] read --device <DEVICE> [--count <N>] [--bit]
  melsec-cli --ip <IP> [--port <PORT>] [--udp] write --device <DEVICE> --values <v1,v2,...> --allow-write [--bit]

옵션:
  --help, -h       단독으로 지정하면 도움말을 표시합니다.
  --ip <IP>       PLC의 IPv4 또는 IPv6 숫자 주소를 지정하세요.
  --port <PORT>   1..65535 포트를 지정하세요. 기본값은 1025입니다. PLC 설정과 맞추세요.
  --udp           UDP를 사용합니다. 기본값은 TCP입니다.
  --localport <N> --udp와 함께 1..65535 로컬 소스 포트를 지정하세요. 생략하면 자동 할당합니다.
  --device <DEV>  D0, M100, X1F 등 Q/L 코드 디바이스 주소를 지정하세요. G와 iQ-R 전용 코드는 지원하지 않습니다.
  --count <N>     read 점수를 지정하세요. 기본값은 1입니다. 워드는 최대 960, 비트는 최대 3584입니다.
  --bit           비트 디바이스를 비트 단위로 접근합니다. 생략하면 워드 단위입니다.
  --values <list> write 값은 쉼표로 구분하세요. 워드는 0..65535, 비트는 0 또는 1입니다. 10진수와 0x 16진수를 지원합니다.
  --allow-write   PLC 값 변경을 허용합니다. write 실행 시 반드시 명시하세요.

주소와 쓰기:
  X/Y/B/W/SB/SW/DX/DY/ZR 주소는 16진수, 다른 주소는 10진수로 입력하고 표시합니다.
  비트 디바이스를 워드 단위로 접근하면 한 워드가 인접 16개 비트를 포함합니다.
  write는 설비 동작에 영향을 줄 수 있습니다. 대상 주소와 쓰기 값을 확인하세요.
  ${writeUncertaintyText()}
  지원 범위는 3E Binary입니다. 4E와 ASCII 옵션은 지원하지 않습니다.

읽기 예제 (예약 예제 주소이므로 실제 PLC 주소와 포트로 바꾸세요):
  melsec-cli --ip 192.0.2.10 --port 1025 read --device D0 --count 10
  melsec-cli --ip 192.0.2.10 --port 1025 read --device M100 --count 16 --bit
  melsec-cli --ip 192.0.2.10 --port 1025 --udp read --device X1F --count 3 --bit

쓰기 예제 (승인된 시험 주소와 값으로 바꾸세요):
  melsec-cli --ip 192.0.2.10 --port 1025 write --device D0 --values 100,200,300 --allow-write

출력:
  [TX], [RX]: 요청과 응답 프레임을 16진수로 표시합니다.
  [END CODE]: PLC 완료 코드를 표시합니다.
  [PARSED]: 워드 값 또는 쓰기 정상 응답을 표시합니다.
  [BIT]: 코어 디코더가 해석한 비트 값만 표시합니다.
  종료 코드는 성공 또는 도움말 0, 통신 또는 응답 오류 1, 인수 오류 2입니다.
""".trimIndent()

fun printUsage() = println(usageText())
