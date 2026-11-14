# IraAlgo neural research: intake audit and recovery checkpoint

14 November 2026. Research only. Status: **blocked before new training**.

This is a continuation of the owner's neural-network handover, not a completed model or trading validation. No production logic changed. No trades placed. No paid compute authorized or provisioned.

## Verified access and recovered inputs

Repository access confirmed with push permission. Base main commit: adbc8d53a84bd976f674bcec9646e4f019964dc7.
Experimental branch: research/iraalgo-neural-20261114.

Source data: the owner's connected data lake (private location omitted).

The root STATUS.txt was read. Its catalog totals are 198 Parquet files, about 8.40 GB and 495.7 million rows; these totals are catalog claims, not an independent full-lake row-count audit.
The index_1m and options_rolling_1m folder listings were inspected. The latest listed NIFTY 2026 files were modified on 19 October. No newer NIFTY upload appeared in those listings. Other dataset folders have not all been recursively checked for newer uploads.

Eight files were downloaded before the workspace failed: NIFTY, BANKNIFTY and INDIAVIX minute bars for 2025 and 2026, plus NIFTY weekly rolling options for 2025 and 2026. Local size checks succeeded for seven; the eighth download returned a running session and its file was subsequently listed at the expected approximate size. Full checksums, date coverage, duplicates, missing bars and OHLC validation remain pending.

Parquet footers were inspected for the two NIFTY 2026 files:
- index: 73,875 rows; open, high, low, close, volume, ts.
- options: 3,102,285 rows; offset, side, open, high, low, close, iv, volume, strike, oi, spot, ts.
These are footer row counts, not validated observations.

brain_models/v1 lists 24 pickle files: regime, daytype, range30 and range60 for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX and BANKEX. They have not been loaded, evaluated or combined.

All four previous baseline artifacts were recovered from the owner's saved files into the workspace before failure:
- train_baseline.py (2,737 bytes), SHA256 9c93270222d081a2c8bc5b627513cce038c2cecd4697ea6f973d616ecc54d8f9
- flat_parquet.py (6,029 bytes), SHA256 7c543219af171224da1d5073a8331022b6583f76d6de6345c55b72baf221a225
- baseline.pt (19,163 bytes), SHA256 d614b0ec0567c19672a27b5cac618474d93a263729263ba6778bd238143e6fc7
- baseline_metrics.json (862 bytes), SHA256 7306a2efca47777d34229a54e6393d23dbb615613dd47ecff348f32c37ce04a0

The source and metrics were read in full. Weights were recovered as bytes but not deserialized or validated. Workspace survival after the outage is unconfirmed; recover these four originals again if needed. The original weights and source files have NOT been transferred to this branch. Only the metric snapshot is included here.

## Baseline assessment

Saved rows: 56,445 train + 12,210 validation + 12,540 test = 81,195.
Split boundaries: 3 July and 25 August 2026.
Architecture in source: 16 inputs, hidden 64/32/16 with ReLU, one logit, eight epochs.
Source window: 20 October 2025 through 19 October 2026.
Target: whether NIFTY rises 15 rows later within a session, not option profit.
Source joins three indices and fits StandardScaler on training only. It selects the best validation-loss epoch.

Saved test accuracy: 52.5598%; balanced accuracy: 51.0254%; majority baseline: 52.4083%.
Improvement over majority: approximately 0.1515 percentage points on 12,540 saved test observations.
Validation loss was lowest at epoch 1 (0.692572) and ended at 0.699262, consistent with deterioration during fitting.
Training loss was not saved, so the full overfitting diagnosis is not independently established from a training/validation loss comparison.

No training reproduction or weight-to-metric verification was completed. The script's 15-row shift must be checked against elapsed minutes in the presence of gaps. This is an underlying-direction diagnostic, not evidence of a cost-adjusted option-buying edge.

## Research already reviewed

- R51 and R72 result reports were inspected. R72's historical C1-O pass must not be treated as a pass for a new model or new cost assumptions.
- R133 write-up: no tested combination met every final gate; sparse later trades and previously viewed holdout were disclosed.
- R169 report: 0/207 passed; do not repeat the same ORB enhancements as fresh discoveries.
- R170 report was retrieved with the other reports; detailed independent review remains incomplete.
- R171 report and amendment: profitability changes materially under spread assumptions. Treat roll/quoted/stress as sensitivity scenarios, not recorded executable bid/ask.
- research.brain.adapters.lake and calendar/expiry_infer were inspected. Rolling option files have actual strikes but no explicit expiry field. The repository's NIFTY observed expiry table ends at 13 April 2026; later rule-based reconstruction needs validation.

## Hard validation constraints

The historical April–October 2026 window has already been viewed in many repository studies and in the baseline. A chronological split of it may support development evaluation but **cannot create a genuinely untouched final test**. Reserve fresh, not-yet-inspected data for the new final test, or clearly label any retrospective results as development evidence. No paper-trading suitability claim is established.

Track a contract by underlying + actual expiry + fixed strike + side. Rolling offset may supply entry context; never use the same rolling offset as the held position. Reject ambiguous expiry, duplicate contract quotes, missing exits and stale/gapped paths; quantify rejected coverage rather than silently dropping it.

Use point-in-time joins, train-only transformations, purge labels across boundaries and prevent sequences spanning sessions or gaps. Underlying signals are the default; option OI/IV/Greeks are context. Treat reconstructed Greeks as model estimates.

Costs must be dated. Sources checked during intake:
- NSE STT: https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax
  Option-sale STT 0.10% through 31 March 2026, 0.15% from 1 April 2026.
- NSE exchange/IPFT circular: https://nsearchives.nseindia.com/content/circulars/FA73061.pdf
  Combined option charges change to Rs 3,553/crore from 1 March 2026.
- Broker charges: https://zerodha.com/charges
  Include brokerage, GST, SEBI fee, stamp duty and execution friction.
- Expiry specification: https://www.nseindia.com/static/products-services/equity-derivatives-nifty50
  Tuesday weekly/monthly schedule, previous trading day when Tuesday is a holiday.
- 2026 holiday circular: https://nsearchives.nseindia.com/content/circulars/CMTR71775.pdf
  In particular 20 October 2026 is a holiday; check the nearest contract mapping for 19 October.
Lot-size history still needs authoritative contract-level verification. Do not hard-code a current lot size across all historical contracts.

## Remaining authorized work

1. Restore compute; check downloaded hashes and recover four baseline artifacts.
2. Finish recursive catalog/freshness audit across indices, options, futures, equities, India VIX, participants and commodities. Prioritize NIFTY.
3. Reproduce baseline and compare its limited custom reader with pyarrow.
4. Pre-register a bounded experiment family before fitting: statistical/strategy baselines, MLP, CNN, LSTM, GRU, attention and controlled ensembles. Include regime, premium and CE/PE/no-trade targets; strike/expiry and risk decisions require executable contract paths.
5. Implement and test chronological walk-forward development, costs and stress costs, stops/exits, checkpoint/resume, logs, failure recovery, reproducibility and monitoring.
6. Report expectancy, drawdown, profit factor, daily-return Sharpe with explicit capital assumptions, win rate, net P&L, coverage and uncertainty. Correct for the full experiment family and existing research reuse.
7. Validate on a genuinely untouched final period once available. Reject unsupported edges.
8. Save trained models/checkpoints and reports on this experimental branch or suitable artifact storage. Keep production unchanged and do not merge main without approval.

## Execution blocker

The runtime initially reported 9 CPUs, about 9.7 GiB host memory and about 59.7 GiB free workspace disk; cgroup limits and GPU availability were not verified.
PyTorch was initially absent. pyarrow 17.0.0 and thrift 0.20.0 installed successfully.
CPU PyTorch 2.5.1 downloaded and reached installation, but successful installation was not verified.
The execution service then disconnected, and a follow-up command explicitly returned "No usable execution environment is available."

No new neural architecture was implemented, no new training started, no new trained model/checkpoint exists, no walk-forward backtest completed, and no persistent runner or live monitoring dashboard was deployed. Completion requires restored compute plus validated data and fresh final evaluation.

This commit contains only intake documentation, a status/inventory record and the previously saved metric snapshot. No source changes or runnable automation. Documentation was reviewed for consistency and secrets; local repository checks and CI execution were unavailable after the runtime outage.
