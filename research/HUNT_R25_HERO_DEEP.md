# R25: the deep hero-zero study (expiry-day Rs 1-10 options), on the 6-year Dhan lake

Written 14 Oct 2026, for Boss's request: "Deep analyse the hero-zero strategies we discussed on Tuesday". On 13 Oct the
NIFTY 22700 CE and 22650 CE ran from a few rupees to about Rs 100 in the afternoon, and the app's Hero arm took no trade.

- **Pre-registration:** `research/hunt/r25/PREREG.md`.
  - Part A, commit `9d04d1b`: the data, the locked holdout, the costs, the gates and the fixed rules, written before any
    outcome was computed.
  - Part B, commit `ff23e7b`: the 3 trigger rules, written after the design-only anatomy and before any rule ran.
  - The design and walk-forward results were committed (`d7c03e6`) before the holdout was read.
- **Code, tests and tables:** `research/hunt/r25/` (`results/report.md` has every table; also `census.json`, `anatomy.md`,
  `case.md`, `holdout.json`).
- **Big tables on Drive:** `gdrive:IraAlgo-data-lake/derived/r25_hero/` (the census and anatomy Parquet files, 23 MB; not
  in git).
- **Experiment entries:** `research/experiments/entries/r25-*.yaml` (9 rules plus the census and the anatomy).
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **What the jackpots have in common.** They are frequent but impossible to pick in advance.
  - On 25% of expiries some Rs 1-10 option ran 10× before 15:05: 119 of 470 design days, and 39 of 148 in the holdout.
  - On 5% it ran 33× (Rs 3 → Rs 100): 25 of 470 and 6 of 148.
  - The jackpots tend to be:
    - the nearer strikes (2-4 strikes OTM, Rs 5-10 rather than Rs 1-3);
    - on a fresh break of the day's high or low or of the opening range;
    - with max pain on the other side;
    - on high-VIX days;
    - earlier in the afternoon.
  - Those states raise the jackpot rate at most 2.3× (from 2.4% to 5.5%). Even then 2 out of 3 such tickets never double.
  - Every state still loses money on average: the best is −Rs 330 per Rs 1,000. The same states come before the big
    losers too.
- **Does any rule make money on unseen data after costs? No.**
  - 0 of 9 rules pass, and 0 of 18 counting R21's 9.
  - The test covered 618 expiries (NIFTY, BANKNIFTY weeklies, SENSEX), with real charges and a conservative spread. The
    locked holdout was 148 expiries of 2021-22, read once.
  - The **lottery basket** (CE + PE near Rs 3 on every expiry, no prediction) loses **52-77% of what it stakes**, in
    every year and in the holdout. At Rs 1,000 an expiry that is about −Rs 380 to −Rs 610 per expiry.
  - The **app's Hero rule** made +Rs 76k in design, but 3 days made all of it. It lost **−Rs 24.6k on 15 holdout
    trades**.
  - The best trigger (T1: a fresh breakout with a near strike) was +Rs 6.5k on 106 holdout trades. But it lost in design
    (−Rs 8.7k on 315 trades), was negative in 2 of 3 walk-forward years, and was no better than random timing (p 0.69).
- **What to avoid.**
  - Rs 1-3 tickets: the worst of all, −Rs 1,128 per Rs 1,000.
  - Strikes 7-10 OTM.
  - Holding to 15:05 hoping for the spike to last. The median 10× option gave back 93% of its peak by 15:05.
  - "Short covering / OI unwinding" as a signal. Jackpots come with OI *rising* on the strike, not falling.
- **What the Hero arm should do: no change.**
  - Keep it on paper only, as it is.
  - Nothing passed the gates, so nothing goes to its paper rules.
  - Not catching 13 Oct was not a fault in the rule. No rule tested here catches such days without paying more on the
    other days than the jackpots return.
  - If Boss wants a "small lottery ticket" for fun, it should be sized as entertainment money: expect to lose most
    of it over time (half to three-quarters of every rupee in this data).

## Data

- **Source:** the Dhan lake on Drive, read straight from its Parquet (`r25data.py`):
  - `options_rolling_1m/opt_{NIFTY,BANKNIFTY,SENSEX}_WEEK_*`: the nearest expiry, ATM-10 .. ATM+10 strikes chosen at
    each minute;
  - index and India VIX minutes.
- **Expiry days come from the data**, since the files have no expiry column. A day is an expiry when the ATM time value
  at 15:15 is below 0.6% of spot and the next session's is more than twice it.
  - It matches the brain's calendar on every NIFTY date except 30 Dec 2025, which the calendar misses.
  - For BANKNIFTY it gets the Sep 2023 – Jan 2024 Thursday monthlies right, where the calendar's weekday rule is wrong.
- **Days:**
  - NIFTY: 271 expiries, 5 Aug 2021 – 6 Oct 2026.
  - BANKNIFTY: 172 weekly expiries, 5 Aug 2021 – 13 Nov 2024. The monthlies after that are out of scope.
  - SENSEX: 175 expiries, 19 May 2023 – 8 Oct 2026.
- **13 Oct 2026 is not in the lake.** It ends on Mon 12 Oct 2026. 13 Oct is treated below from Boss's screenshots and
  R21's notes.
- **Data-quality fixes**, all found in design data before any rule ran (PREREG Part B):
  - Mislabelled rows dropped. The SENSEX file holds BANKEX rows on 5 Apr 2024, with their own spot.
  - Freak prints capped with a same-minute rule: a further-OTM option can't trade above a nearer one. This changed
    0.006-0.19% of bars.
  - A target fills only on a confirmed price.
  - **SENSEX on 2 Feb 2024** (11:57-12:35) had disorderly trading: every put far above fair value for minutes. It stays in
    the data and is named wherever it shows up.
- **Coverage gap (stated honestly).** Only ATM±10 strikes are stored.
  - In design, the whole Rs 1-10 band on one side lay beyond the stored strikes in 4.3% of call-minutes and 5.6% of
    put-minutes, 11:00-15:00. By index:

    | index | call-minutes | put-minutes |
    |---|---|---|
    | NIFTY | 0.4% | 0.2% |
    | BANKNIFTY | 1.8% | 1.7% |
    | SENSEX | 10.2% | 14.1% |

  - In 54-57% of minutes, some strikes cheaper than the outermost stored one (but still above Rs 1) lay beyond range.
    These are mostly Rs 1-2 strikes far OTM, which are the worst bucket anyway.
  - **Hidden jackpots** (a lower bound): 17 strikes on 5 design days (BANKNIFTY 11, SENSEX 6, NIFTY 0) were first seen
    at the edge above Rs 10 and then ran ≥ 5×. Their cheap phase was unseen. The holdout has 0.
  - So the census slightly undercounts far-OTM jackpots, mainly for SENSEX. It does not change any rule result: every
    rule buys the nearest eligible strike or one priced near Rs 3, which is inside the range.

## Step 1: census

All expiry days. Design: 470 expiries, 2023 – 12 Oct 2026. Holdout: 148 expiries, 2021-22, added after the read.

- **Unit:** each (expiry, strike, side) gets one unit per hour slot (11:00, 12:00, 13:00, 14:00). It starts at the first
  minute its traded close is Rs 1-10. "Reached k×" uses the best high after the start.

**Days on which some Rs 1-10 option reached k× before 15:05 (before the close in brackets; close-confirmed in the last
column):**

| | 5× | 10× | 20× | 33× (Rs 3 → 100) | close-confirmed 10× / 33× |
|---|---|---|---|---|---|
| design, 470 days | 257 (55%) [271] | 119 (25%) [134] | 52 (11%) [63] | 25 (5.3%) [31] | 105 / 18 |
| NIFTY 197 | 109 | 45 | 19 | 12 | 41 / 9 |
| BANKNIFTY 98 | 49 | 23 | 12 | 5 | 22 / 4 |
| SENSEX 175 | 99 | 51 | 21 | 8 | 42 / 5 |
| holdout, 148 days | 71 (48%) | 39 (26%) | 17 (11%) | 6 (4%) | 37 / 5 |

**Per unit** (design 27,169 units, holdout 8,617):

| | 5× | 10× | 20× | 33× | mean net held to 15:05, per Rs 1,000 | units profitable |
|---|---|---|---|---|---|---|
| design | 6.2% | 2.6% | 1.1% | 0.46% | −Rs 919 | 3.0% |
| holdout | 6.6% | 2.8% | 0.6% | 0.19% | −Rs 911 | 3.2% |

**Splits** (design, share of units reaching 10×, then mean net per Rs 1,000 held to 15:05):

| split | values |
|---|---|
| index | NIFTY 2.8% (−737), BANKNIFTY 2.4% (−1,050), SENSEX 2.5% (−1,025) |
| year | 2023 1.7%, 2024 3.6%, 2025 2.1%, 2026 2.8% |
| VIX at the start | < 13: 2.0% (−963); 13-17: 2.6% (−939); 17-22: 5.0% (−733); ≥ 22: 6.7% (−128; only 465 units on 9 days) |
| slot | 11:00 3.3%, 12:00 2.6%, 13:00 2.5%, 14:00 2.2% |
| price at the start | Rs 1-3: 1.8% (−1,128); Rs 3-5: 2.7% (−972); Rs 5-10: 3.3% (−716) |
| strikes OTM | 1: 2.3% (−416); 2-3: 3.9-4.0% (−580 / −670); 4-6: 2.3-3.2%; 7-10: 1.8-2.1% (−1,093 to −1,151) |
| weekday | Tue 2.0%, Wed 2.6%, Thu 2.7%, Fri 3.0% (Mon: 4 days only) |
| side | calls 3.1%, puts 2.1% |

**How long the spike lasts** (design, 707 units that reached 10×, on 119 days):
- they traded at or above half their peak for a median **12 minutes** (quartiles 4-27);
- the peak came a median 90 minutes after the start;
- the median give-back from the peak to 15:05 was **93%**;
- only **46%** were still worth ≥ 2× their start at 15:05, and 55% ≥ 1×.
- The holdout is the same: 244 units, median 14 minutes, give-back 89%, 50% still ≥ 2×.

## Step 2: anatomy

- **Rows:** 173,624 decision rows on 467 design days: every 5 minutes, 11:00-14:55, every eligible Rs 1-10 OTM option.
- **Features** are point-in-time. The look-ahead guard (future blanked, and future absurd) gives identical values: tested
  on synthetic days and on a real NIFTY day.
- **Base rates:** jackpot (≥ 10× before 15:05) 2.42%, dud (never 2×) 80.9%. Mean net per Rs 1,000: held to 15:05
  −Rs 678, half sold at 5× −Rs 680.

**States ranked by LIFT, shown with the loss side and the money** (`results/anatomy.md` has all of them):

| state (tercile of the design rows) | rows / days | jackpot % (lift) | dud % (lift) | Rs per 1,000, hold / half 5× |
|---|---|---|---|---|
| strike near in expected-move units (≤ 1.44 EM) | 57,875 / 445 | 3.8% (1.56) | 71.7% (0.89) | −493 / −520 |
| strike near in day-range units (≤ 1.08 × range) | 57,875 / 464 | 3.6% (1.47) | 72.4% (0.90) | −477 / −507 |
| index beyond the opening range on the option's side | 59,943 / 435 | 3.5% (1.43) | 75.3% (0.93) | −669 / −624 |
| VIX in the top third (≥ 13.8) | 57,790 / 201 | 3.5% (1.43) | 78.0% (0.96) | −665 / −658 |
| max pain on the other side of spot | 57,878 / 464 | 3.4% (1.41) | 76.8% (0.95) | −635 / −619 |
| near (range) & early (≥ 200 min left) | 7,354 / 294 | 5.5% (2.29) | 67.3% (0.83) | −365 / −360 |
| opening-range break & VIX falling | 18,183 / 224 | 5.3% (2.19) | 75.1% (0.93) | −767 / −668 |
| near (EM) & max pain behind | 18,418 / 430 | 5.2% (2.16) | 67.6% (0.84) | −372 / −403 |
| near (EM) & opening-range break | 19,257 / 396 | 5.1% (2.12) | 65.4% (0.81) | −490 / −454 |
| **lowest lift:** far in day-range units | 57,875 / 393 | 1.0% (0.42) | 89.7% (1.11) | −822 / −817 |

What it means:
- **Momentum, speed and volume bursts barely matter on their own.**
  - 15-minute move with the option in the top third: lift 1.14.
  - Volume burst: lift about 1.0.
  - Moves after a breakout are as likely to stall as to run.
- **OI.** Own-strike OI rising has lift 1.23, and falling ("unwinding") 0.68. Other-side OI rising has lift 1.22.
- **IV.** Low IV change is slightly better (1.26). High IV is worse (0.72).
- **Distance is the strongest single factor**, mostly because near strikes cost more, so they die slower and need a
  smaller move.
- **No state, or pair of states, has positive expectancy.** The best pair loses Rs 330-365 per Rs 1,000. That is better
  than the base −Rs 678, but it is still a loss before any timing skill.

## Step 3: can it be traded?

Nine rules were pre-registered:

| id | rule | budget | exits |
|---|---|---|---|
| H0 | **the app's Hero arm today** (straddle ≥ 15% + 0.25% momentum, 13:30-14:45, Rs 1-5), NIFTY only | Rs 5,000 | app: half at 5×, rest 20× / 15:05, −60% stop |
| L1 / L2 | **lottery basket at 13:30**: the CE and PE nearest Rs 3 (Rs 1-5), every expiry | Rs 500 a side | L1 hold to 15:05; L2 Boss's ladder: half at 3×, then step locks 5× → 2.5×, 10× → 5×, 20× → 10× |
| L3 / L4 | **lottery basket at 14:30** | Rs 500 a side | as L1 / L2 |
| V4 | R21's best design variant (one-sided 2.5× + momentum, to 15:00, Rs 1-15) | Rs 5,000 | app |
| T1 | **fresh day-high (CE) / day-low (PE) break within 15 min + strike within 1.25 expected moves**, 11:00-14:30 | Rs 1,000 | app (chosen) |
| T2 | strike within 1.08 × the day's range + max pain ≥ 0.058% behind, 11:00-13:30 | Rs 1,000 | app (chosen) |
| T3 | **15-min momentum ≥ 0.25% + beyond the opening range + other-side OI building**, 13:00-14:45 | Rs 1,000 | app (chosen) |

- **The stop.** For these options the stop is the premium itself: the most a ticket can lose is what was paid plus the
  buy charges. The app exits add the −60% close stop.
- **Costs:**
  - Rs 20 an order, STT 0.15% of the sell, exchange, SEBI, stamp and GST;
  - fills at the next bar's open + 5 bps + a half-spread of 1 / 2 / 4 ticks by the strike's 5-minute volume. At Rs 1-5,
    one tick is 1-5% of the price. The lake has no bid / ask, so this is a model: conservative for NIFTY, maybe light
    for thin SENSEX strikes. The stress test doubles it.
  - Entries are a limit capped at 1.2 × the signal price + 0.10, worked for 3 minutes, never chased.
  - A ticket that expires worthless sends no sell order.

### Design: 470 expiries, 2023 – 12 Oct 2026

| rule | trades | hit % | avg win / loss | net Rs | per expiry | per Rs 1,000 risked | per lot (net) | green expiries | worst day | max DD | losing streak (trades) | best day's share | net without best day |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| H0 | 33 | 12.1 | +41,488 / −3,089 | **+76,379** | +388 | +470 | −2,592 | 2.0% | −3,552 | −30,210 | 8 | 75.5% | +18,684 |
| L1 | 724 | 3.6 | +5,008 / −441 | −177,392 | −377 | −523 | −72,516 | 4.5% | −1,032 | −179,194 | 95 | — | −207,915 |
| L2 | 724 | 10.8 | +1,060 / −444 | −204,261 | −435 | −602 | −77,311 | 10.9% | −1,032 | −204,832 | 32 | — | −219,734 |
| L3 | 732 | 5.2 | +2,073 / −420 | −212,662 | −452 | −610 | −74,035 | 6.0% | −1,042 | −212,662 | 72 | — | −237,166 |
| L4 | 732 | 10.8 | +772 / −426 | −216,840 | −461 | −622 | −71,974 | 12.1% | −1,042 | −216,840 | 36 | — | −224,196 |
| V4 | 153 | 11.1 | +23,441 / −3,183 | −34,403 | −73 | −46 | −13,512 | 3.6% | −3,955 | −115,143 | 20 | — | −92,179 |
| T1 | 315 | 12.4 | +3,872 / −579 | −8,721 | −19 | −32 | −25,238 | 8.3% | −1,388 | −39,750 | 27 | — | −20,845 |
| T2 | 561 | 9.8 | +4,030 / −582 | −72,627 | −155 | −148 | −50,282 | 11.5% | −1,388 | −86,213 | 48 | — | −85,270 |
| T3 | 114 | 7.9 | +4,733 / −635 | −24,054 | −51 | −229 | −13,843 | 1.9% | −1,439 | −35,764 | 44 | — | −35,010 |

- **H0's design profit is three days:** 16 May 2024 (+57.7k), 12 Sep 2024 (+56.5k) and 22 Feb 2024 (+43.5k). Without
  them it is −Rs 81k. Per lot (1 lot a trade, so a 5× target sells everything) it loses.
- H0's 2× spread net is +72k. The rest are in `results/report.md`.

### Walk-forward (anchored; T rules re-chosen each fold on the earlier years only)

| rule | 2024 | 2025 | 2026 | sum | years > 0 |
|---|---|---|---|---|---|
| H0 | +119,755 (15) | −17,088 (9) | −6,833 (3) | +95,835 | 1 |
| L1 / L2 | −2,446 / −38,710 | −51,892 / −50,934 | −35,048 / −33,414 | −89,386 / −123,059 | 0 |
| L3 / L4 | −42,507 / −60,812 | −58,004 / −52,941 | −24,309 / −23,485 | −124,819 / −137,239 | 0 |
| V4 | +129,560 (50) | −5,472 (23) | −60,687 (29) | +63,400 | 1 |
| T1 | −54,123 (81) | +7,807 (78) | −7,668 (44) | −53,984 | 1 |
| T2 | −77,494 (127) | −411 (98) | −11,504 (85) | −89,408 | 0 |
| T3 | −14,358 (105) | −1,550 (15) | −7,329 (14) | −23,237 | 0 |

H0's and V4's walk-forward profit is all 2024, the year of 12 Sep, 16 May and 14 Feb. T1's one positive year is 2025
(+Rs 7.8k).

### HOLDOUT: 148 expiries (NIFTY 74, BANKNIFTY 74; 5 Aug 2021 – 29 Dec 2022), read once (14 Oct 2026 02:42 UTC)

| rule | trades | hit % | avg win / loss | net Rs | per expiry | per Rs 1,000 | per lot | green expiries | worst day | max DD | losing streak | net w/o best day | 2× spread | p (test) | BH q (18) | passes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| H0 | 15 | 13.3 | +9,058 / −3,288 | −24,624 | −333 | −332 | −926 | 2.7% | −3,552 | −34,198 | 7 | −34,198 | −26,017 | 0.991 (time twin) | 1.00 | no |
| L1 | 253 | 2.8 | +2,480 / −440 | −90,750 | −613 | −768 | −32,440 | 2.7% | −1,035 | −95,622 | 99 | −98,113 | −93,265 | 1.000 (bootstrap) | 1.00 | no |
| L2 | 253 | 9.5 | +742 / −447 | −84,467 | −571 | −715 | −31,023 | 12.8% | −1,035 | −87,096 | 44 | −87,096 | −87,514 | 1.000 | 1.00 | no |
| L3 | 257 | 5.8 | +1,597 / −408 | −74,852 | −506 | −618 | −26,346 | 7.4% | −970 | −80,150 | 80 | −79,854 | −77,288 | 1.000 | 1.00 | no |
| L4 | 257 | 8.9 | +655 / −411 | −81,103 | −548 | −670 | −29,090 | 10.1% | −970 | −84,053 | 45 | −83,802 | −83,752 | 1.000 | 1.00 | no |
| V4 | 86 | 17.4 | +12,668 / −3,112 | −30,897 | −209 | −74 | −3,385 | 10.1% | −4,067 | −80,689 | 20 | −60,332 | −32,410 | 0.889 | 1.00 | no |
| T1 | 106 | 19.8 | +2,799 / −615 | **+6,530** | +44 | +69 | +3,315 | 14.2% | −1,321 | −18,765 | 12 | **−2,637** | +6,312 | 0.686 | 1.00 | no |
| T2 | 170 | 13.5 | +2,649 / −606 | −28,211 | −191 | −184 | −7,828 | 15.5% | −1,346 | −38,822 | 27 | −36,403 | −29,695 | 0.726 | 1.00 | no |
| T3 | 76 | 21.1 | +2,034 / −620 | −4,627 | −31 | −67 | +874 | 10.8% | −1,234 | −18,359 | 20 | −13,879 | −7,962 | 0.881 | 1.00 | no |

- **Gates** (all must hold): design net > 0; walk-forward sum > 0 with ≥ 2 of 3 years positive; holdout ≥ 30 trades and
  net > 0; BH q < 0.05; 2× spread and out-of-range = 0 still positive; positive without the best day; max drawdown under
  Rs 40k.
  - H0 fails gates 2-6.
  - T1 fails gates 1, 2, 4 and 6.
  - Everything else fails nearly all of them.
- **BH family (18):** R21's 9 p-values were 0.70-1.00, except V8 at 0.006. The smallest q in the family is 0.108
  (R21-V8, a blind control that was only "less bad"). Every R25 q is 1.00.
- **Controls:**
  - **Random-time twins** (same days, same side, same strike rule, same budget, a random minute) did *better* than the
    triggers: their mean was +Rs 39k for H0, +42k for V4, +13k for T1 and +8k for T3. The trigger moment adds nothing.
  - **Random-strike twins** (same minute, a random strike in the band) did *worse* (p 0.00-0.21). That only confirms the
    anatomy: the nearer strike loses less than a random far one. It is not an edge.
- **Lottery dependence:** no rule is positive on the holdout without its best day.
- **Exits:** the same entries with each exit, holdout net Rs (`results/report.md` also has the design grid):

  | entries | hold 15:05 | half 3× | half 5× | ladder | rupee trail | app |
  |---|---|---|---|---|---|---|
  | H0 | −55,201 | −27,382 | −34,887 | −20,491 | −6,143 | −24,624 |
  | L1/L2 (13:30) | −90,750 | −88,687 | −86,465 | −84,467 | −70,059 | −61,498 |
  | L3/L4 (14:30) | −74,852 | −77,904 | −77,711 | −81,103 | −69,009 | −64,046 |
  | V4 | −130,264 | −93,335 | −52,938 | −98,686 | −78,761 | −30,897 |
  | T1 | +399 | −17,671 | −1,047 | −9,291 | −11,086 | +6,530 |

  - The app's −60% stop is the least bad exit almost everywhere. A lottery ticket cut at −60% keeps 40% of the dead
    premium, and that beats any lock.
  - The rupee trail is best for H0 on the holdout (−6k) but worst in design (−65k), so it is not stable.
  - Hold-to-15:05 is the worst exit on the holdout for H0, L1/L2 and V4. For L3/L4 it is mid-table, and for T1 it is
    second best (+0.4k).
  - Exit-grid BH (54 rule × exit cells): the smallest p is 0.34 (T1 with app exits), q 1.00.

## Step 4: 13 Oct and the 10 biggest jackpot days

### 13 Oct 2026 (no minute file: from Boss's screenshots and R21's notes, so every line is an estimate)

The day: NIFTY rallied 22,529 → 22,722 after 13:30, and the 22650 CE went from about Rs 3.75 to a 100.45 high. In R21's
notes:
- the 22700 CE had a low of 1.45, was 4.15 at 14:30, peaked at 59.4 and was 21.8 at 15:05;
- the 22750 CE was 0.75 at 13:30 and peaked at 22.6;
- the straddle peaked at +13.7% at 14:36; the first 0.25% 15-minute move came at 14:40.

What each rule would have done:
- **H0:** no trade. The straddle never reached +15%, exactly as happened.
- **L1 (13:30, the call nearest Rs 3):**
  - It would probably have bought the 22650 CE (about Rs 3-3.75), or the 22700 CE if the 22650 was above Rs 5 then.
    Held to 15:05 that is roughly 15-20×: about +Rs 5,000 to +8,500 on Rs 500.
  - The put (about Rs 3 → near 0) loses about Rs 500.
  - **L1 would have caught 13 Oct.**
- **L2:** the same entries; the ladder sells half at 3× and locks the rest at 5× / 10× of the fill on the way up, so
  roughly half of L1's gain.
- **L3 / L4 (14:30):** the call nearest Rs 3 was probably the 22700 CE at about 4.15 (1 lot of 65). It was worth 21.8 at
  15:05: about +Rs 1,000.
- **V4 and T3:** momentum came only at 14:40, so at best a late entry on the 22700 / 22750 CE.
- **T1:** it fires on the first new day high after 13:30, if the rally took out the morning high. With the strike within
  1.25 expected moves, that is the 22700 CE at around Rs 2-4, so it probably catches part of the move. That can't be
  checked without the day's minutes.
- **T2:** its window ends at 13:30, before the move. No trade.

**What a "catch it next time" rule would have needed to know, and when.** At 13:30 it would have needed to know that a
one-way +0.85% rally was coming in the next 90 minutes. Nothing measured at 13:30 tells that (Step 2). The only way to
be sure of holding a ticket on such a day is to hold one on every expiry (L1). Over 6 years L1's losing tickets cost about Rs 2,400 (design) to Rs 6,200 (holdout) for every Rs 1,000 its winning
tickets bring back:
- design: 26 winning tickets averaging +Rs 5,008, against 698 losing ones averaging −Rs 441;
- holdout: 7 winners averaging +Rs 2,480, against 246 losers averaging −Rs 440.

### The 10 biggest design jackpot days

From `results/case.md`, by the best multiple of any Rs 1-10 unit before 15:05:

| day | jackpot | rules that traded it (net Rs) |
|---|---|---|
| 12 Sep 2024 NIFTY | 25250 CE 1.05 → 163 (156×), from 14:00 to the 15:05 high | H0 +56,452; V4 +57,776; L1 +30,933 (CE) −410 (PE); L3 +13,239; T3 +10,956; T1 +5,881 (CE) −678 (PE) |
| 14 Feb 2024 BANKNIFTY | 46100 CE 1.9 → 157 (83×), peak 14:46, back to 3.8× by 15:05 | V4 +55,824; T2 +1,491; L2 +953; L1 −363 |
| 2 Feb 2024 SENSEX | 71900 PE 9.05 → 717 (79×), the disorderly 12:31 spike | T1/T2 +10,082 (a CE, earlier); V4 −3,683 |
| 17 Apr 2025 NIFTY | 23750 CE 1.7 → 124 (73×), from 11:00 | T1 +11,376; H0 −3,128; every lottery leg lost |
| 18 Apr 2024 NIFTY | 21950 PE 1.3 → 94 (73×), peak 13:28, back to 1× by 15:05 | T2 +7,847 (PE); H0 −3,352; V4 −3,352 |
| 2 Apr 2026 SENSEX | 72600 CE 9.15 → 634 (69×) | T1 +12,059; V4 +8,405; T3 +4,558 |
| 5 Mar 2026 SENSEX | 79700 CE 9.25 → 607 (66×), from 14:11 | none won (L2 +536) |
| 7 Sep 2023 NIFTY | 19700 CE 1.15 → 75 (66×) | L1 +6,775; L2 +3,779; H0 −3,333 |
| 16 May 2024 NIFTY | 22350 CE 1.15 → 74 (65×), from 14:00 | H0 +57,695; V4 +55,012; L3 +24,986; L1 +11,486 |
| 16 Jun 2023 SENSEX | 63400 CE 1.4 → 80 (57×) | L1 +8,226; T1 +6,587 |

- **No rule caught most of them.** H0 caught 2 of the 10 and lost on 3.
- Each rule's big days are a different set. That is what a coin-flip edge looks like.
- In 3 of the 10 the spike was over well before 15:05: 14 Feb, 2 Feb and 18 Apr. The holdout's biggest days were 25 Aug and
  16 Jun 2022 (NIFTY, 52× and 51×).

## Honest notes

- **Holdout.** No earlier locked holdout of the hero family lies in 2021-22. But OBUY's walk-forward showed the app's
  hero_zero rule on test year 2022, and R24's design mining covered 2021-22 at ATM±3 (PREREG table). So H0's holdout
  line is not virgin for 2022. It is the R25 rules that are new there. The holdout has no SENSEX.
- **Spread model.** The lake has no bid / ask. The 1-4 tick half-spread is a model, and real Rs 1-3 NIFTY options are
  often 1 tick wide. Halving the spread would not save the lottery: doubling it moves the lottery nets by only 2-4%.
  Brokerage (Rs 20 an order) and theta are the main costs at these sizes.
- **Out-of-range prices.**
  - About 17% of census units (4,692 of 27,169) needed the out-of-range proxy at some minute (an upper bound), and every
    rule was also run with it at 0. The nets moved by under Rs 2.5k.
  - Some holdout days have missing early index minutes (the opening-range read is then NaN, so no T3 entry). Harmless.
- **Per-lot numbers** differ in sign from the budget numbers for H0: with 1 lot, the 5× target sells the whole position,
  so the 20× runners are lost.
- **What was not tested:**
  - option selling and spreads on expiry day: a different family;
  - BANKNIFTY monthlies after Nov 2024;
  - 13 Oct itself (not in the lake). A phone recording of 13 Oct could be rebuilt into
    a day file to check the estimates above exactly, if the phone recorded that day (not checked here).

## Checks run

- `python -m pytest research/hunt/r25/test_r25.py -q` passes 28 tests: 27 on synthetic days and 1 on real lake days (one per index). They cover:
  - the look-ahead guard (future blanked and future absurd) for every fixed rule, every trigger and every anatomy
    feature;
  - expiry detection, the bad-print cleaner and the design/holdout split;
  - entries never chasing, sizing, worthless expiry, every exit, freak prints filling no target, and the out-of-range
    proxy;
  - market closed / empty / stale days, Hero being NIFTY-only, the lottery pick, BH, the bootstrap, the metrics, the
    census and the trigger selection rule.
- The real-day test runs the guard for every rule and the anatomy features on one design day per index: 0 mismatches.
- Found in self-review: the masked copy shared the full day's trigger cache, so the trigger guard could pass trivially.
  Fixed (`masked()` drops per-day caches; a test checks it) and re-run: still 0 mismatches. No study result used masked
  days, so no number changes.
- The brain tests need the pinned library versions (`research/brain/requirements.txt`). Under this container's pandas 3 /
  pyarrow 26, 4 tests fail for reasons unrelated to R25. With pandas 2.2.3 / numpy 2.1.3 / scipy 1.14.1 / pyarrow 18.1
  (the nearest pyarrow build for Python 3.13), all 40 pass.
- The brain checks: `python -m pytest research/brain/tests -q` and `python -m research.brain.features catalog --check`.
- `python research/experiments/validate.py`, and `python3 research/knowledge/build_pack.py` then `--check`.
- The holdout was read once. `study.py holdout` refuses a second read (a flag file plus `results/holdout.json`).
