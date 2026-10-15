# R62b: the "Market Structure Break & Order Block" indicator (15 Oct 2026): 0 of 16 pass; win rate 21-54%, not 75-80%

**Verdict.** No variant passes. Boss's second video (15 Oct) showed the TradingView indicator "Market Structure Break &
Order Block" with fib factor 0.273, buying pullbacks into its green zones and selling into its red ones. It was tested
on the five index options, Nov 2024 onwards only.
- **0 of 16 gated variants pass.** None passed the design walk-forward (20 Nov 2024 - 31 Mar 2026): 15 of 16 lost
  money there and the 16th was positive in only 2 of 5 quarters.
- **The win rate is 21-44% in design and 30-54% in the final test, against the 75-80% the video claims.** Average wins
  are bigger than average losses, but not by enough to pay for losing more often than winning.
- Four variants made money in the final test (1 Apr - 14 Oct 2026). All four are 15-minute "rejection" entries, the
  family that lost the MOST in design (up to -Rs 1.02 lakh), and in each one the 5 best trades earned more than the
  whole net. A reversal like that is not a reason to trade it.

Research only: nothing in the app changes and nothing goes on paper.

## The indicator: source OBTAINED and ported line by line (not a reconstruction)

- The published script is **"Market Structure Break & Order Block" by EmreKb** (open source, Pine v5, Mozilla Public
  License 2.0, https://www.tradingview.com/script/DkE7UniD/; latest release note 8 May 2023). "Sith Trader" is the
  video's presenter, not the script's author. Its source was read from TradingView's public script service on 15 Oct
  2026 and ported to Python line by line (`research/hunt/r62b/r62bmsb.py`, with "Lnn" comments naming each source line
  and the MPL notice). The Pine file itself is not committed.
- Settings: zigzag length 9 (the default; the video does not change it), fib factor **0.273** (the video; the script's
  default is 0.33).
- **What the code actually does** (this differs from how the indicator is usually described):
  - **Zigzag:** the trend turns down when a bar makes the lowest low of the last 9 bars, up when it makes the highest
    high of the last 9. The swing low (high) is recorded only when the trend turns, so it is known late. That is
    honest (no repainting), and the port is tested for it.
  - **Market-structure break:** the live rule compares the confirmed swings, not a close. A bearish MSB is the newest
    swing low below the previous one by more than 0.273 x the last leg; a bullish MSB is the mirror. The "price closes
    beyond the swing by fib x the leg" rule that many write-ups describe is only a commented-out line (106) in the
    script. Because the swings are confirmed late, a bearish break is only known after price has already bounced 9
    bars up from the new low.
  - **Order block:** the last opposite-colour candle in the swing range before the impulse; the zone is that candle's
    full high-low range.
  - **Script bug, not copied:** when price closes through a zone, the script deletes the OLDEST box in its list, not
    the broken one. R62b uses the intended rule: a zone dies when a bar closes beyond its far side.
- **On the phone:** the app's Pine interpreter (`android/engine`) has the pieces this script uses (arrays,
  `ta.barssince`, `ta.valuewhen`, a separate history for each `ta.*` call), and its per-call history matches the
  reading the port uses for line 107. R62b did not run the script there, and no android code was changed.

## What was tested (the signal source of every rule)

Every rule reads the **INDEX** (spot 1-minute bars of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX). The 1-ITM
nearest-expiry option is only what is bought. No option-chain input is used anywhere.

| piece | rule (pre-registered in `research/hunt/r62b/PREREG.md` before any P&L) | read from |
|---|---|---|
| MSB and zone | the ported indicator on the index's 5-minute or 15-minute bars | index |
| fresh zone | the MSB bar must close beyond the zone, so price has to come back to it | index |
| entry `tch` | the first index minute that touches the zone; buy at the next minute | index |
| entry `rej` | the first signal bar that touches the zone and closes back out of it (or the next bar, in the trend's colour) | index |
| trend filter `h` | the same indicator's state on 1-hour index bars must agree | index |
| stop | just beyond the swing (0.02% outside the swing low / high); the option is sold when the INDEX crosses it | index |
| target `st` | the opposing swing (for a buy, the swing high that made the break) | index |
| target `rr` | the same, taken only when reward:risk at the fill is at least 1.5 | index |
| time exit | 12 signal bars after the fill (60 / 180 minutes) | clock |
| square-off | 15:15; 15:05 on expiry days | clock |

16 variants = timeframe (5m, 15m) x entry (tch, rej) x trend filter (off, on) x target (st, rr), one
Benjamini-Hochberg family. Fib 0.33 and 0.50 were run as information only.

**Measurement.** One held option contract per trade (never the rolling ATM series), next-minute fills, R17 'liq' fills
and dated charges (R51 engine), the R47 foreign / stray-row filter and the `spikes.py` rule on the option prints, real
lot sizes in force, 1 lot. 2x = double slippage and double charges. Gates as R47-R62: final net > 0, p < 0.05 against
500 random-entry twins, BH q <= 0.10, 40+ trades, top-5 trades < 100% of net, positive in design in at least 3 of 5
quarters.

## The win rate next to the money

**Design, 20 Nov 2024 - 31 Mar 2026** (2,440 trades over the 16 variants; 1 lot):

| variant | trades | win | avg win / loss (Rs) | realised R | net 1x (Rs) | net 2x (Rs) | max DD (Rs) |
|---|---|---|---|---|---|---|---|
| 5m-tch-x-st | 415 | 37% | +1,763 / -1,325 | -0.40 | -80,471 | -1,23,912 | 95,338 |
| 5m-tch-x-rr | 361 | 34% | +1,918 / -1,330 | -0.48 | -83,832 | -1,21,783 | 93,680 |
| 5m-tch-h-st | 193 | 36% | +1,845 / -1,245 | -0.04 | -24,032 | -44,127 | 51,495 |
| 5m-tch-h-rr | 166 | 33% | +1,952 / -1,208 | -0.08 | -26,712 | -44,139 | 50,829 |
| 5m-rej-x-st | 276 | 44% | +1,584 / -1,514 | +0.05 | -46,050 | -74,961 | 53,340 |
| 5m-rej-x-rr | 201 | 42% | +1,704 / -1,354 | +0.08 | -12,261 | -33,229 | 35,545 |
| 5m-rej-h-st | 128 | 42% | +1,763 / -1,467 | +0.03 | -13,395 | -27,086 | 27,080 |
| 5m-rej-h-rr | 95 | 41% | +1,934 / -1,301 | +0.06 | +2,560 | -7,597 | 15,580 |
| 15m-tch-x-st | 135 | 33% | +2,391 / -2,021 | -0.16 | -74,292 | -88,724 | 85,800 |
| 15m-tch-x-rr | 112 | 32% | +2,501 / -1,976 | -0.22 | -60,109 | -72,004 | 69,242 |
| 15m-tch-h-st | 55 | 25% | +1,555 / -1,973 | -0.35 | -59,107 | -64,866 | 60,821 |
| 15m-tch-h-rr | 42 | 26% | +1,072 / -1,785 | -0.42 | -43,542 | -47,815 | 45,257 |
| 15m-rej-x-st | 112 | 31% | +2,334 / -2,392 | -0.26 | -1,02,475 | -1,14,038 | 1,07,887 |
| 15m-rej-x-rr | 74 | 26% | +3,075 / -2,140 | -0.31 | -59,263 | -66,848 | 62,905 |
| 15m-rej-h-st | 47 | 26% | +963 / -2,750 | -0.42 | -84,702 | -89,533 | 84,749 |
| 15m-rej-h-rr | 28 | 21% | +1,329 / -2,015 | -0.39 | -36,348 | -39,045 | 36,348 |

**Final test, 1 Apr - 14 Oct 2026, read once** (1,094 trades over the 16 variants):

| variant | trades | win | avg win / loss (Rs) | realised R | net 1x (Rs) | net 2x (Rs) | max DD (Rs) | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| 5m-tch-x-st | 166 | 34% | +1,924 / -1,381 | -0.10 | -44,135 | -62,799 | 44,373 | 0.076 | 0.125 | FAIL |
| 5m-tch-x-rr | 142 | 35% | +1,981 / -1,374 | -0.09 | -30,683 | -46,953 | 32,027 | 0.026 | 0.069 | FAIL |
| 5m-tch-h-st | 84 | 40% | +1,856 / -1,442 | -0.02 | -9,002 | -18,361 | 22,592 | 0.086 | 0.125 | FAIL |
| 5m-tch-h-rr | 73 | 41% | +1,846 / -1,503 | -0.03 | -9,235 | -17,572 | 22,592 | 0.112 | 0.138 | FAIL |
| 5m-rej-x-st | 130 | 39% | +1,946 / -1,702 | -0.07 | -35,256 | -50,304 | 41,301 | 0.148 | 0.148 | FAIL |
| 5m-rej-x-rr | 96 | 41% | +1,895 / -1,556 | -0.07 | -14,766 | -26,004 | 20,329 | 0.084 | 0.125 | FAIL |
| 5m-rej-h-st | 65 | 43% | +1,946 / -1,551 | +0.04 | -2,899 | -10,462 | 15,005 | 0.106 | 0.138 | FAIL |
| 5m-rej-h-rr | 46 | 48% | +1,779 / -1,669 | +0.08 | -896 | -6,378 | 11,362 | 0.126 | 0.144 | FAIL |
| 15m-tch-x-st | 56 | 36% | +3,269 / -2,229 | -0.01 | -14,872 | -21,393 | 27,501 | 0.006 | 0.024 | FAIL |
| 15m-tch-x-rr | 50 | 30% | +3,581 / -2,221 | -0.07 | -24,003 | -29,900 | 33,220 | 0.040 | 0.091 | FAIL |
| 15m-tch-h-st | 24 | 42% | +2,801 / -2,494 | +0.11 | -6,914 | -9,608 | 21,547 | 0.050 | 0.100 | FAIL |
| 15m-tch-h-rr | 21 | 33% | +3,178 / -2,494 | +0.00 | -12,674 | -15,056 | 23,001 | 0.148 | 0.148 | FAIL |
| 15m-rej-x-st | 55 | 46% | +3,694 / -2,129 | +0.14 | +28,474 | +22,085 | 16,987 | 0.002 | 0.011 | FAIL (top 5 = 147% of net; design -1.02 lakh) |
| 15m-rej-x-rr | 45 | 40% | +3,814 / -1,973 | +0.14 | +15,395 | +10,087 | 22,612 | 0.002 | 0.011 | FAIL (top 5 = 256%; design) |
| 15m-rej-h-st | 24 | 54% | +3,583 / -1,905 | +0.32 | +25,623 | +23,022 | 9,261 | 0.002 | 0.011 | FAIL (< 40 trades; top 5; design) |
| 15m-rej-h-rr | 17 | 47% | +3,514 / -1,572 | +0.40 | +13,964 | +12,080 | 9,649 | 0.010 | 0.032 | FAIL (< 40 trades; top 5; design) |

Max drawdown, worst day and months green for every variant, and every per-index table, are in
`hunt/r62b/results/report.md`.

**Win rate against the claim.** The best win rate of any gated variant is 54% (15m-rej-h-st, 24 final trades); the
pooled rates are 36% in design (2,440 trades) and 39% in the final (1,094 trades). Nothing comes near 75-80%, with or without the trend filter. The R48 lesson was a high
win rate with small wins and big losses; here it is the other way round. Wins average 1.1-2.2 times the losses in
the final test (0.35-1.6 times in design), but the rule wins only a third to a half of the time, so the trades lose
money after costs.

## Per index (summed over the 16 variants; a sum, not a tradable book)

| index | design trades | design 1x (Rs) | design 2x (Rs) | final trades | final 1x (Rs) | final 2x (Rs) | variants losing in final |
|---|---|---|---|---|---|---|---|
| NIFTY | 484 | -1,57,674 | -1,97,480 | 253 | -545 | -21,690 | 7 of 16 |
| BANKNIFTY | 547 | -79,151 | -1,41,889 | 249 | +1,21,482 | +88,626 | 2 of 16 |
| FINNIFTY | 438 | -1,35,302 | -1,87,185 | 199 | -1,50,084 | -1,77,001 | 14 of 16 |
| MIDCPNIFTY | 499 | -3,23,716 | -3,90,756 | 183 | -1,62,017 | -1,90,257 | 12 of 16 |
| SENSEX | 472 | -1,08,186 | -1,42,395 | 210 | +69,287 | +52,809 | 3 of 16 |

BANKNIFTY and SENSEX were positive in the final test after losing in design on every variant total. Picking them now,
after the fact, would be choosing the two best of five; that is what chance gives.

## Why it loses

- **The stop is tight and the option is expensive to trade.** On 5-minute bars the swing stop is a median 0.15% of the
  index away (about 35 NIFTY points) and the opposing swing 0.45%; on 15-minute bars 0.27% and 0.78% (design trades). 46% of all trades end at the stop. An option
  round trip costs about Rs 80 a lot in charges plus the spread, so the small winners do not pay for the stops.
- **Before costs the setups are close to random.** Mean realised R is between -0.48 and +0.08 in design for every
  variant: no edge on the index itself.
- **The random-entry twins lose even more.** Random entries with the same tight stops lose Rs 10,632-75,413 a variant
  on average in the final test, so several LOSING variants have small p values (15m-tch-x-st: -Rs 14,872, p 0.006). A
  low p here means "lost less than random", not "made money"; the net and design gates are what decide.
- **Most zones are never traded.** Of about 350 fresh 5-minute zones an index in design, only about 100 gave a first
  touch inside the 09:25-14:30 window; the rest were broken, replaced or out of time first.

## Sensitivity (information only, not gated)

- **Fib 0.33** (the script's default): 0 of 16 positive in design; in the final the same four 15-minute rejection
  variants are positive (+Rs 13,066 to +30,926).
- **Fib 0.50:** 3 of 16 positive in design. One, 5m-rej-h-rr, passes the design rule (+Rs 6,856 on 89 trades, 3 of 5
  quarters) and then makes +Rs 1,226 on 46 final trades; 9 of 16 are positive in the final, mostly by a few thousand
  rupees.
- The factor changes trade counts by about 10-20% and does not make the rule work in design. Choosing 0.50 now, after
  seeing this, would be fitting to the past.

## Honesty notes

- Pre-registered (`research/hunt/r62b/PREREG.md`, committed before any P&L), with Amendment 0 committed before any P&L
  (invalid fills skipped, the time exit's row, the last session 14 Oct 2026, one try per zone, sensitivity without
  twins).
- The final test was read once (`hunt/r62b/results/FINAL_READ`). It is not pristine: earlier studies (R47-R62) have
  seen these sessions, never with these rules.
- Data rule: sessions from 20 Nov 2024 only; bars from 1 Sep 2024 only warm up the zigzag (causal).
- Sample sizes are given with every number above. Experiment entries: `research/experiments/entries/r62b-*.yaml`
  (16 variants + 1 sensitivity entry).
- The sibling study R62 (volume profile + OB / FVG / BOS, branch `claude/r62-volume-profile-smc`) also found 0 of 32;
  R62b does not repeat it (different swing and break rules: the indicator's own zigzag and fib margin).

## Files

- `research/hunt/r62b/PREREG.md` (pre-registration and Amendment 0), `r62bcfg.py` (settings), `r62bmsb.py` (the
  indicator port and the setup walk), `r62bdata.py` (data build), `r62bsim.py` (trades and twins), `r62bstudy.py`
  (design / final / report), `r62bentries.py` (experiment entries), `test_r62b.py` (24 tests on hand-made bars,
  including the look-ahead guard).
- `research/hunt/r62b/results/`: `design.json`, `final.json`, `extras.json` (per index at 2x), `report.md`,
  `build.jsonl` (the data build's counts), `FINAL_READ`.
- `research/hunt/r62b/CONCEPTS_ADD.md`: zigzag, MSB, BOS / ChoCH, order block and fib confirmation, written in R61's
  concept-book format for merging into Jarvis's / Solo's concept book.
