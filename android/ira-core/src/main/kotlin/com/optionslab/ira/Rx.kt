package com.optionslab.ira

import java.util.concurrent.ConcurrentHashMap

/**
 * A pattern written in place ("rx(\" (buy|sell) \")") compiled once and kept (2026-10-05): every question went through
 * dozens of these, each compiled afresh at every reading - a command check alone took about 0.15 ms on a desktop, several
 * times that on a phone, and one spoken question is checked several times. The pattern is the same text, so it matches
 * exactly as before. Only for patterns fixed in the code (never built from what Boss says): at most [KEPT] are kept.
 */
internal object Rx {
    const val KEPT = 1024
    private val kept = ConcurrentHashMap<String, Regex>()

    fun of(pattern: String): Regex = kept[pattern] ?: Regex(pattern).also { if (kept.size < KEPT) kept.putIfAbsent(pattern, it) }
}

/** [pattern] compiled once ([Rx]). */
internal fun rx(pattern: String): Regex = Rx.of(pattern)
