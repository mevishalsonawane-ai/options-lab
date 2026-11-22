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
 * 22 Nov 2026 (B6 review): Zerodha's order ids stay (not secrets, and needed to match a line against Kite's order book: [orderId]);
 * any other long run of digits (a bank account, a demat id) is still masked.
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

    /** The file's text: [TOP_LINE], then each section under "== title", every line redacted ([redact], with [known] and [year]). */
    fun build(sections: List<Section>, known: Collection<String> = emptyList(), year: Int = LocalDate.now().year): String {
        val sb = StringBuilder()
        sb.append(TOP_LINE).append('\n')
        for (s in sections) {
            sb.append('\n').append("== ").append(s.title).append('\n')
            val lines = s.lines.flatMap { it.split('\n') }.map { it.trimEnd() }
            if (lines.none { it.isNotBlank() }) sb.append("(none)\n")
            else for (l in lines) sb.append(redact(l, known, year)).append('\n')
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
     * 22 Nov 2026 (B6 review): nor an option's side and strike ("CE24500", "PE52000") or an F&O segment ("NFO1234"); the account's
     * own id is masked wherever it appears in any case (the app hands it in as a known value).
     * 22 Nov 2026 (B6 review, second look): only a side with a strike's shape (hundreds or fifties, "CE24500", "PE4850") is kept -
     * "PE4821" is masked.
     */
    private val CLIENT_ID = Regex("\\b(?:(?!(?:FY|CY|AY)\\d|(?:CE|PE)\\d{2,4}(?:00|50)\\b)[A-Z]{2}\\d{4,6}|(?!(?:IST|UTC|EMA|SMA|DMA|WMA|HMA|ATR|RSI|ADX|NSE|BSE|MCX|NFO|BFO|CDS|ATM|OTM|ITM|VIX|ORB|CCI|MFI|OBV|ROC|TSI)\\d)[A-Z]{3}\\d{3,6})\\b")
    /** An ntfy topic, as a path on its server or after the word ("ntfy.sh/boss-topic", "topic boss-topic"; never "topic set"). */
    private val NTFY_PATH = Regex("(?i)\\b(ntfy\\.sh)/[^\\s\"'<>)]+")
    /** (Only a word shaped like a topic - with a digit, '-', '_' or '.' - never "the topic can post".) */
    private val TOPIC_WORD = Regex("(?i)\\b(topic)\\s+(?=[^\\s,;)]*[0-9_.-])(?=[^\\s(*]*[A-Za-z0-9])[^\\s,;)]+")
    /**
     * A host name with no scheme (the relay's "relay.example.net:22/path"), with its port and path: masked unless it is one of the public
     * services the app talks to by name (Zerodha, Upstox, NSE, GitHub, ntfy). The app's own class names never match (a package's start
     * is left out, and the name must end the word).
     * 22 Nov 2026 (B6 review): any ending that is a country's two letters or a name ending ([HOST_ENDINGS]), not a fixed list of a few
     * dozen. The app's own words never match ([hostLike]): a two-part name with a capital letter ("R72.live", "OrbArms.view") is not a
     * host unless a port or path follows it, nor is a two-part file's name ("wg-relay-setup.sh", "orb.vault") unless one does.
     * 22 Nov 2026 (B6 review, second look): a name with a '-' after it is masked too ("vps.example.com-2"); and a two-part name with a
     * capital letter is a host after all when its ending is a web name's ([NAME_ENDINGS], any case: "MyRelay.com") or a country's written
     * small ("MyBox.in"; "NIFTY.IN" stays).
     */
    private val HOST = Regex("(?<![\\w.@/-])(?!(?:com\\.optionslab|java|javax|android|androidx|kotlin|kotlinx|org\\.json|dalvik)\\.)" +
        "((?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+([A-Za-z]{2,24}))" +
        // (A full stop that ends the sentence still ends the name: "connected to relay.example.net.")
        "(?!\\w|\\.\\w)((?::\\d{1,5})?(?:/[^\\s\"'<>)]*)?)")
    /** A host's last part that marks a real name: any two letters (a country; a file's only with a port or path after it), or one of these. */
    private val HOST_ENDINGS = setOf("com", "net", "org", "edu", "gov", "mil", "int", "arpa", "info", "biz", "dev", "app", "cloud", "xyz",
        "tech", "site", "online", "host", "lan", "local", "localdomain", "internal", "home", "link", "trade", "ovh", "zone", "network",
        "systems", "click", "icu", "shop", "website", "digital", "email", "services", "solutions", "studio", "onion", "test", "example",
        "invalid", "corp", "private", "duckdns", "pro", "space", "store", "top", "club", "vip", "work", "world", "live", "fun", "run", "page")
    /** 22 Nov 2026 (B6 review): endings that mark a host even in a two-part name with a capital letter (never an arm's word, "R72.live"). */
    private val NAME_ENDINGS = setOf("com", "net", "org", "edu", "gov", "mil", "arpa", "info", "biz", "xyz", "cloud", "online", "site",
        "tech", "duckdns", "onion")
    /** Two-letter endings that are also a file's (a script, a source file): a two-part name's only with a port or a path after it. */
    private val FILE_ENDINGS = setOf("sh", "py", "js", "ts", "kt", "md", "rs", "go", "rb", "pl", "cs", "gz", "xz", "so", "db", "ps", "mk")

    /** Whether [m] (a [HOST] match) is a host name to mask: never a public service's, nor one of the app's own words. */
    private fun hostLike(m: MatchResult): Boolean {
        val name = m.groupValues[1]; val ending = m.groupValues[2].lowercase(); val after = m.groupValues[3]
        if (name.lowercase() in PUBLIC_HOSTS) return false
        val twoParts = name.count { it == '.' } < 2
        if (name != name.lowercase() && twoParts && after.isEmpty())
            return ending in NAME_ENDINGS || (ending.length == 2 && m.groupValues[2] == ending && ending !in FILE_ENDINGS)
        return ending in HOST_ENDINGS || (ending.length == 2 && (ending !in FILE_ENDINGS || !twoParts || after.isNotEmpty()))
    }
    private val PUBLIC_HOSTS = setOf("kite.zerodha.com", "zerodha.com", "api.kite.trade", "ws.kite.trade", "kite.trade", "upstox.com",
        "api.upstox.com", "assets.upstox.com", "nseindia.com", "www.nseindia.com", "nsearchives.nseindia.com", "github.com", "api.github.com",
        "raw.githubusercontent.com", "objects.githubusercontent.com", "uploads.github.com", "ntfy.sh", "console.zerodha.com",
        "developers.kite.trade", "huggingface.co", "api.ipify.org", "ifconfig.me", "www.google.com", "nse.in")
    /** Indian phone numbers, with or without +91, and written 98765 43210 / 98765-43210. */
    private val PHONE = Regex("(?<![\\d.,])(?:\\+?91[ -]?)?[6-9]\\d{9}(?![\\d.,])")
    private val PHONE_SPLIT = Regex("(?<![\\d.,])(?:\\+?91[ -]?)?[6-9]\\d{4}[ -]\\d{5}(?![\\d.,])")
    /**
     * Long hex runs (keys, hashes) and long runs of letters and digits (tokens). 22 Nov 2026 (B6 review): a hex run needs a letter - a
     * run of digits alone is judged by [LONG_DIGITS] and [orderId].
     */
    private val HEX = Regex("\\b(?=[0-9]*[A-Fa-f])[A-Fa-f0-9]{16,}\\b")
    private val LONG_RUN = Regex("[A-Za-z0-9_\\-+/=]{24,}")
    /** Long digit runs (account numbers, ids); rupee figures are written with commas and are shorter. */
    private val LONG_DIGITS = Regex("(?<![\\d.,])\\d{11,}(?![\\d.,])")
    /**
     * 22 Nov 2026 (B6 review, item 8): Zerodha's order ids, kept - they are not secrets, and a line is matched to Kite's order book by
     * them: a run of 15 or 16 digits that starts with its day (yymmdd, as Kite's do: "251121000123456"), or any long run right after an
     * order's word ("order", "entry", "exit", "SL", "GTT", "target", "stop": "exchange order 1100000012345678"). Any other long run of
     * digits (a bank account, a demat id) is still masked ([LONG_DIGITS]).
     */
    private fun orderId(year: Int): Regex {
        orderIdFor?.let { (y, r) -> if (y == year) return r }
        // 22 Nov 2026 (B6 review, second look): the day form only for this year and last year (an older "date" is more likely another id).
        val yy = listOf(year, year - 1).joinToString("|") { "%02d".format(Math.floorMod(it, 100)) }
        return Regex("(?<![\\d.,])(?:(?:(?:$yy)(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{9,10})|" +
            "(?<=(?i:\\b(?:orders?|entry|exit|sl(?:-m)?|gtt|target|tgt|stop)[\\s:#=(]{1,3}))\\d{11,20})(?![\\d.,])").also { orderIdFor = year to it }
    }
    @Volatile private var orderIdFor: Pair<Int, Regex>? = null

    /**
     * [text] with every secret-looking thing replaced: each value in [known] (the app's own key, user id, user name, access token,
     * relay host, static IP, ntfy topic...) wherever it appears, case-insensitively; then the shapes above. Trading symbols and
     * reason codes are kept. A user or client id is shown as [LOGGED_IN_MASK]: the file says "logged in", never who. [year]: today's,
     * for the order ids' day form ([orderId]).
     */
    fun redact(text: String, known: Collection<String> = emptyList(), year: Int = LocalDate.now().year): String {
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
        out = HOST.replace(out) { m -> if (hostLike(m)) MASK else m.value }
        out = USER_AT_HOST.replace(out, MASK)
        out = IPV4.replace(out, MASK)
        out = IPV6.replace(out, MASK)
        out = TRADING_SYMBOL.replace(out, ::keep)
        // (22 Nov 2026, B6 review: phones before the order ids - "order 919876543210" is a phone number, never kept as an id.)
        out = PHONE.replace(out, MASK)
        out = PHONE_SPLIT.replace(out, MASK)
        out = orderId(year).replace(out, ::keep)
        out = REASON_CODE.replace(out) { m -> if (reasonCode(m)) keep(m) else m.value }
        out = CLIENT_ID.replace(out, LOGGED_IN_MASK)
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
