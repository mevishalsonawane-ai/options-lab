# R30 pre-registration: the gamma-flip hero trigger (NIFTY and SENSEX expiries)

Written 14 Oct 2026, BEFORE any R30 trigger or outcome was computed. Committed and pushed on its own. Boss's spec (two
messages, 14 Oct) is followed literally; choices it left open are fixed here.

## The rule

- **GEX profile at 13:45** (the 13:45 bar close; rows ≤ it only). For each stored strike i with OI (R26's
  forward-filled OI, stored chain = a bar in the last 5 minutes):
  GEX_i(S) = (call OI_i − put OI_i) × Γ(S, K_i), with Black-Scholes Γ at the ATM implied vol of 13:45 (our IV of the ATM
  call and put, averaged; `research/brain/bs.py`) and the time left to 15:30. Net GEX(S) = Σ_i GEX_i(S).
- **The flip:** Net GEX(S) on a grid of S from spot × 0.97 to spot × 1.03 in steps of 0.05% of spot; the flip is the
  zero crossing (linear interpolation) nearest the 13:45 spot. No crossing on the grid → no flip → no trade that day
  (counted and reported). The flip is fixed for the afternoon.
- **Window:** triggers at 1-minute bar closes 14:00 .. 14:45; one trade per index per expiry, the first trigger.
- **Call trigger at row t (all in the same bar):**
  1. spot's close at t > flip AND spot's close at the previous row ≤ flip (the break happens in this bar);
  2. spot > the index TWAP since 09:15 (R28's stand-in for VWAP: the index has no volume);
  3. the ATM+1 CE's volume at t ≥ 2 × the 10-bar EMA of its earlier volume (R28's code);
  4. HDFCBANK, RELIANCE and ICICIBANK (NSE tickers, lake `equity_1m`): each bar at t closes above its open.
  Put trigger: the mirror (close < flip after ≥ flip; below the TWAP; ATM-1 PE's volume; all three close below open).
- **Size:** lots = floor(2,000 / (fill × lot)); 0 → skip.
- **Exit (T2, the spec's trail):** once the best high reaches 2× the fill, the lock is the fill (breakeven); at each
  further doubling (4×, 8×, 16× ...), the lock becomes 50% of that level (4× → 2×, 8× → 4×). A minute close at or below
  the lock sells everything at the next open; else 15:05. No partial sales.
- **Costs and fills:** R25's (charges, next-bar open + 5 bps + 1/2/4-tick half-spread, entry limit 1.2 × signal +
  0.10 for 3 minutes, never chased; targets need a confirmed price).

## Variants (one change each)

| id | change |
|---|---|
| G1 | the rule above |
| G2 | also require the destination side to be negative gamma: Net GEX at the trigger-minute spot (13:45 OI) < 0 |
| G3 | exit E1: hold to 15:05 |
| G4 | without the heavyweight check |

## Data, controls, family, gates

- NIFTY 271 and SENSEX 175 weekly expiries (R26 day files); SENSEX has no OI on 45 expiries of 2023-24 (no flip, no
  trade). Nothing fitted: every expiry is out-of-sample; R25 blocks and years reported.
- **Note on signs.** The spec's formula (calls minus puts) is the US convention. Under the Indian reading (writers short
  both sides) net GEX never changes sign, so no flip exists; that reading cannot be tested as a flip rule.
- **Random-time twins (1,000 sets):** same expiry and direction, a uniformly random bar close 14:00-14:45, the nearest
  OTM strike then, the same size rule and exit.
- **BH family: 48 (R21-R29) + 4 = 52.**
- **Gates (all):** ≥ 30 trades; net > 0; net > 0 in every R25 block with trades; BH q < 0.05; net > 0 at 2× spread;
  net > 0 without the best day; max drawdown smaller than Rs 40,000.
- Reported too: days with a flip in range, how often spot crossed it in the window, the funnel of conditions.

## If a rule passes

Paper only, after Boss's go-ahead (CLAUDE.md); never live by itself. No app change in R30.
