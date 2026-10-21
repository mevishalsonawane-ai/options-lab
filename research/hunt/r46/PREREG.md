# R46 pre-registration: another AI's "Option 1" BTC call strategy, re-tested honestly

Written 15 Oct 2026. Committed and pushed **before any profit or loss of any variant was computed**. Before this commit
only data inventories were read: file lists and date coverage, the count of signals (4,309 call crosses in design
before the daily cap), the run time and fillable share of one sample expiry day (15 Jan 2025). After this commit,
sections 1-8 do not change. A change needs a dated amendment at the end that says what was known when it was made.

Boss (15 Oct 2026): "test it and give me the back results, test results." **Research only. Nothing in the app trades
crypto, and nothing here places an order.**

## 1. The claim being tested

Another AI proposed "Option 1":

- Instrument: BTC ATM CALL options on Delta Exchange India. Signal: 5-minute BTC spot.
- Entry: EMA(8) crosses above EMA(21) AND Wilder ADX(14) > 15 AND a neural-net probability > 0.65. The net is a PyTorch
  MLP 12 -> 64 -> 32 -> 1 that predicts the 60-minute call direction. **Its 12 features and weights are not available.**
- Buy the ATM call at the next 5-minute bar's open. Exits: 60-minute time exit and a 50% stop on premium. At most 2
  trades a day. Long calls only. "1000 contracts ~ $300/trade".
- Claimed result: Sep-Oct 2026, 44 days, 21 trades, 85.7% wins, +$122.30 a trade, +$2,568 before tax, +$1,798
  "after 30% tax".

Suspected flaws, to be checked:

- the sample is tiny (21 trades);
- the net was probably trained on the same window;
- tax was applied to the net total, not to each winning trade (India: 30% + cess on every winning trade, no loss
  set-off, plus 1% TDS);
- no Delta fees or GST;
- Delta's thin option book: F1 crypto found that a fill one minute later turned two "winning" rules into losers;
- no profit lock, which breaks Boss's crypto rule.

## 2. Rules this study obeys (CLAUDE.md)

- **Signal on the underlying, trade the option.** Every signal is computed on **BTC spot** (Binance BTCUSDT 1-minute
  bars, built into 5-minute bars). That is the underlying and the default. The trade buys a Delta India BTC option
  chosen by the strike rule. Stops, locks and the time exit act on the option premium. The futures baseline trades the
  BTC perpetual on the same spot signal. **No rule reads an option's own chart.**
- **Crypto rule:** every trade has a stop-loss **and** a profit lock (section 5). There is no "no lock" version. The
  code (`exits.Exit`) refuses to build an exit without both. The other AI's own exit (stop + time, no lock) is **not**
  run. Buy-and-hold BTC is a baseline only.
- Pre-registration, walk-forward (variant B), one holdout read, real fees + GST + spread + slippage, the India tax per
  winning trade, random-entry twins, Benjamini-Hochberg across every variant, an experiment entry for every variant,
  and a sample size with every number.
- No raw data in git. Pools and caches go to `R46_OUT` (the session scratchpad); git holds code, this file, the
  results JSON (statistics only) and the write-up.

## 3. Data and periods (UTC)

- BTC spot and perp 1-minute bars (Binance, from the crypto lake `gdrive:IraAlgo-data-crypto`), 1 Oct 2023 - 12 Oct
  2026 23:59. No minute is missing in the design span.
- Delta Exchange India BTC option 1-minute **trade** candles, one file per daily expiry (12:00 UTC = 17:30 IST),
  28 Dec 2023 - 13 Oct 2026, 1,018 expiry days with none missing. A minute counts as traded only when volume > 0.
- Warm-up: Oct-Dec 2023 (indicators run across days without resets).
- **Design: 1 Jan 2024 - 31 Mar 2026 (821 days).** A design trade must exit before 1 Apr 2026.
- **Locked holdout: 1 Apr 2026 - 12 Oct 2026 (195 days), the last ~6.5 months, read once** by `run.py holdout`. It
  logs `results/HOLDOUT_READ.json` and refuses a second read. Design runs never load bars from the holdout (a test
  checks this).
  - Honest label: **partly seen.** RC1, RC2, NN_CRYPTO and STRAD_CRYPTO opened this span once, for other crypto ideas.
  - The crypto brain (C1/C2) reserves "1 Apr 2026 on" for its own pending read. R46 uses no brain feature, label or
    model, so that read stays clean for C2's ideas.
  - The last ~6 months were chosen over F1C's span (1 Apr - 30 Sep 2025) for two reasons. The other AI's window lies
    inside it, so it is read as part of the one holdout read and is never peeked at separately. It also leaves 27
    months for design.
- **The other AI's window, 1 Sep - 12 Oct 2026 (42 days; our bars end 12 Oct), is reported by itself.** It comes from
  the same holdout read and is labelled "their window, not a test".

## 4. Signal, instrument, fills

- 5-minute bars stamped at their start, built from the minutes present. A bar with no minute is dropped; nothing is
  filled.
- EMA: alpha = 2/(n+1), seeded with the first close. ADX: Wilder's (TR/+DM/-DM summed over the first 14 bars, then
  S - S/14 + x; ADX = the mean of the first 14 DX, then Wilder-smoothed).
- crossover(a, b) at bar i means a[i] > b[i] and a[i-1] <= b[i-1].
- **Call signal:** EMA8 crosses above EMA21 at the bar's close AND ADX(14) > 15 (variant B adds the MLP). **Put
  (extra, mirrored):** EMA8 crosses below EMA21 AND ADX > 15 (B: MLP P(up) < 0.35).
- **Entry minute** E is the next 5-minute bar's open (signal bar start + 5 minutes).
- **Expiry rule:** the nearest daily expiry with at least 90 minutes left at E. That means today's 12:00 UTC expiry
  when E <= 10:30 UTC, else the next day's. The 90 minutes cover the 60-minute hold, 10 minutes before settlement and
  20 spare. No position is held past 11:50 UTC on its expiry day; with this rule that limit never binds.
- **Strike:** the strike nearest to BTC spot (the open at E), within 1% of spot, that has a **real trade** (volume > 0)
  in minutes E .. E+4. Ties go to the lower strike. Its first such minute is F. With no such strike the signal is
  **unfilled**: logged and counted, it uses no daily slot.
- **Fill models:**
  - **naive:** buy at the candle close of F (the printed trade), no spread. The exit walk starts at F+1.
  - **real (MAIN):** the option's price **one minute later** (the close of F+1, as F1C's fix) plus half the modelled
    spread. The walk starts at F+2.
  - **stress:** as real, with the spread x 1.5 (reported, not a gate).
  - Exits sell at the level (or the gap open) less half the spread, one tick (0.1) and 1% of the price (market orders,
    as RC2), never below 0. Naive exits sell at the level with no spread or slippage.
- **One position at a time; at most 2 trades per IST day** (00:00-24:00 IST = 18:30-18:30 UTC, by entry minute).
  Signals while a position is open are skipped.
- Options trade 24x7. The main run uses all hours, as the claim does. An extra reading (not a gate) keeps only entries
  in Boss's crypto window, 00:00-09:00 IST.

## 5. Exits: every trade has a 60-minute time exit, a stop and a profit lock

Levels are multiples of the entry mid (the option price at the fill, before the half-spread). They are checked on
1-minute candles, carefully:

- a minute that opens through the active level exits at the open (on a gap, the worse of the level and the open);
- else a low at or below the level exits at the level;
- only then does the high raise the peak, and a new lock applies from the next minute.

The time exit is the open 60 minutes after the fill. The variants:

- **L1:** stop -50%. Step lock: a 1.25x peak locks 1.00x, 1.50x locks 1.25x, 1.75x locks 1.50x, 2.00x locks 1.75x, and so
  on (each +0.25x of peak raises the lock one rung).
- **L2:** stop -50%. Trailing lock from a 1.30x peak that keeps 50% of the peak gain (lock = 1 + 0.5 x (peak - 1), so
  at least 1.15x).
- **L3:** stop -30% with the L1 step lock (the tighter stop).

**Perp baseline (P1):** 1 BTC of the perpetual at the open of E, on the same variant-A spot signal. Stop -0.40%;
trailing lock from a +0.30% peak that keeps 50% of the peak gain; 60-minute time exit. Long on the call signal; short
on the put signal (extra).

## 6. Signal variants, the MLP, costs, size, tax

- **A:** EMA + ADX only (their net is unavailable).
- **B:** our own MLP in their shape, 12 -> 64 -> 32 -> 1 (ReLU, sigmoid, numpy).
  - The 12 inputs, at the signal bar's close, are all from BTC:
    - returns over 1, 3, 12 and 48 bars;
    - realised volatility over 12 and 288 bars;
    - ADX(14), +DI minus -DI, (EMA8 - EMA21)/close;
    - 1-hour volume vs its 24-hour average (log);
    - distance from the 24-hour high;
    - the perp-over-spot basis in bps (funding pressure; Binance funding files end on 30 Sep 2026, the basis does
      not).
  - Label: BTC spot higher 60 minutes after E than at E.
  - Trained **walk-forward by month** on every 5-minute bar whose 60-minute label ended before the test month began.
    It needs at least 6 months of history (first test month Apr 2024) and never trains on its test month.
  - Scaling is fitted on the training rows only. The last 15% of the training rows (time order) are for early stopping
    (patience 3, at most 30 epochs). Adam, lr 1e-3, L2 1e-4, batch 1,024, seed 0.
  - Threshold **0.65**, not tuned. In the holdout the net retrains each month on everything before that month.
  - **A@B:** A restricted to the months B could trade. A comparison only, not in the family.
- **Costs (Delta India):**
  - The fee per side is min(rate x notional, cap x premium) + 18% GST. The schedule is set by the expiry date, from the
    products API. Where two schedules overlap, the dearer one is used:
    - to 21 Jul 2025: 0.03% / 10%;
    - 22 Jul - 11 Sep 2025: 0.015% / 10%;
    - 12 Sep - 11 Dec 2025: 0.015% / 5%;
    - then: 0.01% / 3.5%.
  - Spread (RC2's model, the wider of its two live-chain fits): full spread = 0.000098 x spot + 0.0179 x premium, at
    least one tick.
  - Perp: taker 0.05% + GST, slippage 0.02% a side.
- **Size:** money is reported per Delta lot (1 contract = 0.001 BTC) and at the other AI's **1,000 contracts (= 1 BTC
  of underlying)**. The study reports what 1,000 ATM contracts really cost (the median premium per BTC at entry)
  against their "$300". USD, and rupees at 85 per USD. The book's capacity for 1,000 contracts is not modelled (a
  flagged limitation: the spread model is for small orders).
- **Tax, reading A** (a CA must confirm): 31.2% (30% + 4% cess) of each winning trade's gross gain. Fees are not
  deductible, there is no loss set-off and no carry-forward. 1% TDS on each sale's value is a cash-flow note (refunded
  after the year's return). Every result is given before tax and after tax.

## 7. Baselines, statistics, gates

- **Random-entry twins:** for each variant and part, 1,000 draws. Each draw takes, on every IST day the rule traded, as
  many random fillable 5-minute entry minutes of that day as the rule took, with the same side, fill and exit.
  p = (1 + #draws whose mean after-tax P&L per trade >= the rule's) / 1,001.
- Buy-and-hold 1 BTC over each part (baseline only; 0.1% a side, tax on a gain). The perp baseline P1.
- **BH family (14):** A and B x call and put x L1, L2, L3 (12 option variants), plus the perp long and short. BH runs
  on the design twin p (and is reported on the holdout too).
- **Report per variant:**
  - trades, win rate, average win and loss;
  - net before tax, tax, after tax, per day;
  - worst trade, maximum drawdown;
  - TDS, exit reasons, unfilled signals;
  - per-quarter after-tax (the walk-forward view), the Boss-window reading;
  - p, q, pass/fail;
  - naive and stress fills alongside the main fill.
- **Gate (all needed, main fill):**
  - profit after costs AND tax in design and in the holdout;
  - design twin p < 0.05 and design BH q <= 0.10;
  - at least 40 trades in design and 40 in the holdout.
  - Puts and perp-short are labelled extra. Even if they pass, they are reported, not proposed, without Boss's say.
- A pass would only make the variant a candidate for a **paper** log. Nothing in the app trades crypto yet, and live
  use stays out of scope.

## 8. Procedure

1. Commit this file and the code.
2. `python -m research.hunt.r46.run design`, then commit `results/design.json` and the design write-up.
3. Only then `python -m research.hunt.r46.run holdout` (once). Commit `results/holdout.json`, the report, experiment
   entries `r46-*.yaml` for every variant, and the FINDINGS section.

## Amendments

(none)
