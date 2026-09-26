package com.optionslab.app.work

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Every event the app wants the owner to see on screen - an order placed,
 * filled, closed or refused, a process that failed, a strategy event - goes
 * through here and shows as a banner at the top of the app (green for
 * success, red for an error). The UI, the background watch and the
 * notifications all post to it; the banner shows only what is recent, so
 * events from while the app was closed do not flash up later.
 */
object Alerts {
    enum class Kind { SUCCESS, ERROR, INFO }

    data class Alert(val id: Long, val kind: Kind, val title: String?, val text: String, val at: Long = System.currentTimeMillis())

    private val seq = AtomicLong()
    private val recent = HashMap<String, Long>()
    private val _queue = MutableStateFlow<List<Alert>>(emptyList())
    val queue: StateFlow<List<Alert>> = _queue

    /**
     * [throttle]: for automatic sources (background events, a failed load shown on a page): the
     * same words again within 20 s (a retry loop, a list scrolled back into view) show once.
     * What the owner just did (a tap, a wrong PIN) always shows.
     */
    fun post(text: String, kind: Kind = classify(text), title: String? = null, throttle: Boolean = false) {
        if (text.isBlank() && title.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val key = "$title|$text"
        if (throttle) synchronized(recent) {
            recent.entries.removeAll { now - it.value > 20_000 }
            if (recent.containsKey(key)) return
            recent[key] = now
        }
        val a = Alert(seq.incrementAndGet(), kind, title, text)
        _queue.update { q -> (q + a).takeLast(4) }
    }

    fun success(text: String, title: String? = null) = post(text, Kind.SUCCESS, title)
    fun error(text: String, title: String? = null) = post(text, Kind.ERROR, title)

    fun dismiss(id: Long) = _queue.update { q -> q.filter { it.id != id } }

    private val FAILURE = Regex(
        "(?i)(^not |\\bnot (sent|placed|modified|moved|closed|cancelled|deleted|saved|allowed)\\b|\\bfail|\\berror\\b|\\brefus|\\breject|" +
            "could not|cannot|can't|couldn't|\\bblocked\\b|\\bdenied\\b|\\binvalid\\b|insufficient|\\bexpired\\b|\\blapsed\\b|" +
            "\\bno (quote|price|contract|session|bars|data)\\b|\\bkill switch is on\\b|\\blimit reached\\b|\\bcompromise|\\bmissing\\b|\\bstopped by android\\b)")

    /** Plain words decide the colour when the caller did not: refusals and failures are red. */
    fun classify(text: String): Kind = if (FAILURE.containsMatchIn(text)) Kind.ERROR else Kind.SUCCESS
}
