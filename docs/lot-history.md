# Lot-size history, and how it was verified

Every rupee figure in this repo is an index-point figure multiplied by a lot
size. NIFTY's lot changed three times inside the backtest sample. Before this
was fixed, `cli.py` pinned 65 for all 170 sessions, so pre-2026 rupee figures
were scaled wrong — the worst single loss was reported as **−Rs 2,507** when
the session in question traded at lot 25 and actually lost **−Rs 979**.

That is a 2.6x error on the number position sizing is driven by.

## The table

| Underlying | First session at this lot | Lot |
|---|---|---|
| NIFTY | 2024-01-01 | 50 |
| NIFTY | 2024-04-26 | 25 |
| NIFTY | 2024-12-27 | 75 |
| NIFTY | 2025-12-31 | 65 |
| BANKNIFTY | 2024-01-01 | 15 |
| BANKNIFTY | 2025-01-31 | 30 |

## Source

NSE UDiFF F&O bhavcopy, column `NewBrdLotQty`:

```
https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv.zip
```

Reproduce with `python tools/verify_lot_history.py`. It downloads the bhavcopy
for each date in the table plus the preceding session, and asserts the lot
changes exactly there.

## Two things that make this subtler than it looks

**A bhavcopy can carry two lot sizes at once.** NSE revises lots
prospectively: the new lot attaches to contracts *introduced* after the
circular, while already-listed far expiries run out their old size. On
2024-12-27 the book held 198 NIFTY option contracts at lot 25 and 1,361 at lot
75 simultaneously. Taking the day's modal lot would pick whichever series
happened to have more strikes listed — an artifact of strike-range rules, not
of what is being traded. The table therefore records the lot on the **nearest
expiry**, which is the series this strategy trades.

**Coverage starts 2024-01-01, and the sample starts 2023-01-05.** The UDiFF
file is the earliest NSE publication that carries a lot column at all; the
legacy `fo<DD><MON><YYYY>bhav.csv` format has no such field, so 2023 lots
cannot be verified from the same source. Those 51 sessions are **skipped**
under `--lot dated` rather than run at an assumed 50.

That is deliberate. `lots.lot_size_on` raises `NoLotSize` for uncovered dates
instead of extrapolating backwards, so the sample shrinks visibly in the
skipped count rather than the rupee totals being quietly wrong. Forward
extrapolation past the last row *is* allowed — the current lot is current until
NSE changes it, and `monitor.check_lot_size` exists to catch that change.

## The table is the FALLBACK, not the primary source

NSE applies a lot change to contracts **introduced** after it. A monthly listed
before the change keeps the old lot until it expires. On 2025-01-30 every NIFTY
contract expiring that day carried lot **25**, while the rest of the book was
at 75 � so the date table's answer is wrong for that session by a factor of
three, and a position sized on it would have been three times too large.

The chain settles it. Upstox publishes open interest already multiplied by the
lot, so the most frequent non-zero OI move *is* one lot.
`lots.lot_from_chain` takes the gcd of the five most frequent move sizes and
then requires it to divide at least 95% of all moves.

Ranking by **count**, not by share of volume, is load-bearing: a real session
has ~4,000 distinct move sizes and the top five are only 11% of the mass � but
they are 65, 130, 195, 260, 325, every one a whole number of lots. A plain gcd
over every move is exact and therefore fragile: on 2026-03-30 a handful of
stray ticks collapse it to 5 against a true lot of 65. The estimator is stable
for any k from 1 to 12.

Across the 170 cached sessions it agrees with the NSE table on 169 and differs
on exactly one � 2025-01-30, where bhavcopy confirms the chain, not the table.
It also covers all 51 sessions of 2023 that the table cannot, so nothing is
skipped any more.

`run_backtest(..., lot_size=DATED_LOT)` therefore asks the chain first and
falls back to the table only when the chain carries no evidence.

The eras derived from open interest alone reproduce the NSE table exactly:

| lot | sessions | first | last |
|---|---|---|---|
| 50 | 68 | 2023-01-05 | 2024-04-25 |
| 25 | 36 | 2024-05-02 | 2025-01-30 |
| 75 | 51 | 2025-01-02 | 2025-12-23 |
| 65 | 15 | 2026-01-06 | 2026-04-13 |

## Effect on the headline

|  | pinned lot 65 | dated lots |
|---|---|---|
| sessions | 170 | 119 (51 skipped, all 2023) |
| win rate | 97.65% | 97.48% |
| mean/trade | +Rs 394 | +Rs 405 |
| **worst loss** | **−Rs 2,507** | **−Rs 979** |
| cost as % of credit | 13.1% | 13.8% |

The average was already about right. The tail was not, and the cost fraction
moved because Rs 20/order is flat — a lot change is not a clean rescale.
