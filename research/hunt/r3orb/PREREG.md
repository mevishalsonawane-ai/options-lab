# R3 pre-registration: the ORB family deep study (ORB, ORB Fresh, ORB Sweep, Range Fade) on the 2021-2026 Dhan lake

Part A was written 14 Oct 2026, BEFORE any trade outcome of any R3 period was computed. Only the lake's file list, the
session and expiry-day counts, and the earlier reports were read. It is committed and pushed on its own. Part B (the
exact filter features and cut values) is added after the design-only anatomy (Step 2), and BEFORE any variant is run
on any period. Part B may only fill in what Part A leaves open. After the Part B commit, nothing here changes. A
change needs a new entry, a new pre-registration and a fresh holdout.

Boss's request (14 Oct 2026): "We closed mostly all the arms, not a single trade from morning, what should we do? Do."
TODO.md R3: the ORB family was PAUSED (not retired) on 13 Oct after losing in every backtest year. Once the full Dhan
download is in the lake, study them deeply and try to enhance them. A version that passes the research gates may come
back on PAPER at once (CLAUDE.md: logged in the Autopilot change timeline, labelled, undoable in one tap, never live by
itself). A version that fails stays a note only. Research only: R3 changes no app code.

## A1. Data

- Lake (Drive `IraAlgo-data-lake`, catalog `data-20261014`), read straight from Parquet by `r3data.py`:
  - `options_rolling_1m/opt_<UND>_WEEK_<YEAR>`: Dhan "rollingoption", the NEAREST expiry on or after the day,
    ATM-10..ATM+10, chosen each minute. This is the ORB family's own contract rule (`OrbRules.expiryOnOrAfter`:
    today's option on an expiry day). After Nov 2024 the nearest BANKNIFTY / FINNIFTY / MIDCPNIFTY expiry is the
    monthly, as the app would trade it.
  - `index_1m_<UND>_<YEAR>` and `index_1m_INDIAVIX_<YEAR>`.
- Sessions (after 15 warm-up sessions for ATR14), built 14 Oct 2026:

  | index | sessions | first | last | expiry days |
  |---|---|---|---|---|
  | BANKNIFTY | 1,255 | 2021-09-03 | 2026-10-12 | 189 |
  | NIFTY | 1,284 | 2021-08-02 | 2026-10-12 | 271 |
  | FINNIFTY | 1,254 | 2021-09-03 | 2026-10-12 | 171 |
  | MIDCPNIFTY | 906 | 2022-02-21 | 2026-10-12 | 101 |
  | SENSEX | 845 | 2023-05-15 | 2026-10-12 | 175 |

- Expiry days come from the data (as R25): ATM time value at 15:15 under 0.6% of spot and the next session's more
  than twice it.
- 13 Oct 2026 (BANKNIFTY's 630-point opening range) is not in the lake (it ends 12 Oct). It is a forward example only.

## A2. The four arms, rebuilt exactly (the app's rules, `android/engine/.../orb/*`, `OrbArms.kt`)

Shared:
- 5-minute bars labelled by their start, built from the 1-minute index bars; only completed bars.
- Opening range = high / low of the bars 09:15 .. 10:00 inclusive (1-minute rows 09:15 .. 10:04).
- Strike = ATM of the 09:20 bar's close (the 09:24 minute's close), half-up to the index step (BANKNIFTY / SENSEX 100,
  NIFTY / FINNIFTY 50, MIDCPNIFTY 25); fixed all day; the nearest expiry on or after the day.
- Entry = 1 lot at the next minute's open after the signal bar closes (a print within 3 minutes, else no trade);
  refused when that premium is at or below the stop points.
- Stop = a resting SL-M at fill - P (tick-floored), P = 40 on BANKNIFTY (`OrbRules.STOP_POINTS`), scaled for the other
  indices by price level: NIFTY 20, FINNIFTY 20, MIDCPNIFTY 10, SENSEX 60. Target = +P (Sweep +2P).
- Profit-lock ladder (`ProfitLock.LADDER`): at +25% of the target lock breakeven-after-charges, at +50% lock +25%, at
  +75% lock +50%; the peak is the best 1-minute high BEFORE the current minute; the lock only rises.
- Target checked on the minute's high, sold at the next minute's open. Stops fill at min(level, the minute's open).
- Out at 15:10 (the 15:10 minute's open); 15:05 on an expiry day (`ExpirySquareOff`: today's option).
- Cooldown: after an exit, the next decision bar must start after the exit's 5-minute bar. One position per arm.

Per arm:
- **ORB**: decision bars 10:05 .. 13:55; close > high -> CE, close < low -> PE. Paper cap 1 entry per side a day
  (R14 `ReentryCap.PAPER_CAP`, since 13 Oct). The pre-cap version (unlimited re-entries) is rebuilt too, as a check.
- **ORB Fresh**: ORB, and the previous bar must not already be beyond the same edge. Same cap.
- **ORB Sweep**: decision bars 10:05 .. 14:25; high > range high and close < range high -> PE; low < range low and
  close > range low -> CE. At most 2 entries a day. Target +2P, ladder on +2P.
- **Range Fade**: decision bars 10:30 .. 13:55; w = high - low; high >= range high - 0.1w and close < range high -> PE;
  low <= range low + 0.1w and close > range low -> CE. At most 2 entries a day.

Not rebuilt (stated): the R12 trade manager's specialists (paper ACT on ORB Fresh only; R12 tested them), ArmPriority
(Liquidity first on BANKNIFTY), AutoExposure across arms, FlowGate, the account Guard. Each arm is simulated on its own,
1 lot, as every earlier ORB study did.

## A3. Costs and fills (every rule, every period)

- Charges: the app's model at today's rates (`research/obuy/costs.py` `Costs("app")`; BSE rates for SENSEX).
- Fills: `Fills("liq")`: +-5 bps plus a half-spread of 1 / 2 / 4 ticks by the contract's lots traded in the 5 minutes
  before (>= 20 / some / none); stops 10 bps and double ticks.
- Lot in force on the day (`r3cfg.LOTS`). Rupees are per 1 lot.
- Held strike outside the lake's ATM+-10 band (a move of over 10 strikes): its exit uses the last stored price before
  (counted and reported).

## A4. Holdout (locked; read ONCE for all of R3)

**There is no lake block that no ORB-type study has read.** Inventory (14 Oct 2026):

| study | data | ORB-family periods read |
|---|---|---|
| R12, R14, H20, H19 | Dhan, BANKNIFTY | the app's four arms, Aug 2021 - 6 Oct 2026 (design + holdout 1 Oct 2025 - 6 Oct 2026) |
| H43 | Dhan, all 5 indices | generic ORB 15/30 (not the app's rules), Aug 2021 - Oct 2026, a 1.8 million-version grid incl. FINNIFTY / MIDCPNIFTY / SENSEX |
| R16 - R22 | release: BANKNIFTY Feb 2024 - Feb 2026, NIFTY Apr 2024 - Apr 2026 | design and holdout, all read |
| R23 | release, the same | all design; its lake read (27 Apr 2026 on) not run |
| R20c, R23 anatomy | FINNIFTY index only (no options) | ORB-range break events, Feb 2024 - Feb 2026 |
| R24 | lake, NIFTY + BANKNIFTY | holdout 27 Apr - 12 Oct 2026 read (option outcomes under the ORB exits) |
| R25 | lake, expiry days | holdout to 31 Dec 2022 read (Hero only) |

So BANKNIFTY (every year) and NIFTY (Apr 2024 on, and 27 Apr - 12 Oct 2026) are spent for this family.

**Choice: an out-of-instrument holdout. Holdout = every session of FINNIFTY, MIDCPNIFTY and SENSEX in the lake
(FINNIFTY 1,254, MIDCPNIFTY 906, SENSEX 845 sessions). Design = every session of BANKNIFTY and NIFTY.**

- These are the indices on which the four arms' exact rules (range, signals, ATM-of-09:20 strike, -P/+P, the ladder)
  have never been run with option prices.
- Honest caveats, stated before the read:
  - It is out-of-instrument, not out-of-time: the same market days as the design data, and FINNIFTY / SENSEX move
    with BANKNIFTY / NIFTY. A day-type effect learnt on design days is partly "seen" on the same holdout days.
  - H43's generic ORB grid computed option outcomes on these indices (different range, exits and timing). R20c / R23
    looked at FINNIFTY index breaks (no options).
  - The stop / target points of the holdout indices are scaled by price level (A2), fixed here before any outcome.
  - So a pass here is weaker than a pass on a fresh time block. The 13-26 Oct paper run is the true forward test.
- Nothing in R3 computes an option outcome on a holdout index before `study.py holdout`. `r3data.load` refuses a
  holdout index without the read flag. The read writes the flag and `results/holdout.json`, and refuses a second read.

## A5. Steps

1. **Rebuild** (design indices): the four arms (plus ORB uncapped) on BANKNIFTY and NIFTY, every year. Compare with the
   earlier numbers (R14 capped ORB by year; R12 / R14 uncapped counts; FamilyPark's TESTED; R19c / R20 V0). Report per
   year and index.
2. **Anatomy** (design indices only): when each arm wins and loses, by
   - day type (trend / range: the close's place in the day's range, and whether the day closed inside the opening
     range; known only after the close, so descriptive only),
   - VIX level (previous close) and VIX change to the decision, the gap (vs ATR14), the opening-range width (vs ATR14;
     incl. "wide" days like 13 Oct and how often price stays inside the range until the last entry), expiry vs not,
     the entry time, the index, the re-entry number, the side, the year.
3. **Part B** (committed before any variant is run): the filter feature and cut for each arm, by the rule in A6.
4. **Design runs** of the 16 variants (A6) and their baselines, per year and index; walk-forward (A7); random-entry
   twins (A8).
5. **The one holdout read** of all 16 variants and the 4 baselines (`study.py holdout`).

## A6. The 16 variants (at most 16; 4 per arm)

For each arm X in {ORB, ORB Fresh, ORB Sweep, Range Fade}:

- **X-F (filter)**: skip the entries in the arm's worst tercile of ONE point-in-time feature. The feature and side are
  chosen in Part B by this fixed rule, on the design years 2021-2024 ONLY (2025-2026 is kept as a walk-forward check):
  - candidates (each known at the signal bar's close): previous VIX close; VIX change from 09:15 to the signal (%);
    gap = (today's open - previous close) / ATR14, in the trade's direction; opening-range width / ATR14; signal time;
    expiry day (yes / no; a two-way split, not terciles); distance from the morning's time-weighted mean price (TWAP
    of 1-minute closes from 09:15) to the signal close, in the trade's direction, / ATR14;
  - terciles of the feature over the arm's design-year 2021-2024 trades (BANKNIFTY + NIFTY pooled; cuts = the
    33rd / 67th percentiles);
  - the chosen feature has the largest gap between its best and worst tercile in mean net Rs per trade, among those
    whose worst tercile is the same tercile on BANKNIFTY and on NIFTY separately. The filter skips that worst tercile.
- **X-E (exits, Boss's R19c consistency rules)**: no fixed target (let winners run); the arm's ladder stays; plus
  - a step lock: once open profit reaches +Rs 1,000 a lot, keep at least +Rs 500 a lot;
  - a give-back trail: from +Rs 1,000 a lot, sell Rs 500 a lot below the peak;
  - a time stop: if 45 minutes after entry the peak never reached +25% of the target, sell at the next open.
  - (All stops rest as SL-M, tick-rounded up, and only rise; the highest stop wins.)
- **X-S (strike and cap)**: 1 strike in the money (CE ATM - step, PE ATM + step) and at most 1 entry a side a day (ORB
  and ORB Fresh have this cap already; for Sweep and Fade it is new).
- **X-C (combined)**: X-F + X-E + X-S together.

Baselines (not counted as variants): the four arms as the app has them today (A2).

## A7. Walk-forward

- Per year (2021 .. 2026) and per index, every variant and baseline, on the design indices.
- The Part B filter is chosen on 2021-2024; 2025-2026 design results are its first out-of-sample check (reported
  separately).
- Nested selection (descriptive): for each test year Y in 2023 .. 2026, pick the variant with the best net over the
  design years before Y (among the 16 + 4 baselines), trade it in Y; report the sum against the baselines.

## A8. Gates (a variant passes only if ALL hold)

Design (BANKNIFTY + NIFTY, every design session):
- D1: net > 0 after costs;
- D2: net > 0 in at least 4 of the 6 calendar years 2021 .. 2026;
- D3: BANKNIFTY net > 0 (the app's index);
- D4: net in 2025-2026 > 0 (the walk-forward block of the filter).

Holdout (FINNIFTY + MIDCPNIFTY + SENSEX, read once):
- H1: pooled net > 0 after costs;
- H2: beats its own arm's baseline on the holdout;
- H3: random-entry twins: p < 0.05 (A9);
- H4: Benjamini-Hochberg q < 0.10 on BOTH its holdout t-test p and its twin p, over the whole ORB-family count (A10);
- H5: net > 0 on at least 2 of the 3 holdout indices.

All 16 variants and 4 baselines are read on the holdout in the one read (no further selection), so the BH count is
fixed in advance.

## A9. Random-entry twins and the t-test

- Twin of a variant on a set of sessions: the same sessions, the same number of entries per session and the same
  sides, but each entry at a random 5-minute bar close inside the arm's decision window (uniform), with the variant's
  strike and exits. 1,000 twins, seed 3. p = (1 + twins with net >= the variant's) / 1,001.
- t-test: one-sided, mean net per index-session (0 on a session without a trade) > 0.

## A10. Multiple testing (Benjamini-Hochberg, q 0.10)

m = 183 p-values:
- R3's 32 holdout p-values (16 variants x {t-test, twin});
- the ORB family's earlier tests, at their reported p-values: R20 14, R20b 14, R20c 12, R22 18 (holdout), R23 22
  (release), R18 20 (ORB-family holdout, descriptive), R19c 24 (ORB-family arm x variant sign tests);
- R17's 27 ORB-family tests (24 design variants + 3 holdout) at p = 1 (not all reported; conservative).
- The list is in `results/prior_p.json` (source of each number).

## A11. Reports

Per variant: trades, trades per day, win rate, average win / loss, net, net per day, % green days, worst day, max
drawdown, big winners cut short (a trade sold by its target or a lock whose option later reached entry + 2 x target
before the square-off), design vs walk-forward vs holdout, BH q, sample sizes. `research/HUNT_R3_ORB_FAMILY.md` starts
with "Verdict for Boss". Experiment entries `research/experiments/entries/r3-*.yaml` (every variant, the rebuild, the
anatomy). Tests (`test_r3orb.py`) include the look-ahead guard: every signal, feature and entry is unchanged when every
minute after the decision is replaced by noise.

## If a variant passes (paper only; the parent session builds it)

Paper only, on BANKNIFTY (the arm's index), logged in the Autopilot change timeline, labelled "R3", undoable in one
tap, never live by itself. Its exact settings will be written in the report.

---

## Part B (14 Oct 2026, after the design-only rebuild and anatomy, BEFORE any variant is run on any period)

Filled in by the fixed rule of A6 (`study.py partb` -> `results/partb.json`, design years 2021-2024, BANKNIFTY +
NIFTY baseline trades). Nothing in Part A changes.

| arm | chosen feature | worst tercile skipped | skip interval | tercile means Rs/trade (low / mid / high) | trades 2021-24 per tercile |
|---|---|---|---|---|---|
| ORB | VIX change 09:15 -> signal (%) | mid | -0.8638 <= x < 1.0372 | -101 / -148 / -63 | 549 / 549 / 549 |
| ORB Fresh | VIX change (%) | mid | -0.8638 <= x < 1.0372 | -101 / -148 / -64 | 549 / 549 / 549 |
| ORB Sweep | VIX change (%) | low | x < -0.9926 | -178 / -120 / -106 | 686 / 685 / 687 |
| Range Fade | expiry day | expiry days | expiry = 1 | not expiry -116 / expiry -177 | 1,937 / 317 |

Noted before any variant runs: on the 2021-2024 design trades, the BEST tercile of every candidate feature still loses
money for every arm (the least bad: ORB, VIX change high third, Rs -63 a trade). A one-feature filter can therefore
lose less but is not expected to turn an arm positive. The 16 variants are exactly A6 with these filters:

- `r3-<arm>-F`: the baseline + the skip above.
- `r3-<arm>-E`: no fixed target; the arm's ladder stays (on +P, Sweep +2P); step lock +Rs 1,000 -> keep +Rs 500 a lot;
  give-back trail from +Rs 1,000, Rs 500 a lot below the peak; time stop: at 45 minutes, sell if the peak never
  reached +25% of P (Sweep 2P).
- `r3-<arm>-S`: 1 strike in the money and at most 1 entry a side a day.
- `r3-<arm>-C`: F + E + S.
