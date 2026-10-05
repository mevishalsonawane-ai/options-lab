package com.optionslab.app.ira

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Making real use count (Jarvis, the owner's wishes 2026-10-03): the mistakes list, the day's usage counts for the
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

    // ---- which kinds of answer he gets wrong ([com.optionslab.ira.SelfDoubt]) --------------------------------------

    /** Question kinds asked a day (kind keys and counts only, never the words), the last 30 days. */
    private const val ASKED_KINDS = "jarvis.askedKinds"

    fun askedKinds(): com.optionslab.ira.DoubtTally = runCatching {
        val o = JSONObject(prefs().getString(ASKED_KINDS) ?: "{}")
        o.keys().asSequence().associate { d ->
            val day = o.getJSONObject(d)
            java.time.LocalDate.parse(d) to day.keys().asSequence().associateWith { k -> day.optInt(k) }
        }
    }.getOrDefault(emptyMap())

    /** One more question of [said]'s kinds asked today (a command or an order adds nothing). */
    @Synchronized fun countAsked(said: String) {
        runCatching {
            val t = com.optionslab.ira.SelfDoubt.count(askedKinds(), com.optionslab.app.data.Market.today(), said)
            val o = JSONObject().apply { t.forEach { (d, m) -> put(d.toString(), JSONObject().apply { m.forEach { (k, n) -> put(k, n) } }) } }
            prefs().putAllSoon(mapOf(ASKED_KINDS to o.toString()))
        }
    }

    /** How careful to be answering [said], from the answers Boss marked wrong (words only: it never acts). */
    fun doubt(said: String): com.optionslab.ira.SelfDoubt.Caution = runCatching {
        com.optionslab.ira.SelfDoubt.judge(said, weakAnswers())
    }.getOrDefault(com.optionslab.ira.SelfDoubt.NONE)

    /** The kinds he doubts now, worked out at most every 10 minutes or when a new mistake is marked (not on every question). */
    @Volatile private var weakCache: Triple<String, Long, List<com.optionslab.ira.SelfDoubt.Record>>? = null

    private fun weakAnswers(): List<com.optionslab.ira.SelfDoubt.Record> {
        val all = mistakes()
        val today = com.optionslab.app.data.Market.today()
        val key = "${all.size}|${all.lastOrNull()?.at}|$today"
        weakCache?.takeIf { it.first == key && System.currentTimeMillis() - it.second < 10 * 60_000L }?.let { return it.third }
        val weak = com.optionslab.ira.SelfDoubt.weak(all, askedKinds(), today)
        weakCache = Triple(key, System.currentTimeMillis(), weak)
        return weak
    }

    // ---- learning from corrections ---------------------------------------------------------------------------------

    private const val LEARNED = "jarvis.learned"

    fun learned(): List<com.optionslab.ira.Corrections.Learned> = runCatching {
        val a = JSONArray(prefs().getString(LEARNED) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> com.optionslab.ira.Corrections.Learned(o.getString("w"), o.getString("r")) } }
    }.getOrDefault(emptyList())

    /** The words just marked wrong, and when: the next question understood within two minutes is what was meant. */
    @Volatile private var awaiting: Pair<String, Long>? = null
    /** Words Jarvis just could not place (said "I'm not sure"): Boss's next wording within 45 s may teach them. */
    @Volatile private var missedLast: Pair<String, Long>? = null

    /** Jarvis could not place [said]: kept a moment, in case Boss says it another way. */
    @Synchronized fun missed(said: String) {
        val w = com.optionslab.ira.Secrets.redact(said)
        if (w.isNotBlank()) runCatching { prefs().put(MISSED, JSONObject().put("d", com.optionslab.app.data.Market.today().toString())
            .put("w", JSONArray(com.optionslab.ira.Missed.add(missedToday(), w))).toString()) }
        missedLast = if (runCatching { com.optionslab.ira.Corrections.missed(w) }.getOrDefault(false)) w to System.currentTimeMillis() else null
        nextAfterMiss = null
    }

    /** The first other words asked after a missed question: only they may teach it (not a command or question between). */
    @Volatile private var nextAfterMiss: String? = null

    /** Every question Boss asks, as it arrives. */
    @Synchronized fun asked(said: String) {
        val m = missedLast ?: return
        if (nextAfterMiss == null && said != m.first) nextAfterMiss = said
    }

    /**
     * After a question is understood: if Boss just said "that was wrong", the misunderstood words are learned as this
     * question (questions only). Returns what to say about it, or null.
     */
    @Synchronized fun maybeLearn(question: String): String? {
        // Only the very next question counts as the rephrasing of a missed one.
        val miss = missedLast; missedLast = null
        val next = nextAfterMiss; nextAfterMiss = null
        if (awaiting == null && miss != null && next == question && System.currentTimeMillis() - miss.second <= com.optionslab.ira.Corrections.REPHRASE_MS) {
            val l = com.optionslab.ira.Corrections.rephrase(miss.first, com.optionslab.ira.Secrets.redact(question)) ?: return null
            keep(l)
            return "Noted, Boss: when you say \"${l.wrong}\", I'll take it as \"${l.right}\". (Say \"that was wrong\" or \"forget what you learned\" if not.)"
        }
        val (wrong, at) = awaiting ?: return null
        if (System.currentTimeMillis() - at > 120_000) { awaiting = null; return null }
        val l = com.optionslab.ira.Corrections.learn(wrong, com.optionslab.ira.Secrets.redact(question)) ?: return null
        awaiting = null
        keep(l)
        return "Got it, Boss: next time \"${l.wrong}\" means \"${l.right}\"."
    }

    private fun keep(l: com.optionslab.ira.Corrections.Learned) {
        val all = (learned().filter { it.wrong != l.wrong } + l).takeLast(com.optionslab.ira.Corrections.KEEP)
        prefs().put(LEARNED, JSONArray().apply { all.forEach { put(JSONObject().put("w", it.wrong).put("r", it.right)) } }.toString())
        IraActivity.add("Learned: \"${l.wrong}\" means \"${l.right}\".")
    }

    /** One key, today's words only (an older day's list is replaced, never kept). */
    private const val MISSED = "jarvis.missed"

    /** Today's words Jarvis could not place (redacted, each once). */
    fun missedToday(): List<String> = runCatching {
        val o = JSONObject(prefs().getString(MISSED) ?: return emptyList())
        if (o.optString("d") != com.optionslab.app.data.Market.today().toString()) return emptyList()
        val a = o.getJSONArray("w"); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun forgetLearned() { prefs().put(LEARNED, null); awaiting = null; missedLast = null }

    /**
     * The words Jarvis could not place on the last day before [day] that had any (at most 4 days back), for his
     * overnight study ([com.optionslab.ira.Agenda.study]).
     */
    fun missedBefore(day: java.time.LocalDate): List<String> = runCatching {
        val o = JSONObject(prefs().getString(MISSED) ?: return emptyList())
        val d = java.time.LocalDate.parse(o.getString("d"))
        if (!d.isBefore(day) || d.isBefore(day.minusDays(4))) return emptyList()
        val a = o.getJSONArray("w"); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /** A lesson Boss approved from Jarvis's study ("did you mean ...?" - yes): kept like a correction (questions only). */
    @Synchronized fun teach(l: com.optionslab.ira.Corrections.Learned) {
        // Checked again here: only a question is ever learned, never anything that acts.
        if (com.optionslab.ira.Corrections.learn(l.wrong, l.right) == null) return
        keep(l)
    }

    /** Jarvis just asked Boss to say [said] another way: his next wording may teach it, as after a live miss. */
    @Synchronized fun expectRephrase(said: String) {
        missedLast = if (runCatching { com.optionslab.ira.Corrections.missed(said) }.getOrDefault(false)) said to System.currentTimeMillis() else null
        nextAfterMiss = null
    }

    // ---- Boss's own words, kept ---------------------------------------------------------------------------------------

    private const val MEMORY = "jarvis.memory"

    fun memory(): List<com.optionslab.ira.Memory.Item> = runCatching {
        val a = JSONArray(prefs().getString(MEMORY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> com.optionslab.ira.Memory.Item(java.time.LocalDate.parse(o.getString("d")), o.getString("t")) } }
    }.getOrDefault(emptyList())

    @Synchronized fun remember(text: String) {
        val all = (memory() + com.optionslab.ira.Memory.Item(com.optionslab.app.data.Market.today(), com.optionslab.ira.Secrets.redact(text).take(300)))
            .takeLast(com.optionslab.ira.Memory.KEEP)
        saveMemory(all)
    }

    private fun saveMemory(all: List<com.optionslab.ira.Memory.Item>) {
        prefs().put(MEMORY, JSONArray().apply { all.forEach { put(JSONObject().put("d", it.day.toString()).put("t", it.text)) } }.toString())
    }

    /**
     * "Forget that" / "forget that I don't trade on Fridays": the one note [f] names dropped ([com.optionslab.ira.AboutBoss.pick]),
     * returned (null: none matched, nothing changed).
     */
    @Synchronized fun forgetOne(f: com.optionslab.ira.AboutBoss.Forget): com.optionslab.ira.Memory.Item? {
        val all = memory()
        val item = com.optionslab.ira.AboutBoss.pick(all, f) ?: return null
        val at = all.lastIndexOf(item)
        if (at < 0) return null
        saveMemory(all.filterIndexed { n, _ -> n != at })
        return item
    }

    fun forgetMemory() { runCatching { prefs().put(MEMORY, null) } }

    // ---- Boss's habits ----------------------------------------------------------------------------------------------

    private const val HABITS = "jarvis.habits"

    fun habits(): com.optionslab.ira.HabitCounts = runCatching {
        val o = JSONObject(prefs().getString(HABITS) ?: "{}")
        o.keys().asSequence().associateWith { k -> val a = o.getJSONArray(k); IntArray(24) { a.optInt(it) } }
    }.getOrDefault(emptyMap())

    fun forgetHabits() { runCatching { prefs().put(HABITS, null); prefs().put(HABITS_LAST, null) } }

    /** When each habit's question was last asked (a habit not asked for a week is not offered unasked). */
    private const val HABITS_LAST = "jarvis.habits.last"

    fun habitsLast(): Map<String, java.time.LocalDate> = runCatching {
        val o = JSONObject(prefs().getString(HABITS_LAST) ?: "{}")
        o.keys().asSequence().mapNotNull { k -> runCatching { k to java.time.LocalDate.parse(o.getString(k)) }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    /** A market question Boss asked, counted at this hour (nothing else is counted). */
    @Synchronized fun noteHabit(question: String) {
        runCatching {
            val k = com.optionslab.ira.Habits.key(question) ?: return
            val c = com.optionslab.ira.Habits.add(habits(), k, LocalDateTime.now(IST).hour)
            prefs().put(HABITS, JSONObject().apply { c.forEach { (key, row) -> put(key, JSONArray(row.toList())) } }.toString())
            val last = (habitsLast() + (k to LocalDateTime.now(IST).toLocalDate())).filterKeys { it in c.keys }
            prefs().put(HABITS_LAST, JSONObject().apply { last.forEach { (key, d) -> put(key, d.toString()) } }.toString())
        }
    }

    // ---- the day's usage -----------------------------------------------------------------------------------------

    private fun dayKey() = "jarvis.usage.${com.optionslab.app.data.Market.today()}"

    /** One more of [what] today: "heard", "misunderstood", "nameFirst", "failed", "mistakes". */
    @Synchronized fun count(what: String) = runCatching {
        val o = JSONObject(prefs().getString(dayKey()) ?: "{}")
        o.put(what, o.optInt(what) + 1)
        prefs().put(dayKey(), o.toString())
    }

    /** How many of [what] today. */
    fun countToday(what: String): Int = runCatching { JSONObject(prefs().getString(dayKey()) ?: "{}").optInt(what) }.getOrDefault(0)

    /**
     * Boss cut Jarvis short ("stop", "bas") three times today with short answers off: a suggestion, once a day, to turn
     * short answers on (his own initiative from how Boss listens; the setting is Boss's to change).
     */
    fun noteHush() {
        count("hush")
        if (brief || countToday("hush") != 3) return
        runCatching { IraHub.note("Boss, you've stopped me a few times today. Want shorter answers? Say \"Jarvis, short answers\" - and \"tell me more\" when you want the rest.") }
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
