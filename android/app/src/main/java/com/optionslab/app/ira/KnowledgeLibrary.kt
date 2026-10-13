package com.optionslab.app.ira

import android.content.Context
import com.optionslab.ira.Knowledge

/**
 * Jarvis's knowledge pack (ira-core's [Knowledge]; assets/knowledge/pack.bin (gzip JSON), built by research/knowledge/build_pack.py):
 * read and indexed once, off the main thread, and kept in memory. Reads only; nothing here acts.
 */
object KnowledgeLibrary {
    const val ASSET = "knowledge/pack.bin"
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
        // 13 Oct (the shared knowledge): the research's verdict merged with the app's own lessons on the same strategy or topic, each
        // with its source, and a disagreement said plainly (both kept). A locked phone gets the research only (the lessons carry the
        // bots' results). Memory only.
        val merged = runCatching {
            com.optionslab.ira.SharedKnowledge.answer(com.optionslab.app.data.SharedKnowledgeHost.snapshot, q, locked, max = 3)
        }.getOrDefault(emptyList())
        if (merged.isNotEmpty()) com.optionslab.app.data.SharedKnowledgeHost.read("Jarvis", "the research and the app's lessons for a research answer")
        val said = Knowledge.say(hits)
        if (merged.isEmpty()) return said to Knowledge.lines(hits)
        val extra = " Beside it, from the shared knowledge: " + merged.joinToString(" ")
        return (if (hits.isEmpty()) "From the shared knowledge, Boss: " + merged.joinToString(" ") else said + extra) to (Knowledge.lines(hits) + merged)
    }

    /** The model's notes for [question] from the pack (memory only: none until the pack has been read). */
    fun modelNotes(question: String): List<String> = runCatching { Knowledge.forModel(index, question) }.getOrDefault(emptyList())
}
