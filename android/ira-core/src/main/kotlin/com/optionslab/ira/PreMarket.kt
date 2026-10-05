package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * The pre-market checklist (usefulness, round 12, 2026-10-05): "am I ready to trade?", "pre-market checklist", "go through
 * my checklist" - Boss at 9:00 hears whether the app and his own setup are ready for the day, each item pass or fail and,
 * when it fails, the fix in words as a step HE takes:
 *
 *  - the app: Zerodha logged in for today, the static IP and the relay, live prices flowing, the AI model, the voice;
 *  - his setup: the kill switch, the guards (a daily loss limit and a max trades a day), the bots armed against the ones
 *    he usually has armed ([usualArmed], from the days recorded), today's events and expiries (a note, never a fail);
 *  - his account, on an unlocked phone only ([Account]): the margin available against his usual position size
 *    ([usualSize], from his own Zerodha trades) and any positions carried overnight.
 *
 * Words only: nothing here places, changes, closes or arms anything - a fix is always said as a step Boss does himself.
 * On a locked phone the account items are left out (said so) and no amount is said. The same checks extend the 09:00
 * morning check (DailyReports.morning), which already holds the app's part. Pure.
 */
object PreMarket {
    enum class Part(val label: String) {
        LOGIN("Zerodha login"), STATIC_IP("Static IP"), RELAY("Relay server"), PRICES("Live prices"), MODEL("AI model"),
        VOICE("Voice"), KILL("Kill switch"), GUARDS("Guards"), BOTS("Bots"), TODAY("Today"), MARGIN("Margin"),
        CARRIED("Overnight positions"),
    }

    /** One item: [ok] true passes, false fails (then [fix] is the step Boss takes), null is a note (never counted). */
    data class Check(val part: Part, val ok: Boolean?, val text: String, val fix: String? = null)

    /**
     * The app and Boss's setup, read by the app. [staticIp]: null when no static IP is registered, else whether the phone
     * is on it now; [relay]: null when no relay is set up, else whether it answers. [pricesMissing]: the markets whose
     * live feed did not answer. [voiceWanted]/[voiceProblem]/[mic]: the voice switch, its problem and the microphone
     * permission. [armed]: the bots armed now; [usual]: those he usually has armed ([usualArmed]; null: too few days
     * recorded). [today]: today's events and expiries, already in words.
     */
    data class Setup(
        val configured: Boolean, val loggedIn: Boolean,
        val staticIp: Boolean?, val relay: Boolean?,
        val pricesMissing: List<String>,
        val modelReady: Boolean,
        val voiceWanted: Boolean, val voiceProblem: String?, val mic: Boolean,
        val kill: Boolean, val live: Boolean,
        val dailyLoss: Double, val maxTrades: Int,
        val armed: Set<String>, val usual: Set<String>?,
        val today: List<String>,
    )

    /** Boss's account (read only on an unlocked phone): margin [available] (null: not read), his [usualSize] (null: no record), the [carried] positions in words. */
    data class Account(val available: Double?, val usualSize: Double?, val carried: List<String>)

    private fun rs(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, x)
    private fun list(xs: Collection<String>) = xs.sorted().let { if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString(", ") + " and " + it.last() }

    /** Every check, in the order Boss runs down them. [locked]: no amount is said (a locked phone may be heard). */
    fun checks(s: Setup, a: Account?, locked: Boolean): List<Check> {
        val out = ArrayList<Check>()
        out += when {
            !s.configured -> Check(Part.LOGIN, null, "Zerodha is not set up: paper only today.")
            s.loggedIn -> Check(Part.LOGIN, true, "Zerodha is logged in for today.")
            else -> Check(Part.LOGIN, false, "Zerodha is not logged in today.", "Log in yourself in Settings, Zerodha, before 9:15.")
        }
        s.staticIp?.let { on ->
            out += if (on) Check(Part.STATIC_IP, true, "The phone is on your registered static IP.")
            else Check(Part.STATIC_IP, false, "The phone is not on your registered static IP: Zerodha will refuse new live orders.",
                "Switch the phone to the connection your static IP is on (or turn your VPN on) yourself.")
        }
        s.relay?.let { on ->
            out += if (on) Check(Part.RELAY, true, "The relay server answers.")
            else Check(Part.RELAY, false, "The relay server is not answering: the Zerodha login and live orders will fail.",
                "Check the relay server is running and reachable yourself.")
        }
        out += if (s.pricesMissing.isEmpty()) Check(Part.PRICES, true, "Live prices are reaching the app.")
        else Check(Part.PRICES, false, "No live prices from ${list(s.pricesMissing)}.",
            "Check the phone's internet and keep the app open; if it stays, open the app's market watch yourself.")
        out += if (s.modelReady) Check(Part.MODEL, true, "The AI model is loaded.")
        else Check(Part.MODEL, false, "The AI model is not on the phone: I answer from my rules only.", "Download it yourself in Settings, Voice and AI model.")
        out += when {
            !s.voiceWanted -> Check(Part.VOICE, null, "Voice is switched off: I answer in the chat only.")
            !s.mic -> Check(Part.VOICE, false, "Voice is on but the microphone permission is missing.", "Allow the microphone for the app yourself in Android's settings.")
            s.voiceProblem != null -> Check(Part.VOICE, false, "Voice is not working: ${s.voiceProblem.trimEnd('.')}.", "Switch the voice off and on again yourself in Settings, Voice and AI model.")
            else -> Check(Part.VOICE, true, "Voice is working.")
        }
        out += if (!s.kill) Check(Part.KILL, true, "The kill switch is off.")
        else Check(Part.KILL, false, "The kill switch is ON: every Zerodha order is refused (paper still trades).",
            "If you mean to trade on Zerodha today, switch it off yourself in Settings, Guards; if you meant it, leave it.")
        val noLoss = s.dailyLoss <= 0.0; val noTrades = s.maxTrades <= 0
        out += when {
            noLoss && noTrades -> Check(Part.GUARDS, false, "No daily loss limit and no max trades a day are set.", "Set both yourself in Settings, Guards.")
            noLoss -> Check(Part.GUARDS, false, "No daily loss limit is set.", "Set one yourself in Settings, Guards.")
            noTrades -> Check(Part.GUARDS, false, "No max trades a day is set.", "Set one yourself in Settings, Guards.")
            locked -> Check(Part.GUARDS, true, "Your daily loss limit and max trades a day are set.")
            else -> Check(Part.GUARDS, true, "Daily loss limit ${rs(s.dailyLoss)} and at most ${s.maxTrades} trades a day are set.")
        }
        out += bots(s.armed, s.usual)
        out += Check(Part.TODAY, null, if (s.today.isEmpty()) "No events or expiries today." else s.today.joinToString("; ") { it.trimEnd('.') } + ".")
        if (a != null && !locked) {
            val size = a.usualSize
            out += when {
                a.available == null -> Check(Part.MARGIN, null, "I could not read your margin just now.")
                size == null || size <= 0.0 -> Check(Part.MARGIN, null, "${rs(a.available)} margin available; no Zerodha trades yet to tell your usual size.")
                a.available >= size -> Check(Part.MARGIN, true, "${rs(a.available)} margin available, enough for your usual position of about ${rs(size)}.")
                else -> Check(Part.MARGIN, false, "${rs(a.available)} margin available, less than your usual position of about ${rs(size)}.",
                    "Add funds in Zerodha yourself, or take a smaller position than usual.")
            }
            out += if (a.carried.isEmpty()) Check(Part.CARRIED, true, "No positions carried overnight.")
            else Check(Part.CARRIED, false, "Carried overnight: ${a.carried.joinToString("; ")}.",
                "Decide on ${if (a.carried.size == 1) "it" else "them"} before the open; closing or keeping is yours to do.")
        }
        return out
    }

    /** The bots armed now against the ones he usually has armed; with no usual yet, what is armed as a note. */
    fun bots(armed: Set<String>, usual: Set<String>?): Check {
        val now = if (armed.isEmpty()) "No bots are armed" else "Armed: ${list(armed)}"
        if (usual == null) return Check(Part.BOTS, null, "$now (too few days recorded to know your usual).")
        val missing = usual - armed; val extra = armed - usual
        if (missing.isEmpty() && extra.isEmpty()) return Check(Part.BOTS, true, if (armed.isEmpty()) "No bots are armed, as usual." else "Armed as usual: ${list(armed)}.")
        val said = listOfNotNull(
            missing.takeIf { it.isNotEmpty() }?.let { "usually armed but not now: ${list(it)}" },
            extra.takeIf { it.isNotEmpty() }?.let { "armed but not usually: ${list(it)}" },
        ).joinToString("; ")
        val fix = listOfNotNull(
            missing.takeIf { it.isNotEmpty() }?.let { "arm ${if (it.size == 1) "it" else "them"} yourself if you mean to run ${if (it.size == 1) "it" else "them"} today" },
            extra.takeIf { it.isNotEmpty() }?.let { "disarm ${if (it.size == 1) "it" else "them"} yourself if you did not mean it" },
        ).joinToString(", and ")
        return Check(Part.BOTS, false, "Bots not as usual: $said.", fix.replaceFirstChar { it.uppercase() } + ".")
    }

    /** The checklist in words: the count, the fails first each with its fix, the passes, the notes - and that nothing changed. */
    fun say(checks: List<Check>, locked: Boolean, accountAsked: Boolean = true): String {
        val fails = checks.filter { it.ok == false }; val passes = checks.filter { it.ok == true }; val notes = checks.filter { it.ok == null }
        val n = fails.size + passes.size
        val head = if (fails.isEmpty()) "Pre-market checklist, Boss: all $n checks pass - you are set to trade."
        else "Pre-market checklist, Boss: ${passes.size} of $n pass, ${fails.size} to fix."
        val parts = ArrayList<String>()
        parts += head
        fails.forEach { parts += "✗ ${it.part.label}: ${it.text} Fix: ${it.fix ?: "check it yourself."}" }
        passes.forEach { parts += "✓ ${it.part.label}: ${it.text}" }
        notes.forEach { parts += "• ${it.part.label}: ${it.text}" }
        if (locked && accountAsked) parts += "• Your margin and overnight positions need the phone unlocked."
        parts += "I changed nothing; each fix is yours to make."
        return parts.joinToString("\n")
    }

    // ---- his usual: bots and position size -------------------------------------------------------------------

    /** Days recorded and the share of them a bot must be armed on to count as usual. */
    const val DAYS = 10
    const val MIN_DAYS = 3

    /** The bots armed on more than half of the last [DAYS] recorded days before [today]; null with fewer than [MIN_DAYS] days. */
    fun usualArmed(days: Map<LocalDate, Set<String>>, today: LocalDate): Set<String>? {
        val past = days.filterKeys { it.isBefore(today) }.toSortedMap().values.toList().takeLast(DAYS)
        if (past.size < MIN_DAYS) return null
        return past.flatten().groupingBy { it }.eachCount().filterValues { it * 2 > past.size }.keys
    }

    /** His usual position size: the median value of his last [n] entries (price times quantity); null with none. */
    fun usualSize(values: List<Double>, n: Int = 20): Double? {
        val v = values.filter { it > 0.0 }.takeLast(n).sorted()
        if (v.isEmpty()) return null
        return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
    }

    private const val US = '\u001F'

    /** The armed-days record as kept (a day a line: the date, then the names); the last [DAYS] * 2 days only. */
    fun encode(days: Map<LocalDate, Set<String>>): String = days.toSortedMap().entries.toList().takeLast(DAYS * 2)
        .joinToString("\n") { (d, names) -> (listOf(d.toString()) + names.sorted().map { it.replace("\n", " ").replace(US, ' ') }).joinToString(US.toString()) }

    fun decode(s: String?): Map<LocalDate, Set<String>> = s.orEmpty().lines().mapNotNull { line ->
        val p = line.split(US)
        val d = runCatching { LocalDate.parse(p[0]) }.getOrNull() ?: return@mapNotNull null
        d to p.drop(1).filter { it.isNotBlank() }.toSet()
    }.toMap()

    /** Today's armed bots added to the record (a day seen more than once keeps every bot armed at any of its reads). */
    fun record(kept: String?, today: LocalDate, armed: Set<String>): String {
        val days = decode(kept).toMutableMap()
        days[today] = days[today].orEmpty() + armed
        return encode(days)
    }

    // ---- asked -------------------------------------------------------------------------------------------------

    private fun words(s: String) = " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("'", " ").replace("’", " ").replace("-", " ")) + " "

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |please |ok |okay )*"
    private const val END = "( (today|now|this morning|for today|for the day|for the open))?( boss| jarvis| please)? $"
    private val ASKED = Regex(
        "^ $LEAD(am i|are we|is everything|are things|is the app|is my setup|is everything set) (all )?(ready|set|good) (to|for) (trade|trading|the open|the market|the day)$END|" +
        "^ $LEAD(am i (all )?set|are we (all )?(ready|set))$END|" +
        "^ $LEAD(do |go through |give me |read |read me |say )?(my |the |a |our )?(pre ?market|premarket|morning|pre open|preopen|opening|trading) (check ?list|checks?|readiness( check)?)( please)?$END|" +
        "^ $LEAD(check ?list|readiness check)( please)?$END|" +
        "^ $LEAD(is everything|everything) (ready|set|in place|ok|okay) (for (the open|today|trading|the day))?( boss| jarvis| please)? $|" +
        // Round 9: "are we good to go for the open", "kya main trade ke liye ready hoon", "sab ready hai kya".
        "^ $LEAD(am i|are we|is everything) (all )?good to go( for (the open|trading|today|the day|the market))?$END|" +
        "^ $LEAD(kya )?(main|mai|hum|ham) (aaj )?(trade|trading|market|open) (ke liye|karne ke liye) (ready|taiyar|tayyar|set) (hoon|hu|hun|hain|hai)( kya)?$END|" +
        "^ $LEAD(kya )?(sab|sab kuch|sabkuch) (ready|taiyar|tayyar|set) (hai|hain)( kya)?( (trade|trading|open|market) ke liye)?( kya)?$END|" +
        "^ $LEAD(trade|trading|open|market) ke liye (sab )?(ready|taiyar|tayyar|set) (hoon|hu|hun|hain|hai)( kya)?$END"
    )

    /** "Am I ready to trade?", "pre-market checklist", "go through my morning checklist" - never going live ([Section.READY]). */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean = ASKED.containsMatchIn(words(text))
}
