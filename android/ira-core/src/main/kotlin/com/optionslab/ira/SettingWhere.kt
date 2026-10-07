package com.optionslab.ira

/**
 * "Where is the quiet hours setting?", "how do I turn off market alerts?", "backup ki setting kahan hai" (07 Oct 2026): where
 * a setting is, from the Settings search's own catalogue ([SettingsIndex]) - its path ("Settings → Voice and AI model → What
 * Jarvis does by itself → Market alerts") and that the search at the top of Settings opens it, highlighted. A reply only:
 * nothing is switched, set or opened from here, and a guarded switch keeps its PIN, fingerprint or confirmation.
 *
 * Only where / how-to questions: "turn off liquidity", "switch off solo", "stop jarvis talking" and "mute" are commands and
 * keep their routes (they never reach here - the hub's guard leaves anything that acts alone), and "how do I stop ORB" (an
 * arm), "how do I close my position" (a trade) or "where is nifty" (the market) are not settings. Pure.
 */
object SettingWhere {
    /** What was asked about ([topic]: Boss's words for the setting, e.g. "quiet hours", "market alerts"). */
    data class Q(val topic: String)

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |okay jarvis |boss |so |and |please |ok |okay |hi |hello |bata |batao |tell me |" +
        "can you tell me |could you tell me |do you know |any idea )*"
    private const val TAIL = "( boss| jarvis| please| then| now| yaar| na| bhai| in the app| in this app| in settings| in the settings)* $"
    private const val SW = "(setting|settings|switch|switches|option|options|toggle)"
    private const val WHERE = "(where is|where are|wheres|where do i find|where can i find|where will i find|where would i find|where do i change|" +
        "where can i change|where do i set|where can i set)"
    private const val OFF = "(turn|switch) (off|on)"
    /** Boss's words for the setting. */
    private const val X = "(?<topic>.+?)"
    private const val HINDI_DO = "(karu|karun|karoon|karte hai|karte hain|karte he|hota hai|hoga|kare|karein|karen|kar sakta hoon|kar sakte hai|kar sakte hain)"

    /** Said with a setting's own word ("setting", "switch", "toggle"...): taken even when nothing is found by that name. */
    private val NAMED = listOf(
        // "where is the quiet hours setting", "where's the mute switch", "where do I find the backup option" (never the kill
        // switch: "kill" is in [NOT_EVER], so it is not taken here)
        "^ $LEAD$WHERE( the| my)? $X $SW$TAIL",
        // "where is the setting for quiet hours", "where are the settings for alerts"
        "^ $LEAD$WHERE the $SW (for|of) (the |my )?$X$TAIL",
        // "where is quiet hours in settings"
        "^ $LEAD$WHERE( the| my)? $X in (the )?settings( boss| jarvis| please)* $",
        // "backup ki setting kahan hai", "quiet hours setting kaha hai", "liquidity ka switch kidhar hai"
        "^ $LEAD$X (ki |ka |ke )?$SW (kahan|kaha|kahaan|kidhar|kidar)( pe| par)?( hai| he| hain| milegi| milega| milta hai| milti hai)?$TAIL",
    ).map { Regex(it) }

    /** Asked how, with no setting word ("how do I turn off market alerts"): taken only when it names a setting. */
    private val HOW = listOf(
        // "where do I turn off market alerts", "where can I switch on the fingerprint"
        "^ $LEAD(where) (do|can|would|should) i $OFF (the |my )?$X( $SW)?$TAIL",
        // "how do I turn off quiet hours", "how can I switch on the fingerprint", "how should I turn off the guard"
        "^ $LEAD(how) (do|can|would|should) i $OFF (the |my )?$X( $SW)?$TAIL",
        // "how to turn off notifications", "how do you switch off the opening read"
        "^ $LEAD(how) (to|do you|does one|do we|can we) $OFF (the |my )?$X( $SW)?$TAIL",
        // "how do I turn quiet hours off"
        "^ $LEAD(how) (do|can) i (turn|switch) (the |my )?$X (off|on)$TAIL",
        // "how do I change the theme", "how do I disable the fingerprint","how do I stop jarvis talking", "how to mute jarvis"
        "^ $LEAD(how) (do i|can i|to|do you) (disable|enable|change|stop|mute|unmute|silence|find) (the |my )?$X( $SW)?$TAIL",
        // "solo kaise band karu", "market alerts kaise off karte hai", "quiet hours kahan se band karu"
        "^ $LEAD$X (kaise|kese|kaisey) (band|off|on|chalu|change|mute) $HINDI_DO$TAIL",
        "^ $LEAD$X (kahan|kaha|kahaan|kidhar)( se)? (band|off|on|chalu|change) $HINDI_DO$TAIL",
    ).map { Regex(it) }

    /**
     * Never a setting, however asked: the market, an arm or a strategy (its switch is under Trade, then Strategies, and "how
     * do I stop ORB" has its own answer), the phone itself.
     */
    private val NOT_EVER = setOf("nifty", "banknifty", "finnifty", "sensex", "orb", "fresh", "sweep", "fade", "bot", "algo", "arm",
        "strategy", "strategie", "pine", "script", "phone", "wifi", "bluetooth", "tv", "computer", "app", "kill")

    /** Never a setting asked how (no setting word said): a trade, a position or an order (closed or placed elsewhere). */
    private val NOT_HOW = NOT_EVER + setOf("position", "trade", "call", "put", "stoploss", "sl", "buy", "sell", "order", "ticket", "gtt")

    private val MUTE = Regex("^ $LEAD(how|where) (do i|can i|to|do you) (mute|unmute|silence) (jarvis|you|him|the voice|your voice)?$TAIL")

    /** Hinglish and filler words around the topic. */
    private val TRIM = Regex("^((the|my|a|an|ye|yeh|is|iss) )+|( (ki|ka|ke|ko|wala|wali|wale|setting|settings|in|in the|of|for|the))+$")

    /** "Where is the X setting", "how do I turn off X", "X setting kahan hai": what X is, or null. */
    fun asked(text: String): Q? = kept.of(text) { fresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val kept = Kept<Q?>(64)

    private fun fresh(text: String): Q? {
        val t = Spaced.joined(text)
        if (t.length > 120) return null
        for (rx in NAMED) {
            val m = rx.find(t) ?: continue
            val topic = topicOf(m) ?: continue
            val terms = SettingsIndex.terms(topic)
            if (terms.isEmpty() || terms.any { it in NOT_EVER }) continue
            return Q(topic)
        }
        // "How do I mute Jarvis", "how to unmute you": the mute itself.
        if (MUTE.containsMatchIn(t)) return Q("mute")
        for (rx in HOW) {
            val m = rx.find(t) ?: continue
            val topic = topicOf(m) ?: continue
            val terms = SettingsIndex.terms(topic)
            if (terms.isEmpty() || terms.any { it in NOT_HOW }) continue
            if (SettingsIndex.strong(topic, gold = false).isEmpty() && SettingsIndex.strong(topic, gold = true).isEmpty()) continue
            return Q(topic)
        }
        return null
    }

    /** The pattern's `topic` group, without the filler words around it. */
    private fun topicOf(m: MatchResult): String? {
        val raw = (m.groups as? MatchNamedGroupCollection)?.get("topic")?.value?.trim() ?: return null
        val topic = TRIM.replace(raw, "").trim()
        return topic.takeIf { it.isNotEmpty() && it.length <= 40 }
    }

    // ---- the answer -------------------------------------------------------------------------------------------------

    /** How many places are named at most. */
    const val NAMED_MAX = 3

    const val NOTHING_SWITCHED = "I only say where it is - nothing is switched from here."
    const val GUARDED = "It keeps its own check there (your PIN, fingerprint or a confirmation), as always."
    const val ARMS = "An arm's own switch (Liquidity 15+5, Hero, the ORB arms) is under Trade, then Strategies."

    /**
     * Where [q]'s setting is in this build ([gold]: IraGoldAlgo's Settings): the best match's path - or up to [NAMED_MAX] when
     * several fit as well - and that the search at the top of Settings opens it. [pages]: the pages this build's Settings
     * shows, the same set its search is given ([SettingsIndex.search]; null: all) - a page that search would not list is never
     * named. Words only; nothing is switched.
     */
    fun answer(q: Q, gold: Boolean, pages: Set<String>? = null): String {
        val found = SettingsIndex.search(q.topic, gold, pages)
        val search = SettingsIndex.said(q.topic).joinToString(" ")
        if (found.isEmpty()) return "Boss, I can't find a setting called \"${q.topic}\". The search at the top of Settings finds every setting " +
            "by its name or what it does. $NOTHING_SWITCHED"
        val shown = found.take(NAMED_MAX)
        val out = StringBuilder()
        if (shown.size == 1) out.append("Boss, it's under ${shown[0].pathText}.")
        else {
            out.append("Boss, ${if (found.size > NAMED_MAX) "the closest ${shown.size} of ${found.size}" else "${shown.size} settings fit"}: ")
            out.append(shown.joinToString("; ") { it.pathText }).append('.')
        }
        out.append(" Type \"$search\" in the search at the top of Settings and tap it: it opens right at it, highlighted. ")
        out.append(NOTHING_SWITCHED)
        if (shown.any { it.guarded }) out.append(' ').append(GUARDED)
        if (!gold && Regex("\\b(liquidity|hero|orb)\\b").containsMatchIn(q.topic)) out.append(' ').append(ARMS)
        return out.toString()
    }
}
