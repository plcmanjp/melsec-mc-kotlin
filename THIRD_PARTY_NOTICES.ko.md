# 외부 구성요소

[English](THIRD_PARTY_NOTICES.md) | **한국어** | [README](README.ko.md)

자체 통신 코어와 예제는 [Apache-2.0](LICENSE)으로 제공합니다. 외부 소프트웨어에는 각 프로젝트의 라이선스가 적용됩니다.

| 구성요소 | 버전 | 사용 범위 | 라이선스와 원본 |
|---|---|---|---|
| Gradle Wrapper | 9.1.0 | 저장소에 포함된 빌드 실행기 | [Gradle 소스](https://github.com/gradle/gradle/tree/v9.1.0), [배포 라이선스](licenses/gradle-LICENSE) |
| Kotlin | 2.2.10 | 컴파일 플러그인, 표준 라이브러리, 테스트 지원 | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/v2.2.10/license/LICENSE.txt) |
| kotlinx.coroutines | 1.8.1 | 런타임과 테스트 | [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/1.8.1/LICENSE.txt) |
| JUnit 5 | 5.10.2 | 테스트 전용 | [EPL-2.0](https://github.com/junit-team/junit5/blob/r5.10.2/LICENSE.md) |
| JetBrains annotations | 23.0.0 | Kotlin과 코루틴의 전이 의존성 | [Apache-2.0](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt) |

Gradle Wrapper의 원본 저작권 헤더를 보존합니다. Gradle 배포본은 저장소에 포함하지 않으며 공식 배포 주소에서 체크섬을 확인해 받습니다. CLI 배포물의 의존 JAR와 그 안의 라이선스/고지는 변경하지 않습니다. 제조사 매뉴얼과 제조사 코드, 로고는 포함하지 않습니다.
