# R15: pre-open (daily) filters on Liquidity 15+5 BANKNIFTY, and what data is missing

Written 12 Oct 2026. Pre-registration: `research/hunt/r15/PREREG.md` (committed before any P&L split, commit 0e09e23).
Code: `research/hunt/r15/features.py` (day table from public data), `research/hunt/r15/run.py` (`trades`, `design`,
`hold` once). Logs and intermediate data: scratchpad only (`r15/design.log`, `hold.log`, `days.csv`, `trades.parquet`,
678 BANKNIFTY-only bhavcopy extracts), kept out of git.

## Verdict (read this first)

**No daily filter helps. Do not add any of them.** 0 of 5 pre-registered filters passed (best BH q 0.50). Two of
the four "skip" filters would have cost money in the design period; the one that looked good later (F4) lost in the
other design half.

Side facts from the same 966 trades (information, not new rules):
- The puts carry the arm: design period puts **+Rs 228 a trade** (400 trades), calls **-Rs 60** (343). This repeats h8/h12
  and is the basis of the pre-registered A2 shadow rule (`PREREG_LIQUIDITY_ADDONS.md`).
- In the part of the locked holdout this data covers (1 Oct 2025 - 23 Feb 2026, 96 sessions) the base arm was about
  flat: **-Rs 16 a day** (223 trades). The h36 "+Rs 167 a day" holdout covers a longer window (to Oct 2026) on Dhan
  data. Small windows swing a lot: the daily sd is about Rs 2,570.

## Data used, and why only two years

The Dhan token expired on 10 Oct 2026 and its 2020-2026 candle cache is not in this repo or container, so the h24/h36
trade table cannot be rebuilt here. This study used only legal, free sources:

| source | what | span | used for |
|---|---|---|---|
| repo release `research-data` | BANKNIFTY 1-min index + near-ATM 1-min options with OI (Kaggle archive, broker-scraped) | 13 Feb 2024 - 23 Feb 2026, 498 sessions | the base Liquidity trades |
| NSE F&O bhavcopy (archives.nseindia.com old format, nsearchives.nseindia.com UDiFF) | daily OI of every BANKNIFTY future and option | Jun 2023 - Feb 2026, 678 of 678 sessions | F3 PCR, F4 futures OI change |
| Yahoo `^NSEBANK`, `^INDIAVIX` daily | index OHLC, India VIX close | Jan 2023 - Feb 2026 | F1, F2, F5 |

## Base trades (fixed before the split)

`liquidity_break.simulate` on BANKNIFTY, 15m + 5m books, "both" levels, the app's exits (15% premium stop, 30-pt index
stop, not +5% after 20 min, next level, failed break, new level, 15:10), entries 09:20-14:00. Real costs: 0.16%
half-spread each side (+0.05% on stop exits), the app's charges (Rs 20 an order, STT 0.15%, exchange, SEBI, stamp,
GST), 1 lot of 30. Differences from the app: ATM strike (the app buys 1-ITM) and no room filter.

| period | sessions | trades | Rs/day | t | net | max DD | green months |
|---|---|---|---|---|---|---|---|
| DESIGN (13 Feb 2024 - 30 Sep 2025) | 402 | 743 | **+176** | 1.27 | +70.8k | -22.2k | 12/20 |
| of which 15m book / 5m book | | 187 / 556 | +87 / +89 | 1.22 / 0.85 | | | |
| DESIGN at 1.5x spread | | | +128 | | | | |
| HOLDOUT part (1 Oct 2025 - 23 Feb 2026) | 96 | 223 | **-16** | -0.11 | -1.5k | -10.3k | 2/5 |

By calendar year: 2024 +60.5k (396 trades), 2025 +4.8k (478), 2026 to Feb +4.0k (92). Per-trade sd Rs 1,726.

## The five filters (DESIGN; K = 5, BH)

Gain = Rs a day saved by skipping every trade of a flagged day (positive = the filter helps). p = random skipping of
the same number of sessions (B 5,000). PASS needs q <= 0.10, a gain in both halves (split 1 Jan 2025), a gain at 1.5x
spread and >= 30 skipped trades.

| filter (all read on the previous session) | flagged days | skipped trades | skipped Rs/trade | gain Rs/day | 1st / 2nd half | at 1.5x | p | BH q | PASS |
|---|---|---|---|---|---|---|---|---|---|
| F1 QUIET: day range in the bottom third of 60 sessions | 110 | 330 | +11 | -8.7 | -17.1 / +0.9 | +12.9 | 0.27 | 0.50 | no |
| F2 LOWVIX: VIX in the bottom third of 250 sessions | 77 | 228 | +129 | **-73.4** | -85.7 / -59.2 | -59.7 | 0.77 | 0.77 | no |
| F3 PCR_EXT: near-expiry PCR outside 10-90% of 120 sessions | 55 | 135 | +58 | -19.4 | +3.3 / -45.5 | -12.2 | 0.49 | 0.61 | no |
| F4 FUTOI_UP: futures OI change in the top third of 60 | 93 | 239 | +16 | -9.4 | +76.0 / -107.6 | +6.4 | 0.30 | 0.50 | no |
| F5 AFTERDROP (lift a trade after a <= -1% day) | 22 | 54 | +377 | +304 a trade | -445 / +982 | | 0.17 | 0.50 | no |

- F2 is the clearest "no": calm-VIX days were GOOD for the arm (+Rs 129 a trade), the opposite of the idea.
- F5, X2's "untested lead" (after a > 1% down day the range doubles): +Rs 377 a trade on 54 trades, but negative in the
  first half and q 0.50. Too few days to say anything; not a rule (it could only be used to skip the other 95% of days).

## Holdout (information only: nothing passed, so nothing was being tested)

Run once (flag written). F1 -122/day, F2 -52, F3 +41, F4 +66, F5 -72 a trade; all q >= 0.68. F3/F4 turning positive
here after failing the design is the usual noise of an 83-day window, not a reason to revisit them.

## What is needed to test add-ons properly (TODO for Boss)

1. **A fresh Dhan data token** (or any broker API with expired-contract option minutes). Then rebuild h24/h36's
   2020-2026 BANKNIFTY trade table (`research/hunt/h24/sim.py`, `h36/plan.py`) and extend it to the present.
   Everything 2020-2023 and Mar-Oct 2026 is only reachable this way. Store the cache outside the repo.
2. **Live recording in the app (free, starts now):** at every Liquidity signal save the BANKNIFTY nearest-expiry chain
   ATM +- 5 (LTP + OI) at the signal minute and 15 minutes earlier, and top-of-book bid/ask of the bought option.
   That is exactly what the A1/A2 shadow rules need (`PREREG_LIQUIDITY_ADDONS.md`) and the only route to order-flow
   ideas (HUNT_FINAL item 4).
3. **Free daily data is already enough** for daily filters (bhavcopy, VIX, index daily): this study shows they do not
   help, so no more is needed there.
4. NSE does not publish minute option history for free; Kaggle-type archives are broker-scraped and stop in Feb 2026
   in this repo. Paid vendors (TrueData, GDFL, Dhan/Upstox historical APIs) are the legal options for minute history.
