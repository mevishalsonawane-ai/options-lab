// Ira's core: the market brain, patterns and what they have taught, the news reader and the question answering.
// Plain Kotlin/JVM like :engine - it builds and tests on a bare JDK, and nothing in IraAlgo depends on it yet: Ira is
// built and proven on its own first (its own test app, then the emulator), and joins IraAlgo only when the owner says so.
plugins {
    kotlin("jvm")
    jacoco
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // The Strategy Lab writes Pine strategies and backtests them with the engine's own Pine interpreter.
    implementation(project(":engine"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    finalizedBy(tasks.jacocoTestReport)
}

jacoco { toolVersion = "0.8.12" }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required.set(true); html.required.set(true) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
