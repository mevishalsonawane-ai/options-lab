# R21 pre-registration: catching expiry-day zero-to-hero moves (the Hero arm)

Written 13 Oct 2026, AFTER step 1 (the descriptive anatomy, design days only: `results/anatomy.md`) and BEFORE any
variant below was run on the holdout. This file is committed on its own before the test run; the commit hash is quoted
in the write-up (`research/HUNT_R21_HERO.md`). Nothing below may change after that commit. A change needs a new entry,
a new pre-registration and a fresh holdout.

Trigger for the study: Tue 13 Oct 2026 (NIFTY weekly expiry). NIFTY rallied 22,529 → 22,722 after 13:30; the 22650 CE
went from about Rs 3.75 to a 100.45 high; the Hero arm took no trade. **13 Oct is a forward example only.** It is not in
any data file used here (the release ends 13 Apr 2026), so it cannot have chosen any parameter.

## Data, costs, splits

- Source: one switch, `R21_SOURCE` = `release` (default: the `research-data` release; the expiring contract's minutes
  on expiry days from `expiry_days.parquet`: NIFTY 53 weeklies 9 Apr 2025 – 13 Apr 2026, BANKNIFTY 11 monthlies
  27 Feb 2025 – 27 Jan 2026; strikes within about ±1,000 points) or `lake` (the 6-year Dhan lake once
  `research/data/CATALOG.json` and its files exist; read through `research/brain/adapters`). Nothing else changes. The lake
  is NOT published today (no catalog, no release), so this run is on the release.
- SENSEX, FINNIFTY, MIDCPNIFTY: no option chains in the release, so not tested (SENSEX would be analysis only anyway).
- Fills: the app's entry (LIMIT at the ask, capped at signal price × 1.2 + 0.10, worked for 3 minutes; never chases)
  with the ask = the next bar's open + 5 bps + a half-spread of 1 tick (≥ 20 lots traded in the 5 minutes before),
  2 ticks (some) or 4 ticks (none); sells at the open − the same half-spread; the 5× / 20× targets fill at the limit
  only once the price trades a tick through it (`research/obuy/costs.py` `Fills("liq")`). On a Rs 1–5 option one tick
  is 1–5% of the price. Stress test: twice the half-spread.
- Charges: the app's model at today's rates (`Costs("app")`: Rs 20 an order, STT 0.15% of the sell, exchange, SEBI,
  stamp, GST). Lots: the NSE lot in force that day; size = floor(Rs 5,000 / (fill × lot)), 0 skips.
- Holdout (locked, read ONCE for all of R21, after this file is committed): NIFTY expiries from 13 Oct 2025 (26 days),
  BANKNIFTY expiries from 25 Aug 2025 (5 days): the R16/R17 cuts. Design = the 33 earlier expiry days (27 + 6).
  On the lake the holdout becomes the last 12 months of each index.
- Note, for honesty: R17 read this same holdout once for a different Hero question (its stop). R21 asks a new
  question (the entry trigger), so it gets its own single read. The pre-R21 Hero design numbers are known.

## Step 1 facts this pre-registration uses (design only, 33 days; `results/anatomy.md`)

- With hindsight, some Rs 1–10 option (13:00–14:30) reached 5× before 15:05 on 24 of 33 days, 10× on 11, 20× on 7.
  Much was given back: on 11 of the 24 the best option was worth under 2× of its start by 15:05 (median give-back from the peak 68%).
- The straddle condition (≥ 15% over its low since 12:00) was met at some minute on 32 of 33 days. The binding
  condition is momentum: a 15-minute 0.25% move happened on only 9 of 33 days, and on all 9 it coincided with the
  straddle. 13 Oct (straddle peaked at +13.7%) is the rarer case where the straddle condition binds.
- Nothing measured at 13:00 separates big days from the rest (trend, gap, max pain, OI walls, VIX, one-sidedness:
  medians within noise; 24 vs 9 days).
- k for the one-sided trigger, chosen on design by the rule stated before the scan (best design net; ties → the larger
  k, which fires less): band 1–5 → k = 2.5 (+Rs 10,338, 7 trades); band 1–15 → k = 2.0 and 2.5 tie (+Rs 47,834,
  9 trades) → k = 2.5. **k = 2.5 for every one-sided variant.**

## Variants (9, all tested; BH across all 9)

Common: one entry a day per index, NIFTY and BANKNIFTY expiry days, momentum = index close vs 15 minutes earlier, ±0.25%
(+ buys a call, − a put), the option = nearest-the-money OTM on that side within 3% of spot, traded in the last 5 bars,
with the app's stale-slack rule; "app exits" = the app's Hero exits today (half at 5×, the rest at 20× or 15:05, a
minute close ≤ 40% of the fill sells the rest).

| id | idea | trigger | window (bar closes) | band Rs | exits |
|---|---|---|---|---|---|
| V0 | baseline: the app's Hero today | straddle ≥ 15% this minute + momentum | 13:30–14:45 | 1–5 | app |
| V1 | (a) one-sided | the option's own close ≥ 2.5 × its low since 12:00 + momentum agreeing | 13:30–14:45 | 1–5 | app |
| V2 | (b) recent expansion | straddle ≥ 10% at any minute in the last 15 + momentum | 13:30–14:45 | 1–5 | app |
| V3 | (a)+(c) | as V1 | 13:30–15:00 | 1–5 | app |
| V4 | (a)+(c)+(d) | as V1 | 13:30–15:00 | 1–15 | app |
| V5 | V4 + (e) lock | as V1 | 13:30–15:00 | 1–15 | app + once the price trades ≥ 4×, a minute close ≤ 2× sells the rest |
| V6 | V4 + (e) give-back trail | as V1 | 13:30–15:00 | 1–15 | app + once a close ≥ 3×, a close ≤ 50% of the best close sells the rest |
| V7 | V4 + plain exit | as V1 | 13:30–15:00 | 1–15 | 15:05 only (no targets, no stop) |
| V8 | (f) blind baseline | none: 13:30, side = the index's direction since 12:00 | 13:30 | 1–5 | app |

## Gates (a variant passes only if ALL hold)

1. Design net > 0 (all 33 design days, parameters as above).
2. Holdout (read once): at least 5 trades and net > 0.
3. Random-time twin on the holdout: 1,000 twin sets, each trade replaced by an entry at a uniformly random minute of the
   same variant's window, same day, same side, same strike rule and exits. p = share of twin sets with net ≥ the
   variant's. **BH across the 9 variants at q = 0.05.**
4. Holdout net > 0 with twice the modelled half-spread.
5. Holdout net > V0's holdout net (it must improve on the arm it would change).

Reported for every variant, design and holdout separately: trades, win rate, total and per-expiry net, worst expiry,
max drawdown, big days (some Rs 1–10 option reached 5× before 15:05, hindsight) caught (traded that day and the bought
option reached 5× its fill before 15:05) vs missed; and whether it would have fired on 13 Oct (forward example, from
the prices Boss's message gives; no minute data of that day exists here).

Also reported, not a gate: an anchored monthly walk-forward of V4 with k re-chosen each month from {1.5, 2, 2.5, 3, 4}
on all earlier expiry days (at least 10), using the same selection rule.

## If a variant passes

Paper only, per CLAUDE.md: a labelled, logged, one-tap-undoable change to the Hero arm's paper rules, shown to Boss,
never live by itself. If nothing passes: no app change; say what data would decide it (the 6-year lake: about 300
NIFTY weeklies).
