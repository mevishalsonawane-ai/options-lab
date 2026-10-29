# R132 pre-registration: does R123's network improve Solo (midday)'s picks?

Written 29 Oct 2026, before any R132 number was computed. Boss asked to put R123's neural network "under Jarvis, also in the
Solo family". Jarvis's own paper strategy is Solo (midday) (`android/engine/.../orb/SoloMidday.kt`, `IraSolo.kt`). R132 asks
one question: **does the network (N1) make Solo's picks better?**

Before writing this file only the network's forecast columns were described (`rg60` and `pdir60` at the 12:00 row: mean, spread;
no outcome, no trade, no Solo signal was computed).

## 0. Honest limits, stated first

- **No new model.** The network is R123's N1 with its saved out-of-sample forecasts:
  - walk-forward folds F1 2023, F2 2024, F3 2025, F4 1 Jan - 15 Mar 2026 (each fold's net trained only on earlier data);
  - the holdout fit for 1 Apr - 19 Oct 2026.
- **The holdout is not fresh.** Apr - Oct 2026 was read by R123-R131. It is read once here, after the walk-forward is committed.
- **Solo's own design overlaps the walk-forward.** Solo's research tuned its rule on data before Jul 2024 (its "unseen period" was
  Jul 2024 - Oct 2026), so Solo's plain numbers for 2023 - Jun 2024 are in-sample for Solo. The question here is only whether the
  network's veto or re-pick beats Solo's own pick on the same days; that comparison is paired.
- **Solo trades at most once a day.** Over ~800 walk-forward sessions and ~135 holdout sessions the plain rule may give fewer
  than 300 walk-forward and fewer than 100 holdout trades. If so, the family cannot reach R123's trade-count gates; the gates are
  NOT lowered and that is reported plainly.
- **A pass earns only a logged-only forward test, decided by Boss.** Nothing goes on paper by itself (the S5 exception in
  CLAUDE.md is for S5 only).

## 1. Solo (midday), ported exactly from `SoloMidday.kt`

Signal source: **the underlying index** (NIFTY, BANKNIFTY, FINNIFTY 1-minute bars); the option bought is chosen by the strike rule.

- Rows: row r is the minute bar starting 09:15 + r (R123's grid). The 11:59 bar (row 164) closes at 12:00.
- Per index and day: `o` = the 09:15 bar's open, `c` = the 11:59 bar's close, `H`/`L` = high/low of the bars 09:15..11:59.
  Needed: the 11:59 bar present, the first bar by 09:16, at least 100 bars before 12:00.
- ATR14: the mean true range of the 14 completed daily candles before today (daily candles from the lake's minute bars; 15
  sessions needed).
- Signal: `|c - o| >= 0.5 ATR14`; up when `c > o`; the close in the outer 25% of the range (up `(H - c)/(H - L) <= 0.25`, down
  `(c - L)/(H - L) <= 0.25`). Strength `|c - o| / ATR14`; the strongest signalling index is taken (ties A-Z). No trade on an index's
  expiry day (the lake's expiry days, R25's rule as R123 uses it); the turn passes to the next strongest. One trade a day.
- The 12:00 index price: the 12:00 bar's open, else the 11:59 close.
- Option: ATM of the 12:00 price (half up to the step: NIFTY/FINNIFTY 50, BANKNIFTY 100), 4 steps in the money (call ATM - 4
  steps, put ATM + 4); the lake's nearest weekly/monthly contract. Not tradable (no traded open in the 12:00-12:03 bars): one step
  less in the money, then one step more; else the index passes the turn to the next strongest (as the app's skip of an untradable
  strike - the research's `pick_leg`). Bought at the first traded open of rows 165-168, 1 lot at today's lots.
- Exit, read on the index's closed bars 12:00..14:29 (rows 165-314):
  - index stop: a bar CLOSE at or beyond `entry index - side x 0.3 ATR14`;
  - breakeven lock: once a bar's high (call) / low (put) has gone `0.45 ATR14` its way (75% of 2R), a LATER bar touching the entry
    index level exits; the stop is checked first on each bar;
  - else 14:30.
  - The option is sold at the first traded open of the 3 bars after the deciding bar (14:30: rows 315-317), else at its last
    traded close up to then.
- Money: R123/R128's `Costs("dated")`, `Fills("liq")` (5-minute volume ticks), both legs' charges; 2x slippage = net minus the
  slippage once more (R128's `leg_net`).

## 2. The network's inputs to the filters (all read at the 12:00 decision row, row 164)

From `wf_rows_preds_N1.parquet` / `holdout_rows_preds_N1.parquet` (R123's N1, out-of-sample):
- `ev_j`, j = 2 x cell + side (side 0 call, 1 put): the network's expected net of the **ATM** option over R123's 8 target/stop/time
  cells. "Solo's side" = calls when Solo buys a call, puts when a put. Solo buys 4 ITM, not ATM: the EV is a proxy, stated.
- `rg60`: the forecast log range (%) of the index's next 60 minutes.
- `pdir60`: the forecast P(index higher in 60 minutes).

The universe: (index, day) pairs with a row-164 forecast. Solo's plain rule runs on the same universe, so every idea is compared
with Solo on the same days.

## 3. The family (5 ideas; BH over these 5)

Each veto applies to **Solo's own pick of the day** (it does not pass the turn to another index), so the idea's trades are a
subset of Solo's trades on the same days.

| id | idea | rule |
|---|---|---|
| F1 | EV veto | take Solo's trade only when `max over the 8 cells of ev_j` for Solo's side is ≥ 0 |
| F2 | quiet-hour veto | skip when `rg60` is in the bottom third: below the 33.3rd percentile of the same index's `rg60` at row 164 over its previous 120 sessions in the forecast files (at least 20 earlier sessions, else no veto) |
| F3 | direction veto (expected to fail; the network is a coin at direction) | take only when `pdir60 > 0.5` for a call, `< 0.5` for a put |
| F4 | network re-pick | among the signalling, tradable indices, take the one with the highest Solo-side best EV (as F1's), instead of the strongest; no threshold |
| F5 | F1 and F2 | both vetoes |

Reference (not in the family): Solo's plain rule on the same universe.

## 4. Twins, statistics and gates

- **Twins (1,000):**
  - F1, F2, F3, F5: random vetoes. In each fold, the same number of Solo's trades as the idea vetoed in that fold is dropped at
    random. p = (1 + #twin totals ≥ the idea's total) / 1,001.
  - F4: on each day with two or more signalling, tradable indices, a random one of them is taken (other days as Solo).
    p as above.
  - BH over the 5 walk-forward p's.
- **Paired difference** (idea minus plain, the same days): total and per-day, with a 1,000-draw day bootstrap 90% interval.
- **Gates (R123's, unchanged), on the idea's own trades:**
  - walk-forward: ≥ 300 trades; average net > 0 at 1x and 2x; ≥ 3 of the 4 folds positive; q ≤ 0.10;
  - holdout (re-read): ≥ 100 trades; average net > 0 at 1x and 2x; the account stop (drawdown above −₹40,000, no month below
    −₹13,000).
- **"Improves Solo"** additionally needs the paired difference > 0 in the walk-forward and the holdout. It is reported; it is not a
  substitute for the gates.

## 5. Look-ahead guard

- Solo's decision reads index bars up to row 164 only (plus the 12:00 bar's open as the entry price). A test changes every bar
  from row 165 on (except that open) and the decision must not move.
- F2's threshold reads only earlier sessions' forecasts. A test checks it.
- The forecasts at row 164 are R123's (their own guard: inputs at row t read bars ≤ t).

## 6. Order of work and outputs

1. This file, committed first.
2. Code and tests: `research/hunt/r132_solo_network/` (`r132.py`, `test_r132.py`; the port is tested against hand-made cases).
3. Walk-forward run → `results/wf.json`, committed.
4. The holdout read once → `results/results.json` (a flag file refuses a second read).
5. Write-up `research/HUNT_R132_SOLO_NETWORK.md`; entries `research/experiments/entries/r132-*.yaml`; a RESULT.md block.
