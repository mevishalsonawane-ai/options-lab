# R17: The pre-trade checks (TODO I3 charges check, I5 smart stop, I4 danger meter)

Written 13 Oct 2026 for Boss. The pre-registration is `research/hunt/r17/PREREG.md`. It was committed on its own (9ae6cdb)
before any test was run. Amendment 1 (04b10ce) came before any test result: delta is taken from the option's own
implied vol, because a VIX-based delta is about 0 for Hero's Rs 1-5 options.

The design picks were frozen and committed (40e93a9) before the holdout was read. The holdout was read once, guarded by
a flag file in the cache.

- Code: `research/hunt/r17/` (`config.py`, `data.py`, `erange.py`, `arms.py`, `run.py`, `test_r17.py`).
- Results: `r17/results/design.json`, `picks.json`, `holdout.json`, `app_constants.json`.
- Experiment entries: `research/experiments/entries/r17a.yaml`, `r17b.yaml`, `r17c.yaml`.
- Raw data stays out of git.

## Verdict (read this first)

**None of the three ideas passed the holdout.** All three go into the app as shadow lines or advice only, with switches
that are off. They never skip, resize or move anything on their own.

| check | holdout test | result | BH q | verdict |
|---|---|---|---|---|
| I3 charges check, skip when costs > 10% of the expected move | 3 of 499 trades skipped; skipped -Rs 471 vs kept -Rs 166 a trade | p 0.31, beats 57% of random twins (needs 95%) | 0.46 | **rejected** |
| I5 smart stop, VIX divergence NIFTY (`tight`, k = 1); no other arm qualified in design | +Rs 94 over 15 trades vs the fixed stop | p 0.17 | 0.46 | **rejected** |
| I4 danger meter, careful / paper-only days | 23 flagged days lost -Rs 503 a day vs -Rs 844 on 99 normal days (the flagged days were *better*) | p 0.70, twin 32% | 0.70 | **rejected** |

Plain words:

1. **Costs are small next to the expected move.** On every arm, the round trip (charges plus two fills' slippage) is a
   median 3-6% of what the option is expected to move in the next 30 minutes. A "skip above X%" rule almost never
   fires. The arms do not lose because a single trade's costs eat the move. They lose because their entries have no edge
   before costs, so every trade pays about Rs 80-100 of charges for nothing. That is R14's lesson again.
2. **A range-scaled stop is not better than the fixed one.** In design, ORB's smart stop (k = 1, a median 95-point stop)
   lost Rs 11,399 less than the fixed 40 over 285 trades. But it was not significant (p 0.11), and in the holdout the k
   values jump around (k 1.25 worse, k 1.5 better). That is noise, not a rule.
3. **The bad days cannot be called in the morning.** The day types that lost most in design were not the ones that lost
   most in the holdout.

## Data and setup

| | |
|---|---|
| source | `research-data` release (switch `R17_SOURCE=lake` reruns everything on the 6-year Dhan lake once it is published) |
| BANKNIFTY | 13 Feb 2024 - 23 Feb 2026, 498 sessions; nearest-expiry chain, ~48 contracts a day |
| NIFTY | 1 Apr 2024 - 13 Apr 2026, 499 sessions; plus 53 expiry days with that day's chain (for Hero) |
| VIX | previous close from the release's INDIAVIX minutes; Yahoo daily ^INDIAVIX where the release has none (after 27 Feb 2026) |
| design / holdout | BANKNIFTY before / from 25 Aug 2025; NIFTY before / from 13 Oct 2025 (R16's cuts) |
| fills | the app's paper fills (+-5 bps, stops -10 bps) + half-spread 1-4 ticks by the contract's 5-minute volume (stops x2) |
| charges | the app's model at today's rates (Rs 20 an order, STT 0.15% on the sell, exchange, SEBI, stamp, GST); NSE lot of the day |
| expected range | R16's 4-input linear model, refit every month on earlier sessions only (5-session embargo, 60-session warm-up). Design error (mean abs log): 0.327 BANKNIFTY, 0.334 NIFTY |

The arms are the app's paper rules as of 13 Oct. Liquidity is excluded. Night is excluded too: it holds overnight, and
its breadth input is not in the data.

| arm (1 lot; Hero Rs 5,000) | design trades | design net | per trade | holdout trades | holdout net | per trade |
|---|---|---|---|---|---|---|
| ORB (1 a side a day) | 285 | -Rs 14,288 | -50 | 117 | -Rs 10,965 | -94 |
| ORB Fresh (with the cap it takes the same trades as ORB) | 285 | -Rs 14,288 | -50 | 117 | -Rs 10,965 | -94 |
| ORB Sweep | 414 | -Rs 74,839 | -181 | 161 | -Rs 38,307 | -238 |
| Range Fade | 452 | -Rs 74,152 | -164 | 185 | -Rs 52,144 | -282 |
| VIX divergence BANKNIFTY | 61 | +Rs 5,536 | +91 | 19 | +Rs 8,462 | +445 |
| VIX divergence NIFTY | 56 | -Rs 6,342 | -113 | 15 | +Rs 14,975 | +998 |
| Hero (NIFTY expiry) | 8 | -Rs 13,600 | -1,700 | 2 | -Rs 5,976 | -2,988 |

Caveat: the release has only the NEXT expiry's chain on most expiry days, so the ORB family trades the next expiry
there (the app trades today's).

## I3: charges check

X = (round-trip charges at the entry price + 2 x (5 bps + 1 tick)) per unit / (expected 30-minute range x |delta|).
The app's `PreTradeChecks.ChargesCheck` computes X the same way, but its expected range is a simpler model than the one
tested here (fixed coefficients, not the monthly refit; see "In the app"). So the app's X is close to this X, not
identical to it.

The pre-registered thresholds were X* = 10-50%. The design pick was 10%: it skipped 47 of 1,276 trades and gained
Rs 4,182, but beat only 32% of random twins even in design. 15% and above skip at most 3 trades.

**Net per trade by cost fifth** (lowest X to highest; n per fifth in brackets):

| arm | period | 1st | 2nd | 3rd | 4th | 5th | X range |
|---|---|---|---|---|---|---|---|
| ORB | design (57 each) | +5 | +14 | +7 | -101 | -176 | 1.7% - 11.2% |
| ORB | holdout (23-24) | +262 | -12 | -277 | -57 | -387 | 2.3% - 8.2% |
| ORB Sweep | design (82-83) | -197 | -246 | -216 | -147 | -99 | 2.4% - 15.2% |
| ORB Sweep | holdout (32-33) | -493 | -662 | -288 | +24 | +237 | 2.4% - 9.2% |
| Range Fade | design (90-91) | -171 | -210 | -170 | -141 | -129 | 2.5% - 16.6% |
| Range Fade | holdout (37) | -357 | -399 | -289 | -289 | -74 | 2.4% - 10.5% |

- VIX divergence has 11-13 trades per fifth in design and 3-4 in the holdout, which is too few to read. Hero has 8 and
  2 trades, so it has no fifths.
- **"The threshold where trades lose" is not one number.**
  - ORB's dearer trades (X above about 5%) lost in both periods.
  - Sweep and Fade lose MORE on their cheap trades: their cheap trades are the quiet windows, where a reversal has no
    room.
- This is post hoc. The ORB pattern can be a new pre-registered idea on the 6-year lake. It is not a rule.

## I5: smart stop-loss

Smart stop = k x E30 x |delta| premium points, floored at 2 ticks. Two forms:
- `smart` replaces the fixed stop.
- `tight` = min(fixed, smart), so it is only ever tighter.

**Design** (best variant vs fixed; daily paired one-sided t; the bar to qualify was p < 0.10):

| arm | trades | fixed net | best variant | its net | p | picked |
|---|---|---|---|---|---|---|
| ORB | 285 | -14,288 | smart k1 (median stop 95 pts) | -2,888 | 0.11 | fixed |
| ORB Sweep | 414 | -74,839 | smart k0.75 | -70,459 | 0.34 | fixed |
| Range Fade | 452 | -74,152 | tight k0.75 | -72,024 | 0.13 | fixed |
| VIX div BANKNIFTY (no stop today) | 61 | +5,536 | smart k1 | +4,334 | 0.54 | fixed |
| VIX div NIFTY (15%) | 56 | -6,342 | **tight k1** | -5,862 | 0.08 | **tight k1** |
| Hero (60%) | 8 | -13,600 | tight k0.5 | -12,919 | 0.18 | fixed |

**Holdout, every variant's net** (information; only the VIX div NIFTY pick was a test):

| arm | trades | fixed | smart k0.5 / 0.75 / 1 / 1.25 / 1.5 | tight k0.5 / 0.75 / 1 |
|---|---|---|---|---|
| ORB | 117 | -10,965 | -7,405 / -7,353 / -10,120 / -16,478 / -2,613 | -10,609 / -10,937 / -10,965 |
| ORB Sweep | 161 | -38,307 | -36,592 / -48,252 / -45,016 / -29,081 / -37,596 | -36,846 / -42,485 / -39,281 |
| Range Fade | 185 | -52,144 | -45,045 / -51,983 / -57,110 / -55,120 / -47,142 | -46,126 / -48,910 / -53,839 |
| VIX div BANKNIFTY | 19 | +8,462 | +1,870 / +6,150 / +10,958 / +7,602 / +6,510 | (same as smart) |
| VIX div NIFTY | 15 | +14,975 | +14,101 / +12,844 / +11,090 / +10,609 / +9,219 | +15,447 / +15,433 / **+15,069** |
| Hero | 2 | -5,976 | -7,170 / -8,790 / -8,654 / -8,654 / -8,654 | -5,976 / -5,976 / -5,976 |

No k is consistently better in both periods on any arm. **Every arm keeps its fixed stop.** The app shows the shadow line
"smart stop would be N points; fixed stop is M" with k = 1 (the pre-registered default when no pick survives).

## I4: daily danger meter

The test uses each session's summed net of all the arms above (ORB Fresh counted, as the paper account sees it), on
sessions where both indices have data.

The features are known by 09:16: |gap|, VIX previous close, VIX change, event day (budget / RBI; only 9 in the data),
expiry day, and day of week. **No global cues** (GIFT Nifty, US markets): none are in the data.

The model is refit walk-forward by month, with a 120-session warm-up.

| period | days | normal | careful | paper-only | flagged mean / day | normal mean / day | p |
|---|---|---|---|---|---|---|---|
| design | 200 | 160 | 22 | 18 | -Rs 1,097 | -Rs 955 | 0.39 |
| holdout | 122 | 99 | 22 | 1 | -Rs 503 | -Rs 844 | 0.70 |

**Day types, mean day net** (design n / holdout n), descriptive:

| feature | worse in BOTH periods | design | holdout |
|---|---|---|---|
| expiry day (either index) | yes | -1,291 (57) vs -845 (168) | -1,417 (25) vs -616 (97) |
| small gap (lowest third) | yes | -1,405 (79) vs -529 / -936 | -1,144 (47) vs -814 / -356 |
| event day (budget, RBI) | yes, tiny n | -3,266 (7) | -2,502 (2) |
| high VIX (top third) | no | -951 (91) | +151 (28) |
| VIX up > 5% | no | -251 (29) | -2,720 (14) |
| Monday | best in both | -275 (45) | +117 (26) |
| Thursday | no | -1,498 (46) | +31 (23) |

The pattern that holds in both periods is expiry days and quiet (small-gap) days. That fits the arms being breakout
buyers, but the combined score still did not separate bad days in the holdout. The app shows the meter's level with the
bucket sample sizes and says plainly that it failed its test.

## In the app (all shadow / advice, all switches off)

**The app does not use the exact models tested above.** It uses simpler, fixed versions of them (corrected 13 Oct after
review; an earlier version of this page said "the same formula"):

| part | tested in R17 | what the app uses |
|---|---|---|
| expected range | R16's 4-input linear model, refit every month on earlier sessions; time-of-day term = the mean log range of the next 30 / 60 minutes at that minute over the previous 20 sessions | R16's fixed coefficients (fitted once on R16's design rows); time-of-day term = R17's design-period mean by 15-minute slot (`results/app_constants.json`; BANKNIFTY 376, NIFTY 379 sessions), clamped to 09:30-15:00 |
| range inputs | 1-minute high and low of the last 30 / 60 minutes | ORB family: 5-minute bars' high and low; VIX divergence and Hero: 1-minute high and low (a minute without a usable high / low counts its close) |
| VIX | previous session's close | VIX snapshot's close, used only when the snapshot is from today or the previous trading session; otherwise unknown |
| danger meter | buckets refit walk-forward by month (120-session warm-up) | one fixed model fitted on 347 sessions (26 Sep 2024 - 23 Feb 2026), never refit |
| danger meter inputs | all known by 09:16 | read at 09:00 without the gap ("provisional, gap not known yet"), read again at 09:16 once BANKNIFTY's open is in |

So the numbers the app shows are close to the research's, not identical, and none of the holdout results above
applies to the app's versions exactly.

- `ira-core/PreTradeChecks.kt` (pure):
  - `RangeModel`, `OptionDelta`, `ChargesCheck`, `SmartStop`, `DangerMeter` as in the table.
- ORB, ORB Fresh, Sweep, Range Fade, Hero and VIX divergence log, at each paper entry: "costs take X% of the expected
  move", a would-skip flag at 10%, and "smart stop would be N points; fixed stop is M".
  - This is memory only and never on the order path. It never changes the order.
  - It is shown in Order Review's day notes and in the arm's day detail, with the holdout samples (499 trades for the
    charges check, 15 for the smart stop) and, for Hero, its tiny sample (8 design, 2 holdout trades).
- Good morning check: "Today: normal / careful / paper-only day", with the sample size and "advice only; it failed its
  holdout test (122 days)". Before the open it says "provisional, gap not known yet"; at 09:16 the meter is read again
  and the new reading is noted in Jarvis's chat and shown on the Pre-trade checks card.
- Switch "Danger meter: half size on careful days (paper arms only)" is OFF by default.
  - It only ever lowers size, and never touches live orders or any Liquidity arm.
  - Halving a 1-lot arm is impossible, so in practice it changes only Hero's budget-based lots.

## Deviations and caveats

1. Amendment 1 (delta from implied vol), made before any test result.
2. I4 used one cut (25 Aug 2025) and only sessions where both indices have data (to 23 Feb 2026). PREREG gave per-index
   cuts, which do not fit a combined day.
3. The release lacks most expiry-day chains, so the ORB family's expiry-day trades use the next expiry. Hero has only
   8 + 2 trades.
4. The VIX-divergence fills have no bid/ask: the half-spread is modelled from volume. The research's own VIX-divergence
   numbers differ from VixDivRules.RECORD (different data and period).
5. Two years of data. All three ideas can be re-run as written on the 6-year lake (`R17_SOURCE=lake`), but that needs a
   NEW holdout and entry: this one is spent.
