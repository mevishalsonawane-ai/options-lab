# HUNT h42: the question engine. 1,296 questions about how the market moves, answered with numbers

Written 2026-10-08. Option BUYING only: 1-ITM, nearest expiry, 1 lot at today's lot size, Rs 1 lakh.

**Files:**
- Code: `research/hunt/h42/`
  - `questions.py` writes `questions.csv`.
  - `build.py` and `tables.py` build the data.
  - `answer.py` answers every question.
  - `report.py` writes `answers.csv`.
  - `make_prereg.py` writes `PREREG.md` and `prereg.json`.
  - `holdout.py` runs the holdout.
  - `audit.py` is the look-ahead audit.
  - `diag_vix.py` holds the diagnostics that found a bug.
- **Full answers table:** [`research/hunt/h42/answers.csv`](hunt/h42/answers.csv). It has 1,296 rows (0.75 MB), one plain-language answer per question, with every number.
- The questions were written before any answer was computed: [`research/hunt/h42/questions.csv`](hunt/h42/questions.csv). Each question has an id, category, question, how it is measured and the data split.
- Holdout: `PREREG.md`, `holdout_hold.csv` (every exit) and `prehold_rules.csv`.

## Verdict (read this first)

**NO. Asking 1,296 questions finds hundreds of TRUE facts about how Indian indices move, but none of them turns into an option buy that beats costs. Rs 5,000/day: NO.**

1. **The market does have real, repeatable habits.**
   - **327 of 1,296 answers are statistically significant after the Benjamini-Hochberg (BH) correction.** 310 of those point the same way in both halves of 2020-2025.
   - Examples:
     - Big gaps usually do NOT fill the same day.
     - An early day-high usually holds into the afternoon.
     - The first 40 minutes move about 50% more than midday.
     - The ATM straddle loses about 82-88% of its value on expiry day.
     - Volatility clusters.
2. **Almost none of them pays an option buyer.**
   - We turned every answer that implies a direction or a bigger move into a trade:
     - 1,172 tradable questions;
     - 38 exits each: targets +15/20/25/30 x stops -10/15/20 x 15/30/60-minute time stops, the Liquidity-arm exit, and the question's own horizon;
     - exits run on the option's 1-minute HIGH and LOW;
     - costs are app fills and charges plus the measured real spread.
   - Results over the **44,536 option tests:**
     - Only 3.8% are net positive.
     - Only 30% are even gross positive.
     - The median test loses Rs 170 a trade.
     - **The smallest BH q of any option test is 1.0. Not one survives.**
3. **Candidates: 0 strict and 6 loose. All 6 lost in the locked holdout.**
   - A loose candidate is a significant fact whose best exit made money before the holdout.
   - In the holdout (Oct 2025 to Oct 2026), the six together made **-Rs 518/day net** at 1 lot each.
   - None beat random entries at the same minute (p = 0.23 to 0.94).
4. **A warning from this hunt: two "winners" were a look-ahead bug.**
   - The first run had two extra rules: "India VIX drops in the first 15 minutes, so buy a PE".
   - They "passed" the holdout at +Rs 553/day (NIFTY) and +Rs 622/day (BANKNIFTY), with p = 0.003 and 0.0005 against random.
   - The diagnostics showed the edge came from 09:20/09:25 entries. There, `s - 15` was negative, and Python's negative index read the VIX at **15:20, the end of the same day**.
   - After the fix, both rules vanished before the holdout.
   - A perturbation audit (`audit.py`) now passes. It scrambles all data after 12:35 and checks that every feature before 12:30 is unchanged across 167 features x 5 indices.
   - That run is void. Its files are kept in `scratchpad/hunt/h42/run1_buggy/`.
   - **Any "edge" bigger than about Rs 200/day at 1 lot should be presumed a bug until audited.**

## What the engine did

| step | what | size |
|---|---|---|
| 1. Questions | 23 categories, generated from templates, specific and measurable without look-ahead, written before any answer | 1,296 questions |
| 2. Answers | Only data before 2025-10-01. Effect is condition vs the other days or minutes. Each answer has a day-clustered 95% CI, a p-value, BH q over all 1,296, and a split-half stability check | 327 significant (q < 0.05), 310 also stable |
| 3. Option translation | Side taken from the pre-holdout answer (CE, PE, or both for "bigger move" answers). 1-ITM nearest expiry, bought at the next minute's open. 38 exits. App charges plus the h24 real half-spread (NIFTY/BN 0.16%, MIDCP 0.21%, FIN 0.42%, SENSEX 0.20%) on both sides. Compared with a random entry at the same index, minute and side | 44,536 option tests, 0 pass BH |
| 4. Candidates | Strict: question q < 0.05 AND option q < 0.05. Loose: question q < 0.05, stable, best exit net > 0 with raw p < 0.05, and better than same-minute random | 0 strict, 6 loose |
| 5. Holdout (once) | 2025-10-01 to 2026-10-06, pre-registered exit, one position at a time, random baseline matched by minute (B = 2,000) | 0 of 6 pass |

**Categories and how many answers were significant after BH:**

| category | answers significant / asked |
|---|---|
| time of day | 39/90 |
| gaps | 17/80 |
| momentum vs reversal | 8/80 |
| VIX | 17/80 |
| option premium | 16/79 |
| prior day | 30/70 |
| candle streaks | 8/70 |
| option +20 jumps | 28/70 |
| day high/low timing | 40/65 |
| day of week | **0/60** |
| theta / straddle decay | 51/55 |
| constituents | 2/54 |
| opening move | 9/50 |
| expiry proximity | 25/50 |
| first hour | 8/50 |
| levels | 5/50 |
| cross-index | 1/48 |
| trend | 4/40 |
| round numbers | 5/40 |
| max-OI pin | 1/30 |
| calendar | 3/30 |
| OI / PCR | 6/30 |
| volume | 5/25 |

Data:
- Index minutes: NIFTY from Aug 2020, BN and FIN from Aug 2021, MIDCP from 2022, SENSEX from 2023.
- Daily candles from 2006. FINNIFTY's 2006-2011 daily rows are flat placeholders and were dropped.
- Option minutes with OI and volume, and India VIX minutes from Oct 2021.
- 19 constituents from Oct 2024. So the constituent questions have one pre-holdout year.

## The 30 most useful answers (all on data before 2025-10-01)

"vs" = the other days or minutes. **Option** = the best of 38 exits for the trade the answer implies, net Rs per trade at 1 lot. With 38 exits to choose from, even that best number is flattering.

**Opening and gaps**
1. **The first 15 minutes do not set the day's direction.** If BANKNIFTY falls more than 0.4% in the first 15 minutes, it closes above the open only **20.8%** of the time, vs 51.9% on other days (n = 106). That is only because it is already down.
   - From 09:30 to 15:09 it keeps falling just **47%** of the time, a coin flip (q = 0.86). Rises behave the same way: 53%.
   - Option: buying a PE at 09:30 loses Rs 128 a trade.
2. **Big gaps usually do NOT fill.**
   - NIFTY gap-ups above 0.5% touch the previous close the same day only **34%** of the time, vs 73% for smaller or no gaps.
   - BANKNIFTY 40% vs 75%. FIN, MIDCP and SENSEX are 25-37%.
   - Trading the fill loses Rs 106-363 a trade.
3. **Big gap-downs bounce first.** After a gap-down above 0.5%, the first 15 minutes are up:
   - **68%** of the time for NIFTY (vs 43%);
   - 62% for BANKNIFTY;
   - 86% for SENSEX (n = 35).
   - The bounce is too small for a CE: -Rs 290 to -342 a trade (SENSEX +314, on 35 trades).
4. **On gap-up days the high is NOT set early any more often.** The day's high comes in the first 30 minutes on 31% of NIFTY gap-up days, vs 34% on other days (n.s.).
   - On any day, the high falls in the first 30 minutes about a third of the time, and so does the low.
5. **An early high usually holds.**
   - If at 12:30 NIFTY's high so far was made in the first 15 minutes, it stays the day's high **75%** of the time, vs 45%.
   - At 14:00: **86%** vs 55%. BANKNIFTY: 76% and 87%.
   - But buying a PE on it loses: the index usually drifts rather than falls.

**Time of day, day of week, expiry**

6. **The first 40 minutes are the most active.** BANKNIFTY's 15-minute moves average **15.5 bps** from 09:20 to 10:00, vs 10.7 at other times. 11:00-13:00 is the quietest (9.8 bps). The last hour picks up again.
   - MIDCP: 18.7 vs 10.8.
   - Buying both CE and PE at 09:20 still loses about Rs 310 a trade.
7. **On expiry days, the biggest 60-minute moves start 09:20-10:00.** BANKNIFTY 29.6 bps vs 21.6 at other expiry hours; NIFTY 21.0 vs 17.0. Midday on expiry is quieter than the expiry average.
8. **Day of week: nothing.** 0 of 60 weekday questions survive BH, in 2020-25 or since 2006. Mondays, Fridays and Thursdays show no reliable up or down bias.
9. **Expiry-day theta is brutal.** The ATM straddle loses **82%** (NIFTY) to 88% (MIDCP) of its 09:20 value by 15:09 on expiry day, vs 11-13% on other days.
   - 3-4 days before expiry it loses only about 8-9% a day.
10. **Theta by the hour.** NIFTY's ATM straddle changes over the next 60 minutes by:
    - **-3.6%** when starting 11:00-12:00;
    - **-13.6%** when starting 14:00-14:55;
    - **-22.6% per hour** on expiry day.
    - Buying options in the last hour fights the steepest decay of the day.

**Prior days, volatility regime**

11. **A big down day is followed by a big day.** After NIFTY fell more than 1%, the next day's range averages **2.37%** vs 1.33% (daily data since 2006). BANKNIFTY: 2.77% vs 1.79%.
    - After 3 or more up days in a row, the range shrinks: 1.21% vs 1.54%.
12. **VIX sets the size of the day.** With VIX below 13 yesterday, NIFTY's range is **0.74%** vs 1.15%. With VIX at 20 or more, it is **1.44%** vs 0.94%.
    - The direction is not set by VIX.
13. **"When India VIX rises more than 5% intraday, does the index fall more?" No.** NIFTY's next 60 minutes: -0.6 bps vs +0.05 (q = 0.80). VIX moves WITH the index. It does not lead it (the minute-level correlation is -0.34 at the same minute, about 0 one minute later).

**Momentum, streaks, levels**

14. **At 5 to 15 minutes the market mean-reverts slightly.** After BANKNIFTY's 5-minute move is a 1.5-sd rise, the next 15 minutes are up 46.4% vs 50.6%. After a 1.5-sd fall, NIFTY is up 54.5% vs 51.2%.
    - That is a real effect, but only 3-4 percentage points. Buying the BANKNIFTY PE after a spike makes Rs +93 gross and Rs -41 net a trade.
15. **"3 red 5-minute candles at 11:00-13:00, then is the next 15 minutes up?"** Yes, slightly: BANKNIFTY **55.6%** vs 49.9% (n = 2,497). After 5 red candles, 57.6%.
    - Buying the CE loses Rs 139 a trade. A 5-6 point edge in the index does not cover the option round trip.
16. **The first-hour high breakout works on NIFTY and MIDCP, not BANKNIFTY.** After the first close above the first-hour high (after 10:15), the index closes above that level:
    - NIFTY **58%** (vs 50%);
    - MIDCP **62%**;
    - BANKNIFTY 50%, a pure coin flip.
    - Breaks above the previous day's high: NIFTY 58%.
    - Options: NIFTY -22 to +211 a trade, MIDCP +129. Neither is significant, and the pre-registered MIDCP version is not a candidate.
17. **A weak afternoon keeps weakening.** If at 12:30 NIFTY is down more than 0.5% and in the bottom 20% of its range, it falls further by 15:09 **60%** of the time, vs 45%.
    - PE: +Rs 288 a trade (n = 128, p = 0.23). This is suggestive only. It did not reach candidate status.
18. **Round numbers barely matter.** After NIFTY crosses below a 100-point level, the next 15 minutes are up 53.9% vs 51.3%: a tiny bounce. The option loses Rs 85.
19. **Calendar:** NIFTY is up on the last 3 trading days of a month **53.8%** vs 47.3% (since 2006, q = 0.006). Best option +Rs 275 a trade, but p = 0.12. Earlier hunts (h28) found these calendar effects fade after 2016.

**Option premiums, jumps, OI, volume**

20. **What fraction of +20-point jumps happen early?** The 09:20-09:45 window holds **14.6%** of NIFTY CE jumps, vs about 10% if the time of day did not matter. The rate per minute is 21.5% vs 12.2%.
    - Drops cluster there as well. Net of drops, an early buy is no better (NIFTY PE early: -0.115 vs -0.078 jump-minus-drop).
21. **Chasing is worse.** If the 1-ITM option has already risen 20 points off its 15-minute low, "+20 before -15" minus "-15 first" falls to **-0.154** vs -0.105 (NIFTY CE). FINNIFTY is the same: -0.154 vs -0.096.
22. **"ATM CE premium falls 20 points while spot is flat, then what?"** Nothing for BANKNIFTY: up 49.6% vs 50.7% over 30 minutes (n.s.). For SENSEX the mirror case (PE falls 20 points) gives up 53.4% vs 50.8% (q = 0.003). The CE still loses Rs 100 a trade.
23. **"Does the PE get more expensive relative to the CE before falls?"** Barely. NIFTY: up 49.9% vs 51.5% (q = 0.22). SENSEX over 30 minutes: 47.4% vs 51.4% (q = 0.052). Option prices react to moves rather than lead them, as h29 also found.
24. **Volatility clusters, but buying it does not pay.** After a 3x near-ATM option volume surge, the next 15-minute move is **51% bigger** (13.6 vs 9.0 bps, NIFTY).
    - After the straddle jumps 1.5 sd, the next 30 minutes are 22% bigger.
    - Buying CE+PE after either still loses Rs 200-310 a trade.
    - A straddle that fell 3% with flat spot means calm continues: the next 60-minute range is 29.6 vs 35.7 bps.
25. **Put writing slightly supports the index.** When near-ATM put OI grows much faster than call OI, NIFTY is up 53.5% vs 51.2% over the next 15 minutes (q = 0.009). The CE still loses Rs 72.

**Cross-index, constituents, pin**

26. **"Does FINNIFTY lead BANKNIFTY?" No.** FINNIFTY makes a 1.5-sd 5-minute move while BANKNIFTY stays flat only 37 times in 4 years. They move together.
    - **A solo BANKNIFTY spike fades rather than drags NIFTY.** NIFTY is up only 43% vs 51% in the next 15 minutes (q = 0.036).
27. **Single heavyweights do not lead the index.** HDFCBANK, ICICIBANK, SBIN, KOTAK, AXIS, RELIANCE, INFY, TCS, "the 5 banks together", and top-20 breadth: only 2 of 54 constituent questions survive BH.
    - One is LT, with the sign reversed (after LT spikes, NIFTY is up 45% vs 50%).
    - The other is a 13-case breadth oddity that is not stable.
    - The constituent data covers only one pre-holdout year.
28. **No max-pain pin.** On expiry days from 14:30, BANKNIFTY moves toward the max-OI strike only **35%** of the time, vs 45% on other days. NIFTY, FIN, MIDCP and SENSEX: no difference.
29. **Expiry day is NOT a bigger-range day.** NIFTY's range is 1.04% on expiry vs 1.05% on other days. BANKNIFTY, FINNIFTY and SENSEX: no difference either.
    - Only premiums behave differently on expiry (see 9 and 10). The index itself does not.
    - MIDCP's day before expiry is even smaller: 1.24% vs 1.43%.
30. **The option buyer's base rate, again.** Over 44,536 condition x exit tests:
    - 70% are gross NEGATIVE, so before costs the conditional entries lose on average (median gross -Rs 24 a trade).
    - 96% are net negative.
    - The median net is -Rs 170 a trade.
    - Conditions do beat a same-minute random entry 57% of the time, but by small amounts.

## The candidates and their one holdout run

All were chosen by the pre-written rule in `PREREG.md`. No strict candidate exists: the best option test has BH q = 1.0. The 6 loose candidates were run once, at 1 lot, with the pre-registered exit. All six fail. The six together: **-Rs 518/day net**.

| id | rule | exit | pre-holdout Rs/day net (gross) | holdout trades | **holdout Rs/day net (gross)** | holdout per trade vs random | p vs random | max DD | lots for Rs 5k/day | lots Rs 1 lakh buys, Rs/day at that size |
|---|---|---|---|---|---|---|---|---|---|---|
| Q0357 | BANKNIFTY expiry day: buy 1-ITM CE+PE at 09:21 | Liquidity arm | +172 (206) | 13 | **-119 (-108)** | -2,267 vs -372 | 0.94 | -29.9k | n/a | 6 lots, -713 |
| Q0108 | MIDCP gap-down > 0.5%: buy CE at 09:16 (toward the fill), out 15:10 | time | +163 (176) | 15 | **-215 (-197)** | -3,547 vs -669 | 0.93 | -80.3k | n/a | 3, -644 |
| Q0176 | MIDCP at 14:00 with day high made in the first 15 min: buy PE | +25 / -20 / 30 min | +69 (121) | 80 | **-55 (+15)** | -171 vs -322 | 0.23 | -33.2k | n/a | 4, -220 |
| Q0177 | MIDCP at 14:00 with day low made in the first 15 min: buy CE | out 15:10 | +108 (162) | 90 | **-23 (+65)** | -64 vs -230 | 0.32 | -28.1k | n/a | 3, -70 |
| Q0378 | MIDCP the day before expiry: buy CE+PE at 09:16 | +30 / -10 / 15 min | +53 (87) | 11 | **-15 (-4)** | -346 vs -440 | 0.47 | -8.1k | n/a | 4, -61 |
| Q0498 | MIDCP first-hour range > 1.5x average: buy CE+PE at 10:16 | Liquidity arm | +175 (223) | 30 | **-91 (-27)** | -755 vs -1,068 | 0.31 | -48.1k | n/a | 1, -91 |

More on these rules:
- Only 1 of the 6 kept any positive exit in the holdout:
  - Q0378 had 8 of 38 exits positive. The best was +86/day, an exit that was not pre-registered.
  - Q0108 and Q0498 had 0 of 38.
- Five of the six are MIDCPNIFTY day-level rules with 30-190 pre-holdout trades. That is the classic setting for a lucky in-sample pick.
- "Lots for Rs 5k/day" is n/a because every rule loses money. More lots only lose faster.
- "Lots Rs 1 lakh buys" is Rs 1,00,000 divided by the median premium per lot.

## Conclusion for Boss

- Hundreds of the 1,296 answers are true and some are surprising:
  - big gaps rarely fill;
  - the early high usually holds;
  - expiry-day straddles lose about 85%;
  - weekdays carry no edge;
  - the open is the busiest time.
- But the market's habits are either:
  - **about size, not direction** (VIX, gaps, prior-day range, volume surges): you would need to own volatility cheaply, and the options already price it;
  - or **a few points of index drift** (streak reversal, breakouts, put writing): an option buyer pays about 0.4-0.8% round-trip plus theta, which eats it.
- Not one of 44,536 option translations survives the correction for how many were tried. The 6 best-looking rules all lost in the locked holdout.
- **Rs 5,000/day: NO.** The best honest plan is still h36's Plan A: BANKNIFTY Liquidity 15+5, 1 lot, about Rs 65-167/day.

## Honesty notes

**Holdout use.** The holdout was run twice.
- Run 1 had the look-ahead bug. Its 8 candidates included the two VIX artefacts. The other 6 are exactly the 6 above, with identical numbers.
- After the fix and the audit, the candidate rule was re-applied mechanically. It produced the same 6, and the holdout was re-run once.
- No threshold, exit or rule was changed after looking at any holdout number.
- After the holdout, FINNIFTY's 2006-2011 daily placeholder rows were dropped. All answers were recomputed. The candidate set did not change, and significant answers went from 336 to 327.

**What is not modelled.**
- Liquidity and impact at size: see h10/h24.
- Partial fills.
- Bid/ask at each signal: the spread is one snapshot from h24.
- STT is today's 0.15%; older trades paid less.
- Lot sizes are today's.

**Disk.** The caches (about 300 MB) were deleted at the end. Rebuild them with:

```
python3 -I research/hunt/h42/build.py NIFTY BANKNIFTY FINNIFTY MIDCPNIFTY SENSEX
python3 -I research/hunt/h42/run_all.py
```

The build takes about 4 minutes and the tables + answers about 5. Run both under the obuy flock.
