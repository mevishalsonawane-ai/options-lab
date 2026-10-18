# R83: MCX energy and base metals - can any new rule beat the app's commodity arms? (18 Oct 2026)

Boss, 18 Oct: "use multiple agents/sessions to enhance the liquidity strategies and commodities strategies ... use all
the existing knowledge and, if you need, the internet." R83 covers crude oil, natural gas and base metals (copper, zinc,
aluminium). Gold and silver are R82's. Research only: nothing in `android/**` or `.github/**` changed.

- Plan: `research/hunt/r83/PREREG.md`, committed (97f2e00) before any rule was run.
- Code: `research/hunt/r83/` (`common.py` harness; `events.py`, `liq.py`, `eve.py`, `daily.py`, `mcxreal.py` rules;
  `report.py` gates and BH; `entries.py` log entries). Tests: `test_common.py`, `test_events.py`, `test_liq_eve.py`,
  `test_daily.py` (49 tests, including look-ahead guards).
- Results: `research/hunt/r83/results/` (`report.md`, `summary.json`, per-idea design and final files, final-read locks).
  Deviations, per worker: `NOTES_events.md`, `NOTES_liq.md`, `NOTES_daily.md`.
- Three agents ran in parallel (events and ORB; liquidity and the EVE regime; daily rules), all on the same harness.

## The answer

**Nothing passes. 0 of 13 frozen rules pass, from 33 variants tried. None goes to paper, and nothing in the app
changes.**

1. **Every intraday crude and natural-gas idea loses after MCX costs, on 7 years of design data and 3.8 years of final
   data.** Gross P&L is close to zero in almost every cell, so the costs decide the sign: about Rs 85-130 for a mini-lot
   round trip. That is the same wall M3, M4, R2 and R53 hit.
   - EIA crude Wednesday and natural-gas storage Thursday, fading the spike or following the break: all four lose in
     design, walk-forward and final.
   - The US-open breakout with trend or cross-market "breadth" agreement: the filters cut M3's loss roughly in half,
     mostly by halving the number of trades. It is still a loss.
   - The app's Liquidity book on crude, 1-hour and 15-minute bars: it loses.
2. **The app's natural-gas evening breakout (EVE) loses after costs on 2016-2026 Henry Hub data, with or without a
   volatility filter.**
   - Unfiltered EVE: -Rs 1.43 lakh in design (1,634 trades) and -Rs 51,428 in the final (881 trades).
   - Its timing is better than random (final twin p 0.015) and its gross is positive (+Rs 66k in the final), but the
     wide natgas book's costs take all of it.
   - This agrees with R53 (-Rs 3,508 on 115 trades). Switching the arm off would only lower risk; it is paper-only, so
     that call is Boss's.
3. **The 12-month trend arm on the two energy legs did well in the final: +Rs 53,610 over 113 leg-months,
   2023-Oct 2026.** Neither new exit (a chandelier stop, or a weekly check) beat it.
   - It was -Rs 14,589 in design (2012-2022). So this is one good stretch, not proof. The arm stays "not proven".
   - R53's loss for this arm (-Rs 66k) covered Apr-Oct 2026 and four legs including silver and zinc. Both readings can
     be true.
4. **Daily Donchian on crude made money, but agreement filters add nothing.** Crude Donchian-20 with Supertrend
   agreement made +Rs 21,751 in the final, on 27 trades (unfiltered: -Rs 2,695). But its walk-forward was below the
   unfiltered rule's, twin p was 0.23, and the top 5 trades carry all of it. Natural gas and copper Donchian lose.
5. **Natural-gas seasonality, OPEC+ days and the rollover week: no edge.** Seasonality lost even against simply staying
   short natural gas, which earns from contango.
6. **Options versus futures, same signal (real MCX minutes, Aug 2025 - Oct 2026):** the 1-ITM option was worse than
   the mini future in 4 of the 7 replays that had trades: E2, both E6 markets, and E7. It was better in 3: the NG
   storage follow (+Rs 20,781 on 49 option trades vs +Rs 4,003 on futures), the NG storage fade, and EVE-calm. In the
   last two both still lost. On crude the option was always the worse choice.

**Rs 5,000 a day from MCX energy at 1 lot: no, on this evidence.**

## Results (Rs per 1 mini lot, after strict MCX costs)

Final = 1 Jan 2023 - 13 Oct 2026, read once. Walk-forward (WF) = 2019-2022, each year's variant picked on earlier years.
BH q is across all 33 variants (the 32 pre-registered, plus E10's unfiltered baseline, which the worker also scored as
a variant). Twin p uses 200 random-time twin sets.

| idea | signal source | frozen pick | final trades | final net | baseline final | WF net | baseline WF | twin p | BH q | net without top 5 | real MCX Aug 2025 - Oct 2026: future / 1-ITM option (trades, net) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E1 EIA crude fade | WTI proxy -> CRUDEOILM | k 0.35 | 9 | -2,258 | 0 | -2,039 | 0 | 0.90 | 1.00 | -1,874 | 0 / 0 | fail, too few trades |
| E2 EIA crude follow | WTI proxy -> CRUDEOILM | 15-min range | 175 | -19,301 | 0 | -10,995 | 0 | 0.67 | 1.00 | -22,393 | 61 / -8,492; 58 / -41,088 | fail |
| E3 NG storage fade | Henry Hub proxy -> NATGASMINI | k 0.35 | 39 | -6,397 | 0 | -7,480 | 0 | 0.57 | 1.00 | -12,723 | 10 / -1,881; 9 / -720 | fail |
| E4 NG storage follow | Henry Hub proxy -> NATGASMINI | 15-min range | 157 | -8,681 | 0 | -10,354 | 0 | 0.02 | 0.66 | -15,511 | 51 / +4,003; 49 / +20,781 | fail |
| E5 OPEC+ follow | MCX CRUDEOIL daily | hold 5 days | 12 | -7,600 | 0 | -18,170 | 0 | 0.74 | 1.00 | -14,134 | - | fail, too few trades |
| E6 US-open ORB, crude | WTI proxy -> CRUDEOILM | SMA20 agree | 502 | -53,446 | -102,127 (M3 USORB) | -37,166 | -68,957 | 0.71 | 1.00 | -58,684 | 144 / -19,966; 139 / -75,285 | fail |
| E6 US-open ORB, NG | Henry Hub proxy -> NATGASMINI | breadth (crude agrees) | 540 | -74,598 | -137,669 (M3 USORB) | -85,575 | -163,408 | 0.63 | 1.00 | -81,541 | 170 / -24,624; 162 / -60,025 | fail |
| E7 Liquidity on crude | WTI proxy -> CRUDEOILM | 1h | 242 | -25,505 | 0 | -19,253 | 0 | 0.58 | 1.00 | -37,483 | 53 / +1,845; 49 / -29,705 | fail |
| E8 Donchian-20 + agreement | MCX daily (copper: COMEX x INR) | crude, Supertrend | 27 | +21,751 | -2,695 (unfiltered) | +36,863 | +38,034 | 0.23 | 1.00 | -34,423 | - | fail |
| E9 NG seasonality | Henry Hub history -> MCX NATGASMINI | \|t\| >= 1.5 | 14 | -37,413 | +14,687 (always short) | -16,310 | +45,719 | 0.75 | 1.00 | -65,664 | - | fail |
| E10 EVE + ATR regime | Henry Hub proxy -> NATGASMINI | calm (< 50th pct) | 533 | -37,644 | -51,428 (app EVE) | -14,854 | -89,164 | 0.045 | 0.74 | -48,931 | 175 / -15,608; 167 / -9,980 | fail |
| E11 trend arm exits | MCX daily | weekly check | 132 | -4,465 | +53,610 (app TSM252) | +14,185 | +13,582 | 0.68 | 1.00 | -147,101 | - | fail |
| E12 rollover week | MCX daily | crude long | 45 | +6,306 | 0 | -37,057 | 0 | 0.48 | 1.00 | -21,938 | - | fail |

### Design per variant (2016-2022 intraday, 2012-2022 daily; trades / net / twin p)

- E1: k 0.20: 148 / -10,620 / 0.66. k 0.35: 64 / -3,901 / 0.62. That is 312 EIA releases.
- E2: 30-min range: 279 / -15,387 / 0.33. 15-min range: 307 / -14,986 / 0.14. Gross +3k / +5k.
- E3: k 0.20: 165 / -10,541 / 0.12. k 0.35: 87 / -9,170 / 0.38. That is 303 storage releases.
- E4: 30-min range: 273 / -22,189 / 0.13. 15-min range: 291 / -20,543 / 0.06. Gross +12k / +16k, eaten by natgas
  costs.
- E5: hold 3 days: 33 / -24,827 / 0.97. Hold 5 days: 33 / -21,121 / 0.85.
- E6 crude: SMA20: 937 / -58,942 / 0.27. Breadth: 956 / -67,257 / 0.58. Unfiltered USORB: 1,800 / -1,12,932.
- E6 NG: SMA20: 915 / -1,24,264 / 0.74. Breadth: 937 / -1,22,322 / 0.66. Unfiltered USORB: 1,788 / -2,49,641.
- E7: 1h: 430 / -26,503 / 0.40 (gross +1,411). 15m: 1,890 / -1,03,159 / 0.01 (gross +20,255).
- E8: crude ST: 80 / +77,182 / 0.08. Crude breadth: 60 / +73,538 / 0.08. Unfiltered crude: 83 / +79,196.
  NG ST: 97 / -37,898. NG breadth: 67 / +15,561. Unfiltered NG: -32,755.
  Copper ST: 104 / -11.2 lakh. Copper breadth: 76 / -8.3 lakh. Copper is on an unadjusted Yahoo front-month series,
  and 1 lot needs about Rs 3.3 lakh margin, so treat the copper numbers as rough.
- E9: |t| >= 1.5: 26 / -37,002 / 0.69. All months: 131 / -1,17,701 / 0.83. Always short: +38,976.
- E10: calm: 593 / -25,459 / 0.01 (gross +38,695). Busy: 844 / -97,526 / 0.27. App EVE: 1,634 / -1,42,662 / 0.09
  (gross +59,162).
- E11: chandelier: 243 leg-months / +5,135 / 0.59. Weekly: 306 / +26,188 / 0.56. App TSM252: -14,589.
- E12: crude long: 127 / -178. Crude short: 127 / -30,876. NG long: 127 / -14,488. NG short: 127 / -13,464.

## Ideas considered, and what happened to each

| Boss's / the brief's idea | R83 test | verdict |
|---|---|---|
| EIA crude (Wed), fade or follow | E1, E2 | both lose; fade has too few big spikes |
| API report (Tue) | not tested | released 16:30 ET = 02:00-03:00 IST, after MCX closes; untradable (M3) |
| Natural-gas storage (Thu), fade or follow | E3, E4 | both lose on the proxy. E4 was positive on 14 months of real MCX, which is too short to count |
| OPEC+ meeting days | E5 | loses; 12 final trades; dates are approximate |
| US-session open ORB | E6 | the filters halve M3's loss but stay negative |
| Liquidity sweep on crude 15m / 1h | E7 | loses on both |
| Supertrend / Donchian with agreement, incl. cross-market breadth | E6, E8 | filters don't beat the unfiltered rule out of sample |
| Natural-gas seasonality | E9 | loses; worse than staying short |
| Volatility regime (ATR percentile) | E10 | calm-only loses less than the app's EVE, but still loses |
| 12-month trend arm on energy: exits | E11 | the app's rule beat both new exits in the final |
| 12-month trend arm: roll timing | not tested | no next-month price history (the continuous series holds the near contract only) |
| MCX options vs futures, same signal | real-MCX replay of E1-E4, E6, E7, E10 | options worse in 4 of 7 replays with trades (every crude one, plus the NG ORB); better in 3 natgas ones, 2 of which still lost |
| Rollover-week effect | E12 | no edge |
| Zinc and aluminium | not tested | no data: the lake has none and the Dhan token is expired (DH-901, 18 Oct) |

## How it was tested

- **Signal source.** Intraday rules read the **US benchmark future as a proxy** for the MCX future it drives:
  - WTI (Dukascopy LIGHT.CMD) for MCX crude: MCX crude = WTI x USD/INR, with 5-min return correlation 0.94 (NN_CRUDE);
  - Henry Hub (GAS.CMD) for MCX natural gas.

  The proxy's % move is booked as the MCX % move, on 1 CRUDEOILM (10 bbl) or NATGASMINI (250 mmBtu) lot, only inside
  MCX hours (09:00 to 23:30 / 23:55 IST), flat 15 minutes before the close. Daily rules read the MCX continuous future
  itself (lake), and COMEX copper x USD/INR for copper. The real-MCX replay reads the MCX near future's minute prices
  (the lake's option `spot`) and buys the 1-ITM near-month option. No rule reads an option's own chart.
- **Data.**
  - Dukascopy 1-minute data, 2016 - 16 Oct 2026: WTI 3,639,541 minutes (13 days failed to download), Henry Hub
    3,028,113 (9 failed), copper 3,536,023 (11 failed).
  - Lake MCX options and futures: Aug 2025 - Oct 2026. Lake MCX daily: 2012-2026. Yahoo daily and USD/INR.
  - All of it lives in /dev/shm, none in git. Price scales were checked against Yahoo: WTI /1e3; gas and copper /1e4.
- **Costs (strict).** Charges are the app's `McxCosts.kt`, ported with a parity test (Rs 194 for a CRUDEOIL futures
  round trip, Rs 84 for an option round trip). Half-spreads, paid on entry and on exit:
  - CRUDEOILM 0.03%;
  - **NATGASMINI 0.06%** (2x the app default, for the wide natgas book);
  - copper 0.05%;
  - plus 0.02% on stop exits.

  Options: CRUDEOIL 0.30% / 0.60% (after / before 17:00); **NATURALGAS 0.45% / 0.90%** (1.5x the app). Daily rules
  pay a full round trip at each roll, and the roll gap is not booked as P&L.
- **Honesty.**
  - Design, then frozen picks, then one final read per idea. The lock files are `results/final_read_*.json`.
  - Walk-forward folds 2019-2022. 200 random-time twins per rule. BH across all 33 variants. A top-5-removed check.
  - Look-ahead guards in the pytest suites.
- **Disclosure: the final window is not "unseen".** It overlaps earlier studies' holdouts: M2 (2025-26),
  M3 and M4 (Jul-Oct 2026), and R53 (Apr-Oct 2026). None of them ran these rules.

## Honest limits

- **Proxy, not MCX.** The proxy carries no MCX-specific basis moves. R2 found MCX catches up to the US within about a
  minute, so minute-level signals transfer, but MCX fills can differ by a tick or two. Where both were computed for the same 14 months (E2, E3, E4, E6 crude, E6 NG), the real-MCX futures replay
  has the same sign as the proxy. The size differs: for E4, +Rs 4,003 on real MCX against +Rs 1,674 on the proxy.
- **Close-only fills.** Entries are the next minute's close (E7: the next open), and stops are checked on minute
  closes. The real-MCX series has closes only.
- **Event calendars.**
  - EIA: official 2025-26 exceptions are used; before 2025, holiday weeks are skipped.
  - Storage: holiday weeks are skipped in every year. EIA's rule moves the report (e.g. a Thursday holiday moves it to
    Wednesday 12:00 ET; [BOE Report / Reuters, 9 Oct 2026](https://boereport.com/2026/10/09/schedule-for-us-energy-data-in-holiday-week-2/amp/)).
  - OPEC+ dates are NN_CRUDE's approximate list, typed from memory.
- **Twins for events.** Every Wednesday is an EIA day and every Thursday a storage day, so twins use non-event Mondays,
  Tuesdays and Fridays.
- **Roll detection** (daily rules) uses open-interest jumps. It misses 5 of 178 crude months and 8 natural-gas months;
  in those months one roll gap is booked as P&L.
- **E6 was run as two ideas** (crude and NG), each with its own frozen pick. That is still 4 variants.
- **MCX hours.** From Monday 2 Nov 2026, non-agri MCX trades 09:00-23:55 again
  ([Zerodha bulletin, 30 Sep 2026](https://zerodha.com/marketintel/bulletin/459705/revision-in-commodity-market-trading-hours-from-november-02-2026)),
  as `McxSession.kt` already assumes.
- **Outside evidence** (ideas only, never evidence of an edge): crude's price reaction to EIA inventory news is fast,
  reverts within about 25 minutes, and partly leaks before the release
  ([Geman & Li, Journal of Energy Markets](https://www.risk.net/journal-of-energy-markets/5728636/an-analysis-of-intraday-market-response-to-crude-oil-inventory-shocks));
  API and EIA surprises move 5-minute returns at once
  ([Ye & Karali 2015](https://ageconsearch.umn.edu/record/205595/files/AAEA_Ye_Karali-2015.pdf)). On MCX, after
  costs, neither the reaction nor its reversal paid.

## For the app

- **Nothing to build.** No rule passed, so there is no `APP_SPEC.md`.
- Risk-lowering options for Boss: switching off the paper NG evening arm (EVE) would lower risk. It lost after costs in
  R83 (2016-2026, both periods) and in R53. Keep the 12-month trend arm's energy legs as they are, still "not proven".
