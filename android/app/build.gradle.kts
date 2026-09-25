import com.android.build.api.artifact.SingleArtifact
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        buildConfigField("String", "ALLOWED_PERMISSIONS", "\"${allowedPermissions().joinToString(",")}\"")
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
}

dependencies {
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

fun allowedPermissions(): List<String> = file("permissions-allowlist.txt").readLines()
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

    @TaskAction
    fun check() {
        val xml = manifest.get().asFile.readText()
        val held = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*?android:name="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toSortedSet()
        val ok = allowed.get().map { it.replace("\${applicationId}", applicationId.get()) }.toSet()
        val problems = ArrayList<String>()
        (held - ok).forEach { problems += "permission not on the allowlist: $it" }
        if (Regex("<queries\\b").containsMatchIn(xml)) problems += "<queries> present: the app could see other installed apps"
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
            allowed.set(allowedPermissions())
            applicationId.set(variant.applicationId)
            report.set(layout.buildDirectory.file("reports/sandbox/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" }.configureEach { dependsOn(guard) }
    }
}
