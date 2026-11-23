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
    private val readScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + com.optionslab.app.data.ScopeGuard.handler("Broker.readScope"))

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
     * Which lane a Zerodha READ belongs to (Boss, 10 Oct: the watch's workers run side by side): the stop checks' and the loss
     * limit's reads first ([com.optionslab.ira.RateGate.Priority.SAFETY]), then ordinary reads, then Jarvis's analysis. Put on a
     * coroutine's context; a request with none is an ordinary read. A write's lane is its own (20 Nov 2026, [writePriority]): exits,
     * stops, stop moves and cancels EXIT, above every read; a new entry's order ENTRY.
     */
    class Lane(val priority: com.optionslab.ira.RateGate.Priority) : kotlin.coroutines.AbstractCoroutineContextElement(Lane) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<Lane>
    }

    /** At most 8 requests to Zerodha in any second from this phone, whatever runs at once (Kite allows about 10). */
    private val rate = com.optionslab.ira.RateGate(perSecond = 8)

    /**
     * 20 Nov 2026 (audit: "stop-price quotes compete with about 40 other readers"): Zerodha allows its quote calls (/quote,
     * /quote/ohlc, /quote/ltp) about one a second, apart from the budget above, and refused the rest with a 429 that each reader
     * then retried a second or more later - a stop's read among them. They now queue here instead, one per 1.1 s, served in lane
     * order: a stop's, target's or lock's read ([stopQuotes], the safety lane) goes before any entry, screen or Jarvis read waiting.
     */
    private val quoteRate = com.optionslab.ira.RateGate(perSecond = 1, windowMs = 1_100L)

    /** B5 merge review: what a budgeted read keeps for the call itself after its waits (else it gives up before taking a slot). */
    const val MIN_CALL_MS = 1_000L

    /**
     * B5 merge review: how long a read with [budget], [spentMs] into it, may wait for the main gate (0 or less: none - no slot). It keeps
     * [MIN_CALL_MS] for the call - or half the budget for a short one (B5 review: an order poll's last 1 s read waits up to half of it
     * for a slot rather than failing at once). Pure.
     */
    internal fun mainGateWaitMs(budget: Long, spentMs: Long): Long =
        budget - spentMs.coerceAtLeast(0L) - (if (budget < 2 * MIN_CALL_MS) budget / 2 else MIN_CALL_MS)

    /**
     * B5 merge review: the most a call under it waits for the request budget and for its quote turn ([quoteRate]) - a live copy's entry
     * price check tries the entries' lane briefly first, then the stop checks' ([ArmLive]). On a coroutine's context.
     */
    class QuoteQueueCap(val ms: Long) : kotlin.coroutines.AbstractCoroutineContextElement(QuoteQueueCap) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<QuoteQueueCap>
    }

    /** Whether [path] is one of Zerodha's quote calls ([quoteRate]). Pure. */
    internal fun quoteLimited(path: String): Boolean = path.startsWith("/quote")

    /**
     * The longest a quote call may still wait for its turn in [quoteRate], [spentMs] into the read: what is left of its deadline - the
     * read's own budget ([READ_BUDGET_MS], 5 s; 10 s for one with none), and at most a quick read's 3 s ([FAST_READ_MS], the event
     * path). B5-D: one hard total for the queue, the call and any 429 retry. 0 or less: no time left. Pure.
     */
    internal fun quoteWaitMs(quick: Boolean, budget: Long?, spentMs: Long = 0L): Long {
        val left = (budget ?: 10_000L) - spentMs.coerceAtLeast(0L)
        return if (quick) minOf(FAST_READ_MS.toLong(), left) else left
    }

    /**
     * B5-D: whether a read of a lane above [p] is waiting in Zerodha's quote queue now ([quoteRate]) - a paper arm's exit read then
     * does without its fresh quote (the stream's last price) and leaves the slot to the live stop's read. Memory only.
     */
    internal fun quoteWaitingAbove(p: com.optionslab.ira.RateGate.Priority): Boolean = quoteRate.waitingAbove(p)

    /** TEST ONLY: the quote queue itself (a test holds its slot and queues a stop's read in it). */
    internal fun quoteGateForTest(): com.optionslab.ira.RateGate { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; return quoteRate }

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
    /** 30 Oct: the arms with a live path of their own (the ORB family, Pine): the door asks Live and "AI trades go live" for them. */
    internal val ARM_TAGS = setOf("iraorb", "irapine")

    /**
     * 19 Nov 2026 (audit batch 2): the saved strategies' AUTOMATIC live entries in flight (a scheduled start's, a signal's later in a
     * run - [Strategies]), by the very order object sent: the door asks them Live and "AI trades go live" too, at the last moment,
     * like [ARM_TAGS]. Boss's own start from the app and every exit are never marked. Memory only.
     */
    private val autoStrategyEntries: MutableSet<Kite.Order> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))

    /** [send]s [o] marked as a saved strategy's automatic entry ([autoStrategyEntries]); the mark goes when it returns. */
    internal suspend fun <T> automaticStrategyEntry(o: Kite.Order, send: suspend () -> T): T {
        autoStrategyEntries.add(o)
        try { return send() } finally { autoStrategyEntries.remove(o) }
    }

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

    /** TEST SEAM (19 Nov 2026, audit batch 2): run inside the door, just before its own checks (a setting flipped at the last moment). */
    // (20 Nov 2026, integration review: set only in debug builds.)
    @Volatile internal var testAtDoor: ((Kite.Order) -> Unit)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    internal fun entryRecheck(o: Kite.Order, arm: String? = null, intended: Double? = null, stop: Double? = null): String? {
        testAtDoor?.invoke(o)
        // The kill switch kept in memory and read again only when a kept-elsewhere setting changed (g.kill is one of them):
        // the door reads nothing from the settings store on the order path (round 2's rule), yet sees a flip at once.
        if (killOn()) return "refused: the kill switch is on"
        // 22 Nov 2026 (B6 review): no entry while a backup is being restored (until the restart) - memory only. Exits never come here.
        Backup.restoringRefusal()?.let { return it }
        // 18 Oct (review): the paper-only period read again inside the door too (memory only), so a period set while this entry
        // waited its turn still refuses it. [placeOrder] asks it first; this is the door's own look.
        PaperOnly.entryRefusal()?.let { return it }
        // 18 Oct (final review F2): a paper strategy's mirrored entry can wait here up to 15 s after its own checks ran: the app in
        // Live, "AI trades go live", its arm's Trade live and its Shadow asked again at the last moment - memory only.
        if (o.tag == com.optionslab.engine.orb.LiveMirror.TAG) ArmLive.doorRefusal(arm)?.let { return it }
        // 30 Oct (deep review, CLAUDE.md: every arm's live trade passes "AI trades go live"): the ORB family's (ORB, ORB Fresh,
        // Liquidity 15+5) and Pine's live entries asked the app's Live and "AI trades go live" here too, at the last moment, from
        // memory ([ArmLive.liveFlags]: the settings are read only after one of those keys was written). Before, they went on the
        // app's Live and their own PIN flag alone. It only ever refuses an entry; exits never come here.
        if (o.tag in ARM_TAGS || autoStrategyEntries.contains(o)) ArmLive.liveFlags().let { (appLive, aiLive) ->
            if (!appLive) return "refused: the app is in Paper"
            if (!aiLive) return "refused: \"AI trades go live\" is off"
        }
        if (o.tag in BOT_TAGS) {
            // 21 Oct: either paper segment's loss stop, or the whole paper day past the paper limit, refuses too: never weaker than one paper figure.
            if (LossBreaker.liveDoorTripped()) return "refused: the daily loss limit was reached today"
            // 30 Oct (Boss: "Make stop bot different for index and comodities"): an MCX entry reads the commodity side's stop (and
            // the index LOSS stop, which stopped every live entry before the split); every other entry the index side's. Memory only.
            if (Strategies.stopRefusesAtDoor(mcx = o.exchange == "MCX")) return "stopped_for_today"
            DayLockGuard.refusal(true)?.let { return it }
            // Smart workers (10 Oct): a news window paused ON, unreliable data, the relay down - memory only, lower risk only.
            // 22 Nov 2026 (B6 review, item 4: the door's checks failed open): each check below that THROWS refuses the entry while its
            // live switch is on ([doorPart], EntryGate's rule: an entry never goes on a guess); in shadow it lets it go, as before.
            doorPart("the smart workers", { SmartWorkers.doorActs() }) { SmartWorkers.doorRefusal(o.tradingSymbol) }?.let { return it }
            // The portfolio guard (TODO S12): a live entry ITS arm ([arm]) was just blocked from by the guard - memory only, it
            // only blocks. Review R1: another arm's entry and a saved strategy's basket leg (no arm) are never refused here.
            if (o.tag != "iraalgostrat")
                doorPart("the portfolio guard", { PortfolioHost.doorActs() }) { PortfolioHost.doorRefusal(arm, o.tradingSymbol, o.side == Kite.Side.BUY) }?.let { return it }
            // 19 Oct: the same-direction cap on the bots' live index-option entries - in SHADOW by default (what it would refuse is
            // only recorded; nothing changes) until Boss switches it on in Bot settings. Memory only; it only ever refuses. A saved
            // strategy's basket leg is never asked (refusing one leg of a hedged basket could leave the rest unhedged).
            if (o.tag != "iraalgostrat")
                doorPart("the same-direction cap", { ExposureCapHost.liveActs() }) { ExposureCapHost.liveCheck(o.tradingSymbol, o.exchange, o.side == Kite.Side.BUY, arm ?: o.tag) }?.let { return it }
            // 20 Oct, enhancement round 1 (idea 4): Boss's account stop rule over the whole Zerodha account - in SHADOW by default
            // (what it would refuse is only recorded) until Boss switches it on in Bot settings. Memory only; it only refuses. A
            // saved strategy's basket leg is never asked (one leg refused could leave the rest unhedged): its automatic start is.
            if (o.tag != "iraalgostrat")
                doorPart("the account stop rule", { AccountStopHost.doorActs() }) { AccountStopHost.doorCheck(o.tradingSymbol, arm ?: o.tag) }?.let { return it }
            // 25 Oct (Boss's decisions on the app audit, [BossRiskHost]): the weekly loss stop, opposite bets, the 2% risk cap and h14's
            // capped limit on the bots' live BUYs - each in SHADOW (logged only) unless Boss switches its live switch on; the spread
            // check is shadow only. Memory only; they only ever refuse. A saved strategy's basket leg is never asked.
            if (o.tag != "iraalgostrat" && o.side == Kite.Side.BUY)
                doorPart("your risk rules", { BossRiskHost.liveActs() }) { BossRiskHost.liveDoor(o.tradingSymbol, o.exchange, arm ?: o.tag, o.quantity * o.multiplier.coerceAtLeast(1),
                    o.lotSize * o.multiplier.coerceAtLeast(1), intended ?: o.price, stop) }?.let { return it }
        }
        return null
    }

    /**
     * 22 Nov 2026 (B6 review, item 4: "entry checks at the door fail open"): one of the door's own checks ([check]: the smart workers, the
     * portfolio guard, the same-direction cap, the account stop rule, Boss's risk rules). One that throws refuses the entry when its live
     * switch is on ([on]; a switch that cannot be read counts as on) - EntryGate's rule: an entry never goes on a guess. With the switch off
     * (shadow: it would only have logged) the entry goes, as before. Memory only, as the checks and their switches are; never on a tick.
     */
    internal fun doorPart(what: String, on: () -> Boolean, check: () -> String?): String? =
        try { check() } catch (e: Throwable) { doorUnread(what, runCatching(on).getOrDefault(true), e) }

    /**
     * 22 Nov 2026 (B6 review, item 4): what a door check that could not be read ([e]) says: with its live switch [on], the entry's refusal
     * (noted in the diagnostics); off (shadow), null - the entry goes as it would have. The hosts' own checks answer with it too.
     */
    internal fun doorUnread(what: String, on: Boolean, e: Throwable): String? {
        if (!on) return null
        val why = "refused: $what could not be checked (${e.javaClass.simpleName}); the entry was not sent"
        runCatching { Diag.record("risk", why) }
        return why
    }

    fun init(context: Context) { app = context.applicationContext }

    class NotLoggedIn : IOException("Log in to Zerodha for today first")
    class KiteError(val type: String, msg: String) : IOException(msg)

    /**
     * 22 Nov 2026 (B6 review, item 1: "an unsure Kite error is treated as a definite refusal"): Kite answered one of [UNSURE] (DataException,
     * GeneralException: the order may have been placed all the same) and the order book read after it ([sendChecked]) did not show the
     * order yet. Neither a refusal nor definite ([definite] false, [ArmOrderLabels.zerodhaRefused] false, never a funds refusal): the
     * caller keeps the order UNKNOWN, as for a lost reply - an entry stays unconfirmed and is settled from the book by its tag, an exit
     * or a stop is looked for ([noteLost]) - never sent again or rested twice beside it. [type]: Kite's own error type. Before, the error
     * itself was thrown and taken as a refusal: an entry the exchange filled late was dropped with no stop.
     */
    class KiteUnsure(val type: String, msg: String) : IOException(msg)

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
        // 26 Oct (erase everything): today's sent count and the paper-only notice's day go too (memory; the count is read again).
        sentKept = null; paperOnlyNoticeDay = null
        if (::app.isInitialized) filesForErase().forEach { it.delete() }
        PnlTracker.clear()
    }

    /** The erase's files: the instrument lists [forget] deletes (empty before [init]). */
    internal fun filesForErase(): List<File> = if (!::app.isInitialized) emptyList() else
        listOf("kite_instruments.json", "kite_instruments.json.tmp", "kite_instruments_bfo.json", "kite_instruments_bfo.json.tmp", "kite_futures.json", "kite_futures.json.tmp").map { File(app.filesDir, it) }

    /**
     * 26 Oct: whether the session or a kept read of the account is still in memory (the erase's test). Memory only: a session
     * read again from an empty vault (every field null) holds nothing.
     */
    internal fun holdsForErase(): Boolean = runCatching {
        credsKept?.let { it.key != null || it.token != null || it.user != null } == true ||
            keptOrders != null || keptBook != null || fundsKept != null || sentKept != null
    }.getOrDefault(false)

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
        // (20 Nov 2026, delta review: the lost replies' searches stop with the session; each stays UNKNOWN and is looked for again once logged in.)
        cancelLostSearches()
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
                             json: Boolean = false, viaRelay: Boolean = false, readOnly: Boolean = false, budgetMs: Long? = null): Any {
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
            callInner(method, path, body, auth, raw, json, viaRelay, readOnly, budgetMs).also { r ->
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
                                  json: Boolean, viaRelay: Boolean, readOnly: Boolean = false, budgetMs: Long? = null): Any {
        var attempt = 0
        // 16 Oct (Boss's diagnostics: "GET /portfolio/positions -> FAILED ... (105388 ms)"): a read had only per-socket limits
        // (20 s connect, 60 s read), which the HTTP stack's own silent retries and the 429 retries each started afresh. A read
        // now has one budget for the whole call ([readBudgetMs]); past it the connection is cut, and no 429 retry starts.
        val write = method != "GET" && !readOnly
        // 20 Nov 2026 (audit B5, item 2): an awaitOrder poll on a deadline asks for less than a read's whole budget ([budgetMs]).
        val budget = readBudgetMs(method, path, readOnly, viaRelay)?.let { b -> budgetMs?.let { minOf(it, b) } ?: b }
        // 20 Nov 2026 (audit B5, item 1: "an order can hang about 80 s" - a write had a 20 s connect and a 60 s read and no whole
        // limit): a write now has [WRITE_CONNECT_MS] to connect, [WRITE_READ_MS] for its answer and [WRITE_BUDGET_MS] in all, 429
        // waits included; past it the connection is cut. Cut after the request left, it is a lost reply (the callers search the
        // book before any resend); cut before, it is "not sent" ([NotSent]). A fixed-length body cut half-way is never taken by Kite.
        val cutAfter = budget ?: if (write) WRITE_BUDGET_MS else null
        val began = System.currentTimeMillis()
        while (true) {
            // Orders and every other write go through the static-IP relay when it is on (it throws if it
            // cannot connect, so nothing leaves from another IP); reads go direct ([viaRelay]: a read that
            // warms the order route).
            val test = testEndpoint
            // One budget for every request from this phone (10 Oct): orders first, then the caller's lane. A wait suspends.
            // 20 Nov 2026 (audit B5, item 3): exits, stops, stop moves, cancels and GTTs in the top lane of their own (EXIT); a new
            // entry's order ([EntrySend]) with the ordinary reads (ENTRY), below the stop checks' reads.
            if (test == null) {
                val ctx = kotlinx.coroutines.currentCoroutineContext()
                val p = writePriority(write, ctx)
                // 20 Nov 2026 (B5 stream audit: "stop-price quotes compete with about 40 other readers"): a quote call first takes its turn
                // in Zerodha's own quote limit ([quoteRate], one a second), in lane order - a stop's read before the others. The turn's
                // wait counts in the read's own budget ([quoteWaitMs]: one hard deadline for the whole read - the queue, the call and any
                // 429 retry); past it the read gives up as on Zerodha's refusal, never a queue without end.
                // (Merge review: the quote turn is taken after the main gate's, so it is stamped when the request really goes.)
                // (B5 merge review: a read with a budget waits for the main gate only while at least [MIN_CALL_MS] of it would be left for
                // the call itself - one whose time is spent takes no slot, and its cut never collapses to a few ms; the slot is given back
                // when its quote turn then runs out, as its request never went.)
                // 22 Nov 2026 (B6 review, item 8): a bot's new entry's order waits for its turn [ENTRY_TURN_WAIT_MS] at most ([entryTurn]);
                // later, it is refused with nothing sent. Exits, stops, cancels, Boss's own orders, a basket's legs and every other write
                // wait as before.
                val slot = if (write || budget == null) { writeTurn(rate, p, entry = write && ctx[EntrySend]?.bounded == true); null } else {
                    // (A free slot is still taken at once with no time left to wait: a short read - an order poll's last second - goes.)
                    val most = minOf(mainGateWaitMs(budget, System.currentTimeMillis() - began), ctx[QuoteQueueCap]?.ms ?: Long.MAX_VALUE)
                    rate.acquireStamp(p, most.coerceAtLeast(0L))
                        ?: throw KiteError("RateLimit", "the phone's own request budget is full: this read's time ran out before it could go")
                }
                if (quoteLimited(path)) {
                    val most = minOf(quoteWaitMs(ctx[FastRead] != null, budget, System.currentTimeMillis() - began) - (if (budget != null) MIN_CALL_MS else 0L),
                        ctx[QuoteQueueCap]?.ms ?: Long.MAX_VALUE)
                    if (!quoteRate.acquireWithin(p, most.coerceAtLeast(0L))) {
                        slot?.let { rate.giveBack(it) }
                        throw KiteError("QuoteLimit", "Zerodha's quote calls are at their limit (one a second): this read's time ran out in the queue")
                    }
                }
            }
            val relay = if ((write || viaRelay) && test == null) {
                // 20 Nov 2026 (audit B5, items 1 and 2): the relay not reachable, or not connected within what is left of the write's
                // budget ([relayWithin]): nothing left the phone.
                if (write) relayWithin((WRITE_BUDGET_MS - (System.currentTimeMillis() - began)).coerceAtLeast(1L)) else Relay.proxy()
            } else null
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
            // 20 Nov 2026 (audit B5, item 2): false until the connection to Zerodha is made (DNS, TCP, the relay's tunnel, TLS): a
            // write that fails before then never left the phone ([NotSent], definite). From then on any failure is a lost reply.
            var mayHaveLeft = false
            // The call's budget left: the connection is cut when it runs out.
            val left = cutAfter?.let { (it - (System.currentTimeMillis() - began)).coerceAtLeast(1L) }
            val cut = left?.let { ms -> runCatching { cutter.schedule(Runnable { runCatching { c.disconnect() } }, ms, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull() }
            try {
                c.requestMethod = method
                c.connectTimeout = if (quick) FAST_READ_MS else if (budget != null) READ_SOCKET_MS else if (write) WRITE_CONNECT_MS else 20_000
                c.readTimeout = if (quick) FAST_READ_MS else if (budget != null) READ_SOCKET_MS else if (write) WRITE_READ_MS else 60_000
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.setRequestProperty("X-Kite-Version", "3")
                c.setRequestProperty("User-Agent", "IraAlgo-Android")
                if (auth) { val tok = token(); c.setRequestProperty("Authorization", "token ${apiKey}:$tok") }
                val bytes = body?.toByteArray()
                if (bytes != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
                    // Streamed at a fixed length, the body cannot be replayed: neither the JVM's nor Android's
                    // connection code may then silently re-send an order when a reply is lost (a second order).
                    c.setFixedLengthStreamingMode(bytes.size)
                }
                // The connection made on its own first (20 Nov 2026, audit B5, item 2), so a failure up to here is told from one after
                // the request may have gone. The request itself (its body, or its line for a DELETE) goes only after this.
                if (write) { c.connect(); mayHaveLeft = true } else mayHaveLeft = true
                if (bytes != null) c.outputStream.use { it.write(bytes) }
                val askedAt = System.currentTimeMillis()
                val code = c.responseCode
                // 16 Oct: the phone clock's offset against Zerodha's server, NTP-style (the round trip's middle vs the Date header).
                if (method == "GET" && code in 200..299) {
                    val gotAt = System.currentTimeMillis()
                    runCatching { c.getHeaderFieldDate("Date", 0L).takeIf { it > 0 }?.let {
                        OrderTiming.serverClock(askedAt, gotAt, it / 1000)
                        // 20 Nov 2026 (audit B5, item 4): kept here too (memory only), for the lost-reply search's time window.
                        com.optionslab.ira.OrderSpeed.offsetFromDate(askedAt, gotAt, it / 1000)?.let { off -> serverOffset = off to gotAt }
                    } }
                }
                if (code == 429 && attempt < 3 && retryFits(cutAfter, System.currentTimeMillis() - began, 1_000L * (attempt + 1))) {
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
                throw failed(write && !mayHaveLeft, "No connection to Zerodha")
            } catch (_: java.net.SocketTimeoutException) {
                throw failed(write && !mayHaveLeft, if (write && !mayHaveLeft) "Could not connect to Zerodha in time" else "Zerodha did not answer in time")
            } catch (e: IOException) {
                if (com.optionslab.app.security.KitePin.mismatch) throw failed(write && !mayHaveLeft,
                    "Refused: Zerodha's certificate chain no longer matches the one this phone pinned. On a network you trust, " +
                        "check api.kite.trade in a browser, then re-trust it under More → Security.")
                throw failed(write && !mayHaveLeft, "Could not reach Zerodha")
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
     * 20 Nov 2026 (audit B5, item 1): a write's limits (an order, a change, a cancel, a GTT). Zerodha answers an order in well under a
     * second; through the relay the connect can take a few. Before, a write had 20 s to connect and 60 s for its answer, each started
     * afresh, and no whole limit: an order could hang about 80 s with its stop or exit waiting on it.
     */
    const val WRITE_CONNECT_MS = 5_000
    const val WRITE_READ_MS = 10_000
    const val WRITE_BUDGET_MS = 15_000L

    /**
     * 20 Nov 2026 (audit B5, item 2): a write that failed before its request could leave the phone - no address for Zerodha, no
     * network, a refused or timed-out connection, the relay not reachable, a TLS failure - so nothing reached Zerodha. Definite
     * ([definite]): the caller may treat it as refused and try again, with no search of the book first. Any doubt stays a lost reply.
     */
    class NotSent(msg: String) : IOException(msg)

    /** A failure's exception: [NotSent] when the write surely never left ([notSent]), else a plain (lost-reply) IOException. */
    private fun failed(notSent: Boolean, msg: String): IOException = if (notSent) NotSent(msg) else IOException(msg)

    /** The relay's connects for a write, off the caller ([relayWithin]): one that outlives the write's budget finishes here. */
    private val relayScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + com.optionslab.app.data.ScopeGuard.handler("Broker.relayScope"))

    /**
     * 20 Nov 2026 (audit B5, item 1): the relay's proxy for a write, waited for at most [ms] (what is left of the write's budget). The
     * relay's SSH connect is a blocking call of up to 15 s a try (and waits on any connect already running), so on its own it could hold
     * an order past its budget; it now runs beside the write, which gives up when [ms] has passed. Either way nothing left the phone
     * ([NotSent]: the order never went from another IP, and it may be sent again). The relay off: null at once, no other thread. One
     * connect at a time is shared by every write waiting on it (review: a connect left behind by a write that gave up is joined by the
     * next write, never started again beside it, so a hung relay never piles up blocked threads). [on] and [proxy]: test seams.
     */
    internal suspend fun relayWithin(ms: Long, on: () -> Boolean = { Relay.enabled },
                                     proxy: () -> java.net.Proxy? = { Relay.proxy() }): java.net.Proxy? {
        if (!on()) return null
        val d = synchronized(relayLock) {
            relayConnect?.takeIf { it.isActive } ?: relayScope.async { proxy() }.also { relayConnect = it }
        }
        val got = try {
            kotlinx.coroutines.withTimeoutOrNull(ms) { listOf(d.await()) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: IOException) { throw NotSent(e.message ?: "The relay could not connect") }
        if (got == null) throw NotSent("The relay did not connect within ${ms / 1000} s; nothing was sent")
        return got.single()
    }
    private val relayLock = Any()
    @Volatile private var relayConnect: kotlinx.coroutines.Deferred<java.net.Proxy?>? = null

    /**
     * 20 Nov 2026 (audit B5, item 3): marks a NEW entry's order on its coroutine ([placeOrder]), so its POST takes the entries' lane,
     * below the exits' and the stop checks' reads ([writePriority]). Memory only.
     */
    private class EntrySend(
        /**
         * 22 Nov 2026 (B6 review, item 8): a bot's own single-order entry (ORB, Liquidity, Pine, the arms' live copies), whose turn in the
         * request budget is bounded ([entryTurn]). Boss's own orders and a saved strategy's basket legs wait as before - one leg of a hedged
         * basket refused could leave the rest unhedged, the reason the door never refuses them on its risk checks either.
         */
        val bounded: Boolean = false,
    ) : kotlin.coroutines.AbstractCoroutineContextElement(EntrySend) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<EntrySend>
    }

    /**
     * The request budget's lane for one call: a write is an exit's (EXIT: exits, stops, stop moves, cancels, GTTs - first of all)
     * unless it is a new entry's order ([EntrySend]: ENTRY); a read takes its caller's [Lane] (ENTRY when none).
     */
    internal fun writePriority(write: Boolean, ctx: kotlin.coroutines.CoroutineContext): com.optionslab.ira.RateGate.Priority = when {
        write && ctx[EntrySend] != null -> com.optionslab.ira.RateGate.Priority.ENTRY
        write -> com.optionslab.ira.RateGate.Priority.EXIT
        else -> ctx[Lane]?.priority ?: com.optionslab.ira.RateGate.Priority.ENTRY
    }

    /** TEST ONLY: [writePriority] as a new entry's order would ask. */
    internal fun entryWritePriorityForTest(): com.optionslab.ira.RateGate.Priority = writePriority(true, EntrySend())

    /**
     * 22 Nov 2026 (B6 review, item 8: "ENTRY writes have no limit on their wait at the RateGate"): the longest a new entry's order waits for
     * its turn in the one request budget ([rate]) - the exits, the stops and the stop checks' reads go first, and a busy minute could hold
     * an entry there for long, to reach Zerodha at a price long gone.
     */
    const val ENTRY_TURN_WAIT_MS = 3_000L

    /**
     * 22 Nov 2026 (B6 review, item 8): a new entry's turn in [gate] (lane [p]), waited for [maxMs] at most; none by then, the entry is
     * refused at the app's own door ("entry_gate": definite, nothing reached Zerodha) - never sent late. Exits and stops never come here.
     */
    internal suspend fun entryTurn(gate: com.optionslab.ira.RateGate, p: com.optionslab.ira.RateGate.Priority, maxMs: Long = ENTRY_TURN_WAIT_MS) {
        if (gate.acquireWithin(p, maxMs)) return
        throw KiteError("entry_gate", "refused: the phone's request budget stayed busy with exits and stop checks for ${maxMs / 1000} s; " +
            "the entry was not sent")
    }

    /** 22 Nov 2026 (B6 review, item 8): a write's (or an unbudgeted read's) turn in [gate]: a new [entry]'s bounded ([entryTurn]), any other's waited for as before. */
    internal suspend fun writeTurn(gate: com.optionslab.ira.RateGate, p: com.optionslab.ira.RateGate.Priority, entry: Boolean) {
        if (entry) entryTurn(gate, p) else gate.acquire(p)
    }

    /**
     * The budget of one read (null: not a read's budget - every write, which has its own since 20 Nov 2026 ([WRITE_BUDGET_MS]),
     * the big downloads, and a read that warms the order route through the relay, whose SSH connect alone can take seconds). A
     * plain read, or a POST that only reads, gets [READ_BUDGET_MS]. The event-driven reads keep their own 3 s socket limits inside it.
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
    private val warmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + com.optionslab.app.data.ScopeGuard.handler("Broker.warmScope"))

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
        // 22 Nov 2026 (B6 review, item 5): the instruments list into memory now, in the background, so the arms' first live entry finds it
        // there ([instrumentsInMemory]: an entry under an arm's lock never reads it itself).
        runCatching { warmInstrumentsInBackground() }
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
                     val exposure: Double = 0.0, val optionPremium: Double = 0.0, val realised: Double = 0.0, val m2m: Double = 0.0,
                     /** 21 Oct, enhancement round 4 (idea 3): the day's payout (money taken out; Kite's utilised.payout). */
                     val payout: Double = 0.0,
                     /** 21 Oct, enhancement round 5 (idea 5): money added in the day (Kite's available.intraday_payin). */
                     val payin: Double = 0.0) {
        /**
         * 21 Oct, round 5: what the next day's opening balance should be if no money left or came in overnight: the opening plus
         * the day's realised P&L, less the premium of options bought, plus money added today. Approximate (charges not in it);
         * NaN when the opening balance is not known.
         */
        val expectedNextOpening: Double get() = if (openingBalance > 0.0) openingBalance + realised - optionPremium + payin else Double.NaN
    }

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

    /**
     * 20 Nov 2026 (deep review of the audit fix): [skipped] - the net rows Zerodha's reply gave malformed (no quantity, not readable)
     * and that were left out, by "SYMBOL:PRODUCT" ("SYMBOL:*" when the row named no product), or [ANY] when a row had no symbol at all.
     * Every exit works on the rows that were read: an exit treats a contract as not known only when its own symbol and product were
     * left out ([unknownForExit], never on [ANY]). A check that would END something on a contract's absence (a protection, a strategy
     * leg's stop, a Protect's recheck) counts any skipped row of the contract, or [ANY], as not known ([unknown]).
     */
    data class Positions(val net: List<Position>, val day: List<Position>, val skipped: Set<String> = emptySet(),
                         /** The left-out rows' own money figures (pnl, m2m, realised, unrealised), so the account's totals keep them. */
                         val skippedMoney: List<Double> = listOf(0.0, 0.0, 0.0, 0.0)) {
        /** Whether the read had a malformed row it left out. */
        val partial: Boolean get() = skipped.isNotEmpty()
        /** For a check that would END something: whether any of [symbol]'s net rows may have been left out (or a row with no symbol). */
        fun unknown(symbol: String): Boolean = ANY in skipped || skipped.any { it.startsWith("$symbol:") }
        /**
         * For an exit: whether [symbol]'s own [product] row was left out. A row with no symbol never blocks an exit on a contract whose
         * own row was read; only one with no row read at all is then not known (the nameless row may be it - never read as 0).
         */
        fun unknownForExit(symbol: String, product: String): Boolean = "$symbol:$product" in skipped || "$symbol:*" in skipped ||
            (ANY in skipped && net.none { it.symbol == symbol && it.product == product })
        val pnl: Double get() = net.sumOf { it.pnl } + skippedMoney[0]
        val m2m: Double get() = net.sumOf { it.m2m } + skippedMoney[1]
        val realised: Double get() = net.sumOf { it.realised } + skippedMoney[2]
        val unrealised: Double get() = net.sumOf { it.unrealised } + skippedMoney[3]

        companion object {
            /** [skipped]'s mark for a malformed row with no symbol: every contract's absence is then not known. */
            const val ANY = "*"
        }
    }

    data class OrderRow(val id: String, val symbol: String, val side: String, val qty: Int, val filled: Int, val price: Double,
                        val avg: Double, val status: String, val message: String, val placedAt: String,
                        val exchange: String = "NFO", val product: String = "", val type: String = "LIMIT", val trigger: Double = 0.0,
                        val variety: String = "regular", val pending: Int = 0, val tag: String = "",
                        /** 21 Oct, enhancement round 4 (idea 2): when the exchange last changed it (Kite's exchange_update_timestamp; "" when not given). */
                        val updatedAt: String = "") {
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
                u.optDouble("option_premium", 0.0), u.optDouble("m2m_realised", 0.0), u.optDouble("m2m_unrealised", 0.0),
                u.optDouble("payout", 0.0), a.optDouble("intraday_payin", 0.0))
        }
        fundsKept = r.startedAt to r.value
        // 21 Oct, enhancement round 4 (idea 3): the day's payout kept for the payout-aware drawdown (recorded on a worker thread).
        // 21 Oct, enhancement round 5 (idea 5): with the day's balances, to confirm the next day that a payout really left.
        runCatching { PayoutWatch.seen(r.value.payout, r.value.openingBalance, r.value.expectedNextOpening) }
        runCatching { AppState.publishFunds(r.value, r.startedAt) }
        return r.value
    }

    // ---- kept reads for the hot path (round 2) ---------------------------------------------------------------

    /** The last margins read and when (memory only): the live entry's margin check ([accountNow] with a need). */
    @Volatile private var fundsKept: Pair<Long, Funds>? = null

    /**
     * 4 Nov: the cash an option buy can use now: Kite's equity net, but never more than its cash balance (pledged collateral does
     * not pay a premium). Read from Zerodha now (workers asking at once share one read), never the kept one: an order just sent
     * may have used it (review). Null when it cannot be read.
     */
    suspend fun freeCash(): Double? = runCatching { funds().let { f -> minOf(f.net, f.available) } }.getOrNull()

    /**
     * 15 Nov: [freeCash] as the margins last read show it ([fundsKept] - a live entry's own account read, [accountNow], has just
     * read them or found them fresh), while that read is at most [com.optionslab.ira.MarginCache.MAX_AGE_MS] old (the most the
     * entry's own margin check trusts); null when none is kept, it is older or from the future, or it is not a number. Memory only:
     * no read, no lock, never throws (the order path: the funds check, [FundsCheck]).
     */
    fun keptFreeCash(nowMs: Long = System.currentTimeMillis()): Double? = runCatching {
        val (at, f) = fundsKept ?: return@runCatching null
        val age = nowMs - at
        if (age < 0 || age > com.optionslab.ira.MarginCache.MAX_AGE_MS) null else minOf(f.net, f.available).takeIf { it.isFinite() }
    }.getOrNull()

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
    internal fun resetForTest() { dropCreds(); dropKept(); sentKept = null; sentIds.clear(); paperOnlyNoticeDay = null
        entryIds.clear(); exitIds.clear(); sendMarks.clear(); serverOffset = null; killSweepAgainMs = 3_000L; relayConnect = null; lastEntrySentAt = 0L
        killJob?.cancel(); killJob = null; killJobActs = false; killRequestsForTest.clear(); killSweepOn = false
        cancelLostSearches(); lostWrites.clear(); foundLately.clear(); claimedIds.clear(); lostSearchMsForTest = null; lostLookGapMsForTest = null; lostSearchInlineForTest = false
        // (22 Nov 2026, B6 review: no test's instruments list in memory left for the next test's entries, [instrumentsInMemory], nor a
        // background read of it still running into the next test.)
        instrumentsWarm?.cancel(); instrumentsWarm = null; instrumentsWarming.set(false); instrumentsMemo = null }
    /** TEST ONLY (20 Nov 2026): the ids of the orders sent from here forgotten, as after a restart (memory only). */
    internal fun forgetSentForTest() { sentIds.clear() }

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

    /** 20 Nov 2026: when Boss was last told of a partial positions read ([netRows]), and how often at most. Memory only. */
    @Volatile private var partialSaidAt = 0L
    internal const val PARTIAL_SAY_MS = 10 * 60_000L

    /** TEST ONLY: the partial-read notice may be said again at once. */
    internal fun resetPartialSaidForTest() { partialSaidAt = 0L }

    /**
     * 20 Nov 2026 (audit; deep review): Zerodha's net rows read one by one. A malformed row - not an object, no symbol, no quantity, or
     * one that does not parse - is never a position at 0 and never fails the whole read (every exit keeps working on the rows read):
     * it is left out and named in [Positions.skipped] (its symbol, or [Positions.ANY] when it has none).
     */
    private fun netRows(a: JSONArray): Triple<List<Position>, Set<String>, List<Double>> {
        val out = ArrayList<Position>()
        val skipped = HashSet<String>()
        val money = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var badRows = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i)
            val sym = o?.optString("tradingsymbol").orEmpty()
            // (Review: a quantity given as a number in a string, which Kite's optInt read as ever, is a quantity.)
            val q = o?.opt("quantity")
            val qty = q is Number || (q is String && q.trim().toDoubleOrNull()?.let { it.isFinite() && it == kotlin.math.floor(it) } == true)
            val bad = o == null || sym.isEmpty() || !qty || runCatching { position(o) }.onSuccess { out += it }.isFailure
            if (!bad) continue
            badRows++
            skipped += if (o == null || sym.isEmpty()) Positions.ANY else "$sym:${o.optString("product").ifEmpty { "*" }}"
            // (Its money figures, when it gives them, still count in the account's totals: a loss is never hidden by a bad row.)
            if (o != null) listOf("pnl", "m2m", "realised", "unrealised").forEachIndexed { k, f -> money[k] += o.optDouble(f, 0.0).takeIf { it.isFinite() } ?: 0.0 }
        }
        if (skipped.isNotEmpty()) {
            runCatching { Diag.record("zerodha", "positions: $badRows malformed row(s) left out (${skipped.joinToString()}); read as not known") }
            // Review: Boss told (at most every [PARTIAL_SAY_MS]) while the reads stay partial. Memory only; never blocks.
            val now = System.currentTimeMillis()
            if (now - partialSaidAt !in 0 until PARTIAL_SAY_MS) {
                partialSaidAt = now
                val what = skipped.map { if (it == Positions.ANY) "a row with no name" else it.substringBefore(':') }.distinct().joinToString()
                val nameless = if (Positions.ANY in skipped) " A row with no name could be any position: check every open position in Kite." else ""
                runCatching { com.optionslab.app.work.Alerts.post("Zerodha's positions reply had $badRows unreadable row(s) ($what). Exits " +
                    "keep working on the rows that were read, and nothing is ended on what could not be read.$nameless Check your positions in Kite.",
                    com.optionslab.app.work.Alerts.Kind.ERROR, "Positions", throttle = true) }
            }
        }
        return Triple(out, skipped, money.toList())
    }

    /**
     * 20 Nov 2026 (audit: [Protections] read Zerodha again for every item): a count bumped by every order, change or cancel from this
     * phone and every order update on the stream. A read kept while it is unchanged shows everything sent since. Memory only.
     */
    fun writeGen(): Long = writes.get()

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
            // 20 Nov 2026 (audit: a malformed positions reply was read as "no positions"): a reply without its "net" list is a failed
            // read (thrown), never an empty account. A malformed row is left out and named ([netRows]); the rest is read as ever.
            val net = d.optJSONArray("net") ?: throw java.io.IOException("Zerodha's positions reply had no net list")
            val (rows, skipped, money) = netRows(net)
            val day = d.optJSONArray("day")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { runCatching { position(it) }.getOrNull() } } }.orEmpty()
            Positions(rows, day, skipped, money)
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

    /**
     * [fresh]: 21 Oct, enhancement round 5 - never joins a read already on its way (one begun before the caller's last positions
     * read could miss a fill the positions show): the positions-lag guard's check of a suspected conversion.
     */
    suspend fun orders(fresh: Boolean = false): List<OrderRow> {
        val gen = writes.get()
        // Workers asking at once share one read (10 Oct, [SHARE_MS]): never one begun before an order, change or cancel.
        val r = ordersShared.run(shareKey(gen), if (fresh) 0L else SHARE_MS, shareContext()) {
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
        o.optString("variety", "regular"), o.optInt("pending_quantity"), o.optString("tag", "").let { if (it == "null") "" else it },
        o.optString("exchange_update_timestamp", "").let { if (it == "null") "" else it })

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
        if (key.startsWith("BFO:")) return bfoToken(key, load = true)
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

    /**
     * "BFO:<symbol>" -> its token from today's BFO list in memory (never a file or the network); the map built once per list.
     * 20 Nov 2026 (audit: "after a restart, SENSEX live prices come from slower quotes"): no list in memory yet (a restart) -
     * its read is started in the background ([loadBfoOptionsInBackground], returns at once), so the SENSEX position is followed on
     * the stream from the next look instead of on quote calls until something else happened to read the list.
     */
    @Volatile private var bfoTokens: Pair<List<Kite.Instrument>, Map<String, Long>>? = null
    private fun bfoToken(key: String, load: Boolean = false): Long? {
        val list = cachedBfoOptions() ?: run { if (load) runCatching { loadBfoOptionsInBackground() }; return null }
        val m = bfoTokens?.takeIf { it.first === list }?.second
            ?: list.associate { "BFO:" + it.tradingSymbol to it.token }.also { bfoTokens = list to it }
        return m[key]
    }

    /**
     * Quotes for "EXCHANGE:SYMBOL" keys. Instruments the live stream has a fresh tick for
     * are answered from it at once; only the rest go to Kite's quote API (and are
     * followed by the stream from then on).
     */
    suspend fun quotes(keys: List<String>, keepStream: Boolean = true, lane: com.optionslab.ira.RateGate.Priority? = null): Map<String, Quote> {
        if (keys.isEmpty()) return emptyMap()
        val tokens = keys.associateWith { tokenOf(it) }
        // [keepStream] false (Battery, 14 Oct: the MCX price reads with nothing held on MCX): answered from the stream if it
        // runs, else by the quote call, without keeping the stream awake for these instruments.
        if (keepStream) KiteStream.touch(tokens.values.filterNotNull())
        val streamed = keys.mapNotNull { k -> tokens[k]?.let { KiteStream.tick(it) }?.let { t -> k to Quote(t.last, t.bid, t.ask, t.open, t.oi, t.volume) } }.toMap()
        val rest = keys.filter { it !in streamed }
        if (rest.isEmpty()) return streamed
        // B5-D: [lane] when the caller says so (an exit's read: SAFETY); else the caller's own, as before.
        return streamed + (if (lane == null) quotesRest(rest) else kotlinx.coroutines.withContext(Lane(lane)) { quotesRest(rest) })
    }

    /**
     * 18 Oct (speed review): the prices a stop, target or profit lock is judged on. Each from the stream's tick while it is
     * [com.optionslab.ira.LiveQuote.FRESH_MS] (2 s) fresh, the rest from Zerodha's quote call itself. [quotes] answers from
     * a tick up to 5 s old, and a stream gone quiet is called stalled only after 10 s: a stop was judged on a price 2-5 s
     * old, with no REST read, as the "fallback" of a look whose ticks were not fresh. Kept followed on the stream. When the
     * quote call fails (a 429, no network), the rest are answered from a tick up to 5 s old as before, and the fresh ones are
     * kept: a failed read never leaves every held position unpriced for that look.
     */
    suspend fun stopQuotes(keys: List<String>, lane: com.optionslab.ira.RateGate.Priority? = null, staleFallback: Boolean = true): Map<String, Quote> {
        if (keys.isEmpty()) return emptyMap()
        val tokens = keys.associateWith { tokenOf(it) }
        KiteStream.touch(tokens.values.filterNotNull())
        val streamed = keys.mapNotNull { k ->
            tokens[k]?.let { KiteStream.freshTick(it) }?.takeIf { it.last > 0 }?.let { t -> k to Quote(t.last, t.bid, t.ask, t.open, t.oi, t.volume) }
        }.toMap()
        val rest = keys.filter { it !in streamed }
        if (rest.isEmpty()) return streamed.mapValues { stopGuard(it.value) }
        // 20 Nov 2026 (audit: "stop-price quotes compete with about 40 other readers"): a stop's, target's or lock's quote read goes
        // in the safety lane of the one request budget, with the exits and orders ([com.optionslab.ira.RateGate]: a higher lane
        // waiting is served before any lower one), never behind the entries', screens' and Jarvis's reads. Same budget, so
        // Zerodha's limit is kept.
        // Review: [lane] (or the caller's own) when it says so - an entry's or a shadow's price check is not a stop's read; else SAFETY.
        val p = lane ?: kotlinx.coroutines.currentCoroutineContext()[Lane]?.priority ?: com.optionslab.ira.RateGate.Priority.SAFETY
        // B5-D: the fallback is the latest tick by when it REACHED the phone (5 s at most, as before the late-packet stamp): a tick
        // stamped back for coming late never leaves a stop with no price when no fresh quote answers.
        fun older(keys: List<String>) = keys.mapNotNull { k ->
            tokens[k]?.let { KiteStream.arrivedTick(it) }?.takeIf { it.last > 0 }?.let { t -> k to Quote(t.last, t.bid, t.ask, t.open, t.oi, t.volume) }
        }.toMap()
        val read = try { kotlinx.coroutines.withContext(Lane(p)) { quotesRest(rest) } } catch (e: Exception) {
            // (B5 merge review: [staleFallback] false - a caller with a fresher read to fall back on - takes the failure, never an old tick.)
            if (e is kotlinx.coroutines.CancellationException || !staleFallback) throw e
            val older = older(rest)
            if (streamed.isEmpty() && older.isEmpty()) throw e
            older
        }
        // B5-D: one Zerodha did not answer for (none in the reply): its latest tick too, never no price.
        val unanswered = if (staleFallback) rest.filter { it !in read } else emptyList()
        return (streamed + older(unanswered) + read).mapValues { stopGuard(it.value) }
    }

    /**
     * 15 Nov ([com.optionslab.ira.PriceNow.stopLast]): [q] as a stop, target or lock may be judged on it - its last price, or its
     * book's bid when the last price lies far outside a real two-sided book (an old trade of a thin option). Pure, memory only.
     */
    fun stopGuard(q: Quote): Quote = runCatching { q.copy(last = com.optionslab.ira.PriceNow.stopLast(q.last, q.bid, q.ask)) }.getOrDefault(q)

    /** Zerodha's quote call itself, never the stream's memory (a paper fill's re-read when no stream tick is 2 s fresh, 16 Oct). */
    suspend fun quotesFresh(keys: List<String>): Map<String, Quote> = if (keys.isEmpty()) emptyMap() else quotesRest(keys)

    /** TEST ONLY: the request lane the last quote read asked in (memory only; one volatile write per read). */
    @Volatile internal var lastQuoteLaneForTest: com.optionslab.ira.RateGate.Priority? = null

    private suspend fun quotesRest(keys: List<String>): Map<String, Quote> {
        if (com.optionslab.app.BuildConfig.DEBUG) lastQuoteLaneForTest = kotlinx.coroutines.currentCoroutineContext()[Lane]?.priority
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
        if (now - futuresTriedAt in 0 until 30 * 60_000L) return if (nfoDay == today) kept else emptyList()
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

    /**
     * The parsed list, the day it was fetched and the file stamp (modified time, length) it was parsed from. 20 Nov 2026: and when
     * it was fetched ([at], epoch ms on the market clock; null in a file written before), for [instrumentsCurrent].
     */
    private class InstrumentsMemo(val stamp: Pair<Long, Long>, val day: String, val list: List<Kite.Instrument>, val at: Long? = null)

    /**
     * 20 Nov 2026 (audit: "the instruments list is cached by date only"): [m] is today's list - of today and fetched after
     * Zerodha's morning dump ([com.optionslab.ira.InstrumentsFile.current]). Memory only.
     */
    private fun instrumentsCurrent(m: InstrumentsMemo?): Boolean = m != null && runCatching {
        com.optionslab.ira.InstrumentsFile.current(m.day,
            m.at?.let { java.time.Instant.ofEpochMilli(it).atZone(com.optionslab.engine.IST).toLocalDateTime() }, Market.now().toLocalDateTime())
    }.getOrDefault(m.day == Market.today().toString())
    @Volatile private var instrumentsMemo: InstrumentsMemo? = null

    /** The instruments file, parsed once per change of the file (it is large: parsing it on every review cost a few hundred ms). */
    private fun instrumentsFile(): InstrumentsMemo? {
        val f = File(app.filesDir, "kite_instruments.json")
        val stamp = f.lastModified() to f.length()
        instrumentsMemo?.let { if (it.stamp == stamp && stamp.first != 0L) return it }
        // Memory audit (14 Oct, item 5): read as a stream, the repeated names and expiries shared - no whole-file string, no
        // JSON tree (several MB at once after every restart).
        return runCatching {
            val saved = f.bufferedReader(Charsets.UTF_8).use { com.optionslab.ira.InstrumentsFile.readSaved(it) } ?: return@runCatching null
            InstrumentsMemo(stamp, saved.day, saved.list, saved.at).also { instrumentsMemo = it }
        }.getOrNull() ?: instrumentsMemo?.takeIf { f.exists() }   // G1-6: a file that does not parse keeps the list in memory; a file gone (cleared data) has none
    }

    /** G1-6: written whole to a temp file, then renamed over [f], so a reader on another lane never parses a half-written file. */
    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("could not replace ${f.name}") }
    }

    /** True when the instruments list in memory is today's download (memory only: no file read). */
    internal fun instrumentsAreToday(): Boolean = instrumentsCurrent(instrumentsMemo)

    /**
     * The last instruments list fetched (any day), without touching the network; parsed again only when the file changes.
     * 20 Nov 2026 (audit): when it is not today's list ([instrumentsCurrent]: an earlier day's, or fetched before the morning's
     * dump), today's is fetched in the background ([refreshInstrumentsInBackground]); the caller is answered at once, as before.
     */
    fun cachedInstruments(): List<Kite.Instrument>? = instrumentsFile().also { if (!instrumentsCurrent(it)) refreshInstrumentsInBackground() }?.list

    @Volatile private var instrumentsTriedAt = 0L
    private val instrumentsLoading = java.util.concurrent.atomic.AtomicBoolean(false)
    /** After a failed background fetch, the next is tried this much later at the soonest. */
    private const val INSTRUMENTS_RETRY_MS = 2 * 60_000L
    /** B5-D review: logged out, a background list read is tried again this soon (memory and a job only: no vault on the caller). */
    private const val LOGGED_OUT_RETRY_MS = 10_000L

    /**
     * 20 Nov 2026: today's instruments list fetched in the background (logged in only; one at a time; at most every
     * [INSTRUMENTS_RETRY_MS]). Returns at once: memory only on the caller's thread (never the network, a file or the vault).
     */
    internal fun refreshInstrumentsInBackground() {
        val now = System.nanoTime() / 1_000_000L
        if (instrumentsTriedAt != 0L && now - instrumentsTriedAt in 0 until INSTRUMENTS_RETRY_MS) return
        if (!instrumentsLoading.compareAndSet(false, true)) return
        instrumentsTriedAt = now
        // The session is looked at on the background thread (it may read the vault): never on the caller's.
        // B5-D review: logged out, the next try is 10 s later (never a new job per call on the hot paths that ask; soon after a login).
        readScope.launch {
            try {
                if (loggedIn) runCatching { downloadInstruments() }
                else instrumentsTriedAt = System.nanoTime() / 1_000_000L - INSTRUMENTS_RETRY_MS + LOGGED_OUT_RETRY_MS
            } finally { instrumentsLoading.set(false) }
        }
    }

    /** TEST ONLY: the background fetch's wait forgotten. */
    internal fun resetInstrumentsRetryForTest() { instrumentsTriedAt = 0L }

    /**
     * 22 Nov 2026 (B6 review, item 5: "the instrument list can be downloaded under the arms' locks"): the instruments list held in MEMORY
     * only - never the file, never Zerodha - for an entry decided under an arm's lock (the ORB family's, the arms' live copies', Pine's):
     * a download there is megabytes with no budget, and every other trade's look waits on that lock. Not today's list: answered with it,
     * and today's fetched in the background, as [cachedInstruments] does. None in memory (a cold start): null - the caller refuses this
     * entry with a note - and the list is loaded in the background ([warmInstrumentsInBackground]: the file first, then Zerodha's).
     */
    fun instrumentsInMemory(): List<Kite.Instrument>? {
        val m = instrumentsMemo ?: run { warmInstrumentsInBackground(); return null }
        if (!instrumentsCurrent(m)) refreshInstrumentsInBackground()
        return m.list
    }

    private val instrumentsWarming = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var instrumentsWarm: kotlinx.coroutines.Job? = null

    /**
     * 22 Nov 2026 (B6 review, item 5): the instruments list read into memory in the background - the saved file, then today's from Zerodha
     * when the file is not today's ([refreshInstrumentsInBackground], throttled). One at a time; returns at once (nothing on the caller).
     */
    internal fun warmInstrumentsInBackground() {
        if (!instrumentsWarming.compareAndSet(false, true)) return
        instrumentsWarm = readScope.launch {
            try {
                runCatching { instrumentsFile() }
                if (!instrumentsCurrent(instrumentsMemo)) refreshInstrumentsInBackground()
            } finally { instrumentsWarming.set(false) }
        }
    }

    /**
     * 22 Nov 2026 (B6 review): the instruments list read into memory once the app's start has read and watched the position books (never
     * before them: [PositionsFirst.awaitPositions]), so the arms' first live entry finds it there ([instrumentsInMemory]). In the background.
     */
    fun warmAfterPositions() {
        readScope.launch { runCatching { PositionsFirst.awaitPositions() }; warmInstrumentsInBackground() }
    }

    /** 22 Nov 2026 (B6 review): whether an instruments list is in memory - only looked at (no load started, nothing read). */
    fun instrumentsHeld(): Boolean = instrumentsMemo != null

    /** TEST ONLY (22 Nov 2026, B6 review): the instruments list in memory forgotten, as after a restart (the file is kept). */
    internal fun forgetInstrumentsMemoForTest() { instrumentsMemo = null }

    /** NIFTY and BANKNIFTY options from GET /instruments/NFO, cached for the day. */
    suspend fun instruments(): List<Kite.Instrument> {
        val had = instrumentsFile()
        had?.takeIf { instrumentsCurrent(it) }?.let { return it.list }
        // 20 Nov 2026 (B5-D review): a list of today fetched before the morning's dump answers at once, as before - its re-read is the
        // background refresh's (one at a time, throttled: [refreshInstrumentsInBackground]), never a download every caller waits on.
        if (had != null && had.day == Market.today().toString()) { refreshInstrumentsInBackground(); return had.list }
        return downloadInstruments()
    }

    /** Today's NFO list downloaded now and kept ([instruments]; the background refresh). A worker thread only. */
    private suspend fun downloadInstruments(): List<Kite.Instrument> {
        val f = File(app.filesDir, "kite_instruments.json")
        val text = call("GET", "/instruments/NFO", raw = true) as String
        val list = com.optionslab.ira.InstrumentsFile.compact(Kite.parseInstruments(text.lineSequence(), setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")))
        // The market recorder's index futures from the same dump (never a second download for them).
        runCatching { keepNfoFutures(text) }
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.token).put(it.tradingSymbol).put(it.name).put(it.expiry.toString()).put(it.strike).put(it.lotSize).put(it.right.name).put(it.tickSize)) }
        val at = Market.now().toInstant().toEpochMilli()
        writeAtomic(f, JSONObject().put("day", Market.today().toString()).put("at", at).put("i", a).toString())
        instrumentsMemo = InstrumentsMemo(f.lastModified() to f.length(), Market.today().toString(), list, at)
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

    /** Review, 20 Nov 2026: when the list in memory was fetched (null: not known), for [cachedBfoOptions]. */
    @Volatile private var bfoAt: java.time.LocalDateTime? = null

    private fun keepBfo(list: List<Kite.Instrument>, at: java.time.LocalDateTime?) { bfoAt = at; bfoOptions = Market.today() to list }

    /**
     * Today's SENSEX options list, memory only (never the network or a file): null when not read today. Review, 20 Nov 2026: nor
     * when it was fetched before the morning's dump ([com.optionslab.ira.InstrumentsFile.current], as the NFO list): read again.
     */
    fun cachedBfoOptions(): List<Kite.Instrument>? = bfoOptions?.takeIf { it.first == Market.today() }?.second
        // (Merge review: a list fetched before the dump still answers - as the NFO list does - while today's is read behind it.)
        ?.also { if (!bfoCurrent()) runCatching { loadBfoOptionsInBackground() } }

    /**
     * B5 merge review: today's SENSEX list only when fetched after the morning's dump (null otherwise; its read is started) - for the
     * morning check ([bfoOptionsNow]) and the expiry square-off's wait, which refuse an earlier list as before. Memory only.
     */
    fun currentBfoOptions(): List<Kite.Instrument>? = cachedBfoOptions()?.takeIf { bfoCurrent() }

    private fun bfoCurrent(): Boolean = runCatching {
        com.optionslab.ira.InstrumentsFile.current(Market.today().toString(), bfoAt, Market.now().toLocalDateTime())
    }.getOrDefault(true)

    /** Tests: SENSEX's list forgotten and its 10-minute retry wait cleared. */
    internal fun resetBfoForTest(keepFile: Boolean = false) {
        bfoOptions = null; bfoAt = null; bfoTriedAt = 0L
        if (!keepFile && ::app.isInitialized) runCatching { bfoFile().delete() }
    }

    /**
     * Read today's SENSEX options in the background (logged in only; at most every 10 minutes after a failure). Returns at once:
     * the stream's price of a SENSEX paper ticket follows on the next look once the list is in.
     */
    fun loadBfoOptionsInBackground() {
        if (bfoOptions?.first == Market.today() && bfoCurrent()) return
        val now = System.nanoTime() / 1_000_000L
        // 20 Nov 2026: 2 minutes after a failure (it was 10: a held SENSEX position's prices waited that long on quote calls); on the
        // monotonic clock, so a wall clock set back never holds it.
        if ((bfoTriedAt != 0L && now - bfoTriedAt in 0 until BFO_RETRY_MS) || !bfoLoading.compareAndSet(false, true)) return
        bfoTriedAt = now
        readScope.launch {
            try {
                // Review, 20 Nov 2026: the session is looked at here, on the background thread (it may read the vault), never the caller's.
                // B5-D review: logged out, tried again 10 s later (not the 2 minutes after a failure), so a login is picked up at once.
                if (!loggedIn) { bfoTriedAt = System.nanoTime() / 1_000_000L - BFO_RETRY_MS + LOGGED_OUT_RETRY_MS; return@launch }
                // 20 Nov 2026: after a restart, today's list saved earlier today is read back first (no download; a background thread).
                val saved = runCatching { readBfoFile() }.getOrNull()
                if (saved != null) { keepBfo(saved.first, saved.second); return@launch }
                val text = call("GET", "/instruments/BFO", raw = true) as String
                val list = Kite.parseInstruments(text.lineSequence(), setOf("SENSEX"), segment = "BFO-OPT")
                keepBfo(list, Market.now().toLocalDateTime())
                runCatching { writeBfoFile(list) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tried again after 10 minutes; until then the ticket is priced from the candle feed, as before.
            } finally { bfoLoading.set(false) }
        }
    }

    /** 3 Nov: today's SENSEX options, read now when not yet in memory (a worker thread only: the morning check). */
    suspend fun bfoOptionsNow(): List<Kite.Instrument> {
        currentBfoOptions()?.let { return it }
        // (B5 review: a background read already on its way is waited for - 15 s at most - never a second download beside it.)
        val until = System.nanoTime() / 1_000_000L + 15_000L
        while (bfoLoading.get() && System.nanoTime() / 1_000_000L < until) kotlinx.coroutines.delay(100)
        currentBfoOptions()?.let { return it }
        runCatching { readBfoFile() }.getOrNull()?.let { keepBfo(it.first, it.second); return it.first }
        val text = try { call("GET", "/instruments/BFO", raw = true) as String } catch (e: Exception) {
            // (B5 review: today's could not be read - the list in memory, fetched before the morning's dump, still answers, with a note.)
            if (e is kotlinx.coroutines.CancellationException) throw e
            cachedBfoOptions()?.let { earlier ->
                runCatching { Diag.record("zerodha", "SENSEX's list for today could not be read (${e.javaClass.simpleName}): the earlier one (before the morning's dump) is used") }
                return earlier
            }
            throw e
        }
        return Kite.parseInstruments(text.lineSequence(), setOf("SENSEX"), segment = "BFO-OPT").also {
            keepBfo(it, Market.now().toLocalDateTime())
            runCatching { writeBfoFile(it) }
        }
    }

    private const val BFO_RETRY_MS = 2 * 60_000L

    /** 20 Nov 2026: today's SENSEX options saved on the phone (public market data, like the NFO list), so a restart reads them back. */
    private fun bfoFile() = File(app.filesDir, "kite_instruments_bfo.json")

    /** Today's saved SENSEX list (fetched after the morning's dump, [com.optionslab.ira.InstrumentsFile.current]), or null. A worker thread only. */
    private fun readBfoFile(): Pair<List<Kite.Instrument>, java.time.LocalDateTime>? {
        val f = bfoFile()
        if (!f.exists()) return null
        val saved = f.bufferedReader(Charsets.UTF_8).use { com.optionslab.ira.InstrumentsFile.readSaved(it) } ?: return null
        val at = saved.at?.let { java.time.Instant.ofEpochMilli(it).atZone(com.optionslab.engine.IST).toLocalDateTime() } ?: return null
        if (!com.optionslab.ira.InstrumentsFile.current(saved.day, at, Market.now().toLocalDateTime())) return null
        return saved.list.takeIf { it.isNotEmpty() }?.let { it to at }
    }

    private fun writeBfoFile(list: List<Kite.Instrument>) {
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.token).put(it.tradingSymbol).put(it.name).put(it.expiry.toString()).put(it.strike).put(it.lotSize).put(it.right.name).put(it.tickSize)) }
        writeAtomic(bfoFile(), JSONObject().put("day", Market.today().toString()).put("at", Market.now().toInstant().toEpochMilli()).put("i", a).toString())
    }

    /** 3 Nov: whether the account's commodity (MCX) segment is enabled at Zerodha (GET /user/margins/commodity); null: not known. */
    suspend fun commodityEnabled(): Boolean? = runCatching {
        (call("GET", "/user/margins/commodity") as? JSONObject)?.takeIf { it.has("enabled") }?.getBoolean("enabled")
    }.getOrNull()

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
    suspend fun placeOrder(o: Kite.Order, exit: Boolean = false, arm: String? = null, intended: Double? = null,
                           verdict: com.optionslab.engine.risk.OrderFingerprint.Verdict? = null, stop: Double? = null, target: Double? = null): String {
        // 22 Oct: the order fingerprint ([OrderVerdicts]): a NEW entry goes only with a verdict that cleared this exact order today
        // ([verdict], stamped by the check that cleared it; [stop] / [target] what the sender rests, as the check saw them). The
        // fingerprint of what is about to be sent is computed here, in memory, and a difference, a verdict from another day or
        // none at all refuses the order and says which field changed. An exit never asks: closing lowers risk.
        if (!exit) OrderVerdicts.refusal(verdict, OrderVerdicts.liveFields(o, Market.today(), OrderVerdicts.sourceOf(o, arm), stop, target))
            ?.let { why -> throw KiteError("fingerprint", why) }
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
                com.optionslab.app.work.TradingBusy.during { sendChecked(o, true, arm, intended) }.also { ExitCoordinator.answered(ticket, it); noteKind(it, entry = false) }
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
        fun count() {
            ExposureCapHost.liveEntered(o.tradingSymbol, o.exchange, o.side == Kite.Side.BUY, arm ?: o.tag)
            // 25 Oct (Boss's decision on the app audit, V1): the IV of each bot's live entry, record only (worked out in the background).
            if (o.side == Kite.Side.BUY && o.exchange != com.optionslab.engine.mcx.Mcx.EXCHANGE)
                runCatching { BossRiskHost.liveEntryIv(o.tradingSymbol, arm ?: o.tag, intended ?: o.price ?: tokenKnown("${o.exchange}:${o.tradingSymbol}")?.let { KiteStream.tick(it)?.last }) }
        }
        return when (val r = entryGate.enter("${o.exchange}:${o.tradingSymbol}", recheck = { entryRecheck(o, arm, intended, stop) }) {
            com.optionslab.app.work.TradingBusy.during {
                // (22 Nov 2026, B6-E: marked before it goes - sent, refused or its reply lost - so a restore waits for its booking: [entriesQuiet].)
                lastEntrySentAt = System.currentTimeMillis()
                // (20 Nov 2026, audit B5: its POST in the lane below the exits' ([EntrySend]); its id kept as an entry's for the kill switch.)
                try { kotlinx.coroutines.withContext(EntrySend(bounded = counted)) { sendChecked(o, false, arm, intended) }.also { noteKind(it, entry = true); if (counted) count() } }
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
     * (its id, as if Zerodha had answered); the book unreadable, the order's fate is unknown (not definite, so the caller looks
     * again). Only what really exists changes the outcome. An entry's look holds the entry door meanwhile: no other entry goes while
     * this one's fate is unknown.
     * 22 Nov 2026 (B6 review, item 1): not there yet is no proof either (the book can trail the POST longer, a LIMIT may rest and fill
     * later): [KiteUnsure], never the error itself - before, it was taken as a refusal and the entry's intent dropped.
     */
    private suspend fun sendChecked(o: Kite.Order, exit: Boolean, arm: String?, intended: Double?): String = try {
        send(o, exit, arm, intended)
    } catch (e: KiteError) {
        if (e.type !in UNSURE) throw e
        val found = try { findRecentRetrying(o, emptyList(), afterError = true) } catch (x: kotlinx.coroutines.CancellationException) { throw x
        } catch (_: Exception) { throw IOException("${e.message} (Zerodha's ${e.type}); its order book could not be read, so the order may exist") }
        runCatching { Diag.record("zerodha", "${o.tradingSymbol}: Zerodha answered ${e.type} (${e.message}); " +
            (found?.let { "the order is in the book: $it" } ?: "no such order in the book yet: its state is unknown (looked for again)")) }
        found ?: throw KiteUnsure(e.type, "${e.message} (Zerodha's ${e.type}); the order is not in its order book yet, so it may still exist")
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
     * 20 Nov 2026 (audit B5, item 4: "lost-reply matching can adopt an identical order"): what [findRecent] knows of each order
     * [send] sent, by the very order object (memory only, kept [SENT_KEEP_MS]): when it was sent, by the phone's market clock
     * ([at]), and the order book as last read before it ([before]: every order in it was there before this one, so none of them
     * is ever taken for its lost reply). Set before the POST, from memory alone: nothing is read for it on the order path.
     */
    private class SendMark(val at: java.time.LocalDateTime, val atMs: Long, val before: List<OrderRow>?, val exit: Boolean,
        /** The phone clock's offset to Zerodha's as measured in the last [OFFSET_FRESH_MS] at the send (null: none fresh), so a clock stepped after the send never moves the window. */
        val offset: Long?)
    private val sendMarks: MutableMap<Kite.Order, SendMark> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())

    private fun markSend(o: Kite.Order, exit: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(sendMarks) {
            if (sendMarks.size > 100) sendMarks.entries.removeIf { now - it.value.atMs > SENT_KEEP_MS }
            sendMarks[o] = SendMark(Market.now().toLocalDateTime(), now, keptOrders?.value, exit,
                serverOffset?.takeIf { (_, at) -> now - at in 0..OFFSET_FRESH_MS }?.first)
        }
    }

    /**
     * 20 Nov 2026 (audit B5, item 4): the phone clock's offset against Zerodha's server (ms, +: the phone ahead) from the last read's
     * Date header, and when it was measured. Memory only.
     */
    @Volatile private var serverOffset: Pair<Long, Long>? = null
    /** An offset older than this is not trusted for the tight window. */
    private const val OFFSET_FRESH_MS = 10 * 60_000L
    /** The tight window's allowance before the send (Zerodha's stamps are whole seconds; the offset is good to about a second). */
    private const val SENT_SLACK_S = 5L
    /** An offset that moved more than this between the send and the search: the phone's clock was stepped; the wide window is used. */
    private const val OFFSET_MOVED_MS = 2_000L

    /**
     * 20 Nov 2026 (audit B5, item 9: the kill switch did not cancel working entry orders): the ids of orders this phone sent, as an
     * entry's ([placeOrder] through the door) or an exit's, kept [SENT_KEEP_MS] in memory, so the kill switch cancels only entries.
     */
    private val entryIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val exitIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 22 Nov 2026 (B6-E, round 3): no entry inside the door now, and none went out of it to Zerodha in the last [settleMs] - sent, refused
     * or its reply lost (its arm may still be waiting for the fill, searching for it, or booking it) - a backup restore waits for this before
     * it reads which live positions are open ([Backup.restore]). Memory only.
     */
    internal fun entriesQuiet(nowMs: Long = System.currentTimeMillis(), settleMs: Long = ENTRY_SETTLE_MS): Boolean =
        entriesQuiet(entryGate.busy, lastEntrySentAt, nowMs, settleMs)

    /** [entriesQuiet] from its parts: the door not [busy] and no entry sent (the last at [sentAt]; 0: none) in the [settleMs] before [nowMs]. */
    internal fun entriesQuiet(busy: Boolean, sentAt: Long, nowMs: Long, settleMs: Long): Boolean =
        !busy && (sentAt <= 0L || nowMs - sentAt !in 0 until settleMs)

    /**
     * (An entry's fill is waited for at most 20 s - a hand order's, 15 s an arm's or Pine's - then the rest cancelled and confirmed, a lost
     * reply searched for about 6 s, and only then booked: 25 s covers them.)
     */
    internal const val ENTRY_SETTLE_MS = 25_000L

    /** 22 Nov 2026 (B6-E, round 3): when the last entry went out of the door to Zerodha ([entriesQuiet]); 0: none in this process. */
    @Volatile private var lastEntrySentAt = 0L

    private fun noteKind(id: String, entry: Boolean) {
        val now = System.currentTimeMillis()
        val m = if (entry) entryIds else exitIds
        if (m.size > 200) m.entries.removeIf { now - it.value > SENT_KEEP_MS }
        m[id] = now
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
        runCatching { markSend(o, exit) }
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

    /** 20 Nov 2026 (audit B5, item 5): the least an [awaitOrder] read is given near the end of its wait. */
    private const val POLL_MIN_BUDGET_MS = 1_000L

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
        // 20 Nov 2026 (audit B5, item 5: "one failed poll cancels a working limit"): a read that fails is read again on the same
        // schedule until [timeoutMs]; the callers cancel only on what Zerodha says or once the wait is over. Only when no read at all
        // answered in the whole wait does this throw (the order's state unknown, as before). Each read is held to what is left of the
        // wait (at most a read's own budget), so the wait ends on time.
        var lastErr: Exception? = null
        var answered = false
        while (true) {
            streamed(orderId)?.let { f ->
                runCatching { OrderTiming.fillKnown(live = true, ms = System.currentTimeMillis() - start, viaStream = true) }
                ended(f)
                return f
            }
            val seen = KiteStream.orderEvents.value
            val hist = try {
                call("GET", "/orders/$orderId", budgetMs = (end - System.currentTimeMillis()).coerceIn(POLL_MIN_BUDGET_MS, READ_BUDGET_MS)) as JSONArray
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: NotLoggedIn) { throw e                     // (no session: reading again cannot help)
            } catch (e: Exception) { lastErr = e; null }
            if (hist != null) answered = true
            if (hist != null && hist.length() > 0) last = hist.getJSONObject(hist.length() - 1)
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
        // No read answered in the whole wait: unknown, as a failed read was before (the callers keep the order as unconfirmed).
        if (!answered) lastErr?.let { throw it }
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
    suspend fun findRecent(o: Kite.Order, known: Collection<String>, afterError: Boolean = false, liveOnly: Boolean = false,
                           sentAt: java.time.LocalDateTime? = null,
                           /** 20 Nov 2026: the earliest an order may be stamped (a lost exit sale's send less 3 minutes, from its caller) when
                            *  this process has no send mark for [o] (after a restart); the mark's window and its "before" list come first. */
                           since: java.time.LocalDateTime? = null,
                           /** 20 Nov 2026: the earliest such order after [since] (a lost sale's own), not the latest. */
                           earliest: Boolean = false): String? {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val rs = rows(call("GET", "/orders") as JSONArray)
        fun at(r: JSONObject) = runCatching { java.time.LocalDateTime.parse(r.optString("order_timestamp"), fmt) }.getOrNull()
        // "Now" by Kite's own clock when the book has a later order than the phone's clock says (a phone running
        // slow would otherwise widen the window); never earlier than the phone's. 25 Oct: the phone's clock is [Market.now]
        // (the system clock in the app; in a test the pinned market clock, which the fake Zerodha stamps its orders from too).
        val now = rs.mapNotNull(::at).fold(Market.now().toLocalDateTime()) { a, t -> if (t.isAfter(a)) t else a }
        // 20 Nov 2026 (audit B5, item 4): an order this phone sent ([markSend]) is matched only from its own send on: from the send's
        // time on Zerodha's clock less [SENT_SLACK_S] when the phone's offset to it was measured in the last [OFFSET_FRESH_MS], else
        // from three minutes before the send (never narrower than the old three minutes before now, so its own order is never missed
        // for a clock it cannot trust). An order in the book read before the send is never taken, whatever its time.
        // (20 Nov 2026, merge review P1: the send mark - kept by the order object itself, so a twin's identical order never shares it - comes
        // first; a caller's own [since] only when there is none, after a restart.)
        val mark = synchronized(sendMarks) { sendMarks[o] }
        // (Review: the offset as it was at the send, never one measured later; one that moved since by more than [OFFSET_MOVED_MS] - the
        // phone's clock stepped - leaves the tight window for the three minutes.)
        val offNow = serverOffset?.takeIf { (_, at) -> System.currentTimeMillis() - at in 0..OFFSET_FRESH_MS }?.first
        val off = mark?.offset?.takeIf { o -> offNow == null || kotlin.math.abs(offNow - o) <= OFFSET_MOVED_MS }
        val from = mark?.let { m -> if (off != null) m.at.minusNanos(off * 1_000_000L).minusSeconds(SENT_SLACK_S) else m.at.minusMinutes(3) }
            ?: since ?: sentAt?.minusMinutes(3) ?: now.minusMinutes(3)
        val before = mark?.before?.mapTo(HashSet()) { it.id }.orEmpty()
        val match: (JSONObject) -> Boolean = {
            it.optString("tradingsymbol") == o.tradingSymbol && it.optString("transaction_type") == o.side.name &&
                it.optInt("quantity") == o.quantity && it.optString("tag") == o.tag.filter { c -> c.isLetterOrDigit() }.take(20) &&
                // 18 Oct: the order's own type too (a resting SL of the same size and tag is never taken for a market exit's lost reply).
                it.optString("order_type") == o.orderType &&
                it.optString("order_id") !in known && !sentIds.containsKey(it.optString("order_id")) && at(it)?.isBefore(from) == false &&
                // (20 Nov 2026, delta review: a stop a trade took as its own another way is never found for another's lost reply.)
                !claimedIds.containsKey(it.optString("order_id")) &&
                it.optString("order_id") !in before &&
                // 20 Nov 2026 (audit B5, item 4): a LIMIT's or SL's price and a stop's trigger must be the ones sent, always (before, only
                // after Kite's error): a resting order at another level is never taken for this one.
                samePrices(o, it) &&
                // (20 Nov 2026, audit B5 deep review: a lost stop's search never takes an order that has ended.)
                (!liveOnly || !diedUnfilled(it)) &&
                (!afterError || adoptableAfterError(o, it))
        }
        return (if (earliest) rs.firstOrNull(match) else rs.lastOrNull(match))?.optString("order_id")?.also { countSent(); noteSent(it); ExitCoordinator.adopted(o, it)
            // (20 Nov 2026, review: a lost reply found is kept as the entry or exit it was sent as, for the kill switch.)
            mark?.let { m -> noteKind(it, entry = !m.exit) } }
    }

    /** 20 Nov 2026 (audit B5 deep review): [r] ended with nothing filled - REJECTED, or CANCELLED unfilled. */
    private fun diedUnfilled(r: JSONObject): Boolean {
        val st = r.optString("status").uppercase()
        return st == "REJECTED" || (st == "CANCELLED" && r.optInt("filled_quantity", 0) <= 0)
    }

    /** [r]'s price (a LIMIT's or an SL's) and trigger (an SL's or an SL-M's) are [o]'s, to half a tick. */
    private fun samePrices(o: Kite.Order, r: JSONObject): Boolean {
        fun same(want: Double?, field: String): Boolean {
            val w = want ?: return false
            val got = r.optDouble(field, Double.NaN)
            return got.isFinite() && kotlin.math.abs(got - w) <= o.tickSize / 2 + 1e-9
        }
        if (o.hasPrice && !same(o.price, "price")) return false
        if (o.hasTrigger && !same(o.triggerPrice, "trigger_price")) return false
        return true
    }

    /**
     * [findRecent] after Kite's error: the row's prices and its status as described there - a LIMIT's or an SL's price, and an
     * SL's or an SL-M's trigger too (a resting stop at another level is never taken for a new one).
     */
    internal fun adoptableAfterError(o: Kite.Order, r: JSONObject): Boolean {
        if (!samePrices(o, r)) return false
        val status = r.optString("status").uppercase()
        if (status == "REJECTED") return false
        if (status == "CANCELLED" && r.optInt("filled_quantity", 0) <= 0) return false
        return true
    }

    /**
     * True when [e] from [placeOrder] means the order was certainly NOT placed: Zerodha answered with a
     * refusal, or nothing was sent (not logged in). A lost reply (timeout, dropped connection, an
     * unreadable answer, Kite's own NetworkException) is not definite: the order may exist. Kite's DataException and
     * GeneralException on an order are checked against the order book inside [placeOrder] first ([UNSURE]); not found there they are
     * [KiteUnsure] (22 Nov 2026, B6 review), not definite either.
     */
    // 20 Nov 2026 (audit B5, item 2): and a write that never left the phone ([NotSent]: no network, no address for Zerodha, the
    // connection refused or not made in time, the relay down). Before, those read as lost replies and were searched for.
    fun definite(e: Throwable): Boolean = e is NotLoggedIn || e is NotSent || (e is KiteError && e.type != "NetworkException")

    /**
     * 20 Nov 2026 (audit B5 deep review): what to say of a write that never left the phone ([NotSent]) - not a refusal by Zerodha, which
     * never saw it: "The [what] was not sent (no connection to Zerodha); trying again."
     */
    fun notSentWords(what: String): String = "The $what was not sent (no connection to Zerodha); trying again."

    // ---- lost replies not found yet (20 Nov 2026, audit B5 deep review) ----------------------------------------------------------

    /**
     * 20 Nov 2026 (audit B5 deep review: the write's 15 s limit makes lost replies more common, and a caller that took "lost and not found
     * in ~4 s" as "not placed" could send a second order or rest a second stop): a write whose reply was lost and that the book did not
     * show yet, kept by the caller's [key] (memory only). Its state is UNKNOWN: the caller never sends it again and never rests a second
     * stop beside it; it asks [lookLost] on its later looks. The book is searched in the BACKGROUND (never on the caller's lock; at most
     * every [LOST_LOOK_GAP_MS]) and the next look acts on what was found. Found: the order (adopted, as any lost reply found). Given up
     * on only once a read of the book that SUCCEEDED after [LOST_SEARCH_MS] still does not show it (Boss told loudly, once; the caller may
     * go on - the book would have shown an order Zerodha took long before). A book that cannot be read never gives up: past
     * [LOST_SEARCH_MS] Boss is told the order's state is unknown, and it stays UNKNOWN.
     */
    private class LostWrite(val order: Kite.Order, val known: Set<String>, sinceMs: Long, val what: String, val liveOnly: Boolean,
                            /** When it was sent, by the phone's market clock: the search's start even once the send's own mark is gone. */
                            val sentAt: java.time.LocalDateTime) {
        /** When it was noted (phone ms); moved only by [ageLostForTest]. */
        @Volatile var sinceMs = sinceMs
        @Volatile var lookedMs = 0L
        /** When a caller last asked after it ([lookLost]): one no caller has asked after for long belongs to a trade long gone. */
        @Volatile var askedMs = sinceMs
        @Volatile var foundId: String? = null
        /** 23 Nov 2026 (B6 follow-ups, round 2): when [foundId] was found (phone ms; 0: not yet) - [lostSales]. */
        @Volatile var foundAtMs = 0L
        /** When a read of the book last SUCCEEDED without finding it (0: never). */
        @Volatile var readOkAtMs = 0L
        /** When a read of the book last FAILED (0: never). */
        @Volatile var readFailAtMs = 0L
        @Volatile var searching: kotlinx.coroutines.Job? = null
        @Volatile var unknownSaid = false
    }
    private val lostWrites = java.util.concurrent.ConcurrentHashMap<String, LostWrite>()
    // (B5 merge review: the searches' order-book reads in the stop checks' lane, SAFETY - an ORB / Liquidity exit or a stop's re-rest
    // waits on them, so they are never starved behind entries or Jarvis; only exits and stops (EXIT) go before them.)
    private val lostScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + Lane(com.optionslab.ira.RateGate.Priority.SAFETY) + com.optionslab.app.data.ScopeGuard.handler("Broker.lostScope"))

    /** TEST ONLY: the lane the lost replies' searches read the book in. */
    internal fun lostLaneForTest(): com.optionslab.ira.RateGate.Priority? = lostScope.coroutineContext[Lane]?.priority
    /** How long a lost reply not found is looked for before it may be given up on (and Boss told). */
    const val LOST_SEARCH_MS = 120_000L
    /** The least time between two searches of one lost reply (a stream look comes every tick). */
    private const val LOST_LOOK_GAP_MS = 3_000L
    /** TEST SEAM: [LOST_SEARCH_MS] shortened. */
    @Volatile internal var lostSearchMsForTest: Long? = null
    /** TEST SEAM: [LOST_LOOK_GAP_MS] shortened. */
    @Volatile internal var lostLookGapMsForTest: Long? = null
    /** TEST SEAM: the book searched on the caller's thread (the tests' looks are one after another), not in the background. */
    @Volatile internal var lostSearchInlineForTest = false

    /** What [lookLost] found: the order, still looking (UNKNOWN: send nothing), or given up on (Boss told; the caller may go on). */
    sealed class LostLook { data class Found(val id: String, val qty: Int, val trigger: Double? = null) : LostLook() { var symbol: String = "" }; object Searching : LostLook(); object GaveUp : LostLook() }

    /**
     * [o] ([what], for Boss's words) was sent under [key], its reply lost and not found in the book yet ([known]: never adopted).
     * [liveOnly] (a stop): an order that died unfilled (REJECTED, or CANCELLED with nothing filled) is never taken for it - a stale key never
     * adopts a dead stop; one that filled (COMPLETE, or cancelled after a part) is still found, so its caller books the fill. Never pruned
     * while a caller still asks after it (an unknown order dropped could be sent twice): each ends found, given up on after a good read,
     * or forgotten; only one no caller has asked after for an hour (its trade long gone) is let go, and only past 500 kept.
     */
    fun noteLost(key: String, o: Kite.Order, known: Collection<String>, what: String, liveOnly: Boolean = false,
                 /**
                  * 22 Nov 2026 (B6 review): a lost reply its caller saved and takes back after a restart - when it was first noted (phone
                  * ms: its [LOST_SEARCH_MS] window runs on from then) and when it was sent (its search's start). Null: now.
                  */
                 sinceMs: Long? = null, sentAt: java.time.LocalDateTime? = null) {
        // (Bounded: only ones no caller has asked after for an hour - their trades long gone - are let go, and only past 500.)
        if (lostWrites.size > 500) lostWrites.entries.removeIf { e ->
            (System.currentTimeMillis() - e.value.askedMs > 3_600_000L).also { gone -> if (gone) e.value.searching?.cancel() }
        }
        lostWrites.putIfAbsent(key, LostWrite(o, known.toSet(), sinceMs ?: System.currentTimeMillis(), what, liveOnly,
            sentAt ?: runCatching { Market.now().toLocalDateTime() }.getOrElse { java.time.LocalDateTime.now() }))
    }

    /** Whether a lost reply is being looked for under [key]. Memory only. */
    fun lostPending(key: String): Boolean = lostWrites.containsKey(key)

    /** The keys of the lost replies being looked for that start with [prefix]. Memory only. */
    fun lostKeys(prefix: String): List<String> = lostWrites.keys.filter { it.startsWith(prefix) }

    /**
     * 23 Nov 2026 (B6 follow-ups, item 2): a book's unresolved lost exit SALES on one position row, for the square-offs
     * ([SquareOffExit.lostPendingProviders]): the quantity of the lost replies kept under keys starting with [prefix] that sell [symbol]
     * in [product], noted less than the lost search's window ([LOST_SEARCH_MS]) and a minute ago. Past that age one still kept is not
     * counted (its search is over, or nobody asks after it), so a write no caller takes off never holds a square-off back for good.
     * Round 2: one found in Zerodha's book is in the square-offs' fresh reads from then on - counted [FOUND_COUNTED_MS] more only (found,
     * or found and taken by its caller: [foundLately]), so a read that began just before it was found, or a caller that took it at once,
     * never leaves it in neither, and a square-off is held back seconds, not minutes. Memory only, never a wait.
     */
    fun lostSales(prefix: String, symbol: String, product: String): Int {
        val now = System.currentTimeMillis()
        val window = (lostSearchMsForTest ?: LOST_SEARCH_MS) + 60_000L
        fun row(o: Kite.Order) = o.side == Kite.Side.SELL && o.tradingSymbol == symbol && o.product == product
        val kept = lostWrites.entries.sumOf { (k, w) ->
            if (k.startsWith(prefix) && row(w.order) && now - w.sinceMs < window && (w.foundId == null || now - w.foundAtMs < FOUND_COUNTED_MS))
                w.order.quantity else 0
        }
        return kept + foundLately.entries.sumOf { (k, v) -> if (k.startsWith(prefix) && row(v.first) && now - v.second < FOUND_COUNTED_MS) v.first.quantity else 0 }
    }

    /** 23 Nov 2026 (B6 follow-ups, round 2): how long a found lost sale is still counted for the square-offs ([lostSales]). */
    const val FOUND_COUNTED_MS = 20_000L
    /** Lost replies found and taken by their caller lately (key -> the order, when it was found): [lostSales]. Memory only; bounded. */
    private val foundLately = java.util.concurrent.ConcurrentHashMap<String, Pair<Kite.Order, Long>>()

    /** The lost reply under [key] forgotten: its stop was taken another way, or its position closed (its search stopped). Memory only. */
    fun forgetLost(key: String) { lostWrites.remove(key)?.searching?.cancel() }

    /**
     * 20 Nov 2026 (delta review: two trades on one contract, one's stop reply lost, the other took that stop as an orphan, then the first's
     * search found the same order - two owners, one lot with no stop): order [id] taken as a trade's stop another way (an orphan adopted).
     * No lost reply's search finds it from now on, and one that found it already and was not asked yet looks again. Memory only (bounded).
     */
    fun claimStop(id: String) {
        val now = System.currentTimeMillis()
        if (claimedIds.size > 500) claimedIds.entries.removeIf { now - it.value > 86_400_000L }
        claimedIds[id] = now
        for (w in lostWrites.values) synchronized(w) { if (w.foundId == id) w.foundId = null }
    }
    private val claimedIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 20 Nov 2026 (delta review): whether order [r] may be a lost reply kept under a key other than [exceptKey] - found as it and not
     * asked after yet, or still looked for with the same contract, side, quantity, order type, tag and trigger and placed no earlier than that search's
     * window opens (a time that cannot be read: may be). Such an order is left to its own lost reply: never adopted as another's orphan.
     */
    fun lostMayBe(r: OrderRow, exceptKey: String? = null): Boolean = lostWrites.entries.any { (k, w) -> k != exceptKey && mayBe(w, r) }

    private fun mayBe(w: LostWrite, r: OrderRow): Boolean {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val placed = runCatching { java.time.LocalDateTime.parse(r.placedAt, fmt) }.getOrNull()
        w.foundId?.let { return it == r.id }
        val o = w.order
        return o.tradingSymbol == r.symbol && o.side.name == r.side && o.quantity == r.qty && o.orderType == r.type &&
            o.tag.filter { c -> c.isLetterOrDigit() }.take(20) == r.tag &&
            (!o.hasTrigger || o.triggerPrice?.let { kotlin.math.abs(it - r.trigger) <= o.tickSize / 2 + 1e-9 } != false) &&
            (placed == null || !placed.isBefore(w.sentAt.minusMinutes(3)))
    }

    /** The background searches of every lost reply stopped (a reset). */
    private fun cancelLostSearches() { for (w in lostWrites.values) w.searching?.cancel() }

    /** TEST ONLY: the background search of the lost reply under [key] (the last one started), if any. */
    internal fun lostSearchForTest(key: String): kotlinx.coroutines.Job? = lostWrites[key]?.searching

    /** 20 Nov 2026 (delta review): how long ago the lost reply under [key] was noted (null: none kept). */
    fun lostAgeMs(key: String): Long? = lostWrites[key]?.let { System.currentTimeMillis() - it.sinceMs }

    /** 20 Nov 2026 (delta review): the trigger the lost stop under [key] was sent at (null: none kept, or no trigger). */
    fun lostTrigger(key: String): Double? = lostWrites[key]?.order?.triggerPrice

    /** TEST ONLY: the lost reply under [key] noted [ms] earlier than it was (the real waits judged, never shortened). */
    internal fun ageLostForTest(key: String, ms: Long) {
        lostWrites[key]?.let { it.sinceMs -= ms; it.askedMs = it.sinceMs; if (it.foundAtMs > 0) it.foundAtMs -= ms }
        foundLately.computeIfPresent(key) { _, v -> v.first to v.second - ms }
    }

    /** TEST ONLY: every lost reply forgotten, as after a restart. */
    internal fun forgetLostForTest() { cancelLostSearches(); lostWrites.clear(); foundLately.clear() }

    /** One search of the book for [w]: found, or a read that succeeded without it; a read that failed changes nothing. */
    private suspend fun searchLost(w: LostWrite) {
        val found = try { findRecent(w.order, w.known, liveOnly = w.liveOnly, sentAt = w.sentAt) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { w.readFailAtMs = System.currentTimeMillis(); return }
        // (20 Nov 2026, delta review: an order claimed by another trade meanwhile ([claimStop]) is not this one: looked for again.)
        if (found != null) synchronized(w) { if (!claimedIds.containsKey(found)) { w.foundId = found; w.foundAtMs = System.currentTimeMillis() } }
        else w.readOkAtMs = System.currentTimeMillis()
    }

    /**
     * 20 Nov 2026 (merge review, the found stop's tiebreaker): the order found for the lost reply under [key] so far, if any, WITHOUT taking it
     * (the caller's own [lookLost] still does) - a search started when one is due, as [lookLost] does. Memory, and the background search.
     */
    fun peekLost(key: String): String? {
        val w = lostWrites[key] ?: return null
        val now = System.currentTimeMillis()
        var inline = false
        synchronized(w) {
            if (w.foundId == null && w.searching?.isActive != true && now - w.lookedMs >= (lostLookGapMsForTest ?: LOST_LOOK_GAP_MS)) {
                w.lookedMs = now
                if (lostSearchInlineForTest) inline = true else w.searching = lostScope.launch { searchLost(w) }
            }
        }
        if (inline) kotlinx.coroutines.runBlocking { searchLost(w) }
        return w.foundId
    }

    /**
     * The lost reply under [key]: null when none is kept. Memory only - it never reads the book itself: a search runs in the background
     * when due ([LOST_LOOK_GAP_MS]) and the next call acts on it ([lostSearchInlineForTest]: on this thread).
     */
    fun lookLost(key: String): LostLook? {
        val w = lostWrites[key] ?: return null
        val now = System.currentTimeMillis()
        w.askedMs = now
        // (20 Nov 2026, delta review: one search at a time - two looks at the same moment start one; the test seam's runs outside the lock.)
        var inline = false
        synchronized(w) {
            if (w.foundId == null && w.searching?.isActive != true && now - w.lookedMs >= (lostLookGapMsForTest ?: LOST_LOOK_GAP_MS)) {
                w.lookedMs = now
                if (lostSearchInlineForTest) inline = true else w.searching = lostScope.launch { searchLost(w) }
            }
        }
        if (inline) kotlinx.coroutines.runBlocking { searchLost(w) }
        w.foundId?.let { found ->
            // (20 Nov 2026, delta review: only the look that takes it off acts on it - another look at the same moment still reads
            // Searching, never "none kept" - and its search is stopped.)
            if (!lostWrites.remove(key, w)) return LostLook.Searching
            w.searching?.cancel()
            // (23 Nov 2026, B6 follow-ups, round 2: still counted a moment for the square-offs once taken - [lostSales].)
            if (foundLately.size > 200) foundLately.entries.removeIf { now - it.value.second > FOUND_COUNTED_MS }
            foundLately[key] = w.order to (w.foundAtMs.takeIf { it > 0 } ?: now)
            runCatching { Diag.record("zerodha", "${w.what} (${w.order.tradingSymbol}): its lost reply found in the book: order $found") }
            return LostLook.Found(found, w.order.quantity, w.order.triggerPrice).also { it.symbol = w.order.tradingSymbol }
        }
        val window = lostSearchMsForTest ?: LOST_SEARCH_MS
        if (now - w.sinceMs < window) return LostLook.Searching
        // Past the window: given up on only on a read that succeeded after it; while the reads after it fail, it stays UNKNOWN (Boss told
        // once); a read still on its way says nothing yet.
        if (w.readOkAtMs - w.sinceMs < window) {
            if (!w.unknownSaid && w.readFailAtMs - w.sinceMs >= window) {
                w.unknownSaid = true
                runCatching {
                    val text = "${w.what} on ${w.order.tradingSymbol}: Zerodha's answer was lost and its order book could not be read for " +
                        "${(now - w.sinceMs) / 1000} s, so the order's state is UNKNOWN. Nothing is sent again meanwhile; check the order book in Kite."
                    Diag.record("zerodha", text)
                    com.optionslab.app.work.Alerts.error(text, "Zerodha")
                }
            }
            return LostLook.Searching
        }
        if (!lostWrites.remove(key, w)) return LostLook.Searching
        w.searching?.cancel()
        runCatching {
            val text = "${w.what} on ${w.order.tradingSymbol}: Zerodha's answer was lost and the order is not in its order book " +
                "${(now - w.sinceMs) / 1000} s later. Check the order book in Kite; the app goes on as if it was not placed."
            Diag.record("zerodha", text)
            com.optionslab.app.work.Alerts.error(text, "Zerodha")
        }
        return LostLook.GaveUp
    }

    /**
     * TEST ONLY (23 Nov 2026, B6 follow-ups, item 1): how many times a caller ran its own lost-reply search ([findRecentRetrying], not the
     * door's after Kite's unsure answer, [sendChecked]) in this process - a debug build's count, memory only.
     */
    internal val callerSearchesForTest = java.util.concurrent.atomic.AtomicInteger()

    /**
     * [findRecent] for a POST whose reply was lost, tried [tries] times [waitMs] apart (the order book can
     * trail the POST by a moment). The order id; null when the book was read and holds no such order;
     * throws when the book could not be read at all, so the caller knows the order's fate is unknown.
     */
    suspend fun findRecentRetrying(o: Kite.Order, known: Collection<String>, tries: Int = 3, waitMs: Long = 2_000, afterError: Boolean = false): String? {
        if (!afterError && com.optionslab.app.BuildConfig.DEBUG) callerSearchesForTest.incrementAndGet()
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

    /** 21 Oct, enhancement round 4 (review): a GTT's placing as [placeGttSure] tells it - placed (its id), refused, or not known. */
    sealed class GttSent { class Placed(val id: Long) : GttSent(); object Refused : GttSent(); object Unknown : GttSent() }

    /**
     * 21 Oct, enhancement round 4 (review): [placeGttOnce], telling a definite refusal (nothing at Zerodha: the caller may rest its
     * exits another way) from a lost reply not found in the GTT list (it may exist: the caller places nothing else this look).
     */
    suspend fun placeGttSure(g: Kite.Gtt, known: Set<Long>): GttSent {
        return try { GttSent.Placed(placeGtt(g)) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // (20 Nov 2026, audit B5 review: never left the phone - not a refusal: nothing rests, and it is simply tried again.)
            if (e is NotSent) return GttSent.Unknown
            if (definite(e)) return GttSent.Refused
            val q = g.orders.firstOrNull()?.quantity ?: return GttSent.Unknown
            runCatching {
                gtts().firstOrNull { r -> r.active && r.id !in known && r.symbol == g.tradingSymbol && r.exchange == g.exchange &&
                    r.legQtys.firstOrNull() == q && r.triggers.zip(g.triggers).all { (a, b) -> kotlin.math.abs(a - b) < 1e-6 } && r.triggers.size == g.triggers.size }?.id
            }.getOrNull()?.let { GttSent.Placed(it) } ?: GttSent.Unknown
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
        // 20 Nov 2026 (audit B5, item 6: "a failed order-count read counts as zero"): a failed read is read once more, afresh; still
        // unreadable, the count is UNKNOWN (null), judged by the order count check ([OrderCountCheck]: in shadow by default the entry
        // goes as before, logged; on, it is refused). An exit never looks at it.
        val countQ = async {
            runCatching { (if (needed != null) ordersRecon() else orders()).size }.getOrNull()
                ?: runCatching { kotlinx.coroutines.withContext(FastRead()) { orders(fresh = true) }.size }.getOrNull()   // (3 s sockets: review)
        }
        val out = bookQ.await()?.let { runCatching { Guard.liveAccount(it, fundsQ.await(), countQ.await()) }.getOrNull() }
        if (needed != null) runCatching { OrderTiming.marginCheck(System.currentTimeMillis() - t0) }
        out
    }

    /**
     * 20 Nov 2026 (audit B5, item 8): one order as a row of the order book ([OrderRow]), from its own history's last entry (GET
     * /orders/{id}): a stop missing from a good read of the book is read again by its id, never assumed resting. Null when Kite has
     * no history for it yet; throws when it cannot be read. [budgetMs]: a whole limit shorter than a read's own.
     */
    suspend fun orderRow(orderId: String, budgetMs: Long? = null): OrderRow? {
        val hist = call("GET", "/orders/${Kite.enc(orderId)}", budgetMs = budgetMs) as JSONArray
        if (hist.length() == 0) return null
        return orderRow(hist.getJSONObject(hist.length() - 1)).takeIf { it.id.isNotBlank() }
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

    /**
     * 20 Nov 2026 (audit B5, item 9: "the kill switch does not cancel working entry orders"): whether the kill switch cancels [r]: a
     * working order of the bots' and arms' ([BOT_TAGS]) that is an ENTRY - one this phone sent as an entry ([entryIds]), or, when
     * this phone does not know it (sent before a restart), a plain LIMIT or MARKET BUY that would open or add to a long by a good
     * positions read ([net]). Never one it sent as an exit ([exitIds]), never a stop (SL, SL-M) it does not know as an entry, never a
     * sale it does not know, never anything not a bot's (Boss's own orders, in Kite or sent from the app).
     * Review (20 Nov 2026): a BUY is cancelled only on a good positions read showing no short position of the same underlying on the
     * same exchange - a working BUY there may be a hedge (a strategy's protective leg, a cover) and cancelling it could leave a short
     * uncovered; with no positions read no BUY is cancelled at all (a known SELL entry still is: it only opens a short).
     */
    internal fun killCancels(r: OrderRow, net: List<Position>?): Boolean {
        // (Coordinator, 20 Nov 2026: only the bots' and arms' orders - ORB and Liquidity, Pine, the strategies, the arms' live copies -
        // told apart by their tag as the door does ([BOT_TAGS]); Boss's own entries sent from the app are never cancelled.)
        if (!r.working || r.tag !in BOT_TAGS) return false
        if (exitIds.containsKey(r.id)) return false
        val known = entryIds.containsKey(r.id)
        if (r.side != "BUY") return known
        if (net == null) return false
        fun root(s: String) = Kite.underlyingOf(s) ?: s.takeWhile { !it.isDigit() }
        if (net.any { it.qty < 0 && it.exchange == r.exchange && root(it.symbol) == root(r.symbol) }) return false
        if (known) return true
        if (r.type !in setOf("LIMIT", "MARKET")) return false
        val held = net.filter { it.symbol == r.symbol && it.exchange == r.exchange && it.product == r.product }.sumOf { it.qty }
        return held >= 0
    }

    private val killScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + com.optionslab.app.data.ScopeGuard.handler("Broker.killScope"))

    /**
     * 20 Nov 2026 (audit B5, item 9): the kill switch just went on ([why]). [act] (only Boss's own act in the app: his kill switch, or
     * its "cancel the bots' working entries" while it is on): the bots' working entry orders at Zerodha are cancelled
     * ([cancelWorkingEntries]) in the background - once the kill switch reads on (review: never before Boss's switch is saved, so no
     * bot re-enters meanwhile; not on within [KILL_SAVED_WAIT_MS], nothing is cancelled) - at once, again a few seconds later, and once
     * more after a whole write's budget ([WRITE_BUDGET_MS]: an entry whose POST was already on its way may reach Zerodha that late).
     * Otherwise (the guard tripping by itself, Jarvis, voice, Autopilot - coordinator, 20 Nov 2026, CLAUDE.md's shadow rule): ONE sweep
     * in the ordinary reads' lane only LOGS which entries it would cancel; nothing is cancelled. A logging request never replaces Boss's
     * cancelling one. Returns at once; nothing here waits, reads the disk or blocks the caller (the guard's order path can turn it on).
     */
    fun killEntriesSoon(why: String, act: Boolean) {
        if (com.optionslab.app.BuildConfig.DEBUG) { if (killRequestsForTest.size > 50) killRequestsForTest.clear(); killRequestsForTest += why to act }
        if (!killSweepOn) return
        synchronized(killLock) {
            val running = killJob?.takeIf { it.isActive }
            if (running != null && killJobActs && !act) return
            running?.cancel()
            killJobActs = act
            killJob = killScope.launch {
                if (!act) { runCatching { cancelWorkingEntries(why, act = false) }; return@launch }
                // (Boss's switch saved first: the kill switch reads on, from the vault - this background thread only.)
                val saved = kotlinx.coroutines.withTimeoutOrNull(KILL_SAVED_WAIT_MS) {
                    while (!runCatching { killOn() }.getOrDefault(false)) delay(100)
                    true
                } ?: false
                if (!saved) {
                    runCatching { Diag.record("zerodha", "Kill switch ($why): it did not read on, so no entry order was cancelled") }
                    return@launch
                }
                // (Deep review: each sweep only while the kill switch still reads on - cleared meanwhile, a bot's new entry is never
                // cancelled; [killCleared] also ends the job at once.)
                suspend fun sweep() { if (runCatching { killOn() }.getOrDefault(false)) runCatching { cancelWorkingEntries(why) } }
                sweep()
                delay(killSweepAgainMs)
                sweep()
                delay(WRITE_BUDGET_MS)
                sweep()
            }
        }
    }

    /** 20 Nov 2026 (audit B5 deep review): the kill switch cleared - any sweep still due is ended (nothing more is cancelled). */
    fun killCleared() {
        synchronized(killLock) { killJob?.cancel(); killJob = null; killJobActs = false }
    }

    /** TEST ONLY: whether a kill-switch sweep job is still running. */
    internal fun killSweepRunningForTest(): Boolean = killJob?.isActive == true
    /** How long Boss's cancelling request waits for his kill switch to read on (saved) before it gives up (nothing cancelled). */
    private const val KILL_SAVED_WAIT_MS = 10_000L
    private val killLock = Any()
    @Volatile private var killJob: kotlinx.coroutines.Job? = null
    @Volatile private var killJobActs = false
    /** TEST ONLY (debug builds): every kill-switch request ([killEntriesSoon]): why, and whether it cancels. Memory only. */
    internal val killRequestsForTest: MutableList<Pair<String, Boolean>> = java.util.Collections.synchronizedList(ArrayList())
    /** How long after the first sweep the second runs (tests shorten it). */
    @Volatile internal var killSweepAgainMs = 3_000L
    /**
     * TEST SEAM: the kill switch's sweep on (always, in the app). The JVM tests start each test with it off ([resetForTest]: a sweep
     * left running from one test would reach the next test's fake Zerodha); a test of it turns it on. Set only in debug builds.
     */
    @Volatile internal var killSweepOn = true
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /**
     * 20 Nov 2026 (audit B5, item 9): the bots' working ENTRY orders ([killCancels]); never an exit, a stop, a possible hedge or Boss's
     * own. [act]: cancelled; else only logged as what would be cancelled (shadow). Reads the order book afresh (tried three times); an
     * unreadable book cancels nothing. With no positions read, no BUY is cancelled ([killCancels]). The ids cancelled (or that would be).
     */
    suspend fun cancelWorkingEntries(why: String, act: Boolean = true): List<String> = kotlinx.coroutines.withContext(
        Lane(if (act) com.optionslab.ira.RateGate.Priority.SAFETY else com.optionslab.ira.RateGate.Priority.ENTRY)) {
        if (!loggedIn) return@withContext emptyList<String>()
        var rows: List<OrderRow>? = null
        for (i in 0 until 3) {
            if (i > 0) delay(1_000)
            rows = runCatching { orders(fresh = true) }.getOrNull()
            if (rows != null) break
        }
        val book = rows ?: run { runCatching { Diag.record("zerodha", "Kill switch ($why): the order book could not be read; no entry order cancelled") }; return@withContext emptyList<String>() }
        val net = if (book.none { it.working && it.tag in BOT_TAGS }) null
            else runCatching { positionBook().net }.getOrNull() ?: run { delay(1_000); runCatching { positionBook().net }.getOrNull() }
        val hit = book.filter { killCancels(it, net) }
        if (!act) {
            if (hit.isNotEmpty()) runCatching { Diag.record("zerodha", "Kill switch ($why, shadow): would have cancelled ${hit.size} working entry order(s) " +
                "of the bots: ${hit.joinToString { "${it.id} ${it.symbol}" }} (not cancelled: only Boss's own switch cancels them)") }
            return@withContext hit.map { it.id }
        }
        val out = ArrayList<String>()
        for (r in hit) {
            runCatching { cancel(r.id, r.variety) }.onSuccess { out += r.id }
                .onFailure { e -> runCatching { Diag.record("zerodha", "Kill switch ($why): cancel of entry ${r.id} (${r.symbol}) failed: ${e.message}") } }
        }
        if (out.isNotEmpty()) runCatching {
            Diag.record("zerodha", "Kill switch ($why): cancelled ${out.size} working entry order(s): ${out.joinToString()}")
            com.optionslab.app.work.Alerts.error("Kill switch: ${out.size} working entry order(s) of the bots were cancelled at Zerodha. " +
                "Exits, stops and your own orders were left as they are.", "Kill switch")
        }
        out
    }

    /** PUT /orders/{variety}/{id}. Callers re-check the order is still working and the owner confirmed. */
    suspend fun modify(order: OrderRow, quantity: Int, orderType: String, price: Double?, trigger: Double?) {
        call("PUT", "/orders/${Kite.enc(order.variety)}/${Kite.enc(order.id)}", Kite.modifyBody(quantity, orderType, price, trigger))
    }
}
