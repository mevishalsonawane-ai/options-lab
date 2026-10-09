package com.optionslab.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.optionslab.app.ui.Root

/**
 * The only activity. Before any content exists the window is marked SECURE:
 * screenshots, screen recording, casting and the recents thumbnail all see a
 * blank surface, on every screen including dialogs (which inherit the flag).
 * Touches arriving through an overlay drawn by another app are discarded, and
 * on Android 12+ other apps' overlays are hidden altogether while the app is
 * shown, so a transparent window cannot trick a tap onto "Record" or "Erase".
 */
class MainActivity : FragmentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        val tabRequests = MutableStateFlow<String?>(null)
        /** A Zerodha position's "Close…" notification button: open its close popup (review + PIN). */
        const val EXTRA_CLOSE = "close"
        val closeRequests = MutableStateFlow<String?>(null)
        /** A per-install secret the app's own notifications carry; another app's launch intent lacks it. */
        const val EXTRA_NONCE = "n"
        // Built into every notification the app posts, so a Keystore fault while saving it must not take the
        // notification (or the foreground service posting it) down: the intent then just is not trusted later.
        fun nonce(): String = runCatching { com.optionslab.app.security.SecurePrefs.getString("intent.nonce") }.getOrNull()
            ?: java.util.UUID.randomUUID().toString()
                .also { runCatching { com.optionslab.app.security.SecurePrefs.put("intent.nonce", it) } }
        private fun trusted(i: android.content.Intent?) = i?.getStringExtra(EXTRA_NONCE)?.let { it == nonce() } == true
        /**
         * A tapped notification's card ([com.optionslab.app.work.NoticeCard]): shown over the app as a banner once it is
         * unlocked (the main screen, where the banner lives, is never composed over the lock). Only the app's own
         * notifications (the nonce) set it.
         */
        val cardRequests = MutableStateFlow<com.optionslab.app.work.NoticeCard?>(null)
        private fun cardOf(i: android.content.Intent): com.optionslab.app.work.NoticeCard? =
            com.optionslab.app.work.NoticeCards.fromExtras { k -> i.getStringExtra(k) }?.let { com.optionslab.app.work.NoticeCards.resolve(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Screenshots / recording follow the owner's switch in More -> Security (see security/Capture).
        com.optionslab.app.security.Capture.apply(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.decorView.filterTouchesWhenObscured = true
        // Dialogs and popups are separate windows the flag above does not cover. On Android 12+
        // hide every other app's overlay window while ours is showing, so none can sit over a
        // PIN pad or a confirm button. (Needs HIDE_OVERLAY_WINDOWS, a normal permission.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        if (trusted(intent)) {
            tabRequests.value = intent?.getStringExtra(EXTRA_TAB)
            closeRequests.value = intent?.getStringExtra(EXTRA_CLOSE)
            // Not again on a re-creation (the activity's saved state), nor when opened from Recents (the old tap's intent
            // replayed): the banner was already shown for this tap.
            val fromHistory = intent?.let { (it.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0 } == true
            if (savedInstanceState == null && !fromHistory) intent?.let { cardOf(it) }?.let { cardRequests.value = it }
        }
        // A fresh open (not a rotation, which brings saved state) holds the logo for a moment first.
        val splash = savedInstanceState == null && com.optionslab.app.ui.components.Splash.enabled
        setContent { Root(this, splash) }
    }

    /** The screen's stalls are timed only while the app is on screen (the diagnostics' "Speed:" line). */
    override fun onStart() {
        super.onStart()
        runCatching { com.optionslab.app.data.Speed.start() }
    }

    override fun onStop() {
        runCatching { com.optionslab.app.data.Speed.stop() }
        // The day's P&L figures kept in memory between writes ([SecurePrefs.putAllLazy]) go to disk now, in the background.
        runCatching { com.optionslab.app.security.SecurePrefs.saveSoon() }
        super.onStop()
    }

    /** Every touch counts as activity for the idle lock. */
    override fun onResume() {
        super.onResume()
        // Jarvis's listening, switched on but stopped by Android while the app was away, starts again on screen.
        if (BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.JarvisVoice.resume(this) }
        // Opening the app just after one of Jarvis's unasked alerts: Boss followed it up (kinds and minutes only).
        if (BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraTools.alertBoss(com.optionslab.ira.AlertSense.Boss.OPENED) }
        // Back on screen in market hours: a stop or lock the price went through while the app was away is sold now (08 Oct,
        // the missed-lock sweeper), off the main thread.
        if (com.optionslab.app.data.Market.isOpen() && !com.optionslab.app.data.Paper.testSkipFeedChecks) {
            val ctx = applicationContext
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { com.optionslab.app.data.Sweeper.run(ctx) }
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.optionslab.app.security.SessionLock.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!trusted(intent)) return
        tabRequests.value = intent.getStringExtra(EXTRA_TAB)
        intent.getStringExtra(EXTRA_CLOSE)?.let { closeRequests.value = it }
        cardOf(intent)?.let { cardRequests.value = it }
    }
}
