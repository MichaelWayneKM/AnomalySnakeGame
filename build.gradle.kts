plugins {
    kotlin("jvm") version "2.3.20"
    application
    id("org.beryx.jlink") version "3.0.1"
}

group = "com.wkds.firstspringboot"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

jlink {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "AnomalySnake"
    }
}

tasks.test {
    useJUnitPlatform()
}