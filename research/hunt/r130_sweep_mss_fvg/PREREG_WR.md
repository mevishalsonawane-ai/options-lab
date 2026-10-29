# R130 Amendment 3: can the win rate reach 65% while still making money? (pre-registered, walk-forward)

Written 29 Oct 2026, before any number for these versions was computed. Boss: *"Enhance it to 57 to 65%, try your
best."*

**Honest label.**
- This is the third search on an idea that failed, after its results were seen.
- Apr - Oct 2026 has been read three times (V1-V6, W1-W4, the 2 locked Amendment 2 versions). It is checked again
  only as "already seen", and a pass there would be weak evidence.
- There is no unseen data (the lake ends 19 Oct).
- **The fair test here is the walk-forward:** each year's version is picked using only the years before it.

**Why these levers.** One look at Amendment 2's design data was taken before writing this, and it is disclosed here.
The best version (15-minute trend, stop ≥ 0.10%, 1-ITM; 201 trades) won 72% of the time on the index but 57% on the
option. Its targets were small (median 0.44 of the stop distance), so 20% of the trades that reached their target still
lost money after charges, spread and decay. So:
- **G, minimum target size:** skip setups whose target is too close to pay for itself;
- **a wider stop:** more trades survive to the target;
- **a stronger trend filter:** fewer counter-moves.

The profit lock is not used: Amendment 2 showed it raises the win rate but loses money.

## 1. The base (fixed)

W1 (wick-only, as stated, 5-minute) with:
- the 3-bar swing liquidity;
- entries all day;
- the **1-ITM** option;
- **no profit lock**;
- the 15-minute trend filter (T1 below, or the stronger T2).

Everything else is Amendment 2 / PREREG_WICK.md unchanged.

## 2. The factors (2 × 4 × 2 × 2 = 32 versions)

| factor | levels |
|---|---|
| S, minimum stop | 0; 0.10% of the index |
| **G, minimum target distance** (target - entry, index) | 0; 0.10%; 0.15%; 0.20% of the index (smaller targets: no trade) |
| **X, stop placement** | at the FVG (0.02% beyond its far edge, as stated); **beyond the sweep's extreme** (0.02% beyond the lowest low of the MSS leg, for buys), a wider stop |
| **T, trend** | T1: the 15-minute structure agrees (Amendment 2). T2: the 15-minute **and** the 60-minute structures agree (the same rule on 60-minute wick bars, 2-bar swings). |

## 3. The walk-forward (the main result)

Four steps.
- **Each step:**
  - train on every design trade before the step's year;
  - pick one version by the rule below;
  - its trades in that year are the out-of-sample result.
- **The steps:** train Aug 2021 - 2022 → 2023; train to 2023 → 2024; train to 2024 → 2025; train to 2025 → 2026
  (to 15 Mar).
- **The pick rule (fixed now):** among the versions with ≥ 50 training trades, a training average net > 0 and a
  training win rate ≥ 65%, take the one with the highest training total.
  - If none qualify: among those with ≥ 50 trades and a positive average net, take the highest win rate.
  - If none of those either: the highest training total.
- **Out-of-sample result:** the four years joined. Trades, win rate (against 65%), average net, total, max drawdown,
  each year.

## 4. Gates (unchanged in spirit; all must hold for a paper proposal)

- **Walk-forward out of sample:**
  - ≥ 150 trades;
  - win rate ≥ 65%;
  - average net > 0 at 1x and 2x slippage;
  - ≥ 3 of 4 years positive.
- **The version picked on all design data (the same rule over all of it):**
  - design BH q ≤ 0.10 over **138 tests** (these 32, Amendment 2's 96, R130's earlier 10);
  - random-entry twins (200 per trade; p by the normal approximation as Amendment 2).
- **"Already seen" Apr - Oct 2026 (read once for that picked version):**
  - ≥ 50 trades;
  - average net > 0;
  - win rate ≥ 65%.
- **Account:** design plus that period on 1 lot, drawdown above -Rs 40,000 and no month below -Rs 13,000.
- **If it passes:** proposed (not built) as a paper arm, labelled "found by a search after results; data already
  seen". Otherwise reported as failing, with how close the win rate got.

## 5. Outputs

- results: `results/design_wr.json` (the 32 versions and the walk-forward), `results/seen_wr.json`;
- write-up: a section in the write-up;
- entries: `r130-wr-*`;
- an update to RESULT.md.
