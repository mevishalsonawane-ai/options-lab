# R20b: support and resistance levels as the ORB range (13 Oct 2026)

**Verdict: no.** Support and resistance levels do not make a better ORB range than the clock does. None of the 7
pre-registered level variants passes the gates on BANKNIFTY, and none comes close: the smallest of its 14 holdout
p-values is 0.31. In the one BH family with R20's 14 tests (28 in all), nothing survives, and the best of all 28
(R20's B0.5 twin, p 0.025) misses the BH bar of 0.0018. No paper app change is proposed. The ORB family stays paused on
paper with its plain 09:15-10:00 range.

In plain English for Boss: we tried four sources of levels (yesterday's high / low / close and the floor pivots the
morning brief shows; the Liquidity arm's swing and pool zones; the biggest put and call open-interest strikes from
yesterday's NSE file; and the same OI walls from the minute data at 10:04), in four ways (as the range, to narrow a wide
range, to confirm a break, and to trade bounces inside a wide range). Every way lost money after costs on BANKNIFTY in the
design period except one (the OI walls as the range, +Rs 1,745 over 98 trades in 361 sessions), and that one lost
Rs 3,972 over 57 trades in the 122 holdout sessions. We also re-ran each holdout 1,000 times with the levels placed at
random, at the same distances from the price (the random-level twins). In 6 of the 7 variants the random levels made more
on average than the real ones. In the 7th (B) the real levels beat 69% of the random runs, far from significant. So the
levels add nothing a random line would not.

- Pre-registration: `research/hunt/r20b/PREREG.md`, commit a600722 (before any run). Design table frozen in commit
  5f00bde before the one holdout read. Code: `research/hunt/r20b/` (`levels.py`, `study.py`, `oi.py`, `test_r20b.py`).
  Results: `results/design.json`, `results/holdout.json`, `results/combined_bh.json`.
- Experiment entries: `research/experiments/entries/r20sr*.yaml` (7 variants, all rejected, plus the facts entry).
  The ids are `r20sr-*` because R20 already uses `r20a` .. `r20g`.

## What was run

- Data: the `research-data` release through R17's loader (BANKNIFTY 13 Feb 2024 - 23 Feb 2026, NIFTY 1 Apr 2024 -
  13 Apr 2026), first 15 sessions of each index dropped for ATR14. Holdout: BANKNIFTY from 25 Aug 2025 (122 sessions),
  NIFTY from 13 Oct 2025 (120 sessions); design = the earlier 361 / 364 sessions.
- Arm: the ORB as the app trades it on paper (1 entry a side a day, ATM strike from the 09:20 bar, -40 / +40 premium
  points with the ladder, 15:10 out; NIFTY -20 / +20, floor 20), the app's paper fills plus 1-4 ticks of spread, the
  app's charges at today's rates, 1 lot. Every variant reads the same option trades and differs only in when it fires.
- Check against earlier work: our plain V0 holdout net is -Rs 10,965 over 117 trades, the same as R20's V0
  (-Rs 10,964.54). Over R17's design days it gives 296 trades and -Rs 14,566, against R17's 285 trades and -Rs 14,288.
  The small gap is R17's later warm-up start.
- OI sources: the NSE F&O bhavcopy was available for every session except the Saturday Budget session of 1 Feb 2025
  (no file), so 3 Feb 2025 had no previous-day OI walls (no fallback to an older file). The intraday OI is the release's
  Dhan minute OI (strikes within 1,000 points of the 09:20 index, the nearest expiry AFTER the day). It is not the Dhan
  ATM +-10 lake feed, which is not published. That variant was run as pre-registered, with those limits stated.

| id | rule (full text in PREREG.md) |
|---|---|
| V0 | plain 09:15-10:00 range (baseline) |
| A-ALL | range = nearest level above / below the 10:00 price (all of pivots + liquidity zones + bhavcopy OI), levels within 0.1 x ATR14 ignored |
| A-PIV | the same with yesterday's H / L / C and P, R1, S1 only |
| A-OI | the same with the bhavcopy OI walls only |
| A-OIi | the same with the intraday OI walls at 10:04 only |
| B | wide day (W45 > 0.75 x ATR14): range clipped to the nearest levels inside it |
| C | plain range; a break must also clear the nearest level within 0.5 x ATR14 beyond the edge |
| D | wide day: bounce trades at levels inside the range (touch within 0.05 x ATR14, close back), at most 2 a day |

## Gates (BANKNIFTY)

| variant | G1 design > 0 and > V0 | G2 3 of 4 design blocks > 0 | G3 holdout t-test p | G4 random-level twin p | BH (28 tests) | G5 holdout > V0 | PASS |
|---|---|---|---|---|---|---|---|
| A-ALL | no | no | 0.80 | 0.84 | no | yes | **no** |
| A-PIV | no | no | 0.90 | 0.90 | no | no | **no** |
| A-OI | yes | no | 0.77 | 0.93 | no | yes | **no** |
| A-OIi | no | no | 0.50 | 0.91 | no | yes | **no** |
| B | no | no | 0.78 | 0.31 | no | yes | **no** |
| C | no | no | 1.00 | 0.96 | no | no | **no** |
| D | no | no | 0.98 | 0.87 | no | no | **no** |

## Results

#### BANKNIFTY: design 2024-03-05 .. 2025-08-22 (361 sessions), holdout 2025-08-25 .. 2026-02-23 (122 sessions)

| variant | design trades | design net Rs | per session | % green trade days | worst day | max DD | design blocks 1-4 | holdout trades | holdout net Rs | per session | % green | worst day | max DD | t-test p | twin p (twin mean) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| V0 | 337 | -14,772 | -40.9 | 43.8 | -2,678 | 21,700 | 2,310 / -4,757 / -12,458 / 133 | 117 | -10,965 | -89.9 | 32.7 | -1,629 | 11,851 | 0.91 | - |
| A-ALL | 294 | -29,437 | -81.5 | 36.6 | -2,952 | 32,870 | -8,276 / -3,519 / -16,469 / -1,172 | 108 | -7,365 | -60.4 | 39.1 | -2,560 | 12,187 | 0.80 | 0.84 (-1,600) |
| A-PIV | 332 | -16,239 | -45.0 | 41.8 | -2,952 | 21,192 | -1,386 / -2,647 / -10,638 / -1,568 | 117 | -11,754 | -96.3 | 36.4 | -1,649 | 15,727 | 0.90 | 0.90 (-4,328) |
| A-OI | 98 | 1,745 | 4.8 | 42.3 | -1,552 | 5,829 | -1,804 / -773 / 1,302 / 3,020 | 57 | -3,972 | -32.6 | 33.9 | -1,600 | 6,915 | 0.77 | 0.93 (2,068) |
| A-OIi | 204 | -7,586 | -21.0 | 40.3 | -1,552 | 14,005 | 4,567 / -7,527 / -1,986 / -2,640 | 71 | -47 | -0.4 | 35.7 | -1,600 | 4,338 | 0.50 | 0.91 (5,861) |
| B | 347 | -17,319 | -48.0 | 44.4 | -2,678 | 23,180 | -445 / -3,469 / -12,797 / -608 | 125 | -6,428 | -52.7 | 34.9 | -2,560 | 10,800 | 0.78 | 0.31 (-7,586) |
| C | 250 | -13,266 | -36.7 | 38.7 | -2,639 | 22,380 | -380 / -10,385 / -4,263 / 1,763 | 85 | -22,075 | -180.9 | 31.3 | -1,869 | 24,879 | 1.00 | 0.96 (-13,024) |
| D | 387 | -30,003 | -83.1 | 41.7 | -2,678 | 34,214 | -1,852 / -3,917 / -22,424 / -1,810 | 144 | -17,933 | -147.0 | 32.1 | -3,094 | 19,047 | 0.98 | 0.87 (-13,022) |

BANKNIFTY: days a variant turns from no-trade (under V0) into trade days, and what those trades made; days it drops:

| variant | design: new trade days (trades) -> net Rs | design: V0 trade days dropped (V0 made there) | holdout: new trade days (trades) -> net Rs | holdout: dropped (V0 made there) |
|---|---|---|---|---|
| A-ALL | 28 (29) -> -10,381 | 72 (-4,473) | 7 (8) -> 1,253 | 22 (-3,357) |
| A-PIV | 27 (28) -> -8,828 | 39 (-5,595) | 6 (7) -> 1,011 | 14 (-1,074) |
| A-OI | 1 (1) -> -669 | 210 (-12,478) | 1 (1) -> 242 | 52 (-1,260) |
| A-OIi | 8 (8) -> -3,510 | 123 (-10,565) | 1 (1) -> 242 | 38 (-6,820) |
| B | 5 (5) -> -1,167 | 0 (0) | 2 (2) -> 493 | 0 (0) |
| C | 0 (0) -> 0 | 71 (-13,155) | 0 (0) -> 0 | 24 (-4,656) |
| D | 8 (16) -> -4,942 | 0 (0) | 5 (10) -> -1,382 | 0 (0) |

BANKNIFTY, 13-Oct-like days (wide, W45 > 0.75 x ATR14, and no V0 trade): design 10 days, holdout 6 days

| variant | design trade days / trades / net Rs | holdout trade days / trades / net Rs |
|---|---|---|
| A-ALL | 5 / 5 / -1,167 | 2 / 2 / 493 |
| A-PIV | 5 / 5 / -1,167 | 1 / 1 / 251 |
| A-OI | 1 / 1 / -669 | 1 / 1 / 242 |
| A-OIi | 3 / 3 / -2,337 | 1 / 1 / 242 |
| B | 5 / 5 / -1,167 | 2 / 2 / 493 |
| C | 0 / 0 / 0 | 0 / 0 / 0 |
| D | 8 / 16 / -4,942 | 5 / 10 / -1,382 |

#### NIFTY: design 2024-04-24 .. 2025-10-10 (364 sessions), holdout 2025-10-13 .. 2026-04-13 (120 sessions)

| variant | design trades | design net Rs | per session | % green trade days | worst day | max DD | design blocks 1-4 | holdout trades | holdout net Rs | per session | % green | worst day | max DD |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| V0 | 348 | -831 | -2.3 | 43.0 | -3,153 | 19,237 | -746 / -9,391 / 1,002 / 8,303 | 121 | -18,152 | -151.3 | 33.6 | -1,991 | 19,016 |
| A-ALL | 289 | -34,085 | -93.6 | 36.8 | -3,230 | 39,861 | 632 / -7,329 / -14,535 / -12,853 | 93 | 14,538 | 121.1 | 46.3 | -1,599 | 2,660 |
| A-PIV | 334 | -40,692 | -111.8 | 36.6 | -3,230 | 42,899 | 73 / -17,503 / -11,127 / -12,135 | 118 | -10,733 | -89.4 | 33.7 | -3,185 | 11,732 |
| A-OI | 39 | -5,662 | -15.6 | 38.9 | -3,228 | 7,709 | 1,504 / -1,165 / -3,807 / -2,194 | 27 | -3,794 | -31.6 | 40.0 | -1,626 | 6,615 |
| A-OIi | 193 | 7,726 | 21.2 | 46.0 | -3,228 | 11,376 | 4,505 / -1,592 / -1,909 / 6,723 | 88 | -4,611 | -38.4 | 41.2 | -1,626 | 8,093 |
| B | 367 | -7,770 | -21.3 | 41.0 | -3,230 | 20,669 | -1,113 / -10,523 / -3,677 / 7,544 | 124 | -18,688 | -155.7 | 33.3 | -1,991 | 19,552 |
| C | 271 | -17,674 | -48.6 | 37.1 | -1,640 | 22,957 | -2,810 / -7,333 / -6,517 / -1,015 | 88 | -642 | -5.3 | 42.0 | -2,757 | 9,380 |
| D | 400 | -8,344 | -22.9 | 42.5 | -3,227 | 24,028 | -2,446 / -10,737 / -2,617 / 7,456 | 128 | -20,848 | -173.7 | 34.3 | -1,991 | 22,020 |

NIFTY: days a variant turns from no-trade (under V0) into trade days, and what those trades made; days it drops:

| variant | design: new trade days (trades) -> net Rs | design: V0 trade days dropped (V0 made there) | holdout: new trade days (trades) -> net Rs | holdout: dropped (V0 made there) |
|---|---|---|---|---|
| A-ALL | 18 (19) -> -5,629 | 81 (-2,244) | 5 (5) -> 2,150 | 30 (-15,623) |
| A-PIV | 17 (17) -> -2,735 | 41 (-8,935) | 5 (5) -> 2,150 | 8 (-5,391) |
| A-OI | 1 (1) -> 313 | 281 (-1,142) | 0 (0) -> 0 | 82 (-18,765) |
| A-OIi | 7 (7) -> -1,033 | 147 (-20,072) | 4 (4) -> 586 | 31 (-4,322) |
| B | 8 (9) -> -826 | 0 (0) | 1 (1) -> 1,261 | 0 (0) |
| C | 0 (0) -> 0 | 57 (-16,582) | 0 (0) -> 0 | 26 (-16,402) |
| D | 12 (22) -> 2,386 | 1 (1,191) | 1 (2) -> -231 | 0 (0) |

NIFTY, 13-Oct-like days (wide, W45 > 0.75 x ATR14, and no V0 trade): design 13 days, holdout 2 days

| variant | design trade days / trades / net Rs | holdout trade days / trades / net Rs |
|---|---|---|
| A-ALL | 8 / 9 / -826 | 1 / 1 / 1,261 |
| A-PIV | 8 / 8 / 2,036 | 1 / 1 / 1,261 |
| A-OI | 1 / 1 / 313 | 0 / 0 / 0 |
| A-OIi | 5 / 5 / -1,682 | 2 / 2 / 798 |
| B | 8 / 9 / -826 | 1 / 1 / 1,261 |
| C | 0 / 0 / 0 | 0 / 0 / 0 |
| D | 12 / 22 / 2,386 | 1 / 2 / -231 |

## The levels themselves (descriptive, no test)

| | BANKNIFTY design (361) | BANKNIFTY holdout (122) | NIFTY design (364) | NIFTY holdout (120) |
|---|---|---|---|---|
| levels per day: pivots / bhavcopy OI / intraday OI / liquidity zones active at 10:05 | 6 / 1.99 / 2.0 / 7.48 | 6 / 2.0 / 2.0 / 7.11 | 6 / 1.99 / 2.0 / 7.88 | 6 / 2.0 / 2.0 / 6.03 |
| median distance from the 10:00 price to the nearest level above / below (beyond 0.1 x ATR14), in ATR14 | 0.235 / 0.224 (349 / 343 days with one) | 0.244 / 0.245 (114 / 117) | 0.234 / 0.228 (360 / 356) | 0.226 / 0.266 (117 / 117) |
| wide days (W45 > 0.75 x ATR14) | 50 | 24 | 52 | 12 |
| median W45 / ATR14 | 0.497 | 0.526 | 0.467 | 0.467 |

The nearest level sits about a quarter of a day's ATR from the 10:00 price. That is about where the opening-range edges
sit too (a median day's range is half an ATR wide). So the level ranges are not tighter on average; they are just
different lines. On days with no V0 trade they sometimes trigger, and those new trades lost money in design (A-ALL: 28
new trade days, -Rs 10,381).

## Why it fails, in one paragraph

The levels move the trigger, but not the odds of a breakout trade after costs. With all sources together there are
about 16 levels a day, so "the nearest level" is almost always about 0.23 ATR away whatever the market did. That is why
mirroring every level to the other side of the price (the twin) gives about the same result or better (6 of 7 variants). The OI walls (A-OI)
did look better in design, but only because they sit far away: the range they set is wide, so A-OI traded on 97 of 361
design days instead of 306. That made it lose less, and it was not better at picking trades (42.3% green days vs V0's
43.8%; its twin, with OI walls at the same distance on random sides, made +Rs 2,068 in the holdout against its real
-Rs 3,972). On 13-Oct-like days (wide range, no V0 trade: 10 design and 6 holdout days on BANKNIFTY) the level ranges did
turn a few into trades. Design: 5 trades, -Rs 1,167 (A-ALL). Holdout: 2 trades, +Rs 493. The bounce rule (D) traded
most of those days and lost on them in both periods (-Rs 4,942 over 16 trades; -Rs 1,382 over 10).

## NIFTY (replication, not a gate)

NIFTY design: every variant except A-OIi lost more than V0 (V0 -Rs 831; A-OIi +Rs 7,726 but only 2 of 4 blocks
positive). The NIFTY holdout has one standout: A-ALL +Rs 14,538 over 93 trades in 120 sessions (t-test p 0.017, no twin
run on NIFTY by design). But A-ALL lost Rs 34,085 on NIFTY in design (289 trades, 364 sessions) and lost in both
BANKNIFTY periods. One good half-year after a bad 18 months, in a 28-test family, is not evidence. It is reported, not
acted on, and a fresh test of it would need its own pre-registration and new data (the Dhan lake).

## Multiple testing

`results/combined_bh.json`: one BH family of 28 holdout p-values (R20b's 7 G3 + 7 G4, R20's 7 G3 + 7 G4 from its
`holdout.json` at commit 98f3c90), q = 0.05. Nothing is rejected. The three smallest p-values are all R20's (B0.5 twin
0.025, B0.75 twin 0.146, C1430 twin 0.294); R20b's smallest is B's twin, 0.31. Walk-forward selector (reported, not a
gate): on BANKNIFTY it picked A-OIi, V0, then A-OI for design blocks 2-4 (-Rs 7,527, -Rs 12,458, +Rs 3,020), and A-OI
for the holdout (-Rs 3,972).

## Limits, stated

- The release holds the next expiry, not today's, on expiry days (as in R17 and R20). The intraday OI is the release's
  +-10-strike window, not the full chain.
- The liquidity zones are read from the 15-minute chart with 10 calendar days of history (one of the app's two
  BANKNIFTY books). The 5-minute book's levels were not tried.
- About 2 years of data: 122 holdout sessions per index. A real edge of a few hundred rupees a day could hide in that.
  None of the variants shows one, and the twins say the level positions carry no information here.

## App change

None. No variant passed, so nothing goes to paper. The levels can still be shown to Boss as context (the morning brief's
pivot, Jarvis's OI walls), but this study gives no reason to trade the ORB off them.
