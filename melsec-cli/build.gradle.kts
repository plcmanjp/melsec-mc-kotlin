plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.plciocheck.melsecmc.cli.MainKt")
}

dependencies {
    implementation(project(":melsec-mc"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

distributions {
    main {
        contents {
            from(rootProject.layout.projectDirectory) {
                include("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")
            }
        }
    }
}
