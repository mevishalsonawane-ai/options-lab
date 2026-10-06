package com.optionslab.ira.neuro

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.TreeMap
import java.util.TreeSet

/**
 * One session of one index, as the graph keeps it: the events that happened ([mask], [Ev.bit]s), what was covered
 * ([cov]: [COV_DAILY], [COV_OPT]), the trend regime read from the sessions before it ([regime], a [Regime] ordinal or -1),
 * the high-volatility flag, the minute the first zero-to-hero move reached its multiple (-1: none), the at-the-money IV
 * at the close (NaN: none) and the day's return.
 */
data class DayRow(val day: Int, val mask: Int, val cov: Int, val regime: Int, val highVol: Boolean, val zthMinute: Int, val iv: Float, val ret: Float) {
    fun has(e: Ev) = mask and e.bit != 0
    fun covers(e: Ev) = cov and (if (e.options) COV_OPT else COV_DAILY) != 0

    companion object {
        const val COV_DAILY = 1
        const val COV_OPT = 2
    }
}

/**
 * The builder's memory between runs, so a run reads only what is new: the index sessions kept (rolling, [NeuroBuilder.DAY_LOOKBACK_YEARS]),
 * the pair statistics as sums per calendar quarter (rolling, [NeuroBuilder.PAIR_LOOKBACK_YEARS]) - sums, so the older 70% /
 * newest 30% split is made afresh at every build - the watermarks (the last day each source was read to), the windows
 * seen per folder (to notice older data arriving later: a full rebuild) and the expiry days known. Versioned; a file of
 * another version is rebuilt from the store.
 */
class NeuroState {
    companion object {
        /** 2: the windows' signatures ([sigs]) and the open-to-close bits of [DayRow.mask] ([Rules.OC_UP] / [Rules.OC_DOWN]). */
        const val VERSION = 2
        private const val MAGIC = 0x4E475354 // "NGST"

        fun read(input: DataInputStream): NeuroState {
            if (input.readInt() != MAGIC) throw IOException("not a NeuroGraph state file")
            val v = input.readInt()
            if (v != VERSION) throw IOException("NeuroGraph state version $v")
            val s = NeuroState()
            s.builds = input.readInt()
            repeat(input.readInt()) {
                val u = input.readUTF()
                val m = TreeMap<Int, DayRow>()
                repeat(input.readInt()) {
                    val r = DayRow(input.readInt(), input.readInt(), input.readByte().toInt(), input.readByte().toInt(), input.readBoolean(),
                        input.readShort().toInt(), input.readFloat(), input.readFloat())
                    m[r.day] = r
                }
                s.rows[u] = m
            }
            repeat(input.readInt()) {
                val k = input.readUTF()
                val m = TreeMap<Int, DoubleArray>()
                repeat(input.readInt()) {
                    val q = input.readInt()
                    val n = input.readByte().toInt()
                    m[q] = DoubleArray(n) { input.readDouble() }
                }
                s.pairs[k] = m
            }
            repeat(input.readInt()) { s.marks[input.readUTF()] = input.readInt() }
            repeat(input.readInt()) { val k = input.readUTF(); s.seen[k] = IntArray(input.readInt()) { input.readInt() } }
            repeat(input.readInt()) { val k = input.readUTF(); s.expiries[k] = TreeSet<Int>().apply { repeat(input.readInt()) { add(input.readInt()) } } }
            repeat(input.readInt()) { s.sigs[input.readUTF()] = input.readLong() }
            return s
        }
    }

    /** Index -> epoch day -> session. */
    val rows = TreeMap<String, TreeMap<Int, DayRow>>()
    /** Pair key ("D|eq:HDFCBANK|BANKNIFTY", "L|idx:BANKNIFTY|NIFTY") -> quarter (year*4 + q) -> sums. */
    val pairs = TreeMap<String, TreeMap<Int, DoubleArray>>()
    /** Source key -> the last epoch day read into the state. */
    val marks = TreeMap<String, Int>()
    /** Store folder -> the windows (epoch day of each file's start) seen there. */
    val seen = TreeMap<String, IntArray>()
    /** Index -> its expiry days (listed by Dhan, and read from the options themselves). */
    val expiries = TreeMap<String, TreeSet<Int>>()
    /**
     * Window (a candle file's store path, or an option window's folder) -> its signature (sizes and modification times,
     * [NeuroBuilder.signature]) for every window whose days were ALL read into the state: one rewritten later means the
     * state learned from data that has since changed, and [NeuroBuilder.needsFull] asks for a rebuild.
     */
    val sigs = TreeMap<String, Long>()
    var builds = 0

    fun write(out: DataOutputStream) {
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(builds)
        out.writeInt(rows.size)
        for ((u, m) in rows) {
            out.writeUTF(u); out.writeInt(m.size)
            for (r in m.values) {
                out.writeInt(r.day); out.writeInt(r.mask); out.writeByte(r.cov); out.writeByte(r.regime); out.writeBoolean(r.highVol)
                out.writeShort(r.zthMinute); out.writeFloat(r.iv); out.writeFloat(r.ret)
            }
        }
        out.writeInt(pairs.size)
        for ((k, m) in pairs) {
            out.writeUTF(k); out.writeInt(m.size)
            for ((q, a) in m) { out.writeInt(q); out.writeByte(a.size); for (x in a) out.writeDouble(x) }
        }
        out.writeInt(marks.size); for ((k, v) in marks) { out.writeUTF(k); out.writeInt(v) }
        out.writeInt(seen.size); for ((k, v) in seen) { out.writeUTF(k); out.writeInt(v.size); for (x in v) out.writeInt(x) }
        out.writeInt(expiries.size); for ((k, v) in expiries) { out.writeUTF(k); out.writeInt(v.size); for (x in v) out.writeInt(x) }
        out.writeInt(sigs.size); for ((k, v) in sigs) { out.writeUTF(k); out.writeLong(v) }
        out.flush()
    }

    /** The newest session kept for any index (epoch day), or null. */
    fun newest(): Int? = rows.values.mapNotNull { if (it.isEmpty()) null else it.lastKey() }.maxOrNull()
}
