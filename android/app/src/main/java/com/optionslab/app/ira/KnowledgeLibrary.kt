package com.optionslab.app.ira

import android.content.Context
import com.optionslab.ira.Knowledge
import com.optionslab.ira.KnowledgeIndexFile
import com.optionslab.ira.SharedKnowledge

/**
 * Jarvis's knowledge pack (ira-core's [Knowledge]; assets/knowledge/pack.bin (gzip JSON), built by research/knowledge/build_pack.py):
 * read and indexed off the main thread, kept in memory while it is used. Reads only; nothing here acts.
 *
 * Memory audit (14 Oct): the index is opened from the file CI builds into the APK ([KnowledgeIndexFile.ASSET]: no unzip and
 * JSON parse of the 5.6 MB pack, no tokenising), and only on the first research question - the shared knowledge reads just
 * the file's head ([shared]). A trim drops it ([drop]); the next question opens it again. A build without the file, or a
 * file for another pack, builds the index from the pack as before.
 */
object KnowledgeLibrary {
    const val ASSET = "knowledge/pack.bin"
    @Volatile private var index: Knowledge.Index? = null
    private val lock = Any()
    /** How the index in memory was made ("the APK's prebuilt index" / "built from the pack"), for the diagnostics. */
    @Volatile var madeFrom: String? = null
        private set

    /** The index (opened the first time; a worker thread), or null when the pack cannot be read. */
    fun index(context: Context?): Knowledge.Index? = index ?: synchronized(lock) {
        index ?: context?.let { c -> open(c) }?.also { index = it }
    }

    private fun packBytes(c: Context): ByteArray? = runCatching { c.assets.open(ASSET).use { it.readBytes() } }.getOrNull()

    private fun open(c: Context): Knowledge.Index? {
        val bytes = packBytes(c) ?: return null
        val prebuilt = runCatching { c.assets.open(KnowledgeIndexFile.ASSET).use { KnowledgeIndexFile.read(it, KnowledgeIndexFile.packId(bytes)) } }.getOrNull()
        if (prebuilt != null) { madeFrom = "the APK's prebuilt index"; return prebuilt }
        return runCatching { Knowledge.Index(Knowledge.read(bytes.inputStream())) }.getOrNull()?.also { madeFrom = "built from the pack" }
    }

    /**
     * The pack's shared section and version - from the index in memory, else the index file's head alone (the index is not
     * opened for it), else the pack parsed and let go. Null when the pack cannot be read. A worker thread.
     */
    fun shared(context: Context?): Pair<SharedKnowledge.Research?, String>? {
        index?.let { return it.pack.shared to it.pack.version }
        val c = context ?: return null
        val bytes = packBytes(c) ?: return null
        return runCatching { c.assets.open(KnowledgeIndexFile.ASSET).use { KnowledgeIndexFile.readShared(it, KnowledgeIndexFile.packId(bytes)) } }.getOrNull()
            ?: runCatching { Knowledge.read(bytes.inputStream()).let { it.shared to it.version } }.getOrNull()
    }

    /** The index if open already (memory only), else null. */
    fun cached(): Knowledge.Index? = index

    /** Memory trim: the index let go (opened again by the next research question). Memory only. */
    fun drop() { synchronized(lock) { index = null; madeFrom = null } }

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
