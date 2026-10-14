# R23 pre-registration: which morning range (if any) gives the ORB an edge

Written 13 Oct 2026, BEFORE any number of this study was computed. Part A (data, periods, the Step 1 anatomy protocol,
the full list of range configurations, costs, the selection rule, the lake test and its gates) is committed on its own
before the first run. Part B is added in a second commit after Step 1 and BEFORE any trading test: it only fills in the
quality-filter features of family (f) by the rule fixed in A4, and nothing else. Part C (the frozen finalists and the
exact lake command) is committed after Step 3 and before any lake read. Nothing below may change after its commit.

## Why

Boss, 13 Oct 2026: "Check the candles, do a deep analysis, and find the best way to find a range which could help [the
ORB]." Already tried (read before writing this; not repeated): today's ORB (5-min bars 09:15..10:00, a 5-min close
beyond, -40 / +40 premium, ladder, 15:10) lost after costs in every tested year and is paused; R20 (adaptive range),
R20b (S/R levels as the range), R20c (2-candle-close breaks), R22 (the three 15-minute candles as the range, 15-min close
break: lost less than the ORB but still lost; mirrored levels did better in its holdout), R17 / R18 (exits).

## A1. Data and periods (THE KEY RISK IS OVERFITTING)

- `R23_SOURCE=release` (default): the `research-data` release through R17's loader: BANKNIFTY 13 Feb 2024 - 23 Feb 2026,
  NIFTY 1 Apr 2024 - 13 Apr 2026 (1-minute index; ~20 strikes of the nearest expiry AFTER the day with volume and OI);
  FINNIFTY index minutes only (anatomy only). MIDCPNIFTY is not in the release (stated, skipped).
- The release's holdout (BANKNIFTY from 25 Aug 2025, NIFTY from 13 Oct 2025) has been read by R16, R18, R20, R20b, R20c
  and R22. It is USED UP. **R23 treats every release session as DESIGN data.** No release number can pass R23.
- Release design blocks: the union of BANKNIFTY and NIFTY calendar days (after 15 warm-up sessions for ATR14), cut into
  4 consecutive equal blocks (numpy `array_split`).
- `R23_SOURCE=lake`: the 6-year Dhan lake (research/data, release tag `data-20261012`) through R17's adapter, once it is
  published. FRESH holdout, defined by R18 PREREG amendment 1 and never read by any study:
  - lake design = sessions up to **2026-04-13** (4 consecutive equal blocks of calendar days);
  - embargo = 2026-04-14 .. 2026-04-26, used by nobody;
  - lake holdout = **2026-04-27 onwards**, read ONCE (its own flag file), only if it holds at least 100 sessions per
    traded index.
- Status on 13 Oct 2026: the lake is NOT published (`CATALOG.json` says `release_published: false`; the release tag
  `data-20261012` does not exist; only the NSE bhavcopy is on Drive). So R23 runs Steps 1-3 now, freezes the finalists,
  and leaves the one lake command. It claims no verdict.

## A2. Step 1: the anatomy of the morning (descriptive, release = design data only)

Sessions: BANKNIFTY, NIFTY (with option volume / OI features), FINNIFTY (index features only), from the 16th session
(ATR14 = the app's ShadowRules.atr14: mean true range of the 14 sessions before the day). Grid: R17's `Day.ix`, row r =
the minute labelled 09:15 + r.

**Reference ranges** (both reported; a feature is trusted only if it holds on both):
- ORB: high / low of rows 0 .. 49 (the 5-minute bars 09:15 .. 10:00, today's ORB); decision bars 10:05 .. 13:55.
- R30: high / low of rows 0 .. 29 (the first 30 minutes); decision bars 09:45 .. 13:55.

**The break**: the first 5-min bar in the decision window that closes beyond the range (either side; one per day). The
decision minute r = the break bar's last row. Days without a break are counted apart.

**Outcome** (from the break close, 1-minute highs / lows through 14:59; MFE / MAE in ATR14, in the break direction):
- RUN: MFE >= 0.5 x ATR14. (Boss's example 1.5 x ATR is also counted: shares reaching 1.0 and 1.5 x ATR are printed.)
- FAKE: a 5-min close back inside the range within the next 6 bars (30 minutes) AND MFE < 0.25 x ATR14.
- CHOP: every other break.

**Features**, every one known at the decision minute r (rows <= r of the day, earlier sessions, the previous VIX close,
VIX minutes <= r, option minutes <= r). "Aligned" = multiplied by the break side (+1 up / -1 down).
| id | feature |
|---|---|
| w_atr | range width / ATR14 |
| w_prev | range width / the previous session's high - low |
| gap_al | aligned gap: side x (09:15 open - previous close) / ATR14 |
| gap_abs | abs gap / ATR14 |
| gap_filled | the previous close was traded between 09:15 and r (1 / 0) |
| c5_body, c5_al | the first 5-min candle: abs(close - open) / (high - low); aligned colour (+1 / -1 / 0) |
| c15_body, c15_al | the same for the first 15-min candle |
| c30_body, c30_al | the same for the first 30-min candle |
| c15_wick_with | the first 15-min candle's wick on the break side / its high - low |
| ovol45_rel | option volume (all strikes in the file, calls + puts) rows 0 .. 44, / its median over the previous 20 sessions |
| ovol15_rel | the same for rows 0 .. 14 |
| oi_flow_al | side x ((put OI - call OI) at row min(r, 59) minus at row 0) / total OI at row 0 (put writing = support) |
| oi_chg | (total OI at row min(r, 59) - at row 0) / total OI at row 0 |
| vix_prev | India VIX previous close |
| vix_chg | VIX at row r / VIX at row 0 - 1 (n/a where the release has no VIX minutes) |
| or_inside_pd | the range lies inside the previous day's high-low (1 / 0) |
| beyond_pd | the break close is beyond the previous day's high (up) / low (down) (1 / 0) |
| tests | 5-min bars between the range end and the break whose extreme came within 0.10 x range of the broken edge |
| t_break | minutes from 09:15 to the break close |
| piv_al | side x (break close - floor pivot P) / ATR14 (P = previous day's (H + L + C) / 3) |
| twap_al | side x (break close - mean of the 1-minute closes 0 .. r) / ATR14 (the index has no volume; time-weighted) |
| expiry | the session is the index's nearest expiry (research/brain/calendar; n/a for FINNIFTY) |
| brk_str | side x (break close - broken edge) / ATR14 |
| brk_body | the break bar's body / its high - low |
| pd_dir_al | side x (previous close - previous open) / ATR14 |

**Tables** (each number with its n): class shares (RUN / FAKE / CHOP / no break) by index and range; per feature the RUN
and FAKE medians, AUC (P(RUN value > FAKE value)), Mann-Whitney p, BH q over all features of that range; the same AUC on
each half of the release (by date), on BANKNIFTY and NIFTY separately; RUN share by feature tercile. Descriptive only:
no Step 1 number enters a gate.

**Look-ahead guard** (pytest): every feature at decision row r, every range and every signal is identical when every
minute after r (after the range end, for ranges) is replaced by noise.

## A3. Step 2: the range configurations (fixed here; at most 30)

Common to every configuration (the ORB arm's trade, R22's `trade()`): BANKNIFTY and NIFTY (a day's net = the sum), both
sides, at most 1 trade per side per index per day = the FIRST signal of that side whose decision candle closes after the
range ends and by 14:00; strike = ATM of the 09:20 5-minute bar's close (half-up to 100 / 50), held all day; nearest
expiry AFTER the day; entry at the next minute's open (print within 3 minutes, premium above 40 / 20); exits = the ORB
family (-40 / +40 premium points on BANKNIFTY, -20 / +20 on NIFTY, the profit-lock ladder, out at 15:10); 1 lot (the lot
in force). A signal = a close of the break timeframe's candle strictly beyond the range edge. Break candles are aligned to
09:15 (5-min: 09:15, 09:20, ...; 15-min: 09:15, 09:30, ...; 30-min: 09:15, 09:45, ...).

| family | id | range | break candle |
|---|---|---|---|
| (a) time window | `r23-t15-5m`, `r23-t30-5m`, `r23-t45-5m`, `r23-t60-5m`, `r23-t75-5m` | high / low of the first T minutes (rows 0 .. T-1), T = 15 / 30 / 45 / 60 / 75 | 5-min |
| (b)+(g) own timeframe | `r23-t15-15m`, `r23-t30-15m`, `r23-t60-15m`, `r23-t75-15m` | as (a) | 15-min |
| | `r23-t30-30m`, `r23-t60-30m` | as (a) (one / two 30-min candles) | 30-min |
| (c) volatility-defined | `r23-vol30`, `r23-vol40`, `r23-vol50` | grows bar by bar from 09:15; it closes at the first 5-min bar end from 09:30 on where high - low >= k x ATR14 (k = 0.30 / 0.40 / 0.50), or at 10:15 at the latest | 5-min |
| (d) initial balance value area | `r23-va60-5m`, `r23-va60-15m` | value area of the first hour (rows 0 .. 59): a TPO profile of the 1-minute closes in bins of max(range / 30, 0.05); POC = the fullest bin (ties: nearest the mid); VA grows from the POC by adding the fuller neighbour (ties: the upper) until it holds >= 70% of the minutes; VAH / VAL = its outer bin edges | 5-min / 15-min |
| (e) previous-day / gap | `r23-pd-or45` | [min(low of rows 0 .. 44, previous day's low), max(high of rows 0 .. 44, previous day's high)] | 5-min |
| | `r23-gap-or30` | the first 30 minutes' range stretched to include the previous close | 5-min |
| (f) range + quality filter | `r23-orb-f1`, `r23-orb-f2`, `r23-r22-f1`, `r23-r22-f2` | today's ORB range (5-min break) / R22's range (rows 0 .. 44, 15-min break), taking a signal only when the Part B filter passes at its decision minute: f1 = the best qualifying Step 1 feature on the RUN side of its design median; f2 = the best two, both on their RUN side | 5-min / 15-min |

That is 22 configurations (the family's count for the experiment log: 22 + the 2 baselines + 1 anatomy entry).
**Baselines**, on the same sessions: today's ORB (R20's V0, exactly as R22 ran it) and `r22-brk15` (R22's 15-min close
break of rows 0 .. 44). No other configuration may be added after Part A; if Part B finds no qualifying feature, the 4
(f) configurations are logged as "not run: no feature qualified".

**Random twins** (1,000 each, seed 2023):
- random-level twin: on each index-day with probability 1/2, the range is mirrored around the price at the range end
  (P_end; twin high = 2 P_end - low, twin low = 2 P_end - high), else kept; the same signals, entries, exits.
- random-time twin: each trade re-entered at a decision candle drawn uniformly from the configuration's own window that
  day (same side, contract, exits).
p = (1 + twins with total net >= the configuration's) / 1,001.

Costs (R17's = the hunt studies' and R22's): app paper fills (+-5 bps, stops -10 bps) plus a half-spread of 1 / 2 / 4
ticks by the contract's last 5 minutes of volume (`Fills(mode="liq")`, stops x2), the app's charges at today's rates, the
NSE lot in force. Stress: the half-spread ticks doubled.

## A4. Step 3: selection on the release (design data only)

- Score of every configuration and baseline on the release: net, per-day P&L, trades per day, % green trade days, worst
  day, max drawdown, the 4 design blocks, BANKNIFTY / NIFTY, both sides, "big winners cut short" (option high after
  entry >= entry + 2 x target but sold below entry + target), and the one-sided t-test p (daily net > 0, no-trade days
  = 0).
- **Selection rule** (pre-stated): eligible = net over the release > today's ORB's net on the same days. Rank the eligible
  by (number of positive blocks, then the worst block's net, then the total net), highest first. **Finalists = the top 3**
  (fewer if fewer are eligible). A finalist with net <= 0 or fewer than 3 positive blocks is flagged "carried, not
  promising".
- **Nested walk-forward of the rule itself**: for k = 2, 3, 4 the same rule is applied using blocks 1 .. k-1 only
  (eligibility against the ORB on those blocks; (f) median cuts refit on those blocks), and the top-1 and the top-3 mean
  are scored on block k, beside the ORB, `r22-brk15` and the mean of all 22 configurations on block k. If the picked
  configuration does not beat the ORB out of sample, the selection is no better than chance and the write-up says so.
- (f) filters: the median cut used for the finalists is the one fitted on the whole release; it is frozen in Part C.
- **Part B qualifying rule** for (f) (fixed now): on the ORB reference range, a feature qualifies when (i) its RUN vs FAKE
  Mann-Whitney BH q < 0.10 over all features, (ii) its AUC is on the same side of 0.5 in both halves of the release and
  on BANKNIFTY and on NIFTY separately, (iii) the same side on the R30 reference range, (iv) abs(AUC - 0.5) >= 0.05
  pooled. Bool features: the RUN side is the value with the higher RUN share. The two qualifying features with the
  largest abs(AUC - 0.5) are used (f1 = the first; f2 = both).
- Release twins (random-level, random-time) for the finalists and baselines, descriptive.
- BH (descriptive on the release): the 58 holdout p-values of R20 / R20b / R20c / R22 (`hunt/r22/results/holdout.json`)
  plus the 22 configurations' release t-test p-values. Every R23 p is in-sample, so this can only show "nothing".

## A5. Step 4: the ONE lake read (only when the lake is published)

`R23_SOURCE=lake python3 research/hunt/r23/r23trades.py lake` (refuses twice; refuses with fewer than 100 holdout
sessions per index; refuses if Part C is not committed). For the frozen finalists, today's ORB and `r22-brk15`:
lake design (4 blocks) and the fresh lake holdout (2026-04-27 on). Gates, all required for a finalist to PASS:
- L1 lake holdout net > 0 after costs;
- L2 it beats today's ORB on the lake holdout (net);
- L3 random-level twin and L4 random-time twin p (lake holdout);
- L5 BH q < 0.10 for its holdout t-test (G3), L3 and L4 over the WHOLE family: the 58 earlier holdout p-values + R23's 22
  release p-values + the finalists' 3 x 3 lake p-values;
- L6 at least 3 of 4 lake design blocks positive.
Reported beside the gates: the stress (2x ticks) net, BANKNIFTY / NIFTY, FINNIFTY / MIDCPNIFTY where the lake's options
load through the adapter, the per-day table, and the 13 Oct 2026 session as a forward example only (if its minutes are
available; never used to choose).

## App change (not built here)

If a finalist passes every lake gate, the write-up gives the exact rule for a PAPER-only ORB change (CLAUDE.md: a
paper change from an idea that passed the research gates, logged in the Autopilot timeline, undoable, never live by
itself). If none passes, or the lake is not ready, the write-up says so plainly.

## Part B. The (f) quality filters (written 13 Oct 2026 after Step 1, BEFORE any trading test)

Step 1 (`results/anatomy.md|json`; design = every release session; ORB range: 1,242 breaks on BANKNIFTY 413 / NIFTY
423 / FINNIFTY 406 of 1,455 sessions, 284 RUN, 452 FAKE, 506 CHOP). Eight features qualify by the A4 rule (q < 0.10,
the same side in both halves, on BANKNIFTY, on NIFTY and on the R30 range, abs(AUC - 0.5) >= 0.05): `twap_al` (AUC
0.653), `brk_str` (0.631), `t_break` (0.396), `tests` (0.397), `w_atr` (0.582), `piv_al` (0.576), `pd_dir_al` (0.554),
`w_prev` (0.553). The rule takes the two with the largest abs(AUC - 0.5):

| filter | passes when (at the signal's decision minute) | fitted cut (median of the feature over the 1,242 ORB-range breaks) |
|---|---|---|
| f1 | `twap_al` = side x (decision close - mean of the 1-minute closes 09:15 .. decision) / ATR14 is ABOVE the cut | 0.21982 |
| f2 | f1 AND `brk_str` = side x (decision close - broken edge) / ATR14 is ABOVE the cut | 0.03538 |

So `r23-orb-f1` / `-f2` (today's ORB range, 5-min close break) and `r23-r22-f1` / `-f2` (R22's range, rows 0 .. 44,
15-min close break) take the FIRST signal of each side only when its filter passes (a filtered first signal leaves that
side untraded, as every configuration's "first signal of that side" rule says). In the nested walk-forward (A4) both cuts
are refit as the median over the training blocks' ORB-range breaks. The same cuts are used for both bases.

Caveat, stated before the test: both picks measure how far price has already gone in the break direction at the
decision. `brk_str` is partly tied to the FAKE definition by construction (a close far beyond the edge needs a bigger
reversal to close back inside within 30 minutes), and an early break has more time to run (`t_break`). The trading test,
with real option entries at the decision, is what decides whether this is money or arithmetic. Nothing else changes.

## Part C. The frozen finalists and the exact lake test (written 13 Oct 2026 after Step 3, BEFORE any lake read)

Steps 2-3 ran on the release (515 calendar days, 5 Mar 2024 - 13 Apr 2026, all design; `results/release.md|json`). By
the A4 rule the finalists are, in rank order (`results/finalists.json`, frozen by this commit):

1. `r23-r22-f2`: R22's range (high / low of 09:15 .. 09:59), first 15-min close beyond each edge (candles 10:00 .. 13:45),
   taken only when twap_al > 0.21982 AND brk_str > 0.03538 at that close. Release: 390 trades, Rs +16,158, 2 of 4 blocks.
2. `r23-r22-f1`: the same with twap_al > 0.21982 only. Release: 472 trades, Rs +15,593, 2 of 4 blocks.
3. `r23-orb-f2`: today's ORB range (09:15 .. 10:04), first 5-min close beyond (10:05 .. 13:55), both filters. Release:
   349 trades, Rs -12,461, 2 of 4 blocks.

All three are flagged "carried, not promising" (none has 3 of 4 positive blocks; the third loses). The nested
walk-forward of the rule lost out of sample (top-1 Rs -17,791 over blocks 2-4 vs `r22-brk15` Rs -8,535), and on the
release the mirrored ranges made as much as the real ones (twin-level p 0.72 / 0.59 / 0.81). They are carried to the lake
only because A4 says the top 3 go; nothing here suggests they will pass.

The lake read, ONCE, after the lake is published (`research/data/fetch_release.py --dest $IRAALGO_LAKE` or the Drive copy):

```
R23_SOURCE=lake python3 research/hunt/r23/r23trades.py lake
```

It refuses if `results/finalists.json` is not committed unchanged, if the flag `<cache>/r23_lake/LAKE_HOLDOUT_READ`
exists, or if either index has fewer than 100 sessions from 2026-04-27. Design = lake sessions up to 2026-04-13 (4
blocks), embargo 2026-04-14 .. 26, holdout = 2026-04-27 on. Gates L1-L6 as in A5; BH at q 0.10 over m = 58 + 22 + 9 = 89.
Optional and descriptive only, before the read: `R23_SOURCE=lake python3 research/hunt/r23/r23anatomy.py` (Step 1 on
the lake's design years).
