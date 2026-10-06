package com.optionslab.ira

import java.time.Instant

/**
 * Has Jarvis already told this story? (Boss, 06 Oct: "check the same news is not read again".) A headline is the same
 * story as one seen before when its link is the same once tracking parts are removed (query, fragment, "amp" pages,
 * http/https, "www.", a trailing slash), or when its title is the same or nearly the same words (another site's copy, a
 * reworded update) within [SAME_STORY_HOURS]. Keeps the newest [max] links and titles only. Pure; not thread-safe
 * (the caller holds its own lock).
 */
class NewsDedup(private val max: Int = 3_000) {
    private val links = LinkedHashSet<String>()
    private val titles = ArrayDeque<Pair<Set<String>, Instant>>()

    /** True the first time this story is seen (and remembers it); false for a story already told. */
    fun firstTime(h: Headline, now: Instant = Instant.now()): Boolean {
        val link = linkKey(h.link)
        val words = titleWords(h.title)
        val since = now.minusSeconds(SAME_STORY_HOURS * 3600)
        while (titles.isNotEmpty() && titles.first().second.isBefore(since)) titles.removeFirst()
        val seenLink = link != null && link in links
        val seenTitle = titles.any { same(it.first, words) }
        if (link != null) { links.add(link); Upkeep.trimOldest(links, max) }
        if (words.isNotEmpty()) { titles.addLast(words to now); while (titles.size > max) titles.removeFirst() }
        return !seenLink && !seenTitle
    }

    val size: Int get() = links.size

    companion object {
        /** A reworded copy of a story counts as the same within this many hours. */
        const val SAME_STORY_HOURS = 12L
        /** Share of the shorter title's words the two must share to be the same story. */
        const val OVERLAP = 0.8
        private val STOP = setOf("a", "an", "the", "to", "of", "in", "on", "for", "and", "as", "at", "by", "is", "are", "with", "from", "its", "it")

        /** The link without what changes between copies of the same page; null when there is none. */
        fun linkKey(link: String): String? {
            var s = link.trim().lowercase()
            if (s.isEmpty()) return null
            s = s.substringBefore('#').substringBefore('?')
            s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("m.")
            s = s.replace("/amp/", "/").removeSuffix("/amp").removeSuffix(".amp").removeSuffix("/")
            return s.ifEmpty { null }
        }

        /** The title's meaningful words (small linking words dropped). */
        fun titleWords(title: String): Set<String> =
            title.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() && it !in STOP }.toSet()

        /** Same story: identical words, or (both 4+ words) at least [OVERLAP] of the shorter title's words shared. */
        fun same(a: Set<String>, b: Set<String>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            if (a == b) return true
            val small = minOf(a.size, b.size)
            if (small < 4) return false
            return a.intersect(b).size.toDouble() / small >= OVERLAP
        }
    }
}
