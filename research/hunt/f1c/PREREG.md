# F1C pre-registration: the crypto part of the IraAlgo Strategy Factory (TODO F1)

Written 14 Oct 2026, **before** any screen result or rule outcome was computed. Part A (sections 1-7) is committed
first; part B (section 8, the candidate rules) is added after the screen (step 2, design data only) and committed
before any rule's P&L is computed. Nothing in either part may change after its commit except by a dated amendment
at the end. Research only: nothing in the app trades crypto.

## 1. Question

Is there any crypto rule (BTC, ETH, SOL, BNB, XRP perpetuals; Delta Exchange India INR BTC/ETH options), traded in and
out like the Indian market inside Boss's 00:00-09:00 IST window, that makes money after real fees, slippage, funding
and India's crypto tax, beats random-entry twins after Benjamini-Hochberg across the whole family, holds up
walk-forward by quarter, and holds up on a locked holdout read once?

CLAUDE.md crypto rule: **every trade has a stop-loss AND a profit lock** (a step or trailing lock). No variant without
either. Buy-and-hold and DCA appear only as baselines to beat.

## 2. Periods and the holdout

Holdouts already read by earlier crypto work (from their write-ups):

| study | holdout read |
|---|---|
| RC1 (`HUNT_RC1_CRYPTO_SMALL_ACCOUNT.md`) | 13 Oct 2025 - 12 Oct 2026 (perps and carry to 30 Sep 2026) |
| RC2 (`HUNT_RC2_CRYPTO_OPTIONS.md`) | 13 Apr - 13 Oct 2026 |
| NN_CRYPTO (`NN_CRYPTO.md`) | 1 Apr - 7 Oct 2026 |
| STRAD_CRYPTO (`STRAD_CRYPTO.md`) | 1 Apr - 7 Oct 2026 |
| crypto brain C1 / C2 | 1 Apr 2026 on: locked, **not read** (C2's W4-ETH still waits on it) |

The most recent 6 months were read (RC1, RC2, NN, STRAD), so F1C does **not** use them. F1C's holdout is the clean
earlier block **1 Apr 2025 00:00 UTC - 30 Sep 2025 23:59 UTC** (183 days): no earlier study read it as a holdout. It is
not virgin: it was design or walk-forward data for C1, C2, RC1, RC2, NN and STRAD, and their published findings (for
example "only the size of the move is predictable") were learned partly on it. No F1C rule is chosen on it.

- **Design**: 1 Nov 2023 - 31 Mar 2025 and 1 Nov 2025 - 31 Mar 2026 (the brain's data starts 1 Oct 2023; the first
  31 days are feature warm-up; October 2025 is a 31-day embargo so no feature window reads a holdout minute). A label
  or trade must start and end inside one design span.
- **Apr 2026 on** is not touched (the brain's own lock; also read by RC1/RC2/NN/STRAD).
- Screen split (step 2): 1 Nov 2023 - 30 Sep 2024. Selection split: 1 Oct 2024 - 31 Mar 2025 and 1 Nov 2025 - 31 Mar 2026.
- Walk-forward folds: calendar quarters 2023Q4 (Nov-Dec), 2024Q1-Q4, 2025Q1, 2025Q4 (Nov-Dec), 2026Q1.
- The holdout is opened once, by `research/hunt/f1c/run.py holdout`, only after the design, walk-forward, twin and
  stress results are committed; `<cache>/f1c/HOLDOUT_READ.json` refuses a second read.

Code: `research/hunt/f1c/f1ccfg.py` (`HOLDOUT`, `DESIGN`, `SCREEN`, `SELECT`).

## 3. Step 2, the feature screen (design data only)

- Features: every crypto brain feature (78 after the F1 refresh; `research/brain/crypto/FEATURES.md`) for BTC, ETH,
  SOL, BNB, XRP (option and DVOL features exist for BTC and ETH only; OI / long-short metrics for all five).
- Targets: the signed next move `y_ret_h` (the **direction** family) and its size `|y_ret_h|` (the **size** family),
  h = 15, 60 and 240 minutes, spot close to spot close.
- Rows: non-overlapping, one per h minutes (the bar that closes on an h-minute UTC boundary).
- Test: Spearman rank correlation, two-sided, on the screen split. BH across every (coin, feature, horizon) of a
  family at once; pass at q < 0.05.
- A pass must then show the same sign with p < 0.05 on the selection split ("confirmed").
- "Pays": for a confirmed direction feature, half the top-minus-bottom decile spread of the next move (selection split,
  bps) must exceed a Delta India perp round trip (2 x (0.05% x 1.18 + slippage); 15.8 bps BTC/ETH, 21.8 bps others).
- Everything that fails is reported as noise, by name.

## 4. Money, costs and tax

- Account Rs 1,00,000 (also reported at Rs 25,000). Fixed size, no compounding. Rs 85 per USD (only lot rounding
  depends on it).
- **Perps** (Binance USD-M 1-minute bars stand in for Delta India INR perps; RC1 measured 0.987-0.994 correlation):
  risk 1% of the account at the stop, notional capped at 3x the account, whole Delta lots (BTC 0.001, ETH 0.01,
  SOL 0.1, BNB 0.01, XRP 1). Fee 0.05% taker + 18% GST per side; slippage 0.02% per side (BTC, ETH), 0.05% (SOL,
  BNB, XRP); funding at Binance's settled rate for each 00/08/16 UTC settlement held (the 00:00 UTC one, 05:30 IST,
  is inside the window). Entry at the next minute's open after the signal bar closes, plus slippage.
- **Options** (Delta India BTC/ETH daily options, 1-minute trade candles from the options lake): at most 2% of the
  account in premium a trade, whole lots (BTC 0.001, ETH 0.01). Fee min(0.01% of notional, 3.5% of premium) + 18%
  GST per side; RC2's spread model (the wider of its two live-chain fits) on entry and exit, exits less 1 tick and
  1%. No entry in a strike's first 15 minutes, nor on a candle that jumps more than 2x from the previous close (RC2's
  listing glitch); the option must have traded in the 30 minutes before entry.
- A rule stops for the IST day after losing 3% of the account that day.
- **Stress**: perps slippage x2; options spread x1.5 and fee 0.03%.
- **Tax** (modelling assumptions; a CA must confirm):
  - reading A (VDA, sec 115BBH, the primary number): 30% + 4% cess = 31.2% of each profitable trade's price gain
    (fees not deductible), losses never set off, no carry-forward;
  - reading B (business income): 31.2% of each financial year's net P&L after costs when positive.
  - **TDS** 1% of every sell's value (perps: the exit of a long and the entry of a short; options: the sale at exit) is
    withheld and refunded about 365 days later. It is a credit against tax, not a cost; its cash lock is reported
    (peak rupees locked and whether trading could continue on a Rs 1 lakh account).
- **Baselines** to beat: buy-and-hold and weekly DCA of the same coin over the same design days (after the same
  tax reading A and TDS), and flat (zero).

## 5. Exits: every trade has a stop-loss AND a profit lock

Perps (R = the stop distance; stop = k x the 14-hour ATR of hourly bars, `atr14_1h_pct`, never below 0.25%):

| variant | stop | lock |
|---|---|---|
| `s10_step` | k = 1.0 | step: at +1R lock +0.25R, at +2R lock +1R, at +3R lock +2R, then +1R more locked per +1R |
| `s10_trail` | k = 1.0 | trail: once the peak is +1R, keep 50% of the open profit, never below +0.25R |
| `s15_step` | k = 1.5 | step, as above |
| `s15_trail` | k = 1.5 | trail, as above |

Options (RC2's exits, multiples of the entry premium): `s40_step`, `s40_trail`, `s25_step`, `s25_trail` (stop -40% or
-25%; step lock 1.3x locks 1.0x, 1.6x locks 1.3x, 2x locks 1.6x, 3x locks 2.4x, ...; trail from 1.3x keeps 75% of the
peak, never below 1.0x).

Every trade is flat by 09:00 IST (03:30 UTC) at the latest, or at the rule's own time limit. Inside a minute bar the
order is unknown, so the check is the cautious one: a bar that opens through the active level exits at its open; a bar
whose low (long) / high (short) touches it exits at the level; only then does the bar's extreme raise the peak, and a
new lock applies from the next bar. "Big winners cut short" = lock exits after which price went on, before the forced
time exit, to at least 2R more (perps) or to 2x the exit price (options).

## 6. Random twins and BH

- For every rule and exit variant: a pool of random entries on the same design days with the same entry clock (the
  rule's own IST entry minutes, drawn at random), a coin-flip side, the same sizing and exits. Twin p (one-sided) = the
  share of 4,000 random twin sets (as many trades as the rule) whose mean net rupees a trade is at least the rule's.
- BH across the **whole family**: every rule x every exit variant (at most 20 x 4 = 80 tests) at once.

## 7. Gates (a rule passes only if all hold, for its pick)

- **Pick** (chosen on design only): the variant with the best design net after tax A; ties to the lower trade count.
- G1 design: net after fees, slippage, funding and tax A > 0 on at least 100 trades.
- G2 walk-forward: each quarter from 2024Q1 uses the variant with the best net on all earlier design quarters; the
  sum over those quarters is > 0 and at least half the quarters are green.
- G3 twins: twin p < 0.05 and BH q < 0.10.
- G4 stress: net after tax A > 0 under the stress costs.
- G5 holdout (read once, after G1-G4 are committed): the pick's net after tax A > 0.

A pass would be a **candidate for a watch-only paper log**, not an arm; nothing in the app trades crypto and any
change there is a separate decision for Boss.

## 8. The candidate rules (part B)

To be added after the screen, before any rule P&L: at most 20 rules, each with the four exit variants of its type.

## Amendments

(none yet)
