# R144b pre-registration: why Liquidity 15+5, the ORB family and Solo 2.0 lose money, and filters to skip those trades

Written 1 Nov 2026, before any R144b number. Research only: **nothing in the app or any arm changes.** One of four
parallel studies (R144a-d) on the same question; R144b owns the arms below.

Boss, 1 Nov: *"Find all the lost trades in indian market from our study for current arms, and find what all makes it
lose money ... find patterns for each arm, so it could help us to ignore those trades."*

## 1. The arms and how each one's trades are rebuilt (no rule is changed)

| arm | engine (unchanged) | indices | notes |
|---|---|---|---|
| **LIQ** Liquidity 15+5 | R115 / R101 units and walk (`r115study.units("LIQ")`, `walk`), R143's old store for Dec 2022 - Nov 2024 | BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5 (R101 `LIQ_BOOKS`) | one arm, pooled; each index also reported |
| **ORB** | R3's engine (`r3engine.run`, `baseline("orb")`) on R3's session store | BANKNIFTY (the app's index) | the other indices reported, not gated |
| **ORB Fresh** | `baseline("orb_fresh")` | BANKNIFTY | as above |
| **ORB Sweep** | `baseline("orb_sweep")` | BANKNIFTY | as above |
| **Range Fade** | `baseline("range_fade")` | BANKNIFTY | as above |
| **Solo 2.0** | R57's candidates (`r57setups.candidates`, S1-S3 + S4, no C4) scored by the **frozen app model** `results/models/A2.json` (threshold 0.4871, `>=`), first taker each day | NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY | the model was trained on Nov 2024 - Mar 2026: its design trades are in-sample and flattered; its unseen years are truly out of sample |

- **Parity first:** each rebuilt arm must reproduce its published count and net on its published window (LIQ: R101
  design 881 trades / final 357; ORB family: R3 `rebuild.json`; Solo 2.0: R57's final 128 trades, +Rs 99,172, within
  the difference a frozen model vs monthly refits makes, which is reported). An arm that fails parity is reported as
  "not rebuilt" with the reason, and gets no filter test.
- **An arm that cannot be rebuilt** for a period (no store, no option data) is said so plainly; it is not guessed.
- 1 lot, each date's own lot, R17 fills and dated charges (each engine's own). A loser is net < 0.

## 2. Periods

| name | dates | role |
|---|---|---|
| **unseen** | 1 Dec 2022 - 19 Nov 2024 | test (R143's never-used years; Sep - Nov 2022 warm-up only) |
| **design** | 20 Nov 2024 - 31 Mar 2026, halves H1 (to 31 Mar 2025) and H2 | anatomy and the choice of filters |
| **final** | 1 Apr - 13 Oct 2026 | test, read once (`results/FINAL_READ` flag) |

The final months were already read by R140 / R141 for LIQ and by R3 / R57 for the others; here they are a test only
for the new filters, which are chosen without them.

## 3. Features at entry (all read from bars closed before the entry minute; the look-ahead guard test checks this)

Signal source for every feature: the underlying index (R3's 1-minute session store), its options as context, VIX.

- **Price action:** push into the entry (last 3 five-minute closes, ATR, the trade's way); the signal bar's range / ATR;
  distance beyond the previous day's high (calls) or low (puts) / ADR; stretch from today's open / ADR; distance from
  the day's time-weighted average price / ADR (**TWAP, labelled: the index has no volume, so no true VWAP**); the
  09:15-09:30 opening-range width / ADR; the gap the trade's way / ADR; yesterday's range / ADR; the 60-minute trend
  the trade's way / ADR; earlier fake pokes today (R140's A11); entry minute; sessions to expiry.
- **Volume:** the option band's volume so far today vs its 20-session median at the same minute; the traded option's
  last 5-minute volume vs its own 5-minute average today (the signal-bar spike). **Futures volume: not in the store,
  not computed.**
- **Liquidity:** the entry premium; zero-volume minutes of the traded option in the last 15 (an illiquidity proxy);
  the traded contract's OI. **Bid-ask spread and depth: the lake has none, not computed.**
- **OI / options:** the traded contract's OI change since 09:30 (%); band put/call OI ratio and its change since 09:30;
  ATM implied volatility (Black-Scholes from the ATM call and put, 6.5%, calendar days to the next expiry found in the
  store) and its change since 09:30; skew (ATM put IV minus call IV); the ATM straddle / ADR (the expected move vs the
  usual move).
- **Market context:** VIX; its change from yesterday's close; its 30-minute change; the day regime (day's range so far
  / ADR, and how directional it is so far). **Breadth, the futures basis and the US overnight move: not in the stores
  used here, not computed** (R141 / R101 already tested breadth on LIQ).
- **Order flow (PROXIES ONLY, the lake has no order book):** the traded option's tick-direction volume delta over the
  last 15 minutes (sign of each minute's close change times its volume, over total volume); its OI-price quadrant over
  the last 15 minutes (long build-up / short build-up / short covering / long unwinding).

Continuous features are cut into fifths on **design** trades of each arm; categories stay as they are.

## 4. Anatomy per arm (descriptive)

Loser rate; money lost by exit type; bunching (share of losers on index-days where every trade lost, longest losing
streak); the worst 20 trades; the loser rate and average net by fifth of each feature, per period. A slice is
**steady** only if, in every period the arm has (unseen, design, final), it has >= 30 trades, a loser rate above the
arm's and an average net below 0. Each arm's flaws are named plainly.

## 5. Skip filters (chosen from design only; at most 3 per arm)

**Choice rule (fixed now):**
1. Candidates: the lowest or highest fifth of each continuous feature, and each category of a categorical one.
2. Eligible if, in H1 and in H2 separately: >= 30 trades, loser rate >= the arm's + 5 points, average net < 0.
3. Ranked by the design sum of net in the slice (most negative first: the money the slice cost).
4. Taken in that order, up to 3, skipping a candidate whose skipped trades overlap an already chosen one's by > 50%.
5. The cut points are frozen from design. A filter **skips the trade** (no other trade takes its place; for LIQ the
   candidate is dropped and the walk is re-run, as R140). Unknown feature values never skip.

**Tests, separately on the final and the unseen years** (R142's lose-less gates):
1. the sum of losing days is smaller than today's rules;
2. max drawdown, worst day and worst month no worse;
3. the arm still makes money (net > 0);
4. better than random skipping: 2,000 random twins dropping as many trades of the same arm and period; p = share of
   twins whose sum of losing days is at least as good; p <= 0.05;
5. BH q <= 0.10 across every (filter, period) p-value in this group (R144b);
6. >= 40 trades left and >= 30 skipped.

A filter **passes** only if it passes in both periods (where an arm lacks the unseen years, that is said and it cannot
pass). Win rate before -> after is reported against Boss's 65% rule. Every raw p-value goes to
`results/pvalues.json` so the coordinator can apply BH across R144a-d.

**Passing filters are proposed (not built)** as paper switches, off by default, logged in the Autopilot timeline; live
only through shadow first (CLAUDE.md).

## 6. Outputs

`r144b_lib.py` (pure functions), `r144b.py` (adapters and runs), `test_r144b.py`, `results/*.json`,
`research/HUNT_R144B_LOSS_ORB_LIQ.md`, entries `research/experiments/entries/r144b-*.yaml`.

## Amendment 1 (1 Nov 2026, written after the ORB and Liquidity design trades were rebuilt, before any Solo 2.0 number and before any filter was chosen)

1. **Data:** the lake was downloaded from the shared Drive folder `IraAlgo-data-lake` (90 files: index minutes
   2021-2026 for the five indices and VIX, weekly and monthly option files, `index_daily`). The stores were rebuilt
   from it.
2. **Parity, passed to the rupee:**
   - the ORB family on R3's design (BANKNIFTY + NIFTY, Aug 2021 - Oct 2026): ORB 2,489 trades / -Rs 2,23,040; ORB
     Fresh 2,489 / -2,23,812; ORB Sweep 3,156 / -5,05,954; Range Fade 3,512 / -5,10,978, all equal to R3's
     `rebuild.json`;
   - Liquidity on design: 881 trades / +Rs 2,57,249, equal to R101's.
3. **Solo 2.0's frozen model was trained on 21 Nov 2024 - 14 Oct 2026** (the `trained_on` field of `A2.json`), not
   only on the design months as section 1 said.
   - Solo 2.0's design AND final trades are therefore in-sample for the model. Its arm-level numbers in both are
     flattered.
   - Only the unseen years (Dec 2022 - Nov 2024) are out of sample for it.
   - The filter test is unchanged: filters are still chosen on design and tested on final and unseen. A Solo filter's
     final result is read with this caveat. Its unseen result carries the weight.
4. **Liquidity's exit labels** in R101's engine are coarse: `rule_exit` covers the index stop, target, failed break
   and new-liquidity exits together. "Money lost by exit type" uses these labels as they are.
