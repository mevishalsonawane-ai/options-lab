package com.optionslab.app.ira

import com.optionslab.app.data.AppHealth
import com.optionslab.app.data.Diag
import com.optionslab.app.data.Market
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.data.VariantRunner
import com.optionslab.ira.AutoChange
import com.optionslab.ira.ChangeTimeline
import com.optionslab.ira.HealthTuner
import com.optionslab.ira.VariantLab
import java.time.LocalDate

/**
 * "Today's changes" (S13; the logic is ira-core's [ChangeTimeline]): every automatic change any agent made today, read from
 * the agents' own books - Autopilot's change log (its own changes and the specialists' nightly tuning), the strategy lab's
 * changes, the health tuner's cadences and the findings bus - and "Undo everything today", which undoes each change
 * through that change's own undo path, newest first ([Autopilot.undo] / [Autopilot.revertWithPin], [VariantRunner.undo],
 * [AppHealth.restoreCadence]). An undo that raises live risk is done only with Boss's PIN ([undoAll]'s pinConfirmed).
 * Reads and undoes only: nothing here places, sizes or closes anything. Off the main thread (the books are read from memory
 * once loaded). Not in IraGoldAlgo.
 */
object ChangeTimelineHost {
    fun enabled(): Boolean = Autopilot.enabled()

    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }
    private fun at(day: LocalDate, minute: Int): Long = day.atTime(minute / 60, minute % 60).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    /** The strategy lab's changes as the timeline reads them (its automatic paper promotions; a live one is Boss's). */
    internal fun labChanges(b: VariantLab.Book): List<ChangeTimeline.LabChange> = b.changes.map { c ->
        val v = b.variants.firstOrNull { it.id == c.variant }
        ChangeTimeline.LabChange(c.id, VariantLab.strategy(c.strategy)?.label ?: c.strategy, v?.moved() ?: "new rules", c.why,
            auto = c.kind == VariantLab.ChangeKind.AUTO_PAPER, live = c.kind == VariantLab.ChangeKind.LIVE_PROPOSAL, status = c.status.name, day = c.day,
            atMs = minOf(at(c.day, VariantRunner.SETTLE_FROM), System.currentTimeMillis()))
    }

    /** The health tuner's changes today: from its findings (with their times), else a cadence lengthened today (its step day). */
    private fun healthChanges(day: LocalDate): List<ChangeTimeline.HealthChange> {
        val now = System.currentTimeMillis()
        val tuned = runCatching { AppHealth.book().tuned }.getOrDefault(emptyMap())
        val seen = runCatching { SmartWorkers.bus.recent(now) }.getOrDefault(emptyList())
            .filter { it.who == "health tuner" && com.optionslab.ira.AgentLessons.dayOf(it.atMs) == day }
            .mapNotNull { f ->
                val t = HealthTuner.TUNABLES.firstOrNull { f.words.startsWith(it.words) } ?: return@mapNotNull null
                ChangeTimeline.HealthChange(t.key, f.words, f.atMs, restore = f.words.contains(" back to "), active = tuned.containsKey(t.key))
            }
        val keys = seen.map { it.key }.toSet()
        val fromBook = tuned.values.filter { it.stepOn == day && it.key !in keys }.map { t ->
            ChangeTimeline.HealthChange(t.key, "${HealthTuner.words(t.key)} slowed to every ${com.optionslab.ira.HealthBook.msWords(t.ms)} (${t.why})", at(day, 12 * 60), false, true)
        }
        return seen + fromBook
    }

    /** Every change of [day], oldest first (a worker thread). */
    fun items(day: LocalDate = today()): List<ChangeTimeline.Item> {
        runCatching { Autopilot.ensureLoaded() }
        runCatching { VariantRunner.ensureLoaded() }
        runCatching { AppHealth.ensureLoaded() }
        val now = System.currentTimeMillis()
        return ChangeTimeline.merge(
            // An undo the agent itself holds for the PIN (un-muting where the manager acts on live, the order flow's CONFIRM in
            // Live) is "needs your PIN", as Autopilot.undo will ask (review G5-2).
            ChangeTimeline.fromAutopilot(Autopilot.book.value, day) { e -> runCatching { Autopilot.undoNeedsPin(e.id) }.getOrDefault(true) },
            runCatching { ChangeTimeline.fromLab(labChanges(VariantRunner.variants.value), day) }.getOrDefault(emptyList()),
            ChangeTimeline.fromHealth(healthChanges(day)),
            runCatching { ChangeTimeline.fromFindings(SmartWorkers.bus.recent(now), day) }.getOrDefault(emptyList()),
        )
    }

    /** One change undone through its own path. What its path said, or null when it could not be undone. */
    private fun undoOne(i: ChangeTimeline.Item, pinConfirmed: Boolean): String? = runCatching {
        when (i.source) {
            ChangeTimeline.Source.AUTOPILOT, ChangeTimeline.Source.SPECIALISTS -> {
                val e = AutoChange.entry(Autopilot.book.value, i.key) ?: return@runCatching null
                val said = if (e.status == AutoChange.Status.REVERT_ASKED) Autopilot.revertWithPin(i.key, pinConfirmed)
                    else Autopilot.undo(i.key, pinConfirmed)
                said.takeIf { it.startsWith("Undone") || it.startsWith("Reverted") }
            }
            ChangeTimeline.Source.LAB -> VariantRunner.undo(i.key).takeIf { it.startsWith("Undone") }
            ChangeTimeline.Source.HEALTH -> AppHealth.restoreCadence(i.key)
            ChangeTimeline.Source.FINDING -> null
        }
    }.getOrNull()

    /** What "undo everything today" would do now (for the request's words and the sheet's button). */
    fun plan(pinConfirmed: Boolean = false): ChangeTimeline.Plan = ChangeTimeline.undoPlan(items(), pinConfirmed)

    /**
     * "Undo everything today": each change of today's that can be undone, newest first, through its own undo path; one that
     * raises live risk only with [pinConfirmed] (else listed as needing the PIN). What to say.
     */
    fun undoAll(pinConfirmed: Boolean = false): String {
        val p = plan(pinConfirmed)
        val done = p.now.map { ChangeTimeline.Done(it, undoOne(it, pinConfirmed)) }
        runCatching { Diag.record("timeline", "undo everything today: ${done.count { it.said != null }} undone, ${p.needPin.size} need the PIN, ${p.left.size} left") }
        if (done.any { it.said != null }) runCatching { IraActivity.add("Undid ${done.count { it.said != null }} of today's automatic changes on your word.") }
        return ChangeTimeline.sayUndone(done, p.needPin, p.left)
    }

    /** "Undo everything today" by voice: put to Boss as a request (never done unasked; a live raise only with the PIN on the sheet). */
    fun offerUndoAll(): String {
        val p = plan()
        if (p.now.isEmpty() && p.needPin.isEmpty()) return "There is nothing of today's to undo, Boss."
        IraHub.offer("undo everything today", "Boss, undo today's automatic changes?", ChangeTimeline.askText(p), suspend { undoAll(pinConfirmed = false) },
            addsRisk = p.needPin.isNotEmpty(), alwaysAsk = true)
        return "Asked: undo ${p.now.size} of today's automatic change${if (p.now.size == 1) "" else "s"}" +
            (if (p.needPin.isNotEmpty()) "; ${p.needPin.size} more need your PIN in Home → Automation card → Autopilot → Today's changes." else ".")
    }

    /** "What changed today across the app?" */
    fun say(): String = ChangeTimeline.say(items()) { SmartWorkers.hhmm(it) }
}
