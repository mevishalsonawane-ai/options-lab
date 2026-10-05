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
        return "Sorry, Boss. I've noted it: you said \"$said\". Say it another way and I'll ask whether to learn what you meant."
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
            clarityAsked(said)
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

    // ---- which unasked alerts Boss follows up ([com.optionslab.ira.AlertSense]) --------------------------------------

    /** The alerts said aloud and held back (kind names and minutes only, never the words), the last 14 days. */
    private const val ALERTS = "jarvis.alertSense"
    @Volatile private var alertCache: com.optionslab.ira.AlertSense.Log? = null

    fun alertLog(): com.optionslab.ira.AlertSense.Log = alertCache ?: runCatching {
        val o = JSONObject(prefs().getString(ALERTS) ?: "{}")
        val s = o.optJSONArray("s") ?: JSONArray()
        val h = o.optJSONArray("h") ?: JSONArray()
        val k = o.optJSONObject("k") ?: JSONObject()
        com.optionslab.ira.AlertSense.Log(
            said = (0 until s.length()).map { i -> s.getJSONObject(i).let { x ->
                com.optionslab.ira.AlertSense.Said(x.getString("k"), LocalDateTime.parse(x.getString("t")),
                    x.optString("r").takeIf { it.isNotEmpty() }?.let { r -> runCatching { com.optionslab.ira.AlertSense.Reaction.valueOf(r) }.getOrNull() })
            } },
            held = (0 until h.length()).map { i -> h.getJSONObject(i).let { x -> x.getString("k") to LocalDateTime.parse(x.getString("t")) } },
            skipped = k.keys().asSequence().associateWith { n -> k.optInt(n) },
            resetAt = o.optString("r").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.AlertSense.Log()).also { alertCache = it }

    @Synchronized private fun alertUpdate(f: (com.optionslab.ira.AlertSense.Log) -> com.optionslab.ira.AlertSense.Log) {
        runCatching {
            val was = alertLog()
            val log = f(was)
            if (log == was) return@runCatching
            alertCache = log
            val o = JSONObject()
                .put("s", JSONArray().apply { log.said.forEach { x -> put(JSONObject().put("k", x.kind).put("t", x.at.toString()).apply { x.reaction?.let { put("r", it.name) } }) } })
                .put("h", JSONArray().apply { log.held.forEach { (k, t) -> put(JSONObject().put("k", k).put("t", t.toString())) } })
                .put("k", JSONObject().apply { log.skipped.forEach { (k, n) -> put(k, n) } })
            log.resetAt?.let { o.put("r", it.toString()) }
            prefs().putAllSoon(mapOf(ALERTS to o.toString()))
        }
    }

    private fun minuteNow(): LocalDateTime = LocalDateTime.now(IST).withSecond(0).withNano(0)

    /**
     * An unasked market alert ([a]) aloud - or, a kind Boss keeps letting pass, now and then only (every caller has
     * already put the line in the chat, so nothing is lost). Only market colour is ever held back: any other kind, every
     * safety warning among them, is said as always. True when it was said.
     */
    fun sayAlert(a: Automations.Auto, text: String): Boolean {
        if (!alertAloud(a)) return false
        val said = JarvisVoice.announce(text)
        // Only alerts Boss could hear are judged by what he did next.
        if (said) alertSaid(listOf(a))
        return said
    }

    /** Whether an alert of kind [a] is said aloud now (the learned say); one held back is recorded as such. */
    fun alertAloud(a: Automations.Auto): Boolean {
        val now = minuteNow()
        val aloud = runCatching { com.optionslab.ira.AlertSense.aloud(alertLog(), a.name, now) }.getOrDefault(true)
        if (!aloud) alertUpdate { com.optionslab.ira.AlertSense.heldBack(it, a.name, now) }
        return aloud
    }

    /** The record that has a kind [a] said less often ("fear (VIX) spikes (you followed up 1 of my last 6)"), or null. */
    fun alertRecord(a: Automations.Auto): String? =
        runCatching { com.optionslab.ira.AlertSense.records(alertLog(), minuteNow()).firstOrNull { it.kind == a.name }?.say() }.getOrNull()

    /** Alerts of the kinds [kinds] were just said aloud (one merged line may hold several, [IraAirtime]). */
    fun alertSaid(kinds: Collection<Automations.Auto>) {
        val now = minuteNow()
        kinds.distinct().forEach { k -> alertUpdate { com.optionslab.ira.AlertSense.spoken(it, k.name, now) } }
    }

    /** Boss asked something, opened the app or muted Jarvis: the alert said just before it is marked (no write otherwise). */
    fun alertBoss(what: com.optionslab.ira.AlertSense.Boss) {
        val now = minuteNow()
        if (!runCatching { com.optionslab.ira.AlertSense.waiting(alertLog(), now) }.getOrDefault(false)) return
        alertUpdate { com.optionslab.ira.AlertSense.boss(it, what, now) }
    }

    /** "Which alerts do you hold back?". */
    fun alertsHeld(): String = runCatching { com.optionslab.ira.AlertSense.say(alertLog(), minuteNow()) }
        .getOrDefault("I could not read my alert record just now, Boss.")

    /** "Say everything again": every alert aloud again, the count started afresh. */
    fun alertsAll(): String {
        val now = minuteNow()
        val said = runCatching { com.optionslab.ira.AlertSense.sayAll(alertLog(), now) }.getOrDefault("Done, Boss: I'll say every alert aloud again.")
        alertUpdate { com.optionslab.ira.AlertSense.reset(it, now) }
        IraActivity.add("Saying every alert aloud again (as asked).")
        return said
    }

    /** For the evening review: the kinds said less often now (kind names). */
    fun alertQuietKeys(): Set<String> = runCatching { com.optionslab.ira.AlertSense.quietKeys(alertLog(), minuteNow()) }.getOrDefault(emptySet())

    fun alertReview(before: Set<String>?): List<String> = runCatching { com.optionslab.ira.AlertSense.review(alertLog(), minuteNow(), before) }.getOrDefault(emptyList())

    // ---- how old his own data is ([com.optionslab.ira.DataAge]) ------------------------------------------------------

    /** The day's freshness record: counts, times and source names only (never a price or a headline), the last 14 days. */
    private const val FRESH = "jarvis.dataAge"
    @Volatile private var freshCache: com.optionslab.ira.DataAge.Log? = null
    /** When the record was last written this run (null: not yet). */
    @Volatile private var freshSavedAt: Long? = null

    fun freshLog(): com.optionslab.ira.DataAge.Log = freshCache ?: runCatching {
        val a = JSONArray(prefs().getString(FRESH) ?: "[]")
        com.optionslab.ira.DataAge.Log((0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            val sp = o.optJSONArray("s") ?: JSONArray()
            com.optionslab.ira.DataAge.Day(java.time.LocalDate.parse(o.getString("d")), o.optInt("c"), o.optInt("o"), o.optInt("a"),
                o.optInt("w"), o.optInt("h"), (0 until sp.length()).mapNotNull { j -> runCatching { sp.getJSONObject(j).let { x ->
                    com.optionslab.ira.DataAge.Spell(com.optionslab.ira.DataAge.Source.valueOf(x.getString("k")), LocalDateTime.parse(x.getString("f")),
                        LocalDateTime.parse(x.getString("t")), x.optLong("w")) } }.getOrNull() })
        } })
    }.getOrDefault(com.optionslab.ira.DataAge.Log()).also { freshCache = it }

    @Synchronized private fun freshUpdate(f: (com.optionslab.ira.DataAge.Log) -> com.optionslab.ira.DataAge.Log) {
        runCatching {
            val was = freshLog()
            val log = f(was)
            if (log == was) return@runCatching
            freshCache = log
            // The watch's check every minute only adds to the counts: that is written at most every 10 minutes (the newest
            // is in memory, so every answer reads it); a new day, a spell of old data or an answer is written at once.
            val nowMs = System.currentTimeMillis()
            if (!com.optionslab.ira.Upkeep.dueToSave(freshSavedAt, nowMs, com.optionslab.ira.DataAge.material(was, log))) return@runCatching
            freshSavedAt = nowMs
            val a = JSONArray().apply { log.days.forEach { d -> put(JSONObject().put("d", d.day.toString()).put("c", d.checks).put("o", d.old)
                .put("a", d.answers).put("w", d.warned).put("h", d.withheld)
                .put("s", JSONArray().apply { d.spells.forEach { x -> put(JSONObject().put("k", x.source.name).put("f", x.from.toString())
                    .put("t", x.to.toString()).put("w", x.worstSec)) } })) } }
            prefs().putAllSoon(mapOf(FRESH to a.toString()))
        }
    }

    private fun secondNow(): LocalDateTime = LocalDateTime.now(IST).withNano(0)

    /** Checks the watch made (kept only while the market trades; nothing but their ages). */
    fun freshSeen(checks: List<com.optionslab.ira.DataAge.Check>) {
        if (checks.isEmpty()) return
        val now = secondNow()
        freshUpdate { com.optionslab.ira.DataAge.observe(it, checks, now) }
    }

    /** A market answer given on [checks]: whether it carried an age note, and whether its prices were held back. */
    fun freshAnswered(checks: List<com.optionslab.ira.DataAge.Check>, warned: Boolean, withheld: Boolean) {
        if (checks.isEmpty()) return
        val now = secondNow()
        freshUpdate { com.optionslab.ira.DataAge.answered(it, checks, warned, withheld, now) }
    }

    /** "Is your data fresh?": each source's age now and today's record. */
    fun freshSay(checks: List<com.optionslab.ira.DataAge.Check>, trading: Boolean): String =
        runCatching { com.optionslab.ira.DataAge.say(checks, freshLog(), secondNow(), trading) }
            .getOrDefault("I could not read my freshness record just now, Boss.")

    /** For the evening review: how often and how long his data was old today. */
    fun freshReview(): List<String> =
        runCatching { com.optionslab.ira.DataAge.review(freshLog(), com.optionslab.app.data.Market.today()) }.getOrDefault(emptyList())

    /** The minute each option chain read last is from (underlying name to minute), for its age. */
    private val chainAt = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    /** The option chain of [u] was read: its data is from [at] (null: unknown, nothing kept). */
    fun chainSeen(u: String, at: LocalDateTime?) { if (at != null) chainAt[u] = at }

    /** How old the last chain of [u] is, or null when none was read. */
    fun chainCheck(u: String): com.optionslab.ira.DataAge.Check? {
        val now = secondNow()
        return com.optionslab.ira.DataAge.chain(u, chainAt[u], now, com.optionslab.ira.Market.NIFTY.trading(now))
    }

    /** Said before an option-chain answer when that chain is old (kept in the day's record too), else null. */
    fun chainNote(u: String): String? = runCatching {
        val c = chainCheck(u) ?: return@runCatching null
        freshSeen(listOf(c))
        com.optionslab.ira.DataAge.note(c, secondNow())
    }.getOrNull()

    // ---- learning from corrections ---------------------------------------------------------------------------------

    private const val LEARNED = "jarvis.learned"

    /**
     * The wordings learned and still in use: one unused for [com.optionslab.ira.Corrections.EXPIRE_DAYS] days is dropped
     * (and one kept before days were noted counts from today).
     */
    @Synchronized fun learned(): List<com.optionslab.ira.Corrections.Learned> = runCatching {
        val a = JSONArray(prefs().getString(LEARNED) ?: "[]")
        val today = com.optionslab.app.data.Market.today()
        val all = (0 until a.length()).map { a.getJSONObject(it).let { o ->
            val u = o.optString("u").takeIf { it.isNotEmpty() }?.let { s -> runCatching { java.time.LocalDate.parse(s) }.getOrNull() }
            // The day of Boss's yes (none on wordings kept before it was noted).
            val since = o.optString("s").takeIf { it.isNotEmpty() }?.let { s -> runCatching { java.time.LocalDate.parse(s) }.getOrNull() }
            com.optionslab.ira.Corrections.Learned(o.getString("w"), o.getString("r"), u ?: today, since) } }
        val fresh = com.optionslab.ira.Corrections.fresh(all, today)
        if (fresh.size != all.size || (0 until a.length()).any { a.getJSONObject(it).optString("u").isEmpty() }) saveLearned(fresh)
        fresh
    }.getOrDefault(emptyList())

    private fun saveLearned(all: List<com.optionslab.ira.Corrections.Learned>) {
        prefs().put(LEARNED, JSONArray().apply { all.forEach { l -> put(JSONObject().put("w", l.wrong).put("r", l.right)
            .put("u", (l.used ?: com.optionslab.app.data.Market.today()).toString()).apply { l.since?.let { put("s", it.toString()) } }) } }.toString())
    }

    /** A learned wording was just read as meant: it stays another 60 days. */
    @Synchronized fun usedLearned(l: com.optionslab.ira.Corrections.Learned) {
        runCatching { saveLearned(com.optionslab.ira.Corrections.touch(learned(), l, com.optionslab.app.data.Market.today())) }
    }

    /** "Forget the word X": the wordings dropped (none when X was not learned). */
    @Synchronized fun forgetWord(word: String): List<com.optionslab.ira.Corrections.Learned> {
        val (kept, gone) = com.optionslab.ira.Corrections.forget(learned(), word)
        if (gone.isNotEmpty()) { saveLearned(kept); IraActivity.add("Forgot ${gone.size} learned wording(s), as Boss asked.") }
        return gone
    }

    /** The words just marked wrong, and when: the next question understood within a minute may be what was meant. */
    @Volatile private var awaiting: Pair<String, Long>? = null
    /** Words Jarvis just could not place (said "I'm not sure"): Boss's next wording within a minute may teach them. */
    @Volatile private var missedLast: Pair<String, Long>? = null
    /** Wordings already put to Boss (this run): not asked again, whatever he answered. */
    private val offeredOnce = java.util.Collections.synchronizedSet(HashSet<Pair<String, String>>())

    /** [l] was just put to Boss: not offered again this run. */
    fun offered(l: com.optionslab.ira.Corrections.Learned) { offeredOnce += l.wrong to l.right }

    /** Jarvis could not place [said]: kept a moment, in case Boss says it another way. */
    @Synchronized fun missed(said: String) {
        val w = com.optionslab.ira.Secrets.redact(said)
        if (w.isNotBlank()) runCatching { prefs().put(MISSED, JSONObject().put("d", com.optionslab.app.data.Market.today().toString())
            .put("w", JSONArray(com.optionslab.ira.Missed.add(missedToday(), w))).toString()) }
        // Also kept for the week (two weeks at most): what his weekly goals of understanding are set on.
        if (w.isNotBlank()) runCatching {
            val all = com.optionslab.ira.Improve.addMissed(missedWeek(), com.optionslab.app.data.Market.today(), w)
            prefs().put(MISSED_WEEK, JSONArray().apply { all.forEach { put(JSONObject().put("d", it.first.toString()).put("w", it.second)) } }.toString())
        }
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
     * After a question is understood: if Boss just rephrased words Jarvis could not place, or said "that was wrong" a
     * moment ago ([com.optionslab.ira.Corrections.REPHRASE_MS]), the wording to put to him ("Shall I take ... to mean
     * ...?"), or null. Nothing is kept here: only his yes keeps it ([teach]), and neither wording may act.
     */
    @Synchronized fun proposal(question: String): com.optionslab.ira.Corrections.Learned? {
        // Only the very next question counts as the rephrasing of a missed one.
        val miss = missedLast; missedLast = null
        val next = nextAfterMiss; nextAfterMiss = null
        val known = learned()
        fun fresh(l: com.optionslab.ira.Corrections.Learned?) = l?.takeIf { (it.wrong to it.right) !in offeredOnce }
        if (awaiting == null && miss != null && next == question && System.currentTimeMillis() - miss.second <= com.optionslab.ira.Corrections.REPHRASE_MS)
            return fresh(com.optionslab.ira.Corrections.propose(miss.first, com.optionslab.ira.Secrets.redact(question), missed = true, learned = known))
        val (wrong, at) = awaiting ?: return null
        if (System.currentTimeMillis() - at > com.optionslab.ira.Corrections.REPHRASE_MS) { awaiting = null; return null }
        val l = com.optionslab.ira.Corrections.propose(wrong, com.optionslab.ira.Secrets.redact(question), missed = false, learned = known) ?: return null
        awaiting = null
        return fresh(l)
    }

    private fun keep(l: com.optionslab.ira.Corrections.Learned) {
        val today = com.optionslab.app.data.Market.today()
        val all = (learned().filter { it.wrong != l.wrong } + l.copy(used = today, since = today)).takeLast(com.optionslab.ira.Corrections.KEEP)
        saveLearned(all)
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

    /** The phrasings missed over the last two weeks, with their day (redacted), for his weekly goals ([com.optionslab.ira.Improve]). */
    private const val MISSED_WEEK = "jarvis.missed.week"

    fun missedWeek(): List<Pair<java.time.LocalDate, String>> = runCatching {
        val a = JSONArray(prefs().getString(MISSED_WEEK) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> java.time.LocalDate.parse(o.getString("d")) to o.getString("w") } }
    }.getOrDefault(emptyList())

    fun forgetLearned() { prefs().put(LEARNED, null); awaiting = null; missedLast = null; offeredOnce.clear() }

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
        // What is kept is that checked wording itself (never acting, never about the wordings themselves).
        val ok = com.optionslab.ira.Corrections.learn(l.wrong, l.right) ?: return
        if (!com.optionslab.ira.Corrections.safe(ok)) return
        keep(ok)
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

    /**
     * A market question Boss asked, counted at this hour (nothing else is counted); and his routine noted (the question's
     * key only: a market question, his P&L or the week's events - [com.optionslab.ira.Routine.key]). Returns a habit of
     * his to put to him now, or null; nothing is kept from it without his yes ([keepRoutine]).
     */
    @Synchronized fun noteHabit(question: String, afterLoss: Boolean = false): com.optionslab.ira.Routine.Pattern? {
        val now = LocalDateTime.now(IST)
        // One vault write for all of it - the habit, its day, the day's count and the routine (each write re-encrypts the
        // whole vault, and this runs on every market question): the same values as before.
        val writes = HashMap<String, Any?>()
        runCatching {
            val k = com.optionslab.ira.Habits.key(question)
            if (k != null) {
                val c = com.optionslab.ira.Habits.add(habits(), k, now.hour)
                writes[HABITS] = JSONObject().apply { c.forEach { (key, row) -> put(key, JSONArray(row.toList())) } }.toString()
                val last = (habitsLast() + (k to now.toLocalDate())).filterKeys { it in c.keys }
                writes[HABITS_LAST] = JSONObject().apply { last.forEach { (key, d) -> put(key, d.toString()) } }.toString()
                // And counted for the day: "the question you asked most" in his weekly review.
                writes.putAll(counted(com.optionslab.ira.Improve.ASKED_PREFIX + k))
            }
        }
        val p = runCatching { noteRoutine(question, now, afterLoss, writes) }.getOrNull()
        if (writes.isNotEmpty()) runCatching { prefs().putAll(writes) }
        return p
    }

    // ---- Boss's routine ([com.optionslab.ira.Routine]) --------------------------------------------------------------

    private const val ROUTINE_LOG = "jarvis.routine.log"
    private const val ROUTINE_KEPT = "jarvis.routine.kept"
    private const val ROUTINE_OFFERED = "jarvis.routine.offered"

    /** What Boss asked and when (keys only, never his words), the last eight weeks. */
    fun routineLog(): List<com.optionslab.ira.Routine.Seen> = runCatching {
        val a = JSONArray(prefs().getString(ROUTINE_LOG) ?: "[]")
        (0 until a.length()).mapNotNull { com.optionslab.ira.Routine.decode(a.optString(it)) }
    }.getOrDefault(emptyList())

    /** The routines Boss said yes to (said at their time, words only). */
    fun routineKept(): List<com.optionslab.ira.Routine.Kept> = runCatching {
        val a = JSONArray(prefs().getString(ROUTINE_KEPT) ?: "[]")
        (0 until a.length()).mapNotNull { com.optionslab.ira.Routine.decodeKept(a.optString(it)) }
    }.getOrDefault(emptyList())

    /** Boss's routines found now, strongest first. */
    fun routines(): List<com.optionslab.ira.Routine.Pattern> = com.optionslab.ira.Routine.patterns(routineLog(), com.optionslab.app.data.Market.today())

    private fun routineOfferedAt(): Map<String, java.time.LocalDate> = runCatching {
        val o = JSONObject(prefs().getString(ROUTINE_OFFERED) ?: "{}")
        o.keys().asSequence().mapNotNull { k -> runCatching { k to java.time.LocalDate.parse(o.getString(k)) }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    private fun saveKept(all: List<com.optionslab.ira.Routine.Kept>) {
        prefs().put(ROUTINE_KEPT, JSONArray(all.map { com.optionslab.ira.Routine.encode(it) }).toString())
    }

    /** [question] noted in Boss's routine; what to write is added to [writes] (the caller writes it once). */
    private fun noteRoutine(question: String, now: LocalDateTime, afterLoss: Boolean, writes: MutableMap<String, Any?>): com.optionslab.ira.Routine.Pattern? {
        val k = com.optionslab.ira.Routine.key(question) ?: return null
        val log = com.optionslab.ira.Routine.add(routineLog(), com.optionslab.ira.Routine.Seen(k, now, afterLoss))
        writes[ROUTINE_LOG] = JSONArray(log.map { com.optionslab.ira.Routine.encode(it) }).toString()
        // Asked by himself: a routine of it he said yes to lasts on.
        var kept = routineKept()
        if (kept.any { it.key == k }) {
            kept = com.optionslab.ira.Routine.renew(kept, k, now.toLocalDate())
            writes[ROUTINE_KEPT] = JSONArray(kept.map { com.optionslab.ira.Routine.encode(it) }).toString()
        }
        return com.optionslab.ira.Routine.toOffer(com.optionslab.ira.Routine.patterns(log, now.toLocalDate()), k, kept, routineOfferedAt(), now.toLocalDate())
    }

    /** [p] was just put to Boss: not put again for a month, whatever he answers. */
    @Synchronized fun routineOffered(p: com.optionslab.ira.Routine.Pattern) {
        runCatching {
            val all = routineOfferedAt() + (p.slot to com.optionslab.app.data.Market.today())
            prefs().put(ROUTINE_OFFERED, JSONObject().apply { all.entries.sortedBy { it.value }.takeLast(40).forEach { (k, d) -> put(k, d.toString()) } }.toString())
        }
    }

    /** Boss said yes to [p]: kept (checked again: only a question's answer, words only). What to say, or null. */
    @Synchronized fun keepRoutine(p: com.optionslab.ira.Routine.Pattern): String? {
        val k = com.optionslab.ira.Routine.keep(p, com.optionslab.app.data.Market.today()) ?: return null
        saveKept(com.optionslab.ira.Routine.put(routineKept(), k))
        IraActivity.add("Routine kept, as Boss said yes: ${com.optionslab.ira.Routine.about(k.key)}.")
        return com.optionslab.ira.Routine.kept(k)
    }

    /** "Forget my routine": what was noted and kept, all dropped. */
    @Synchronized fun forgetRoutine() {
        runCatching { prefs().put(ROUTINE_LOG, null); prefs().put(ROUTINE_KEPT, null); prefs().put(ROUTINE_OFFERED, null) }
        IraActivity.add("Forgot Boss's routine, as he asked.")
    }

    // ---- which answers Boss finds unclear ([com.optionslab.ira.Clarity]) ------------------------------------------------

    /** The unclear answers noted (kind keys and minutes only, never the words), the last 30 days. */
    private const val CLARITY = "jarvis.clarity"
    @Volatile private var clarityCache: com.optionslab.ira.Clarity.Log? = null
    /** The kind of the question last asked and when (ms): a "what?" soon after is about its answer. Never the words. */
    @Volatile private var lastKind: Pair<String, Long>? = null
    /** The kinds said shorter now, worked out at most every 10 minutes or when the log changes. */
    @Volatile private var shortCache: Triple<String, Long, List<com.optionslab.ira.Clarity.Record>>? = null

    fun clarityLog(): com.optionslab.ira.Clarity.Log = clarityCache ?: runCatching {
        val o = JSONObject(prefs().getString(CLARITY) ?: "{}")
        val e = o.optJSONArray("e") ?: JSONArray()
        com.optionslab.ira.Clarity.Log(
            events = (0 until e.length()).map { i -> e.getJSONObject(i).let { x -> com.optionslab.ira.Clarity.Event(x.getString("k"), LocalDateTime.parse(x.getString("t"))) } },
            resetAt = o.optString("r").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.Clarity.Log()).also { clarityCache = it }

    @Synchronized private fun clarityUpdate(f: (com.optionslab.ira.Clarity.Log) -> com.optionslab.ira.Clarity.Log) {
        runCatching {
            val log = f(clarityLog())
            clarityCache = log
            shortCache = null
            val o = JSONObject().put("e", JSONArray().apply { log.events.forEach { x -> put(JSONObject().put("k", x.kind).put("t", x.at.toString())) } })
            log.resetAt?.let { o.put("r", it.toString()) }
            prefs().putAllSoon(mapOf(CLARITY to o.toString()))
        }
    }

    /** A question with a kind of answer was asked ([said]'s kind only is kept, in memory). */
    fun clarityAsked(said: String) {
        val k = runCatching { com.optionslab.ira.Clarity.kind(said) }.getOrNull() ?: return
        lastKind = k to System.currentTimeMillis()
    }

    /** Boss said [said]: "what?" / "come again" soon after a question notes that answer's kind as unclear (once an answer). */
    fun clarityHeard(said: String) {
        if (!runCatching { com.optionslab.ira.Clarity.unclear(said) }.getOrDefault(false)) return
        val last = lastKind ?: return
        if (System.currentTimeMillis() - last.second > com.optionslab.ira.Clarity.REACT_MS) return
        lastKind = null
        clarityUpdate { com.optionslab.ira.Clarity.heard(it, last.first, minuteNow()) }
    }

    private fun clarityShorter(): List<com.optionslab.ira.Clarity.Record> {
        val log = clarityLog()
        val now = minuteNow()
        val key = "${log.events.size}|${log.events.lastOrNull()?.at}|${log.resetAt}|${now.toLocalDate()}"
        shortCache?.takeIf { it.first == key && System.currentTimeMillis() - it.second < 10 * 60_000L }?.let { return it.third }
        val list = com.optionslab.ira.Clarity.shorter(log, askedKinds(), now)
        shortCache = Triple(key, System.currentTimeMillis(), list)
        return list
    }

    /** How many sentences to say aloud answering [said] (a kind Boss often asks "what?" after), or null: as usual. Voice only. */
    fun claritySentences(said: String): Int? = runCatching { com.optionslab.ira.Clarity.sentences(said, clarityShorter()) }.getOrNull()

    /** "Which answers do you keep short?". */
    fun clarityHeld(): String = runCatching { com.optionslab.ira.Clarity.say(clarityLog(), askedKinds(), minuteNow()) }
        .getOrDefault("I could not read my record of unclear answers just now, Boss.")

    /** "Say your answers in full again" (or Boss's own "full answers"): every answer as usual aloud, the count afresh. */
    fun clarityReset(): String {
        val now = minuteNow()
        val said = runCatching { com.optionslab.ira.Clarity.sayReset(clarityLog(), askedKinds(), now) }.getOrDefault("Done, Boss: every answer as usual aloud again.")
        clarityUpdate { com.optionslab.ira.Clarity.reset(it, now) }
        IraActivity.add("Saying every answer as usual aloud again (as asked).")
        return said
    }

    // ---- what he has learned, in one view ([com.optionslab.ira.Learnings]) ------------------------------------------

    /** Every learning store read with its own accessor (the goals are added by [IraImprove], which holds them). */
    fun learnings(plan: com.optionslab.ira.Improve.Plan?): com.optionslab.ira.Learnings.Inputs = com.optionslab.ira.Learnings.Inputs(
        words = runCatching { learned() }.getOrDefault(emptyList()),
        routines = runCatching { routineKept() }.getOrDefault(emptyList()),
        alerts = runCatching { alertLog() }.getOrDefault(com.optionslab.ira.AlertSense.Log()),
        paper = runCatching { IraNewsTrades.calibration() }.getOrDefault(emptyList()),
        solo = runCatching { IraSolo.calibration() }.getOrDefault(emptyList()),
        mistakes = runCatching { mistakes() }.getOrDefault(emptyList()),
        tally = runCatching { askedKinds() }.getOrDefault(emptyMap()),
        patterns = runCatching { patternCalls() }.getOrDefault(emptyList()),
        data = runCatching { freshLog() }.getOrDefault(com.optionslab.ira.DataAge.Log()),
        news = runCatching { newsMoves() }.getOrDefault(emptyList()),
        plan = plan,
        clarity = runCatching { clarityLog() }.getOrDefault(com.optionslab.ira.Clarity.Log()))

    /**
     * "Undo everything you learned this week", on Boss's Confirm: the wordings and routines kept in the last 7 days
     * dropped and the alert count started afresh - learned stores only (the goals are [IraImprove.dropWeek]'s). Never a
     * setting, the PIN, Live, a guard or the Google speech choice; never a record (marks, trades, pattern outcomes).
     */
    @Synchronized fun undoLearnedWeek(): com.optionslab.ira.Learnings.Undo {
        val today = com.optionslab.app.data.Market.today()
        val now = minuteNow()
        val u = com.optionslab.ira.Learnings.undo(learnings(null), now)
        if (u.words.isNotEmpty()) runCatching { saveLearned(com.optionslab.ira.Learnings.keepWords(learned(), today)) }
        if (u.routines.isNotEmpty()) runCatching { saveKept(com.optionslab.ira.Learnings.keepRoutines(routineKept(), today)) }
        if (u.alerts.isNotEmpty()) alertUpdate { com.optionslab.ira.AlertSense.reset(it, now) }
        if (u.clarity.isNotEmpty()) clarityUpdate { com.optionslab.ira.Clarity.reset(it, now) }
        IraActivity.add("Undid this week's learning, as Boss confirmed: ${u.words.size} wording(s), ${u.routines.size} routine(s), " +
            "${u.alerts.size} alert kind(s) aloud again, ${u.clarity.size} answer kind(s) as usual aloud again.")
        return u
    }

    // ---- the day's usage -----------------------------------------------------------------------------------------

    /** One key a day ("jarvis.usage.2026-10-05"); the last [USAGE_KEEP_DAYS] days are kept (the weekly review reads one week). */
    private const val USAGE = "jarvis.usage."
    private const val USAGE_KEEP_DAYS = 35L

    private fun dayKey() = "$USAGE${com.optionslab.app.data.Market.today()}"

    /** One more of [what] today: "heard", "misunderstood", "nameFirst", "failed", "mistakes". */
    @Synchronized fun count(what: String) = runCatching { prefs().putAll(counted(what)) }

    /** The writes for one more of [what] today - and, with the day's first count, the days older than [USAGE_KEEP_DAYS] dropped. */
    private fun counted(what: String): Map<String, Any?> {
        val key = dayKey()
        val was = prefs().getString(key)
        val o = JSONObject(was ?: "{}")
        o.put(what, o.optInt(what) + 1)
        val out = HashMap<String, Any?>()
        if (was == null) runCatching {
            com.optionslab.ira.Upkeep.staleDayKeys(prefs().keys(USAGE), USAGE, com.optionslab.app.data.Market.today(), USAGE_KEEP_DAYS).forEach { out[it] = null }
        }
        out[key] = o.toString()
        return out
    }

    /** All of [day]'s counts (name to count), for his weekly review ([com.optionslab.ira.Improve.week]). */
    fun usageOn(day: java.time.LocalDate): Map<String, Int> = runCatching {
        val o = JSONObject(prefs().getString("jarvis.usage.$day") ?: "{}")
        o.keys().asSequence().associateWith { o.optInt(it) }
    }.getOrDefault(emptyMap())

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

    // ---- how the patterns Jarvis told of played out ([com.optionslab.ira.PatternCalls]) -----------------------------

    /** The patterns told of (market, chart, kind, candle, close and how each horizon went - market data only). */
    private const val PATTERN_CALLS = "jarvis.patternCalls"
    @Volatile private var callsCache: List<com.optionslab.ira.PatternCalls.Call>? = null

    fun patternCalls(): List<com.optionslab.ira.PatternCalls.Call> = callsCache ?: runCatching {
        com.optionslab.ira.PatternCalls.load(prefs().getString(PATTERN_CALLS) ?: "")
    }.getOrDefault(emptyList()).also { callsCache = it }

    @Synchronized private fun callsUpdate(f: (List<com.optionslab.ira.PatternCalls.Call>) -> List<com.optionslab.ira.PatternCalls.Call>) {
        runCatching {
            val was = patternCalls()
            val log = f(was)
            if (log == was) return@runCatching
            callsCache = log
            prefs().putAllSoon(mapOf(PATTERN_CALLS to com.optionslab.ira.PatternCalls.save(log)))
        }
    }

    /** Patterns just told of (in an answer Boss saw or heard): followed from now on. */
    fun patternsTold(calls: List<com.optionslab.ira.PatternCalls.Call>) {
        if (calls.isEmpty()) return
        callsUpdate { com.optionslab.ira.PatternCalls.add(it, calls, com.optionslab.app.data.Market.today()) }
    }

    /** The calls whose horizons have passed, measured on the phone's 1-minute candles. */
    fun patternsSettle(bars: Map<com.optionslab.ira.Market, List<com.optionslab.ira.Candle>>) {
        if (patternCalls().all { it.settled }) return
        callsUpdate { log -> bars.entries.fold(log) { l, e -> com.optionslab.ira.PatternCalls.settle(l, e.key, e.value) } }
    }

    // ---- how the index moved after each news theme's headlines ([com.optionslab.ira.NewsMoves]) --------------------

    /** The timed stories (theme, index, minute, price and the biggest move after it - market data only, no headline text). */
    private const val NEWS_MOVES = "jarvis.newsMoves"
    @Volatile private var newsMovesCache: List<com.optionslab.ira.NewsMoves.Note>? = null
    /** The headline list last noted (the same list is not grouped into stories again). */
    @Volatile private var newsMovesSeen: List<com.optionslab.ira.Headline>? = null

    fun newsMoves(): List<com.optionslab.ira.NewsMoves.Note> = newsMovesCache ?: runCatching {
        com.optionslab.ira.NewsMoves.load(prefs().getString(NEWS_MOVES) ?: "")
    }.getOrDefault(emptyList()).also { newsMovesCache = it }

    /** New stories in [news] noted, and every open horizon measured on the phone's 1-minute candles. Timing only; nothing acts on it. */
    @Synchronized fun newsMovesUpdate(news: List<com.optionslab.ira.Headline>, bars: Map<com.optionslab.ira.Market, List<com.optionslab.ira.Candle>>) {
        runCatching {
            val was = newsMoves()
            val fresh = news.isNotEmpty() && news !== newsMovesSeen
            if (!fresh && was.all { it.settled }) return@runCatching
            val log = com.optionslab.ira.NewsMoves.update(was, if (fresh) news else emptyList(), bars, IST, com.optionslab.app.data.Market.today())
            if (fresh) newsMovesSeen = news
            if (log == was) return@runCatching
            newsMovesCache = log
            prefs().putAllSoon(mapOf(NEWS_MOVES to com.optionslab.ira.NewsMoves.save(log)))
        }
    }
}
