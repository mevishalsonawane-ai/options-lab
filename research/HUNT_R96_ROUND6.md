# R96 round 6: NR7 and three more ideas on the untouched COPPER / ZINC option books (21 Oct 2026)

Boss, 21 Oct: *"Run until we found something, commodities options buying strategy"*. This is the last round before the
coordinator asks Boss whether to continue. Research only: nothing in `android/**` or `.github/**` changed; nothing trades.

- Pre-registration: `research/hunt/r96_mcx_buy/PREREG_ROUND6.md`, committed with the frozen code and tests in 24380ea
  **before any round-6 statistic**. Note N11 in `hunt/r96_mcx_buy/NOTES.md`. Internet scan: the "Round 6 scan" section
  of `hunt/r96_mcx_buy/INTERNET.md`.
- Every number: `hunt/r96_mcx_buy/results/` (`design_round6.json`, `report_round6.md`, `trades_design_round6.csv`).
  Cumulative ledger: `hunt/r96_mcx_buy/LEDGER.md`. Six-round summary: `research/HUNT_R96_SUMMARY.md`.
- **Signal source of every rule: the MCX future** (the COPPER / ZINC near future; daily rules: the back-adjusted
  near-month daily future). R6B also reads the ATM IV of the option chain, as a filter only.
- Every trade buys a 1-ITM option and has:
  - a hard stop: -40% of the premium, plus a stop on the future;
  - a profit lock: the ladder, plus the edge take-profit.
- Exits use real prints where one exists; model-priced exits are flagged.
- **Locked holdout (15 Jul - 19 Oct 2026): still NOT read** (nothing passed every design gate). Crude NR7 was not re-run.
- **Ideas tried in R96: 46** (42 + 4). Gate 10 (new): BH also with R97 / R98's 13 MCX gold ideas (59).
- Not tested and not counted (PREREG_ROUND6 s2):
  - the MCX-vs-COMEX gold lag: `xauusd_1m/` on Drive still holds only README.md and us_events.csv, so it is skipped;
  - wider strikes: the lake is still ATM+/-3;
  - real bid / ask spreads: the lake has none;
  - the crude NR7 forward read-out: needs 15 signal days from 20 Oct 2026.

## The answer for round 6

**NOTHING. 0 of 4 pass, and all four lose money after costs.** NR7, the one idea that looked alive on crude, loses
on copper, the first book it has met that was not used to choose it. ZINC's options are too thin to trade: 33 NR7
signals in design, 0 trades (no option print within 15 minutes of the signal).

| rule (signal on the future) | signals (COPPER / ZINC) | design trades | net (1 lot) | gross | twin p | BH q (46) | q with R97/R98 (59) | folds + | worst month | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| R6A NR7 break -> 1-ITM, 3 sessions (R3A's rule) | 34 / 33 | 13 (all COPPER) | **-Rs 84,978** | -Rs 63,383 | 0.830 | 0.932 | 0.942 | 1 of 4 | -Rs 48,265 | dies (31% win) |
| R6B R6A on cheap-IV days only | 21 / 11 | 7 (all COPPER) | -Rs 71,707 | -Rs 60,916 | 0.900 | 0.941 | 0.946 | 1 of 4 | -Rs 48,265 | dies |
| R6C first-half-hour move (to 09:29) -> 1-ITM at close - 45 min, out at close - 15 | 82 / 52 | 48 (47 COPPER) | -Rs 43,932 | +Rs 642 | 0.425 | 0.738 | 0.730 | 1 of 4 | -Rs 15,058 | dies (no edge before costs) |
| R6D first close beyond the 20-day channel -> 1-ITM, 5 sessions | 32 / 37 | 21 (20 COPPER) | -Rs 75,373 | -Rs 34,398 | 0.433 | 0.738 | 0.730 | 2 of 4 | -Rs 94,425 | dies |

Design 5 Aug 2025 - 14 Jul 2026, 1 lot, net after Zerodha MCX charges and the pre-registered half-spreads (COPPER
0.50% / 1.00% from / before 17:00 IST, ZINC 1.25% / 2.50%). The NR7-base family q (R6A + R6B) is 1.00. Full table
(2x spreads, drawdown, without the best 3, model / stale exits, stop risk, the 2012-25 futures check):
`results/report_round6.md`.

## What was learned

1. **NR7 does not carry over to copper (R6A).** 13 COPPER trades, 31% win, -Rs 84,978 (gross -Rs 63,383, so it
   loses before costs). Random sessions of the same months lost less (twin mean -Rs 53,902, p 0.83).
   - Two model-priced stop exits carry -Rs 48,175 of the loss (2 Mar 2026 -Rs 47,576; gate 8 fails at 15% model
     exits). On real-print exits only it still lost -Rs 36,803 on 11.
   - On 2012 - Jul 2025 daily futures the same NR7 break is mildly positive: COPPER +0.17% a trade vs +0.14% for
     random sessions (364 trades, p 0.42); ZINC +0.28% vs +0.14% (355, p 0.12); pooled +0.22% on 719. That is much
     weaker than crude, gas and gold in round 1 (+0.58% vs +0.32% on 1,436), and far too small for copper options.
   - So the crude NR7 result (R3A / R4G) stays a crude-only finding that was chosen after looking. It has not been
     confirmed on a fresh book.
2. **The cheap-IV filter does nothing here either (R6B):** 7 trades, -Rs 71,707. With R5B (gas, metals) and R5C (crude
   straddle), R4G's filter has now failed on every book except the one it was found on.
3. **Copper's first half-hour does not predict its last hour on MCX (R6C):**
   - 48 trades, gross +Rs 642 before Rs 44,575 of costs, 40% win, 1 of 4 folds.
   - This matches R3I (the last-half-hour version on the four minis, -Rs 79,100 on 223) and U1. The Chinese copper
     result (Jin et al. 2020) does not show up in an MCX option in the last hour.
   - 69% of R6C's exits use a stale print, because copper options print rarely just before the close. The gross is
     zero either way.
4. **A 20-day channel breakout loses on copper options (R6D):** 21 trades, -Rs 75,373. Most of the loss is one
   model-priced stop (30 Jan 2026, -Rs 94,172, the -40% stop on a gap). On real-print exits it is -Rs 1,268 on 18, flat.
   2012-25 futures: +0.07% a trade vs +0.02% for twins (783, p 0.28 / 0.33), a trend edge too small to pay for an option.
5. **The base-metal books are poor for an option buyer, whatever the signal:**
   - Lots are big. Copper is 2,500 kg at a ~Rs 30 premium, about Rs 75,000 a lot. The median 1-lot stop risk was
     Rs 26,000-32,000, so 2% risk needs about Rs 13-16 lakh of capital.
   - Spreads are wide. Zinc barely prints: 1 trade from 134 signals across R6C and R6D.
6. **A data limit to know about (diagnostic, not a gate).** COPPER's listed strikes are Rs 10 apart, but the lake's
   ATM+/-3 offsets repeat strikes, so the stored window holds only 3-4 distinct strikes (on 11 Mar 2026: 1200, 1210,
   1220). A 1-ITM call is then often already the deepest stored strike, so the edge take-profit fires within a minute or
   two of entry.
   - R6A had 5 such edge exits within 15 minutes, netting +Rs 1,928 in total, and R6D had 4, netting -Rs 4,575. They
     are not what lost the money; the stops are (R6A -Rs 53,986, R6D -Rs 1.53 lakh).
   - It does mean copper's right tail can't be measured with this lake, as with crude.

## Leads (unchanged; none from round 6)

1. **R4G, crude NR7 on cheap-IV days:** +Rs 49,381 on 28 trades (14 signal days), twin p 0.002.
   - Its own BH q is 0.046 over 46 (0.059 with R97 / R98).
   - It fails trades (28 < 30) and the family gate: **q 0.177**, up from 0.163 as the ledger grew.
2. **R3A, crude NR7:** +Rs 48,875 on 46, twin p 0.014; BH q 0.161 (0.165 with R97 / R98); fails the family gate.

The forward read-out (PREREG_ROUND5 s7) is unchanged and is the only honest way to settle them. It reads R3A and R4G
ONCE on minute data from 20 Oct 2026, at 15 crude NR7 signal days or on 30 Jun 2027, whichever is first.

## Honest limits

- 11.3 months of design data, near month and ATM+/-3 strikes only (3-4 distinct strikes on COPPER), assumed spreads.
  The COPPER / ZINC half-spreads were set from one-minute option ranges, not from quotes.
- Small samples: R6A has 13 trades, R6B 7, R6D 21. These rules die on the money and on the 2012-25 futures check, not
  on a fine statistical margin.
- Twins test timing within the month, not the month's trend.
- The literature effects (Jin et al. 2020; Szakmary, Shen & Sharma 2010) were read from abstracts only. Szakmary et al.
  use monthly data, so the 20-day channel is our adaptation.

## Files

`research/hunt/r96_mcx_buy/`: `PREREG_ROUND6.md`, `NOTES.md` (N11), `INTERNET.md`, `results/design_round6.json`,
`results/report_round6.md`, `results/trades_design_round6.csv`, `LEDGER.md`. Tests: 80, incl. look-ahead guards for the
channel signal and R6C's size filter. Experiment entries: `research/experiments/entries/r96-6-*.yaml`.
Commands: `IRAALGO_LAKE=~/lake python -m research.hunt.r96_mcx_buy.study design 6`, then `... .report 6`,
`... .entries 6`.
