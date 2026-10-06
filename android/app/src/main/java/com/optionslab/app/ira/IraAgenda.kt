package com.optionslab.app.ira

import com.optionslab.ira.Agenda
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jarvis's own plan for the day ([Agenda]; Boss, 5 Oct: "let him direct his own work"): made once each morning from what
 * the app knows, kept on the phone (encrypted), worked through on every market-watch pass at each item's time, said
 * when Boss asks "what's your plan today?" and summed up in the 15:35 wrap-up, with what is carried to tomorrow.
 *
 * Every item only speaks, studies or works on paper: nothing here places, changes or closes anything, and nothing adds
 * risk. A lesson from his study is put to Boss as a question (always asked, even with automatic stops on) and kept only
 * on his yes - and only as a question, never anything that acts.
 */
internal object IraAgenda {
    private const val KEY = "jarvis.agenda"
    private val lock = Mutex()

    private fun prefs() = com.optionslab.app.security.SecurePrefs

    /** The plan kept, and its day. */
    private fun read(): Pair<String, List<Agenda.Item>>? = runCatching {
        val o = JSONObject(prefs().getString(KEY) ?: return null)
        val a = o.getJSONArray("i")
        o.getString("d") to (0 until a.length()).map { n ->
            val j = a.getJSONObject(n)
            val w = j.optJSONArray("w")
            Agenda.Item(j.getString("id"), Agenda.Kind.valueOf(j.getString("k")), j.getInt("at"), j.getString("t"),
                done = if (j.has("done")) j.getString("done") else null, carried = j.optInt("c", 0),
                words = if (w == null) emptyList() else (0 until w.length()).map { w.getString(it) },
                guess = if (j.has("g")) j.getString("g") else null)
        }
    }.getOrNull()

    private fun save(day: String, items: List<Agenda.Item>) = runCatching {
        val a = JSONArray()
        items.forEach { i ->
            val j = JSONObject().put("id", i.id).put("k", i.kind.name).put("at", i.at).put("t", i.text).put("c", i.carried)
                .put("w", JSONArray(i.words))
            i.done?.let { j.put("done", it) }
            i.guess?.let { j.put("g", it) }
            a.put(j)
        }
        prefs().put(KEY, JSONObject().put("d", day).put("i", a).toString())
    }

    /** Positions open now (Zerodha in Live when logged in, else the paper account), or null when they cannot be read. */
    private suspend fun openPositions(): Int? {
        val live = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
        val b = com.optionslab.app.data.Broker
        if (live && b.loggedIn) {
            // The read blocks on the network: run apart, so the 15 s limit really ends the wait.
            val read = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { runCatching { b.passPositionBook().net.count { it.open } }.getOrNull() }
            return kotlinx.coroutines.withTimeoutOrNull(15_000) { read.await() }
        }
        // A count only (no price in it), said or put in the plan: a paper price read in the last 20 s is shared (Battery, round 9).
        return runCatching { com.optionslab.app.data.Paper.snapshot(com.optionslab.app.data.Paper.SHARED_QUOTE_MS).positions.positions.count { it.quantity != 0 } }.getOrNull()
    }

    /** Today's plan, made now if there is none yet for today ([made]: it was just made). */
    private suspend fun todayLocked(): Pair<List<Agenda.Item>, Boolean> {
        val m = com.optionslab.app.data.Market
        val day = m.today()
        val saved = read()
        if (saved?.first == day.toString()) return saved.second to false
        val trading = runCatching { m.isTradingDay(day) }.getOrDefault(false)
        val events = runCatching { IraEvents.upcoming(1) }.getOrDefault(emptyList())
        val goals = runCatching { IraGoals.statuses() }.getOrDefault(emptyList())
        val notes = runCatching { IraTools.memory().map { it.text } }.getOrDefault(emptyList())
        val minutes = runCatching { IraNewsTrades.byMinute() }.getOrDefault(emptyList())
        val bad = (9..15).filter { h -> com.optionslab.ira.ActAlone.badHour(minutes, h * 60) != null }
        val tests = runCatching { IraExpert.verdicts() }.getOrDefault(emptyList())
            .filter { it.state == com.optionslab.ira.Vetting.State.TESTING }.map { it.name }
        val open = if (trading) openPositions() else null
        // Overnight study: the words he could not place on the last day, and the answers marked wrong in the last 3 days.
        val wrong = runCatching { IraTools.mistakes() }.getOrDefault(emptyList())
            .filter { val d = it.at.toLocalDate(); d.isBefore(day) && !d.isBefore(day.minusDays(3)) }.map { it.said }
        // His own goals for the week ([IraImprove]): checked once a day; the phrasings his learning goal is set on are studied too.
        val self = runCatching { IraImprove.forAgenda() }.getOrDefault(0 to emptyList())
        val missed = runCatching { IraTools.missedBefore(day) }.getOrDefault(emptyList()) + self.second
        val teach = runCatching { Agenda.study(missed, wrong, IraTools.learned()) }.getOrDefault(emptyList())
        val items = Agenda.build(Agenda.Facts(day, trading, events, open, goals, notes, bad, tests, teach, saved?.second.orEmpty(), selfGoals = self.first))
        save(day.toString(), items)
        return items to true
    }

    suspend fun today(): List<Agenda.Item> = lock.withLock { todayLocked().first }

    /** [id] done today, with what came of it. */
    private suspend fun mark(id: String, what: String) {
        lock.withLock {
            val saved = read()
            if (saved != null && saved.first == com.optionslab.app.data.Market.today().toString()) save(saved.first, Agenda.done(saved.second, id, what))
            Unit
        }
    }

    /** "What's your plan today?" */
    suspend fun say(): String = Agenda.say(today(), com.optionslab.app.data.Market.minuteNow())

    /** The wrap-up's line: what he did and what he carries (only when a plan was made today). */
    suspend fun wrapLine(): String? {
        val items = lock.withLock { read()?.takeIf { it.first == com.optionslab.app.data.Market.today().toString() }?.second } ?: return null
        return Agenda.wrap(items)
    }

    /**
     * Every market-watch pass, 08:45 to 16:00: the day's plan made (and posted) once, then each item worked at its time -
     * two at most a pass, the most important first.
     */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.AGENDA)) return
        val minute = com.optionslab.app.data.Market.minuteNow()
        if (minute < 8 * 60 + 45 || minute >= 16 * 60) return
        IraHub.appContext() ?: return                    // made and told when it can be shown, not before
        val (items, made) = lock.withLock { todayLocked() }
        if (made) {
            Agenda.morning(items)?.let { IraHub.noteAloud(it, com.optionslab.ira.SpeakChoice.Weight.MINOR, from = Automations.Auto.AGENDA) }
            IraActivity.add("Made my plan for the day: ${items.size} thing${if (items.size == 1) "" else "s"}.")
            Automations.acted(Automations.Auto.AGENDA, "Made my plan for the day.")
        }
        for (i in Agenda.due(items, minute).take(2)) {
            if (Agenda.late(i, minute)) {
                mark(i.id, Agenda.MISSED)
                runCatching { IraThinking.add(com.optionslab.ira.Thinking.agenda(IraThinking.now(), i, done = false)) }
                continue
            }
            runCatching { work(i) }
        }
    }

    /** One item, at its time: words said, positions or goals read, the paper tests read, or a question from his study. */
    private suspend fun work(i: Agenda.Item) {
        val locked = IraHub.locked()
        val said: String = when (i.kind) {
            Agenda.Kind.EVENT, Agenda.Kind.WEAK_HOUR, Agenda.Kind.RULE, Agenda.Kind.ABOUT -> Agenda.line(i) ?: run { mark(i.id, Agenda.summary(i)); return }
            Agenda.Kind.GOAL -> Agenda.goalLine(i, runCatching { IraGoals.statuses() }.getOrDefault(emptyList()))
            Agenda.Kind.EXPIRY, Agenda.Kind.POSITIONS -> Agenda.positionsLine(i, openPositions())
            Agenda.Kind.SELF -> runCatching { IraImprove.checkLine() }.getOrNull() ?: run { mark(i.id, Agenda.summary(i)); return }
            Agenda.Kind.PAPER_TEST -> Agenda.paperLine(runCatching { IraExpert.verdicts() }.getOrDefault(emptyList()).map { it.text() })
            Agenda.Kind.TEACH -> {
                // Boss's own words: put to him only on an unlocked phone (tried again on the next pass).
                if (!locked) teach(i)
                return
            }
        }
        val what = Agenda.summary(i)
        mark(i.id, what)
        runCatching { IraThinking.add(com.optionslab.ira.Thinking.agenda(IraThinking.now(), i, done = true)) }
        IraHub.note(said, from = Automations.Auto.AGENDA)
        IraActivity.add("My plan: $what.")
        // Said aloud only what holds nothing of Boss's account or words - or anything once the phone is unlocked.
        if (i.kind.means == Agenda.Means.SPEAK && (i.kind.open || !locked)) JarvisVoice.announce(com.optionslab.ira.Wake.spoken(said, 2))
        Automations.acted(Automations.Auto.AGENDA, what)
    }

    /** A question from his study: "did you mean ...?" (Boss's yes keeps it, as a question only), or "say it another way". */
    private suspend fun teach(i: Agenda.Item) {
        val text = Agenda.ask(i)
        mark(i.id, Agenda.summary(i))
        IraActivity.add("My plan: " + Agenda.summary(i) + ".")
        Automations.acted(Automations.Auto.AGENDA, Agenda.summary(i))
        val w = i.words.firstOrNull() ?: return
        val guess = i.guess
        if (guess == null || Agenda.lessons(i).isEmpty()) {
            IraHub.note(text, from = Automations.Auto.AGENDA)
            JarvisVoice.announce(com.optionslab.ira.Wake.spoken(text, 2))
            runCatching { IraTools.expectRephrase(w) }
            return
        }
        IraHub.offer("learn a wording from my study", "Boss, teach me", text, suspend {
            val ls = Agenda.lessons(i)
            if (ls.isEmpty()) "Nothing was learned, Boss: that would not be a question."
            else {
                ls.forEach { IraTools.teach(it) }
                mark(i.id, Agenda.summary(i, learned = true))
                "Learned, Boss: when you say \"${ls.first().wrong}\", I'll take it as \"${ls.first().right}\" - a question only. Say \"forget what you learned\" to undo."
            }
        }, alwaysAsk = true)
    }
}
