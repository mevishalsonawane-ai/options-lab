package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Which of the on-device model's memory slots a prompt goes to (Boss, 4 Oct: "it takes a lot of time thinking").
 * Reading a prompt's fixed instructions is the main cost on a phone's CPU, and the model keeps what it has read only
 * for the prompt sent before. One free-form question asks it twice - which command (Intents), then a chat line (Chat) -
 * so each prompt threw the other's instructions away and every question read both again in full. Each kind of prompt
 * now keeps its own slot: a prompt goes to the slot whose last prompt shares the longest beginning with it, or, when
 * none shares at least [minShared] characters, to an empty slot or the one unused longest. Only where text is read
 * changes: what the model writes, and every check of it, stays the same. Pure; not thread-safe (the model's lock).
 */
class PromptSlots(val size: Int = 4, val minShared: Int = 64) {
    private val last = arrayOfNulls<String>(size)
    private val used = LongArray(size)
    private var clock = 0L

    /** The slot for [prompt] (0 until [size]), remembered as that slot's last prompt. */
    fun pick(prompt: String): Int {
        var best = -1
        var shared = 0
        for (i in 0 until size) {
            val n = last[i]?.let { common(it, prompt) } ?: continue
            if (n > shared) { shared = n; best = i }
        }
        val slot = if (best >= 0 && shared >= minShared) best
            else (0 until size).firstOrNull { last[it] == null } ?: (0 until size).minByOrNull { used[it] }!!
        last[slot] = prompt
        used[slot] = ++clock
        return slot
    }

    /** Whether a slot already holds [prefix] (its last prompt begins with it): warming it again would be work for nothing. */
    fun holds(prefix: String): Boolean = last.any { it != null && it.startsWith(prefix) }

    /** The model left memory (or was switched): every slot is empty again. */
    fun clear() { last.fill(null); used.fill(0L); clock = 0L }

    private fun common(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}

/**
 * The fixed beginnings of the prompts a free-form question sends (which command - [Intents]; a chat line - [Chat]),
 * read into the model's slots right after it loads, so the first question no longer pays for them (a few hundred words
 * of fixed instructions and list, the main cost on a phone's CPU). Each is cut at a ":" just before the first word that
 * changes, so the question's prompt finds it already read and only its own words are new (the runner compares tokens,
 * so a different split there costs only the last word read again). Only what is read ahead changes: what
 * the model writes, and every check of it, stays the same. Pure.
 */
object PromptWarm {
    fun prefixes(now: LocalDateTime): List<String> = listOf(
        Intents.prompt("").substringBefore("\nREQUEST:") + "\nREQUEST:",
        Chat.prompt("", now).substringBefore("\n\nBoss:") + "\n\nBoss:",
    )
}
