package com.optionslab.ira

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * "Only Boss's voice can trade" (the owner's wish, 2026-10-02): a voice print made on the phone from a few phrases the
 * owner says once, and each spoken approval checked against it. The print is the spread of the voice's spectrum
 * (mel-frequency cepstral coefficients: their mean and variation over the voiced frames); a sample matches when it is
 * as close to the owner's phrases as they are to each other, with a margin. Nothing leaves the phone: the audio is
 * dropped after the check; only the numbers of the print are kept, encrypted. It tells voices apart in a quiet room; it
 * is not a bank-grade check and a recording of the owner could pass. Pure.
 */
object VoicePrint {
    const val RATE = 16_000
    const val SAMPLES_NEEDED = 5
    private const val FRAME = 400          // 25 ms
    private const val HOP = 160            // 10 ms
    private const val FFT = 512
    private const val MELS = 26
    private const val CEPS = 13
    /** Distances this much past the owner's own spread still match (a different room, a tired voice). */
    const val MARGIN = 1.6
    private const val FLOOR = 0.6

    /** The features of one phrase (16 kHz mono PCM), or null when there is too little voice in it. */
    fun features(pcm: ShortArray): DoubleArray? {
        if (pcm.size < FRAME * 4) return null
        val x = DoubleArray(pcm.size) { pcm[it] / 32768.0 }
        for (i in x.size - 1 downTo 1) x[i] = x[i] - 0.97 * x[i - 1]
        val window = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }
        val filters = melFilters()
        val frames = ArrayList<Pair<Double, DoubleArray>>()
        var start = 0
        val re = DoubleArray(FFT); val im = DoubleArray(FFT)
        while (start + FRAME <= x.size) {
            re.fill(0.0); im.fill(0.0)
            var energy = 0.0
            for (i in 0 until FRAME) { re[i] = x[start + i] * window[i]; energy += x[start + i] * x[start + i] }
            fft(re, im)
            val power = DoubleArray(FFT / 2 + 1) { (re[it] * re[it] + im[it] * im[it]) / FFT }
            val logMel = DoubleArray(MELS) { m -> ln(max(1e-10, filters[m].indices.sumOf { k -> filters[m][k] * power[k] })) }
            val c = DoubleArray(CEPS) { n -> (0 until MELS).sumOf { m -> logMel[m] * cos(PI * n * (m + 0.5) / MELS) } }
            frames += energy to c
            start += HOP
        }
        // The voiced frames only: within 30 dB of the loudest.
        val loud = frames.maxOfOrNull { it.first } ?: return null
        if (loud <= 1e-7) return null
        val voiced = frames.filter { 10 * log10(max(it.first, 1e-12) / loud) > -30 }.map { it.second }
        if (voiced.size < 20) return null
        val d = CEPS - 1                                                  // c0 is loudness: left out
        val mean = DoubleArray(d) { j -> voiced.sumOf { it[j + 1] } / voiced.size }
        val sd = DoubleArray(d) { j -> sqrt(voiced.sumOf { (it[j + 1] - mean[j]).pow(2) } / voiced.size) }
        return mean + sd
    }

    /** The owner's print: the phrases' average, how much each number varies, and how far the phrases sit apart. */
    data class Print(val mean: DoubleArray, val spread: DoubleArray, val limit: Double) {
        fun save(): String = (mean.toList() + spread.toList() + limit).joinToString(",")
        companion object {
            fun load(s: String): Print? = runCatching {
                val v = s.split(",").map { it.toDouble() }
                val n = (v.size - 1) / 2
                require(n > 0 && v.size == 2 * n + 1)
                Print(v.subList(0, n).toDoubleArray(), v.subList(n, 2 * n).toDoubleArray(), v.last())
            }.getOrNull()
        }
    }

    /** A print from the owner's phrases ([SAMPLES_NEEDED] or more feature vectors), or null. */
    fun enroll(samples: List<DoubleArray>): Print? {
        if (samples.size < SAMPLES_NEEDED || samples.map { it.size }.distinct().size != 1) return null
        val n = samples[0].size
        val mean = DoubleArray(n) { j -> samples.sumOf { it[j] } / samples.size }
        val spread = DoubleArray(n) { j -> max(FLOOR, sqrt(samples.sumOf { (it[j] - mean[j]).pow(2) } / (samples.size - 1))) }
        // Each phrase against the print of the others: the farthest one sets how far a match may be.
        val loo = samples.indices.map { i ->
            val rest = samples.filterIndexed { k, _ -> k != i }
            val m = DoubleArray(n) { j -> rest.sumOf { it[j] } / rest.size }
            distance(samples[i], m, spread)
        }
        return Print(mean, spread, loo.max() * MARGIN)
    }

    fun distance(x: DoubleArray, mean: DoubleArray, spread: DoubleArray): Double =
        sqrt(x.indices.sumOf { ((x[it] - mean[it]) / spread[it]).pow(2) } / x.size)

    /** Does [sample] (features) match the owner's [print]? */
    fun matches(print: Print, sample: DoubleArray?): Boolean =
        sample != null && sample.size == print.mean.size && distance(sample, print.mean, print.spread) <= print.limit

    private fun melFilters(): Array<DoubleArray> {
        fun mel(f: Double) = 2595 * log10(1 + f / 700)
        fun hz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)
        val lo = mel(20.0); val hi = mel(RATE / 2.0)
        val pts = DoubleArray(MELS + 2) { hz(lo + (hi - lo) * it / (MELS + 1)) }
        val bins = pts.map { Math.floor((FFT + 1) * it / RATE).toInt().coerceIn(0, FFT / 2) }
        return Array(MELS) { m ->
            DoubleArray(FFT / 2 + 1) { k ->
                val a = bins[m]; val b = bins[m + 1]; val c = bins[m + 2]
                when {
                    k in a until b && b > a -> (k - a).toDouble() / (b - a)
                    k in b..c && c > b -> (c - k).toDouble() / (c - b)
                    else -> 0.0
                }
            }
        }
    }

    /** In-place radix-2 FFT (size a power of two). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
