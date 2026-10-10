package com.optionslab.app.ira

import com.optionslab.ira.BigMoveRisk
import com.optionslab.ira.MorningCues
import com.optionslab.ira.TodayGlance
import java.time.LocalDate
import java.time.LocalDateTime
import com.optionslab.ira.Market as IraMarket

/**
 * The reads Home's "Today at a glance" card ([TodayGlance]) shares with Jarvis's own answers, from the same inputs:
 * the big-move risk ([BigMoveRisk.read] on the 1-minute candles Jarvis last read, [IraHub.recentBars], and India VIX's),
 * GIFT Nifty against Nifty's previous close and the FIIs' positioning ([MorningCues], from the market recorder's saved
 * readings). Reads only what the phone already holds - no network, nothing written. Call off the main thread.
 */
internal object GlanceReads {
    /** The recorder's participant files are decrypted at most this often (ms): they change once a day, after 18:30. */
    private const val FII_EVERY_MS = 30 * 60_000L
    @Volatile private var fiiKept: Triple<Long, LocalDate, String>? = null

    /** [m]'s big-move risk at [now], as Jarvis reads it when asked ("is a big move likely now?"). */
    fun bigMove(m: IraMarket, now: LocalDateTime, tradingDay: Boolean): BigMoveRisk.Read =
        BigMoveRisk.read(m, IraHub.recentBars(m), IraHub.recentBars(IraMarket.VIX), now, tradingDay)

    /** The GIFT Nifty line: the recorder's last reading against Nifty's previous close (as the 09:00 check reads it). */
    fun gift(now: LocalDateTime): String {
        val g = runCatching { com.optionslab.app.data.MarketRecorder.lastGift() }.getOrNull()
        val prev = g?.let { (read, gift) -> runCatching { MorningCues.prevClose(IraHub.recentBars(IraMarket.NIFTY), gift.at ?: read) }.getOrNull() }
        return TodayGlance.giftLine(g?.second, g?.first, prev, now)
    }

    /** The FIIs' positioning line from the recorder's two newest participant files; decrypted at most every 30 minutes. */
    fun fii(today: LocalDate, nowMs: Long = System.currentTimeMillis()): String {
        fiiKept?.takeIf { it.second == today && nowMs - it.first < FII_EVERY_MS }?.let { return it.third }
        val ps = runCatching { com.optionslab.app.data.MarketRecorder.participantsRecorded() }.getOrDefault(emptyList())
        val line = TodayGlance.fiiLine(MorningCues.fii(ps.getOrNull(0)), MorningCues.fii(ps.getOrNull(1)), today)
        fiiKept = Triple(nowMs, today, line)
        return line
    }
}
