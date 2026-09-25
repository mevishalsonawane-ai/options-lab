package com.optionslab.engine.strategy

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Where one trading session ends and the next begins. Port of
 * `services/strategy_module/session.py`.
 *
 * IraAlgo logs the user out at SESSION_EXPIRY_TIME (03:00 IST by default)
 * because Indian broker tokens expire daily around then. That hour, not
 * midnight, is what every "today" in the module means: a signal run rolls at
 * it and the daily loss limit resets on it. Monday 22:00 and Tuesday 01:00 are
 * the same session; midnight would split it in half.
 */
object Session {
    val DEFAULT_RESET: LocalTime = LocalTime.of(3, 0)

    /** Which session an instant belongs to (the instant is read in IST). */
    fun sessionDay(moment: ZonedDateTime, reset: LocalTime = DEFAULT_RESET): LocalDate {
        val ist = moment.withZoneSameInstant(IST)
        return if (ist.toLocalTime() < reset) ist.toLocalDate().minusDays(1) else ist.toLocalDate()
    }

    /** The instant the session containing [moment] began, in IST. */
    fun sessionStartedAt(moment: ZonedDateTime, reset: LocalTime = DEFAULT_RESET): ZonedDateTime =
        sessionDay(moment, reset).atTime(reset).atZone(IST)
}

/**
 * Timed start and timed square-off. Port of the pure rules in
 * `services/strategy_module/scheduler.py`; the phone has no APScheduler, so
 * the cron is expressed as [PlannedJob]s and the app polls [due] with its own
 * clock.
 *
 * Kept from upstream, each because the alternative is a silent failure:
 *
 * - every time is IST, whatever the device zone;
 * - a broken day list refuses the whole job rather than trading four days of
 *   five without saying so;
 * - an intraday strategy's `exit_time` always installs a square-off, even with
 *   the scheduler switched off or absent (weekdays only in that case), and
 *   fills in when `auto_stop_time` is blank;
 * - a job that missed its slot by more than [MISFIRE_GRACE] is dropped rather
 *   than firing into a market that has moved, and several missed slots
 *   coalesce into one.
 *
 * Holidays are NOT skipped upstream (a holiday start is refused by the
 * broker; a holiday stop is a no-op on a strategy not running). [due] takes an
 * `isHoliday` hook so the app can supply an exchange calendar; it suppresses
 * starts only, because a square-off is the safety half of the pair.
 */
object Scheduler {
    enum class JobKind { START, STOP }

    /** One cron job: `days` at `hour:minute` IST. [name] says where the time came from. */
    data class PlannedJob(
        val jobId: String,
        val kind: JobKind,
        val name: String,
        val days: List<DayOfWeek>,
        val hour: Int,
        val minute: Int,
    ) {
        fun asDict(): Map<String, Any?> = linkedMapOf(
            "job_id" to jobId, "kind" to kind.name.lowercase(), "name" to name,
            "day_of_week" to days.joinToString(",") { CRON_DAYS[it.value - 1] }, "hour" to hour, "minute" to minute,
        )
    }

    /** A job whose slot has come round. [scheduledAt] is the slot, not the poll time. */
    data class Due(val job: PlannedJob, val scheduledAt: ZonedDateTime)

    val MISFIRE_GRACE: Duration = Duration.ofSeconds(60)
    private val CRON_DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val WEEKDAYS = DayOfWeek.entries.take(5)
    private val HHMM = Regex("""(\d{1,2}):(\d{2})""")

    fun startJobId(strategyId: Long) = "strategy:$strategyId:start"
    fun stopJobId(strategyId: Long) = "strategy:$strategyId:stop"

    /** `HH:MM` (or a [LocalTime]) as `(hour, minute)`, strictly; null for anything else. */
    fun parseHhmm(value: Any?): Pair<Int, Int>? {
        if (value == null) return null
        if (value is LocalTime) return value.hour to value.minute
        if (value !is String) return null
        val m = HHMM.matchEntire(value.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h > 23 || mi > 59) null else h to mi
    }

    /** Day names to week-ordered days; any unknown day refuses the whole list. */
    fun cronDays(raw: Any?): List<DayOfWeek>? {
        if (raw !is List<*> || raw.isEmpty()) return null
        val days = ArrayList<DayOfWeek>()
        for (day in raw) {
            val i = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").indexOf(Py.str(day).trim().uppercase())
            if (i < 0) return null
            val d = DayOfWeek.of(i + 1)
            if (d !in days) days.add(d)
        }
        return days.sorted()
    }

    /** What should be installed for a strategy. */
    fun plannedJobs(def: StrategyDef): List<PlannedJob> = plannedJobs(
        def.id,
        def.scheduler?.let {
            linkedMapOf(
                "enabled" to it.enabled,
                "days" to it.days.map { d -> CRON_DAYS[d.value - 1].uppercase() },
                "start_time" to it.startTime?.let { t -> "%02d:%02d".format(t.hour, t.minute) },
                "auto_stop_time" to it.autoStopTime?.let { t -> "%02d:%02d".format(t.hour, t.minute) },
                "default_mode" to it.defaultMode.wire,
            )
        },
        def.exitTime,
    )

    /**
     * Port of `_planned_jobs` over the raw stored shape, so a hand-edited or
     * half-broken config is judged exactly as upstream judges it.
     */
    fun plannedJobs(strategyId: Long, scheduler: Map<String, Any?>?, exitTime: Any?): List<PlannedJob> {
        val scheduled = scheduler != null && com.optionslab.engine.risk.RiskValues.truthy(scheduler["enabled"])
        val days = if (scheduled) cronDays(scheduler!!["days"]) else null
        if (!scheduled || days == null) {
            val exitAt = parseHhmm(exitTime) ?: return emptyList()
            return listOf(
                PlannedJob(stopJobId(strategyId), JobKind.STOP, "Strategy $strategyId scheduled square-off (exit_time)", WEEKDAYS, exitAt.first, exitAt.second),
            )
        }
        val planned = ArrayList<PlannedJob>()
        parseHhmm(scheduler!!["start_time"])?.let {
            planned.add(PlannedJob(startJobId(strategyId), JobKind.START, "Strategy $strategyId scheduled start", days, it.first, it.second))
        }
        var stopAt = parseHhmm(scheduler["auto_stop_time"])
        var source = "scheduler.auto_stop_time"
        if (stopAt == null && exitTime != null) {
            stopAt = parseHhmm(exitTime)
            source = "exit_time"
        }
        stopAt?.let {
            planned.add(PlannedJob(stopJobId(strategyId), JobKind.STOP, "Strategy $strategyId scheduled square-off ($source)", days, it.first, it.second))
        }
        return planned
    }

    /** The most recent slot of [job] at or before [at], in IST. */
    fun previousFire(job: PlannedJob, at: ZonedDateTime): ZonedDateTime? {
        val ist = at.withZoneSameInstant(IST)
        for (back in 0..7) {
            val day = ist.toLocalDate().minusDays(back.toLong())
            if (day.dayOfWeek !in job.days) continue
            val slot = day.atTime(job.hour, job.minute).atZone(IST)
            if (!slot.isAfter(ist)) return slot
        }
        return null
    }

    /** The next slot of [job] strictly after [after], in IST: what a job list shows. */
    fun nextFire(job: PlannedJob, after: ZonedDateTime): ZonedDateTime? {
        val ist = after.withZoneSameInstant(IST)
        for (ahead in 0..7) {
            val day = ist.toLocalDate().plusDays(ahead.toLong())
            if (day.dayOfWeek !in job.days) continue
            val slot = day.atTime(job.hour, job.minute).atZone(IST)
            if (slot.isAfter(ist)) return slot
        }
        return null
    }

    /**
     * The jobs to run now. A job is due when its latest slot at or before
     * [now] falls after [lastCheck] (the previous poll; null on the first) and
     * is no more than [MISFIRE_GRACE] old. Missed slots coalesce: one run per
     * job, never a burst of catch-up starts.
     */
    fun due(
        def: StrategyDef,
        lastCheck: ZonedDateTime?,
        now: ZonedDateTime,
        isHoliday: (LocalDate) -> Boolean = { false },
        /** How late a slot may be caught. A poller whose ticks are further apart than [MISFIRE_GRACE] must widen it. */
        grace: Duration = MISFIRE_GRACE,
    ): List<Due> = plannedJobs(def).mapNotNull { job ->
        val slot = previousFire(job, now) ?: return@mapNotNull null
        if (lastCheck != null && !slot.isAfter(lastCheck)) return@mapNotNull null
        if (Duration.between(slot, now) > grace) return@mapNotNull null
        if (job.kind == JobKind.START && isHoliday(slot.toLocalDate())) return@mapNotNull null
        Due(job, slot)
    }

    /** What a scheduled start decides. */
    sealed interface StartDecision {
        data class Start(val mode: RunMode) : StartDecision
        data class Skip(val reason: String) : StartDecision

        /** Refused and recorded, so a schedule that quietly does nothing every morning is visible. */
        data class Refuse(val eventKind: String, val message: String) : StartDecision
    }

    /**
     * Port of `run_scheduled_start`'s gates: fail closed if the scheduler has
     * since been disabled (a job can outlive the sync that should remove it),
     * idempotent when already running, and live only when live is enabled.
     */
    fun startDecision(def: StrategyDef, running: Boolean): StartDecision {
        val config = def.scheduler
        if (config == null || !config.enabled) return StartDecision.Skip("the scheduler is disabled")
        if (running) return StartDecision.Skip("already running")
        if (config.defaultMode == RunMode.LIVE && !def.liveEnabled) {
            return StartDecision.Refuse("live_disabled", "Scheduled live start refused: live trading is not enabled for this strategy")
        }
        return StartDecision.Start(config.defaultMode)
    }

    /**
     * Port of `run_scheduled_stop`: deliberately not gated on the scheduler
     * being enabled, because an open run must be closed whatever the config now
     * says. Returns the stop reason, or null when nothing is running.
     */
    fun stopDecision(running: Boolean): String? = if (running) "scheduler" else null
}
