# R96 round 5: two new literature ideas + four from round 4's list (21 Oct 2026)

Boss, 21 Oct: *"Run until we found something, commodities options buying strategy"*. Research only: nothing in
`android/**` or `.github/**` changed; nothing trades.

- Pre-registration: `research/hunt/r96_mcx_buy/PREREG_ROUND5.md`, committed with the frozen code and tests in 8569af2
  **before any round-5 statistic**. Note N10 in `hunt/r96_mcx_buy/NOTES.md`. Internet scan: the "Round 5 scan" section
  of `hunt/r96_mcx_buy/INTERNET.md`.
- Every number: `hunt/r96_mcx_buy/results/` (`design_round5.json`, `report_round5.md`, `trades_design_round5.csv`).
  Cumulative ledger: `hunt/r96_mcx_buy/LEDGER.md`.
- **Signal source of every rule: the MCX future** (R5F: the GOLDM and SILVERM daily futures). R5B and R5C also read the
  ATM IV of the option chain, as a filter only. Every trade buys options with a hard stop (-40% single leg / -30% or
  -25% straddle, plus a stop on the future for single legs) and a profit lock (the ladder; edge take-profit where said).
  Exits use real prints where one exists; model-priced exits are flagged.
- **Locked holdout (15 Jul - 19 Oct 2026): still NOT read** (nothing passed every design gate). Crude NR7 was not re-read.
- **Ideas tried so far in R96: 42** (36 + 6). R97-R99 have logged nothing on main, so BH counts R96's ideas only.
- Not tested and not counted (s2 of the pre-registration): the MCX-vs-COMEX lag (the XAUUSD minute pack is not on
  Drive: only its README is), an OPEC+ pre-meeting straddle (monthly Sunday meetings: ~11 in design), weekly-expiry
  effects (MCX lists monthly options only), the gold overnight drift (far below GOLDM spreads; R97-R99 territory).

## The answer for round 5

**NOTHING new found; the leads are unchanged (continue). 0 of 6 pass, and all six lose money after costs.** The
natural-gas storage-day effect from the literature does not exist on MCX (it is negative on 2012-25 futures too), and
the cheap-IV filter that helped crude NR7 does nothing on gas and metals.

| rule (signal on the future) | design trades | net (1 lot) | gross | twin p | BH q (42) | folds + | worst month | verdict |
|---|---|---|---|---|---|---|---|---|
| R5A NG storage day: 1-ITM call 17:00 IST -> square-off (NATGASMINI, NATURALGAS) | 70 | -Rs 43,455 | -Rs 30,317 | 0.473 | 0.764 | 1 of 4 | -Rs 11,929 | dies (loses before costs; history -0.31% a Thursday) |
| R5B NR7 on gas + metals, cheap-IV days only | 36 | -Rs 36,585 | -Rs 24,410 | 0.677 | 0.869 | 1 of 4 | -Rs 19,552 | dies |
| R5C crude NR7 -> ATM straddle, cheap-IV days only | 26 | -Rs 28,728 | -Rs 13,897 | 0.762 | 0.872 | 1 of 4 | -Rs 7,175 | dies (19% win) |
| R5D EIA spike fade at 0.10 ATR (crude) | 15 | -Rs 368 | +Rs 1,749 | 0.162 | 0.553 | 1 of 4 | -Rs 798 | dies (too small to matter) |
| R5E pre-event ATM straddle 17:00 IST -> release - 5 min (crude + gas) | 164 | -Rs 93,324 | -Rs 35,058 | 0.283 | 0.560 | 0 of 4 | -Rs 14,873 | dies (14% win) |
| R5F gold/silver ratio breakout -> 1-ITM SILVERM, 5 sessions | 27 | -Rs 47,162 | -Rs 27,127 | 0.794 | 0.878 | 0 of 4 | -Rs 27,367 | dies |

Design 5 Aug 2025 - 14 Jul 2026, 1 lot, net after Zerodha MCX charges and half-spreads. Full table (2x spreads,
drawdown, without the best 3, model exits, stop risk, by book, the 2012-25 futures check): `results/report_round5.md`.

## What was learned

1. **The natural-gas "announcement day" drift is not on MCX (R5A).** On the 2012 - Jul 2025 NATURALGAS daily futures,
   Thursdays (the storage proxy) averaged **-0.31%** open -> close after 0.10% costs over 614 Thursdays, worse than other
   days (-0.17%, p 0.88). On options in design: 70 trades, 43% win, gross -Rs 30,317 before Rs 13,139 of costs. The
   paper's sample ends in 2018 and uses NYMEX returns; the full text was not read. (The pooled history counts the same
   614 NATURALGAS rows twice, once per gas book, as in earlier rounds; the per-book row is the real sample.)
2. **The cheap-IV filter is not a general edge (R5B, R5C).** On gas and metals NR7 breaks (36 trades) it lost -Rs 36,585;
   only SILVERM was positive (6 trades, +Rs 4,569). On crude, a cheap-IV straddle (26 trades) won only 19% of the time:
   cheap premiums did not make the straddle's two spreads and theta worth paying. So R4G's result is crude NR7's
   *direction* signal, not cheap options in general.
3. **Buying a straddle before the release and selling before it does not pay (R5E)**, 164 trades, 14% win, gross
   -Rs 35,058 before Rs 58,266 of costs. Random days at the same clock lost even more (twin mean -Rs 1.12 lakh, p 0.28),
   so there may be a small IV run-up into the release, but 3-4 hours of a monthly straddle's theta and four spreads
   swamp it. With E1 (straddle through the release) this closes the event-straddle family.
4. **The EIA spike fade at 0.10 ATR** traded 15 times for -Rs 368 (gross +Rs 1,749, p 0.162). Even the looser threshold
   gives too few trades and a gain smaller than its costs. Dead.
5. **The gold/silver ratio breakout (R5F)** lost -Rs 47,162 on 27 SILVERM trades (11% model-priced exits; real-exit net
   +Rs 14,705, so one model-priced loss dominates). On 2012-25 futures it is flat (+0.04% a trade over 807, twin -0.01%,
   p 0.32). No edge.
6. **The bar keeps rising.** R4G's q is 0.042 over 42 ideas, but its family gate (R3A + six copies, now including R5C)
   is **q 0.163** (was 0.124). R3A's q is 0.147. More design-window copies of crude NR7 can only make this worse; the
   honest route is new data (the forward read-out, s "Leads").

## Leads (unchanged from round 4)

1. **R4G, crude NR7 when the NR7 day's ATM IV is below its 20-session median:** +Rs 49,381 on 28 trades (14 signal
   days), twin p 0.002; fails trades and the family gate (0.163).
2. **R3A, crude NR7:** +Rs 48,875 on 46, twin p 0.014; fails BH (0.147) and the family gate.
3. **R4A, crude NR7 at the US open:** 4 of 4 folds, but no better than random days at that clock (p 0.198).

**Forward read-out (pre-registered in PREREG_ROUND5 s7):** the app's "NR7 crude (record only)" (rules = R3A, OFF by
default, logs only while Boss's switch is ON) is the forward log. R3A and R4G are read ONCE, by this repository's frozen
code on the lake's minute data from **20 Oct 2026**, when **15 crude NR7 signal days** with a trade exist or on
**30 Jun 2027**, whichever is first. Pass (each rule): net > 0 at 1x and 2x spreads, twin p <= 0.10, no month below
-Rs 13,000. A pass allows only a PAPER arm to be proposed to Boss.

## Round 6: pre-registered list

Same design, locked holdout, costs, twins and gates (incl. gates 8 and 9); BH over 42 + these (+ R97-R99's ideas if
logged on main). Round 6 writes PREREG_ROUND6.md with exact numbers before running anything. No more crude NR7 copies on
the design window.

| # | idea | why (evidence) | rule sketch (signal on the future) |
|---|---|---|---|
| R6-1 | **NR7 on books R96 has never touched: COPPER and ZINC options** (both are on Drive; daily futures since 2012) | an out-of-sample test of the NR7 idea on books not used to choose it; the 2012-25 futures check is independent too | R2A exactly (NR7 break -> 1-ITM, -40% / opposite extreme, ladder, edge, 3 sessions); R4G's IV filter as a second, separate test |
| R6-2 | **MCX-vs-COMEX/spot gold lag in Indian evening hours** (only if the XAUUSD minute pack is uploaded to Drive) | Boss's idea; untestable in round 5 | MCX GOLDM future lagging XAUUSD x USDINR by >= k after a 5-minute XAUUSD move -> 1-ITM GOLDM, out after 15 minutes; pre-check: the lag must exceed the option round-trip cost before any option run |
| R6-3 | **R3A / R4G on wider real prints** (only if the lake gets ATM+/-5 or wider for MCX) | the stored edge caps every crude winner; one R4G winner is model-priced | R3A / R4G exactly, edge at the new window (re-scoring, not a new idea) |
| R6-4 | **Real spreads**: replace the assumed half-spreads with the app's recorded MCX bid/ask, where recorded | every R96 cost is an assumption stressed at 2x | re-cost R3A / R4G (diagnostic, not a new test) |
| R6-5 | **The forward read-out** (s Leads) | the only honest route past the multiple-testing wall without the holdout | as pre-registered; not before 15 signal days |

## Honest limits

- 11.3 months of design data, near month and ATM+/-3 strikes only, assumed spreads (no bid/ask).
- The literature effect sizes in INTERNET.md come from abstracts and summaries; none were checked on full texts. The
  natural-gas paper's direction ("higher returns on announcement days") was read from its abstract only.
- R5A's history uses every Thursday as the storage day (holiday-moved weeks not removed).
- Twins test timing within the month, not the month's trend.

## Files

`research/hunt/r96_mcx_buy/`: `PREREG_ROUND5.md`, `NOTES.md` (N10), `INTERNET.md`, `results/design_round5.json`,
`results/report_round5.md`, `results/trades_design_round5.csv`, `LEDGER.md`, tests (70, incl. look-ahead guards for the
ratio signal, the storage stop's ATR and the IV filter). Experiment entries `research/experiments/entries/r96-5-*.yaml`.
Commands: `IRAALGO_LAKE=~/lake python -m research.hunt.r96_mcx_buy.study design 5`, then `... .report 5`,
`... .entries 5`.
