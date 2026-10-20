package com.optionslab.app.data

/**
 * When the open positions' checks last ran (18 Oct, for the Live check's "each open position has its stop watched"): the paper
 * book's pass ([Paper.tick]: resting stops and targets fill there) and the stops-and-targets pass ([Protections.tick]: paper
 * and Zerodha protections). Two volatile stamps written once at the end of a pass - no disk, no vault, nothing that blocks;
 * memory only (a restart starts them at 0, "never checked", until the first pass).
 */
object WatchClock {
    /** Wall-clock ms the paper book's last pass ended (0: none in this process). */
    @Volatile var paperAt = 0L
        private set
    /** Wall-clock ms the stops-and-targets pass last ended (0: none in this process). */
    @Volatile var stopsAt = 0L
        private set

    fun paperChecked(now: Long = System.currentTimeMillis()) { paperAt = now }
    fun stopsChecked(now: Long = System.currentTimeMillis()) { stopsAt = now }

    // ---- 20 Oct, enhancement round 1 (idea 6): the drift guard on the 15-second stop check -------------------------------

    /** Wall-clock ms the last look at the stops STARTED (the 15-second check or the full pass; 0: none in this process). */
    @Volatile var lookAt = 0L
        private set

    private val drift = com.optionslab.ira.StopCadence.Drift()
    @Volatile private var loggedAt = 0L
    @Volatile private var unlogged = 0

    /**
     * A look at the stops starts now ([held]: something is held). A gap over 16 s since the last held look is counted for the
     * day and logged (at most once in 5 minutes, with how many since); one of 30 s or more is also shown in the app. Memory and
     * the diary's own queue only: never the order or tick path, never a wait. Returns the drift, if any.
     */
    fun looked(held: Boolean, now: Long = System.currentTimeMillis()): com.optionslab.ira.StopCadence.Gap? {
        lookAt = now
        val day = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() }
        val g = drift.look(now, held, day) ?: return null
        unlogged++
        if (g.alert || now - loggedAt >= LOG_EVERY_MS) {
            val n = unlogged
            unlogged = 0; loggedAt = now
            runCatching { Diag.record("watch", com.optionslab.ira.StopCadence.words(g) + if (n > 1) " ($n gaps over 16 s since the last note)" else "") }
        }
        if (g.alert) runCatching { com.optionslab.app.work.Alerts.post(com.optionslab.ira.StopCadence.ALERT_WORDS, com.optionslab.app.work.Alerts.Kind.ERROR, "Stop check", throttle = true) }
        return g
    }

    /** When the next 15-second look is due: 15 s after the last one STARTED ([com.optionslab.ira.StopCadence.windowEnd]). */
    fun windowEnd(now: Long = System.currentTimeMillis()): Long = com.optionslab.ira.StopCadence.windowEnd(lookAt, now)

    /** The diagnostics' line: today's gaps over 16 s while holding. */
    fun driftLine(): String = drift.line(runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() })

    private const val LOG_EVERY_MS = 5 * 60_000L

    /** TEST ONLY: as a fresh process. */
    internal fun resetForTest() { paperAt = 0L; stopsAt = 0L; lookAt = 0L; loggedAt = 0L; unlogged = 0; drift.look(0L, false, java.time.LocalDate.MIN) }
}
