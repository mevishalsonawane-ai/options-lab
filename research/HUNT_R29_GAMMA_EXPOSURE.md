# R29: gamma exposure (GEX) on expiry afternoons, NIFTY and SENSEX

Written 14 Oct 2026, for Boss: "can you check using gamma exposure?"

- **Pre-registration:** `research/hunt/r29/PREREG.md`, commit `11c265d`, pushed before any outcome was computed.
- **Code, tests and tables:** `research/hunt/r29/` (`results/results.json`).
- **Experiment entries:** `research/experiments/entries/r29-*.yaml` (4 rules, all rejected, plus the descriptive
  check).
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **Gamma exposure does not give a profitable trade. 0 of 4 rules pass**, and the count across R21-R29 is now 0 of 48.
- **What GEX measures.** At 14:00 on each expiry we add up the option open interest × gamma across the chain. That is
  how much the option writers (dealers) are "short gamma": how hard they must chase the market if it moves. The
  research idea is that heavy short gamma makes the afternoon move more.
- **At first it looks like it works.** On 341 expiries (NIFTY 241, SENSEX 100), GEX ranks with "the afternoon moved
  more than the options priced" (rank correlation 0.33, p < 0.001):
  - on the third of days with the most GEX, the median afternoon moved 1.29× what ATM implied vol priced in;
  - on the third with the least, 0.86×.
- **But it is really an implied-vol effect.** GEX is high when implied vol is low (correlation −0.69): low IV makes
  gamma bigger. And low-IV afternoons are the ones that move more than priced (−0.57).
  - Holding implied vol fixed, GEX's own link to the afternoon move is slightly **negative** (−0.11). That fits the
    US finding that big option gamma pins the market a little.
  - So GEX is mostly re-measuring R27's clue: options are good value only when they are priced cheaply against the
    move.
- **The trades lose.** Buying a Rs 15-30 call and put at 14:00 (Rs 1,000 each, half at 2× and step lock):
  - every eligible expiry (264): −Rs 1,03,781 (−Rs 249 per Rs 1,000);
  - X1, heavy short gamma: 66 expiries, −Rs 32,226;
  - X2, US-style negative net gamma: 98 expiries, −Rs 42,909;
  - X3, both: 24 expiries, −Rs 15,385;
  - X4, X1 plus R27's price check: 26 expiries, −Rs 12,807.
  - No GEX version beats random expiries of the same count (p 0.53-0.67).
  - Even on the high-GEX days, where the market *did* move more than ATM priced, the Rs 15-30 wings still lost
    −Rs 422 per Rs 1,000 (on the 69 of those days that had a pair to buy). Wings carry extra premium (the volatility skew), and charges and spread are a large share of
    a Rs 1,000 leg.
- **What to do:** no app change. If anything is worth a future test, it is not GEX itself but the cheap-implied-vol
  condition it stands in for, traded through ATM options rather than wings. That would need a new pre-registered study
  and, given that all past data has now been looked at, a forward shadow log.

## Method (PREREG.md has every detail)

- **One decision per expiry and index, at the 14:00 bar close.**
  - ATM implied vol σ from our Black-Scholes IV of the ATM call and put (`research/brain/bs.py`).
  - **GEX_short** = Σ (call OI + put OI) × gamma × S² × 0.01, treating writers as short both sides, the Indian
    convention.
  - **GEX_net** = Σ (call OI − put OI) × gamma × S² × 0.01 (US convention).
  - Both are divided by their median over the index's previous 20 expiries.
  - "High" and "low" are cut at the 2/3 and 1/3 quantiles of all earlier expiries (expanding, point-in-time).
  - **Three implementation choices the pre-registration did not spell out** (fixed before any outcome was read):
    - GEX_net can change sign, so it is divided by the median *size* (absolute value) of the previous 20 nets.
    - A regime cut needs at least 10 earlier scaled values. That costs the first 10 decision days of each index.
    - The 20-expiry scale needs GEX on all of the last 20 expiry slots. After a gap (SENSEX's 45 days without OI) an
      index waits 20 expiries before deciding again.
- **Outcome:** the move ratio = realized variance from 14:00 to 15:25 ÷ the variance ATM implied vol priced in.
- **Trade:** the CE and PE nearest Rs 22.5 within Rs 15-30, Rs 1,000 each, R26's engine with real costs.
- **SENSEX:** 45 expiries of 2023-24 have no OI in the lake (no GEX, no decision). That leaves 100 SENSEX decision days.

## Results

| rule | expiries (legs) | hit % | ≥ 4× / 10× legs % | net Rs | per Rs 1,000 | worst day | max DD | losing streak | net w/o best day | 2× spread | random same-size sets | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| B0 every eligible day (baseline) | 264 (401) | 29.9 | 7.2 / 1.5 | −1,03,781 | −249 | −4,140 | −1,03,781 | 18 | −1,08,488 | — | — | — | — |
| X1 heavy short gamma | 66 (86) | 22.7 | 5.8 / 0 | −32,226 | −394 | −1,870 | −34,644 | 17 | −33,861 | −32,515 | −31,710 | 0.53 | 1.00 |
| X2 negative net gamma | 98 (153) | 28.6 | 7.2 / 2.6 | −42,909 | −273 | −3,061 | −43,178 | 10 | −47,616 | −43,360 | −40,660 | 0.62 | 1.00 |
| X3 both | 24 (35) | 16.7 | 8.6 / 0 | −15,385 | −504 | −1,691 | −15,385 | 8 | −16,490 | −15,506 | −12,789 | 0.67 | 1.00 |
| X4 X1 + price check | 26 (39) | 19.2 | 7.7 / 0 | −12,807 | −420 | −1,484 | −12,807 | 10 | −14,021 | −12,911 | −10,863 | 0.65 | 1.00 |

- **Held to 15:05 instead (E1):** B0 −Rs 1,09,556, X1 −Rs 43,193, X2 −Rs 28,034, X3 −Rs 16,572, X4 −Rs 21,955.
- **Every R25 block with trades loses**, for every rule (X4 has none in 2021-22). X1: 2021-22 had 17 expiries and
  −Rs 10,865; 2023-26 had 49 and −Rs 21,361.
- **BH family: 48.** The smallest q is 0.096 (an earlier study's rule); every R29 q is 1.00.

**GEX and the afternoon move (341 decision days; the move ratio averages 1.37, median 1.00, and is above 1 on 49%):**

| GEX_short tercile | days | move ratio mean / median | days with a pair | the pair, per Rs 1,000 (E3) |
|---|---|---|---|---|
| lowest | 114 | 1.04 / 0.86 | 105 | −268 |
| middle | 113 | 1.18 / 0.98 | 90 | −206 |
| highest | 114 | 1.89 / 1.29 | 69 | −422 |

- GEX_net is weaker: rank correlation −0.14 with the move ratio, p 0.009.
- By index, GEX_short vs the move ratio: NIFTY 0.28 (241 days), SENSEX 0.55 (100 days).
- **The confound:** GEX vs ATM IV −0.69, ATM IV vs the move ratio −0.57. Holding IV fixed, GEX vs the move is −0.11.

## Honest notes

- **Gamma here is computed at one implied vol (ATM) for every strike.** The real smile would give far strikes a little
  less gamma. Ranking GEX within an index's own history removes most of that.
- **The confound analysis** (`confound()` in `r29gex.py`, written to `results.json`) **was done after seeing the
  descriptive table.** It is reported as a description, not a test.
  But it explains the pattern simply, and it matches the US evidence that more gamma means calmer markets, given the
  price of volatility.
- **Whether dealers in India delta-hedge** like US market makers is unknown. If writers don't hedge, GEX would have
  little mechanical effect, which is consistent with what we see.
- **The pair uses Rs 15-30 wings,** as in Boss's earlier frameworks. An ATM straddle on low-IV days was NOT tested here
  and would need its own pre-registration.

## Checks run

- `python -m pytest research/hunt/r29/test_r29.py -q` passes 6 tests. They cover:
  - GEX sign and size;
  - the look-ahead guard on the IV and GEX reads (future blanked or absurd);
  - the move ratio and the pair pick;
  - the regime selection;
  - the scaling and the regime cuts reading earlier expiries only (a later change moves nothing earlier; a GEX gap).
- R26-R28 tests, the brain tests and the catalog check still pass. `python research/experiments/validate.py`, and
  `python3 research/knowledge/build_pack.py` then `--check`.
