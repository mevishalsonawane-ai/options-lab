# R105: Donchian giveback (trades that go +Rs 1,000-2,000 and then end in a loss)

26 Oct 2026. Research only: nothing in the app changes (no `android/**`). Pre-registered in
`research/hunt/r105_donchian_giveback/PREREG.md` (commit 99592b7, pushed before any result); code and design results
frozen in aada9f5 before the one final read. Full tables: `research/hunt/r105_donchian_giveback/results/report.md`.

Boss, 26 Oct: *"I saw too many Donchian [losses] from Thursday, Friday and today. Should we make any change in Breadth
Donchian? Most of the time it goes to 1,000 to 2,000 profit and then we end up losing money. Please check and validate,
and use all the PDF/book studies we have done, and check if anything helps us recover the loss without affecting the
profit."*

## The answer

**No change to the exits.** We tested 10 new exits aimed at exactly those trades, including 2 from the books, on
Breadth Donchian (R72) and on Donchian 20 (R51). That is 20 tests, and **none passed**. Every rule that rescues the
trades which go +Rs 1,000 and then lose also cuts the winners by more than it saves. And none did better than
selling the same number of trades at random minutes.

Five things explain it:

1. **Rs 1,000 is a small move on 1 lot.** It is a median **+6% of the premium**: about +4% on BANKNIFTY, FINNIFTY
   and MIDCPNIFTY, and about +10% on NIFTY and SENSEX. An option premium moves that much on ordinary noise in a few
   minutes, so "+Rs 1,000" is not yet a winner.
2. **Most trades that reach +Rs 1,000 do win.** In the final period (1 Apr - 13 Oct 2026, 1,202 Breadth Donchian
   trades), 590 trades reached +Rs 1,000:
   - 472 of them ended green;
   - 118 (1 in 5) ended red, costing **Rs 1,08,736**;
   - the 590 together made **+Rs 8,29,786**.

   Locking at +Rs 1,000 sells many of the 472 early, to save some of the 118.
3. **The giveback is not where most of the money is lost.** Breadth Donchian's 651 losing trades lost Rs 7,92,049 in
   the final period:
   - 372 of them never got even Rs 500 up (-Rs 4,86,258);
   - the giveback group was only 14% of the losses.

   The losses come mainly from breakouts that fail at once, not from winners that turn.
4. **Your paper record is within normal bad luck for Breadth Donchian, but not for R51 with its Supertrend filter.**
   See "Validation" below. -Rs 3,583 over 11 trades is roughly a 1-in-4 run for R72's backtest. R51's 14 filtered
   trades (-Rs 16,188) are a 1-in-40 to 1-in-200 run, while the same 14 signals on the plain rules (-Rs 5,591) are
   ordinary.
5. **The paper app does not run R72 the way the backtest does.** The biggest difference is the same-direction cap
   switched on 19 Oct. There are also several possible bugs (listed below). Fixing those matters more than any exit.

## Before / after: Breadth Donchian (R72), 1 lot, net after all charges and slippage

Design = 20 Nov 2024 - 31 Mar 2026 (3,071 trades). WF = the 4 walk-forward quarters Apr 2025 - Mar 2026 (2,238
trades). Final = 1 Apr - 13 Oct 2026 (1,202 trades, read once).

"Cohort" = the same final trades that reached +Rs 1,000 and ended red (118 trades, -Rs 1,08,736 today). "Winners" =
the final trades that end green today (+Rs 9,63,414); both columns are re-priced under the rule.

| rule (signal source) | design net | WF net | final net | final max DD | cohort after | winners after | twin p (design / final) | verdict |
|---|---|---|---|---|---|---|---|---|
| **today's exits** (-15%, L25, 30/60/100, 30 min) | **Rs 2,16,929** | **Rs 3,35,882** | **Rs 1,71,366** | Rs 61,884 | -Rs 1,08,736 | Rs 9,63,414 | - | baseline |
| X1 breakeven once +10% (premium) | Rs 1,89,083 | Rs 2,86,350 | Rs 1,62,967 | Rs 65,587 | -Rs 73,980 | Rs 9,03,776 | 0.98 / 0.83 | fail |
| X2 breakeven once +15% (premium) | Rs 2,01,194 | Rs 3,12,877 | Rs 1,72,168 | Rs 63,256 | -Rs 97,651 | Rs 9,47,818 | 0.99 / 0.63 | fail |
| X3 +3% once +15% (premium) | Rs 1,99,907 | Rs 3,09,305 | Rs 1,57,499 | Rs 68,196 | -Rs 93,230 | Rs 9,26,665 | 1.00 / 0.97 | fail |
| **X4 breakeven once +Rs 1,000 (Boss's idea)** | Rs 64,794 | Rs 1,93,819 | Rs 1,34,226 | Rs 74,655 | **-Rs 12,039** | **Rs 8,17,166** | 1.00 / 0.97 | fail |
| X5 after 15 min, breakeven if it was +8% (premium + time) | Rs 2,11,463 | Rs 3,17,294 | Rs 1,50,419 | Rs 65,242 | -Rs 88,974 | Rs 9,14,117 | 0.89 / 0.85 | fail |
| X6 once +12%, keep half the best gain (premium) | Rs 1,25,219 | Rs 2,67,762 | Rs 1,22,292 | Rs 73,072 | -Rs 58,990 | Rs 8,68,211 | 1.00 / 0.95 | fail |
| X7 index closes back through the breakout level, from the fill (index) | Rs 2,78,825 | Rs 3,12,521 | Rs 1,40,319 | **Rs 53,679** | -Rs 97,346 | Rs 7,40,433 | 0.94 / 0.41 | fail |
| X8 X7, armed once +10% (index) | Rs 2,18,821 | Rs 3,28,275 | Rs 1,74,969 | Rs 60,825 | -Rs 1,01,658 | Rs 9,59,368 | 0.66 / 0.23 | fail |
| X9 book: sell 1 of 2 lots at +15%, other to breakeven (Carli / Douglas) | Rs 1,41,180 | Rs 2,67,797 | Rs 1,13,294 | Rs 56,134 | -Rs 83,336 | Rs 8,76,518 | 1.00 / 1.00 | fail |
| X10 book: breakeven at +15%, then trail the last 10-min index bar (Trader Tom) | Rs 1,98,332 | Rs 3,10,637 | **Rs 1,76,360** | Rs 57,859 | -Rs 93,753 | Rs 9,48,112 | 0.99 / 0.36 | fail |

Notes on the table:
- **X4 is your idea made literal.** It almost wipes out the giveback (-Rs 1,08,736 becomes -Rs 12,039). But it cuts
  the winners by Rs 1,46,248, so the final net falls by Rs 37,140. In design it falls from Rs 2.17 lakh to Rs 0.65
  lakh.
- **The two closest (X8, X10) are within noise.** They are a little above today in the final (+Rs 3.6k, +Rs 5k on
  1,202 trades), but below it in the walk-forward. X10 is also below in design. Random exits did as well (p 0.23 /
  0.36).
- **X7 flip-flops.** It made more in design (+Rs 62k) and had a smaller final drawdown (Rs 53.7k vs Rs 61.9k). But it
  made Rs 31k less in the final, and only 2 of the 4 quarters were better. That pattern is what luck looks like.

Donchian 20 + Supertrend (R51 on paper; design 4,208, final 1,670 trades), net design / final:
- today Rs 50,425 / Rs 1,34,743;
- best final: X8 Rs 44,872 / Rs 1,34,120 and X7 Rs 1,06,938 / Rs 1,30,788;
- X4: -Rs 31,657 / Rs 1,19,759;
- X9: -Rs 64,584 / Rs 73,433;
- every rule failed (`results/report.md`).

**Gate (pre-registered, all needed):**
- better net in design, WF (3 of 4 quarters) and final;
- worst day and drawdown not worse;
- beats 500 random-minute exit twins (p < 0.05) in design and final;
- BH q <= 0.10 over the 20 new tests plus the 274 earlier Donchian tests in the experiment log (R63-R75, R101,
  R104);
- at least 30 changed trades;
- the design gain survives dropping its 5 best trades;
- still better at 2x slippage.

The best twin p was 0.13 (B:X7, final only). Every BH q is 1.0.

## How big is the giveback? (the cohort, both arms, every period)

| | Breadth Donchian design | WF | final | 14-19 Oct (4 sessions) | R51 + Supertrend final |
|---|---|---|---|---|---|
| all trades | 3,071 / Rs 2,16,929 | 2,238 / Rs 3,35,882 | 1,202 / Rs 1,71,366 | 25 / -Rs 624 | 1,670 / Rs 1,34,743 |
| reached +Rs 1,000 | 1,638 / Rs 18.5 lakh | 1,214 / Rs 14.1 lakh | 590 / Rs 8.30 lakh | 14 / Rs 16,314 | 793 / Rs 10.2 lakh |
| ... then ended red | 384 / -Rs 5,61,586 | 268 / -Rs 3,72,663 | 118 / -Rs 1,08,736 | 2 / -Rs 1,703 | 171 / -Rs 1,82,911 |
| ... peak Rs 1,000-2,000 then red | 284 / -Rs 4,01,696 | 211 / -Rs 2,86,948 | 98 / -Rs 91,608 | 2 / -Rs 1,703 | 145 / -Rs 1,57,372 |
| reached +8-25% premium, ended red | 290 / -Rs 3,31,964 | 193 / -Rs 2,09,961 | 83 / -Rs 66,066 | 0 | 117 / -Rs 92,333 |
| never reached +8% | 1,657 / -Rs 17.2 lakh | 1,200 / -Rs 11.2 lakh | 716 / -Rs 6.23 lakh | 15 / -Rs 17,667 | 1,039 / -Rs 8.53 lakh |

(n trades / their net. "Reached" uses the best minute high from fill to exit, so it is the most that could have been
sold, not what a real order would get.) So Boss is right that it happens: about **1 trade in 10** in the final (1 in 8 in design). It
costs about Rs 1-1.8 lakh per half-year. But the trades that never go green cost 4-6 times more. The 14-19 Oct days
(the only unseen backtest days) had just 2 such trades. 19 Oct's loss (-Rs 18,450 on 10 trades) was breaks that failed
straight away: 6 stops, and none of the 10 was ever +8%.

## Validation: is the paper record consistent with the backtest?

**Bootstrap** (20,000 draws of the backtest's own trades, and every run of consecutive trades in time order; 1 lot):

| paper | from | random trades | consecutive trades | 6 consecutive sessions | reading |
|---|---|---|---|---|---|
| R72: 11 trades, -Rs 3,583 | final (1,202 trades) | 22nd percentile | 31st | 38th | normal: about 1 run in 4 is this bad |
| R72 | WF (2,238) | 22nd | 33rd | 29th | normal |
| R51 (+ Supertrend): 19 trades, -Rs 11,997 | final (1,670) | 4.6th | 7.7th | 20th | unlucky but not alarming (about 1 in 13-22) |
| R51 | WF (3,044) | 6.6th | 12th | 21st | same |
| R51 with its filter: 14 trades, -Rs 16,188 | final | **0.5th** | **2.3rd** | 13th | **rare (1 in 40-200)** |
| the same 14 on plain rules: -Rs 5,591 | final | 18th | 23rd | 31st | normal |

The 6-session column uses whole sessions (all 5 indices) against your 6 paper days, so it is only a rough guide. Its
sample is 124-242 windows.

Plain reading:
- Breadth Donchian's paper loss is ordinary bad luck. It needs no exit change.
- R51's filtered trades are unusually bad, and much worse than the same signals without the filter. On 14 trades this
  could still be luck. But it is worth checking that the phone's Supertrend (10, 3) is computed like R73's T7: on the
  index's 5-minute bars, agreeing at the signal bar's close (item 6 below).

**Fills and exits checked against the research rules.** The diagnostics only carry order prices for 21 Oct. Other
days have counts and refusals. The lake ends 19 Oct, the phone's live recordings are sealed (no passphrase here), and
the Dhan token has expired, so 20-26 Oct could not be replayed minute by minute.

What matched the research rules:
- 21 Oct, R72 NIFTY 22950 PE and BANKNIFTY 55300 PE were bought at 11:20:03 (the research buys at the 11:20 open)
  and sold at 11:50:00 on the 30-minute exit, at +32.7% and +33.7%. Same entry minute, same exit rule.
- No trades on an index's own expiry day for R72 (SENSEX 22 Oct).
- Today's three trades fit the rules: two stops near -15% and one 30-minute exit.
  - NIFTY 22500 PE -Rs 1,463 is 22.5 points on 65 qty.
  - BANKNIFTY 54000 PE -Rs 1,529 is 51 points on 30 qty.

Mismatches (the first two are app rules the backtest does not have; the rest look like bugs for the app session to
check):

1. **The same-direction cap (on since 19 Oct 13:32) changes R72.** At most 2 automatic paper positions may bet the
   same way, across all arms. Breadth Donchian fires on 4-5 indices at once by design, and R51 trades mostly the same
   breaks (77% overlap, R72), so most of R72's signals are refused.
   - Paper record: 11 real trades plus 15 would-be trades (-Rs 4,313). Together that is 26 trades and -Rs 7,896.
   - The backtest takes them all. So the paper R72 is a different, non-random subset of the backtest's trades.
   - The would-be record keeps them comparable. The plain-rules record is "real + would-be", not "real" alone.
2. **The daily loss stop refused trades the backtest takes.**
   - On 23 Oct ("the bot is stopped for today", daily loss limit reached at 09:49), the 13:45 call breaks on all 5
     indices were refused for both arms.
   - R75 found daily loss caps do not help Donchian: L1-X5 failed design and all 4 quarters.
3. **Missed fills: "the fill window passed".**
   - R51: the 13:45 put breaks on BANKNIFTY, FINNIFTY and SENSEX on 21 Oct.
   - R72: FINNIFTY's 09:20 put break on 22 Oct.
   - The backtest fills at the next minute's open, within 3 minutes, whenever the contract trades. Four misses in two
     days is more than the backtest's no-fill rate. Needs a look at why the window passed (quotes, stream, order
     path).
4. **Exits checked late while positions were open (a safety-rule issue).**
   - The health log says "the stops were looked at # s or more apart while a position was held": 2 times on 21 Oct, 2
     on 22 Oct and 4 on 23 Oct. The stream also dropped 9 times on 21 Oct between 11:54 and 12:38.
   - On 21 Oct both R72 positions bought at 12:11 were sold in the same second (12:33:42):
     - NIFTY at +4.9%;
     - BANKNIFTY at +3.4%, about 7 points (Rs 216) below its +5% lock (471.3). The backtest sells a lock at its level
       less slippage.
   - CLAUDE.md requires full-cadence stop / lock checks while a position is open, so this looks like a bug. It also
     makes paper exits a little worse than the backtest.
5. **Late entry.** The 21 Oct 12:11:17 fills came 1-2 minutes after a 5-minute boundary. The research buys at the
   boundary minute's open (12:10), so paper pays the move in between. Small, but it adds up.
6. **R51 treated NIFTY as "its expiry day" on Wed 21 Oct.**
   - R51 Donchian 20's status said "NIFTY from 21 Oct: its expiry day: no trades".
   - On the same day R72 bought NIFTY 27 Oct contracts, and the Hero ticket said "NIFTY not its expiry day".
   - NIFTY's weekly expiry is Tuesday (20 Oct).
   - If R51 really skipped NIFTY all day on 21 Oct, its expiry check is stale by a day.
7. **Check the Supertrend filter's paper calculation** against R73 T7 (see the bootstrap above). On paper, R51's 14
   filtered trades lost Rs 16,188, against Rs 5,591 for the same 14 signals on the plain rules.

## What the books said, and whether it helped

- **Trader Tom (`book-tom-05`): breakeven at +1R, then trail behind the last completed bar.** As X10 (1R = the 15%
  stop, 10-minute index bars) it was the closest rule on R72 in the final (+Rs 5k on 1,202 trades). But it lost
  Rs 18.6k in design and Rs 25k in the walk-forward, and random exits did as well (p 0.99 / 0.36). It did not help.
- **Carli (`book-carli-01`) and Mark Douglas via Trader Tom (`book-tom-20`): scale out half at the first target and
  move the stop to entry.** As X9 (2 lots, 1 sold at +15%) it was the worst rule:
  - R72: Rs 1,13,294 vs Rs 1,71,366 in the final, and Rs 1,41,180 vs Rs 2,16,929 in design;
  - R51: -Rs 64,584 in design;
  - it caps the big winners on half the size, and Donchian lives on those.
- R104 (F11 and F9) had already run 32 book exit, filter and sizing rules on these arms, and none helped. The Wyckoff
  swing-low trail came closest and failed one quarter. R63-R69 had already tested trend-turn exits, step trails,
  breakeven trails, staircases and the 1-point trail.
- So **no book exit beats today's exits on Donchian**. The books' "move to breakeven early / scale out" advice is
  written for setups with a clear invalidation level and room to run. On a 1-lot option with a 30-minute life,
  +4-10% is noise.

## What can go on paper

**Nothing from R105.** No rule passed, so under the research rules they all stay notes. Nothing changes live.
Today's exits stay: -15% stop, +5% once +25%, breakeven once +30%, the 30/60/100 ladder, out after 30 minutes,
square-off 15:15.

What would help more than an exit (decisions for Boss and the app session, not R105 results):
1. Fix or explain items 3-7 above.
2. Judge R72 on "real + would-be" while the same-direction cap is on, or exempt R72 from the cap on paper. With the
   cap, paper is not testing the strategy that was researched. Not running R51 and R72 side by side (R72's own advice)
   would also free the cap for R72.
3. Keep R75's freeze. The giveback is real but small. The large losses are breaks that fail at once, and five rounds
   of filters (R73, R75, R101, R104) found no way to skip those reliably.

## Honest limits

- **The final window is reused.** It has been read by R51-R75, R101, R104 and now R105, so it is weak evidence. The
  gate counted all 274 earlier Donchian tests in BH.
- **The extra out-of-sample window is tiny:** 4 sessions (14-19 Oct), 25 R72 trades and 46 R51 trades. Paper after 19
  Oct could not be replayed (no data).
- **The 14-19 Oct store is a separate copy built to 19 Oct.** Its cleaning step runs over the longer file, so its
  final-period baseline differs from the frozen one by 2 trades: R72 Rs 1,70,336 vs Rs 1,71,366, R51 Rs 1,31,546 vs
  Rs 1,34,743. The OOS rows compare rule against baseline inside that same copy.
- **The OOS read was started once and stopped by a missing file before any trade was read.** Its read-once flag was
  removed and the code now checks for the files before setting it.
- **X9 assumptions.** It is priced as two 1-lot legs, each charged as its own orders (slightly more brokerage than one
  2-lot order) with 1-lot slippage. It is reported per lot.
- **"Reached +Rs 1,000" uses minute highs,** so it is an upper bound.
- **The headline nets re-walk the book**: an earlier exit can free a side for a later signal. The twin test uses the
  same trades without re-walking, so it measures exit timing only.

## How it was measured

- **Signal source:** Donchian 20 on the index's 5-minute bars, with R72's breadth (4 of 5) or R73's Supertrend
  agreement; buy the 1-ITM option.
  - X1-X6 and X9 are stops, locks or a partial target on the premium.
  - X7, X8 and X10 read the index: the breakout level (prior-20-bar high or low) and the last completed 10-minute
    index bar.
  - No rule reads an option chart for a signal.
- **Engine:** R64's held-contract simulator on R51's Amendment A clean stores, with:
  - next-minute fills (at the open, within 3 minutes) and R17 liquidity slippage;
  - stops on the minute low, with gap fills at the worse open;
  - a lock set at a minute's end acts from the next minute;
  - an index exit sells at the next minute's open;
  - dated charges, 1 lot.

  R105's engine equals R64's trade by trade with no rule (a test on 400 random paths). The baselines reproduce R75 to
  the rupee (R72 3,071 / Rs 2,16,929 and 1,202 / Rs 1,71,366; R51 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743).
- **Tests:** `research/hunt/r105_donchian_giveback/tests/test_r105.py` (25 tests). They cover:
  - engine = R64;
  - each rule on hand-made paths: thresholds, next-minute action, rupees vs quantity, the 15-minute wait, the
    half-gain stop, the limit leg, index close-through, put mirror, arming, the 10-minute trail;
  - a look-ahead guard: changing any premium or index bar after the decision minute never changes it;
  - the cohort, stats, BH with the earlier family, twins, the gate, the bootstrap, and the read-once final / OOS.
- **Logged:** `research/experiments/entries/r105-*.yaml` (20 entries, all rejected).

To re-run:

```
rclone copy gdrive:IraAlgo-data-lake/index_1m $IRAALGO_LAKE --include "index_1m_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX,INDIAVIX}_202[123456].parquet"
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m $IRAALGO_LAKE --include "opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX}_WEEK_202[456].parquet"
R51_CLEAN=1 python research/hunt/r51/r51data.py build
python research/hunt/r105_donchian_giveback/r105study.py design      # then `final` (refuses a second read)
R105_EXT=1 python research/hunt/r105_donchian_giveback/r105study.py build_ext && R105_EXT=1 python research/hunt/r105_donchian_giveback/r105study.py oos
python research/hunt/r105_donchian_giveback/r105report.py && python research/hunt/r105_donchian_giveback/r105entries.py
```
