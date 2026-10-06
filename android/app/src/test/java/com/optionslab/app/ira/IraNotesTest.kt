package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.TodayNotes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Today's notes: each note Jarvis posts by himself is kept with its time and category (the automation that posted it,
 * else its first words), exactly as it shows in the chat; the switch behind each is its own or its group's; "what did
 * you tell me today" answers with the day's digest; forgetting the conversation forgets them.
 */
class IraNotesTest : RobolectricTest() {
    @Before fun up() {
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(
            java.time.LocalDate.of(2026, 10, 6).atTime(15, 50).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
        IraNotes.clear()
    }

    @After fun down() {
        runBlocking { IraHub.forgetAll() }
        IraNotes.clear()
        com.optionslab.app.data.Market.testClock = null
    }

    @Test fun eachNoteIsKeptWithItsTagAndShownAsBefore() {
        IraHub.note("BankNifty is 12 pts from 54,180 (swing high, 15-min).", from = Automations.Auto.LIQUIDITY)
        IraHub.note("Opening read, Boss (06 Oct) - the open against yesterday's close: Nifty +0.4%.")
        IraHub.note("Your weekly review is ready, Boss.", from = null, kind = TodayNotes.Category.COACH)
        IraHub.note("Muted by voice, for today only.")
        // The chat has each note exactly as posted, as Jarvis's own.
        val chat = IraHub.state.value.messages.map { it.text }
        assertEquals(listOf("BankNifty is 12 pts from 54,180 (swing high, 15-min).", "Opening read, Boss (06 Oct) - the open against yesterday's close: Nifty +0.4%.",
            "Your weekly review is ready, Boss.", "Muted by voice, for today only."), chat)
        assertTrue(IraHub.state.value.messages.all { it.fromIra })
        val today = IraNotes.today()
        assertEquals(listOf(TodayNotes.Category.OTHER, TodayNotes.Category.COACH, TodayNotes.Category.MARKET, TodayNotes.Category.LIQUIDITY), today.map { it.category })
        assertEquals(listOf(null, null, "OPENING", "LIQUIDITY"), today.map { it.source })
        assertEquals(chat.reversed(), today.map { it.text })
        assertTrue(today.all { it.at.toLocalDate() == java.time.LocalDate.of(2026, 10, 6) })
        // Boss's "Forget this conversation" forgets them too.
        IraHub.forgetConversation()
        assertTrue(IraNotes.today().isEmpty())
    }

    @Test fun theSwitchBehindANoteIsItsOwnOrItsGroups() {
        assertEquals("jarvis.switch.group.MARKET" to "Market alerts", IraNotes.switchOf("LIQUIDITY"))
        assertEquals("jarvis.switch.sub.OPENING" to "Opening read", IraNotes.switchOf("OPENING"))
        assertEquals("jarvis.switch.group.COACH" to "Coach me", IraNotes.switchOf("TOMORROW"))
        assertEquals(IraNotes.SOLO_KEY to "Solo (midday)", IraNotes.switchOf(TodayNotes.SOLO))
        // Always on (no switch), retired, unknown or none: no shortcut.
        assertNull(IraNotes.switchOf("RELAY"))
        assertNull(IraNotes.switchOf("SOLO_IDEAS"))
        assertNull(IraNotes.switchOf("NOT_ONE"))
        assertNull(IraNotes.switchOf(null))
        IraHub.note("near a level", from = Automations.Auto.LIQUIDITY)
        IraHub.note("Liquidity 15+5 trade closed - a normal loss.", from = null, kind = TodayNotes.Category.LIQUIDITY)
        IraHub.note("near another level", from = Automations.Auto.LIQUIDITY)
        assertEquals(listOf("jarvis.switch.group.MARKET" to "Market alerts"), IraNotes.switches(IraNotes.today(), TodayNotes.Category.LIQUIDITY))
    }

    @Test fun askedWhatHeSaidTodayAndForgottenWithTheConversation() {
        // The question is Jarvis's (CI's app tests run with Jarvis off: the hub's branch is not there then).
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD)
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        IraHub.ask("what did you tell me today")
        assertEquals(TodayNotes.NONE, IraHub.state.value.messages.last().text)
        IraHub.note("BankNifty is 12 pts from 54,180.", from = Automations.Auto.LIQUIDITY)
        IraHub.note("Tomorrow, Wed 07 Oct: no expiry.", from = Automations.Auto.TOMORROW)
        IraHub.ask("aaj kya bataya")
        val said = IraHub.state.value.messages.last()
        assertTrue(said.fromIra)
        assertEquals(TodayNotes.digest(IraNotes.notes.value, java.time.LocalDate.of(2026, 10, 6)), said.text)
        assertTrue(said.text, said.text.startsWith("Today I've posted 2 notes by myself, Boss: Liquidity 1, Plans 1."))
        // The digest is an answer, not a note: it is not kept among them.
        assertEquals(2, IraNotes.today().size)
        IraHub.forgetConversation()
        assertTrue(IraNotes.today().isEmpty())
    }
}
