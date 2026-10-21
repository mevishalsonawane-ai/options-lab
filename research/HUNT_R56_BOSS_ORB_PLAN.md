# R56: Boss's own manual NIFTY ORB plan, backtested (21 Nov 2024 - 14 Oct 2026)

Written 15 Oct 2026 for Boss, who plans to trade this by hand. Research only: no app code changed.
Pre-registration `research/hunt/r56/PREREG.md` was committed and pushed in **9803297** before any P&L was computed.
Code: `research/hunt/r56/` (`r56cfg.py`, `r56data.py`, `r56engine.py`, `r56study.py`, tests `test_r56.py`). Full tables
are in `results/report.md`, the numbers in `results/summary.json`, and one row per trade in `results/trades.csv`.

**Signal source: the NIFTY INDEX** (1-minute closes against the 09:15-09:29 range), which is the CLAUDE.md default. The
option is only what gets traded. The target is set on the option premium (+Rs 1,000 a lot).

## Verdict for Boss (read this first)

1. **As written, the plan loses money, in both parts of the period.** Over 467 trades (1 lot each, after real charges
   and slippage) it lost **Rs 79,190, about Rs 170 a trade**. The design part (21 Nov 2024 - 31 Mar 2026) lost
   Rs 45,736 (333 trades) and the recent part (1 Apr - 14 Oct 2026) lost Rs 33,454 (134 trades).
2. **It wins often but loses big.** About 69% of trades win, but a win is capped at about +Rs 983 net while the
   average loss is **-Rs 2,754**. To break even with those sizes it would need to win about 74% of the time.
3. **The typical loss is much larger than Rs 1,000.** Of 144 losing trades, **128 (89%) lost more than Rs 1,000**. The
   median loss was Rs 2,606, 1 in 10 losses was worse than Rs 4,845, and the worst was **Rs 6,963** (one lot). A
   range-stop exit lost Rs 3,023 on average (101 trades). The range stop sits on the far side of the 15-minute range,
   so the index has to cross the whole range before it fires.
4. **The breakout minute adds nothing.** Random entries on the same days, on the same side and with the same exits,
   lost less than the rule on average (Rs -20,709). The rule beat only 2.6% of them (p = 0.97). The design and recent
   parts agree (p = 0.91 and 0.95).
5. **Bad months come often.** 7 of 24 months were green. The worst single day was -Rs 6,963, the worst 5-session run
   -Rs 13,977, and the max drawdown -Rs 82,634. That is more than Boss's -Rs 40k account stop rule, which this plan
   would have hit on 27 Nov 2025. Four months were worse than the -Rs 13k monthly stop: Aug 2025, Nov 2025, Dec 2025
   and Jul 2026.
6. **The context variants (not decisions) also lose.** Holding to the stop or square-off with no target: -Rs 156,206.
   Swapping the range stop for a Rs 1,000 hard stop: -Rs 40,765, and the recent part was 0 green months out of 7. That
   variant loses less in total and its worst day is only -Rs 2,409, but it still loses money and was not tested as a
   decision.

## The plan exactly as tested (PREREG A3)

- Opening range = high / low of the NIFTY index 1-minute bars 09:15 .. 09:29. If the 09:29 bar is missing (for example
  the 21 Oct 2025 Muhurat session), there is no trade.
- The FIRST 1-minute index close above the high (09:30 .. 14:59 bars) buys the ATM CALL. The first close below the low
  buys the ATM PUT. ATM = the strike nearest the index close at the signal (step 50), nearest expiry (on an expiry day,
  that day's contract). One trade a day, no re-entry, and no trade if there is no signal by 15:00.
- Fill = the open of the next minute of THAT contract (within 3 minutes, else no trade) plus slippage. The trade is
  priced on that one contract until it exits, never the rolling ATM series.
- Stop = an index 1-minute close beyond the OTHER side of the range. The option is then sold at the next minute's open.
- Target = entry + Rs 1,000 / lot on the premium, rounded up to the tick. When the option's minute high reaches it,
  the option is sold at the next minute's open. If the stop and target hit in the same minute, it counts as a stop.
- Square-off at 15:15, or 15:05 on expiry days. Lot = that day's contract lot read from the data: 25 (to 26 Dec 2024,
  and 24-30 Jan 2025 on the contract listed before NSE's change), 75 (27 Dec 2024 - 30 Dec 2025) and 65 (from 31 Dec
  2025). The target is therefore 40 points on lot 25, 13.35 on lot 75 and 15.40 on lot 65.
- Costs: the charges in force on each date (STT 0.1% to 31 Mar 2026, then 0.15%; NSE, SEBI, stamp, GST, Rs 20 an
  order). Fills use R49's liquidity slippage (5 bps plus 1-4 ticks), and stop exits get double (10 bps, ticks x2).
  The average round trip cost about Rs 65.
- Data clean-up from R47: option rows more than 3% off the index were dropped (none were in this window), and a
  stray-print guard was applied to fills and the target check. **`research/data/spikes.py` is not on main yet (15 Oct
  2026), so the Data Brain's spike flags were NOT applied.**

## Results (1 lot, net of charges and slippage)

| part | trades | win | target / stop / 15:15 | avg win | avg loss | net | net / trade | worst day | worst 5 sessions | max DD | losing streak | months green |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| whole span | 467 | 69% | 69% / 22% / 9% | +983 | -2,754 | **-79,190** | -170 | -6,963 | -13,977 | -82,634 | 9 | 7 / 24 |
| design (to 31 Mar 2026) | 333 | 70% | 70% / 21% / 9% | +986 | -2,755 | -45,736 | -137 | -6,963 | -12,510 | -63,826 | 9 | 5 / 17 |
| recent (1 Apr - 14 Oct 2026) | 134 | 67% | 66% / 23% / 10% | +973 | -2,750 | -33,454 | -250 | -6,658 | -13,977 | -33,797 | 3 | 2 / 7 |

- Sessions: 471. There was a trade on 467, no signal by 15:00 on 3, and no range on 1 (Muhurat). Every signal got a fill.
- **Target trades**: 322. Each made about Rs 1,049 gross at exit (the next minute's open is usually a little past the
  level), paid about Rs 65 in charges, and kept about **Rs 984 net**. So a "+Rs 1,000" exit nets a little under Rs 1,000.
- **Random-entry twins** (1,000 sets; same days, same sides, same exits; entry minute random 09:30-14:59):

  | part | rule | twins' average | twins 5%..95% | p |
  |---|---|---|---|---|
  | whole | -79,190 | -20,709 | -69,404 .. +24,080 | 0.974 |
  | design | -45,736 | -13,347 | -52,777 .. +25,457 | 0.907 |
  | recent | -33,454 | -7,362 | -34,409 .. +16,875 | 0.946 |

### Loss size (what Boss should expect when it goes wrong)

| part | losing trades | worse than -Rs 1,000 | median loss | 75th pct | 90th pct | worst |
|---|---|---|---|---|---|---|
| whole | 144 | 128 (89%) | 2,606 | 3,569 | 4,845 | 6,963 |
| design | 100 | 88 (88%) | 2,576 | 3,507 | 4,944 | 6,963 |
| recent | 44 | 40 (91%) | 2,674 | 3,676 | 4,193 | 6,658 |

Losses by size (whole span, net Rs): 0-500: 5, 500-1,000: 11, 1,000-1,500: 18, 1,500-2,000: 13, 2,000-3,000: 39,
over 3,000: 58. Losses mostly come from range stops (101 trades, avg -Rs 3,023) and from square-offs of trades that
drifted without reaching either level.

### Splits

| split | whole | design | recent |
|---|---|---|---|
| expiry days | 99 trades, 71% win, -6,368 | 71, 75%, +1,150 | 28, 61%, -7,518 |
| other days | 368, 69%, -72,822 | 262, 69%, -46,886 | 106, 69%, -25,936 |
| calls | 222, 73%, -24,379 | 159, 72%, -23,281 | 63, 73%, -1,097 |
| puts | 245, 66%, -54,811 | 174, 68%, -22,455 | 71, 62%, -32,357 |

Expiry days were the only split above zero, and only in the design part (+Rs 1,150 on 71 trades). They lost in the
recent part. Nothing here is a robust edge: these are after-the-fact splits of one losing rule, and none is a new
rule.

### Per month (whole span, net Rs)

2024-11 -3,636 · 2024-12 -7,152 · 2025-01 -7,244 · 2025-02 -1,457 · **2025-03 +11,162** · **2025-04 +8,593** ·
2025-05 -4,511 · 2025-06 -2,644 · 2025-07 -10,888 · 2025-08 -14,567 · **2025-09 +16,355** · 2025-10 -10,763 ·
2025-11 -15,045 · 2025-12 -15,447 · **2026-01 +7,501** · 2026-02 -2,290 · **2026-03 +6,297** · 2026-04 -7,940 ·
2026-05 -8,107 · **2026-06 +6,826** · 2026-07 -14,523 · 2026-08 -6,804 · **2026-09 +2,642** · 2026-10 (to 14th) -5,549.
Trade counts and win rates by month are in `results/report.md`.

## Context only (labelled; NOT decisions, nothing was chosen from these)

| variant | whole span | design | recent | note |
|---|---|---|---|---|
| C1 no target (range stop or 15:15) | 467 trades, 36% win, **-156,206** | -90,496 | -65,710 | avg win +4,103, avg loss -2,782; max DD -203,887 |
| C2 Rs 1,000 hard stop instead of the range stop | 467, 48% win, **-40,765** | -12,230 | -28,535 | avg loss -1,073, worst day -2,409; recent 0 of 7 months green |
| C3 target as a resting limit at the level | 467, 69% win, -94,368 | -56,773 | -37,595 | a limit fills exactly at +Rs 1,000 gross; the next-minute exit was a little better |

## Comparison with R49

R49's closest rule on NIFTY is `r49-g-orb-m15-k1`: the same 15-minute range, but with 5-minute closes, the ATM of the
09:20 bar, a 20-point premium stop and target, up to one entry per side, and out at 15:10. It lost Rs 14,422 on 409
design trades and Rs 18,872 on 158 final-test trades (twin p 0.93, FAIL). The old app ORB (`r49-orb`, 50-minute
range) lost Rs 9,276 and Rs 7,831. Boss's version keeps the same direction of result. It loses more in rupees because
its range stop is far wider than the +Rs 1,000 target.

## Honesty notes

- One pre-registered rule, fixed by Boss and not tuned, so no Benjamini-Hochberg. The design / recent split is a
  stability check. Both parts lose.
- The recent part is not pristine: R49 already ran ORB-type rules on NIFTY to 13 Oct 2026.
- Data spike flags (`research/data/spikes.py`) are not on main and were not applied. The R47 foreign-row filter and
  stray-print guard were applied.
- Minute bars: the stop is read on 1-minute index closes and the target on 1-minute option highs, with exits at the
  next minute's open. A human trading by hand will usually be slower, which tends to make results worse, not better.
- Lake data ends 14 Oct 2026. The raw data was deleted after the run (no raw data in git).

## What this means for Boss

Trading this plan as written would probably lose about Rs 170 a trade on 1 lot, with a typical losing day of about
Rs 2,600 and occasional days near Rs 7,000. If Boss still wants to trade it on paper, the context table shows that the
wide range stop drives the big losses. A tighter stop (C2) cut the worst day to about Rs 2,400, but C2 still lost
money and is only context. Any new version would need its own pre-registration and test.
