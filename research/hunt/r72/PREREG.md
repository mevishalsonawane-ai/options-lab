# R72 pre-registration: cross-index signals (the five indices read together), buy the option

Written 16 Oct 2026, **before any R72 profit or loss was computed**; committed first. Research only (no `android/**`).

Boss, 16 Oct, after R71 (no single-chart pattern passed): *"Any more strategies/pattern you could find?"*, then chose
**cross-index signals** from the options offered (single-chart patterns are close to exhausted: H25 tested 195 of them,
R44 / R52 / R62 / R71 about 100 more on the recent market; only Donchian 20 passed).

## 1. Idea

Every earlier pattern read **one** index's chart. R72 reads the **five indices together** (NIFTY, BANKNIFTY, FINNIFTY,
MIDCPNIFTY, SENSEX, on the same 5-minute grid) and asks whether agreement, disagreement or leadership between them says
something one chart cannot. Never tested before in this repository (checked `research/experiments/entries/`, `HUNT_*.md`;
R13 "majority rule" is a weekly breadth of one index, not this).

**Signal source: the indices (the underlying) only.** No option-chain data, no option chart. Each trade buys the
**1-ITM option of the index named by the rule**, taken exactly as Donchian takes trades (entries 09:20-15:10, one open
a side per index, at most 2 a side a day, never on that index's expiry day). A signal on a 5-minute bar's close fills at
the next 5-minute row. "Channel" = Donchian's: the high / low of the **previous 20** 5-minute bars of that index
(continuous series, R44 `ind.donchian`); "break up" = a close above it.

## 2. The six rules (call side; put side the mirror unless stated)

| id | rule | traded index | index stop (XI) | original | grid |
|---|---|---|---|---|---|
| **C1 Breadth breakout** | on one bar, at least M of the 5 indices break up through their own channel | every index that broke up on that bar | its channel middle | M = 4 | M {4, 5} |
| **C2 Laggard catch-up** | at least 3 of the other 4 indices broke up within the last K bars, index X did not, and X closes above its own channel middle; first bar this becomes true | X (the laggard) | X's channel middle | K = 3 | K {3, 6} |
| **C3 Lonely breakout fade** | X breaks up through its channel while none of the other 4 broke up within the last K bars (first such bar) | X, the **put** (a fade) | the break bar's high | K = 3 | K {3, 6} |
| **C4 Leader at a fixed time** | at the close of the bar ending at T, all 5 indices are above their day's open: buy the strongest ((close - open) / ATR14 of its 5-min bars); all 5 below: the put of the weakest | one index a day | its day low so far | T = 10:15 | T {10:15, 11:15} |
| **C5 Breadth thrust** | the count of indices closing above their own EMA20 (5-min) jumps to at least M from at most 1 within the previous 3 bars | every index above its EMA20 on that bar | its EMA20 | M = 4 | M {4, 5} |
| **C6 Divergence at the day's low (SMT)** | X makes a new day low (bar 1 from 09:45 on) while at least R of the other 4 do not (their bar-1 low stays > 0.25 ATR14 above their own day low so far); then, within 3 bars and with no lower low, a close above bar 1's high | X | X's bar-1 low | R = 3 | R {3, 4} |

## 3. Exits, measurement, periods, gates: as R71

Exits XB (-15% / +30% / 30 min), XL (-15%, 30/60/100 ladder + L25, 30 min), XI (the index stop above + -25% safety
stop + ladder + L25, 60 min); square-off 15:15. Original = original parameter with XL; re-tune = 2 parameters x 3
exits, picked walk-forward (R51 section 4). R64's engine on R51's Amendment A clean stores, dated charges, R17 fills,
1 lot. Design 20 Nov 2024 - 31 Mar 2026, walk-forward quarters Apr 2025 - Mar 2026, final 1 Apr - 13 Oct 2026 read
**once** (`results/FINAL_READ`).

Gates per test (6 originals + 6 re-tunes = **12 tests**): final net > 0; random-entry twin p < 0.05 (500, seed 72; same
day, index and side, entry minute uniform 09:20-15:10, same exits; XI's stop the same points from entry); BH q <= 0.10
across the 12; >= 40 trades; top 5 < net; design and walk-forward > 0. Paper only if every gate passes and the final
stays > 0 at twice the slippage. Compared with Donchian XB / XL on the same days (net, win, average win / loss, worst
day, max drawdown, green days, trades a month, daily correlation, same-trade overlap, Donchian + rule combined); a
correlation >= 0.6 is labelled a duplicate.

**Honest note:** the final window has been read for many ideas (R51-R71), so a pass there is weak evidence; with R71's
16 and R72's 12 tests today, the design and walk-forward results carry most of the weight. Every test gets an entry in
`research/experiments/entries/` (family patterns-indicators), failures included.
