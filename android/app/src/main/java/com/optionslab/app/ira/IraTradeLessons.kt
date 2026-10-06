package com.optionslab.app.ira

import com.optionslab.ira.BotTrades
import com.optionslab.ira.TradeLesson

/**
 * Jarvis on each closed Liquidity 15+5 trade against its research ([TradeLesson], Boss, 06 Oct 2026): once a trade
 * closes, one line in the chat - whether it was a normal win or loss for the arm and what kind - so Boss learns what a
 * normal win or loss looks like. In the chat only (never spoken, no pop-up); on short answers or while saving battery only
 * its first sentence. Told once per closed trade (remembered across a restart for the day). The same lesson rides on the
 * bot-trades answer ([BotTrades.answer]) and the 15:35 wrap-up has its one line for the day ([wrapLine]). Facts only:
 * nothing here arms, stops, places or closes anything. Not in IraGoldAlgo (no Liquidity arm).
 */
internal object IraTradeLessons {
    /** Told this run when no context is at hand to remember it across a restart. */
    private val toldHere = HashSet<String>()

    @Synchronized private fun first(day: java.time.LocalDate, key: String): Boolean {
        val ctx = IraHub.appContext()
        if (ctx != null) return com.optionslab.app.work.LiquidityNotices.once(ctx, day, key)
        return toldHere.add("$day|$key")
    }

    /**
     * The Liquidity 15+5 paper positions entered on [day] and closed (the arm's own book; reads only). The arms' lock may
     * be held by their tick across a network read: a short wait, else null (this round says nothing; the next one will).
     */
    private suspend fun closedToday(day: java.time.LocalDate): List<com.optionslab.app.data.OrbArms.Position>? =
        kotlinx.coroutines.withTimeoutOrNull(3_000) {
            runCatching { com.optionslab.app.data.OrbArms.liquidityToday(day) }.getOrNull()
        }?.filter { !it.open && !it.unconfirmed && it.exitTime != null }

    /**
     * The lines not yet said: one for each Liquidity trade closed today that has not had its lesson (each then marked
     * told). [brief]: the first sentence alone.
     */
    internal suspend fun unsaid(brief: Boolean): List<String> {
        val day = com.optionslab.app.data.Market.today()
        val closed = closedToday(day) ?: return emptyList()
        return closed.filter { it.exitTime!!.toLocalDate() == day }.mapNotNull { p ->
            val l = BotTrades.lesson(IraBots.tradeOf(p)) ?: return@mapNotNull null
            if (!first(day, "${p.arm}|${p.symbol}|${p.entryTime}|lesson|${p.exitTime}")) return@mapNotNull null
            "${BotTrades.label(p.arm)} trade closed - ${if (brief) l.short else l.text}"
        }
    }

    /** Each round of the words lane: the closed trades' lessons, all in one chat note (one line a trade). */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        val ctx = IraHub.appContext()
        val brief = IraTools.brief || runCatching { com.optionslab.app.work.Battery.saving(ctx) }.getOrDefault(false)
        val lines = runCatching { unsaid(brief) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return
        IraHub.note(lines.joinToString("\n"))
    }

    /** The wrap-up's line on today's Liquidity trades in research terms, or null when none closed today. */
    suspend fun wrapLine(): String? {
        if (com.optionslab.app.BuildConfig.GOLD) return null
        val day = com.optionslab.app.data.Market.today()
        val closed = closedToday(day) ?: return null
        val lessons = closed.filter { it.exitTime!!.toLocalDate() == day }.mapNotNull { BotTrades.lesson(IraBots.tradeOf(it)) }
        return TradeLesson.day(lessons)
    }
}
