# RC2: buying BTC/ETH options on Delta Exchange India, always with a stop-loss and a profit lock

Written 13 Oct 2026 for Boss's question: "I don't want to hold crypto. Like the Indian market: take profit and sell. Is
there anything like options in crypto?" (TODO C5). Research only: no app code, no orders, no accounts.

- Pre-registered in `research/hunt/rc2/PREREG.md` (commit `f32ef32`, before any P&L), with four amendments listed there.
- Code: `research/hunt/rc2/` (`signals.py` rules, `exits.py` stops and locks, `costs.py`, `sim.py`, `stats.py`, `run.py`,
  `anatomy.py`, `deribit_check.py`, `report.py`, `fetch/`). Tests: `research/hunt/rc2/test_rc2.py` (28).
- Tables: `research/hunt/rc2/results/` (`anatomy.md`, `deribit_check.md`, `report.md`, `design.csv`, `holdout.csv`,
  `walkforward.csv`). Experiment entries: `research/experiments/entries/rc2-*.yaml` (8 rules + the anatomy).
- Research, not financial advice.

## Verdict for Boss

**Yes, crypto has options like NIFTY's, and Delta Exchange India sells them in rupees. But none of the 8 option-buying
rules we tested made money after costs, every one with a stop and a profit lock. Nothing passes. No rule is proposed for
a paper arm.**

1. **What exists (Delta Exchange India, checked on its live API, 13 Oct 2026):**
   - BTC and ETH calls and puts.
   - Expiries: a new **daily** expiry every day, weekends included, settling at **17:30 IST** (12:00 UTC). Three dailies
     are listed at a time. There are also weekly (Friday) and monthly expiries.
   - Settlement is cash, on Delta's index; the wallet is in rupees. Trading is 24×7.
   - Lot sizes: BTC 0.001 BTC (about Rs 8,050 of notional at $83,200 and Rs 96.78/$); ETH 0.01 ETH (about Rs 2,430).
   - One lot of an at-the-money daily option costs very little:
     - with 24 h left: about Rs 69 (BTC) / Rs 31 (ETH);
     - with 6 h left: about Rs 27 / Rs 12.
   - Fees: 0.01% of notional per side, capped at 3.5% of the premium, plus 18% GST.
     - About Rs 1 a lot per side on BTC.
     - That is 2-3% of an at-the-money premium each way, and the 3.5% cap on cheap options.
   - Spread (live book): about 1% of the premium at the money with 6-24 h left; 5% one to two percent out of the money;
     17-24% for the cheap far strikes.
2. **Does any rule make money? No.** We tested 8 option-buying rules × 4 stop/lock variants = 32 variants.
   - **Design:** 25 months, 772 daily expiries per coin.
     - No rule cleared all its gates: cost stress, walk-forward and significance.
     - Three were a little positive at base costs (R4, R8, the hero R6), and all three turn negative under the cost stress (1.5× spread, 0.03% fee).
   - **Holdout:** 13 Apr - 13 Oct 2026, read once. **Every rule's chosen variant lost money except one.**
     - The exception: the previous-day high/low break (R8), +1.5% a trade on 290 trades, t = 0.3.
     - That is +Rs 8,910 on a Rs 1 lakh account in six months (Rs 49 a day).
     - **After the strict crypto (VDA) tax reading it is −Rs 34,173.**
     - It had already failed the design gates (walk-forward −Rs 12,864; −Rs 1.13 lakh at 1.5× spread).
3. **Why it fails:**
   - **Costs.** A round trip in an at-the-money daily option costs **7-14% of the premium**: spread 3-6.5 points, fees
     2.7-6.8, exit slippage about 1. A cheap far strike costs about **36%**.
   - **Direction.** The rules do catch moves: before costs most are positive, +1.1% to +9.2% a trade (R7 is negative even before costs). But they are no
     better at picking calls or puts than a coin flip: the direction twin's p is 0.004-0.73 and mostly not significant.
     What they really buy is volatility after a move, and that is mostly priced in.
4. **What to avoid:**
   - Avoid buying daily options in the Asian/European morning (08:30-17:30 IST). Moves then are only **0.67-0.90×**
     what the options price, and the Europe-open breakout (R2) lost −10.4% a trade over 1,309 trades.
   - **Avoid "zero to hero" lottery buys of cheap far strikes.** In hindsight they look tempting: a cheap option bought
     3 h before settlement closed at 3× its price on 17% of tries (BTC, 3,554 options). But the average one was worth
     0.68× at 17:20 IST. The spread on them is 17-24%, and the hero rule lost −18.7% a trade on the holdout (56 trades).
   - Avoid funding-rate "crowd reversal" buys (R7): −13% a trade, worse than random times (p = 0.99).
   - Avoid sizes above 2% of the account a trade: the worst designs lost Rs 1.3-2.7 lakh on Rs 1
     lakh over 25 months, at only 2% a trade.
   - Avoid trusting a back-test that bought a strike in its first minutes. Delta's first mark candle of a new strike is
     often wrong (5.7% of listings). One such trade made our first design run look like a winner (see "What went wrong
     on the way").
5. **Tax: a CA must confirm.** Two readings were run.
   - (A) VDA: 30% + cess on every winning trade, no loss set-off.
   - (B) Business income: losses set off.
   - Under (A) even the one positive holdout rule loses. Delta's marketing says F&O avoids the 1% TDS; if TDS did apply,
     it would lock about Rs 1,500 (R8 at Rs 25k) to Rs 9,800 (R4 at Rs 1 lakh) over the six months, as a prepayment.
6. **If Boss still wants crypto exposure "like the Indian market":** nothing here supports it with real money. The
   honest next step would be a pure watch-only paper log of R8 (the previous-day break, −40% stop with the step lock)
   on the phone, to see whether live Delta fills match the mark-plus-spread model. That is not proposed as an arm,
   because it failed the gates.

## Step 1: anatomy (design data only)

Full tables: `results/anatomy.md` and `results/deribit_check.md`.

**Spreads and liquidity** (live Delta book, 9 snapshots 19:00-20:20 UTC, 7,263 quotes; the simulator's frozen fit used the
first 8). Median spread as a share of the
mid:

| strike | BTC, 6-24 h left | BTC, 1-2 days | ETH, 6-24 h | ETH, 1-2 days |
|---|---|---|---|---|
| at the money (±0.25%) | 1.1% | 0.8% | 2.0% | - |
| 0.25-1% out | 2.4% | 1.1% | 3.2% | 1.7% |
| 1-2% out | 4.8% | 1.7% | 7.1% | 2.5% |
| 2-4% out | 16% | 2.9% | 24% | 5.1% |

- Every listed strike had both a bid and an ask. Typical size at the ask is 5,000-8,000 lots (BTC) and 4,000-6,000
  lots (ETH).
- The book was not seen in its last 6 hours before settlement (all polls were in the UTC evening). The simulator
  therefore uses the WIDER of two fits (this one and STRAD_CRYPTO's 22 snapshots), plus a 1.5× stress.
- **Mark vs real prices:**
  - Delta's mark sat inside the bid/ask on 96% (BTC, 1,331 quotes) / 94% (ETH, 682) of near-the-money quotes (11
    snapshots), at the mid (median deviation 0.01%; `results/deribit_check.md` section d).
  - In history, Delta's traded 5-minute closes sat −0.1% from the mark at the median, with a median distance of 4.3%
    (850,241 BTC bars on 109 expiries; 353,996 ETH bars on 108). That covers all strikes, cheap ones included, so it is roughly consistent with the
    modelled half-spread (about 1.5-3% at the money, much more on cheap strikes); it is not a precise check.

**Premium decay of the daily at-the-money straddle** (call + put, % of spot, median over about 700-770 design days):

| hours left | 24 | 12 | 6 | 3 | 1 | 0.17 |
|---|---|---|---|---|---|---|
| BTC | 1.72% | 0.94% | 0.66% | 0.46% | 0.26% | 0.11% |
| ETH | 2.53% | 1.39% | 0.98% | 0.67% | 0.39% | 0.21% |

- Decay is close to the square root of time: 55% of the 24-hour value is left at 12 h, 38% at 6 h, 15% at 1 h.
- The 12-hour straddle bought at 05:30 IST paid back 1.02× its price on average (median 0.74×, 39% of 722 BTC days)
  and 1.03× for ETH (680 days). That is about fair before costs.

**Zero to hero.** A cheap out-of-the-money option (0.02-0.10% of spot) of the day's expiry; 5-minute mark closes, before
costs, never in a strike's first 15 minutes:

| bought | BTC options | closed >= 3x | 5x | 10x | worth at 17:20 IST (mean) | days any 3x | ETH options | 3x | 10x | worth at 17:20 |
|---|---|---|---|---|---|---|---|---|---|---|
| 11:30 IST (6 h before) | 5,024 | 20% | 11% | 5% | 0.91× | 45% | 2,740 | 19% | 6% | 0.83× |
| 14:30 IST (3 h before) | 3,554 | 17% | 9% | 4% | 0.68× | 38% | 2,185 | 13% | 4% | 0.69× |
| 16:30 IST (1 h before) | 2,034 | 12% | 5% | 1% | 0.63× | 24% | 1,346 | 9% | 2% | 0.60× |

- 53-88% of them are worth almost nothing (< 5% of the entry) at 17:20 IST; the later they are bought, the fewer.
- On Deribit's real traded prices (its 08:00 UTC expiries, every 3rd day 2022 - Apr 2026), the odds are lower. Bought
  2 h before, 7.7% reached 3× (BTC, 897 options) and 7.9% (ETH, 770); 10× 0.9% / 1.8%.

**Moves vs what options price, by hour** (|1-hour move| RMS / DVOL-implied, 773 design days):

- 08:30-17:30 IST (03:00-12:00 UTC): 0.67-0.87 (BTC), 0.73-0.90 (ETH). Options are dear.
- 18:30-21:30 IST (13:00-16:00 UTC, the US open): 1.12-1.46 (BTC), 1.12-1.40 (ETH). Options are cheap, hour for hour.
  But a daily option's price covers the whole day, so the US hours cannot be bought alone.

**Big-move days** (top 20% of daily ranges, 155 of 772 per coin):
- They follow a big previous day (rank correlation +0.32 to +0.35) and a high DVOL at 05:30 IST (+0.28 to +0.39).
- They are rarer at weekends (11-17% of big days vs 32-33% of other days).
- Funding says nothing (−0.10 / −0.04).

**Delta vs Deribit pricing.** Delta's mark-implied vol is **0.88×** Deribit's trade IV for BTC (230 random design hours,
median 35 vs 41 vol points) and 0.92× for ETH (227 hours).
- So Delta's options looked cheaper than Deribit's, not dearer.
- Part of the gap is method: Deribit's expiries are at 08:00 UTC, so its IV is interpolated to Delta's 12:00, across the
  quiet morning hours.

## Step 2: the rules (pre-registered; option buying only)

Each rule buys the at-the-money option of the nearest daily expiry with enough time left. The hero buys a cheap
out-of-the-money one. The rule was run with four stop/lock exits:
- stop −40% or −25%;
- a step lock (1.3× locks breakeven, 1.6× locks 1.3×, 2× locks 1.6×, 3× locks 2.4×, ...) or a trailing lock (from 1.3×,
  75% of the peak, never below breakeven);
- the hero: stop −50% / −70%; step lock from 2× (locks 1.3×) or trail from 2× at 60% of the peak, never below 1.3×.

Nothing is held past 17:20 IST on expiry day.

| rule | entry (UTC; IST = UTC + 5:30) |
|---|---|
| R1 Asia ORB | first 5-min close outside the 00:00-01:00 range, 01:00-07:00; today's expiry |
| R2 Europe ORB | outside 07:00-08:00, 08:00-10:30; today's expiry |
| R3 US ORB | outside 13:30-14:00 (US open), 14:00-17:00; tomorrow's expiry; 4 h max |
| R4 vol burst | 15-min move >= 2.5× the DVOL-implied 15-min move; 2 h max |
| R5 DVOL jump | DVOL up >= 3% in the hour + a >= 1× implied 1-h move; 3 h max |
| R6 hero | 14:30-16:30 IST: 15-min move >= 2× implied → cheap OTM option of today's expiry |
| R7 funding reversal | funding beyond its 60-day 2.5/97.5 percentile + the last hour turning against the crowd |
| R8 previous-day break | first close above yesterday's high (call) / below its low (put), 01:00-22:00; 3 h max |

Random twins for every variant:
- same-clock random entries with a random side (2,500 per coin per rule);
- the same entries with a coin-flip side.

## Step 3: results

**Money.** Rs 1,00,000 (and Rs 25,000). At most 2% of the account in premium a trade, whole lots. A 5% daily loss cap
per rule. Fixed size, no compounding.

**Costs.**
- Delta fees with the cap and GST.
- The spread model on entry and exit, plus one tick and 1% slippage on exits.
- Stress: spread ×1.5 and fee 0.03%.

### The pick of each rule (best design mean return of its four exits), design vs holdout (Rs 1 lakh)

| rule | exit | design trades (per day) | win | avg win / loss | net / trade | Rs net | green days | worst day | max DD | holdout trades | net / trade | Rs net | after VDA tax | after business tax |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R1 Asia ORB | −25% step | 1,419 (1.84) | 23% | +67% / −26% | −4.7% | −1,33,941 | 25% | −1,591 | 1,84,969 | 354 | −7.4% | −51,893 | −84,972 | −51,893 |
| R2 Europe ORB | −25% trail | 1,309 (1.69) | 25% | +48% / −29% | −10.4% | −2,71,117 | 24% | −1,695 | 2,78,187 | 331 | −9.1% | −60,028 | −89,860 | −60,028 |
| R3 US ORB | −40% step | 1,493 (1.93) | 29% | +56% / −30% | −4.6% | −1,36,299 | 34% | −1,950 | 1,44,214 | 349 | −3.1% | −21,523 | −59,834 | −21,523 |
| R4 vol burst | −40% trail | 2,218 (2.87) | 41% | +49% / −33% | +0.9% | +39,599 | 40% | −5,504 | 61,798 | 512 | −5.4% | −54,659 | −1,05,278 | −54,659 |
| R5 DVOL jump | −25% trail | 164 (0.21) | 32% | +43% / −25% | −3.2% | −10,172 | 33% | −2,441 | 15,201 | 36 | −12.2% | −8,645 | −9,987 | −8,645 |
| R6 hero | −70% step | 211 (0.27) | 34% | +162% / −72% | +7.9% | +33,095 | 30% | −3,595 | 39,773 | 56 | −18.7% | −20,916 | −33,228 | −20,916 |
| R7 funding | −25% step | 334 (0.43) | 16% | +52% / −26% | −13.3% | −87,652 | 15% | −2,395 | 87,652 | 71 | −12.7% | −17,846 | −20,576 | −17,846 |
| R8 prev-day break | −40% step | 1,184 (1.53) | 34% | +61% / −31% | +0.4% | +9,123 | 35% | −2,861 | 80,173 | 290 | +1.5% | +8,910 | −34,173 | +6,130 |

- "Green days" is the share of days with a trade that ended green.
- At Rs 25,000 (Rs 500 of premium a trade) the same trades give a quarter of these rupees. Holdout: R8 +Rs 2,064 (Rs 11 a
  day; −Rs 8,476 after VDA tax). Every other pick is −Rs 2,037 to −Rs 14,844.

### Gates (pre-registered) for the picks

| rule | G1 design > 0, >= 60 trades | G2 BH q < 0.10 vs same-clock random | G3 walk-forward > 0 | stress > 0 | holdout > 0 | passes |
|---|---|---|---|---|---|---|
| R1 | no | yes (q 0.004) | no (−1,54,035) | no | no | **no** |
| R2 | no | yes (0.001) | no (−2,48,408) | no | no | **no** |
| R3 | no | yes (0.002) | no (−1,18,514) | no | no | **no** |
| R4 | yes | yes (0.001) | no (−12,472) | no (−1,93,352) | no | **no** |
| R5 | no | yes (0.022) | no (−11,805) | no | no | **no** |
| R6 | yes | yes (0.001) | no (−36,871) | no (−9,954) | no | **no** |
| R7 | no | no (0.995) | no (−83,318) | no | no | **no** |
| R8 | yes | yes (0.001) | no (−12,864) | no (−1,12,908) | yes (+8,910, 290 trades) | **no** |

- **Beating random times means little here.** The same-clock twins are bought at random moments, often when nothing is
  moving, and pay theta for nothing. The rules buy right after a move, when vol stays high for a while.
- The coin-flip-direction twin is the sharper test. On raw p-values only R4 (0.004-0.010) and R8 (0.006-0.043 for the
  −40% variants, 0.015 for −25% trail) pick the side better than chance. After BH across the 32 variants none does (the
  smallest q is 0.064), and none by enough to pay the costs.
- BH ran across all 32 variants.

### Where the money goes (design, picks; points of the premium per round trip)

| rule | median entry price (% of spot) | spread | fees | exit slippage | gross / trade | net / trade |
|---|---|---|---|---|---|---|
| R1 | 0.52% | 4.4 | 4.8 | 1.1 | +5.4% | −4.7% |
| R2 | 0.29% | 6.5 | 6.8 | 1.1 | +3.3% | −10.4% |
| R3 | 0.99% | 3.2 | 2.7 | 1.0 | +2.2% | −4.6% |
| R4 | 0.87% | 3.6 | 3.2 | 1.1 | +8.9% | +0.9% |
| R8 | 0.70% | 3.9 | 3.8 | 1.1 | +9.2% | +0.4% |
| R6 hero | 0.066% | 25.3 | 8.9 | 1.9 | +44.2% | +7.9% |

- The fee is 0.01% of the notional, against a premium of only 0.3-1% of it, so it costs 1-3% of the premium each way.
- Cheaper (later, more out-of-the-money) options pay more in fees and spread.

### Big winners cut short by the lock

The "cut short" count is lock exits after which the option later closed at 2× the exit price or more, before the
forced exit.

| rule (pick) | design lock exits | cut short | holdout lock exits | cut short |
|---|---|---|---|---|
| R1 | 637 | 260 | 146 | 41 |
| R2 | 569 | 186 | 145 | 38 |
| R3 | 699 | 101 | 168 | 15 |
| R4 | 1,178 | 187 | 264 | 29 |
| R6 hero | 102 | 40 | 22 | 5 |
| R8 | 610 | 120 | 146 | 26 |

- On daily options the lock often sells near breakeven, and the option later doubles. The step lock's first rung
  (1.3× locks breakeven) is the main culprit.
- Wider locks were not better overall: the −25% and −40% variants with either lock all lose in the holdout, except R8's
  −40% ones.
- The lock is Boss's rule and stays. The study compared lock styles; it never ran "no lock" or "no stop".

### Walk-forward (each quarter uses the variant with the best record on all earlier design trades)

- Across all 32 variants, the walk-forward picked the hero every quarter (its past record looked best). That made
  **−Rs 36,871 on 184 trades, −10% a trade**: 3 green quarters of 8.
- Per rule, re-picking the exit each quarter, every rule lost: from −Rs 11,805 (R5) to −Rs 2,48,408 (R2).

### By year and coin (design, picks, net per trade)

- R4: 2024 +4.3% (862 trades), 2025 −0.4% (1,020), 2026 to April −4.2% (336). It is fading.
- R8: 2024 +4.2% (471), 2025 −2.3% (563), 2026 −1.4% (150); BTC −3.0%, ETH +4.1%.
- Hero: BTC +47% (90 trades) vs ETH −21% (121). The three best trades are 259% of the sum of all returns; the median
  trade is −79%.

## What went wrong on the way (told plainly)

- **The first design run had a data bug.** Delta's first mark candle of a newly listed strike often opens at a stale
  value. On 3 Feb 2025 a BTC 93000 put listed at 07:30 IST "opened" at $25.6 and was really about $1,650. The
  simulator bought it at 25.6 and booked **+4,889%**.
  - That one trade made R5 (DVOL jump) look like the only rule that passed every design gate: +26.4% a trade on 164
    trades.
  - Fix, for every rule and the anatomy: no entry in a strike's first 15 minutes, nor on a mark that jumps more than 2×
    from the previous close (PREREG amendment 4).
  - Re-run once: R5 is −3.2% a trade. The holdout was read only after the fix.
- **The holdout is "partly seen."** NN_CRYPTO and STRAD_CRYPTO opened Apr-Oct 2026 once for other ideas. Nothing here
  was chosen on it.
- **R5's DVOL threshold was lowered from 5% to 3%** before any P&L, because 5% gave only 57 design signals (amendment 1).

## Limits

- **Mark, not trades.** Fills are Delta's mark plus half the modelled spread. Today the mark sits at the mid. Delta's
  traded closes sit a median 4.3% from the mark, consistent with half a spread.
- **Spreads were seen only in the UTC evening** and only today. The model is the wider of two fits, plus the stress;
  2024 books were probably thinner. That makes the losses, if anything, understated.
- **5-minute bars.** Stops and locks fill at the level when a bar's low touches it, at the open when it gaps. Within a
  bar the stop is checked before any new high. 0.1% of mark bars have spiky highs or lows; they make exits earlier and
  worse, not better.
- **Strikes.** ±3.5% of spot on Delta's grid, plus 3.5-8% out of the money on a coarser grid. 0-9% of the ATM rules'
  signals found no tradable strike. R5's rate was 18%: its jumps come in fast moves that leave the fetched band. 29% of
  the hero's signals found no option in its cheap band. All are counted in `design.csv`.
- **Strike band.** The fetched strikes centre on spot 24 h before settlement. An entry 24-30 h before settlement
  (R7, R8 and a few R5 trades) could find no strike if spot later moved more than about 3%. That only skips trades
  before big moves, which favour buyers, so it can only make results look worse.
- **Rupee conversion:** Rs 96.78/$ throughout. Fees as of 13 Oct 2026; older periods may have charged 0.03% (the stress
  covers it).
- **Tax** is two simplified readings at the 30% slab + cess. A CA must confirm which applies, and whether 1% TDS does.

## Files

- Code and tests: `research/hunt/rc2/`.
- Raw data: about 1,912 Delta chains, Deribit series and trades, and live polls. They are in the session scratchpad
  (`raw/`), not in git; re-fetch with `research/hunt/rc2/fetch/*.py`.
- Trade lists: `scratchpad/rc2_trades/`.
- Re-run: `python3 -m research.hunt.rc2.anatomy`, `... .run design`, `... .report`. The holdout refuses a second read
  (`results/HOLDOUT_READ`).
