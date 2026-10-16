# R72: cross-index signals (the five indices read together), buy the option

16 Oct 2026. Research only: nothing in the app changes (no `android/**`). Paper or not is Boss's call. Pre-registered in
`research/hunt/r72/PREREG.md` (committed before any result, f9e761f); design picks frozen before the final read
(0a0b778). Full tables: `research/hunt/r72/results/report.md`.

Boss, 16 Oct, after R71 found no new chart pattern: *"Any more strategies/pattern you could find?"*, then chose
**cross-index signals**.

## Answer in one paragraph

**One idea passed every gate: "Breadth Donchian" (C1).** It is Donchian 20, but a break is traded only when **at least 4
of the 5 indices (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX) break their own 20-bar channel on the same 5-minute
bar**. On the same days as Donchian it made **Rs 1,71,366 in the final period vs Donchian's Rs 55,439** (Rs 81,845 with
L25), and **Rs 2,16,929 in design vs Rs 8,281**, with a smaller final drawdown (Rs 61,884 vs Rs 92,452) and more green
days (52% vs 47%). It is **not a new, separate edge**: 77% of its trades are Donchian's own trades (daily correlation
0.80), so it should **replace or filter the Donchian paper arm, not run beside it** (both together would double the
same risk). Recommended for **paper only**, if Boss wants it; never live. The other five cross-index ideas failed.

## The results (1 lot per trade, net after all charges and slippage)

| strategy (signal on the indices) | design trades / net | final trades / net | final win | avg win / loss | worst day | max DD | green days | trades / month | twin p (BH q) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| **Donchian 20** (paper arm today) | 4,518 / Rs 8,281 | 1,787 / Rs 55,439 | 44% | Rs 1,566 / -Rs 1,188 | -Rs 23,012 | Rs 92,452 | 47% | 278 | (R51 passed) | on paper |
| Donchian 20 with L25 | 4,498 / Rs 9,998 | 1,781 / Rs 81,845 | 44% | Rs 1,609 / -Rs 1,183 | -Rs 23,012 | Rs 91,626 | 47% | 277 | | (R66) |
| **C1 Breadth Donchian (4 of 5), L25** | 3,071 / **Rs 2,16,929** | 1,202 / **Rs 1,71,366** | 46% | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | **Rs 61,884** | **52%** | 187 | **0.008 (0.032)** | **PASS** |
| C1 re-tune (4 of 5, index stop) | 2,910 / Rs 2,25,481 | 1,153 / Rs 1,73,315 | 45% | Rs 2,285 / -Rs 1,622 | -Rs 36,808 | Rs 80,262 | 50% | 179 | 0.64 (0.97) | fail (random entries on those days did as well) |
| C2 Laggard catch-up | 1,716 / -Rs 2,43,309 | 613 / -Rs 1,00,746 | 40% | | -Rs 18,686 | Rs 1,25,695 | 41% | 95 | 0.89 | fail |
| C3 Lonely breakout, faded | -Rs 3,66,441 design | 499 / -Rs 1,60,188 | 41% | | -Rs 13,034 | Rs 1,61,171 | 34% | 78 | 0.40 | fail |
| C3 re-tune | -Rs 1,87,474 design | 398 / -Rs 48,623 | 23% | | -Rs 5,820 | Rs 60,444 | 31% | 62 | 0.18 | fail |
| C4 Leader at 10:15 | -Rs 23,835 design | 55 / -Rs 30,969 | 36% | | -Rs 5,430 | Rs 30,969 | 36% | 9 | 0.97 | fail |
| C5 Breadth thrust (EMA20) | -Rs 5,78,603 design | 1,797 / -Rs 3,30,727 | 39% | | -Rs 25,280 | Rs 3,30,727 | 32% | 279 | 0.75 | fail |
| C5 re-tune (5 of 5) | -Rs 3,46,669 design | 1,374 / -Rs 2,71,443 | 39% | | -Rs 37,071 | Rs 2,71,443 | 35% | 213 | 0.85 | fail |
| C6 Divergence at the day's low / high (SMT) | -Rs 1,68,737 design | 348 / Rs 35,463 | 42% | Rs 1,381 / -Rs 822 | -Rs 4,176 | Rs 33,446 | 50% | 54 | 0.002 (0.012) | fail (lost in design; top 5 trades > net) |
| C6 re-tune (4 of 4 hold) | Rs 17,241 design | 164 / Rs 33,733 | 39% | | -Rs 5,286 | Rs 11,783 | 47% | 26 | 0.002 (0.012) | fail (walk-forward -Rs 42,798; top 5 > net) |

(C2 and C4's re-tunes picked their original setting, so their rows are the same.) Design = 20 Nov 2024 - 31 Mar 2026,
final = 1 Apr - 13 Oct 2026 (133 sessions), read once.

## Why "Breadth Donchian" works, and how sure we are

- **It sorts Donchian's own trades.** In the final, the Donchian trades that breadth confirms made **+Rs 1,37,635** (752
  trades); the ones it does not confirm **lost Rs 55,790** (1,029 trades). A break that the whole market joins is a real
  move; a break on one or two indices alone is mostly noise that comes back (R72's C3 shows the same from the other side).
- **It holds up everywhere we can look:** made money on all 5 indices in the final (BANKNIFTY Rs 55k, FINNIFTY Rs 69k,
  MIDCPNIFTY Rs 24k, SENSEX Rs 13k, NIFTY Rs 10k); positive in all four design quarters (Rs 85k, 70k, 10k, 171k); 5 of 7
  final months positive (April -Rs 15k and June -Rs 41k were the red ones; Donchian was red in June too); its neighbours
  in design were all positive too (5 of 5 indices instead of 4: Rs 1.16-1.73 lakh; every exit: Rs 2.07-2.25 lakh). Calls
  made more than puts (Rs 1.27 lakh vs Rs 44k).
- **Gates:** final net > 0; beats 500 random entries on the same days and sides (p 0.008; those random entries averaged
  Rs 20,729, so picking the breadth days is not the whole story, the timing adds too); BH q 0.032 across R72's 12 tests
  and **0.045 across all 28 tests run today** (R71 + R72); without its 5 best trades still +Rs 1,12,623; design and
  walk-forward +Rs 2,16,929; at **twice the slippage still +Rs 1,37,648**.
- **But:** the final window has been read for many ideas, and Donchian itself was chosen on it, so the final is weaker
  evidence than it looks. Only paper trading from 14 Oct is truly unseen. That is exactly what a paper trial is for.

## Risk Boss must see before switching it on

At 1 lot on each of the five indices, **C1's worst design day was -Rs 58,613 and its worst design drawdown Rs 1,99,077**
(Donchian: -Rs 56,647 and Rs 2,24,592). In the final it was gentler (worst day -Rs 22,571, drawdown Rs 61,884), but both
the design numbers are **far beyond the account stop (-Rs 40k drawdown or -Rs 13k a month)**. Breadth Donchian trades
less than Donchian (about 187 trades a month instead of 277) and loses less on the days that go wrong, but it is not
safe at 1 lot on all five indices against that stop. On paper this costs nothing; for any future live use the size
would have to be cut (fewer indices or the account stop would trip), and that is a separate decision.

## Should it go on paper?

**Yes, if Boss wants: paper only, as a filter on (or replacement for) the Donchian paper arm, never beside it.** It
passed the same gates Donchian passed, and more strongly. Per the safety rules, a gate-passing idea may act on paper arms
straight away, logged in the Autopilot change timeline, undoable in one tap, never carried to live. The cleanest test:
run "Breadth Donchian" as its own paper arm for a few weeks **with plain Donchian still on paper**, and compare the two
on the same unseen days (paper costs nothing; this is the only time running both is useful).

## Exact rules of Breadth Donchian (C1), in steps

Every signal is read from the indices; the option is only what is bought.

1. Every 5 minutes (09:15-09:19, 09:20-09:24, ...), for each of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, take its
   **channel**: the highest high and the lowest low of its previous 20 five-minute bars (yesterday's bars count early in
   the day). This is Donchian 20's channel.
2. When that bar closes, count how many of the five indices **closed above** their channel's high.
3. If **4 or 5** of them did, every index that closed above its channel gets a **call** signal. (The same with "below the
   low" gives **put** signals.) If only 0-3 did, there is no trade, even if Donchian alone would trade.
4. Buy the **1-ITM** option of that index (1 strike in the money from the ATM of the minute before) at the next minute,
   between **09:20 and 15:10**; never on that index's own expiry day.
5. One open position a side per index; at most 2 entries a side per index per day (Donchian's caps).
6. Exits (as the Donchian paper arm with Boss's L25): stop **-15%** on the premium; once the option is **+25%**, the stop
   moves to **+5%**; the 30/60/100 ladder (breakeven + charges at +30%, +30% at +60%, +60% at +100%); no fixed target;
   sold after **30 minutes**; square-off **15:15**.

For the app this needs all five indices' 5-minute bars live at the same moment, including the ones not traded.

## The ideas that failed (what we learned)

- **C5 breadth thrust** (many indices crossing their EMA20 together) and **C2 laggard catch-up** (buy the index that has
  not yet broken out) lost heavily: the laggard usually stays behind, and EMA crosses are too frequent to mean anything.
- **C3 fading a lonely breakout** lost money, but its losses support C1: a break on one index alone is weak, yet fading it
  with options does not pay either.
- **C6 SMT divergence** (one index makes a new low, the others do not, then it turns) made money in the final and beat
  random entries strongly (p 0.002), but lost Rs 1.69 lakh in design and its final profit rests on its top 5 trades.
  Not proven; logged as rejected.
- **C4 leader at a fixed time**: too few trades and no edge.

## How it was measured (same as Donchian and R71)

R51's Amendment A clean stores of the Drive lake, R64's engine (one held contract, next-minute fill, R17 liquidity fills,
stops on the minute low with gap fills at the worse open, dated charges with STT 0.15% from 1 Apr 2026, lots in force,
1 lot), Donchian's trade-taking. R71's study code, unchanged, with R72's rules plugged in; the Donchian numbers it prints
match R66 exactly (design Rs 8,281 / Rs 9,998, final Rs 55,439 / Rs 81,845). Walk-forward re-tune as R51; random-entry
twins 500 per test (seed 72); Benjamini-Hochberg across the 12. Tests: `research/hunt/r72/test_r72.py` (22 tests, a
look-ahead guard for every rule and setting, each rule on a hand-made five-index market).

## Honest limits

- The final window is not a clean holdout (read by R51-R71). The design period and the paper trial carry the weight.
- 12 more tests today on top of R71's 16; C1 still passes with BH across all 28 (q 0.045), but not by a wide margin.
- C1 is Donchian with a filter, so it is not a diversifier. Its gain is Donchian's losing trades removed.
- MIDCPNIFTY and SENSEX feed the count; if a feed is missing for a bar, that index simply does not count (the 4-of-5
  rule then needs 4 of the indices that have data).
