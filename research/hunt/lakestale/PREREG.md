# Stale repeated prints in the lake: pre-registration

Written 18 Oct 2026, before the rule was run on the lake or on any study. Research only (no `android/**`).

## Why

R77 (branch `claude/r77-absorption`, `results/posthoc.json`) found a fill on a fake price. On 28 Sep 2026 the FINNIFTY
24650 CE shows Rs 1,855.85 from 09:40 to 09:52: open = high = low = close, zero volume, the same price every minute. It
first traded at 09:53 at Rs 192.45. The 24600 and 24700 calls traded all morning near Rs 170-215. One R77 trade bought
at Rs 1,855.85 and lost -Rs 1,00,027. R51's stray-print step (`research/hunt/lakeclean.py`, step 2) cannot see it: its
reference is the median of the row before, the row and the row after, and all three repeat the same wrong price.

## The rule (lakeclean step 3, `stale=True`; frozen here)

On the option rows left after steps 1 (foreign rows) and 2 (stray prints), per contract (side, strike) and session,
taking the first row of each minute (the row r51data's store keeps):

1. **Stale run:** at least `STALE_MIN_RUN = 5` consecutive rows that are flat (open = high = low = close), have
   volume 0 and repeat one price.
2. **Far from the neighbours:** the row's price is outside the no-arbitrage band set by the nearest lower strike
   (K_lo) and nearest higher strike (K_hi) of the same side on the same day:
   - calls: C(K_hi) <= C(K) <= min(C(K_lo), C(K_hi) + (K_hi - K));
   - puts: P(K_lo) <= P(K) <= min(P(K_hi), P(K_lo) + (K - K_lo)).

   A neighbour's price is the close of its latest TRADED row (volume > 0) at or before this minute, at most
   `STALE_AGE = 15` minutes old. The row is flagged when its price is above hi x (1 + `STALE_TOL` = 0.5) + 2 ticks, or
   below lo / 1.5 - 2 ticks. With no usable neighbour on one side, that side has no bound; with none on either side the
   row is kept.
3. **Action:** flagged rows are dropped from the store (as stray opens are) and logged with `why = stale_run`. The ATM
   label and the expiry test keep reading the rows after step 1 only, as they do today, so step 3 changes option prices
   only, never which days or minutes a rule considers.

Why this rule: a zero-volume minute is not a trade; the lake carries the last price forward. That is usually right
(quiet options), so flatness and zero volume alone are never enough. A carried price is wrong when it breaks the
no-arbitrage order of the strikes around it, which needs no model, no rate and no volatility. The 50% plus 2-tick margin
allows for a neighbour's last trade being up to 15 minutes old and for cheap options near the minimum tick.

Parameters were set from the principle and the one known case, before any count. They will not be changed after the
measurement below, whatever it shows.

## Look-ahead

- The band uses only rows at or before the minute it judges (test: `test_band_reads_nothing_after_the_minute`).
- The run length is **two-sided**: a row early in a 13-minute run is flagged because the run goes on. This is an
  offline repair of the data, like step 2's median, which already reads the row after. It decides only whether a
  lake price was real; it does not tell a strategy anything about later prices.
- No study's decision reads these option prices: every rule in R51 (Donchian and the R44 near-misses), R72, R73, R76
  and R77 takes its signal from the index, R77's volume context sums option volumes (a dropped row has volume 0), and
  the ATM label and expiry flag are unchanged (test: `test_store_changes_option_prices_only`). Fills, stops and exits
  read the option prices, which is exactly what the repair is for.
- For information, a causal version (a row counts only from the 5th row of its run, using no later row) is counted too
  (test: `test_causal_flags_do_not_depend_on_later_rows`). The two-sided rule is the one applied.

## What will be measured (once)

1. Over the WEEK option files the studies use (20 Nov 2024 - 13 Oct 2026, 5 indices, after steps 1-2): rows flagged,
   contracts (day, side, strike) and days touched, by index; the number of stale-run rows kept (inside the band, or
   not judgeable); the causal count. The largest flagged runs are listed for a sanity read; any that look wrong are
   reported as false positives, not fixed by a new rule.
2. The stores rebuilt with step 3 (`R51_CLEAN=1 R51_STALE=1`, folder `clean_stale`) and the same code re-run on them,
   main variants at least:
   - R51 Amendment A: R44-donchian20-RT (the passing rule) and its 6 near-misses (`amend.py run`);
   - R72: the 12 cross-index tests (C1 Breadth Donchian passed);
   - R73: the 36 trend / wait tests (T7 Supertrend passed);
   - R76: B0 and the 5 lock variants on Liquidity 15+5;
   - R77: the 8 absorption tests.

   Same rules, grids, seeds, twins, walk-forward picks and gates. The final periods were already read; this re-prices
   them, it does not re-select. For each study: trades, net (design, walk-forward, final), twin p, BH q and the gate
   verdict, against the committed results (the committed Amendment A stores were rebuilt here first and reproduce the
   committed drop counts exactly). A verdict "changes" when any gate flips. Every change is reported, including a
   passed rule getting worse.
