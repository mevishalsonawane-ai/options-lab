package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Boss's first question of the day paid for every reader's patterns (2026-10-05): about 0.4 s on a desktop before a
 * word was understood, more on a phone. [up] reads a few made-up questions through the same readers once, off the main
 * thread, when Jarvis starts. Nothing is answered, said, kept or done: only the readers are made ready. Pure.
 */
object Warm {
    /** Made-up words of each kind (a question, a follow-up, a filler, Hinglish, the account, a command as words only). */
    private val SAID = listOf("how is nifty doing today", "umm and bank nifty?", "what are my positions", "nifty ka kya haal hai",
        "should I trade now?", "what is a hammer", "stop strategy 1")

    @Volatile private var done = false

    /**
     * The hub's question detectors, each asked of the made-up words once (speed round 3, 2026-10-05): the readers above left
     * them cold, and the first real question still paid about half a second on a desktop compiling their patterns and
     * filling their [Kept] readings. Words-only readers: their answers are dropped (a kept reading is what reading again gives).
     */
    private val DETECTORS: List<(String) -> Any?> = listOf<(String) -> Any?>(
        AboutBoss::forgetAsked, AboutBoss::knowAsked, Airtime::asked, AlertSense::asked, AtmBuy::asked, AppAnswers::sections,
        ArmChange::asked, ArmDay::asked, ArmFit::asked, ArmHabits::asked, AskedAgain::asked, AutoStop::read,
        BatteryUse::asked, BeforeTomorrow::asked, BigCandles::asked, BookDecay::asked, WhereIWin::asked, TradesADay::asked, AfterLoss::asked, StopNoise::asked, DayScore::asked, RequestBook::asked, BigPicture::asked, BotHealth::asked, BotTrades::asked, Breadth::asked,
        Briefing::asked, Bundle::acts, Causes::asked, ChainDrift::asked, ChainIntel::asked, Charges::asked, Clarity::asked, CoPilot::asked, Comebacks::asked,
        Compare::asked, Compare::markets, Conditional::asked, Consistency::asked, Corrections::forgetWordAsked, Corrections::wordsAsked,
        DataAge::asked, MarketRecord::asked, MorningCues::asked, BigMoveRisk::asked, LiquidityMap::asked, DayAfter::asked, DayClock::asked, DayCompare::asked, DayJournal::asked, DayStory::asked, DaySummary::asked,
        Distance::asked, ExpectedRange::asked, ExpiryDay::asked, ExpiryEve::asked, ExpiryPin::asked, ExpiryHour::asked, ExtremeCloses::asked,
        FigureFirst::asked, FirstMove::asked, Gap::asked, GapRecord::asked, Goals::asked, Goals::clearAsked, Goals::read,
        Habits::asked, Headroom::asked, Hearing::asked, Honest::asked, HonestStars::asked, Improve::asked, InsideDays::asked,
        Intents::mayMean, Intents::prompt, LastHour::asked, Latency::asked, LeadIndex::asked, LeadPart::asked, Learnings::asked, Learnings::undoAsked,
        Lessons::asked, LevelInfo::asked, LikeToday::asked, Lookback::prevAsked, Lookback::time, LunchRange::asked,
        MarketMemory::asked, MarketStory::asked, Memory::forgetAsked, Memory::recallAsked, Memory::toKeep, MindChange::asked,
        Momentum::asked, MonthReview::asked, MonthTurns::asked, MorningAsks::asked, MorningSense::asked, MoreAfter::asked, SmallTrades::asked, DayIndex::asked, CheckTimes::asked, CondNeeds::asked, { Moves.asked(it) }, MultiDay::asked, MoveTime::asked, GiveBack::asked, MyNumbers::asked, NextAsk::asked, MyStreaks::asked, NeedsTrue::asked,
        NetLean::asked, NetLean::market, NewsDesk::asked, NewsMoves::asked, Nicknames::asked, NoListen::asked, Odds::asked,
        OpenHighLow::asked, OpenReach::asked, OpeningRange::asked, OptionFacts::asked, OptionQuote::asked, OtmReach::asked, OrderWhy::asked, Outlook::asked, OutlookCheck::asked, Overnight::asked,
        OutsideApp::asked, OutsideApp::say, PatternCalls::asked, Payoff::asked, PeriodMove::asked, Pivots::asked,
        Plan::pronounAfter, Plan::pronounUnclear, PnlGap::asked, PositionHealth::asked, Practice::asked, PreMarket::asked, PriorDay::asked, RangeBreaks::asked,
        Realised::asked, RelativeMove::asked, RelayHealth::asked, Reminder::daily, Reminder::heardAsked, Reminder::missedAsked,
        Reminder::modelAsked, Reminder::tomorrow, Reminder::usageAsked, ReminderBook::cancelOne, ReminderBook::listAsked,
        RoundCloses::asked, Routine::asked, Routine::forgetAsked, SaidAbout::asked, Scenarios::asked, SelfCalibration::asked,
        SelfCheck::asked, SelfWhy::asked, SharpMove::asked, SinceLast::asked, SinceMorning::asked, Sizing::asked,
        SplitDays::asked, StraddleDecay::asked, Streak::asked, StreamHealth::asked, Structure::asked, SwitchOff::asked, TalkHours::asked,
        TaxRecords::asked, TaxRecords::exportAsked, Thinking::asked, Together::asked, TopicLength::asked, Tour::asked, TradeCase::asked, TradeReplay::asked, TradeSearch::asked,
        TrendReads::asked, TrendReads::span, TurnDowns::asked, UsualIndex::asked, Vetting::asked, VixNext::asked,
        VixBand::asked, VixRank::asked, WatchAsk::asked, WeakLink::asked, WeekAhead::asked, WeeklyReview::asked, WeekRange::asked, Weekdays::asked, WhatIf::asked,
        WordFit::asked, WrongThing::asked, WrongThing::objected, ZerodhaSession::asked
    )

    /** Every reader made ready once; later calls return at once. Safe on any thread. */
    fun up() {
        if (done) return
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        var prev: String? = null
        for (q in SAID) {
            runCatching { Secrets.redact(q) }
            runCatching { Corrections.apply(q, emptyList()) }
            runCatching { Sources.asked(q) }
            runCatching { Understand.questions(prev, q) }
            runCatching { Commands.parse(q) }
            runCatching { Agenda.asked(q) }
            runCatching { Plan.steps(q) { false } }
            runCatching { Later.mentionsTime(q); Later.split(q, now) }
            runCatching { Reminder.asked(q); Reminder.cancelAsked(q) }
            runCatching { AboutBoss.fact(q) }
            runCatching { Intents.quick(q) }
            runCatching { Chat.personal(q) }
            runCatching { Glossary.explain(q) }
            runCatching { MarketDays.expiryAsked(q) }
            runCatching { Suggest.line(q) }
            runCatching { Ira().answer(q, emptyMap(), emptyList()) }
            for (d in DETECTORS) runCatching { d(q) }
            prev = q
        }
        done = true
    }
}
