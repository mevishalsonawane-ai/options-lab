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
import kotlinx.coroutines.launch

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
        // An APK repackaged with a lower target SDK gets old permissions marked implicit: never trust that.
        val implicit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && flags != null && context.applicationInfo.targetSdkVersion >= 35)
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

    /** The parts of [report] that cannot change while the process runs: the installed APK, its assets, the device model. */
    private class Static(val sandbox: Finding, val debuggable: Boolean, val recordOk: Boolean, val emulator: Boolean)

    private fun static(context: Context) =
        Static(sandbox(context), !BuildConfig.DEBUG && debuggableBuild(context), assetDigestOk(context), emulator())
            .also { staticMemo = StaticMemo(context.applicationContext, android.os.SystemClock.elapsedRealtime(), it) }

    /** The static findings plus the ones that can change at any moment (root, hooks, debugger, ADB), read now. */
    private fun assemble(context: Context, st: Static): List<Finding> {
        scans.incrementAndGet()
        if (Thread.currentThread() === scanWatchedForTest) scansOnWatchedForTest.incrementAndGet()
        testScanDelayMs.takeIf { it > 0 }?.let { ms ->
            val end = System.currentTimeMillis() + ms
            while (testScanDelayMs > 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        }
        val out = ArrayList<Finding>()
        out += st.sandbox
        out += if (rooted()) Finding("Root", Severity.DANGER, "su binaries, Magisk or test-keys present")
        else Finding("Root", Severity.OK, "no root indicators found")
        out += if (hooked()) Finding("Hooking", Severity.DANGER, "an instrumentation framework is loaded or listening")
        else Finding("Hooking", Severity.OK, "no Frida/Xposed traces in this process")
        out += if (debuggerAttached()) Finding("Debugger", Severity.DANGER, "a debugger is attached to this process")
        else Finding("Debugger", Severity.OK, "none attached")
        if (st.debuggable) out += Finding("Build", Severity.DANGER, "release build marked debuggable - repackaged?")
        out += if (st.recordOk) Finding("Record", Severity.OK, "170-session chain file matches its build-time SHA-256")
        else Finding("Record", Severity.DANGER, "bundled chains do not match their build-time hash")
        if (st.emulator) out += Finding("Device", Severity.NOTICE, "running on an emulator")
        val adb = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        if (adb) out += Finding("USB debugging", Severity.NOTICE, "ADB is enabled; turn it off when not developing")
        return out
    }

    fun report(context: Context): List<Finding> = assemble(context, static(context))

    /** The last static part, for the application (process) it was read in and when. */
    private class StaticMemo(val app: Context, val at: Long, val st: Static)
    @Volatile private var staticMemo: StaticMemo? = null

    /** Compute the static part ahead of a send (when the order review opens), off the main thread. */
    fun warmStatic(context: Context) { static(context) }

    /**
     * The check right before an order is sent. Root, hooks, a debugger and ADB are read fresh every time
     * (they can appear at any moment); the APK's sandbox and the 10 MB asset hash - which cannot change
     * while the app runs - are reused when computed within [staticMaxAgeMs] (the review computes them as
     * it opens), so hashing 10 MB no longer sits between the owner's confirmation and the order.
     */
    fun reportForSend(context: Context, staticMaxAgeMs: Long = 60_000): List<Finding> {
        val now = android.os.SystemClock.elapsedRealtime()
        val st = staticMemo?.takeIf { it.app === context.applicationContext && now - it.at in 0..staticMaxAgeMs }?.st ?: static(context)
        return assemble(context, st)
    }

    fun compromised(findings: List<Finding>): Boolean = findings.any { it.severity == Severity.DANGER }

    /**
     * The last report and when it was read (SystemClock.elapsedRealtime). 22 Nov 2026 (B6 review): kept fresh by the background
     * refresher ([startRefresher]) too, and read with no lock: [cachedAt] is written after [cached] and read before it, so a reader
     * that sees a new time sees its report (an older time with a newer report only reads as older: never fresher than it is).
     */
    @Volatile private var cached: List<Finding>? = null
    @Volatile private var cachedAt = 0L

    /** One scan at a time (the background one, a [reportWithin] that found nothing fresh); never held by a reader of the cache. */
    private val scanLock = Any()

    private fun keep(findings: List<Finding>, at: Long) { cached = findings; cachedAt = at }

    /** The cached report when it is at most [maxAgeMs] old at [now] (as before: the clock is the process's own, never set back); else null. */
    private fun fresh(maxAgeMs: Long, now: Long = android.os.SystemClock.elapsedRealtime()): List<Finding>? {
        val at = cachedAt
        val c = cached ?: return null
        return c.takeIf { now - at <= maxAgeMs }
    }

    /**
     * [report], reused for up to [maxAgeMs] (the asset hash is not free); 0 = always fresh. 22 Nov 2026 (B6 review): the cache is read
     * with no lock (it was @Synchronized, so every caller waited behind a scan under way: a SHA-256 of the 10 MB asset, binder calls,
     * /proc reads, a socket connect), and the background refresher keeps it under a minute old, so an entry's check reads memory.
     */
    fun reportWithin(context: Context, maxAgeMs: Long): List<Finding> {
        askedAt = android.os.SystemClock.elapsedRealtime()
        fresh(maxAgeMs)?.let { return it }
        return synchronized(scanLock) {
            fresh(maxAgeMs) ?: run {
                val now = android.os.SystemClock.elapsedRealtime()
                report(context).also { keep(it, now) }
            }
        }
    }

    // ---- 22 Nov 2026 (B6 review: a full device scan ran inside the 15-second stop check) -------------------------------------

    /** The background refresher's pace: the cached report stays under a minute old for [reportWithin]'s callers. */
    const val REFRESH_MS = 40_000L
    /** The oldest cached verdict an automatic entry goes on (as the minute the stop check's scan was reused before); older is unknown. */
    const val VERDICT_MAX_AGE_MS = 60_000L
    /** The refresher reads the parts that cannot change while the app runs (the APK, the 10 MB asset's hash) at most this often. */
    private const val STATIC_REFRESH_MS = 10 * 60_000L

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
        com.optionslab.app.data.ScopeGuard.handler("device check"))
    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var refresher: kotlinx.coroutines.Job? = null

    /** Scans made in this process (the tests; memory only). */
    internal val scans = java.util.concurrent.atomic.AtomicLong()

    /**
     * One scan now, kept as the cached report: the probes that can change read fresh, the static part reused ([STATIC_REFRESH_MS]).
     * [unlessFresherThanMs] (0 or more): none when a report that fresh is kept already (another thread's scan just ended).
     */
    internal fun refresh(context: Context, unlessFresherThanMs: Long = -1L): List<Finding> = synchronized(scanLock) {
        if (unlessFresherThanMs >= 0L) fresh(unlessFresherThanMs)?.let { return@synchronized it }
        val at = android.os.SystemClock.elapsedRealtime()
        reportForSend(context, STATIC_REFRESH_MS).also { keep(it, at) }
    }

    /** A scan in the background unless one is under way (returns at once). */
    private fun kick(context: Context) {
        if (!scanning.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        runCatching { scope.launch { try { refresh(app, unlessFresherThanMs = REFRESH_MS) } finally { scanning.set(false) } } }
            .onFailure { scanning.set(false) }
    }

    /**
     * The device check's verdict for an automatic entry from memory only - never a scan on the caller's thread: compromised or not
     * from a report at most [maxAgeMs] old, else null (unknown). A report missing or older than [REFRESH_MS] starts a scan in the
     * background ([kick]). For the stop check and the passes: their exits never wait on the device check.
     */
    fun cachedCompromised(context: Context, maxAgeMs: Long = VERDICT_MAX_AGE_MS): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        askedAt = now
        val at = cachedAt
        val c = cached
        if (c == null || now - at > REFRESH_MS) kick(context)
        return if (c != null && now - at <= maxAgeMs) compromised(c) else null
    }

    /** [cachedCompromised] for an automatic entry: unknown refuses it (an entry never goes on a guess), as a check that cannot run. */
    fun entryRefused(context: Context): Boolean = cachedCompromised(context) ?: true

    /**
     * The verdict for the full pass, which may wait (never the 15-second stop check, whose exits never wait on it): the kept one; with
     * none (the first pass after a start, the refresher off) the device is scanned here, as the pass always did ([reportWithin]: a scan
     * already under way is waited for, never a second one) - so a scheduled start is never refused for want of a verdict.
     */
    fun entryRefusedPass(context: Context): Boolean =
        cachedCompromised(context) ?: compromised(reportWithin(context, VERDICT_MAX_AGE_MS))

    /** When a verdict or a report was last asked for (SystemClock.elapsedRealtime; 0: never): the refresher runs while it is recent. */
    @Volatile private var askedAt = 0L
    /** The refresher keeps scanning this long after the last ask (an entry's check, the stop check, a pass), [wanted] aside. */
    private const val ASKED_KEEP_MS = 5 * 60_000L

    /**
     * The background refresher (started once by the app, after the open positions' books are read): a scan every [REFRESH_MS], off
     * the main thread, so the cached verdict is always fresh for the entries' checks and nothing on a stop check ever scans. Only
     * while it can matter (battery): [wanted] says so (the app in Live with real orders on), or a verdict was asked for in the last
     * [ASKED_KEEP_MS]; otherwise the next ask starts a scan itself.
     */
    fun startRefresher(context: Context, wanted: () -> Boolean = { true }, afterStart: suspend () -> Unit = {}) {
        val app = context.applicationContext ?: context
        synchronized(this) {
            if (refresher?.isActive == true) return
            refresher = scope.launch {
                runCatching { afterStart() }
                while (true) {
                    val asked = askedAt
                    if (runCatching(wanted).getOrDefault(true) ||
                        (asked > 0L && android.os.SystemClock.elapsedRealtime() - asked < ASKED_KEEP_MS)) runCatching { refresh(app) }
                    kotlinx.coroutines.delay(REFRESH_MS)
                }
            }
        }
    }

    /** TEST ONLY: each scan takes this much longer (ms), as a slow phone's can. 0 in the app; set only in debug builds. */
    @Volatile internal var testScanDelayMs = 0L
        set(v) { check(BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }
    /** TEST ONLY: the thread whose scans are counted in [scansOnWatchedForTest] (null in the app). */
    @Volatile internal var scanWatchedForTest: Thread? = null
        set(v) { check(BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }
    internal val scansOnWatchedForTest = java.util.concurrent.atomic.AtomicLong()

    /**
     * TEST ONLY: no refresher and no scan under way (each test's app start): a background scan an earlier test started is waited for
     * (10 s at most), so it never lands in the next test's cache. The cache itself is kept, as before.
     */
    internal fun settleForTest() {
        check(BuildConfig.DEBUG) { "test only" }
        synchronized(this) { refresher?.cancel(); refresher = null }
        testScanDelayMs = 0L
        val end = System.currentTimeMillis() + 10_000L
        while (scanning.get() && System.currentTimeMillis() < end) Thread.sleep(5)
        synchronized(scanLock) { scanWatchedForTest = null; scansOnWatchedForTest.set(0L) }
    }

    /** TEST ONLY: [settleForTest], and no report kept (as a new process). */
    internal fun resetForTest() {
        settleForTest()
        synchronized(scanLock) { cached = null; cachedAt = 0L }
        askedAt = 0L
    }
}
