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

## 8. The candidate rules (part B, written after the screen, before any rule P&L)

What the screen (section 3) said, design data only (`results/screen.csv`, `screen.json`): of 1,080 testable direction
tests (78 features x 3 horizons x 5 coins, less the option / DVOL features the alt coins lack), 356 passed BH and 245
were confirmed on the selection split; **89% of the confirmed ones are reversals** (price stretched away from the
24-hour VWAP, the day's range, the EMA ribbon, or after one-sided taker flow tends to come back a little), and they are
two to three times stronger inside Boss's window (median selection IC at 60 minutes -0.067 in the window vs -0.034 at
all hours). The typical edge is tiny: median half decile spread 0.7 bps against a 15.8-21.8 bps round trip; 2 of 245
"pay" on paper (XRP at 240 minutes). Funding, basis, premium, long/short ratios, DVOL, every option-market feature
(skew, put/call, ATM IV, option flow) and the calendar confirmed nothing for direction: noise. Size (|move|) is
predictable from realised volatility and ranges (550 confirmed), as C1 found.

So the rules are mostly reversal rules on the confirmed features, plus breakout / trend rules as the opposite bet
and the earlier studies' best near-misses (C2 W4, RC2 R8, RC2 R4). Before any P&L, decision counts were checked (BTC
202-4,720 decisions per rule over 670 window days). Three rules first drafted on funding, skew and put/call were
replaced at that point by P12, P14 and P15, because the screen had already called those features noise.

Every rule: decisions at 5-minute closes whose entry minute is inside 00:00-09:00 IST; entry the next minute; one
position per rule and coin at a time; the 3% day loss cap; flat by 09:00 IST or the time limit; the four exit variants
of its venue (section 5). Code: `research/hunt/f1c/rules.py` (thresholds are written there; ATR = `atr14_1h_pct`).

| id | venue, coins | rule | time limit | max a day |
|---|---|---|---|---|
| P01 | perp, all 5 | VWAP-24h stretch fade: `vwap24_z` > 2 short, < -2 long | 60 min | 2 |
| P02 | perp, all 5 | Day-extreme fade: `day_range_pos` >= 0.95 after a > 1 ATR hour -> short; <= 0.05 after a < -1 ATR hour -> long | 120 | 2 |
| P03 | perp, all 5 | Ribbon stretch fade: close more than 1.5 ATR from the 15-min ribbon's middle EMA, against it | 60 | 2 |
| P04 | perp, all 5 | Hour reversal: at a UTC hour's close, an hour that moved > 1.5 ATR, against it | 60 | 2 |
| P05 | perp, all 5 | Taker exhaustion: 60-min perp taker imbalance > 0.2 on an up hour -> short; < -0.2 on a down hour -> long | 60 | 2 |
| P06 | perp, all 5 | Window-open fade (C2 W4 as a rule): at 00:00 IST, `vwap24_z` > 1 short, < -1 long | 09:00 IST | 1 |
| P07 | perp, all 5 | Previous UTC day high / low break, with it (first cross of the day) | 180 | 1 |
| P08 | perp, all 5 | Asia opening range: first 5-min close outside the 00:00-01:00 IST spot range, 01:00-06:00 IST, with it | 09:00 IST | 1 |
| P09 | perp, all 5 | Ribbon squeeze break: 15-min ribbon width < 0.25 ATR and a 15-min move > 0.5 ATR, with it | 120 | 2 |
| P10 | perp, all 5 | Ribbon trend: 1-h ribbon fully ordered and the 15-min ribbon turning fully ordered the same way, with it | 180 | 2 |
| P11 | perp, all 5 | Liquidation-flush fade (`liq_flush_60m` = 1), against the 60-min move | 120 | 1 |
| P12 | perp, all 5 | Day-extreme 4-hour fade: close within 0.1% of the UTC day's high -> short, of its low -> long (first of the day) | 240 | 1 |
| P13 | perp, all 5 | OI divergence: a > 1 ATR hour while OI fell > 0.5% (|OI change| < 20%, the lake has bad OI prints), against it | 120 | 2 |
| P14 | perp, all 5 | Ribbon slope fade: the 15-min ribbon's middle EMA moved > 0.5 ATR in 60 min, against it | 60 | 2 |
| P15 | perp, all 5 | Consensus reversal: `vwap24_z` > 1, `day_range_pos` > 0.8, ribbon distance > 0.5 ATR and taker imbalance > 0.1 all at once -> short (mirror -> long) | 60 | 2 |
| O16 | Delta options, BTC ETH | P01's signal; buy the ATM call (long) / put (short) of the day's 17:30 IST expiry | 60 | 2 |
| O17 | Delta options, BTC ETH | P04's signal, ATM call / put | 60 | 2 |
| O18 | Delta options, BTC ETH | P07's signal, ATM call / put | 180 | 1 |
| O19 | Delta options, BTC ETH | Volatility burst: |15-min move| >= 2.5x DVOL's 15-min move, with it (RC2 R4 inside the window) | 120 | 2 |
| O20 | Delta options, BTC ETH | P08's signal, ATM call / put | 09:00 IST | 1 |

20 rules x 4 exit variants = 80 tests in one BH family. Perp rules pool their coins (one test per rule and variant),
option rules pool BTC and ETH. Random twins: 1,500 per rule, variant and coin. Walk-forward, gates and the holdout
read as in sections 6-7; the holdout is read for all 20 rules at once, in one run, after the design results are
committed.

## Amendments

(none yet)
