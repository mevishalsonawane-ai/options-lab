# R109 pre-registration: IraThinker's two strategies (mom60 and the BANKNIFTY weekly short strangle) on real prints

26 Oct 2026. Written and pushed BEFORE any R109 profit or loss is computed. Branch `claude/r109-irathinker-rules` (from
main). Research only: nothing in `android/**` or `irathinker-app/**` changes; nothing goes live.

## 0. Why

Boss: "Do we get to learn something from IraThinker?" IraThinker (`irathinker-app/` on main, Boss's separate alert app)
paper-tracks two strategies from its own studies. Its live paper log prices them on a Black-Scholes tape (index 20-day
realised vol, no chain), and its alerts carry study footers we have never checked on our own data. R109 tests the rules
EXACTLY as the source states them, on our lake's real option prints, with our fills and dated charges, and measures how far
a BS tape is from real fills.

Rules read from the source on 26 Oct (`irathinker-app/app/app/src/main/java/com/irathinker/app/`): `Mom60.java`,
`Mom60Idx.java`, `Mom60Engine.java`, `Mom60IdxEngine.java`, `Strangle.java`, `StrangleEngine.java`, `OptionsMath.java`.

**Signal source:** mom60 reads the index spot's own trailing 60-minute return (the underlying, CLAUDE.md default) and buys
the option; its stop is on the option premium (allowed). The strangle has no directional signal: strikes are set off the
index spot at 09:30. No rule reads an option's own chart.

## 1. The rules (as IraThinker states them)

### mom60 (Mom60.Watcher.process)
- 1-minute index bars, closed bars only. ret60 at bar i = close[i] / close[the bar that started exactly 60 minutes
  earlier, same session] - 1; missing either bar = no signal (so the first possible signal bar is 10:15).
- Fire when |ret60| >= the threshold: up -> buy the ATM CE, down -> the ATM PE, of the nearest MONTHLY expiry.
- Entry at the next minute's open (the signal bar's close time). Last entry 14:30 (signal bar starting at or before 14:29).
- One position at a time; entries at least 60 minutes apart (entry to entry); the bar on which a position resolves is not
  also judged for a new entry (the source's `continue`).
- Exit: the premium stop when a minute's CLOSE of the held option <= 70% of the entry price (checked first), else the time
  exit 60 minutes after entry. In the engine below both sell at the next minute's open.
- Thresholds (the studies' train q90 of |ret60|, frozen): **BANKNIFTY 0.50%, FINNIFTY 0.5025%, SENSEX 0.4735%** (the
  study numbers Boss gave; IraThinker's live config rounds FINNIFTY to 0.50% and SENSEX to 0.47%, noted, not tested).
- Out-of-family checks (IraThinker does not run these; reported separately, gated and counted the same): **NIFTY** and
  **MIDCPNIFTY**, threshold = the q90 of |ret60| over every eligible signal bar (bar start 10:15-14:29) in the history
  period (section 3), computed by code from index bars only and frozen before the design run.
- Weekly-chain variant (NOT IraThinker's rule; reported separately, gated and counted): the same signals bought on the
  nearest WEEKLY expiry, for **NIFTY** and **SENSEX** (the two indices with weekly options after 20 Nov 2024).

### BANKNIFTY weekly short strangle (Strangle.buildEntry / grade)
- Every Monday (a Monday holiday skips the week): the 09:30 bar's close = spot; ATM = spot rounded to 100; SELL the
  nearest MONTHLY expiry's CE at ATM + 200 and PE at ATM - 200 at the next minute's open (09:31).
- Hold to Thursday's close (the last session on or before Thursday when Thursday is a holiday). When the monthly expiry
  falls on or before that Thursday (within the Monday-Thursday hold), the position instead settles at the expiry at
  intrinsic value against the index's last close of the expiry day (NSE settles on a last-30-minute average; stated).
- No stop. One entry a week.

## 2. Our engine (real prints; the same conventions as R64 / R101 / R103 / R105)

- **Data:** the Drive lake (`gdrive:IraAlgo-data-lake`) copied 26 Oct 2026 with rclone: `index_1m` (to 19 Oct 2026),
  `index_daily`, `participants/bhav_index` 2021-2026 (NSE expiry calendar, to 13 Oct), `options_rolling_1m`
  `opt_<UND>_MONTH_<YEAR>` for BANKNIFTY, FINNIFTY, SENSEX, NIFTY, MIDCPNIFTY and `opt_<UND>_WEEK_<YEAR>` for NIFTY,
  SENSEX (and BANKNIFTY WEEK, read only to confirm it equals MONTH after Nov 2024). Never in git.
- **Held contracts:** R51's store builder (`r51data.add_options`, Amendment A cleaning: foreign rows and stray prints
  removed) run on the MONTH series (and WEEK for the variant), so every trade is priced on the strike bought, never on the
  rolling series. A strike is the same contract across days until the series' monthly expiry passes.
- **mom60 strike:** the chain's own ATM label (offset 0) at the signal minute; the index close rounded to the step if
  missing. Entry fill: the first open of that contract in the 3 minutes from the entry minute (R64), else the signal is
  skipped (counted; no position, the spacing clock is not reset).
- **Fills:** `liq` slippage (5 bps + 1 / 2 / 4 ticks by the contract's traded lots in the 5 minutes before the order; stop
  orders 10 bps and twice the ticks). An exit with no open in its minute takes the first open in the next 5 minutes, else
  the leg is STALE and is repriced by Black-Scholes at the implied vol of its last print, the index at the exit minute,
  6.5% rate, time to 15:30 of its expiry, with thin-contract slippage (4 ticks + 5 bps) (R103 Amendment 2). A mom60 time
  exit due after 15:29 sells at the 15:29 minute's close.
- **Strangle exit:** buy both legs back at the open of the exit day's 15:29 minute (a leg with no open there: its latest
  close in 15:24-15:29, else stale-repriced as above). Expiry weeks: cash settlement at intrinsic, no charges on the short
  legs at settlement (exercise STT is the buyer's).
- **Charges:** `research/obuy/costs.py` 'dated' (STT on the sell side, exchange NSE or BSE, SEBI, stamp, GST, Rs 20 an
  order). **Lots:** the lot in force on the day (`research/hunt/r51/r51cfg.py` LOTS). 1 lot.
- **Expiry calendar:** NSE indices: the bhavcopy (`r102data`), monthly = the last expiry of each month; SENSEX and days
  after 13 Oct: R51's data test (the ATM straddle's time value collapses then jumps), cross-checked with the bhavcopy
  where both exist.

## 3. Periods

- **History (descriptive, not gated):** the first option day of each MONTH series (BANKNIFTY / NIFTY Aug 2021, FINNIFTY
  Aug 2021, MIDCPNIFTY 2022, SENSEX May 2023) - 19 Nov 2024. Used for the NIFTY / MIDCPNIFTY thresholds, the comparison
  with IraThinker's full-history claims, and the BS-vs-prints gap.
- **Design:** 20 Nov 2024 - 31 Mar 2026.
- **Walk-forward window:** 1 Apr 2025 - 31 Mar 2026 (inside design, as R107 / R108), reported by quarter. No parameter is
  fitted anywhere in R109, so walk-forward is the same frozen rule judged on later, separately counted data.
- **Final:** 1 Apr - 13 Oct 2026, read ONCE (`results/FINAL_READ`, the code refuses a second read). Not fresh: R51-R108
  read it.
- **Post-13 Oct:** 14 - 19 Oct 2026 (the lake's end, 4 sessions), read with the final, descriptive only.

## 4. Tests (8) and twins

| id | rule | chain |
|---|---|---|
| `m60-bn` | mom60 BANKNIFTY, 0.50% | monthly |
| `m60-fin` | mom60 FINNIFTY, 0.5025% | monthly |
| `m60-sx` | mom60 SENSEX, 0.4735% | monthly |
| `m60-nf` | mom60 NIFTY (out of family), history q90 | monthly |
| `m60-mid` | mom60 MIDCPNIFTY (out of family), history q90 | monthly |
| `m60-nf-w` | mom60 NIFTY, weekly-chain variant | weekly |
| `m60-sx-w` | mom60 SENSEX, weekly-chain variant | weekly |
| `strangle-bn` | BANKNIFTY Monday short strangle | monthly |

**Random-entry twin (mom60):** for each real trade, a twin on the same index and day, same direction (CE / PE), entered
at a uniformly random minute of the rule's entry window (10:16-14:30), ATM by the same rule at the minute before, the same
exits (30% premium stop on its own entry, 60-minute hold), same fills and charges. A pool of 50 twins per real trade
(seed 109); 400,000 draws of one twin per real trade; twin p = (1 + #{draw mean net >= real mean net}) / (draws + 1), per
period.

**Twin (strangle):** the same Monday and structure (ATM +- 200 off the spot at the random minute) entered at a uniformly
random minute 09:31-14:30, held to the same exit; pool 50, 400,000 draws, as above. Because a timing twin cannot test
whether selling itself pays, the strangle also needs a week-bootstrap p < 0.05 of its mean net in design (10,000
resamples, seed 109).

## 5. Gates (all must hold; never relaxed)

1. Net > 0 in design, in the walk-forward window, and in the final.
2. Beats its twin in each of the three: twin p < 0.05 in design, walk-forward and final.
3. Benjamini-Hochberg q <= 0.10 with p = the largest of the test's gating p-values (the three twin p's; for the strangle
   also its bootstrap p), over m = the 8 R109 tests + **14,245 earlier tests**: R64-R107 as R108 counted them (5,777),
   R108's 7, and R1-R63 counted as the larger of each study's logged entries and variants (8,461; R47's and R48's
   exhaustive searches, 130,176 and 8.8 million setups, count at their logged entries, 30 and 12). The earlier tests
   count as p = 1. Also reported: q over R109 alone, and q with every logged variant of R1-R108 (8,961,492).
4. At least 40 trades in design and 20 in the final.
5. Strangle only (selling, as R103): Boss's account rule at 1 lot in design, walk-forward and final: worst calendar month
   >= -Rs 13,000 and maximum drawdown < Rs 40,000; and the design bootstrap p < 0.05.

mom60's worst month and drawdown against the account rule are reported, not gated (the prompt's standard gates).

## 6. Reported for every test (each with n)

Trades, win rate, net Rs per lot (total and per trade), gross % of premium per trade, worst day / worst week, worst month,
max drawdown, months <= -Rs 13,000, quarter nets in the walk-forward window, stale legs, entries skipped for no print, and
per period: history, design, walk-forward, final, post-13 Oct. Strangle: margin estimate (13% of spot x lot, calibrated
to IraThinker's own Rs 2.14 lakh at BANKNIFTY about 55,000 x 30; a planning number, the broker decides), net / margin,
the 5 worst weeks in rupees, weeks <= -Rs 13,000 and <= -Rs 40,000.

## 7. The BS-vs-prints gap (descriptive)

For every real trade the same signal is also priced on IraThinker's own BS tape, as its code does: the entry at the signal
bar's close, strike = spot rounded to the step, expiry by IraThinker's rule (`OptionsMath.monthlyExpiry` last Tuesday /
`monthlyExpiryBse` last Thursday), IV = the index's 20-session realised vol (`OptionsMath.realizedVol`: population stdev
of the last 20 daily log returns x sqrt(252), clamped 10-90) from the sessions BEFORE the day, frozen for the hold;
repriced at each minute's close, the same stop and time exit, `bsPriceDays` (6.5% rate, 0.35-day floor, the same normal
CDF approximation). Strangle: `Strangle.grade` (BS at Monday 09:31 and at Thursday 15:30 with frozen IV; intrinsic in
expiry weeks; minus the flat 1.0 pp). Reported: mean % per trade on the BS tape vs on real prints (gross, and net of our
costs), win rates, and IraThinker's claimed numbers beside them.

## 8. Overlap with our paper arms (descriptive)

R72 (Breadth Donchian, C1 4-of-5) and R51 (Donchian 20) candidate SIGNALS (R101's `r72_signals` / `r51_signals` on index
bars; the app's R51 also has a Supertrend filter, so these are signals, not the app's trades): for each mom60 trade, is
there a same-index, same-day, same-direction R72 / R51 signal (a) on that day, (b) in the 60 minutes before its entry or
during its hold. Design and final.

## 9. What a pass means

A test that passes every gate may go on PAPER in IraAlgo (CLAUDE.md, 13 Oct): as a labelled paper arm, logged in the
Autopilot change timeline, undoable in one tap, never live. Building it is a separate task. A fail stays a note in
`research/experiments/entries/r109-*.yaml`. The strangle is option SELLING with ~Rs 2 lakh of margin per lot: even a pass
would be paper-only and the write-up must show its worst weeks in rupees.

## 10. Checks

`research/hunt/r109_irathinker/tests/test_r109.py`: ret60 and the signal walk on hand-made bars (spacing, one position, the
resolve-bar rule, last entry 14:30, gaps), the stop / time exits and fills, stale repricing, the strangle's expiry-week
settlement and holiday Thursday, the BS port against hand values, the twin p, BH with earlier tests, the final lock, and
the look-ahead guard (changing any bar after a decision minute never changes that decision). Plus
`python -m pytest research/brain/tests -q && python -m research.brain.features catalog --check`.
