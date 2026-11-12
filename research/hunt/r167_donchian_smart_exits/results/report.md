# R167: "smart" exits for R51 / R72, where the stop and target follow the market (12 Nov 2026)

## For Boss, in short

**Nothing passed (0 of 102 tests), so nothing changes in the app.** None of the "smart" exits, and none of the three
rules for your giveback problem, raised the win rate by 5 points without losing money.

### Your "+Rs 1.5k to 2.5k, then a Rs 2k-3k loss" problem

**It is real, and at 2-3 lots it is common.**

Rs 1,500 on 3 lots is only about Rs 500 a lot (about 7 NIFTY premium points), so most trades touch it on a normal
wiggle. Apr - Oct 2026, today's exits:

| arm | lots | trades | reached +Rs 1,500 | then ended at Rs 0 or worse | then ended at -Rs 2,000 or worse |
|---|---|---|---|---|---|
| R72 | 1 | 1,202 | 407 (34%) | 42 (-Rs 37,048 in all) | 6 (-Rs 14,960) |
| R72 | 3 | 1,202 | 774 (64%) | 240 (-Rs 7,54,528) | 144 (-Rs 6,65,573) |
| R51 | 1 | 1,670 | 544 (33%) | 66 (-Rs 69,257) | 9 (-Rs 30,804) |
| R51 | 3 | 1,670 | 1,069 (64%) | 346 (-Rs 11,46,891) | 196 (-Rs 10,11,522) |

At 3 lots, about 1 trade in 5 does exactly what you described. The design years (Nov 2024 - Mar 2026) show the same
pattern: R72 at 3 lots, 735 of 3,071 trades.

**But locking that profit in costs more than it saves.** The trades that dip after +Rs 1,500 and then recover are the
arms' big winners. Apr - Oct 2026:

| rule (all tighten-only) | R72, 1 lot | R72, 3 lots | R51, 1 lot | R51, 3 lots |
|---|---|---|---|---|
| today | win 45.8%, Rs 1,71,366 | win 46.5%, Rs 6,25,048 | win 44.9%, Rs 1,34,743 | win 46.0%, Rs 5,57,695 |
| **G2** keep +Rs 500, trail Rs 1,000 under the best | **win 49.1%**, Rs 1,02,962 | **win 61.4%**, Rs 44,292 | **win 48.9%**, Rs 75,099 | **win 63.1%**, Rs 58,130 |
| **G3** 3-minute index trail after +Rs 1,500 | win 48.5%, Rs 1,30,008 | win 45.6%, Rs 1,73,403 | win 47.3%, Rs 76,556 | win 48.0%, Rs 2,09,380 |
| **G1** breakeven lock after +Rs 1,500 | win 43.7%, Rs 1,64,146 | win 28.4%, Rs 3,11,497 | win 42.3%, Rs 1,31,543 | win 28.4%, Rs 3,73,116 |

- **G2 nearly ends your problem.** At 3 lots only 79 R72 trades still go from +Rs 1,500 to Rs 0 or worse (240 today),
  and the win rate jumps 15-17 points. But the profit falls by about 90%.
  - At 1 lot it adds 3-4 points and costs Rs 60-68k.
  - It does beat random exits (twin p 0.002), so it is a real effect, not luck. It just costs too much.
- **G1 lowers the win rate.** The breakeven lock is sold with a stop order. Slippage turns most "Rs 0" exits into
  -Rs 20 to -100, which counts as a loss.
- **On the trades the app actually takes (R151's picker), same picture.** G2 at 1 lot: R72 +5.1 points but
  -Rs 11,032; R51 +4.4 points and -Rs 24,987. At 3 lots it costs Rs 3.7-4.5 lakh.

**So the drop from +Rs 1.5k to a loss is the price of the big winners.** These arms make money because the average win is
bigger than the average loss (R72 about Rs 1,748 vs Rs 1,217). If you want fewer of those painful trades, the cheapest
lever found so far is the lot count, not the exit: the same moves are 3x smaller in rupees at 1 lot.

### The "smart" exits (stop / target that follow levels, volatility, volume, momentum)

None raised the win rate. Most lowered it, and all but two made less money than today's exits in the final period
(1 lot, Apr - Oct 2026):

| rule | what it does | R72 win / net vs today | R51 win / net vs today |
|---|---|---|---|
| SR-T | sell before the next resistance; stop under each broken level | -0.3 pts / -Rs 1,10,203 | -0.3 pts / -Rs 1,18,255 |
| VOLX-T | 3 x ATR trail, 4 x ATR target (index) | -0.3 / -Rs 36,544 | -0.1 / -Rs 20,909 |
| VOLP-T | 3 x the option's own ATR trail | -5.9 / -Rs 73,715 | -6.5 / -Rs 94,217 |
| FLOW-T | volume spike or OI build-up against -> tight trail | -4.3 / -Rs 40,190 | -4.5 / -Rs 29,279 |
| MOM-T | 1-minute Supertrend turns against | -2.7 / -Rs 17,594 | -3.2 / -Rs 20,899 |
| CTX-T | a different rule per market mood | +0.1 / -Rs 3,507 | -0.9 / -Rs 99,825 |
| ML-T | a model predicts if the move continues | -4.0 / -Rs 1,18,264 | -2.7 / -Rs 87,305 |

- The free versions (allowed to widen the stop or hold longer; research only) were no better.
  - MOM-F made a little more on R51 (+Rs 11,677) and in the walk-forward year, but with a win rate 6-9 points lower.
  - The free index-ATR stop (VOLX-F) made Rs 1.37 lakh more on R51 in the walk-forward year, but lost Rs 46,064 in the
    final period, with more than double the drawdown.
- Some rules did lower the worst day and the drawdown at the cost of profit. FLOW-T on R72, 1 lot: worst day -Rs 14,495
  vs -Rs 22,571; drawdown Rs 45,148 vs Rs 61,884; Rs 40,190 less profit.

**What would ever go live:** only tighten-only (T) rules, after a shadow phase behind an off-by-default switch. None
qualifies, so there is nothing to shadow.

## Method (honest notes)

- **Pre-registered** in `PREREG.md` before any code touched data. Amendment A (your giveback message, plus one
  clarification of SR's target) was committed before any number. The code, tests and design / walk-forward results were
  committed before the one final read (`results/FINAL_READ`, 12 Nov 2026 19:19 UTC).
- **Data:** the Drive lake (index, India VIX and weekly option minutes with OI), copied with rclone on 12 Nov. The R51
  Amendment A clean stores were rebuilt from it.
  - B0 parity is exact: design R72 3,071 / Rs 2,16,929 and R51 4,208 / Rs 50,425; final R72 1,202 / Rs 1,71,366 and
    R51 1,670 / Rs 1,34,743.
  - The first draft of this report said the data could not be reached. That was wrong: the Drive login was in the
    environment, and only the rclone tool was missing.
- **Signal source: the index.** The Donchian entries are unchanged. Exits read:
  - the index (R150's 5-minute levels, ATR, Supertrend, regime);
  - the option's own premium (VOLP's stop, the "in profit" checks, G1-G3's rupee levels, solved from the real fill);
  - the held option's volume and OI (FLOW).
  - Every rule decides at a minute's close on data up to that minute, and sells at the next open.
- **Engine and costs:** R152's (R106 / R64 simulator, R17 slippage, dated Zerodha charges on the whole quantity). R106's
  walk is re-run for every rule.
- **Periods:**
  - design: 20 Nov 2024 - 31 Mar 2026, walk-forward window Apr 2025 - Mar 2026;
  - final: 1 Apr - 13 Oct 2026, read once. It has been read by many studies (R51 - R166), so it is weak evidence.
  - CTX and ML were trained only on design days before each scored window. Their design-period numbers are in-sample.
- **Gates (all must hold):**
  - win rate +5 points AND net not lower than today's, in walk-forward and final;
  - net > 0 in every period;
  - beats 500 random-exit twins (the larger of the net and win-rate p's);
  - BH q <= 0.10 with **m = 2,664** (R167's 102 plus 2,562 earlier tests, R161-R166 included, at p = 1);
  - >= 40 final trades.
- **Results:** 0 of 102 pass. The lowest p values are 0.002, for G2 and G3 at 1 lot (both arms) and G2 at 2 lots
  (R51). Their q is 1.0 at m = 2,664 (0.041 within R167 alone), and every one of them makes less money than today's
  exits.
- **Raw p-values for the joint BH with the siblings:** `results/raw_pvalues.csv` (102 rows: p, p_net, p_win, q at
  m 2,664, q within R167).
- **Every number in full:** `results/tables.md` (each arm x lots x rule: walk-forward and final trades, win, net, worst
  day, max drawdown, the giveback counts, p, q). Also `results/design_*.json`, `final_*.json`, `decision.json` and
  `picker.json` (the second look on R151's picks).

## Tests

`python -m pytest research/hunt/r167_donchian_smart_exits -q`: 44 tests. They cover:
- every rule's edge cases: no levels, a breakout, ATR warm-up, missing prints, missing OI, puts, the square-off row;
- the look-ahead guard: every measurement and every rule's decision at minute m is unchanged when all data after m is
  cut, at 6 and 5 cut points;
- the engine on synthetic days through R106's real simulator: every T version's exit is never later than today's;
- a synthetic giveback trade. Its best was +Rs 2,625; today's exits ended at -Rs 2,354, G1 at -Rs 19 and G2 at
  +Rs 1,602.
