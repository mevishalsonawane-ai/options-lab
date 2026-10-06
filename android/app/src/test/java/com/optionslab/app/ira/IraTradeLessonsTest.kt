package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Market
import com.optionslab.app.data.OrbArms
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.app.work.LiquidityNotices
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Jarvis's lesson on a closed Liquidity 15+5 trade ([IraTradeLessons], [com.optionslab.ira.TradeLesson]) on
 * LiquidityNoticesTest's failed-break day (the 5-minute book buys the 54,000 CE at 13:05 and sells it at 13:10): exactly
 * one chat line for the trade, never twice, and the bot-trades answer carries the same lesson.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class IraTradeLessonsTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|LIQCE"

    private fun bar(k: Int): DoubleArray = when (k) {
        20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        46 -> doubleArrayOf(54_140.0, 54_145.0, 54_080.0, 54_090.0)      // closes back under 54,100: the failed break
        else -> doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
    }

    private fun feed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start ->
            val b = bar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0)
        }

    @Before fun up() {
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.JARVIS)
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        Background.grantNotifications(context)
        LiquidityNotices.resetForTest()
        day = AutomationSupport.tradingDayAt(12, 50).toLocalDate()
        at(LocalTime.of(12, 50))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-LIQ-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.PE, 30, "NSE_FO|LIQPE0", "BANKNIFTY-LIQ-54000PE")))
        upstox.price(ceKey, 300.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
        IraTools.brief = false
    }

    @After fun down() {
        if (!::upstox.isInitialized) return
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun passes(from: LocalTime, until: LocalTime) {
        var t = from
        while (!t.isAfter(until)) { at(t); runBlocking { OrbArms.tick() }; t = t.plusMinutes(1) }
    }

    private fun lessonLines(): List<String> = IraHub.state.value.messages.filter { it.fromIra }.map { it.text }
        .flatMap { it.lines() }.filter { it.startsWith("Liquidity 5m trade closed - ") }

    @Test fun aClosedTradeGetsExactlyOneLessonLineAndTheBotTradesAnswerCarriesIt() {
        AppSettings.save(AppSettings.load().copy(hideAmountsOnLockScreen = false))
        val msg = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(msg, msg.startsWith("Liquidity 15+5 armed on paper"))
        val before = lessonLines().size
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        runBlocking { IraTradeLessons.watch() }
        assertEquals("nothing closed yet: no lesson", before, lessonLines().size)
        passes(LocalTime.of(13, 6), LocalTime.of(13, 10))
        // The best and worst premium seen while held are kept for the lesson.
        val p = runBlocking { OrbArms.liquidityToday(day) }.single()
        assertTrue(p.toString(), !p.open && p.peak != null && p.low != null)
        runBlocking { IraTradeLessons.watch() }
        passes(LocalTime.of(13, 11), LocalTime.of(13, 13))
        runBlocking { IraTradeLessons.watch(); IraTradeLessons.watch() }
        val said = lessonLines().drop(before)
        assertEquals(said.toString(), 1, said.size)
        val lesson = said.single().removePrefix("Liquidity 5m trade closed - ")
        // (A loss on this day: its first sentence, said even on short answers, names the exit against its share.)
        assertTrue(lesson, lesson.contains("failed break") && lesson.contains("of Liquidity's"))
        // "Why did the liquidity bot exit?": the same lesson in the answer.
        val q = com.optionslab.ira.BotTrades.asked("why did the liquidity bot exit?")
        assertNotNull(q)
        val answer = runBlocking { IraBots.tradesToday(q!!, emptyList()) }
        assertTrue(answer, answer.contains("Against the research: $lesson"))
        // The wrap-up's line for the day.
        val wrap = runBlocking { IraTradeLessons.wrapLine() }
        assertTrue(wrap.toString(), wrap!!.startsWith("Liquidity 15+5 in research terms: 1 trade ("))
    }

    /** The lessons are words only: a live (Zerodha) Liquidity trade closed today has its lesson too, beside a paper one. */
    @Test fun aLiveLiquidityTradeHasItsLessonToo() {
        fun closed(arm: String, symbol: String, live: Boolean, h: Int) = org.json.JSONObject().put("arm", arm).put("symbol", symbol)
            .put("right", "CE").put("qty", 30).put("entry", 200.0).put("entryTime", day.atTime(h, 0).toString())
            .put("signalBar", day.atTime(h, 0).minusMinutes(5).toString()).put("exit", 230.0).put("exitTime", day.atTime(h, 20).toString())
            .put("why", "next_liquidity").put("charges", 40.0).put("live", live).put("kite", if (live) "K1" else "").put("lot", 30)
        AutomationSupport.orbState(context, org.json.JSONObject().put("positions", org.json.JSONArray()
            .put(closed("liquidity5", "BANKNIFTY-LIQ-PAPER", false, 10))
            .put(closed("liquidity15", "BANKNIFTY-LIQ-LIVE", true, 11))))
        assertEquals("the paper reader is unchanged", 1, runBlocking { OrbArms.liquidityToday(day) }.size)
        assertEquals(2, runBlocking { OrbArms.liquidityTodayWithLive(day) }.size)
        val lines = runBlocking { IraTradeLessons.unsaid(brief = false) }
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines.toString(), lines.any { it.startsWith("Liquidity 15m trade closed - ") })
        val wrap = runBlocking { IraTradeLessons.wrapLine() }
        assertTrue(wrap.toString(), wrap!!.startsWith("Liquidity 15+5 in research terms: 2 trades ("))
    }

    /** The arms' tick may hold their lock across a network read: the lesson waits a short while, then says nothing. */
    @Test fun aHeldArmsLockNeverStallsTheWordsLane() {
        val lock = OrbArms::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null) as kotlinx.coroutines.sync.Mutex
        runBlocking { lock.lock() }
        try {
            val t0 = System.currentTimeMillis()
            runBlocking { IraTradeLessons.watch() }
            val wrap = runBlocking { IraTradeLessons.wrapLine() }
            val waited = System.currentTimeMillis() - t0
            assertEquals(null, wrap)
            assertTrue("waited $waited ms", waited < 15_000)
        } finally { lock.unlock() }
    }
}
