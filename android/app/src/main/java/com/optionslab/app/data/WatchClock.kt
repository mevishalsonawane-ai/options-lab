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

    /** TEST ONLY: as a fresh process. */
    internal fun resetForTest() { paperAt = 0L; stopsAt = 0L }
}
