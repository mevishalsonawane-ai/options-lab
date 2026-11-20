package com.optionslab.app.testing

import com.optionslab.app.data.Market

/**
 * 20 Nov 2026 (audit): a position read closed (or turned round) counts only once a second read at least
 * [com.optionslab.app.data.Protections.FLAT_AGREE_MS] later agrees. [clockOn] moves the market's clock on past that (still ticking), so a
 * test's next look is that second read.
 */
object SecondRead {
    fun clockOn(seconds: Long = 6) {
        val at = Market.now().toInstant().plusSeconds(seconds)
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), at))
    }
}
