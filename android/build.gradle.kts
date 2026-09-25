// Plugins live on ONE classpath here, so the Kotlin plugin can see the Android
// plugin. The Android plugin is added only where an Android SDK is configured:
// the engine must stay buildable and testable on a bare JDK.
buildscript {
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
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
        if (System.getProperty("optionslab.android") == "true") {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
