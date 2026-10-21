package com.optionslab.engine.signals

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read-only Telegram login's protocol ([TdSession]) against a scripted stand-in for TDLib: the login steps, only channels
 * listed (titles only), only the selected channel's messages ever becoming events (a spy on everything TDLib could send - a
 * private chat, a group, another channel), the requests allowed (never a send, a join, a leave, an open or a "mark as read"),
 * the history paged back to 20 Nov 2024, edits, polls and TDLib's errors.
 */
class TdSessionTest {
    private val sent = ArrayList<String>()
    private val s = TdSession { sent += it }
    private val params = TdSession.Params(12345, "0123456789abcdef0123456789abcdef", "/data/tdlib/db", "/data/tdlib/files", "a2V5")

    private fun unix(t: LocalDateTime) = t.toEpochSecond(ZoneOffset.ofHoursMinutes(5, 30))
    private fun chat(id: Long, title: String, type: String) = """{"@type":"updateNewChat","chat":{"@type":"chat","id":$id,"title":${TdSession.q(title)},"type":$type}}"""
    private val channelType = """{"@type":"chatTypeSupergroup","supergroup_id":1,"is_channel":true}"""
    private val groupType = """{"@type":"chatTypeSupergroup","supergroup_id":2,"is_channel":false}"""
    private val privateType = """{"@type":"chatTypePrivate","user_id":7}"""
    private val basicType = """{"@type":"chatTypeBasicGroup","basic_group_id":3}"""
    private fun msg(chat: Long, id: Long, text: String, at: LocalDateTime, reply: Long? = null, edit: Long = 0) =
        """{"@type":"message","id":$id,"chat_id":$chat,"date":${unix(at)},"edit_date":$edit,""" +
            (reply?.let { """"reply_to":{"@type":"messageReplyToMessage","chat_id":$chat,"message_id":$it},""" } ?: "") +
            """"content":{"@type":"messageText","text":{"@type":"formattedText","text":${TdSession.q(text)},"entities":[]}}}"""
    private fun newMsg(chat: Long, id: Long, text: String, at: LocalDateTime, reply: Long? = null) = """{"@type":"updateNewMessage","message":${msg(chat, id, text, at, reply)}}"""
    private val t0 = LocalDateTime.of(2026, 10, 15, 9, 28, 4)

    private fun login() {
        s.start(params)
        s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitTdlibParameters"}}""")
        s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateReady"}}""")
    }

    @Test fun theLoginSteps() {
        s.start(params)
        assertTrue(sent[0].contains("logStreamEmpty")); assertTrue(sent[1].contains("\"new_verbosity_level\":0"))
        val e = s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitTdlibParameters"}}""")
        val p = sent.last()
        assertTrue(p.startsWith("{\"@type\":\"setTdlibParameters\"")); assertTrue(p.contains("\"api_id\":12345"))
        assertTrue(p.contains("\"database_encryption_key\":\"a2V5\"")); assertTrue(p.contains("\"use_secret_chats\":false"))
        assertEquals(emptyList(), e.filterIsInstance<TdSession.Event.AuthChanged>().filter { it.auth != TdSession.Auth.STARTING })
        s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitPhoneNumber"}}""")
        assertEquals(TdSession.Auth.NEED_PHONE, s.auth)
        s.phone("+91 98xx 0000 11")
        assertTrue(sent.last().startsWith("{\"@type\":\"setAuthenticationPhoneNumber\",\"phone_number\":\"+91"))
        s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitCode","code_info":{"type":{"@type":"authenticationCodeTypeTelegramMessage","length":5}}}}""")
        assertEquals(TdSession.Auth.NEED_CODE, s.auth); assertEquals("in the Telegram app", s.hint)
        s.code(" 12345 ")
        assertTrue(sent.last().contains("\"code\":\"12345\""))
        s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitPassword","password_hint":"pet"}}""")
        assertEquals(TdSession.Auth.NEED_PASSWORD, s.auth); assertEquals("pet", s.hint)
        s.password("p\"w\\x")
        assertTrue(sent.last().contains("\"password\":\"p\\\"w\\\\x\""))
        val r = s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateReady"}}""")
        assertEquals(TdSession.Auth.READY, (r.single() as TdSession.Event.AuthChanged).auth)
        assertNull(s.hint)
        // An e-mail or sign-up step: done in the Telegram app.
        s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitRegistration"}}""")
        assertEquals(TdSession.Auth.UNSUPPORTED, s.auth)
        s.logOut(); assertEquals(TdSession.Auth.LOGGING_OUT, s.auth); assertTrue(sent.last().contains("\"logOut\""))
        s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateClosed"}}""")
        assertEquals(TdSession.Auth.CLOSED, s.auth)
    }

    @Test fun withoutKeysItAsksForThem() {
        s.start(null)
        assertEquals(TdSession.Auth.NEED_KEYS, s.auth)
        s.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitTdlibParameters"}}""")
        assertEquals(TdSession.Auth.NEED_KEYS, s.auth)
        assertTrue(sent.none { it.contains("setTdlibParameters") })
    }

    @Test fun onlyTheSelectedChannelEverBecomesAnEvent() {
        login()
        for (u in listOf(chat(-1001, "Sensex Kings", channelType), chat(-1002, "Other Channel", channelType), chat(-1003, "Family group", groupType),
            chat(7, "Mom", privateType), chat(-4, "Old group", basicType))) s.onUpdate(u)
        // Titles of channels only: no private chat, no group.
        assertEquals(listOf("Sensex Kings", "Other Channel"), s.channels().map { it.title })
        // Nothing is selected at first: nothing is read.
        assertEquals(emptyList(), s.onUpdate(newMsg(-1001, 1L shl 20, "#sensex 73100 CE above 280", t0)))
        s.selected = setOf(-1001)
        val spy = ArrayList<TdSession.Event>()
        for (u in listOf(newMsg(-1001, 2L shl 20, "#sensex 73100 CE above 280", t0, reply = 1L shl 20), newMsg(-1002, 3, "NIFTY 25000 CE above 120 OTHER", t0),
            newMsg(-1003, 4, "group secret 25000 CE above 1", t0), newMsg(7, 5, "private: my bank pin", t0), newMsg(-4, 6, "old group", t0),
            """{"@type":"updateMessageEdited","chat_id":7,"message_id":5,"edit_date":${unix(t0)}}""",
            """{"@type":"updateMessageEdited","chat_id":-1002,"message_id":3,"edit_date":${unix(t0)}}""")) spy += s.onUpdate(u)
        val m = assertIs<TdSession.Event.NewMessage>(spy.single())
        assertEquals("Sensex Kings", m.chat.title); assertEquals("#sensex 73100 CE above 280", m.msg.text)
        assertEquals(t0, m.msg.at); assertEquals(2L shl 20, m.msg.id); assertEquals(1L shl 20, m.msg.replyTo)
        assertTrue(spy.joinToString().let { !it.contains("OTHER") && !it.contains("secret") && !it.contains("bank pin") && !it.contains("old group") })
        // A message of a selected chat id that is not a listed channel (a private chat's id slipped in): still nothing.
        s.selected = setOf(-1001, 7)
        assertEquals(emptyList(), s.onUpdate(newMsg(7, 8, "private again", t0)))
        // Its edit: the time only.
        val ed = s.onUpdate("""{"@type":"updateMessageEdited","chat_id":-1001,"message_id":${2L shl 20},"edit_date":${unix(t0.plusMinutes(3))}}""")
        assertEquals(TdSession.Event.Edited(TdSession.Channel(-1001, "Sensex Kings"), 2L shl 20, t0.plusMinutes(3)), ed.single())
        // A picture with a caption is read; one without text is not.
        val cap = """{"@type":"updateNewMessage","message":{"@type":"message","id":9,"chat_id":-1001,"date":${unix(t0)},"content":{"@type":"messagePhoto","caption":{"@type":"formattedText","text":"NIFTY 25000 PE above 90"}}}}"""
        assertEquals("NIFTY 25000 PE above 90", (s.onUpdate(cap).single() as TdSession.Event.NewMessage).msg.text)
        assertEquals(emptyList(), s.onUpdate("""{"@type":"updateNewMessage","message":{"@type":"message","id":10,"chat_id":-1001,"date":${unix(t0)},"content":{"@type":"messageSticker"}}}"""))
        assertEquals(emptyList(), s.onUpdate("not json"))
        // Renamed: the new title.
        s.onUpdate("""{"@type":"updateChatTitle","chat_id":-1001,"title":"Sensex Kings VIP"}"""); s.onUpdate("""{"@type":"updateChatTitle","chat_id":7,"title":"x"}""")
        assertEquals("Sensex Kings VIP", s.channels().first().title); assertEquals(2, s.channels().size)
    }

    @Test fun readOnlyRequestsOnly() {
        login()
        for (t in listOf("sendMessage", "viewMessages", "openChat", "joinChat", "leaveChat", "addChatMember", "deleteMessages", "setMessageReactions",
            "forwardMessages", "readAllChatMentions", "toggleChatIsMarkedAsUnread", "searchPublicChats", "joinChatByInviteLink", "setOption"))
            assertFailsWith<IllegalArgumentException> { s.request(t) }
        s.onUpdate(chat(-1001, "Sensex Kings", channelType)); s.onUpdate(chat(-1002, "Other", channelType))
        // History: of a selected channel, or the one chat checked on Boss's tap - never of any other.
        assertFailsWith<IllegalArgumentException> { s.history(-1002) }
        assertFailsWith<IllegalArgumentException> { s.poll(-1002) }
        assertFailsWith<IllegalArgumentException> { s.history(7, check = true) }
        s.selected = setOf(-1001)
        s.history(-1001); s.poll(-1001); s.history(-1002, check = true); s.loadChats(); s.close()
        val types = sent.map { Regex("\"@type\":\"(\\w+)\"").find(it)!!.groupValues[1] }
        assertTrue(types.all { it in TdSession.ALLOWED }, types.toString())
        assertTrue(sent.none { it.contains("viewMessages") || it.contains("sendMessage") || it.contains("openChat") })
    }

    @Test fun historyIsPagedBackToTheTwentiethOfNovember() {
        login(); s.onUpdate(chat(-1001, "Sensex Kings", channelType)); s.selected = setOf(-1001)
        s.history(-1001)
        assertTrue(sent.last().contains("\"from_message_id\":0") && sent.last().contains("\"@extra\":\"h:-1001:0\""))
        val page = """{"@type":"messages","total_count":2,"messages":[${msg(-1001, 300, "T1 done", t0.plusMinutes(5), reply = 200)},${msg(-1001, 200, "#sensex 73100 CE above 280", t0)}],"@extra":"h:-1001"}"""
        val h = assertIs<TdSession.Event.History>(s.onUpdate(page).single())
        assertEquals(listOf(200L, 300L), h.msgs.map { it.id }); assertEquals(200L, h.next); assertEquals(200L, h.msgs[1].replyTo)
        // A page reaching before 20 Nov 2024: its older messages dropped, and it is the last.
        val old = LocalDateTime.of(2024, 11, 19, 10, 0)
        val last = """{"@type":"messages","total_count":2,"messages":[${msg(-1001, 150, "NIFTY 25000 CE above 120", old.plusDays(1))},${msg(-1001, 100, "too old", old)}],"@extra":"h:-1001"}"""
        val h2 = assertIs<TdSession.Event.History>(s.onUpdate(last).single())
        assertEquals(listOf(150L), h2.msgs.map { it.id }); assertNull(h2.next)
        assertNull((s.onUpdate("""{"@type":"messages","total_count":0,"messages":[],"@extra":"h:-1001"}""").single() as TdSession.Event.History).next)
        // A poll's page: never paged on. A history answer for a chat no longer selected: dropped.
        assertTrue((s.onUpdate(page.replace("h:-1001", "p:-1001")).single() as TdSession.Event.History).let { it.poll && it.next == null })
        s.selected = emptySet()
        assertEquals(emptyList(), s.onUpdate(page))
        // A page that gives back only the message it started from: the last (never asked again for ever).
        s.selected = setOf(-1001)
        val same = """{"@type":"messages","total_count":1,"messages":[${msg(-1001, 200, "x", t0)}],"@extra":"h:-1001:200"}"""
        assertNull((s.onUpdate(same).single() as TdSession.Event.History).next)
        // "Check this chat": one page, flagged.
        assertTrue((s.onUpdate(page.replace("h:-1001", "c:-1001")).single() as TdSession.Event.History).let { it.check && it.next == null })
    }

    @Test fun tdlibsErrorsAndFloodWaits() {
        val e = assertIs<TdSession.Event.Error>(s.onUpdate("""{"@type":"error","code":429,"message":"Too Many Requests: retry after 35"}""").single())
        assertEquals(429, e.code); assertEquals(35, e.retryAfter)
        assertEquals(12, (s.onUpdate("""{"@type":"error","code":420,"message":"FLOOD_WAIT_12"}""").single() as TdSession.Event.Error).retryAfter)
        assertNull((s.onUpdate("""{"@type":"error","code":400,"message":"PHONE_CODE_INVALID"}""").single() as TdSession.Event.Error).retryAfter)
        assertEquals("\"a\\u0001\\n\"", TdSession.q("a\u0001\n"))
        // A refused history page names itself (its tag), so it can be asked again after the wait.
        assertEquals("h:-1001:300", (s.onUpdate("""{"@type":"error","code":429,"message":"retry after 3","@extra":"h:-1001:300"}""").single() as TdSession.Event.Error).extra)
    }

    @Test fun anotherClientsAnswersAreNotOursAndClosingIsNotClosed() {
        val mine = TdSession(7) { sent += it }
        mine.start(params)
        assertEquals(emptyList(), mine.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateClosed"},"@client_id":6}"""))
        assertEquals(TdSession.Auth.STARTING, mine.auth)
        mine.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateReady"},"@client_id":7}""")
        assertEquals(TdSession.Auth.READY, mine.auth)
        mine.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateClosing"},"@client_id":7}""")
        assertEquals(TdSession.Auth.CLOSING, mine.auth)
        mine.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateClosed"},"@client_id":7}""")
        assertEquals(TdSession.Auth.CLOSED, mine.auth)
    }

    @Test fun aMessagesEditTimeIsKept() {
        login(); s.onUpdate(chat(-1001, "Sensex Kings", channelType)); s.selected = setOf(-1001)
        val page = """{"@type":"messages","total_count":1,"messages":[${msg(-1001, 9, "x", t0, edit = unix(t0.plusMinutes(4)))}],"@extra":"p:-1001"}"""
        val m = (s.onUpdate(page).single() as TdSession.Event.History).msgs.single()
        assertTrue(m.edited); assertEquals(t0.plusMinutes(4), m.editedAt)
    }

    // 16 Oct, the login fix (Boss: "Telegram login button not working").
    @Test fun theParametersGoOnceThoughTdlibAsksTwice() {
        val c = TdSession(clientId = 1) { sent += it }
        c.start(params)
        assertTrue(sent.last().contains("\"getAuthorizationState\""))
        // TDLib's update, then the answer to the getAuthorizationState kick (no "authorization_state" wrapper), with its client id.
        c.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitTdlibParameters"},"@client_id":1}""")
        c.onUpdate("""{"@type":"authorizationStateWaitTdlibParameters","@client_id":1}""")
        assertEquals(1, sent.count { it.contains("\"setTdlibParameters\"") })
        // Every field TDLib 1.8.67's setTdlibParameters takes, flat (no "parameters" object), the key as base64 bytes.
        val p = sent.single { it.contains("\"setTdlibParameters\"") }
        for (f in listOf("use_test_dc", "database_directory", "files_directory", "database_encryption_key", "use_file_database",
            "use_chat_info_database", "use_message_database", "use_secret_chats", "api_id", "api_hash", "system_language_code",
            "device_model", "system_version", "application_version")) assertTrue(p.contains("\"$f\":"), f)
        assertTrue(!p.contains("\"parameters\""))
        assertEquals(TdSession.Auth.STARTING, c.auth)
        // Another client's answer (a whole number, as TDLib's JSON gives it) changes nothing; ours does.
        assertEquals(emptyList(), c.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitPhoneNumber"},"@client_id":2}"""))
        assertEquals(TdSession.Auth.STARTING, c.auth)
        val e = c.onUpdate("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitPhoneNumber"},"@client_id":1}""")
        assertEquals(listOf<TdSession.Event>(TdSession.Event.AuthChanged(TdSession.Auth.NEED_PHONE)), e)
        // A restarted session sends them again.
        c.start(params)
        c.onUpdate("""{"@type":"authorizationStateWaitTdlibParameters","@client_id":1}""")
        assertEquals(2, sent.count { it.contains("\"setTdlibParameters\"") })
    }

    @Test fun aChannelIdBeyondThirtyTwoBitsAndAStringIdReadWhole() {
        login()
        val big = -1002_345_678_901L
        s.onUpdate(chat(big, "Big Channel", channelType))
        s.onUpdate("""{"@type":"updateNewChat","chat":{"@type":"chat","id":"-1009876543210","title":"Str","type":$channelType}}""")
        assertEquals(listOf(big, -1009876543210L), s.channels().map { it.id })
    }

    @Test fun tdlibsErrorsInPlainWords() {
        assertTrue(TdSession.errorWords("API_ID_INVALID").contains("api_id / api_hash are not valid"))
        assertTrue(TdSession.errorWords("Valid api_id must be provided. Can be obtained at https://my.telegram.org").contains("not valid"))
        assertTrue(TdSession.errorWords("PHONE_NUMBER_INVALID").contains("country code"))
        assertTrue(TdSession.errorWords("PHONE_NUMBER_BANNED").contains("banned"))
        assertTrue(TdSession.errorWords("PHONE_CODE_INVALID").contains("not right"))
        assertTrue(TdSession.errorWords("PHONE_CODE_EXPIRED").contains("expired"))
        assertTrue(TdSession.errorWords("PASSWORD_HASH_INVALID").contains("password is not right"))
        assertTrue(TdSession.errorWords("Wrong database encryption key").contains("could not be opened"))
        assertEquals("Telegram asks to wait 30 s before trying again.", TdSession.errorWords("Too Many Requests: retry after 30"))
        assertEquals("Telegram asks to wait 7 s before trying again.", TdSession.errorWords("FLOOD_WAIT_7"))
        assertEquals("Telegram asks to wait a while before trying again.", TdSession.errorWords("Too Many Requests"))
        assertEquals("Telegram: Something else", TdSession.errorWords("Something else"))
        assertEquals(210, TdSession.errorWords("x".repeat(500)).length)
    }
}
