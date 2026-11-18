# R196 pre-registration: enhancing R195's option-selling rules (side study, 18 Nov 2026)

Written 18 Nov 2026, **before any R196 profit or loss was computed**; its hash and time are in `results/PREREG_STAMP.txt`
(the session's git commits are blocked by a permission check, so the stamp is the record of the order). Owner: Boss
(they/them). Boss, after R195: "Enhance it more try internet, find new ways to get more profit and less loss and more
winrate". **Research only. The app stays buy-only; nothing goes to paper or live.**

## 1. Where the ideas come from (internet, 18 Nov 2026) and R195

R195 found no reliable seller. Only the 09:20 straddle with a 25% stop per leg (S1) made money in 2021-2025, and it
faded in 2026, broke the account stop, and needs about Rs 2-2.6 lakh of margin. The only structure that fits Rs 56k
is one credit spread on a non-expiry day, and R195's trend spreads lost.

The ideas below are the common practitioner fixes. They come from vendor and blog pages, not peer-reviewed tests, so
each is a hypothesis:

* **Stop to cost on the surviving leg** after the first leg is stopped (Fyers Strategy Builder's "Move SL to cost";
  AlgoTest "trail stop loss to break-even").
* **One combined-premium stop instead of per-leg stops.** Per-leg stop clusters at thin strikes get spiked
  (MarketCalls, "How the 9:20 intraday straddlers are being gamed": a Nifty call went from 122.7 to 803.5 in one second
  on 20 Aug 2021). R195's fills double the stop slippage ticks but cannot model such a spike.
* **A later entry, 10:30** (MarketCalls, "Is the 9:20 straddle no more working": suggested, no numbers shown).
* **A profit lock** on the combined premium, and **a strangle** (less gamma) instead of the straddle.
* **Skip shock days.** Traders warn against selling into event risk (5paisa, share.market Budget-day pieces).
* **Put skew** (a structural, non-directional idea): index puts are usually priced richer than calls, so a bull put
  spread collects the skew. It is the one structure that fits Rs 56k on non-expiry days.

## 2. Data, engine, costs, margin: R195's, unchanged

The R195 stores (2 Aug 2021 - 13 Nov 2026) and `r195engine` are used as they are: stale-leg Black-Scholes marks, real
entry prints required, R103 'liq' slippage (stop ticks doubled), dated charges (BSE for SENSEX), today's lot every
year, R195's margin model. Square-off 15:10, 15:05 on expiry day. **New:** a per-minute loop for the straddle family
(`r196engine.run_short_pair`), because the stop-to-cost and lock rules make one leg's stop depend on the other's exit.
It uses the same marks, fills and charges, and a test checks that it reproduces R195's S1 trades exactly when the new
rules are off.

## 3. Periods, and the holdout honestly

* **Design (walk-forward): 2 Aug 2021 - 31 Dec 2025**, the same as R195. No rule has a fitted parameter; every filter
  is trailing-only.
* **2026 (1 Jan - 13 Nov) is NOT a clean holdout any more.** R195 read it once for the base rules these variants
  modify. R196 reports 2026 as a **"second look"** and gates on it (net > 0, 40+ trades), but a pass here can only be
  "**provisional**".
* **The clean holdout is forward: every session from 16 Nov 2026 on.** It is read ONCE, when each surviving candidate
  has 40+ forward trades (about 2-3 months for the all-days rules), by `r196study.py forward`, which writes
  `results/FORWARD_READ`. Only a candidate that passes the forward read could be called reliable.

## 4. The candidates (14, fixed now)

Base = **S1** (R195): 09:20 decision, sell C(K0) and P(K0) at 09:21, a 25% stop on each leg's premium, square-off.
Signal source of every A rule: none directional (K0 from the index); A6 also reads the index, and A7 reads India VIX.

| id | rule |
|---|---|
| **A1-NIFTY** | S1 + **stop to cost**: once one leg is stopped, the other leg's stop moves down to its own entry fill (it exits when its mark >= its fill). |
| **A2-NIFTY** | Straddle with **one combined stop**: close both legs when C + P marks >= 1.20 x their summed fills. No per-leg stop. |
| **A3-NIFTY** | S1 + **profit lock** on the open pair: once the open profit (both legs, marks vs fills, rupees) reaches 30% of the credit, close everything if it falls back to 15% of the credit. |
| **A4-NIFTY** | S1 entered at **10:30** (decision on the 10:30 close, fill 10:31). |
| **A5-NIFTY** | **Strangle**: sell C(K0 + 2 steps) and P(K0 - 2 steps) at 09:21, a 30% stop per leg. |
| **A6-NIFTY** | S1 **skipping shock days** (the index): skip when the 09:15 open is more than 0.6% from yesterday's close, or yesterday's range was >= 1.5x the average range of the 20 sessions before it. |
| **A7-<index>** for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (5 tests) | **The combination, fixed now**: S1 + stop to cost (A1) + profit lock (A3), on days when India VIX at 09:20 >= its median of the previous 250 sessions (R195's S2 filter). |
| **PS1-NIFTY** | **Bull put spread** (put skew), every NIFTY day that is not its expiry day: 09:20 decision, sell P(K0 - 2 steps), buy P(K0 - 6 steps); stop when the loss reaches 50% of max loss; square-off 15:10. Signal source: none directional (a fixed side by structure). |
| **PS2-NIFTY** | PS1 on days India VIX >= its 250-session median. |
| **PS3-BANKNIFTY** | PS1 on BANKNIFTY (not its expiry day). |

## 5. Gates (fixed now)

A candidate is **"provisional"** only if ALL of 1-9 hold. It becomes **"reliable"** only after the forward read
(section 3) is net > 0 with 40+ trades, inside the account stop.

1. Design net > 0, and net > 0 in its post-regime part (20 Nov 2024 - 31 Dec 2025).
2. 2026 second look: net > 0.
3. 40+ trades in design and 40+ in 2026.
4. Net > 0 without its 5 best trades, in design and in 2026 separately.
5. Twin p < 0.05 on design. A rules: the timing twin (random decision minute 09:20 - 13:00 on a 5-minute grid; A4
   10:30 - 13:00). A6 / A7: random-day twins from their unfiltered base (S1 / S1+A1+A3) of the same size. PS rules:
   the **side twin**, the same day and strikes mirrored as a bear call spread with probability 1/2, which tests the
   skew claim itself.
6. BH q <= 0.10, m = 4,807 (entries at R195's end) + 14 = 4,821. p = max(day-bootstrap p, twin normal-tail p, and for
   A1-A7 the paired bootstrap p that the mean daily difference vs S1 on the same index is <= 0).
7. Account stop at 1 lot over design + 2026: worst month >= -Rs 13,000 and max drawdown <= Rs 40,000.
8. Design net > 0 at 2x slippage ticks.
9. **Boss's three aims, vs the base** (A rules: S1 on the same index and days traded; PS rules: R195's T2-NIFTY, the
   only all-days NIFTY spread): on design, higher net, a higher win rate, and a smaller max drawdown.

"Possible at Rs 56k" is reported as in R195 (model margin <= Rs 56k on 95% of 2026 trades). It is expected to be yes
only for PS1-PS3.

## 6. Process

`r196engine.py`, `r196study.py` (design, the 2026 second look, gates, BH, the forward read later), `r196report.py`,
`r196entries.py`, `test_r196.py` (the S1 reproduction, stop to cost, lock, combined stop, shock filter, put-spread legs,
look-ahead guards, the forward lock). One entry per candidate in `research/experiments/entries/r196-*.yaml`.
