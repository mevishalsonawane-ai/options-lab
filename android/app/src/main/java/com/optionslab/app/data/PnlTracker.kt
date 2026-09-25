package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import org.json.JSONArray

/**
 * Today's account mark-to-market, one sample a minute, as the PC's P&L
 * tracker draws it. Your P&L is private, so the series lives in the
 * encrypted vault like everything else about the account, and is dropped
 * when the day changes.
 */
object PnlTracker {
    private const val K_DAY = "pnl.day"
    private const val K_SERIES = "pnl.series"
    private const val MAX = 480

    /** (minute of the IST day, total P&L) samples, oldest first. */
    data class Point(val minute: Int, val pnl: Double)

    @Synchronized
    fun today(): List<Point> {
        if (SecurePrefs.getString(K_DAY) != Market.today().toString()) return emptyList()
        val a = runCatching { JSONArray(SecurePrefs.getString(K_SERIES) ?: "[]") }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONArray(it) }.map { Point(it.getInt(0), it.getDouble(1)) }
    }

    /** Record [pnl] for the current minute (the latest sample in a minute wins). */
    @Synchronized
    fun record(pnl: Double): List<Point> {
        val now = Market.now()
        val minute = now.hour * 60 + now.minute
        val pts = today().filter { it.minute != minute }.toMutableList()
        pts += Point(minute, Math.round(pnl * 100) / 100.0)
        val kept = pts.sortedBy { it.minute }.takeLast(MAX)
        val a = JSONArray()
        kept.forEach { a.put(JSONArray().put(it.minute).put(it.pnl)) }
        SecurePrefs.putAll(mapOf(K_DAY to Market.today().toString(), K_SERIES to a.toString()))
        return kept
    }

    fun clear() = SecurePrefs.putAll(mapOf(K_DAY to null, K_SERIES to null))
}
