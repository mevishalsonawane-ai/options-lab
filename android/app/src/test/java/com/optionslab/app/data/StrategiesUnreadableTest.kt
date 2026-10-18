package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.strategy.OptionType
import com.optionslab.engine.strategy.Position
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 18 Oct, review: a strategies book that cannot be read just now (a Keystore hiccup past the retries) is never set aside
 * as destroyed: the read fails, nothing is cached, and the next read gets the book back - an open live run's legs and stops
 * are never lost to it. Only bytes that fail authentication (or do not parse) are set aside. The day's stop and the order
 * labels never throw into an arm meanwhile.
 */
class StrategiesUnreadableTest : RobolectricTest() {
    private val file get() = File(context.filesDir, "strategies.vault")
    private fun asides() = context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("strategies.vault.unreadable") }

    @After fun down() { file.takeIf { it.isDirectory }?.deleteRecursively(); asides().forEach { it.delete() } }

    private fun savedOne(): ByteArray {
        assertNull(runBlocking { Strategies.save(AutomationSupport.strategy("Kept basket", Position.B to OptionType.CE)) })
        assertTrue(file.isFile)
        return file.readBytes()
    }

    @Test fun aReadThatFailsIsRetriedAndTheFileIsNeverSetAside() {
        val bytes = savedOne()
        Strategies.forgetCacheForTest()
        // The file there but not readable now (a read that throws, not bad bytes).
        file.delete(); file.mkdirs()
        assertTrue(runCatching { runBlocking { Strategies.all() } }.isFailure)
        assertTrue("never set aside", asides().isEmpty())
        // Meanwhile the day's stop reads its last known state and a label is skipped: neither throws.
        assertFalse(runBlocking { Strategies.stoppedToday() })
        runBlocking { Strategies.tagOwner("paper:1", "test · entry") }
        // Readable again: the same book back, nothing lost.
        file.deleteRecursively(); file.writeBytes(bytes)
        assertEquals(listOf("Kept basket"), runBlocking { Strategies.all() }.map { it.def.name })
        assertTrue(asides().isEmpty())
    }

    @Test fun bytesThatFailAuthenticationAreStillSetAside() {
        savedOne()
        Strategies.forgetCacheForTest()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(runBlocking { Strategies.all() }.isEmpty())
        assertEquals(1, asides().size)
    }
}
