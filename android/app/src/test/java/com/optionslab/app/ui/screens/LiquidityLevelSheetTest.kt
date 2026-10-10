package com.optionslab.app.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.PriceAlarm
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.LiquidityOverlay
import com.optionslab.ira.LevelAlarm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import kotlin.math.max

/** A liquidity level tapped on the layer: its sheet, an alert set through the app's own alarms (once), listed and removed. */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class LiquidityLevelSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    private val day = LocalDate.of(2026, 10, 5)
    private val flow = MutableStateFlow<List<PriceAlarm>>(emptyList())
    private val store = StoreLevelAlarms(flow)

    @Before fun fresh() { Alarms.save(emptyList()) }
    @After fun noNetwork() { assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun shows(t: String) = compose.onAllNodesWithText(keepNumbersWhole(t)).fetchSemanticsNodes().isNotEmpty()
    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }
    private fun until(what: () -> Boolean) = compose.waitUntil(5_000) { idle(); what() }

    @Test fun theSheetSetsOneAlarmListsItAndRemovesIt() {
        // Another alarm on the index, not on a level: never listed here.
        Alarms.upsert(PriceAlarm(1L, PriceAlarm.CHART + "BANKNIFTY", false, 53_500.0, note = "my own"))
        val level = LevelAlarm.Level(54_180.0, 1, pool = true, from = 10, to = 40, taken = false)
        compose.setContent {
            IraAlgoTheme("light") {
                LevelSheet("BANKNIFTY", PickedLevel(level, null, 53_960.0), 15, day, 53_967.5, store) {}
            }
        }
        idle()
        listOf("Liquidity level", "Level 54,180", "Swing-high pool · 15-min chart", "212.50 pts above the last price 53,967.50",
            "Not taken today: still in play", "An alert fires when the price rises to it").forEach { assertTrue(it, shows(it)) }
        assertFalse(shows("53,500 · falls to it"))

        compose.onNodeWithText("Alert me when price reaches it").performScrollTo().performClick()
        until { Alarms.all().size == 2 }
        val a = Alarms.all().single { it.id != 1L }
        assertEquals(PriceAlarm.CHART + "BANKNIFTY", a.symbol)
        assertTrue("rises to it from below", a.above)
        assertEquals(54_180.0, a.level, 0.0)
        assertEquals("Liquidity level 54,180 (swing-high pool, 15-min)", a.note)
        assertTrue(a.enabled)
        until { shows("Alert set: BANKNIFTY rises to 54,180") }
        // Listed with the symbol's level alarms; a second one at the same price and direction is refused.
        until { shows("54,180 · rises to it · swing-high pool, 15-min") }
        compose.onNodeWithText("Alert me when price reaches it").assertIsNotEnabled()
        assertFalse(runBlocking { store.add(a.copy(id = a.id + 1, note = "again")) })
        assertEquals(2, Alarms.all().size)
        // The app's alarm list (the Alarms page's) follows.
        assertEquals(Alarms.all().sortedBy { it.id }, flow.value)

        compose.onNodeWithText("Remove").performScrollTo().performClick()
        until { Alarms.all().size == 1 }
        assertEquals(1L, Alarms.all().single().id)
        until { !shows("54,180 · rises to it · swing-high pool, 15-min") }
    }

    /** An alert at the level switched off on the Alarms page does not block it: the button switches that one back on. */
    @Test fun anAlertSwitchedOffIsTurnedBackOnNotBlocking() {
        val level = LevelAlarm.Level(54_180.0, 1, pool = true, from = 10, to = 40, taken = false)
        Alarms.upsert(PriceAlarm(7L, PriceAlarm.CHART + "BANKNIFTY", true, 54_180.0, enabled = false, note = LevelAlarm.note(level, 15)))
        flow.value = Alarms.all()
        compose.setContent {
            IraAlgoTheme("light") {
                LevelSheet("BANKNIFTY", PickedLevel(level, null, 53_960.0), 15, day, 53_967.5, store) {}
            }
        }
        idle()
        assertFalse(shows("An alert at this level is already set."))
        compose.onNodeWithText("Turn the alert back on").performScrollTo().performClick()
        until { Alarms.all().single().enabled }
        // The same alarm switched back on - not a second one.
        assertEquals(7L, Alarms.all().single().id)
        until { shows("Alert back on: BANKNIFTY rises to 54,180") }
        // Now it stands: a second is refused.
        assertFalse(runBlocking { store.add(PriceAlarm(8L, PriceAlarm.CHART + "BANKNIFTY", true, 54_180.0)) })
        assertEquals(1, Alarms.all().size)
    }

    @Test fun aLevelBelowFallsToItAndATakenOneSaysWhen() {
        val level = LevelAlarm.Level(54_000.0, -1, pool = false, from = 5, to = 20, taken = true)
        compose.setContent {
            IraAlgoTheme("light") {
                LevelSheet("FINNIFTY", PickedLevel(level, day.atTime(11, 15), 54_100.0), 5, day, 54_100.0, store) {}
            }
        }
        idle()
        listOf("Swing-low zone · 5-min chart", "100.00 pts below the last price 54,100", "Taken today at 11:15",
            "An alert fires when the price falls to it").forEach { assertTrue(it, shows(it)) }
        compose.onNodeWithText("Alert me when price reaches it").performScrollTo().performClick()
        until { Alarms.all().size == 1 }
        val a = Alarms.all().single()
        assertEquals(PriceAlarm.CHART + "FINNIFTY", a.symbol)
        assertFalse(a.above)
        assertEquals("Liquidity level 54,000 (swing-low zone, 5-min)", a.note)
    }

    @Test fun withoutAlarmsThereIsNoButton() {
        val level = LevelAlarm.Level(54_180.0, 1, pool = true, from = 10, to = 40, taken = false)
        compose.setContent {
            IraAlgoTheme("light") { LevelSheet("BANKNIFTY", PickedLevel(level, null, 54_000.0), 15, day, 54_000.0, null) {} }
        }
        idle()
        assertTrue(shows("Level 54,180"))
        assertFalse(shows("Alert me when price reaches it"))
    }

    @Test fun aLongPressOnADrawnLevelOpensItsSheet() {
        val m = LiquidityOverlay.build(LiquidityFixtures.bars, 15, "BANKNIFTY", LiquidityFixtures.now,
            listOf(LiquidityFixtures.closed, LiquidityFixtures.open))
        compose.setContent {
            IraAlgoTheme("light") { LiquidityChart("BANKNIFTY", listOf(15, 5), 15, {}, m, null, levelAlarms = store) }
        }
        idle()
        var tapped: LiquidityOverlay.Shape? = null
        compose.onNodeWithContentDescription("Liquidity levels chart").performTouchInput {
            // As the canvas lays the bars out: at least 3 dp each, beside a 52 dp label column, the newest at the right,
            // the visible bars' range with 8% spare each side.
            val plotW = max(1f, width - 52.dp.toPx())
            val w = max(3.dp.toPx(), plotW / m.bars.size)
            val start = max(0, m.bars.size - max(1, (plotW / w).toInt()))
            val view = m.bars.subList(start, m.bars.size)
            val pad = max(view.maxOf { it.high } - view.minOf { it.low }, 1.0) * 0.08
            val lo = view.minOf { it.low } - pad; val hi = view.maxOf { it.high } + pad
            val s = m.shapes.first { !it.taken && it.to >= start && it.edge in lo..hi }
            tapped = s
            val bar = max(s.from, start)
            longClick(Offset((bar - start + 0.5f) * w, (height * (1 - (s.edge - lo) / (hi - lo))).toFloat()))
        }
        until { shows("Liquidity level") }
        val s = tapped!!
        assertTrue(shows("Level " + LevelAlarm.price(s.edge)))
        assertTrue(shows("Alert me when price reaches it"))
        // The last price here is the last closed bar's close.
        assertTrue(shows(LevelAlarm.distance(s.edge, m.bars.last().close)))
        compose.onNodeWithText("Close").performClick()
        until { !shows("Liquidity level") }
    }
}
