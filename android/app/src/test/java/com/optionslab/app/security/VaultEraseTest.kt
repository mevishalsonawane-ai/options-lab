package com.optionslab.app.security

import com.optionslab.app.testing.FakeAndroidKeyStore
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 26 Oct (CI on 29ba08f: RootNavigationTest "the vault key is gone" failed once): a save on another thread could make a NEW vault
 * key after erase-everything destroyed the old one. Between [Vault.beginErase] and [Vault.endErase] no key is made and no vault
 * file is written.
 */
class VaultEraseTest : RobolectricTest() {
    private val vaultAlias = "ol.vault.data.v1"
    private val pepperAlias = "ol.pin.pepper.v1"
    private val plain = "a paper book: nothing real".toByteArray()

    private fun aliases() = FakeAndroidKeyStore.aliases()

    @Test fun duringAnEraseNoKeyIsMadeAndNoFileWritten() {
        Vault.writeFile(File(context.filesDir, "before.vault"), plain)
        PinPepper.mix(PinPepper.VERIFIER, byteArrayOf(1))
        assertTrue(vaultAlias in aliases() && pepperAlias in aliases())
        Vault.beginErase()
        try {
            assertTrue(Vault.isErasing)
            assertFalse("the vault key went at the start", vaultAlias in aliases())
            assertFalse("the pepper went at the start", pepperAlias in aliases())
            val f = File(context.filesDir, "during.vault")
            Vault.writeFile(f, plain)                                  // dropped, never thrown
            assertFalse("a save during the erase is dropped", f.exists())
            try { Vault.encrypt(plain); fail("sealed during an erase") } catch (_: Vault.Erasing) {}
            try { Vault.readFileSteady(File(context.filesDir, "before.vault")); fail("read during an erase") } catch (_: Vault.Erasing) {}
            try { PinPepper.mix(PinPepper.VERIFIER, byteArrayOf(1)); fail("pepper made during an erase") } catch (_: Vault.Erasing) {}
            // A settings write too (the market recorder's wipe made one from the slow file thread).
            SecurePrefs.wipe()
            SecurePrefs.put("x.during", 1)
            SecurePrefs.flush()                                        // a deferred write runs now, inside the erase
            assertFalse(File(context.noBackupFilesDir, "prefs.vault").exists())
            assertFalse("no key was made", vaultAlias in aliases() || pepperAlias in aliases())
        } finally { Vault.endErase() }
        assertFalse(Vault.isErasing)
        assertFalse(vaultAlias in aliases())
        // After the erase, saves work again (a new key: the PIN Boss sets next).
        val after = File(context.filesDir, "after.vault")
        Vault.writeFile(after, plain)
        assertArrayEquals(plain, Vault.readFile(after))
        assertTrue(vaultAlias in aliases())
    }

    @Test fun aSaveThatBeganBeforeTheEraseNeverLandsAfterIt() {
        val f = File(context.filesDir, "slow.vault")
        // The save starts before the erase and is held (the slow-write seam) until the erase has begun and ended; its rename
        // comes after the erase deleted everything, so it is dropped (its file would bring the erased data back).
        Vault.testWriteDelayMs = 400L
        val failure = AtomicReference<Throwable?>()
        val before = Vault.droppedInErase.get()
        val t = Thread { try { Vault.writeFile(f, plain) } catch (e: Throwable) { failure.set(e) } }
        try {
            t.start()
            Thread.sleep(100)                       // the save is under way (inside its delay)
            Vault.beginErase()
            Vault.endErase()
            t.join(10_000)
        } finally { Vault.testWriteDelayMs = 0L }
        assertNull(failure.get())
        assertFalse("a save from before the erase landed after it", f.exists())
        assertEquals(before + 1, Vault.droppedInErase.get())
        Vault.writeFile(f, plain)                   // the next save is written as usual
        assertArrayEquals(plain, Vault.readFile(f))
    }

    /** Many writers on other threads saving all through an erase, 300 times: no key is ever made while it runs. */
    @Test fun backgroundSavesRacingTheEraseNeverRecreateTheKey() {
        val prefs = File(context.noBackupFilesDir, "prefs.vault")
        val stop = AtomicBoolean(false)
        val tries = AtomicLong()
        val failure = AtomicReference<Throwable?>()
        val writers = (0 until 4).map { n ->
            Thread {
                try {
                    var i = 0
                    while (!stop.get()) {
                        i++
                        runCatching {
                            when (n) {
                                0 -> Vault.writeFile(File(context.filesDir, "race$n.vault"), plain)
                                1 -> Vault.encrypt(plain)
                                2 -> SecurePrefs.put("race.$i", i)
                                else -> PinPepper.mix(PinPepper.BOX, byteArrayOf(i.toByte()))
                            }
                        }
                        tries.incrementAndGet()
                    }
                } catch (e: Throwable) { failure.set(e) }
            }.apply { isDaemon = true; start() }
        }
        try {
            repeat(300) { round ->
                Vault.writeFile(File(context.filesDir, "seed.vault"), plain)
                val seen = tries.get()
                Vault.beginErase()
                try {
                    // The writers get to run inside the erase (bounded: a slow machine never hangs the test).
                    val until = System.nanoTime() + 200_000_000L
                    while (tries.get() < seen + 8 && System.nanoTime() < until) Thread.yield()
                    File(context.filesDir, "race0.vault").delete()
                    prefs.delete()
                    Thread.yield()
                    assertFalse("round $round: a vault key was made during the erase", vaultAlias in aliases())
                    assertFalse("round $round: a pepper was made during the erase", pepperAlias in aliases())
                    assertFalse("round $round: a vault file was written during the erase", File(context.filesDir, "race0.vault").exists())
                } finally { Vault.endErase() }
            }
        } finally {
            stop.set(true)
            writers.forEach { it.join(10_000) }
        }
        assertNull(failure.get())
        assertTrue("the writers ran", tries.get() > 300)
    }

    @Test fun anEraseThatThrowsStillEnds() {
        Vault.beginErase()
        Vault.testBeforeLastDestroy = { throw IllegalStateException("a failing hook") }
        try { Vault.endErase(); fail("the hook's failure is not hidden") } catch (_: IllegalStateException) {}
        Vault.testBeforeLastDestroy = null
        assertFalse("the erase ended anyway", Vault.isErasing)
        Vault.encrypt(plain)                        // saves work again
        assertTrue(vaultAlias in aliases())
    }

    @Test fun twoErasesAtOnceHoldUntilTheLastEnds() {
        Vault.beginErase(); Vault.beginErase()
        Vault.endErase()
        assertTrue(Vault.isErasing)
        try { Vault.encrypt(plain); fail() } catch (_: Vault.Erasing) {}
        Vault.endErase()
        assertFalse(Vault.isErasing)
        Vault.endErase()                            // one too many: harmless
        assertFalse(Vault.isErasing)
        assertEquals(12 + plain.size + 16, Vault.encrypt(plain).size)
    }
}
