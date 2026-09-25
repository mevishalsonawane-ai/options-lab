pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "IraAlgo"

// The engine is plain Kotlin/JVM: it builds and tests anywhere a JDK does, so
// the strategy can be verified against the PC's numbers without an Android SDK.
include(":engine")

// The app needs the Android SDK. Include it whenever one is configured, and
// never silently: a machine without one says so instead of failing to resolve.
val sdkConfigured = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true
// Read by the root buildscript, which decides whether to load the Android plugin.
System.setProperty("optionslab.android", sdkConfigured.toString())
if (sdkConfigured) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK configured: building :engine only.")
}
