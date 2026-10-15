package com.optionslab.engine.signals

import com.optionslab.engine.IST
import com.optionslab.engine.orb.MiniJson
import java.time.Instant
import java.time.LocalDateTime

/**
 * A Telegram Desktop chat export (File -> Export chat history -> JSON, `result.json`) read for the Signal tester's back-test of
 * past calls (15 Oct 2026). Only what the tester needs leaves this reader: the chat's name and, per message, its time (IST, to
 * the second: `date_unixtime` when present, else `date`), its text (plain, hashtags and bold joined back) and whether it was
 * edited later (its first text is then unknown, so it is not scored). Service messages, media without text and everything else
 * in the file (ids, senders, reactions, files) are ignored; the caller keeps only the parsed calls, never the raw chat. A whole-
 * account export ("chats": {"list": [...]}) gives each chat. Pure.
 */
object TelegramExport {
    data class Msg(val at: LocalDateTime, val text: String, val edited: Boolean)
    data class Chat(val name: String, val messages: List<Msg>)

    class Bad(msg: String) : IllegalArgumentException(msg)

    /** The chats in [json] (one for a single chat's export). Throws [Bad] when it is not a Telegram export. */
    fun read(json: String): List<Chat> {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { MiniJson.parse(json) as? Map<String, Any?> }.getOrNull() ?: throw Bad("This is not a Telegram export (result.json).")
        val list = ((o["chats"] as? Map<*, *>)?.get("list") as? List<*>)?.mapNotNull { it as? Map<*, *> }
        val chats = list ?: if (o["messages"] is List<*>) listOf(o) else throw Bad("No messages in this file: export the chat as JSON from Telegram Desktop.")
        return chats.map { c -> Chat((c["name"] as? String)?.trim().orEmpty().ifEmpty { "(no name)" }, messages(c["messages"] as? List<*>)) }
    }

    private fun messages(list: List<*>?): List<Msg> = list.orEmpty().mapNotNull { m ->
        val x = m as? Map<*, *> ?: return@mapNotNull null
        if (x["type"] != "message") return@mapNotNull null
        val text = text(x["text"]).trim()
        if (text.isEmpty()) return@mapNotNull null
        val at = time(x["date_unixtime"], x["date"]) ?: return@mapNotNull null
        Msg(at, text, x["edited"] != null || x["edited_unixtime"] != null)
    }

    private fun time(unix: Any?, date: Any?): LocalDateTime? {
        val s = (unix as? String)?.toLongOrNull() ?: (unix as? Double)?.toLong()
        if (s != null) return Instant.ofEpochSecond(s).atZone(IST).toLocalDateTime()
        return (date as? String)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    }

    /** A message's text: a string, or a list of strings and {"type": ..., "text": ...} pieces. */
    private fun text(t: Any?): String = when (t) {
        is String -> t
        is List<*> -> t.joinToString("") { p -> (p as? String) ?: ((p as? Map<*, *>)?.get("text") as? String).orEmpty() }
        else -> ""
    }
}
