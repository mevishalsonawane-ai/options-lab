package com.optionslab.app.security

import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSystemClock
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration

/**
 * The device check: what counts as compromised (and so withdraws biometrics, refuses live orders and,
 * if the owner chose, refuses to open), and each probe on its own.
 */
class IntegrityTest : RobolectricTest() {
    /**
     * [Integrity.reportWithin] keeps its report in a static cache that outlives the test, and the Robolectric clock
     * restarts with every test (so a cached time can lie in the "future"): drop it, or a compromised report made
     * here would be reused by every later test that asks for a recent one.
     */
    @org.junit.Before @org.junit.After fun dropCachedReport() {
        // 22 Nov 2026: and no background scan or refresher of a test left running.
        Integrity.resetForTest()
        Integrity::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        Integrity::class.java.getDeclaredField("cachedAt").apply { isAccessible = true }.setLong(null, 0L)
    }

    private fun f(sev: Integrity.Severity) = Integrity.Finding("x", sev, "test")

    @Test fun onlyADangerFindingIsACompromise() {
        assertFalse(Integrity.compromised(emptyList()))
        assertFalse(Integrity.compromised(listOf(f(Integrity.Severity.OK), f(Integrity.Severity.NOTICE))))
        assertTrue(Integrity.compromised(listOf(f(Integrity.Severity.OK), f(Integrity.Severity.DANGER))))
    }

    private fun cleanDevice() {
        ShadowBuild.setTags("release-keys")
        ShadowBuild.setFingerprint("google/oriole/oriole:14/AP1A/1:user/release-keys")
        ShadowBuild.setHardware("oriole")
    }

    @Test fun aCleanPhoneReportsEveryProbe() {
        cleanDevice()
        val r = Integrity.report(context)
        val names = r.map { it.name }
        for (n in listOf("Sandbox", "Root", "Hooking", "Debugger", "Record")) assertTrue("$n missing from $names", n in names)
        assertEquals(Integrity.Severity.OK, r.first { it.name == "Root" }.severity)
        assertEquals(Integrity.Severity.OK, r.first { it.name == "Hooking" }.severity)
        assertEquals(Integrity.Severity.OK, r.first { it.name == "Debugger" }.severity)
        assertFalse("not an emulator", "Device" in names)
        assertNotEquals("the sandbox holds only what the build allows", Integrity.Severity.DANGER, r.first { it.name == "Sandbox" }.severity)
    }

    @Test fun testKeysMeanRootAndACompromise() {
        cleanDevice()
        ShadowBuild.setTags("test-keys")
        assertTrue(Integrity.rooted())
        val r = Integrity.report(context)
        assertEquals(Integrity.Severity.DANGER, r.first { it.name == "Root" }.severity)
        assertTrue(Integrity.compromised(r))
    }

    @Test fun anEmulatorIsANoticeNotACompromise() {
        cleanDevice()
        ShadowBuild.setFingerprint("generic/sdk_gphone64/emu64:14/UE1A/1:userdebug/dev-keys")
        assertTrue(Integrity.emulator())
        val r = Integrity.report(context)
        assertEquals(Integrity.Severity.NOTICE, r.first { it.name == "Device" }.severity)
        cleanDevice(); ShadowBuild.setHardware("ranchu")
        assertTrue(Integrity.emulator())
    }

    @Test fun aListenerOnFridasPortIsHooking() {
        cleanDevice()
        // A backlog of 50: nothing accepts here, so with a backlog of 1 the first probe's connection filled the queue and a
        // second probe could time out (read as no listener) on a busy machine.
        val port = runCatching { ServerSocket(27042, 50, InetAddress.getByName("127.0.0.1")) }.getOrNull()
            ?: return   // something else holds the port on this machine: the probe cannot be isolated
        port.use {
            assertTrue(Integrity.hooked())
            assertEquals(Integrity.Severity.DANGER, Integrity.report(context).first { f -> f.name == "Hooking" }.severity)
        }
        assertFalse(Integrity.hooked())
    }

    @Test fun thePermissionsHeldAreTheAllowedOnes() {
        val held = Integrity.heldPermissions(context)
        assertTrue("android.permission.INTERNET" in held)
        assertEquals(held.sorted(), held)
        assertTrue("android.permission.INTERNET" in Integrity.allowedPermissions(context))
        for (never in listOf("READ_SMS", "READ_CONTACTS", "CAMERA", "ACCESS_FINE_LOCATION", "READ_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW"))
            assertFalse("$never must never be requested", held.any { it.endsWith(".$never") })
        // The microphone is held for "Jarvis" only, and on the allowlist for that.
        if (held.any { it.endsWith(".RECORD_AUDIO") }) assertTrue("android.permission.RECORD_AUDIO" in Integrity.allowedPermissions(context))
    }

    @Test fun aRecentReportIsReusedAndAnOldOneRedone() {
        // Past any report cached by an earlier test in this run (the clock restarts with each test).
        ShadowSystemClock.advanceBy(Duration.ofDays(365))
        cleanDevice()
        val first = Integrity.reportWithin(context, 60_000)
        assertFalse(Integrity.compromised(first))
        ShadowBuild.setTags("test-keys")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        assertSame("within a minute the report is reused", first, Integrity.reportWithin(context, 60_000))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        assertTrue("older than a minute it is redone", Integrity.compromised(Integrity.reportWithin(context, 60_000)))
    }

    // ---- 22 Nov 2026 (B6 review: a full scan ran inside the 15-second stop check) ------------------------------------------

    private fun awaitTrue(what: String, timeoutMs: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(5) }
        throw AssertionError("timed out waiting for $what")
    }

    /** The stop check's question: answered from memory at once, never a scan on its thread; with no verdict yet an entry is refused. */
    @Test fun theStopChecksVerdictIsReadFromMemoryAndUnknownRefusesAnEntry() {
        ShadowSystemClock.advanceBy(Duration.ofDays(365))
        cleanDevice()
        Integrity.testScanDelayMs = 1_500
        Integrity.scanWatchedForTest = Thread.currentThread()
        val t0 = System.nanoTime()
        assertEquals("nothing kept yet: unknown", null, Integrity.cachedCompromised(context))
        assertTrue("unknown refuses an entry", Integrity.entryRefused(context))
        assertTrue("never waited on the scan", (System.nanoTime() - t0) / 1_000_000 < 1_000)
        // The scan it started runs in the background and is kept.
        awaitTrue("the background scan") { Integrity.cachedCompromised(context) != null }
        assertEquals(false, Integrity.cachedCompromised(context))
        assertFalse(Integrity.entryRefused(context))
        assertEquals("no scan ran on the asking thread", 0L, Integrity.scansOnWatchedForTest.get())
        // A compromised verdict refuses; one older than a minute is unknown again (refused) and its rescan starts.
        Integrity.testScanDelayMs = 0
        ShadowBuild.setTags("test-keys")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertEquals(null, Integrity.cachedCompromised(context))
        awaitTrue("the rescan") { Integrity.cachedCompromised(context) == true }
        assertTrue(Integrity.entryRefused(context))
        assertEquals(0L, Integrity.scansOnWatchedForTest.get())
    }

    /**
     * The full pass (never the stop check) with no verdict kept - the first pass after a start - scans once, as it always did, so a
     * scheduled start is never refused for want of a verdict; with one kept it reads it and scans nothing.
     */
    @Test fun theFullPassScansOnceWhenNoVerdictIsKeptAndOtherwiseReadsIt() {
        ShadowSystemClock.advanceBy(Duration.ofDays(365))
        cleanDevice()
        Integrity.testScanDelayMs = 300
        val before = Integrity.scans.get()
        assertFalse("a clean phone, scanned for the pass", Integrity.entryRefusedPass(context))
        Thread.sleep(500)                                   // the background scan it also asked for finds this one fresh
        assertEquals("one scan, not two", before + 1, Integrity.scans.get())
        Integrity.testScanDelayMs = 0
        assertFalse(Integrity.entryRefusedPass(context))
        assertEquals("the kept verdict: no scan", before + 1, Integrity.scans.get())
        ShadowBuild.setTags("test-keys")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertTrue("an old one: scanned again, compromised", Integrity.entryRefusedPass(context))
    }

    /**
     * The refresher keeps the verdict fresh off the asking threads, and an entry's own check ([Integrity.reportWithin]) reads it -
     * never waiting behind a scan another thread is making (it was one lock for both).
     */
    @Test fun theRefresherKeepsItFreshAndAReaderNeverWaitsBehindAScan() {
        ShadowSystemClock.advanceBy(Duration.ofDays(365))
        cleanDevice()
        val before = Integrity.scans.get()
        Integrity.startRefresher(context)
        awaitTrue("the refresher's first scan") { Integrity.cachedCompromised(context) != null }
        assertTrue(Integrity.scans.get() > before)
        val kept = Integrity.reportWithin(context, 60_000)
        val n = Integrity.scans.get()
        assertSame("served from the refresher's report", kept, Integrity.reportWithin(context, 60_000))
        assertEquals("no scan of its own", n, Integrity.scans.get())
        // A slow scan under way on another thread (holding the scans' lock): a reader of the fresh report is answered at once.
        Integrity.testScanDelayMs = 3_000
        val other = Thread { Integrity.refresh(context) }.apply { start() }
        Thread.sleep(200)
        val t0 = System.nanoTime()
        assertSame(kept, Integrity.reportWithin(context, 60_000))
        assertTrue("never waited behind the other scan", (System.nanoTime() - t0) / 1_000_000 < 1_000)
        Integrity.testScanDelayMs = 0
        other.join(10_000)
    }
}
