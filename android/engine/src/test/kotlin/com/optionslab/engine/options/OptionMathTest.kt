package com.optionslab.engine.options

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Black-76 against opengreeks and `option_greeks_service.py`. The fixture
 * `greeks.json` is written by calling those functions directly, with the clock
 * frozen so time to expiry is reproducible.
 */
class OptionMathTest {
    private val fx = Json.resource("greeks.json").obj()
    private fun type(flag: String) = if (flag == "c") OptionType.CE else OptionType.PE

    @Test fun `price and greeks match opengreeks across moneyness, tenor, rate and vol`() {
        val cases = fx.l("black")
        assertTrue(cases.size > 1000)
        for (c in cases.map { it.obj() }) {
            val t = type(c.s("flag"))
            val (f, k, yrs, r, s) = listOf(c.d("F"), c.d("K"), c.d("t"), c.d("r"), c.d("sigma"))
            val what = "$t F=$f K=$k t=$yrs r=$r s=$s"
            near(c.d("price"), OptionMath.price(t, f, k, yrs, r, s), 1e-9, 1e-9, "$what price")
            val g = OptionMath.greeks(t, f, k, yrs, r, s)
            near(c.d("delta"), g.delta, 1e-9, 1e-12, "$what delta")
            near(c.d("gamma"), g.gamma, 1e-9, 1e-15, "$what gamma")
            near(c.d("theta"), g.theta, 1e-9, 1e-10, "$what theta")
            near(c.d("vega"), g.vega, 1e-9, 1e-10, "$what vega")
            near(c.d("rho"), g.rho, 1e-9, 1e-10, "$what rho")
        }
    }

    @Test fun `implied volatility matches opengreeks, including its refusals and clamps`() {
        for (c in fx.l("iv").map { it.obj() }) {
            val t = type(c.s("flag"))
            val got = OptionMath.solveIv(c.d("price"), t, c.d("F"), c.d("K"), c.d("t"), c.d("r"))
            val what = "iv ${c}"
            val err = c["error"] as String?
            when {
                err == null -> near(c.d("iv"), (got as OptionMath.IvSolve.Solved).sigma, 1e-6, 1e-12, what)
                "below intrinsic" in err -> assertEquals(OptionMath.IvSolve.Failed(OptionMath.IvFailure.BELOW_INTRINSIC), got, what)
                "maximum" in err -> assertEquals(OptionMath.IvSolve.Failed(OptionMath.IvFailure.ABOVE_MAXIMUM), got, what)
                else -> error("unexpected $err")
            }
        }
    }

    @Test fun `calculate_greeks end to end - symbol, 15-30 cut-off, fallbacks, rounding`() {
        val now = ZonedDateTime.of(2025, 11, 18, 10, 17, 23, 500_000_000, OptionMath.IST)
        for (c in fx.l("calculate_greeks").map { it.obj() }) {
            val sym = OptionMath.parseOptionSymbol(c.s("symbol"))
            val years = OptionMath.timeToExpiryYears(now, sym.expiry, OptionMath.expiryCutoff(c.s("exchange")))
            val rate = c.dn("rate") ?: 0.0
            val raw = OptionMath.legGreeks(sym.type, c.d("spot"), sym.strike, years, c.d("price"), rate)
            val rounded = OptionMath.legGreeksRounded(sym.type, c.d("spot"), sym.strike, years, c.d("price"), rate)
            val what = c.s("symbol") + " @ " + c.d("price")
            if (c["ok"] != true) { assertNull(raw, what); continue }
            assertNotNull(raw, what); assertNotNull(rounded, what)
            val r = c.o("raw"); val p = c.o("response")
            near(r.d("days_to_expiry"), years * 365.0, 1e-12, 1e-12, "$what days")
            near(r.d("implied_volatility"), raw.ivPct, 1e-7, 1e-10, "$what iv")
            val rg = r.o("greeks"); val pg = p.o("greeks")
            near(rg.d("delta"), raw.greeks.delta, 1e-7, 1e-12, "$what delta")
            near(rg.d("gamma"), raw.greeks.gamma, 1e-7, 1e-15, "$what gamma")
            near(rg.d("theta"), raw.greeks.theta, 1e-7, 1e-12, "$what theta")
            near(rg.d("vega"), raw.greeks.vega, 1e-7, 1e-12, "$what vega")
            near(rg.d("rho"), raw.greeks.rho, 1e-7, 1e-12, "$what rho")
            assertEquals(p.d("implied_volatility"), rounded.ivPct, "$what iv shown")
            assertEquals(listOf(pg.d("delta"), pg.d("gamma"), pg.d("theta"), pg.d("vega"), pg.d("rho")),
                with(rounded.greeks) { listOf(delta, gamma, theta, vega, rho) }, "$what greeks shown")
            assertEquals(p["note"] != null, raw.theoretical, "$what theoretical")
            p["time_value"]?.let { assertEquals(it.dbl(), rounded.timeValue, "$what time value") }
            p["intrinsic_value"]?.let { assertEquals(it.dbl(), rounded.intrinsic, "$what intrinsic") }
        }
    }

    @Test fun `time to expiry uses the exchange cut-off, 365-day years and the 0_0001 floor`() {
        for (c in fx.l("time").map { it.obj() }) {
            val now = ZonedDateTime.parse(c.s("now"))
            val exp = OptionMath.expiryInstant(OptionMath.parseExpiryCode(c.s("expiry")), OptionMath.expiryCutoff(c.s("exchange")))
            val (y, d) = OptionMath.timeToExpiry(now, exp)
            near(c.d("years"), y, 1e-12, 1e-15, "years $c")
            near(c.d("days"), d, 1e-12, 1e-13, "days $c")
        }
        for ((ex, hhmm) in fx.o("expiry_cutoff")) assertEquals(hhmm, OptionMath.expiryCutoff(ex).toString(), ex)
        assertEquals("28NOV25", OptionMath.expiryCode(OptionMath.parseExpiryCode("28NOV25")))
    }

    @Test fun `vectorised chain greeks match calculate_chain_greeks`() {
        for (c in fx.l("chain_greeks").map { it.obj() }) {
            val strikes = c.dl("strikes")
            val (ce, pe) = OptionMath.chainGreeks(strikes, c.l("ce").map { it.dblOrNull() }, c.l("pe").map { it.dblOrNull() },
                c.d("forward"), c.d("t"), c.dn("rate") ?: 0.0)
            for ((py, kt) in listOf(c.l("ce_greeks") to ce, c.l("pe_greeks") to pe)) {
                assertEquals(py.size, kt.size)
                for (i in py.indices) {
                    val e = py[i]?.obj()
                    val a = kt[i]
                    if (e == null) { assertNull(a, "strike ${strikes[i]}"); continue }
                    assertNotNull(a, "strike ${strikes[i]}")
                    // opengreeks' *_array IV solve is a hair less exact than its scalar one, so a
                    // value can land one unit away in the last displayed decimal.
                    near(e.d("iv"), a.ivPct, 0.0, 0.0100001, "iv ${strikes[i]}")
                    near(e.d("delta"), a.delta, 0.0, 0.0001001, "delta ${strikes[i]}")
                    near(e.d("gamma"), a.gamma, 0.0, 0.000001001, "gamma ${strikes[i]}")
                    near(e.d("theta"), a.theta, 0.0, 0.0001001, "theta ${strikes[i]}")
                    near(e.d("vega"), a.vega, 0.0, 0.0001001, "vega ${strikes[i]}")
                }
            }
        }
    }

    @Test fun `helpers - parity forward, mid price, python rounding`() {
        assertEquals(24_380.25, OptionMath.forwardFromParity(24_350.0, 120.5, 90.25, 24_000.0))
        assertEquals(24_000.0, OptionMath.forwardFromParity(24_350.0, 0.0, 90.25, 24_000.0))
        assertEquals(10.5, OptionMath.priceForGreeks(9.0, 10.0, 11.0))
        assertEquals(9.0, OptionMath.priceForGreeks(9.0, 11.0, 10.0))
        assertEquals(0.0, OptionMath.priceForGreeks(null, null, 10.0))
        assertEquals(2.67, pyRound(2.675, 2)) // 2.675 is 2.67499999... in binary, as Python knows
        assertEquals(0.12, pyRound(0.125, 2)) // an exact tie rounds to even
        assertEquals(-0.0, pyRound(-0.001, 2))
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
