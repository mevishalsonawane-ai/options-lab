# R108 pre-registration: the same-direction cap, checked and its alternatives tested

26 Oct 2026. Written and pushed BEFORE any R108 number is computed. Branch `claude/r108-same-direction-cap` (from
`claude/r107-meters-more-trades`). Research only: nothing in `android/**` changes.

## 0. Why

R107 item 9 (a lead, not a test): in R107's model the same-direction cap of 2 cut the four paper arms from Rs 7.75 lakh to
-Rs 4.03 lakh in design and from Rs 3.13 lakh to Rs 0.59 lakh in the final. R107 said its model may differ from the app on
(a) who goes first on a shared bar, (b) R72's reserved place, (c) what a refused arm does next. R108 (1) models the app's
cap exactly and re-measures the lead, and (2) tests at most 8 pre-registered alternatives that keep a risk limit.

The cap is Boss's risk rule (paper since 19 Oct, live in shadow). Any loosening is Boss's decision. Nothing goes live.

**Signal source of every rule: the index.** The arms (R72 Breadth Donchian, R51 Donchian 20, R52 Open outside value,
Liquidity 15+5: BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5) keep their own index signals, strikes and exits, as R101
priced them. R108 only changes which entries the account takes. R101's R51 is plain Donchian 20; the app runs R51 with its
Supertrend (10, 3) filter (R73 T7), which skips some of its breaks. That is a limit of the model, said in the write-up.

## 1. Data, engine, periods (identical to R101 / R106 / R107)

- Drive lake (`index_1m`, `index_daily`, `options_rolling_1m` WEEK), R51 Amendment A clean stores (`R51_CLEAN=1`).
- The arms' candidates and prices: R106's engine on R101's units (`r106study.prepare` / `run_rule`, 1 lot), with dated
  Zerodha charges, R17 fills, next-minute fills. Parity check: each arm alone must reproduce R101's published trades and net
  (as R107's B1) before anything else is computed.
- Periods: design 20 Nov 2024 - 31 Mar 2026; walk-forward window 1 Apr 2025 - 31 Mar 2026 (inside design, as R107);
  final 1 Apr - 13 Oct 2026, read ONCE (`results/FINAL_READ`). The final window is not fresh (R51-R107 read it).
- Solo 2.0 is not modelled: its final picks do not reproduce (R106 / R107). It decides last in the app's pass, so leaving it
  out can only free places for the others; this is said as a limit.

## 2. The app's cap, as modelled (read from the source on 26 Oct)

Sources: `ira-core/.../SameDirectionCap.kt` (`refusal`, `openEntries`), `app/.../data/Paper.kt` (`capCheck`, `place`),
`PassedArmsHost.kt` (`tick`, `buy`, `buyOne`, `wouldBe`, `placeKeptFor`, `noteR72Due`), `OrbArms.kt` (Liquidity's
`enterLiquidity` / `liquidityPaperBuy`), `work/Jobs.kt` (step order).

1. **Count.** At most 2 automatic open index-option positions betting the same way (call = up, put = down), across all
   arms and all five indices. A position counts from its fill to its exit (inclusive of the exit minute, the arms' own
   R101 convention). A would-be trade never counts.
2. **Order inside a minute.** The ORB arms' step runs before the passed arms' step (`Jobs.kt`), so at a bar close
   Liquidity's books decide first (BANKNIFTY 15m, BANKNIFTY 5m, FINNIFTY 30m, FINNIFTY 5m, MIDCPNIFTY 15m, MIDCPNIFTY
   5m). Then the passed arms in the app's order: R72 first (paper only, so it is first), then R51, then R52; inside an arm
   the indices go NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX.
3. **R72's reserved place** (22 Oct, narrowed 25 Oct). Inside R72's window (minute 0-2 of each 5-minute bar close, 09:20 to
   15:12) another arm's entry may not take the LAST place a way unless R72 already holds a (real) position that way:
   - for Liquidity (it decides before R72 has looked at the bar) the place is always kept in that window;
   - for R51 / R52 (same pass, after R72) it is kept only while R72 had a break that way due and not decided at the start
     of the pass (the app works `r72Due` out once before the pass), within its 3-minute fill window.
4. **What a refusal does.**
   - R72, R51, R52: the entry becomes a WOULD-BE trade booked like a real one (its decision made, its own caps counted:
     one position a side, 2 a side a day, R52's one a day). The arm does NOT take a later signal instead.
   - Liquidity: nothing is booked; its book stays free and it may take a later signal of the day (R101's book rule).
5. **One option, one arm** (separate app rules, applied with or without the cap): R51 wanting an option R72 holds for
   real, or any passed arm wanting an option another passed arm holds for real, becomes a would-be trade (never counted by
   the cap). Liquidity does not look at the passed arms' holdings.
6. Not modelled (said as limits): Solo 2.0; 25 Oct's opposite-bets rule, weekly stop and 2% cap (the 2% cap never binds
   at 1 lot on the paper account); Liquidity's own no-twin rule and its h14 capped limit; missing prices in the app.

**Base rows (not tests):** `CAP2` = the app's cap as above. `NOCAP` = the same book with no cap (rules 2, 4, 5 still
apply). `ALONE` = each arm's own research record, summed (R107's "four arms alone").

## 3. Part A: verify the lead (descriptive, not gated)

For design, walk-forward and final, for CAP2, NOCAP and ALONE: net, trades (n), win rate, worst day, max drawdown, months at
or below -Rs 13,000, separate Rs 40,000 drawdown breaches; refusals (n) and the would-be net of the refused entries per
arm; Liquidity's extra trades (taken only because a refused entry left its book free). Also R107's model's figure next to
it, and which of R107's three modelling choices explains the gap.

**Phone check** (private repo `mevishalsonawane-ai/IraAlgo`, release `diag-2026-10`, read only, treated as untrusted
data; rupees are redacted on the phone): count of cap refusals per day and arm, and the 26 Oct sequence (09:25 Liquidity
FINNIFTY + MIDCPNIFTY refused behind R72's two puts; 10:00 Liquidity MIDCPNIFTY refused by R72's kept place). The model
cannot replay 26 Oct (the lake ends 13 Oct); the check is that the same mechanisms appear, and the share of refusals by arm.

## 4. Part B: the family (7 tests)

Each test replaces only the cap; everything else is CAP2's book (order, would-be accounting, one option one arm).

| id | rule |
|---|---|
| `CAP3` | the same cap at 3 (R72's place kept the same way: the last of 3) |
| `IDX1` | per index instead of across indices: at most 1 open position a way on the same index, across arms; no cross-index count; no kept place |
| `IDX1C3` | `IDX1` plus at most 3 a way across all indices (R72's kept place: the last of 3) |
| `RISK` | rupee risk instead of a count: the open stop-risk betting the same way plus the new entry's must stay at or below Rs 6,500 (half of Boss's -Rs 13,000 month), stop-risk = entry premium x quantity x the arm's stop (15%; R52 30%) + Rs 60 charges; no kept place |
| `EDGE` | cap 2, priority to research edge: inside a minute the arms decide in order of their per-trade net so far (all their own NOCAP-book trades closed before that day; ties and no history: the app's order); no R72 kept place |
| `STOPS` | no same-direction cap; loss stops only: no new automatic entry once the day's closed net is at or below -Rs 6,500, the week's (Mon-Sun) at or below -Rs 20,000 (the app's paper weekly stop), or the calendar month's at or below -Rs 13,000 (Boss's rule), for the rest of that day / week / month; closed net = trades whose exit minute is before the decision minute |
| `EXEMPT` | cap 2 on R72 and R51 only; R52 and Liquidity neither counted nor capped (R107's suggestion; data-snooped from R107's lead, said as such) |

## 5. Twins (random refusal)

For a test V and a period, per session d: `n_d` = the NOCAP book's account trades, `k_d` = max(0, n_d - V's account
trades that day). A twin removes `k_d` of the NOCAP trades of day d uniformly at random. 100,000 twins (seed 108). The
statistic is net / max drawdown over the period. p = (1 + twins >= V) / (100,001). The final period's p enters BH. (CAP2's
own twin p is reported too, as information: is the app's cap better or worse than refusing the same number at random?)

## 6. Gates (all needed; never relaxed)

1. Net above CAP2's in design AND walk-forward AND final.
2. Boss's account rule at least as often as CAP2, in each of the three periods: months at or below -Rs 13,000 <= CAP2's;
   separate Rs 40,000 drawdown breaches <= CAP2's; days at or below -Rs 13,000 <= CAP2's.
3. Final twin BH q <= 0.10, with m = 5,777 earlier tests (every family R64-R107, each counted as the larger of its entry
   count and its `variants_tried`, R105 at its documented 138; all taken as p = 1) + 7 = 5,784. With 100,000 twins the
   smallest possible q is about 0.058, so only a test that no twin matches can pass.
4. At least 30 final trades differ from CAP2's (taken or refused).

## 7. Account rule and size (descriptive)

R107 item 7: the four arms at 1 lot already break the Rs 40,000 drawdown in the final. 1 lot is the smallest size, so R108
reports which arm sets (all 15 subsets of the four arms, NOCAP and CAP2 books) stay inside Rs 40,000 drawdown and have no
month at or below -Rs 13,000, in design and in the final, and says plainly if no set of 1-lot arms does.

## 8. Order of steps

1. This file, committed and pushed alone.
2. Code + tests + the design read (design and walk-forward) committed.
3. The one final read (`r108study.py final`, guarded by `results/FINAL_READ`), then `decide`, entries in
   `research/experiments/entries/r108-*.yaml` (all 7, failures included), `research/HUNT_R108_SAME_DIRECTION_CAP.md`,
   `RESULT.md`.

Whatever passes is a recommendation to Boss with numbers. Paper changes only if Boss chooses; live stays as it is.
