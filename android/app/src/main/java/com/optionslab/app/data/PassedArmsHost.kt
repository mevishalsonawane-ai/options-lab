package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.BreadthDonchianArm
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.engine.orb.SharedSales
import com.optionslab.engine.orb.Solo2
import com.optionslab.engine.orb.SoloMidday
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * The paper arms that PASSED research (15 Oct 2026, Boss: "Both"; the rules are the engine's, [PassedArms]):
 *  - "R51 Donchian 20" ([DonchianArm]) on NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX;
 *  - "R52 Open outside value" ([OovArm]) on the same five, with R57's Solo layer as its stop (labelled);
 *  - "Solo 2.0" ([Solo2], research R57's A2 meta-labeller): Jarvis's own second paper Solo, next to Solo (midday), which it
 *    never changes;
 *  - "R72 Breadth Donchian" ([BreadthDonchianArm], 16 Oct, Boss: "Okay add it"): Donchian's break taken only when at least 4 of
 *    the five indices break together, BESIDE R51 Donchian 20 (both run, compared on the same days: [comparison]); it reads the
 *    same five 5-minute series Donchian builds (no extra read), its trades in its own rows with Donchian's exits.
 * Every row, notice and answer says [PassedArms.PASSED].
 *
 * 19 Oct (Boss: "Yes, keep only breadth donchain"): each arm is ON, RECORD ONLY or OFF ([Mode]). Record only: its signals are
 * decided exactly as when on, but nothing is bought - each becomes a would-be trade ([SHADOW_OFF]) closed by its own exits, never
 * mirrored live, never on the paper account (so never in the same-direction cap), no notice. The 19 Oct update set R51 Donchian 20
 * to record only once ([R51_RECORD]), so only R72 Breadth Donchian buys the Donchian breaks and the two can still be compared.
 *
 * 18 Oct (Boss: "What ever strategies are present for paper, should be present for live too"): this file itself still places
 * only paper orders; its live path is [com.optionslab.app.data.ArmLive], told of each paper buy, stop or lock and close (memory
 * only). That live trade goes only in Live with "AI trades go live" on, its own "Trade live" switch on, outside the paper-only
 * period and past every guard - so the words below about Zerodha describe this book, not that live path.
 *
 * PAPER ONLY, always: every order is the paper account's ([Paper.place]); there is no live flag and no Zerodha call anywhere
 * here, so nothing can reach Zerodha whatever Live, "AI trades go live" or a voice command says - and Solo 2.0 is never
 * offered as a trade idea. 1 lot each. Their book is their own encrypted file (not in the backup): their trades are a record
 * of their own, apart from the plain-rules record for the 26 Oct decision. New entries obey the kill switch, the bot's stop
 * for today (the daily loss limit included), the account day lock and the account guard; a stale price refuses a MARKET entry
 * (the paper account's own check). Exits always go, and the bot's stop for today sells what is held (it only lowers risk).
 *
 * Under CLAUDE.md's PAPER rule a passed idea acts on paper at once: each arm is switched ON once by this update (logged in
 * Autopilot's change timeline as PAPER_ARM_ON, Boss's, undone in one tap); after that only Boss switches it - except its
 * forward-test bar, set in advance, which switches it OFF by itself (risk-lowering, logged the same way):
 *  - R51 / R52 / R72: at 40 paper trades, a net below 0 or below the cost of twice the spread ([PassedArms.ForwardBar]);
 *  - Solo 2.0: SoloMidday's bar - after 60 trades a net per trade <= 0, or a drawdown beyond -Rs 25,000 at any time.
 *
 * Off the order and tick paths: the entries run in the watch's arms passes (a worker), their reads and orders on a copy of the
 * book OUTSIDE its lock (merged back after), so the exits never wait behind them; the previous sessions' bars and daily candles are read once a day in the background ([prefetch]); Solo 2.0's
 * model is read once, at start-up, from the app's assets on a worker ([refresh]) - never on the main thread, never in the
 * fast lanes. While a position is held its exits run in the fast path's minute lane at full cadence ([manageOnly]), and after
 * a restart the book is read first ([PositionsFirst]).
 */
object PassedArmsHost {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "passed_arms.vault")
        // 18 Oct (Boss: every paper strategy present for live too): these arms' paper trades, for their live path ([ArmLive]).
        ArmLive.register(com.optionslab.engine.orb.LiveMirror.PASSED) { mirrorLegs() }
    }

    /** A paper trade's reference for its live path ([ArmLive]): its arm and its paper order (or its entry time). */
    internal fun mirrorRef(p: Pos): String = "passed:${p.arm}:${p.orderId ?: p.entryTime}"

    /** Today's and the open paper trades, each with what it still holds (0 once closed); null until the book is read. Memory only. */
    private fun mirrorLegs(): List<ArmLive.PaperLeg>? {
        if (cache == null) return null
        val v = _view.value
        return (v.open + v.today.filter { !it.open }).filter { it.shadow == null }.map { ArmLive.PaperLeg(mirrorRef(it), it.arm, if (it.open) it.qty else 0) }
    }

    /** [p]'s own stop on the premium at its entry (-15% and the like); null for S1 (its stop is on the index). */
    private fun premiumStop(p: Pos): Double? = if (p.setup == Solo2.Setup.S1.key) null else exitsOf(p).stop?.let { p.entry * (1 - it) }

    /** 16 Oct: R72 Breadth Donchian last (each arm's notice numbers go by its place here, so the first three keep theirs). */
    val SOURCES = listOf(DonchianArm.SOURCE, OovArm.SOURCE, Solo2.SOURCE, BreadthDonchianArm.SOURCE)
    /** The arms the 15 Oct update switched on (R72's has its own switch-on, [BREADTH_ON]). */
    private val FIRST_THREE = listOf(DonchianArm.SOURCE, OovArm.SOURCE, Solo2.SOURCE)
    fun label(source: String): String = when (source) { DonchianArm.SOURCE -> DonchianArm.LABEL; OovArm.SOURCE -> OovArm.LABEL
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.LABEL; else -> Solo2.LABEL }
    fun research(source: String): String = when (source) { DonchianArm.SOURCE -> DonchianArm.RESEARCH_ID; OovArm.SOURCE -> OovArm.RESEARCH_ID
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.RESEARCH_ID; else -> Solo2.RESEARCH_ID }
    fun underlyings(source: String): List<String> = when (source) { DonchianArm.SOURCE -> DonchianArm.UNDERLYINGS; OovArm.SOURCE -> OovArm.UNDERLYINGS
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.UNDERLYINGS; else -> Solo2.UNDERLYINGS }
    fun apart(source: String): String = when (source) { DonchianArm.SOURCE -> DonchianArm.REPORT_APART; OovArm.SOURCE -> OovArm.REPORT_APART
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.REPORT_APART; else -> Solo2.REPORT_APART }
    private val ALL_INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    /** The one-time switch-on of 15 Oct (the PAPER rule, Boss's "Both"); in [Book.migrated] once done, never again. */
    internal const val AUTO_ON = "passed_on_2026-10-15"
    /** 16 Oct (Boss's choice: "All three"): R64's 30-60-100 lock switched on once for new paper trades ([setLock]). */
    internal const val LOCK_ON = "lock_306100_2026-10-16"
    /** 16 Oct (Boss's choice after R65 - R69: "Select the best"): R66's +5% once +25% switched on once for new locked trades ([setL25]). */
    internal const val L25_ON = "lock_l25_2026-10-16"
    /** 16 Oct (Boss: "Okay add it"): R72 Breadth Donchian switched on once ([BreadthDonchianArm]); the day kept in [Book.breadthSince]. */
    internal const val BREADTH_ON = "breadth_on_2026-10-16"
    /** Its change-log words: Boss's choice, and the note with its risk line. */
    private val BREADTH_CHOICE = com.optionslab.ira.AutoChange.passedArmChoice(BreadthDonchianArm.SOURCE)
    private val BREADTH_NOTE = "the 16 Oct update (your \"Okay add it\"), beside ${DonchianArm.LABEL} to compare on the same days. " +
        "${BreadthDonchianArm.RISK} - its paper book never live; its live copy goes by its own Trade live and Shadow switches"
    /** 16 Oct (Boss: "Update normal Donchian ... T7 supertrend agree"): R73 T7 switched on once for R51 Donchian 20's new entries ([setSupertrend]). */
    internal const val ST_ON = "donchian_st_2026-10-16"
    /**
     * 19 Oct (Boss's choice: "Yes, keep only breadth donchain"): R51 Donchian 20 set to record only once, when it was on
     * ([Book.recordOnly]); R72 Breadth Donchian stays on. Logged as Boss's ask; one tap undoes it (back on).
     */
    internal const val R51_RECORD = "r51_record_only_2026-10-19"

    /** An arm's switch: on (paper trades), record only (would-be trades, nothing bought), or off (19 Oct). */
    enum class Mode { ON, RECORD, OFF }

    /** A filtered break Supertrend could not judge (skipped): its status words, counted on the row and in the diagnostics. */
    internal const val ST_UNJUDGED = "Supertrend cannot be judged yet (no earlier session in), so not taken"
    /**
     * 16 Oct (R73 review): where today's count of those is kept ([Book.status], "<day>: <n> ..."), so the row, Jarvis and the
     * diagnostics show how many breaks the filter skipped unjudged (a day with no history read would otherwise look quiet).
     */
    internal const val ST_UNJUDGED_KEY = "${DonchianArm.SOURCE}|Supertrend not judged"

    /** Today's ([day]) count kept under [ST_UNJUDGED_KEY] in [status]; 0 for another day or none. */
    internal fun stUnjudgedOn(status: Map<String, String>, day: LocalDate): Int =
        status[ST_UNJUDGED_KEY]?.takeIf { it.startsWith("$day: ") }?.substringAfter(": ")?.substringBefore(' ')?.toIntOrNull() ?: 0

    /** TEST SEAM: true in the JVM tests' app (the other tests' books start with these arms off). Its setter throws in a release build. */
    @Volatile internal var testNoAutoOn: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST SEAM: an index's 1-minute bars of the earlier sessions (null in the app: Upstox's history). Debug builds only. */
    @Volatile internal var testHistory: ((String) -> List<Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }

    /** TEST SEAM: an index's daily candles (null in the app: Upstox's). Debug builds only. */
    @Volatile internal var testDaily: ((String) -> List<Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }

    /**
     * One paper trade. [setup] Solo 2.0's setup key (S1..S4; null for R51 / R52); [entryRef] / [exitRef] the prices seen when
     * each order went in (the spread paid, for the forward bar); [idx] / [atr] S1's 12:00 index price and ATR14 (its exits);
     * [lock] it entered with R64's 30-60-100 lock ([PassedArms.withLock]): a trade keeps the exits it entered with.
     */
    data class Pos(
        val arm: String, val index: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val orderId: String?, val setup: String? = null, val entryRef: Double? = null, val idx: Double? = null, val atr: Double? = null,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0, val exitRef: Double? = null,
        val paperFill: Double? = null,
        /** 16 Oct: taken back by the safety net ([adopt]) after it was found held with no record here ([ADOPT_FILL]: from its real fill). */
        val adopted: String? = null,
        /** 16 Oct (R64 A4, Boss's choice): entered under the 30-60-100 lock (no fixed target). */
        val lock: Boolean = false,
        /**
         * 16 Oct (R66 L25, Boss's choice): entered with the lock's +5% once +25% step too ([PassedArms.LOCK_L25]); only with [lock].
         * The ladder a trade entered with is kept (persisted); a trade from an older book reads as the plain A4 ladder.
         */
        val l25: Boolean = false,
        /**
         * 16 Oct (R72): an R51 Donchian 20 trade's break was also a breadth break (at least 4 of the five indices broke that way on
         * that bar): true / false; null for the other arms and older trades. Labels only (Boss's comparison); never changes a trade.
         */
        val breadth: Boolean? = null,
        /**
         * 16 Oct (R72 review): the SELL fills this trade's exit took, "orderId:qty,..." ([SharedSales.write]): its own exit's, or the
         * outside sales booked to it ("closed_elsewhere"). So a sale is booked once, and another arm's own exit is never taken for
         * a sale elsewhere, when R51 and R72 hold the same option. Null: none (an older trade, or nothing explained it).
         */
        val soldBy: String? = null,
        /**
         * 16 Oct (R73 T7, Boss): an R51 Donchian 20 trade taken under the Supertrend filter (its break agreed with Supertrend
         * (10, 3)); false for the other arms and for Donchian's plain-rules trades, so the two records stay apart.
         */
        val st: Boolean = false,
        /**
         * 19 Oct: a WOULD-BE trade, never on the paper account - [SHADOW_TWIN]: R51 Donchian 20 or R72 Breadth Donchian already
         * held this very option (Boss: no duplicate Donchian contracts); [SHADOW_CAP]: the same-direction cap refused it;
         * [SHADOW_OFF]: the arm is set to record only; [SHADOW_HELD] (22 Oct): another of these arms held the option. Bought
         * and sold at the prices seen, with estimated charges, by the arm's own exits, so its record (and the R72-vs-R51
         * comparison) still counts it; labelled apart everywhere; never mirrored live. Null: a real paper trade.
         */
        val shadow: String? = null,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val side: Int get() = if (right == "CE") 1 else -1
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
        /** The spread and slippage this round trip paid (the forward bar's "twice the spread"). */
        val spread: Double get() = PassedArms.spreadPaid(qty, entry, entryRef, exit ?: entry, exitRef,
            com.optionslab.engine.sandbox.PaperSpread.DEFAULTS[index] ?: com.optionslab.engine.sandbox.PaperSpread.UNKNOWN)
        /** The minute the research's walk starts from: its own entry minute (S1: 12:00, SoloMidday's). */
        val entryMinute: LocalDateTime get() = if (setup == Solo2.Setup.S1.key) SoloMidday.entryTime(day) else entryTime.withSecond(0).withNano(0)
    }

    private class Book(
        val armed: MutableMap<String, Boolean> = HashMap(),
        val positions: MutableList<Pos> = ArrayList(),
        /** Decisions made, so each is made once: "day|arm|..." */
        val decided: MutableSet<String> = HashSet(),
        /** 21 Oct: with the day each line was written ([StatusMap]). */
        val status: StatusMap = StatusMap(),
        val log: ArrayList<String> = ArrayList(),
        val migrated: MutableSet<String> = HashSet(),
        /** R51 / R52: closed trades when Boss switched it back on after its bar switched it off (the bar is not judged again). */
        val base: MutableMap<String, Int> = HashMap(),
        /** Solo 2.0's baseline ("from|peak", [SoloMidday.Baseline]). */
        var soloBase: String? = null,
        /** The forward verdict each arm switched itself off for (acted on once). */
        val acted: MutableMap<String, String> = HashMap(),
        /** Why each arm switched itself off (cleared when Boss switches it on). */
        val paused: MutableMap<String, String> = HashMap(),
        /** Solo 2.0's scored candidates, "day|key|score|what". */
        val scores: ArrayList<String> = ArrayList(),
        val marks: MutableMap<String, Double> = HashMap(),
        /** Each open trade's lock as an earlier look saw it earned ("time|level"): it holds even when the feed loses those bars. */
        val locks: MutableMap<String, String> = HashMap(),
        /** R64's 30-60-100 lock for NEW trades ([setLock]); open trades keep what they entered with ([Pos.lock]). */
        var lockOn: Boolean = false,
        /** R66's +5% once +25% step for NEW trades under the lock ([setL25]); open trades keep what they entered with ([Pos.l25]). */
        var l25On: Boolean = false,
        /** R73 T7: R51 Donchian 20's new breaks are taken only when Supertrend agrees ([setSupertrend]). */
        var stOn: Boolean = false,
        /** 16 Oct: the day R72 Breadth Donchian was first switched on (Boss's comparison counts from it); null: never on. */
        var breadthSince: LocalDate? = null,
        /** 19 Oct: the arms set to record only ([Mode.RECORD]): never also in [armed] as on. */
        val recordOnly: MutableSet<String> = HashSet(),
    ) {
        /** A copy for the entries phase (worked on outside the book's lock, then merged back). */
        fun copy(): Book = Book(HashMap(armed), ArrayList(positions), HashSet(decided), status.copyMap(), ArrayList(log), HashSet(migrated), HashMap(base),
            soloBase, HashMap(acted), HashMap(paused), ArrayList(scores), HashMap(marks), HashMap(locks), lockOn, l25On, stOn, breadthSince, HashSet(recordOnly))

        /** [source]'s entries run this pass: on, or record only. */
        fun active(source: String): Boolean = armed[source] == true || source in recordOnly
    }

    /** What the rows and Jarvis read (memory only). */
    data class View(
        val armed: Map<String, Boolean> = emptyMap(), val status: Map<String, String> = emptyMap(), val open: List<Pos> = emptyList(),
        val closed: List<Pos> = emptyList(), val today: List<Pos> = emptyList(), val log: List<String> = emptyList(),
        /** Every closed trade, oldest first (the records and the forward bars). */
        val all: List<Pos> = emptyList(), val marks: Map<String, Double> = emptyMap(), val paused: Map<String, String> = emptyMap(),
        val scores: List<String> = emptyList(), val modelLoaded: Boolean = false,
        val base: Map<String, Int> = emptyMap(), val soloBase: SoloMidday.Baseline = SoloMidday.Baseline(),
        /** R64's 30-60-100 lock is on for new trades. */
        val lockOn: Boolean = false,
        /** R66's +5% once +25% step is on for new trades under the lock. */
        val l25On: Boolean = false,
        /** R73 T7's Supertrend filter is on for R51 Donchian 20's new breaks. */
        val stOn: Boolean = false,
        /** The day R72 Breadth Donchian was first switched on (null: never). */
        val breadthSince: LocalDate? = null,
        /** 16 Oct (R73 review): today's R51 breaks the Supertrend filter skipped because it could not judge them. */
        val stUnjudged: Int = 0,
        /** 19 Oct: the arms set to record only (would-be trades, nothing bought). */
        val recordOnly: Set<String> = emptySet(),
    ) {
        fun on(source: String): Boolean = armed[source] == true
        /** 19 Oct: [source] is record only (its breaks kept as would-be trades, nothing bought). */
        fun recording(source: String): Boolean = !on(source) && source in recordOnly
        fun mode(source: String): Mode = if (on(source)) Mode.ON else if (recording(source)) Mode.RECORD else Mode.OFF
        /** 19 Oct: [source]'s closed would-be trades (record only, the twin rule, the cap: never bought), within [closedOf]. */
        fun wouldBeOf(source: String): List<Pos> = closedOf(source).filter { it.shadow != null }
        fun closedOf(source: String): List<Pos> = all.filter { it.arm == source }
        fun todayOf(source: String): List<Pos> = today.filter { it.arm == source }
        /** Today's running P&L of [source]: closed after charges, open at the last price before charges. */
        fun todayPnl(source: String): Double = todayOf(source).sumOf { p -> p.net ?: marks[p.symbol]?.let { (it - p.entry) * p.qty } ?: 0.0 }
        /** The forward test's line. */
        fun forwardLine(source: String): String {
            val c = closedOf(source)
            return if (source == Solo2.SOURCE) SoloMidday.forwardLine(SoloMidday.record(c.map { it.net ?: 0.0 })).replace("Forward test", "Forward test (60 trades)").removeSuffix(".")
            else PassedArms.ForwardBar.line(PassedArms.ForwardBar.judge(c.map { it.net ?: 0.0 }, c.map { it.spread }, base[source] ?: 0))
        }

        /**
         * 16 Oct (R72, the point of running both): R72 Breadth Donchian's closed paper trades next to R51 Donchian 20's on the same
         * days, since the breadth arm was first switched on, and Donchian's trades breadth did not confirm (those labelled). Words.
         */
        fun comparison(): String {
            val since = breadthSince
            fun from(s: String) = closedOf(s).filter { since == null || !it.day.isBefore(since) }
            val don = from(DonchianArm.SOURCE)
            val labelled = don.filter { it.breadth != null }
            // 16 Oct (R73 T7): Donchian's trades taken under its Supertrend filter apart from its plain-rules ones (labelled).
            val split = if (stOn || don.any { it.st }) don.filter { it.st }.map { it.net ?: 0.0 } to don.filter { !it.st }.map { it.net ?: 0.0 } else null
            return BreadthDonchianArm.comparison(since, from(BreadthDonchianArm.SOURCE).map { it.net ?: 0.0 }, don.map { it.net ?: 0.0 },
                labelled.takeIf { it.isNotEmpty() }?.filter { it.breadth == false }?.map { it.net ?: 0.0 }, split)
        }
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    @Volatile private var cache: Book? = null
    private const val LOG_KEPT = 200
    /** Closed trades kept per arm. */
    private const val CLOSED_KEPT = 1500
    /** Its own notices: 8100.. (buys 8100-8119, switch-offs 8120-8123, sells 8140-8159, taken back 8160-8179); no other part of the app uses 81xx. */
    private const val NOTICE = 8100

    private fun ready() = ::file.isInitialized

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        var unreadable = false
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            o.optJSONObject("armed")?.let { m -> m.keys().forEach { b.armed[it] = m.getBoolean(it) } }
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += readPos(a.getJSONObject(i)) }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            b.status.readFrom(o, runCatching { file.lastModified() }.getOrDefault(0L))
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) }
            o.optJSONObject("base")?.let { m -> m.keys().forEach { b.base[it] = m.getInt(it) } }
            b.soloBase = o.optString("soloBase").ifEmpty { null }
            o.optJSONObject("acted")?.let { m -> m.keys().forEach { b.acted[it] = m.getString(it) } }
            o.optJSONObject("paused")?.let { m -> m.keys().forEach { b.paused[it] = m.getString(it) } }
            o.optJSONArray("scores")?.let { a -> for (i in 0 until a.length()) b.scores += a.getString(i) }
            o.optJSONObject("locks")?.let { m -> m.keys().forEach { b.locks[it] = m.getString(it) } }
            b.lockOn = o.optBoolean("lock306100", false)
            b.l25On = o.optBoolean("lockL25", false)
            b.breadthSince = o.optString("breadthSince").ifEmpty { null }?.let { LocalDate.parse(it) }
            b.stOn = o.optBoolean("donchianST", false)
            // 19 Oct: absent in an older book (no arm record only); never beside "on" (on wins).
            o.optJSONArray("recordOnly")?.let { a -> for (i in 0 until a.length()) a.getString(i).takeIf { it in SOURCES && b.armed[it] != true }?.let { b.recordOnly += it } }
        }.onFailure { unreadable = true; if (file.exists()) Vault.setAside(file) }
        cache = b
        // 15 Oct (the PAPER rule): on once, on paper. A book that could not be read is never switched on by itself (Boss may
        // have switched an arm off): it is only marked done. Never in IraGoldAlgo.
        if (AUTO_ON !in b.migrated) {
            b.migrated += AUTO_ON
            val on = !unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD
            if (on) for (s in FIRST_THREE) if (b.armed[s] != true) {
                b.armed[s] = true
                note(b, "${label(s)} switched on once by the 15 Oct update: ${PassedArms.PASSED} (${research(s)})")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedArmOn(s, label(s), research(s)) }
            }
            save(b)
        } else publish(b)
        // 16 Oct (Boss's choice: "All three"): R64's A4, the 30-60-100 lock, on for new paper trades, once - Boss's own choice (it passed
        // every R64 gate but was not better than the research exits in every period, so R64 left it a note); logged so the
        // timeline shows it and one tap undoes it. Same care as above: never on an unreadable book, never in Gold.
        if (LOCK_ON !in b.migrated) {
            b.migrated += LOCK_ON
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD && !b.lockOn) {
                b.lockOn = true
                note(b, "The 30-60-100 lock turned on by the 16 Oct update for new paper trades, your choice: ${LOCK_WORDS} (R64 A4)")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedLockOn() }
            }
            save(b)
        }
        // 16 Oct (Boss's choice after R65 - R69: "Select the best"): R66's L25, +5% locked once +25%, on once for NEW paper trades
        // that enter under the lock - a small improvement, not proven (it failed the random-exit check), so it is Boss's choice;
        // logged so the timeline shows it and one tap undoes it. Same care: never on an unreadable book, never in Gold.
        if (L25_ON !in b.migrated) {
            b.migrated += L25_ON
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD && !b.l25On) {
                b.l25On = true
                note(b, "L25 turned on by the 16 Oct update for new paper trades under the 30-60-100 lock, your choice: $L25_WORDS (R66)")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedL25On() }
            }
            save(b)
        }
        // 16 Oct (Boss: "Okay add it", after R72): R72 Breadth Donchian passed every gate, so under the PAPER rule it trades on paper
        // at once, BESIDE R51 Donchian 20 (both run, to compare on the same days) - on once, logged in the change timeline (one tap
        // undoes it), shown to Boss with its risk line. Same care: never on an unreadable book, never in Gold; after this only Boss
        // switches it (and its forward bar, which only switches it off).
        if (BREADTH_ON !in b.migrated) {
            b.migrated += BREADTH_ON
            val s = BreadthDonchianArm.SOURCE
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD && b.armed[s] != true) {
                b.armed[s] = true
                if (b.breadthSince == null) b.breadthSince = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                note(b, "${label(s)} switched on once by the 16 Oct update, your \"Okay add it\": ${PassedArms.PASSED} (${research(s)}), beside " +
                    "${DonchianArm.LABEL} to compare on the same days. ${BreadthDonchianArm.RISK}")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedArmOn(s, label(s), research(s), BREADTH_CHOICE, BREADTH_NOTE) }
            }
            save(b)
        }
        // 16 Oct (Boss: "Update normal Donchian as you recommended, T7 supertrend agree"): R73 T7 passed every gate and beat
        // Donchian in design, walk-forward and final, so under the PAPER rule it acts on R51 Donchian 20's paper entries at once:
        // on once, logged in the change timeline (one tap undoes it). It only skips breaks, never adds or resizes one. Its trades
        // are labelled ([Pos.st]) so the plain-rules record stays apart. Same care: never on an unreadable book, never in Gold.
        if (ST_ON !in b.migrated) {
            b.migrated += ST_ON
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD && !b.stOn) {
                b.stOn = true
                note(b, "${DonchianArm.LABEL}: Supertrend filter turned on by the 16 Oct update, your ask: ${DonchianArm.ST_RULE}")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedSupertrendOn() }
            }
            save(b)
        }
        // 19 Oct (Boss's choice: "Yes, keep only breadth donchain"): only R72 Breadth Donchian buys the Donchian breaks on paper; R51
        // Donchian 20, when on, is set to record only once - its breaks still decided, each kept as a would-be trade (nothing bought),
        // so the two can still be compared. Logged as Boss's ask in the change timeline (one tap puts it back on). R72, Liquidity, the
        // daily loss limit, the day lock and every guard are not touched. Same care: never an unreadable book, never in Gold.
        if (R51_RECORD !in b.migrated) {
            b.migrated += R51_RECORD
            val s = DonchianArm.SOURCE
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD && b.armed[s] == true) {
                b.armed[s] = false
                b.recordOnly += s
                // (Review: R72's state said as it is - Boss may have switched it off, or its forward bar may have.)
                val r72 = if (b.armed[BreadthDonchianArm.SOURCE] == true) "${BreadthDonchianArm.LABEL} stays on"
                    else "${BreadthDonchianArm.LABEL} is off: switching it on is your choice"
                note(b, "${label(s)} set to record only by the 19 Oct update, your \"Yes, keep only breadth donchain\": its breaks are kept as " +
                    "would-be trades, nothing is bought; $r72" +
                    if (b.positions.any { it.arm == s && it.open && it.shadow == null }) "; what it holds still closes by its own exits" else "")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedArmRecordOnly(s, label(s), r72) }
            }
            save(b)
        }
        return b
    }

    private fun readPos(p: JSONObject): Pos {
        fun d(k: String) = if (p.has(k)) p.getDouble(k) else null
        return Pos(p.getString("arm"), p.getString("index"), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
            LocalDateTime.parse(p.getString("entryTime")), p.optString("orderId").ifEmpty { null }, p.optString("setup").ifEmpty { null },
            d("entryRef"), d("idx"), d("atr"), d("exit"), p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
            p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0), d("exitRef"), d("paperFill"), p.optString("adopted").ifEmpty { null }, p.optBoolean("lock", false),
            p.optBoolean("l25", false), if (p.has("breadth")) p.getBoolean("breadth") else null, p.optString("soldBy").ifEmpty { null }, p.optBoolean("st", false),
            p.optString("shadow").ifEmpty { null })
    }

    private fun writePos(p: Pos): JSONObject = JSONObject().put("arm", p.arm).put("index", p.index).put("symbol", p.symbol).put("right", p.right)
        .put("qty", p.qty).put("entry", p.entry).put("entryTime", p.entryTime.toString()).put("orderId", p.orderId ?: "").put("charges", p.charges)
        .apply {
            p.setup?.let { put("setup", it) }; p.entryRef?.let { put("entryRef", it) }; p.idx?.let { put("idx", it) }; p.atr?.let { put("atr", it) }
            p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) }
            p.exitRef?.let { put("exitRef", it) }; p.paperFill?.let { put("paperFill", it) }; p.adopted?.let { put("adopted", it) }
            if (p.lock) put("lock", true)
            if (p.l25) put("l25", true)
            p.breadth?.let { put("breadth", it) }
            p.soldBy?.let { put("soldBy", it) }
            if (p.st) put("st", true)
            p.shadow?.let { put("shadow", it) }
        }

    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject()
        o.put("armed", JSONObject().apply { b.armed.forEach { (k, v) -> put(k, v) } })
        o.put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
        o.put("decided", JSONArray().apply { b.decided.sorted().takeLast(600).forEach { put(it) } })
        o.put("status", JSONObject().apply { b.status.forEach { (k, v) -> put(k, v) } })
        b.status.writeTo(o)
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        o.put("migrated", JSONArray().apply { b.migrated.sorted().forEach { put(it) } })
        o.put("base", JSONObject().apply { b.base.forEach { (k, v) -> put(k, v) } })
        b.soloBase?.let { o.put("soloBase", it) }
        o.put("acted", JSONObject().apply { b.acted.forEach { (k, v) -> put(k, v) } })
        o.put("paused", JSONObject().apply { b.paused.forEach { (k, v) -> put(k, v) } })
        o.put("scores", JSONArray().apply { b.scores.takeLast(120).forEach { put(it) } })
        o.put("lock306100", b.lockOn)
        o.put("lockL25", b.l25On)
        b.breadthSince?.let { o.put("breadthSince", it.toString()) }
        o.put("donchianST", b.stOn)
        o.put("recordOnly", JSONArray().apply { b.recordOnly.sorted().forEach { put(it) } })
        o.put("locks", JSONObject().apply { b.locks.filterKeys { k -> b.positions.any { it.open && lockKey(it) == k } }.forEach { (k, v) -> put(k, v) } })
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        // 19 Oct (order speed): written on this book's own writer thread ([writer]), never under [lock] on the caller's - an
        // entry booked here ([record]) used to wait for the vault write before the next index's entry, and the 15-second stop
        // check's exits ([manageOnly]) waited for the lock behind it. The newest book is always the last written.
        pendingBytes.set(o.toString().toByteArray(Charsets.UTF_8))
        writer.kick()
        publish(b)
    }

    /** The newest book not yet on disk ([save]); [flushBook] writes it. */
    private val pendingBytes = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    private val writeLock = Any()
    private val writer = com.optionslab.ira.WriteBehind(com.optionslab.ira.WriteBehind.thread("passed-arms-writer")) { flushBook() }

    /** The newest saved book to the vault, one write at a time; a failed write is kept for the next one (said in diagnostics). */
    private fun flushBook() {
        synchronized(writeLock) {
            val bytes = pendingBytes.getAndSet(null) ?: return
            try {
                written.write(file, bytes)
            } catch (e: Exception) {
                pendingBytes.compareAndSet(null, bytes)
                runCatching { Diag.record("passed", "The passed arms' book could not be written (${e.javaClass.simpleName}); it is written again with the next change") }
            }
        }
    }

    /** TEST ONLY: the vault write on the caller's thread (tests that read the file at once). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var writeInlineForTest = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v; writer.inline = v }

    /** Every saved book on disk now (a test, a wipe), [timeoutMs] at most. Never on the order path. */
    internal fun awaitWrites(timeoutMs: Long = 10_000L): Boolean = writer.await(timeoutMs).also { flushBook() }

    private fun soloBase(b: Book): SoloMidday.Baseline = b.soloBase?.split('|')?.let { runCatching { SoloMidday.Baseline(it[0].toInt(), it[1].toDouble()) }.getOrNull() }
        ?: SoloMidday.Baseline()

    private fun publish(b: Book) {
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        val closed = b.positions.filter { !it.open }
        _view.value = View(b.armed.toMap(), b.status.shown({ k, _ -> k == ST_UNJUDGED_KEY }), b.positions.filter { it.open }, closed.takeLast(30), b.positions.filter { it.day == today },
            b.log.takeLast(40), closed, b.marks.toMap(), b.paused.toMap(), b.scores.filter { it.startsWith("$today|") }.map { it.substringAfter('|').substringAfter('|') },
            model != null, b.base.toMap(), soloBase(b), b.lockOn, b.l25On, b.stOn, b.breadthSince, stUnjudgedOn(b.status, today), b.recordOnly.toSet())
        // 16 Oct: each open trade's own exits for the position popup's "Exits" line (memory only).
        runCatching { com.optionslab.ira.ArmExits.set("passed", b.positions.filter { it.open && it.shadow == null }.map { exitsOf(it, b.locks[lockKey(it)]) }) }
    }

    /**
     * [p]'s exits now, as the position popup words them: the stop on the premium (raised to a lock already earned, [lock] as
     * the book keeps it, "time|level"), the target, and the time it is out by - the time stop or the square-off, the earlier.
     * Solo 2.0's S1 is out on SoloMidday's index stop, its breakeven lock or 14:30.
     */
    internal fun exitsOf(p: Pos, lock: String?): com.optionslab.ira.ArmExits.Entry {
        if (p.setup == Solo2.Setup.S1.key) {
            val idxStop = if (p.idx != null && p.atr != null) SoloMidday.stopLevel(p.side, p.idx, p.atr) else null
            val held = lock?.split('|')?.getOrNull(1)?.toDoubleOrNull()
            // 16 Oct: a locked S1 keeps its index exits, with the premium ladder beside them (its first step, or "lock held").
            return com.optionslab.ira.ArmExits.Entry("passed", label(p.arm), false, p.symbol, held, null, SoloMidday.EXIT,
                (idxStop?.let { "stop on the index at %.2f".format(Locale.ENGLISH, it) } ?: "stop on the index") + ", breakeven lock" +
                    if (!p.lock) "" else if (held != null) "; premium lock held" else "; on the premium, " + firstStepWords(exitsOf(p)).orEmpty())
        }
        val x = exitsOf(p)
        val held = lock?.split('|')?.getOrNull(1)?.toDoubleOrNull()
        val stop = listOfNotNull(PassedArms.stopLevel(p.entry, x), held).maxOrNull()
        val sq = x.squareOff
        val at = PassedArms.timeExitAt(p.entryMinute, x)?.takeIf { it.toLocalDate() == p.day && it.toLocalTime().isBefore(sq) }?.toLocalTime() ?: sq
        // R66's L25 first: "+5% locked once +25%" before it is earned, "lock held" after.
        val step = if (held == null) firstStepWords(x) else "lock held"
        return com.optionslab.ira.ArmExits.Entry("passed", label(p.arm), false, p.symbol, stop, PassedArms.targetLevel(p.entry, x), at,
            step?.takeIf { x.steps.isNotEmpty() })
    }

    /** The ladder's first step in words. R64's keeps breakeven (NaN: the fill plus the charges) there: worded so, never "+NaN%". */
    private fun firstStepWords(x: PassedArms.PremiumExits): String? = x.steps.firstOrNull()?.let { (t, k) ->
        (if (k.isNaN()) "breakeven" else "+%.0f%%".format(Locale.ENGLISH, k * 100)) + " locked once +%.0f%%".format(Locale.ENGLISH, t * 100)
    }

    private fun now0(): LocalDateTime = runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }

    private fun note(b: Book, text: String) {
        b.log += "${now0()} $text"
        runCatching { Diag.record("passed-arms", text) }
    }

    // ---- Solo 2.0's model (read once, at start-up, off the main thread) --------------------------------------------------

    @Volatile private var model: Solo2.Model? = null
    @Volatile private var modelFailed: String? = null

    /** Reads the model from the assets once (a worker: the start-up's [refresh] or the first pass). Never on the main thread. */
    private fun ensureModel() {
        if (model != null || modelFailed != null || !ready()) return
        runCatching { app.assets.open(Solo2.ASSET).use { String(it.readBytes(), Charsets.UTF_8) } }
            .mapCatching { Solo2.parseModel(it) }
            .onSuccess { model = it }
            .onFailure { modelFailed = it.javaClass.simpleName; runCatching { Diag.record("passed-arms", "Solo 2.0's model could not be read: ${it.javaClass.simpleName}") } }
    }

    /** Read the book (the rows and the start-up's positions-first step call it, off the main thread); the model is read here once. */
    suspend fun refresh(): View {
        if (ready()) { ensureModel(); lock.withLock { publish(book()) } }
        return _view.value
    }

    /** A position is held (memory only). */
    fun holding(): Boolean = _view.value.open.isNotEmpty()

    /**
     * The watch needs this host's pass now (memory only): a position is held, or an arm is on (or record only, 19 Oct) and the time is 09:16 .. 15:16
     * (the entries run 09:20 .. 15:10; the 15:15 square-off). Outside that it costs the watch nothing. 22 Oct: from 09:16, not 09:19, so the
     * indices are followed on the stream ([follow]) before 09:19 starts: the 09:15 bar is whole at the 09:20 check.
     */
    fun wantsPass(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean =
        holding() || ((_view.value.armed.values.any { it } || _view.value.recordOnly.isNotEmpty()) && minute in WAKE_FROM until WAKE_UNTIL)

    private const val WAKE_FROM = 9 * 60 + 16
    private const val WAKE_UNTIL = 15 * 60 + 17

    /**
     * Switch [source] on or off - on paper, always. Off sells nothing it holds: its own exits still close it. [log]: Boss's flip
     * from the row (Autopilot's change log is told); false for the change log's own Undo. On after the forward bar switched it
     * off (or, 19 Oct, after record only): the bar counts from now ([Book.base] / Solo 2.0's baseline).
     */
    suspend fun setArmed(source: String, on: Boolean, log: Boolean = true): String = setMode(source, if (on) Mode.ON else Mode.OFF, log)

    /**
     * 19 Oct: [source] on, record only or off ([Mode]) - on paper, always; on and record only never both. Record only: its signals are
     * decided as when on, each kept as a would-be trade, nothing bought. Leaving on sells nothing it holds (its own exits still close
     * it). [log]: Boss's pick from the row (the change log is told, like [setArmed]); false for the change log's own Undo.
     */
    suspend fun setMode(source: String, mode: Mode, log: Boolean = true): String {
        if (!ready() || source !in SOURCES) return "${label(source)} is not ready yet."
        return lock.withLock {
            val b = book()
            val was = if (b.armed[source] == true) Mode.ON else if (source in b.recordOnly) Mode.RECORD else Mode.OFF
            if (was == mode) return@withLock "${label(source)} is already ${modeWord(mode)} (paper only)."
            b.armed[source] = mode == Mode.ON
            if (mode == Mode.RECORD) b.recordOnly += source else b.recordOnly -= source
            if (mode == Mode.ON && source == BreadthDonchianArm.SOURCE && b.breadthSince == null) b.breadthSince = runCatching { Market.today() }.getOrElse { LocalDate.now() }
            // 19 Oct (review): on again from record only, the forward bar also counts from now - its would-be trades were never
            // bought, so they never switch it off the moment it is back on (they stay in its record, labelled).
            if (mode == Mode.ON && (b.paused.remove(source) != null || was == Mode.RECORD)) {
                val nets = b.positions.filter { it.arm == source && !it.open }.map { it.net ?: 0.0 }
                if (source == Solo2.SOURCE) SoloMidday.baseline(nets).let { b.soloBase = "${it.from}|${it.peak}" } else b.base[source] = nets.size
            }
            val holds = if (b.positions.any { it.arm == source && it.open && it.shadow == null }) "; what it holds still closes by its own exits" else ""
            note(b, "${label(source)} " + when (mode) {
                Mode.ON -> "switched on, paper only"
                Mode.RECORD -> "set to record only: its breaks are kept as would-be trades, nothing is bought$holds"
                Mode.OFF -> "switched off$holds"
            })
            save(b)
            if (log) runCatching {
                if (mode == Mode.RECORD) com.optionslab.app.ira.Autopilot.passedArmRecordOnlyByBoss(source, label(source), modeChange(was))
                else com.optionslab.app.ira.Autopilot.passedArmSwitchedByBoss(source, label(source), research(source), mode == Mode.ON, modeChange(was))
            }
            when (mode) {
                Mode.ON -> "${label(source)} on, paper only. ${PassedArms.PASSED}."
                Mode.RECORD -> "${label(source)} is record only: would-be trades, nothing bought."
                Mode.OFF -> "${label(source)} off."
            }
        }
    }

    private fun modeWord(m: Mode): String = when (m) { Mode.ON -> "on"; Mode.RECORD -> "record only"; Mode.OFF -> "off" }

    /** [m] as the change log's "from" / "to" ([com.optionslab.ira.AutoChange]). */
    private fun modeChange(m: Mode): String = when (m) {
        Mode.ON -> com.optionslab.ira.AutoChange.ON; Mode.RECORD -> com.optionslab.ira.AutoChange.RECORD_ONLY; Mode.OFF -> com.optionslab.ira.AutoChange.SWITCHED_OFF
    }

    /** R64's lock in words (the notes, the notices). */
    const val LOCK_WORDS = "own stop and time exit, no fixed target, breakeven locked once +30%, +30% once +60%, +60% once +100%"

    /**
     * R64's 30-60-100 lock on or off for NEW paper trades of the three arms (open trades keep the exits they entered with). Paper
     * only. [log]: false for the change log's own Undo (the only caller today; the switch-on is logged by the 16 Oct update).
     */
    suspend fun setLock(on: Boolean, log: Boolean = false): String {
        if (!ready()) return "The passed arms are not ready yet."
        return lock.withLock {
            val b = book()
            if (b.lockOn == on) return@withLock "The 30-60-100 lock is already ${if (on) "on" else "off"} (paper only)."
            b.lockOn = on
            note(b, if (on) "The 30-60-100 lock on for new paper trades: $LOCK_WORDS" else "The 30-60-100 lock off: new paper trades use the research exits" +
                if (b.positions.any { it.open && it.lock }) "; open trades keep the lock they entered with" else "")
            save(b)
            if (log) runCatching { com.optionslab.app.ira.Autopilot.logPassedLockOn() }
            if (on) "The 30-60-100 lock is on for new paper trades." else "The 30-60-100 lock is off: new paper trades use the research exits."
        }
    }

    /** R66's L25 step in words (the notes, the notices). */
    const val L25_WORDS = "+5% locked once the option has been +25% above the fill; the rest of the 30-60-100 lock stays, locks only rise"

    /**
     * R66's L25 (+5% locked once +25%) on or off for NEW paper trades of the three arms that enter under the 30-60-100 lock (open
     * trades keep the ladder they entered with). Paper only. [log]: false for the change log's own Undo (the switch-on is logged by
     * the 16 Oct update).
     */
    suspend fun setL25(on: Boolean, log: Boolean = false): String {
        if (!ready()) return "The passed arms are not ready yet."
        return lock.withLock {
            val b = book()
            if (b.l25On == on) return@withLock "The +5% once +25% step is already ${if (on) "on" else "off"} (paper only)."
            b.l25On = on
            note(b, if (on) "L25 on for new paper trades under the 30-60-100 lock: $L25_WORDS" else "L25 off: new paper trades use the plain 30-60-100 lock" +
                if (b.positions.any { it.open && it.l25 }) "; open trades keep the step they entered with" else "")
            save(b)
            if (log && on) runCatching { com.optionslab.app.ira.Autopilot.logPassedL25On() }
            if (on) "The +5% once +25% step is on for new paper trades" + (if (b.lockOn) "." else ", but it acts only with the 30-60-100 lock, which is off.")
            else "The +5% once +25% step is off: new paper trades use the plain 30-60-100 lock."
        }
    }

    /**
     * R73 T7's Supertrend filter on or off for R51 Donchian 20's NEW breaks (open trades are not touched). Paper only. [log]: false
     * for the change log's own Undo (the switch-on is logged by the 16 Oct update).
     */
    suspend fun setSupertrend(on: Boolean, log: Boolean = false): String {
        if (!ready()) return "The passed arms are not ready yet."
        return lock.withLock {
            val b = book()
            if (b.stOn == on) return@withLock "${DonchianArm.LABEL}'s Supertrend filter is already ${if (on) "on" else "off"} (paper only)."
            b.stOn = on
            note(b, if (on) "${DonchianArm.LABEL}: Supertrend filter on: ${DonchianArm.ST_RULE}" else "${DonchianArm.LABEL}: Supertrend filter off: new breaks are taken on the plain rules")
            save(b)
            if (log && on) runCatching { com.optionslab.app.ira.Autopilot.logPassedSupertrendOn() }
            if (on) "${DonchianArm.LABEL} takes a break only when Supertrend (10, 3) agrees (paper only)."
            else "${DonchianArm.LABEL} is back on its plain rules: every break is taken (paper only)."
        }
    }

    /**
     * The diagnostics lines: each arm's switch, open trades, record, apart index and forward test. Every rupee figure is
     * written as is for Copy diagnostics (on the phone only); [hideNet] (the uploaded file) writes [NET_HIDDEN] (20 Oct: Boss's 19 Oct
     * file carried "9 closed on it -10,947, 10 on the research exits -1,049" and "FINNIFTY 3 2,173", rupees the upload
     * must never hold), and its forward test's and comparison's "₹" figures are hidden the same way.
     */
    fun diagLine(hideNet: Boolean = false): String {
        val v = _view.value
        fun m(x: Double) = if (hideNet) NET_HIDDEN else "%,.0f".format(Locale.ENGLISH, x)
        fun rupees(text: String) = if (hideNet) RUPEE_FIGURE.replace(text, NET_HIDDEN) else text
        return SOURCES.joinToString("\n") { s ->
            val c = v.closedOf(s)
            val ap = c.filter { it.index == apart(s) }
            val wb = v.wouldBeOf(s)
            "${label(s)}: ${if (v.on(s)) "on (paper only)" else if (v.recording(s)) com.optionslab.ira.PassedArmsTalk.RECORD_ONLY_WORDS else "off"}" +
                "${v.paused[s]?.let { " (switched itself off: $it)" } ?: ""} · open ${v.open.count { it.arm == s }} · " +
                "${c.size} closed ${m(c.sumOf { it.net ?: 0.0 })} after charges · " +
                // 19 Oct: the would-be trades (nothing bought) counted apart within that record.
                (if (wb.isNotEmpty()) "of them ${wb.size} would-be ${m(wb.sumOf { it.net ?: 0.0 })} · " else "") +
                "${apart(s)} ${ap.size} ${m(ap.sumOf { it.net ?: 0.0 })} · " +
                rupees(v.forwardLine(s)) + " · 30-60-100 lock ${if (v.lockOn) "on" else "off"} for new trades, ${c.count { it.lock }} closed on it " +
                    "${m(c.filter { it.lock }.sumOf { it.net ?: 0.0 })}, ${c.count { !it.lock }} on the research exits ${m(c.filter { !it.lock }.sumOf { it.net ?: 0.0 })}" +
                    " · L25 +5% once +25% ${if (v.l25On && v.lockOn) "on" else if (v.l25On) "on (acts only with the lock, which is off)" else "off"} for new trades, " +
                    "${c.count { it.l25 }} closed on it ${m(c.filter { it.l25 }.sumOf { it.net ?: 0.0 })}" + (if (s == DonchianArm.SOURCE) " · Supertrend filter (R73 T7) ${if (v.stOn) "on" else "off"} for new breaks, " +
                        "${v.stUnjudged} not judged today, " +
                        "${c.count { it.st }} closed on it ${m(c.filter { it.st }.sumOf { it.net ?: 0.0 })}, ${c.count { !it.st }} on the plain rules ${m(c.filter { !it.st }.sumOf { it.net ?: 0.0 })}" else "") +
                    (if (s == Solo2.SOURCE) " · model ${if (v.modelLoaded) "loaded" else modelFailed ?: "not loaded yet"}" else "") +
                // (16 Oct, R73 review: the unjudged count is said once above, today's only; its kept line is not repeated.)
                v.status.filterKeys { it.startsWith("$s|") && it != ST_UNJUDGED_KEY }.toSortedMap().entries.joinToString("") { (k, x) -> " · ${k.substringAfter('|')} ${rupees(x)}" } +
                if (s == BreadthDonchianArm.SOURCE) " · ${rupees(v.comparison())}" else ""
        } + " · ${PassedArms.PASSED}"
    }

    /** What the uploaded diagnostics write for a rupee figure ([diagLine]). */
    const val NET_HIDDEN = "(net hidden)"
    /** A rupee figure in words made for the phone ("−₹1,049", "+₹2,173", "₹ 500", "Rs 40,000"). */
    private val RUPEE_FIGURE = Regex("[-+−]?\\s*(?:₹|\\bRs\\.?)\\s*[-+−]?\\d[\\d,]*(?:\\.\\d+)?")

    // ---- the data the passes read --------------------------------------------------------------------------------------

    /** An index's earlier sessions (oldest first, at most ~11) and its daily candles, read once a day in the background. */
    internal class Hist(val day: LocalDate, val sessions: List<PassedArms.DayBars>, val daily: List<Bar>)

    private val hist = java.util.concurrent.ConcurrentHashMap<String, Hist>()
    @Volatile private var histTried: Pair<LocalDate, Long>? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The history of [index] for [day] (null: not read yet). Memory only. Shared with the Watchlist ([WatchlistHost]). */
    internal fun history(index: String, day: LocalDate): Hist? = hist[index]?.takeIf { it.day == day }

    private fun upKey(index: String): String = runCatching { ChartFeed.instrumentKey(index) }.getOrNull()
        ?: (if (index == "SENSEX") "BSE_INDEX|SENSEX" else Upstox.INDEX_KEYS[index] ?: index)

    private fun toBars(bars: List<Upstox.Bar>): List<Bar> = bars.map { b ->
        Bar(b.istDate.atTime(b.istMinute / 60, b.istMinute % 60), b.open, b.high, b.low, b.close)
    }

    /**
     * Starts the day's history reads in the background (from 08:45; at most every 10 minutes until they are in): each index's
     * 1-minute bars of the last 16 calendar days before today and its daily candles of the last 45, and India VIX's. The passes
     * never wait for them: until they are in, a rule that needs them waits (R51 still decides from today's 21st bar).
     */
    internal fun prefetch(now: LocalDateTime, indices: List<String> = ALL_INDICES) {
        val day = now.toLocalDate()
        if (now.toLocalTime().isBefore(LocalTime.of(8, 45))) return
        if (indices.all { history(it, day) != null } && history(VIX, day) != null) return
        val t = histTried
        val ms = System.currentTimeMillis()
        if (t != null && t.first == day && ms - t.second < 10 * 60_000L) return
        histTried = day to ms
        background.launch { runCatching { prefetchNow(day, indices) } }
    }

    internal const val VIX = "INDIAVIX"

    /** [prefetch]'s reads for [day] (tests call it directly). */
    internal suspend fun prefetchNow(day: LocalDate, indices: List<String> = ALL_INDICES) {
        for (u in indices + VIX) {
            if (history(u, day) != null) continue
            runCatching {
                val key = if (u == VIX) Upstox.INDEX_KEYS.getValue("INDIAVIX") else upKey(u)
                val ones = testHistory?.invoke(u) ?: toBars(Net.history(key, day.minusDays(16), day.minusDays(1)))
                val daily = testDaily?.invoke(u) ?: toBars(Net.daily(key, day.minusDays(45), day.minusDays(1)))
                val sessions = ones.filter { it.start.toLocalDate().isBefore(day) }.groupBy { it.start.toLocalDate() }.toSortedMap()
                    .map { (d, l) -> PassedArms.DayBars.of(d, l) }.filter { it.count > 0 }
                if (sessions.isNotEmpty() || daily.isNotEmpty()) hist[u] = Hist(day, sessions, daily.filter { it.start.toLocalDate().isBefore(day) })
            }
        }
    }

    /** Today's finished 1-minute bars of [key] at [now] (the feed's, with the stream's newer whole minutes), bar start times. */
    private suspend fun minutes(key: String, now: LocalDateTime): List<Bar> {
        val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
        val feed = Net.intradayQuick(key).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
        val bars = runCatching { LocalCandles.overlay(key, feed, nowSec) }.getOrDefault(feed)
            .filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }
        return toBars(bars).sortedBy { it.start }.distinctBy { it.start }
    }

    /** [u]'s finished minutes today, read at most once a minute (shared with the Watchlist, [WatchlistHost]). */
    internal suspend fun indexBars(u: String, now: LocalDateTime): List<Bar> {
        val minute = now.withSecond(0).withNano(0)
        lastRead[u]?.takeIf { it.first == minute }?.let { return it.second }
        return minutes(upKey(u), now).also { lastRead[u] = minute to it }
    }

    /** The index's listed options saved on the phone today (SENSEX: BSE's list); never a download in the pass. */
    private fun listed(u: String): List<Upstox.Contract> {
        val bfo = u == Paper.BFO_UNDERLYING
        val saved = runCatching { if (bfo) Market.cachedBfoContracts() else Market.cachedContracts() }.getOrNull()
        if (saved == null || saved.first != Market.today()) {
            runCatching { if (bfo) Market.refreshBfoInBackground() else Market.refreshContractsInBackground() }
            return emptyList()
        }
        return saved.second.filter { it.underlying == u }
    }

    /** The reads of one pass, made outside the lock: each index's minutes today (null: unread), and India VIX's. */
    private class Reads(val bars: Map<String, List<Bar>?>, val vix: List<Bar>?)

    /** The minute each index was last read for entries (each index at most once a minute). */
    private val lastRead = java.util.concurrent.ConcurrentHashMap<String, Pair<LocalDateTime, List<Bar>>>()

    /**
     * 22 Oct (a break said only "missed"): each index's last entry checks - when, and the last whole minute it had - for the words
     * of a break whose fill window passed ([DonchianArm.missedWhy]). Memory only, [LOOKS_KEPT] an index.
     */
    private val looks = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<DonchianArm.Look>>()
    private const val LOOKS_KEPT = 12

    private fun looked(u: String, at: LocalDateTime, bars: List<Bar>?) {
        val q = looks.getOrPut(u) { java.util.ArrayDeque() }
        synchronized(q) {
            q.addLast(DonchianArm.Look(at, bars?.lastOrNull { it.start.toLocalDate() == at.toLocalDate() }?.start?.toLocalTime(), failed = bars == null))
            while (q.size > LOOKS_KEPT) q.removeFirst()
        }
    }

    private fun looksOf(u: String): List<DonchianArm.Look> = looks[u]?.let { q -> synchronized(q) { q.toList() } }.orEmpty()

    /** R72's breadth reads all five indices: the others' looks, so a late one is named (R51 reads its own index only). */
    private fun othersOf(src: String, u: String): Map<String, List<DonchianArm.Look>> =
        if (src != BreadthDonchianArm.SOURCE) emptyMap() else BreadthDonchianArm.UNDERLYINGS.filter { it != u }.associateWith { looksOf(it) }

    /** 22 Oct: why a due break could not be bought at its last try ("day|arm|index|bar|side" -> why), for [DonchianArm.missedWhy]. Memory only. */
    private val tried = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val TRIED_KEPT = 50

    /**
     * 22 Oct (R72's 09:20 breaks waited to 09:21:24): the stream builds an index's minutes only while it follows the index, and a
     * minute it joined part-way is never used ([com.optionslab.ira.LiveCandles]). FINNIFTY, MIDCPNIFTY and SENSEX were followed only
     * from the first entry check's read (09:20:03), so their 09:19 minute was never whole there, the feed's came later, and breadth
     * (four of five breaking) could not be judged until the next minute's check. Now each pass from 09:16 to 15:12 touches the
     * armed arms' indices (a touch lasts 3 minutes; each pass renews it), so every bar's last minute is whole when it closes.
     * Memory and the stream's own subscription only: no disk, no network read, never on the order path.
     */
    private fun follow(now: LocalDateTime, v: View) {
        val m = now.hour * 60 + now.minute
        if (m < WAKE_FROM || m > FOLLOW_UNTIL) return
        val tokens = SOURCES.filter { v.on(it) || v.recording(it) }.flatMap { underlyings(it) }.distinct()
            .mapNotNull { u -> runCatching { LocalCandles.tokenOf(upKey(u)) }.getOrNull() }
        if (tokens.isNotEmpty()) KiteStream.touch(tokens)
    }

    private const val FOLLOW_UNTIL = 15 * 60 + 12

    /** Whether an entry decision may be due at [t]: a 5-minute bar has just closed (its first 3 minutes), or Solo's 12:00. */
    internal fun entryMinute(t: LocalTime): Boolean {
        val m = t.hour * 60 + t.minute
        if (m < 9 * 60 + 20 || m > 15 * 60 + 12) return false
        return (m - (9 * 60 + 15)) % 5 <= 2 || m in 12 * 60 until 12 * 60 + 4
    }

    private suspend fun reads(now: LocalDateTime, v: View): Reads {
        val want = SOURCES.filter { v.on(it) || v.recording(it) }.flatMap { underlyings(it) }.distinct()
        val minute = now.withSecond(0).withNano(0)
        // 16 Oct ("Signal to order took up to 6.5 s"): the underlyings' minutes and the VIX are read side by side, not one
        // after another (each read was a network wait the entry stood behind). Same reads, same bars.
        return kotlinx.coroutines.coroutineScope {
            val reads = want.map { u ->
                u to async(kotlinx.coroutines.Dispatchers.IO) {
                    lastRead[u]?.takeIf { it.first == minute }?.second
                        ?: runCatching { minutes(upKey(u), now) }.getOrNull()?.also { lastRead[u] = minute to it }
                }
            }
            val vix = async(kotlinx.coroutines.Dispatchers.IO) {
                if (v.on(Solo2.SOURCE) || v.recording(Solo2.SOURCE)) runCatching { minutes(Upstox.INDEX_KEYS.getValue("INDIAVIX"), now) }.getOrNull() else null
            }
            val out = HashMap<String, List<Bar>?>()
            for ((u, d) in reads) out[u] = d.await()
            for ((u, d) in out) looked(u, now, d)
            Reads(out, vix.await())
        }
    }

    // ---- the passes ----------------------------------------------------------------------------------------------------

    /**
     * The fast path's minute lane (open positions first): only the open trades' exits - never an entry's reads, so these arms
     * never hold up another arm's exits. Nothing held: returns at once (memory only).
     */
    suspend fun manageOnly() {
        if (!ready() || com.optionslab.app.BuildConfig.GOLD || !holding() || !Market.isTradingDay()) return
        lock.withLock {
            val b = book()
            val changed = runCatching { exits(b, now0()) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            if (changed) save(b) else publish(b)
        }
    }

    /** Only one entries phase at a time (the exits never wait for it: they take only the book's own lock). */
    private val entryLock = Mutex()

    /**
     * The watch's arms passes: the open trades' exits and the forward bars first, under the book's lock; then each arm's entries
     * (their index reads before, their price reads and orders on a copy of the book, OUTSIDE its lock), merged back under it -
     * so the minute lane's exits ([manageOnly]) never wait behind an entry's network reads.
     */
    suspend fun tick() {
        if (!ready() || com.optionslab.app.BuildConfig.GOLD || !Market.isTradingDay()) return
        val now = now0()
        if (cache == null) lock.withLock { book() }
        ensureModel()
        runCatching { prefetch(now) }
        lock.withLock {
            val b = book()
            var changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            changed = runCatching { forward(b) }.getOrDefault(false) || changed
            changed = prune(b) || changed
            if (changed) save(b) else publish(b)
        }
        val v = _view.value
        if (!v.armed.values.any { it } && v.recordOnly.isEmpty()) return
        runCatching { follow(now, v) }
        if (!entryMinute(now.toLocalTime())) return
        if (!entryLock.tryLock()) return
        try {
            val reads = runCatching { reads(now, v) }.getOrNull() ?: return
            val (work, n0, log0, sc0) = lock.withLock { val b = book(); Quad(b.copy(), b.positions.size, b.log.size, b.scores.size) }
            // 19 Oct: an arm set to record only runs here too (its breaks become would-be trades in [buyOne]).
            // 19 Oct (order speed): this bar's due Donchian options priced side by side first, not one after another inside each
            // index's entry (each a network wait when the stream does not have the option yet). Memory and reads only.
            runCatching { prewarm(work, now, reads) }
            val st0 = HashMap<String, String>(work.status)
            var changed = false
            if (work.active(DonchianArm.SOURCE)) changed = runCatching { donchian(work, now, reads) }.getOrElse { work.status["${DonchianArm.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (work.active(OovArm.SOURCE)) changed = runCatching { oov(work, now, reads) }.getOrElse { work.status["${OovArm.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (work.active(Solo2.SOURCE)) changed = runCatching { solo2(work, now, reads) }.getOrElse { work.status["${Solo2.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (work.active(BreadthDonchianArm.SOURCE)) changed = runCatching { breadth(work, now, reads) }.getOrElse { work.status["${BreadthDonchianArm.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (!changed) return
            lock.withLock {
                val b = book()
                // 16 Oct: each entry is booked in the book the moment it fills ([buy]); only one that is not there yet is added.
                val added = work.positions.drop(n0).filter { a -> b.positions.none { same(it, a) } }
                b.positions += added
                added.forEach { p -> work.marks[p.symbol]?.let { b.marks[p.symbol] = it } }
                b.decided += work.decided
                // 21 Oct: a line written again this pass (same words, a new day) carries its day over too.
                work.status.forEach { (k, x) -> if (st0[k] != x || work.status.days[k] != b.status.days[k]) b.status[k] = x }
                b.log += work.log.drop(log0)
                b.scores += work.scores.drop(sc0)
                save(b)
            }
        } finally { entryLock.unlock() }
    }

    private data class Quad(val a: Book, val b: Int, val c: Int, val d: Int)

    /** How long the entries wait at most for [prewarm]'s reads (each read is also capped by the quick feed's own timeouts). */
    private const val PREWARM_MS = 3_000L

    /**
     * The options R51 Donchian 20's and R72 Breadth Donchian's breaks due now (not decided yet, inside their fill window) would
     * buy, priced side by side ([Paper.recentQuote]: the stream's tick, a price read in the last seconds, else one read), at most
     * [PREWARM_MS]; [buyOne] then finds each price in memory. Decides nothing: the entries still make every check.
     */
    private suspend fun prewarm(b: Book, now: LocalDateTime, reads: Reads) {
        val r51 = b.active(DonchianArm.SOURCE)
        val r72 = b.active(BreadthDonchianArm.SOURCE)
        if (!r51 && !r72) return
        val today = now.toLocalDate()
        val all = seriesAll(today, reads)
        val sigs = ArrayList<Pair<String, DonchianArm.Signal>>()
        if (r51) for (u in DonchianArm.UNDERLYINGS) all[u]?.let { ser -> DonchianArm.signals(u, ser, today).forEach { sigs += DonchianArm.SOURCE to it } }
        if (r72) runCatching { BreadthDonchianArm.signals(all, today) }.getOrDefault(emptyList()).forEach { sigs += BreadthDonchianArm.SOURCE to it }
        val due = sigs.filter { (src, x) -> "$today|$src|${x.underlying}|${x.bar.b}|${x.side}" !in b.decided && !now.isBefore(x.entryTime) && DonchianArm.mayFill(x, now) }
        if (due.isEmpty()) return
        val contracts = due.mapNotNull { (_, x) ->
            // Only a list already saved today (never a download started from here: the entries themselves ask for one).
            val l = runCatching { if (x.underlying == Paper.BFO_UNDERLYING) Market.cachedBfoContracts() else Market.cachedContracts() }.getOrNull()
                ?.takeIf { it.first == today }?.second?.filter { it.underlying == x.underlying }.orEmpty()
            if (l.isEmpty()) return@mapNotNull null
            val exp = OrbRules.expiryAfter(today, l.map { it.expiry }.distinct()) ?: return@mapNotNull null
            Paper.ticketContract(x.underlying, exp, x.strike.toDouble(), if (x.side > 0) Right.CE else Right.PE)
        }.distinctBy { it.symbol }
        // Read on the background scope, so the entries wait [PREWARM_MS] at most even when a read ignores cancellation.
        val reads = contracts.map { c -> background.async { runCatching { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) } } }
        kotlinx.coroutines.withTimeoutOrNull(PREWARM_MS) { reads.forEach { it.await() } }
    }

    /** The same trade: one paper order (or, with none, the same option bought at the same time). */
    private fun same(a: Pos, b: Pos): Boolean = a.symbol == b.symbol && a.arm == b.arm && if (a.orderId != null || b.orderId != null) a.orderId == b.orderId else a.entryTime == b.entryTime

    /** The options an entry is buying now (between its order and its booking): the safety net never takes them for orphans. */
    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    internal const val ADOPT_FILL = "fill"

    /** The options these arms hold or are buying now (memory only): the safety net's ([OrphanWatch]) "watched here". */
    fun claims(): Set<String> = _view.value.open.filter { it.shadow == null }.map { it.symbol }.toSet() + inFlight

    /** Closed trades kept: the last [CLOSED_KEPT] of each arm; the forward bars' baselines move with what is dropped. */
    private fun prune(b: Book): Boolean {
        var changed = false
        for (s in SOURCES) {
            val closed = b.positions.filter { it.arm == s && !it.open }
            val drop = closed.size - CLOSED_KEPT
            if (drop <= 0) continue
            val gone = closed.take(drop).toSet()
            b.positions.removeAll { it in gone }
            b.base[s]?.let { b.base[s] = (it - drop).coerceAtLeast(PassedArms.ForwardBar.TRADES) }
            if (s == Solo2.SOURCE) soloBase(b).takeIf { it.from > 0 }?.let { x -> b.soloBase = "${(x.from - drop).coerceAtLeast(0)}|${x.peak - gone.sumOf { it.net ?: 0.0 }}" }
            changed = true
        }
        return changed
    }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    private fun say(b: Book, key: String, why: String, final: Boolean = false): Boolean {
        if (final) note(b, "${label(key.substringBefore('|'))} ${key.substringAfter('|', "")}: $why")
        if (b.status.sameToday(key, why)) return final
        b.status[key] = why
        return true
    }

    // ---- the forward bars ----------------------------------------------------------------------------------------------

    /** Each arm's forward bar on its closed trades: a failed bar switches it off once (risk-lowering, logged). */
    private fun forward(b: Book): Boolean {
        var changed = false
        for (s in SOURCES) {
            if (b.armed[s] != true) continue
            val c = b.positions.filter { it.arm == s && !it.open }
            val nets = c.map { it.net ?: 0.0 }
            val (fail, key, why) = if (s == Solo2.SOURCE) {
                val base = soloBase(b)
                val chk = SoloMidday.judge(nets, base)
                Triple(SoloMidday.switchOff(chk.verdict) && !base.actedOn(b.acted[s], chk.verdict), base.key(chk.verdict),
                    if (chk.verdict == SoloMidday.Verdict.FAILED_DRAWDOWN) "a drawdown of ${SoloMidday.rs(chk.drawdown)}, beyond −₹25,000"
                    else "${chk.record.trades} trades at ${SoloMidday.rs(chk.record.perTrade ?: 0.0)} a trade (it needs more than ₹0)")
            } else {
                val base = b.base[s] ?: 0
                val chk = PassedArms.ForwardBar.judge(nets, c.map { it.spread }, base)
                val k = "${chk.verdict}@$base"
                Triple(PassedArms.ForwardBar.switchOff(chk.verdict) && b.acted[s] != k, k,
                    if (chk.verdict == PassedArms.ForwardBar.Verdict.FAILED_NET) "net ${PassedArms.rs(chk.judgedNet)} after 40 paper trades (below ₹0)"
                    else "net ${PassedArms.rs(chk.judgedNet)} after 40 paper trades is below the cost of twice the spread (${PassedArms.rs(chk.judgedSpread)})")
            }
            if (!fail) continue
            b.armed[s] = false
            b.acted[s] = key
            b.paused[s] = why
            note(b, "${label(s)} switched itself off on its forward-test bar: $why (paper; switching it back on is Boss's choice)")
            runCatching { com.optionslab.app.ira.Autopilot.logPassedArmOff(s, label(s), why) }
            runCatching { Notifier.post(app, NOTICE + 20 + SOURCES.indexOf(s), Notifier.SELL, "${label(s)} switched itself off · Paper",
                "Its forward-test bar, set in advance: $why. What it holds closes by its own exits. Switching it back on is your choice.", "almanac") }
            changed = true
        }
        return changed
    }

    // ---- entries -------------------------------------------------------------------------------------------------------

    /** Whether the bot is stopped for today (Boss's stop, or the daily loss limit). Unknown counts as stopped. */
    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    /** [u]'s expiry today by the list saved on the phone (null: no list yet - nothing is bought). */
    private fun expiryToday(u: String, today: LocalDate): Boolean? {
        val l = listed(u)
        if (l.isEmpty()) return null
        return HeroRules.isExpiryDay(today, l.map { it.expiry }.distinct())
    }

    /** The day's sessions of [u] up to now: the earlier ones (history) and today's. */
    private fun todayBars(u: String, today: LocalDate, reads: Reads): PassedArms.DayBars? = reads.bars[u]?.let { PassedArms.DayBars.of(today, it) }

    /**
     * Buys [strikes] (the first that is listed and fills) on [u] for [arm]: the guards, then a MARKET buy on the paper account.
     * The position, or why not (null position).
     */
    private suspend fun buy(b: Book, arm: String, u: String, side: Int, strikes: List<Int>, now: LocalDateTime, setup: String? = null,
                            idx: Double? = null, atr: Double? = null, keys: List<String> = emptyList(), breadth: Boolean? = null): Pair<Pos?, String> {
        val today = now.toLocalDate()
        val right = if (side > 0) Right.CE else Right.PE
        val expiry = OrbRules.expiryAfter(today, listed(u).map { it.expiry }.distinct()) ?: return null to "no $u expiry after today is listed"
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return null to "refused: the kill switch is on"
        if (stoppedToday()) return null to "refused: the bot is stopped for today"
        runCatching { DayLockGuard.refusal(false) }.getOrNull()?.let { return null to "refused: $it" }
        var why = "no listed strike"
        for (k in strikes) {
            val c = Paper.ticketContract(u, expiry, k.toDouble(), right) ?: run { why = "$k ${right.name} not listed"; null } ?: continue
            if (c.lotSize <= 0) { why = "lot size unknown"; continue }
            // One contract, one arm: never two of these arms holding the same option (each one's exits read its own lot); a
            // would-be trade holds nothing, so it never blocks one. (And the book itself: one the safety net took back since this
            // pass's copy was made.) 22 Oct (R72 FINNIFTY's 09:20 put lost to R52, which had just bought that very put, then
            // said "missed"): the entry is kept as a would-be trade, labelled with the arm that holds it ([SHADOW_HELD]), so the
            // arm's record still counts its signal - nothing is bought, never mirrored live (it was refused before, live too).
            val held = b.positions.firstOrNull { it.open && it.shadow == null && it.symbol == c.symbol && !mayShare(arm, it.arm) }
                ?: _view.value.open.firstOrNull { it.shadow == null && it.symbol == c.symbol && !mayShare(arm, it.arm) }
            // 19 Oct (Boss: no duplicate Donchian contracts): R51 Donchian 20 and R72 Breadth Donchian no longer both buy one option -
            // the second is kept as a would-be trade (its record and the R72-vs-R51 comparison still count it). R51 decides
            // first on a bar both break, so R51 holds it and R72 records the would-be; the other way round when R72 held it first.
            // 19 Oct review (CLAUDE.md's LIVE rule): only while this arm's entry cannot also be mirrored live - skipping it would
            // skip its live trade too; then both buy, as before.
            val twin = if (ExposureCapHost.liveMirrorPossible(arm)) null
                else b.positions.firstOrNull { it.open && it.shadow == null && it.symbol == c.symbol && mayShare(arm, it.arm) }
                    ?: _view.value.open.firstOrNull { it.shadow == null && it.symbol == c.symbol && mayShare(arm, it.arm) }
            // 19 Oct (order speed): each order timed from its own decision ([OrderTiming.ownDecision]), not the run's first.
            val step = OrderTiming.ownDecision { buyOne(b, arm, u, c, right, now, setup, idx, atr, keys, breadth, twin, held) }
            why = step.why
            if (step.pos != null || step.stop) return step.pos to step.why
            // Held by another arm and no price for its would-be: never another strike instead (as before), said with the holder.
            if (held != null) return null to "${c.symbol} is held by ${label(held.arm)} (${step.why})"
        }
        return null to why
    }

    /** One strike's try ([buyOne]): the trade (a real one, or a would-be one), why not, and whether to stop trying strikes. */
    private class BuyStep(val pos: Pos?, val why: String, val stop: Boolean)

    /**
     * [c] for [arm]: its price, a would-be trade when [twin] holds it, the account guard, the MARKET buy (the same-direction cap
     * may refuse it: a would-be trade), and the trade booked at once. Run as its own decision ([buy]).
     */
    private suspend fun buyOne(b: Book, arm: String, u: String, c: Paper.Contract, right: Right, now: LocalDateTime, setup: String?, idx: Double?,
                               atr: Double?, keys: List<String>, breadth: Boolean?, twin: Pos?, held: Pos? = null): BuyStep {
        // 19 Oct: the price read in the last few seconds when there is one ([prewarm] read this bar's options side by side before
        // the entries); the fill itself is priced fresh in Paper.place (2 s at most), as before.
        val q = runCatching { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) }.getOrNull()
        val ref = q?.ltp?.takeIf { it > 0 } ?: return BuyStep(null, "${c.symbol}: no fresh price", false)
        // 19 Oct (Boss: "Yes, keep only breadth donchain"): an arm set to record only buys nothing - its entry is a would-be trade,
        // priced at the ask when the quote has one (a buyer pays it), else the last price, as the cap's. Never mirrored live, never
        // on the account (so never counted by the same-direction cap and never a twin for R72), no notice.
        if (arm in b.recordOnly && b.armed[arm] != true)
            return BuyStep(wouldBe(b, arm, u, c, right, q?.ask?.takeIf { it > 0 } ?: ref, now, setup, idx, atr, keys, breadth, SHADOW_OFF,
                "switched to record only"), "would-be", true)
        // 22 Oct: another of these arms holds this option (not R51 / R72's twin): a would-be trade at the ask (a buyer pays it), else
        // the last price, as the cap's; the line names the arm.
        if (held != null) {
            val p = wouldBe(b, arm, u, c, right, q?.ask?.takeIf { it > 0 } ?: ref, now, setup, idx, atr, keys, breadth, SHADOW_HELD,
                "${c.symbol} is held by ${label(held.arm)}")
            return BuyStep(p, "would-be", true)
        }
        if (twin != null) {
            // Priced at the twin's own real fill (its spread and slippage paid), never a better price than the account got.
            val p = wouldBe(b, arm, u, c, right, twin.entry, now, setup, idx, atr, keys, breadth, SHADOW_TWIN, "${label(twin.arm)} already holds ${c.symbol}")
            return BuyStep(p, "would-be", true)
        }
        // 16 Oct: the account guard's view shares prices read in the last [Paper.SHARED_QUOTE_MS] (it re-read every held
        // symbol before each entry); the fill itself is priced fresh in Paper.place (2 s at most).
        val snap = runCatching { Paper.snapshot(reuseQuotesMs = Paper.SHARED_QUOTE_MS) }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", 1, ref), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return BuyStep(null, "refused by the account guard: " + refusals.joinToString(" "), true)
        runCatching { LiveRecorder.signal(label(arm), c.symbol, u, right.name, ref, paper = true) }
        inFlight += c.symbol
        var why = ""
        val p: Pos = (try {
            val r = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, known = q, auto = label(arm), mirror = arm)
            // 19 Oct: the same-direction cap refused it: nothing was bought; kept as a would-be trade at the price seen.
            // Priced at the ask when the quote has one (a buyer pays it), else the last price.
            if (r.capped) return BuyStep(wouldBe(b, arm, u, c, right, q?.ask?.takeIf { it > 0 } ?: ref, now, setup, idx, atr, keys, breadth,
                SHADOW_CAP, r.message), r.message, true)
            val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
            if (fill == null) {
                r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
                why = "${c.symbol} not filled (${r.message})"
                null
            } else {
                val charges = Paper.state.trades.filter { it.orderId == r.orderId }.sumOf { it.charges.toDouble() }
                val pos = Pos(arm, u, c.symbol, right.name, fill.quantity, fill.price, now, r.orderId, setup, ref, idx, atr, charges = charges, lock = b.lockOn,
                    l25 = b.lockOn && b.l25On, breadth = breadth, st = arm == DonchianArm.SOURCE && b.stOn)
                b.positions += pos
                b.marks[c.symbol] = fill.price
                b.decided += keys
                // 16 Oct (the unwatched re-entry): booked in the book itself at once, never only on the entries' copy until the
                // end of the pass - its stop, target and time exit run from the next minute-lane look, and a pass cut short or
                // a restart after the fill can never leave it held with no record here. Not cancellable between the two.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(pos, keys) }
                // 18 Oct: its live path ([ArmLive]): the same option and lots, its stop resting at Zerodha - only in Live with AI
                // trades on, its own "Trade live" on and every guard clear (memory only here; the order goes on a worker).
                runCatching { ArmLive.entered(ArmLive.Ask(arm, mirrorRef(pos), c, 1, pos.qty, pos.entry, stop = premiumStop(pos))) }
                r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${label(arm)} · entry") } }
                runCatching { Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", "${label(arm)} · entry", r.orderId) }
                pos
            }
        } finally { inFlight -= c.symbol }) ?: return BuyStep(null, why, false)
        runCatching { Notifier.post(app, NOTICE + SOURCES.indexOf(arm) * 5 + ALL_INDICES.indexOf(u).coerceAtLeast(0), Notifier.BUY,
            "${label(arm)} bought ${c.symbol} · Paper",
            "Bought %d @ %s. %s. %s.".format(Locale.ENGLISH, p.qty, px(p.entry), exitWords(p), PassedArms.PASSED), "almanac") }
        note(b, "${label(arm)} bought ${c.symbol} ${p.qty} @ ${px(p.entry)} · paper" + (setup?.let { " ($it)" } ?: ""))
        return BuyStep(p, "bought", true)
    }

    /** [Pos.shadow]: R51 or R72 already held the option (no duplicate Donchian contracts, 19 Oct). */
    const val SHADOW_TWIN = "twin"
    /** [Pos.shadow]: the same-direction cap refused the entry (19 Oct). */
    const val SHADOW_CAP = "cap"
    /** [Pos.shadow]: the arm is set to record only (19 Oct, Boss: "Yes, keep only breadth donchain"). */
    const val SHADOW_OFF = "off"
    /** [Pos.shadow]: another of these arms (not its Donchian twin) already held the option (22 Oct). */
    const val SHADOW_HELD = "held"

    /** Why a would-be trade was not bought, in words. */
    fun shadowWords(p: Pos): String = when (p.shadow) {
        SHADOW_TWIN -> "would-be: the other Donchian arm already held this option"
        SHADOW_CAP -> "would-be: refused by the same-direction cap"
        SHADOW_OFF -> "would-be: switched to record only"
        SHADOW_HELD -> "would-be: another arm already held this option"
        else -> ""
    }

    /**
     * A WOULD-BE trade of [arm] in [c] at [ref] (19 Oct, [Pos.shadow] = [kind]): nothing is bought; booked here like a real trade
     * (its decision made, its caps counted, its exits run on the prices seen), never mirrored live, no notice. [because]: why.
     */
    private suspend fun wouldBe(b: Book, arm: String, u: String, c: Paper.Contract, right: Right, ref: Double, now: LocalDateTime, setup: String?,
                                idx: Double?, atr: Double?, keys: List<String>, breadth: Boolean?, kind: String, because: String): Pos {
        runCatching { Paper.rememberContract(c) }
        val pos = Pos(arm, u, c.symbol, right.name, c.lotSize, ref, now, null, setup, ref, idx, atr, lock = b.lockOn, l25 = b.lockOn && b.l25On,
            breadth = breadth, st = arm == DonchianArm.SOURCE && b.stOn, shadow = kind)
        b.positions += pos
        if (c.symbol !in b.marks) b.marks[c.symbol] = ref
        b.decided += keys
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(pos, keys) }
        note(b, "${label(arm)} would-be ${c.symbol} ${pos.qty} @ ${px(ref)} (not bought: $because) · labelled apart")
        return pos
    }

    /** What the arm's status says for a trade it just took (a would-be one labelled as such). */
    private fun takenWords(p: Pos, what: String): String =
        if (p.shadow == null) "bought ${p.symbol} @ ${px(p.entry)} ($what)" else "would-be ${p.symbol} @ ${px(p.entry)} ($what; ${shadowWords(p)})"

    /**
     * [p] (just filled, on the entries' copy) booked in the book itself, with its decisions [keys]; saved. One the safety net
     * took back meanwhile (the same order) is replaced by it, while still open.
     */
    private suspend fun record(p: Pos, keys: List<String>) = lock.withLock {
        val r = book()
        val i = r.positions.indexOfFirst { same(it, p) }
        when {
            i < 0 -> r.positions += p
            r.positions[i].adopted != null && r.positions[i].open -> r.positions[i] = p
        }
        r.marks[p.symbol] = p.entry
        r.decided += keys
        save(r)
    }

    private fun exitWords(p: Pos): String = when {
        p.setup == Solo2.Setup.S1.key -> "Out on SoloMidday's index stop, at breakeven or at 14:30" +
            if (p.lock) "; on the premium, ${com.optionslab.ira.PassedArmsTalk.ladderWords(p.l25)}" else ""
        p.lock -> exitsOf(p).let { x ->
            listOfNotNull(x.stop?.let { "stop −%.0f%%".format(Locale.ENGLISH, it * 100) }, com.optionslab.ira.PassedArmsTalk.ladderWords(p.l25),
                x.tmaxMinutes?.let { "out after $it minutes" }, "square-off ${x.squareOff}").joinToString(", ")
        }
        else -> exitsOf(p).let { x ->
            listOfNotNull(x.stop?.let { "stop −%.0f%%".format(Locale.ENGLISH, it * 100) }, x.target?.let { "target +%.0f%%".format(Locale.ENGLISH, it * 100) },
                x.steps.firstOrNull()?.let { (t, k) -> "+%.0f%% locked once +%.0f%%".format(Locale.ENGLISH, k * 100, t * 100) },
                x.tmaxMinutes?.let { "out after $it minutes" }, "square-off ${x.squareOff}").joinToString(", ")
        }
    }

    /** S1's premium backstop when its index cannot be read: the Solo layer's -30% stop, SoloMidday's 14:30. */
    private val S1_BACKSTOP = PassedArms.PremiumExits(0.30, null, null, emptyList(), SoloMidday.EXIT)

    private fun exitsOf(p: Pos): PassedArms.PremiumExits = when (p.arm) {
        DonchianArm.SOURCE -> DonchianArm.EXITS
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.EXITS
        OovArm.SOURCE -> OovArm.EXITS
        else -> Solo2.exits(Solo2.Setup.entries.firstOrNull { it.key == p.setup } ?: Solo2.Setup.S2)
    }.let { if (p.lock) PassedArms.withLock(it, p.l25) else it }

    /** The lock's breakeven for [p]: its fill plus the round-trip charges per unit (R64: the fill + charges(entry, entry) / qty). */
    private fun breakeven(p: Pos): Double = p.entry + ProfitLock.roundTripPerUnit(p.entry, p.qty)

    /** The continuous 5-minute series of [u]: the earlier sessions' (history, when read) and today's. */
    private fun series(u: String, today: PassedArms.DayBars): List<PassedArms.Bar5> {
        val h = history(u, today.date)?.sessions.orEmpty().takeLast(2)
        return h.flatMap { PassedArms.bars5(it) } + PassedArms.bars5(today, today.lastRow)
    }

    /**
     * 16 Oct (R73 review): the longer series Supertrend (10, 3) reads for R51's filter: every earlier session already prefetched
     * (about 11, from [prefetchNow]'s 16 days; in memory, no extra read) and today's. The research's Supertrend ran on one
     * continuous series, so seeding it from 2 sessions could differ near its start; Donchian's own signals still use [series].
     */
    private fun stSeries(u: String, today: PassedArms.DayBars): List<PassedArms.Bar5> =
        history(u, today.date)?.sessions.orEmpty().flatMap { PassedArms.bars5(it) } + PassedArms.bars5(today, today.lastRow)

    /** The five indices' continuous 5-minute series this pass (an index unread is left out: for breadth it is not breaking). */
    private fun seriesAll(today: LocalDate, reads: Reads): Map<String, List<PassedArms.Bar5>> =
        BreadthDonchianArm.UNDERLYINGS.mapNotNull { u -> todayBars(u, today, reads)?.let { u to series(u, it) } }.toMap()

    private suspend fun donchian(b: Book, now: LocalDateTime, reads: Reads): Boolean {
        val src = DonchianArm.SOURCE
        val today = now.toLocalDate()
        val all = seriesAll(today, reads)
        // 16 Oct (R72): each of its breaks labelled with whether breadth confirmed it (Boss's comparison; never changes a trade).
        val confirmed = runCatching { BreadthDonchianArm.signals(all, today).map { Triple(it.underlying, it.bar.b, it.side) }.toSet() }.getOrNull()
        var changed = false
        for (u in DonchianArm.UNDERLYINGS) {
            val s = all[u] ?: continue
            // 16 Oct (R73 T7, Boss): the Supertrend filter is R51's only (R73 found it does not help R72's breadth breaks), so it
            // is passed here and not inside [breaks]. Its series is built only when the filter is on (memory only).
            val st = if (b.stOn) todayBars(u, today, reads)?.let { stSeries(u, it) }.orEmpty() else null
            val gate: ((DonchianArm.Signal) -> Boolean?)? = st?.let { ser -> { x: DonchianArm.Signal -> DonchianArm.supertrendAgrees(ser, x) } }
            changed = breaks(b, src, u, DonchianArm.signals(u, s, today), now, gate) { x -> confirmed?.let { Triple(u, x.bar.b, x.side) in it } } || changed
        }
        return changed
    }

    /**
     * R72 Breadth Donchian ([BreadthDonchianArm]): the same five series Donchian reads (no extra read), its breadth signals,
     * then Donchian's own taking ([breaks]) in its own rows: its caps count only its own trades.
     */
    private suspend fun breadth(b: Book, now: LocalDateTime, reads: Reads): Boolean {
        val src = BreadthDonchianArm.SOURCE
        val today = now.toLocalDate()
        val sigs = BreadthDonchianArm.signals(seriesAll(today, reads), today).groupBy { it.underlying }
        var changed = false
        for (u in BreadthDonchianArm.UNDERLYINGS) {
            if (reads.bars[u] == null) continue
            changed = breaks(b, src, u, sigs[u].orEmpty(), now) || changed
        }
        return changed
    }

    /**
     * Donchian's taking of [sigs] on [u] for [src] (R51's own, and R72's): its expiry day trades nothing; each break decided once;
     * the 2-minute fill slack, one position a side and 2 a side a day in [src]'s own rows; the buy. [confirm]: a bought trade's
     * R72 label (R51's only). [gate]: R73 T7's Supertrend filter (R51's only, when on): a break it does not pass (false: against
     * it; null: cannot be judged) is skipped and decided, before the day's caps as the research filtered before taking.
     */
    private suspend fun breaks(b: Book, src: String, u: String, sigs: List<DonchianArm.Signal>, now: LocalDateTime,
                               gate: ((DonchianArm.Signal) -> Boolean?)? = null,
                               confirm: (DonchianArm.Signal) -> Boolean? = { null }): Boolean {
        val today = now.toLocalDate()
        val key = "$src|$u"
        var changed = false
        when (expiryToday(u, today)) {
            null -> return say(b, key, "waiting for the options list")
            true -> return say(b, key, "its expiry day: no trades")
            false -> Unit
        }
        // An earlier day's tries are dropped (memory only; a few a day at most).
        if (tried.size > TRIED_KEPT) tried.keys.removeAll { !it.startsWith("$today|") }
        for (s in sigs) {
            val dk = "$today|$src|$u|${s.bar.b}|${s.side}"
            if (dk in b.decided) continue
            if (now.isBefore(s.entryTime)) continue
            // 22 Oct: why the window passed, in words (tried and refused, no check in time, the feed late, the minutes corrected).
            if (!DonchianArm.mayFill(s, now)) { b.decided += dk; changed = say(b, key, DonchianArm.missedWhy(s, tried.remove(dk), looksOf(u), othersOf(src, u)), final = true) || changed; continue }
            // 16 Oct (R73 T7): with the filter on, a break is taken only when Supertrend (10, 3) agrees; one it is against, or
            // cannot judge (no earlier session in, or no direction yet), is skipped - it only ever skips (lowers risk), never
            // adds. Decided once, before the day's caps, as the research filtered the signals before taking them.
            if (gate != null) {
                val ag = gate(s)
                if (ag != true) {
                    b.decided += dk
                    if (ag == null) b.status[ST_UNJUDGED_KEY] = "$today: ${stUnjudgedOn(b.status, today) + 1} skipped, Supertrend could not judge (no earlier session in)"
                    changed = say(b, key, "${s.entryTime.toLocalTime()} ${if (s.side > 0) "call" else "put"} break skipped: " +
                        if (ag == false) "Supertrend (10, 3) is against it (R73 T7)" else ST_UNJUDGED, final = true) || changed
                    continue
                }
            }
            val open = b.positions.filter { it.arm == src && it.index == u && it.open }.map { it.side }.toSet()
            val taken = b.positions.filter { it.arm == src && it.index == u && it.day == today }.groupingBy { it.side }.eachCount()
            val skip = DonchianArm.mayTake(s, open, taken)
            if (skip != null) { b.decided += dk; changed = say(b, key, "${s.entryTime.toLocalTime()} break skipped: $skip") || changed; continue }
            val (p, why) = buy(b, src, u, s.side, listOf(s.strike), now, keys = listOf(dk), breadth = confirm(s))
            changed = true
            if (p != null) { b.decided += dk; tried.remove(dk); say(b, key, takenWords(p, "${s.entryTime.toLocalTime()} break")) }
            else { tried[dk] = why; say(b, key, "${s.entryTime.toLocalTime()} break: $why; tried again until ${s.entryTime.plusMinutes(DonchianArm.FILL_SLACK_MINUTES).toLocalTime()}") }
        }
        return changed
    }

    private suspend fun oov(b: Book, now: LocalDateTime, reads: Reads): Boolean {
        val src = OovArm.SOURCE
        val today = now.toLocalDate()
        val t = now.toLocalTime()
        if (t.isBefore(OovArm.DECIDE_AT)) return false
        var changed = false
        for (u in OovArm.UNDERLYINGS) {
            val dk = "$today|$src|$u"
            if (dk in b.decided) continue
            val key = "$src|$u"
            if (!OovArm.mayFill(t)) { b.decided += dk; changed = say(b, key, "the 09:20 decision was missed (09:20-09:22 only)") || changed; continue }
            val d = todayBars(u, today, reads) ?: continue
            when (expiryToday(u, today)) {
                null -> { changed = say(b, key, "waiting for the options list") || changed; continue }
                true -> { b.decided += dk; changed = say(b, key, "its expiry day: no trade") || changed; continue }
                false -> Unit
            }
            val prev = history(u, today)?.sessions?.lastOrNull() ?: run { changed = say(b, key, "waiting for the previous session's bars") || changed; null } ?: continue
            val first = d.through(4)
            if (first.lastRow < 4) continue
            val side = OovArm.signal(first, OovArm.valueArea(prev))
            if (side == 0) { b.decided += dk; changed = say(b, key, "opened inside (or at) yesterday's value area: no trade today") || changed; continue }
            val (p, why) = buy(b, src, u, side, listOf(OovArm.strike(u, side, first)), now, keys = listOf(dk))
            changed = true
            if (p != null) { b.decided += dk; say(b, key, takenWords(p, "opened outside value")) }
            else say(b, key, "$why; tried again until 09:22")
        }
        return changed
    }

    /** Solo 2.0's context inputs for [u] at the signal row (what the phone has; the rest NaN for the model's medians). */
    private fun context(u: String, today: LocalDate, now: LocalDateTime, reads: Reads): Solo2.Context {
        val h = history(u, today)
        val prevClose = h?.sessions?.lastOrNull()?.lastClose ?: h?.daily?.lastOrNull()?.close ?: Double.NaN
        val ranges = h?.sessions.orEmpty().takeLast(10).map { it.high - it.low }
        val atr = h?.daily?.let { ShadowRules.atr14(it, today) } ?: Double.NaN
        val vixDay = reads.vix?.let { PassedArms.DayBars.of(today, it).c }
        val vixPrev = history(VIX, today)?.let { it.sessions.lastOrNull()?.lastClose ?: it.daily.lastOrNull()?.close } ?: Double.NaN
        val expiries = listed(u).map { it.expiry }.distinct()
        val dte = Solo2.dte(today, expiries, { d -> runCatching { Market.isTradingDay(d) }.getOrDefault(true) })
        val chain = runCatching {
            val snap = MemoryChain.read(u)
            val spot = snap.spot ?: return@runCatching null
            val exp = snap.expiry ?: return@runCatching null
            val years = java.time.Duration.between(now, exp.atTime(15, 30)).toMinutes() / (365.0 * 24 * 60)
            Solo2.chainContext(spot, PassedArms.step(u), snap.legs.map { Solo2.Leg(it.strike, it.right == "CE", it.ltp, it.oi) }, years)
        }.getOrNull()
        return Solo2.Context(u, prevClose, ranges, atr, vixDay, vixPrev, dte, chain?.pcr5 ?: Double.NaN, chain?.ivAtm ?: Double.NaN, chain?.ivSkew ?: Double.NaN)
    }

    private suspend fun solo2(b: Book, now: LocalDateTime, reads: Reads): Boolean {
        val src = Solo2.SOURCE
        val m = model ?: return say(b, "$src|model", "its model is not loaded" + (modelFailed?.let { " ($it)" } ?: " yet"))
        val today = now.toLocalDate()
        val dayKey = "$today|$src"
        if (dayKey in b.decided) return false
        // One position at a time (one trade a day, then nothing more).
        if (b.positions.any { it.arm == src && it.open }) return false
        var changed = false
        val cands = ArrayList<Pair<Solo2.Candidate, PassedArms.DayBars>>()
        for (u in Solo2.UNDERLYINGS) {
            val d = todayBars(u, today, reads) ?: continue
            when (expiryToday(u, today)) {
                null -> { changed = say(b, "$src|$u", "waiting for the options list") || changed; continue }
                true -> { changed = say(b, "$src|$u", "its expiry day: never a candidate") || changed; continue }
                false -> Unit
            }
            val h = history(u, today)
            Solo2.s3(u, d.through(4), h?.sessions?.lastOrNull()?.let { OovArm.valueArea(it) })?.let { cands += it to d }
            Solo2.s4(u, d)?.let { cands += it to d }
            Solo2.s2Taken(Solo2.s2(DonchianArm.signals(u, series(u, d), today))).forEach { cands += it to d }
            val atr = h?.daily?.let { ShadowRules.atr14(it, today) }
            val ones = reads.bars[u].orEmpty()
            Solo2.s1(u, SoloMidday.signal(u, ones, atr, now))?.let { cands += it to d }
        }
        // Only the candidates whose entry minute is now (or up to 2 minutes ago): earlier ones were decided when they came.
        val live = cands.filter { (c, _) -> val e = c.entryTime(today); !now.isBefore(e) && now.isBefore(e.plusMinutes(3)) && "$today|$src|${c.key}" !in b.decided }
        if (live.isEmpty()) return changed
        // Each candidate's 29 inputs are kept with its score: the decision's words (15 Oct: "why", explanation only) come from them.
        val scored = live.map { (c, d) ->
            val x = runCatching { Solo2.features(d, c, context(c.underlying, today, now, reads)) }.getOrNull()
            Triple(Solo2.Scored(c, x?.let { runCatching { m.score(it) }.getOrNull() } ?: Double.NaN), d, x)
        }
        for ((sc, _, x) in scored.sortedWith(compareBy<Triple<Solo2.Scored, PassedArms.DayBars, DoubleArray?>> { it.first.c.entryRow }.thenByDescending { it.first.score })) {
            val c = sc.c
            val ck = "$today|$src|${c.key}"
            val words = "%s %s %s %.4f".format(Locale.ENGLISH, c.setup.key, c.underlying, if (c.side > 0) "call" else "put", sc.score)
            // Why, in words (memory only, from the numbers in hand: no disk, network or settings read here).
            // (Guarded: the words can never stop or change a decision.)
            val because = runCatching { Solo2.reason(x, m) }.getOrDefault("").let { if (it.isEmpty()) "" else " · $it" }
            if (!sc.score.isFinite() || !m.take(sc.score)) {
                b.decided += ck; b.scores += "$today|${c.key.replace('|', ':')}|$words: below ${"%.4f".format(Locale.ENGLISH, m.threshold)}, skipped$because"; changed = true
                continue
            }
            val (p, why) = buy(b, src, c.underlying, c.side, c.strikes, now, c.setup.key, c.index, c.atr, keys = listOf(ck, dayKey))
            changed = true
            if (p != null) {
                b.decided += ck; b.decided += dayKey
                b.scores += "$today|${c.key.replace('|', ':')}|$words: ${if (p.shadow == null) "bought" else "would-be"} ${p.symbol}$because"
                say(b, src, takenWords(p, Solo2.why(c, sc.score, m)), final = true)
                return true
            }
            // R57 takes the day's first passing candidate and nothing more that day: one that cannot be bought ends the day too.
            b.decided += ck; b.decided += dayKey
            b.scores += "$today|${c.key.replace('|', ':')}|$words: passed, not bought ($why)$because"
            say(b, src, "${c.setup.key} ${c.underlying} passed but could not be bought: $why; nothing more today (one pick a day)", final = true)
            return true
        }
        return changed
    }

    // ---- exits ---------------------------------------------------------------------------------------------------------

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /**
     * Each open trade's exit, when due. True when [b] changed. Each trade in its own try (16 Oct): one that fails is said
     * (its status and Diag, once per reason) and the others' exits still run - never one failure stopping every exit.
     */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        val stopped = stoppedToday()
        if (sharedSoldElsewhere(b, now)) changed = true
        for ((i, p) in b.positions.withIndex().toList()) {
            if (!p.open) continue
            try {
                if (exitOne(b, i, p, now, stopped)) changed = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val k = "${p.arm}|exit"
                val w = "exit check failed on ${p.symbol}: ${e.javaClass.simpleName}; tried again next look"
                if (!b.status.sameToday(k, w)) { b.status[k] = w; note(b, w); changed = true }
            }
        }
        return changed
    }

    /** The stream's last price counts as the price now for this long (ms). */
    private const val FRESH_MS = 15_000L

    /** [p]'s exit (at [i] in [b]), when due. True when [b] changed. */
    private suspend fun exitOne(b: Book, i: Int, p: Pos, now: LocalDateTime, stopped: Boolean): Boolean {
        val c = Paper.contractOf(p.symbol)
        if (c == null) {
            // The paper book no longer knows the contract (a reset): nothing is held there, so the trade is closed at its entry.
            val done = p.copy(exit = p.entry, exitTime = now, why = "closed_elsewhere")
            b.positions[i] = done; closed(b, done); return true
        }
        // Gone from the paper book without this host selling it (the loss limit, Boss, a square-off): closed there. (An option
        // two arms hold is settled before this, by [sharedSoldElsewhere]; a trade alone on its option is judged here as before.)
        // A would-be trade (19 Oct) holds nothing on the account: only its own exits close it.
        val held = if (p.shadow != null) Int.MAX_VALUE else Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity }
        if (held <= 0) {
            // 16 Oct (R72 review): a sale another trade's record already took wholly (another arm's own exit) is never this one's.
            val took = taken(b)
            val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) &&
                (took[it.orderId] ?: 0) < it.quantity }
            // 16 Oct (R72): one sale may have closed R51's and R72's lots of the same option together: each books only its share.
            val share = t?.let { (p.qty.toDouble() / it.quantity.coerceAtLeast(1)).coerceAtMost(1.0) } ?: 0.0
            val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: now, why = "closed_elsewhere",
                charges = p.charges + (t?.charges?.toDouble() ?: 0.0) * share,
                soldBy = t?.let { SharedSales.write(mapOf(it.orderId to minOf(p.qty, it.quantity - (took[it.orderId] ?: 0)))) })
            b.positions[i] = done; closed(b, done); return true
        }
        val overnight = p.day.isBefore(now.toLocalDate())
        val bars = if (!overnight) runCatching { minutes(c.feedKey, now) }.getOrNull().orEmpty() else emptyList()
        // The stream's price only while fresh (16 Oct: a tick left from a stream that stopped following the option is not
        // the price now); else the last closed minute, else a quote.
        val ltp = runCatching { Paper.bookNow(c)?.takeIf { it.ageMs <= FRESH_MS }?.last }.getOrNull()?.takeIf { it > 0 } ?: bars.lastOrNull()?.close
            ?: if (!overnight) runCatching { Paper.quote(c)?.ltp }.getOrNull()?.takeIf { it > 0 } else null
        ltp?.let { b.marks[p.symbol] = it }
        val known = b.locks[lockKey(p)]?.split('|')?.let { runCatching { LocalDateTime.parse(it[0]) to it[1].toDouble() }.getOrNull() }
        // 20 Oct, review: the walk's own exit (its kind and price), so a stop or lock is never booked better than it ([sell]).
        var walked: PassedArms.Exit? = null
        val why: String? = when {
            overnight -> "overnight"
            p.setup == Solo2.Setup.S1.key -> {
                val ones = runCatching { minutes(upKey(p.index), now) }.getOrNull().orEmpty()
                val w = SoloMidday.walk(SoloMidday.Open(p.index, p.side, p.idx ?: Double.NaN, p.atr ?: Double.NaN, p.entryMinute), ones, now)
                // The index unread (or its levels unknown): the premium backstop, -30% (the Solo layer's stop), so it never runs unwatched.
                w.exit ?: (if (ones.isEmpty() || p.idx == null || p.atr == null)
                    PassedArms.exitOnBars(p.entry, p.entryTime.withSecond(0).withNano(0), bars, ltp, now, S1_BACKSTOP).exit?.kind?.let { "backstop_" + it.why } else null)
                    // R64's lock on S1's premium, beside its index exits (A4: S1 keeps them; its own premium stop is none).
                    ?: if (p.lock) PassedArms.exitOnBars(p.entry, p.entryTime.withSecond(0).withNano(0), bars, ltp, now, exitsOf(p), known, breakeven(p)).also { lw ->
                        lw.lock?.let { lv -> if (known == null || lv > known.second) b.locks[lockKey(p)] = "$now|$lv" }
                        walked = lw.exit
                    }.exit?.kind?.why else null
            }
            else -> PassedArms.exitOnBars(p.entry, p.entryMinute, bars, ltp, now, exitsOf(p), known, if (p.lock) breakeven(p) else null).also { w ->
                w.lock?.let { lv -> if (known == null || lv > known.second) b.locks[lockKey(p)] = "$now|$lv" }
                walked = w.exit
            }.exit?.kind?.why
        } ?: if (stopped) "operator_stop" else null
        // 18 Oct: the stop or lock this trade's exits rest on now, for its live trade's Zerodha stop ([ArmLive]; memory only).
        if (p.shadow == null) runCatching { ArmLive.level(mirrorRef(p), listOfNotNull(premiumStop(p), b.locks[lockKey(p)]?.substringAfter('|')?.toDoubleOrNull()).maxOrNull()) }
        if (why == null) return false
        val walkPx = walked?.takeIf { it.kind.why == why }?.price
        val next = if (p.shadow != null) sellWouldBe(b, p, why, ltp, overnight, walkPx) else sell(b, p, c, why, ltp, walkPx)
        if (next != p) { b.positions[i] = next; return true }
        return false
    }

    /** What every trade in [b] took of each SELL fill, by order id ([Pos.soldBy]; memory only). */
    private fun taken(b: Book): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (p in b.positions) for ((id, q) in SharedSales.read(p.soldBy)) out[id] = (out[id] ?: 0) + q
        return out
    }

    /**
     * 16 Oct (R72 review): an option two open trades here hold (R51 Donchian 20's and R72 Breadth Donchian's lots, [mayShare]) that
     * the paper account now holds less of than they add up to was partly or wholly sold elsewhere (Boss, the loss limit, a
     * square-off): the trades it closed are booked "closed_elsewhere" OLDEST FIRST, each at the sales it took and with its share
     * of their charges ([SharedSales]: each sale's charges booked once; another arm's own exit never taken for a sale elsewhere).
     * A sale of only part of the oldest trade closes nothing (as for a trade alone). Memory only (the paper book in hand), no
     * order. True when [b] changed.
     */
    private fun sharedSoldElsewhere(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        val groups = b.positions.withIndex().filter { it.value.open && it.value.shadow == null }.groupBy { it.value.symbol }.filterValues { it.size > 1 }
        if (groups.isEmpty()) return false
        val state = Paper.state
        for ((symbol, rows) in groups) {
            try {
                val held = state.positions.filter { it.symbol == symbol }.sumOf { it.quantity }
                if (held >= rows.sumOf { it.value.qty }) continue
                val took = taken(b)
                val sales = state.trades.filter { it.symbol == symbol && it.action == "SELL" }.map {
                    SharedSales.Sale(it.orderId, it.quantity, it.quantity - (took[it.orderId] ?: 0), it.price.toDouble(), it.charges.toDouble(), it.timestamp)
                }
                for (c in SharedSales.closedElsewhere(rows.map { SharedSales.Open(it.index, it.value.qty, it.value.entry, it.value.entryTime) }, held, sales)) {
                    val p = b.positions[c.key]
                    val done = p.copy(exit = c.price, exitTime = c.at ?: now, why = "closed_elsewhere", charges = p.charges + c.charges,
                        soldBy = SharedSales.write(c.took))
                    b.positions[c.key] = done; closed(b, done); changed = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val w = "closed-elsewhere check failed on $symbol: ${e.javaClass.simpleName}; tried again next look"
                val k = "${rows.first().value.arm}|exit"
                if (!b.status.sameToday(k, w)) { b.status[k] = w; note(b, w); changed = true }
            }
        }
        return changed
    }

    /** The walk's exit kind named [why] (null: an exit the walk did not decide, e.g. overnight or Boss's stop). */
    private fun kindOf(why: String): PassedArms.ExitKind? = PassedArms.ExitKind.values().firstOrNull { it.why == why }

    /** Sells all of [p] at market on paper (a target is booked at the target at most; a stop or lock never above its walk's price). */
    private suspend fun sell(b: Book, p: Pos, c: Paper.Contract, why: String, ltp: Double?, walkPx: Double? = null): Pos {
        val lots = (p.qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        val r = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) {
            r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            val k = "${p.arm}|exit"
            if (!b.status.sameToday(k, "exit_retry ${p.symbol}")) { b.status[k] = "exit_retry ${p.symbol}"; note(b, "${p.symbol}: the $why exit did not fill (${r.message}); tried again next pass") }
            return p
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${label(p.arm)} · exit") } }
        runCatching { Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", "${label(p.arm)} · exit", r.orderId) }
        val target = PassedArms.targetLevel(p.entry, exitsOf(p))
        val booked = kindOf(why)?.let { PassedArms.booked(it, fill.price, target, walkPx) } ?: fill.price
        val done = p.copy(exit = booked, exitTime = now0(), why = why, charges = p.charges + chargesOf(r.orderId), exitRef = ltp,
            paperFill = fill.price.takeIf { kotlin.math.abs(it - booked) > 1e-9 }, soldBy = r.orderId?.let { SharedSales.write(mapOf(it to p.qty)) })
        forget(b, p)
        closed(b, done)
        return done
    }

    /**
     * A would-be trade's exit (19 Oct, [Pos.shadow]): nothing is sold - booked at the price seen ([ltp]; a target at the target at
     * most, as [sell] books it), with the charges a real round trip would have paid. No price: tried again next look (an
     * overnight one closes at its last mark, else its entry).
     */
    private fun sellWouldBe(b: Book, p: Pos, why: String, ltp: Double?, overnight: Boolean, walkPx: Double? = null): Pos {
        // A seller gets the bid: the stream's fresh bid when it has one, else the price seen.
        val bid = runCatching { Paper.contractOf(p.symbol)?.let { Paper.bookNow(it) }?.takeIf { it.ageMs <= FRESH_MS }?.bid }.getOrNull()?.takeIf { it > 0 }
        val at = (if (ltp != null) bid?.let { minOf(it, ltp) } ?: ltp else null) ?: if (overnight) (b.marks[p.symbol] ?: p.entry) else return p
        val target = PassedArms.targetLevel(p.entry, exitsOf(p))
        val booked = kindOf(why)?.let { PassedArms.booked(it, at, target, walkPx) } ?: at
        val charges = runCatching {
            com.optionslab.engine.sandbox.SandboxCosts.charge("BUY", java.math.BigDecimal(p.entry), p.qty).toDouble() +
                com.optionslab.engine.sandbox.SandboxCosts.charge("SELL", java.math.BigDecimal(booked), p.qty).toDouble()
        }.getOrDefault(0.0)
        val done = p.copy(exit = booked, exitTime = now0(), why = why, charges = charges, exitRef = ltp)
        closed(b, done)
        return done
    }

    /**
     * 16 Oct (R72): the book's key of [p]'s earned lock - its option's symbol, as before, or "arm|symbol" for R72 Breadth Donchian's,
     * which may hold the very option R51 Donchian 20 holds (the same break, [mayShare]): each trade keeps its own lock.
     */
    private fun lockKey(p: Pos): String = if (p.shadow != null) "${p.shadow}|${p.arm}|${p.symbol}"
        else if (p.arm == BreadthDonchianArm.SOURCE) "${p.arm}|${p.symbol}" else p.symbol

    /**
     * 16 Oct (R72): R51 Donchian 20 and R72 Breadth Donchian may hold the same option at once - most of R72's breaks are R51's own,
     * and the two run side by side to be compared on the same days. Each holds its own lot on paper and sells only it; their
     * locks are kept apart ([lockKey]). Any other two of these arms: never the same option.
     */
    private fun mayShare(a: String, b: String): Boolean = a != b && setOf(a, b) == setOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE)

    /** [p]'s lock and (when no other open trade here holds its option) its last price, forgotten. */
    private fun forget(b: Book, p: Pos) {
        b.locks.remove(lockKey(p))
        if (b.positions.none { it.open && it.symbol == p.symbol && !same(it, p) }) b.marks.remove(p.symbol)
    }

    private fun closed(b: Book, p: Pos) {
        // 16 Oct: on every way out (closed elsewhere too), so a later trade on the same option never reads this one's lock.
        forget(b, p)
        if (p.shadow != null) {
            // 19 Oct: a would-be trade closed - its record only (never live, no notice).
            b.status["${p.arm}|${p.index}"] = "would-be ${p.symbol} closed: ${p.why}"
            note(b, "%s: would-be %s closed (%s) @ %s, %s after estimated charges (%s)".format(Locale.ENGLISH, label(p.arm), p.symbol, p.why,
                px(p.exit ?: 0.0), rs(p.net ?: 0.0), shadowWords(p)))
            return
        }
        // 18 Oct: its live trade, if any, is sold on the next look ([ArmLive]; memory only).
        runCatching { ArmLive.closed(mirrorRef(p), p.why ?: "exit") }
        b.status["${p.arm}|${p.index}"] = "closed ${p.symbol}: ${p.why}"
        note(b, "%s: %s sold (%s) @ %s, %s after charges · paper".format(Locale.ENGLISH, label(p.arm), p.symbol, p.why, px(p.exit ?: 0.0), rs(p.net ?: 0.0)))
        runCatching { Notifier.post(app, NOTICE + 40 + SOURCES.indexOf(p.arm) * 5 + ALL_INDICES.indexOf(p.index).coerceAtLeast(0), Notifier.SELL,
            "${label(p.arm)} sold ${p.symbol} · Paper",
            "Sold (%s) @ %s: %s after charges. %s.".format(Locale.ENGLISH, p.why, px(p.exit ?: 0.0), rs(p.net ?: 0.0), PassedArms.PASSED), "almanac") }
    }

    // ---- the safety net (16 Oct) ----------------------------------------------------------------------------------------

    /** The arm whose owner label is [owner] ("R51 Donchian 20"), or null. */
    fun armOf(owner: String): String? = SOURCES.firstOrNull { label(it) == owner }

    /**
     * Takes back [o], a paper long one of these arms opened that has no open record here ([OrphanWatch] found it unwatched):
     * booked as its arm's trade from its real fill and fill time, so its stop, target and time exit run from the next look.
     * Said plainly to Boss (a notice, the log, Diag). The words,
     * or null when it is not taken (not these arms', already held here, being bought now, or the contract unknown).
     */
    suspend fun adopt(o: com.optionslab.engine.orb.OrphanAudit.Orphan): String? {
        if (!ready() || com.optionslab.app.BuildConfig.GOLD) return null
        val arm = armOf(o.owner) ?: return null
        if (o.symbol in inFlight) return null
        val c = Paper.contractOf(o.symbol) ?: return null
        return lock.withLock {
            val b = book()
            // 16 Oct (R72 review): an R72 lot with no record here that shares its option with an open R51 trade is not taken back
            // (nor even found: R51's open record keeps the symbol "watched", and two owners' buys read as no owner in OrphanAudit).
            // Left so on purpose, paper only: it needs a crash between the fill and [record] (which books the fill at once), the
            // option stays watched with R51's stop, target and time exit while R51 holds it, and the paper account's own 15:15 MIS
            // square-off sells whatever is left. Splitting one symbol's quantity between owners would change the net for every arm.
            if (b.positions.any { it.open && it.shadow == null && it.symbol == o.symbol }) return@withLock null
            val now = now0()
            val p = Pos(arm, c.underlying, o.symbol, c.right.name, o.qty, o.fill, o.at ?: now, o.orderId, charges = chargesOf(o.orderId),
                adopted = ADOPT_FILL, lock = b.lockOn, l25 = b.lockOn && b.l25On, st = arm == DonchianArm.SOURCE && b.stOn)
            b.positions += p
            b.marks[p.symbol] = p.entry
            val words = "${label(arm)}: ${p.symbol} ${p.qty} was held on paper with no record here, so its exits were not running; " +
                "taken back as its trade (filled @ ${px(p.entry)} at ${p.entryTime.toLocalTime()})" +
                ". ${exitWords(p)}"
            b.status["$arm|${p.index}"] = "taken back ${p.symbol} (safety net)"
            note(b, words)
            save(b)
            runCatching { Notifier.post(app, NOTICE + 60 + SOURCES.indexOf(arm) * 5 + ALL_INDICES.indexOf(p.index).coerceAtLeast(0), Notifier.SELL,
                "${label(arm)}: ${p.symbol} taken back · Paper", "$words. ${PassedArms.PASSED}.", "almanac") }
            words
        }
    }

    // ---- Jarvis ----------------------------------------------------------------------------------------------------------

    /** What Jarvis says ([com.optionslab.ira.PassedArmsTalk]): the arms' book read off the main thread, and the Watchlist's log. */
    suspend fun talk(topic: com.optionslab.ira.PassedArmsTalk.Topic): String = com.optionslab.ira.PassedArmsTalk.answer(topic, facts(refresh()))

    internal fun facts(v: View): com.optionslab.ira.PassedArmsTalk.Facts {
        val arms = SOURCES.map { s ->
            val c = v.closedOf(s)
            val ap = c.filter { it.index == apart(s) }
            val wb = c.filter { it.shadow != null }
            com.optionslab.ira.PassedArmsTalk.Arm(s, v.armed[s] ?: false, v.todayOf(s).map { tradeLine(it, v.marks) }, c.size, c.sumOf { it.net ?: 0.0 },
                c.count { (it.net ?: 0.0) > 0 }, ap.size, ap.sumOf { it.net ?: 0.0 }, v.forwardLine(s), v.paused[s],
                if (s == Solo2.SOURCE) v.scores else emptyList(), stOn = s == DonchianArm.SOURCE && v.stOn,
                stUnjudged = if (s == DonchianArm.SOURCE) v.stUnjudged else 0,
                live = runCatching { ArmLive.armLine(s) }.getOrNull(), recordOnly = v.recording(s),
                wouldBe = wb.size, wouldBeNet = wb.sumOf { it.net ?: 0.0 })
        }
        val w = runCatching { WatchlistHost.cards() }.getOrDefault(emptyList()).map { com.optionslab.engine.orb.Watchlist.line(it) }
        return com.optionslab.ira.PassedArmsTalk.Facts(arms, w, runCatching { WatchlistHost.on() }.getOrNull(), v.modelLoaded,
            com.optionslab.ira.PassedArmsTalk.exitsWords(v.lockOn, v.l25On), v.comparison())
    }

    /** One trade in words (its row and Jarvis). */
    fun tradeLine(p: Pos, marks: Map<String, Double>): String =
        if (p.open) "${p.symbol} ${p.qty} @ ${px(p.entry)}" + (marks[p.symbol]?.let { " · now ${px(it)}" } ?: "") + (p.setup?.let { " ($it)" } ?: "") +
            lockTag(p) + " · paper"
        else "${p.symbol} ${p.qty} @ ${px(p.entry)} → ${px(p.exit ?: 0.0)} (${p.why}) · ${rs(p.net ?: 0.0)}" + lockTag(p)

    /** The ladder a trade entered with, labelled so each record stays separable (the research exits, A4, A4 + L25). */
    private fun lockTag(p: Pos): String = (if (!p.lock) "" else if (p.l25) " · 30-60-100 lock + L25" else " · 30-60-100 lock") +
        (if (p.st) " · Supertrend filter" else "") + (if (p.shadow != null) " · ${shadowWords(p)}" else "")

    /** For tests: the book forgotten (its file goes with the test's directory), and the day's memory. */
    internal fun wipe() {
        synchronized(writeLock) { pendingBytes.set(null) }
        cache = null; _view.value = View(); hist.clear(); histTried = null; lastRead.clear(); looks.clear(); tried.clear()
    }

    /** TEST ONLY: closed trades added to the book as if traded (the forward bars' tests). Debug builds only. */
    internal suspend fun addClosedForTest(list: List<Pos>) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "a test seam" }
        lock.withLock { val b = book(); b.positions += list; save(b) }
    }

    /**
     * TEST ONLY: [p] booked as an open trade, as a book carried over from before 19 Oct may hold one (R72 beside R51 on the same
     * option, each with its own paper lot). Debug builds only.
     */
    internal suspend fun addOpenForTest(p: Pos) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "a test seam" }
        lock.withLock { val b = book(); b.positions += p; b.marks[p.symbol] = p.entry; save(b) }
    }

    /** For tests: the model forgotten (read again on the next pass). */
    internal fun forgetModel() { model = null; modelFailed = null }
}
