package com.optionslab.ira

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

    /** The model left memory (or was switched): every slot is empty again. */
    fun clear() { last.fill(null); used.fill(0L); clock = 0L }

    private fun common(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
