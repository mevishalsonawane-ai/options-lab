package com.optionslab.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
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
        }
        // A fresh open (not a rotation, which brings saved state) holds the logo for a moment first.
        val splash = savedInstanceState == null && com.optionslab.app.ui.components.Splash.enabled
        setContent { Root(this, splash) }
    }

    /** Every touch counts as activity for the idle lock. */
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
    }
}
