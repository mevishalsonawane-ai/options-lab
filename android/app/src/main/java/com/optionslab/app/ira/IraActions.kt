package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.ira.Command
import com.optionslab.ira.Commands
import com.optionslab.engine.strategy.RunMode
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.first

/**
 * What Jarvis does when asked, through the app's own functions - the same ones its buttons call - so every limit the
 * app has still applies (the account guard, the kill switch for new live entries, lots and value caps). The owner's
 * rule (2026-10-02): what adds risk runs at once, what stops or closes waits for one tap ([Command.Kind.reduces]).
 * Every action is logged in the app's diagnostics as Jarvis's. Nothing here runs unless the owner asked.
 */
internal object IraActions {
    /** One thing Jarvis can act on, as numbered in its lists. */
    data class Target(val name: String, val run: suspend () -> String)

    /** The app's screen model while the app is open: the Live order paths (order review and send) need it. */
    @Volatile private var bridge: WeakReference<com.optionslab.app.ui.AppModel>? = null
    fun attach(model: com.optionslab.app.ui.AppModel) { bridge = WeakReference(model) }
    private fun model() = bridge?.get()

    private fun ctx(): Context? = IraHub.appContext()
    private fun compromised(): Boolean = ctx()?.let { runCatching {
        com.optionslab.app.security.Integrity.compromised(com.optionslab.app.security.Integrity.reportForSend(it)) }.getOrDefault(false) } ?: false
    private fun log(what: String) = runCatching { com.optionslab.app.data.Diag.record("jarvis", what) }

    // ---- what can be acted on, in the order Jarvis lists it ---------------------------------------------------------

    /** Every strategy and arm: the strategy module, then the Pine arms, then the ORB arms (as in "my strategies"). */
    suspend fun arms(): List<Pair<String, Pair<suspend () -> String, suspend () -> String>>> {
        val out = ArrayList<Pair<String, Pair<suspend () -> String, suspend () -> String>>>()
        val s = AppSettings.load()
        val mode = if (s.live) RunMode.LIVE else RunMode.SANDBOX
        runCatching { com.optionslab.app.data.Strategies.all() }.getOrDefault(emptyList()).forEach { e ->
            val id = e.def.id
            out += e.def.name to (suspend {
                if (s.live && !s.allowRealOrders) "Live mode has real orders switched off: switch it on at the top first."
                else com.optionslab.app.data.Strategies.setArmed(id, true, mode, automatic = true) ?: "Started ${e.def.name} (${mode.name.lowercase()}, automatic)."
            } to suspend {
                val a = com.optionslab.app.data.Strategies.setArmed(id, false, mode, automatic = true)
                val b = if (e.running) com.optionslab.app.data.Strategies.stop(id, "Jarvis", compromised()) else null
                listOfNotNull(a, b).joinToString(" ").ifBlank { "Stopped ${e.def.name}." }
            })
        }
        com.optionslab.app.data.PineScripts.items.value.filter { it.auto.on || com.optionslab.app.data.PineAuto.todayOf(it.id) != null }.forEach { x ->
            out += x.name to (suspend { com.optionslab.app.data.PineAuto.arm(x.id, true, pinConfirmed = true).let { r -> if (r == "ok") "Started ${x.name}." else r } } to
                suspend { com.optionslab.app.data.PineAuto.arm(x.id, false).let { r -> if (r == "ok") "Stopped ${x.name} (what it held is sold)." else r } })
        }
        runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).forEach { a ->
            val src = a.arm.source
            out += a.arm.label to (suspend {
                val r = com.optionslab.app.data.OrbArms.setArmed(src, true, automatic = true, pinConfirmed = true)
                ctx()?.let { runCatching { com.optionslab.app.work.Jobs.ensureWatch(it) } }
                r
            } to suspend { com.optionslab.app.data.OrbArms.setArmed(src, false, a.automatic) })
        }
        return out
    }

    /** Open orders: the paper account's, then Zerodha's (when logged in). */
    private suspend fun openOrders(): List<Target> {
        val out = ArrayList<Target>()
        runCatching { com.optionslab.app.data.Paper.snapshot() }.getOrNull()?.orders?.orders
            ?.filter { com.optionslab.ira.AppFacts.isOpen(it.status) }?.forEach { o ->
                out += Target("paper ${o.action} ${o.quantity} ${o.symbol}") { com.optionslab.app.data.Paper.cancel(o.orderId).message }
            }
        if (Broker.loggedIn) runCatching { Broker.orders() }.getOrNull()?.filter { it.working }?.forEach { o ->
            out += Target("Zerodha ${o.side} ${o.qty} ${o.symbol}") { Broker.cancel(o.id, o.variety); "Cancelled at Zerodha: ${o.side} ${o.qty} ${o.symbol}." }
        }
        return out
    }

    /** Open positions: the paper account's, then Zerodha's (closing a Zerodha one needs the app open). */
    private suspend fun openPositions(): List<Target> {
        val out = ArrayList<Target>()
        runCatching { com.optionslab.app.data.Paper.snapshot() }.getOrNull()?.positions?.positions?.filter { it.quantity != 0 }?.forEach { p ->
            out += Target("paper ${p.symbol} (${p.quantity})") {
                com.optionslab.app.data.Paper.close(p.symbol, p.product).also { r ->
                    r.orderId?.let { id -> com.optionslab.app.data.Strategies.tagOwner("paper:$id", com.optionslab.app.data.Origins.manual("Jarvis")) }
                }.message
            }
        }
        if (Broker.loggedIn) runCatching { Broker.positionBook().net }.getOrNull()?.filter { it.open }?.forEach { p ->
            out += Target("Zerodha ${p.symbol} (${p.qty})") { liveClose(listOf(p)) }
        }
        return out
    }

    /** Zerodha exits go through the app's own square-off (the order review's checks), sent without the swipe or PIN. */
    private suspend fun liveClose(ps: List<Broker.Position>): String {
        val m = model() ?: return "Closing a Zerodha position needs JarvisAlgo open: open it and ask again."
        if (ps.size == 1) m.planSquareOff(ps.single(), "Jarvis") else m.planSquareOffAll()
        return sendPlan(m)
    }

    /** Waits for the order review the app prepared, then sends it as the swipe would; the app's refusals stand. */
    private suspend fun sendPlan(m: com.optionslab.app.ui.AppModel): String {
        val plan = kotlinx.coroutines.withTimeoutOrNull(30_000) {
            m.plan.first { it is com.optionslab.app.ui.Load.Done || it is com.optionslab.app.ui.Load.Failed }
        } ?: return "Zerodha did not answer in time; nothing was sent."
        if (plan is com.optionslab.app.ui.Load.Failed) return "Not sent: ${plan.why}"
        val p = (plan as com.optionslab.app.ui.Load.Done<com.optionslab.app.ui.OrderPlan>).value
        if (!p.sendable) { val why = p.refusals.flatten().joinToString(" ").ifBlank { "the app's checks refused it" }; m.dismissPlan(); return "Not sent: $why" }
        m.sendPlan()
        val sent = kotlinx.coroutines.withTimeoutOrNull(60_000) {
            m.sending.first { it is com.optionslab.app.ui.Load.Done || it is com.optionslab.app.ui.Load.Failed }
        }
        return when (sent) {
            is com.optionslab.app.ui.Load.Done -> "Sent to Zerodha: ${p.title}. " + sent.value.joinToString("; ") { "${it.status} ${it.filled} at ${"%.2f".format(it.avgPrice)}" }
            is com.optionslab.app.ui.Load.Failed -> "Zerodha refused: ${sent.why}"
            else -> "Sent; Zerodha has not confirmed yet - see Trade, then Account."
        }
    }

    // ---- doing it ---------------------------------------------------------------------------------------------------

    /** What [c] would do, in words, and the action itself - or why it cannot be done (the action is then null). */
    suspend fun prepare(c: Command): Pair<String, (suspend () -> String)?> {
        fun pick(names: List<String>, what: String): Int? = Commands.pick(c, names)
        return when (c.kind) {
            Command.Kind.STOP_ALL -> Commands.describe(c) to suspend { com.optionslab.app.data.Strategies.stopForToday(true, compromised()) }
            Command.Kind.START_ALL -> Commands.describe(c) to suspend { com.optionslab.app.data.Strategies.startAgain() }
            Command.Kind.STOP_ONE, Command.Kind.START_ONE -> {
                val all = arms()
                if (all.isEmpty()) return "There are no strategies or arms to ${if (c.kind == Command.Kind.START_ONE) "start" else "stop"}." to null
                val i = pick(all.map { it.first }, "strategy") ?: return ("Which one? " + all.mapIndexed { n, a -> "${n + 1}. ${a.first}" }.joinToString("; ") +
                    ". Say its number, like \"${if (c.kind == Command.Kind.START_ONE) "start" else "stop"} strategy 1\".") to null
                val (name, act) = all[i]
                Commands.describe(c, name) to (if (c.kind == Command.Kind.START_ONE) act.first else act.second)
            }
            Command.Kind.CANCEL_ALL -> {
                val o = openOrders()
                if (o.isEmpty()) "There are no open orders to cancel." to null
                else "cancel ${o.size} open order${if (o.size > 1) "s" else ""}" to suspend { o.map { runCatching { it.run() }.getOrElse { e -> "Failed: ${e.message}" } }.joinToString(" ") }
            }
            Command.Kind.CANCEL_ONE -> {
                val o = openOrders()
                if (o.isEmpty()) return "There are no open orders to cancel." to null
                val i = if (c.target == "last") o.lastIndex else pick(o.map { it.name }, "order")
                    ?: return ("Which order? " + o.mapIndexed { n, x -> "${n + 1}. ${x.name}" }.joinToString("; ") + ".") to null
                Commands.describe(c, o[i].name) to o[i].run
            }
            Command.Kind.CLOSE_ALL -> {
                val paper = openPositions().filter { it.name.startsWith("paper") }
                val live = if (Broker.loggedIn) runCatching { Broker.positionBook().net.filter { it.open } }.getOrDefault(emptyList()) else emptyList()
                if (paper.isEmpty() && live.isEmpty()) "There are no open positions to close." to null
                else "close ${paper.size + live.size} open position${if (paper.size + live.size > 1) "s" else ""}" to suspend {
                    (paper.map { runCatching { it.run() }.getOrElse { e -> "Failed: ${e.message}" } } + (if (live.isNotEmpty()) listOf(liveClose(live)) else emptyList())).joinToString(" ")
                }
            }
            Command.Kind.CLOSE_ONE -> {
                val p = openPositions()
                if (p.isEmpty()) return "There are no open positions to close." to null
                val i = pick(p.map { it.name }, "position") ?: return ("Which position? " + p.mapIndexed { n, x -> "${n + 1}. ${x.name}" }.joinToString("; ") + ".") to null
                Commands.describe(c, p[i].name) to p[i].run
            }
            Command.Kind.KILL_ON, Command.Kind.KILL_OFF -> Commands.describe(c) to suspend {
                setSettings { it.copy(guardKill = c.kind == Command.Kind.KILL_ON) }
                if (c.kind == Command.Kind.KILL_ON) "Kill switch on: no new live positions; exits still go." else "Kill switch off."
            }
            Command.Kind.MODE_PAPER -> Commands.describe(c) to suspend { setSettings { it.copy(mode = "sandbox", allowRealOrders = false) }; "Paper mode: orders now go to the paper account." }
            Command.Kind.MODE_LIVE -> if (!Broker.linked) "Zerodha is not set up yet: More, then Zerodha." to null
                else Commands.describe(c) to suspend { setSettings { it.copy(mode = "live", allowRealOrders = true) }; "Live mode: orders now go to Zerodha." }
            Command.Kind.ALARM_ADD -> {
                val m = c.market; val lvl = c.level; val above = c.above
                if (m == null || lvl == null || above == null) return "Tell me the index, above or below, and the level: \"alert me when Nifty goes above 25000\"." to null
                val sym = when (m) {
                    com.optionslab.ira.Market.NIFTY -> "NIFTY"; com.optionslab.ira.Market.BANKNIFTY -> "BANKNIFTY"; com.optionslab.ira.Market.VIX -> "INDIAVIX"
                    com.optionslab.ira.Market.FINNIFTY -> com.optionslab.app.data.PriceAlarm.CHART + "FINNIFTY"
                    com.optionslab.ira.Market.SENSEX -> com.optionslab.app.data.PriceAlarm.CHART + "SENSEX"
                    com.optionslab.ira.Market.GOLD -> return "Gold alarms are in IraGoldAlgo." to null
                }
                Commands.describe(c) to suspend {
                    com.optionslab.app.data.Alarms.upsert(com.optionslab.app.data.PriceAlarm(System.currentTimeMillis(), sym, above, lvl, note = "set by Jarvis"))
                    model()?.refreshAlarms()
                    "Alarm set: ${m.label} ${if (above) "above" else "below"} ${"%,.2f".format(java.util.Locale.ENGLISH, lvl)}."
                }
            }
            Command.Kind.JTRADES_RISK -> {
                val v = c.level
                if (v != null && v < 500) "Tell me the risk in rupees, at least 500, or say risk off." to null
                else Commands.describe(c) to suspend {
                    IraNewsTrades.riskPerTrade = v
                    if (v == null) "My trades take 1 lot again."
                    else "Each of my trades will risk about Rs %,.0f once my paper record is proven; until then 1 lot.".format(java.util.Locale.ENGLISH, v)
                }
            }
            Command.Kind.JTRADES_PAPER -> Commands.describe(c) to suspend { IraNewsTrades.paperFirst = true; "My suggested trades stay on paper now, Boss." }
            Command.Kind.JTRADES_LIVE -> {
                val why = com.optionslab.ira.JarvisTrades.proven(IraNewsTrades.closedRecord())
                if (why != null) "$why I'll tell you when they have earned it." to null
                else Commands.describe(c) to suspend { IraNewsTrades.paperFirst = false; "My suggested trades now follow the app's mode: real Zerodha orders in Live, after your yes each time." }
            }
            Command.Kind.JTRADES_LIMIT -> {
                val v = c.level?.takeIf { it >= 500 } ?: return "Tell me the limit in rupees, at least 500." to null
                Commands.describe(c) to suspend { IraNewsTrades.dailyLimit = v; "My trades now stop for the day after losing Rs %,.0f.".format(java.util.Locale.ENGLISH, v) }
            }
            Command.Kind.AUTOPILOT_ON, Command.Kind.AUTOPILOT_OFF -> Commands.describe(c) to suspend {
                IraHub.autopilot = c.kind == Command.Kind.AUTOPILOT_ON
                if (IraHub.autopilot) "Autopilot on, Boss: strategies that pass two years of testing are added on paper by themselves, and ones I added that stop working are retired. Live still follows your mode switch."
                else "Autopilot off: I will ask before adding a strategy."
            }
            Command.Kind.EVENT_ADD -> {
                val d = c.day; val n = c.target
                if (d == null || n.isNullOrBlank()) "Tell me the event and the day: \"add event RBI policy on 5 Dec\"." to null
                else Commands.describe(c) to suspend { IraEvents.add(d, n); "Noted, Boss: ${n} on $d. I will remind you in the morning check." }
            }
            Command.Kind.EVENT_REMOVE -> {
                val e = IraEvents.owner()
                if (e.isEmpty()) return "You have not added any events." to null
                val i = c.number?.let { (it - 1).takeIf { x -> x in e.indices } } ?: Commands.pick(c, e.map { it.name })
                    ?: return ("Which event? " + e.mapIndexed { n, x -> "${n + 1}. ${x.name} on ${x.day}" }.joinToString("; ") + ".") to null
                Commands.describe(c, "the event ${e[i].name} on ${e[i].day}") to suspend { IraEvents.remove(e[i]); "Event removed." }
            }
            Command.Kind.ALARM_REMOVE -> {
                val a = com.optionslab.app.data.Alarms.all()
                if (a.isEmpty()) return "There are no alarms to remove." to null
                val i = if (c.target == "last") a.lastIndex else c.number?.let { (it - 1).takeIf { x -> x in a.indices } }
                    ?: return ("Which alarm? " + a.mapIndexed { n, x -> "${n + 1}. ${x.describe()}" }.joinToString("; ") + ".") to null
                Commands.describe(c, "the alarm ${a[i].describe()}") to suspend { com.optionslab.app.data.Alarms.remove(a[i].id); model()?.refreshAlarms(); "Alarm removed." }
            }
        }
    }

    /** Runs a prepared action, logged as Jarvis's; never throws. */
    suspend fun run(what: String, act: suspend () -> String): String {
        log(what)
        IraAccount.invalidate()
        return runCatching { act() }.getOrElse { "That did not work: ${it.message ?: "an error"}." }
    }

    private fun setSettings(f: (AppSettings) -> AppSettings) {
        val m = model()
        if (m != null) m.update(f) else AppSettings.save(f(AppSettings.load()))
    }

    // ---- trades -------------------------------------------------------------------------------------------------------

    /**
     * A trade Jarvis was asked for, done at once (the owner's rule): Paper places a market order on the paper account;
     * Live goes through the app's order review and its checks, sent without the swipe or PIN (the app must be open).
     */
    suspend fun trade(t: IraOrders.Ticket, live: Boolean): String {
        IraAccount.invalidate()
        log("trade ${t.title} ${if (live) "LIVE" else "paper"}")
        if (!live) {
            val c = com.optionslab.app.data.Paper.contractFor(t.underlying, t.expiry, t.strike, t.right) ?: return "${t.title} is not listed."
            val q = runCatching { com.optionslab.app.data.Paper.quote(c) }.getOrNull()
            val r = com.optionslab.app.data.Paper.place(c, if (t.buy) "BUY" else "SELL", t.lots, "MARKET", AppSettings.load().orderProduct, null, null, q)
            val source = com.optionslab.app.data.Origins.manual("Jarvis")
            r.orderId?.let { com.optionslab.app.data.Strategies.tagOwner("paper:$it", source) }
            ctx()?.let { c2 -> r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().forEach {
                com.optionslab.app.work.Notifier.orderFilled(c2, it.action, it.quantity, it.symbol, it.price, "Paper", source) } }
            model()?.loadPaper(quiet = true)
            return "Paper: ${r.message}"
        }
        val m = model() ?: return "A Zerodha order needs JarvisAlgo open: open it and ask again."
        m.planManual(t.underlying, t.expiry, t.strike, t.right, if (t.buy) com.optionslab.engine.Kite.Side.BUY else com.optionslab.engine.Kite.Side.SELL,
            t.lots, AppSettings.load().orderProduct, null, null, "Jarvis")
        return sendPlan(m)
    }
}
