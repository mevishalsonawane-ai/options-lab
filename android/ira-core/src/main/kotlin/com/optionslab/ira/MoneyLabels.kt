package com.optionslab.ira

/**
 * Every money figure says whose money it is (12 Oct, Boss: "in the morning my portfolio was 4k, in the evening it was
 * negative" - Home's "Capital" and the day report mixed the PAPER result with Zerodha's real money). A source check: in the
 * files that show Boss's money (Home's money card, the day report, the widget, Jarvis's funds answer), a line that formats a
 * figure as money ([MONEY]) inside words about an account's money ([NOUN]: P&L, capital, funds, available, balance) must also
 * name the account ([ACCOUNT]: Paper / PAPER / Zerodha / LIVE, or the MoneyFacts helpers that say it), or carry a
 * "money-label:" comment saying why it needs none. Run as a test over the app's sources, so a new unlabelled figure fails the
 * build's tests instead of confusing Boss. Pure.
 */
object MoneyLabels {
    private val MONEY = Regex("""\brs\(|\bamt\(|\bRs %|₹|MoneyFacts\.rs\(""")
    private val NOUN = Regex("""(?i)P&L|\bpnl\b|capital|\bfunds\b|available|balance|day report""")
    /** A bare money heading on its own ("Capital": Home's card before 12 Oct) - whatever follows it, it must say whose. */
    private val BARE = Regex(""""(?:Capital|P&L|Day P&L|Funds|Balance)"""")
    private val ACCOUNT = Regex("""Paper|PAPER|paper|Zerodha|LIVE|MoneyFacts\.venue\(|cardTitle\(|cardNote\(|dayTitle\(|money-label:""")
    private val LITERAL = Regex(""""(?:[^"\\]|\\.)*"""")

    /** The words inside the line's string literals (roughly: template expressions with quotes inside are cut, never missed whole). */
    private fun literals(line: String): String = LITERAL.findAll(line).joinToString(" ") { it.value }

    /** The lines of [source] (1-based number and text) showing a money figure with no account named. Comments are skipped. */
    fun unlabelled(source: String): List<Pair<Int, String>> = source.lines().mapIndexedNotNull { i, raw ->
        val line = raw.trim()
        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@mapIndexedNotNull null
        if (ACCOUNT.containsMatchIn(line)) return@mapIndexedNotNull null
        val bare = BARE.containsMatchIn(line)
        if (!bare && !MONEY.containsMatchIn(line)) return@mapIndexedNotNull null
        if (!bare && !NOUN.containsMatchIn(literals(line))) return@mapIndexedNotNull null
        (i + 1) to line
    }
}
