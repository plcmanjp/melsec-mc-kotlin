plugins {
    kotlin("jvm") version "2.2.10" apply false
}

allprojects {
    group = "com.plciocheck"
    version = "0.1.0"
}

subprojects {
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(rootProject.layout.projectDirectory) {
            include("LICENSE", "NOTICE")
            into("META-INF")
        }
    }
}
