package com.optionslab.app.data

import com.optionslab.engine.signals.TelegramExport

/**
 * Where the Signal tester's Telegram messages come from (15 Oct 2026; [SignalTesterHost]). Boss: "Telegram should only read from
 * the particular channel" - so a source hands out a chat's messages only when asked for that one chat ([messages]); the picker
 * gets titles only ([titles]). The tester asks only for the chats Boss selected (or the one chat he taps "Check this chat" on):
 * every other chat's messages are never asked for, so they never reach the reader or the store.
 *
 * Working sources: a Telegram Desktop export ([ExportSource]); Boss's paste box and the Share target push single messages
 * straight in. A Telegram login (TDLib, Telegram's own client library, read-only) would be one more [MessageSource] - not in this
 * change: see android/README.md, "Signal tester". No notification reading (Boss, 15 Oct).
 */
interface MessageSource {
    /** A short key ("export"), kept beside each chat title seen. */
    val key: String
    /** The titles of the chats this source can see - titles only, never a message. */
    suspend fun titles(): List<String>
    /** The messages of the one chat [title] (oldest first), each with its own time; empty when the source has no such chat. */
    suspend fun messages(title: String): List<TelegramExport.Msg>
}

/**
 * A Telegram Desktop export (File → Export chat history → JSON, result.json), held in memory only while the picker is open -
 * never stored as it is. Exporting just the channel (not the whole account) keeps other chats off the phone altogether.
 */
class ExportSource private constructor(private val read: List<TelegramExport.Chat>) : MessageSource {
    override val key: String = "export"
    override suspend fun titles(): List<String> = read.map { it.name }
    override suspend fun messages(title: String): List<TelegramExport.Msg> = read.firstOrNull { it.name == title }?.messages.orEmpty()

    companion object {
        /** The export in [json]; throws [TelegramExport.Bad] when it is not one. */
        fun of(json: String): ExportSource = ExportSource(TelegramExport.read(json))
    }
}
