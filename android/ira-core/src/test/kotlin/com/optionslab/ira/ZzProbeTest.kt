package com.optionslab.ira
import kotlin.test.Test
class ZzProbeTest {
    @Test fun probe() {
        val qs = listOf(
            "what is the support today", "what are the supports and resistances", "what is the gap today", "what is the expiry today", "what is the next expiry",
            "what is the premium on 25000 ce", "what is the iv today", "what is the iv", "what are fiis doing today", "what is the fii data today", "what are fii flows",
            "tell me about support on nifty", "explain the gap down today", "explain why nifty fell today", "what is the lot size", "what is the margin for 2 lots",
            "what is atm strike", "what is the vwap", "what is the sl", "what is the trail", "what is the day's high", "what's my stop loss", "explain my stop loss",
            "what is the trend", "what is open interest", "what is the oi", "what does pcr mean", "what is the pcr", "what is max pain", "what s the vix", "what is a lot of money",
            "what does the market look like", "what is the gap between nifty and banknifty", "what s the premium", "what is the range today", "what is my margin",
            "what are the fii numbers", "explain the nifty chart", "tell me about banknifty support and resistance", "what is the margin available",
            "what do fiis think", "what is hedging", "what is the squareoff time", "what time is square off", "what is the square off time",
        )
        for (q in qs) println("GLOSS [$q] -> " + (Glossary.explain(q)?.take(50)) + " | parse=" + Ask.parse(q).let { it.topics.toString() + " cmd=" + it.command + " ord=" + it.order })
        val iq = listOf("close the position", "exit the trade", "exit my position", "close my trade", "stop my strategy", "pause the strategy", "will you close all positions?",
            "would you close all positions?", "can you stop all bots?", "will you square off everything at 3:15?", "close all positions", "kill the algo", "stop the bot",
            "stop trading", "halt trading", "sell off everything", "exit everything", "get out of everything", "cancel the order", "remove my orders", "go paper",
            "back to paper", "switch to paper", "turn off the kill switch", "hit the kill switch", "can i trade", "what s my pnl", "am i up today",
            "i want to close all positions", "now close all positions", "just stop all bots", "ok stop the bots", "stop all bots please", "close holdings", "exit the holdings",
            "liquidate my holdings", "stop all", "pause", "stop my arms", "switch off auto trading", "turn on paper trading", "change to paper", "go back to paper", "move to paper mode",
            "go to paper", "go paper mode")
        for (q in iq) println("QUICK [$q] -> " + Intents.quick(q) + " | cmd=" + Ask.parse(q).command?.let { it.kind })
        val ap = listOf("where is my nifty position", "where is the banknifty option chain", "where is banknifty trading", "where is nifty", "where s my bank nifty order",
            "where is the nifty strategy", "where do i see nifty orders", "is it a good time to buy", "is it ok to sell my position", "is it safe to sell options",
            "nifty kyu gira", "nifty upar jayega ya niche", "lot badha do", "sl upar karo", "how much nifty rose today", "my niche strategy", "is it right to sell now",
            "should i buy nifty or banknifty which is better", "which is better to buy nifty or bank nifty", "where is bank", "nifty vs bank nifty", "when is the next expiry", "what is the next expiry date")
        for (q in ap) Ask.parse(q).let { println("ASK [$q] -> ${it.text} ${it.topics} m=${it.markets} cmd=${it.command} ord=${it.order}") }
        val mv = listOf("how much did nifty move in the last hour", "how did nifty do since 2", "since 9:30 how much nifty moved", "nifty since 4", "how did nifty go from 25000",
            "is nifty up since 12", "how much did nifty move since 12:30pm", "how much did i make since 10", "nifty down from the open", "what did nifty do in the last 2 hours",
            "how did nifty do since 10.30", "what is today's range", "what's nifty's range today", "today's range of banknifty", "what was the day s range", "how much can nifty move today", "nifty moved 2 lots from 11")
        for (q in mv) println("MOVES [$q] -> " + Moves.asked(q) + " | ER=" + ExpectedRange.asked(q) + " cmp=" + Compare.asked(q))
        println("ER pts " + ExpectedRange.points(25000.0, 16.0) + " " + ExpectedRange.points(25000.0, 16.0, 94))
        for (q in listOf("the usual", "usual", "my usual please", "what is the usual"))  println("HAB [$q] " + Habits.asked(q))
        for (q in listOf("how is nifty", "what are the levels on BankNifty", "how volatile is India VIX", "should i buy nifty", "why is FinNifty moving today", "how is Gold doing", "any patterns on Sensex"))
            println("HKEY [$q] " + Habits.key(q) + " -> " + Habits.key(Habits.key(q)?.let { Habits.question(it) } ?: "x"))
    }
}
