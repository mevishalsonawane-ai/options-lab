# R196 pre-registration: does SENSEX earn its place in R72, R51 and Liquidity 15+5 with today's exits?

Written 20 Nov 2026, **before any R196 number was computed**: no SENSEX trade, total or twin has been run. Committed and
pushed on `claude/sensex-arms` before the code's first run on data. Research only: nothing in `android/**` changes.
Owner: Boss (they/them).

Boss asked (20 Nov 2026): *"Also create a new session for adding sensex index for our arms"*: R51 Donchian 20, R72 Breadth
Donchian and Liquidity 15+5. R196 was free when this was written (R194 and R195 are taken; no `r196` folder, branch or
log entry existed).

**Signal source of every rule: the index** (the arms' own index breaks: R51 / R72 on 5-minute SENSEX bars; Liquidity on
the SENSEX 15- and 5-minute liquidity levels). R151's picker reads index features at or before the decision minute. Options
(BSE's SENSEX options, BFO) are only bought; stops, breakevens and locks sit on the option premium. No rule reads an option's
own chart.

## 0. What is already true (found in the code before this was written)

- **R51 and R72 already trade SENSEX.** `DonchianArm.UNDERLYINGS` and `BreadthDonchianArm.UNDERLYINGS` are NIFTY, BANKNIFTY,
  FINNIFTY, MIDCPNIFTY and SENSEX; `ArmLive.zerodha` maps a SENSEX contract to Zerodha's BFO list, so a SENSEX trade of these
  arms already goes live through the arms' live gates. The research behind them (R51, R72, R151, R176 - R193) priced SENSEX
  as one of the five, inside pooled totals.
- **R72's breadth is not built from NIFTY's constituents.** It is "at least 4 of the 5 indices (NIFTY, BANKNIFTY, FINNIFTY,
  MIDCPNIFTY, SENSEX) break their own 20-bar channel on the same 5-minute bar" (`BreadthDonchianArm`, research/HUNT_R72_
  CROSS_INDEX.md). SENSEX is already one of the five votes, so the signal needs no SENSEX constituents and nothing to
  transfer. R196 does not change the breadth.
- **Liquidity 15+5 does not trade SENSEX** (`LiquidityRules.UNDERLYINGS` = BANKNIFTY, FINNIFTY, MIDCPNIFTY). For it SENSEX
  would be new.

So for R51 and R72 the question is **keep or drop SENSEX**, and for Liquidity it is **add SENSEX or not**.

## 1. Prior looks (honest disclosure)

- R72's design (HUNT_R72_CROSS_INDEX.md) reported SENSEX's share (about Rs 13k); R51's cleaned run (HUNT_R51_CANCELLED_
  RECENT.md) reported SENSEX +Rs 14,166. Both with older exits, before Nov 2026. Every later arm study (R151 - R193) had
  SENSEX inside its pooled R51 / R72 totals over this whole span, R193 up to 13 Nov 2026; none reported or gated SENSEX alone.
- **Liquidity on SENSEX failed before:** H11-2 / H4 / H23 / H34 / LIQ-11 / LIQ-12 (research/hunt/r52/CATALOGUE.md). R52 priced
  it on real options and it failed (58 trades +Rs 762; hold period 105 trades, -Rs 18.5 a day); H34 found it lost. Those used
  older exits (no breakeven, no Rs 1,000 lock) and older spans. R196 re-tests it with today's exits from Nov 2024; it counts
  as one more try of a failed idea and is in the BH count like every test.
- Therefore **no window here is unseen data.** W2 (below) is locked for R196 and read once, but its pooled R51 / R72 totals
  (SENSEX included) and R52's SENSEX Liquidity hold period were read before.

## 2. Data and engines (nothing raw in git)

- Drive lake `gdrive:IraAlgo-data-lake`, copied with rclone on 20 Nov 2026 to `~/.cache/iraalgo/sx/raw`: `index_1m` 2024 -
  2026 of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX and INDIAVIX with the 20 Oct - 13 Nov 2026 fill / gap parts;
  `options_rolling_1m` WEEK 2024 - 2026 of the five indices, BANKNIFTY MONTH and SENSEX MONTH (MONTH is not traded, only
  kept), with the 2026 gap parts; `index_daily`. `r196data.py` builds one flat folder (`~/.cache/iraalgo/sx/lake`) in
  R190's layout (2026 files joined with their fill parts, first print of a minute kept). Read with `python3 -I`.
- **Deviation from R193 (data, not rules):** the stores start on 1 Jan 2024 (warm-up to the first counted day, 1 Nov 2024),
  not 3 Aug 2020, because only 2024 - 2026 was copied. The 5-minute Donchian, Supertrend, the liquidity levels (10 days back)
  and the picker's features (index_daily, full history) need far less. Section 3 measures the effect.
- Engines unchanged: R186's `r186engine.price` (R176's held-contract simulator, R17 fills: the app's 5 / 10 bps plus a
  volume-based half-spread, dated Zerodha charges) through R193's `price` / `walk` / `units`; R152's picker module (R151's
  frozen app numbers). R196 only adds SENSEX to Liquidity's book list in its own process (`r101cfg.LIQ_BOOKS`,
  `fam_liq.IDX_STOP`), never in those files.
- **SENSEX specifics, as the engine already has them:** BSE charges (exchange 0.0325% from Oct 2024, 0.05% before; STT,
  SEBI, stamp, GST as NSE; `obuy.costs` dated); lot 10, 20 from 28 Nov 2024 (`r51cfg.LOTS`); strike step 100; tick 0.05;
  its own weekly expiry read from the option data (`r51data` expiry test), so the 15:05 expiry-day square-off and "never on
  the index's own expiry day" follow SENSEX's own calendar (Friday, then Tuesday, then Thursday as BSE moved it), holidays
  included (a holiday is simply not a session).

## 3. Reproduction first (before any SENSEX number)

R193's published total-span 1-lot results (results/span.json, 1 Nov 2024 - 13 Nov 2026) on R196's lake and stores:
A and V5 for R72 and for R51, A and LIQ-V5 for Liquidity (trades and net, all indices pooled as R193 published them). The gap is reported with its size; a gap
over one trade or Rs 1 is investigated (expected cause: the 2024 warm-up start) and written down before the SENSEX numbers
are read. It never changes a rule or the gate.

## 4. Span and windows

| window | dates | role |
|---|---|---|
| **W1** | 1 Nov 2024 - 31 Mar 2026 | design and walk-forward |
| **W2** | 1 Apr - 13 Nov 2026 | **holdout, locked: read once** (flag file `results/W2_READ`), after W1's results are written |
| total | 1 Nov 2024 - 13 Nov 2026 | twins and "without the top 5" |

Walk-forward: nothing is fitted (every rule and number is frozen above), so the walk-forward check is the frozen rule judged
quarter by quarter: W1's six quarters are Nov - Dec 2024, Q1 - Q4 2025 and Q1 2026. W2's quarters (Q2, Q3, Oct - 13 Nov
2026) are reported, not gated.

## 5. The tests (fixed now; 1 lot)

| id | arm | what is judged |
|---|---|---|
| **R72-SX** | R72 Breadth Donchian | the SENSEX trades of R72's live rule, from the arm's normal five-index walk: R151's R72 picker (skip under 0.4656, removed after the walk so it holds its side and caps), V5 exits (breakeven at +10%, then +Rs 500 a lot locked at +Rs 1,000 a lot net and Rs 500 under each further Rs 500 step), the arm's own -15% stop, ladder / L25, 30-minute time stop, index exits, square-offs (15:05 on an expiry day). Exactly R193's R72-V5. |
| **R51-SX** | R51 Donchian 20 | the same for R51 (R193's R51-V5; picker 0.4469). |
| **LIQ-SX** | Liquidity 15+5 | a new SENSEX book pair: the SENSEX 15-minute and 5-minute liquidity levels (`fam_liq.signals`, room 1.0), 1-ITM option of the nearest (weekly) SENSEX expiry, entries 09:20 - 14:00, never on SENSEX's expiry day, Liquidity's own exits (-15% stop, +5% within 20 minutes, the index stop, failed break / new level) plus V5 (breakeven at +10% or once the best open P&L is +Rs 1,000 a lot net) - exactly R193's LIQ-V5. **Index stop: 50 points**, fixed now: the arms' ~0.06% of the index (BANKNIFTY 30, FINNIFTY 15, MIDCPNIFTY 8; SENSEX about 80,000 - 85,000 in this span) and the value H11-2 used. Room = 1 x the index stop, as the other books. |

**3 tests.** Each SENSEX slice must stand on its own (the other indices' trades are not affected by keeping or dropping
SENSEX: R51 / R72 take at most one position a side and two entries a side a day *per index*, and R72's breadth still counts
SENSEX's vote either way; Liquidity's walk is per index and book. R196 checks this: the other books' trades with and without
the SENSEX book must be identical, and any difference is reported).

## 6. What is reported (per test and window)

Trades, win %, net, average win / loss, max drawdown (closed-trade daily equity, R193's `stats`), worst month, net without
the 5 best trades, the account-stop check (max drawdown <= Rs 40,000 and worst month >= -Rs 13,000; a breach is written
next to the verdict), the W1 and W2 quarters, exits by reason, how many SENSEX expiry days were skipped and how many
entries R151's picker removed. Descriptive only (never gated): the same SENSEX slice with the arms' own exits (A, no picker,
no breakeven, no lock); the per-index V5 table of R51 and R72 for all five indices (context); the arm with and without
SENSEX; and a funded-lots line is NOT given (R193's Rs 56k sizing is descriptive and the gate is 1 lot).

## 7. The gate (fixed now; never loosened after seeing numbers)

SENSEX **PASSES** for an arm only if ALL hold, at 1 lot:

1. net > 0 in **W1 and in W2**;
2. at least 40 trades in W1 and in W2;
3. walk-forward: net > 0 in **at least 4 of W1's 6 quarters**;
4. over the total span, net **without its 5 best trades** > 0;
5. **random-twin p < 0.05** (one-sided, empirical, 2,000 twins, seed 196) over the total span;
6. **BH q <= 0.10** with m = 4,808 (entry files in `research/experiments/entries` at this branch's base, origin/main
   82fbc3b4) + 3 = **4,811**; earlier entries count as p = 1. As R190 / R193, BH uses the normal-tail twin p
   (z = (actual - twin mean) / twin sd).

**Twins** (R51's random-entry null, priced with the test's own exits): for each (day, side) of the slice's taken trades,
the same number of entries at uniform random minutes in the rule's entry window (R51 / R72: 09:20 - 15:10; Liquidity:
09:20 - 14:00), the 1-ITM strike from the minute before entry (`r51 engine.strike_for`, money 1), priced by
`r186engine.price` with the same exits and lock as the test (V5) at 1 lot, without the rule's index exits (`fx` = -1, R51's
twin convention, disclosed); a twin that cannot be filled counts Rs 0. The picker is not applied to twins (they copy the
kept trades' days, sides and count).

A slice that **fails** stays as it is today: for R51 / R72 the report says whether SENSEX's record argues for Boss switching
it off (a risk-lowering choice that is his); for Liquidity SENSEX stays a note unless Boss chooses it as an exception
(CLAUDE.md). A slice that **passes** may act on paper straight away (logged, labelled, one-tap undo, shown to Boss); live
only by Boss's explicit choice through every live gate. Nothing here changes the app: Boss decides first.

## 8. Order of work

1. This file, committed and pushed. 2. `r196data.py lake` and the stores. 3. Reproduction (section 3) ->
`results/repro.json`. 4. W1 for the 3 tests and the descriptive lines -> `results/w1.json`, written and committed.
5. W2 once (`results/W2_READ`) and the total span with the twins -> `results/w2.json`, `results/total.json`.
6. The gate -> `results/decision.json`; entries in `research/experiments/entries/` (3 tests, failures included); report.

## 9. Tests of the code (synthetic, no data)

`test_r196.py`: the look-ahead guard (a SENSEX liquidity signal and the twin plan never read minutes after their own),
the windows and quarters, the gate (each of the six items failing alone), BH with m = 4,811, twin plans (same days, sides,
counts; window bounds; expiry-day sessions never planned), the slice filter (SENSEX only; an empty slice; a holiday gap),
SENSEX's lot on 27 / 28 Nov 2024, BSE vs NSE charges on the same trade, and stale / missing option minutes (no fill ->
Rs 0, never a made-up price).
