# R53 gold book: notes and deviations (15 Oct 2026)

Code: `rules.py` (one adapter per rule, on the study's own code), `twins.py`, `run.py`; tests `test_gold.py`.
Results: `../results/gold_repro.json`, `../results/gold_design.json`, `../results/gold_final.json` (read once),
per-trade CSVs in `../results/trades/gold_*.csv`. Money: USD per standard lot (100 oz) after the studies' costs.
Signal source of every rule: XAUUSD itself (U), buys only; no option prices are involved.

## 1. Data: what "filled" means (answer to the coordinator, 15 Oct)

**Nothing was forward-filled or invented by R53.** `xauusd_m1_bid_to20261012_filled.csv.gz` (cache folder, not in git)
is the R53 file `xauusd_m1_bid_to20261012.csv.gz` plus **real Dukascopy 1-minute BID bars for whole days that the file
was missing**.

- The release file (re-uploaded 1 Oct 2026 by `gold.yml`, whose `dukascopy-node ... -s` skips failed requests silently)
  lacks 86 weekdays: 18 in Oct 2023, 12 in Jan 2024, 18 in Mar 2024, 15 in Oct 2024, **7 at the start of the design
  (21-29 Nov 2024)**, Good Friday 2025, and **14 inside the final test (21-29 May and 21-31 Aug 2026)**, plus Good
  Friday 2026.
- Those days were fetched from the same source, Dukascopy's `XAUUSD/.../BID_candles_min_1.bi5` day files, decoded by
  `research/hunt/r53/fetch_xau_tail.py`'s own `decode()` (the decoder that made the file's 1-12 Oct tail), and merged
  with the file's rows winning on any duplicate timestamp. Check: a re-fetched day the file already had (3 Jun 2025)
  matched it bar for bar (1,380 of 1,380 minutes, every open/high/low/close difference 0.0).
- Added: 113,833 real minutes from 99 day files (85,474 in the warm-up before 20 Nov 2024; 9,312 in design: 21-29 Nov
  2024; 19,047 in the final test: 21-29 May and 21-31 Aug 2026). Three day files are empty at the source and stay
  missing: 29 Mar 2024, 18 Apr 2025, 3 Apr 2026 (Good Fridays, the market was shut).
- Why: without them the design loses its first 7 days and the final test loses 14 of its ~138 trading days (10%), and
  every rule's trade count drifts. A missing day is not a no-trade day; it is a gap the code would bridge.
- Prices: every entry and exit is still a price printed by Dukascopy in that minute. See section 3 for the old scripts'
  own in-session grid handling (`liquidity_gold.sessions`), checked trade by trade.

## 2. Reproduction (old window Oct 2023 - 25 Sep 2026; `run repro`)

Faithfulness is proven three ways:

1. **Trade for trade against the study's own function on the real data**, for every rule whose script exposes one
   (GB1/GB2 `option1`/`option2`, GB3/GB4 `gold_tas.trades`, GB5 `gold_ai_opinion.trade`, GB7/GB10
   `liquidity_gold.simulate`, GB8/GB9 `gold_1h_plus.simulate`, GB11 `gold_candles.run`): identical lists of nets, on
   the release file and on the filled file (`identical_to_study_fn: true` in `gold_repro.json`).
2. **Against the scripts' own main() on the same real window** for the main()-only scripts: `gold_reopen.py`
   (GB6 +$57,650 / 769 trades; 23:00-23:55 +$78,949 / 755), `gold_mtf_lock.py` (GB12 keep-half 8 h +$146,871 / 1,026;
   giveback 1.5 +$121,597 / 1,071), `gold_trend_lock.py` (GB13 3 ATR +$185,829 / 56; 5 ATR +$312,269 / 56): the
   adapters give exactly these numbers.
3. The same on synthetic bars in the tests (every adapter vs the study function; GB6 vs `by_reopen` / `main`; GB12 vs
   `gold_mtf_lock.main`'s baseline row).

Against the old **write-ups** the numbers are close but not equal, because the old studies ran on an earlier download
with its own holes (the workflow skips failed days silently; the exact old file was not kept). With the filled file
there are 3-8% MORE trades than the write-ups everywhere (e.g. GB6 769 vs 716 reopens), so the old file was missing
~50 days, and on the R53 release file (86 days missing) there are fewer. Where the old held-out year can be compared
it matches to the dollar (GB12 +$82,875 and +$67,929; GB13 +$90,705 and +$173,328 for Oct 2025 - Sep 2026), so the
differences sit in the old file's 2023-2025 holes, not in the code.

| rule / variant | write-up (n, net) | adapter, filled file (n, net) | with R53 gap fills |
|---|---|---|---|
| GB1 orig (M15, H1 bias, 2R) | 265, +15,879 | 281, +15,938 | +15,938 |
| GB1 rt_1R | 266, -7,470 | 281, -9,280 | -9,280 |
| GB1 rt_oppcross | 316, +13,456 | 332, +11,424 | +11,424 |
| GB2 orig (M5, +100%) | 269, +3,957 | 284, -2 | -4 |
| GB2 rt_200 | 269, -25,253 | 284, -29,212 | -29,214 |
| GB3 orig (15m) | 947, +72,557 | 1,017, +61,966 | same |
| GB3 rt_notargets | 947, +135,707 | 1,017, +128,080 | same |
| GB4-4h | 59, +154,843 | 64, +157,123 | same |
| GB4-30m (no targets) | 470, +175,452 | 507, +168,633 | same |
| GB5-1h | 445, +87,609 | 481, +82,558 | +78,967 |
| GB5-4h | 165, +131,910 | 169, +124,248 | +124,036 |
| GB6 orig (reopen +5/+120) | 716, +62,714 | 769, +57,650 | same |
| GB6 rt_fixed2300 | 707, +81,223 | 755, +78,949 | same |
| GB7 | 133, +40,672 | 144, +46,076 | same |
| GB8 | 81, +101,195 | 85, +102,808 | same |
| GB9 | 145, +121,593 | 153, +126,020 | same |
| GB10 | 488, +33,309 | 539, +32,890 | same |
| GB11 orig | 424, +37,730 | 460, +38,391 | same |
| GB11 rt_4candles | 450, +21,033 | 487, +17,565 | same |
| GB12 orig | 947, +154,337 | 1,026, +146,871 | same |
| GB12 rt_giveback1.5 | 991, +122,485 | 1,071, +121,597 | same |
| GB13 orig (3 ATR) | 52, +207,628 | 56, +185,829 | same |
| GB13 rt_giveback5 | 52, +310,766 | 56, +312,269 | same |

## 3. Deviations and choices (all made before any R53 number was seen, except where stated)

- **GB2's chart**: the PREREG does not say M5 or M15. R53 uses **M5** (the study's only positive buys-only option-2
  cell, i.e. its "closest"); M15 is not run.
- **GB4 and GB5** are two originals each; they are reported as separate ideas (`GB4-4h`, `GB4-30m`, `GB5-1h`,
  `GB5-4h`), each with walk-forward on its original alone.
- **Gap fills (PREREG section 2)**: the studies already fill a gapped stop at the bar's open in TAS, the dip lock and the
  trend lock. Two did not: `gold_session_strats.walk` (GB1, GB2, GB11; 1-minute bars, so it almost never matters: GB2
  -$2 over three years) and `gold_ai_opinion.trade` (GB5, 1h/4h bars held over weekends: -$3.6k / -$0.2k over three
  years). R53 fills those stops at min(stop, open) too (`gap=True`); the reproduction uses the study's fill (`gap=False`).
  Targets fill at their level as in the studies (when a bar gaps over a target that is the worse price).
- **Swap**: only `gold_trend_lock.py` charged it (GB13, $40 a lot a night, x3 Wednesday, rollover 21:00 UTC). The other
  studies did not, and R53 follows them; `gold_*.json` shows `swap.usd_if_charged` for every rule that holds overnight
  (TAS, AI opinion, 24x5 liquidity) so the cost can be read.
- **Both-sides simulation**: GB1, GB2 and GB11's scripts trade both sides one position at a time and then report the
  buys; R53 does the same (a short can block a later buy), as the studies' "buys only" rows did.
- **Exit time of an intrabar exit** on 15m-4h bars (TAS, AI opinion, dip lock) is recorded as the bar's last minute
  (the bar's first minute when the stop filled at a gapped open). It affects only the hold-matched twins' hold times.
- **Trades open at the data's end** (12 Oct 2026 23:59) are closed at the last bar, as the studies close theirs.
- **Old-script quirks kept as written** (they read data completeness after the fill, not prices; harmless on complete
  data, stated here):
  - `liquidity_gold.sessions` (GB7-GB10) drops a session with under 60% of its minutes and forward-fills missing minutes
    inside a session on a fixed grid. Checked on the design trades: **no entry of any rule is
    on a minute that does not exist in the data** (GB5-1h has 2 entries whose 1h bar is labelled 23:00 while its first
    real minute is 23:01; the price used is that real 23:01 open). **3 exits of GB9** (of 69) are booked at a grid minute
    inside the daily break or after a holiday early close (22 May 2025 21:59, 24 Dec 2025 21:59, 19 Jan 2026 20:59): the
    price is the last real close carried forward by the study's grid (a stale but printed price, never an invented one).
    Against the next real open they would have made +$1.2k more in total (-$13, +$1,102, +$132): the study's convention
    is the conservative one here and is kept. The 24x5 grid carries forward 30,114 design / 12,434 final-test minutes
    (mostly the daily 21:00-22:00 break and holidays); the India grid 21 design / 0 final. `gold_final.json` counts the
    final-test trades with an entry or exit on such a minute (`fills_on_missing_minutes`).
  - `gold_reopen.by_reopen` (GB6) needs the exit minute (+120) to exist, else the night is skipped.
  - `gold_mtf_lock` (GB12) marks "the last candle before a break" by the next candle's start; the break schedule is known
    in advance.
  The look-ahead test therefore perturbs (rather than truncates) the data after the fill for GB6-GB10; every other rule
  passes both the truncation and the perturbation test.
- **Twins**: random entries with the rule's own exits for GB1, GB2, GB5, GB11, GB12 (every candidate bar inside the
  rule's entry window in the period is traded once by the rule's exit code; a twin samples as many as the rule has
  trades; twin trades may overlap). Hold-matched for GB3, GB4 (exits need TAS's tracker line), GB6 (a clock rule:
  random minute of any weekday, same hold), GB7/GB10 (India-hours window 03:50-09:00 UTC), GB8/GB9 (24x5 window
  00:05-23:00, Friday to 19:00) and GB13 (exits need the Supertrend; swap charged on the twins too). Hold-matched
  twins keep gold's drift, so they are a harder bar for buys-only rules than a zero-mean null.
- **Speed**: twin candidates for the 1-minute walk rules use a vectorised copy of `walk()` (`FastWalk`), tested equal
  to it on 400 random entries with and without gap fills.

## 4. Design (20 Nov 2024 - 31 Mar 2026, 354 trading days) and picks

Picks (highest design net, not the original, > 0): **GB3 -> rt_notargets, GB6 -> rt_fixed2300, GB13 -> rt_giveback5**.
All other rules: original only in the final test. Full table in `gold_design.json` and the R53 write-up.

## 5. Final test (1 Apr - 12 Oct 2026, 138 trading days), read once at 04:23 UTC 15 Oct 2026

`final_read_gold.json` locks GB1-GB13 originals plus GB3:rt_notargets, GB6:rt_fixed2300, GB13:rt_giveback5. Not
re-run. Two final-test trades touch the liquidity scripts' forward-filled grid (`fills_on_missing_minutes`):

- **GB8**, exit 3 Jul 2026 (US holiday, early close 16:58 UTC): the Friday 20:40 cut-off is priced at the last real
  close (16:58), +$3,839. At the next real open (5 Jul 22:00) it would be +$4,566. Achievable, conservative; kept.
- **GB9**, entry 7 Apr 2026 21:00 UTC: the study's 24x5 grid lets an entry fall inside the daily 21:00-22:00 break, at
  the 20:59 close carried forward (a stale price nobody could buy at). At the next real price (22:00 open, $13.23
  higher) the trade nets +$6,955 instead of +$8,278: **GB9's final net is overstated by $1,323 (+$26,813 -> +$25,490)**.
  Its p was computed on +$26,813. Not corrected in the json, because a re-run of the final is not allowed; the gate
  reader should use +$25,490. In design no GB9 entry fell on such a minute.
