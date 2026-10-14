# R27: Night (R3) smart exit - hold past 09:16 for +20/+30 points, or keep selling at 09:16?

Written 14 Oct 2026, for Boss's request: "For the Night strategy: if the agents think the market will go up after some
time, or increase by 20-30 points, keep the trade and wait for the profit, then sell it." That morning the Night (R3)
paper arm's BANKNIFTY 55400 CE (bought 13 Oct 15:20 at about Rs 789) was sold by its fixed rule at 09:16 at Rs 730,
-Rs 1,859 after charges, and BANKNIFTY then rose about 215 points by 10:00.

- **Pre-registration:** `research/hunt/r27/PREREG.md`, commit `b2f0b55`, pushed before any test-period outcome was
  computed. The 8 variants, gates and holdout were frozen there; its sha256 is stamped in every result file.
- **Code and tests:** `research/hunt/r27/`. `study.py` runs rebuild → describe → test → holdout → report; `test_r27.py`
  has 27 tests, including the look-ahead guard.
- **Results:** `research/hunt/r27/results/`. `report.md` has every table, plus `rebuild.json`, `describe.md`,
  `test.json` and `holdout.json`. The holdout was read once (`HOLDOUT_READ.flag`).
- **Experiment entries:** `research/experiments/entries/r27-v1..v8.yaml`, one per variant, all failures.
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **Does waiting for +20/30 points pay after costs? No.** 8 ways of holding past 09:16 were tested on 5 years of
  minute data, each with a stop and a 10:30 time cap. **None beat the plain 09:16 sale on the years it was not designed
  on.** In the locked last year (1 Oct 2025 to 12 Oct 2026, 173 trades, read once) **all 8 made less than the 09:16
  sale**: Rs 4,600 to Rs 25,900 less, or Rs 26 to Rs 150 less per trade.
- **How often it helped vs hurt.** It is a coin flip.
  - Holding always (the six "H1" ways) helped on 75-85 nights and hurt on 88-98 in the locked year. Over 2023-25 it was
    about 50/50: 217-243 helped vs 227-255 hurt of 472.
  - The morning after 09:16 swings both ways. On 4 mornings in 5 the index goes 20 points our way before 10:30, but its
    median move against us is about 55 points. So a +20 target and a -20 stop are reached about equally often.
- **Holding when the morning "looks good" made it worse, not better (H2).** "Hold only if it opened our way and the first
  minute also went our way, sell on +30, stop if the index falls back through its open":
  - 2023-25: -Rs 863 vs the 09:16 sale;
  - locked year: -Rs 8,916;
  - no better than holding on random days at the same rate (random-twin p 0.71 and 0.62).

  A strong first minute tends to be given back: those mornings averaged -Rs 257 a lot by 10:30 in 2021-22.
- **Boss's lock (H3: from +Rs 1,000, keep Rs 500 below the best; stop Rs 1,000 below the 09:16 value).**
  - It gives more green days: 55.7% vs 49.2% in the locked year.
  - But it cuts the big nights: hurt on 113 nights, helped on 60.
  - It made **-Rs 25,900 vs the 09:16 sale** in the locked year (173 trades), and -Rs 2,461 in 2023-25. This is the
    same result as R19c.
- **"Gave it back"** (a 09:16 profit turned into a loss by holding):
  - 2023-25: 4-50 times in 472 trades, depending on the variant;
  - locked year: 1-17 times in 173.
  - It roughly cancels the losers that holding rescued: 2-39 and 1-17.
- **The trap in this data.** On 2021-22, the block the descriptive step read, the index-point holds looked good: +Rs 21k
  to +34k on 224 trades, p 0.006-0.04. They faded in 2023-25 (best +Rs 24.8k on 472, BH q 0.48) and turned negative in
  the locked year. Had the rule been chosen on 2021-22 alone, the app would now be losing money with it.
- **Today's trade (14 Oct, an estimate).** 14 Oct is not in the data lake (it ends 12 Oct). See "The 14 Oct trade"
  below.
  - The option opened Rs 59 below its buy price, which points to a gap down against the call. H2 would therefore
    probably have sold at 09:16 exactly as the app did: -Rs 1,859.
  - The "always hold" ways would have lost less if BANKNIFTY rose steadily from 09:16: about -Rs 780 to -Rs 1,530.
  - Boss's lock could have finished about +Rs 1,100.
  - But if BANKNIFTY first dipped 45-60 points (the usual morning swing), the same rules would have stopped out lower:
    down to about -Rs 2,850.
  - One morning cannot decide this. On the 173 locked-year mornings, every one of these rules did worse than selling
    at 09:16.
- **What to change: nothing in the Night arm. Keep the 09:16 sale.** No variant passed, so none is offered even for
  paper.
- **The app's live "agents" were not tested.** Order flow, consensus, the trap guard and the trade manager's view cannot
  be rebuilt from minute data. If Boss wants them judged, the way is a SHADOW log each morning that Night holds a
  position (below). It acts on nothing, and is judged after about 100 mornings with the same test.

## What was tested

- **The trades: R3 exactly as the app runs it** (`NightRules`, X2's PREREG rule R3).
  - 1-ITM NIFTY weekly and BANKNIFTY monthly, 1 lot.
  - Bought at the 15:20 open on a strong close (location, day move, breadth) that the day-end option OI build-up agrees
    with (z >= 0.5).
  - Never on an expiry day, never held into expiry.
  - Rebuilt on the Dhan lake (Aug 2021 to 12 Oct 2026): 869 trades (224 + 472 + 173).
- **B0 = the 09:16 sale** (as now).
- **The decision is made at 09:16:00**, using only what is known then: the 09:15 bar of the index, VIX and options,
  and the entry day. Held options are watched on 1-minute highs and lows (stop first in a tie, gaps fill at the open).
  Index levels sell at the option's next open. Every hold has a stop and a 10:30 cap.
- **Costs**, as X2:
  - app fills (+5 bps buy, -5 bps sell, -10 bps stop);
  - app charges (STT 0.15%);
  - the real half-spread of 0.16% on both sides;
  - also checked at 1.5× the spread.

| id | hold | target | stop |
|---|---|---|---|
| V1 / V2 | always (H1) | index +20 / +30 points our way (BANKNIFTY scaled by its level, about ×2.2) | index -20 / -30 (scaled) |
| V3 / V4 | always | index +20 / +30 (BANKNIFTY raw points) | index -20 / -30 (raw) |
| V5 / V6 | always | option +Rs 20 / +Rs 30 a unit over the 09:16 price | option -Rs 20 / -Rs 30 |
| V7 (H2) | only if the gap AND the 09:15 bar both went our way | index +30 (scaled) | the index back through its 09:15 open |
| V8 (H3) | always | none: Boss's floor (from +Rs 1,000 on the lot over the buy, Rs 500 below the best, raised only) | the lot Rs 1,000 below its 09:16 value |

The random-decision twin for V7 holds on random nights at V7's own rate, with the same exits (2,000 twins).

## Periods (why this holdout)

| block | dates | R3 trades | use |
|---|---|---|---|
| DESC | Aug 2021 - Dec 2022 | 224 | the descriptive step; in-sample |
| walk-forward | 2023, 2024, 2025 Jan-Sep | 166 / 167 / 139 | test years, not read before the PREREG |
| HOLDOUT | 1 Oct 2025 - 12 Oct 2026 | 173 (NIFTY 75, BANKNIFTY 98) | read once |

- **No block is unread for this arm.** X2 read R3's 09:16 sale in the last year, and h31 read R17 nights with 10:15,
  "GX" and premium-point exits in every year.
- **The last year is the least-read one for "what happens after 09:16"**, and it is the current regime.
- **The 2021-22 block was mined harder** (h31 chose its variants there).
- These prior reads are disclosed in the PREREG.

## Step 1: the rebuild matches the app and X2

- **OI build-up.** The app's 15:19 OI build-up history (`NightRules.SEED`, 60 sessions to 5 Oct 2026) is reproduced
  exactly for both indices (largest difference 0.0).
- **B0 against X2's numbers:**

  | period | R27 | X2 |
  |---|---|---|
  | holdout | +Rs 168/day (173 trades: NIFTY -Rs 1.2k, BANKNIFTY +Rs 43.7k) | +Rs 150/day (155 trades: NIFTY -Rs 8.5k, BANKNIFTY +Rs 45.9k) |
  | 2022 | +Rs 54k | +Rs 41k |
  | 2023 | +Rs 30k | +Rs 46k |
  | 2024 | +Rs 38k | +Rs 39k |
  | 2025 Jan-Sep | +Rs 74k | +Rs 78k |

- **Differences (`results/rebuild.json`):**
  1. **Breadth.** The lake has 59 stocks (NIFTY 50 + NIFTY BANK), not R3's 214 F&O stocks. The 59-stock share at 15:19
     is a proxy, so trade-for-trade identity is not possible. X2's trade list is not in git either.
  2. **Live-only gates.** The app's thin-option, order-flow, kill-switch and day-lock gates are live-only.
  3. **BANKNIFTY lot.** It is 30 from 31 Dec 2025 (NSE). `research/obuy/config.py` still says 35. The 14 Oct sale
     (-Rs 1,859 on 730 - 789) is 30 units.
  4. **Expiry days** come from the data. They differ from the NSE weekday rule only where the data is right: the Diwali
     shift of 3 Nov 2021, and BANKNIFTY's Thursday monthlies of Sep 2023 to Jan 2024.

## Step 2: what the held option does after 09:16 (2021-22 only, 224 trades; `results/describe.md`)

| after 09:16, option vs its 09:16 price | to 09:30 | to 10:00 | to 10:30 | to 11:00 |
|---|---|---|---|---|
| all mornings, mean Rs a lot | +231 | -92 | +7 | +194 |
| gap our way (126) | +361 | -130 | -17 | +177 |
| gap against (98) | +65 | -44 | +38 | +217 |
| 09:15 bar our way (120) | +154 | -314 | -288 | -70 |
| 09:15 bar against (104) | +321 | +159 | +340 | +492 |
| gap and 09:15 bar our way: H2's sign (62) | +287 | -377 | -257 | -103 |
| VIX down at 09:15 (106) | +95 | +184 | +308 | +538 |
| VIX up at 09:15 (115) | +368 | -362 | -265 | -110 |

- **The swings are large both ways.** By 10:30 the index (NIFTY-scaled) touched +20 our way on 82% of mornings and +30
  on 74%. Its median adverse move was -55 points. The option touched +Rs 20 on 74% and -Rs 20 on 71%.
- **The first minute mean-reverts.** "Our way" at 09:15 is worse afterwards.
- **Not tested here; leads for a later pre-registration only:** VIX falling at the open, and the held option's OI rising
  overnight (17 mornings). The first 5 minutes' direction looks strong, but it is known only after 09:20 and mostly
  measures the move already in the price.

## Step 4: results (`results/report.md` has the full tables)

### Walk-forward test years, 2023 to Sep 2025 (472 trades, 678 sessions)

| variant | Rs/trade | vs B0 (total) | 2023 / 2024 / 2025 vs B0 | win % | green days % | worst day | max DD | helped / hurt | gave back | p | BH q |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0 (09:16)** | **301** | | | 55.9 | 54.0 | -15,383 | -39,504 | | | | |
| V1 I20 scaled | 330 | +13,559 | +4.2k / +10.4k / -1.1k | 54.7 | 51.6 | -13,741 | -53,810 | 235 / 237 | 22 | 0.21 | 0.74 |
| V2 I30 scaled | 275 | -12,300 | +1.2k / +0.8k / -14.3k | 51.9 | 50.3 | -13,976 | -62,650 | 217 / 255 | 42 | 0.71 | 0.80 |
| V3 I20 raw | 354 | +24,778 | +5.8k / +10.0k / +9.0k | 56.8 | 54.0 | -14,169 | -47,980 | 243 / 227 | 15 | 0.06 | 0.48 |
| V4 I30 raw | 324 | +10,667 | +4.7k / +5.3k / +0.7k | 54.4 | 51.9 | -14,403 | -51,109 | 233 / 237 | 30 | 0.30 | 0.74 |
| V5 P20 | 296 | -2,498 | +5.9k / +5.4k / -13.7k | 54.0 | 50.0 | -15,187 | -52,073 | 237 / 235 | 32 | 0.55 | 0.74 |
| V6 P30 | 257 | -20,962 | +3.3k / +14.0k / -38.2k | 51.7 | 50.3 | -14,797 | -58,713 | 231 / 241 | 50 | 0.80 | 0.80 |
| V7 H2 | 300 | -863 | +3.5k / -0.3k / -4.0k | 55.5 | 54.3 | -15,383 | -45,294 | 48 / 45 | 4 | 0.53 (twin 0.71) | 0.74 |
| V8 H3 lock | 296 | -2,461 | -0.4k / +0.4k / -2.5k | 55.7 | 51.9 | -15,450 | -52,057 | 197 / 275 | 40 | 0.54 | 0.74 |

- **No variant passed.** Every holding variant has a worse max drawdown than B0, by 15-59%.
- **The best, V3**, holds the BANKNIFTY leg for just +20 raw points, about 9 NIFTY points: a near-immediate exit.
  It made +Rs 52 a trade (p 0.06, BH q 0.48).
- **The anchored walk-forward pick** (V4 every year) added +Rs 10.7k.
- **Counting R3's family** (4,085 earlier overnight tries), every q is 1.00.

### Locked holdout, 1 Oct 2025 to 12 Oct 2026 (173 trades, 252 sessions; information only, since nothing passed)

| variant | Rs/trade | vs B0 | win % | green days % | worst day | max DD | helped / hurt | gave back / rescued |
|---|---|---|---|---|---|---|---|---|
| **B0 (09:16)** | **245** | | 49.7 | 49.2 | -15,115 | -85,200 | | |
| V1 I20 scaled | 219 | -4,576 | 51.4 | 53.3 | -16,182 | -89,050 | 82 / 91 | 8 / 11 |
| V2 I30 scaled | 137 | -18,779 | 49.7 | 50.8 | -16,263 | -89,766 | 77 / 96 | 12 / 12 |
| V3 I20 raw | 215 | -5,248 | 50.3 | 50.8 | -16,182 | -90,276 | 85 / 88 | 7 / 8 |
| V4 I30 raw | 190 | -9,484 | 49.7 | 50.0 | -16,182 | -89,588 | 85 / 88 | 8 / 8 |
| V5 P20 | 173 | -12,445 | 50.3 | 50.0 | -16,481 | -95,430 | 77 / 96 | 10 / 11 |
| V6 P30 | 96 | -25,744 | 46.8 | 44.3 | -16,019 | -94,498 | 75 / 98 | 17 / 12 |
| V7 H2 | 194 | -8,916 | 49.7 | 48.4 | -15,115 | -83,938 | 22 / 23 | 1 / 1 |
| V8 H3 lock | 96 | -25,893 | 55.5 | 55.7 | -16,581 | -98,695 | 60 / 113 | 7 / 17 |

- **Every variant lost to the 09:16 sale, on both indices.**
- **The big overnight winners were mostly kept.** The share of B0's top-decile winners kept was 0.92-1.0, because the
  gap is already in the 09:16 price. The losses come from the ordinary mornings.

## The 14 Oct trade under each variant (an estimate: 13-14 Oct are not in the lake)

**Known facts:**
- BANKNIFTY 55400 CE, monthly, 30 units;
- bought about Rs 789 at 15:20 on 13 Oct, sold Rs 730 at 09:16 on 14 Oct: -Rs 1,859 after charges (about Rs 90);
- BANKNIFTY rose about 215 points by 10:00.

**Assumed:**
- a delta of about 0.55 for a 1-ITM monthly;
- a BANKNIFTY/NIFTY ratio of about 2.2.

**The minute path is not known.**

| variant | if BANKNIFTY rose steadily from 09:16 | if it first dipped by the stop distance |
|---|---|---|
| B0 (what happened) | -1,859 | -1,859 |
| V1 / V2 (target +44 / +66 BN pts) | about -1,140 / -780 | stopped about -2,600 / -3,000 |
| V3 / V4 (+20 / +30 BN pts) | about -1,530 / -1,365 | stopped about -2,200 / -2,350 |
| V5 / V6 (option +20 / +30) | about -1,260 / -960 | stopped about -2,460 / -2,760 |
| V7 H2 | the option opened Rs 59 below its buy, which points to a gap down: most likely sold at 09:16, -1,859 | the same |
| V8 H3 lock | the floor arms near option Rs 822 (BN about +170); sold about Rs 829 on the first pullback, about +1,100 | stop at about Rs 697 (BN about -60), about -2,850 |

So on this one morning a lock would probably have paid, if the dip was small. Over the 173 mornings of the locked year,
Boss's lock made -Rs 25,900 vs the 09:16 sale. The app's own recorder (LiveRecorder) has the real 14 Oct minutes and can
settle the estimate.

## How the app's agents could be tested (SHADOW only, a proposal; nothing built here)

The order flow, consensus vote, trap guard and trade-manager extension need tick and order-book data the history does
not have.

1. **Log at 09:16 each morning Night holds a position**, in the Night log and diagnostics:
   - each agent's view (flow side and strength, consensus vote, trap flag, the trade manager's hold/exit verdict);
   - the 09:15 bar facts R27 used (gap, first minute, VIX change, the held option's OI change);
   - what each R27 variant would do.
2. **Record the held option's price and the index every minute to 10:30** (the recorder already does this).
3. **Act on nothing.** The 09:16 sale stays.
4. **After about 100 mornings** (about 7 months at R3's rate of 1-2 nights a week per index), run the same paired test
   against the 09:16 sale. A random-hold twin is the bar.

A passing agent would get a PAPER switch only: off by default, logged in the Autopilot timeline, undoable in one tap,
never live.

## Honesty notes

- **Variants.** 8 were pre-registered and all are reported. The random twin is a baseline, not a variant.
- **Multiple testing.** BH over the 8; with R3's family (4,085 overnight tries) every q is 1.
- **Data.** The breadth proxy (59 stocks) and the live-only gates mean R27's R3 is not trade-for-trade the app's. The
  per-year and holdout B0 totals match X2 within normal noise.
- **2021-22 was read before the PREREG.** The descriptive step read it, and its in-sample results (p 0.006-0.04) are
  reported but never used to pass anything.
- **No raw data in git.** The lake caches are in `~/.cache/iraalgo/r27`; only small JSON and markdown results are
  committed.
