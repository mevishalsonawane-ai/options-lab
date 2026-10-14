# R29 pre-registration: gamma exposure (GEX) and expiry-afternoon option buying (NIFTY, SENSEX)

Written 14 Oct 2026, BEFORE any R29 outcome was computed. Committed and pushed on its own; nothing changes afterwards.
Boss (14 Oct): "can you check using gamma exposure?"

## The idea (literature)

- When option writers (dealers) are short a lot of gamma, their delta hedging chases the market, which can make moves
  bigger ("gamma fragility": Barbon & Buraschi; Baltussen et al. 2021 find intraday momentum stronger when dealer gamma
  is more negative). Long gamma does the opposite (pinning: Ni, Pearson, Poteshman 2005).
- Counter-evidence: high 0DTE OI-gamma went with LOWER intraday vol in the US (Dim, Eraker, Vilkov).
- Sign convention is uncertain in India (retail are net buyers of both calls and puts, so writers are short both). Both
  readings are tested.
- R27's G1 (pin distance + momentum) lost −Rs 6,273 on 48 trades. R29 tests GEX as a REGIME (which days), not a timing
  trigger.

## Data

- NIFTY 271 and SENSEX 175 weekly expiries (R26 day files; R25 loader). SENSEX has no OI on 45 expiries of 2023-24:
  those days get no GEX and no trade.
- Decision time: the **14:00 bar close** (one decision per expiry and index).

## Measures at 14:00 (point-in-time; rows ≤ the 14:00 bar)

- Spot S, minutes left to 15:30, the ATM implied vol σ (our Black-Scholes IV of the ATM call and put, averaged;
  `research/brain/bs.py`).
- Per stored strike k with OI (R26's forward-filled OI, stored chain = a bar in the last 5 minutes): Γ_k = BS gamma at
  σ.
- **GEX_short** = Σ_k (OI_call,k + OI_put,k) × Γ_k × S² × 0.01 (writers short both sides; the size of their short gamma).
- **GEX_net** = Σ_k (OI_call,k − OI_put,k) × Γ_k × S² × 0.01 (US convention: dealers long calls, short puts; more
  negative = more fragile).
- Both are scaled by the index's own history: the day's value ÷ the median of the same measure on the previous 20
  expiries of that index (needs ≥ 20 earlier expiries with OI; earlier days get no decision). This removes lot-size and
  level changes.
- Regime cuts: the 2/3 quantile (GEX_short) and the 1/3 quantile (GEX_net) of the scaled value over ALL EARLIER
  expiries of that index (expanding, point-in-time).

## Outcomes (hindsight; never inputs)

- **Move ratio:** realized variance of 1-minute log returns from 14:00 to 15:25 ÷ (σ² × the same time span). Above 1 =
  the afternoon moved more than options priced (good for buyers).
- **Trade:** at the 14:00 close, buy the CE and the PE whose price is nearest Rs 22.5 within Rs 15-30 (R25 eligibility),
  Rs 1,000 each (whole lots, at least 1), held with **E3** (half at 2×, step lock, else 15:05); E1 (hold to 15:05)
  reported. R26's engine; R25 costs and fills.

## Rules (all non-directional pairs; nothing fitted beyond the expanding cuts)

| id | buy the pair at 14:00 when |
|---|---|
| X1 | GEX_short (scaled) ≥ its expanding 2/3 quantile (dealers very short gamma) |
| X2 | GEX_net (scaled) ≤ its expanding 1/3 quantile (US-convention negative gamma) |
| X3 | both X1 and X2 |
| X4 | X1, and buy only the legs whose iv_rv ≤ 1.0 (R27's price check, R27's code) |
| B0 | every eligible expiry (the baseline, not a rule; not in the BH family) |

## Tests and gates

- **Descriptive:** the mean and median move ratio, and the pair's mean net per Rs 1,000, by GEX_short tercile and by
  GEX_net tercile (terciles over all decision days, descriptive only); the rank correlation of each scaled GEX with the
  move ratio, with its p-value.
- **Control (p):** 1,000 random sets of the same number of expiries drawn from the eligible days of the same index
  (B0's pool), same pair and exit; p = share with net ≥ the rule's.
- **BH family: R21 9 + R25 9 + R26 10 + R27 11 + R28 5 + R29 4 = 48.**
- **Gates (all):** ≥ 30 trades (pairs count as one); net > 0; net > 0 in each R25 block that has trades (2021-22,
  2023-26); BH q < 0.05; net > 0 at 2× the half-spread; net > 0 without the best day; max drawdown smaller than
  Rs 40,000.

## If a rule passes

Paper only, after Boss's go-ahead (CLAUDE.md); never live by itself. No app change in R29.
