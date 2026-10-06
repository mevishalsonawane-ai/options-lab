package com.optionslab.ira

/**
 * Asked to do something outside IraAlgo (Boss's chat, 5 Oct: "open youtube" landed in "Words I could not place"): open or
 * launch another app, play music or a video, call or message someone, book a cab, order food. Jarvis works only inside
 * the app - nothing outside it is ever done - so he says so politely instead of "I'm not sure". Words only; nothing acts,
 * nothing is opened, and nothing about the account is said.
 *
 * Narrow on purpose: the app's own screens and words ("open the chain", "open settings", "open zerodha", "call oi",
 * "call option kya hai", "play the replay of my last trade") are never taken. A named outside app or service (YouTube,
 * WhatsApp, Swiggy, Uber...) is taken whole, with its verb first; a call or a message to a person only when the words
 * name no market, option or app word at all. Pure.
 */
object OutsideApp {
    const val SAY = "I only work inside IraAlgo, Boss - I can't open other apps, play music or videos, or call or message anyone. " +
        "Ask me about the market, your account or the app."

    /**
     * Asked to search the web for something of the market ("search google for nifty news", round 14): the same no, and what
     * the app itself has instead - its own news desk and the market read. Words only; nothing is searched or opened.
     */
    const val SAY_SEARCH = "I can't search Google or the web, Boss - I only work inside IraAlgo. What I do have: the news desk's own headlines " +
        "(ask \"any news on Nifty\") and the market itself (ask \"how is Nifty\" or \"why did Nifty move\")."

    /** Apps and services that are not IraAlgo. (Zerodha and Kite are left out: the app has its own Zerodha screen.) */
    private const val APPS = "(youtube|you tube|yt|whatsapp|whats app|watsapp|spotify|gaana|jiosaavn|saavn|wynk|netflix|hotstar|jiohotstar|prime video|amazon prime|" +
        "instagram|insta|facebook|fb|twitter|x app|telegram|snapchat|linkedin|chrome|google chrome|google|gmail|google maps|maps|calculator|camera|gallery|" +
        "photos|clock app|calendar app|contacts|dialer|phone app|play store|playstore|app store|uber|ola|rapido|swiggy|zomato|blinkit|zepto|amazon|flipkart|" +
        "paytm|phonepe|phone pe|gpay|google pay|truecaller|music app|music player|radio|fm|browser|spotify app|tradingview|trading view|moneycontrol)"
    private const val LEAD = "^ (?:hey |ok |okay )?(?:jarvis )?(?:please |can you |could you |will you |would you |just )*"
    private const val TAIL = "(?: for me| please| jarvis| boss| now| right now| quickly| app| bhai)* $"

    private val ASKED = listOf(
        // "Open YouTube", "launch Spotify", "start WhatsApp", "go to Instagram", "switch to Chrome"
        "$LEAD(open|launch|start|run|go to|switch to|take me to|show me|bring up|pull up) (the )?$APPS( app)?$TAIL",
        // Hinglish: "YouTube kholo", "WhatsApp khol do", "Spotify chalao"
        "$LEAD$APPS( app)? (kholo|khol do|kholdo|khol de|open karo|open kar do|chalao|chala do|chalu karo|start karo|lagao)$TAIL",
        // "Play music", "play a song on YouTube", "play some songs", "play the radio"
        "$LEAD(play|put on|start playing|start) (some |a |the |my |any )?(music|song|songs|gaana|gaane|gana|video|videos|playlist|podcast|radio|movie|film)( on [a-z ]{1,20})?$TAIL",
        "$LEAD(play|put on) (something|anything) on $APPS$TAIL",
        // "Gaana bajao", "music chalao", "koi song lagao"
        "$LEAD(koi |ek )?(gaana|gaane|gana|music|song|songs|video)( (sunao|bajao|chalao|chala do|laga do|lagao|play karo))$TAIL",
        // "Search Google for ...", "google it"
        "$LEAD(search|look up) (on )?(google|youtube)( for .{1,60})?$TAIL",
        "$LEAD(google|youtube) (it|that|this)$TAIL",
        // "Book an Uber", "order food from Swiggy", "order a pizza"
        "$LEAD(book|call|get) (me )?(an? )?(uber|ola|rapido|cab|taxi|auto|ride|flight|ticket|tickets|train ticket|movie ticket)s?( .{1,40})?$TAIL",
        "$LEAD(order) (some |a |me )?(food|pizza|biryani|burger|groceries|dinner|lunch|coffee|tea|chai)( (from|on) $APPS)?$TAIL",
        "$LEAD(order) .{1,30} (from|on) (swiggy|zomato|blinkit|zepto|amazon|flipkart)$TAIL",
        // "Set an alarm on my phone", "turn on the flashlight", "take a photo", "take a selfie"
        "$LEAD(turn on|turn off|switch on|switch off) (the )?(flashlight|torch|wifi|wi fi|bluetooth|hotspot)$TAIL",
        "$LEAD(take|click) (a |my )?(photo|picture|pic|selfie|screenshot)$TAIL",
    ).map { rx(it) }

    /** A web search asked ("search google for nifty news", "google nifty news", "look up banknifty on google"): taken even with an index named. */
    private val SEARCH = listOf(
        "$LEAD(search|look up|look for|find) (on |in )?(google|youtube|the web|the internet)( for| about)? .{1,60}$TAIL",
        "$LEAD(search|look up|look for|find|check) .{1,60} (on|in) (google|youtube|the web|the internet)$TAIL",
        "$LEAD(search|look up) (online|the web|the internet) (for|about) .{1,60}$TAIL",
        "$LEAD(google|youtube) (for |about )?.{1,60}$TAIL",
        "$LEAD(google|youtube|internet|net) (par|pe|mein|me) .{1,60} (search|dhundo|dekho|check) (karo|kar do|kardo)$TAIL",
    ).map { rx(it) }

    /** A call or a message to a person ("call mom", "message Rahul", "send a WhatsApp to Priya", "mummy ko call karo"): the one capture is the person. */
    private val TO_PERSON = listOf(
        "$LEAD(?:call|phone|dial|ring|video call|message|text|sms|whatsapp|whats app|email|mail|ping) ([a-z]+(?: [a-z]+)?)$TAIL",
        "$LEAD(?:send|write) (?:a |an )?(?:message|msg|text|sms|whatsapp|whats app|email|mail|note) (?:to )?([a-z]+(?: [a-z]+)?)(?: saying .{1,80})?$TAIL",
        "$LEAD(?:send|write) ([a-z]+(?: [a-z]+)?) (?:a |an )(?:message|msg|text|sms|whatsapp|email|mail)(?: saying .{1,80})?$TAIL",
        "$LEAD([a-z]+(?: [a-z]+)?) ko (?:call|phone|message|msg|whatsapp|sms|text|mail|email) (?:karo|kar do|kardo|lagao|laga do|bhejo|bhej do)$TAIL",
    ).map { rx(it) }

    /** Words of the app, the market or the account: with any of these a "call ..." or "message ..." is never a person. */
    private val APP_WORDS = rx(" (oi|open interest|option|options|ce|pe|put|puts|call|calls|side|wall|writing|writers|written|strike|strikes|premium|premiums|" +
        "price|ltp|iv|delta|gamma|theta|vega|spread|spreads|ratio|chain|buy|buying|sell|selling|short|long|leg|legs|position|positions|order|orders|trade|trades|" +
        "alert|alerts|alarm|alarms|support|zerodha|kite|broker|jarvis|ira|iraalgo|me|it|that|this|back|again|later|everyone|all|boss|market|markets|" +
        "news|level|levels|nifty|banknifty|finnifty|sensex|vix|gold|expiry|atm|otm|itm|money|lot|lots|the|of|in|on|at|kya|hai|kitna|kahan|kab|kyun|" +
        "up|down|out|off|now|today|done|ok|okay|yes|no|it|wait|stop|more|less|how|what|why|when|where|who|is|are) ")

    private fun words(text: String) = Spaced.joined(text)

    /** Does [text] ask Jarvis to do something outside IraAlgo (open another app, play music, call or message someone)? */
    fun asked(text: String): Boolean {
        val t = words(text)
        if (t.isBlank()) return false
        if (searched(t)) return true
        if (rx("\\d").containsMatchIn(t)) return false
        if (Market.mentioned(text).isNotEmpty()) return false
        if (ASKED.any { it.containsMatchIn(t) }) return true
        // The person named is never a word of the app or the market ("call oi", "call side", "message me later").
        val who = TO_PERSON.firstNotNullOfOrNull { it.find(t) }?.groupValues?.get(1) ?: return false
        return !APP_WORDS.containsMatchIn(" $who ")
    }

    private fun searched(t: String) = SEARCH.any { it.containsMatchIn(t) } && !rx(" (google pay|gpay|google maps|youtube music) ").containsMatchIn(t) &&
        !rx(" (kholo|khol do|kholdo|khol de|open karo|open kar do|chalao|chala do|chalu karo|start karo|lagao) $").containsMatchIn(t)

    /** What Jarvis says to [text] (taken by [asked]): a web search gets what the app has instead; anything else, [SAY]. */
    fun say(text: String): String = if (searched(words(text))) SAY_SEARCH else SAY
}
