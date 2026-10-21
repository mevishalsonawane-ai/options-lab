package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Upstox
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

/**
 * Zerodha Kite Connect - the broker, as the PC trading app uses it.
 *
 * CREDENTIALS. The API key, API secret and the day's access
 * token live only in the encrypted vault. The secret and the token are never
 * shown again after entry, never logged, and never put in an error message.
 *
 * THERE IS NO REFRESH TOKEN. Kite issues individual developers an access
 * token that dies at about 06:00 IST the next day; the PC logs in every
 * morning for the same reason. The app asks once per trading day.
 *
 * NOTHING IS SENT WITHOUT YOU. [placeOrder] exists, but the UI calls it only
 * after you have reviewed the legs, held the send button, and re-proved who
 * you are with your PIN or fingerprint. Real orders are also off until you
 * switch them on, and are refused outright on a compromised device.
 */
object Broker {
    private lateinit var app: Context

    /** Where a bounded read runs ([within]): apart from its caller, so a caller that stops waiting is not held. */
    private val readScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * A Zerodha read with a real deadline. [call] does blocking HttpsURLConnection I/O (20 s connect, 60 s read) that does
     * not suspend, so a withTimeoutOrNull wrapped straight around it cannot end the wait. Here [read] runs apart on IO and
     * the caller waits [ms] at most: null on timeout or failure. A read the caller gave up on finishes in the background
     * and is dropped. Reads only: never used for anything that places, changes or cancels.
     */
    suspend fun <T> within(ms: Long, read: suspend () -> T): T? {
        val d = readScope.async { runCatching { read() }.getOrNull() }
        return kotlinx.coroutines.withTimeoutOrNull(ms) { d.await() }
    }

    private const val K_KEY = "kite.apiKey"
    private const val K_SECRET = "kite.apiSecret"            // legacy: plain inside the vault; migrated on next login
    private const val K_SEALED = "kite.apiSecretSealed"      // sealed with the owner's PIN (SecretBox)
    private const val K_BIO_SEALED = "kite.apiSecretBio"      // a second copy only the fingerprint opens (BiometricGate)
    private const val K_REDIRECT = "kite.redirect"
    private const val K_TOKEN = "kite.accessToken"
    private const val K_LOGIN_AT = "kite.loginAt"
    private const val K_USER = "kite.userName"
    private const val K_UID = "kite.userId"
    private const val K_SENT_DAY = "kite.sentDay"
    private const val K_SENT = "kite.sentCount"

    /**
     * The positions read shared within one market-watch pass ([passPositionBook], [com.optionslab.ira.PassShare]): the
     * words-only checks of a pass read Zerodha's positions once, not once each. Nothing that orders uses it.
     */
    private const val PASS_BOOK_MS = 20_000L
    private val passBook = com.optionslab.ira.PassShare<Positions>(PASS_BOOK_MS)

    // ---- the watch's workers (10 Oct): one request budget, shared reads, one door for entries ----------------------

    /**
     * Which lane a Zerodha request belongs to (Boss, 10 Oct: the watch's workers run side by side): exits, stops and the
     * loss limit first ([com.optionslab.ira.RateGate.Priority.SAFETY]), then entries and ordinary reads, then Jarvis's
     * analysis. Put on a coroutine's context; a request with none is an ordinary read. Every order is SAFETY.
     */
    class Lane(val priority: com.optionslab.ira.RateGate.Priority) : kotlin.coroutines.AbstractCoroutineContextElement(Lane) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<Lane>
    }

    /** At most 8 requests to Zerodha in any second from this phone, whatever runs at once (Kite allows about 10). */
    private val rate = com.optionslab.ira.RateGate(perSecond = 8)

    /**
     * Positions, orders and margins asked for by several workers at once are read once: a caller whose read is already on
     * its way (started at most [SHARE_MS] ago, with no order, change or cancel since) waits for that one. Nothing is kept
     * after a read ends ([com.optionslab.ira.SingleFlight]), so no caller gets an older answer than it would have.
     */
    const val SHARE_MS = 2_000L
    private val positionsShared = com.optionslab.ira.SingleFlight<Positions>()
    private val ordersShared = com.optionslab.ira.SingleFlight<List<OrderRow>>()
    private val fundsShared = com.optionslab.ira.SingleFlight<Funds>()

    /** What a shared read runs with: the caller's quick-read marker and lane (never its job). */
    private suspend fun shareContext(): kotlin.coroutines.CoroutineContext {
        val c = kotlinx.coroutines.currentCoroutineContext()
        return (c[FastRead] ?: kotlin.coroutines.EmptyCoroutineContext) + (c[Lane] ?: kotlin.coroutines.EmptyCoroutineContext)
    }

    /**
     * The shared reads' key: the change count (an order from here, or one the stream reported), the quick-read marker and the
     * caller's lane (review R1: a read runs at its first caller's lane in the request budget, so an exit's SAFETY read never
     * joins - and waits behind - an entry's or Jarvis's lower-lane read; only reads of the same lane are shared).
     */
    private suspend fun shareKey(gen: Long): Triple<Long, Boolean, com.optionslab.ira.RateGate.Priority> {
        val c = kotlinx.coroutines.currentCoroutineContext()
        return Triple(gen, c[FastRead] != null, c[Lane]?.priority ?: com.optionslab.ira.RateGate.Priority.ENTRY)
    }

    /** TEST ONLY: the shared reads' key as the calling coroutine would make it. */
    internal suspend fun shareKeyForTest(gen: Long): Any = shareKey(gen)

    /** The diagnostics' line: requests sent through the budget, how many waited, reads shared, entries at the door. */
    fun sharingLine(): String = "Zerodha requests: ${rate.taken} sent (8 a second at most), ${rate.waited} waited for the budget; " +
        "reads shared: positions ${positionsShared.joins}, orders ${ordersShared.joins}, margins ${fundsShared.joins}; " +
        "entry door: ${entryGate.went} through, ${entryGate.refused} refused" + (entryGate.lastRefusal?.let { " (last: $it)" } ?: "")

    /**
     * The one door every new Zerodha position goes through (10 Oct, [com.optionslab.ira.EntryGate]): one entry at a time from
     * any worker, the kill switch read again at the last moment (and, for the bots' own entries, the daily loss limit, the
     * bot's day stop and the Zerodha day lock). Exits never come here.
     */
    private val entryGate = com.optionslab.ira.EntryGate(maxWaitMs = 15_000L)

    /**
     * The order tags of the bots' own entries (ORB and Liquidity, the Pine scripts, the scheduled strategies, and since 18 Oct the
     * paper strategies' live path, [ArmLive]: "iraarm").
     */
    private val BOT_TAGS = setOf("iraorb", "irapine", "iraalgostrat", com.optionslab.engine.orb.LiveMirror.TAG)

    /**
     * Why an entry may not be sent NOW, asked inside the door (null: it may). Reads memory and the settings already in it:
     * no lock any bot holds (the bots call [placeOrder] holding their own).
     */
    @Volatile private var killKept: Pair<Int, Boolean>? = null

    private fun killOn(): Boolean {
        val g = SecurePrefs.generationHint
        killKept?.let { if (it.first == g) return it.second }
        val on = SecurePrefs.getBoolean(AppSettings.KILL_KEY, false)
        killKept = g to on
        return on
    }

    internal fun entryRecheck(o: Kite.Order, arm: String? = null): String? {
        // The kill switch kept in memory and read again only when a kept-elsewhere setting changed (g.kill is one of them):
        // the door reads nothing from the settings store on the order path (round 2's rule), yet sees a flip at once.
        if (killOn()) return "refused: the kill switch is on"
        // 18 Oct (review): the paper-only period read again inside the door too (memory only), so a period set while this entry
        // waited its turn still refuses it. [placeOrder] asks it first; this is the door's own look.
        PaperOnly.entryRefusal()?.let { return it }
        // 18 Oct (final review F2): a paper strategy's mirrored entry can wait here up to 15 s after its own checks ran: the app in
        // Live, "AI trades go live", its arm's Trade live and its Shadow asked again at the last moment - memory only.
        if (o.tag == com.optionslab.engine.orb.LiveMirror.TAG) ArmLive.doorRefusal(arm)?.let { return it }
        if (o.tag in BOT_TAGS) {
            if (LossBreaker.trippedToday()) return "refused: the daily loss limit was reached today"
            if (Strategies.stopHint() != null) return "stopped_for_today"
            DayLockGuard.refusal(true)?.let { return it }
            // Smart workers (10 Oct): a news window paused ON, unreliable data, the relay down - memory only, lower risk only.
            runCatching { SmartWorkers.doorRefusal(o.tradingSymbol) }.getOrNull()?.let { return it }
            // The portfolio guard (TODO S12): a live entry ITS arm ([arm]) was just blocked from by the guard - memory only, it
            // only blocks. Review R1: another arm's entry and a saved strategy's basket leg (no arm) are never refused here.
            if (o.tag != "iraalgostrat")
                runCatching { PortfolioHost.doorRefusal(arm, o.tradingSymbol, o.side == Kite.Side.BUY) }.getOrNull()?.let { return it }
            // 19 Oct: the same-direction cap on the bots' live index-option entries - in SHADOW by default (what it would refuse is
            // only recorded; nothing changes) until Boss switches it on in Bot settings. Memory only; it only ever refuses. A saved
            // strategy's basket leg is never asked (refusing one leg of a hedged basket could leave the rest unhedged).
            if (o.tag != "iraalgostrat")
                runCatching { ExposureCapHost.liveCheck(o.tradingSymbol, o.exchange, o.side == Kite.Side.BUY, arm ?: o.tag) }.getOrNull()?.let { return it }
            // 20 Oct, enhancement round 1 (idea 4): Boss's account stop rule over the whole Zerodha account - in SHADOW by default
            // (what it would refuse is only recorded) until Boss switches it on in Bot settings. Memory only; it only refuses. A
            // saved strategy's basket leg is never asked (one leg refused could leave the rest unhedged): its automatic start is.
            if (o.tag != "iraalgostrat")
                runCatching { AccountStopHost.doorCheck(o.tradingSymbol, arm ?: o.tag) }.getOrNull()?.let { return it }
        }
        return null
    }

    fun init(context: Context) { app = context.applicationContext }

    class NotLoggedIn : IOException("Log in to Zerodha for today first")
    class KiteError(val type: String, msg: String) : IOException(msg)

    // ---- credentials ------------------------------------------------------------

    /**
     * The order path's session, kept in memory (round 2, Boss: "don't wait even for a sec"): the API key, the day's access
     * token, when it was issued and the account holder's name, read from the settings vault once and then answered from here,
     * so placing, changing or reading an order takes no vault lock or decryption. Never written anywhere new and never
     * logged; dropped on logout, a session end or expiry, a new login or new keys, the app's lock and the kill switch
     * ([dropCreds]), and whenever the vault is wiped or read again ([SecurePrefs.generationHint]).
     */
    private class Creds(val key: String?, val token: String?, val loginAt: Long, val user: String?, val gen: Int) {
        /** When the token dies (Kite's ~06:00 IST the next day), epoch ms; null without a login. */
        val expiresMs: Long? = loginAt.takeIf { it > 0 }?.let { Kite.expiresAt(Instant.ofEpochMilli(it).atZone(IST)).toInstant().toEpochMilli() }
    }
    @Volatile private var credsKept: Creds? = null

    private fun creds(): Creds {
        val g = SecurePrefs.generationHint
        credsKept?.takeIf { it.gen == g }?.let { return it }
        return Creds(SecurePrefs.getString(K_KEY), SecurePrefs.getString(K_TOKEN), SecurePrefs.getLong(K_LOGIN_AT, 0L),
            SecurePrefs.getString(K_USER), g).also { credsKept = it }
    }

    /** Forget the in-memory session (logout, lock, kill switch, expiry): the next use reads the vault again. */
    fun dropCreds() { credsKept = null }

    val configured: Boolean get() = SecurePrefs.getString(K_KEY) != null &&
        (SecurePrefs.getString(K_SEALED) != null || SecurePrefs.getString(K_SECRET) != null || SecurePrefs.getString(K_BIO_SEALED) != null)
    val apiKey: String? get() = creds().key
    /**
     * The redirect URL to register in the Kite Connect app. The login page
     * inside IraAlgo catches it before it loads, so it never reaches the
     * network and no server is needed. (A URL saved by an older version is kept.)
     */
    const val REDIRECT = "http://127.0.0.1/iraalgo"
    val redirect: String get() = SecurePrefs.getString(K_REDIRECT) ?: REDIRECT
    val userName: String? get() = creds().user
    val userId: String? get() = SecurePrefs.getString(K_UID)

    /** Linked: keys saved and at least one Zerodha login completed. The app opens only once linked. */
    val linked: Boolean get() = configured && !SecurePrefs.getString(K_UID).isNullOrBlank()

    /** Only the last four characters of the key, for recognising it. */
    fun maskedKey(): String = apiKey?.let { "••••" + it.takeLast(4) } ?: "not set"

    /**
     * Save the key with the secret sealed by [pin] (already verified by the caller), and/or by the
     * fingerprint ([bioSealed], from BiometricGate). At least one; the secret is never stored readable.
     */
    fun saveCredentials(apiKey: String, apiSecret: String, pin: CharArray?, bioSealed: String? = null) {
        require(apiKey.isNotBlank() && apiKey.all { it.isLetterOrDigit() }) { "The API key should be letters and digits only" }
        require(apiSecret.isNotBlank() && apiSecret.all { it.isLetterOrDigit() }) { "The API secret should be letters and digits only" }
        require(pin != null || bioSealed != null) { "Enter your PIN, or use your fingerprint, to seal the secret" }
        SecurePrefs.putAll(mapOf(K_KEY to apiKey.trim(), K_SEALED to pin?.let { com.optionslab.app.security.SecretBox.seal(apiSecret.trim(), it) },
            K_BIO_SEALED to bioSealed, K_SECRET to null, K_REDIRECT to null, K_TOKEN to null, K_LOGIN_AT to null))
        dropCreds()
    }

    /** The fingerprint-sealed copy of the secret, if there is one. */
    val bioSealedSecret: String? get() = SecurePrefs.getString(K_BIO_SEALED)
    val pinSealed: Boolean get() = SecurePrefs.getString(K_SEALED) != null || SecurePrefs.getString(K_SECRET) != null
    fun dropBioSealed() { SecurePrefs.put(K_BIO_SEALED, null) }

    fun forget() {
        passBook.drop()
        KiteStream.stop()
        com.optionslab.app.security.BiometricGate.forgetSecretKey()
        SecurePrefs.putAll(mapOf(K_KEY to null, K_SECRET to null, K_SEALED to null, K_BIO_SEALED to null, K_REDIRECT to null, K_TOKEN to null,
            K_LOGIN_AT to null, K_USER to null, K_UID to null))
        dropCreds(); dropKept()
        File(app.filesDir, "kite_instruments.json").delete()
        File(app.filesDir, "kite_futures.json").delete()
        PnlTracker.clear()
    }

    fun expiresAt(): ZonedDateTime? = creds().loginAt.takeIf { it > 0 }
        ?.let { Kite.expiresAt(Instant.ofEpochMilli(it).atZone(IST)) }

    val loggedIn: Boolean get() = liveToken() != null

    /** The day's token while it is alive (memory only); an expired one is forgotten from memory at once. */
    private fun liveToken(): String? {
        val c = creds()
        val tok = c.token ?: return null
        val exp = c.expiresMs ?: return null
        if (System.currentTimeMillis() < exp) return tok
        // Expired: the in-memory copy is dropped (the vault's is replaced at the next login).
        credsKept = Creds(c.key, null, 0L, c.user, c.gen)
        return null
    }

    private fun token(): String = liveToken() ?: throw NotLoggedIn()

    /** The session token for the live price stream (never logged, never shown). */
    internal fun streamToken(): String? = liveToken()

    private fun dropSession() {
        passBook.drop(); dropKept(); KiteStream.stop(); SecurePrefs.putAll(mapOf(K_TOKEN to null, K_LOGIN_AT to null)); dropCreds()
    }

    /** Bumped when Zerodha itself ends the session (expired or logged out elsewhere): the app asks to log in again. */
    val sessionEnded = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** The notification of a session Zerodha ended ([endedNotice]). */
    internal const val ID_SESSION_ENDED = 2061

    /**
     * 18 Oct (live end-to-end tests): Zerodha ended the session mid-day (a token expired or a login elsewhere). Only the open
     * app asked to log in again ([sessionEnded]); with the app in the background Boss was not told, while no order, stop or exit
     * of the app's could go out. Now a notification says so (always shown, like an order's): the stops and GTTs resting at
     * Zerodha still stand. No account figure in it. Posted off the caller's thread (the settings read is never on an order's path).
     */
    private fun endedNotice() {
        runCatching {
            val ctx = app
            readScope.launch {
                runCatching {
                    com.optionslab.app.work.Notifier.post(ctx, ID_SESSION_ENDED, com.optionslab.app.work.Notifier.APPROVAL, "Zerodha session ended",
                        "Zerodha ended today's session (it expired or you logged in elsewhere). Until you log in again the app cannot place, " +
                            "change or exit any order; stops and GTTs resting at Zerodha still stand. Open Zerodha in the app and log in.",
                        "broker", setting = "broker.login")
                }
            }
        }
    }

    /** The notification of a bot's live entry the paper-only period refused ([paperOnlyRefused]). */
    internal const val ID_PAPER_ONLY = 2062
    /** The day the last [ID_PAPER_ONLY] notice was posted (memory only): one a day is enough to tell Boss why. */
    @Volatile private var paperOnlyNoticeDay: String? = null

    /**
     * 18 Oct: a live entry refused by the paper-only period. Always in the diagnostics; a bot's (no screen shows its refusal)
     * also as one notification a day. Posted off the caller's thread: the order path only reads memory here.
     */
    private fun paperOnlyRefused(o: Kite.Order, why: String) {
        runCatching { Diag.record("zerodha", "Entry not sent (${o.tradingSymbol}): the paper-only period") }
        if (o.tag !in BOT_TAGS) return
        val day = runCatching { Market.today().toString() }.getOrNull() ?: return
        if (paperOnlyNoticeDay == day) return
        paperOnlyNoticeDay = day
        runCatching {
            val ctx = app
            readScope.launch {
                runCatching {
                    com.optionslab.app.work.Notifier.post(ctx, ID_PAPER_ONLY, com.optionslab.app.work.Notifier.APPROVAL, "Live entry not sent: paper-only period",
                        "A bot's live entry (${o.tradingSymbol}) was not sent. $why", "broker", setting = "risk.paperonly")
                }
            }
        }
    }

    // ---- HTTP ---------------------------------------------------------------------

    /**
     * TEST SEAM (JVM tests only): send [call]'s requests to a local fake Kite server instead of
     * api.kite.trade, trusting that server's test certificate instead of the pinned Zerodha chain,
     * and without the static-IP relay. Null - always, in the app - means Zerodha itself.
     *
     * It cannot be switched on in a release build: the setter throws unless BuildConfig.DEBUG, and
     * nothing in the app calls it (only src/test does). Every other line of [call] - headers, auth,
     * retries, error mapping - runs unchanged against the fake.
     */
    internal class TestEndpoint(val base: String, val ssl: javax.net.ssl.SSLSocketFactory)
    @Volatile internal var testEndpoint: TestEndpoint? = null
        set(v) {
            check(com.optionslab.app.BuildConfig.DEBUG) { "the test endpoint exists only in debug builds" }
            field = v
        }

    /** Order fields kept in the diagnostics diary (what was asked, never who asked or with what key). */
    private val DIAG_FIELDS = setOf("tradingsymbol", "exchange", "transaction_type", "quantity", "order_type", "product",
        "price", "trigger_price", "validity", "variety", "tag")

    /**
     * Every Zerodha write (orders, changes, cancels, GTTs) and every failed read goes into the owner's diagnostics
     * diary: the path, the order's fields, Zerodha's answer or the failure, how long it took, and whether it went
     * through the static-IP relay. No header, token or key is ever read here.
     */
    private suspend fun call(method: String, path: String, body: String? = null, auth: Boolean = true, raw: Boolean = false,
                             json: Boolean = false, viaRelay: Boolean = false, readOnly: Boolean = false): Any {
        val t0 = System.currentTimeMillis()
        // [readOnly]: a POST that only reads (Zerodha's contract note): sent direct like a GET, and it changes nothing,
        // so no kept read is dropped and no diary line is written for it unless it fails.
        val write = method != "GET" && !readOnly
        val where = "$method ${path.substringBefore('?')}"
        val asked = if (body == null || json) "" else body.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('='); if (k in DIAG_FIELDS) "$k: ${java.net.URLDecoder.decode(kv.substringAfter('='), "UTF-8")}" else null
        }.joinToString(" ", prefix = " {", postfix = "}").takeIf { it != " {}" }.orEmpty()
        val route = if ((write || viaRelay) && testEndpoint == null && Relay.enabled) " via relay" else ""
        // A write (an order, a change, a cancel) changes the positions: the pass's shared read is dropped before it is
        // sent and again once it is answered, so no words-only check is given the book from before it.
        if (write) { passBook.drop(); writes.incrementAndGet() }
        return try {
            callInner(method, path, body, auth, raw, json, viaRelay, readOnly).also { r ->
                if (write) Diag.record("zerodha", "$where$asked$route -> ok ${(r as? JSONObject)?.optString("order_id")?.takeIf { it.isNotEmpty() }?.let { "order $it " } ?: ""}(${System.currentTimeMillis() - t0} ms)")
                // Self-healing (10 Oct): the answer's time, after it came back (memory only; nothing added before the request).
                SmartWorkers.restSample(true, System.currentTimeMillis() - t0)
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException)
                Diag.record("zerodha", "$where$asked$route -> FAILED ${e.javaClass.simpleName}: ${e.message} (${System.currentTimeMillis() - t0} ms)")
            // A network failure counts against Zerodha's health; Zerodha's own refusal (a KiteError) or no login does not, and
            // (20 Oct) nor does a failure that never reached Zerodha - the phone's own DNS or network gone, or our own cancel.
            if (e is java.io.IOException && e !is KiteError && e !is NotLoggedIn &&
                com.optionslab.ira.SelfHeal.zerodhaFault(e.javaClass.simpleName, e.message)) SmartWorkers.restSample(false, System.currentTimeMillis() - t0)
            throw e
        } finally {
            if (write) {
                passBook.drop()
                writes.incrementAndGet()
                // Jarvis's kept account figures are dropped too (an order, a change, a cancel or a GTT, from any screen,
                // a strategy or the guard): no answer is said from before it ([com.optionslab.app.ira.IraAccount.invalidate]).
                runCatching { com.optionslab.app.ira.IraAccount.invalidate() }
            }
        }
    }

    private suspend fun callInner(method: String, path: String, body: String?, auth: Boolean, raw: Boolean,
                                  json: Boolean, viaRelay: Boolean, readOnly: Boolean = false): Any {
        var attempt = 0
        // 16 Oct (Boss's diagnostics: "GET /portfolio/positions -> FAILED ... (105388 ms)"): a read had only per-socket limits
        // (20 s connect, 60 s read), which the HTTP stack's own silent retries and the 429 retries each started afresh. A read
        // now has one budget for the whole call ([readBudgetMs]); past it the connection is cut, and no 429 retry starts.
        val budget = readBudgetMs(method, path, readOnly, viaRelay)
        val began = System.currentTimeMillis()
        while (true) {
            // Orders and every other write go through the static-IP relay when it is on (it throws if it
            // cannot connect, so nothing leaves from another IP); reads go direct ([viaRelay]: a read that
            // warms the order route).
            val test = testEndpoint
            // One budget for every request from this phone (10 Oct): orders first, then the caller's lane. A wait suspends.
            if (test == null) {
                val p = if (method != "GET" && !readOnly) com.optionslab.ira.RateGate.Priority.SAFETY
                    else kotlinx.coroutines.currentCoroutineContext()[Lane]?.priority ?: com.optionslab.ira.RateGate.Priority.ENTRY
                rate.acquire(p)
            }
            val relay = if (((method != "GET" && !readOnly) || viaRelay) && test == null) Relay.proxy() else null
            val url = URL((test?.base ?: Kite.API) + path)
            val c = (if (relay != null) url.openConnection(relay) else url.openConnection()) as HttpsURLConnection
            // TCP_NODELAY on every socket (a small order body is sent at once, not held for Nagle); one wrapper per factory,
            // so the HTTP stack's connection pool (keyed on the factory) keeps reusing the same warm connection.
            c.sslSocketFactory = NoDelay.of(test?.ssl ?: com.optionslab.app.security.KitePin.socketFactory)
            // A read on the event-driven path ([FastRead]) gives up in 3 s, so one slow answer never holds the next look.
            val quick = method == "GET" && kotlinx.coroutines.currentCoroutineContext()[FastRead] != null
            // Speed: a reply read to its end leaves its connection (TCP + TLS, and through the relay its SSH
            // channel) in the HTTP stack's pool, so the next call - the order itself - skips the handshakes.
            // Any failure closes it. A POST is never re-sent on a pooled connection: its body is streamed at
            // a fixed length (below), which the stack cannot replay, and a non-GET gets a health check first.
            var reusable = false
            // The read's budget left: the connection is cut when it runs out (writes are never cut: an order is never half-sent).
            val left = budget?.let { (it - (System.currentTimeMillis() - began)).coerceAtLeast(1L) }
            val cut = left?.let { ms -> runCatching { cutter.schedule(Runnable { runCatching { c.disconnect() } }, ms, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull() }
            try {
                c.requestMethod = method
                c.connectTimeout = if (quick) FAST_READ_MS else if (budget != null) READ_SOCKET_MS else 20_000
                c.readTimeout = if (quick) FAST_READ_MS else if (budget != null) READ_SOCKET_MS else 60_000
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.setRequestProperty("X-Kite-Version", "3")
                c.setRequestProperty("User-Agent", "IraAlgo-Android")
                if (auth) { val tok = token(); c.setRequestProperty("Authorization", "token ${apiKey}:$tok") }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
                    // Streamed at a fixed length, the body cannot be replayed: neither the JVM's nor Android's
                    // connection code may then silently re-send an order when a reply is lost (a second order).
                    val bytes = body.toByteArray()
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
                val askedAt = System.currentTimeMillis()
                val code = c.responseCode
                // 16 Oct: the phone clock's offset against Zerodha's server, NTP-style (the round trip's middle vs the Date header).
                if (method == "GET" && code in 200..299) {
                    val gotAt = System.currentTimeMillis()
                    runCatching { c.getHeaderFieldDate("Date", 0L).takeIf { it > 0 }?.let { OrderTiming.serverClock(askedAt, gotAt, it / 1000) } }
                }
                if (code == 429 && attempt < 3 && retryFits(budget, System.currentTimeMillis() - began, 1_000L * (attempt + 1))) {
                    SmartWorkers.restLimited(); attempt++; delay(1_000L * attempt); continue
                }
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                reusable = true
                if (raw && code in 200..299) return text
                val json = runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("Zerodha returned an unreadable reply ($code)")
                if (json.optString("status") != "success") {
                    val type = json.optString("error_type", "Error")
                    // Kite's own message names the problem (margin, price band,
                    // freeze quantity); it carries no credential, so it is shown.
                    val msg = json.optString("message", "request failed").take(300)
                    if (type == "TokenException") {
                        // One request's TokenException is checked against the profile before the day's session is
                        // dropped: a single refused call (a glitch on one endpoint) no longer logs Boss out for the day.
                        // The reason is kept for the diagnostics (the path only, never its query; Kite's message has no key).
                        // Only the profile refused for its token ends the day: a network failure or a timeout on the
                        // check keeps the session (the glitch case), and a cancel is passed on, never read as an end.
                        val kept = path.substringBefore('?') != "/user/profile" && auth && try {
                            callInner("GET", "/user/profile", null, true, false, false, false); true
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: KiteError) { e.type != "TokenException"
                        } catch (_: Exception) { true }
                        Diag.record("info", "Zerodha TokenException on $method ${path.substringBefore('?')}: $msg" +
                            if (kept) " (the profile still answers: session kept)" else " (session ended)")
                        if (kept) throw KiteError(type, msg)
                        if (loggedIn) { dropSession(); sessionEnded.value++; endedNotice() }
                        throw KiteError(type, "Zerodha session ended: $msg")
                    }
                    // SEBI's static-IP rule: Zerodha takes orders only from an IP listed in the Kite Connect app.
                    if (msg.contains("No IPs configured", ignoreCase = true) || msg.contains("not allowed to place orders", ignoreCase = true))
                        throw KiteError(type, "Zerodha does not know your order IP yet. Add ${StaticIp.registered ?: "your static IP"} in developers.kite.trade → My apps → your app → IP whitelist, save, then try again. (Zerodha: $msg)")
                    throw KiteError(type, msg)
                }
                return json.opt("data") ?: JSONObject.NULL
            } catch (e: KiteError) {
                throw e
            } catch (e: NotLoggedIn) {
                throw e
            } catch (_: java.net.UnknownHostException) {
                throw IOException("No connection to Zerodha")
            } catch (_: java.net.SocketTimeoutException) {
                throw IOException("Zerodha did not answer in time")
            } catch (e: IOException) {
                if (com.optionslab.app.security.KitePin.mismatch) throw IOException(
                    "Refused: Zerodha's certificate chain no longer matches the one this phone pinned. On a network you trust, " +
                        "check api.kite.trade in a browser, then re-trust it under More → Security.")
                throw IOException("Could not reach Zerodha")
            } finally {
                cut?.cancel(false)
                if (!reusable) c.disconnect()
                if (relay != null) relayUsedAt = System.currentTimeMillis()
            }
        }
    }

    /** A read's whole budget (16 Oct): connect, wait, retries and reply together. */
    const val READ_BUDGET_MS = 5_000L
    /** Each socket step of a budgeted read (connect, each read) gives up after this. */
    const val READ_SOCKET_MS = 5_000
    /** The big downloads keep their old limits: the instrument list (megabytes) and the historical candles. */
    private val UNBUDGETED = listOf("/instruments")

    /**
     * The budget of one call (null: not cut - every write, which must never be half-sent, the big downloads, and a read that
     * warms the order route through the relay, whose SSH connect alone can take seconds). A plain read, or a POST that only
     * reads, gets [READ_BUDGET_MS]. The event-driven reads keep their own 3 s socket limits inside it.
     */
    internal fun readBudgetMs(method: String, path: String, readOnly: Boolean, viaRelay: Boolean = false): Long? {
        if (method != "GET" && !readOnly) return null
        if (viaRelay) return null
        if (UNBUDGETED.any { path.startsWith(it) }) return null
        return READ_BUDGET_MS
    }

    /** A 429 retry after [waitMs] fits the read's budget (no budget: always, as before). */
    internal fun retryFits(budget: Long?, spentMs: Long, waitMs: Long): Boolean = budget == null || spentMs + waitMs < budget

    /** Cuts a read's connection when its budget is spent (one small thread for the whole app). */
    private val cutter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "zerodha-cut").apply { isDaemon = true } }

    @Volatile private var warmedAt = 0L
    private val warmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Get the order route ready while the owner is still reviewing (or, for the bots, during market hours):
     * the relay's SSH session and a pooled TLS connection to Zerodha through it, and the static-IP reading
     * an entry is checked against. Only removes waiting: every check at send still runs. Returns at once
     * (the work runs in the background), never throws, and does something at most every [WARM_GAP_MS] -
     * [force] (a bar close the armed arms decide on is seconds away) at most every [FORCED_WARM_MS].
     * The relay's read is timed for the order-speed card ([OrderTiming.relayPing]).
     */
    fun warmOrderRoute(entry: Boolean = true, force: Boolean = false, gapMs: Long? = null) {
        val now = System.currentTimeMillis()
        if (now - warmedAt in 0 until (gapMs ?: if (force) FORCED_WARM_MS else WARM_GAP_MS)) return
        warmedAt = now
        if (testEndpoint != null) return            // JVM tests: the fake Kite needs no warming, and nothing may reach the internet
        // Zerodha's address looked up ahead (the system keeps it), so the order's own connection skips the DNS wait.
        warmScope.launch { runCatching { java.net.InetAddress.getAllByName(com.optionslab.app.security.KitePin.HOST) } }
        if (entry && StaticIp.registered != null) warmScope.launch { runCatching { StaticIp.current(maxAgeMs = 30_000) } }
        // Without the relay, the review's own reads have already pooled the connection the order uses.
        if (Relay.enabled && loggedIn) warmScope.launch {
            // Cold: nothing went through the relay for 4 minutes (its pooled connection is likely gone: handshakes again).
            val cold = System.currentTimeMillis() - relayUsedAt > 240_000
            val t0 = System.currentTimeMillis()
            runCatching { call("GET", "/user/profile", viaRelay = true) }
                .onSuccess { runCatching { OrderTiming.relayPing(System.currentTimeMillis() - t0, cold) } }
        }
    }

    /** The order route is warmed at most this often (Zerodha's profile read through the relay, in market hours). */
    const val WARM_GAP_MS = 60_000L
    private const val FORCED_WARM_MS = 5_000L

    /** When a request last went through the relay (cold or warm, for the order-speed card). */
    @Volatile private var relayUsedAt = 0L
    @Volatile private var directAt = 0L

    /**
     * The same cheap read as the relay's, straight to Zerodha (reads never use the relay): the network's own round trip,
     * beside the relay's, for the order-speed card. At most once a minute, in the background; never an order, never the
     * relay. Returns at once, never throws.
     */
    fun pingDirect() {
        val now = System.currentTimeMillis()
        if (now - directAt in 0 until WARM_GAP_MS) return
        directAt = now
        if (testEndpoint != null || !loggedIn) return
        warmScope.launch {
            val t0 = System.currentTimeMillis()
            runCatching { call("GET", "/user/profile") }.onSuccess { runCatching { OrderTiming.directPing(System.currentTimeMillis() - t0) } }
        }
    }

    // ---- login --------------------------------------------------------------------

    fun loginUrl(): String = Kite.loginUrl(apiKey ?: error("API key not set"))

    /**
     * Open the sealed API secret with the owner's (verified) PIN, for one login.
     * A secret saved before sealing existed is sealed now and the readable copy removed.
     */
    fun unsealSecret(pin: CharArray): String? {
        SecurePrefs.getString(K_SEALED)?.let { blob ->
            val secret = com.optionslab.app.security.SecretBox.open(blob, pin) ?: return null
            // An older (v1) seal is re-sealed in the device-bound format now that the PIN has opened it.
            runCatching { com.optionslab.app.security.SecretBox.upgrade(blob, pin, secret)?.let { SecurePrefs.put(K_SEALED, it) } }
            return secret
        }
        val legacy = SecurePrefs.getString(K_SECRET) ?: return null
        SecurePrefs.putAll(mapOf(K_SEALED to com.optionslab.app.security.SecretBox.seal(legacy, pin), K_SECRET to null))
        return legacy
    }

    /**
     * The PIN changed: the API secret sealed under [newPin], as the settings to write together with the new PIN ([com.optionslab.app.security.PinLock.setPin]'s
     * `also`), or nothing when no PIN-sealed secret opens with [oldPin]. 18 Oct, review: it used to be written after the PIN
     * changed, so a failed write left the secret sealed under the old PIN (and crashed the app); now both go in one write.
     */
    fun resealedSecret(oldPin: CharArray, newPin: CharArray): Map<String, Any?> {
        val secret = unsealSecret(oldPin) ?: return emptyMap()
        return mapOf(K_SEALED to com.optionslab.app.security.SecretBox.seal(secret, newPin))
    }

    /** Exchange the request token for the day's access token (kite_login.exchange_token). */
    suspend fun completeLogin(requestToken: String, secret: String): String {
        val key = apiKey ?: error("API key not set")
        val data = call("POST", "/session/token",
            Kite.form(listOf("api_key" to key, "request_token" to requestToken, "checksum" to Kite.checksum(key, requestToken, secret))),
            auth = false) as JSONObject
        val token = data.optString("access_token")
        if (token.isBlank()) throw IOException("Zerodha returned no access token")
        SecurePrefs.putAll(mapOf(K_TOKEN to token, K_LOGIN_AT to System.currentTimeMillis(),
            K_USER to data.optString("user_name"), K_UID to data.optString("user_id")))
        dropCreds(); dropKept()
        // 18 Oct (review): a paper-only period the start could not read is read now (this is a worker thread, never the order
        // path), so the day's live entries are not refused for want of it.
        runCatching { PaperOnly.ensureLoaded() }
        return data.optString("user_name", "you")
    }

    suspend fun logout() {
        KiteStream.stop()
        val key = apiKey
        val tok = SecurePrefs.getString(K_TOKEN)
        if (key != null && tok != null) runCatching {
            call("DELETE", "/session/token?api_key=${Kite.enc(key)}&access_token=${Kite.enc(tok)}")
        }
        dropSession()
    }

    // ---- account --------------------------------------------------------------------

    data class Funds(val available: Double, val used: Double, val net: Double,
                     val openingBalance: Double = 0.0, val collateral: Double = 0.0, val span: Double = 0.0,
                     val exposure: Double = 0.0, val optionPremium: Double = 0.0, val realised: Double = 0.0, val m2m: Double = 0.0)

    /** One row of GET /portfolio/positions (net or day book). */
    data class Position(
        val symbol: String, val product: String, val qty: Int, val avg: Double, val last: Double, val pnl: Double,
        val exchange: String = "NFO", val token: Long = 0L, val overnight: Int = 0, val multiplier: Double = 1.0,
        val m2m: Double = 0.0, val realised: Double = 0.0, val unrealised: Double = 0.0, val close: Double = 0.0,
        val buyQty: Int = 0, val buyAvg: Double = 0.0, val sellQty: Int = 0, val sellAvg: Double = 0.0,
        /** 21 Oct, round 3: Kite's day_buy_quantity / day_sell_quantity (today's only); null when the read did not give them. */
        val dayBuyQty: Int? = null, val daySellQty: Int? = null,
    ) {
        val open: Boolean get() = qty != 0
    }

    data class Positions(val net: List<Position>, val day: List<Position>) {
        val pnl: Double get() = net.sumOf { it.pnl }
        val m2m: Double get() = net.sumOf { it.m2m }
        val realised: Double get() = net.sumOf { it.realised }
        val unrealised: Double get() = net.sumOf { it.unrealised }
    }

    data class OrderRow(val id: String, val symbol: String, val side: String, val qty: Int, val filled: Int, val price: Double,
                        val avg: Double, val status: String, val message: String, val placedAt: String,
                        val exchange: String = "NFO", val product: String = "", val type: String = "LIMIT", val trigger: Double = 0.0,
                        val variety: String = "regular", val pending: Int = 0, val tag: String = "") {
        /** Still working at the exchange: can be modified or cancelled. */
        val working: Boolean get() = status in WORKING
    }

    data class Trade(val id: String, val orderId: String, val symbol: String, val exchange: String, val side: String,
                     val qty: Int, val price: Double, val product: String, val at: String)

    data class Holding(val symbol: String, val exchange: String, val isin: String, val qty: Int, val t1: Int, val avg: Double,
                       val last: Double, val close: Double, val pnl: Double, val dayChange: Double, val dayChangePct: Double,
                       val product: String = "CNC") {
        val invested: Double get() = avg * (qty + t1)
        val value: Double get() = last * (qty + t1)
    }

    private val WORKING = setOf("OPEN", "TRIGGER PENDING", "AMO REQ RECEIVED", "OPEN PENDING", "VALIDATION PENDING", "PUT ORDER REQ RECEIVED", "MODIFY PENDING")

    suspend fun funds(): Funds {
        // Workers asking at once share one read ([SHARE_MS]); kept and published with when that read began.
        val r = fundsShared.run(shareKey(writes.get()), SHARE_MS, shareContext()) {
            val eq = (call("GET", "/user/margins") as JSONObject).getJSONObject("equity")
            val a = eq.optJSONObject("available") ?: JSONObject()
            val u = eq.optJSONObject("utilised") ?: JSONObject()
            Funds(a.optDouble("live_balance", a.optDouble("cash", 0.0)), u.optDouble("debits", 0.0), eq.optDouble("net", 0.0),
                a.optDouble("opening_balance", 0.0), a.optDouble("collateral", 0.0), u.optDouble("span", 0.0), u.optDouble("exposure", 0.0),
                u.optDouble("option_premium", 0.0), u.optDouble("m2m_realised", 0.0), u.optDouble("m2m_unrealised", 0.0))
        }
        fundsKept = r.startedAt to r.value
        runCatching { AppState.publishFunds(r.value, r.startedAt) }
        return r.value
    }

    // ---- kept reads for the hot path (round 2) ---------------------------------------------------------------

    /** The last margins read and when (memory only): the live entry's margin check ([accountNow] with a need). */
    @Volatile private var fundsKept: Pair<Long, Funds>? = null

    /** The kept margins as the pure check sees them (the free margin the guard compares with: equity net). */
    private fun marginSnap(): com.optionslab.ira.MarginCache.Snapshot? =
        fundsKept?.let { (at, f) -> com.optionslab.ira.MarginCache.Snapshot(f.net, at) }

    /**
     * Keep the margins snapshot fresh: read again in the background when it is [com.optionslab.ira.MarginCache.REFRESH_MS]
     * old (at most every 30 s), only in market hours with a session. Returns at once, never throws, never on the caller's thread.
     */
    fun refreshMarginsIfDue() {
        if (testEndpoint == null && !Market.isOpen()) return
        if (!loggedIn || !com.optionslab.ira.MarginCache.due(marginSnap(), System.currentTimeMillis())) return
        if (!marginRefreshing.compareAndSet(false, true)) return
        warmScope.launch { try { runCatching { funds() } } finally { marginRefreshing.set(false) } }
    }
    private val marginRefreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Zerodha's orders and positions as last read, for the event-driven exits' looks between the 15-second passes: a stop,
     * target or trail then decides from the stream's price and these, and only an order goes over REST. Used only while the
     * read is under [RECON_MS] old, no write went to Zerodha since ([writes]: every order, change or cancel from this phone
     * bumps it) and the price stream is live (Zerodha's order updates on it bump it too, from Kite web or a stop filling).
     * Anything else reads Zerodha, as before. Memory only.
     */
    private class Kept<T>(val at: Long, val gen: Long, val value: T)
    private val writes = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var keptOrders: Kept<List<OrderRow>>? = null
    @Volatile private var keptBook: Kept<Positions>? = null
    const val RECON_MS = 20_000L

    /** An order changed at Zerodha (the stream said so): the kept orders and positions are not used again. */
    fun reconStale() { writes.incrementAndGet() }

    private fun dropKept() { keptOrders = null; keptBook = null; fundsKept = null; killKept = null; writes.incrementAndGet() }

    /** TEST ONLY: nothing kept in memory from an earlier test (the session, the kept reads, today's count). */
    internal fun resetForTest() { dropCreds(); dropKept(); sentKept = null; sentIds.clear(); paperOnlyNoticeDay = null }

    private fun <T> keptValue(k: Kept<T>?): T? = k?.takeIf {
        it.gen == writes.get() && System.currentTimeMillis() - it.at in 0..RECON_MS && KiteStream.status.value == KiteStream.Status.LIVE
    }?.value

    /** [orders], or the kept read when it may stand for it (see [Kept]). */
    suspend fun ordersRecon(): List<OrderRow> = keptValue(keptOrders) ?: orders()

    /** [positionBook], or the kept read when it may stand for it (see [Kept]). */
    suspend fun positionsRecon(): Positions = keptValue(keptBook) ?: positionBook()

    /** Marks a read on the event-driven path: Zerodha reads under it give up in [FAST_READ_MS]. */
    class FastRead : kotlin.coroutines.AbstractCoroutineContextElement(FastRead) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<FastRead>
    }
    const val FAST_READ_MS = 3_000

    /** TCP_NODELAY on each socket Zerodha's calls make; one wrapper per underlying factory (the connection pool's key). */
    private object NoDelay {
        @Volatile private var kept: Pair<javax.net.ssl.SSLSocketFactory, javax.net.ssl.SSLSocketFactory>? = null
        fun of(f: javax.net.ssl.SSLSocketFactory): javax.net.ssl.SSLSocketFactory {
            kept?.let { if (it.first === f) return it.second }
            return Wrap(f).also { kept = f to it }
        }
        private class Wrap(private val d: javax.net.ssl.SSLSocketFactory) : javax.net.ssl.SSLSocketFactory() {
            private fun <T : java.net.Socket> nd(s: T): T { runCatching { s.tcpNoDelay = true }; return s }
            override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
            override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean): java.net.Socket =
                nd(d.createSocket(s, host, port, autoClose))
            override fun createSocket(): java.net.Socket = nd(d.createSocket())
            override fun createSocket(host: String?, port: Int): java.net.Socket = nd(d.createSocket(host, port))
            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket =
                nd(d.createSocket(host, port, localHost, localPort))
            override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket = nd(d.createSocket(host, port))
            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket =
                nd(d.createSocket(address, port, localAddress, localPort))
        }
    }

    private fun position(it: JSONObject) = Position(
        it.optString("tradingsymbol"), it.optString("product"), it.optInt("quantity"), it.optDouble("average_price", 0.0),
        it.optDouble("last_price", 0.0), it.optDouble("pnl", 0.0), it.optString("exchange", "NFO"), it.optLong("instrument_token"),
        it.optInt("overnight_quantity"), it.optDouble("multiplier", 1.0), it.optDouble("m2m", 0.0), it.optDouble("realised", 0.0),
        it.optDouble("unrealised", 0.0), it.optDouble("close_price", 0.0), it.optInt("buy_quantity"), it.optDouble("buy_price", 0.0),
        it.optInt("sell_quantity"), it.optDouble("sell_price", 0.0),
        dayQty(it, "day_buy_quantity"), dayQty(it, "day_sell_quantity"))

    /** 21 Oct, round 3: a day quantity Zerodha gave (a number); absent or null is "not known", never 0 (review). */
    internal fun dayQty(o: JSONObject, k: String): Int? = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null

    private fun rows(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }

    suspend fun positionBook(): Positions {
        // Always read fresh (stops, targets, the loss limit and the bots rely on it); within a market-watch pass the
        // answer is also kept for the words-only checks after it ([passPositionBook]) and the event-driven exits ([positionsRecon]).
        val ticket = passBook.ticket()
        val at = passBook.now()
        val gen = writes.get()
        // Workers asking at once share one read (10 Oct, [SHARE_MS]): never one begun before an order, change or cancel.
        val r = positionsShared.run(shareKey(gen), SHARE_MS, shareContext()) {
            val d = call("GET", "/portfolio/positions") as JSONObject
            Positions(rows(d.optJSONArray("net")).map(::position), rows(d.optJSONArray("day")).map(::position))
        }
        passBook.put(ticket, at, r.value)
        keptBook = Kept(r.startedAt, gen, r.value)
        // 19 Oct: the same-direction cap's live count follows what Zerodha holds (a stop that filled there closes its entry).
        ExposureCapHost.livePositions(r.value.net.groupBy { it.symbol }.mapValues { e -> e.value.sumOf { it.qty } }, r.startedAt)
        runCatching { AppState.publishPositions(r.value, r.startedAt) }
        return r.value
    }

    /** The market watch opens and closes its pass: within it, [passPositionBook] reads Zerodha at most once. */
    fun passBegin() = passBook.open()
    fun passEnd() = passBook.close()

    /**
     * Positions for words only - the Jarvis heads-up, trades going nowhere, the day's target, the plan, the 15:10 MIS
     * word and the watch notice's P&L line; never for anything that places, changes or closes an order. Within a
     * market-watch pass this is the pass's one read (at most [PASS_BOOK_MS] old; any write to Zerodha or a session end
     * drops it); outside a pass, and when there is none yet, Zerodha is read now. A failed read is never kept.
     */
    suspend fun passPositionBook(): Positions = passBook.share { positionBook() }

    suspend fun positions(): List<Position> = positionBook().net

    suspend fun holdings(): List<Holding> = rows(call("GET", "/portfolio/holdings") as? JSONArray).map {
        Holding(it.optString("tradingsymbol"), it.optString("exchange", "NSE"), it.optString("isin"), it.optInt("quantity"),
            it.optInt("t1_quantity"), it.optDouble("average_price", 0.0), it.optDouble("last_price", 0.0), it.optDouble("close_price", 0.0),
            it.optDouble("pnl", 0.0), it.optDouble("day_change", 0.0), it.optDouble("day_change_percentage", 0.0), it.optString("product", "CNC"))
    }.sortedBy { it.symbol }

    suspend fun trades(): List<Trade> = rows(call("GET", "/trades") as? JSONArray).map {
        Trade(it.optString("trade_id"), it.optString("order_id"), it.optString("tradingsymbol"), it.optString("exchange"),
            it.optString("transaction_type"), it.optInt("quantity"), it.optDouble("average_price", 0.0), it.optString("product"),
            it.optString("fill_timestamp", it.optString("exchange_timestamp", "")))
    }.sortedByDescending { it.at }

    suspend fun orders(): List<OrderRow> {
        val gen = writes.get()
        // Workers asking at once share one read (10 Oct, [SHARE_MS]): never one begun before an order, change or cancel.
        val r = ordersShared.run(shareKey(gen), SHARE_MS, shareContext()) {
            rows(call("GET", "/orders") as JSONArray).map(::orderRow).sortedByDescending { it.placedAt }
        }
        val list = r.value
        // 20 Oct, enhancement round 2: the exits sent from here that this read shows are ticked off (memory only).
        ExitCoordinator.ordersRead(list)
        // A resting stop that filled between the stream's updates is measured from the book (memory only; most reads skip it).
        runCatching { if (FillSlippage.anyWaiting()) list.forEach { if (FillSlippage.waiting(it.id)) FillSlippage.ended(it.id, it.status, it.avg, it.filled) } }
        keptOrders = Kept(r.startedAt, gen, list)
        runCatching { AppState.publishOrders(list, r.startedAt) }
        // The "Open" widget's pending orders come from the reads already made here, handed to its own thread so this
        // (an order path) never waits on its vault write or the launcher.
        runCatching { com.optionslab.app.widget.OpenWidget.fromOrdersSoon(app, list) }
        return list
    }

    private fun orderRow(o: JSONObject) = OrderRow(o.optString("order_id"), o.optString("tradingsymbol"), o.optString("transaction_type"),
        o.optInt("quantity"), o.optInt("filled_quantity"), o.optDouble("price", 0.0), o.optDouble("average_price", 0.0),
        o.optString("status"), o.optString("status_message", "").let { if (it == "null") "" else it }, o.optString("order_timestamp", ""),
        o.optString("exchange", "NFO"), o.optString("product"), o.optString("order_type", "LIMIT"), o.optDouble("trigger_price", 0.0),
        o.optString("variety", "regular"), o.optInt("pending_quantity"), o.optString("tag", "").let { if (it == "null") "" else it })

    // ---- market data ----------------------------------------------------------------

    private val INDEX = mapOf("NIFTY" to ("NSE:NIFTY 50" to 256265L), "BANKNIFTY" to ("NSE:NIFTY BANK" to 260105L),
        "INDIAVIX" to ("NSE:INDIA VIX" to 264969L), "FINNIFTY" to ("NSE:NIFTY FIN SERVICE" to 257801L),
        "MIDCPNIFTY" to ("NSE:NIFTY MID SELECT" to 288009L), "SENSEX" to ("BSE:SENSEX" to 265L))

    data class Quote(val last: Double, val bid: Double?, val ask: Double?, val open: Double, val oi: Long = 0, val volume: Long = 0)

    /** "NSE:NIFTY BANK" / "NFO:BANKNIFTY26OCT56000CE" -> Kite instrument token, from the day's list. */
    @Volatile private var tokenMap: Pair<LocalDate, Map<String, Long>>? = null
    fun tokenOf(key: String): Long? {
        INDEX.values.firstOrNull { it.first == key }?.let { return it.second }
        // MCX: from the day's MCX list ([McxMarket]), so its contracts stream too.
        if (key.startsWith("MCX:")) return McxMarket.tokenOf(key.removePrefix("MCX:"))
        // 20 Oct, review: SENSEX's options (BFO) from today's BFO list in memory, so a live SENSEX trade's exits stream too.
        if (key.startsWith("BFO:")) return bfoToken(key)
        if (!key.startsWith("NFO:")) return null
        val m = tokenMap?.takeIf { it.first == Market.today() }?.second
            ?: cachedInstruments()?.associate { "NFO:" + it.tradingSymbol to it.token }?.also { tokenMap = Market.today() to it }
            ?: return null
        return m[key]
    }

    /**
     * [tokenOf] only from what is already in memory (never builds the day's map or reads a file): for timing an order and the
     * trade manager's looks.
     */
    internal fun tokenKnown(key: String): Long? =
        INDEX.values.firstOrNull { it.first == key }?.second ?: if (key.startsWith("BFO:")) bfoToken(key)
        // 20 Oct, second review: MCX from its list in memory (a live MCX trade's exits stream again; never the file here).
        else if (key.startsWith("MCX:")) McxMarket.tokenKnown(key.removePrefix("MCX:"))
        else tokenMap?.takeIf { it.first == Market.today() }?.second?.get(key)

    /** "BFO:<symbol>" -> its token from today's BFO list in memory (never a file or the network); the map built once per list. */
    @Volatile private var bfoTokens: Pair<List<Kite.Instrument>, Map<String, Long>>? = null
    private fun bfoToken(key: String): Long? {
        val list = cachedBfoOptions() ?: return null
        val m = bfoTokens?.takeIf { it.first === list }?.second
            ?: list.associate { "BFO:" + it.tradingSymbol to it.token }.also { bfoTokens = list to it }
        return m[key]
    }

    /**
     * Quotes for "EXCHANGE:SYMBOL" keys. Instruments the live stream has a fresh tick for
     * are answered from it at once; only the rest go to Kite's quote API (and are
     * followed by the stream from then on).
     */
    suspend fun quotes(keys: List<String>, keepStream: Boolean = true): Map<String, Quote> {
        if (keys.isEmpty()) return emptyMap()
        val tokens = keys.associateWith { tokenOf(it) }
        // [keepStream] false (Battery, 14 Oct: the MCX price reads with nothing held on MCX): answered from the stream if it
        // runs, else by the quote call, without keeping the stream awake for these instruments.
        if (keepStream) KiteStream.touch(tokens.values.filterNotNull())
        val streamed = keys.mapNotNull { k -> tokens[k]?.let { KiteStream.tick(it) }?.let { t -> k to Quote(t.last, t.bid, t.ask, t.open, t.oi, t.volume) } }.toMap()
        val rest = keys.filter { it !in streamed }
        if (rest.isEmpty()) return streamed
        return streamed + quotesRest(rest)
    }

    /**
     * 18 Oct (speed review): the prices a stop, target or profit lock is judged on. Each from the stream's tick while it is
     * [com.optionslab.ira.LiveQuote.FRESH_MS] (2 s) fresh, the rest from Zerodha's quote call itself. [quotes] answers from
     * a tick up to 5 s old, and a stream gone quiet is called stalled only after 10 s: a stop was judged on a price 2-5 s
     * old, with no REST read, as the "fallback" of a look whose ticks were not fresh. Kept followed on the stream. When the
     * quote call fails (a 429, no network), the rest are answered from a tick up to 5 s old as before, and the fresh ones are
     * kept: a failed read never leaves every held position unpriced for that look.
     */
    suspend fun stopQuotes(keys: List<String>): Map<String, Quote> {
        if (keys.isEmpty()) return emptyMap()
        val tokens = keys.associateWith { tokenOf(it) }
        KiteStream.touch(tokens.values.filterNotNull())
        val streamed = keys.mapNotNull { k ->
            tokens[k]?.let { KiteStream.freshTick(it) }?.takeIf { it.last > 0 }?.let { t -> k to Quote(t.last, t.bid, t.ask, t.open, t.oi, t.volume) }
        }.toMap()
        val rest = keys.filter { it !in streamed }
        if (rest.isEmpty()) return streamed
        val read = try { quotesRest(rest) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val older = rest.mapNotNull { k -> tokens[k]?.let { KiteStream.tick(it) }?.takeIf { it.last > 0 }?.let { t -> k to Quote(t.last, t.bid, t.ask, t.open, t.oi, t.volume) } }.toMap()
            if (streamed.isEmpty() && older.isEmpty()) throw e
            older
        }
        return streamed + read
    }

    /** Zerodha's quote call itself, never the stream's memory (a paper fill's re-read when no stream tick is 2 s fresh, 16 Oct). */
    suspend fun quotesFresh(keys: List<String>): Map<String, Quote> = if (keys.isEmpty()) emptyMap() else quotesRest(keys)

    private suspend fun quotesRest(keys: List<String>): Map<String, Quote> {
        val data = call("GET", "/quote?" + keys.joinToString("&") { "i=" + Kite.enc(it) }) as JSONObject
        return keys.mapNotNull { k ->
            val q = data.optJSONObject(k) ?: return@mapNotNull null
            val depth = q.optJSONObject("depth")
            fun best(side: String) = depth?.optJSONArray(side)?.optJSONObject(0)?.optDouble("price")?.takeIf { it > 0 }
            k to Quote(q.optDouble("last_price"), best("buy"), best("sell"), q.optJSONObject("ohlc")?.optDouble("open") ?: 0.0,
                q.optLong("oi", 0), q.optLong("volume", 0))
        }.toMap()
    }

    /**
     * Last price and previous close for "EXCHANGE:SYMBOL" keys, from Kite's OHLC quote (500 keys a call): the Night (R3)
     * arm's breadth ([com.optionslab.engine.orb.NightRules.breadth]). Reads only; a key Kite does not know is left out.
     */
    suspend fun lastAndPrevClose(keys: List<String>): Map<String, Pair<Double, Double>> {
        val out = HashMap<String, Pair<Double, Double>>()
        for (chunk in keys.chunked(500)) {
            val data = call("GET", "/quote/ohlc?" + chunk.joinToString("&") { "i=" + Kite.enc(it) }) as JSONObject
            for (k in chunk) {
                val q = data.optJSONObject(k) ?: continue
                val pc = q.optJSONObject("ohlc")?.optDouble("close") ?: continue
                out[k] = q.optDouble("last_price") to pc
            }
        }
        return out
    }

    /** Kite's quote key for an index by its IraAlgo name ("NIFTY" -> "NSE:NIFTY 50"), null for one Kite has no index of. */
    fun indexKey(symbol: String): String? = INDEX[symbol]?.first

    /**
     * The market recorder's read ([MarketRecorder]): every key's last price, volume, OI, best bid and offer with their
     * quantities and the book's totals. A key with a fresh full-mode tick on the live stream is answered from it (no
     * network); the rest in ONE quote call per 500 keys. Unlike [quotes] it never adds anything to the stream's follow
     * list (so the recorder alone never keeps the stream, and the phone's radio, busy). Reads only.
     */
    suspend fun recorderQuotes(keys: Map<String, Long?>): Map<String, com.optionslab.ira.MarketRecord.Quote> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<String, com.optionslab.ira.MarketRecord.Quote>()
        for ((k, token) in keys) {
            val t = (token ?: tokenOf(k))?.let { KiteStream.tick(it) } ?: continue
            if (t.bid == null && t.ask == null && t.tradable) continue          // no depth on this tick: the quote call has it
            out[k] = com.optionslab.ira.MarketRecord.Quote(t.last, t.volume.takeIf { t.tradable }, t.oi.takeIf { t.tradable }, t.bid, t.bidQty,
                t.ask, t.askQty, t.buyQty, t.sellQty, stream = true)
        }
        for (chunk in keys.keys.filter { it !in out }.chunked(500)) {
            val data = call("GET", "/quote?" + chunk.joinToString("&") { "i=" + Kite.enc(it) }) as JSONObject
            for (k in chunk) {
                val q = data.optJSONObject(k) ?: continue
                val depth = q.optJSONObject("depth")
                fun top(side: String) = depth?.optJSONArray(side)?.optJSONObject(0)
                fun px(side: String) = top(side)?.optDouble("price")?.takeIf { it > 0 }
                fun qty(side: String) = top(side)?.optLong("quantity")?.takeIf { px(side) != null }
                fun long(name: String) = if (q.has(name)) q.optLong(name) else null
                out[k] = com.optionslab.ira.MarketRecord.Quote(q.optDouble("last_price"), long("volume"), long("oi"), px("buy"), qty("buy"),
                    px("sell"), qty("sell"), long("buy_quantity"), long("sell_quantity"))
            }
        }
        return out
    }

    /** The index futures the market recorder follows, by name. */
    val FUTURE_NAMES = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    private fun futuresFile() = File(app.filesDir, "kite_futures.json")

    /** The futures kept: (the day NFO's were read, the day BFO's were read, the list). */
    private fun futuresKept(): Triple<String, String, List<Kite.Future>> = runCatching {
        val o = JSONObject(futuresFile().readText())
        val a = o.getJSONArray("f")
        Triple(o.optString("nfo"), o.optString("bfo"), (0 until a.length()).map { a.getJSONArray(it) }.map {
            Kite.Future(it.getLong(0), it.getString(1), it.getString(2), LocalDate.parse(it.getString(3)), it.getInt(4), it.getString(5))
        })
    }.getOrDefault(Triple("", "", emptyList()))

    private fun keepFutures(nfoDay: String, bfoDay: String, list: List<Kite.Future>) {
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.token).put(it.tradingSymbol).put(it.name).put(it.expiry.toString()).put(it.lotSize).put(it.exchange)) }
        writeAtomic(futuresFile(), JSONObject().put("nfo", nfoDay).put("bfo", bfoDay).put("f", a).toString())
    }

    /** NFO's futures from the day's instruments dump [instruments] has just downloaded (no second download for them). */
    private fun keepNfoFutures(text: String) {
        val (_, bfoDay, kept) = futuresKept()
        val nfo = Kite.parseFutures(text.lineSequence(), FUTURE_NAMES)
        keepFutures(Market.today().toString(), bfoDay, nfo + kept.filter { it.exchange == "BFO" })
    }

    @Volatile private var futuresTriedAt = 0L

    /**
     * Today's index futures (NFO's NIFTY, BANKNIFTY, FINNIFTY; BFO's SENSEX), for the market recorder. NFO's come with the
     * day's instruments dump; BFO's dump is read once a day. A failed read is tried again after 30 minutes at most.
     */
    suspend fun futures(): List<Kite.Future> {
        val today = Market.today().toString()
        val (nfoDay, bfoDay, kept) = futuresKept()
        if (nfoDay == today && bfoDay == today) return kept
        val now = System.currentTimeMillis()
        if (now - futuresTriedAt < 30 * 60_000L) return if (nfoDay == today) kept else emptyList()
        futuresTriedAt = now
        val nfo: List<Kite.Future> = if (nfoDay == today) kept.filter { it.exchange == "NFO" } else {
            // The day's options list first: not downloaded yet today, its dump carries the futures too.
            runCatching { instruments() }
            val again = futuresKept()
            if (again.first == today) again.third.filter { it.exchange == "NFO" }
            else Kite.parseFutures((call("GET", "/instruments/NFO", raw = true) as String).lineSequence(), FUTURE_NAMES)
        }
        val bfo = if (bfoDay == today) kept.filter { it.exchange == "BFO" }
            else runCatching { Kite.parseFutures((call("GET", "/instruments/BFO", raw = true) as String).lineSequence(), setOf("SENSEX")) }.getOrNull()
        val all = nfo + bfo.orEmpty()
        keepFutures(today, if (bfo != null) today else bfoDay, all)
        return all
    }

    private val KITE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

    /**
     * 1-minute candles with OI (needs a plan that includes historical data). [from] / [to]: the session's window (NSE's
     * 09:15 to F&O's close of [day] unless asked - 15:40 from 3 Aug 2026, so a future's or an option's last 10 minutes are
     * in; an index's candles end 15:29 either way; MCX's 09:00-23:59).
     */
    suspend fun minuteBars(token: Long, day: LocalDate, from: String = "09:15:00",
                           to: String = "${com.optionslab.engine.NseHours.foCloseText(day)}:00"): List<Upstox.Bar> {
        val q = "from=${Kite.enc("$day $from")}&to=${Kite.enc("$day $to")}&oi=1"
        val candles = (call("GET", "/instruments/historical/$token/minute?$q") as JSONObject).optJSONArray("candles") ?: JSONArray()
        return (0 until candles.length()).map { candles.getJSONArray(it) }.map { r ->
            val ts = r.getString(0)
            val epoch = runCatching { OffsetDateTime.parse(ts, KITE_TS) }.getOrElse { OffsetDateTime.parse(ts) }.toEpochSecond()
            Upstox.Bar(epoch, r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.optLong(5), if (r.length() > 6) r.optLong(6) else 0L)
        }
    }

    /**
     * Zerodha's MCX instrument list (GET /instruments/MCX, CSV): public, so it is read without the login when there is none
     * ([McxMarket.contracts]). Reads only.
     */
    suspend fun mcxInstrumentsText(): String = call("GET", "/instruments/MCX", auth = loggedIn, raw = true) as String

    /** Zerodha's public commodity margin list (JSON: each contract's NRML and MIS margin per lot). Reads only, no login. */
    suspend fun mcxMarginsText(): String = call("GET", "/margins/commodity", auth = false, raw = true) as String

    /** Instrument token of an index Kite knows by its IraAlgo name (NIFTY, BANKNIFTY, INDIAVIX). */
    fun indexToken(symbol: String): Long? = INDEX[symbol]?.second

    /**
     * Daily candles (needs Kite's historical-data add-on); Kite serves about 2000 days a call. [continuous]: a future's
     * continuous near-month series (Kite's continuous=1; the MCX trend arm's 12-month history), not roll-adjusted. Reads only.
     */
    suspend fun dailyBars(token: Long, from: LocalDate, to: LocalDate, continuous: Boolean = false): List<Upstox.Bar> {
        val out = ArrayList<Upstox.Bar>()
        var lo = from
        while (!lo.isAfter(to)) {
            val hi = minOf(lo.plusDays(1900), to)
            val q = "from=${Kite.enc("$lo 00:00:00")}&to=${Kite.enc("$hi 23:59:59")}" + (if (continuous) "&continuous=1" else "")
            val candles = (call("GET", "/instruments/historical/$token/day?$q") as JSONObject).optJSONArray("candles") ?: JSONArray()
            for (i in 0 until candles.length()) {
                val r = candles.getJSONArray(i)
                val ts = r.getString(0)
                val epoch = runCatching { OffsetDateTime.parse(ts, KITE_TS) }.getOrElse { OffsetDateTime.parse(ts) }.toEpochSecond()
                out += Upstox.Bar(epoch, r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.optLong(5), 0L)
            }
            lo = hi.plusDays(1)
            delay(340)
        }
        return out.distinctBy { it.epochSecond }.sortedBy { it.epochSecond }
    }

    /** The index's 1-minute bars (needs Kite's historical data), e.g. for settlement. */
    suspend fun indexMinuteBars(symbol: String, day: LocalDate): List<Upstox.Bar> =
        minuteBars(INDEX[symbol]?.second ?: throw IOException("no Zerodha index for $symbol"), day)

    private val sparks = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, List<Double>>>()

    /**
     * [symbol]'s level from Zerodha. [spark] false (the order watch: it reads only the last price and the change from
     * the open): the quote alone, no read of the day's candles - the high, low and spark are then this minute's kept
     * ones, else the last price ([com.optionslab.ira.IndexSpark]).
     */
    suspend fun indexQuote(symbol: String, spark: Boolean = true): Market.Quote? {
        val (key, token) = INDEX[symbol] ?: return null
        val q = quotes(listOf(key))[key] ?: return null
        val minute = Market.minuteNow()
        val kept = sparks[symbol]
        val read = com.optionslab.ira.IndexSpark.candles(spark, kept?.first, minute, kept?.second.isNullOrEmpty())
        val closes = if (read) runCatching { minuteBars(token, Market.today()).map { it.close } }.getOrDefault(emptyList()).also { sparks[symbol] = minute to it }
            else kept?.takeIf { it.first == minute }?.second.orEmpty()
        val m = Market.now().let { it.hour * 60 + it.minute }
        return Market.Quote(symbol, q.last, q.open.takeIf { it > 0 } ?: q.last, closes.maxOrNull() ?: q.last, closes.minOrNull() ?: q.last, m,
            closes.ifEmpty { listOf(q.last) })
    }

    // ---- instruments ---------------------------------------------------------------------

    /** The parsed list, the day it was fetched and the file stamp (modified time, length) it was parsed from. */
    private class InstrumentsMemo(val stamp: Pair<Long, Long>, val day: String, val list: List<Kite.Instrument>)
    @Volatile private var instrumentsMemo: InstrumentsMemo? = null

    /** The instruments file, parsed once per change of the file (it is large: parsing it on every review cost a few hundred ms). */
    private fun instrumentsFile(): InstrumentsMemo? {
        val f = File(app.filesDir, "kite_instruments.json")
        val stamp = f.lastModified() to f.length()
        instrumentsMemo?.let { if (it.stamp == stamp && stamp.first != 0L) return it }
        // Memory audit (14 Oct, item 5): read as a stream, the repeated names and expiries shared - no whole-file string, no
        // JSON tree (several MB at once after every restart).
        return runCatching {
            val (day, list) = f.bufferedReader(Charsets.UTF_8).use { com.optionslab.ira.InstrumentsFile.read(it) } ?: return@runCatching null
            InstrumentsMemo(stamp, day, list).also { instrumentsMemo = it }
        }.getOrNull() ?: instrumentsMemo?.takeIf { f.exists() }   // G1-6: a file that does not parse keeps the list in memory; a file gone (cleared data) has none
    }

    /** G1-6: written whole to a temp file, then renamed over [f], so a reader on another lane never parses a half-written file. */
    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("could not replace ${f.name}") }
    }

    /** True when the instruments list in memory is today's download (memory only: no file read). */
    internal fun instrumentsAreToday(): Boolean = instrumentsMemo?.day == Market.today().toString()

    /** The last instruments list fetched (any day), without touching the network; parsed again only when the file changes. */
    fun cachedInstruments(): List<Kite.Instrument>? = instrumentsFile()?.list

    /** NIFTY and BANKNIFTY options from GET /instruments/NFO, cached for the day. */
    suspend fun instruments(): List<Kite.Instrument> {
        val f = File(app.filesDir, "kite_instruments.json")
        instrumentsFile()?.takeIf { it.day == Market.today().toString() }?.let { return it.list }
        val text = call("GET", "/instruments/NFO", raw = true) as String
        val list = com.optionslab.ira.InstrumentsFile.compact(Kite.parseInstruments(text.lineSequence(), setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")))
        // The market recorder's index futures from the same dump (never a second download for them).
        runCatching { keepNfoFutures(text) }
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.token).put(it.tradingSymbol).put(it.name).put(it.expiry.toString()).put(it.strike).put(it.lotSize).put(it.right.name).put(it.tickSize)) }
        writeAtomic(f, JSONObject().put("day", Market.today().toString()).put("i", a).toString())
        instrumentsMemo = InstrumentsMemo(f.lastModified() to f.length(), Market.today().toString(), list)
        // G1-2: the day's token map follows the list just downloaded. One built earlier today from yesterday's file (the watch's
        // first pass) would otherwise miss every contract listed since - new strikes too - for the rest of the day.
        tokenMap = Market.today() to list.associate { "NFO:" + it.tradingSymbol to it.token }
        return list
    }

    /**
     * G1-5: the day and its specs in one volatile pair, the map a concurrent one: [spec] runs on several lanes at once (the
     * fast path's LIVE and MINUTE lanes, the history, the protections), so a plain map could lose entries or break mid-resize.
     */
    @Volatile private var specsOfDay: Pair<LocalDate, java.util.concurrent.ConcurrentHashMap<String, Kite.Spec>>? = null
    private fun specsToday(): java.util.concurrent.ConcurrentHashMap<String, Kite.Spec> {
        val today = Market.today()
        specsOfDay?.let { if (it.first == today) return it.second }
        return java.util.concurrent.ConcurrentHashMap<String, Kite.Spec>().also { specsOfDay = today to it }
    }

    /**
     * Lot and tick for any instrument, e.g. to square off a position the app
     * did not open. Options the app already knows come from the cached NFO
     * list; anything else is looked up once in that exchange's dump and kept
     * in memory for the day.
     */
    suspend fun spec(exchange: String, symbol: String): Kite.Spec {
        val specs = specsToday()
        specs["$exchange:$symbol"]?.let { return it }
        if (exchange == "NFO") (cachedInstruments() ?: emptyList()).firstOrNull { it.tradingSymbol == symbol }?.let {
            return Kite.Spec("NFO", symbol, it.token, it.lotSize, it.tickSize).also { s -> specs["$exchange:$symbol"] = s }
        }
        require(exchange.all { it.isLetter() }) { "unknown exchange $exchange" }
        val text = call("GET", "/instruments/${Kite.enc(exchange)}", raw = true) as String
        val got = Kite.lookup(text.lineSequence(), setOf(symbol))[symbol] ?: throw IOException("$exchange:$symbol is not listed on Zerodha today")
        specs["$exchange:$symbol"] = got
        return got
    }

    // ---- SENSEX's options at Zerodha (BFO), for the stream's prices of the Hero ₹1,000 ticket's paper SENSEX side (15 Oct) ----

    /** Today's SENSEX options from GET /instruments/BFO (memory only), or null when not read today. */
    @Volatile private var bfoOptions: Pair<LocalDate, List<Kite.Instrument>>? = null
    @Volatile private var bfoTriedAt = 0L
    private val bfoLoading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Today's SENSEX options list, memory only (never the network or a file): null when not read today. */
    fun cachedBfoOptions(): List<Kite.Instrument>? = bfoOptions?.takeIf { it.first == Market.today() }?.second

    /** Tests: SENSEX's list forgotten and its 10-minute retry wait cleared. */
    internal fun resetBfoForTest() { bfoOptions = null; bfoTriedAt = 0L }

    /**
     * Read today's SENSEX options in the background (logged in only; at most every 10 minutes after a failure). Returns at once:
     * the stream's price of a SENSEX paper ticket follows on the next look once the list is in.
     */
    fun loadBfoOptionsInBackground() {
        if (!loggedIn || cachedBfoOptions() != null) return
        val now = System.currentTimeMillis()
        if (now - bfoTriedAt < 10 * 60_000L || !bfoLoading.compareAndSet(false, true)) return
        bfoTriedAt = now
        readScope.launch {
            try {
                val text = call("GET", "/instruments/BFO", raw = true) as String
                bfoOptions = Market.today() to Kite.parseInstruments(text.lineSequence(), setOf("SENSEX"), segment = "BFO-OPT")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tried again after 10 minutes; until then the ticket is priced from the candle feed, as before.
            } finally { bfoLoading.set(false) }
        }
    }

    fun find(list: List<Kite.Instrument>, underlying: String, expiry: LocalDate, strike: Double, right: Right) =
        list.firstOrNull { it.name == underlying && it.expiry == expiry && it.strike == strike && it.right == right }

    /**
     * Today's chain from Kite's own 1-minute candles, in the same shape the
     * Upstox path returns, so the ticket is priced by the same engine code.
     */
    suspend fun liveChain(underlying: String, near: Int = 14): Market.LiveChain {
        val today = Market.today()
        val all = instruments().filter { it.name == underlying && !it.expiry.isBefore(today) }
        if (all.isEmpty()) throw IOException("no listed $underlying options on Kite")
        val expiry = all.minOf { it.expiry }
        val chain = all.filter { it.expiry == expiry }
        val spot = indexQuote(underlying)?.last ?: throw IOException("no $underlying quote from Kite")
        val strikes = chain.map { it.strike }.distinct().sortedBy { kotlin.math.abs(it - spot) }.take(near * 2 + 1).toSet()
        val wanted = chain.filter { it.strike in strikes }
        val contracts = wanted.map { Upstox.Contract(underlying, it.expiry, it.strike, it.right, it.lotSize, "kite:${it.token}", it.tradingSymbol) }
        // One quote call prices every strike with its open interest: about a second, where fetching
        // each contract's minute candles at Kite's three-a-second limit took 15-20 seconds.
        return quoteSnapshot(underlying, expiry, wanted, contracts, spot)
    }

    private suspend fun quoteSnapshot(underlying: String, expiry: LocalDate, wanted: List<Kite.Instrument>,
                                      contracts: List<Upstox.Contract>, spot: Double): Market.LiveChain {
        val q = quotes(wanted.map { "NFO:${it.tradingSymbol}" })
        val minute = Market.minuteNow()
        val series = wanted.zip(contracts).mapNotNull { (ins, c) ->
            val last = q["NFO:${ins.tradingSymbol}"]?.last?.takeIf { it > 0 } ?: return@mapNotNull null
            val qt = q["NFO:${ins.tradingSymbol}"]!!
            Series(c.expiry, c.strike, c.right, c.lotSize, intArrayOf(minute), doubleArrayOf(last), doubleArrayOf(last),
                doubleArrayOf(last), doubleArrayOf(last), longArrayOf(qt.volume), longArrayOf(qt.oi))
        }
        if (series.isEmpty()) throw IOException("Zerodha returned no quotes for the $expiry $underlying chain")
        return Market.LiveChain(expiry, series, wanted.first().lotSize, contracts, spot, "Zerodha live quotes", minute)
    }

    // ---- orders ------------------------------------------------------------------------

    /** Today's count of orders sent, kept in memory (read from the vault once a day / after a wipe; round 2). */
    @Volatile private var sentKept: Triple<String, Int, Int>? = null

    fun sentToday(): Int {
        val day = Market.today().toString()
        val g = SecurePrefs.generationHint
        sentKept?.let { (d, n, gen) -> if (d == day && gen == g) return n }
        val n = if (SecurePrefs.getString(K_SENT_DAY) == day) SecurePrefs.getInt(K_SENT, 0) else 0
        sentKept = Triple(day, n, g)
        return n
    }

    /**
     * Counted in memory at once (the daily cap reads it from there), written to the vault in the background:
     * the Keystore encryption and two disk syncs no longer sit between Zerodha's answer and the fill check.
     */
    @Synchronized private fun countSent() {
        val day = Market.today().toString()
        val n = sentToday() + 1
        sentKept = Triple(day, n, SecurePrefs.generationHint)
        SecurePrefs.putAllSoon(mapOf(K_SENT_DAY to day, K_SENT to n))
    }

    data class Fill(val orderId: String, val status: String, val avgPrice: Double, val filled: Int, val message: String)

    /**
     * Sends ONE order. Callers must have passed Kite.refusals and the owner's confirmation. [arm]: a bot's strategy key (the one
     * its entry check asked the portfolio guard with), so the door refuses only that arm's entry; null for any other order.
     */
    suspend fun placeOrder(o: Kite.Order, exit: Boolean = false, arm: String? = null, intended: Double? = null): String {
        // 18 Oct (Boss): the paper-only period refuses every NEW live entry, from every source (Boss's own, the arms, Pine, the
        // strategies, Jarvis, MCX), even when the app is already Live. Memory only ([PaperOnly.entryRefusal]); an exit never asks.
        if (!exit) PaperOnly.entryRefusal()?.let { why -> paperOnlyRefused(o, why); throw KiteError("paper_only", why) }
        // SEBI static IP: a new position is not opened from an IP Zerodha would refuse (exits always go).
        if (!exit) StaticIp.entryBlock()?.let { throw KiteError("static_ip", it) }
        // An exit never waits behind an entry.
        // 20 Oct, review: an exit sent no longer frees a place in the same-direction cap by itself. A resting LIMIT target, a part
        // sold, Boss's own sale or an exit Zerodha then rejected each freed a whole place while the position was still held, and
        // the cap undercounted all day. A place is freed only when a positions read shows the position closed
        // ([ExposureCapHost.livePositions], on every read): until then it is counted one too many, which only lowers risk.
        // 20 Oct, enhancement round 2: every exit sent is noted by the exit coordinator (memory only) until a read of the book shows
        // it, so the next exit sender on the same contract never reads the book without it ([ExitCoordinator]).
        if (exit) {
            val ticket = ExitCoordinator.sending(o)
            return try {
                com.optionslab.app.work.TradingBusy.during { sendChecked(o, true, arm, intended) }.also { ExitCoordinator.answered(ticket, it) }
            } catch (e: Throwable) {
                ExitCoordinator.failed(ticket, e is Exception && e !is kotlinx.coroutines.CancellationException && definite(e))
                throw e
            }
        }
        // A new position (10 Oct): through the one door, one at a time from any worker, the kill switch (and, for the bots,
        // the loss limit, the day stop and the day lock) read again inside it. A refusal is definite: nothing was sent.
        // 19 Oct: a bot's live index-option entry counted by the same-direction cap until it closes (memory only).
        // 20 Oct, review: counted INSIDE the door, before it opens for the next entry. Counted after it (as before), four puts
        // queued on one signal each passed the cap's check before the one ahead of it was counted. A reply lost on the way (the
        // order may exist) counts too: counting one too many only lowers risk, and the next positions read corrects it.
        val counted = o.tag in BOT_TAGS && o.tag != "iraalgostrat"
        fun count() = ExposureCapHost.liveEntered(o.tradingSymbol, o.exchange, o.side == Kite.Side.BUY, arm ?: o.tag)
        return when (val r = entryGate.enter("${o.exchange}:${o.tradingSymbol}", recheck = { entryRecheck(o, arm) }) {
            com.optionslab.app.work.TradingBusy.during {
                try { sendChecked(o, false, arm, intended).also { if (counted) count() } }
                catch (e: Exception) { if (counted && e !is kotlinx.coroutines.CancellationException && !definite(e)) runCatching { count() }; throw e }
            } }) {
            is com.optionslab.ira.EntryGate.Outcome.Went -> r.value
            is com.optionslab.ira.EntryGate.Outcome.Refused -> {
                // The paper-only period's refusal inside the door: told and typed as at the door's front ([paperOnlyRefused]).
                if (PaperOnly.isRefusal(r.why)) { paperOnlyRefused(o, r.why); throw KiteError("paper_only", r.why) }
                runCatching { Diag.record("zerodha", "Entry not sent (${o.tradingSymbol}): ${r.why}") }
                throw KiteError("entry_gate", r.why)
            }
        }
    }

    /**
     * 18 Oct (live end-to-end tests): Kite's error types after which an order may still have been placed although Zerodha
     * answered with an error, not a lost reply: DataException (502, the OMS answered but Kite could not read the answer) and
     * GeneralException (500, unclassified). They were taken as refusals ([definite]): an ORB entry the exchange filled was left
     * untracked with no stop, and an ORB exit that filled put the stop back on a flat position (a sell resting with nothing held).
     */
    private val UNSURE = setOf("DataException", "GeneralException")

    /**
     * [send], and after one of the [UNSURE] errors the order book is read for the order before anything is decided, as for a
     * lost reply ([findRecentRetrying]: three looks two seconds apart, the book can trail the POST): found, it is the order placed
     * (its id, as if Zerodha had answered); not there, the error stands as before (a refusal); the book unreadable, the order's
     * fate is unknown (not definite, so the caller looks again). Only what really exists changes the outcome. An entry's look
     * holds the entry door meanwhile: no other entry goes while this one's fate is unknown.
     */
    private suspend fun sendChecked(o: Kite.Order, exit: Boolean, arm: String?, intended: Double?): String = try {
        send(o, exit, arm, intended)
    } catch (e: KiteError) {
        if (e.type !in UNSURE) throw e
        val found = try { findRecentRetrying(o, emptyList(), afterError = true) } catch (x: kotlinx.coroutines.CancellationException) { throw x
        } catch (_: Exception) { throw IOException("${e.message} (Zerodha's ${e.type}); its order book could not be read, so the order may exist") }
        runCatching { Diag.record("zerodha", "${o.tradingSymbol}: Zerodha answered ${e.type} (${e.message}); " +
            (found?.let { "the order is in the book: $it" } ?: "no such order in the book: refused")) }
        found ?: throw e
    }

    /**
     * Ids Zerodha gave this phone for its orders (and lost replies adopted since) in the last [SENT_KEEP_MS], memory only:
     * [findRecent] never takes one of them for another order's lost reply (18 Oct: a second identical hand order refused at
     * Kite's gateway was shown "Filled" with the first one's id). Only the last minutes matter: [findRecent] looks back three.
     */
    private val sentIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val SENT_KEEP_MS = 15 * 60_000L

    private fun noteSent(id: String) {
        val now = System.currentTimeMillis()
        if (sentIds.size > 200) sentIds.entries.removeIf { now - it.value > SENT_KEEP_MS }
        sentIds[id] = now
    }

    /**
     * Sends [o] (the order itself, timed); [placeOrder] decides whether it may. Its slippage is noted in memory after
     * Zerodha's answer ([FillSlippage.sent]: a map put, measured once it fills; [intended]: the price the caller decided on).
     */
    private suspend fun send(o: Kite.Order, exit: Boolean, arm: String?, intended: Double?): String {
        // Order speed: the body is built before the clock starts (a string; nothing is fetched for it), then the request is
        // timed from its start to Zerodha's answer. The price's age is the stamp of this contract's last stream tick.
        val body = o.formBody()
        val stamp = runCatching { tokenKnown("${o.exchange}:${o.tradingSymbol}")?.let { KiteStream.seen(it)?.tick?.exchangeTime } }.getOrNull()
        val t0 = runCatching { OrderTiming.sending(live = true, exchSec = stamp) }.getOrDefault(System.currentTimeMillis())
        val data = call("POST", "/orders/regular", body) as JSONObject
        runCatching { OrderTiming.answered(live = true, startedAt = t0) }
        countSent()
        val id = data.getString("order_id")
        noteSent(id)
        // Measuring never costs the order: the order is placed, so nothing here may throw past the id.
        runCatching { FillSlippage.sent(id, o, exit, arm, intended) }
        return id
    }

    /**
     * Waits between reads of an order's state: most options orders fill within a few hundred ms, so the
     * first second is read closely, then it backs off to Kite's pace (about 5 reads in the first second,
     * well inside its 10-a-second limit).
     */
    internal val POLL_MS = longArrayOf(150, 200, 250, 300, 400, 500, 750, 1_000)

    /**
     * Read an order until it is terminal or [timeoutMs] passes. Zerodha's own order update pushed on the live stream
     * ([KiteStream.orderUpdate]: COMPLETE, REJECTED or CANCELLED for this order id) answers at once, without another read;
     * otherwise the order's state comes from Kite's REST order history on the same schedule as before ([POLL_MS]), and
     * any update on the stream wakes the next look early.
     */
    suspend fun awaitOrder(orderId: String, timeoutMs: Long = 20_000): Fill {
        val start = System.currentTimeMillis()
        val end = start + timeoutMs
        var last: JSONObject? = null
        var i = 0
        while (true) {
            streamed(orderId)?.let { f ->
                runCatching { OrderTiming.fillKnown(live = true, ms = System.currentTimeMillis() - start, viaStream = true) }
                ended(f)
                return f
            }
            val seen = KiteStream.orderEvents.value
            val hist = call("GET", "/orders/$orderId") as JSONArray
            if (hist.length() > 0) last = hist.getJSONObject(hist.length() - 1)
            val st = last?.optString("status") ?: ""
            if (st in com.optionslab.ira.OrderUpdate.TERMINAL) {
                runCatching { OrderTiming.fillKnown(live = true, ms = System.currentTimeMillis() - start, viaStream = false) }
                break
            }
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            val wait = POLL_MS[minOf(i++, POLL_MS.size - 1)].coerceAtMost(left)
            kotlinx.coroutines.withTimeoutOrNull(wait) { KiteStream.orderEvents.first { it != seen } }
        }
        val o = last ?: return Fill(orderId, "UNKNOWN", 0.0, 0, "no order history yet")
        return Fill(orderId, o.optString("status"), o.optDouble("average_price", 0.0), o.optInt("filled_quantity"),
            // Kite sends "status_message": null for an open order, which optString reads as the text "null".
            o.optString("status_message", "").let { if (it == "null") "" else it }).also { ended(it) }
    }

    /** A fill's end, for its slippage ([FillSlippage.ended]: memory only; nothing when it is not terminal or not noted). */
    private fun ended(f: Fill) { runCatching { FillSlippage.ended(f.orderId, f.status, f.avgPrice, f.filled) } }

    /** Order [orderId]'s end as Zerodha pushed it on the stream (COMPLETE, REJECTED or CANCELLED), or null when not (yet). */
    private fun streamed(orderId: String): Fill? = KiteStream.orderUpdate(orderId)?.takeIf { it.terminal }?.let { u ->
        Fill(orderId, u.status, u.avgPrice, u.filled, u.message)
    }

    /**
     * After a POST whose answer was lost (timeout, dropped connection), find the
     * order Kite may have placed anyway: same symbol, side, quantity, tag and order type,
     * placed in the last three minutes, and not one of [known] nor one this phone already has the id of.
     * [afterError] (18 Oct review: Kite answered DataException or GeneralException, [sendChecked]): stricter, as the order
     * is adopted only when it is surely this one - a LIMIT's price must match too (to half a tick), and an order the
     * exchange REJECTED, or CANCELLED with nothing filled, is never taken for it (that is no live order; the error stands).
     */
    suspend fun findRecent(o: Kite.Order, known: Collection<String>, afterError: Boolean = false): String? {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val rs = rows(call("GET", "/orders") as JSONArray)
        fun at(r: JSONObject) = runCatching { java.time.LocalDateTime.parse(r.optString("order_timestamp"), fmt) }.getOrNull()
        // "Now" by Kite's own clock when the book has a later order than the phone's clock says (a phone running
        // slow would otherwise widen the window); never earlier than the phone's.
        val now = rs.mapNotNull(::at).fold(java.time.LocalDateTime.now(IST)) { a, t -> if (t.isAfter(a)) t else a }
        val since = now.minusMinutes(3)
        return rs.lastOrNull {
            it.optString("tradingsymbol") == o.tradingSymbol && it.optString("transaction_type") == o.side.name &&
                it.optInt("quantity") == o.quantity && it.optString("tag") == o.tag.filter { c -> c.isLetterOrDigit() }.take(20) &&
                // 18 Oct: the order's own type too (a resting SL of the same size and tag is never taken for a market exit's lost reply).
                it.optString("order_type") == o.orderType &&
                it.optString("order_id") !in known && !sentIds.containsKey(it.optString("order_id")) && at(it)?.isBefore(since) == false &&
                (!afterError || adoptableAfterError(o, it))
        }?.optString("order_id")?.also { countSent(); noteSent(it); ExitCoordinator.adopted(o, it) }
    }

    /**
     * [findRecent] after Kite's error: the row's prices and its status as described there - a LIMIT's or an SL's price, and an
     * SL's or an SL-M's trigger too (a resting stop at another level is never taken for a new one).
     */
    internal fun adoptableAfterError(o: Kite.Order, r: JSONObject): Boolean {
        fun same(want: Double?, field: String): Boolean {
            val w = want ?: return false
            val got = r.optDouble(field, Double.NaN)
            return got.isFinite() && kotlin.math.abs(got - w) <= o.tickSize / 2 + 1e-9
        }
        if (o.hasPrice && !same(o.price, "price")) return false
        if (o.hasTrigger && !same(o.triggerPrice, "trigger_price")) return false
        val status = r.optString("status").uppercase()
        if (status == "REJECTED") return false
        if (status == "CANCELLED" && r.optInt("filled_quantity", 0) <= 0) return false
        return true
    }

    /**
     * True when [e] from [placeOrder] means the order was certainly NOT placed: Zerodha answered with a
     * refusal, or nothing was sent (not logged in). A lost reply (timeout, dropped connection, an
     * unreadable answer, Kite's own NetworkException) is not definite: the order may exist. Kite's DataException and
     * GeneralException on an order are checked against the order book inside [placeOrder] first ([UNSURE]).
     */
    fun definite(e: Throwable): Boolean = e is NotLoggedIn || (e is KiteError && e.type != "NetworkException")

    /**
     * [findRecent] for a POST whose reply was lost, tried [tries] times [waitMs] apart (the order book can
     * trail the POST by a moment). The order id; null when the book was read and holds no such order;
     * throws when the book could not be read at all, so the caller knows the order's fate is unknown.
     */
    suspend fun findRecentRetrying(o: Kite.Order, known: Collection<String>, tries: Int = 3, waitMs: Long = 2_000, afterError: Boolean = false): String? {
        var read = false
        var err: Exception? = null
        for (i in 0 until tries) {
            if (i > 0) delay(waitMs)
            try {
                findRecent(o, known, afterError)?.let { return it }
                read = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                err = e
            }
        }
        if (!read) throw err ?: IOException("Zerodha's order book could not be read")
        return null
    }

    /**
     * The newest of today's orders on [symbol] with [side] and [tag], placed at or after [since] (IST, Kite's
     * timestamp, with two minutes' allowance for the phone's clock) and not in [exclude]: an entry whose order
     * id was lost, found again.
     */
    suspend fun latestTagged(symbol: String, side: String, tag: String, since: java.time.LocalDateTime, exclude: Collection<String>): OrderRow? {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val from = since.minusMinutes(2)
        return orders().firstOrNull {
            it.symbol == symbol && it.side == side && it.tag == tag && it.id !in exclude &&
                runCatching { !java.time.LocalDateTime.parse(it.placedAt, fmt).isBefore(from) }.getOrDefault(false)
        }
    }

    // ---- GTT ------------------------------------------------------------------------------

    /** [orderIds]: the orders a fired GTT placed (its legs' results), so a fired stop's own exit order can be found (18 Oct). */
    data class GttRow(val id: Long, val type: String, val status: String, val exchange: String, val symbol: String,
                      val triggers: List<Double>, val lastPrice: Double, val orders: String, val created: String,
                      val orderIds: List<String> = emptyList(), val legPrices: List<Double> = emptyList(),
                      /** 18 Oct review: each leg's side, quantity and product (what an active GTT would sell or buy). */
                      val legSides: List<String> = emptyList(), val legQtys: List<Int> = emptyList(), val legProducts: List<String> = emptyList()) {
        val active: Boolean get() = status.lowercase() == "active"
        /** The quantity this GTT, while active, would send on [side] in [product] (its first leg: a single-leg stop). */
        fun covers(side: String, product: String): Int =
            if (!active || legSides.isEmpty()) 0 else if (legSides[0] == side && legProducts.getOrNull(0)?.let { it == product } != false) legQtys.getOrElse(0) { 0 } else 0
    }

    suspend fun gtts(): List<GttRow> = rows(call("GET", "/gtt/triggers") as? JSONArray).map { g ->
        val c = g.optJSONObject("condition") ?: JSONObject()
        val tv = c.optJSONArray("trigger_values") ?: JSONArray()
        val os = rows(g.optJSONArray("orders")).joinToString(" / ") { "${it.optString("transaction_type")} ${it.optInt("quantity")} @ ${it.optDouble("price")}" }
        GttRow(g.optLong("id"), g.optString("type"), g.optString("status"), c.optString("exchange"), c.optString("tradingsymbol"),
            (0 until tv.length()).map { tv.getDouble(it) }, c.optDouble("last_price", 0.0), os, g.optString("created_at"),
            rows(g.optJSONArray("orders")).mapNotNull { o -> o.optJSONObject("result")?.optJSONObject("order_result")?.optString("order_id")?.takeIf { it.isNotBlank() } },
            rows(g.optJSONArray("orders")).map { it.optDouble("price", 0.0) },
            rows(g.optJSONArray("orders")).map { it.optString("transaction_type") },
            rows(g.optJSONArray("orders")).map { it.optInt("quantity") },
            rows(g.optJSONArray("orders")).map { it.optString("product") })
    }.sortedByDescending { it.created }.also { ExitCoordinator.gttsRead(it) }

    /** Places a GTT. Callers must have had the owner confirm it. */
    /**
     * 18 Oct review: [placeGtt] where a lost reply never leaves a second GTT: on an answer that is not a definite refusal, the GTT
     * list is read and an active GTT on the same contract with the same trigger and quantity, not one of [known], is adopted (the
     * POST went through). Null when refused or not found (the caller rests it on a later look).
     */
    suspend fun placeGttOnce(g: Kite.Gtt, known: Set<Long>): Long? {
        return try { placeGtt(g) } catch (e: Exception) {
            if (definite(e)) return null
            val q = g.orders.firstOrNull()?.quantity ?: return null
            runCatching {
                gtts().firstOrNull { r -> r.active && r.id !in known && r.symbol == g.tradingSymbol && r.exchange == g.exchange &&
                    r.legQtys.firstOrNull() == q && r.triggers.zip(g.triggers).all { (a, b) -> kotlin.math.abs(a - b) < 1e-6 } && r.triggers.size == g.triggers.size }?.id
            }.getOrNull()
        }
    }

    suspend fun placeGtt(g: Kite.Gtt): Long {
        // 20 Oct, enhancement round 2: noted by the exit coordinator until a read of the GTT list shows it ([ExitCoordinator]).
        val ticket = ExitCoordinator.sendingGtt(g)
        return try {
            (call("POST", "/gtt/triggers", g.formBody()) as JSONObject).getLong("trigger_id").also { ExitCoordinator.answered(ticket, it.toString()) }
        } catch (e: Throwable) {
            ExitCoordinator.failed(ticket, e is Exception && e !is kotlinx.coroutines.CancellationException && definite(e))
            throw e
        }
    }

    suspend fun deleteGtt(id: Long) { call("DELETE", "/gtt/triggers/$id"); ExitCoordinator.gttDeleted(id) }

    /**
     * 18 Oct (the arms' live path, [ArmLive]): an active GTT's trigger and order replaced in place (PUT), so a carried position's
     * stop moves up with its lock without ever being deleted and placed again (no moment with two, or none).
     */
    suspend fun modifyGtt(id: Long, g: Kite.Gtt) { call("PUT", "/gtt/triggers/$id", g.formBody()) }

    /** What Zerodha would block for these orders together ([required]), against the account's free margin. */
    data class Margin(val required: Double, val initial: Double, val available: Double, val charges: Double) {
        val short: Boolean get() = required > available
    }

    /** POST /margins/basket, counting open positions (so a hedge's benefit is included). */
    suspend fun basketMargin(orders: List<Kite.Order>): Margin = kotlinx.coroutines.coroutineScope {
        // The free margin is read alongside the basket, not after it.
        val free = async { funds() }
        val d = call("POST", "/margins/basket?consider_positions=true", Kite.basketJson(orders), json = true, readOnly = true) as JSONObject
        val fin = d.optJSONObject("final")?.optDouble("total", Double.NaN) ?: Double.NaN
        val init = d.optJSONObject("initial")?.optDouble("total", Double.NaN) ?: Double.NaN
        val charges = rows(d.optJSONArray("orders")).sumOf { it.optJSONObject("charges")?.optDouble("total", 0.0) ?: 0.0 }
        Margin(if (fin.isNaN()) init else fin, init, free.await().net, charges)
    }

    /**
     * POST /margins/orders (Kite Connect's order margin calculator): what Zerodha would block for each of [orders] on its own.
     * A read - it places, changes and cancels nothing - sent as JSON like [basketMargin] and, like the contract note, direct
     * and without a diary line unless it fails. The rows' totals, in order (the Live check, 18 Oct, shows only that each was
     * priced and whether the free margin covers them, never the figures).
     */
    suspend fun orderMargins(orders: List<Kite.Order>): List<Double> =
        rows(call("POST", "/margins/orders", Kite.basketJson(orders), json = true, readOnly = true) as? JSONArray).map { it.optDouble("total", Double.NaN) }

    /**
     * POST /charges/orders (Kite Connect's virtual contract note): Zerodha's own charges for orders already executed,
     * [body] being [com.optionslab.ira.ExactCharges.requestJson]. A read - it places, changes and cancels nothing - sent
     * as JSON the way [basketMargin] is. Its data array, as text ([com.optionslab.ira.ExactCharges.total] reads it).
     */
    suspend fun contractNote(body: String): String = (call("POST", "/charges/orders", body, json = true, readOnly = true) as JSONArray).toString()

    /**
     * The account as the guard judges it - positions, funds, today's order count - read in parallel (one
     * round trip instead of three). Null when the positions cannot be read; funds and orders are optional,
     * exactly as the sequential reads were.
     */
    suspend fun accountNow(needed: Double? = null): com.optionslab.engine.risk.AccountGuard.Account? = kotlinx.coroutines.coroutineScope {
        // Round 2: a live entry that says what it needs ([needed], rupees) may be checked against the kept margins (read in
        // the background at most 30 s apart) and the kept orders and positions ([ordersRecon]); Zerodha is read now when the
        // margins are old, or their headroom is within 20% of the need, or the kept reads may not stand ([Kept]).
        val t0 = System.currentTimeMillis()
        val kept = if (needed != null && com.optionslab.ira.MarginCache.decide(marginSnap(), needed, t0) == com.optionslab.ira.MarginCache.Use.CACHED)
            fundsKept?.second else null
        val bookQ = async { runCatching { if (needed != null) positionsRecon() else positionBook() }.getOrNull() }
        val fundsQ = async { kept ?: runCatching { funds() }.getOrNull() }
        val countQ = async { runCatching { (if (needed != null) ordersRecon() else orders()).size }.getOrDefault(0) }
        val out = bookQ.await()?.let { runCatching { Guard.liveAccount(it, fundsQ.await(), countQ.await()) }.getOrNull() }
        if (needed != null) runCatching { OrderTiming.marginCheck(System.currentTimeMillis() - t0) }
        out
    }

    /** One order's latest state (last entry of its history), or null if Kite has none yet. */
    suspend fun orderState(orderId: String): Fill? {
        val hist = call("GET", "/orders/${Kite.enc(orderId)}") as JSONArray
        if (hist.length() == 0) return null
        val o = hist.getJSONObject(hist.length() - 1)
        return Fill(orderId, o.optString("status"), o.optDouble("average_price", 0.0), o.optInt("filled_quantity"),
            // Kite sends "status_message": null for an open order, which optString reads as the text "null".
            o.optString("status_message", "").let { if (it == "null") "" else it }).also { ended(it) }
    }

    suspend fun cancel(orderId: String, variety: String = "regular") {
        call("DELETE", "/orders/${Kite.enc(variety)}/${Kite.enc(orderId)}")
    }

    /** PUT /orders/{variety}/{id}. Callers re-check the order is still working and the owner confirmed. */
    suspend fun modify(order: OrderRow, quantity: Int, orderType: String, price: Double?, trigger: Double?) {
        call("PUT", "/orders/${Kite.enc(order.variety)}/${Kite.enc(order.id)}", Kite.modifyBody(quantity, orderType, price, trigger))
    }
}
