# R160 pre-registration: does the closing-auction gap get corrected the next morning?

Written 11 Nov 2026, before any outcome number was computed. It is committed and pushed before the study code runs on the
data, and its sha256 is stamped in every result file.

**Numbering:** the brief asked for R156 "if free". R155-R159 are already used on branches (`claude/r155-scalp-filters`,
`claude/r156-scalp-network`, `claude/r157-scalp-network-plus`, `claude/r158-beat-breadth-donchian`,
`claude/r159-r151-plus-filters`). This study is **R160**, on branch `claude/r160-cas-gap`.

## 0. Boss's question (11 Nov 2026)

"Using [the Closing Auction Session] can we predict the next day opening?" ... "if there is any issues, market tries to
correct itself" ... "prices tries to fill the gap".

## 1. What was looked at before writing this (disclosed)

Only data properties, no outcome:

- Where the data ends. The Drive lake (`gdrive:IraAlgo-data-lake`, built 13-20 Oct 2026) holds index, futures and option
  minutes to **19 Oct 2026**. Every file is cut at the 15:29 bar (the lake build drops bars outside 09:15-15:29). **No
  bar from 15:30-15:39 exists** for options or futures, although F&O now trades to 15:40.
- Dhan's token is expired (HTTP 401, DH-901, tried 11 Nov), so nothing after 19 Oct can be added from Dhan.
- Index futures minutes exist only for contracts still listed on 19 Oct (Oct / Nov / Dec 2026 expiries, from late July).
  So futures cover the post-CAS period only, never the pre-CAS control.
- Post-CAS (3 Aug - 19 Oct 2026, 54 sessions), NIFTY, BANKNIFTY and SENSEX prints all stand still from 15:15 to 15:27 on
  all 54 days. The 15:29 bar's close equals the official daily close on 54/54 days (NIFTY, BANKNIFTY) and 51/54
  (SENSEX). Before 3 Aug the 15:29 bar never equals the official close (the old 15:00-15:30 VWAP close).
- One day's bars were printed to understand the layout (NIFTY 15 Sep 2026, an expiry day): official close 23,118.60, frozen
  print 23,172.35, Oct futures 23,346 -> 23,330 over 15:14-15:29. This is one anecdote; it is not a test.

## 2. Data (sha256 of the files used)

```
658f6bce39cfd8ebde1413645b43d50234c432c4d0df0d9ac8b09c22cd877048  index_1m/index_1m_NIFTY_2025.parquet
b2240c709b4c82debeb96b18ca9e0306f8832cac5a5c8693db466bc1e814112d  index_1m/index_1m_NIFTY_2026.parquet
a40331f17e40b6d083589b4eed4ab159d54d1103c088ba74a5af97fa2e42069d  index_1m/index_1m_BANKNIFTY_2025.parquet
268598438087af76a48d2d36fa829f59c32dd66b021d20e17b990d79034d6116  index_1m/index_1m_BANKNIFTY_2026.parquet
0faad1e83302c1198aa6781143cc22f7ce262b87c62671b78c0116b75327eae3  index_1m/index_1m_SENSEX_2025.parquet
f76bf270ffa21886472ddb823e55f63d4158217b9f55e1cce7788b3ab1a351fb  index_1m/index_1m_SENSEX_2026.parquet
a9fc9f993f85876116de7a9f542ba5b73314ffc204724e72b622ab5a536233fb  index_daily/index_daily.parquet
1795d15baeb2af4d9d933bc5aee5aa05d0a444189666c329a4a5d816e96ea0de  fut/fut_1m_live_index.parquet
c7675cab25c8fd3b18e1b5a5e95aaa2d30901dac3d17c55fe1c63c0a265f62e6  opt/opt_BANKNIFTY_MONTH_2025.parquet
51ad57ac7c173ab7595c7e3ba484c8a6e05e4da402c83733e3ef8d6e04f7421d  opt/opt_BANKNIFTY_MONTH_2026.parquet
abc852a2ac5efed056b683d010ec66e8e5f9a6ecef6146f5a3dde542a35f6cd7  opt/opt_NIFTY_WEEK_2025.parquet
532013be4ec6a1859e7f57ebe69f88323c675f45803ff52d949de32663512e8c  opt/opt_NIFTY_WEEK_2026.parquet
a9231a89dc6b0907ec9baccda60162eae8f4f373a6d7c7d2fc3f3f9a7be224e9  opt/opt_SENSEX_WEEK_2025.parquet
559286f4f0fff1c2105ced459cb22ca773645e9e3ec4acd38f0e901887f1a42a  opt/opt_SENSEX_WEEK_2026.parquet
```

Raw data stays out of git (the lake is on Drive). The study reads it from `$R160_LAKE`.

## 3. Periods

| label | entry days (day t) | role |
|---|---|---|
| **POST** | 3 Aug 2026 - 16 Oct 2026 (next open known to 19 Oct) | the test: about 53 nights |
| **PRE** | 1 Jan 2025 - 31 Jul 2026 | the control: same measure under the old VWAP close; about 390 nights |

There is no separate locked holdout: POST is all the post-CAS data there is, and it is read once. The real out-of-sample
check is the forward log (section 9). PRE is a control, not a design set: nothing is tuned on it.

## 4. Measures (all in basis points, bps)

For index U (NIFTY primary; BANKNIFTY and SENSEX secondary) on day t:

- **Option-implied index, per minute m:** `F(m)` = median over ATM offsets -2..+2 of `strike + call close - put close`
  (put-call parity), from the nearest-expiry rolling option file (NIFTY weekly, BANKNIFTY monthly, SENSEX weekly).
  An offset counts only when both its call and put have a close above 0 in that minute; a minute needs at least 3
  offsets.
- **Basis b(t)** = median over 14:30-14:59 of `F(m) - index close(m)` (carry, dividends and parity bias, measured while
  the index still prints). A day needs 10 or more valid minutes.
- **Late fair level L(t)** = median over **15:20-15:27** of `F(m)` minus b(t). These minutes are inside the freeze and
  before any closing print (the index can move at 15:28). A day needs 4 or more valid minutes.
- **Official close C(t)** = the daily close (`index_daily`). **Next official open O(t+1)** = the next session's daily open.
- **Dislocation D = ln(L / C)**: where derivatives traded late vs the official close.
- **Gap G = ln(O / C)**. **Residual R = ln(O / L) = G - D**: the move from the late level to the next open.
- **Late move M = ln(L / I1514)**, where I1514 is the index close of the 15:14 bar (the last continuous print post-CAS).
- Robustness only: `G20` with the index close of the 09:20 bar instead of O; a futures-implied L (POST only: per day,
  the listed contract with the most volume that day, same basis method with futures closes).

**Signal source:** the index, read through its derivatives: option prices are used only as a measure of the index (put-call
parity), with futures as a check. CLAUDE.md allows option data as context. No rule reads an option's own chart for
direction.

## 5. Hypotheses and tests

**H1 (primary, NIFTY, POST):** the next open moves back toward the late level. Test: OLS slope beta of G on D, one-sided
(beta > 0), p from 100,000 permutations of D across nights. Also the sign hit-rate (sign G = sign D on nights with
|D| >= 2 bps) and Spearman rho.

- beta = 1 means the open lands on the late level (the whole auction gap is corrected); beta = 0 means none of it is.
- H1 is judged **supported** only if all three hold: beta >= 0.25, the BH q (section 7) <= 0.10 and the hit-rate >= 55%.

**H1 control:** the same slope in PRE, and beta(POST) - beta(PRE) with a 95% bootstrap interval (10,000 resamples).

- Stated in advance: a positive beta is expected in PRE too, because L is a later price than the old VWAP close.
- If beta(POST) is not clearly above beta(PRE), the effect is "the newest price beats the official close". The auction
  did not create it.

**H2 (does it pay, NIFTY, POST):** the slope gamma of R on D, two-sided permutation p.

- An option bought at 15:2x-15:3x is already priced off the late level L, not off the official close. So a buyer gains
  only if the move from L to the open (R) is predictable.
- gamma > 0: the open goes beyond L (continuation). gamma < 0: it falls back toward the close. gamma = 0: no edge for a
  buyer, even if H1 holds.

**Secondary (a):** the late move M predicting the next open: the slope of G on M and of R on M, with the sign hit-rate
(POST, and PRE as control).

**Secondary (b):** expiry days (POST, NIFTY weekly and SENSEX weekly expiry days; BANKNIFTY monthly expiries).

- Descriptive: |D| on expiry days vs other days, and whether the expiring contract's freeze-implied level differs from
  the official close (the settlement proxy). About 10 NIFTY expiries.
- No pass / fail.

H1, H2 and (a) are repeated for BANKNIFTY and SENSEX, and with G20 and the futures-implied L, as robustness.

## 6. Trade T1 (always computed; judged only as section 8 says)

- **When:** on POST nights with |D| >= 5 bps on NIFTY.
- **What:** buy the ATM call if D > 0 (the expected correction is up from C), the ATM put if D < 0.
  - ATM is the offset-0 strike at entry, from the nearest weekly expiry.
  - 1 lot (65 from 31 Dec 2025).
- **Entry:** the open of the 15:28 bar of day t.
  - This is a proxy: the real entry would be at 15:3x, after the official close is known, and those bars are not in our
    data.
  - The move between 15:28 and the real entry is unknown. It is stated as a caveat; it is not modelled.
- **Exit:** the open of the 09:16 bar next session, same strike and side (R146's convention), falling forward to 09:45 at
  the latest.
- **Skipped:** expiry days (the weekly expires; Night's rule).
- **Costs:**
  - app fills (+-5 bps);
  - dated charges (`Costs('dated')`: Rs 20 an order, STT 0.15% of the sell premium from 1 Apr 2026, exchange, SEBI, stamp,
    GST);
  - a 0.16% half-spread on both legs, and a stress case at 1.5x the spread.
- **Fill guard:** R146's isolated-stray-print guard (20%).
- **Random-side twins:** 2,000. The same nights, with the side drawn at random.
- **Compared with:** R146's Night (R3) holdout, Rs 480 a trade over 84 NIFTY trades.

## 7. Multiple testing

- **R160's family has 10 tests:**
  - H1 x 3 indices;
  - H2 x 3;
  - (a) the slope of R on M x 3;
  - the T1 twin.
- BH is reported two ways:
  - within R160 (m = 10);
  - counting earlier tests at p = 1, as R154 did: m = 2,154 + 10.
    - 2,154 = R154's tally of 1,682 (890 earlier + 792 R154), plus the 472 experiment entries R155-R159 added on their
      branches, counted one test each.
- **The gate uses the second (m = 2,164).**

## 8. The bar (small sample: about 53 nights, about 10 expiries)

- With 53 nights, a one-sided p of 0.05 needs a correlation of about 0.23. Clearing BH with m = 2,164 needs about 0.5.
  So only a large effect can pass. A miss means "not shown", not "proved absent".
- **H1** is supported only under section 5's three conditions.
- **T1 passes only if all of these hold:**
  - H1 is supported and H2's gamma > 0 (an edge beyond L);
  - net > 0 also at 1.5x the spread;
  - twin p <= 0.05;
  - at least 20 trades.
- **Even a pass changes nothing in the app.** It only starts a forward log (section 9).

## 9. The forward log, if anything is promising

If H1 is supported (whatever T1 shows), R160 proposes a shadow forward log in the style of R134.

- **What:** every NSE session from the first one after this study, record D, M, G and R (and T1's would-be trade) with
  this file's definitions, frozen.
- **Data:** this needs the 15:30-15:39 option and futures bars, which only the phone's recorder or a fixed lake build can
  supply.
- **When to decide:** after a fixed **60 new nights**, with the same tests and bar.
- **Nothing before 60 nights:** no paper or live use. The CAS rules change on 7 Dec 2026 (section 10), so the log restarts
  its count on that date.

If H1 is not supported, the proposal is only to re-run this file's tests once the lake holds 60+ post-CAS nights with
15:30-15:39 bars.

## 10. Rules in force (to be checked and cited in the report)

- NSE circular 38/2026 (18 Mar 2026) says CAS sets the cash close of stocks with derivatives, and F&O closing prices keep
  their own method.
- SEBI's circular of 15 Oct 2026 (HO/47/11/11(3)2025-MRD-POD2/I/23843/2026) moves CAS to 15:30-15:40 and expiry
  settlement to the 30-minute VWAP, from 7 Dec 2026.
- The report states what applied in POST and what changes after 7 Dec 2026.

## 11. Look-ahead guard

- Everything on day t (L, b, D, M, the trade's side and entry) uses only bars up to 15:28 of t. C and O are outcomes.
- A test changes every bar after 15:27 of day t and every bar of t+1, and checks that L, b and M do not change.
- A second test checks that the trade's side does not change when t+1 changes.

## 12. Deviations

Any change after this file is pushed goes into `NOTES.md` with its reason and whether it came before or after a result.
