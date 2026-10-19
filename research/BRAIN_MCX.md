# MCX Data Brain (MB1): five levels on MCX commodity futures

Written 20 Oct 2026. Boss, 19 Oct: "After commodities are done, save the data in numerical tensors, vectors, or
optimized binary/columnar file formats and save it in neuro network of 5 levels, so that we could use that to find
best strategy."

Pre-registration: `research/brain/mcx/PREREG.md` (commit 8f1d9f5b, before any feature, model or trade). Code and
commands: `research/brain/mcx/README.md`. Every number below: `research/brain/mcx/results/` (`design_metrics.json`,
`holdout_metrics.json`, `l4_design.json`, `cards.json`). Experiment entries: `research/experiments/entries/mb1-*.yaml`
(11). **Nothing here trades and nothing in the app changed.** Signal source of everything: the MCX future itself
(its minute closes); ATM IV, put/call OI and option volume are context only.

## Verdict (read this first)

**Like the India brain, the MCX brain can tell how BIG the next hour will be, but not which WAY it will go, and no
rule built on it pays after MCX costs.**

| question | design (walk-forward, ~150 sessions a symbol) | locked holdout (1 Jul - 16 Oct 2026, 76 sessions, read once) |
|---|---|---|
| size of the next 60 minutes | **predictable** on 5 of 6 (14-19% smaller error than the best plain guess; crude 4%, fails) | **holds** on 5 of 6: 8-13% (crude 13%); natural gas drops to 3.5% (p 0.10) |
| direction of the next 60 minutes | **not predictable**: AUC 0.49-0.54 on 3,870-7,499 rows | AUC 0.48-0.53 |
| does the last hour continue? | **no**: AUC 0.50-0.54, 47-51% continue | not read |
| US open: size of the next 150 min / continuation | **no** on all 12 tests (73-149 days each) | not read |
| event hours bigger? | **yes**: US CPI/NFP hours 1.15-1.74x on gold and silver (9-17 releases), gas storage 1.23x (33); EIA crude 1.15x (42, q 0.11, fails) | not read |
| event hours: follow or fade the hour before? | **neither** (0 of 8) | not read |
| L2 encoder (16-number market state) adds anything? | **no**: size error -0.06% to +0.79%, direction AUC -0.007 to +0.007 (0 of 12) | gains -0.6% to +0.8% |
| L3 committee direction when it acts | 1 of 6 passes (SILVERM 53.2% on 3,586 acted rows, q 0.004) | **fails**: SILVERM 47.1% on 1,096; best GOLDM 52.8% on 1,171 |
| L4 rules from the brain (16 rule x symbol cells, 32 variants) | **0 of 16 pass** the design gate | not read (nothing passed) |

64 primary tests in one Benjamini-Hochberg family for L1-L3 (q 0.05), 32 L4 variant tests in another (q 0.10).

## The data, stored the way Boss asked

| layer | what | where |
|---|---|---|
| L1 raw | the 6 MCX monthly option books (near-future close + ATM-5..+5 IV, OI, volume per minute; 5 Aug 2025 - 13 Oct 2026); 11 MCX futures, every live contract, 1 minute (fetched from Dhan 19-20 Oct; the Drive copy was 14 Oct and 6 commodities); near-month daily 2012 - 16 Oct 2026 | Drive lake; my fetch at `gdrive:IraAlgo-data-lake/derived/mcx_brain/v1/l1_inputs/` |
| L2 clean | the near-future close (median of the minute's option `spot`), option context, the continuous future (previous-day volume roll, `research/data/mcx.py`), the option expiry calendar | `clean.py` |
| L3 cube | one MCX session grid per symbol and day (09:00-23:29 / 23:54 by US time), 1.76 M rows, Parquet zstd, 43 MB | `derived/mcx_brain/v1/cube/` |
| L4 features | 56 point-in-time features in 10 families (price, vol, trend, levels, session, volume, options, calendar, events, daily), Parquet zstd, 355 MB | `derived/mcx_brain/v1/features/`, catalogue `research/brain/mcx/FEATURES.md` |
| tensors | float32 [minute x feature] per symbol (`.npy`, memory-mapped) and Arrow IPC; the joint float32 tensor [11 symbols x 270,160 minutes x 56 features]; fixed 120-minute windows and the encoder input [N x 24 x 6] at every decision row; sha256 manifest. 1.5 GB | `derived/mcx_brain/v1/tensors/`, manifest `results/tensors_manifest.json` |
| brain outputs | every walk-forward and holdout forecast, the 16-number state vectors, the design-fitted encoder | `derived/mcx_brain/v1/study/` |

Coverage: GOLD 306 sessions (224 design / 5 embargo / 77 holdout), GOLDM, SILVER, SILVERM, CRUDEOIL, NATURALGAS 308
(226 / 5 / 77). CRUDEOILM 20, NATGASMINI 37, COPPER, ZINC, ALUMINIUM 35 sessions, all after the design end: built,
stored and described (cards) but not modelled (PREREG: >= 60 design sessions). Missing: option books for CRUDEOILM,
NATGASMINI, COPPER, ZINC (another session is fetching them; ALUMINIUM has none); option minutes after 13 Oct (14 Oct is
a gap day for the six, 15-16 Oct come from the future); 19 Oct (fetched, but `lake_build` keeps MCX days up to the
newest NIFTY daily bar, 16 Oct); futures minutes of contracts that expired before 13 Oct (Dhan serves live contracts
only). GOLD and SILVER option books are thin in Jan-May 2026 (19-71% of minutes missing), so those months have fewer
forecasts.

**Point in time.** The look-ahead guard (every row after a cut deleted, or perturbed; every feature and the cube builder
unchanged before it) passes on the synthetic tests and on the real cube (GOLDM, CRUDEOIL, NATURALGAS, SILVER, COPPER,
last 30 sessions, 8 cuts x 2 modes). Canaries (the next close, the day's final high) are caught. A day's price source
is chosen from the previous day; the option expiry calendar is a fixed input. Prices are closes only, so ranges are
measured the same way on every source.

## The five levels

```
L5 knowledge   plain-English cards per commodity (results/CARDS.md)                     -> Jarvis, Boss
L4 search      4 rules x 2 variants x 4 commodities, real MCX costs, random twins, BH   -> 0 of 16 pass
L3 committee   size: mean of 3 members + 80% band + abstain; direction: calibrated stack, acts at p >= .55 / <= .45
L2 encoder     120-minute window -> 16-number market state (MLP autoencoder, PCA baseline), 6 market states
L1 specialists A range, B persistence, C direction, D US open, E event hours
L4 store / L3 cube / L2 clean / L1 raw  (above)
```

### L1-A: size of the next hour (the one thing that works)

Target: log of the close range of the next 60 minutes. Decision every 15 minutes, 09:30 to the close - 75 minutes.
Model: gradient-boosted trees on all 56 features, refitted per fold. "Skill" = how much smaller its mean absolute
error is than the best of three plain guesses: the usual range at this clock time (previous 20 sessions, `b_tod`), the
last hour's range (`b_last`), and the move the ATM option prices (`b_iv`).

| symbol | design rows / sessions | best plain guess | design skill (BH q) | holdout skill (p), 3,917 rows / 76 sessions | direction AUC design (rows) | holdout AUC (rows) |
|---|---|---|---|---|---|---|
| GOLD | 4,720 / 146 | option IV | 13.9% (0.004) | 12.0% (0.0005) | 0.526 (4,471) | 0.533 (3,856) |
| GOLDM | 7,359 / 150 | option IV | 19.1% (0.004) | 8.4% (0.0005) | 0.533 (7,333) | 0.517 (3,873) |
| SILVER | 4,041 / 130 | last hour | 17.7% (0.004) | 9.8% (0.0005) | 0.535 (3,870) | 0.504 (3,844) |
| SILVERM | 6,693 / 149 | option IV | 18.2% (0.004) | 8.2% (0.0005) | 0.533 (6,620) | 0.525 (3,912) |
| CRUDEOIL | 7,628 / 150 | option IV | 4.0% (0.240) | 13.1% (0.0005) | 0.492 (7,499) | 0.481 (3,886) |
| NATURALGAS | 7,628 / 150 | clock time | 17.5% (0.004) | 3.5% (0.096) | 0.500 (7,456) | 0.523 (3,772) |

- The skill is smaller on the holdout than in design (8-13% against 14-19%), but it holds where it passed, except
  natural gas. Crude failed design (4%) and is 13% on the holdout: it is not counted as a pass.
- The forecasts are calibrated in the middle and too low at the top: GOLDM design deciles go from a 0.17% forecast /
  0.18% actual to 0.73% / 1.10% (736 rows a decile). Big hours are bigger than the brain says.
- The linear model is much weaker (ridge skill -175% to +12%); the plain-guess winner is usually the option market's
  own IV, so the brain beats the option market's forecast of the next hour by 8-19%.

### L1-B, L1-C, L1-D: direction, persistence, the US open: no

Direction AUC 0.48-0.54 everywhere, log-loss worse than the base rate on every symbol (0 of 6, design and holdout).
The last hour continues 47-51% of the time (0 of 6). At the US open the next 150 minutes continue the day's move 46-53%
of days (73-149 days), and the brain cannot forecast the size of that window better than the previous 20 days' mean
(skill -31% to +8%; 0 of 12).

### L1-E: event hours are bigger, not directional

| group | releases | hour after vs usual (median) | BH q | follows the hour before |
|---|---|---|---|---|
| GOLD, US CPI / NFP | 10 | 1.74x | 0.004 | 6 of 11 |
| GOLDM, US CPI / NFP | 17 | 1.60x | 0.004 | 9 of 18 |
| SILVER, US CPI / NFP | 9 | 1.63x | 0.015 | 3 of 10 |
| SILVERM, US CPI / NFP | 17 | 1.15x | 0.015 | 10 of 18 |
| NATURALGAS, EIA storage | 33 | 1.23x | 0.014 | 16 of 36 |
| CRUDEOIL, EIA crude | 42 | 1.15x | 0.11 (fails) | 23 of 45 |
| CRUDEOIL, US CPI / NFP | 17 | 1.02x | 0.08 (fails) | 12 of 18 |
| NATURALGAS, US CPI / NFP | 17 | 1.13x | 0.92 (fails) | 13 of 17 (q > 0.05) |

A size fact for risk (stops set for a normal hour are too tight in these hours), not a direction edge: R83 already
showed EIA and storage fade/follow lose after costs.

### L2: the sequence encoder

A self-supervised autoencoder squeezes the last 120 minutes (24 five-minute steps x 6 channels) into 16 numbers, fitted
per fold on 22,296-40,000 windows of all six commodities. It reconstructs the windows a little better than PCA
(mean squared error 0.112-0.122 against 0.116-0.124) but adds nothing the 56 features do not already carry: size error
-0.06% to +0.79%, direction AUC -0.007 to +0.007 (0 of 12 pass). The state vectors are stored with the tensors; six
market states (KMeans on the training states) are in the forecast tables.

### L3: the committee

- Size: the mean of ridge, trees and trees + state. Its 80% band covered 65-80% of design hours and 77-88% of holdout
  hours, except SILVERM (65% / 28%: its ridge member breaks on outliers, and the pre-registered committee keeps it).
  Abstaining on the 16-46% of design hours where the members disagree most lowers the error on every symbol but crude
  (design MAE GOLD 0.59 -> 0.38, SILVER 0.57 -> 0.43, SILVERM 0.72 -> 0.41, the others by 0.01-0.03; crude 0.388 ->
  0.395); on the holdout it abstained on only 0-14% of hours.
- Direction: acts on 51-75% of design rows and 25-59% of holdout rows. Design: SILVERM right 53.2% of 3,586 (q 0.004), the other five 49.9-51.4%.
  Holdout: SILVERM 47.1% of 1,096 (p 0.98); best GOLDM 52.8% of 1,171 (p 0.03, not a pre-registered pass on its own).
  Rejected.

### L4: strategy search from the brain's outputs

Traded one mini lot (GOLDM, SILVERM, CRUDEOILM, NATGASMINI) on the brain's walk-forward test forecasts (21 Nov 2025 -
23 Jun 2026, ~150 sessions), Zerodha MCX charges, R53 spreads, 0.02% stop slippage; B4 buys the ATM straddle on the
option book. Full numbers: `research/HUNT_MB1_MCX_BRAIN.md`.

| rule | GOLDM | SILVERM | CRUDEOILM | NATGASMINI |
|---|---|---|---|---|
| B1 committee direction | -Rs 1.89 lakh (710) | -Rs 5.39 lakh (685) | -Rs 76k (1,438) | -Rs 42k (628) |
| B2 big-size follow | -Rs 2.22 lakh (454) | **+Rs 2.53 lakh** (570; q 0.24, top-5 carried) | -Rs 23k (785) | **+Rs 20k** (421; q 0.08, top-5 carried) |
| B3 quiet fade | -Rs 58k (113) | -Rs 16k (50) | -Rs 5.7k (89) | -Rs 9.4k (140) |
| B4 size-edge straddle (option buy) | -Rs 82k (110) | -Rs 4.47 lakh (315) | -Rs 3.91 lakh (472) | -Rs 1.77 lakh (308) |

Net after costs, frozen variant (trades). 0 of 16 pass. B2 is the only lead: when the brain expects a big hour and the
last 30 minutes already moved a third of it, following made money before and after costs on silver mini and natural
gas mini, beat random same-hour entries (twin p 0.03 / 0.005), but lost without its 5 best trades. It stays a note for
a future pre-registered test. The straddle rule (B4) shows the brain's size edge is not enough to buy MCX options: even
when it expects far more movement than the IV prices (its top decile or fifth), the straddle loses (17-32% winners); the option market's spread and the
average premium decay take it, as M4 and R85 found.

### L5: knowledge cards

`research/brain/mcx/results/CARDS.md` (and `cards.json` for Jarvis): per commodity the usual hourly move by clock
time, what the brain can and cannot forecast (design and holdout), the US open and the event hours, with sample
sizes. Examples: GOLDM's busiest hour starts at 19:00 IST (median 0.40% close range, 221 sessions), its quietest at
16:00 (0.20%); natural gas from 18:00 (1.39%, 223) vs 09:00 (0.47%); the futures-only base metals are busiest around
noon (copper 0.28%, aluminium 0.33%, 34 sessions). Matches R85 (the US-open hour is the busiest).

## What this means for Boss

1. The brain is useful as a **size gauge**, not a signal: it tells how wide the next hour is likely to be, 8-13% better
   than the option market's own IV or the usual clock-time range (holdout). That can size stops and targets, or warn of
   a wide hour, on gold, silver and crude. It never says up or down.
2. **No strategy from this brain goes to paper.** Nothing passed, so there is no APP_SPEC.
3. Event hours (US CPI / NFP for gold and silver, EIA gas storage for natural gas) are 1.2-1.7x the usual hour: a risk
   fact, not a trade.
4. Next, if wanted: re-test B2 "big-size follow" alone, pre-registered, on silver mini and natural gas mini when the
   holdout window has grown (it has not been read for rules), and add the base metals once they have 60+ sessions.

## Honesty notes

- The holdout window (Jul-Oct 2026) overlaps the final windows of R53, R83 and R85 (MCX); none of them ran these models
  or rules. The holdout was read once for the predictions (`results/holdout_reads.json`, 19 Oct 21:30 UTC) and not for
  rules.
- Design runs: one, plus the KMeans market states added (a descriptive output, pre-registered in section 5) and the
  run restarted before any result was read. A labels rule was relaxed before any model ran: the India rule needs every
  minute of a window, the option-spot series misses some, so MCX labels need >= 80% (stated in `labels.py`).
- Implementation choices made before results and not spelled out in the PREREG: one open trade per symbol and rule
  variant; B2/B3 thresholds and B4 ratio cut-offs from the inner out-of-time 30% of each training fold; B4 skips option
  expiry days; US CPI and NFP pooled in L1-E; L1-E follow/fade tested two-sided.
- Sample sizes are small for the US-open and event tests (9-149 per test).
