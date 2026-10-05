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

    /** [digest] of the [reading] of each of [SAID] by the code before these patterns were kept (worked out on that code, 2026-10-05). */
    private val BEFORE = listOf(
        "8651a7fdf5190b4e9bffee921cc2ac38", "eceafb88903851e941b2abf6fd57f042", "59905903f9a386bb9c478cbeeaed4ea6", "b1b0f037985929ed29e8abace3463008",
        "c738c42581c4f068d4a265aebe352056", "e89206af86b2eb3906a53cec8840edc0", "5053849421afb38bb61e4f69a68f70a1", "c9a520c8302a222acfcddf68055bac00",
        "00f55291153c2806f3be19c6567d54a5", "537ed39f7882206b500f5df968f11b34", "44736672499f6a0e86fd9eb787cd6d7c", "535056673a1645cf8cf2709558224af9",
        "be5785a96140c0c6be38d92065ec15f1", "a9a7f5322b7dc22dba5e11a96a68efe2", "80361049014f7035312f9704de154e9c", "8a7870e71e1ef34e155306f0340b3e1b",
        "65692c0f7f2f125e0b70782d1c5d0f56", "9905378e8f9590816f5c5e00185275a3", "b2c0991a444ee4a194aaa6d9ca19403c", "1ae43cbab270148f0b169d2483d2296b",
        "f252fe027b00721350c50a693082e49c", "aa08968a0364cda09571377d674dc82a", "6468d40cba37e4259b345cca3f2e5611", "6e79a3751a6bf0596b73106d59861813",
        "967e3379051b71a7bb82ef25a3abeee3", "0f676e62d3423c558d6d3483065e96e3", "6f2268d6c30d9a66b753ca56b36f1d62", "6c0df506ead64cfce587dd945731c087",
        "73c35beb9134aec955fd283a1427db24", "4ab1d95ebbdbe97ad0bafbe73ddfb57e", "7949d606e56c03310e659cd361eb274d", "fbc1af446faff04517200870f5b8857b",
        "9303b58f3379488cbd7f6f97e3e3780d", "c49f0a28f6c8c7e559dfe1d070052601", "3ba5c441f9c1cfa8021e8a9ad629bc25", "c4195bd3c35936411af15fa0e185006a",
        "ba10a55c81ffcbcd2da3d5e614362665", "c0efa7fcd857a0c63fd91a27de725802", "0edd0bebe38aa846751d4a7198813c04", "a76d8b99a764ce38325ca3250bee6a21",
        "82f4f6b7f5854132cc7dfbb11c97af46", "bc854c53d33ecabd9424a12e884f5292", "9af63a2ec601e8923a9f18efd1fe3406", "da70e974f12aea03dbe2665ce0c8c7f4",
        "44b302bf850cfce5cbd110054fdc1e50", "09a6ac58618f7d89c399fe795804be93", "506606af26e436cd6918d5920d6e6983", "42ef2d591cad199291e7765b80ea66f2",
        "5f64983f28a27ab69afe7139898846db", "169e7722fa85f990729cbdc77f5ea0a3", "ce5d7ae398322f27cbdef6ec0a44e1a9", "a50794ac6c7ea476ea45484072e1a4e2",
        "5beb679589ab1d121896e3d881a4b11a", "d2d29712ec6468a509973dfbf3adee88", "29a29ac0e9d7176f9f9d7bed924f30d7", "88f1642231bae92d5ee4dc8300654028",
        "0345abbdfbcc02c7d890d27f4e3951be", "8a87a4b3869b4d39622809e147b29881", "b260ec7b67e240319c9a0fae96cb58f5", "3e9d97d9c85b8286374f2f791f5c40f7",
        "448b66a5a0bf63c05e8e82600aac27fb", "1527e098b4d3b36b2befe149655c12d6", "173fd924e2f3f0a6e511c47be4e27fa1", "62fc66facc0707b6622c9fd0c0f0f692",
        "05f41553c0f671439683afe89edb4cf4", "61f3ae45eebb57a283ba78083e2d9db7", "1180cefa634fefc945bd8d99a35a4563", "37fd1f6c51fd3ccad8a9a77bb4551a87",
        "eb67f6aa6fce2aeff327d4bf01c9ce02", "ff245404f4984b52a7a9f9f831565d30", "bc1f4abbc62d2c3c620587cd3498e543", "5de00177d7e7f65fb2a8e27bc523a935",
        "bc9adb60deb3f8d7519f146c38e62faf", "8469334d785f38f0f0a1a3c3ef15b10b", "2b84f087abd6d6b9c1553aaba58ac558", "ade43b885ec0d423fa6db3d8ea544b23",
        "4089874424b0a410722982d6b2f6fdc9", "6c90562e800b785e69105e39e4abc188", "66c9f1d95743b921919dcc2b2e58467c", "5d332dda5cad7c5a4b5d433df8e112ea",
        "7092a08de933f62554e33d14b3f83b5d", "d2e9b826322ab3081e53af78ff1e20ce", "05ac35d7ca5d8076db4411bc41f33f04", "883b5eed0c4286eaaac30939022fe634",
        "54d08df9a86ed1b3400bdbe51f619458", "f6f4109533e05f9cda812bba15d08250", "858ab1b87e44e27498ac3518996a7e5f", "0e47a837bd5ceb8b906290ca34393535",
        "6a54d4d52edc70592cf29d056b27639a", "231677400ec7f06d8e297e9c24d60a10", "ae196ad1c16f58f8bb307deff1ae582d", "a0e4cfc318b73670d97c85433ead813f",
        "0f34d8a548c0071b5ef0525751af181a", "650541054112e805cecb176f87239fdd", "118e815fe66400212580100f4bbb638d", "b9cce15341892243889418db3317874b",
        "dbf00b4520829d6fd68650863f551ea5", "4c024bce287f561c5982b5e78a22cbd4", "c8c6354d0a18f76b7100134f759742de", "f57f506ca4a2784d8c12e7d92345ad94",
        "c8f65005bbb030e41194f83364133971", "20175f0e49d99248d65967dcf1675a57", "93ee548383deb7065f8e1439ff6690bd", "9a6502845989938354a08a947cabbd15",
        "0cf6462c2dd5ae17e63c973ffb746f5d", "54dd5863d1638d03efd6fbf66fe58243", "fbf3d20364d19b2749f4cf943438bff9", "a2cca171186705b4a27b2ef940516024",
        "f91e8a2100c25a9724487de92e3adc94", "ada469a4fc7f8a8cb03cc4b3bc352eeb", "d9e3ff75156562d7bf4b7fc32ce9049d", "197b4d8948a402dcc26652446a0b9e0f",
        "c237b0a767b82d797585a87c2d2345c2", "1e7ea08ca869bd4d6e2a90f295872065", "9fdb9940235b03ff4dd30a8abcdbf641", "32c8fbcf4cbbe5c75fd40feda0ce7c7d",
        "f280e7ec183b5c3c3285aad161d1d5fe", "770a376adc1744724b530835c982557c", "7c60d95fbdd182ace6b9eaee78376dff", "3d8d4d8f97394401176baff747ab7320",
        "9449286b536324d71205f243c3cc0506", "27695258bb13fb380362a4b6d6ebddbc", "476c60b5fdf61cd8a4d71d112765b68e", "17da7182645a71bae3f4dc2cf49f2ba3",
        "12e76c5c5fefa24bf80cfdac498bf5b5", "26b9dd5c6aa08ebb69b4e001efd6c0b5", "ea9e60a8300c3e8b41ded94863e7fc0e", "88e64c5f3e6d81a7116c7f605dd0c2c1",
        "c127b29e78fb0b8a2e12e15a32993598", "e1cf23329dd98879e2d5f9d0060519a8", "7a7187f99820c5b06a1ffbf371c5f11a", "82d7fea092fe987a4bfc2d7d446bb488",
        "5d98f26b2b5493d3a2c33627cb3ea46a", "f63257175eb73898c34a98c09283da52", "f3cf8749fbcc81d4b9a1349713c34048", "680418e39a1636cde769465cb968aa41",
        "73783c99a69ff21b7b83f5694cb11e39", "82ef21f23d38fc17691cd85dbc4488f3", "2590fb196e6d06b0d08286a99ce08a6a", "2ee0dea947c3052c798d084d7936f931",
        "7781ff5fd5ddae172812818683c2d3b6", "236768a701f731505a84e6fcba6cc7c5", "6e26dd89996329cab6b6b232084e5d37", "28966766102da85d012d61fc715b736b",
        "84719e770aeb559a5ee1d200858ecffb", "479fbada0de7885220ddaac69cd14f44", "17a463154675d23655cd0bbf2b1bbac3", "eba18e69d16a6da48164de517721fc36",
        "d126fd268872e9d345f1b0361498c496", "2ba42dfe785465b55a95d6823a1b6a1e", "baa22432f821658d1ed33b22074a68df", "a222be5a3b02d08b373b4b43858b8688",
        "a8e3dc8c888bb27d61b07a381de4cbf5", "e602448167123bc5798ffd758da91be5", "5a652387deaff5f2ae3fed4fb564209f", "cb3d9cbaa503ffb1d74cab29cddc996d",
        "4d3125dc049b63d005518e2e9dce1462", "ea218a872669524fab0ac437e8f0c90b", "b15e02e4906bf02ad39decf0e1d8a54d", "6a2f1831160c105b316208d47b4ae751",
        "9fb10b562d5212d71f823e7e2ef5b661", "fd6182038c02e5f9df0ef1ab843ec0bb", "88f1642231bae92d5ee4dc8300654028", "948551fc57b47aae3ea8c8eba4566165",
        "a2f9e7e11aca0e2d6ee97c16e408dc0a", "f5434a92c2097de7c2ffb31c3f14a0d3", "978a216b7f5b9ee5a3f42196d77865e5", "0fdf38f0151aab11e938aa26d1eeb2f4",
        "8a8197aa770323eceedaa1a58a5a4282", "af2311d18942ecb8e85348c963237f80", "639a2e6f6f72e3db2e8a958604e7551f", "bb0ba44adbb0f21a94524640cd99d6ea",
        "4e049690394d6b8d54f9f12475950b99", "ac64a0536a567768377bd59afda904bb", "eaadfe50307647f4c8a22e6aa7d75780", "99d5434031a3e9a9da8a749c43a68c0e",
        "7a96af06e46c82935b058eaa4fc44a7f", "6c8f9f5ab02a17b4e9a6e3fda3509619", "08716bc4e3b90e9b8565b1e33b92f323", "4d068878a6d4c034d6ef6275e6703e9b",
        "826a22173d8e0751c5217bf550f036b9", "bb06c50d9ce9685d3b4550758d822c38", "ee3a2319695499704c2062c60a1a8620", "6551a97cfd975a5185510bbff8654401",
        "07d9f71e1c85e8f6e128dd4efdeb2bf7", "62d4cb3a8acf3c11583a22dd782d6390", "e26fce1a3c74a2c005ad056496b4eb27", "b68867a595a859b8aa570359709470b3",
        "967cca492724460877b5693c07d8f6e6",
    )
}
