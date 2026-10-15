# R59: audit of the "S2 Mean Reversion Dip Buy" on stock options (15 Oct 2026)

**Verdict: the claimed +Rs 1,695 a trade is not real.** Their number reproduces on the lake's *rolling* ATM series, but
**100% of its profit comes from that series switching to another strike or rolling to the next expiry**. Nobody
holding an actual option gets those jumps. On contracts you could really hold, with one real lot, real fills and real
charges, **0 of 84 pre-registered cells pass the gates**. The pooled held-contract version makes Rs +2 a trade over 78
final-test trades without a stop, and loses Rs 57 a trade with the 25% stop. Nothing goes to paper. Nothing in the app
changes.

**Signal source:** their rule reads its signal from the **option's own price chart** (the ATM premium falling 20% in a
15-minute bar). Under CLAUDE.md that is **not the default**. It is labelled and reported separately here (family A),
and it could only ever go on paper with Boss's explicit yes. The default-compatible comparison (family B) reads its
signal from the **stock**: a 1% move in 15 minutes against the option. It fails too.

Pre-registered in `hunt/r59/PREREG.md` (commit 8db2c18, pushed before any P&L). The design results and the per-stock
spread estimates were frozen in commit 68e9ef6 before the one final read. Code, tests and results are in `hunt/r59/`
(`results/report.md` has every cell). Experiment entries: `experiments/entries/r59-*.yaml` (5 files).

## The claim (another AI, shared by Boss on 15 Oct)

- **Universe:** RELIANCE, HDFCBANK, ICICIBANK, INFY, TCS, SBIN, AXISBANK, KOTAKBANK, ITC, TMPV (Tata Motors).
- **Data:** our Dhan lake `stock_options_rolling_1m`, 4 Nov 2024 - 14 Oct 2026, ATM only, monthly expiry, 15-minute
  bars.
- **Rule:** buy the ATM CE (and separately PE) at a 15-minute close when the premium fell 20% or more since the
  previous close and is at least Rs 5; sell 4 bars (60 minutes) later. Rs 10,000 notional (fractional contracts,
  no lot size), Rs 95 a trade, no stop, no slippage, overlapping trades allowed.
- **Claimed:** 6,945 trades, 73.4% win, +Rs 1,695 a trade, +Rs 1.177 crore. Every stock and side 66-80% win. TCS about
  +Rs 3,900 a trade on Rs 10,000.

## Step 1: their number, reproduced

Their code on the lake's rolling ATM rows, 15-minute closes (last close in each 15-minute bin), `pct_change` over the
whole series (so it also compares a day's first bar with the previous day's last), exit 4 rows later:

| | their claim | our reproduction |
|---|---|---|
| trades | 6,945 | 6,689 |
| win rate | 73.4% | 73.5% |
| per trade | +Rs 1,695 | +Rs 1,836 (median +Rs 1,024) |
| total | +Rs 1.177 crore | +Rs 1.228 crore |
| TCS | about +Rs 3,900 a trade | CE +Rs 3,940, PE +Rs 3,577 |
| per stock and side | 66-80% win | 66-80% win (TCS CE 65.8%, ITC PE 80.0%) |

**It reproduces closely.** By year: 2024 (from 4 Nov) 735 trades, +Rs 2,068 a trade; 2025 4,013 trades, +Rs 1,723;
2026 (to 14 Oct) 1,941 trades, +Rs 1,982. The small differences in the count probably come from how empty bins and
duplicate minutes are handled; they do not change anything below.

## Step 2: where the profit comes from

The lake's option file holds, for each minute, **whichever contract was ATM at that minute**. So the "premium" series
jumps whenever the stock crosses halfway to the next strike (a different contract), and again on the day after expiry
(the next month's contract, with a month more time value).

**How the fake dip happens (CE example):** the stock rises past the halfway point, the ATM call becomes the next higher
strike, which is much cheaper. The series "falls 20%+" though the call you held went **up**. If the stock then dips
back, the series switches back to the lower, dearer strike, and the "premium" jumps back up. A held option gets none of
this.

Each of their 6,689 trades, classified by what the series did between the bars:

| category | trades | share of trades | profit | share of profit | Rs a trade | win |
|---|---|---|---|---|---|---|
| **one contract throughout** (no switch, no roll) | 701 | 10.5% | -Rs 12,734 | **-0.1%** | **-Rs 18** | 48.8% |
| the "drop" was a strike switch | 5,607 | 83.8% | +Rs 86.1 lakh | 70.1% | +Rs 1,535 | 76.8% |
| the "recovery" was a strike switch | 2,805 | 41.9% | +Rs 70.4 lakh | 57.3% | +Rs 2,510 | 86.6% |
| the exit was priced on the **next expiry** | 42 | 0.6% | +Rs 33.0 lakh | 26.8% | **+Rs 78,485** | 100% |
| any switch or roll | 5,988 | **89.5%** | +Rs 1.230 crore | **100.1%** | +Rs 2,053 | 76.4% |

(The rows overlap: a trade can switch on the drop and on the recovery.)

- **100.1% of the profit is from switches and rolls.** The 701 trades that stayed on one contract lost Rs 18 a trade,
  even with their Rs 95 cost and no spread.
- **Just 42 trades made Rs 33 lakh:** their entry is the expiring contract (median Rs 6.5) and their exit, 4 bars
  later, is the next month's contract (median Rs 55.6), so Rs 10,000 of "contracts" becomes Rs 80,000 (median +Rs
  72,981 a trade).
- Every one of the 20 series shows the same thing: switches/rolls are 91-103% of each series' profit.
- **Their own trades valued on the contract actually held at the signal bar** (a diagnostic, Rs 10,000, their Rs 95,
  no spread): 6,509 trades, 43.6% win, **Rs -3 a trade**.
- It probably also explains their own baselines (not re-run here): "buy and hold 60 minutes" losing on all 20 series
  and "buy after a 20% jump" losing on 19 of 20. A jump on the rolling series is usually a switch to a dearer strike,
  which the next switch takes back.

This is the same artefact R45 (NIFTY premium scalp) and R46 amendment 3 (BTC 25% dip buy, 99.5% of profit from
switches/rolls) found.

## Step 3: the honest test

Pre-registered before any P&L (`hunt/r59/PREREG.md`). The same idea, but tradable:

- **Held contracts.** A contract is (expiry, strike, side), followed across the ATM-1, ATM and ATM+1 rows. Expiry dates
  and **lot sizes come from the weekly NSE F&O bhavcopies** (`XpryDt`, `NewBrdLotQty`), per stock, expiry and date.
- **Family A (option chart, NON-DEFAULT):** at a 15-minute close (from the 2nd bar), the ATM contract **whose own
  price** fell 20%+ since the previous bar of the same session, and closes at Rs 5+ (its last trade in the bar's last
  5 minutes, so not stale).
- **Family B (stock, default):** the stock's own 15-minute close (`equity_1m`) fell 1.0%+ (buy the ATM CE) or rose
  1.0%+ (buy the ATM PE) in the bar.
- **Fills:** buy at the open of the contract's next 1-minute row (within 5 minutes), sell at the open 60 minutes later
  (or at 15:25) on the **same** contract. R47's stray-print guard on both.
- **Spread, per stock:** 5 bps plus the larger of R17's 1/2/4 ticks (by 5-minute volume) and a Roll (1984) half-spread
  estimated per stock and side on design sessions only: 0.26% (RELIANCE CE) to 0.50% (ITC PE) of the premium a side.
  Stops pay double.
- **Charges:** the NSE rates in force on the trade date (`Costs("dated")`), both orders. 1x and 2x costs.
- **Size:** exactly 1 lot. **Variants:** NS (no stop, their exit; comparison only) and S25 (a premium stop 25% below
  the fill, checked every minute, gaps fill at the open), because Boss requires stops.
- **Cleaning:** rows of another underlying (spot more than 3% off the stock; R47 pattern; 24-2,962 rows per stock in
  design), `spikes.py` bad ticks (212-490 rows per stock in design), short special sessions, 5 sessions either side of a corporate action
  (HDFCBANK bonus 26 Aug 2025, KOTAKBANK split 14 Jan 2026, Tata Motors demerger 14 Oct 2025, all found from the data).
- **Expiry:** stock options are physically settled, so no trade on the expiry session or the 2 sessions before it.
- **No overlap:** one position per stock and side.
- **Splits:** design 20 Nov 2024 - 31 Mar 2026; final test 1 Apr - 12 Oct 2026 (equity minutes end on 12 Oct), read
  once.
- **Gates (per cell, final test, 1x costs):** net > 0; p < 0.05 against 500 sets of random-entry twins (each trade
  re-entered at a random bar of the same session, same stock, side and rules); Benjamini-Hochberg q <= 0.10 across all
  84 cells; 40+ trades; net without the top 5 trades > 0; design net > 0.

### Results, pooled (all 10 stocks, both sides)

| cell | design: trades, win, Rs/trade, net | final: trades, win, Rs/trade, net (2x costs) | without top 5 | p, q | capital per trade (median / max) | gate |
|---|---|---|---|---|---|---|
| A NS (option chart, no stop) | 381, 46.7%, -32, -Rs 12,174 | 78, 44.9%, **+2**, +Rs 143 (-Rs 12,151) | -Rs 25,890 | 0.030, 0.34 | Rs 8,730 / 20,431 | fail |
| A S25 (option chart, 25% stop) | 394, 46.2%, -29, -Rs 11,400 | 80, 45.0%, **-57**, -Rs 4,546 (-Rs 18,409) | -Rs 30,207 | 0.038, 0.34 | Rs 8,730 / 20,431 | fail |
| B NS (stock signal, no stop) | 131, 45.8%, -156, -Rs 20,427 | 75, 36.0%, **-252**, -Rs 18,910 (-Rs 33,856) | -Rs 45,853 | 0.028, 0.34 | Rs 12,820 / 27,519 | fail |
| B S25 (stock signal, 25% stop) | 134, 45.5%, -176, -Rs 23,528 | 78, 35.9%, **-380**, -Rs 29,644 (-Rs 45,238) | -Rs 56,586 | 0.086, 0.48 | Rs 12,868 / 27,519 | fail |

- Held-contract 20% drops are **rare**: 381 design trades in 16 months across 20 series, against 5,197 rolling
  "signals" of their rule in the same months. Most of their signals were switches.
- The pooled cells beat their random twins (p about 0.03), but only because a random ATM option held for an hour loses
  more (twins average about -Rs 350 a trade in family A and -Rs 620 in B: time decay plus the spread). Beating a losing
  baseline is not making money.
- The one "near-pass" is AXISBANK CE (A: 5 final trades, +Rs 16,561, p 0.002-0.006; B: 4 trades, +Rs 15,609, p 0.002,
  q 0.05). Five trades on one stock carry the whole number, far below the 40-trade gate, and AXISBANK PE lost in design
  in family A. It is noise, not an edge.

### Results per stock and side (final test, 1x costs; full table with design, 2x costs and capital in `results/report.md`)

Trades, win %, net Rs, p (q) per cell. No per-stock cell has 40 final trades (most have 0-13).

| stock | A NS CE | A NS PE | A S25 CE | A S25 PE | B NS CE | B NS PE | B S25 CE | B S25 PE |
|---|---|---|---|---|---|---|---|---|
| RELIANCE | 0 | 8, 50%, -853, 0.20 (0.64) | 0 | 8, 50%, -709, 0.17 (0.58) | 1, 0%, -1,151 | 2, 50%, +85 | 1, 0%, -1,151 | 2, 50%, +85 |
| HDFCBANK | 2, 0%, -449 | 3, 67%, +444, 0.12 (0.49) | 2, 0%, -449 | 3, 67%, +444 | 2, 0%, -3,268 | 5, 20%, -4,076 | 2, 0%, -3,268 | 5, 20%, -4,839 |
| ICICIBANK | 2, 50%, -851 | 8, 50%, -2,777 | 2, 50%, -851 | 8, 50%, -2,777 | 1, 100%, +1,122 | 1, 0%, -1,048 | 1, 100%, +1,122 | 1, 0%, -1,048 |
| INFY | 3, 33%, -1,023 | 1, 100%, +430 | 3, 33%, -1,023 | 1, 100%, +430 | 1, 0%, -1,423 | 5, 60%, +898, 0.11 (0.48) | 1, 0%, -1,423 | 5, 60%, +898 |
| TCS | 7, 29%, -2,410 | 8, 63%, +2,702, 0.03 (0.34) | 7, 29%, -2,410 | 8, 50%, -3,137 | 9, 44%, -2,141 | 9, 44%, +1,063, 0.07 (0.48) | 10, 50%, -377 | 9, 33%, -4,753 |
| SBIN | 3, 33%, +95 | 5, 40%, -586 | 3, 33%, +95 | 5, 40%, -586 | 4, 0%, -7,095 | 3, 33%, -4,571 | 5, 0%, -9,507 | 3, 33%, -5,297 |
| AXISBANK | 5, 80%, +16,561, 0.006 (0.12) | 11, 45%, +2,757, 0.22 | 5, 80%, +16,561, 0.002 (0.05) | 13, 54%, +5,487, 0.14 | 4, 100%, +15,609, 0.002 (0.05) | 4, 75%, +7,763, 0.04 (0.34) | 4, 100%, +15,609, 0.002 (0.05) | 4, 75%, +7,763, 0.04 (0.34) |
| KOTAKBANK | 1, 0%, -4,706 | 3, 0%, -7,764 | 1, 0%, -5,385 | 3, 0%, -7,764 | 3, 33%, -5,761 | 3, 33%, -2,877 | 3, 33%, -6,440 | 3, 33%, -2,877 |
| ITC | 0 | 0 | 0 | 0 | 1, 100%, +1,366 | 3, 0%, -2,267 | 1, 100%, +1,366 | 3, 0%, -2,267 |
| TMPV | 1, 0%, -1,191 | 7, 43%, -237 | 1, 0%, -1,191 | 7, 43%, -1,283 | 3, 0%, -7,341 | 11, 18%, -3,798 | 3, 0%, -8,347 | 12, 25%, -4,894 |

Every cell fails: too few trades everywhere, and where a cell is positive its design half lost, or one or two trades
carry it. TCS, the claim's star (+Rs 3,900 a trade), loses in design in every family and side.

### Capital, lots and costs

- **Rs 10,000 notional is not a tradable size.** The smallest order is 1 lot. Lots in this window (bhavcopy): RELIANCE
  500, HDFCBANK 550, then 1,100 after the bonus, then 650 from the Jul 2026 contracts, ICICIBANK 700, INFY 400, TCS 175
  then 225, SBIN 750, AXISBANK 625, KOTAKBANK 400 then 2,000 after the split, ITC 1,600 then 1,725, TMPV 550, 800, then
  1,600. The contract value (spot x lot) is about Rs 4-11 lakh (less for Tata Motors after the demerger).
- **One lot of the option bought** cost a median Rs 8,730 in family A and Rs 12,820 in B (per-cell medians Rs 3,980-
  22,729, largest Rs 27,519). The premium after a 20% drop is cheap, so A's lots are smaller.
- **Costs:** a stock-option round trip here costs about 0.6-1.1% of the premium in spread and bps, plus Rs 50-160 of
  charges a lot (Rs 51-71 on a Rs 2,000-10,000 lot). That is roughly Rs 120-170 a trade on a Rs 9,000 lot, against an
  average gross edge of about zero.
  The flat Rs 95 with no spread understates it, but costs are not why the claim fails. The switches are.

## Honest limits

- The final test (1 Apr - 12 Oct 2026) is a fresh read for this idea, but not pristine: other studies have looked at
  these months for other ideas.
- Stock-option minutes match NSE bhavcopy highs and lows only about 68% of the time (`data/VALIDATION.md` section 3).
  Bad ticks are filtered, but stock-option prices are noisier than index ones.
- The lake has only ATM-1..ATM+1 for these stocks. A held contract that moves two strikes away loses its rows. That
  rarely matters in 60 minutes; when the exit row is missing, the last close with a 4-tick spread is used (1-4 trades
  of each pooled final cell, 2-11 in design).
- The Roll spread is an estimate from minute closes; Dhan has no historical bid/ask. The 2x-cost columns show the
  results do not hinge on it.
- B's 1% threshold was fixed in the PREREG, not tuned. Other thresholds were not tried.
- Six cells (A, RELIANCE CE and ITC CE / PE, with and without the stop) had no final trades and so no p-value; the BH
  step ran over the 78 cells that had one. Counting the six as p = 1 would only raise every q.
- Two cleaning rules use knowledge about a whole day: a date is a session only with 300+ stock minutes (short special
  sessions are known from the exchange calendar), and the 5 sessions before a corporate action are skipped (bonus,
  split and demerger dates are announced weeks ahead). Neither reads prices after a signal.

## What changes

Nothing in the app. Their rule should not be used anywhere: on paper it would only "work" on a price series nobody can
trade. Lesson for any future outside claim on the lake's option files: **check whether a trade's entry and exit are the
same contract** (strike + expiry + side). R45, R46 and R59 all found the same artefact.
