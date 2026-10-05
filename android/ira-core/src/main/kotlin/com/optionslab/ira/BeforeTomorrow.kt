package com.optionslab.ira

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * "What do I need to do before tomorrow?" (usefulness round 23, 2026-10-05): one checklist for the next trading day, facts
 * only - each item a step Boss takes himself, or that it is fine:
 *
 *  - the Zerodha login (each lasts one day; the 09:00 morning check reminds him), or that the app is on Paper;
 *  - his legs, paper and Zerodha, that expire on the next trading day ([ExpiryEve]) - saying when Zerodha was not read;
 *  - the arms and bots armed now, so running then, each with its paper record (and its tested win rate when known);
 *  - the static IP and the relay server, when set up;
 *  - the battery setting (Android may stop an app that is not Unrestricted overnight);
 *  - how old the last backup is ([BackupNudge]).
 *
 * Words only: nothing here places, changes, closes, arms or disarms anything. Boss's account (his legs, the arms' records)
 * only on an unlocked phone; on a locked one no amount is said and those are left out, said so. Pure.
 */
object BeforeTomorrow {
    /** An armed arm or bot's paper record: [trades] closed, [wins], [net] rupees; [testedWinRate] 0..1 when its test is known. */
    data class Record(val trades: Int, val wins: Int, val net: Double, val testedWinRate: Double? = null)

    /**
     * What the app read. [next]: the next trading day (null: not known). [configured]/[live]: Zerodha set up, the app on
     * Live. [expiring]: his legs expiring on [next] (null: not read - a locked phone). [loggedIn]: Zerodha logged in now;
     * [zerodhaRead]: its positions were actually read. [armed]: names armed now; [records]: their paper records by name
     * (null: not read). [staticIp]/[relay]/[battery]: null when not set up or not known. [lastBackup]: null when never.
     * [undated]: open Zerodha positions read but not in the instruments on the phone, so their expiry couldn't be told.
     */
    data class Facts(
        val today: LocalDate, val next: LocalDate?,
        val configured: Boolean, val live: Boolean,
        val expiring: List<ExpiryEve.Leg>?, val loggedIn: Boolean, val zerodhaRead: Boolean,
        val armed: List<String>, val records: Map<String, Record>?,
        val staticIp: Boolean?, val relay: Boolean?, val battery: Boolean?,
        val lastBackup: LocalDate?,
        val undated: Int = 0,
    )

    /** One item: [todo] true is a step Boss takes, false is fine, null a note. */
    data class Item(val label: String, val todo: Boolean?, val text: String)

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun list(xs: List<String>) = if (xs.size <= 1) xs.joinToString() else xs.dropLast(1).joinToString(", ") + " and " + xs.last()

    /** "tomorrow (Tue 6 Oct)" when the next trading day is tomorrow, else "the next trading day (Mon 12 Oct)". */
    fun dayWords(today: LocalDate, next: LocalDate): String =
        (if (next == today.plusDays(1)) "tomorrow" else "the next trading day") + " (${next.format(DAY)})"

    /** Every item, in the order Boss runs down them. [locked]: no amount, no legs, no records. */
    fun items(f: Facts, locked: Boolean): List<Item> {
        val next = f.next ?: return emptyList()
        val day = dayWords(f.today, next)
        val out = ArrayList<Item>()
        out += when {
            !f.configured -> Item("Zerodha login", null, "Zerodha isn't set up: $day is paper only.")
            f.live -> Item("Zerodha login", true, "Log in to Zerodha yourself before 09:15 $day - a login lasts one day; the 09:00 morning check reminds you.")
            else -> Item("Zerodha login", null, "The app is on Paper: log in to Zerodha $day only if you mean to trade there.")
        }
        val legs = f.expiring
        out += when {
            locked -> Item("Expiring legs", null, "What of yours expires needs the phone unlocked.")
            legs == null -> Item("Expiring legs", null, "I couldn't read what of yours expires $day just now; ask \"what expires tomorrow\" in a minute.")
            legs.isNotEmpty() -> Item("Expiring legs", true,
                "${legs.size} leg${if (legs.size == 1) "" else "s"} of yours expire${if (legs.size == 1) "s" else ""} $day: " +
                    list(legs.sortedWith(compareBy<ExpiryEve.Leg> { it.where }.thenBy { it.symbol }).map { "${it.where} ${it.symbol}" }) +
                    ". Decide on ${if (legs.size == 1) "it" else "them"} yourself; ask \"what expires tomorrow\" for the money and the 15:05 square-off." +
                    (zerodhaGap(f)?.let { " $it" } ?: ""))
            f.loggedIn && !f.zerodhaRead -> Item("Expiring legs", null, "Nothing on Paper expires $day; Zerodha's positions couldn't be read just now, so I can't say the same for them.")
            f.configured && !f.loggedIn -> Item("Expiring legs", false, "Nothing on Paper expires $day; Zerodha isn't logged in, so its positions weren't read.")
            f.configured && f.zerodhaRead && f.undated > 0 -> Item("Expiring legs", null, "Nothing I could date of yours expires $day - Paper read; ${ExpiryEve.undatedLine(f.undated)}.")
            f.configured -> Item("Expiring legs", false, "Nothing you hold expires $day - Paper and Zerodha both read.")
            else -> Item("Expiring legs", false, "Nothing you hold on Paper expires $day.")
        }
        out += if (f.armed.isEmpty()) Item("Armed", null, "Nothing is armed: no arm or bot runs $day unless you arm it.")
        else {
            val names = f.armed.sorted()
            val recs = f.records
            if (locked || recs == null) Item("Armed", null, "Armed now, so running $day: ${list(names)}." + (if (locked) " Their records need the phone unlocked." else ""))
            else Item("Armed", null, "Armed now, so running $day: " + names.joinToString("; ") { n ->
                val r = recs[n]
                if (r == null || r.trades == 0) "$n (no paper trades yet)"
                else "$n (paper: ${r.trades} trade${if (r.trades == 1) "" else "s"}, ${r.wins} won, net ${rs(r.net)}" +
                    (r.testedWinRate?.let { "; its test won ${Math.round(it * 100)}%" } ?: "") + ")"
            } + ". Disarm any you don't mean to run, yourself.")
        }
        f.staticIp?.let { on ->
            out += if (on) Item("Static IP", false, "The phone is on your registered static IP now.")
            else Item("Static IP", true, "The phone isn't on your registered static IP: Zerodha refuses new live orders off it. Switch the connection (or your VPN) yourself before 09:15.")
        }
        f.relay?.let { on ->
            out += if (on) Item("Relay server", false, "The relay server answers.")
            else Item("Relay server", true, "The relay server isn't answering: the Zerodha login and live orders go through it. Check it is running yourself.")
        }
        f.battery?.let { on ->
            out += if (on) Item("Battery", false, "IraAlgo's battery setting is Unrestricted.")
            else Item("Battery", true, "IraAlgo's battery setting isn't Unrestricted: Android may stop the app overnight, and the 09:00 check and the arms with it. Set it to Unrestricted yourself in Android's settings.")
        }
        out += if (BackupNudge.due(f.lastBackup, f.today)) Item("Backup", true, BackupNudge.say(f.lastBackup))
        else {
            val ago = ChronoUnit.DAYS.between(f.lastBackup, f.today)
            Item("Backup", false, "Your last backup was ${if (ago == 0L) "today" else if (ago == 1L) "yesterday" else "$ago days ago"}.")
        }
        return out
    }

    /** When Zerodha is logged in but its positions weren't read: said, so a Paper-only list is never taken for both. */
    private fun zerodhaGap(f: Facts): String? = when {
        f.loggedIn && !f.zerodhaRead -> "(Zerodha's positions couldn't be read just now: only Paper's are listed.)"
        f.configured && !f.loggedIn -> "(Zerodha isn't logged in, so only Paper's are listed.)"
        f.zerodhaRead && f.undated > 0 -> "(${ExpiryEve.undatedLine(f.undated)}.)"
        else -> null
    }

    /** The checklist in words: the count, the steps first, then what is fine, then the notes - and that nothing changed. */
    fun say(f: Facts, locked: Boolean): String {
        val next = f.next ?: return "I couldn't tell the next trading day just now, Boss, so I can't say what's needed before it."
        val all = items(f, locked)
        val todo = all.filter { it.todo == true }; val fine = all.filter { it.todo == false }; val notes = all.filter { it.todo == null }
        val head = "Before ${dayWords(f.today, next)}, Boss: " +
            (if (todo.isEmpty()) "nothing to do." else "${todo.size} thing${if (todo.size == 1) "" else "s"} to do.")
        val parts = ArrayList<String>()
        parts += head
        todo.forEach { parts += "✗ ${it.label}: ${it.text}" }
        fine.forEach { parts += "✓ ${it.label}: ${it.text}" }
        notes.forEach { parts += "• ${it.label}: ${it.text}" }
        parts += "Facts only - I changed nothing; each step is yours."
        return parts.joinToString("\n")
    }


    // ---- asked ---------------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |please |ok |okay )*"
    private const val TAIL = "( boss| jarvis| please)* $"
    /** The next day named: tomorrow, the next session, tomorrow's open. */
    private const val NEXT = "(tomorrow|tmrw|tomorrows open|tomorrows session|the next session|the next trading day|tomorrow morning|the open tomorrow)"
    private val ASKED = Regex(
        // "what do I need to do before tomorrow", "what do i have to do for tomorrow", "what should i sort out before tomorrow"
        "^ $LEAD(what|anything|is there anything)( else)? (do i|i|should i|must i|have i got to|that i)? ?(need to|have to|should|must|gotta|got to|ought to)? ?" +
            "(do|sort out|sort|take care of|check|fix|handle|get done|set up|remember)( first)? (before|for|ahead of) $NEXT$TAIL|" +
        // "anything to do before tomorrow", "things to do before tomorrow"
        "^ $LEAD(anything|is there anything|things|stuff|what) (left )?to (do|sort out|take care of|check) (before|for|ahead of) $NEXT$TAIL|" +
        // "tomorrow's checklist", "checklist for tomorrow", "my to do list for tomorrow", "to do before tomorrow"
        "^ $LEAD(give me |read me |say |go through )?(my |the |a )?(tomorrows|tmrws) (check ?list|to ?do( list)?|to dos)$TAIL|" +
        "^ $LEAD(give me |read me |say |go through )?(my |the |a )?(check ?list|to ?do( list)?|to dos) (before|for) $NEXT$TAIL|" +
        // Hinglish: "kal se pehle kya karna hai", "kal ke liye kya karna hai", "kal ke liye kuch karna hai kya"
        "^ $LEAD(mujhe )?(kal|kal subah) (se pehle|ke liye|se pahle) (mujhe )?(kya|kya kya|kuch) (karna|karna padega|karna hoga|karna chahiye)( hai| he| h)?( kya)?$TAIL|" +
        "^ $LEAD(mujhe )?(kya|kya kya|kuch) (karna|karna padega|karna hoga|karna chahiye)( hai| he| h)? (kal|kal subah) (se pehle|ke liye|se pahle)( kya)?$TAIL"
    )
    /** Never this: an order or an act, a market forecast, a reminder, levels, expiry (ExpiryEve's), the plan (Reminder.tomorrow's). */
    private val NOT = rx(" (buy|sell|trade|trades|order|orders|close|exit|square|hedge|nifty|banknifty|bank nifty|levels|level|expire|expires|expiring|expiry|remind|reminder|alert|plan|outlook|ready|prepare) ")

    /** "What do I need to do before tomorrow?", "checklist for tomorrow", "kal se pehle kya karna hai". */
    fun asked(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        return ASKED.containsMatchIn(t)
    }
}
