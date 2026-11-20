# R202 + R203 pre-registration: sell when the trend turns, and other ways to lose less, on R72, R51 and Liquidity 15+5

Written 20 Nov 2026, **before any R202 / R203 number was computed**. Committed and pushed on `claude/trend-exit-study`
before the code's first run on a rule below. Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 20 Nov: *"check that if any trend is change in between. Means if I have already bought a trade and trend changes, or
if the agent sees that the market is in the opposite direction, we should sell the trades automatically without any
permission. And also I want to check if there are any more enhancements we could do to reduce the losses of the R51, R72
and liquidity."*

- **R202 (study A):** an extra "the trend turned against the open trade" exit, 9 rules x 3 arms = 27 tests.
- **R203 (study B):** other loss-cutting ideas, 11 rules x 3 arms = 33 tests.
- R195 - R201 are taken by the option-selling work on an unmerged branch, so these are R202 and R203.

**Signal source of every rule: the index** (the trade's own index, the five indices together for breadth, NIFTY for the
market rule; the arms' own signals are index signals). Options are only bought. Stops and locks sit on the option premium.
The only rules that read the premium are R203's tighter entry stop (a stop, allowed on the premium) and the day loss cap /
two-losers rule (the arm's own closed P&L). No rule reads an option's own chart for a signal.

## 0. What was known before (so nothing is tested twice by accident)

- **R63 (16 Oct)** tested four trend-turn exits (15-min EMA20, 15-min Supertrend, two 5-min closes beyond the day's
  average price, 5-min Donchian-10) on Liquidity and R51 **with their plain exits** to 14 Oct 2026: all failed on these
  arms (they saved about as much on losers as they gave up on winners). R72 was not in R63, and none was tested on top of
  today's exits (V5: breakeven at +10%, the step lock / breakeven at +Rs 1,000, the R151 picker).
- **R142 (31 Oct)** tested bad-break filters and a "no progress" exit; **R81** an index no-progress exit on Liquidity;
  **R176 / R179 / R186** rupee stops (-Rs 1,000 / -Rs 2,000), step trails, targets, breakevens and locks; **R193** the
  wide-day skip (FB-R72-A10) on top of V5; **R194** support / resistance. Those are not repeated here.

## 1. Data and engine (nothing raw in git)

- **Source: the `dhan-data` branch of options-lab** (Dhan v2, fetched 6 Oct 2026), copied out of git with `git archive`
  and written in the lake's layout by `r202data.py` (`~/.cache/iraalgo/r202/lake`), read with `python3 -I`.
  **This is not R190's lake copy**: in this session the Drive lake could not be copied (the Drive copy was not permitted)
  and the Dhan token had expired. So the 20 Oct - 13 Nov 2026 fill is missing and **the span ends on 5 Oct 2026** (the
  last full session; 6 Oct is partial and dropped), not on 13 Nov. Same Dhan endpoints as the lake.
- R51's Amendment A clean stores built here from that folder (1 Sep 2024 warm-up, options 21 Nov 2024 - 5 Oct 2026).
- Engine: R186's (`r186engine.price`: R176's held-contract simulator, R106 walk, R101 / R112 units, R17 fills, dated
  Zerodha charges), R152's picker module (R151's frozen app numbers). Nothing in those engines is changed.
- **Reproduction (done before this file, on the baselines only; no rule below was run):** R193's published months
  Dec 2024 - Sep 2026, the arms' own exits (A) and V5, against this build: R72 A Rs 3,49,046 vs Rs 3,52,091 and V5
  Rs 3,14,724 vs Rs 3,17,860 (21 of 22 months equal to the rupee; Sep 2025 differs); R51 A Rs 1,51,778 vs Rs 1,52,850 and
  V5 Rs 1,84,570 vs Rs 1,84,183 (21 of 22 months equal); Liquidity A Rs 2,80,706 vs Rs 2,75,153 and V5 Rs 2,46,480 vs
  Rs 2,40,770 (19 of 22 months equal; Jan, May, Jul 2025 differ). The gaps (under 2.5%) come from the source (dhan-data
  against the lake's own files) and are carried in every table's heading. Trade counts: R72 V5 2,157, R51 V5 2,822,
  Liquidity 1,208 (21 Nov 2024 - 5 Oct 2026).

## 2. Baseline and windows

**Baseline B = what each arm runs today (V5)**, 1 lot:
- R72 / R51: the arm's own exits (-15% stop, ladder / L25, 30-minute time stop, square-offs; 15:05 on an expiry day) +
  breakeven at +10% + R186's Rs 500 step lock from +Rs 1,000 a lot (`lock="L500"`) + R151's picker (skips applied after
  the walk, as the app does).
- Liquidity (BANKNIFTY 15/5, FINNIFTY 30/5, MIDCPNIFTY 15/5): its own exits (-15% stop, 20-minute time stop, its index
  exits) + breakeven at +10% or at +Rs 1,000 a lot (`lock="LBE"`).

| window | dates | role |
|---|---|---|
| **design** | 21 Nov 2024 - 31 Mar 2026 | first look; four walk-forward quarters inside it (Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026) |
| **holdout** | 1 Apr - 5 Oct 2026 | locked; read **once**, by `r202study.py final`, after the design results and the code are committed (`results/HOLDOUT_READ`) |
| last year | 24 Nov 2025 - 5 Oct 2026 | descriptive only (Boss's baseline year, cut short by the data); written by the final read |

Honest context, stated before any number: the holdout is locked for **these rules** only. The baseline's own numbers on
it were read by R151's second look, R152, R176, R179, R186, R190 and R193, and V5 itself was designed on Oct 2025 -
Oct 2026. R151's picker was trained on the design window, so B is flattered there. Nov 2024 onward is the regime in which
R72 and R51 made all their money (R190); nothing here says anything about older years.

**Walk-forward.** R202's rules and R203's stop / cap rules have no fitted number (every parameter is fixed below), so their
walk-forward is the fold-by-fold check of section 6. R203's "skip the worst bucket" rules are truly walk-forward: each
quarter's skip is chosen only from the baseline trades before that quarter (section 4).

## 3. R202: the trend-turn exits (27 tests)

Each rule is an **extra exit on top of B**: the first exit to fire wins; B's stops, locks, time stops, index exits and
square-offs all stay. A rule only ever sells earlier. When it fires on a completed bar that closes at minute r, the option
is sold at the **open of minute r + 1** as a market sale (the engine's forced index exit, R17 fill). A bar counts only if
it **began at or after the signal's entry minute** (so it is complete at least 5 minutes after the order). "Wrong side" =
below for a call, above for a put. 5-minute bars are 09:15-09:19, ...; 15-minute bars 09:15-09:29, ...

| id | "the trend turned" means | signal source |
|---|---|---|
| X1 | a **Donchian-20 break the other way** on the same index: R51's own opposite signal (Donchian 20 + Supertrend, as the arm computes it); sold at that signal's entry minute | the trade's index, 5-min |
| X2 | an **R72 breadth break the other way** on the same index (R72's own opposite signal, 4 of 5 indices) | the five indices, 5-min |
| X3 | an **opposite signal from any of the three arms** on the same index (R51, R72 or a Liquidity book) | index, 5-min / 15-min / 30-min |
| X4 | **back through the entry level**: two 5-min closes in a row on the wrong side of the index's open at the entry minute | the trade's index, 5-min |
| X5 | **EMA20 against it**: two 5-min closes in a row on the wrong side of the EMA20 of 5-min closes (EMA carried across sessions) | the trade's index, 5-min |
| X6 | **day's average price against it**: two 5-min closes in a row on the wrong side of the session's average price (the index has no volume: the equal-weight mean of the day's 1-minute typical prices so far, as R63) | the trade's index, 5-min |
| X7 | **Supertrend(10, 3) flips** against the trade on a 5-min bar (ATR 10, multiplier 3, carried across sessions); only a flip after entry counts | the trade's index, 5-min |
| X8 | **breadth turned**: on two 5-min bars in a row at least 4 of the 5 indices closed on the wrong side of their own session average price | NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, 5-min |
| X9 | **the market is the other way**: a 15-min NIFTY close on the wrong side of NIFTY's EMA20 of 15-min closes (EMA carried across sessions); the bar must close at least 5 minutes after the entry minute | NIFTY, 15-min |

Arms: R72, R51, LIQ. An opposite signal is any candidate of that arm's raw signal list (before the picker and before the
walk) on the same index and day, on the other side, with an entry minute after the trade's. The walk is re-run with the
variant's prices (an earlier exit can free the arm's side for its next signal, as it would in the app); the picker's
skips are applied after the walk.

## 4. R203: other ways to lose less (33 tests)

| id | rule | kind |
|---|---|---|
| W-hour | skip the worst **entry-time** bucket (09:20-09:59, 10:00-10:59, 11:00-11:59, 12:00-12:59, 13:00-13:59, 14:00 on) | walk-forward skip |
| W-vix | skip the worst **VIX regime** (R101's `f_vixreg`: yesterday's VIX close at or under / over its 20-session median) | walk-forward skip |
| W-gap | skip the worst **opening-gap** bucket (the index's gap in absolute size: under 0.25%, 0.25-0.75%, 0.75% or more) | walk-forward skip |
| W-dow | skip the worst **weekday** | walk-forward skip |
| W-rv | skip the worst **realised-volatility** third (R101's `f_rv` rank: under 1/3, 1/3-2/3, 2/3 or more) | walk-forward skip |
| W-und | skip the worst **index** | walk-forward skip |
| W-trend | skip the worst **trend-strength** bucket: the index's move from the day's open to the minute before the entry, in the trade's direction (under 0, 0-0.3%, 0.3-0.6%, 0.6% or more) | walk-forward skip |
| S10 | **tighter entry stop**: -10% of the fill instead of -15% (everything else as B) | exit |
| DC3 | **day loss cap**: no new entry of the arm once its trades already closed that day total -Rs 3,000 or worse (1 lot) | skip |
| DC5 | the same with -Rs 5,000 | skip |
| L2 | **two losers in a row**: no new entry on an index that day once the arm's last two closed trades on it both lost | skip |

**Walk-forward skip (fixed rule, no number chosen after seeing results).** Quarters start 1 Apr 2025, 1 Jul 2025, 1 Oct
2025, 1 Jan 2026, 1 Apr 2026, 1 Jul 2026 and 1 Oct 2026. For a quarter, the training set is the arm's **baseline (B)
trades on days before the quarter's first day**. A bucket is bad if it holds at least 60 training trades and their mean
net is below Rs 0; the single worst bad bucket (lowest mean net) is skipped for that quarter; if no bucket is bad, nothing
is skipped. Before 1 Apr 2025 nothing is skipped. An unknown feature value is its own bucket and is never skipped.
Features are known at or before the entry minute.

Skips (walk-forward, DC3, DC5, L2) are applied **the way the app applies a skip**: after the walk, so a skipped trade
still holds its side and counts in the arm's day caps (R193 section 3), and after the picker. DC and L2 read only trades
whose exit minute is before the new entry's signal minute; a trade the rule itself removed does not count toward the cap.
S10 is re-priced and re-walked.

## 5. Costs, sizing and what is reported

Dated Zerodha charges and R17 slippage on the trade's real fill; **1 lot**. Per rule, arm and window (and for B):
trades, win %, net, average loss, max drawdown (daily closed-trade equity), worst month, the change against B, and how
many trades the rule changed (sold earlier) or removed. Also vs Boss's account stop (max drawdown <= Rs 40,000, worst
month >= -Rs 13,000), descriptive.

## 6. The gate (fixed now; never loosened after seeing numbers)

A rule **PASSES** on an arm only if ALL hold, against that arm's B:

1. **design**: better than B. "Better" (R106's rule): net above B's; or net within 5% of B's (B's net above 0) with max
   drawdown and worst month each at least 20% smaller.
2. **holdout**: better than B, same definition.
3. **walk-forward**: better than B (same definition) in at least 3 of the 4 design quarters.
4. max drawdown not above B's in design and in holdout.
5. at least 40 trades changed (exits) or removed (skips) in design and in holdout.
6. **holdout random-twin p < 0.05** (one-sided, empirical, 2,000 twins, seed 202).
7. **BH q <= 0.10** over the holdout twin p-values with m = 4,808 (the entry files in `research/experiments/entries` at
   this branch's base, origin/main 82fbc3b4) + 60 = **4,868**; earlier entries count as p = 1. As R190 / R193, BH uses
   each rule's normal-tail twin p (z = (rule net - twin mean) / twin sd).

**Twins** (on the rule's own walk in that window, so the trade count is the rule's):
- *exit rules* (X1 - X9, S10): every trade the rule closes at least 2 minutes earlier than B would (same candidate) is
  instead sold at the open of a random minute strictly between its fill and B's exit, with B's exits active before it
  (`r186engine.forced_nets`, breakeven and lock on); every other trade keeps the rule's net. A hard control: random early
  cuts of trades that later went wrong save money too, so the rule has to time the exit better than chance.
- *skip rules*: the same number of trades the rule removed in the window is removed at random from the rule's walk
  before its skips.

A rule that fails any item is "does not pass", with every failed item listed. Design twin p-values are reported too
(descriptive). **What a pass would allow** (CLAUDE.md): an exit that only sells earlier, or a skip, lowers risk; one that
passes could act on paper straight away and on live through every existing live gate, but only if Boss chooses it. One
that fails stays a note unless Boss explicitly makes it an exception. Nothing is built in the app by this study.

## 7. Order of work and honesty

1. This file is committed and pushed before any rule is run.
2. `r202study.py design` -> `results/design.json` (design window and its quarters only: trades after 31 Mar 2026 are
   dropped before any statistic, and the walk-forward skips of later quarters are not computed).
3. The code and design results are committed and pushed. Then `r202study.py final` reads the holdout once
   (`results/HOLDOUT_READ` is written first; a crash before `final.json` exists may be re-run; a finished read is never
   re-run with a changed rule) -> `results/final.json`, `results/decision.json`, `results/report.md`.
4. All 60 tests are logged in `research/experiments/entries/` (failures included), with sample sizes.
5. No rule, bucket, threshold or gate item is changed after the design numbers; any amendment is dated and written here
   before the number it affects.

## 8. Tests (pytest, synthetic, no data)

The look-ahead guard (every indicator and exit row is unchanged when the minutes after the firing bar are replaced by
noise; the walk-forward skip of a quarter is unchanged when trades on or after its first day change), bars from minutes
with gaps, a holiday gap in the EMA / Supertrend carry, an entry too late for any bar to complete, expiry-day and
market-closed minutes (NaN), the opposite-signal row, the earlier-of with the arm's own index exit, the bucket functions
(unknown value), the day loss cap and two-losers rule (only closed trades count), the windows, the statistics, the gate
(each item fails alone), twin p and BH.
