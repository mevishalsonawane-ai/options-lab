package com.optionslab.app.ira

import com.optionslab.ira.Vetting

/**
 * The local market expert (part 7): every strategy, arm and Pine script trading on paper is judged on its own forward
 * paper trades ([Vetting]). What held up is brought to Boss (going live stays his step, with his PIN); what failed is
 * told and switching it off is offered (asked first, unless Boss chose automatic stops in chat). Each verdict is told
 * once. Jarvis's own trades and Boss's hand trades have their own records and are left out.
 */
internal object IraExpert {
    private const val TOLD = "jarvis.expert.told"

    /** Each paper strategy's verdict now. */
    suspend fun verdicts(): List<Vetting.Verdict> {
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        return IraAccount.trips(false, owners)
            .filter { it.owner.isNotBlank() && it.owner != "Manual" && !it.owner.startsWith("Jarvis") }
            .groupBy { it.owner }
            .map { (owner, l) -> Vetting.judge(owner, l.sortedBy { it.closedAt }.map { it.net }) }
    }

    suspend fun say(): String = Vetting.say(verdicts())

    /** Every market-watch pass: a new held-up or failed verdict is told once (and a failed one offered off). */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val told = runCatching { com.optionslab.app.security.SecurePrefs.getString(TOLD) }.getOrNull()?.split('|')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
        var changed = false
        for (v in verdicts()) {
            if (v.state == Vetting.State.TESTING) continue
            val key = "${v.name}:${v.state}"
            if (key in told) continue
            val c = IraHub.appContext() ?: continue          // told when it can be shown, not before
            told += key; changed = true
            IraActivity.add(v.text())
            if (v.state == Vetting.State.HELD_UP) {
                val text = v.text() + " It may be worth trading live: arm it in Live yourself, with your PIN."
                JarvisPopup.show(c, "Boss, ${v.name} held up", text); IraHub.note(text)
            } else {
                // Switching it off is offered (Boss, 4 Oct: anything Jarvis thinks should stop is asked first).
                val (what, act) = runCatching { IraActions.prepare(com.optionslab.ira.Command(com.optionslab.ira.Command.Kind.STOP_ONE, target = v.name)) }.getOrNull() ?: (null to null)
                // Only when the name found is exactly this strategy's (never a near match switched off by mistake), and only
                // in Paper: in Live, stopping a run sells what it holds - that is Boss's to do.
                val exact = what != null && what.equals(com.optionslab.ira.Commands.describe(com.optionslab.ira.Command(com.optionslab.ira.Command.Kind.STOP_ONE, target = v.name), v.name), ignoreCase = true)
                val paper = runCatching { !com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
                if (act == null || what == null || !exact || !paper) { JarvisPopup.show(c, "Boss, ${v.name} failed its paper test", v.text()); IraHub.note(v.text()) }
                else IraHub.offer(what, "Boss, ${v.name} failed its paper test", v.text() + " Shall I $what?", act)
            }
        }
        if (changed) runCatching { com.optionslab.app.security.SecurePrefs.put(TOLD, told.joinToString("|")) }
    }
}
