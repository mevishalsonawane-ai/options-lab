package com.optionslab.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.provider.Settings
import com.optionslab.app.BuildConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Checks for an environment in which the app's protections can be bypassed.
 *
 * None of these is proof - root can hide itself - so they are reported
 * honestly as findings, and the owner decides whether a compromised device
 * may open the app at all ("Refuse compromised devices" in the Cabinet).
 * Biometric unlock is always withdrawn on a compromised device: a rooted
 * phone can fake a biometric callback, but not the PIN's key derivation.
 */
object Integrity {
    enum class Severity { OK, NOTICE, DANGER }

    data class Finding(val name: String, val severity: Severity, val detail: String)

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su", "/system/sbin/su",
        "/vendor/bin/su", "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
        "/data/adb/magisk", "/sbin/.magisk", "/cache/.disable_magisk", "/data/adb/ksu", "/data/adb/ap",
    )

    fun rooted(): Boolean =
        SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) } ||
            Build.TAGS?.contains("test-keys") == true

    /** Frida and Xposed-family hooks show up in the process's own memory map. */
    fun hooked(): Boolean {
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrDefault("")
        val lowered = maps.lowercase()
        if (listOf("frida", "xposed", "lsposed", "substrate", "gum-js-loop").any { it in lowered }) return true
        return fridaPortOpen()
    }

    private fun fridaPortOpen(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 27042), 60); true }
    }.getOrDefault(false)

    fun debuggerAttached(): Boolean = Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    fun emulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK built for") ||
            Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
            Build.PRODUCT.contains("sdk_gphone")

    fun debuggableBuild(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** The bundled 170-session record, hashed at build time and re-hashed here. */
    fun assetDigestOk(context: Context): Boolean = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        context.assets.open("expiry_nifty.olx").use { inp ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) } == BuildConfig.EXPIRY_SHA256
    }.getOrDefault(false)

    /** Every permission this installed app actually requests, as the system sees it. */
    fun heldPermissions(context: Context): List<String> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toList() ?: emptyList()
    }.getOrDefault(emptyList()).sorted()

    /**
     * Permissions Android adds by itself, which the app never asked for: those flagged
     * implicit by the system (Android 12+), and the local-network permission newer Android
     * versions give every app that has INTERNET. They say nothing about the APK being altered.
     */
    private val OS_ADDED = setOf("android.permission.ACCESS_LOCAL_NETWORK")

    fun platformAdded(context: Context): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val names = info.requestedPermissions ?: return@runCatching emptySet<String>()
        val flags = info.requestedPermissionsFlags
        val implicit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && flags != null)
            names.indices.filter { flags[it] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_IMPLICIT != 0 }.map { names[it] }.toSet()
        else emptySet()
        implicit + names.filter { it in OS_ADDED }
    }.getOrDefault(emptySet())

    fun allowedPermissions(context: Context): Set<String> =
        BuildConfig.ALLOWED_PERMISSIONS.split(",").map { it.replace("\${applicationId}", context.packageName) }.toSet()

    /**
     * The sandbox, checked from inside. The build already refuses any permission
     * off the allowlist; finding one here means this APK was altered after it
     * was built. And with no <queries> declared, Android 11+ hides every other
     * app from this one - so the list of apps visible here should hold only
     * this app itself (system components aside).
     */
    fun sandbox(context: Context): Finding {
        val extra = heldPermissions(context) - allowedPermissions(context) - platformAdded(context)
        if (extra.isNotEmpty()) return Finding("Sandbox", Severity.DANGER, "holds permissions it was never built with: ${extra.joinToString { it.substringAfterLast('.') }}")
        val visible = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getInstalledApplications(0).count {
                it.packageName != context.packageName && it.flags and ApplicationInfo.FLAG_SYSTEM == 0
            }
        }.getOrDefault(0)
        // Android always lets every app see the keyboard, the launcher and any
        // app that opened it, so a handful is expected; more means the
        // visibility restriction is not in force (an older Android, or tampering).
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                Finding("Sandbox", Severity.NOTICE, "no sensitive permissions held; Android below 11 does not hide the list of installed apps")
            // Some makers show their pre-installed apps to every app. That is the phone's privacy, not tampering
            // with IraAlgo, so it is reported but does not withdraw biometrics.
            visible > 5 -> Finding("Sandbox", Severity.NOTICE, "can see $visible other installed apps (common on some phone makers)")
            else -> Finding("Sandbox", Severity.OK, "no access to messages, mail, contacts, files or other apps' data")
        }
    }

    fun report(context: Context): List<Finding> {
        val out = ArrayList<Finding>()
        out += sandbox(context)
        out += if (rooted()) Finding("Root", Severity.DANGER, "su binaries, Magisk or test-keys present")
        else Finding("Root", Severity.OK, "no root indicators found")
        out += if (hooked()) Finding("Hooking", Severity.DANGER, "an instrumentation framework is loaded or listening")
        else Finding("Hooking", Severity.OK, "no Frida/Xposed traces in this process")
        out += if (debuggerAttached()) Finding("Debugger", Severity.DANGER, "a debugger is attached to this process")
        else Finding("Debugger", Severity.OK, "none attached")
        if (!BuildConfig.DEBUG && debuggableBuild(context)) out += Finding("Build", Severity.DANGER, "release build marked debuggable - repackaged?")
        out += if (assetDigestOk(context)) Finding("Record", Severity.OK, "170-session chain file matches its build-time SHA-256")
        else Finding("Record", Severity.DANGER, "bundled chains do not match their build-time hash")
        if (emulator()) out += Finding("Device", Severity.NOTICE, "running on an emulator")
        val adb = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        if (adb) out += Finding("USB debugging", Severity.NOTICE, "ADB is enabled; turn it off when not developing")
        return out
    }

    fun compromised(findings: List<Finding>): Boolean = findings.any { it.severity == Severity.DANGER }

    private var cached: List<Finding>? = null
    private var cachedAt = 0L

    /** [report], reused for up to [maxAgeMs] (the asset hash is not free); 0 = always fresh. */
    @Synchronized
    fun reportWithin(context: Context, maxAgeMs: Long): List<Finding> {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAt <= maxAgeMs) return it }
        return report(context).also { cached = it; cachedAt = now }
    }
}
