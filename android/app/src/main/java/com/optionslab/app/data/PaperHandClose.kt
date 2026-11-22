package com.optionslab.app.data

/**
 * 22 Nov 2026 (B6 review): Boss's own close of one paper row - Trade's Close ([com.optionslab.app.ui.AppModel.paperClose]), or Jarvis
 * closing one paper position Boss names (by name, number or nickname) on his word. CLAUDE.md (PAPER ENDS AND LIVE COPIES): only Boss's own
 * paper reset or hand close leaves a live copy running, and only an NSE intraday (MIS) copy, which keeps its own Zerodha stop, its rule
 * exits and the 15:15 square-off; a carried (NRML: Night) or MCX copy is sold with the paper ([ArmLive.detachPaperSymbol]). Jarvis's
 * close went straight to [Paper.close], so the arm booked "closed elsewhere" and its live copy was sold. The shade's "Close position"
 * stays a plain [Paper.close] (the copy sold with it): the app is usually closed then, and these words would reach no screen.
 * A worker thread (it reads and writes the arms' book), never the tick path.
 */
object PaperHandClose {
    /**
     * Closes [symbol] [product] on paper as Boss's own close ([why]: the diary's words, "paper row closed by hand"): its live copies with
     * their own square-off stop following it first; a close that did not go (it threw, or the row is still held) gives them back to
     * their paper trade. The answer names every live copy and what happens to it ([ArmLive.liveCopyWords]). Throws as [Paper.close] does.
     * 22 Nov 2026 (B6 review): [asked] - the live copies ([copiesNow]) Boss was told of when he asked (Jarvis's confirm can come minutes
     * later): a copy open now that he was not told of (the arm entered the contract again) closes nothing - [CHANGED] - so a copy he was
     * never told of is never left running by it; nor does a moment the arms' book cannot be read ([UNREAD]). One he was told of that has
     * closed since leaves nothing behind: the close goes.
     */
    suspend fun close(symbol: String, product: String, why: String, asked: Set<String>? = null): Paper.Result {
        // (The words name every live copy of the row - one with its own 15:15 square-off stays open, a carried or MCX one is sold.)
        val open = if (asked == null) { runCatching { ArmLive.refresh() }; runCatching { ArmLive.liveCopiesOpen(symbol, product) }.getOrDefault(emptyList()) }
            else copiesNow(symbol, product) ?: return Paper.Result(false, UNREAD, emptyList())
        if (asked != null && !asked.containsAll(open.map { it.ref })) return Paper.Result(false, CHANGED, emptyList())
        val copies = runCatching { ArmLive.detachPaperSymbol(symbol, why, product) }.getOrDefault(emptyList())
        val words = ArmLive.liveCopyWords(open)
        suspend fun giveBack() = copies.forEach { runCatching { ArmLive.undetach(it.ref) } }
        // (A live copy that opened between that read and the detach - never told of - takes it all back: nothing closed. Shadow trades
        // send nothing to Zerodha and are never said.)
        if (asked != null && !asked.containsAll(copies.filter { !it.shadow }.map { it.ref })) { giveBack(); return Paper.Result(false, CHANGED, emptyList()) }
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

    /**
     * 22 Nov 2026 (B6 review): the live copies of [symbol] [product] open now, read once for a confirm - its words ([ArmLive.liveCopyWords])
     * and the copies it names (for [close]'s check) from the same read; null when the arms' book has not been read or cannot be read now.
     * A worker thread.
     */
    suspend fun copiesNow(symbol: String, product: String): List<ArmLive.Pos>? {
        val v = runCatching { ArmLive.refresh() }.getOrNull() ?: return null
        if (!v.loaded || v.unreadable) return null
        return runCatching { ArmLive.liveCopiesOpen(symbol, product) }.getOrNull()
    }

    /** 22 Nov 2026 (B6 review): what [close] says when a live copy opened since Boss was asked: nothing closed. */
    const val CHANGED = "The paper position's live copies at Zerodha changed since you asked, Boss, so I left it open. Ask again to hear what " +
        "closing it does now."
    /** 22 Nov 2026 (B6 review): what is said when the arms' live book cannot be read for a confirm: nothing closed. */
    const val UNREAD = "I could not read the arms' live copies at Zerodha just now, Boss, so I left the paper position open. Ask again in a " +
        "moment, or close it in Trade."
}
