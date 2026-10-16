package com.optionslab.app.ira

import android.content.Context
import com.optionslab.ira.Concepts

/**
 * Jarvis's concept book (15 Oct 2026; ira-core's [Concepts], assets/concepts/concepts.json, built by
 * research/hunt/r61/app_concepts.py): read once off the main thread and kept in memory (about 100 KB of text). A memory
 * trim drops it before anything position-related ([com.optionslab.ira.MemoryPlan.Item.CONCEPTS]); the next use reads it
 * again. Words only; nothing here acts or reads Boss's account.
 */
object ConceptLibrary {
    @Volatile private var book: Concepts.Book? = null
    private val lock = Any()

    /** The book (read the first time: a worker thread only), or an empty book when the asset cannot be read. */
    fun load(context: Context?): Concepts.Book = book ?: synchronized(lock) {
        book ?: run {
            val c = context ?: return Concepts.Book.EMPTY
            val text = runCatching { c.assets.open(Concepts.ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                ?: return Concepts.Book.EMPTY
            Concepts.parse(text).also { if (it.concepts.isNotEmpty()) book = it }
        }
    }

    /** The book if read already (memory only: safe on the main thread), else null. */
    fun cached(): Concepts.Book? = book

    /** Memory trim: the book let go (read again on the next concept question or screen). Memory only. */
    fun drop() { synchronized(lock) { book = null } }
}
