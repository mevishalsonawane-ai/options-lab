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
 *
 * Round 14 (5 Oct): round 13's open items ("was yesterday an NR7 day" is that one session's shape, InsideDays; "did Nifty
 * break yesterday's high" / "how far is Nifty from yesterday's high" today's place against it, PriorDay; "search google
 * for nifty news" the polite no with what the app has instead, OutsideApp), and the newest families join the hunt:
 * SinceMorning, SwitchOff, StreamHealth, RelayHealth, FirstMove, VixNext, MorningSense, HonestStars and ArmDay.
 *
 * Round 15 (5 Oct): round 14's open items ("what is an inside day" is the word's meaning, Glossary; "why did the orb trade
 * fail today" a fail of today's arm trades, ArmDay), the expiry-eve question ("what expires tomorrow", "kal kya expire ho
 * raha hai": ExpiryEve, his legs that expire next), and the newest families join the hunt: TalkHours and NetLean, with
 * more of ExpiryPin, VixNext and ArmDay.
 *
 * Round 16 (5 Oct): today's new things asked - "why did the stream drop" (StreamHealth), "is the order watch running", "why
 * did the watch get stuck", "battery setting kya hai", "kya mera phone app ko rok raha hai" (WatchAsk, read only) - and
 * more English and Hinglish of LikeToday, SplitDays, MorningAsks, ExpiryEve, StreamHealth and RelayHealth.
 *
 * Round 17 (5 Oct): more English and Hinglish of the newest families - ArmFit, RoundCloses, TurnDowns (now in the hunt's
 * families, after MorningAsks as in the hub), BeforeTomorrow, BatteryUse and WatchAsk - none an order, a command or a Bundle
 * act; and a learned speech habit's undo said with "stop" ("stop offering my morning question", "stop shortening your
 * briefings", "stop reminding me why I turn your ideas down") reaches that undo, never a STOP of a strategy by that name,
 * while "stop orb", "stop all strategies", "stop the order watch" and "stop listening" read as before.
 *
 * Round 18 (5 Oct): more English and Hinglish of MonthTurns, LunchRange, WeakLink and TopicLength (ArmWeek is said on
 * Sunday evening and has no question of its own) - none an order, a command or a Bundle act. "Is the position watch on"
 * is the order watch asked (WatchAsk), never the positions list; "stop the position watch" stays a command. "Stop offering
 * trades" / "stop saying boss" still read as STOP_ONE of a strategy by that name: Act.parse reads words only and cannot
 * know Boss's strategy, Pine script and arm names (they live in the app), so the app's own pick finds no such arm and
 * asks "Which one?" - nothing is stopped without his pick and Confirm.
 *
 * Round 19 (5 Oct): more English and Hinglish of OpenHighLow ("open at its high", "open hi high", "open high open low ka
 * record"), BigCandles ("a 0.5% candle in the first hour" is no longer the first move's), OutlookCheck and Headroom's LOSS
 * at his stops (AtStops: "saare stop lag gaye to kitna nuksan"); Graduation has no question (it is said once, unasked).
 * And 60-odd everyday questions ("nifty kaha hai", "aaj ka plan", "mera p&l", "kya karu", "orb ka kya haal", "market band
 * hai kya", "kal expiry hai kya") still go where they should after all the additions - none acts - while "kill switch on
 * karo", "close all" and the rest stay commands.
 *
 * Round 20 (5 Oct): more English and Hinglish of ExtremeCloses, the day's wrap-up (DaySummary's newest words), ArmChange
 * ("is hafte mere bots kaise rahe pichle hafte ke mukable" was the account's history) and WeekRange; Boss's reminders one
 * at a time (ReminderBook: the list reads only, one named waits for his Confirm) and all of them ("sab reminders hata do",
 * "saare reminders cancel karo" were Missed: now the cancel-all, which names them and waits for his Confirm -
 * coverage-actions.txt is unchanged, as none of its lines reads differently); UsualIndex's corrections ("no banknifty",
 * "mera matlab sensex se tha") read as the index meant and never act; the everyday questions still go where they should.
 *
 * Round 21 (5 Oct): how Boss actually says his everyday questions. His book as "we" ("how much are we down", "how much did
 * we make today", "did we make money today" were the market, nothing or his funds), "where do I stand today" (where things
 * are in the app), "am I bleeding"; "is Nifty holding up" (read as his holdings); "how was my day today" (the market);
 * "what's my PL" and the recognizer's "what's my pin L" (nothing, or his PIN setting); "paisa bana kya aaj", "aaj ka hisaab",
 * "account kaisa hai", "koi trade chal raha hai kya" (the market or nothing); "how's my book"; "sab thik hai kya" and "is
 * everything okay" (nothing). Each now routes to its own answer, none acts, and coverage-actions.txt is unchanged.
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
        "MorningSense" to { q -> MorningSense.asked(q) != null },
        "HonestStars" to { q -> HonestStars.asked(q) != null },
        "TalkHours" to { q -> TalkHours.asked(q) != null },
        "MorningAsks" to { q -> MorningAsks.asked(q) != null },
        "TurnDowns" to { q -> TurnDowns.asked(q) != null },
        "TopicLength" to { q -> TopicLength.asked(q) != null },
        "OutlookCheck" to { q -> OutlookCheck.asked(q) },
        "UsualIndex" to { q -> UsualIndex.asked(q) != null },
        "Nicknames" to { q -> Nicknames.asked(q) != null },
        "LeadIndex" to { q -> LeadIndex.asked(q) != null },
        "LeadPart" to { q -> LeadPart.asked(q) != null },
        "NextAsk" to { q -> NextAsk.asked(q) != null },
        "Headroom" to { q -> Headroom.asked(q) != null },
        "ArmFit" to { q -> ArmFit.asked(q) },
        "WeakLink" to { q -> WeakLink.asked(q) },
        "ArmChange" to { q -> ArmChange.asked(q) },
        "PnlGap" to { q -> PnlGap.asked(q) },
        "ArmDay" to { q -> ArmDay.asked(q) != null },
        "BookDecay" to { q -> BookDecay.asked(q) },
        "WhereIWin" to { q -> WhereIWin.asked(q) != null },
        "TradesADay" to { q -> TradesADay.asked(q) != null },
        "AfterLoss" to { q -> AfterLoss.asked(q) != null },
        "NetLean" to { q -> NetLean.asked(q) },
        "ExpiryEve" to { q -> ExpiryEve.asked(q) },
        "BeforeTomorrow" to { q -> BeforeTomorrow.asked(q) },
        "BotTrades" to { q -> BotTrades.asked(q) != null },
        "SwitchOff" to { q -> SwitchOff.asked(q) != null },
        "SaidAbout" to { q -> SaidAbout.asked(q) != null },
        "WeekAhead" to { q -> WeekAhead.asked(q) != null },
        "ZerodhaSession" to { q -> ZerodhaSession.asked(q) != null },
        "OrderWhy" to { q -> OrderWhy.asked(q) != null },
        "RelayHealth" to { q -> RelayHealth.asked(q) != null },
        "StreamHealth" to { q -> StreamHealth.asked(q) },
        "BatteryUse" to { q -> BatteryUse.asked(q) },
        "WatchAsk" to { q -> WatchAsk.asked(q) != null },
        "Tour" to { q -> Tour.asked(q) },
        "ExpiryPin" to { q -> ExpiryPin.asked(q) != null },
        "SinceMorning" to { q -> SinceMorning.asked(q) },
        "ChainDrift" to { q -> ChainDrift.asked(q) != null },
        "ChainIntel" to { q -> ChainIntel.asked(q) != null },
        "DayClock" to { q -> DayClock.asked(q) != null },
        "GapRecord" to { q -> GapRecord.asked(q) != null },
        "RangeBreaks" to { q -> RangeBreaks.asked(q) != null },
        "PriorDay" to { q -> PriorDay.asked(q) != null },
        "LastHour" to { q -> LastHour.asked(q) != null },
        "InsideDays" to { q -> InsideDays.asked(q) != null },
        "FirstMove" to { q -> FirstMove.asked(q) != null },
        "VixNext" to { q -> VixNext.asked(q) != null },
        "SplitDays" to { q -> SplitDays.asked(q) != null },
        "RoundCloses" to { q -> RoundCloses.asked(q) != null },
        "MonthTurns" to { q -> MonthTurns.asked(q) != null },
        "LunchRange" to { q -> LunchRange.asked(q) != null },
        "OpenHighLow" to { q -> OpenHighLow.asked(q) != null },
        "BigCandles" to { q -> BigCandles.asked(q) != null },
        "ExtremeCloses" to { q -> ExtremeCloses.asked(q) != null },
        "WeekRange" to { q -> WeekRange.asked(q) != null },
        "RelativeMove" to { q -> RelativeMove.asked(q) != null },
        "Comebacks" to { q -> Comebacks.asked(q) != null },
        "VixBand" to { q -> VixBand.asked(q) != null },
        "Overnight" to { q -> Overnight.asked(q) != null },
        "DayAfter" to { q -> DayAfter.asked(q) != null },
        "Weekdays" to { q -> Weekdays.asked(q) != null },
        "DayCompare" to { q -> DayCompare.asked(q) != null },
        "LikeToday" to { q -> LikeToday.asked(q) },
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
        "whats my worst case today" to "Headroom", "what if all my stops get hit" to "Headroom", "how much do i lose if every stop is hit" to "Headroom",
        "aaj kitna loss ho sakta hai" to "Headroom", "sab stop hit ho gaye to kitna loss" to "Headroom", "what is my worst possible loss today" to "Headroom",
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
        "what if vix goes to 20" to "Scenarios", "recap the day" to "DayStory", "how was my day" to "DaySummary", "summarise the day" to "DaySummary",
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
        // ---- LikeToday: today's start against every past session's ----
        "is today like any past day" to "LikeToday", "has there been a day like today" to "LikeToday", "how did days like today end" to "LikeToday",
        "which past days started like today" to "LikeToday", "similar days to today for banknifty" to "LikeToday", "aaj jaisa din pehle kab tha" to "LikeToday",
        "show me days like today" to "LikeToday", "is today like yesterday" to "DayCompare",
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
        "don't start with the number" to "FigureFirst", "why are you saying the level first" to "LeadPart",
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
        "did nifty break yesterday's high" to "PriorDay", "how far is nifty from yesterday's high" to "PriorDay",
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
        "any contradictions between my bots" to "BotTrades", "why did my bots lose today" to "ArmDay",
        "am i net long or short" to "NetLean", "which way am i leaning right now" to "NetLean", "what's my net delta" to "NetLean",
        "do my positions cancel each other out" to "NetLean", "are my bots on opposite sides right now" to "NetLean", "is my book long or short" to "NetLean",
        "mera net position kis taraf hai" to "NetLean",
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
        "is today an inside day" to "InsideDays", "what is an inside bar" to "PatternExpert",
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
        // ==== Round 14: round 13's open items, routed ====
        // One named day's shape: InsideDays answers it directly (never Boss's own history).
        "was yesterday an nr7 day" to "InsideDays", "was yesterday an inside day" to "InsideDays", "was friday an nr7 day" to "InsideDays",
        "kal inside day tha kya" to "InsideDays", "kya kal nr7 tha" to "InsideDays", "was the last session an inside day" to "InsideDays",
        "was yesterday an inside day for banknifty" to "InsideDays", "is banknifty an inside day today" to "InsideDays",
        // Today against the prior day's high or low: PriorDay answers it directly (never Lookback's list of figures).
        "has banknifty taken out yesterday's low" to "PriorDay", "is nifty above yesterday's high" to "PriorDay",
        "kya nifty ne kal ka high toda" to "PriorDay", "nifty kal ke high se kitna door hai" to "PriorDay",
        "how far is banknifty from the previous day low" to "PriorDay", "did nifty cross the prior day high today" to "PriorDay",
        "is nifty below yesterday's low" to "PriorDay", "has nifty broken pdh" to "PriorDay", "how far is nifty from pdl" to "PriorDay",
        // A web search: outside the app, said with what the app has instead.
        "search google for nifty news" to "OutsideApp", "search google for banknifty" to "OutsideApp", "google nifty news" to "OutsideApp",
        "look up nifty on google" to "OutsideApp", "search the internet for nifty news" to "OutsideApp", "google pe nifty news search karo" to "OutsideApp",
        "search youtube for options trading" to "OutsideApp",
        // ...and their neighbours: the figures, a forecast, the record, the app's own search, a level, the meaning.
        "where is yesterday's low" to "Lookback", "how often does nifty break yesterday's high" to "PriorDay",
        "search my orders" to "Account:ORDERS", "was yesterday a good day" to "Account:HISTORY",
        "did i break even yesterday" to "Account:HISTORY",
        // ---- Round 14. SinceMorning: what changed since this morning ----
        "what changed since this morning" to "SinceMorning", "what's different since the open" to "SinceMorning", "subah se kya badla" to "SinceMorning",
        "subah se kya change hua" to "SinceMorning", "what has changed since morning on banknifty" to "SinceMorning",
        "how is now different from this morning" to "SinceMorning", "kya badla subah se" to "SinceMorning", "anything new since 9:45" to "SinceMorning",
        "now versus this morning" to "SinceMorning", "subah aur ab mein kya fark hai" to "SinceMorning", "what's new since the open" to "SinceMorning",
        // ---- SwitchOff: which arms lost in both records ----
        "what should i switch off" to "SwitchOff", "which bot should i switch off" to "SwitchOff", "kaun sa bot band karun" to "SwitchOff",
        "kaunsa bot band karna chahiye" to "SwitchOff", "kaun si strategy band karun" to "SwitchOff", "which arm should i disarm" to "SwitchOff",
        "should i turn off orb fresh" to "SwitchOff", "which arms lost both in the backtest and on paper" to "SwitchOff",
        "is any bot worth turning off" to "SwitchOff", "which strategies are losing in both test and paper" to "SwitchOff",
        // ---- StreamHealth: the live price stream's drops ----
        "stream kyun toot raha hai" to "StreamHealth", "why does the live feed keep dropping" to "StreamHealth",
        "live data kyun toot raha hai" to "StreamHealth", "why is the price stream disconnecting" to "StreamHealth",
        "is the live price stream ok" to "StreamHealth", "why do prices keep freezing" to "StreamHealth",
        "stream baar baar kyun band hota hai" to "StreamHealth", "why do live prices keep freezing" to "StreamHealth",
        // ---- BatteryUse (battery round 1): what of the app runs in the background now ----
        "battery kyun kha raha hai" to "BatteryUse", "why is the app using so much battery" to "BatteryUse",
        "why is iraalgo draining my battery" to "BatteryUse", "what is running in the background" to "BatteryUse",
        "app itni battery kyun kha raha hai" to "BatteryUse", "is jarvis draining the battery" to "BatteryUse",
        "background mein kya chal raha hai" to "BatteryUse", "battery usage" to "BatteryUse",
        // ---- RelayHealth: the relay and the static IP ----
        "why is the relay timing out" to "RelayHealth", "is the relay connected" to "RelayHealth", "relay chal raha hai kya" to "RelayHealth",
        "static ip sahi hai kya" to "RelayHealth", "is my static ip registered" to "RelayHealth", "why is zerodha failing through the relay" to "RelayHealth",
        "relay kyun nahi chal raha" to "RelayHealth", "can i trade live right now" to "RelayHealth",
        // ---- FirstMove: the first move against the day's close ----
        "does the first half hour decide the day" to "FirstMove", "how often does the first 30 minutes direction match the close" to "FirstMove",
        "first half hour ki direction se din kaisa jaata hai" to "FirstMove", "does the opening move usually hold till the close" to "FirstMove",
        "if nifty is down in the first 30 minutes does it close down" to "FirstMove", "first move stats for banknifty" to "FirstMove",
        "pehle aadhe ghante ki direction se din ka close kitni baar milta hai" to "FirstMove",
        // ---- VixNext: India VIX's change against the next day ----
        "when vix jumps how big is the next day" to "VixNext", "vix badhne ke baad agle din kitna move hota hai" to "VixNext",
        "after vix falls is the next day quieter" to "VixNext", "how much does banknifty move the day after a vix spike" to "VixNext",
        "vix next day record" to "VixNext", "jab vix 5% uchalta hai to agle din nifty kitna chalta hai" to "VixNext",
        // ---- SplitDays: two indices on opposite sides of their previous closes (round 23) ----
        "how often do nifty and banknifty close opposite ways" to "SplitDays", "how often does banknifty diverge from nifty" to "SplitDays",
        "what usually happens after nifty and banknifty split" to "SplitDays", "nifty banknifty divergence history" to "SplitDays",
        "how many days did nifty and finnifty end in opposite directions" to "SplitDays", "nifty aur banknifty kitni baar ulta chalte hain" to "SplitDays",
        "banknifty nifty se kitni baar ulta jaata hai" to "SplitDays", "do the indices usually diverge" to "SplitDays",
        "how often is one up and the other down for nifty and banknifty" to "SplitDays", "nifty sensex divergence record" to "SplitDays",
        // Their neighbours: today alone, the leader over a week, the record of one index's own day.
        "are nifty and banknifty moving together" to "Together", "is banknifty diverging from nifty" to "Together",
        // ---- RoundCloses: past closes beside round numbers against chance (round 24) ----
        "does nifty close near round numbers" to "RoundCloses", "are round numbers a magnet for nifty" to "RoundCloses",
        "how often does banknifty end near a round thousand" to "RoundCloses", "round number record" to "RoundCloses",
        "nifty gol figure ke paas kitni baar band hota hai" to "RoundCloses", "do sensex closes cluster at round levels" to "RoundCloses",
        // Its neighbours: one price, the expiry pin.
        "what s at 25000 on nifty" to "LevelInfo", "does nifty pin to max pain on expiry" to "ExpiryPin",
        // ---- MonthTurns: a month's first and last 3 trading days against the rest (round 25) ----
        "how does nifty do at the beginning of the month" to "MonthTurns", "is there a turn of the month effect" to "MonthTurns",
        "how does banknifty usually do at month end" to "MonthTurns", "month beginning record" to "MonthTurns",
        "mahine ke aakhri din nifty kaisa rehta hai" to "MonthTurns", "how volatile are the first few days of the month" to "MonthTurns",
        // Its neighbours: the month's own move, Boss's month, a weekday's record.
        "how was nifty this month" to "PeriodMove", "how was my month" to "Account:MONTH", "is friday usually the widest day" to "Weekdays",
        // ---- LunchRange: the 12:00-13:30 window against the morning and afternoon, and the afternoon's break of it (round 26) ----
        "does nifty break the lunch range" to "LunchRange", "lunch range breakout record" to "LunchRange",
        "is the lunch lull real" to "LunchRange", "how wide is the banknifty lunch range compared to the morning" to "LunchRange",
        "lunch ki range todne ke baad nifty kya karta hai" to "LunchRange", "how often does sensex close above the lunch range" to "LunchRange",
        // Its neighbours: how quiet lunch usually is, the opening range's breaks, the last hour's turn.
        "is nifty usually quiet at lunch" to "DayClock", "how often do opening range breaks hold for banknifty" to "RangeBreaks", "how often does the last hour reverse the day" to "LastHour",
        // ---- OpenHighLow: the days whose open was the day's high or low, and how they closed (round 27) ----
        "how often is the open the high of the day" to "OpenHighLow", "open = low days record" to "OpenHighLow",
        "how do open high days close for banknifty" to "OpenHighLow", "open low wale din nifty kaisa chalta hai" to "OpenHighLow",
        "how often does sensex open at the day's high or low" to "OpenHighLow", "o=h o=l record for finnifty" to "OpenHighLow",
        // Its neighbours: the gap's record, the opening range's breaks, the first move of the day.
        "how often does the gap fill" to "GapRecord", "how often do opening range breaks hold for nifty" to "RangeBreaks", "first move stats for finnifty" to "FirstMove",
        // ---- BigCandles: what followed a big 5-minute candle in the first hour (round 28) ----
        "after a big 5-minute candle in the first hour does the day continue" to "BigCandles", "big candle record for banknifty" to "BigCandles",
        "how often does a big green candle in the morning follow through" to "BigCandles", "subah bada candle aane ke baad nifty kya karta hai" to "BigCandles",
        "what happens after a 0.4% 5-minute candle" to "BigCandles", "large first hour candles record for finnifty" to "BigCandles",
        // Its neighbours: the first move of the day, a sharp move explained, which candle patterns work.
        "does the first half hour decide the day for sensex" to "FirstMove", "which candle patterns work on nifty" to "PatternCalls", "how often do opening range breaks hold for sensex" to "RangeBreaks",
        // ---- ExtremeCloses: closes at the ends of the day's range and what the next day did (round 29) ----
        "how often does nifty close near its high" to "ExtremeCloses", "what happens the day after banknifty closes at the low" to "ExtremeCloses",
        "strong close record for sensex" to "ExtremeCloses", "high pe close hone ke baad agle din kya hota hai" to "ExtremeCloses",
        "after a weak close what does finnifty do next day" to "ExtremeCloses", "how often are there extreme closes" to "ExtremeCloses",
        // Its neighbours: the last hour's record, the trend days remembered, the big-candle record.
        "last hour record for nifty" to "LastHour", "how many trend days did banknifty have this month" to "MarketMemory", "big candle record for sensex" to "BigCandles",
        // ---- WeekRange: the week's range and the weekdays of its high and low (round 30) ----
        "which day of the week usually makes the weekly high" to "WeekRange", "weekly range record for nifty" to "WeekRange",
        "how big is a normal week for banknifty" to "WeekRange", "hafte ka low kis din banta hai" to "WeekRange",
        "on which day does sensex usually make its weekly low" to "WeekRange", "what is the usual weekly range of finnifty" to "WeekRange",
        // Its neighbours: each weekday's own range, Boss's own week, the close-at-the-ends record.
        "which day of the week has the biggest range for nifty" to "Weekdays", "how did i do last week" to "Account:HISTORY", "how often does sensex close near its low" to "ExtremeCloses",
        // ---- RelativeMove: one index's day moves against another's, in % (round 31) ----
        "how often does banknifty move more than nifty in % on a day" to "RelativeMove", "is sensex more volatile than nifty" to "RelativeMove",
        "banknifty nifty se zyada kitni baar chalta hai" to "RelativeMove", "kya nifty se zyada banknifty chalta hai" to "RelativeMove",
        "relative volatility of finnifty" to "RelativeMove", "does banknifty usually have a wider range than nifty" to "RelativeMove",
        // Its neighbours: today's strength, today's correlation, the days they close opposite ways, Boss's own pick of index.
        "is banknifty stronger than nifty" to "Compare", "does banknifty move with nifty" to "Together",
        "how often do nifty and banknifty diverge" to "SplitDays", "jarvis what did you learn about me" to "Learnings",
        "tumne mere baare mein kya seekha" to "Learnings", "what do you know about me" to "AboutBoss",
        // ---- Comebacks: the days an index fell (or rose) a size from the previous close, and how they ended (round 32) ----
        "when nifty is down 1% intraday how often does it recover" to "Comebacks", "how often does banknifty bounce back after falling 1.5% in the day" to "Comebacks",
        "nifty 1% upar jaane ke baad kitni baar wapas aata hai" to "Comebacks", "does a 1% intraday rally on sensex usually hold" to "Comebacks",
        "intraday recovery record for nifty" to "Comebacks", "when finnifty is up 1 percent in the day how often does it give it back" to "Comebacks",
        // Its neighbours: a gap's fill, the last hour, today's run of closes, a what-if, a reason for today's move.
        "do gap downs on banknifty usually fill the same day" to "GapRecord", "does sensex usually reverse in the last hour" to "LastHour",
        "how many days in a row has nifty risen" to "Streak", "what if banknifty falls 1.5%" to "Scenarios", "why did nifty recover today" to "Causes",
        // ---- VixBand: past days against one day's move priced by India VIX the evening before (round 33) ----
        "how often does nifty stay within the vix expected move" to "VixBand", "does india vix usually overstate the move" to "VixBand",
        "how often does banknifty move more than vix implies" to "VixBand", "vix ke expected move se nifty kitni baar bahar jata hai" to "VixBand",
        "vix expected move record" to "VixBand", "how often does nifty break twice the vix move" to "VixBand",
        // Its neighbours: VIX's level, the day after a VIX jump.
        "is vix high" to "VixRank", "how high is india vix" to "VixRank",
        "after a vix spike how much does banknifty move the next day" to "VixNext",
        // ---- Overnight: past sessions split at the open into the overnight move and the session's (round 34) ----
        "does nifty make its moves overnight or during the day" to "Overnight", "overnight vs intraday returns for banknifty" to "Overnight",
        "how big are nifty's overnight moves usually" to "Overnight", "is the trend made in the gaps or in market hours" to "Overnight",
        "are weekend gaps bigger than weekday overnight moves" to "Overnight", "nifty ka move raat mein banta hai ya din mein" to "Overnight",
        // Its neighbours: a gap's fill, today's gap, the intraday comeback.
        "do gaps usually fill during the session" to "GapRecord", "how often does nifty recover a 1% fall during the day" to "Comebacks",
        // ---- DayAfter: what the next session did after a big day (round 35) ----
        "after nifty falls 1% in a day what happens the next day" to "DayAfter", "does banknifty bounce the day after a big down day" to "DayAfter",
        "after a 2% up day does nifty follow through the next session" to "DayAfter", "big day follow through record" to "DayAfter",
        "1% girne ke baad agle din nifty kya karta hai" to "DayAfter", "does nifty recover the day after a 1% fall" to "DayAfter",
        // Its neighbours: the intraday comeback, the day after a close at the low, the day after a VIX jump.
        "does nifty recover from a 1% fall" to "Comebacks", "what happens the day after nifty closes at the low" to "ExtremeCloses",
        "after a vix spike how much does nifty move the next day" to "VixNext",
        // ---- MorningSense: the morning check items said briefly ----
        "which morning items do you skip" to "MorningSense", "which morning check items do you leave out" to "MorningSense",
        "morning check ka kya skip karte ho" to "MorningSense", "read me the whole morning check" to "MorningSense",
        "subah ka poora check sunao" to "MorningSense", "why did you skip items in the morning check" to "MorningSense",
        "morning check mein kya chhodte ho" to "MorningSense", "say the whole morning check again" to "MorningSense",
        // ---- TurnDowns: the reasons Boss turns Jarvis's trade ideas down for, said up front ----
        "why do i turn down your ideas" to "TurnDowns", "why did i reject your trade ideas" to "TurnDowns",
        "what reasons do i usually give for rejecting your suggestions" to "TurnDowns", "dont remind me why i turned your ideas down" to "TurnDowns",
        "don't remind me of my reasons" to "TurnDowns", "why did you tell me why i rejected them" to "TurnDowns",
        // ---- TopicLength: the topics said in a sentence or in full aloud, as Boss asks for them ----
        "how long do i like your answers" to "TopicLength", "which topics do you keep short" to "TopicLength",
        "which topics do you say in detail for me" to "TopicLength", "do you know how short i like my answers" to "TopicLength",
        "say every topic at the usual length" to "TopicLength", "forget how long i like my answers" to "TopicLength",
        "kaun se topic short mein batate ho" to "TopicLength", "har topic normal length mein bolo" to "TopicLength",
        // ---- OutlookCheck: the 09:00 outlook against the close, in counts ----
        "how good are your morning outlooks" to "OutlookCheck", "are your morning outlooks any good" to "OutlookCheck",
        "what's your outlook record" to "OutlookCheck", "how often are your outlooks right" to "OutlookCheck",
        "how did your outlook do today" to "OutlookCheck", "aapka subah ka outlook kitna sahi hota hai" to "OutlookCheck",
        "jarvis how good are your morning calls" to "OutlookCheck",
        // ---- UsualIndex: the index taken when Boss names none, learned from his corrections ----
        "which index do i usually mean" to "UsualIndex", "what index do you assume" to "UsualIndex",
        "which index do you take when i dont name one" to "UsualIndex", "what's my usual index" to "UsualIndex",
        "do you know which index i usually mean" to "UsualIndex", "mera usual index kaunsa hai" to "UsualIndex",
        "use nifty when i dont name an index" to "UsualIndex", "reset my default index" to "UsualIndex", "don't assume my index" to "UsualIndex",
        "use nifty when i don't name one" to "UsualIndex", "mera usual index bhool jao" to "UsualIndex",
        // ---- Nicknames: the words Boss uses for one arm or position, learned on his pick ----
        "what nicknames do i use" to "Nicknames", "which nicknames do you know for my arms" to "Nicknames",
        "what do i call my bots" to "Nicknames", "show my nicknames" to "Nicknames", "what nicknames have you learned" to "Nicknames",
        "mere bots ke nicknames kya hain" to "Nicknames", "forget my nicknames for my arms" to "Nicknames", "forget my nicknames" to "Nicknames",
        "clear the nicknames" to "Nicknames", "forget the names i use for my positions" to "Nicknames", "mere nicknames bhool jao" to "Nicknames",
        // ---- LeadIndex: the index Boss asks about by name, named first where both are given ----
        "which index do you mention first" to "LeadIndex", "why do you say banknifty first" to "LeadIndex",
        "why do you mention bank nifty first in the greeting" to "LeadIndex", "why is banknifty first" to "LeadIndex",
        "which index do i ask about most" to "LeadIndex", "what index do i ask you about the most" to "LeadIndex",
        "kaunsa index pehle bolte ho" to "LeadIndex", "banknifty pehle kyun bolte ho" to "LeadIndex",
        "main kaunsa index sabse zyada puchta hoon" to "LeadIndex", "mention nifty first again" to "LeadIndex",
        "say nifty first again" to "LeadIndex", "don't say bank nifty first" to "LeadIndex", "stop saying banknifty first" to "LeadIndex",
        "nifty pehle bolo phir se" to "LeadIndex", "banknifty pehle mat bolo" to "LeadIndex",
        // ---- LeadPart: the part of a market read Boss asks for on its own, said right after the price in an overview ----
        "what do you say first in an overview" to "LeadPart", "why do you give the levels first" to "LeadPart",
        "why are the levels first" to "LeadPart", "why do you start with patterns" to "LeadPart",
        "what comes first in your overview" to "LeadPart", "which part do i ask about most" to "LeadPart",
        "overview mein pehle kya batate ho" to "LeadPart", "levels pehle kyun bolte ho" to "LeadPart",
        "say your overviews in the usual order" to "LeadPart", "stop putting the levels first" to "LeadPart",
        "stop starting with the levels" to "LeadPart", "don't put the patterns first" to "LeadPart",
        "go back to the usual order in your overviews" to "LeadPart", "levels pehle mat batao" to "LeadPart",
        // ---- NextAsk: the question Boss usually asks next, offered in one short question at the end of an answer ----
        "what do i usually ask next" to "NextAsk", "what do i ask after the levels" to "NextAsk",
        "which follow ups do i ask" to "NextAsk", "what follow ups have you learned" to "NextAsk",
        "why do you keep offering the next question" to "NextAsk", "why did you ask what is next" to "NextAsk",
        "main uske baad kya puchta hoon" to "NextAsk", "main aksar agla kya puchta hoon" to "NextAsk",
        "stop offering what i ask next" to "NextAsk", "stop suggesting the next question" to "NextAsk",
        "don't offer follow ups" to "NextAsk", "do not ask me what comes next" to "NextAsk",
        "stop ending your answers with a question" to "NextAsk", "no more follow up offers" to "NextAsk",
        "agla sawal mat pucho" to "NextAsk", "agla sawal offer mat karo" to "NextAsk",
        // ...and a market question that merely puts Nifty first is never its undo: it keeps its market route.
        "nifty pehle batao" to "Market", "nifty ko pehle lo" to "Market", "give nifty first" to "Market", "say nifty first" to "Market",
        // ---- HonestStars: his confidence scores against their record ----
        "how honest are your stars" to "HonestStars", "do your 5 star ideas actually work" to "HonestStars",
        "tumhare confidence stars kitne sahi hain" to "HonestStars", "are your confidence ratings any good" to "HonestStars",
        "say confidence without the record" to "HonestStars", "how reliable is your confidence" to "HonestStars",
        "tumhara confidence kitna sahi hai" to "HonestStars", "confidence seedha bolo" to "HonestStars",
        // ---- BookDecay: what time decay does to his whole book (usefulness round 29) ----
        "what's my theta" to "BookDecay", "how much am i losing to time decay" to "BookDecay", "theta on my positions" to "BookDecay",
        "is theta working for me or against me" to "BookDecay", "am i long or short theta" to "BookDecay", "my net theta" to "BookDecay",
        "how much decay over the weekend on my positions" to "BookDecay", "how much theta am i collecting" to "BookDecay",
        "mera theta kitna hai" to "BookDecay", "meri positions ka theta kitna hai" to "BookDecay", "time decay se kitna nuksan ho raha hai" to "BookDecay",
        "decay kitna kha raha hai" to "BookDecay",
        // ---- WhereIWin: where his own trading makes and loses its money (usefulness round 30) ----
        "where do i make my money" to "WhereIWin", "am i better at calls or puts" to "WhereIWin", "my calls vs my puts" to "WhereIWin",
        "do i make more buying or selling options" to "WhereIWin", "which index do i make money on" to "WhereIWin",
        "what kind of trades work for me" to "WhereIWin", "my best index" to "WhereIWin", "call mein zyada kamata hoon ya put mein" to "WhereIWin",
        "kis index mein paisa banta hai" to "WhereIWin",
        // ---- TradesADay: his days by how many trades he took, and his trades by their place in the day (usefulness round 31) ----
        "do i do better when i trade less" to "TradesADay", "do i lose more on days i trade a lot" to "TradesADay",
        "how many trades a day work best for me" to "TradesADay", "my quiet days vs my busy days" to "TradesADay",
        "how does my first trade of the day do" to "TradesADay", "do my later trades lose" to "TradesADay",
        "do i lose after my second trade" to "TradesADay", "din ka pehla trade kaisa jaata hai" to "TradesADay",
        // ---- AfterLoss: his trades after a loss against after a win (usefulness round 32) ----
        "how do i trade after a loss" to "AfterLoss", "do i revenge trade" to "AfterLoss", "am i a revenge trader" to "AfterLoss",
        "do i chase my losses" to "AfterLoss", "how does my next trade do after a losing trade" to "AfterLoss",
        "do i get careless after a win" to "AfterLoss", "loss ke baad mera agla trade kaisa jaata hai" to "AfterLoss",
        // ---- ArmChange: the arms' paper results this week against last week ----
        "what's changed in my arms' results this week vs last" to "ArmChange", "how are my bots doing this week compared to last week" to "ArmChange",
        "my arms this week vs last week" to "ArmChange", "my strategies week on week" to "ArmChange", "what changed in my bots this week" to "ArmChange",
        "how did my arms do this week versus last week" to "ArmChange", "compare my bots this week with last week" to "ArmChange",
        "week on week for my arms" to "ArmChange", "mere bots ka is hafte vs pichle hafte" to "ArmChange", "mere arms mein is hafte kya badla" to "ArmChange",
        "pichle hafte se mere bots mein kya badla" to "ArmChange", "my bots' results this week against last week" to "ArmChange",
        // ---- PnlGap: today's paper P&L taken apart against what Boss expected ----
        "why is my p&l different from what i expected" to "PnlGap", "why is my pnl lower than i expected" to "PnlGap",
        "why is my p&l not what i expected" to "PnlGap", "my p&l doesn't add up" to "PnlGap", "break down my p&l" to "PnlGap",
        "today's p&l breakdown" to "PnlGap", "how much of my p&l is realised" to "PnlGap", "realised vs unrealised" to "PnlGap",
        "where did my p&l come from" to "PnlGap", "mera p&l expected se alag kyun hai" to "PnlGap", "aaj ka pnl itna kam kyun hai" to "PnlGap",
        "mera p&l ka breakdown do" to "PnlGap",
        // ---- WeakLink: what most often went wrong in the arms' last paper trades ----
        "what's the weakest link in my setup" to "WeakLink", "what usually goes wrong in my paper trades" to "WeakLink",
        "where do my bots go wrong" to "WeakLink", "what do my losing trades have in common" to "WeakLink",
        "mere trades mein sabse kamzor kadi kya hai" to "WeakLink", "what keeps going wrong with my bots" to "WeakLink",
        // ---- ArmFit: each arm's record on days in today's bands ----
        "which of my arms suits today" to "ArmFit", "which bot fits today's conditions" to "ArmFit", "how do my arms do on days like today" to "ArmFit",
        "orb's record on days like this" to "ArmFit", "aaj ke din kaun sa bot suit karta hai" to "ArmFit", "which strategy suits today" to "ArmFit",
        "which arms suit this market" to "Account:REGIME",
        // ---- ArmDay: a strategy's losing day beside the index ----
        "why did my strategy lose today" to "ArmDay", "orb ka aaj loss kyun hua" to "ArmDay", "why did orb fresh lose money today" to "ArmDay",
        "mera bot aaj kyun haara" to "ArmDay", "what went wrong with my bots today" to "ArmDay", "why did liquidity 15+5 lose today" to "ArmDay",
        "range fade aaj kyun loss mein gaya" to "ArmDay", "meri strategy ne aaj loss kyun kiya" to "ArmDay",
        // Their neighbours: the commands beside them stay commands (each through its own confirm), the market's fall its own.
        "why do prices keep dropping" to "Why", "why did you skip the trade" to "Thinking",
        // ==== Round 15: round 14's open items, routed ====
        // A word's meaning: the glossary (whether one day was one stays InsideDays').
        "what is an inside day" to "Glossary", "what is an nr7 day" to "Glossary", "inside day kya hota hai" to "Glossary",
        "what does nr7 mean" to "Glossary", "what does inside day mean" to "Glossary", "nr7 ka matlab kya hai" to "Glossary",
        // A loss or a fail of today's arm trades: ArmDay, beside the index (what each trade was, against its rule, BotTrades').
        "why did the orb trade fail today" to "ArmDay", "why did my orb trades fail today" to "ArmDay", "why did orb fail today" to "ArmDay",
        "orb aaj kyun fail hua" to "ArmDay", "why did my bot's trade fail today" to "ArmDay", "why did the range fade trade fail" to "ArmDay",
        "why did orb sweep get stopped out" to "ArmDay", "range fade aaj kyun fail ho gaya" to "ArmDay",
        // ---- Round 15. ExpiryEve: what of his expires on the next trading day ----
        "what expires tomorrow" to "ExpiryEve", "kal kya expire ho raha hai" to "ExpiryEve", "which of my positions expire tomorrow" to "ExpiryEve",
        "what's expiring tomorrow" to "ExpiryEve", "kal kaun si position expire hogi" to "ExpiryEve", "do i have anything expiring tomorrow" to "ExpiryEve",
        "kal kya expire hoga" to "ExpiryEve", "which legs expire tomorrow" to "ExpiryEve", "is anything of mine expiring tomorrow" to "ExpiryEve",
        "which of my nifty options expire tomorrow" to "ExpiryEve", "mere kaun se trades kal expire honge" to "ExpiryEve",
        "what expires on the next trading day" to "ExpiryEve", "any of my positions expiring tomorrow" to "ExpiryEve",
        // Its neighbours: when the expiry is (the calendar's), the pin record, the meaning.
        "what expires on the next expiry" to "MarketDays", "expiry kab hai" to "MarketDays",
        "how did the last expiry go" to "MarketMemory",
        // ---- ExpiryPin, more of it ----
        "how often does nifty settle near max pain on expiry" to "ExpiryPin", "does max pain work on expiry" to "ExpiryPin",
        "expiry pin record" to "ExpiryPin", "expiry pe nifty max pain ke paas band hota hai kya" to "ExpiryPin", "does nifty get pinned on expiry" to "ExpiryPin",
        "max pain hit rate" to "ExpiryPin", "how close does nifty settle to the biggest oi strike on expiry" to "ExpiryPin",
        "expiry pe max pain sahi hota hai kya" to "ExpiryPin", "max pain on past expiries" to "ExpiryPin", "does the pin effect work on expiry days" to "ExpiryPin",
        "how often does nifty settle within 100 points of max pain on expiry" to "ExpiryPin", "is max pain reliable on expiry day" to "ExpiryPin",
        // ---- NetLean, more of it ----
        "which way am i leaning" to "NetLean", "what's my net delta on banknifty" to "NetLean", "do my bots contradict each other right now" to "NetLean",
        "main long hoon ya short" to "NetLean", "meri position kis taraf hai" to "NetLean", "mere bots ek dusre ke against hain kya" to "NetLean",
        "am i net short on nifty" to "NetLean", "what is my net exposure" to "NetLean", "how am i positioned overall" to "NetLean",
        "which side is my book on" to "NetLean", "am i long or short on banknifty" to "NetLean", "main net short hoon kya" to "NetLean",
        "what is my net delta" to "NetLean",
        // ---- TalkHours: the hours Boss talks to Jarvis ----
        "when do i usually talk to you" to "TalkHours", "which hours am i active" to "TalkHours", "why was the briefing so short" to "TalkHours",
        "say your briefings in full at any hour" to "TalkHours", "don't shorten your briefings" to "TalkHours", "main tumse kab baat karta hoon" to "TalkHours",
        "briefings poori bolo hamesha" to "TalkHours", "what time do i usually talk to you" to "TalkHours",
        "which hours do you keep your briefings short" to "TalkHours", "why do you keep your briefings short" to "TalkHours",
        "what hours do i talk to you" to "TalkHours", "when do you cut your updates short" to "TalkHours",
        "dont cut your briefings short any more" to "TalkHours", "main aapse kis time baat karta hu" to "TalkHours",
        // ---- VixNext, more of it ----
        "how big is the next day after vix spikes 7%" to "VixNext", "vix spike follow through stats" to "VixNext",
        "when india vix falls 5% is the next session quieter" to "VixNext", "after a vix jump does nifty fall the next day" to "VixNext",
        "vix girta hai to agle din market kitna chalta hai" to "VixNext", "when vix drops how does nifty do the next day" to "VixNext",
        "vix next session record for banknifty" to "VixNext", "how does banknifty move the day after vix rises 10%" to "VixNext",
        // ---- ArmDay, more of it ----
        "why did range fade lose" to "ArmDay", "what went wrong with orb today" to "ArmDay", "why was today a bad day for my bots" to "ArmDay",
        "how did the market beat my strategy today" to "ArmDay", "aaj bot ko nuksan kyun hua" to "ArmDay", "why are my bots in the red today" to "ArmDay",
        // Their neighbours: what the bots did today (BotTrades), and the meaning of a candle.
        "what did orb trade today" to "BotTrades", "did orb follow its rule today" to "BotTrades",
        // ---- Round 16. BeforeTomorrow: what Boss needs to do before the next trading day ----
        "what do i need to do before tomorrow" to "BeforeTomorrow", "what do i have to do before tomorrow" to "BeforeTomorrow",
        "anything i need to do before tomorrow" to "BeforeTomorrow", "is there anything i need to do for tomorrow" to "BeforeTomorrow",
        "what should i sort out before tomorrow" to "BeforeTomorrow", "anything to do before tomorrow" to "BeforeTomorrow",
        "what do i need to take care of before tomorrow's open" to "BeforeTomorrow", "checklist for tomorrow" to "BeforeTomorrow",
        "tomorrow's checklist" to "BeforeTomorrow", "my to do list for tomorrow" to "BeforeTomorrow", "kal se pehle kya karna hai" to "BeforeTomorrow",
        "kal ke liye kya karna hai" to "BeforeTomorrow", "mujhe kal ke liye kuch karna hai kya" to "BeforeTomorrow",
        "what do i need to check before the next trading day" to "BeforeTomorrow", "kya karna padega kal se pehle" to "BeforeTomorrow",
        // Its neighbours: what expires (ExpiryEve), the morning's own checklist (PreMarket).
        "what's expiring tomorrow for me" to "ExpiryEve", "pre market checklist" to "PreMarket", "is everything set for today" to "PreMarket",
        // ==== Round 16: the newest families, and today's new things asked ====
        // ---- LikeToday, more of it ----
        "is today similar to any past day" to "LikeToday", "find days like today" to "LikeToday", "any past day like today for nifty" to "LikeToday",
        "how did similar days end" to "LikeToday", "aaj jaisa din kab aaya tha" to "LikeToday", "aaj jaise din pe market kaise band hua" to "LikeToday",
        "pehle aaj jaisa din kab tha banknifty mein" to "LikeToday", "days that started like today" to "LikeToday", "aaj jaise din ke baad kya hua" to "LikeToday",
        "have we seen a day like this before" to "LikeToday", "is there a past day that matches today" to "LikeToday",
        // ---- SplitDays, more of it ----
        "how often do nifty and banknifty go opposite ways" to "SplitDays", "what happens the day after nifty and banknifty split" to "SplitDays",
        "how often does banknifty go the other way from nifty" to "SplitDays", "divergence between nifty and banknifty history" to "SplitDays",
        "after nifty and banknifty diverge what happens next day" to "SplitDays", "nifty aur banknifty alag alag direction mein kitni baar band hue" to "SplitDays",
        "nifty banknifty ulta kab chalte hain" to "SplitDays", "nifty upar banknifty neeche kitni baar hua" to "SplitDays",
        "how many times did nifty rise and banknifty fall" to "SplitDays",
        // ---- MorningAsks: the question Boss asks every morning ----
        "what do i ask every morning" to "MorningAsks", "what do you offer me in the morning" to "MorningAsks", "don't offer me the morning question" to "MorningAsks",
        "main roz subah kya poochta hoon" to "MorningAsks", "what do i usually ask in the morning" to "MorningAsks", "no more morning offers" to "MorningAsks",
        "which questions do i ask in the morning" to "MorningAsks", "what is my usual morning question" to "MorningAsks",
        "which morning questions have you learned" to "MorningAsks", "subah wala sawal offer mat karo" to "MorningAsks",
        // ---- ExpiryEve, more of it ----
        "what expires tomorrow for me" to "ExpiryEve", "kal mera kya expire hoga" to "ExpiryEve", "are any of my legs expiring tomorrow" to "ExpiryEve",
        "what's expiring tomorrow in my account" to "ExpiryEve", "which of my trades expire tomorrow" to "ExpiryEve",
        "mere positions kal expire ho rahe hain kya" to "ExpiryEve", "kal kaunse options expire ho rahe hain mere" to "ExpiryEve",
        // ---- StreamHealth, more of it: "why did the stream drop" ----
        "why did the stream drop" to "StreamHealth", "why did the live stream drop" to "StreamHealth", "why did the price stream disconnect" to "StreamHealth",
        "stream kyun drop hua" to "StreamHealth", "live data kyun band ho gaya" to "StreamHealth", "why did live prices stop" to "StreamHealth",
        "why does my live feed keep dropping" to "StreamHealth", "is the live stream connected" to "StreamHealth", "why did the websocket disconnect" to "StreamHealth",
        "stream baar baar kyun toot raha hai" to "StreamHealth", "why did the stream stop this morning" to "StreamHealth",
        "live stream ka kya haal hai" to "StreamHealth", "how many times did the stream drop today" to "StreamHealth",
        // ---- RelayHealth, more of it ----
        "is the relay up" to "RelayHealth", "why is the relay down" to "RelayHealth", "relay kyun fail ho raha hai" to "RelayHealth",
        "relay connect kyun nahi ho raha" to "RelayHealth", "is my static ip working right now" to "RelayHealth", "why can't i connect to the relay" to "RelayHealth",
        "relay server down hai kya" to "RelayHealth", "when did the relay last work" to "RelayHealth", "is the relay server reachable" to "RelayHealth",
        "static ip kyun fail hua" to "RelayHealth", "why did the relay stop" to "RelayHealth",
        // ---- WatchAsk: the order watch's state and IraAlgo's battery setting, read only ----
        "is the order watch running" to "WatchAsk", "is the watch running" to "WatchAsk", "is my order watch on" to "WatchAsk",
        "order watch chal raha hai kya" to "WatchAsk", "order watch status" to "WatchAsk", "how is the order watch" to "WatchAsk",
        "is the order watch stuck" to "WatchAsk", "order watch band hai kya" to "WatchAsk", "is the order watch still running" to "WatchAsk",
        "why did the watch get stuck" to "WatchAsk", "why did the order watch stop" to "WatchAsk", "why is the order watch stuck" to "WatchAsk",
        "watch kyun ruk gaya" to "WatchAsk", "order watch kyun band hua" to "WatchAsk", "what stopped the order watch" to "WatchAsk",
        "why does the order watch keep stopping" to "WatchAsk", "order watch kyun atak gaya" to "WatchAsk", "why was the order watch stuck this morning" to "WatchAsk",
        "battery setting kya hai" to "WatchAsk", "what is my battery setting" to "WatchAsk", "is iraalgo battery unrestricted" to "WatchAsk",
        "is the app battery optimized" to "WatchAsk", "what's iraalgo's battery setting" to "WatchAsk", "battery unrestricted hai kya" to "WatchAsk",
        "kya mera phone app ko rok raha hai" to "WatchAsk", "is my phone stopping the app" to "WatchAsk", "is android killing the app" to "WatchAsk",
        "is the phone killing iraalgo" to "WatchAsk", "is android stopping the watch" to "WatchAsk", "kya phone iraalgo ko band kar raha hai" to "WatchAsk",
        "is my phone killing the app in the background" to "WatchAsk", "kya android app ko band kar deta hai" to "WatchAsk",
        // Their neighbours: the watchlist, the market, a range, the kill switch, the bots' own state stay where they were.
        "how many times did nifty rise and fall today" to "Market", "what is in my watchlist" to "Market", "is the kill switch on" to "Account:RISK",
        "is orb running" to "Account:STRATEGIES",
        // ---- Round 17. ArmFit, more of it ----
        "which arm suits today" to "ArmFit", "which bot suits today's market" to "ArmFit", "which strategy fits today" to "ArmFit",
        "which of my bots is best for today" to "ArmFit", "what arm works best today" to "ArmFit", "which algo is right for today" to "ArmFit",
        "how did orb do on days like today" to "ArmFit", "how do my strategies perform on days like this" to "ArmFit",
        "my arms' record on days like today" to "ArmFit", "which arm suits today's conditions" to "ArmFit",
        "which of my strategies suits a day like today" to "ArmFit", "how does range fade do on days like today" to "ArmFit",
        "how did liquidity fare on sessions like today" to "ArmFit", "aaj kaunsa arm suit karta hai" to "ArmFit",
        "aaj ke liye kaun sa strategy fit hai" to "ArmFit", "aaj jaise din pe mere arms kaise chalte hain" to "ArmFit",
        "aaj konsa bot theek baithega" to "ArmFit", "aaj ke market ke liye kaunsa algo suit karega" to "ArmFit",
        "aaj jaise dino mein meri strategies kaisi rehti hain" to "ArmFit", "aaj ke din konsi strategy fit hoti hai" to "ArmFit",
        // ---- RoundCloses, more of it ----
        "does nifty respect round numbers" to "RoundCloses", "how often does nifty close near a round number" to "RoundCloses",
        "do round numbers act as magnets for banknifty" to "RoundCloses", "nifty round figure pe kitni baar band hota hai" to "RoundCloses",
        "historically does nifty close near round thousands" to "RoundCloses", "how often does banknifty settle near round 500s" to "RoundCloses",
        "gol number pe nifty aksar band hota hai kya" to "RoundCloses", "round number stats for sensex" to "RoundCloses",
        "do nifty closes stick to round levels" to "RoundCloses", "how often does finnifty finish near a round hundred" to "RoundCloses",
        "is there a round number magnet on nifty closes" to "RoundCloses", "banknifty gol figure ke paas kitne din band hua" to "RoundCloses",
        "do psychological levels attract nifty closes" to "RoundCloses", "nifty ka round number record kya hai" to "RoundCloses",
        // ---- TurnDowns, more of it ----
        "why do i reject your trade ideas" to "TurnDowns", "why do i keep turning down your ideas" to "TurnDowns",
        "what reasons do i give for turning down your trades" to "TurnDowns", "don't remind me why i rejected your ideas" to "TurnDowns",
        "stop reminding me why i turn your ideas down" to "TurnDowns", "main tumhare ideas kyun reject karta hoon" to "TurnDowns",
        "mere reasons mat yaad dilao" to "TurnDowns", "forget why i reject your ideas" to "TurnDowns",
        "why did you tell me why i turned it down" to "TurnDowns", "what reason do you tell me before i answer" to "TurnDowns",
        "why do i usually say no to your suggestions" to "TurnDowns", "which reasons do i usually have for rejecting your ideas" to "TurnDowns",
        "no need to tell me why i turned your ideas down" to "TurnDowns", "main aapke trade kyun mana kar deta hoon" to "TurnDowns",
        "meri wajah mat batao" to "TurnDowns", "stop telling me why i reject your ideas" to "TurnDowns",
        // ---- BeforeTomorrow, more of it ----
        "what should i do before tomorrow" to "BeforeTomorrow", "anything left to do before tomorrow" to "BeforeTomorrow",
        "what do i need to do for tomorrow" to "BeforeTomorrow", "what do i need to take care of before the next trading day" to "BeforeTomorrow",
        "give me tomorrow's checklist" to "BeforeTomorrow", "kal ke liye kya kya karna hai" to "BeforeTomorrow",
        "kal subah se pehle kya karna padega" to "BeforeTomorrow", "kuch karna hai kal ke liye" to "BeforeTomorrow",
        "what must i get done before tomorrow" to "BeforeTomorrow", "is there anything to do before tomorrow" to "BeforeTomorrow",
        "read me the checklist for tomorrow" to "BeforeTomorrow", "what do i have to check before tomorrow morning" to "BeforeTomorrow",
        "kya karna hoga kal se pehle" to "BeforeTomorrow", "mujhe kal subah ke liye kya karna chahiye" to "BeforeTomorrow",
        "what else do i need to do before tomorrow" to "BeforeTomorrow", "jarvis what should i sort out for tomorrow" to "BeforeTomorrow",
        // ---- BatteryUse, more of it ----
        "why is jarvis eating so much battery" to "BatteryUse", "is the app draining my battery" to "BatteryUse",
        "how much battery does iraalgo use" to "BatteryUse", "what is draining my battery" to "BatteryUse",
        "why does my phone battery drain so fast" to "BatteryUse", "app bahut battery kha raha hai" to "BatteryUse",
        "jarvis kitni battery leta hai" to "BatteryUse", "battery kyun ja rahi hai" to "BatteryUse", "what's running in the background" to "BatteryUse",
        "battery drain report" to "BatteryUse", "why is my battery dying so fast" to "BatteryUse",
        "iraalgo itni battery kyun kha raha hai" to "BatteryUse", "kyun phone itni battery kha raha hai" to "BatteryUse",
        "background mein kya kya chal raha hai" to "BatteryUse",
        // ---- WatchAsk, more of it ----
        "is the order watch working" to "WatchAsk", "is my order watch alive" to "WatchAsk", "order watch chalu hai kya" to "WatchAsk",
        "why did the order watch freeze" to "WatchAsk", "what stalled the order watch" to "WatchAsk",
        "is iraalgo's battery set to unrestricted" to "WatchAsk", "is my phone restricting iraalgo" to "WatchAsk",
        "kya android watch ko rok raha hai" to "WatchAsk", "how's the order watch doing" to "WatchAsk", "order watch kyun latak gaya" to "WatchAsk",
        "why did my order watch crash" to "WatchAsk", "watch kyun band ho gaya" to "WatchAsk", "is battery optimization on for iraalgo" to "WatchAsk",
        "kya phone app ko sleep mein daal raha hai" to "WatchAsk",
        // ---- A learned speech habit's undo said with "stop": the undo, never a strategy to stop ----
        "stop offering my morning question" to "MorningAsks", "stop offering me the morning question" to "MorningAsks",
        "stop offering my usual morning question" to "MorningAsks", "stop offering the morning question at the morning check" to "MorningAsks",
        "stop shortening your briefings" to "TalkHours", "stop cutting your briefings short" to "TalkHours",
        "stop saying the coin toss" to "HonestStars", "stop adding your record to your confidence" to "HonestStars",
        "stop qualifying your confidence" to "HonestStars", "stop skipping items in the morning check" to "MorningSense",
        "stop shortening the morning check" to "MorningSense",
        // ---- Round 18. MonthTurns, more of it ----
        "how does nifty usually do in the first few days of the month" to "MonthTurns", "does banknifty rally at the start of the month" to "MonthTurns",
        "is there a month end effect on nifty" to "MonthTurns", "how does nifty behave at month end" to "MonthTurns",
        "what is the turn of the month record for banknifty" to "MonthTurns", "month start stats for nifty" to "MonthTurns",
        "does nifty tend to rise in the last 3 days of the month" to "MonthTurns", "how volatile is banknifty at the end of the month" to "MonthTurns",
        "is the start of the month stronger than the rest for nifty" to "MonthTurns", "month end vs rest of the month for sensex" to "MonthTurns",
        "turn of the month effect on finnifty" to "MonthTurns", "does nifty fall at the end of the month usually" to "MonthTurns",
        "historically how does nifty perform at the start of a new month" to "MonthTurns", "is month end bullish for nifty" to "MonthTurns",
        "how does nifty usually trade at the end of each month" to "MonthTurns", "what happens to nifty at month end" to "MonthTurns",
        "how bearish is nifty at month end" to "MonthTurns", "what happens to banknifty at the turn of the month" to "MonthTurns",
        "does sensex usually trade higher at the start of the month" to "MonthTurns",
        "mahine ke shuru mein nifty kaisa rehta hai" to "MonthTurns", "mahine ke end mein banknifty kaisa chalta hai" to "MonthTurns",
        "month ke last days mein nifty aksar girta hai kya" to "MonthTurns", "mahine ki shuruaat mein banknifty upar jata hai kya" to "MonthTurns",
        "month ki shuruat mein nifty kaisa hota hai" to "MonthTurns", "mahine ke aakhir mein nifty kaisa chalta hai" to "MonthTurns",
        "month start pe nifty ka record kya hai" to "MonthTurns", "month turn pe banknifty kaisa chalta hai" to "MonthTurns",
        "mahine ke end mein nifty girta hai kya" to "MonthTurns", "mahine ki shuruaat mein nifty chadhta hai kya" to "MonthTurns",
        "month ke shuru mein banknifty kya karta hai" to "MonthTurns",
        // ---- LunchRange, more of it ----
        "how wide is the lunch range on nifty" to "LunchRange", "does the lunch range break up or down usually" to "LunchRange",
        "how often does banknifty break above the lunch range" to "LunchRange", "is the midday range narrower than the morning" to "LunchRange",
        "what happens after the lunch range breaks" to "LunchRange", "does nifty hold the lunch range breakout" to "LunchRange",
        "lunch hour range stats for banknifty" to "LunchRange", "does the afternoon break the lunch box" to "LunchRange",
        "how often does nifty break below the lunch range" to "LunchRange", "noon range breakout stats for nifty" to "LunchRange",
        "what is today's lunch range" to "LunchRange", "how is the lunch hour range today" to "LunchRange",
        "lunch range ka breakout kab hota hai" to "LunchRange", "lunch ke baad nifty range todta hai kya" to "LunchRange",
        "dopahar ki range kitni hoti hai nifty mein" to "LunchRange", "lunch time ki range upar tootti hai ya neeche" to "LunchRange",
        "lunch ki range kitni choti hoti hai" to "LunchRange", "lunch range todne ke baad banknifty wapas aata hai kya" to "LunchRange",
        "dopahar mein nifty ki range kaisi rehti hai" to "LunchRange", "lunch ka box kab tootta hai" to "LunchRange",
        "lunch ke baad banknifty box todta hai kya" to "LunchRange", "lunch mein nifty ka range kitna hota hai" to "LunchRange",
        // Their neighbours: when the day's high comes and how quiet lunch is stay the day's clock's; one month's move PeriodMove's.
        "how often is the day's high made in the lunch hour" to "DayClock", "is lunchtime the quietest part of the day on nifty" to "DayClock",
        "how did nifty do this month" to "PeriodMove", "what will nifty do at month end" to "Outlook",
        // ---- WeakLink, more of it ----
        "where do my trades usually go wrong" to "WeakLink", "what is the weakest link in my trading" to "WeakLink",
        "what are the weak spots in my bots" to "WeakLink", "what goes wrong most often with my strategies" to "WeakLink",
        "what usually goes wrong in my bots" to "WeakLink", "where do my arms usually go wrong" to "WeakLink",
        "what is my weakest point" to "WeakLink", "my weakest links" to "WeakLink",
        "which part of my setup is weakest" to "WeakLink", "what keeps going wrong in my paper trades" to "WeakLink",
        "where does my system go wrong" to "WeakLink", "what is the weak spot in my system" to "WeakLink",
        "what goes wrong in my trades the most" to "WeakLink", "which area of my trading is weakest" to "WeakLink", "what part of my bots is weak" to "WeakLink",
        "mere bots mein aksar kya galat hota hai" to "WeakLink",
        "mere setup mein kamzor kadi kaunsi hai" to "WeakLink", "meri strategies mein baar baar kya galat hota hai" to "WeakLink",
        "mera sabse kamzor point kya hai" to "WeakLink", "mere arms mein aksar kahan gadbad hoti hai" to "WeakLink",
        "mere trade kahan galat jaate hain" to "WeakLink", "sabse kamzor kadi batao" to "WeakLink", "mere bots kahan galat hote hain" to "WeakLink",
        "meri strategy kahan gadbad hoti hai" to "WeakLink", "mera kamzor hissa kya hai" to "WeakLink",
        // Its neighbour: one day's arm trades stay ArmDay's.
        "why did orb lose today" to "ArmDay",
        // ---- TopicLength, more of it ----
        "which topics do you say in detail" to "TopicLength",
        "how short do i like your answers" to "TopicLength", "what answer length do i prefer by topic" to "TopicLength",
        "do you know how long i like my answers" to "TopicLength", "which topics do i like short" to "TopicLength",
        "reset how short i like your answers" to "TopicLength",
        "reset how detailed i like your answers" to "TopicLength", "stop shortening answers by topic" to "TopicLength",
        "dont shorten answers by topic" to "TopicLength", "do not shorten answers by topic" to "TopicLength", "dont lengthen answers by topic" to "TopicLength",
        "stop giving topics short" to "TopicLength", "how long do you think i like your answers" to "TopicLength",
        "how detailed do you think i like your answers" to "TopicLength", "which topics do you cut short" to "TopicLength",
        "which topics do i prefer in detail" to "TopicLength", "do you shorten some topics" to "TopicLength", "do you cut short any topics" to "TopicLength",
        "which topics do you keep brief" to "TopicLength",
        "kaunse topic detail mein bolte ho" to "TopicLength",
        "sab topic usual length mein batao" to "TopicLength",
        // ---- WatchAsk: "the position watch" is the order watch (it watches the positions' stops and targets), read only ----
        "is the position watch on" to "WatchAsk", "is the position watch running" to "WatchAsk", "is my positions watch working" to "WatchAsk",
        "is the positions watch alive" to "WatchAsk", "position watch chal raha hai kya" to "WatchAsk", "why did the position watch stop" to "WatchAsk",
        "is my position watch working" to "WatchAsk", "the position watch status" to "WatchAsk", "how is the position watch doing" to "WatchAsk",
        "is the position watcher running" to "WatchAsk", "why did my position watch stop" to "WatchAsk",
        // ---- Round 19. OpenHighLow, more of it ----
        "how often does nifty open at its high" to "OpenHighLow", "how often does banknifty open at its low" to "OpenHighLow",
        "how often does sensex open at its high" to "OpenHighLow", "how often is the open the low of the day for banknifty" to "OpenHighLow",
        "open high days record for nifty" to "OpenHighLow", "what happens on open low days" to "OpenHighLow", "how do open low days close" to "OpenHighLow",
        "open equals high days for sensex" to "OpenHighLow", "does nifty fall on open high days" to "OpenHighLow",
        "how many days was the open the high" to "OpenHighLow", "is today an open high day" to "OpenHighLow",
        "was today's open the low of the day" to "OpenHighLow", "o=h days for banknifty" to "OpenHighLow",
        "how often did the open equal the high last month" to "OpenHighLow",
        "open high wale din kitne hote hain" to "OpenHighLow", "open low wale din banknifty kaise band hota hai" to "OpenHighLow",
        "open high wale din nifty kaise band hota hai" to "OpenHighLow", "kitni baar open hi high hota hai" to "OpenHighLow",
        "kitni baar open hi low hota hai" to "OpenHighLow", "open high open low ka record batao" to "OpenHighLow",
        "open high ya open low ka record batao" to "OpenHighLow", "open high day hai kya aaj" to "OpenHighLow",
        // ---- BigCandles, more of it ----
        "what follows a big candle in the first hour" to "BigCandles", "does a big red candle in the morning continue" to "BigCandles",
        "how often does a big 5 minute candle reverse" to "BigCandles", "big candle stats for nifty" to "BigCandles",
        "after a large opening candle does banknifty hold the move" to "BigCandles", "what happens after a huge green candle at the open" to "BigCandles",
        "do big first hour candles follow through on sensex" to "BigCandles", "how often does a 0.5% candle in the first hour continue" to "BigCandles",
        "how often does a 0.4% candle in the first hour hold" to "BigCandles",
        "subah ki badi candle ke baad banknifty kya karta hai" to "BigCandles", "pehle ghante mein bada candle aaye to nifty kaisa chalta hai" to "BigCandles",
        "badi red candle ke baad nifty wapas aata hai kya" to "BigCandles", "lambi candle ke baad nifty kya karta hai" to "BigCandles",
        "big candle ke baad follow through hota hai kya" to "BigCandles",
        // ---- OutlookCheck, more of it ----
        "did your outlook hold today" to "OutlookCheck", "is your outlook accurate" to "OutlookCheck", "how accurate are your outlooks" to "OutlookCheck",
        "what's your outlook hit rate" to "OutlookCheck", "how often is your morning outlook right" to "OutlookCheck",
        "check your outlooks against the close" to "OutlookCheck", "how did your morning call do" to "OutlookCheck",
        "were your outlooks right this week" to "OutlookCheck", "tumhara outlook kitna sahi hai" to "OutlookCheck",
        "aapka outlook kitna sahi hota hai" to "OutlookCheck", "aaj ka subah ka outlook sahi tha" to "OutlookCheck",
        "tera outlook kitna sahi nikalta hai" to "OutlookCheck", "subah ka outlook sahi nikla" to "OutlookCheck",
        // Its neighbour: the outlook itself (a read, never its record).
        "what's the outlook for today" to "Outlook",
        // ---- Headroom's LOSS at his stops (AtStops), more of it ----
        "what is my worst case today" to "Headroom", "what's the worst case for today" to "Headroom", "tell me my max possible loss" to "Headroom",
        "what if every stop gets hit" to "Headroom", "how much would i lose if all my stops are hit" to "Headroom",
        "if all my stops get hit how much do i lose" to "Headroom", "worst case right now" to "Headroom",
        "what is my maximum possible loss today" to "Headroom", "aaj zyada se zyada kitna loss ho sakta hai" to "Headroom",
        "aaj max kitna nuksan ho sakta hai" to "Headroom", "saare stop lag gaye to kitna nuksan" to "Headroom",
        "saare stop lag gaye to kitna loss hoga" to "Headroom", "sab stops hit ho jaye to kitna loss hoga" to "Headroom",
        "agar sab stop hit ho gaye to kitna" to "Headroom",
    )

    /** The new wordings at Boss's stops (round 19): each Headroom's LOSS, read by [AtStops.ASKED]. */
    private val AT_STOPS_19 = listOf("what is my worst case today", "what's the worst case for today", "tell me my max possible loss",
        "what if every stop gets hit", "how much would i lose if all my stops are hit", "if all my stops get hit how much do i lose", "worst case right now",
        "what is my maximum possible loss today", "aaj zyada se zyada kitna loss ho sakta hai", "aaj max kitna nuksan ho sakta hai",
        "saare stop lag gaye to kitna nuksan", "saare stop lag gaye to kitna loss hoga", "sab stops hit ho jaye to kitna loss hoga", "agar sab stop hit ho gaye to kitna")

    /**
     * Round 19: 60-odd everyday things Boss asks (English and Hinglish), each with where it must go - none an order, a
     * command or a Bundle act - after the many families added since the first rounds.
     */
    private val EVERYDAY: List<Pair<String, String>> = listOf(
        // Where the index is.
        "nifty kaha hai" to "Market", "nifty kahan hai" to "Market", "where is nifty" to "Market", "nifty kitna hai" to "Market",
        "nifty kitne pe hai" to "Market", "banknifty kya chal raha hai" to "Market", "what's nifty at" to "Market", "how's the market" to "Market",
        "how is banknifty doing" to "Market", "vix kitna hai" to "Market", "any news" to "Market", "aaj ki news kya hai" to "Market",
        "what should i do" to "Market",
        // Jarvis's plan for the day.
        "aaj ka plan" to "Agenda", "aaj ka plan kya hai" to "Agenda", "what's the plan for today" to "Agenda",
        // His P&L, positions, money and orders.
        "mera p&l" to "Account:PNL", "mera pnl kitna hai" to "Account:PNL", "what's my p&l today" to "Account:PNL", "how much did i make today" to "Account:PNL",
        "am i in profit" to "Account:PNL", "kitna kamaya aaj" to "Account:PNL",
        "show my positions" to "Account:POSITIONS", "mere positions dikhao" to "Account:POSITIONS", "meri positions kaisi hain" to "Account:POSITIONS",
        "what's my margin" to "Account:FUNDS", "kitna margin bacha hai" to "Account:FUNDS",
        "what are my open orders" to "Account:ORDERS", "koi order pending hai kya" to "Account:ORDERS", "what's my risk" to "Account:RISK",
        "how much can i lose today" to "Headroom",
        // What to do now: the trade check (never an order).
        "kya karu" to "TradeCheck", "ab kya karu" to "TradeCheck", "kya karun" to "TradeCheck", "kya karna chahiye" to "TradeCheck",
        "should i trade today" to "TradeCheck", "aaj trade karu ya nahi" to "TradeCheck",
        // His bots.
        "orb ka kya haal" to "Account:STRATEGIES", "orb ka kya haal hai" to "Account:STRATEGIES", "how is orb doing" to "Account:STRATEGIES",
        "did any bot trade today" to "Account:STRATEGIES", "how are my bots doing" to "Account:BOTS",
        // The market's hours and days, the expiry.
        "market kab khulega" to "OptionFacts", "when does the market open" to "OptionFacts", "nifty ka lot size" to "OptionFacts",
        "is the market open" to "Account:STATUS", "market khula hai kya" to "Account:STATUS", "market band hai kya" to "Account:STATUS",
        "kya market band hai" to "Account:STATUS", "am i logged in" to "Account:STATUS",
        "aaj market band hai kya" to "MarketDays", "kal market khulega kya" to "MarketDays", "kal expiry hai kya" to "MarketDays",
        "when is the next expiry" to "MarketDays", "is tomorrow expiry" to "MarketDays", "aaj expiry hai kya" to "MarketDays", "expiry kab hai" to "MarketDays",
        // The chain, why it moved, the day, the app.
        "what's the pcr" to "Account:CHAIN", "max pain kya hai" to "Account:CHAIN",
        "why is nifty falling" to "Causes", "nifty kyun gir raha hai" to "Causes",
        "how was my day" to "DaySummary", "aaj ka din kaisa raha" to "DaySummary",
        "paper mode hai ya live" to "Account:SETTINGS", "what's the outlook for today" to "Outlook", "what is theta" to "Glossary", "what can you do" to "Help",
    )

    /** The commands Boss says every day beside them: each stays a command (through its own confirm). */
    private val EVERYDAY_ACTS: List<Pair<String, Command.Kind>> = listOf(
        "kill switch on karo" to Command.Kind.KILL_ON, "kill switch on" to Command.Kind.KILL_ON, "kill switch off" to Command.Kind.KILL_OFF,
        "close all" to Command.Kind.CLOSE_ALL, "close all positions" to Command.Kind.CLOSE_ALL, "square off everything" to Command.Kind.CLOSE_ALL,
        "exit all positions" to Command.Kind.CLOSE_ALL, "sab positions band karo" to Command.Kind.CLOSE_ALL, "sab band karo" to Command.Kind.STOP_ALL,
        "stop all strategies" to Command.Kind.STOP_ALL, "stop orb" to Command.Kind.STOP_ONE, "start orb" to Command.Kind.START_ONE,
        "switch to paper" to Command.Kind.MODE_PAPER, "cancel my order" to Command.Kind.CANCEL_ONE, "sell my put" to Command.Kind.CLOSE_ONE)

    /** Round 17's undo wordings said with "stop" (each a learned speech habit's own undo), with the family each must get. */
    private val STOP_UNDO = listOf("stop offering my morning question", "stop offering me the morning question", "stop offering my usual morning question",
        "stop offering the morning question at the morning check", "stop shortening your briefings", "stop cutting your briefings short",
        "stop saying the coin toss", "stop adding your record to your confidence", "stop qualifying your confidence",
        "stop skipping items in the morning check", "stop shortening the morning check", "stop reminding me why i turn your ideas down",
        "stop telling me why i reject your ideas", "stop correcting your confidence words", "stop shortening answers by topic",
        "stop saying topics in detail")

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
    private val HUB_ORDER = listOf("DayJournal", "AlertSense", "Airtime", "Hearing", "PatternCalls", "TrendReads", "OutsideApp", "Clarity", "WordFit", "AskedAgain", "FigureFirst", "WrongThing", "ArmHabits", "MorningSense", "HonestStars", "TalkHours", "MorningAsks", "TurnDowns", "TopicLength", "OutlookCheck", "UsualIndex", "Nicknames", "LeadIndex", "LeadPart", "NextAsk", "NewsMoves",
        "TaxRecords.exportAsked", "Learnings", "Learnings.undoAsked", "PreMarket", "Headroom", "ArmFit", "WeakLink", "ArmChange", "PnlGap", "ArmDay", "BookDecay", "WhereIWin", "TradesADay", "AfterLoss", "NetLean", "ExpiryEve", "BeforeTomorrow", "BotTrades", "SwitchOff", "SaidAbout", "WeekAhead", "ZerodhaSession", "OrderWhy", "RelayHealth", "StreamHealth", "BatteryUse", "WatchAsk", "Tour", "DataAge", "Honest", "Thinking",
        "SelfWhy", "Consistency", "CoPilot", "SinceMorning", "ExpiryPin", "ChainDrift", "ChainIntel", "DayClock", "GapRecord", "RangeBreaks", "PriorDay", "LastHour", "InsideDays", "FirstMove", "VixNext", "SplitDays", "RoundCloses", "MonthTurns", "LunchRange", "OpenHighLow", "BigCandles", "ExtremeCloses", "WeekRange", "RelativeMove", "Comebacks", "VixBand", "Overnight", "DayAfter", "Weekdays", "DayCompare", "LikeToday", "Structure", "MindChange", "Breadth",
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
        assertTrue(all.size >= 120, "${all.size}")
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
        assertTrue(all.size >= 120, "${all.size}")
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

    // ---- Round 14: round 13's open items, and the newest families ----

    @Test fun roundFourteenWordingsNeitherOrderNorCommandNorBundle() {
        val newest = setOf("SinceMorning", "SwitchOff", "StreamHealth", "RelayHealth", "FirstMove", "VixNext", "MorningSense", "HonestStars", "ArmDay",
            "InsideDays", "PriorDay", "OutsideApp")
        val all = ASKED.filter { it.second in newest }
        assertTrue(all.size >= 120, "${all.size}")
        // Boss's own Hinglish, each where it belongs.
        for ((s, want) in listOf("stream kyun toot raha hai" to "StreamHealth", "subah se kya badla" to "SinceMorning",
            "kaun sa bot band karun" to "SwitchOff", "orb ka aaj loss kyun hua" to "ArmDay")) assertTrue(s to want in all, s)
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
        // The commands beside these words still act as before (each through its own confirm).
        for (s in listOf("stop orb", "switch off orb", "stop all strategies")) assertEquals("Act", audit.feature(s), s)
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("switch off orb").command?.kind)
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("was yesterday an nr7 day then close all positions", "did nifty break yesterday's high and stop all strategies",
            "subah se kya badla phir sab strategies band karo", "why did orb lose today then kill switch on"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
        // A web search is said politely with what the app has instead; nothing is searched. Any other outside ask keeps its words.
        for (s in listOf("search google for nifty news", "google nifty news", "look up nifty on google")) {
            assertTrue(OutsideApp.asked(s), s)
            assertEquals(OutsideApp.SAY_SEARCH, OutsideApp.say(s), s)
        }
        assertTrue(OutsideApp.SAY_SEARCH.startsWith("I can't search Google or the web, Boss") && "news desk" in OutsideApp.SAY_SEARCH)
        for (s in listOf("open youtube", "open google", "google maps kholo", "play music")) assertEquals(OutsideApp.SAY, OutsideApp.say(s), s)
        // The record questions stay the record; a forecast or an alert is never one session's place.
        assertEquals(false, PriorDay.asked("how often does nifty break yesterday's high")?.now)
        for (s in listOf("will nifty break yesterday's high", "alert me when nifty breaks yesterday's high", "should i buy above yesterday's high"))
            assertTrue(PriorDay.asked(s)?.now != true, s)
        assertEquals(null, InsideDays.asked("what happens after an inside day")?.one)
        for (s in listOf("will today be an inside day", "was yesterday an inside bar", "my inside day trades")) assertEquals(null, InsideDays.asked(s), s)
    }

    // ---- Round 15: round 14's open items, the expiry-eve question, and the newest families ----

    @Test fun roundFifteenWordingsNeitherOrderNorCommandNorBundle() {
        val newest = setOf("ExpiryPin", "NetLean", "TalkHours", "VixNext", "ArmDay", "ExpiryEve", "Glossary")
        val all = ASKED.filter { it.second in newest }
        assertTrue(all.size >= 100, "${all.size}")
        // Boss's own Hinglish, each where it belongs.
        for ((s, want) in listOf("kal kya expire ho raha hai" to "ExpiryEve", "orb aaj kyun fail hua" to "ArmDay", "main long hoon ya short" to "NetLean",
            "main tumse kab baat karta hoon" to "TalkHours", "inside day kya hota hai" to "Glossary")) assertTrue(s to want in all, s)
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
        // The commands beside these words still act as before (each through its own confirm), and are never these questions.
        for (s in listOf("stop orb", "switch off orb", "stop all strategies", "close all positions", "kill switch on", "square off my expiring positions",
            "close everything expiring tomorrow")) {
            assertEquals("Act", audit.feature(s), s)
            assertTrue(!ExpiryEve.asked(s) && ArmDay.asked(s) == null && !NetLean.asked(s), s)
        }
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop orb").command?.kind)
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("what expires tomorrow then close all positions", "why did orb fail today and stop orb",
            "am i net long or short then kill switch on"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
        // When the expiry is, a forecast, an alert or advice is never the expiry-eve question.
        for (s in listOf("when is the next expiry", "does nifty expire tomorrow", "which index expires tomorrow", "will nifty expire near max pain tomorrow",
            "should i close what expires tomorrow", "remind me what expires tomorrow", "what expired yesterday", "what is expiry"))
            assertTrue(!ExpiryEve.asked(s), s)
        // The answer: the checklist when something of his expires, else that nothing does - with whether Zerodha was read. Nothing acts.
        val d = java.time.LocalDate.of(2026, 10, 6)
        assertEquals("x", ExpiryEve.answer("x", d, true))
        assertTrue(ExpiryEve.answer(null, d, true).startsWith("Nothing you hold expires on the next trading day (Tue 6 Oct), Boss"))
        assertTrue("Zerodha isn't logged in" in ExpiryEve.answer(null, d, false))
        assertTrue("Boss" in ExpiryEve.answer(null, null, true) && "Boss" in ExpiryEve.LOCKED)
        // The word's meaning, never a figure asked about; one day's shape stays InsideDays'.
        assertTrue(Glossary.explain("what is an nr7 day")!!.startsWith("An inside day is"))
        for (s in listOf("is today an inside day", "was yesterday an nr7 day", "what happens after an inside day")) assertEquals("InsideDays", audit.feature(s), s)
    }

    // ---- Round 16: the newest families, and today's new things asked (the stream, the order watch, the battery setting) ----

    @Test fun roundSixteenWordingsNeitherOrderNorCommandNorBundle() {
        val newest = setOf("LikeToday", "SplitDays", "MorningAsks", "ExpiryEve", "StreamHealth", "RelayHealth", "WatchAsk")
        val all = ASKED.filter { it.second in newest }
        assertTrue(all.size >= 120, "${all.size}")
        // Boss's own words today, each where it belongs.
        for ((s, want) in listOf("why did the stream drop" to "StreamHealth", "is the order watch running" to "WatchAsk", "why did the watch get stuck" to "WatchAsk",
            "battery setting kya hai" to "WatchAsk", "kya mera phone app ko rok raha hai" to "WatchAsk")) assertTrue(s to want in all, s)
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
        // The commands beside these words still act as before (each through its own confirm), and are never these questions.
        for (s in listOf("stop orb", "switch off orb", "close all positions", "stop the order watch", "start the order watch", "stop the relay", "kill switch on")) {
            assertEquals("Act", audit.feature(s), s)
            assertTrue(WatchAsk.asked(s) == null && !StreamHealth.asked(s) && RelayHealth.asked(s) == null, s)
        }
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop orb").command?.kind)
        assertEquals(Command.Kind.CLOSE_ALL, Ask.parse("close all positions").command?.kind)
        // Asked to do, never answered as asked: a restart, the battery set, the watchlist.
        for (s in listOf("restart the watch", "set battery to unrestricted", "add nifty to watchlist", "what should i watch today", "watch nifty 25000",
            "is my battery low", "close the app", "kill the app", "is the kill switch on")) assertEquals(null, WatchAsk.asked(s), s)
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("is the order watch running then close all positions", "why did the stream drop and stop orb"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
    }

    // ---- Round 17: the newest families, and a learned habit's undo said with "stop" ----

    @Test fun roundSeventeenWordingsNeitherOrderNorCommandNorBundle() {
        val newest = setOf("ArmFit", "RoundCloses", "TurnDowns", "BeforeTomorrow", "BatteryUse", "WatchAsk")
        val all = ASKED.filter { it.second in newest }
        assertTrue(all.size >= 120, "${all.size}")
        // Wrong before this round, each now where it belongs.
        for ((s, want) in listOf("aaj konsa bot theek baithega" to "ArmFit", "does nifty respect round numbers" to "RoundCloses",
            "stop reminding me why i turn your ideas down" to "TurnDowns", "stop telling me why i reject your ideas" to "TurnDowns",
            "is there anything to do before tomorrow" to "BeforeTomorrow", "battery drain report" to "BatteryUse")) assertTrue(s to want in all, s)
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
        // A switch, a forecast or advice is never one of these questions; the commands beside them still act as before.
        for (s in listOf("should i run orb today", "start orb today", "will nifty close at 25000 today", "stop the order watch", "set battery to unrestricted"))
            assertTrue(!ArmFit.asked(s) && RoundCloses.asked(s) == null && WatchAsk.asked(s) == null && !BatteryUse.asked(s) && !BeforeTomorrow.asked(s), s)
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("which arm suits today then stop orb", "what do i need to do before tomorrow and close all positions",
            "why is the app draining my battery then kill switch on"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
    }

    @Test fun aLearnedHabitsUndoSaidWithStopIsNeverAStopCommand() {
        for (s in STOP_UNDO) {
            val p = Ask.parse(s)
            assertEquals(null, p.command, s); assertEquals(null, p.order, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Bundle.acts(s) && !FollowUp.acts(s) && !Reminder.asked(s) && !Reminder.cancelAsked(s), s)
            assertEquals(null, Intents.quick(s), s)
            // Its own undo takes it (the hub's branch is reached: no order, no command, not bundled), never "Act".
            val undo = MorningAsks.asked(s) == MorningAsks.Request.RESET || TurnDowns.asked(s) == TurnDowns.Request.RESET ||
                TalkHours.asked(s) == TalkHours.Request.RESET || HonestStars.asked(s) == HonestStars.Request.RESET ||
                WordFit.asked(s) == WordFit.Request.OFF || MorningSense.asked(s) == MorningSense.Request.RESET ||
                TopicLength.asked(s) == TopicLength.Request.RESET
            assertTrue(undo, s)
            assertTrue(audit.feature(s) in setOf("MorningAsks", "TurnDowns", "TalkHours", "HonestStars", "WordFit", "MorningSense", "TopicLength"), "$s: ${audit.feature(s)}")
            assertTrue(Wake.heard("Jarvis, $s", false) is Wake.Heard.Ask, s)
        }
        // The commands stay exactly as they were, each through its own confirm.
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop orb").command?.kind)
        assertEquals(Command.Kind.STOP_ALL, Ask.parse("stop all strategies").command?.kind)
        assertEquals(Command(Command.Kind.STOP_ONE, target = "order watch"), Ask.parse("stop the order watch").command)
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop strategy 2").command?.kind)
        for (s in listOf("stop orb", "stop all strategies", "stop the order watch", "stop strategy 2", "stop range fade")) assertEquals("Act", audit.feature(s), s)
        assertEquals(Wake.Heard.Stop, Wake.heard("Jarvis, stop listening", false))
        // Only a learned habit's own undo is let through: other "stop <doing>" words are no undo, and read as before.
        for (s in listOf("stop offering trades", "stop saying boss")) assertTrue(MorningAsks.asked(s) == null && HonestStars.asked(s) == null && TalkHours.asked(s) == null, s)
    }

    // ---- Round 18: the newest families, the position watch, and "stop <doing>" with no arm of that name ----

    @Test fun roundEighteenWordingsNeitherOrderNorCommandNorBundle() {
        val newest = setOf("MonthTurns", "LunchRange", "WeakLink", "TopicLength", "WatchAsk")
        val all = ASKED.filter { it.second in newest }
        assertTrue(all.size >= 150, "${all.size}")
        // Wrong before this round, each now where it belongs.
        for ((s, want) in listOf("is month end bullish for nifty" to "MonthTurns", "how does nifty usually trade at the end of each month" to "MonthTurns",
            "what happens to nifty at month end" to "MonthTurns", "mahine ki shuruaat mein banknifty upar jata hai kya" to "MonthTurns",
            "lunch ke baad nifty range todta hai kya" to "LunchRange", "dopahar mein nifty ki range kaisi rehti hai" to "LunchRange",
            "which part of my setup is weakest" to "WeakLink", "mera sabse kamzor point kya hai" to "WeakLink", "mere trade kahan galat jaate hain" to "WeakLink",
            "dont shorten answers by topic" to "TopicLength", "how long do you think i like your answers" to "TopicLength", "do you shorten some topics" to "TopicLength",
            "is the position watch on" to "WatchAsk")) assertTrue(s to want in all, s)
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
        // A forecast, advice, Boss's own book, an alert or one day's trades is never one of these records.
        for (s in listOf("should i trade at month end", "will nifty rise at the start of next month", "what will nifty do at month end",
            "should i buy at the start of the month", "how did my trades do at month end", "what happens to my put at month end",
            "how did nifty do this month", "what happens at month end expiry")) assertEquals(null, MonthTurns.asked(s), s)
        for (s in listOf("should i trade the lunch range breakout", "will nifty break the lunch range today", "alert me when nifty breaks the lunch range",
            "remind me at lunch")) assertEquals(null, LunchRange.asked(s), s)
        for (s in listOf("why did orb lose today", "what went wrong in yesterday's trade", "what should i fix in my setup")) assertTrue(!WeakLink.asked(s), s)
        // The length commands stay commands, and a bare wish is never the learned topics asked.
        for ((s, k) in listOf("shorter" to Command.Kind.BRIEF_ON, "short answers please" to Command.Kind.BRIEF_ON, "tell me more" to Command.Kind.MORE,
            "full answers" to Command.Kind.BRIEF_OFF)) { assertEquals(k, Ask.parse(s).command?.kind, s); assertEquals(null, TopicLength.asked(s), s) }
        for (s in listOf("in short", "detail mein batao")) assertEquals(null, TopicLength.asked(s), s)
        // The commands beside them still act as before (each through its own confirm).
        for (s in listOf("buy nifty at month end", "stop orb", "stop the order watch", "stop the position watch", "stop all strategies", "close all positions", "kill switch on"))
            assertEquals("Act", audit.feature(s), s)
        assertEquals(Command(Command.Kind.STOP_ONE, target = "position watch"), Ask.parse("stop the position watch").command)
        for (s in listOf("stop the position watch", "start the position watch", "watch my positions")) assertEquals(null, WatchAsk.asked(s), s)
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("what's the weakest link in my setup then stop orb", "is the position watch on and close all positions",
            "how does nifty do at month end then kill switch on"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
    }

    @Test fun aStopOfNoArmByThatNameIsNeverPickedWithoutAsking() {
        // "Stop offering trades" / "stop saying boss" are no learned habit's undo, so they read as before: a STOP_ONE of a
        // strategy by that name. Ask.parse reads words only (and is kept by them), so it cannot know Boss's own strategy,
        // Pine script and arm names - those live in the app. The app's pick then finds no arm of that name and asks
        // "Which one?" with the list: nothing is stopped unless Boss names one and confirms.
        val arms = TradeCheck.RECORD.keys.toList() + listOf("Strategy 1", "My Pine script")
        for ((s, target) in listOf("stop offering trades" to "offering trades", "stop saying boss" to "saying boss")) {
            val c = Ask.parse(s).command
            assertEquals(Command(Command.Kind.STOP_ONE, target = target), c, s)
            assertEquals(null, Commands.pick(c!!, arms), s)
            assertEquals(null, Ask.parse(s).order, s)
        }
        // A real arm named is still picked, exactly as before.
        assertEquals(arms.indexOf("ORB Sweep"), Commands.pick(Ask.parse("stop orb sweep").command!!, arms))
        assertEquals(arms.indexOf("Range Fade"), Commands.pick(Ask.parse("stop range fade").command!!, arms))
    }

    @Test fun theOrderWatchAnswerOnlyReads() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val local = java.time.LocalDateTime.of(2026, 10, 5, 9, 40)
        val now = local.atZone(zone).toInstant().toEpochMilli()
        val diary = listOf("10-05 09:35:02 [watch] STUCK: alive but no finished check · last check 09:31 · waiting on relay connect for 212s · battery OPTIMIZED (not Unrestricted)",
            "10-05 09:36:00 [stream] dropped: no data from Zerodha for 12 s")
        // Running and fresh.
        val ok = WatchAsk.answer(WatchAsk.Asked.STATUS, now, now - 30_000, now - 5_000, null, 0, false, true, diary, local, zone)
        assertTrue(ok.startsWith("Boss, the order watch is running: its last check was at 09:39."), ok)
        // Alive but waiting on a step: stuck, and what on.
        val busy = WatchAsk.answer(WatchAsk.Asked.STATUS, now, now - 9 * 60_000, now - 10_000, "relay connect", now - 240_000, true, true, diary, local, zone)
        assertTrue("running but stuck: it has waited 4 minutes on relay connect" in busy && "set IraAlgo's battery to Unrestricted" in busy, busy)
        // Why it got stuck: today's record in plain words.
        val why = WatchAsk.answer(WatchAsk.Asked.STUCK, now, now - 30_000, now - 5_000, null, 0, true, true, diary, local, zone)
        assertTrue(why.startsWith("Boss, at 09:35 it was stuck waiting on relay connect for 3 minutes."), why)
        // Dead in market hours; outside them it simply isn't running.
        val dead = WatchAsk.answer(WatchAsk.Asked.STATUS, now, now - 9 * 60_000, 0, null, 0, null, true, emptyList(), local, zone)
        assertTrue("isn't running: no check since 09:31" in dead && "opening IraAlgo restarts it" in dead, dead)
        assertTrue("runs only in market hours" in WatchAsk.answer(WatchAsk.Asked.STATUS, now, 0, 0, null, 0, false, false, emptyList(), local, zone))
        // The battery setting and the phone holding the app back.
        assertTrue("IraAlgo's battery is set to Unrestricted" in WatchAsk.answer(WatchAsk.Asked.BATTERY, now, now - 30_000, now - 5_000, null, 0, false, true, diary, local, zone))
        assertTrue(WatchAsk.answer(WatchAsk.Asked.PHONE, now, now - 30_000, now - 5_000, null, 0, true, true, diary, local, zone).startsWith("Boss, it can: IraAlgo is battery-optimized"))
        // Always "Boss", and nothing is changed or restarted by the answer.
        for (a in WatchAsk.Asked.values()) for (r in listOf(true, false, null)) {
            val s = WatchAsk.answer(a, now, now - 9 * 60_000, now - 10_000, "Zerodha quotes and positions", now - 300_000, r, true, diary, local, zone)
            assertTrue(s.startsWith("Boss, ") && s.endsWith("I don't change a setting or restart anything myself."), s)
        }
    }

    // ---- Round 19: the newest families, and the everyday questions after all the additions ----

    private fun neverActs(s: String) {
        val p = Ask.parse(s)
        assertEquals(null, p.order, s); assertEquals(null, p.command, s)
        assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
        assertTrue(!Bundle.acts(s), s)
        Intents.quick(s)?.let { r -> assertTrue(Ask.parse(r).order == null && Ask.parse(r).command == null && !Bundle.acts(r), "$s -> $r") }
        assertTrue(!Reminder.asked(s) && !Reminder.cancelAsked(s) && !FollowUp.acts(s), s)
        assertEquals(null, Reminder.parse(s, today.atTime(10, 0)), s)
        assertTrue(Understand.questions(null, s).orEmpty().none { FollowUp.acts(it) || Ask.parse(it).command != null || Ask.parse(it).order != null }, s)
    }

    @Test fun roundNineteenWordingsNeitherOrderNorCommandNorBundle() {
        val newest = setOf("OpenHighLow", "BigCandles", "OutlookCheck")
        val all = ASKED.filter { it.second in newest } + ASKED.filter { it.first in AT_STOPS_19 }
        assertTrue(all.size >= 80, "${all.size}")
        // Wrong before this round, each now where it belongs.
        for ((s, want) in listOf("how often does nifty open at its high" to "OpenHighLow", "kitni baar open hi high hota hai" to "OpenHighLow",
            "open high open low ka record batao" to "OpenHighLow", "how often does a 0.5% candle in the first hour continue" to "BigCandles",
            "saare stop lag gaye to kitna nuksan" to "Headroom")) assertTrue(s to want in all, s)
        for ((s, want) in all) { assertEquals(want, audit.feature(s), s); neverActs(s) }
        // At his stops, each is the day's LOSS room with the legs at their stops - never a what-if dropped for its "if".
        for (s in AT_STOPS_19) { assertEquals(Headroom.Asked.LOSS, Headroom.asked(s), s); assertTrue(AtStops.ASKED.containsMatchIn(" " + spacedWords(s.lowercase().replace("'", "")) + " "), s) }
        // A forecast, advice, an alert, his own book or a candle pattern is never one of these records.
        for (s in listOf("will nifty open at its high tomorrow", "should i buy on open low days", "alert me if the open is the high",
            "my open high trades")) assertEquals(null, OpenHighLow.asked(s), s)
        for (s in listOf("will the big candle continue", "should i buy after a big candle in the first hour", "what is a marubozu candle",
            "alert me on a big candle in the morning")) assertEquals(null, BigCandles.asked(s), s)
        for (s in listOf("what's the outlook for today", "what is your outlook for nifty", "give me the morning outlook")) assertTrue(!OutlookCheck.asked(s), s)
        // The first move's record keeps its own words.
        assertTrue(FirstMove.asked("how often does the first half hour decide the day") != null)
        // Graduation has no question: it is said once, when the paper record passes - nothing asks it, nothing switches.
        // Said with something to do, each is left to the multi-step plan (never answered and the action dropped).
        for (s in listOf("how often is the open the high of the day then stop orb", "what's my worst case today and close all positions",
            "did your outlook hold today then kill switch on"))
            assertTrue(Bundle.acts(s) || Ask.parse(s).command != null || Ask.parse(s).order != null, s)
    }

    @Test fun everydayQuestionsStillGoWhereTheyShould() {
        assertTrue(EVERYDAY.size >= 60, "${EVERYDAY.size}")
        assertEquals(EVERYDAY.size, EVERYDAY.map { it.first }.distinct().size)
        val wrong = EVERYDAY.mapNotNull { (s, want) -> audit.feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got ${hits(s)}" } }
        assertTrue(wrong.isEmpty(), "taken by the wrong feature (${wrong.size} of ${EVERYDAY.size}):\n" + wrong.joinToString("\n"))
        for ((s, _) in EVERYDAY) neverActs(s)
        // The commands stay commands, each through its own confirm; an order stays an order.
        for ((s, k) in EVERYDAY_ACTS) { assertEquals(k, Ask.parse(s).command?.kind, s); assertEquals("Act", audit.feature(s), s) }
        assertTrue(Ask.parse("buy 1 lot nifty 24500 ce").order != null)
        assertEquals("Act", audit.feature("buy 1 lot nifty 24500 ce"))
        // "Market band karo" is no market-hours question: it stays a stop of an arm by that name (the app asks which one).
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("market band karo").command?.kind)
    }

    // ---- Round 20: the newest families' wordings, Boss's reminders, his index corrections ----

    /** Round 20's question wordings, each with the feature it must get - none acts. */
    private val ROUND20 = listOf(
        // ExtremeCloses
        "how often does banknifty close at its high" to "ExtremeCloses", "how often does nifty close at the low" to "ExtremeCloses",
        "does sensex usually close near the day high" to "ExtremeCloses", "what happens next day after a close at the low" to "ExtremeCloses",
        "low pe close hone ke baad agle din kya hota hai" to "ExtremeCloses", "closing near the high record for finnifty" to "ExtremeCloses",
        "how often does nifty settle near its low" to "ExtremeCloses", "what happens the day after a strong close" to "ExtremeCloses",
        // DaySummary: the wrap-up's newest words (usefulness round 28)
        "summarize today" to "DaySummary", "give me a summary of my day" to "DaySummary", "end of day report" to "DaySummary",
        "eod summary" to "DaySummary", "summary of today" to "DaySummary", "wrap up my day" to "DaySummary", "how did my day go" to "DaySummary",
        "aaj ka summary" to "DaySummary", "can you summarise my day for me" to "DaySummary",
        // ArmChange ("is hafte ... pichle hafte ke mukable" was the account's history before this round)
        "what changed in my arms this week" to "ArmChange", "how did my strategies do this week vs last week" to "ArmChange",
        "compare my arms week on week" to "ArmChange", "is hafte mere bots kaise rahe pichle hafte ke mukable" to "ArmChange",
        "is hafte meri strategies kaisi rahi pichle hafte se" to "ArmChange",
        // WeekRange: the weekdays of the week's high and low
        "which day usually makes the week's high" to "WeekRange", "week ka high kis din banta hai" to "WeekRange",
        "on which day does nifty usually make its weekly high" to "WeekRange",
        // ReminderBook: the list only reads
        "what reminders do i have" to "ReminderBook", "list my reminders" to "ReminderBook", "any reminders" to "ReminderBook",
        "mere reminders kya hain" to "ReminderBook", "do i have any reminders today" to "ReminderBook", "which reminders are set" to "ReminderBook",
        // The everyday questions, still where they were
        "how is nifty" to "Market", "what's the vix" to "Market", "banknifty kitna gira" to "Market", "mera p&l kitna hai" to "Account:PNL",
        "how many trades today" to "Account:ORDERS", "what's my target" to "Account:PROTECTIONS",
    )
    /** One reminder named: ReminderBook's, waiting for Boss's Confirm. */
    private val CANCEL_ONE_20 = listOf("cancel the 14:30 reminder", "delete the reminder about nifty", "3 baje wala reminder hata do",
        "remove the reminder about expiry", "cancel the 3 pm reminder")
    /** All of them: the cancel-all, which names each and waits for Boss's Confirm. The first five were Missed before this round. */
    private val CANCEL_ALL_20 = listOf("sab reminders hata do", "saare reminders cancel karo", "mere sab reminders hata do", "all reminders hata do",
        "mere saare reminders hatao", "cancel all my reminders", "delete all reminders", "clear my reminders", "cancel my reminders")
    /** Boss's corrections of the index Jarvis took, with the index each means. */
    private val CORRECTIONS_20 = listOf("no banknifty" to Market.BANKNIFTY, "i meant sensex" to Market.SENSEX, "nahi finnifty ka" to Market.FINNIFTY,
        "not nifty, banknifty" to Market.BANKNIFTY, "mera matlab sensex se tha" to Market.SENSEX, "no i meant finnifty" to Market.FINNIFTY)

    @Test fun roundTwentyWordingsNeitherOrderNorCommandNorBundle() {
        assertEquals(ROUND20.size, ROUND20.map { it.first }.distinct().size)
        val wrong = ROUND20.mapNotNull { (s, want) -> audit.feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got ${hits(s)}" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        for ((s, _) in ROUND20) neverActs(s)
    }

    @Test fun remindersAreReadOrAskedFirstNeverDroppedUnasked() {
        for (s in CANCEL_ONE_20 + CANCEL_ALL_20) {
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            // Never a trade, a strategy or the kill switch; the cancel-all reads as "cancel my reminders" always has.
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            if (s in CANCEL_ALL_20) assertEquals(Bundle.acts("cancel my reminders"), Bundle.acts(s), s)
        }
        // One named: ReminderBook's pick (asked first in the hub); never the cancel-all.
        for (s in CANCEL_ONE_20) { assertEquals("ReminderBook", audit.feature(s), s); assertTrue(ReminderBook.cancelOne(s) != null, s) }
        // All of them: the cancel-all's Confirm (the hub names them and waits); never one picked.
        for (s in CANCEL_ALL_20) {
            assertEquals("Reminder", audit.feature(s), s)
            assertTrue(Reminder.cancelAsked(s), s); assertEquals(null, ReminderBook.cancelOne(s), s)
        }
        // The list only reads: never a cancel.
        for ((s, want) in ROUND20) if (want == "ReminderBook") { assertTrue(ReminderBook.listAsked(s), s); assertTrue(!Reminder.cancelAsked(s), s) }
        // Not the reminders: strategies, alarms and orders keep their own words.
        assertEquals(Command.Kind.STOP_ALL, Ask.parse("sab strategies band karo").command?.kind)
        for (s in listOf("sab alarms hata do", "saare orders cancel karo", "sab band karo", "remind me at 3 pm to check nifty")) assertTrue(!Reminder.cancelAsked(s), s)
    }

    @Test fun bossIndexCorrectionsAreReadAndNeverAct() {
        for ((s, m) in CORRECTIONS_20) {
            assertEquals(m, UsualIndex.correction(s), s)
            neverActs(s)
        }
        for (s in listOf("no", "no thanks", "how is banknifty", "buy banknifty", "no banknifty is falling")) assertEquals(null, UsualIndex.correction(s), s)
    }

    // ---- Round 21: Boss's everyday questions as he says them ----

    /** Round 21's phrasings, each with the feature it must get (every one went wrong before this round) - none acts. */
    private val ROUND21 = listOf(
        // His book as "we", and in traders' words
        "how much are we down" to "Account:PNL", "how much did we make today" to "Account:PNL", "did we make money today" to "Account:PNL",
        "are we in the green" to "Account:PNL", "where do i stand today" to "Account:PNL", "am i bleeding" to "Account:PNL",
        // An index holding a level is the market's, never his holdings
        "is nifty holding up" to "Market", "how's nifty holding up" to "Market", "is the market holding up" to "Market",
        // The wrap-up with "today" said after it
        "how was my day today" to "DaySummary",
        // P&L clipped or misheard by the recognizer
        "what's my pl" to "Account:PNL", "what's my pin l" to "Account:PNL",
        // Hinglish
        "paisa bana kya aaj" to "Account:PNL", "aaj ka hisaab" to "Account:PNL", "hisaab batao" to "Account:PNL",
        "account kaisa hai" to "Account:PNL+POSITIONS+ORDERS+STRATEGIES", "koi trade chal raha hai kya" to "Account:POSITIONS",
        "how's my book" to "Account:PNL+POSITIONS+ORDERS+STRATEGIES",
        // Jarvis's own check, asked with "kya" or in English
        "sab thik hai kya" to "SelfCheck", "kya sab theek hai" to "SelfCheck", "is everything okay" to "SelfCheck",
    )

    @Test fun roundTwentyOneWordingsRouteAndNeverAct() {
        assertEquals(ROUND21.size, ROUND21.map { it.first }.distinct().size)
        val wrong = ROUND21.mapNotNull { (s, want) -> audit.feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got ${hits(s)}" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        for ((s, _) in ROUND21) neverActs(s)
        // Neighbours keep their own: the market asked as "we", his holdings and PIN, and closing what he holds stays a command.
        assertEquals("Market", audit.feature("are we up or down today"))
        for (s in listOf("what am i holding", "am i holding anything", "show my holdings")) assertEquals("Account:POSITIONS", audit.feature(s), s)
        assertEquals("Account:SETTINGS", audit.feature("what is my pin"))
        assertEquals("Headroom", audit.feature("where do i stand against my limits"))
        for (s in listOf("book my profit", "sell my nifty put")) assertEquals(Command.Kind.CLOSE_ONE, Ask.parse(s).command?.kind, s)
        assertEquals(null, Heard.fix("pl show nifty").takeIf { it != "pl show nifty" }, "a bare pl is never P&L")
    }

    private val ROUND22 = listOf(
        // Theta on his book, asked loosely
        "what's theta doing for me" to "BookDecay", "is theta on my side" to "BookDecay", "am i theta positive" to "BookDecay",
        "is my book theta negative" to "BookDecay", "what's the decay on my strangle" to "BookDecay", "how much theta does my strangle make" to "BookDecay",
        "theta se kitna kama raha hoon" to "BookDecay",
        // The comeback record asked as a habit, and a gain given up
        "does nifty recover from a 1% fall" to "Comebacks", "how often does nifty give up a 1% gain intraday" to "Comebacks",
        "does banknifty usually give up a 1% gain" to "Comebacks",
        // VIX judged right or wrong against the moves
        "is vix accurate" to "VixBand", "how often is vix wrong" to "VixBand", "how often does vix get it right" to "VixBand",
        "how good is vix at predicting moves" to "VixBand", "does the market move as much as vix says" to "VixBand",
        // Where he wins, asked in his own words
        // ("Where am I making / losing money" is asked of now: the account's P&L, as before round 22 - round 22 review.)
        "where am i making money" to "Account:HOWTO+PNL", "where am i losing money" to "Account:HOWTO+PNL", "where do i lose the most" to "WhereIWin",
        "what do i make money on" to "WhereIWin", "what do i lose money on" to "WhereIWin", "which index works best for me" to "WhereIWin",
        "is selling working for me" to "WhereIWin", "how do i do on banknifty" to "WhereIWin",
        // What has to happen for his position, with the index named first
        "what does nifty need to do for my put" to "Account:NEED", "where does nifty need to be for my put" to "Account:NEED",
        "when does my put start making money" to "Account:NEED", "at what nifty level am i in profit" to "Account:NEED",
        "how much does nifty have to move for me to break even" to "Account:NEED",
    )

    /** Follow-ups (the question before, what is said now, what it is read as, and where that goes). */
    private val ROUND22_FOLLOW = listOf(
        Triple("what needs to happen for my put to work", "what about my call?", "what needs to happen for my call to work") to "Account:NEED",
        Triple("what needs to happen for my put to work", "and the call?", "what needs to happen for my call to work") to "Account:NEED",
        Triple("where do i make my money", "what about puts?", "where do i make my money on puts") to "WhereIWin",
        Triple("where do i make my money", "same for last week", "where do i make my money last week") to "WhereIWin",
        Triple("where do i make my money this month", "and last month?", "where do i make my money last month") to "WhereIWin",
    )

    @Test fun roundTwentyTwoWordingsRouteAndNeverAct() {
        assertEquals(ROUND22.size, ROUND22.map { it.first }.distinct().size)
        val wrong = ROUND22.mapNotNull { (s, want) -> audit.feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got ${hits(s)}" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        for ((s, _) in ROUND22) neverActs(s)
        for ((t, want) in ROUND22_FOLLOW) {
            val (prev, now, read) = t
            assertEquals(read, FollowUp.resolve(prev, now), now)
            assertEquals(want, audit.feature(read), read)
            neverActs(now); neverActs(read)
        }
        // Neighbours keep their own: the word explained, VIX's level and feed, today's market, the app's how-to, his orders,
        // advice on his put, and the P&L over another span.
        assertEquals("Glossary", audit.feature("what is theta"))
        assertEquals("VixRank", audit.feature("is vix high"))
        for (s in listOf("is vix data accurate", "is the vix figure right", "will vix be right tomorrow", "does nifty recover from here", "does nifty recover today",
            "what does nifty need to do today", "at what level should i sell my put", "when should i close my put"))
            assertTrue(audit.feature(s) !in setOf("VixBand", "Comebacks", "Account:NEED", "WhereIWin", "BookDecay"), "$s: ${audit.feature(s)}")
        for (s in listOf("how do i trade banknifty", "how do i buy a put")) assertEquals("Account:HOWTO", audit.feature(s), s)
        assertEquals("Account:ORDERS", audit.feature("is my order working"))
        assertEquals("what was my p&l last week", FollowUp.resolve("what's my p&l", "same for last week"))
        assertEquals("what was my p&l yesterday", FollowUp.resolve("where do i make my money", "and yesterday?"))
        // An order or a command is never carried, whichever kind is named now; both kinds named before: nothing to swap.
        for ((prev, now) in listOf("buy nifty 24000 put" to "what about calls?", "close my put" to "and the call?", "sell my calls" to "what about puts?",
            "do i make more on calls or puts" to "what about puts?", "how is nifty" to "what about puts?")) assertEquals(null, FollowUp.resolve(prev, now), "$prev / $now")
        for (s in listOf("sell my nifty put", "book my profit")) assertTrue(Ask.parse(s).command != null || Ask.parse(s).order != null, s)
    }

    /**
     * Round 22 review: the P&L asked over today, right now or one month keeps its old route (WhereIWin is his record split,
     * never the day's P&L); a holding-less "what do I need to do for my ..." is not NEED; VIX judged without "at ..." is
     * VixBand; the wake word and "can you tell me" no longer hide WhereIWin.
     */
    private val ROUND22_REVIEW = listOf(
        "how did I do on nifty today" to "Account:PNL+POSITIONS+ORDERS+STRATEGIES", "how did i do on options today" to "Account:PNL+POSITIONS+ORDERS+STRATEGIES",
        "where did I lose money today" to "Account:PNL+HOWTO", "where am i losing money" to "Account:HOWTO+PNL",
        "what did I make money on today" to "Account:PNL", "which index did i lose money on today" to "Account:PNL",
        "how did I do on banknifty this month" to "Account:HISTORY",
        // His record, still WhereIWin
        "where do I make my money" to "WhereIWin", "am I better at calls or puts" to "WhereIWin", "which index works best for me" to "WhereIWin",
        "how do i do on banknifty" to "WhereIWin", "where do i make my money this month" to "WhereIWin", "which index do i make money on" to "WhereIWin",
        "can you tell me where i make my money" to "WhereIWin", "jarvis where do i make my money" to "WhereIWin",
        "jarvis can you tell me where i make my money" to "WhereIWin",
        // Not his holding: the old routes
        "what do I need to do for my strategy to go live" to "Account:STRATEGIES",
        "what do I need to do for my account to go live" to "Account:PNL+POSITIONS+ORDERS+STRATEGIES",
        "where do I need to be for my meeting" to "Account:HOWTO",
        // VIX judged, said short
        "how accurate is vix" to "VixBand", "how good is vix" to "VixBand", "how reliable is india vix" to "VixBand",
    )

    @Test fun roundTwentyTwoReviewKeepsTheOldRoutes() {
        assertEquals(ROUND22_REVIEW.size, ROUND22_REVIEW.map { it.first }.distinct().size)
        val wrong = ROUND22_REVIEW.mapNotNull { (s, want) -> audit.feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got ${hits(s)}" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        for ((s, _) in ROUND22_REVIEW) neverActs(s)
        // NEED still takes the index named first when a holding is named.
        for (s in listOf("what does nifty need to do for my put", "where does nifty need to be for my put", "what do i need to do for my 24500 put"))
            assertEquals("Account:NEED", audit.feature(s), s)
        for (s in listOf("what do I need to do for my strategy to go live", "what do I need to do for my account to go live", "where do I need to be for my meeting"))
            assertTrue(!NeedsTrue.asked(s), s)
        // Jarvis's own money stays out of WhereIWin.
        assertTrue(audit.feature("where do you make your money") != "WhereIWin")
    }

    /**
     * Round 23: the newest features as Boss says them - the home-screen widgets (the app's how-to), Overnight with the
     * weekend's gaps and the recognizer's "over nite", TradesADay asked plainly, WhereIWin's chance line and Hinglish,
     * VixBand's range broken and "VIX sahi hota hai kya".
     */
    private val ROUND23 = listOf(
        // The widgets: how to add one and what it shows, never the P&L, orders or positions themselves
        "what does the open widget show" to "Account:HOWTO", "what is the open widget" to "Account:HOWTO", "how to add widget" to "Account:HOWTO",
        "widget kaise lagaye" to "Account:HOWTO", "home screen widget kaise lagaun" to "Account:HOWTO", "vidget kaise lagaye" to "Account:HOWTO",
        "how do i show my p&l on the widget" to "Account:HOWTO", "widget pe p&l kaise dikhaye" to "Account:HOWTO",
        "why does the widget not show my p&l" to "Account:HOWTO", "can i see my orders on the widget" to "Account:HOWTO",
        "why is my widget blank" to "Account:HOWTO", "the open widget is empty" to "Account:HOWTO",
        // Overnight: the weekend's gaps, the day said plainly, the recognizer's slips
        "are monday gaps bigger" to "Overnight", "weekend gaps vs weekday gaps" to "Overnight",
        "how big are the gaps compared to the day's move" to "Overnight", "do gaps matter more than the day" to "Overnight",
        "over nite moves of nifty" to "Overnight", "over knight vs intraday" to "Overnight", "overnight moves of banknifty" to "Overnight",
        // TradesADay asked plainly
        "are my busy days worse" to "TradesADay", "are my quiet days better" to "TradesADay", "is it better for me to take fewer trades" to "TradesADay",
        "do fewer trades work better for me" to "TradesADay", "how much did i make when i traded less" to "TradesADay",
        "my p&l on days with few trades" to "TradesADay", "first trade of the day kaisa rehta hai mera" to "TradesADay",
        // WhereIWin: the chance line asked of, "where do I win", Hinglish
        "is the gap between my best and worst real" to "WhereIWin", "is the difference between my calls and puts just luck" to "WhereIWin",
        "is my edge on banknifty real" to "WhereIWin", "is my win rate on banknifty real" to "WhereIWin", "best aur worst ka fark luck hai kya" to "WhereIWin",
        "where do i win" to "WhereIWin", "where do i win the most" to "WhereIWin", "main kahan kamata hoon" to "WhereIWin",
        "kidhar se paisa banta hai mera" to "WhereIWin",
        // VixBand: the range broken, VIX judged in Hinglish
        "how often is the vix range broken" to "VixBand", "vix wala range kitni baar toota" to "VixBand", "vix sahi hota hai kya" to "VixBand",
    )

    @Test fun roundTwentyThreeWordingsRouteAndNeverAct() {
        assertEquals(ROUND23.size, ROUND23.map { it.first }.distinct().size)
        val wrong = ROUND23.mapNotNull { (s, want) -> audit.feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got ${hits(s)}" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        for ((s, _) in ROUND23) neverActs(s)
        // The how-to knows the widgets, and says only that when one is named.
        for (s in listOf("how do i add the widget", "what does the open widget show", "widget kaise lagaye", "how do i show my p&l on the widget"))
            assertEquals(listOf(AppAnswers.WIDGETS), AppAnswers.howto(s), s)
        assertTrue(AppAnswers.howto("where is the kill switch").none { it == AppAnswers.WIDGETS })
        // Neighbours keep their own: the P&L, the history, Headroom, today's gap, the market's gap, a definition, VIX's level.
        for ((s, want) in listOf("my p&l" to "Account:PNL", "how much did i make today" to "Account:PNL", "what's my p&l this week" to "Account:HISTORY",
            "my best day this month" to "Account:HISTORY", "how much did i make this week" to "Account:HISTORY",
            "where am i making money" to "Account:HOWTO+PNL", "where did i lose money today" to "Account:PNL+HOWTO",
            "how many trades did i take yesterday" to "Account:HISTORY+ORDERS",
            "how much headroom do i have left" to "Headroom", "how many more trades can i take today" to "Headroom", "how much more can i lose today" to "Headroom",
            "is the gap bigger today" to "Gap", "where is the kill switch" to "Account:RISK+HOWTO"))
            assertEquals(want, audit.feature(s), s)
        for (s in listOf("is the gap between nifty and banknifty real", "what's the difference between calls and puts", "how big was the monday gap",
            "vix aaj sahi hai kya", "how is vix", "did i trade too much today", "should i trade less"))
            assertTrue(audit.feature(s) !in setOf("WhereIWin", "Overnight", "VixBand", "TradesADay", "Account:HOWTO"), "$s: ${audit.feature(s)}")
        // "Where did I win today" is tied to today: never his record (the account's route, as before).
        assertTrue(audit.feature("where did i win today") != "WhereIWin")
        // An order or a command with a widget named reads as it did: never the how-to.
        for (s in listOf("buy 1 lot nifty widget", "cancel all orders", "square off everything", "sell my nifty put"))
            assertTrue(Ask.parse(s).command != null || Ask.parse(s).order != null || Bundle.acts(s), s)
        assertTrue(!AppAnswers.about(" buy nifty widget "), "a trading word keeps the widget out of the app's answers")
    }

    /** Round 23's review: a span inside TradesADay's ask, lots kept out, "purchase" with a widget, one weekend gap. */
    @Test fun roundTwentyThreeReviewWordings() {
        assertEquals("TradesADay", audit.feature("how much did i make last week when i traded less"))
        assertEquals(MyNumbers.Span.LAST_WEEK, TradesADay.span("how much did i make last week when i traded less"))
        neverActs("how much did i make last week when i traded less")
        assertTrue(audit.feature("how much did i make when i took more lots") != "TradesADay", audit.feature("how much did i make when i took more lots"))
        // An order with a widget named is still an order, never the widgets' how-to.
        for (s in listOf("purchase nifty 24500 ce widget", "nifty 24500 ce khareedo widget", "nifty 24500 ce le lo widget", "nifty 24500 ce lelo widget"))
            assertTrue(!AppAnswers.about(" " + s + " "), s)
        val p = Ask.parse("purchase nifty 24500 ce widget")
        assertTrue(p.order != null || p.command != null || Bundle.acts("purchase nifty 24500 ce widget"), "purchase nifty 24500 ce widget")
        // One gap named is today's gap, Gap's - never Overnight's record.
        assertEquals("Gap", audit.feature("is the weekend gap bigger than usual"))
        assertTrue(Overnight.asked("is the weekend gap bigger than usual") == null)
        assertEquals("Overnight", audit.feature("are weekend gaps bigger than usual"))
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
