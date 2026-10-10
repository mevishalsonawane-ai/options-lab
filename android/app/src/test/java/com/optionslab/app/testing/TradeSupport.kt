package com.optionslab.app.testing

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.github.takahirom.roborazzi.captureRoboImage
import com.optionslab.app.data.Market
import com.optionslab.app.data.Net
import com.optionslab.app.data.Paper
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * TEST ONLY (area A, the Trade tab): a fake Upstox candle feed on localhost, so the paper account
 * ([Paper], the real sandbox engine) gets prices without the internet.
 *
 * While a [FakeUpstox] is open, [Net]'s debug-only `testEndpoint` sends every Upstox request to a
 * MockWebServer on 127.0.0.1 (HTTPS, throwaway certificate); Net's retries and parsing run unchanged.
 * [close] clears it. Needs `@ConscryptMode(ConscryptMode.Mode.OFF)` on the test class.
 *
 * Prices: [price] sets the last price of an instrument key; the intraday reply is three minute
 * candles of today (IST) closing there, with a range that holds the close (so the sandbox never
 * sees the quote as stale). Unknown keys get an empty (successful) reply. [down] answers 503.
 */
class FakeUpstox : Closeable {
    val prices = ConcurrentHashMap<String, Double>()
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var down = false

    fun price(key: String, last: Double) { prices[key] = last }

    /** One 1-minute candle of today, by its start (IST). */
    data class Candle(val at: LocalTime, val open: Double, val high: Double, val low: Double, val close: Double)

    /** A key's own candles of today (oldest first), served instead of the three made from [price]: highs and lows that matter. */
    val minutes = ConcurrentHashMap<String, List<Candle>>()

    private val server = MockWebServer()

    init {
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // A throwaway certificate for this run only, trusted through Net's debug-only test endpoint.
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(server.hostName).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        Market.testClock = null
        Net.testEndpoint = Net.TestEndpoint(server.url("/").toString().trimEnd('/'), trust.sslSocketFactory())
    }

    override fun close() {
        Net.testEndpoint = null
        runCatching { server.shutdown() }
    }

    // ---- replies ----------------------------------------------------------------------------

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.requestUrl?.encodedPath ?: ""
        requests += path
        if (down) return MockResponse().setResponseCode(503)
        val candles = JSONArray()
        // /v3/historical-candle/intraday/{key}/minutes/1
        val parts = path.split('/')
        val i = parts.indexOf("intraday")
        if (i >= 0 && i + 1 < parts.size) {
            val key = URLDecoder.decode(parts[i + 1], "UTF-8")
            val own = minutes[key]
            if (own != null) ownCandles(own).forEach { candles.put(it) }
            else prices[key]?.let { last -> todayCandles(last).forEach { candles.put(it) } }
        }
        return MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("status", "success").put("data", JSONObject().put("candles", candles)).toString())
    }

    private fun ownCandles(list: List<Candle>): List<JSONArray> {
        val day = Market.today()
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        // Newest first, as Upstox sends them.
        return list.sortedByDescending { it.at }.map { c ->
            JSONArray().put(ZonedDateTime.of(day, c.at, IST).toOffsetDateTime().format(fmt)).put(c.open).put(c.high).put(c.low).put(c.close).put(1_000).put(0)
        }
    }

    private fun todayCandles(last: Double): List<JSONArray> {
        val day = Market.today()
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        // Newest first, as Upstox sends them; every bar holds the last price inside its range.
        return (2 downTo 0).map { m ->
            val t = ZonedDateTime.of(day, LocalTime.of(9, 15 + m), IST).toOffsetDateTime().format(fmt)
            JSONArray().put(t).put(last).put(last * 1.02).put(last * 0.98).put(last).put(1_000).put(0)
        }
    }

    companion object {
        const val HOST = "api.upstox.com"
    }
}

/**
 * The option contracts the paper form lists (the Upstox instrument master, cached for today), plus the
 * fake prices behind them. NIFTY: two weekly expiries, strikes 24,300-24,700 step 100, lot 75, spot
 * 24,512 (ATM 24,500). BANKNIFTY: one expiry, strikes 51,800-52,200, lot 35, spot 52,040.
 */
object TradeFixtures {
    val nearExpiry: LocalDate get() = Market.today().plusDays(9)
    val farExpiry: LocalDate get() = Market.today().plusDays(16)
    val niftyStrikes = listOf(24_300.0, 24_400.0, 24_500.0, 24_600.0, 24_700.0)
    val bankStrikes = listOf(51_800.0, 51_900.0, 52_000.0, 52_100.0, 52_200.0)
    const val NIFTY_SPOT = 24_512.0
    const val BANK_SPOT = 52_040.0

    fun key(underlying: String, expiry: LocalDate, strike: Double, right: String) =
        "NSE_FO|${underlying.take(2)}${expiry.dayOfYear}${strike.toInt()}$right"

    /** Writes today's contract list where [Market.contracts] reads it, and prices every contract at [premium]. */
    fun seed(context: android.content.Context, upstox: FakeUpstox?, premium: (strike: Double, right: String) -> Double = { _, _ -> 100.0 }) {
        val rows = JSONArray()
        fun add(u: String, e: LocalDate, ks: List<Double>, lot: Int) {
            for (k in ks) for (r in listOf("CE", "PE")) {
                val key = key(u, e, k, r)
                rows.put(JSONArray().put(u).put(e.toString()).put(k).put(r).put(lot).put(key).put("$u$k$r"))
                upstox?.price(key, premium(k, r))
            }
        }
        add("NIFTY", nearExpiry, niftyStrikes, 75)
        add("NIFTY", farExpiry, niftyStrikes, 75)
        add("BANKNIFTY", nearExpiry, bankStrikes, 35)
        File(context.filesDir, "contracts.json").writeText(JSONObject().put("day", Market.today().toString()).put("c", rows).toString())
        upstox?.price(Upstox.INDEX_KEYS.getValue("NIFTY"), NIFTY_SPOT)
        upstox?.price(Upstox.INDEX_KEYS.getValue("BANKNIFTY"), BANK_SPOT)
    }

    /**
     * Paper mode with the account guard's time-of-day entry cut-off off (so a test means the same at
     * any hour the CI runs) and every other limit at its default.
     */
    fun paperSettings(nakedShortsAllowed: Boolean = false) {
        SecurePrefs.putAll(mapOf("k.mode" to "sandbox", "k.allow" to false, "g.cutoff" to -1, "g.v" to 2))
        if (nakedShortsAllowed) SecurePrefs.put("g.naked", false)
    }

    fun killSwitch(on: Boolean) = SecurePrefs.put("g.kill", on)
}

/** A real [AppModel] owned by a store the test clears in its @After (its coroutines end with it). */
class ModelHolder(private val app: Application) {
    val store = ViewModelStore()
    val model: AppModel by lazy {
        ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AppModel::class.java]
    }

    fun clear() { store.clear(); Thread.sleep(150) }
}

/** Waits (real time, main looper idled) for [get] to answer non-null. */
fun <T> awaitValue(what: String, timeoutMs: Long = 20_000, get: () -> T?): T {
    val end = System.currentTimeMillis() + timeoutMs
    var last: Throwable? = null
    while (System.currentTimeMillis() < end) {
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        try { get()?.let { return it } } catch (e: AssertionError) { last = e }
        Thread.sleep(20)
    }
    throw AssertionError("timed out waiting for $what" + (last?.let { ": ${it.message}" } ?: ""))
}

/** The paper account's snapshot once the model has one that satisfies [ok]. */
fun AppModel.awaitPaper(what: String = "the paper account", timeoutMs: Long = 20_000, ok: (Paper.Snapshot) -> Boolean = { true }): Paper.Snapshot =
    awaitValue(what, timeoutMs) {
        when (val l = paper.value) {
            is Load.Done -> l.value.takeIf(ok)
            is Load.Failed -> throw AssertionError("paper load failed: ${l.why}")
            else -> null
        }
    }

/**
 * Screen-matrix base for the Trade tab: [lintKnown] is [ScreenTest.lint] with known layout bugs matched
 * per finding (a regex over the finding's text) instead of per device, so a screen with one known bug
 * still fails on any other error. When every error is a known one, the test is skipped with
 * "LAYOUT BUG (...)" and the findings, as [ScreenTest.checkScreen] does with its knownBugs.
 */
abstract class TradeScreenBase(device: DeviceConfig) : ScreenTest(device) {
    @get:org.junit.Rule val watchdog = TradeWatchdog()

    data class Known(val pattern: Regex, val bug: String)

    protected fun lintKnown(name: String, known: List<Known> = emptyList(), options: LayoutLint.Options = LayoutLint.Options()) {
        compose.waitForIdle()
        val findings = compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { LayoutLint.check(it, compose.density, options) }
        findings.forEach { println("LAYOUT $name ${device.name}: $it") }
        val errors = findings.filter { it.level == LayoutLint.Level.ERROR }
        val matched = errors.associateWith { e -> known.firstOrNull { it.pattern.containsMatchIn(e.toString()) } }
        val other = matched.filterValues { it == null }.keys
        if (other.isNotEmpty()) org.junit.Assert.fail("Layout problems on $name (${device.name}):\n" + other.joinToString("\n") +
            (matched.filterValues { it != null }.keys.takeIf { it.isNotEmpty() }?.let { "\n(and known: ${it.joinToString("; ")})" } ?: ""))
        val bugs = matched.values.filterNotNull().map { it.bug }.distinct()
        if (bugs.isNotEmpty()) org.junit.Assume.assumeTrue("LAYOUT BUG ($name, ${device.name}): ${bugs.joinToString(" | ")}\n" +
            errors.joinToString("\n"), false)
    }

    /**
     * Screenshot and [lintKnown] of what is on screen now. With a dialog or popup open there are several
     * roots: each non-empty one is saved (the window as <name>_<device>.png, the others with a -layerN suffix).
     */
    protected fun snap(name: String, known: List<Known> = emptyList(), options: LayoutLint.Options = LayoutLint.Options()) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
        val nodes = roots.fetchSemanticsNodes()
        if (nodes.size <= 1) capture(name)
        else nodes.forEachIndexed { i, n ->
            if (n.size.width > 0 && n.size.height > 0) {
                val file = "build/outputs/roborazzi/${name}${if (i == 0) "" else "-layer$i"}_${device.name}.png"
                roots[i].captureRoboImage(file)
            }
        }
        lintKnown(name, known, options)
    }

    /** Was: the clock paused around text-field dialogs (they never settled; fixed in the app's AlertDialog). */
    protected fun <T> paused(block: () -> T): T = block()   // the clock runs now (see AreaESupport.pause)

    protected fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }
}

/**
 * A stuck Trade-tab test fails with its stack instead of holding the CI job: after [seconds] the test
 * thread is interrupted (every 5 s until it ends), and all threads' stacks are printed once.
 */
class TradeWatchdog(private val seconds: Long = 180) : org.junit.rules.TestRule {
    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val thread = Thread.currentThread()
                val timer = java.util.Timer("trade-watchdog ${description.methodName}", true)
                var dumped = false
                timer.schedule(object : java.util.TimerTask() {
                    override fun run() {
                        if (!dumped) {
                            dumped = true
                            System.err.println("WATCHDOG ${description.displayName} still running after $seconds s; test thread:")
                            thread.stackTrace.forEach { System.err.println("    at $it") }
                        }
                        thread.interrupt()
                    }
                }, seconds * 1000, 5_000)
                try { base.evaluate() } finally { timer.cancel(); Thread.interrupted() }
            }
        }
}
