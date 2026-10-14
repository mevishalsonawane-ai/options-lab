# R27 pre-registration: Night (R3) smart exit - hold past 09:16 for +20/+30 points, or sell at 09:16 as now?

Written 14 Oct 2026, AFTER step 1 (the rebuild) and step 2 (descriptive, DESC block only), and BEFORE any variant's
outcome in the walk-forward test years or the holdout was computed. Code: `research/hunt/r27/` (`r27cfg.py`,
`r27data.py`, `r27lib.py`, `study.py`, `test_r27.py`). Nothing in the app changes in R27 (research only).

## Question (Boss, 14 Oct)

"For the Night strategy: if the agents think the market will go up after some time, or increase by 20-30 points, keep
the trade and wait for the profit, then sell it." At 09:16 R3 always sells. Does a rule "hold when the morning signs
point our way, sell on a +20 / +30 point gain, else at a stop or a time cap" beat the fixed 09:16 sale (B0) after costs?

Prior evidence, stated before the test:
- HUNT_R12: on profitable strategies every early-exit or trail manager tested made them worse.
- R19c: Boss's +Rs 1,000 / Rs 500 floor failed on every arm.
- h31: 10:15 and point exits (P15-P30, premium points, symmetric stop, 10:15 cap) were tried on R17 nights. None
  passed after 4,080 variants.
- X2 / h40 / h27: the overnight information is priced by 09:15, so R3 exits at 09:16.

Expectation, written now: most variants will be about B0 or worse. The edge is in the gap, and the morning after 09:16
is close to a coin flip.

## Data (the Dhan lake on Drive, `IraAlgo-data-lake`, rebuilt 13-14 Oct 2026)

- `index_1m` NIFTY, BANKNIFTY, INDIAVIX.
- `options_rolling_1m`:
  - `opt_NIFTY_WEEK` (R3's contract and the OI build-up);
  - `opt_BANKNIFTY_MONTH` (R3's contract);
  - `opt_BANKNIFTY_WEEK` (the nearest series for the OI build-up; from 2025 it is the monthly, byte for byte).
- `equity_1m`: the 59 NIFTY 50 + NIFTY BANK stocks.
- Span: options 2 Aug 2021 to 12 Oct 2026. 13 and 14 Oct are not in the lake. Raw data stays out of git; caches live in
  `~/.cache/iraalgo/r27`.

R3 is rebuilt exactly as `NightRules` / `research/hunt/x2/PREREG.md`:
- the 15:19 close location, the day's move and breadth (R17 "all agree");
- the day-end OI build-up z (h26 BUopen at 15:19, ATM ±2 of the nearest series, z against the previous 60 sessions,
  at least 20), traded when side × z >= 0.5;
- 1-ITM strike from the 15:19 close (floor(x / step + 0.5) × step);
- bought at the option's 15:20 open (first bar 15:20-15:23);
- no entry on an expiry day of the index, never held into the contract's expiry. Expiry days are found from the data
  (r25's time-value test). They agree with NSE's calendar except where the data is right and the weekday rule is not:
  the Diwali shift of 3 Nov 2021, and BANKNIFTY's Thursday monthlies of Sep 2023 to Jan 2024.

### Step 1 check (`results/rebuild.json`)

- **The OI build-up matches the app's seeded history exactly.** On the 60 sessions 10 Jul to 5 Oct 2026, for both
  indices, the largest difference from `NightRules.SEED` is 0.0.
- **The differences from R3 as researched and run:**
  1. **Breadth.** The lake has 59 stocks, not R3's 214 F&O stocks. R27 reads the share of the 59 above their previous
     close at 15:19 (minute data, so no look-ahead), with at least 40 priced. The app needs 100 or more of its 214.
  2. **Start date.** The z needs 20 sessions of history, so the first trades are in Sep 2021. NIFTY in X2 started in
     2020 from h31's data.
  3. **The app's live-only gates cannot be rebuilt.** These are the thin-option gate, the order-flow gate, the kill
     switch and the day lock.
  4. **BANKNIFTY lot.** It is 30 from 31 Dec 2025 (NSE's revision; obuy's lot table misses it). The app's 14 Oct sale,
     -Rs 1,859 on (730 - 789), is 30 units.
- **R3 signals that have both prices:**

  | period | NIFTY | BANKNIFTY |
  |---|---|---|
  | 2021 (Aug-Dec) | 26 | 26 |
  | 2022 | 90 | 82 |
  | 2023 | 79 | 87 |
  | 2024 | 79 | 88 |
  | 2025 Jan-Sep | 64 | 75 |
  | holdout (count only) | 75 | 98 |

- **B0 net by year, design only, both indices, against X2:**

  | year | R27 | X2 |
  |---|---|---|
  | 2021 (Aug-Dec here) | +34k | +48k (all of 2021) |
  | 2022 | +54k | +41k |
  | 2023 | +30k | +46k |
  | 2024 | +38k | +39k |
  | 2025 Jan-Sep | +74k | +78k |

  The same size and sign each year. Trade-for-trade identity is not possible: X2's trade list is not in git, and the
  breadth here is a 59-stock proxy.

## Periods and the locked holdout

| block | dates | use |
|---|---|---|
| DESC | lake start to 31 Dec 2022 | design: the descriptive step (step 2) read only this; reported as in-sample |
| WF | 2023, 2024, 2025 Jan-Sep | walk-forward test years: no variant outcome read before this file |
| HOLDOUT | 1 Oct 2025 to the lake's end (12 Oct 2026) | read ONCE by `study.py holdout` (flag file; refuses a second read) |

Why this holdout, and what was read there before (no block is pristine for this arm):
- X2 read R3's own B0 (the 09:16 sale) on 1 Oct 2025 to 6 Oct 2026: +Rs 150/day, 155 trades. B0 says nothing about the
  path after 09:16, which is what R27 tests.
- h31's holdout read, as information only, two R17 variants with post-09:16 exits on NIFTY weekly, on the same nights
  family:
  - GX (sell at 09:16 if the 09:15 bar is against, else hold to 10:15): +Rs 43.8k;
  - 10:15: +Rs 35.8k;
  - plus R17 family summaries.

  That is the nearest thing to H2 and it was positive there. It is disclosed, and it is a reason to read the result
  with care, not to change the variants.
- The older block (2021-2022) was mined harder: h31 chose its variants on it, including P15-P30 and GX. So the most
  recent block is the least-read one for this question. It is also the current regime: BANKNIFTY monthly only, NIFTY
  Tuesday expiries, today's lots.
- R12, R19c and R25 never read R3 nights.
- The descriptive step read DESC only. Its result (step 2, below) shaped two choices: the 10:30 cap, and the expectation
  that H2 is weak.

### Step 2 (DESC block, 224 R3 trades; `results/describe.md`)

Premium is in Rs per unit against the 09:16 open:
- **Holding is flat on average.** Mean change to 09:30 / 10:00 / 10:30 / 11:00: +7.0 / -3.0 / -0.7 / +5.2 (Rs +231 /
  -92 / +7 / +194 a lot).
- **Big swings both ways.** The index (NIFTY-scaled) reaches +20 in the position's favour by 10:30 on 82% of
  mornings, but its median adverse move is -55. The option touches +20 on 74% and -20 on 71%.
- **"Gap and the 09:15 bar both with us" (H2's sign) is WORSE**, -Rs 257 a lot to 10:30 (n 62). "09:15 bar against
  us" is better, +Rs 340 (n 104). That is first-minute mean reversion.
- **Other leads, not tested in R27:** VIX down at 09:15 (+Rs 308, n 106) and the held option's OI up overnight
  (n 17). They are for a later pre-registration only.

## The 8 variants (frozen)

Common to all:
- The decision is made at 09:16:00 on what is known then: the next session's 09:15 bar of the index, VIX and options,
  and the entry day.
- Not held: sold at the 09:16 open, exactly as B0.
- Held: watched from the 09:16 bar.
  - Premium levels are resting orders on the option's 1-minute high/low. The stop counts first in a tie. A gap through
    a level fills at the open.
  - Index levels are read on the index's 1-minute high/low and sold at the option's next open.
  - Time cap: the 10:30 open. Every variant has a stop and a cap.
- "Scaled" BANKNIFTY points are pts × (BANKNIFTY / NIFTY 15:19 level on the entry day), about 2.1-2.3.

| id | hold when | target | stop | cap |
|---|---|---|---|---|
| V1_H1_I20s | always (H1) | index +20 in our favour (BN scaled) from the 09:15 close | index -20 (BN scaled) | 10:30 |
| V2_H1_I30s | always | index +30 (BN scaled) | index -30 (BN scaled) | 10:30 |
| V3_H1_I20r | always | index +20 (BN raw points) | index -20 (BN raw) | 10:30 |
| V4_H1_I30r | always | index +30 (BN raw) | index -30 (BN raw) | 10:30 |
| V5_H1_P20 | always | option +Rs 20 a unit over the 09:16 open | option -Rs 20 | 10:30 |
| V6_H1_P30 | always | option +Rs 30 | option -Rs 30 | 10:30 |
| V7_H2_I30s | **H2**: the gap (09:15 open vs the entry day's last close) AND the 09:15 bar (close vs open) are both in the position's favour; else sold at 09:16 | index +30 (BN scaled) | the index back through its 09:15 open | 10:30 |
| V8_H3_LOCK | always (H3, Boss's floor as R19c / the app's R19b switch) | none | the lot Rs 1,000 below its 09:16 value; the floor: once the lot is +Rs 1,000 over the 15:20 buy (option high, from the next minute), a resting stop Rs 500 on the lot below the best, raised only, on the tick rounded up | 10:30 |

- V1/V3 and V2/V4 are the same on NIFTY. They differ only in BANKNIFTY's reading of "points".
- **Random-decision twin (the baseline for the one conditional variant, V7):** hold on random nights at V7's own hold
  rate in the same period, with V7's exits. 2,000 twins, seed 27. H1 and H3 always hold, so their baseline is B0
  itself.

## Costs

The same as X2:
- app fills: +5 bps buy, -5 bps sell, -10 bps resting stop; a target limit fills at its level;
- app charges (obuy `Costs('app')`, STT 0.15%);
- the h24 half-spread of 0.16% on the entry and exit prices;
- STRESS = 1.5× the spread.

The lot is the one in force on the entry day.

## Tests (WF years pooled; per year reported)

- **Main test.** One-sided paired t-test of the per-trade difference (variant net - B0 net on the same night) > 0, over
  all R3 trades. A night the variant leaves alone counts as 0. BH across the 8 variants (q).
- **R3's family.** Also reported: q with the 4,085 tries already logged in the `overnight` family (h31 + X2), counted
  as p = 1. This is information; the gate uses the 8.
- **Reported for each variant and B0:**
  - trades, % held, win rate, average win and loss;
  - net, per trade and per session (all sessions of the period);
  - the difference from B0;
  - net at 1.5× spread;
  - % green days, worst day, max drawdown;
  - "gave it back" (B0 > 0 and the variant <= 0) and "rescued" (B0 <= 0 and the variant > 0);
  - helped / hurt nights;
  - the share of B0's top-decile winners' net kept ("big gains captured");
  - per index, the exit reasons, and sample sizes.
- **Walk-forward by year.**
  - Each fixed variant's difference from B0 in 2023, 2024 and 2025.
  - An anchored pick: each test year trades the variant with the best total difference over ALL earlier years (DESC
    included), or B0 if none is positive. Its total is reported.
- **PASS_WF (all needed):**
  1. pooled difference > 0;
  2. BH q (8) <= 0.10;
  3. difference > 0 in at least 2 of the 3 WF years;
  4. difference > 0 at 1.5× spread;
  5. max drawdown and worst day no more than 10% worse than B0's;
  6. V7 only: twin p <= 0.05;
  7. at least 100 trades.
- **Holdout (once).** All 8 and B0 are reported. A variant that passed WF must also show:
  - holdout difference > 0;
  - BH q <= 0.05 over the WF-passed variants;
  - twin p <= 0.05 where one applies;
  - at least 30 trades.

  If none passed WF, the holdout is information only.
- **Verdict.**
  - A variant that passes both is described for **PAPER only**: behind a switch, off by default, logged in the
    Autopilot timeline, one-tap undo, never live (CLAUDE.md).
  - Everything else stays a note.

## What cannot be tested on minute history (said up front)

The app's live "agents" cannot be rebuilt from 1-minute bars, so R27 does not test them:
- order flow (bid/ask and aggressor imbalance);
- the consensus / majority vote;
- the trap guard;
- the trade manager's VWAP / value-area / OI / VIX extension.

How the app could test them forward, in **SHADOW**. Each morning that Night holds a position:
- at 09:16, log each agent's view (flow direction and strength, consensus vote, trap flag, the trade manager's verdict);
- log what each R27 variant would do;
- log the realised path to 10:30: the option's high, low and close each minute, from the app's own recorder.

Nothing is acted on. After about 100 mornings, the same paired test against the 09:16 sale decides whether any agent
earns a paper switch.
