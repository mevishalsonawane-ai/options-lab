# R145: a neural network over the top-100 crypto coins. What patterns are real?

3 Nov 2026. Research only: nothing in the app changes and nothing trades. Boss: *"Create a neural network with the same
data, and find the patterns of the data"* (the 1-minute candles of the top-100 coins in the Drive crypto lake).

## The answer in short

**The network finds real patterns in how much coins will move, not in which way. Nothing is tradable.**

1. **Real (confirmed on the unseen Apr-Oct 2026 holdout):**
   - **Volatility.** Next-hour volatility is very predictable: R² 0.78 on 444,243 unseen coin-hours (design 0.77 on
     1,072,642). It comes almost entirely from the coin's own recent volatility (60 and 240 minutes), plus the IST hour.
   - **Big moves.** Whether a coin moves more than 2× its usual size in the next 15-60 minutes: AUC 0.70 (design 0.71).
   - **Size of the move.** R² 0.05 at 60 minutes (design 0.07).
   - **Which coins beat the others over the next 15-240 minutes.** Rank correlation (IC) 0.053 (design 0.057), in all
     24 IST hours and in every market state. It is a short-term reversal: coins that just ran up against the rest tend
     to lag over the next hours, and the reverse.
   - **BTC leads alts by one minute, and only one.** BTC's last minute correlates 0.016 with an alt's next minute
     (same minute: 0.59). Nothing after 2 minutes. Small coins lag most (JST, KAIA, BSV, KAS: about 0.04-0.05); ETH and
     SOL do not lag at all.
   - **Coins move in stable groups:** a large-cap group with BTC/ETH, a DeFi group (AAVE, UNI, LINK, CRV, LDO, ARB, OP,
     PENDLE, ENS), a meme group (DOGE, PEPE, SHIB, BONK, FLOKI; the tightest), and an L1/AI group (SOL, AVAX, NEAR,
     APT, SEI, INJ, FET...). The groups found in Jan 2024 - Feb 2025 matched Feb 2025 - Mar 2026 (adjusted Rand
     index 0.19, p 0.001) and matched again on the holdout (0.20, p 0.001).
2. **Noise, or too small to matter:**
   - **Direction.** The network ranks up-hours from down-hours a little better than a coin (AUC 0.52-0.53, holdout and
     design), but its probabilities are no better than "always 48%" on the holdout (log-loss improvement q 0.08 at 60
     minutes, 0.72 at 15 minutes). The fifth time a network says this: R16, B1, R41, R123, now R145.
   - The **"strong" evening hours** for direction (17:00-19:59 IST in design, AUC up to 0.58) fell to noise on the
     holdout (q 0.42-0.59).
   - **"Does BTC's last 5 minutes predict the alts' next 5?"** Not on the holdout (q 0.27).
3. **The network is not better than simpler models.** On volatility and big moves it ties gradient-boosted trees. On
   ranking coins it ties trees and a plain linear model. Of the 14 "network beats trees" claims that passed design,
   only 4 held on the holdout, all tiny.
4. **Nothing can be traded.** All 40 pre-registered trading variants lost after real costs in design (−Rs 122 to
   −Rs 176 a trade at Rs 1 lakh), with every stop and profit-lock pair. The network's picks beat random entries by
   about Rs 25-60 a trade, but a round trip costs Rs 120-200 (0.12-0.20%). No trading rule was taken to the holdout.

**What could be used later (a NOTE, not a proposal to trade):** the volatility and big-move forecasts are good enough to
**size or skip** trades by risk. For example, smaller size or a wider stop when the next hour is forecast to be wild. That is
a risk tool, not an edge, and like everything in crypto it would need Boss's say-so, a stop and a profit lock.

## What was built

- **Pre-registration:** `research/brain/crypto/PREREG_R145_NN.md`.
  - Commit `342a2dd`: written before any data, model or result.
  - Amendment 1 (`6610473`): the holdout procedure, fixed after only fold 0 had been seen.
  - Amendment 2 (`7794913`): frozen-price rows left out of every score (see "Deviations").
- **Code** (`research/brain/crypto/r145/`):
  - `data.py`: lake / archive months → 5-minute bars and lead-lag statistics. Raw minutes are never kept.
  - `panel.py`: 51 point-in-time features, 15 labels, the sequence channels.
  - `guard.py`: the look-ahead guard.
  - `models.py`: the networks and baselines.
  - `run.py`: the walk-forward.
  - `stats.py`, `patterns.py`, `trade.py`, `evaluate.py`: tests, patterns, trading test and evaluation.
- **Tests:** 28 tests in `research/brain/tests/test_r145_nn.py`.
- **Results:** `research/brain/crypto/results/`.
  - `r145_design.json`: 254 tests, scores, patterns, importance.
  - `r145_trade_design.json`: 40 trading variants.
  - `r145_holdout.json`: the one holdout read.
  - `r145_HOLDOUT_READ`: read once, 3 Nov 2026 19:05 UTC.
- **Big files** (5-minute bars, rows, predictions, model weights): `gdrive:IraAlgo-data-crypto/derived/r145/`, never in git.

**Data.**
- 96 Binance USD-M perpetuals of the top-100 coins (manifest); 95 have design data. GRAMUSDT (TON's new name) starts
  in Jul 2026.
- 1-minute klines and funding: Nov 2025 on from the Drive lake, earlier months from data.binance.vision (the lake's
  own source).
- 2,141 design coin-months and 670 holdout coin-months, turned into 5-minute bars.

**Sample sizes.**
- Design: 1,483,142 hourly decision rows (8 Jan 2024 - 31 Mar 2026).
- Scored out of sample in six quarterly walk-forward folds (Oct 2024 - Mar 2026): 1,072,642 rows over 79 weeks, after
  dropping 7,893 frozen-price rows.
- Holdout (1 Apr - 11 Oct 2026, read once): 444,243 rows, 97 coins, 28 weeks, 190 frozen-price rows dropped.

**Signal source of every rule:** the coin's own perpetual price (the underlying) and market context (BTC/ETH moves,
breadth, dispersion, funding, IST hour). Traded as the perpetual itself; no option-price signals.

**Models** (fixed in advance, no tuning):
- N1, a multi-task MLP: 15 heads, mean of 3 seeds.
- N2, a GRU over each coin's last 4 hours of 5-minute bars, joined with the MLP.
- Baselines: base rate / volatility-history line (naive), ridge/logistic (linear), LightGBM (trees), and the MLP
  refitted on shuffled labels (the random twin).

## Results

### How well each model forecasts (out of sample)

| target (60 minutes) | metric | naive | linear | trees | **MLP (N1)** | **GRU (N2)** | shuffled twin |
|---|---|---|---|---|---|---|---|
| volatility, design | R² | 0.723 | 0.679 | 0.773 | 0.768 | 0.771 | −0.007 |
| volatility, **holdout** | R² | 0.740 | 0.636 | **0.784** | 0.776 | 0.778 | −0.030 |
| big move (> 2× usual), design | AUC | 0.50 | 0.695 | 0.704 | 0.706 | 0.710 | 0.487 |
| big move, **holdout** | AUC | 0.50 | 0.676 | **0.701** | 0.698 | 0.700 | 0.456 |
| size of move, design | R² | 0.000 | 0.053 | 0.064 | 0.070 | 0.074 | −0.001 |
| size of move, **holdout** | R² | 0.000 | 0.036 | 0.050 | 0.054 | **0.056** | −0.001 |
| which coins outperform, design | IC | 0 | 0.054 | 0.054 | 0.057 | 0.056 | −0.001 |
| which coins outperform, **holdout** | IC | 0 | 0.050 | 0.049 | **0.054** | 0.053 | −0.006 |
| direction (up or down), design | AUC | 0.506 | 0.528 | 0.521 | 0.531 | 0.530 | 0.502 |
| direction, **holdout** | AUC | 0.500 | 0.524 | 0.516 | **0.524** | 0.524 | 0.489 |

15 and 240 minutes look the same (all in the JSON). Example: holdout volatility R² is 0.70 at 15 minutes and 0.78 at
240; big move AUC 0.70 / 0.65; IC 0.053 / 0.053; direction AUC 0.52 / 0.52.

### The 254 tests (one Benjamini–Hochberg family, q 0.05) and the holdout

| family | what | design "real" | confirmed on holdout |
|---|---|---|---|
| F1 / F2 | MLP / GRU beat the naive baseline | 14 / 15 each | 12 each (all but direction) |
| F3 / F4 | MLP / GRU beat trees | 6 / 15, 8 / 15 | 1 and 3 (size of move, big move at 15 min; tiny) |
| F5 | MLP beats linear | 9 / 15 | 9 (size, volatility, big move) |
| F6 | MLP beats its shuffled-label twin | 14 / 15 | 12 (all but direction) |
| P1 | by IST hour (direction, rank) | 27 / 48 | 23: rank in 23 of 24 hours; direction in no hour |
| P2 | by coin (direction AUC > 0.5) | 80 / 95 | 70 (AUC 0.51-0.55; too small to pay costs) |
| P3 | by market state | 10 / 14 | 8 (rank in all 7 states; direction only on BTC-up days, q 0.049) |
| P4 | BTC lead-lag | 2 / 6 | 1 (1 minute) |
| P5 | stable coin groups | 1 / 1 | 1 |
| **all** | | **185 / 254** | **152 / 185** |

A design "real" needed q < 0.05, the right sign in at least 4 of 6 folds and a minimum size. A holdout "confirmed" needed
the same sign and q < 0.05 across the 185 holdout tests.

### What the network actually learned (permutation importance, last two design folds)

- **Volatility / big moves / size:**
  - the coin's own recent volatility carries nearly all of it (trees: 60-minute 40%, 240-minute 28%);
  - then the IST hour (a third of the big-move skill), the coin's own recent returns, and market breadth/dispersion.
- **Which coins outperform:** the coin's own recent returns (43%) and its cross-sectional ranks (27%; trees lean on its
  volatility rank). Then breadth/dispersion (13%) and its own volatility (10%). Funding and taker flow add little.
- **Direction:** what little there is comes from BTC's last 15-60 minutes, breadth and ETH's last 4 hours. That is the
  whole market's next hour, not a coin's. In the last two design folds the network's direction forecast had no skill
  over the base rate (log-loss), which the holdout then confirmed.

### Hours (IST), coins and market states

- **When crypto moves:** 18:00-21:59 IST is the busiest (average 60-minute move 90-101 bp, the US open and US data).
  15:00-16:59 and 04:00 IST are the quietest (64-67 bp).
- Boss's 00:00-09:00 IST window is mid-range (64-84 bp).
- The rank pattern holds in every hour, including the window (holdout IC 0.02-0.09 per hour; only 11:00 IST misses).
- **Coins:**
  - volatility is predictable for every coin (R² within a coin 0.48 for SKY to 0.78 for DASH);
  - direction AUC per coin is 0.50-0.55 everywhere, with 10 coins (BNB, XRP, DOGE, ADA, SHIB...) not confirmed;
  - no coin is a standout.
- **Market states:** ranking works in calm, normal and wild markets, on BTC-up and BTC-down days, weekends and weekdays.

### Trading test (design only; nothing passed, so nothing went to the holdout)

TD-h: trade the network's top and bottom 5% direction calls. TX-h: long the top-3 and short the bottom-3 predicted ranks.
Every trade has a stop (1× or 2× the coin's usual move) and a profit lock (trail: exit below half the peak once +1×;
step: break-even at +1×, +1× at +2×). Costs: 0.05% taker a side, slippage 1/2/5 bp a side, funding. Rs 1 lakh notional a
trade, no tax.

| best variant of each rule (design walk-forward, Oct 2024 - Mar 2026) | trades | win | net / trade | 2× slippage | random twins | months positive |
|---|---|---|---|---|---|---|
| TD-15, Boss's window, stop 2×, trail | 7,538 | 43% | −Rs 130 | −Rs 225 | −Rs 191 | 0 of 18 |
| TD-60, all hours, stop 2×, step | 18,013 | 42% | −Rs 133 | −Rs 230 | −Rs 190 | 1 of 18 |
| TX-60, Boss's window, stop 2×, trail | 26,226 | 46% | −Rs 157 | −Rs 243 | −Rs 185 | 0 of 18 |
| TX-240, all hours, stop 2×, step | 37,657 | 45% | −Rs 123 | −Rs 209 | −Rs 172 | 1 of 18 |

All 40 variants lost (`r145_trade_design.json`). The 2× stop with either lock lost least; the 1× stop was cut short
more often. India's 30% VDA tax with no loss set-off would make every number worse (NN_CRYPTO).

## Deviations and caveats (honest notes)

- **Test count.** The pre-registration said "240 tests"; its own table adds up to 255. 254 were run (GRAMUSDT has no
  design rows). All 254 are in one BH family.
- **Amendment 2, frozen prices.** A dry run of the design evaluation (on partial predictions) showed the naive
  volatility line beating every model. The cause was 7,894 rows on a frozen price: LITUSDT Oct 2024 - Dec 2025
  (apparently a dead contract whose symbol was later reused), PUMPUSDT before real trading, and one exchange-wide frozen
  hour. Those rows were left out of every score for every model, as R123's rule "zero-volume minutes are never priced".
  With them in, design volatility R² reads 0.92 naive vs 0.86 for the networks (`scores_with_stale_rows`). The change
  touched no direction or rank result.
- **Fold bar for late-listed coins.** "Right sign in at least 4 of 6 folds" was applied as 4/6 of the folds a coin
  actually has (e.g. 2 of 3) for coins listed during the design period. The pre-registration did not say how to
  treat them; this was decided in the code before the final run and affects only per-coin (P2) cells.
- **Fixed before any fit:** funding was first looked up 5 minutes early (at the bar's open). A test caught it.
- **Compute.** The walk-forward ran in chunks because a background job lasts at most 2 hours; finished model/fold
  outputs are skipped on resume. The GRU saves and resumes per epoch. A resumed run gives exactly the same losses as
  one uninterrupted run (tested, and seen on folds 3-4).
- **Point in time, checked three ways:**
  - the guard on synthetic panels, plus a planted leak that it caught;
  - the guard on real data: 8 coins, 7 cuts × 2 modes, 51 features: PASS;
  - the holdout build re-made all 1,483,142 design rows with identical features and labels.
- **Holdout status.** The crypto brain's C1/C2 had never read Apr-Oct 2026. NN_CRYPTO, STRAD_CRYPTO and RC2 had read it
  for BTC and ETH (other ideas), so for those two coins it was partly seen. For the other 95 coins and for every
  cross-sectional question it was unread. It is now read (`r145_HOLDOUT_READ`).
- **Survivorship.** The coins are the top 100 as of Oct 2026. Coins that dropped out earlier are missing. The rank
  target is demeaned every hour and horizons are 15-240 minutes, which limits but does not remove this.
- **Assumptions:**
  - slippage is assumed (no bid/ask in the archive);
  - funding is charged on the 00/08/16 UTC schedule (coins on 4-hour funding are undercounted slightly);
  - open interest and long/short data are not used (only a few coins have them).
- **Direction "per coin" (P2)** tests AUC > 0.5 (ranking), a looser test than the log-loss test, which failed. Those
  70 coins rank up-hours slightly better than chance, but the edge is far below costs (the trading test).

## Experiment log

- `r145-crypto-nn`: the model tests (F1-F6).
- `r145-patterns`: P1-P5.
- `r145-trade-td` and `r145-trade-tx`: the trading rules.

All are in `research/experiments/entries/`.

## Re-run

```bash
python -m research.brain.crypto.r145.data bars --manifest <manifest.csv> --from 2023-12 --to 2026-03
python -m research.brain.crypto.r145.data funding --manifest <manifest.csv> --from 2023-12 --to 2026-03
python -m research.brain.crypto.r145.run build --manifest <manifest.csv>
python -m research.brain.crypto.r145.run fit                      # resumable; --folds / --models to split it
python -m research.brain.crypto.r145.evaluate design && python -m research.brain.crypto.r145.evaluate trade
python -m research.brain.crypto.r145.guard                       # look-ahead guard on the real panel
# the holdout was read once (r145_HOLDOUT_READ); `evaluate holdout` refuses a second read
```
