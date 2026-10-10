# HUNT h22: which automated paper strategy made Boss's +Rs 5k days (1 Oct and 5 Oct 2026)?

Code: `research/hunt/h22/`.
- `solo_learner.py`: Solo as it ran on 5 Oct, with its learning brain.
- `pine_replay.py`: Boss's two known Pine scripts, traded the way the Pine auto-trader trades them.
- `liq_old.py`: Liquidity 15+5 under the rules the phone actually had on 1 Oct morning.
- `liq_old_stats.py`: the long-run record of that Liquidity version.
- `orb_noladder.py`: h19's ORB-family port, with and without the profit lock.

Caches are in `scratchpad/hunt/h22/`. Everything is option BUYING, 1 lot, with the app's paper fills (+5 bps on
buys, -5 bps on market sells, -10 bps on stops) and SandboxCosts charges.

## Plain verdict for Boss

1. **1 Oct (+Rs 5,032): Liquidity 15+5 under its first-day rules.** On 1 Oct the phone ran the *first* version of
   Liquidity: armed for the first time that morning, ATM strike, 15% premium stop, exit at the next liquidity level,
   no index stop, no 20-minute time stop, entries to 14:30. In the replay that version made **+Rs 8,045 on 1 Oct
   (7 trades)**. BANKNIFTY fell about 1,000 points after noon, and two 5-minute BANKNIFTY puts rode it to their
   targets for +Rs 3,780 and +Rs 3,491.
   - The ORB family netted about -Rs 1,040 that day without the profit lock, or -Rs 3,618 with it (the lock reached
     the phone only around midday).
   - Liquidity plus the ORB family gives **+Rs 4,400 to +Rs 7,000 on 23-27 round trips**, which brackets Boss's
     +Rs 5,032.
   - Boss's "38 trades" is 38 *fills* (the P&L calendar counts paper trade-book rows, so buys and sells separately).
     That is 19 round trips, close to this book.
   - h19 found Liquidity at only +341 because it replayed the later rules. The index stop and the 20-minute time stop
     reached the code at 13:11 IST on 1 Oct, after both big put trades had been entered.
2. **5 Oct (+Rs 4,955): none of the automated strategies I can replay made it.**

   | strategy on 5 Oct | Rs |
   |---|---|
   | ORB family | -6,282 to -8,856 |
   | Liquidity, 5 Oct rules | -3,707 (h19) |
   | Liquidity, if the phone still had the 1 Oct rules | +408 |
   | Solo (the learning brain) | 0 trades; one losing trade in one warm-up setting |
   | Pine: Matrix v2.2 | +2,872 |
   | Pine: Supertrend + 50 EMA | -3,388 (5m) / -6,522 (15m) |

   - **No combination of these reaches +Rs 4,955.** The best possible subset is about +Rs 3,280.
   - The 48 fills (24 round trips) fit the ORB family plus Liquidity, but their P&L does not.
   - So the green on 5 Oct came from something no replay can see:
     - Jarvis's own paper trades (the news and pattern ideas he took alone from 4 Oct; at most 2 a day, +40-point
       target, so at most about +Rs 5k);
     - a Pine script other than the two I know of;
     - Boss's own manual or voice orders.
   - **Only the paper trade log (by "who placed it") can settle 5 Oct.**
3. **Should Boss trust the 1 Oct winner? Trust Liquidity a little; the +Rs 8k day itself proves nothing.** One day is
   luck-sized: the version's own daily swings are several thousand rupees.
   - **Long run, 1-Oct rules exactly (Aug 2021 to 6 Oct 2026, net of charges):**
     - +Rs 1.25 lakh over 2,925 trades.
     - Locked holdout (Oct 2025 on): +Rs 44,917, about Rs 180 a session, t 0.74, bootstrap p 0.32.
     - Random entries with the same exits lost about Rs 1.3-1.5 lakh in each period, so the entries carry real
       information.
     - It still loses about half of all months, with a -Rs 40k max drawdown.
   - So it is **a weak real edge worth about Rs 100-180 a day per lot. It is not a Rs 5k/day method**, and +Rs 8k was
     an unusually good trend afternoon.
   - Also, the app no longer runs this version: today's Liquidity (1-ITM, room filter,
   index stop, time stop) is the better-documented one (+Rs 2.42 lakh 2021-26 in the validated port, +Rs 86k in the
   holdout per h19). **Nothing that made the 5 Oct green can be trusted, because nothing replayable made it.**
4. **Everything else replayed is a long-run loser**, so a green day from any of them is noise:

   | strategy (1 lot) | period | trades | net |
   |---|---|---|---|
   | Solo, the 5 Oct learning brain (NIFTY + BANKNIFTY) | 2021 - 6 Oct 2026 | 3,997 | **-Rs 6.29 lakh** (8 of 69 months green; holdout -1.53 lakh) |
   | Pine Supertrend+50EMA, BANKNIFTY 5m | Aug 2021 - Oct 2026 | 1,565 | -Rs 2.13 lakh |
   | Pine Supertrend+50EMA, BANKNIFTY 15m | Aug 2021 - Oct 2026 | 539 | -Rs 1.68 lakh |
   | Pine Matrix v2.2, BANKNIFTY 5m | Aug 2021 - Oct 2026 | 1,491 | -Rs 2.21 lakh |
   | ORB / Fresh / Sweep / Fade | 2021-26 (h19/h20/h21) | | -Rs 9.8 / 2.5 / 2.9 / 2.7 lakh |

## What the app was running each day (from git history; times in IST)

| feature | first able to trade | notes |
|---|---|---|
| ORB, ORB Fresh | before 28 Sep | the arms sat idle all day on 29 Sep (bug fixed 29 Sep 15:41); the profit lock was added 1 Oct 12:00 (build 12:29) |
| ORB Sweep, Range Fade | 29/30 Sep | |
| Pine auto-trade | since 26 Sep | the scripts live on the phone, so which ones were armed is unknown. Duplicate scripts were armed (switched off by the 6 Oct fix) |
| **Liquidity 15+5** (BN 15m+5m, FIN 30m+5m) | **1 Oct** (added 1 Oct 00:46) | 1 Oct morning: 15% premium stop, exit at the next level or on a failed break / new liquidity, ATM, entries to 14:30. The index stop and 20-minute time stop came 1 Oct 13:11 (build 13:29); the 14:00 entry cut-off 1 Oct 21:17; the room filter and 1-ITM 6 Oct 15:09 |
| Solo (Jarvis trades by himself) | 3 Oct (Sat), so **5 Oct** was its first session | the learning brain, no daily cap, 30% stop, NIFTY + BANKNIFTY, paper only |
| Jarvis acts on his own ideas (news / pattern) | 4 Oct (Sat), so 5 Oct | at most 2 a day, 15% stop, +40 target, profit lock |
| "Jarvis plans the day" (parks arms by regime) | 4 Oct (Sat), so 5 Oct | places no trades; it only switches paper arms off and on |
| Hero (NIFTY expiry) | 6 Oct 10:13, off by default | its 13:30-14:45 window is after the data ends on 6 Oct, so it cannot be replayed |
| Solo midday | 7 Oct 00:35 | after the window |

So **on 1 Oct, Solo, Jarvis's own trades, Hero and the regime planner did not exist yet**. The only automatic traders
were the ORB family, Liquidity, Pine scripts and the Strategies baskets (the presets are option selling, and Boss
buys only).

## Per strategy per day (net Rs after charges, 1 lot; round trips in brackets)

Boss's paper account, for comparison: 28 Sep -56 (2 fills), 29 Sep +589 (2), 30 Sep -6,014 (12), **1 Oct +5,032
(38)**, **5 Oct +4,955 (48)**, 6 Oct -4,551 (40).

| strategy | 28 Sep | 29 Sep (exp) | 30 Sep | **1 Oct** | **5 Oct** | 6 Oct (to ~12:10) |
|---|---|---|---|---|---|---|
| ORB, no lock | -1,618 (5) | 0 | +2,055 (9) | -2,505 (9) | -2,200 (11) | 0 |
| ORB Fresh, no lock | -1,282 (1) | 0 | -1,666 (3) | -3,019 (4) | -1,339 (1) | 0 |
| ORB Sweep, no lock | not armed | +2,337 (1) | -2,629 (2) | +2,292 (1) | -2,659 (2) | 0 |
| Range Fade, no lock | not armed | +2,285 (2) | -2,629 (2) | +2,192 (2) | -2,658 (2) | -157 (1) |
| ORB family with the lock (h19) | -2,229 | +1,006 | -4,556 | -3,618 (20) | -6,282 (19) | +991 |
| **Liquidity, rules of 1 Oct morning** | not yet armed | not yet armed | not yet armed | **+8,045 (7)** | +408 (4) | -140 (1) |
| Liquidity, rules in force that day (h19) | | | | +341 (3) | -3,707 (3) | 0 |
| Solo learning brain (5 Oct version) | did not exist | | | did not exist | 0 (0), or -1,656 (1) at a 10-day warm-up | not priced (NIFTY expiry) |
| Pine Supertrend+50EMA 5m | 0 | not priced | 0 | +2,420 (2) | -3,388 (1) | 0 |
| Pine Supertrend+50EMA 15m | 0 | not priced | -368 (1) | 0 | -6,522 (1) | 0 |
| Pine Matrix v2.2 5m | -2,316 (1) | not priced | +4,358 (1) | -494 (2) | +2,872 (1) | 0 |
| Jarvis's own trades | did not exist | | | did not exist | not replayable (≤2 trades) | not replayable |

**Reconciliation, day by day:**
- **1 Oct, +5,032 / 19 round trips.** Liquidity (first-day rules) at +8,045 is the only large winner.
  - With all four ORB arms and no lock: +7,005 over 23 round trips.
  - With the lock from about midday (h19's lock figures): about +4,427 over 27 round trips.
  - Without plain ORB: +9,510 over 14 round trips.
  - Boss's figure sits inside this range. Live fills (the phone samples LTP, a different quote) easily move a day by
    ±Rs 1-2k.
- **30 Sep, -6,014 / 6 round trips.** ORB Fresh + Sweep + Fade without the lock: -6,924 over 7 round trips. This is a
  close fit, with no Liquidity yet.
- **28-29 Sep, 1 round trip each, -56 / +589.** Neither matches any arm's trades. The arms were idle on 29 Sep (fixed
  15:41 that day), so these were most likely hand trades. The app's own test uses "589, 2 trades" for that Tuesday.
- **5 Oct, +4,955 / 24 round trips.** Not explained by any replayable automatic strategy (see the verdict).
- **6 Oct, -4,551 / 20 round trips.** The data ends about 12:12, and that day's rule changes landed mid-session, so
  6 Oct cannot be reconciled.

## Solo on 5 Oct, in detail

**Rules (git 0498ec6, ported line by line from `Learner.kt` / `IraSolo.kt`):**
- Three online logistic "views" per index, looking 15, 30 and 60 minutes ahead, retrained every minute.
- A view trades only when **all** of these hold:
  - its last 400 confident guesses are at least 55% right;
  - the guess is confident (|p - 0.5| ≥ 0.10);
  - its band of sureness is not below 55%.
- Of the views that would trade, it follows the one with the best record.
- One trade at a time, no daily cap, Rs 5,000 daily loss stop.
- ATM option, nearest expiry after today. 30% resting stop; out when the view's horizon has passed, or at 15:10.

**Warm-up sweep.** The brain was first built on the morning of 5 Oct from whatever days the phone held, so I swept the
warm-up from 5 days to the full history.
- At every warm-up from 5 to 250 days, and with the full history, every view's band of sureness was below 55%. It
  **never traded on 5 Oct**.
- The one exception is a 10-day warm-up: 1 trade, NIFTY CE, -Rs 1,656.

**Long run.** The same brain run continuously from 2020 and trading every session:
- 2021-6 Oct 2026: 3,997 trades, -Rs 6.29 lakh (gross -Rs 3.64 lakh).
- Every year red. Holdout (1 Oct 2025 on): -Rs 1.53 lakh.

**Solo is not the source, and it is not an edge.** It was replaced by Solo midday on 6 Oct.

## Pine scripts

- **The scripts on the phone are not in the repo.** I replayed the two the repo knows Boss wrote:
  - Supertrend(10,3) + 50 EMA (research/ST_EMA_PINE.md);
  - Matrix Premium v2.2 (research/MATRIX_PINE.md).
- **How the auto-trader trades them** (PineAuto.kt as of 1 Oct, defaults: BANKNIFTY 5m, 1 lot, strategy position,
  put on short, no stop or target):
  - on each completed candle, a change of the strategy's position sells the held option and buys the ATM option of
    the new side, nearest expiry after today;
  - 15:15 square-off.
- **They trade 0-2 times a day**, so they cannot produce 19-24 round trips. On the two green days they made:
  - 1 Oct: +2,420 (ST 5m) and -494 (Matrix);
  - 5 Oct: +2,872 (Matrix).
- **All three lose over 2021-26:** -1.7 to -2.2 lakh each.
- **Armed duplicates.** The 6 Oct fix switched off armed duplicate scripts, so Boss's phone may have run the same
  script twice. That doubles both the fills and the P&L.

## Liquidity 15+5, 1-Oct rules: long-run record (the honest protocol)

Nothing here was tuned. These are the app's rules as they stood on 1 Oct, replayed on Aug 2021 to 6 Oct 2026,
BANKNIFTY and FINNIFTY, 1 lot each, app paper fills and charges.

| | pre-holdout (Aug 2021 - Sep 2025) | **locked holdout (1 Oct 2025 - 6 Oct 2026)** | all |
|---|---|---|---|
| trades | 2,203 | 722 | 2,925 |
| gross / charges / **net** | +2.31 L / 1.51 L / **+Rs 80,515** | +1.12 L / 0.68 L / **+Rs 44,917** | +3.44 L / 2.18 L / **+Rs 1,25,432** |
| Rs per session | +78 | +180 | +98 |
| t (daily) | 1.03 | 0.74 | 1.26 |
| block-bootstrap P(mean ≤ 0) | 0.14 | 0.32 | 0.12 |
| winning trades | 39% | 42% | 39% |
| worst day / best day | -12,413 / +21,430 | -9,077 / +27,258 | |
| worst month | -19,090 | -24,194 | |
| green months | 27/50 | 9/13 | 36/63 |
| max drawdown | -40,326 | -36,489 | -40,326 |
| P(losing month), bootstrap | 49% | 49% | 49% |

Per year (net): 2021 (Aug-Dec) -10,314; 2022 -13,317; 2023 +2,310; 2024 +22,701; 2025 +59,077; 2026 (to 6 Oct)
+64,976.

**Random entries with identical exits** (same day and book, random minute 09:20-14:30, random side, the same target
distance and exit delay; 30 draws): -Rs 1.52 lakh before the holdout and -Rs 1.29 lakh in it. **No draw came close to
the real signals (p < 0.03 in both periods).** The level-break entries carry real information that random timing does
not.

**Reading it:**
- **It is a weak edge, not luck.** The entries beat random entries by Rs 1.7-2.3 lakh with identical exits. The
  version stays green net of charges before and in the holdout, and the last three years are all green.
- **It is far too weak to judge from a day or a week.** It makes about Rs 100-180 per session at 1 lot. It loses in
  about half of all months, and its single days swing from -Rs 12k to +Rs 27k.
- **The +Rs 8,045 on 1 Oct is about 45-80 average sessions' worth in one day.** That was one strong trend afternoon,
  not the method's normal pace.
- **The app's current Liquidity is better documented** (1-ITM, room filter, index stop, time stop; +Rs 2.42 lakh
  2021-26 in the validated port, with a positive holdout per h19).
  - The current version lost on 1 Oct (-568) and on 5 Oct (-3,078). The first-day version would have won on 1 Oct.
    That is the day-to-day noise of exit rules, not a reason to switch.
  - Comparing the two versions was not pre-registered here, and picking the better one after seeing 1 Oct would be
    hindsight.
- **Not Rs 5,000/day.** At its long-run pace, Rs 5,000/day would need about 30-50 lots, far beyond the capacity and
  Rs 1 lakh capital limits in H10/H13/H14.

## Limits of this replay

- **What could not be replayed:**
  - Jarvis's news and pattern trades (they depend on live headlines and the nightly pattern study);
  - Pine scripts other than the two known ones;
  - manual or voice orders;
  - Hero on 6 Oct (the data ends);
  - anything after about 12:12 on 6 Oct.
- **Expiry days are not priced for strategies that buy the next expiry** (Pine, Solo, Liquidity on 29 Sep; Solo on
  NIFTY on 6 Oct), because Dhan's data holds only the nearest contract.
- **Fills differ live.** The phone fills paper at the LTP it samples, not at minute opens. Single trades can differ by
  a few points, and stop exits by more.
- **To settle 5 Oct for certain:** export the paper trade book for 1, 5 and 6 Oct with the "who placed it" label
  (Origins). Every fill carries it ("Strategy: Liquidity 15+5 · entry", "Jarvis: news · entry", "Pine · <name> ·
  entry", "Manual · ...").
