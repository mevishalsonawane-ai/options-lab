# R144b: why Liquidity 15+5, the ORB family and Solo 2.0 lose money, and which trades to skip

1 Nov 2026. Research only: nothing in the app changes. One of four parallel studies (R144a-d).

- Pre-registration: `research/hunt/r144b_loss_orb_liq/PREREG.md`:
  - dc86985, before any number;
  - amendment 1 in 07cc000, before any Solo 2.0 number or filter choice.
- Filters frozen from design data in 76a8702, before any test trade was priced.
- The final months were read once (`results/FINAL_READ`).

Boss, 1 Nov: *"Find all the lost trades ... find what all makes it lose money ... find patterns for each arm, so it
could help us to ignore those trades."*

## The answer in short

1. **No skip filter passed.** Four arms got filters chosen by the fixed rule (Liquidity 3, Range Fade 1). All failed
   in the test periods. No switch is proposed.
2. **ORB, ORB Fresh, ORB Sweep and Range Fade lose money on BANKNIFTY in the unseen years and in design.** ORB and
   ORB Fresh made a little in the final months (+Rs 22 a trade, 128 trades); Sweep and Fade lost there too.
   - Their flaw is the stop: on every one of these arms, the -40-point stop-outs are nearly all of the money lost.
   - About 9 in 10 ORB losers fall on days when every ORB trade lost.
   - No skip can fix an arm that loses on almost every kind of day. The best one (Range Fade, narrow morning range)
     halved the loss but left the arm losing.
3. **Solo 2.0 lost Rs 77,142 on 451 trades in Dec 2022 - Nov 2024**, the only years its model never saw.
   - Its design and final profits (+Rs 1,02,774 on 310 trades and +Rs 89,382 on 127) are on months the shipped
     model was trained on (amendment 1). They flatter it.
   - **This is the most important finding here:** Solo 2.0's edge is not shown out of sample.
4. **Liquidity made money in every period:**

   | period | trades | net |
   |---|---|---|
   | unseen years | 894 | +Rs 83,307 |
   | design | 881 | +Rs 2,57,249 |
   | final | 357 | +Rs 11,155 |

   - It loses on most trades: 69%, 56% and 63% of trades in the three periods.
   - Most of its losers come in bad days: 67-80% of them fall on index-days where every Liquidity trade lost.
   - **No entry-time sign was steady in all three periods.**
5. **Boss's 65% win rule:** no arm comes close in any period, before or after any filter.
   - The highest win rate of any arm in any period is Solo 2.0's 57.5% in the final months.
   - Liquidity's is 31-44%.
   - The ORB family's is 26-44%.

## Data and method (what was rebuilt, and how exactly)

**Data:** the Dhan lake, downloaded on 1 Nov from the shared Drive folder `IraAlgo-data-lake`:
- 90 files: index minutes 2021-2026 for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX and India VIX;
- weekly and monthly option minutes;
- `index_daily`.

The stores were rebuilt from it. Nothing raw is in git.

**Parity, every arm checked before use:**

| arm | check | result |
|---|---|---|
| ORB family | R3's design, BANKNIFTY + NIFTY, Aug 2021 - Oct 2026 | identical to the rupee: ORB 2,489 trades / -Rs 2,23,040; Fresh 2,489 / -2,23,812; Sweep 3,156 / -5,05,954; Fade 3,512 / -5,10,978 |
| Liquidity | R101's design and final | identical: 881 / +Rs 2,57,249 and 357 / +Rs 11,155 |
| Liquidity, old store (2022-24) | against the main store on the weeks both hold (20 Nov - 30 Dec 2024) | identical: 68 trades, -Rs 841 |
| Solo 2.0 | the app's frozen model on the final, against R57's monthly refits (128 / +Rs 99,172) | 127 trades / +Rs 89,382: close, as a frozen model should be |

**Which trades each arm is judged on:**
- the ORB family on BANKNIFTY, the app's index (other indices reported);
- Liquidity on its three books (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5);
- Solo 2.0 on NIFTY, BANKNIFTY, FINNIFTY and MIDCPNIFTY.

All 1 lot, after each engine's own charges and fills.

**Features:** 31 at entry, all read from bars closed before the entry minute (the look-ahead guard test checks this).
- **Signal source:** the index; its options and VIX as context.
- **Coverage:** ORB 100%. Liquidity 95% for option features, 65% for "since 09:30" changes (many entries come before
  09:30).
- **Missing:** 7 trades on 13 Oct 2026 have no features (the session store ends 12 Oct). Unknown never skips.
- **Not available, as pre-registered:** bid-ask spread, depth and the order book; index VWAP (TWAP used instead);
  futures volume and basis; breadth; the US overnight move. **Order flow is two proxies only.**

**"Steady"** means: in all three periods (unseen, design, final), >= 30 trades, a loser rate above the arm's, and an
average net below 0.

## Summary table

| arm (judged on) | unseen Dec 22 - Nov 24: n, lose, avg | design: n, lose, avg | final Apr - Oct 26: n, lose, avg | steady losing signs | filters chosen | passed |
|---|---|---|---|---|---|---|
| Liquidity 15+5 | 894, 69%, +Rs 93 | 881, 56%, +Rs 292 | 357, 63%, +Rs 31 | none | 3 | 0 |
| ORB (BANKNIFTY) | 456, 63%, -Rs 101 | 320, 62%, -Rs 129 | 128, 56%, +Rs 22 | 6 | 0 (none qualified) | - |
| ORB Fresh (BANKNIFTY) | same trades as ORB in all three periods | | | 6 | 0 | - |
| ORB Sweep (BANKNIFTY) | 628, 73%, -Rs 124 | 454, 74%, -Rs 248 | 176, 72%, -Rs 309 | 9 | 0 (none qualified) | - |
| Range Fade (BANKNIFTY) | 674, 70%, -Rs 124 | 500, 69%, -Rs 229 | 207, 67%, -Rs 144 | 13 | 1 | 0 |
| Solo 2.0 | 451, 60%, -Rs 171 | 310, 47%, +Rs 332* | 127, 43%, +Rs 704* | none | 0 (none qualified) | - |

\* in-sample for the shipped model.

## Liquidity 15+5

**How it loses:**

| period | trades | lose | money lost | average loss | longest losing streak |
|---|---|---|---|---|---|
| unseen | 894 | 69% | -Rs 3.50 lakh | -Rs 568 | 18 |
| design | 881 | 56% | -Rs 5.01 lakh | -Rs 1,009 | 13 |
| final | 357 | 63% | -Rs 2.23 lakh | -Rs 997 | 12 |

- **Exits** (the engine's labels are coarse: `rule_exit` covers the index stop, target, failed break and new
  liquidity together):
  - rule exits are the biggest share of the money lost: -Rs 1.48 lakh / -3.09 / -1.45;
  - time stops: -Rs 0.43 lakh / -1.16 / -0.50;
  - the 15% option stop: -Rs 1.58 lakh in the unseen years (156 trades, every one a loser), against -0.77 in design
    and -0.28 in the final. In 2022-24, when the indices had weekly expiries, the option stop was hit five times as
    often.
- **Bunching:** 80%, 67% and 69% of losers fall on index-days where every Liquidity trade lost.
- **Worst 20 trades** (all periods; 7% of all money lost; the worst -Rs 5,165, MIDCPNIFTY, 28 Mar 2025):
  - 11 were stop-outs;
  - they came on higher-VIX days (median 15.7 against 13.6) and at higher premiums (Rs 373 against Rs 270).
- **By index:** BANKNIFTY made money in all three periods (+Rs 110 / +328 / +84 a trade). FINNIFTY was flat in the
  unseen years (-Rs 3). MIDCPNIFTY lost in the final (-Rs 36).
- **Steady signs:** none. Signs that held in design (a big PCR rise since 09:30, falling ATM IV, a narrow 09:15-09:30
  range) did not hold in the other periods.
- **Flaws in plain words:**
  - it loses on most trades and lives on a few long runners (R141);
  - its losses bunch into whole bad days;
  - in a weekly-expiry market (2022-24) its 15% option stop is hit far more often.

**Filters (chosen on design, tested once):**

| filter: skip when ... | final: trades left, net (before -> after), twin p | unseen: trades left, net, twin p | why it failed |
|---|---|---|---|
| the put/call OI ratio rose >= 0.195 since 09:30 | 317, +11,155 -> +19,415, p 0.60 | 726, +83,307 -> +77,429, p 0.82 | no better than random skipping; worse drawdown and worst month in the unseen years |
| ATM IV fell by > 0.36 points since 09:30 | 308, -> +18,325, p 0.17 | 728, -> +69,518, p 0.83 | no better than random skipping; worse worst month in the unseen years |
| the 09:15-09:30 range < 0.32 ADR | 292, -> -6,156, p 0.95 | 611, -> +1,18,074, p 0.33 | the final turned into a loss; no better than random |

- The "trades left" column counts trades after the walk is re-run, as R140 did.
- Win rate before -> after, against Boss's 65%: final 37% -> 36-38%; unseen 31% -> 32-33%.

## ORB and ORB Fresh (BANKNIFTY)

- **The same trades:** on BANKNIFTY in these three periods, ORB Fresh took exactly ORB's trades. Everything below is
  true of both.
- **How it loses** (unseen / design / final):

  | | unseen | design | final |
  |---|---|---|---|
  | lose | 63% | 62% | 56% |
  | average | -Rs 101 | -Rs 129 | +Rs 22 |
  | money lost | -Rs 1.06 lakh | -Rs 1.12 lakh | -Rs 0.40 lakh |

- **Exits:** the -40-point stop is 84%, 83% and 79% of the money lost (108 / 65 / 20 trades, every one a loser).
  The profit lock's give-backs are the rest. Targets almost never lose.
- **Bunching:** 92%, 90% and 89% of losers fall on days when every ORB trade lost.
- **Worst 20:** all stop-outs, on higher VIX (median 14.9 against 13.5) and dearer options (premium Rs 1,076 against
  Rs 492). 15 of them are in 2026, after BANKNIFTY moved to monthly expiries and bigger lots.
- **By index:** every other index lost in the unseen years and in design too.
- **Steady losing signs** (all three periods, n >= 30 each):

  | sign at entry | unseen: n, lose, avg | design | final |
  |---|---|---|---|
  | a quiet signal bar (range < 0.81 ATR) | 91, 65%, -175 | 64, 70%, -225 | 31, 61%, -189 |
  | far below yesterday's high (calls) / above the low (puts): < -0.59 ADR | 95, 71%, -215 | 64, 72%, -374 | 30, 63%, -131 |
  | a gap against the trade (< -0.24 ADR) | 114, 69%, -196 | 64, 66%, -184 | 35, 60%, -23 |
  | option band volume a bit below usual (0.61-0.81 of median) | 94, 67%, -179 | 64, 63%, -330 | 38, 58%, -21 |
  | the option's last 5 minutes quiet (< 0.62 of its 5-min average) | 95, 63%, -88 | 64, 64%, -124 | 35, 57%, -133 |
  | high VIX (>= 15.8) | 56, 71%, -183 | 64, 63%, -63 | 48, 60%, -34 |

- **Why no filter:** none of these met the choice rule's bar (+5 points over the arm's loser rate in both design
  halves with n >= 30 each). The arm loses even outside them.
- **Flaws in plain words:**
  - it buys a break that has already happened;
  - it is stopped out on days with no follow-through: a quiet break bar, low volume, far from yesterday's level, a gap
    against it;
  - its losses come whole-day at a time.

## ORB Sweep (BANKNIFTY)

- **How it loses:**

  | | unseen | design | final |
  |---|---|---|---|
  | lose | 73% | 74% | 72% |
  | average | -Rs 124 | -Rs 248 | -Rs 309 |

- **Exits:** stop-outs are 95-98% of the money lost. The loss per trade grew each period.
- **Bunching:** 80-83% of losers fall on all-lose days. The longest losing streak is 19 trades.
- **Worst 20:** all stop-outs, in high VIX (median 18.4 against 13.6). 18 of them are in 2026.
- **Steady signs:** 9, all pointing one way. The sweep reversal fails on directional days:
  - the day already directional (top fifth: 80%, 76% and 76% lose);
  - the option in "short build-up" (its price falling while OI rises) into entry: 317 / 208 / 84 trades, 74-76% lose;
  - a middling push, gap or stretch.
- **Flaw:** it bets on a failed break, and on trend days the break does not fail.

## Range Fade (BANKNIFTY)

- **How it loses:**

  | | unseen | design | final |
  |---|---|---|---|
  | lose | 70% | 69% | 67% |
  | average | -Rs 124 | -Rs 229 | -Rs 144 |

- **Exits:** stop-outs are 94% of the money lost in every period.
- **Bunching:** 72-73% of losers fall on all-lose days.
- **Steady signs:** 13. The clearest:
  - no earlier fake poke today (385 / 322 / 152 trades, 68-73% lose): fading a range edge that has not yet rejected
    once;
  - a low put/call ratio (< 0.83);
  - a quiet day so far (day range in the lowest fifth);
  - a gap against the trade.
- **Its one filter: skip when the 09:15-09:30 range < 0.33 ADR.**
  - final: -Rs 29,811 -> -13,082 on 145 trades; losing days -66,769 -> -42,459; twin p 0.084;
  - unseen: -Rs 83,384 -> -40,002 on 368 trades; drawdown halved; twin p 0.035.
  - It failed: the arm still loses money, and BH q is 0.28.
  - It is the only R144b filter that beat random skipping in either period, and it is a loss-cutter on a losing arm,
    not a fix.
- **Flaw:** it fades the edge of a range that is not a range. On a narrow-morning day the "range" breaks.

## Solo 2.0

- **The result by period:**

  | period | trades | lose | average | net | in-sample for the model? |
  |---|---|---|---|---|---|
  | unseen (2022-24) | 451 | 60% | -Rs 171 | -Rs 77,142 | no |
  | design | 310 | 47% | +Rs 332 | +Rs 1,02,774 | yes |
  | final | 127 | 43% | +Rs 704 | +Rs 89,382 | yes |

- **Unseen, by setup:** S3 (open outside value) 339 trades, -Rs 48,626; S2 (Donchian) 103 trades, -Rs 17,393.
  It lost in each unseen year: -Rs 26,787 in 2023 and -Rs 44,932 in Jan - Nov 2024.
- **By index, unseen:** FINNIFTY -Rs 227 a trade (222 trades), MIDCPNIFTY -Rs 174 (155). MIDCPNIFTY carried the
  final, as R57 warned (87 trades, +Rs 850 a trade).
- **Exits:** stop-outs and time exits share the losses. Unseen: stops -Rs 1.82 lakh on 121 trades; time -Rs 1.05
  lakh.
- **Bunching:** with one trade a day, every losing trade is its whole day.
- **Worst 20:** 18% of all money lost. The worst is -Rs 14,592 on MIDCPNIFTY, 7 Apr 2025, a crash day.
- **Steady signs:** none. Nothing qualified for a filter.
- **Flaw:** the model's choice of which candidate to take has no out-of-sample record. Where it is out of sample, the
  arm loses.

## What this means (proposals only; nothing built)

1. **No paper or shadow skip switch is proposed:** no filter passed.
2. **Solo 2.0:** Boss may want to know that it lost money on the two years its model never saw. Its forward judge
   (switch off after 60 paper trades if net <= 0, or at -Rs 25,000) stays its guard. Whether to keep it on paper is
   Boss's call; nothing is changed here.
3. **The ORB family:** R144b agrees with R3 that the arms lose, and adds that it held in 2022-24 and that the stop-outs
   are almost all of the loss.
   - The steady signs (a quiet break bar, a gap against, low volume, high VIX) are leads for a fresh pre-registered
     test, not results.
   - The Range Fade narrow-morning skip is the one candidate that beat random skipping, though not BH.
4. **Liquidity:** no entry sign held up. Its losses are a day-level problem (bad days take every trade). The lead
   for a next study is a day-level stop or a cap after the first loss on an index-day, pre-registered with R140's
   gates.

## Files

- `research/hunt/r144b_loss_orb_liq/`:
  - `PREREG.md` (with amendment 1), `r144b_lib.py`, `r144b.py`, `test_r144b.py`;
  - results: `design.json` (frozen filters, design anatomy), `test.json` (every table by period: exits, bunching,
    worst 20, every feature by fifth, steady slices, filter tests), `pvalues.json` (8 raw p-values for the
    coordinator), `status.json`.
- Entries:
  - `research/experiments/entries/r144b-anatomy.yaml` (not-a-test);
  - `research/experiments/entries/r144b-filters.yaml` (rejected).
- To re-run: `IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144b_loss_orb_liq/r144b.py collect
  {orb|liq|solo} {design|final|unseen}`, then `design` / `test`. The final is read once.

## Addendum, 1 Nov 2026: did any filter raise the win rate? (Boss: "ignore 65%, check if the win rate is increased")

The win rate is the share of trades with net > 0. "Random p" is the share of 2,000 random skips of the same number of
trades whose win rate was at least as high (trade-level skips, no re-walk).

| filter | final: win before -> after, net, random p | unseen 2022-24: win, net, random p | win rate up in both? |
|---|---|---|---|
| Liquidity: skip a PCR rise >= 0.195 since 09:30 | 37.3% -> 38.2% (+0.9), +11,155 -> +19,415, p 0.10 | 31.0% -> 31.8% (+0.8), +83,307 -> +77,429, p 0.14 | **yes, but by < 1 point, not beyond random, and it cut 2022-24 net** |
| Liquidity: skip an ATM IV fall since 09:30 | 37.3% -> 37.3% (0.0), p 0.54 | 31.0% -> 31.9% (+0.9), p 0.09 | no (flat in the final) |
| Liquidity: skip a narrow 09:15-09:30 range | 37.3% -> 36.0% (-1.3), net turns -6,156, p 0.83 | 31.0% -> 33.4% (+2.4), p 0.01 | no (down in the final) |
| Range Fade: skip a narrow 09:15-09:30 range | 33.3% -> 33.8% (+0.5), p 0.36 | 29.8% -> 29.6% (-0.2), p 0.59 | no (down in 2022-24) |

**Verdict:** no filter raises the win rate in a way that beats random skipping in both periods. All four failed the
gates, so by the CLAUDE.md safety rules they stay notes only: no arm is changed.
