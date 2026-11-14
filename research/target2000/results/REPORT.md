# Whole-lot Rs2000 neural target study

**Research only. No dependable65% profitable strategy or Rs2000-per-trade expectancy established.**

Inputs: 2025-10-20–2026-10-19; 16808 prospective sequence candidates. Training uses earlier dates; account evaluation starts April2026.
One whole historical NIFTY lot per position, Rs100000 account. Fixed next-open contract; target +Rs2000 NET at normal modeled costs, planned stop about-Rs1500, maximum30minutes. Tick rounding and gaps change realized payoff. Stress preserves identical premium barriers, doubles modeled friction and independently replays cash/risk eligibility.
Neural dense64/32 and causal CNN32→GRU32→attention→dense32; hybrid averages seeds42/73. Each has target probability, win probability and net payoff heads. Two expanding chronological folds; prior-only fit/validation/calibration. Logistic/ridge and momentum comparisons included.
The 2000-rupee objective is a winning-trade target, not a promised profit or a guaranteed average over losses. No fractional lots or profit scaling. Every date was previously researched, so these are development results rather than an untouched final test.

| Policy | Trades | Win % | Rs2000 reaches | Avg winner Rs | All-trade avg Rs | Net Rs | Stress net Rs | DD Rs | BH q |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| linear_p0.55_net0 | 6 | 50.00 | 1 | 861.41 | 54.30 | 325.81 | -404.79 | 1501.82 | 1.000 |
| linear_p0.55_net250 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| linear_p0.55_net500 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| linear_p0.60_net0 | 4 | 25.00 | 0 | 349.07 | -477.34 | -1909.34 | -2359.32 | 1909.34 | 1.000 |
| linear_p0.60_net250 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| linear_p0.60_net500 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| linear_p0.65_net0 | 2 | 50.00 | 0 | 349.07 | -576.79 | -1153.58 | -1375.30 | 1502.65 | 1.000 |
| linear_p0.65_net250 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| linear_p0.65_net500 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| dense_p0.55_net0 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| dense_p0.55_net250 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| dense_p0.55_net500 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| dense_p0.60_net0 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| dense_p0.60_net250 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| dense_p0.60_net500 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| dense_p0.65_net0 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| dense_p0.65_net250 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| dense_p0.65_net500 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| hybrid_p0.55_net0 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| hybrid_p0.55_net250 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| hybrid_p0.55_net500 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| hybrid_p0.60_net0 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| hybrid_p0.60_net250 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| hybrid_p0.60_net500 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| hybrid_p0.65_net0 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| hybrid_p0.65_net250 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| hybrid_p0.65_net500 | 0 | undefined | 0 | undefined | 0.00 | 0.00 | 0.00 | 0.00 | 1.000 |
| linear_ungated | 189 | 43.92 | 22 | 916.22 | -99.80 | -18862.07 | -39195.83 | 28556.91 | 1.000 |
| dense_ungated | 191 | 41.88 | 23 | 973.05 | -119.23 | -22773.28 | -39065.01 | 34045.09 | 1.000 |
| hybrid_ungated | 186 | 41.40 | 19 | 924.02 | -150.41 | -27976.05 | -34728.49 | 36146.13 | 1.000 |
| momentum | 140 | 35.00 | 17 | 1080.49 | -240.93 | -33729.94 | -35064.27 | 38234.14 | 1.000 |

## Candidate prediction quality

Candidate metrics describe overlapping proposed trades before account admission, not executed-trade performance. The constant-prevalence Brier is a hindsight diagnostic, not a fitted trading strategy.
```json
{
  "dense": {
    "samples": 9502,
    "target": {
      "prevalence": 0.09787413176173437,
      "mean_prediction": 0.13200633490282382,
      "auc": 0.6717903752653048,
      "brier": 0.08740966177851565,
      "constant_prevalence_brier": 0.08829478609362101,
      "bins": [
        {
          "low": 0,
          "high": 0.25,
          "n": 9447,
          "predicted": 0.13112202803783254,
          "actual": 0.09727955964856569
        },
        {
          "low": 0.25,
          "high": 0.4,
          "n": 55,
          "predicted": 0.28389809769505897,
          "actual": 0.2
        }
      ]
    },
    "win": {
      "prevalence": 0.37507893075142074,
      "mean_prediction": 0.3741601201727623,
      "auc": 0.5109702617280927,
      "brier": 0.23438994758739856,
      "constant_prevalence_brier": 0.2343947264577917,
      "bins": [
        {
          "low": 0.25,
          "high": 0.4,
          "n": 8100,
          "predicted": 0.36651459456669283,
          "actual": 0.36864197530864196
        },
        {
          "low": 0.4,
          "high": 0.55,
          "n": 1402,
          "predicted": 0.4183318444303673,
          "actual": 0.4122681883024251
        }
      ]
    },
    "net_mae": 869.7779318932833
  },
  "hybrid": {
    "samples": 9502,
    "target": {
      "prevalence": 0.09787413176173437,
      "mean_prediction": 0.1277591405812481,
      "auc": 0.6780627976056077,
      "brier": 0.08680989690063694,
      "constant_prevalence_brier": 0.08829478609362101,
      "bins": [
        {
          "low": 0,
          "high": 0.25,
          "n": 9363,
          "predicted": 0.12536257507718876,
          "actual": 0.09558902061305137
        },
        {
          "low": 0.25,
          "high": 0.4,
          "n": 139,
          "predicted": 0.2891911032755478,
          "actual": 0.2517985611510791
        }
      ]
    },
    "win": {
      "prevalence": 0.37507893075142074,
      "mean_prediction": 0.37451663999561124,
      "auc": 0.5312315361995389,
      "brier": 0.2346025640047531,
      "constant_prevalence_brier": 0.2343947264577917,
      "bins": [
        {
          "low": 0,
          "high": 0.25,
          "n": 1,
          "predicted": 0.24799390355170117,
          "actual": 0.0
        },
        {
          "low": 0.25,
          "high": 0.4,
          "n": 7634,
          "predicted": 0.35573417184824707,
          "actual": 0.3654702646057113
        },
        {
          "low": 0.4,
          "high": 0.55,
          "n": 1843,
          "predicted": 0.4499661593584612,
          "actual": 0.4150841020075963
        },
        {
          "low": 0.55,
          "high": 0.6,
          "n": 24,
          "predicted": 0.5602841561493418,
          "actual": 0.375
        }
      ]
    },
    "net_mae": 867.9996779988734
  },
  "linear": {
    "samples": 9502,
    "target": {
      "prevalence": 0.09787413176173437,
      "mean_prediction": 0.11547339952300101,
      "auc": 0.6654322149132711,
      "brier": 0.08603989743549574,
      "constant_prevalence_brier": 0.08829478609362101,
      "bins": [
        {
          "low": 0,
          "high": 0.25,
          "n": 9437,
          "predicted": 0.11395274313930005,
          "actual": 0.09621701812016531
        },
        {
          "low": 0.25,
          "high": 0.4,
          "n": 52,
          "predicted": 0.2934472859767793,
          "actual": 0.36538461538461536
        },
        {
          "low": 0.4,
          "high": 0.55,
          "n": 9,
          "predicted": 0.47326054816701313,
          "actual": 0.2222222222222222
        },
        {
          "low": 0.55,
          "high": 0.6,
          "n": 3,
          "predicted": 0.5715079867186775,
          "actual": 0.3333333333333333
        },
        {
          "low": 0.6,
          "high": 0.65,
          "n": 1,
          "predicted": 0.6230774975292634,
          "actual": 0.0
        }
      ]
    },
    "win": {
      "prevalence": 0.37507893075142074,
      "mean_prediction": 0.36445452095591413,
      "auc": 0.5206107990575264,
      "brier": 0.23489072704708608,
      "constant_prevalence_brier": 0.2343947264577917,
      "bins": [
        {
          "low": 0,
          "high": 0.25,
          "n": 178,
          "predicted": 0.22096941702315576,
          "actual": 0.3707865168539326
        },
        {
          "low": 0.25,
          "high": 0.4,
          "n": 7765,
          "predicted": 0.35168888216507105,
          "actual": 0.36806181584030906
        },
        {
          "low": 0.4,
          "high": 0.55,
          "n": 1519,
          "predicted": 0.4406420614422297,
          "actual": 0.40618828176431865
        },
        {
          "low": 0.55,
          "high": 0.6,
          "n": 30,
          "predicted": 0.5700693866709776,
          "actual": 0.6666666666666666
        },
        {
          "low": 0.6,
          "high": 0.65,
          "n": 6,
          "predicted": 0.619808510737394,
          "actual": 0.16666666666666666
        },
        {
          "low": 0.65,
          "high": 1.000001,
          "n": 4,
          "predicted": 0.673476971474209,
          "actual": 0.5
        }
      ]
    },
    "net_mae": 889.1942985711995
  }
}
```

## Decision and limitations

Historically qualifying policies: [].
A policy needs100executed trades,65%net wins,positive normal/stress net and existing risk gates to qualify even retrospectively. Fresh forward evidence is still required. Empty accounts have undefined win rates and cannot be an edge.
A Rs2000 winning target is below Rs2000 average expectancy whenever some trades lose or close earlier. We report all-trade mean separately; no success claim from the target amount alone.
Each full account carries across both outer windows; fold-ledger slices in results.json retain this account path. No best outer threshold or seed is selected.
1000closed-trade permutations and1000five-day block resamples per endpoint; fixed rupee reordering does not replay cash gates. BH covers62endpoints within this batch only, not all prior project trials.
Only six five-minute state snapshots are modeled. True10second, executable depth, aggressor delta and full intrabar paths are unavailable. Signed-volume and delta inputs are proxies. Option state snapshots may select different strikes over time; held positions never roll strike.
Labels are standalone trade outcomes; account gates can force earlier exits. Missing labels mask fitting only; candidates with unavailable future outcomes remain forecast rows. Unpriceable held terminal contracts abort replay.
Calibration uses earlier data and does not establish future calibration. Structural regime changes, inferred expiry mapping and modeled fills remain limitations. No production changes or real orders.

The two probability heads are calibrated independently and are not constrained jointly. probability_audit.json records any target-probability>win-probability inconsistencies, plus gate admission counts; these are diagnostics, not post-result corrections to forecasts.

## Reproduce

```bash
python -m research.target2000.study --lake /absolute/path/to/lake
python -m pytest research/target2000 -q
```

See ../PREREG.md and ../README.md. Model architecture/checkpoints/scalers/calibration are in models.zip;62full ledgers in ledgers.zip; all outer forecasts in forecasts.npz. Data hashes in data_audit.json.
