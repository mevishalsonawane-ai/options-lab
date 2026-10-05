package com.optionslab.ira

/**
 * What the phone's speech recognizer writes for the owner's Indian English (2026-10-05): "P&L" comes out as "p and l"
 * or "pee and el", Nifty as "niftee", Sensex as "census", VIX as "vicks". These are read as the words meant - for
 * QUESTIONS only: [Ask] reads commands and orders from the words as heard, so nothing misheard here can ever start,
 * stop, close, cancel or place anything. Words already right are never touched. Pure.
 */
object Heard {
    private val FIXES: List<Pair<Regex, String>> = listOf(
        // P&L, said letter by letter.
        // (Spaced words only: "panel" or "penal" are never read as P&L.)
        Regex("(?i)\\b(?:p|pee|pea|pi)\\s+(?:and|n|en|an)\\s+(?:l|el|ell|al)\\b") to "p&l",
        Regex("(?i)\\bpandl\\b|\\bpnl\\b|\\bpn\\s+l\\b") to "p&l",
        Regex("(?i)\\bprofit\\s+(?:and|&|n)\\s+loss\\b") to "p&l",
        // "What's my PL", "what's my pin L", "my peon L" (round 21): P&L clipped or misheard - only after "my" / "the", so a PIN
        // or "pl" said alone is never read as one.
        Regex("(?i)\\b(my|the|our)\\s+(?:pl|p/l|pin\\s+l|peon\\s+l|pee\\s+l|pianl|pinal)\\b") to "$1 p&l",
        // MTM, said in words or letters.
        Regex("(?i)\\bmark\\s+to\\s+market\\b|\\bm\\s+t\\s+m\\b|\\bem\\s+tee\\s+em\\b") to "mtm",
        // The indices as the recognizer writes them (questions only: an alarm or an order never takes these).
        Regex("(?i)\\b(?:bank\\s*niftee|bank\\s*nifity|bankniftie|bank\\s+fifty|bank\\s+nifti)\\b") to "banknifty",
        Regex("(?i)\\b(?:fine\\s+nifty|fine\\s+nifti|finney\\s+nifty|fin\\s+fifty|phin\\s+nifty|fin\\s+nifity)\\b") to "finnifty",
        Regex("(?i)\\b(?:niftee|nifity|niftie|knifty|nifte|nifti)\\b") to "nifty",
        Regex("(?i)\\b(?:census|sensexs|sensek|sen\\s+sex|sense\\s+ex|sensecs)\\b") to "sensex",
        Regex("(?i)\\b(?:india\\s+vicks|india\\s+vics|vicks|vics|vix\\s+index)\\b") to "vix",
    )

    /**
     * The name said with a possessive or plural ("jarvis's", heard as "jarvis s"): the name. [padded]: lower case, spaced
     * words with a space at each end ([Wake.heard]'s form).
     */
    fun nameForms(padded: String): String = NAME_FORMS.replace(padded, " $1 ")

    private val NAME_FORMS = Regex(" (jarvis|jervis|jarvas) s ")

    /**
     * Voice, round 26 (Boss's diagnostics, 5 Oct, Pixel 9 on the en-US recognizer: "darius good evening"): slips of
     * "Jarvis" the recognizer writes as other names. Counted only as the very first word, and only with a question, a
     * greeting or a market word right after ("darius good evening", "travis how is nifty") - so "darius" alone, or
     * "harvest season", never wakes him. Never "named": anything that acts still needs the name itself. Not a Hinglish
     * word among them. [padded]: as [nameForms] takes it. Where the slip ends in [padded] (its trailing space), or null.
     */
    fun nameSlip(padded: String): Int? = NAME_SLIP.find(padded)?.let { m -> m.range.last }

    private val NAME_SLIP = Regex("^ (?:hey |ok |okay )?(?:darius|darious|dharius|travis|travis s|harvest|jarvises|jarvis es) " +
        "(?=(?:what|whats|what s|how|hows|how s|why|when|where|which|who|is|are|was|were|did|do|does|can|could|will|would|tell|show|give|" +
        "any|good|hello|hi|hey|namaste|nifty|bank|banknifty|finnifty|sensex|vix|gold|market|markets|my|today|status|update|news) )")

    /** [text] with misheard words read as meant, or [text] itself when nothing needed fixing. */
    fun fix(text: String): String = fixed.same(text) { FIXES.fold(text) { t, (r, to) -> r.replace(t, to) } }

    /** The last words read ([Kept]; pure). */
    private val fixed = Kept<String>(64)
}
