package com.optionslab.app.work

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.optionslab.app.R
import com.optionslab.app.data.Diag
import com.optionslab.app.data.GoldDipPaper
import com.optionslab.app.data.GoldTasPaper
import com.optionslab.app.data.GoldPaper
import com.optionslab.app.data.GoldTrendPaper
import com.optionslab.app.data.Strategies
import com.optionslab.app.security.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The quick-settings tile (the owner's ask, 2026-10-02): the app's logo as a button to stop or start the bot without opening the app.
 *
 *   IraAlgo      the same switch as Home's "Stop index bots" / "Start index bots" (30 Oct: the INDEX side only - the MCX bots
 *                keep trading; they have their own stop on the MCX card): stopping means no armed strategy or NSE arm starts
 *                again today (running ones keep managing their own exits); it does not touch the kill switch.
 *   IraGoldAlgo  stops every gold arm (disarms them; an open trade is still managed to its exit) and remembers which
 *                were armed; starting arms those again.
 *
 * Stopping works at once, even on the lock screen (the safe direction); starting asks for the phone to be unlocked first.
 * Nothing new is decided here: the tile calls the app's own stop / start.
 */
class BotTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var live: kotlinx.coroutines.Job? = null
    /** TEST ONLY: the panel's refresh loop while it is open (a test waits for it to end). */
    internal val liveForTest: kotlinx.coroutines.Job? get() = live
    /** 22 Nov 2026 (B6 review): the panel is open (set on the main thread): only then may the tile show an amount. */
    @Volatile private var listening = false

    /** While the panel is open the tile's second line is today's P&L, refreshed every second. */
    override fun onStartListening() {
        listening = true
        // 22 Nov 2026 (B6 review): a P&L left from the last time is never shown again as the panel opens (on the lock screen too): the
        // state alone at once, before the first read; the read then shows today's figure where it may.
        stateOnly()
        live?.cancel()
        live = scope.launch { while (true) { refreshNow(); kotlinx.coroutines.delay(1_000) } }
    }

    /**
     * 22 Nov 2026 (B6 review): the panel closed - the last P&L stayed on the tile (seen again on the lock screen, however stale): the
     * second line goes back to the state alone, at once, and a refresh still on its way shows no amount.
     */
    override fun onStopListening() {
        listening = false
        live?.cancel(); live = null
        stateOnly()
    }

    /** 22 Nov 2026 (B6 review): the tile's second line as its state alone, no amount (the main thread; no read: the tile's own state). */
    private fun stateOnly() {
        runCatching {
            val t = qsTile ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.subtitle = subtitle(t.state == Tile.STATE_ACTIVE, null, listening = false, locked = false, hideOnLock = true)
            t.updateTile()
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onClick() {
        scope.launch {
            if (running()) { stop(); refresh() }
            else withContext(Dispatchers.Main) {
                if (isLocked) unlockAndRun { scope.launch { start(); refresh() } } else scope.launch { start(); refresh() }
            }
        }
    }

    private fun refresh() { scope.launch { refreshNow() } }

    private suspend fun refreshNow() {
        val on = runCatching { running() }.getOrDefault(false)
        // Today's P&L in place of "tap to stop / start" - unless the phone is locked and the owner hides figures there.
        val hideOnLock = runCatching { com.optionslab.app.data.AppSettings.load().hideAmountsOnLockScreen }.getOrDefault(true)
        val pnl = if ((isLocked && hideOnLock) || !listening) null else runCatching { todayPnl() }.getOrNull()
        withContext(Dispatchers.Main) {
            val t = qsTile ?: return@withContext
            t.icon = Icon.createWithResource(this@BotTile, R.drawable.ic_notification_art)
            t.label = if (com.optionslab.app.BuildConfig.GOLD) "Gold bot" else "Index bots"   // 30 Oct: the tile stops the index side only
            t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            // 22 Nov 2026 (B6 review): decided again here, on the main thread, as it is shown: a phone locked since the read, or a panel
            // closed meanwhile, shows no amount.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.subtitle = subtitle(on, pnl, listening, runCatching { isLocked }.getOrDefault(true), hideOnLock)
            t.updateTile()
        }
    }

    /**
     * Today's P&L, live: IraAlgo - the paper account's day P&L before charges (Home's "P&L today"), or in Live the
     * Zerodha figure the watch publishes each minute; IraGoldAlgo - today's closed trades of the four arms (by the
     * Indian day, as the P&L calendar) plus the open trades at the last price.
     */
    private suspend fun todayPnl(): String? =
        if (com.optionslab.app.BuildConfig.GOLD) {
            com.optionslab.app.data.GoldBooks.awaitLoaded()
            val today = GoldPaper.now().plusMinutes(330).toLocalDate()
            val closed = (GoldPaper.book.value.trades + GoldTrendPaper.book.value.trades + GoldDipPaper.book.value.trades +
                GoldTasPaper.book.value.trades)
                .filter { it.exitTime.plusMinutes(330).toLocalDate() == today }.sumOf { it.pnl }
            val px = GoldPaper.book.value.price
            val open = (GoldPaper.book.value.open(px) ?: 0.0) + (GoldTrendPaper.book.value.open(px) ?: 0.0) + (GoldDipPaper.book.value.open(px) ?: 0.0) +
                (GoldTasPaper.book.value.open(px) ?: 0.0)
            "Today " + GoldPaper.usd(closed + open)
        } else {
            val v = if (com.optionslab.app.data.AppSettings.load().live) PositionCards.livePnl ?: com.optionslab.app.widget.IraWidget.lastPnl()
                else com.optionslab.app.data.Paper.snapshot().dayGross   // before charges, as Home and Zerodha show it
            v?.let { "Today " + (if (it < 0) "-₹" else "+₹") + "%,.0f".format(java.util.Locale.ENGLISH, kotlin.math.abs(it)) }
        }

    private suspend fun running(): Boolean {
        if (!com.optionslab.app.BuildConfig.GOLD) return !Strategies.stoppedToday()
        com.optionslab.app.data.GoldBooks.awaitLoaded()
        return GoldPaper.book.value.armed || GoldTrendPaper.book.value.armed || GoldDipPaper.book.value.armed || GoldTasPaper.book.value.armed
    }

    private suspend fun stop() {
        if (com.optionslab.app.BuildConfig.GOLD) {
            com.optionslab.app.data.GoldBooks.awaitLoaded()
            val was = listOfNotNull(if (GoldPaper.book.value.armed) "liquidity" else null, if (GoldTrendPaper.book.value.armed) "trend" else null,
                if (GoldDipPaper.book.value.armed) "dip" else null, if (GoldTasPaper.book.value.armed) "tas" else null)
            if (was.isNotEmpty()) SecurePrefs.put(GOLD_ARMED, was.joinToString(","))
            GoldPaper.setArmed(false); GoldTrendPaper.setArmed(false); GoldDipPaper.setArmed(false); GoldTasPaper.setArmed(false)
            runCatching { Diag.record("gold", "tile: all arms stopped (were: ${was.joinToString()})") }
        } else {
            // The strategies' book unreadable just now: said in the log, never a crash of the tile (18 Oct, review).
            val msg = runCatching { Strategies.stopForToday(stopRunning = false, compromised = false, why = com.optionslab.ira.DayStop.Why.TILE) }
                .getOrElse { "could not stop: ${it.javaClass.simpleName}" }
            runCatching { Diag.record("info", "tile: $msg") }
        }
    }

    private suspend fun start() {
        if (com.optionslab.app.BuildConfig.GOLD) {
            val arms = (SecurePrefs.getString(GOLD_ARMED) ?: "liquidity").split(',').toSet()
            if ("liquidity" in arms) GoldPaper.setArmed(true)
            if ("trend" in arms) GoldTrendPaper.setArmed(true)
            if ("dip" in arms) GoldDipPaper.setArmed(true)
            if ("tas" in arms) GoldTasPaper.setArmed(true)
            runCatching { GoldService.ensure(this) }
            runCatching { Diag.record("gold", "tile: arms started: ${arms.joinToString()}") }
        } else {
            val msg = runCatching { Strategies.startAgain() }.getOrElse { "could not start: ${it.javaClass.simpleName}" }
            runCatching { Diag.record("info", "tile: $msg") }
            // 19 Nov 2026 (audit batch 2): in Live with a PIN set the tile cannot lift the stop - Boss is told how, never left guessing.
            startNotice(msg)?.let { text -> runCatching { Notifier.post(this, TILE_PIN_NOTICE, Notifier.RISK, "Index bots still stopped", text) } }
        }
    }

    companion object {
        private const val GOLD_ARMED = "gold.tile.armed"
        /** 19 Nov 2026 (audit batch 2): the notice when the tile's Start needs the PIN (Live), and its words. */
        private const val TILE_PIN_NOTICE = 7481
        internal const val TILE_PIN_TEXT = "Open IraAlgo to start the index bots (PIN): in Live the tile cannot start them."

        /** 19 Nov 2026 (audit batch 2): the notice the tile's Start posts for what [Strategies.startAgain] said; null: none. */
        internal fun startNotice(said: String): String? = if (said == Strategies.LIFT_PIN) TILE_PIN_TEXT else null

        /**
         * 22 Nov 2026 (B6 review): the tile's second line - today's [pnl] while the panel is open ([listening]), never on a [locked] phone
         * with "hide amounts on the lock screen" on ([hideOnLock]); otherwise the state alone ([on]), with no amount.
         */
        internal fun subtitle(on: Boolean, pnl: String?, listening: Boolean, locked: Boolean, hideOnLock: Boolean): String =
            pnl?.takeIf { listening && !(locked && hideOnLock) } ?: if (on) "Running" else "Stopped"
    }
}
