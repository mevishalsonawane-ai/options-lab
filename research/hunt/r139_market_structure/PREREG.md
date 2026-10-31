# R139 pre-registration: market structure (higher highs and lows, lower highs and lows), 12 rules

Written 31 Oct 2026, before any R139 trade or number. Research only (no app code).

Boss, 31 Oct: *"Anything regarding equity shift, lower low, lower high, something like that, and create a strategy.
Search on internet regarding these topics and provide me a good backtest result."*

- **"Equity shift"** is read as the **market structure shift (MSS)**: the trend turns when price breaks the last higher
  low (or lower high). ICT's **"equilibrium"** (the 50% of a swing leg; below it is "discount", above it "premium")
  sounds similar, so it is covered too (S8, S9).
- **"A good backtest result"** is answered honestly: every rule, its gates and its tests are fixed here. Nothing is
  tuned after a number is seen.
- Sources and their exact rules: `SOURCES.md`, written before any number. It found no independent, sample-sized test
  of BOS, CHoCH, MSS or premium/discount. The academic evidence on simple breakout and support/resistance rules shows
  small edges that shrink out of sample and after costs.

**Signal source: the index spot only** (wicks for swings, stops, targets and touches; 15-minute closes only where a rule
says "closes above"). The trade buys the ATM option. Nothing is read from an option's own chart.

## What we already know, and what is new here

| earlier study | what it found | how R139 differs |
|---|---|---|
| R131 trend15 | 15-min wick structure, stop-and-reverse: +Rs 6 a trade on 6,317 (design), -Rs 56 on 894 (holdout); a 60-min agreement filter made it worse | R139 trades a **trend state** (two higher highs AND two higher lows), not every break; entries are pullbacks, closes and reversals, each with its own structural stop |
| R137 | strong-swing close break: design +Rs 156 on 797, holdout -Rs 373 on 130 | S2 / S10: the break must be **with a Dow trend**, stop at the higher low, not the breakout bar |
| R62, R62B | BOS/ChoCH filter, MSB + order block: 0 of 32, 0 of 16 | no order blocks, no volume profile; plain swings |
| R130, R131 S1 | sweep, then MSS, then FVG: lost | S3 / S5 need a **Dow down-trend**, not a sweep; no FVG |
| R131 S10 | OTE pullback 0.618 / 0.705: lost | S8 is ICT's 50% equilibrium after a BOS leg; S9 is the shallow 25% (R135) |
| R135 | breaks continue; strong-swing breaks continue slightly; shallow pullbacks hold more than deep ones | S2, S9, S10 test exactly these as trades |
| R124, R113 | direction is a coin; moves and the money cluster in the first hour | S10: BOS entries to 10:15 only |
| R123 / R132-R137 | R123's N1 network is the only filter that has held up | S12: S1 + N1's expected-value filter |

## 1. Swings and trend (shared by every rule)

- **Bars:** 15-minute index bars aligned to 09:15 (R131's bars), across sessions.
- **Swing high:** a bar whose high is above the 3 bars before it and at or above the 3 bars after it (R131's pivot,
  wicks). Swing lows mirror. A swing is **known only at the close of the 3rd bar after it** (no look-ahead). Swings from
  today and the previous 4 sessions only.
- **Dow trend at a bar's close:** UP if the last two known swing highs rise AND the last two known swing lows rise
  (HH + HL); DOWN if both fall (LH + LL); otherwise none.
- Each rule is written for the long side (buy the ATM CALL). The short side (buy the ATM PUT) is the exact mirror
  (R131's mirrored index: high <-> low), so Boss's "lower high, lower low" sell is the mirror of every rule below.

## 2. The 12 rules (one BH family)

| id | name (source) | entry (long side; the put side mirrors) | stop (index) | target |
|---|---|---|---|---|
| **S1** (primary) | Dow pullback (Dow theory; "buy the higher low") | trend UP and a new swing low is confirmed (it is a higher low): buy at the next minute | 0.02% below that higher low | 2R |
| S2 | BOS continuation (SMC) | trend UP at the previous bar's close; this bar is the first 15-min **close** above the last swing high | 0.02% below the last swing low (the higher low) | 2R |
| S3 | CHoCH reversal (SMC) | trend DOWN; the first 15-min close above the last swing high (the last lower high) | 0.02% below the lowest low since that lower high | 2R |
| S4 | 1-2-3 reversal (Sperandeo, Ross) | point 1 a lower low, point 2 the next swing high (a lower high), point 3 the next swing low above point 1, all known; the first 1-minute **wick** a tick above point 2, with no bar above point 2 and none below point 3 since | 0.02% below point 3 | 2R |
| S5 | MSS, then the first higher low (ICT) | after an S3 shift, the first swing low confirmed within 24 bars that is above the shift's low; cancelled if price goes below that low first | 0.02% below the new higher low | 2R |
| S6 | Multi-timeframe (60-min trend + 5-min CHoCH) | the 60-min Dow trend (pivots of 2 bars each side, the last 60-min bar closed) is UP and the 5-min chart gives S3's shift up | 0.02% below the 5-min low since its lower high | 2R |
| S7 | Swing failure (SFP, Turtle Soup) | a 15-min wick a tick below the last swing low (untaken since it formed) and the bar closes back above it; no trend condition | 0.02% below that bar's low | 2R |
| S8 | Equilibrium / discount (ICT) | the last swing low is a higher low; the highest high since it broke the last swing high before it (a BOS leg) and the leg is at least 1 x ATR14; buy the first 1-minute touch of the leg's **50%**, not touched since the leg high (a minute that also makes a new leg high is not an entry); one trade per higher low | 0.02% below the leg low | the leg high |
| S9 | Shallow pullback (R135) | as S8 but the first touch of **25%** back from the leg high | 0.02% below the leg's 50% | the leg high |
| S10 | BOS, first hour (R113, R124) | S2 with entries to 10:15 only | as S2 | 2R |
| S11 | First lower high after a lower low (Boss's words; Brooks's L2) | the trend was not already DOWN, a swing low made a **lower low** (the first break of the up-structure), and the first swing high confirmed after it is **lower** than the high before: buy the put at once (the long side mirrors) | 0.02% above that lower high | 2R |
| S12 | S1 + R123's N1 network | S1's trades kept only when N1's expected value for that side is above 0 (R132 Amendment A's NEV filter; walk-forward views, 2023 on) | as S1 | as S1 |

## 3. Trading (R131-R138's rules)

- Entry at the next minute's open; the ATM option (the last index close before entry, rounded to the step); the nearest
  expiry that does **not** expire that day (so a day when both the weekly and monthly expire is left out, as in
  R131-R138). Exits are always by 15:20; there is no expiry-day square-off because the option held never expires that day.
- Entries 09:15-15:00. One trade at a time per index per rule; a call and a put signal in the same minute: neither.
- Exits on the index's minute wicks: the stop first if the stop and target are in the same minute; else 15:20.
- A rule whose entry open is already beyond the stop (or past the target) does not trade.
- 1 lot (today's lots), dated charges, liquidity-aware slippage; also 2x slippage.
- **Random-entry twins:** 100 per trade, entry minute uniform from the trade's own entry minute to 15:00, same day,
  same side, same stop and target distances, same exits. p = (1 + twin totals >= the rule's total) / 101. For S12,
  R132's test instead: random subsets of S1's trades of the same size per fold (2,000).

## 4. Gates

Design 1 Aug 2021 - 15 Mar 2026. Nothing is fitted, so each calendar fold (2023, 2024, 2025, 2026 Q1) is out of sample.
A rule passes design only if all hold:
1. >= 300 trades;
2. average net > 0 at 1x and 2x slippage;
3. beats its random twins, p < 0.05;
4. BH q <= 0.10 over the 12;
5. >= 3 of 4 folds positive;
6. Boss's account stop on 1 lot (all five indices together): max drawdown above -Rs 40,000 and no month below
   -Rs 13,000.

**Holdout 1 Apr - 19 Oct 2026, read once** for all 12 after the design results are committed. It confirms a rule if
the average net is > 0 at 1x and 2x slippage on >= 50 trades. This entry family has not been read on the holdout
before (R131 trend15 used a different rule on the same months).

**Win rate against Boss's 65% rule** is reported for every rule. It is not a gate here (Boss, 29 Oct: 65% is for
Jarvis's own new strategies).

A rule passing design and the holdout is proposed (not built) as a paper arm for Boss to choose. Otherwise it is
rejected.

## 5. Outputs

- `research/hunt/r139_market_structure/`: `SOURCES.md`, `PREREG.md`, `r139.py`, `test_r139.py` (each rule on
  hand-built zigzags, the mirror, the look-ahead guard), `results/design.json`, `results/holdout.json`.
- `research/HUNT_R139_MARKET_STRUCTURE.md`: per-rule table (trades, win rate, average win and loss, net per trade,
  total, max drawdown, worst month, p, q), per index and per year for the best rules, and sources vs data.
- Entries `research/experiments/entries/r139-*.yaml`; RESULT.md.
