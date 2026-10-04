package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfWhyTest {
    private val t = LocalDateTime.of(2026, 10, 5, 9, 0)
    private val log = listOf(
        Activity.Entry(t.plusMinutes(5), "Today's plan: BankNifty is sideways. I parked ORB 5 (on sideways days it made -Rs 3,000.00 over 6 trades)"),
        Activity.Entry(t.plusMinutes(40), "Held back a Nifty idea: you asked me to remember \"no trades before 9:30\""),
        Activity.Entry(t.plusMinutes(90), "Took on paper by myself: buy 1 lot of the Nifty call at the money (pattern)"))

    @Test fun heExplainsHimself() {
        assertTrue(SelfWhy.asked("Jarvis why did you park ORB 5?")); assertTrue(SelfWhy.asked("why was ORB 5 parked")); assertFalse(SelfWhy.asked("why is nifty down"))
        assertTrue(SelfWhy.find("why did you park ORB 5?", log)!!.what.contains("parked ORB 5"))
        assertTrue(SelfWhy.find("why did you hold back the nifty idea", log)!!.what.startsWith("Held back"))
        assertEquals(log[2], SelfWhy.find("why did you do that?", log))                 // the latest thing
        assertTrue(SelfWhy.answer("why did you park ORB 5", log, t).startsWith("Today at 09:05: Today's plan: BankNifty is sideways. I parked ORB 5"))
        assertTrue(SelfWhy.answer("why did you sell gold", log, t).startsWith("I have no record"))
    }
}
