# R167: "smart" exits for R51 / R72, where the stop and target follow the market (12 Nov 2026)

## For Boss, in short

**No result yet: the study is built and tested, but it could not run.** The price data is not in this session's cloud
computer, and the three ways to get it are blocked:
- the Dhan token in the environment has expired (Dhan answers "invalid or expired");
- the Google Drive copy needs a Drive login this computer doesn't have;
- the GitHub copy of the data was never published.

**I made up no numbers.** Every test is logged as "proposed, not run".

**To unblock it (one step for you):** give the session a fresh Dhan access token, or start the next session in an
environment that has the Drive lake. The study then runs with five commands (listed under "How to run" below). The
first thing it does is check that it reproduces today's published results exactly:
- R72: 1,202 trades, Rs 1,71,366 (Apr - Oct 2026);
- R51: 1,670 trades, Rs 1,34,743.

If those don't match exactly, it stops.

### What I built (all decided before seeing any number)

Ten exit ideas, checked every minute of the trade:

| rule | what it does |
|---|---|
| SR | Sell just before the next resistance (support for puts), unless the index closes through it; then hold. The stop climbs to just under each broken level, or the latest higher low. |
| VOLX | Stop and target sized by the index's current volatility (ATR). A 3 x ATR trail, 4 x ATR target. |
| VOLP | Same idea on the option's own price swings: a 3 x the option's ATR trail. |
| FLOW | A volume spike against the trade, or OI building against the option, switches to a tight 3-minute trail. |
| MOM | Sell when the 1-minute Supertrend turns against the trade. |
| CTX | A different rule for each market mood (VIX high or low, trending or ranging day), chosen on old data only. |
| ML | A small model predicts, each minute, whether the move will continue. Sell when the chance drops low. |
| **G1** | **Your problem:** once the trade has been +Rs 1,500, it can no longer end in a loss (lock at breakeven). |
| **G2** | Once +Rs 1,500, keep at least +Rs 500, and never give back more than about Rs 1,000 of the best profit. |
| **G3** | Once +Rs 1,500, sell as soon as the index breaks its last 3-minute low (high for a put). |

Every idea is tested on R72 and R51, at 1, 2 and 3 lots: **102 tests**.

### The live-safety split (your rule: on live, automatic changes may only lower risk)

- **T versions (tighten-only):** today's -15% stop, ladder, 30-minute exit and 15:15 all stay. The new rule can only
  sell earlier. These are the only ones that could ever go live, and only after a shadow phase (logged only), behind a
  switch that is off by default. **All three giveback rules (G1-G3) are tighten-only.**
- **F versions (free):** the rule may also widen the stop or hold longer. Research and paper only.

Nothing changes in the app from R167.

### Your "+Rs 1.5k to 2.5k, then a Rs 2k-3k loss" problem

The test checks it directly with a synthetic NIFTY trade (1 lot) whose best open profit was +Rs 2,625, then falls
through the -15% stop within 30 minutes:
- today's exits: -Rs 2,354;
- G1: -Rs 19 (breakeven, less slippage);
- G2: +Rs 1,602.
- G3 gave -Rs 1,196 there, but that example is not fair to it: the made-up index barely moves while the made-up option
  swings, so the index trail has nothing to read.

That is only a worked example on made-up prices, not evidence. The real question is the trade-off: G1 / G2 will also
sell some trades that dip after +Rs 1,500 and would have gone on to big wins.

What earlier studies found on the same problem, so you know the odds:
- R162 (this morning): breakeven after +10% on the premium raised R72's win rate from 45.8% to 49.5% (1 lot, Apr - Oct
  2026, 1,203 trades) for Rs 6,757 less profit. That is 3.7 points, short of the 5-point bar.
- R153: locking +Rs 1,000 after +Rs 2,000 failed (it was tested only together with a -Rs 2,000 stop).
- The arms make money because the average win (R72 about Rs 1,748) is bigger than the average loss (about Rs 1,217).
  Rules that sell winners early usually raise the win rate and lower the profit.

G1-G3 are new in three ways: your +Rs 1,500 trigger, G2's trailing floor, and G3's index trail. My honest expectation is
a higher win rate, with the profit question open.

The first run will also count how often your case happens: how many trades reached +Rs 1,500, and how many of them
ended at Rs 0 or worse, and at -Rs 2,000 or worse. It does this for 1, 2 and 3 lots.

## Method (honest notes)

- **Pre-registered** in `PREREG.md` before any code touched data. Amendment A (your giveback message, plus one
  clarification of SR's target) was committed before any number existed either.
- **Signal source: the index.** The arms' Donchian entries are unchanged. Exits read the index (levels, ATR, Supertrend,
  regime), the option's own premium (VOLP's stop, the "in profit" checks, G1-G3's rupee levels) and the held option's
  volume and OI (FLOW). Every rule decides at a minute's close using only data up to that minute, and sells at the next
  minute's open. Locks are solved from the trade's real fill and charges.
- **Engine and costs:** R152's (R106 / R64 simulator, R17 slippage, dated Zerodha charges on the whole quantity, R106's
  walk re-run for every rule).
- **Periods:**
  - design: 20 Nov 2024 - 31 Mar 2026, walk-forward window Apr 2025 - Mar 2026;
  - final: 1 Apr - 13 Oct 2026, read once. Many earlier studies have already read this period (R51 - R166), so it is weak
    evidence.
  - CTX and ML are trained only on design days before each window they score. Their design-period numbers are
    in-sample and labelled so.
- **Gates (your success test):**
  - win rate up >= 5 points AND net not lower than today's, in walk-forward and final;
  - net > 0 in every period;
  - beats random-exit twins (500 per test, p = the larger of the net and win-rate p's);
  - BH q <= 0.10 with **m = 2,664** (R167's 102 plus 2,562 earlier tests, including today's siblings R161-R166, at p = 1);
  - >= 40 final trades.
  - Worst day and max drawdown are reported (lower is a plus).
- **Raw p-values for the joint BH with R161-R166:** `results/raw_pvalues.csv`, written by `r167study.py decide`. It does
  not exist yet, because nothing has run.

## How to run (once the lake is at `$IRAALGO_LAKE`)

```bash
R51_CLEAN=1 python research/hunt/r51/r51data.py build
python research/hunt/r167_donchian_smart_exits/r167data.py build
python research/hunt/r167_donchian_smart_exits/r167study.py design     # asserts B0 parity first
python research/hunt/r167_donchian_smart_exits/r167study.py final      # the one final read
python research/hunt/r167_donchian_smart_exits/r167study.py decide     # BH, gates, raw_pvalues.csv
python research/hunt/r167_donchian_smart_exits/r167study.py picker     # second look on R151's picks
python research/hunt/r167_donchian_smart_exits/r167report.py           # results/tables.md
python research/hunt/r167_donchian_smart_exits/r167entries.py          # entries get the real verdicts
```

## Tests

`python -m pytest research/hunt/r167_donchian_smart_exits -q`: 44 tests. They cover:
- every rule's edge cases: no levels, a breakout, ATR warm-up, missing prints, missing OI, puts, the square-off row;
- the look-ahead guard: every measurement and every rule's decision at minute m is unchanged when all data after m is
  cut, checked at 6 and 5 cut points on synthetic days;
- the engine on synthetic days through R106's real simulator: every T version's exit is never later than today's, on 4
  random days at 1 and 3 lots;
- the giveback example above.
