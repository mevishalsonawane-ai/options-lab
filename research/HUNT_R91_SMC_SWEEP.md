# R91: Boss's ICT / Smart Money "liquidity sweep" (20 Oct 2026)

Boss, 20 Oct, sent a video strategy:
1. On the 1-hour chart, mark the previous swing high (shorts) or swing low (longs).
2. Wait for the next day. If price breaks above the swing high but the 1-hour candle closes back below it, that is a
   "smart money trap".
3. On the 5-minute chart, enter on the break of the strong bearish candle. The stop goes at the sweep's high and the
   target at the support zone. Longs are the mirror.

R91 turned these steps into fixed rules and tested them on real option prices with real costs. It is research only:
nothing in `android/**` or `.github/**` changed.

- **Plan:** `hunt/r91_smc_sweep/PREREG.md`, pushed (d3020a2) before any statistic. Code and tests were pushed before
  any P&L; so were notes N1 (signal counts only, `NOTES.md`) and the design results with the 7 experiment entries
  (3399ba5). The holdout was then read once (`results/FINAL_READ`).
- **Code:** `hunt/r91_smc_sweep/` (`sweep.py` rules, `study.py`, `arms.py`, `report.py`, `entries.py`). Option pricing,
  twins and the instrument wrapper are reused from R89 / R90. Tests: `tests/test_r91.py`, 63 tests. They cover
  hand-made cases plus the look-ahead guard on all 7 variants: a swing is known only after its right-hand bars close,
  and a sweep only after its candle closes.
- **Tables:** every table is in `hunt/r91_smc_sweep/results/report.md`. JSON: `design.json`, `final.json`,
  `arms.json`, `counts_design.json`.

## Signal source of every rule: the UNDERLYING

| rule piece | read on |
|---|---|
| 1h fractal swing high / low (N = 2, N3: 3) | the underlying (index spot 1m OHLC; MCX near future, close-only) |
| the sweep (1h high beyond the level, 1h close back inside; S15: a 15m close) | the underlying |
| the strong 5m candle (body >= 60% of range, range >= 1.0 x ATR14 of the previous 5m bar, within 6 bars) | the underlying |
| entry (sell-stop at the candle's low / buy-stop at its high) | the underlying |
| stop (the sweep's extreme +/- 1 tick) | the underlying |
| target (nearest intact 1h swing / previous day's low or high / fixed 2R) | the underlying |
| what is bought | the 1-ITM put (after a swept high) or call (after a swept low), nearest expiry, real 1-minute prints |

No rule reads an option's own chart, and no option-chain data are used.

## The answer for Boss

**It does not work after costs. 0 of 63 pre-registered tests passed (7 variants x 9 instruments). The lowest BH
q-value was 0.38, against the 0.10 needed. Nothing goes to paper. Nothing goes live. It stays a note.**

1. **The setup is rare.** Each index has about 120-190 sweeps in 16 months. About half find no strong 5m candle within
   30 minutes, and 15-30% are retaken before the candle forms. What remains is 18-36 trades per index on the base
   rule: about 0.1 a day, or one every two weeks. Most tests are under the 40-trade minimum.
2. **It loses money in design.** The five indices pooled lost Rs 40,340 on 135 trades with the base rule (B), from
   20 Nov 2024 to 31 Mar 2026. Win rate 32%. Max drawdown Rs 88,718. Worst month -Rs 40,095 (Apr 2025), which breaks
   Boss's -Rs 13k monthly stop. Green months: 6 of 17.
   - Random entry times on the same days and sides, with the same stop and target distances (the after-trigger
     twins), lost about the same: Rs 42,134 on average. The sweep's timing adds nothing.
   - On the underlying itself, before any cost, the base rule lost Rs 37,150 (135 trades).
   - MCX lost on every variant: base -Rs 15,095 on 61 trades. GOLDM / SILVERM options printed too rarely to fill 12
     of 16 and 17 of 22 signals.
3. **How often the sweep fails.** The stop at the sweep's extreme was hit on 41% of index trades (MCX 51%), but only
   about 2% within 15 minutes (MCX 7%). On top of that, 18% of index sweeps (20% MCX) were retaken before any entry.
   The losses are mostly slow: no follow-through by 15:15 while the option loses time value, not quick whipsaws.
4. **Best-looking cells were luck.**
   - MIDCPNIFTY base: +Rs 25,798 on 36 trades (twin p 0.022, q 0.46), but -Rs 40,105 without its top 5 trades, with
     a worst month of -Rs 18,352 and 2 of 4 folds positive.
   - SENSEX "any day": +Rs 29,202 on 24 trades (p 0.010, q 0.38), but -Rs 8,039 without its top 5.
   - Both fail several gates.
5. **The locked holdout (1 Apr - 19 Oct 2026, read once) confirms the fail.** No design passer existed, so nothing
   could become a paper candidate.
   - Described only: the indices pooled turned positive, base +Rs 15,243 on 44 trades and T2R +Rs 40,734 on 57.
   - BANKNIFTY, FINNIFTY and MIDCPNIFTY cells show twin p 0.002-0.03 on 7-15 trades each. Their q is 0.08 over all
     63, but they are negative without their top 5, and every cell is under 20 trades.
   - NIFTY lost in every variant (-Rs 16,659 on 10 trades, base). SILVERM lost Rs 18,386 on 11 trades with a worst
     month of -Rs 12,934. The MCX pooled base lost Rs 28,566 on 63 trades.
   - One good half-year after a losing design period, on 3-15 trades a cell, is not evidence. If Boss wants it
     watched, it needs a fresh pre-registration on data after 19 Oct 2026.
6. **Prior evidence agreed.** R86 found fading failed pokes at yesterday's high / low, round numbers and the opening
   range lost on 6 of 6 tests. R87 found stop runs above the day's high are followed by more upside. R91 adds the 1h
   swing, the 1h close confirmation and Boss's 5m candle. They cut the trade count about tenfold, but do not create an
   edge.

## How R91 differs from the Liquidity arm, and do they trade the same days?

The two arms are mirror images:
- **The Liquidity arm (15+5)** buys in the direction of the break: a 15m / 5m close through a "both" level (a pool on
  a swing zone, LuxAlgo-style pivot 20). It stops on a failed break.
- **R91** fades the failed break: a previous-session 1h fractal swing, a 1h close back inside, a strong 5m candle.

On the same stores (Liquidity BANKNIFTY reproduced to the rupee: +Rs 1,10,094 on 336 design trades):

| window | R91 B trades (BANKNIFTY) | on Liquidity days | same side | opposite side | daily P&L corr | R91 net on Liquidity days / other days |
|---|---|---|---|---|---|---|
| design | 27 | 74% | 33% | 56% | -0.00 | -Rs 21,987 (20) / -Rs 5,103 (7) |
| holdout | 11 | 73% | 18% | 55% | -0.23 | +Rs 14,665 (8) / -Rs 4,643 (3) |

"Same side" and "opposite" can add up to more than 100%, because the arm can trade both sides on one day.
- On most R91 days the Liquidity arm also trades, usually on the other side: it bought the break, and R91 sells the
  failure. The daily P&L hardly correlates.
- Adding R91 would not diversify the Liquidity arm in a way that pays: R91 lost on those same days in design.

| arm (same stores) | R91 B trades | on the arm's days | same side | opposite side | daily corr (per index) | R91 net on arm days / other days |
|---|---|---|---|---|---|---|
| Breadth Donchian, design (+Rs 2,16,929 / 3,071) | 135 | 87% | 56% | 70% | -0.21 to +0.18 | -Rs 40,846 (117) / +Rs 507 (18) |
| Breadth Donchian, holdout (+Rs 1,69,712 / 1,225) | 44 | 75% | 45% | 68% | 0.00 to +0.12 | +Rs 32,541 (33) / -Rs 17,298 (11) |
| ORB signal (R20 V0), design | 135 | 93% | 45% | 65% | n/a (signals only) | -Rs 24,710 (125) / -Rs 15,629 (10) |
| ORB signal, holdout | 44 | 91% | 43% | 66% | n/a | +Rs 18,572 (40) / -Rs 3,329 (4) |

Liquidity on BANKNIFTY + FINNIFTY (R51 defines the arm for those two only) is in `report.md`: design 56 R91 trades,
71% on arm days, 54% opposite side.

## Base rule (B) per instrument, design (option, 1 lot, after costs)

| instrument | trades (a week) | win | avg win / loss | per trade | net | max DD | worst day | worst month | stopped (<= 15 min) | twin mean, p | q | und pts / trade | opt gross / cost pts |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 25 (0.37) | 24% | 4,522 / -3,055 | -1,237 | -30,920 | 30,920 | -7,292 | -9,411 | 56% (0%) | -27,431, 0.67 | 1.00 | -32.0 | -16.2 / 1.1 |
| BANKNIFTY | 27 (0.40) | 26% | 2,996 / -2,403 | -1,003 | -27,090 | 33,435 | -6,162 | -8,886 | 48% (0%) | -903, 1.00 | 1.00 | -28.8 | -28.4 / 3.4 |
| SENSEX | 18 (0.27) | 33% | 5,532 / -1,916 | +566 | +10,195 | 9,318 | -5,946 | -5,946 | 28% (0%) | -5,192, 0.052 | 0.50 | +57.5 | +32.1 / 3.8 |
| FINNIFTY | 29 (0.43) | 31% | 2,932 / -2,235 | -632 | -18,323 | 28,847 | -7,565 | -8,788 | 38% (3%) | -5,423, 0.94 | 1.00 | -13.1 | -7.5 / 1.9 |
| MIDCPNIFTY | 36 (0.54) | 42% | 6,694 / -3,553 | +717 | +25,798 | 24,853 | -8,392 | -18,352 | 36% (3%) | -3,185, 0.022 | 0.46 | +10.7 | +6.7 / 1.1 |
| GOLDM | 4 (0.14) | 25% | 1,069 / -1,293 | -702 | -2,808 | 2,808 | -1,670 | -1,670 | 25% (0%) | -1,210, 0.91 | 1.00 | +251.9 | -51.6 / 18.6 |
| SILVERM | 5 (0.17) | 40% | 1,595 / -1,852 | -474 | -2,367 | 4,726 | -2,428 | -4,726 | 20% (0%) | -3,125, 0.46 | 1.00 | +233.9 | -20.7 / 74.0 |
| CRUDEOIL (mini) | 28 (0.94) | 36% | 163 / -197 | -69 | -1,926 | 1,926 | -517 | -886 | 54% (11%) | -2,626, 0.16 | 0.84 | +10.9 | +0.7 / 7.6 |
| NATURALGAS (mini) | 24 (0.80) | 21% | 405 / -527 | -333 | -7,994 | 8,531 | -1,577 | -2,366 | 58% (4%) | -6,872, 0.79 | 1.00 | -0.5 | -0.9 / 0.4 |

Walk-forward folds (index Apr-Jun 2025, Jul-Sep, Oct-Dec, Jan-Mar 2026), base, net in Rs:
- NIFTY: -83, -14,060, -2,924, -3,587.
- BANKNIFTY: -1,570, -9,167, -1,021, -1,694.
- SENSEX: +13,360, -6,660, -1,081, +7,423.
- FINNIFTY: +31, -10,455, -3,940, +8,270.
- MIDCPNIFTY: -2,622, +13,728, -2,690, +12,475.

Picking the best variant before each fold beat B on NIFTY only. It was worse on BANKNIFTY, FINNIFTY and MIDCPNIFTY,
and in total. Every variant on every instrument, the funnel, the folds and the pooled books are in `report.md`.

## Pooled (all indices / all MCX, 1 lot each, after costs)

| variant | design indices: trades, net (twins) | design MCX: trades, net | holdout indices: trades, net (twins) | holdout MCX: trades, net |
|---|---|---|---|---|
| B (1h, fractal 2, next day, swing target) | 135, -40,340 (-42,134) | 61, -15,095 | 44, +15,243 (-13,112) | 63, -28,566 |
| N3 (fractal 3) | 107, -69,211 (-47,623) | 58, -18,395 | 34, +31,232 (+826) | 58, -17,666 |
| ANY (any swing, not only an earlier session) | 162, -8,783 (-22,529) | 107, -47,516 | 59, +13,073 (-22,554) | 127, -61,222 |
| S15 (15m close back inside) | 154, -79,709 (+61,074) | 66, -42,494 | 45, +11,576 (-2,190) | 67, -51,757 |
| TPD (previous day's low / high target) | 112, -25,819 (-28,482) | 71, -23,239 | 42, +5,273 (-14,548) | 76, -3,837 |
| T2R (fixed 2R) | 188, -61,900 (-50,394) | 75, -15,435 | 57, +40,734 (-662) | 81, -1,198 |
| RR2 (min reward:risk 2) | 77, -14,622 (-15,335) | 43, -11,255 | 32, -36,081 (-25,281) | 47, -25,336 |

## Data and method

- **Lake:** Drive `gdrive:IraAlgo-data-lake`, copied 20 Oct 2026; nothing raw committed.
  - Indices: R89 / R51 day stores, 474 option days each; design 20 Nov 2024 - 31 Mar 2026 (336 sessions), holdout
    1 Apr - 19 Oct 2026 (137).
  - MCX: R53 books; design 5 Aug 2025 - 31 Mar 2026, the same holdout. CRUDEOIL and NATURALGAS are traded as the mini
    contracts, labelled "(mini)".
- **Option:** the 1-ITM put / call, bought at the next minute's open.
  - Index: R51 engine, with the app's slippage model (5 bps + 1/2/4 ticks by volume) and dated charges. Flat at 15:15
    (15:05 on expiry). No fill before 09:30 or after 14:45.
  - MCX: R53 rules + R85 strict half-spread, from 09:15 to 22:30, flat at the session cut.
- **Trades:** 1 lot, one position at a time, at most 2 trades a day.
- **Gates:** at least 40 trades; net above zero; after-trigger twin p < 0.05; BH q < 0.10 over 63 cells; positive in
  3 of 4 index folds (MCX 2 of 3); positive without the top 5; worst month >= -Rs 13,000.
- **Limits:** MCX bars are close-only, so wicks and sweeps are understated there. Signals are rare, so every cell is
  small. ATM strikes were not tested.
