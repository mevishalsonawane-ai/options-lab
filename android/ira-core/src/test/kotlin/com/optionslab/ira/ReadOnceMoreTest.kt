package com.optionslab.ira

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The rest of the question readers (Reason, the account, Hinglish, the spoken numbers, the wake word, reminders, the
 * day readers and the smaller ones) with their patterns compiled once ([Rx]): every reading exactly as before.
 */
class ReadOnceMoreTest {
    companion object {
        private val NOW = LocalDateTime.of(2026, 1, 5, 10, 0)
        private val TODAY = NOW.toLocalDate()

        /** Said to Jarvis, of every kind these readers know, English and Hinglish, spoken numbers and clock times. */
        val SAID = ReadOnceTest.SAID + listOf(
            "how much did nifty move in the last 15 minutes", "how did banknifty do in the past 2 hours", "since the open how is sensex",
            "nifty since 10:30 am", "what was nifty at 11 am", "where was banknifty at 2:15 pm", "is nifty stronger than banknifty",
            "which index is the weakest", "what's the expected range for nifty", "give me the day story", "how do you know that",
            "jarvis where did you get that from", "pivots for tomorrow on nifty", "yesterday's close on banknifty", "is nifty overbought",
            "what are the chances nifty closes above 25000", "will banknifty touch 52000 by friday", "will nifty hit 26000",
            "did the opening range break on nifty", "how did nifty do this week", "how did sensex do last week", "how was banknifty this month",
            "brief me", "catch me up", "is the vix high", "what will my 24500 ce be worth if nifty expires at 24700 bought at 120",
            "what's the premium of nifty 24500 ce", "what is the price of 52000 pe", "what's at 24500 important", "levels near 25000",
            "what's the bigger picture on nifty", "what's changed since last time", "is banknifty moving with nifty",
            "are options expensive right now", "what's your outlook for nifty this week", "prediction for tomorrow on nifty",
            "did nifty gap up today", "nifty up 3 days in a row?", "is the kill switch on", "what is my daily loss limit",
            "how much margin do I have", "my p&l yesterday", "what did I change in my settings", "am I ready to go live",
            "why did that trade lose", "where do I find the option chain", "how do I set an alarm", "can you hear me",
            "any fed events this week", "how are my positions", "explain my position", "what if I had taken that trade",
            "bazaar kaisa hai", "aaj kitna kamaya", "mera p&l kitna hai", "kya main profit mein hoon", "meri positions dikhao",
            "nifty kitne pe hai", "banknifty ka bhav kya hai", "koi news hai kya", "pichle ghante nifty kitna gira",
            "kya nifty 25000 ke upar band hoga", "haan kar do", "nahi rehne do", "theek hai", "strategy 1 band kar do",
            "twenty four thousand five hundred", "nifty at twenty five thousand", "remind me at half past two", "remind me at 3 pm daily",
            "remind me tomorrow at nine to check nifty", "in 10 minutes remind me to exit", "cancel my reminders", "what did you miss",
            "at 2:30 tell me nifty", "is the market open on friday", "next expiry", "is tomorrow a holiday", "is the market open next monday",
            "nifty 24500 call delta", "what's the open interest at 24500", "time left to expiry", "jarvis", "hey jarvis how is nifty",
            "jarvis stop", "ok jarvis be quiet", "yes go ahead", "no don't", "Rs 1,200 profit. Nifty is up 0.5%. Good, Boss.",
            "what was the sharp move on nifty", "why did nifty suddenly fall", "how fast are you", "remember that I hate overnight trades",
            "what did I tell you", "forget what I told you", "my max loss is 5000 a day", "I don't trade on expiry day",
            "set max lots to 3", "turn off the loss limit", "max lots 5", "daily loss limit off", "no new entries after 2:30 pm",
            "what if nifty falls 2%", "which of my positions is riskiest", "how many lots can I buy with 20000", "how far is nifty from 25000",
            "replay my last trade", "the usual", "what is theta", "show my trades on monday", "my best time of day",
            "what's on the agenda today", "how are you improving", "where are you weak", "practice monday's session",
            "first buy nifty then set an alert", "how is the market? story so far", "o'clock five", "it's 3-30 pm",
            "nifty five o'clock", "1 pts", "2 pts down", "Boss, yes. It is up, Boss.", "https://example.com is it good",
        )

        private val OBJECT_ID = Regex("@[0-9a-f]+\\b")

        /** What [f] gives, an object without a reading of its own named by its class only. */
        private fun r(f: () -> Any?): String = runCatching { OBJECT_ID.replace(f().toString(), "") }.getOrElse { "!" + it.javaClass.simpleName }

        /** Everything these readers make of [q], in one line. */
        fun reading(q: String): String = listOf(
            r { AppAnswers.sections(q) }, r { AppAnswers.howto(q) }, r { AppAnswers.about(" " + q.lowercase() + " ") },
r { Compare.asked(q) }, r { Compare.markets(q) }, r { ExpectedRange.asked(q) }, r { DayStory.asked(q) },
            r { Sources.asked(q) }, r { Pivots.asked(q) }, r { Lookback.time(q) }, r { Lookback.prevAsked(q) }, r { Momentum.asked(q) },
            r { Odds.asked(q) }, r { OpeningRange.asked(q) }, r { PeriodMove.asked(q) }, r { Briefing.asked(q) }, r { VixRank.asked(q) },
            r { Payoff.asked(q) }, r { OptionQuote.asked(q) }, r { LevelInfo.asked(q) }, r { BigPicture.asked(q) }, r { SinceLast.asked(q) },
            r { Together.asked(q) }, r { Realised.asked(q) }, r { Outlook.asked(q) }, r { Outlook.span(q) }, r { Outlook.session(q) },
            r { Gap.asked(q) }, r { Streak.asked(q) },
            r { Hinglish.hasHindi(q) }, r { Hinglish.normalize(q) }, r { Hinglish.question(q) }, r { Hinglish.yesNo(q) },
            r { Spoken.digits(q) }, r { Spoken.question(q) }, r { Wake.named(q) }, r { Wake.hush(q) }, r { Wake.heard(q, false) },
            r { Wake.heard(q, true) }, r { Wake.pieces(q) }, r { Wake.yesNo(q) }, r { Aloud.onceBoss(q) }, r { Aloud.numbers(q) },
            r { Aloud.say(q) }, r { Address.boss(q) }, r { AboutBoss.fact(q) }, r { AboutBoss.kind(q) }, r { AboutBoss.amount(q) },
            r { AboutBoss.forgetAsked(q) }, r { AboutBoss.noted(q) }, r { Reminder.parse(q, NOW) }, r { Reminder.asked(q) },
            r { Reminder.cancelAsked(q) }, r { Reminder.clock(q, NOW) }, r { Reminder.missedAsked(q) }, r { Reminder.heardAsked(q) },
            r { Reminder.usageAsked(q) }, r { Reminder.modelAsked(q) }, r { Later.mentionsTime(q) }, r { Later.split(q, NOW) },
            r { MarketDays.namesAnotherDay(q) }, r { MarketDays.asked(q, TODAY) }, r { MarketDays.expiryAsked(q) }, r { OptionFacts.asked(q) },
            r { Heard.fix(q) }, r { SharpMove.asked(q) }, r { MarketStory.asked(q) }, r { Latency.asked(q) }, r { Suggest.closest(q) },
            r { Chat.personal(q) }, r { Chat.smallTalk(q, 1) }, r { Chat.accept(q) }, r { Goals.read(q) }, r { Goals.asked(q) },
            r { Goals.clearAsked(q) }, r { WhatIf.minute(q) }, r { WhatIf.asked(q) }, r { MoveAlarm.read(q) }, r { TradeSearch.parse(q, TODAY) },
            r { Memory.toKeep(q) }, r { Memory.recallAsked(q) }, r { Memory.forgetAsked(q) }, r { Exposure.moveAsked(q) },
            r { Exposure.rankAsked(q) }, r { Events.date(q, TODAY) }, r { Glossary.explain(q) }, r { Sizing.asked(q) }, r { Intents.quick(q) },
            r { Intents.pick(q) }, r { SettingsTalk.parse(" " + q.lowercase() + " ") }, r { SettingsTalk.forbidden(" " + q.lowercase() + " ") },
            r { Plan.steps(q) { true } }, r { AutoStop.read(q) }, r { Practice.day(q, TODAY) }, r { Practice.asked(q) }, r { Habits.key(q) },
            r { Habits.asked(" " + q.lowercase() + " ") }, r { SelfCheck.asked(q) }, r { Corrections.normalize(q) },
            r { Corrections.understood(q) }, r { Corrections.missed(q) }, r { Distance.asked(q) }, r { TradeReplay.asked(q) },
            r { MonthReview.asked(q) }, r { MonthReview.month(q, TODAY) }, r { Improve.asked(q) }, r { SelfWhy.asked(q) },
            r { TradeCase.asked(q) }, r { BossRules.of(q) }, r { ExpiryDay.asked(q) }, r { Airtime.asked(q) }, r { AlertSense.asked(q) },
            r { Agenda.asked(q) }, r { Spelling.fix(q) }, r { Lessons.asked(q) }, r { Vetting.asked(q) }, r { SelfCalibration.asked(q) },
            r { Writer.check(q, listOf("Nifty is at 24,500."), "Nifty is at 24,500.") }, r { Secrets.redact(q) },
        ).joinToString(" | ")
    }

    @Test fun anOptionIsKeptApartFromThePlainPattern() {
        val p = "\\b(review|should)"
        assertSame(rx(p, RegexOption.IGNORE_CASE), rx(p, RegexOption.IGNORE_CASE))
        assertEquals(false, rx(p).containsMatchIn("REVIEW"))
        assertEquals(true, rx(p, RegexOption.IGNORE_CASE).containsMatchIn("REVIEW"))
    }

    @Test fun everyReadingAsBeforeTheChange() {
        assertEquals(SAID.size, BEFORE.size)
        SAID.zip(BEFORE).forEach { (q, want) -> val got = reading(q); assertEquals(want, digest(got), "$q -> $got") }
    }

    /** The first 32 hex digits of the SHA-256 of [s] (one reading runs to a thousand characters or more). */
    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** [digest] of the [reading] of each of [SAID] by the code before these patterns were kept (worked out on that code, 2026-10-05). "How are my strategies doing" changed on purpose since: it reads the strategies' health ([BotHealth]). */
    private val BEFORE = listOf(
        "21b4b46640902b57c2eefc80d93a8510", "486186425ff72b4953c872abf4ea0bf3", "1d572b4a988a793877467a996869ddf7", "afaa2a88d0eccf487339098e390a4e52",
        "5aea275226aa3dcf89a0838750b7eef3", "1fedb7baf184647d9b175ac75b5ad364", "a39637ba602f84a77274b26ecfe9ff97", "fa85f6771b138d00c52e174e6d0f1362",
        "86a3a59b6ed113ea887002e85a647cb7", "652cce0efaa04ae688370d6325fda7a6", "807bf441da4894ee1a40eec57c06bd3a", "faf997f1322f1acaffb30b0f5dcf9d82",
        "a5539e1c420156eb610d3c35af8b0bb0", "58cb659187f0b7ae361e91fe86c9ec2a", "80361049014f7035312f9704de154e9c", "3235325935fca08c93bc1df0371c26c9",
        "7b961e40265238c8d70cedec39b07aa4", "fd9c4f88ae06e241799ca277904f37c5", "d576cffad468798f85efc382c15fe256", "c8af25285f97822806c8c9de5584fd41",
        "1386ec624767dfcf54e506125a6ce049", "8cb356bf647d4a9fdc9fe1ab53132e02", "cbade931f9ba930328ea6b3309cfffbd", "c197c251af8e75c2b9f692ba343e1f24",
        "f209197268d6768463b35a7968a3ff51", "0f676e62d3423c558d6d3483065e96e3", "2a04f9a7700040369877a8a2aee4a7c9", "c6b407c36dcb674c75bf2eb70e12172f",
        "ed1dc55ddce238a639956cf2a4a6d829", "4ab1d95ebbdbe97ad0bafbe73ddfb57e", "720b9d0c60cc64c9142f51f9e9f5ad22", "1acd228bdde754569456276430c313e1",
        "70da9b205ae532cc34ef07e4a1c1d054", "cca84f9740b531b2127e6f2dca92b44e", "9b6f81e2e9fb2199ccc68741674212e3", "b1877e74893270380916ea54ef151df4",
        "e6f631b4110837b5e86009cea0f58e02", "b8e07795d5fbebcee1e11cffaae5d49e", "0edd0bebe38aa846751d4a7198813c04", "a76d8b99a764ce38325ca3250bee6a21",
        "82f4f6b7f5854132cc7dfbb11c97af46", "bc854c53d33ecabd9424a12e884f5292", "362bcccd3f4c187e877bbafdb9423d1f", "97ae8e4637b3800dd6ffddc3a24b6077",
        "e1e881b2288cac621c445728fedd9ce0", "0de40a7f6c87b2f34b87aace61bdc61e", "7d6922e9dd9fa4332713c89fe91a7363", "42ef2d591cad199291e7765b80ea66f2",
        "5f64983f28a27ab69afe7139898846db", "169e7722fa85f990729cbdc77f5ea0a3", "1b1b08d31e2441c3c5dfa60a858ed751", "d5b4f4f707cc28ad0b0075f372b8ac17",
        "6948464456d068c2e3ae5391d64198c3", "1511dabb236cc93be18dffb27bba0a5f", "4c3909bad67ecb87efc1ea62ce31f1ff", "012682cc89dc9cad19f83659973fb3fc",
        "5f876d3e7c6b4bf87e9058f53af1655b", "8a87a4b3869b4d39622809e147b29881", "4400d74fe64e0c5099b80f7420331db4", "372dabce80d347ec90b3040c0fe40c52",
        "ffd610045eafec7128964e362ccbb213", "1527e098b4d3b36b2befe149655c12d6", "173fd924e2f3f0a6e511c47be4e27fa1", "01034d696526a795bc63139ced6ac739",
        "05f41553c0f671439683afe89edb4cf4", "271d0d0b2a7ef6c39d057ffd952965ae", "7691379dadebff310c60462bc6eda901", "0e65946a62d226840be3b9ace338060b",
        "d2f255a0b763d941f46054a8cdc79efc", "5783d06635d4502d7beceb231cc0c2e3", "2ab527e9e865c2ed441b5ed986a58b09", "4f4e9dfad3f5d6e803d265bed84e4399",
        "5329bd5a802b9ca8c6f834dd8fb13f70", "7940b7ba0ae5e75924691eb788441519", "93cf15c4243d13cd4a4af4e15b2d1c86", "7e9fc581ee18b07c103811277260e41f",
        "4089874424b0a410722982d6b2f6fdc9", "3221f1c2f6514c664ddf5216a2d9dd97", "7e70d3c837aa868eb7574b854db1f29f", "d7df815d32172360697863196d2e8260",
        "df2b742c8ec0b08639aaad8e4fbbf456", "d9b456e3f3728cf0dafbe5979953f3d5", "eb8e49b3f504b15ecbc584addf99105e", "1827f7411b037be312dcfb4eb2a4ceca",
        "b18478e432307f774f5781687b0f397b", "c3c2178df67eb2770e9c418cd2e4f599", "074eedcface1164ccc03ad3f0dcb47a9", "e95f187f7bb81c541edee17dac9c3e31",
        "3332a46e03703538adc9bb3244940c15", "777a2af8d769008786af021053f45645", "75e2e6159f9969c46cf129f8ac173947", "d1ce107f7130697eb6fe3305e68bb171",
        "4e7950d841ff9447b535b2c16aa008bd", "e0552dc7bf26d4b397456e980f6032dc", "d1dbca9742d12522de4983cd4a0553d2", "306469bcc720715e8e7a2318dca1d3ce",
        "7400e38fb22810612aa133a697694bec", "47bedd6f655c962c94334e0a2d6784c1", "c8c6354d0a18f76b7100134f759742de", "25e95ca0c654f9d0779d82ac590ff1c4",
        "ae16995fe39b8e157c83837a9d40d2b2", "af43ca81d98ba5b8d9c1160ff7841fe3", "5c6076cc7e8eb2455b163cccb3774da6", "9a6502845989938354a08a947cabbd15",
        "0cf6462c2dd5ae17e63c973ffb746f5d", "227ebcd63d76cfc51e73286558709f32", "fbf3d20364d19b2749f4cf943438bff9", "3fe840742ce6319ffe2b5d5b650fb737",
        "f91e8a2100c25a9724487de92e3adc94", "bb397a1322803999048a22c9b0543e33", "d9e3ff75156562d7bf4b7fc32ce9049d", "197b4d8948a402dcc26652446a0b9e0f",
        "69d99c23a0afd0cbf2f925b653d93809", "1b1b2a1f76fbf6f96c386bd9e43356ec", "013cc74a1eb736d103ee3fb222d01250", "79bd6830bc0ce28829379f4fe03fada7",
        "adb8e5e0cc00d8fea671e5f264d62d0a", "a4ace6399873a35d779471028f7eaefb", "6446541ee43b5215819c0c7fd375e11f", "3d8d4d8f97394401176baff747ab7320",
        "6a3e66ee21cd245cee6d314648bb85be", "a2368943eaae3f7de9bc931123e63bac", "daf2950cd0a9668e07071cb3b6f0e9b0", "814289cc4ca62a49e552d5bbc79c8270",
        "c43e6f3ca014f070480be1fa831e6ebb", "f30ea1f61f9ea83ec5c4e882c13d5831", "6a074a8437f79da07929506c3b4fb359", "8bcf6d491f3a147b8d6a4c0f1b4f78d9",
        "a2ffde519ba651deb69d26e16cf34241", "cf2a89edcb60d5bf3b55c52f6b97439e", "4defb8c603bcaa179695d82cee445e94", "f769053a87bba8e6f7f1fcbab74b5082",
        "c77151e93b9ab56dbafc252e2752ff32", "e3ce077c3128446e85fe1368bfc44968", "56b350a58e10541c6c5cf5d235326c34", "6b259e4b448e56149670f50424802160",
        "870e6288f46229dcb5fbb05637803be1", "bd6fca8796f2f296f2fb66396bb8b4c4", "980e9244a5c0f11ca64e05fa488b0b83", "828041b1d075bc8c36a48e344eaaa015",
        "86e8f7b9f6f89b070b61362e67423a8e", "74121b1302cb3a235903c30db048a284", "6e26dd89996329cab6b6b232084e5d37", "f823d4f6d7a5876bb58a900a62c9f04f",
        "faaf258781299d22fde6bc81c286dc71", "e0c0536fce6cf3748f3c67c03ccea2d8", "64bdc7f6acea7bfc1beb8318df9e5f36", "eba18e69d16a6da48164de517721fc36",
        "d126fd268872e9d345f1b0361498c496", "2ba42dfe785465b55a95d6823a1b6a1e", "baa22432f821658d1ed33b22074a68df", "a0b27e6a65b0a90fb4bff474700046a9",
        "d2ce25bb5e4a5ef53e66b34a89f1a09a", "8cbd009d5f573b51ef9b9e55d8b4eabf", "54bbed94fc4cd0728437579c7702ade3", "0c3ff14b45931ac49e7077f3cc2974fb",
        "73d00f0c47b97b31ef754eeaa756c137", "f60674b5f6fb7383c3c53267101ebe76", "9a55a29d6f42689815e9ccd9c3c97469", "7343805889fcec502781ea1c046155cc",
        "26b1f93b5aec86543e40bfb8198f3084", "4dc2cda3b690dabe5c2c7d817c1c2f72", "012682cc89dc9cad19f83659973fb3fc", "948551fc57b47aae3ea8c8eba4566165",
        "facabe48b9ceb4aaeb96833ffb78171e", "f5434a92c2097de7c2ffb31c3f14a0d3", "5b38734f610c86861e41af1ec6a53ed7", "714da8410c27734de5608e19d7a200bb",
        "1d8d064f9b5054536d9a7b8b01296712", "09c08541bdc4d9adec983b5e814d34fe", "e748371333721763f1291fead7096472", "bb0ba44adbb0f21a94524640cd99d6ea",
        "81a8dd006857d93cf56954877e115947", "e06d8d3f1fa6c191bdcfc3b4045fb22f", "a4a574e6016f4b6ce3643ba3933a2780", "b1be953500c6d1808c699901ef337272",
        "3e59cd85d5d3f7328c8d4cbc9f6429e1", "4932a19391f88794b3c0fc56edddf4d1", "1a4a475cd3f0b6c06ca130abe3ebb6b7", "eb38091b7d9998590e4b5be62f29362c",
        "826a22173d8e0751c5217bf550f036b9", "383d0544d07f5b21ec551af06f1bd53b", "fd6155e065529e1fd340beaf3bd5a582", "3fc69097f6b5bc9c3a1a49cda2a05ed0",
        "628b62aba8af54d217f6b66039be82cf", "708b14c9e487e39ab3425cf345dc0b4d", "beb5e1e7d3e11f2943982921a0c95e76", "d30a5ccbebb7008a52266f7d2d4ef33a",
        "73a962e0e0895a681fc04fdf0037c6f5",
    )
}
