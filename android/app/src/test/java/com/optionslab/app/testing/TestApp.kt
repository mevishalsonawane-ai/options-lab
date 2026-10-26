package com.optionslab.app.testing

import android.app.Application
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.Broker
import com.optionslab.app.data.History
import com.optionslab.app.data.Holidays
import com.optionslab.app.data.Journal
import com.optionslab.app.data.KiteStream
import com.optionslab.app.data.Ledger
import com.optionslab.app.data.Market
import com.optionslab.app.data.OrbArms
import com.optionslab.app.data.Paper
import com.optionslab.app.data.PineAuto
import com.optionslab.app.data.ShadowArms
import com.optionslab.app.data.Protections
import com.optionslab.app.data.StaticIp
import com.optionslab.app.data.Store
import com.optionslab.app.data.Strategies
import com.optionslab.app.data.TradeBook
import com.optionslab.app.security.SecurePrefs

/**
 * The Application every Robolectric test runs in (set in robolectric.properties).
 *
 * Does what [com.optionslab.app.IraAlgoApp.onCreate] does to make the stores usable - every
 * `init(context)` - and nothing else: no crash handler, no notification channels, no
 * [com.optionslab.app.work.Jobs] (alarms / WorkManager), no process-lifecycle observer and
 * no PineScripts background thread. [NetworkGuard] makes any attempt to reach the internet fail.
 *
 * Robolectric builds a new Application (with new, empty app directories) for every test, but
 * the app's `object` singletons live on for the whole run; so each store's in-memory cache is
 * dropped here too, and the fake Keystore is emptied - every test starts on a "fresh phone".
 */
class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.optionslab.app.ui.components.Splash.enabled = false
        com.optionslab.app.ui.LoginPrompt.enabled = false
        // 25 Oct: the market clock is pinned to noon on a trading day before the test instance is built (Robolectric builds the
        // Application first), so a test class's fields read the same day as its body (MarketClockRule), never the wall clock.
        MarketClockRule.pinProcessDefault()
        // Screen tests place paper orders at whatever hour CI runs; PaperMarketHoursTest turns this off.
        Market.testOrdersAnyTime = true
        // The fake candle feeds are a few fixed minutes of the morning: the 08 Oct feed checks (a stale price never fills an
        // entry, Pine's thin-option check and its best price from the minute highs) are off unless a test turns them on.
        Paper.testSkipFeedChecks = true
        // 19 Oct: the paper book's and the passed arms' vault writes on the caller's thread, as before, so a test can read the file
        // right after an order; the order-speed tests (ExposureSpeedTest) turn this off to measure the app's background writes.
        Paper.writeInlineForTest = true
        com.optionslab.app.data.PassedArmsHost.writeInlineForTest = true
        Strategies.writeInlineForTest = true
        com.optionslab.app.security.Vault.testWriteDelayMs = 0L
        // 20 Oct: a backup restore freezes its vault files until the restart (no restart in a test): every test starts thawed.
        com.optionslab.app.security.Vault.thaw()
        com.optionslab.app.security.Vault.testFailWrites = emptySet()
        // The same-direction cap off for every other test (their arms' many same-way entries are not what they test); its own
        // tests switch it on.
        com.optionslab.app.data.ExposureCapHost.resetForTest(paperOn = false)
        // 20 Oct, enhancement round 1: the account stop rule in shadow with no days, the drift guard with no looks.
        com.optionslab.app.data.AccountStopHost.resetForTest()
        // 25 Oct: the new paper risk rules off for every other test (their arms' entries are not what they test); their own tests
        // switch them on ([com.optionslab.app.data.BossRiskHost]).
        com.optionslab.app.data.BossRiskHost.resetForTest(paperOn = false)
        com.optionslab.app.work.StopLane.quietNow = false
        com.optionslab.app.data.WatchClock.resetForTest()
        IdleSampler.install()
        NetworkGuard.install()
        NetworkGuard.blocked.clear()
        KiteStream.testOff = true          // the fake Kite has no price stream: never open its socket
        com.optionslab.app.data.OrderFlowLive.testOff = true     // and the order flow never lays out its instruments by itself
        FakeAndroidKeyStore.install()
        FakeAndroidKeyStore.reset()
        SecurePrefs.init(this)
        SecurePrefs.wipe()                 // clears the cache left by the previous test
        Ledger.init(this)
        Alarms.init(this)
        Store.init(this)
        Market.init(this)
        Holidays.init(this)
        Holidays.resetForTest()            // no background holiday refresh or test list left from the previous test
        com.optionslab.app.data.McxMarket.init(this)
        Broker.init(this)
        StaticIp.init(this)
        Paper.init(this)
        History.init(this)
        Strategies.init(this)
        com.optionslab.app.data.ArmLive.init(this)
        OrbArms.init(this)
        ShadowArms.init(this)
        com.optionslab.app.data.McxPaperArms.init(this)
        com.optionslab.app.data.VixDivArm.init(this)
        com.optionslab.app.data.HeroTicketArm.init(this)
        // 15 Oct: the Hero ₹1,000 ticket's one-time switch-on stays out of every other test (its own tests turn it back on).
        com.optionslab.app.data.HeroTicketArm.testNoAutoOn = true
        // 21 Oct: FT3's one-time switch-on too (its own tests turn it back on).
        com.optionslab.app.data.Ft3Arm.init(this)
        com.optionslab.app.data.Ft3Arm.testNoAutoOn = true
        com.optionslab.app.data.PassedArmsHost.init(this)
        com.optionslab.app.data.PassedArmsHost.testNoAutoOn = true
        com.optionslab.app.data.WatchlistHost.init(this)
        com.optionslab.app.data.NtfyArm.init(this)
        com.optionslab.app.data.StockOptions.init(this)
        com.optionslab.app.data.GrammarArm.init(this)
        com.optionslab.app.data.MarketRecorder.init(this)
        PineAuto.init(this)
        Protections.init(this)
        TradeBook.init(this)
        com.optionslab.app.data.FillSlippage.init(this)
        com.optionslab.app.data.FillSlippage.resetForTest()
        com.optionslab.app.data.LiquidityTest.init(this)
        com.optionslab.app.data.LiquidityTest.resetForTest()
        // Liquidity BANKNIFTY's paper-test freeze ends on a fixed date: unfrozen here so no test depends on the real date (the freeze
        // has its own tests, which set it).
        com.optionslab.app.data.LiquidityTest.setStateForTest(com.optionslab.ira.LiquidityFreeze.State(unfrozen = true))
        // 18 Oct: no paper-only period, read (a fresh phone whose start read it); the period's own tests set or unread it.
        com.optionslab.app.data.PaperOnly.resetForTest()
        com.optionslab.app.data.ZerodhaSync.resetForTest()
        com.optionslab.app.data.OrderReviewHost.init(this)
        com.optionslab.app.data.OrderReviewHost.resetForTest()
        Journal.init(this)
        com.optionslab.app.data.Diag.init(this)
        com.optionslab.app.data.FlowGate.init(this)
        com.optionslab.app.data.SmartWorkers.init(this)
        // The smart workers (10 Oct): no finding, health sample, brain or setting left from an earlier test.
        com.optionslab.app.data.SmartWorkers.resetForTest()
        com.optionslab.app.data.FlowGate.resetForTest()
        // The app's own health and Jarvis's growth (11 Oct): nothing kept from an earlier test, normal cadences.
        com.optionslab.app.data.AppHealth.init(this)
        com.optionslab.app.data.AppHealth.resetForTest()
        com.optionslab.app.ira.SelfGrowth.init(this)
        com.optionslab.app.ira.SelfGrowth.resetForTest()
        // Reset before init too (13 Oct): init's background read of Boss's modes then finds them read and writes nothing - it
        // raced the reset and could leave "trademanager.policy.perarm" in a test's settings (VaultTest).
        com.optionslab.app.data.TradeManagerHost.resetForTest()
        com.optionslab.app.data.TradeManagerHost.init(this)
        com.optionslab.app.data.TradeManagerHost.resetForTest()
        // 25 Oct: the time machine (22 Oct): the previous test's checks finished (they run on its own thread and would outlive the
        // test), nothing kept, and its bars from memory - an empty phone, so an arm's entry here never reads the bundled
        // sessions (tens of MB per entry); TimeMachineHostTest reads the real store. Its forward log is this test's own file.
        com.optionslab.app.data.TimeMachineHost.resetForTest()
        com.optionslab.app.data.TimeMachineHost.init(this)
        com.optionslab.app.data.TimeMachineHost.bars = com.optionslab.app.data.TimeMachineHost.Bars { u, _, _ ->
            emptyList<com.optionslab.engine.risk.TimeMachine.Session>() to com.optionslab.app.data.TimeMachineHost.noSessions(u) }
        com.optionslab.app.data.OrderVerdicts.resetForTest()
        // The watch's workers (10 Oct): no claim, timing, shared state or busy mark left from an earlier test.
        com.optionslab.app.data.AutoExposure.resetForTest()
        com.optionslab.app.data.AppState.resetForTest()
        com.optionslab.app.work.WatchWorkers.resetForTest()
        com.optionslab.app.work.TradingBusy.resetForTest()
        com.optionslab.app.data.OrderFlowLive.init(this)
        com.optionslab.app.data.OrderFlowLive.resetForTest()
        com.optionslab.app.ira.IraHub.init(this)
        com.optionslab.app.ira.IraHub.awaitLoadedBlocking()   // its memory is read off the main thread: every test starts with it in
        // Ira never reaches the network in tests: no live candles, empty news feeds (a test sets its own).
        com.optionslab.app.ira.IraHub.testLive = { emptyList() }
        com.optionslab.app.ira.IraHub.testFeed = { "" }
        // Caches from an earlier test (the files behind them are already gone with its directories).
        Paper.loadInlineForTest = false; Paper.wipe(); Strategies.wipe(); OrbArms.wipe(); ShadowArms.wipe(); PineAuto.wipe(); Protections.wipe(); TradeBook.wipe(); Journal.wipe(); com.optionslab.app.data.Diag.wipe()
        com.optionslab.app.data.MarketRecorder.wipe()
        com.optionslab.app.data.McxMarket.wipe()
        com.optionslab.app.data.McxPaperArms.wipe(); com.optionslab.app.data.McxPaperArms.testDaily = null
        com.optionslab.app.data.LossBreaker.testPaperDay = null
        com.optionslab.app.data.VixDivArm.wipe()
        com.optionslab.app.data.HeroTicketArm.wipe()
        com.optionslab.app.data.Ft3Arm.wipe()
        // 15 Oct: the earlier sessions' history is empty unless a test gives its own - the watch's Watchlist step starts its
        // background reads (JobsTest's "a test reached the internet: api.upstox.com" on main came from them).
        com.optionslab.app.data.PassedArmsHost.wipe(); com.optionslab.app.data.PassedArmsHost.testHistory = { emptyList() }
        com.optionslab.app.data.ArmLive.wipe(); com.optionslab.app.data.ArmLive.testNow = null; com.optionslab.app.data.ArmLive.testSync = false; com.optionslab.app.data.ArmLive.testHeard = null
        com.optionslab.app.data.ArmLive.testDefaultOff = true
        com.optionslab.app.data.PassedArmsHost.testDaily = { emptyList() }
        com.optionslab.app.data.WatchlistHost.resetForTest()
        com.optionslab.app.data.NtfyArm.wipe()
        com.optionslab.app.data.NtfyReader.wipe()
        // 26 Oct: the quick pass off in the tests (they drive the pass themselves); a test that checks it turns it on.
        com.optionslab.app.data.NtfyArm.testNoKick = true
        com.optionslab.app.data.StockOptions.wipe()
        com.optionslab.app.data.GrammarArm.wipe()
        com.optionslab.app.data.UsCues.testFetch = null; com.optionslab.app.data.UsCues.resetForTest()
        com.optionslab.app.work.PositionCards.forgetPostedForTest()
        com.optionslab.app.widget.IraWidget.forgetDrawnForTest()
        // The event-driven checks and the order-speed figures of an earlier test (in memory only).
        com.optionslab.app.data.FastPath.resetForTest()
        com.optionslab.app.data.OrderTiming.forget()
        // Round 2: the order path's in-memory session and kept reads, and the stream's local candles, of an earlier test.
        com.optionslab.app.data.Broker.resetForTest()
        // The stream's ticks too: Broker.quotes answers from one up to 5 s old, so an earlier test's price would be this one's.
        com.optionslab.app.data.KiteStream.resetForTest()
        // The Live check's last run and the watch's check stamps of an earlier test (memory only).
        com.optionslab.app.data.LiveSelfTest.resetForTest()
        // A Live check a screen starts sees a closed market (no stream to wait on, no feed to read): the hour CI runs at never matters.
        com.optionslab.app.data.LiveSelfTest.sourcesForTest = FakeLiveSources(open = false)
        com.optionslab.app.data.WatchClock.resetForTest()
    }
}
