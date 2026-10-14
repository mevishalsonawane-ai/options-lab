# R35 pre-registration: the 1-minute Dual-Supertrend hero framework (NIFTY and SENSEX expiries)

Written 14 Oct 2026, BEFORE any R35 trigger or outcome was computed. Committed and pushed on its own; nothing here
changes afterwards. Boss's spec (14 Oct, two messages) is followed literally; where it was silent, the choice is fixed
here.

## The rule, as specified

- **Window:** triggers at 1-minute bar closes 14:00 .. 14:45. One trade per index per expiry: the first trigger.
- **Option:** the nearest OTM strike at the trigger minute: ATM+1 CE (calls), ATM-1 PE (puts). (The spec names "the
  option premium chart" but no strike; R26's ATM+1 is used.)
- **Supertrends on the option's own 1-minute premium** (the strike's own bars of the day up to t; untraded minutes are
  skipped): ST(7, 3) and ST(10, 2): Wilder ATR over n bars, bands = (high + low) / 2 ± m × ATR with the standard
  final-band recursion; green when the close is above the previous final upper band, red below the previous final lower
  band, else unchanged. **Trigger: both green at t and not both green at the strike's previous bar** (the "flip to
  confluence"). For a PUT, the same on the PE's premium (the PE premium rising).
- **Volume:** the option's volume at t ≥ 2 × the 10-bar EMA of its volume up to the previous bar.
- **Index VWAP filter:** a call needs spot > the index's average price since the open; a put needs spot < it. The lake's
  index minutes carry no volume, so this is the **time-weighted** average of (high + low + close) / 3 since 09:15 (a
  TWAP standing in for VWAP; stated in every table).
- **Heavyweights:** HDFC Bank, Reliance and ICICI Bank (the top 3 of both NIFTY and SENSEX), from the lake's
  `equity_1m`: the trigger minute's bar of all three must close above its open (calls) / below its open (puts). A
  missing bar fails.
- **Size:** lots = floor(2,000 / (fill × lot)); **0 lots = skip** (the spec's floor rule). Whole lots only.
- **Exit:** E3, Boss's step lock: sell half at 2×, then once the best high reaches m× the fill the lock is (m − 1)×; a
  minute close at or below the lock sells at the next open; else 15:05. (R26's engine; with 1 lot the 2× sale is the
  whole position.)
- **Costs and fills:** R25's (Rs 20 an order, STT, exchange, SEBI, stamp, GST; next-bar open + 5 bps + 1/2/4-tick
  half-spread; entry limit 1.2 × the signal price + 0.10 for 3 minutes, never chased).

## Data

- NIFTY 271 weekly expiries (5 Aug 2021 – 6 Oct 2026) and SENSEX 175 (19 May 2023 – 8 Oct 2026): R26's day files.
- `equity_1m` (Drive lake) for HDFCBANK, RELIANCE, ICICIBANK, 2021-2026.
- Nothing is fitted (the spec gives every parameter), so **every expiry is out-of-sample** for this rule. R25's blocks
  (2021-22 / 2023-26) and every year are reported.

## Variants (each changes one thing from S1)

| id | change |
|---|---|
| S1 | the rule above (E3 exit) |
| S2 | exit E1: hold to the 15:05 square-off |
| S3 | without the heavyweight check (does the new ingredient add anything?) |
| S4 | plus R34's price check: skip unless the option's implied vol ≤ 1.0 × the forecast realized vol (R34's `iv_rv`, computed point-in-time with R34's code; the seasonal factor from earlier expiries of the same index) |
| S5 | the other chart reading: the two Supertrends on the INDEX 1-minute chart (both flip green → call, both flip red → put); the rest as S1 |

## Controls, family, gates

- **Random-time twins (1,000 sets):** each trade re-entered at a uniformly random bar close 14:00 .. 14:45 of the same
  expiry, same direction, the nearest OTM strike then, the same size rule (skip at 0 lots) and exit.
- **BH family: R21 9 + R25 9 + R26 10 + R34 11 + R35 5 = 44.**
- **Gates (all must hold):** ≥ 30 trades; net > 0; net > 0 in every R25 block that has expiries; BH q < 0.05; net > 0 at
  2× the half-spread; net > 0 without the best day; max drawdown smaller than Rs 40,000.
- Also reported: by index, direction and year; skips for "1 lot above Rs 2,000"; hit rate and ≥ 2× / 4× / 10× shares.

## If a rule passes

Paper only, after Boss's go-ahead (CLAUDE.md). Nothing live. R35 changes no app code.
