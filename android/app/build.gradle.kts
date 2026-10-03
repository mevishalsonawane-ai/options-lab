import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import com.android.build.api.artifact.SingleArtifact
import java.security.MessageDigest
import java.time.Duration

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    jacoco
}

android {
    namespace = "com.optionslab.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iraalgo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // The bundled 170-session record is hashed at build time; the app
        // re-hashes it at runtime, so a swapped or truncated chain file is
        // caught before any backtest reads it.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(file("src/main/assets/expiry_nifty.olx").readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        buildConfigField("String", "EXPIRY_SHA256", "\"$digest\"")

        // The permission allowlist, so the running app can check itself too.
        buildConfigField("String", "ALLOWED_PERMISSIONS", "\"${allowedPermissions(jarvis = true).joinToString(",")}\"")

        // The commit the build was made from (CI sets GITHUB_SHA): every build is "1.0.0", so this tells them apart.
        buildConfigField("String", "COMMIT", "\"${(System.getenv("GITHUB_SHA") ?: "local").take(7)}\"")

        manifestPlaceholders["appLabel"] = "@string/app_name"
        // Jarvis, the assistant (voice, chat, on-device model): in IraAlgo and IraGoldAlgo (where it only talks).
        // -PtestsWithoutJarvis=true (CI's app tests): the screens and jobs as they are with Jarvis off, as those tests expect.
        buildConfigField("boolean", "JARVIS", (project.findProperty("testsWithoutJarvis") != "true").toString())
    }

    // Two apps from one project: IraAlgo (NSE options, Zerodha, paper) and IraGoldAlgo (XAUUSD only, paper and alerts,
    // no broker). Each has its own package, name, colours and logo; `assembleRelease` builds both.
    flavorDimensions += "brand"
    productFlavors {
        create("ira") {
            dimension = "brand"
            isDefault = true
            buildConfigField("boolean", "GOLD", "false")
        }
        create("gold") {
            dimension = "brand"
            applicationId = "com.iragoldalgo.app"
            buildConfigField("boolean", "GOLD", "true")
        }
    }

    // Jarvis's on-device model runner (llama.cpp, fixed release) for 64-bit ARM phones.
    // -PnoLlm=true (the x86_64 emulator check): built without it, so the APK installs there; the app then simply has no model.
    if (project.findProperty("noLlm") != "true") {
        externalNativeBuild { cmake { path = file("src/jarvis/cpp/CMakeLists.txt"); version = "3.22.1" } }
        defaultConfig { ndk { abiFilters += "arm64-v8a" } }
    }

    // The microphone, the voice service and the widget: Jarvis's manifest, in both apps.
    sourceSets {
        getByName("ira").manifest.srcFile("src/jarvis/AndroidManifest.xml")
        getByName("gold").manifest.srcFile("src/jarvis/AndroidManifest.xml")
    }

    signingConfigs {
        // CI signs with a keystore held in repository secrets; without one the
        // release build falls back to the debug key so it still installs.
        create("release") {
            val path = System.getenv("OL_KEYSTORE_PATH")
            if (path != null && file(path).exists()) {
                storeFile = file(path)
                storePassword = System.getenv("OL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OL_KEY_ALIAS")
                keyPassword = System.getenv("OL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            // JaCoCo for the JVM/Robolectric tests: :app:createDebugUnitTestCoverageReport.
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The .olx chains are already gzip; recompressing them wastes build time.
        noCompress += "olx"
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        // JVM unit tests (no device): plain JUnit 4 for pure logic, Robolectric for anything that
        // needs Android (the vault, settings, backups) and for Compose screens. See src/test/README.md.
        unitTests {
            // Robolectric reads the merged debug manifest and resources (Compose screens need them).
            isIncludeAndroidResources = true
            // Pure JVM tests: an Android call that is not under Robolectric answers a default.
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "4g"
                // Two test JVMs at once (the CI runner has 4 cores, 16 GB), each replaced every few classes so
                // one area's static state cannot leak into - or stall - another's; and a hard limit, so a hang
                // fails the build in minutes instead of holding CI for hours.
                // CI runs the suite in shards (-PtestShard=i/n, one job each): a class - with its inner classes -
                // belongs to shard hash(name) % n. Without the property every class runs, as before.
                (project.findProperty("testShard") as String?)?.split("/")?.map { v -> v.trim().toInt() }?.let { (i, n) ->
                    it.exclude(org.gradle.api.specs.Spec<org.gradle.api.file.FileTreeElement> { e ->
                        !e.isDirectory && e.name.endsWith(".class") &&
                            (e.relativePath.pathString.substringBefore('$').removeSuffix(".class").hashCode() and 0x7fffffff) % n != i
                    })
                }
                it.maxParallelForks = 2
                // A fresh JVM every 4 classes: Robolectric's text shadows never free their native objects and the
                // screen matrices draw thousands of screens, so a long-lived JVM fills its heap (one ran out of
                // memory and failed every later class in it).
                it.setForkEvery(4)
                it.timeout.set(Duration.ofMinutes(20))   // imported: inside android {} "java" is the compile-options block
                it.systemProperty("robolectric.logging.enabled", "false")
                // Screen tests write their screenshots (build/outputs/roborazzi); nothing is compared or committed.
                it.systemProperty("roborazzi.test.record", "true")
                it.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
    }
}

dependencies {
    // Ira, the on-device trading assistant (plain Kotlin: the brain, patterns, learning, news, answers).
    implementation(project(":ira-core"))
    implementation(project(":engine"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // The Kite live price stream (WebSocket). No logging interceptor: nothing about the connection is ever logged.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // SSH for the built-in static-IP relay (pure Java, no native code, no permissions).
    implementation("com.github.mwiede:jsch:0.2.18")
    // ---- tests (src/test: JVM + Robolectric + Compose UI; nothing here reaches the APK) ----
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Registers the empty ComponentActivity createComposeRule() launches. Debug variant only:
    // the release manifest (and its sandbox report) is unchanged.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Screenshots of every tested screen on the JVM (Robolectric native graphics).
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.36.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.36.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.36.0")
    // A fake Zerodha (Kite Connect) server on localhost for the live-order paths; same version as okhttp.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // WorkManager in tests (synchronous executor, TestListenableWorkerBuilder); same version as work-runtime.
    testImplementation("androidx.work:work-testing:2.10.0")
}


// Robolectric loads the app's classes through its own class loader, which gives them no code
// location: JaCoCo must be told to count those too, or the coverage report reads ~0%.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}


// ---- The sandbox guard --------------------------------------------------------
//
// The app must never be able to read messages, mail, contacts, files, photos or
// another app's data. The manifest removes those permissions explicitly; this
// makes it a BUILD FAILURE for the final merged manifest - after every library
// has contributed its own - to carry any permission outside the allowlist, to
// ask to see other installed apps (<queries>), or to bind an accessibility or
// notification-listener service (the two ways an app can read other apps' screens
// and notifications).

fun allowedPermissions(jarvis: Boolean = false): List<String> =
    (listOf("permissions-allowlist.txt") + if (jarvis) listOf("permissions-allowlist-jarvis.txt") else emptyList())
        .flatMap { file(it).readLines() }
        .map { it.substringBefore("#").trim() }
        .filter { it.isNotEmpty() }

abstract class CheckSandbox : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val allowed: ListProperty<String>

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    /** A <queries> block that only asks for text-to-speech engines (Jarvis's voice) shows no other app: allowed. */
    private fun queriesOnlyTts(xml: String): Boolean = Regex("<queries\\b[^>]*>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL).findAll(xml).all { q ->
        q.groupValues[1].replace(Regex("<intent>\\s*<action\\s+android:name=\"android\\.intent\\.action\\.TTS_SERVICE\"\\s*/>\\s*</intent>"), "").isBlank()
    } && !Regex("<queries\\b[^>]*/>").containsMatchIn(xml)

    @TaskAction
    fun check() {
        val xml = manifest.get().asFile.readText()
        val held = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*?android:name="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toSortedSet()
        val ok = allowed.get().map { it.replace("\${applicationId}", applicationId.get()) }.toSet()
        val problems = ArrayList<String>()
        (held - ok).forEach { problems += "permission not on the allowlist: $it" }
        if (Regex("<queries\\b").containsMatchIn(xml) && !queriesOnlyTts(xml)) problems += "<queries> present: the app could see other installed apps"
        for (bind in listOf("BIND_ACCESSIBILITY_SERVICE", "BIND_NOTIFICATION_LISTENER_SERVICE", "BIND_DEVICE_ADMIN")) {
            if (xml.contains("android.permission.$bind")) problems += "declares a $bind component"
        }
        report.get().asFile.writeText(
            "held:\n" + held.joinToString("\n") { "  $it" } + "\n" +
                (if (problems.isEmpty()) "sandbox: OK\n" else problems.joinToString("\n", "VIOLATIONS:\n") + "\n"))
        if (problems.isNotEmpty()) throw GradleException("Sandbox guard failed:\n  " + problems.joinToString("\n  "))
        logger.lifecycle("Sandbox guard: ${held.size} permissions, all on the allowlist; no <queries>.")
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val guard = tasks.register<CheckSandbox>("check${cap}Sandbox") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowed.set(allowedPermissions(jarvis = true))
            applicationId.set(variant.applicationId)
            report.set(layout.buildDirectory.file("reports/sandbox/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" }.configureEach { dependsOn(guard) }
    }
}
