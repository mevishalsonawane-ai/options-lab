package com.optionslab.app.ira

import android.content.Context
import com.optionslab.ira.Knowledge

/**
 * Jarvis's knowledge pack (ira-core's [Knowledge]; assets/knowledge/pack.json.gz, built by research/knowledge/build_pack.py):
 * read and indexed once, off the main thread, and kept in memory. Reads only; nothing here acts.
 */
object KnowledgeLibrary {
    const val ASSET = "knowledge/pack.json.gz"
    @Volatile private var index: Knowledge.Index? = null
    private val lock = Any()

    /** The index (the asset read and indexed the first time; a worker thread), or null when the pack cannot be read. */
    fun index(context: Context?): Knowledge.Index? = index ?: synchronized(lock) {
        index ?: context?.let { c -> runCatching { c.assets.open(ASSET).use { Knowledge.Index(Knowledge.read(it)) } }.getOrNull() }
            ?.also { index = it }
    }

    /** The index if read already (memory only), else null. */
    fun cached(): Knowledge.Index? = index

    /**
     * Jarvis's answer to a research question [q] and the facts it rests on (the model's facts for a rewrite). [locked]: Boss's
     * own figures left out. A worker thread.
     */
    fun answer(context: Context?, q: String, locked: Boolean): Pair<String, List<String>> {
        val ix = index(context) ?: return Knowledge.NOT_LOADED to emptyList()
        val hits = Knowledge.search(ix, q, k = 3, locked = locked)
        return Knowledge.say(hits) to Knowledge.lines(hits)
    }

    /** The model's notes for [question] from the pack (memory only: none until the pack has been read). */
    fun modelNotes(question: String): List<String> = runCatching { Knowledge.forModel(index, question) }.getOrDefault(emptyList())
}
