// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.frame

import melsecmckotlin.FrameException
import melsecmckotlin.device.DeviceCode
import melsecmckotlin.device.DeviceRef
import java.util.concurrent.atomic.AtomicInteger

private const val MAX_BIT_POINTS = 3584

/** ASCII 프레임에서 X/Y HeadDevice 번호를 표현할 진법. */
enum class AsciiXyAddressFormat {
    HEXADECIMAL,
    OCTAL,
}

/**
 * 3E/4E Frame Binary/ASCII 요청 메시지 빌더
 *
 * 일반 ASCII 요청은 기본 [AccessRoute]와 기본 monitoringTimer(0010)만 지원한다.
 * 비기본 설정은 프레임 생성 시 [FrameException]으로 거부한다.
 * Binary 요청과 [ModuleBufferCodec]은 전달한 경로와 타이머를 사용한다.
 *
 * 출처:
 *   - MC 프로토콜 참조 (3E Binary 요청), (3E ASCII 요청), (4E 요청), (ReqLen 계산), (골든 byte)
 *   - MC 프로토콜 참조 (Batch Read Word), (Batch Write Word), (Batch Read Bit)
 *   - MC 프로토콜 참조 (ASCII 디바이스 코드), (ASCII HeadDevice 6-digit)
 *
 * 골든 예시 (MC 프로토콜 참조, 3E Binary):
 *   encodeBatchReadWord(D100, 5) →
 *   50 00 | 00 FF FF 03 00 | 0C 00 | 10 00 | 01 04 | 00 00 | 64 00 00 A8 | 05 00
 *
 * Binary byte order: 전 구간 little-endian (MC 프로토콜 참조)
 *   "send 2-byte numerical value from the lower byte (L: bits 0 to 7)"
 *
 * ASCII 모드 (MC 프로토콜 참조):
 *   전 구간 대문자 hex 문자열. 수치 값을 그대로 hex string (little-endian 아님).
 *   예: Command 0x0401 → "0401" (not "0104")
 *   출력은 ASCII 문자 바이트 배열 (US-ASCII 인코딩).
 *
 * ReqLen 공식 (MC 프로토콜 참조):
 *   Binary: Timer(2) + Command(2) + Subcommand(2) + RequestData(가변) byte 수
 *   ASCII:  Timer(4) + Command(4) + Subcommand(4) + RequestData ASCII char 수
 *   워드 데이터는 워드당 4 char, 비트 쓰기 데이터는 1 char/bit (16 bits = 16 char).
 *   (4E도 동일 — Serial/Fixed는 ReqLen 계산 포함 안 됨)
 *
 * 4E Binary Serial No. 관리 (설계 검토 확정):
 *   AtomicInteger 단조 증가, 0~0xFFFF wrapping, little-endian 2 byte.
 *   순차 폴링(요청→응답 완료 후 다음 요청)이므로 쌍 매칭 충분.
 */
class McFrameEncoder(
    private val accessRoute: AccessRoute = AccessRoute.DEFAULT,
    private val monitoringTimer: ByteArray = FrameConstants.MONITORING_TIMER_DEFAULT,
    private val frameType: FrameType = FrameType.FRAME_3E,
    private val dataMode: DataMode = DataMode.BINARY,
    private val asciiXyAddressFormat: AsciiXyAddressFormat = AsciiXyAddressFormat.HEXADECIMAL,
) {

    /** 4E Serial No. 단조 증가 카운터 (0~0xFFFF wrapping) */
    private val serialCounter = AtomicInteger(0)

    /** 직전 요청에서 사용한 Serial No. — 응답 echo 검증용으로 Decoder에서 사용 */
    @Volatile
    var lastSerialNo: Int = 0
        private set

    /** Shares route, timer and the 4E serial sequence with ordinary requests. */
    fun moduleBufferCodec(profile: ModuleBufferProfile): ModuleBufferCodec {
        require(monitoringTimer.size == 2)
        val timer = (monitoringTimer[0].toInt() and 0xFF) or
            ((monitoringTimer[1].toInt() and 0xFF) shl 8)
        return ModuleBufferCodec(profile, frameType, dataMode, accessRoute, timer).also { codec ->
            codec.nextSerial = { serialCounter.getAndIncrement() and 0xFFFF }
        }
    }

    // ── Batch Read in word units (Command 0401H, Subcommand 0000H) ────────
    /**
     * 워드 단위 일괄 읽기 요청 프레임을 생성한다.
     *
     * 출처: MC 프로토콜 참조 (Q/L Subcommand 0000)
     * 골든 예시(3E Binary): D100 5 word → 50 00 00 FF FF 03 00 0C 00 10 00 01 04 00 00 64 00 00 A8 05 00
     * 4E는 앞에 Subheader(54 00)+Serial(2)+Fixed(00 00) 추가, 이후 구조 동일.
     *
     * Request Data Binary (Q/L, 6 byte):
     *   HeadDevice (3 byte LE) + DeviceCode (1 byte) + Count (2 byte LE)
     *
     * Request Data ASCII (Q/L, 12 char):
     *   DeviceCode 2char + HeadDevice 6-digit + Count 4char
     *
     * [device] 읽기 시작 디바이스 (D100 등)
     * [count]  읽기 점수 (Q/L: 1~960 word, MC 프로토콜 참조)
     */
    fun encodeBatchReadWord(device: DeviceRef, count: Int): ByteArray {
        require(count in 1..960) { "Word count must be 1..960, got $count" }
        requireQlAccessible(device)

        return if (dataMode == DataMode.ASCII) {
            val requestDataAscii = buildWordRequestDataAscii(device, count)
            buildFrameAsciiForType(McCommand.BatchRead, Subcommand.WordUnitsQL, requestDataAscii)
        } else {
            val requestData = buildWordRequestData(device, count)
            buildFrameForType(McCommand.BatchRead, Subcommand.WordUnitsQL, requestData)
        }
    }

    // ── Batch Read in bit units (Command 0401H, Subcommand 0001H) ─────────
    /**
     * 비트 단위 일괄 읽기 요청 프레임을 생성한다.
     *
     * 출처: MC 프로토콜 참조 (공식 매뉴얼)
     * 골든 예시: M100 8 bit →
     *   ... 01 04 | 01 00 | 64 00 00 90 | 08 00
     *   (Command-Count 구간만 골든 — 전체 프레임은 Access Route 포함)
     *
     * Request Data (Q/L, 6 byte Binary / 12 char ASCII):
     *   Binary: HeadDevice (3 byte LE) + DeviceCode (1 byte) + Count (2 byte LE, 비트 수)
     *   ASCII:  DeviceCode 2char + HeadDevice 6-digit + Count 4char
     *
     * [device] 읽기 시작 비트 디바이스 (M100 등)
     * [count]  읽기 비트 수 (1~960×16 = 1~15360 bits, 실용 상한 960)
     */
    fun encodeBatchReadBit(device: DeviceRef, count: Int): ByteArray {
        require(count in 1..MAX_BIT_POINTS) { "count must be in 1..$MAX_BIT_POINTS" }
        requireQlAccessible(device)

        return if (dataMode == DataMode.ASCII) {
            val requestDataAscii = buildWordRequestDataAscii(device, count)
            buildFrameAsciiForType(McCommand.BatchRead, Subcommand.BitUnitsQL, requestDataAscii)
        } else {
            val requestData = buildWordRequestData(device, count) // 구조 동일, Count만 비트 수
            buildFrameForType(McCommand.BatchRead, Subcommand.BitUnitsQL, requestData)
        }
    }

    // ── Batch Write in word units (Command 1401H, Subcommand 0000H) ───────
    /**
     * 워드 단위 일괄 쓰기 요청 프레임을 생성한다.
     *
     * 출처: MC 프로토콜 참조 (Q/L Subcommand 0000)
     * 골든 예시: M100 2 word → ... 01 14 | 00 00 | 64 00 00 90 | 02 00 | 47 23 96 AB
     *   (MC 프로토콜 참조 공식 매뉴얼 정합)
     *
     * Request Data Binary (Q/L):
     *   HeadDevice (3 byte LE) + DeviceCode (1 byte) + Count (2 byte LE) + WriteData (count×2 byte LE)
     *
     * Request Data ASCII (Q/L):
     *   DeviceCode 2char + HeadDevice 6-digit + Count 4char + WriteData (count×4char)
     *
     * 비트 디바이스를 워드 단위로 쓰기(Subcommand 0000): Count = 워드 수, 비트 16개 = 1 word.
     *
     * [device] 쓰기 시작 디바이스
     * [values] 쓸 워드 값 배열. 각 Int의 하위 16비트만 직렬화하며 범위를 검사하지 않는다.
     * 호출자는 unsigned 0..65535 또는 의도한 signed 16비트 표현인지 검증해야 한다.
     * 예: 65536은 0, -1은 0xFFFF로 전송된다. Binary는 2 byte LE, ASCII는 4 char다.
     */
    fun encodeBatchWriteWord(device: DeviceRef, values: IntArray): ByteArray {
        require(values.isNotEmpty()) { "values must not be empty" }
        require(values.size in 1..960) { "Word count must be 1..960, got ${values.size}" }
        requireQlAccessible(device)

        return if (dataMode == DataMode.ASCII) {
            // ASCII Request Data = DevCode(2) + HeadDevice(6) + Count(4) + WriteData(count×4)
            // MC ASCII 규격: 디바이스 코드 먼저(Binary와 순서 반대, 실 PLC 0xC050 검출).
            val sb = StringBuilder()
            sb.append(device.asciiCode)
            sb.append(formatAsciiHeadDevice(device))
            sb.append(values.size.toAscii4Hex())
            for (word in values) sb.append(word.toAscii4Hex())
            buildFrameAsciiForType(McCommand.BatchWrite, Subcommand.WordUnitsQL, sb.toString())
        } else {
            // Binary Request Data = HeadDev(3) + DevCd(1) + Count(2) + WriteData(n×2)
            val requestData = ByteArray(6 + values.size * 2)
            var pos = 0

            // HeadDevice 3 byte LE (MC 프로토콜 참조)
            requestData[pos++] = (device.number and 0xFF).toByte()
            requestData[pos++] = ((device.number shr 8) and 0xFF).toByte()
            requestData[pos++] = ((device.number shr 16) and 0xFF).toByte()

            // DeviceCode 1 byte (Q/L, MC 프로토콜 참조)
            requestData[pos++] = device.qlCodeByte

            // Count 2 byte LE
            requestData[pos++] = (values.size and 0xFF).toByte()
            requestData[pos++] = ((values.size shr 8) and 0xFF).toByte()

            // WriteData: 각 워드 2 byte LE (MC 프로토콜 참조)
            for (word in values) {
                requestData[pos++] = (word and 0xFF).toByte()
                requestData[pos++] = ((word shr 8) and 0xFF).toByte()
            }

            buildFrameForType(McCommand.BatchWrite, Subcommand.WordUnitsQL, requestData)
        }
    }

    // ── Batch Write in bit units (Command 1401H, Subcommand 0001H) ────────
    /**
     * 비트 디바이스를 한 점 단위로 쓴다.
     *
     * Binary write data는 선행 점을 상위 nibble에 넣는다. 예를 들어
     * `M8000=ON` 한 점은 `10H`, `OFF` 한 점은 `00H`다. 이 경로는 비트
     * 디바이스만 허용하므로 워드 단위 write처럼 인접 16점을 덮어쓰지 않는다.
     * ASCII 쓰기 데이터는 1 char/bit이며 16 bits는 16 char로 직렬화한다.
     */
    fun encodeBatchWriteBit(device: DeviceRef, values: IntArray): ByteArray {
        require(values.isNotEmpty()) { "values must not be empty" }
        require(values.size in 1..MAX_BIT_POINTS) {
            "Bit count must be 1..$MAX_BIT_POINTS, got ${values.size}"
        }
        require(device.code.isBit) { "Bit-unit write requires a bit device, got ${device.code.name}" }
        require(values.all { it == 0 || it == 1 }) { "Bit values must be 0 or 1" }
        requireQlAccessible(device)

        return if (dataMode == DataMode.ASCII) {
            val requestData = buildString {
                append(device.asciiCode)
                append(formatAsciiHeadDevice(device))
                append(values.size.toAscii4Hex())
                values.forEach { append(it) }
            }
            buildFrameAsciiForType(McCommand.BatchWrite, Subcommand.BitUnitsQL, requestData)
        } else {
            val packed = ByteArray((values.size + 1) / 2)
            values.forEachIndexed { index, value ->
                val shift = if (index % 2 == 0) 4 else 0
                packed[index / 2] = (packed[index / 2].toInt() or (value shl shift)).toByte()
            }
            val requestData = buildWordRequestData(device, values.size) + packed
            buildFrameForType(McCommand.BatchWrite, Subcommand.BitUnitsQL, requestData)
        }
    }

    // ── CPU Model Read (Command 0101H) ───────────────────────────────────
    /**
     * 0101H CPU 모델명 읽기 요청 프레임을 4종 모드(3E/4E × Binary/ASCII)로 생성한다.
     *
     * 0101H 명령의 RequestData는 없다 (Timer + Cmd + Subcmd 이후 데이터 없음).
     *   Binary RequestData: ByteArray(0) (0 byte)
     *   ASCII RequestData:  "" (0 char)
     *
     * Binary 3E 프레임은 기존 CpuModelReader.REQUEST_FRAME 고정 상수와 바이트 동일하다.
     *   ReqLen = Timer(2) + Cmd(2) + Subcmd(2) + 0 = 6 → 06 00 LE (기존 상수 확인)
     *
     * ASCII 3E 응답 데이터부 형식 (실측 확정 2026-06-12):
     *   raw ASCII 모델명 16 char + 기종코드 4 hex char (대문자) = 총 20 char
     *   예: "Q06UDVCPU       0368"
     *
     * lastSerialNo 갱신: 4E 모드에서 직전 요청 Serial을 echo 검증에 사용하도록 갱신.
     */
    fun encodeCpuModelRead(): ByteArray {
        return if (dataMode == DataMode.ASCII) {
            buildFrameAsciiForType(McCommand.CpuModelRead, Subcommand.WordUnitsQL, "")
        } else {
            buildFrameForType(McCommand.CpuModelRead, Subcommand.WordUnitsQL, ByteArray(0))
        }
    }

    // ── 디바이스 접근성 가드 (P4/P5, 회귀 검증) ──────────────────────

    /**
     * iQ-R 전용 디바이스(qlCode=-1: LTN·LCN·LSTN·LTS·LTC·LSTS·LSTC·LCS·LCC·LZ·RD)를
     * Q/L Subcommand 0000/0001 Batch Read/Write 경로로 인코딩하기 전에 거부한다.
     *
     * 대칭성: Binary 경로는 기존에 [DeviceRef.qlCodeByte]가 늦게(요청 데이터 조립 중) 거부했으나
     * ASCII 경로는 [DeviceCode.asciiCode]가 빈 문자열("")을 그대로 이어붙여 필드가 밀리는
     * 결함이 있었다(P4). 이 가드를 encode 진입점에 두어 Binary/ASCII 양쪽을 동일하게,
     * 그리고 요청 데이터 조립 이전에 막는다.
     *
     * 앱은 DeviceType 8종(D/W/SD/SW/M/X/Y/B 등 Q/L 표준 디바이스)만 노출하므로
     * 이 가드에 도달하지 않는다 — melsec-cli처럼 임의 디바이스 문자열을 직접
     * 조립할 수 있는 경로에서만 발현하는 latent 방어다.
     *
     * @throws FrameException iQ-R 전용 디바이스일 때
     */
    private fun requireQlAccessible(device: DeviceRef) {
        if (device.code == DeviceCode.G) throw FrameException("G requires a qualified module buffer read")
        if (!device.code.isQlAccessible) {
            throw FrameException(
                "Device ${device.code.name} is iQ-R exclusive (Subcommand 0002 required); " +
                    "not accessible via Q/L Subcommand 0000/0001 Batch Read/Write"
            )
        }
        // HeadDevice 는 Binary 에서 3-byte(24-bit) 필드로 직렬화된다. 상한(0xFFFFFF) 초과 시
        // 상위 비트가 조용히 잘려 *다른* 디바이스를 정상 값으로 오인 읽는 silent 결함이 된다
        // (예: D16777216 → 전선상 D0). PLC 는 잘린 in-range 주소를 정상 응답하므로 EndCode 로도
        // 걸러지지 않는다. 상한 초과는 명시적 예외로 전환한다(실 디바이스는 전부 이 범위 내 —
        // 최대 ZR 도 약 4.18M 로 0xFFFFFF 미만).
        if (device.number !in 0..0xFFFFFF) {
            throw FrameException(
                "HeadDevice number ${device.number} out of range for ${device.code.name} " +
                    "(max 0xFFFFFF / 16,777,215 — 24-bit HeadDevice field)"
            )
        }
    }

    // ── Binary 프레임 타입 분기 진입점 ──────────────────────────────────

    /**
     * Binary 모드에서 frameType에 따라 3E/4E 프레임을 빌드한다.
     *
     * 이 함수는 Binary 경로(3E Binary / 4E Binary) 전용이다.
     * ASCII 경로는 [buildFrameAsciiForType]을 사용한다.
     *
     * 3E Binary: [buildFrame3E] (기존 경로 완전 보존)
     * 4E Binary: [buildFrame4E] (Serial 삽입)
     */
    private fun buildFrameForType(
        command: McCommand,
        subcommand: Subcommand,
        requestData: ByteArray
    ): ByteArray = when (frameType) {
        FrameType.FRAME_3E -> buildFrame3E(command, subcommand, requestData)
        FrameType.FRAME_4E -> buildFrame4E(command, subcommand, requestData)
    }

    /**
     * ASCII 모드에서 frameType에 따라 3E/4E ASCII 프레임을 빌드한다.
     *
     * 3E ASCII: [buildFrame3EAsciiFromString] (기존 경로 보존)
     * 4E ASCII: [buildFrame4EAscii] (Subheader "5400" + Serial + Fixed "0000" + 3E ASCII 본문)
     */
    private fun buildFrameAsciiForType(
        command: McCommand,
        subcommand: Subcommand,
        requestDataAscii: String
    ): ByteArray = when (frameType) {
        FrameType.FRAME_3E -> buildFrame3EAsciiFromString(command, subcommand, requestDataAscii)
        FrameType.FRAME_4E -> buildFrame4EAscii(command, subcommand, requestDataAscii)
    }

    // ── 3E Binary 프레임 빌더 (골든 기준, 불변) ───────────────────────────

    /**
     * 3E Binary 요청 메시지 공통 헤더 + RequestData 조립
     *
     * 구성 (MC 프로토콜 참조):
     *   Subheader(2) + AccessRoute(5) + ReqLen(2) + Timer(2) + Cmd(2) + Subcmd(2) + RequestData
     *
     * ReqLen = Timer(2) + Cmd(2) + Subcmd(2) + requestData.size  (MC 프로토콜 참조)
     *
     * 이 함수는 3E Binary 골든 바이트 기준점이다. 수정 금지.
     */
    private fun buildFrame3E(
        command: McCommand,
        subcommand: Subcommand,
        requestData: ByteArray
    ): ByteArray {
        val cmdBytes = command.toBytes()
        val subcmdBytes = subcommand.toBytes()

        // ReqLen = Timer(2) + Cmd(2) + Subcmd(2) + RequestData bytes (MC 프로토콜 참조)
        val reqLen = monitoringTimer.size + cmdBytes.size + subcmdBytes.size + requestData.size
        val reqLenBytes = byteArrayOf(
            (reqLen and 0xFF).toByte(),
            ((reqLen shr 8) and 0xFF).toByte()
        )

        val routeBytes = accessRoute.toBytes()

        return FrameConstants.SUBHEADER_3E_REQUEST +
            routeBytes +
            reqLenBytes +
            monitoringTimer +
            cmdBytes +
            subcmdBytes +
            requestData
    }

    // ── 4E Binary 프레임 빌더 ─────────────────────────────────────────────

    /**
     * 4E Binary 요청 메시지 조립 (MC 프로토콜 참조, 공식 매뉴얼)
     *
     * 구성:
     *   Subheader(2: 54H 00H) + Serial No.(2 LE) + Fixed(2: 00H 00H)
     *   + AccessRoute(5) + ReqLen(2) + Timer(2) + Cmd(2) + Subcmd(2) + RequestData
     *
     * Serial No.:
     *   - AtomicInteger 단조 증가 (0~0xFFFF wrapping)
     *   - little-endian 2 byte 직렬화
     *   - lastSerialNo에 저장 → 응답 echo 검증 시 사용
     *
     * ReqLen = Timer(2)+Cmd(2)+Subcmd(2)+RequestData 바이트 수
     *   (Serial/Fixed는 ReqLen 계산 범위 밖 — MC 프로토콜 참조 구조 기준)
     */
    private fun buildFrame4E(
        command: McCommand,
        subcommand: Subcommand,
        requestData: ByteArray
    ): ByteArray {
        val cmdBytes = command.toBytes()
        val subcmdBytes = subcommand.toBytes()

        // Serial No. 증가 (0~0xFFFF wrapping)
        val serial = serialCounter.getAndIncrement() and 0xFFFF
        lastSerialNo = serial
        val serialBytes = byteArrayOf(
            (serial and 0xFF).toByte(),
            ((serial shr 8) and 0xFF).toByte()
        )

        // ReqLen = Timer(2) + Cmd(2) + Subcmd(2) + RequestData bytes (MC 프로토콜 참조)
        val reqLen = monitoringTimer.size + cmdBytes.size + subcmdBytes.size + requestData.size
        val reqLenBytes = byteArrayOf(
            (reqLen and 0xFF).toByte(),
            ((reqLen shr 8) and 0xFF).toByte()
        )

        val routeBytes = accessRoute.toBytes()

        return FrameConstants.SUBHEADER_4E_REQUEST +
            serialBytes +
            FrameConstants.FIXED_4E_BYTES +
            routeBytes +
            reqLenBytes +
            monitoringTimer +
            cmdBytes +
            subcmdBytes +
            requestData
    }

    // ── 4E ASCII 프레임 빌더 ─────────────────────────────────────────────

    /**
     * 4E ASCII 요청 메시지 조립 (MC 프로토콜 참조, 프레임 검증)
     *
     * 구성:
     *   Subheader(4):  "5400"  (FrameConstants.SUBHEADER_4E_REQUEST_ASCII)
     *   Serial No.(4): 4 char hex 대문자 (serialCounter 공유 — P2와 동일 AtomicInteger)
     *   Fixed(4):      "0000"  (FrameConstants.FIXED_4E_ASCII)
     *   ASCII 본문(Subheader 제외): Route(10) + ReqDataLen(4) + Timer(4) + Cmd(4) + Subcmd(4) + RequestData
     *
     * ⚠ 3E ASCII '전체 프레임'(buildFrame3EAsciiFromString)을 재사용하면 "5000" 서브헤더가
     *   4E 헤더 뒤에 중복 삽입돼 실 PLC가 Route 를 오독·무응답한다(실 PLC만 검출, **회귀 검증**).
     *   4E Binary(buildFrame4E)가 route 부터 직접 조립하듯, ASCII도 본문만([buildAsciiBodyString])을 붙인다.
     *
     * Serial No. ASCII: (serialCounter.getAndIncrement() and 0xFFFF).toAscii4Hex() — 대문자 4 char
     * lastSerialNo는 Binary와 공유하는 동일 필드에 저장한다.
     *
     * 출처: MC 프로토콜 참조 + 실 PLC 골든(Q06UDVCPU 192.0.2.10, 2026-06-15):
     *   0101H → "54001236000000FF03FF00000C001001010000" → RX "D4001236000000FF03FF0000180000Q06UDVCPU       0368"
     */
    private fun buildFrame4EAscii(
        command: McCommand,
        subcommand: Subcommand,
        requestDataAscii: String
    ): ByteArray {
        // Serial No. 증가 (0~0xFFFF wrapping) — Binary buildFrame4E와 동일 카운터 공유
        val serial = serialCounter.getAndIncrement() and 0xFFFF
        lastSerialNo = serial
        val serialStr = serial.toAscii4Hex()  // 4 char hex 대문자

        // 4E ASCII = Subheader "5400" + Serial(4) + Fixed "0000" + ASCII 본문(Subheader 제외)
        val frame = FrameConstants.SUBHEADER_4E_REQUEST_ASCII +
            serialStr +
            FrameConstants.FIXED_4E_ASCII +
            buildAsciiBodyString(command, subcommand, requestDataAscii)
        return frame.toByteArray(Charsets.US_ASCII)
    }

    // ── 3E ASCII 프레임 빌더 ──────────────────────────────────────────────

    /**
     * 3E ASCII 프레임 빌더 — ASCII RequestData 문자열을 직접 받는 내부 핵심 빌더.
     *
     * encodeBatchReadWord/Bit/WriteWord의 ASCII 경로에서
     * ASCII RequestData 문자열을 생성한 뒤 이 함수에 전달한다.
     *
     * 3E ASCII = Subheader "5000" + ASCII 본문([buildAsciiBodyString]).
     *
     * [requestDataAscii] ASCII hex 문자열 (대문자 필수)
     */
    internal fun buildFrame3EAsciiFromString(
        command: McCommand,
        subcommand: Subcommand,
        requestDataAscii: String
    ): ByteArray {
        val frame = FrameConstants.SUBHEADER_3E_REQUEST_ASCII +      // "5000"
            buildAsciiBodyString(command, subcommand, requestDataAscii)
        return frame.toByteArray(Charsets.US_ASCII)
    }

    /**
     * ASCII 요청 본문(Subheader 제외) 생성 — 3E/4E ASCII 공용.
     *
     * 구성 (MC 프로토콜 참조,, 설계 검토):
     *   Access Route(10): "00FF03FF00"
     *     Network No.(2) + PC No.(2) + Module I/O No.(4) + Module Sta. No.(2)
     *     ※ ASCII는 값 그대로 hex 표기 (Module I/O No. 0x03FF → "03FF").
     *   ReqDataLen(4): Timer(4)+Cmd(4)+Subcmd(4)+RequestData char 수 (설계 검토)
     *   Timer(4) "0010" / Command(4) / Subcommand(4) / RequestData
     *
     * 서브헤더("5000"/"5400")는 호출측(buildFrame3EAsciiFromString/buildFrame4EAscii)이 앞에 붙인다.
     * ⚠ 4E ASCII는 이 본문만 붙여야 한다 — 3E '전체 프레임'을 재사용하면 "5000" 중복(**회귀 검증**).
     */
    private fun buildAsciiBodyString(
        command: McCommand,
        subcommand: Subcommand,
        requestDataAscii: String
    ): String {
        // 일반 ASCII 본문은 기본 route/timer만 직렬화하므로 다른 설정을 조용히 무시하지 않습니다.
        // ModuleBufferCodec은 자체 직렬화 경로로 전달된 설정을 사용합니다.
        if (accessRoute != AccessRoute.DEFAULT ||
            !monitoringTimer.contentEquals(byteArrayOf(0x10, 0x00))
        ) {
            throw FrameException("일반 ASCII 프레임은 기본 AccessRoute와 monitoringTimer만 지원합니다.")
        }
        val route = "00FF03FF00"                                      // Access Route 10 char (MC 프로토콜 참조)
        val timerStr = "0010"                                         // Monitoring Timer 4 char
        val cmdStr = command.toAscii4()                               // Command 4 char
        val subcmdStr = subcommand.toAscii4()                         // Subcommand 4 char
        val reqDataLen = timerStr.length + cmdStr.length + subcmdStr.length + requestDataAscii.length
        val reqDataLenStr = reqDataLen.toAscii4Hex()                  // 4 char hex, 대문자
        return route + reqDataLenStr + timerStr + cmdStr + subcmdStr + requestDataAscii
    }

    /**
     * ASCII 모드 Batch Read/Bit Read Request Data 문자열 생성 (12 char)
     *
     * 필드 순서(MC ASCII 규격, 회귀 검증 실 PLC 확정 — Binary [번호][코드]와 반대):
     *   DeviceCode 2char (MC 프로토콜 참조) +
     *   HeadDevice 6-digit (MC 프로토콜 참조: 10/16진법 분기 포함) +
     *   Count 4char hex
     *
     * 기본 Q/L은 [DeviceRef.toAsciiAddress6Digit] 표기를 유지한다. FX로 자동 식별된
     * 세션은 X/Y만 8진 문자열로 바꾸고 B/W 등 다른 16진 디바이스는 그대로 둔다.
     */
    private fun buildWordRequestDataAscii(device: DeviceRef, count: Int): String {
        // DeviceCode 2char + HeadDevice 6-digit + Count 4char
        // MC ASCII 규격: 디바이스 코드가 먼저(Binary [번호][코드]와 순서 반대).
        // 실 PLC 검증: [번호][코드] 순서는 EndCode 0xC050(AsciiError)로 거부됨. [코드][번호]로 EndCode 0000 정상.
        return device.asciiCode +
            formatAsciiHeadDevice(device) +
            count.toAscii4Hex()
    }

    private fun formatAsciiHeadDevice(device: DeviceRef): String {
        if (asciiXyAddressFormat != AsciiXyAddressFormat.OCTAL ||
            (device.code != DeviceCode.X && device.code != DeviceCode.Y)
        ) {
            return device.toAsciiAddress6Digit()
        }
        val digits = device.number.toString(8)
        if (digits.length > 6) {
            throw FrameException(
                "HeadDevice ${device.code.name}${device.number} exceeds 6-digit FX ASCII octal field " +
                    "(got '$digits')"
            )
        }
        return digits.padStart(6, '0')
    }

    /**
     * Batch Read/Bit Read 공통 Request Data 조립 (8 byte)
     *
     * HeadDevice (3 byte LE) + DeviceCode (1 byte) + Count (2 byte LE)
     *
     * MC 프로토콜 참조, 공통 구조
     */
    private fun buildWordRequestData(device: DeviceRef, count: Int): ByteArray {
        val data = ByteArray(6)
        var pos = 0

        // HeadDevice 3 byte LE (MC 프로토콜 참조: Q/L 3 byte little-endian)
        data[pos++] = (device.number and 0xFF).toByte()
        data[pos++] = ((device.number shr 8) and 0xFF).toByte()
        data[pos++] = ((device.number shr 16) and 0xFF).toByte()

        // DeviceCode 1 byte Q/L (MC 프로토콜 참조)
        data[pos++] = device.qlCodeByte

        // Count 2 byte LE
        data[pos++] = (count and 0xFF).toByte()
        data[pos] = ((count shr 8) and 0xFF).toByte()

        return data
    }
}

/** ByteArray + 연산자 (Kotlin 표준 없으므로 로컬 정의) */
private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val result = ByteArray(this.size + other.size)
    this.copyInto(result, 0)
    other.copyInto(result, this.size)
    return result
}

// ── ASCII 헬퍼 (McFrameEncoder 전용) ─────────────────────────────────────

/**
 * McCommand 값을 4자리 대문자 hex 문자열로 변환.
 * 예: BatchRead(0x0401) → "0401"
 * ASCII 모드는 값 그대로 표기 (Binary LE와 달리 리틀엔디안 아님).
 */
private fun McCommand.toAscii4(): String = "%04X".format(code)

/**
 * Subcommand 값을 4자리 대문자 hex 문자열로 변환.
 * 예: WordUnitsQL(0x0000) → "0000", BitUnitsQL(0x0001) → "0001"
 */
private fun Subcommand.toAscii4(): String = "%04X".format(code)

/**
 * 정수 값을 4자리 대문자 hex 문자열로 변환 (0~0xFFFF 범위).
 * 예: 24 → "0018", 5 → "0005", 0x1234 → "1234"
 */
private fun Int.toAscii4Hex(): String = "%04X".format(this and 0xFFFF)
