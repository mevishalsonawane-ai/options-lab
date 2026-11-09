# R154 pre-registration: 10-15 point scalping on the index options

Written 9 Nov 2026, **before any R154 number was computed** (committed and pushed on `claude/r154-scalp-10-15` ahead of
the code's first run). Research only; nothing in `android/**` changes.

Boss, 9 Nov: *"I want to study 10-15 pts scalping strategy, use all the data information we have till now, and find out the
winrate and profit/loss results."*

**Signal source for every rule: the index** (CLAUDE.md default). Every entry signal is read from 1- or 5-minute bars of
the index itself (spot); the option bought is the 1-ITM nearest-expiry option of that index (the arms' existing strike
rule). Reading A puts its target and stop on the option's premium (CLAUDE.md allows stops and targets on the premium);
reading B puts them on the index. No rule reads its signal from an option's own chart.

## 0. What is already known (cited, not re-run)

- **SCALP17** (`research/SCALP17.md`, 15-20 premium points, NIFTY / BANKNIFTY / FINNIFTY, 2020 - Oct 2026): from a random
  minute an ATM / 1-ITM option reaches +17 before -15 within 30 minutes 32.7% of the time and -15 first 40.7%; the
  average entry makes -0.70 points gross and -3.31 net. 1,100,700 condition x target / stop / time combos were screened;
  0 passed BH, and every shortlisted rule lost money net in 2025 - Oct 2026.
- **R45** (`research/HUNT_R45_PREMIUM_SCALP_AUDIT.md`): a claimed premium scalp's profit came from the rolling ATM series
  switching contracts; priced on a held contract it lost Rs 150-170 a trade.
- **SCALP_1MIN** (`research/SCALP_1MIN.md`): 1-minute HMA / VWAP / CPR scalps on BANKNIFTY lost in every layer.
- **R106 / R152** (`research/HUNT_R106_SMALL_TARGETS.md`, `research/hunt/r152_donchian_2k/`): small fixed rupee targets
  (Rs 500 - 2,000) on the Liquidity, R72 and R51 arms lost money against the arms' own exits: they cut the few big
  winners the arms live on. R154 asks whether a points-based scalp is any different, and on more entries.

## 1. Data, engine, fills and charges

- Lake: `gdrive:IraAlgo-data-lake` copied 9 Nov 2026 (`index_1m`, `index_daily`, `options_rolling_1m` WEEK = nearest
  expiry). **All** index-option data the repo has: NIFTY, BANKNIFTY, FINNIFTY from Aug 2021, MIDCPNIFTY from Jan 2023,
  SENSEX from May 2023, to 19 Oct 2026.
- Stores: from 20 Nov 2024, R51's Amendment A clean stores (`R51_CLEAN=1`, the arms' own; R106's `clean_post` stores for
  14 - 19 Oct). Before 20 Nov 2024, an **early store** built by the same code (`r51data.index_days` / `add_options`,
  Amendment A cleaning) from the first option day to 19 Nov 2024, kept to the contracts within 2 strikes of the day's ATM
  range (the only ones a 1-ITM entry can buy). Every trade is priced on the ONE contract bought (never the rolling series).
- Engine: R106's numba simulator (`r106engine.simulate`, R64's held-contract line), R17 fills (`engine.SLIP`: buy +5 bps
  plus the half-spread ticks from 5-minute volume; market sells -5 bps and the ticks; stop sales -10 bps and twice the
  ticks; a gap through a stop fills at the minute's open; a limit target fills at its price), next-minute fills (the
  open of the minute after the signal, up to 2 minutes later if that minute has no print), dated Zerodha charges
  (`engine.charges`), dated lot sizes (`r51cfg.LOTS`). If a stop and a target are both touched in one minute, **the stop
  is taken first** (conservative).
- Square-off: **15:15** (Boss's ask), **15:05 on expiry days** (CLAUDE.md; R51's straddle test marks expiry days).

## 2. Exit rules (fixed now): 24 = 2 readings x 3 sizes x 2 stops x 2 time stops

| reading | target | stop | time stop |
|---|---|---|---|
| **A: premium points** | sell when the option's premium is +P above the real buy fill (a resting limit at fill + P) | a resting stop at fill - S on the premium | T minutes after the fill (sold at the close of the T-th minute) |
| **B: index points** | sell (market, next minute's open) once the index has moved +P in the trade's favour from its level at the fill (the index open of the fill minute; the previous close if missing) | sell (market, next minute's open) once the index has moved S against | as A |

P in {10, 12, 15} points; S = P (1:1) or 1.5 x P (1:1.5: 15, 18, 22.5); T in {15, 30} minutes; plus the square-off.
For B a target and a stop seen in the same minute count as the stop. Rule ids: `A10s10t15`, `A10s15t15`, ...,
`B15s22.5t30`.

## 3. Entries (fixed now; signal on the index, buy the 1-ITM option from the minute before the entry)

**Arms (their entries as already reproduced; only their exits are replaced by the scalp exits; their index exits are
dropped too; their own taking rules stay):**

| id | arm | units / taking rule | B0 must reproduce (whole design 20 Nov 2024 - 31 Mar 2026 / final) |
|---|---|---|---|
| R72 | R72 Breadth Donchian 4 of 5 | R101's units; one open a side, 2 fresh fills a side a day | 3,071 / Rs 2,16,929 ; 1,202 / Rs 1,71,366 |
| R51 | R51 Donchian 20 + Supertrend (10, 3) (today's app rule) | R112 / R152's units; as R72 | 4,208 / Rs 50,425 ; 1,670 / Rs 1,34,743 |
| LIQ | Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5) | R101's units; one position a book | 881 / Rs 2,57,249 ; 357 / Rs 11,155 |

The arms' stores begin 20 Nov 2024, so their scalps cover 20 Nov 2024 onward only. **ORB**: no ORB arm runs today
(cancelled after R3 / R49 / R92) and no ORB unit is reproduced in R101; the opening-range break is covered by E1 below.

**Plain scalping entries (new, common, small and fixed):**

| id | signal (index bars) |
|---|---|
| E1 OR15 | first-15-minute range (09:15 - 09:29 highs / lows): the first 1-minute close above the high (call) / below the low (put) from 09:30; each side once a day |
| E2 TWAPX | 5-minute bar closes above (call) / below (put) the session's running average price after closing on the other side the bar before (from the bar ending 09:44). **The index has no volume, so the session's equal-weight average of (H + L + C) / 3 stands in for VWAP**; labelled so |
| E3 EMA921 | 9 / 21 EMA cross on 5-minute closes (EMAs carried across sessions; the first 30 sessions of each index are warm-up only) |
| E4 PBB | 1-minute close above the previous 1-minute high (call) / below its low (put), only with the trend: the last completed 5-minute close above (call) / below (put) its 21 EMA and that EMA rising (call) / falling (put) since the bar before |

Plain taking rule: one open position per entry x index at a time (a signal while a position is open is skipped); at most
5 trades per entry x index a day; entries from 09:20 (E1 from 09:31) to 14:45. A bar with no print gives no signal.

**Random-entry baseline (descriptive, not a test):** per index and session, 5 minutes drawn uniformly from 09:20 - 14:45
and a coin-flip side (seed 154), same exits and taking rule: what a scalp exit earns with no signal at all.

Tests: (4 plain entries x 5 indices + R72 x 5 + R51 x 5 + LIQ x 3) x 24 exits = **33 x 24 = 792** (at 1 lot).

## 4. Periods

- **P1 design**: the first option day (Aug 2021; MIDCPNIFTY Jan 2023; SENSEX May 2023) to 31 Mar 2025. For the arms, 20 Nov
  2024 - 31 Mar 2025 only. Also shown split at 20 Nov 2024 (before: weekly expiries on every index, older lot sizes).
- **P2 walk-forward**: 1 Apr 2025 - 31 Mar 2026 (four quarters). Nothing is fitted, so every rule is its own out-of-sample
  run; in addition a **walk-forward pick** (descriptive, never gated): per entry x index, at the start of each quarter and
  of the final, the exit rule with the highest 1-lot net on all earlier data is chosen and run on that window only.
- **P3 final**: 1 Apr - 13 Oct 2026, read ONCE (`results/FINAL_READ`). **Weak evidence: this window was already read by
  R51 - R153 (the arms) and by SCALP17 (as part of its 2025 - Oct 2026 holdout).**
- **P4 post**: 14 - 19 Oct 2026 (4 sessions), read once after the final (`results/POST_READ`). Tiny; reported, never gated.

## 5. What is reported (per index x entry x exit rule, every period)

Trades, win rate, **break-even win rate** (average net loss / (average net win + average net loss) of the rule's own
trades), net, per session, worst day, max drawdown (daily), net without the 5 best trades, the exit mix (target / stop /
time / square-off), **average charges per trade**, exit-twin p and BH q, and the verdict. **Lots 1, 2 and 3** (qty =
lots x the dated lot size; charges and R17 slippage of the whole quantity): 1 lot is the gated test, 2 and 3 lots are
shown for every rule (descriptive). A short charges table: the round-trip charges of a typical trade per index and lots,
and the premium points they eat. Pooled views per entry and per reading, the random-entry baseline, and R106 / R152 /
SCALP17 compared honestly.

## 6. Gates (all must hold, at 1 lot; never relaxed)

1. Net > 0 in P1, in P2 and in P3.
2. Beats its random-exit twins: P3, 500 twin sets (seed 154). Each twin keeps the rule's own trades (same entries, same
   contracts, same fills) and sells each one instead at the open of a uniformly random minute strictly after its fill
   and no later than the end of its time-stop window (fill + T minutes, or the square-off if sooner); p = (1 + #twins
   with total net >= the rule's) / 501. BH q <= 0.10, counted against **R153's tally of 890 earlier tests** (R106's 549,
   the 299 later arm entries, R115 / R150 / R151, R152's 18, R153's 24), taken as p = 1, plus R154's 792: m = 1,682. A
   stricter q that also counts SCALP17's 1,100,700 screened combos and R45's 21 tests is shown (never used to pass).
3. At least 40 trades in P3.
4. Arm entries only (R106's gate 2): net above the arm's own exits (B0, same index, 1 lot) in P2 AND P3.

## 7. What a pass means (CLAUDE.md)

A passing rule may act on PAPER straight away (labelled switch, Autopilot change timeline, one-tap undo, shown to Boss).
Nothing goes live from R154: anything new on live trades starts in shadow behind a switch that is off by default.
A failed rule stays a note here and in `research/experiments/entries/r154-*.yaml` (one entry per entry x index x reading,
covering its 12 exit rules).

## 8. Tests

`test_r154.py` (pytest): entry signals (each on hand-built bars), the **look-ahead guard** (every entry's signals up to
minute r are unchanged when every bar after r is erased), the index-trigger exits (target, stop, same-minute stop first,
time window), the plain taking walk (one open at a time, 5 a day, 14:45 cut), twin rows inside the window, stats /
break-even / BH, and the period split.
