package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.ira.Command
import com.optionslab.ira.SettingsTalk
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
    /** Closes Jarvis's own Zerodha position in [symbol] through the app's square-off (the app must be open). */
    suspend fun closeLiveSymbol(symbol: String): String {
        val ps = runCatching { Broker.positionBook().net.filter { it.symbol == symbol && it.qty != 0 } }.getOrNull() ?: return "Zerodha did not answer: close $symbol yourself."
        if (ps.isEmpty()) return "Already closed."
        return liveClose(ps)
    }

    private suspend fun liveClose(ps: List<Broker.Position>): String {
        val m = model() ?: return "Closing a Zerodha position needs IraAlgo open: open it and ask again."
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
            is com.optionslab.app.ui.Load.Done -> "Sent to Zerodha: ${p.title}. " + sent.value.joinToString("; ") { "${it.status} ${it.filled} at ${"%.2f".format(it.avgPrice)}" +
                (com.optionslab.app.data.Origins.shortId(it.orderId)?.let { id -> " (order $id)" } ?: "") }
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
            Command.Kind.START_ALL -> Commands.describe(c) to suspend {
                // Lift today's stop, then switch on every strategy, Pine script and arm the app has.
                val again = runCatching { com.optionslab.app.data.Strategies.startAgain() }.getOrNull()
                val each = arms().map { (name, act) -> runCatching { act.first() }.getOrElse { e -> "$name: ${e.message ?: "failed"}." } }
                (listOfNotNull(again) + each).joinToString(" ").ifBlank { "There are no strategies or arms to start." }
            }
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
                // "Close 1 lot of ...": only a whole position is closed here, never more than Boss asked for.
                if (c.lots != null) return "I can only close a whole position, Boss, not ${c.lots} lot${if (c.lots == 1) "" else "s"} of it. Say \"close\" and its name to close all of it, or trim it on the Positions screen." to null
                val i = pick(p.map { it.name }, "position") ?: return ("Which position? " + p.mapIndexed { n, x -> "${n + 1}. ${x.name}" }.joinToString("; ") + ".") to null
                Commands.describe(c, p[i].name) to p[i].run
            }
            Command.Kind.KILL_ON, Command.Kind.KILL_OFF -> Commands.describe(c) to suspend {
                setSettings { it.copy(guardKill = c.kind == Command.Kind.KILL_ON) }
                if (c.kind == Command.Kind.KILL_ON) "Kill switch on: no new live positions; exits still go." else "Kill switch off."
            }
            Command.Kind.MODE_PAPER -> Commands.describe(c) to suspend { setSettings { it.copy(mode = "sandbox", allowRealOrders = false) }; "Paper mode: orders now go to the paper account." }
            // Real orders are never switched on via Jarvis (Boss's rule): going live is the PAPER / LIVE badge, asked there.
            Command.Kind.MODE_LIVE -> "Going live is yours alone, Boss: tap PAPER TRADING at the top of the app and confirm there. I never switch real orders on." to null
            Command.Kind.ALARM_ADD -> {
                val m = c.market; val above = c.above
                // "Falls 1% from here": the level from the price now.
                if (c.level == null && c.pct != null && m == null) return "Tell me the index: \"tell me if BankNifty falls 1% from here\"." to null
                var base: Double? = null
                val lvl = c.level ?: c.pct?.let { pct ->
                    // A fresh price only (in market hours the last 3 minutes): a level from an old price would be wrong.
                    val st = IraHub.state.value
                    val fresh = !com.optionslab.app.data.Market.isOpen() || st.liveAt?.isAfter(java.time.Instant.now().minusSeconds(180)) == true
                    val px = st.snaps[m!!]?.price?.takeIf { fresh } ?: return "I don't have a fresh ${m.label} price just now to measure $pct% from. Try again in a moment." to null
                    base = px
                    com.optionslab.ira.MoveAlarm.level(px, com.optionslab.ira.MoveAlarm.Move(pct, above == true))
                }
                if (m == null || lvl == null || above == null) return "Tell me the index, above or below, and the level: \"alert me when Nifty goes above 25000\"." to null
                val sym = alarmSymbol(m) ?: return "Gold alarms are in IraGoldAlgo." to null
                Commands.describe(c.copy(level = lvl)) + (base?.let { " (" + c.pct + "% from " + "%,.2f".format(java.util.Locale.ENGLISH, it) + ")" } ?: "") to suspend {
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
            // The app's limits: said back old -> new and confirmed; security settings never change by voice.
            Command.Kind.SET_REFUSED -> "Boss, the PIN, fingerprint, real orders, one-tap orders and the lock change only in Settings, never through me." to null
            Command.Kind.SET_LIMIT -> {
                val key = c.target?.let { runCatching { SettingsTalk.Key.valueOf(it) }.getOrNull() } ?: return "I could not tell which setting." to null
                val v = c.level ?: return "Tell me the new value for ${key.label}." to null
                val old = setting(key, AppSettings.load())
                if (old == v) return "${key.label.replaceFirstChar { it.uppercase() }} is already ${SettingsTalk.show(key, v)}." to null
                val more = if (SettingsTalk.loosens(key, old, v)) " (this allows more risk)" else ""
                SettingsTalk.describe(key, old, v) + more to suspend {
                    // Checked again at Confirm: changed meanwhile (by hand, or another request), nothing is applied.
                    val nowV = setting(key, AppSettings.load())
                    if (nowV != old) "${key.label.replaceFirstChar { it.uppercase() }} changed since you asked (now ${SettingsTalk.show(key, nowV)}), so I left it. Ask again."
                    else {
                        com.optionslab.app.data.SettingsLog.nextBy = "Jarvis"
                        setSettings { applySetting(key, v, it) }
                        "Done: ${key.label} is now ${SettingsTalk.show(key, v)}."
                    }
                }
            }
            Command.Kind.UNDO -> {
                val group = com.optionslab.app.data.SettingsLog.lastUndoable()
                if (group.isEmpty()) return "No limit change is left to undo." to null
                val cur = AppSettings.load()
                group.firstOrNull { setting(it.key, cur) != it.new }?.let { ch ->
                    return "${ch.key.label.replaceFirstChar { it.uppercase() }} has changed again since, so I won't undo it: tell me the value you want." to null
                }
                val more = if (group.any { SettingsTalk.loosens(it.key, it.new, it.old) }) " (this allows more risk)" else ""
                "undo: " + group.joinToString(", ") { SettingsTalk.describe(it.key, it.new, it.old) } + more to suspend {
                    val again = AppSettings.load()
                    if (group.any { setting(it.key, again) != it.new } || com.optionslab.app.data.SettingsLog.lastUndoable() != group)
                        "The limits changed since you asked, so I left them. Ask again."
                    else {
                        com.optionslab.app.data.SettingsLog.nextBy = com.optionslab.app.data.SettingsLog.UNDO_BY
                        setSettings { s -> group.fold(s) { acc, ch -> applySetting(ch.key, ch.old, acc) } }
                        com.optionslab.app.data.SettingsLog.markUndone(group)
                        "Undone: " + group.joinToString(", ") { "${it.key.label} is back to ${SettingsTalk.show(it.key, it.old)}" } + "."
                    }
                }
            }
            Command.Kind.QUIET_ON -> { JarvisVoice.quietHours = true; "Quiet hours on, Boss: from 22:00 to 07:00 I say nothing unless you ask." to null }
            Command.Kind.MISTAKE -> IraTools.markWrong() to null
            // The emergency exit (always confirmed): the kill switch first (nothing new opens), the bots stopped, then
            // every position closed - each step said, none skipped because another failed.
            Command.Kind.EXIT_ALL -> Commands.describe(c) to suspend {
                val out = ArrayList<String>()
                runCatching { setSettings { it.copy(guardKill = true) }; out += "Kill switch on." }.onFailure { e -> out += "Kill switch not set: ${e.message}." }
                out += runCatching { com.optionslab.app.data.Strategies.stopForToday(true, compromised()) }.getOrElse { e -> "Stopping the bots failed: ${e.message}." }
                val (what, act) = runCatching { prepare(Command(Command.Kind.CLOSE_ALL)) }.getOrElse { e -> ("Closing failed: ${e.message}.") to null }
                out += if (act == null) what else runCatching { act() }.getOrElse { e -> "Closing failed: ${e.message}." }
                IraActivity.add("Emergency exit: " + out.joinToString(" "))
                out.joinToString(" ")
            }
            Command.Kind.BRIEF_ON -> { IraTools.brief = true; "Short answers, Boss. Say \"tell me more\" for the rest." to null }
            Command.Kind.BRIEF_OFF -> { IraTools.brief = false; "Full answers again." to null }
            Command.Kind.MORE -> (IraHub.lastFullAnswer() ?: "There is no answer of mine to say more about.") to null
            Command.Kind.PRACTICE -> "Replaying the day..." to null
            Command.Kind.VOICE_CHECK -> JarvisVoice.diagnose(ctx()) to null
            Command.Kind.LEARN_RESET -> { IraTools.forgetLearned(); "Done, Boss: I've forgotten what I learned from your corrections." to null }
            Command.Kind.JTRADES_WEEKLY -> { val v = c.level ?: return "Tell me the limit in rupees." to null
                if (v < 1000) "Tell me a weekly limit of at least Rs 1,000." to null
                else Commands.describe(c) to suspend { IraTools.weeklyLimit = v; "My trades' weekly loss limit is now ${com.optionslab.ira.AppFacts.amt(v)}." } }
            Command.Kind.TARGET_SET -> { val v = c.level ?: return "Tell me the target in rupees." to null
                IraJournal.setTarget(v); IraActivity.add("Set today's target to ${com.optionslab.ira.AppFacts.amt(v)}.")
                "Today's target is ${com.optionslab.ira.AppFacts.amt(v)}, Boss. I'll tell you when you reach it." to null }
            Command.Kind.TARGET_CLEAR -> { IraJournal.setTarget(null); "Today's target is cleared." to null }
            Command.Kind.NOTE -> { val n = c.target ?: return "Tell me the note." to null; IraJournal.note(n) to null }
            Command.Kind.PREF_RESET -> { IraNewsTrades.resetPreferences(); "Done, Boss: I'll offer every kind of suggestion again." to null }
            Command.Kind.QUIET_OFF -> { JarvisVoice.quietHours = false; "Quiet hours off." to null }
            // Jarvis's voice and language: done at once (nothing to confirm, nothing at risk).
            Command.Kind.MUTE -> { JarvisVoice.muted = true; IraActivity.add("Muted my voice."); "Muted, Boss. I'll reply on screen only. Say \"Jarvis, unmute\" or \"Jarvis, speak again\" to hear me." to null }
            Command.Kind.UNMUTE -> { JarvisVoice.muted = false; IraActivity.add("Voice back on."); "Voice on, Boss." to null }
            Command.Kind.HINDI -> { JarvisVoice.hindi = true
                (if (IraModel.state.value.status == IraModel.Status.READY) "Ab main Hindi mein jawab doonga, Boss." +
                    (if (IraModel.choice == IraModel.FASTEST) " (The fastest model's Hindi is weak: some replies may stay in English. The 1.5B model is better at Hindi.)" else "") else
                    "Boss, Hindi replies need the AI model on the phone (Settings, Voice and AI model); until then I reply in English.") to null }
            Command.Kind.ENGLISH -> { JarvisVoice.hindi = false; "Back to English, Boss." to null }
            Command.Kind.PACE_SLOWER -> { JarvisVoice.pace = JarvisVoice.pace - 0.15f; "Slower now, Boss." to null }
            Command.Kind.PACE_FASTER -> { JarvisVoice.pace = JarvisVoice.pace + 0.15f; "Faster now, Boss." to null }
            Command.Kind.PACE_NORMAL -> { JarvisVoice.pace = 1f; "Back to my normal pace, Boss." to null }
            Command.Kind.JTRADES_PAPER -> Commands.describe(c) to suspend { IraNewsTrades.paperFirst = true; "My suggested trades stay on paper now, Boss." }
            Command.Kind.JTRADES_LIVE -> {
                val why = com.optionslab.ira.JarvisTrades.proven(IraNewsTrades.closedRecord())
                // Never by voice (no backdoor to real money): Boss's own switch, with his fingerprint.
                if (why != null) "$why I'll tell you when they have earned it." to null
                else "Real money is yours to switch on, Boss: turn on \"AI trades go live\" in Jarvis settings, What Jarvis does by itself, with your fingerprint." to null
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
                // "Remove the Nifty alarm": that market's alarms only (all of them when one is set, or "all" was said).
                val mk = c.market
                if (mk != null && c.number == null) {
                    val sym = alarmSymbol(mk) ?: return "There are no ${mk.label} alarms here." to null
                    // "Remove the last Nifty alarm": the last of that market's alarms, never another market's.
                    val mine = a.filter { it.symbol == sym }.let { l -> if (c.target == "last") l.takeLast(1) else l }
                    if (mine.isEmpty()) return "There are no ${mk.label} alarms to remove." to null
                    if (mine.size > 1 && c.target != "all")
                        return ("Which ${mk.label} alarm? " + mine.joinToString("; ") { x -> "${a.indexOf(x) + 1}. ${x.describe()}" } + ". Or say \"remove all ${mk.label} alarms\".") to null
                    return Commands.describe(c, if (mine.size == 1) "the alarm ${mine[0].describe()}" else "all ${mine.size} ${mk.label} alarms") to suspend {
                        mine.forEach { com.optionslab.app.data.Alarms.remove(it.id) }; model()?.refreshAlarms()
                        if (mine.size == 1) "Alarm removed." else "${mine.size} ${mk.label} alarms removed."
                    }
                }
                if (c.target == "all") return Commands.describe(c, "all ${a.size} alarms") to suspend {
                    a.forEach { com.optionslab.app.data.Alarms.remove(it.id) }; model()?.refreshAlarms(); "All alarms removed."
                }
                val i = if (c.target == "last") a.lastIndex else c.number?.let { (it - 1).takeIf { x -> x in a.indices } }
                    ?: return ("Which alarm? " + a.mapIndexed { n, x -> "${n + 1}. ${x.describe()}" }.joinToString("; ") + ".") to null
                Commands.describe(c, "the alarm ${a[i].describe()}") to suspend { com.optionslab.app.data.Alarms.remove(a[i].id); model()?.refreshAlarms(); "Alarm removed." }
            }
        }
    }

    /** The alarm symbol a market's alarms are kept under (null for gold: its alarms are in IraGoldAlgo). */
    private fun alarmSymbol(m: com.optionslab.ira.Market): String? = when (m) {
        com.optionslab.ira.Market.NIFTY -> "NIFTY"; com.optionslab.ira.Market.BANKNIFTY -> "BANKNIFTY"; com.optionslab.ira.Market.VIX -> "INDIAVIX"
        com.optionslab.ira.Market.FINNIFTY -> com.optionslab.app.data.PriceAlarm.CHART + "FINNIFTY"
        com.optionslab.ira.Market.SENSEX -> com.optionslab.app.data.PriceAlarm.CHART + "SENSEX"
        com.optionslab.ira.Market.GOLD -> null
    }

    /** Runs a prepared action, logged as Jarvis's; never throws. */
    /**
     * Jarvis checks his own work (part 3): after [k] said it was done, the app is read again and its effect looked for
     * (once more 3 s later, as exits and cancels take a moment); [result] comes back with the check's word.
     */
    suspend fun verified(k: com.optionslab.ira.Command.Kind, result: String): String {
        val need = com.optionslab.ira.Verify.needs(k)
        if (need.isEmpty() || com.optionslab.ira.Plan.failed(result)) return result
        var problem: String? = null
        var note: String? = null
        for (wait in listOf(1_500L, 3_000L)) {
            kotlinx.coroutines.delay(wait)
            val f = runCatching { facts(need) }.getOrNull() ?: continue
            problem = com.optionslab.ira.Verify.problem(k, f)
            note = com.optionslab.ira.Verify.note(k, f)
            if (problem == null && note == null) break
        }
        if (problem != null) IraActivity.add("Checked my own work: $problem.")
        return com.optionslab.ira.Verify.say(result, problem, checked = true) + (note?.let { " $it" } ?: "")
    }

    /** The facts [need] names, read from the app now (one that cannot be read stays null: not judged). */
    private suspend fun facts(need: Set<String>): com.optionslab.ira.Verify.Facts {
        val s = runCatching { AppSettings.load() }.getOrNull()
        val live = s?.live == true
        fun <T> read(name: String, f: () -> T): T? = if (name in need) runCatching(f).getOrNull() else null
        // Both accounts: a close and the emergency exit close paper and Zerodha positions alike.
        val positions = if ("positions" in need) runCatching {
            com.optionslab.app.data.Paper.snapshot().positions.positions.count { it.quantity != 0 } +
                (if (Broker.loggedIn) Broker.positionBook().net.count { it.open } else 0)
        }.getOrNull() else null
        val orders = if ("orders" in need) runCatching {
            if (live) com.optionslab.app.data.Broker.orders().count { it.working }
            else com.optionslab.app.data.Paper.snapshot().orders.orders.count { it.status.lowercase() !in setOf("complete", "cancelled", "rejected") }
        }.getOrNull() else null
        val armed = if ("armed" in need) runCatching { com.optionslab.app.data.OrbArms.view().arms.any { it.armed } }.getOrNull() else null
        val bots = if ("bots" in need) runCatching { com.optionslab.app.data.Strategies.stoppedToday() }.getOrNull() else null
        return com.optionslab.ira.Verify.Facts(killOn = read("kill") { s!!.guardKill }, live = read("live") { s!!.live },
            botsStopped = bots, anyArmed = armed, openPositions = positions, workingOrders = orders)
    }

    suspend fun run(what: String, act: suspend () -> String): String {
        log(what)
        IraAccount.invalidate()
        return runCatching { act() }.getOrElse { "That did not work: ${it.message ?: "an error"}." }
            .also { IraActivity.add("$what: ${IraActivity.short(it)}"); if (it.startsWith("That did not work")) IraTools.count("failed") }
    }

    /** A setting's value now, in [SettingsTalk]'s terms. */
    fun setting(k: SettingsTalk.Key, s: AppSettings): Double = when (k) {
        SettingsTalk.Key.DAILY_LOSS -> s.guardDailyLoss
        SettingsTalk.Key.PAPER_DAILY_LOSS -> s.guardPaperDailyLoss
        SettingsTalk.Key.MAX_LOTS -> s.guardMaxLots.toDouble()
        SettingsTalk.Key.LOTS_PER_ORDER -> s.maxLotsPerOrder.toDouble()
        SettingsTalk.Key.MAX_OPEN -> s.guardMaxOpen.toDouble()
        SettingsTalk.Key.MAX_TRADES -> s.guardMaxTrades.toDouble()
        SettingsTalk.Key.PAPER_TRADES -> s.guardPaperTrades.toDouble()
        SettingsTalk.Key.ORDERS_PER_DAY -> s.maxOrdersPerDay.toDouble()
        SettingsTalk.Key.DRAWDOWN -> s.guardDrawdownPct
        SettingsTalk.Key.ORDER_VALUE -> s.guardMaxValue
        SettingsTalk.Key.EXPOSURE -> s.guardMaxExposure
        SettingsTalk.Key.CUTOFF -> s.guardCutoff.toDouble()
        SettingsTalk.Key.LOSS_ALERT -> s.pnlLossAlert
        SettingsTalk.Key.PROFIT_ALERT -> s.pnlProfitAlert
        SettingsTalk.Key.PRODUCT -> if (s.orderProduct == "MIS") 0.0 else 1.0
        SettingsTalk.Key.EXPIRY_SQUARE_OFF -> if (s.expirySquareOff) 1.0 else 0.0
        SettingsTalk.Key.NAKED_SHORTS -> if (s.guardNakedShort) 1.0 else 0.0
    }

    private fun applySetting(k: SettingsTalk.Key, v: Double, s: AppSettings): AppSettings = when (k) {
        SettingsTalk.Key.DAILY_LOSS -> s.copy(guardDailyLoss = v)
        SettingsTalk.Key.PAPER_DAILY_LOSS -> s.copy(guardPaperDailyLoss = v)
        SettingsTalk.Key.MAX_LOTS -> s.copy(guardMaxLots = v.toInt())
        SettingsTalk.Key.LOTS_PER_ORDER -> s.copy(maxLotsPerOrder = v.toInt())
        SettingsTalk.Key.MAX_OPEN -> s.copy(guardMaxOpen = v.toInt())
        SettingsTalk.Key.MAX_TRADES -> s.copy(guardMaxTrades = v.toInt())
        SettingsTalk.Key.PAPER_TRADES -> s.copy(guardPaperTrades = v.toInt())
        SettingsTalk.Key.ORDERS_PER_DAY -> s.copy(maxOrdersPerDay = v.toInt())
        SettingsTalk.Key.DRAWDOWN -> s.copy(guardDrawdownPct = v)
        SettingsTalk.Key.ORDER_VALUE -> s.copy(guardMaxValue = v)
        SettingsTalk.Key.EXPOSURE -> s.copy(guardMaxExposure = v)
        SettingsTalk.Key.CUTOFF -> s.copy(guardCutoff = v.toInt())
        SettingsTalk.Key.LOSS_ALERT -> s.copy(pnlLossAlert = v)
        SettingsTalk.Key.PROFIT_ALERT -> s.copy(pnlProfitAlert = v)
        SettingsTalk.Key.PRODUCT -> s.copy(orderProduct = if (v == 0.0) "MIS" else "NRML")
        SettingsTalk.Key.EXPIRY_SQUARE_OFF -> s.copy(expirySquareOff = v != 0.0)
        SettingsTalk.Key.NAKED_SHORTS -> s.copy(guardNakedShort = v != 0.0)
    }

    /** Would [c] loosen one of the app's limits (so only Boss's voice may ask for it)? */
    fun loosens(c: Command): Boolean {
        if (c.kind == Command.Kind.UNDO) {
            val cur = AppSettings.load()
            return com.optionslab.app.data.SettingsLog.lastUndoable().any { SettingsTalk.loosens(it.key, setting(it.key, cur), it.old) }
        }
        if (c.kind != Command.Kind.SET_LIMIT) return false
        val key = c.target?.let { runCatching { SettingsTalk.Key.valueOf(it) }.getOrNull() } ?: return false
        return SettingsTalk.loosens(key, setting(key, AppSettings.load()), c.level ?: return false)
    }

    private fun setSettings(f: (AppSettings) -> AppSettings) {
        val m = model()
        // Applied to what is stored (not the screen's copy, which can lag a kill switch the guard turned on by itself).
        if (m != null) m.update { f(AppSettings.load()) } else AppSettings.save(f(AppSettings.load()))
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
                com.optionslab.app.work.Notifier.orderFilled(c2, it.action, it.quantity, it.symbol, it.price, "Paper", source, it.orderId) } }
            model()?.loadPaper(quiet = true)
            return "Paper: ${r.message}" + (com.optionslab.app.data.Origins.shortId(r.orderId)?.let { " (order $it)" } ?: "")
        }
        val m = model() ?: return "A Zerodha order needs IraAlgo open: open it and ask again."
        m.planManual(t.underlying, t.expiry, t.strike, t.right, if (t.buy) com.optionslab.engine.Kite.Side.BUY else com.optionslab.engine.Kite.Side.SELL,
            t.lots, AppSettings.load().orderProduct, null, null, "Jarvis")
        return sendPlan(m)
    }
}
