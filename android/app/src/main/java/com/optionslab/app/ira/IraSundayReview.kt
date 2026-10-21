package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.Market
import com.optionslab.ira.SundayReview
import com.optionslab.ira.WeeklyReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * Jarvis's spoken Sunday review ([SundayReview], TODO I15; 20 Oct, enhancement round 2): the app's side. [sunday] runs in
 * the Sunday 18:00 report's worker ([com.optionslab.app.work.DailyReports.Kind.WEEK], under the "Weekly review" switch),
 * once a Sunday: it reads the week's closed paper round trips (the weekly review's own sources, [IraWeekly.trades]) and the
 * live ones (the Zerodha trade book, [com.optionslab.app.data.TradeBook.trips]) OFF the main thread, puts the review in the
 * chat, and says it - only on an unlocked phone, read the way every reply is ([JarvisVoice.lockedSafe]: unknown counts as
 * locked). A locked phone gets a "Sunday review ready" notification with no figure in it, and the review waits for Boss to
 * ask ("Sunday review": [answer]). A worker's thread, never the order or tick path; it reads books, nothing more, and never
 * touches the position watch. Reads only: nothing is placed, changed, armed or stopped. Not in IraGoldAlgo.
 */
internal object IraSundayReview {
    internal const val NOTIFY_ID = 2036
    /** The Sunday it was last made (a retried or repeated run says nothing twice). */
    internal const val ONCE_KEY = "jarvis.sundayReview.last"
    /** 21 Oct, enhancement round 3 (idea 7): the Sunday it was last said aloud (a catch-up says it once, at most). */
    internal const val SPOKEN_KEY = "jarvis.sundayReview.spoken"
    /** The arms' book waited on at most this long (its pass may hold its lock across a network read). */
    private const val BOOK_MS = 20_000L

    private fun on() = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    /** How the review reached Boss. */
    enum class Delivered { SPOKEN, NOTIFIED, HELD }

    /** A paper trade's arm as it is said: Liquidity 15+5 per book, a Pine script or other arm by its own name, else its group. */
    internal fun armOf(t: WeeklyReview.Trade): String = when (t.group) {
        WeeklyReview.Group.LIQUIDITY -> "Liquidity ${t.name}"
        WeeklyReview.Group.PINE, WeeklyReview.Group.OTHER -> t.name
        else -> t.group.label
    }

    /**
     * The review of the week [today] falls after ([SundayReview.weekFor]), read now off the main thread. A book that could
     * not be read (or the arms' book not in time) is said as unread, never as "no trades".
     */
    internal suspend fun compose(today: LocalDate): SundayReview.Said = withContext(Dispatchers.IO) {
        val monday = SundayReview.weekFor(today)
        val paper = runCatching { withTimeoutOrNull(BOOK_MS) { IraWeekly.trades(monday, whole = true) } }.getOrNull()
            ?.map { SundayReview.Trade(SundayReview.Book.PAPER, armOf(it), it.closed, it.net, it.charges) }
        val live = runCatching {
            val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
            // Real money: the Zerodha trade book only (an unreadable book throws: said as unread).
            com.optionslab.app.data.TradeBook.trips(true).map { t ->
                SundayReview.Trade(SundayReview.Book.LIVE, com.optionslab.app.data.TradeBook.ownerOf(t, owners), t.day, t.net, t.charges)
            }
        }.getOrNull()
        SundayReview.compose(monday, paper, live) { d -> runCatching { Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5) }
    }

    /**
     * Sunday's run: true when the review was made now or already today (the arms' week is then kept to the chat, so the two
     * are never said over each other); false when it is not Sunday, the switch is off, or not in this app.
     */
    suspend fun sunday(context: Context): Boolean {
        if (!on() || !Automations.on(Automations.Auto.WEEK)) return false
        val today = Market.today()
        if (!SundayReview.due(today)) return false
        return withContext(Dispatchers.IO) {
            val p = com.optionslab.app.security.SecurePrefs
            if (runCatching { p.getString(ONCE_KEY) == today.toString() }.getOrDefault(false)) return@withContext true
            val said = compose(today)
            runCatching { p.put(ONCE_KEY, today.toString()) }
            IraHub.note(said.text, from = Automations.Auto.WEEK, whole = true)
            val how = deliver(context, said.text)
            if (how == Delivered.SPOKEN) runCatching { p.put(SPOKEN_KEY, today.toString()) }
            // No figure in the switch's "last did" line (it is shown in Settings).
            runCatching { Automations.acted(Automations.Auto.WEEK, "Sunday review " + if (how == Delivered.SPOKEN) "said" else "ready in the chat") }
            true
        }
    }

    /**
     * [text] said, or not: on a locked phone ([locked], read now; unknown counts as locked) only the plain "ready"
     * notification ([SundayReview.READY_TEXT], no figure) and nothing aloud. Unlocked: said whole (every sentence, none cut
     * to "the rest is in the chat"), through the listening voice when it runs, else the plain voice - each re-reads the lock
     * as it speaks, so a phone locked in between hears no figure. Muted or quiet hours: kept in the chat only.
     */
    internal fun deliver(context: Context, text: String, locked: Boolean = JarvisVoice.lockedSafe()): Delivered {
        if (locked) {
            runCatching { com.optionslab.app.work.Notifier.post(context, NOTIFY_ID, com.optionslab.app.work.Notifier.IRA, SundayReview.READY_TITLE, SundayReview.READY_TEXT, tab = "almanac") }
            return Delivered.NOTIFIED
        }
        if (!com.optionslab.app.BuildConfig.JARVIS) return Delivered.HELD
        // Review: muted or quiet hours first (the listening voice would only pop it up there, and it was logged as said).
        if (JarvisVoice.muted || JarvisVoice.quietNow()) return Delivered.HELD
        val n = SundayReview.sentences(text) + 1
        if (runCatching { JarvisVoice.announce(JarvisSpeaker.words(text, n), full = true, whole = true) }.getOrDefault(false)) return Delivered.SPOKEN
        runCatching { JarvisSpeaker.speak(context, text, n) }
        return Delivered.SPOKEN
    }

    /** One catch-up at a time (the unlock and the app coming on screen can arrive together). */
    private val catching = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 21 Oct, enhancement round 3 (idea 7): the Sunday review said at the next unlocked moment that Sunday evening when it was not
     * said at 18:00 - the phone was off then (its alarm missed; it is made now, once), or locked (only the "ready" notice went).
     * Run on the phone's unlock and when the app comes on screen ([com.optionslab.app.IraAlgoApp]), off the main thread. The
     * lock is read first and again as it speaks ([deliver]): a locked phone (or one whose lock cannot be read) hears nothing, and
     * it stays due. Muted or quiet hours: kept in the chat, as at 18:00. Said once ([SPOKEN_KEY]); never on another day or before
     * 18:00 ([SundayReview.catchUpDue]). Reads only; never the order or tick path, never the position watch.
     */
    suspend fun catchUp(context: Context): Delivered? {
        if (!on() || !Automations.on(Automations.Auto.WEEK)) return null
        if (runCatching { JarvisVoice.lockedSafe() }.getOrDefault(true)) return null
        if (!catching.compareAndSet(false, true)) return null
        try {
            return withContext(Dispatchers.IO) {
                val now = Market.now().toLocalDateTime()
                val today = now.toLocalDate()
                val p = com.optionslab.app.security.SecurePrefs
                val spoken = runCatching { p.getString(SPOKEN_KEY) }.getOrElse { return@withContext null }
                if (!SundayReview.catchUpDue(now, spoken)) return@withContext null
                val made = runCatching { p.getString(ONCE_KEY) == today.toString() }.getOrDefault(false)
                val said = compose(today)
                if (!made) {
                    // The phone was off at 18:00: made now, once, and kept in the chat as the alarm would have.
                    runCatching { p.put(ONCE_KEY, today.toString()) }
                    IraHub.note(said.text, from = Automations.Auto.WEEK, whole = true)
                }
                val how = deliver(context, said.text)
                if (how == Delivered.SPOKEN) {
                    runCatching { p.put(SPOKEN_KEY, today.toString()) }
                    // The "ready" notice from 18:00 is no longer needed.
                    runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTIFY_ID) }
                    runCatching { Automations.acted(Automations.Auto.WEEK, "Sunday review said when you unlocked the phone") }
                }
                how
            }
        } finally { catching.set(false) }
    }

    /** Boss asked ("Sunday review"): the review of the last week; on a locked phone ([locked]) only to unlock (it holds figures). */
    suspend fun answer(locked: Boolean): String {
        if (locked) return "Unlock the phone for that, Boss: the Sunday review has your figures in it."
        val now = Market.now().toLocalDateTime()
        // 21 Oct, round 3: asked for on a Sunday evening, it is not said again by the catch-up at the next unlock.
        if (SundayReview.catchUpDue(now, null)) withContext(Dispatchers.IO) {
            runCatching { com.optionslab.app.security.SecurePrefs.put(SPOKEN_KEY, now.toLocalDate().toString()) }
        }
        return compose(now.toLocalDate()).text
    }
}
