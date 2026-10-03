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
        for (never in listOf("READ_SMS", "READ_CONTACTS", "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "READ_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW"))
            assertFalse("$never must never be requested", held.any { it.endsWith(".$never") })
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
}
