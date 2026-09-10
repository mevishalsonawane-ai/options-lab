# options-lab

A net-of-cost research harness for intraday Indian index options, and one
strategy that survived it.

Built after two earlier attempts in this codebase reported 90%+ win rates that
turned out to be arithmetic rather than edge. The design rule here is therefore
narrow: **prefer the option that makes a wrong number impossible over the one
that makes it unlikely.**

## The one result

```
PYTHONPATH=. python -m options_lab.cli expiry-put
```

On a NIFTY expiry day, at 11:00 IST, sell one put at the nearest listed strike
to `F x 0.9925`, where `F` is the put-call-parity forward. Hold to cash
settlement. Never buy it back.

Measured on 170 expiry sessions, 2023-01-05 to 2026-04-13, lot 65, all costs:

| variant | win rate | mean/trade | worst trade |
|---|---|---|---|
| 0.75% OTM, quoted spread | 97.65% (166/170) | +Rs 394 | -Rs 2,507 |
| 1.00% OTM, roll spread | 100.00% (170/170) | +Rs 271 | +Rs 10 |

Take the 0.75%. It pays more, and it has actually shown you four losses; the
100% cell has never been billed, so every downside number for it is arithmetic
on a hypothetical.

**What this pays, honestly: 5% to 12% a year on properly capitalised money.**
Weight the lower half. The sample is a bull market in which no expiry session
fell more than 1.11% from the 11:00 mark, and the +Rs 394 headline is the
survivor of a roughly 370-configuration search whose selection cost was never
charged against it.

## What this is not

The loss is **unbounded**. A -6% expiry costs about Rs 82,000 per lot, which is
259 median wins. A 2020-03-23 repeat costs more than the margin you posted.
`options_lab/strategy/sizing.py` computes that tail and sizes against it; read
that file before trading this.

Capacity is about **2 lots**. Median near-ATM top-of-book depth is 2.0 lots, so
extra capital buys a deeper buffer and nothing else. At Rs 10 lakh the return
dilutes to about 4%/yr. This is not a percentage business; it pays roughly
Rs 41,000 a year, full stop.

## What was tested and closed

Everything directional. Six signal families across five horizons reached a
hit-rate ceiling of **51.2%** against the 59-74% required at a 1-minute hold.
A 38-cell walk-forward scalping sweep produced 2 "significant" cells where
chance predicts 1.9, and those inverted out of sample. Delaying entry by one
minute flips the best 1-minute edge from +0.290 to -0.220 index points: real
microstructure edge decays with latency, this one inverts.

Multi-day does not rescue it either. From five days out, a coin that always
says "long" clears the cost bar with zero skill, because what clears it is
drift. Buy-and-hold returned 11.25%/yr on a sealed 2017-2026 holdout and beat
every active rule tested.

## Layout

```
options_lab/
  harvest/      nightly Upstox collector -> parquet day-partitions
  backfill/     HTTP-Range reader over the 4.1 GB Kaggle options archive
  strategy/     expiry_put (the live one), sizing, signal_backtest
  features/     signed order flow, DTE and liquidity gates
  indicators/   UT Bot and Linear Regression Channel, ported from Pine
  costs.py      itemised charges + the tick-floored spread law
  ic.py         panel IC with its null band and controls attached
  data/         170 expiry-session chains, harvested bars, instrument masters
docs/design.md  the full spec, including the stopping rule
```

## Invariants worth knowing before you change anything

- **No zero-cost path exists.** `costs.REGIMES` holds only `roll`, `quoted` and
  `stress`. Once a zero-cost model is constructible, some run uses it and gets
  reported.
- **The spread has a tick floor**, `0.162 + 0.00292 x premium`. A flat
  percentage is fine on a Rs 785 option and physically impossible on the Rs 5
  option this strategy trades: 0.9% of Rs 4.85 is below the Rs 0.05 tick.
- **Contract identity is `(underlying, expiry, strike, right)`**, never the
  broker's `instrument_key`. Upstox recycles tokens at settlement, so a stored
  key can later resolve to a different underlying's contract with no error.
- **Quantities are normalised to contracts at ingest.** Upstox publishes
  lot-multiplied units; NSE and Kaggle publish contracts. Splicing them
  unconverted puts a 65x step change exactly where it reads as alpha decay.
- **`ic.py` refuses to print a raw IC without its partial.** That omission is
  how PCR and max pain look real; both are near-monotone transforms of the
  index level and neither survives controlling for it.
- **Modules refuse impossible input loudly.** `NotASnapshot` names the
  duplicated strike, `DirtyInput` names the NaN index, `AmbiguousChain` names
  the expiries. Eight defects were found by feeding these modules input their
  own tests never produced; all eight returned a plausible wrong answer rather
  than failing.

## Running it

```bash
pip install pandas numpy pyarrow requests pytest

PYTHONPATH=. python -m pytest options_lab/tests/ -q        # 206 tests
PYTHONPATH=. python -m options_lab.cli expiry-put          # the strategy
PYTHONPATH=. python -m options_lab.cli ic --underlying NIFTY
PYTHONPATH=. python -m options_lab.harvest.cli --indices   # nightly collector
```

Run the harvester daily. No free source serves expired contracts, so a day not
collected is gone permanently.

## Status

Not deployed. Before any capital moves, three things are unmeasured: real
broker margin (every return figure here is a formula reconstruction, and it
swings the answer by 7.5 percentage points), the true intraday bid-ask spread
(no free source carries quotes), and whether a broker force-closes a naked
short near expiry, which would break the hold-to-settlement rule the whole
result depends on.
