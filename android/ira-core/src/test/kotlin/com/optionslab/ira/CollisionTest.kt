package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The collision hunt (routing round 10, 5 Oct): what Boss asks around the newest families - Clarity (and its "what?"),
 * Headroom, NeedsTrue, PositionHealth, TradeCase, DayClock, Structure, MarketDays, ChainDrift, ChainIntel and the chain's
 * own read (the account's CHAIN) - in English and Hinglish, run through every one of their matchers and through the whole
 * hub ([CoverageTest.feature], IraHub.ask's order). A question two families take must go, by the hub's order, to the one
 * meant; each line names the feature it must get.
 *
 * Round 11 (5 Oct): the open items round 10 listed are routed and asserted here ("talk me through my put" and its kin are
 * the position explained, "holiday kab hai" and the month's trading days the calendar, "expiry ka din kya hai" the next
 * expiry, "make the case for buying calls" the trade check's case, "breakeven kitna door hai" what the position needs),
 * and the newest families join the hunt: GapRecord, Weekdays, MindChange, Causes, WeekAhead, SaidAbout, ZerodhaSession,
 * AskedAgain, WordFit, Boss's own STREAKS - and Again, the voice's own "say that again slowly", checked apart.
 *
 * Round 13 (5 Oct): round 12's open items ("what all can you do" is the help answer, "how was yesterday for Nifty" the
 * index's last session, "the average price of my put" his position, "meri put explain karo phir isko band karo" the plan),
 * and the newest families join the hunt: OrderWhy, BotTrades, WrongThing, OutsideApp, TrendReads, ArmHabits, PriorDay,
 * LastHour, InsideDays and Boss's own NUMBERS - none an order or a command; said with an action, the multi-step plan.
 */
class CollisionTest {
    private val audit = CoverageTest()
    private val today: LocalDate = LocalDate.of(2026, 10, 5)

    /** The families in IraHub.ask's order, each named as [CoverageTest.feature] names what answers it. */
    private val FAMILIES: List<Pair<String, (String) -> Boolean>> = listOf(
        "TrendReads" to { q -> TrendReads.asked(q) },
        "OutsideApp" to { q -> OutsideApp.asked(q) },
        "Clarity" to { q -> Clarity.asked(q) != null },
        "WordFit" to { q -> WordFit.asked(q) != null },
        "AskedAgain" to { q -> AskedAgain.asked(q) },
        "FigureFirst" to { q -> FigureFirst.asked(q) != null },
        "WrongThing" to { q -> WrongThing.asked(q) != null || WrongThing.objected(q) },
        "ArmHabits" to { q -> ArmHabits.asked(q) },
        "Headroom" to { q -> Headroom.asked(q) != null },
        "BotTrades" to { q -> BotTrades.asked(q) != null },
        "SaidAbout" to { q -> SaidAbout.asked(q) != null },
        "WeekAhead" to { q -> WeekAhead.asked(q) != null },
        "ZerodhaSession" to { q -> ZerodhaSession.asked(q) != null },
        "OrderWhy" to { q -> OrderWhy.asked(q) != null },
        "Tour" to { q -> Tour.asked(q) },
        "ChainDrift" to { q -> ChainDrift.asked(q) != null },
        "ChainIntel" to { q -> ChainIntel.asked(q) != null },
        "DayClock" to { q -> DayClock.asked(q) != null },
        "GapRecord" to { q -> GapRecord.asked(q) != null },
        "RangeBreaks" to { q -> RangeBreaks.asked(q) != null },
        "PriorDay" to { q -> PriorDay.asked(q) != null },
        "LastHour" to { q -> LastHour.asked(q) != null },
        "InsideDays" to { q -> InsideDays.asked(q) != null },
        "Weekdays" to { q -> Weekdays.asked(q) != null },
        "DayCompare" to { q -> DayCompare.asked(q) != null },
        "Structure" to { q -> Structure.asked(q) != null },
        "MindChange" to { q -> MindChange.asked(q) },
        "TradeCase" to { q -> TradeCase.asked(q) },
        "Scenarios" to { q -> Scenarios.asked(q) != null },
        "Causes" to { q -> Causes.asked(q) != null },
        "MarketDays" to { q -> MarketDays.expiryAsked(q) || MarketDays.asked(q, today) != null },
        // The account's own sections, in Account.sections' precedence (STREAKS is set last, then NEED; each wins over HEALTH).
        "Account:NUMBERS" to { q -> MyNumbers.asked(q) },
        "Account:STREAKS" to { q -> MyStreaks.asked(q) },
        "Account:NEED" to { q -> NeedsTrue.asked(q) },
        "Account:HEALTH" to { q -> PositionHealth.asked(q) },
        "Account:RISK" to { q -> Topic.ACCOUNT in Ask.parse(q).topics && Section.RISK in AppAnswers.sections(q) },
        "Account:CHAIN" to { q -> Topic.ACCOUNT in Ask.parse(q).topics && Section.CHAIN in AppAnswers.sections(q) },
    )

    private fun hits(q: String): List<String> = FAMILIES.filter { (_, f) -> runCatching { f(q) }.getOrDefault(false) }.map { it.first }

    /** What Boss asks around these families (English, Hinglish, the recognizer's spellings), with the feature each must get. */
    private val ASKED: List<Pair<String, String>> = listOf(
        // ---- Clarity ----
        "which answers do you keep short" to "Clarity", "say your answers in full again" to "Clarity", "which answers do you cut short" to "Clarity",
        "are you keeping your answers short" to "Clarity", "why are you giving short answers" to "Clarity", "can you give full answers" to "Clarity",
        "which answers were confusing" to "Clarity", "which answers are unclear" to "Clarity", "which of your answers are confusing" to "Clarity",
        "why are your answers so short now" to "Clarity", "give me answers in full again" to "Clarity", "don't shorten your answers" to "Clarity",
        "which answers did i find unclear" to "Clarity", "what answers do you shorten" to "Clarity", "are you shortening your answers" to "Clarity",
        "why are you giving me short answers" to "Clarity", "give full answers again" to "Clarity",
        // ---- Headroom ----
        "how much can i still lose today" to "Headroom", "kitna aur loss le sakta hoon" to "Headroom", "how much room do i have today" to "Headroom",
        "how many trades left" to "Headroom", "am i close to my limit" to "Headroom", "how close is my loss to the limit" to "Headroom",
        "am i allowed to trade more today" to "Headroom", "am i blocked from trading" to "Headroom",
        "kya main aur trade kar sakta hoon" to "Headroom", "kitne trade aur kar sakta hoon" to "Headroom", "am i overtrading" to "Headroom",
        "how much more can i trade" to "Headroom", "am i trading too much" to "Headroom", "how close am i to my limits" to "Headroom",
        "how close am i to my daily loss limit" to "Headroom", "how close am i to the loss limit" to "Headroom",
        "how much more can i lose" to "Headroom", "how many trades do i have left" to "Headroom", "how many more trades can i take" to "Headroom",
        "how many orders can i still place today" to "Headroom", "am i near my loss limit" to "Headroom", "am i close to my max loss" to "Headroom",
        "how much headroom do i have" to "Headroom", "show my headroom" to "Headroom", "kitne trade bache hain" to "Headroom",
        "limit se kitna door hoon" to "Headroom", "how much room is left today" to "Headroom", "where do i stand against my limits" to "Headroom",
        "am i within my limits" to "Headroom", "how much loss is left" to "Headroom", "kitna aur loss utha sakta hoon" to "Headroom",
        "kitne aur trade le sakta hoon" to "Headroom", "limit ke kitne paas hoon" to "Headroom", "jarvis how close am i to my limits" to "Headroom",
        "boss style how much can i still lose" to "Headroom", "kitne trade aur le sakta hoon aaj" to "Headroom",
        "am i allowed to take another trade" to "Headroom", "how close is my drawdown to my daily loss limit" to "Headroom",
        // ---- Account:NEED ----
        "make the case for holding my put" to "Account:NEED", "pros and cons of holding my call" to "Account:NEED",
        "does my put need nifty to fall" to "Account:NEED", "meri put kaam karegi kya" to "Account:NEED",
        "where do i start losing on my put" to "Account:NEED", "at what level does my call make money" to "Account:NEED",
        "at what nifty level do i break even" to "Account:NEED", "what nifty level do i need for profit" to "Account:NEED",
        "how much does nifty need to fall for my put" to "Account:NEED", "what needs to happen for my put to work" to "Account:NEED",
        "where is my breakeven" to "Account:NEED", "what does my iron condor need" to "Account:NEED",
        "at what price does my put start making money" to "Account:NEED", "where will my call break even" to "Account:NEED",
        "how far does nifty have to go for my call" to "Account:NEED", "how much does banknifty have to rise for my call to work" to "Account:NEED",
        "my breakeven" to "Account:NEED", "for my trade to work" to "Account:NEED", "for my trade to work what needs to happen" to "Account:NEED",
        "what is my breakeven" to "Account:NEED", "how far is my breakeven" to "Account:NEED",
        "what is the breakeven of my 24500 put" to "Account:NEED", "for my 24500 put to work what needs to happen" to "Account:NEED",
        "what does my call need" to "Account:NEED", "what has to be true for my position" to "Account:NEED",
        "what does my straddle need to make money" to "Account:NEED", "what does my short strangle need" to "Account:NEED",
        "for my short straddle to work what has to happen" to "Account:NEED", "breakeven of my iron condor" to "Account:NEED",
        "meri put ke liye kya hona chahiye" to "Account:NEED", "mere call ko profit ke liye kya chahiye" to "Account:NEED",
        "meri position ka breakeven kya hai" to "Account:NEED", "what must nifty do for my call to work" to "Account:NEED",
        "mera breakeven kahan hai" to "Account:NEED", "where does my position break even" to "Account:NEED",
        "how far is nifty from my breakeven" to "Account:NEED", "meri call ke liye kya hona chahiye" to "Account:NEED",
        "mera iron condor kab profit dega" to "Account:NEED", "how much does nifty have to fall for my put to pay off" to "Account:NEED",
        "how far does banknifty need to move for my straddle" to "Account:NEED",
        // ---- Account:HEALTH ----
        "is my put healthy" to "Account:HEALTH", "is my 24500 call okay" to "Account:HEALTH", "how is my 24500 put doing" to "Account:HEALTH",
        "check my positions" to "Account:HEALTH", "is my straddle safe" to "Account:HEALTH", "is my trade okay" to "Account:HEALTH",
        "are my trades fine" to "Account:HEALTH", "check my position" to "Account:HEALTH", "check on my positions" to "Account:HEALTH",
        "are my positions in danger" to "Account:HEALTH", "are any of my positions at risk" to "Account:HEALTH",
        "which of my positions is at risk" to "Account:HEALTH", "how is my put" to "Account:HEALTH", "are my positions okay" to "Account:HEALTH",
        "is my position safe" to "Account:HEALTH",
        // ---- TradeCase ----
        "make the case" to "TradeCase", "make the case for trading now" to "TradeCase", "pros and cons of trading now" to "TradeCase",
        "case for and against" to "TradeCase",
        // ---- DayClock ----
        "has the low usually been made by now" to "DayClock", "is the high of the day usually made in the first hour" to "DayClock",
        "does nifty usually make its high in the morning" to "DayClock", "is the low usually made in the first half hour" to "DayClock",
        "how often is the high made in the first hour" to "DayClock", "what time does nifty usually bottom" to "DayClock",
        "when is nifty most volatile" to "DayClock", "when is the market most volatile" to "DayClock",
        "how volatile is the first hour usually" to "DayClock", "is it usually quiet after lunch" to "DayClock",
        "how does nifty behave at lunch" to "DayClock", "lunch mein market shant rehta hai kya" to "DayClock",
        "sabse zyada movement kab hota hai" to "DayClock", "nifty sabse zyada kab hilta hai" to "DayClock",
        "when does nifty usually make its high" to "DayClock", "is the lunch hour usually quiet" to "DayClock",
        "is the market usually quiet at lunch" to "DayClock", "does banknifty usually make its low in the afternoon" to "DayClock",
        "how often is the low in by the first hour" to "DayClock", "when is banknifty most active" to "DayClock",
        "which hour moved the most today" to "DayClock", "has the high been made already" to "DayClock",
        "has the low been made by now" to "DayClock", "is it usually volatile at the open" to "DayClock",
        "when does nifty usually make its low" to "DayClock", "kab high banta hai" to "DayClock", "high usually kitne baje banta hai" to "DayClock",
        "busiest half hour" to "DayClock", "which half hour is the busiest" to "DayClock", "quietest time of day" to "DayClock",
        "when is the market most quiet" to "DayClock", "is the open usually the busiest" to "DayClock",
        "is the first half hour usually the most volatile" to "DayClock", "what's the day clock" to "DayClock",
        "how is the day clock for banknifty" to "DayClock", "time of day pattern for nifty" to "DayClock", "intraday seasonality" to "DayClock",
        "what time is the low usually made" to "DayClock", "is the high usually in by now" to "DayClock",
        "which half hour moves the most" to "DayClock", "busiest time of the day" to "DayClock", "quietest hour of the day" to "DayClock",
        "is the afternoon usually volatile" to "DayClock", "nifty ka high kab banta hai" to "DayClock", "nifty ka low kab banta hai" to "DayClock",
        "what time is most volatile" to "DayClock", "which hour is the most volatile" to "DayClock",
        "is the first half hour usually volatile" to "DayClock", "is the opening usually volatile" to "DayClock",
        "is the last hour usually busy" to "DayClock", "day clock" to "DayClock", "nifty day clock" to "DayClock", "time of day stats" to "DayClock",
        "which time of day moves the most for banknifty" to "DayClock", "what part of the day is the busiest" to "DayClock",
        "sabse busy time kab hota hai" to "DayClock", "how often does nifty make its low in the first hour" to "DayClock",
        "is the high usually made before lunch" to "DayClock", "does banknifty usually bottom in the morning" to "DayClock",
        "is the market usually volatile at the open" to "DayClock",
        // ---- Structure ----
        "aaj trend day hai kya" to "Structure", "range day hai ya nahi" to "Structure", "what's the structure today" to "Structure",
        "is today a trend day" to "Structure", "the structure today" to "Structure", "banknifty structure" to "Structure",
        "higher highs today" to "Structure", "is it a range day" to "Structure", "what's the trend so far" to "Structure",
        "trend so far" to "Structure", "what's the range so far" to "Structure", "is nifty making higher highs" to "Structure",
        "trend or range so far" to "Structure", "nifty ka structure kya hai" to "Structure",
        // ---- MarketDays ----
        "is the market open today" to "MarketDays", "aaj market khula hai kya" to "MarketDays", "kal expiry hai kya" to "MarketDays",
        "next holiday kab hai" to "MarketDays", "how many days to expiry" to "MarketDays", "days left to expiry" to "MarketDays",
        "time left for expiry" to "MarketDays", "is today a trading day" to "MarketDays", "is the market open on saturday" to "MarketDays",
        "what time does the market open tomorrow" to "MarketDays", "is tomorrow a holiday" to "MarketDays",
        "when is the next expiry" to "MarketDays", "how long till expiry" to "MarketDays",
        // ---- ChainDrift ----
        "how has max pain moved today" to "ChainDrift", "how has max pain moved" to "ChainDrift",
        "has the call wall moved since morning" to "ChainDrift", "how has the put wall shifted" to "ChainDrift",
        "where is the put wall now" to "ChainDrift", "call wall kahan hai" to "ChainDrift", "max pain kahan shift hua" to "ChainDrift",
        "max pain trend today" to "ChainDrift", "max pain shift since the open" to "ChainDrift", "has max pain moved up or down" to "ChainDrift",
        "how has the highest call oi strike changed" to "ChainDrift", "did the call wall move" to "ChainDrift",
        "is the put wall holding" to "ChainDrift", "put wall kahan shift hua" to "ChainDrift", "has max pain shifted" to "ChainDrift",
        "max pain since the open" to "ChainDrift", "max pain kitna shift hua" to "ChainDrift", "where is the call wall" to "ChainDrift",
        "is the call wall shifting" to "ChainDrift", "has the put wall moved" to "ChainDrift",
        "has the biggest call oi moved since morning" to "ChainDrift", "how has the chain drifted today" to "ChainDrift",
        "chain drift" to "ChainDrift", "is max pain moving up" to "ChainDrift", "max pain and the walls" to "ChainDrift",
        "max pain through the day" to "ChainDrift", "max pain kahan gaya" to "ChainDrift", "how has max pain drifted since the open" to "ChainDrift",
        "max pain kahan khiska" to "ChainDrift", "has the put wall shifted today" to "ChainDrift",
        // ---- ChainIntel ----
        "how has the chain changed since morning" to "ChainIntel", "how has oi changed since morning" to "ChainIntel",
        "how has the oi built up today" to "ChainIntel", "where is the biggest put oi now" to "ChainIntel", "what's the straddle at" to "ChainIntel",
        "how much is the straddle" to "ChainIntel", "what is the skew today" to "ChainIntel", "where is the biggest oi build up" to "ChainIntel",
        "which strike has the highest call oi" to "ChainIntel", "straddle breakeven" to "ChainIntel",
        "breakeven of the atm straddle" to "ChainIntel", "where is the biggest call oi" to "ChainIntel",
        "how has the oi shifted since morning" to "ChainIntel", "oi kaise shift hua" to "ChainIntel",
        "where is the most call writing" to "ChainIntel", "how has the pcr changed today" to "ChainIntel", "expected move by expiry" to "ChainIntel",
        "what's the iv skew" to "ChainIntel", "put writing kahan ho rahi hai" to "ChainIntel",
        // ---- Account:CHAIN ----
        "where is max pain" to "Account:CHAIN", "has oi shifted" to "Account:CHAIN", "where is fresh call writing" to "Account:CHAIN",
        "max pain kya hai" to "Account:CHAIN", "what is max pain now" to "Account:CHAIN", "max pain of banknifty" to "Account:CHAIN",
        "what's the pcr" to "Account:CHAIN", "how is the option chain" to "Account:CHAIN", "how is the option chain looking" to "Account:CHAIN",
        "read the option chain" to "Account:CHAIN", "max pain vs spot" to "Account:CHAIN", "how far is spot from max pain" to "Account:CHAIN",
        "is nifty near max pain" to "Account:CHAIN", "will nifty go to max pain" to "Account:CHAIN", "where is max pain heading" to "Account:CHAIN",
        "iv kya hai" to "Account:CHAIN", "call oi vs put oi" to "Account:CHAIN", "which strike has the most oi" to "Account:CHAIN",
        "what is the pcr" to "Account:CHAIN", "what is max pain" to "Account:CHAIN", "what's the max pain now" to "Account:CHAIN",
        "kya matlab hai pcr ka" to "Account:CHAIN", "what is the max pain for banknifty" to "Account:CHAIN",
        "option chain of nifty" to "Account:CHAIN", "analyze the option chain" to "Account:CHAIN", "will max pain shift" to "Account:CHAIN",
        "where is support from oi" to "Account:CHAIN", "where is resistance from the option chain" to "Account:CHAIN",
        "max pain kahan hai" to "Account:CHAIN",
        // ---- Account:RISK ----
        "what's my risk on my put" to "Account:RISK", "what's my loss limit" to "Account:RISK", "show my limits" to "Account:RISK",
        "loss limit kitna bacha hai" to "Account:RISK", "what is my max loss for the day" to "Account:RISK",
        "how much risk is left" to "Account:RISK", "is my risk ok" to "Account:RISK", "what is my daily loss limit" to "Account:RISK",
        "what are my risk limits" to "Account:RISK", "what is my trade limit" to "Account:RISK", "mera loss limit kya hai" to "Account:RISK",
        "is the guard on" to "Account:RISK", "is my strategy close to its limit" to "Account:RISK+STRATEGIES",
        "how much risk am i taking" to "Account:RISK",
        // ---- Their neighbours: the other features these words must stay with ----
        "how much can i lose if nifty falls 1%" to "Account:MOVE", "make the case for my position" to "Account:POSITIONS",
        "when was today's high" to "Market", "what time was the high today" to "Market", "aaj high kab bana" to "Market",
        "should i trade now" to "TradeCheck", "what if nifty falls 1%" to "Scenarios", "how much do i lose if nifty falls 1%" to "Account:MOVE",
        "is my call in profit" to "Account:PNL", "what time does the market close" to "OptionFacts",
        "when does the market usually reverse" to "Market", "what is the opening range" to "Glossary", "how is the first hour today" to "Market",
        "how volatile was the first hour today" to "Market", "how much did nifty move in the first hour" to "Market",
        "was lunch quiet today" to "Market", "is it quiet today" to "Market", "is the market quiet" to "Market", "is nifty quiet today" to "Market",
        "is nifty making new highs" to "Market", "has nifty made its high for the day" to "Market", "is the low in" to "Market",
        "is the high in for the day" to "Market", "nifty ka high ban gaya kya" to "Market", "aaj ka low ban chuka hai kya" to "Market",
        "what is the high of the day" to "Market", "how far is nifty from the day high" to "Market",
        "how much can i still lose if nifty falls 200 points" to "Account:MOVE", "what's the expected move" to "ExpectedRange",
        "what is today's trend" to "Market", "which way is nifty trending" to "Market", "range today" to "Market",
        "how big is today's range" to "Market", "today's high and low" to "Market", "day high day low" to "Market",
        "market kab band hoga" to "OptionFacts", "how are my positions looking" to "Account:POSITIONS",
        "is any position near its stop" to "Account:PROTECTIONS+POSITIONS", "what needs to happen today" to "Market",
        "what needs to happen for nifty to break out" to "Market", "what has to happen for banknifty to recover" to "Market",
        "what would it take for nifty to cross 25000" to "Odds", "what does nifty need to do to reach 25000" to "Market",
        "how far is nifty from 25000" to "Distance", "how much more does nifty need to fall to hit 24000" to "Market",
        "how is my day going" to "Account:PNL+POSITIONS+ORDERS+STRATEGIES", "what is my max loss on this trade" to "Account:PNL",
        "how many trades did i take" to "Account:ORDERS", "can i take another trade" to "Market", "should i take another trade" to "Market",
        "should i stop trading for the day" to "Account:PROTECTIONS", "what's the case for nifty going up" to "Market",
        "bull case for nifty" to "Market", "bear case for banknifty" to "Market", "talk me through my positions" to "Account:POSITIONS",
        "walk me through my trade" to "Account:EXPLAIN_POS", "explain my positions" to "Account:EXPLAIN_POS", "what is nifty at" to "Market",
        "what is my p&l" to "Account:PNL", "what was that about vix" to "Market", "what do you mean by max pain" to "Glossary",
        "what does pcr mean" to "Glossary", "repeat the levels" to "Market", "what was the nifty high" to "Market",
        "what was the high today" to "Market", "meaning of theta" to "Glossary", "which alerts do you hold back" to "AlertSense",
        "how much did i lose today" to "Account:PNL", "how is my position doing" to "Account:EXPLAIN_POS",
        "what if nifty falls 100 points what happens to my put" to "Account:MOVE", "what's the worst position" to "Account:RANK",
        "are my stops okay" to "Account:PROTECTIONS", "where was today's high" to "Market", "when did nifty make its high today" to "Market",
        "what is the day high" to "Market", "nifty day low" to "Market", "when does the market open" to "OptionFacts",
        "how much time is left in the session" to "OptionFacts", "how much time till close" to "OptionFacts",
        "market kitne baje band hoga" to "OptionFacts", "when is the best time to trade" to "Account:TIMEOFDAY",
        "where will max pain be at expiry" to "Outlook", "what is a call wall" to "Glossary", "how is nifty" to "Market",
        "what's the vix" to "Market", "why is nifty falling" to "Causes", "what is theta" to "Glossary",
        "how many lots can i buy with 50000" to "Sizing", "what's my pnl" to "Account:PNL", "show my positions" to "Account:POSITIONS",
        "how are my strategies doing" to "Account:BOTS", "stop all strategies" to "Act", "close all positions" to "Act", "kill switch on" to "Act",
        "buy 1 lot nifty 25000 ce" to "Act", "sell my put" to "Act", "square off my position" to "Act",
        "should i exit my position" to "Account:POSITIONS", "brief me" to "CoPilot", "what matters right now" to "CoPilot",
        "any contradictions" to "Consistency", "is it a good day to trade" to "TradeCheck", "what are my rules" to "AboutBoss",
        "how are you improving" to "Improve", "what have you learned this week" to "Learnings", "how well are you hearing me" to "Hearing",
        "can you hear me" to "Chat", "am i ready to trade" to "PreMarket", "which patterns work on nifty" to "PatternCalls",
        "what's the main news" to "NewsDesk", "how does the market react to rbi news" to "NewsMoves", "is your data fresh" to "DataAge",
        "what if vix goes to 20" to "Scenarios", "recap the day" to "DayStory", "how was my day" to "DaySummary",
        "help me journal today" to "DayJournal", "why so quiet" to "Airtime", "what's crude doing" to "Honest",
        "what time do i lose most" to "Account:TIMEOFDAY", "how did i do this week" to "Account:HISTORY", "mera pnl kitna hai" to "Account:PNL",
        "aaj kitne trade hue" to "Account:ORDERS",
        // ==== Round 11: round 10's open items, routed ====
        "talk me through my put" to "Account:EXPLAIN_POS", "explain my put" to "Account:EXPLAIN_POS", "how did my put do" to "Account:EXPLAIN_POS",
        "my put" to "Account:EXPLAIN_POS", "holiday kab hai" to "MarketDays", "how many trading days left this month" to "MarketDays",
        "expiry ka din kya hai" to "MarketDays", "make the case for buying calls" to "TradeCase", "breakeven kitna door hai" to "Account:NEED",
        "breakeven kahan hai" to "Account:NEED", "how many trading days are left this month" to "MarketDays",
        "is mahine kitne trading din bache hain" to "MarketDays", "which day is expiry" to "MarketDays",
        // ...and their neighbours stay where they were.
        "how did my calls do this week" to "Account:HISTORY", "how many trend days this month" to "MarketMemory",
        "my position" to "Account:POSITIONS", "is there an expiry today" to "MarketDays",
        // ---- GapRecord: the index's gap record ----
        "when nifty gaps up over 0.5% how often does it fill the gap by 11" to "GapRecord", "do gap downs usually fill" to "GapRecord",
        "what usually happens after a gap up" to "GapRecord", "gap fill rate for banknifty" to "GapRecord",
        "how often does a gap like today's fill" to "GapRecord", "nifty gap kitni baar bharta hai" to "GapRecord",
        "does the gap usually fill" to "GapRecord", "how often do gap ups fill by 10" to "GapRecord", "gap down ke baad kya hota hai" to "GapRecord",
        "do gaps fill on nifty" to "GapRecord", "what's the gap fill record" to "GapRecord", "how often does banknifty fill a gap down by noon" to "GapRecord",
        "gap up fill hota hai kya" to "GapRecord", "how often does nifty close above the open after a gap up" to "GapRecord",
        "gap fill stats" to "GapRecord", "gap record for nifty" to "GapRecord", "how often does a 1% gap fill" to "GapRecord",
        "do big gaps fill the same day" to "GapRecord", "how often does a gap down fill by 11" to "GapRecord",
        "do gap ups usually get filled by the close" to "GapRecord", "what happens after a gap down usually" to "GapRecord",
        "gap down fill rate" to "GapRecord", "how often does banknifty fill its gap" to "GapRecord",
        "how often does a 0.5% gap up fill by noon" to "GapRecord", "gap fill kitni baar hota hai" to "GapRecord",
        "does nifty usually fill a gap down" to "GapRecord", "how often does a gap like this fill" to "GapRecord",
        // Its neighbours: today's gap alone (never his order fills), a forecast.
        "has the gap filled" to "Gap", "will today's gap fill" to "Market",
        // ---- Weekdays: the index's weekday record ----
        "are mondays more volatile" to "Weekdays", "which day of the week moves the most" to "Weekdays", "how does nifty usually do on fridays" to "Weekdays",
        "are expiry days wider than other days" to "Weekdays", "weekday record for banknifty" to "Weekdays", "monday ko nifty kaisa chalta hai" to "Weekdays",
        "expiry ke din range zyada hota hai kya" to "Weekdays", "which weekday is the most volatile" to "Weekdays", "is friday usually a down day" to "Weekdays",
        "how does banknifty do on wednesdays" to "Weekdays", "are tuesdays quiet" to "Weekdays", "which day has the biggest range" to "Weekdays",
        "how volatile is thursday usually" to "Weekdays", "konsa din sabse zyada move hota hai" to "Weekdays", "friday ko market kaisa rehta hai" to "Weekdays",
        "are fridays volatile" to "Weekdays", "which weekday has the biggest range" to "Weekdays", "is monday usually volatile" to "Weekdays",
        "how do mondays go for banknifty" to "Weekdays", "expiry days vs other days" to "Weekdays", "is thursday usually the busiest" to "Weekdays",
        "which day is the most volatile" to "Weekdays", "kis din sabse zyada volatile hota hai" to "Weekdays", "are mondays calmer than fridays" to "Weekdays",
        "weekday stats for nifty" to "Weekdays", "is today's range normal for a monday" to "Market",
        // ---- MindChange: what would change Jarvis's read ----
        "what would change your mind" to "MindChange", "what would make you wrong" to "MindChange", "what would invalidate that read" to "MindChange",
        "when would that stop being true" to "MindChange", "aapka view kab badlega" to "MindChange", "what would make you change your view" to "MindChange",
        "what would prove you wrong" to "MindChange", "when does that read stop holding" to "MindChange", "what level would change your mind" to "MindChange",
        "is your read still valid" to "MindChange", "what would flip your view" to "MindChange", "tumhara view kab galat hoga" to "MindChange",
        "what would make you turn bearish" to "MindChange", "what would make you bullish" to "MindChange", "what would change your view on nifty" to "MindChange",
        "when would you be wrong" to "MindChange", "what invalidates your read" to "MindChange", "what would it take to change your mind" to "MindChange",
        "is that read still holding" to "MindChange", "aapka read kab galat hoga" to "MindChange", "what would make that read wrong" to "MindChange",
        // ---- Causes: why the market moved, weighed ----
        "why did nifty fall" to "Causes", "why is the market down today" to "Causes", "what caused the rally in banknifty" to "Causes",
        "reason for today's fall" to "Causes", "nifty kyun gira" to "Causes", "why did banknifty go up today" to "Causes",
        "what's behind today's fall" to "Causes", "why is nifty up" to "Causes", "market kyun gir raha hai" to "Causes",
        "what drove the market today" to "Causes", "why did the market crash" to "Causes", "banknifty kyun chadha" to "Causes",
        "what explains today's move" to "Causes", "why did sensex drop" to "Causes", "what moved the market today" to "Causes",
        "why did nifty go down today" to "Causes", "why is banknifty falling" to "Causes", "why did the market rally" to "Causes",
        "what caused today's fall" to "Causes", "what's driving the market today" to "Causes", "nifty aaj kyun gira" to "Causes",
        "why is sensex up today" to "Causes", "reason for the rally in nifty" to "Causes", "why did finnifty fall so much" to "Causes",
        "what caused the drop in nifty" to "Causes",
        // ---- WeekAhead: the week from the calendar ----
        "what does this week look like" to "WeekAhead", "week ahead" to "WeekAhead", "is this an expiry week" to "WeekAhead",
        "how many trading days this week" to "WeekAhead", "plan for next week" to "WeekAhead", "is hafte kya hai" to "WeekAhead",
        "agle hafte kya hai" to "WeekAhead", "what's coming up next week" to "WeekAhead", "any holidays this week" to "WeekAhead",
        "what's on this week" to "WeekAhead", "is there an expiry this week" to "WeekAhead", "how many sessions left this week" to "WeekAhead",
        "what's next week like" to "WeekAhead", "next week mein kya hai" to "WeekAhead", "anything big this week" to "WeekAhead",
        "what's this week like" to "WeekAhead", "how does next week look" to "WeekAhead", "any expiry next week" to "WeekAhead",
        "is next week an expiry week" to "WeekAhead", "week ahead for nifty" to "WeekAhead", "how many trading days next week" to "WeekAhead",
        "what's on next week" to "WeekAhead", "agle hafte expiry kab hai" to "WeekAhead", "is hafte kitne trading din hain" to "WeekAhead",
        "what does next week look like" to "WeekAhead",
        // ---- SaidAbout: Boss's own words read back ----
        "what did i say about banknifty" to "SaidAbout", "remind me what i said about expiry" to "SaidAbout",
        "did i note anything about the hammer last week" to "SaidAbout", "find my notes on fridays" to "SaidAbout",
        "maine expiry ke baare mein kya kaha tha" to "SaidAbout", "what did i write about overtrading" to "SaidAbout",
        "what have i said about gap ups" to "SaidAbout", "did i say anything about vix" to "SaidAbout", "show my notes about the orb" to "SaidAbout",
        "what did i note about fridays last week" to "SaidAbout", "maine banknifty ke baare mein kya likha tha" to "SaidAbout",
        "what did i say in my journal about revenge trading" to "SaidAbout", "what did i say about expiry" to "SaidAbout",
        "what did i write about banknifty last week" to "SaidAbout", "what have i noted about the orb" to "SaidAbout",
        "did i mention anything about gap ups" to "SaidAbout", "my notes on expiry" to "SaidAbout", "remind me what i said about fridays" to "SaidAbout",
        "what did i tell you about overtrading" to "SaidAbout", "maine fridays ke baare mein kya kaha tha" to "SaidAbout",
        "what did i say about vix this week" to "SaidAbout", "find my notes about revenge trading" to "SaidAbout",
        // A reminder with a time stays a reminder (it only speaks).
        "remind me at 3 pm what i said about expiry" to "Reminder", "remind me at 3 pm to check nifty" to "Reminder",
        // ---- ZerodhaSession: why the broker logged Boss out ----
        "why was i logged out of zerodha" to "ZerodhaSession", "why did kite log me out" to "ZerodhaSession",
        "what happened to my zerodha session" to "ZerodhaSession", "when does my zerodha session end" to "ZerodhaSession",
        "zerodha se logout kyun hua" to "ZerodhaSession", "why did zerodha disconnect" to "ZerodhaSession",
        "why does kite keep logging me out" to "ZerodhaSession", "kite logout kyun hua" to "ZerodhaSession",
        "when will my kite login expire" to "ZerodhaSession", "why did my zerodha login expire" to "ZerodhaSession",
        "why did zerodha log me out today" to "ZerodhaSession", "why was i kicked out of kite" to "ZerodhaSession",
        "why did my kite session end" to "ZerodhaSession", "kite se logout kyun hua" to "ZerodhaSession",
        "when does my kite session expire" to "ZerodhaSession", "how long does my zerodha login last" to "ZerodhaSession",
        "why am i getting logged out of zerodha" to "ZerodhaSession", "what happened with my kite login" to "ZerodhaSession",
        // Its neighbours: whether he is logged in now is the app's status.
        "am i still logged in to zerodha" to "Account:STATUS", "is my zerodha session still valid" to "Account:STATUS",
        // ---- AskedAgain: the market reads Boss asks again ----
        "which of your answers do i ask again" to "AskedAgain", "what do i keep asking twice" to "AskedAgain",
        "which of your market reads missed" to "AskedAgain", "kaun se jawab main dobara puchta hoon" to "AskedAgain",
        "what do i ask you again and again" to "AskedAgain", "which answers did i have to ask twice" to "AskedAgain",
        "what questions do i repeat" to "AskedAgain", "which reads do i ask again" to "AskedAgain",
        "which of your answers did i ask twice" to "AskedAgain", "what do i ask you twice" to "AskedAgain",
        "which of your reads missed" to "AskedAgain", "main kya dobara puchta hoon" to "AskedAgain", "which market reads do i ask again" to "AskedAgain",
        // ---- WordFit: his confidence words against his numbers ----
        "how well do your words match your numbers" to "WordFit", "what do you mean by usually" to "WordFit",
        "say your confidence words as written" to "WordFit", "match your words to the numbers again" to "WordFit",
        "what does often mean when you say it" to "WordFit", "are your confidence words accurate" to "WordFit",
        "do your words match the numbers" to "WordFit", "what do you mean by rarely" to "WordFit",
        "when you say usually how often is that" to "WordFit", "how well do your confidence words match your numbers" to "WordFit",
        "what do you mean by often" to "WordFit", "what do you mean when you say rarely" to "WordFit", "how often is usually" to "WordFit",
        "stop correcting your confidence words" to "WordFit", "are your confidence words calibrated" to "WordFit",
        "don't correct your confidence words" to "WordFit",
        // ---- Account:STREAKS: Boss's own runs and weekdays ----
        "am i on a winning streak" to "Account:STREAKS", "how many green days in a row" to "Account:STREAKS", "my losing streak" to "Account:STREAKS",
        "what's my best weekday" to "Account:STREAKS", "which day of the week do i lose most" to "Account:STREAKS",
        "lagatar kitne din loss hua" to "Account:STREAKS", "mera konsa din best hai" to "Account:STREAKS",
        "what's my longest winning streak" to "Account:STREAKS", "how many losing days in a row" to "Account:STREAKS",
        "am i on a losing streak" to "Account:STREAKS", "what's my worst day of the week" to "Account:STREAKS",
        "how many trades have i won in a row" to "Account:STREAKS", "which weekday do i make the most money" to "Account:STREAKS",
        "mera winning streak kitna hai" to "Account:STREAKS", "lagatar kitne din profit hua" to "Account:STREAKS", "what's my streak" to "Account:STREAKS",
        "do i lose more on mondays" to "Account:STREAKS", "my best day of the week for trading" to "Account:STREAKS",
        "how many green days in a row have i had" to "Account:STREAKS", "what's my winning streak" to "Account:STREAKS",
        "how many losing trades in a row" to "Account:STREAKS", "how many red days in a row" to "Account:STREAKS",
        "which weekday is my best" to "Account:STREAKS", "which day of the week do i make most" to "Account:STREAKS",
        "mera losing streak kitna hai" to "Account:STREAKS", "lagatar kitne din profit" to "Account:STREAKS",
        "how many days in a row have i been green" to "Account:STREAKS", "what's my best day of the week" to "Account:STREAKS",
        "do i do better on fridays" to "Account:STREAKS", "am i on a green streak" to "Account:STREAKS", "how many winning days in a row" to "Account:STREAKS",
        // An index's run stays the candles' Streak.
        "nifty streak" to "Streak", "how many days in a row has nifty fallen" to "Streak", "banknifty losing streak" to "Streak",
        "how many days in a row has banknifty risen" to "Streak", "how many red days in a row for nifty" to "Streak",
        // ---- Round 12. RangeBreaks: the opening-range breakout record ----
        "do orb breakouts fail often" to "RangeBreaks", "how often does the opening range breakout work on banknifty" to "RangeBreaks",
        "how often does nifty break the opening range and reverse" to "RangeBreaks", "what's the orb breakout success rate" to "RangeBreaks",
        "orb breakout stats for nifty" to "RangeBreaks", "how reliable is the opening range breakout" to "RangeBreaks",
        "does the first 15 minute range usually hold" to "RangeBreaks", "how often does a break of the first hour range hold" to "RangeBreaks",
        "opening range breakout kitni baar kaam karta hai" to "RangeBreaks", "how often does the opening range breakdown hold" to "RangeBreaks",
        "when banknifty breaks below the opening range how often does it close there" to "RangeBreaks",
        "how often does the first 30 minute range hold on sensex" to "RangeBreaks", "is the opening range breakout reliable on finnifty" to "RangeBreaks",
        "opening range breakdown kitni baar tikta hai" to "RangeBreaks", "what percentage of opening range breakouts hold till close" to "RangeBreaks",
        // Its neighbours: today's own opening range, his ORB arms, the meaning.
        "did nifty break the opening range" to "OpeningRange", "what's the opening range today" to "OpeningRange",
        "is nifty above the opening range" to "OpeningRange", "will nifty break the opening range today" to "OpeningRange",
        "how is my orb arm doing" to "Account:BOTS", "how did my orb strategy do" to "Account:STRATEGIES",
        "what is an opening range breakout" to "PatternExpert",
        // ---- DayCompare: today against an earlier session ----
        "nifty today vs friday" to "DayCompare", "how does today compare to last thursday" to "DayCompare",
        "is today more of a trend day than yesterday" to "DayCompare", "what's different about today from yesterday" to "DayCompare",
        "how is banknifty today different from yesterday" to "DayCompare", "compare today and friday for banknifty" to "DayCompare",
        "difference between today and yesterday" to "DayCompare", "today versus yesterday on sensex" to "DayCompare",
        "kal aur aaj mein kya farak hai" to "DayCompare", "aaj parso se kaise alag hai" to "DayCompare",
        "how was today different from monday" to "DayCompare", "is today any different from yesterday" to "DayCompare",
        "how did today stack up against yesterday" to "DayCompare", "comparison between today and friday" to "DayCompare",
        // Its neighbours: today alone, his own P&L, two indices.
        "compare my pnl today with yesterday" to "Account:HISTORY", "compare nifty and banknifty" to "Compare",
        // ---- Account:NUMBERS: Boss's own trading numbers ----
        "what's my profit factor" to "Account:NUMBERS", "what is my expectancy" to "Account:NUMBERS", "what's my risk reward" to "Account:NUMBERS",
        "what is my average loss this month" to "Account:NUMBERS", "show me my trading numbers" to "Account:NUMBERS",
        "what is my average win this week" to "Account:NUMBERS", "meri trading stats batao" to "Account:NUMBERS",
        "average profit per trade kitna hai mera" to "Account:NUMBERS", "do i book my profits too early" to "Account:NUMBERS",
        "am i cutting my winners too early" to "Account:NUMBERS", "what's my payoff ratio" to "Account:NUMBERS",
        "my win loss ratio last month" to "Account:NUMBERS", "how much do i lose per trade on average" to "Account:NUMBERS",
        "mera average profit kitna hai" to "Account:NUMBERS",
        // Its neighbours: the review's win rate, the day's P&L, his limits, an index's range.
        "what's my win rate" to "Account:REVIEW", "what is my biggest loss" to "Account:PNL", "what is my max loss limit" to "Account:RISK",
        "what's the average range of nifty" to "Market",
        // ---- Tour: which questions to ask ----
        "what should i ask you now" to "Tour", "what else can i ask you" to "Tour", "any questions i should ask" to "Tour",
        "what kind of things can i ask you" to "Tour", "suggest some good questions" to "Tour", "aapse kya pooch sakta hoon" to "Tour",
        "sawal suggest karo" to "Tour", "take me on a tour" to "Tour",
        // ---- FigureFirst: the figure said first ----
        "which answers do you start with the number" to "FigureFirst", "why do you start with the number first" to "FigureFirst",
        "don't start with the number" to "FigureFirst", "why are you saying the level first" to "FigureFirst",
        "which reads do you say the figure first" to "FigureFirst", "dont say the number first anymore" to "FigureFirst",
        "kaun se jawab mein number pehle bolte ho" to "FigureFirst", "what level is nifty at" to "Market",
        // ---- OutsideApp: anything outside IraAlgo, said politely (never done) ----
        "open youtube" to "OutsideApp", "youtube kholo" to "OutsideApp", "whatsapp kholo" to "OutsideApp", "launch spotify" to "OutsideApp",
        "play some music" to "OutsideApp", "gaana bajao" to "OutsideApp", "play a song on youtube" to "OutsideApp", "call mom" to "OutsideApp",
        "mummy ko call karo" to "OutsideApp", "message rahul" to "OutsideApp", "send a whatsapp to rahul" to "OutsideApp",
        "text my wife" to "OutsideApp", "open google" to "OutsideApp", "open instagram" to "OutsideApp", "open the camera" to "OutsideApp",
        "book an uber" to "OutsideApp", "order food from swiggy" to "OutsideApp", "jarvis open youtube" to "OutsideApp",
        "can you open whatsapp for me" to "OutsideApp", "send rahul a message" to "OutsideApp", "ring dad" to "OutsideApp",
        "put on some music" to "OutsideApp", "spotify chalao" to "OutsideApp", "start the music" to "OutsideApp", "take a screenshot" to "OutsideApp",
        // Its neighbours: the app's own screens and words, and the commands beside them.
        "open the chain" to "Account:CHAIN", "open settings" to "Account:SETTINGS", "open my positions" to "Account:POSITIONS",
        "call oi kahan hai" to "Account:CHAIN", "call writing kahan hai" to "ChainIntel", "put call ratio" to "Account:CHAIN",
        "open zerodha" to "Account:STATUS", "start orb" to "Act", "play the alert sound" to "Account:ALARMS", "open orders" to "Account:ORDERS",
        // ==== Round 13: round 12's open items, routed ====
        "what all can you do" to "Help", "what else can you do" to "Help", "what can you do" to "Help",
        "how was yesterday for nifty" to "DayCompare", "how was nifty yesterday" to "DayCompare", "how did banknifty do yesterday" to "DayCompare",
        "how was the market yesterday" to "DayCompare", "kal nifty kaisa tha" to "DayCompare", "how did sensex close yesterday" to "DayCompare",
        "what is the average price of my put" to "Account:POSITIONS", "what's my average price on the 24500 put" to "Account:POSITIONS",
        "meri put ka average price kya hai" to "Account:POSITIONS", "what is the entry price of my call" to "Account:POSITIONS",
        "meri put explain karo" to "Account:EXPLAIN_POS",
        // ...and their neighbours: his own yesterday, the index's prior-day figures.
        "how was yesterday" to "Account:HISTORY", "how was my day yesterday" to "Account:HISTORY", "what was yesterday's high" to "Lookback",
        "did nifty break yesterday's high" to "Lookback", "how far is nifty from yesterday's high" to "Lookback",
        // ---- Round 13. OrderWhy: what became of his order ----
        "why was my banknifty order rejected" to "OrderWhy", "why did my stop loss order get cancelled" to "OrderWhy",
        "why was my sell order cancelled" to "OrderWhy", "why did my paper order get rejected" to "OrderWhy",
        "what happened to my banknifty order" to "OrderWhy", "what happened to my 10:15 order" to "OrderWhy", "order reject kyon hua" to "OrderWhy",
        "mera stop loss cancel kyun hua" to "OrderWhy", "reason for the rejection of my order" to "OrderWhy", "what was the rejection reason" to "OrderWhy",
        "why did my zerodha order fail" to "OrderWhy", "why was my order not placed" to "OrderWhy", "why didn't my order go through" to "OrderWhy",
        "mere order ka kya hua" to "OrderWhy", "why was the target order cancelled" to "OrderWhy", "why did my order get cancelled today" to "OrderWhy",
        "why was my nifty buy order rejected" to "OrderWhy", "why did my orb order get cancelled" to "OrderWhy", "why was my exit order rejected" to "OrderWhy",
        "what happened to my last paper order" to "OrderWhy", "why did kite reject my order" to "OrderWhy",
        "why did my 24500 put order get rejected" to "OrderWhy",
        // Its neighbours: the day's orders, and the cancel itself (a command, through its own confirm).
        "how many orders did i place today" to "Account:ORDERS", "show my orders" to "Account:ORDERS", "cancel my last order" to "Act",
        // ---- BotTrades: his bots' trades today, explained ----
        "explain my bots' trades today" to "BotTrades", "why did orb take that trade" to "BotTrades", "what did my bots do today" to "BotTrades",
        "did my bots follow their rules" to "BotTrades", "why did range fade exit" to "BotTrades", "walk me through orb's trades" to "BotTrades",
        "what trades did orb take" to "BotTrades", "are my bots fighting each other" to "BotTrades", "did my bots take opposite sides" to "BotTrades",
        "mere bots ne aaj kya kiya" to "BotTrades", "orb ne trade kyun liya" to "BotTrades", "today's bot trades" to "BotTrades",
        "why did the liquidity bot exit" to "BotTrades", "did orb stick to its rules today" to "BotTrades",
        "any contradictions between my bots" to "BotTrades", "why did my bots lose today" to "BotTrades",
        "explain the trades my bots took today" to "BotTrades", "why did orb go long today" to "BotTrades",
        "were my bots' trades within their rules" to "BotTrades", "what did range fade do today" to "BotTrades", "break down the orb trades" to "BotTrades",
        "bots ke trades samjhao" to "BotTrades", "what did the liquidity bot do today" to "BotTrades", "why did my bots trade today" to "BotTrades",
        // Its neighbours: how they are doing, which are armed, another day, a backtest, a stop.
        "how are my bots doing" to "Account:BOTS", "which bots are armed" to "Account:STRATEGIES", "what did my bots do yesterday" to "Account:HISTORY+STRATEGIES",
        "backtest orb" to "Backtest", "stop orb" to "Act", "disarm range fade" to "Act",
        // ---- WrongThing: the questions Jarvis answered with the wrong thing ----
        "what did you get wrong today" to "WrongThing", "which questions did you answer wrong" to "WrongThing",
        "what did you misunderstand today" to "WrongThing", "that's not what i asked" to "WrongThing", "galat jawab" to "WrongThing",
        "ye nahi poocha maine" to "WrongThing", "you answered the wrong thing" to "WrongThing", "which of my questions did you get wrong" to "WrongThing",
        "what did you answer wrongly today" to "WrongThing", "which questions did you take the wrong way" to "WrongThing",
        "what did you get wrong this week" to "WrongThing", "aaj tumne kya galat samjha" to "WrongThing", "what mistakes did you make today" to "WrongThing",
        "what have you got wrong this week" to "WrongThing", "which questions did you misunderstand today" to "WrongThing",
        "where did you misunderstand me" to "WrongThing", "that was the wrong answer" to "WrongThing", "not what i meant" to "WrongThing",
        "aaj kya galat jawab diya" to "WrongThing",
        // Its neighbours: Jarvis's mistakes list, and marking the last answer wrong (a note, the existing command).
        "what did you get wrong" to "Account:MISTAKES", "you got it wrong" to "Act",
        // ---- OutsideApp, more of it ----
        "call my brother" to "OutsideApp", "play music on spotify" to "OutsideApp", "open gmail" to "OutsideApp", "open the calculator" to "OutsideApp",
        "book a cab" to "OutsideApp", "instagram kholo" to "OutsideApp", "open chrome" to "OutsideApp", "open netflix" to "OutsideApp",
        "whatsapp khol do" to "OutsideApp", "play a song" to "OutsideApp", "take a selfie" to "OutsideApp", "turn on the flashlight" to "OutsideApp",
        "order a pizza" to "OutsideApp", "call priya" to "OutsideApp", "send an email to priya" to "OutsideApp",
        // ---- TrendReads: Jarvis's own trend and range reads, scored ----
        "how often were your trend reads right this month" to "TrendReads", "how accurate are your structure reads" to "TrendReads",
        "did your trend calls hold" to "TrendReads", "your trend read record" to "TrendReads", "tumhare trend reads kitne sahi the" to "TrendReads",
        "how many of your trend reads held this week" to "TrendReads", "were your range reads right today" to "TrendReads",
        "how good are your trend calls" to "TrendReads", "your structure read record for last month" to "TrendReads",
        "how often are your trend reads wrong" to "TrendReads", "did your range calls hold today" to "TrendReads",
        "how accurate were your trend reads" to "TrendReads", "are your trend reads accurate" to "TrendReads",
        "how reliable are your trend calls" to "TrendReads", "score your trend reads" to "TrendReads", "trend call accuracy" to "TrendReads",
        "how many of your range reads were right" to "TrendReads", "did your structure reads hold up this month" to "TrendReads",
        "trend calls kaise rahe" to "TrendReads",
        // ---- ArmHabits: what Boss does with his bots after losing days ----
        "do i usually disarm my bots after losses" to "ArmHabits", "do i keep orb armed after losses" to "ArmHabits",
        "when do i usually disarm range fade" to "ArmHabits", "which bots do i keep armed" to "ArmHabits", "my arming habits" to "ArmHabits",
        "do i give up on my bots too fast" to "ArmHabits", "loss ke baad main bot band karta hoon kya" to "ArmHabits",
        "do i switch off orb after losing days" to "ArmHabits", "what do i do with my bots after a loss" to "ArmHabits",
        "do i turn off my bots after two losing days" to "ArmHabits", "how quickly do i disarm a losing bot" to "ArmHabits",
        "do i usually switch off orb after a loss" to "ArmHabits", "what do i usually do after losing days" to "ArmHabits",
        "my disarming habits" to "ArmHabits", "do i keep range fade on after losses" to "ArmHabits",
        "after how many losing days do i disarm orb" to "ArmHabits", "which strategies do i usually switch off after losses" to "ArmHabits",
        "how do i handle my bots after a losing day" to "ArmHabits",
        // ---- PriorDay: the prior day's high and low record ----
        "when nifty takes out yesterday's high in the first hour how often does it close above it" to "PriorDay",
        "how often does nifty break the previous day's high" to "PriorDay", "does banknifty usually hold below yesterday's low after breaking it" to "PriorDay",
        "pdh pdl record" to "PriorDay", "kal ka high todne ke baad kitni baar upar band hota hai" to "PriorDay",
        "how often does nifty close above the prior day high" to "PriorDay", "how often does yesterday's low get taken out" to "PriorDay",
        "prior day high record for banknifty" to "PriorDay", "does a break of yesterday's high usually hold" to "PriorDay",
        "how often does nifty reverse after breaking the previous day's low" to "PriorDay", "how often does nifty take out yesterday's high" to "PriorDay",
        "does nifty usually close above yesterday's high after breaking it" to "PriorDay",
        "how often does banknifty break yesterday's low in the first hour" to "PriorDay", "previous day high low record" to "PriorDay",
        "how often does a break of the prior day low reverse" to "PriorDay",
        // ---- LastHour: the last hour's record ----
        "how often does the last hour continue the day's direction" to "LastHour", "does nifty usually reverse in the last hour" to "LastHour",
        "on up days does banknifty extend in the closing hour" to "LastHour", "last hour record for sensex" to "LastHour",
        "aakhri ghante mein kitni baar palat-ta hai" to "LastHour", "how often does nifty reverse in the last hour" to "LastHour",
        "does the last hour usually follow the trend" to "LastHour", "on down days does nifty keep falling in the last hour" to "LastHour",
        "closing hour record for nifty" to "LastHour", "how often does the last hour undo the whole day" to "LastHour",
        "how often does the last hour reverse on down days" to "LastHour", "does banknifty usually continue in the last hour" to "LastHour",
        "power hour record" to "LastHour", "how often does the closing hour fade the day" to "LastHour",
        "does the final hour usually give back the day's move" to "LastHour", "how often is the last hour the same direction as the day" to "LastHour",
        // Its neighbours: today's last hour, and how busy it usually is.
        "how did nifty do in the last hour today" to "Moves",
        // ---- InsideDays: the inside-day and narrow-range record ----
        "after an inside day how often does nifty's range expand the next day" to "InsideDays", "how often does an nr7 day lead to a bigger day" to "InsideDays",
        "inside day record for banknifty" to "InsideDays", "do narrow range days usually break out the next day" to "InsideDays",
        "inside day ke baad kitni baar bada move aata hai" to "InsideDays", "what happens after an inside day" to "InsideDays",
        "nr7 record for nifty" to "InsideDays", "after a narrow range day does nifty usually expand" to "InsideDays",
        "how often does banknifty break out after an inside day" to "InsideDays", "how often do inside days lead to a trend day" to "InsideDays",
        "nr7 stats for banknifty" to "InsideDays", "do inside days usually expand the next day" to "InsideDays",
        "how often does a narrow day lead to a breakout" to "InsideDays", "what usually happens after an nr7 day" to "InsideDays",
        "do tight range days usually lead to a big move" to "InsideDays",
        // Its neighbours: one day's own shape, the candle pattern.
        "is today an inside day" to "Market", "what is an inside bar" to "PatternExpert",
        // ---- Account:NUMBERS, more of it ----
        "what's my average win and average loss" to "Account:NUMBERS", "what's my risk reward on my trades" to "Account:NUMBERS",
        "my profit factor" to "Account:NUMBERS", "my expectancy" to "Account:NUMBERS", "how much do i make per trade" to "Account:NUMBERS",
        "do i hold my losers longer than my winners" to "Account:NUMBERS", "do i cut my winners short" to "Account:NUMBERS",
        "my trading stats" to "Account:NUMBERS", "mera average loss kitna hai" to "Account:NUMBERS", "what's my average win last month" to "Account:NUMBERS",
        "what's my profit factor this week" to "Account:NUMBERS", "how long do i hold my winners" to "Account:NUMBERS",
        "what's my average loss" to "Account:NUMBERS", "what's my reward to risk" to "Account:NUMBERS", "do i hold my losers too long" to "Account:NUMBERS",
        "do i book my winners too early" to "Account:NUMBERS", "my win loss size" to "Account:NUMBERS", "what's my expectancy this month" to "Account:NUMBERS",
        "how much do i make on average per trade" to "Account:NUMBERS", "mera average profit kya hai" to "Account:NUMBERS",
        "how long do i hold my losers" to "Account:NUMBERS",
    )

    @Test fun eachQuestionGoesWhereItShould() {
        assertTrue(ASKED.size >= 600, "${ASKED.size}")
        assertEquals(ASKED.size, ASKED.map { it.first }.distinct().size)
        val wrong = ASKED.mapNotNull { (s, want) -> audit.feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got ${hits(s)}" } }
        assertTrue(wrong.isEmpty(), "taken by the wrong feature (${wrong.size} of ${ASKED.size}):\n" + wrong.joinToString("\n"))
    }

    @Test fun noCollisionGoesToTheWrongFamily() {
        // Caught by two or more families: the hub's order must give the one meant (the first family that takes it).
        val wrong = ASKED.mapNotNull { (s, want) ->
            val h = hits(s)
            if (h.size < 2 || h.none { want.startsWith(it) } || want.startsWith(h.first())) null
            else "\"$s\": $h - the hub gives ${h.first()}, wanted $want"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        // The hunt did meet collisions (Headroom over the account's RISK, NEED over HEALTH, ChainDrift over ChainIntel...).
        assertTrue(ASKED.count { hits(it.first).size >= 2 } >= 20)
    }

    // ---- The audit's order is the hub's: read from IraHub.ask itself when the app's source is beside this module ----

    /** The question branches of IraHub.ask between the `bundled` read and the Plan block, in [CoverageTest.feature]'s order. */
    private val HUB_ORDER = listOf("DayJournal", "AlertSense", "Airtime", "Hearing", "PatternCalls", "TrendReads", "OutsideApp", "Clarity", "WordFit", "AskedAgain", "FigureFirst", "WrongThing", "ArmHabits", "MorningSense", "HonestStars", "NewsMoves",
        "TaxRecords.exportAsked", "Learnings", "Learnings.undoAsked", "PreMarket", "Headroom", "BotTrades", "SwitchOff", "SaidAbout", "WeekAhead", "ZerodhaSession", "OrderWhy", "RelayHealth", "StreamHealth", "Tour", "DataAge", "Honest", "Thinking",
        "SelfWhy", "Consistency", "CoPilot", "SinceMorning", "ChainDrift", "ChainIntel", "DayClock", "GapRecord", "RangeBreaks", "PriorDay", "LastHour", "InsideDays", "FirstMove", "VixNext", "Weekdays", "DayCompare", "Structure", "MindChange", "Breadth",
        "TradeCase", "Scenarios", "Causes", "Agenda", "Improve")

    @Test fun theAuditFollowsTheHubsOrderAndEveryBranchIsGuarded() {
        val hub = java.io.File("../app/src/main/java/com/optionslab/app/ira/IraHub.kt").takeIf { it.isFile }?.readText() ?: return
        val from = hub.indexOf("val bundled = runCatching"); val to = hub.indexOf("com.optionslab.ira.Plan.steps(q)", from)
        assertTrue(from > 0 && to > from)
        // Stretches of the branches live in IraHub's own private functions (`if (askedOfX(q, parsed, bundled, understood)) return`,
        // kept small for the compiler): each call is read as that function's body, in place, so the order and every guard
        // are checked across them just the same.
        val helper = Regex("if \\((\\w+)\\(q, parsed, bundled, understood\\)\\) return")
        val inlined = mutableListOf<String>()
        val body = helper.replace(hub.substring(from, to)) { m ->
            val name = m.groupValues[1]; inlined += name
            val head = hub.indexOf("private fun $name(q: String, parsed: ")
            assertTrue(head > 0, "no private fun $name in IraHub")
            val open = hub.indexOf("{\n", head) + 2; val end = hub.indexOf("\n        return false\n    }\n", open)
            assertTrue(end > open, "$name: no closing `return false`")
            hub.substring(open, end)
        }
        assertTrue(inlined.size >= 6 && inlined.distinct() == inlined, "$inlined")
        assertTrue(!helper.containsMatchIn(body))
        val calls = Regex("com\\.optionslab\\.ira\\.(\\w+)\\.(asked|exportAsked|undoAsked)\\(q\\)").findAll(body).toList()
        val order = calls.map { it.groupValues[1] + (if (it.groupValues[2] == "asked") "" else "." + it.groupValues[2]) }.distinct()
        assertEquals(HUB_ORDER, order)
        // Every question branch there keeps the guard: not said with something to do, no order, no command. (SelfWhy is
        // read only inside Thinking's own guarded branch.)
        for (c in calls) {
            if (c.groupValues[1] == "SelfWhy") continue
            val head = body.substring(0, c.range.first).let { it.substring(maxOf(it.lastIndexOf(" if ("), it.lastIndexOf("= if ("))) }
            for (g in listOf("!bundled", "parsed.order == null", "parsed.command == null")) assertTrue(g in head, "${c.groupValues[1]}: $g")
        }
        // HeardBack is the voice path's own read-back (JarvisVoice), never a question branch of the hub.
        assertTrue("HeardBack" !in body)
    }

    // ---- Clarity's "what?": only the bare "what?" family, never a real question that starts with "what" ----

    private val UNCLEAR = listOf("what", "what?", "huh", "kya", "come again", "say that again", "what did you say", "what does that mean",
        "what do you mean", "pardon", "sorry what", "what was that", "i didn't understand", "samjha nahi", "samajh nahi aaya", "matlab",
        "kya bola", "phir se", "dobara bolo", "i didn't get that", "you lost me", "that was confusing", "jarvis what")
    private val NOT_UNCLEAR = listOf("what is nifty at", "what is the pcr", "what is max pain", "what is my breakeven", "what was that about vix",
        "what did you say about banknifty", "what do you mean by max pain", "what does pcr mean", "what do you mean nifty is weak",
        "what's the structure today", "what time is the low usually made", "what needs to happen for my put to work", "what about banknifty",
        "what now", "what's up", "what if nifty falls 1%", "what are my risk limits", "what matters right now", "kya matlab hai pcr ka",
        "matlab kya hai", "kya bola tumne", "phir se bolo nifty ka level", "i didn't understand the levels", "repeat the levels",
        "what was the high today", "what is a call wall", "which answers do you keep short", "say your answers in full again", "tell me more", "go on")

    @Test fun onlyTheBareWhatIsUnclear() {
        for (s in UNCLEAR) { assertTrue(Clarity.unclear(s), s); assertEquals(null, Clarity.asked(s), s) }
        for (s in NOT_UNCLEAR) assertTrue(!Clarity.unclear(s), s)
        // Asking about the shorter answers is never the "what?" itself, and never anything that acts.
        for (s in listOf("which answers do you keep short", "are you shortening your answers", "give full answers again"))
            assertTrue(!Clarity.unclear(s) && Ask.parse(s).command == null && Ask.parse(s).order == null, s)
    }

    // ---- Round 10's new wordings: none acts ----

    /** The newest features' natural variants added this round, with the feature each must get. */
    private val ROUND10 = listOf(
        "has the low usually been made by now" to "DayClock", "is the high of the day usually made in the first hour" to "DayClock",
        "does nifty usually make its high in the morning" to "DayClock", "how often is the high made in the first hour" to "DayClock",
        "what time does nifty usually bottom" to "DayClock", "when is nifty most volatile" to "DayClock", "is it usually quiet after lunch" to "DayClock",
        "lunch mein market shant rehta hai kya" to "DayClock", "sabse zyada movement kab hota hai" to "DayClock", "nifty sabse zyada kab hilta hai" to "DayClock",
        "kab high banta hai" to "DayClock", "is the open usually the busiest" to "DayClock", "has the high been made already" to "DayClock",
        "aaj trend day hai kya" to "Structure", "what's the trend so far" to "Structure",
        "is my put healthy" to "Account:HEALTH", "how is my 24500 put doing" to "Account:HEALTH", "are any of my positions at risk" to "Account:HEALTH",
        "does my put need nifty to fall" to "Account:NEED", "at what nifty level do i break even" to "Account:NEED",
        "make the case for holding my put" to "Account:NEED", "meri put kaam karegi kya" to "Account:NEED", "where do i start losing on my put" to "Account:NEED",
        "mera iron condor kab profit dega" to "Account:NEED",
        "am i allowed to take another trade" to "Headroom", "kya main aur trade kar sakta hoon" to "Headroom", "am i overtrading" to "Headroom",
        "how close is my loss to the limit" to "Headroom", "how much can i lose if nifty falls 1%" to "Account:MOVE",
        "are you keeping your answers short" to "Clarity", "can you give full answers" to "Clarity",
        "max pain trend today" to "ChainDrift", "where is max pain" to "Account:CHAIN", "case for and against" to "TradeCase",
        "what is a call wall" to "Glossary",
    )

    @Test fun roundTenWordingsNeitherOrderNorCommandNorBundle() {
        assertTrue(ROUND10.size >= 10)
        for ((s, want) in ROUND10) {
            assertEquals(want, audit.feature(s), s)
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Bundle.acts(s), s)
            assertEquals(null, Intents.quick(s), s)
            assertTrue(!Reminder.asked(s) && !Reminder.cancelAsked(s) && !FollowUp.acts(s), s)
            assertTrue(Understand.questions(null, s).orEmpty().none { FollowUp.acts(it) || Ask.parse(it).command != null || Ask.parse(it).order != null }, s)
        }
        // A change of a limit stays out of Headroom; the orders beside these words still act as before (each its own confirm).
        for (s in listOf("change my loss limit", "increase my trade limit to 10", "set my daily loss limit to 5000")) assertEquals(null, Headroom.asked(s), s)
        for (s in listOf("sell my put", "close my call", "exit my put if it falls 50", "square off my position")) {
            assertEquals(false, NeedsTrue.asked(s), s); assertEquals(false, PositionHealth.asked(s), s); assertEquals("Act", audit.feature(s), s)
        }
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("is my put healthy then close all positions", "am i overtrading, then stop all strategies",
            "when is nifty most volatile and kill switch on"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
    }

    // ---- Round 11's wordings: routed, and none acts ----

    /** Round 10's open items and the newest families' wordings fixed this round, with the feature each must get. */
    private val ROUND11 = listOf(
        "talk me through my put" to "Account:EXPLAIN_POS", "explain my put" to "Account:EXPLAIN_POS", "how did my put do" to "Account:EXPLAIN_POS",
        "my put" to "Account:EXPLAIN_POS", "holiday kab hai" to "MarketDays", "how many trading days left this month" to "MarketDays",
        "expiry ka din kya hai" to "MarketDays", "make the case for buying calls" to "TradeCase", "breakeven kitna door hai" to "Account:NEED",
        "breakeven kahan hai" to "Account:NEED", "is mahine kitne trading din bache hain" to "MarketDays",
        "gap down ke baad kya hota hai" to "GapRecord", "do gaps fill on nifty" to "GapRecord", "gap up fill hota hai kya" to "GapRecord",
        "do big gaps fill the same day" to "GapRecord", "how often does nifty close above the open after a gap up" to "GapRecord", "has the gap filled" to "Gap",
        "are tuesdays quiet" to "Weekdays", "which day has the biggest range" to "Weekdays", "konsa din sabse zyada move hota hai" to "Weekdays",
        "which day is the most volatile" to "Weekdays",
        "what would make you change your view" to "MindChange", "what level would change your mind" to "MindChange", "is your read still valid" to "MindChange",
        "what would make you turn bearish" to "MindChange", "what would make you bullish" to "MindChange", "what invalidates your read" to "MindChange",
        "what drove the market today" to "Causes", "what explains today's move" to "Causes",
        "any holidays this week" to "WeekAhead", "is there an expiry this week" to "WeekAhead", "what's next week like" to "WeekAhead",
        "next week mein kya hai" to "WeekAhead", "anything big this week" to "WeekAhead",
        "remind me what i said about expiry" to "SaidAbout", "what did i say in my journal about revenge trading" to "SaidAbout",
        "why did zerodha disconnect" to "ZerodhaSession",
        "what do i ask you again and again" to "AskedAgain", "what questions do i repeat" to "AskedAgain",
        "what does often mean when you say it" to "WordFit", "stop correcting your confidence words" to "WordFit",
        "how many green days in a row" to "Account:STREAKS", "how many losing days in a row" to "Account:STREAKS", "lagatar kitne din loss hua" to "Account:STREAKS",
        "lagatar kitne din profit hua" to "Account:STREAKS", "do i lose more on mondays" to "Account:STREAKS", "which weekday is my best" to "Account:STREAKS",
    )

    @Test fun roundElevenWordingsNeitherOrderNorCommandNorBundle() {
        assertTrue(ROUND11.size >= 40)
        // Every wording of the newest families in the hunt, not just the ones fixed: none is an order, a command, a Bundle act,
        // an everyday intent or a reminder (WordFit's on and off switch only its own wording check, never a strategy).
        val newest = setOf("GapRecord", "Weekdays", "MindChange", "Causes", "WeekAhead", "SaidAbout", "ZerodhaSession", "AskedAgain", "WordFit", "Account:STREAKS")
        val all = ROUND11 + ASKED.filter { it.second in newest }
        assertTrue(all.size >= 150, "${all.size}")
        for ((s, want) in all) {
            assertEquals(want, audit.feature(s), s)
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Bundle.acts(s), s)
            assertEquals(null, Intents.quick(s), s)
            assertTrue(!Reminder.asked(s) && !Reminder.cancelAsked(s) && !FollowUp.acts(s), s)
            assertEquals(null, Reminder.parse(s, today.atTime(10, 0)), s)
            assertTrue(Understand.questions(null, s).orEmpty().none { FollowUp.acts(it) || Ask.parse(it).command != null || Ask.parse(it).order != null }, s)
        }
        // The orders and commands beside these words still act as before (each through its own confirm).
        for (s in listOf("sell my put", "close my call", "square off my position", "buy 1 lot nifty 25000 ce", "stop all strategies", "stop strategy 2",
            "stop the orb arm", "kill switch on")) assertEquals("Act", audit.feature(s), s)
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop strategy 2").command?.kind)
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("explain my put then close all positions", "why did nifty fall and stop all strategies", "is this an expiry week, then kill switch on",
            "what did i say about expiry then close all positions", "am i on a winning streak and square off my position",
            "do gaps fill on nifty then sell my put", "why did zerodha log me out, stop all strategies"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
        // The month's trading days come from the calendar (a weekday without a holiday, or a weekend session): Mon 5 Oct 2026
        // with Tue 20 Oct shut has 21 in October, 19 from today on.
        val month = MarketDays.asked("how many trading days left this month", today)
        assertEquals(MarketDays.Asked.Month, month)
        val said = MarketDays.say(month!!, today, { d -> if (d == LocalDate.of(2026, 10, 20)) "Dussehra" else null }, null)
        assertEquals("Boss, October has 21 trading days on the exchange calendar, 19 of them from today on, today included. " +
            "Market holidays left this month: Tue 20 Oct (Dussehra).", said)
        for (s in listOf("how many trading days did i trade this month", "how many green days this month", "how many trading days were there last month"))
            assertTrue(MarketDays.asked(s, today) != MarketDays.Asked.Month, s)
        // Logging in or out stays Boss's own step on the Zerodha screen: never answered as a session question.
        for (s in listOf("log me out of zerodha", "log me in to kite", "zerodha login karo")) assertEquals(null, ZerodhaSession.asked(s), s)
    }

    // ---- Round 12 (Boss's chat, 5 Oct): his order's fate asked, outside the app, a close by a pronoun ----

    /** Asked what became of his order: a question about his account, never the CANCEL command (its answer is built elsewhere). */
    private val ORDER_WHY = listOf("why was my last order canceled", "why was my last order cancelled", "why was my order cancelled",
        "why was my order rejected", "what happened to my last order", "order cancel kyun hua", "mera order cancel kyun hua",
        "why did my order get rejected", "why did my last order get cancelled", "mera order reject kyun hua", "why did my order fail")

    @Test fun anOrdersFateAskedIsNeverTheCancelCommand() {
        for (s in ORDER_WHY) {
            val p = Ask.parse(s)
            assertEquals(null, p.command, s); assertEquals(null, p.order, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Bundle.acts(s) && !FollowUp.acts(s) && !Reminder.asked(s) && !Reminder.cancelAsked(s), s)
            assertEquals(null, Intents.quick(s), s)
            assertEquals(null, Plan.steps(s) { x -> Ask.parse(x).let { it.order == null && it.command != null } || Toolbox.isRead(x) }, s)
            // Left as a question about his own account: never the command, never Jarvis's own doing, never "not understood".
            val got = audit.feature(s)
            assertTrue(got != "Act" && got != "SelfWhy" && got != "Missed" && got != "Market" && got != "OutsideApp", "$s: $got")
            assertTrue(!SelfWhy.asked(s) && !OutsideApp.asked(s), s)
        }
        // Cancelling stays the command, through its own confirm; "why did you cancel my order" is still his question to Jarvis.
        assertEquals(Command(Command.Kind.CANCEL_ONE, target = "last"), Ask.parse("cancel my last order").command)
        assertEquals(Command.Kind.CANCEL_ONE, Ask.parse("cancel my order").command?.kind)
        for (s in listOf("cancel my last order", "cancel my order", "cancel all orders")) assertEquals("Act", audit.feature(s), s)
        assertTrue(SelfWhy.asked("why did you cancel my order"))
        assertTrue(SelfWhy.asked("why was orb parked"))
    }

    @Test fun outsideTheAppIsSaidPolitelyAndNothingActs() {
        val outside = ASKED.filter { it.second == "OutsideApp" }.map { it.first }
        assertTrue(outside.size >= 20)
        for (s in outside) {
            val p = Ask.parse(s)
            assertEquals(null, p.command, s); assertEquals(null, p.order, s)
            assertTrue(!Bundle.acts(s) && !FollowUp.acts(s) && !Reminder.asked(s), s)
            assertEquals(null, Intents.quick(s), s)
        }
        assertTrue(OutsideApp.SAY.startsWith("I only work inside IraAlgo, Boss") && "can't open other apps" in OutsideApp.SAY)
        // The app's own screens and words, and anything of the market or the account, are never taken.
        for (s in listOf("open the chain", "open settings", "open my positions", "open zerodha", "open orders", "call oi kahan hai", "call side",
            "call writing kahan hai", "put call ratio", "call 24500", "call me boss", "call it a day", "call option kya hai", "play the alert sound",
            "play the replay of my last trade", "start orb", "start strategy 2", "message me when nifty hits 25000", "text me at 3 pm", "call the support",
            "open interest kitna hai", "launch the strategy", "show me the chain", "close it", "how is nifty"))
            assertTrue(!OutsideApp.asked(s), s)
    }

    @Test fun roundTwelveWordingsNeitherOrderNorCommandNorBundle() {
        val newest = setOf("RangeBreaks", "DayCompare", "Account:NUMBERS", "Tour", "FigureFirst", "OutsideApp")
        val all = ASKED.filter { it.second in newest }
        assertTrue(all.size >= 80, "${all.size}")
        for ((s, want) in all) {
            assertEquals(want, audit.feature(s), s)
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Bundle.acts(s), s)
            assertEquals(null, Intents.quick(s), s)
            assertTrue(!Reminder.asked(s) && !Reminder.cancelAsked(s) && !FollowUp.acts(s), s)
            assertEquals(null, Reminder.parse(s, today.atTime(10, 0)), s)
            assertTrue(Understand.questions(null, s).orEmpty().none { FollowUp.acts(it) || Ask.parse(it).command != null || Ask.parse(it).order != null }, s)
        }
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("do orb breakouts fail often then close all positions", "compare today with yesterday and stop all strategies",
            "what's my profit factor, then kill switch on", "what can i ask you then square off my position", "open youtube then close all positions"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
    }

    // ---- Round 13: round 12's open items, and the newest families ----

    @Test fun roundTwelvesOpenItemsAreRouted() {
        // "What all can you do": what Jarvis can do (Ira.answer's help, the areas from the Toolbox), never "not understood".
        for (s in listOf("what all can you do", "what else can you do", "what can you do", "what all do you do")) {
            assertEquals(setOf(Topic.HELP), Ask.parse(s).topics, s)
            assertEquals("Help", audit.feature(s), s)
        }
        // "How was yesterday for Nifty": the index's last session ([DayCompare], the day alone), not Boss's own history -
        // only with an index or the market named; his own yesterday stays his.
        for (s in listOf("how was yesterday for nifty", "how was nifty yesterday", "how was the market yesterday", "kal nifty kaisa tha")) {
            assertEquals(DayCompare.Focus.DAY, DayCompare.asked(s)?.focus, s)
            assertTrue(Topic.ACCOUNT !in Ask.parse(s).topics, s)
        }
        for (s in listOf("how was yesterday", "how was my day yesterday", "how did i do yesterday")) assertEquals(setOf(Topic.ACCOUNT), Ask.parse(s).topics, s)
        // "What is the average price of my put": his position's line (quantity and average price), not the market's answer;
        // the index's own average stays the market's, and his average win stays his numbers.
        for (s in listOf("what is the average price of my put", "what's my average price on the 24500 put", "meri put ka average price kya hai"))
            assertTrue(Section.POSITIONS in AppAnswers.sections(s) && Ask.parse(s).topics == setOf(Topic.ACCOUNT), s)
        assertEquals("Market", audit.feature("what's the average range of nifty"))
        assertEquals("Account:NUMBERS", audit.feature("what's my average win and average loss"))
        // "Meri put explain karo phir isko band karo": never one STOP_ONE of the whole any more - the multi-step plan: the put
        // explained, then that position's close, shown and confirmed once before anything is done.
        val said = "meri put explain karo phir isko band karo"
        assertEquals(null, Ask.parse(said).command); assertEquals(null, Ask.parse(said).order)
        assertTrue(Bundle.acts(said))
        assertEquals(listOf("meri put explain karo", "close my put"), Plan.steps(said, ::hubStep))
        assertEquals(Command.Kind.CLOSE_ONE, Ask.parse("close my put").command?.kind)
        assertTrue(Command.Kind.CLOSE_ONE in Plan.ALLOWED)
        assertEquals("Account:EXPLAIN_POS", audit.feature("meri put explain karo"))
        assertEquals(listOf("meri 24500 put explain karo", "close my 24500 put"), Plan.steps("meri 24500 put explain karo phir isko band kar do", ::hubStep))
        assertEquals(listOf("meri call explain karo", "close my call"), Plan.steps("meri call explain karo uske baad isko band karo", ::hubStep))
        // No position named before the pronoun: asked which, never a plan, never a command (and "isko" is never a strategy).
        for (s in listOf("nifty kaisa hai phir isko band karo", "isko band karo")) {
            assertEquals(null, Ask.parse(s).command, s)
            assertEquals(null, Plan.steps(s, ::hubStep), s)
        }
        assertTrue(Plan.pronounUnclear("nifty kaisa hai phir isko band karo"))
        // The Hinglish commands are read as before ("phir se" is "again", never a step).
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("strategy 1 band karo").command?.kind)
        assertEquals(Command.Kind.STOP_ALL, Ask.parse("sab strategies band karo").command?.kind)
        assertEquals(Command.Kind.CLOSE_ONE, Ask.parse("position band karo").command?.kind)
        assertEquals(null, Plan.steps("nifty phir se 25000 cross karega", ::hubStep))
    }

    @Test fun roundThirteenWordingsNeitherOrderNorCommandNorBundle() {
        val newest = setOf("OrderWhy", "BotTrades", "WrongThing", "OutsideApp", "TrendReads", "ArmHabits", "PriorDay", "LastHour", "InsideDays", "Account:NUMBERS")
        val all = ASKED.filter { it.second in newest }
        assertTrue(all.size >= 150, "${all.size}")
        for ((s, want) in all) {
            assertEquals(want, audit.feature(s), s)
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Bundle.acts(s), s)
            assertEquals(null, Intents.quick(s), s)
            assertTrue(!Reminder.asked(s) && !Reminder.cancelAsked(s) && !FollowUp.acts(s), s)
            assertEquals(null, Reminder.parse(s, today.atTime(10, 0)), s)
            assertTrue(Understand.questions(null, s).orEmpty().none { FollowUp.acts(it) || Ask.parse(it).command != null || Ask.parse(it).order != null }, s)
            // Each (but another app, and "galat jawab" said of the last answer) is a question a plan can hold, answered at its turn.
            if (want != "OutsideApp" && !WrongThing.objected(s)) assertTrue(Toolbox.isRead(s), s)
        }
        // Said with something to do, each goes to the multi-step plan: the question answered at its turn, the action only
        // after the plan's one confirm (never answered and the action dropped, never done as one command of the whole).
        val plans = mapOf(
            "pdh pdl record then square off my position" to listOf("pdh pdl record", "square off my position"),
            "inside day record for banknifty then close all positions" to listOf("inside day record for banknifty", "close all positions"),
            "how often does nifty reverse in the last hour and stop all strategies" to listOf("how often does nifty reverse in the last hour", "stop all strategies"),
            "what did my bots do today then stop all strategies" to listOf("what did my bots do today", "stop all strategies"),
            "why was my order rejected then cancel all orders" to listOf("why was my order rejected", "cancel all orders"),
            "what did you get wrong today and close all positions" to listOf("what did you get wrong today", "close all positions"),
            "how often were your trend reads right then kill switch on" to listOf("how often were your trend reads right", "kill switch on"),
            "do i usually disarm my bots after losses, then stop all strategies" to listOf("do i usually disarm my bots after losses", "stop all strategies"),
            "what's my profit factor then switch to paper mode" to listOf("what's my profit factor", "switch to paper mode"),
            "why did orb take that trade then stop orb" to listOf("why did orb take that trade", "stop orb"),
        )
        for ((s, steps) in plans) {
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null, s)
            assertEquals(steps, Plan.steps(s, ::hubStep), s)
            assertTrue(Plan.steps(s, ::hubStep)!!.mapNotNull { Ask.parse(it).command?.kind }.all { it in Plan.ALLOWED }, s)
        }
        // Outside the app is never a step: said with an action it stays left to the action's own confirm (round 12).
        assertEquals(null, Plan.steps("open youtube then close all positions", ::hubStep))
        // The phone's own switches are outside the app, never a strategy started or stopped.
        for (s in listOf("turn on the flashlight", "turn off the flashlight", "switch on the torch", "turn on wifi")) assertEquals(null, Ask.parse(s).command, s)
        assertEquals(Command.Kind.START_ONE, Ask.parse("start orb").command?.kind)
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("turn off orb").command?.kind)
    }

    /** The hub's own test of a plan's step: an action a plan may hold, or a question it can answer. */
    private fun hubStep(s: String) = Ask.parse(s).let { it.order == null && it.command?.kind in Plan.ALLOWED } || Toolbox.isRead(s)

    @Test fun aCloseByAPronounIsLeftToThePlanThatAsksFirst() {
        // Round 11's open item: "explain my put then close it". "Close it" alone names nothing, so it is never a command; after
        // a named position it is that position's close - a step of the plan, shown and confirmed once before anything is done.
        val named = mapOf(
            "explain my put then close it" to listOf("explain my put", "close my put"),
            "explain my put and close it" to listOf("explain my put", "close my put"),
            "how is my put then close that" to listOf("how is my put", "close my put"),
            "explain my put then square it off" to listOf("explain my put", "close my put"),
            "explain my 24500 put then close this position" to listOf("explain my 24500 put", "close my 24500 put"),
        )
        for ((s, steps) in named) {
            // The whole is never one command (nothing runs as said), but it holds an action: no question handler answers it.
            assertEquals(null, Ask.parse(s).command, s); assertEquals(null, Ask.parse(s).order, s)
            assertTrue(Bundle.acts(s), s)
            assertEquals(steps, Plan.steps(s, ::hubStep), s)
            // The close is the one position's close, a step that lowers risk, done only after the plan's confirm (IraHub.planAsked).
            val close = Ask.parse(steps[1]).command
            assertEquals(Command.Kind.CLOSE_ONE, close?.kind, s)
            assertTrue(close!!.kind in Plan.ALLOWED)
            assertTrue(!Plan.pronounUnclear(s), s)
        }
        assertEquals("put", Ask.parse("close my put").command?.target)
        // No position named before it: asked which, nothing done - and never a plan.
        for (s in listOf("how is nifty then close it", "why did nifty fall then close that", "what's the pcr and exit it")) {
            assertTrue(Bundle.acts(s), s)
            assertEquals(null, Plan.steps(s, ::hubStep), s)
            assertTrue(Plan.pronounUnclear(s), s)
            assertEquals(null, Ask.parse(s).command, s)
        }
        assertTrue(Plan.WHICH_POSITION.startsWith("Which position should I close, Boss?") && "nothing was done" in Plan.WHICH_POSITION)
        // Named before it, but no plan formed (the first part is no step): never dropped silently - the hub asks which.
        for (s in listOf("my put is losing, close it", "my 24500 put looks weak then exit it")) {
            assertTrue(Bundle.acts(s), s)
            assertEquals(null, Plan.steps(s, ::hubStep), s)
            assertTrue(!Plan.pronounUnclear(s), s)
            assertTrue(Plan.pronounAfter(s), s)
        }
        for ((s, _) in named) assertTrue(Plan.pronounAfter(s), s)
        for (s in listOf("close it", "how is nifty", "close my put")) assertTrue(!Plan.pronounAfter(s), s)
        // Alone, a pronoun close stays words that do nothing: never a command, an order, a Bundle act or a plan.
        for (s in listOf("close it", "close that", "exit it", "square it off", "close this one")) {
            val p = Ask.parse(s)
            assertEquals(null, p.command, s); assertEquals(null, p.order, s)
            assertTrue(!Bundle.acts(s), s)
            assertEquals(null, Plan.steps(s, ::hubStep), s)
            assertTrue(!Plan.pronounUnclear(s), s)
        }
        // The named closes and the plans of round 11 are unchanged.
        assertEquals(listOf("explain my put", "close all positions"), Plan.steps("explain my put then close all positions", ::hubStep))
        assertEquals(Command.Kind.CLOSE_ONE, Ask.parse("close that position").command?.kind)
    }

    // ---- Again: the voice's own "say that again slowly" - heard before the question path, never a question family ----

    private val AGAIN = listOf("say that again slowly", "repeat it slower", "once more slowly", "dobara dheere bolo", "dheere se phir se bolo",
        "say the last number again", "what was that number", "which level did you say", "woh number phir se bolo", "kya number tha",
        "kitna bola", "just the numbers", "repeat the figures", "sirf numbers batao", "what was that level again", "jarvis say that again slowly",
        "tell me that again a bit slower", "phir se thoda dheere", "say the numbers again", "only the figures")
    private val NOT_AGAIN = listOf("repeat that", "speak slower", "thoda dheere bolo", "what was the nifty high", "what was the high today",
        "what was that about vix", "what is nifty at", "what's the vix", "is the market slow today", "how is nifty", "nifty kitna hai",
        "what was nifty at 11 am", "which level is support for nifty", "what is the level of banknifty", "why so quiet", "tell me more", "go on")

    @Test fun againIsTheVoicesOwnAndNeverActs() {
        for (s in AGAIN) {
            assertTrue(Again.read(s) != null, s)
            // No question family takes it first in the hub's order, and it is never an order, a command or a Bundle act.
            assertTrue(hits(s).isEmpty(), "$s: ${hits(s)}")
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            assertTrue(!Bundle.acts(s), s)
            assertTrue(!Reminder.asked(s) && !FollowUp.acts(s), s)
        }
        for (s in NOT_AGAIN) assertEquals(null, Again.read(s), s)
        // A plain "repeat that" stays "tell me more"; "speak slower" stays the lasting pace change.
        assertEquals(Command.Kind.MORE, Ask.parse("repeat that").command?.kind)
        assertEquals(Command.Kind.PACE_SLOWER, Ask.parse("speak slower").command?.kind)
    }
}
