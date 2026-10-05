package com.optionslab.ira

import java.util.Locale

/**
 * Numbers and times as the phone's recognizer writes them (2026-10-05): "twenty four thousand five hundred", "24 5
 * hundred", "chaubees hazaar paanch sau", "2 lakh", "point five percent", "dedh" / "dhai" (1.5 / 2.5), "saade das baje",
 * "half past ten", "ten thirty am" - read as digits ("24500", "0.5 percent", "at 10:30") so the questions that read
 * digits (how far, if Nifty moves 100 points, an option's price, where was Nifty at 10:30) understand them.
 *
 * QUESTIONS only: [digits] is the plain reading; [question] gives it only when neither the words as heard nor the words
 * read could act (a command, an order, an alarm, a reminder, a goal, a setting, a note to keep) - so nothing read here can
 * ever create or change an order, a quantity, a price, an alarm level or a time anything is set for. Pure.
 */
object Spoken {
    private val EN = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90)

    /** Hindi numbers as the recognizer spells them (the ones Boss says for index levels, strikes and times). */
    private val HI: Map<String, Int> = buildMap {
        fun put(v: Int, vararg ws: String) = ws.forEach { put(it, v) }
        put(1, "ek"); put(2, "do"); put(3, "teen", "tin"); put(4, "char", "chaar"); put(5, "paanch", "panch", "paach")
        put(6, "chhe", "chhah", "chah", "cheh", "chhai"); put(7, "saat"); put(8, "aath", "aat"); put(9, "nau"); put(10, "das", "dus")
        put(11, "gyarah", "gyara", "gyaarah"); put(12, "barah", "bara", "baarah", "baara"); put(13, "terah", "tera")
        put(14, "chaudah", "chauda", "choudah"); put(15, "pandrah", "pandra", "pandhra"); put(16, "solah", "sola"); put(17, "satrah", "satra")
        put(18, "atharah", "athara", "attharah"); put(19, "unnis", "unees", "unis"); put(20, "bees", "bis")
        put(21, "ikkis", "ikkees"); put(22, "bais", "baees", "baais"); put(23, "teis"); put(24, "chaubees", "chaubis", "chobis", "choubees", "chaubiss")
        put(25, "pachees", "pachis", "pacchis", "pachchis"); put(26, "chhabbis", "chabbis", "chhabis"); put(27, "sattais", "satais", "sattaees")
        put(28, "atthais", "athais", "atthaees"); put(29, "untees", "unatis", "untis"); put(30, "tees", "tis")
        put(31, "ikattis", "iktees"); put(32, "battis", "batees"); put(33, "taintis", "tentis"); put(34, "chautis", "chontis"); put(35, "paintis", "pentis")
        put(36, "chattis", "chhattis"); put(37, "saintis"); put(38, "adtis", "artis"); put(39, "untalis"); put(40, "chalis", "chaalis")
        put(41, "ikatalis"); put(42, "bayalis", "byalis"); put(43, "taintalis"); put(44, "chavalis", "chauvalis"); put(45, "paintalis", "pantalis")
        put(46, "chhiyalis"); put(47, "saintalis"); put(48, "adtalis", "artalis"); put(49, "unchas"); put(50, "pachas", "pachaas")
        put(51, "ikyavan", "ikyawan"); put(52, "bavan", "baavan"); put(53, "tirpan", "tirepan"); put(54, "chauvan", "chauwan"); put(55, "pachpan")
        put(56, "chhappan", "chappan"); put(57, "sattavan"); put(58, "atthavan"); put(59, "unsath"); put(60, "saath")
        put(70, "sattar"); put(75, "pachhattar", "pachattar"); put(80, "assi"); put(81, "ikyasi"); put(82, "bayasi"); put(83, "tirasi")
        put(84, "chaurasi"); put(85, "pachasi"); put(90, "nabbe", "nabbay")
    }

    private val MULT = mapOf("hundred" to 100.0, "hundreds" to 100.0, "sau" to 100.0,
        "thousand" to 1e3, "thousands" to 1e3, "hazaar" to 1e3, "hazar" to 1e3, "hajar" to 1e3, "hajaar" to 1e3, "hazzar" to 1e3, "k" to 1e3,
        "lakh" to 1e5, "lakhs" to 1e5, "lac" to 1e5, "lacs" to 1e5, "laakh" to 1e5, "million" to 1e6,
        "crore" to 1e7, "crores" to 1e7, "karod" to 1e7, "karor" to 1e7)
    /** "Saade das" 10.5, "sawa do" 2.25, "paune teen" 2.75: said before a number. */
    private val MOD = mapOf("saade" to 0.5, "sade" to 0.5, "saadhe" to 0.5, "sadhe" to 0.5, "saarhe" to 0.5,
        "sawa" to 0.25, "sava" to 0.25, "savaa" to 0.25, "paune" to -0.25, "pone" to -0.25, "paunay" to -0.25)
    private val FIXED = mapOf("dedh" to 1.5, "derh" to 1.5, "dhed" to 1.5, "dhai" to 2.5, "dhaai" to 2.5,
        "adhai" to 2.5, "arhai" to 2.5, "dhaee" to 2.5)
    /** Words that follow a number and say it is one ("do percent", "teen points"). */
    private val UNITS = setOf("points", "point", "pts", "pt", "percent", "per", "%", "ce", "pe", "call", "calls", "put", "puts", "rupees",
        "rupee", "rs", "rupaye", "rupay", "minutes", "minute", "mins", "min", "hours", "hour", "ghante", "ghanta", "days", "day", "din",
        "strike", "strikes", "baje")
    private val HINDI_TIME_AFTER = setOf("tak", "tk", "se", "ke", "ki", "ko", "baad", "pehle", "pahle")
    private val PREP = setOf("at", "by", "till", "until", "since", "after", "before", "from")
    private val AMPM = Regex("^([ap])\\.?m\\.?$")
    private val DIGITS = Regex("^\\d+(?:\\.\\d+)?$")
    private val GLUED_K = Regex("^(\\d+(?:\\.\\d+)?)k$")
    private val NIFTYISH = setOf("nifty", "bank", "banknifty", "fin", "phin", "fine", "finney", "finnifty")

    private class Tok(val raw: String) {
        val lead: String; val core: String; val trail: String
        init {
            val m = Regex("^([^\\p{L}\\p{N}]*)(.*?)([^\\p{L}\\p{N}%]*)$").find(raw)!!
            lead = m.groupValues[1]; core = m.groupValues[2]; trail = m.groupValues[3]
        }
        val w = core.lowercase(Locale.ENGLISH)
    }

    /** [text] with spoken numbers and times written as digits; [text] itself (exactly) when there are none. */
    fun digits(text: String): String {
        val pre = text.replace(Regex("(?i)\\bo'?\\s?clock\\b"), "oclock").replace(Regex("(?<=[A-Za-z])-(?=[A-Za-z])"), " ")
        val toks = pre.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { Tok(it) }
        val out = ArrayList<String>()
        var changed = false
        var i = 0
        while (i < toks.size) {
            val hit = time(toks, i) ?: number(toks, i)
            if (hit != null) {
                // ("a.m." keeps no stray dot once written "am".)
                val trail = toks[i + hit.first - 1].trail.let { if (Regex("[ap]m$").containsMatchIn(hit.second) && it.startsWith(".")) it.drop(1) else it }
                out += toks[i].lead + hit.second + trail
                i += hit.first; changed = true
            } else { out += toks[i].raw; i++ }
        }
        return if (changed) out.joinToString(" ") else text
    }

    /**
     * [said] with its numbers and times as digits - only when it is a question: [said] itself (exactly) whenever the
     * words as heard or as read could act or set anything (see the class note), or when nothing is left but a number.
     */
    fun question(said: String): String {
        val d = digits(said)
        if (d == said) return said
        if (GUARD.containsMatchIn(norm(said)) || GUARD.containsMatchIn(norm(Hinglish.normalize(said))) || GUARD.containsMatchIn(norm(d))) return said
        // A number alone ("twenty four thousand") asks nothing.
        if (Regex("[a-z]{2,}").findAll(d.lowercase(Locale.ENGLISH)).none { it.value !in UNITS && it.value !in setOf("am", "pm", "at") }) return said
        if (FollowUp.acts(said) || FollowUp.acts(d)) return said
        if (Reminder.asked(said) || Reminder.asked(d) || Reminder.cancelAsked(d) || Memory.toKeep(said) != null || Memory.toKeep(d) != null ||
            Goals.read(said) != null || Goals.read(d) != null || AutoStop.read(said) != null || AutoStop.read(d) != null) return said
        return d
    }

    /** Words that could act or set anything: such words are never read here (they go on as heard, as strict as ever). */
    private val GUARD = Regex(Compound.ACTION.pattern + "| (target|targets|goal|goals|aim|limit|limits|budget|capital|max|maximum|keep|remember|" +
        "forget|save|change|modify|edit|increase|decrease|raise|lower|reduce|add|remove|delete|clear|watch|track|notify|ping|warn|wake|" +
        "tell me when|let me know|sl|stoploss|trailing|automatically|approve|confirm|yaad|bata dena|batana|bolna|setting|settings|" +
        "quantity|qty|exit|stop loss) ")

    private fun norm(s: String) = " " + s.lowercase(Locale.ENGLISH).replace("'", "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    // ---- Times ----

    private fun joinable(t: List<Tok>, k: Int) = k in t.indices && k > 0 && t[k].lead.isEmpty() && t[k - 1].trail.isEmpty()
    private fun w(t: List<Tok>, k: Int) = if (k in t.indices) t[k].w else ""
    private fun ampm(t: List<Tok>, k: Int): String? = if (!joinable(t, k)) null else AMPM.find(t[k].w)?.let { it.groupValues[1] + "m" }

    /** An hour 1..12 said in English or Hindi words ([digitsToo]: or written). */
    private fun hour(t: List<Tok>, k: Int, digitsToo: Boolean): Int? {
        val s = w(t, k)
        val v = EN[s] ?: HI[s] ?: (if (digitsToo && Regex("^\\d{1,2}$").matches(s)) s.toInt() else null)
        return v?.takeIf { it in 1..12 }
    }

    /** Minutes after an hour: "thirty", "forty five", "oh five", "15" (consumed, value). */
    private fun minutes(t: List<Tok>, k: Int): Pair<Int, Int>? {
        if (!joinable(t, k)) return null
        val s = w(t, k)
        if ((s == "oh" || s == "o") && joinable(t, k + 1)) EN[w(t, k + 1)]?.takeIf { it in 1..9 }?.let { return 2 to it }
        EN[s]?.takeIf { it in 10..19 }?.let { return 1 to it }
        TENS[s]?.takeIf { it <= 50 }?.let { tens ->
            val u = if (joinable(t, k + 1)) EN[w(t, k + 1)]?.takeIf { it in 1..9 } else null
            return if (u != null) 2 to tens + u else 1 to tens
        }
        if (Regex("^[0-5]\\d$").matches(s)) return 1 to s.toInt()
        return null
    }

    private fun hm(h: Int, m: Int) = if (m == 0) "$h" else "$h:%02d".format(Locale.ENGLISH, m)

    /** A time said at [i]: (tokens used, as written). */
    private fun time(t: List<Tok>, i: Int): Pair<Int, String>? {
        val s = w(t, i)
        // "Half past ten", "quarter past two", "quarter to eleven".
        if ((s == "half" || s == "quarter") && w(t, i + 1) == "past" && joinable(t, i + 1) && joinable(t, i + 2)) hour(t, i + 2, true)?.let { h ->
            val ap = ampm(t, i + 3)
            return (if (ap != null) 4 else 3) to "$h:${if (s == "half") "30" else "15"}" + (ap?.let { " $it" } ?: "")
        }
        if (s == "quarter" && w(t, i + 1) == "to" && joinable(t, i + 1) && joinable(t, i + 2)) hour(t, i + 2, true)?.let { h ->
            val ap = ampm(t, i + 3)
            return (if (ap != null) 4 else 3) to "${if (h == 1) 12 else h - 1}:45" + (ap?.let { " $it" } ?: "")
        }
        // "Ten o'clock".
        if (w(t, i + 1) == "oclock" && joinable(t, i + 1)) hour(t, i, true)?.let { h ->
            val ap = ampm(t, i + 2)
            return (if (ap != null) 3 else 2) to "$h:00" + (ap?.let { " $it" } ?: "")
        }
        // "Saade das baje", "dedh baje", "paune teen baje", "shaam chaar baje".
        hindiTime(t, i)?.let { return it }
        // "Ten thirty am", "3 15 pm"; "at ten thirty", "after 10 30".
        hour(t, i, true)?.let { h ->
            val m = minutes(t, i + 1)
            if (m != null && !after(t, i + 1 + m.first)) {
                val ap = ampm(t, i + 1 + m.first)
                val wordy = EN.containsKey(s) || HI.containsKey(s) || !Regex("^\\d+$").matches(w(t, i + 1))
                if (ap != null) return (1 + m.first + 1) to "$h:%02d $ap".format(Locale.ENGLISH, m.second)
                if (w(t, i - 1) in PREP && joinable(t, i) && (wordy || m.second >= 10)) return (1 + m.first) to "$h:%02d".format(Locale.ENGLISH, m.second)
            }
            // "Ten am" (a written "10 am" stays as it is).
            if (m == null && (EN.containsKey(s) || HI.containsKey(s))) ampm(t, i + 1)?.let { ap -> return 2 to "$h $ap" }
        }
        return null
    }

    /** A number goes on after [k] ("at ten fifty thousand" is no time). */
    private fun after(t: List<Tok>, k: Int) = joinable(t, k) && (MULT.containsKey(w(t, k)) || w(t, k) == "point")

    private val PARTS = setOf("subah", "savere", "sawere", "shaam", "sham", "raat", "dopahar", "dopehar", "dupahar")

    private fun hindiTime(t: List<Tok>, i: Int): Pair<Int, String>? {
        // "Shaam chaar baje": the part of the day said first is read with the time ("at 4 pm").
        if (w(t, i) in PARTS) return if (!joinable(t, i + 1)) null else hindiTime(t, i + 1)?.let { (n, s) -> n + 1 to s }
        var k = i
        val mod = MOD[w(t, k)]
        if (mod != null) k++
        if (k != i && !joinable(t, k)) return null
        val fixed = if (mod == null) FIXED[w(t, k)] else null
        val h0 = fixed?.toInt() ?: hour(t, k, true) ?: return null
        val bj = w(t, k + 1)
        if (bj !in setOf("baje", "bje", "bajey", "baj", "bajke") || !joinable(t, k + 1)) return null
        var h = h0; var m = 0
        when {
            fixed != null -> m = 30
            mod == 0.5 -> m = 30
            mod == 0.25 -> m = 15
            mod == -0.25 -> { h = if (h0 == 1) 12 else h0 - 1; m = 45 }
        }
        val part = w(t, i - 1)
        val ap = when {
            part in setOf("subah", "savere", "sawere") && joinable(t, i) -> " am"
            !joinable(t, i) -> ""
            part in setOf("shaam", "sham", "raat", "dopahar", "dopehar", "dupahar") && h in 1..11 || part in setOf("dopahar", "dopehar", "dupahar") && h == 12 -> " pm"
            else -> ""
        }
        val before = if (part in PARTS) w(t, i - 2) else part
        val at = if (before in PREP || w(t, k + 2) in HINDI_TIME_AFTER) "" else "at "
        return (k + 2 - i) to at + hm(h, m) + ap
    }

    // ---- Numbers ----

    private enum class Last { NONE, VALUE, MULT }

    /** A number said at [i]: (tokens used, as written), or null when there is none (or digits only, already written). */
    private fun number(t: List<Tok>, i: Int): Pair<Int, String>? {
        var total = 0.0; var cur = 0.0; var last = Last.NONE; var lastMult = Double.MAX_VALUE
        var words = false; var gated = false; var strong = false; var hundred = false
        var mod = 0.0
        var j = i
        fun next(k: Int) = if (joinable(t, k)) w(t, k) else ""
        fun isValue(s: String) = EN.containsKey(s) || TENS.containsKey(s) || HI.containsKey(s) || FIXED.containsKey(s) || DIGITS.matches(s)
        /** Can [v] be added to what is said so far ("twenty" + "four"; never "two" + "five")? */
        fun addable(v: Double): Boolean {
            if (last == Last.NONE) return true
            if (last == Last.MULT && cur == 0.0) return v < lastMult
            if (cur % 1.0 != 0.0) return false
            val r = (cur % 100).toInt()
            return when {
                v < 10 && v % 1.0 == 0.0 -> r % 10 == 0 && r !in 10..19 && (r != 0 || last == Last.MULT)
                else -> r == 0 && last == Last.MULT && v < 100
            }
        }
        while (j < t.size && (j == i || joinable(t, j))) {
            val s = t[j].w
            val n1 = next(j + 1)
            when {
                (s == "a" || s == "an") && last == Last.NONE -> {
                    if (!MULT.containsKey(n1) || n1 == "k") break
                    cur = 1.0; last = Last.VALUE; words = true; strong = true
                }
                MOD.containsKey(s) && last != Last.VALUE && mod == 0.0 -> {
                    if (!isValue(n1) || FIXED.containsKey(n1)) break
                    mod = MOD.getValue(s); words = true; strong = true; gated = true
                }
                s == "half" -> {
                    // "Half a percent", "half percent", "half a lakh".
                    if (last != Last.NONE) break
                    val skipA = n1 == "a" && joinable(t, j + 2)
                    val after = if (skipA) w(t, j + 2) else n1
                    if (after !in setOf("percent", "per", "%") && !(MULT.containsKey(after) && after != "k")) break
                    cur = 0.5; last = Last.VALUE; words = true; strong = true
                    if (skipA) j++
                }
                s == "and" -> {
                    // "Two hundred and fifty"; "one and a half".
                    if (last == Last.VALUE && n1 == "a" && joinable(t, j + 2) && w(t, j + 2) == "half" && cur % 1.0 == 0.0 && cur > 0) {
                        cur += 0.5; strong = true; j += 3; continue
                    }
                    if (last != Last.MULT || !isValue(n1) || FIXED.containsKey(n1)) break
                }
                s == "point" -> {
                    // "Point five", "one point two five".
                    val ds = StringBuilder()
                    var k = j + 1
                    while (joinable(t, k)) {
                        val d = EN[w(t, k)]?.takeIf { it <= 9 } ?: w(t, k).takeIf { Regex("^\\d$").matches(it) }?.toInt() ?: break
                        ds.append(d); k++
                    }
                    if (ds.isEmpty() || cur % 1.0 != 0.0 || last == Last.MULT && cur == 0.0 && total > 0) break
                    cur += "0.$ds".toDouble(); last = Last.VALUE; words = true; strong = true
                    j = k; continue
                }
                GLUED_K.matches(s) -> {
                    if (last != Last.NONE) break
                    total = GLUED_K.find(s)!!.groupValues[1].toDouble() * 1000; lastMult = 1e3; last = Last.MULT; words = true; strong = true
                }
                MULT.containsKey(s) -> {
                    val m = MULT.getValue(s)
                    if (s == "k" && last != Last.VALUE) break
                    if (m == 100.0) {
                        if (hundred || last == Last.MULT && cur == 0.0 && lastMult <= 100) break
                        cur = (if (cur == 0.0) 1.0 else cur) * 100; hundred = true
                        if (lastMult == Double.MAX_VALUE) lastMult = 100.0
                    } else {
                        if (m >= lastMult && !(lastMult == 100.0 && total == 0.0)) break
                        total += (if (cur == 0.0) 1.0 else cur) * m; cur = 0.0; hundred = false; lastMult = m
                    }
                    last = Last.MULT; words = true; strong = true
                }
                isValue(s) -> {
                    val v = (EN[s] ?: TENS[s] ?: HI[s])?.toDouble() ?: FIXED[s] ?: s.toDouble()
                    // "24 5 hundred", "twenty four five hundred", "chaubees paanch sau": 24,500 ("twenty five hundred" is 2,500).
                    if (v in 1.0..9.0 && v % 1.0 == 0.0 && n1 in setOf("hundred", "sau") && last == Last.VALUE && total == 0.0 && !hundred &&
                        cur in 10.0..99.0 && cur % 1.0 == 0.0 && !addable(v)) {
                        total = cur * 1000; cur = v; lastMult = 1e3; strong = true
                    } else {
                        if (!addable(v)) break
                        cur += v + mod
                        if (mod != 0.0) mod = 0.0
                    }
                    if (HI.containsKey(s) || FIXED.containsKey(s) || s == "one" && n1 != "point" && j == i) gated = true
                    if (FIXED.containsKey(s)) strong = strong || MULT.containsKey(n1)
                    if (!DIGITS.matches(s)) words = true
                    last = Last.VALUE
                }
                else -> break
            }
            j++
        }
        if (mod != 0.0) return null
        // A phrase that ends on "and" gives it back ("two hundred and the rest").
        while (j > i && t[j - 1].w == "and") j--
        val n = j - i
        if (n == 0 || !words) return null
        // "Do you", "the one", "ek baar", "Nifty saath": a lone Hindi word (or "one") is a number only with a unit after it.
        if (gated && !strong && next(j).let { it !in UNITS && it != "baje" }) return null
        if (n == 1 && t[i].w == "fifty" && w(t, i - 1) in NIFTYISH) return null
        val value = total + cur
        return n to fmt(value)
    }

    private fun fmt(v: Double): String = if (v % 1.0 == 0.0 && v < 1e15) v.toLong().toString() else
        "%.4f".format(Locale.ENGLISH, v).trimEnd('0').trimEnd('.')
}
