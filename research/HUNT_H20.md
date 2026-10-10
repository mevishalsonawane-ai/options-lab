# HUNT h20: the ORB arms' profit lock, and whether a smaller target or tighter lock makes them pay

**Verdict.** The profit lock is "not working as expected" for two reasons, and both are real:

1. **The app does not do what the backtest assumed.** The lock never moves the resting stop. It is a market sell the app
   sends after a sampled price (every 15 s, and on paper without Zerodha only the last 1-minute candle's close) has
   already fallen through the lock. So a spike that turns back inside one sample is never locked. A rung that is
   earned sells about 3 to 6 premium points below its level. On real BANKNIFTY option minutes, the "breakeven" lock exits
   lose money after charges 56-64% of the time: Rs -43 per ORB lock exit on average, against Rs +28 in the
   optimistic resting-order model the research used. There are also 3 smaller bugs and gaps (listed below).
2. **The market does reach the rungs. The arms just have no edge.** 79% of ORB trades touch +10 and 48% touch +40
   before the -40 stop. 77% of the trades that touch +10 still end below entry if nothing protects them. The lock catches
   these trades, but the entries are no better than random entries with the same exits (p 0.16-1.0). Every arm loses
   even GROSS, with no charges and no slippage.

**No smaller target, tighter ladder, trail or time exit makes any of the four arms profitable.** I pre-registered 24
trials (6 exits x 4 arms). All 24 lose before the holdout: BH q >= 0.95, SPA p = 1.0, and the walk-forward is negative
in every year for every arm. The locked holdout (1 Oct 2025 - 5 Oct 2026, 248 sessions, run once) also loses on every
arm. **Rs/day at 1 lot (holdout, lot 30-35, net of app costs): ORB -857, ORB Fresh -98, ORB Sweep -300, Range Fade
-272. All four together lose about Rs -1,500 a day.** Rs 5,000/day from these arms: **NO.**

Code: `research/hunt/h20/` (`PREREG.md` was written before any result, `sim.py`, `analyse.py`, `pre_variants.csv`,
`pre_report.json`, `holdout.csv`). The simulator's optimistic ("tick") mode matches obuy's validated engine exactly:
4,000 ORB trades, net difference Rs 0.00, 0 exit-minute mismatches.

---

## Part 1: code audit of the lock (no app changes made)

The ladder (`engine/.../orb/ProfitLock.kt:20`, `LADDER = 25/50/75% of the target -> lock 0/25/50%`) on a 40-point target
gives exactly Boss's +10 -> breakeven, +20 -> +10, +30 -> +20. Commits: 4a8103d (1 Oct, ladder) and 4618b05 (6 Oct,
breakeven = entry + the round trip's charges, `ProfitLock.kt:52-58`, `roundTripPerUnit` `:112`).

| question | expected (Boss / docs) | actual code path |
|---|---|---|
| When is it evaluated? | continuously | On every watch pass: once a minute (`Jobs.kt:624` -> `OrbArms.tick` -> `priceCheck`, `OrbArms.kt:942`), plus every **15 s** while a position is open (`Jobs.kt:1125-1150` -> `priceCheckOnly`, `OrbArms.kt:975`). Not on bar close. It runs only while the app's foreground watch loop is running. |
| On which price? | the option's price | `Paper.lastPrice` (`Paper.kt:206`) -> `quote` (`Paper.kt:132-138`). This is the Zerodha stream's last tick when the app is logged in and streaming (`Paper.kt:196-203`). **Otherwise it is the last Upstox 1-minute candle's CLOSE.** It is never the high. The peak is the best of these 15-second samples (`OrbArms.kt:1202-1208`). Live positions use the Kite quote `last` (`OrbArms.kt:1701`). |
| Is the resting stop modified? | the stop moves up | **No, on paper or live.** The -40 SL-M placed at entry (`OrbArms.kt:1094-1097` paper; `placeStop` live) stays at -40. When `ProfitLock.exits` is true, `priceCheck` returns `"profit_lock"` (`OrbArms.kt:1155`, live `:1716`). `exit()` (`:1164`) or `exitLive()` (`:1730`) then cancels the SL-M and sends a **MARKET** sell. (By contrast, the app's manual `Protections.kt` trails do move resting orders.) |
| If that sell fails? | lock kept | Paper: `restop()` (`OrbArms.kt:1181-1186`) puts the SL-M back at the **original -40 trigger**. Live: `unsold()` (`:1733-1738`) re-places it from `p.entry`, also -40. Live with the cancel not yet confirmed: returns and retries on the next pass. The lock is checked again 15 s later, but between passes, or if the app dies, the downside is -40, not the lock. |
| "Counts from the next price" | yes | Correct as documented. `ProfitLock.exits(..., peakBefore, ltp)` (`ProfitLock.kt:62`) is fed `p.peak` before it is updated (`OrbArms.kt:1204-1208`). So a price that is itself the new best never sells on the same look. |
| Spike +15 and back within a minute | locks | **Never locks** unless a 15-second sample lands on it, and **never on the Upstox path**, which sees one close a minute. Real data: 11.4% of ORB trades (8-11% across the arms) touch +10 on the minute high but never close a minute at or above +10. |
| Lock below breakeven from charges rounding? | no | The level is floored at entry + charges (`ProfitLock.kt:54-56`), so the level is never below breakeven. The **fill** is: it is a market sell after the price has already crossed. The median fill is 5.6 points below the lock level for ORB (5.1 Fresh, 3.6 Sweep, 3.0 Fade), against 0.1-0.3 points in the resting-order model. |

**Concrete bugs and design gaps**

1. **The lock is app-side only (main gap).** No SL-M is modified, so the lock (a) fills worse than its level and
   (b) does not exist while the app's 15-second loop is not running: process killed, Doze, or no foreground service.
2. **The peak comes from samples, not highs.** On the paper/Upstox path it comes from minute closes. Short spikes never
   earn a rung, and the +40 target (also an app-side LTP check, `OrbArms.kt:1156`) misses spikes the same way. ORB:
   48.0% of trades touch +40 on the high, but only 41.8% close a minute there.
3. **A failed lock exit falls back to the -40 stop, not the lock level** (`restop` `OrbArms.kt:1181`, `unsold` `:1733`).
4. **ORB Sweep's ladder is not +10/+20/+30.** `SweepRules.TARGET_POINTS = 80` (`SweepRules.kt:22`, used by
   `ProfitLock.targetOf` at `ProfitLock.kt:27`), so its rungs are +20 -> BE, +40 -> +20, +60 -> +40. Boss expects +10/+20/+30.
5. **Live exits use `OrbRules.exitReason` (+40) for every arm** (`OrbArms.kt:1717`; paper uses SweepRules for Sweep at
   `:1156`). This is latent today because Sweep is paper-only, but a live Sweep would take +40 while laddering on 80.
6. **The research and the app disagree.** `ArmsBacktest.fill` (`ArmsBacktest.kt:127-137`) and `Replay.day`
   (`Replay.kt:39-49`) take the peak from minute HIGHS, fill at the lock level as if it were a resting order, and use
   no charges floor (`level(..., costPerUnit = 0)`). So `research/PROFIT_LOCK.md` measured an optimistic bound that the
   app's code path cannot achieve.

**Proposed fixes (NOT applied):**
- (F1) In `OrbArms.ladder()` / `priceCheck` / `priceCheckLive`, when a new rung is earned, **modify the resting SL-M
  trigger up to the lock level** (`Paper.modify`, and the Kite modify in the live path). Never lower it. If the modify
  fails, keep the current app-side market sell as a fallback and alert. This removes gaps 1 and 3.
- (F2) Build the peak from the best price since entry, not from 15-second samples. Use every stream tick (update
  `Position.peak` from the `KiteStream` tick handler). On the Upstox path, use the max HIGH of the minute candles since
  `entryTime`, which `Paper.quote` already downloads.
- (F3) `restop` / `unsold`: re-place the stop at `max(stopTrigger, current lock)`.
- (F4) If Boss means +10/+20/+30 points for every arm, give `ProfitLock` a points ladder for Sweep instead of 25/50/75%
  of 80.
- (F5) `priceCheckLive`: pick `SweepRules.exitReason` for Sweep, as the paper path does.
- (F6) Make `ArmsBacktest` / `Replay` model what the app does (sampled price, market sell, charges floor), or report both.

**What F1/F2 would buy:** per trade, the app moves from the "app" column to the "tick" column below (ORB: Rs -100 ->
-89 per trade). That makes the lock behave as designed. **It does not make any arm profitable.**

## Part 2: data (BANKNIFTY option minutes, app rules, app costs; pre-holdout Aug 2021 - Sep 2025, 996 sessions)

**How far the trades go** (the arm's trades as the app takes them; path with the -40 stop only, up to 15:10):

| arm | n | reach +10 (high / close) | +20 | +30 | +40 | spike +10 never closed | median give-back after reaching +10 | of those, end below entry |
|---|---|---|---|---|---|---|---|---|
| ORB | 6317 | 78.9% / 67.6% | 65.3% | 55.5% | 48.0% | 11.4% | 79 pts | 77% |
| ORB Fresh | 1898 | 77.7 / 66.4 | 65.3 | 55.1 | 47.3 | 11.2 | 79 | 77 |
| ORB Sweep | 1296 | 77.4 / 68.1 | 61.0 | 50.0 | 43.2 | 9.3 | 65 | 68 |
| Range Fade | 1416 | 75.6 / 67.4 | 59.1 | 48.9 | 42.1 | 8.2 | 66 | 69 |

So the market does reach the rungs. The typical trade goes +10 to +40, then gives everything back and hits -40.

**How the current ladder actually performed** (pre-holdout, net of app costs). "app" means a resting -40 stop, with the
lock and target sold at market on the next minute after a minute close crosses them. "tick" is the optimistic bound:
highs and lows, filled at the level.

| arm | app: net / per trade | exits lock / stop / target | lock fill vs level | lock exits losing | tick bound: net / per trade | no lock (app) |
|---|---|---|---|---|---|---|
| ORB | -632k / -100 | 45 / 33 / 22% | -5.6 pts | 64% | -703k / -89 | -539k / -117 |
| ORB Fresh | -217k / -114 | 45 / 34 / 21 | -5.1 | 62 | -201k / -102 | -244k / -142 |
| ORB Sweep (+80) | -217k / -168 | 43 / 43 / 11 | -3.6 | 56 | -200k / -151 | -173k / -146 |
| Range Fade | -228k / -161 | 53 / 32 / 14 | -3.0 | 58 | -198k / -136 | -234k / -183 |

The ladder improves the loss per trade on ORB, Fresh and Fade. Its exits come sooner, though, so ORB takes more trades
and pays more charges in total. Under the ladder the +40 target is hit by only 22% of ORB trades: the lock usually exits
first, near breakeven. **This is the "40 mostly doesn't touch the target" Boss sees.**

**Pre-registered alternatives (app mode, pre-holdout net, Rs):**

| arm | now | A1 tgt 15 | A2 tgt 20, +10->BE | A3 tgt 30, +10->BE, +20->+10 | A4 tight +8/+15/+20/+30 | A5 trail 50% from +10 | A6 ladder + 30-min time exit | best random p |
|---|---|---|---|---|---|---|---|---|
| ORB | -632k | -649k | -674k | -615k | -639k | -657k | -630k | 0.14 |
| ORB Fresh | -217k | -173k | -186k | -207k | -216k | -218k | -199k | 0.10 |
| ORB Sweep | -217k | -172k | -189k | -202k | -168k | -195k | -154k | 0.46 |
| Range Fade | -228k | -207k | -215k | -218k | -218k | -222k | -205k | 0.97 |

- GROSS (no slippage, no charges) is also negative for every one of the 32 rows. A target of 15 lifts the win rate to
  about 61%, but the average loss stays large.
- 24 trials: BH q >= 0.95 for all. White RC p = 1.0, SPA p = 1.0. 0/5 years positive for every variant. Walk-forward
  (2023-25, picking the best of 8 exits each year) is negative for every arm: ORB -349k, Fresh -99k, Sweep -108k,
  Fade -148k. **Nothing was promoted.**
- Worst month at 1 lot: about Rs -12k to -46k. Losing months: 38-48 of 50.

**Locked holdout** (1 Oct 2025 - 5 Oct 2026, run once). This is information only, since nothing was promoted. It covers
the app's current exits, the no-lock version, and the best pre-holdout alternative for each arm. App mode, net, 1 lot
of 30-35:

| arm | variant | trades | net | Rs/day | gross (headline) | net with spread | max DD |
|---|---|---|---|---|---|---|---|
| ORB | now | 1559 | -212,535 | -857 | -25,225 | -212,248 | -221,563 |
| ORB | A3 | 1604 | -220,026 | -887 | -22,851 | -225,487 | -222,154 |
| ORB Fresh | now | 458 | -24,228 | -98 | +34,509 | -25,872 | -37,905 |
| ORB Fresh | A1 tgt 15 | 446 | -46,130 | -186 | +15,037 | -47,112 | -58,876 |
| ORB Sweep | now | 328 | -74,341 | -300 | -41,608 | -75,256 | -79,323 |
| ORB Sweep | A6 | 348 | -80,579 | -325 | -42,649 | -79,114 | -79,081 |
| Range Fade | now | 368 | -67,492 | -272 | -19,157 | -67,499 | -67,508 |
| Range Fade | A6 | 377 | -61,237 | -247 | -17,976 | -61,271 | -61,503 |

ORB Fresh is the only arm that is gross-positive in the holdout. It loses its whole gross to charges and slippage.

## Answers

1. **Why the lock "isn't working":** mostly design, plus some bugs. It is a sampled, app-side market sell, not a
   resting stop. It misses intra-minute spikes and fills 3-6 points below its level, so "breakeven" exits are usually
   small losses. A failed exit falls back to -40, Sweep's rungs are on 80 points, and live Sweep uses the wrong target.
   Fixes F1-F6 are above and none were applied. The market reaches the rungs often (79% reach +10). The lock is doing
   its job of cutting give-backs, but it cannot create an edge the entries do not have.
2. **Does a smaller target or tighter lock make them profitable after costs?** No. None of 24 pre-registered variants
   works, before the holdout or in it. All of them lose even gross, and they are no better than random entries.
3. **Rs/day at 1 lot (net, app costs, holdout):** ORB -857, ORB Fresh -98, ORB Sweep -300, Range Fade -272; together
   about -1,527. Before the holdout, at their smaller historic lots, the figures were -635 / -218 / -218 / -229.
   Recommendation: keep these four arms paper-only or off. Apply F1-F3 anyway so the paper record reflects the lock Boss
   asked for.

Caveats: the data has no bid/ask, so fills are modelled ("app" +-5 bps, -10 bps on stops; the spread version is shown).
The app's "one index, one side" exposure rule between arms is not modelled (each arm is on its own). Lots follow the
date: 15-25 before the holdout, 30-35 in it.

---

## Follow-up (07 Oct): fixes F1-F6 applied, and the fixed lock replayed

Boss approved the fixes. The app now: (F1) moves the resting -40 stop UP to the lock (paper: the paper stop is modified;
live: the Zerodha SL order is modified, never cancelled and re-sold; never moved down; a refused modify keeps the old
stop, is said and is retried next look); (F2) takes the best price from every stream tick, else the 1-minute candle
HIGHS after the entry minute (a rung still counts from the next look), and checks the +target on those highs too; (F3)
puts a stop back at the current lock, not -40, after a failed exit; (F4) shows ORB Sweep's rungs as they are (+20 / +40 /
+60 on its +80, no rule change); (F5) gives each arm its own target live (Sweep +80); (F6) the paper book fills a resting
SELL SL-M at its trigger or the gap open from the minute candles / stream ticks, and ArmsBacktest / Replay rest one stop
at max(-40, lock) filled at the trigger or gap open.

Replay (`research/hunt/h20/fixed.py`, sim mode `fixed` = the fixed app: resting stop at max(-40, lock from minute highs),
fill at trigger / gap open -10 bps; +target an app check on the minute high, sold next minute). Same signals, lots, app
costs (net) and position rule as above; the app's own rules, nothing chosen. Output `research/hunt/h20/fixed.csv`.

**Locked holdout (1 Oct 2025 - 5 Oct 2026, 248 sessions), Rs/day at 1 lot, net:**

| arm | before (app until 07 Oct) | after (fixed lock) | change | trades before / after | lock exits after |
|---|---|---|---|---|---|
| ORB | -857 | -733 | +124 | 1559 / 1904 | 65% |
| ORB Fresh | -98 | -150 | -52 | 458 / 474 | 66% |
| ORB Sweep | -300 | -318 | -18 | 328 / 331 | 53% |
| Range Fade | -272 | -250 | +22 | 368 / 376 | 66% |
| Liquidity 15+5 | not laddered: unchanged | unchanged | 0 | - | - |
| **four arms** | **-1,527** | **-1,451** | **+76** | | |

Pre-holdout (996 sessions): ORB -635 -> -636, Fresh -218 -> -195, Sweep -218 -> -195, Fade -229 -> -199 Rs/day.

The fix does what Boss asked: lock exits now fill at the lock (or the gap), not 3-6 points under it, and they happen
without the app looking. It does not create an edge: every arm still loses after costs, before and in the holdout. ORB
Fresh is worse in the holdout because exits come sooner (fewer +40 targets, 25% -> 14%). Liquidity 15+5 has no ladder,
so its exits are unchanged; only its paper 15% stop now fills at its trigger / gap open like the others' resting stops.
