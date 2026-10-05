package com.optionslab.ira

/** How Jarvis addresses the owner: "Boss" (the owner's wish, 2026-10-02), at the start of what it says. Pure. */
object Address {
    const val NAME = "Boss"
    /** Words whose capital stays when they no longer start the sentence. */
    private val KEEP = setOf("Nifty", "BankNifty", "FinNifty", "Sensex", "India", "Gold", "Jarvis", "Ira", "Zerodha", "Paper", "Live",
        "I", "I'm", "I've", "I'll", "I'd", "ORB", "Pine", "P&L", "NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "VIX", "Upstox", "Hugging")

    fun boss(text: String): String {
        val t = text.trim()
        // Already addresses Boss anywhere ("Voice on, Boss.", "Good morning, Boss."): said once, never twice.
        if (t.isEmpty() || rx("\\b$NAME\\b").containsMatchIn(t)) return t
        if (rx("^(Hello|Hi|Hey|Good (morning|afternoon|evening))\\b").containsMatchIn(t))
            return rx("^(Hello|Hi|Hey|Good (morning|afternoon|evening))( there)?[.,!]?").replace(t) { "${it.groupValues[1]} $NAME." }
        val word = t.takeWhile { !it.isWhitespace() }
        val keep = word.trimEnd(',', '.', ':', ';', '!', '?').removeSuffix("'s").removeSuffix("’s") in KEEP || word.length > 1 && word[1].isUpperCase() || word.first().isDigit()
        return "$NAME, " + (if (keep) t else t.replaceFirstChar { it.lowercase() })
    }
}
