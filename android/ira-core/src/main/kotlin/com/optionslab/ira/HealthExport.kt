package com.optionslab.ira

import java.time.LocalDate

/**
 * The exportable health report (S17): the last [DAYS] days of main-thread stalls with where they were stuck, the ANRs and
 * crashes with their stack traces (Android's exit records' traces and the app's own sealed crash file), and the order
 * path's timings - as one plain-text report Boss can hand to whoever helps.
 *
 * Everything that goes into it passes through [redact]: tokens, API keys and secrets, the PIN, account and client ids,
 * e-mail addresses, phone numbers, long digit runs and any value the app names as secret ([redact]'s `known`) are replaced
 * before the text leaves this function. Pure: the app reads the traces and the books and shares the result.
 */
object HealthExport {
    const val DAYS = 14
    /** A trace keeps at most this many lines (the top of the stack, where the cause is). */
    const val TRACE_LINES = 60
    const val REDACTED = "‹redacted›"

    /** One ANR or crash trace, as read. */
    data class Trace(val atMs: Long, val kind: HealthBook.ExitKind, val text: String)

    // Ordered: the specific shapes first, then the generic long-token catch-all.
    private val PATTERNS: List<Regex> = listOf(
        // key=value / key: value pairs whose key names a secret
        Regex("(?i)\\b(access[_ -]?token|request[_ -]?token|refresh[_ -]?token|api[_ -]?key|api[_ -]?secret|secret|password|passwd|pwd|pin|otp|totp|" +
            "authorization|auth|token|session|cookie|enctoken|user[_ -]?id|client[_ -]?id|account[_ -]?id|uid)\\b[\"']?\\s*[:=]\\s*[\"']?" +
            // JSON-quoted keys ("pin":"4821") and an auth scheme before the value ("Authorization: token key:secret") too (R5 #6)
            "((?:(?:bearer|token|basic)\\s+)?[^\\s\"',;&]+)"),
        // "PIN is 4821", "my otp was 123456", "password is hunter2"
        Regex("(?i)\\b(pin|otp|totp|passcode)\\b(?:\\s+(?:is|was|number|no|code))*\\s*[:=]?\\s*\\d{4,8}\\b"),
        Regex("(?i)\\b(password|passwd|pwd|passcode)\\s+(?:is|was)\\s+\\S+"),
        // HTTP auth headers
        Regex("(?i)\\b(bearer|token|basic)\\s+[A-Za-z0-9._~+/=:-]{8,}"),
        // JSON web tokens
        Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),
        // URLs' query strings (they can carry tokens)
        Regex("(?i)(https?://[^\\s?#]+)\\?[^\\s]*"),
        // e-mail addresses
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        // Zerodha client ids (two or three letters and four to six digits: AB1234)
        Regex("(?i)\\b[A-Z]{2,3}\\d{4,6}\\b"),
        // phone numbers (Indian mobiles, with or without +91, and written 98765 43210 / 98765-43210)
        Regex("(?<![\\d.])(?:\\+?91[ -]?)?[6-9]\\d{9}(?![\\d.])"),
        Regex("(?<![\\d.])(?:\\+?91[ -]?)?[6-9]\\d{4}[ -]\\d{5}(?![\\d.])"),
        // long hex or base64-ish runs (keys, tokens, hashes)
        Regex("\\b[A-Fa-f0-9]{16,}\\b"),
        Regex("[A-Za-z0-9_\\-+/=]{24,}"),
        // long digit runs (account and order numbers); times and sizes in the report are shorter
        Regex("(?<![\\d.])\\d{8,}(?![\\d.])"),
    )

    /**
     * [text] with every secret-looking thing replaced by [REDACTED]; every value in [known] (the app's own API key, user id,
     * user name, the PIN's length is never known here) replaced wherever it appears, case-insensitively.
     */
    fun redact(text: String, known: Collection<String> = emptyList()): String {
        var out = text
        for (k in known.filter { it.length >= 3 }.sortedByDescending { it.length }) out = out.replace(k, REDACTED, ignoreCase = true)
        for ((i, r) in PATTERNS.withIndex()) out = when (i) {
            0, 1, 2 -> r.replace(out) { m -> "${m.groupValues[1]}=$REDACTED" }
            5 -> r.replace(out) { m -> "${m.groupValues[1]}?$REDACTED" }
            else -> r.replace(out, REDACTED)
        }
        return out
    }

    /**
     * A trace cut to the top [TRACE_LINES] lines, each trimmed; messages after "Exception:" kept only as the class name. An
     * ANR dump (Android's runtime statistics first, the threads after "DALVIK THREADS") starts at the main thread's stack
     * (review G8-9: its first 60 lines were only the runtime's statistics).
     */
    fun trimTrace(text: String): String {
        val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        val from = lines.indexOfFirst { MAIN.containsMatchIn(it) }.takeIf { it > 0 }
            ?: lines.indexOfFirst { it.trimStart().startsWith("DALVIK THREADS") }.takeIf { it > 0 } ?: 0
        return lines.drop(from).take(TRACE_LINES)
            .map { l -> ex.find(l)?.let { m -> m.groupValues[1] + ": ‹message left out›" } ?: l }.joinToString("\n")
    }

    /** An ANR dump's main-thread header: `"main" prio=5 tid=1 Blocked`. */
    private val MAIN = Regex("^\\s*\"main\"\\s")

    /** "java.lang.IllegalStateException: anything" -> the class only (a message can carry a URL, a symbol or a token). */
    private val ex = Regex("^\\s*(?:Caused by: )?([a-zA-Z_$][\\w$.]*(?:Exception|Error|Throwable))(?::\\s.*)$")

    /** The report: header, stalls by day with their places and frames, exits with traces, order timings - all redacted. */
    fun build(b: HealthBook.Book, traces: List<Trace>, orderLines: List<String>, today: LocalDate, build: String, device: String,
              hhmm: (Long) -> String, known: Collection<String> = emptyList()): String {
        val from = today.minusDays(DAYS.toLong() - 1)
        val sb = StringBuilder()
        sb.append("IraAlgo health report\n")
        sb.append("Build ").append(build).append(" · ").append(device).append(" · ").append(today).append(" · last ").append(DAYS).append(" days\n")
        sb.append("No secrets: tokens, keys, the PIN, account and client ids are removed.\n\n")
        sb.append("== Main-thread stalls (").append(HealthBook.STALL_MS).append(" ms or more)\n")
        val days = b.days.filter { !it.day.isBefore(from) && !it.day.isAfter(today) }.sortedByDescending { it.day }
        if (days.none { it.stalls > 0 }) sb.append("none\n")
        for (d in days) {
            if (d.stalls == 0 && d.overruns.isEmpty()) continue
            sb.append(d.day).append(": ").append(d.stalls).append(" stall(s), longest ").append(d.longestMs).append(" ms")
            if (d.places.isNotEmpty()) sb.append("; places ").append(d.places.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ×${it.value}" })
            if (d.overruns.isNotEmpty()) sb.append("; workers past their limit ").append(d.overruns.entries.joinToString(", ") { "${it.key} ×${it.value}" })
            sb.append('\n')
            for (s in d.samples) sb.append("  ").append(hhmm(s.atMs)).append(" ").append(s.ms).append(" ms in ").append(s.where).append('\n')
        }
        sb.append("\n== ANRs and crashes\n")
        val exits = b.exits.filter { !HealthBook.dayOf(it.atMs).isBefore(from) }
        if (exits.isEmpty() && traces.isEmpty()) sb.append("none\n")
        for (e in exits) sb.append(HealthBook.dayOf(e.atMs)).append(' ').append(hhmm(e.atMs)).append(' ').append(e.kind.name).append(" in ").append(e.where).append('\n')
        for (t in traces.filter { !HealthBook.dayOf(it.atMs).isBefore(from) }.sortedByDescending { it.atMs }) {
            sb.append("\n-- ").append(t.kind.name).append(" at ").append(HealthBook.dayOf(t.atMs)).append(' ').append(hhmm(t.atMs)).append(" (trace)\n")
            sb.append(trimTrace(t.text)).append('\n')
        }
        sb.append("\n== Order path\n")
        val timed = days.filter { it.orderMedianMs != null }
        if (timed.isEmpty() && orderLines.isEmpty()) sb.append("no orders timed\n")
        for (d in timed) sb.append(d.day).append(": signal to sent median ").append(d.orderMedianMs).append(" ms over ").append(d.orderN).append(" order(s)\n")
        for (l in orderLines) sb.append(l).append('\n')
        sb.append("\n== Other\n")
        for (d in days) if (d.reconnects + d.stale + d.trims + d.saverOn > 0)
            sb.append(d.day).append(": stream reconnects ").append(d.reconnects).append(", stale ").append(d.stale).append(", memory trims ").append(d.trims)
                .append(", battery saver seen ").append(d.saverOn).append("/").append(d.saverLooks).append('\n')
        return redact(sb.toString(), known)
    }

    /** The report's file name. */
    fun fileName(today: LocalDate): String = "iraalgo-health-$today.txt"
}
