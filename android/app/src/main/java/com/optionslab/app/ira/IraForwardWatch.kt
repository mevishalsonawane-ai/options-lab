package com.optionslab.app.ira

import com.optionslab.ira.ForwardCheck
import com.optionslab.ira.ForwardWatch
import java.time.LocalDate

/**
 * The forward-test watch ([ForwardWatch], Boss, 06 Oct 2026): after a paper arm's trade closes - on the words lane's next
 * round ([watch], [Automations.Auto.FORWARD], its own switch under Coach me, on) - each of Liquidity 15+5, Solo (midday)
 * and Hero is checked against its backtest ([ForwardCheck], the P&L tab's Live vs backtest card's own check, on the same
 * trades as [com.optionslab.app.data.ForwardRecords]); when its verdict's category changed from the last one told, or a
 * milestone is reached (20 trades; 40 and 60 for Solo), one note in the chat, tagged for Today's notes (Coach).
 * Asked ("is anything drifting", [answer]): one line an arm.
 *
 * Reads only, each bounded: the arms' closed paper book through its own reader
 * ([com.optionslab.app.data.OrbArms.closedPaper]: its lock is held only to copy the book, never across this work - and a
 * lock held by the arms' tick is waited on a few seconds at most), Solo's own record and baseline through its public
 * readers ([IraSolo.all], [IraSolo.baseline]). Words only: nothing here switches an arm, changes its lots or its rules;
 * Solo's own switch-off stays Solo's own. What was last told for each arm is kept under [ForwardWatch.KEY_PREFIX]
 * (this phone's alone, never in a backup: Solo's record it is read from never leaves the phone). In the chat only (never
 * spoken); never in quiet hours (a change found then is told after them). Not in IraGoldAlgo.
 */
internal object IraForwardWatch {
    /** The arms' book is copied under its lock: waited on this long at most (the tick may hold it across a network read). */
    private const val READ_MS = 4_000L
    /** The whole round, at most (the words lane runs its checks one after another). */
    private const val WATCH_MS = 8_000L
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Solo (midday)'s own closed forward trades, oldest first (the forward test's; the retired Solo's are not its); null
     * when its store could not be read - [IraSolo.all] answers an empty list then, so an unreadable vault is checked
     * after it (never taken as "no trades"; [ForwardWatch.decide] also takes "too few" after a told verdict as unreadable).
     */
    private fun soloClosed(): List<IraSolo.T>? = runCatching {
        val all = IraSolo.all()
        if (com.optionslab.app.security.SecurePrefs.unreadable) return@runCatching null
        IraSolo.midday(all).filter { it.closed && it.net != null }
    }.getOrNull()

    /**
     * Each watched arm ([ForwardWatch.ARMS]) with its check now - null for an arm whose record could not be read in time
     * (never guessed as "no trades").
     */
    internal suspend fun checks(): List<Pair<ForwardCheck.Expectation, ForwardCheck.Result?>> {
        val closed = kotlinx.coroutines.withTimeoutOrNull(READ_MS) {
            runCatching { com.optionslab.app.data.OrbArms.closedPaper() }.getOrNull()
        }
        val solo = soloClosed()?.mapNotNull { t -> runCatching { ForwardCheck.Trade(LocalDate.parse(t.day), t.net!!) }.getOrNull() }
        return ForwardWatch.ARMS.map { e ->
            e to runCatching {
                when (e.key) {
                    ForwardCheck.LIQUIDITY.key -> closed?.let { ForwardCheck.check(e, com.optionslab.app.data.ForwardRecords.liquidity(it)) }
                    ForwardCheck.HERO.key -> closed?.let { ForwardCheck.check(e, com.optionslab.app.data.ForwardRecords.hero(it)) }
                    ForwardCheck.SOLO.key -> solo?.let { ForwardCheck.check(e, it) }
                    else -> null
                }
            }.getOrNull()
        }
    }

    /** Solo's own switch-off line now (its closed trades from its baseline), or null when its record could not be read. */
    internal fun soloBar(): ForwardWatch.SoloBar? = runCatching {
        val nets = soloClosed() ?: return@runCatching null
        ForwardWatch.soloBar(nets.map { it.net!! }, IraSolo.baseline())
    }.getOrNull()

    /** "Is anything drifting", "forward test status": one line an arm against its backtest. Reads only. */
    suspend fun answer(): String {
        val checks = kotlinx.coroutines.withTimeoutOrNull(WATCH_MS) { checks() }
            ?: return "I could not read the arms' records in time just now, Boss - ask me again in a moment."
        return ForwardWatch.summary(checks, soloBar())
    }

    /**
     * One arm named ("liquidity vs backtest", "is hero drifting", [ForwardWatch.armAsked]): that arm's line and what it means,
     * Solo's with its own switch-off line, Hero's with its drawdown against the backtest's worst ([ForwardWatch.armAnswer]).
     * Reads only.
     */
    suspend fun answer(arm: ForwardCheck.Expectation): String {
        val checks = kotlinx.coroutines.withTimeoutOrNull(WATCH_MS) { checks() }
            ?: return "I could not read ${ForwardWatch.name(arm)}'s record in time just now, Boss - ask me again in a moment."
        val r = checks.firstOrNull { it.first.key == arm.key }?.second
        return ForwardWatch.armAnswer(arm, r, if (arm.key == ForwardCheck.SOLO.key) soloBar() else null)
    }

    private fun told(e: ForwardCheck.Expectation): ForwardWatch.Told? =
        runCatching { ForwardWatch.Told.decode(com.optionslab.app.security.SecurePrefs.getString(ForwardWatch.key(e))) }.getOrNull()

    /**
     * Each round of the words lane: every arm checked; what is new kept, and a note put for each arm whose verdict changed
     * or reached a milestone. What is kept is written before the note is put, so a restart midway never tells it twice;
     * should it not be written, nothing is said (the next round tries again).
     */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.FORWARD)) return
        if (runCatching { JarvisVoice.quietNow() }.getOrDefault(true)) return
        if (!busy.compareAndSet(false, true)) return
        try {
            val checks = kotlinx.coroutines.withTimeoutOrNull(WATCH_MS) { checks() } ?: return
            val bar = soloBar()
            for ((e, r) in checks) {
                if (r == null) continue
                val d = ForwardWatch.decide(r, told(e), if (e.key == ForwardCheck.SOLO.key) bar else null) ?: continue
                val kept = runCatching { com.optionslab.app.security.SecurePrefs.put(ForwardWatch.key(e), d.told.encode()) }.isSuccess
                if (!kept) continue
                val text = d.text ?: continue
                IraHub.note(text, from = Automations.Auto.FORWARD, kind = com.optionslab.ira.TodayNotes.Category.COACH)
                Automations.acted(Automations.Auto.FORWARD, "${ForwardWatch.name(e)}: ${d.told.category.words}.")
            }
        } finally {
            busy.set(false)
        }
    }
}
