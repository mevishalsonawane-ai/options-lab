package com.optionslab.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * 21 Oct, enhancement round 3 (idea 6): text copied to the clipboard marked SENSITIVE. The diagnostics hold the day's trades,
 * stops and account lines; from Android 13 the system's copy preview and the keyboard's clipboard strip hide a clip marked so,
 * instead of showing its first lines over whatever app is open. Older Android: the same extra is set (it is ignored there).
 */
object SensitiveClip {
    /** Android's extra (API 33's ClipDescription.EXTRA_IS_SENSITIVE); by its value on older ones, as Android's own guide says. */
    internal val EXTRA: String = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"

    /** The clip itself (the tests read its mark). */
    fun clip(label: String, text: String): ClipData = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(EXTRA, true) }
    }

    /** [text] put on the clipboard as [label], marked sensitive. False when there is no clipboard. */
    fun copy(context: Context, label: String, text: String): Boolean {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(clip(label, text))
        return true
    }
}
