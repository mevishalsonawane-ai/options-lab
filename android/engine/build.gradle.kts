plugins {
    kotlin("jvm")
    jacoco
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The engine tests reproduce the PC ledger from the same bundled data the
    // app ships, so they read it from the app's assets rather than a copy.
    systemProperty("olx.assets", rootProject.file("app/src/main/assets").absolutePath)
    maxHeapSize = "2g"
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    finalizedBy(tasks.jacocoTestReport)
}

jacoco { toolVersion = "0.8.12" }

// Line and branch coverage of the engine: build/reports/jacoco/test/html/index.html (and XML for CI).
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required.set(true); html.required.set(true) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
