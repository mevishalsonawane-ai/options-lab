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
        "roko|chalu|shuru|khareedo|kharido|lelo|mera|meri|aaj|abhi|haan|nahi|nahin|rehne|bilkul|wala|wali|sabhi|saare|kyun|kyu|kyon|gira|giri|chadha|chadhi|badha|upar|neeche|jayega|jaega|jayegi|pichle|pichhle|ghante|ghanta|kitna|kitne|kitni|hafte|hafta|mahine|mahina|lagao|batana|kar|kamaya|kamaye|kamai|hatao|hata|karega|karegi|pahunchega|pahunchegi|hoga|hogi)\\b")

    /** Verb last -> English command first: (pattern, English verb). */
    private val VERBS = listOf(
        // "Mera Nifty position band karo": a position is closed, not stopped.
        Regex("^(?!.*\\b(?:aaj|today|size|jarvis)\\b)(.*?\\b(?:position|positions|trade|trades))\\s*(?:ko\\s+)?(?:band\\s+kar\\s+do|band\\s+kardo|band\\s+karo|band\\s+kar)$") to "close",
        Regex("^(.*?)\\s*(?:ko\\s+)?(?:band\\s+kar\\s+do|band\\s+kardo|band\\s+karo|band\\s+kar|rok\\s+do|roko)$") to "stop",
        Regex("^(.*?)\\s*(?:ko\\s+)?(?:chalu\\s+kar\\s+do|chalu\\s+karo|shuru\\s+kar\\s+do|shuru\\s+karo|start\\s+karo|start\\s+kar\\s+do)$") to "start",
        Regex("^(.*?)\\s*(?:ko\\s+)?(?:cancel\\s+kar\\s+do|cancel\\s+karo|cancel\\s+kardo)$") to "cancel",
        Regex("^(.*?)\\s*(?:ko\\s+)?(?:close\\s+kar\\s+do|close\\s+karo|exit\\s+karo|square\\s+off\\s+karo)$") to "close",
        Regex("^(.*?)\\s*(?:khareedo|kharido|le\\s+lo|lelo|buy\\s+karo|buy\\s+kar\\s+do)$") to "buy",
        Regex("^(.*?)\\s*(?:dikhao|dikhaiye|batao|bataiye|bata\\s+do)$") to "show",
        Regex("^(.*?)\\s*(?:ko\\s+)?(?:hata\\s+do|hatao|hata\\s+de)$") to "remove",
        // "max lots 5 kar do": a setting (last, after the verbs above).
        Regex("^(?!.*\\b(?:alert|alarm)\\s+set\\s+(?:kar|kardo))(.*?)\\s*(?:kar\\s+do|kardo|kar\\s+de)$") to "set",
    )

    private val WORDS = listOf(
        // "Kya Nifty aaj 25000 cross karega", "Nifty 25000 pahunchega kya": the odds of reaching a level (a question, never an alarm).
        Regex("^(?!.*\\b(?:alert|alarm|lagao|batana|order|lot|lots|buy|sell|ce|pe|call|put)\\b)(?:kya\\s+)?(.+?)\\s+(\\d{2,6})\\s+ke\\s+(?:upar|uppar)\\s+band\\s+(?:hoga|hogi|honge)(?:\\s+kya)?$") to "will $1 close above $2",
        Regex("^(?!.*\\b(?:alert|alarm|lagao|batana|order|lot|lots|buy|sell|ce|pe|call|put)\\b)(?:kya\\s+)?(.+?)\\s+(\\d{2,6})\\s+ke\\s+(?:neeche|niche)\\s+band\\s+(?:hoga|hogi|honge)(?:\\s+kya)?$") to "will $1 close below $2",
        Regex("^(?!.*\\b(?:alert|alarm|lagao|batana|order|lot|lots|buy|sell|ce|pe|call|put)\\b)(?:kya\\s+)?(.+?)\\s+(\\d{2,6})\\s+(?:(?:ko|tak)\\s+)?(?:cross|touch|hit)?\\s*(?:karega|karegi|kar\\s+payega|pahunchega|pahunchegi|jayega|jaega|jayegi|jaegi)(?:\\s+kya)?$") to "will $1 cross $2",
        Regex("\\btrade\\s+kar(?:u|un|na|ni)?\\s+(?:kya|chahiye)(?:\\s+kya)?\\b|\\bkya\\s+trade\\s+kar(?:u|un|na)\\b") to "should i trade now",
        Regex("\\b(?:market|bazaar|bazar)\\s+kaisa\\s+hai\\b") to "how is the market",
        // "Kya karna chahiye": the trade check (never a direction); "kitne trade kiye": my trades today.
        Regex("^(?:ab\\s+|aaj\\s+)?kya\\s+kar(?:na|u|un|e)\\s+(?:chahiye|hum)$") to "should i trade now",
        Regex("\\b(?:trade|trades)\\s+(?:kiye|kie|liye|lie)\\b") to "trades did i take",
        Regex("^(.*?)\\s+kaisa\\s+(?:hai|chal\\s+raha\\s+hai)$") to "how is $1",
        Regex("^(.*?)\\s+kya\\s+(?:hai|hua)$") to "what is $1",
        Regex("\\bmera\\b|\\bmeri\\b|\\bmere\\b") to "my",
        Regex("\\b(?:kyun|kyu|kyon)\\b") to "why",
        Regex("\\b(?:kamaya|kamaye|kamai)\\b") to "did i make",
        Regex("\\b(?:gira|giri|gire)\\b") to "fell",
        Regex("\\b(?:chadha|chadhi|badha|badhi)\\b") to "rose",
        Regex("\\b(?:upar)\\b") to "going up",
        Regex("\\b(?:neeche)\\b") to "going down",
        Regex("\\b(?:jayega|jaega|jayegi|jaegi)\\b") to "",
        Regex("\\b(?:pichle|pichhle)\\s+(?:ek\\s+)?(?:ghante|ghanta)\\b") to "in the last hour",
        Regex("\\b(?:is|iss)\\s+(?:hafte|hafta)\\b") to "this week",
        Regex("\\b(?:pichle|pichhle)\\s+(?:hafte|hafta)\\b") to "last week",
        Regex("\\b(?:is|iss)\\s+(?:mahine|mahina)\\b") to "this month",
        Regex("\\b(?:kitna|kitne|kitni)\\b") to "how much",
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

    // How the recognizer spells the little Hindi words ("kya" / "kia", "raha" / "rha", "hai" / "hain").
    private const val KYA = "(?:kya|kia|kyaa)"
    private const val RAHA = "(?:raha|rha|rahi|rhi|rahe|rhe)"
    private const val HAI = "(?:hai|hain|hay|he)"
    private const val MINE = "(?:(?:mera|meri|mere|apna|apni|apne)\\s+)?"
    private const val SHOW = "(?:dikhao|dikhaao|dikhaiye|dikha\\s+do|dikha\\s+dijiye|dikhana|batao|bataiye|bata\\s+do|$KYA\\s+$HAI|check\\s+karo)"

    /**
     * The commonest questions in Hinglish (2026-10-05): "Nifty kya chal raha hai", "aaj kitna kamaya", "positions dikha
     * do", "koi order pending hai kya" - each read as the English question. Questions only: every line here ends in a
     * question word, never in a verb that acts, and [Ask] reads commands and orders from [normalize] alone, never from
     * these (so no action is understood more widely). (pattern, English); `$1` is the market or the thing asked about.
     */
    private val QUESTIONS: List<Pair<Regex, String>> = listOf(
        // The market as a whole: "market kya chal raha hai", "bazaar ka kya haal hai" (as "market kaisa hai").
        Regex("^(?:(aaj|abhi)\\s+)?(?:share\\s+)?(?:market|bazaar|bazar)\\s+(?:(?:mein|me|main)\\s+)?(?:$KYA\\s+chal\\s+$RAHA|$KYA\\s+ho\\s+$RAHA|kaisa\\s+chal\\s+$RAHA|kaisi\\s+chal\\s+$RAHA)\\s+$HAI$") to "$1 how is the market",
        Regex("^(?:(aaj|abhi)\\s+)?(?:share\\s+)?(?:market|bazaar|bazar)\\s+(?:ka|ki)\\s+(?:$KYA\\s+haal|haal\\s+$KYA|haal\\s+kaisa)\\s+$HAI$") to "$1 how is the market",
        // "Market khula hai kya": whether it is open today.
        Regex("^(?:(?:aaj|kya)\\s+)?(?:market|bazaar|bazar)\\s+(?:aaj\\s+)?(?:khula|khuli|open)\\s+(?:$HAI|hoga|rahega)(?:\\s+$KYA)?(?:\\s+aaj)?$") to "is the market open today",
        // The owner's own money: "aaj kitna kamaya", "kitna loss hua", "aaj ki kamai kitni hai", "main profit mein hoon kya".
        Regex("^(?:(?:aaj|maine|humne|abhi\\s+tak|ab\\s+tak)\\s+)*(?:kitna|kitne|kitni|kitana)\\s+(?:paisa\\s+|paise\\s+|profit\\s+|munafa\\s+)?(?:kamaya|kamaye|kamai|kamaai|banaya|banaye|bana|bane|bani)(?:\\s+(?:maine|humne|aaj|$HAI|$KYA))*$") to "what is my p&l today",
        Regex("^(?:(?:aaj|maine|humne)\\s+)*(?:kitna|kitne|kitni|kitana)\\s+(?:profit|munafa|fayda|faida|loss|nuksan|nuksaan|nuqsan|ghata)\\s+(?:hua|hui|huwa|ho\\s+gaya|$HAI|kiya|banaya)(?:\\s+(?:aaj|$HAI|$KYA))*$") to "what is my p&l today",
        Regex("^(?:(?:aaj|maine|humne)\\s+)*(?:kitna|kitne|kitni)\\s+(?:paisa\\s+|paise\\s+)?(?:gawaya|gavaya|ganwaya|gavaaya|gawaye|haara|haare)(?:\\s+(?:maine|aaj|$HAI|$KYA))*$") to "what is my p&l today",
        Regex("^(?:aaj\\s+)?(?:(?:ka|ki)\\s+)?$MINE(?:aaj\\s+)?(?:(?:ka|ki)\\s+)?(?:p&l|p\\s+l|pnl|mtm|kamai|kamaai|profit\\s+loss)\\s+(?:kitna|kitni|kitne|$KYA)\\s+$HAI$") to "what is my p&l today",
        Regex("^(?:$KYA\\s+)?(?:main|mai|mein|hum)\\s+(?:aaj\\s+)?(?:profit|fayde|faayde|munafe)\\s+(?:mein|me|main)\\s+(?:hoon|hu|hun|hain|$HAI)(?:\\s+$KYA)?$") to "am i in profit today",
        Regex("^(?:$KYA\\s+)?(?:main|mai|mein|hum)\\s+(?:aaj\\s+)?(?:loss|nuksan|nuksaan|ghate)\\s+(?:mein|me|main)\\s+(?:hoon|hu|hun|hain|$HAI)(?:\\s+$KYA)?$") to "am i in loss today",
        // Positions and orders: "positions dikha do", "mere orders kya hain", "koi order pending hai kya".
        Regex("^$MINE(?:(?:sabhi|saare|sab|open|khuli|khule)\\s+)?(positions?|orders?|trades?|holdings?)\\s+$SHOW$") to "show my $1",
        Regex("^(?:koi|kitne|kitni|kaun\\s+si|kaunsi|kaun\\s+se|kaunse)\\s+(?:open\\s+)?(positions?|orders?|trades?)\\s+(?:(?:khuli|khule|open|pending|baaki|bachi|bache|lagi|lage)\\s+)?(?:$HAI|hui\\s+$HAI)(?:\\s+$KYA)?$") to "show my $1",
        // What Boss holds and his money (audit, 5 Oct): "mere paas kya hai", "paisa kitna bacha hai", "account mein kitna paisa hai".
        Regex("^(?:abhi\\s+)?(?:mere|hamare|apne)\\s+(?:paas|pas)\\s+(?:abhi\\s+)?$KYA(?:\\s+$KYA)?\\s+$HAI$") to "show my positions",
        Regex("^(?:(?:mere|apne)\\s+(?:paas|pas)\\s+|(?:account|khate)\\s+(?:mein|me|main)\\s+)?(?:(?:kitna|kitne|kitni)\\s+)?(?:paisa|paise|funds?|balance|margin|cash)\\s+" +
            "(?:(?:kitna|kitne|kitni)\\s+)?(?:bacha|bache|bachi|baaki|available|pada|padi)?\\s*$HAI(?:\\s+$KYA)?$") to "what are my funds",
        // The trade check and a trade idea (audit, 5 Oct): "trade lu kya", "aaj trade karun ya nahi", "kya kharidu".
        Regex("^(?:(?:aaj|abhi|ab)\\s+)?(?:koi\\s+)?trade\\s+(?:lu|loon|lun|le\\s+lu|le\\s+loon|karun|karu|karoon|karna\\s+chahiye|kar\\s+sakta\\s+(?:hoon|hu)|kar\\s+sakte\\s+hain)" +
            "\\s+(?:ya\\s+nahi|ya\\s+nahin|ki\\s+nahi|$KYA)$") to "should i trade now",
        Regex("^(?:(?:aaj|abhi|ab)\\s+)?$KYA\\s+(?:kharidu|kharidun|khareedu|khareedun|lu|loon|lun)(?:\\s+(?:aaj|abhi))?$") to "what should i buy",
        // The owner's bots: "strategies kaise chal rahe hain".
        Regex("^$MINE(strateg(?:y|ies)|arms?|bots?|algos?)\\s+(?:kaise|kaisi|kaisa)\\s+(?:chal\\s+$RAHA\\s+$HAI|$HAI)$") to "how are my $1 doing",
        // Round 5 (5 Oct): how many trades today, the trade check and an idea asked another way, what Jarvis can do.
        Regex("^(?:aaj\\s+)?(?:kitne|kitni)\\s+trades?\\s+(?:hue|hui|huye|huwe|ho\\s+gaye)(?:\\s+aaj)?(?:\\s+$KYA)?$") to "how many trades did i take today",
        Regex("^$KYA\\s+(?:main|mai|mein|hum)\\s+(?:(?:aaj|abhi)\\s+)?trade\\s+(?:karu|karun|karoon|lu|loon|lun|kar\\s+sakta\\s+(?:hoon|hu)|kar\\s+sakti\\s+(?:hoon|hu))$") to "should i trade now",
        Regex("^(?:(?:aaj|abhi)\\s+)?(?:call|calls|ce)\\s+(?:kharidu|khareedu|lu|loon|lun)\\s+ya\\s+(?:put|puts|pe)$|^(?:(?:aaj|abhi)\\s+)?(?:put|puts|pe)\\s+(?:kharidu|khareedu|lu|loon|lun)\\s+ya\\s+(?:call|calls|ce)$") to "what should i buy",
        Regex("^(?:koi|kuch)\\s+(?:trade\\s+)?(?:idea|ideas|tip|tips|suggestion)\\s+(?:do|dijiye|de\\s+do|batao|bataiye|$HAI(?:\\s+$KYA)?)$") to "any trade ideas",
        Regex("^(?:tum|aap|tu)\\s+$KYA\\s+(?:kya\\s+)?kar\\s+(?:sakte|sakti|sakta)\\s+(?:ho|hai|hain|hoon)$") to "what can you do",
        // "Kaun sa index sabse strong hai".
        Regex("^(?:aaj\\s+)?(?:kaun\\s+sa|kaunsa|konsa|kon\\s+sa)\\s+index\\s+(?:sabse\\s+)?(?:strong|mazboot|majboot|tez|upar)\\s+$HAI$") to "which index is strongest",
        // The other ways India asks how the market is (Marathi, Gujarati, Bengali, Telugu, Tamil, Punjabi in Latin script).
        Regex("^(?:(aaj|abhi)\\s+)?(?:share\\s+)?(?:market|bazaar|bazar)\\s+(?:kemon(?:\\s+ache|\\s+achhe)?|kasa\\s+(?:aahe|ahe|hai)|kem\\s+che|ela\\s+undi|eppadi\\s+irukku|da\\s+ki\\s+haal\\s+(?:hai|aa|ae))$") to "$1 how is the market",
        Regex("^(?:(aaj|abhi)\\s+)?(.+?)\\s+(?:kemon(?:\\s+ache|\\s+achhe)?|kasa\\s+(?:aahe|ahe)|kem\\s+che|ela\\s+undi|eppadi\\s+irukku|da\\s+ki\\s+haal\\s+(?:hai|aa|ae))$") to "$1 how is $2",
        // One market: "Nifty kya chal raha hai", "BankNifty ka kya haal hai", "Sensex mein kya ho raha hai".
        Regex("^(?:(aaj|abhi)\\s+)?(.+?)\\s+(?:(?:mein|me|main)\\s+)?(?:$KYA\\s+chal\\s+$RAHA|$KYA\\s+ho\\s+$RAHA|kaisa\\s+chal\\s+$RAHA|kaisi\\s+chal\\s+$RAHA)\\s+$HAI$") to "$1 how is $2",
        Regex("^(?:(aaj|abhi)\\s+)?(.+?)\\s+(?:ka|ki)\\s+(?:$KYA\\s+haal|haal\\s+$KYA|haal\\s+kaisa)\\s+$HAI$") to "$1 how is $2",
        // Its price: "Nifty kitne pe hai", "BankNifty ka rate kya hai", "Nifty kahan hai".
        Regex("^(.+?)\\s+(?:kitne|kitna|kis\\s+level)\\s+(?:pe|par)\\s+(?:$HAI|chal\\s+$RAHA\\s+$HAI|trade\\s+kar\\s+$RAHA\\s+$HAI)$") to "what is $1 price",
        Regex("^(.+?)\\s+(?:ka|ki)\\s+(?:rate|bhav|bhaav|price|bhaw)\\s+(?:$KYA|kitna)\\s+$HAI$") to "what is $1 price",
        Regex("^(.+?)\\s+(?:kahan|kaha|kidhar)\\s+(?:$HAI|chal\\s+$RAHA\\s+$HAI|trade\\s+kar\\s+$RAHA\\s+$HAI)$") to "where is $1 trading",
        // News: "aaj ki news kya hai", "koi khabar hai kya", "news sunao".
        Regex("^(?:aaj\\s+)?(?:(?:ki|ka)\\s+)?(?:koi\\s+)?(?:taaza\\s+|taza\\s+|nayi\\s+)?(?:news|khabar|khabren|samachar)\\s+(?:$KYA\\s+$HAI|$HAI(?:\\s+$KYA)?|batao|sunao|dikhao)$") to "any news today",
    )

    /** Markets that a market-only line above must name ("Nifty kahan hai" asks a price; "Boss kahan hai" asks nothing). */
    private val MARKET_ONLY = setOf("what is \$1 price", "where is \$1 trading", "\$1 how is \$2")

    /**
     * [text] with a common Hinglish question ([QUESTIONS]) read as its English, then [normalize]d; [text] itself when
     * no such question is in it. For reading QUESTIONS only - commands and orders are read from [normalize].
     */
    fun question(text: String): String {
        val t = text.lowercase().replace(Regex("[^a-z0-9.,&% ]"), " ").replace(Regex("[.,]+(?=\\s|$)"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("\\s+(?:na|yaar|zara|jarvis|boss|please|ji)$"), "").replace(Regex("^(?:(?:jarvis|boss|hey|ok|okay|zara|yaar)\\s+)+"), "")
        for ((r, to) in QUESTIONS) {
            val m = r.find(t) ?: continue
            if (to in MARKET_ONLY && Market.mentioned(Heard.fix(m.groupValues.last())).isEmpty()) continue
            return normalize(r.replace(t, to).replace(Regex("\\s+"), " ").trim())
        }
        return text
    }

    /** "haan" / "nahi" and friends: true, false, or null when it is neither. */
    fun yesNo(text: String): Boolean? {
        val t = " " + text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (Regex(" (nahi|nahin|nahii|nhi|nai|na|mat karo|mat|rehne do|ruko|cancel karo) ").containsMatchIn(t)) return false
        if (Regex(" (haan|haa|ha|han|ji|ji haan|bilkul|theek hai|thik hai|kar do|kardo|le lo|lelo|chalo) ").containsMatchIn(t)) return true
        return null
    }
}
