package com.optionslab.app.ira

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.WeeklyReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The weekly reviews live in their own encrypted file ([IraWeekly.FILE]), not the preferences (every preference write
 * re-encrypts the whole map): reviews an earlier build kept under [IraWeekly.KEY] move there once and leave the preferences.
 */
class IraWeeklyStoreTest : RobolectricTest() {
    private fun review(monday: LocalDate) = WeeklyReview.Review(monday, monday.plusDays(4), monday.plusDays(4).atTime(15, 45),
        "A quiet week.", "A quiet week, Boss.", "Watch Thursday's expiry.", emptyList())

    @Test fun reviewsKeptInThePreferencesMoveToTheirOwnFileOnce() {
        val old = listOf(review(LocalDate.of(2026, 9, 28)), review(LocalDate.of(2026, 9, 21)))
        SecurePrefs.put(IraWeekly.KEY, WeeklyReview.encodeAll(old))
        val f = IraWeekly.file()!!
        assertFalse(f.exists())

        assertEquals(old, IraWeekly.kept())
        // Moved: in the file (sealed, not plain text), gone from the preferences.
        assertTrue(f.exists())
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("A quiet week"))
        assertEquals(WeeklyReview.encodeAll(old), Vault.readFile(f)!!.toString(Charsets.UTF_8))
        assertNull(SecurePrefs.getString(IraWeekly.KEY))
        // Read again (and after the preferences reload): the same, from the file.
        SecurePrefs.reload()
        assertNull(SecurePrefs.getString(IraWeekly.KEY))
        assertEquals(old, IraWeekly.kept())
    }

    @Test fun noReviewsAnywhereIsEmpty() {
        assertEquals(emptyList<WeeklyReview.Review>(), IraWeekly.kept())
        assertFalse(IraWeekly.file()!!.exists())
    }
}
