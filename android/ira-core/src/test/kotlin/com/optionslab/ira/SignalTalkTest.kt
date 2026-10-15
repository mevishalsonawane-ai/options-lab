package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jarvis on the Signal tester ([SignalTalk]): what is asked (a channel, or all), what is not (actions, chart channels), the answer. */
class SignalTalkTest {
    @Test fun aChannelOrAllIsAsked() {
        assertEquals(SignalTalk.Ask("sensex kings"), SignalTalk.asked("How is channel Sensex Kings doing?"))
        assertEquals(SignalTalk.Ask("bank nifty pro"), SignalTalk.asked("is the Bank Nifty Pro channel any good"))
        assertEquals(SignalTalk.Ask("sensex kings"), SignalTalk.asked("channel sensex kings kaisa chal raha hai"))
        assertEquals(SignalTalk.Ask(null), SignalTalk.asked("how are the telegram channels doing"))
        assertEquals(SignalTalk.Ask(null), SignalTalk.asked("signal tester ka kya haal hai"))
        assertEquals(SignalTalk.Ask(null), SignalTalk.asked("how is the channel tester doing"))
    }

    @Test fun actionsAndChartChannelsAreNotThisQuestion() {
        assertNull(SignalTalk.asked("add channel sensex kings to the signal tester"))
        assertNull(SignalTalk.asked("switch off the signal tester"))
        assertNull(SignalTalk.asked("how is the donchian channel doing"))
        assertNull(SignalTalk.asked("how is nifty doing"))
        assertNull(SignalTalk.asked("what is the price channel breakout"))
    }

    @Test fun theAnswerIsFromTheScorecards() {
        val ch = listOf(SignalTalk.Channel("Sensex Kings", "5 calls, 2 triggered", listOf("Deepest drawdown −₹400.")),
            SignalTalk.Channel("BN Pro", "1 call, 0 triggered", emptyList()))
        val one = SignalTalk.answer(SignalTalk.Ask("sensex kings"), ch, on = true)
        assertTrue(one.startsWith("Signal tester (shadow — never trades), Sensex Kings: 5 calls, 2 triggered. Deepest"))
        assertTrue(SignalTalk.answer(SignalTalk.Ask("kings"), ch, true).contains("Sensex Kings: 5 calls"))
        assertTrue(SignalTalk.answer(SignalTalk.Ask("crypto gurus"), ch, true).contains("no channel called \"crypto gurus\". On your allow-list: Sensex Kings, BN Pro"))
        assertEquals("Signal tester (shadow — never trades), switched off: Sensex Kings: 5 calls, 2 triggered; BN Pro: 1 call, 0 triggered.",
            SignalTalk.answer(SignalTalk.Ask(null), ch, on = false))
        assertTrue(SignalTalk.answer(SignalTalk.Ask(null), emptyList(), true).contains("no channels on your allow-list"))
    }
}
