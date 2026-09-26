package com.optionslab.app.testing

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.github.takahirom.roborazzi.captureRoboImage
import com.optionslab.app.data.Market
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
 * The app's [com.optionslab.app.data.Net] opens plain `HttpsURLConnection`s to api.upstox.com. While a
 * [FakeUpstox] is open, the JVM's default proxy selector sends only that host to a tiny CONNECT proxy
 * on 127.0.0.1, which tunnels to a MockWebServer holding a throwaway certificate for api.upstox.com;
 * the JVM's default TLS trust is set to that certificate for the duration. Every other host still goes
 * through [NetworkGuard] (and is blocked). [close] restores the guard and the default TLS settings.
 * No production code changes: Net runs unchanged, its retries and parsing included.
 *
 * Needs `@ConscryptMode(ConscryptMode.Mode.OFF)` on the test class (the JDK's TLS talks to the fake).
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

    private val server = MockWebServer()
    private val proxy = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val oldFactory: SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
    private val oldVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    @Volatile private var open = true

    private val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> =
            if (uri.host == HOST) listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.localPort))) else NetworkGuard.select(uri)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    init {
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(HOST).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        HttpsURLConnection.setDefaultSSLSocketFactory(trust.sslSocketFactory())
        HttpsURLConnection.setDefaultHostnameVerifier { host, _ -> host == HOST }
        ProxySelector.setDefault(selector)
        Thread({ acceptLoop() }, "fake-upstox-proxy").apply { isDaemon = true }.start()
    }

    override fun close() {
        open = false
        ProxySelector.setDefault(NetworkGuard)
        HttpsURLConnection.setDefaultSSLSocketFactory(oldFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(oldVerifier)
        runCatching { proxy.close() }
        runCatching { server.shutdown() }
    }

    // ---- the CONNECT proxy -------------------------------------------------------------

    private fun acceptLoop() {
        while (open) {
            val client = try { proxy.accept() } catch (_: IOException) { return }
            Thread({ tunnel(client) }, "fake-upstox-tunnel").apply { isDaemon = true }.start()
        }
    }

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun tunnel(client: Socket) {
        try {
            val head = readHead(client.getInputStream())
            if (!head.startsWith("CONNECT ")) { client.close(); return }
            val upstream = Socket("127.0.0.1", server.port)
            client.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
            val a = Thread { pipe(client.getInputStream(), upstream.getOutputStream()); runCatching { upstream.shutdownOutput() } }
            a.isDaemon = true; a.start()
            pipe(upstream.getInputStream(), client.getOutputStream())
            runCatching { client.close() }; runCatching { upstream.close() }
        } catch (_: IOException) {
            runCatching { client.close() }
        }
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n); to.flush()
            }
        } catch (_: IOException) { }
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
            prices[key]?.let { last -> todayCandles(last).forEach { candles.put(it) } }
        }
        return MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("status", "success").put("data", JSONObject().put("candles", candles)).toString())
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

    /**
     * Dialogs holding a text field never report idle under Robolectric while the clock runs by itself;
     * [paused] stops the automatic clock for [block] and [frames] moves it by hand.
     */
    protected fun <T> paused(block: () -> T): T {
        compose.mainClock.autoAdvance = false
        try { return block() } finally { compose.mainClock.autoAdvance = true }
    }

    protected fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }
}
