# Opening-range retest: exit economics

Research only. Previously inspected historical data; no independent final holdout.
11 exit policies plus one latency arm, each at normal and doubled modeled friction.
Every account starts with Rs100,000; changing stop size changes risk eligibility.

| Stop | Target | Hold min | Delay min | Trades | Win % | Gross Rs | Costs Rs | Net Rs | Stress net Rs | PF | BH q |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10% | 8% | 20 | 0 | 66 | 57.58 | 9335.60 | 13499.00 | -4163.40 | -11656.63 | 0.83 | 0.990 |
| 10% | 10% | 20 | 0 | 66 | 57.58 | 13080.03 | 13523.00 | -442.97 | -7954.45 | 0.98 | 0.990 |
| 10% | 12% | 20 | 0 | 66 | 53.03 | 12848.30 | 13520.91 | -672.61 | -8182.96 | 0.97 | 0.990 |
| 15% | 8% | 20 | 0 | 55 | 61.82 | 7046.83 | 10533.40 | -3486.58 | -10129.56 | 0.83 | 0.990 |
| 15% | 10% | 20 | 0 | 55 | 60.00 | 9045.53 | 10545.96 | -1500.43 | -8433.76 | 0.93 | 0.990 |
| 15% | 12% | 20 | 0 | 55 | 54.55 | 9137.66 | 10546.25 | -1408.59 | -8622.98 | 0.93 | 0.990 |
| 20% | 8% | 20 | 0 | 45 | 68.89 | 10568.55 | 8274.39 | 2294.16 | -3726.04 | 1.19 | 0.990 |
| 20% | 10% | 20 | 0 | 45 | 66.67 | 12139.55 | 8284.16 | 3855.39 | -3056.36 | 1.32 | 0.990 |
| 20% | 12% | 20 | 0 | 45 | 64.44 | 14072.87 | 8297.08 | 5775.79 | -692.85 | 1.47 | 0.990 |
| 15% | 10% | 10 | 0 | 60 | 43.33 | 1471.55 | 11408.92 | -9937.37 | -17973.90 | 0.57 | 0.990 |
| 15% | 10% | 30 | 0 | 55 | 61.82 | 7579.13 | 10535.67 | -2956.55 | -9574.96 | 0.88 | 0.990 |
| 15% | 10% | 20 | 1 | 55 | 50.91 | 6898.28 | 10539.87 | -3641.59 | -10833.92 | 0.84 | 0.990 |

## Interpretation

The 20%stop/10%target policy wins 30 of 45 trades (66.67%), net Rs3855.39; doubled-friction net Rs-3056.36. Its Wilson95%win interval is [0.5207048830990042, 0.7864112506566996], assuming independent trades. This is exploratory history, not evidence that the future win rate is at least65%.
Profitable doubled-friction endpoints: 0 of12. Read all target neighbors above rather than interpreting only the best point.
The20%stop also changes risk-budget admission. A post-result comparison against the15%stop/10%target center has 44 matching entries: their net changes by Rs1095.66, compared with the whole-account change of Rs5355.82. Differing executed trades contribute to that gap; it cannot be attributed solely to allowing the same positions more room. Full decomposition is below.
The latency arm tests the original15%center only, not the retrospectively interesting20%stop policy. The profitable boundary of this small grid is not a demonstrated robustness plateau.

## Chronological selection

The strict selector uses only preceding dates and retains the original 100-trade and 65%-win thresholds. Historical dates were previously reviewed in other research.

```json
[
  {
    "evaluation_start": "2026-04-01",
    "training_last_day": "2026-03-30",
    "selected": "NO_TRADE",
    "qualified": 0
  },
  {
    "evaluation_start": "2026-07-01",
    "training_last_day": "2026-06-30",
    "selected": "NO_TRADE",
    "qualified": 0
  }
]
```

## Cost and exit diagnosis for the original center

```json
{
  "reason": {
    "gap_target": {
      "trades": 1,
      "wins": 1,
      "gross": 1332.5,
      "fees": 81.11195162567161,
      "friction": 167.7005199999985,
      "net": 1083.68752837433
    },
    "initial_stop": {
      "trades": 10,
      "wins": 0,
      "gross": -13921.125000000002,
      "fees": 641.4087208403791,
      "friction": 1163.486110000003,
      "net": -15726.019830840383
    },
    "target": {
      "trades": 21,
      "wins": 21,
      "gross": 19226.900000000016,
      "fees": 1426.585744000924,
      "friction": 2662.9227120000032,
      "net": 15137.39154399909
    },
    "time_exit": {
      "trades": 23,
      "wins": 11,
      "gross": 2407.250000000002,
      "fees": 1548.4501416933372,
      "friction": 2854.291299999998,
      "net": -1995.4914416933334
    }
  },
  "side": {
    "C": {
      "trades": 26,
      "wins": 14,
      "gross": 1947.8000000000056,
      "fees": 1767.7116526455354,
      "friction": 3394.5196639999963,
      "net": -3214.4313166455263
    },
    "P": {
      "trades": 29,
      "wins": 19,
      "gross": 7097.725000000012,
      "fees": 1929.8449055147767,
      "friction": 3453.8809780000065,
      "net": 1713.999116485229
    }
  },
  "positive_gross_but_nonpositive_net": 1,
  "mean_cost_per_trade": 191.7446763665512
}
```

Post-result matched-entry diagnostic:
```json
{
  "exploratory": true,
  "basis": "Post-result matched-entry diagnostic; risk eligibility and subsequent account paths may differ",
  "groups": {
    "left_only": {
      "entries": 11,
      "original_net": -3970.525631544297,
      "wider_stop_net": 0.0
    },
    "right_only": {
      "entries": 1,
      "original_net": 0.0,
      "wider_stop_net": 289.6382312389676
    },
    "both": {
      "entries": 44,
      "original_net": 2470.093431383998,
      "wider_stop_net": 3565.7490916308216
    }
  },
  "net_difference": 5355.819523030086
}
```

## Validation and limitations

Baseline numeric ledger parity: True.
Profitable normal/stress center-grid neighbors: 0/8.
Qualifying historical policies: [].
All ledgers reconcile net = gross - fees - friction and ending capital.
results.json includes each policy in separate April-June and July-October accounts, Wilson intervals, 1,000 permutations and 1,000 five-day block resamples. These separate accounts do not sum to a continuous portfolio.
Monte Carlo resamples fixed rupee closed-trade P&L; it does not replay reordered cash gates or establish an edge. BH adjustment covers this batch only, not the entire research history.
Modeled OHLC fills are not executable quotes. Missing holding bars and expiry inference limitations of the shared loader still apply. No true 10-second, depth or aggressor-volume information is introduced.
No model or strategy is approved for trading. Prospective unseen data is required even if historical thresholds pass.

## Reproduce

```bash
python -m research.exit_economics.study --lake /absolute/path/to/lake
python -m pytest research/exit_economics research/retest research/neural_v2/test_strategy.py -q
```

Data hashes are in audit.json. The input loader and account engine are unchanged. See ../PREREG.md and ../SOURCES.md.
