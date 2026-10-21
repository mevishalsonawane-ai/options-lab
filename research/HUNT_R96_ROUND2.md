# R96 round 2: refine the NR7 lead with real fills (21 Oct 2026)

Boss, 21 Oct: *"go ahead with round 2"*. Research only: nothing in `android/**` or `.github/**` changed; nothing trades.

- Pre-registration: `research/hunt/r96_mcx_buy/PREREG_ROUND2.md` (commit 38c1019a, before any round-2 statistic). Code
  and tests frozen in 4ca25db1 (bytecode dropped in 58c75234); notes N4-N5 in `hunt/r96_mcx_buy/NOTES.md`. One fix
  after the first launch (a3b0a428, "recompute daily indicators on every book load"): the cached books lacked
  the round-2 indicator columns, so the run stopped at R2C with nothing saved; the rerun is the same frozen rules (R2A
  and R2B printed identical numbers both times).
- Every number: `hunt/r96_mcx_buy/results/` (`design_round2.json`, `report_round2.md`, `trades_design_round2.csv`).
  Cumulative ledger: `hunt/r96_mcx_buy/LEDGER.md`.
- **Signal source of every rule: the MCX future.** The trade is always an option buy, with a hard stop and a profit lock.
- **Locked holdout (15 Jul - 19 Oct 2026): still NOT read** (nothing passed).
- **Ideas tried so far in R96: 17** (9 in round 1, 8 in round 2). BH runs over all 17.

## The answer for round 2

**LEADS (continue), nothing found. 0 of 8 pass the design gates. The round-1 NR7 lead does not survive real fills on
the mini books.**

| rule (signal on the future; all with the edge take-profit) | design trades | net (1 lot) | twin p (BH q, 17 ideas) | folds + | worst month | model exits | verdict |
|---|---|---|---|---|---|---|---|
| **R2A** N1 re-test: NR7 break -> 1-ITM, real fills | 65 | **-Rs 4,067** | 0.271 (0.577) | 2 of 4 | -Rs 15,380 | 3% | dies: **round 1's +Rs 1.84 lakh came from model-priced exits** |
| R2B NR7 break, 2-ITM | 67 | -Rs 7,562 | 0.365 (0.690) | 2 of 4 | -Rs 5,349 | 0% | dies |
| R2C Crabel inside-day NR7 | 13 | -Rs 14,129 | 0.752 | 1 of 4 | -Rs 13,913 | 0% | dies (too rare) |
| **R2D** NR7 break on CRUDEOIL / NATURALGAS options | 43 | **+Rs 30,767** | 0.076 (0.322) | **3 of 4** | -Rs 19,847 | 5% | **lead** (fails twin p, BH, worst month) |
| R2E NR7 break with a Rs 6,500 risk cap | 48 | -Rs 8,599 | 0.758 | 1 of 4 | -Rs 5,815 | 2% | dies |
| **R2F** relaxed squeeze -> ATM straddle, 6 books | 15 | +Rs 18,279 | **0.002 (0.034)** | 1 of 4 | -Rs 4,227 | 0% | lead on paper only: **one trade** (CRUDEOIL, 3 Mar 2026, +Rs 45,807) is all of it |
| R2G NR7 day -> ATM straddle | 69 | -Rs 59,254 | 0.876 | 0 of 4 | -Rs 12,845 | 0% | dies (lost before costs: compression does not make MCX straddles pay) |
| R2H quiet-base 20-day breakout | 56 | -Rs 1.08 lakh | 1.000 | 0 of 4 | -Rs 33,894 | 11% | dies |

Design 5 Aug 2025 - 14 Jul 2026, 1 lot, net after Zerodha MCX charges and half-spreads; full table with gross, costs,
2x spread, drawdown, without-top-3 and capital: `results/report_round2.md`.

## What was learned

1. **The NR7 breakout's option profit was a pricing artefact on the mini books.** Selling at a real print as soon as
   the held strike reaches the deepest stored strike (the edge take-profit) turned round 1's +Rs 1.84 lakh into
   -Rs 4,067 (65 trades, 62% win; avg win Rs 1,167 vs avg loss -Rs 2,029). The edge exits made +Rs 26,100 (45), but
   the underlying and premium stops lost -Rs 44,301 (17). With only ATM+/-3 stored, the take-profit comes too early on
   metals (GOLDM's window is +/-0.2%), so the right tail that N1 needed is cut off. The **signal** still looks real on
   futures (2012-25: +0.58% a trade vs +0.32% random, 1,436); what fails is capturing it with the options this lake can
   price.
2. **Crude is the one book where it holds up with real fills.** NR7 on CRUDEOIL options: 23 trades, +Rs 45,170; on
   CRUDEOILM (the same signal, a tenth of the lot): 23 trades, +Rs 3,705. NATURALGAS lost -Rs 14,403 (20). R2D as a
   whole: 3 of 4 folds positive, real-exit net +Rs 31,212, but twin p 0.076 and a -Rs 19,847 month. Crude's strikes are
   50 points on ~Rs 8,000 (0.6%), so a winner can run about 1.8% before the edge exit, much further than metals.
   Caveat: crude was the WEAKEST book in the 2012-25 futures check (+0.58% vs +0.41% random, p 0.26), so a crude-only
   rule rests on the option data alone.
3. **Straddles after compression still lose** (R2G: 69 trades, -Rs 59,254, 0 of 4 folds; R1 S1/V1 too). The squeeze
   straddle's p 0.002 (R2F) is one crude spike (March 2026, crude ATM IV ~168%); 14 of its 15 trades lost.
4. **Capping premium risk (R2E) removes the metals trades that breached the month limit, but what is left loses.**
5. **Inside-day NR7 is too rare** on 11 months (13 trades).

## Which ideas died and why

| idea | why |
|---|---|
| R2A, R2B, R2E | real fills: the NR7 option trade loses on the minis; the edge cuts winners early, stops cost more |
| R2C | 13 trades in 11 months; lost |
| R2G | compression does not make MCX straddles pay (lost before costs) |
| R2H | 20-day breakouts from a quiet base lost on metals (-Rs 1.04 lakh), roughly flat on energy |

## Leads worth refining

1. **R2D, NR7 on crude** (CRUDEOIL / CRUDEOILM): the only rule with real-fill profits spread over many trades and
   3 of 4 folds. Needs a cleaner test on crude alone.
2. **Wider strikes** would settle the NR7 question on every book. The data, not the idea, limits it: a fresh Dhan token
   (the one in this environment returns HTTP 401) would let the lake fetch ATM+/-10 for the mini books (the fetcher
   already supports `mcxopt_<SYM>`). That is the single most useful thing Boss can do for this hunt.
3. R2F is not a lead in substance (one trade); it stays in the ledger only.

## Round 3: pre-registered list (new or refined ideas)

Same periods, locked holdout, costs, twins and gates (incl. gate 8, real exits); BH over 17 + these.
Round 3 writes PREREG_ROUND3.md with exact numbers before running anything.

| # | idea | why (evidence) | rule sketch (signal on the future) |
|---|---|---|---|
| R3-1 | **NR7 break on crude only** (CRUDEOILM + CRUDEOIL) | R2D's profit is crude; labelled as chosen after seeing round 2 | R2A on the two crude books; edge take-profit |
| R3-2 | **NR4 break on crude** (Crabel's shorter compression) | more signals than NR7 on the book that works | narrowest range of 4 sessions; break / exits as R2A |
| R3-3 | **NR7 break with an underlying trail instead of the premium ladder** | edge exits made money, stops lost; let the future decide | after +1 ATR in favour, stop moves to breakeven on the future, then a 1.5 x ATR chandelier on closes; edge take-profit kept |
| R3-4 | **NR7 break only in a low-volatility regime** | true compression: ATR14 in the bottom third of its 1-year range | R2A + the ATR filter, energy books |
| R3-5 | **weekly NR7** (the narrowest week of 7) break, held 5 sessions | a bigger base before the move; fewer, larger moves | weekly bars from the daily series; break of that week's high / low during the next week; energy books |
| R3-6 | **break in the first two hours only** | Crabel: early breaks from compression follow through best | R2A, but only breaks between 09:15 and 11:15; energy books |
| R3-7 | **NR7 break, entry at the US open** (19:00 / 20:00 IST) if the break held all day | crude moves most in US hours; avoids the morning's wide spread (0.60%) | the same break, entry only if the future is still beyond the level at the US cash open |

If a fresh Dhan token appears before round 3, R3-0 is added first: **N1 exactly as round 1, priced on real ATM+/-10
prints** (no model marks), the cleanest answer to whether NR7 is tradeable on all four books.

## Honest limits

- Same as round 1: 11.3 months of design data, near month and ATM+/-3 only, assumed spreads, overlap with earlier
  studies' windows. The edge take-profit is forced by the data; with wider strikes the exits would differ.
- R2D and R3-1 choose crude after looking at results: their p-values are optimistic, which is why every idea stays in
  the cumulative BH and only the locked holdout can confirm anything.

## Files

`research/hunt/r96_mcx_buy/`: `PREREG_ROUND2.md`, `NOTES.md` (N4-N5), `results/design_round2.json`,
`results/report_round2.md`, `results/trades_design_round2.csv`, `LEDGER.md`. Command:
`IRAALGO_LAKE=~/lake python -m research.hunt.r96_mcx_buy.study design 2`, then `... .report 2`, `... .entries 2`.
