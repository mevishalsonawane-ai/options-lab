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
        // The question's kind and the way it was taken, for "what did you get wrong today?" (never the words; a record only).
        wrongSaid()
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
            againAsked(said)
            wrongAsked(said)
            talkHeard()
            asksHeard(said)
            lengthAsked(said)
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
    /** The alert kinds that are safety warnings: said at once aloud ([JarvisVoice.announce] urgent), never held for Boss to finish. */
    private val SAFETY_ALERTS = setOf(Automations.Auto.HEADSUP, Automations.Auto.OVERTRADE, Automations.Auto.MIS, Automations.Auto.FEED,
        Automations.Auto.EXPIRY, Automations.Auto.HEALTH, Automations.Auto.BOTS, Automations.Auto.RELAY, Automations.Auto.RESCUE,
        Automations.Auto.GUARD, Automations.Auto.STALE, Automations.Auto.COOLOFF)

    fun sayAlert(a: Automations.Auto, text: String): Boolean {
        if (!alertAloud(a)) return false
        // A safety warning is said at once, even while Boss is speaking; market colour waits for him to finish.
        val said = JarvisVoice.announce(text, urgent = a in SAFETY_ALERTS)
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

    /**
     * The kinds said shorter aloud now (a kind Boss often asks "what?" after; voice only - [com.optionslab.ira.SpokenReply],
     * which reads the question once). None when unreadable (as usual).
     */
    fun clarityShorterNow(): List<com.optionslab.ira.Clarity.Record> = runCatching { clarityShorter() }.getOrDefault(emptyList())

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

    // ---- his confidence words against the numbers beside them ([com.optionslab.ira.WordFit]) ----------------------------

    /** The words checked (Jarvis's own words and numbers only, never Boss's), the last 60 days, and Boss's "as written". */
    private const val WORD_FIT = "jarvis.wordfit"
    @Volatile private var wordFitCache: com.optionslab.ira.WordFit.Log? = null

    fun wordFitLog(): com.optionslab.ira.WordFit.Log = wordFitCache ?: runCatching {
        val o = JSONObject(prefs().getString(WORD_FIT) ?: "{}")
        val e = o.optJSONArray("e") ?: JSONArray()
        com.optionslab.ira.WordFit.Log(
            events = (0 until e.length()).map { i -> e.getJSONObject(i).let { x -> com.optionslab.ira.WordFit.Event(x.getString("s"),
                x.optString("to").takeIf { it.isNotEmpty() }, x.getInt("k"), x.getInt("n"), LocalDateTime.parse(x.getString("t")), x.optBoolean("f", true)) } },
            off = o.optBoolean("off", false),
            offAt = o.optString("oa").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.WordFit.Log()).also { wordFitCache = it }

    @Synchronized private fun wordFitUpdate(f: (com.optionslab.ira.WordFit.Log) -> com.optionslab.ira.WordFit.Log) {
        runCatching {
            val log = f(wordFitLog())
            wordFitCache = log
            val o = JSONObject().put("e", JSONArray().apply { log.events.forEach { x ->
                put(JSONObject().put("s", x.said).put("to", x.to ?: "").put("k", x.k).put("n", x.n).put("t", x.at.toString()).put("f", x.fixed)) } })
            o.put("off", log.off)
            log.offAt?.let { o.put("oa", it.toString()) }
            prefs().putAllSoon(mapOf(WORD_FIT to o.toString()))
        }
    }

    /**
     * [text] with each confidence word set to fit the number beside it ("usually" beside "4 of the last 12" -> "sometimes"),
     * unless Boss asked them left as written; every word checked is noted. Only words change, never a figure; on any
     * trouble the text is returned as it was. Nothing acts.
     */
    fun fitWords(text: String): String {
        if (!com.optionslab.app.BuildConfig.JARVIS) return text
        return runCatching {
            val r = com.optionslab.ira.WordFit.check(text, fix = !wordFitLog().off)
            if (r.fits.isNotEmpty()) wordFitUpdate { com.optionslab.ira.WordFit.noted(it, r.fits, minuteNow()) }
            r.text
        }.getOrDefault(text)
    }

    /** "How well do your words match your numbers?" / "what do you mean by usually?". */
    fun wordFitSay(q: String): String = runCatching { com.optionslab.ira.WordFit.say(q, wordFitLog(), minuteNow()) }
        .getOrDefault("I could not read my record of words and numbers just now, Boss.")

    /** "Say your confidence words as written" ([off]) / "match your words to the numbers again". Words only. */
    fun wordFitSwitch(off: Boolean): String {
        val was = wordFitLog().off
        if (was != off) {
            wordFitUpdate { com.optionslab.ira.WordFit.switched(it, off, minuteNow()) }
            IraActivity.add(if (off) "Leaving confidence words as written (as asked)." else "Matching confidence words to the numbers again (as asked).")
        }
        return com.optionslab.ira.WordFit.saySwitched(off, was)
    }

    // ---- the market reads Boss asks again within minutes ([com.optionslab.ira.AskedAgain]) ----------------------------

    /** The re-asks noted (kind keys, indices named, times and gaps only - never the words), the last 30 days. */
    private const val AGAIN = "jarvis.askedAgain"
    @Volatile private var againCache: com.optionslab.ira.AskedAgain.Log? = null
    /** The market read asked last (its kind and when; in memory only, never the words). */
    @Volatile private var againOpen: com.optionslab.ira.AskedAgain.Seen? = null

    fun againLog(): com.optionslab.ira.AskedAgain.Log = againCache ?: runCatching {
        val e = JSONArray(prefs().getString(AGAIN) ?: "[]")
        com.optionslab.ira.AskedAgain.Log((0 until e.length()).map { i -> e.getJSONObject(i).let { x ->
            val m = x.optJSONArray("m") ?: JSONArray()
            com.optionslab.ira.AskedAgain.Event(x.getString("k"), (0 until m.length()).map { j -> m.getString(j) },
                LocalDateTime.parse(x.getString("t")), x.optInt("g"))
        } })
    }.getOrDefault(com.optionslab.ira.AskedAgain.Log()).also { againCache = it }

    /** A question asked: a market read asked again within minutes is noted (a record only - it changes nothing he does). */
    @Synchronized fun againAsked(said: String) {
        runCatching {
            val step = com.optionslab.ira.AskedAgain.heard(againLog(), againOpen, said, LocalDateTime.now(IST).withNano(0))
            againOpen = step.open
            val again = step.again ?: return@runCatching
            // Whether the re-ask was after a figure (a yes or no by its kind, never the words; [com.optionslab.ira.FigureFirst]).
            runCatching { figureUpdate { com.optionslab.ira.FigureFirst.heard(it, again.kind, com.optionslab.ira.FigureFirst.wantsFigure(again.kind, said), minuteNow()) } }
            againCache = step.log
            val a = JSONArray().apply { step.log.events.forEach { x ->
                put(JSONObject().put("k", x.kind).put("m", JSONArray().apply { x.markets.forEach { put(it) } }).put("t", x.at.toString()).put("g", x.gapS))
            } }
            prefs().putAllSoon(mapOf(AGAIN to a.toString()))
        }
    }

    // ---- the questions he answered with the wrong thing ([com.optionslab.ira.WrongThing]) ------------------------------

    /** The misses noted (the question's kind, the way it was taken, the time and the sign only - never the words), 30 days. */
    private const val WRONG_THING = "jarvis.wrongThing"
    @Volatile private var wrongCache: com.optionslab.ira.WrongThing.Log? = null
    /** The question asked last (its kind, the way taken, its words as a set; in memory only, never saved). */
    @Volatile private var wrongLast: com.optionslab.ira.WrongThing.Last? = null

    fun wrongLog(): com.optionslab.ira.WrongThing.Log = wrongCache ?: runCatching {
        val e = JSONArray(prefs().getString(WRONG_THING) ?: "[]")
        com.optionslab.ira.WrongThing.Log((0 until e.length()).map { i -> e.getJSONObject(i).let { x ->
            com.optionslab.ira.WrongThing.Event(x.getString("k"), x.getString("w"), LocalDateTime.parse(x.getString("t")),
                runCatching { com.optionslab.ira.WrongThing.Why.valueOf(x.optString("y")) }.getOrDefault(com.optionslab.ira.WrongThing.Why.REPEAT))
        } })
    }.getOrDefault(com.optionslab.ira.WrongThing.Log()).also { wrongCache = it }

    private fun wrongSave(log: com.optionslab.ira.WrongThing.Log) {
        wrongCache = log
        val a = JSONArray().apply { log.events.forEach { x ->
            put(JSONObject().put("k", x.kind).put("w", x.took).put("t", x.at.toString()).put("y", x.why.name))
        } }
        prefs().putAllSoon(mapOf(WRONG_THING to a.toString()))
    }

    /** A question asked: the same question again within 2 minutes notes the first answer as a miss (a record only). */
    @Synchronized fun wrongAsked(said: String) {
        runCatching {
            val step = com.optionslab.ira.WrongThing.heard(wrongLog(), wrongLast, said, LocalDateTime.now(IST).withNano(0))
            wrongLast = step.last
            if (step.noted != null) wrongSave(step.log)
        }
    }

    /** Boss said the last answer was not what he asked ("galat jawab", "that was wrong"): noted against that question, once. */
    @Synchronized fun wrongSaid() {
        runCatching {
            val step = com.optionslab.ira.WrongThing.said(wrongLog(), wrongLast, LocalDateTime.now(IST).withNano(0))
            wrongLast = step.last
            if (step.noted != null) wrongSave(step.log)
        }
    }

    /** "What did you get wrong today?". */
    fun wrongSay(request: com.optionslab.ira.WrongThing.Request): String =
        runCatching { com.optionslab.ira.WrongThing.say(wrongLog(), request, LocalDateTime.now(IST).withNano(0)) }
            .getOrDefault("I could not read my record of answers that missed just now, Boss.")

    /** "Which of your answers do I ask again?". */
    fun againSay(): String = runCatching { com.optionslab.ira.AskedAgain.say(againLog(), askedKinds(), LocalDateTime.now(IST).withNano(0)) }
        .getOrDefault("I could not read my record of answers you asked again just now, Boss.")

    // ---- the figure first in a market read Boss keeps asking again for it ([com.optionslab.ira.FigureFirst]) ----------

    /** The re-asks noted (kind keys, after a figure or not, and minutes only - never the words), the last 30 days. */
    private const val FIGURE = "jarvis.figureFirst"
    @Volatile private var figureCache: com.optionslab.ira.FigureFirst.Log? = null
    /** The kinds said figure first now, worked out at most every 10 minutes or when the log changes. */
    @Volatile private var leadCache: Triple<String, Long, List<com.optionslab.ira.FigureFirst.Record>>? = null

    fun figureLog(): com.optionslab.ira.FigureFirst.Log = figureCache ?: runCatching {
        val o = JSONObject(prefs().getString(FIGURE) ?: "{}")
        val e = o.optJSONArray("e") ?: JSONArray()
        com.optionslab.ira.FigureFirst.Log(
            events = (0 until e.length()).map { i -> e.getJSONObject(i).let { x ->
                com.optionslab.ira.FigureFirst.Event(x.getString("k"), x.optBoolean("f"), LocalDateTime.parse(x.getString("t"))) } },
            resetAt = o.optString("r").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.FigureFirst.Log()).also { figureCache = it }

    @Synchronized private fun figureUpdate(f: (com.optionslab.ira.FigureFirst.Log) -> com.optionslab.ira.FigureFirst.Log) {
        runCatching {
            val log = f(figureLog())
            figureCache = log
            leadCache = null
            val o = JSONObject().put("e", JSONArray().apply { log.events.forEach { x -> put(JSONObject().put("k", x.kind).put("f", x.figure).put("t", x.at.toString())) } })
            log.resetAt?.let { o.put("r", it.toString()) }
            prefs().putAllSoon(mapOf(FIGURE to o.toString()))
        }
    }

    private fun figureLeading(): List<com.optionslab.ira.FigureFirst.Record> {
        val log = figureLog()
        val now = minuteNow()
        val key = "${log.events.size}|${log.events.lastOrNull()?.at}|${log.resetAt}|${now.toLocalDate()}"
        leadCache?.takeIf { it.first == key && System.currentTimeMillis() - it.second < 10 * 60_000L }?.let { return it.third }
        val list = com.optionslab.ira.FigureFirst.leading(log, now)
        leadCache = Triple(key, System.currentTimeMillis(), list)
        return list
    }

    /**
     * The reads said figure first now: an answer to one of them is said aloud with its figure first, else as it is (voice
     * only; [com.optionslab.ira.SpokenReply], which reads the question once). None when unreadable (the answer as it is).
     */
    fun figureLeadingNow(): List<com.optionslab.ira.FigureFirst.Record> = runCatching { figureLeading() }.getOrDefault(emptyList())

    /** "Which reads do you start with the number?". */
    fun figureHeld(): String = runCatching { com.optionslab.ira.FigureFirst.say(figureLog(), minuteNow()) }
        .getOrDefault("I could not read my record of the reads you asked again for a figure just now, Boss.")

    /** "Say your market reads in the usual order": every read in its usual order aloud, the count afresh. */
    fun figureReset(): String {
        val now = minuteNow()
        val said = runCatching { com.optionslab.ira.FigureFirst.sayReset(figureLog(), now) }.getOrDefault("Done, Boss: every market read in its usual order aloud again.")
        figureUpdate { com.optionslab.ira.FigureFirst.reset(it, now) }
        IraActivity.add("Saying every market read in its usual order aloud again (as asked).")
        return said
    }

    // ---- the morning-check items Boss leaves as they are ([com.optionslab.ira.MorningSense]) -----------------------

    /** The trading mornings' failing minor items (keys and days only - never a word, an amount or an account figure). */
    private const val MORNING = "jarvis.morningSense"
    @Volatile private var morningCache: com.optionslab.ira.MorningSense.Log? = null

    fun morningLog(): com.optionslab.ira.MorningSense.Log = morningCache ?: runCatching {
        val o = JSONObject(prefs().getString(MORNING) ?: "{}")
        val m = o.optJSONArray("m") ?: JSONArray()
        com.optionslab.ira.MorningSense.Log(
            mornings = (0 until m.length()).map { i -> m.getJSONObject(i).let { x ->
                val f = x.optJSONArray("f") ?: JSONArray()
                com.optionslab.ira.MorningSense.Morning(java.time.LocalDate.parse(x.getString("d")), (0 until f.length()).map { f.getString(it) }.toSet()) } },
            resetAt = o.optString("r").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.MorningSense.Log()).also { morningCache = it }

    @Synchronized private fun morningUpdate(f: (com.optionslab.ira.MorningSense.Log) -> com.optionslab.ira.MorningSense.Log) {
        runCatching {
            val log = f(morningLog())
            morningCache = log
            val o = JSONObject().put("m", JSONArray().apply { log.mornings.forEach { x ->
                put(JSONObject().put("d", x.day.toString()).put("f", JSONArray().apply { x.failed.forEach { put(it) } })) } })
            log.resetAt?.let { o.put("r", it.toString()) }
            prefs().putAllSoon(mapOf(MORNING to o.toString()))
        }
    }

    /**
     * The spoken "N things need you" of the 09:00 check: [failing] (the failing lines to be said aloud, mark taken off)
     * with the minor items Boss usually leaves as they are named in a few words; then today's failing items noted (keys
     * only). The voice only - the chat, pop-up and notification keep every item. On any trouble, the check's own words.
     */
    fun morningAloud(bad: Int, failing: List<String>, allFailing: List<String>): String {
        val today = com.optionslab.app.data.Market.today()
        val brief = runCatching { com.optionslab.ira.MorningSense.briefToday(morningLog(), today) }.getOrDefault(emptySet())
        runCatching { morningUpdate { com.optionslab.ira.MorningSense.noted(it, today, allFailing) } }
        return runCatching { com.optionslab.ira.MorningSense.aloud(bad, failing, brief) }
            .getOrDefault("$bad thing${if (bad > 1) "s" else ""} need you: " + failing.joinToString(". ") + ".")
    }

    /** "Which morning items do you skip?". */
    fun morningSay(): String = runCatching { com.optionslab.ira.MorningSense.say(morningLog(), com.optionslab.app.data.Market.today()) }
        .getOrDefault("I could not read my record of the morning check just now, Boss.")

    /** "Say the whole morning check again": every failing item read out in full again, the count afresh. */
    fun morningReset(): String {
        val now = minuteNow()
        val said = runCatching { com.optionslab.ira.MorningSense.sayReset(morningLog(), now.toLocalDate()) }
            .getOrDefault("Done, Boss: I'll read out every failing item of the morning check again.")
        morningUpdate { com.optionslab.ira.MorningSense.reset(it, now) }
        IraActivity.add("Reading out every failing item of the morning check again (as asked).")
        return said
    }

    // ---- the confidence scores that have not held up ([com.optionslab.ira.HonestStars]) --------------------------------

    /** When Boss last asked for his confidence plainly (no idea before it counts); the ideas themselves are IraNewsTrades'. */
    private const val STARS_RESET = "jarvis.honestStars.reset"

    fun starsReset(): LocalDateTime? = runCatching { prefs().getString(STARS_RESET)?.let { LocalDateTime.parse(it) } }.getOrNull()

    private fun starsNow(): LocalDateTime = com.optionslab.app.data.Market.now().toLocalDateTime().withSecond(0).withNano(0)

    /**
     * The spoken "Confidence N out of 5." of a trade idea, with the score's record beside it when it has not held up.
     * The voice only - the score, the chat and the approval card are unchanged. On any trouble, the plain words.
     */
    fun starsAloud(stars: Int): String = runCatching {
        com.optionslab.ira.HonestStars.aloud(stars, com.optionslab.ira.HonestStars.honest(IraNewsTrades.starsScored(), starsNow(), starsReset()))
    }.getOrDefault("Confidence $stars out of 5.")

    /** "How honest are your confidence scores?". */
    fun starsSay(): String = runCatching { com.optionslab.ira.HonestStars.say(IraNewsTrades.starsScored(), starsNow(), starsReset()) }
        .getOrDefault("I could not read my ideas' record just now, Boss.")

    /** "Say your confidence plainly": the record no longer added aloud, the count afresh from now. */
    fun starsPlain(): String {
        val now = starsNow()
        val said = runCatching { com.optionslab.ira.HonestStars.sayReset(IraNewsTrades.starsScored(), now, starsReset()) }
            .getOrDefault("Done, Boss: I'll say my confidence plainly.")
        starsResetAt(now)
        IraActivity.add("Saying my confidence plainly again (as asked).")
        return said
    }

    private fun starsResetAt(now: LocalDateTime) = runCatching { prefs().putAllSoon(mapOf(STARS_RESET to now.toString())) }

    // ---- the hours Boss talks to him ([com.optionslab.ira.TalkHours]) ------------------------------------------------

    /** The days Boss asked something and the hours he did (days and hours only - never a word or what it was about). */
    private const val TALK_HOURS = "jarvis.talkHours"
    @Volatile private var talkCache: com.optionslab.ira.TalkHours.Log? = null
    /** His hours, worked out at most every 10 minutes (not on every briefing): when, and the hours. */
    @Volatile private var talkHoursCache: Pair<Long, List<Int>>? = null

    private fun talkNow(): LocalDateTime = com.optionslab.app.data.Market.now().toLocalDateTime().withSecond(0).withNano(0)

    fun talkLog(): com.optionslab.ira.TalkHours.Log = talkCache ?: runCatching {
        val o = JSONObject(prefs().getString(TALK_HOURS) ?: "{}")
        val d = o.optJSONArray("d") ?: JSONArray()
        com.optionslab.ira.TalkHours.Log(
            days = (0 until d.length()).map { i -> d.getJSONObject(i).let { x ->
                val h = x.optJSONArray("h") ?: JSONArray()
                com.optionslab.ira.TalkHours.Day(java.time.LocalDate.parse(x.getString("d")), (0 until h.length()).map { h.getInt(it) }.toSet()) } },
            resetAt = o.optString("r").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.TalkHours.Log()).also { talkCache = it }

    @Synchronized private fun talkUpdate(f: (com.optionslab.ira.TalkHours.Log) -> com.optionslab.ira.TalkHours.Log) {
        runCatching {
            val log = f(talkLog())
            if (log == talkCache) return@runCatching
            talkCache = log
            talkHoursCache = null
            val o = JSONObject().put("d", JSONArray().apply { log.days.forEach { x ->
                put(JSONObject().put("d", x.day.toString()).put("h", JSONArray().apply { x.hours.sorted().forEach { put(it) } })) } })
            log.resetAt?.let { o.put("r", it.toString()) }
            prefs().putAllSoon(mapOf(TALK_HOURS to o.toString()))
        }
    }

    /** Boss asked something now: the day and hour noted (a learning that only ever shortens a long unasked briefing aloud). */
    private fun talkHeard() = talkUpdate { com.optionslab.ira.TalkHours.heard(it, talkNow()) }

    private fun talkHours(): List<Int> {
        val t = System.currentTimeMillis()
        talkHoursCache?.takeIf { t - it.first < 10 * 60_000L }?.let { return it.second }
        val hs = runCatching { com.optionslab.ira.TalkHours.record(talkLog(), talkNow()).hours }.getOrDefault(emptyList())
        talkHoursCache = t to hs
        return hs
    }

    /**
     * An unasked, non-urgent briefing as said aloud: outside the hours Boss talks to him, a long one in its first sentence
     * and "the rest is in the chat" ([com.optionslab.ira.TalkHours]). The voice only; on any trouble, [text] as it is.
     */
    fun talkAloud(text: String): String = runCatching { com.optionslab.ira.TalkHours.aloud(text, talkNow(), talkHours()) }.getOrDefault(text)

    /** "When do I usually talk to you?". */
    fun talkSay(): String = runCatching { com.optionslab.ira.TalkHours.say(talkLog(), talkNow()) }
        .getOrDefault("I could not read my record of your hours just now, Boss.")

    /** "Say your briefings in full at any hour": every briefing in full again, the count afresh. */
    fun talkReset(): String {
        val now = talkNow()
        val said = runCatching { com.optionslab.ira.TalkHours.sayReset(talkLog(), now) }.getOrDefault("Done, Boss: every briefing in full at any hour again.")
        talkUpdate { com.optionslab.ira.TalkHours.reset(it, now) }
        IraActivity.add("Saying every briefing in full at any hour again (as asked).")
        return said
    }

    // ---- the question Boss asks every morning ([com.optionslab.ira.MorningAsks]) ----------------------------------------

    /** The weekday mornings: the kinds of market question Boss asked and whether the check ran (kinds and days only - never a word). */
    private const val MORNING_ASKS = "jarvis.morningAsks"
    @Volatile private var asksCache: com.optionslab.ira.MorningAsks.Log? = null
    /** The kind offered at the end of this morning's check, and when (kept in memory only; any other words end it). */
    @Volatile private var asksOffered: Pair<String, LocalDateTime>? = null

    fun asksLog(): com.optionslab.ira.MorningAsks.Log = asksCache ?: runCatching {
        val o = JSONObject(prefs().getString(MORNING_ASKS) ?: "{}")
        val m = o.optJSONArray("m") ?: JSONArray()
        com.optionslab.ira.MorningAsks.Log(
            mornings = (0 until m.length()).map { i -> m.getJSONObject(i).let { x ->
                val k = x.optJSONArray("k") ?: JSONArray()
                com.optionslab.ira.MorningAsks.Morning(java.time.LocalDate.parse(x.getString("d")), (0 until k.length()).map { k.getString(it) }.toSet(),
                    x.optBoolean("c", false)) } },
            resetAt = o.optString("r").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.MorningAsks.Log()).also { asksCache = it }

    @Synchronized private fun asksUpdate(f: (com.optionslab.ira.MorningAsks.Log) -> com.optionslab.ira.MorningAsks.Log) {
        runCatching {
            val log = f(asksLog())
            if (log == asksCache) return@runCatching
            asksCache = log
            val o = JSONObject().put("m", JSONArray().apply { log.mornings.forEach { x ->
                put(JSONObject().put("d", x.day.toString()).put("c", x.checked).put("k", JSONArray().apply { x.keys.sorted().forEach { put(it) } })) } })
            log.resetAt?.let { o.put("r", it.toString()) }
            prefs().putAllSoon(mapOf(MORNING_ASKS to o.toString()))
        }
    }

    private fun asksNow(): LocalDateTime = com.optionslab.app.data.Market.now().toLocalDateTime().withSecond(0).withNano(0)

    /** Boss asked [said] now: on a weekday morning, its market kind noted (never the words, never the account). */
    private fun asksHeard(said: String) {
        val now = asksNow()
        if (!com.optionslab.ira.MorningAsks.morning(now)) return
        asksUpdate { com.optionslab.ira.MorningAsks.heard(it, said, now) }
    }

    /**
     * The 09:00 check's last line: the question Boss asks on most mornings and has not asked yet today, offered in words
     * ("say 'yes' for it") - never answered unasked; then today marked a trading morning. Null when there is none.
     * Words only: nothing here acts.
     */
    fun morningAsksLine(): String? {
        val today = com.optionslab.app.data.Market.today()
        val u = runCatching { com.optionslab.ira.MorningAsks.offer(asksLog(), today) }.getOrNull()
        asksUpdate { com.optionslab.ira.MorningAsks.checked(it, today) }
        asksOffered = u?.let { it.key to asksNow() }
        return u?.let { runCatching { com.optionslab.ira.MorningAsks.line(it) }.getOrNull() }
    }

    /**
     * Boss's words [said] after the morning offer: a bare "yes" within the minutes allowed - and nothing else waiting
     * for his yes ([waiting]) - is the offered question (a market question, checked again), else null. Any words end
     * the offer, whatever they were.
     */
    fun morningAsksYes(said: String, waiting: Boolean): String? {
        val (key, at) = asksOffered ?: return null
        asksOffered = null
        return com.optionslab.ira.MorningAsks.taken(key, at, said, waiting, asksNow())
    }

    /**
     * Boss's words went another way (a request read as understood, or a yes / no to Jarvis's question): the morning offer
     * and the wait for a turn-down reason both end - only ever his very next words may take them.
     */
    fun endWaits() {
        asksOffered = null
        turnedDownAt = null
        nickAsked = null
    }

    /** "What do you offer me in the morning?". */
    fun morningAsksSay(): String = runCatching { com.optionslab.ira.MorningAsks.say(asksLog(), com.optionslab.app.data.Market.today()) }
        .getOrDefault("I could not read my record of your morning questions just now, Boss.")

    /** "Don't offer my usual morning question": the morning check ends as before, the count afresh. */
    fun morningAsksReset(): String {
        val now = asksNow()
        val said = runCatching { com.optionslab.ira.MorningAsks.sayReset(asksLog(), now.toLocalDate()) }
            .getOrDefault("Done, Boss: no morning question offered, and my count starts afresh.")
        asksUpdate { com.optionslab.ira.MorningAsks.reset(it, now) }
        asksOffered = null
        IraActivity.add("No longer offering Boss's usual morning question (as asked).")
        return said
    }

    // ---- the reasons Boss turns Jarvis's trade ideas down for ([com.optionslab.ira.TurnDowns]) ---------------------------

    /** The reasons noted: each one's kind and time only - never Boss's words, the idea or his account. */
    private const val TURN_DOWNS = "jarvis.turnDowns"
    @Volatile private var turnCache: com.optionslab.ira.TurnDowns.Log? = null
    /** When Boss last turned an idea down without a reason yet (in memory only; his very next words end it). */
    @Volatile private var turnedDownAt: LocalDateTime? = null

    fun turnLog(): com.optionslab.ira.TurnDowns.Log = turnCache ?: runCatching {
        val o = JSONObject(prefs().getString(TURN_DOWNS) ?: "{}")
        val n = o.optJSONArray("n") ?: JSONArray()
        com.optionslab.ira.TurnDowns.Log(
            notes = (0 until n.length()).map { i -> n.getJSONObject(i).let { x ->
                com.optionslab.ira.TurnDowns.Note(LocalDateTime.parse(x.getString("t")), x.getString("k")) } },
            resetAt = o.optString("r").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.TurnDowns.Log()).also { turnCache = it }

    @Synchronized private fun turnUpdate(f: (com.optionslab.ira.TurnDowns.Log) -> com.optionslab.ira.TurnDowns.Log) {
        runCatching {
            val log = f(turnLog())
            if (log == turnCache) return@runCatching
            turnCache = log
            val o = JSONObject().put("n", JSONArray().apply { log.notes.forEach { x -> put(JSONObject().put("t", x.at.toString()).put("k", x.reason)) } })
            log.resetAt?.let { o.put("r", it.toString()) }
            prefs().putAllSoon(mapOf(TURN_DOWNS to o.toString()))
        }
    }

    private fun turnNow(): LocalDateTime = com.optionslab.app.data.Market.now().toLocalDateTime().withSecond(0).withNano(0)

    /**
     * Boss turned a suggested trade down, [said] being his words for it when spoken ("no, too late in the day"): the
     * reason's kind noted when there is one; else his very next words may give it ([turnDownReason]). Nothing acts.
     */
    fun turnedDown(said: String?) {
        val now = turnNow()
        val r = said?.let { runCatching { com.optionslab.ira.TurnDowns.reason(it) }.getOrNull() }
        if (r == null) { turnedDownAt = now; return }
        turnedDownAt = null
        turnUpdate { com.optionslab.ira.TurnDowns.heard(it, r, now) }
        IraActivity.add("Noted why Boss turned my idea down: ${r.phrase}.")
    }

    /**
     * Boss's words [said] just after he turned an idea down: a reason ("it's too late in the day") within the minutes
     * allowed is noted and the words to say back returned; else null. Any words end the wait, whatever they were.
     */
    fun turnDownReason(said: String): String? {
        val at = turnedDownAt ?: return null
        turnedDownAt = null
        val now = turnNow()
        if (!com.optionslab.ira.TurnDowns.fresh(at, now)) return null
        val r = com.optionslab.ira.TurnDowns.reason(said) ?: return null
        turnUpdate { com.optionslab.ira.TurnDowns.heard(it, r, now) }
        IraActivity.add("Noted why Boss turned my idea down: ${r.phrase}.")
        return com.optionslab.ira.TurnDowns.noted(r)
    }

    /** The one line said before Jarvis asks about an idea ([expiry]: an expiry day for its index), or null. Words only. */
    fun turnDownsLine(expiry: Boolean): String? =
        runCatching { com.optionslab.ira.TurnDowns.upFront(turnLog(), turnNow(), expiry) }.getOrNull()

    /** "Why do I turn down your ideas?". */
    fun turnDownsSay(): String = runCatching { com.optionslab.ira.TurnDowns.say(turnLog(), turnNow()) }
        .getOrDefault("I could not read my record of your reasons just now, Boss.")

    /** "Don't remind me why I turn your ideas down": no reason said up front, the count afresh. */
    fun turnDownsReset(): String {
        val now = turnNow()
        val said = runCatching { com.optionslab.ira.TurnDowns.sayReset(turnLog(), now) }
            .getOrDefault("Done, Boss: no reason of yours said up front, and my count starts afresh.")
        turnUpdate { com.optionslab.ira.TurnDowns.reset(it, now) }
        turnedDownAt = null
        IraActivity.add("No longer saying Boss's reasons up front before an idea (as asked).")
        return said
    }

    // ---- how long Boss likes each topic's answers ([com.optionslab.ira.TopicLength]) -----------------------------------

    /** The wishes noted: each answer kind, its direction and time only - never Boss's words or the answer. */
    private const val TOPIC_LENGTH = "jarvis.topicLength"
    @Volatile private var lengthCache: com.optionslab.ira.TopicLength.Log? = null
    /** The kind of the question last asked and when (in memory only): "in short" soon after is about its answer. */
    @Volatile private var lengthLast: Pair<String, LocalDateTime>? = null
    /** When Boss last asked for the answer whole and was answered (ms; in memory only): said in full aloud. */
    @Volatile private var lengthLongAt: Long = 0L

    fun lengthLog(): com.optionslab.ira.TopicLength.Log = lengthCache ?: runCatching {
        val o = JSONObject(prefs().getString(TOPIC_LENGTH) ?: "{}")
        val n = o.optJSONArray("n") ?: JSONArray()
        com.optionslab.ira.TopicLength.Log(
            notes = (0 until n.length()).map { i -> n.getJSONObject(i).let { x ->
                com.optionslab.ira.TopicLength.Note(LocalDateTime.parse(x.getString("t")), x.getString("k"), x.getString("d")) } },
            resetAt = o.optString("r").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.TopicLength.Log()).also { lengthCache = it }

    @Synchronized private fun lengthUpdate(f: (com.optionslab.ira.TopicLength.Log) -> com.optionslab.ira.TopicLength.Log) {
        runCatching {
            val log = f(lengthLog())
            if (log == lengthCache) return@runCatching
            lengthCache = log
            val o = JSONObject().put("n", JSONArray().apply { log.notes.forEach { x -> put(JSONObject().put("t", x.at.toString()).put("k", x.kind).put("d", x.dir)) } })
            log.resetAt?.let { o.put("r", it.toString()) }
            prefs().putAllSoon(mapOf(TOPIC_LENGTH to o.toString()))
        }
    }

    private fun lengthNow(): LocalDateTime = com.optionslab.app.data.Market.now().toLocalDateTime().withSecond(0).withNano(0)

    /** A question with a kind of answer was asked ([said]'s kind only is kept, in memory). */
    private fun lengthAsked(said: String) {
        val k = runCatching { com.optionslab.ira.TopicLength.kindOf(said) }.getOrNull() ?: return
        lengthLast = k to lengthNow()
    }

    /**
     * Boss's words [said] just after an answer ([last]: its text as in the chat): a wish for its length ("in short",
     * "detail mein batao") within the minutes allowed is noted with that answer's kind, and the reply returned - the
     * answer in its first sentence, or whole ([locked]: neither - it is left to the chat). Else null. Words only.
     */
    fun lengthWish(said: String, last: String?, locked: Boolean): String? {
        val dir = com.optionslab.ira.TopicLength.wish(said) ?: return null
        val (kind, at) = lengthLast ?: return null
        val now = lengthNow()
        if (!com.optionslab.ira.TopicLength.fresh(at, now)) return null
        lengthLast = null
        val before = com.optionslab.ira.TopicLength.learned(lengthLog(), now).firstOrNull { it.kind == kind }
        lengthUpdate { com.optionslab.ira.TopicLength.heard(it, kind, dir, now) }
        val after = com.optionslab.ira.TopicLength.learned(lengthLog(), now).firstOrNull { it.kind == kind }
        val learnedNow = after?.takeIf { before == null || before.dir != it.dir }
        if (learnedNow != null) IraActivity.add("Learned how Boss likes ${learnedNow.phrase}: ${learnedNow.dir.how}.")
        if (dir == com.optionslab.ira.TopicLength.Dir.LONG && locked) return com.optionslab.ira.TopicLength.LOCKED_LONG
        if (dir == com.optionslab.ira.TopicLength.Dir.LONG) lengthLongAt = System.currentTimeMillis()
        // On a locked phone the answer is never said again (it may be his account's): the wish is noted only.
        return com.optionslab.ira.TopicLength.reply(dir, if (locked) null else last, learnedNow, kind)
    }

    /** Was [said] Boss's "in detail" answered just now (so it is said whole aloud)? */
    fun lengthWished(said: String): Boolean =
        System.currentTimeMillis() - lengthLongAt < 60_000L &&
            runCatching { com.optionslab.ira.TopicLength.wish(said) == com.optionslab.ira.TopicLength.Dir.LONG }.getOrDefault(false)

    /**
     * The topics learned short or whole now (a topic Boss keeps asking one way; voice only - [com.optionslab.ira.SpokenReply],
     * which reads the question once). None when unreadable (as usual).
     */
    fun lengthLearnedNow(): List<com.optionslab.ira.TopicLength.Record> =
        runCatching { com.optionslab.ira.TopicLength.learned(lengthLog(), lengthNow()) }.getOrDefault(emptyList())

    /** "How long do I like your answers?". */
    fun lengthSay(): String = runCatching { com.optionslab.ira.TopicLength.say(lengthLog(), lengthNow()) }
        .getOrDefault("I could not read my record of how you like your answers just now, Boss.")

    /** "Say every topic at the usual length": every topic as usual aloud, the count afresh. */
    fun lengthReset(): String {
        val now = lengthNow()
        val said = runCatching { com.optionslab.ira.TopicLength.sayReset(lengthLog(), now) }
            .getOrDefault("Done, Boss: every topic at the usual length aloud again.")
        lengthUpdate { com.optionslab.ira.TopicLength.reset(it, now) }
        lengthLast = null
        IraActivity.add("Saying every topic at the usual length aloud again (as asked).")
        return said
    }

    // ---- the index Boss means when he names none ([com.optionslab.ira.UsualIndex]) ----------------------------------

    /** Boss's corrections: each index he said he meant and when - never his words or the question. */
    private const val USUAL_INDEX = "jarvis.usualIndex"
    @Volatile private var indexCache: com.optionslab.ira.UsualIndex.Log? = null
    /** The market question that named no index, last asked, and when (in memory only): "no, BankNifty" soon after is about it. */
    @Volatile private var indexLast: Pair<String, LocalDateTime>? = null

    fun indexLog(): com.optionslab.ira.UsualIndex.Log = indexCache ?: runCatching {
        val o = JSONObject(prefs().getString(USUAL_INDEX) ?: "{}")
        val n = o.optJSONArray("n") ?: JSONArray()
        com.optionslab.ira.UsualIndex.Log(
            notes = (0 until n.length()).map { i -> n.getJSONObject(i).let { x ->
                com.optionslab.ira.UsualIndex.Note(LocalDateTime.parse(x.getString("t")), x.getString("m")) } },
            resetAt = o.optString("r").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) })
    }.getOrDefault(com.optionslab.ira.UsualIndex.Log()).also { indexCache = it }

    @Synchronized private fun indexUpdate(f: (com.optionslab.ira.UsualIndex.Log) -> com.optionslab.ira.UsualIndex.Log) {
        runCatching {
            val log = f(indexLog())
            if (log == indexCache) return@runCatching
            indexCache = log
            val o = JSONObject().put("n", JSONArray().apply { log.notes.forEach { x -> put(JSONObject().put("t", x.at.toString()).put("m", x.market)) } })
            log.resetAt?.let { o.put("r", it.toString()) }
            prefs().putAllSoon(mapOf(USUAL_INDEX to o.toString()))
        }
    }

    private fun indexNow(): LocalDateTime = com.optionslab.app.data.Market.now().toLocalDateTime().withSecond(0).withNano(0)

    /**
     * Boss's words [said]: a correction ("no, BankNifty") of the market question that named no index, asked within the
     * minutes allowed, is noted (the index only) and returned as (what is said, the question read for that index); else
     * null - and any other words end the wait. Never while something waits for his yes or Confirm ([waiting]). Words only.
     */
    fun indexCorrection(said: String, waiting: Boolean): Pair<String, String>? {
        val last = indexLast
        indexLast = null
        if (last == null || waiting) return null
        val m = com.optionslab.ira.UsualIndex.correction(said) ?: return null
        val now = indexNow()
        if (!com.optionslab.ira.UsualIndex.fresh(last.second, now)) return null
        val again = com.optionslab.ira.UsualIndex.reading(last.first, m) ?: return null
        val before = com.optionslab.ira.UsualIndex.learned(indexLog(), now)
        indexUpdate { com.optionslab.ira.UsualIndex.heard(it, m, now) }
        val after = com.optionslab.ira.UsualIndex.learned(indexLog(), now)
        val learnedNow = after?.takeIf { before == null || before.market != it.market }
        if (learnedNow != null) IraActivity.add("Learned the index Boss means when he names none: ${learnedNow.phrase}.")
        return com.optionslab.ira.UsualIndex.corrected(m, learnedNow) to again
    }

    /**
     * Boss's question [said]: when it names no index it is kept (in memory) for a correction just after, and - once an
     * index is learned and the phone is not [locked] - returned as (what is said, the question read for that index). Else
     * null: answered as usual. Understanding only.
     */
    fun indexReading(said: String, locked: Boolean): Pair<String, String>? {
        if (!com.optionslab.ira.UsualIndex.unnamed(said)) return null
        val now = indexNow()
        indexLast = said to now
        if (locked) return null
        val r = com.optionslab.ira.UsualIndex.learned(indexLog(), now) ?: return null
        val read = com.optionslab.ira.UsualIndex.reading(said, r.market) ?: return null
        indexReadLast = Triple(said, r.market, com.optionslab.app.data.Market.now().toLocalDateTime())
        return com.optionslab.ira.UsualIndex.took(r) to read
    }

    /** The question last read for the learned index, that index, and when (in memory only): its spoken answer is led by it. */
    @Volatile private var indexReadLast: Triple<String, com.optionslab.ira.Market, LocalDateTime>? = null

    /**
     * The learned index Boss's words [said] were just read for ([indexReading]), or null: the voice then says
     * "BankNifty, as usual:" before the answer ([com.optionslab.ira.UsualIndex.aloud]). Speech wording only.
     */
    fun indexReadFor(said: String): com.optionslab.ira.Market? {
        val last = indexReadLast ?: return null
        if (last.first != said) return null
        val fresh = runCatching { com.optionslab.ira.UsualIndex.leadFresh(last.third, com.optionslab.app.data.Market.now().toLocalDateTime()) }
            .getOrDefault(false)
        return if (fresh) last.second else null
    }

    /** "Which index do I usually mean?". */
    fun indexSay(): String = runCatching { com.optionslab.ira.UsualIndex.say(indexLog(), indexNow()) }
        .getOrDefault("I could not read my record of which index you mean just now, Boss.")

    /** "Use Nifty when I don't name an index": Nifty again when he names none, the count afresh. */
    fun indexReset(): String {
        val now = indexNow()
        val said = runCatching { com.optionslab.ira.UsualIndex.sayReset(indexLog(), now) }
            .getOrDefault("Done, Boss: Nifty again when you name no index.")
        indexUpdate { com.optionslab.ira.UsualIndex.reset(it, now) }
        indexLast = null
        indexReadLast = null
        IraActivity.add("Taking Nifty again when Boss names no index (as asked).")
        return said
    }

    // ---- the nicknames Boss uses for his arms and positions ([com.optionslab.ira.Nicknames]) -------------------------

    /** Boss's nicknames: his words and the one arm or position he picked for them - nothing else. */
    private const val NICKNAMES = "jarvis.nicknames"
    @Volatile private var nickCache: com.optionslab.ira.Nicknames.Log? = null
    /** Jarvis's last "Which one?" for a command Boss said himself (in memory only): his next pick of that list teaches it. */
    @Volatile private var nickAsked: com.optionslab.ira.Nicknames.Asked? = null
    /** What was just learned from his pick, said once beside the confirm ([nickTakeLearned]). */
    @Volatile private var nickLearned: String? = null

    fun nickLog(): com.optionslab.ira.Nicknames.Log = nickCache ?: runCatching {
        val a = JSONArray(prefs().getString(NICKNAMES) ?: "[]")
        com.optionslab.ira.Nicknames.Log((0 until a.length()).mapNotNull { i -> a.getJSONObject(i).let { x ->
            val fam = runCatching { com.optionslab.ira.Nicknames.Family.valueOf(x.getString("f")) }.getOrNull()
            fam?.let { f -> com.optionslab.ira.Nicknames.Note(LocalDateTime.parse(x.getString("t")), f, x.getString("w"), x.getString("n")) } } })
    }.getOrDefault(com.optionslab.ira.Nicknames.Log()).also { nickCache = it }

    @Synchronized private fun nickUpdate(f: (com.optionslab.ira.Nicknames.Log) -> com.optionslab.ira.Nicknames.Log) {
        runCatching {
            val log = f(nickLog())
            if (log == nickCache) return@runCatching
            nickCache = log
            val a = JSONArray().apply { log.notes.forEach { x ->
                put(JSONObject().put("t", x.at.toString()).put("f", x.family.name).put("w", x.words).put("n", x.name)) } }
            prefs().putAllSoon(mapOf(NICKNAMES to a.toString()))
        }
    }

    private fun nickNow(): LocalDateTime = com.optionslab.app.data.Market.now().toLocalDateTime().withSecond(0).withNano(0)

    /** Boss's words [said] (his own, as heard): anything but a command naming one arm or position ends the wait for his pick. */
    fun nickHeard(said: String) {
        if (nickAsked == null) return
        val kind = runCatching { com.optionslab.ira.Ask.parse(said).command?.kind }.getOrNull()
        if (kind == null || com.optionslab.ira.Nicknames.family(kind) == null) nickAsked = null
    }

    /** Jarvis asks "Which one?" of [names] for Boss's [words] in a command of [kind]: kept (in memory) for his pick. */
    fun nickAsking(kind: com.optionslab.ira.Command.Kind, words: String?, names: List<String>) {
        if (com.optionslab.ira.Nicknames.family(kind) == null) return
        nickAsked = com.optionslab.ira.Nicknames.asking(kind, words, names, nickNow())
    }

    /**
     * Boss's command of [kind] picked [index] of [names]: when it answers Jarvis's "Which one?" just before, the words he
     * first used are kept for that one name (said once beside the confirm). The wait ends either way. Understanding only.
     */
    fun nickPicked(kind: com.optionslab.ira.Command.Kind, index: Int, names: List<String>) {
        val asked = nickAsked
        nickAsked = null
        val n = com.optionslab.ira.Nicknames.picked(asked, kind, index, names, nickNow()) ?: return
        nickUpdate { com.optionslab.ira.Nicknames.heard(it, n) }
        nickLearned = com.optionslab.ira.Nicknames.learned(n)
        IraActivity.add("Learned a nickname of Boss's for one ${n.family.noun} (on his pick).")
    }

    /** What was just learned from his pick, once (null: nothing). */
    fun nickTakeLearned(): String? = nickLearned.also { nickLearned = null }

    /**
     * Boss's [words] in a command of [kind] that matched none or several of [names]: the one his nickname stands for, as
     * (its index, what is said beside the confirm) - only when exactly one of [names] has it; else null (Jarvis asks).
     */
    fun nickResolve(kind: com.optionslab.ira.Command.Kind, words: String?, names: List<String>): Pair<Int, String>? {
        val fam = com.optionslab.ira.Nicknames.family(kind) ?: return null
        val log = nickLog()
        val i = com.optionslab.ira.Nicknames.resolve(log, fam, words, names) ?: return null
        val n = com.optionslab.ira.Nicknames.note(log, fam, words) ?: return null
        nickAsked = null
        return i to com.optionslab.ira.Nicknames.took(n)
    }

    /** "What nicknames do I use?". */
    fun nickSay(): String = runCatching { com.optionslab.ira.Nicknames.say(nickLog()) }
        .getOrDefault("I could not read the nicknames I've learned just now, Boss.")

    /** "Forget my nicknames for my arms". */
    fun nickReset(): String {
        val said = runCatching { com.optionslab.ira.Nicknames.sayReset(nickLog()) }
            .getOrDefault("Done, Boss: I've forgotten your nicknames.")
        nickUpdate { com.optionslab.ira.Nicknames.forget() }
        nickAsked = null
        IraActivity.add("Forgot Boss's nicknames for his arms and positions (as asked).")
        return said
    }

    // ---- the morning outlook checked against the close ([com.optionslab.ira.OutlookCheck]) ---------------------------

    /** Each index's 09:00 outlook numbers (previous close, range, direction read, pivot) and the day's open, high, low, close. Market data only. */
    private const val OUTLOOK_CHECK = "jarvis.outlookCheck"
    @Volatile private var outlookCache: List<com.optionslab.ira.OutlookCheck.Entry>? = null

    fun outlookLog(): List<com.optionslab.ira.OutlookCheck.Entry> = outlookCache ?: runCatching {
        com.optionslab.ira.OutlookCheck.decode(prefs().getString(OUTLOOK_CHECK) ?: "")
    }.getOrDefault(emptyList()).also { outlookCache = it }

    @Synchronized private fun outlookUpdate(f: (List<com.optionslab.ira.OutlookCheck.Entry>) -> List<com.optionslab.ira.OutlookCheck.Entry>) {
        runCatching {
            val log = f(outlookLog())
            if (log == outlookCache) return@runCatching
            outlookCache = log
            prefs().putAllSoon(mapOf(OUTLOOK_CHECK to com.optionslab.ira.OutlookCheck.encode(log)))
        }
    }

    /** The 09:00 check made [entries] (its outlook's numbers): noted for the 15:35 check. */
    fun outlookMade(entries: List<com.optionslab.ira.OutlookCheck.Entry>) {
        if (entries.isEmpty()) return
        outlookUpdate { com.optionslab.ira.OutlookCheck.made(it, entries) }
    }

    /** At the wrap-up: [day]'s outlooks set against its candles ([bars]), and the words for it - or null (nothing to check). */
    fun outlookCheck(bars: Map<com.optionslab.ira.Market, List<com.optionslab.ira.Candle>>, day: java.time.LocalDate): String? {
        var said: String? = null
        outlookUpdate { log -> com.optionslab.ira.OutlookCheck.check(log, bars, day).let { (next, words) -> said = words; next } }
        return said
    }

    /** "How good are your morning outlooks?": the record in counts. */
    fun outlookSay(today: java.time.LocalDate): String = runCatching { com.optionslab.ira.OutlookCheck.say(outlookLog(), today) }
        .getOrDefault("I could not read my morning outlooks' record just now, Boss.")

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
        clarity = runCatching { clarityLog() }.getOrDefault(com.optionslab.ira.Clarity.Log()),
        wordFit = runCatching { wordFitLog() }.getOrDefault(com.optionslab.ira.WordFit.Log()),
        again = runCatching { againLog() }.getOrDefault(com.optionslab.ira.AskedAgain.Log()),
        wrong = runCatching { wrongLog() }.getOrDefault(com.optionslab.ira.WrongThing.Log()),
        figure = runCatching { figureLog() }.getOrDefault(com.optionslab.ira.FigureFirst.Log()),
        arms = runCatching { IraBots.armLog() }.getOrDefault(com.optionslab.ira.ArmHabits.Log()),
        morning = runCatching { morningLog() }.getOrDefault(com.optionslab.ira.MorningSense.Log()),
        stars = runCatching { IraNewsTrades.starsScored() }.getOrDefault(emptyList()),
        starsReset = starsReset(),
        hours = runCatching { talkLog() }.getOrDefault(com.optionslab.ira.TalkHours.Log()),
        asks = runCatching { asksLog() }.getOrDefault(com.optionslab.ira.MorningAsks.Log()),
        turnDowns = runCatching { turnLog() }.getOrDefault(com.optionslab.ira.TurnDowns.Log()),
        lengths = runCatching { lengthLog() }.getOrDefault(com.optionslab.ira.TopicLength.Log()),
        usualIndex = runCatching { indexLog() }.getOrDefault(com.optionslab.ira.UsualIndex.Log()),
        nicknames = runCatching { nickLog() }.getOrDefault(com.optionslab.ira.Nicknames.Log()))

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
        if (u.figure.isNotEmpty()) figureUpdate { com.optionslab.ira.FigureFirst.reset(it, now) }
        if (u.morning.isNotEmpty()) morningUpdate { com.optionslab.ira.MorningSense.reset(it, now) }
        if (u.stars.isNotEmpty()) starsResetAt(now)
        if (u.hours.isNotEmpty()) talkUpdate { com.optionslab.ira.TalkHours.reset(it, now) }
        if (u.asks.isNotEmpty()) { asksUpdate { com.optionslab.ira.MorningAsks.reset(it, now) }; asksOffered = null }
        if (u.turnDowns.isNotEmpty()) { turnUpdate { com.optionslab.ira.TurnDowns.reset(it, now) }; turnedDownAt = null }
        if (u.lengths.isNotEmpty()) { lengthUpdate { com.optionslab.ira.TopicLength.reset(it, now) }; lengthLast = null }
        if (u.usualIndex.isNotEmpty()) { indexUpdate { com.optionslab.ira.UsualIndex.reset(it, now) }; indexLast = null }
        if (u.nicknames.isNotEmpty()) { nickUpdate { com.optionslab.ira.Nicknames.forgetWeek(it, today) }; nickAsked = null }
        IraActivity.add("Undid this week's learning, as Boss confirmed: ${u.words.size} wording(s), ${u.routines.size} routine(s), " +
            "${u.alerts.size} alert kind(s) aloud again, ${u.clarity.size} answer kind(s) as usual aloud again, ${u.figure.size} market read kind(s) in the usual order again, ${u.morning.size} morning-check item(s) read out in full again, " +
            "${u.stars.size} confidence score(s) said plainly again, " + (if (u.hours.isNotEmpty()) "briefings in full at any hour again, " else "briefings unchanged, ") +
            (if (u.asks.isNotEmpty()) "no morning question offered, " else "morning check unchanged, ") +
            (if (u.turnDowns.isNotEmpty()) "no reason of Boss's said up front, " else "ideas asked as before, ") +
            (if (u.lengths.isNotEmpty()) "every topic at the usual length aloud, " else "topic lengths unchanged, ") +
            (if (u.usualIndex.isNotEmpty()) "Nifty again when Boss names no index, " else "the index taken unchanged, ") +
            (if (u.nicknames.isNotEmpty()) "${u.nicknames.size} nickname(s) forgotten." else "nicknames unchanged."))
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
        set(v) { runCatching { prefs().putAllSoon(mapOf("jarvis.brief" to v)) } }

    var wakeStrict: Boolean
        get() = runCatching { prefs().getBoolean("jarvis.wake.strict", false) }.getOrDefault(false)
        set(v) { runCatching { prefs().putAllSoon(mapOf("jarvis.wake.strict" to v)) } }

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

    // ---- Jarvis's own trend and range reads, scored after each close ([com.optionslab.ira.TrendReads]) ----------------

    /** The reads he gave (index, minute, kind, price and how the day ended - market data only, never Boss's words). */
    private const val TREND_READS = "jarvis.trendReads"
    @Volatile private var trendCache: List<com.optionslab.ira.TrendReads.Call>? = null

    fun trendReads(): List<com.optionslab.ira.TrendReads.Call> = trendCache ?: runCatching {
        com.optionslab.ira.TrendReads.load(prefs().getString(TREND_READS) ?: "")
    }.getOrDefault(emptyList()).also { trendCache = it }

    @Synchronized private fun trendUpdate(f: (List<com.optionslab.ira.TrendReads.Call>) -> List<com.optionslab.ira.TrendReads.Call>) {
        runCatching {
            val was = trendReads()
            val log = f(was)
            if (log == was) return@runCatching
            trendCache = log
            prefs().putAllSoon(mapOf(TREND_READS to com.optionslab.ira.TrendReads.save(log)))
        }
    }

    /** A trend or range read just given in session (null: it was no call). */
    fun trendReadSaid(call: com.optionslab.ira.TrendReads.Call?) {
        if (call == null) return
        trendUpdate { com.optionslab.ira.TrendReads.add(it, call, com.optionslab.app.data.Market.today()) }
    }

    /** The reads whose session has closed, scored on the phone's 1-minute candles. */
    fun trendReadsSettle(bars: Map<com.optionslab.ira.Market, List<com.optionslab.ira.Candle>>) {
        if (trendReads().all { it.settled }) return
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        trendUpdate { log -> bars.entries.fold(log) { l, e -> com.optionslab.ira.TrendReads.settle(l, e.key, e.value, now) } }
    }

    // ---- Boss's open legs as they stood this morning ([com.optionslab.ira.SinceMorning]) --------------------------------

    /** The day and Boss's open legs (where, symbol, quantity - no prices or amounts) noted once near the morning mark. */
    private const val MORNING_HELD = "jarvis.morningHeld"

    /** Today's morning legs and whether Zerodha's were read, or null when none were noted today. */
    fun morningHeld(today: java.time.LocalDate): com.optionslab.ira.SinceMorning.Noted? = runCatching {
        com.optionslab.ira.SinceMorning.decode(prefs().getString(MORNING_HELD) ?: "", today)
    }.getOrNull()

    /** The day the morning note was completed (Zerodha's legs read too), so the window stops reading. */
    @Volatile private var heldNotedOn: java.time.LocalDate? = null

    /** True once today's morning legs are noted with Zerodha's read; until then each refresh in the window reads again. */
    fun morningHeldNoted(today: java.time.LocalDate): Boolean =
        heldNotedOn == today || !com.optionslab.ira.SinceMorning.wantsRead(morningHeld(today))

    /** Notes [held] (with whether Zerodha's were read) as today's morning legs: the first read kept, Zerodha's filled in by a later one. */
    @Synchronized fun noteMorningHeld(today: java.time.LocalDate, held: List<com.optionslab.ira.SinceMorning.Held>, zerodha: com.optionslab.ira.SinceMorning.Zerodha,
                                      at: java.time.LocalTime) {
        runCatching {
            if (heldNotedOn == today) return@runCatching
            val kept = morningHeld(today)
            // A read that finished outside 09:40-10:15 notes nothing (renote keeps what was kept); a Zerodha read keeps its time.
            val next = com.optionslab.ira.SinceMorning.renote(kept, held, zerodha, at) ?: return@runCatching
            if (next != kept) prefs().putAllSoon(mapOf(MORNING_HELD to com.optionslab.ira.SinceMorning.encode(today, next)))
            if (!com.optionslab.ira.SinceMorning.wantsRead(next)) heldNotedOn = today
        }
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
