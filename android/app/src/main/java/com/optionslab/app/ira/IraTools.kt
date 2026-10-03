package com.optionslab.app.ira

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Making real use count (JarvisAlgo, the owner's wishes 2026-10-03): the mistakes list, the day's usage counts for the
 * evening summary, short spoken answers, the wake-word sensitivity, Jarvis's weekly loss cap, and practice on a past
 * day. Words are kept with secrets hidden.
 */
internal object IraTools {
    private val IST = ZoneId.of("Asia/Kolkata")
    private fun prefs() = com.optionslab.app.security.SecurePrefs

    // ---- the mistakes list ---------------------------------------------------------------------------------------

    private const val MISTAKES = "jarvis.mistakes"

    fun mistakes(): List<com.optionslab.ira.Mistakes.Entry> = runCatching {
        val a = JSONArray(prefs().getString(MISTAKES) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> com.optionslab.ira.Mistakes.Entry(LocalDateTime.parse(o.getString("t")), o.getString("s"), o.getString("a")) } }
    }.getOrDefault(emptyList())

    /** "That was wrong": the last question and answer before it are kept. */
    @Synchronized fun markWrong(): String {
        val ms = IraHub.state.value.messages.dropLastWhile { m -> !m.fromIra && com.optionslab.ira.Commands.parse(m.text)?.kind == com.optionslab.ira.Command.Kind.MISTAKE }
        val answer = ms.lastOrNull { it.fromIra } ?: return "There is no answer of mine to mark yet."
        val question = ms.subList(0, ms.indexOf(answer)).lastOrNull { !it.fromIra }
        val heard = JarvisVoice.heardText
        val said = com.optionslab.ira.Secrets.redact(question?.text ?: heard ?: "?").take(200)
        val all = (mistakes() + com.optionslab.ira.Mistakes.Entry(LocalDateTime.now(IST).withNano(0), said,
            com.optionslab.ira.Secrets.redact(answer.text).take(300))).takeLast(100)
        prefs().put(MISTAKES, JSONArray().apply { all.forEach { put(JSONObject().put("t", it.at.toString()).put("s", it.said).put("a", it.answered)) } }.toString())
        count("mistakes")
        IraActivity.add("Marked wrong: \"$said\".")
        awaiting = said to System.currentTimeMillis()
        return "Sorry, Boss. I've noted it: you said \"$said\". Say it another way and I'll learn what you meant."
    }

    // ---- learning from corrections ---------------------------------------------------------------------------------

    private const val LEARNED = "jarvis.learned"

    fun learned(): List<com.optionslab.ira.Corrections.Learned> = runCatching {
        val a = JSONArray(prefs().getString(LEARNED) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> com.optionslab.ira.Corrections.Learned(o.getString("w"), o.getString("r")) } }
    }.getOrDefault(emptyList())

    /** The words just marked wrong, and when: the next question understood within two minutes is what was meant. */
    @Volatile private var awaiting: Pair<String, Long>? = null

    /**
     * After a question is understood: if Boss just said "that was wrong", the misunderstood words are learned as this
     * question (questions only). Returns what to say about it, or null.
     */
    @Synchronized fun maybeLearn(question: String): String? {
        val (wrong, at) = awaiting ?: return null
        if (System.currentTimeMillis() - at > 120_000) { awaiting = null; return null }
        val l = com.optionslab.ira.Corrections.learn(wrong, com.optionslab.ira.Secrets.redact(question)) ?: return null
        awaiting = null
        val all = (learned().filter { it.wrong != l.wrong } + l).takeLast(com.optionslab.ira.Corrections.KEEP)
        prefs().put(LEARNED, JSONArray().apply { all.forEach { put(JSONObject().put("w", it.wrong).put("r", it.right)) } }.toString())
        IraActivity.add("Learned: \"${l.wrong}\" means \"${l.right}\".")
        return "Got it, Boss: next time \"${l.wrong}\" means \"${l.right}\"."
    }

    fun forgetLearned() { prefs().put(LEARNED, null); awaiting = null }

    // ---- the day's usage -----------------------------------------------------------------------------------------

    private fun dayKey() = "jarvis.usage.${com.optionslab.app.data.Market.today()}"

    /** One more of [what] today: "heard", "misunderstood", "nameFirst", "failed", "mistakes". */
    @Synchronized fun count(what: String) = runCatching {
        val o = JSONObject(prefs().getString(dayKey()) ?: "{}")
        o.put(what, o.optInt(what) + 1)
        prefs().put(dayKey(), o.toString())
    }

    fun usageToday(): com.optionslab.ira.Usage.Day = runCatching {
        val o = JSONObject(prefs().getString(dayKey()) ?: "{}")
        com.optionslab.ira.Usage.Day(o.optInt("heard"), o.optInt("misunderstood"), o.optInt("nameFirst"), o.optInt("failed"), o.optInt("mistakes"))
    }.getOrDefault(com.optionslab.ira.Usage.Day())

    // ---- voice: short answers, wake sensitivity --------------------------------------------------------------------

    var brief: Boolean
        get() = runCatching { prefs().getBoolean("jarvis.brief", false) }.getOrDefault(false)
        set(v) { runCatching { prefs().put("jarvis.brief", v) } }

    var wakeStrict: Boolean
        get() = runCatching { prefs().getBoolean("jarvis.wake.strict", false) }.getOrDefault(false)
        set(v) { runCatching { prefs().put("jarvis.wake.strict", v) } }

    // ---- Jarvis's weekly loss cap ----------------------------------------------------------------------------------

    var weeklyLimit: Double
        get() = runCatching { prefs().getString("jarvis.trades.weekly")?.toDouble() }.getOrNull() ?: com.optionslab.ira.WeeklyCap.DEFAULT
        set(v) { runCatching { prefs().put("jarvis.trades.weekly", v.toString()) } }

    fun weeklyHit(): Boolean = com.optionslab.ira.WeeklyCap.hit(
        IraNewsTrades.closedRecord().map { it.day to it.rupees }, com.optionslab.app.data.Market.today(), weeklyLimit)

    // ---- practice on a past day ----------------------------------------------------------------------------------

    /**
     * "Practice on last Thursday": that day's candles replayed through the pattern expert as it watched live, each
     * suggestion played on the day's real option prices; told in the chat a few seconds apart, then summed up.
     */
    suspend fun practice(text: String, say: (String) -> Unit) {
        val today = com.optionslab.app.data.Market.today()
        val m = com.optionslab.ira.Market.mentioned(text).firstOrNull { it in IraStudy.MARKETS } ?: com.optionslab.ira.Market.BANKNIFTY
        val u = m.name
        // No day named: the last session on the phone before today (a Monday's "yesterday" is no session).
        val day = com.optionslab.ira.Practice.day(text, today)
            ?: runCatching { com.optionslab.app.data.Store.barDays(u).lastOrNull { it.isBefore(today) } }.getOrNull() ?: today.minusDays(1)
        // Streamed, never all held at once (years of days with their option prices): the 20 days before, then the day.
        val earlier = ArrayDeque<List<com.optionslab.ira.Candle>>()
        var session: com.optionslab.engine.Session? = null
        runCatching {
            for (s in com.optionslab.app.data.Store.barSessions(u)) {
                if (s.day.isBefore(day)) { s.index?.let { earlier.addLast(IraHub.candles(s.day, it)); if (earlier.size > 20) earlier.removeFirst() } }
                else if (s.day == day) { session = s; break }
            }
        }
        val found = session
        val ix = found?.index ?: run { say("I don't have ${m.label}'s prices for $day on the phone, so I can't replay it."); return }
        val prior = earlier.flatten()
        val edges = IraStudy.state.value.edges
        if (edges.none { it.tradable }) { say("I haven't studied the patterns yet (the nightly study), so there is nothing to replay."); return }
        say("Practice on $day, ${m.label}: replaying the day as I watched it...")
        val events = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.optionslab.ira.Practice.run(m, prior, IraHub.candles(day, ix), edges, found, com.optionslab.ira.JarvisTrades.strikeStep(u))
        }
        for (e in events) { say(e.text); kotlinx.coroutines.delay(1_500) }
        say(com.optionslab.ira.Practice.summary(m, day, events))
        IraActivity.add("Practised on $day (${m.label}): ${events.size} suggestions.")
    }
}
