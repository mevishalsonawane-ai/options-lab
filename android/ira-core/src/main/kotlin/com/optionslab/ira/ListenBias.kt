package com.optionslab.ira

/**
 * Voice, round 27 (Boss's phone listens with the en-US on-device recognizer because English (India) is not installed,
 * and it writes "census" for Sensex, "niftee" for Nifty, "darius" for Jarvis): the words Jarvis's ears are leaned
 * towards (Android 13+, RecognizerIntent.EXTRA_BIASING_STRINGS), so trading words are heard right in the first place
 * and [Heard] has less to mend afterwards.
 *
 * Only names and question words: never PIN, lock, Live, AI-live, guard or the Google speech choice ([SENSITIVE] is
 * checked by the tests). Leaning the ears changes only which words are written; what is done with them is still [Ask]'s
 * and [Act]'s rules. Fixed and pure: the same list every call, nothing learned, nothing heard kept.
 */
object ListenBias {
    /** Recognizers keep only a short list; more is ignored or slows the turn. */
    const val MAX = 48

    private val WORDS: List<String> = listOf(
        // The name, written as the recognizer should (round 26: "darius", "travis").
        "Jarvis",
        // Indices and the account.
        "Nifty", "Bank Nifty", "BankNifty", "Fin Nifty", "FinNifty", "Sensex", "India VIX", "VIX", "Midcap Nifty",
        "P&L", "P and L", "MTM", "Zerodha", "Kite", "margin", "funds",
        // Options words.
        "theta", "delta", "gamma", "vega", "strike", "strikes", "CE", "PE", "call", "put", "premium", "expiry",
        "weekly expiry", "open interest", "PCR", "max pain", "straddle", "strangle", "iron condor",
        // Trade words Boss asks about (heard better, never done by this: acting keeps Ask's and Act's own rules).
        "square off", "stop loss", "target", "positions", "orders",
        // Hinglish question words the en-US recognizer drops ([Hinglish]).
        "kya chal raha hai", "kitna", "dikhao",
    )

    /** Words that must never be leaned towards: the backdoor list (checked by the tests). */
    val SENSITIVE: Set<String> = setOf("pin", "lock", "unlock", "live", "ai live", "ai-live", "guard", "google", "speech", "password", "passcode")

    /** The words to lean the recognizer towards: distinct, at most [MAX], the same every call. */
    fun words(): List<String> = LIST

    private val LIST: List<String> = WORDS.distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(MAX)
}
