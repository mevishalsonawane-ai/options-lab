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

    /*
     * A plain amount, with no Rs or ₹ (round 7): "you're down 2,500 today", "net 1,240.50", "-3,400.50". It counts only as
     * money in the account's words - after profit, loss, down, up, net, made, lost..., or with a sign of its own - and
     * only when the sentence is the account's: it speaks of Boss's own money ("you're down", "your P&L", Solo, the account) or names no market.
     * A market alert keeps its figures: "Nifty at 24,612", "VIX up 8%", "BankNifty down 180 points" all pass.
     * A figure followed by %, points, lots, trades, minutes... is no amount; nor is a small whole number ("down 3 in a row").
     */
    /** The number: digits with commas, an optional decimal part; possessive, so a figure is never read half. */
    private const val NUM = "\\d++(?:,\\d++)*+(?:\\.\\d++)?+"
    /** What follows a number that makes it no amount. */
    private const val NOT_MONEY = "(?!\\s*(?:%|percent|per cent|pts?\\b|points?\\b|bps\\b|basis|lots?\\b|trades?\\b|positions?\\b|orders?\\b|" +
        "times\\b|x\\b|in a row|of \\d|out of|wins?\\b|losers?\\b|losses\\b|days?\\b|weeks?\\b|sessions?\\b|minutes?\\b|mins?\\b|hours?\\b|hrs?\\b|seconds?\\b|" +
        "strikes?\\b|contracts?\\b|candles?\\b|[:/]\\d|am\\b|pm\\b))"
    private val CUED = Regex("(?i)\\b(?:p\\s?&\\s?l|pnl|mtm|m2m|profits?|loss|net|down|up|made|lost|gained|earned|booked|realised|realized|unrealised|unrealized|" +
        "charges|brokerage)\\b[^\\d.!?;\\n]{0,20}?(?<![\\w.,])[+-]?($NUM)$NOT_MONEY")
    private val SIGNED = Regex("(?<![\\w.,:/-])[+-]($NUM)$NOT_MONEY")
    /** The sentence speaks of Boss's own money or trades (enough to hold an amount even beside a market's name). */
    private val ACCOUNT_WORDS = Regex("(?i)\\b(?:you['’]?re|you are|you['’]?ve|you (?:made|lost|gained|earned|booked|paid|are|were|have|had)|" +
        "(?:your|my|our) (?:p\\s?&\\s?l|pnl|mtm|m2m|profits?|loss|losses|account|net|day|days|week|month|trades?|positions?|book|portfolio|charges|brokerage|money)|" +
        "i (?:made|lost|booked|paid)|solo|account|portfolio|paper|zerodha|booked|realised|realized|unrealised|unrealized|mtm|m2m)\\b")
    /** The sentence is about a market. */
    private val MARKET_WORDS = Regex("(?i)\\b(?:nifty|banknifty|bank nifty|finnifty|midcpnifty|midcap|sensex|bankex|vix|index|indices|spot|futures?|" +
        "oi|open interest|strikes?|levels?|support|resistance|pivot|vwap|candle|gap|crude|usdinr|dow|nasdaq|gift)\\b")
    private val SENTENCES = Regex("(?<=[.!?;])\\s+|\\n+")

    /** Is it a figure of money (not a price, level or percent), and is it the account's? */
    private fun plainAmount(text: String): Boolean = SENTENCES.split(text).any { s ->
        val money = (CUED.findAll(s) + SIGNED.findAll(s)).any { m -> moneyShaped(m.groupValues[1]) }
        money && (ACCOUNT_WORDS.containsMatchIn(s) || !MARKET_WORDS.containsMatchIn(s))
    }

    /** "2,500", "840.50", "120": an amount; "3" or "12" alone is a count, not money. */
    private fun moneyShaped(n: String): Boolean = ',' in n || '.' in n || (n.toDoubleOrNull() ?: 0.0) >= 100

    /** Does [text] hold Boss's account: an amount in rupees (or a plain one in the account's words), a P&L figure or a contract's symbol? */
    fun holdsAccount(text: String): Boolean = RUPEES.containsMatchIn(text) || PNL.containsMatchIn(text) || SYMBOL.containsMatchIn(text) || plainAmount(text)

    /** What an unasked line says: [text] itself, or [plain] when the phone is [locked] and [text] holds Boss's account. */
    fun said(text: String, locked: Boolean, plain: String = SAID): String = if (locked && holdsAccount(text)) plain else text

    /**
     * An unasked line at the moment it is finally said (it may have waited up to 30 s for Boss's own answer, and the
     * phone may have locked meanwhile - review, 5 Oct): null in quiet hours ([quiet]; not said), else [said] for the
     * phone as it is now.
     */
    fun atSay(text: String, locked: Boolean, quiet: Boolean, plain: String = SAID): String? = if (quiet) null else said(text, locked, plain)

    /** An unasked pop-up's title, the same way. */
    fun title(title: String, locked: Boolean): String = if (locked && holdsAccount(title)) TITLE else title
}
