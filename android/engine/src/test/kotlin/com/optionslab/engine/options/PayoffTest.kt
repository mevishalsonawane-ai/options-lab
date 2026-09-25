package com.optionslab.engine.options

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Strategy Builder against its own TypeScript: `strategy.json` is written
 * by running IraAlgo's `strategyMath.ts`, `strategyTemplates.ts`,
 * `templateResolution.ts` and the Arbitrage page's `computeRow` under node
 * (type stripping only, no bundler), on the fixture chain of
 * `templatePayoff.test.ts`: NIFTY 24,350 in 6% contango, 11- and 39-day expiries.
 */
class PayoffTest {
    private val fx = Json.resource("strategy.json").obj()

    private fun leg(x: Any?): StrategyLeg = x.obj().let { l ->
        StrategyLeg(
            side = Side.valueOf(l.s("side")), lots = l["lots"].lng().toInt(), lotSize = l["lotSize"].lng().toInt(), expiry = l.s("expiry"),
            price = l.d("price"), segment = Segment.valueOf(l.s("segment")), strike = l.dn("strike"),
            optionType = (l["optionType"] as String?)?.let { OptionType.valueOf(it) }, iv = l.d("iv"), active = l["active"] as Boolean,
            symbol = l.s("symbol"), id = l.s("id"), expiryTs = l.dn("expiryTs"), tickSize = l.dn("tickSize"), marketPrice = l.dn("marketPrice"),
            referenceUnderlying = l.dn("referenceUnderlying"), forwardPrice = l.dn("forwardPrice"), exitPrice = l.dn("exitPrice"),
        )
    }

    /** P&L in rupees on lots of 65-75: 1e-9 relative, plus a hair absolute for values that should be ~0. */
    private fun money(e: Double, a: Double, what: String) = near(e, a, 1e-9, 1e-6, what)

    @Test fun `every template's payoff, breakevens, max profit and loss and PoP match strategyMath`() {
        val cases = fx.l("payoffs").map { it.obj() }
        assertEquals(63, cases.size)
        for (c in cases) {
            val name = c.s("name")
            val legs = c.l("legs").map(::leg)
            val now = Instant.parse(c.s("now"))
            val spot = c.d("spot")
            val nearest = Payoff.nearestLegDays(legs, now)
            near(c.d("nearest"), nearest, 1e-12, 0.0, "$name nearest")
            val range = Payoff.payoffPriceRange(spot, legs, c.d("atmIv"), nearest / 365)
            val pr = c.dl("range")
            near(pr[0], range.first, 1e-12, 0.0, "$name range lo"); near(pr[1], range.second, 1e-12, 0.0, "$name range hi")
            assertEquals(c["multi"], Payoff.hasMultipleActiveExpiries(legs), "$name multi")
            money(c.d("netCredit"), Payoff.netCredit(legs), "$name credit")
            money(c.d("totalPremium"), Payoff.totalPremium(legs), "$name premium")

            val p = Payoff.computePayoff(legs, spot, nearest, c.d("daysAtT0"), range, c["steps"].lng().toInt(), c.d("ivShift"), c.d("fallbackIv"), now)
            val r = c.o("result")
            money(r.d("maxProfit"), p.maxProfit, "$name max profit")
            money(r.d("maxLoss"), p.maxLoss, "$name max loss")
            val be = r.dl("breakevens")
            assertEquals(be.size, p.breakevens.size, "$name breakevens $be vs ${p.breakevens}")
            be.zip(p.breakevens).forEach { (e, a) -> near(e, a, 1e-9, 1e-7, "$name breakeven") }
            val samples = r.l("samples").map { it.obj() }
            assertEquals(samples.size, p.samples.size, "$name samples")
            for ((e, a) in samples.zip(p.samples)) {
                near(e.d("underlying"), a.underlying, 1e-12, 1e-9, "$name u")
                money(e.d("expiry"), a.expiry, "$name expiry @ ${a.underlying}")
                money(e.d("tplus0"), a.tplus0, "$name t+n @ ${a.underlying}")
            }
            assertEquals(r.l("zeroCrossings").map { it.lng().toInt() }, p.zeroCrossings, "$name zero crossings")
            val pop = Payoff.probabilityOfProfit(p.samples, spot, c.d("atmIv"), nearest / 365)
            nearOrNull(c.dn("pop"), pop, 1e-9, 1e-12, "$name pop")
            for (g in c.l("pnlGrid").map { it.obj() }) {
                val u = g.d("u")
                money(g.d("now"), Payoff.totalPnlAt(legs, u, 0.0, c.d("ivShift"), c.d("fallbackIv"), now), "$name pnl now @ $u")
                money(g.d("t5"), Payoff.totalPnlAt(legs, u, 5.0, c.d("ivShift"), c.d("fallbackIv"), now), "$name pnl t+5 @ $u")
                g.dl("legs").zip(legs).forEachIndexed { i, (e, l) -> money(e, Payoff.legPnlAt(l, u, 2.0, null, now), "$name leg $i @ $u") }
            }
        }
    }

    @Test fun `unbounded tails are reported as infinities, not window edges`() {
        val byName = fx.l("payoffs").map { it.obj() }.associateBy { it.s("name") }
        assertEquals(Double.POSITIVE_INFINITY, byName.getValue("long_call").o("result").d("maxProfit"))
        assertEquals(Double.NEGATIVE_INFINITY, byName.getValue("short_straddle").o("result").d("maxLoss"))
        // ...and the defined-risk condor stays defined even with its carry-scaled legs.
        assertTrue(byName.getValue("long_iron_condor").o("result").d("maxLoss").isFinite())
    }

    @Test fun `templates, their icons and preview values match strategyTemplates`() {
        val ts = fx.l("templates").map { it.obj() }
        assertEquals(ts.size, StrategyTemplates.ALL.size)
        for ((e, a) in ts.zip(StrategyTemplates.ALL)) {
            assertEquals(e.s("id"), a.id); assertEquals(e.s("name"), a.name); assertEquals(e.s("direction"), a.direction.name)
            assertEquals(e.s("description"), a.description, a.id)
            assertEquals(e["illustrativePreview"], a.illustrativePreview)
            assertEquals(e.s("payoffPath"), a.payoffPath, a.id)
            assertEquals(e.l("legs").map { it.obj().let { l -> listOf(l.s("side"), l.s("optionType"), l["strikeOffset"].lng(), l["lots"].lng(), (l["expiryOffset"] ?: 0L).lng()) } },
                a.legs.map { listOf(it.side.name, it.optionType.name, it.strikeOffset.toLong(), it.lots.toLong(), it.expiryOffset.toLong()) }, a.id)
            for (pv in e.l("preview")) near(pv.list()[1].dbl(), StrategyTemplates.previewValue(a, pv.list()[0].dbl()), 1e-12, 1e-12, "${a.id} preview")
        }
        assertEquals(9, StrategyTemplates.byDirection(Direction.BULLISH).size)
        assertEquals(20, StrategyTemplates.byDirection(Direction.NON_DIRECTIONAL).size)
    }

    @Test fun `strike and expiry resolution match templateResolution`() {
        val r = fx.o("resolution")
        for (c in r.l("strike").map { it.obj() }) {
            val a = c.l("args")
            assertEquals(c.dn("result"), StrategyTemplates.resolveStrikeOffset(a[0].list().map { it.dbl() }, a[1].dbl(), a[2].lng().toInt()), "$a")
        }
        for (c in r.l("expiry").map { it.obj() }) {
            val a = c.l("args")
            assertEquals(c["result"], StrategyTemplates.resolveExpiryOffset(a[0].list().map { it.str() }, a[1].str(), a[2].lng().toInt()), "$a")
        }
        for (c in r.l("topology").map { it.obj() }) {
            val legs = c.l("legs").map { it.obj().let { l -> l["strikeOffset"].lng().toInt() to l.dn("resolvedStrike") } }
            assertEquals(c.l("result").map { it.str() }, StrategyTemplates.validateStrikeTopology(legs))
        }
        for (n in r.l("normalize")) assertEquals(n.list()[1], StrategyTemplates.normalizeExpiryCode(n.list()[0].str()))
        assertEquals(false, StrategyTemplates.canReuseChainContract("11AUG26", "04-AUG-26"))
        assertEquals(true, StrategyTemplates.canReuseChainContract("04AUG26", "04-AUG-26"))
    }

    @Test fun `the template dialog resolves legs on the listed ladder and refuses what it cannot place`() {
        // Worked from TemplateDialog.tsx and templateResolution.test.ts (PB-H4, PB-H6, PG-21, PG-22).
        val strikes = listOf(24_000.0, 24_100.0, 24_200.0, 24_250.0, 24_300.0, 24_350.0, 24_400.0, 24_450.0, 24_500.0, 24_600.0, 24_700.0)
        val chain = strikes.map { k ->
            ChainRow(k, OptLeg(Payoff.buildOptionSymbol("NIFTY", "25AUG26", k, OptionType.CE), 100.0 + maxOf(0.0, 24_350 - k)),
                if (k == 24_000.0) null else OptLeg(Payoff.buildOptionSymbol("NIFTY", "25AUG26", k, OptionType.PE), 100.0 + maxOf(0.0, k - 24_350)))
        }
        val expiries = listOf("22SEP26", "25AUG26")
        val condor = StrategyTemplates.byId("long_iron_condor")!!
        val ok = StrategyTemplates.resolve(condor, chain, 24_350.0, "25AUG26", expiries, lotMultiplier = 2)
        assertTrue(ok.ok, "${ok.errors}")
        assertEquals(listOf(24_100.0, 24_250.0, 24_450.0, 24_600.0), ok.legs.map { it.strike }) // walks listed strikes, not +/- 2 x 50
        assertEquals(listOf(2, 2, 2, 2), ok.legs.map { it.lots })
        assertEquals(100.0, ok.legs[0].price)
        assertEquals("NIFTY25AUG2624100PE", ok.legs[0].symbol)

        val batman = StrategyTemplates.resolve(StrategyTemplates.byId("batman_strategy")!!, chain, 24_350.0, "25AUG26", expiries)
        assertEquals(listOf("One or more template strikes are outside the loaded option chain."), batman.errors) // no clamping
        assertEquals(setOf(0, 1, 2, 3), batman.strikeErrorIndexes)

        val missingPut = StrategyTemplates.resolve(StrategyTemplates.byId("bear_put_spread")!!, chain, 24_200.0, "25AUG26", expiries)
        assertEquals(listOf("The required PE contract at 24000 is not available in the loaded option chain."), missingPut.errors)

        val cal = StrategyTemplates.resolve(StrategyTemplates.byId("call_calendar")!!, chain, 24_350.0, "25AUG26", expiries)
        assertTrue(cal.ok)
        assertEquals(listOf("25AUG26", "22SEP26"), cal.legs.map { it.expiry })
        assertEquals(0.0, cal.legs[1].price); assertNull(cal.legs[1].symbol) // another expiry's contract is not in this chain
        val noLater = StrategyTemplates.resolve(StrategyTemplates.byId("call_calendar")!!, chain, 24_350.0, "22SEP26", expiries)
        assertEquals(listOf("A later expiry is required for Call Calendar."), noLater.errors)
        assertTrue(noLater.expiryInvalid)

        val legs = StrategyTemplates.toStrategyLegs(cal, "NIFTY", 65)
        assertEquals("NIFTY22SEP2624350CE", legs[1].symbol)
    }

    @Test fun `small pieces - moneyness, Black-Scholes, bands, expiry parsing, symbols`() {
        for (c in fx.l("moneyness").map { it.obj() }) {
            val a = c.l("args")
            val m = Payoff.strikeMoneyness(a[0].dblOrNull(), a[1].dblOrNull(), a[2].dbl(), OptionType.valueOf(a[3].str()))
            val e = c["result"]?.obj()
            if (e == null) assertNull(m, "$a") else assertEquals(Moneyness(e.s("label"), e.s("kind"), e["steps"].lng().toInt()), m, "$a")
        }
        for (c in fx.l("bs").map { it.obj() }) {
            val t = OptionType.valueOf(c.s("type"))
            val args = listOf(c.d("spot"), c.d("strike"), c.d("t"), c.d("iv"), c.d("r"), c.d("q"))
            near(c.d("price"), Payoff.bsPrice(t, args[0], args[1], args[2], args[3], args[4], args[5]), 1e-12, 1e-12, "bs $args")
            val g = Payoff.bsGreeks(t, args[0], args[1], args[2], args[3], args[4], args[5])
            val e = c.o("greeks")
            near(e.d("delta"), g.delta, 1e-12, 1e-14, "delta $args"); near(e.d("gamma"), g.gamma, 1e-12, 1e-14, "gamma $args")
            near(e.d("theta"), g.theta, 1e-12, 1e-12, "theta $args"); near(e.d("vega"), g.vega, 1e-12, 1e-12, "vega $args")
        }
        for (p in fx.l("normCdf")) near(p.list()[1].dbl(), Payoff.normCdf(p.list()[0].dbl()), 1e-14, 1e-16, "cdf")
        for (c in fx.l("bands").map { it.obj() }) {
            val a = c.l("args").map { it.dbl() }
            val b = Payoff.lognormalPriceBand(a[0], a[1], a[2], a[3])
            val e = c["result"]?.obj()
            if (e == null) assertNull(b) else { assertNotNull(b); near(e.d("lower"), b.first, 1e-12, 0.0, "lo"); near(e.d("upper"), b.second, 1e-12, 0.0, "hi") }
        }
        val now = Instant.parse("2026-08-14T05:00:00Z")
        for (c in fx.l("expiryDates").map { it.obj() }) {
            assertEquals(c["ms"]?.lng(), Payoff.parseExpiryDate(c.s("e"))?.toEpochMilli(), c.s("e"))
            near(c.d("days"), Payoff.daysToExpiry(c.s("e"), now), 1e-12, 0.0, c.s("e"))
        }
        for (c in fx.l("symbols").map { it.obj() }) {
            val a = c.l("args")
            assertEquals(c.s("result"), Payoff.buildOptionSymbol(a[0].str(), a[1].str(), a[2].dbl(), OptionType.valueOf(a[3].str())))
        }
        assertEquals("NIFTY25AUG26FUT", Payoff.buildFutureSymbol("NIFTY", "25AUG26"))
    }

    @Test fun `arbitrage scanner rows match the page's computeRow`() {
        val pair = CalendarPair("NFO:NIFTY:near-next", "NIFTY", "NFO", "near-next", FutContract("N", "NFO", "NIFTY", ""), FutContract("F", "NFO", "NIFTY", ""))
        fun q(x: Any?) = x?.obj()?.let { Quote(it.dn("bid"), it.dn("ask"), it.dn("ltp"), it["ts"]?.lng()) }
        for (c in fx.l("arbitrageRows").map { it.obj() }) {
            val r = ChainAnalytics.spreadRow(pair, q(c["near"]), q(c["far"]), c["now"].lng())
            val e = c.o("row")
            assertEquals(e.dn("nearMid"), r.nearMid); assertEquals(e.dn("farMid"), r.farMid); assertEquals(e.dn("rawSpread"), r.rawSpread)
            assertEquals(e.dn("bestCredit"), r.bestCredit); assertEquals(e.dn("spreadPct"), r.spreadPct)
            assertEquals(e["direction"], r.direction?.name)
            assertEquals(e["fresh"], r.fresh); assertEquals(e["liquid"], r.liquid); assertEquals(e["hasData"], r.hasData)
        }
    }

    @Test fun `strategy builder chart reference follows strategy_builder_reference_service`() {
        assertEquals("NIFTY" to "NSE_INDEX", Payoff.chartReference("nifty", "NFO"))
        assertEquals("SENSEX" to "BSE_INDEX", Payoff.chartReference("SENSEX", "BFO"))
        assertEquals("RELIANCE" to "NSE", Payoff.chartReference("RELIANCE", "NFO"))
        assertEquals("NIFTY25AUG26FUT" to "NFO", Payoff.chartReference("NIFTY", "NFO", "nifty25aug26fut", "nfo"))
        assertNull(Payoff.chartReference("CRUDEOIL", "MCX"))
        assertEquals("BSE", Payoff.quoteExchange("TCS", "BFO"))
    }
}
