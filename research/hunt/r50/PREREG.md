# R50 pre-registration: every cancelled hero / zero-to-hero rule, re-tested on the recent market only

Written 15 Oct 2026, BEFORE any R50 trade or P&L was computed. Committed and pushed on its own.

## Why

Boss (15 Oct): "add the Nov 2024 onwards view. Don't test any data before that, and now find a hero-zero, ORB, and all
the strategies which we have cancelled". R50 is the hero part. Every expiry-day hero rule of R21-R43 was cancelled
(0 of 170 passed). R50 asks a narrower question: since NSE's weekly-expiry change (20 Nov 2024), does any of them, or a
small re-tune of it, make money on the market as it is now?

## Data and window (nothing before 20 Nov 2024 is traded or reported)

- Drive lake `gdrive:IraAlgo-data-lake` (`options_rolling_1m`, `index_1m`, `index_daily`), copied to `$IRAALGO_LAKE`.
  Files from 2024 on are read so indicators and the GEX scale can warm up; **no trade before 20 Nov 2024 is simulated or
  reported**.
- Expiry days come from the data, as in R25/R43 (`r25data.expiry_days`: ATM time value at 15:15 < 0.6% of spot and
  the next session's > 2x it). The lake's last session (NIFTY 13 Oct 2026, a Tuesday weekly expiry) has no next session;
  it counts as an expiry when its 15:15 time value is < 0.6% (the first half of the same test). Only the WEEK files are
  read: for BANKNIFTY, FINNIFTY and MIDCPNIFTY the WEEK and MONTH files are the same contracts in this window (one is
  used, so no expiry is counted twice). Expiries in scope: NIFTY weekly (Thursday, then Tuesday from Sep 2025), SENSEX
  weekly (as BSE moved it), BANKNIFTY / FINNIFTY / MIDCPNIFTY monthly only. The exact list is written to
  `results/expiry_days.json` and the write-up.
- Every trade is priced on ONE held contract (the strike bought, R25's `Day.opts[(strike, side)]` grid rebuilt from
  every offset; never the rolling ATM series, R45).

## Split (fixed now)

- **DESIGN:** expiry days 20 Nov 2024 - 31 Mar 2026. Walk-forward inside it: blocks B0 = 20 Nov 2024 - 31 Mar 2025,
  B1 = Q2 2025, B2 = Q3 2025, B3 = Q4 2025, B4 = Q1 2026. A re-tune picks its cell on B0..B(i-1) (expanding, highest
  net) and trades B(i); walk-forward net = the sum over B1..B4. Original rules have nothing fitted; their walk-forward
  net is their net over B1..B4 (and every block is reported).
- **FINAL TEST:** expiry days 1 Apr - 13 Oct 2026, read ONCE by `study.py final`, which refuses a second run if
  `results/final.json` exists. A re-tune trades its cell chosen on the whole design.
- **Honesty note:** R43 (holdout 2025-26), R44, R45 and the brain studies already looked at parts of 2025-26, so this
  final test is not pristine. Only live paper from 15 Oct 2026 is truly unseen.

## Measurement (never relaxed)

- Engine: R25's (`hunt/r25/r25lib.py`) re-used with these settings, in `r50lib.simulate`:
  - entry worked on the 3 minutes after the signal minute (next-minute fills), LIMIT capped at ref x 1.2 + 0.10, never
    chases; fill = the open (+ costs below) or the cap;
  - exits triggered by a 1-minute close sell at the NEXT minute's open (a gap fills at the worse open); targets fill only
    when the high trades a tick through and the bar confirms (max(close, next open) >= target);
  - spread / slippage `Fills("liq")`: 5 bps plus 1 / 2 / 4 ticks a side by the contract's 5-minute volume (>= 20 lots /
    some / none) - wide for cheap expiry options (a tick is 1-5% of a Rs 1-5 option); **2x spread stress** = twice the
    ticks;
  - charges: `research/obuy/costs.py` `Costs("dated")` (STT, exchange, SEBI, stamp, GST, Rs 20 brokerage as in force on
    the trade date; BSE rates for SENSEX); a worthless exit pays no sell charges;
  - lots in force on each date: `obuy/config.LOT_SCHEDULE`, plus the revisions it lacks: BANKNIFTY 35 from 27 Jun 2025
    and 30 from 31 Dec 2025, MIDCPNIFTY 140 from 27 Jun 2025 and 120 from 31 Dec 2025 (the app's current lots,
    `ShadowSim.kt`: NIFTY 65, BANKNIFTY 30, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20).
- Sizing as each original: "floor" = whole lots within the budget, 0 lots skips; "min1" (R26 / R34 / R36) = at least 1
  lot; "skip" (R35) = skip when 1 lot costs more than the budget.
- No look-ahead: every signal reads rows <= t; a prefix/poison test is in `test_r50.py`.

### Exits (codes)

| code | rule |
|---|---|
| app | the Hero arm: half at 5x, rest at 20x, a 1-minute close <= 0.4x fill sells, else 15:05 |
| hold | 15:05, no stop |
| s50 | a close <= 0.5x fill sells, else 15:05 (R43 "hold") |
| E2 | 4x target, else 15:05, no stop (R26 P) |
| E3 | half at 2x, then lock (floor(best/fill) - 1) x fill on the rest, else 15:05 (R26 A6, R35, R36) |
| tr25 | Boss's 25% trail (R38): a close <= 0.75 x the confirmed peak (from the fill) sells, else 15:05 |

## The rules (signal source stated; "index" is the default)

Original rules keep their old settings and run on all 5 indices, except O-H0 (NIFTY only, as the app).

| id | family | original | entry | strike / budget / size | exit | signal source |
|---|---|---|---|---|---|---|
| O-H0 | app Hero arm | R21 V0 = HeroRules.kt | ATM straddle >= 15% over its low since 12:00 AND 15-min index move >= +0.25% (CE) / <= -0.25% (PE), 13:30-14:45, one a day | nearest OTM Rs 1-5 (3% band, stale-slack), Rs 5,000, floor | app | index momentum + ATM straddle premium (chain context) |
| O-V4 | app Hero arm | R21/R25 V4 | option's own close >= 2.5x its low since 12:00 + the momentum, 13:30-15:00 | Rs 1-15, Rs 5,000, floor | app | OPTION'S OWN CHART (non-default) + index momentum |
| O-L1 | cheap OTM lottery | R25 L1 | 13:30, CE AND PE | nearest Rs 3 within Rs 1-5, Rs 500 a side, floor | hold | none (time only) |
| O-L3 | cheap OTM lottery | R25 L3 | 14:30, CE AND PE | as L1 | hold | none |
| O-BASE | cheap OTM lottery | R43 base cheap hero | 12:30 bar close, CE AND PE | nearest eligible Rs 5-30, Rs 5,000, floor | s50 | none |
| O-P | OI-wall short covering | R26 P | 13:45-14:30: 16 closes beyond the 13:00 highest-OI wall + wall OI down >= theta from its 12:00+ peak (15% NIFTY/SENSEX, 25% BANKNIFTY/FINNIFTY/MIDCPNIFTY) + chain 15-min volume >= 2x + 15-min index move + summed OI down | ATM+-1 Rs 15-30, Rs 2,000, min1 | E2 | index break of a chain (OI) level; OI as filter |
| O-A6 | OI-wall short covering | R26 A6 | as P | as P | E3 | as P |
| O-B1 | Boss's 1:45-2:30 window | R26 B1 | P without the OI conditions | as P | E2 | index |
| O-X1 | gamma / GEX | R36 X1 | 14:00 on days when GEX_short (scaled by the previous 20 expiries' median) is in the top third of earlier values; CE AND PE | nearest Rs 22.5 within Rs 15-30, Rs 1,000 a leg, min1 | E3 | chain (GEX) day filter, no direction |
| O-X2 | gamma / GEX | R36 X2 | as X1 with GEX_net in the bottom third | as X1 | E3 | as X1 |
| O-E821 | sweep signals | R43 ema821_adx15 cheap hero hold | 5-min index EMA8 x 21 cross with ADX14 > 15, bars closing 12:30-14:45 | nearest eligible Rs 5-30, Rs 5,000, floor | s50 | index |
| O-E921 | sweep signals | R43 ema921_adx20 cheap hero hold | EMA9 x 21, ADX > 20 | as E821 | s50 | index |
| O-E926 | sweep signals | R43 ema926_adx20 cheap hero hold | EMA9 x 26, ADX > 20 | as E821 | s50 | index |
| O-RSIM | sweep signals | R43 rsi_momo cheap hero hold | RSI14 crosses 55 up (CE) / 45 down (PE) | as E821 | s50 | index |
| O-RSIF | sweep signals | R43 rsi_fade cheap all hold | RSI14 crosses under 30 (CE) / over 70 (PE), 09:20-14:45 | as E821 | s50 | index |
| O-DON | sweep signals | R43 donchian20 otm2 all hold | close beyond the prior 20-bar high / low, 09:20-14:45 | 2nd OTM strike, Rs 5,000, floor | s50 | index |
| O-S3 | volume / supertrend | R35 S3 | 14:00-14:45: ST(7,3) and ST(10,2) both turn green on the OPTION'S own 1-min bars + option volume >= 2x EMA10 + index on the trade's side of its TWAP | ATM+-1, Rs 2,000, skip | E3 | OPTION'S OWN CHART (non-default) |
| O-S5 | volume / supertrend | R35 S5 (without the heavyweight check: the lake copy has no equity minutes here) | as S3 with both Supertrends on the INDEX 1-min bars | as S3 | E3 | index (+ option volume filter) |
| O-K1 | afternoon window | R34 K1e | 14:30-14:45 index range; first 1-min close outside it by 15:00 | ATM+-1, Rs 2,000, min1 | hold | index |
| O-T1 | afternoon window | R25 T1 | 11:00-14:30: fresh 15-min break of the day's high (CE) / low (PE) + strike within 1.25 expected moves; one per side a day | Rs 1-10, Rs 1,000, floor | app | index |

R37 (gamma flip) and R39/R40 (mined patterns / ML scores) are not re-run: R37 needs the heavyweight equity minutes and a
spot-grid GEX solve, and R39/R40 were fitted year by year and have no fixed rule to carry over. They stay off.

## Small re-tunes (one per family; the cell is chosen on design by the walk-forward above)

| id | base | grid (cells) |
|---|---|---|
| T-HERO | O-H0 trigger, all 5 indices | band {Rs 1-5, Rs 5-30} x exit {app, tr25} (4) |
| T-LOT | lottery basket, Rs 1-5 nearest Rs 3, Rs 500 a side | time {12:30, 13:30, 14:30} x exit {hold, tr25} (6) |
| T-OI | O-P entries | exit {E2, E3, tr25, hold} (4) |
| T-BOSS | O-B1 entries | strike {ATM+-1 Rs 15-30, nearest eligible Rs 5-30} x exit {E2, tr25} (4) |
| T-GEX | O-X1 days and legs | exit {E3, tr25, hold} (3) |
| T-SWEEP | R43 cheap hero window | signal {ema821_adx15, ema921_adx20, donchian20} x exit {s50, tr25} (6) |
| T-ST | O-S5 entries | exit {E3, tr25, hold} (3) |
| T-AFT | O-K1 entries | strike {ATM+-1, nearest eligible Rs 5-30} x exit {hold, tr25} (4) |

## Statistics and gates

- Random-entry twins, 500 per rule and period: each trade is re-entered at a uniformly random minute of the rule's own
  window (same day, side, strike rule, budget, sizing and exit; up to 20 draws for a fillable entry, else 0). Fixed-time
  rules (lottery, GEX) draw from 12:00-14:45. p = (#twins with net >= the rule's net + 1) / 501.
- BH (Benjamini-Hochberg) over every final-test test in R50: 20 originals + 8 re-tunes = 28.
- **Gates (all needed):** final-test net > 0; p < 0.05; q <= 0.10; >= 40 final-test trades (else "too few trades to
  judge"); net without the top 5 trades > 0 (top-5 share < 100% of net); design walk-forward net > 0.
- Reported per rule: trades, win rate, heroes caught (peak >= 5x / 10x the fill while held; and exits banked at >= 5x),
  net, net per index-expiry-day, worst day, max drawdown (daily), top-1/3/5 share of net and net without them, p, q,
  2x-spread net, per-block design nets, OLD verdict.

## What a pass means

Research only: nothing in the app changes. A rule that passes every gate is named as a candidate to come back on PAPER
(Boss decides; logged and undoable). Live is never touched. A rule that fails stays off.
