# melsec-mc-kotlin

[English](README.md) | **한국어**

Mitsubishi MELSEC PLC의 MC Protocol 3E/4E 프레임을 다루는 Kotlin/JVM 라이브러리입니다. TCP/UDP 전송과 Binary/ASCII 인코딩을 제공합니다. Android SDK 없이 빌드하며, 코루틴 기반 JVM 프로그램에서 사용할 수 있습니다.

[![CI](https://github.com/plcmanjp/melsec-mc-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/plcmanjp/melsec-mc-kotlin/actions/workflows/ci.yml)

이 프로젝트는 Mitsubishi Electric의 공식 제품이나 인증 라이브러리가 아닌 독립 구현입니다. 현재 버전은 `0.1.0`이며 0.x 버전에서 API가 변경될 수 있습니다.

## 구성과 지원 범위

| 모듈 | 내용 |
|---|---|
| `melsec-mc` | 디바이스 주소 파싱, 프레임 인코더/디코더, TCP/UDP 클라이언트 |
| `melsec-cli` | 3E Binary TCP/UDP 읽기와 명시적으로 허용한 쓰기 예제 |

코어는 다음 기능을 구현합니다.

- 3E/4E와 Binary/ASCII의 네 가지 프레임 조합, TCP/UDP 전송
- 일반 디바이스 워드/비트 일괄 읽기와 쓰기, CPU 모델 읽기 요청
- 요청 serial 검증을 사용하는 4E 응답 디코딩
- 부분 TCP 수신, 제한 시간, 취소 시 소켓 정리, TCP 연결 재시도
- 별도 `ModuleBufferCodec`의 모듈 버퍼 읽기

일반 디바이스와 CPU 모델의 ASCII 요청은 기본 `AccessRoute`와 기본 `monitoringTimer`(`0010`)만 지원합니다. 비기본 설정은 프레임 생성 전에 `FrameException`으로 거부합니다. Binary 요청과 별도 `ModuleBufferCodec`은 전달한 경로와 타이머를 사용합니다.

명령 enum에 값이 존재한다고 모든 MC/SLMP 명령을 지원하는 것은 아닙니다. Random access, label access, remote RUN/STOP, 1E/5E 및 시리얼 통신 API는 제공하지 않습니다. 일반 디바이스 인코더는 Q/L 형식의 디바이스 필드를 사용하며 iQ-R 전용 long 디바이스를 거부합니다. 각 CPU와 포트에서의 실제 접근 가능 범위는 PLC 설정과 매뉴얼로 확인하세요.

## 빌드와 테스트

JDK 17을 설치하고 `JAVA_HOME`을 지정하세요. Gradle 9.1.0은 포함된 공식 Wrapper가 받으며 배포본 SHA-256을 검증합니다. 최초 빌드는 Gradle/Maven 저장소에 연결합니다. Android SDK, 개인 설정 파일, 별도 Gradle 설치는 필요하지 않습니다.

```bash
git clone https://github.com/plcmanjp/melsec-mc-kotlin.git
cd melsec-mc-kotlin
./gradlew build test :melsec-cli:installDist
```

Windows PowerShell에서는 다음 명령을 사용하세요.

```powershell
.\gradlew.bat build test :melsec-cli:installDist
```

코어 JAR와 소스 JAR는 `melsec-mc/build/libs/`에, CLI는 `melsec-cli/build/install/melsec-cli/`에 생성됩니다. 자동 테스트는 프레임 fixture와 로컬 소켓을 사용하며 실제 PLC가 필요하지 않습니다.

Windows에서 테스트 클래스 로딩 오류가 발생하면 동기화 폴더 밖의 짧은 영문 경로에 새로 체크아웃해 실행하세요. 로컬 검증에서는 긴 한글 OneDrive 경로에서 클래스 로딩이 실패했고, 같은 소스의 영문 임시 경로에서는 통과했습니다. 원인을 특정 문자 하나로 단정하지 않습니다.

## 다른 JVM 프로젝트에서 사용

현재 Maven 저장소에 배포하지 않습니다. 검증한 Git 커밋 또는 태그를 고정한 소스 체크아웃을 Gradle composite build로 연결할 수 있습니다. 소비 프로젝트의 `settings.gradle.kts`에 추가하세요.

```kotlin
includeBuild("../melsec-mc-kotlin")
```

소비 프로젝트는 Kotlin/JVM과 Maven Central을 설정하고 `build.gradle.kts`에 다음 의존성을 추가하세요.

```kotlin
dependencies {
    implementation("com.plciocheck:melsec-mc:0.1.0")
}
```

이 좌표는 composite build가 로컬 프로젝트로 대체합니다. `includeBuild` 없이 Maven Central에서 받을 수 있는 좌표가 아닙니다.

## Kotlin 읽기 예제

다음 함수는 TCP 3E Binary로 D100부터 5워드를 읽습니다. 호출자가 실제 PLC 주소와 설정된 MC 포트를 전달합니다.

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

프레임과 데이터 모드를 바꿀 때는 `ConnectionConfig`, `McFrameEncoder`, 디코더 호출의 모드를 동일하게 설정하세요. 4E는 요청을 인코딩한 직후 `encoder.lastSerialNo`를 저장하고, `decodeResponse(bytes, frameType, dataMode, expectedSerial)` 또는 대응 비트 디코더에 전달하세요. 인코딩부터 응답 디코딩까지 끝난 후 다음 요청을 생성하세요.

UDP는 `McDatagramClient(config, localPort = ...)`를 사용합니다. `localPort`는 PLC가 고정된 응답 목적지 포트를 요구할 때만 설정하세요. UDP의 `connect()` 완료는 PLC 응답 확인을 뜻하지 않습니다.

## CLI

CLI는 3E Binary 전용이며 `--ip`에는 IPv4/IPv6 숫자 주소를 받습니다. 호스트 이름은 받지 않습니다. 4E/ASCII는 위 코어 API로 사용하세요. 아래 `192.0.2.10`은 문서용 주소이므로 실제 장비 주소로 바꿔야 합니다. CLI 도움말과 진단 메시지는 현재 한국어로 표시합니다.

```bash
# 도움말은 PLC에 연결하지 않습니다.
./melsec-cli/build/install/melsec-cli/bin/melsec-cli --help

# D100부터 5워드를 읽습니다.
./melsec-cli/build/install/melsec-cli/bin/melsec-cli --ip 192.0.2.10 --port 1025 read --device D100 --count 5

# UDP로 M100부터 8비트를 읽습니다.
./melsec-cli/build/install/melsec-cli/bin/melsec-cli --ip 192.0.2.10 --port 1025 --udp read --device M100 --count 8 --bit
```

Windows에서는 같은 경로의 `melsec-cli.bat`를 실행하세요. 쓰기는 `write --device D100 --values 100 --allow-write`처럼 명시적으로 허용해야 합니다. 이 옵션은 장비가 안전한 상태인지 확인해 주지 않습니다. 잘못된 옵션과 값은 연결 전에 거부합니다.

## 연결과 쓰기 계약

- 하나의 client/encoder 조합에서 동시에 여러 요청을 실행하지 마세요. 스레드 하나에서도 코루틴이 겹칠 수 있으므로 호출자가 요청 인코딩부터 응답 디코딩까지 직렬화해야 합니다. 4E serial은 동시 요청 지원을 의미하지 않습니다.
- `maxRetry`와 `retryBackoffMs`는 TCP 연결 시도에만 적용합니다. UDP와 명령 자체를 자동 재전송하지 않습니다.
- 쓰기 송신 뒤 시간 초과나 연결 끊김이 발생하면 장비에 반영되었는지 알 수 없을 수 있습니다. 실패를 미실행으로 해석하거나 쓰기를 무조건 재전송하지 마세요.
- `close()`는 해당 client 인스턴스의 사용을 끝냅니다. 다시 사용할 때 새 인스턴스를 만드세요. 취소는 전파하고 소켓 수명을 명시적으로 관리하세요.
- TCP의 `timeoutMs`와 `connectTimeoutMs`는 호출자가 `1..Int.MAX_VALUE` 밀리초로 제한해야 합니다. 설정 타입은 `Long`이지만 Java 소켓에 전달할 때 `Int`로 변환하므로 초과 값은 지원하지 않습니다.
- 코어의 `encodeBatchWriteWord`는 각 `Int`의 하위 16비트만 직렬화합니다. 예를 들어 `65536`은 `0`으로, `-1`은 `0xFFFF`로 전송됩니다. 호출자가 unsigned 값 `0..65535` 또는 의도한 signed 16비트 표현인지 확인하세요. CLI는 워드 입력을 `0..65535`로 제한합니다.
- `timeoutMs`는 응답 대기 제한입니다. DNS 조회, 연결 재시도와 backoff를 모두 합친 작업 전체 시간의 보장은 아닙니다.
- 디코더는 프레임과 EndCode를 검사하지만 일반 워드 응답의 예상 점수 등 업무 수준 검증은 호출자가 수행해야 합니다. PLC 장치 수명주기, 접근 권한, 장비별 주소 한도와 쓰기 허용은 호출자 책임입니다.
- MC 통신은 이 라이브러리에서 TLS나 사용자 인증을 추가하지 않습니다. 접근이 통제된 장비 네트워크에서 사용하세요. 진단 sink에는 프레임 값과 접속 정보가 포함될 수 있으므로 공개 로그에 주의하세요.

## 주소와 기종 제한

`DeviceParser`는 `D100`, `M100`, `X1F` 등의 표기를 받습니다. `X/Y/B/W` 등은 `DeviceCode.isHexNotation`에 따라 16진으로 파싱합니다. FX의 8진 X/Y 사용자 표기를 자동 판별하지 않습니다. 필요한 경우 호출자가 주소를 정수로 변환하고 ASCII wire 표기는 `AsciiXyAddressFormat`을 명시하세요.

`G`를 일반 디바이스로 읽거나 쓰지 마세요. `ModuleBufferAddress`와 `ModuleBufferCodec`을 사용합니다. 모듈 버퍼는 읽기 전용이며 Q, IQ_R, FX5 wire profile을 구분합니다. FX5 모듈 접근은 현재 3E ASCII만 허용하고 Binary와 4E를 거부합니다. 일반 디바이스 인코더의 제한과 모듈 버퍼의 wide 주소 형식은 서로 다릅니다.

## 검증 범위

CI는 Windows/Linux JDK 17에서 코어/CLI 테스트와 빌드를 수행합니다. 과거 앱 통합 과정에서는 Q06UDVCPU, QJ71E71-100, R04CPU, RJ71EN71에서 통신 검증을 수행했습니다. 이는 해당 시점의 통합 검증 기록이며 이 독립 배포본의 모든 명령, 모든 기종 또는 모든 펌웨어에 대한 실기 인증이 아닙니다.

이 공개본 분리는 실제 PLC에 접속하지 않고 검증했습니다. 새로운 장비에서 사용하기 전에 읽기부터 시작해 기종, 포트, 프레임과 명령별 동작을 확인하세요.

## 출처와 라이선스

프로토콜의 원문은 Mitsubishi Electric의 공식 매뉴얼을 참조하세요. 매뉴얼을 이 저장소에서 재배포하지 않습니다.

- [MELSEC Communication Protocol Reference Manual, SH-080008-AB](https://dl.mitsubishielectric.com/dl/fa/document/manual/plc/sh080008/sh080008ab.pdf)
- SLMP Reference Manual, SH-080956ENG: [제조사 다운로드](https://www.mitsubishielectric.com/fa/download/index.html)에서 문서 번호로 찾으세요.
- FX5 모듈 버퍼 형식은 JY997D56001K Appendix 8을 참조합니다. 실제 적용 시 해당 CPU의 매뉴얼과 리비전을 확인하세요.

자체 코드와 문서는 [Apache-2.0](LICENSE)입니다. 저작권 고지는 [NOTICE 한국어 안내](NOTICE.ko.md), 외부 구성요소는 [외부 구성요소](THIRD_PARTY_NOTICES.ko.md)를 확인하세요. 상업적 사용과 수정/재배포는 라이선스 조건에 따라 허용됩니다. 기여와 오류 보고는 [기여 안내](CONTRIBUTING.ko.md)를 참조하세요.
