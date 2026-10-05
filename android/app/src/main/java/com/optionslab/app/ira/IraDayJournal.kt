package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.ira.DayJournal
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The end-of-day journal assistant ([DayJournal], usefulness round 8): after the close, and on "help me journal
 * today", today's journal entry drafted from the facts - Boss's own trades replayed, the reasons he noted, his rules
 * kept or broken, the words before an order he was shown and what followed, his goals and limits - then up to three
 * short questions by voice, on an unlocked phone only. His answers are kept in the journal in his own words (secrets
 * hidden) and never acted on: they are not read as rules, memories or commands. Shown in the chat and on the P&L
 * calendar's day card; said aloud without amounts.
 */
internal object IraDayJournal {
    private val IST = ZoneId.of("Asia/Kolkata")
    /** The journal by day and account ("2026-10-05|P"): the draft's lines and Boss's answers. The last [KEEP_DAYS] kept. */
    private const val KEY = "jarvis.daybook"
    /** Today's words before an order (times and reminders only). */
    private const val SHOWN = "jarvis.daybook.shown"
    private const val KEEP_DAYS = 90

    private fun key(day: LocalDate, live: Boolean) = "$day|${if (live) "Z" else "P"}"

    private fun book(): JSONObject = runCatching { JSONObject(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "{}") }.getOrDefault(JSONObject())

    @Synchronized private fun save(b: JSONObject) {
        val keys = b.keys().asSequence().toList().sorted()
        keys.dropLast(KEEP_DAYS).forEach { b.remove(it) }
        runCatching { com.optionslab.app.security.SecurePrefs.put(KEY, b.toString()) }
    }

    /** The day's answers already kept. */
    private fun answers(day: LocalDate, live: Boolean): List<DayJournal.Answer> = runCatching {
        val a = book().optJSONObject(key(day, live))?.optJSONArray("qa") ?: return@runCatching emptyList()
        (0 until a.length()).map { a.getJSONObject(it).let { o -> DayJournal.Answer(o.getString("k"), o.getString("q"), o.getString("a"), LocalDateTime.parse(o.getString("t"))) } }
    }.getOrDefault(emptyList())

    @Synchronized private fun keepDraft(live: Boolean, d: DayJournal.Draft) {
        val b = book()
        val e = b.optJSONObject(key(d.day, live)) ?: JSONObject()
        e.put("l", JSONArray().apply { d.lines.forEach { put(it) } })
        b.put(key(d.day, live), e)
        save(b)
    }

    @Synchronized private fun keepAnswer(day: LocalDate, live: Boolean, a: DayJournal.Answer) {
        val b = book()
        val e = b.optJSONObject(key(day, live)) ?: JSONObject()
        val qa = e.optJSONArray("qa") ?: JSONArray()
        if (qa.length() < 10) qa.put(JSONObject().put("k", a.key).put("q", a.question).put("a", a.answer).put("t", a.at.toString()))
        e.put("qa", qa)
        b.put(key(day, live), e)
        save(b)
    }

    /** Every answer Boss gave the journal's questions, both accounts, with the day it was for ("what did I say about X?"). */
    fun allAnswers(): List<Pair<LocalDate, DayJournal.Answer>> = runCatching {
        val b = book()
        b.keys().asSequence().toList().flatMap { k ->
            val day = runCatching { LocalDate.parse(k.substringBefore('|')) }.getOrNull() ?: return@flatMap emptyList<Pair<LocalDate, DayJournal.Answer>>()
            answers(day, k.substringAfter('|') == "Z").map { day to it }
        }
    }.getOrDefault(emptyList())

    /** The journal's lines for [day] in the paper or Zerodha account (the P&L calendar's day card), or none. */
    fun entry(day: LocalDate, live: Boolean): List<String> = runCatching {
        val e = book().optJSONObject(key(day, live)) ?: return@runCatching emptyList()
        val l = e.optJSONArray("l") ?: JSONArray()
        DayJournal.entry((0 until l.length()).map { l.getString(it) }, answers(day, live))
    }.getOrDefault(emptyList())

    // ---- the words before an order ([com.optionslab.ira.PreTrade]) shown today ----------------------------------

    /** A word before an order was shown now (times and reminders only: no amounts are in them). */
    @Synchronized fun shown(reminders: List<String>) {
        if (reminders.isEmpty()) return
        runCatching {
            val today = com.optionslab.app.data.Market.today().toString()
            val o = runCatching { JSONObject(com.optionslab.app.security.SecurePrefs.getString(SHOWN) ?: "{}") }.getOrDefault(JSONObject())
            val a = if (o.optString("d") == today) o.optJSONArray("w") ?: JSONArray() else JSONArray()
            if (a.length() >= 60) return@runCatching
            a.put(JSONObject().put("t", LocalDateTime.now(IST).withNano(0).toString())
                .put("r", JSONArray().apply { reminders.forEach { put(com.optionslab.ira.Secrets.redact(it).take(300)) } }))
            com.optionslab.app.security.SecurePrefs.put(SHOWN, JSONObject().put("d", today).put("w", a).toString())
        }
    }

    private fun shownToday(): List<DayJournal.Shown> = runCatching {
        val o = JSONObject(com.optionslab.app.security.SecurePrefs.getString(SHOWN) ?: return@runCatching emptyList())
        if (o.optString("d") != com.optionslab.app.data.Market.today().toString()) return@runCatching emptyList()
        val a = o.getJSONArray("w")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { x ->
            val r = x.getJSONArray("r")
            DayJournal.Shown(LocalDateTime.parse(x.getString("t")), (0 until r.length()).map { r.getString(it) })
        } }
    }.getOrDefault(emptyList())

    // ---- the draft -----------------------------------------------------------------------------------------------

    /** Today's journal drafted from the app's mode (Zerodha in Live, else paper), and which account it is. Reads only. */
    private suspend fun draft(): Pair<Boolean, DayJournal.Draft> {
        val s = AppSettings.load()
        val live = s.live
        val today = com.optionslab.app.data.Market.today()
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val own = runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList())
            .filter { it.day == today && com.optionslab.app.data.TradeBook.ownerOf(it, owners).startsWith("Manual") }
        val replays = if (own.isEmpty()) emptyList() else IraJournal.replays(own.sortedBy { it.closedAt }.takeLast(12), owners)
        val notes = IraJournal.notes().filter { it.first.toLocalDate() == today }.map { DayJournal.Noted(it.first, it.second) }
        val rules = runCatching { IraTools.memory().map { it.text } }.getOrDefault(emptyList())
        val expiry = com.optionslab.ira.Market.entries.filter { it != com.optionslab.ira.Market.VIX && it != com.optionslab.ira.Market.GOLD }
            .filter { m -> runCatching { com.optionslab.app.data.Market.isExpiryDay(m.name) }.getOrDefault(false) }.toSet()
        val goals = runCatching { IraGoals.statuses() }.getOrDefault(emptyList())
        val d = DayJournal.draft(if (live) "Zerodha" else "Paper", today, replays, notes, rules, shownToday(), goals, expiry,
            lossLimit = if (live) s.guardDailyLoss else s.guardPaperDailyLoss, tradeLimit = if (live) s.guardMaxTrades else s.guardPaperTrades,
            answered = answers(today, live).map { it.key }.toSet())
        return live to d
    }

    // ---- the questions -------------------------------------------------------------------------------------------

    /** The questions being asked now (in memory only): which account, the questions, which is open and since when. */
    private data class Asking(val live: Boolean, val day: LocalDate, val questions: List<DayJournal.Question>, val at: Int, val since: LocalDateTime) {
        val current: DayJournal.Question? get() = questions.getOrNull(at)
    }
    @Volatile private var asking: Asking? = null

    private fun open(now: LocalDateTime): Asking? = asking?.takeIf { a ->
        a.current != null && a.day == now.toLocalDate() && !now.isAfter(a.since.plusMinutes(DayJournal.OPEN_MINUTES))
    }

    /**
     * Boss's words while a question is open: kept as his answer (or a skip or a stop), and the next question said; null
     * when no question is open or the words are an order or a command (they go on as usual and the questions pause).
     * His answer is only ever kept - never read as a rule, a memory or a command.
     */
    @Synchronized fun heard(q: String): String? {
        val now = LocalDateTime.now(IST)
        val a = open(now) ?: return null
        // Asking for the journal again starts it afresh; a question of his own ("...?") is a question, not an answer.
        if (DayJournal.asked(q) || q.trim().endsWith("?")) { asking = null; return null }
        val heard = DayJournal.heard(q)
        if (heard == DayJournal.Heard.ANSWER) {
            val p = runCatching { com.optionslab.ira.Ask.parse(q) }.getOrNull()
            if (p == null || p.order != null || p.command != null) { asking = null; return null }
            // A question of his (voice gives no "?"), a reminder or a note to remember is not an answer: it is answered as
            // usual and the journal pauses (review, 5 Oct). His own reflections ("I thought...", "main...") stay answers.
            val t = q.trim().lowercase()
            val mine = Regex("^(i|i'm|im|i was|i thought|i felt|i wanted|i should|my|main|mujhe|mera|maine|because|kyunki|bas)\\b").containsMatchIn(t)
            val asks = Regex("^(jarvis,? )?(what|how|why|where|when|which|who|is|are|can|could|will|should|do|does|did|tell me|show me|kya|kitna|kitne|kaise|kab|kahan|kyun)\\b").containsMatchIn(t) ||
                !mine && p.topics.any { it != com.optionslab.ira.Topic.OFF_TOPIC && it != com.optionslab.ira.Topic.GREETING }
            val other = runCatching { com.optionslab.ira.Reminder.asked(q) || com.optionslab.ira.Memory.toKeep(q) != null ||
                com.optionslab.ira.SaidAbout.asked(q) != null }.getOrDefault(false)
            if (asks || other) { asking = null; return null }
        }
        val cur = a.current ?: return null
        if (heard == DayJournal.Heard.ANSWER) {
            val words = DayJournal.kept(q)
            if (words.isNotBlank()) {
                keepAnswer(a.day, a.live, DayJournal.Answer(cur.key, cur.text, words, now.withNano(0)))
                IraActivity.add("Kept your journal answer.")
            }
        }
        if (heard == DayJournal.Heard.STOP) { asking = null; return DayJournal.next(heard, cur) }
        val next = a.copy(at = a.at + 1, since = now)
        // A locked phone: the questions pause (they name his trades); "help me journal today" goes on after the unlock.
        if (next.current != null && runCatching { IraHub.locked() }.getOrDefault(true)) {
            asking = null
            return (if (heard == DayJournal.Heard.ANSWER) "Noted in your journal, Boss." else "Skipped, Boss.") +
                " Unlock the phone and say \"help me journal today\" for the rest."
        }
        asking = if (next.current == null) null else next
        return DayJournal.next(heard, next.current)
    }

    /** Starts the questions of [d] now (unlocked phone only): the first one's words, or null when there are none. */
    @Synchronized private fun start(live: Boolean, d: DayJournal.Draft): String? {
        val q = d.questions.firstOrNull()
        if (q == null) { asking = null; return null }
        asking = Asking(live, d.day, d.questions, 0, LocalDateTime.now(IST))
        return DayJournal.first(q, d.questions.size)
    }

    /**
     * "Help me journal today" (asked on an unlocked phone): today's draft, kept, with the first question first so it is
     * heard even when only one sentence is said.
     */
    suspend fun help(): String {
        val (live, d) = draft()
        if (d.trades == 0) return d.lines.first()
        keepDraft(live, d)
        val first = if (runCatching { IraHub.locked() }.getOrDefault(true)) null else start(live, d)
        IraActivity.add("Drafted today's journal.")
        return listOfNotNull(first, d.spoken, d.lines.joinToString("\n")).joinToString("\n")
    }

    /**
     * After the close (the Coach switch): today's draft in the chat, said without amounts; on an unlocked phone the
     * first question follows. Nothing when Boss traded nothing of his own today.
     */
    suspend fun evening() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.JOURNAL)) return
        val (live, d) = runCatching { draft() }.getOrNull() ?: return
        if (d.trades == 0) return
        keepDraft(live, d)
        IraHub.note(com.optionslab.ira.Address.boss("Your journal for today, drafted from the facts.\n" + d.lines.joinToString("\n")))
        // The wrap-up is said first: wait (at most 90 s) until Jarvis has finished speaking, so this never cuts it off.
        kotlinx.coroutines.delay(2_000)
        var waited = 0
        while (JarvisVoice.speakingNow && waited < 90) { kotlinx.coroutines.delay(1_000); waited++ }
        val locked = runCatching { IraHub.locked() }.getOrDefault(true)
        val first = if (locked) null else start(live, d)
        first?.let { IraHub.note(it) }
        val said = if (first != null) "${d.spoken} $first"
            else d.spoken + if (d.questions.isNotEmpty()) " Unlock the phone and say \"help me journal today\" for them." else ""
        JarvisVoice.announce(com.optionslab.ira.Overheard.said(said, locked, "Boss, today's journal is drafted in the chat."))
        Automations.acted(Automations.Auto.JOURNAL, "Drafted today's journal: ${d.trades} trade${if (d.trades == 1) "" else "s"}, ${d.questions.size} question${if (d.questions.size == 1) "" else "s"}.")
    }
}
