plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(17)

    sourceSets {
        main { kotlin.setSrcDirs(listOf("src")) }
        test { kotlin.setSrcDirs(listOf("test")) }
    }
}

java {
    withSourcesJar()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.withType<Jar>().configureEach {
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist/melsec-mc"))
}
