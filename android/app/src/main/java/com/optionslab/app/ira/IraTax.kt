package com.optionslab.app.ira

import android.content.Context
import android.content.Intent

/**
 * Boss's tax-year records read for Jarvis ([com.optionslab.ira.TaxRecords]; usefulness round 11): the financial year's
 * F&O turnover (an estimate, to confirm with his CA), realised P&L by month, charges and trades, from the app's own trade
 * books; and, on his yes on an unlocked phone, the year's trades in a CSV handed to Android's share sheet. Reads only -
 * nothing here places, changes or closes anything. The CSV holds dates, symbols, quantities, values, P&L and charges:
 * never an order ID, an account ID or a key; nothing of it is logged.
 */
internal object IraTax {
    /** Where the CSV is written: the app's own cache, shared only through [AUTHORITY_SUFFIX]'s FileProvider. */
    private const val DIR = "exports"
    private const val AUTHORITY_SUFFIX = ".exports"

    private fun trades(live: Boolean): List<com.optionslab.ira.TaxRecords.Trade> =
        runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList()).map { t ->
            com.optionslab.ira.TaxRecords.trade(t.symbol, t.direction, t.qty, t.entry, t.exit, t.openedAt, t.closedAt, t.charges)
        }

    /** The year's facts for the chat (the account section; the hub keeps it off a locked phone). */
    fun lines(question: String, today: java.time.LocalDate): List<String> {
        val fy = com.optionslab.ira.TaxRecords.fy(question, today)
        val z = trades(true).takeIf { it.isNotEmpty() }
        return com.optionslab.ira.TaxRecords.lines(z, trades(false), fy, today)
    }

    /** What would be exported for [fy]: Zerodha's trades when the app recorded any that year, else the paper account's. */
    fun pick(fy: com.optionslab.ira.TaxRecords.Fy, today: java.time.LocalDate): Pair<String, List<com.optionslab.ira.TaxRecords.Trade>> {
        val z = com.optionslab.ira.TaxRecords.inYear(trades(true), fy, today)
        return if (z.isNotEmpty()) "Zerodha" to z else "Paper" to com.optionslab.ira.TaxRecords.inYear(trades(false), fy, today)
    }

    /**
     * Writes [fy]'s trades to a CSV in the app's cache (older exports removed) and opens the share sheet on it. Called only
     * after Boss's yes; the caller checks the phone is unlocked.
     */
    suspend fun export(c: Context, fy: com.optionslab.ira.TaxRecords.Fy, today: java.time.LocalDate): String {
        val (label, w) = pick(fy, today)
        if (w.isEmpty()) return com.optionslab.ira.TaxRecords.nothing(fy)
        val text = com.optionslab.ira.TaxRecords.csv(label, w, fy, today)
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(c.cacheDir, DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            java.io.File(dir, com.optionslab.ira.TaxRecords.fileName(fy, label)).apply { writeText(text, Charsets.UTF_8) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + AUTHORITY_SUFFIX, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Trades ${fy.label}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share your ${fy.label} trades").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { c.startActivity(chooser) }
        return com.optionslab.ira.TaxRecords.done(label, w.size, fy)
    }
}
