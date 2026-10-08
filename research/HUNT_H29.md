# HUNT h29: can option PRICES tell a buyer when options are cheap and which side to buy?

Written 8 Oct 2026. Option BUYING only, 1 lot, Rs 1 lakh. Data: Dhan nearest-expiry ATM±10 option minutes, index
minutes, NIFTY / BANKNIFTY / FINNIFTY / SENSEX / MIDCPNIFTY, Aug 2020 to Oct 2026.
- Plan, written before any P&L: `research/hunt/h29/PREREG.md`.
- Code: `research/hunt/h29/` (`build.py`, `features.py`, `describe.py`, `rules.py`, `liqfilter.py`, `strikes.py`).
- Logs: `scratchpad/hunt/h29/*.log`.

## Verdict

**NO. Option prices do not tell a buyer which side to buy. "Cheap" options do not pay a buyer either.**

1. **Directional buys from skew or synthetic-forward moves lose money. All 24 variants lose, gross and net.**
   - Best: -Rs 50/day gross and -Rs 76/day net at 1 lot.
   - The main "calls bid vs puts" signal lost -Rs 1,437 to -1,594/day net.
   - Random entries with the same exits lost LESS. p vs random was 0.9-1.0.
2. **The synthetic forward does "lead" the index, but the buyer cannot use it.**
   - When the option-implied forward jumps vs spot, the INDEX follows over the next 5-30 minutes. The IC is +5 to +13%,
     with the same sign in all 5 indices, before the holdout and in it.
   - But that is the index catching up to the options. Options have already moved. After the signal the forward itself
     slips back by 0.1-4 bps.
   - So a buyer pays for a move that is already in the price.
3. **Buying only when IV is cheap vs a HAR-RV forecast (16 variants) loses too.**
   - Best: +Rs 5/day gross and -Rs 30/day net.
   - It beat random at raw p = 0.02, but BH q = 0.80.
   - On every IV-cheapness fifth, the index moved less by 15:10 than the IV priced in (0.62-0.73× before the holdout).
     IV here is in trading time, so it also carries overnight risk. That flatters this ratio somewhat.
   - Cheap days are less overpriced, not underpriced. In the holdout even that ordering disappeared.
4. **As a filter on Liquidity 15+5: nothing passes.** 0 of 12 pre-registered filters were adopted.
   - The best was "IV low vs today's realised vol": Rs 154/trade against 31 unfiltered. Raw p = 0.032, but BH q = 0.39.
   - It also cuts the number of trades, so net per day does not rise.
5. **Which strike: picking the strike with the lowest IV on the smile gives a small, not significant gain.**
   - It earned +0.21 points of net return on premium (1.49% against 1.28% for 1-ITM). p = 0.12, q = 0.24. Not adopted.
   - "Buy OTM when IV is cheap" was worse than always buying 1-ITM.
6. **Multiple testing.**
   - Hansen SPA p = 1.00 and White RC p = 1.00 over the 40 standalone variants.
   - Walk-forward: -Rs 1.53 L (test 1) and -Rs 1.44 L (test 2a), with 0 of 4 test years positive.
   - **Nothing passed the gates, so the locked holdout was not used to test any rule.** I looked at it afterwards for
     descriptive numbers only (marked post-hoc below).
7. **Rs 5,000/day at Rs 1 lakh: NO.** h29 adds nothing to the plan.
   - Liquidity 15+5 alone (1 lot per index, 5 indices, holdout, real spread) is Rs 331/day net and Rs 1,011/day gross.
   - That needs about 15 lots per index for Rs 5,000/day. It does not fit Rs 1 lakh (see h16).

**Honest variant count: 55**, all pre-registered and all run: 24 (test 1) + 16 (test 2a) + 12 (test 2b) + 3 (test 3).
The descriptive checks D1-D3 and one diagnostic choose nothing.

## What was built (no P&L)

Every minute, per index (5,718 index-days):
- **Synthetic forward:** F = median over strikes within 2 steps of spot of (K + C - P). Basis = F/S - 1.
- **ATM IV:** Black-76 implied vol from the ATM straddle, in trading time.
- **Skew (risk reversal, rr):** IV of the call at about F + straddle/2 minus IV of the put at about F - straddle/2.
  That is about 30-35 delta.
- **Term structure:** monthly minus weekly ATM IV.
- **Changes as z-scores:** dbz is the 5-minute basis change; drz is the 15-minute rr change.
- **Realised vol forecast:** pooled log-HAR, refit each year on earlier days only.
- **Cheapness:** cheap1 = IV / HAR forecast; cheap2 = IV / today's realised vol so far.

Median IV/HAR is 1.2-1.36, so options usually price more movement than the HAR forecast expects.

### D1: IV through the day (median, relative to 09:20, non-expiry days)

| | 10:00 | 11:00 | 12:00 | 13:00 | 14:00 | 15:00 |
|---|---|---|---|---|---|---|
| NIFTY, pre-holdout | 0.991 | 0.995 | 1.009 | 1.025 | 1.031 | 1.036 |
| BANKNIFTY, pre-holdout | 0.990 | 0.994 | 1.005 | 1.014 | 1.022 | 1.028 |
| MIDCP, pre-holdout | 0.987 | 0.982 | 0.985 | 0.995 | 0.998 | 1.001 |
| NIFTY, holdout | 1.005 | 1.008 | 1.021 | 1.036 | 1.058 | 1.071 |

- There is a small IV dip of 1% by 10:00-11:00, then a drift up. Part of the drift is arithmetic: the overnight's
  share of the remaining time grows through the day.
- There is no lunch "crush" big enough to time entries around. The early dip is about 1 vol percentage point × 1%.

### D2: is the move priced cheaply?

The table compares the realised index move to 15:10 with the move the ATM IV implies. All indices and check times
(10:00, 11:30, 13:00) are averaged.

| cheap1 fifth | real/implied, pre | range/implied, pre | real/implied, holdout | range/implied, holdout |
|---|---|---|---|---|
| 1 (cheapest) | 0.73 | 1.34 | 0.66 | 1.23 |
| 2 | 0.72 | 1.29 | 0.72 | 1.27 |
| 3 | 0.72 | 1.30 | 0.65 | 1.20 |
| 4 | 0.70 | 1.27 | 0.70 | 1.22 |
| 5 (dearest) | 0.62 | 1.19 | 0.67 | 1.15 |

- Even on the cheapest days, the move to the close is about 0.7× what is priced in. Part of that gap is the overnight
  risk inside the trading-time IV.
- The ordering ("cheap is less overpriced") held before the holdout and vanished in it.
- Term structure (monthly minus weekly IV) did not sort the realised move at all.

### D3: does anything in the prices predict the next index move? (Spearman IC × 100)

| feature, next 5 min | BN | FIN | MIDCP | NIFTY | SENSEX |
|---|---|---|---|---|---|
| dbz (forward jumped vs spot), pre | 7.5 | 5.1 | 4.8 | 5.1 | 10.1 |
| dbz, holdout (post-hoc) | 7.3 | 2.5 | 2.8 | 8.7 | 13.5 |
| drz (skew shift to calls), pre | -1.3 | 0.0 | -1.2 | 0.0 | -0.7 |
| rr level, next 30 min, pre | -2.8 | -1.9 | -2.2 | -4.0 | -3.9 |

- The forward-vs-spot signal is real and stable for the INDEX. Controlling for the last 5-minute index move hardly
  changes it.
- Skew shifts carry nothing. A rich call skew is mildly contrarian (rr level, negative IC).

**Why the forward lead does not help a buyer** (`lead_check.log`): average bps in the signal's direction after a
basis jump (dbz ≥ 2), before the holdout.

| | index, last 5 min | forward, last 5 min | index, next 15 min | forward, next 15 min |
|---|---|---|---|---|
| NIFTY | -1.9 | +2.9 | +0.8 | -0.8 |
| BANKNIFTY | -3.2 | +2.6 | +1.5 | -0.6 |
| FINNIFTY | -3.2 | +9.2 | +0.5 | -4.7 |
| MIDCP | +3.7 | +12.8 | +1.1 | -2.0 |

The options moved first. The index catches up by 1-1.5 bps. The forward, which is what an option is priced on, gives
part of it back. The buyer gets nothing.

## Test 1: directional buys from skew / forward shifts (obuy Lab, 1-ITM nearest, expiry days skipped)

Pre-holdout (Aug 2020 to Sep 2025, 1,269 days). 1 lot. App fills and charges plus the real half-spread (h24).

| signal (k) | trades | gross Rs/trade | net Rs/trade | net Rs/day | random, same exits, Rs/trade | p vs random |
|---|---|---|---|---|---|---|
| basis (2), exits E1-E4 | 7.9-9.0k | -105 to -133 | -207 to -236 | -1,437 to -1,594 | -112 to -150 | 1.00 |
| basis (3) | 3.6-4.0k | -149 to -173 | -254 to -278 | -755 to -874 | -107 to -167 | 1.00 |
| skew (2) | 1.4-1.6k | -72 to -225 | -206 to -357 | -247 to -438 | -185 to -278 | 0.7-1.0 |
| skew (3) | 660-700 | -204 to -373 | -341 to -507 | -186 to -280 | -178 to -305 | 0.97-1.0 |
| both (2) | 2.6-2.9k | -113 to -184 | -227 to -297 | -494 to -599 | -131 to -188 | 1.00 |
| both (3), best = E2 | 261-278 | -228 to -449 | -347 to -569 | **-76** to -117 | -187 to -264 | 0.9-1.0 |

The exits were E1 (Liquidity arm), E2 (-15% / +30%), E3 (profit-lock ladder) and E4 (30-minute exit). None of the
24 variants was positive in any form.

## Test 2a: buy only when IV is cheap (IV/HAR rank in the lowest 10% / 20% of the prior 60 days), at 10:00 / 11:30 / 13:00

| q, direction | trades | gross Rs/day | net Rs/day | best exit | p vs random (BH) |
|---|---|---|---|---|---|
| 10%, last-30-min move | 380-444 | +4.6 (E2) to -34 | **-30 (E2)** to -67 | E2 | 0.02 (0.80) |
| 10%, move since open | 377-444 | -21 to -66 | -54 to -101 | E3 | 0.5-1.0 |
| 20%, last-30-min move | 1.1-1.3k | -56 to -118 | -151 to -212 | E2 | 0.8-1.0 |
| 20%, move since open | 1.1-1.3k | -38 to -113 | -131 to -215 | E2 | 0.5-1.0 |

- Walk-forward (anchored, yearly): -Rs 1.44 L, with 0 of 4 years positive. Test 1's walk-forward: -Rs 1.53 L, 0 of 4.
- SPA and RC p = 1.00. No variant reached the holdout.

## Test 2b: pricing features as filters on Liquidity 15+5 (h4's real trades, 5 indices, 1 lot, real spread)

Pre-holdout. Unfiltered: Rs 31/trade and Rs 101/day net (Rs 133/trade gross). The halves were -73/trade (2021-23) and
+106/trade (2024 to Sep 2025).

| filter | trades | net Rs/trade | net Rs/day | both halves up? | raw p | BH q |
|---|---|---|---|---|---|---|
| cheap1 (IV/HAR): keep cheapest third | 916 | 22 | 23 | no | 0.59 | 0.93 |
| cheap1: drop dearest third | 1,829 | 21 | 44 | no | 0.67 | 0.93 |
| **cheap2 (IV/today's RV): keep cheapest third** | 562 | **154** | 100 | **yes** | **0.032** | 0.39 |
| cheap2: drop dearest third | 1,120 | 39 | 50 | no | 0.42 | 0.93 |
| IV level: keep lowest third | 925 | 63 | 68 | yes | 0.25 | 0.93 |
| IV level: drop highest third | 1,848 | 59 | 127 | yes | 0.11 | 0.68 |
| forward moved our way (dbz): keep top third | 742 | -9 | -8 | no | 0.78 | 0.93 |
| dbz: drop bottom third | 1,483 | 4 | 7 | no | 0.82 | 0.93 |
| skew shifted our way (drz): keep top third | 580 | 10 | 7 | no | 0.61 | 0.93 |
| drz: drop bottom third | 1,158 | -20 | -26 | no | 0.91 | 0.93 |
| skew rich on our side (rr): keep top third | 882 | -39 | -40 | no | 0.93 | 0.93 |
| rr: drop bottom third | 1,763 | 36 | 74 | no | 0.43 | 0.93 |

- **Adopted: none.** Holdout baseline: Rs 79/trade and Rs 331/day net (Rs 242/trade and Rs 1,011/day gross; Rs 35/trade
  at 1.5× spread).
- **Post-hoc only** (the holdout was looked at, nothing chosen from it): "IV low vs today's RV" made Rs 370/trade on
  103 trades. Net/day fell to Rs 172 from 331.
  - It is the one lead worth logging on paper: the app could record IV / intraday RV at each signal.
  - It is not a rule: it failed BH before the holdout, and it lowers Rs/day.
- Agreeing with option traders' direction (dbz, drz) made Liquidity trades WORSE, not better.

## Test 3: which strike at the Liquidity signal (BN, FIN, MIDCP; arm exits; 1,629 pre-holdout trades)

| rule | net return on premium, 2021-23 | 2024 to Sep 2025 | all pre | net Rs/day at 1 lot | Rs/day at a Rs 33k ticket |
|---|---|---|---|---|---|
| R0: 1-ITM always (incumbent) | -0.10% | 2.16% | 1.28% | 208 | 952 |
| R1: 1-OTM when IV/HAR cheap, else 1-ITM | -0.42% | 2.18% | 1.17% | 183 | 845 |
| R2: lowest-IV strike of 1-ITM / ATM / 1-OTM | -0.09% | 2.51% | 1.49% | 245 | 1,067 |

- Paired bootstrap: R2 vs R0 p = 0.12 (BH q 0.24); R1 p = 0.83. **Not adopted.** Keep 1-ITM (as h13 / h16).
- By IV-cheapness third, OTM never had a consistent edge per rupee. The middle third was best for every strike. This
  is noise, not convexity.

## Rs/day, size and risk

- **No h29 rule is tradable.** The best standalone variant loses Rs 30/day net at 1 lot. Scaling it up loses more.
- For reference, the incumbent Liquidity 15+5 at 1 lot per index (5 indices, holdout, real spread):
  - Rs 331/day net and Rs 1,011/day gross. At 1.5× spread, about Rs 140/day net.
  - Rs 5,000/day would need about 15 lots per index. That is far beyond Rs 1 lakh.
  - Drawdown, ruin and capacity at Rs 1 lakh are as h16 / h24 report: about Rs 1,100/day at κ 0.02 with a 60%
    drawdown, and BANKNIFTY alone at real spreads.
- **Rs 5,000/day realistically at Rs 1 lakh: NO.**

## Caveats

- **IV method.** IV is Black-76 on the synthetic forward, r = 0, in trading time. Strikes beyond ATM±10 are missing,
  and the ±strad/2 wings sometimes fall back to the nearest strike. rr is noisy: drz has fat tails.
- **Term structure** is only available while weeklies and monthlies coexist (NIFTY, SENSEX, and BN/FIN before Nov 2024).
- **HAR** uses an in-sample warm-up fit for 2020-22. Each year after that is fit only on earlier years.
- **Spread** is h24's one-day snapshot (BN / NIFTY / SENSEX 0.16%, MIDCP 0.21%, FIN 0.42%), applied to every year. It is
  applied on top of the app fill minus its 5 bps. The SENSEX value is assumed to be NIFTY's.
- **Test 3** keeps the incumbent's trade set and buys the other strike on the same signals.
- **Disk.** The caches in `scratchpad/hunt/h29/cache/h29` (feat_*.parquet, 124 MB) can be deleted. `build.py` and
  `features.py` rebuild them in about 15 minutes.
