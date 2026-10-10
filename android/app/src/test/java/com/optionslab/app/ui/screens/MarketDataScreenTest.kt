package com.optionslab.app.ui.screens

import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.MarketRecorder
import com.optionslab.app.data.RecordedData
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.FakePicker
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.testing.has
import com.optionslab.app.testing.reveal
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.MarketRecord
import com.optionslab.ira.RecorderFeeds
import com.optionslab.ira.RecorderStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime

/** Synthetic recorder days, written with the recorder's own writers ([MarketRecord], [RecorderFeeds]). */
internal object MarketDataFixtures {
    private fun t(h: Int, m: Int, s: Int = 0) = LocalTime.of(h, m, s)
    private val expiry = LocalDate.of(2026, 10, 27)

    /** A full day on [day]: levels every minute, NIFTY's future and its book, two headlines, GIFT Nifty, FII's OI. */
    fun day(day: LocalDate, up: Boolean = true, fiiLong: Long = 40_000): List<String> {
        val out = ArrayList<String>()
        out += MarketRecord.header(day)
        out += RecorderFeeds.gift(t(9, 0), RecorderFeeds.Gift("NIFTY", expiry, 25_040.0, 30.0, 0.12, 1000, null))
        var v = 25_000.0
        var oi = 10_000_000L
        for (m in 0 until 375) {
            val at = t(9, 15).plusMinutes(m.toLong()).plusSeconds(5)
            out += MarketRecord.spot(at, "NIFTY", v)
            out += MarketRecord.spot(at, "BANKNIFTY", v * 2.2)
            val q = MarketRecord.Quote(v + 60, volume = 1000L * m, oi = oi, bid = v + 59.5, bidQty = 75, ask = v + 60.5, askQty = 150,
                buyQty = 300_000L + m * 100, sellQty = 200_000L)
            out += MarketRecord.future(at, "NIFTY", "NIFTY26OCTFUT", expiry, q, v)
            out += MarketRecord.depth(at, "NIFTY26OCTFUT", q)
            v += if (up) 1.0 else -1.0
            oi += 1_000
        }
        out += MarketRecord.news(MarketRecord.News(t(10, 0, 10), "Reuters", null, "RBI holds rates, markets rally", "https://x.test/a-$day",
            0.8, "positive", listOf("NIFTY", "BANKNIFTY"), true))
        out += MarketRecord.news(MarketRecord.News(t(11, 30, 10), "Mint", null, "Foreign funds sell banks", "https://x.test/b-$day",
            -0.6, "negative", listOf("BANKNIFTY"), false))
        val rows = RecorderFeeds.CLIENTS.map { c ->
            val f = MutableList(RecorderFeeds.COLUMNS.size) { 50_000L }
            if (c == "FII") { f[0] = fiiLong; f[1] = 100_000 - fiiLong }
            RecorderFeeds.Participant(c, f)
        }
        val p = RecorderFeeds.Participants(day, rows)
        rows.forEach { out += RecorderFeeds.participant(p, day.atTime(18, 45), "oi", it) }
        return out
    }

    /** A day the recorder opened and could read nothing on. */
    fun emptyDay(day: LocalDate): List<String> = listOf(MarketRecord.header(day), MarketRecord.gap(t(9, 0), "quotes", "no quotes returned"))

    val newest: LocalDate = LocalDate.of(2026, 10, 6)

    /** [n] recorded days (newest first, as the list gives them) and their summaries. */
    fun cross(n: Int): Pair<List<Pair<LocalDate, Long>>, List<RecorderStats.Summary>> {
        val dates = (0 until n).map { newest.minusDays(it.toLong()) }
        val sums = dates.mapIndexed { i, d -> RecorderStats.summary(d, RecorderStats.parse(day(d, up = i % 3 != 0, fiiLong = 30_000L + i * 1_500))) }
        return dates.map { it to 800_000L } to sums
    }
}

/** The "Market data" viewer on the phone's own (encrypted) recorder files: the day picker, an empty day, a day with data. */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class MarketDataScreenTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val mon = LocalDate.of(2026, 10, 5)
    private val tue = LocalDate.of(2026, 10, 6)

    @Before fun up() { AreaE.resetGlobals(); MarketRecorder.wipe() }

    @After fun down() {
        MarketRecorder.wipe()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { IraAlgoTheme("light") { content() } }
        compose.waitForIdle()
    }

    private fun tap(text: String) { compose.onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }

    @Test fun nothingRecordedSaysSo() {
        show { MarketDataBody() }
        compose.waitForText("Nothing recorded yet", substring = true)
        compose.waitForText("First lessons from 20 recorded days: 0 so far.")
        compose.waitForText("No NSE participant-wise OI recorded yet", substring = true)
    }

    @Test fun theDayPickerShowsAnEmptyDayAndADayWithData() {
        MarketRecorder.append(mon, MarketDataFixtures.day(mon))
        MarketRecorder.append(tue, MarketDataFixtures.emptyDay(tue))
        show { MarketDataBody() }
        // The newest day first: Tuesday, with nothing but a gap.
        compose.waitForText("Tue 6 Oct")
        compose.waitForText("Mon 5 Oct")
        compose.onNodeWithText("Tue 6 Oct").assertIsSelected()
        compose.waitForText("Nothing recorded on 6 Oct")
        compose.waitForText("Not everything was recorded")
        assertTrue(compose.has("quotes", substring = true))
        // Monday: the headlines with the moves after them, the future and its book.
        tap("Mon 5 Oct")
        compose.onNodeWithText("Mon 5 Oct").assertIsSelected()
        compose.waitForText("RBI holds rates, markets rally")
        compose.waitForText("News timeline")
        // 10:00:10 -> NIFTY at 25045 (minute 45), +30 points 30 minutes on, the tone's way.
        compose.waitForText("NIFTY at 25045:", substring = true)
        assertTrue(compose.has("+30 min +30 (+0.12%) agreed", substring = true))
        assertTrue(compose.has("Foreign funds sell banks"))
        assertTrue(compose.has("After the fact", substring = true))
        compose.reveal("Index futures: OI and basis")
        compose.waitForText("LONG BUILD-UP")
        compose.reveal("Futures book per 15 minutes")
        compose.waitForText("09:15")
        compose.reveal("FII index futures: long %")
        compose.waitForText("40.0%")
        compose.reveal("First lessons")
        compose.waitForText("First lessons from 20 recorded days: 1 so far.")
        // Read once, kept: the second read is the same parsed day.
        val size = MarketRecorder.recordedDays().first { it.first == mon }.second
        assertTrue(RecordedData.day(mon, size) === RecordedData.day(mon, size))
    }

    @Test fun theRecorderCardOpensTheViewer() {
        val offline = OfflineModel(app)
        try {
            MarketRecorder.on = true
            MarketRecorder.append(mon, MarketDataFixtures.day(mon))
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides FakePicker(app)) { IraAlgoTheme("light") { DataPage(offline.model) } }
            }
            compose.reveal("Open recorded data")
            compose.waitForText("1 (since", substring = true)
            compose.until(10_000, "the button enabled") {
                runCatching { compose.onNodeWithText("Open recorded data").assertIsEnabled() }.isSuccess
            }
            tap("Open recorded data")
            compose.waitForText("What the recorder has saved · information only")
            compose.waitForText("RBI holds rates, markets rally")
            compose.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
            compose.until(10_000, "the viewer closed") { !compose.has("What the recorder has saved · information only") }
        } finally { offline.close() }
    }

    @Test fun nothingToOpenWithoutDays() {
        val offline = OfflineModel(app)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides FakePicker(app)) { IraAlgoTheme("light") { DataPage(offline.model) } }
            }
            compose.reveal("Open recorded data")
            compose.waitForText("none yet")
            compose.onNodeWithText("Open recorded data").assertIsNotEnabled()
            assertFalse(compose.has("Market data"))
        } finally { offline.close() }
    }
}

/** The page at the largest font on every size and theme: a day with data and its lessons, an empty day. Nothing cut or overlapping. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarketDataLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix().filter { (it[0] as DeviceConfig).fontScale == 2.0f }
    }

    @Test fun aDayWithDataAndTheLessons() {
        val (days, sums) = MarketDataFixtures.cross(20)
        val d = MarketDataFixtures.newest
        val view = DayView.of(d, RecorderStats.parse(MarketDataFixtures.day(d)))
        checkScreen("market-data-day") { MarketDataContent(days, d, {}, view, CrossDays(sums, 20, 20)) }
        assertTrue(compose.onAllNodesWithText("Tue 6 Oct").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun anEmptyDayWhileReading() {
        val d = MarketDataFixtures.newest
        val view = DayView.of(d, RecorderStats.parse(MarketDataFixtures.emptyDay(d), bad = 2))
        checkScreen("market-data-empty") { MarketDataContent(listOf(d to 120L, d.minusDays(1) to 0L), d, {}, view, CrossDays(null, 1, 2)) }
        assertTrue(compose.onAllNodesWithText("Nothing recorded on 6 Oct").fetchSemanticsNodes().isNotEmpty())
    }
}
