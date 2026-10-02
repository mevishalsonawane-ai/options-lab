package com.optionslab.ira

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoicePrintTest {
    /** A synthetic voice: a pitch and three formants (the shape of the vocal tract), with jitter and room noise. */
    private fun voice(f0: Double, formants: List<Double>, seed: Long, seconds: Double = 1.5): ShortArray {
        val r = java.util.Random(seed)
        val pitch = f0 * (1 + (r.nextDouble() - 0.5) * 0.06)
        val fs = formants.map { it * (1 + (r.nextDouble() - 0.5) * 0.04) }
        val n = (VoicePrint.RATE * seconds).toInt()
        val lead = VoicePrint.RATE / 5                                   // a little silence first
        return ShortArray(n + lead) { i ->
            val noise = r.nextGaussian() * 30
            if (i < lead) return@ShortArray noise.toInt().toShort()
            val t = (i - lead).toDouble() / VoicePrint.RATE
            var s = 0.0
            var h = 1
            while (h * pitch < 7_000) {
                val f = h * pitch
                val amp = fs.sumOf { fm -> exp(-((f - fm) / 120).let { it * it }) } + 0.02
                s += amp * sin(2 * PI * f * t)
                h++
            }
            (s * 2_500 + noise).coerceIn(-32_000.0, 32_000.0).toInt().toShort()
        }
    }

    private val boss = listOf(500.0, 1_500.0, 2_500.0)
    private val other = listOf(750.0, 1_900.0, 3_000.0)

    @Test fun theOwnersVoiceMatchesAndAnotherDoesNot() {
        val samples = (1..5L).map { assertNotNull(VoicePrint.features(voice(120.0, boss, it))) }
        val print = assertNotNull(VoicePrint.enroll(samples))
        assertTrue(VoicePrint.matches(print, VoicePrint.features(voice(120.0, boss, 99))), "the owner again")
        assertFalse(VoicePrint.matches(print, VoicePrint.features(voice(210.0, other, 7))), "someone else")
        assertFalse(VoicePrint.matches(print, VoicePrint.features(voice(120.0, other, 8))), "same pitch, another voice")
        // Kept as numbers only, and read back the same.
        val back = assertNotNull(VoicePrint.Print.load(print.save()))
        assertEquals(print.limit, back.limit, 1e-12)
        assertTrue(VoicePrint.matches(back, VoicePrint.features(voice(120.0, boss, 98))))
    }

    @Test fun silenceOrTooFewPhrasesGiveNothing() {
        assertNull(VoicePrint.features(ShortArray(16_000)))
        assertNull(VoicePrint.features(ShortArray(100)))
        assertNull(VoicePrint.enroll((1..3L).map { VoicePrint.features(voice(120.0, boss, it))!! }))
        assertNull(VoicePrint.Print.load("nonsense"))
        assertFalse(VoicePrint.matches(VoicePrint.enroll((1..5L).map { VoicePrint.features(voice(120.0, boss, it))!! })!!, null))
    }
}
