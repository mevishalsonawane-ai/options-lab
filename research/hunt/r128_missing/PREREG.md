# R128 pre-registration: what we were missing, fixed and tested (option buying only)

Written 28 Oct 2026, before any R128 number was computed. Research only: nothing in the app changes, nothing trades.

Boss, 28 Oct: *"We are missing something. Deep research using internet, pdf study we had, and find out what are we
missing and add it or fix it and test it."* Then: *"Only options buying."*

## 0. What was missing (three independent reviews, 28 Oct)

| source | key point |
|---|---|
| Internet research | Option buyers win only when the realised move beats the move the option already prices: the volatility risk premium (Coval & Shumway 2001; Goyal & Saretto 2009; 0DTE studies 2025). India: NIFTY option sellers earn their premium mostly **overnight**, and intraday option returns are near fair (Bhat, Pandey & Rao 2024, *J. Futures Markets*; Muravyev & Ni 2020, *JFE*). Intraday momentum: the return so far predicts the last half hour (Gao, Han, Li & Zhou 2018; Baltussen, Da, Lammers & Martens 2021). No Indian test of that was found. |
| The repo's book studies (R104 and others) | "Buy only when the forecast move beats the price" (Bennett, Ross, Wilmott) was tested only with a daily forecast and an implied volatility that includes overnight variance (h29). No study split a buyer's loss into time decay, volatility change and costs. Exits were tested on entries with no edge. |
| An audit of R123-R127 | 1. Our move forecast was never compared with the option's price. 2. Every rule used fixed, symmetric exits, which remove a bought option's convexity. 3. The R124/R125 rules could barely fire, because the direction share spans only 0.39-0.54. 4. R123's S4/S5 morning put picks passed the whole walk-forward and were positive on the holdout (+Rs 345 / +Rs 579 a trade), failing only on trade count: an open lead. 5. Since Nov 2024, BANKNIFTY, FINNIFTY and MIDCPNIFTY trade monthly options. 6. Minor: R127's random twins counted "no trade" as Rs 0, overstating p. No bug turned profits into losses. |

## 1. The tests (one BH family of 9 trading rules; T4 is descriptive)

**Data:** R123's rows, every 5 minutes from 09:20 to 14:55, all five indices. Out-of-sample network forecasts come
from R123's N1 walk-forward (folds 2023, 2024, 2025, 1 Jan - 15 Mar 2026) and its holdout fit (Apr - Oct 2026). Option
bars: the cleaned lake (lakeclean steps 1-3; minutes with no trade are never priced). 1 lot at today's lots, dated
charges, Fills("liq"), and 2x slippage. Every rule only **buys** options and holds intraday only (never overnight).

### T1: buy the straddle only when the forecast move is large compared with the option's price

**The ratio at row t:**
- F = the forecast 60-minute range in points: exp(nn_rg_60) / 100 × close.
- I = the range the ATM option implies: 1.6 × atm_iv / 100 × close × √(60 / (252 × 375)). 1.6 is the expected
  high-low range of a random walk in standard deviations.
- Q = F / I.

**Signal:** Q is at or above the 90th percentile of the same index's Q over its previous 60 sessions (earlier rows
only; no outcome is read). The first 60 sessions are burn-in.

**Trade:**
- Buy the ATM call and the ATM put (the strike nearest the index close at t) at the open of t+1.
- At most one open straddle per index; at most 5 trades a day.

**Exits:**

| rule | exit |
|---|---|
| T1a | after 60 minutes, or at the square-off |
| T1b | hold to the square-off (15:20; 15:05 on expiry days) |
| T1c | the straddle's combined value, marked at each minute's last traded closes: stop at -35% of its cost; after it has gained 20%, exit when it falls to 85% of its peak; else at the square-off |

**Reported but not gated:**
- straddle P&L (T1a exits) by decile of Q, and the realised / implied range by decile;
- NIFTY and SENSEX (still weekly expiries) separately from the monthly-expiry indices after Nov 2024.

**Twins:** 1,000 random rows of the same index and day with the same exit rule.

### T2: the S4/S5 lead with exits that keep the big runs

The entries are R123 N1's own S4 and S5 trades (walk-forward 1,085 / 555, holdout 52 / 41): the same option at the
same minute. Only the exit changes:

| rule | exit |
|---|---|
| T2-S4x1, T2-S5x1 | **trailing lock**: the same -Y stop as the original cell; once net reaches +Rs 1,000, exit when net falls to 60% of its peak; else at the square-off |
| T2-S4x2, T2-S5x2 | **hold to close**: the same -Y stop; no target; out at the square-off |

**Twins:** 1,000 same-entry twins with a random side, the other option under the same exit rule.

The original exits are reported beside them. Gates are as R123 (≥ 100 holdout trades), so S4 and S5 cannot pass on
the holdout's trade count. This test only decides whether a forward test should use the new exits.

### T3: late-day intraday momentum, buying an in-the-money option

On non-expiry days, at 15:00 (the close of the 14:59 bar):
- **T3a:** r = the 14:59 bar's wick midpoint ÷ the previous session's last-bar midpoint, minus 1.
- **T3b (Gao et al.):** r = the 09:44 bar's midpoint ÷ the previous session's last-bar midpoint, minus 1 (the opening
  half hour, including the gap).

**Trade:**
- Enter if |r| is in the top 30% of the same index's |r| over its previous 250 sessions.
- Buy the option 2 strikes in the money in r's direction at the open of the 15:00 bar.
- Sell at the close of the 15:19 bar.

**Twins:** 1,000 random-side twins on the same days.

### T4 (descriptive): where an option buyer's money goes

For T1a's straddles (all rows with a trade) and for every ATM straddle at 10:00, the average of:
- the cost paid, charges, and slippage;
- the index's absolute move over the hold;
- the ATM IV change from entry to exit;
- the share of trades where the move beat the cost.

### T5 (fix): R127's random twins

Twins without a trade are resampled from that trade's valid twins instead of counting as Rs 0. R127 is re-run and its
p-values corrected. No R127 rule or result changes otherwise.

## 2. Gates (all 9 rules)

A rule passes only if all of these hold:
- **Walk-forward (2023 - 15 Mar 2026):**
  - ≥ 300 trades;
  - average net per trade > 0 at 1x and at 2x slippage;
  - positive in ≥ 3 of the 4 folds;
  - BH q ≤ 0.10 over the 9 rules.
- **Holdout (Apr - Oct 2026):**
  - ≥ 100 trades;
  - average net > 0 at 1x and 2x;
  - q ≤ 0.10;
  - Boss's account stop is not breached (drawdown above -Rs 40,000, no month below -Rs 13,000).
- **Honest limit:** the holdout has already been read for other questions (R123-R126). A pass here earns only a shadow
  forward test on sessions after 19 Oct 2026, never paper directly.

## 3. Outputs

- Code and results: `research/hunt/r128_missing/`.
- Write-up: `research/HUNT_R128_MISSING.md`.
- Experiment entries: `research/experiments/entries/r128-*.yaml`.
- R127 corrected.
- RESULT.md.
