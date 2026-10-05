package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayHealthTest {
    private val now = LocalDateTime.of(2026, 10, 5, 15, 40)
    private val ip = "144.24.125.164"
    private val timeout = "connect FAILED to the server as opc/ubuntu/ec2-user: JSchException: java.net.SocketTimeoutException: Read timed out"
    private val notEstablished = "connect FAILED to the server as opc/ubuntu/ec2-user: JSchException: timeout: socket is not established"

    /** Boss's 5 Oct, as the diary kept it: good at 11:55, failing every 5 minutes from 13:01 with one connect at 14:00. */
    private fun day(): List<String> {
        val out = ArrayList<String>()
        out += "10-05 09:00:05 [static-ip] Morning check: Not on your registered static IP $ip: new live positions will be refused"
        out += "10-05 11:55:02 [relay] connected as opc"
        var t = LocalDateTime.of(2026, 10, 5, 13, 1, 0)
        while (t.isBefore(LocalDateTime.of(2026, 10, 5, 15, 25))) {
            if (t.hour == 14 && t.minute == 1) out += "10-05 14:00:30 [relay] connected as opc"
            else {
                out += "10-05 %02d:%02d:%02d [relay] %s".format(t.hour, t.minute, t.second, if (t.minute % 2 == 0) timeout else notEstablished)
                // A second connection retrying in the same breath: one try, not two.
                if (t.hour == 13 && t.minute == 11) out += "10-05 13:11:08 [relay] $timeout"
            }
            t = t.plusMinutes(5)
        }
        out += "10-05 13:30:00 [zerodha] POST /session/token via relay -> FAILED IOException: Relay: cannot reach $ip on port 22 (is the server running, with the IP attached?) (15012 ms)"
        out += "10-05 13:31:00 [zerodha] GET /portfolio/positions via relay -> FAILED IOException: Relay: cannot reach $ip on port 22 (is the server running, with the IP attached?) (15003 ms)"
        out += "10-05 13:32:00 [tap] Square off all"
        return out
    }

    private val setup = RelayHealth.Setup(relayOn = true, relaySet = true, connected = false, registeredIp = ip,
        live = true, realOrders = true, linked = true, loggedIn = true)

    @Test fun asks() {
        listOf("why is my relay failing", "Why is my relay failing?", "is the relay working", "why can't the relay connect", "is my relay server up",
            "what's wrong with the relay", "relay health", "relay status", "is the relay down", "relay kyun nahi chal raha", "relay ka kya haal hai",
            "why does the relay keep timing out", "when did the relay last connect", "why is zerodha login failing through the relay",
            "relay kaam kar raha hai kya", "is my oracle server up", "how's the relay").forEach { assertEquals(RelayHealth.Asked.RELAY, RelayHealth.asked(it), it) }
        listOf("is my static ip working", "is my static IP OK?", "am i on my static ip", "am i on my registered static ip", "static ip kaam kar raha hai kya",
            "is the static ip right", "are my orders going from my static ip").forEach { assertEquals(RelayHealth.Asked.STATIC_IP, RelayHealth.asked(it), it) }
        listOf("can I trade live right now?", "can i trade live now", "can i place live orders now", "can i place a live order right now",
            "will live orders go through now", "are live orders working", "is live trading possible right now", "abhi live trade kar sakta hoon kya")
            .forEach { assertEquals(RelayHealth.Asked.LIVE, RelayHealth.asked(it), it) }
        // Doing something to the relay or the mode is never answered here: those are Boss's own taps.
        listOf("turn on the relay", "switch off the relay", "connect the relay", "reconnect the relay", "test the relay", "relay on karo",
            "relay band karo", "relay on", "forget server", "go live", "switch to live mode", "can i go live", "am i on live", "can i trade now",
            "is nifty working its way up", "is the market down", "why is nifty failing at 25000", "what is a static ip address", "why did zerodha log me out")
            .forEach { assertNull(RelayHealth.asked(it), it) }
    }

    @Test fun theLiveWordingsNeitherActNorBundleAndRouteHere() {
        val audit = CoverageTest()
        for (s in listOf("can i trade live right now", "can i trade live now", "can i place live orders now", "will live orders go through now",
            "is live trading possible right now", "why is my relay failing", "relay kyun nahi chal raha", "is my static ip working")) {
            assertEquals("RelayHealth", audit.feature(s), s)
            val p = Ask.parse(s)
            assertNull(p.order, s); assertNull(p.command, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertFalse(Bundle.acts(s), s)
            assertNull(Intents.quick(s), s)
        }
        // "Can I go live?" stays the readiness checklist, "can I trade now?" the trade check, "go live" the mode switch (its own confirm).
        assertEquals("Act", audit.feature("go live"))
        assertTrue(audit.feature("can i trade now") != "RelayHealth")
        assertTrue(audit.feature("can i go live") != "RelayHealth")
    }

    @Test fun failuresInPlainWords() {
        assertEquals(RelayHealth.Fail.TIMEOUT, RelayHealth.fail("JSchException: java.net.SocketTimeoutException: Read timed out"))
        assertEquals(RelayHealth.Fail.TIMEOUT, RelayHealth.fail("JSchException: timeout: socket is not established"))
        assertEquals(RelayHealth.Fail.TIMEOUT, RelayHealth.fail("IOException: Relay: cannot reach 1.2.3.4 on port 22"))
        assertEquals(RelayHealth.Fail.AUTH, RelayHealth.fail("JSchException: Auth fail"))
        assertEquals(RelayHealth.Fail.HOSTKEY, RelayHealth.fail("JSchException: HostKey has been changed: 1.2.3.4"))
        assertEquals(RelayHealth.Fail.REFUSED, RelayHealth.fail("JSchException: java.net.ConnectException: Connection refused"))
        assertEquals(RelayHealth.Fail.NETWORK, RelayHealth.fail("JSchException: java.net.ConnectException: Network is unreachable"))
        assertEquals(RelayHealth.Fail.DNS, RelayHealth.fail("JSchException: java.net.UnknownHostException: server"))
        assertEquals(RelayHealth.Fail.CLOSED, RelayHealth.fail("JSchException: Session.connect: java.io.IOException: End of IO Stream Read"))
        assertEquals(RelayHealth.Fail.OTHER, RelayHealth.fail("JSchException: something odd"))
    }

    @Test fun bossesFifthOfOctober() {
        val ev = RelayHealth.events(day(), now)
        assertEquals(2, ev.count { it.kind == RelayHealth.Kind.CONNECTED })
        assertEquals(2, ev.count { it.kind == RelayHealth.Kind.ZERODHA_FAILED })
        assertEquals(1, ev.count { it.kind == RelayHealth.Kind.OFF_STATIC_IP })
        val said = RelayHealth.answer(RelayHealth.Asked.RELAY, day(), now, setup)
        assertTrue(said.startsWith("Boss, the relay has been failing for 1 hour 34 minutes: 16 failed connects since 14:06, about every 5 minutes, the last at 15:21; " +
            "the last good connect was at 14:00."), said)
        assertTrue("It's been on and off since 13:01: 29 failures today with 1 good connect between them." in said, said)
        assertTrue("Every one was a timeout reaching the server on port 22" in said, said)
        assertTrue("cloud instance stopped, the reserved IP detached from it, port 22 closed in its security list" in said, said)
        assertTrue("The Zerodha login through the relay failed 1 time today, the last at 13:30, and 1 other Zerodha call failed through it too." in said, said)
        assertTrue("found you not on your registered static IP $ip" in said, said)
        assertTrue("the instance is running, that the reserved IP is attached to it, and that port 22 is open in its security list; then tap Connect & test" in said, said)
        assertTrue(said.endsWith("I don't change a setting or connect on my own."), said)
        // Counts and times only: no user name, raw error or exception from the record.
        for (w in listOf("opc", "ubuntu", "ec2-user", "JSch", "SocketTimeout", "Exception", "socket is not established")) assertFalse(w in said, "$w in: $said")
    }

    @Test fun theStaticIpAskedLeadsWithTheIp() {
        val said = RelayHealth.answer(RelayHealth.Asked.STATIC_IP, day(), now, setup)
        assertTrue(said.startsWith("Boss, the last static IP check, at 9:00, found you not on your registered static IP $ip: new live positions are refused until you are " +
            "(with the relay on, that means the relay isn't carrying the orders)."), said)
        assertTrue("The relay has been failing for" in said, said)
    }

    @Test fun canITradeLiveIsFactsOnly() {
        val said = RelayHealth.answer(RelayHealth.Asked.LIVE, day(), now, setup)
        assertTrue(said.startsWith("Facts only, Boss: the app is in Live mode; Zerodha is logged in; the relay is failing; the last static IP check (9:00) found you off your registered IP."), said)
        assertTrue("So a live order wouldn't go through right now (the relay, the static IP)." in said, said)
        assertTrue("The relay has been failing for 1 hour 34 minutes" in said, said)
        val paper = RelayHealth.answer(RelayHealth.Asked.LIVE, emptyList(), now, RelayHealth.Setup(false, false, false, null))
        assertEquals("Facts only, Boss: the app is in Paper mode; Zerodha isn't linked. So a live order wouldn't go through right now (Paper mode, no Zerodha).", paper)
        val clear = RelayHealth.answer(RelayHealth.Asked.LIVE, listOf("10-05 09:00:05 [static-ip] Morning check: On your registered static IP $ip",
            "10-05 09:01:00 [relay] connected as opc"), now, setup.copy(connected = true))
        assertTrue(clear.endsWith("Nothing in that stops a live order now; each one still needs your fingerprint, and Zerodha has the last word."), clear)
        // The morning's "off" is not held against a relay that connected after it.
        val after = RelayHealth.answer(RelayHealth.Asked.LIVE, listOf("10-05 09:00:05 [static-ip] Morning check: Not on your registered static IP $ip: new live positions will be refused",
            "10-05 09:20:00 [relay] connected as opc"), now, setup.copy(connected = true))
        assertTrue("before the relay connected again at 9:20" in after && "Nothing in that stops" in after, after)
        assertTrue("kill switch" in RelayHealth.answer(RelayHealth.Asked.LIVE, emptyList(), now, setup.copy(kill = true)))
    }

    @Test fun workingAgainAndQuiet() {
        val back = day() + "10-05 15:30:00 [relay] connected as opc"
        val said = RelayHealth.answer(RelayHealth.Asked.RELAY, back, now, setup.copy(connected = true))
        assertTrue(said.startsWith("The relay is connecting again, Boss: last good connect at 15:30. Earlier today it failed 29 times between 13:01 and 15:21, about every 5 minutes."), said)
        assertFalse("What you can check" in said, said)
        val fine = RelayHealth.answer(RelayHealth.Asked.RELAY, listOf("10-05 09:01:00 [relay] connected as opc"), now, setup.copy(connected = true))
        assertTrue(fine.startsWith("The relay is working, Boss: last good connect at 9:01, with no failures in the last 7 days."), fine)
        val none = RelayHealth.answer(RelayHealth.Asked.RELAY, emptyList(), now, setup)
        assertTrue(none.startsWith("Boss, I have no relay connects or failures in the record for the last 7 days."), none)
        val notSet = RelayHealth.answer(RelayHealth.Asked.RELAY, emptyList(), now, RelayHealth.Setup(false, false, false, null))
        assertTrue(notSet.startsWith("The relay isn't set up on this phone, Boss"), notSet)
    }

    @Test fun aRefusedKeySaysTheKeyStep() {
        val lines = listOf("10-05 10:00:00 [relay] connected as opc", "10-05 12:00:00 [relay] NEW relay key made (the old one no longer works): paste the new one into the server",
            "10-05 12:01:00 [relay] connect FAILED to the server as opc/ubuntu/ec2-user: JSchException: Auth fail")
        val said = RelayHealth.answer(RelayHealth.Asked.RELAY, lines, now, setup)
        assertTrue("It was the server answered but refused the app's key" in said, said)
        assertTrue("A new relay key was made at 12:00" in said, said)
        assertTrue("add the app's key" in said, said)
        assertFalse("security list" in said, said)
    }

    @Test fun triesAndCadence() {
        val e = { h: Int, m: Int, s: Int -> RelayHealth.Event(LocalDateTime.of(2026, 10, 5, h, m, s), RelayHealth.Kind.FAILED, RelayHealth.Fail.TIMEOUT) }
        val fails = listOf(e(13, 0, 0), e(13, 0, 20), e(13, 3, 0), e(13, 6, 0), e(13, 9, 5))
        assertEquals(4, RelayHealth.tries(fails).size)
        assertEquals(3L, RelayHealth.cadence(fails))
        assertNull(RelayHealth.cadence(fails.take(3)))
        // A record from last year's December, read in January, is last year's; older than the week is left out.
        val jan = LocalDateTime.of(2027, 1, 2, 10, 0)
        assertEquals(2026, RelayHealth.events(listOf("12-31 15:00:00 [relay] connected as opc"), jan).single().at.year)
        assertTrue(RelayHealth.events(listOf("12-01 15:00:00 [relay] connected as opc"), jan).isEmpty())
        assertEquals("1 hour 34 minutes", RelayHealth.span(java.time.Duration.ofMinutes(94)))
    }
}
