package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The coverage audit (2026-10-05): what an Indian options trader asks a voice assistant through a trading day -
 * English, Hinglish, Hindi in Latin script, with the phone recognizer's usual slips - fed through the whole question
 * path the app takes ([Understand.questions], then [Ask.parse] / [Ask.reading], the hub's own readers and the app's
 * sections, then [Suggest]). Each line names the kind of answer it must get; a line not understood, or answered as the
 * wrong kind (Boss's own P&L read as the market's), fails. And every action-like line reads exactly as it did before
 * the audit's changes: nothing that acts was widened.
 */
class CoverageTest {
    /** The kind of answer a line gets. */
    enum class Kind { MARKET, ACCOUNT, JARVIS, INFO, CHAT, ACT, SUGGESTED, MISSED, HONEST }

    private val today: LocalDate = LocalDate.of(2026, 10, 5)

    /** The kind of each question [said] is understood as, in the order the app's hub takes them (IraHub.ask). */
    private fun route(said: String): List<Kind> =
        ((if (Sources.asked(said)) null else Understand.questions(null, said)) ?: listOf(said)).map { kind(it, 0) }

    private fun kind(q: String, depth: Int): Kind {
        val p = Ask.parse(q)
        if (SelfCheck.asked(q)) return Kind.JARVIS
        if (p.command != null || p.order != null || Topic.ORDER in p.topics || Topic.COMMAND in p.topics) return Kind.ACT
        // What the phone has no data for, VWAP, targets, lots with no budget: said so (IraHub, just after "is your data fresh").
        if (Honest.asked(q) != null) return Kind.HONEST
        if (Chat.smallTalk(q, 0) != null) return Kind.CHAT
        if (Agenda.asked(q) || Improve.asked(q) || Latency.asked(q)) return Kind.JARVIS
        if (Reminder.cancelAsked(q) || Reminder.asked(q)) return Kind.ACT
        if (Distance.asked(q) != null) return Kind.MARKET
        if (OptionFacts.asked(q) != null || Sizing.asked(q) != null) return Kind.INFO
        if (DaySummary.asked(q)) return Kind.ACCOUNT
        if (MarketDays.expiryAsked(q) || MarketDays.asked(q, today) != null) return Kind.INFO
        if (Outlook.asked(q) && !Regex("(?i)\\b(my|mine|our)\\b").containsMatchIn(q)) return Kind.MARKET
        if (Goals.asked(q) || SelfWhy.asked(q) || Vetting.asked(q) || SelfCalibration.asked(q) || Lessons.asked(q) || Sources.asked(q)) return Kind.JARVIS
        if (Habits.asked(q)) return Kind.MARKET
        if (depth == 0 && p.command?.kind.let { it == null || it == Command.Kind.STOP_ONE }) Intents.quick(q)?.let { return kind(it, 1) }
        if ((Topic.ACCOUNT !in p.topics || !Regex("(?i)\\b(my|mine|our|me|i)\\b").containsMatchIn(q)) && Topic.EXPLAIN !in p.topics && Glossary.explain(q) != null) return Kind.INFO
        if (Topic.ACCOUNT !in p.topics && !Regex("(?i)\\b(i|me|my|mine)\\b").containsMatchIn(q) && OptionQuote.asked(p.text.ifBlank { q }) != null) return Kind.MARKET
        if (Topic.BACKTEST in p.topics) return Kind.MARKET
        if (Topic.ACCOUNT in p.topics) return Kind.ACCOUNT
        if (Topic.SUGGEST in p.topics || Topic.EXPLAIN in p.topics || Topic.TRADE_CHECK in p.topics) return Kind.MARKET
        if (Regex("(?i)\\bsolo\\b").containsMatchIn(q)) return Kind.JARVIS
        if (Topic.ADVICE !in p.topics && reasoned(p.text.ifBlank { q }, p)) return Kind.MARKET
        if (Topic.OFF_TOPIC in p.topics) return when {
            Chat.personal(q) -> Kind.CHAT
            Suggest.closest(q) != null -> Kind.SUGGESTED
            else -> Kind.MISSED
        }
        if (Topic.HELP in p.topics) return Kind.JARVIS
        if (p.topics == setOf(Topic.GREETING)) return Kind.CHAT
        return Kind.MARKET
    }

    /** The hub's reasoning over the candles (IraHub.reasoned): does one of its readers take [q]? */
    private fun reasoned(q: String, p: Question): Boolean {
        if (MarketStory.asked(q) != null || SharpMove.asked(q) != null || ExpiryDay.asked(q)) return true
        if (Topic.WHY in p.topics && !Regex("(?i)\\bhow much\\b").containsMatchIn(q)) return false
        return Payoff.asked(q) != null || VixRank.asked(q) || SinceLast.asked(q) || Briefing.asked(q) || Realised.asked(q) ||
            Together.asked(q) || Compare.asked(q) || Odds.asked(q) != null || ExpectedRange.asked(q) || Moves.asked(q) != null ||
            BigPicture.asked(q) || LevelInfo.asked(q) != null || OpeningRange.asked(q) || PeriodMove.asked(q) != null ||
            Momentum.asked(q) || Pivots.asked(q) || DayStory.asked(q) || Gap.asked(q) || Streak.asked(q)
    }

    private val M = Kind.MARKET; private val A = Kind.ACCOUNT; private val J = Kind.JARVIS; private val I = Kind.INFO; private val C = Kind.CHAT
    private val H = Kind.HONEST

    /** What Boss asks through a trading day, with the kind of answer each must get. */
    private val ASKED: List<Pair<String, Kind>> = listOf(
        // ---- Before the open ----
        "good morning jarvis" to C, "brief me" to M, "morning briefing" to M, "what did you study last night" to A,
        "is the market open today" to I, "is tomorrow a holiday" to I, "is the market open tomorrow" to I, "when is the next expiry" to I,
        "is today expiry" to I, "gap up or gap down today" to M, "how did nifty open" to M, "where did banknifty open" to M,
        "what's the plan for today" to J, "what's your plan today" to J, "any news" to M, "what's the news today" to M,
        "latest headlines" to M, "any news on banknifty" to M, "what did fiis do yesterday" to A, "fii data" to A,
        "what is the outlook for today" to M, "how will the market open" to M, "what does history say about today" to A,
        // ---- Prices and the day ----
        "how is nifty" to M, "how's nifty doing today" to M, "what is nifty at" to M, "nifty price" to M,
        "where is banknifty trading" to M, "banknifty level now" to M, "what's the sensex" to M, "finnifty update" to M,
        "is nifty up or down" to M, "is the market up today" to M, "how much is nifty up today" to M,
        "how much did banknifty fall today" to M, "nifty change today" to M, "what's the day high on nifty" to M,
        "nifty day low" to M, "today's range of banknifty" to M, "nifty opening price" to M, "previous close of nifty" to M,
        "yesterday's close banknifty" to M, "what was yesterday's high on nifty" to M, "how is gold" to M, "gold price" to M,
        "how is the market" to M, "how are all the indices" to M, "what's moving" to M, "top gainers" to M,
        "which index is strongest" to M, "is banknifty stronger than nifty" to M, "compare nifty and banknifty" to M,
        "what's going on" to M, "kya chal raha hai" to M, "how is bank nifty doing" to M, "how is nifty fifty" to M,
        "how is the nifty" to M, "nifty 50 kaisa hai" to M,
        // ---- Volatility ----
        "what is india vix" to M, "vix kitna hai" to M, "is vix high" to M, "how volatile is the market" to M,
        "is it a volatile day" to M, "is today a busy day" to M, "india vix today" to M, "how is vicks" to M,
        // ---- Trend and levels ----
        "trend on nifty" to M, "is banknifty bullish" to M, "is nifty bearish" to M, "nifty trend 15 minute" to M,
        "what's the 1 hour trend on banknifty" to M, "nifty support levels" to M, "resistance for banknifty" to M,
        "key levels for nifty today" to M, "where is the next support on nifty" to M, "nifty pivot points" to M,
        "banknifty pivots" to M, "is nifty overbought" to M, "rsi of nifty" to M, "nifty momentum" to M,
        "big picture on nifty" to M, "describe the nifty chart" to M, "read the banknifty chart" to M,
        "levels on all indices" to M, "where is nifty heading" to M, "is the market trending or sideways" to M,
        // ---- Moves over time ----
        "how much did nifty move in the last hour" to M, "how much has banknifty moved since the open" to M,
        "nifty in the last 30 minutes" to M, "how did nifty do this week" to M, "banknifty this month" to M,
        "nifty weekly performance" to M, "how many days has nifty been up" to M, "nifty streak" to M,
        "recap the day" to M, "what happened in the market today" to M, "what changed since i last asked" to M,
        "expected range for nifty today" to M, "chances nifty closes above 25000" to M, "will nifty cross 25000 today" to M,
        "how far is nifty from 25000" to M, "opening range of nifty" to M, "did nifty break the opening range" to M,
        "explain this move" to M, "what happened at 11:20" to M, "nifty gap today" to M,
        // ---- Why ----
        "why is nifty falling" to M, "why is the market down today" to M, "why did banknifty jump" to M,
        "what moved the market" to M, "why is sensex up" to M, "nifty kyun gira" to M, "market kyun gira aaj" to M,
        "banknifty kyun upar hai" to M,
        // ---- Patterns and words ----
        "any pattern on nifty" to M, "any candle pattern on banknifty" to M, "is there a breakout on nifty" to M,
        "what is a doji" to M, "explain bullish engulfing" to M, "what is a hammer" to M, "what is theta" to I,
        "explain max pain" to I, "what is pcr" to A, "what is delta" to I, "what is implied volatility" to A,
        "what does iv crush mean" to I, "what is a straddle" to I, "theta kya hai" to I,
        // ---- Options ----
        "what is the atm strike of nifty" to I, "lot size of banknifty" to I, "price of nifty 25000 ce" to I,
        "what is the premium of banknifty 52000 pe" to I, "how many lots can i buy with 20000" to I,
        "what is the pcr of nifty" to A, "max pain for nifty" to A, "option chain of banknifty" to A,
        "where is the highest open interest" to A, "what is the iv of nifty" to A, "how much time is left for expiry" to I,
        "if i bought the 25000 ce at 120 what is my profit at 25200" to M,
        // ---- Should I ----
        "should i trade now" to M, "is it a good day to trade" to M, "safe to trade today" to M, "market mood" to M,
        "can i trade now" to M, "should i stay out today" to M, "what should i buy" to M, "any trade ideas" to M,
        "give me a trade" to M, "what do you recommend" to M, "should i buy nifty calls" to M,
        "backtest the hammer on nifty" to M, "backtest breakout on banknifty 15 minute" to M,
        // ---- Boss's own money ----
        "what is my p&l" to A, "my pnl today" to A, "how much did i make today" to A, "how much did i lose today" to A,
        "am i in profit" to A, "am i up today" to A, "how am i doing" to A, "what's my mtm" to A, "what is my p and l" to A,
        "what's my pee and el" to A, "how much money did i make" to A, "my p&l yesterday" to A, "last week's p&l" to A,
        "my best day" to A, "my worst day this month" to A, "what is my profit this month" to A, "wrap up my day" to A,
        "how was my day" to A, "my realised profit today" to A,
        // ---- Positions, orders, funds ----
        "show my positions" to A, "what are my open positions" to A, "what am i holding" to A, "my orders" to A,
        "any pending orders" to A, "did my order fill" to A, "was my order rejected" to A, "why was my order rejected" to A,
        "how many trades did i take today" to A, "my trades today" to A, "my funds" to A, "available margin" to A,
        "how much margin do i have" to A, "my balance" to A, "explain my positions" to A, "which position is losing most" to A,
        "what happens to my p&l if nifty moves 100 points" to A, "how was my last trade" to A, "replay my trades today" to A,
        "show my position" to A, "what is my exposure" to A,
        // ---- Strategies, risk, settings ----
        "how are my strategies doing" to A, "which strategies are running" to A, "what's my daily loss limit" to A,
        "is the kill switch on" to A, "what are my stops" to A, "where is my stop loss" to A, "my targets" to A,
        "my alarms" to A, "what alerts do i have" to A, "which mode am i in" to A, "am i in paper mode" to A,
        "is zerodha connected" to A, "am i logged in to kite" to A, "my win rate" to A, "what am i doing wrong" to A,
        "review my week" to A, "what if i had taken that trade" to A, "why did my last trade lose" to A,
        "where is the kill switch" to A, "how do i switch to live" to A, "where can i see my orders" to A,
        "am i ready for live" to A, "what did i change in settings" to A, "what time do i lose most" to A,
        // ---- Jarvis himself ----
        "what can you do" to J, "who are you" to C, "can you hear me" to C, "help" to J, "how are you improving" to J,
        "run a self check" to J, "how fast are you" to J, "what have you learned" to J, "how do you know that" to J,
        "where are you weakest" to J, "the usual" to M, "how are you" to C, "thank you" to C, "what did you do today" to A,
        "what are my goals" to J, "what held up" to J, "how is solo doing" to J,
        // ---- Hinglish ----
        "nifty kaisa hai" to M, "aaj nifty kaisa hai" to M, "banknifty kya chal raha hai" to M, "market kaisa hai" to M,
        "bazaar ka kya haal hai" to M, "nifty kitne pe hai" to M, "banknifty ka rate kya hai" to M, "sensex kahan hai" to M,
        "aaj kitna kamaya" to A, "kitna loss hua" to A, "mera p&l kitna hai" to A, "main profit mein hoon kya" to A,
        "positions dikhao" to A, "mere orders dikhao" to A, "koi order pending hai kya" to A,
        "strategies kaise chal rahe hain" to A, "banknifty upar jayega kya" to M, "nifty 25000 cross karega kya" to M,
        "aaj ki news kya hai" to M, "koi khabar hai kya" to M, "trade karu kya" to M, "kya karna chahiye" to M,
        "pichle ghante nifty kitna gira" to M, "market khula hai kya" to A, "expiry kab hai" to I,
        "kitne trade kiye aaj" to A, "banknifty mein kya ho raha hai" to M, "nifty ka trend kya hai" to M,
        "nifty ka support kya hai" to M, "aaj ka high kya hai nifty ka" to M, "sab theek hai" to J,
        // ---- Hindi in Latin script ----
        "aaj bazaar mein kya ho raha hai" to M, "nifty abhi kitne par hai" to M, "kya main loss mein hoon" to A,
        "maine aaj kitna kamaya" to A, "kitni positions khuli hain" to A, "fin nifty mein kya ho raha hai" to M,
        // ---- Recognizer slips ----
        "how is niftee" to M, "how is the census today" to M, "how is bank fifty" to M, "what is my pnl" to A,
        "um how is nifty" to M, "how how is banknifty" to M, "how is nifty i mean banknifty" to M,
        "how is nifty and what's my p&l" to M, "nifty kaisa hai aur mera p&l kitna hai" to M,
        "how far is nifty from twenty five thousand" to M, "what is my m t m" to A,
        // ---- Through the day as Boss says it (the audit's second sweep: Hinglish first, then the trader's English) ----
        "nifty kya kar raha hai" to M, "nifty abhi kahan hai" to M, "nifty kitna upar hai" to M, "nifty kitna neeche hai" to M,
        "aaj nifty kitna gira" to M, "aaj market kitna gira" to M, "banknifty kitna chadha aaj" to M, "sensex kitne points upar hai" to M,
        "nifty ka level kya hai" to M, "nifty ka support kahan hai" to M, "banknifty ka resistance kya hai" to M, "nifty ka high low batao" to M,
        "aaj ka range kya hai" to M, "vix kya bol raha hai" to M, "vix badh raha hai kya" to M, "market volatile hai kya" to M,
        "market mein dar hai kya" to M, "nifty bullish hai kya" to M, "nifty bearish hai ya bullish" to M, "trend kya hai" to M,
        "market ka trend kya hai" to M, "banknifty ka trend batao" to M, "nifty upar jayega ya neeche" to M, "market kidhar jayega" to M,
        "aaj market kahan band hoga" to M, "nifty kahan tak jayega" to M, "koi pattern bana hai kya" to M, "nifty mein koi breakout hai kya" to M,
        "news batao" to M, "koi news hai" to M, "market ki news kya hai" to M, "banknifty ki news" to M, "market kyun gir raha hai" to M,
        "nifty kyun badh raha hai" to M, "aaj market itna kyun gira" to M, "ye girawat kyun aayi" to M, "mera pnl batao" to A,
        "mera profit kitna hai" to A, "aaj ka profit kitna hai" to A, "aaj ka loss kitna hai" to A, "kitna nuksan hua aaj" to A,
        "total kitna kamaya is hafte" to A, "is mahine kitna kamaya" to A, "meri positions kya hain" to A, "open positions kitni hain" to A,
        "mere paas kya hai" to A, "mera margin kitna hai" to A, "mera balance kitna hai" to A, "paisa kitna bacha hai" to A, "funds kitne hain" to A,
        "meri strategy chal rahi hai kya" to A, "kaun si strategy chal rahi hai" to A, "orb chal raha hai kya" to A, "kill switch on hai kya" to A,
        "paper mode mein hoon kya" to A, "live mode on hai kya" to A, "mera stop loss kya hai" to A, "mera target kya hai" to A,
        "alerts kya lage hain" to A, "aaj expiry hai kya" to I, "kal market khula hai kya" to I, "kal chhutti hai kya" to I,
        "market kitne baje band hoga" to I, "market kab khulega" to I, "lot size kya hai nifty ka" to I, "atm strike kya hai banknifty ka" to I,
        "nifty 25000 call ka premium kya hai" to I, "banknifty 52000 put ka price kya hai" to I, "trade lu kya" to M,
        "abhi trade karna chahiye kya" to M, "aaj trade karun ya nahi" to M, "kya kharidu" to M, "koi trade idea hai" to M, "kuch suggest karo" to M,
        "nifty ka chart samjhao" to M, "pcr kya hai" to A, "pcr kitna hai" to A, "max pain kya hai nifty ka" to A, "oi data batao" to A,
        "fii ne aaj kitna becha" to A, "fii data kya hai" to A, "gold ka rate kya hai" to M, "gold kaisa hai" to M, "how's the market looking" to M,
        "what's nifty doing" to M, "where's nifty now" to M, "nifty right now" to M, "nifty quote" to M, "give me nifty" to M,
        "tell me about nifty" to M, "how's banknifty looking today" to M, "what's the vibe in the market" to M, "is the market green" to M,
        "is the market red" to M, "are we up or down today" to M, "how bad is the fall" to M, "how much is the market down" to M,
        "is nifty above 25000" to M, "is banknifty below 52000" to M, "how much has nifty fallen from the high" to M,
        "how far from the day high is nifty" to M, "where will nifty close today" to M, "is it a good time to buy calls" to M,
        "should i buy puts now" to M, "should i sell options today" to M, "should i exit" to M, "should i book profit" to A,
        "should i hold my position" to A, "what's my profit right now" to A, "how much am i down" to A, "how much am i up" to A,
        "what's my running pnl" to A, "am i green today" to A, "what's my day looking like" to A, "how did i do yesterday" to A,
        "how much did i make this week" to A, "what's my total profit this month" to A, "how many trades today" to A,
        "how many lots am i holding" to A, "what's open right now" to M, "do i have any open positions" to A, "any open orders" to A,
        "did my nifty order go through" to A, "what's the status of my order" to A, "what's my used margin" to A, "how much cash do i have" to A,
        "how much can i trade with" to A, "what's my buying power" to A, "is my strategy running" to A, "are my bots running" to A,
        "which arms are active" to A, "what's the status of orb" to A, "is anything running" to A, "what's the kill switch status" to A,
        "what are my risk limits" to A, "how much can i still lose today" to A, "how close am i to my daily loss limit" to A,
        "what's my max loss" to A, "where's my stop" to A, "what's my stop loss on nifty" to A, "did my stop loss hit" to A,
        "which alerts are set" to A, "any alarms set" to A, "am i on paper or live" to A, "what mode is the app in" to A,
        "is the data connected" to A, "is kite connected" to A, "is the app working" to A, "when does the market close today" to I,
        "how much time till close" to I, "how long till the market closes" to I, "how much time is left in the session" to I,
        "when does the market open tomorrow" to I, "is monday a holiday" to I, "next holiday" to I, "when is the next holiday" to I,
        "is thursday expiry" to I, "what day is expiry this week" to I, "what's the lot size" to I, "what's atm right now" to I, "nifty atm" to I,
        "what's the premium of the atm call" to I, "nifty 25000 ce price" to I, "banknifty 52000 pe ltp" to I, "how much is the 25000 call" to M,
        "what's the iv today" to A, "what's the pcr today" to A, "nifty pcr" to A, "banknifty max pain" to A, "where is the max oi" to A,
        "call writing kahan hai" to A, "what are fiis doing" to A, "fii dii data" to A, "did fiis buy or sell" to A, "how is gold today" to M,
        "gold rate" to M, "what's sgx nifty" to H, "gift nifty" to H, "how is gift nifty" to H, "what are global cues" to M, "any events today" to A,
        "is there rbi policy today" to A, "when is the fed meeting" to A, "what is the budget date" to A, "explain the market today" to M,
        "summarize the market" to M, "give me a summary" to M, "market summary please" to M, "what's happening with banknifty" to M,
        "bank nifty update please" to M, "quick update" to M, "status update" to M, "how is everything" to M, "anything i should know" to M,
        "anything important" to M, "what should i watch today" to M, "key things today" to M, "what's your view on nifty" to M,
        "what do you think about nifty" to M, "is nifty going to fall" to M, "will nifty go up today" to M, "will banknifty fall more" to M,
        "is this a reversal" to M, "is the fall over" to M, "is this a bull trap" to M, "is it a fake breakout" to M, "is nifty forming a top" to M,
        "what are the chances of a bounce" to M, "how strong is the trend" to M, "is the trend weak" to M, "is momentum fading" to M,
        "is nifty oversold" to M, "is banknifty overbought" to M, "what's the rsi" to M, "what's the macd on nifty" to M, "vwap of nifty" to H,
        "nifty above vwap" to H, "supertrend on nifty" to M, "moving average of nifty" to M, "200 dma of nifty" to M,
        // ---- The third sweep (round 5): what the phone has no data for (said so, never Nifty's answer), VWAP, targets,
        // lots with no budget; more Hinglish and the other languages in Latin script; the recognizer's spellings ----
        "kitne lot le sakta hoon" to H, "what's the dollar rupee" to H, "where is the bottom" to H,
        "what's the target for nifty today" to H, "where is vwap" to H, "what's crude doing" to H, "how is the dow" to H,
        "how did us markets close" to H, "how is asia" to H, "any results today" to H, "crude oil kaisa hai" to H,
        "crude kitne pe hai" to H, "brent price" to H, "how is nasdaq" to H, "dow jones kaisa hai" to H, "how did wall street do" to H,
        "us market kaisa raha" to H, "nikkei today" to H, "hang seng kaisa hai" to H, "how are asian markets" to H,
        "dollar kitne ka hai" to H, "usd inr rate" to H, "rupee kaisa hai aaj" to H, "is the rupee falling" to H, "how is silver" to H,
        "bitcoin price" to H, "us bond yields" to H, "kiske results aaj hain" to H, "earnings today" to H,
        "banknifty ka target kya hai" to H, "nifty target today" to H, "nifty ka target batao" to H,
        "where is the bottom for banknifty" to H, "nifty kitna aur girega" to H, "banknifty vwap" to H, "is nifty above vwap" to H,
        "vwap kahan hai" to H, "how many lots can i take" to H, "kitne lots le sakta hu" to H, "how many lots can i buy" to H,
        "gift nifty kya bol raha hai" to H, "natural gas price" to H, "how is europe" to H, "dax today" to H, "what's the s&p doing" to H,
        "how is the dow jones today" to H, "how much can banknifty fall" to M, "what is vwap" to I, "vwap kya hota hai" to I,
        "what does vwap mean" to I, "nifty ka haal batao" to M, "bazaar kaisa chal raha hai" to M, "market ka mood kaisa hai" to M,
        "aaj market mein tezi hai kya" to M, "aaj mandi hai kya" to M, "nifty mein tezi hai kya" to M, "banknifty mein mandi hai kya" to M,
        "sensex kitna chadha" to M, "sensex kitna gira aaj" to M, "finnifty kaisa hai" to M, "vix kitna hai abhi" to M,
        "nifty da ki haal hai" to M, "nifty kasa aahe" to M, "market kasa aahe" to M, "nifty ela undi" to M, "nifty kem che" to M,
        "bazar kemon" to M, "how is nifti" to M, "how is bang nifty" to M, "nifty prise" to M, "what is the nifty rate" to M,
        "bank nifty kitne pe hai" to M, "how is fin nifty" to M, "how is sensecs" to M, "nifty supprt" to M, "nifty resistence" to M,
        "banknifty treand" to M, "how is the vix today" to M, "how is india vicks" to M, "nifty fifty kitne pe hai" to M,
        "nifti kaisa hai" to M, "nifty kaisa he" to M, "nifty kesa hai" to M, "market kesa hai" to M, "nifty kitne par chal raha hai" to M,
        "banknifty kitne pe chal raha hai" to M, "aaj ka nifty" to M, "nifty ka bhav kya hai" to M, "sensex ka bhav" to M,
        "market upar hai ya neeche" to M, "nifty hara hai ya laal" to M, "market laal hai kya" to M, "market hara hai kya" to M,
        "aaj gap up khula kya" to M, "nifty gap down khula" to M, "nifty kitne pe khula" to M, "kal nifty kahan band hua" to M,
        "pichle hafte nifty kaisa raha" to M, "is mahine banknifty kaisa raha" to M, "nifty ka 15 minute trend" to M,
        "banknifty ka 5 minute chart kaisa hai" to M, "nifty ke levels batao" to M, "banknifty ke pivot batao" to M,
        "koi candle pattern bana kya" to M, "nifty breakout de raha hai kya" to M, "banknifty mein breakdown hua kya" to M,
        "vix kyun badha" to M, "market itna volatile kyun hai" to M, "nifty rsi kitna hai" to M, "aaj ka summary do" to M,
        "market ka summary batao" to M, "din kaisa raha market ka" to M, "aaj kya hua market mein" to M, "nifty ne aaj kya kiya" to M,
        "how's nifty looking right now" to M, "nifty update do" to M, "banknifty ka update" to M, "sab indices kaise hain" to M,
        "nifty aur banknifty kaise hain" to M, "kaun sa index sabse strong hai" to M, "mera mtm kitna hai" to A,
        "aaj kitna profit hua" to A, "kitna paisa bana aaj" to A, "meri position dikhao" to A, "mere trades dikhao" to A,
        "kitne lots khule hain" to A, "margin kitna bacha hai" to A, "kitna margin hai" to A, "zerodha connected hai kya" to A,
        "kite login hai kya" to A, "mera aaj ka pnl" to A, "pnl batao" to A, "p and l batao" to A, "what's my profit and loss" to A,
        "my p n l" to A, "mtm kitna hai" to A, "show my possitions" to A, "my pee and ell" to A, "what's my pnl looking like" to A,
        "aaj kitne trade hue" to A, "kal kitna kamaya tha" to A, "pichle hafte ka pnl" to A, "is hafte ka profit" to A,
        "meri sabse badi loss wali position" to A, "kaun si position loss mein hai" to A, "what is my net pnl after charges" to A,
        "how much did i pay in brokerage" to A, "expiry kis din hai" to I, "agla expiry kab hai" to I, "kal holiday hai kya" to I,
        "aaj market band hai kya" to I, "market kitne baje khulta hai" to I, "nifty ka lot size" to I,
        "banknifty ka lot size kitna hai" to I, "atm kya hai nifty ka" to I, "how many lots can i buy with 50000" to I,
        "50000 mein kitne lot" to I, "1 lakh mein kitne lot aayenge" to I, "nifty atm call ka premium" to I, "delta kya hota hai" to I,
        "gamma kya hai" to I, "what is a covered call" to I, "iron condor kya hai" to I, "what does otm mean" to I,
        "what is an itm option" to I, "kya main trade karu" to M, "aaj trade karna safe hai kya" to M, "market mein entry lu kya" to M,
        "call kharidu ya put" to M, "kuch idea do" to M, "jarvis tum kya kar sakte ho" to J, "tum kaun ho" to C, "kaise ho" to C,
        "shukriya" to C, "dhanyavad" to C, "thanks jarvis" to C, "help karo" to J, "how are things" to C, "tum kaise ho" to C,
        "aap kaun ho" to C, "kya haal hai" to C, "dollar ka rate kya hai" to H, "crude ka bhav kya hai" to H, "what is the vee wap" to H,
        "what is the target" to H, "how did asia close" to H, "nasdaq futures" to H, "how is the rupee today" to H,
        "nifty kitna aur jayega" to H, "how low can nifty go" to H, "what is my day target" to A,
    )

    /**
     * Still not answered as meant: none since round 5 (crude, the Dow, the rupee, results, VWAP, a target and lots with no
     * amount are now said so; "how are things" is small talk). Kept for the next sweep; whatever lands here must never act.
     */
    private val KNOWN_GAPS = listOf<String>()

    /** Things Boss asks to be DONE - and the questions that sound like them. Each must read exactly as before the audit. */
    private val ACTIONS: List<String> = listOf(
        "stop all strategies", "stop strategy 1", "start all strategies", "start orb", "stop orb 5", "pause all bots", "halt the algos",
        "close all positions", "close my nifty position", "close everything", "exit all trades", "square off everything",
        "cancel all orders", "cancel order 2", "cancel my pending orders", "kill switch on", "turn the kill switch on",
        "kill switch off", "activate the kill switch", "switch to paper mode", "go back to paper", "switch to live mode", "go live",
        "buy 2 lots nifty 25000 ce", "buy 1 lot banknifty atm pe", "sell 1 lot nifty 24500 pe", "buy nifty call",
        "set an alarm on nifty above 25000", "alert me when banknifty goes below 51000", "remove alarm 2", "delete all alarms",
        "mute for 30 minutes", "be quiet", "unmute", "stop talking", "remind me at 3 pm to check nifty", "cancel my reminders",
        "set my day target to 5000", "set daily loss limit to 3000", "set max lots 5", "trail my stop loss", "set target 25200 on nifty",
        "strategy 1 band karo", "sab strategies band karo", "orb chalu karo", "saare orders cancel karo", "position band karo",
        "nifty 25000 ce 2 lot khareedo", "kill switch on karo", "paper mode pe daalo", "alarm lagao nifty 25000 pe", "chup raho",
        "can you close all positions?", "should i close my position", "did i buy 2 lots of nifty?", "what if i buy nifty calls",
        "why did you stop orb", "is the kill switch on", "what is my daily loss limit", "what are my alarms", "what's my target",
        "am i on live", "is orb running", "how many orders did i place", "what is a stop loss", "explain the kill switch",
        "aur banknifty band karo", "and close everything", "how is nifty and stop all strategies", "nifty kaisa hai aur sab band karo",
        "umm stop all strategies", "jarvis stop orb", "please cancel all orders", "go live now", "turn on live mode",
        "am i on paper or live", "how much can i still lose today", "kya kharidu", "trade lu kya", "kya buy karu", "nifty khareedu kya",
    )

    /** How [s] reads for anything that could act: the command or order, the quick everyday line, reminders, follow-ups. */
    private fun actionPrint(s: String): String {
        val p = Ask.parse(s)
        val c = p.command?.let { "${it.kind}/${it.target}/${it.number}/${it.market}/${it.above}/${it.level}/${it.pct}/${it.lots}/${it.day != null}" }
        val acts = listOf(Topic.ORDER, Topic.COMMAND).filter { it in p.topics }
        return listOf(c, p.order?.toString(), acts, Intents.quick(s), Reminder.asked(s), Reminder.cancelAsked(s), FollowUp.acts(s),
            FollowUp.resolve("how is nifty", s), Understand.questions(null, s)?.filter { FollowUp.acts(it) }).joinToString(" | ")
    }

    /** Every line in this audit that could act, or names a word of acting (stop, close, order, live, alarm, limit...). */
    private fun actionLike(): List<String> = (ACTIONS + ASKED.map { it.first }).distinct().filter { s ->
        actionPrint(s).let { !it.startsWith("null | null | [] | null | false | false | false") } ||
            Regex("(?i)\\b(stop|start|close|cancel|order|orders|live|kill|switch|alarm|alert|mute|remind|reminder|target|limit|buy|sell|band|chalu|khareed|kharid|lagao)")
                .containsMatchIn(s)
    }

    @Test fun nothingThatActsReadsAnyDifferently() {
        val before = javaClass.getResource("/coverage-actions.txt")!!.readText().lines().filter { it.isNotBlank() }
            .associate { it.substringBefore("\t") to it.substringAfter("\t") }
        // (Every line read before too: one that stopped reading as an action would be missed otherwise.)
        val lines = (before.keys + actionLike()).distinct()
        assertTrue(lines.size >= 100, "${lines.size}")
        for (s in lines) assertEquals(before[s], actionPrint(s), s)
    }

    @Test fun everyLineIsUnderstoodAsItsKind() {
        val wrong = ASKED.mapNotNull { (s, want) ->
            val got = route(s)
            // Two questions in one breath: the first part named, the second its own kind.
            if (got.first() == want) null else "\"$s\": wanted $want, got $got"
        }
        assertTrue(wrong.isEmpty(), "not understood or wrong kind (${wrong.size} of ${ASKED.size}):\n" + wrong.joinToString("\n"))
    }

    @Test fun twoQuestionsInOneBreathAreEachTheirOwnKind() {
        assertEquals(listOf(M, A), route("how is nifty and what's my p&l"))
        assertEquals(listOf(M, A), route("nifty kaisa hai aur mera p&l kitna hai"))
    }

    @Test fun theAuditListIsLargeAndEachLineOnce() {
        assertTrue(ASKED.size >= 650, "${ASKED.size}")
        assertEquals(ASKED.size, ASKED.map { it.first }.distinct().size)
    }

    @Test fun gapsTheAuditFound() {
        // "You know" asked is not a filler.
        assertEquals(null, Understand.questions(null, "how do you know that"))
        assertEquals(null, Understand.questions(null, "do you know why nifty fell"))
        assertEquals(listOf("how is nifty"), Understand.questions(null, "you know, how is nifty"))
        // Hinglish asked of a word explains it; Boss's own stop stays his.
        assertTrue(Glossary.explain("theta kya hai")!!.startsWith("Theta"))
        assertTrue(Glossary.explain("delta ka matlab kya hai")!!.startsWith("Delta"))
        assertEquals(null, Glossary.explain("mera stop loss kya hai"))
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse("mera stop loss kya hai").topics)
        // The calendar and the clock.
        assertTrue(MarketDays.expiryAsked("is thursday expiry"))
        assertTrue(MarketDays.expiryAsked("aaj expiry hai kya"))
        assertTrue(MarketDays.expiryAsked("how much time is left for expiry"))
        assertEquals(MarketDays.Asked.Day(today.plusDays(1)), MarketDays.asked("kal chhutti hai kya", today))
        assertEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("market kitne baje band hoga"))
        assertEquals(OptionFacts.Asked.Atm(Market.NIFTY), OptionFacts.asked("what's the premium of the atm call"))
        assertEquals(OptionFacts.Asked.Atm(Market.BANKNIFTY), OptionFacts.asked("banknifty atm"))
        assertEquals(null, OptionFacts.asked("what is an atm call"), "the word, for the glossary")
        assertEquals(null, OptionFacts.asked("buy 1 lot nifty atm call"), "an order is never a quote")
        // Boss's own: green, buying power, paper or live, how much more he can lose.
        for (s in listOf("am i green today", "what's my buying power", "am i on paper or live", "how much can i still lose today",
            "is kite connected", "mere paas kya hai", "paisa kitna bacha hai")) assertEquals(setOf(Topic.ACCOUNT), Ask.parse(s).topics, s)
        assertTrue(Section.RISK in AppAnswers.sections("how much can i still lose today"))
        assertTrue(Section.SETTINGS in AppAnswers.sections("am i on paper or live"))
        assertTrue(Section.FUNDS in AppAnswers.sections("what's my buying power"))
        // The trade check and a trade idea in Hinglish - questions, never an order.
        assertEquals(setOf(Topic.TRADE_CHECK), Ask.parse("trade lu kya").topics)
        assertEquals(setOf(Topic.SUGGEST), Ask.parse("kya kharidu").topics)
        assertEquals(null, Ask.parse("kya kharidu").order)
    }

    // ---- The cross-feature routing audit (round 6, 5 Oct) ----

    /**
     * Which feature answers [said] in Jarvis (not GOLD), taking IraHub.ask's branches in its own order (app/.../IraHub.kt,
     * ask(): fillers and follow-ups first, then DayJournal, AlertSense, Airtime, DataAge, Honest, Thinking, ChainIntel,
     * TradeCase, Agenda, Improve, the reminders and Jarvis's own checks, Distance... down to the account's sections and
     * the reasoning over the candles). Over the pure readers only; a branch the app adds must be added here in its place.
     * (Thinking takes its question first; with nothing in the trail and SelfWhy's words it goes on to SelfWhy - by design.)
     */
    private fun feature(said: String): String {
        val asSaid = Sources.asked(said) || AboutBoss.knowAsked(said) || Memory.recallAsked(said) || Memory.forgetAsked(said)
        val qs = if (asSaid) null else Understand.questions(null, said)?.takeIf { it.isNotEmpty() && it != listOf(said) }
        if (qs != null) return qs.joinToString(" & ") { feature(it) }
        val q = said
        if (DayJournal.asked(q)) return "DayJournal"
        val p = Ask.parse(q)
        val plain = p.order == null && p.command == null
        if (p.order == null && AlertSense.asked(q) != null) return "AlertSense"
        if (p.order == null && Airtime.asked(q)) return "Airtime"
        if (plain && DataAge.asked(q)) return "DataAge"
        if (plain && Honest.asked(q) != null) return "Honest"
        if (plain && Thinking.asked(q) != null) return "Thinking"
        if (plain && ChainIntel.asked(q) != null) return "ChainIntel"
        if (plain && TradeCase.asked(q)) return "TradeCase"
        if (plain && Agenda.asked(q)) return "Agenda"
        if (plain && Improve.asked(q)) return "Improve"
        if (Reminder.cancelAsked(q) || Reminder.asked(q)) return "Reminder"
        if (SelfCheck.asked(q)) return "SelfCheck"
        if (Reminder.missedAsked(q)) return "Missed"
        if (Reminder.heardAsked(q)) return "Heard"
        if (Latency.asked(q)) return "Latency"
        if (Reminder.usageAsked(q)) return "Usage"
        if (Reminder.modelAsked(q)) return "Model"
        if (plain && Distance.asked(q) != null) return "Distance"
        if (plain && OptionFacts.asked(q) != null) return "OptionFacts"
        if (plain && Sizing.asked(q) != null) return "Sizing"
        if (plain && DaySummary.asked(q)) return "DaySummary"
        if (plain && (MarketDays.expiryAsked(q) || MarketDays.asked(q, today) != null)) return "MarketDays"
        if (plain && p.markets.isEmpty() && Reminder.tomorrow(q)) return "Tomorrow"
        if (plain && Outlook.asked(q) && !Regex("(?i)\\b(my|mine|our)\\b").containsMatchIn(q)) return "Outlook"
        if (plain && Chat.smallTalk(q, 0) != null) return "Chat"
        if (Memory.toKeep(q) != null || plain && (AboutBoss.fact(q) != null || AboutBoss.forgetAsked(q) != null) || AboutBoss.knowAsked(q) ||
            Memory.recallAsked(q) || Memory.forgetAsked(q)) return "AboutBoss"
        if (Goals.asked(q) || Goals.clearAsked(q)) return "Goals"
        if (plain && SelfWhy.asked(q)) return "SelfWhy"
        if (Vetting.asked(q)) return "Vetting"
        if (SelfCalibration.asked(q)) return "SelfCalibration"
        if (Lessons.asked(q)) return "Lessons"
        if (Sources.asked(q)) return "Sources"
        if (Habits.asked(q)) return "Habits"
        if (Topic.BACKTEST in p.topics) return "Backtest"
        if (Topic.ACCOUNT in p.topics) return "Account:" + AppAnswers.sections(q).joinToString("+")
        if (Topic.COMMAND in p.topics || Topic.ORDER in p.topics) return "Act"
        if (Topic.SUGGEST in p.topics) return "Suggest"
        if (Topic.TRADE_CHECK in p.topics) return "TradeCheck"
        // IraHub.reasoned, in its order.
        val t = p.text.ifBlank { q }
        if (MarketStory.asked(t) != null) return "MarketStory"
        if (SharpMove.asked(t) != null) return "SharpMove"
        if (MarketMemory.asked(t) != null) return "MarketMemory"
        if (ExpiryDay.asked(t)) return "ExpiryDay"
        if (Topic.WHY in p.topics && !Regex("(?i)\\bhow much\\b").containsMatchIn(t)) return "Why"
        if (Regex("(?i)\\b(i|me|my|we|our)\\b").containsMatchIn(t)) return "Market"
        if (Odds.asked(t) != null) return "Odds"
        if (ExpectedRange.asked(t)) return "ExpectedRange"
        if (PeriodMove.asked(t) != null) return "PeriodMove"
        return if (Topic.OFF_TOPIC in p.topics) "Missed" else "Market"
    }

    private val ACCOUNT_REVIEW = "Account:REVIEW"

    /** The new families (DayJournal to AboutBoss), as Boss says them - English, Hinglish, the recognizer's spellings. */
    private val ROUTED: List<Pair<String, String>> = listOf(
        // ---- MarketMemory: the notable sessions remembered ----
        "when did nifty last gap down" to "MarketMemory", "when did banknifty last gap up this much" to "MarketMemory",
        "last time vix spiked" to "MarketMemory", "when did vix last jump like this" to "MarketMemory",
        "when was the last trend day" to "MarketMemory", "how many trend days this month" to "MarketMemory",
        "how many gap ups this month" to "MarketMemory", "what happened the last 3 expiries" to "MarketMemory",
        "how did the last expiry go" to "MarketMemory", "when did banknifty last fall this much" to "MarketMemory",
        "do you remember any big days lately" to "MarketMemory", "what notable days do you remember" to "MarketMemory",
        "nifty last kab gap down hua" to "MarketMemory", "pichle 3 expiry mein kya hua" to "MarketMemory",
        "remember when nifty gapped down" to "MarketMemory", "do you remember when nifty last gapped down" to "MarketMemory",
        // ---- ChainIntel: writing, the OI's shift, the skew, the move priced in by expiry ----
        "where is the most call writing" to "ChainIntel", "where is the most put writing" to "ChainIntel",
        "sabse zyada call writing kahan hai" to "ChainIntel", "call writing kahan ho rahi hai" to "ChainIntel",
        "put writing kahan hai" to "ChainIntel", "where are the put writers" to "ChainIntel", "where is call writing highest" to "ChainIntel",
        "how has the oi shifted since morning" to "ChainIntel", "how has the oi changed since the open" to "ChainIntel",
        "oi kaise shift hua" to "ChainIntel", "iv skew" to "ChainIntel", "iv skew kya hai" to "ChainIntel", "is there a skew" to "ChainIntel",
        "are puts costlier than calls" to "ChainIntel", "expected move by expiry" to "ChainIntel",
        "what's the expected move for this expiry" to "ChainIntel", "what's the expected move this expiry" to "ChainIntel",
        "expected move for banknifty by expiry" to "ChainIntel", "expected move this week" to "ChainIntel",
        "what is the straddle pricing" to "ChainIntel", "straddle kitna hai" to "ChainIntel", "how much move is the market pricing in" to "ChainIntel",
        "analyze the option chain in detail" to "ChainIntel",
        // ---- ExpectedRange: the day's range from VIX (not the expiry's straddle) ----
        "expected move today" to "ExpectedRange", "expected range today" to "ExpectedRange", "expected move kitna hai" to "ExpectedRange",
        "what is the expected move" to "ExpectedRange",
        // ---- TradeCase: the case for and against trading now ----
        "make the case" to "TradeCase", "make the case for trading now" to "TradeCase", "make the case for and against" to "TradeCase",
        "pros and cons of trading now" to "TradeCase", "pros and cons of trading today" to "TradeCase", "talk me through it" to "TradeCase",
        "should i trade now and why" to "TradeCase", "explain the trade check" to "TradeCase", "full trade check" to "TradeCase",
        "trade karu ya nahi aur kyun" to "TradeCase", "aaj trade karu ya nahi kyun" to "TradeCase",
        // ---- Thinking: his own decisions, from the reason trail ----
        "why didn't you take that trade" to "Thinking", "why didnt you take the trade" to "Thinking", "why didnt u take that trade" to "Thinking",
        "why did you not take that trade" to "Thinking", "why didn't you take the nifty trade" to "Thinking",
        "tumne woh trade kyun nahi liya" to "Thinking", "trade kyun nahi liya" to "Thinking", "jarvis ne woh trade kyun nahi liya" to "Thinking",
        "why did you skip that trade" to "Thinking", "why didn't solo take that trade" to "Thinking", "why did you take that trade" to "Thinking",
        "why only one lot" to "Thinking", "why were you quiet at 11" to "Thinking", "why didn't you alert me about the fall" to "Thinking",
        "why didn't you tell me about that move" to "Thinking", "why did you hold back that alert" to "Thinking",
        "why did you skip that check" to "Thinking", "why did you skip the plan item" to "Thinking", "what made you say check me" to "Thinking",
        "why did you say check me" to "Thinking", "what were you thinking" to "Thinking", "walk me through your day" to "Thinking",
        // ---- SelfWhy: what he did, from the activity log ----
        "why did you stop orb" to "SelfWhy", "why did you say that" to "SelfWhy",
        // ---- Airtime: the day's quiet ----
        "why so quiet" to "Airtime", "why so quite" to "Airtime", "why are you so quiet today" to "Airtime", "why have you been quiet" to "Airtime",
        "why were you so silent" to "Airtime", "why so quiet jarvis" to "Airtime", "why aren't you saying anything" to "Airtime",
        "why havent you said anything today" to "Airtime", "itna chup kyun ho" to "Airtime", "chup kyun ho" to "Airtime",
        "aaj itna shant kyun ho" to "Airtime", "did you hold back any alerts" to "Airtime", "how many alerts did you say today" to "Airtime",
        "what did you not tell me today" to "Airtime",
        // ---- AlertSense: the alerts he says less often ----
        "which alerts do you hold back" to "AlertSense", "say everything again" to "AlertSense",
        // ---- DataAge: how old his data is ----
        "is your data fresh" to "DataAge", "how old is your data" to "DataAge", "is the data stale" to "DataAge", "is your data live" to "DataAge",
        "is the feed delayed" to "DataAge", "how fresh is the data" to "DataAge", "is the option chain fresh" to "DataAge",
        "how old are the prices" to "DataAge", "when did you last read the news" to "DataAge", "is the news old" to "DataAge",
        "are your prices live" to "DataAge", "data fresh hai kya" to "DataAge", "data kitna purana hai" to "DataAge",
        "tumhara data live hai kya" to "DataAge", "bhav kitna purana hai" to "DataAge",
        // ---- DayJournal: help with today's journal ----
        "help me journal today" to "DayJournal", "let's journal today" to "DayJournal", "help me write my journal" to "DayJournal",
        "help me journal" to "DayJournal", "draft my journal" to "DayJournal", "journal my day" to "DayJournal", "my journal entry" to "DayJournal",
        "aaj ka journal likhne mein madad karo" to "DayJournal", "journal likhne mein help karo" to "DayJournal",
        "mera journal likhwao" to "DayJournal",
        // ---- Improve and Lessons ----
        "how are you improving" to "Improve", "tum kaise improve ho rahe ho" to "Improve", "are you getting better" to "Improve",
        "how are you getting better" to "Improve", "what are you improving" to "Improve", "what have you learned this week" to "Lessons",
        // ---- AboutBoss: what he was told about Boss ----
        "what do you know about me" to "AboutBoss", "tum mere baare mein kya jaante ho" to "AboutBoss", "what did i tell you" to "AboutBoss",
        "forget that i trade on fridays" to "AboutBoss", "i get greedy after a win" to "AboutBoss", "remember that i trade on fridays" to "AboutBoss",
        // ---- Boss's week, month, charges and trades (the account's own sections) ----
        "how was my week" to ACCOUNT_REVIEW, "how was my weak" to ACCOUNT_REVIEW, "review my week" to ACCOUNT_REVIEW,
        "how did my week go" to ACCOUNT_REVIEW, "weekly review" to ACCOUNT_REVIEW, "how was my week overall" to ACCOUNT_REVIEW,
        "mera hafta kaisa raha" to ACCOUNT_REVIEW, "any insights on my trading" to ACCOUNT_REVIEW, "what are my habits" to ACCOUNT_REVIEW,
        "review my trades" to ACCOUNT_REVIEW, "how did i do this week" to "Account:HISTORY",
        "how was my month" to "Account:MONTH", "mera mahina kaisa raha" to "Account:MONTH", "review last month" to "Account:MONTH",
        "how was my month after charges" to "Account:MONTH",
        "how much did i pay in charges this week" to "Account:CHARGES", "charges kitne lage" to "Account:CHARGES",
        "kitna brokerage diya" to "Account:CHARGES", "how much brokerage did i pay" to "Account:CHARGES",
        "how much did i pay in charges this month" to "Account:CHARGES", "what were my charges today" to "Account:CHARGES",
        "how much brokerage this month" to "Account:CHARGES", "how much did charges eat into my profit" to "Account:CHARGES",
        "how much tax on my trades" to "Account:CHARGES", "what is my gst" to "Account:CHARGES",
        "how was my last trade" to "Account:REPLAY", "replay my last trade" to "Account:REPLAY", "replay my trades today" to "Account:REPLAY",
        "how were my exits today" to "Account:REPLAY", "mera last trade kaisa tha" to "Account:REPLAY", "mera pichla trade kaisa raha" to "Account:REPLAY",
        "why did my last trade lose" to "Account:LOSSES", "what did you do today" to "Account:ACTIVITY",
        // ---- More of each, as said in a hurry ----
        "when did nifty last gap down like this" to "MarketMemory", "last time banknifty gapped up" to "MarketMemory",
        "where is maximum call writing" to "ChainIntel", "how is the skew today" to "ChainIntel", "are puts dearer than calls" to "ChainIntel",
        "what is the atm straddle" to "ChainIntel", "expected move till expiry" to "ChainIntel", "implied move by expiry" to "ChainIntel",
        "give me the case for and against" to "TradeCase", "why didnt jarvis take that trade" to "Thinking",
        "how come you didn't take that trade" to "Thinking", "why didn't you take that one" to "Thinking",
        "why were you silent today" to "Airtime", "why so silent" to "Airtime", "why are u so quiet" to "Airtime",
        "is your data up to date" to "DataAge", "data taza hai kya" to "DataAge", "write my journal" to "DayJournal",
        "help me with my journal" to "DayJournal", "pichla mahina kaisa raha" to "Account:MONTH", "stt kitna laga" to "Account:CHARGES",
        "brokerage kitna gaya" to "Account:CHARGES", "what do you know about me jarvis" to "AboutBoss",
        // ---- The market's own week (never Boss's history) ----
        "how was the market this week" to "PeriodMove", "how was nifty this week" to "PeriodMove",
    )

    @Test fun eachFamilyGetsItsOwnQuestions() {
        val wrong = ROUTED.mapNotNull { (s, want) -> feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got" } }
        assertTrue(wrong.isEmpty(), "taken by the wrong feature (${wrong.size} of ${ROUTED.size}):\n" + wrong.joinToString("\n"))
        assertTrue(ROUTED.size >= 180, "${ROUTED.size}")
        assertEquals(ROUTED.size, ROUTED.map { it.first }.distinct().size)
    }

    @Test fun neighboursKeepTheirOwnQuestions() {
        // The pairs that sound alike: each goes to its own feature in the hub's order.
        for ((a, b) in listOf(
            ("why didn't you take that trade" to "Thinking") to ("why did you stop orb" to "SelfWhy"),
            ("expected move today" to "ExpectedRange") to ("expected move by expiry" to "ChainIntel"),
            ("how was my week" to ACCOUNT_REVIEW) to ("how was the market this week" to "PeriodMove"),
            ("how was my month" to "Account:MONTH") to ("how much did i pay in charges this month" to "Account:CHARGES"),
            ("how was my last trade" to "Account:REPLAY") to ("why did my last trade lose" to "Account:LOSSES"),
            ("why so quiet" to "Airtime") to ("why were you quiet at 11" to "Thinking"),
            ("make the case" to "TradeCase") to ("should i trade now" to "TradeCheck"),
            ("help me journal today" to "DayJournal") to ("how was my day" to "DaySummary"),
            ("remember when nifty gapped down" to "MarketMemory") to ("remember that i trade on fridays" to "AboutBoss"),
            ("how are you improving" to "Improve") to ("how are you" to "Chat"),
            ("is your data fresh" to "DataAge") to ("what's sgx nifty" to "Honest"),
        )) { assertEquals(a.second, feature(a.first), a.first); assertEquals(b.second, feature(b.first), b.first) }
        // "Remember when..." asks; it is never kept as a note.
        assertEquals(null, Memory.toKeep("remember when nifty gapped down"))
        assertEquals("i trade on fridays", Memory.toKeep("remember that i trade on fridays"))
        // "How did I do this week" is his week, not the calendar's events; "any events this week" stays the events.
        assertEquals(setOf(Section.HISTORY), AppAnswers.sections("how did i do this week"))
        assertEquals(setOf(Section.EVENTS), AppAnswers.sections("any events this week"))
    }

    @Test fun noRoutedQuestionActs() {
        // Every line of the routing audit is a question: no feature it reaches acts, and nothing in it reads as an
        // order (a "journal ... help karo" read as a note is taken by DayJournal first, before anything is parsed).
        for ((s, _) in ROUTED) {
            val f = feature(s)
            assertTrue(f != "Act" && f != "Reminder", "$s -> $f")
            assertEquals(null, Ask.parse(s).order, s)
            assertTrue(Understand.questions(null, s).orEmpty().none { FollowUp.acts(it) }, s)
        }
    }

    @Test fun whatIsStillNotUnderstoodNeverActs() {
        for (s in KNOWN_GAPS) assertTrue(Kind.ACT !in route(s), s)
        // Round 5's gaps: said honestly now - and none of them acts.
        for (s in listOf("kitne lot le sakta hoon", "what's the dollar rupee", "where is the bottom", "what's the target for nifty today",
            "where is vwap", "what's crude doing", "how is the dow", "how did us markets close", "how is asia", "any results today"))
            assertEquals(listOf(Kind.HONEST), route(s), s)
    }
}
