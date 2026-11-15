# R177 (EMA regime) pre-registration: the 9/21 EMA crossover with all three "regime filters" together

Written 15 Nov 2026, **before any R177 signal, trade or P&L number was computed**. Committed and pushed on
`claude/r177-ema-regime` on its own, before the code's first run on data. Research only: nothing in `android/**` changes.
Owner: Boss (they/them).

**Numbering.** This study was assigned the number R177. `origin/main` already holds a different R177 (`r177_vp_trap`,
entries `r177-k1`, `r177-x1`, `r177-x2`, `r177a-*`, `r177b-*`, `r177c-*`). To keep the assigned name without a clash,
this study's folder is `research/hunt/r177_ema_regime/` and every entry id starts `r177-ema-`. Call it **R177-EMA**.

## 0. Boss's question

Boss shared a PDF claiming a 9/21 EMA crossover bot fails in chop and that three regime filters fix it, and asked
*"What if all 3 are together"*, then *"Start here"*. This study tests the PDF's full rule exactly as stated, the plain
crossover with no filters, and each filter alone, so Boss can see whether stacking them helps.

**Signal source for every rule: the index spot** (NIFTY 50, NIFTY BANK; CLAUDE.md default). Every entry, filter, stop
and target is computed on the index's own 1-minute bars (aggregated to 15-minute and 1-hour bars). The option chosen by
the strike rule is only what is bought and sold; no rule reads an option's own chart. The one exception is the VWAP
weight (section 2, filter 2): the index has no volume, so the option chain's traded volume is used as the weight.

## 1. What was already tested near this (cited, not re-run)

| study | rule | result |
|---|---|---|
| R44 / R51 | EMA 8/21 + ADX(14) > 15 + stepped profit lock, 5-minute index bars, ATM | -Rs 50,947 on 3,129 design trades; -Rs 32,749 on 1,262 final trades |
| R44 | EMA 9/21 + ADX > 20 and EMA 9/26 + ADX > 20 (5-minute) | failed (part of the 0-of-14 sweep) |
| R104 book-porwal-08 | ADX + EMA + ATR (a book rule) | -Rs 1.23 L on 1,115 trades |
| H46 | VWAP as a filter | failed (2,400 variants) |
| R17 / R20 | ATR stops | did not help |
| ADX across the log | ADX as a filter | rejected in 77 of 91 tests |

Expected from these (said now, before any number): EMA crossovers on index options have lost money in every earlier
test here; ADX and VWAP filters have mostly not helped. The most likely outcome is that all variants lose after costs
and the filters only cut the trade count. This is stated so the reader can weigh the result; it does not change any rule.

## 2. The rules (fixed now)

### Data and bars

- 1-minute index bars of the stored day (row r = 09:15 + r, 375 rows). **15-minute bars** b = 0..24 (bar b = rows
  15b .. 15b + 14; open = first minute's open, high / low = max / min, close = last minute's close; a bar with no
  minute is missing and gives no signal). **1-hour bars** k = 0..6, 09:15-anchored (09:15-10:14, ..., 14:15-15:14, and
  the part bar 15:15-15:29). Every indicator runs on the continuous series of existing bars across days (R44 style).
- EMA adjust=False (R44 `ind.ema`); ADX(14) Wilder (R44 `ind.adx`); ATR(14) Wilder (true range with the previous
  close; the first value is the mean of the first 14 true ranges, then (prev x 13 + TR) / 14).
- `cross_up(a, b)` = a > b and a[1] <= b[1]; `cross_dn` the mirror (R44 `ind.cross_up / cross_dn`).

### Signal (the plain 9/21 crossover)

- **Call (long)**: on the 15-minute bar t, cross_up(EMA9, EMA21) of the 15-minute closes.
- **Put (mirror short, bought as a PUT)**: cross_dn(EMA9, EMA21).
- The decision is at the close of signal bar t; the order is placed for the next minute (row 15(t+1)), filled at the
  option's open there, or at the first traded minute up to 2 minutes later (R51's fill rule); else no fill (counted).
- Signal bars 0..22 (entries 09:30 .. 15:00). No signal in the first 15 sessions of each index's data (warm-up).

### Filter 1: ADX
ADX(14) of the 15-minute bars at the signal bar > 25. Otherwise no trade.

### Filter 2: 1-hour trend and VWAP
- **1-hour trend (primary, H1-A)**: on the last 1-hour bar COMPLETED at or before the signal bar's close
  (the 1-hour bar k is complete once 15-minute bar 4k + 3 has closed; before 10:15 it is the previous session's last
  1-hour bar), call: H1 close > H1 EMA21; put: H1 close < H1 EMA21.
- **Alternative (variant ALL-H1B only)**: H1 EMA9 > H1 EMA21 (call), < (put), on the same completed 1-hour bar.
- **VWAP (primary)**: session VWAP from 09:15 to the signal bar's last minute, of each minute's typical price
  (high + low + close) / 3 of the index, **weighted by the total traded volume of the stored option chain in that
  minute** (every stored strike, calls and puts, of the day's stored expiry). Neither the index nor any futures series
  in the reachable data has volume (section 4), so index-futures volume cannot be used; the option chain's activity is
  the closest volume series available. Call: signal bar close > VWAP; put: close < VWAP. If the weight sum is 0 the
  VWAP cannot be computed and there is no trade.
- **Variant ALL-TWAP**: the same with equal weights (session TWAP of the typical price).
- **Variant ALL-OR ("clearing the opening range")**: the opening range is 09:15-09:44 (15-minute bars 0 and 1); call
  also needs signal bar close > OR high, put close < OR low; no trade on signal bar 0 (the range is not complete).

### Filter 3: ATR stop on the index
- At the fill minute f, the index entry = the index's open at row f; ATR = ATR(14) of the signal bar.
- Call: stop = entry - 2 x ATR; put: stop = entry + 2 x ATR. From row f on, when a minute's index low <= stop (call)
  or high >= stop (put), the option is sold at the next minute's open **as a stop sale** (R17: x0.999 minus twice the
  half-spread), the worse fill.

### Exits (all rules)
- **Opposite crossover** of the 15-minute EMAs (cross_dn for a call, cross_up for a put) on any bar after the signal
  bar: sold at the option's open at the next minute (a market sale). The exit crossover needs no filter.
- **Square-off at 15:10** (r51cfg `SQ_ROW`, the app's square-off; R51's rule): sold at the 15:10 open (a market sale).
- With filter 3: the index stop above. **Variant ALL-2R**: also a target at entry + 2R (call; R = 2 x ATR, so +4 x ATR
  on the index; put: entry - 4 x ATR); when a minute's index high >= target the option is sold at the next minute's open
  (market). A minute that touches both stop and target counts as the stop.
- Whichever comes first. The PDF gives no target; the primary has none.
- No exit minute in the option data: the first traded open within the next 3 minutes, else the last close before (R51).

### Trade
- **Strike**: ATM = the index close of the signal bar rounded to the strike step (NIFTY 50, BANKNIFTY 100), of the
  day's stored expiry (the nearest expiry after the day). **Variant ALL-ITM**: 1-ITM (call ATM - step, put ATM + step).
  A strike not in the stored chain: no fill (counted).
- **One open position a side** per index (CE and PE are separate books); a signal while that side is open is ignored.
  No daily cap (the PDF has none).
- **The index's own expiry days are skipped** (no entry), as the arms do (the day equals a stored expiry date).
- Lots: the lot in force on the trade date (r51cfg `LOTS`); **1, 2 and 3 lots**.

### Costs
- R17 fills (research/obuy/costs.py `Fills("liq")` as R51's engine: buys +5 bps + a half-spread of 1 / 2 / 4 ticks for
  >= 20 / some / no lots of position size traded in the 5 minutes before; market sales -5 bps - the half-spread; stop
  sales -10 bps - 2 x the half-spread).
- Zerodha charges `Costs("dated")` (STT, exchange, SEBI, stamp, GST at the rates in force on the trade date; Rs 20 an
  order) on the whole quantity.

### The rule sets (10) and their exits

| id | entry | exits | strike |
|---|---|---|---|
| PLAIN | 9/21 cross | opposite cross, 15:10 | ATM |
| ADX | + filter 1 | opposite cross, 15:10 | ATM |
| TREND | + filter 2 (H1-A and VWAP) | opposite cross, 15:10 | ATM |
| ATR | 9/21 cross | + filter 3 stop | ATM |
| **ALL (primary)** | + filters 1, 2 (H1-A, VWAP) | + filter 3 stop | ATM |
| ALL-H1B | + filters 1, 2 (H1-B, VWAP) | + filter 3 stop | ATM |
| ALL-TWAP | + filters 1, 2 (H1-A, TWAP) | + filter 3 stop | ATM |
| ALL-OR | + filters 1, 2 (H1-A, VWAP) + opening range | + filter 3 stop | ATM |
| ALL-2R | + filters 1, 2 | + filter 3 stop + 2R target | ATM |
| ALL-ITM | + filters 1, 2 | + filter 3 stop | 1-ITM |

## 3. Tests

Each rule set is judged in **9 cells**: index {NIFTY, BANKNIFTY, both} x side {calls, puts, both}, at **1, 2 and 3
lots**: **10 x 9 x 3 = 270 tests**, all in the BH. The headline is ALL, both indices, both sides.

## 4. Data (the honest limit, stated first)

- The Dhan lake (`gdrive:IraAlgo-data-lake`) that R44 / R51 / R176 ran on is **not reachable from this session**
  (as R171 and R173 found). Reachable: the repo's `research-data` GitHub release, read with R171's loader
  (`research/hunt/r171_liquidity_gates/r171data.py`, unchanged): real 1-minute OHLCV option prices, one expiry a day
  (the nearest after the day), 20 strikes a day, and the index minutes (no index volume), for **NIFTY** (`nifty_prev`,
  `nifty_year`) and **BANKNIFTY** (`banknifty_prev_year_wide`, `banknifty_year_wide`). FINNIFTY and SENSEX have index
  minutes only (no options), MIDCPNIFTY nothing, so **only NIFTY and BANKNIFTY are traded**. `extra_series.parquet`'s
  BANKNIFTY futures (`BNFUT`) have no volume either.
- R171's rule: **no session on or after 24 Feb 2026 is used** (the 24 Feb - 5 Sep 2026 data hole). So the data ends
  23 Feb 2026, and Apr - Oct 2026 cannot be read here.
- Sessions: NIFTY from 1 Apr 2024, BANKNIFTY from 13 Feb 2024; indicators warm up on the first 15 sessions.

## 5. Periods (fixed now)

- **Full period**: 6 May 2024 - 23 Feb 2026 (trades only; earlier bars warm the indicators).
- **Design**: 6 May 2024 - 22 Aug 2025, in four walk-forward folds: (1) 6 May - 31 Aug 2024, (2) Sep - Dec 2024,
  (3) Jan - Apr 2025, (4) 1 May - 22 Aug 2025. Nothing in R177-EMA is fitted (every setting is the PDF's), so the
  design walk-forward net is the design net (R51's rule); each fold is reported.
- **Final (locked holdout, read ONCE) = the last 6 months**: 25 Aug 2025 - 23 Feb 2026. `results/FINAL_READ` is written
  before the read; a second read is refused. Honesty: these months were read by other studies (R171, R173) for other
  rules, and lie before the brain's own holdout (27 Apr 2026), which this study never touches.

## 6. Twins (random entries, the p-value)

Per (index, side) and rule set, 2,000 twins, seed 177: the same final-period days and the same number of entries per
day, entry signal bars drawn uniformly without replacement from that day's 23 allowed bars (0..22), the same side,
strike rule and exits (opposite cross after the drawn bar; the index stop from the drawn bar's ATR; 2R where used;
15:10), the same lots. Overlap between twin trades is not prevented (R44's design). A cell's twin total is the sum of
its (index, side) units' twins with the same draw number. **Empirical p** = (1 + twins with total net >= the rule's) /
2,001, one-sided. With 2,000 twins the smallest empirical p is 0.0005, which can never pass BH at m = 4,950 on its
own, so the **BH p** is the normal tail of z = (rule net - twin mean) / twin sd (one-sided); both are reported.

## 7. Gates (all must hold, per cell and lots; R51 / R176's gates)

1. Final net > 0.
2. Empirical twin p < 0.05.
3. BH q <= 0.10 across **m = 4,680 + 270 = 4,950** (4,680 = the entry files in `research/experiments/entries/` at the
   branch base, origin/main 694d71f02; each earlier test taken as p = 1). q within R177-EMA alone is also shown.
4. At least 40 final trades.
5. Final net without its top 5 trades > 0.
6. Design walk-forward net (= design net) > 0.
7. The account stop rule at the tested lots, in design and in the final: max drawdown (daily net) <= Rs 40,000 and
   worst calendar month >= -Rs 13,000.

"Stacking helps" is answered descriptively: ALL vs PLAIN vs each filter alone, same cells, same periods.

## 8. What is reported

For every rule set x index x side x lots, in the full period, the design (and its 4 folds) and the last 6 months:
trades, win % (net > 0), net after charges and slippage, average win / average loss, worst day, max drawdown (daily
net), worst calendar month, net without the top 5, exit mix (cross, stop, target, 15:10), no-fill count. Gate verdicts,
p and q. A short plain-English summary for Boss first (`results/summary.md`), the full tables in `results/report.md`,
`results/decision.json`, and one experiment entry per rule-set group.

## 9. Tests (pytest, in this folder) and the look-ahead guard

Unit tests with edge cases: EMA / ADX / ATR on short and NaN series; 15-minute and 1-hour bars from minutes with gaps
and missing bars; the completed 1-hour bar at each 15-minute bar (09:30, 10:14, 10:15 and the day's first bars use the
previous session); VWAP with zero volume; the OR check on bar 0; the strike rule; stop / target / cross / square-off
ordering, a stop and target in one minute, a gap through the stop, a missing exit minute; fills with no trade in the
slack; expiry-day skip; one-position-a-side; empty data; month / drawdown / worst-day helpers; BH with m; the gates;
the twin sampler (same days and counts, seed reproducible). **Look-ahead guard**: on synthetic days, replacing every
index minute after a signal bar's close with noise leaves that bar's decision (signal, filters, stop, strike) unchanged,
and replacing every minute after a trade's exit row leaves the exit row and price unchanged.
