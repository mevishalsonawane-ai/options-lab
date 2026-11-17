# R191 pre-registration: a risk-capped portfolio of the existing trade streams

Written 17 Nov 2026, BEFORE any R191 portfolio profit or loss was computed (on any era). Research only; nothing under
`android/` or `.github/` changes.

Boss (owner): *"find me very good profitable strategies with good winrate ... it should be great and reliable"*, then
*"Try more harder"*. R190 (`research/hunt/r190_best_hunt`) tested 13 single rules on three eras; none passed its gate.

**Question.** Can a pre-registered PORTFOLIO with risk rules, built only from trade streams that already exist, make
money in every era AND stay inside Boss's account stop (-Rs 40,000 drawdown, -Rs 13,000 in a month; account about
Rs 56,000), reliably? No new entry signal is invented here (the sister study R192 does that). The only levers are
which streams are combined, one-position limits, and loss caps that use information known at the time.

## 1. What I saw before writing this (honest disclosure)

- R190's published stream-level numbers for every era (results/summary.md): Night, Liquidity and R72 nets, win rates,
  worst months and drawdowns in eras A, B and C. So eras C and A are **not unseen at the stream level**: R190 read
  them. What has not been computed by anyone is any R191 portfolio rule on any era.
- The buffers below (section 4) are set from era-B averages only (R190: Night's average loss about Rs 1,900 in B,
  Liquidity's about Rs 600 in B). No parameter is fitted or searched; nothing is tuned on any era.
- There is no unseen option history (R190 section 2). The only new data will be the forward test (section 9).

## 2. Data (nothing raw goes into git)

Trade streams exactly as R190 priced them (1 lot, dated Zerodha charges, its slippage models, real 1-minute option
prices), read from R190's caches under `~/.cache/iraalgo/r190` with `python3 -I`:

| stream | source | eras with data | signal source |
|---|---|---|---|
| **N** Night (R3) book: NIFTY + BANKNIFTY, 1 lot each | `r190night.night_rows` / `trades_of` (R147 builder) | A (NIFTY only), B, C | index close location, 59-stock breadth; option OI build-up as context |
| **L** Liquidity 15+5 (BANKNIFTY, FINNIFTY, MIDCPNIFTY), as built | `arms_trades.pkl` "L1" | B, C | the index |
| **R** R72 + R151 picker (live by Boss's exception) | `arms_trades.pkl` "D2" | B, C | the five indices' 5-minute Donchian breaks |
| VIX flag (previous close above its 252-day 90th percentile) | `rules_NIFTY.pkl` "vix_high" (R190's daily VIX check) | A, B, C | India VIX daily closes |

Option buying only (Boss, 21 Oct). Every stream signals on the underlying and buys the option. The R72 picker's
era-C numbers are mostly in-sample for the picker itself (R151 trained it on Nov 2024 - Mar 2026).

Eras (R190's): **A** = 3 Aug 2020 - 30 Jul 2021, **B** = 1 Dec 2021 - 31 Oct 2024, **C** = 1 Nov 2024 - 13 Nov 2026.

**Era A**: only Night on NIFTY has data. In era A every variant runs with the components that have data (Night NIFTY
with that variant's Night and portfolio rules); Liquidity and R72 simply have no trades there. Era A is still judged
by the gate (net, trades, top-5, twin p), because Boss asked for money in every era.

## 3. Timing: what is known when (the no-look-ahead rule)

Each trade has a decision time and a "known" time (when its closed P&L may be used by a rule):

- Night: decided and bought at 15:20 on day d; closed at the next session's 09:16 (day nd). Its P&L is usable from
  the Night decision of nd onward (15:20 > 09:16), and by intraday decisions only from the session after nd.
- Liquidity and R72 (intraday): decided during day d; the cache has no exit minute, so their P&L is treated as known
  only after the close of day d (15:30). No same-day intraday result is ever used by another decision of that day, and
  it is not used by that day's 15:20 Night decision either.
- Encoded as day + fraction: intraday decision d+0.3, Night decision d+0.6, Night known nd+0.5, intraday known d+0.7;
  a closed trade is visible to a decision iff its known time is strictly earlier.
- Money is booked on the close day (Night: nd; intraday: d) for months, drawdown and every statistic.
- A look-ahead guard test: changing the nets of every trade decided at or after time t never changes which trades
  before t were taken.

## 4. The variants (fixed now: 8, all at 1 lot per trade)

Building blocks:
- **one-a-night**: at most one Night position a night: NIFTY when both indices signal; only if its premium x lot fits
  Rs 56,000 (R190's N2 choice, without N2's month cap).
- **month halt**: once the month's closed net (visible trades, booked in this month) is <= **-Rs 8,000**, no new entry
  of any component until the month ends. Why -8,000: the account's -13,000 month stop less a Rs 5,000 buffer for what
  can still land after the halt (about two Night losses and two Liquidity losses at era-B averages: 2 x 1,900 + 2 x 600).
- **drawdown brake**: once visible closed equity is >= **Rs 25,000** below its running peak (the -40,000 stop less a
  Rs 15,000 buffer, about one bad month), no new entry. While halted, the variant's candidate trades are followed on
  paper; at the first decision of a new month, trading resumes if the previous calendar month's paper closed net was
  > 0 (the peak is then reset to the equity at resumption); otherwise the halt continues and is checked again next month.
- **equity-curve filter**: each component trades only while the sum of its own last 20 candidate trades (on paper,
  visible at the decision) is > 0; with fewer than 20 visible candidate trades (each era starts fresh) it trades.
- **VIX skip**: no Night or Liquidity entry on a day whose VIX flag is high (unknown = not high, as in R190).

| id | components | rules |
|---|---|---|
| **P1** | N + L | none (equal weight, 1 lot each; the baseline portfolio) |
| **P2** | N + L + R | none |
| **P3** | N + L | one-a-night |
| **P4** | N + L | one-a-night + month halt |
| **P5** | N + L | one-a-night + month halt + drawdown brake |
| **P6** | N + L | one-a-night + month halt + equity-curve filter |
| **P7** | N + L + R | one-a-night + month halt + equity-curve filter |
| **P8** | N + L | one-a-night + month halt + VIX skip |

Order of checks for a candidate: static filters (one-a-night, VIX), then the drawdown brake, the month halt and the
equity-curve filter. Candidates of the same decision time are taken in the order N, L, R, then index name.

No daily loss cap: the cache has no exit minutes for the intraday streams, so a same-day cap cannot be applied without
look-ahead. Sizing below 1 lot is impossible, so "halve the size" rules are replaced by halts. Intraday premiums are not
in the cache, so the Rs 56,000 fit is only checked for Night (stated as a caveat).

## 5. Measurement (per variant, era and component, at 1 lot)

Trades, win % of trades, winning months % (months with net > 0 over months with at least one booked trade), average
win and average loss, net, net without the top 5 trades, worst day, worst month, max drawdown (on the daily booked
net). Per component: the same for the trades the portfolio actually took.

## 6. Random twins and multiple testing

- **Twin**: 2,000 twins per variant and era, seed 191. In each twin, every calendar month (by decision day) of the era
  gets one random sign (+1 or -1, probability 1/2), applied to the nets of ALL candidate trades of that month (all
  components together, so their correlation is kept); the variant's rules are then re-run on the flipped streams.
  This is a month-block random-side twin: under "no edge", a month is as likely to go one way as the other. Its null
  mean is zero, which is stricter than random-entry twins (they lose the charges).
- p = (1 + twins with net >= the variant's net) / 2,001 (one-sided), and the normal-tail p.
- **BH**: m = 4,762 (R190's family total: the entry files before R190 plus its 13 candidates) + 8 = **4,770**. Earlier
  tests count as p = 1. Each variant's BH p is its largest normal-tail twin p over the eras it is judged on (A, B, C).

## 7. The RELIABLE gate (all must hold; fixed now, never loosened)

In EACH of eras A, B and C (era A with the components that have data, section 2):
1. 40+ trades;
2. net > 0;
3. net without the top 5 trades > 0;
4. twin p < 0.05 (empirical);

and
5. BH q <= 0.10;
6. in eras B and C: max drawdown < Rs 40,000 and every month > -Rs 13,000 (worst month > -13,000), at 1 lot.

A variant meeting 1-6 is RELIABLE; otherwise NOT RELIABLE with every failed item listed.

## 8. Order of work and the era lock

1. This file is committed before any portfolio number exists.
2. `r191study.py b` computes era B only (`select_era` refuses C and A) and writes `results/era_b.json`. It also names
   the **B pick**: among variants that pass items 1-4 and 6 on era B, the highest era-B net / max drawdown (none if
   none passes). Committed.
3. `results/ERA_CA_READ.flag` is committed. `r191study.py ca` refuses to run unless that flag is tracked in git and
   `results/era_ca.json` does not exist. It reads eras C and A once, for all 8 variants (the list is fixed; nothing is
   dropped or re-tuned).
4. `r191study.py report` writes `results/summary.md`, `results/report.md` and `decision.json`.

## 9. Forward test from 17 Nov 2026 (new days only)

Whatever the verdict, the B pick (or, if none, P4) is followed on paper from 17 Nov 2026 on new sessions, with the
same rules and 1 lot: monthly checkpoints (net, worst month, drawdown against the account stop) and a first judgement
after 6 months or 150 portfolio trades, whichever is later. A forward test only reports; it never changes these rules.

## 10. Tests

`test_r191.py` (pytest): timing and visibility, one-a-night, the VIX skip, the month halt (including a Night trade
booked into the next month across a weekend gap, an all-loss month, empty months), the drawdown brake and its
resumption, the equity-curve filter warm-up, statistics (empty input, months up), the twin's sign blocks, the gate,
BH, the era lock, and the look-ahead guard.

## 11. Outputs

`results/summary.md` (outcome first, one table per variant x era), `results/report.md`, `decision.json`,
`results/*.json`, entries `research/experiments/entries/r191-*.yaml` (one per variant, failures included).
