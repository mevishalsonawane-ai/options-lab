# R183 pre-registration: skip R51 / R72 entries when the index is "sideways"?

Written 15 Nov 2026, **before any R183 number was computed** (no filter value, no trade, no parity run); committed on
`claude/r183-sideways-filter` as its own commit before the code's first run on data. Research only: nothing in
`android/**` changes. Owner: Boss (they/them).

Boss, 15 Nov 2026, after R51 Donchian 20 and R72 Breadth Donchian lost on 9 - 13 Nov (58 paper trades, 41% won):
*"How could we ignore sideways markets"*. Both arms buy an option when the index breaks out of a range; on sideways days
breaks tend to fail. The question: does skipping new R51 / R72 entries when the index is sideways improve them?

**Signal source: the index the arm signals on.** Every filter below is computed only from that index's own 1-minute bars
(and 5-minute bars built from them) strictly BEFORE the signal bar. No option price, OI or volume is used (the index has
no volume). Entries, strikes and exits are unchanged; a filter can only skip an entry.

## 1. Baseline (today's live rules) and parity

**B = R179's V4**: today's rules + R151's picker as live (R72 0.4656, R51 0.4469) + R162's breakeven lock at +10% (BE10).
Rebuilt exactly as R179 (`r179study.py`): `S6.units`, `R7.apply_rule(arm, us, None)`, `r179study.priced(arm, us, "A",
True, lots)` (BE10), `S6.walk`, then the picker's post-walk keep (`P.kept`). The R176_POST=1 stores (built to 19 Oct 2026).

Parity, asserted before anything else is read: B at 1 lot on the year reproduces R179's published V4 (results/year.json):
- R72: final 604 trades / Rs 1,67,233; whole year 1,069 / Rs 3,24,629.
- R51: final 655 / Rs 1,44,802; whole year 1,367 / Rs 2,49,903.
(and today's rules A at 1 lot on the final = R172's 1,200 / Rs 1,70,336 and 1,668 / Rs 1,31,546, as R179 asserts). Trades
must match exactly and net within Rs 1; if not, the study stops and reports the gap.

## 2. How a skip works (as the app's picker)

Every candidate is walked with B's prices; the picker's skips and then the filter's skips are removed from the record
afterwards. **A skipped entry still holds its side and counts in the day's caps** (the app's rule for the picker, CLAUDE.md
6 Nov), so a filter can never free a place for a trade B would not have made: each variant's trades are a subset of B's.
A filter value that cannot be computed (too few bars, no earlier sessions) means **no skip**.

## 3. The filters (fixed now, with their grids: 16 variants)

Notation: the trade's signal-entry row is `er` (1-minute row from 09:15); the **signal bar** is row `s = er - 1`. Today's
rows used are **0 .. s - 1 only**. 5-minute bars: today's 1-minute rows grouped as 09:15-09:19, 09:20-09:24, ...; a
5-minute bar is used only if all five of its rows are < s (its last row <= s - 1); O = first finite open, H = max high,
L = min low, C = last finite close; an all-NaN bar is dropped. The 5-minute series for ADX / ER / CHOP is the previous 3
trading sessions' full 5-minute bars (from the store) followed by today's complete bars (a continuous chart, as on a
5-minute chart).

| id | filter | skip the entry when |
|---|---|---|
| ADX15 / ADX20 / ADX25 | ADX(14), Wilder's smoothing, on the 5-minute series (needs >= 29 bars) | ADX < 15 / 20 / 25 |
| ER6_20 / ER6_30 / ER12_20 / ER12_30 | Kaufman efficiency ratio over the last N 5-minute closes: abs(C_t - C_t-N) / sum of abs(C_i - C_i-1) over those N steps (needs N + 1 closes) | N = 6 or 12, ER < 0.20 / 0.30 |
| TX3 / TX5 / TX7 | crossings of today's running TWAP: TWAP at row r = mean of (H + L + C) / 3 over today's finite rows 0 .. r (the index has no volume, so time-weighted); for each complete 5-minute bar today, d = C - TWAP at its last row; a crossing = a sign change between consecutive non-zero d | crossings so far today >= 3 / 5 / 7 |
| RF40 / RF60 | today's range so far (max H - min L over rows 0 .. s - 1) / ADR20 (mean full-session H - L of the previous 20 sessions; needs 20) | ratio < 0.40 / 0.60 |
| RT70 / RT85 | time-of-day aware: today's range so far / the mean over the previous 20 sessions of their range over the same rows 0 .. s - 1 (needs 20) | ratio < 0.70 / 0.85 |
| CH55 / CH62 | Choppiness index(14) on the 5-minute series: 100 x log10(sum of the 14 true ranges / (max H - min L of the 14 bars)) / log10(14) (needs 15 bars) | CHOP > 55 / 61.8 |

R181's "VWAP-chop" reading was looked for and is not in the repo (R181 has no chop measure), so it is not added.
Thresholds were chosen from the usual textbook levels, before any data was looked at.

## 4. Selection (1 lot, per arm; mirrors R178)

Window **20 Nov 2024 - 19 Oct 2025**, split H1 = 20 Nov 2024 - 30 Apr 2025, H2 = 1 May - 19 Oct 2025.
- eligible = the variant's net (B + filter) is above B's net in H1 AND in H2;
- chosen = the eligible variant with the largest whole-window net gain over B (ties: the earlier in the table's order);
- no eligible variant: nothing is chosen for that arm and no test is run for it.
Reported per variant and arm: trades, win rate, net, H1 / H2 nets, gain, skipped count, worst month, max drawdown.

Honesty: R151's picker was trained on 20 Nov 2024 - 31 Mar 2026 and frozen, so B is in-sample for the picker in the
selection window and in the test year's early part (the filters are not). The selection window was read by R51 - R178 for
other rules, never for these filters on B.

## 5. The one test (the final read)

The chosen variant per arm, on **20 Oct 2025 - 19 Oct 2026**, read ONCE (`results/FINAL_READ` written before the read;
the run refuses to start if it exists). Periods as R176 / R179 (K6.YEAR_PERIODS): early (to 31 Mar 2026), final
(1 Apr - 13 Oct 2026), last 6 months (20 Apr - 19 Oct 2026), the year.

Gates (1 lot; all must hold; `S6.gates(per, q, K6.YEAR_PERIODS, K6.YEAR_BETTER)` with S = B + filter, A = B):
1. net > 0 in early, final, last 6 months and the year;
2. net above B's in early, final and the year;
3. selection twin BH q <= 0.10 (below), and the empirical twin p < 0.05;
4. at least 40 final trades;
5. the account stop rule in every period: max drawdown <= Rs 40,000 and worst month >= -Rs 13,000 (B already breaks
   it in the final; the report says which pass everything else).

**Twins**: 2,000 random-skip twins (seed 183): drop as many of B's year trades at random as the filter skipped
(`L.selection_twin`). Empirical p = (1 + twins with net >= the filter's) / 2,001. Because the smallest empirical p
(0.0005) can never pass BH at this m on its own, the **BH p** is the normal tail of z = (filter net - twin mean) /
twin sd (one-sided), as R177-EMA; both are reported.

**BH count**: the running count after R182 is 4,073 (R180 4,051, R181 4,066, R182 4,073); R177-EMA's 270 tests were run
after R182 (it counted from a different base), so they are added here to be conservative: prior = 4,343. R183 adds
**32 selection comparisons (16 variants x 2 arms) + up to 2 tests**: **m = up to 4,377**. q within R183's tests is
also shown.

Reported, not gated: every variant on the test year at 1 lot (descriptive, after the one chosen variant's read), and the
chosen variants at 3 lots.

## 6. The recent days (descriptive, never a test)

The stores end 19 Oct 2026, so 9 - 13 Nov cannot be replayed by the engine. Instead: the phone's paper orders of
R51 Donchian 20 and R72 Breadth Donchian on 9 - 13 Nov (the diagnostics R182 read; outside git, read-only) and the
phone's market recordings (index level each second for NIFTY and BANKNIFTY; for FINNIFTY / MIDCPNIFTY only the near
future is recorded, used as a labelled proxy; SENSEX not recorded: not computable). For each paper entry the chosen
filter's value is computed from those minute bars before the entry minute (earlier sessions from the recordings, then
the lake to 19 Oct where recordings are missing), and the report says which of the trades it would have skipped and
their paper P&L. Gaps in the recordings are stated. This is a small, after-the-fact sample and is never used to choose.

## 7. Tests (pytest, synthetic)

Each filter's arithmetic (ADX, ER, TWAP crossings, range ratios, CHOP) on hand-made bars; 5-minute bars use only rows
< s; too few bars -> no skip; the selection rule (both halves, ties, none eligible); the post-walk mask keeps a subset;
the BH count; the normal-tail p. **Look-ahead guard**: a filter's value at the entry is unchanged when every bar from the
signal row on is replaced by noise.
