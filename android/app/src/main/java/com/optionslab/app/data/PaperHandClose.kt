package com.optionslab.app.data

/**
 * 22 Nov 2026 (B6 review): Boss's own close of one paper row - Trade's Close ([com.optionslab.app.ui.AppModel.paperClose]), the shade's
 * "Close position" on a paper card, or Jarvis closing one named paper position on Boss's word. CLAUDE.md (PAPER ENDS AND LIVE COPIES):
 * only Boss's own paper reset or hand close leaves a live copy running, and only an NSE intraday (MIS) copy, which keeps its own Zerodha
 * stop, its rule exits and the 15:15 square-off; a carried (NRML: Night) or MCX copy is sold with the paper ([ArmLive.detachPaperSymbol]).
 * Jarvis's and the shade's close went straight to [Paper.close], so the arm booked "closed elsewhere" and its live copy was sold.
 * A worker thread (it reads and writes the arms' book), never the tick path.
 */
object PaperHandClose {
    /**
     * Closes [symbol] [product] on paper as Boss's own close ([why]: the diary's words, "paper row closed by hand"): its live copies with
     * their own square-off stop following it first; a close that did not go (it threw, or the row is still held) gives them back to
     * their paper trade. The answer names every live copy and what happens to it ([ArmLive.liveCopyWords]). Throws as [Paper.close] does.
     */
    suspend fun close(symbol: String, product: String, why: String): Paper.Result {
        runCatching { ArmLive.refresh() }
        // (The words name every live copy of the row - one with its own 15:15 square-off stays open, a carried or MCX one is sold.)
        val open = runCatching { ArmLive.liveCopiesOpen(symbol, product) }.getOrDefault(emptyList())
        val copies = runCatching { ArmLive.detachPaperSymbol(symbol, why, product) }.getOrDefault(emptyList())
        val words = ArmLive.liveCopyWords(open)
        suspend fun giveBack() = copies.forEach { runCatching { ArmLive.undetach(it.ref) } }
        // Closing is an exit: nothing stops it, the kill switch included (it only refuses new entries).
        val r = try { Paper.close(symbol, product) } catch (e: Exception) { giveBack(); throw e }
        if (!r.ok && runCatching { Paper.state.positions.any { it.symbol == symbol && it.product == product && it.quantity != 0 } }.getOrDefault(true)) {
            giveBack()
            return r
        }
        // (Closed: the live copies' mark written now, so a kill before the next look never sells them.)
        if (copies.isNotEmpty()) runCatching { ArmLive.applyDetached() }
        return if (words == null) r else r.copy(message = "${r.message} $words")
    }

    /** What [close] would do to [symbol] [product]'s live copies, for a confirm before it (null: none open). A worker thread. */
    suspend fun words(symbol: String, product: String): String? {
        runCatching { ArmLive.refresh() }
        return runCatching { ArmLive.liveCopyWords(ArmLive.liveCopiesOpen(symbol, product)) }.getOrNull()
    }
}
