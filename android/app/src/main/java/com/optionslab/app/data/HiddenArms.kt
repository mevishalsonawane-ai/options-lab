package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.orb.ArmsCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Boss's 15 Oct arms cleanup on the phone ([ArmsCleanup]: what is kept, what is hidden, and the rules). Every automatic trader
 * outside Boss's short list is switched off through its own setter and hidden - code, settings and trade records kept - and
 * the "Hidden arms" list (Home → Strategies) restores each in one tap. Each switch-off and each restore is logged in Autopilot's
 * change timeline as Boss's, undone in one tap there too.
 *
 * OPEN POSITIONS COME FIRST: nothing here runs before the start's positions step is done ([PositionsFirst.awaitPositions]),
 * and an arm holding a position (or a working order, or a running strategy) is neither hidden nor stopped - it keeps its own
 * stop, target, trail and square-off at full cadence, visible, and is switched off on the first pass after it is flat (a pass
 * every [WAIT_MS] only while one waits; no loop otherwise). A read that fails counts as "holding": nothing is switched off on it.
 *
 * Nothing here is on the order or tick path: the passes run on their own IO worker, take each arm's own lock briefly (as its
 * screen does) and write this small file. The arms' entry paths read only memory ([blocks], [mayArm]). Nothing is ever switched
 * ON except by Boss's restore (or his Undo of a switch-off in the timeline), and then on PAPER only: each arm's own no-PIN path,
 * which in Live refuses or waits for the PIN on its row. PIN, lock, Live, AI-live, the guard, the kill switch, the day lock and
 * Google speech are never touched. Never in IraGoldAlgo.
 */
object HiddenArms {
    /** A pass while an arm waits for its exit, this often (it is switched off within this of going flat). */
    const val WAIT_MS = 20_000L

    /**
     * One hidden arm as the cleanup reads and switches it: [now] its switch and whether it holds anything (null: not readable -
     * treated as holding); [off] switches it off (only ever called when it is flat), returning the ids it switched off (a
     * family's members: Pine scripts, shadows, scheduled strategies; empty for a single arm); [on] puts those back on PAPER.
     */
    internal class Arm(
        val key: String,
        val now: suspend () -> ArmsCleanup.Now?,
        val off: suspend () -> List<String>,
        val on: suspend (List<String>) -> String,
    )

    private lateinit var file: File
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val poke = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(ArmsCleanup.State())
    /** The cleanup's state, in memory (the rows, the list and the arms' entry gates read it; never a file read). */
    val state: StateFlow<ArmsCleanup.State> = _state

    /** Each family's members switched off by the cleanup (key -> ids), so a restore puts back exactly those. */
    private val ids = HashMap<String, List<String>>()

    @Volatile private var loaded = false

    private fun ready() = ::file.isInitialized

    fun init(context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        file = File(context.applicationContext.filesDir, "hidden_arms.vault")
        scope.launch { runCatching { lock.withLock { load() } }; run() }
    }

    // ---- what the rows and the arms read (memory only) ---------------------------------------------------------------------

    /** Is [key]'s row shown? Kept arms always; a hidden one only while it still holds a position. */
    fun visible(key: String): Boolean = ArmsCleanup.visible(_state.value, key)

    /** May [key] be switched on by its switch, a voice command or an automation? Not while hidden (restore it first). */
    fun mayArm(key: String): Boolean {
        // 16 Oct (the order review: "ORB Sweep armed on paper" 4 times, hidden since 15 Oct): while the cleanup's file could not
        // be read its state is empty, so a hidden arm read as "may arm" and its switch, Jarvis's day plan or a voice command
        // armed it. Until it reads, the cleanup's arms are not switched on (nothing is switched off: risk only lowered).
        if (unread && ArmsCleanup.HIDDEN.any { it.key == key }) return false
        return ArmsCleanup.mayArm(_state.value, key)
    }

    /** The cleanup's file is there but could not be read yet (a Keystore hiccup): its arms stay as they are, none armed. */
    @Volatile var unread = false
        private set

    /** Why [key] (named [label]) may not be armed now, in plain words. */
    fun refusal(label: String): String =
        if (unread) "$label can't be switched on right now: the hidden-arms list could not be read yet (it is tried again shortly)."
        else ArmsCleanup.refusal(label)

    /**
     * May [key] take no NEW entry (or host or offer a new idea, or start a scheduled run) now? True from the moment the cleanup
     * takes it in - also while it still holds a position and waits for its exit: that position keeps its own stop, target,
     * trail and square-off at full cadence, but nothing new is opened (that only lowers risk). Memory only: the entry paths read it.
     */
    fun blocks(key: String): Boolean = key in _state.value.hidden

    /** The ORB family's or the expiry Hero's key for an arms' book source, or null for any other arm (Liquidity's books among them). */
    fun keyOfSource(source: String): String? = source.takeIf { it in ORB_SOURCES }

    private val ORB_SOURCES = setOf(ArmsCleanup.ORB, ArmsCleanup.ORB_FRESH, ArmsCleanup.ORB_SWEEP, ArmsCleanup.RANGE_FADE, ArmsCleanup.HERO)

    // ---- the file --------------------------------------------------------------------------------------------------------------

    private fun load() {
        if (loaded || !ready()) return
        val exists = file.exists()
        val text = runCatching { Vault.readFileSteady(file)?.toString(Charsets.UTF_8) }.getOrNull()
        val o = text?.let { runCatching { JSONObject(it) }.getOrNull() }
        // Saved but unreadable (a Keystore hiccup): never start over - that would hide again what Boss restored and lose the
        // members to restore. Nothing is switched or hidden until it reads (arms stay as they are: no risk added).
        if (exists && o == null) { unread = true; error("hidden arms' file unreadable") }
        unread = false
        _state.value = ArmsCleanup.decode(o?.optString("state"))
        ids.clear()
        o?.optJSONObject("ids")?.let { m -> m.keys().forEach { k -> m.optJSONArray(k)?.let { a -> ids[k] = (0 until a.length()).map { a.getString(it) } } } }
        loaded = true
    }

    private val written = Vault.LastWrite()

    private fun save() {
        if (!ready()) return
        val o = JSONObject().put("state", ArmsCleanup.encode(_state.value))
            .put("ids", JSONObject().apply { ids.forEach { (k, v) -> put(k, JSONArray(v)) } })
        runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
    }

    // ---- the passes ----------------------------------------------------------------------------------------------------------

    /** The worker: after the positions step, a pass; then one every [WAIT_MS] while an arm waits for its exit, else on a poke. */
    private suspend fun run() {
        awaitPositionsStep()
        // Liquidity 15+5 keeps its FINNIFTY and MIDCPNIFTY books on with its switch, on paper (once; [OrbArms.keepLiquidityFour]).
        runCatching { OrbArms.keepLiquidityFour() }.onFailure { runCatching { Diag.record("arms", "Liquidity's FINNIFTY / MIDCPNIFTY books: not changed now (${it.javaClass.simpleName})") } }
        while (true) {
            runCatching { passNow() }
            if (_state.value.waiting.isEmpty()) poke.receive() else withTimeoutOrNull(WAIT_MS) { poke.receive() }
        }
    }

    /**
     * OPEN POSITIONS COME FIRST: returns once the start's positions step (every book read, the watch started) is done - at once in a
     * process that never ran it (the unit tests' app). Nothing of the cleanup reads or changes an arm before.
     */
    internal suspend fun awaitPositionsStep() {
        while (PositionsFirst.startedAt != 0L && !PositionsFirst.order.isDone(com.optionslab.ira.StartOrder.Step.POSITIONS)) PositionsFirst.awaitPositions()
    }

    /** The arms the cleanup works on (tests swap them for stand-ins). */
    @Volatile internal var arms: List<Arm> = defaultArms()

    /**
     * One pass: each hidden arm read ([Arm.now]); the steps of [ArmsCleanup.pass] applied - OFF through its own setter, and
     * each first switch-off or hiding logged (the diagnostics and Autopilot's timeline). What it did, for the diagnostics.
     */
    internal suspend fun passNow(): List<ArmsCleanup.Step> = lock.withLock {
        load()
        val now = arms.mapNotNull { a -> runCatching { a.now() }.getOrNull() }
        val before = _state.value
        val (next, steps) = ArmsCleanup.pass(before, now)
        // In force at once: the gated arms read it, and each switch-off is checked against it below.
        _state.value = next
        var s = next
        for (st in steps) {
            val label = ArmsCleanup.of(st.key)?.label ?: st.key
            when (st.action) {
                ArmsCleanup.Action.OFF -> {
                    val arm = arms.firstOrNull { it.key == st.key }
                    val off = arm?.let { runCatching { it.off() }.getOrNull() }
                    if (off != null && off.isNotEmpty()) ids[st.key] = (ids[st.key].orEmpty() + off).distinct()
                    // Read again: still on (a failure, or only part of a family switched off) - kept on screen and waiting, not
                    // logged as done, and tried again on the next pass ([WAIT_MS]).
                    val after = arm?.let { runCatching { it.now() }.getOrNull() }
                    if (off == null || after == null || after.armed) {
                        s = s.copy(waiting = s.waiting + st.key, logged = s.logged - st.key)
                        runCatching { Diag.record("arms", "$label: the cleanup could not switch it off now; it tries again") }
                        continue
                    }
                    runCatching { Diag.record("arms", "$label: ${ArmsCleanup.HIDDEN_WORDS}" + if (st.first) "" else " (it had been switched on again)") }
                    if (st.first) runCatching { com.optionslab.app.ira.Autopilot.logArmHidden(st.key, label, wasOn = true) }
                }
                ArmsCleanup.Action.HIDE -> {
                    runCatching { Diag.record("arms", "$label: hidden on your choice (15 Oct); it was off") }
                    runCatching { com.optionslab.app.ira.Autopilot.logArmHidden(st.key, label, wasOn = false) }
                }
                ArmsCleanup.Action.WAIT -> runCatching { Diag.record("arms", "$label: ${ArmsCleanup.WAITING}") }
            }
        }
        val changed = s != before
        _state.value = s
        if (changed || steps.isNotEmpty()) save()
        steps
    }

    /**
     * Boss restores [key] from the Hidden arms list (or undoes its switch-off in the timeline: [log] false - the timeline marks
     * that itself): no longer hidden, and back on - ON PAPER ONLY, through its own no-PIN path - when it was on. What to say.
     */
    suspend fun restore(key: String, log: Boolean = true): String {
        if (!ready()) return "Not ready yet."
        val label = ArmsCleanup.of(key)?.label ?: return "There is no hidden arm called $key."
        val (wasOn, members) = lock.withLock {
            load()
            if (key !in _state.value.hidden) return "$label is not hidden."
            val r = ArmsCleanup.restore(_state.value, key)
            _state.value = r.state
            val m = ids.remove(key).orEmpty()
            save()
            r.on to m
        }
        val said = if (wasOn) runCatching { arms.firstOrNull { it.key == key }?.on?.invoke(members) }.getOrNull() ?: "switch it on from its row" else null
        runCatching { Diag.record("arms", "$label: restored by you from the Hidden arms list" + (said?.let { " - $it" } ?: "")) }
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logArmRestored(key, label) }
        return "$label is back on its row" + (said?.let { ": $it" } ?: " (it was off: switch it on there when you choose).")
    }

    /** The timeline's Undo of a restore: [key] hidden again, through the same pass (switched off once it holds nothing). */
    suspend fun hideAgain(key: String): String {
        if (!ready()) return "Not ready yet."
        val label = ArmsCleanup.of(key)?.label ?: return "There is no arm called $key."
        lock.withLock { load(); _state.value = ArmsCleanup.hideAgain(_state.value, key); save() }
        poke.trySend(Unit)
        return "$label is hidden again (switched off once it holds nothing)."
    }

    /** The diagnostics' line. */
    fun diagLine(): String {
        val s = _state.value
        if (!s.done) return "Hidden arms: the 15 Oct cleanup has not run yet"
        return "Hidden arms: ${ArmsCleanup.listed(s).size} hidden" + (if (s.waiting.isEmpty()) "" else
            "; waiting for their exits: " + s.waiting.mapNotNull { ArmsCleanup.of(it)?.label }.joinToString(", "))
    }

    // ---- each hidden arm, through its own setters ------------------------------------------------------------------------------

    private fun orbArm(src: String) = Arm(src,
        now = { OrbArms.cleanupNow(src)?.let { (armed, open) -> ArmsCleanup.Now(src, armed, open) } },
        off = { OrbArms.cleanupOff(src); emptyList() },
        on = { OrbArms.setArmed(src, true, automatic = OrbArms.autoOf(src), pinConfirmed = false) })

    private fun defaultArms(): List<Arm> = listOf(ArmsCleanup.ORB, ArmsCleanup.ORB_FRESH, ArmsCleanup.ORB_SWEEP, ArmsCleanup.RANGE_FADE, ArmsCleanup.HERO)
        .map { orbArm(it) } + listOf(
        Arm(ArmsCleanup.NIGHT,
            now = { if (!NightArm.readyForCleanup()) null else NightArm.refresh().let { v -> ArmsCleanup.Now(ArmsCleanup.NIGHT, v.armed, v.open.isNotEmpty()) } },
            off = { NightArm.setArmed(false); emptyList() },
            on = { NightArm.setArmed(true) }),
        Arm(ArmsCleanup.VIX_DIV,
            now = { if (!VixDivArm.readyForCleanup()) null else VixDivArm.refresh().let { v -> ArmsCleanup.Now(ArmsCleanup.VIX_DIV, v.armed, v.open.isNotEmpty()) } },
            off = { VixDivArm.setArmed(false); emptyList() },
            on = { VixDivArm.setArmed(true) }),
        // The strategy lab's paper ideas: no switch of their own - a gate ([blocks]) stops new entries and new ideas being hosted.
        Arm(ArmsCleanup.FORGE,
            now = { if (!GrammarArm.readyForCleanup()) null else GrammarArm.refresh().let { v ->
                ArmsCleanup.Now(ArmsCleanup.FORGE, !blocks(ArmsCleanup.FORGE) && v.active.isNotEmpty(), v.ideas.any { it.open != null }) } },
            off = { emptyList() },
            on = { "its paper ideas trade again" }),
        // Pine scripts that place orders (alerts-only scripts never trade and are left as they are).
        Arm(ArmsCleanup.PINE,
            now = {
                PineAuto.load()
                val on = PineScripts.items.value.filter { it.auto.on && it.auto.mode == "trade" }
                ArmsCleanup.Now(ArmsCleanup.PINE, on.isNotEmpty(), PineAuto.holding())
            },
            off = {
                PineScripts.items.value.filter { it.auto.on && it.auto.mode == "trade" }.mapNotNull { s ->
                    s.id.toString().takeIf { PineAuto.arm(s.id, false) == "ok" }
                }
            },
            on = { ids -> ids.mapNotNull { it.toLongOrNull() }.map { PineAuto.arm(it, true, pinConfirmed = false) }
                .let { r -> if (r.all { it == "ok" }) "its scripts are on again (in Live their orders wait for your PIN on the Pine page)" else r.firstOrNull { it != "ok" } ?: "" } }),
        // Research shadows Boss re-armed on paper (the shadows themselves keep recording: no orders).
        Arm(ArmsCleanup.SHADOW,
            now = {
                ShadowArms.rowsNow()?.let { rows -> ArmsCleanup.Now(ArmsCleanup.SHADOW, rows.any { it.armed }, rows.any { r -> r.trades.any { it.open && it.paper } }) }
            },
            off = { ShadowArms.rows().filter { it.armed }.map { r -> ShadowArms.disarm(r.variant.id); r.variant.id } },
            on = { ids -> ids.map { ShadowArms.promote(it) }.lastOrNull() ?: "nothing to re-arm" }),
        // Saved strategies Boss armed to start themselves on a schedule (a running one is "holding").
        Arm(ArmsCleanup.BASKETS,
            now = { Strategies.all().let { l -> ArmsCleanup.Now(ArmsCleanup.BASKETS, l.any { it.def.scheduler?.enabled == true }, l.any { it.running }) } },
            off = {
                val auto = Strategies.automatic()
                Strategies.all().filter { it.def.scheduler?.enabled == true }.mapNotNull { e ->
                    val mode = e.def.scheduler?.defaultMode ?: com.optionslab.engine.strategy.RunMode.SANDBOX
                    // "id:auto" or "id:approve": a restore brings it back with the same choice.
                    "${e.def.id}:${if (auto[e.def.id] ?: (mode != com.optionslab.engine.strategy.RunMode.LIVE)) "auto" else "approve"}"
                        .takeIf { Strategies.setArmed(e.def.id, false, mode) == null }
                }
            },
            // Back on paper only, whatever they were armed for (a live schedule is armed again by Boss with the PIN).
            on = { ids -> ids.mapNotNull { m -> m.substringBefore(':').toLongOrNull()?.let { it to (m.substringAfter(':', "auto") != "approve") } }
                .mapNotNull { (id, automatic) -> Strategies.setArmed(id, true, com.optionslab.engine.strategy.RunMode.SANDBOX, automatic) }
                .firstOrNull() ?: "its strategies start on their schedules again, on paper" }),
        // Jarvis's own news and pattern trade ideas (asked, or taken on paper by himself): a gate ([blocks]); the news is still told.
        Arm(ArmsCleanup.JARVIS,
            now = { ArmsCleanup.Now(ArmsCleanup.JARVIS, !blocks(ArmsCleanup.JARVIS), com.optionslab.app.ira.IraNewsTrades.all().any { !it.closed }) },
            off = { emptyList() },
            on = { "Jarvis offers his trade ideas again" }),
    )

    /** TEST ONLY: as after a restart - the state read again from its file on the next pass. */
    internal fun reloadForTest() { loaded = false; unread = false; _state.value = ArmsCleanup.State(); ids.clear() }

    /** TEST ONLY: the file read now, as the start does (a failure leaves [unread] set). */
    internal fun loadForTest() { runCatching { load() } }

    /** TEST ONLY: the arms the app uses. */
    internal fun defaultArmsForTest(): List<Arm> = defaultArms()

    /** TEST ONLY: a fresh state, the default arms, [context]'s file. */
    internal fun resetForTest(context: Context) {
        file = File(context.applicationContext.filesDir, "hidden_arms.vault")
        file.delete(); written.forget()
        _state.value = ArmsCleanup.State(); ids.clear(); loaded = false; unread = false
        arms = defaultArms()
    }
}
