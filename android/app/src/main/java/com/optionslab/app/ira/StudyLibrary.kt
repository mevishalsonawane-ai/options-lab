package com.optionslab.app.ira

import android.content.Context
import com.optionslab.ira.StudyBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The study pages (S19; ira-core's [StudyBook]) from the bundled summary (assets/study/pages.txt), read once off the main
 * thread and kept in memory; [open] is the page Jarvis asked for ("teach me about VWAP"), shown over the app by
 * [com.optionslab.app.ui.screens.SelfSheetHost]. Reads only.
 */
object StudyLibrary {
    const val ASSET = "study/pages.txt"
    @Volatile private var pages: List<StudyBook.Page>? = null

    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> = _open

    /** The pages (read from the APK's assets the first time; a worker thread). */
    fun pages(context: Context): List<StudyBook.Page> = pages ?: runCatching {
        context.assets.open(ASSET).bufferedReader().use { StudyBook.parse(it.readText()) }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) pages = it }

    /** The pages if read already (memory only), else none. */
    fun cached(): List<StudyBook.Page> = pages.orEmpty()

    fun show(id: String) { _open.value = id }
    fun close() { _open.value = null }

    /** "Teach me about [topic]": the page opened over the app, and Jarvis's words. */
    fun answer(context: Context?, topic: String): String {
        val all = context?.let { pages(it) } ?: cached()
        val p = StudyBook.find(all, topic)
        if (p != null) show(p.id)
        return StudyBook.say(p, topic)
    }

    /** TEST ONLY. */
    internal fun resetForTest() { _open.value = null }
}
