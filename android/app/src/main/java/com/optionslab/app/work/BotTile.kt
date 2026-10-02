package com.optionslab.app.work

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.optionslab.app.R
import com.optionslab.app.data.Diag
import com.optionslab.app.data.GoldDipPaper
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
 *   IraAlgo      the same switch as Home's "Stop bot for today" / "Start bot": stopping means no armed strategy or arm
 *                starts again today (running ones keep managing their own exits); it does not touch the kill switch.
 *   IraGoldAlgo  stops every gold arm (disarms them; an open trade is still managed to its exit) and remembers which
 *                were armed; starting arms those again.
 *
 * Stopping works at once, even on the lock screen (the safe direction); starting asks for the phone to be unlocked first.
 * Nothing new is decided here: the tile calls the app's own stop / start.
 */
class BotTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var live: kotlinx.coroutines.Job? = null

    /** While the panel is open the tile's second line is today's P&L, refreshed every 5 seconds. */
    override fun onStartListening() {
        live?.cancel()
        live = scope.launch { while (true) { refresh(); kotlinx.coroutines.delay(5_000) } }
    }

    override fun onStopListening() { live?.cancel(); live = null }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onClick() {
        scope.launch {
            if (running()) { stop(); refresh() }
            else withContext(Dispatchers.Main) {
                if (isLocked) unlockAndRun { scope.launch { start(); refresh() } } else scope.launch { start(); refresh() }
            }
        }
    }

    private fun refresh() {
        scope.launch {
            val on = runCatching { running() }.getOrDefault(false)
            // Today's P&L in place of "tap to stop / start" - unless the phone is locked and the owner hides figures there.
            val hide = isLocked && runCatching { com.optionslab.app.data.AppSettings.load().hideAmountsOnLockScreen }.getOrDefault(true)
            val pnl = if (hide) null else runCatching { todayPnl() }.getOrNull()
            withContext(Dispatchers.Main) {
                val t = qsTile ?: return@withContext
                t.icon = Icon.createWithResource(this@BotTile, R.drawable.ic_notification_art)
                t.label = if (com.optionslab.app.BuildConfig.GOLD) "Gold bot" else "IraAlgo bot"
                t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.subtitle = pnl ?: if (on) "Running" else "Stopped"
                t.updateTile()
            }
        }
    }

    /**
     * Today's P&L, live: IraAlgo - the paper account's day P&L after charges (Home's "P&L today"), or in Live the
     * Zerodha figure the watch publishes each minute; IraGoldAlgo - today's closed trades of the three arms (by the
     * Indian day, as the P&L calendar) plus the open trades at the last price.
     */
    private suspend fun todayPnl(): String? =
        if (com.optionslab.app.BuildConfig.GOLD) {
            val today = GoldPaper.now().plusMinutes(330).toLocalDate()
            val closed = (GoldPaper.book.value.trades + GoldTrendPaper.book.value.trades + GoldDipPaper.book.value.trades)
                .filter { it.exitTime.plusMinutes(330).toLocalDate() == today }.sumOf { it.pnl }
            val px = GoldPaper.book.value.price
            val open = (GoldPaper.book.value.open(px) ?: 0.0) + (GoldTrendPaper.book.value.open(px) ?: 0.0) + (GoldDipPaper.book.value.open(px) ?: 0.0)
            "Today " + GoldPaper.usd(closed + open)
        } else {
            val v = if (com.optionslab.app.data.AppSettings.load().live) com.optionslab.app.widget.IraWidget.lastPnl()
                else com.optionslab.app.data.Paper.snapshot().dayPnl
            v?.let { "Today " + (if (it < 0) "-₹" else "+₹") + "%,.0f".format(java.util.Locale.ENGLISH, kotlin.math.abs(it)) }
        }

    private suspend fun running(): Boolean =
        if (com.optionslab.app.BuildConfig.GOLD) GoldPaper.book.value.armed || GoldTrendPaper.book.value.armed || GoldDipPaper.book.value.armed
        else !Strategies.stoppedToday()

    private suspend fun stop() {
        if (com.optionslab.app.BuildConfig.GOLD) {
            val was = listOfNotNull(if (GoldPaper.book.value.armed) "liquidity" else null, if (GoldTrendPaper.book.value.armed) "trend" else null,
                if (GoldDipPaper.book.value.armed) "dip" else null)
            if (was.isNotEmpty()) SecurePrefs.put(GOLD_ARMED, was.joinToString(","))
            GoldPaper.setArmed(false); GoldTrendPaper.setArmed(false); GoldDipPaper.setArmed(false)
            runCatching { Diag.record("gold", "tile: all arms stopped (were: ${was.joinToString()})") }
        } else {
            val msg = Strategies.stopForToday(stopRunning = false, compromised = false)
            runCatching { Diag.record("info", "tile: $msg") }
        }
    }

    private suspend fun start() {
        if (com.optionslab.app.BuildConfig.GOLD) {
            val arms = (SecurePrefs.getString(GOLD_ARMED) ?: "liquidity").split(',').toSet()
            if ("liquidity" in arms) GoldPaper.setArmed(true)
            if ("trend" in arms) GoldTrendPaper.setArmed(true)
            if ("dip" in arms) GoldDipPaper.setArmed(true)
            runCatching { GoldService.ensure(this) }
            runCatching { Diag.record("gold", "tile: arms started: ${arms.joinToString()}") }
        } else {
            val msg = Strategies.startAgain()
            runCatching { Diag.record("info", "tile: $msg") }
        }
    }

    companion object { private const val GOLD_ARMED = "gold.tile.armed" }
}
