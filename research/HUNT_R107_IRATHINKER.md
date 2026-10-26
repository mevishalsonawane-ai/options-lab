# R107: every IraThinker strategy on every index (26 Oct 2026)

Boss, 26 Oct: "test each and every strategy for all the index and provide me the results from IraThinker".

## The answer in short

**No IraThinker strategy passed** on any index, after real option prices, real charges and slippage.
- 13 tests were priced on real NIFTY and BANKNIFTY options, and none passed the pre-registered gates.
- Because none passed, **the holdout (Oct 2025 onwards) was never read**.
- No edge is claimed for anything below.

| Strategy | What happened (design period, 1 lot) |
|---|---|
| **ORB alert** (IraThinker's live "BUY PUT" alert) | Lost on every index: NIFTY −₹869 a trade (35 trades, 29% won), BANKNIFTY −₹36 (38). FINNIFTY and SENSEX lost on index points too. The 3R runner was no better. Every ORB plan without the gates lost more (NIFTY 363 trades −₹61,627; BANKNIFTY 390 trades −₹80,966). |
| **Gap fade** | NIFTY about breakeven (+₹60 a trade, 30 trades, p 0.46); BANKNIFTY −₹96 (45). FINNIFTY and SENSEX lost on index points. |
| **mom60** (paper track) | About breakeven after costs: BANKNIFTY +₹7 a trade (213 trades, p 0.49), and it loses at 2x slippage. NIFTY +₹49 (118, p 0.43). IraThinker's own Black-Scholes paper tape shows +1.4% (BANKNIFTY) and +2.9% (NIFTY) per trade, which real prices did not confirm. On index points FINNIFTY moved the right way on average (p 0.02), but there are no FINNIFTY option prices to say whether that survives option costs. On NIFTY and BANKNIFTY it did not. |
| **BANKNIFTY short strangle** (option SELLING, paper track) | Closest to passing, but did not pass. It made +₹3,935 a week (36 weeks, 81% won, +13.9% of the premium). It failed two gates: too few weeks (36, below the 40 needed) and the multiple-testing check (q 0.155, above 0.10). The worst week lost −₹31,283 on one lot (−285% of its premium, in Aug 2025). It also needs about ₹2 lakh of margin, double Boss's ₹1 lakh. |
| **Prediction engine** 60-minute vote (display only in the app) | No edge on real options: NIFTY +₹7 a trade (312), BANKNIFTY −₹94 (342). SENSEX index points +₹252 (p 0.07), descriptive only. |

**What this means for IraThinker:**
- Its alerts are information, not a proven edge.
- The live ORB alert (buy PUT after VIX breaks up) lost money in the period tested.
- mom60 is a coin toss after costs.
- The strangle is the only candidate worth a longer test. It is option selling with naked tail risk, needs more data, and
  needs a margin Boss does not have.

## How it was tested

- **Signals: IraThinker's own code.** `research/hunt/r107_irathinker/java/R107Runner.java` calls the app's engine on
  historical bars:
  - `Analysis.signalHistory` for ORB and gap fade;
  - `Mom60.Watcher`, fed one closed bar at a time, for mom60;
  - `Strangle.buildEntry` for the strangle;
  - `Predict.forecast` at 10:30 for the prediction engine.

  No rule was re-written. Python only prices the trades the Java code names.
- **Signal source (CLAUDE.md):** every rule's signal is on the underlying index. The option is only what is bought, or sold
  for the strangle. Option-chain data plays no part.
- **Data:** the `research-data` release (downloaded 26 Oct). Strikes about ATM, nearest expiry, 1-minute prices.

  | Index | Period |
  |---|---|
  | NIFTY (index + real options) | 1 Apr 2024 – 13 Apr 2026 |
  | BANKNIFTY (index + real options) | 13 Feb 2024 – 23 Feb 2026 |
  | FINNIFTY and SENSEX (index only) | Feb 2024 – Feb 2026 |
  | INDIA VIX | to 27 Feb 2026 |

  No MIDCPNIFTY data exists, so it was not tested.
- **Real prices:**
  - one lot at the lot in force;
  - entry at the open of the minute after the signal bar closes;
  - IraThinker's strike, at the nearest listed one;
  - `obuy.costs` 'liq' slippage (5 bps plus 1–4 ticks by volume; stops 2x) and `Costs("dated")` charges (STT, exchange,
    SEBI, stamp, GST, ₹20 an order);
  - square-off 15:15 (15:05 on expiry day).
- **Monthly-contract rules** (mom60, strangle) are priced only when the nearest listed contract is that index's monthly.
  For BANKNIFTY that means from 14 Nov 2024, after its weeklies ended (Amendment 1). So 213 of BANKNIFTY's 477 mom60 fires
  and 36 of its design-period strangle weeks have real prices.
- **FINNIFTY and SENSEX** have no option data, so they are reported as index points × lot, before any option cost.
  Descriptive only; these can never pass.
- **Split:** design to 30 Sep 2025; holdout from 1 Oct 2025, read once, only for a test that passed design. None did.
- **Gates (PREREG.md):**
  1. 40 or more trades;
  2. mean > 0 with day-bootstrap p < 0.05;
  3. Benjamini–Hochberg q ≤ 0.10 over the 13 real-price tests;
  4. > 0 at 2x slippage;
  5. > 0 without the 5 best days;
  6. worst month ≥ −₹13,000.

## Every test (design period; ₹ per trade, 1 lot, after charges and slippage)

S1 = ORB alert at 2R · S1R = ORB alert at 3R · S1A = every ORB plan at 2R · S2 = gap fade · S3 = mom60 · S4 = strangle
(sold) · S5 = prediction engine. "Signals" counts the whole period; "trades" counts the design period's priced trades.

| Index | Test | Signals | Trades | Won | Mean ₹ | Total ₹ | p | BH q | Total at 2x slip | Worst month ₹ | Result |
|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | S1 | 41 | 35 | 28.6% | −869 | −30,424 | 0.99 | 0.99 | −30,950 | −14,739 | fail |
| NIFTY | S1R | 41 | 35 | 25.7% | −631 | −22,077 | 0.92 | 0.99 | −22,616 | −11,401 | fail |
| NIFTY | S1A | 490 | 363 | 34.7% | −170 | −61,627 | 0.87 | 0.99 | −67,523 | −31,180 | fail |
| NIFTY | S2 | 42 | 30 | 43.3% | +60 | +1,786 | 0.46 | 0.99 | +1,270 | −4,609 | fail |
| NIFTY | S3 | 636 | 118 | 50.0% | +49 | +5,809 | 0.43 | 0.99 | +4,102 | −8,386 | fail |
| NIFTY | S5 | 439 | 312 | 42.0% | +7 | +2,113 | 0.48 | 0.99 | −2,268 | −12,006 | fail |
| BANKNIFTY | S1 | 42 | 38 | 39.5% | −36 | −1,357 | 0.54 | 0.99 | −2,035 | −8,500 | fail |
| BANKNIFTY | S1R | 42 | 38 | 36.8% | −178 | −6,769 | 0.65 | 0.99 | −7,456 | −8,500 | fail |
| BANKNIFTY | S1A | 484 | 390 | 31.5% | −208 | −80,966 | 0.91 | 0.99 | −88,607 | −26,416 | fail |
| BANKNIFTY | S2 | 55 | 45 | 46.7% | −96 | −4,320 | 0.60 | 0.99 | −5,024 | −7,476 | fail |
| BANKNIFTY | S3 | 477 | 213 | 50.7% | +7 | +1,407 | 0.49 | 0.99 | −3,589 | −22,111 | fail |
| BANKNIFTY | S5 | 438 | 342 | 41.2% | −94 | −32,068 | 0.81 | 0.99 | −37,922 | −13,819 | fail |
| BANKNIFTY | **S4** | 98 | 36 | 80.6% | **+3,935** | **+1,41,646** | **0.012** | 0.155 | +1,40,477 | −10,080 | **fail** (n < 40, q > 0.10) |
| FINNIFTY | S1 | 46 | 40 | 37.5% | −453 | −18,136 | 0.73 | – | – | −13,939 | index pts only |
| FINNIFTY | S1R | 46 | 40 | 35.0% | −426 | −17,023 | 0.70 | – | – | −13,939 | index pts only |
| FINNIFTY | S1A | 490 | 391 | 41.4% | −63 | −24,660 | 0.60 | – | – | −54,129 | index pts only |
| FINNIFTY | S2 | 48 | 40 | 55.0% | −495 | −19,818 | 0.74 | – | – | −17,864 | index pts only |
| FINNIFTY | S3 | 470 | 414 | 55.6% | +433 | +1,79,244 | 0.02 | – | – | −17,785 | index pts only |
| FINNIFTY | S5 | 444 | 345 | 49.3% | +116 | +39,860 | 0.29 | – | – | −15,428 | index pts only |
| SENSEX | S1 | 40 | 36 | 36.1% | −531 | −19,108 | 0.78 | – | – | −9,374 | index pts only |
| SENSEX | S1R | 40 | 36 | 33.3% | −431 | −15,519 | 0.72 | – | – | −9,374 | index pts only |
| SENSEX | S1A | 494 | 395 | 39.0% | −35 | −13,767 | 0.57 | – | – | −30,854 | index pts only |
| SENSEX | S2 | 37 | 34 | 50.0% | −524 | −17,800 | 0.75 | – | – | −10,410 | index pts only |
| SENSEX | S3 | 383 | 340 | 52.1% | +160 | +54,349 | 0.26 | – | – | −50,981 | index pts only |
| SENSEX | S5 | 444 | 345 | 53.3% | +252 | +87,097 | 0.07 | – | – | −10,905 | index pts only |

### IraThinker's own paper tape vs real prices

| Strategy | IraThinker's study claim | IraThinker's Black-Scholes tape on this data | Real option prices (design) |
|---|---|---|---|
| mom60 BANKNIFTY | +2.29 / +0.99 / +4.75% a trade (train / val / test) | +1.37% a trade, 477 trades, 52% won | +₹7 a trade (213), about 0% |
| mom60 FINNIFTY | +2.33% a trade, n = 713 | +1.85%, 470 | no option data |
| mom60 SENSEX | +2.03% a trade, n = 593 | +1.91%, 383 | no option data |
| Strangle BANKNIFTY | +16.42% of premium, 77.8% won, worst week −285% | −14.83% of premium, 98 weeks | **+13.9% of premium, 81% won, worst week −285%** (36 weeks) |

The app's Black-Scholes tape prices options from 20-day realised volatility, so it is a poor guide either way. It made
mom60 look better than real prices did, and the strangle look worse: real premiums (implied vol) are richer than its
realised-vol model, which likely helps a seller.

## Not tested as trade rules

- **SMC, the Market Meter / BullBear gauge (GOC-style) and the Time Machine** are displays in the app, with no entry, exit
  or side. The Meter also needs whole-chain OI and breadth, which this data does not hold.
- **Price alerts** are Boss's own levels.
- **Market flush** is a message, not a trade.
- Earlier research on their parts is listed in `irathinker-app/REVIEW_2026-10-26.md`.

## Limits (read before using any number)

- Only about 2 years of data with real options. Every design sample is 30 to 414 trades.
- BANKNIFTY monthly-contract tests start in Nov 2024.
- The ORB alert's gate is IraThinker's Signal Test form (the lite IraScore). The live app's full IraScore adds its Time
  Machine and ledger points, which cannot be replayed on history.
- No MIDCPNIFTY; no FINNIFTY or SENSEX option prices.
- The strangle's expiry-week settlement uses the index's last close and ignores STT on exercise. It holds naked short
  options over nights and weekends (gap risk), and needs about ₹2 lakh margin.
- IraThinker's expiry rule (last Tuesday) is applied to 2024–25 dates in its own tape, when NSE's rule was different.
  This affects only the tape column, never the real-price results, which use the listed contracts.

## Files

| File | What |
|---|---|
| `research/hunt/r107_irathinker/PREREG.md` | the plan, written before any result, with Amendment 1 |
| `research/hunt/r107_irathinker/java/R107Runner.java` | runs IraThinker's engine on history |
| `research/hunt/r107_irathinker/r107prep.py` | release files to bar CSVs |
| `research/hunt/r107_irathinker/r107study.py` | real-option pricing, gates and holdout |
| `research/hunt/r107_irathinker/test_r107.py` | 12 tests, including the look-ahead guard |
| `research/hunt/r107_irathinker/results/results.json` | every number above |
| `research/experiments/entries/r107-*.yaml` | 25 entries, one per test |

To reproduce:
1. Download the release.
2. Run `r107prep.py`.
3. Compile `R107Runner.java` with IraThinker's sources (same classpath as its harness).
4. Run its modes (`orb`, `mom60 <IDX> <thr> <step> <bse>`, `strangle`, `predict`).
5. Run `r107study.py`.
