package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.signals.TdSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The Signal tester's read-only Telegram login ([TelegramCapture], 15 Oct) against a stand-in for TDLib that sees every request
 * and can push any update - a spy source. Proven: the keys stay in the encrypted settings; only the selected channel's posts
 * reach the reader and the store (a private chat's, a group's and another channel's never do - not in the records, the chats
 * seen, the vault file, the diagnostics or the backup); every request is read-only (never a send, a join, an open or a "mark
 * as read"); each post is read on arrival; a channel re-posting its call with ticks only notes that call; an edit keeps the
 * first text; the history (pages newest first) is read oldest first so its status posts find their calls; capture runs only in
 * its window and closes under memory pressure; "Log out of Telegram" wipes the session and its keys.
 */
class TelegramCaptureTest : RobolectricTest() {
    private lateinit var day: LocalDate
    private val sent = java.util.Collections.synchronizedList(ArrayList<String>())
    private val inbox = LinkedBlockingQueue<String>()
    private val hash = "0123456789abcdef0123456789abcdef"
    private val kings = -1001L
    private val other = -1002L
    private val group = -1003L
    private val mom = 77L

    /** The stand-in: answers the login steps itself; everything else is pushed by the test. */
    /** [loggedIn]: a session already authorised on this phone (a restart after a login): ready as soon as it starts. */
    private inner class Fake(private val loggedIn: Boolean = false) : TelegramCapture.Transport {
        override fun send(json: String) {
            sent += json
            when {
                json.contains("\"getAuthorizationState\"") -> inbox += state("authorizationStateWaitTdlibParameters")
                json.contains("\"setTdlibParameters\"") -> inbox += state(if (loggedIn) "authorizationStateReady" else "authorizationStateWaitPhoneNumber")
                json.contains("\"setAuthenticationPhoneNumber\"") -> inbox += state("authorizationStateWaitCode")
                json.contains("\"checkAuthenticationCode\"") -> inbox += state("authorizationStateReady")
                json.contains("\"loadChats\"") -> listOf(chat(kings, "Sensex Kings", true), chat(other, "Other Calls", true), chat(group, "Family", false)).forEach { inbox += it }
                json.contains("\"close\"") || json.contains("\"logOut\"") -> inbox += state("authorizationStateClosed")
            }
        }
        override fun receive(timeoutSec: Double): String? = inbox.poll((timeoutSec * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

    private fun state(t: String) = """{"@type":"updateAuthorizationState","authorization_state":{"@type":"$t"}}"""
    private fun chat(id: Long, title: String, channel: Boolean) = """{"@type":"updateNewChat","chat":{"id":$id,"title":"$title","type":""" +
        (if (channel) """{"@type":"chatTypeSupergroup","is_channel":true}""" else """{"@type":"chatTypeSupergroup","is_channel":false}""") + "}}"
    private fun unix(t: LocalDateTime) = t.atZone(IST).toEpochSecond()
    private fun msg(chat: Long, id: Long, text: String, t: LocalDateTime, reply: Long? = null, edit: Long = 0) =
        """{"@type":"message","id":$id,"chat_id":$chat,"date":${unix(t)},"edit_date":$edit,""" +
            (reply?.let { """"reply_to":{"@type":"messageReplyToMessage","chat_id":$chat,"message_id":$it},""" } ?: "") +
            """"content":{"@type":"messageText","text":{"@type":"formattedText","text":${TdSession.q(text)}}}}"""
    private fun push(chat: Long, id: Long, text: String, t: LocalDateTime, reply: Long? = null) { inbox += """{"@type":"updateNewMessage","message":${msg(chat, id, text, t, reply)}}""" }

    private fun waitFor(what: String, ms: Long = 8_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > end) throw AssertionError("waited for $what"); Thread.sleep(20) }
    }

    @Before fun up() {
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        Market.testClock = Clock.fixed(day.atTime(9, 20).atZone(IST).toInstant(), IST)
        TelegramCapture.testTransport = { Fake() }
        SignalTesterHost.testHolding = false
        SignalTesterHost.testModelMissing = true
        // Stand-ins for prices and contracts: a call read live starts a pricing round at once, never on the real feed.
        SignalTesterHost.testBars = { _, _ -> emptyList() }
        SignalTesterHost.testContracts = emptyList()
        SignalTesterHost.setOn(true)
    }

    @After fun down() {
        TelegramCapture.resetForTest(); SignalTesterHost.resetForTest(); Market.testClock = null
        assertEquals("no test may reach the internet", emptyList<String>(), com.optionslab.app.testing.NetworkGuard.blocked.toList())
    }

    /** Keys saved, logged in (phone, code), the channel list read. */
    private fun login() {
        assertEquals("The api_hash is 32 letters and digits (0-9, a-f).", TelegramCapture.saveKeys("12345", "nothex"))
        assertEquals("The api_id is a number (my.telegram.org → API development tools).", TelegramCapture.saveKeys("abc", hash))
        // 16 Oct: saved keys start the login at once (no second tap).
        assertEquals("Saved on this phone only (encrypted). Starting Telegram…", TelegramCapture.saveKeys("12345", hash))
        waitFor("the phone step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_PHONE }
        assertEquals("Telegram is waiting for your phone number.", TelegramCapture.startLogin())
        TelegramCapture.phone("+91 99999 00000")
        waitFor("the code step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_CODE }
        TelegramCapture.code("12345")
        waitFor("logged in") { TelegramCapture.view.value.loggedIn }
        waitFor("the channel list", 6_000) { TelegramCapture.view.value.channels.size == 2 }
    }

    @Test fun theKeysAndTheLoginStayEncryptedOnThisPhone() {
        login()
        assertEquals(12345, SecurePrefs.getInt(TelegramCapture.K_API_ID, 0))
        assertEquals(hash, SecurePrefs.getString(TelegramCapture.K_API_HASH))
        assertNotNull(SecurePrefs.getString(TelegramCapture.K_DB_KEY))
        // The database key went to TDLib; the phone number and the code were not kept anywhere.
        assertTrue(sent.any { it.contains("\"database_encryption_key\":\"${SecurePrefs.getString(TelegramCapture.K_DB_KEY)}\"") })
        assertFalse(SecurePrefs.snapshot().values.any { it.toString().contains("9999900000") })
        // Never in a backup: the "telegram." settings are this phone's own.
        assertFalse(com.optionslab.ira.Upkeep.carried(TelegramCapture.K_API_HASH))
        assertFalse(com.optionslab.ira.Upkeep.carried(TelegramCapture.K_DB_KEY))
        val diag = runBlocking { Diag.report() }
        assertFalse(diag.contains(hash)); assertFalse(diag.contains("9999900000"))
        assertTrue(diag.contains("Telegram login (Signal tester, read-only)"))
        // Only channels are listed, by title; nothing is selected.
        assertEquals(listOf("Sensex Kings", "Other Calls"), TelegramCapture.view.value.channels.map { it.title })
        assertTrue(SignalTesterHost.telegramSelected().isEmpty())
    }

    @Test fun onlyTheSelectedChannelReachesTheReaderAndTheStore() {
        login()
        val t = day.atTime(9, 28, 4)
        // Nothing selected: nothing is read, whatever arrives.
        push(kings, 1L shl 20, "#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++", t)
        Thread.sleep(300)
        assertTrue(SignalTesterHost.view.value.recs.isEmpty())
        assertEquals("Reading only: Sensex Kings.", TelegramCapture.select(mapOf(kings to "Sensex Kings")))
        // The spy: a post of every kind of chat; only the selected channel's may come through.
        push(other, 5, "NIFTY 25000 CE above 120 OTHERCHANNEL", t)
        push(group, 6, "NIFTY 25100 CE above 100 FAMILYGROUP", t)
        push(mom, 7, "my bank PIN is 4242 PRIVATECHAT", t)
        push(kings, 2L shl 20, "#sensex 73200 CE\nAbove 250\nTarget 280-300", t.plusMinutes(1))
        inbox += """{"@type":"updateMessageEdited","chat_id":$mom,"message_id":7,"edit_date":${unix(t)}}"""
        waitFor("the selected channel's post") { SignalTesterHost.view.value.recs.isNotEmpty() }
        Thread.sleep(300)
        val recs = SignalTesterHost.view.value.recs
        assertEquals(listOf("Sensex Kings"), recs.map { it.channel }.distinct())
        assertEquals(listOf(SignalTesterHost.TELEGRAM), recs.map { it.source }.distinct())
        assertEquals(2L shl 20, recs.single().msgId)
        val everything = recs.joinToString { it.toString() } + SignalTesterHost.view.value.seen.joinToString() + SignalTesterHost.diagLine() +
            runBlocking { Diag.report() } + File(context.noBackupFilesDir, SignalTesterHost.FILE).readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("OTHERCHANNEL", "FAMILYGROUP", "PRIVATECHAT", "4242", "Family")) assertFalse(secret, everything.contains(secret))
        // Every request was read-only.
        val types = sent.map { Regex("\"@type\":\"(\\w+)\"").find(it)!!.groupValues[1] }
        assertTrue(types.toString(), types.all { it in TdSession.ALLOWED })
        assertTrue(sent.none { it.contains("viewMessages") || it.contains("openChat") || it.contains("sendMessage") || it.contains("joinChat") })
    }

    @Test fun statusPostsRepliesAndEditsNoteTheCall() {
        login()
        TelegramCapture.select(mapOf(kings to "Sensex Kings"))
        val t = day.atTime(9, 28, 4)
        val call = "#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++"
        push(kings, 100, call, t)
        waitFor("the call") { SignalTesterHost.view.value.recs.size == 1 }
        // The same call re-posted with ticks; a reply "done"; a status about a call nobody posted; an edit of the call.
        push(kings, 101, "#sensex 73100 CE\nAbove 280✅\nTarget 310✅-345-400+++", t.plusMinutes(10))
        push(kings, 102, "done ✅", t.plusMinutes(12), reply = 100)
        push(kings, 103, "SL hit in sensex 74000 ce", t.plusMinutes(13))
        inbox += """{"@type":"updateMessageEdited","chat_id":$kings,"message_id":100,"edit_date":${unix(t.plusMinutes(2))}}"""
        waitFor("four records") { SignalTesterHost.view.value.recs.size == 4 }
        waitFor("the edit") { SignalTesterHost.view.value.recs.first().editedAt != null }
        val recs = SignalTesterHost.view.value.recs
        val c = recs[0]
        assertEquals(SignalTesterHost.CALL, c.kind)
        assertEquals(1, recs.count { it.kind == SignalTesterHost.CALL })
        assertEquals(listOf(c.id, c.id), recs.filter { it.kind == SignalTesterHost.FOLLOW }.map { it.link })
        assertEquals(com.optionslab.engine.signals.CallParser.Follow.TARGET, recs[1].follow?.kind); assertEquals(1, recs[1].follow?.targetNo)
        assertEquals(SignalTesterHost.UNKNOWN_CALL, recs[3].reason); assertNull(recs[3].text)
        // Edited after posting: the first text kept and scored, flagged with the edit's time.
        assertEquals(call, c.text); assertEquals(t.plusMinutes(2), c.editedAt)
        assertTrue(com.optionslab.app.ui.screens.callLine(c).contains("edited after posting (at 09:30:04; scored as first posted)"))
        assertEquals(1, SignalTesterHost.view.value.cards.single().editedLater)
        // The same post seen again (a poll): dropped by its id.
        inbox += """{"@type":"messages","total_count":1,"messages":[${msg(kings, 100, call, t)}],"@extra":"p:$kings"}"""
        Thread.sleep(300)
        assertEquals(4, SignalTesterHost.view.value.recs.size)
        // A later call first seen live, then edited, its edit seen only by a poll: flagged too.
        push(kings, 110, "#nifty 25000 PE\nAbove 90\nTarget 110", t.plusMinutes(20))
        waitFor("the second call") { SignalTesterHost.view.value.recs.size == 5 }
        inbox += """{"@type":"messages","total_count":1,"messages":[${msg(kings, 110, "#nifty 25000 PE\nAbove 95\nTarget 110", t.plusMinutes(20), edit = unix(t.plusMinutes(26)))}],"@extra":"p:$kings"}"""
        waitFor("the poll's edit") { SignalTesterHost.view.value.recs.last().editedAt != null }
        assertEquals(t.plusMinutes(26), SignalTesterHost.view.value.recs.last().editedAt)
        assertEquals("#nifty 25000 PE\nAbove 90\nTarget 110", SignalTesterHost.view.value.recs.last().text)
    }

    @Test fun theHistoryIsReadOldestFirstSoItsStatusPostsFindTheirCalls() {
        login()
        TelegramCapture.select(mapOf(kings to "Sensex Kings"))
        val d = LocalDate.of(2025, 3, 4).atTime(10, 0)
        assertTrue(TelegramCapture.fetchHistory(kings).startsWith("Reading its history back to 2024-11-20"))
        assertTrue(sent.last().contains("\"getChatHistory\"") && sent.last().contains("\"h:$kings:0\""))
        // Telegram's pages come newest first: the "T1 done" before its call.
        inbox += """{"@type":"messages","total_count":2,"messages":[${msg(kings, 30, "T1 done", d.plusMinutes(20), reply = 20)}],"@extra":"h:$kings"}"""
        waitFor("the next page asked", 6_000) { sent.count { it.contains("\"getChatHistory\"") } == 2 }
        assertTrue(sent.last().contains("\"from_message_id\":30"))
        inbox += """{"@type":"messages","total_count":2,"messages":[${msg(kings, 20, "NIFTY 25000 CE above 120 tgt 140", d)},""" +
            """${msg(kings, 10, "too old", LocalDate.of(2024, 11, 19).atTime(10, 0))}],"@extra":"h:$kings"}"""
        waitFor("the history read") { SignalTesterHost.view.value.recs.size == 2 }
        val recs = SignalTesterHost.view.value.recs
        assertEquals(listOf(SignalTesterHost.CALL, SignalTesterHost.FOLLOW), recs.map { it.kind })
        assertEquals(recs[0].id, recs[1].link)
        assertEquals(listOf(SignalTesterHost.IMPORTED), recs.map { it.source }.distinct())
        waitFor("done") { TelegramCapture.view.value.fetching == null }
    }

    @Test fun checkThisChatCountsOneChannelOnlyAndKeepsNoText() {
        login()
        assertEquals("Checking…", TelegramCapture.check(other))
        assertTrue(sent.last().contains("\"c:$other:0\""))
        val t = day.atTime(9, 30)
        val page = (1..6).joinToString(",") { msg(other, it.toLong(), "NIFTY 2500$it CE above 1$it tgt 2$it", t.plusMinutes(it.toLong())) }
        inbox += """{"@type":"messages","total_count":6,"messages":[$page],"@extra":"c:$other"}"""
        waitFor("the counts") { SignalTesterHost.view.value.seen.any { it.stats != null } }
        val seen = SignalTesterHost.view.value.seen.single()
        assertEquals("Other Calls", seen.title); assertEquals(6, seen.stats!!.calls); assertTrue(seen.stats!!.looksLikeCalls)
        assertTrue(SignalTesterHost.view.value.recs.isEmpty())
        assertTrue(SignalTesterHost.telegramSelected().isEmpty())
    }

    /**
     * 16 Oct (Boss: channels chosen in Choose channels, diagnostics "0 channels selected", "4 chats followed, 0 seen"): login,
     * the list, two channels selected, then a new process - the selection is read back from the phone, the diagnostics say 2,
     * and both channels' posts are read.
     */
    @Test fun twoChannelsSelectedStaySelectedAfterARestartAndTheirPostsAreRead() {
        MemoryTrim.resetForTest()   // no memory pressure left over from another test (it would keep the reader closed)
        login()
        assertEquals("Reading only: Sensex Kings, Other Calls.", TelegramCapture.select(mapOf(kings to "Sensex Kings", other to "Other Calls")))
        assertTrue(TelegramCapture.diagLine(), TelegramCapture.diagLine().endsWith("· 2 channels selected"))
        // A new process: everything in memory forgotten; the vault file and the settings stay.
        TelegramCapture.resetForTest(); SignalTesterHost.resetForTest()
        TelegramCapture.testTransport = { Fake(loggedIn = true) }
        SignalTesterHost.testBars = { _, _ -> emptyList() }; SignalTesterHost.testContracts = emptyList()
        SignalTesterHost.testModelMissing = true; SignalTesterHost.testHolding = false
        assertEquals(setOf(kings, other), SignalTesterHost.telegramSelected().keys)
        TelegramCapture.refresh()
        assertTrue(TelegramCapture.setCapture(true).startsWith("Capture on"))
        try { readAfterRestart() } finally { TelegramCapture.setCapture(false) }   // capture left on would run in the next tests
    }

    private fun readAfterRestart() {
        TelegramCapture.ensure(day.atTime(10, 0))
        assertTrue(TelegramCapture.view.value.running)
        assertTrue(TelegramCapture.diagLine(), TelegramCapture.diagLine().endsWith("· running · 2 channels selected"))
        // Logged in again by itself, the channel list read (posts are read only from chats the session knows).
        waitFor("the channel list after the restart", 6_000) { TelegramCapture.view.value.channels.size == 2 }
        push(kings, 1L shl 20, "#sensex 73100 CE\nAbove 280\nTarget 310", day.atTime(10, 0, 5))
        push(other, 2L shl 20, "NIFTY 25000 PE above 90 target 120", day.atTime(10, 0, 9))
        push(group, 3L shl 20, "NIFTY 25100 CE above 100 FAMILYGROUP", day.atTime(10, 0, 9))
        waitFor("both selected channels read") { SignalTesterHost.view.value.recs.map { it.channel }.toSet() == setOf("Sensex Kings", "Other Calls") }
        assertFalse(SignalTesterHost.view.value.recs.any { "FAMILYGROUP" in it.text.orEmpty() })
    }

    @Test fun okBeforeTheListCameInChangesNothingAndSaysSoAndASelectionIsReadBack() {
        login()
        TelegramCapture.select(mapOf(kings to "Sensex Kings"))
        // OK with nothing listed: nothing saved, said in plain words (it was silent).
        assertTrue(TelegramCapture.notListed().startsWith("Nothing was saved: your channel list had not come in from Telegram yet."))
        assertEquals(TelegramCapture.notListed(), TelegramCapture.view.value.said)
        assertEquals(mapOf(kings to "Sensex Kings"), SignalTesterHost.telegramSelected())
        // An empty selection is a choice Boss can make, and said as such.
        assertEquals("No channel selected: Telegram is not read.", TelegramCapture.select(emptyMap()))
        assertTrue(TelegramCapture.diagLine().endsWith("· 0 channels selected"))
    }

    @Test fun captureRunsOnlyInItsWindowAndClosesUnderMemoryPressure() {
        val v = TelegramCapture.View(available = true, loggedIn = true, capture = true)
        val d = day
        assertFalse(TelegramCapture.wanted(d.atTime(8, 59), v, pressure = false, tradingDay = true))
        assertTrue(TelegramCapture.wanted(d.atTime(9, 0), v, pressure = false, tradingDay = true))
        assertTrue(TelegramCapture.wanted(d.atTime(15, 34), v, pressure = false, tradingDay = true))
        assertFalse(TelegramCapture.wanted(d.atTime(15, 35), v, pressure = false, tradingDay = true))
        assertFalse(TelegramCapture.wanted(d.atTime(10, 0), v, pressure = true, tradingDay = true))
        assertFalse(TelegramCapture.wanted(d.atTime(10, 0), v, pressure = false, tradingDay = false))
        assertFalse(TelegramCapture.wanted(d.atTime(10, 0), v.copy(capture = false), pressure = false, tradingDay = true))
        assertFalse(TelegramCapture.wanted(d.atTime(10, 0), v.copy(loggedIn = false), pressure = false, tradingDay = true))
        assertFalse(TelegramCapture.wanted(d.atTime(10, 0), v.copy(available = false), pressure = false, tradingDay = true))
        assertTrue(TelegramCapture.wanted(d.atTime(16, 0), v.copy(to = LocalTime.of(23, 30)), pressure = false, tradingDay = true))
        // Running (logged in, capture on, in the window), then memory pressure: closed before anything that watches positions.
        login()
        assertTrue(TelegramCapture.setCapture(true).startsWith("Capture on"))
        TelegramCapture.ensure(day.atTime(10, 0))
        assertTrue(TelegramCapture.view.value.running)
        val released = MemoryTrim.release(com.optionslab.ira.MemoryPlan.releases(com.optionslab.ira.MemoryPlan.RUNNING_MODERATE), com.optionslab.ira.MemoryPlan.RUNNING_MODERATE)
        assertTrue(com.optionslab.ira.MemoryPlan.Item.TELEGRAM in released)
        waitFor("closed") { !TelegramCapture.view.value.running }
        // Capture off: closed, and not started by the round.
        TelegramCapture.setCapture(false)
        TelegramCapture.ensure(day.atTime(10, 1))
        Thread.sleep(200)
        assertFalse(TelegramCapture.view.value.running)
    }

    @Test fun logOutReachesTelegramEvenWhenTheReaderIsClosed() {
        login()
        TelegramCapture.close()
        waitFor("closed") { !TelegramCapture.view.value.running }
        val before = sent.count { it.contains("\"logOut\"") }
        // The stand-in answers the restart: parameters, then the session is already authorised.
        TelegramCapture.testTransport = { object : TelegramCapture.Transport {
            override fun send(json: String) { sent += json
                if (json.contains("\"getAuthorizationState\"")) inbox += state("authorizationStateWaitTdlibParameters")
                if (json.contains("\"setTdlibParameters\"")) inbox += state("authorizationStateReady")
                if (json.contains("\"logOut\"") || json.contains("\"close\"")) inbox += state("authorizationStateClosed") }
            override fun receive(timeoutSec: Double): String? = inbox.poll((timeoutSec * 1000).toLong(), TimeUnit.MILLISECONDS)
        } }
        assertTrue(runBlocking { TelegramCapture.logOut() }.startsWith("Logged out of Telegram"))
        assertEquals(before + 1, sent.count { it.contains("\"logOut\"") })
        assertNull(SecurePrefs.getString(TelegramCapture.K_API_HASH))
    }

    @Test fun logOutWipesTheSessionAndItsKeys() {
        login()
        val dir = File(context.noBackupFilesDir, "tdlib").apply { mkdirs(); File(this, "db.sqlite").writeText("x") }
        assertTrue(runBlocking { TelegramCapture.logOut() }.startsWith("Logged out of Telegram"))
        assertTrue(sent.any { it.contains("\"logOut\"") })
        assertFalse(dir.exists())
        for (k in listOf(TelegramCapture.K_API_ID, TelegramCapture.K_API_HASH, TelegramCapture.K_DB_KEY, TelegramCapture.K_LOGGED_IN))
            assertNull(k, SecurePrefs.getString(k))
        assertEquals(0, SecurePrefs.getInt(TelegramCapture.K_API_ID, 0))
        assertFalse(TelegramCapture.view.value.loggedIn)
        assertEquals("Enter your api_id and api_hash first.", TelegramCapture.startLogin())
    }

    @Test fun notInABuildWithoutTheLibrary() {
        TelegramCapture.resetForTest()
        assertFalse(TelegramCapture.available())
        // The library is not in the tests' build: "not in this build", or (a non-arm64 host) the plain reason - said in the dialog too.
        val why = TelegramCapture.view.value.why
        assertEquals(why?.let { "$it." } ?: "Telegram login is not in this build.", TelegramCapture.startLogin())
        assertEquals(TelegramCapture.view.value.said, why?.let { "$it." } ?: "Telegram login is not in this build.")
        assertEquals(why ?: "Not in this build", TelegramCapture.refresh().line)
    }

    // ---- 16 Oct, Boss: "Telegram login button not working" -----------------------------------------------------------------

    @Test fun theLibrarysAbsenceSaysWhyInPlainWords() {
        assertEquals("Not on this phone: the Telegram login needs a 64-bit ARM phone (arm64-v8a); this one is armeabi-v7a, armeabi",
            TelegramCapture.unavailableWhy(packaged = false, abis = listOf("armeabi-v7a", "armeabi"), error = "UnsatisfiedLinkError"))
        assertEquals("Not on this phone: the Telegram login needs a 64-bit ARM phone (arm64-v8a); this one is unknown",
            TelegramCapture.unavailableWhy(packaged = true, abis = emptyList(), error = "UnsatisfiedLinkError"))
        assertEquals("Telegram's library is in the app but did not load on this phone (UnsatisfiedLinkError)",
            TelegramCapture.unavailableWhy(packaged = true, abis = listOf("arm64-v8a", "armeabi-v7a"), error = "UnsatisfiedLinkError"))
        assertNull(TelegramCapture.unavailableWhy(packaged = false, abis = listOf("arm64-v8a"), error = "UnsatisfiedLinkError"))
        assertEquals("Not on this phone: the Telegram login needs a 64-bit ARM phone (arm64-v8a); this one is armeabi-v7a",
            TelegramCapture.View(why = TelegramCapture.unavailableWhy(false, listOf("armeabi-v7a"), "x")).line)
    }

    @Test fun withoutKeysTheLoginSaysSoInTheDialog() {
        assertEquals("Enter your api_id and api_hash first.", TelegramCapture.startLogin())
        assertEquals("Enter your api_id and api_hash first.", TelegramCapture.view.value.said)
        assertFalse(TelegramCapture.view.value.running)
        // Bad keys: refused before anything is saved or started, the reason in the dialog.
        assertEquals("The api_hash is 32 letters and digits (0-9, a-f).", TelegramCapture.saveKeys("12345", "nothex"))
        assertEquals("The api_hash is 32 letters and digits (0-9, a-f).", TelegramCapture.view.value.said)
        assertEquals("The api_id is a number (my.telegram.org → API development tools).", TelegramCapture.saveKeys("0", hash))
        assertFalse(TelegramCapture.view.value.keys)
        assertNull(SecurePrefs.getString(TelegramCapture.K_API_HASH))
        assertFalse(TelegramCapture.view.value.running)
        assertEquals("Tap \"Log in to Telegram\" first.", TelegramCapture.phone("+91 99999 00000"))
    }

    /** A stand-in that answers as TDLib 1.8.67 does: the update AND the kick's own answer, each with its client id. */
    private inner class Real(val onSend: (String) -> List<String> = { emptyList() }) : TelegramCapture.Transport {
        override val clientId: Int get() = 1
        override fun send(json: String) {
            sent += json
            val c = ""","@client_id":1}"""
            when {
                json.contains("\"setLogStream\"") -> { inbox += """{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitTdlibParameters"}$c"""; inbox += """{"@type":"ok"$c""" }
                json.contains("\"getAuthorizationState\"") -> inbox += """{"@type":"authorizationStateWaitTdlibParameters"$c"""
                json.contains("\"setTdlibParameters\"") && sent.count { it.contains("\"setTdlibParameters\"") } > 1 ->
                    inbox += """{"@type":"error","code":400,"message":"Unexpected setTdlibParameters"$c"""
                json.contains("\"close\"") || json.contains("\"logOut\"") -> inbox += """{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateClosed"}$c"""
            }
            for (x in onSend(json)) inbox += x
        }
        override fun receive(timeoutSec: Double): String? = inbox.poll((timeoutSec * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
    private fun st(t: String, more: String = "") = """{"@type":"updateAuthorizationState","authorization_state":{"@type":"$t"$more},"@client_id":1}"""

    @Test fun theWholeLoginAsTdlibAnswersItEveryStepToldInTheDialog() {
        TelegramCapture.testTransport = { Real { json -> when {
            json.contains("\"setTdlibParameters\"") -> listOf("""{"@type":"ok","@client_id":1}""", st("authorizationStateWaitPhoneNumber"))
            json.contains("\"setAuthenticationPhoneNumber\"") ->
                listOf(st("authorizationStateWaitCode", ""","code_info":{"@type":"authenticationCodeInfo","phone_number":"x","type":{"@type":"authenticationCodeTypeTelegramMessage","length":5},"timeout":0}"""))
            json.contains("\"checkAuthenticationCode\"") && json.contains("\"11111\"") -> listOf("""{"@type":"error","code":400,"message":"PHONE_CODE_INVALID","@client_id":1}""")
            json.contains("\"checkAuthenticationCode\"") -> listOf(st("authorizationStateWaitPassword", ""","password_hint":"pet","has_recovery_email_address":false"""))
            json.contains("\"checkAuthenticationPassword\"") && json.contains("\"wrong\"") -> listOf("""{"@type":"error","code":400,"message":"PASSWORD_HASH_INVALID","@client_id":1}""")
            json.contains("\"checkAuthenticationPassword\"") -> listOf(st("authorizationStateReady"))
            else -> emptyList()
        } } }
        assertEquals("Saved on this phone only (encrypted). Starting Telegram…", TelegramCapture.saveKeys("12345", hash))
        waitFor("the phone step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_PHONE }
        val v = TelegramCapture.view.value
        assertEquals("Telegram started. Enter your phone number with the country code (+91…), then Send.", v.said)
        assertTrue(v.line, v.line.endsWith("· waiting for your phone number"))
        // TDLib asked twice for its parameters (its update and the kick's answer): sent once, so no "Unexpected" error.
        Thread.sleep(300)
        assertEquals(1, sent.count { it.contains("\"setTdlibParameters\"") })
        assertFalse(TelegramCapture.view.value.said.orEmpty().contains("Unexpected"))
        TelegramCapture.phone("+91 99999 00000")
        waitFor("the code step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_CODE }
        assertEquals("Telegram sent you a login code in the Telegram app. Enter it, then Send.", TelegramCapture.view.value.said)
        // A wrong code: TDLib's error, in plain words, in the dialog; still at the code step.
        TelegramCapture.code("11111")
        waitFor("the error") { TelegramCapture.view.value.said == "That login code is not right: enter the newest code Telegram sent." }
        assertEquals(TdSession.Auth.NEED_CODE, TelegramCapture.view.value.auth)
        TelegramCapture.code("12345")
        waitFor("the 2-step password") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_PASSWORD }
        assertEquals("pet", TelegramCapture.view.value.hint)
        assertEquals("Your account has 2-step verification: enter its password, then Send.", TelegramCapture.view.value.said)
        TelegramCapture.password("wrong")
        waitFor("the wrong password") { TelegramCapture.view.value.said == "That 2-step verification password is not right." }
        TelegramCapture.password("right")
        waitFor("logged in") { TelegramCapture.view.value.loggedIn }
        assertEquals("Logged in (read-only). Next: Choose channels.", TelegramCapture.view.value.said)
        // Nothing secret in what is shown: never the phone number, the code or the password.
        val shown = TelegramCapture.view.value.toString() + TelegramCapture.diagLine()
        for (x in listOf("9999900000", "12345", "right", hash)) assertFalse(x, shown.contains(x))
        // Every request read-only.
        assertTrue(sent.all { Regex("\"@type\":\"(\\w+)\"").find(it)!!.groupValues[1] in TdSession.ALLOWED })
    }

    /**
     * 16 Oct: the view says "opened" (running, starting) before the reader reads TDLib's first answer, so the reader alone moves
     * the step and its words together; a step never shows under the last step's words (once seen as the phone step under
     * "Starting Telegram…" in CI).
     */
    @Test fun theViewSaysOpenedBeforeTheReaderReadsAnything() {
        val seen = java.util.concurrent.atomic.AtomicReference<TelegramCapture.View?>()
        TelegramCapture.testTransport = { object : TelegramCapture.Transport by Fake() {
            override fun receive(timeoutSec: Double): String? { seen.compareAndSet(null, TelegramCapture.view.value); return inbox.poll((timeoutSec * 1000).toLong(), TimeUnit.MILLISECONDS) }
        } }
        assertEquals("Saved on this phone only (encrypted). Starting Telegram…", TelegramCapture.saveKeys("12345", hash))
        waitFor("the phone step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_PHONE }
        val first = seen.get()!!
        assertTrue(first.running)
        assertEquals(TdSession.Auth.STARTING, first.auth)
        assertEquals("Saved on this phone only (encrypted). Starting Telegram…", first.said)
        assertEquals("Telegram started. Enter your phone number with the country code (+91…), then Send.", TelegramCapture.view.value.said)
    }

    @Test fun badKeysAreToldWhenTelegramRefusesThem() {
        TelegramCapture.testTransport = { Real { json -> when {
            json.contains("\"setTdlibParameters\"") -> listOf(st("authorizationStateWaitPhoneNumber"))
            json.contains("\"setAuthenticationPhoneNumber\"") -> listOf("""{"@type":"error","code":400,"message":"API_ID_INVALID","@client_id":1}""")
            else -> emptyList()
        } } }
        TelegramCapture.saveKeys("12345", hash)
        waitFor("the phone step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_PHONE }
        TelegramCapture.phone("+91 99999 00000")
        waitFor("the refusal") { TelegramCapture.view.value.said.orEmpty().contains("api_id / api_hash are not valid") }
        assertTrue(TelegramCapture.view.value.said!!.contains("Log out of Telegram"))
    }

    @Test fun aStartThatStaysStartingSaysWhatToCheckAndTriesAgain() {
        TelegramCapture.stuckMs = 300
        // TDLib that never answers (no network, say).
        TelegramCapture.testTransport = { object : TelegramCapture.Transport {
            override fun send(json: String) { sent += json; if (json.contains("\"close\"")) inbox += state("authorizationStateClosed") }
            override fun receive(timeoutSec: Double): String? = inbox.poll(minOf(200L, (timeoutSec * 1000).toLong()), TimeUnit.MILLISECONDS)
        } }
        TelegramCapture.saveKeys("12345", hash)
        val v0 = TelegramCapture.view.value
        assertTrue(v0.busy); assertEquals(TdSession.Auth.STARTING, v0.auth); assertTrue(v0.line, v0.line.endsWith("· starting"))
        waitFor("the stuck message") { TelegramCapture.view.value.stuck }
        assertEquals(TelegramCapture.STUCK_WORDS, TelegramCapture.view.value.said)
        assertTrue(TelegramCapture.STUCK_WORDS.contains("Try again"))
        // "Try again": closed and started afresh, against a TDLib that answers this time.
        TelegramCapture.testTransport = { Fake() }
        TelegramCapture.stuckMs = TelegramCapture.STUCK_MS
        assertEquals("Starting Telegram…", runBlocking { TelegramCapture.retryLogin() })
        waitFor("the phone step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_PHONE }
        assertFalse(TelegramCapture.view.value.stuck)
    }

    @Test fun aStepOnlyTheTelegramAppCanDoIsTriedAfreshOnTheNextTap() {
        var starts = 0
        TelegramCapture.testTransport = { starts++; Real { json -> if (json.contains("\"setTdlibParameters\""))
            listOf(if (starts == 1) st("authorizationStateWaitEmailAddress", ""","allow_apple_id":false,"allow_google_id":false""") else st("authorizationStateWaitPhoneNumber"))
            else emptyList() } }
        TelegramCapture.saveKeys("12345", hash)
        waitFor("the unsupported step") { TelegramCapture.view.value.auth == TdSession.Auth.UNSUPPORTED }
        assertTrue(TelegramCapture.view.value.said.orEmpty().contains("only the Telegram app can do"))
        // Boss finishes it in the Telegram app, then taps "Log in to Telegram" again: closed and started afresh.
        assertEquals("Starting Telegram…", TelegramCapture.startLogin())
        waitFor("the phone step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_PHONE }
        assertEquals(2, starts)
    }

    @Test fun tdlibsErrorWhileStartingStaysInFrontOfTheStuckWords() {
        TelegramCapture.stuckMs = 300
        TelegramCapture.testTransport = { Real { json -> if (json.contains("\"setTdlibParameters\""))
            listOf("""{"@type":"error","code":400,"message":"Valid api_id must be provided. Can be obtained at https://my.telegram.org","@client_id":1}""") else emptyList() } }
        TelegramCapture.saveKeys("12345", hash)
        waitFor("the stuck message") { TelegramCapture.view.value.stuck }
        val said = TelegramCapture.view.value.said.orEmpty()
        assertTrue(said, said.startsWith("Telegram says the api_id / api_hash are not valid"))
        assertTrue(said, said.endsWith(TelegramCapture.STUCK_WORDS))
    }

    @Test fun aBackgroundTrimKeepsALoginInProgressRealShortageClosesIt() {
        TelegramCapture.saveKeys("12345", hash)
        waitFor("the phone step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_PHONE }
        TelegramCapture.phone("+91 99999 00000")
        waitFor("the code step") { TelegramCapture.view.value.auth == TdSession.Auth.NEED_CODE }
        // Boss leaves the app to read the code in Telegram: a background trim. The login stays; the rest is still let go.
        val bg = com.optionslab.ira.MemoryPlan.BACKGROUND
        val done = MemoryTrim.release(com.optionslab.ira.MemoryPlan.releases(bg), bg)
        assertFalse(com.optionslab.ira.MemoryPlan.Item.TELEGRAM in done)
        assertTrue(com.optionslab.ira.MemoryPlan.Item.MODEL in done)
        assertTrue(MemoryTrim.lastLine.orEmpty().endsWith("the position watch untouched"))
        Thread.sleep(200)
        assertTrue(TelegramCapture.view.value.running)
        assertEquals(TdSession.Auth.NEED_CODE, TelegramCapture.view.value.auth)
        // Real shortage while showing: closed, and the dialog says how to carry on.
        val low = com.optionslab.ira.MemoryPlan.RUNNING_LOW
        assertTrue(com.optionslab.ira.MemoryPlan.Item.TELEGRAM in MemoryTrim.release(com.optionslab.ira.MemoryPlan.releases(low), low))
        waitFor("closed") { !TelegramCapture.view.value.running }
        assertTrue(TelegramCapture.view.value.said.orEmpty().startsWith("Telegram was closed to free memory"))
        MemoryTrim.resetForTest()
    }
}
