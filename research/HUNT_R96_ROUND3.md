# R96 round 3: crude NR7 refinements + three literature ideas (21 Oct 2026)

Boss, 21 Oct: *"Run until we found something, commodities options buying strategy"*. Research only: nothing in
`android/**` or `.github/**` changed; nothing trades.

- Pre-registration: `research/hunt/r96_mcx_buy/PREREG_ROUND3.md`, committed with the frozen code and tests in 9540826
  **before any round-3 statistic**. Notes N6-N7 in `hunt/r96_mcx_buy/NOTES.md`. Internet scan: the "Round 3 scan"
  section of `hunt/r96_mcx_buy/INTERNET.md`.
- Every number: `hunt/r96_mcx_buy/results/` (`design_round3.json`, `report_round3.md`, `trades_design_round3.csv`).
  Cumulative ledger: `hunt/r96_mcx_buy/LEDGER.md`.
- **Signal source of every rule: the MCX future.** The trade is always a 1-ITM option buy with a hard stop (-40% of the
  premium and a stop on the future) and a profit lock; every exit is at a real print where one exists (edge take-profit).
- **Locked holdout (15 Jul - 19 Oct 2026): still NOT read** (nothing passed every design gate).
- **Ideas tried so far in R96: 27** (9 + 8 + 10). BH runs over all 27.

## The answer for round 3

**LEADS (continue), nothing found. 0 of 10 pass. The crude NR7 breakout (R3A) passes every design gate except the
multiple-testing gate (BH q 0.126 > 0.10 over 27 ideas).** None of the three literature ideas survives MCX costs.

| rule (signal on the future) | design trades | net (1 lot) | twin p (BH q, 27) | folds + | worst month | real-exit net | verdict |
|---|---|---|---|---|---|---|---|
| **R3A** NR7 break, crude only (CRUDEOILM + CRUDEOIL) | 46 | **+Rs 48,875** | **0.014 (0.126)** | **4 of 4** | -Rs 9,718 | +Rs 30,747 | **lead: fails BH only** |
| R3B NR4 break, crude | 77 | -Rs 886 | 0.226 (0.524) | 2 of 4 | -Rs 30,883 | -Rs 17,418 | dies |
| R3C NR7, trail on the future instead of the premium ladder (energy) | 43 | +Rs 35,048 | 0.066 (0.341) | 3 of 4 | -Rs 19,847 | +Rs 35,492 | lead (fails p, BH, month) |
| R3D NR7 in a low-volatility regime (energy) | 27 | +Rs 16,991 | 0.178 (0.512) | 3 of 4 | -Rs 8,475 | +Rs 459 | weak lead (27 trades) |
| R3E weekly NR7, 5 sessions (energy) | 10 | -Rs 6,173 | 0.535 (0.831) | 2 of 4 | -Rs 15,275 | -Rs 19,866 | dies (too rare) |
| R3F NR7, breaks 09:15-11:15 only (energy) | 23 | +Rs 5,353 | 0.309 (0.557) | 2 of 4 | -Rs 22,200 | +Rs 22,330 | weak lead |
| R3G NR7 morning break still holding at the US open (energy) | 32 | +Rs 43,065 | 0.190 (0.512) | 3 of 4 | -Rs 21,647 | +Rs 26,583 | lead (fails p, BH, month) |
| R3H EIA-day 11:00-11:30 ET momentum (crude) | 90 | -Rs 10,239 | 0.585 (0.831) | 2 of 4 | -Rs 8,018 | -Rs 10,239 | dies |
| R3I last-half-hour intraday momentum (4 minis) | 223 | -Rs 79,100 | 0.639 (0.862) | 0 of 4 | -Rs 38,431 | -Rs 17,719 | dies (lost before costs) |
| R3J US-open range break in a high-vol regime (crude) | 14 | +Rs 7,406 | 0.126 (0.485) | 1 of 4 | +Rs 7,406 | +Rs 7,406 | dies (14 trades, one quarter) |

Design 5 Aug 2025 - 14 Jul 2026, 1 lot, net after Zerodha MCX charges and half-spreads; full table with gross, costs,
2x spread, drawdown, without-top-3, by book and the 2012-25 futures check: `results/report_round3.md`.

## What was learned

1. **R3A, NR7 on crude, is the strongest candidate R96 has had, and it is still not proven.** Design: 46 trades, 76% win,
   +Rs 48,875 net (+Rs 38,655 at 2x spreads), positive in all four folds (+12,721 / +4,353 / +13,445 / +18,356), worst
   month -Rs 9,718, max drawdown -Rs 11,854, +Rs 23,093 without its best 3 trades, 4% model-priced exits. Twin mean
   -Rs 7,067, twin p 0.014. 2012-25 crude futures: +0.58% a trade vs +0.41% for twins (329 trades, p 0.26).
   Why it is not a pass:
   - BH q 0.126 over 27 ideas (the gate is 0.10).
   - **The books were chosen after seeing round 2.** CRUDEOIL's half is the same 23 trades as R2D (+Rs 45,170). The
     CRUDEOILM half (+Rs 3,705) trades the same signal days at a tenth of the size, so it is not an independent check.
   - The rupee result is almost all one book: CRUDEOIL, 23 trades. Its median 1-lot stop risk is about Rs 4,657, so
     2% risk needs about Rs 2.3 lakh.
2. **With ATM+/-3 strikes, the exit question can't be answered on crude.** R3C swapped the premium ladder for a trail
   on the future. Its CRUDEOIL trades came out identical to R3A's (17 edge exits, 5 underlying stops, 1 time exit): every
   crude winner hits the deepest stored strike before the ladder or the trail can act. Only natural gas changed
   (-Rs 10,123 on 20). Wider strikes are still the missing data.
3. **Natural gas drags every NR7 variant down**, crude carries it. Gas NR7 lost in R3C (-Rs 10,123, 20), R3D, R3E and
   R3F. The exception is R3G's US-open entry: +Rs 9,895 on 13 (crude +Rs 33,169 on 19). This holds even though gas was the
   strongest book on 2012-25 futures (+1.08% vs +0.54%, p 0.02): its options (0.45-0.90% half-spreads, x1250 lot) eat
   the edge.
4. **The tighter filters cut too many trades.** NR4 doubles the signals but loses (77 trades, -Rs 886), so the
   compression has to be real. The low-vol filter (27), weekly NR7 (10) and early breaks (23) leave too few trades to
   judge. On weekly NR7, 2012-25 futures look good (+1.58% a trade vs about +0.27% for twins, 125 trades), but 11 months
   give only 10 option trades.
5. **The literature's intraday effects do not pay an MCX option buyer.**
   - EIA-day momentum (R3H): 90 trades, gross only +Rs 6,893, before Rs 17,132 of costs.
   - Last-half-hour momentum (R3I): lost before costs on all four books (223 trades, gross -Rs 39,120, 30% win).
   - High-vol ORB (R3J): 14 trades, all in the Feb-Apr fold (the March crude spike).
   This repeats round 1. Intraday commodity effects are a few basis points of the future, and a 0.30% half-spread on
   the option is larger.

## Which ideas died and why

| idea | why |
|---|---|
| R3B NR4 | more trades, no edge: -Rs 886 on 77, three months below -Rs 13k |
| R3E weekly NR7 | 10 trades; gas lost |
| R3H EIA momentum | gross +Rs 6,893 on 90 trades, below costs |
| R3I last half-hour | lost before costs (gross -Rs 39,120, 223) |
| R3J high-vol ORB | 14 trades, one fold |

## Leads

1. **R3A, NR7 breakout on crude.** It fails only the BH gate. The rules keep the holdout locked, so it can't be
   confirmed there. Round 4 can only strengthen or kill it by testing new ideas (each new idea also raises the BH bar).
   If Boss wants to change that rule, it is Boss's decision (see RESULT.md).
2. **R3G, US-open entry** (+Rs 43,065 on 32, 3 of 4 folds, crude +Rs 33,169 on 19). It fails twin p (0.19) and has a
   -Rs 21,647 month.
3. **R3C** (trail): on crude it is R3A; the gas half decides it.

## Round 4: pre-registered list

Same periods, locked holdout, costs, twins and gates (incl. gate 8); BH over 27 + these. Round 4 writes PREREG_ROUND4.md
with exact numbers before running anything.

| # | idea | why (evidence) | rule sketch (signal on the future) |
|---|---|---|---|
| R4-0 | **NR7 on all four minis, priced on ATM+/-10 real prints** (only if a fresh Dhan token lets the lake fetch wider strikes) | the edge take-profit is the binding constraint (R3C = R3A on crude); this is the clean test of whether the NR7 future edge (1,436 trades 2012-25) is tradeable | N1 exactly, no model marks |
| R4-1 | **NR7 crude, US-open entry** (R3A x R3G) | R3G's crude half (+Rs 33,169, 19) and R3A's 4 of 4 folds; avoids the 0.60% day half-spread | R3G's entry on CRUDEOILM + CRUDEOIL; labelled as chosen after rounds 2-3 |
| R4-2 | **NR7 crude with a 12-month TSMOM filter** (Moskowitz, Ooi & Pedersen 2012) | trade only breaks in the direction of the 12-month return sign; the literature's strongest commodity effect, as a filter only | R3A + the back-adjusted 250-session return sign agrees with the break |
| R4-3 | **EIA spike fade** (Li & Geman 2018; Rousse & Sevi 2019) | the competing hypothesis to R3H (which lost) | 10:30-10:35 ET move > 2 sigma of the last 60 EIA days' first 5 minutes -> 1-ITM against it at 10:36 ET; stop beyond the spike's extreme; out after 60 minutes |
| R4-4 | **MCX evening-open momentum, gold / silver** (Ma et al., Global Finance Journal 2025, night-session effect) | night-session first half-hour predicts the rest in Chinese metal futures | 17:00-17:30 IST move of the future -> 1-ITM that way at 17:31 (0.30% half-spread from 17:00); stop the 17:00 price; out at square-off |
| R4-5 | **NR7 on crude with a 2-ITM strike** | deeper strikes reach the edge later, so winners run further on real prints | R3A with 2-ITM |

## Honest limits

- 11.3 months of design data, near month and ATM+/-3 strikes only, assumed spreads (no bid/ask).
- R3A's books were picked after round 2; its p is optimistic. Only an untouched sample can settle it.
- The literature effect sizes in INTERNET.md come from abstracts; none were checked on full texts.
- Twins test timing within the month, not the month's trend.

## Files

`research/hunt/r96_mcx_buy/`: `PREREG_ROUND3.md`, `NOTES.md` (N6-N7), `INTERNET.md`, `results/design_round3.json`,
`results/report_round3.md`, `results/trades_design_round3.csv`, `LEDGER.md`, tests (47, incl. the look-ahead guards
for the new daily flags). Commands: `IRAALGO_LAKE=~/lake python -m research.hunt.r96_mcx_buy.study design 3`, then
`... .report 3`, `... .entries 3`.
