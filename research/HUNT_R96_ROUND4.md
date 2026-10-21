# R96 round 4: five more crude NR7 tests + two literature ideas (21 Oct 2026)

Boss, 21 Oct: *"Run until we found something, commodities options buying strategy"*. Research only: nothing in
`android/**` or `.github/**` changed; nothing trades.

- Pre-registration: `research/hunt/r96_mcx_buy/PREREG_ROUND4.md`, committed with the frozen code and tests in ed8697b
  **before any round-4 statistic**. Notes N8-N9 in `hunt/r96_mcx_buy/NOTES.md`. Internet scan: the "Round 4 scan"
  section of `hunt/r96_mcx_buy/INTERNET.md`.
- Every number: `hunt/r96_mcx_buy/results/` (`design_round4.json`, `report_round4.md`, `trades_design_round4.csv`).
  Cumulative ledger: `hunt/r96_mcx_buy/LEDGER.md`.
- **Signal source of every rule: the MCX future.** R4G also reads the option chain's ATM IV, as a filter only. Every
  trade is a 1-ITM option buy (R4E 2-ITM) with a hard stop (-40% of the premium and a stop on the future) and a profit
  lock (the ladder and the edge take-profit). Exits use real prints where one exists; model-priced exits are flagged.
- **Locked holdout (15 Jul - 19 Oct 2026): still NOT read** (nothing passed every design gate). R3A was not re-read.
- **Ideas tried so far in R96: 36** (29 + 7). R97 (gold) had logged nothing on main, so BH counts R96's ideas only.
- Data: the lake still stores ATM+/-3 strikes only on every MCX book (its STATUS.txt says +/-5; the files say +/-3), so
  the pre-registered R4-0 (NR7 on wider real prints) could not run and is not counted.

## The answer for round 4

**LEADS (continue), nothing found. 0 of 7 pass.** The best result yet is **R4G, crude NR7 only when options are cheap**:
+Rs 49,381 on 28 trades, twin p 0.002, BH q 0.036 over 36 ideas. It fails two gates: too few trades (28 < 30), and the
new family gate (q 0.124 > 0.10 once R3A and its five round-4 copies count as one idea). The two literature ideas lost.

| rule (signal on the future) | design trades | net (1 lot) | twin p | BH q (36) | family q | folds + | worst month | real-exit net | verdict |
|---|---|---|---|---|---|---|---|---|---|
| **R4G** NR7 crude, NR7 day's ATM IV below its 20-session median | 28 | **+Rs 49,381** | **0.002** | **0.036** | 0.124 | 3 of 4 | -Rs 4,030 | +Rs 31,253 | **lead: fails trades (28) and the family gate** |
| R4A NR7 crude, break still holding -> entry at the US open | 38 | +Rs 36,246 | 0.198 | 0.508 | 0.124 | 4 of 4 | -Rs 8,767 | +Rs 18,165 | lead (fails p, BH) |
| R4B NR7 crude, with the 12-month trend only | 15 | +Rs 11,534 | 0.178 | 0.508 | 0.124 | 3 of 4 | -Rs 6,692 | +Rs 11,534 | weak lead (15 trades) |
| R4F NR7 crude, with the 1-month trend only | 21 | +Rs 2,400 | 0.273 | 0.518 | 0.124 | 2 of 4 | -Rs 8,449 | +Rs 2,400 | weak lead (-Rs 1,807 at 2x spreads) |
| R4E NR7 crude, 2-ITM strike | 47 | -Rs 901 | 0.293 | 0.528 | 0.124 | 2 of 4 | -Rs 3,726 | -Rs 901 | dies |
| R4C EIA spike fade (crude) | 2 | -Rs 267 | 0.090 | 0.404 | - | 0 of 4 | -Rs 267 | -Rs 267 | dies (2 trades: the 0.20 ATR spike is rare; the ledger calls it a "lead" only because p < 0.10) |
| R4D 17:00-17:30 IST momentum, GOLDM + SILVERM | 214 | -Rs 1.10 lakh | 0.683 | 0.847 | - | 1 of 4 | -Rs 36,953 | -Rs 1.11 lakh | dies (lost before costs) |

Design 5 Aug 2025 - 14 Jul 2026, 1 lot, net after Zerodha MCX charges and half-spreads. Full table (gross, costs, 2x
spreads, drawdown, without the best 3, by book, the 2012-25 futures check): `results/report_round4.md`.

## What was learned

1. **R4G is R3A with fewer, better trades, and it still isn't proven.** Design: 28 trades (14 per crude book), 86% win,
   +Rs 49,381 net (+Rs 43,142 at 2x spreads), folds +26,036 / -3,584 / +8,574 / +18,356, worst month -Rs 4,030, max
   drawdown -Rs 5,573, +Rs 24,733 without its best 3. Median 1-lot stop risk: CRUDEOIL Rs 11,923 (2% needs about Rs 6.0 lakh),
   CRUDEOILM Rs 1,257 (about Rs 63,000); the pooled Rs 4,877 understates the big book. Twins (random sessions of the month, no IV filter) averaged -Rs 5,700.
   Why it is not a pass, and why the p is weaker than it looks:
   - 28 trades are **14 signal days**: both crude books trade the same days at a 10:1 size. The real sample is 14.
   - The 22 Oct 2025 winner (+Rs 18,128 on the two books) is a **model-priced exit** (its strike left the stored window).
     On real prints only, R4G made +Rs 31,253.
   - 24 of the 28 trades are R3A's own trades; 4 are new (21 and 25 Aug 2025, +Rs 7,907), freed up because R3A was
     still holding an earlier trade on those days. **Post-hoc check (not pre-registered, reported as a diagnostic only):**
     the 24 shared trades made +Rs 41,474, against +Rs 25,470 for a random 24 of R3A's 46 (p 0.068, 20,000 draws). R3A's
     22 trades on "IV not cheap" days still made +Rs 7,401 (68% win). So the IV filter helps a little, but most of R4G's
     result is R3A's crude NR7 result again.
   - The crude books were chosen after round 2; the filter was chosen in round 4 from the literature (pre-registered), not
     from the data.
2. **The other crude NR7 variants did not improve R3A.**
   - US-open entry (R4A): +Rs 36,246 on 38, 4 of 4 folds, but random days at the US open made +Rs 9,825 (p 0.198).
   - Trend filters (R4B 12-month, R4F 1-month) cut the sample to 15 and 21 trades. On 2012-25 futures both filtered
     versions look slightly better than random days (+0.53% vs +0.35%, 316 trades; +0.84% vs +0.56%, 374 trades), but
     neither is significant (p 0.31, 0.18).
   - 2-ITM (R4E) lost: -Rs 901 on 47. A deeper strike costs more per lot and reaches the stored edge sooner, so the
     winners are smaller (avg +Rs 627 vs R3A's +Rs 2,815 on CRUDEOIL).
3. **The evening-momentum effect from Chinese metal futures does not exist on MCX gold and silver options at 17:00**
   (R4D: 214 trades, gross -Rs 44,042 before Rs 66,367 of costs, 24% win). MCX has no session break at 17:00, so there
   is no "night open" like China's; this is the third intraday momentum idea to lose before costs (U1, R3I, R4D).
4. **The EIA spike fade almost never triggers.** A 0.20 ATR move in the first 5 minutes after the EIA release happened on
   only 1 day per book in 11 months (2 trades, -Rs 267). The filter was set too tight; it is logged as tried and dead.

## Leads

1. **R4G, crude NR7 when the NR7 day's ATM IV is below its 20-session median.** Fails trades (28) and the family gate.
2. **R3A, crude NR7** (round 3): fails BH (q 0.126 over 36; R4G's low p pulled it down from 0.135) and the family gate.
3. **R4A, crude NR7 at the US open:** 4 of 4 folds, but no better than random days at that clock.

The design sample cannot settle these: crude NR7 fires about twice a month, and every new copy raises the bar. What can
settle them is new data the rules have never seen (round 5, item R5-1).

## Round 5: pre-registered list

Same design, locked holdout, costs, twins and gates (incl. gates 8 and 9); BH over 36 + these (+ R97's ideas if logged on
main). Round 5 writes PREREG_ROUND5.md with exact numbers before running anything.

| # | idea | why (evidence) | rule sketch (signal on the future) |
|---|---|---|---|
| R5-0 | **R3A and R4G on wider real prints** (only if the lake gets ATM+/-5 or wider for MCX) | the stored edge still caps every crude winner; the 22 Oct 2025 trade is model-priced | R3A / R4G exactly, edge at the new window |
| R5-1 | **A fresh, untouched sample for R4G and R3A:** every crude NR7 signal from 20 Oct 2026 on, logged by the research code (not the app) and read ONCE when >= 15 signal days exist (about 8 months) | the only honest way past the multiple-testing wall without touching the locked holdout | R4G and R3A exactly as frozen; twin p and net, one read |
| R5-2 | **The cheap-IV filter on the other books** (NATURALGAS, NATGASMINI, GOLDM, SILVERM NR7) | tests R4G's filter where it was not chosen; R2A/R2D lost there without it | R2A / R2D + R4G's filter |
| R5-3 | **Cheap-IV NR7 straddle** (crude) | R2G (NR7 straddle) lost on expensive premiums; the filter targets exactly that | R2G on crude, only on R4G's cheap-IV days |
| R5-4 | **EIA spike fade at 0.10 ATR** | R4C's filter was too tight to test the idea (2 trades) | R4C with 0.10 x ATR14 (labelled as a re-try after seeing the trade count, not the result's sign) |

## Honest limits

- 11.3 months of design data, near month and ATM+/-3 strikes only, assumed spreads (no bid/ask).
- R4G's 28 trades are 14 signal days; one big winner is model-priced; its books were chosen after round 2.
- The post-hoc subset check (p 0.068) was not pre-registered and is a diagnostic only.
- The literature effect sizes in INTERNET.md come from abstracts; none were checked on full texts.
- Twins test timing within the month, not the month's trend.

## Files

`research/hunt/r96_mcx_buy/`: `PREREG_ROUND4.md`, `NOTES.md` (N8-N9), `INTERNET.md`, `results/design_round4.json`,
`results/report_round4.md`, `results/trades_design_round4.csv`, `LEDGER.md`, tests (60, incl. look-ahead guards for the
trend columns and the IV filter). Commands: `IRAALGO_LAKE=~/lake python -m research.hunt.r96_mcx_buy.study design 4`,
then `... .report 4`, `... .entries 4`.
