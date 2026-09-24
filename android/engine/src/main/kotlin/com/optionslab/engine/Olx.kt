package com.optionslab.engine

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** CE / PE are option rights; IX is the underlying index itself. */
enum class Right { CE, PE, IX }

/**
 * One contract's bars for one session, column-oriented.
 *
 * Contract identity is (underlying, expiry, strike, right) - never a broker
 * token, which Upstox recycles once a contract settles.
 */
class Series(
    val expiry: LocalDate?,
    val strike: Double,
    val right: Right,
    val lot: Int,
    val minutes: IntArray,          // IST minute of day, ascending
    val close: DoubleArray,
    val open: DoubleArray?,
    val high: DoubleArray?,
    val low: DoubleArray?,
    val volume: LongArray?,
    val oi: LongArray,
) {
    val size: Int get() = minutes.size

    fun contractId(underlying: String): String =
        if (right == Right.IX) underlying
        else "$underlying|$expiry|${fmtG(strike)}|$right"

    /** Index of the last bar at or before [minute], or -1. */
    fun lastAtOrBefore(minute: Int): Int {
        var lo = 0
        var hi = minutes.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (minutes[mid] <= minute) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    fun indexOf(minute: Int): Int {
        val i = lastAtOrBefore(minute)
        return if (i >= 0 && minutes[i] == minute) i else -1
    }
}

/** A day's worth of series - one expiry chain, or a whole harvested partition. */
class Session(val day: LocalDate, val lotHint: Int?, val series: List<Series>) {
    val options: List<Series> get() = series.filter { it.right != Right.IX }
    val index: Series? get() = series.firstOrNull { it.right == Right.IX }
    val rowCount: Int get() = series.sumOf { it.size }
}

/**
 * The compact series format shared with `tools/export_android_assets.py`.
 * See that file for the byte layout; the two must change together.
 */
object Olx {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'L'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())
    const val FLAG_OHL = 1
    const val FLAG_VOLUME = 2

    class BadFile(msg: String) : IllegalArgumentException(msg)

    fun read(input: InputStream, float32Prices: Boolean = false): List<Session> {
        val inp = DataInputStream(BufferedInputStream(GZIPInputStream(input, 1 shl 16), 1 shl 16))
        val magic = ByteArray(4).also { inp.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw BadFile("not an OLX1 file")
        val flags = inp.readUnsignedByte()
        val ohl = flags and FLAG_OHL != 0
        val hasVol = flags and FLAG_VOLUME != 0
        val nDays = readVarint(inp).toInt()
        val out = ArrayList<Session>(nDays)
        repeat(nDays) {
            val day = LocalDate.ofEpochDay(readVarint(inp))
            val lotRaw = readVarint(inp).toInt()
            val nSeries = readVarint(inp).toInt()
            val series = ArrayList<Series>(nSeries)
            repeat(nSeries) {
                val exp = readVarint(inp)
                val strike = readVarint(inp) / 100.0
                val right = Right.entries[inp.readUnsignedByte()]
                val lot = readVarint(inp).toInt()
                val n = readVarint(inp).toInt()
                val minutes = IntArray(n)
                var prev = 0L
                for (i in 0 until n) { prev += readVarint(inp); minutes[i] = prev.toInt() }
                val closeP = LongArray(n)
                prev = 0L
                for (i in 0 until n) { prev += readZigzag(inp); closeP[i] = prev }
                val close = DoubleArray(n) { price(closeP[it], float32Prices) }
                var open: DoubleArray? = null
                var high: DoubleArray? = null
                var low: DoubleArray? = null
                if (ohl) {
                    open = DoubleArray(n) { price(closeP[it] + readZigzag(inp), float32Prices) }
                    high = DoubleArray(n) { price(closeP[it] + readZigzag(inp), float32Prices) }
                    low = DoubleArray(n) { price(closeP[it] + readZigzag(inp), float32Prices) }
                }
                val volume = if (hasVol) LongArray(n) { readVarint(inp) } else null
                val oi = LongArray(n)
                prev = 0L
                for (i in 0 until n) { prev += readZigzag(inp); oi[i] = prev }
                series += Series(
                    expiry = if (exp == 0L) null else LocalDate.ofEpochDay(exp - 1),
                    strike = if (float32Prices) strike.toFloat().toDouble() else strike,
                    right = right, lot = lot, minutes = minutes, close = close,
                    open = open, high = high, low = low, volume = volume, oi = oi,
                )
            }
            out += Session(day, if (lotRaw == 0) null else lotRaw - 1, series)
        }
        return out
    }

    /**
     * The PC cache stores closes as float32, so a Rs 0.70 put is really
     * 0.699999988. Rebuilding that exact value keeps every forward, strike
     * and P&L bit-comparable with the Python ledger.
     */
    private fun price(paise: Long, float32: Boolean): Double {
        val d = paise / 100.0
        return if (float32) d.toFloat().toDouble() else d
    }

    fun write(out: OutputStream, sessions: List<Session>) {
        val ohl = sessions.all { s -> s.series.all { it.open != null && it.high != null && it.low != null } }
        val vol = sessions.all { s -> s.series.all { it.volume != null } }
        val buf = ByteArrayOutputStream()
        buf.write(MAGIC)
        buf.write((if (ohl) FLAG_OHL else 0) or (if (vol) FLAG_VOLUME else 0))
        writeVarint(buf, sessions.size.toLong())
        for (s in sessions) {
            writeVarint(buf, s.day.toEpochDay())
            writeVarint(buf, (s.lotHint?.plus(1) ?: 0).toLong())
            val ordered = s.series.sortedWith(
                compareBy<Series>({ it.expiry?.toEpochDay() ?: 0L }, { it.strike }, { it.right.ordinal }))
            writeVarint(buf, ordered.size.toLong())
            for (se in ordered) {
                writeVarint(buf, se.expiry?.toEpochDay()?.plus(1) ?: 0L)
                writeVarint(buf, Math.round(se.strike * 100))
                buf.write(se.right.ordinal)
                writeVarint(buf, se.lot.toLong())
                writeVarint(buf, se.size.toLong())
                var prev = 0L
                for (m in se.minutes) { writeVarint(buf, m - prev); prev = m.toLong() }
                val closeP = LongArray(se.size) { paise(se.close[it]) }
                prev = 0L
                for (c in closeP) { writeZigzag(buf, c - prev); prev = c }
                if (ohl) {
                    for (arr in listOf(se.open!!, se.high!!, se.low!!)) {
                        for (i in arr.indices) writeZigzag(buf, paise(arr[i]) - closeP[i])
                    }
                }
                if (vol) se.volume!!.forEach { writeVarint(buf, it) }
                prev = 0L
                for (x in se.oi) { writeZigzag(buf, x - prev); prev = x }
            }
        }
        GZIPOutputStream(out).use { it.write(buf.toByteArray()) }
    }

    private fun paise(x: Double): Long = Math.round(x * 100.0)

    private fun readVarint(inp: DataInputStream): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = inp.read()
            if (b < 0) throw EOFException("truncated OLX file")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw BadFile("varint overflow")
        }
    }

    private fun readZigzag(inp: DataInputStream): Long {
        val v = readVarint(inp)
        return (v ushr 1) xor -(v and 1)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "varint cannot hold $value" }
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out.write(b or 0x80) else { out.write(b); return }
        }
    }

    private fun writeZigzag(out: ByteArrayOutputStream, v: Long) =
        writeVarint(out, (v shl 1) xor (v shr 63))
}

/** Python's `f"{x:g}"` for the strike values this app meets. */
fun fmtG(x: Double): String =
    if (x == Math.rint(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString()
    else x.toBigDecimal().stripTrailingZeros().toPlainString()

/** Minute of day for an "HH:MM" string. */
fun hhmm(text: String): Int {
    val parts = text.trim().split(":")
    require(parts.size >= 2) { "time must be HH:MM, not '$text'" }
    val h = parts[0].toInt()
    val m = parts[1].toInt()
    require(h in 0..23 && m in 0..59) { "time must be HH:MM, not '$text'" }
    return h * 60 + m
}

fun minuteText(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
