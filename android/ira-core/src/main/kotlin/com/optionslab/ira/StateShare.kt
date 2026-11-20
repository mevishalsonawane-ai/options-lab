package com.optionslab.ira

import java.time.LocalDate

/**
 * 20 Nov 2026 (Boss: "Can we create a button where i could share u all the setting details always which could help you analysis"):
 * the "Share full settings and state" file - ONE plain-text file Boss shares himself from the share sheet, never clipboard text (his
 * 20 Nov note was cut at about 140 lines, so R51 / R72's "Not mirrored today: 8 (last: the account stop rule ...)" never reached it).
 *
 * This is the pure half: the headings in their order, the file's first line, the redaction every line passes through, and the small
 * helpers that pick today's diary lines. The app gathers the lines ([com.optionslab.app.data.SettingsShare]) and writes the file.
 *
 * Privacy: P&L figures may appear (they are Boss's own, and he shares the file himself), but never a token, key or secret, the access
 * token, the PIN, the relay's address or any IP, the Zerodha user or client id, a phone number, an e-mail or a name ([redact]).
 */
object StateShare {
    /** The file's first line, always. */
    const val TOP_LINE = "Shared by you; contains your P&L and settings; no keys or tokens"

    /** The headings, in the file's order (the tests check every one is there). */
    const val HEADER = "1. Header"
    const val ARMS = "2. Live gates per arm"
    const val SWITCHES = "3. Every switch in Bot settings and Settings"
    const val STOPS = "4. Stops and limits that can block entries"
    const val REPORT = "5. Diagnostics report (in full)"
    const val DECISIONS = "6. Today's decisions per arm"
    const val DIARY = "7. Today's diary by category"
    val HEADINGS: List<String> = listOf(HEADER, ARMS, SWITCHES, STOPS, REPORT, DECISIONS, DIARY)

    /** The diary categories copied in full for today (each bounded to [DIARY_PER_AREA] lines). */
    val DIARY_AREAS: List<String> = listOf("armlive", "orb", "passed", "passed-arms", "exits", "orders", "autostops", "risk", "funds",
        "step-trail", "liquidity", "arms", "mode")
    const val DIARY_PER_AREA = 300
    /** Each arm's notes in section 6: its last this-many lines of today. */
    const val NOTES_PER_ARM = 50

    const val MASK = "‹redacted›"
    const val LOGGED_IN_MASK = "‹logged in›"

    /** One part of the file: its heading and its lines (a line may hold line breaks; each becomes its own line). */
    data class Section(val title: String, val lines: List<String>)

    /** The file's text: [TOP_LINE], then each section under "== title", every line redacted ([redact], with [known]). */
    fun build(sections: List<Section>, known: Collection<String> = emptyList()): String {
        val sb = StringBuilder()
        sb.append(TOP_LINE).append('\n')
        for (s in sections) {
            sb.append('\n').append("== ").append(s.title).append('\n')
            val lines = s.lines.flatMap { it.split('\n') }.map { it.trimEnd() }
            if (lines.none { it.isNotBlank() }) sb.append("(none)\n")
            else for (l in lines) sb.append(redact(l, known)).append('\n')
        }
        return sb.toString()
    }

    /** The file's name for [day]. */
    fun fileName(day: LocalDate): String = "iraalgo-settings-and-state-$day.txt"

    // ---- redaction --------------------------------------------------------------------------------------------------------------

    /** Option and future trading symbols (MIDCPNIFTY27OCT2613250PE is 24 characters): never a key, kept as they are. */
    private val TRADING_SYMBOL = Regex("\\b[A-Z]{2,20}\\d{2}[A-Z]{3}\\d{2}(?:\\d+(?:\\.\\d+)?(?:CE|PE)|FUT)\\b")
    /** Reason codes of lowercase words and underscores (no_entry_window): kept, as the diary keeps them. */
    private val REASON_CODE = Regex("\\b[a-z]+(?:_[a-z]+)+\\b")
    /** A reason code is kept only when one of its words is a decision word, as the diary's own redaction keeps it (a passphrase is not). */
    private val REASON_WORDS = setOf("no", "not", "after", "before", "outside", "entry", "entries", "window", "break", "liquidity", "room",
        "square", "off", "flat", "refused", "skipped", "signal", "cap", "lock", "day", "time", "hours", "new", "now", "waiting", "closed",
        "open", "expiry", "quote", "price", "stop", "target", "trail", "guard", "held", "holding", "missed",
        // 20 Nov 2026 (review): the app's own alert and order words too ("flip_outgoing_exit_rejected" is a strategy's stuck-exit kind).
        "exit", "exits", "rejected", "failed", "leg", "legs", "run", "order", "orders", "sent", "fill", "filled", "cancelled", "skip",
        "live", "paper", "shadow", "limit", "loss", "profit", "read", "error", "timeout", "missing", "stale", "flip", "outgoing")
    private fun reasonCode(m: MatchResult): Boolean = m.value.split('_').any { it in REASON_WORDS }

    // Ordered: the specific shapes first, then the generic catch-alls.
    private val KEY_VALUE = Regex("(?i)\\b(access[_ -]?token|request[_ -]?token|refresh[_ -]?token|enctoken|api[_ -]?key|api[_ -]?secret|" +
        "secret|password|passwd|pwd|otp|totp|authorization|cookie|user[_ -]?id|client[_ -]?id|account[_ -]?id|private[_ -]?key|" +
        "public[_ -]?key|host[_ -]?key|relay[_ -]?host|topic)\\b[\"']?\\s*[:=]\\s*[\"']?((?:(?:bearer|token|basic)\\s+)?[^\\s\"',;&)]+)")
    /** "PIN is 4821", "pin: 4821", "my otp was 123456": only a number after the word (never "PIN at arming"). */
    private val PIN_NUMBER = Regex("(?i)\\b(pin|otp|totp|passcode)\\b(?:\\s+(?:is|was|number|no|code))*\\s*[:=]?\\s*\\d{4,8}\\b")
    /**
     * "Bearer <value>" / "token <value>" whose value holds a letter and a digit (never "token refreshed", nor an instrument's number,
     * "token 25626500").
     */
    private val AUTH_HEADER = Regex("(?i)\\b(bearer|token)\\s+(?=[A-Za-z0-9._~+/=:-]*\\d)(?=[0-9._~+/=:-]*[A-Za-z])[A-Za-z0-9._~+/=:-]{8,}")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    /** Any URL with a scheme (the relay's, a webhook's, an ntfy server's): the whole of it. */
    private val URL = Regex("(?i)\\b(?:https?|wss?|ssh|sftp)://[^\\s\"'<>)]+")
    /** user@host as ssh writes it, and e-mail addresses. */
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val USER_AT_HOST = Regex("\\b[a-z_][a-z0-9_-]{0,31}@(?=[A-Za-z0-9.-]*[A-Za-z])[A-Za-z0-9.-]+\\b")
    /** IPv4 (with a port); IPv6 with four or more groups, or with "::" (a clock time, 10:15:00, has three groups and is kept). */
    private val IPV4 = Regex("(?<![\\d.])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?::\\d{1,5})?(?![\\d.])")
    private val IPV6 = Regex("(?<![0-9A-Za-z:])(?:(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}|(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)(?![0-9A-Za-z:])")
    /**
     * Zerodha client ids: two capital letters and four to six digits (AB1234), or three and three to six (XYZ123); never a year
     * ("FY2026", "CY2025"), an indicator's length, a strike word or an exchange ("EMA200", "ATM100", "NSE500"; "MA200", "SL500" are short).
     */
    private val CLIENT_ID = Regex("\\b(?:(?!(?:FY|CY|AY)\\d)[A-Z]{2}\\d{4,6}|(?!(?:IST|UTC|EMA|SMA|DMA|WMA|HMA|ATR|RSI|ADX|NSE|BSE|MCX|ATM|OTM|ITM|VIX|ORB|CCI|MFI|OBV|ROC|TSI)\\d)[A-Z]{3}\\d{3,6})\\b")
    /** An ntfy topic, as a path on its server or after the word ("ntfy.sh/boss-topic", "topic boss-topic"; never "topic set"). */
    private val NTFY_PATH = Regex("(?i)\\b(ntfy\\.sh)/[^\\s\"'<>)]+")
    /** (Only a word shaped like a topic - with a digit, '-', '_' or '.' - never "the topic can post".) */
    private val TOPIC_WORD = Regex("(?i)\\b(topic)\\s+(?=[^\\s,;)]*[0-9_.-])(?=[^\\s(*]*[A-Za-z0-9])[^\\s,;)]+")
    /**
     * A host name with no scheme (the relay's "relay.example.net:22/path"), with its port and path: masked unless it is one of the public
     * services the app talks to by name (Zerodha, Upstox, NSE, GitHub, ntfy). The app's own class names never match (a package's start
     * is left out, and the name must end the word).
     */
    private val HOST = Regex("(?<![\\w.@/-])(?!(?:com\\.optionslab|java|javax|android|androidx|kotlin|kotlinx|org\\.json|dalvik)\\.)" +
        "((?:[A-Za-z0-9-]+\\.)+(?:com|net|org|io|in|dev|app|cloud|xyz|me|co|trade|ai|info|biz|tech|site|online|host|lan|local|internal|home|" +
        "de|uk|eu|cc|link|us|ca|au|fr|nl|ru|jp|cn|sg|ly|gg|to|tv|pro|space|store|top|club|vip|work|world|live|fun|run|page|duckdns))" +
        // (A full stop that ends the sentence still ends the name: "connected to relay.example.net.")
        "(?!\\w|\\.\\w)((?::\\d{1,5})?(?:/[^\\s\"'<>)]*)?)")
    private val PUBLIC_HOSTS = setOf("kite.zerodha.com", "zerodha.com", "api.kite.trade", "ws.kite.trade", "kite.trade", "upstox.com",
        "api.upstox.com", "assets.upstox.com", "nseindia.com", "www.nseindia.com", "nsearchives.nseindia.com", "github.com", "api.github.com",
        "raw.githubusercontent.com", "objects.githubusercontent.com", "uploads.github.com", "ntfy.sh", "console.zerodha.com",
        "developers.kite.trade", "huggingface.co", "api.ipify.org", "ifconfig.me", "www.google.com", "nse.in")
    /** Indian phone numbers, with or without +91, and written 98765 43210 / 98765-43210. */
    private val PHONE = Regex("(?<![\\d.,])(?:\\+?91[ -]?)?[6-9]\\d{9}(?![\\d.,])")
    private val PHONE_SPLIT = Regex("(?<![\\d.,])(?:\\+?91[ -]?)?[6-9]\\d{4}[ -]\\d{5}(?![\\d.,])")
    /** Long hex runs (keys, hashes) and long runs of letters and digits (tokens). */
    private val HEX = Regex("\\b[A-Fa-f0-9]{16,}\\b")
    private val LONG_RUN = Regex("[A-Za-z0-9_\\-+/=]{24,}")
    /** Long digit runs (account numbers, ids); rupee figures are written with commas and are shorter. */
    private val LONG_DIGITS = Regex("(?<![\\d.,])\\d{11,}(?![\\d.,])")

    /**
     * [text] with every secret-looking thing replaced: each value in [known] (the app's own key, user id, user name, access token,
     * relay host, static IP, ntfy topic...) wherever it appears, case-insensitively; then the shapes above. Trading symbols and
     * reason codes are kept. A user or client id is shown as [LOGGED_IN_MASK]: the file says "logged in", never who.
     */
    fun redact(text: String, known: Collection<String> = emptyList()): String {
        if (text.isEmpty()) return text
        // Kept aside first, so no pattern below ever touches them (put back at the end).
        val kept = ArrayList<String>()
        fun keep(m: MatchResult): String { kept += m.value; return "\u0000${kept.size - 1}\u0000" }
        var out = text
        // Whole words only ("root" never masks "rooted"); the value itself may hold any characters.
        // (Three letters at least: a two-letter name part would blank "vs" or "RAM" all through the file.)
        for (k in known.map { it.trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length })
            out = Regex("(?<![A-Za-z0-9])" + Regex.escape(k) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).replace(out, MASK)
        out = KEY_VALUE.replace(out) { m -> "${m.groupValues[1]}=$MASK" }
        out = PIN_NUMBER.replace(out) { m -> "${m.groupValues[1]}=$MASK" }
        out = AUTH_HEADER.replace(out) { m -> "${m.groupValues[1]} $MASK" }
        out = JWT.replace(out, MASK)
        out = URL.replace(out, MASK)
        out = EMAIL.replace(out, MASK)
        out = NTFY_PATH.replace(out) { m -> "${m.groupValues[1]}/$MASK" }
        out = TOPIC_WORD.replace(out) { m -> "${m.groupValues[1]} $MASK" }
        out = HOST.replace(out) { m -> if (m.groupValues[1].lowercase() in PUBLIC_HOSTS) m.value else MASK }
        out = USER_AT_HOST.replace(out, MASK)
        out = IPV4.replace(out, MASK)
        out = IPV6.replace(out, MASK)
        out = TRADING_SYMBOL.replace(out, ::keep)
        out = REASON_CODE.replace(out) { m -> if (reasonCode(m)) keep(m) else m.value }
        out = CLIENT_ID.replace(out, LOGGED_IN_MASK)
        out = PHONE.replace(out, MASK)
        out = PHONE_SPLIT.replace(out, MASK)
        out = HEX.replace(out, MASK)
        out = LONG_RUN.replace(out, MASK)
        out = LONG_DIGITS.replace(out, MASK)
        return if (kept.isEmpty()) out else Regex("\u0000(\\d+)\u0000").replace(out) { m -> kept[m.groupValues[1].toInt()] }
    }

    // ---- the diary's lines ------------------------------------------------------------------------------------------------------

    /** The diary's area of [line] ("11-20 10:15:00 [orb] text" -> "orb"); null for a line of another shape. */
    fun areaOf(line: String): String? = DIARY_LINE.find(line)?.groupValues?.get(2)
    private val DIARY_LINE = Regex("^(\\d{2}-\\d{2}) \\d{2}:\\d{2}:\\d{2} \\[([^\\]]+)]")

    /** [lines] (the diary's, oldest first) of [day] only. */
    fun today(lines: List<String>, day: LocalDate): List<String> {
        val mmdd = "%02d-%02d".format(day.monthValue, day.dayOfMonth)
        return lines.filter { DIARY_LINE.find(it)?.groupValues?.get(1) == mmdd }
    }

    /** The last [max] of [lines] in [area] (oldest first). */
    fun inArea(lines: List<String>, area: String, max: Int = DIARY_PER_AREA): List<String> = lines.filter { areaOf(it) == area }.takeLast(max)

    /** The last [max] of [lines] that name [label] (case aside; oldest first). */
    fun naming(lines: List<String>, label: String, max: Int = NOTES_PER_ARM): List<String> {
        if (label.isBlank()) return emptyList()
        return lines.filter { it.contains(label, ignoreCase = true) }.takeLast(max)
    }

    /**
     * A data class's toString() ("AppSettings(a=1, b=x, c=[p, q])") as one "name: value" line per field, in its order; null when
     * [text] is not of that shape. Field names in the text are the source's own (a shrunk build keeps them: Kotlin writes them as text).
     */
    fun fields(text: String): List<String>? {
        val open = text.indexOf('(')
        if (open <= 0 || !text.endsWith(")")) return null
        val body = text.substring(open + 1, text.length - 1)
        if (body.isBlank()) return emptyList()
        val parts = FIELD_START.split(body)
        return parts.map { p -> p.trim().let { kv -> val i = kv.indexOf('='); if (i > 0) "${kv.substring(0, i)}: ${kv.substring(i + 1)}" else kv } }
    }
    private val FIELD_START = Regex(", (?=[A-Za-z_][A-Za-z0-9_]*=)")
}
