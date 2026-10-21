package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The battery and heat budget for the nightly and background work (S18): the strategy forge, the history vault, the
 * weekly consolidation and Jarvis's nightly learning ask [decide] before they start (and between steps). They skip or
 * pause when:
 *
 *  - the battery is under [MIN_BATTERY_PCT]% and not charging;
 *  - the battery is hotter than [MAX_TEMP_C] °C (BatteryManager's EXTRA_TEMPERATURE, tenths of a degree);
 *  - Android's power-save mode is on;
 *  - tonight's CPU time for the background jobs is spent ([NIGHT_CPU_MS]; a "night" runs from [NIGHT_FROM_HOUR]:00 to the
 *    next morning's [NIGHT_TO_HOUR]:00, and the day's work before it counts toward the day's own budget: [budgetOf]).
 *
 * Nothing on the order path, the market watch, stops or exits ever asks: only background jobs that can wait. Pure.
 */
object BudgetRule {
    const val MIN_BATTERY_PCT = 30
    const val MAX_TEMP_C = 40.0
    /** Background CPU time allowed a night (and a day): ten minutes. */
    const val NIGHT_CPU_MS = 10 * 60_000L
    const val NIGHT_FROM_HOUR = 16
    const val NIGHT_TO_HOUR = 8
    /**
     * 12 Oct ("tonight's background time is spent: the history vault waits for tomorrow" at 16:24, "Kept 24 pieces of 62"):
     * the last [HISTORY_RESERVE_MS] of each budget is the history vault's - the other jobs stop short of it, so the vault
     * (the phone's only copy of the day's option prices, gone from the feed in days) always gets its share.
     */
    const val HISTORY_RESERVE_MS = 3 * 60_000L

    enum class Job(val words: String) {
        FORGE("the strategy forge"), HISTORY("the history vault"), CONSOLIDATION("the weekly consolidation"), LEARNING("Jarvis's nightly learning"),
        ORDER_REVIEW("the order review"),
        /** 20 Oct: the Candle agent's daily study of older candles (24x7 learning, outside market hours). */
        CANDLE_STUDY("the Candle agent's study"),
    }

    /** The phone now: battery level (0-100; null unknown), charging, temperature (°C; null unknown), power save. */
    data class Phone(val batteryPct: Int?, val charging: Boolean, val tempC: Double?, val powerSave: Boolean)

    data class Decision(val allow: Boolean, val why: String)

    /** The budget's "night" a moment belongs to: the date its evening started (before 08:00 counts to the day before). */
    fun nightOf(t: LocalDateTime): LocalDate = if (t.hour < NIGHT_TO_HOUR) t.toLocalDate().minusDays(1) else t.toLocalDate()

    /**
     * The budget a moment spends (review G8-10): the day's own from [NIGHT_TO_HOUR]:00 to [NIGHT_FROM_HOUR]:00 ("2026-10-12 day"),
     * else the night's ("2026-10-12 night", the date its evening started) - the day's work never spends the night's 10 minutes.
     */
    fun budgetOf(t: LocalDateTime): String =
        if (t.hour in NIGHT_TO_HOUR until NIGHT_FROM_HOUR) "${t.toLocalDate()} day" else "${nightOf(t)} night"

    /**
     * The background jobs' CPU, each moment counted once (review G8-10): a job's run counts the process's CPU clock from its last
     * ask to this one ([ask]; asks more than [runGapMs] apart start a new run, the gap not counted) or to its end ([done]), but
     * a stretch another job running at the same time already counted is not counted again. Thread-safe; the clocks are given.
     */
    class CpuMeter(private val runGapMs: Long = 120_000L) {
        private val last = HashMap<Job, Pair<Long, Long>>()      // the CPU clock and the wall clock at the job's last ask
        private var countedTo = Long.MIN_VALUE                   // the CPU clock up to which time is already counted
        @Volatile var spentMs = 0L; private set

        private fun charge(from: Long, to: Long) {
            val start = maxOf(from, countedTo)
            if (to > start) spentMs += to - start
            if (to > countedTo) countedTo = to
        }

        /** [job] asks at [cpuMs] (the process's CPU clock) and [wallMs]: what is spent so far. */
        @Synchronized fun ask(job: Job, cpuMs: Long, wallMs: Long): Long {
            val prev = last.put(job, cpuMs to wallMs)
            if (prev != null && wallMs - prev.second in 0..runGapMs && cpuMs >= prev.first) charge(prev.first, cpuMs)
            return spentMs
        }

        /** [job]'s run ended at [cpuMs]: the CPU since its last ask is counted however long that was. */
        @Synchronized fun done(job: Job, cpuMs: Long) {
            val prev = last.remove(job) ?: return
            if (cpuMs >= prev.first) charge(prev.first, cpuMs)
        }

        /** A new budget (a new day or night). */
        @Synchronized fun reset() { last.clear(); countedTo = Long.MIN_VALUE; spentMs = 0L }
    }

    /**
     * May [job] run now? [cpuUsedMs]: the background CPU spent so far this budget. Every job but the history vault stops at
     * [capMs] less [HISTORY_RESERVE_MS] (the vault's share); the vault may use the whole budget.
     */
    fun decide(job: Job, phone: Phone, cpuUsedMs: Long, capMs: Long = NIGHT_CPU_MS): Decision {
        val pct = phone.batteryPct
        if (pct != null && pct < MIN_BATTERY_PCT && !phone.charging) return Decision(false, "battery at $pct% and not charging: ${job.words} waits")
        val t = phone.tempC
        if (t != null && t > MAX_TEMP_C) return Decision(false, "battery at ${"%.1f".format(java.util.Locale.ENGLISH, t)} °C: ${job.words} waits for it to cool")
        if (phone.powerSave) return Decision(false, "battery saver is on: ${job.words} waits")
        if (cpuUsedMs >= capMs) return Decision(false, "tonight's background time (${capMs / 60_000} min of CPU) is spent: ${job.words} waits for tomorrow")
        if (job != Job.HISTORY && cpuUsedMs >= capMs - HISTORY_RESERVE_MS)
            return Decision(false, "tonight's background time is down to the history vault's share: ${job.words} waits for tomorrow")
        return Decision(true, "ok")
    }

    /** BatteryManager's EXTRA_TEMPERATURE (tenths of a degree) in °C; null when not given. */
    fun tempOf(tenths: Int): Double? = if (tenths <= 0 || tenths == Int.MIN_VALUE) null else tenths / 10.0

    /** The level as a percentage from EXTRA_LEVEL and EXTRA_SCALE; null when not given. */
    fun pctOf(level: Int, scale: Int): Int? = if (level < 0 || scale <= 0) null else (level * 100 / scale)
}
