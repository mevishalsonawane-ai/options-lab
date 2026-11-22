package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.BreadthDonchianArm
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.StepTrail
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.engine.orb.R51Picker
import com.optionslab.engine.orb.R72Picker
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.engine.orb.SharedSales
import com.optionslab.engine.orb.Solo2
import com.optionslab.engine.orb.W20Rule
import com.optionslab.engine.orb.SoloMidday
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
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

    /**
     * A paper trade's reference for its live path ([ArmLive]): its arm and its paper order (or its entry time). 22 Nov 2026 (B6 review,
     * item 6: two indices' would-be trades of one arm on one pass share that pass's time, so the second's live copy was refused as a
     * duplicate of the first): a would-be trade (no paper order) is told apart by its own id ([Pos.wb]); one saved before has none and
     * keeps its old reference, so a live copy already asked with it still follows it.
     */
    internal fun mirrorRef(p: Pos): String = "passed:${p.arm}:${p.orderId ?: p.wb ?: p.entryTime}"

    /** Today's and the open paper trades, each with what it still holds (0 once closed); null until the book is read. Memory only. */
    private fun mirrorLegs(): List<ArmLive.PaperLeg>? {
        if (cache == null) return null
        val v = _view.value
        // (19 Nov 2026, audit batch 2: a would-be trade whose live copy was asked is that copy's paper trade too: [Pos.copied].)
        return (v.open + v.today.filter { !it.open }).filter { it.mirrored }.map { ArmLive.PaperLeg(mirrorRef(it), it.arm, if (it.open) it.qty else 0, fixed = it.fx, symbol = it.symbol, lock = it.fx && it.sl) }
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
    /** 1 Nov (Boss: "Make the change the arms"): R140's wide-day skip switched on once for R72 Breadth Donchian's new breaks ([setWideSkip]). */
    internal const val WIDE_ON = "r72_wide_skip_2026-11-01"
    /** 1 Nov (Boss: "Make the change the arms"): R142's 09:25 no-progress exit switched on once for R52's new trades ([setNoProgress]). */
    internal const val NP5_ON = "r52_no_progress_2026-11-01"
    /**
     * 6 Nov (Boss: "I am the owner, i want you to skip the rule, and i want live"): R151's R72 and R51 pickers switched on once, ACTING
     * on paper and live ([setPicker], [R72Picker], [R51Picker]): a break scored under the arm's threshold is skipped (a would-be trade,
     * [SHADOW_PICK]). Both failed their gates: Boss's fourth exception (CLAUDE.md), labelled "failed gates, Boss's exception".
     */
    internal const val PICK_ON = "r72_r51_pickers_live_2026-11-06"
    /** 6 Nov (Boss: "Do whatever you recommend", on R51): R150 B's W20 on R51 switched on once as a SHADOW LOG ([setW20], [W20Rule]). */
    internal const val W20_ON = "r51_w20_shadow_2026-11-06"
    /**
     * 19 Oct (Boss's choice: "Yes, keep only breadth donchain"): R51 Donchian 20 set to record only once, when it was on
     * ([Book.recordOnly]); R72 Breadth Donchian stays on. Logged as Boss's ask; one tap undoes it (back on).
     */
    internal const val R51_RECORD = "r51_record_only_2026-10-19"
    /**
     * 30 Oct (Boss: "dont keep any in paper only or record only"): every arm left "record only" is switched ON once (R51 Donchian 20),
     * logged in the change timeline as Boss's ask (one tap undoes it). From now an arm is on (paper, and live through the live gates)
     * or off (shadow: would-be trades, nothing bought).
     */
    internal const val RECORD_ON_1030 = "record_only_on_2026-10-30"

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

    /** TEST SEAM: a picker threshold in place of R72's and R51's own (6 Nov); null in the app. Its setter throws in a release build. */
    @Volatile internal var testPickThreshold: Double? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST SEAM: the book's bytes read this way instead of from the vault (a throw: a read that failed). Null in the app. */
    @Volatile internal var testRead: (() -> ByteArray?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /**
     * Reads of the book that failed in a row ([book]). A failure that does not clear (the key itself gone, not a moment's hiccup)
     * would leave what the arms hold with no exits and no safety net for good: after [READ_FAILS_BEFORE_ASIDE] in a row the book
     * is set aside as before, and the paper account's positions are taken back by the orphan watch with their arm's exits.
     */
    @Volatile private var readFails = 0
    internal const val READ_FAILS_BEFORE_ASIDE = 5

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
        /**
         * 1 Nov (R142 NP5-52, Boss: "Make the change the arms"): an R52 trade that entered with the 09:25 no-progress exit
         * ([OovArm.NO_PROGRESS]). Set only when it could not be copied live then (a live copy keeps the plain exits; the early
         * exit is only logged); a trade keeps the exits it entered with.
         */
        val np5: Boolean = false,
        /**
         * 6 Nov (R151 Amendment C / E; failed gates, Boss's exception): an R72 / R51 trade's picker score when its break was
         * scored ([R72Picker]); null: not scored (the picker off, its history not in yet, another arm). A label only: never changes a trade.
         */
        val pick: Double? = null,
        /**
         * 6 Nov (R150 B's W20, a shadow log only): an R51 Donchian 20 trade's label - true: W20 would have skipped its break (a 3+-touch
         * 5-minute level within 0.25% ahead), false: kept; null: not labelled. A label only: never changes a trade.
         */
        val w20: Boolean? = null,
        /**
         * 15 Nov (Boss's exception, failed gates; [com.optionslab.engine.orb.StepTrail]): an R51 / R72 trade that entered with the breakeven
         * rule ([StepTrailExits]). A trade keeps the rule it entered with (persisted); its breakeven is kept with its lock ([Book.locks]).
         */
        val fx: Boolean = false,
        /** 15 Nov: the contract's lot size at the entry, for the breakeven rule's per-lot levels (null: an older trade). */
        val lot: Int? = null,
        /**
         * 16 Nov (the fourth version, Boss's exception): with [fx], the trade also has R51 / R72's ₹500 step lock from +₹1,000 a lot
         * ([StepTrail.Lock.L500]); false: V4 alone, as it entered. Persisted; its lock is kept with its lock ([Book.locks]) as the breakeven is.
         */
        val sl: Boolean = false,
        /**
         * 19 Nov 2026 (audit batch 2: a live trade was silently skipped because a paper-only rule refused its paper trade): a would-be trade
         * ([shadow]) whose live copy was asked all the same - the paper account refused or could not price the entry (another arm held the
         * option on paper, the paper account guard, a paper cap or rule, no fresh paper price) while the entry could be copied live. Its live
         * copy follows it (its own exits) exactly as a real paper trade's; every live gate still applies. Persisted.
         */
        val copied: Boolean = false,
        /**
         * 22 Nov 2026 (B6 review, item 6): a would-be trade's own id (a random UUID made with it; persisted), its live copy's reference
         * ([mirrorRef]) in place of a paper order id it never had. Null: a real paper trade, or a would-be one saved before.
         */
        val wb: String? = null,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val side: Int get() = if (right == "CE") 1 else -1
        /** 19 Nov 2026 (audit batch 2): a real paper trade, or a would-be one whose live copy was asked ([copied]): it has a live path. */
        val mirrored: Boolean get() = shadow == null || copied
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
        /** 1 Nov: R140's wide-day skip on R72 Breadth Donchian's new breaks ([setWideSkip]). */
        var wideOn: Boolean = false,
        /** 1 Nov: R142's 09:25 no-progress exit on R52's new trades ([setNoProgress]). */
        var np5On: Boolean = false,
        /** 6 Nov (Boss's exception): R151's R72 and R51 pickers skip low-scored breaks on paper and live ([setPicker]). */
        var pickOn: Boolean = false,
        /** 6 Nov: R150 B's W20 labels each R51 break (shadow log only, [setW20]). */
        var w20On: Boolean = false,
        /**
         * 19 Nov 2026 (audit batch 2, coordinator's decision B2-B): a paper-only refusal (another arm holds the option on paper, the paper
         * account guard, no fresh paper price) also sends the live copy ([setRefusalLive]). Off (the default): shadow - the refusal skips
         * live as before and each "would have gone live" is logged ([REFUSAL_MARK]). On adds live entries, so it takes the PIN.
         */
        var refusalLiveOn: Boolean = false,
        /**
         * 20 Nov 2026 (audit B5, item 1; Bot settings): an R51 / R72 break whose channel reads an earlier session that does not reach the
         * last trading day ([staleWhy]) waits inside its fill window while the history is read again, and is let go when the window ends
         * - on paper and live. Off (the default): shadow - taken as before and logged "would have skipped (stale history)" ([STALE_MARK]).
         * Both ways take the PIN ([setStaleSkip]): on also lets a better history replace the one in use mid-session.
         */
        var staleOn: Boolean = false,
    ) {
        /** A copy for the entries phase (worked on outside the book's lock, then merged back). */
        fun copy(): Book = Book(HashMap(armed), ArrayList(positions), HashSet(decided), status.copyMap(), ArrayList(log), HashSet(migrated), HashMap(base),
            soloBase, HashMap(acted), HashMap(paused), ArrayList(scores), HashMap(marks), HashMap(locks), lockOn, l25On, stOn, breadthSince, HashSet(recordOnly), wideOn, np5On, pickOn, w20On,
            refusalLiveOn, staleOn)

        /**
         * [source]'s entries run this pass. 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record
         * only"): every arm runs - an arm that is on buys, one that is off keeps each signal as a would-be trade (nothing bought).
         */
        @Suppress("UNUSED_PARAMETER") fun active(source: String): Boolean = true
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
        /** 1 Nov: R140's wide-day skip is on for R72 Breadth Donchian's new breaks (paper; live in shadow). */
        val wideOn: Boolean = false,
        /** 1 Nov: R142's 09:25 no-progress exit is on for R52's new trades (paper; live in shadow). */
        val np5On: Boolean = false,
        /** 6 Nov: R151's R72 and R51 pickers skip low-scored breaks on paper and live (failed gates, Boss's exception). */
        val pickOn: Boolean = false,
        /** 6 Nov: R150 B's W20 labels each R51 break (failed gates; a shadow log only). */
        val w20On: Boolean = false,
        /** 19 Nov 2026 (audit batch 2, B2-B): a paper-only refusal also sends the live copy ([setRefusalLive]; off: shadow, logged only). */
        val refusalLiveOn: Boolean = false,
        /** 19 Nov 2026 (audit batch 2, B2-B): today's paper-only refusals that would have gone live (logged in shadow, [REFUSAL_MARK]). */
        val refusalShadowToday: Int = 0,
        /** 20 Nov 2026 (audit B5, item 1): R51 / R72 breaks on a stale history wait and are let go ([setStaleSkip]; off: shadow, logged only). */
        val staleOn: Boolean = false,
        /** 20 Nov 2026 (audit B5, item 1): today's R51 / R72 breaks the stale-history check would have held (logged in shadow, [STALE_MARK]). */
        val staleShadowToday: Int = 0,
        /** 19 Nov 2026 (audit batch 2, B2-B): the book has been read (its switches are known; the card says "reading" before). */
        val loaded: Boolean = false,
    ) {
        fun on(source: String): Boolean = armed[source] == true
        /**
         * [source] is off, so it runs in shadow: its breaks kept as would-be trades, nothing bought. 30 Oct (Boss): every arm that is
         * off records (the 19 Oct "record only" and "off" are now one: off = shadow).
         */
        fun recording(source: String): Boolean = !on(source)
        fun mode(source: String): Mode = if (on(source)) Mode.ON else if (recording(source)) Mode.RECORD else Mode.OFF
        /** 19 Oct: [source]'s closed would-be trades (record only, the twin rule, the cap: never bought), within [closedOf]. */
        fun wouldBeOf(source: String): List<Pos> = closedOf(source).filter { it.shadow != null }
        fun closedOf(source: String): List<Pos> = all.filter { it.arm == source }
        fun todayOf(source: String): List<Pos> = today.filter { it.arm == source }
        /** Today's running P&L of [source]: closed after charges, open at the last price before charges. */
        fun todayPnl(source: String): Double = todayOf(source).sumOf { p -> p.net ?: marks[p.symbol]?.let { (it - p.entry) * p.qty } ?: 0.0 }
        /** The forward test's line. */
        fun forwardLine(source: String): String {
            val c = closedOf(source).filter { it.shadow == null }         // 22 Oct: the bar counts real trades only
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
        val gen = wipes
        val b = Book()
        var unreadable = false
        // 25 Oct (app audit): a read that failed for a moment (a Keystore hiccup past readFileSteady's retries) is not a damaged
        // file. It used to set the book aside and start an empty one: every arm off and what they held forgotten by their own
        // exits, with nothing in the change timeline. Now it is thrown and nothing is cached, so the next call reads again (as
        // Strategies and TradeBook do). Only bytes that fail authentication or do not parse are set aside.
        val bytes: ByteArray? = try { (testRead?.invoke() ?: Vault.readFileSteady(file)).also { readFails = 0 } } catch (e: Vault.Tampered) {
            readFails = 0
            unreadable = true; if (file.exists()) Vault.setAside(file); null
        } catch (e: Exception) {
            // 26 Oct: a read the erase refused ([Vault.Erasing]) is not a failed read: never counted, never set aside (the
            // book is being deleted); read again after the erase.
            if (e is Vault.Erasing) throw java.io.IOException("the passed arms' book is being erased", e)
            readFails += 1
            if (readFails < READ_FAILS_BEFORE_ASIDE) {
                runCatching { Diag.record("passed-arms", "the arms' book could not be read now (${e.javaClass.simpleName}, $readFails in a row); read again on the next look") }
                throw java.io.IOException("the passed arms' book could not be read now", e)
            }
            // It does not clear: set aside as before (an empty book; the orphan watch takes back what the paper account holds).
            runCatching { Diag.record("passed-arms", "the arms' book could not be read $readFails times in a row (${e.javaClass.simpleName}): set aside; a fresh book starts") }
            readFails = 0
            unreadable = true; if (file.exists()) Vault.setAside(file); null
        }
        if (bytes != null) runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
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
            b.wideOn = o.optBoolean("r72WideSkip", false)
            b.np5On = o.optBoolean("r52NoProgress", false)
            b.pickOn = o.optBoolean("r72PickerShadow", false)
            b.w20On = o.optBoolean("r51W20Shadow", false)
            b.refusalLiveOn = o.optBoolean("refusalLive", false)
            b.staleOn = o.optBoolean("staleHistorySkip", false)
            // 19 Oct: absent in an older book (no arm record only); never beside "on" (on wins).
            o.optJSONArray("recordOnly")?.let { a -> for (i in 0 until a.length()) a.getString(i).takeIf { it in SOURCES && b.armed[it] != true }?.let { b.recordOnly += it } }
        }.onFailure { unreadable = true; if (file.exists()) Vault.setAside(file) }
        // 26 Oct: read before an erase that came meanwhile ([wipe]): not kept (as a read that failed: the next call reads again).
        if (gen != wipes) throw java.io.IOException("the passed arms' book was erased while it was read")
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
        // 1 Nov (Boss: "Make the change the arms", after R142 / R143 / R144a): R140's wide-day skip on R72 Breadth Donchian and R142's
        // 09:25 no-progress exit on R52 passed their gates and held in all six periods tested (two never seen), so under the PAPER
        // rule they act on paper at once: on once each, logged in the change timeline (one tap undoes each). Both only lower risk
        // (a skip, an earlier exit). LIVE stays in shadow: a break or trade that may be copied live is taken or kept as before and
        // the rule is only logged. Same care: never on an unreadable book, never in Gold.
        if (WIDE_ON !in b.migrated) {
            b.migrated += WIDE_ON
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD && !b.wideOn) {
                b.wideOn = true
                note(b, "${BreadthDonchianArm.LABEL}: wide-day skip turned on by the 1 Nov update, your ask: ${BreadthDonchianArm.WIDE_RULE} (paper; live in shadow)")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedWideSkipOn() }
            }
            save(b)
        }
        if (NP5_ON !in b.migrated) {
            b.migrated += NP5_ON
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD && !b.np5On) {
                b.np5On = true
                note(b, "${OovArm.LABEL}: the 09:25 no-progress exit turned on by the 1 Nov update, your ask: ${OovArm.NO_PROGRESS_RULE} (paper; live in shadow)")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedNoProgressOn() }
            }
            save(b)
        }
        // 6 Nov (Boss: "I am the owner, i want you to skip the rule, and i want live"; CLAUDE.md's fourth exception): R151's R72 and R51
        // pickers failed their gates (48.9% / 49.9% won on Apr - Oct 2026, the bar 50%) but ACT on paper and live: a break scored under
        // the arm's threshold is skipped (a would-be trade). On once, logged in the change timeline as Boss's (its Undo takes the PIN:
        // it adds live trades back). Same care: never on an unreadable book, never in Gold.
        if (PICK_ON !in b.migrated) {
            b.migrated += PICK_ON
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD && !b.pickOn) {
                b.pickOn = true
                note(b, "${R72Picker.LABEL} and ${R51Picker.LABEL}: turned on by the 6 Nov update, your ask (${R72Picker.STATUS}): ${R72Picker.RULE}; " +
                    "R51's the same at ${R51Picker.THRESHOLD}")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedPickerOn() }
            }
            save(b)
        }
        // 6 Nov (Boss: "Do whatever you recommend", on R51): R150 B's W20 lost far less than R51 in every period but failed R140's gate
        // that the arm still makes money, so it only LOGS (CLAUDE.md: a failed idea stays a note): each R51 break labelled "W20 would
        // skip / keep"; nothing is skipped or changed, paper or live. On once, logged in the change timeline (one tap undoes it).
        if (W20_ON !in b.migrated) {
            b.migrated += W20_ON
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD && !b.w20On) {
                b.w20On = true
                note(b, "${R51Picker.LINE}: turned on by the 6 Nov update, your ask (${W20Rule.STATUS}): ${W20Rule.RULE}; and each is " +
                    "scored by R151's R51 picker (would take at ${R51Picker.THRESHOLD} or more)")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedW20On() }
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
        // 30 Oct (Boss: "dont keep any in paper only or record only"): each arm still record only goes ON once. Same care: never an
        // unreadable book (Boss's choices unknown), never in Gold.
        if (RECORD_ON_1030 !in b.migrated) {
            b.migrated += RECORD_ON_1030
            if (!unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD) for (s in b.recordOnly.toList().filter { it in SOURCES }) {
                b.recordOnly -= s
                if (b.armed[s] == true) continue
                b.armed[s] = true
                // The forward bar counts from now (its would-be trades were never bought).
                val nets = b.positions.filter { it.arm == s && !it.open && it.shadow == null }.map { it.net ?: 0.0 }
                if (s == Solo2.SOURCE) SoloMidday.baseline(nets).let { x -> b.soloBase = "${x.from}|${x.peak}" } else b.base[s] = nets.size
                note(b, "${label(s)} switched ON by the 30 Oct update, your ask (\"dont keep any in paper only or record only\"): it buys on " +
                    "paper again, and live through your live gates (Live with your PIN, AI trades go live, its Trade live switch)")
                runCatching { com.optionslab.app.ira.Autopilot.passedArmSwitchedByBoss(s, label(s), research(s), true, com.optionslab.ira.AutoChange.RECORD_ONLY) }
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
            p.optString("shadow").ifEmpty { null }, p.optBoolean("np5", false), d("pick"),
            if (p.has("w20")) p.getBoolean("w20") else null, p.optBoolean("step500", false),
                p.optInt("lot500", 0).takeIf { it > 0 }, p.optBoolean("stepLock", false), p.optBoolean("copied", false),
            wb = p.optString("wb").ifEmpty { null })
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
            if (p.np5) put("np5", true)
            p.pick?.let { put("pick", it) }
            p.w20?.let { put("w20", it) }
            if (p.fx) put("step500", true)
            p.lot?.let { put("lot500", it) }
            if (p.sl) put("stepLock", true)
            if (p.copied) put("copied", true)
            p.wb?.let { put("wb", it) }
        }

    private val written = Vault.LastWrite()

    /**
     * 20 Nov 2026 (final review of audit B5): the decisions written - every one of [today]'s (its picker decisions, held breaks, logs said
     * once: never dropped, however many), then the newest of the earlier ones up to [cap] in all. Pure.
     */
    internal fun keptDecided(decided: Collection<String>, today: LocalDate?, cap: Int = 600): List<String> {
        val mine = if (today == null) emptyList() else decided.filter { it.startsWith("$today|") }.sorted()
        val rest = decided.filter { today == null || !it.startsWith("$today|") }.sorted()
        return rest.takeLast((cap - mine.size).coerceAtLeast(0)) + mine
    }

    private fun save(b: Book) {
        // 26 Oct (Erase everything): a pass that read the book before [wipe] never writes it back or shows it again; the
        // generation goes with the bytes, so a write already queued is dropped too ([flushBook]).
        val gen = wipes
        if (cache !== b) return
        val o = JSONObject()
        o.put("armed", JSONObject().apply { b.armed.forEach { (k, v) -> put(k, v) } })
        o.put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
        o.put("decided", JSONArray().apply { keptDecided(b.decided, runCatching { Market.today() }.getOrNull()).forEach { put(it) } })
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
        o.put("r72WideSkip", b.wideOn)
        o.put("r52NoProgress", b.np5On)
        o.put("r72PickerShadow", b.pickOn)
        o.put("r51W20Shadow", b.w20On)
        o.put("refusalLive", b.refusalLiveOn)
        o.put("staleHistorySkip", b.staleOn)
        o.put("recordOnly", JSONArray().apply { b.recordOnly.sorted().forEach { put(it) } })
        o.put("locks", JSONObject().apply { b.locks.filterKeys { k -> b.positions.any { it.open && (lockKey(it) == k || ownLockKey(it) == k) } }.forEach { (k, v) -> put(k, v) } })
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        // 19 Oct (order speed): written on this book's own writer thread ([writer]), never under [lock] on the caller's - an
        // entry booked here ([record]) used to wait for the vault write before the next index's entry, and the 15-second stop
        // check's exits ([manageOnly]) waited for the lock behind it. The newest book is always the last written.
        pendingBytes.set(gen to o.toString().toByteArray(Charsets.UTF_8))
        writer.kick()
        publish(b)
    }

    /** The newest book not yet on disk ([save]), with the erase generation it was saved in ([wipes]); [flushBook] writes it. */
    private val pendingBytes = java.util.concurrent.atomic.AtomicReference<Pair<Int, ByteArray>?>(null)
    private val writeLock = Any()
    private val writer = com.optionslab.ira.WriteBehind(com.optionslab.ira.WriteBehind.thread("passed-arms-writer")) { flushBook() }

    /** The newest saved book to the vault, one write at a time; a failed write is kept for the next one (said in diagnostics). */
    private fun flushBook() {
        synchronized(writeLock) {
            val pending = pendingBytes.getAndSet(null) ?: return
            // 26 Oct: saved before an erase ([wipe] bumps the generation under this lock): never written after it.
            if (pending.first != wipes) return
            val bytes = pending.second
            try {
                written.write(file, bytes)
            } catch (e: Exception) {
                pendingBytes.compareAndSet(null, pending)
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
        // 26 Oct: an old book (read before an erase) is never shown again.
        if (cache !== b) return
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        val closed = b.positions.filter { !it.open }
        _view.value = View(b.armed.toMap(), b.status.shown({ k, _ -> k == ST_UNJUDGED_KEY }), b.positions.filter { it.open }, closed.takeLast(30), b.positions.filter { it.day == today },
            b.log.takeLast(40), closed, b.marks.toMap(), b.paused.toMap(), b.scores.filter { it.startsWith("$today|") }.map { it.substringAfter('|').substringAfter('|') },
            model != null, b.base.toMap(), soloBase(b), b.lockOn, b.l25On, b.stOn, b.breadthSince, stUnjudgedOn(b.status, today), b.recordOnly.toSet(),
            wideOn = b.wideOn, np5On = b.np5On, pickOn = b.pickOn, w20On = b.w20On, refusalLiveOn = b.refusalLiveOn,
            refusalShadowToday = b.log.count { it.startsWith(today.toString()) && REFUSAL_MARK in it }, staleOn = b.staleOn,
            staleShadowToday = b.log.count { it.startsWith(today.toString()) && STALE_MARK in it }, loaded = true)
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
        // 15 Nov: the stop that applies - the arm's own stop (the rule has no entry stop since V4), raised to the lock or the stop rule held ([held]).
        val stop = listOfNotNull(PassedArms.effectiveStop(p.entry, x), held).maxOrNull()
        val sq = x.squareOff
        val at = PassedArms.timeExitAt(p.entryMinute, x)?.takeIf { it.toLocalDate() == p.day && it.toLocalTime().isBefore(sq) }?.toLocalTime() ?: sq
        // R66's L25 first: "+5% locked once +25%" before it is earned, "lock held" after.
        val step = if (held == null) firstStepWords(x) else "lock held"
        // 1 Nov (R142 NP5-52): an R52 trade that entered with the 09:25 exit says it beside its ladder.
        return com.optionslab.ira.ArmExits.Entry("passed", label(p.arm), false, p.symbol, stop, PassedArms.targetLevel(p.entry, x), at,
            listOfNotNull(step?.takeIf { x.steps.isNotEmpty() }, noProgressWords(x), fixedWords(p)).joinToString("; ").ifEmpty { null })
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
     * 30 Oct (CI review; CLAUDE.md "open positions come first"): the watch's pass runs this arm in front of the stops only when it
     * has real work - switched on, or holding (or a book not read yet; [ensureRead] reads it first). Switched off and flat it only decides for the switched-off
     * shadow ([OffShadow]), which the pass leaves to its own job after the stops ([OffShadow.runLate]). Memory only.
     */
    fun realDue(): Boolean = ready() && (cache == null || _view.value.armed.values.any { it } || _view.value.open.any { it.mirrored })

    /** 30 Oct: the book read once (a restart), on the watch's worker, so [realDue] knows the switch and what is held. */
    suspend fun ensureRead() { if (ready() && cache == null) refresh() }

    /**
     * The watch needs this host's pass now (memory only): a position is held, or an arm is on (or record only, 19 Oct) and the time is 09:16 .. 15:16
     * (the entries run 09:20 .. 15:10; the 15:15 square-off). Outside that it costs the watch nothing. 22 Oct: from 09:16, not 09:19, so the
     * indices are followed on the stream ([follow]) before 09:19 starts: the 09:15 bar is whole at the 09:20 check.
     */
    fun wantsPass(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean =
        // 25 Oct (merge review): a book not read yet (a read that failed at start-up) wants a pass, so each watch pass reads it again
        // ([tick] does) and the set-aside after 5 failures is reached - else the arms sat unread all day until the rows were opened.
        // 30 Oct: an arm that is off still runs (in shadow), so its hours always want a pass on a trading day (the scheduler's wake
        // rules still pace it: battery).
        (ready() && cache == null) || holding() || (minute in WAKE_FROM until WAKE_UNTIL && runCatching { Market.isTradingDay() }.getOrDefault(false))

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
            if (was == mode) return@withLock "${label(source)} is already ${modeWord(mode)}."
            b.armed[source] = mode == Mode.ON
            if (mode == Mode.RECORD) b.recordOnly += source else b.recordOnly -= source
            if (mode == Mode.ON && source == BreadthDonchianArm.SOURCE && b.breadthSince == null) b.breadthSince = runCatching { Market.today() }.getOrElse { LocalDate.now() }
            // 19 Oct (review): on again from record only, the forward bar also counts from now - its would-be trades were never
            // bought, so they never switch it off the moment it is back on (they stay in its record, labelled).
            if (mode == Mode.ON && (b.paused.remove(source) != null || was == Mode.RECORD)) {
                val nets = b.positions.filter { it.arm == source && !it.open && it.shadow == null }.map { it.net ?: 0.0 }
                if (source == Solo2.SOURCE) SoloMidday.baseline(nets).let { b.soloBase = "${it.from}|${it.peak}" } else b.base[source] = nets.size
            }
            val holds = if (b.positions.any { it.arm == source && it.open && it.shadow == null }) "; what it holds still closes by its own exits" else ""
            note(b, "${label(source)} " + when (mode) {
                Mode.ON -> "switched on: paper, and live through your live gates"
                Mode.RECORD, Mode.OFF -> "switched off - it runs in shadow: its breaks are kept as would-be trades, nothing is bought$holds"
            })
            save(b)
            if (log) runCatching {
                if (mode == Mode.RECORD) com.optionslab.app.ira.Autopilot.passedArmRecordOnlyByBoss(source, label(source), modeChange(was))
                else com.optionslab.app.ira.Autopilot.passedArmSwitchedByBoss(source, label(source), research(source), mode == Mode.ON, modeChange(was))
            }
            when (mode) {
                Mode.ON -> "${label(source)} on: paper, and live through your live gates. ${PassedArms.PASSED}."
                Mode.RECORD, Mode.OFF -> "${label(source)} off: it runs in shadow (would-be trades, nothing bought)."
            }
        }
    }

    private fun modeWord(m: Mode): String = when (m) { Mode.ON -> "on"; Mode.RECORD, Mode.OFF -> "off (it runs in shadow)" }   // 30 Oct: record only and off are one

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
     * 1 Nov (Boss: "Make the change the arms"): R140's wide-day skip on or off for R72 Breadth Donchian's NEW breaks (open trades are
     * not touched). Paper only; while R72 may be copied live it is only logged. [log]: false for the change log's own Undo (the
     * switch-on is logged by the 1 Nov update).
     */
    suspend fun setWideSkip(on: Boolean, log: Boolean = false): String {
        if (!ready()) return "The passed arms are not ready yet."
        return lock.withLock {
            val b = book()
            if (b.wideOn == on) return@withLock "${BreadthDonchianArm.LABEL}'s wide-day skip is already ${if (on) "on" else "off"} (paper only)."
            b.wideOn = on
            note(b, if (on) "${BreadthDonchianArm.LABEL}: wide-day skip on: ${BreadthDonchianArm.WIDE_RULE} (paper; live in shadow)"
                else "${BreadthDonchianArm.LABEL}: wide-day skip off: breaks after a wide day are taken again")
            save(b)
            if (log && on) runCatching { com.optionslab.app.ira.Autopilot.logPassedWideSkipOn() }
            if (on) "${BreadthDonchianArm.LABEL} takes no new paper break the day after a very wide day (live: logged only)."
            else "${BreadthDonchianArm.LABEL} takes its breaks after a wide day again (paper only)."
        }
    }

    /**
     * 6 Nov (Boss's exception, CLAUDE.md): R151's R72 and R51 pickers on or off. On, a new R72 / R51 break scored under its arm's
     * threshold is skipped on paper and live (a would-be trade). Off adds those live trades back, so the change log's Undo of the
     * switch-on takes the PIN ([AutoChange.passedPicker]: live scope). [log]: false for the change log's own Undo.
     */
    suspend fun setPicker(on: Boolean, log: Boolean = false): String {
        if (!ready()) return "The passed arms are not ready yet."
        return lock.withLock {
            val b = book()
            if (b.pickOn == on) return@withLock "The R72 and R51 pickers are already ${if (on) "on" else "off"}."
            b.pickOn = on
            note(b, if (on) "${R72Picker.LABEL} and ${R51Picker.LABEL} on (${R72Picker.STATUS}): ${R72Picker.RULE}; R51's the same at ${R51Picker.THRESHOLD}"
                else "${R72Picker.LABEL} and ${R51Picker.LABEL} off: R72's and R51's breaks are taken without them")
            save(b)
            if (log && on) runCatching { com.optionslab.app.ira.Autopilot.logPassedPickerOn() }
            if (on) "The R72 and R51 pickers are on: a break they score low is skipped, on paper and live (${R72Picker.STATUS})."
            else "The R72 and R51 pickers are off: their breaks are taken without them."
        }
    }

    /**
     * 19 Nov 2026 (audit batch 2, coordinator's decision B2-B; Bot settings): a paper-only refusal of an entry that could go live - another
     * arm holds the option on paper, the paper account guard, no fresh paper price - also sends its live copy, through every live gate.
     * Off (the default) is shadow: the refusal skips live as it always did and each such entry is logged "would have gone live". On adds
     * live entries (live risk up), so it takes [pinConfirmed]; off only lowers risk (one tap).
     */
    suspend fun setRefusalLive(on: Boolean, pinConfirmed: Boolean = false): String {
        if (!ready()) return "The passed arms are not ready yet."
        if (on && !pinConfirmed) return "Switching this on adds live entries: it needs your PIN. Nothing changed."
        return lock.withLock {
            val b = book()
            if (b.refusalLiveOn == on) return@withLock "Paper refusals going live is already ${if (on) "on" else "off"}."
            b.refusalLiveOn = on
            note(b, if (on) "paper refusals go live ON (Boss's PIN): an entry the paper book refused that could go live is sent live through every live gate"
                else "paper refusals go live OFF: such entries skip live as before (each logged in shadow)")
            save(b)
            if (on) "On: an entry the paper book refuses (held on paper, the paper guard, no paper price) still goes live through every live gate."
            else "Off (shadow): such entries skip live as before; each is logged as would have gone live."
        }
    }

    /** 19 Nov 2026 (audit batch 2, B2-B): the words of a paper refusal that would have gone live (shadow), counted on the Bot settings card. */
    const val REFUSAL_MARK = "would have gone live"

    /**
     * 20 Nov 2026 (audit B5, item 1; Bot settings): R51 and R72's stale-history check on or off. On, a break whose 20-bar channel reads an
     * earlier session that does not reach the last trading day ([staleWhy]: yesterday missing, or read in part) waits inside its fill
     * window while the history is read again, and is let go when the window ends - on paper and live. Off (the default) is shadow: taken
     * as before, each logged "would have skipped (stale history)". Both ways take [pinConfirmed] (20 Nov 2026, deep review): off adds those
     * live entries back, and on also lets a better history replace the one in use mid-session ([keepHist]), which moves the channels of
     * every arm reading it and can make a new break, live too.
     */
    suspend fun setStaleSkip(on: Boolean, pinConfirmed: Boolean = false): String {
        if (!ready()) return "The passed arms are not ready yet."
        if (!pinConfirmed) return if (on) "Switching the stale-history check on lets a better history replace the one in use, which can change live breaks: it needs your PIN. Nothing changed."
            else "Switching the stale-history check off lets such breaks go live again: it needs your PIN. Nothing changed."
        return lock.withLock {
            val b = book()
            if (b.staleOn == on) return@withLock "The stale-history check is already ${if (on) "on" else "off (shadow)"}."
            b.staleOn = on
            // (On: the 1, 2, 5-minute reads start afresh, not after the tries the off reads counted.)
            if (on) { histTried = null; pickTried = null }
            note(b, if (on) "stale-history check ON (Boss's PIN): an R51 / R72 break that reads an earlier session not up to the last trading day waits while the history is read again, then is let go (paper and live)"
                else "stale-history check OFF (Boss's PIN): such breaks are taken as before (each logged in shadow)")
            save(b)
            runCatching { Diag.record("passed", "Stale-history check: ${if (on) "shadow → on" else "on → shadow"} (by you)") }
            if (on) "On: an R51 or R72 break that reads a stale history waits while it is read again, and is let go if it is not whole in time."
            else "Off (shadow): such breaks are taken as before; each is logged as would have skipped."
        }
    }

    /** 20 Nov 2026 (audit B5, item 1): the words of a break the stale-history check would have held (shadow), counted on its card. */
    const val STALE_MARK = "would have skipped (stale history)"

    /**
     * 6 Nov (Boss: "Do whatever you recommend", on R51): R150 B's W20 label on or off. On, each new R51 break is labelled "W20 would skip
     * / keep" ([W20Rule]); it never skips or changes a trade, paper or live. [log]: false for the change log's own Undo.
     */
    suspend fun setW20(on: Boolean, log: Boolean = false): String {
        if (!ready()) return "The passed arms are not ready yet."
        return lock.withLock {
            val b = book()
            if (b.w20On == on) return@withLock "${R51Picker.LINE} is already ${if (on) "on" else "off"} (a log only)."
            b.w20On = on
            note(b, if (on) "${R51Picker.LINE} on (${W20Rule.STATUS}): ${W20Rule.RULE}; and each is scored by R151's R51 picker"
                else "${R51Picker.LINE} off: R51's breaks are no longer labelled")
            save(b)
            if (log && on) runCatching { com.optionslab.app.ira.Autopilot.logPassedW20On() }
            if (on) "${R51Picker.LINE} on: each R51 break is labelled; nothing is skipped (${W20Rule.STATUS})."
            else "${R51Picker.LINE} off: R51's breaks are no longer labelled (they were never skipped)."
        }
    }

    /**
     * 1 Nov (Boss: "Make the change the arms"): R142's 09:25 no-progress exit on or off for R52 Open outside value's NEW trades (open
     * trades keep the exits they entered with). Paper only; a trade that may be copied live keeps the plain exits and the early
     * exit is only logged. [log]: false for the change log's own Undo (the switch-on is logged by the 1 Nov update).
     */
    suspend fun setNoProgress(on: Boolean, log: Boolean = false): String {
        if (!ready()) return "The passed arms are not ready yet."
        return lock.withLock {
            val b = book()
            if (b.np5On == on) return@withLock "${OovArm.LABEL}'s 09:25 no-progress exit is already ${if (on) "on" else "off"} (paper only)."
            b.np5On = on
            note(b, if (on) "${OovArm.LABEL}: 09:25 no-progress exit on: ${OovArm.NO_PROGRESS_RULE} (paper; live in shadow)"
                else "${OovArm.LABEL}: 09:25 no-progress exit off for new trades" +
                    if (b.positions.any { it.open && it.np5 }) "; open trades keep the exits they entered with" else "")
            save(b)
            if (log && on) runCatching { com.optionslab.app.ira.Autopilot.logPassedNoProgressOn() }
            if (on) "${OovArm.LABEL}'s new paper trades are sold at 09:25 when the option is not up 5% by then (live: logged only)."
            else "${OovArm.LABEL}'s new paper trades keep their plain exits (no 09:25 exit)."
        }
    }

    /**
     * 6 Nov (R151 Amendment C / E; failed gates, Boss's exception): a picker in words - on or off, and R72's closed trades it scored ([closed]: R72's,
     * would-be ones included) split by its decision, with their nets ([m]: a rupee figure, hidden where the caller hides them).
     * Judged after about 100 scored trades (CLAUDE.md: a shadow log first).
     */
    internal fun pickerWords(on: Boolean, closed: List<Pos>, m: (Double) -> String = { "%,.0f".format(Locale.ENGLISH, it) },
                             label: String = R72Picker.LABEL, takes: (Double) -> Boolean = R72Picker::takes, status: String = R72Picker.STATUS): String {
        // Taken: real trades the picker scored and let through; skipped: the breaks it skipped, kept as would-be trades ([SHADOW_PICK]).
        val skip = closed.filter { it.shadow == SHADOW_PICK }
        val take = closed.filter { it.pick != null && it.shadow == null && takes(it.pick) }
        return "$label ${if (on) "on" else "off"} ($status): taken ${take.size} closed " +
            "${m(take.sumOf { it.net ?: 0.0 })}, skipped ${skip.size} (would-be) ${m(skip.sumOf { it.net ?: 0.0 })}"
    }

    /**
     * 6 Nov (R150 B's W20; failed gates, a shadow log only): R51's W20 in words - on or off, and R51's closed trades it labelled
     * ([closed], would-be ones included) split by "would skip" / "would keep", with their nets ([m]: a rupee figure).
     */
    internal fun w20Words(on: Boolean, closed: List<Pos>, m: (Double) -> String = { "%,.0f".format(Locale.ENGLISH, it) }): String {
        val (skip, keep) = closed.filter { it.w20 != null }.partition { it.w20 == true }
        return "${W20Rule.LABEL} ${if (on) "on" else "off"} (${W20Rule.STATUS}): would skip ${skip.size} closed " +
            "${m(skip.sumOf { it.net ?: 0.0 })}, would keep ${keep.size} closed ${m(keep.sumOf { it.net ?: 0.0 })} (judged at about 100)"
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
        // 22 Oct (the uploaded file cut each line at 300 characters: R72, R52 and Solo 2.0 never reached it): R72, the primary
        // arm, first; each arm's line, then each index's status and R72's comparison on lines of their own (the upload splits
        // this text at its line breaks).
        return (listOf(BreadthDonchianArm.SOURCE) + SOURCES.filter { it != BreadthDonchianArm.SOURCE }).joinToString("\n") { s ->
            val c = v.closedOf(s)
            val ap = c.filter { it.index == apart(s) }
            val wb = v.wouldBeOf(s)
            "${label(s)}: ${if (v.on(s)) "on (paper; live through the live gates)" else com.optionslab.ira.PassedArmsTalk.RECORD_ONLY_WORDS}" +
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
                    // 1 Nov: R72's wide-day skip and R52's 09:25 exit (paper; live in shadow), with the trades that entered with the exit.
                    (if (s == BreadthDonchianArm.SOURCE) " · wide-day skip (R140) ${if (v.wideOn) "on" else "off"} for new breaks (live in shadow)" else "") +
                    // 6 Nov: R151's R72 picker (failed gates, Boss's exception): the closed trades it took and the breaks it skipped.
                    (if (s == BreadthDonchianArm.SOURCE) " · ${pickerWords(v.pickOn, c, m = { m(it) })}" else "") +
                    // 6 Nov: R150 B's W20 on R51 (failed gates; a shadow log only): the closed trades it would skip and keep.
                    (if (s == DonchianArm.SOURCE) " · ${w20Words(v.w20On, c) { m(it) }} · " +
                        pickerWords(v.pickOn, c, { m(it) }, R51Picker.LABEL, R51Picker::takes, R51Picker.STATUS) else "") +
                    (if (s == OovArm.SOURCE) " · 09:25 no-progress exit (R142) ${if (v.np5On) "on" else "off"} for new trades (live in shadow), " +
                        "${c.count { it.np5 }} closed on it ${m(c.filter { it.np5 }.sumOf { it.net ?: 0.0 })}" else "") +
                    // 15 Nov (Boss's exception, failed gates): the breakeven rule - its closed trades apart from the arm's own exits.
                    // 16 Nov: the fourth version (the step lock) is the switch's now; its trades are counted with the rule's.
                    // Review: the two versions' closed trades counted apart (V4 alone; with the step lock).
                    (if (StepTrail.covers(s)) " · ${StepTrail.DIAG} ${if (StepTrailExits.isOn()) "on" else "off"}, ${StepTrail.DIAG_LOCK} " +
                        "${if (StepTrailExits.isOn() && StepTrailExits.lockOn()) "on" else "off"} for new trades, " +
                        "${c.count { it.fx && it.sl }} closed on it ${m(c.filter { it.fx && it.sl }.sumOf { it.net ?: 0.0 })}, " +
                        "${c.count { it.fx && !it.sl }} on the breakeven alone ${m(c.filter { it.fx && !it.sl }.sumOf { it.net ?: 0.0 })}, " +
                        "${c.count { !it.fx }} on the arm's own exits " +
                        m(c.filter { !it.fx }.sumOf { it.net ?: 0.0 }) else "") +
                // (16 Oct, R73 review: the unjudged count is said once above, today's only; its kept line is not repeated.)
                v.status.filterKeys { it.startsWith("$s|") && it != ST_UNJUDGED_KEY }.toSortedMap().entries.joinToString("") { (k, x) -> "\n${label(s)} · ${k.substringAfter('|')} ${rupees(x)}" } +
                if (s == BreadthDonchianArm.SOURCE) "\n${label(s)} · ${rupees(v.comparison())}" else ""
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
    /** 20 Nov 2026 (audit B5, item 2): the day's last history read - when it started and how many there were (the back-off). Memory only. */
    @Volatile private var histTried: Tried? = null
    /** 20 Nov 2026: a history read is running (a second is never started beside it). */
    private val histBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One day's background reads so far: the last one started at [at] (ms), [tries] in all. */
    internal class Tried(val day: LocalDate, val at: Long, val tries: Int)

    /**
     * 20 Nov 2026 (audit B5, item 2: an empty or stale history was kept all day, R51 / R72 then had no signal before 10:55, or a false
     * one): how long after [tries] reads the next may start - 1, 2, then 5 minutes ([backoff]: the picker's own, 10 minutes after that).
     */
    internal fun backoffMs(tries: Int, backoff: LongArray = HIST_BACKOFF_MS): Long =
        if (tries >= SLOW_AFTER_TRIES) SLOW_BACKOFF_MS else backoff[(tries - 1).coerceIn(0, backoff.lastIndex)]
    /**
     * 20 Nov 2026 (review): after this many reads in a day (about an hour) the reads slow to every [SLOW_BACKOFF_MS] - a day the
     * calendar counts as trading but the market was shut leaves every history short of it all day, and is never read every 5 minutes.
     */
    internal const val SLOW_AFTER_TRIES = 14
    internal const val SLOW_BACKOFF_MS = 15 * 60_000L
    internal val HIST_BACKOFF_MS = longArrayOf(60_000L, 120_000L, 300_000L)
    internal val PICK_BACKOFF_MS = longArrayOf(60_000L, 120_000L, 300_000L, 600_000L)

    /** Whether a read for [day] may start now ([t]: the day's reads so far; [nowMs]). */
    internal fun readDue(t: Tried?, day: LocalDate, nowMs: Long, backoff: LongArray): Boolean =
        readDueAfter(t, day, nowMs, t?.let { backoffMs(it.tries, backoff) } ?: 0L)

    /** Whether a read for [day] may start now, [gapMs] after the last ([t]). */
    internal fun readDueAfter(t: Tried?, day: LocalDate, nowMs: Long, gapMs: Long): Boolean =
        t == null || t.day != day || nowMs - t.at !in 0 until gapMs

    /**
     * 20 Nov 2026 (final review of audit B5): the gap before the next history read. With the stale-history check on ([on]): 1, 2, then 5
     * minutes ([backoffMs]). Off (the default), as before: every [MISSING_GAP_MS] while one is missing ([missing]) - a history that came
     * sooner would add live breaks - and a stale one in use is read again only every [STALE_LOG_GAP_MS], for the "would have replaced" log.
     */
    internal fun histGapMs(on: Boolean, missing: Boolean, tries: Int, backoff: LongArray = HIST_BACKOFF_MS): Long = when {
        on -> backoffMs(tries, backoff)
        missing -> MISSING_GAP_MS
        else -> STALE_LOG_GAP_MS
    }
    internal const val MISSING_GAP_MS = 10 * 60_000L
    internal const val STALE_LOG_GAP_MS = 60 * 60_000L

    /**
     * 20 Nov 2026 (audit B5, item 1: the history was never checked for yesterday): why the earlier sessions [sessions] (oldest first)
     * cannot stand for the history of a day whose previous trading session is [prev] - the last one is older than [prev] (a day missing:
     * a read that lags, Upstox's not in yet), or [prev] lacks any of its last [DonchianArm.LOOKBACK] five-minute bars (read in part): the
     * bars the 20-bar channel of today's first bars reads. Null: they stand, or it cannot be told - [prev] unknown (no trading day in the
     * ten before by the calendar), no session at all (today's breaks then wait for 20 bars of today, as always), or a session newer than
     * the calendar's (the data knows better). Pure.
     */
    internal fun staleWhy(sessions: List<PassedArms.DayBars>, prev: LocalDate?): String? {
        if (prev == null) return null
        val last = sessions.lastOrNull() ?: return null
        if (last.date.isBefore(prev)) return "its last session is ${last.date}, not $prev"
        if (last.date != prev) return null
        val tail = PassedArms.Session.N / 5 - DonchianArm.LOOKBACK
        val n = PassedArms.bars5(last).count { it.b >= tail }
        return if (n < DonchianArm.LOOKBACK) "its $prev session lacks ${DonchianArm.LOOKBACK - n} of its last ${DonchianArm.LOOKBACK} five-minute bars" else null
    }

    /** Why [index]'s history for [day] cannot stand ([staleWhy]); null: it stands, or none is in (memory only). */
    internal fun historyStale(index: String, day: LocalDate): String? =
        history(index, day)?.let { staleWhy(it.sessions, previousSession(day)) }

    /** 20 Nov 2026 (audit B5, item 2): [index]'s history for [day] is read again - none, no session in it, or stale. Memory only. */
    internal fun historyWanted(index: String, day: LocalDate): Boolean = histRank(history(index, day), previousSession(day)) < 3

    /** How good a history is: 0 none, 1 daily candles only, 2 sessions that do not stand ([staleWhy]), 3 whole. */
    private fun histRank(h: Hist?, prev: LocalDate?): Int = when {
        h == null -> 0
        h.sessions.isEmpty() -> 1
        staleWhy(h.sessions, prev) != null -> 2
        else -> 3
    }

    /**
     * 20 Nov 2026 (review): [histRank], and among histories that do not stand, the newer last session first, then the more of its last
     * [DonchianArm.LOOKBACK] five-minute bars - so a read that misses yesterday never replaces one that lacks only a bar of it.
     */
    internal fun histOrder(h: Hist?, prev: LocalDate?): Long {
        val r = histRank(h, prev).toLong()
        if (r != 2L || h == null) return r * 100_000_000L
        val last = h.sessions.last()
        val tail = PassedArms.Session.N / 5 - DonchianArm.LOOKBACK
        return r * 100_000_000L + last.date.toEpochDay() * 100L + PassedArms.bars5(last).count { it.b >= tail }
    }

    /**
     * 20 Nov 2026 (audit B5, item 1): whether the Donchian channel of today's bar [b] in [series] reads an earlier session - fewer than
     * [DonchianArm.LOOKBACK] of today's bars before it. Later bars read today's alone, so an earlier session that does not stand cannot
     * change them. Pure.
     */
    internal fun readsHistory(series: List<PassedArms.Bar5>, today: LocalDate, b: Int): Boolean =
        series.count { it.date == today && it.b < b } < DonchianArm.LOOKBACK
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeGuard.handler("PassedArmsHost.background"))

    /** 4 Nov: the day each index's history read last finished (found or not): until then a rule needing it waits. Memory only. */
    private val histRead = java.util.concurrent.ConcurrentHashMap<String, LocalDate>()

    /** Whether [index]'s history read for [day] has not finished yet (a restart's first looks): its rules wait, not skip. */
    internal fun historyPending(index: String, day: LocalDate): Boolean = history(index, day) == null && histRead[index] != day

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
        // 20 Nov 2026 (audit B5, item 2): read again while any is missing, has no session or does not reach the last trading day
        // ([historyWanted]) - with the stale-history check on 1, 2, then every 5 minutes; off, as before ([histGapMs]: every 10 minutes
        // while one is missing, and a stale one in use only once an hour, for the log).
        if ((indices + VIX).none { historyWanted(it, day) }) return
        val all = (indices + ALL_INDICES).distinct()
        val on = staleActs()
        val missing = (all + VIX).any { history(it, day) == null }
        val t = histTried
        val ms = System.currentTimeMillis()
        // (Off: the gaps as before - see [histGapMs].)
        if (!readDueAfter(t, day, ms, t?.let { histGapMs(on, missing, it.tries) } ?: 0L)) return
        // (Off: a stale history in use is read again at most once an hour, only for the log.)
        val staleToo = on || staleReadAt.let { it == null || it.first != day || ms - it.second !in 0 until STALE_LOG_GAP_MS }
        if (!missing && !staleToo) return
        if (!histBusy.compareAndSet(false, true)) return
        histTried = Tried(day, ms, if (t != null && t.day == day) t.tries + 1 else 1)
        if (staleToo && !on) staleReadAt = day to ms
        // (Every index still wanted is read, not only the caller's: one arm's reads never hold another's back behind the back-off.)
        background.launch { try { runCatching { prefetchNow(day, all, staleToo) } } finally { histBusy.set(false) } }
    }

    internal const val VIX = "INDIAVIX"

    /** 20 Nov 2026: when (day, ms) a stale history in use was last read again with the check off ([prefetch]). Memory only. */
    @Volatile private var staleReadAt: Pair<LocalDate, Long>? = null

    /**
     * 6 Nov (R151's R72 picker, a shadow log): the picker's OWN history, apart from [hist] so nothing another rule reads changes (R51's
     * Supertrend reads every session [hist] holds): each of the five indices' and India VIX's last [R72Picker.HIST_SESSIONS]
     * sessions as 5-minute bars, its daily candles (the last [R72Picker.DAILY_SESSIONS]) and its last session's last close. Memory only.
     */
    internal class PickHist(val day: LocalDate, val hist5: List<PassedArms.Bar5>, val daily: List<R72Picker.Daily>, val prevClose: Double,
                            /**
                             * 20 Nov 2026 (audit B5, item 4): why it does not reach the last trading day (its sessions, [staleWhy], or its daily
                             * candles); null: whole. Such a history still scores as before (a break it cannot score is taken), but its scores are
                             * never kept, and it is read again ([prefetchPicker]).
                             */
                            val stale: String? = null)

    private val pickHist = java.util.concurrent.ConcurrentHashMap<String, PickHist>()
    @Volatile private var pickTried: Tried? = null
    /** A picker history download is running (a second is never started beside it). */
    private val pickBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 6 Nov: each R72 break's picker score, by its decision key ("day|arm|index|bar|side"), for the trade it becomes. Memory only. 20 Nov 2026
     * (audit B5, item 4): with whether it was made from whole inputs - only such a score is used again as it is ([pickScore]); one made from
     * part of them is kept only for the trade's label and the log line of the decision it made.
     */
    private val pickScores = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Boolean>>()

    /**
     * The decision key of the R72 break [p] was entered on: R72 fills inside its break's window (the bar's close .. 3 minutes on), so
     * its entry minute's row, over 5, less 1, is the break's bar. Null for another arm.
     */
    internal fun pickKey(p: Pos): String? {
        if (p.arm != BreadthDonchianArm.SOURCE && p.arm != DonchianArm.SOURCE) return null
        val row = PassedArms.Session.row(p.entryTime.toLocalTime())
        return "${p.day}|${p.arm}|${p.index}|${row / 5 - 1}|${p.side}"
    }

    /**
     * 6 Nov: R72's trades of [today] not yet labelled with their break's picker score get it ([pickScores]), whenever it was scored
     * (the same pass, or a later look once the picker's history came in). A label only. True when one changed.
     */
    private fun patchPicks(b: Book, today: LocalDate): Boolean {
        var changed = false
        for (i in b.positions.indices) {
            val p = b.positions[i]
            if (p.day != today) continue
            val k = pickKey(p) ?: continue
            // (20 Nov 2026: the score that decided the break, when one did; else the last made.)
            val sc = if (p.pick != null) null else decidingScore(k) ?: (if (p.arm == BreadthDonchianArm.SOURCE) pickScores[k] else r51Scores[k])?.first
            val w = if (p.arm == DonchianArm.SOURCE && p.w20 == null) w20Labels[k] else null
            if (sc == null && w == null) continue
            b.positions[i] = p.copy(pick = sc ?: p.pick, w20 = w ?: p.w20)
            changed = true
        }
        return changed
    }

    /** 6 Nov: each R51 break's W20 label (true: would skip), by its decision key, for the trade it becomes. Memory only. */
    private val w20Labels = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 6 Nov: each R51 break's R51-picker score, by its decision key, for the trade it becomes (20 Nov 2026: as [pickScores]). Memory only. */
    private val r51Scores = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Boolean>>()

    /**
     * 6 Nov (R151 Amendment E and R150 B's W20; failed gates, a shadow log only): each of today's R51 breaks R51 itself takes (with its
     * Supertrend filter on - the research's R51 and the population both were tested on - only those Supertrend agrees with) labelled once "W20 would skip / keep"
     * and scored by R51's picker, logged one line per bar; both kept for the trade it became ([patchPicks]). Skipped on an index's
     * expiry day, and until all six histories are in and this pass's reads are whole (said once a day; labelled on a later look).
     * Never changes a trade.
     */
    private fun labelW20(b: Book, today: LocalDate, reads: Reads): Boolean {
        if (!b.w20On) return false
        if (w20Labels.keys.any { !it.startsWith("$today|") }) { w20Labels.keys.removeAll { !it.startsWith("$today|") }; r51Scores.keys.removeAll { !it.startsWith("$today|") } }
        val src = DonchianArm.SOURCE
        if ((R72Picker.INDICES + VIX).any { pickReady(it, today) == null }) {
            val wait = "$today|$src|shadow_wait"
            if (wait in b.decided) return false
            b.decided += wait
            note(b, "${R51Picker.LINE}: its history is still loading, so R51's breaks are not labelled yet (labelled on a later look; the trades go on as always)")
            return true
        }
        if (reads.vix == null || R72Picker.INDICES.any { reads.bars[it] == null }) return false
        val all = seriesAll(today, reads)
        val lines = java.util.TreeMap<Int, MutableList<String>>()
        for (u in DonchianArm.UNDERLYINGS) {
            val series = all[u] ?: continue
            val h = pickReady(u, today) ?: continue
            val t = todayBars(u, today, reads) ?: continue
            if (expiryToday(u, today) != false) continue
            // R51's own breaks: with its Supertrend filter on (as the research's R51), only the ones Supertrend agrees with.
            val st = if (b.stOn) stSeries(u, t) else null
            val sigs = DonchianArm.signals(u, series, today).filter { st == null || DonchianArm.supertrendAgrees(st, it) == true }.sortedBy { it.bar.b }
            sigs.forEachIndexed { earlier, s ->
                val dk = "$today|$src|$u|${s.bar.b}|${s.side}"
                if (w20Labels.containsKey(dk)) return@forEachIndexed
                val skip = W20Rule.skips(h.hist5, t, s.bar.entryRow, s.side) ?: return@forEachIndexed
                // The features are pure, so a score that cannot be made now would not be made later: the W20 label is kept either way
                // and the line says the picker could not score it (the trade then counts in W20's record, not the picker's).
                val score = decidingScore(dk) ?: r51Scores[dk]?.first ?: pickScore(today, src, s, earlier, 0, reads)
                w20Labels[dk] = skip
                if ("$dk|w20" in b.decided) return@forEachIndexed
                b.decided += "$dk|w20"
                lines.getOrPut(s.bar.b) { ArrayList() } += R51Picker.part(u, s.side, skip, score)
            }
        }
        for ((bar, parts) in lines) note(b, "${R51Picker.LINE} ${PassedArms.Session.time(5 * bar + 5)} breaks: ${parts.joinToString("; ")}")
        return lines.isNotEmpty()
    }

    private fun pickReady(u: String, day: LocalDate): PickHist? = pickHist[u]?.takeIf { it.day == day }

    /** 20 Nov 2026 (audit B5, item 4): why [u]'s picker history for [day] does not reach the last trading day (null: whole, or none). */
    internal fun pickerStale(u: String, day: LocalDate): String? = pickReady(u, day)?.stale

    /** 20 Nov 2026 (audit B5, item 4): the picker score kept for the decision key [key], with whether it was made from whole inputs. */
    internal fun pickScoreKept(key: String): Pair<Double, Boolean>? = pickScores[key] ?: r51Scores[key]

    /**
     * Starts the picker's history reads in the background (from 08:45; at most every 10 minutes until they are in), as [prefetch]:
     * 40 calendar days of 1-minute bars and 460 of daily candles for the five indices and India VIX. Never waited for: until they are
     * in, a break is not scored (its trade goes on as always).
     */
    internal fun prefetchPicker(now: LocalDateTime) {
        val day = now.toLocalDate()
        if (now.toLocalTime().isBefore(LocalTime.of(8, 45))) return
        // 20 Nov 2026 (audit B5, items 2 and 4): read again while any is missing or does not reach the last trading day, 1, 2, 5, then
        // every 10 minutes (it was every 10 from the start, and a stale one was kept all day).
        if ((R72Picker.INDICES + VIX).all { pickReady(it, day).let { h -> h != null && (h.stale == null || !staleActs()) } }) return
        val t = pickTried
        val ms = System.currentTimeMillis()
        // (Off: every 10 minutes while one is missing, as before; on: 1, 2, 5, then every 10.)
        if (!readDueAfter(t, day, ms, t?.let { if (staleActs()) backoffMs(it.tries, PICK_BACKOFF_MS) else MISSING_GAP_MS } ?: 0L)) return
        if (!pickBusy.compareAndSet(false, true)) return
        pickTried = Tried(day, ms, if (t != null && t.day == day) t.tries + 1 else 1)
        background.launch { try { runCatching { prefetchPickerNow(day) } } finally { pickBusy.set(false) } }
    }

    /** [prefetchPicker]'s reads for [day] (tests call it directly); an index read only in part is not kept (read again later). */
    internal suspend fun prefetchPickerNow(day: LocalDate) {
        for (u in R72Picker.INDICES + VIX) {
            val was = pickReady(u, day)
            // (20 Nov 2026, deep review: one in use is read again only with the stale-history check on; off, the first kept stays, as before.)
            if (was != null && (was.stale == null || !staleActs())) continue
            runCatching {
                val key = if (u == VIX) Upstox.INDEX_KEYS.getValue("INDIAVIX") else upKey(u)
                val ones = testHistory?.invoke(u) ?: toBars(Net.history(key, day.minusDays(40), day.minusDays(1)))
                val daily = testDaily?.invoke(u) ?: toBars(Net.daily(key, day.minusDays(460), day.minusDays(1)))
                val sessions = ones.filter { it.start.toLocalDate().isBefore(day) }.groupBy { it.start.toLocalDate() }.toSortedMap()
                    .map { (d, l) -> PassedArms.DayBars.of(d, l) }.filter { it.count > 0 }.takeLast(R72Picker.HIST_SESSIONS)
                val days = daily.filter { it.start.toLocalDate().isBefore(day) }.sortedBy { it.start }.distinctBy { it.start.toLocalDate() }
                    .takeLast(R72Picker.DAILY_SESSIONS).map { R72Picker.Daily(it.start.toLocalDate(), it.open, it.close) }
                // Kept only when it holds what the features need (the research always had it): else read again later.
                // 20 Nov 2026 (audit B5, item 4): marked when it does not reach the last trading day (its scores are then never kept), and
                // never put over one that does.
                val prev = previousSession(day)
                val stale = staleWhy(sessions, prev) ?: days.lastOrNull()?.date?.takeIf { prev != null && it.isBefore(prev) }?.let { "its daily candles end on $it, not $prev" }
                if (sessions.size >= R72Picker.MIN_SESSIONS && days.size >= (if (u == VIX) R72Picker.MIN_VIX_DAILY else R72Picker.MIN_DAILY) &&
                    (was == null || stale == null))
                    pickHist[u] = PickHist(day, sessions.flatMap { R72Picker.bars5(it) }, days, sessions.last().lastClose, stale)
            }
        }
    }

    /**
     * 6 Nov (Boss's exception, CLAUDE.md): the picker's score for [s] of [src] (R72 or R51), made once and kept; null when it cannot be
     * made now (the six histories not all in, this pass's reads not whole, or the features failing) - such a break is never skipped.
     */
    private fun pickScore(today: LocalDate, src: String, s: DonchianArm.Signal, earlier: Int, fCount: Int, reads: Reads): Double? {
        val cache = if (src == BreadthDonchianArm.SOURCE) pickScores else r51Scores
        if (cache.keys.any { !it.startsWith("$today|") }) cache.keys.removeAll { !it.startsWith("$today|") }
        val dk = "$today|$src|${s.underlying}|${s.bar.b}|${s.side}"
        // 20 Nov 2026 (audit B5, item 4): only a score made from whole inputs is used again as it is; one made from part of them is made
        // afresh on the next ask (the reads may be whole by then) - for the labels and the log. The skip or take a score DECIDED is kept
        // apart and never changes ([pickDecide]).
        cache[dk]?.takeIf { it.second }?.let { return it.first }
        if ((R72Picker.INDICES + VIX).any { pickReady(it, today) == null }) return null
        if (reads.vix == null || R72Picker.INDICES.any { reads.bars[it] == null }) return null
        // (Whether the inputs are whole, read BEFORE the score is made: a whole history landing in between never marks a score made
        // from the stale one as whole.)
        val whole = runCatching { pickWhole(today, s, reads) }.getOrDefault(false)
        val model = if (src == BreadthDonchianArm.SOURCE) R72Picker.MODEL else R51Picker.MODEL
        val sc = runCatching { model.score(R72Picker.features(pickInput(today, s, earlier, fCount, reads))) }.getOrNull()?.takeIf { it.isFinite() }
            ?: return null
        cache[dk] = sc to whole
        return sc
    }

    /**
     * 20 Nov 2026 (deep review of audit B5, item 4): what the picker decided for today's break [s] of [src] - its skip words, or null
     * (taken, or not scored). The first score that decides a break (a skip or a take) fixes that decision for the day: a break skipped
     * on one look is never bought on a later one because a fresher score came (its would-be trade's booking failed and was tried again),
     * nor a take turned into a skip. A break it could not score is taken and not fixed, as before. Memory only.
     */
    internal fun pickDecide(decided: MutableSet<String>?, today: LocalDate, src: String, s: DonchianArm.Signal, score: () -> Double?,
                            words: (Double) -> String?): String? {
        if (pickDecisions.keys.any { !it.startsWith("$today|") }) pickDecisions.keys.removeAll { !it.startsWith("$today|") }
        val dk = "$today|$src|${s.underlying}|${s.bar.b}|${s.side}"
        // (Kept in the book too - "dk|pickFix:score|skip or take" - so a restart never decides the break again.)
        val fixed = pickDecisions[dk] ?: decided?.firstOrNull { it.startsWith("$dk|$PICK_FIX") }?.substringAfter("|$PICK_FIX")?.split('|')?.let { f ->
            val sc = f.getOrNull(0)?.toDoubleOrNull() ?: return@let null
            val skipped = f.getOrNull(1) == "skip"
            PickFix(sc, if (skipped) words(sc) ?: "R151's picker skipped it on an earlier look (score %.3f; your exception)".format(Locale.ENGLISH, sc) else null)
        }?.also { pickDecisions[dk] = it }
        if (fixed != null) return fixed.words
        val sc = score() ?: return null
        val w = words(sc)
        pickDecisions[dk] = PickFix(sc, w)
        decided?.add("$dk|$PICK_FIX$sc|${if (w != null) "skip" else "take"}")
        return w
    }

    /** 20 Nov 2026: a break's fixed picker decision - the score that decided it and its skip words (null: taken). */
    internal class PickFix(val score: Double, val words: String?)

    private const val PICK_FIX = "pickFix:"

    /** 20 Nov 2026: the picker's decision of each break, by its decision key ([pickDecide]). Memory (and the book's decisions). */
    private val pickDecisions = java.util.concurrent.ConcurrentHashMap<String, PickFix>()

    /** The score that decided the break [dk] ([pickDecide]), or null: none decided yet. */
    private fun decidingScore(dk: String): Double? = pickDecisions[dk]?.score

    /**
     * 20 Nov 2026 (audit B5, item 4): whether [s]'s picker inputs are whole - all six picker histories up to the last trading day, and
     * this pass's minutes of the five indices and India VIX each holding the break bar's last minute. Memory only.
     */
    private fun pickWhole(today: LocalDate, s: DonchianArm.Signal, reads: Reads): Boolean {
        if ((R72Picker.INDICES + VIX).any { pickReady(it, today).let { h -> h == null || h.stale != null } }) return false
        val r = s.bar.entryRow - 1
        if (R72Picker.INDICES.any { u -> todayBars(u, today, reads)?.c?.getOrNull(r)?.isNaN() != false }) return false
        return reads.vix?.let { PassedArms.DayBars.of(today, it) }?.c?.getOrNull(r)?.isNaN() == false
    }

    /** Why [model] skips a break scoring [sc] (null: taken, or not scored - never skipped on the unknown). */
    private fun pickWords(sc: Double?, model: R72Picker.Model): String? {
        if (sc == null) return null
        val thr = testPickThreshold ?: model.threshold
        if (model.takes(sc, thr)) return null
        return "R151's picker scored it %.3f, under %.4f (your exception)".format(Locale.ENGLISH, sc, thr)
    }

    /** R72's picker skip ([breaks]' pickSkip) for today's breaks [sigs] (all five indices); null when the picker is off. */
    private fun pickSkipR72(b: Book, today: LocalDate, sigs: Map<String, List<DonchianArm.Signal>>, reads: Reads): ((DonchianArm.Signal) -> String?)? {
        if (!b.pickOn) return null
        val src = BreadthDonchianArm.SOURCE
        val order = sigs.mapValues { (_, l) -> l.sortedBy { it.bar.b } }
        val count = sigs.values.flatten().groupingBy { it.bar.b to it.side }.eachCount()
        return { x ->
            pickDecide(b.decided, today, src, x, { pickScore(today, src, x, order[x.underlying]?.indexOf(x)?.coerceAtLeast(0) ?: 0, count[x.bar.b to x.side] ?: 0, reads) },
                { sc -> pickWords(sc, R72Picker.MODEL) })
        }
    }

    /**
     * R51's picker skip for [u]'s breaks today [sigs]; [gate] R51's Supertrend filter when on (its breaks are then the ones it passes,
     * as the research's R51 - the picker's "breaks earlier today" counts those). Null when the picker is off.
     */
    private fun pickSkipR51(b: Book, today: LocalDate, sigs: List<DonchianArm.Signal>, gate: ((DonchianArm.Signal) -> Boolean?)?, reads: Reads): ((DonchianArm.Signal) -> String?)? {
        if (!b.pickOn) return null
        // R51's own breaks, built only when a break is decided (the gate is Supertrend's, already run for it by [breaks]).
        val own by lazy { sigs.filter { gate == null || gate(it) == true }.sortedBy { it.bar.b } }
        return { x ->
            val i = own.indexOf(x)
            pickDecide(b.decided, today, DonchianArm.SOURCE, x, { if (i < 0) null else pickScore(today, DonchianArm.SOURCE, x, i, 0, reads) },
                { sc -> pickWords(sc, R51Picker.MODEL) })
        }
    }

    /**
     * 6 Nov (R151's R72 picker; the skips themselves are made in [breaks]): each of today's R72 breaks [sigs] (all five indices) scored once and logged
     * ("would take / would skip"), one line per bar; its score is kept for the trade it became ([patchPicks]). Skipped on an index's
     * expiry day (the research had no break there), and until all six histories are in (said once a day; scored on a later look).
     * The log itself never throws and never changes a trade: R72's taking goes on whatever happens here.
     */
    private fun scoreBreaks(b: Book, today: LocalDate, sigs: List<DonchianArm.Signal>, reads: Reads): Boolean {
        if (!b.pickOn || sigs.isEmpty()) return false
        if (pickScores.keys.any { !it.startsWith("$today|") }) pickScores.keys.removeAll { !it.startsWith("$today|") }
        val src = BreadthDonchianArm.SOURCE
        // The research scored every break with all five indices and India VIX known: until all six histories are in, none is scored
        // (said once a day), so no score is ever made from part of the inputs.
        if ((R72Picker.INDICES + VIX).any { pickReady(it, today) == null }) {
            val wait = "$today|$src|pick_wait"
            if (wait in b.decided) return false
            b.decided += wait
            note(b, "${R72Picker.LABEL}: its history is still loading, so R72's breaks are not scored yet (scored on a later look; the trades go on as always)")
            return true
        }
        // This pass's own reads must be whole too (each index's minutes and VIX's): a read that failed is scored on a later look.
        if (reads.vix == null || R72Picker.INDICES.any { reads.bars[it] == null }) return false
        val byIndex = sigs.groupBy { it.underlying }.mapValues { (_, l) -> l.sortedBy { it.bar.b } }
        val count = sigs.groupingBy { it.bar.b to it.side }.eachCount()
        // One log line per bar and side, with each index's score (not one line per index: the trade lines stay easy to read).
        val lines = java.util.TreeMap<Pair<Int, Int>, MutableList<String>>(compareBy({ it.first }, { it.second }))
        for ((u, list) in byIndex) {
            if (expiryToday(u, today) != false) continue
            list.forEachIndexed { earlier, s ->
                val dk = "$today|$src|$u|${s.bar.b}|${s.side}"
                if ("$dk|pick" in b.decided) return@forEachIndexed
                // (20 Nov 2026: the score the decision used, when one was made; else made now.)
                val score = decidingScore(dk) ?: pickScores[dk]?.first ?: pickScore(today, src, s, earlier, count[s.bar.b to s.side] ?: 0, reads) ?: return@forEachIndexed
                b.decided += "$dk|pick"
                lines.getOrPut(s.bar.b to s.side) { ArrayList() } += R72Picker.part(u, score)
            }
        }
        for ((key, parts) in lines) {
            val at = PassedArms.Session.time(5 * key.first + 5)
            note(b, "${R72Picker.LABEL} $at ${if (key.second > 0) "call" else "put"} breaks: ${parts.joinToString(", ")}")
        }
        return lines.isNotEmpty()
    }

    /** One break's picker input from this pass's reads and the picker's history (an index without either is left out). */
    private fun pickInput(today: LocalDate, s: DonchianArm.Signal, earlier: Int, fCount: Int, reads: Reads): R72Picker.Input {
        val syms = R72Picker.INDICES.mapNotNull { u ->
            val h = pickReady(u, today) ?: return@mapNotNull null
            val t = todayBars(u, today, reads)
            u to R72Picker.Sym(h.hist5, t, h.daily, t?.o?.get(0) ?: Double.NaN)
        }.toMap()
        val v = pickReady(VIX, today)
        return R72Picker.Input(s.underlying, today, s.bar.entryRow, s.side, earlier, fCount, syms,
            reads.vix?.let { PassedArms.DayBars.of(today, it) }, v?.prevClose ?: Double.NaN, v?.daily.orEmpty())
    }

    /** [prefetch]'s reads for [day] (tests call it directly). */
    internal suspend fun prefetchNow(day: LocalDate, indices: List<String> = ALL_INDICES,
                                     /** 20 Nov 2026: also read again a stale history already in use (off: once an hour, for the log only). */
                                     staleToo: Boolean = true) {
        // 4 Nov (Boss: after Android closed the app at 13:40, R51's 13:40 FINNIFTY and SENSEX breaks were skipped, "Supertrend
        // cannot be judged yet", and CISD could not look): the day's earlier sessions are kept on the phone once read, so a
        // restart has them back from the file in a moment instead of waiting on Upstox. All the cached files are read first.
        // (A test's own history seam bypasses the file, so a test's planted bars are always the ones read.)
        val cached = testHistory == null && testDaily == null
        if (cached) for (u in indices + VIX) if (historyWanted(u, day)) runCatching { readHistCache(u, day)?.let { (ones, daily) -> keepHist(u, day, ones, daily) } }
        for (u in indices + VIX) {
            // 20 Nov 2026 (audit B5, items 1-2): read again unless whole ([historyWanted]: none, no session, or not up to the last trading day).
            if (!historyWanted(u, day)) continue
            if (!staleToo && history(u, day) != null) continue
            runCatching {
                val key = if (u == VIX) Upstox.INDEX_KEYS.getValue("INDIAVIX") else upKey(u)
                val ones = testHistory?.invoke(u) ?: toBars(Net.history(key, day.minusDays(16), day.minusDays(1)))
                val daily = testDaily?.invoke(u) ?: toBars(Net.daily(key, day.minusDays(45), day.minusDays(1)))
                // 4 Nov (review): only a whole read is kept on the phone (sessions and daily candles both in): a partial one is
                // not pinned for the day, so a restart reads Upstox again.
                val whole = ones.any { it.start.toLocalDate().isBefore(day) } && daily.any { it.start.toLocalDate().isBefore(day) }
                // 20 Nov 2026 (audit B5): and only one up to the last trading day: a stale read is never pinned on the phone for the day.
                // (With the check off, the one kept is pinned as before even when stale, so a restart reads back the history in use.)
                if (keepHist(u, day, ones, daily) && cached && whole && (!staleActs() || !historyWanted(u, day))) runCatching { writeHistCache(u, day, ones, daily) }
            }
            histRead[u] = day
        }
    }

    /**
     * [u]'s earlier sessions and daily candles for [day] kept in memory; false when there was nothing to keep. 20 Nov 2026 (audit B5): a
     * read again never puts a worse one over what is kept ([histRank]: a stale or empty read over a whole one), and a read whose daily
     * candles failed keeps the daily candles already in.
     */
    private fun keepHist(u: String, day: LocalDate, ones: List<Bar>, daily: List<Bar>): Boolean {
        val sessions = ones.filter { it.start.toLocalDate().isBefore(day) }.groupBy { it.start.toLocalDate() }.toSortedMap()
            .map { (d, l) -> PassedArms.DayBars.of(d, l) }.filter { it.count > 0 }
        if (sessions.isEmpty() && daily.isEmpty()) return false
        val was = history(u, day)
        val h = Hist(day, sessions, daily.filter { it.start.toLocalDate().isBefore(day) }.ifEmpty { was?.daily.orEmpty() })
        val prev = previousSession(day)
        if (histOrder(h, prev) < histOrder(was, prev)) return false
        // 20 Nov 2026 (deep review of audit B5): a history already in use is replaced only with the stale-history check on. Off (the
        // default), the first one kept stays for the day, as before (a new one would change the channels of R51, R72 and every arm reading
        // it, live too): a better read is only logged ([histWould]).
        if (was != null && !staleActs()) {
            if (histOrder(h, prev) > histOrder(was, prev)) histWould.putIfAbsent("$day|$u", "$u: a ${if (histRank(h, prev) == 3) "whole" else "better"} " +
                "history arrived; would have replaced the one in use (${staleWhy(was.sessions, prev) ?: "it has no earlier session"}) - the stale-history check is off, so it is kept")
            return false
        }
        hist[u] = h
        return true
    }

    /** 20 Nov 2026: the stale-history check is on ([Book.staleOn]; the book in memory, else the last view). Memory only. */
    private fun staleActs(): Boolean = cache?.staleOn ?: _view.value.staleOn

    /** 20 Nov 2026: each index's better history that came with the check off ("day|index" -> words), logged once by the next pass. */
    private val histWould = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The [histWould] lines not yet logged, put in [b]'s log once a day each; true when one was. */
    private fun logHistWould(b: Book): Boolean {
        var any = false
        for ((k, words) in histWould) {
            histWould.remove(k)
            if ("$k|histWould" in b.decided) continue
            b.decided += "$k|histWould"; note(b, words); any = true
        }
        return any
    }

    /**
     * 4 Nov: the history cache, one small text file per index (market prices only: nothing about the account), for one day.
     * Written and read on the background reads ([prefetchNow]), never on the order or tick path. A file from another day,
     * or one that cannot be read, is ignored (the reads go to Upstox as before).
     */
    private fun histFile(u: String): File? = if (::app.isInitialized) File(File(app.filesDir, "history_cache"), "$u.txt") else null

    private fun barLine(kind: Char, x: Bar) = "$kind,${x.start},${x.open},${x.high},${x.low},${x.close}"

    internal fun writeHistCache(u: String, day: LocalDate, ones: List<Bar>, daily: List<Bar>) {
        val f = histFile(u) ?: return
        f.parentFile?.mkdirs()
        val text = buildString {
            append(day).append('\n')
            ones.forEach { append(barLine('m', it)).append('\n') }
            daily.forEach { append(barLine('d', it)).append('\n') }
        }
        val tmp = File(f.parentFile, "${f.name}.${Thread.currentThread().id}.${System.nanoTime()}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    internal fun readHistCache(u: String, day: LocalDate): Pair<List<Bar>, List<Bar>>? {
        val f = histFile(u)?.takeIf { it.isFile } ?: return null
        val lines = f.readLines()
        if (lines.firstOrNull() != day.toString()) return null
        val ones = ArrayList<Bar>(); val daily = ArrayList<Bar>()
        for (l in lines.drop(1)) {
            val p = l.split(',')
            if (p.size != 6) continue
            val b = Bar(LocalDateTime.parse(p[1]), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
            if (p[0] == "m") ones += b else if (p[0] == "d") daily += b
        }
        return if (ones.isEmpty() && daily.isEmpty()) null else ones to daily
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

    /** [bars]' last whole minute on [now]'s day (null: none). */
    private fun lastMinuteOf(bars: List<Bar>?, now: LocalDateTime): LocalTime? =
        bars?.lastOrNull { it.start.toLocalDate() == now.toLocalDate() }?.start?.toLocalTime()

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

    /**
     * 22 Oct (Boss: "reserve R72's place"): the arm the same-direction cap keeps its last place for at [t] - R72 Breadth Donchian,
     * when it is on (not record only) and a 5-minute bar has just closed (its fill window, [entryMinute]'s 09:20 .. 15:12); else
     * null. Memory only (the paper book's entry door asks it under its lock).
     */
    fun placeKeptFor(t: LocalTime): String? =
        if (_view.value.on(BreadthDonchianArm.SOURCE) && entryMinute(t) && t.isBefore(LocalTime.of(15, 13))) BreadthDonchianArm.LABEL else null

    /**
     * 25 Oct (Boss's decision on the arms audit: "keep it only when R72 has a break due, not on every bar"): the arm the cap keeps its
     * last place for, for an entry betting [dir] at [at] - R72 Breadth Donchian only while it is on, inside its bar-close window
     * ([placeKeptFor]) AND one of its breaks that way is due and not decided yet ([r72Due], from its last entry check). Before R72 has
     * looked at a bar, or when its bar has no breadth break, nothing is kept: another arm's entry is never refused for a break R72
     * does not have. Memory only (the paper book's entry door asks it under its lock).
     */
    fun placeKeptFor(at: LocalDateTime, dir: Int): String? {
        placeKeptFor(at.toLocalTime()) ?: return null
        // Review: R72 has not looked at this bar yet (another arm's pass came first): kept, as before - its break may be due.
        if (r72DueBar != barCloseOf(at)) return BreadthDonchianArm.LABEL
        val until = r72Due[dir] ?: return null
        return if (at.isBefore(until)) BreadthDonchianArm.LABEL else null
    }

    /** The 5-minute bar close (09:20, 09:25, ...) at or before [at]. */
    internal fun barCloseOf(at: LocalDateTime): LocalDateTime {
        val m = at.hour * 60 + at.minute
        val close = 9 * 60 + 15 + ((m - (9 * 60 + 15)).coerceAtLeast(0) / 5) * 5
        return at.toLocalDate().atTime(close / 60, close % 60)
    }

    /** R72's breaks due now by side, with the end of their fill window (25 Oct; [noteR72Due]). Memory only. */
    @Volatile private var r72Due: Map<Int, LocalDateTime> = emptyMap()
    /** The bar close [r72Due] was worked out at (null: none yet). */
    @Volatile private var r72DueBar: LocalDateTime? = null

    /** R72's due breaks from this entry check's reads ([BreadthDonchianArm.dueSides]): kept for [placeKeptFor]. */
    private fun noteR72Due(b: Book, now: LocalDateTime, reads: Reads) {
        if (!b.active(BreadthDonchianArm.SOURCE)) { r72Due = emptyMap(); r72DueBar = barCloseOf(now); return }
        val today = now.toLocalDate()
        val sigs = runCatching { BreadthDonchianArm.signals(seriesAll(today, reads), today) }.getOrNull() ?: return
        // 1 Nov (review): a break the wide-day skip will skip holds no place under the cap (it is never taken).
        val wide = BreadthDonchianArm.UNDERLYINGS.filter { u -> wideWords(b, BreadthDonchianArm.SOURCE, u, today) != null }.toSet()
        r72Due = BreadthDonchianArm.dueSides(sigs, now) { x -> "$today|${BreadthDonchianArm.SOURCE}|${x.underlying}|${x.bar.b}|${x.side}" in b.decided ||
            x.underlying in wide }
        r72DueBar = barCloseOf(now)
    }

    /** TEST ONLY: R72's due breaks as its entry check would have found them at [bar] (null: it has not looked at this bar). */
    internal fun r72DueForTest(due: Map<Int, LocalDateTime>, bar: LocalDateTime? = null) { r72Due = due; r72DueBar = bar }

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
                    // 26 Oct: a read this minute is used again only when it already had the closed bar's last minute; one that
                    // came without it (the feed late) is read again, so a retry ([planRetry]) can find the minute once it lands.
                    lastRead[u]?.takeIf { it.first == minute && DonchianArm.barWhole(lastMinuteOf(it.second, now), now) }?.second
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
        // 6 Nov: R151's R72 picker reads its own history only while it is on (a shadow log; background, never waited for).
        if (_view.value.pickOn || _view.value.w20On) runCatching { prefetchPicker(now) }
        lock.withLock {
            val b = book()
            var changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            changed = runCatching { forward(b) }.getOrDefault(false) || changed
            changed = runCatching { logHistWould(b) }.getOrDefault(false) || changed
            changed = prune(b) || changed
            if (changed) save(b) else publish(b)
        }
        val v = _view.value
        // 30 Oct: no early return for "all off": an arm that is off runs in shadow (its signals kept as would-be trades).
        runCatching { follow(now, v) }
        if (!entryMinute(now.toLocalTime())) { plannedRetry = null; return }
        if (!entryLock.tryLock()) return
        try {
            val reads = runCatching { reads(now, v) }.getOrNull() ?: return
            val gen = wipes
            val (work, n0, log0, sc0) = lock.withLock { val b = book(); Quad(b.copy(), b.positions.size, b.log.size, b.scores.size) }
            // 25 Oct: R72's due breaks noted first, so the cap keeps its place only for a break it really has ([placeKeptFor]).
            runCatching { noteR72Due(work, now, reads) }
            // 19 Oct: an arm set to record only runs here too (its breaks become would-be trades in [buyOne]).
            // 19 Oct (order speed): this bar's due Donchian options priced side by side first, not one after another inside each
            // index's entry (each a network wait when the stream does not have the option yet). Memory and reads only.
            runCatching { prewarm(work, now, reads) }
            val st0 = HashMap<String, String>(work.status)
            var changed = false
            // 22 Oct (Boss: "No breadth donchain ran today, which is our primary arm right now"): R72 Breadth Donchian decides FIRST.
            // It decided last, so at 09:21:24 R52 had already bought its puts in the same pass, the same-direction cap (2 a side)
            // was full, and R72's three puts were kept as would-be trades - its FINNIFTY put held by R52. Now the primary arm takes
            // the cap's places and its options first; the others follow (one R72 already holds becomes their would-be trade).
            // 22 Oct, merge review (CLAUDE.md's LIVE rule): first only while none of the other arms' entries could also be mirrored
            // live - R72 (paper only) taking an option first would skip that arm's live trade; then the order stays as before (R72 last).
            // 22 Oct (arms audit): a record-only arm is never mirrored live ([aRecordOnlyBreakIsNeverMirroredLiveEvenWithItsTradeLiveOn]),
            // so only an arm that is ON counts here - record-only R51 with its "Trade live" on no longer puts R72 last for nothing.
            val r72First = SOURCES.none { it != BreadthDonchianArm.SOURCE && work.armed[it] == true && ExposureCapHost.liveMirrorPossible(it) }
            if (r72First && work.active(BreadthDonchianArm.SOURCE)) changed = runCatching { breadth(work, now, reads) }.getOrElse { work.status["${BreadthDonchianArm.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (work.active(DonchianArm.SOURCE)) changed = runCatching { donchian(work, now, reads) }.getOrElse { work.status["${DonchianArm.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (work.active(OovArm.SOURCE)) changed = runCatching { oov(work, now, reads) }.getOrElse { work.status["${OovArm.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (work.active(Solo2.SOURCE)) changed = runCatching { solo2(work, now, reads) }.getOrElse { work.status["${Solo2.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (!r72First && work.active(BreadthDonchianArm.SOURCE)) changed = runCatching { breadth(work, now, reads) }.getOrElse { work.status["${BreadthDonchianArm.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            // R72 has decided its breaks (bought, refused or let go): the place is no longer kept for them.
            runCatching { noteR72Due(work, now, reads) }
            // 6 Nov: R151's R72 picker logs R72's breaks AFTER every arm's entries (one line per bar; its skips were already made in
            // [breaks], from the same kept scores); each trade gets its score when the pass is merged ([patchPicks]).
            if (work.active(BreadthDonchianArm.SOURCE)) changed = runCatching {
                scoreBreaks(work, now.toLocalDate(), BreadthDonchianArm.signals(seriesAll(now.toLocalDate(), reads), now.toLocalDate()), reads)
            }.getOrDefault(false) || changed
            // 6 Nov: R150 B's W20 labels R51's breaks the same way (a shadow log, after every arm's entries).
            if (work.active(DonchianArm.SOURCE)) changed = runCatching { labelW20(work, now.toLocalDate(), reads) }.getOrDefault(false) || changed
            // 26 Oct (R105 lead 3): a bar this check could not settle (a break due and not decided, or an index's bar not whole
            // yet) is looked at again 10 s on, inside its fill window, not on the next full pass up to a minute later.
            runCatching { planRetry(now, retryEnds(work, now, reads)) }
            if (!changed) return
            lock.withLock {
                // 26 Oct: an erase since the copy was taken ([wipe]): what this pass worked on is not merged into the new book.
                if (gen != wipes) return@withLock
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
                // 6 Nov: R72's trades get their break's picker score (a label only; shadow log).
                runCatching { patchPicks(b, now.toLocalDate()) }
                save(b)
            }
        } finally { entryLock.unlock() }
    }

    private data class Quad(val a: Book, val b: Int, val c: Int, val d: Int)

    // ---- 26 Oct (R105 lead 3): the entry check's quick retry inside a fill window ----------------------------------------

    /**
     * R105 found four Donchian breaks lost to "the fill window passed" (R72's FINNIFTY 09:20 put on 22 Oct; R51's three lines dated
     * "21 Oct", written before R51 was switched off on 19 Oct). The bar-close check runs once, 3 s after the boundary; when an index's
     * last minute had not reached the phone, or a buy found no fresh price, the next try was the full pass up to a minute later, and
     * the window is 3 minutes. Now such a bar is looked at again [DonchianArm.RETRY_SECONDS] on, until it is settled or its window
     * ends. When the entries end the fill windows still open of: each R51 / R72 break due and not decided, and (when one of the five
     * indices' read lacks the closed bar's last minute) the bar itself. Memory only.
     */
    private fun retryEnds(b: Book, now: LocalDateTime, reads: Reads): List<LocalDateTime> {
        val r51 = b.active(DonchianArm.SOURCE)
        val r72 = b.active(BreadthDonchianArm.SOURCE)
        if (!r51 && !r72) return emptyList()
        val today = now.toLocalDate()
        val out = ArrayList<LocalDateTime>()
        val close = barCloseOf(now)
        val indices = ((if (r51) DonchianArm.UNDERLYINGS else emptyList()) + (if (r72) BreadthDonchianArm.UNDERLYINGS else emptyList())).distinct()
        if (DonchianArm.inWindow(close.toLocalTime()) && indices.any { u -> !DonchianArm.barWhole(lastMinuteOf(reads.bars[u], now), now) })
            out += close.plusMinutes(DonchianArm.FILL_SLACK_MINUTES + 1)
        val all = seriesAll(today, reads)
        fun undecided(src: String, x: DonchianArm.Signal) = "$today|$src|${x.underlying}|${x.bar.b}|${x.side}" !in b.decided && DonchianArm.mayFill(x, now)
        if (r51) for (u in DonchianArm.UNDERLYINGS) all[u]?.let { DonchianArm.signals(u, it, today) }.orEmpty()
            .filter { undecided(DonchianArm.SOURCE, it) }.forEach { out += DonchianArm.windowEnd(it) }
        if (r72) runCatching { BreadthDonchianArm.signals(all, today) }.getOrDefault(emptyList())
            .filter { undecided(BreadthDonchianArm.SOURCE, it) }.forEach { out += DonchianArm.windowEnd(it) }
        return out
    }

    /** When the entry check is planned to look again (null: none); kept for the tests and the diagnostics. Memory only. */
    @Volatile internal var plannedRetry: LocalDateTime? = null
        private set
    @Volatile private var retryJob: kotlinx.coroutines.Job? = null

    /** TEST SEAM: true in the JVM tests' app (a planned retry is kept in [plannedRetry], never launched). Debug builds only. */
    @Volatile internal var testNoRetry: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /**
     * The retry itself: one at a time, on this host's background scope (never the main thread, never the order or tick path), a
     * whole [tick] (its exits first, under the book's lock, then the entries with every guard). PAPER ONLY: not while any of these
     * arms can be mirrored live (CLAUDE.md's LIVE rule: a live entry that would have been missed is never added by this change).
     */
    private fun planRetry(now: LocalDateTime, ends: List<LocalDateTime>) {
        if (retryJob?.isActive == true) return
        val bar = barCloseOf(now)
        val (seenBar, n) = retriesOf
        val done = if (seenBar == bar) n else 0
        val at = DonchianArm.nextRetry(now, ends)?.takeIf { done < MAX_RETRIES_A_BAR && !liveMirrorPossibleNow() }
        plannedRetry = at
        if (at == null) return
        retriesOf = bar to done + 1
        if (testNoRetry) return
        val waitMs = java.time.Duration.between(now, at).toMillis().coerceIn(0L, DonchianArm.RETRY_SECONDS * 1000)
        retryJob = background.launch {
            kotlinx.coroutines.delay(waitMs)
            // This retry may plan the next one (its own job is over as far as [planRetry] is concerned).
            retryJob = null
            // Live switched on during the wait: no retry (it would be a live entry the old timing would have missed).
            if (liveMirrorPossibleNow()) { plannedRetry = null; return@launch }
            runCatching { if (wantsPass()) tick() }
        }
    }

    /**
     * Whether any of these arms that is on (or record only) could be mirrored live now. The live flags are read first (the cap's
     * check uses only flags already read, and before anything has read them it says "not live"): this runs on the entries'
     * worker or the retry's background job, never the order or tick path.
     */
    private fun liveMirrorPossibleNow(): Boolean {
        runCatching { ArmLive.liveFlags() }
        val v = _view.value
        // 30 Oct (review): the arms that are ON only - an arm that is off runs in shadow and is never mirrored live.
        return SOURCES.any { s -> v.on(s) && ExposureCapHost.liveMirrorPossible(s) }
    }

    /** At most this many retries for one bar (an index whose feed is down all day costs a few reads a bar, never 17). */
    internal const val MAX_RETRIES_A_BAR = 8
    /** The bar the retries were counted for, and how many. Memory only. */
    @Volatile private var retriesOf: Pair<LocalDateTime?, Int> = null to 0

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
        // 19 Nov (review): the automatic-stop switches read again in the background if the start could not (memory only here).
        runCatching { ArmAutoStopsHost.ensureReady() }
        for (s in SOURCES) {
            if (b.armed[s] != true) continue
            // 19 Nov (Boss's choice, [ArmAutoStopsHost]): with its switch off the bar no longer switches R51 or R72 off ("run until i stop
            // them"); R52 and Solo 2.0 keep theirs. Before the switch is read it waits for a later pass (a switch-off is never undone by
            // itself). Memory only.
            if (!ArmAutoStopsHost.mayLatch(com.optionslab.ira.ArmAutoStops.Rule.FORWARD_BAR, s)) continue
            // 22 Oct (arms audit): only the trades really bought count toward the 40-trade bar and its twice-the-spread test; a
            // would-be trade (the cap, a twin, record only) was never bought and books no entry spread, so it could neither
            // switch the arm off nor pass it. They stay in the record, labelled.
            val c = b.positions.filter { it.arm == s && !it.open && it.shadow == null }
            // 15 Nov: the breakeven rule's trades COUNT here, unlike Liquidity's plain-rules records: this bar is a
            // safety cut-off that switches the arm off on what it really trades (as the 30-60-100 lock's trades count). Leaving them
            // out would keep the bar from ever judging an arm while the switch is on - less protection, not more. Its own-exits record
            // is split out in the diagnostics ([diagLine]).
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

    /**
     * 19 Nov ([ArmAutoStopsHost]'s timeline entry): R51 and R72 when their forward bar switched them off and they are still off, with why;
     * null while the book is not ready. Reads only (a worker thread).
     */
    internal suspend fun forwardBarStopped(): List<String>? {
        if (!ready()) return null
        return lock.withLock {
            val b = book()
            listOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE).filter { b.armed[it] != true && b.paused[it] != null }
                .map { "${label(it)} switched itself off on its forward-test bar (${b.paused[it]})" }
        }
    }

    /** 19 Nov ([ArmAutoStopsHost]): whether R51 or R72 has a trade closed before [day]; null while the book is not ready. Reads only. */
    internal suspend fun tradedBefore(day: LocalDate): Boolean? {
        if (!ready()) return null
        return lock.withLock {
            book().positions.any { (it.arm == DonchianArm.SOURCE || it.arm == BreadthDonchianArm.SOURCE) && it.exitTime?.toLocalDate()?.isBefore(day) == true }
        }
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
                            idx: Double? = null, atr: Double? = null, keys: List<String> = emptyList(), breadth: Boolean? = null,
                            pickSkip: String? = null): Pair<Pos?, String> {
        val today = now.toLocalDate()
        val right = if (side > 0) Right.CE else Right.PE
        val expiry = OrbRules.expiryAfter(today, listed(u).map { it.expiry }.distinct()) ?: return null to "no $u expiry after today is listed"
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return null to "refused: the kill switch is on"
        if (stoppedToday()) return null to "refused: the bot is stopped for today"
        runCatching { DayLockGuard.refusal(false) }.getOrNull()?.let { return null to "refused: $it" }
        var why = "no listed strike"
        // 19 Nov 2026 (audit batch 2): the first strike the paper side could not price or fill ([liveNoPrice]).
        var unpriced: Paper.Contract? = null
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
            // the second is kept as a would-be trade (its record and the R72-vs-R51 comparison still count it). R72 decides
            // first on a bar both break (22 Oct: the primary arm), so R72 holds it and R51 records the would-be.
            // 19 Oct review (CLAUDE.md's LIVE rule): only while this arm's entry cannot also be mirrored live - skipping it would
            // skip its live trade too; then both buy, as before.
            val twin = if (ExposureCapHost.liveMirrorPossible(arm)) null
                else b.positions.firstOrNull { it.open && it.shadow == null && it.symbol == c.symbol && mayShare(arm, it.arm) }
                    ?: _view.value.open.firstOrNull { it.shadow == null && it.symbol == c.symbol && mayShare(arm, it.arm) }
            // 19 Oct (order speed): each order timed from its own decision ([OrderTiming.ownDecision]), not the run's first.
            val step = OrderTiming.ownDecision { buyOne(b, arm, u, c, right, now, setup, idx, atr, keys, breadth, twin, held, pickSkip) }
            why = step.why
            if (step.pos != null || step.stop) return step.pos to step.why
            if (step.noPrice && unpriced == null) unpriced = c
            // Held by another arm and no price for its would-be: never another strike instead (as before), said with the holder.
            if (held != null) {
                liveNoPrice(b, arm, u, c.takeIf { step.noPrice }, right, now, setup, idx, atr, keys, breadth, pickSkip,
                    "${c.symbol} is held by ${label(held.arm)} (${step.why})")?.let { return it to "would-be" }
                return null to "${c.symbol} is held by ${label(held.arm)} (${step.why})"
            }
        }
        liveNoPrice(b, arm, u, unpriced, right, now, setup, idx, atr, keys, breadth, pickSkip, why)?.let { return it to "would-be" }
        return null to why
    }

    /**
     * 19 Nov 2026 (audit batch 2: a live trade was silently skipped when the paper side had no fresh price): while the entry could be copied
     * live, the first strike the paper side could not price or fill ([c]) is kept as a would-be trade at Zerodha's last price
     * ([SHADOW_NOPRICE], [because] why) and its live copy asked ([askLive]; every live gate applies). Null: nothing asked (no such strike,
     * the arm off or skipped by the picker - those stay would-be only - no live path now, or no Zerodha price either). 19 Nov 2026 (B2-B):
     * only with Bot settings' switch on ([setRefusalLive]); off, logged in shadow ([refusalShadow]).
     */
    private suspend fun liveNoPrice(b: Book, arm: String, u: String, c: Paper.Contract?, right: Right, now: LocalDateTime, setup: String?, idx: Double?,
                                    atr: Double?, keys: List<String>, breadth: Boolean?, pickSkip: String?, because: String): Pos? {
        if (c == null || pickSkip != null || b.armed[arm] != true || !ExposureCapHost.liveMirrorPossible(arm)) return null
        // (B2-B: off - the default - shadow: skipped live as before, logged once.)
        if (!b.refusalLiveOn) { refusalShadow(b, arm, c.symbol, "no fresh paper price"); return null }
        val px = runCatching { ArmLive.liveLast(c) }.getOrNull() ?: return null
        val p = wouldBe(b, arm, u, c, right, px, now, setup, idx, atr, keys, breadth, SHADOW_NOPRICE, because, copied = true)
        askLive(arm, c, p)
        return p
    }

    /**
     * One strike's try ([buyOne]): the trade (a real one, or a would-be one), why not, and whether to stop trying strikes. 19 Nov 2026
     * (audit batch 2): [noPrice] - the paper side had no fresh price for it, or its paper buy did not fill.
     */
    private class BuyStep(val pos: Pos?, val why: String, val stop: Boolean, val noPrice: Boolean = false)

    /**
     * [c] for [arm]: its price, a would-be trade when [twin] holds it, the account guard, the MARKET buy (the same-direction cap
     * may refuse it: a would-be trade), and the trade booked at once. Run as its own decision ([buy]).
     */
    private suspend fun buyOne(b: Book, arm: String, u: String, c: Paper.Contract, right: Right, now: LocalDateTime, setup: String?, idx: Double?,
                               atr: Double?, keys: List<String>, breadth: Boolean?, twin: Pos?, held: Pos? = null, pickSkip: String? = null): BuyStep {
        // 19 Oct: the price read in the last few seconds when there is one ([prewarm] read this bar's options side by side before
        // the entries); the fill itself is priced fresh in Paper.place (2 s at most), as before.
        val q = runCatching { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) }.getOrNull()
        // 28 Oct (Boss: "Yes fix finnifty"): an option with a live bid and ask but no last price (an untraded FINNIFTY strike) is
        // priced at the ask a buyer pays, on a sane book only ([Paper.entryPrice]); otherwise the line says why there is no price.
        val ref = Paper.entryPrice(c, q) ?: return BuyStep(null, "${c.symbol}: no fresh price" +
            (Paper.noPriceWhy(c.symbol)?.let { " ($it)" } ?: ""), false, noPrice = true)
        // 19 Oct (Boss: "Yes, keep only breadth donchain"): an arm set to record only buys nothing - its entry is a would-be trade,
        // priced at the ask when the quote has one (a buyer pays it), else the last price, as the cap's. Never mirrored live, never
        // on the account (so never counted by the same-direction cap and never a twin for R72), no notice.
        // 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): every arm that is off.
        if (b.armed[arm] != true)
            return BuyStep(wouldBe(b, arm, u, c, right, q?.ask?.takeIf { it > 0 } ?: ref, now, setup, idx, atr, keys, breadth, SHADOW_OFF,
                "switched off, it runs in shadow"), "would-be", true)
        // 6 Nov (Boss's exception): R151's picker skipped it - a would-be trade at the ask, nothing bought, never copied live. (After the
        // arm-off check: an arm that is off keeps every break as its own would-be, so its record stays whole.)
        if (pickSkip != null)
            return BuyStep(wouldBe(b, arm, u, c, right, q?.ask?.takeIf { it > 0 } ?: ref, now, setup, idx, atr, keys, breadth, SHADOW_PICK, pickSkip),
                "would-be", true)
        // 22 Oct: another of these arms holds this option (not R51 / R72's twin): a would-be trade at the ask (a buyer pays it), else
        // the last price, as the cap's; the line names the arm.
        // 19 Nov 2026 (audit batch 2): while the entry could be copied live, a paper-only refusal never skips the live trade - the paper
        // side is kept as a would-be trade and its live copy is asked ([askLive]; every live gate still applies).
        // (B2-B: only with Bot settings' switch on, Boss's PIN - [setRefusalLive]; off, shadow: skipped live as before and logged.)
        val canLive = ExposureCapHost.liveMirrorPossible(arm)
        val liveCopy = b.refusalLiveOn && canLive
        if (held != null) {
            val p = wouldBe(b, arm, u, c, right, q?.ask?.takeIf { it > 0 } ?: ref, now, setup, idx, atr, keys, breadth, SHADOW_HELD,
                "${c.symbol} is held by ${label(held.arm)}", copied = liveCopy)
            if (liveCopy) askLive(arm, c, p) else if (canLive) refusalShadow(b, arm, c.symbol, "held on paper by ${label(held.arm)}")
            return BuyStep(p, "would-be", true)
        }
        if (twin != null) {
            // Priced at the twin's own real fill (its spread and slippage paid), never a better price than the account got.
            val p = wouldBe(b, arm, u, c, right, twin.entry, now, setup, idx, atr, keys, breadth, SHADOW_TWIN, "${label(twin.arm)} already holds ${c.symbol}")
            return BuyStep(p, "would-be", true)
        }
        // 1 Nov (R142 NP5-52; CLAUDE.md's LIVE rule): R52's 09:25 exit goes on a trade only while it cannot be copied live - its
        // paper exit would sell the live copy early too. A trade that may be copied live keeps the plain exits; the early exit is
        // only logged for it (live in shadow, [np5Shadow]). Memory only (the live flags already read).
        val np5LiveCopy = arm == OovArm.SOURCE && b.np5On && mayCopyLive(arm)
        // 16 Oct: the account guard's view shares prices read in the last [Paper.SHARED_QUOTE_MS] (it re-read every held
        // symbol before each entry); the fill itself is priced fresh in Paper.place (2 s at most).
        val snap = runCatching { Paper.snapshot(reuseQuotesMs = Paper.SHARED_QUOTE_MS) }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", 1, ref), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) {
            // 19 Nov 2026 (audit batch 2): the PAPER account's guard never skips the live trade ([askLive]; the live order has its own guard).
            if (liveCopy) {
                val p = wouldBe(b, arm, u, c, right, q?.ask?.takeIf { it > 0 } ?: ref, now, setup, idx, atr, keys, breadth, SHADOW_GUARD,
                    "refused by the paper account guard: " + refusals.joinToString(" "), copied = true)
                askLive(arm, c, p)
                return BuyStep(p, "would-be", true)
            }
            if (canLive) refusalShadow(b, arm, c.symbol, "refused by the paper account guard")
            return BuyStep(null, "refused by the account guard: " + refusals.joinToString(" "), true)
        }
        // 22 Oct: the guard's verdict stamped with this order's fingerprint ([OrderVerdicts]; the premium stop rests on the fill).
        val verdict = OrderVerdicts.stamp(OrderVerdicts.paperFields(c, "BUY", 1, "MARKET", "MIS", null, null, now.toLocalDate(), arm), refusals, "paper guard")
        runCatching { LiveRecorder.signal(label(arm), c.symbol, u, right.name, ref, paper = true) }
        inFlight += c.symbol
        var why = ""
        val p: Pos = (try {
            val r = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, known = q, auto = label(arm), mirror = arm, verdict = verdict, source = arm)
            // 19 Oct: the same-direction cap refused it: nothing was bought; kept as a would-be trade at the price seen.
            // Priced at the ask when the quote has one (a buyer pays it), else the last price.
            // 25 Oct: one of Boss's new risk rules refused it ([BossRiskHost]): kept the same way, labelled with that rule.
            // (19 Nov 2026, audit batch 2: kept as it was. While the entry may be copied live the cap and Boss's paper rules only log
            // ([ExposureCapHost.liveMirrorPossible] in [Paper.place] and [BossRiskHost.paperSize]), so they never skip its live trade; one
            // they refuse now was priced on the book alone, which is never mirrored live (28 Oct).)
            if (r.capped) return BuyStep(wouldBe(b, arm, u, c, right, q?.ask?.takeIf { it > 0 } ?: ref, now, setup, idx, atr, keys, breadth,
                if (r.rule != null) SHADOW_RULE else SHADOW_CAP, r.message), r.message, true)
            val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
            if (fill == null) {
                r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
                why = "${c.symbol} not filled (${r.message})"
                null
            } else {
                val charges = Paper.state.trades.filter { it.orderId == r.orderId }.sumOf { it.charges.toDouble() }
                val pos = Pos(arm, u, c.symbol, right.name, fill.quantity, fill.price, now, r.orderId, setup, ref, idx, atr, charges = charges, lock = b.lockOn,
                    l25 = b.lockOn && b.l25On, breadth = breadth, st = arm == DonchianArm.SOURCE && b.stOn,
                    np5 = arm == OovArm.SOURCE && b.np5On && !np5LiveCopy, fx = StepTrailExits.forEntry(arm), lot = c.lotSize.takeIf { it > 0 })
                    // 16 Nov: a new trade with the rule takes its fourth version (the step lock) too while that is on (Bot settings).
                    .let { it.copy(sl = it.fx && StepTrailExits.lockOn()) }
                b.positions += pos
                b.marks[c.symbol] = fill.price
                b.decided += keys
                // 16 Oct (the unwatched re-entry): booked in the book itself at once, never only on the entries' copy until the
                // end of the pass - its stop, target and time exit run from the next minute-lane look, and a pass cut short or
                // a restart after the fill can never leave it held with no record here. Not cancellable between the two.
                // 1 Nov (review): the mark of an R52 trade that keeps its plain exits because it may be copied live ([np5Shadow]) is
                // booked with it, in the book itself.
                val marks = if (np5LiveCopy) listOf(np5LiveKey(pos)) else emptyList()
                b.decided += marks
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(pos, keys + marks) }
                // 18 Oct: its live path ([ArmLive]): the same option and lots, its stop resting at Zerodha - only in Live with AI
                // trades on, its own "Trade live" on and every guard clear (memory only here; the order goes on a worker).
                // 15 Nov: [Pos.fx] goes with it - the live trade takes its own breakeven rule on ITS fill, lots and best price.
                runCatching { ArmLive.entered(ArmLive.Ask(arm, mirrorRef(pos), c, 1, pos.qty, pos.entry, stop = premiumStop(pos), fixed = pos.fx, lock = pos.fx && pos.sl)) }
                // 22 Oct: the time machine's reality check (shadow only; background): the arm's premium stop and target, out by its square-off.
                runCatching {
                    val x = exitsOf(pos)
                    TimeMachineHost.afterEntry(TimeMachineHost.Entry(arm, label(arm), mirrorRef(pos), u, if (right == Right.CE) 1 else -1, now,
                        x.stop?.let { it * pos.entry }, x.target?.let { it * pos.entry }, premium = true, exitAt = x.squareOff))
                }
                r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${label(arm)} · entry") } }
                runCatching { Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", "${label(arm)} · entry", r.orderId) }
                pos
            }
        } finally { inFlight -= c.symbol }) ?: return BuyStep(null, why, false, noPrice = true)   // (19 Nov 2026, audit batch 2: not filled on paper: [liveNoPrice])
        runCatching { Notifier.post(app, NOTICE + SOURCES.indexOf(arm) * 5 + ALL_INDICES.indexOf(u).coerceAtLeast(0), Notifier.BUY,
            "${label(arm)} bought ${c.symbol} · Paper",
            "Bought %d @ %s. %s. %s.".format(Locale.ENGLISH, p.qty, px(p.entry), exitWords(p), PassedArms.PASSED), "almanac") }
        note(b, "${label(arm)} bought ${c.symbol} ${p.qty} @ ${px(p.entry)} · paper" + (setup?.let { " ($it)" } ?: ""))
        if (np5LiveCopy) note(b, "${label(arm)}: ${c.symbol} keeps its plain exits - it may be copied live, so the 09:25 no-progress exit " +
            "is only logged for it (live in shadow)")
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
    /** [Pos.shadow]: one of 25 Oct's risk rules refused the entry (the account or weekly stop, opposite bets, the 2% cap). */
    const val SHADOW_RULE = "rule"
    /**
     * 6 Nov (Boss's exception, CLAUDE.md): R151's picker skipped the break (its score under the arm's threshold), on paper and so
     * never copied live; kept as a would-be trade so the skips' own record shows what they saved or cost.
     */
    const val SHADOW_PICK = "pick"
    /** 19 Nov 2026 (audit batch 2): the paper account guard refused the paper entry (a would-be trade; its live copy asked: [Pos.copied]). */
    const val SHADOW_GUARD = "guard"
    /** 19 Nov 2026 (audit batch 2): no fresh paper price for the entry (a would-be trade at Zerodha's price; its live copy asked). */
    const val SHADOW_NOPRICE = "noprice"

    /** Why a would-be trade was not bought, in words. */
    fun shadowWords(p: Pos): String = (when (p.shadow) {
        SHADOW_GUARD -> "would-be: refused by the paper account guard"
        SHADOW_NOPRICE -> "would-be: no fresh paper price (priced at Zerodha's last price)"
        else -> shadowWordsBefore(p)
    }) + if (p.copied) " · its live copy was asked (every live gate applies)" else ""

    private fun shadowWordsBefore(p: Pos): String = when (p.shadow) {
        SHADOW_TWIN -> "would-be: the other Donchian arm already held this option"
        SHADOW_CAP -> "would-be: refused by the same-direction cap"
        SHADOW_OFF -> "would-be: switched off, it runs in shadow"
        SHADOW_HELD -> "would-be: another arm already held this option"
        SHADOW_RULE -> "would-be: refused by a risk rule (account or weekly stop, opposite bets or the 2% cap)"
        SHADOW_PICK -> "would-be: skipped by R151's picker (failed gates, Boss's exception)"
        else -> ""
    }

    /**
     * A WOULD-BE trade of [arm] in [c] at [ref] (19 Oct, [Pos.shadow] = [kind]): nothing is bought; booked here like a real trade
     * (its decision made, its caps counted, its exits run on the prices seen), never mirrored live, no notice. [because]: why.
     */
    private suspend fun wouldBe(b: Book, arm: String, u: String, c: Paper.Contract, right: Right, ref: Double, now: LocalDateTime, setup: String?,
                                idx: Double?, atr: Double?, keys: List<String>, breadth: Boolean?, kind: String, because: String,
                                /** 19 Nov 2026 (audit batch 2): its live copy is asked ([Pos.copied]; no R52 09:25 exit then, as a real copied trade). */
                                copied: Boolean = false): Pos {
        runCatching { Paper.rememberContract(c) }
        val pos = Pos(arm, u, c.symbol, right.name, c.lotSize, ref, now, null, setup, ref, idx, atr, lock = b.lockOn, l25 = b.lockOn && b.l25On,
            breadth = breadth, st = arm == DonchianArm.SOURCE && b.stOn, shadow = kind, np5 = arm == OovArm.SOURCE && b.np5On && !copied,
            fx = StepTrailExits.forEntry(arm), lot = c.lotSize.takeIf { it > 0 }, copied = copied,
            // (22 Nov 2026, B6 review, item 6: its own id - its live copy's reference, never shared with another would-be trade of the pass.)
            wb = java.util.UUID.randomUUID().toString()).let { it.copy(sl = it.fx && StepTrailExits.lockOn()) }
        b.positions += pos
        if (c.symbol !in b.marks) b.marks[c.symbol] = ref
        b.decided += keys
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(pos, keys) }
        note(b, "${label(arm)} would-be ${c.symbol} ${pos.qty} @ ${px(ref)} (not bought: $because) · labelled apart")
        return pos
    }

    /**
     * 19 Nov 2026 (audit batch 2: a live R51 / R72 trade was silently skipped because another arm held the option on paper, or a paper-only
     * rule refused the paper trade): the live copy of the would-be trade [pos] ([Pos.copied]) asked exactly as a paper fill asks it - the
     * same signal and strike, through every live gate ([ArmLive.entered]: Live, AI trades go live, the arm's own switch and shadow, the entry
     * door, the guards, the funds check). Not a new skip: it removes one. Memory only here (the order goes on a worker).
     */
    private fun askLive(arm: String, c: Paper.Contract, pos: Pos) {
        runCatching { ArmLive.entered(ArmLive.Ask(arm, mirrorRef(pos), c, 1, pos.qty, pos.entry, stop = premiumStop(pos), fixed = pos.fx, lock = pos.fx && pos.sl)) }
    }

    /**
     * 19 Nov 2026 (audit batch 2, B2-B): a paper-only refusal of [arm]'s entry on [symbol] ([why]) that could have gone live, logged in shadow
     * (the switch off: [setRefusalLive]) - once a day for the arm and option. Nothing is sent.
     */
    private fun refusalShadow(b: Book, arm: String, symbol: String, why: String) {
        val day = now0().toLocalDate().toString()
        val line = "${label(arm)}: $symbol $REFUSAL_MARK ($why; switch off, shadow - nothing sent)"
        if (b.log.none { it.startsWith(day) && it.endsWith(line) }) note(b, line)
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

    /** 16 Nov: the fourth version's lock [p] entered with (R51 / R72's ₹500 step lock), or null: V4 alone, or not a rule trade. */
    private fun lockOf(p: Pos): StepTrail.Lock? = if (p.fx && p.sl) StepTrail.lockFor(p.arm) else null

    /**
     * 15 Nov: Boss's stop rule a trade entered with (breakeven at +10%; 16 Nov: and the step lock from +₹1,000 a lot), at its own levels
     * and labelled (null: none).
     */
    private fun fixedWords(p: Pos): String? {
        if (!p.fx || !StepTrail.covers(p.arm)) return null
        val lot = lotOf(p)
        val lk = lockOf(p)
        val s = StepTrail.stopLevel(p.entry, lot)
        val be = StepTrail.breakevenLevel(p.entry, lot)
        val q = p.qty.coerceAtLeast(1)
        val first = lk?.let { StepTrail.lockTrigger(p.entry, lot, q, 0) }
        val keep = lk?.let { StepTrail.lockKeep(p.entry, lot, q, 0, it) }
        return StepTrail.labelOf(lk) + listOfNotNull(s?.let { "stop ${px(it)} where tighter" },
            be?.let { "breakeven ${px(it)} from ${px(StepTrail.breakevenTrigger(p.entry))}" },
            if (first != null && keep != null) "step lock ${px(keep)} from ${px(first)}, then up ₹500 a lot a step" else null)
            .joinToString(", ", " (", ")").takeIf { s != null || be != null }.orEmpty()
    }

    private fun exitWords(p: Pos): String = listOfNotNull(exitWordsOwn(p), fixedWords(p)).joinToString("; ")

    private fun exitWordsOwn(p: Pos): String = when {
        p.setup == Solo2.Setup.S1.key -> "Out on SoloMidday's index stop, at breakeven or at 14:30" +
            if (p.lock) "; on the premium, ${com.optionslab.ira.PassedArmsTalk.ladderWords(p.l25)}" else ""
        p.lock -> exitsOf(p).let { x ->
            listOfNotNull(x.stop?.let { "stop −%.0f%%".format(Locale.ENGLISH, it * 100) }, com.optionslab.ira.PassedArmsTalk.ladderWords(p.l25),
                noProgressWords(x), x.tmaxMinutes?.let { "out after $it minutes" }, "square-off ${x.squareOff}").joinToString(", ")
        }
        else -> exitsOf(p).let { x ->
            listOfNotNull(x.stop?.let { "stop −%.0f%%".format(Locale.ENGLISH, it * 100) }, x.target?.let { "target +%.0f%%".format(Locale.ENGLISH, it * 100) },
                x.steps.firstOrNull()?.let { (t, k) -> "+%.0f%% locked once +%.0f%%".format(Locale.ENGLISH, k * 100, t * 100) },
                noProgressWords(x), x.tmaxMinutes?.let { "out after $it minutes" }, "square-off ${x.squareOff}").joinToString(", ")
        }
    }

    /** 1 Nov (R142 NP5-52): the no-progress exit in words ("out after 5 minutes unless up 5%"); null when the trade has none. */
    private fun noProgressWords(x: PassedArms.PremiumExits): String? =
        x.noProgress?.let { "out after %d minutes unless up %.0f%%".format(Locale.ENGLISH, it.minutes, it.gain * 100) }

    /**
     * Whether S1's premium backstop ([S1_BACKSTOP]) judges it at [now]: its index minutes [ones] unread, or its levels ([idx], [atr])
     * unknown, or (20 Nov 2026, audit B5 item 8) the newest minute over 3 minutes old ([com.optionslab.ira.CandleFresh.indexStale]).
     */
    internal fun s1Backstop(ones: List<Bar>, idx: Double?, atr: Double?, now: LocalDateTime): Boolean =
        ones.isEmpty() || idx == null || atr == null || com.optionslab.ira.CandleFresh.indexStale(ones.maxOfOrNull { it.start }, now)

    /** S1's premium backstop when its index cannot be read: the Solo layer's -30% stop, SoloMidday's 14:30. */
    private val S1_BACKSTOP = PassedArms.PremiumExits(0.30, null, null, emptyList(), SoloMidday.EXIT)

    private fun exitsOf(p: Pos): PassedArms.PremiumExits = when (p.arm) {
        DonchianArm.SOURCE -> DonchianArm.EXITS
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.EXITS
        OovArm.SOURCE -> OovArm.EXITS
        else -> Solo2.exits(Solo2.Setup.entries.firstOrNull { it.key == p.setup } ?: Solo2.Setup.S2)
    }.let { if (p.lock) PassedArms.withLock(it, p.l25) else it }
        // 1 Nov (R142 NP5-52): the 09:25 no-progress exit, on the trades that entered with it.
        .let { if (p.np5 && p.arm == OovArm.SOURCE) it.copy(noProgress = OovArm.NO_PROGRESS) else it }
        // 15 Nov (Boss's exception, failed gates): the breakeven rule on the R51 / R72 trades that entered with it ([Pos.fx]); the
        // walk keeps the arm's own stop and takes the breakeven as a lock (up only), beside the arm's own exits.
        // 16 Nov: with the fourth version's step lock when it entered with it ([Pos.sl]), on its own quantity (per lot).
        .let { if (p.fx && StepTrail.covers(p.arm)) it.copy(stepTrail = PassedArms.StepTrailOn(lotOf(p), lock = lockOf(p), qty = p.qty.coerceAtLeast(1))) else it }
        // (16 Nov: on the quantity bought - a paper R51 / R72 trade is never sold in part, so its quantity is its entry quantity.)

    /**
     * 15 Nov: [p]'s lot size (the breakeven rule is per lot): the one kept at its entry; else its paper contract's (the paper book,
     * read already on this worker); else its quantity (a tighter stop, never a looser one).
     */
    private fun lotOf(p: Pos): Int = p.lot?.takeIf { it > 0 } ?: runCatching { Paper.contractOf(p.symbol)?.lotSize }.getOrNull()?.takeIf { it > 0 }
        ?: p.qty.coerceAtLeast(1)

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
        // 20 Nov 2026 (audit B5, item 1): each index's stale history read BEFORE the series is built, so a whole read landing in between
        // never makes a series built on the stale one look whole (a whole history is never replaced by a worse one: [keepHist]).
        val staleOf = DonchianArm.UNDERLYINGS.associateWith { historyStale(it, today) }
        val all = seriesAll(today, reads)
        // 16 Oct (R72): each of its breaks labelled with whether breadth confirmed it (Boss's comparison; never changes a trade).
        val confirmed = runCatching { BreadthDonchianArm.signals(all, today).map { Triple(it.underlying, it.bar.b, it.side) }.toSet() }.getOrNull()
        var changed = false
        for (u in DonchianArm.UNDERLYINGS) {
            val s = all[u] ?: continue
            // 16 Oct (R73 T7, Boss): the Supertrend filter is R51's only (R73 found it does not help R72's breadth breaks), so it
            // is passed here and not inside [breaks]. Its series is built only when the filter is on (memory only).
            // 4 Nov (review): whether the history was still loading, read BEFORE the series is built, so a read finishing in
            // between never turns "not loaded yet" into "cannot be judged".
            val stPending = b.stOn && historyPending(u, today)
            val st = if (b.stOn) todayBars(u, today, reads)?.let { stSeries(u, it) }.orEmpty() else null
            val gate: ((DonchianArm.Signal) -> Boolean?)? = st?.let { ser -> { x: DonchianArm.Signal -> DonchianArm.supertrendAgrees(ser, x) } }
            val sigs = DonchianArm.signals(u, s, today)
            // 20 Nov 2026 (audit B5, item 1): a break whose channel reads an earlier session that does not stand ([staleWhy]).
            val stale = staleOf[u]
            val histStale: ((DonchianArm.Signal) -> String?)? = stale?.let { why -> { x: DonchianArm.Signal ->
                if (readsHistory(s, today, x.bar.b)) "$u's earlier sessions are not whole ($why)" else null } }
            changed = breaks(b, src, u, sigs, now, gate, stPending = stPending,
                confirm = { x -> confirmed?.let { Triple(u, x.bar.b, x.side) in it } }, pickSkip = pickSkipR51(b, today, sigs, gate, reads), histStale = histStale) || changed
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
        // 20 Nov 2026 (audit B5, item 1): a breadth break counts all five indices' channels, so any of them reading an earlier session
        // that does not stand ([staleWhy]) on that bar holds it. Read BEFORE the series is built (as [donchian]).
        val staleBy = BreadthDonchianArm.UNDERLYINGS.mapNotNull { i -> historyStale(i, today)?.let { i to it } }
        val all = seriesAll(today, reads)
        val sigs = BreadthDonchianArm.signals(all, today).groupBy { it.underlying }
        val pickSkip = pickSkipR72(b, today, sigs, reads)
        val histStale: ((DonchianArm.Signal) -> String?)? = if (staleBy.isEmpty()) null else { x: DonchianArm.Signal ->
            staleBy.firstOrNull { (i, _) -> all[i]?.let { readsHistory(it, today, x.bar.b) } == true }?.let { (i, why) -> "$i's earlier sessions are not whole ($why)" } }
        var changed = false
        for (u in BreadthDonchianArm.UNDERLYINGS) {
            if (reads.bars[u] == null) continue
            val (skip, noted) = wideSkip(b, src, u, today, sigs[u].orEmpty().isNotEmpty())
            changed = breaks(b, src, u, sigs[u].orEmpty(), now, skipRule = skip, pickSkip = pickSkip, histStale = histStale) || noted || changed
        }
        return changed
    }

    /**
     * 1 Nov (R140 FB-R72-A10, Boss: "Make the change the arms"): R72's wide-day skip for [u] today, as [breaks]' skip - the reason a
     * break is skipped, or null (taken). Off, or the ratio unknown (no daily candles or no previous session read yet): never skips.
     * CLAUDE.md's LIVE rule: while R72 may be copied live, a skip would skip its live trade too - the break is taken as before
     * and the skip is only said on its status line (live in shadow). Memory only (the history read in the background, the
     * live flags already read).
     */
    private fun wideSkip(b: Book, src: String, u: String, today: LocalDate, due: Boolean): Pair<((DonchianArm.Signal) -> String?)?, Boolean> {
        val words = wideDayWords(b, u, today) ?: return null to false
        if (mayCopyLive(src)) {
            // Said once a day per index, when a break is there to take (live in shadow: nothing skipped).
            val k = "$today|$src|$u|wide_shadow"
            if (!due || k in b.decided) return null to false
            b.decided += k
            note(b, "${label(src)} $u: wide-day skip in shadow - $words; its breaks are taken as before because it may be copied live (logged only)")
            return null to true
        }
        return { _: DonchianArm.Signal -> "no new break $words" } to false
    }

    /** Why [u]'s breaks are skipped today on paper ([wideSkip]'s skip; null: taken, or only logged while R72 may be copied live). */
    private fun wideWords(b: Book, src: String, u: String, today: LocalDate): String? =
        wideDayWords(b, u, today)?.takeIf { !mayCopyLive(src) }

    /** The wide-day rule's words for [u] today when it holds (on, the ratio known and at the cut); null otherwise. */
    private fun wideDayWords(b: Book, u: String, today: LocalDate): String? {
        if (!b.wideOn) return null
        val h = history(u, today) ?: return null
        // 1 Nov (review): yesterday is the trading calendar's previous session, so candles that lag a session are never read as it.
        val ratio = BreadthDonchianArm.wideRatio(h.daily, previousSession(today)) ?: return null
        if (!BreadthDonchianArm.wideDay(ratio)) return null
        return "the day after a very wide day (yesterday's range %.2fx its 20-session average, cut %.2f; R140 FB-R72-A10)"
            .format(Locale.ENGLISH, ratio, BreadthDonchianArm.WIDE_CUT)
    }

    /** The trading session before [today] by the market calendar (weekends and NSE holidays skipped); null when none within 10 days. */
    internal fun previousSession(today: LocalDate): LocalDate? {
        var d = today.minusDays(1)
        repeat(10) {
            if (runCatching { Market.isTradingDay(d) }.getOrDefault(false)) return d
            d = d.minusDays(1)
        }
        return null
    }

    /**
     * 1 Nov (review): whether [arm]'s entry may be copied live now, for the two 1 Nov paper rules (CLAUDE.md's LIVE rule: they act
     * only when it cannot). The live flags are read afresh first (the entries' worker, never the order or tick path), and while
     * the live book is not read yet (its switches unknown) a Live account with AI trades on counts as "may" - never "cannot".
     */
    private fun mayCopyLive(arm: String): Boolean {
        val (app, ai) = runCatching { ArmLive.liveFlags() }.getOrDefault(false to false)
        if (ExposureCapHost.liveMirrorPossible(arm)) return true
        return app && ai && runCatching { !ArmLive.view.value.loaded }.getOrDefault(true)
    }

    /**
     * Donchian's taking of [sigs] on [u] for [src] (R51's own, and R72's): its expiry day trades nothing; each break decided once;
     * the 2-minute fill slack, one position a side and 2 a side a day in [src]'s own rows; the buy. [confirm]: a bought trade's
     * R72 label (R51's only). [gate]: R73 T7's Supertrend filter (R51's only, when on): a break it does not pass (false: against
     * it; null: cannot be judged) is skipped and decided, before the day's caps as the research filtered before taking.
     */
    private suspend fun breaks(b: Book, src: String, u: String, sigs: List<DonchianArm.Signal>, now: LocalDateTime,
                               gate: ((DonchianArm.Signal) -> Boolean?)? = null,
                               confirm: (DonchianArm.Signal) -> Boolean? = { null },
                               /** 1 Nov: R72's wide-day skip ([wideSkip]): why a break is skipped, null to take it. */
                               skipRule: ((DonchianArm.Signal) -> String?)? = null,
                               /** 4 Nov: the gate's history was still loading when its series was built (after a restart). */
                               stPending: Boolean = false,
                               /** 6 Nov (Boss's exception): R151's picker - why a break is skipped (kept as a would-be trade), null to take it. */
                               pickSkip: ((DonchianArm.Signal) -> String?)? = null,
                               /**
                                * 20 Nov 2026 (audit B5, item 1): why a break's channel reads an earlier session that does not stand ([staleWhy]);
                                * null: it does not (or none is in). On ([Book.staleOn]) such a break waits; off it is logged in shadow.
                                */
                               histStale: ((DonchianArm.Signal) -> String?)? = null): Boolean {
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
            if (!DonchianArm.mayFill(s, now)) {
                // 20 Nov 2026 (audit B5, item 1): a break the stale-history check held to its window's end is let go as one the arm would
                // have taken: it holds its side and counts in the day's caps ([staleHeld]), so the check never frees a place for a trade
                // the arm would not have made (it only removes trades).
                // (Held: marked in the book when it first waited, so a restart keeps it - and still held at the end, not tried and refused
                // since the history came whole (after a restart that is not known: held). Only one the arm's own filters would have taken:
                // Supertrend, the wide-day skip and the caps.)
                if (b.staleOn && "$dk|$STALE_WAIT" in b.decided && tried[dk].let { it == null || it.startsWith(STALE_TRIED) } && (gate == null || runCatching { gate(s) }.getOrNull() == true) &&
                    runCatching { skipRule?.invoke(s) }.getOrNull() == null &&
                    DonchianArm.mayTake(s, openSides(b, src, u, today, sigs, s), takenToday(b, src, u, today)) == null) b.decided += "$dk|$STALE_HELD"
                b.decided += dk; changed = say(b, key, DonchianArm.missedWhy(s, tried.remove(dk), looksOf(u), othersOf(src, u)), final = true) || changed; continue
            }
            // 20 Nov 2026 (audit B5, item 1: the history was never checked for yesterday; an old one made false breaks from 09:20 to 10:55,
            // and they could go live): a break whose 20-bar channel reads an earlier session that does not reach the last trading day.
            // On ([Book.staleOn], Bot settings): not decided - it waits while the history is read again ([prefetch], 1, 2, 5 minutes) and the
            // next look inside its fill window judges it; the window ending lets it go, said with this reason ([DonchianArm.missedWhy]). It
            // only skips (lowers risk), on paper and live alike. Off (the default): shadow - logged once, taken as before (CLAUDE.md: a new
            // live skip starts in shadow).
            val staleBreak = histStale?.let { f -> runCatching { f(s) }.getOrNull() }
            if (staleBreak != null) {
                val what = "${s.entryTime.toLocalTime()} ${if (s.side > 0) "call" else "put"} break"
                if (b.staleOn) {
                    tried[dk] = "$STALE_TRIED: $staleBreak"
                    if (b.decided.add("$dk|$STALE_WAIT")) changed = true
                    changed = say(b, key, "$what: waiting - $staleBreak; the history is read again (judged on the next look)") || changed
                    continue
                }
            }
            // (Off - shadow: logged once, only where the break goes on to be bought, after the arm's own filters and caps.)
            val staleShadow = staleBreak?.takeIf { "$dk|stale" !in b.decided }?.let { why ->
                "${label(src)} $u ${s.entryTime.toLocalTime()} ${if (s.side > 0) "call" else "put"} break: $STALE_MARK - $why (the check is off: shadow, taken as before)" }
            // 16 Oct (R73 T7): with the filter on, a break is taken only when Supertrend (10, 3) agrees; one it is against, or
            // cannot judge (no earlier session in, or no direction yet), is skipped - it only ever skips (lowers risk), never
            // adds. Decided once, before the day's caps, as the research filtered the signals before taking them.
            if (gate != null) {
                val ag = gate(s)
                // 4 Nov: after a restart the earlier sessions may still be loading (from the phone's file or Upstox): the break
                // is not decided yet, and the next look inside its fill window judges it (a missed window is said as before).
                if (ag == null && stPending) {
                    tried[dk] = "the earlier sessions were still loading after a restart, so Supertrend could not judge it"
                    changed = say(b, key, "${s.entryTime.toLocalTime()} ${if (s.side > 0) "call" else "put"} break: waiting for the earlier sessions to load (judged on the next look)") || changed
                    continue
                }
                if (ag != true) {
                    b.decided += dk
                    if (ag == null) b.status[ST_UNJUDGED_KEY] = "$today: ${stUnjudgedOn(b.status, today) + 1} skipped, Supertrend could not judge (no earlier session in)"
                    changed = say(b, key, "${s.entryTime.toLocalTime()} ${if (s.side > 0) "call" else "put"} break skipped: " +
                        if (ag == false) "Supertrend (10, 3) is against it (R73 T7)" else ST_UNJUDGED, final = true) || changed
                    continue
                }
            }
            // 1 Nov (R140 FB-R72-A10): skipped and decided before the day's caps, as the research dropped the candidates before taking.
            val skipWhy = skipRule?.invoke(s)
            if (skipWhy != null) {
                b.decided += dk
                changed = say(b, key, "${s.entryTime.toLocalTime()} ${if (s.side > 0) "call" else "put"} break skipped: $skipWhy", final = true) || changed
                continue
            }
            val open = openSides(b, src, u, today, sigs, s)
            val taken = takenToday(b, src, u, today)
            val skip = DonchianArm.mayTake(s, open, taken)
            // 6 Nov (Boss's exception, CLAUDE.md: "i want live"): R151's picker skips a break it scores under the arm's threshold, on
            // paper and live alike. It is decided AFTER the day's caps and the skipped break is kept as a would-be trade
            // ([SHADOW_PICK]) that holds its side and counts in the caps just as the trade would have: so the picker only ever REMOVES
            // a trade the arm would have made - it can never free a place for one the arm would not have made (CLAUDE.md: never adds).
            val pickWhy = if (skip != null) null else runCatching { pickSkip?.invoke(s) }.getOrNull()
            if (pickWhy != null) {
                val (p, why) = buy(b, src, u, s.side, listOf(s.strike), now, keys = listOf(dk), breadth = confirm(s), pickSkip = pickWhy)
                if (p != null) { b.decided += dk; tried.remove(dk) } else tried[dk] = why
                changed = say(b, key, "${s.entryTime.toLocalTime()} ${if (s.side > 0) "call" else "put"} break skipped: $pickWhy" +
                    (if (p != null) " (kept as a would-be trade)" else "; its would-be trade is tried again ($why)"), final = p != null) || changed
                continue
            }
            if (skip != null) { b.decided += dk; changed = say(b, key, "${s.entryTime.toLocalTime()} break skipped: $skip") || changed; continue }
            staleShadow?.let { b.decided += "$dk|stale"; note(b, it); changed = true }
            val (p, why) = buy(b, src, u, s.side, listOf(s.strike), now, keys = listOf(dk), breadth = confirm(s))
            changed = true
            if (p != null) { b.decided += dk; tried.remove(dk); say(b, key, takenWords(p, "${s.entryTime.toLocalTime()} break")) }
            else { tried[dk] = why; say(b, key, "${s.entryTime.toLocalTime()} break: $why; tried again until ${s.entryTime.plusMinutes(DonchianArm.FILL_SLACK_MINUTES).toLocalTime()}") }
        }
        return changed
    }

    /** 20 Nov 2026 (audit B5, item 1): the start of a stale-history hold's words ([breaks]' tried). */
    private const val STALE_TRIED = "the history was not ready"
    /** 20 Nov 2026 (audit B5, item 1): the decision mark of a break the stale-history check let go ([breaks]): it counts in the caps. */
    internal const val STALE_HELD = "staleHeld"
    /** 20 Nov 2026 (review): the decision mark of a break the stale-history check is holding (kept in the book across a restart). */
    private const val STALE_WAIT = "staleWait"

    /**
     * The sides [src] holds on [u] for today's break [s]: its open trades', and (20 Nov 2026, audit B5) each earlier break the stale-history
     * check let go ([STALE_HELD]) while the trade it would have been could still be open - up to its time stop ([DonchianArm.EXITS]), the
     * longest it could have run. Memory and the book only.
     */
    private fun openSides(b: Book, src: String, u: String, today: LocalDate, sigs: List<DonchianArm.Signal>, s: DonchianArm.Signal): Set<Int> {
        val open = b.positions.filter { it.arm == src && it.index == u && it.open }.map { it.side }.toMutableSet()
        val tmax = (DonchianArm.EXITS.tmaxMinutes ?: 0).toLong()
        for (h in sigs) if (h !== s && "$today|$src|$u|${h.bar.b}|${h.side}|$STALE_HELD" in b.decided &&
            h.entryTime.isBefore(s.entryTime) && s.entryTime.isBefore(h.entryTime.plusMinutes(tmax))) open += h.side
        return open
    }

    /** The trades [src] took on [u] today by side, with the breaks the stale-history check let go ([STALE_HELD]) counted as taken. */
    private fun takenToday(b: Book, src: String, u: String, today: LocalDate): Map<Int, Int> {
        val taken = b.positions.filter { it.arm == src && it.index == u && it.day == today }.groupingBy { it.side }.eachCount().toMutableMap()
        val pre = "$today|$src|$u|"
        for (k in b.decided) if (k.startsWith(pre) && k.endsWith("|$STALE_HELD"))
            k.removePrefix(pre).split('|').getOrNull(1)?.toIntOrNull()?.let { side -> taken[side] = (taken[side] ?: 0) + 1 }
        return taken
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
        // 22 Oct (arms audit): an unreadable stop flag is never a reason to sell what is held (entries keep "unknown is stopped").
        val stopped = runCatching { Strategies.stoppedToday() }.getOrDefault(false) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)
        if (sharedSoldElsewhere(b, now)) changed = true
        // 10 Nov (R51's 30 Oct "exit_retry" line stood for a week): a failed-exit line about a contract its arm no longer holds open ends.
        runCatching {
            val open = b.positions.filter { it.open }.map { it.arm to it.symbol }.toSet()
            for (k in com.optionslab.ira.ArmStatus.staleExitKeys(b.status, open)) { b.status.remove(k); changed = true }
        }
        // 26 Oct: every held option's minutes are asked for side by side first, so each look below waits on its own read at most
        // [EXIT_READ_WAIT_MS] in all, not one after another.
        if (b.positions.count { it.open } > 1) for (p in b.positions) if (p.open && !p.day.isBefore(now.toLocalDate()))
            runCatching { Paper.contractOf(p.symbol)?.let { exitRead(it.feedKey, now) } }
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

    /**
     * 26 Oct (R105 lead 4, OPEN POSITIONS FIRST): the held trade's 1-minute bars for its exit look, waited on [EXIT_READ_WAIT_MS] at
     * most. The read ran inside the look, one held option after another under the book's lock, up to 30 s each on a slow feed
     * (two tries of 5 s connect + 10 s read): with the stream quiet the stops and locks were looked at 30 s and more apart, and
     * on 21 Oct both R72 puts were sold in one look at 12:33:42. Now the read goes on in the background (one at a time per
     * option; what it brings is kept for the next look) and the look decides at once on the price now and the minutes it has.
     */
    private suspend fun exitMinutes(key: String, now: LocalDateTime): List<Bar> {
        val day = now.toLocalDate()
        val running = exitRead(key, now)
        val got = kotlinx.coroutines.withTimeoutOrNull(EXIT_READ_WAIT_MS) {
            try { running.await() } catch (e: kotlinx.coroutines.CancellationException) {
                // The look's own cancellation goes up; only the read's failure is answered with the minutes kept.
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
                null
            } catch (_: Throwable) { null }
        }
        return got ?: exitBars[key]?.takeIf { it.first.toLocalDate() == day }?.second.orEmpty()
    }

    /** [key]'s minutes read in flight, or a new read started on the background scope (one at a time per option). */
    private fun exitRead(key: String, now: LocalDateTime): kotlinx.coroutines.Deferred<List<Bar>> =
        exitReads[key]?.takeIf { it.isActive } ?: background.async { minutes(key, now).also { exitBars[key] = now to it } }.also { exitReads[key] = it }

    /** How long an exit look waits for a held option's minutes (the price now decides meanwhile). */
    internal const val EXIT_READ_WAIT_MS = 2_000L
    private val exitReads = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<Bar>>>()
    private val exitBars = java.util.concurrent.ConcurrentHashMap<String, Pair<LocalDateTime, List<Bar>>>()

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
        val bars = if (!overnight) exitMinutes(c.feedKey, now) else emptyList()
        // The stream's price only while fresh (16 Oct: a tick left from a stream that stopped following the option is not
        // the price now); else the last closed minute, else a quote.
        // 26 Oct (review): a closed minute stands in for the price now only when it is the one just ended (or the one before: the
        // feed's own lag); minutes kept from an earlier, slow read are older, so a quote is read instead, as before.
        // 15 Nov (Boss: "Yes fix it"; [com.optionslab.ira.PriceNow]): only a price the option traded at since the entry, or a sane
        // book's bid, is the price now. A last price from before the entry minute (an untraded monthly option's is from another day)
        // and the bid of an empty or wide book are not: five FINNIFTY trades were "stopped" seconds after their entry on them.
        val entrySec = p.entryTime.withSecond(0).withNano(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        val seen = runCatching { Paper.bookNow(c) }.getOrNull()?.takeIf { it.ageMs <= FRESH_MS }
        val streamCounts = seen != null && com.optionslab.ira.PriceNow.lastCounts(seen.last, seen.lastTradeSec, entrySec) &&
            !com.optionslab.ira.PriceNow.farFromBook(seen.last, seen.lastTradeSec, seen.bid, seen.ask)
        val entryMin = p.entryTime.withSecond(0).withNano(0)
        val recentBar = bars.lastOrNull()?.takeIf { !it.start.isBefore(now.withSecond(0).withNano(0).minusMinutes(2)) && !it.start.isBefore(entryMin) }?.close
        // Review: with a fresh packet everything is decided from memory - its last price when it counts, else its own book when sane
        // (what a seller gets now), else the minute just closed - and nothing is read from the network (a quote read here would hold
        // every other position's exit look behind it). Only with no fresh packet is a quote read, as before.
        val ltp = if (seen != null) (if (streamCounts) seen.last else null) ?: com.optionslab.ira.PriceNow.bookBid(seen.bid, seen.ask) ?: recentBar
            else recentBar ?: if (!overnight) runCatching { Paper.quote(c) }.getOrNull()?.let { q ->
                com.optionslab.ira.PriceNow.ofQuote(q.ltp, q.noLast, q.bid, q.ask, null, Paper.lastCandleSec(c.symbol), entrySec)
            } else null
        ltp?.let { b.marks[p.symbol] = it }
        // Said once per trade (the arm's notes), so a stop that waits for a real price is never silent.
        var waitNoted = false
        if (ltp == null && !overnight && p.shadow == null && !now.isBefore(entryMin.plusMinutes(2))) {
            val k = "${p.day}|noprice|${p.symbol}|${p.entryTime}"
            if (k !in b.decided) {
                b.decided += k
                note(b, "${p.symbol}: no trade since its entry and no sane book just now - its stop is judged on the next real price (its time exit and square-off still act)")
                waitNoted = true
            }
        }
        val known = b.locks[lockKey(p)]?.split('|')?.let { runCatching { LocalDateTime.parse(it[0]) to it[1].toDouble() }.getOrNull() }
        // 16 Nov (review): a rule trade's own R64 lock (without the rule), remembered apart, from the same one walk ([PassedArms.Walk.ownLock]).
        val ownRule = p.fx && StepTrail.covers(p.arm)
        val knownOwn = if (ownRule && p.lock) b.locks[ownLockKey(p)]?.split('|')?.let { runCatching { LocalDateTime.parse(it[0]) to it[1].toDouble() }.getOrNull() } else null
        var ownWalked: Double? = null
        // 20 Nov 2026 (audit B5, item 5: an earned lock was not saved at once - only with the book's next change): a lock that rose this
        // look makes the book saved (on its own writer, [save]: never on this look's thread), so a restart keeps it.
        var lockRose = false
        // 20 Nov 2026 (audit B5, item 6: the locks rose only on closed minutes, so they stalled while the option's minutes stopped coming):
        // while the minutes in hand stop short of the one just ended (the same two minutes [recentBar] allows the feed), an R51 / R72
        // trade's best since its entry also comes from the checked prices now of the looks since ([saneHighs]: memory, never read on the
        // background, never on this look) and the price now once past the entry minute - the same levels, only no longer waiting. With
        // the minutes current the walk is the research's own, as before. (Only once the minute after the entry minute has had its two
        // minutes to come: before that no closed minute after the entry can be in hand, so none is missing yet.)
        // 20 Nov 2026 (deep review): only prices that passed the price-now checks ([ltp]: a trade since the entry and not far outside a sane
        // book, else the sane book's bid, else a checked quote) count - kept across looks as the best seen ([saneHighs]), so one bad print
        // never raises a lock. Memory only.
        val rule = p.arm == DonchianArm.SOURCE || p.arm == BreadthDonchianArm.SOURCE
        // (A stream price only when its trade is known to be after the entry minute - never a print from before the fill; else the sane
        // book's bid.)
        val saneNow = if (seen != null) (if (streamCounts && (seen.lastTradeSec ?: 0L) >= entrySec + 60) seen.last else null)
            ?: com.optionslab.ira.PriceNow.bookBid(seen.bid, seen.ask) else ltp
        if (rule && !overnight && saneNow != null && saneNow.isFinite() && saneNow > 0 && now.withSecond(0).withNano(0).isAfter(entryMin)) {
            if (saneHighs.size > 200) { val open = b.positions.filter { it.open }.map { highKey(it) }.toSet(); saneHighs.keys.retainAll(open) }
            saneHighs.merge(highKey(p), saneNow) { a, x -> maxOf(a, x) }
        }
        val bestNow = if (overnight || recentBar != null || !rule || now.withSecond(0).withNano(0).minusMinutes(2).isBefore(entryMin)) null
            else saneHighs[highKey(p)]
        // 20 Oct, review: the walk's own exit (its kind and price), so a stop or lock is never booked better than it ([sell]).
        var walked: PassedArms.Exit? = null
        val why: String? = when {
            overnight -> "overnight"
            p.setup == Solo2.Setup.S1.key -> {
                val ones = exitMinutes(upKey(p.index), now)
                val w = SoloMidday.walk(SoloMidday.Open(p.index, p.side, p.idx ?: Double.NaN, p.atr ?: Double.NaN, p.entryMinute), ones, now)
                // The index unread (or its levels unknown): the premium backstop, -30% (the Solo layer's stop), so it never runs unwatched.
                // 20 Nov 2026 (audit, B5 item 8): so too when the index minutes are stale (the newest over 3 minutes old: a feed that
                // stopped, or minutes kept from an earlier read) - the walk on them would hold, never stop ([com.optionslab.ira.CandleFresh]).
                w.exit ?: (if (s1Backstop(ones, p.idx, p.atr, now))
                    PassedArms.exitOnBars(p.entry, p.entryTime.withSecond(0).withNano(0), bars, ltp, now, S1_BACKSTOP).exit?.kind?.let { "backstop_" + it.why } else null)
                    // R64's lock on S1's premium, beside its index exits (A4: S1 keeps them; its own premium stop is none).
                    ?: if (p.lock) PassedArms.exitOnBars(p.entry, p.entryTime.withSecond(0).withNano(0), bars, ltp, now, exitsOf(p), known, breakeven(p)).also { lw ->
                        lw.lock?.let { lv -> if (known == null || lv > known.second) { b.locks[lockKey(p)] = "$now|$lv"; lockRose = true } }
                        walked = lw.exit
                    }.exit?.kind?.why else null
            }
            else -> PassedArms.exitOnBars(p.entry, p.entryMinute, bars, ltp, now, exitsOf(p), known, if (p.lock) breakeven(p) else null, knownOwn, high = bestNow).also { w ->
                w.lock?.let { lv -> if (known == null || lv > known.second) { b.locks[lockKey(p)] = "$now|$lv"; lockRose = true } }
                if (ownRule && p.lock) w.ownLock?.let { lv -> ownWalked = lv; if (knownOwn == null || lv > knownOwn.second) { b.locks[ownLockKey(p)] = "$now|$lv"; lockRose = true } }
                walked = w.exit
            }.exit?.kind?.why
        } ?: if (stopped) "operator_stop" else null
        // 1 Nov (R142 NP5-52, live in shadow): an R52 trade that kept its plain exits because it may be copied live - the 09:25 exit
        // it would have taken is logged once, nothing sold ([np5Shadow]).
        val np5Noted = why == null && np5Shadow(b, p, bars, now, known)
        // 18 Oct: the stop or lock this trade's exits rest on now, for its live trade's Zerodha stop ([ArmLive]; memory only).
        // 16 Nov (review): a rule trade's live copy takes the rule's levels (breakeven, step lock) on its OWN live fill, lots and best
        // ([ArmLive.ruleStop]): the paper level handed over is the arm's own exits alone - its stop and, with R64's lock, that lock walked
        // without the rule - never the paper trade's own breakeven or step lock, worked out on the paper fill.
        if (p.mirrored) runCatching {             // (19 Nov 2026, audit batch 2: and a would-be trade's live copy: [Pos.copied])
            // (Review: the arm's own lock comes from the same one walk ([PassedArms.Walk.ownLock]), remembered apart ([ownLockKey]) so a
            // restart or a short read never hands over less than it had.)
            val paperLock = if (ownRule) { if (p.lock) listOfNotNull(ownWalked, knownOwn?.second).maxOrNull() else null }
                else b.locks[lockKey(p)]?.substringAfter('|')?.toDoubleOrNull()
            ArmLive.level(mirrorRef(p), listOfNotNull(premiumStop(p), paperLock).maxOrNull())
        }
        if (why == null) return np5Noted || waitNoted || lockRose
        val walkPx = walked?.takeIf { it.kind.why == why }?.price
        val next = if (p.shadow != null) sellWouldBe(b, p, why, ltp, overnight, walkPx) else sell(b, p, c, why, ltp, walkPx)
        if (next != p) { b.positions[i] = next; return true }
        return false
    }

    /**
     * 1 Nov (R142 NP5-52, CLAUDE.md's LIVE rule): [p], an R52 trade that kept its plain exits ([Pos.np5] false) while the 09:25 exit is
     * on, would have been sold by it now: logged once in the arm's notes (live in shadow), nothing sold, nothing changed on the
     * trade. True when it was noted (the book changed). Pure reads of the minutes already in hand.
     */
    /** The mark [buyOne] leaves for an R52 trade that kept its plain exits because it may be copied live ([np5Shadow]). */
    private fun np5LiveKey(p: Pos): String = "${p.day}|np5live|${p.symbol}|${p.entryTime}"

    private fun np5Shadow(b: Book, p: Pos, bars: List<Bar>, now: LocalDateTime, known: Pair<LocalDateTime, Double>?): Boolean {
        if (p.arm != OovArm.SOURCE || p.np5 || p.shadow != null || !b.np5On || p.day != now.toLocalDate()) return false
        // 1 Nov (review): only a trade that kept its plain exits BECAUSE it may be copied live (marked at its entry), never one that
        // entered while the exit was off.
        if (np5LiveKey(p) !in b.decided) return false
        val k = "${p.day}|np5shadow|${p.symbol}|${p.entryTime}"
        if (k in b.decided) return false
        val w = PassedArms.exitOnBars(p.entry, p.entryMinute, bars, null, now, exitsOf(p).copy(noProgress = OovArm.NO_PROGRESS), known,
            if (p.lock) breakeven(p) else null)
        val ex = w.exit
        if (ex == null || ex.kind != PassedArms.ExitKind.NO_PROGRESS) {
            // Past its window without it: decided (never looked at again).
            if (!now.isBefore(p.entryMinute.plusMinutes(OovArm.NO_PROGRESS.minutes.toLong() + 1))) b.decided += k
            return false
        }
        b.decided += k
        note(b, "${label(p.arm)}: ${p.symbol} would have been sold at ${ex.at.toLocalTime()} by the 09:25 no-progress exit (not up 5%) - " +
            "it may be copied live, so it keeps its plain exits (live in shadow; logged only)")
        return true
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
        // 22 Oct (arms audit): an exit only ever reduces what the book holds; refused as already closed (a square-off or Boss got
        // there first between the held read and this sale), the trade is booked closed elsewhere on the next look, never sold short.
        val r = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null, exit = true)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null && !r.ok && Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
            // 19 Nov 2026 (audit batch 2, coordinator's decision): the arm's own exit ([why]) lost the race to another paper close - the next
            // look books it closed elsewhere, but its live copy is still sold by this exit ([ArmLive.ruleExit]), never only detached.
            if (p.mirrored) runCatching { ArmLive.ruleExit(mirrorRef(p), why) }
            return p
        }
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
        // 15 Nov (review): with no price now ([com.optionslab.ira.PriceNow]) a clock exit books at the walk's own price (the last minute's
        // close, else the entry), so a would-be trade on an untraded option still closes on time.
        val at = (if (ltp != null) bid?.let { minOf(it, ltp) } ?: ltp else null) ?: walkPx?.takeIf { it.isFinite() && it > 0 } ?: if (overnight) (b.marks[p.symbol] ?: p.entry) else return p
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

    /** 16 Nov (review): the key of a rule trade's own R64 lock (walked without the rule), for its live copy's level. */
    private fun ownLockKey(p: Pos): String = "own|" + lockKey(p)

    /**
     * 16 Oct (R72): R51 Donchian 20 and R72 Breadth Donchian may hold the same option at once - most of R72's breaks are R51's own,
     * and the two run side by side to be compared on the same days. Each holds its own lot on paper and sells only it; their
     * locks are kept apart ([lockKey]). Any other two of these arms: never the same option.
     */
    private fun mayShare(a: String, b: String): Boolean = a != b && setOf(a, b) == setOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE)

    /** 20 Nov 2026 (audit B5, item 6): [p]'s key in [saneHighs]. */
    private fun highKey(p: Pos): String = lockKey(p) + "|" + p.entryTime

    /** 20 Nov 2026 (audit B5, item 6): each open R51 / R72 trade's best checked price now since its entry minute ([exits]). Memory only. */
    private val saneHighs = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** [p]'s lock and (when no other open trade here holds its option) its last price, forgotten. */
    private fun forget(b: Book, p: Pos) {
        b.locks.remove(lockKey(p)); b.locks.remove(ownLockKey(p))
        saneHighs.remove(highKey(p))
        if (b.positions.none { it.open && it.symbol == p.symbol && !same(it, p) }) b.marks.remove(p.symbol)
    }

    private fun closed(b: Book, p: Pos) {
        // 16 Oct: on every way out (closed elsewhere too), so a later trade on the same option never reads this one's lock.
        forget(b, p)
        // 10 Nov: a failed exit's line about this contract ends with its trade (it stood for a week before).
        val retryKey = "${p.arm}|exit"
        if (com.optionslab.ira.ArmStatus.exitRetryOf(b.status[retryKey], p.symbol)) b.status.remove(retryKey)
        if (p.shadow != null) {
            // 19 Oct: a would-be trade closed - its record only (never live, no notice). 19 Nov 2026 (audit batch 2): one whose live copy
            // was asked ([Pos.copied]) tells it, as a real paper trade does: its own exit is the live copy's exit too.
            if (p.copied) runCatching { ArmLive.closed(mirrorRef(p), p.why ?: "exit", ArmLive.pct(p.entry, p.exit)) }
            b.status["${p.arm}|${p.index}"] = "would-be ${p.symbol} closed: ${p.why}"
            note(b, "%s: would-be %s closed (%s) @ %s, %s after estimated charges (%s)".format(Locale.ENGLISH, label(p.arm), p.symbol, p.why,
                px(p.exit ?: 0.0), rs(p.net ?: 0.0), shadowWords(p)))
            return
        }
        // 18 Oct: its live trade, if any, is sold on the next look ([ArmLive]; memory only).
        runCatching { ArmLive.closed(mirrorRef(p), p.why ?: "exit", ArmLive.pct(p.entry, p.exit)) }
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
                adopted = ADOPT_FILL, lock = b.lockOn, l25 = b.lockOn && b.l25On, st = arm == DonchianArm.SOURCE && b.stOn,
                // 15 Nov (review): a position taken back was bought before this record - not a new trade, so no breakeven rule (a
                // breakeven judged from an old fill could sell it at once).
                lot = c.lotSize.takeIf { it > 0 })
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
        (if (p.st) " · Supertrend filter" else "") + (if (p.fx) " · ${StepTrail.labelOf(lockOf(p))}" else "") + (if (p.shadow != null) " · ${shadowWords(p)}" else "")

    /** Bumped by [wipe] (under [writeLock]): a read, a save or a pass's entries under way when the erase came are not kept. */
    @Volatile private var wipes = 0

    /**
     * For tests: the book forgotten (its file stays: the tests read it again, as after the app is killed), and the day's memory.
     * 26 Oct: a save queued before, or made by a pass that read the book before, is dropped ([save], [flushBook]), and a pass's
     * entries worked on a copy are not merged back ([tick]). Never reads the vault; never waits on the book's lock.
     */
    internal fun wipe() {
        synchronized(writeLock) {
            wipes++
            pendingBytes.set(null)
            written.forget()
        }
        cache = null; _view.value = View(); hist.clear(); histRead.clear(); histTried = null; staleReadAt = null; pickHist.clear(); pickTried = null; pickScores.clear(); pickDecisions.clear(); saneHighs.clear(); histWould.clear(); w20Labels.clear(); r51Scores.clear(); lastRead.clear(); looks.clear(); tried.clear(); r72Due = emptyMap(); r72DueBar = null
        runCatching { com.optionslab.ira.ArmExits.set("passed", emptyList()) }
        // 26 Oct: the exit looks' kept minutes and the entry retry are memory too.
        exitReads.clear(); exitBars.clear(); retryJob?.cancel(); retryJob = null; plannedRetry = null; retriesOf = null to 0
    }

    /**
     * 26 Oct (Erase everything, [PersonalStores]): [wipe], and the book's file deleted - under the writer's lock, so a write in
     * progress finishes first and the file goes after it.
     */
    internal fun wipeForErase() {
        synchronized(writeLock) {
            wipe()
            if (ready()) file.delete()
            runCatching { histFile(VIX)?.parentFile?.deleteRecursively() }     // 4 Nov: the history cache (market prices only)
        }
    }

    /** Whether its book (trades, switches, log, status) is held in memory or shown. Memory only. */
    internal fun holdsForErase(): Boolean = runCatching {
        val v = _view.value
        cache?.let { b -> b.positions.isNotEmpty() || b.log.isNotEmpty() || b.armed.values.any { it } || b.decided.isNotEmpty() || b.status.isNotEmpty() ||
            b.acted.isNotEmpty() || b.paused.isNotEmpty() || b.scores.isNotEmpty() || b.marks.isNotEmpty() || b.recordOnly.isNotEmpty() } == true ||
            v.open.isNotEmpty() || v.all.isNotEmpty() || v.log.isNotEmpty() || v.armed.values.any { it } || v.status.isNotEmpty()
    }.getOrDefault(false)

    /** Its book's file (none before [init]). */
    // 19 Nov (review): with any copy of the book set aside as unreadable (it held the arms' trades; [ArmAutoStopsHost] reads it as history).
    internal fun filesForErase(): List<File> = if (ready()) listOfNotNull(file, histFile(VIX)?.parentFile) +
        runCatching { file.parentFile?.listFiles { x -> x.name.startsWith(file.name + ".unreadable.") }?.toList() }.getOrNull().orEmpty() else emptyList()

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
