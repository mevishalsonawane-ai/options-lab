# R138: the triple-top fake breakout. After it, the index does what it does at any other moment. 0 of 18 trades pass (5-point band, no band, and 7 enhancements)

31 Oct 2026. Research only (no app change). Boss, 31 Oct: *"on 15min timeframe, if there are 2 candles are touching a
same resistance (till 5 points is ok), and creating a M pattern. And if there is a 3 time its touch the same resistance
and give a fake breakout, then check what happens."*

**Signal source: the index spot's 15-minute candles, on wicks (highs and lows).** "Closed back below" uses the candle's
close. The trade buys the ATM option; nothing is read from an option's chart.

## The answer

**What happens after the fake breakout: nothing special.** The move after it looks like the move after any 15-minute
close at the same time of day.
- **Design, Aug 2021 - 15 Mar 2026: 1,361 M fake breakouts** on the five indices.
- **60 minutes later the index was lower 51% of the time** (986 with a full hour left). For random closes at the same
  time of day (the control, 20 other sessions per event) it was 49%. The average move was -0.006% vs -0.004%: about 1
  NIFTY point. p 0.88, q 1.0.
- **To 15:20:** -0.002% vs -0.012% for the control (p 0.41).
- **Does it fall to the M's neckline first?**
  - **21% of the time** it reached the neckline first.
  - **61% of the time** it went back **above the fake high** first: the breakout was real after all.
  - **18%** reached neither by 15:20.
  - The control, with the same distances, gave **21% / 62%**: the same. p 0.91.
- **Why the fake high wins so often:** it is close and the neckline is far (median 20 points to the fake high, 61 to
  the neckline, all indices together). The odds are what those distances give for a random price path. The pattern
  adds nothing.
- **Holdout, 1 Apr - 19 Oct 2026, read once: 156 events.** Neckline first 26%, fake high first 53%. Control 23% / 58%.
  No difference (p 0.44).

**None of the 16 versions of the M differs from the control** (q ≥ 0.26 over 100 tests). The 16 versions are: the
level (the higher high, or the average), the poke (1 tick, or the full tolerance), the fail (in one candle, or two),
and the touches (same session, or spanning yesterday).

**The same holds:**
- **by index:** neckline first 16-24%, fake high first 57-64% (212-305 events each);
- **by year:** neckline first 12-23%, fake high first 58-63%, 2021-2026 (64-376 events a year);
- **by time of day:** most events come after 12:00, because an M needs time to form. From 15:00 there is too little
  time left: 8% neckline, 30% fake high, 62% neither (256 events).

## Round 2 (Boss: "Remove that 5point and try again, still loss then try enhancing it"): 0 of 10 pass

Pre-registered as Amendment A (`PREREG.md`, commit 80035f4) before any number. The holdout months had already been
read once for this pattern, so round 2's read of them is labelled **"already seen"**.

**Removing the 5-point band makes it worse, not better.**

| version | design trades | won | net per trade (2x slip) | total, 1 lot | p / q | already-seen holdout |
|---|---|---|---|---|---|---|
| R138's T1 (5 points), for comparison | 839 | 34% | -Rs 118 (-Rs 138) | -Rs 99,330 | 0.43 / 1.0 | 120 trades, +Rs 36 each |
| **U0** no band at all | 3,970 | 29% | **-Rs 200** (-Rs 222) | -Rs 7,94,646 | 1.0 / 1.0 | 513, -Rs 7 |
| **U1** band 0.25 x ATR (the base for round 2) | 1,515 | 30% | -Rs 199 (-Rs 220) | -Rs 3,00,934 | 0.98 / 1.0 | 190, +Rs 72 |
| **U2** band 0.5 x ATR | 2,530 | 30% | -Rs 209 (-Rs 230) | -Rs 5,28,791 | 1.0 / 1.0 | 338, -Rs 22 |

- **A wider band means more M's** (6,510 with no band against 1,361 with 5 points), but still no edge. 60 minutes
  later the index was lower 48% of the time, against 49% for random closes. Neckline first 19% (control 19%), back
  above the fake high first 67% (control 65%). q ≥ 0.41 for every test of round 2.
- **Why it loses more without the band:** the extra M's are looser, and the option's costs are the same per trade.

**The seven enhancements, on U1 (the base with the best design net per trade):**

| enhancement | design trades | won | net per trade (2x slip) | total | worst month | p | already-seen holdout |
|---|---|---|---|---|---|---|---|
| **E1** only events by 14:00 | 1,040 | 28% | -Rs 262 (-Rs 284) | -Rs 2,72,607 | -Rs 44,369 | 1.0 | 130, +Rs 68 |
| **E2** neckline ≥ 1.5x the stop | 1,195 | 25% | -Rs 209 (-Rs 231) | -Rs 2,49,753 | -Rs 43,353 | 1.0 | 145, +Rs 108 |
| **E3** only below the day's open | 699 | 34% | -Rs 140 (-Rs 161) | -Rs 98,041 | -Rs 35,853 | 0.63 | 75, +Rs 45 |
| **E4** 60% lock after +Rs 1,000, no target | 1,546 | 37% | -Rs 137 (-Rs 161) | -Rs 2,12,408 | -Rs 15,699 | 0.84 | 195, -Rs 137 |
| **E5** R123's network filter (2023 - Mar 2026) | 142 | 40% | **-Rs 107** (-Rs 124) | -Rs 15,137 | -Rs 8,724 | 0.15 | 5, +Rs 535 |
| **E6** flip: buy the CALL when it closes back above the fake high, 2R | 700 | 37% | -Rs 154 (-Rs 177) | -Rs 1,07,748 | -Rs 22,358 | 0.49 | 89, -Rs 243 |
| **E7** E1 + E2 + E3 | 374 | 27% | -Rs 187 (-Rs 208) | -Rs 70,007 | -Rs 34,772 | 0.83 | 39, +Rs 43 |

- **Every enhancement loses in design** at 1x and 2x slippage. BH q = 1.0 over all 18 R138 variants. None is near 65%
  wins (best 40%).
- **The network helped most,** as in R132-R137. It cut the loss from -Rs 198 to -Rs 107 a trade on the same days
  (1,255 trades down to 142, p 0.15). It is still a loss.
- **The flip (E6) also loses.** The breakout after a fake breakout comes as often as at any other moment (R138's
  control), so there is nothing to catch there either.
- **The already-seen holdout months were again kind to some puts** (E2 +Rs 108 a trade on 145 trades, U1 +Rs 72 on
  190), as in round 1. All of them lost in design: U1 and E2 in every fold, E3 and E7 in 3 of 4. This is the one
  period, not the pattern.

**Verdict, round 2:** rejected. The fake breakout of an M has no edge on Indian indices, with or without the 5-point
band, and no filter or exit tried makes it pay. Nothing goes on paper or live.

## Round 1 details

### The tradable version: 0 of 8 pass

The trade:
- **signal:** the M's fake breakout on the index;
- **entry:** buy the ATM put at the next candle's open;
- **stop:** the fake high + 0.02%;
- **target:** the neckline, or 2R;
- **exit:** out by 15:20;
- **size and costs:** 1 lot, real charges and slippage;
- **check:** against 100 random-entry twins per trade.

All 8 versions **lose in design at 1x and 2x slippage**. Seven of them lose in every one of the four test years
(2023-2026 Q1); T3 made money in 2024 only.

| variant | design trades | win (vs 65%) | avg win / loss | net per trade (2x slip) | total, 1 lot | drawdown / worst month | p / q | holdout (read once) |
|---|---|---|---|---|---|---|---|---|
| **T1** M, neckline target (primary) | 839 | 34% | +Rs 1,025 / -Rs 700 | **-Rs 118** (-Rs 138) | -Rs 99,330 | -Rs 1,08,716 / -Rs 11,610 | 0.43 / 1.0 | 120 trades, 38% won, +Rs 36 each |
| **T2** M, 2R target | 859 | 35% | +Rs 986 / -Rs 715 | -Rs 115 (-Rs 134) | -Rs 98,795 | -Rs 1,06,585 / -Rs 13,169 | 0.56 / 1.0 | 120, 40%, -Rs 38 |
| **T3** M, two-candle fail | 256 | 43% | +Rs 983 / -Rs 868 | -Rs 65 (-Rs 83) | -Rs 16,732 | -Rs 28,217 / -Rs 7,238 | 0.97 / 1.0 | 25, 60%, +Rs 173 |
| **T4** M, touches may span yesterday | 1,419 | 35% | +Rs 1,264 / -Rs 879 | -Rs 124 (-Rs 144) | -Rs 1,75,531 | -Rs 1,87,737 / -Rs 20,170 | 0.06 / 0.48 | 199, 37%, -Rs 27 |
| **T5** M, poke ≥ the tolerance | 682 | 36% | +Rs 1,056 / -Rs 801 | -Rs 139 (-Rs 158) | -Rs 94,867 | -Rs 1,18,189 / -Rs 13,621 | 0.86 / 1.0 | 73, 45%, +Rs 70 |
| **T6** M, average level | 799 | 33% | +Rs 1,012 / -Rs 709 | -Rs 134 (-Rs 154) | -Rs 1,06,991 | -Rs 1,17,125 / -Rs 11,569 | 0.67 / 1.0 | 122, 37%, -Rs 34 |
| **T7** W mirror (buy the call), neckline | 821 | 33% | +Rs 1,050 / -Rs 803 | -Rs 196 (-Rs 217) | -Rs 1,61,174 | -Rs 1,64,808 / -Rs 15,647 | 1.0 / 1.0 | 120, 42%, +Rs 75 |
| **T8** W mirror, 2R | 827 | 35% | +Rs 1,059 / -Rs 839 | -Rs 169 (-Rs 190) | -Rs 1,40,003 | -Rs 1,59,626 / -Rs 15,420 | 0.99 / 1.0 | 121, 39%, +Rs 17 |

Notes on the table:
- p is against random-entry twins with the same stop, target and exits; q is BH over the 8 variants.
- **None reaches Boss's 65% win rate.** The best is 43% (T3). The 65% rule is reported, not a gate here: it is for
  Jarvis's own strategies (Boss, 29 Oct).
- **Five versions were positive in the holdout.**
  - T1, T5, T7 and T8 made +Rs 17 to +Rs 75 a trade on 73-121 trades. All four lost in all four design years, so this
    is the noise between periods, not an edge.
  - T3 made +Rs 173 a trade, but on only 25 trades (50 needed), after losing in 3 of 4 design years.
  - CLAUDE.md: failed ideas stay notes.

## Is this different from what we knew?

- **It agrees with R135, R22, R130-R131 and R61B.** On Indian indices a poke through a level and back does not
  reliably reverse:
  - R135: sweeps of yesterday's, last week's and last month's highs and lows, and of the opening range, continue more
    often than they reverse.
  - R22: morning-range levels break more than they hold.
  - R130-R131: sweep-and-reverse entries lost.
  - R61B: trap checks did not help Solo.
- **The M is a little different from R135's sweeps.** After the M's fake breakout the index neither reverses nor
  continues more than normal: it is a coin flip with the usual odds. R135's sweeps of daily, weekly and monthly levels
  leaned towards continuation.
- **The W mirror (a triple-bottom fake breakdown) leaned the R135 way in design.** After it, the index reached the
  neckline (up) first only 20% of the time, against 24% for the control (1,262 events, q 0.01). That means slightly
  more continuation down. But the holdout went the other way (28% vs 21%, 167 events, p 0.06), and the W trades lost
  in design. Not usable.
- **The fake breakout makes the M worse, not better.** A plain double top, measured when its second touch is done
  (6,004 events), reached its neckline first 43% of the time before poking above the level (45%). After the fake
  breakout it is 21% (q 0.01). The two races start at different moments, so this is not like for like. But nothing
  suggests the third poke is a sell signal.

## What is proposed

**Nothing.**
- No arm, no paper change, no Jarvis rule.
- If Boss sees a triple top with a fake breakout on the chart, the data says: treat the next hour as a coin flip. The
  breakout is more likely to resume (61%) than to fall to the neckline (21%), and the same is true at any other
  moment with those distances.

## How it was done

- **Pre-registration:** `research/hunt/r138_triple_top_fake/PREREG.md`, commit 1b29d4b, before any number. The design
  results were committed (8da85a1) before the one holdout read, which is guarded by a lock file.
- **The pattern (15-minute candles from 09:15, wicks):**
  - **Two touches:** two candle highs within the tolerance, at least 2 candles apart, with no higher high between them.
  - **Pullback:** the lowest low between the touches (the neckline) is at least 1.0 x ATR14 below the level.
  - **Third touch:** a later candle (at least 2 after the second touch) pokes above the level by 1 tick and closes
    back below.
  - **Tolerance:** NIFTY 5 points (Boss's number). The other indices use 0.022% of price. At the events' prices that
    was a median of BANKNIFTY 10.6, FINNIFTY 4.6, MIDCPNIFTY 2.3 and SENSEX 16.0 points.
- **Data:** the Drive lake (index minutes and the weekly and monthly option minutes) for the five indices. Design is
  Aug 2021 - 15 Mar 2026; the holdout is 1 Apr - 19 Oct 2026.
- **Options:** the ATM option of the nearest expiry that does not expire that day, so the exit is always 15:20. Days
  when the weekly and monthly expire together have no such contract in the lake and are left out, as in R131-R137.
- **Costs:** R131's dated charges and liquidity-aware slippage.
- **Tests:** `test_r138.py`, 18 tests: the M and each variant on hand-built candles, the tolerance per index, the W
  mirror, the measures, the trade's stop and target, round 2's bands, filters and flip, and two look-ahead guards.
- **Round 2:** `r138b.py`; results `results/amendA_design.json` (committed before the read) and
  `results/amendA_holdout_seen.json`.
- **Results:** `results/design.json`, `results/holdout.json`. Event and trade files are in `$R138_OUT`, not in git.
- **Entries:** `research/experiments/entries/r138-t1.yaml` … `r138-t8.yaml`, `r138-u0.yaml` … `r138-u2.yaml` and
  `r138-e1.yaml` … `r138-e7.yaml` (all rejected), and `r138-describe.yaml` (the facts).
