# R193 pre-registration: do the arms' current exits, R151's picker and FB-R72-A10 help R72, R51 and Liquidity 15+5 from Nov 2024?

Written 17 Nov 2026, **before any R193 number was computed** (no reproduction, no new trade). Committed and pushed on
`claude/r193-arms-nov24` before the code's first run on data. Research only: nothing in `android/**` changes (except the
knowledge pack, if `build_pack.py --check` asks for a rebuild). Owner: Boss (they/them).

Boss asked whether recent research helps the live arms R72, R51 and Liquidity 15+5, and chose: *"Check only from nov 24"*.
R193 was free when this was written (R191 and R192 are taken; no `r193` branch, folder or log entry existed).

**Signal source of every rule: the index** (the arms' own 5-minute index breaks; R151's picker and FB-R72-A10 read index
features at or before the decision minute). Options are only bought; stops, breakevens and locks sit on the option premium.
No rule reads an option's own chart.

## 1. Data and engines (nothing raw in git)

- Lake copy `~/.cache/iraalgo/r190/lake` (R190's flat folder: index_1m, options_rolling_1m WEEK + BANKNIFTY MONTH, with
  the 20 Oct - 13 Nov 2026 fill joined, first print of a minute kept), read with `python3 -I`.
- R51's Amendment A clean stores as R190 built them (3 Aug 2020 - 13 Nov 2026), linked read-only into
  `~/.cache/iraalgo/r193/r51/clean`; every other cache in `~/.cache/iraalgo/r193` (never git).
- Engine: R186's (`r186engine.price`: R176's held-contract simulator, R106 walk, R101 / R112 / R115 units, R17 fills,
  dated Zerodha charges) with R179's pricing for the arms' own exits and R152's picker module (R151's frozen app numbers).
  Nothing in those engines is changed; R193 only calls them over a longer span.

### 1a. Reproduction first (before any new number)

1. **R186's published year** (20 Oct 2025 - 19 Oct 2026, 1 lot, results/year.json): BASE = V4 (R72 1,069 trades /
   Rs 3,24,629; R51 1,367 / Rs 2,49,903; Liquidity with BE 688 / Rs 1,00,222), L500 (V5: R72 1,097 / 64.4% /
   Rs 2,13,110; R51 1,430 / 62.7% / Rs 1,62,959), LBE1000 (Liquidity 688 / 44.5% / Rs 98,239) and A (own exits; R72
   2,281 / Rs 2,98,253, R51 3,084 / Rs 2,21,238, Liquidity 688 / Rs 1,17,923), run on R190's stores.
2. **R190's era C** (1 Nov 2024 - 13 Nov 2026, R72 own exits, 1 lot): D1 4,571 trades / Rs 3,90,101; D3 (FB-R72-A10,
   skipped before the walk as R140a / R190 did) 3,721 / Rs 4,78,594; Liquidity L1 1,323 / Rs 2,55,293. This checks the
   longer span, the stores and R193's own A10 feature code (section 3) together.

Any gap is reported with its size. If a gap is larger than Rs 1 or one trade, the R193 numbers are still computed but
every table carries the gap in its heading, and the cause is investigated and written down before the report is read.
(R186 used stores built to 19 Oct with a Sep 2024 warm-up; R190's start in 2020 and hold the 13 Nov fill. A small gap
from the warm-up is possible and would be disclosed, never tuned away.)

## 2. Span and windows

The span is **1 Nov 2024 - 13 Nov 2026** (R190's era C: "from Nov 24"), the last day in the lake copy.

| window | dates | what it is |
|---|---|---|
| **W1** | 1 Nov 2024 - 31 Mar 2026 | R151's pickers were trained on 20 Nov 2024 - 31 Mar 2026: **in-sample** for the picker. FB-R72-A10's cut (1.3206) was chosen in R140 Amendment A on 20 Nov 2024 - 31 Mar 2026: **in-sample** for A10 too. |
| **W2** | 1 Apr 2026 - 13 Nov 2026 | **out-of-sample for the picker** (but not fresh: R151's second look, R152, R176, R179, R186 and R190 read Apr - Oct 2026). For A10, 1 Apr - 13 Oct 2026 was R140a's final (read once there, then by R143 / R190), so only **14 Oct - 13 Nov 2026** is unseen by A10's choice; R190 read D3 over it today. |
| **Total** | 1 Nov 2024 - 13 Nov 2026 | both together |

Honest context, stated before any number: Nov 2024 onward is the regime in which R72 and R51 made all their money (R190:
R72 lost Rs 4.07 lakh in Dec 2021 - Oct 2024; R143, R144a found the same). A positive result here says nothing about
older years. V4's +10% breakeven (R162 / R179) and V5's step lock (R186) were designed and judged on Oct 2025 - Oct 2026,
inside this span.

## 3. The variants (fixed now; 1 lot gated)

The baseline of every arm is **A = the arm's own exits, no picker, no breakeven, no lock** (R179's A: -15% stop, the
ladder / L25, the time stop, the index exits, the arms' own square-offs (15:05 on an expiry day); Liquidity's own 15+5 exits).

| id | arm | rule |
|---|---|---|
| R72-P | R72 | A + R151's picker (skip under 0.4656) |
| R72-V4 | R72 | picker + breakeven at +10% (V4) |
| R72-V5 | R72 | V4 + step lock: +Rs 500 a lot locked at a best open P&L of +Rs 1,000 a lot net, then Rs 500 a lot under each further Rs 500 step (R186 L500, `r186engine.lock_levels`) - the rule live today |
| R72-V5A10 | R72 | V5 + FB-R72-A10 |
| R51-P | R51 | A + R151's picker (skip under 0.4469) |
| R51-V4 | R51 | picker + breakeven at +10% |
| R51-V5 | R51 | V4 + the same step lock - live today |
| R51-V5A10 | R51 | V5 + FB-R72-A10 (the R72 cut applied unchanged to R51; never fitted on R51) |
| LIQ-V4 | Liquidity 15+5 | A + breakeven at +10% |
| LIQ-V5 | Liquidity 15+5 | V4 + breakeven once the best open P&L is +Rs 1,000 a lot net (R186 LBE1000) - live today |

**10 tests.** Rules exactly as R186 / R179 priced them (`r186engine.price` with `lock="L500"` / `"LBE"`, `be_x=0.10`).

- **Picker** (as the app runs it, CLAUDE.md fourth exception): every candidate is walked with the variant's prices and a
  break the picker scores under its threshold is then removed from the record, so it still holds its side and counts in
  the day's caps; a break it cannot score is taken (`r152picker.kept`).
- **FB-R72-A10** ("skip after a very wide day"): skip the entry when yesterday's index range / the average daily range of
  the 20 sessions before today (at least 10 known) is >= 1.3206 (R140a's feature A10, `r140a.Days` / `features`; the same
  index as the trade; earlier sessions only, so a holiday simply means the previous trading session). In R193 it is
  applied **the way the app applies a skip filter** (removed after the walk, like the picker), because it is judged as an
  addition to the live rule. R193's own A10 code is checked against R190's D3 (which skipped before the walk) in 1a.
- Signals, strikes, entries and every other exit are the arms' own; whichever exit comes first wins; a stop never moves
  down; a level reached in a minute acts from the next minute; locks are sold as stop orders (R17 stop fill; a gap can
  fill below the level); a minute that touches both a stop and a lock is taken as the stop (R186's order).

## 4. Costs and sizing

- Dated Zerodha charges (`engine.charges`) and R17 slippage, on the trade's real entry fill.
- **1 lot**: every gate.
- **Funded lots (second table, descriptive, never gated):** each trade gets lots = floor(Rs 56,000 / (entry premium x lot
  size)), capped at 3; a trade that cannot pay for one lot is skipped (counted). Its net is the same candidate priced at
  that many lots (charges and slippage on the bigger quantity), on the 1-lot walk. Each trade is funded on its own; the
  table does not model two positions sharing Rs 56k (R72 can hold several indices at once), so it flatters.

## 5. What is reported (per variant and window, and for A)

Trades, win %, net, average win / average loss, max drawdown (closed-trade equity, R106's `stats`), worst month, net
without the top 5 trades, vs Boss's account stop (max drawdown <= Rs 40,000 and worst month >= -Rs 13,000: a breach is
written next to the verdict). Also (descriptive, not gated): V5A10 vs V5 (A10's own effect on top of the live rule),
V5 vs V4, and how many trades the picker and A10 removed.

## 6. The gate (fixed now; never loosened after seeing numbers)

A variant **HELPS** its arm only if ALL hold, vs the arm's A:

1. higher net than A in **W1 and in W2**;
2. lower max drawdown than A in **W1 and in W2**;
3. at least 40 trades in W1 and in W2;
4. over the total span, the variant's net **without its 5 best trades** is above A's full net;
5. **random-twin p < 0.05** (one-sided, empirical, 2,000 twins, seed 193) over the total span;
6. **BH q <= 0.10** with m = 4,755 (the entry files in `research/experiments/entries` at this branch's base, origin/main
   d1b1d57d8) + 10 = **4,765**; earlier entries count as p = 1. As R190, BH uses each variant's normal-tail twin p
   (z = (variant net - twin mean) / twin sd), because an empirical p from 2,000 twins cannot go below 1/2,001.

**Twins** (built on the variant's own walk, so the number of trades and the walk are the same as the variant's):
- *filter part* (picker, A10): the variant's walk before its skips; the same number of trades the filters removed is
  removed at random instead;
- *exit part* (breakeven, locks): every trade the variant's exit closes at least 2 minutes earlier than A's exits would
  (same candidate) is instead sold at the open of a random minute strictly between its fill and A's exit, with A's own
  exits active before it (R186's `forced_nets` with the base set to A: no breakeven, no lock); every other trade keeps
  the variant's net.
- A variant with both (V4, V5, V5A10) draws both parts in each twin. p = (1 + twins with net >= the variant's) / 2,001.

A variant that fails any item is "does not help", with every failed item listed. If no variant of an arm passes, the
answer for that arm is "nothing helps reliably" - which says nothing against Boss keeping the rule they chose (the fifth
and fourth exceptions are Boss's own choices); it only says this study cannot show it helps.

## 7. Honesty and order of work

1. This file is committed and pushed before any number.
2. Reproduction (1a) is run and written to `results/repro.json` first.
3. Then `results/span.json` (the 10 variants, A, the twins, the funded table), `results/decision.json` (the gate),
   `results/summary.md`, `results/report.md`.
4. Every variant is logged in `research/experiments/entries/` (R190's YAML format; `validate.py`), failures included.
5. The span is read once (`results/SPAN_READ`, written before the run). A crash before the results file is written may
   be re-run; a finished read is never re-run with a changed rule.

## 8. Tests (pytest, synthetic, no data)

The look-ahead guard (A10 and the windows use only earlier sessions: the future replaced by noise changes nothing), A10 on
a holiday gap and with too little history, the window split, the funded lots, the gate (each item fails alone), the twin p,
BH with the earlier entries, and engine edge cases through `r186engine.simulate`: expiry day (15:05 square-off), missing
minutes, a trade still open at the 15:05 or a 15:20 square-off, a stop and a lock touched in the same minute (stop first), a
lock that only rises.
