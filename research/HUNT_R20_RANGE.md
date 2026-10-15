# R20: An opening range that adapts to the day (ORB family, wide-range days)

Written 13 Oct 2026 for Boss. Pre-registration: `research/hunt/r20/PREREG.md`, committed on its own (c445042) before
any number was computed, with the experiment entries `research/experiments/entries/r20.yaml` and `r20a.yaml` -
`r20g.yaml`. The design table was frozen and committed (728b68c) before the holdout was read. The holdout was read once,
guarded by a flag file in the cache.

- Code: `research/hunt/r20/` (`r20lib.py` the rules, `r20run.py` the runner, `posthoc.py`, `test_r20.py`). Data, fills,
  charges and the ORB exits are R17's (`research/hunt/r17/data.py`, `arms.py`), unchanged.
- Results: `r20/results/design.json`, `holdout.json`, `posthoc.json`.
- Check: the plain ORB here reproduces R17's ORB trade for trade (454 of 454 trades on the shared days identical) and
  its holdout exactly (117 trades, -Rs 10,965).

## Verdict (read this first)

**No variant passes. Not one of the 7 clears even the design gates on BANKNIFTY, and none survives the holdout after
BH.** A range that adapts to the day does not turn the ORB into something worth trading. The ORB loses on narrow and
normal days too; the wide days are not why it loses.

Plain words:

1. **Days like today are not common.** On BANKNIFTY the plain 09:15-10:00 range gave no ORB trade on 15% of design
   days (55 of 361) and 12% of holdout days (15 of 122). The whole family (ORB, Sweep, Range Fade) sat out on 6% (21 of
   361) and 4% (5 of 122). A day that stayed inside until 14:00 and THEN broke out, like today, came 13 times in 361
   design days (3.6%) and 4 times in 122 holdout days (3.3%): a little less than once a month.
2. **The late break was not worth chasing.** On those 13 design days the index moved a mean -36 points (median -56)
   from the break to 15:10 in the break's direction (5 of 13 went the right way); the ORB option trade on it made
   -Rs 567 in all (5 of 13 green). Holdout: 4 days, +22 points mean, +Rs 925 in all (2 of 4 green). Too few to act on,
   and the 14:30 last entry (C1430) that would catch some of them lost in both periods.
3. **Shorter ranges trade more and lose more.** The 15-minute range (A15) took 416 design trades and lost Rs 34,682
   (vs the plain range's 337 trades and -Rs 14,772); the 30-minute one lost Rs 21,946. Holdout: -Rs 15,434 and
   -Rs 13,021 vs -Rs 10,965. Breaking a smaller box just means more false breaks.
4. **The adaptive range (B) is noise.** B0.5 made +Rs 3,078 in the holdout (122 sessions) but lost Rs 19,849 in design
   (361 sessions) and was positive in only 1 of 4 design blocks; after BH its holdout q is 0.91 (t) and 0.35 (twin).
   B0.75 and B1.0 lost in both periods.
5. **Fading inside a wide range (D) is worse.** -Rs 21,051 design, -Rs 18,387 holdout, the worst holdout of all.
6. **The honest answer: on a wide-range day, simply don't trade the ORB.** The ORB family stays paused on paper. Nothing
   here should change the app's range rule. The "never trades" worry is real but rare (about 1 day in 7 with no ORB
   trade, 1 in 30 with a late break), and the trades it misses lost money on average.

## Data and setup

| | |
|---|---|
| data | `research-data` release, 1-minute index and option bars: BANKNIFTY 13 Feb 2024 - 23 Feb 2026, NIFTY 1 Apr 2024 - 13 Apr 2026. The first 15 sessions of each have no ATR14 and are left out of every variant |
| design / holdout | BANKNIFTY 5 Mar 2024 - 22 Aug 2025 (361 sessions) / 25 Aug 2025 - 23 Feb 2026 (122). NIFTY 24 Apr 2024 - 10 Oct 2025 (364) / 13 Oct 2025 - 13 Apr 2026 (120). R16 / R17's cut dates |
| arm | the ORB as the app trades it on paper: 5-minute close beyond the range buys the ATM option (strike from the 09:20 bar), 1 entry a side a day (R14 cap), -40 / +40 premium points with the profit-lock ladder, out at 15:10, premium <= 40 refused. NIFTY (replication, not a gate): -20 / +20, floor 20, 50-point strikes |
| costs | the app's charges at today's rates; paper fills +-5 bps plus 1-4 ticks of half-spread by recent volume; stops -10 bps and double ticks (R17's `Fills("liq")`, `Costs("app")`); 1 lot, the NSE lot in force that day |
| ATR14 | the app's `ShadowRules.atr14`: mean true range of the 14 sessions before the day |
| caveat | the release has only the NEXT expiry on most expiry days, so expiry-day trades use the next expiry (the app uses today's). The design facts include 46 BANKNIFTY expiry days; the holdout only 5 (monthly expiries) |
| disclosure | R17 had already read this holdout for the plain ORB (it lost). None of the 7 variants had been run on any period before the pre-registration |

Range naming follows the app's `OrbParams.orEnd`: "the 45-minute range" is the bars labelled 09:15 to 10:00 inclusive
(really 09:15-10:05), the 15-minute range ends with the 09:30 bar, the 30-minute range with the 09:45 bar.

## Step 1: how often does the range give no trade? (plain 45-minute range)

BANKNIFTY. "No ORB" = no 5-minute close beyond the range on the 10:05-13:55 bars. "No family" = also no Sweep signal
(to 14:25) and no Range Fade signal (10:30-13:55). W45 / ATR14 = the range's width in ATR14s (median 0.50 design,
0.53 holdout). BANKNIFTY's median ATR14 was 661 points in design and 497 in the holdout, so today's 630-point range is
about 1 to 1.3 ATR14 (the top bin).

| split | design days | no ORB | no family | holdout days | no ORB | no family |
|---|---|---|---|---|---|---|
| all | 361 | 55 (15.2%) | 21 | 122 | 15 (12.3%) | 5 |
| W45 < 0.5 ATR | 186 | 22 (11.8%) | 11 | 52 | 5 (9.6%) | 1 |
| 0.5 - 0.75 | 125 | 23 (18.4%) | 7 | 46 | 4 (8.7%) | 1 |
| 0.75 - 1.0 | 34 | 7 (20.6%) | 2 | 17 | 2 (11.8%) | 1 |
| >= 1.0 | 16 | 3 (18.8%) | 1 | 7 | 4 (57.1%) | 2 |
| VIX prev close < 13 | 93 | 17 (18.3%) | 6 | 107 | 12 (11.2%) | 4 |
| VIX 13 - 16 | 190 | 23 (12.1%) | 8 | 15 | 3 (20.0%) | 1 |
| VIX >= 16 | 78 | 15 (19.2%) | 7 | 0 | - | - |
| expiry day | 46 | 11 (23.9%) | 5 | 5 | 1 (20.0%) | 0 |
| other days | 315 | 44 (14.0%) | 16 | 117 | 14 (12.0%) | 5 |

Wider ranges trade a little less often (18-21% no-ORB days above 0.5 ATR vs 12% below, design), and expiry days
more often sit out (24% vs 14%, design, 46 days). The holdout's 4 of 7 at >= 1.0 ATR is the closest match to today,
but 7 days is far too few to lean on. VIX makes no clear difference.

Late breaks (no ORB trade, then a close beyond the range on a 14:00-15:00 bar):

| index | period | no-ORB days | late breaks | index points to 15:10 (mean / median, right way) | ORB option trade on it |
|---|---|---|---|---|---|
| BANKNIFTY | design | 55 | 13 | -36 / -56, 5 of 13 | -Rs 567 in all, 5 of 13 green |
| BANKNIFTY | holdout | 15 | 4 | +22 / +22, 3 of 4 | +Rs 925 in all, 2 of 4 green |
| NIFTY | design | 48 | 11 | +5 / +4, 6 of 11 | +Rs 2,413 in all, 7 of 11 green |
| NIFTY | holdout | 13 | 9 | -8 / -29, 3 of 9 | +Rs 582 in all, 3 of 9 green |

NIFTY (no-ORB days 13.2% design, 48 of 364; 10.8% holdout, 13 of 120) looks the same.

## Step 3: the variants

Daily net = that day's trades' net, 0 on a day with no trade, every session counted. Gates (PREREG): G1 design net > 0
and > the plain rules; G2 net > 0 in at least 3 of 4 design blocks; G3 holdout mean daily net > 0 (one-sided t) and G4
beats 1,000 random-entry twins (same trades a day, random bar in the variant's own window, random side), both after BH
over the 14 holdout p-values at q 0.05; G5 holdout net > the plain rules'.

### BANKNIFTY: design 2024-03-05 - 2025-08-22 (361 sessions), holdout 2025-08-25 - 2026-02-23 (122 sessions)

| variant | period | trades | trade days | net Rs | Rs / session | % green trade days | worst day | max DD | 4 design blocks (Rs) |
|---|---|---|---|---|---|---|---|---|---|
| V0 | design | 337 | 306 | -14,772 | -41 | 44% | -2,678 | 21,700 | +2,316 / -4,921 / -12,301 / +133 |
| V0 | holdout | 117 | 107 | -10,965 | -90 | 33% | -1,629 | 11,851 | |
| A15 | design | 416 | 342 | -34,682 | -96 | 42% | -2,747 | 41,184 | -718 / -10,628 / -24,400 / +1,063 |
| A15 | holdout | 134 | 115 | -15,434 | -127 | 39% | -3,038 | 16,593 | |
| A30 | design | 367 | 315 | -21,946 | -61 | 43% | -2,678 | 30,857 | +720 / -6,392 / -18,854 / +2,581 |
| A30 | holdout | 122 | 110 | -13,021 | -107 | 36% | -3,038 | 13,842 | |
| B0.5 | design | 371 | 321 | -19,849 | -55 | 42% | -2,678 | 26,265 | +3,032 / -1,418 / -17,814 / -3,649 |
| B0.5 | holdout | 122 | 111 | +3,078 | +25 | 38% | -1,600 | 5,398 | |
| B0.75 | design | 346 | 310 | -11,782 | -33 | 45% | -2,678 | 22,890 | +4,636 / -2,453 / -17,576 / +3,611 |
| B0.75 | holdout | 122 | 111 | -4,377 | -36 | 32% | -1,600 | 8,731 | |
| B1.0 | design | 341 | 307 | -12,358 | -34 | 44% | -2,678 | 19,539 | +3,135 / -3,070 / -12,733 / +309 |
| B1.0 | holdout | 121 | 111 | -12,168 | -100 | 31% | -1,629 | 13,049 | |
| C1430 | design | 351 | 314 | -14,415 | -40 | 45% | -2,678 | 21,576 | +2,217 / -4,494 / -12,486 / +348 |
| C1430 | holdout | 120 | 109 | -10,079 | -83 | 34% | -1,629 | 11,408 | |
| D | design | 362 | 303 | -21,051 | -58 | 44% | -2,964 | 27,771 | +1,198 / -5,616 / -16,540 / -92 |
| D | holdout | 131 | 106 | -18,387 | -151 | 35% | -3,084 | 20,897 | |

| variant | G1 design > 0 and > plain | G2 >= 3 of 4 blocks > 0 | holdout t p (BH q) | random twin: mean Rs, p (BH q) | G5 holdout > plain | PASS |
|---|---|---|---|---|---|---|
| V0 (plain) | - | - | 0.91 | -14,265, 0.36 | - | baseline |
| A15 | no | no | 0.94 (0.97) | -16,507, 0.455 (0.91) | no | **no** |
| A30 | no | no | 0.92 (0.97) | -14,723, 0.411 (0.91) | no | **no** |
| B0.5 | no | no | 0.35 (0.91) | -14,312, 0.025 (0.35) | yes | **no** |
| B0.75 | no | no | 0.72 (0.97) | -14,620, 0.146 (0.91) | yes | **no** |
| B1.0 | no | no | 0.93 (0.97) | -14,571, 0.398 (0.91) | no | **no** |
| C1430 | no | no | 0.89 (0.97) | -15,345, 0.294 (0.91) | yes | **no** |
| D | no | no | 0.97 (0.97) | -14,855, 0.645 (0.97) | no | **no** |

Walk-forward selector (pick the best variant so far, trade it next block): BANKNIFTY picked B0.75, B0.75, B1.0 for
design blocks 2-4 (-Rs 2,453, -Rs 17,576, +Rs 309) and B0.75 for the holdout (-Rs 4,377, 122 sessions).

### NIFTY (replication, not a gate): design 24 Apr 2024 - 10 Oct 2025 (364 sessions), holdout 13 Oct 2025 - 13 Apr 2026 (120 sessions)

| variant | period | trades | trade days | net Rs | Rs / session | % green trade days | worst day | max DD | 4 design blocks (Rs) |
|---|---|---|---|---|---|---|---|---|---|
| V0 | design | 348 | 316 | -831 | -2 | 43% | -3,153 | 19,237 | -746 / -9,391 / +1,002 / +8,303 |
| V0 | holdout | 121 | 107 | -18,152 | -151 | 34% | -1,991 | 19,016 | |
| A15 | design | 427 | 346 | -13,406 | -37 | 42% | -3,155 | 24,500 | -2,374 / -989 / -2,915 / -7,128 |
| A15 | holdout | 140 | 116 | -8,150 | -68 | 42% | -2,823 | 16,319 | |
| A30 | design | 373 | 323 | +4,037 | +11 | 44% | -3,153 | 12,345 | -1,249 / -2,336 / -472 / +8,094 |
| A30 | holdout | 128 | 111 | -16,603 | -138 | 35% | -1,798 | 21,828 | |
| B0.5 | design | 366 | 325 | -10,749 | -30 | 42% | -3,153 | 22,943 | +808 / -8,354 / -9,482 / +6,278 |
| B0.5 | holdout | 124 | 109 | -499 | -4 | 38% | -1,991 | 14,333 | |
| B0.75 | design | 359 | 323 | -10,477 | -29 | 41% | -3,153 | 23,021 | -5 / -8,874 / -8,030 / +6,432 |
| B0.75 | holdout | 122 | 108 | -11,649 | -97 | 35% | -1,991 | 15,910 | |
| B1.0 | design | 351 | 317 | -3,999 | -11 | 43% | -3,153 | 19,818 | -683 / -9,388 / -2,232 / +8,303 |
| B1.0 | holdout | 121 | 107 | -14,157 | -118 | 34% | -1,991 | 18,418 | |
| C1430 | design | 359 | 318 | -1,039 | -3 | 43% | -3,153 | 18,789 | -748 / -9,223 / +1,555 / +7,377 |
| C1430 | holdout | 133 | 115 | -20,528 | -171 | 32% | -1,991 | 21,694 | |
| D | design | 365 | 311 | +1,748 | +5 | 41% | -3,153 | 19,462 | -2,308 / -9,309 / +3,507 / +9,858 |
| D | holdout | 127 | 107 | -11,182 | -93 | 37% | -1,991 | 13,756 | |

| variant | G1 design > 0 and > plain | G2 >= 3 of 4 blocks > 0 | holdout t p (BH q) | random twin: mean Rs, p (BH q) | G5 holdout > plain | PASS |
|---|---|---|---|---|---|---|
| V0 (plain) | - | - | 0.99 | -13,184, 0.71 | - | baseline |
| A15 | no | no | 0.83 (0.99) | -14,770, 0.255 (0.99) | yes | **no** |
| A30 | yes | no | 0.98 (0.99) | -13,756, 0.616 (0.99) | yes | **no** |
| B0.5 | no | no | 0.52 (0.99) | -13,425, 0.075 (0.99) | yes | **no** |
| B0.75 | no | no | 0.92 (0.99) | -13,520, 0.418 (0.99) | yes | **no** |
| B1.0 | no | no | 0.96 (0.99) | -13,053, 0.545 (0.99) | yes | **no** |
| C1430 | no | no | 0.99 (0.99) | -14,105, 0.752 (0.99) | no | **no** |
| D | yes | no | 0.90 (0.99) | -13,891, 0.386 (0.99) | yes | **no** |

Walk-forward selector: B0.5, A15, A30 for design blocks 2-4 (-Rs 8,354, -Rs 2,915, +Rs 8,094) and A30 for the holdout
(-Rs 16,603, 120 sessions). A30 and D were positive in NIFTY design (+Rs 4,037, +Rs 1,748, 364 sessions) but each in
only 1 or 2 of 4 blocks, and both lost in the holdout.

## Post hoc (NOT a test; after the holdout read): where the plain ORB loses

The plain ORB by the range's width (BANKNIFTY, net Rs per session, sessions in brackets):

| W45 / ATR14 | design | holdout |
|---|---|---|
| < 0.5 | -59 (186) | -35 (52) |
| 0.5 - 0.75 | -24 (125) | -57 (46) |
| 0.75 - 1.0 | -150 (34) | -350 (17) |
| >= 1.0 | +267 (16) | -84 (7) |

It loses in almost every bin in both periods. The 0.75-1.0 bin lost most in both (-Rs 5,105 over 34 days; -Rs 5,950
over 17), but that is one bin among four, picked after looking; the >= 1.0 bin flips sign. On the 50 design / 24 holdout
days wider than 0.75 ATR14, B0.75 (15-minute range instead) made +Rs 2,153 / +Rs 48 where the plain range made -Rs 837
/ -Rs 6,540; skipping those days entirely would have made 0. "Do not trade the ORB on a wide day (W45 > 0.75 x
ATR14)" is a possible fresh pre-registration for the 6-year Dhan lake, not a finding. The ORB loses on the other days
too, so it would not make the ORB profitable.

## For the app

Nothing to build. No variant passed, so no paper change is proposed. The ORB family stays paused on paper with its
plain 09:15-10:00 range. If Boss wants the app to say why on a day like today, the honest line is: "Wide opening range
(about N x ATR14); the ORB sits out. Late breaks after 14:00 happened a little less than once a month in the last two years and
lost on average (13 design days, -Rs 567 in all)."

## Experiment log

`r20` (facts, not a test), `r20a` A15, `r20b` A30, `r20c` B0.5, `r20d` B0.75, `r20e` B1.0, `r20f` C1430, `r20g` D: all
7 variants rejected, holdout read once.
