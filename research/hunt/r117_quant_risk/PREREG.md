# R117 pre-registration: quant risk tools on the paper book (more profit per rupee of risk, less loss)

27 Oct 2026. Written and pushed BEFORE any R117 number is computed. Branch `claude/battery-audit-1027`. Research only:
nothing in `android/**` changes.

## 0. Why

Boss, 27 Oct: *"Also use the quant mathematics and quant trading techniques, and find out how could we increase the
profit of all strategies with more winrate and less loss."*

What is already known (and NOT re-tested here):

- **A higher win rate by itself loses money here.** R105C / R106: selling at +5% gives 61% wins and -Rs 1.18 lakh on
  R72 in the final (today's rules: 46%, +Rs 1.71 lakh). Breakeven moves, tighter stops and small targets were tested on
  about 35,000 exit variants (R12, R18, R19, R38, R63-R70); only the A4 ladder (R64) was adopted.
- **Time-of-day filters do not hold up** (R112, R113: all 7 failed the walk-forward year).
- **Direction models do not work** (AUC 0.48-0.54; R41, R57, R60, R61); the R57 meta-labeller (Solo 2.0) is the only
  passed ML.
- **The paper book as the app runs it (R108's CAP2) is about flat:** design -Rs 1,28,786 (3,456 trades, 42.7% wins,
  max drawdown Rs 2,09,037); walk-forward year +Rs 25,383; final +Rs 15,955 (1,384 trades, 41.8%, drawdown Rs 74,624).
  Boss's account rule (Rs 40,000 drawdown, no month at -Rs 13,000) is broken at 1 lot in every arm set (R108 item 5).

So the quant question that is left is not "a better signal" but **risk control at the book level**: given the same
signals, which entries should the account take so that it keeps most of the profit with less loss? R74 section 3
listed this family (volatility-targeted sizing, equity-curve control) as **never tested**. One lot is already the
smallest size, so "smaller size" can only mean **fewer simultaneous positions** (a lower cap) or **skipped entries**.
Every rule below can only REFUSE an entry (it never adds one, never sizes up, never changes a stop or exit), so each one
can only lower risk. That fits CLAUDE.md's LIVE rule (only lower risk; shadow first).

**Signal source of every rule: the index** (the arms are unchanged: R72 Breadth Donchian, R51 Donchian 20, R52 Open
outside value, Liquidity 15+5, priced as in R101 / R106 / R108). The risk inputs are India VIX (an index), the NIFTY
daily closes, and the book's and arms' own closed results. No option-chart signal is used.

## 1. Data, engine, periods (identical to R108)

- Drive lake (`index_1m` incl. INDIAVIX, `index_daily`, `options_rolling_1m` WEEK), R51 Amendment A clean stores
  (`R51_CLEAN=1`); R106's engine on R101's units, dated Zerodha charges, R17 fills, next-minute fills, 1 lot.
- The book: R108's `walk_day` (the app's order, R72's kept place, would-be trades, one option one arm) with **CAP2 (the
  app's same-direction cap of 2) as the base of every rule**. Parity first: R117's CAP2 must reproduce R108's published
  CAP2 trades and net in design, the walk-forward window and the final (to the rupee), or R117 stops.
- Periods: design 20 Nov 2024 - 31 Mar 2026; walk-forward window 1 Apr 2025 - 31 Mar 2026 (inside design); final
  1 Apr - 13 Oct 2026, read ONCE by R117 (`results/FINAL_READ`). **The final window is not fresh** (R51-R116 read it);
  a pass here is weaker evidence than a pass on unseen data, and is said so.

## 2. The six rules (all on top of CAP2; parameters fixed here, never tuned)

A "cap 1" day or minute uses the app's own count rule at 1 (`CountCap(1)` with R72's kept place), so R72 keeps its
priority exactly as in the app. A refused passed-arm entry becomes a would-be trade (as the app does); a refused
Liquidity entry books nothing (as the app does).

| id | name | the rule | the quant idea |
|---|---|---|---|
| T1 | VIXCAP | cap 1 (instead of 2) on a day whose India VIX open (09:15 minute's open) is at or above **V67** | volatility targeting (Moreira & Muir 2017): less exposure when volatility is high |
| T2 | RVCAP | cap 1 on a day whose NIFTY 20-session realised volatility (stdev of daily close-to-close log returns, sessions up to YESTERDAY) is at or above **R67** | the same with realised, not implied, volatility |
| T3 | EQ10 | cap 1 on a day when the book's own closed net over its previous 10 sessions (this rule's own walk) is below 0 | equity-curve control / time-series momentum of the book's P&L |
| T4 | ARMEQ | an arm's entries are refused on a day when the sum of its last 20 trades (its own research record, ALONE, closed on EARLIER days) is below 0; no history = allowed | per-strategy equity-curve filter (trade a strategy only while it works) |
| T5 | DAYLOCK | no new entry once the day's closed book net is at or above +Rs 6,500 (the mirror of R108's day stop) | lock a good day; R113: later entries net about zero |
| T6 | COMBO | T1 and T3 together: cap 1 when either fires | the two risk triggers combined |

**V67 and R67** are the 67th percentiles of the daily values over the DESIGN sessions only (20 Nov 2024 - 31 Mar 2026),
computed in the design phase, written to `results/thresholds.json` and committed before the final read. Nothing of the
final window is used to set them.

Not in R117 (said, not hidden): sizing UP when volatility is low (raises risk; the account rule is already broken at
1 lot), Kelly sizing (R117 Part A only measures the Kelly fraction), delta-based strike choice (needs the engine's strike
rule changed: a separate study), the MCX arms, Solo 2.0 (its final picks do not reproduce: R106-R108), the Pine scripts
and ORB family (they lose in every hour: R113).

## 3. Part A: descriptive quant measures of today's book (not tests, no gate)

For CAP2 in each period: win rate, average win and loss, payoff ratio, **breakeven win rate** (avg loss / (avg win +
avg loss)), expectancy per trade, daily Sharpe (annualised x sqrt(250)), Sortino, the full-Kelly fraction of the daily
P&L (mean / variance, per rupee) and of the trades (W - (1-W)/payoff), the lag-1 autocorrelation of daily P&L and of
the 10-session P&L (does T3 have a basis?), the arms' daily P&L correlations, and the chance of a Rs 40,000 drawdown
within 120 sessions by a block bootstrap of the design days (block 5, 20,000 paths). These describe; they decide nothing.

## 4. Part B: the tests

Each rule is walked over all sessions (design and final in one walk, so EQ10's and ARMEQ's history runs on). Measured per
period, 1 lot, after charges: net, trades, win rate, avg win / loss, worst day, max drawdown, net / max drawdown,
months at or below -Rs 13,000, days at or below -Rs 13,000, separate Rs 40,000 drawdowns.

**Twin (one per rule, final window):** the rule's net / max drawdown against 100,000 random-refusal twins built from
CAP2's real trades: on each day remove from CAP2's real trades as many as the rule took fewer than CAP2 that day
(uniformly at random; a day where the rule took as many or more keeps CAP2's day). p = (1 + #twins >= actual) / (n + 1).
Seed 117.

## 5. Gates (all must hold; never relaxed)

1. **Less loss, every period** (design, walk-forward, final): max drawdown strictly below CAP2's; and days at or below
   -Rs 13,000, months at or below -Rs 13,000 and Rs 40,000 drawdowns each no more than CAP2's.
2. **Profit kept, every period:** net >= CAP2's net - max(Rs 15,000, 15% of |CAP2's net|).
3. **Better than chance:** BH q <= 0.10 on the final twin p, counted over R117's 6 tests plus every earlier test
   (R108's N_PRIOR 5,777 plus R108-R116's 86 logged entries = 5,863 prior; total 5,869). The q within R117 alone is
   reported too, but does not decide.
4. **Enough difference:** at least 30 final trades differ from CAP2's.

A rule that passes is a **paper candidate** for Boss: risk-lowering, so per CLAUDE.md it may act on paper arms (logged
in the Autopilot change timeline, undoable), and anything for LIVE starts in shadow behind a switch that is off. Boss
decides. A rule that fails stays a note. Nothing in R117 changes the app.

## 6. Logged

Every rule (pass or fail) and the Part A measures are written to `research/experiments/entries/r117-*.yaml`, with
sample sizes, after `results/decision.json`. The write-up is `research/HUNT_R117_QUANT_RISK.md`.

## Amendment 1 (27 Oct, after the design numbers, BEFORE the final read): a stricter check for DAYLOCK

The design phase showed a flaw in gate 3 for DAYLOCK: its random-refusal twin is tilted in its favour. DAYLOCK only drops
the trades that come AFTER a day is already +Rs 6,500; a twin drops the same number of that day's trades at random,
often the early winners that made the day good, so DAYLOCK beats almost every twin (design p about 1e-5) whatever the
later trades are worth. The gates are never relaxed, so the fix only adds a check:

- **Paired day check** (`r117lib.paired_days`): on the days a rule's net differs from CAP2's, the difference (rule minus
  CAP2) is bootstrapped over those days (20,000 resamples, seed 117); p = the share of resampled means at or below 0.
- **DAYLOCK must also have a paired p <= 0.10 in the final window** (on top of gates 1-4). It is reported for every rule,
  but decides only DAYLOCK (the other rules' twins are not tilted this way).

Design-phase numbers seen before this amendment: every rule's design / walk-forward summary and twin p (they are in
`results/design.json` as first computed); no final-window number has been computed by R117.
