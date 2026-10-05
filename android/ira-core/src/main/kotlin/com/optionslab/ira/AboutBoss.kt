package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * What Jarvis knows about Boss (goals and memory, round 2; Boss, 5 Oct): the things Boss tells him about himself and his
 * trading - "I don't trade on Fridays", "remember my target is 2000 a day", "remind me I get greedy after a win" - kept
 * with his other notes ([Memory]), brought up when they bear on the moment (in the morning plan, and when Boss asks
 * "should I trade now?"), listed on "what do you know about me?" and dropped on "forget that" / "forget that I don't
 * trade on Fridays".
 *
 * A fact only shapes what Jarvis SAYS: nothing here places, changes, stops or starts anything, sets a target or a
 * limit, or loosens one. (A note that is also one of Boss's rules - [BossRules] - only ever holds a trade idea back.)
 * Secrets are never kept, and nothing that would act (an order or a command) is ever taken as a fact. Pure.
 */
object AboutBoss {
    /** What a fact is about: how it is grouped when listed, and when it is brought up. */
    enum class Kind(val label: String) {
        TARGET("Your target"), LOSS("Your loss limit"), TEMPER("What to watch in you"), DAYS("Your days"),
        STYLE("How you trade"), OTHER("Other things you told me"),
    }

    /** When a fact is brought up: the morning plan, or Boss's "should I trade now?". */
    enum class At { MORNING, CHECK }

    /** The moment: [dayPnl] Boss's day so far (null: not known); [expiryToday] an index expires today. */
    data class Moment(val today: LocalDate, val at: At, val dayPnl: Double? = null, val expiryToday: Boolean = false)

    /** "Forget that" ([last]: the last thing kept), or "forget that I don't trade on Fridays" ([about]: the words given). */
    data class Forget(val last: Boolean, val about: String = "")

    /** Facts brought up at once, at most (Boss is reminded, not lectured). */
    const val MAX_SAID = 3

    private fun t(s: String) = " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", "").replace(",", ""), ".:") + " "

    private val LEAD = Regex("(?i)^\\s*(?:(?:hey |ok )?jarvis[,!.]?\\s+|boss[,!.]?\\s+|please\\s+)*")

    /** Said before a fact: "remind me (that) ...", "note that ...", "about me: ...", "just so you know, ...". */
    private val TOLD = Regex("(?i)^(?:remind me(?: that)?|(?:please )?note(?: down)? that|fyi[,:]?|for your information[,:]?|about me[:,-]|" +
        "a thing about me[:,-]|something about me[:,-]|(?:just )?so you know[,:]?|you should know(?: that)?|know this about me[:,-]?)\\s+")

    private const val ADVERB = "(?:dont|do not|never|rarely|seldom|usually|always|only|mostly|normally|generally|often|prefer to|like to|love to|hate to|tend to|try to|try not to|avoid|cant|can not|cannot|should not|shouldnt|must not|mustnt)"
    private const val VERB = "(?:trade|trading|trades|buy|buying|sell|selling|short|shorting|scalp|scalping|hold|holding|carry|carrying|take|taking|enter|entering|exit|exiting|" +
        "book|booking|average|averaging|chase|chasing|touch|risk|play|use|overtrade|over trade|revenge trade|look at|watch|check)"
    private const val MOOD = "(?:greedy|impatient|scared|fearful|nervous|overconfident|over confident|emotional|anxious|reckless|careless|tilted|angry|" +
        "desperate|lazy|stubborn|hasty|jumpy|fomo|afraid)"
    private val SELF = listOf(
        // "I don't trade on Fridays", "I only trade BankNifty", "I never hold overnight", "I tend to overtrade on expiry days".
        Regex("^ (?:i|im|i am) $ADVERB (?:to )?$VERB "),
        // "I get greedy after a win", "I'm impatient in the first hour", "I go on tilt after two losses".
        Regex("^ (?:im|i am|i (?:get|become|go|feel|turn|grow|can get|tend to get|usually get|always get|often get|can be|tend to be))(?: (?:very|too|a bit|really|so|quite))? (?:$MOOD|on tilt) "),
        // "I overtrade after a loss", "I revenge trade", "I move my stop", "I panic in fast markets".
        Regex("^ i (?:usually |always |often |tend to |sometimes )?(?:overtrade|over trade|revenge trade|panic|chase|hold (?:my )?losers|hold on to losers|move my stops?|" +
            "average down|book (?:too )?early|exit (?:too )?early|cut (?:my )?winners|let losers run|freeze|hesitate) "),
        // "My weakness is chasing breakouts", "my style is scalping", "my max loss is 3000 a day".
        Regex("^ my (?:weakness|strength|style|edge|setup|favourite setup|favorite setup|rule|routine|habit|problem|mistake|biggest mistake|" +
            "max loss|maximum loss|daily loss limit|loss limit|stop for the day|capital|risk per trade|weekly target|monthly target) (?:is|are) "),
    )

    private val SECRET = Regex("(?i)\\b(m?pin|tpin|pass(word|code|wd|phrase|key)?|pass phrase|pwd|otp|totp|2fa|api[ _-]?key|secret|token|login|cvv|cvc|" +
        "security (answer|question)|card number|account number|user ?id|client ?id)\\b")
    private val QUESTION = Regex("^ (what|why|how|when|where|which|who|do|does|did|can|could|should|would|will|is|are|am i|shall) ")

    /** Words that would act (an order or a command): never a fact. */
    private fun acts(text: String): Boolean = runCatching {
        Commands.parse(text) != null || Ask.parse(text).let { it.order != null || it.command != null }
    }.getOrDefault(true)

    /**
     * Something Boss told about himself, in his own words ("I get greedy after a win"), or null. Only what plainly speaks
     * of himself and his trading: never a question, a secret, an order or a command, and "remind me" with a time is a
     * reminder ([Reminder]), not a fact.
     */
    fun fact(text: String): String? {
        val raw = text.trim()
        if (raw.endsWith("?") || raw.length > 300) return null
        val led = LEAD.replace(raw, "")
        val told = TOLD.find(led)
        if (told != null && told.value.trim().lowercase(Locale.ENGLISH).startsWith("remind") && Later.mentionsTime(led)) return null
        val what = (if (told != null) led.substring(told.range.last + 1) else led).trim().trimEnd('.', '!').trim()
        val s = t(what)
        if (!rx("^ (i|im|my) ").containsMatchIn(s) || QUESTION.containsMatchIn(s)) return null
        if (Secrets.hasSecret(what) || SECRET.containsMatchIn(what)) return null
        if (what.split(" ").size < 3) return null
        // Told as a fact ("remind me I ...", "note that my ..."), anything about himself; else only the plain patterns.
        if (told == null && SELF.none { it.containsMatchIn(s) }) return null
        if (acts(what)) return null
        return what.replaceFirstChar { it.uppercase(Locale.ENGLISH) }
    }

    private val WEEKDAY = DayOfWeek.values().associateWith { it.name.lowercase(Locale.ENGLISH) }

    private val WIN = Regex(" after (a |my |the )?(win|wins|winning|good (day|trade|start)|big (win|day|trade)|profit|profitable|green) | when i m up | when i am up | after i win ")
    private val LOSS_AFTER = Regex(" after (a |my |the |two |2 |three |3 )?(loss|losses|losing|bad (day|trade|start)|big loss|red) | when i m down | when i am down | after i lose ")

    /** What a fact is about. */
    fun kind(fact: String): Kind {
        val s = t(fact)
        return when {
            rx(" (max|maximum|daily|day) loss | loss limit | stop for the day | lose more than | stop (trading )?(at|after) (a )?(loss|losing) ").containsMatchIn(s) -> Kind.LOSS
            rx(" (target|goal|aim) | make \\d").containsMatchIn(s) -> Kind.TARGET
            rx(" $MOOD | on tilt | overtrade | over trade | revenge | chase | chasing | panic | freeze | hesitate | weakness | mistake | losers | move my stop | average down | (book|exit) (too )?early ").containsMatchIn(s) ||
                WIN.containsMatchIn(s) || LOSS_AFTER.containsMatchIn(s) -> Kind.TEMPER
            WEEKDAY.values.any { s.contains(" $it ") || s.contains(" ${it}s ") } || rx(" (expiry|weekend|weekends|budget day|rbi day|event days?) ").containsMatchIn(s) -> Kind.DAYS
            rx(" $VERB | nifty | banknifty | bank nifty | finnifty | sensex | options? | overnight | lots? | capital | style | setup | edge | routine | morning | afternoon ").containsMatchIn(s) -> Kind.STYLE
            else -> Kind.OTHER
        }
    }

    /** The amount in a target or a loss limit ("2000 a day", "5k", "1.5 lakh"), or null. */
    fun amount(fact: String): Double? = rx(" (?:rs |inr |rupees |₹)?(\\d+(?:\\.\\d+)?) ?(k|thousand|lakh|lac)? ").find(t(fact.replace("₹", " rs ")))?.let { m ->
        val n = m.groupValues[1].toDoubleOrNull() ?: return@let null
        (n * when (m.groupValues[2]) { "k", "thousand" -> 1_000.0; "lakh", "lac" -> 100_000.0; else -> 1.0 }).takeIf { it >= 100 }
    }

    private fun day(d: DayOfWeek) = d.name.lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase(Locale.ENGLISH) }

    /** The fact names [today]'s weekday ("I don't trade on Fridays"), or expiry when an index expires today. */
    private fun forToday(fact: String, m: Moment): Boolean = forTodayByWeekday(fact, m) || (m.expiryToday && t(fact).contains(" expiry"))

    /**
     * What Boss told about himself that bears on [m], said back in a line ([MAX_SAID] facts at most), or null: his days
     * (the weekday, an expiry), what to watch in him (after a win when he is up, after a loss when he is down; the rest
     * always), his target (the morning; at a check once he is past it) and his loss limit (at a check once he is past it).
     * Words only.
     */
    fun recall(notes: List<String>, m: Moment): String? {
        val said = ArrayList<String>()
        val facts = notes.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { t(it) }.reversed()     // newest first
        val up = (m.dayPnl ?: 0.0) > 0
        val down = (m.dayPnl ?: 0.0) < 0
        facts.filter { kind(it) == Kind.DAYS && forToday(it, m) }.take(1).forEach { f ->
            said += "it's ${if (forTodayByWeekday(f, m)) day(m.today.dayOfWeek) else "an expiry day"} and you told me \"$f\""
        }
        facts.filter { kind(it) == Kind.TEMPER }.filter { f ->
            val s = t(f)
            when {
                WIN.containsMatchIn(s) -> up
                LOSS_AFTER.containsMatchIn(s) -> down
                else -> true
            }
        }.take(2).forEach { f ->
            val s = t(f)
            said += when {
                WIN.containsMatchIn(s) -> "you're up today, and you asked me to remind you: \"$f\""
                LOSS_AFTER.containsMatchIn(s) -> "you're down today, and you asked me to remind you: \"$f\""
                else -> "you asked me to remind you: \"$f\""
            }
        }
        facts.firstOrNull { kind(it) == Kind.TARGET && amount(it) != null }?.let { f ->
            val a = amount(f)!!
            val pnl = m.dayPnl
            when {
                m.at == At.MORNING -> said += "your target, as you told me: \"$f\" (I watch a day's target only when you set it: \"my target today is ${a.toLong()}\")"
                pnl != null && pnl >= a -> said += "you're past your target of ${AppFacts.amt(a)} (\"$f\") - you told me that's your day"
            }
        }
        facts.firstOrNull { kind(it) == Kind.LOSS && amount(it) != null }?.let { f ->
            val a = amount(f)!!
            val pnl = m.dayPnl
            if (m.at == At.CHECK && pnl != null && pnl <= -a) said += "you're down past the ${AppFacts.amt(a)} you told me you stop at (\"$f\")"
        }
        if (said.isEmpty()) return null
        val lines = said.distinct().take(MAX_SAID)
        return "From what you told me about yourself, Boss: " + lines.joinToString("; ") + "."
    }

    private fun forTodayByWeekday(fact: String, m: Moment): Boolean {
        val s = t(fact); val w = WEEKDAY.getValue(m.today.dayOfWeek)
        return s.contains(" $w ") || s.contains(" ${w}s ")
    }

    // ---- asked by voice ------------------------------------------------------------------------------------------------

    private fun norm(text: String) = t(text).replace(rx("^ ((hey |ok )?jarvis |boss |please )+"), " ").replace(rx(" (jarvis|boss|please) $"), " ")

    private val KNOW = Regex("^ (so )?(what do you know about me|what all do you know about me|what have you learn(ed|t) about me|what do you remember about me|" +
        "tell me what you know about me|what have i told you about (me|myself)|what do you know of me|what you know about me|how well do you know me|" +
        "mere baare (mein|me) (kya|kitna) (jaante|jante|pata) (ho|hai)|tum mere baare (mein|me) kya jaante ho|" +
        // His own stated rules are among what he told (routing audit, round 9): "what are my rules", "mere rules kya hain".
        "what are my (own )?(trading )?rules|what (rules|trading rules) (did i|have i) (tell|told|give|given|gave|set) you|" +
        "(tell me|remind me of|read me|list) my (own )?(trading )?rules|what rules do i (have|follow|go by)|" +
        "mere (apne )?(trading )?(rules|niyam|usool) (kya|kaun se) (hain|hai))( again| please)? $")

    /** "What do you know about me?" */
    fun knowAsked(text: String): Boolean = KNOW.containsMatchIn(norm(text))

    /** Not one of Boss's facts: his goals, all his notes ([Memory.forgetAsked]), what was learned, the conversation, settings. */
    private val NOT_A_FACT = Regex(" (goals?|notes|everything|preferences|what you learn(ed|t)|learn(ed|t)|lessons?|wordings?|conversation|chat|reminders?|alarms?|events?|what i rejected?) ")

    /** "Forget that", "forget that I don't trade on Fridays", "forget my target" (one fact), or null. */
    fun forgetAsked(text: String): Forget? {
        val s = norm(text)
        if (Memory.forgetAsked(text)) return null
        if (rx("^ (forget|drop|delete|remove|scratch|erase) (that|this|the last (thing|note|one|fact)( i told you)?|what i just (said|told you)|the last thing i (said|told you)) $").containsMatchIn(s))
            return Forget(true)
        val m = rx("^ (?:forget|drop|delete|remove|erase) (?:that |about |the (?:note|fact|thing|bit) about |what i (?:said|told you) about )(.+) $").find(s)
            ?: rx("^ (?:forget|drop|delete|remove|erase) (my .+) $").find(s) ?: return null
        val about = m.groupValues[1].trim()
        if (NOT_A_FACT.containsMatchIn(" $about ") || about.length < 3) return null
        return Forget(false, about)
    }

    private val STOP = setOf("i", "im", "my", "me", "the", "a", "an", "on", "in", "at", "of", "to", "is", "am", "are", "and", "that", "this", "about",
        "get", "after", "when", "it", "you", "your", "told", "said", "thing", "note", "jarvis", "boss", "please", "be", "do", "for")

    private fun words(s: String): Set<String> = t(s).trim().split(" ").filter { it.length > 1 && it !in STOP }.map { it.removeSuffix("s") }.toSet()

    /** Which kept item [f] means (the last one kept, or the one its words name - the newest when two do), or null. */
    fun pick(items: List<Memory.Item>, f: Forget): Memory.Item? {
        if (items.isEmpty()) return null
        if (f.last) return items.last()
        val want = words(f.about)
        if (want.isEmpty()) return null
        val need = kotlin.math.ceil(want.size * 0.75).toInt().coerceAtLeast(1)
        return items.withIndex().map { (n, it) -> Triple(n, it, (words(it.text) intersect want).size) }
            .filter { it.third >= need }.maxWithOrNull(compareBy<Triple<Int, Memory.Item, Int>>({ it.third }, { it.first }))?.second
    }

    /** Said after a fact is dropped (or none matched). */
    fun forgot(item: Memory.Item?, f: Forget): String = when {
        item != null -> "Done, Boss: I've forgotten \"${item.text}\"."
        f.last -> "There's nothing you've told me to forget, Boss."
        else -> "I don't have anything like \"${f.about}\" from you, Boss. Ask \"what do you know about me?\" to hear what I keep."
    }

    /** Said when a fact is kept. */
    fun noted(fact: String): String {
        val whenSaid = when (kind(fact)) {
            Kind.DAYS -> "I'll bring it up that day, in my morning plan and when you ask whether to trade"
            Kind.TEMPER -> "I'll bring it up when it applies, in my morning plan and when you ask whether to trade"
            Kind.TARGET -> "I'll say it in my morning plan, and tell you when you're past it if you ask whether to trade"
            Kind.LOSS -> "I'll tell you when you're past it if you ask whether to trade"
            else -> "I'll keep it in mind"
        }
        return "Noted about you, Boss: \"$fact\". $whenSaid - it only shapes what I say." + BossRules.saidBack(fact) +
            " Say \"forget that\" to drop it, or \"what do you know about me?\" to hear it all."
    }

    /** "What do you know about me?": every kept note, grouped by what it is about, the newest first. */
    fun lines(items: List<Memory.Item>): String {
        if (items.isEmpty()) return "You haven't told me about yourself yet, Boss. Tell me things like \"I don't trade on Fridays\", " +
            "\"remember my target is 2000 a day\" or \"remind me I get greedy after a win\", and I'll bring them up when they matter."
        val shown = items.takeLast(15).reversed().distinctBy { t(it.text) }
        val groups = shown.groupBy { kind(it.text) }
        val body = Kind.values().filter { it in groups }.joinToString(" ") { k ->
            k.label + ": " + groups.getValue(k).joinToString("; ") { "\"${it.text}\" (${it.day})" } + "."
        }
        val more = if (items.size > shown.size) " (and ${items.size - shown.size} older)" else ""
        return "What you've told me, Boss$more. $body It only shapes what I say - nothing I keep about you acts. " +
            "Say \"forget that ...\" to drop one, or \"forget what I told you\" for all."
    }
}
