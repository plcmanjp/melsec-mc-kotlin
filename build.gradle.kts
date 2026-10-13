plugins {
    base
    kotlin("jvm") version "2.2.10" apply false
}

allprojects {
    group = "com.plciocheck"
    version = "0.2.0"
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

tasks.register("dist") {
    group = "distribution"
    description = "Builds the library and CLI distributions in dist/."
    dependsOn(":melsec-mc:jar", ":melsec-mc:sourcesJar", ":melsec-cli:installDist", ":melsec-cli:distZip", ":melsec-cli:distTar")
}

tasks.named<Delete>("clean") {
    delete(layout.projectDirectory.dir("dist"))
}
