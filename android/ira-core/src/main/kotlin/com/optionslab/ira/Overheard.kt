package com.optionslab.ira

/**
 * What Jarvis says or shows UNASKED while the phone is locked (usefulness round 4, 2026-10-05): a locked phone may be
 * overheard, or its pop-up seen by whoever is near it, so a line that holds Boss's account - a rupee amount, a P&L, a
 * trading symbol - is swapped for a plain one that only says it is in the chat. The full line always goes to the chat.
 * The last net under every unasked line: each watch already picks its own amount-free words where it can (the target,
 * the report card, the heads-ups); this catches the rest. An answer Boss asked for is not touched here. Pure.
 */
object Overheard {
    /** Said (or shown) instead, while the phone is locked. */
    const val SAID = "Boss, I've put something for you in the chat."
    /** A pop-up's title instead, while the phone is locked. */
    const val TITLE = "Jarvis"

    private val RUPEES = Regex("(?i)(?:\\bRs\\.?|₹|\\bINR)\\s?[+-]?\\d|\\d[\\d,]*(?:\\.\\d+)?\\s?(?:rupees|lakh|crore)\\b")
    private val PNL = Regex("(?i)\\bP\\s?&\\s?L\\b[^.]{0,12}[+-]?\\d|\\bP and L\\b[^.]{0,12}[+-]?\\d")
    /** A contract as the broker writes it: NIFTY25O0725000CE, BANKNIFTY25OCT55000PE, NIFTY25OCTFUT. */
    private val SYMBOL = Regex("\\b[A-Z]{2,}[A-Z0-9]*\\d(?:CE|PE)\\b|\\b[A-Z]{2,}\\d{2}[A-Z]{3}FUT\\b")

    /** Does [text] hold Boss's account: an amount in rupees, a P&L figure or a contract's symbol? */
    fun holdsAccount(text: String): Boolean = RUPEES.containsMatchIn(text) || PNL.containsMatchIn(text) || SYMBOL.containsMatchIn(text)

    /** What an unasked line says: [text] itself, or [plain] when the phone is [locked] and [text] holds Boss's account. */
    fun said(text: String, locked: Boolean, plain: String = SAID): String = if (locked && holdsAccount(text)) plain else text

    /** An unasked pop-up's title, the same way. */
    fun title(title: String, locked: Boolean): String = if (locked && holdsAccount(title)) TITLE else title
}
