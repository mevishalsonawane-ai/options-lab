package com.optionslab.engine.signals

import com.optionslab.engine.IST
import com.optionslab.engine.orb.MiniJson
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Signal tester's Telegram login, read-only (15 Oct 2026, Boss: "no notification reading - log in to Telegram in the app;
 * read only the channels I select"). The app runs TDLib, Telegram's official client library, through its JSON interface; this
 * is everything the app says to it and makes of what it answers. Pure: the app passes TDLib's JSON in ([onUpdate]) and sends
 * what [send] is given.
 *
 * Read-only by construction: every request goes through [request], which refuses any type not in [ALLOWED] - the login steps,
 * the chat list, a chat's history, log out and close. There is no way here to send a message, join or leave a chat, react,
 * open a chat or mark anything as read (viewMessages is never sent).
 *
 * Only the channels Boss selects ([selected]) are read: the chat list keeps channels' TITLES only (private chats, groups and
 * bots are not even listed); a new or edited message of any chat not selected is dropped as soon as its chat id is seen, before
 * its content is looked at - it never becomes an [Event], so it never reaches the reader, the store or a log. A chat's history
 * is fetched only for a selected chat, or the one chat Boss taps "Check this chat" on ([check]).
 *
 * Secrets: the api_id / api_hash and the database key come from the app's encrypted settings ([Params]) and go to TDLib only;
 * the phone number, the login code and the 2-step password go straight to TDLib and are never kept. TDLib's own log is switched
 * off ([start]) so it writes nothing to the phone's log.
 */
class TdSession(private val clientId: Int? = null, private val send: (String) -> Unit) {
    /** What TDLib needs to start: Boss's own api_id and api_hash (my.telegram.org), its folders, the database's key (base64). */
    data class Params(val apiId: Int, val apiHash: String, val dbDir: String, val filesDir: String, val dbKeyB64: String)

    enum class Auth(val words: String) {
        OFF("not started"), STARTING("starting"), NEED_KEYS("needs your api_id and api_hash"), NEED_PHONE("waiting for your phone number"),
        NEED_CODE("waiting for the login code Telegram sent you"), NEED_PASSWORD("waiting for your 2-step verification password"),
        READY("logged in (read-only)"), LOGGING_OUT("logging out"), CLOSING("closing"), CLOSED("closed"),
        UNSUPPORTED("this login step needs the Telegram app (sign up or e-mail) - finish it there, then try again"),
    }

    /** One channel in the list: its id and title (nothing else of it is kept). */
    data class Channel(val id: Long, val title: String)

    sealed class Event {
        /** A new message of a selected channel. */
        data class NewMessage(val chat: Channel, val msg: TelegramExport.Msg) : Event()
        /** A message of a selected channel edited at [at] (its first text is kept; only the edit's time is). */
        data class Edited(val chat: Channel, val msgId: Long, val at: LocalDateTime) : Event()
        /** A page of a selected (or checked) chat's history, oldest first; [next]: the message id to page on from, null when done. */
        data class History(val chat: Channel, val msgs: List<TelegramExport.Msg>, val next: Long?, val check: Boolean, val poll: Boolean = false) : Event()
        /** TDLib refused a request ([extra]: the request's tag, e.g. a history page's): [retryAfter] seconds when it asks to slow down. */
        data class Error(val code: Int, val message: String, val retryAfter: Int?, val extra: String? = null) : Event()
        data class AuthChanged(val auth: Auth) : Event()
    }

    @Volatile var auth: Auth = Auth.OFF
        private set
    /** Where the login code went ("the Telegram app", "SMS") or the 2-step password's hint - for the login dialog only. */
    @Volatile var hint: String? = null
        private set
    private val chats = LinkedHashMap<Long, String>()
    /** Boss's selected channels (the app sets them; nothing is selected at first). */
    @Volatile var selected: Set<Long> = emptySet()
    private var params: Params? = null

    /** The channels TDLib has listed, titles only, newest first as Telegram orders them. */
    fun channels(): List<Channel> = synchronized(chats) { chats.map { Channel(it.key, it.value) } }

    companion object {
        /** The only requests ever sent to TDLib (read-only, [request]). */
        val ALLOWED: Set<String> = setOf(
            "setLogVerbosityLevel", "setLogStream", "setTdlibParameters", "setAuthenticationPhoneNumber", "checkAuthenticationCode",
            "checkAuthenticationPassword", "resendAuthenticationCode", "loadChats", "getChatHistory", "logOut", "close", "getAuthorizationState",
        )
        /** History from this day (Boss: the back-test from 20 Nov 2024). */
        val HISTORY_FROM: LocalDate = LocalDate.of(2024, 11, 20)
        const val PAGE = 100
        const val POLL = 20

        private val HEX = "0123456789abcdef"
        /** [s] as a JSON string. */
        fun q(s: String): String = buildString(s.length + 2) {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n"); c == '\r' -> append("\\r"); c == '\t' -> append("\\t")
                c < ' ' -> { append("\\u00"); append(HEX[c.code shr 4]); append(HEX[c.code and 15]) }
                else -> append(c)
            }
            append('"')
        }

        @Suppress("UNCHECKED_CAST")
        private fun obj(x: Any?): Map<String, Any?>? = x as? Map<String, Any?>
        private fun long(x: Any?): Long? = (x as? Double)?.toLong() ?: (x as? String)?.toLongOrNull()
    }

    /** A request to TDLib; anything not in [ALLOWED] is refused here, whoever asks. */
    fun request(type: String, fields: String = "", extra: String? = null) {
        require(type in ALLOWED) { "not a read-only request: $type" }
        val body = StringBuilder("{\"@type\":").append(q(type))
        if (fields.isNotEmpty()) body.append(',').append(fields)
        extra?.let { body.append(",\"@extra\":").append(q(it)) }
        send(body.append('}').toString())
    }

    /** Started with [p] (null: no keys yet - the login asks for them). TDLib's log off; its parameters when it asks. */
    fun start(p: Params?) {
        params = p
        auth = if (p == null) Auth.NEED_KEYS else Auth.STARTING
        request("setLogStream", "\"log_stream\":{\"@type\":\"logStreamEmpty\"}")
        request("setLogVerbosityLevel", "\"new_verbosity_level\":0")
        if (p != null) request("getAuthorizationState")
    }

    fun phone(number: String) = request("setAuthenticationPhoneNumber", "\"phone_number\":${q(number.filter { it.isDigit() || it == '+' })}")
    fun code(code: String) = request("checkAuthenticationCode", "\"code\":${q(code.trim())}")
    fun password(pw: String) = request("checkAuthenticationPassword", "\"password\":${q(pw)}")
    fun logOut() { auth = Auth.LOGGING_OUT; request("logOut") }
    fun close() = request("close")
    /** The main chat list (TDLib then sends each chat as an update; only channels are kept, by title). */
    fun loadChats(limit: Int = 200) = request("loadChats", "\"chat_list\":{\"@type\":\"chatListMain\"},\"limit\":$limit")

    /**
     * A page of the selected channel [id]'s history from [fromId] (0: the newest) backwards; [check]: the one chat Boss tapped
     * "Check this chat" on (one page). Refused for a chat that is neither selected nor checked, or not a listed channel.
     */
    fun history(id: Long, fromId: Long = 0, check: Boolean = false) {
        require(check || id in selected) { "history only of a selected channel" }
        require(synchronized(chats) { id in chats }) { "not a listed channel" }
        request("getChatHistory", "\"chat_id\":$id,\"from_message_id\":$fromId,\"offset\":0,\"limit\":$PAGE,\"only_local\":false",
            extra = (if (check) "c:" else "h:") + id + ":" + fromId)
    }

    /**
     * The selected channel [id]'s newest [POLL] messages (the capture's round looks every minute, so a post the live updates
     * missed - Telegram may not push a large channel's posts to a client that has not opened it - is still read; the tester drops
     * a message it already has by its id).
     */
    fun poll(id: Long) {
        require(id in selected) { "only a selected channel is read" }
        require(synchronized(chats) { id in chats }) { "not a listed channel" }
        request("getChatHistory", "\"chat_id\":$id,\"from_message_id\":0,\"offset\":0,\"limit\":$POLL,\"only_local\":false", extra = "p:$id")
    }

    private fun setAuth(a: Auth, out: MutableList<Event>) { if (auth != a) { auth = a; out += Event.AuthChanged(a) } }

    /** One of TDLib's answers or updates read: what it means for the tester (often nothing). */
    fun onUpdate(json: String): List<Event> {
        val o = obj(runCatching { MiniJson.parse(json) }.getOrNull()) ?: return emptyList()
        // TDLib's receive is shared by every client of the process: another (an earlier, closing) client's answer is not ours.
        if (clientId != null) (o["@client_id"] as? Double)?.toInt()?.let { if (it != clientId) return emptyList() }
        val out = ArrayList<Event>()
        when (o["@type"]) {
            "updateAuthorizationState" -> authState(obj(o["authorization_state"]), out)
            "authorizationStateWaitTdlibParameters", "authorizationStateWaitPhoneNumber", "authorizationStateWaitCode",
            "authorizationStateWaitPassword", "authorizationStateReady", "authorizationStateClosed", "authorizationStateClosing",
            "authorizationStateLoggingOut" -> authState(o, out)
            "updateNewChat" -> obj(o["chat"])?.let { c ->
                val type = obj(c["type"])
                // Channels only: a private chat, a group, a bot or a secret chat is not listed, not even its title.
                if (type?.get("@type") == "chatTypeSupergroup" && type["is_channel"] == true) {
                    val id = long(c["id"]) ?: return@let
                    synchronized(chats) { chats[id] = (c["title"] as? String).orEmpty().take(120) }
                }
            }
            "updateChatTitle" -> long(o["chat_id"])?.let { id -> synchronized(chats) { if (id in chats) chats[id] = (o["title"] as? String).orEmpty().take(120) } }
            "updateNewMessage" -> obj(o["message"])?.let { m ->
                // The chat id first: a chat not selected is dropped here, before anything else of the message is read.
                val id = long(m["chat_id"]) ?: return@let
                if (id !in selected) return@let
                val ch = channel(id) ?: return@let
                message(m)?.let { out += Event.NewMessage(ch, it) }
            }
            "updateMessageEdited" -> {
                val id = long(o["chat_id"])
                if (id != null && id in selected) {
                    val ch = channel(id)
                    val mid = long(o["message_id"]); val at = long(o["edit_date"])
                    if (ch != null && mid != null && at != null && at > 0) out += Event.Edited(ch, mid, time(at))
                }
            }
            "messages" -> {
                val extra = o["@extra"] as? String ?: return emptyList()
                val check = extra.startsWith("c:")
                val poll = extra.startsWith("p:")
                val id = extra.substringAfter(':').substringBefore(':').toLongOrNull() ?: return emptyList()
                val from = extra.substringAfter(':').substringAfter(':', "").toLongOrNull() ?: 0L
                if (!check && id !in selected) return emptyList()
                val ch = channel(id) ?: return emptyList()
                val list = (o["messages"] as? List<*>).orEmpty().mapNotNull { obj(it) }
                // TDLib gives the newest first; a page reaching back before the first day is the last.
                val msgs = list.mapNotNull { message(it) }.filter { !it.at.toLocalDate().isBefore(HISTORY_FROM) }.sortedBy { it.at }
                // A page that gives back only where it started from (or nothing older) is the last.
                val oldest = list.mapNotNull { long(it["id"]) }.minOrNull()?.takeIf { from == 0L || it < from }
                val reachedStart = list.any { m -> long(m["date"])?.let { time(it).toLocalDate().isBefore(HISTORY_FROM) } == true }
                out += Event.History(ch, msgs, if (check || poll || list.isEmpty() || reachedStart || oldest == null) null else oldest, check, poll)
            }
            "error" -> {
                val code = (o["code"] as? Double)?.toInt() ?: 0
                val msg = (o["message"] as? String).orEmpty().take(200)
                val retry = Regex("(?:retry after|FLOOD_WAIT_)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(msg)?.groupValues?.get(1)?.toIntOrNull()
                out += Event.Error(code, msg, retry, o["@extra"] as? String)
            }
        }
        return out
    }

    private fun channel(id: Long): Channel? = synchronized(chats) { chats[id]?.let { Channel(id, it) } }

    private fun time(unix: Long): LocalDateTime = Instant.ofEpochSecond(unix).atZone(IST).toLocalDateTime()

    /** A message's time, text (a text message, or a picture's or file's caption), id and the message it replies to. */
    private fun message(m: Map<String, Any?>): TelegramExport.Msg? {
        val c = obj(m["content"]) ?: return null
        val text = ((obj(c["text"]) ?: obj(c["caption"]))?.get("text") as? String)?.trim().orEmpty()
        if (text.isEmpty()) return null
        val at = long(m["date"])?.let { time(it) } ?: return null
        val reply = obj(m["reply_to"])?.takeIf { it["@type"] == "messageReplyToMessage" }?.let { long(it["message_id"]) }
        val edit = long(m["edit_date"])?.takeIf { it > 0 }?.let { time(it) }
        return TelegramExport.Msg(at, text.take(4000), edit != null, long(m["id"]), reply, edit)
    }

    private fun authState(s: Map<String, Any?>?, out: MutableList<Event>) {
        when (s?.get("@type")) {
            "authorizationStateWaitTdlibParameters" -> {
                val p = params
                if (p == null) { setAuth(Auth.NEED_KEYS, out); return }
                request("setTdlibParameters", "\"use_test_dc\":false,\"database_directory\":${q(p.dbDir)},\"files_directory\":${q(p.filesDir)}," +
                    "\"database_encryption_key\":${q(p.dbKeyB64)},\"use_file_database\":false,\"use_chat_info_database\":true," +
                    "\"use_message_database\":false,\"use_secret_chats\":false,\"api_id\":${p.apiId},\"api_hash\":${q(p.apiHash)}," +
                    "\"system_language_code\":\"en\",\"device_model\":\"Android\",\"system_version\":\"\",\"application_version\":\"IraAlgo\"")
                setAuth(Auth.STARTING, out)
            }
            "authorizationStateWaitPhoneNumber" -> { hint = null; setAuth(Auth.NEED_PHONE, out) }
            "authorizationStateWaitCode" -> {
                hint = when (obj(obj(s["code_info"])?.get("type"))?.get("@type")) {
                    "authenticationCodeTypeTelegramMessage" -> "in the Telegram app"
                    "authenticationCodeTypeSms", "authenticationCodeTypeSmsWord", "authenticationCodeTypeSmsPhrase" -> "by SMS"
                    "authenticationCodeTypeCall", "authenticationCodeTypeFlashCall", "authenticationCodeTypeMissedCall" -> "by a call"
                    else -> null
                }
                setAuth(Auth.NEED_CODE, out)
            }
            "authorizationStateWaitPassword" -> { hint = (s["password_hint"] as? String)?.take(60)?.ifEmpty { null }; setAuth(Auth.NEED_PASSWORD, out) }
            "authorizationStateReady" -> { hint = null; setAuth(Auth.READY, out) }
            "authorizationStateLoggingOut" -> setAuth(Auth.LOGGING_OUT, out)
            // Closing is not closed: the client's files are still in use until TDLib says it is closed.
            "authorizationStateClosing" -> setAuth(Auth.CLOSING, out)
            "authorizationStateClosed" -> { synchronized(chats) { chats.clear() }; setAuth(Auth.CLOSED, out) }
            null -> Unit
            else -> setAuth(Auth.UNSUPPORTED, out)
        }
    }
}
