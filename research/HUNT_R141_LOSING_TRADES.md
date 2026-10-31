# R141: every losing trade of our four paper arms, and the patterns in them

31 Oct 2026. Research only, **descriptive**: nothing here is a trading rule yet, and nothing in the app changes. Boss:
*"Can we check every losing trade and find a pattern in them for each arm"*. Pre-registration:
`research/hunt/r141_losing_trades/PREREG.md` (pushed before any number).

**What was looked at:**
- the arms' own trades, rebuilt exactly (to the rupee);
- design 20 Nov 2024 - 31 Mar 2026, split in two halves (H1 to Mar 2025, H2 Apr 2025 - Mar 2026), plus the final
  months Apr - 13 Oct 2026;
- 1 lot, after charges;
- a losing trade is net below 0.

**A "steady" pattern** must show more losers than the arm's average, and an average loss, in each of H1, H2 and the
final months separately (>= 30 trades in each).

**Not covered yet:** ORB, Night (R3), Solo, S5 Trail, S3-V2-E3 and the MCX / gold arms. Each needs its own engine;
they can follow.

## The answer in short

1. **On every arm about half the trades lose** (R72 53-54%, R51 55%, R52 44-49%, Liquidity 56-63%). The arms make
   money because their winners are bigger, not because they win often.
2. **The biggest share of the lost money is trades that never worked.** On R72, R51 and R52 the option never rose
   even 5% after entry.
   - These are 44-50% of all money lost on R72 and R51, and 35-39% on R52.
   - Almost all such trades lose: 92-98% of them.
   - They lose fast: on R72, 85-91% of trades that ended within 15 minutes lost, about Rs 1,300 each.
3. **About a quarter of the lost money is trades that "gave it back"**: up a median **+28-32%** on the option, then
   ended in a loss (22-30% of the money lost).
   - **R105 already tested 20 exits for exactly these** on R72 and R51. None passed: rescuing them sold too many
     winners early.
4. **Losses come in bunches.**
   - On R72, 42-43% of losing trades fall on index-days where every trade of the arm lost.
   - On Liquidity, 67-69%.
   - The longest losing streaks: R72 29 trades in design and 15 in the final; R51 20 and 18; Liquidity 13 and 12;
     R52 11 and 18.
5. **Most "obvious" patterns are not steady.** Weekday, index, call vs put and one-hour windows flip between periods.
   - Example: R72's 09:30-10:30 entries lost 74% in the final months but only 52% in design.
6. **The patterns that held in every period, at the moment of entry** (things the arm could see before buying):

| arm | steady losing pattern (all three periods) | design: trades, lose, avg | final: trades, lose, avg |
|---|---|---|---|
| R72 | **weak push into the entry** (2nd-lowest fifth of the last 3 bars' move, in ATR) | 614, 59%, -Rs 179 | 244, 61%, -Rs 128 |
| R72 | **after a very wide day** (yesterday's range in the top fifth vs its 20-day average). R140 already tested it as a filter: **it passed** | 616, 58%, -Rs 78 | 187, 58%, -Rs 120 |
| R51 | **after an earlier fake poke today** (the index already poked a new day extreme and closed back) | 945, 60%, -Rs 193 | 348, 58%, -Rs 141 |
| R51 | **weak push into the entry** (2 lowest-but-one fifths) | 841, 60%, -Rs 141 | 329, 61%, -Rs 161 |
| R51 | **mid-morning entries** (10:30-12:00) | 812, 58%, -Rs 170 | 320, 60%, -Rs 164 |
| R51 | **a quiet signal bar** (the last 5-min bar's range in the lowest fifth) | 842, 59%, -Rs 169 | 362, 62%, -Rs 164 |
| R51 | **after a very wide day** (as R72; R140: missed by one gate) | 842, 60%, -Rs 129 | 276, 59%, -Rs 82 |
| R51 | 15-min trend unclear; a wide 20-bar box; a gap against the trade | 829 / 842 / 842, 56-60% | 319 / 432 / 430, 56-59% |
| R52 | **none.** Its one 09:20 trade a day shows no steady pattern at entry. Its losses are the largest per trade (avg -Rs 2,361 design, -Rs 1,893 final), and its loser rate rose from 44% to 49% in the final | | |
| Liquidity | **a gap against the trade** (lowest fifth). R140 tested it as a filter: it **failed** in the final (p 0.48) | 176, 67%, -Rs 248 | 39, 67%, -Rs 37 |

## Each arm in plain words

**R72 Breadth Donchian** (3,071 design trades, 1,635 losers, -Rs 22.5 lakh lost; final 1,202 trades, 651 losers,
-Rs 7.9 lakh lost)
- **Half of the lost money** is trades that never worked: 680 + 284 trades.
- **Every stop-out is a loser** (568 + 174 trades, about -Rs 2,000 each).
- **A quarter** gave back a median +31% / +28% before losing. Mostly they ran out of time (256 + 122) or hit the stop
  (203 + 53).
- **Steady signs at entry:** a weak push into the break, and a very wide previous day. R140's filter on the second
  one passed every lose-less gate and is proposed for paper (TODO R140-1).

**R51 Donchian 20 + Supertrend** (4,208 design trades, 2,337 losers, -Rs 32.3 lakh lost; final 1,670, 921 losers,
-Rs 11.0 lakh)
- The same shape as R72: 44-49% of the lost money never worked, a quarter gave back about +30%.
- **R51 has the most steady losing signs of the four arms:** an earlier fake poke today, a weak push, a quiet signal
  bar, mid-morning entries, a wide previous day, an unclear 15-min trend.
- These overlap (a quiet, mid-morning, weak break after a fake poke is one kind of day). **They are leads for a
  pre-registered filter test, not results.**

**R52 Open outside value** (706 design trades, 313 losers, -Rs 7.4 lakh lost; final 293, 142 losers, -Rs 2.7 lakh)
- One trade a day at 09:20, so each losing trade is its whole day.
- **Fewer losers (44%), but big ones:** 90 of 313 design losers were above Rs 3,000.
- When it never worked, it lost on average Rs 3,454 (design) and Rs 2,335 (final).
- **No steady sign at entry was found.** Nothing to filter yet.

**Liquidity 15+5** (881 design trades, 497 losers, -Rs 5.0 lakh lost; final 357, 224 losers, -Rs 2.2 lakh)
- **Many small losses** (avg about -Rs 1,000). The money comes from the few trades that run:
  - trades held 1-3 hours lost only 26-28% of the time and made about Rs 2,200-3,000 each;
  - trades held over 3 hours made about Rs 5,500-6,000 each.
- **Quick exits** (15 minutes or less) lose 63-69%.
- **Its losers come in bad days:** two-thirds fall on days when every Liquidity trade lost.
- **Its loser rate rose** from 56% to 63% in the final months; it made only +Rs 31 a trade there.
- **Steady sign:** a gap against the trade (already tested in R140, failed).

## What this suggests testing next (each needs its own pre-registered test with R140's lose-less gates)

1. **R72 wide-day filter:** already passed in R140. It waits for Boss's go-ahead as a paper switch (TODO R140-1).
2. **R51 "bad-break" filter:** skip R51 breaks that come after an earlier fake poke today, or with a weak push or a
   quiet signal bar.
   - These held in all three periods here, but they were found by searching, so they must pass a fresh test.
   - The final months are no longer unseen for them; a forward log would be the honest check.
3. **A "no progress" exit on R72 / R51:** sell if the option has not gained some small amount within N minutes.
   - The biggest losing group is trades that never worked, and they lose fast.
   - R81 tried a no-progress exit on Liquidity, where it was the worst idea: Liquidity's slow starters include its
     runners.
   - On the Donchian arms it has not been tested.
4. **Not worth repeating:**
   - exits for the "gave it back" trades (R105, 0 of 20);
   - weekday or index filters (not steady);
   - 15-min structure filters (R140, 0 of 12).

## Files

- `research/hunt/r141_losing_trades/`: `PREREG.md`, `r141.py`, `test_r141.py`, `results/losers.json`. That file
  holds every table: exit reason, minutes held, peak type, loss sizes, index, side, weekday, time, clustering, the 17
  entry features by fifth per period, and a depth-2 tree per arm.
- Entries: `research/experiments/entries/r141-*.yaml`.
