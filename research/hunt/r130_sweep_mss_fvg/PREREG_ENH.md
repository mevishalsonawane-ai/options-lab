# R130 Amendment 2: can the wick-only setup be improved? (a pre-registered search)

Written 29 Oct 2026, before any number for these versions was computed.

Boss: *"Can we enhance it?"* He chose three improvements: major liquidity only; a morning and trend filter; cheaper to
trade. Then: *"Also check what will happen if we add profit lock."*

**Honest label.** This is a search on an idea that already failed, after its results were seen.
- **The search:** it is run on the design period only (Aug 2021 - 15 Mar 2026) and judged by year (walk-forward). Every
  version counts in BH, including R130's earlier 10.
- **Apr - Oct 2026:** it has been read twice already (V1-V6, W1-W4). The locked versions are checked on it once more,
  labelled **"already seen"**. A pass there would be weak evidence.
- **New data:** the lake ends on 19 Oct 2026, so there is no unseen data today. A forward check needs the lake topped
  up after 19 Oct.
- **CLAUDE.md:** a failed idea stays a note. Nothing goes on paper unless a version passes every gate below.

## 1. The base

W1 from PREREG_WICK.md, the rule as stated, wick-only, 5-minute chart:
- a wick sweep;
- a wick MSS within 12 bars;
- the largest FVG in the leg;
- a pullback into it within 12 bars;
- a wick-engulfing trigger;
- stop 0.02% beyond the FVG;
- target: equal highs / lows, else the next swing;
- out by 15:20;
- one trade at a time per index.

Signal on the index; the option is bought.

## 2. The factors (2 × 2 × 2 × 2 × 2 × 3 = 96 versions)

| factor | levels |
|---|---|
| **L, liquidity swept** | L0: the recent 3-bar swing low (W1). L1: **major liquidity only**: yesterday's low, the opening range low (09:15-09:29, usable from 09:30), or equal lows (two untaken 3-bar swing lows of the last 5 sessions within 0.03%; the level is the lower of the pair). Whichever untaken major low is swept by a wick at least 1 tick below it starts the setup. The MSS level is still the latest 3-bar swing high. |
| **M, time** | M0: entries 09:15-15:00. M1: **morning**, entries 09:15-11:00. |
| **T, trend** | T0: none. T1: **15-minute structure agrees**. On 15-minute wick bars with 2-bar swings, the trend is the direction of the latest wick break of a confirmed 15-minute swing (a high 1 tick above the latest swing high: up; a low 1 tick below the latest swing low: down), last 5 sessions, using only 15-minute bars finished by the trigger. Buys need up, sells need down. |
| **S, minimum stop** | S0: none. S1: the stop distance at entry must be **at least 0.10% of the index**, so charges are a smaller share of each trade (setups with smaller stops are skipped). |
| **K, strike** | K0: ATM. K1: **1 in the money** (CE: ATM - one step; PE: ATM + one step). |
| **P, profit lock** (on the option premium, 1 lot) | P0: none. P1: **Boss's S5 lock**: once open profit reaches +Rs 1,000, exit if it falls back to 60% of the best open profit so far. P2: the same with a +Rs 500 trigger. |

**The lock, point in time:**
- open profit = (price - entry fill) × lot;
- the best so far uses the option's minute **highs** of earlier minutes only;
- the lock fires in a minute whose **low** reaches the lock price, filled at that price (or the minute's open if it
  gapped below), with stop slippage;
- the index stop and target still apply, whichever comes first;
- the lock only ends a trade earlier; the next setup still waits until the trade's plain exit time (the scan is shared
  across P and K).

Everything not listed is W1 unchanged (same option rule: the nearest expiry that does not expire that day; dated
charges; liquidity slippage, also at 2x).

## 3. Twins, p and BH

- **Twins:** 200 random-entry twins per trade. They use the same index, day and side; the version's own entry window
  (09:20-15:00, or 09:20-11:00 for M1); the same stop and target distances; the same strike rule and the same lock.
- **p:** with 200 twins, the empirical p cannot go below 0.005, which no family of 106 can pass under BH. So p is taken
  from the normal approximation of the twins' totals (z = (rule total - twin mean) / twin sd), and the empirical p is
  reported next to it.
- **BH:** q over **106 tests**: these 96 plus R130's V1-V6 and W1-W4, using their recorded twin p values.

## 4. Gates and the selection rule

- **Design gates (all):**
  - ≥ 300 trades;
  - average net > 0 at 1x and at 2x slippage;
  - positive in ≥ 3 of the 4 folds (2023, 2024, 2025, 2026 Q1);
  - BH q ≤ 0.10.
- **Locked for the "already seen" check:** of the versions passing every design gate, the 2 with the highest design
  total. If none passes, the 2 with the highest design total are still checked and reported, but they cannot pass.
- **Apr - Oct 2026 ("already seen") gates:**
  - ≥ 50 trades;
  - average net > 0;
  - **win rate ≥ 65%** (Boss's 65% rule);
  - over design plus that period on 1 lot, drawdown above -Rs 40,000 and no month below -Rs 13,000.
- **Paper arm:** only a version passing all of the above is proposed for paper, labelled as found by a search after
  results. Even then it is a proposal for Boss, not built.
- **Every factor's effect is also reported** (each level averaged over the others, design only), so Boss sees what each
  change does, including the profit lock.

## 5. Outputs

- results: `results/design_enh.json`, `results/seen_enh.json`;
- write-up: a section in `research/HUNT_R130_SWEEP_MSS_FVG.md`;
- entries: `r130-enh-*` (one per factor family plus the 2 locked versions), with the BH family size 106;
- an update to RESULT.md.
