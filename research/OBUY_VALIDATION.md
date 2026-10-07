## obuy validation: does the option-buying lab reproduce known backtests, and do its statistics behave? (research/obuy)

Written 7 Oct 2026. Code: `research/obuy/` (see its README). Reproduce: `python3 -I research/obuy/run.py validate`
(about 1 minute; writes `<scratchpad>/obuy_cache/runs/validate/validation.json`), plus the two runs below:
`run ... --name singles` and `run ... --name grids`.

### Verdict

**The framework is ready.** It reproduces both trade-level references to the paisa:
- **Liquidity 15+5 baseline:** 1,651 trades, net **Rs +242,420.76**, every trade matched (book / day / entry minute)
  with identical net. Reference: LIQUIDITY_EXITS_3060.md.
- **Solo midday signals:** 623 trades, the same net to the paisa under all five exit sets compared (Jarvis C0 30/60
  + ladder **Rs +34,846.50**, X1, P1, P4, T1). Reference: JARVIS_EXITS.md.

The anchored walk-forward also matches the earlier studies' own numbers: Liquidity 2023-26 Rs +257,203 (DD -29,408),
Solo Rs +21,825.

The hero-zero rule lands on the same 61 trades and 6 wins as the hero replay: Rs +219,202 here against Rs +230,521.
The fills are modelled differently (next open + spread here, a worked limit order there).

The statistics are calibrated on synthetic data:
- Random-baseline p is uniform under the null.
- SPA does not fire on noise and does fire on a planted edge.
- PBO on noise is about 0.45.

On real grids the overfitting controls do their job:

| grid | variants | best in-sample variant | walk-forward | promoted |
|---|---|---|---|---|
| hero | 54 | Rs +420,703 | Rs -78,668 | no |
| Solo | 414 | Rs +87,344 | Rs -158,096 | no |

Of the 1,224 grid variants, 43 beat the random baseline at raw p < 0.05, but none survives Benjamini-Hochberg (smallest
q = 0.068). SPA p = 0.86, and nothing is promoted. A 1,224-variant run takes 15 minutes.

### 1. Parity with earlier backtests (same data, same rules)

| check | obuy | reference | match |
|---|---|---|---|
| Liquidity 15+5, arm exits (A) | 1,651 trades, Rs +242,420.76 | liquidity_exits_3060.py / Kotlin replay: 1,651, Rs +242,420.76 | 1,651 / 1,651 trades identical to the paisa; none missing on either side |
| Liquidity per year | 2021 -9,474 / 2022 -5,309 / 2023 +19,384 / 2024 +44,444 / 2025 +105,870 / 2026 +87,504 | LIQUIDITY_EXITS_3060.md, the same | exact |
| Liquidity exit mix | time stop 24%, failed break / new level 21%, next level 20%, index stop 20%, premium stop 11%, 15:10 3.5% | the same | exact |
| Liquidity walk-forward 2023-26 | Rs +257,203, DD -29,408, PF 1.43 | Rs +257,203, DD -29,408, PF 1.43 | exact |
| Solo midday, C0 (30-pt stop, +60, ladder 60, 15:15) | 623 trades, Rs +34,846.50 | JARVIS_EXITS.md SOLO: 623, Rs +34,846.50 | 623 / 623 identical |
| Solo, X1 (40 / 80 / ladder 80) | Rs +11,287.87 | Rs +11,287.87 | 623 / 623 |
| Solo, P1 (-15% / +30%) | Rs -35,600.58 | Rs -35,600.58 | 623 / 623 |
| Solo, P4 (-20% / +40% + ladder) | Rs -6,203.29 | Rs -6,203.29 | 623 / 623 |
| Solo, T1 (30/60 + ladder + Pine % trail) | Rs +13,545.66 | Rs +13,545.66 | 623 / 623 |
| Solo walk-forward (C0) | Rs +21,825 | JARVIS_EXITS.md: C0 Rs +21,825 | exact |
| Option data layer | 253 / 253 BANKNIFTY 2022 contract-day series | the maxloss/prep export every earlier study used | identical, bar for bar |

These cover:
- Every premium-exit type: % stop, point stop, % and point targets, the profit-lock ladder with the
  breakeven-after-charges floor, and the Pine % trail.
- The structural / index exits: index stop, next-level target, failed break / new level, time stop.
- The app's fills (±5 / 10 bps, exact half-even rounding) and the app's charges (SandboxCosts).
- Lot sizes as of each date, expiry-day skipping, and one position per book.

### 2. Cross-checks with older studies (different data or conventions, so expect to be close, not identical)

| check | obuy | reference | reading |
|---|---|---|---|
| Expiry hero-zero, the app's Hero rule, NIFTY, all expiries (Rs 5k ticket, out 15:05) | 61 trades, 6 wins, **Rs +219,202**; best day 2024-09-12 Rs +201,566; by year 2020 +47,854, 2021 -35,876, 2022 -46,135, 2023 -27,663, 2024 +328,916, 2025 -39,083, 2026 -8,811 | hero_deep replay: 61 trades, 6 wins, Rs +230,521 (realistic fills); best 2024-09-12 Rs +205,601; per year +46,797 / -35,337 / -45,518 / -27,066 / +339,114 / -39,064 / -8,405 | Same trades. Net within 5%: obuy fills at the next open + a 1-4 tick half-spread, where the replay worked a limit order and used Dhan's spot print instead of the index OHLC. |
| 09:20 BANKNIFTY straddle held to 15:10, non-expiry days (0.5 slippage a side, Rs 40 a leg, lot 30) | Feb 2024 - Feb 2025: 209 days, Rs -587 a day, win 27.8%. Feb 2025 - Feb 2026: 238 days, Rs -439 a day, win 31.5% | LONG_VOL.md: Rs -423 / -461 a day, win 26% / 33% | Close. LONG_VOL bought the NEXT expiry on expiry days. That contract is not in Dhan's rolling data, so obuy either skips expiry days or buys today's expiring contract. Including them gives Rs -228,170 vs -105,413 in year A (43 weekly expiries, where the expiring straddle decays to zero). |
| Big-bar pullback, BANKNIFTY Feb 2025 - Feb 2026 (top 20% body, 40% pullback, index stop, 2R, max 2 a day; flat costs) | 301 trades, Rs +19,694, win 41% | COMBINED.md: 285 trades, Rs +7,468, win 42% | Same order. COMBINED exited at the option's close in the index-exit minute, obuy at the next minute's open, and the data source differs. |
| ORB 15-min breakout | no earlier same-rule study | - | Runs end to end. A sanity result: it is WORSE than random entries with the same exits (below). |

### 3. Engine unit tests (synthetic paths): 13 / 13 pass

The tests cover:
- A stop at its trigger (-10 bps), and a gap through the stop filled at the open.
- A resting target, and the stop winning when one bar hits both.
- The ladder locking +R/4 after a +R/2 peak, and a lock using only the peak from BEFORE the bar.
- The time stop at entry + 20 minutes, and the index stop filled at the next minute's open.
- Square-off at the next printed bar, and one position per book.
- Vectorised charges equal to Decimal SandboxCosts on 1,000 random orders.
- +5 bps fills equal to Decimal, including exact half-paisa ties (Rs 10.005 -> 10.00, 30.015 -> 30.02).

### 4. Cost model

Round trip, 75 units, Rs 100 -> 110 and Rs 400 -> 380:

| date | app (today's rates) | dated (rates in force) |
|---|---|---|
| Jun 2022 | Rs 66.42 / 115.44 | Rs 61.51 / 99.35 |
| Jun 2023 | 66.42 / 115.44 | 61.89 / 100.50 |
| Dec 2024 | 66.42 / 115.44 | 62.30 / 101.19 |
| Jun 2026 | 66.42 / 115.44 | 66.42 / 115.44 |

The 'dated' model follows the STT steps (0.05% -> 0.0625% Apr 2023 -> 0.1% Oct 2024 -> 0.15% Apr 2026) and the
Oct 2024 exchange-fee change. Its pre-Oct-2024 exchange rates are approximate. The validation runs use the app model
(today's rates), which is the conservative choice for older years.

### 5. Statistics calibration (synthetic)

| check | result | expected |
|---|---|---|
| Random-baseline p, 200 null strategies | 3.5% below 0.05, mean 0.50 | ~5%, 0.5 |
| White RC / Hansen SPA, 300 pure-noise variants | p = 0.21 / 0.37 | not significant |
| SPA with one planted edge (+0.15 sd a day) | p = 0.00, picks the right variant | significant |
| PBO (CSCV, 16 blocks), 300 noise variants | 0.45 | ~0.5 |
| Deflated Sharpe: best of 300 noise variants / the true edge | 0.57 / 0.999 | not significant / significant |
| BH on 1,000 uniform p's / with 10 real signals | 0 / 12 rejections (Holm 7) | ~0 / ~10 |

The random-baseline null widens its spread by sqrt(K / (K - 1)), because it draws one of K alternatives per trade.
Small pools on big grids therefore do not make p-values too small.

### 6. The single strategies, run through the full pipeline

Run `singles`: 7 strategies, 7 variants, 1,529 trading days (Aug 2020 - Oct 2026). Walk-forward = test years after
the first two calendar years. All figures are 1 lot, after costs.

| strategy | WF trades | WF net | per year | PF | max DD | years + | p vs random (raw / Holm) | random mean / trade vs real | MC P(profit 1y) | DSR | promoted |
|---|---|---|---|---|---|---|---|---|---|---|---|
| liquidity15_5 | 1,397 | +257,203 | +68,661 | 1.43 | -29,408 | 4/4 | 0.000 / 0.003 | -79 vs +184 | 98% | 0.93 | yes* |
| solo_midday (C0) | 564 | +21,825 | +4,606 | 1.09 | -43,618 | 3/5 | 0.007 / 0.042 | -87 vs +39 | 61% | 0.31 | yes* |
| hero_zero (NIFTY) | 45 | +207,224 | +44,000 | 2.20 | -83,510 | 1/5 | 0.485 / 1.000 | +4,714 vs +4,605 | 39% | 0.24 | no |
| bigbar_pullback (4 indices) | 4,182 | -364,003 | -76,762 | 0.83 | -369,510 | 1/5 | 0.447 / 1.000 | -89 vs -87 | 3% | 0.00 | no |
| orb15 (4 indices) | 3,275 | -717,286 | -151,264 | 0.80 | -754,666 | 0/5 | 0.997 / 1.000 | -118 vs -219 | 1% | 0.00 | no |
| straddle920 (4 indices) | 3,401 | -1,359,030 | -286,355 | 0.65 | -1,382,240 | 0/5 | 1.000 / 1.000 | -307 vs -399 | 0% | 0.00 | no |
| random_entry (control) | 2,754 | -329,019 | -69,385 | 0.75 | -343,947 | 0/5 | 0.767 / 1.000 | -104 vs -120 | 1% | 0.00 | no |

\* These two pass the gates mechanically, but they are NOT clean discoveries. Their rules were chosen on this same
data:
- Liquidity's exits were tuned on Feb 2024 - Feb 2026. Before that window it made only Rs +3,156 on 609 trades.
- Solo's C0 exit was picked after the fact in JARVIS_EXITS.md, and C0 is the best of 12 exits on SOLO.

Single-variant strategies have no grid for the walk-forward to choose from. For these two, the walk-forward is simply
the fixed rule out of the first two years, so they are validation targets, not promotions. Catalog strategies will
run with declared grids, where the walk-forward does choose.

What the run shows:
- **The hero ticket's profit is luck, not edge.** Random tickets on the same days with the same exits average MORE
  per trade (Rs +4,714 vs +4,605). The profit is one day's jackpot, 2024-09-12, which random entries also catch.
- **The random-entry control behaves like a control.** p = 0.77 and it fails every gate.
- **ORB loses more than random entries** (p = 0.997). Buying the breakout pays for the move it has already made.
- **The straddle times all lose.** Random times (the baseline) are no better.

The Monte Carlo at 1% / 2% risk shows near-zero drawdowns for strategies without a premium stop. With the full
premium at risk, a 1% risk on Rs 5 lakh rarely affords one lot, so most of those trades are skipped. Read the 1-lot
columns for those strategies.

### 7. Grids (1,224 variants): the overfitting controls at work

Run `grids`, `--pool 10`, families:
- orb_grid: 384 variants.
- bigbar_grid: 192.
- solo_midday_grid: 414.
- straddle_grid: 180.
- hero_grid: 54.

| family | variants | best in-sample variant | WF (picked on past years only) | years + | p vs random (raw / Holm) | PBO | promoted |
|---|---|---|---|---|---|---|---|
| hero_grid | 54 | Rs +420,703 (PF 1.96) | Rs -78,668 | 1/5 | 0.767 / 1.000 | 16% | no |
| solo_midday_grid | 414 | Rs +87,344 | Rs -158,096 | 0/5 | 0.974 / 1.000 | 44% | no |
| straddle_grid | 180 | Rs -173,076 | Rs -217,129 | 0/5 | 0.013 / 0.067 | 15% | no |
| bigbar_grid | 192 | Rs -268,902 | Rs -434,256 | 0/5 | 0.983 / 1.000 | 21% | no |
| orb_grid | 384 | Rs -231,205 | Rs -487,638 | 0/5 | 0.996 / 1.000 | 52% | no |

- Over all 1,224 variants: White's Reality Check p = 0.50 and Hansen SPA p = 0.86. No variant beats not trading
  after costs.
- 162 variants made money in full sample, and 43 of them beat their random baseline at raw p < 0.05. After
  Benjamini-Hochberg over all 1,224 variants the smallest q is 0.068, so none survives. These are exactly the false
  discoveries the corrections exist to stop.
- The hero grid shows the failure mode most clearly. Its best variant made Rs +420,703 in sample. The walk-forward
  chose a different "best" each year and lost Rs 78,668 out of sample.

### 8. Run times (4 cores, 15 GB, Python / numpy, no numba)

| step | time |
|---|---|
| Loading one underlying's option year (≈ 3.9 M rows, both sides) | 5-10 s (all NIFTY weekly years ≈ 55 s) |
| Data pass (one pass for all jobs): 7 single strategies, 26,000 candidates + 20 random entries each (capped) | 79-117 s |
| Data pass: 119 packs (5 grids, ≈ 600,000 candidates + 400,000 random alternatives) | 348 s, de-duplicated path store 418 MB |
| Exits | ≈ 0.07-0.16 s per variant per 1,650 trades; 1,224 variants in 175 s |
| Random baselines, SPA (1,000 stationary-bootstrap draws), PBO, DSR, walk-forward | 10-90 s per run |
| Full runs | singles: 90 s; grids (1,224 variants): 900 s, of which signals 330 s (the hero signal scans chains) |

A catalog of about 40 strategies x 50-100 variants each is therefore 1-2 hours end to end. Peak memory is about 9 GB
for a 1,224-variant run.

### 9. Limits (what the lab cannot tell you)

- **No bid/ask.** Fills are modelled: the app's ±5 bps, or `liq` with a 1-4 tick half-spread. Real fills on Rs 1-5
  options are probably worse, which matters most for hero tickets.
- **No next-week weekly contracts** (Dhan's rolling data holds the nearest expiry only). On expiry days a strategy can
  only skip the day or trade today's expiry. Monthly contracts are available (`series="month"`).
- **ATM±10 strikes only.** Far-OTM tickets on BANKNIFTY / SENSEX are sometimes unavailable.
- **Futures history is not available** (only the contracts live at the fetch date).
- **Single-exit positions only.** No partial exits or scaling in.
