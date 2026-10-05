package com.optionslab.app.testing

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.optionslab.app.data.ChartFeed
import com.optionslab.app.data.Holidays
import com.optionslab.app.data.Market
import com.optionslab.app.data.Net
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.Alerts
import com.optionslab.app.work.PositionCards
import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TEST ONLY (area F: background work, notifications, widget and the data layer's plumbing).
 *
 * [UpstoxStub] is a local HTTPS stand-in for Upstox's public candle feed and instrument master,
 * wired into the app's real [Net] code through `Net.testEndpoint` (every request's scheme and host
 * are replaced by the stub's). [Background] fixes the market clock (`Market.testClock`), the NSE
 * calendar and the other process-wide state these tests touch, and puts it all back afterwards.
 *
 * Needs `@ConscryptMode(ConscryptMode.Mode.OFF)` on the test class when [UpstoxStub] is used.
 */
class UpstoxStub : Closeable {
    /** Every request's path, URL-decoded ("/v3/historical-candle/intraday/NSE_INDEX|Nifty 50/minutes/1"). */
    val paths = CopyOnWriteArrayList<String>()

    /** The answer to each request, by decoded path. Runs on the server's threads; may block. */
    @Volatile var reply: (String) -> MockResponse = { status(404) }

    private val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = URLDecoder.decode((request.path ?: "").replace("+", "%2B"), "UTF-8")
                paths += p
                return reply(p)
            }
        }
        server.start()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(server.hostName).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        Net.testEndpoint = Net.TestEndpoint(server.url("/").toString().trimEnd('/'), trust.sslSocketFactory())
    }

    fun count(pred: (String) -> Boolean): Int = paths.count(pred)

    override fun close() {
        Net.testEndpoint = null
        server.shutdown()
    }

    companion object {
        const val BASE = "/v3/historical-candle"
        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        fun status(code: Int): MockResponse = MockResponse().setResponseCode(code).setBody("{}")
        fun json(body: String): MockResponse = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

        /** Upstox's candle payload for [bars], newest first as Upstox sends them. */
        fun candles(bars: List<Upstox.Bar>): MockResponse {
            val rows = JSONArray()
            bars.sortedByDescending { it.epochSecond }.forEach { b ->
                rows.put(JSONArray().put(Instant.ofEpochSecond(b.epochSecond).atZone(IST).format(TS))
                    .put(b.open).put(b.high).put(b.low).put(b.close).put(b.volume).put(b.oi))
            }
            return json(JSONObject().put("status", "success").put("data", JSONObject().put("candles", rows)).toString())
        }

        /** [n] one-minute bars on [day] from [from], priced around [price]; volume and OI in whole lots of [lot]. */
        fun minutes(day: LocalDate, from: LocalTime, n: Int, price: Double, lot: Int = 75): List<Upstox.Bar> = (0 until n).map { i ->
            val t = day.atTime(from.plusMinutes(i.toLong())).atZone(IST).toEpochSecond()
            val p = price + i * 0.5
            Upstox.Bar(t, p, p + 1, p - 1, p + 0.25, lot * (10L + i), lot * (1_000L + 10L * i))
        }

        fun isIntraday(path: String) = path.startsWith("$BASE/intraday/")
        fun isHistory(path: String) = path.startsWith("$BASE/") && !isIntraday(path)
    }
}

object Background {
    /** Wednesday 15 October 2025: an ordinary NSE trading day in these tests (the calendar is set by [calendar]). */
    val WED: LocalDate = LocalDate.of(2025, 10, 15)

    /** Fix the market clock at [day] [hour]:[minute] IST. */
    fun at(day: LocalDate, hour: Int, minute: Int) {
        Market.testClock = Clock.fixed(day.atTime(hour, minute).atZone(IST).toInstant(), IST)
    }

    /**
     * The NSE calendar the app sees: fetched on [fetched] (so nothing refreshes it from NSE), with
     * these holidays and special sessions. Set straight into the in-memory book, as a fetch would.
     */
    fun calendar(fetched: LocalDate?, holidays: Set<LocalDate> = emptySet(), extra: Set<LocalDate> = emptySet()) {
        holidaysCache().set(null, Holidays.Book(fetched, holidays.associateWith { "Test holiday" }, emptySet(), emptySet(), extra))
        Holidays.forgetTrading()
    }

    /** Everything these tests change that outlives one Robolectric application. */
    fun reset() {
        Market.testClock = null
        Net.testEndpoint = null
        holidaysCache().set(null, null)
        Holidays.forgetTrading()
        clearChartCache()
        clearAlerts()
        clearCards()
        com.optionslab.app.widget.OpenWidget.resetForTest()
    }

    fun clearChartCache() {
        val f = ChartFeed::class.java.getDeclaredField("past").apply { isAccessible = true }
        val m = f.get(null) as MutableMap<*, *>
        synchronized(m) { m.clear() }
    }

    private fun holidaysCache() = Holidays::class.java.getDeclaredField("cache").apply { isAccessible = true }

    /** A linked Zerodha account (keys saved, a login once) with no session today. Made-up values. */
    fun linkZerodha() = SecurePrefs.putAll(mapOf("kite.apiKey" to "test-key-not-real", "kite.apiSecret" to "test-secret-not-real",
        "kite.userId" to "ZZ0000"))

    fun grantNotifications(context: Context) =
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    fun denyNotifications(context: Context) =
        shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    fun notifications(context: Context) = shadowOf(context.getSystemService(NotificationManager::class.java))

    fun title(n: Notification): String? = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    fun text(n: Notification): String? = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** Today's instrument list where [Market.contracts] reads it: the master is never downloaded. */
    fun contracts(context: Context, day: LocalDate, list: List<Upstox.Contract>) {
        val rows = JSONArray()
        list.forEach { rows.put(JSONArray().put(it.underlying).put(it.expiry.toString()).put(it.strike).put(it.right.name).put(it.lotSize).put(it.instrumentKey).put(it.tradingSymbol)) }
        File(context.filesDir, "contracts.json").writeText(JSONObject().put("day", day.toString()).put("c", rows).toString())
    }

    /**
     * Wait (really) until [ok], or fail with [what]. Counted in sleeps, not read off a clock:
     * under Robolectric's paused looper System.currentTimeMillis() stands still.
     */
    fun await(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        var left = timeoutMs / 25
        while (!ok()) {
            if (left-- <= 0) throw AssertionError("timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    /** The in-app banner queue and its 20-second repeat filter are process-wide: emptied between tests. */
    fun clearAlerts() {
        Alerts.queue.value.forEach { Alerts.dismiss(it.id) }
        val m = Alerts::class.java.getDeclaredField("recent").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        synchronized(m) { m.clear() }
    }

    /** The position cards shown so far (process-wide). */
    fun clearCards() {
        listOf("shown", "sources", "sourceQty").forEach {
            (PositionCards::class.java.getDeclaredField(it).apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
        }
    }

    /** WorkManager for this test's application: synchronous, and nothing with a network constraint runs. */
    fun workManager(context: Context): WorkManager {
        WorkManagerTestInitHelper.initializeTestWorkManager(context.applicationContext,
            Configuration.Builder().setMinimumLoggingLevel(Log.WARN).setExecutor(SynchronousExecutor()).build())
        return WorkManager.getInstance(context.applicationContext)
    }

    fun unique(context: Context, name: String): List<WorkInfo> = WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWork(name).get()
}

/**
 * A test of area F still running after [seconds] prints its stack and is interrupted, so a stuck wait fails with
 * its place instead of holding the whole CI job (Robolectric runs every test on one thread).
 */
class BackgroundWatchdog(private val seconds: Long = 150) : org.junit.rules.TestRule {
    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val thread = Thread.currentThread()
                val timer = java.util.Timer("background-watchdog ${description.methodName}", true)
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

/**
 * Run [block] (which reaches into other areas' stores and locks: strategies, ORB arms, the paper account) on its
 * own thread, and fail with that thread's stack if it is still running after [seconds]: a lock held elsewhere can
 * then never hold the test thread, and with it the whole CI job.
 */
fun <T> bounded(what: String, seconds: Long = 90, block: () -> T): T {
    var worker: Thread? = null
    val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "bounded: $what").apply { isDaemon = true; worker = this } }
    val f = pool.submit(java.util.concurrent.Callable { block() })
    try {
        return f.get(seconds, java.util.concurrent.TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        val stack = worker?.stackTrace?.joinToString("\n    at ") ?: "?"
        throw AssertionError("$what still running after $seconds s:\n    at $stack")
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    } finally {
        pool.shutdownNow()
    }
}
