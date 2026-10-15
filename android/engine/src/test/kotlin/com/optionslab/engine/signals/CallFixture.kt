package com.optionslab.engine.signals

import com.optionslab.engine.signals.CallParser.Reading

/**
 * The Signal tester's message fixture (15 Oct 2026): real-style Telegram messages from different channels' styles - the 15 Oct
 * SENSEX sample, hashtags, emojis, Hindi and Hinglish, any order of lines, ranges, cmp, percent stops, stocks, several calls in
 * one message, follow-ups, results posts, adverts, chatter, sell calls, futures, a hidden notification - each with the reading
 * expected from the rules alone ([rules]) and, where the rules are unsure, the stub model's reply ([model]) and the reading
 * expected after both stages ([both]). The stub model is deterministic: its reply is fixed per message.
 */
object CallFixture {
    /** [text]; [rules]: the expected rules-only reading ([describe]); [model]: the stub model's reply (null: never asked or no reply);
     *  [both]: the expected reading after stage (b) (null: as [rules]); [sureBoth]: counts as understood after both stages. */
    data class Case(val channel: String, val text: String, val rules: String, val model: String? = null, val both: String? = null)

    fun describe(r: Reading): String = when (r) {
        is Reading.NewCall -> "CALL " + r.draft.line() + (if (r.sure) "" else " | unsure: " + r.unsure.joinToString("; "))
        is Reading.FollowUp -> "FOLLOW ${r.kind.key}" + (r.targetNo?.let { " #$it" } ?: "") + (r.level?.let { " @${CallParser.num(it)}" } ?: "") +
            (if (r.partial) " partial" else "") + (r.ref?.let { " ref ${it.underlying ?: "?"} ${it.strike?.let { s -> CallParser.num(s) } ?: "?"} ${it.right ?: "?"}" } ?: "")
        is Reading.NotACall -> "NOT " + r.reason + (if (r.maybe) " (maybe)" else "")
    }

    private fun j(action: String, u: String? = null, k: Int? = null, right: String? = null, trigger: Number? = null, stop: Number? = null,
                  targets: List<Number> = emptyList(), follow: String? = null, n: Int? = null, conf: Double = 0.9): String =
        """{"action":"$action","underlying":${u?.let { "\"$it\"" } ?: "null"},"strike":${k ?: "null"},"right":${right?.let { "\"$it\"" } ?: "null"},""" +
            """"expiry":null,"trigger":${trigger ?: "null"},"stop":${stop ?: "null"},"targets":[${targets.joinToString(",")}],""" +
            """"followup":${follow?.let { "\"$it\"" } ?: "null"},"target_no":${n ?: "null"},"confidence":$conf}"""

    val CASES: List<Case> = listOf(
        // Channel A: the 15 Oct sample and its style.
        Case("A", "#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++", "CALL SENSEX 73100 CE above 280, stop none given, targets 310 / 345 / 400"),
        Case("A", "#nifty 25150 PE\nAbove 110\nTarget 125-140-160+++", "CALL NIFTY 25150 PE above 110, stop none given, targets 125 / 140 / 160"),
        Case("A", "T1 done ✅", "FOLLOW target #1"),
        Case("A", "2nd target done 🎯🎯 book half", "FOLLOW target #2"),
        Case("A", "#sensex 73300 CE\nAbove 210\nTarget 240-270-300+++\nSL 185", "CALL SENSEX 73300 CE above 210, stop 185, targets 240 / 270 / 300"),
        Case("A", "SL hit 😔", "FOLLOW stop"),
        // Channel B: capitals, slashes, emojis.
        Case("B", "BANKNIFTY 52000 PE BUY ABOVE 310 SL 280 TGT 340/370/420", "CALL BANKNIFTY 52000 PE above 310, stop 280, targets 340 / 370 / 420"),
        Case("B", "NIFTY 25100 CE ABV 120 🎯 135 150 170 🛑 105", "CALL NIFTY 25100 CE above 120, stop 105, targets 135 / 150 / 170"),
        Case("B", "📢 #NIFTY 25000CE\n➡️ Buy @ 95-100\n🎯 Target: 115/130/150\n❌ SL: 80", "CALL NIFTY 25000 CE near 95-100, stop 80, targets 115 / 130 / 150"),
        Case("B", "🔥🔥 NIFTY 25000 CE 🔥🔥\n✅ ABOVE 120\n🎯 140/160\n🛑 100", "CALL NIFTY 25000 CE above 120, stop 100, targets 140 / 160"),
        Case("B", "Book profit 💰💰", "FOLLOW book"),
        Case("B", "Exit all positions now", "FOLLOW exit"),
        // Channel C: lower case, ranges, near, cmp, percent stops.
        Case("C", "Bank nifty 51500 pe buy range 240-245 sl 210 target 280 320", "CALL BANKNIFTY 51500 PE near 240-245, stop 210, targets 280 / 320"),
        Case("C", "FINNIFTY 23500 CE near 90 sl 75 tgt 110", "CALL FINNIFTY 23500 CE near 90, stop 75, targets 110"),
        Case("C", "MIDCPNIFTY 12800 CE above 160 sl 140 target 190", "CALL MIDCPNIFTY 12800 CE above 160, stop 140, targets 190"),
        Case("C", "BNF 52000 CE CMP 300 SL 270 TGT 350", "CALL BANKNIFTY 52000 CE at market (cmp 300), stop 270, targets 350"),
        Case("C", "NIFTY 25000 CE @ 120 SL 20% TGT 140", "CALL NIFTY 25000 CE near 120, stop 20%, targets 140"),
        Case("C", "trail sl to cost", "FOLLOW trail"),
        Case("C", "Trail SL 150", "FOLLOW trail @150"),
        // Channel D: Hindi and Hinglish.
        Case("D", "SENSEX 73,100 CE upar 280 ke, SL 255, लक्ष्य 310/345", "CALL SENSEX 73100 CE above 280, stop 255, targets 310 / 345"),
        Case("D", "Nifty 25000 CE 120 ke upar buy karo, sl 100, target 140-160", "CALL NIFTY 25000 CE above 120, stop 100, targets 140 / 160"),
        Case("D", "Sensex 73100 PE 280 pe buy karo sl 250 target 320", "CALL SENSEX 73100 PE near 280, stop 250, targets 320"),
        Case("D", "निफ्टी 25000 CE 120 के ऊपर खरीदें, स्टॉप लॉस 100, लक्ष्य 140", "CALL NIFTY 25000 CE above 120, stop 100, targets 140"),
        Case("D", "sl ho gaya bhai 😢", "FOLLOW stop"),
        Case("D", "target 1 ho gaya ✅✅", "FOLLOW target #1"),
        Case("D", "ठीक है 👍", "NOT no words or numbers (emojis or a picture only)"),
        // Channel E: any order of lines, expiry written, several calls in one message.
        Case("E", "Sensex 73100 ce\nt1 310 t2 345 t3 400\nsl 250\nabove 280", "CALL SENSEX 73100 CE above 280, stop 250, targets 310 / 345 / 400"),
        Case("E", "BUY NIFTY 16 OCT 25200 CE ABOVE 85 SL 70 TARGET 100 120", "CALL NIFTY 25200 CE (2026-10-16) above 85, stop 70, targets 100 / 120"),
        Case("E", "NIFTY 25000 CE above 120 sl 100 tgt 140\nBANKNIFTY 52000 PE above 300 sl 270 tgt 350",
            "CALL NIFTY 25000 CE above 120, stop 100, targets 140 || CALL BANKNIFTY 52000 PE above 300, stop 270, targets 350"),
        Case("E", "#BANKNIFTY\n52000 CE above 300 tgt 330\n51800 PE above 280 tgt 310",
            "CALL BANKNIFTY 52000 CE above 300, stop none given, targets 330 || CALL BANKNIFTY 51800 PE above 280, stop none given, targets 310"),
        Case("E", "BUY BANKNIFTY 52000 CE\nENTRY: 300-305\nSTOPLOSS: 275\nTARGETS: 330 / 360 / 400", "CALL BANKNIFTY 52000 CE near 300-305, stop 275, targets 330 / 360 / 400"),
        Case("E", "73100 CE 2nd target done 🎯🎯", "FOLLOW target #2 ref ? 73100 CE"),
        Case("E", "sl hit in sensex 73100 ce", "FOLLOW stop ref SENSEX 73100 CE"),
        // Channel F: stocks, lottery, odd spacing, dots.
        Case("F", "RELIANCE 3000 CE above 45 sl 38 target 55 65", "CALL RELIANCE 3000 CE above 45, stop 38, targets 55 / 65 | unsure: RELIANCE is not a symbol the phone's lists hold",
            j("new", "RELIANCE", 3000, "CE", 45, 38, listOf(55, 65)), "CALL RELIANCE 3000 CE above 45, stop 38, targets 55 / 65 | unsure: RELIANCE is not a symbol the phone's lists hold"),
        Case("F", "Hero zero: SENSEX 74000 CE above 5 target 20 50 100", "CALL SENSEX 74000 CE above 5, stop none given, targets 20 / 50 / 100"),
        Case("F", "Nifty   25000   CE    abv  120.  SL 100.  Target 140.", "CALL NIFTY 25000 CE above 120, stop 100, targets 140"),
        Case("F", "Nifty weekly 25000 call above 120 target 135", "CALL NIFTY 25000 CE above 120, stop none given, targets 135"),
        Case("F", "BANKEX 62000 CE above 300 sl 260 target 350", "CALL BANKEX 62000 CE above 300, stop 260, targets 350"),
        Case("F", "nifty 25000 pe buy above 95 tgt 110 120 open sl 80", "CALL NIFTY 25000 PE above 95, stop 80, targets 110 / 120"),
        // Unsure: the model is asked.
        Case("G", "SENSEX 73100 CE 280", "CALL SENSEX 73100 CE near 280, stop none given, targets none given | unsure: the entry was read from a bare number",
            j("new", "SENSEX", 73100, "CE", 280), "CALL SENSEX 73100 CE near 280, stop none given, targets none given"),
        Case("G", "73100 CE above 280", "CALL ? 73100 CE above 280, stop none given, targets none given | unsure: no index or stock named",
            j("new", "SENSEX", 73100, "CE", 280), "CALL ? 73100 CE above 280, stop none given, targets none given | unsure: no index or stock named; the model said underlying SENSEX (not named in the message)"),
        Case("G", "BankNifty 52000CE above 310 SL 290 TGT 330 350 lot size 30",
            "CALL BANKNIFTY 52000 CE above 310, stop 290, targets 330 / 350 | unsure: numbers not understood: 30",
            j("new", "BANKNIFTY", 52000, "CE", 310, 290, listOf(330, 350)), "CALL BANKNIFTY 52000 CE above 310, stop 290, targets 330 / 350"),
        Case("G", "NIFTY 25000 CE\nCMP 118\nBuy now", "CALL NIFTY 25000 CE at market (cmp 118), stop none given, targets none given"),
        Case("G", "Today's NIFTY range 25000-25200, buy on dips", "NOT an index level but no CE / PE (maybe)",
            j("not_a_call", conf = 0.95), "NOT an index level but no CE / PE"),
        Case("G", "Nifty CE buy karo jaldi", "NOT names CE / PE but no strike (maybe)", null, null),
        Case("G", "Nifty 25000 ce le lo 120 pe, sl 100, tgt 140", "CALL NIFTY 25000 CE near 120, stop 100, targets 140"),
        // Not calls.
        Case("H", "Good morning traders 🌞 market opens in 15 min", "NOT chatter: no option (a strike with CE / PE) in it"),
        Case("H", "Join our VIP channel for 95% accuracy 👉 t.me/xyz", "NOT an advert or promotion"),
        Case("H", "SENSEX 73100 CE 280 ➡️ 400 🚀 BOOM 120 points", "NOT a results post (no entry level)"),
        Case("H", "Profit booked 🤑 70 points", "FOLLOW book"),
        Case("H", "Sell NIFTY 25000 CE below 80", "NOT a sell (option-writing) call: only option buys are tested"),
        Case("H", "Nifty fut buy above 25100", "NOT a futures call: only option buys are tested"),
        Case("H", "New message", "NOT the notification hid the message (Telegram's message preview is off)"),
        Case("H", "🙏🙏🙏", "NOT no words or numbers (emojis or a picture only)"),
        Case("H", "Wait for entry 🙏", "NOT chatter: no option (a strike with CE / PE) in it"),
        Case("H", "Hold sensex call for 400", "FOLLOW hold"),
    )
}
