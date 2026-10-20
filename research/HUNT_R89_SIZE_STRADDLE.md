# R89: a strategy from R88 — buy a straddle when a big candle is expected (20 Oct 2026)

*Research only: nothing in the app changes. Pre-registration: `research/hunt/r89/PREREG.md` (commit 5f4f988, before
any P&L). Code and tests: `research/hunt/r89/`. Results: `research/hunt/r89/results/` (`design.json`, `oos.json`,
every trade in `trades_*.csv`). Design results were committed before the out-of-sample run, which ran once
(`results/OOS_READ`).*

Boss, 20 Oct: *"If we create a strategy based on the above findings, what will the profit and win rate be?"*

## The answer for Boss

**It loses money everywhere: 0 of 18 versions are profitable, before or after 1 Jun 2026. Win rate 3-40%. Nothing goes
to paper.**

R88 found that the SIZE of the next candle is predictable but its DIRECTION is not. A call or a put needs direction,
so the only option-buying strategy R88 supports is a **straddle**: buy the ATM call AND the ATM put when the model
expects a big candle, sell both 15 or 60 minutes later. It earns if the index moves enough either way.

| | design walk-forward (index Jun 2025 - May 2026; MCX Feb - May 2026) | out of sample (1 Jun - 19 Oct 2026) |
|---|---|---|
| straddles traded | 4,302 | 2,214 |
| profitable versions | 0 of 18 | 0 of 18 |
| total net (1 lot each leg, all 18 versions) | **-Rs 12.8 lakh** | **-Rs 4.4 lakh** |
| win rate (net > 0) | 2-40% | 3-35% |
| green months | 0-7 of 13 (index), 0-1 of 3-4 (MCX) | 0-2 of 5 |

Per version, out of sample (1 lot of the call + 1 lot of the put):

| instrument | hold | straddles | win rate | net | per straddle | avg win / avg loss | gross before costs | costs |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 15 min | 159 | 25% | -Rs 9,945 | -Rs 63 | +751 / -336 | +10,435 | 20,380 |
| NIFTY | 60 min | 70 | 23% | -Rs 6,485 | -Rs 93 | +1,461 / -553 | +2,543 | 9,027 |
| BANKNIFTY | 15 min | 158 | 25% | -Rs 29,048 | -Rs 184 | +533 / -419 | +57 | 29,105 |
| BANKNIFTY | 60 min | 74 | 27% | -Rs 10,077 | -Rs 136 | +1,438 / -719 | +3,548 | 13,625 |
| FINNIFTY | 15 min | 157 | 32% | -Rs 41,693 | -Rs 266 | +712 / -723 | -13,402 | 28,291 |
| FINNIFTY | 60 min | 71 | 35% | -Rs 20,252 | -Rs 285 | +1,107 / -1,042 | -7,345 | 12,907 |
| MIDCPNIFTY | 15 min | 187 | 33% | -Rs 43,052 | -Rs 230 | +625 / -644 | -5,808 | 37,244 |
| MIDCPNIFTY | 60 min | 78 | 33% | -Rs 7,585 | -Rs 97 | +1,433 / -862 | +8,096 | 15,681 |
| SENSEX | 15 min | 176 | 29% | -Rs 16,920 | -Rs 96 | +461 / -323 | +4,454 | 21,374 |
| SENSEX | 60 min | 87 | 28% | -Rs 14,164 | -Rs 163 | +989 / -602 | -3,607 | 10,558 |
| GOLDM | 15 min | 105 | 31% | -Rs 31,983 | -Rs 305 | +891 / -853 | +24,270 | 56,253 |
| GOLDM | 60 min | 54 | 22% | -Rs 27,305 | -Rs 506 | +2,462 / -1,354 | +3,665 | 30,970 |
| SILVERM | 15 min | 58 | 31% | -Rs 44,241 | -Rs 763 | +901 / -1,512 | +232 | 44,473 |
| SILVERM | 60 min | 34 | 29% | -Rs 23,460 | -Rs 690 | +1,646 / -1,663 | +1,422 | 24,883 |
| CRUDEOIL | 15 min | 202 | 6% | -Rs 33,448 | -Rs 166 | +183 / -190 | +736 | 34,184 |
| CRUDEOIL | 60 min | 99 | 11% | -Rs 16,960 | -Rs 171 | +279 / -228 | +91 | 17,051 |
| NATURALGAS | 15 min | 220 | 3% | -Rs 43,488 | -Rs 198 | +302 / -212 | +975 | 44,463 |
| NATURALGAS | 60 min | 128 | 6% | -Rs 24,128 | -Rs 189 | +374 / -226 | +775 | 24,903 |

**Why it fails:**
1. **The option market already knows.** Before costs the straddles roughly break even (gross -Rs 13k to +Rs 24k per
   version). When a big candle is likely, option prices are already high — R88's own finding: candles are bigger when
   VIX/IV is high. You pay for the move in advance.
2. **Costs are larger than any edge.** A straddle is 4 orders: Rs 120-200 per straddle on an index (brokerage,
   STT, exchange, GST and the spread) and Rs 170-770 on MCX (out of sample, cost / straddles). The gross per straddle is a few rupees to a few hundred.
3. **The signal is no better than random timing.** 500 sets of straddles bought at random times on the same days
   lost about the same (design twin p 0.28-1.0 for 17 of 18; none passes after correcting for 18 tries).

So **with R88's findings the honest answer is: profit negative, win rate about 25-35% on indices and under 15% on
crude and natural gas.** The size forecast is still useful for risk (sizing stops and lots, expecting the US-open hour
and the first 15 minutes), but it is not a money-making signal on its own.

## Method

- Signal (underlying): R88's size model (HistGradientBoosting on R88's 22 factors), refitted each month on all earlier
  months; enter when the forecast is in the top 10% of the model's own training forecasts; 15-minute candles; index
  09:30-14:30, MCX 09:15-22:00; one straddle at a time.
- Trade: BUY 1 lot ATM call + 1 lot ATM put (nearest weekly for NIFTY/SENSEX, nearest monthly otherwise and on MCX) at
  the entry minute's option open; sell both after 15 or 60 minutes (square-off 15:10 / 15:05 on expiry; MCX 15 minutes
  before close).
- Real 1-minute option prices from the lake. Index fills `obuy` Fills("liq") (5 bps + 1-4 ticks by volume) and
  charges Costs("dated"); lots in force (`r51cfg`). MCX: R85's half-spreads (0.30/0.60%, natural gas 0.45/0.90%,
  before/after 17:00) and Zerodha MCX option charges.
- Gate: design net > 0, random-entry twin BH q < 0.10 over 18 versions, out-of-sample net > 0. None met the first.
- Caveat: Jun-Oct 2026 is out of sample for this P&L, but R88 had already scored its size model there.
- Experiment log: `research/experiments/entries/r89.yaml`.
