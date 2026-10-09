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
        // Screen tests place paper orders at whatever hour CI runs; PaperMarketHoursTest turns this off.
        Market.testOrdersAnyTime = true
        // The fake candle feeds are a few fixed minutes of the morning: the 08 Oct feed checks (a stale price never fills an
        // entry, Pine's thin-option check and its best price from the minute highs) are off unless a test turns them on.
        Paper.testSkipFeedChecks = true
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
        com.optionslab.app.data.McxMarket.init(this)
        Broker.init(this)
        StaticIp.init(this)
        Paper.init(this)
        History.init(this)
        Strategies.init(this)
        OrbArms.init(this)
        ShadowArms.init(this)
        com.optionslab.app.data.McxPaperArms.init(this)
        com.optionslab.app.data.VixDivArm.init(this)
        com.optionslab.app.data.MarketRecorder.init(this)
        PineAuto.init(this)
        Protections.init(this)
        TradeBook.init(this)
        Journal.init(this)
        com.optionslab.app.data.Diag.init(this)
        com.optionslab.app.data.FlowGate.init(this)
        com.optionslab.app.data.FlowGate.resetForTest()
        com.optionslab.app.data.OrderFlowLive.init(this)
        com.optionslab.app.data.OrderFlowLive.resetForTest()
        com.optionslab.app.ira.IraHub.init(this)
        com.optionslab.app.ira.IraHub.awaitLoadedBlocking()   // its memory is read off the main thread: every test starts with it in
        // Ira never reaches the network in tests: no live candles, empty news feeds (a test sets its own).
        com.optionslab.app.ira.IraHub.testLive = { emptyList() }
        com.optionslab.app.ira.IraHub.testFeed = { "" }
        // Caches from an earlier test (the files behind them are already gone with its directories).
        Paper.wipe(); Strategies.wipe(); OrbArms.wipe(); ShadowArms.wipe(); PineAuto.wipe(); Protections.wipe(); TradeBook.wipe(); Journal.wipe(); com.optionslab.app.data.Diag.wipe()
        com.optionslab.app.data.MarketRecorder.wipe()
        com.optionslab.app.data.McxMarket.wipe()
        com.optionslab.app.data.McxPaperArms.wipe(); com.optionslab.app.data.McxPaperArms.testDaily = null
        com.optionslab.app.data.VixDivArm.wipe()
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
    }
}
