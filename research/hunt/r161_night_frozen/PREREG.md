# R161 pre-registration: does the frozen index print change Night (R3)'s 15:20 decision?

Written 11 Nov 2026, before any number below was computed. Boss asked for it ("Ok") after R160 noted the problem.

## 1. The question

`NightRules` decides at 15:20 from the index's **15:19 close**. That close gives the close location in the day's range, the
day's move and the 1-ITM strike.

Since the closing auction began (3 Aug 2026), NIFTY's and BANKNIFTY's prints stand still from 15:15 to 15:28 in our data
(R160, PREREG 1). So Night's "15:19 close" is really the 15:14 value, while futures and options keep trading.

**Question:** how often would Night's index-based part, or its strike, differ if it read the live market at 15:19
instead of the frozen print? And what is that worth?

What was known before writing this:

- R160's finding that the print is frozen on all 54 post-auction days.
- R160's data (the same lake files and sha256, R160 PREREG 2).
- No Night-specific number.

## 2. Two readings of "the index at 15:19"

- **A, the app's reading:** the index's 15:19 bar close. Since 3 Aug this is the 15:14 value.
- **B, the live reading:** the median over 15:17-15:19 of the option-implied index, minus that day's 14:30-14:59 basis.
  - It uses R160's `measure.implied_minutes` and `measure.basis` unchanged.
  - Check: the same with futures (POST only; the listed contract with the most volume that day).
- **The day's range for B:** the index high and low to 15:14, widened to include B if B lies outside them. A uses the
  index range to 15:19, as the app does.

From each reading:

- the day's move vs the previous official close;
- the close location `loc`;
- Night's **index condition**:
  - **+1** if `loc >= 0.6` and the move `>= 0`;
  - **-1** if `loc <= 0.4` and the move `<= 0`;
  - **0** otherwise.
- the ATM strike, `floor(x / step + 0.5) x step`, with a step of 50 (NIFTY) or 100 (BANKNIFTY).

**Left out, with reasons:**

- **Breadth (stock prices).** CAS stocks are also in the auction from 15:15, so A and B would read the same stale
  breadth. The lake has no 214-stock minute data after 13 Oct.
- **OI build-up.** Options still trade, so it is unaffected.

So R161 measures only the part of Night that the frozen print can change.

## 3. Periods

| label | entry days |
|---|---|
| POST | 3 Aug - 16 Oct 2026 |
| PRE | 1 Jan 2025 - 31 Jul 2026 |

- In PRE the print is live, so A is the real 15:19 index.
- A vs B disagreements in PRE therefore measure how noisy B itself is: the floor.

## 4. Measures

**For each index (NIFTY, BANKNIFTY) and period:**

1. the mean and 90th percentile of |A - B| in bps;
2. the **flip rate**: the share of nights where the index condition differs between A and B, split into "A trades, B
   would not", "B trades, A would not" and "opposite sides";
3. the **strike rate**: the share of nights where the ATM strike differs.

**The value of a flip (POST only, descriptive):**

- **Nights priced:** those where the condition differs.
- **Contract:** each side's 1-ITM option, using Night's contract rules as R146 priced them.
  - It is bought at the 15:20 open and sold at the 09:16 open next session.
  - Charges: app fills, dated charges and the 0.16% half-spread, all from R160's `trade.py` / R146.
- **Skipped:** expiry days and nights held into expiry, following Night's rule.
- **Not modelled:** breadth and OI are not applied, because they cannot be measured here (section 2).
- **Limit:** this is an upper bound on what a flip can matter, not Night's actual P&L.

## 5. The bar (decided now)

**R161 recommends a SHADOW change only if both hold:**

- the POST flip rate is at least 5 percentage points above the PRE flip rate, on either index;
- POST |A - B| is clearly bigger than PRE: the mean at least twice as large.

The shadow change would make Night read the futures / option-implied index at 15:19 and log the difference, behind a
switch that is off by default (CLAUDE.md: anything that could change live trades starts in shadow).

Otherwise R161 recommends no change.

**No significance test is run.** The question is how often the input differs, not a prediction. The counts are given
with their sample sizes.

R161 changes nothing in the app.

## 6. Look-ahead guard

- B uses only bars up to 15:19 of the day.
- A test changes every bar after 15:19, and the next day, and checks that A, B and the condition do not change.

## 7. Deviations

Any change after this file is pushed goes into `NOTES.md`.
