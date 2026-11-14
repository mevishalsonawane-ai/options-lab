# Neural V2 cost-adjusted development report

All results are retrospective development evidence. No untouched final test exists.

Input window: 2025-10-20 00:00:00+05:30 through 2026-10-19 00:00:00+05:30. Valid samples: 13,460; evaluated samples: 6,753.
Outer evaluation: 123 sessions; fixed capital Rs100000. No-trade baseline net and drawdown are zero.

| Model | Trades | Net Rs | Stress net Rs | Expectancy Rs | Max DD Rs | Win % | PF | Sharpe | BH q |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| mlp_0.001 | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| mlp_0.0003 | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| mlp_inner_selected | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| lstm_0.001 | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| lstm_0.0003 | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| lstm_inner_selected | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| gru_0.001 | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| gru_0.0003 | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| gru_inner_selected | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| ensemble | 0 | 0 | 0 | 0.0 | 0 | 0.0 | n/a | n/a | 1.000 |
| momentum | 830 | -175409 | -261102 | -211.3 | 177732 | 34.0 | 0.57 | -8.60 | 1.000 |
| contrarian | 814 | -90241 | -174435 | -110.9 | 94008 | 41.8 | 0.71 | -5.26 | 1.000 |

## Decision

No model is approved for paper or live trading by this study. Historical reuse, reconstructed expiry metadata, assumed spreads and incomplete market coverage prevent a credible fresh edge claim.

Entry gates are fixed by the preregistration. Zero trades means abstention and insufficient evidence, not a profitable strategy. Drawdown is measured on realized closed-trade equity, not intratrade mark-to-market. Per-fold results and prediction diagnostics are in results.json.

The experiment covers NIFTY only, with BANKNIFTY and India VIX context. Futures, equities, participant and commodity ingestion, verified Greeks, strike/expiry optimization, independent final testing and persistent hosted execution remain incomplete.

Zero IV is flagged as missing. No fabricated Greeks. Fixed ATM strike at signal time; next-open execution and minute-open risk exits. Stops can overshoot between observations. Full 16-minute contract paths are required before inclusion, creating a retrospective coverage restriction; rejected opportunities are not assumed executable.

No fresh test was consumed. R51/R72 were reviewed as historical references; momentum and contrarian baselines here are not parity reproductions of those arms. Bootstrap and BH do not remove historical selection bias. Stress columns reprice the same filled paths, not a second stop simulation.

Costs and schedule sources are in HANDOVER.md. Brokerage/taxes are continuous approximations without contract-note aggregation rounding. Checkpoints retain optimizer/RNG/scaler/configuration for resume and are not live trading artifacts.

BH includes 24 reported candidates across the initial and corrected cost runs. The first attempt is retained for transparency; the correction was not a search for better performance. Prior repository-wide research reuse remains an additional bias.

Baseline ledgers are unconstrained signal diagnostics: account cash, monthly loss and drawdown stop gates are not applied, so losses may exceed the fixed reference capital. They are not executable account simulations. Neural candidates made no trades; full account risk integration remains incomplete.
