# R182: order-flow delta - what it is, what it did on the phone's recordings, and a delta skip rule on the three arms

*15 Nov 2026. Research only; nothing in the app changes. Pre-registered (PREREG.md, committed and pushed before any
forward return or trade outcome was computed). Run once (see "How it was run" below).*

## For Boss, in plain words

**What delta is.** Every trade has a buyer and a seller. "Delta" tries to say which side was in a hurry: volume that
traded when the price ticked UP is counted as aggressive buying, volume when it ticked DOWN as aggressive selling, and
delta = buying minus selling in a minute (or 5, or 15). CVD is the running total through the day. Zerodha does not say
who the aggressor was, so the phone ESTIMATES it from the price ticks (the "tick rule"). This is order-flow delta, not the
option's delta (the Greek).

**Which delta to trust.** The phone records delta twice, made by two different pieces of the app:
- the minute file (`orderflow_1m`, TickZone) counts ALL the volume and signs each second by its price change;
- the 1-second file (`orderflow-*.csv.gz`, the live order-flow read the app shows and FlowGate uses) signs each tick
  packet, but deliberately DROPS big prints that did not move the price (over 5x the usual packet size) and re-sent old
  packets. It holds only 65-83% of the volume, which is why the two disagree on about 97% of minutes (any missing
  print changes the number). The footprint is made from the same packets as the 1-second file, so it matches it.
- Neither is the truth (no aggressor flag). The pre-registered test of a signing is how tightly it tracks the same
  minute's price change. On the same minutes (9 days; 3,210 NIFTY and 3,212 BANKNIFTY minutes) the **minute file's delta
  tracks price best (rank correlation +0.45 / +0.48)**, the 1-second file's quote-rule delta next (+0.42 / +0.40), and the
  **1-second file's tick-rule delta - the one the app's live read uses - worst (+0.27 / +0.27)**. So the minute file is
  the more trustworthy delta; the 1-second one is noisier. The study had to use the 1-second file (pre-registered)
  because the minute file stops on 28 Oct, before most of the arms' trades.

**What delta did on this data (Part A, 20 days, about 6,100-6,400 minutes per index).**
- **It moves WITH price in the same minute** - as expected, partly by construction: rank correlation +0.24 NIFTY,
  +0.28 BANKNIFTY, +0.13 FINNIFTY, +0.18 MIDCPNIFTY (all intervals well above 0); +0.44 / +0.49 in the minute file.
- **It does NOT tell where price goes next.** The pre-registered test (5-minute delta -> the next 15 minutes' futures
  move) gave NIFTY -0.007 (95% interval -0.055 to +0.034, p 0.76), BANKNIFTY -0.028 (-0.075 to +0.020, p 0.24),
  FINNIFTY -0.064 (-0.121 to -0.005, p 0.031), MIDCPNIFTY -0.006 (-0.067 to +0.048, p 0.84). BH q = 1.0 for all (m =
  4,073). If anything the sign is slightly NEGATIVE (after buying, price drifted a little lower), the opposite of the
  usual reading, and FINNIFTY's futures hardly trade (its delta is zero most minutes).
- In numbers you can feel: after a positive 5-minute NIFTY delta the next 15 minutes moved +0.73 basis points on average
  (3,329 minutes), after a negative one +0.93 bp (3,007) - both tiny, both up (the rally), no edge.
- Every other version (1, 5, 15 minute delta, 15-minute CVD slope, CVD divergence, book imbalance, absorption) against
  1, 5, 15 and 30 minutes ahead: all between -0.07 and +0.05; 6 of 104 cells have an interval excluding 0, about what
  chance alone gives. By time of day the first hour leans slightly positive (+0.07 / +0.08, intervals include 0) and the
  afternoon slightly negative (BANKNIFTY 13:30-15:15 -0.10).
- The minute file on its 9 days says the same: NIFTY +0.016, BANKNIFTY -0.100 (-0.185 to -0.041).

**The three arms (Part B): their real paper trades, 21 Oct - 13 Nov 2026 (16 trading days with trades).**
179 trades rebuilt from the paper orders (every buy matched to its sells; none left open, none orphaned); all fall on
order-flow days; 149 are on an index whose future is recorded (the 30 SENSEX trades have no flow); 129 had a non-zero
5-minute futures delta before entry. The pre-registered rule: **skip the entry when the underlying future's 5-minute
delta is against the trade** (CE wants buying, PE wants selling). Net = rupees after real dated charges on the app's own
paper fills.

| arm | trades | win | net | rule skips | skipped: win, net | rule keeps | kept: win, net | random-skip twins p | BH q |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | 9 | 44% | Rs -3,635 | 4 | 25%, Rs -3,842 | 5 | 60%, Rs 207 | 0.219 | 1.0 |
| R51 Donchian 20 | 94 | 53% | Rs -38,448 | 14 | 64%, Rs 2,596 | 80 | 51%, Rs -41,044 | 0.523 | 1.0 |
| R72 Breadth Donchian | 76 | 49% | Rs -29,597 | 9 | 67%, Rs -1,149 | 67 | 46%, Rs -28,447 | 0.420 | 1.0 |
| all three (descriptive) | 179 | 51% | Rs -71,680 | 27 | 59%, Rs -2,395 | 152 | 49%, Rs -69,284 | 0.421 | - |

- **Delta agrees vs disagrees at entry** (all three arms): agrees 102 trades, win 58%, average Rs -71; disagrees 27,
  win 59%, average Rs -89. No real difference. For R51 the "against delta" trades were actually the better ones (64% won,
  +Rs 185 a trade vs -Rs 141). Liquidity went the hoped-for way (the 4 skipped lost Rs 3,842) but 4 trades is nothing.
- The secondary versions (only strong opposition; the 15-minute delta; absorption against the side) were no better;
  absorption happened before only 2 of 179 entries.
- The arms are breakout systems, so most entries already go WITH the delta (102 agree, 27 disagree): a delta filter
  would rarely act.
- The trades with NO usable delta did worst (zero delta, mostly thin FINNIFTY futures: 11 trades, average Rs -2,123; no
  label, mostly SENSEX: 39 trades, average Rs -993). That is the instruments, not delta; descriptive only.

**This cannot pass the repo's gates** (>= 40 holdout trades, every period positive, BH): 179 trades over 16 days, R51 and
R72 sharing 24 identical entries. Read everything here as "no sign of a delta edge yet", not as a proof.

**Data problems.**
- The two delta recorders disagree by design (above); the app's live read uses the noisier one.
- The 1-second file drops 17-35% of the futures volume (big prints at an unchanged price).
- One print can dominate a day: BANKNIFTY 21 Oct, 29,010 units in one second signed "buy" set the day's CVD.
- FINNIFTY futures barely trade (prints in 53-362 seconds a day); MIDCPNIFTY is thin and gappy from 3 Nov (up to 262 thin
  minutes on 12 Nov).
- Missing futures minutes: 9 Nov 82, 6 Nov 56, 4 Nov 37, 11 Nov 35-36, 13 Nov 29-30, 2 Nov 24, 23 Oct 18-19; longest gap
  32 minutes (4 Nov). No recording on 20 Oct or 10 Nov.
- The footprint (absorption) exists only for NIFTY and BANKNIFTY; absorption was rare (0-3 minutes a day).
- The minute file (the better delta) was exported only to 28 Oct.

**Verdict.** No. Delta, as the phone records it, moves with price but did not predict the next 1-30 minutes on any of
the four index futures, and skipping the arms' entries against the 5-minute delta did not beat skipping at random (p 0.22
/ 0.52 / 0.42, q = 1.0). By the pre-registered recommendation rule (an arm's primary "helps", or Part A positive on 2+
indices) **a new shadow delta label on these arms is NOT worth adding now**. Liquidity's entries are already logged with
the live flow read by FlowGate's shadow log (52 Liquidity signals in the exported log), so its forward record grows
anyway.

**Next steps.**
1. App side (separate task, not done here): look at the 1-second recorder's tick-rule delta, which tracks price worst of
   the three signings - its big-print filter drops a fifth to a third of the volume, and the live order-flow read (and
   any CONFIRM) rests on it.
2. Keep exporting the minute file (`orderflow_1m`) so a re-run can use the better delta.
3. Re-run R182 unchanged (the code takes any number of days) at about 60 trading days (roughly 600 arm trades).

## How it was run

- PREREG.md committed and pushed first (60703f992). The first run attempt stopped with an error in Part A's
  descriptive grid (absorption is constant on FINNIFTY and MIDCPNIFTY, which have no footprint, so a bootstrap had no
  values) before anything was written or printed; the guard was added with a test and the run done once more. All numbers
  are from that completed run.
- One post-hoc, descriptive check was added after the run: the three signings compared on the SAME minutes
  (`r182posthoc.py`, `results/posthoc.json`); it is the "which delta to trust" comparison above.
- The pre-registered "helps" bar compares the skipped trades' average with the kept trades'. For R72 the skipped average
  (Rs -128) is above all kept trades' (Rs -425) but below the labelled kept trades' (Rs +18); either way its twin p (0.42)
  misses the 0.20 bar, so no arm "helps".
- Per-trade rows stay out of git; `results/results.json` holds aggregates only.
- Signal source: the index near future's order flow (1-second tick-rule delta); the arms' own entries, exits and option
  fills are unchanged.

## Tables (from results/results.json)

Run 2026-11-15T10:52:18+00:00; PREREG.md SHA-256 cf0fc8640f928e98...; 20 order-flow days (2026-10-15 - 2026-11-13); BH m = 4,073.

### Pre-registered tests

| test | what | p | BH q (m = 4,073) | q within R182 |
|---|---|---|---|---|
| r182-a-nifty | Part A primary, NIFTY: 5-minute delta -> next 15-minute futures return | 0.7574 | 1.000 | 0.840 |
| r182-a-banknifty | Part A primary, BANKNIFTY: 5-minute delta -> next 15-minute futures return | 0.2430 | 1.000 | 0.567 |
| r182-a-finnifty | Part A primary, FINNIFTY: 5-minute delta -> next 15-minute futures return | 0.0309 | 1.000 | 0.216 |
| r182-a-midcpnifty | Part A primary, MIDCPNIFTY: 5-minute delta -> next 15-minute futures return | 0.8398 | 1.000 | 0.840 |
| r182-b-liquidity | Part B primary, Liquidity: skip when the 5-minute futures delta opposes | 0.2189 | 1.000 | 0.567 |
| r182-b-r51 | Part B primary, R51: skip when the 5-minute futures delta opposes | 0.5231 | 1.000 | 0.732 |
| r182-b-r72 | Part B primary, R72: skip when the 5-minute futures delta opposes | 0.4204 | 1.000 | 0.732 |

### Part A: delta and the index future's price (1-second file, 20 days, minute closes 09:30-15:14)

| index | same-minute rho (95% CI) | quote rule same-minute rho | primary: D5 -> next 15 min rho (95% CI) | p (day bootstrap) | p (circular shift) | minutes | next-15-min return after D5 > 0 / < 0 (bp, n) |
|---|---|---|---|---|---|---|---|
| NIFTY | +0.243 (+0.218, +0.272) | +0.404 | -0.007 (-0.055, +0.034) | 0.757 | 0.822 | 6,361 | +0.73 (3,329) / +0.93 (3,007) |
| BANKNIFTY | +0.282 (+0.259, +0.308) | +0.418 | -0.028 (-0.075, +0.020) | 0.243 | 0.295 | 6,356 | +0.58 (3,235) / +1.21 (3,061) |
| FINNIFTY | +0.132 (+0.086, +0.179) | +0.401 | -0.064 (-0.121, -0.005) | 0.031 | 0.044 | 6,259 | +0.17 (1,930) / +3.07 (1,376) |
| MIDCPNIFTY | +0.176 (+0.155, +0.199) | +0.279 | -0.006 (-0.067, +0.048) | 0.840 | 0.848 | 6,137 | +0.30 (3,120) / +0.87 (2,889) |

Minute-file (TickZone) cross-check on its overlap days, same statistics:

| index | days | same-minute rho (95% CI) | D5 -> next 15 min rho (95% CI) | minutes |
|---|---|---|---|---|
| NIFTY | 9 | +0.443 (+0.432, +0.454) | +0.016 (-0.053, +0.078) | 3,025 |
| BANKNIFTY | 9 | +0.487 (+0.439, +0.536) | -0.100 (-0.185, -0.041) | 3,025 |

Primary by time of day (rho, 95% CI, minutes):

| index | 09:30-10:30 | 10:30-13:30 | 13:30-15:15 |
|---|---|---|---|
| NIFTY | +0.069 (-0.064, +0.183), 1,144 | -0.020 (-0.081, +0.038), 3,368 | -0.054 (-0.140, +0.028), 1,849 |
| BANKNIFTY | +0.083 (-0.025, +0.183), 1,145 | -0.050 (-0.105, -0.002), 3,362 | -0.096 (-0.191, -0.019), 1,849 |
| FINNIFTY | -0.048 (-0.240, +0.157), 1,049 | -0.058 (-0.112, +0.003), 3,361 | -0.086 (-0.198, +0.014), 1,849 |
| MIDCPNIFTY | +0.070 (-0.055, +0.166), 1,005 | -0.048 (-0.142, +0.043), 3,291 | +0.007 (-0.099, +0.093), 1,841 |

Descriptive grid: rank correlation of each feature at the minute close with the next h minutes' futures return (95% day-bootstrap interval; * = interval excludes 0). Not tests.

**NIFTY**

| feature | 1 min | 5 min | 15 min | 30 min |
|---|---|---|---|---|
| D1 | +0.018* (+0.003, +0.031) | +0.002 (-0.020, +0.023) | +0.008 (-0.017, +0.029) | +0.008 (-0.025, +0.033) |
| D5 | -0.001 (-0.016, +0.015) | -0.006 (-0.049, +0.042) | -0.007 (-0.052, +0.032) | -0.010 (-0.068, +0.041) |
| D15 | +0.006 (-0.012, +0.022) | +0.005 (-0.040, +0.044) | -0.009 (-0.075, +0.045) | +0.001 (-0.082, +0.076) |
| CVDslope15 | +0.004 (-0.014, +0.022) | +0.008 (-0.036, +0.046) | -0.018 (-0.080, +0.035) | -0.004 (-0.085, +0.068) |
| Div15 | +0.021 (-0.001, +0.044) | +0.000 (-0.046, +0.049) | -0.016 (-0.089, +0.057) | -0.020 (-0.091, +0.067) |
| OBI | -0.032* (-0.052, -0.010) | -0.019 (-0.055, +0.017) | -0.013 (-0.071, +0.058) | -0.014 (-0.087, +0.059) |
| Absorb5 | +0.001 (-0.007, +0.006) | +0.014 (-0.001, +0.037) | -0.016 (-0.056, +0.033) | -0.026 (-0.067, +0.030) |

**BANKNIFTY**

| feature | 1 min | 5 min | 15 min | 30 min |
|---|---|---|---|---|
| D1 | -0.004 (-0.027, +0.022) | -0.020 (-0.050, +0.012) | -0.022 (-0.047, +0.003) | -0.015 (-0.048, +0.016) |
| D5 | -0.018 (-0.043, +0.007) | -0.039 (-0.080, +0.001) | -0.028 (-0.076, +0.020) | -0.015 (-0.085, +0.049) |
| D15 | -0.019 (-0.040, +0.003) | -0.016 (-0.047, +0.013) | +0.008 (-0.070, +0.080) | +0.003 (-0.103, +0.091) |
| CVDslope15 | -0.014 (-0.036, +0.008) | -0.015 (-0.050, +0.016) | +0.008 (-0.066, +0.074) | -0.001 (-0.101, +0.084) |
| Div15 | +0.002 (-0.019, +0.025) | +0.015 (-0.029, +0.059) | +0.010 (-0.058, +0.072) | -0.028 (-0.108, +0.042) |
| OBI | +0.003 (-0.027, +0.032) | +0.013 (-0.036, +0.056) | +0.023 (-0.047, +0.092) | +0.027 (-0.062, +0.110) |
| Absorb5 | +0.003 (-0.008, +0.010) | +0.021 (-0.014, +0.054) | +0.047* (+0.023, +0.073) | +0.008 (-0.046, +0.060) |

**FINNIFTY**

| feature | 1 min | 5 min | 15 min | 30 min |
|---|---|---|---|---|
| D1 | +0.016 (-0.009, +0.042) | +0.007 (-0.019, +0.030) | -0.029* (-0.057, -0.003) | -0.039 (-0.089, +0.013) |
| D5 | -0.002 (-0.027, +0.018) | -0.025 (-0.063, +0.012) | -0.064* (-0.120, -0.006) | -0.066 (-0.149, +0.024) |
| D15 | -0.009 (-0.034, +0.016) | -0.025 (-0.077, +0.023) | -0.066 (-0.145, +0.016) | -0.064 (-0.171, +0.044) |
| CVDslope15 | -0.010 (-0.040, +0.017) | -0.029 (-0.080, +0.021) | -0.065 (-0.143, +0.019) | -0.059 (-0.165, +0.049) |
| Div15 | -0.008 (-0.043, +0.023) | -0.040 (-0.099, +0.019) | -0.051 (-0.117, +0.026) | -0.063 (-0.131, +0.024) |
| OBI | +0.007 (-0.035, +0.044) | +0.038 (-0.010, +0.081) | +0.050 (-0.015, +0.113) | +0.050 (-0.056, +0.139) |
| Absorb5 | - (-, -) | - (-, -) | - (-, -) | - (-, -) |

**MIDCPNIFTY**

| feature | 1 min | 5 min | 15 min | 30 min |
|---|---|---|---|---|
| D1 | -0.012 (-0.035, +0.011) | -0.021 (-0.056, +0.015) | -0.009 (-0.056, +0.034) | -0.020 (-0.060, +0.016) |
| D5 | -0.009 (-0.028, +0.012) | -0.017 (-0.060, +0.024) | -0.006 (-0.065, +0.045) | -0.036 (-0.093, +0.023) |
| D15 | +0.008 (-0.015, +0.030) | +0.009 (-0.043, +0.059) | +0.011 (-0.066, +0.092) | -0.041 (-0.142, +0.061) |
| CVDslope15 | +0.006 (-0.018, +0.028) | +0.006 (-0.047, +0.057) | +0.007 (-0.069, +0.087) | -0.042 (-0.137, +0.054) |
| Div15 | +0.026* (+0.004, +0.051) | +0.022 (-0.017, +0.066) | -0.014 (-0.059, +0.038) | -0.038 (-0.097, +0.029) |
| OBI | -0.014 (-0.030, +0.004) | -0.011 (-0.056, +0.032) | -0.042 (-0.098, +0.016) | -0.018 (-0.091, +0.053) |
| Absorb5 | - (-, -) | - (-, -) | - (-, -) | - (-, -) |

### Part B: the arms' paper trades (net = rupees after dated charges, 1 trade as the arm sized it)

**Liquidity**: all 9, 44%, Rs -3,635, Rs -404 (n, win, net, average); labelled 8, no label 1; points -33.1; average -1.8% of the premium.

| rule | eligible (labelled) | kept, all: n, win, net, avg | skipped: n, win, net, avg | kept net of the eligible: rule / random twins mean (95%) | twin p |
|---|---|---|---|---|---|
| primary: 5-minute delta opposes | 8 | 5, 60%, Rs 207, Rs 41 | 4, 25%, Rs -3,842, Rs -961 | Rs -1,016 / Rs -2,417 (Rs -5,418, Rs 560) | 0.219 |
| V1: strong opposition (beyond the day's typical) | 7 | 7, 43%, Rs -1,140, Rs -163 | 2, 50%, Rs -2,495, Rs -1,248 | Rs -1,194 / Rs -2,620 (Rs -4,622, Rs 381) | 0.194 |
| V2: 15-minute delta opposes | 8 | 6, 50%, Rs -2,383, Rs -397 | 3, 33%, Rs -1,253, Rs -418 | Rs -3,605 / Rs -3,017 (Rs -5,611, Rs -320) | 0.647 |
| V3: absorption against the side, last 5 minutes | 3 | 9, 44%, Rs -3,635, Rs -404 | 0, -, Rs 0, - | - | - |

| delta at entry | n | win | net | average |
|---|---|---|---|---|
| agrees | 4 | 50% | Rs -1,016 | Rs -254 |
| disagrees | 4 | 25% | Rs -3,842 | Rs -961 |
| zero | 1 | 100% | Rs 1,223 | Rs 1,223 |
| no label | 0 | - | Rs 0 | - |

**R51**: all 94, 53%, Rs -38,448, Rs -409 (n, win, net, average); labelled 64, no label 30; points -746.1; average -1.1% of the premium.

| rule | eligible (labelled) | kept, all: n, win, net, avg | skipped: n, win, net, avg | kept net of the eligible: rule / random twins mean (95%) | twin p |
|---|---|---|---|---|---|
| primary: 5-minute delta opposes | 64 | 80, 51%, Rs -41,044, Rs -513 | 14, 64%, Rs 2,596, Rs 185 | Rs -7,061 / Rs -3,559 (Rs -20,639, Rs 23,072) | 0.523 |
| V1: strong opposition (beyond the day's typical) | 44 | 87, 53%, Rs -36,683, Rs -422 | 7, 57%, Rs -1,765, Rs -252 | Rs -6,054 / Rs -6,580 (Rs -15,443, Rs 2,376) | 0.452 |
| V2: 15-minute delta opposes | 64 | 78, 51%, Rs -41,169, Rs -528 | 16, 62%, Rs 2,721, Rs 170 | Rs -7,185 / Rs -3,282 (Rs -21,832, Rs 23,175) | 0.532 |
| V3: absorption against the side, last 5 minutes | 32 | 93, 53%, Rs -39,611, Rs -426 | 1, 100%, Rs 1,163, Rs 1,163 | Rs 3,908 / Rs 4,902 (Rs 455, Rs 9,202) | 0.813 |

| delta at entry | n | win | net | average |
|---|---|---|---|---|
| agrees | 50 | 62% | Rs -7,061 | Rs -141 |
| disagrees | 14 | 64% | Rs 2,596 | Rs 185 |
| zero | 6 | 17% | Rs -14,076 | Rs -2,346 |
| no label | 24 | 38% | Rs -19,908 | Rs -830 |

**R72**: all 76, 49%, Rs -29,597, Rs -389 (n, win, net, average); labelled 57, no label 19; points -587.5; average -1.7% of the premium.

| rule | eligible (labelled) | kept, all: n, win, net, avg | skipped: n, win, net, avg | kept net of the eligible: rule / random twins mean (95%) | twin p |
|---|---|---|---|---|---|
| primary: 5-minute delta opposes | 57 | 67, 46%, Rs -28,447, Rs -425 | 9, 67%, Rs -1,149, Rs -128 | Rs 852 / Rs -252 (Rs -10,077, Rs 9,075) | 0.420 |
| V1: strong opposition (beyond the day's typical) | 42 | 70, 47%, Rs -29,185, Rs -417 | 6, 67%, Rs -412, Rs -69 | Rs -16,371 / Rs -14,367 (Rs -21,752, Rs -7,112) | 0.704 |
| V2: 15-minute delta opposes | 57 | 61, 44%, Rs -35,248, Rs -578 | 15, 67%, Rs 5,651, Rs 377 | Rs -5,949 / Rs -220 (Rs -11,979, Rs 11,509) | 0.825 |
| V3: absorption against the side, last 5 minutes | 37 | 75, 48%, Rs -30,759, Rs -410 | 1, 100%, Rs 1,163, Rs 1,163 | Rs 1,203 / Rs 2,309 (Rs -2,249, Rs 6,094) | 0.867 |

| delta at entry | n | win | net | average |
|---|---|---|---|---|
| agrees | 48 | 54% | Rs 852 | Rs 18 |
| disagrees | 9 | 67% | Rs -1,149 | Rs -128 |
| zero | 4 | 25% | Rs -10,498 | Rs -2,624 |
| no label | 15 | 27% | Rs -18,801 | Rs -1,253 |

**all three (descriptive)**: all 179, 51%, Rs -71,680, Rs -400 (n, win, net, average); labelled 129, no label 50; points -1,366.7; average -1.4% of the premium.

| rule | eligible (labelled) | kept, all: n, win, net, avg | skipped: n, win, net, avg | kept net of the eligible: rule / random twins mean (95%) | twin p |
|---|---|---|---|---|---|
| primary: 5-minute delta opposes | 129 | 152, 49%, Rs -69,284, Rs -456 | 27, 59%, Rs -2,395, Rs -89 | Rs -7,224 / Rs -7,510 (Rs -28,719, Rs 21,379) | 0.421 |
| V1: strong opposition (beyond the day's typical) | 93 | 164, 50%, Rs -67,007, Rs -409 | 15, 60%, Rs -4,672, Rs -311 | Rs -23,619 / Rs -23,594 (Rs -35,890, Rs -11,681) | 0.506 |
| V2: 15-minute delta opposes | 129 | 145, 48%, Rs -78,799, Rs -543 | 34, 62%, Rs 7,120, Rs 209 | Rs -16,739 / Rs -7,066 (Rs -29,681, Rs 22,463) | 0.736 |
| V3: absorption against the side, last 5 minutes | 72 | 177, 50%, Rs -74,005, Rs -418 | 2, 100%, Rs 2,325, Rs 1,163 | Rs 3,519 / Rs 5,691 (Rs 558, Rs 10,153) | 0.841 |

| delta at entry | n | win | net | average |
|---|---|---|---|---|
| agrees | 102 | 58% | Rs -7,224 | Rs -71 |
| disagrees | 27 | 59% | Rs -2,395 | Rs -89 |
| zero | 11 | 27% | Rs -23,351 | Rs -2,123 |
| no label | 39 | 33% | Rs -38,709 | Rs -993 |

### Trades per arm per day (rebuilt from the paper orders)

| arm | 10-21 | 10-22 | 10-26 | 10-27 | 10-28 | 10-29 | 10-30 | 11-02 | 11-03 | 11-04 | 11-05 | 11-06 | 11-09 | 11-11 | 11-12 | 11-13 | total | with futures flow | labelled |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity | 0 | 1 | 2 | 0 | 1 | 4 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 9 | 9 | 8 |
| R51 | 0 | 0 | 1 | 1 | 0 | 0 | 5 | 17 | 9 | 14 | 8 | 9 | 7 | 13 | 4 | 6 | 94 | 75 | 64 |
| R72 | 4 | 0 | 7 | 0 | 1 | 4 | 2 | 12 | 4 | 4 | 0 | 10 | 3 | 15 | 4 | 6 | 76 | 65 | 57 |

By underlying: Liquidity {'MIDCPNIFTY': 1, 'FINNIFTY': 4, 'BANKNIFTY': 4}; R51 {'MIDCPNIFTY': 20, 'SENSEX': 19, 'NIFTY': 15, 'FINNIFTY': 21, 'BANKNIFTY': 19}; R72 {'NIFTY': 20, 'BANKNIFTY': 19, 'FINNIFTY': 13, 'MIDCPNIFTY': 13, 'SENSEX': 11}. Same option bought in the same second by R51 and R72: Liquidity 0, R51 24, R72 24.

### Data quality (futures, 1-second file)

| index | day | seconds | minutes recorded | thin (<20 s) | missing | gaps >= 5 s | longest gap (s) | seconds with a trade | absorption minutes |
|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 2026-10-15 | 17,089 | 375 | 1 | 0 | 3 | 50 | 9,546 | 0 |
| NIFTY | 2026-10-16 | 15,735 | 373 | 8 | 2 | 8 | 124 | 7,060 | 0 |
| NIFTY | 2026-10-19 | 15,892 | 372 | 8 | 3 | 24 | 118 | 7,524 | 0 |
| NIFTY | 2026-10-21 | 17,282 | 373 | 3 | 2 | 15 | 91 | 9,742 | 0 |
| NIFTY | 2026-10-22 | 17,053 | 375 | 1 | 0 | 1 | 54 | 10,110 | 0 |
| NIFTY | 2026-10-23 | 15,965 | 356 | 5 | 19 | 22 | 1,062 | 10,218 | 0 |
| NIFTY | 2026-10-26 | 16,013 | 371 | 3 | 4 | 28 | 165 | 10,444 | 0 |
| NIFTY | 2026-10-27 | 16,162 | 372 | 6 | 3 | 15 | 183 | 10,131 | 1 |
| NIFTY | 2026-10-28 | 16,629 | 374 | 7 | 1 | 21 | 138 | 8,893 | 0 |
| NIFTY | 2026-10-29 | 16,098 | 369 | 3 | 6 | 9 | 418 | 7,664 | 0 |
| NIFTY | 2026-10-30 | 16,550 | 375 | 0 | 0 | 1 | 4 | 7,978 | 0 |
| NIFTY | 2026-11-02 | 15,033 | 351 | 9 | 24 | 24 | 425 | 7,749 | 0 |
| NIFTY | 2026-11-03 | 16,657 | 374 | 3 | 1 | 16 | 117 | 9,412 | 0 |
| NIFTY | 2026-11-04 | 15,058 | 338 | 5 | 37 | 19 | 1,912 | 8,557 | 0 |
| NIFTY | 2026-11-05 | 17,352 | 371 | 6 | 4 | 9 | 158 | 10,616 | 1 |
| NIFTY | 2026-11-06 | 14,732 | 319 | 6 | 56 | 11 | 1,806 | 8,595 | 0 |
| NIFTY | 2026-11-09 | 12,796 | 293 | 9 | 82 | 12 | 1,474 | 6,599 | 0 |
| NIFTY | 2026-11-11 | 15,235 | 340 | 14 | 35 | 23 | 1,084 | 8,638 | 1 |
| NIFTY | 2026-11-12 | 16,965 | 375 | 1 | 0 | 0 | 3 | 8,731 | 0 |
| NIFTY | 2026-11-13 | 14,740 | 346 | 6 | 29 | 18 | 1,205 | 6,968 | 1 |
| BANKNIFTY | 2026-10-15 | 18,699 | 375 | 1 | 0 | 3 | 51 | 5,224 | 0 |
| BANKNIFTY | 2026-10-16 | 17,952 | 373 | 7 | 2 | 8 | 127 | 3,239 | 2 |
| BANKNIFTY | 2026-10-19 | 17,883 | 372 | 5 | 3 | 22 | 117 | 3,993 | 3 |
| BANKNIFTY | 2026-10-21 | 18,884 | 373 | 1 | 2 | 16 | 91 | 7,461 | 0 |
| BANKNIFTY | 2026-10-22 | 18,592 | 375 | 1 | 0 | 1 | 55 | 4,465 | 0 |
| BANKNIFTY | 2026-10-23 | 17,404 | 357 | 5 | 18 | 20 | 1,062 | 6,479 | 1 |
| BANKNIFTY | 2026-10-26 | 18,291 | 371 | 3 | 4 | 23 | 165 | 7,231 | 0 |
| BANKNIFTY | 2026-10-27 | 18,338 | 372 | 4 | 3 | 15 | 182 | 5,481 | 1 |
| BANKNIFTY | 2026-10-28 | 18,129 | 373 | 6 | 2 | 20 | 140 | 4,331 | 0 |
| BANKNIFTY | 2026-10-29 | 18,268 | 369 | 3 | 6 | 11 | 418 | 5,295 | 1 |
| BANKNIFTY | 2026-10-30 | 18,837 | 375 | 0 | 0 | 1 | 4 | 5,062 | 0 |
| BANKNIFTY | 2026-11-02 | 16,763 | 351 | 9 | 24 | 29 | 424 | 4,109 | 0 |
| BANKNIFTY | 2026-11-03 | 18,390 | 374 | 3 | 1 | 16 | 117 | 6,184 | 0 |
| BANKNIFTY | 2026-11-04 | 16,603 | 338 | 4 | 37 | 16 | 1,912 | 5,336 | 0 |
| BANKNIFTY | 2026-11-05 | 18,916 | 371 | 5 | 4 | 9 | 168 | 9,400 | 0 |
| BANKNIFTY | 2026-11-06 | 16,061 | 319 | 5 | 56 | 11 | 1,806 | 6,924 | 0 |
| BANKNIFTY | 2026-11-09 | 14,323 | 293 | 6 | 82 | 8 | 1,475 | 4,904 | 0 |
| BANKNIFTY | 2026-11-11 | 16,910 | 339 | 11 | 36 | 20 | 1,085 | 8,272 | 0 |
| BANKNIFTY | 2026-11-12 | 19,008 | 375 | 1 | 0 | 0 | 3 | 6,380 | 0 |
| BANKNIFTY | 2026-11-13 | 16,780 | 346 | 6 | 29 | 20 | 1,205 | 4,220 | 0 |
| FINNIFTY | 2026-10-15 | 13,427 | 375 | 3 | 0 | 6 | 50 | 136 | 0 |
| FINNIFTY | 2026-10-16 | 13,608 | 373 | 7 | 2 | 8 | 127 | 65 | 0 |
| FINNIFTY | 2026-10-19 | 13,152 | 372 | 14 | 3 | 34 | 117 | 113 | 0 |
| FINNIFTY | 2026-10-21 | 13,360 | 373 | 8 | 2 | 43 | 92 | 99 | 0 |
| FINNIFTY | 2026-10-22 | 13,492 | 375 | 2 | 0 | 4 | 55 | 84 | 0 |
| FINNIFTY | 2026-10-23 | 12,573 | 356 | 10 | 19 | 45 | 1,060 | 124 | 0 |
| FINNIFTY | 2026-10-26 | 13,183 | 371 | 4 | 4 | 26 | 165 | 204 | 0 |
| FINNIFTY | 2026-10-27 | 13,201 | 372 | 9 | 3 | 26 | 185 | 362 | 0 |
| FINNIFTY | 2026-10-28 | 9,618 | 373 | 105 | 2 | 817 | 140 | 112 | 0 |
| FINNIFTY | 2026-10-29 | 12,908 | 369 | 7 | 6 | 52 | 419 | 123 | 0 |
| FINNIFTY | 2026-10-30 | 13,308 | 375 | 7 | 0 | 56 | 23 | 63 | 0 |
| FINNIFTY | 2026-11-02 | 12,135 | 351 | 14 | 24 | 73 | 423 | 53 | 0 |
| FINNIFTY | 2026-11-03 | 12,909 | 374 | 6 | 1 | 54 | 117 | 104 | 0 |
| FINNIFTY | 2026-11-04 | 11,672 | 338 | 8 | 37 | 44 | 1,912 | 96 | 0 |
| FINNIFTY | 2026-11-05 | 12,967 | 371 | 16 | 4 | 54 | 168 | 294 | 0 |
| FINNIFTY | 2026-11-06 | 11,281 | 319 | 7 | 56 | 17 | 1,806 | 115 | 0 |
| FINNIFTY | 2026-11-09 | 9,253 | 293 | 33 | 82 | 100 | 1,477 | 77 | 0 |
| FINNIFTY | 2026-11-11 | 11,411 | 339 | 27 | 36 | 77 | 1,084 | 289 | 0 |
| FINNIFTY | 2026-11-12 | 13,258 | 375 | 6 | 0 | 22 | 18 | 128 | 0 |
| FINNIFTY | 2026-11-13 | 11,931 | 346 | 11 | 29 | 40 | 1,206 | 83 | 0 |
| MIDCPNIFTY | 2026-10-15 | 18,332 | 375 | 1 | 0 | 3 | 49 | 1,954 | 0 |
| MIDCPNIFTY | 2026-10-16 | 17,581 | 373 | 9 | 2 | 15 | 125 | 1,070 | 0 |
| MIDCPNIFTY | 2026-10-19 | 17,521 | 372 | 6 | 3 | 22 | 117 | 1,170 | 0 |
| MIDCPNIFTY | 2026-10-21 | 18,080 | 373 | 2 | 2 | 17 | 91 | 2,377 | 0 |
| MIDCPNIFTY | 2026-10-22 | 18,322 | 375 | 1 | 0 | 1 | 54 | 3,162 | 0 |
| MIDCPNIFTY | 2026-10-23 | 17,250 | 357 | 5 | 18 | 23 | 1,063 | 4,906 | 0 |
| MIDCPNIFTY | 2026-10-26 | 17,619 | 371 | 3 | 4 | 25 | 164 | 2,265 | 0 |
| MIDCPNIFTY | 2026-10-27 | 17,706 | 372 | 5 | 3 | 15 | 182 | 2,132 | 0 |
| MIDCPNIFTY | 2026-10-28 | 17,644 | 372 | 7 | 3 | 21 | 140 | 1,954 | 0 |
| MIDCPNIFTY | 2026-10-29 | 17,485 | 369 | 3 | 6 | 9 | 417 | 896 | 0 |
| MIDCPNIFTY | 2026-10-30 | 18,458 | 375 | 0 | 0 | 1 | 3 | 3,050 | 0 |
| MIDCPNIFTY | 2026-11-02 | 16,323 | 351 | 10 | 24 | 30 | 423 | 1,392 | 0 |
| MIDCPNIFTY | 2026-11-03 | 13,933 | 371 | 84 | 4 | 206 | 117 | 1,044 | 0 |
| MIDCPNIFTY | 2026-11-04 | 15,140 | 338 | 26 | 37 | 99 | 1,911 | 1,684 | 0 |
| MIDCPNIFTY | 2026-11-05 | 11,195 | 371 | 156 | 4 | 478 | 168 | 1,648 | 0 |
| MIDCPNIFTY | 2026-11-06 | 15,295 | 319 | 6 | 56 | 16 | 1,806 | 2,016 | 0 |
| MIDCPNIFTY | 2026-11-09 | 12,477 | 292 | 33 | 83 | 76 | 1,475 | 676 | 0 |
| MIDCPNIFTY | 2026-11-11 | 13,040 | 339 | 79 | 36 | 255 | 1,084 | 931 | 0 |
| MIDCPNIFTY | 2026-11-12 | 5,610 | 363 | 262 | 12 | 532 | 94 | 518 | 0 |
| MIDCPNIFTY | 2026-11-13 | 11,537 | 345 | 119 | 30 | 396 | 1,206 | 1,120 | 0 |

### The two recorders on the same minutes (minutes with >= 50 s in the minute file and >= 20 s in the 1-second file)

| index | day | minutes | 1-second volume / minute volume | delta equal | delta sign agrees | corr (minute vs 1-second delta) | corr quote rule vs minute | corr quote rule vs 1-second |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 2026-10-15 | 372 | 0.76 | 1.6% | 68% | +0.51 | +0.57 | +0.59 |
| NIFTY | 2026-10-16 | 361 | 0.77 | 2.8% | 67% | +0.77 | +0.70 | +0.76 |
| NIFTY | 2026-10-19 | 360 | 0.81 | 2.5% | 68% | +0.73 | +0.74 | +0.57 |
| NIFTY | 2026-10-21 | 369 | 0.82 | 1.1% | 69% | +0.74 | +0.42 | +0.37 |
| NIFTY | 2026-10-22 | 373 | 0.83 | 0.8% | 69% | +0.74 | +0.65 | +0.75 |
| NIFTY | 2026-10-23 | 347 | 0.79 | 1.4% | 62% | +0.54 | +0.14 | +0.09 |
| NIFTY | 2026-10-26 | 365 | 0.68 | 0.5% | 69% | +0.16 | +0.18 | +0.53 |
| NIFTY | 2026-10-27 | 362 | 0.76 | 1.1% | 68% | +0.59 | +0.57 | +0.61 |
| NIFTY | 2026-10-28 | 359 | 0.81 | 1.9% | 67% | +0.86 | +0.80 | +0.87 |
| BANKNIFTY | 2026-10-15 | 372 | 0.76 | 5.1% | 64% | +0.70 | +0.65 | +0.78 |
| BANKNIFTY | 2026-10-16 | 361 | 0.71 | 11.4% | 61% | +0.17 | +0.55 | -0.04 |
| BANKNIFTY | 2026-10-19 | 360 | 0.76 | 9.4% | 62% | +0.75 | +0.75 | +0.78 |
| BANKNIFTY | 2026-10-21 | 370 | 0.81 | 3.0% | 65% | -0.56 | +0.89 | -0.62 |
| BANKNIFTY | 2026-10-22 | 373 | 0.78 | 8.8% | 63% | +0.57 | +0.58 | +0.65 |
| BANKNIFTY | 2026-10-23 | 347 | 0.69 | 2.3% | 62% | +0.25 | +0.30 | +0.47 |
| BANKNIFTY | 2026-10-26 | 365 | 0.76 | 3.0% | 64% | +0.55 | +0.58 | +0.52 |
| BANKNIFTY | 2026-10-27 | 362 | 0.65 | 3.0% | 64% | +0.46 | +0.44 | +0.63 |
| BANKNIFTY | 2026-10-28 | 359 | 0.74 | 7.8% | 64% | +0.78 | +0.83 | +0.83 |

### Post hoc (after the run, descriptive): the three signings on the same minutes

Each signing's minute delta against its own file's same-minute price change, the 9 overlap days, minutes with >= 50 s in the minute file and >= 20 s in the 1-second file (r182posthoc.py).

| index | minutes | minute file, tick rule: rho (95% CI), sign agrees | 1-second file, tick rule | 1-second file, quote rule |
|---|---|---|---|---|
| NIFTY | 3,210 | +0.449 (+0.437, +0.462), 68% | +0.266 (+0.241, +0.287), 59% | +0.418 (+0.396, +0.437), 64% |
| BANKNIFTY | 3,212 | +0.476 (+0.438, +0.522), 70% | +0.273 (+0.240, +0.312), 60% | +0.397 (+0.341, +0.456), 66% |
