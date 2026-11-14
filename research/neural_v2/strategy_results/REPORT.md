# NIFTY confirmed breakout backtest

Period: 2025-10-20 through 2026-10-19, 247 available sessions. Previously reviewed historical data; not a fresh test.

| Scenario | Trades | Net win rate | Gross Rs | Fees Rs | Spread/slippage Rs | Net Rs | Ending capital Rs | Realized DD Rs | Close-mark DD Rs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| base | 179 | 44.13% | -3077.81 | 12010.09 | 23454.25 | -38542.15 | 61457.85 | 38661.74 | 39568.27 |
| double_friction | 129 | 34.88% | 1159.11 | 8360.20 | 32488.56 | -39689.65 | 60310.35 | 39694.87 | 39807.73 |

## Rules

NIFTY breaks the preceding 20-minute high/low, BANKNIFTY confirms its direction, India VIX has not risen over 3% in 15 minutes. Entry on five-minute boundaries from 10:15–13:45. Buy one lot one-strike-ITM CE/PE, nearest scheduled weekly expiry 1–7 days away; skip expiry day.

Stop -15%, target +30%, protect 60% of the prior-bar peak gain after +15%. At 20 minutes exit unless at least +5%; maximum hold 45 minutes, square-off by 14:50. Maximum two trades/day and one position; cash and risk-budget checks apply.

Rs100000 initial capital; initial estimated risk at most Rs2500/trade, Rs3000 daily loss budget, Rs13000 monthly budget and Rs40000 drawdown budget. Gaps can overshoot limits. All entries, fills and exclusions are in the ledgers and results.json.

Neural models were deliberately excluded: the prior study did not establish an edge, and the final all-data weights cannot create an unseen historical test. The rule is informed by prior breakout/cost/risk findings, not by pretending those weights predicted their training data.

## Monthly net P&L

| Scenario | Month | Trades | Net Rs |
|---|---|---:|---:|
| base | 2025-10 | 11 | -5872.45 |
| base | 2025-11 | 29 | 2416.74 |
| base | 2025-12 | 29 | -9158.65 |
| base | 2026-01 | 30 | -3324.39 |
| base | 2026-02 | 33 | -4496.65 |
| base | 2026-03 | 5 | -2652.57 |
| base | 2026-04 | 17 | -7686.05 |
| base | 2026-05 | 20 | -6941.18 |
| base | 2026-06 | 2 | -566.01 |
| base | 2026-07 | 0 | 0.00 |
| base | 2026-08 | 2 | -14.14 |
| base | 2026-09 | 1 | -246.79 |
| base | 2026-10 | 0 | 0.00 |
| double_friction | 2025-10 | 12 | -7157.26 |
| double_friction | 2025-11 | 27 | -3461.41 |
| double_friction | 2025-12 | 27 | -12186.57 |
| double_friction | 2026-01 | 28 | -5032.30 |
| double_friction | 2026-02 | 30 | -8200.75 |
| double_friction | 2026-03 | 2 | -1437.30 |
| double_friction | 2026-04 | 3 | -2214.05 |
| double_friction | 2026-05 | 0 | 0.00 |
| double_friction | 2026-06 | 0 | 0.00 |
| double_friction | 2026-07 | 0 | 0.00 |
| double_friction | 2026-08 | 0 | 0.00 |
| double_friction | 2026-09 | 0 | 0.00 |
| double_friction | 2026-10 | 0 | 0.00 |

## Assessment

Retrospective result only. No paper/live approval: the rules were designed after earlier studies inspected this history. One candidate was registered before this run, without parameter searching. Prior research reuse remains selection bias.

OHLC fills assume stop/target availability with modeled friction. A minute touching both uses stop first; trail updates begin next bar. Spread law is an assumption, not a recorded bid/ask. Missing/nontraded holding minutes delay executable fills and are counted. A terminal unpriceable position aborts the run. Close-mark drawdown uses simulated liquidation at bar close; it is not tick-level worst drawdown.

Fees: brokerage Rs20/order, dated STT, exchange/IPFT, SEBI, GST and buy stamp duty. Cost rates use continuous approximations without contract-note rounding. Cost sources: https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax and https://nsearchives.nseindia.com/content/circulars/FA73061.pdf . Expiry is reconstructed from repository-observed dates or published schedules, not explicit expiry fields in the lake.

The stress scenario independently replays cash, risk gates and entries with doubled spread/slippage; it can take different trades. No real orders or production changes.
