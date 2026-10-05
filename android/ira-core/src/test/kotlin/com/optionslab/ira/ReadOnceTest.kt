package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Patterns compiled once ([Rx]) and the readers warmed at start ([Warm]): every reading exactly as before. */
class ReadOnceTest {
    companion object {
        /** Questions, follow-ups, fillers, Hinglish, the account, orders and commands of every kind the readers know. */
        val SAID = listOf(
            "how is nifty doing today", "what are the levels on banknifty", "why did the market fall", "any news on sensex",
            "is nifty bullish", "should I trade now?", "what should I buy", "what is a hammer", "how are my strategies doing",
            "what's going on", "kya chal raha hai", "and banknifty?", "yesterday's high on nifty", "what's moving", "describe the nifty chart",
            "how much did I make today", "umm so what is the uh vix doing", "nifty ka kya haal hai", "where is banknifty trading",
            "buy 2 lots nifty 24500 ce", "sell one lot banknifty atm pe", "buy nifty", "Did I buy 2 lots of Nifty?", "stop strategy 1",
            "what can you do", "hello jarvis", "senseks today", "levels on all indices", "how volatile is finnifty",
            "backtest the hammer on nifty", "gold price", "how is the market and what about my orders", "tell me a joke",
            "how is Nifty, I mean BankNifty", "how how is nifty", "aur sensex?", "uske baad?", "what about yesterday?",
            "remove the nifty alarm", "clear all banknifty alerts", "alert me when nifty goes above 25000", "nifty 24500 pe alert lagao",
            "tell me if banknifty falls 1% from here", "close the nifty position", "sell my 1 lot of nifty 24500 ce", "exit everything",
            "cancel all orders", "turn the kill switch on", "switch to live", "don't switch to live", "start all statergies",
            "be quiet for 30 minutes", "mute", "speak slower", "my target today is 3000", "set max lots to 3", "stop trading",
            "jarvis, note: I bought because of the hammer at support", "that was wrong", "undo", "what's the trend, I mean the levels on BankNifty",
            "how is nifty and what's my p&l", "nifty kaisa hai aur mera p&l kitna hai", "15 minute trend on nifty", "1 hour chart of sensex",
        )

        /** Everything the readers make of [q] after "how is nifty", in one line. */
        fun reading(q: String): String = listOf(
            Ask.parse(q).toString(), Commands.parse(q).toString(), Market.mentioned(q).toString(),
            FollowUp.resolve("how is nifty", q).toString(), Understand.questions("how is nifty", q).toString(),
            Understand.questions(null, q).toString(), Filler.clean(q), Compound.split(q).toString(), FollowUp.onlyMarkets(q).toString(),
        ).joinToString(" | ")
    }

    @Test fun aPatternIsCompiledOnceAndMatchesAsWritten() {
        val p = " (stop|halt) (all|every) "
        assertSame(rx(p), rx(p))
        assertNotSame(rx(p), rx(" (start) "))
        for (s in listOf(" stop all ", " halt every bot ", " stop nothing ", "")) assertEquals(Regex(p).containsMatchIn(s), rx(p).containsMatchIn(s))
        assertEquals(Regex("\\s+").replace("a  b \t c", " "), rx("\\s+").replace("a  b \t c", " "))
    }

    @Test fun warmingChangesNoReading() {
        val before = SAID.map { reading(it) }
        Warm.up(); Warm.up()
        assertEquals(before, SAID.map { reading(it) })
    }

    @Test fun everyReadingAsBeforeTheChange() {
        assertEquals(SAID.size, BEFORE.size)
        SAID.zip(BEFORE).forEach { (q, want) -> assertEquals(want, reading(q), q) }
        assertTrue(BEFORE.any { "STOP_ONE" in it } && BEFORE.any { "ALARM_REMOVE" in it } && BEFORE.any { "ORDER" in it })
    }

    /** [reading] of each of [SAID] by the code before patterns were kept (worked out on that code, 2026-10-05). */
    private val BEFORE = listOf<String>(
        "Question(text=how is nifty doing today, markets=[NIFTY], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [NIFTY] | null | null | null | how is nifty doing today | null | false",
        "Question(text=what are the levels on banknifty, markets=[BANKNIFTY], topics=[LEVELS], order=null, command=null, pattern=null, minutes=null) | null | [BANKNIFTY] | null | null | null | what are the levels on banknifty | null | false",
        "Question(text=why did the market fall, markets=[], topics=[WHY, OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | why did the market fall | null | false",
        "Question(text=any news on sensex, markets=[SENSEX], topics=[NEWS], order=null, command=null, pattern=null, minutes=null) | null | [SENSEX] | null | null | null | any news on sensex | null | false",
        "Question(text=is nifty bullish, markets=[NIFTY], topics=[TREND], order=null, command=null, pattern=null, minutes=null) | null | [NIFTY] | null | null | null | is nifty bullish | null | false",
        "Question(text=should I trade now?, markets=[], topics=[TRADE_CHECK], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | should I trade now? | null | false",
        "Question(text=what should I buy, markets=[], topics=[SUGGEST], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | what should I buy | null | false",
        "Question(text=what is a hammer, markets=[], topics=[EXPLAIN], order=null, command=null, pattern=HAMMER, minutes=null) | null | [] | null | null | null | what is a hammer | null | false",
        "Question(text=how are my strategies doing, markets=[], topics=[ACCOUNT], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | how are my strategies doing | null | false",
        "Question(text=what's going on, markets=[NIFTY], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [] | what's going on on Nifty | [what's going on on Nifty] | null | what's going on | null | false",
        "Question(text=kya chal raha hai, markets=[NIFTY], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [] | kya chal raha hai on Nifty | [kya chal raha hai on Nifty] | null | kya chal raha hai | null | false",
        "Question(text=and banknifty?, markets=[BANKNIFTY], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [BANKNIFTY] | how is BankNifty | [how is BankNifty] | null | and banknifty? | null | true",
        "Question(text=yesterday's high on nifty, markets=[NIFTY], topics=[LEVELS], order=null, command=null, pattern=null, minutes=null) | null | [NIFTY] | null | null | null | yesterday's high on nifty | null | false",
        "Question(text=what's moving, markets=[NIFTY, BANKNIFTY, FINNIFTY, SENSEX], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [] | what's moving on Nifty | [what's moving on Nifty] | null | what's moving | null | false",
        "Question(text=describe the nifty chart, markets=[NIFTY], topics=[OVERVIEW, TREND, LEVELS, PATTERNS], order=null, command=null, pattern=null, minutes=null) | null | [NIFTY] | null | null | null | describe the nifty chart | null | false",
        "Question(text=how much did I make today, markets=[], topics=[ACCOUNT], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | how much did I make today | null | false",
        "Question(text=umm so what is the uh vix doing, markets=[VIX], topics=[VOLATILITY, OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [VIX] | null | [what is the vix doing] | [what is the vix doing] | what is the vix doing | null | false",
        "Question(text=how is nifty, markets=[NIFTY], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [NIFTY] | how is Nifty | [how is Nifty] | null | nifty ka kya haal hai | null | false",
        "Question(text=where is banknifty trading, markets=[BANKNIFTY], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [BANKNIFTY] | null | null | null | where is banknifty trading | null | false",
        "Question(text=buy 2 lots nifty 24500 ce, markets=[NIFTY], topics=[ORDER], order=OrderRequest(market=NIFTY, buy=true, lots=2, strike=24500, right=CE, missing=[], atm=false), command=null, pattern=null, minutes=null) | null | [NIFTY] | null | null | null | buy 2 lots nifty 24500 ce | null | false",
        "Question(text=sell one lot banknifty atm pe, markets=[BANKNIFTY], topics=[ORDER], order=OrderRequest(market=BANKNIFTY, buy=false, lots=1, strike=null, right=PE, missing=[], atm=true), command=null, pattern=null, minutes=null) | null | [BANKNIFTY] | null | null | null | sell one lot banknifty atm pe | null | false",
        "Question(text=buy nifty, markets=[NIFTY], topics=[ORDER], order=OrderRequest(market=NIFTY, buy=true, lots=null, strike=null, right=null, missing=[how many lots, call or put, which strike], atm=false), command=null, pattern=null, minutes=null) | null | [NIFTY] | null | null | null | buy nifty | null | false",
        "Question(text=Did I buy 2 lots of Nifty?, markets=[NIFTY], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [NIFTY] | null | null | null | Did I buy 2 lots of Nifty? | null | false",
        "Question(text=stop strategy 1, markets=[], topics=[COMMAND], order=null, command=Command(kind=STOP_ONE, target=null, number=1, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=STOP_ONE, target=null, number=1, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | stop strategy 1 | null | false",
        "Question(text=what can you do, markets=[], topics=[HELP], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | what can you do | null | false",
        "Question(text=hello jarvis, markets=[], topics=[GREETING], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | hello jarvis | null | false",
        "Question(text=senseks today, markets=[SENSEX], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [SENSEX] | null | null | null | senseks today | null | false",
        "Question(text=levels on all indices, markets=[NIFTY, BANKNIFTY, FINNIFTY, SENSEX], topics=[LEVELS], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | levels on all indices | null | false",
        "Question(text=how volatile is finnifty, markets=[FINNIFTY], topics=[VOLATILITY], order=null, command=null, pattern=null, minutes=null) | null | [FINNIFTY] | null | null | null | how volatile is finnifty | null | false",
        "Question(text=backtest the hammer on nifty, markets=[NIFTY], topics=[BACKTEST], order=null, command=null, pattern=HAMMER, minutes=null) | null | [NIFTY] | null | null | null | backtest the hammer on nifty | null | false",
        "Question(text=gold price, markets=[GOLD], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [GOLD] | null | null | null | gold price | null | false",
        "Question(text=how is the market and what about my orders, markets=[], topics=[TRADE_CHECK], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | how is the market and what about my orders | null | false",
        "Question(text=tell me a joke, markets=[], topics=[OFF_TOPIC], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | tell me a joke | null | false",
        "Question(text=how is Nifty, I mean BankNifty, markets=[BANKNIFTY, NIFTY], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [BANKNIFTY, NIFTY] | null | [how is BankNifty] | [how is BankNifty] | how is BankNifty | null | false",
        "Question(text=how how is nifty, markets=[NIFTY], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [NIFTY] | null | [how is nifty] | [how is nifty] | how is nifty | null | false",
        "Question(text=aur sensex?, markets=[SENSEX], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=null) | null | [SENSEX] | how is Sensex | [how is Sensex] | null | aur sensex? | null | true",
        "Question(text=uske baad?, markets=[], topics=[OFF_TOPIC], order=null, command=null, pattern=null, minutes=null) | null | [] | where is Nifty heading next | [where is Nifty heading next] | null | uske baad? | null | false",
        "Question(text=what about yesterday?, markets=[], topics=[ACCOUNT], order=null, command=null, pattern=null, minutes=null) | null | [] | what was yesterday's high, low and close on Nifty | [what was yesterday's high, low and close on Nifty] | null | what about yesterday? | null | false",
        "Question(text=remove the nifty alarm, markets=[NIFTY], topics=[COMMAND], order=null, command=Command(kind=ALARM_REMOVE, target=null, number=null, market=NIFTY, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=ALARM_REMOVE, target=null, number=null, market=NIFTY, above=null, level=null, day=null, pct=null, lots=null) | [NIFTY] | null | null | null | remove the nifty alarm | null | false",
        "Question(text=clear all banknifty alerts, markets=[BANKNIFTY], topics=[COMMAND], order=null, command=Command(kind=ALARM_REMOVE, target=all, number=null, market=BANKNIFTY, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=ALARM_REMOVE, target=all, number=null, market=BANKNIFTY, above=null, level=null, day=null, pct=null, lots=null) | [BANKNIFTY] | null | null | null | clear all banknifty alerts | null | false",
        "Question(text=alert me when nifty goes above 25000, markets=[NIFTY], topics=[COMMAND], order=null, command=Command(kind=ALARM_ADD, target=null, number=null, market=NIFTY, above=true, level=25000.0, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=ALARM_ADD, target=null, number=null, market=NIFTY, above=true, level=25000.0, day=null, pct=null, lots=null) | [NIFTY] | null | null | null | alert me when nifty goes above 25000 | null | false",
        "Question(text=nifty 24500 pe alert lagao, markets=[NIFTY], topics=[COMMAND], order=null, command=Command(kind=ALARM_ADD, target=null, number=null, market=NIFTY, above=null, level=24500.0, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=ALARM_ADD, target=null, number=null, market=NIFTY, above=null, level=24500.0, day=null, pct=null, lots=null) | [NIFTY] | null | null | null | nifty 24500 pe alert lagao | null | false",
        "Question(text=tell me if banknifty falls 1% from here, markets=[BANKNIFTY], topics=[COMMAND], order=null, command=Command(kind=ALARM_ADD, target=null, number=null, market=BANKNIFTY, above=false, level=null, day=null, pct=1.0, lots=null), pattern=null, minutes=null) | Command(kind=ALARM_ADD, target=null, number=null, market=BANKNIFTY, above=false, level=null, day=null, pct=1.0, lots=null) | [BANKNIFTY] | null | null | null | tell me if banknifty falls 1% from here | null | false",
        "Question(text=close the nifty position, markets=[NIFTY], topics=[COMMAND], order=null, command=Command(kind=CLOSE_ONE, target=nifty, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=CLOSE_ONE, target=nifty, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [NIFTY] | null | null | null | close the nifty position | null | false",
        "Question(text=sell my 1 lot of nifty 24500 ce, markets=[NIFTY], topics=[COMMAND], order=null, command=Command(kind=CLOSE_ONE, target=nifty 24500 ce, number=null, market=null, above=null, level=null, day=null, pct=null, lots=1), pattern=null, minutes=null) | Command(kind=CLOSE_ONE, target=nifty 24500 ce, number=null, market=null, above=null, level=null, day=null, pct=null, lots=1) | [NIFTY] | null | null | null | sell my 1 lot of nifty 24500 ce | null | false",
        "Question(text=exit everything, markets=[], topics=[COMMAND], order=null, command=Command(kind=EXIT_ALL, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=EXIT_ALL, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | exit everything | null | false",
        "Question(text=cancel all orders, markets=[], topics=[COMMAND], order=null, command=Command(kind=CANCEL_ALL, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=CANCEL_ALL, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | cancel all orders | null | false",
        "Question(text=turn the kill switch on, markets=[], topics=[COMMAND], order=null, command=Command(kind=KILL_ON, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=KILL_ON, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | turn the kill switch on | null | false",
        "Question(text=switch to live, markets=[], topics=[COMMAND], order=null, command=Command(kind=MODE_LIVE, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=MODE_LIVE, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | switch to live | null | false",
        "Question(text=don't switch to live, markets=[], topics=[OFF_TOPIC], order=null, command=null, pattern=null, minutes=null) | null | [] | null | null | null | don't switch to live | null | false",
        "Question(text=start all statergies, markets=[], topics=[COMMAND], order=null, command=Command(kind=START_ALL, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=START_ALL, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | start all statergies | null | false",
        "Question(text=be quiet for 30 minutes, markets=[], topics=[COMMAND], order=null, command=Command(kind=MUTE_FOR, target=null, number=30, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=MUTE_FOR, target=null, number=30, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | be quiet for 30 minutes | null | false",
        "Question(text=mute, markets=[], topics=[COMMAND], order=null, command=Command(kind=MUTE, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=MUTE, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | mute | null | false",
        "Question(text=speak slower, markets=[], topics=[COMMAND], order=null, command=Command(kind=PACE_SLOWER, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=PACE_SLOWER, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | speak slower | null | false",
        "Question(text=my target today is 3000, markets=[], topics=[COMMAND], order=null, command=Command(kind=TARGET_SET, target=null, number=null, market=null, above=null, level=3000.0, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=TARGET_SET, target=null, number=null, market=null, above=null, level=3000.0, day=null, pct=null, lots=null) | [] | null | null | null | my target today is 3000 | null | false",
        "Question(text=set max lots to 3, markets=[], topics=[COMMAND], order=null, command=Command(kind=SET_LIMIT, target=MAX_LOTS, number=null, market=null, above=null, level=3.0, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=SET_LIMIT, target=MAX_LOTS, number=null, market=null, above=null, level=3.0, day=null, pct=null, lots=null) | [] | null | null | null | set max lots to 3 | null | false",
        "Question(text=stop trading, markets=[], topics=[COMMAND], order=null, command=Command(kind=STOP_ALL, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=STOP_ALL, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | stop trading | null | false",
        "Question(text=jarvis, note: I bought because of the hammer at support, markets=[], topics=[COMMAND], order=null, command=Command(kind=NOTE, target=I bought because of the hammer at support, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=NOTE, target=I bought because of the hammer at support, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | jarvis, note: I bought because of the hammer at support | null | false",
        "Question(text=that was wrong, markets=[], topics=[COMMAND], order=null, command=Command(kind=MISTAKE, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=MISTAKE, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | that was wrong | null | false",
        "Question(text=undo, markets=[], topics=[COMMAND], order=null, command=Command(kind=UNDO, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null), pattern=null, minutes=null) | Command(kind=UNDO, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null) | [] | null | null | null | undo | null | false",
        "Question(text=what's the trend, I mean the levels on BankNifty, markets=[BANKNIFTY], topics=[TREND, LEVELS], order=null, command=null, pattern=null, minutes=null) | null | [BANKNIFTY] | null | [the levels on BankNifty] | [the levels on BankNifty] | the levels on BankNifty | null | false",
        "Question(text=how is nifty and what's my p&l, markets=[NIFTY], topics=[ACCOUNT], order=null, command=null, pattern=null, minutes=null) | null | [NIFTY] | null | [how is nifty, what's my p&l] | [how is nifty, what's my p&l] | how is nifty and what's my p&l | [how is nifty, what's my p&l] | false",
        "Question(text=nifty kaisa hai aur my p&l how much hai, markets=[NIFTY], topics=[ACCOUNT], order=null, command=null, pattern=null, minutes=null) | null | [NIFTY] | null | [nifty kaisa hai, mera p&l kitna hai] | [nifty kaisa hai, mera p&l kitna hai] | nifty kaisa hai aur mera p&l kitna hai | [nifty kaisa hai, mera p&l kitna hai] | false",
        "Question(text=15 minute trend on nifty, markets=[NIFTY], topics=[TREND], order=null, command=null, pattern=null, minutes=15) | null | [NIFTY] | null | null | null | 15 minute trend on nifty | null | false",
        "Question(text=1 hour chart of sensex, markets=[SENSEX], topics=[OVERVIEW], order=null, command=null, pattern=null, minutes=60) | null | [SENSEX] | null | null | null | 1 hour chart of sensex | null | false",
    )
}
