# Completed-candle alignment test

Read results/REPORT.md for the measured outcomes. The user's exact 5-minute,
1-minute and 10-second idea remains untested: no readable sub-minute underlying
and option history was available. No fake ten-second bars or proxy substitution.

The two actual tests use one-minute candle colour alone and agreement with the
latest completed five-minute candle. Bullish means close>open; bearish means
close<open. Signals are computed on NIFTY, then real fixed-strike CE/PE contracts
are held through the shared option execution engine. Exits/costs/risk are held
constant. This is the simple candle-colour interpretation, not EMA trend alignment.

```bash
python -m pip install -r research/neural_v2/requirements-final-lock.txt
python -m pytest research/mtf_alignment research/neural_v2/test_strategy.py -q
python -m research.mtf_alignment.study --lake /absolute/path/to/lake
```

Data requirements and scheduled expiry mapping are the same as
research/neural_v2/README.md. The six NIFTY/BANKNIFTY/INDIAVIX index files and two
NIFTY weekly option files remain outside git. BANKNIFTY/VIX are loaded by the
shared loader but do not enter these signals. No neural predictions used.

Local preregistration commit677b97f preceded results. Initial unit testing found
that NumPy's default relative tolerance accepted a Rs1 cash discrepancy; the
reconciliation guard now uses absolute tolerance1e-6 and rejects that regression.
The guard correction does not change trades. The four-arm BH adjustment was
added as a reporting safeguard after execution began; no entries/exits were
changed in response to results. It covers the four primary replay p-values, not
all historical research or diagnostic restarts.

No readable source tick data, private catalogue contents, credentials or raw
market bars are included. All public ledgers are derived research simulations.
Production code, workflows and main remain untouched. No orders or paid compute.
