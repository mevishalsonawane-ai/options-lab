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
 * Touches arriving through an overlay drawn by another app are discarded, so
 * a transparent window cannot trick a tap onto "Record" or "Erase".
 */
class MainActivity : FragmentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        val tabRequests = MutableStateFlow<String?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        window.decorView.filterTouchesWhenObscured = true
        tabRequests.value = intent?.getStringExtra(EXTRA_TAB)
        setContent { Root(this) }
    }

    /** Every touch counts as activity for the idle lock. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.optionslab.app.security.SessionLock.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabRequests.value = intent.getStringExtra(EXTRA_TAB)
    }
}
