package com.optionslab.engine.options

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every chain screen against IraAlgo's own service, run by `chain.json`'s
 * generator on a synthetic 95-strike NIFTY chain with only the broker calls
 * replaced. The numbers compared are the ones IraAlgo's JSON carries, so most
 * comparisons are exact.
 */
class ChainAnalyticsTest {
    private val fx = Json.resource("chain.json").obj()
    private val now = ZonedDateTime.parse(fx.s("now"))
    private val spot = fx.d("spot")
    private val expiry = OptionMath.parseExpiryCode(fx.s("expiry"))
    private val tYears = OptionMath.timeToExpiryYears(now, expiry, OptionMath.expiryCutoff("NFO"))
    private val prevOi = fx.o("prev_oi")

    private fun leg(x: Any?): OptLeg? = x?.obj()?.let {
        OptLeg(it.s("symbol"), it.d("ltp"), oi = it["oi"].lng(), volume = it["volume"].lng(),
            prevOi = prevOi[it.s("symbol")]?.lng(), lotSize = it["lotsize"]?.lng()?.toInt())
    }

    private fun rows(x: Any?): List<ChainRow> = x.list().map { r -> r.obj().let { ChainRow(it.d("strike"), leg(it["ce"]), leg(it["pe"])) } }
    private val full = rows(fx["chain"])

    private fun window(count: Int): List<ChainRow> {
        val (atm, w) = ChainAnalytics.chainWindow(full, spot, count)
        assertEquals(24_350.0, atm)
        return w
    }

    @Test fun `ATM, labels and offsets follow option_chain_service`() {
        val strikes = fx.dl("strikes")
        assertEquals(24_350.0, ChainAnalytics.atmStrike(spot, strikes))
        assertEquals(24_350.0, ChainAnalytics.atmStrike(24_375.0, strikes)) // a tie goes to the lower strike
        assertNull(ChainAnalytics.atmStrike(Double.NaN, strikes))
        val labels = ChainAnalytics.strikeLabels(strikes, 24_350.0, 2)
        assertEquals(listOf("ITM2", "ITM1", "ATM", "OTM1", "OTM2"), labels.map { it.ceLabel })
        assertEquals(listOf("OTM2", "OTM1", "ATM", "ITM1", "ITM2"), labels.map { it.peLabel })
        assertEquals(24_250.0, ChainAnalytics.offsetStrike(24_350.0, "ITM2", OptionType.CE, strikes))
        assertEquals(24_450.0, ChainAnalytics.offsetStrike(24_350.0, "itm2", OptionType.PE, strikes))
        assertEquals(24_250.0, ChainAnalytics.offsetStrike(24_350.0, "OTM2", OptionType.PE, strikes))
        assertNull(ChainAnalytics.offsetStrike(24_350.0, "OTM99", OptionType.CE, strikes))
        assertEquals(47, window(23).size)
    }

    private fun checkOi(py: Map<String, Any?>, oi: OiData) {
        assertEquals(py["lot_size"].lng().toInt(), oi.lotSize)
        assertEquals(py.d("pcr_oi"), oi.pcr.pcrOi)
        assertEquals(py.d("pcr_volume"), oi.pcr.pcrVolume)
    }

    @Test fun `OI tracker and max pain match oi_tracker_service`() {
        val w = window(fx["oi_window"].lng().toInt())
        val py = fx.o("oi_data")
        val oi = ChainAnalytics.oiData(w, spot, 24_420.5, 24_350.0)
        checkOi(py, oi)
        assertEquals(py["total_ce_oi"].lng(), oi.pcr.totalCeOi)
        assertEquals(py["total_pe_oi"].lng(), oi.pcr.totalPeOi)
        assertEquals(py.l("chain").map { it.obj().let { c -> Triple(c.d("strike"), c["ce_oi"].lng(), c["pe_oi"].lng()) } },
            oi.chain.map { Triple(it.strike, it.ceOi, it.peOi) })

        val mp = assertNotNull(ChainAnalytics.maxPain(w, spot, 24_420.5, 24_350.0))
        val pm = fx.o("max_pain")
        assertEquals(pm.d("max_pain_strike"), mp.maxPainStrike)
        checkOi(pm, mp.oi)
        val pains = pm.l("pain_data").map { it.obj() }
        assertEquals(pains.size, mp.painData.size)
        for ((p, k) in pains.zip(mp.painData)) {
            assertEquals(listOf(p.d("strike"), p.d("ce_pain"), p.d("pe_pain"), p.d("total_pain"), p.d("total_pain_cr")),
                listOf(k.strike, k.cePain, k.pePain, k.totalPain, k.totalPainCr))
        }
    }

    @Test fun `OI profile change butterfly matches oi_profile_service`() {
        val w = window(fx["oi_profile_window"].lng().toInt())
        val py = fx.o("oi_profile")
        val p = ChainAnalytics.oiProfile(w, spot, 24_350.0)
        assertEquals(py["lot_size"].lng().toInt(), p.lotSize)
        val chain = py.l("oi_chain").map { it.obj() }
        assertEquals(chain.size, p.chain.size)
        for ((e, a) in chain.zip(p.chain)) {
            assertEquals(e.d("strike"), a.strike)
            assertEquals(e["ce_oi"].lng(), a.ceOi); assertEquals(e["pe_oi"].lng(), a.peOi)
            assertEquals(e.d("ce_oi_change"), a.ceOiChange, "ce change ${a.strike}")
            assertEquals(e.d("pe_oi_change"), a.peOiChange, "pe change ${a.strike}")
        }
    }

    @Test fun `IV smile matches iv_smile_service`() {
        val w = window(fx["iv_smile_window"].lng().toInt())
        val py = fx.o("iv_smile")
        val s = assertNotNull(ChainAnalytics.ivSmile(w, spot, 24_350.0, tYears))
        assertEquals(py.dn("atm_iv"), s.atmIv)
        assertEquals(py.dn("skew"), s.skew)
        val chain = py.l("chain").map { it.obj() }
        assertEquals(chain.size, s.chain.size)
        var both = 0
        for ((e, a) in chain.zip(s.chain)) {
            assertEquals(e.d("strike"), a.strike)
            assertEquals(e.dn("ce_iv"), a.ceIv, "ce iv ${a.strike}")
            assertEquals(e.dn("pe_iv"), a.peIv, "pe iv ${a.strike}")
            if (a.ceIv != null && a.peIv != null) both++
        }
        assertTrue(both > 20)
    }

    @Test fun `GEX matches gex_service, 6-dp gamma and all`() {
        val w = window(fx["gex_window"].lng().toInt())
        val py = fx.o("gex")
        val g = assertNotNull(ChainAnalytics.gex(w, spot, 24_350.0, tYears, futuresPrice = 24_420.5))
        assertEquals(py["lot_size"].lng().toInt(), g.lotSize)
        assertEquals(py.d("pcr_oi"), g.pcrOi)
        assertEquals(py["total_ce_oi"].lng(), g.totalCeOi)
        assertEquals(py["total_pe_oi"].lng(), g.totalPeOi)
        assertEquals(py.d("total_ce_gex"), g.totalCeGex)
        assertEquals(py.d("total_pe_gex"), g.totalPeGex)
        assertEquals(py.d("total_net_gex"), g.totalNetGex)
        for ((e, a) in py.l("chain").map { it.obj() }.zipStrict(g.chain)) {
            assertEquals(listOf(e.d("strike"), e.d("ce_gamma"), e.d("pe_gamma"), e.d("ce_gex"), e.d("pe_gex"), e.d("net_gex")),
                listOf(a.strike, a.ceGamma, a.peGamma, a.ceGex, a.peGex, a.netGex), "strike ${a.strike}")
        }
    }

    @Test fun `gamma density matches gamma_density_service, with and without a forward`() {
        val t = fx.o("gamma_density_t")
        val (years, days) = OptionMath.timeToExpiry(now, OptionMath.expiryInstant(expiry, OptionMath.expiryCutoff("NFO")))
        near(t.d("years"), years, 1e-12, 0.0, "t"); near(t.d("days"), days, 1e-12, 0.0, "dte")
        val w = window(fx["gamma_density_window"].lng().toInt())
        for ((key, fwd, rate) in listOf(Triple("gamma_density", 24_392.15, 0.0), Triple("gamma_density_spot", null, 6.5))) {
            val py = fx.o(key)
            val g = assertNotNull(ChainAnalytics.gammaDensity(w, spot, 24_350.0, fwd, years, days, rate))
            assertEquals(py.d("forward_price"), g.forwardPrice)
            assertEquals(py.d("atm_iv"), g.atmIv, key)
            assertEquals(py.d("dte_days"), g.dteDays)
            assertEquals(py.d("interest_rate"), g.interestRate)
            assertEquals(py.dn("peak_intraday_strike"), g.peakIntradayStrike, key)
            assertEquals(py.dn("peak_expiry_strike"), g.peakExpiryStrike, key)
            for ((name, band) in listOf("intraday_band" to g.intradayBand, "expiry_band" to g.expiryBand)) {
                val b = py.o(name)
                assertEquals(listOf(b.d("sigma_move"), b.d("one_sigma_low"), b.d("one_sigma_high"), b.d("two_sigma_low"), b.d("two_sigma_high")),
                    listOf(band.sigmaMove, band.oneSigmaLow, band.oneSigmaHigh, band.twoSigmaLow, band.twoSigmaHigh), "$key $name")
            }
            assertEquals(py.d("sigma_move"), g.intradayBand.sigmaMove)
            for ((e, a) in py.l("chain").map { it.obj() }.zipStrict(g.chain)) {
                assertEquals(e.d("strike"), a.strike)
                assertEquals(e.dn("iv"), a.iv, "$key iv ${a.strike}")
                near(e.d("density_intraday"), a.densityIntraday, 1e-7, 1e-9, "$key intraday ${a.strike}")
                near(e.d("density_expiry"), a.densityExpiry, 1e-7, 1e-9, "$key expiry ${a.strike}")
            }
        }
    }

    @Test fun `synthetic future matches synthetic_future_service`() {
        val py = fx.o("synthetic_future")
        val s = assertNotNull(ChainAnalytics.syntheticFuture(full, spot))
        assertEquals(py.d("atm_strike"), s.atmStrike)
        assertEquals(py.d("synthetic_future_price"), s.price)
        near(s.price - spot, s.basis, 0.0, 0.005, "basis")
        assertEquals(ChainAnalytics.forwardFromChain(full, 24_350.0, spot), pyRound(s.price, 4), "chain parity forward")
    }

    @Test fun `vol surface matches vol_surface_service`() {
        val chains = fx.o("surface_chains").mapValues { rows(it.value) }
        for (c in fx.l("vol_surface").map { it.obj() }) {
            val codes = c.l("expiries").map { it.str() }
            val input = codes.map { SurfaceExpiry(it, OptionMath.parseExpiryCode(it), chains.getValue(it)) }
            val v = assertNotNull(ChainAnalytics.volSurface(spot, input, c["strike_count"].lng().toInt(), now))
            val py = c.o("result").o("data")
            assertEquals(py.d("atm_strike"), v.atmStrike)
            assertEquals(py.dl("strikes"), v.strikes, "strikes $codes")
            assertEquals(py.l("expiries").map { it.obj().let { e -> e.s("date") to e.d("dte") } }, v.expiries.map { it.date to it.dte })
            val surf = py.l("surface").map { row -> row.list().map { it.dblOrNull() } }
            assertEquals(surf, v.surface, "surface $codes")
            assertTrue(v.surface.flatten().count { it != null } > v.strikes.size)
        }
    }

    private fun straddleInput(): Triple<List<TimeValue>, List<Double>, Map<Double, StrikeCloses>> {
        val si = fx.o("straddle_input")
        val u = si.l("underlying").map { it.obj().let { c -> TimeValue(c["timestamp"].lng(), c.d("close")) } }
        val ce = HashMap<Double, MutableMap<Long, Double>>()
        val pe = HashMap<Double, MutableMap<Long, Double>>()
        for ((sym, pts) in si.o("options")) {
            val s = OptionMath.parseOptionSymbol(sym)
            val m = (if (s.type == OptionType.CE) ce else pe).getOrPut(s.strike) { HashMap() }
            for (p in pts.list()) m[p.list()[0].lng()] = p.list()[1].dbl()
        }
        val closes = (ce.keys + pe.keys).associateWith { StrikeCloses(ce[it].orEmpty(), pe[it].orEmpty()) }
        return Triple(u, fx.dl("strikes"), closes)
    }

    @Test fun `dynamic ATM straddle matches straddle_chart_service`() {
        val (u, strikes, closes) = straddleInput()
        assertTrue(closes.keys.containsAll(ChainAnalytics.straddleStrikes(u, strikes)))
        for (c in fx.l("straddle").map { it.obj() }) {
            val py = c.o("result").o("data")
            val s = ChainAnalytics.straddle(u, strikes, closes, c["days"].lng().toInt(), now, expiry)
            assertEquals(py["days_to_expiry"].lng(), s.daysToExpiry)
            val series = py.l("series").map { it.obj() }
            assertEquals(series.size, s.series.size)
            for ((e, a) in series.zip(s.series)) {
                assertEquals(listOf(e.d("time"), e.d("spot"), e.d("atm_strike"), e.d("ce_price"), e.d("pe_price"), e.d("straddle"), e.d("synthetic_future")),
                    listOf(a.time.toDouble(), a.spot, a.atmStrike, a.cePrice, a.pePrice, a.straddle, a.syntheticFuture))
            }
        }
    }

    @Test fun `custom straddle simulation matches custom_straddle_service`() {
        val (u, strikes, closes) = straddleInput()
        for (c in fx.l("custom_straddle").map { it.obj() }) {
            val py = c.o("result").o("data")
            val s = ChainAnalytics.customStraddle(u, strikes, closes, c["days"].lng().toInt(), c.d("adjustment_points"),
                c["lot_size"].lng().toInt(), c["lots"].lng().toInt())
            assertEquals(py["quantity"].lng().toInt(), s.quantity)
            val sum = py.o("summary")
            assertEquals(listOf(sum.d("total_pnl"), sum.d("max_pnl"), sum.d("min_pnl")), listOf(s.totalPnl, s.maxPnl, s.minPnl), "summary $c")
            assertEquals(sum["total_adjustments"].lng().toInt(), s.totalAdjustments)
            val pts = py.l("pnl_series").map { it.obj() }
            assertEquals(pts.size, s.pnlSeries.size)
            for ((e, a) in pts.zip(s.pnlSeries)) {
                assertEquals(listOf(e.d("time"), e.d("pnl"), e.d("spot"), e.d("atm_strike"), e.d("entry_strike"), e.d("ce_price"), e.d("pe_price"),
                    e.d("straddle"), e.d("synthetic_future"), e.d("adjustments")),
                    listOf(a.time.toDouble(), a.pnl, a.spot, a.atmStrike, a.entryStrike, a.cePrice, a.pePrice, a.straddle, a.syntheticFuture, a.adjustments.toDouble()))
            }
            val trades = py.l("trades").map { it.obj() }
            assertEquals(trades.size, s.trades.size)
            for ((e, a) in trades.zip(s.trades)) {
                assertEquals(e.s("type"), a.type.name)
                assertEquals(listOf(e.d("time"), e.d("strike"), e.d("ce_price"), e.d("pe_price"), e.d("straddle"), e.d("spot"), e.d("leg_pnl"), e.d("cumulative_pnl")),
                    listOf(a.time.toDouble(), a.strike, a.cePrice, a.pePrice, a.straddle, a.spot, a.legPnl, a.cumulativePnl))
                assertEquals(listOf(e.dn("old_strike"), e.dn("exit_ce"), e.dn("exit_pe"), e.dn("exit_straddle")), listOf(a.oldStrike, a.exitCe, a.exitPe, a.exitStraddle))
            }
        }
    }

    @Test fun `calendar arbitrage universe matches arbitrage_service`() {
        val contracts = fx.o("arbitrage_contracts").values.flatMap { it.list() }.map {
            it.obj().let { c -> FutContract(c.s("symbol"), c.s("exchange"), c.s("name"), c.s("expiry"), c["lotsize"].lng().toInt(), c.d("tick_size")) }
        }
        for (c in fx.l("arbitrage").map { it.obj() }) {
            val u = ChainAnalytics.arbitrage(contracts, c.l("exchanges").map { it.str() })
            if (c["ok"] != true) { assertNull(u); continue }
            val py = c.o("result").o("data")
            assertNotNull(u)
            assertEquals(py.o("counts")["underlyings"].lng().toInt(), u.underlyingCount)
            assertEquals(py.l("pairs").map { it.obj().let { p -> listOf(p.s("id"), p.o("near").s("symbol"), p.o("far").s("symbol"), p.o("far").s("expiry"), p.o("near")["lotsize"].lng()) } },
                u.pairs.map { listOf(it.id, it.near.symbol, it.far.symbol, it.far.expiry, it.near.lotSize!!.toLong()) })
            assertEquals(py.l("symbols").map { it.obj().let { s -> s.s("symbol") to s.s("exchange") } }, u.symbols)
        }
    }

    @Test fun `calendar spread rows follow the scanner's computeRow`() {
        // Worked by hand from Arbitrage.tsx computeRow; the TypeScript-run cases live in PayoffTest's fixture.
        val pair = CalendarPair("NFO:NIFTY:near-next", "NIFTY", "NFO", "near-next",
            FutContract("A", "NFO", "NIFTY", "25-NOV-25"), FutContract("B", "NFO", "NIFTY", "30-DEC-25"))
        val r = ChainAnalytics.spreadRow(pair, Quote(24_400.0, 24_401.0, 24_400.5, 1_000), Quote(24_550.0, 24_552.0, null, 2_000), 5_000)
        assertEquals(149.0, r.bestCredit); assertEquals(SpreadDirection.SHORT_SPREAD, r.direction)
        near(149.0 / 24_400.5 * 100, r.spreadPct!!, 1e-15, 0.0)
        assertEquals(150.5, r.rawSpread); assertTrue(r.fresh && r.liquid)
        val stale = ChainAnalytics.spreadRow(pair, Quote(ltp = 100.0), Quote(bid = 0.0, ask = 101.0), 10_000)
        assertNull(stale.spreadPct); assertEquals(false, stale.fresh); assertEquals(false, stale.liquid)
        assertEquals(listOf(r, stale), ChainAnalytics.rankSpreads(listOf(stale, r)))
    }

    @Test fun `multi strike OI matches multi_strike_oi_service`() {
        val ms = fx.o("multi_strike_oi")
        val hist = ms.o("history")
        val legs = ms.l("legs").map { it.obj() }.filter { it["active"] != false && (it["segment"] ?: "OPTION") == "OPTION" }.map { l ->
            LegOiSeries(l.s("symbol"), l.s("side").uppercase(), l.dn("strike"), (l["optionType"] as String?)?.let { OptionType.valueOf(it) }, l["expiry"] as String?,
                hist.l(l.s("symbol")).map { p -> p.obj().let { TimeValue(it["timestamp"].lng(), it.d("oi")) } })
        }
        val under = hist.l("NIFTY25NOV2524300CE").mapIndexed { j, p -> TimeValue(p.obj()["timestamp"].lng(), 24_000 + j * 1.234) }
        val m = ChainAnalytics.multiStrikeOi(under, legs, ms["days"].lng().toInt())
        val py = ms.o("result").o("data")
        assertEquals(py["underlying_available"], m.underlyingAvailable)
        assertEquals(py.l("underlying_series").map { it.obj().let { p -> p["time"].lng() to p.d("value") } }, m.underlyingSeries.map { it.time to it.value })
        val pl = py.l("legs").map { it.obj() }
        assertEquals(pl.size, m.legs.size)
        for ((e, a) in pl.zip(m.legs)) {
            assertEquals(e.s("symbol"), a.symbol); assertEquals(e.s("side"), a.side); assertEquals(e["has_oi"], a.hasOi)
            assertEquals(e.l("series").map { it.obj().let { p -> p["time"].lng() to p.d("value") } }, a.series.map { it.time to it.value })
        }
    }
}

/** zip() that refuses unequal lengths, like Python's zip(strict=True). */
private fun <A, B> List<A>.zipStrict(other: List<B>): List<Pair<A, B>> {
    assertEquals(size, other.size, "length")
    return zip(other)
}
