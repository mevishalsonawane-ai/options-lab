package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The backup passphrase's rule (unchanged figures) and its advice (20 Oct: five tries on 19 Oct with "make it longer"). */
class PassphraseTest {
    private fun c(s: String) = s.toCharArray()

    @Test fun theRuleIsTheSameAsBefore() {
        // The app's BackupTest figures, and the minimum of 10 characters and 50 bits.
        assertEquals(0, Passphrase.strength(c("Short1!")))
        assertEquals(1, Passphrase.strength(c("aaaaaaaaaaaa")))
        assertEquals(2, Passphrase.strength(c("Abcdefgh12")))
        assertEquals(3, Passphrase.strength(c("correct horse battery staple")))
        assertEquals(1, Passphrase.strength(c("mypassword")))       // 10 lower-case letters: 47 bits
        assertEquals(2, Passphrase.strength(c("mypasswords")))      // 11: 51.7 bits
        assertEquals(10, Passphrase.MIN)
        assertEquals(50.0, Passphrase.FAIR_BITS)
    }

    @Test fun theAdviceSaysHowLongAndTheShorterWay() {
        val a = assertNotNull(Passphrase.advice(c("mypassword")))
        assertEquals("Weak: make it 11 characters long as typed (10 now), or 10 with a digit and a dash or a space in it", a.short)
        assertTrue(a.full.startsWith("That passphrase is too easy to guess: make it 11 characters long as typed (10 now)"), a.full)
        assertTrue(a.full.contains("river-lamp-cloud-42"), a.full)
        // The example itself passes, and so does following either way.
        assertTrue(Passphrase.accepted(c("river-lamp-cloud-42")))
        assertTrue(Passphrase.accepted(c("mypasswordx")))
        assertTrue(Passphrase.accepted(c("mypa-ssw0rd")))
    }

    @Test fun repeatsAreNamed() {
        val a = assertNotNull(Passphrase.advice(c("abababababab")))
        assertEquals("Weak: use at least 6 different characters (2 now)", a.short)
        assertTrue(Passphrase.accepted(c("abcdefababab")))
    }

    @Test fun tooShortAndEmptyAndAcceptedOnes() {
        val empty = assertNotNull(Passphrase.advice(CharArray(0)))
        assertEquals("0 of at least 10 characters", empty.short)
        assertTrue(empty.full.startsWith("That passphrase is too short: it needs at least 10 characters (0 now)."))
        assertEquals("7 of at least 10 characters", Passphrase.advice(c("Short1!"))!!.short)
        assertNull(Passphrase.advice(c("Abcdefgh12")))
        assertNull(Passphrase.advice(c("correct horse battery staple")))
    }

    /** Whatever the kinds used, following the advice's length passes (never a needed length that is still weak). */
    @Test fun theNeededLengthAlwaysPasses() {
        for (alphabet in listOf("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "0123456789", "abc123", "aB", "a-", "äöüßéè")) {
            val pool = Passphrase.pool(c(alphabet))
            val n = Passphrase.lengthFor(pool)
            val p = CharArray(n) { alphabet[it % alphabet.length] }
            if (p.toSet().size * 2 >= n) assertTrue(Passphrase.accepted(p), "$alphabet x$n")
            assertTrue(!Passphrase.accepted(CharArray(maxOf(Passphrase.MIN, n - 1)) { alphabet[it % alphabet.length] }) || n - 1 < Passphrase.MIN,
                "$alphabet: one shorter must not pass")
        }
    }
}
