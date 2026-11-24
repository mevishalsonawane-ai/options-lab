# IraAlgo NIFTY option replay, November 2026

## Result
The frozen dense model lost INR 12,804.61 over 44 final-period fills. Replaying the saved checkpoint reproduced every aggregate used for the headline. No model met the joint win-rate and average-profit gates. The no-trade benchmark preserved the INR 100,000 account. Research only.

| Dense model phase | Trades | Wins | Win rate | Net INR | Mean INR |
|---|---:|---:|---:|---:|---:|
| Refit, evaluated on its own training set | 491 | 401 | 81.67% | 319,787.75 | 651.30 |
| Three expanding validation folds | 189 | 78 | 41.27% | -25,454.23 | -134.68 |
| Final-period replay | 44 | 16 | 36.36% | -12,804.61 | -291.01 |

In the final period the dense model lost INR 6,175 before costs. Its win-rate session-bootstrap interval is 25.7–50.0%, mean P&L interval INR -622 to +83, profit factor 0.585, closed-trade maximum drawdown INR 18,235.63 and peak entry debit INR 15,754.18. Doubling spread/slippage produced INR -16,130.16. The sign-flip test for positive daily returns gave p=.933, Bonferroni-adjusted p=1 across four model families. Eighteen sessions constrain precision. The GRU won all four trades it selected (+INR 2,184 total). This was a secondary result and four observations cannot support promotion. Dense was the only preregistered family to meet the validation trade-count floor. It lost in aggregate even before the final period.

## Data and implementation
The Drive root manifest dated 16 November enumerated 290 Parquet files, 507,059,024 records, and 8,597,301,296 bytes. The separately listed brain_models/v1 folder contained 24 pickles. Supplementary dhan index files, brain models and other derived folders sit outside the manifest. The full-session historical files used here end on November 13. The November 16 order-book sample is a separate audit. The replay read 21 Parquet files covering 8,323,328 source records; date filtering and eligibility produced 4,938 candidates over an input window of 248 sessions. The two NIFTY November extension files came from the separate dhan/NIFTY folder. Nineteen inputs matched the root manifest SHA-256 and two matched the separate NIFTY manifest. A second execution from empty cache and checkpoints reproduced the full results JSON exactly. No raw chain or private configuration is included.

Twenty completed one-minute rows by 21 features describe NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY, India VIX, premium return/range, volume, OI, IV, strike distance, clock, expiry, option side, opening range and momentum. The signed OI balance is a descriptive call-minus-put quantity. Dealer inventories were unobserved. Weekly expiry is reconstructed from the repo expiry calendar and its October–November extension. The feed supplies absolute strikes and a front-week book with no explicit contract ID. Use the November 16 snapshot for a separate security-ID cross-check. Relative volume, OI and a minute's OHLC cannot establish actual order queue priority.

Dense flattens the 20×21 window into 420 inputs, then 32 and 16 ReLU units and a binary logit. GRU uses 16 recurrent units; temporal CNN uses two valid width-three convolutions with 24 and 16 channels, ReLU, mean pooling and a logit. Logistic regression uses time means. All learn the positive net one-lot outcome after 20 minutes. Scalers see training only. Eight AdamW epochs per neural fit; UTC/IST dates and epochs are logged. The final 340 candidates were excluded from fitting and model selection here. R187 had already studied the same calendar period; independent prospective testing remains outstanding.

## Execution
See PROTOCOL.md. Each half-hour opportunity evaluates both sides, takes the highest score above 0.55, or abstains. Sufficient historical activity, one whole lot, one concurrent debit, funds available and fixed strike/expiry are required. The one-minute model uses next-minute opens plus spread/slippage and dated repository charges. Volume in the execution minute is an ex-post fill proxy; historical bid/ask queue data are unavailable. The stress gate checks entry affordability using the base debit, a limitation near capital exhaustion. The 09:45 slot lacks a full historical 15-minute return for the earliest rows of its input sequence and is skipped. Max drawdown is computed on closed trades; intratrade adverse excursion can be larger. Final-period step, percent, ATR and adaptive exits all lost. The step rule uses completed closes and next tradable open, so it can lose more than INR 500.

Run results provide each fold/model's win/loss counts, net gains and losses, expectancy, daily Sharpe and Sortino including idle sessions, recovery, consecutive losses, monthly, call/put, expiry and regime splits, bootstrap intervals, sequence-permutation drawdown and cost stress. The corpus includes LSTM and ensemble studies on linked research branches. HMM, TFT, reinforcement learning and a fresh full-lake multi-asset training run remain open. None was deployed.

## Reproduce
Use Python 3.12, torch 2.5.1 CPU, pandas 2.2.3, NumPy 2.3.5, pyarrow 17.0.0 and scikit-learn 1.8.0 for training. Feature construction was also independently run with pyarrow 26.0.1. From repository root, with the authorized lake mounted:

```bash
python -m research.iraalgo_forward.tools.ingest /path/to/lake /tmp/ira-input
OPENBLAS_NUM_THREADS=1 python -m research.iraalgo_forward.train --lake /tmp/ira-input --cache /tmp/ira-cache --out research/iraalgo_forward/run
python -m research.iraalgo_forward.tools.diagnostics --cache /tmp/ira-cache --run research/iraalgo_forward/run --depth /path/to/dhan_optionchain_1min_20261116.parquet
python -m research.iraalgo_forward.tools.dashboard
python -m http.server --bind 127.0.0.1 --directory research/iraalgo_forward 8080
```

Open dashboard.html on port 8080. It polls status.json while a local job writes it. Opening the portable snapshot directly polls the published branch when the viewer permits network access. Current task, checkpoint, epoch, losses, candidate counts, returns and drawdown come from the runner. CPU time and thread count are recorded; instantaneous utilization was not sampled. A new execution can resume matching epoch checkpoints. A clean output directory starts an independent run. For replay, unpack checkpoints.zip into run/. Use weights_only=True for PyTorch inputs. Load joblib files only from a trusted source. No live brokerage execution or hosted trainer is configured.

## Prior work and external review
R169 recorded 0/207 ORB-family changes passing, R170 0/576, R171 0/72 execution gates, R133 0/185 ICT combinations and R187 0/20 neural entry/exit tests. In R136 the 35 extra ICT features degraded trade-outcome log loss (0.846 to 0.854). R176's initial INR 500 staircase returned approximately -INR 3.23 lakh across three one-lot arms versus +INR 6.37 lakh under the existing exits on their year. These figures are from the original reports. V0.1's saved code and metrics agree with the supplied 81,195 rows, 8 epochs, 52.56% accuracy and 51.03% balanced accuracy. It predicts index direction. Prior target-2000 hybrid and neural_v2 artifacts are on research/target2000-neural-20261115 and research/iraalgo-neural-20261114.

Bhala-Srinivash/nse-trading-skills (MIT) supplies position-size and stop concepts. Shubhamkumartrichy/claude-trading-skills-india (MIT, b62336d) includes cost calculators, next-bar replay and calendars. rupeezytech/algo_ai_skill (Apache-2.0, 48d90e2) includes backtest and broker-integration guidance. They can be used to cross-check assumptions; none supplies validated edge or proprietary historical fixed-contract fills. No external trading code was incorporated.

## Quote-level check
The 16 November sample contains 253,482 chain rows. Across 166 executable nearest-strike NIFTY side snapshots the median half spread was 0.125 points and the 95th percentile 0.15, compared with a 0.551 median modeled allowance. This single morning omits adverse selection and order latency. A separate five-index, 5-snapshot spot momentum diagnostic entered one snapshot later at the ask, held ten snapshots and sold at the bid using the same security ID and displayed quantity. Each index supplied six simulated trades. See run/depth_replay.json. This short sample establishes execution mechanics only.
