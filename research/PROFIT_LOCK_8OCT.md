# Thu 8 Oct 2026: why the paper account touched +Rs 8k and fell back, and why "the profit lock is not working"

Code and data: `research/hunt/lock8/`.
- `fetch_today.py` pulls today's 1-minute candles from Dhan v2 `/charts/intraday`. Credentials are read at runtime and never printed.
- `day.py` holds Boss's 20 fills and the app's charges (`SandboxCosts.charge`).
- `reconstruct.py` builds the day's P&L path and the giveback attribution.
- `orb_section.py` runs the ORB arms trade by trade. `locks.py` ports `ProfitLock.kt` to Python.
- `sim_today.py` runs the what-ifs on today's data. `history.py` runs the multi-year check.
- Outputs are in `lock8/data/` (`curve.csv`, `history_paths.csv`, `history_liq_realised.csv`, `history.log`).

Everything is paper, 1 lot, option buying. Rupees are net of the app's charges unless marked "gross". The app's
screens show P&L before charges (gross). The daily loss limit uses the net figure.

## Verdict (read this first)

1. **The +8k was real on the screen, but it was mostly one open trade.** On Dhan's minute data the account reached
   **gross +Rs 8,000 at 14:10-14:12 and again at 14:21**. Within those minutes it touched up to +Rs 8.6k. Earlier it
   reached +6.5k at 13:30.
   - Net of charges these peaks were **+Rs 6,100** (14:11) and **+Rs 5,000** (13:30).
   - The day ended at **gross +Rs 5,801 / net +Rs 3,772**, with 20 round trips and **Rs 2,030 of charges**.
   - The worst dip was 13:30 → 13:39: net +4,967 → −2,108, a **Rs 7,075 swing in nine minutes**.
2. **Who gave it back.** Each fall was mostly **Jarvis Solo's open NIFTY put** (65 units). Its premium moved 30-40
   points at a time, which is Rs 2-3k a swing.
   - 13:30 → 13:39: Solo −2,785, the Liquidity 54700PE −2,004, the Pine 24650PE −2,285.
   - 14:11 → 14:15: Solo −1,804, Pine 24600PE −933.
   - 14:21 → 14:31: Solo −2,261 (its 14:30 time exit came after a fall from 364 to about 325).
   - **ORB was not the giver.** The seven ORB entries made money (see section 3).
3. **What is broken and what is by design.**
   - **ORB arms (ORB, ORB Sweep, Range Fade): the lock worked as designed.** The 7 Oct fix (38400e4) shows in the fills.
     Every lock exit filled within 1-3 points of the level that should have been resting.
     - Two of them turned green into red **by design**. ORB Sweep's first rung is +20, and that trade only reached
       +10.6. Range Fade peaked at +19.0, one point short of the +20 rung, so it was only locked at breakeven.
   - **Pine auto-trade: the lock is too weak and too slow, and the contract is too thin.** This is where "not working"
     is fair. Details in section 2.
     - On these FINNIFTY premiums (Rs 340-370) the lock keeps only breakeven until the option is +30 points or +8% up.
       Both Pine winners peaked at about +22 points (+6.2 to 6.5%), so all of that profit was allowed to go.
     - The lock is checked on one sampled price per watch pass (about 60-90 s apart), not on the highs. It is not a
       resting stop; it sells at market after the fact.
     - FINNIFTY's October monthly option hardly trades. The 09:30 trade's breakeven lock at 338.81 was gapped: the last
       trade was 342.05 at 10:11 and the next was 326.70 at 10:12. Even a resting stop would probably have filled
       near 326.70.
   - **Solo: no premium lock or trail exists, by design.** It has an index stop, a breakeven move on the index and a
     14:30 time exit. Today its premium went 261 → 364 (14:21) → 327 at the 14:30 exit.
   - **Liquidity 15+5: no premium lock, by design.** It exits at index levels, a 15% resting stop, an index stop and a
     20-minute time stop.
   - **There is no account-level day-profit protection anywhere.** The account guard and the loss breaker are loss-only,
     and Jarvis's "day target" only speaks.
4. **What would have kept the +8k? Nothing robust.**
   - A tight account lock (close everything after a 10-20% giveback, once the day is ≥ +Rs 5k net) would have kept
     +Rs 4.3-4.9k net today instead of +3.8k.
   - **On 5 years of the arms' replays, account-level day locks do not help beyond random stopping.**
     - On the full arms book they "save" money only because that book loses about Rs 1,300 a day, so fewer minutes in
       the market means less loss. Random stopping saves about as much (placebo p 0.2-1.0).
     - On Liquidity, the one arm with a positive record, every giveback lock **costs** money: −Rs 3 to −176 a day. The
       day's best days are exactly the ones a lock cuts.
   - A Solo premium trail would have **cut** today's best trade, from +4,199 to between +786 and +2,905.

### Fixes, ranked

| # | fix | what it is | today's effect (net) | evidence beyond today |
|---|---|---|---|---|
| 1 | **Pine: make the lock a resting SL-M that moves up (the ORB F1/F2 fix).** Track the best price on ticks/minute highs, and check Pine in the 15-second loop. | a real gap: Pine has neither, ORB does | Pine trades +797 with fills at the trigger; −125 if the thin book fills at the minute's low | same mechanism h20 measured for ORB |
| 2 | **Pine: stop auto-trading FINNIFTY monthly options**, or require a minimum minute volume / spread. 0-12 lots a minute, many minutes with no trade. | fills and locks on that contract are fiction | the 09:30 gap (−11.6 pts below the lock) and the 13:31 entry at a stale 368.90 | h24: FINNIFTY half-spread 0.42%, 3× BANKNIFTY |
| 3 | **Run every money exit in the 15-second loop** (Pine, Solo, Liquidity's index exits), not only the full pass. The full pass runs every *pass time + 60 s*. | timing | Pine 13:31 stop: hit at 13:37 (335.9), sold at 13:39 (332.4): Rs 210-370 | — |
| 4 | **Pine lock thresholds** (e.g. keep 50% from +5% instead of +8%). | design choice; **untested on history**: test with `hunt/h22/pine_replay.py` before changing | the two Pine winners: −695 → +558 and +229 → +552 net (resting, minute highs) | none yet |
| 5 | **Account-level day lock: only as a comfort rule, set high and loose.** For example, stop new entries at +Rs 8k net, or close everything on a 30-50% giveback from a +Rs 8k peak. | new feature | would not have fired today (net peak +6.1k) | about neutral on Liquidity, small and inconsistent gain on the arms book (section 5) |
| — | **Do NOT add a premium trail to Solo** from this day. | — | cuts Solo +4,199 → +786 / +2,905 | solo2 ablation: a 30/60 ladder cost −Rs 2.18 L on Solo's trades |
| — | **Do NOT cap ORB re-entries because of today.** | — | 7 ORB entries made +Rs 4.0k | h19/h20/h21 say ORB loses long-run anyway; judge it on its forward record |

## 1. The day's P&L path (Dhan 1-minute data, Boss's fills, app charges)

Method:
- Open positions are marked at each minute's close, net of the buy charges already paid.
- Closed trades count at their net.
- Gross is the same without charges, which is what the screens show.
- "Highs" marks open positions at each minute's high: the best a tick-level screen could have shown.

| time | net | gross | gross at highs | what was open |
|---|---|---|---|---|
| 09:25 | +1,354 | +1,563 | | MIDCP put sold +2,232 |
| 10:15 | +659 | | | Pine 24800PE gapped through its lock |
| 11:50 | −2,109 | | | ORB −1,448, Liquidity FIN −1,347 |
| 13:20 | +4,683 | +6,103 | +6,800 (13:21) | Solo, ORB #15, Liquidity 54700PE |
| 13:24 | +1,993 | +3,485 | | |
| 13:30 | **+4,967** | **+6,458** | +6,766 | Solo, Liquidity 54700PE |
| 13:39 | **−2,108** | **−521** | | Solo, Liquidity, Pine 24650PE stopped |
| 14:04 | +5,757 | +7,658 | +7,753 | Solo |
| 14:11 | **+6,101** | **+8,001** | +8,629 (14:10) | Solo, Pine 24600PE |
| 14:15 | +3,364 | +5,265 | | |
| 14:21 | +6,033 | +7,998 | +8,180 | Solo |
| 14:31 | **+3,772** | **+5,801** | | all flat (Solo out at 327.40) |

**Boss's "+8k several times" matches the gross screen at 14:04-14:12 and 14:21.** "Fell back to 2-3k" matches the net
figure at 13:22-13:24 (+1.9-2.2k), at 14:15 (+3.4k) and at the close (+3.8k). Gross at the close was +5.8k.

Giveback attribution (change in each trade's contribution, net):
- 13:30 → 13:39, −7,075: Solo −2,785, Liquidity 54700PE −2,004, Pine 24650PE −2,285.
- 14:11 → 14:15, −2,737: Solo −1,804, Pine 24600PE −933.
- 14:21 → 14:31, −2,261: Solo −2,261.
- From the 14:11 peak to the close, −2,329: Solo −1,637, Pine 24600PE −692.

Per trade (net; "best" is the best minute high while held):

| # | strategy | contract | in → out | net Rs | best open profit |
|---|---|---|---|---|---|
| 1 | Liquidity | BN 54900PE | 694.20 → 668.10 | −878 | none |
| 2 | Liquidity | MIDCP 13675PE ×120 | 219.20 → 238.75 | +2,232 | +25.9 pt (+3,102) |
| 3 | Pine | FIN 24800PE ×60 | 337.22 → 327.20 | −695 | +21.8 pt (+1,310) |
| 4 | ORB | BN 54900PE | 759.18 → 714.19 | −1,448 | +1.6 pt |
| 5 | Liquidity | BN 54800PE | 705.00 → 699.70 | −256 | +9.1 pt (+273) |
| 6 | Liquidity | FIN 24750PE ×60 | 357.80 → 336.95 | −1,347 | +8.2 pt (+492) |
| 7 | ORB Sweep | BN 54900CE | 825.15 → 842.85 | +424 | +41.8 pt (+1,252) |
| 8 | Solo | NIFTY 13OCT 22550PE ×65 | 261.33 → 327.40 | +4,199 | +102.7 pt (+6,674) at 14:21 |
| 9 | Range Fade | BN 54900CE | 835.95 → 838.66 | −26 | +19.0 pt (+571) |
| 10 | ORB Sweep | BN 54900CE | 833.00 → 792.21 | −1,328 | +10.6 pt (+318) |
| 11 | ORB | BN 54900PE | 773.70 → 779.87 | +83 | +28.3 pt (+849) |
| 12 | Liquidity | BN 54800PE | 731.60 → 735.55 | +19 | +25.3 pt (+759) |
| 13 | ORB | BN 54900PE | 771.90 → 811.90 *(est.)* | +1,096 | +41.7 pt |
| 14 | ORB | BN 54900PE | 767.00 → 774.62 | +126 | +22.6 pt (+678) |
| 15 | ORB | BN 54900PE | 795.85 → 835.85 *(est.)* | +1,094 | +43.9 pt |
| 16 | Liquidity | BN 54700PE | 714.50 → 699.40 | −550 | +35.5 pt (+1,065) |
| 17 | Pine | FIN 24650PE ×60 | 368.90 → 332.40 | −2,285 | 0 |
| 18 | ORB | BN 54900PE | 792.40 → 853.90 | +1,738 | +66.8 pt |
| 19 | ORB | BN 54900PE | 835.35 → 883.85 | +1,346 | +54.4 pt |
| 20 | Pine | FIN 24600PE ×60 | 351.80 → 357.25 | +229 | +21.7 pt (+1,302) |

Data notes:
- **MIDCPNIFTY contract (redacted in the paste).** The only strike whose minute ranges contain both fills
  (09:22 215.75-226.20 and 09:25 225.70-245.05) is **13675PE (27 OCT)**.
- **Missing ORB exits (#13, #15).** Both are booked at the +40 target. #13 hit +40 at 12:56 with no rung earned before.
  #15 is ambiguous:
  - The 13:19 minute low (798.75) went 0.55 point under the breakeven lock (799.30) that its 13:18 high (807, +11.15)
    had just earned. The rules would sell there for about −Rs 25 net.
  - Otherwise it reached +40 at 13:21.
  - **Only the target version reproduces Boss's +8k**, so it is the one used. If #15 was in fact the 13:19 breakeven
    exit, every figure after 13:19 is about Rs 1,120 lower.
- Dhan's first bar of the day is 09:16 (no 09:15 bar). Bars are labelled by their start minute, which is consistent
  with the fills.

## 2. Each strategy's protection in the code, and what it did today

**How often things are checked.** The watch runs a full pass, then waits 60 s (`Jobs.kt:1126`), so a full pass happens
every *pass time + 60 s*. Today's relay timeouts made passes long:
- A Pine candle fetch can take up to 15 s (`PineAuto.kt:384`).
- Index quotes can take up to 20 s each.

While anything is held, a lighter **15-second loop** (`Jobs.kt:1140-1162`) runs only three things: the paper book's
resting orders (`Paper.tick`), `OrbArms.priceCheckOnly` (ORB price checks only, `OrbArms.kt:991`) and `Protections`.

These run only in the full pass:
- **Pine** (`Jobs.kt:626`)
- **Solo** (`Jobs.kt:641`)
- **Liquidity's index exits** (`OrbArms.kt:959`)

Without a Zerodha stream, the paper price is the last Upstox 1-minute candle's close (`Paper.kt:134`). The stream
dropped many times today (11:41-12:37 among others).

| strategy | protection | how it is enforced | today |
|---|---|---|---|
| ORB / ORB Fresh / Range Fade | −40 SL-M; ladder +10 → breakeven after charges, +20 → +10, +30 → +20; +40 target | **resting SL-M moved up** on ticks or minute highs (`OrbArms.kt:1240`, `ProfitLock.raise`); paper fills at trigger, gap or tick; 15-s checks; the target is a market sell | worked (section 3) |
| ORB Sweep | the same on +80: +20 → breakeven, +40 → +20, +60 → +40 | the same | #7 locked +17.7; #10 never reached +20 |
| Liquidity 15+5 | 15% resting SL-M; index stop; 20-min time stop; next level / failed break / new level; **no premium lock** (`ProfitLock.kt:16`) | stop resting; index exits only on the 60-s pass (`OrbArms.kt:959`) | #16 was +35.5 pts at 13:30, exited −15.1 at 13:40 (by design) |
| Pine auto-trade | stop/target (assumed 30/60, Boss's 06 Oct rule) plus a ladder on the target (+15 → breakeven after charges, +30 → +15, +45 → +30) plus a % trail (5% → breakeven, 8% → keep 50%, 20% → 65%, 40% → 75%) | **one sampled LTP per full pass** (`PineAuto.kt:336`); the best price is raised only from that LTP (`PineAuto.kt:355`); exit is a **MARKET sell after the fact** (`PineAuto.kt:342`, `:622`); no resting order | see below |
| Jarvis Solo | index stop at 0.3 ATR on a 1-min close; index breakeven once 75% of 2R; 14:30 exit; **no premium stop, target or trail** (`SoloMidday.kt:42-47`, `IraSolo.kt:528-535`) | walk of index minutes per full pass; market close | ran to the 14:30 exit, giving back from 364 to 327 |
| Account | daily loss limit only (`AccountGuard.kt:19`, `LossBreaker.kt:37`); "day target" is a Jarvis message only (`IraJournal.kt:190`) | — | no profit protection exists |

**Pine, trade by trade.** The locks in the log are exactly breakeven after charges:
- 337.22 + Rs 1.59/unit = 338.81.
- 351.80 + 1.62 = 353.42.

**#3 (09:30, FIN 24800PE 337.22).**
- The best minute high was 359.05 at 09:46 (+21.8 pts, +6.5%). The app recorded "best 354.00", its sampled LTP.
- That earned only the breakeven rung. +30 pts or +8% was needed for more.
- The price stayed ≥ 339.75 until 10:11. In the 10:12 minute the next trades were at 326.70: a 15-point gap in a
  contract that trades a few lots a minute (10:02-10:09 had no trades at all).
- The app sold at 327.20. With a resting stop:
  - Filled at the trigger, as the app's backtests assume: 338.51, +Rs 677 better.
  - Filled where the market actually traded: 326.70, the same as what happened.
- **Verdict: armed correctly, too close to entry by design, and gapped by illiquidity.** The 60-s poll was not the
  cause here.

**#17 (13:31, FIN 24650PE 368.90).**
- The buy price was the last trade of the 13:30 minute. The 13:31 minute had no trades at all.
- The 30-point stop (338.90) was crossed at 13:37 (335.90). The app saw it on the next pass and sold at 13:39 for
  332.40.
- **The poll cost Rs 210-370.**

**#20 (14:02, FIN 24600PE 351.80).**
- The minute high was 373.50 at 14:10; the app's sample was 366.80. Both give only the breakeven rung (+21.7 pts = 6.2%).
- The breakeven was crossed at 14:15 (352.15). The app sold at 14:16:50 for 357.25, a lucky price.
- A resting stop would have sold at 352-353, Rs 250-305 worse.

## 3. The ORB arms today (Boss: "ORB was also positive multiple times, then became loss")

Rules as fixed in 38400e4:
- **ORB / Range Fade (+40):** +10 → breakeven after charges, +20 → +10, +30 → +20.
- **Sweep (+80):** +20 → breakeven, +40 → +20, +60 → +40.
- The stop is max(−40, lock), resting and moved up.

"Rules on minutes" walks that stop on Dhan's minute highs and lows: the rung counts from the next minute, and fills are
at the trigger or gap open less 10 bps.

| # | arm | in | peak (pts / Rs) | rung reached | stop that should have rested | rules on minutes | actual |
|---|---|---|---|---|---|---|---|
| 4 | ORB | 11:31 759.18 | +1.6 / +49 | none | 719.18 (−40) | stop 718.46 @11:36 | 714.19 @11:36, −1,448 |
| 7 | Sweep | 11:41 825.15 | +41.8 / +1,252 @11:57 | +40 → lock +20 | 845.15 | lock 844.30 @11:58 | 842.85 @11:58, +424 |
| 9 | Range Fade | 12:05 835.95 | +19.0 / +571 @12:12 | +10 → breakeven | 839.55 | rung earned on the 12:08 spike and broken in the same minute → market sell ≈ 830 @12:09 | 838.66 @12:15, −26 |
| 10 | Sweep | 12:21 833.00 | +10.6 / +318 | none (first rung +20) | 793.00 (−40) | stop 792.21 @12:31 | 792.21 @12:31, −1,328 |
| 11 | ORB | 12:38 773.70 | +28.3 / +849 @12:39 | +20 → lock +10 | 783.70 | lock 782.92 @12:40 | 779.87 @12:40, +83 |
| 13 | ORB | 12:51 771.90 | +41.7 / +1,251 @12:56 | target | — | target @12:56 | not in log (est. target +1,096) |
| 14 | ORB | 13:05 767.00 | +22.6 / +678 @13:07 | +20 → lock +10 | 777.00 | lock 773.48 @13:08 | 774.62 @13:08, +126 |
| 15 | ORB | 13:15 795.85 | +43.9 / +1,316 @13:21 | +10 → breakeven by 13:18 | 799.30 | breakeven 798.55 @13:19 (low beat the lock by 0.55) | not in log (est. target +1,094) |
| 18 | ORB | 13:40 792.40 | +66.8 / +2,004 @13:45 | target | — | target @13:45 | 853.90 @13:45, +1,738 |
| 19 | ORB | 13:55 835.35 | +54.4 / +1,633 @13:59 | target | — | target @13:59 | 883.85 @13:59, +1,346 |

**ORB re-entries.** There were **7 ORB entries, all on BANKNIFTY 27OCT 54900PE**: 11:31, 12:38, 12:51, 13:05, 13:15,
13:40 and 13:55. That is the first entry plus 6 re-entries.
- **ORB net +Rs 4,034**, with charges of Rs 730.
- **ORB family** (ORB +4,034, Sweep −904, Range Fade −26): **+Rs 3,104 net** on 10 trades.

**Why green trades turned red.**
- **ORB proper: none did.** Every ORB trade that got to +20 kept at least +6 points. The three that reached +40 sold at
  or above target.
  - The family's running P&L went red at 11:36 (the 11:31 stop) and only turned green for good at 13:42, so on the
    ORB row it looked "positive, then a loss" around 13:21-13:42.
- **Sweep #10 (+10.6 → −40.8)** is the design. Sweep's first rung is +20, so a +10 move has no protection. Sweep's
  stop is the full −40.
- **Range Fade #9 (+19 → about 0)** missed the +20 rung by one point, so it only had breakeven.
  - It also shows the remaining weak spot from h20: a rung earned on a one-minute spike (the 12:08 high 847, close
    831.55) cannot rest, because a stop must sit below the price (`ProfitLock.kt:97`). The app's own check then sells
    at market below the lock (`OrbArms.kt:1176`, `:1276`).
  - The app got 838.66 by luck of timing. The rule says about 830.
- **The resting stop is being moved and filled.** Exits are 1-3 points under the lock, consistent with
  `Paper.restingFill` filling at the first stream tick under the trigger.
  - No sign of a missed SL-M modify, a re-entry bug or a feed problem in the ORB rows.
  - #4's stop filled 4.3 points under its −40 trigger (714.19 vs 718.46 expected): a tick gap in a fast fall
    (about Rs 128).

## 4. What-ifs on today's minutes (`sim_today.py`)

| scenario | day net |
|---|---|
| actual (reconstructed) | **+3,772** (peak net +6,101) |
| A. per-trade locks exactly as designed (ORB family walked on minutes) | +2,629. Of the −1,143 gap, −1,117 is #15 taking the 13:19 breakeven instead of the target and −254 is #9 selling below the lock; small fill differences make up the rest |
| B. Pine lock as a resting stop on highs/ticks | Pine +797 if fills land at the trigger; −125 if the thin book fills at the minute's low |
| C. Solo with a premium trail | Pine's default trail or "keep 50% from +10%": Solo out at 12:18 for +786 (−3,413). "Keep 75% from +20%": out at 12:35 for +2,905 (−1,294). "Give back at most 15% of the best": no change |
| D. account lock, stop new entries at +3k/+4k | −1,027 (blocks the 13:31-13:55 trades, which netted positive) |
| D. account lock, flat on a 30-50% giveback once ≥ +3k/+4k | −1,912 (fires at 13:22, before the ORB and Solo gains) |
| D. account lock, flat on a 10% / 15% / 20% giveback once ≥ +5k | **+1,143 / +950 / +507** (fires 14:05 / 14:07 / 14:13) |
| D. account lock, flat on 30% / 50% from +5k | −328 / 0 |
| D. account lock, anything starting at +6k or +8k net | 0 to +507 (the net peak was only +6.1k) |

Only a tight trail that starts high would have helped today. Slightly different settings (+4k, or 30-50%) lose
money today. That is the sign of a rule fitted to one day.

## 5. History: does an account-level day lock help over many days? (`history.py`)

**Book 1: minute paths.** This is h19's replay of the phone's arms with app fills and charges, 1 lot, August 2021 to
6 Oct 2026. Pre-holdout is up to 30 Sep 2025; the holdout runs from 1 Oct 2025.

The table shows the change in Rs per day against no lock, as pre / holdout. The placebo p is the share of 200 random
same-action, same-minute stops that saved at least as much.

| rule | all six arms (base −1,242 / −1,394 a day) | p (pre / ho) | Liquidity only (base +160 / +351 a day) | p (pre / ho) |
|---|---|---|---|---|
| stop entries at +3k | +93 / +223 | 0.81 / 0.62 | −10 / +26 | 0.62 / 0.19 |
| stop entries at +5k | +81 / +158 | 0.24 / 0.17 | −7 / +13 | 0.69 / 0.27 |
| stop entries at +8k | +39 / +113 | 0.30 / 0.02 | −11 / +19 | 0.86 / 0.12 |
| flat on 20% giveback from +3k | +8 / +55 | 1.00 / 0.91 | **−121 / −25** | 1.00 / 0.54 |
| flat on 30% giveback from +5k | +50 / +47 | 0.54 / 0.66 | **−32 / −85** | 0.95 / 0.94 |
| flat on 10% giveback from +5k | +31 / −8 | 0.90 / 0.89 | **−48 / −176** | 0.97 / 1.00 |
| flat on 30% giveback from +8k | +32 / +91 | 0.31 / 0.04 | −0.2 / +23 | 0.47 / 0.05 |
| flat on 50% giveback from +8k | +36 / +18 | 0.02 / 0.46 | +5 / +0.1 | 0.17 / 0.44 |
| floor at X/2 once ≥ +5k | +52 / −31 | 0.03 / 0.92 | −3 / −70 | 0.51 / 0.97 |

The other settings (12 per book, plus the ORB-only book) are in `lock8/data/history_paths.csv`.

**Book 2: realised P&L only.** This is h24/h36's Liquidity table (BANKNIFTY + MIDCPNIFTY, 1-ITM monthly, real-spread
costs). "Stop new entries once the realised day ≥ +X" gives:
- −13.5k / −16.8k / −12.9k before the holdout (X = 3k / 5k / 8k).
- −4.6k / +1.8k / +2.2k in the holdout.

Giveback rules almost never fire there (0-1 day), because Liquidity rarely has more than 2-3 closed trades a day.

What it says:
- **On the full arms book every lock "helps" a little, but no more than random stopping.** That book loses about
  Rs 1,300 a day, so any rule that leaves the market early saves a bit. The p-values flip between periods; no setting
  is significant in both. This matches h19's 16 pre-registered rules.
- **On Liquidity, the only arm with a positive record, giveback locks cost money.**
  - A 10-30% giveback from +3k or +5k costs Rs 25-176 a day.
  - On the best 5% of days the 20%-from-+3k rule removes Rs 2.2 L before the holdout.
  - The loose versions (30-50% from +8k) are about neutral, because they almost never fire (1 day in 25-50).
- **So an account lock cannot turn "+8k fell to +3k" into a better long run.** It trades Boss's best days for comfort
  on some others.
  - If he wants it anyway: **set it high and loose** (stop new entries at +8k net, or flat on a 30-50% giveback from a
    +8k peak).
  - Expect it to fire about once a month and to cost or save little.

## Bugs and gaps found (file:line, android/ unchanged)

1. **`PineAuto.kt:336-366`: the Pine lock reads one sampled price per watch pass.**
   - The best price is raised only from that price (`:355`). It is never raised from the minute highs or the stream's
     ticks, unlike ORB's F2 fix (`Paper.highSince`).
   - Today it recorded "best 354.00" where the high was 359.05, and "best 366.80" where it was 373.50.
2. **`PineAuto.kt:342` / `:622`: the Pine lock and stop are not resting orders.** They are a MARKET sell placed after
   the sampled price is already through the level.
3. **`Jobs.kt:626`, `:641`, `OrbArms.kt:959` vs `Jobs.kt:1140-1162`: Pine, Solo and Liquidity's index exits run only in
   the full watch pass.**
   - That pass runs every pass time + 60 s (`Jobs.kt:1126`). The 15-second loop covers only ORB price checks, the paper
     book and Protections.
   - Today this cost the 13:31 Pine trade Rs 210-370.
4. **`Paper.kt:134`: without the Zerodha stream, every paper price is the last 1-minute candle's close.**
   - With today's stream drops, Pine and Solo saw at most one price a minute.
   - Paper buys in a minute with no trades fill at a stale price (Pine #17 at 368.90).
5. **`ProfitLock.kt:97` + `OrbArms.kt:1176/1276`: a rung earned and lost inside one look cannot rest** (a stop must sit
   below the price), so it becomes a market sell below the lock. Known from h20; Range Fade #9 today. This is by
   design and cannot be fully fixed for paper or live.
6. **No account-level profit protection.**
   - `AccountGuard.kt:19` (maxDailyLoss) and `LossBreaker.kt:37` are loss-only.
   - `IraJournal.kt:190` "day target" only sends a message.
   - This is a design gap, not a bug, and the history above says it is not worth much.

Not bugs:
- ORB's ladder and resting stop worked as fixed on 7 Oct.
- Liquidity has no premium lock by design (`ProfitLock.kt:16`).
- Solo has only index exits by design (`SoloMidday.kt:42-47`).

## Assumptions and limits

- **Fills are Boss's paste.** The MIDCP strike is inferred, and two ORB exits are estimated (#15 is ambiguous, see
  section 1).
- **Pine settings are assumed** (stop 30, target 60, default trail). They are consistent with every lock and stop
  level in the log.
- **Minute data is Dhan's.** The app's paper feed (Upstox candles or Zerodha ticks) can differ by a few points.
  Tick-level paths inside a minute are unknown, so "resting stop" fills are given both at the trigger and at the
  minute's low for the thin FINNIFTY contracts.
- **History covers only the arms h19 replayed** (ORB family, Liquidity BN + FIN) and h24's Liquidity BN + MIDCP table.
  There is no multi-year replay of Pine or Solo inside one combined account. Today's book mixes all five strategies.
