package com.optionslab.ira

import java.util.Locale

/**
 * The in-app study pages (S19, Research → Learn): plain-English pages, each with the app's OWN research numbers, from the
 * bundled summary (assets/study/pages.txt - a summary of research/HUNT_*.md and the strategy notes, never the whole files).
 * Searchable ([search]) and openable by Jarvis ("teach me about VWAP": [find]). Pure: the app reads the asset.
 *
 * The asset's format: "@@ id | title | group | keywords" starts a page; the lines after it, up to the next "@@", are its
 * paragraphs (a blank line between them); lines starting with "#" are comments.
 */
object StudyBook {
    data class Page(val id: String, val title: String, val group: String, val keywords: List<String>, val paragraphs: List<String>) {
        val text: String get() = paragraphs.joinToString("\n\n")
    }

    fun parse(text: String): List<Page> {
        val out = ArrayList<Page>()
        var head: List<String>? = null
        val body = StringBuilder()
        fun flush() {
            val h = head ?: return
            val paras = body.toString().split(Regex("\\n\\s*\\n")).map { it.lines().joinToString(" ") { l -> l.trim() }.trim() }.filter { it.isNotEmpty() }
            if (h.size >= 3) out += Page(h[0].trim(), h[1].trim(), h[2].trim(), h.getOrNull(3).orEmpty().split(',').map { it.trim().lowercase(Locale.ENGLISH) }.filter { it.isNotEmpty() }, paras)
            body.clear()
        }
        for (line in text.lineSequence()) {
            if (line.startsWith("#")) continue
            if (line.startsWith("@@")) { flush(); head = line.removePrefix("@@").split('|') ; continue }
            if (head != null) body.append(line).append('\n')
        }
        flush()
        return out
    }

    private fun words(s: String): List<String> = spacedWords(s.lowercase(Locale.ENGLISH)).split(' ').filter { it.length >= 2 }

    /** How well [p] answers [query]: a keyword or title hit counts most, then words in the text. 0: no match. */
    fun score(p: Page, query: String): Int {
        val q = query.trim().lowercase(Locale.ENGLISH)
        if (q.isEmpty()) return 0
        var s = 0
        if (p.id == q || p.title.lowercase(Locale.ENGLISH) == q) s += 100
        for (k in p.keywords) if (k == q) s += 60 else if (k.contains(q) || (q.contains(k) && k.length >= 3)) s += 25
        if (p.title.lowercase(Locale.ENGLISH).contains(q)) s += 30
        val qw = words(q).filter { it !in STOP }
        val tw = words(p.title + " " + p.keywords.joinToString(" "))
        val bw = words(p.text).toSet()
        for (w in qw) { if (w in tw) s += 10; if (w in bw) s += 2 }
        return s
    }

    private val STOP = setOf("the", "a", "an", "of", "on", "about", "and", "to", "me", "how", "what", "is", "why", "in", "for", "my")

    /** Pages matching [query], best first (all pages, in order, for an empty query). */
    fun search(pages: List<Page>, query: String): List<Page> {
        if (query.isBlank()) return pages
        return pages.map { it to score(it, query) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    /** The page for Jarvis's "teach me about [topic]" (the best match, if it is a clear one). */
    fun find(pages: List<Page>, topic: String): Page? = pages.map { it to score(it, topic) }.filter { it.second >= 10 }.maxByOrNull { it.second }?.first

    /** Jarvis's words when he opens [p] (its first paragraph, cut to a sentence or two). */
    fun say(p: Page?, topic: String): String {
        if (p == null) return "I have no study page on \"$topic\" yet, Boss. Research → Learn has the ones I have; search there."
        val first = p.paragraphs.firstOrNull().orEmpty()
        val cut = first.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")
        return "Opening the study page \"${p.title}\". $cut"
    }
}
