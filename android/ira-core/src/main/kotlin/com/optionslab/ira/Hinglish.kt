package com.optionslab.ira

/**
 * Hinglish (the owner's wish, 2026-10-02): what the phone's English recognizer writes when the owner speaks Hindi
 * words - "Nifty kaisa hai", "strategy 1 band karo", "haan", "nahi" - turned into the English Jarvis understands
 * before it is parsed. Hindi puts the verb last ("strategy 1 band karo"), so commands are turned round ("stop strategy
 * 1"). Text without Hindi words is returned unchanged. Pure.
 */
object Hinglish {
    /** Words that only Hindi uses (so English text, "Bollinger band" included, is left alone). */
    private val HINDI = Regex("\\b(kya|kaisa|kaise|kaisi|hai|hain|batao|bataiye|dikhao|dikhaiye|karo|kardo|karu|karun|karna|chahiye|" +
        "roko|chalu|shuru|khareedo|kharido|lelo|mera|meri|aaj|abhi|haan|nahi|nahin|rehne|bilkul|wala|wali|sabhi|saare|kyun|kyu|kyon|gira|giri|chadha|chadhi|badha|upar|neeche|niche|jayega|jaega|jayegi)\\b")

    /** Verb last -> English command first: (pattern, English verb). */
    private val VERBS = listOf(
        Regex("^(.*?)\\s*(?:ko\\s+)?(?:band\\s+kar\\s+do|band\\s+kardo|band\\s+karo|band\\s+kar|rok\\s+do|roko)$") to "stop",
        Regex("^(.*?)\\s*(?:ko\\s+)?(?:chalu\\s+kar\\s+do|chalu\\s+karo|shuru\\s+kar\\s+do|shuru\\s+karo|start\\s+karo|start\\s+kar\\s+do)$") to "start",
        Regex("^(.*?)\\s*(?:ko\\s+)?(?:cancel\\s+kar\\s+do|cancel\\s+karo|cancel\\s+kardo)$") to "cancel",
        Regex("^(.*?)\\s*(?:ko\\s+)?(?:close\\s+kar\\s+do|close\\s+karo|exit\\s+karo|square\\s+off\\s+karo)$") to "close",
        Regex("^(.*?)\\s*(?:khareedo|kharido|le\\s+lo|lelo|buy\\s+karo|buy\\s+kar\\s+do)$") to "buy",
        Regex("^(.*?)\\s*(?:dikhao|dikhaiye|batao|bataiye|bata\\s+do)$") to "show",
    )

    private val WORDS = listOf(
        Regex("\\btrade\\s+kar(?:u|un|na|ni)?\\s+(?:kya|chahiye)(?:\\s+kya)?\\b|\\bkya\\s+trade\\s+kar(?:u|un|na)\\b") to "should i trade now",
        Regex("\\b(?:market|bazaar|bazar)\\s+kaisa\\s+hai\\b") to "how is the market",
        Regex("^(.*?)\\s+kaisa\\s+(?:hai|chal\\s+raha\\s+hai)$") to "how is $1",
        Regex("^(.*?)\\s+kya\\s+(?:hai|hua)$") to "what is $1",
        Regex("\\bmera\\b|\\bmeri\\b|\\bmere\\b") to "my",
        Regex("\\b(?:kyun|kyu|kyon)\\b") to "why",
        Regex("\\b(?:gira|giri|gire)\\b") to "fell",
        Regex("\\b(?:chadha|chadhi|badha|badhi)\\b") to "rose",
        Regex("\\b(?:upar)\\b") to "going up",
        Regex("\\b(?:neeche|niche)\\b") to "going down",
        Regex("\\b(?:jayega|jaega|jayegi|jaegi)\\b") to "",
        Regex("\\baaj\\b") to "today",
        Regex("\\babhi\\b") to "now",
        Regex("\\bsab\\b|\\bsaare\\b|\\bsabhi\\b") to "all",
        Regex("\\b(?:ka|ki|ke)\\b") to "",
    )

    fun hasHindi(text: String): Boolean = HINDI.containsMatchIn(text.lowercase())

    fun normalize(text: String): String {
        var t = text.lowercase().replace(Regex("[^a-z0-9.,&% ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (!hasHindi(t)) return text
        for ((r, to) in WORDS) t = r.replace(t, to).replace(Regex("\\s+"), " ").trim()
        for ((r, verb) in VERBS) r.find(t)?.let { m -> t = "$verb ${m.groupValues[1].trim()}".trim() }
        return t
    }

    /** "haan" / "nahi" and friends: true, false, or null when it is neither. */
    fun yesNo(text: String): Boolean? {
        val t = " " + text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (Regex(" (nahi|nahin|nahii|nhi|nai|na|mat karo|mat|rehne do|ruko|cancel karo) ").containsMatchIn(t)) return false
        if (Regex(" (haan|haa|ha|han|ji|ji haan|bilkul|theek hai|thik hai|kar do|kardo|le lo|lelo|chalo) ").containsMatchIn(t)) return true
        return null
    }
}
