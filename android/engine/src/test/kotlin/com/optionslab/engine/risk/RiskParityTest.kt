package com.optionslab.engine.risk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The phone's risk core must decide exactly what IraAlgo's `services/risk`
 * decides. Every expectation here was written by running the Python
 * (`src/test/resources/risk/gen_risk.py`) over the repo's own golden vectors
 * (`test/risk/vectors.json`), exhaustive grids of every rule and boundary,
 * seeded random loose states full of aliases and junk values, and ratchet
 * sequences where each decision is written back before the next tick.
 *
 * Comparison is exact, detail text included: the arithmetic is the same IEEE
 * operations in the same order, so there is no tolerance to hide behind.
 */
class RiskParityTest {
    private fun check(file: String, run: (Map<String, Any?>) -> Pair<Any?, Any?>): Int {
        val cases = Fixtures.cases(file)
        val failures = ArrayList<String>()
        cases.forEachIndexed { i, c ->
            val (expected, actual) = run(c)
            Fixtures.diff(expected, actual)?.let { failures.add("#$i ${c["source"] ?: ""}: $it\n   input=$c") }
        }
        if (failures.isNotEmpty()) fail("${failures.size}/${cases.size} differ from Python:\n" + failures.take(5).joinToString("\n"))
        return cases.size
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(c: Map<String, Any?>, key: String = "state") = c[key] as Map<String, Any?>

    @Test fun `every per-position decision matches Python`() {
        val n = check("risk/position.json.gz") { c -> c["expected"] to Risk.evaluatePositionState(state(c), c["ltp"]).asDict() }
        assertTrue(n > 10_000, "only $n cases")
    }

    @Test fun `the legacy evaluate_trail adapter matches Python`() {
        check("risk/position.json.gz") { c -> c["trail"] to Risk.evaluateTrail(state(c), c["ltp"]) }
    }

    @Test fun `the repo's golden vectors are all present and pass`() {
        val vectors = Fixtures.cases("risk/position.json.gz").filter { (it["source"] as String).startsWith("vectors:") }
        assertEquals(35, vectors.size, "vectors.json case count changed; regenerate")
    }

    @Test fun `configuration validation matches Python`() {
        check("risk/validate.json.gz") { c -> c["expected"] to Risk.validatePosition(PositionRisk.fromState(state(c)), c["ltp"]) }
    }

    @Test fun `points conversion, side, trail mode, pnl and price formatting match Python`() {
        check("risk/helpers.json.gz") { c ->
            @Suppress("UNCHECKED_CAST")
            val a = c["args"] as List<Any?>
            c["expected"] to when (c["fn"]) {
                "stop_from_points" -> RiskValues.stopFromPoints(a[0], a[1], a[2])
                "target_from_points" -> RiskValues.targetFromPoints(a[0], a[1], a[2])
                "normalise_side" -> RiskValues.normaliseSide(a[0]).wire
                "side_from_quantity" -> RiskValues.sideFromQuantity(a[0]).wire
                "normalise_trail_mode" -> RiskValues.normaliseTrailMode(a[0]).wire
                "format_price" -> RiskValues.formatPrice((a[0] as Number).toDouble())
                "position_pnl" -> Risk.positionPnl(a[0], a[1], (a[2] as Number).toDouble(), a[3])
                else -> fail("unknown fn ${c["fn"]}")
            }
        }
    }

    @Test fun `every aggregate decision matches Python`() {
        val n = check("risk/aggregate.json.gz") { c ->
            c["expected"] to Risk.evaluateAggregateState(
                state(c), (c["realized"] as Number).toDouble(), (c["unrealized"] as Number).toDouble(),
            ).asDict()
        }
        assertTrue(n > 10_000, "only $n cases")
    }

    @Test fun `aggregate pnl matches Python`() {
        @Suppress("UNCHECKED_CAST")
        check("risk/pnl.json.gz") { c -> c["expected"] to Risk.aggregatePnl(c["positions"] as List<Any>).asDict() }
    }

    @Test fun `trail to entry matches Python`() {
        @Suppress("UNCHECKED_CAST")
        check("risk/trail_to_entry.json.gz") { c ->
            c["expected"] to Risk.trailStopsToEntry(
                c["positions"] as List<Any>,
                (c["exclude"] as List<String>),
                c["last_prices"] as Map<String, Any?>?,
            ).asDict()
        }
    }
}
