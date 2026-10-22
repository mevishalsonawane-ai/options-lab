package com.optionslab.app.testing

import com.optionslab.app.data.Market
import com.optionslab.app.data.OrderVerdicts
import com.optionslab.engine.Kite
import com.optionslab.engine.risk.OrderFingerprint

/**
 * TEST: a verdict that clears [o] today, as the sender's own check would have stamped it (22 Oct, the order fingerprint): the
 * tests that send an entry straight to [com.optionslab.app.data.Broker.placeOrder] pass one, so the door's fingerprint look
 * finds the order it is about to send. [arm] as the test passes it to placeOrder (the source the door computes).
 */
object Cleared {
    fun live(o: Kite.Order, arm: String? = null, stop: Double? = null, target: Double? = null): OrderFingerprint.Verdict =
        OrderVerdicts.cleared(OrderVerdicts.liveFields(o, Market.today(), OrderVerdicts.sourceOf(o, arm), stop, target), "test")
}
