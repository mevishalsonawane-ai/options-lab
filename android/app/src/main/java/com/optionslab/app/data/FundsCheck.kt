package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.orb.LiveMirror
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 15 Nov 2026 (Boss: "Yes" to building it in shadow; the phone's diagnostics: 25 arm live BUYs of 2-6 Nov, and one ORB, rejected by
 * Zerodha with "Insufficient funds. Margin required"): the funds check - no live arm entry the free cash cannot pay for even one lot.
 *
 * Before, the free-cash fit ([LiveMirror.lotsThatFit], 4 Nov) only cut a buy of more than one lot; when not even one lot fitted, the
 * order went anyway and Zerodha refused it, and every later signal of the day went again.
 *
 *  - It asks only an option BUY (index or stock options) on its way to Zerodha: an arm's live copy ([ArmLive]) and the ORB family's own live entry
 *    ([OrbArms], Liquidity 15+5 among them). A sale, a future and an MCX order (their own margin) are never asked.
 *  - Its figure is the free cash the entry's own path already holds (the fit's read, or the margins its account guard just read:
 *    [Broker.keptFreeCash]): nothing new is read. Unknown (not read, older than the margin check trusts): it says nothing.
 *  - SHADOW by default (CLAUDE.md: anything new that could skip a live trade starts logged only, behind a switch that is off by
 *    default): the order goes exactly as before; what it would have skipped is logged (no rupees in the diagnostics) and counted
 *    per day, on Bot settings' card and the daily diagnostics, so Boss sees how many rejections it would have saved.
 *  - ON (Boss's switch in Bot settings): such an entry is not sent; it is kept as a would-be entry (the arm, the contract, the lots,
 *    the price) and logged. On only ever lowers risk, so it needs no PIN; OFF adds live trades back, so it takes the PIN.
 *
 * The order path reads only memory here ([check]: no settings read, no disk, no network, no lock held while anything is read:
 * OrderPathRound2Test stays 0). The switch is read once in the background at the start; the record is written in the background.
 */
object FundsCheck {
    internal const val K_ON = "g.fundsCheck.live"
    private const val K_LOG = "fundsCheck.log"
    private const val KEPT = 300

    /** What a would-be entry's line and the diagnostics say. */
    const val WORDS = "free cash would not cover one lot"
    /** The card's and the diagnostics' name. */
    const val LABEL = "Funds check"

    @Volatile private var on = false
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    private val lock = Any()
    private var recs: List<Rec> = emptyList()
    /**
     * 15 Nov (review): bumped by [wipe] ("Erase everything"): a save of the record queued before the erase never writes it back after.
     * [PersonalStores] wipes this store before the settings vault, so a save that passed this check is dropped by the vault's own
     * wipe; one that runs after this store's wipe is skipped here. Saves take [writeLock] (never the order path's [lock]).
     */
    @Volatile private var wipeGen = 0
    private val writeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One entry the check would skip ([applied] false: shadow, it went as before) or skipped ([applied] true: not sent, kept as a
     * would-be entry): when, whose ([owner]: the arm's label), the contract, the lots wanted and the price it was judged at.
     */
    data class Rec(val atMs: Long, val day: LocalDate, val owner: String, val symbol: String, val lots: Int, val price: Double?, val applied: Boolean) {
        fun encode(): String = listOf(atMs.toString(), day.toString(), clean(owner), clean(symbol), lots.toString(), price?.toString() ?: "",
            if (applied) "1" else "0").joinToString("|")
    }
    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ')
    private fun decode(l: String): Rec? = runCatching {
        val p = l.split('|')
        if (p.size < 7) return null
        Rec(p[0].toLong(), LocalDate.parse(p[1]), p[2], p[3], p[4].toInt(), p[5].toDoubleOrNull(), p[6] == "1")
    }.getOrNull()

    /** Bot settings' card: the switch, whether it was read, and today's counts ([line]). */
    data class View(val on: Boolean = false, val loaded: Boolean = false, val wouldSkipToday: Int = 0, val skippedToday: Int = 0, val line: String = "")
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private fun marketDay(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        scope.launch { load() }
    }

    /** The switch and the record from the settings vault (a worker thread only). */
    internal fun load() {
        // Read first, outside the lock: the order path's [check] never waits on a settings read.
        val saved = runCatching { SecurePrefs.getBoolean(K_ON, false) }.getOrNull()
        val kept = runCatching { SecurePrefs.getString(K_LOG).orEmpty().lines().mapNotNull { decode(it) } }.getOrDefault(emptyList())
        synchronized(lock) {
            if (!switched && saved != null) on = saved
            // Merged with anything recorded before this read (never thrown away), oldest first.
            recs = (kept + recs).distinct().sortedBy { it.atMs }.takeLast(KEPT)
            loaded = true
        }
        publish()
    }

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

    /** The switch now, from memory (off until the start's read lands: its default, the side that changes nothing). */
    fun isOn(): Boolean = on

    /**
     * On the order path, just before a live entry is sent: [owner]'s (the arm's label) BUY of [lots] lots of [symbol], [perLot] rupees a
     * lot at the price it was judged at ([price]), against the free cash its own path already holds ([free]; null: unknown). Null: send
     * it (the cash covers one lot, a figure is unknown, or - in shadow - it would skip it, logged and counted). Otherwise why it is not
     * sent (the switch is on), logged and kept as a would-be entry. Memory only; never throws.
     */
    fun check(owner: String, symbol: String, lots: Int, perLot: Double, free: Double?, price: Double?): String? = runCatching {
        if (!LiveMirror.shortOfOneLot(perLot, free)) return@runCatching null
        val applied = on
        record(Rec(System.currentTimeMillis(), marketDay(), owner, symbol, lots, price, applied), wipeGen)
        if (applied) "refused: $WORDS (funds check on; kept as a would-be entry)" else null
    }.getOrNull()

    private fun record(r: Rec, gen: Int) {
        synchronized(lock) { recs = (recs + r).takeLast(KEPT) }
        // No rupee figure in the diagnostics (they hide rupees): the arm, the contract, the lots wanted and what happened.
        val lotWords = "${r.lots} lot${if (r.lots == 1) "" else "s"} wanted"
        runCatching {
            Diag.record("funds", "${r.owner}: ${r.symbol}, $lotWords - $WORDS" +
                if (r.applied) " (funds check on: not sent, kept as a would-be entry)" else " (shadow: logged only, sent as before)")
        }
        scope.launch {
            persist(gen)
            publish()
        }
    }

    /**
     * Writes the record (a worker thread), unless an erase came since [gen] was read: true when it was written. The check and the write
     * are one step under [writeLock], which [wipe] takes too, so no save can slip between them.
     */
    internal fun persist(gen: Int): Boolean = synchronized(writeLock) {
        if (gen != wipeGen) return@synchronized false
        val text = synchronized(lock) { recs }.joinToString("\n") { it.encode() }
        runCatching { SecurePrefs.putAllLazy(mapOf(K_LOG to text)) }.isSuccess
    }

    /** TEST ONLY: the erase generation now (for [persist]). */
    internal fun genForTest(): Int = wipeGen

    /** Today's records (the card, the diagnostics, the tests). Memory only. */
    fun recsOf(day: LocalDate = marketDay()): List<Rec> = synchronized(lock) { recs.filter { it.day == day } }

    /** The diagnostics' and the card's line: the switch and today's counts. Memory only. */
    fun line(): String {
        val t = recsOf()
        val would = t.count { !it.applied }
        val skipped = t.count { it.applied }
        fun n(k: Int) = "$k live ${if (k == 1) "entry" else "entries"}"
        return "$LABEL: " + when {
            !loaded -> "not read yet (counts as off: shadow)"
            on -> "on - a live arm entry the $WORDS is not sent"
            else -> "shadow (off) - logged only"
        } + ". Today: ${n(would)} it would have skipped (sent as before)" + (if (skipped > 0) ", ${n(skipped)} not sent" else "") + "."
    }

    private fun publish() {
        runCatching {
            val t = recsOf()
            _view.value = View(on, loaded, t.count { !it.applied }, t.count { it.applied }, line())
        }
    }

    /**
     * Boss's switch (Bot settings). On only lowers risk: at once. Off adds live entries back: it takes [pinConfirmed] (the card asks the
     * PIN). What to say. A worker thread only (the switch's first read may be made here).
     */
    fun set(on: Boolean, pinConfirmed: Boolean = false): String {
        if (!on && !pinConfirmed) return "Turning the funds check off lets live entries the free cash cannot pay for go to Zerodha again: it takes your PIN. Nothing changed."
        if (!loaded) load()
        synchronized(lock) {
            if (this.on == on) return "The funds check is already ${if (on) "on" else "off (shadow)"}."
            this.on = on
            loaded = true
            switched = true
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("funds", "$LABEL: ${if (on) "shadow → on" else "on → shadow"} (by you)") }
        publish()
        return if (on) "On: a live arm entry the free cash cannot pay for even one lot is not sent; it is kept as a would-be entry."
        else "Off: in shadow - such entries go to Zerodha as before; each is logged."
    }

    /** Erase everything ([PersonalStores]): as on a fresh phone - off, no record ([switched]: a late start read never brings Boss's old "on" back). */
    fun wipe() {
        synchronized(writeLock) {
            wipeGen++
            synchronized(lock) { on = false; loaded = true; switched = true; recs = emptyList() }
        }
        publish()
    }

    /** Whether Boss's choice or a record is still in memory (the erase's check). Memory only. */
    internal fun holdsForErase(): Boolean = on || synchronized(lock) { recs.isNotEmpty() }

    /** TEST ONLY: as a fresh start would have it (read, [on]), nothing switched, no record. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true) {
        synchronized(lock) { this.on = on; this.loaded = loaded; switched = false; recs = emptyList() }
        publish()
    }
}
