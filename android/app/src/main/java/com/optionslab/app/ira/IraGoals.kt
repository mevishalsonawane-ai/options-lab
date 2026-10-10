package com.optionslab.app.ira

import com.optionslab.ira.Goals
import java.time.LocalDate

/**
 * Boss's goals over days (part 6): kept on the phone, their progress read from the closed trades of the app's mode,
 * said each morning and when asked; a goal close or broken is told once a day, and a broken loss goal brings an offer
 * to switch the kill switch on (asked, never done alone). Goals only hold trading back.
 */
internal object IraGoals {
    private const val KEY = "jarvis.goals"
    private const val TOLD = "jarvis.goals.told"

    fun all(): List<Goals.Goal> = runCatching {
        val a = org.json.JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Goals.Goal(Goals.Kind.valueOf(it.getString("k")), Goals.Period.valueOf(it.getString("p")), it.getDouble("a")) } }
    }.getOrDefault(emptyList())

    private fun save(g: List<Goals.Goal>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(KEY, if (g.isEmpty()) null else org.json.JSONArray().apply {
            g.forEach { put(org.json.JSONObject().put("k", it.kind.name).put("p", it.period.name).put("a", it.amount)) } }.toString())
    }

    fun add(g: Goals.Goal) { save(Goals.add(all(), g)) }
    fun clear() { save(emptyList()) }

    /** Each goal's standing now. */
    suspend fun statuses(): List<Goals.Status> {
        val goals = all()
        if (goals.isEmpty()) return emptyList()
        val live = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val trips = IraAccount.trips(live, owners)
        val today = com.optionslab.app.data.Market.today()
        val closed = trips.map { Goals.Closed(it.closedAt.toLocalDate(), it.net) }
        val tradesToday = trips.count { it.openedAt.toLocalDate() == today }
        return goals.map { Goals.status(it, closed, tradesToday, today) }
    }

    suspend fun say(): String = Goals.say(statuses())

    /** Every market-watch pass: a goal close, broken, met or at its trade limit is told once a day. */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val today = com.optionslab.app.data.Market.today().toString()
        val stored = runCatching { com.optionslab.app.security.SecurePrefs.getString(TOLD) }.getOrNull()
        val told = stored?.split('|')?.toMutableSet() ?: mutableSetOf()
        told.removeAll { !it.startsWith(today) }
        for (s in statuses()) {
            val state = when { s.broken -> "broken"; s.near -> "near"; s.met -> "met"; else -> null } ?: continue
            val key = "$today:${s.goal.kind}:${s.goal.period}:$state"
            if (key in told) continue
            val c = IraHub.appContext() ?: continue          // told when it can be shown, not before
            told += key
            val text = s.text
            JarvisPopup.show(c, "Boss, your goal: ${s.goal.text()}", text)
            IraActivity.add("Goal: $text")
            if (s.broken && s.goal.kind == Goals.Kind.MAX_LOSS) IraHub.offerKillSwitch("$text Shall I switch the kill switch on (no new positions; exits still go)?")
            else IraHub.noteAloud(text, com.optionslab.ira.SpeakChoice.Weight.MINOR, from = null, kind = com.optionslab.ira.TodayNotes.Category.COACH)
        }
        // Saved only when it changed: each save re-encrypts and rewrites the whole vault, and this ran at every pass.
        val joined = told.joinToString("|")
        if (joined != (stored ?: "")) runCatching { com.optionslab.app.security.SecurePrefs.put(TOLD, joined) }
    }

    fun isToday(d: LocalDate) = d == com.optionslab.app.data.Market.today()
}
