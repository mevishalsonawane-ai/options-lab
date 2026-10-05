package com.optionslab.ira

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What every spoken line costs ([Aloud.say], then [SayAs.figures] and [Pauses.shape], as the listening voice runs them),
 * timed over the lines below, and the words said for each pinned to what the code gave before it was made cheaper
 * (speed round 9). Run with `-Dira.bench=1` for steadier numbers.
 */
class VoiceBenchTest {
    companion object {
        /** Replies and lines of every kind: figures, symbols, brackets, dashes, lines, Hindi, and plain words. */
        val LINES = listOf(
        "   ",
        "1 crore",
        "1 lakh and 12.5 lakh and 2.5 crore",
        "1,00,000 and 12,50,000 and 2,50,00,000",
        "1,23,00,00,000 and 12,34,56,78,901",
        "1,23,456 crore",
        "1.23 lakh crore",
        "1.23 lakh crore rupees",
        "1.23 lakh rupees",
        "1.23 lakh rupees and 12.35 lakh",
        "1.23 लाख रुपये",
        "12,345 rupees crore",
        "123 crore and 1,235 crore",
        "123456 rupees and 1,234,567",
        "2,50,00,000 और 24,500 CE",
        "2.5 करोड़ और 24,500 कॉल",
        "24,612.50 and 1,23,456.78 and 1.2345 and 0.50",
        "24,613 and 1,23,457 and 1.23 and 0.5",
        "24500",
        "99,99,999",
        "999 became 1000.",
        "999 became 999.6.",
        "At 9:15 on 05.10.2026.",
        "BANKNIFTY26OCT52000PE",
        "Bank Nifty October 52,000 put",
        "Bank Nifty is up 0.4% today, Boss. It led the market.",
        "Bank Nifty is up 0.4% today. It led the market.",
        "Boss",
        "Boss, I took that as \"what is bank nifty doing\".",
        "Boss, I took that as \"what is bank nifty doing\". Bank Nifty is up 0.4 percent today. It led the market.",
        "Boss, Nifty is up 0.4 percent. Bank Nifty is down.",
        "Boss, margin is 1.23 lakh rupees, 40 percent, of the usual.",
        "Boss, margin is Rs 1,23,456 (40%) of the usual.",
        "Boss, resistance at 24,500, 24,600.",
        "Boss, the market is flat.",
        "Boss, today's loss is Rs 1,400 (70%) of the Rs 2,000 limit - Rs 600 left. Trades: 4 of 5 · kill switch off.\nYour call.",
        "Boss, yes Boss, it is up.",
        "Boss, yes, it is up.",
        "Boss, your margin is 1.23 lakh rupees.",
        "Boss, your margin is Rs 1,23,456. You hold NIFTY25O0724500CE. FIIs sold Rs 12,345 crore.",
        "Boss, your margin is Rs 1,23,456.40.",
        "Boss, निफ्टी 500 रुपये ऊपर।",
        "Bossy words stay, Boss.",
        "Bought 2 Bank Nifty lots.",
        "Bought NIFTY25O0724500CE at 120.",
        "Bought Nifty 7 October 24,500 call at 120.",
        "CE writers at the top",
        "Change - 5 points",
        "Close position(s) now.",
        "Down Rs 900 (on Zerodha)",
        "Down Rs 900, on Zerodha",
        "FIIs sold 12,345 crore rupees.",
        "FIIs sold Rs 12,345 crore.",
        "It fell (see the chat. It is long) today.",
        "Jarvis, shorter please",
        "MIDCPNIFTY25OCT13000CE",
        "Margin 1.23 lakh rupees Zerodha",
        "Margin 1.23 lakh rupees, Zerodha",
        "Margin is 1,23,456 rupees.",
        "Margin is 1.23 lakh rupees.",
        "Margin is Rs 1,23,456.",
        "Margin is ₹1,23,456.40.",
        "Midcap Nifty October 13,000 call",
        "NIFTY25000CE",
        "NIFTY25D0126000CE",
        "NIFTY25O1425000PE and FINNIFTY25OCT24000CE",
        "NIFTY25O4024500CE",
        "NIFTY26OCTFUT",
        "NIFTY26XYZ24500CE",
        "Nifty 1 December 26,000 call",
        "Nifty 14 October 25,000 put and Fin Nifty October 24,000 call",
        "Nifty 24,500 CE price",
        "Nifty 25,000 call",
        "Nifty 7 October 24,500 call at 120.",
        "Nifty October future",
        "Nifty call",
        "Nifty is at 24,612 (plus 0.21 percent).",
        "Nifty is at 24,612, Boss. It rose 0.21 percent today. Banks led. The rest is in the chat.",
        "Nifty is at 24,612, Boss. The rest is in the chat.",
        "Nifty is at 24,612, plus 0.21 percent.",
        "Nifty is at 24,612.",
        "Nifty is at 24,612.40 (+0.21%).",
        "Nifty is at 24,612.40, Boss. It rose 0.21% today. Banks led. IT lagged. Volume was light.",
        "Nifty up 0.4 percent Bank Nifty down 0.2 percent Sensex flat.",
        "Nifty up 0.4 percent, Bank Nifty down 0.2 percent, Sensex flat.",
        "No name here.",
        "No reason noted — what would you do differently?",
        "No reason noted, what would you do differently?",
        "Order 250930000123456 placed.",
        "RELIANCE25OCT2800CE",
        "Ready: Zerodha logged in, relay up. Kill switch off",
        "Ready:\n- Zerodha logged in\n- relay up.\n• Kill switch off",
        "Relay up · prices flowing | guards on",
        "Relay up, prices flowing, guards on",
        "Reliance October 2,800 call",
        "Resistance at 24,500 24,600.",
        "Rs 1,23,456",
        "Rs 1,23,456 crore",
        "Rs 12,345 crore",
        "Same.",
        "Sensex at 81,523 and 99,999 rupees.",
        "Tell me more",
        "The PE ratio is 22.",
        "The market is flat.",
        "Today's loss is Rs 1,400 (70 percent) of the limit.",
        "Today's loss is Rs 1,400, 70 percent, of the limit.",
        "Trades: 4 of the 3 your limit allows - reached.",
        "Trades: 4 of the 3 your limit allows, reached.",
        "Up 0.5 percent on Monday",
        "Up 120 points Bank Nifty up 300 points",
        "Up 120 points, Bank Nifty up 300 points",
        "Walls 24,500 24,600. Nifty up 0.4 percent Bank Nifty down. (Paper is practice.) Done!",
        "Walls at 24,500 24,600 and 24,700.",
        "Walls at 24,500, 24,600 and 24,700.",
        "What is Bank Nifty doing today?",
        "Yes, Boss. Nifty is up, Boss. Boss, it is fine.",
        "Yes, Boss. Nifty is up. It is fine.",
        "[^\\p{L}\\p{N}]+",
        "all of it fits: nothing left for the chat",
        "at 09:15 and 15:35:42",
        "at 9:15 and 15:36",
        "banknifty",
        "call",
        "chat",
        "don't be shorter",
        "exit bank nifty",
        "go on",
        "in more detail",
        "india vix level",
        "is it up? or down. really!",
        "is that shorter?",
        "less detail",
        "longer answers",
        "minus 45,000 rupees",
        "more detail always",
        "more details",
        "my pin is 4321 right",
        "named once, in Hindi",
        "nifty",
        "nifty 24000 put",
        "nifty 24500 put",
        "nifty 50 calls",
        "nifty kya hai?",
        "no score, one reading",
        "on 05.10.2026 with v1.2.3 and NIFTY24600.5CE",
        "percent",
        "rupees",
        "say less",
        "shorter",
        "sure enough",
        "the 24500 CE and 24,600PE",
        "the 24500 call and 24,600 put",
        "the chat",
        "the recognizer was sure of the best reading",
        "today",
        "too long",
        "twice changes nothing",
        "up 0.4",
        "up 120 points, then 1 point",
        "up 120pts, then 1 pt",
        "up Rs +1,50,000",
        "up plus 1.5 lakh rupees",
        "v1.2.3",
        "vix",
        "what is bank nifty doing",
        "what is bank nifty doing?",
        "what is nifty doing",
        "what's my p and l today",
        "what's nifty doing",
        "whats nifty doing now",
        "word",
        "निफ्टी ₹500 ऊपर।",
        "बॉस, निफ्टी 0.4 प्रतिशत ऊपर है (आज)। बाकी चैट में है।",
        "बॉस, निफ्टी 24,612 पर है। आज यह प्लस 0.21 प्रतिशत ऊपर है। बैंक आगे रहे। बाकी चैट में है।",
        "बॉस, निफ्टी 24,612.40 पर है। आज यह +0.21% ऊपर है। बैंक आगे रहे। आईटी पीछे रहा।",
        "बॉस, मैंने सुना: \"nifty kya hai\"।",
        "Nifty is at 24,612.40, up 0.4% today. Bank Nifty is flat. Boss, the trend is up - support 24,500 (strong) · resistance 24,800.",
        "Your P&L today is Rs 12,345.50, Boss. Two trades open: NIFTY25O0724500CE and BANKNIFTY26OCT52000PE.",
        "I could not read your limits just now, Boss.",
        "Yes Boss, done.",
        "Okay Boss. The rest is quiet today, nothing to watch.",
        "Boss, margin used Rs 1,40,000 of 2,00,000 (70 percent)\n- free Rs 60,000\n- one order open",
        "निफ्टी 24,500 पर है, बॉस। बैंक निफ्टी नीचे है।",
        "Market opens at 09:15:42 and closes at 15:30. Max pain 24,500 24,600.",
        "Nifty up 0.4 percent Bank Nifty down 0.2 percent Sensex up 120 pts",
        "Here is what I see, Boss: the market is calm, no big news, and your strategies are running as planned. Nothing needs you right now.",
        )

        /** Everything the voice makes of [t], in one line. */
        fun said(t: String): String = listOf(
            SayAs.figures(t), SayAs.figures(t, hindi = true), Pauses.shape(t), Pauses.shape(SayAs.figures(t, Aloud.hindi(t))),
            Aloud.say(t, Aloud.Length.SHORT), Aloud.say(t), Aloud.say(t, Aloud.Length.FULL), Aloud.numbers(t), Aloud.onceBoss(t),
        ).joinToString(" | ")

        fun digest(lines: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(lines.joinToString("\n") { said(it) }.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun everyLineSaidAsBefore() {
        assertEquals(BEFORE, digest(LINES))
        // Applying the pauses twice still changes nothing.
        LINES.forEach { t -> val once = Pauses.shape(SayAs.figures(t)); assertEquals(once, Pauses.shape(once), t) }
    }

    @Test fun voiceCost() {
        val long = System.getProperty("ira.bench") != null
        fun pass(): LongArray {
            val t = LongArray(3)
            for (l in LINES) {
                var s = System.nanoTime(); Aloud.say(l); t[0] += System.nanoTime() - s
                s = System.nanoTime(); val f = SayAs.figures(l); t[1] += System.nanoTime() - s
                s = System.nanoTime(); Pauses.shape(f); t[2] += System.nanoTime() - s
            }
            return t
        }
        repeat(if (long) 200 else 20) { pass() }
        val runs = if (long) 200 else 20
        val total = LongArray(3)
        repeat(runs) { pass().forEachIndexed { i, v -> total[i] += v } }
        fun us(i: Int) = total[i] / 1000.0 / runs / LINES.size
        println("VoiceBench: per line Aloud.say %.2f us, SayAs.figures %.2f us, Pauses.shape %.2f us (%d lines, %d runs)".format(
            us(0), us(1), us(2), LINES.size, runs))
    }

    /**
     * [digest] of [LINES] on the code before this round (2026-10-05), changed once on purpose since: a line said shorter
     * now keeps every safety warning past the cut ([Aloud.keep] - "kill switch" lines said one sentence long keep it);
     * and (Voice, round 26) "Rs 1,400, 70 percent" / "Down Rs 900, on Zerodha" are said "1,400 rupees, 70 percent" /
     * "900 rupees, on Zerodha" - before, the comma after the figure was taken into it ("1,400, rupees 70 percent").
     */
    private val BEFORE = "10fd4dc92e2875070cd55abc1d689de3483784075410f729060695813cc5c63b"
}
