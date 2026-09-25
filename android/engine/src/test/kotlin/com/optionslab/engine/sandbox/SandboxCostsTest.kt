package com.optionslab.engine.sandbox

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** Parity with the desktop sandbox's charges.py and slippage.py (execution_config.json). */
class SandboxCostsTest {
    private val on = SandboxConfig(stopSlippageBps = BigDecimal("10"), spreadFallbackBps = BigDecimal("5"), chargesEnabled = true)
    private fun d(s: String) = BigDecimal(s)

    @Test fun stopsSlipTenBpsAgainstTheOrder() {
        assertEquals(d("159.84"), SandboxCosts.fillPrice(d("160.00"), "SELL", "SL-M", false, null, on))
        assertEquals(d("200.20"), SandboxCosts.fillPrice(d("200.00"), "BUY", "SL-M", false, null, on))
        // An SL never fills past its limit.
        assertEquals(d("159.90"), SandboxCosts.fillPrice(d("160.00"), "SELL", "SL", false, d("159.90"), on))
    }

    @Test fun marketSlipsOnlyWithoutABook() {
        assertEquals(d("200.10"), SandboxCosts.fillPrice(d("200.00"), "BUY", "MARKET", false, null, on))
        assertEquals(d("200.00"), SandboxCosts.fillPrice(d("200.00"), "BUY", "MARKET", true, null, on))
        assertEquals(d("200.00"), SandboxCosts.fillPrice(d("200.00"), "BUY", "LIMIT", false, null, on))
        assertEquals(d("0.01"), SandboxCosts.adverse(d("0.01"), "SELL", d("20000")))
    }

    @Test fun offByDefault() {
        assertEquals(d("160.00"), SandboxCosts.fillPrice(d("160.00"), "SELL", "SL-M", false, null, SandboxConfig()))
    }

    @Test fun chargesPerLeg() {
        // BUY 30 @ 200 = 6,000: 20 + txn 2.1318 + sebi 0.006 + stamp 0.18 + GST 3.98468 = 26.30
        assertEquals(d("26.30"), SandboxCosts.charge("BUY", d("200"), 30))
        // SELL 30 @ 240 = 7,200: 20 + STT 10.80 + txn 2.55816 + sebi 0.0072 + GST 4.06177 = 37.43
        assertEquals(d("37.43"), SandboxCosts.charge("SELL", d("240"), 30))
        assertEquals(d("0.00"), SandboxCosts.charge("BUY", d("0"), 30))
    }
}
