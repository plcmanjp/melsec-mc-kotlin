// Copyright 2026 plcmanjp
// SPDX-License-Identifier: Apache-2.0

package melsecmckotlin.transport

/**
 * 순수 모듈용 진단 sink.
 *
 * 기본값 null = 무출력·무오버헤드(release/test 무영향).
 * 앱 측에서 debug 빌드에서만 sink를 주입하고, release는 미주입으로 둔다.
 *
 * 사용 예 (앱 측):
 * ```kotlin
 * if (BuildConfig.DEBUG) {
 *     PlcDiag.sink = { tag, msg -> android.util.Log.d(tag, msg) }
 * }
 * ```
 *
 * 순수 Kotlin 모듈이므로 android.util.Log·BuildConfig 직접 참조 불가.
 * sink 주입이 없으면 [log] 호출 시 람다 본문 자체가 평가되지 않는다(inline 최적화).
 */
object PlcDiag {

    /**
     * 로그 출력 함수.
     * null(기본) 시 log 호출 자체가 무시된다 — 문자열 연산 비용 없음.
     */
    @Volatile
    var sink: ((tag: String, msg: String) -> Unit)? = null

    /**
     * [msg] 람다를 평가하기 전에 sink null 여부를 확인한다.
     * sink가 null이면 람다 본문이 실행되지 않으므로 hex 변환 등 연산 비용이 없다.
     */
    inline fun log(tag: String, msg: () -> String) {
        sink?.let { it(tag, msg()) }
    }

    /**
     * [b]의 앞 min([n], b.size) 바이트를 "%02X" 공백 구분 hex 문자열로 반환한다.
     *
     * @param b   덤프할 바이트 배열
     * @param n   최대 덤프 바이트 수 (기본 32)
     * @return    예: "D0 00 FF 01 0E 00 ..."
     */
    fun hex(b: ByteArray, n: Int = 32): String {
        val limit = minOf(n, b.size)
        if (limit == 0) return "(empty)"
        return (0 until limit).joinToString(" ") { "%02X".format(b[it].toInt() and 0xFF)  }
    }
}
