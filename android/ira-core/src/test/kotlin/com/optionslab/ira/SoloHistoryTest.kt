package com.optionslab.ira

import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * Solo over real history (index minutes + real option minutes, research-data release). Runs only when SOLO_DATA names
 * the folder with the exported files (day,expiry,right,strike,m,o,h,l,c); prints the report.
 */
class SoloHistoryTest {
    private fun days(f: File): Sequence<Solo.HistDay> = sequence {
        GZIPInputStream(f.inputStream()).bufferedReader().use { rd ->
            rd.readLine()
            var day: String? = null; var expiry = ""
            var ix = arrayOfNulls<Candle>(375)
            var opts = HashMap<Pair<Int, Boolean>, Array<Candle?>>()
            fun done(): Solo.HistDay? {
                val d = day ?: return null
                // Fill missing index minutes from the one before.
                for (i in 1 until 375) if (ix[i] == null) ix[i] = ix[i - 1]
                if (ix[0] == null) return null
                return Solo.HistDay(LocalDate.parse(d), LocalDate.parse(expiry), ix.map { it!! }, opts)
            }
            while (true) {
                val line = rd.readLine() ?: break
                val p = line.split(',')
                if (p[0] != day) {
                    done()?.let { yield(it) }
                    day = p[0]; expiry = p[1]; ix = arrayOfNulls(375); opts = HashMap()
                }
                val m = p[4].toInt()
                val d = LocalDate.parse(p[0])
                val c = Candle(d.atTime(9, 15).plusMinutes(m.toLong()), p[5].toDouble(), p[6].toDouble(), p[7].toDouble(), p[8].toDouble())
                when (p[2]) {
                    "IX" -> ix[m] = c
                    else -> opts.getOrPut(p[3].toInt() to (p[2] == "CE")) { arrayOfNulls(375) }[m] = c
                }
            }
            done()?.let { yield(it) }
        }
    }

    @Test fun soloOverHistory() {
        val dir = System.getenv("SOLO_DATA")?.let(::File) ?: return
        val sets = listOf(Triple("NIFTY Apr 2024-Apr 2025", "nifty_a.csv.gz", Triple(50, 75, 0)),
            Triple("NIFTY Apr 2025-Apr 2026", "nifty_b.csv.gz", Triple(50, 75, 0)),
            Triple("BANKNIFTY Feb 2025-Feb 2026", "banknifty_b.csv.gz", Triple(100, 30, 0)))
        val variants = (System.getenv("SOLO_VARIANTS") ?: "base").split(',')
        for (v in variants) {
            val r = when (v) {
                "base" -> Solo.Rules()
                "itm1" -> Solo.Rules(itm = 1)
                "itm2" -> Solo.Rules(itm = 2)
                "top20" -> Solo.Rules(bigQuantile = 0.80)
                "k15" -> Solo.Rules(k = 1.5)
                "one" -> Solo.Rules(maxPerDay = 1)
                "slow20" -> Solo.Rules(slowMinutes = 20)
                "slow30" -> Solo.Rules(slowMinutes = 30)
                "slow20itm1" -> Solo.Rules(slowMinutes = 20, itm = 1)
                "slow30k15" -> Solo.Rules(slowMinutes = 30, k = 1.5)
                "slow20one" -> Solo.Rules(slowMinutes = 20, maxPerDay = 1)
                "learn20" -> Solo.Rules(maxPerDay = 1, recentN = 20)
                "learn40" -> Solo.Rules(maxPerDay = 1, recentN = 40)
                "learn60" -> Solo.Rules(maxPerDay = 1, recentN = 60)
                "lockA" -> Solo.Rules(maxPerDay = 1, recentN = 60, ladder = listOf(0.5 to 0.0, 0.75 to 0.25))
                "lockB" -> Solo.Rules(maxPerDay = 1, recentN = 60, ladder = listOf(0.75 to 0.25))
                "lockC" -> Solo.Rules(maxPerDay = 1, recentN = 60, ladder = listOf(0.75 to 0.0))
                "lockD" -> Solo.Rules(maxPerDay = 1, recentN = 60, ladder = listOf(0.5 to 0.0))
                "nolock" -> Solo.Rules(maxPerDay = 1, recentN = 60, profitLock = false)
                "room08" -> Solo.Rules(maxPerDay = 1, recentN = 60, maxRangeUsed = 0.8)
                "room10" -> Solo.Rules(maxPerDay = 1, recentN = 60, maxRangeUsed = 1.0)
                "room12" -> Solo.Rules(maxPerDay = 1, recentN = 60, maxRangeUsed = 1.2)
                "learn40b" -> Solo.Rules(maxPerDay = 1, recentN = 40, recentMinR = 0.1)
                else -> error(v)
            }
            for ((name, file, cfg) in sets) {
                val f = File(dir, file); if (!f.exists()) continue
                val rep = Solo.backtest(days(f), step = cfg.first, lot = cfg.second, r = r)
                println("SOLO [$v] " + Solo.say(name, rep))
                println("SOLO [$v]   exits: " + rep.trades.groupingBy { it.exit }.eachCount() + "  calls/puts: " + rep.trades.count { it.call } + "/" + rep.trades.count { !it.call } +
                    "  months: " + rep.byMonth().entries.joinToString(" ") { "${it.key.drop(2)}:${"%.0f".format(it.value / 1000)}k" })
            }
        }
    }
}
