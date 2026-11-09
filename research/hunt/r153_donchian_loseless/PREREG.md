# R153 pre-registration: combinations that make the Donchian arms LOSE LESS (1-3 lots)

Written 7 Nov 2026, **before any R153 number was computed** (committed on `claude/r152-donchian-2k` before the code's
first run). Research only; nothing in `android/**` changes.

Boss, 7 Nov, after R152 (+Rs 2,000 / -Rs 2,000 failed): *"Can we try some combination for less loss."*
As in R140 (Boss, 31 Oct: "losing money is important, we should always check how could we lose less"), **the goal is
fewer and smaller losses, not more profit**; the arm must still make money.

Signal source for every rule: **the index** (unchanged). The rules change only the option's exit (on the premium, which
CLAUDE.md allows) and when the arm stops taking new trades for the day.

## 0. What is already known (so R153 does not repeat it)

- R152: a +Rs 2,000 cap (with or without a -Rs 2,000 stop) loses money at every lot size: it cuts the big winners.
  The -Rs 2,000 stop alone kept a profit and cut the worst day, but gave up much more profit, and lost to random exits.
- R105: locking at +Rs 1,000 on 1 lot sells winners early; most trades that reach +Rs 1,000 end green.
- R112: entries until 10:00 kept 78% of the final-period profit with a smaller worst day, but failed on net.
- R140: "lose less" filters from market structure failed against random skips.
- Never tested on these arms: a day loss cap; a lock that protects a +Rs 2,000 open profit while the winner keeps
  running; and these combined with the -Rs 2,000 stop.

## 1. Arms, engine, B0

Exactly as R152 (`research/hunt/r152_donchian_2k/`): R72 Breadth Donchian (R101's units) and R51 Donchian 20 +
Supertrend (10, 3) (R112 / R115), R106's walk (one open a side, 2 fresh fills a side a day), R152's engine (R106's rupee
levels for N lots, the arm's -15% stop kept with the nearer stop acting), dated Zerodha charges on the whole quantity,
R17 slippage from the order's size. B0 = today's rules at the same lots; at 1 lot it must reproduce R72 3,071 /
Rs 2,16,929 and 1,202 / Rs 1,71,366, R51 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743 (asserted).

## 2. The four combinations (fixed now; amounts are for the whole position, net of charges)

- **STOP2K**: a -Rs 2,000 stop (R152's: the nearer of it and the arm's -15% acts).
- **LOCK2K**: once the trade has been +Rs 2,000 (the premium reached the price whose sale nets +Rs 2,000), a lock rests
  at the price whose sale nets +Rs 1,000; if the premium falls back there, sell (a stop sale, so slippage applies). No
  target: the winner keeps running under the arm's own ladder, time stop and 15:15 square-off.
- **DAYCAP**: no new entry for the rest of the day once this arm's trades that day that have already closed (exit
  minute before the new entry minute) net -Rs 4,000 or worse (two full -Rs 2,000 losses). Open trades keep their exits.
- **AM10**: new entries only until 10:00 (entry row <= 10:00), as R112.

| id | rule |
|---|---|
| C1 | STOP2K + LOCK2K |
| C2 | DAYCAP (today's exits) |
| C3 | STOP2K + LOCK2K + DAYCAP |
| C4 | AM10 + STOP2K + LOCK2K |

Each on R72 and R51, at 1, 2 and 3 lots = **24 tests**. No amount is fitted: Rs 2,000 is Boss's; the lock keeps half of
it; the day cap is two of Boss's stops; 10:00 is R112's.

## 3. Periods

As R152: design 20 Nov 2024 - 31 Mar 2026 with the walk-forward window Apr 2025 - Mar 2026; final 1 Apr - 13 Oct 2026,
read once by R153 (`results/FINAL_READ`; read by many earlier studies: weak evidence); post 14 - 19 Oct 2026 reported,
never gated.

## 4. Gates: losing less (R140's, all must hold)

In **each** of design, the walk-forward window and the final, against B0 at the same lots:
1. **The sum of losing days is smaller** (closer to zero) than today's.
2. **Max drawdown** (daily) not larger; **worst day** not worse; **worst month** not worse.
3. **The arm still makes money:** net > 0. A higher net is not required; the change is reported.

Across the family:
4. **Better than random (final period), 2,000 twins (seed 153), on the sum of losing days**; p = (1 + twins at least as
   good) / 2,001 (R115's normal-tail p when no twin reaches it); p < 0.05.
   - exit part (STOP2K, LOCK2K): each trade the rule sold before today's exit is sold at a uniformly random minute
     strictly between its fill and today's exit instead (R140);
   - skip part (DAYCAP, AM10): the same number of trades is removed at random from the trades the rule's exits would
     take without the skip (AM10: candidates dropped at random, then walked again, as R140; DAYCAP: trades removed at
     random within the same days, as many per day as the cap removed);
   - a combination with both parts must beat both: its p is the larger of the two (intersection-union).
5. **BH q <= 0.10** with m = R153's 24 + R152's 866 (its 18 + 848 earlier) = 890, the earlier tests taken as p = 1.
   q within R153 alone is also shown.
6. **>= 40 final trades** and **>= 30 final trades that differ** from today's.

Reported for every test and period: trades, win rate, net and its change, sum of losing days, losing days, worst day,
max drawdown, worst month, exit mix, trades the cap / cut removed. **Second look** (descriptive, never gated): the four
combinations on the trades R151's app pickers take, Apr - Oct 2026, as in R152 (a skipped break still holds its side).

## 5. What a pass means (CLAUDE.md)

PAPER: a passing rule may act on paper for that arm straight away, behind a labelled switch, logged in the Autopilot
timeline, undoable in one tap. LIVE: each part only lowers risk (earlier exits, fewer entries), but anything new on live
trades starts in shadow behind a switch that is off by default. Nothing goes live from R153. Failed rules stay notes
here and in `research/experiments/entries/r153-*.yaml`.
