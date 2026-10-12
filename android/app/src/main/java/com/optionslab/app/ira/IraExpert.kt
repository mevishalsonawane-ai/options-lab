package com.optionslab.app.ira

import com.optionslab.ira.Vetting

/**
 * The local market expert (part 7): every strategy, arm and Pine script trading on paper is judged on its own forward
 * paper trades against ITS OWN BACKTEST's range ([Vetting], research X1: from 13 Oct 2026, 60 trades and 40 trading days
 * before anything passes). A pass, or a record outside the backtest's range ("investigate" / "check the fills"), is told
 * once. Nothing is switched: passing only informs Boss (going live stays his step, with his PIN), and an arm below its
 * range is never offered off (Boss, 12 Oct: every arm keeps running on paper). Jarvis's own trades and Boss's hand trades
 * have their own records and are left out.
 */
internal object IraExpert {
    private const val TOLD = "jarvis.expert.told"

    /** Each paper strategy's verdict now. */
    suspend fun verdicts(): List<Vetting.Verdict> {
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val today = runCatching { com.optionslab.app.data.Market.today() }.getOrElse { java.time.LocalDate.now() }
        // Judged again only when the paper trips (or the day) changed: every pass read it.
        return judged.of(IraAccount.trips(false, owners) to today) { (trips, day) ->
            trips.filter { it.owner.isNotBlank() && it.owner != "Manual" && !it.owner.startsWith("Jarvis") }
                // Liquidity 15+5 per index (research X1): BANKNIFTY's paper test apart from FINNIFTY's and MIDCPNIFTY's.
                .groupBy { com.optionslab.ira.LiquiditySplit.owner(it.owner, it.symbol) }
                .map { (owner, l) -> Vetting.judge(owner, l.map { Vetting.Trade(it.closedAt.toLocalDate(), it.net) }, day) }
        }
    }

    private val judged = com.optionslab.ira.SameInput<Pair<List<com.optionslab.ira.Insights.Trip>, java.time.LocalDate>, List<Vetting.Verdict>>()

    suspend fun say(): String = Vetting.say(verdicts())

    /** Every market-watch pass: a pass, or a record outside its backtest's range, is told once. Nothing is switched or offered. */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val told = runCatching { com.optionslab.app.security.SecurePrefs.getString(TOLD) }.getOrNull()?.split('|')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
        var changed = false
        for (v in verdicts()) {
            if (v.state != Vetting.State.PASSED && v.state != Vetting.State.BELOW && v.state != Vetting.State.ABOVE) continue
            val key = "${v.name}:${v.state}"
            if (key in told) continue
            val c = IraHub.appContext() ?: continue          // told when it can be shown, not before
            told += key; changed = true
            IraActivity.add(v.text())
            val title = when (v.state) {
                Vetting.State.PASSED -> "Boss, ${v.name} passed its paper test"
                Vetting.State.BELOW -> "Boss, ${v.name} is below its backtest's range"
                else -> "Boss, ${v.name} is above its backtest's range"
            }
            // The words carry rupees: aloud only on an unlocked phone (the note itself says no amount when locked).
            JarvisPopup.show(c, title, v.text())
            if (!runCatching { IraHub.locked() }.getOrDefault(true))
                IraHub.noteAloud(v.text(), com.optionslab.ira.SpeakChoice.Weight.MINOR, from = null, kind = com.optionslab.ira.TodayNotes.Category.COACH)
        }
        if (changed) runCatching { com.optionslab.app.security.SecurePrefs.put(TOLD, told.joinToString("|")) }
        runCatching { graduationWatch() }
    }

    private const val GRADUATED = "jarvis.expert.graduated"

    /**
     * The first time Jarvis's own trades (or Solo's) pass their paper test, what that means and what Boss would do to
     * let them go live is told once ([com.optionslab.ira.Graduation]) - on an unlocked phone (the record's rupees are
     * account data). Words only: no switch, mode or order is touched; real orders stay behind his fingerprint.
     */
    private fun graduationWatch() {
        if (runCatching { IraHub.locked() }.getOrDefault(true)) return
        val c = IraHub.appContext() ?: return
        val done = runCatching { com.optionslab.app.security.SecurePrefs.getString(GRADUATED) }.getOrNull()?.split('|')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
        val records = mapOf(
            com.optionslab.ira.Graduation.Who.JARVIS to runCatching { IraNewsTrades.closedRecord() }.getOrDefault(emptyList()),
            com.optionslab.ira.Graduation.Who.SOLO to runCatching { IraSolo.closedRecord() }.getOrDefault(emptyList()))
        val due = com.optionslab.ira.Graduation.due(done, records.mapValues { (_, r) -> r.isNotEmpty() && com.optionslab.ira.JarvisTrades.proven(r) == null })
        if (due.isEmpty()) return
        val aiLive = !IraNewsTrades.paperFirst
        val appLive = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
        val tell = due.map { who -> who to com.optionslab.ira.Graduation.say(who, records[who].orEmpty(), aiLive, appLive) }
        // Marked told BEFORE it is shown: a pop-up or note that throws must never bring the same note back every pass.
        // Not saved (a vault fault): told on a later pass instead, never twice.
        for ((who, _) in tell) done += who.key
        // (A vault that could not be read takes the put without writing it, and without throwing: checked, and read back -
        // not saved, not shown; review, 5 Oct.)
        val markedTold = runCatching {
            com.optionslab.app.security.SecurePrefs.put(GRADUATED, done.joinToString("|"))
            !com.optionslab.app.security.SecurePrefs.unreadable &&
                com.optionslab.app.security.SecurePrefs.getString(GRADUATED)?.split('|')?.containsAll(tell.map { it.first.key }) == true
        }.getOrDefault(false)
        if (!markedTold) return
        for ((who, text) in tell) {
            runCatching { IraActivity.add("Told Boss ${who.whose} passed their paper test (once; nothing switched).") }
            runCatching { JarvisPopup.show(c, "Boss, ${who.whose} passed their paper test", text) }
            runCatching { IraHub.noteAloud(text, com.optionslab.ira.SpeakChoice.Weight.MINOR, from = null, kind = com.optionslab.ira.TodayNotes.Category.COACH) }
        }
    }
}
