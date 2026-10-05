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

    /**
     * The kind of each question [said] is understood as, in the order the app's hub takes them (IraHub.ask). Boss's learned
     * words (Jarvis's own) and his routine with Jarvis (his own) only as said by him, before anything is cleaned or split;
     * a question said with something to do is left to the multi-step plan by the families added since round 5.
     */
    private fun route(said: String): List<Kind> {
        if (Corrections.wordsAsked(said) || Corrections.forgetWordAsked(said) != null) return listOf(Kind.JARVIS)
        if (Routine.asked(said) || Routine.forgetAsked(said)) return listOf(Kind.ACCOUNT)
        val asSaid = Sources.asked(said) || AboutBoss.knowAsked(said) || Memory.recallAsked(said) || Memory.forgetAsked(said) || PatternCalls.asked(said) || TrendReads.asked(said) || SinceMorning.asked(said) || ExpiryPin.asked(said) != null ||
            Learnings.asked(said) != null || Learnings.undoAsked(said) || NewsMoves.asked(said) != null || PreMarket.asked(said) ||
            ChainDrift.asked(said) != null || Headroom.asked(said) != null || ArmFit.asked(said) || WeakLink.asked(said) || ArmDay.asked(said) != null || NetLean.asked(said) || ExpiryEve.asked(said) || BeforeTomorrow.asked(said) || BotTrades.asked(said) != null || DayClock.asked(said) != null ||
            SaidAbout.asked(said) != null || GapRecord.asked(said) != null || Causes.asked(said) != null || WeekAhead.asked(said) != null || AskedAgain.asked(said) || FigureFirst.asked(said) != null || Weekdays.asked(said) != null || DayCompare.asked(said) != null || LikeToday.asked(said) ||
            RangeBreaks.asked(said) != null || PriorDay.asked(said) != null || LastHour.asked(said) != null || InsideDays.asked(said) != null || FirstMove.asked(said) != null || VixNext.asked(said) != null || SplitDays.asked(said) != null || RoundCloses.asked(said) != null || MonthTurns.asked(said) != null || LunchRange.asked(said) != null || OpenHighLow.asked(said) != null || BigCandles.asked(said) != null || ExtremeCloses.asked(said) != null || NeedsTrue.asked(said) || Clarity.asked(said) != null || ZerodhaSession.asked(said) != null || Tour.asked(said) || WrongThing.asked(said) != null || WrongThing.objected(said) || OrderWhy.asked(said) != null || ArmHabits.asked(said) || MorningSense.asked(said) != null || HonestStars.asked(said) != null || TalkHours.asked(said) != null || MorningAsks.asked(said) != null || TurnDowns.asked(said) != null || TopicLength.asked(said) != null || OutlookCheck.asked(said) || UsualIndex.asked(said) != null || RelayHealth.asked(said) != null || StreamHealth.asked(said) || WatchAsk.asked(said) != null || BatteryUse.asked(said) || SwitchOff.asked(said) != null
            RangeBreaks.asked(said) != null || PriorDay.asked(said) != null || LastHour.asked(said) != null || InsideDays.asked(said) != null || FirstMove.asked(said) != null || VixNext.asked(said) != null || SplitDays.asked(said) != null || RoundCloses.asked(said) != null || MonthTurns.asked(said) != null || LunchRange.asked(said) != null || OpenHighLow.asked(said) != null || BigCandles.asked(said) != null || NeedsTrue.asked(said) || Clarity.asked(said) != null || ZerodhaSession.asked(said) != null || Tour.asked(said) || WrongThing.asked(said) != null || WrongThing.objected(said) || OrderWhy.asked(said) != null || ArmHabits.asked(said) || MorningSense.asked(said) != null || HonestStars.asked(said) != null || TalkHours.asked(said) != null || MorningAsks.asked(said) != null || TurnDowns.asked(said) != null || TopicLength.asked(said) != null || OutlookCheck.asked(said) || RelayHealth.asked(said) != null || StreamHealth.asked(said) || WatchAsk.asked(said) != null || BatteryUse.asked(said) || SwitchOff.asked(said) != null ||
            ReminderBook.listAsked(said) || ReminderBook.cancelOne(said) != null
        return ((if (asSaid) null else Understand.questions(null, said)) ?: listOf(said)).map { kind(it, 0) }
    }

    private fun kind(q: String, depth: Int): Kind {
        val p = Ask.parse(q)
        // The families added since round 5 (rounds 6-8), in the hub's order: Boss's journal is his; Jarvis's alerts, data,
        // reasons, pattern record and self-check are his own; the chain, the structure, a what-if, the case and the news desk
        // are the market's.
        if (p.order == null && p.command == null && !Bundle.acts(q)) {
            if (DayJournal.asked(q)) return Kind.ACCOUNT
            // (Clarity: the answers said shorter aloud - Jarvis's own voice; HeardBack is the voice path's alone, not a branch here.)
            if (AlertSense.asked(q) != null || Airtime.asked(q) || Hearing.asked(q) || PatternCalls.asked(q) || TrendReads.asked(q) || Clarity.asked(q) != null) return Kind.JARVIS
            if (AskedAgain.asked(q)) return Kind.JARVIS
            if (FigureFirst.asked(q) != null) return Kind.JARVIS
            if (WrongThing.asked(q) != null || WrongThing.objected(q)) return Kind.JARVIS
            if (ArmHabits.asked(q)) return Kind.ACCOUNT
            if (MorningSense.asked(q) != null) return Kind.JARVIS
            if (HonestStars.asked(q) != null) return Kind.JARVIS
            if (TalkHours.asked(q) != null) return Kind.JARVIS
            if (MorningAsks.asked(q) != null) return Kind.JARVIS
            if (TurnDowns.asked(q) != null) return Kind.JARVIS
            if (TopicLength.asked(q) != null) return Kind.JARVIS
            if (OutlookCheck.asked(q)) return Kind.JARVIS
            if (UsualIndex.asked(q) != null) return Kind.JARVIS
            if (NewsMoves.asked(q) != null) return Kind.MARKET
            if (TaxRecords.exportAsked(q)) return Kind.ACCOUNT
            if (Learnings.asked(q) != null || Learnings.undoAsked(q)) return Kind.JARVIS
            if (PreMarket.asked(q)) return Kind.ACCOUNT
            if (Headroom.asked(q) != null) return Kind.ACCOUNT
            if (ArmFit.asked(q)) return Kind.ACCOUNT
            if (WeakLink.asked(q)) return Kind.ACCOUNT
            if (ArmDay.asked(q) != null) return Kind.ACCOUNT
            if (NetLean.asked(q)) return Kind.ACCOUNT
            if (ExpiryEve.asked(q)) return Kind.ACCOUNT
            if (BeforeTomorrow.asked(q)) return Kind.ACCOUNT
            if (BotTrades.asked(q) != null) return Kind.ACCOUNT
            if (SwitchOff.asked(q) != null) return Kind.ACCOUNT
            if (SaidAbout.asked(q) != null) return Kind.ACCOUNT
            if (WeekAhead.asked(q) != null) return Kind.INFO
            if (ZerodhaSession.asked(q) != null) return Kind.ACCOUNT
            if (OrderWhy.asked(q) != null) return Kind.ACCOUNT
            if (RelayHealth.asked(q) != null) return Kind.ACCOUNT
            if (StreamHealth.asked(q)) return Kind.ACCOUNT
            if (BatteryUse.asked(q)) return Kind.JARVIS
            if (WatchAsk.asked(q) != null) return Kind.ACCOUNT
            if (Tour.asked(q)) return Kind.JARVIS
            if (DataAge.asked(q)) return Kind.JARVIS
            if (Honest.asked(q) != null) return Kind.HONEST
            if (Thinking.asked(q) != null || Consistency.asked(q)) return Kind.JARVIS
            if (CoPilot.asked(q) || SinceMorning.asked(q) || ExpiryPin.asked(q) != null || ChainDrift.asked(q) != null || ChainIntel.asked(q) != null || DayClock.asked(q) != null || GapRecord.asked(q) != null || RangeBreaks.asked(q) != null || PriorDay.asked(q) != null || LastHour.asked(q) != null || InsideDays.asked(q) != null || FirstMove.asked(q) != null || VixNext.asked(q) != null || SplitDays.asked(q) != null || RoundCloses.asked(q) != null || MonthTurns.asked(q) != null || LunchRange.asked(q) != null || OpenHighLow.asked(q) != null || BigCandles.asked(q) != null || ExtremeCloses.asked(q) != null || Weekdays.asked(q) != null || DayCompare.asked(q) != null || LikeToday.asked(q) || Structure.asked(q) != null ||
                MindChange.asked(q) || Breadth.asked(q) != null || TradeCase.asked(q) || Scenarios.asked(q) != null ||
                Causes.asked(q) != null) return Kind.MARKET
        }
        if (SelfCheck.asked(q)) return Kind.JARVIS
        if (p.command != null || p.order != null || Topic.ORDER in p.topics || Topic.COMMAND in p.topics) return Kind.ACT
        // What the phone has no data for, VWAP, targets, lots with no budget: said so (IraHub, just after "is your data fresh").
        if (Honest.asked(q) != null) return Kind.HONEST
        if (Chat.smallTalk(q, 0) != null) return Kind.CHAT
        if (Agenda.asked(q) || Improve.asked(q) || Latency.asked(q)) return Kind.JARVIS
        if (ReminderBook.cancelOne(q) != null) return Kind.ACT
        if (ReminderBook.listAsked(q)) return Kind.ACCOUNT
        if (Reminder.cancelAsked(q) || Reminder.asked(q)) return Kind.ACT
        if (Distance.asked(q) != null) return Kind.MARKET
        if (OptionFacts.asked(q) != null || Sizing.asked(q) != null) return Kind.INFO
        if (DaySummary.asked(q)) return Kind.ACCOUNT
        if (MarketDays.expiryAsked(q) || MarketDays.asked(q, today) != null) return Kind.INFO
        if (Outlook.asked(q) && !Regex("(?i)\\b(my|mine|our)\\b").containsMatchIn(q)) return Kind.MARKET
        if (p.order == null && p.command == null && !Bundle.acts(q) && NewsDesk.asked(q) != null) return Kind.MARKET
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
        "call writing kahan hai" to M, "what are fiis doing" to A, "fii dii data" to A, "did fiis buy or sell" to A, "how is gold today" to M,
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
        // ---- Boss's own words looked up (SaidAbout, round 14): his, so the account's kind ----
        "what did i say about expiry" to A, "did i note anything about the hammer" to A, "find my notes on fridays" to A,
        "maine expiry ke baare mein kya kaha tha" to A,
        // ---- The week ahead from the calendar (WeekAhead, round 15): the calendar's, so information ----
        "what does this week look like" to I, "is this an expiry week" to I, "plan for next week" to I, "is hafte kya hai" to I,
        // ---- Why Zerodha logged Boss out (ZerodhaSession, voice round 14): his broker session, so the account's kind ----
        "why was i logged out of zerodha" to A, "why did kite log me out" to A, "what happened to my zerodha session" to A,
        "zerodha se logout kyun hua" to A, "when does my zerodha session end" to A,
        // ---- The relay's health (RelayHealth, round 19): Boss's own setup, from the diagnostics - the account's kind ----
        "why is my relay failing" to A, "is my static ip working" to A, "relay kyun nahi chal raha" to A, "is the relay down" to A,
        // ---- What should I switch off (SwitchOff, round 20): the arms' tested and paper records - Boss's account ----
        "which arms lost in both the test and on paper" to A, "is any arm worth disarming" to A, "which arms are worth keeping" to A,
        "which bot should i disarm first" to A,
        // ---- Zerodha's price stream dropping (StreamHealth): Boss's own setup, from the diagnostics - the account's kind ----
        "why is the price stream dropping" to A, "stream kyun toot raha hai" to A, "is the price stream working" to A,
        // ---- Why the app uses battery (BatteryUse, battery round 1): the app's own background work - Jarvis's own ----
        "why is the app using so much battery" to J, "battery kyun kha raha hai" to J, "what is eating my battery" to J,
        // ---- The order watch and IraAlgo's battery setting (WatchAsk, round 16): Boss's own setup, read only - the account's kind ----
        "is the watch running" to A, "why did the watch get stuck" to A, "battery setting kya hai" to A, "kya mera phone app ko rok raha hai" to A,
        // ---- What can I ask you (Tour, voice round 15): five questions for the part of the day - Jarvis's own ----
        "what can i ask you" to J, "what should i ask you now" to J, "main kya pooch sakta hoon" to J, "suggest some questions" to J,
        // ---- Two indices on opposite sides of the day (SplitDays, market intelligence round 23): the market's ----
        "how often do nifty and banknifty go opposite ways" to M, "nifty banknifty divergence record" to M,
        "nifty aur banknifty kitni baar ulte chalte hain" to M,
        // ---- What to do before tomorrow (BeforeTomorrow, usefulness round 23): his login, legs, arms, setup - the account's ----
        "what do i need to do before tomorrow" to A, "kal se pehle kya karna hai" to A, "checklist for tomorrow" to A,
        "anything i need to do before tomorrow" to A, "tomorrow's to do list" to A,
        // ---- Closes beside round numbers (RoundCloses, market intelligence round 24): the market's ----
        "does nifty end near round numbers" to M, "are round numbers a magnet for banknifty" to M,
        "nifty gol figure ke paas kitni baar khatam hota hai" to M,
        // ---- The month-start and month-end record (MonthTurns, market intelligence round 25): the market's ----
        "how does nifty do at the beginning of the month" to M, "is there a turn of the month effect" to M,
        "month end record for banknifty" to M, "mahine ki shuruaat mein nifty kaisa chalta hai" to M,
        // ---- The lunch-range record (LunchRange, market intelligence round 26): the market's ----
        "does nifty break the lunch range" to M, "is the lunch lull real" to M,
        "lunch ki range todne ke baad nifty kya karta hai" to M,
        // ---- The open-high / open-low record (OpenHighLow, market intelligence round 27): the market's ----
        "how often is the open the high of the day" to M, "open = low days record" to M,
        "open low wale din nifty kaisa chalta hai" to M,
        // ---- The big-candle record (BigCandles, market intelligence round 28): the market's ----
        "big candle record for banknifty" to M, "after a big 5-minute candle in the first hour does the day continue" to M,
        "subah bada candle aane ke baad nifty kya karta hai" to M,
        // ---- The close-at-the-ends record (ExtremeCloses, market intelligence round 29): the market's ----
        "how often does nifty finish near its high" to M, "what does banknifty do the day after it settles at the low" to M,
        "high pe khatam hone ke baad agle din kya hota hai" to M,
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
     * ask(): Boss's learned words and routine as said, fillers and follow-ups, then - for a question not said with
     * something to do (IraHub's `bundled`, [Bundle.acts]) - DayJournal, AlertSense, Airtime, Hearing, PatternCalls, TrendReads, Clarity,
     * WordFit, AskedAgain, FigureFirst, WrongThing, ArmHabits, MorningSense, HonestStars, TalkHours, MorningAsks, TurnDowns, TopicLength, OutlookCheck, UsualIndex, NewsMoves, TaxExport, Learnings, PreMarket, Headroom, ArmFit, WeakLink, ArmDay, NetLean, ExpiryEve, BeforeTomorrow, BotTrades, SaidAbout, WeekAhead, DataAge, Honest, Thinking,
     * Consistency, CoPilot, SinceMorning, ExpiryPin, ChainDrift, ChainIntel, DayClock, GapRecord, RangeBreaks, PriorDay, LastHour, InsideDays, FirstMove, VixNext, SplitDays, RoundCloses, MonthTurns, LunchRange, OpenHighLow, BigCandles, ExtremeCloses, Weekdays, DayCompare, LikeToday, Structure, MindChange, Breadth, TradeCase,
     * Scenarios, Causes, Agenda, Improve; the reminders and Jarvis's own checks,
     * Distance... Outlook, NewsDesk, down to the account's sections (PositionHealth, BotHealth and NeedsTrue are its HEALTH,
     * BOTS and NEED; HeardBack is the voice path's own read-back, never a branch of the hub), a pattern explained, Solo and IraHub.reasoned's readers over the candles, each in its
     * place). Over the pure readers only (what Boss's corrections taught depends on what is kept, and is left out); a
     * branch the app adds must be added here in its place. (Thinking takes its question first; with nothing in the trail
     * and SelfWhy's words it goes on to SelfWhy - by design.) [understood] and [cleaned] are IraHub.ask's own: a part of a
     * split or a learned reading, and one question with its fillers gone.
     */
    internal fun feature(said: String, understood: Boolean = false, cleaned: Boolean = false): String {
        // Boss's own learned words and routine: only as said by him, before anything is cleaned or split.
        // (Before the order / command guard, as in the hub: "forget ..." reads as acting to the parser and to Bundle, and these
        // stores only drop Boss's own learned words and habits - nothing that trades.)
        if (!understood && (Corrections.wordsAsked(said) || Corrections.forgetWordAsked(said) != null)) return "Corrections"
        if (!understood && (Routine.asked(said) || Routine.forgetAsked(said))) return "Routine"
        val asSaid = Sources.asked(said) || AboutBoss.knowAsked(said) || Memory.recallAsked(said) || Memory.forgetAsked(said) ||
            Corrections.wordsAsked(said) || Corrections.forgetWordAsked(said) != null || Routine.asked(said) || Routine.forgetAsked(said) ||
            PatternCalls.asked(said) || TrendReads.asked(said) || SinceMorning.asked(said) || ExpiryPin.asked(said) != null || Learnings.asked(said) != null || Learnings.undoAsked(said) || NewsMoves.asked(said) != null ||
            PreMarket.asked(said) ||
            ChainDrift.asked(said) != null || Headroom.asked(said) != null || ArmFit.asked(said) || WeakLink.asked(said) || ArmDay.asked(said) != null || NetLean.asked(said) || ExpiryEve.asked(said) || BeforeTomorrow.asked(said) || BotTrades.asked(said) != null || NeedsTrue.asked(said) || Clarity.asked(said) != null || DayClock.asked(said) != null ||
            SaidAbout.asked(said) != null || GapRecord.asked(said) != null || WordFit.asked(said) != null || Causes.asked(said) != null || WeekAhead.asked(said) != null || AskedAgain.asked(said) || FigureFirst.asked(said) != null || MindChange.asked(said) || Weekdays.asked(said) != null || DayCompare.asked(said) != null || LikeToday.asked(said) || RangeBreaks.asked(said) != null || PriorDay.asked(said) != null || LastHour.asked(said) != null || InsideDays.asked(said) != null || FirstMove.asked(said) != null || VixNext.asked(said) != null || SplitDays.asked(said) != null || RoundCloses.asked(said) != null || MonthTurns.asked(said) != null || LunchRange.asked(said) != null || OpenHighLow.asked(said) != null || BigCandles.asked(said) != null || ExtremeCloses.asked(said) != null || ZerodhaSession.asked(said) != null || Tour.asked(said) || WrongThing.asked(said) != null || WrongThing.objected(said) || OrderWhy.asked(said) != null || ArmHabits.asked(said) || MorningSense.asked(said) != null || HonestStars.asked(said) != null || TalkHours.asked(said) != null || MorningAsks.asked(said) != null || TurnDowns.asked(said) != null || TopicLength.asked(said) != null || OutlookCheck.asked(said) || UsualIndex.asked(said) != null || RelayHealth.asked(said) != null || StreamHealth.asked(said) || WatchAsk.asked(said) != null || BatteryUse.asked(said) || SwitchOff.asked(said) != null
            SaidAbout.asked(said) != null || GapRecord.asked(said) != null || WordFit.asked(said) != null || Causes.asked(said) != null || WeekAhead.asked(said) != null || AskedAgain.asked(said) || FigureFirst.asked(said) != null || MindChange.asked(said) || Weekdays.asked(said) != null || DayCompare.asked(said) != null || LikeToday.asked(said) || RangeBreaks.asked(said) != null || PriorDay.asked(said) != null || LastHour.asked(said) != null || InsideDays.asked(said) != null || FirstMove.asked(said) != null || VixNext.asked(said) != null || SplitDays.asked(said) != null || RoundCloses.asked(said) != null || MonthTurns.asked(said) != null || LunchRange.asked(said) != null || OpenHighLow.asked(said) != null || BigCandles.asked(said) != null || ZerodhaSession.asked(said) != null || Tour.asked(said) || WrongThing.asked(said) != null || WrongThing.objected(said) || OrderWhy.asked(said) != null || ArmHabits.asked(said) || MorningSense.asked(said) != null || HonestStars.asked(said) != null || TalkHours.asked(said) != null || MorningAsks.asked(said) != null || TurnDowns.asked(said) != null || TopicLength.asked(said) != null || OutlookCheck.asked(said) || RelayHealth.asked(said) != null || StreamHealth.asked(said) || WatchAsk.asked(said) != null || BatteryUse.asked(said) || SwitchOff.asked(said) != null ||
            ReminderBook.listAsked(said) || ReminderBook.cancelOne(said) != null
        val qs = if (asSaid || understood || cleaned) null else Understand.questions(null, said)?.takeIf { it.isNotEmpty() && it != listOf(said) }
        if (qs != null) return if (qs.size == 1) feature(qs[0], cleaned = true) else qs.joinToString(" & ") { feature(it, understood = true) }
        val q = said
        // A question said with something to do: the question handlers leave it to the multi-step plan (IraHub's `bundled`).
        val free = !Bundle.acts(q)
        val p = Ask.parse(q)
        val plain = p.order == null && p.command == null
        val alone = plain && free
        if (alone && DayJournal.asked(q)) return "DayJournal"
        if (alone && AlertSense.asked(q) != null) return "AlertSense"
        if (alone && Airtime.asked(q)) return "Airtime"
        if (alone && Hearing.asked(q)) return "Hearing"
        if (alone && PatternCalls.asked(q)) return "PatternCalls"
        if (alone && TrendReads.asked(q)) return "TrendReads"
        if (alone && OutsideApp.asked(q)) return "OutsideApp"
        if (alone && Clarity.asked(q) != null) return "Clarity"
        if (alone && WordFit.asked(q) != null) return "WordFit"
        if (alone && AskedAgain.asked(q)) return "AskedAgain"
        if (alone && FigureFirst.asked(q) != null) return "FigureFirst"
        if (alone && (WrongThing.asked(q) != null || WrongThing.objected(q))) return "WrongThing"
        if (alone && ArmHabits.asked(q)) return "ArmHabits"
        if (alone && MorningSense.asked(q) != null) return "MorningSense"
        if (alone && HonestStars.asked(q) != null) return "HonestStars"
        if (alone && TalkHours.asked(q) != null) return "TalkHours"
        if (alone && MorningAsks.asked(q) != null) return "MorningAsks"
        if (alone && TurnDowns.asked(q) != null) return "TurnDowns"
        if (alone && TopicLength.asked(q) != null) return "TopicLength"
        if (alone && OutlookCheck.asked(q)) return "OutlookCheck"
        if (alone && UsualIndex.asked(q) != null) return "UsualIndex"
        if (alone && NewsMoves.asked(q) != null) return "NewsMoves"
        if (alone && TaxRecords.exportAsked(q)) return "TaxExport"
        if (alone && Learnings.asked(q) != null) return "Learnings"
        if (alone && !understood && Learnings.undoAsked(q)) return "LearningsUndo"
        if (alone && PreMarket.asked(q)) return "PreMarket"
        if (alone && Headroom.asked(q) != null) return "Headroom"
        if (alone && ArmFit.asked(q)) return "ArmFit"
        if (alone && WeakLink.asked(q)) return "WeakLink"
        if (alone && ArmDay.asked(q) != null) return "ArmDay"
        if (alone && NetLean.asked(q)) return "NetLean"
        if (alone && ExpiryEve.asked(q)) return "ExpiryEve"
        if (alone && BeforeTomorrow.asked(q)) return "BeforeTomorrow"
        if (alone && BotTrades.asked(q) != null) return "BotTrades"
        if (alone && SwitchOff.asked(q) != null) return "SwitchOff"
        if (alone && SaidAbout.asked(q) != null) return "SaidAbout"
        if (alone && WeekAhead.asked(q) != null) return "WeekAhead"
        if (alone && ZerodhaSession.asked(q) != null) return "ZerodhaSession"
        if (alone && OrderWhy.asked(q) != null) return "OrderWhy"
        if (alone && RelayHealth.asked(q) != null) return "RelayHealth"
        if (alone && StreamHealth.asked(q)) return "StreamHealth"
        if (alone && BatteryUse.asked(q)) return "BatteryUse"
        if (alone && WatchAsk.asked(q) != null) return "WatchAsk"
        if (alone && Tour.asked(q)) return "Tour"
        if (alone && DataAge.asked(q)) return "DataAge"
        if (alone && Honest.asked(q) != null) return "Honest"
        // (The hub's Thinking falls through to SelfWhy when no reason was written and SelfWhy takes the words.)
        if (alone && Thinking.asked(q) != null) return "Thinking"
        if (alone && Consistency.asked(q)) return "Consistency"
        if (alone && CoPilot.asked(q)) return "CoPilot"
        if (alone && SinceMorning.asked(q)) return "SinceMorning"
        if (alone && ExpiryPin.asked(q) != null) return "ExpiryPin"
        if (alone && ChainDrift.asked(q) != null) return "ChainDrift"
        if (alone && ChainIntel.asked(q) != null) return "ChainIntel"
        if (alone && DayClock.asked(q) != null) return "DayClock"
        if (alone && GapRecord.asked(q) != null) return "GapRecord"
        if (alone && RangeBreaks.asked(q) != null) return "RangeBreaks"
        if (alone && PriorDay.asked(q) != null) return "PriorDay"
        if (alone && LastHour.asked(q) != null) return "LastHour"
        if (alone && InsideDays.asked(q) != null) return "InsideDays"
        if (alone && FirstMove.asked(q) != null) return "FirstMove"
        if (alone && VixNext.asked(q) != null) return "VixNext"
        if (alone && SplitDays.asked(q) != null) return "SplitDays"
        if (alone && RoundCloses.asked(q) != null) return "RoundCloses"
        if (alone && MonthTurns.asked(q) != null) return "MonthTurns"
        if (alone && LunchRange.asked(q) != null) return "LunchRange"
        if (alone && OpenHighLow.asked(q) != null) return "OpenHighLow"
        if (alone && BigCandles.asked(q) != null) return "BigCandles"
        if (alone && ExtremeCloses.asked(q) != null) return "ExtremeCloses"
        if (alone && Weekdays.asked(q) != null) return "Weekdays"
        if (alone && DayCompare.asked(q) != null) return "DayCompare"
        if (alone && LikeToday.asked(q)) return "LikeToday"
        if (alone && Structure.asked(q) != null) return "Structure"
        if (alone && MindChange.asked(q)) return "MindChange"
        if (alone && Breadth.asked(q) != null) return "Breadth"
        if (alone && TradeCase.asked(q)) return "TradeCase"
        if (alone && Scenarios.asked(q) != null) return "Scenarios"
        if (alone && Causes.asked(q) != null) return "Causes"
        if (alone && Agenda.asked(q)) return "Agenda"
        if (alone && Improve.asked(q)) return "Improve"
        if (ReminderBook.listAsked(q) || ReminderBook.cancelOne(q) != null) return "ReminderBook"
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
        if (alone && NewsDesk.asked(q) != null) return "NewsDesk"
        if (plain && Chat.smallTalk(q, 0) != null) return "Chat"
        if (Memory.toKeep(q) != null || plain && (AboutBoss.fact(q) != null || AboutBoss.forgetAsked(q) != null) || AboutBoss.knowAsked(q) ||
            Memory.recallAsked(q) || Memory.forgetAsked(q)) return "AboutBoss"
        if (plain && AutoStop.read(q) != null) return "AutoStop"
        if (Goals.asked(q) || Goals.clearAsked(q)) return "Goals"
        if (plain && SelfWhy.asked(q)) return "SelfWhy"
        if (Vetting.asked(q)) return "Vetting"
        if (SelfCalibration.asked(q)) return "SelfCalibration"
        if (Lessons.asked(q)) return "Lessons"
        if (Sources.asked(q)) return "Sources"
        if (!understood && Habits.asked(q)) return "Habits"
        if (!understood && p.order == null && (p.command == null || p.command?.kind == Command.Kind.STOP_ONE)) Intents.quick(q)?.let { return feature(it, understood = true) }
        if (plain && (Topic.ACCOUNT !in p.topics || !Regex("(?i)\\b(my|mine|our|me|i)\\b").containsMatchIn(q)) && Topic.EXPLAIN !in p.topics &&
            Glossary.explain(q) != null) return "Glossary"
        if (plain && Topic.ACCOUNT !in p.topics && !Regex("(?i)\\b(i|me|my|mine)\\b").containsMatchIn(q) && OptionQuote.asked(p.text.ifBlank { q }) != null) return "OptionQuote"
        if (Topic.BACKTEST in p.topics) return "Backtest"
        if (Topic.ACCOUNT in p.topics) return "Account:" + AppAnswers.sections(q).joinToString("+")
        if (Topic.COMMAND in p.topics || Topic.ORDER in p.topics) return "Act"
        if (Topic.SUGGEST in p.topics) return "Suggest"
        if (p.pattern != null && Topic.EXPLAIN in p.topics) return "PatternExpert"
        if (Topic.TRADE_CHECK in p.topics) return "TradeCheck"
        if (plain && Regex("(?i)\\bsolo\\b").containsMatchIn(q)) return "Solo"
        // IraHub.reasoned, in its order.
        val t = p.text.ifBlank { q }
        if (MarketStory.asked(t) != null) return "MarketStory"
        if (SharpMove.asked(t) != null) return "SharpMove"
        if (MarketMemory.asked(t) != null) return "MarketMemory"
        if (ExpiryDay.asked(t)) return "ExpiryDay"
        if (Topic.WHY in p.topics && !Regex("(?i)\\bhow much\\b").containsMatchIn(t)) return "Why"
        if (Payoff.asked(t) != null) return "Payoff"
        if (VixRank.asked(t)) return "VixRank"
        if (SinceLast.asked(t)) return "SinceLast"
        if (Briefing.asked(t)) return "Briefing"
        if (Realised.asked(t)) return "Realised"
        if (Together.asked(t)) return "Together"
        if (Compare.asked(t)) return "Compare"
        if (Regex("(?i)\\b(i|me|my|we|our)\\b").containsMatchIn(t)) return "Market"
        if (Odds.asked(t) != null) return "Odds"
        if (ExpectedRange.asked(t)) return "ExpectedRange"
        if (Moves.asked(t) != null) return "Moves"
        if (Lookback.prevAsked(t) || Lookback.time(t) != null) return "Lookback"
        if (BigPicture.asked(t)) return "BigPicture"
        if (LevelInfo.asked(t) != null) return "LevelInfo"
        if (OpeningRange.asked(t)) return "OpeningRange"
        if (PeriodMove.asked(t) != null) return "PeriodMove"
        if (Momentum.asked(t)) return "Momentum"
        if (Pivots.asked(t)) return "Pivots"
        if (DayStory.asked(t)) return "DayStory"
        if (Gap.asked(t)) return "Gap"
        if (Streak.asked(t)) return "Streak"
        // (Ira.answer's own: what Jarvis can do, the voice - AppAnswers.help; round 13.)
        return if (Topic.OFF_TOPIC in p.topics) "Missed" else if (Topic.HELP in p.topics) "Help" else "Market"
    }

    private val ACCOUNT_REVIEW = "Account:REVIEW"

    /** The new families (DayJournal to AboutBoss; round 7: Scenarios, Structure, PositionHealth, Routine, Corrections; round 8: NewsDesk, Consistency, PatternCalls, BotHealth; round 9: PreMarket, NewsMoves, Hearing, CoPilot, Learnings), as Boss says them - English, Hinglish, the recognizer's spellings. */
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
        // ---- ExpiryPin: past Nifty expiries' settle against max pain and the biggest OI strike (round 22) ----
        "how often does nifty close near max pain on expiry" to "ExpiryPin", "does nifty pin to max pain on expiry day" to "ExpiryPin",
        "expiry pin record" to "ExpiryPin", "how often does nifty settle at the biggest oi strike on expiry" to "ExpiryPin",
        "does max pain work on expiry days" to "ExpiryPin", "expiry pe nifty max pain ke paas band hota hai kya" to "ExpiryPin",
        // ---- ChainDrift: max pain and the biggest call / put OI through the day (round 12) ----
        "how has max pain moved today" to "ChainDrift", "has max pain shifted since morning" to "ChainDrift", "max pain drift" to "ChainDrift",
        "banknifty max pain through the day" to "ChainDrift", "max pain kitna shift hua" to "ChainDrift", "is the call wall shifting" to "ChainDrift",
        "where is the put wall" to "ChainDrift", "has the biggest call oi moved" to "ChainDrift", "how has the chain drifted today" to "ChainDrift",
        "max pain and call wall since the open" to "ChainDrift",
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
        "how are you getting better" to "Improve", "what are you improving" to "Improve", "what have you learned this week" to "Learnings",
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
        // ---- TaxRecords: the financial year's facts, and its trades exported (on Boss's yes) ----
        "what's my f&o turnover this year" to "Account:TAX", "my fno turnover" to "Account:TAX", "my tax summary" to "Account:TAX",
        "mera turnover kitna hai" to "Account:TAX", "my p&l for last financial year" to "Account:TAX",
        "export my trades for tax" to "TaxExport", "download my trades as csv for my ca" to "TaxExport", "tax ke liye trades export karo" to "TaxExport",
        // ---- The market's own week (never Boss's history) ----
        "how was the market this week" to "PeriodMove", "how was nifty this week" to "PeriodMove",

        // ==== Round 7 (5 Oct): the families added since the routing audit, and their neighbours ====
        // ---- Scenarios: a what-if about an index or India VIX (English, Hinglish, slips) ----
        "what if nifty opens 1% down" to "Scenarios", "what if nifty opens 1% down tomorrow" to "Scenarios",
        "what if banknifty falls 500 points" to "Scenarios", "what if vix goes to 20" to "Scenarios",
        "what if vix jumps 10%" to "Scenarios", "suppose nifty drops 2%" to "Scenarios",
        "let's say nifty gaps up 100 points" to "Scenarios", "what happens if nifty breaks 25000" to "Scenarios",
        "what if nifty hits 24000" to "Scenarios", "what if nifty open 1 percent down" to "Scenarios",
        "what if nifty fall 200 points" to "Scenarios", "what would happen if banknifty crashes 3%" to "Scenarios",
        "what happens if sensex falls 1000 points" to "Scenarios", "imagine nifty rallies 2%" to "Scenarios",
        "what if india vix spikes to 25" to "Scenarios", "scenario nifty down 2 percent" to "Scenarios",
        "what if nifty moves 300 points" to "Scenarios", "what if nifty swings 300 points" to "Scenarios",
        "what if nifty gaps down 200 points tomorrow" to "Scenarios", "suppose banknifty opens 1% up" to "Scenarios",
        "what if sensex rallies 1000 points" to "Scenarios", "what happens if nifty goes below 24000" to "Scenarios",
        "what if nifty crosses 26000" to "Scenarios", "what if nifty dips 150 points" to "Scenarios",
        "wat if nifty falls 1%" to "Scenarios", "wht if nifty drops 2%" to "Scenarios", "what will happen if nifty falls 2%" to "Scenarios",
        "what if finnifty falls 1%" to "Scenarios", "what if banknifty opens gap down 300 points" to "Scenarios",
        "what if nifty goes up 1% today" to "Scenarios", "agar nifty 1% gir jaye to kya hoga" to "Scenarios",
        "agar nifty kal 1% neeche khule to" to "Scenarios", "nifty 1% down khula to kya hoga" to "Scenarios",
        "agar banknifty 500 points gira to kya hoga" to "Scenarios", "agar vix 20 ho jaye to kya hoga" to "Scenarios",
        "agar nifty 25000 todta hai to kya hoga" to "Scenarios", "agar nifty 2% chadh jaye to kya hoga" to "Scenarios",
        "agar sensex 1000 points gir jaye to kya hoga" to "Scenarios", "agar nifty kal gap down 1% khule to kya hoga" to "Scenarios",
        // ---- Its neighbours: Boss's own book at a move stays the account's (Exposure.moveAsked), as does his replay ----
        "what happens to my p&l if nifty moves 100 points" to "Account:MOVE",
        "what if nifty falls 100 points what happens to my positions" to "Account:MOVE",
        "how much do i lose if nifty falls 1%" to "Account:MOVE", "if nifty drops 200 points what happens to my p&l" to "Account:MOVE",
        "what's my exposure if banknifty moves 500 points" to "Account:MOVE",
        "what if i had taken that trade" to "Account:WHATIF",
        // ---- DayClock: when the high and low usually come, by now, and the busiest half hour (round 13) ----
        "when does nifty usually make its high" to "DayClock", "what time does banknifty normally make its low" to "DayClock",
        "when is the day's high usually made" to "DayClock", "nifty ka high kab banta hai" to "DayClock",
        "is the low of the day usually in by now" to "DayClock", "how often is the high already made by this time" to "DayClock",
        "which half hour moves the most" to "DayClock", "is the lunch hour usually quiet" to "DayClock",
        "busiest time of the day for banknifty" to "DayClock", "nifty day clock" to "DayClock",
        // ---- GapRecord: how the index's past gaps played out - filled by a time, never came back (round 14) ----
        "when nifty gaps up over 0.5% how often does it fill the gap by 11" to "GapRecord", "do gap downs usually fill" to "GapRecord",
        "what usually happens after a gap up" to "GapRecord", "gap fill rate for banknifty" to "GapRecord",
        "how often does a gap like today's fill" to "GapRecord", "nifty gap kitni baar bharta hai" to "GapRecord",
        "how often does sensex fill its gap by noon" to "GapRecord", "nifty gap fill record" to "GapRecord",
        // ---- MindChange: what would make Jarvis's last structure read no longer true, and whether it has since (round 11) ----
        "what would change your mind" to "MindChange", "what would make you wrong" to "MindChange",
        "what would invalidate that read" to "MindChange", "when would that stop being true" to "MindChange",
        "where would you be wrong" to "MindChange", "what level would invalidate your read" to "MindChange",
        "what would it take to change your mind" to "MindChange", "aapka view kab badlega" to "MindChange",
        "ye kab galat hoga" to "MindChange", "what would change your read on banknifty" to "MindChange",
        // ---- RangeBreaks: the opening-range breakout record (round 16) ----
        "when nifty breaks its first 15 minute range how often does it hold by the close" to "RangeBreaks",
        "do opening range breakouts usually hold" to "RangeBreaks", "how often do orb breakouts fail" to "RangeBreaks",
        "opening range breakout record for banknifty" to "RangeBreaks", "how often does nifty stay inside the opening range all day" to "RangeBreaks",
        "opening range todne ke baad kitni baar tikta hai" to "RangeBreaks", "how often does the first hour range break hold" to "RangeBreaks",
        // ---- PriorDay: the prior day's high and low record (round 17) ----
        "when nifty takes out yesterday's high in the first hour how often does it close above it" to "PriorDay",
        "how often does nifty break the previous day's high" to "PriorDay", "pdh pdl record" to "PriorDay",
        "does banknifty usually hold below yesterday's low after breaking it" to "PriorDay",
        "how often does nifty take out the prior day high or low" to "PriorDay", "prior day high low stats for sensex" to "PriorDay",
        "kal ka high todne ke baad kitni baar upar band hota hai" to "PriorDay",
        // ---- LastHour: the last hour against the day's direction (round 18) ----
        "how often does the last hour continue the day's direction" to "LastHour", "does nifty usually reverse in the last hour" to "LastHour",
        "on up days does banknifty usually extend in the closing hour" to "LastHour", "last hour record for sensex" to "LastHour",
        "how often does the final hour reverse on down days" to "LastHour", "aakhri ghante mein kitni baar palat jata hai" to "LastHour",
        // ---- InsideDays: what followed inside days and NR7 days (round 19) ----
        "after an inside day how often does nifty's range expand the next day" to "InsideDays", "how often does an nr7 day lead to a bigger day" to "InsideDays",
        "inside day record for banknifty" to "InsideDays", "do narrow range days usually break out the next day" to "InsideDays",
        "how often do inside days and nr7 days expand for sensex" to "InsideDays", "inside day ke baad kitni baar bada move aata hai" to "InsideDays",
        // ---- FirstMove: how often the first 30 minutes' direction matched the day's close (round 20) ----
        "how often does the first 30 minutes direction match the day's close" to "FirstMove", "does the opening move usually decide the day" to "FirstMove",
        "when nifty is up in the first half hour how often does it close up" to "FirstMove", "first move record for banknifty" to "FirstMove",
        "does the first hour usually set the direction of the day for sensex" to "FirstMove", "pehle aadhe ghante ki direction se din ka close kitni baar milta hai" to "FirstMove",
        // ---- VixNext: India VIX's change against the index's next day (round 21) ----
        "when vix jumps 5% how big is the next day" to "VixNext", "after a vix spike how much does nifty move the next day" to "VixNext",
        "when india vix falls 5% is the next session quieter" to "VixNext", "vix spike record for banknifty" to "VixNext",
        "when vix rises 8 percent how wide is sensex's range the next day" to "VixNext", "jab vix 5% uchalta hai to agle din nifty kitna chalta hai" to "VixNext",
        // ---- SplitDays: two indices on opposite sides of their previous closes, and the day after (round 23) ----
        "how often do nifty and banknifty end opposite ways" to "SplitDays", "nifty banknifty divergence stats" to "SplitDays",
        "what happens the day after nifty and banknifty diverge" to "SplitDays", "how often does banknifty go the other way from nifty" to "SplitDays",
        "how often do sensex and finnifty move in opposite directions" to "SplitDays", "nifty aur banknifty kitni baar alag disha mein jaate hain" to "SplitDays",
        "how often do the indices diverge" to "SplitDays", "split day record for nifty and banknifty" to "SplitDays",
        // ---- RoundCloses: past closes beside round numbers against chance (round 24) ----
        "does nifty close near round numbers" to "RoundCloses", "are round numbers a magnet for nifty" to "RoundCloses",
        "how often does banknifty end near a round thousand" to "RoundCloses", "round number record for sensex" to "RoundCloses",
        "do nifty closes cluster at round hundreds" to "RoundCloses", "nifty gol figure ke paas kitni baar band hota hai" to "RoundCloses",
        "how often does finnifty close near round 500 levels" to "RoundCloses", "are psychological levels a magnet for nifty" to "RoundCloses",
        // ---- MonthTurns: a month's first and last 3 trading days against the rest (round 25) ----
        "how does nifty do at the beginning of the month" to "MonthTurns", "is there a turn of the month effect" to "MonthTurns",
        "how are the last few days of the month for banknifty" to "MonthTurns", "month end record" to "MonthTurns",
        "mahine ki shuruaat mein nifty kaisa chalta hai" to "MonthTurns", "does sensex rally in the first 3 trading days of the month" to "MonthTurns",
        "month beginning vs month end record for finnifty" to "MonthTurns", "mahine ke aakhri dino mein banknifty kaisa rehta hai" to "MonthTurns",
        // ---- LunchRange: the 12:00-13:30 window against the rest, and the afternoon's break of it (round 26) ----
        "does nifty break the lunch range" to "LunchRange", "lunch range breakout record" to "LunchRange",
        "is the lunch lull real" to "LunchRange", "how wide is banknifty's lunch range against the morning" to "LunchRange",
        "lunch ki range todne ke baad nifty kya karta hai" to "LunchRange", "how often does the afternoon break below the lunch range" to "LunchRange",
        "midday range record for finnifty" to "LunchRange", "does sensex hold a break above the lunch box" to "LunchRange",
        // ---- OpenHighLow: the days whose open was the day's high or low, and how they closed (round 27) ----
        "how often is the open the high of the day" to "OpenHighLow", "open = low days record" to "OpenHighLow",
        "how do open high days close for banknifty" to "OpenHighLow", "open low wale din nifty kaisa chalta hai" to "OpenHighLow",
        "open high open low record" to "OpenHighLow", "how often has nifty opened at the high" to "OpenHighLow",
        "how often does sensex open at the day's high or low" to "OpenHighLow", "o=h o=l record for finnifty" to "OpenHighLow",
        // ---- BigCandles: what followed a big 5-minute candle in the first hour (round 28) ----
        "after a big 5-minute candle in the first hour does the day continue" to "BigCandles", "big candle record for banknifty" to "BigCandles",
        "how often does a big green candle in the morning follow through" to "BigCandles", "subah bada candle aane ke baad nifty kya karta hai" to "BigCandles",
        "what happens after a 0.4% 5-minute candle" to "BigCandles", "large first hour candles record for finnifty" to "BigCandles",
        // ---- ExtremeCloses: closes at the ends of the day's range and what the next day did (round 29) ----
        "how often does nifty close near its high" to "ExtremeCloses", "what happens the day after banknifty closes at the low" to "ExtremeCloses",
        "strong close record for sensex" to "ExtremeCloses", "high pe close hone ke baad agle din kya hota hai" to "ExtremeCloses",
        "after a weak close what does finnifty do next day" to "ExtremeCloses", "how often are there extreme closes" to "ExtremeCloses",
        // ---- Weekdays: each weekday's record, and expiry days against the rest (round 15) ----
        "are mondays more volatile" to "Weekdays", "which day of the week moves the most" to "Weekdays",
        "how does nifty usually do on fridays" to "Weekdays", "weekday record for banknifty" to "Weekdays",
        "monday ko nifty kaisa chalta hai" to "Weekdays", "are expiry days more volatile" to "Weekdays",
        "expiry day range vs normal days" to "Weekdays", "kis din market sabse zyada hilta hai" to "Weekdays",
        "which weekday is the most volatile" to "Weekdays", "expiry ke din range zyada hota hai kya" to "Weekdays",
        // ---- BotTrades: today's arm trades explained, signal to exit, against their rules (reasoning round 13) ----
        "explain my bots trades today" to "BotTrades", "explain my bots' trades" to "BotTrades", "explain today's bot trades" to "BotTrades",
        "walk me through my bots' trades" to "BotTrades", "why did orb take that trade" to "BotTrades", "why did my bots trade today" to "BotTrades",
        "why did the liquidity bot exit" to "BotTrades", "what did my bots do today" to "BotTrades", "what trades did orb take" to "BotTrades",
        "did my bots follow their rules" to "BotTrades", "did orb stick to its rules today" to "BotTrades", "any contradictions in my bots" to "BotTrades",
        "did my bots take opposite sides" to "BotTrades", "my bots' trades today" to "BotTrades", "mere bots ne aaj kya kiya" to "BotTrades",
        "orb ne trade kyun liya" to "BotTrades", "bots ke trades samjhao" to "BotTrades", "break down the range fade trade" to "BotTrades",
        // ---- WeakLink: what most often went wrong in the arms' last paper trades (reasoning round 20) ----
        "what's the weakest link in my setup" to "WeakLink", "my weak spots" to "WeakLink", "what keeps going wrong with my bots" to "WeakLink",
        "where do my trades go wrong" to "WeakLink", "mere bots mein aksar kya galat hota hai" to "WeakLink",
        // ---- ArmFit: each armed arm's backtest and paper days split on today's bands (reasoning round 19) ----
        "which of my arms suits today" to "ArmFit", "which arm suits today" to "ArmFit", "which bot fits today's conditions" to "ArmFit",
        "how do my arms do on days like today" to "ArmFit", "my bots on days like today" to "ArmFit", "aaj jaise din par mere arms" to "ArmFit",
        // ---- LikeToday: today's start against every past session's, and how the like days ended (reasoning round 18) ----
        "is today like any past day" to "LikeToday", "has there been a day like today" to "LikeToday",
        "how did days like today end" to "LikeToday", "similar days to today for banknifty" to "LikeToday",
        "aaj jaisa din pehle kab tha" to "LikeToday", "is today similar to any earlier day" to "LikeToday",
        // ---- DayCompare: today set against an earlier session, measure against measure (reasoning round 12) ----
        "how is today different from yesterday" to "DayCompare", "how is nifty today different from yesterday" to "DayCompare",
        "compare today with yesterday" to "DayCompare", "compare yesterday and today" to "DayCompare",
        "today vs yesterday" to "DayCompare", "banknifty today vs yesterday" to "DayCompare",
        "is today more like a trend day than yesterday" to "DayCompare", "is today more like a trend day or a range day than yesterday" to "DayCompare",
        "how is today different from last thursday" to "DayCompare", "compare today with last friday" to "DayCompare",
        "how does today compare with yesterday" to "DayCompare", "what's the difference between today and yesterday" to "DayCompare",
        "is today like yesterday" to "DayCompare", "aaj aur kal mein kya fark hai" to "DayCompare", "aaj kal se kaise alag hai" to "DayCompare",
        "is today a trend day like yesterday" to "DayCompare", "how is today different from the day before yesterday" to "DayCompare",
        // ---- Structure: today's intraday structure - higher highs, swing levels, trend or range so far ----
        "what's the structure today" to "Structure", "what's the structure" to "Structure", "market structure" to "Structure",
        "what is the market structure today" to "Structure", "nifty structure today" to "Structure",
        "banknifty ka structure kya hai" to "Structure", "aaj ka structure kya hai" to "Structure", "structure batao" to "Structure",
        "nifty ka structure batao" to "Structure", "is nifty making higher highs" to "Structure",
        "is banknifty making lower lows" to "Structure", "higher highs or lower lows" to "Structure", "lower highs today" to "Structure",
        "where are the swing levels" to "Structure", "swing highs and lows" to "Structure", "nifty swing levels" to "Structure",
        "swing levels for banknifty" to "Structure", "swing high and low today" to "Structure", "trend or range so far" to "Structure",
        "is it a trend day" to "Structure", "is today a range day" to "Structure", "trend day or range day" to "Structure",
        "trending or sideways today" to "Structure", "is nifty trending or ranging" to "Structure",
        "is the market trending or sideways" to "Structure", "trending or range bound" to "Structure", "trend ya range" to "Structure",
        "trend hai ya range" to "Structure", "range ya trend" to "Structure", "aaj trend day hai ya range" to "Structure",
        "what's the strucure today" to "Structure", "whats the structre" to "Structure", "what's the intraday structure" to "Structure",
        // ---- Its neighbours: the opening range, the day's story, another span's structure ----
        "how is the opening range" to "OpeningRange", "did nifty break the opening range" to "OpeningRange",
        "what's the opening range" to "Glossary",
        "how did the day go for nifty" to "DayStory", "what's the day's story" to "DayStory", "how has the day gone" to "DayStory",
        "what happened today" to "MarketStory",
        "what was the structure yesterday" to "Account:HISTORY", "weekly structure" to "Account:HISTORY",
        // ---- PositionHealth: each open position's health (the account's HEALTH), the slips included ----
        "check my positions" to "Account:HEALTH", "check my positions please" to "Account:HEALTH",
        "kya meri positions theek hain" to "Account:HEALTH", "meri positions theek hai kya" to "Account:HEALTH",
        "meri positions theek hain na" to "Account:HEALTH", "are my positions okay" to "Account:HEALTH",
        "are my positions fine" to "Account:HEALTH", "position health" to "Account:HEALTH", "positions health check" to "Account:HEALTH",
        "how healthy are my positions" to "Account:HEALTH", "health check on my positions" to "Account:HEALTH",
        "meri positions ka haal" to "Account:HEALTH", "check on my open positions" to "Account:HEALTH",
        "are all my positions safe" to "Account:HEALTH", "check my position" to "Account:HEALTH", "chek my positions" to "Account:HEALTH",
        "check my postions" to "Account:HEALTH", "chk my positions" to "Account:HEALTH", "chek my postions" to "Account:HEALTH",
        // ---- NeedsTrue: what has to be true for an open position (the account's NEED) ----
        "for my 24500 put to work, what needs to happen" to "Account:NEED", "what has to happen for my call to pay off" to "Account:NEED",
        "what would have to be true for my position to make money" to "Account:NEED", "where is my breakeven" to "Account:NEED",
        "how far is my breakeven" to "Account:NEED", "what does my 24500 put need" to "Account:NEED",
        "meri put ke liye kya hona chahiye" to "Account:NEED", "what needs to happen for my banknifty call" to "Account:NEED",
        "what's my breakeven on the 24500 pe" to "Account:NEED", "for my short call to make money what has to happen" to "Account:NEED",
        // ---- Its neighbours: the positions shown, and ranked ----
        "show my positions" to "Account:POSITIONS", "show positions" to "Account:POSITIONS", "what are my positions" to "Account:POSITIONS",
        "my positions" to "Account:POSITIONS", "how are my positions" to "Account:POSITIONS",
        "how are my positions doing" to "Account:POSITIONS", "meri positions kaisi hain" to "Account:POSITIONS",
        "rank my positions" to "Account:RANK", "which of my positions is losing most" to "Account:RANK",
        // ---- Routine: what Boss usually asks Jarvis, and forgetting it - as said by him ----
        "what do i usually ask" to "Routine", "what do i usually ask you" to "Routine", "what do i usually ask about" to "Routine",
        "what do i ask you the most" to "Routine", "what do i normally ask" to "Routine", "jarvis what do i usually ask" to "Routine",
        "what's my routine" to "Routine", "what is my routine" to "Routine", "what is my usual routine" to "Routine",
        "what are my habits with you" to "Routine", "do i have a routine" to "Routine",
        "what have you noticed about my routine" to "Routine", "mera routine kya hai" to "Routine",
        "main usually kya poochta hoon" to "Routine", "mai aksar kya puchta hu" to "Routine", "forget my routine" to "Routine",
        "forget the routine" to "Routine", "clear my routine" to "Routine", "drop my habits" to "Routine", "clear the habits" to "Routine",
        // ---- Its neighbours: "the usual" (Habits) and his trading habits (the review) ----
        "the usual" to "Habits", "my usual" to "Habits", "my usual please" to "Habits", "same as always" to "Habits",
        "what are my trading habits" to ACCOUNT_REVIEW, "what are my bad habits" to ACCOUNT_REVIEW,
        "review my habits" to ACCOUNT_REVIEW, "my habits" to ACCOUNT_REVIEW,
        // ---- Corrections: the wordings Jarvis learned from Boss, listed or forgotten ----
        "what words have you learned" to "Corrections", "which words have you learned" to "Corrections",
        "what words did you learn from me" to "Corrections", "what words have you learnt" to "Corrections",
        "what wordings have you learned" to "Corrections", "which phrases did you learn" to "Corrections",
        "list the words you learned" to "Corrections", "tell me the words you learned" to "Corrections",
        "show me the words you have learned" to "Corrections", "your learned words" to "Corrections", "learned words" to "Corrections",
        "forget the word nifty fifty" to "Corrections", "forget the word bank nifty" to "Corrections",
        "forget the word teeta" to "Corrections", "unlearn the word teeta" to "Corrections", "drop the word nifty fifty" to "Corrections",
        "forget the phrase market kaisa" to "Corrections", "forget the wording kya scene" to "Corrections",
        // ---- Their neighbours: what Jarvis learned of the market, and of Boss ----
        "what did you learn" to "Lessons", "what did you learn today" to "Lessons",
        "forget what i told you" to "AboutBoss", "what have you learned about me" to "AboutBoss",

        // ==== Round 8 (5 Oct): last round's open items, and NewsDesk, Consistency, PatternCalls and BotHealth ====
        // ---- The market's "we" is the structure; Boss's "we" stays his book ----
        "are we making higher highs" to "Structure", "are we making lower lows" to "Structure",
        "are we making higher lows today" to "Structure", "r we making lower highs" to "Structure",
        "are we trending or ranging" to "Structure", "are we in profit" to "Account:PNL",
        // ---- A position ranked with no "my": the open positions are Boss's (the account; locked-phone rules) ----
        "which position is losing the most" to "Account:RANK", "which position is losing most" to "Account:RANK",
        "worst position" to "Account:RANK", "best position" to "Account:RANK", "worst open position" to "Account:RANK",
        "which position is winning the most" to "Account:RANK", "biggest losing position" to "Account:RANK",
        "what's the best position to take" to "Account:POSITIONS", "which positions are losing most in the market" to "Account:POSITIONS",
        // ---- "My trading routine" is his routine with Jarvis; his trading habits stay the review ----
        "what is my trading routine" to "Routine", "what's my trading routine" to "Routine", "whats my trading routine" to "Routine",
        // ---- NewsDesk: the main news, news on a sector or theme, the news that moved the market ----
        "what's the main news today" to "NewsDesk", "what's the main news" to "NewsDesk", "whats the main news today" to "NewsDesk",
        "wats the main news" to "NewsDesk", "main news" to "NewsDesk", "what's the news today" to "NewsDesk",
        "top headlines today" to "NewsDesk", "todays top headlines" to "NewsDesk", "top stories today" to "NewsDesk",
        "major headlines" to "NewsDesk", "key news today" to "NewsDesk", "biggest story today" to "NewsDesk",
        "what's the big news today" to "NewsDesk", "important news today" to "NewsDesk", "what's in the news" to "NewsDesk",
        "what's making the news" to "NewsDesk", "what is in the headlines" to "NewsDesk",
        "any news on banks" to "NewsDesk", "banks pe koi news" to "NewsDesk", "banking sector news" to "NewsDesk",
        "any rbi news" to "NewsDesk", "news on the fed" to "NewsDesk", "any news on it stocks" to "NewsDesk",
        "news about tcs" to "NewsDesk", "any news on infosys" to "NewsDesk", "any news on pharma" to "NewsDesk",
        "any news on auto stocks" to "NewsDesk", "any news on metals" to "NewsDesk", "any news on oil" to "NewsDesk",
        "headlines about inflation" to "NewsDesk", "any earnings news" to "NewsDesk", "news on fii flows" to "NewsDesk",
        "rupee news" to "NewsDesk", "budget news" to "NewsDesk", "any news on the budget" to "NewsDesk",
        "what news moved the market" to "NewsDesk", "what news moved the market today" to "NewsDesk",
        "did the news move nifty" to "NewsDesk", "which headline moved banknifty" to "NewsDesk",
        "what news drove banknifty today" to "NewsDesk", "which news pushed the market" to "NewsDesk",
        "did any news move the market" to "NewsDesk", "news behind today's fall" to "NewsDesk",
        "aaj ki main news kya hai" to "NewsDesk", "koi badi news hai aaj" to "NewsDesk", "koi zaroori news" to "NewsDesk",
        "aaj ki badi khabar" to "NewsDesk",
        // ---- Its neighbours: the plain news (Ira's), a sharp move explained, why the market fell ----
        "any news" to "Market", "what's the news" to "Market", "latest news" to "Market", "news on nifty" to "Market",
        "banknifty news" to "Market", "any news behind this sudden fall" to "SharpMove", "explain this move" to "SharpMove",
        "what coincided with this drop" to "SharpMove", "why did nifty suddenly fall" to "SharpMove",
        "why did the market drop today" to "Causes",
        // ---- Causes: why the market moved, the candidates weighed by evidence (reasoning round 10) ----
        "why did nifty fall" to "Causes", "why is the market down" to "Causes", "why has banknifty fallen so much" to "Causes",
        "what caused the fall today" to "Causes", "what's behind the rally in banknifty" to "Causes", "reason for today's fall" to "Causes",
        "nifty aaj kyun gira" to "Causes", "why is sensex rising today" to "Causes", "why is the market so weak today" to "Causes",
        "what made nifty fall" to "Causes",
        // ---- Consistency: facts pulling different ways, and Boss's words against his day ----
        "any contradictions" to "Consistency", "any contradictions today" to "Consistency", "jarvis any contradictions" to "Consistency",
        "is there any contradiction" to "Consistency", "any mixed signals today" to "Consistency", "any conflicts in the data" to "Consistency",
        "any inconsistency in the data" to "Consistency", "do the facts agree" to "Consistency", "do the signals agree" to "Consistency",
        "do the numbers add up" to "Consistency", "do your numbers agree with each other" to "Consistency",
        "are the numbers consistent" to "Consistency", "are the facts conflicting" to "Consistency",
        "what's pulling different ways" to "Consistency", "anything pulling opposite ways" to "Consistency",
        "check yourself for contradictions" to "Consistency", "consistency check" to "Consistency", "contradiction check please" to "Consistency",
        "am i going against my own rules" to "Consistency", "have i been going against my rules" to "Consistency",
        "am i breaking my rules today" to "Consistency", "was i keeping my rules today" to "Consistency", "am i following my rules" to "Consistency",
        // (Its neighbours, the case both ways and Boss's own notes, are in the pairs below and round 6's lines.)
        // ---- PatternCalls: how the patterns Jarvis told of played out ----
        "which patterns work on nifty" to "PatternCalls", "which patterns worked on banknifty" to "PatternCalls",
        "which candle patterns work on nifty" to "PatternCalls", "which patterns work on banknifty 15 minute" to "PatternCalls",
        "what patterns work" to "PatternCalls", "what patterns are working" to "PatternCalls", "which patterns pay off" to "PatternCalls",
        "which chart patterns held up" to "PatternCalls", "what patterns did well this week" to "PatternCalls",
        "how good are your pattern calls" to "PatternCalls", "how accurate are your pattern calls" to "PatternCalls",
        "how reliable are your pattern calls" to "PatternCalls", "how right have your pattern calls been" to "PatternCalls",
        "your pattern hit rate" to "PatternCalls", "your pattern record" to "PatternCalls", "pattern track record" to "PatternCalls",
        "your pattern calls track record" to "PatternCalls", "jarvis pattern accuracy" to "PatternCalls",
        "kaun se patterns kaam karte hain" to "PatternCalls", "nifty pe kaun se pattern chalte hain" to "PatternCalls",
        "pattern calls kaise rahe" to "PatternCalls",
        // ---- Its neighbours: the paper tests (Vetting), the patterns now, a pattern explained ----
        "what held up on paper" to "Vetting", "what pattern is forming on nifty" to "Market", "explain the hammer" to "PatternExpert",
        "what is a doji" to "PatternExpert",
        // ---- BotHealth: each strategy's health (the account's BOTS) ----
        "how are my bots doing" to "Account:BOTS", "how are my strategies doing" to "Account:BOTS", "how are the arms doing" to "Account:BOTS",
        "how are my pine scripts doing" to "Account:BOTS", "how are my auto trades doing" to "Account:BOTS",
        "is the orb arm behaving" to "Account:BOTS", "is orb 5 behaving" to "Account:BOTS", "is my pine script behaving" to "Account:BOTS",
        "is the orb arm overtrading" to "Account:BOTS", "is the orb arm acting up" to "Account:BOTS", "is my bot going crazy" to "Account:BOTS",
        "is my strategy misbehaving" to "Account:BOTS", "how is the orb arm performing" to "Account:BOTS", "how is my orb arm doing" to "Account:BOTS",
        "are my algos ok" to "Account:BOTS", "are the bots fine" to "Account:BOTS", "are my bots healthy" to "Account:BOTS",
        "are my arms working fine" to "Account:BOTS", "are my auto trades fine" to "Account:BOTS",
        "which strategy is losing" to "Account:BOTS", "which bot is losing" to "Account:BOTS", "which arm is losing" to "Account:BOTS",
        "which algo lost" to "Account:BOTS", "which strategies lost" to "Account:BOTS", "which of my bots is worst" to "Account:BOTS",
        "strategy health" to "Account:BOTS", "bots health check" to "Account:BOTS", "health check on my strategies" to "Account:BOTS",
        "mere bots kaise chal rahe hain" to "Account:BOTS", "mere algos kaise chal rahe hain" to "Account:BOTS",
        "meri strategies theek chal rahi hain" to "Account:BOTS", "mere bots ka haal" to "Account:BOTS",
        "kaun si strategy loss mein hai" to "Account:BOTS",
        // ---- MyStreaks (round 16): Boss's own runs of days and trades, his best and worst weekday; a market's run stays Streak ----
        "am i on a winning streak" to "Account:STREAKS", "how many green days in a row have i had" to "Account:STREAKS",
        "my losing streak" to "Account:STREAKS", "what's my best weekday" to "Account:STREAKS", "which day of the week do i lose most" to "Account:STREAKS",
        "have i lost three days in a row" to "Account:STREAKS", "how many trades in a row did i lose" to "Account:STREAKS",
        "my winning and losing streaks" to "Account:STREAKS", "lagatar kitne din loss hua mera" to "Account:STREAKS",
        "mera konsa din best hai" to "Account:STREAKS", "my longest winning streak" to "Account:STREAKS",
        "how many days in a row has nifty risen" to "Streak", "banknifty losing streak" to "Streak",
        // ---- MyNumbers (round 17): Boss's own averages, ratios, per-trade result and hold times ----
        "what's my average win and average loss" to "Account:NUMBERS", "my profit factor" to "Account:NUMBERS",
        "what's my expectancy" to "Account:NUMBERS", "how much do i make per trade" to "Account:NUMBERS",
        "do i hold my losers longer than my winners" to "Account:NUMBERS", "do i cut my winners short" to "Account:NUMBERS",
        "my trading stats" to "Account:NUMBERS", "what is my risk reward on my trades" to "Account:NUMBERS",
        "mera average loss kitna hai" to "Account:NUMBERS", "my average loss this month" to "Account:NUMBERS",
        // ---- Headroom (round 13): how close Boss is to his limits; the limits themselves stay the account's RISK ----
        "how close am i to my limits" to "Headroom", "how much can i still lose today" to "Headroom", "how many trades do i have left" to "Headroom",
        "am i near my loss limit" to "Headroom", "limit se kitna door hoon" to "Headroom", "kitna aur loss le sakta hoon" to "Headroom",
        "what are my risk limits" to "Account:RISK",
        // ---- SaidAbout (round 14): what Boss said about something, from his own notes and journal answers ----
        "what did i say about expiry" to "SaidAbout", "what did i say about banknifty" to "SaidAbout",
        "what have i told you about being greedy" to "SaidAbout", "did i note anything about the hammer" to "SaidAbout",
        "find my notes on fridays" to "SaidAbout", "my notes about bank nifty" to "SaidAbout",
        "what did i write about nifty last week" to "SaidAbout", "maine expiry ke baare mein kya kaha tha" to "SaidAbout",
        // ---- WeekAhead: the week's sessions, expiries, holidays and events from the calendar (round 15) ----
        "what does this week look like" to "WeekAhead", "week ahead" to "WeekAhead", "is this an expiry week" to "WeekAhead",
        "how many trading days this week" to "WeekAhead", "what's coming up this week" to "WeekAhead",
        "expiries and holidays this week" to "WeekAhead", "plan for next week" to "WeekAhead", "agle hafte kya hai" to "WeekAhead",
        "what does next week look like" to "WeekAhead", "is hafte kya hai" to "WeekAhead",
        // ---- ZerodhaSession (voice round 14): why the Zerodha login ended, from the diagnostics; logging in stays Boss's own ----
        "why was i logged out of zerodha" to "ZerodhaSession", "why did zerodha log me out" to "ZerodhaSession",
        "why did my kite session expire" to "ZerodhaSession", "what happened to my zerodha login" to "ZerodhaSession",
        "why do i keep getting logged out of kite" to "ZerodhaSession", "zerodha se logout kyun hua" to "ZerodhaSession",
        "when does my zerodha session end" to "ZerodhaSession",
        // ---- RelayHealth (round 19): the relay and the static IP from the diagnostics; testing it stays Boss's own tap ----
        "why is my relay failing" to "RelayHealth", "is my static ip working" to "RelayHealth", "relay kyun nahi chal raha" to "RelayHealth",
        "is the relay working" to "RelayHealth", "why can't the relay connect" to "RelayHealth", "is my relay server up" to "RelayHealth",
        "what's wrong with the relay" to "RelayHealth", "relay health" to "RelayHealth", "am i on my static ip" to "RelayHealth",
        "why is zerodha login failing through the relay" to "RelayHealth", "is my static ip ok" to "RelayHealth",
        "relay ka kya haal hai" to "RelayHealth", "static ip kaam kar raha hai kya" to "RelayHealth",
        "why does the relay keep timing out" to "RelayHealth", "when did the relay last connect" to "RelayHealth",
        "relay status" to "RelayHealth", "is the relay down" to "RelayHealth",
        // ---- SwitchOff (round 20): each arm's two-year test beside its paper record; switching stays Boss's (asked first) ----
        "what should i switch off" to "SwitchOff", "which arms should i turn off" to "SwitchOff", "which strategies should i stop" to "SwitchOff",
        "which arms lost in both the test and on paper" to "SwitchOff", "is any arm worth switching off" to "SwitchOff",
        "should i switch off orb" to "SwitchOff", "should i disarm orb fresh" to "SwitchOff", "kaun sa bot band karun" to "SwitchOff",
        "kya band karna chahiye" to "SwitchOff", "what to switch off" to "SwitchOff", "which arms are worth keeping" to "SwitchOff",
        // ---- StreamHealth: why Zerodha's price stream drops, from the diagnostics; it reconnects by itself ----
        "why is the price stream dropping" to "StreamHealth", "why does the stream keep disconnecting" to "StreamHealth",
        "stream kyun toot raha hai" to "StreamHealth", "is the price stream working" to "StreamHealth",
        "why is the websocket dropping" to "StreamHealth", "why do the ticks keep dropping" to "StreamHealth",
        "price stream baar baar disconnect ho raha hai" to "StreamHealth", "kite stream status" to "StreamHealth",
        // ---- BatteryUse (battery round 1): what runs in the background now, biggest first; the order watch never slowed ----
        "why is the app using so much battery" to "BatteryUse", "battery kyun kha raha hai" to "BatteryUse",
        "why is my battery draining so fast" to "BatteryUse", "how much battery does the app use" to "BatteryUse",
        "app kitni battery khata hai" to "BatteryUse", "what is running in the background" to "BatteryUse",
        // ---- WatchAsk (round 16): the order watch's state and IraAlgo's battery setting, read only; "stop the order watch" stays a command ----
        "is the watch running" to "WatchAsk", "why did the watch get stuck" to "WatchAsk", "battery setting kya hai" to "WatchAsk",
        "kya mera phone app ko rok raha hai" to "WatchAsk", "watch kyun ruk gaya" to "WatchAsk", "what is my battery setting" to "WatchAsk",
        // ---- Tour (voice round 15): "what can I ask you?" names five questions for the part of the day ----
        "what can i ask you" to "Tour", "what else can i ask jarvis" to "Tour", "what should i ask now" to "Tour",
        "what questions can i ask" to "Tour", "what kind of questions should i ask you" to "Tour", "suggest some questions" to "Tour",
        "give me some ideas of what to ask" to "Tour", "what's worth asking right now" to "Tour", "give me a tour" to "Tour",
        "main kya pooch sakta hoon" to "Tour", "tumse kya puchu" to "Tour", "kya poochna chahiye" to "Tour", "kuch sawal batao" to "Tour",
        "hammer ke baare mein maine kya bola tha" to "SaidAbout",
        // ---- Clarity (round 11): the answers said shorter aloud, and back to usual ----
        "which answers do you keep short" to "Clarity", "which of your answers do you keep shorter" to "Clarity",
        "which answers have you shortened" to "Clarity", "which answers were unclear" to "Clarity",
        "which of your answers do i find confusing" to "Clarity", "why are your answers so short now" to "Clarity",
        "kaun se jawab chhote karte ho" to "Clarity", "say your answers in full again" to "Clarity",
        "don't shorten your answers" to "Clarity", "no need to shorten your answers anymore" to "Clarity",
        "forget which answers i found unclear" to "Clarity",
        // ---- WordFit (round 12): his confidence words against the numbers beside them ----
        "how well do your words match your numbers" to "WordFit", "are your confidence words calibrated" to "WordFit",
        "what do you mean by usually" to "WordFit", "when you say often what do you mean" to "WordFit",
        "what does rarely mean" to "WordFit", "how calibrated are you" to "WordFit",
        "say your confidence words as written" to "WordFit", "match your words to the numbers again" to "WordFit",
        // ---- AskedAgain (round 13): the market reads Boss asked again within minutes ----
        "which of your answers do i ask again" to "AskedAgain", "which answers did i have to ask twice" to "AskedAgain",
        "what do i keep asking again" to "AskedAgain", "which of your market reads missed" to "AskedAgain",
        "which answers didn't land" to "AskedAgain", "kaun se jawab main dobara puchta hoon" to "AskedAgain",
        // ---- FigureFirst (round 14): the market reads said figure first aloud, Boss having asked them again for it ----
        "which reads do you start with the number" to "FigureFirst", "why do you start with the number" to "FigureFirst",
        "why are you saying the figure first" to "FigureFirst", "say your market reads in the usual order" to "FigureFirst",
        "dont start with the number" to "FigureFirst", "number pehle mat bolo" to "FigureFirst",
        // ---- WrongThing (round 15): the questions Jarvis answered with the wrong thing ----
        "what did you get wrong today" to "WrongThing", "which questions did you answer with the wrong thing" to "WrongThing",
        "what did you get wrong this week" to "WrongThing", "where did you misunderstand me today" to "WrongThing",
        "aaj kya galat jawab diya" to "WrongThing", "galat jawab" to "WrongThing", "ye nahi poocha" to "WrongThing",
        "you answered the wrong thing" to "WrongThing",
        // ---- OutsideApp (routing round 12): outside IraAlgo, said politely - nothing outside the app is ever done ----
        "open youtube" to "OutsideApp", "youtube kholo" to "OutsideApp", "open whatsapp" to "OutsideApp", "play music" to "OutsideApp",
        "call mom" to "OutsideApp", "message rahul" to "OutsideApp", "launch spotify" to "OutsideApp", "gaana bajao" to "OutsideApp",
        "open chrome" to "OutsideApp", "book a cab" to "OutsideApp",
        // Its neighbours: the app's own screens.
        "open the option chain" to "Account:CHAIN", "open settings" to "Account:SETTINGS",
        // ---- ArmHabits (learning round 16): what Boss does with his bots after losing days ----
        "do i usually disarm my bots after losses" to "ArmHabits", "do i usually disarm range fade after two losing days" to "ArmHabits",
        "do i keep orb armed after losses" to "ArmHabits", "when do i usually disarm range fade" to "ArmHabits",
        "what do i do with my bots after a losing day" to "ArmHabits", "which bots do i keep armed" to "ArmHabits",
        "my arming habits" to "ArmHabits", "do i give up on my bots too fast" to "ArmHabits",
        "loss ke baad main bot band karta hoon kya" to "ArmHabits",
        // ---- MorningSense (learning round 17): the morning-check items Boss leaves, named in a few words aloud ----
        "which morning check items do you skip" to "MorningSense", "what do you leave out of the morning check" to "MorningSense",
        "why was the morning check so short today" to "MorningSense", "what do i usually ignore in the morning check" to "MorningSense",
        "say the whole morning check again" to "MorningSense", "read the full morning check" to "MorningSense",
        "don't skip anything in the morning check" to "MorningSense", "poora morning check sunao" to "MorningSense",
        // ---- HonestStars (learning round 18): the confidence scores said aloud with their record when they have not held up ----
        "how honest are your confidence scores" to "HonestStars", "are your confidence stars reliable" to "HonestStars",
        "can i trust your confidence" to "HonestStars", "does your 4 out of 5 mean anything" to "HonestStars",
        "how often does your 5 out of 5 work" to "HonestStars", "why did you say it was a coin toss" to "HonestStars",
        "say your confidence plainly" to "HonestStars", "don't add your record to the confidence" to "HonestStars",
        "tumhara confidence kitna sahi hai" to "HonestStars", "confidence seedha bolo" to "HonestStars",
        // ---- TalkHours (learning round 19): long unasked briefings said in a sentence aloud outside the hours Boss talks to Jarvis ----
        "when do i usually talk to you" to "TalkHours", "what time of day do i talk to you" to "TalkHours",
        "which hours do you keep briefings short" to "TalkHours", "why was the briefing so short" to "TalkHours",
        "why do you keep your updates short" to "TalkHours", "main tumse kab baat karta hoon" to "TalkHours",
        "say your briefings in full at any hour" to "TalkHours", "don't shorten your briefings" to "TalkHours",
        "no need to cut the updates short" to "TalkHours", "briefing poori bolo hamesha" to "TalkHours",
        // ---- MorningAsks (learning round 20): the question Boss asks every morning, offered in one line at the end of the morning check ----
        "what do i usually ask in the morning" to "MorningAsks", "what question do i ask every morning" to "MorningAsks",
        "which question do you offer me in the morning" to "MorningAsks", "why did you offer that question in the morning check" to "MorningAsks",
        "main subah kya poochta hoon" to "MorningAsks", "don't offer my usual morning question" to "MorningAsks",
        "no more morning offers" to "MorningAsks", "subah ka sawal mat poocho" to "MorningAsks",
        // ---- TurnDowns (learning round 21): the reasons Boss turns Jarvis's ideas down for, said up front ----
        "why do i turn down your ideas" to "TurnDowns", "why do i usually reject your trade ideas" to "TurnDowns",
        "what reasons do i give for turning down your ideas" to "TurnDowns", "why did you remind me why i turned it down" to "TurnDowns",
        "main tumhare idea kyun reject karta hoon" to "TurnDowns", "don't remind me why i turn your ideas down" to "TurnDowns",
        "don't tell me my reasons up front" to "TurnDowns", "mere reasons mat yaad dilao" to "TurnDowns",
        // ---- TopicLength (learning round 22): the topics said in a sentence or in full aloud, as Boss asks ----
        "how long do i like your answers" to "TopicLength", "which topics do you keep short" to "TopicLength",
        "what answer length do i like" to "TopicLength", "say every topic at the usual length" to "TopicLength",
        "stop shortening answers by topic" to "TopicLength", "kaun se topic detail mein batate ho" to "TopicLength",
        // ---- OutlookCheck (reasoning round 21): the 09:00 outlook against the close, in counts ----
        "how good are your morning outlooks" to "OutlookCheck", "did your outlook hold today" to "OutlookCheck",
        "how accurate are your outlooks" to "OutlookCheck", "tumhara outlook kitna sahi hota hai" to "OutlookCheck",
        "subah ka outlook sahi tha kya" to "OutlookCheck",
        // ---- UsualIndex (learning round 23): the index taken when Boss names none, from his corrections ----
        "which index do i usually mean" to "UsualIndex", "what's my usual index" to "UsualIndex",
        "what index do you assume" to "UsualIndex", "use nifty when i dont name an index" to "UsualIndex",
        "don't assume my index" to "UsualIndex", "mera usual index bhool jao" to "UsualIndex",
        // ---- Its neighbours: the strategies listed, Solo, the positions' health ----
        "show my strategies" to "Account:STRATEGIES", "list my strategies" to "Account:STRATEGIES",
        "what strategies are running" to "Account:STRATEGIES", "which strategies are on" to "Account:STRATEGIES",
        "how is solo doing" to "Solo", "is my position healthy" to "Account:HEALTH",
        // ---- The chain's words asked are the glossary's; the chain's own reads stay ChainIntel's ----
        "what is a straddle" to "Glossary", "explain straddle" to "Glossary", "straddle kya hota hai" to "Glossary",
        // ---- Round 9: Boss's own rules, the case's contradictions, the patterns trusted, the news that moved ----
        "what are my rules" to "AboutBoss", "what rules did i tell you" to "AboutBoss", "mere rules kya hain" to "AboutBoss",
        "any contradictions in my case" to "Consistency", "any contradictions in the case" to "Consistency",
        "which patterns do you trust" to "PatternCalls", "which patterns can i trust" to "PatternCalls",
        "kaun se patterns pe bharosa hai" to "PatternCalls",
        "was it the news that moved nifty" to "NewsMoves", "was it the news that moved the market" to "NewsMoves",
        "was it rbi news that moved banknifty" to "NewsMoves", "kya news se nifty gira" to "NewsMoves",
        "did the news move banknifty" to "NewsDesk", "was it the news that moved nifty today" to "NewsDesk",
        // ---- PreMarket ----
        "am i ready to trade" to "PreMarket", "pre market checklist" to "PreMarket", "kya main trade ke liye ready hoon" to "PreMarket",
        "sab ready hai kya" to "PreMarket", "go through my pre market checklist" to "PreMarket", "are we good to go for the open" to "PreMarket",
        // ---- NewsMoves ----
        "how does the market react to rbi news" to "NewsMoves", "rbi news pe nifty kaise react karta hai" to "NewsMoves",
        "fed ki news se market hilta hai kya" to "NewsMoves", "which news moves nifty the most" to "NewsMoves",
        // ---- Hearing ----
        "how well are you hearing me" to "Hearing", "can you hear me properly" to "Hearing", "kya tum mujhe theek se sun rahe ho" to "Hearing",
        "are you hearing me properly" to "Hearing", "mera awaaz saaf aa raha hai kya" to "Hearing",
        // ---- CoPilot ----
        "what matters right now" to "CoPilot", "brief me like a co pilot" to "CoPilot", "abhi sabse important kya hai" to "CoPilot",
        "what's most important right now" to "CoPilot", "top three things right now" to "CoPilot", "kya matter karta hai abhi" to "CoPilot",
    )

    @Test fun eachFamilyGetsItsOwnQuestions() {
        val wrong = ROUTED.mapNotNull { (s, want) -> feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got" } }
        assertTrue(wrong.isEmpty(), "taken by the wrong feature (${wrong.size} of ${ROUTED.size}):\n" + wrong.joinToString("\n"))
        assertTrue(ROUTED.size >= 470, "${ROUTED.size}")
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
            // Round 7: the families added since, each beside the one it sounds like.
            ("what if nifty falls 1%" to "Scenarios") to ("how much do i lose if nifty falls 1%" to "Account:MOVE"),
            ("what if nifty moves 100 points" to "Scenarios") to ("what happens to my p&l if nifty moves 100 points" to "Account:MOVE"),
            ("agar nifty 1% gir jaye to kya hoga" to "Scenarios") to ("agar nifty 1% gira to mera p&l kya hoga" to "Account:PNL"),
            ("what if nifty swings 300 points" to "Scenarios") to ("nifty swing levels" to "Structure"),
            ("what if nifty opens 1% down" to "Scenarios") to ("what if i had taken that trade" to "Account:WHATIF"),
            ("what's the structure today" to "Structure") to ("how did the day go for nifty" to "DayStory"),
            ("trend or range so far" to "Structure") to ("how is the opening range" to "OpeningRange"),
            ("what's the structure today" to "Structure") to ("what was the structure yesterday" to "Account:HISTORY"),
            ("check my positions" to "Account:HEALTH") to ("show my positions" to "Account:POSITIONS"),
            ("are my positions okay" to "Account:HEALTH") to ("rank my positions" to "Account:RANK"),
            ("kya meri positions theek hain" to "Account:HEALTH") to ("meri positions kaisi hain" to "Account:POSITIONS"),
            ("check my positions" to "Account:HEALTH") to ("close my positions" to "Act"),
            ("what do i usually ask" to "Routine") to ("the usual" to "Habits"),
            ("what are my habits with you" to "Routine") to ("what are my habits" to ACCOUNT_REVIEW),
            ("what's my routine" to "Routine") to ("what are my trading habits" to ACCOUNT_REVIEW),
            ("forget my routine" to "Routine") to ("forget what i told you" to "AboutBoss"),
            ("what words have you learned" to "Corrections") to ("what have you learned this week" to "Learnings"),
            ("forget the word teeta" to "Corrections") to ("forget that i trade on fridays" to "AboutBoss"),
            ("what's my f&o turnover this year" to "Account:TAX") to ("export my trades for tax" to "TaxExport"),
            ("my tax summary" to "Account:TAX") to ("how much tax on my trades" to "Account:CHARGES"),
            // Round 8: the newest families beside the ones they sound like.
            ("what's the main news today" to "NewsDesk") to ("what's the news" to "Market"),
            ("any news on banks" to "NewsDesk") to ("banknifty news" to "Market"),
            ("what news moved the market" to "NewsDesk") to ("any news behind this sudden fall" to "SharpMove"),
            ("news behind today's fall" to "NewsDesk") to ("why did the market drop today" to "Causes"),
            ("how are my bots doing" to "Account:BOTS") to ("show my strategies" to "Account:STRATEGIES"),
            ("which strategy is losing" to "Account:BOTS") to ("which position is losing the most" to "Account:RANK"),
            ("is the orb arm behaving" to "Account:BOTS") to ("is orb running" to "Account:STRATEGIES"),
            ("are my bots healthy" to "Account:BOTS") to ("are my positions okay" to "Account:HEALTH"),
            ("is orb 5 behaving" to "Account:BOTS") to ("stop orb 5" to "Act"),
            ("any contradictions" to "Consistency") to ("make the case" to "TradeCase"),
            ("do the facts agree" to "Consistency") to ("give me the case for and against" to "TradeCase"),
            ("am i going against my own rules" to "Consistency") to ("what did i tell you" to "AboutBoss"),
            ("which patterns work on nifty" to "PatternCalls") to ("what held up" to "Vetting"),
            ("how good are your pattern calls" to "PatternCalls") to ("where are you weakest" to "SelfCalibration"),
            ("which patterns work on nifty" to "PatternCalls") to ("what pattern is forming on nifty" to "Market"),
            ("your pattern record" to "PatternCalls") to ("explain the hammer" to "PatternExpert"),
            ("are we making higher highs" to "Structure") to ("are we in profit" to "Account:PNL"),
            ("what is my trading routine" to "Routine") to ("what are my trading habits" to ACCOUNT_REVIEW),
            ("worst position" to "Account:RANK") to ("what's the best position to take" to "Account:POSITIONS"),
            ("what is a straddle" to "Glossary") to ("what is the atm straddle" to "ChainIntel"),
            // Round 11: the answers said shorter beside the alerts said less often.
            ("which answers do you keep short" to "Clarity") to ("which alerts do you hold back" to "AlertSense"),
            ("say your answers in full again" to "Clarity") to ("say everything again" to "AlertSense"),
            // Round 12: a confidence word asked of is his own; a market word asked of stays the glossary's.
            ("what do you mean by usually" to "WordFit") to ("what do you mean by max pain" to "Glossary"),
            // Round 13: what Boss asks again is a record of Jarvis's answers; his usual questions stay his routine.
            ("what do i keep asking again" to "AskedAgain") to ("what do i usually ask" to "Routine"),
            // Round 14: the order his reads are said in is his own; the answers said shorter stay Clarity's.
            ("say your market reads in the usual order" to "FigureFirst") to ("say your answers in full again" to "Clarity"),
            // Round 15: what he got wrong today is his record of misses; plain "what did you get wrong" stays the marked mistakes.
            ("what did you get wrong today" to "WrongThing") to ("what did you get wrong" to "Account:MISTAKES"),
            // Round 16: what Boss does with his bots after losses is his record; how they are doing stays the bots' health.
            ("do i usually disarm my bots after losses" to "ArmHabits") to ("how are my bots doing" to "Account:BOTS"),
            // Round 17: the spoken morning check's length is his own; the checklist itself stays PreMarket's.
            ("which morning check items do you skip" to "MorningSense") to ("go through my morning checklist" to "PreMarket"),
            // Round 18: how his confidence scores held up is his own; his confidence words against their numbers stay WordFit's.
            ("how honest are your confidence scores" to "HonestStars") to ("are your confidence words calibrated" to "WordFit"),
            // Round 19: the hours his briefings are said in full are learned from Boss; the morning check's length stays MorningSense's.
            ("why was the briefing so short" to "TalkHours") to ("why was the morning briefing so short" to "MorningSense"),
            // Round 20: the question offered at the end of the morning check is learned from Boss; the check's items stay MorningSense's.
            ("don't offer my usual morning question" to "MorningAsks") to ("say the whole morning check again" to "MorningSense"),
            // Round 21: why Boss turns Jarvis's ideas down is learned from him; how those ideas' scores held up stays HonestStars'.
            ("why do i turn down your ideas" to "TurnDowns") to ("do your 5 star ideas actually work" to "HonestStars"),
            // Round 22: the topics said shorter as Boss asks for them; the answers said shorter after his "what?" stay Clarity's.
            ("which topics do you keep short" to "TopicLength") to ("which answers do you keep short" to "Clarity"),
            // Reasoning round 10: a fall weighed by its evidence, beside one sudden move's coincidences and the news behind it.
            ("why did nifty fall" to "Causes") to ("why did nifty suddenly fall" to "SharpMove"),
            ("what caused the fall today" to "Causes") to ("what news moved the market" to "NewsDesk"),
            ("why is nifty falling" to "Causes") to ("how much did nifty fall today" to "Market"),
            // Market intelligence round 15: a weekday's record beside the calendar and a single day's story.
            ("are mondays more volatile" to "Weekdays") to ("is monday a holiday" to "MarketDays"),
            ("are expiry days more volatile" to "Weekdays") to ("how did the last expiry go" to "MarketMemory"),
            ("how does nifty usually do on fridays" to "Weekdays") to ("what will nifty do on monday" to "Outlook"),
            // Market intelligence round 16: the opening-range record beside today's range and Boss's ORB arms.
            ("do opening range breakouts usually hold" to "RangeBreaks") to ("did nifty break the opening range" to "OpeningRange"),
            ("how often do orb breakouts fail" to "RangeBreaks") to ("is the orb arm behaving" to "Account:BOTS"),
            // Market intelligence round 17: the prior-day record beside today against yesterday.
            ("how often does nifty break the previous day's high" to "PriorDay") to ("how is today different from yesterday" to "DayCompare"),
            // Market intelligence round 18: the last-hour record beside how busy the last hour is.
            ("does nifty usually reverse in the last hour" to "LastHour") to ("is the last hour usually volatile" to "DayClock"),
            // Reasoning round 14: his own trend reads scored, beside today's structure, his pattern record and the market's trend days.
            ("how often were your trend reads right this month" to "TrendReads") to ("trend or range so far" to "Structure"),
            ("how accurate are your structure reads" to "TrendReads") to ("what's the structure today" to "Structure"),
            ("your trend read record" to "TrendReads") to ("your pattern record" to "PatternCalls"),
            ("did your trend calls hold" to "TrendReads") to ("how many trend days this month" to "MarketMemory"),
            ("tumhare trend reads kitne sahi the" to "TrendReads") to ("what would change your mind" to "MindChange"),
            // Market intelligence round 19: the inside-day record beside staying inside the opening range.
            ("how often does nifty's range expand after an inside day" to "InsideDays") to ("how often does nifty stay inside the opening range all day" to "RangeBreaks"),
            // Market intelligence round 20: the first move's record beside the first half hour's range and how busy it is.
            ("how often does the first half hour direction match the close" to "FirstMove") to ("how often does nifty break its first 30 minute range" to "RangeBreaks"),
            // Market intelligence round 21: the VIX next-day record beside the last VIX spike one by one.
            ("when vix jumps 5% how big is the next day" to "VixNext") to ("when did vix last jump like this" to "MarketMemory"),
            // Market intelligence round 23: the split-day record beside today's moving together and the leader over a week.
            ("how often do nifty and banknifty diverge" to "SplitDays") to ("is banknifty moving with nifty" to "Together"),
            ("nifty banknifty divergence record" to "SplitDays") to ("relative strength banknifty vs nifty this week" to "Breadth"),
            // Market intelligence round 24: the round-number record beside what sits at one price and the expiry pin.
            ("does nifty close near round numbers" to "RoundCloses") to ("what's at 25000 on nifty" to "LevelInfo"),
            ("are round numbers a magnet for nifty" to "RoundCloses") to ("how often does nifty close near max pain on expiry" to "ExpiryPin"),
            // Market intelligence round 25: the month-edge record beside the month's own move, Boss's month and a weekday's record.
            ("how does nifty do at the beginning of the month" to "MonthTurns") to ("how was the market this week" to "PeriodMove"),
            ("month end record" to "MonthTurns") to ("how was my month" to "Account:MONTH"),
            ("is there a turn of the month effect" to "MonthTurns") to ("are mondays more volatile" to "Weekdays"),
            // Market intelligence round 26: the lunch-range record beside how quiet lunch is, the opening range and the last hour.
            ("does nifty break the lunch range" to "LunchRange") to ("is the lunch hour usually quiet" to "DayClock"),
            ("lunch range breakout record" to "LunchRange") to ("do opening range breakouts usually hold" to "RangeBreaks"),
            ("is the lunch lull real" to "LunchRange") to ("does nifty usually reverse in the last hour" to "LastHour"),
            // Market intelligence round 27: the open-high / open-low record beside the gap, the opening range and the first move.
            ("how often is the open the high of the day" to "OpenHighLow") to ("how often does the gap fill" to "GapRecord"),
            ("open = low days record" to "OpenHighLow") to ("do opening range breakouts usually hold" to "RangeBreaks"),
            ("how do open high days close" to "OpenHighLow") to ("does the first half hour decide the day" to "FirstMove"),
            // Market intelligence round 28: the big-candle record beside the first move, the candle patterns and the opening range.
            ("big candle record" to "BigCandles") to ("does the first half hour decide the day" to "FirstMove"),
            ("after a big 5-minute candle in the first hour does the day continue" to "BigCandles") to ("which candle patterns work on nifty" to "PatternCalls"),
            ("how often does a big green candle in the morning follow through" to "BigCandles") to ("do opening range breakouts usually hold" to "RangeBreaks"),
            // Market intelligence round 29: the close-at-the-ends record beside the last hour, the big-candle record and the day before.
            ("how often does nifty close near its high" to "ExtremeCloses") to ("last hour record for sensex" to "LastHour"),
            ("strong close record" to "ExtremeCloses") to ("big candle record" to "BigCandles"),
            ("what happens the day after nifty closes at the low" to "ExtremeCloses") to ("how often does nifty break the previous day's high" to "PriorDay"),
            // Reasoning round 15: now against this morning, beside the co-pilot, the chain's drift, the OI shift, today against yesterday and the news.
            ("what changed since this morning" to "SinceMorning") to ("what matters right now" to "CoPilot"),
            ("what's different since the open" to "SinceMorning") to ("has the biggest put oi moved since morning" to "ChainDrift"),
            ("anything new since 9:45" to "SinceMorning") to ("how has the oi shifted since morning" to "ChainIntel"),
            ("now versus this morning" to "SinceMorning") to ("how is today different from yesterday" to "DayCompare"),
            ("subah se kya badla" to "SinceMorning") to ("what changed in how you work" to "Learnings"),
            // Market intelligence round 22: the expiry pin record beside max pain through today.
            ("how often does nifty close near max pain on expiry" to "ExpiryPin") to ("how has max pain moved today" to "ChainDrift"),
            // Reasoning round 16: a strategy's losing day beside the index, next to its trades explained, its health and Boss's own trade.
            ("why did my strategy lose today" to "ArmDay") to ("explain my bots trades today" to "BotTrades"),
            // Reasoning round 19: each arm's record on days in today's bands, next to the daily regime and a like day.
            ("which of my arms suits today" to "ArmFit") to ("which arms suit this market" to "Account:REGIME"),
            // Reasoning round 20: what most often went wrong in the arms' last paper trades, next to one day's loss.
            ("what keeps going wrong with my bots" to "WeakLink") to ("what went wrong with my bots today" to "ArmDay"),
            ("how do my arms do on days like today" to "ArmFit") to ("is today like any past day" to "LikeToday"),
            ("aaj ke din kaun sa bot suit karta hai" to "ArmFit") to ("why did orb lose today" to "ArmDay"),
            ("why did orb lose today" to "ArmDay") to ("why did orb take that trade" to "BotTrades"),
            ("what went wrong with range fade today" to "ArmDay") to ("did my bots follow their rules" to "BotTrades"),
            ("orb ka aaj loss kyun hua" to "ArmDay") to ("mere bots ne aaj kya kiya" to "BotTrades"),
            ("why was today a bad day for my bots" to "ArmDay") to ("why did my last trade lose" to "Account:LOSSES"),
            // Reasoning round 17: which way the open book leans now, next to the what-if on it and the bots' trades today.
            ("am i net long or short" to "NetLean") to ("are my bots fighting each other" to "BotTrades"),
            ("do my bots contradict each other right now" to "NetLean") to ("any contradictions between my bots" to "BotTrades"),
            ("which way am i leaning" to "NetLean") to ("did my bots take opposite sides" to "BotTrades"),
            ("whats my net delta on banknifty" to "NetLean") to ("why did orb lose today" to "ArmDay"),
            ("main long hoon ya short" to "NetLean") to ("mere bots ne aaj kya kiya" to "BotTrades"),
            // Reasoning round 18: today against every past day, next to a named day and the gap's own record.
            ("is today like any past day" to "LikeToday") to ("is today like yesterday" to "DayCompare"),
            ("how did days like today end" to "LikeToday") to ("do gap ups usually fill" to "GapRecord"),
        )) { assertEquals(a.second, feature(a.first), a.first); assertEquals(b.second, feature(b.first), b.first) }
        // Boss's Hinglish what-if is a what-if; a forecast, advice or his own book in Hindi never is.
        for (s in listOf("kal nifty ka kya hoga", "nifty 200 points gir jayega kya", "agar nifty 1% gira to kya buy karu",
            "agar nifty 1% gira to meri positions ka kya hoga", "what if nifty falls 1% should i buy puts", "will nifty fall 1% tomorrow"))
            assertEquals(null, Scenarios.asked(s), s)
        // "What is my trading routine" is his routine (round 8: it fell to a market answer); his trading habits stay the review.
        assertEquals(true, Routine.asked("what is my trading routine"))
        assertEquals(false, Routine.asked("what are my trading habits"))
        // "Remember when..." asks; it is never kept as a note.
        assertEquals(null, Memory.toKeep("remember when nifty gapped down"))
        assertEquals("i trade on fridays", Memory.toKeep("remember that i trade on fridays"))
        // "How did I do this week" is his week, not the calendar's events; "any events this week" stays the events.
        assertEquals(setOf(Section.HISTORY), AppAnswers.sections("how did i do this week"))
        assertEquals(setOf(Section.EVENTS), AppAnswers.sections("any events this week"))
    }

    /** Round 9's lines: Boss's rules, the case's contradictions, the patterns trusted, the news that moved, and the recent features' wordings. */
    private val ROUND9 = listOf("what are my rules", "what rules did i tell you", "mere rules kya hain", "any contradictions in my case",
        "which patterns do you trust", "which patterns can i trust", "kaun se patterns pe bharosa hai", "was it the news that moved nifty",
        "was it the news that moved the market", "was it rbi news that moved banknifty", "kya news se nifty gira", "kya main trade ke liye ready hoon",
        "sab ready hai kya", "go through my pre market checklist", "are we good to go for the open", "rbi news pe nifty kaise react karta hai",
        "fed ki news se market hilta hai kya", "can you hear me properly", "kya tum mujhe theek se sun rahe ho", "are you hearing me properly",
        "mera awaaz saaf aa raha hai kya", "abhi sabse important kya hai", "kya matter karta hai abhi", "top three things right now")

    @Test fun roundNineWordingsNeitherOrderNorCommandNorBundle() {
        for (s in ROUND9) {
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Bundle.acts(s), s)
            assertEquals(null, Intents.quick(s), s)
            assertTrue(!Reminder.asked(s) && !FollowUp.acts(s), s)
            assertTrue(ROUTED.any { it.first == s }, s)
        }
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("what are my rules and then exit all", "am i ready to trade, then stop all strategies", "what matters right now then kill switch on",
            "was it the news that moved nifty and close all positions"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
        // The words that act stay as they were: a voice check and "run ..." are still commands, never these questions.
        assertEquals(Command.Kind.VOICE_CHECK, Ask.parse("are you hearing me ok").command?.kind)
        assertEquals(Command.Kind.START_ONE, Ask.parse("run the pre market checklist").command?.kind)
        // Neighbours: his own rules, his goals, going against his rules.
        assertEquals("AboutBoss", feature("what are my rules")); assertEquals("Goals", feature("what are my goals"))
        assertEquals("Consistency", feature("am i going against my own rules"))
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
