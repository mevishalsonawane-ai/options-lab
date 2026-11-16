# R184: absorption and exhaustion as option-buying rules (NIFTY / BANKNIFTY, phone recordings)

*16 Nov 2026. Research only; nothing in the app changes. Pre-registered (PREREG.md, commit c0ba6fc44, pushed before any
signal or rupee was computed). Design on 13 days, selection committed (db1edde53), read flag committed (18dcb4566), then
the 7-day holdout read once. Per-trade rows are outside git (`~/.cache/iraalgo/r184/`).*

Boss, 16 Nov 2026: "Now check absorption and exhaustion technique in options buying."

## For Boss, in plain words

**What was tested.** The two classic order-flow reversal setups, written as exact rules on the index FUTURE's own price
and order flow (signal on the underlying), each trading 1 lot of the ATM option:

- **Absorption.** Over the last 3 or 5 minutes aggressive selling is heavy (the futures delta is 2x or 3x the day's own
  normal for such a window) but price does not fall (it stays within 3 or 6 basis points, about 7-14 NIFTY points, of
  where the window began): someone is soaking up the selling -> **buy a CALL**. The mirror (heavy buying, price will not
  rise) -> **buy a PUT**. Optionally wait for the next minute to close in the trade's direction ("turn").
- **Exhaustion.** A push makes a new 15- or 30-minute low on a spike of selling (2x or 3x the day's normal minute delta);
  in the next 1-3 minutes the selling dries up (less than half the spike), price stops falling (no new low beyond the
  band) and delta flips to buying -> **buy a CALL**. The mirror at a new high -> **buy a PUT**. Optionally also need the
  close back above the climax minute's close ("flip+close").
- **Exits, the same for all:** -15% on the premium, stop to breakeven (fill + charges) once the option is +10%, an index
  stop just beyond the absorption / climax extreme, a time stop of 15 or 30 minutes, 15:15 (15:05 on expiry).
- Real dated charges and the repo's liquidity slippage; also 2x slippage and the recorded bid / ask.
- 32 versions per setup (window, heaviness, band, confirmation, time stop). The best version per setup and index was
  chosen on the first 13 days (15 Oct - 3 Nov), then read ONCE on the last 7 days (4 - 13 Nov).

**What happened on the 7 locked days (the only numbers that count):**

| setup, index | chosen version | trades | win | net (2x slip) | a trade | random twins, a trade | twin p | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| Absorption NIFTY | ABS w3 k2 b6 turn T30 | 8 | 25% | Rs 2,153 (Rs 1,971) | Rs 269 | Rs 188 | 0.39 | 1.00 | too few trades |
| Absorption BANKNIFTY | ABS w5 k2 b6 turn T30 | 6 | 50% | Rs 2,257 (Rs 2,079) | Rs 376 | Rs -46 | 0.25 | 1.00 | too few trades |
| Exhaustion NIFTY | EXH X30 k3 b6 flip T30 | 14 | 21% | Rs -3,728 (Rs -4,055) | Rs -266 | Rs -282 | 0.45 | 1.00 | no evidence |
| Exhaustion BANKNIFTY | EXH X30 k2 b3 flip+close T30 | 22 | 23% | Rs -2,072 (Rs -2,765) | Rs -94 | Rs -294 | 0.24 | 1.00 | no evidence |

(1 lot; rupees summed over the 7 days; twins = the same number of entries at the same minutes of the day, same side and
same exits, on other days of the same period, 1,000 sets.)

**What it means.**
- **Exhaustion: no.** It lost on both indices on the locked days (36 trades, Rs -5,800,
  22% won) and was no better than random entries at the same minutes (p 0.45
  and 0.24). On the design days the best of 32 versions had made only
  Rs 44 and Rs 75 a
  trade, and most versions lost there (median version Rs -5,064 NIFTY, Rs -3,264 BANKNIFTY).
- **Absorption: positive, but far too few trades to mean anything.** 14 trades in the locked week made
  Rs 4,411 (Rs 315 a trade), after Rs 7,280 on 36 design trades. But
  it did not beat random entries clearly (p 0.39 and 0.25); the
  chosen versions were the lucky tail of the design grid (only 9 of 32 NIFTY and 3
  of 32 BANKNIFTY versions made money on the design days; the median version lost Rs 742 and
  Rs 2,732); and the money came from CALLS in a strong rally (locked week: calls 9 trades
  Rs 6,129, puts 5 trades Rs -1,718). The pre-registered bar needs at least 10 locked trades per
  index: neither had them (8 and 6).
- **How big an edge could 20 days see?** With 6-22 locked trades per cell and a spread of about
  Rs 1,268-Rs 1,908 a trade, an edge would need to be **Rs 844-Rs 1,939 a trade** to
  show at the usual 5% level with 80% power, and Rs 1,589-Rs 3,653 at the multiple-testing level. The
  best observed mean was Rs 376. This sample can only catch a huge edge; it cannot rule out a modest one.
- **Win rates are low (21-50%)**: these are small-stop, let-it-run trades. The money is made by the 30-minute time exits
  and lost at the index stop.
- **Nothing passes** (all 8 BH q = 1.0 with m = 4,512). Nothing goes to paper or live. Absorption could be
  re-checked when a few more months of recordings exist, with the same frozen rules (a forward test, no new tuning).

**Overlap with the arms (descriptive, 21 Oct - 13 Nov, 77 arm paper trades on NIFTY / BANKNIFTY).** Exhaustion fired
in the same direction as an R51 / R72 / Liquidity entry within 15 minutes 0 times in 90 trades on arm
days and the opposite way 21 times: it is a counter-trend idea against breakout arms. Absorption fired in the
arms' direction 5 of 40 times (R51 1, R72 4) and against
them 3 times. Neither duplicates an arm.

## Exact rules (signal source: the near index future; the option only prices the trade)

From PREREG.md sections 2-4. Minute bars from the future's 1-second rows; delta D = buy_vol - sell_vol (the app's tick
rule). A bar with fewer than 20 recorded seconds is "thin" and cannot be part of a signal. References are the same day's
earlier minutes only (at least 20): ref1 = mean |D| of earlier minutes; ref_w = mean |w-minute delta| of earlier windows.
Signals 09:40 - 14:44; the option is bought at the start of the next minute.

- ABSORPTION (w, k, beta, confirm). CALL when the w-minute delta <= -k x ref_w and the window's low >= the close before
  the window - beta bp; PUT is the mirror. "turn": the next minute must close up (CALL) / down (PUT); the signal is then
  that minute. Index stop: the window's extreme -/+ beta bp. Grid w {3, 5}, k {2, 3}, beta {3, 6} bp, confirm
  {none, turn}.
- EXHAUSTION (X, k, beta, confirm). Climax c: a new X-minute low with D[c] <= -k x ref1. Signal: the first minute r in
  c+1..c+3 where every minute after c sold less than half of D[c], no low went beyond L[c] - beta bp, and D[r] > 0
  ("flip+close": also C[r] > C[c]); a broken condition kills the climax. PUT is the mirror at a new high. Index stop:
  the extreme -/+ beta bp. Grid X {15, 30}, k {2, 3}, beta {3, 6} bp, confirm {flip, flip+close}.
- Both: time stop T {15, 30} minutes; one trade at a time, at most 4 a day per index. ATM strike from the index at the
  signal minute (recorded strike within 2 steps otherwise). Fill = option `last` + liquidity slippage.

## Design (13 days, 15 Oct - 3 Nov): every version, and the choice

| cell | versions with >= 12 trades | versions with net > 0 | median version net | chosen | trades | win | net | a trade | twins p (descriptive, after selection) |
|---|---|---|---|---|---|---|---|---|---|
| Absorption NIFTY | 14 / 32 | 9 / 32 | Rs -742 | ABS w3 k2 b6 turn T30 | 24 | 29% | Rs 4,164 | Rs 174 | 0.10 |
| Absorption BANKNIFTY | 12 / 32 | 3 / 32 | Rs -2,732 | ABS w5 k2 b6 turn T30 | 12 | 42% | Rs 3,116 | Rs 260 | 0.11 |
| Exhaustion NIFTY | 32 / 32 | 3 / 32 | Rs -5,064 | EXH X30 k3 b6 flip T30 | 31 | 23% | Rs 1,369 | Rs 44 | 0.32 |
| Exhaustion BANKNIFTY | 32 / 32 | 10 / 32 | Rs -3,264 | EXH X30 k2 b3 flip+close T30 | 39 | 41% | Rs 2,925 | Rs 75 | 0.27 |

Every chosen version uses the 30-minute time stop. Absorption's choices both use the wide band (6 bp) and the "turn"
confirmation, which also makes them rare. The design twin p values are flattered by choosing the best of 32 and are not
tests. All 128 versions are in `results/selection.json`.

## Holdout (7 days, 4 - 13 Nov), detail

| cell | signals | trades | win | net | 2x slip | bid/ask | avg win | avg loss | worst day | best day | calls (n, net) | puts (n, net) | exits (n, net) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ABS NIFTY | 12 | 8 | 25% | Rs 2,153 | Rs 1,971 | Rs 2,521 | Rs 2,582 | Rs -502 | Rs -2,297 | Rs 3,785 | 5, Rs 3,471 | 3, Rs -1,317 | time 2 (Rs 5,164), index_stop 3 (Rs -2,843), breakeven 3 (Rs -167) |
| ABS BANKNIFTY | 15 | 6 | 50% | Rs 2,257 | Rs 2,079 | Rs 1,885 | Rs 1,933 | Rs -1,181 | Rs -2,478 | Rs 2,774 | 4, Rs 2,658 | 2, Rs -401 | time 4 (Rs 5,313), index_stop 2 (Rs -3,056) |
| EXH NIFTY | 18 | 14 | 21% | Rs -3,728 | Rs -4,055 | Rs -3,326 | Rs 1,793 | Rs -828 | Rs -3,542 | Rs 1,699 | 8, Rs -2,939 | 6, Rs -789 | time 3 (Rs 5,380), index_stop 9 (Rs -9,064), breakeven 2 (Rs -43) |
| EXH BANKNIFTY | 24 | 22 | 23% | Rs -2,072 | Rs -2,765 | Rs -2,244 | Rs 2,445 | Rs -841 | Rs -3,559 | Rs 4,033 | 4, Rs 169 | 18, Rs -2,241 | time 11 (Rs 9,400), index_stop 11 (Rs -11,472) |

Signals not traded were blocked by an open trade or the 4-a-day cap; none was unpriced in the holdout (design: a few
unpriced for no index price, mostly 15 Oct, when the NIFTY index row starts at 10:30).

**Tests (m = 4,512 = 4,376 after R183 + 128 design versions + 8 tests):**

| test | p | BH q (m 4,512) | q within R184's 8 |
|---|---|---|---|
| ABS NIFTY vs random twins (T1) | 0.390 | 1.00 | 0.60 |
| ABS NIFTY mean > 0, day bootstrap (T2) | 0.324 | 1.00 | 0.60 |
| ABS BANKNIFTY vs random twins (T1) | 0.249 | 1.00 | 0.60 |
| ABS BANKNIFTY mean > 0, day bootstrap (T2) | 0.307 | 1.00 | 0.60 |
| EXH NIFTY vs random twins (T1) | 0.454 | 1.00 | 0.60 |
| EXH NIFTY mean > 0, day bootstrap (T2) | 0.800 | 1.00 | 0.80 |
| EXH BANKNIFTY vs random twins (T1) | 0.237 | 1.00 | 0.60 |
| EXH BANKNIFTY mean > 0, day bootstrap (T2) | 0.622 | 1.00 | 0.71 |

Twin sets' mean net a trade (10th .. 90th percentile): ABS NIFTY Rs 188 (Rs -237 .. Rs 630); ABS BANKNIFTY Rs -46 (Rs -859 .. Rs 807); EXH NIFTY Rs -282 (Rs -559 .. Rs 0); EXH BANKNIFTY Rs -294 (Rs -655 .. Rs 72). Random entries at the same minutes, side mix and exits in
the locked week lost on 3 of 4 cells; the NIFTY absorption twins made money too (mostly calls in the rally).

**Power** (smallest mean edge a trade detectable with 80% power; sd = the holdout trades'): ABS NIFTY Rs 1,447 (5%) / Rs 2,725 (BH level), n 8, sd Rs 1,643; ABS BANKNIFTY Rs 1,939 (5%) / Rs 3,653 (BH level), n 6, sd Rs 1,908; EXH NIFTY Rs 844 (5%) / Rs 1,589 (BH level), n 14, sd Rs 1,268; EXH BANKNIFTY Rs 922 (5%) / Rs 1,736 (BH level), n 22, sd Rs 1,736.

**Pooled (descriptive):** absorption 14 trades, 36% won, Rs 4,411 (2x
Rs 4,050); exhaustion 36 trades, 22% won, Rs -5,800 (2x
Rs -6,820).

## Data and caveats

- **The recorder may hide absorption.** The 1-second file the app keeps drops big prints that did not move the price
  (R182: 17-35% of futures volume) - exactly the "heavy volume, no price move" absorption reads. R184's absorption is
  absorption of the volume the recorder KEPT. The minute recorder (complete volume, the better delta in R182) stops on
  28 Oct, so it could not cover a design / holdout split.
- **Delta is an estimate** (tick rule; Zerodha gives no aggressor flag) and tracks price only loosely (R182: +0.27).
- **Missing data in the holdout:** NIFTY futures minutes recorded of 375: 11-04 338, 11-05 371, 11-06 319, 11-09 293, 11-11 340, 11-12 375, 11-13 346; thin minutes up to 91 a day. Thin
  minutes cannot be part of a signal, so these days give fewer signals.
- **One regime.** The locked week was mostly a one-way rally; the calls / puts split shows it. 7 days cannot separate
  "the rule" from "the week".
- R181's loader kept one recorded role per option strike (CE or REC_CE, the same token); R184 merges both. Noted for any
  later reuse of that loader.
- 1 lot throughout; the worst holdout day was Rs -3,559 (inside the -Rs 13k month / -Rs 40k drawdown account rule).

## How it was run

- `r184.py --stage design` (the code refuses any other day split) -> `results/selection.json`, committed before the read
  flag.
- `results/FINAL_READ` (2026-11-16T01:40:19.815528+00:00) committed, then `r184.py --stage holdout` once at 2026-11-16T01:40:43 UTC
  -> `results/holdout.json` (records PREREG's and the selection's SHA-256) and `results/decision.json`.
- Tests: `python -m pytest research/hunt/r184_absorption_exhaustion -q` (30 tests, including the look-ahead guard: every
  signal at minute r is unchanged when all later minutes are replaced by random values, for all 32 signal settings).
- No amendment to PREREG.md.
