package com.optionslab.app.ira

import com.optionslab.ira.Improve
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Jarvis improving himself week by week ([Improve]): at the weekend review he reads his own week from the day's counts
 * ([IraTools.usageOn]) and the phrasings he missed, grades last week's goals and sets up to three for the week ahead -
 * kept on the phone (encrypted). The agenda checks them once a day, the wrap-up says how they stand, and "how are you
 * improving?" answers from them.
 *
 * A goal is about his own behaviour only and only ever speaks, studies, asks Boss to teach him or works on paper: nothing
 * here places, changes or closes anything, switches anything, or adds risk.
 */
internal object IraImprove {
    private const val KEY = "jarvis.improve"
    private val lock = Mutex()

    private fun prefs() = com.optionslab.app.security.SecurePrefs

    private fun read(): Improve.Plan? = runCatching {
        val o = JSONObject(prefs().getString(KEY) ?: return null)
        val g = o.getJSONArray("g")
        val h = o.optJSONArray("h") ?: JSONArray()
        Improve.Plan(LocalDate.parse(o.getString("w")),
            (0 until g.length()).map { n ->
                val j = g.getJSONObject(n)
                val w = j.optJSONArray("words")
                Improve.Goal(Improve.Kind.valueOf(j.getString("k")), j.getInt("t"), j.getInt("b"),
                    if (w == null) emptyList() else (0 until w.length()).map { w.getString(it) })
            },
            (0 until h.length()).map { n -> h.getJSONObject(n).let { Improve.Score(LocalDate.parse(it.getString("w")), it.getInt("m"), it.getInt("n")) } },
            if (o.has("last")) o.getString("last") else null)
    }.getOrNull()

    private fun save(p: Improve.Plan) = runCatching {
        val g = JSONArray()
        p.goals.forEach { x -> g.put(JSONObject().put("k", x.kind.name).put("t", x.target).put("b", x.baseline).put("words", JSONArray(x.words))) }
        val h = JSONArray()
        p.history.forEach { s -> h.put(JSONObject().put("w", s.week.toString()).put("m", s.met).put("n", s.total)) }
        val o = JSONObject().put("w", p.week.toString()).put("g", g).put("h", h)
        p.lastGrade?.let { o.put("last", it) }
        prefs().put(KEY, o.toString())
    }

    /** His record for the week starting [start], up to [upTo]. */
    private fun week(start: LocalDate, upTo: LocalDate): Improve.Week =
        Improve.week(start, Improve.days(start, upTo).map { IraTools.usageOn(it) }, Improve.missedIn(IraTools.missedWeek(), start))

    private fun learned() = runCatching { IraTools.learned() }.getOrDefault(emptyList())

    /** The weekend cycle for the week starting [next] (once: a plan already for that week is kept), or null when it was made before. */
    private fun cycleLocked(next: LocalDate): Improve.Cycle? {
        val old = read()
        if (old?.week == next) return null
        val past = next.minusWeeks(1)
        val c = Improve.weekend(old, week(past, past.plusDays(6)), learned(), next)
        save(c.plan)
        return c
    }

    /** This week's plan, made late (from last week's record) when the weekend review did not run. */
    private suspend fun plan(today: LocalDate): Improve.Plan? = lock.withLock {
        val w = Improve.weekOf(today)
        // Late only when the kept plan is older: next week's goals set at the weekend review are never overwritten.
        val kept = read()
        if (kept == null || kept.week.isBefore(w)) runCatching { cycleLocked(w) }
        read()
    }

    /**
     * At the weekend review (after the week's last session): his week read, last week's goals graded, next week's set -
     * the whole in the chat, the plain words aloud (no word of Boss's, no figure of his account).
     */
    suspend fun weekend() {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        val today = com.optionslab.app.data.Market.today()
        val c = lock.withLock { runCatching { cycleLocked(Improve.nextWeek(today)) }.getOrNull() } ?: return
        IraHub.note(c.said)
        IraActivity.add("Reviewed my own week and set ${c.plan.goals.size} goal${if (c.plan.goals.size == 1) "" else "s"} for next week.")
        if (Automations.on(Automations.Auto.WEEK)) JarvisVoice.announce(com.optionslab.ira.Wake.spoken(c.spoken, 4))
    }

    /** "How are you improving?" / "what are your goals?" */
    suspend fun say(): String {
        val today = com.optionslab.app.data.Market.today()
        val p = plan(today)
        return Improve.say(p, week(Improve.weekOf(today), today), learned(), today)
    }

    /** This week's plan as kept (for the ledger of what he learned, [com.optionslab.ira.Learnings]). */
    suspend fun current(): Improve.Plan? = plan(com.optionslab.app.data.Market.today())

    /** "Undo everything you learned this week": his own goals for this week dropped (graded weeks kept). How many. */
    suspend fun dropWeek(): Int = lock.withLock {
        val today = com.optionslab.app.data.Market.today()
        val p = read()
        val dropped = com.optionslab.ira.Learnings.dropGoals(p, today) ?: return@withLock 0
        save(dropped)
        p?.goals?.size ?: 0
    }

    /** The agenda's daily check (null: no goals this week). */
    suspend fun checkLine(): String? {
        val today = com.optionslab.app.data.Market.today()
        return Improve.checkLine(plan(today), week(Improve.weekOf(today), today), learned(), today)
    }

    /** The wrap-up's line (null: no goals this week). */
    suspend fun wrapLine(): String? {
        val today = com.optionslab.app.data.Market.today()
        return Improve.wrap(plan(today), week(Improve.weekOf(today), today), learned(), today)
    }

    /** How many goals he has this week (for the agenda), and the phrasings of his learning goal not learned yet (its study). */
    suspend fun forAgenda(): Pair<Int, List<String>> {
        val today = com.optionslab.app.data.Market.today()
        val p = plan(today)
        val n = if (p != null && p.week == Improve.weekOf(today)) p.goals.size else 0
        return n to Improve.toStudy(p, learned(), today)
    }
}
