# melsec-mc-kotlin

**English** | [한국어](README.ko.md)

A Kotlin/JVM library for MC Protocol 3E/4E communication with Mitsubishi MELSEC PLCs. It provides TCP/UDP transport and Binary/ASCII encoding for coroutine-based JVM applications, with no Android SDK required.

[![CI](https://github.com/plcmanjp/melsec-mc-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/plcmanjp/melsec-mc-kotlin/actions/workflows/ci.yml)

This is an independent implementation, not an official or certified Mitsubishi Electric product. The current version is `0.1.0`. APIs may change during the 0.x series.

## Modules and supported features

| Module | Purpose |
|---|---|
| `melsec-mc` | Device address parsing, frame encoding/decoding, TCP/UDP clients |
| `melsec-cli` | 3E Binary TCP/UDP examples for reads and explicitly enabled writes |

The core implements:

- All four combinations of 3E/4E frames and Binary/ASCII data, with TCP/UDP transport
- Batch word/bit reads and writes for ordinary devices, plus CPU model read requests
- 4E response decoding with request serial validation
- Partial TCP reads, timeouts, socket cleanup on cancellation, and TCP connection retries
- Module buffer reads through the separate `ModuleBufferCodec`

Ordinary device and CPU model ASCII requests support only the default `AccessRoute` and `monitoringTimer` (`0010`). Nondefault settings are rejected with `FrameException` before a frame is produced. Binary requests and the separate `ModuleBufferCodec` use the supplied route and timer.

An enum entry does not mean that a complete MC/SLMP command API is implemented. Random access, label access, remote RUN/STOP, 1E/5E frames, and serial communication APIs are not provided. The ordinary device encoder uses Q/L device fields and rejects iQ-R-only long devices. Check the PLC settings and manuals for the address ranges supported by each CPU and port.

## Build and test

Install JDK 17 and set `JAVA_HOME`. The included official Gradle Wrapper downloads Gradle 9.1.0 and verifies the distribution SHA-256. The first build needs access to the Gradle and Maven repositories. No Android SDK, private configuration file, or separate Gradle installation is required.

```bash
git clone https://github.com/plcmanjp/melsec-mc-kotlin.git
cd melsec-mc-kotlin
./gradlew build test :melsec-cli:installDist
```

On Windows PowerShell:

```powershell
.\gradlew.bat build test :melsec-cli:installDist
```

Core and sources JARs are written to `melsec-mc/build/libs/`. The CLI is installed in `melsec-cli/build/install/melsec-cli/`. Automated tests use frame fixtures and local sockets; they do not require a physical PLC.

If test classes fail to load on Windows, try a fresh checkout in a short ASCII-only path outside a synchronized folder. Local verification failed to load test classes in a long Korean OneDrive path, while the same sources passed in an ASCII temporary path. The cause has not been isolated to a particular character or path property.

## Use from another JVM project

The library is not currently published to a Maven repository. You can pin a reviewed Git commit or tag and use that source checkout through a Gradle composite build. Add this to the consuming project's `settings.gradle.kts`:

```kotlin
includeBuild("../melsec-mc-kotlin")
```

Configure Kotlin/JVM and Maven Central in the consuming project, then add this dependency to `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.plciocheck:melsec-mc:0.1.0")
}
```

The composite build substitutes this coordinate with the local project. It is not a coordinate you can download from Maven Central without `includeBuild`.

## Kotlin read example

This function reads five words starting at D100 over TCP 3E Binary. Supply the actual PLC address and its configured MC port.

```kotlin
import com.plciocheck.melsecmc.device.DeviceParser
import com.plciocheck.melsecmc.frame.McFrameDecoder
import com.plciocheck.melsecmc.frame.McFrameEncoder
import com.plciocheck.melsecmc.transport.ConnectionConfig
import com.plciocheck.melsecmc.transport.McSocketClient

suspend fun readD100(ip: String, port: Int): IntArray {
    val encoder = McFrameEncoder()
    val decoder = McFrameDecoder()
    val request = encoder.encodeBatchReadWord(DeviceParser.parse("D100"), 5)
    val config = ConnectionConfig(ip = ip, port = port, maxRetry = 0)
    return McSocketClient(config).use { client ->
        client.connect()
        decoder.decodeResponse(client.sendAndReceive(request)).words
    }
}
```

When changing the frame or data mode, keep `ConnectionConfig`, `McFrameEncoder`, and the decoder call consistent. For 4E, capture `encoder.lastSerialNo` immediately after encoding a request and pass it to `decodeResponse(bytes, frameType, dataMode, expectedSerial)` or the matching bit decoder. Finish decoding the response before encoding the next request.

For UDP, use `McDatagramClient(config, localPort = ...)`. Set `localPort` only when the PLC requires a fixed response destination port. A completed UDP `connect()` does not confirm that the PLC has responded.

## CLI

The CLI supports 3E Binary only. `--ip` accepts a numeric IPv4 or IPv6 address, not a hostname. Use the core API above for 4E/ASCII. The address `192.0.2.10` below is reserved for documentation and must be replaced with your device address. CLI help and diagnostics currently use Korean.

```bash
# Display help without connecting to a PLC.
./melsec-cli/build/install/melsec-cli/bin/melsec-cli --help

# Read five words starting at D100.
./melsec-cli/build/install/melsec-cli/bin/melsec-cli --ip 192.0.2.10 --port 1025 read --device D100 --count 5

# Read eight bits starting at M100 over UDP.
./melsec-cli/build/install/melsec-cli/bin/melsec-cli --ip 192.0.2.10 --port 1025 --udp read --device M100 --count 8 --bit
```

On Windows, run `melsec-cli.bat` from the same directory. Writes require explicit permission, for example `write --device D100 --values 100 --allow-write`. This flag does not verify that the equipment is in a safe state. Invalid options and values are rejected before connecting.

## Connection and write contracts

- Do not run overlapping requests on one client/encoder pair. Coroutines can overlap even on one thread, so the caller must serialize the entire operation from request encoding through response decoding. A 4E serial number does not provide concurrent request support.
- `maxRetry` and `retryBackoffMs` apply only to TCP connection attempts. UDP operations and commands themselves are not automatically retransmitted.
- After a write is sent, a timeout or connection loss can leave its outcome unknown. Do not interpret a failure as proof that the write did not execute, or retry writes unconditionally.
- `close()` ends the lifetime of that client instance. Create a new instance for further use. Propagate cancellation and manage the socket lifetime explicitly.
- For TCP, callers must limit `timeoutMs` and `connectTimeoutMs` to `1..Int.MAX_VALUE` milliseconds. These settings are typed as `Long` but are converted to `Int` for Java sockets; larger values are unsupported.
- The core `encodeBatchWriteWord` serializes only the low 16 bits of each `Int`. For example, `65536` becomes `0`, and `-1` becomes `0xFFFF`. Callers must validate unsigned values in `0..65535` or an intentional signed 16-bit representation. The CLI restricts word input to `0..65535`.
- `timeoutMs` limits response waiting. It does not guarantee an overall deadline covering DNS lookup, connection retries, and backoff.
- The decoder checks the frame and EndCode, but callers must perform application-level checks such as the expected ordinary word count. PLC lifecycle, access permissions, model-specific address limits, and permission to write remain the caller's responsibility.
- This library does not add TLS or user authentication to MC communication. Use it on an access-controlled equipment network. Diagnostic sinks may contain frame values and connection details; keep them out of public logs.

## Addresses and PLC-specific limits

`DeviceParser` accepts addresses such as `D100`, `M100`, and `X1F`. Devices such as `X/Y/B/W` are parsed as hexadecimal according to `DeviceCode.isHexNotation`. FX octal X/Y input is not detected automatically. Convert the address to an integer when needed and specify `AsciiXyAddressFormat` for its ASCII wire representation.

Do not access `G` as an ordinary device. Use `ModuleBufferAddress` and `ModuleBufferCodec`. Module buffer access is read-only and distinguishes Q, IQ_R, and FX5 wire profiles. FX5 module access currently permits only 3E ASCII and rejects Binary and 4E. The ordinary device encoder limits differ from the module buffer wide address format.

## Verification scope

CI builds and tests the core and CLI on Windows/Linux with JDK 17. Earlier application integration work tested communication with Q06UDVCPU, QJ71E71-100, R04CPU, and RJ71EN71. Those are historical integration results, not physical certification of every command, PLC model, or firmware version in this independent distribution.

The public extraction was verified without connecting to a physical PLC. Start with reads on new equipment, and verify the specific model, port, frame, and commands before use.

## References and license

Refer to Mitsubishi Electric's official manuals for the protocol specification. The manuals are not redistributed in this repository.

- [MELSEC Communication Protocol Reference Manual, SH-080008-AB](https://dl.mitsubishielectric.com/dl/fa/document/manual/plc/sh080008/sh080008ab.pdf)
- SLMP Reference Manual, SH-080956ENG: search by document number in the [manufacturer downloads](https://www.mitsubishielectric.com/fa/download/index.html).
- The FX5 module buffer format references JY997D56001K Appendix 8. Check the manual and revision for your CPU before applying it.

Original code and documentation are provided under [Apache-2.0](LICENSE). See [NOTICE](NOTICE) for attribution and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for external components. Commercial use, modification, and redistribution are permitted under the license terms. See [CONTRIBUTING.md](CONTRIBUTING.md) for contributions and issue reports.
