package com.optionslab.app.testing

import com.optionslab.ira.OrderFlow

/** Order-flow reads for the tests: [buyers] the buyers' share (72 buyers, 30 sellers at 70), warm, read at [atSec], no trap. */
object FlowFixtures {
    fun read(underlying: String, buyers: Int, atSec: Long): OrderFlow.Read = OrderFlow.Read(
        underlying, atSec, OrderFlow.sideOf(buyers), OrderFlow.strength(buyers), buyers, true, 55_000.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, Double.NaN, null, null, emptyMap(),
    // The executed flow (tick-rule signed volume) on the same side as the share: CONFIRM needs it to agree.
    ).copy(cvd60 = (buyers - 50) * 10.0)
}
