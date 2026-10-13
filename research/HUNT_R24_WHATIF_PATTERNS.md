# R24: what every option buy would have done, what came before the big winners and losers, and rules built from it

Written 13 Oct 2026 (IST evening), for Boss's request of 14 Oct: "Add calculation data to the brain for every call/put
option: what could have happened, the profit or loss, if we had bought it. Find all the amounts and back-track them, find
all the patterns, find the most common pattern, and create a strategy based on it."

Pre-registered in `research/hunt/r24/PREREG.md` (commit `11cfa984`, pushed before any outcome was computed). Code, tests
and tables: `research/hunt/r24/` (`whatif.py` Step 1, `items.py` + `mine.py` + `study.py` Steps 2–3, `report.py`,
`results/report.md` with every table, `results/*.json`). Experiment entries: `research/experiments/entries/r24-*.yaml`
(the measurement, the selection procedure, and the 8 rules). Research only: nothing in the app changes.

## Verdict for Boss

- **What did the big winning option buys have in common before entry?** Mostly that it was **expiry day** and the ATM
  straddle had **grown since 09:20** (the market was already moving), plus the option being on the side of the move
  (with the day's trend, past the 15-minute opening range, above the day's average price). In those states 32–34% of
  buys made +50% or more by 15:05, against 8.8% normally (lift 3.6–3.8×).
- **But the same states made the big losers too.** In those exact states 45–47% of buys lost 50% or more (loss lift
  2.7–2.8×). The pattern marks **big swings, not an edge**. Their average result under the app's ORB exits is about
  zero (Rs −35 to +41 a lot). The "most common" states before winners (several levels nearby, NIFTY, high IV) are just
  as common before losers.
- **What did the big losers have in common?** Far out-of-the-money options (OTM2–3) bought on expiry day: 90–95% of them
  lost half or more by 15:05, worse after 13:00 and when the straddle was shrinking. Also: options bought 7+ days from
  expiry (monthly BANKNIFTY) almost never win big. **Avoid buying OTM2–3 on expiry day.**
- **Does a pattern still win on unseen data after costs?** **No.** 8 rules were built from the top patterns (design
  data only) and read once on the locked holdout (27 Apr – 12 Oct 2026, 116 sessions per index). **0 of 8 pass.** The best,
  R24-p3 ("ATM IV high and the opening gap went against the option, Liquidity 15+5 exits"), made Rs +14,066 on 47 trades.
  That is no better than buying at random minutes on the same days (p 0.073, BH q 0.86 across 41 tests).
  Walk-forward inside the design period was already negative: Rs −332k over three test blocks.
- **Blind buying loses at every exit.** On average every one of the 14 options (CE / PE, ATM−3..+3), at every entry
  minute, loses money at +15 min, +30 min, +60 min, 15:05 and 15:20, and under all three app exit families. NIFTY loses
  Rs −97 (+15 min) to Rs −170 (15:05) a lot; BANKNIFTY Rs −131 to Rs −231. The round trip's costs alone are about Rs 95 a lot.
- **No rule passes, so there is nothing to put on a paper arm.** What is kept is the table itself. It is on Drive and in
  the brain as labels, for later studies and for Jarvis's "what would have happened" answers.

## Step 1: the what-if table ("calculation data")

**What it is.**
- Source: the Dhan lake on Drive (`opt_{NIFTY,BANKNIFTY}_WEEK`, index and VIX minutes), read through the brain's lake
  adapter and cube v2.
- One row for every session × index × side (CE, PE) × strike (ATM−3..ATM+3 at the decision minute) × entry minute
  09:20–14:45.
- **11,662,056 rows**, 99.9% filled. NIFTY and BANKNIFTY each have 1,282 sessions with an option chain, 2 Aug 2021 to
  12 Oct 2026.
- Each row is "bought at the ask", 1 lot, net of everything.

**Costs (the hunt studies' model plus the spread by moneyness).**
- Buy at the open + 5 bps + a half-spread; sell at the price − 5 bps − the half-spread (stops: −10 bps).
- Half-spread = the larger of h × price and 1 / 2 / 4 ticks (by the contract's volume in the previous 5 minutes).
  h = 0.16% ITM/ATM, 0.20% OTM1, 0.25% OTM2, 0.35% OTM3.
- The app's charges at today's rates (STT 0.15%).
- Every rupee number is also stored at twice the spread (`*_x2`).

**Outcomes in each row.**
- Net Rs and % at +15 / +30 / +60 minutes, 15:05 and 15:20.
- The best and worst traded price before 15:20, as % and Rs a lot (MFE / MAE), and the minutes to each.
- The app's three exit families, net:
  - `orb`: −40 / +40 with the profit-lock ladder, out at 15:10 (15:05 on expiry day);
  - `sweep`: −40 / +80 with its ladder;
  - `liq`: Liquidity 15+5's −15% stop and 20-minute time stop, without its index-level target, which belongs to its signal.

**Where it is.**
- `gdrive:IraAlgo-data-lake/derived/r24_whatif/` (12 Parquet files, 1.22 GB, with `CATALOG.json`: rows, sessions,
  sha256, columns). Uploaded and checked with `rclone check`. Not in git.
- **In the brain: labels only.**
  - `research/brain/whatif_labels.py` writes `<cache>/labels_whatif/<UND>/<YYYY>/<day>.parquet`: 32 `y_wi_*` columns per
    decision minute (ITM1 / ATM / OTM1 × CE / PE: net % at +30 and 15:05, MFE, MAE, ORB-exit Rs; plus the best net % at
    15:05 among the 14 and the share that won).
  - They meet features only through `split.join_forward`.
  - `research/brain/tests/test_whatif_labels.py` proves no feature module reads them. A name clash either way is refused.
  - The R24 tests prove the pattern items are computed from features alone: scrambling every outcome leaves them
    unchanged.

**The amounts (design period, 10.5 million option-minutes, 1,161 sessions; mean net Rs per lot).**

| index | option | median fill | +15 min | +30 min | 15:05 | median best (MFE) | median worst (MAE) | big win (≥ +50% at 15:05) | big loss (≤ −50%) |
|---|---|---|---|---|---|---|---|---|---|
| NIFTY | ATM CE | 97 | −97 | −106 | −182 | +23% | −29% | 11.4% | 20.0% |
| NIFTY | ATM PE | 85 | −92 | −100 | −164 | +26% | −31% | 12.2% | 19.7% |
| NIFTY | OTM3 CE / PE | 36 | −76 / −78 | −81 / −84 | −127 / −147 | +23% / +24% | −38% | 9.2% / 8.8% | 31.4% / 30.5% |
| BANKNIFTY | ATM CE | 317 | −135 | −144 | −223 | +17% | −22% | 8.3% | 15.0% |
| BANKNIFTY | ATM PE | 287 | −124 | −131 | −236 | +19% | −24% | 9.1% | 14.4% |
| BANKNIFTY | ITM3 CE / PE | 503 / 453 | −159 / −142 | −168 / −146 | −293 / −249 | +14% / +16% | −17% / −18% | 5.7% / 7.0% | 6.8% / 6.4% |

- **The typical bought option does go up after entry.** The median best price before 15:20 is +17% to +26% above the
  fill, reached about 50 minutes in. Exits decide the result, and on average no fixed exit recovers the costs.
- **Expiry day is where the big outcomes are.** Big wins: NIFTY 14.8%, BANKNIFTY 16.5%. Big losses: about 57% for both.
  Options 7+ days out (BANKNIFTY monthlies) almost never move 50% in a day (0.8% big wins).
- **Earlier entries have bigger swings.** Entries before 10:00 have the most big wins and the most big losses; after
  13:00 there are the fewest of both.
- Every table (all 28 index × side × strike cells, by time of day, by days to expiry): `hunt/r24/results/report.md`.

## Step 2: back-tracking (design only, 2.13 million option-minutes on a 5-minute grid, 1,161 sessions)

- Big winner = net ≥ +50% at the 15:05 exit (8.8% of rows); big loser = net ≤ −50% (16.8%).
- The state before entry is read from the brain's point-in-time features at the decision minute (the look-ahead guard
  passes on the real lake cube: 9 cuts × 2 modes over 4 days, both indices).
- The 23 market items are aligned to the option's side ("with the option" = up for a call, down for a put).
- 66,944 itemsets of 1–3 items met the floors (≥ 0.5% of rows, ≥ 100 sessions). Every table below has its row and
  session counts in `results/report.md`.

**The most common states before big winners are just common** (share of all big wins vs share of all rows):

| state before entry | share of big wins | share of all rows | share of big losses | win lift | loss lift |
|---|---|---|---|---|---|
| two or more levels within 0.2% | 97.4% | 97.9% | 98.1% | 0.99 | 1.00 |
| index = NIFTY | 56.2% | 50.3% | 57.3% | 1.12 | 1.14 |
| ATM IV in its top third | 45.6% | 33.4% | 57.7% | 1.37 | 1.73 |

**Ranked by lift, the strongest winner patterns are expiry-day expansion, and they are two-sided:**

| pattern (all on expiry day, straddle grown since 09:20) | rows / sessions | big-win rate (lift) | big-loss rate (lift) | mean Rs at 15:05 | mean Rs, ORB exits |
|---|---|---|---|---|---|
| + other-side OI building up | 13,279 / 158 | 33.8% (3.84) | 47.0% (2.80) | +897 | −35 |
| + the 15-min opening range broken with the option | 11,676 / 167 | 33.1% (3.77) | 44.8% (2.66) | +834 | +41 |
| + trend since the open with the option | 13,097 / 192 | 31.8% (3.62) | 46.4% (2.76) | +618 | +19 |

- These are the moves R21 (the Hero arm) chased. Holding to 15:05 averages positive here, but R21's holdout already
  showed such jackpots do not repeat on demand: 0 wins in 11–15 holdout trades for every one-sided variant.
- **Big losers (what to avoid):** OTM2–3 on expiry day. With the IV or VIX mid-range, realised vol low, or entry after
  13:00, 90–95% of them lose half or more (loss lift 5.3–5.7×, 18,000–38,000 rows, 118–307 sessions each).
- The tree (depth 4) says the same: its best leaf is "expiry day, entry before 13:00, not OTM2–3". There 20.1% win big
  (lift 2.3), but 46.4% lose big and the mean is still −Rs 266 at 15:05.
- **Second view, ORB exits.** The top decile of ORB-exit Rs is dominated by options 7+ days out. The same states fill the
  bottom decile (bigger lots and premiums give bigger rupee swings both ways).

## Step 3: rules from the patterns

**How the 8 rules were built (as pre-registered).**
- Eligible: winner lift ≥ 1.3, ≥ 100 sessions, the pattern fixes a side.
- Ranked by lift × support; at most 70% day overlap with a rule already chosen.
- Each rule buys the first minute of the day the pattern holds: ATM, or the pattern's moneyness; 1 lot; one trade per
  index a day.
- Exit family = the best of orb / sweep / liq on design. All 8 chose `liq`: on design, the orb and sweep exits lost on
  7 of the 8.
- Clarification, fixed in code before Step 3 ran: when a pattern holds for the call and the put in the same minute, that
  minute is skipped (never both bought).

**Walk-forward (rules picked on the earlier design blocks, scored on the next).**

| test block | net of the 8 rules |
|---|---|
| 2 (Apr 2022 – Aug 2023) | Rs −214,260 |
| 3 (Aug 2023 – Dec 2024) | Rs +84,575 |
| 4 (Dec 2024 – Apr 2026) | Rs −202,313 |

Only one rule (PCR low + put, `liq` exits) was picked in every fold. It made +71k, +59k and then −27k.

**The final 8 (selected once on the whole design period), and the one holdout read.**

| id | rule (state at the decision minute → buy, exit) | design trades / net Rs | design blocks positive | holdout trades | holdout win | holdout net Rs (Rs/day) | worst day | max DD | big winners cut short | 2× spread | random-entry p / BH q | random-pattern p | gates |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| p1 | put-call OI ratio low, buy the PE | 1,775 / +143,779 | 3 of 4 | 134 | 24% | −1,595 (−14) | −5,338 | −45,850 | 38 / 45 | −6,601 | 0.491 / 1.00 | 0.318 | fail 2-6 |
| p2 | two+ levels near, ATM IV high, own-side OI building | 817 / +7,117 | 2 | 45 | 13% | −20,777 (−179) | −4,133 | −23,832 | 21 / 22 | −21,619 | 0.810 / 1.00 | 0.547 | fail 1-5 |
| p3 | ATM IV high, opening gap against the option | 749 / +24,947 | 3 | 47 | 19% | +14,066 (+121) | −8,285 | −18,401 | 15 / 21 | +12,568 | 0.073 / 0.86 | 0.045 | fail 3 |
| p4 | two+ levels near, expiry day, buy the CE | 430 / +25,815 | 2 | 32 | 3% | −18,601 (−160) | −3,120 | −18,704 | 11 / 12 | −18,950 | 0.993 / 1.00 | 0.498 | fail 1-5 |
| p5 | gap against the option, 1–2 days to expiry | 513 / +33,125 | 2 | 18 | 17% | +3,187 (+27) | −3,009 | −6,328 | 5 / 7 | +2,879 | 0.116 / 0.86 | 0.095 | fail 1-4 |
| p6 | other-side OI building, realised vol high | 1,247 / +9,766 | 2 | 84 | 26% | +15,952 (+138) | −7,920 | −17,438 | 18 / 26 | +8,573 | 0.490 / 1.00 | 0.040 | fail 1, 3 |
| p7 | some room to the next level ahead, ATM IV high | 1,015 / +51,329 | 3 | 61 | 23% | +804 (+7) | −7,844 | −37,255 | 25 / 29 | −220 | 0.287 / 1.00 | 0.114 | fail 3-5 |
| p8 | far from the level behind, IV rank high | 826 / +49,439 | 2 | 62 | 23% | +14,737 (+127) | −6,704 | −35,249 | 19 / 26 | +13,746 | 0.138 / 0.86 | 0.030 | fail 1, 3 |

Gates (all must hold):
1. design net > 0 in ≥ 3 of 4 blocks;
2. holdout ≥ 30 trades and net > 0;
3. random-entry twin BH q < 0.05 (41 p-values: these 8 plus 33 R20–R22 holdout p-values; R23 has not read a holdout);
4. random-pattern twin p < 0.05;
5. holdout net > 0 at 2× spread;
6. holdout max drawdown under Rs 40,000 a lot.

Trades a day: 0.16–1.16 per session (both indices together).

**Reading.**
- **The patterns beat random patterns, not random timing.**
  - 200 random patterns of the same size and frequency, with the same exits, lost about Rs 22k–30k each on the holdout.
    Three rules (p3, p6, p8) beat them (p 0.030–0.045).
  - But buying at random minutes on the same days, same side and strike, did about as well as the rules. For p6 the
    random-entry average was Rs +16,287 against the rule's +15,952.
  - So the small edge is in which days and sides the Liquidity exits were used on (a mild, mostly short-lived
    tendency), not in the pattern's moment.
  - A caveat on the random-pattern control: 22–38 of each rule's 200 twins (11–19%) never trade on the holdout. They are
    patterns whose bins hold for the call and the put at once, so every minute is skipped, and they count as Rs 0. Left
    out (counts only; no outcome re-read), the pattern p would be about 0.055 for p3, 0.045 for p6 and 0.035 for p8. No
    verdict changes: gate 3 fails for all eight.
- **The Liquidity exits cut most big winners short:** 573 of the 699 trades that reached +50% (p1, design) kept less
  than half of it (the 20-minute time stop and the −15% stop). That is the price of their small, steady losses.
- **The last design block (Dec 2024 – Apr 2026) was negative for 7 of the 8 rules.** Whatever worked in 2022–2024 had
  already faded before the holdout.

## Honest notes

- **Pre-registration.** It was written and pushed before any outcome existed (`11cfa984`). The tercile cut points were
  computed mechanically on design rows. One clarification was made in code before Step 3 ran: a both-sides minute is
  skipped. Nothing else changed.
- **Holdout.** It was read once (`study.py holdout` refuses a second read: a cache flag, and the committed
  `results/step3_holdout.json`). It covers 116 sessions per index, about 5.5 months: short, so a real but small edge could
  be missed. R23 plans to read the same lake window for its own 3 finalists; the two studies are separate families.
- **Per-session figures.**
  - Design figures divide by all 1,305 index sessions of the design period. That includes Jan–Jul 2021, before the
    option data starts (1,161 sessions have options); multiply by 1.12 for "per session with options".
  - Design block 1 likewise starts on 1 Jan 2021.
- **Found in the independent review (none changes a verdict).**
  - The design days and blocks come from the feature store, which starts 1 Jan 2021, before the option data (2 Aug 2021).
    So about 144 sessions without options sit in block 1 and in the tercile fits. This differs from the PREREG wording
    "from the first session with an option chain". p8's block 1 is Rs 0 (no trades), which is part of why it fails gate
    1; it fails gate 3 too.
  - The BH family uses, for the four R20 break rules, their random-LEVEL twin p. Using their random-TIME p (piv5n2 0.00)
    instead leaves every R24 q above 0.05: the smallest R24 p is 0.073 across 41 tests.
  - The ORB stop is rounded down to the tick; the app rounds half-even, at most Rs 0.05 apart.
  - Options filled at Rs 40 or less keep their `orb` / `sweep` rows with no stop. That matches the PREREG and the app's
    `stopTrigger`, but the app's arms refuse such entries. So the `orb` column for cheap options (NIFTY OTM3) and the
    `y_wi_*_orb_rs` labels describe a trade the app would not take. All 8 rules chose `liq`, so they are unaffected.
  - The brain's `y_wi_*` labels are written for every day, including R24's holdout window. A later study must not use
    27 Apr – 12 Oct 2026 as a fresh holdout.
- **The spread model.**
  - It is anchored on one real snapshot (h24, 6 Oct 2026; 0.05–0.24% half-spread from ITM2 to OTM2). OTM3's 0.35% is an
    extrapolation.
  - Earlier years were probably wider, which would make every number above worse, not better.
  - Fills are at the minute's open; the real ask at a signal can be wider (h24).
- **What is missing.**
  - Order-flow summaries are not in the lake; they exist only in the phone's recordings since Oct 2026.
  - The brain's day-type is on the sibling branch (`claude/india-brain-v1`). Two point-in-time proxies stand in: ADX, and
    the day's range used vs VIX's expected move.
  - The lake's index has no volume, so "VWAP distance" is the brain's equal-weight average price (`dist_tpavg_pct`).
  - "Two+ levels within 0.2%" is true on 98% of rows, so it carries no information (its bins were fixed before the data
    was seen).
- **Monthly expiries.** The lake's `opt_*_MONTH` files are not on Drive yet. The study ran on the nearest expiry
  (`WEEK`), which for BANKNIFTY has been the monthly since Nov 2024. The code builds the MONTH table with no change once
  the files exist: its expiry is the month's last scheduled expiry from the brain calendar. A test checks it against the
  cube path.
- **Sample sizes.** Every number above carries its count: rows and sessions in Steps 1–2, trades and sessions in Step 3.

## Re-running on the full lake (one command)

```bash
bash research/hunt/r24/refresh_lake.sh
```

This one command:
- fetches the lake from Drive, including `opt_*_MONTH` once uploaded;
- rebuilds the cube, the features and the look-ahead guard for both indices;
- rebuilds the what-if table (WEEK and MONTH) and the brain's `y_wi_*` labels;
- re-runs Steps 1–2 and the report, and uploads the table and catalog to Drive.

Step 3 is not re-run: its holdout has been read, so new rules need a new pre-registration and a fresh holdout. The script rewrites Steps 1–2 only, so after a refresh `results/report.md` mixes the new Steps 1–2 with the old Step 3: add a dated note when committing it.

## Checks run

- `python -m pytest research/brain/tests -q` (incl. the new `test_whatif_labels.py`).
- `python -m research.brain.features catalog --check`.
- `python -m pytest research/hunt/r24/test_r24.py -q` (22 tests):
  - the outcome maths on known option paths: fixed exits, MFE / MAE and their timing, the ORB target, stop and lock,
    the sweep lock, the Liquidity time stop, no fill;
  - the cost model against `research/obuy/costs.py`;
  - the outcome never depends on prices before the fill;
  - the label / feature separation;
  - the miner finding a planted pattern, and telling "common" from "lift";
  - rule entries, the per-rule numbers, the MONTH path, the one-read holdout.
- `python -m research.brain.guard` on the real lake cube for both indices (PASS).
- `python research/experiments/validate.py` and `python research/knowledge/build_pack.py --check`.
