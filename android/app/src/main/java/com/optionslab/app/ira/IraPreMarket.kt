package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Market
import com.optionslab.app.data.OrbArms
import com.optionslab.app.data.Strategies
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.PreMarket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pre-market checklist ([PreMarket], usefulness round 12): "am I ready to trade?", "pre-market checklist" - the app's
 * own readiness and Boss's setup, each pass or fail with the fix said as a step he takes himself. The same checks extend
 * the 09:00 morning check (DailyReports.morning). It reads only: nothing here places, changes, closes or arms anything.
 * Boss's account (margin against his usual size, positions carried overnight) only on an unlocked phone.
 */
internal object IraPreMarket {
    /** The bots armed on each recent day (names only, no amounts): his usual, learned from what he arms himself. */
    private const val ARMED = "jarvis.premarket.armed"
    private const val ZERODHA_MS = 8_000L

    /** The bots armed now: the ORB arms, the strategies on their schedule and the Pine scripts switched on (names only). */
    suspend fun armedNow(): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching { OrbArms.view().arms.filter { it.armed }.forEach { out += it.arm.label } }
        runCatching { Strategies.all().filter { it.def.scheduler?.enabled == true }.forEach { out += it.def.name } }
        runCatching { com.optionslab.app.data.PineScripts.items.value.filter { it.auto.on }.forEach { out += it.name } }
        return out
    }

    /** What is armed today, added to the record his usual is read from (bot names only). */
    fun remember(armed: Set<String>) {
        runCatching { SecurePrefs.put(ARMED, PreMarket.record(SecurePrefs.getString(ARMED), Market.today(), armed)) }
    }

    /** The relay server answers: connected already, or a plain TCP knock on its SSH port (5 s at most; logs in to nothing). */
    private suspend fun relayAnswers(): Boolean? {
        val r = com.optionslab.app.data.Relay
        val host = r.host
        if (!r.enabled || host == null) return null
        if (r.connected) return true
        return withContext(Dispatchers.IO) {
            runCatching { java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(host, 22), 5_000); true } }.getOrDefault(false)
        }
    }

    /** The app and Boss's setup now. [locked]: his own calendar entries are left out. */
    suspend fun setup(context: Context?, locked: Boolean): PreMarket.Setup {
        val s = AppSettings.load()
        val staticIp = com.optionslab.app.data.StaticIp.registered?.let {
            runCatching { com.optionslab.app.data.StaticIp.status(force = true).matches }.getOrDefault(false)
        }
        val ira = IraHub.state.value
        val mic = context != null && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val armed = armedNow()
        remember(armed)
        val today = Market.today()
        val events = runCatching { IraEvents.upcoming(0) }.getOrDefault(emptyList())
            .filter { it.day == today && !(locked && it.owner) }.map { it.name }
        return PreMarket.Setup(
            configured = Broker.configured, loggedIn = Broker.loggedIn,
            staticIp = staticIp, relay = runCatching { relayAnswers() }.getOrNull(),
            pricesMissing = ira.liveMissing.map { it.label },
            modelReady = IraModel.state.value.status == IraModel.Status.READY,
            voiceWanted = JarvisVoice.wanted, voiceProblem = JarvisVoice.state.value.problem, mic = mic,
            kill = s.guardKill, live = s.live,
            dailyLoss = if (s.live) s.guardDailyLoss else s.guardPaperDailyLoss,
            maxTrades = if (s.live) s.guardMaxTrades else s.guardPaperTrades,
            armed = armed, usual = PreMarket.usualArmed(PreMarket.decode(SecurePrefs.getString(ARMED)), today),
            today = events,
        )
    }

    /** Boss's account: Zerodha's margin against his usual position size, and what is carried overnight (unlocked phone only). */
    suspend fun account(): PreMarket.Account {
        val sizes = runCatching { com.optionslab.app.data.TradeBook.trips(true).sortedBy { it.openedAt }.map { it.entry * it.qty } }.getOrDefault(emptyList())
        val available = if (Broker.loggedIn) Broker.within(ZERODHA_MS) { Broker.funds().available } else null
        val live = if (Broker.loggedIn) Broker.within(ZERODHA_MS) { Broker.positionBook().net.filter { it.open } }.orEmpty() else emptyList()
        val paper = runCatching { com.optionslab.app.data.Paper.state.positions.filter { it.quantity != 0 } }.getOrDefault(emptyList())
        val carried = live.map { "${it.symbol}, ${kotlin.math.abs(it.qty)} ${if (it.qty > 0) "long" else "short"} on Zerodha" } +
            paper.map { "${it.symbol}, ${kotlin.math.abs(it.quantity)} ${if (it.quantity > 0) "long" else "short"} on paper" }
        return PreMarket.Account(available, PreMarket.usualSize(sizes), carried)
    }

    /**
     * "What do I need to do before tomorrow?" ([com.optionslab.ira.BeforeTomorrow], usefulness round 23): the Zerodha
     * login, his legs expiring on the next trading day (IraCoach.expiryEveRead, saying when Zerodha wasn't read), the arms
     * armed now with their paper records, the static IP and the relay, the battery setting and the backup's age - one
     * checklist, facts only. Reads only: nothing is placed, changed, closed, armed or disarmed. On a locked phone his legs
     * and the records are not read at all.
     */
    suspend fun beforeTomorrow(context: Context?, locked: Boolean): String {
        val s = AppSettings.load()
        val today = Market.today()
        val next = com.optionslab.ira.ExpiryEve.nextTradingDay(today) { Market.isTradingDay(it) }
        val eve = if (locked) null else runCatching { IraCoach.expiryEveRead() }.getOrNull()
        val armed = runCatching { armedNow() }.getOrDefault(emptySet()).toList()
        val records = if (locked) null else runCatching {
            IraBots.bots().filter { it.where == "Paper" && it.name in armed }.associate { b ->
                val st = com.optionslab.ira.BotHealth.stats(b.trades)
                b.name to com.optionslab.ira.BeforeTomorrow.Record(st.trades, st.wins, st.net, b.tested?.winRate)
            }
        }.getOrNull()
        val staticIp = com.optionslab.app.data.StaticIp.registered?.let {
            runCatching { com.optionslab.app.data.StaticIp.status(force = true).matches }.getOrDefault(false)
        }
        val battery = context?.let { c -> runCatching { com.optionslab.app.ui.screens.BatteryCheck.unrestricted(c) }.getOrNull() }
        val lastBackup = runCatching { SecurePrefs.getString("backup.last")?.let(java.time.LocalDate::parse) }.getOrNull()
        val facts = com.optionslab.ira.BeforeTomorrow.Facts(
            today = today, next = next, configured = Broker.configured, live = s.live,
            expiring = eve?.legs, loggedIn = eve?.loggedIn ?: Broker.loggedIn, zerodhaRead = eve?.zerodhaRead ?: false,
            armed = armed, records = records,
            staticIp = staticIp, relay = runCatching { relayAnswers() }.getOrNull(), battery = battery,
            lastBackup = lastBackup,
        )
        return com.optionslab.ira.BeforeTomorrow.say(facts, locked)
    }

    /** "Am I ready to trade?": the checklist in words. Reads only. */
    suspend fun answer(context: Context?, locked: Boolean): String {
        val setup = setup(context, locked)
        val acct = if (locked) null else runCatching { account() }.getOrNull()
        return PreMarket.say(PreMarket.checks(setup, acct, locked), locked)
    }
}
