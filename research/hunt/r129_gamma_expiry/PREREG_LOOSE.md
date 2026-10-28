# R129 amendment 1 (pre-registration): a looser version of the gamma expiry strategy

Written 28 Oct 2026, after R129's result (the stated rule fired 2-6 times in 917 expiry days) and before any number of this
version was computed. Boss: *"Try a looser version of the gamma strategy"*.

**Honest limit:** this is a second look, chosen knowing that the first version barely traded. The loosening below is fixed
here, once, from the R129 filter counts only, never from trade results. This family of 4 gets its own BH, and the write-up
also gives q over all 10 R129 versions.

## What is loosened (each from the R129 filter that blocked the most days)

| R129 rule (as stated) | loose rule |
|---|---|
| The heaviest OI wall needs a 3x imbalance (blocked 517 days) | **The nearest wall the index can break, 2x:** the nearest strike at or above spot (within +5 strikes) whose call OI ≥ 2× its put OI is the bullish wall; the nearest at or below spot (within −5) whose put OI ≥ 2× its call OI is the bearish wall. Read at 13:45. Both may exist; whichever breaks first is traded. |
| India VIX above 12 (blocked 194 days) | **Dropped.** Trades are reported by VIX above / at or below 12. |
| A 5-minute close beyond the 13:15-13:59 box AND the wall, 14:00-15:00, still on the near side at 13:59 (blocked 184) | **A 5-minute close beyond the wall and beyond the TWAP, from 13:50 to 15:00** (bars closing 13:50 … 15:00). There is no box; the index must be on the near side of the wall at 13:45. |
| All three heavyweights beyond their VWAP | **2 of the 3** (same stocks per index; MIDCPNIFTY none) |
| The ATM±1 option at ₹15-30 at the setup | **The nearest out-of-the-money option (1 to 8 strikes) priced ₹10-40 at the setup** |

Unchanged:
- the Supertrend(7,3) + (10,2) trigger with the option's volume over its 10-minute mean, triggers to 15:00;
- the 4x target, the Supertrend-flip exit and the cap;
- one trade per index per expiry day;
- data, money, twins and gates (R129 PREREG sections 2 and 5).

## The 4 versions (BH family)

| id | trigger | cap |
|---|---|---|
| L1 | dual Supertrend on the option's premium chart (as written; NOT the default, labelled) | 15:25 |
| L2 | dual Supertrend on the index (default) | 15:25 |
| L3 | as L2 | 15:05 (the app's expiry square-off) |
| L4 | no trigger: buy at the next minute's open after the setup bar (index breakout only; default); out on an index Supertrend flip, 4x or the cap | 15:25 |

## Reports

Everything in R129's report list. Also:
- by VIX above / at or below 12;
- by the wall's distance from spot;
- the claim check (the index move after the setup to 15:20) on these setups.
