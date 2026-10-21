package com.optionslab.engine.orb

/**
 * The arms pass's index reads (13 Oct audit fix). Before, the pass read BANKNIFTY's candles once and, when that read failed,
 * gave EVERY book "no_index_data" - the FINNIFTY and MIDCPNIFTY Liquidity books too, though they decide on their own index.
 * Now each index is read at most once per pass ([Reads], shared by every book on it), and a failed read stops only the books
 * on that index, with the index named ("no_index_data: MIDCPNIFTY"). Data plumbing only: no book's rules change.
 */
object IndexData {
    /** The verdict of a book whose index could not be read (the plain form: saved before 13 Oct, BANKNIFTY's). */
    const val NONE = "no_index_data"
    private const val NAMED = "$NONE: "

    /** The verdict of a book on [index] when [index]'s candles could not be read this pass. */
    fun none(index: String): String = NAMED + index

    /** True for a no-data verdict, named or not. */
    fun isNone(verdict: String?): Boolean = verdict == NONE || verdict?.startsWith(NAMED) == true

    /** The index a no-data verdict names: BANKNIFTY for the plain one (the only index it was ever given for), else null. */
    fun indexOf(verdict: String?): String? = when {
        verdict == NONE -> OrbRules.UNDERLYING
        verdict?.startsWith(NAMED) == true -> verdict.removePrefix(NAMED).ifBlank { null }
        else -> null
    }

    /**
     * The index a book decides on: none for Hero (it reads its own NIFTY data elsewhere), its own for a Liquidity book
     * ([liquidityIndex]), BANKNIFTY for the ORB family (ORB, Sweep, Range Fade).
     */
    fun bookIndex(hero: Boolean, liquidityIndex: String?): String? = if (hero) null else liquidityIndex ?: OrbRules.UNDERLYING

    /** [index]'s no-data verdict when its read failed in [reads] this pass, else null (the book decides as before). */
    fun gate(index: String?, reads: Reads<*>): String? = index?.takeIf { reads.failed(it) }?.let(::none)

    /**
     * One pass's reads: each index fetched at most once, its candles (or its failure) shared by every later look in the
     * pass. One per pass, used by one thread under the arms' lock; never kept past the pass. Reads nothing by itself.
     */
    class Reads<T> {
        @PublishedApi internal val got = HashMap<String, Result<T>>()

        /** [index]'s candles: fetched by [fetch] on the first look, the same answer after. Null when the fetch failed. */
        inline fun read(index: String, fetch: () -> T): T? = got.getOrPut(index) { runCatching { fetch() } }.getOrNull()

        /** As [read], but a failed fetch throws its error again (on every look, without fetching again). */
        inline fun readOrThrow(index: String, fetch: () -> T): T = got.getOrPut(index) { runCatching { fetch() } }.getOrThrow()

        /** True once [index] was fetched this pass and the fetch failed. */
        fun failed(index: String): Boolean = got[index]?.isFailure == true

        /** The indices fetched this pass (each once). */
        val fetched: Set<String> get() = got.keys
    }
}
