# HUNT h13: which option should Liquidity 15+5 buy? (strike, expiry, stop scaling) under h10's capacity model

Code: `research/hunt/h13/`
- `PREREG.md`: the grid, model, windows and choice rule, written before any h13 P&L.
- `build.py`: one data pass that builds 8 contract choices × (real trades + 5 random-entry alternatives per signal), then
  the Δ/premium (elasticity) pass for the scaled stops.
- `run.py pre` (choice made on data before 1 Oct 2025) and `run.py hold` (run once).

Logs and JSON are in `scratchpad/hunt/h13/`. The locked choice is in `choice_locked.json`.

Everything here is option BUYING. Entries, exits, index logic and books are the app's Liquidity 15+5 arm, unchanged
(h4/h7 port). Only the option bought changes. At 1 lot with no impact, the incumbent reproduces h10 to the rupee: BN
144,334 / FIN 91,911 / MIDCP 102,311. At the plan size (BN 26 / FIN 3 / MIDCP 11, κ 0.02) it reproduces h10's Rs
4,928/day for Dec 2024 to Sep 2025.

## Verdict (plain language)

**Changing the option does not raise the realistic Rs/day. Keep buying the 1-ITM nearest-expiry option, as the app does
now.**

1. **The pre-registered rule picked ATM (with the scaled stop).** At κ = 0.02, ATM options trade about 30% more
   volume, so BANKNIFTY capacity rises from 287 to 373 lots. On Dec 2024 to Sep 2025 that gave a bigger "max realistic"
   number than 1-ITM: **Rs 35.8k/day against 22.9k**.
   - The advantage was fragile even before the holdout. At κ = 0.04 the ATM peak was only Rs 1.1k/day, against 2.2k for
     1-ITM.
   - Its Sharpe at the Rs 5,000/day size was lower: 0.99 against 1.17.
2. **The holdout (run once) did not confirm ATM.**

   | | ATM (choice) | 1-ITM (incumbent) |
   |---|---|---|
   | net/day at the Rs 5,000 size, no limits | Rs 4,764 | Rs 4,830 |
   | net/day at the Rs 5,000 size, h10's limits | Rs 4,171 | **Rs 6,075** |
   | max drawdown | 16.9 L | 13.2 L |
   | net/day at full capacity | **Rs 11.9k** (373 BN lots) | **Rs 19.9k** (287 BN lots) |
   | at κ = 0.04 | loses | loses |

   Both lose at κ = 0.04 (ATM -2.7k/day, 1-ITM -1.1k/day at the 5k size). Post hoc, at equal lots in the holdout
   1-ITM was the best of all 16 variants: Rs 4,830/day, against ATM 3,870, 2-ITM 3,304 and 1-OTM 1,894.
3. **Expiry choice: when weeklies existed, the nearest weekly was far better than the monthly.**
   - At the reference size, net/day on Oct 2021 to Nov 2024 was 1,918-2,813 for the near variants and -158 to 881 for
     the monthly ones.
   - The monthly's 5-minute volume was about 1% of the weekly's: BN capacity 25 lots against 3,264.
   - So "longer-dated" was strictly worse.
   - Since Nov 2024, BN, FIN and MIDCP have only monthlies, and next-month contracts are not in the data. **The
     next-month question cannot be tested for the current market.** Judging by the weekly era, the second contract
     would be thinner still.
4. **Scaling the stop to the same index move changes almost nothing.** Measured Δ/premium ratios are 0.92-1.12, so the
   stop moves from 15% to roughly 14-17%. Results differ by noise only.
5. **2-ITM has the least capacity** (BN 160 lots, FIN 2, MIDCP 6) and the highest cost per rupee of gross.
6. **The edge itself is still real.** Every variant beats random entries with the same exits (p = 0.0005, BH q =
   0.0005, both before and in the holdout). But the strike/expiry choice adds nothing significant:
   - SPA over the 16 variants: p = 0.10 for Dec 2024 to Sep 2025, and p = 0.077 for all pre-holdout data (White RC
     0.049);
   - the walk-forward pick (ATM, ATM, 2-ITM) and the incumbent made about the same.

**Rs 5,000/day realistically? Borderline. Not improved by option choice.**
- It needs the same h10 plan: 1-ITM monthly, about BN 26 / FIN 3 / MIDCP 11 lots.
- In the holdout it made **Rs 4,830/day net (Rs 13,933 gross)**, and Rs 6,075 net with h10's limits.
- That holds only if impact is around κ ≈ 0.02. At κ = 0.04 it loses.

## Grid and choice rule (PREREG.md; never changed)

The grid has 16 variants:
- strike: 1-OTM / ATM / 1-ITM / 2-ITM;
- expiry: `near` (weekly when one exists, else monthly) / `month`;
- stop: `fixed` (-15% premium stop, +5% time-stop gain) / `scaled` (the same index move as the 1-ITM near option:
  stop = 0.15·λ_k/λ_ref, clipped to 5-60%, with the time gain at stop/3).

The model is h10's, unchanged:
- participation cap 15% of the 5-minute volume, with exits worked over later minutes;
- 'real' fills plus square-root impact κ·√(q/v5), κ = 0.02 (0.01 and 0.04 reported);
- charges per order slice.

The choice rule:
1. A variant is eligible if its net is positive in both eras and its random-entry BH q is below 0.05.
2. Among eligible variants, pick the highest "max realistic Rs/day" on Dec 2024 to Sep 2025: FIN and MIDCP at
   capacity, BANKNIFTY scanned up to capacity, κ = 0.02.
3. Keep the incumbent if it is within 15% of the best.

Result: 15 of the 16 variants were eligible. ATM/near/scaled scored 35,773 against the incumbent's 22,932, so the
**choice was ATM/near/scaled**.

## Pre-holdout table (κ 0.02, cap 15%)

- "Ref" = BN 26 / FIN 3 / MIDCP 11 lots.
- Era W = Oct 2021 to Nov 2024; era M = Dec 2024 to Sep 2025.
- Rs/day net, gross in brackets.

| variant | ref W | ref M | capacity M (BN/FIN/MID) | max realistic M, κ .02 | κ .04 | κ .01 |
|---|---|---|---|---|---|---|
| 1-OTM near fixed | 1,918 (2,677) | 4,809 (10,625) | 244/3/10 | 31,442 | 2,227 | 50,681 |
| 1-OTM near scaled | 1,731 | 5,354 (11,163) | 244/3/10 | 32,926 | 2,851 | 52,153 |
| ATM near fixed | 2,279 (3,125) | 4,279 (10,418) | 366/5/16 | 35,376 | 930 | 69,715 |
| **ATM near scaled (choice)** | 1,954 | 4,452 (10,591) | 373/5/16 | **35,773** | 1,058 | 70,841 |
| **1-ITM near (incumbent)** | 2,504 (3,496) | 4,928 (11,676) | 287/3/11 | 22,932 | 2,241 | 49,642 |
| 2-ITM near fixed | 2,813 (4,042) | 4,775 (12,456) | 160/2/6 | 17,184 | 1,664 | 32,256 |
| 2-ITM near scaled | 2,752 | 4,595 | 160/2/6 | 17,099 | 1,565 | 32,181 |
| month variants (8) | -158 .. 881 (gross 2.4-4.1k) | = near (same contract) | | = near | | |

The max-realistic numbers need huge size:
- **ATM at 373/5/16 lots ties up Rs 1.9 crore of premium at p95 (Rs 2.7 crore max) and has a Rs 68 lakh drawdown.**
- 1-ITM at 287/3/11 ties up Rs 1.6 crore and has a Rs 49 lakh drawdown.

Per-year net/day at the reference size, incumbent:

| 2021 (Oct-Dec) | 2022 | 2023 | 2024 | 2025 (Jan-Sep) |
|---|---|---|---|---|
| -3,508 | -55 | 1,683 | 7,580 | 4,917 |

ATM: -3,093 / 162 / 1,190 / 5,964 / 4,458.

## Rs 5,000/day plans (sized on Dec 2024 to Sep 2025) and the holdout

| | ATM/near/scaled: BN 33 / FIN 5 / MIDCP 16 | 1-ITM (h10 plan): BN 26 / FIN 3 / MIDCP 11 |
|---|---|---|
| pre (Dec 2024 to Sep 2025) net / gross per day | 5,031 / 13,700 | 4,928 / 11,676 |
| pre max DD, worst month, P(losing month) | -6.7 L, -3.5 L, 42% | -5.3 L, -2.9 L, 39% |
| premium tied up, p95 / max | 18 L / 31 L | 15 L / 24 L |
| **HOLDOUT net / gross per day, no limits** | **4,764 / 16,061** | **4,830 / 13,933** |
| HOLDOUT with limits (-75k/day, -2.5L/month) | 4,171 | 6,075 |
| HOLDOUT κ 0.01 / κ 0.04 (no limits) | 8,544 / -2,681 | 7,810 / -1,131 |
| HOLDOUT worst day / worst month / max DD | -2.3 L / -7.7 L / -16.9 L | -2.1 L / -6.6 L / -13.2 L |
| HOLDOUT losing months, P(losing month) | 6/13, 46% | 6/13, 45% |
| HOLDOUT by index, net (gross) | BN +11.6 L (27.3 L), FIN -0.9 L (0.8 L), MIDCP +1.2 L (11.9 L) | BN +10.7 L (23.6 L), FIN -0.5 L (0.9 L), MIDCP +1.8 L (10.2 L) |
| HOLDOUT random entries (same exits, model, lots) | +1,831/trade against -4,718; p = 0.0005 | +1,850 against -2,872; p = 0.0005 |
| HOLDOUT capacity (BN/FIN/MID lots) | 156 / 1 / 5 | 129 / 1 / 4 |
| HOLDOUT net/day by BN lots (κ .02): 50 / 100 / 200 / 287 | 6.1k / 9.9k / 18.6k / 18.0k | 7.4k / 12.0k / 17.8k / 19.9k |

- The best 5 days made 2.0-2.2× the total holdout net.
- Capital at the Rs 5,000 size: premium about Rs 15-18 lakh plus a drawdown of Rs 13-17 lakh, so **about Rs 35-40
  lakh**. Option buying needs no margin beyond the premium.

## Honesty notes

- 16 variants, all counted. The choice was made only on data before 2025-10-01 and the holdout ran once.
  - The first holdout call crashed while printing (a JSON key type) before any output, then ran after the fix.
  - Reporting of capital at the max size was added in the same fix, and it is reporting only.
- The h10 incumbent's numbers were known before PREREG.md was written. The choice rule, which is capacity-driven, was
  fixed blind to every other variant.
- The main pre-holdout window is short: 10 months, about 520 trades. The "max realistic" metric extrapolates to sizes
  of Rs 1.5-2 crore of premium, where κ is unknown. The κ = 0.04 column was already pointing the other way, and the
  holdout agreed with it.
- Δ is a finite difference over neighbouring strikes, missing on 3-9% of trades; those trades use the pre-holdout
  median ratio. The data has no next-week weekly or next-month contract.
- The month variants in era W have about 10% fewer trades, because of gaps in the monthly ATM±10 data.
- The caveats inherited from h10 apply: gross is computed at bar prints, and exchange freeze limits are ignored.

## The rule (unchanged from h10)

1. Trade Liquidity 15+5 as the app does and **buy the 1-ITM option of the nearest expiry**: the current-month contract
   for BN, FIN and MIDCP today. Keep the -15% premium stop.
2. Size: BN 26 / FIN 1-3 / MIDCP 4-11 lots.
3. Never put more than 15% of the last 5 minutes' volume in one order.

Do not switch to ATM, OTM, 2-ITM or the monthly contract for capacity.
