package com.optionslab.engine.risk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The three gates on a real order (22 Oct): Boss's own, and an arm's live trade; nothing opens by itself. */
class OrderGatesTest {
    @Test fun yesMustBeTypedExactly() {
        for (ok in listOf("yes", "YES", " yes ", "Yes")) assertTrue(OrderGates.yesTyped(ok), ok)
        for (no in listOf("", "y", "yess", "no", "yes please", null)) assertFalse(OrderGates.yesTyped(no), no ?: "null")
    }

    @Test fun bossOwnOrderNeedsAllThree() {
        val shut = OrderGates.manual(checksPassed = false, fingerprintOk = true, yesRequired = true, typed = "", pinDone = false)
        assertEquals(listOf(false, false, false), shut.map { it.done })
        assertEquals("✗ Checks passed, fingerprint matches (a check refused it)", shut[0].line())
        assertEquals("✗ \"yes\" typed for this order (type yes below)", shut[1].line())
        assertEquals("✗ Your PIN or fingerprint (asked when you send)", shut[2].line())
        assertEquals("0 of 3 gates open", OrderGates.summary(shut))
        val changed = OrderGates.manual(true, false, true, "yes", false)
        assertEquals("✗ Checks passed, fingerprint matches (the order changed after its check)", changed[0].line())
        assertTrue(changed[1].done)
        val open = OrderGates.manual(true, true, true, "yes", true)
        assertTrue(OrderGates.allOpen(open))
        assertEquals(listOf("✓ Checks passed, fingerprint matches", "✓ \"yes\" typed for this order", "✓ Your PIN or fingerprint"), open.map { it.line() })
        // The setting off: gate 2 counts as open and says so; the PIN gate never goes away.
        val noYes = OrderGates.manual(true, true, yesRequired = false, typed = null, pinDone = false)
        assertEquals("✓ \"yes\" typed for this order (off in your settings)", noYes[1].line())
        assertFalse(noYes[2].done)
        assertFalse(OrderGates.allOpen(noYes))
    }

    @Test fun anArmsLiveTradeNeedsItsSwitchesThePinAndTheFingerprint() {
        val off = OrderGates.arm(tradeLive = false, aiLive = true, armedWithPin = false, fingerprint = null, refusal = null)
        assertEquals("✗ Trade live and \"AI trades go live\" on (its switch is off)", off[0].line())
        assertEquals("✗ PIN at arming (armed only with your PIN)", off[1].line())
        assertEquals("✗ Order fingerprint at the door (checked on each live entry; none sent yet)", off[2].line())
        assertEquals("✗ Trade live and \"AI trades go live\" on (AI trades go live is off)", OrderGates.arm(true, false, true, null, null)[0].line())
        assertEquals("✗ Trade live and \"AI trades go live\" on (AI trades not read yet)", OrderGates.arm(true, null, true, null, null)[0].line())
        val refused = OrderGates.arm(true, true, true, "abcd", "quantity 30 → 60")
        assertFalse(refused[2].done)
        assertEquals("✗ Order fingerprint at the door (last send refused: quantity 30 → 60)", refused[2].line())
        val open = OrderGates.arm(true, true, true, "1234abcd5678ef00", null)
        assertTrue(OrderGates.allOpen(open))
        assertEquals("✓ Order fingerprint at the door (last matched 1234abcd5678ef00)", open[2].line())
        assertEquals("3 of 3 gates open", OrderGates.summary(open))
    }
}
