# R26: Boss's "OI wall blast" hero-zero framework, tested on 770 expiries

Written 14 Oct 2026, for Boss's request to test a mechanical hero-zero framework found online: a call-wall break held for
15+ minutes, with the wall's call OI unwinding and a volume spike, buying the ATM+1 option at Rs 15-30, "it goes to 4×
or to zero".

- **Pre-registration:** `research/hunt/r26/PREREG.md`, commit `f69d9d0`, pushed before any trigger or outcome was
  computed.
- **Code, tests and tables:** `research/hunt/r26/` (`results/report.md` has every table; `results/results.json`,
  `results/describe.json`). The engine is R25's: the data loader, fills, charges and out-of-range rule are unchanged.
- **Experiment entries:** `research/experiments/entries/r26-*.yaml` (10 rules, all rejected, plus the descriptive check).
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **Does it make money after costs? No.**
  - The framework as written (P) traded on **15 of 770 expiries** (about once in 50 expiries, about 3 times a year across
    all four indices). It lost **−Rs 3,851** after charges and spread: 3 winners, 12 losers, −Rs 154 per Rs 1,000
    risked.
  - It made money in only 2 of 6 years (2024 and a +Rs 28 one-trade 2026). It lost in both of R25's blocks: 2021-22
    (−Rs 2,652, 3 trades) and 2023-26 (−Rs 1,199, 12 trades).
  - None of the 10 versions tested passes (gates in PREREG). The best p-value is 0.73; the smallest BH q in the 28-test
    family (R21 + R25 + R26) is 0.168, and every R26 q is 1.00.
- **The 4× target is the weak part, not the entry.** On the same 15 entries:
  - holding to 15:05 makes +Rs 8,228, holding to 15:20 +Rs 14,499, and Boss's own style (half at 2×, step lock) +Rs 10,383;
  - but all three are **one day**: 12 Sep 2024 (NIFTY 25250 CE). Without that day each is negative (−Rs 9,730,
    −Rs 1,115 and −Rs 1,138).
  - 15 trades is far too few to trust either way. Gate 1 needs 30.
- **It triggers rarely because the parts seldom line up.** Of 1,540 expiry-sides (each expiry, calls and puts):
  - spot closed beyond the 13:00 wall in the window on 338;
  - it held there for 16 minutes on 169;
  - the wall's OI also fell by 15% / 25% on 128;
  - the volume spike was also there on only 38;
  - with the price and summed-OI conditions, 35 (A3, no price filter);
  - and with the option at Rs 15-30, 15 (P).
- **The OI part adds nothing.**
  - Once spot is past the wall, the wall's OI usually falls anyway (128 of 169 held breaks). Dropping the OI conditions
    (B1) adds only 3 trades, all losers (−Rs 4,081).
  - At 13:45, before the move, the wall's OI state does not change the chance of a 4× blast: 10.0% when it was already
    down ≥ θ from its noon peak (180 cases), against 9.6% overall (1,486).
  - During a blast the wall's OI falls (≥ θ in 54 of 133 blasts with OI data). But it falls just as often on days with no
    blast (516 of 1,265). That is the same finding as R25: OI moves *with* the move; it does not announce it.
- **Best and worst days.**
  - Best: 12 Sep 2024, NIFTY. The trigger fired at 14:20; it bought 5 lots of the 25250 CE at Rs 15.86 (Rs 2,007) and
    sold at 4× for **+Rs 5,879**. Held to 15:05 the same ticket made +Rs 17,958. This is the same day that made the app's
    Hero rule look good in R25.
  - Worst: 20 Jul 2023, BANKNIFTY. The 46300 CE was bought at 14:23 and never moved: **−Rs 1,859**.
  - No BANKNIFTY trade made money (6 trades, −Rs 6,279; one ended at −Rs 0). FINNIFTY lost both of its trades. SENSEX never triggered with the
    price filter.
- **13 Oct 2026 is not in the lake** (it ends 12 Oct). Without that day's OI chain, nobody can say whether the call wall broke
  and unwound. From R25's notes, the ATM+1 CE that afternoon was in single digits (the 22700 CE was
  Rs 4.15 at 14:30). So the **Rs 15-30 filter would most likely have blocked it**, and the no-filter version (A3) would
  have needed the volume spike, which is the rarest part.
- **What to do: nothing in the app.**
  - No live use, and no paper change: nothing passed.
  - If Boss wants to keep watching the idea, log it in shadow only (plan below). That costs nothing and builds a
    forward sample.
  - The one thing worth remembering: across R21, R25 and R26, every hero rule's profit sits on a handful of days. On
    expiry afternoons, **holding a winner to the square-off beat the 4× target** on these entries. That is the same lesson
    as R25's "the median 10× option gave back 93%": a fixed exit almost always picks the wrong moment. Neither is an edge
    on 15 trades.

## Data

- **The Dhan lake on Drive**, read with R25's loader: `options_rolling_1m/opt_{NIFTY,BANKNIFTY,SENSEX,FINNIFTY}_WEEK_*`
  (the nearest expiry, ATM±10 strikes, OHLC, volume, OI, IV each minute) and the index and VIX minutes.
- **770 expiries** found from the data (R25's time-value rule):

  | index | expiries | span |
  |---|---|---|
  | NIFTY | 271 | 5 Aug 2021 – 6 Oct 2026 |
  | BANKNIFTY | 172 | weeklies, 5 Aug 2021 – 13 Nov 2024 |
  | SENSEX | 175 | 19 May 2023 – 8 Oct 2026 |
  | FINNIFTY | 152 | weeklies, 26 Aug 2021 – 19 Nov 2024 (new in R26) |

  - The detector misses a few 2021-22 FINNIFTY weeks where the lake is thin.
  - R25's blocks: 2021-22 has 201 expiries, 2023-26 has 569.
- **The walls.** At 13:00 the highest-OI strike was the outermost stored strike on **25 of 1,540** expiry-sides (1.6%).
  Only there might the true wall lie outside ATM±10.
  - **92 expiry-sides had no OI at all at 13:00**: SENSEX 2023 (33 expiries), SENSEX 2024 (12) and one BANKNIFTY day.
    These cannot trigger.
- **Volume.** The index files carry no volume, and the futures minutes in the lake cover only recent months. So the
  volume spike uses the option chain's volume (all stored strikes, both sides).
- **Costs:** R25's. Rs 20 an order, STT 0.15% of the sell, exchange, SEBI, stamp and GST. Fills at the next minute's open
  + 5 bps + a 1-4 tick half-spread by volume, with entries capped at 1.2× the signal price + 0.10. The stress test is 2×
  the spread.

## The rule as tested (PREREG.md has every detail)

- **P, the primary reading.**
  - Entries 13:45-14:30.
  - The Call Wall (Put Wall) is the strike with the most call (put) OI at 13:00.
  - **UP:**
    - spot's 1-minute closes are above the wall for 16 minutes in a row, after being at or below it since 13:00;
    - the wall's call OI is down ≥ 15% (NIFTY, SENSEX) or ≥ 25% (BANKNIFTY, FINNIFTY) from its peak since 12:00;
    - the chain's last-15-minute volume is ≥ 2× its session average per 15 minutes;
    - spot is up over 15 minutes, and the summed call OI is down over 15 minutes.
  - **DOWN** mirrors it with the Put Wall.
  - It buys the ATM+1 CE (ATM-1 PE) if it is priced Rs 15-30.
  - Size: Rs 2,000, in whole lots.
  - Exit: target 4×, else the 15:05 square-off.
  - At most one trade per index per expiry.
- **Alternatives**, each changing one thing from P:

  | id | change |
  |---|---|
  | A1 | rolling wall: a fresh break of the wall as read 16 minutes earlier |
  | A2 | OI reference: the 12:00 value instead of the peak |
  | A3 | no Rs 15-30 filter |
  | A4 | exit: hold to 15:05 |
  | A5 | exit: hold to 15:20 |
  | A6 | exit: half at 2×, step lock (2× → 1×, 3× → 2×, …) |
  | A7 | BANKNIFTY ATM+2 |
  | A8 | SENSEX: the wall is the next 1,000-point level |
  | B1 | P without the OI conditions (baseline b) |

## Results (all 770 expiries; `results/report.md` has everything)

| rule | trades (UP / DOWN) | hit % | reached ≥ 2× / ≥ 4× % | avg win / loss | net Rs | per Rs 1,000 risked | worst day | max DD | losing streak | net w/o best day | 2× spread | twin mean | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **P** | 15 (7 / 8) | 20.0 | 46.7 / 6.7 | +3,245 / −1,132 | **−3,851** | −154 | −1,859 | −7,856 | 7 | −9,730 | −3,944 | +17,747 | 1.00 | 1.00 |
| A1 rolling wall | 6 (2 / 4) | 16.7 | 50.0 / 0 | +1,298 / −1,012 | −3,763 | −413 | −1,577 | −3,763 | 3 | −5,060 | −3,805 | +5,052 | 0.996 | 1.00 |
| A2 OI from 12:00 | 13 (6 / 7) | 23.1 | 53.8 / 7.7 | +3,245 / −1,055 | −814 | −38 | −1,859 | −6,368 | 6 | −6,693 | −887 | +15,617 | 0.998 | 1.00 |
| A3 no price filter | 35 (13 / 22) | 25.7 | 37.1 / 8.6 | +2,356 / −1,213 | −10,338 | −169 | −2,373 | −14,596 | 8 | −16,170 | −10,531 | +32,712 | 1.00 | 1.00 |
| A4 hold 15:05 | 15 | 20.0 | 46.7 / 6.7 | +7,271 / −1,132 | +8,228 | +329 | −1,859 | −7,856 | 7 | −9,730 | +8,104 | +45,198 | 1.00 | 1.00 |
| A5 hold 15:20 | 15 | 26.7 | 46.7 / 20.0 | +7,455 / −1,393 | +14,499 | +579 | −1,929 | −6,850 | 6 | −1,115 | +14,398 | +53,563 | 1.00 | 1.00 |
| A6 2× + step lock | 15 | 53.3 | 46.7 / 6.7 | +2,432 / −1,296 | +10,383 | +415 | −1,859 | −5,370 | 3 | −1,138 | +10,322 | +16,013 | 0.798 | 1.00 |
| A7 BANKNIFTY ATM+2 | 7 (2 / 5) | 14.3 | 42.9 / 0 | +1,090 / −1,370 | −7,130 | −605 | −1,899 | −7,130 | 5 | −8,220 | −7,182 | −3,141 | 0.732 | 1.00 |
| A8 SENSEX 1,000 level | 0 | — | — | — | 0 | — | — | — | — | — | — | — | 1.00 | 1.00 |
| B1 no OI conditions | 18 (7 / 11) | 16.7 | 44.4 / 5.6 | +3,245 / −1,154 | −7,572 | −257 | −1,620 | −10,333 | 9 | −13,452 | −7,688 | +15,160 | 1.00 | 1.00 |

- **Per expiry**, P is −Rs 5.0, and only 0.4% of expiries are green (most have no trade).
- **The best day's share:** for A4, A5 and A6 the best day is 218%, 108% and 111% of the whole net.
- **Real risk.** P never needed a lot above Rs 2,000 (largest ticket Rs 2,007, median Rs 1,713). A3 bought 1 lot above
  budget 3 times, the largest Rs 3,026 (NIFTY 28 Nov 2024 PE at Rs 40, lot 75).
- **Out-of-range prices** were never needed (0 trades). The out-of-range-at-0 nets equal the base.
- **By index (P):**

  | index | trades | net Rs |
  |---|---|---|
  | NIFTY | 7 | +5,154 (all of it 12 Sep 2024) |
  | BANKNIFTY | 6 | −6,279 (0 winners) |
  | FINNIFTY | 2 | −2,726 |
  | SENSEX | 0 | — |

- **By direction (P):** UP 7 trades, +Rs 4,046; DOWN 8 trades, −Rs 7,897.
- **R25's blocks and years (P):**

  | 2021-22 | 2023-26 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
  |---|---|---|---|---|---|---|---|
  | 3 / −2,652 | 12 / −1,199 | 2 / −1,488 | 1 / −1,164 | 4 / −5,204 | 4 / +7,008 | 3 / −3,032 | 1 / +28 |

- **The gates.** All 10 fail.
  - Gate 1 (≥ 30 trades): only A3 has 30 or more, and A3 loses.
  - Gate 3 (positive in both blocks): only A6 meets it.
  - Gate 4 (BH): every rule fails.
  - Gate 6 (positive without the best day): every rule fails.
  - Gate 7 (drawdown under Rs 40k): every rule meets it. At Rs 2,000 a trade, 15 trades can't lose Rs 40k.
- **Random-time twins** (same expiry, same direction, same strike rule and filter, a random minute 13:45-14:30, 1,000
  sets) did **better** than the trigger for every rule that traded (A8 never traded).
  - The twins keep the trigger's direction, and on these days the move often started before the break was confirmed. So
    entering earlier at a random minute caught more of it.
  - The 16-minute confirmation costs more than it protects. It is late by design.
- **Baseline (b), the OI part:**
  - P takes 15 trades; B1 (no OI conditions) takes 18. The 3 extra trades all lost (−Rs 4,081).
  - That is 3 trades, which is no evidence either way.
  - On the 15 expiries both traded, B1 nets −Rs 3,492 against P's −Rs 3,851 (a few entries differ by minutes). Of the
    169 held breaks, 128 already had the wall's OI down ≥ θ.
- **Baseline (c): R25's rules**, quoted from R25 on its 618 expiries (NIFTY, BANKNIFTY, SENSEX; not re-run):

  | rule | trades | net Rs | note |
  |---|---|---|---|
  | app's Hero rule (H0) | 48 | +51,755 | +76,379 in design, −24,624 in the holdout; 3 days carry all of it |
  | R25 T1 | 421 | −2,191 | |

  Neither passed R25's gates. P loses less in rupees than both only because it trades so rarely.
- **BH family: 28** (R21's 9 + R25's 9 + R26's 10).
  - The smallest q is 0.168, for R21-V8, a blind control.
  - Every R26 q is 1.00.

## Descriptive check of the claim itself

- **Do walls break in the window?**
  - Spot closed beyond the 13:00 wall at least once in 13:45-14:30 on 338 of 1,540 expiry-sides (22%).
  - It held for 16 minutes on 169 (11%).
  - The full trigger without the price filter fired on 35 (2.3%).

  | index | expiry-sides | beyond the wall | held 16 min | + OI down | + volume | full trigger |
  |---|---|---|---|---|---|---|
  | NIFTY | 542 | 138 | 76 | 61 | 12 | 12 |
  | BANKNIFTY | 344 | 79 | 41 | 33 | 12 | 11 |
  | SENSEX | 350 | 58 | 24 | 21 | 8 | 7 |
  | FINNIFTY | 304 | 63 | 28 | 13 | 6 | 5 |

- **Wall OI around the big moves** (the ATM±1 option at 13:45 reaching a confirmed 4× before 15:05 counts as a
  "blast"; 142 of 1,486 expiry-sides with a price, 9.6%):

  | | n | wall OI down ≥ θ | down less | rising / flat | no OI |
  |---|---|---|---|---|---|
  | blasts (13:45 → the first 4× minute) | 142 | 54 | 47 | 32 | 9 |
  | no blast (13:45 → 15:00) | 1,344 | 516 | 273 | 476 | 79 |

  - **The bought strike's own OI** fell ≥ θ during 78 of 142 blasts, against 313 of 1,344 non-blasts. That is a
    near-the-money strike going in the money: its writers cover *because* it moved. It happens during the move, too late
    to trade on.
  - **Point-in-time at 13:45**, before the move, the blast rate by the wall's OI state:

    | wall OI at 13:45 | expiry-sides | blast rate |
    |---|---|---|
    | down ≥ θ from its noon peak | 180 | 10.0% |
    | down less | 855 | 10.2% |
    | at its peak | 363 | 7.7% |
    | no OI | 88 | 10.2% |

    No useful lift.
  - **Of the 142 blasts**, 35 came after a held wall break in the window and 14 with the full trigger. The trigger misses
    9 in 10 blasts.
- **Link to R25:** R25 found jackpots come with OI rising on the strike before the move. Here, OI falling at the wall is
  common, and it is a description of the move while it happens, not a warning before it. "Short covering" is real as a
  story of what happened; it is not a signal that comes early enough to buy cheap.

## Boss's four extra filters (14 Oct, after the run): a quick look, NOT a test

Boss sent four more confirmation filters (intraday PCR, OI unwinding at three strikes, heavyweight stocks, and the
premium breaking its own swing high on volume). They arrived after the results above were seen, so this section is only
a description of A3's 35 triggers (the no-price-filter version, to have more than 15). It is not a pre-registered test
and counts in no gate.

| filter (read at the trigger minute) | triggers kept | net Rs kept | net Rs of the ones it removes |
|---|---|---|---|
| PCR (Σ put OI / Σ call OI over ATM±10) up over 15 min and > 1.2 (CE); down and < 0.7 (PE) | 10 | −11,746 | +1,407 |
| OI down over 15 min at ATM, ATM+1 and ATM+2 (CE side; mirrored for PE) | 5 | −7,308 | −3,031 |
| premium's close above its own 15-min high, on above-average volume | 5 | −762 | −9,577 |
| all three together | **1** (NIFTY 15 Sep 2026) | +28 | |

- The heavyweight-stock filter was not checked: the lake's `equity_1m` files are not pulled into this study yet.
- What this shows is that the extra filters cut a rare trigger down to about 1 trade in 5 years. No rule can be judged
  on that, either way. The PCR filter kept the worst trades. The premium filter kept the least bad, but 5 trades is
  noise.
- A proper test needs its own pre-registration (R27), with the heavyweight data and one fixed reading of each filter.

## Forward test plan (shadow only; a description, no app change)

The app already records what this trigger needs, for NIFTY and BANKNIFTY:
- `LiveRecord` writes each index's nearest-expiry chain at ATM±10 every minute, with OI;
- `TickZone` writes ATM±3 options every second, with OI and volume.

A shadow logger (log only, never an order, behind a switch that is off by default) would work like this:

1. **On an expiry day, at the 13:00 minute row,** read the Call Wall and Put Wall from the recorded chain (the highest
   OI among ATM±10). Note whether the wall is the outermost recorded strike.
2. **Each minute from 13:45 to 14:30,** from the recorded minute rows only:
   - the last 16 index closes beyond the wall;
   - the wall's OI against its peak since 12:00 (15% NIFTY, 25% BANKNIFTY);
   - the chain's last-15-minute volume against its session average. This needs the chain's cumulative volume, which
     `TickZone` has for ATM±3; summing the ATM±3 volume is the closest live match;
   - the 15-minute index move and the summed call (put) OI change.
3. **When it fires,** write one shadow line with the time, the direction, the wall, the OI fall %, the volume ratio,
   the ATM+1 CE / ATM-1 PE and its LTP, and whether it is inside Rs 15-30.
4. **After the close,** a research script replays each line against the recorded prices, with all four exits (4×,
   15:05, 15:20, half at 2× + step lock) and R25's costs. It adds the result to the R26 table.
5. **Decision rule, pre-registered now:** look again after 30 shadow triggers. At 1-2 a year on NIFTY (7 in 5 years) and a
   monthly BANKNIFTY expiry, that is many years away. So this is for curiosity, not for a decision soon.

Notes:
- It runs off the main thread, on data already in memory or on disk, never on the order or tick path.
- SENSEX and FINNIFTY are not recorded by the phone. BANKNIFTY now has only a monthly expiry.

## Honest notes

- **Small sample.** 15 trades for P (35 for A3) over 5 years and four indices. Any net, positive or negative, on 15
  trades is mostly luck. That is why gate 1 exists. The framework's own conditions make it rare.
- **Vague points were fixed before the run.** These include: the wall read at 13:00; "more than 15 minutes" as 16
  closes; the volume as the option chain's; the OI reference as the noon peak; SENSEX's θ = 15%; and FINNIFTY's 25%. One
  alternative reading was tested per point. No reading was chosen after seeing results. The exits were all reported.
- **The data has no bid / ask.** The spread is R25's model. Doubling it moves P by under Rs 100.
- **SENSEX OI is missing on 45 expiries of 2023-24**, so SENSEX had fewer chances to trigger. With the price filter it
  never did. Without it (A3), 7 trades lost −Rs 4,023.
- **The twins keep the trigger's direction**, which is information from the trigger itself. So they test only the timing
  of the entry, as in R25.
- **The R25 blocks:** R26's rules were never run on 2021-22 before. R25 used that block as its own locked holdout. It is
  reported here as a split, not as a fresh holdout.
- **What was not tested:** option selling around walls; monthly BANKNIFTY / FINNIFTY expiries after Nov 2024; MIDCPNIFTY;
  13 Oct 2026 itself (not in the lake).

## Checks run

- `python -m pytest research/hunt/r26/test_r26.py -q` passes 28 tests: 27 on synthetic days and 1 on real expiries
  (NIFTY, BANKNIFTY, SENSEX and FINNIFTY days that triggered). They cover:
  - the look-ahead guard (future blanked and future absurd) for every rule's conditions and signal;
  - the wall (highest OI, ties, the outermost flag), OI forward-fill from the past only, and the volume spike;
  - the 16-close hold (15 closes do not fire), the 13:45-14:30 window, and "already above at 13:00 is not a move above";
  - the rolling wall, the no-OI baseline, the price filter, the DOWN mirror, θ by index, ATM+2 and the SENSEX
    1,000-point level;
  - one trade per expiry, and market-closed, empty and stale days;
  - sizing (whole lots; 1 lot over budget, flagged), the 4× target (a freak print fills nothing), 15:05 / 15:20, half at
    2× + step lock (and 1 lot), and worthless at the exit;
  - the twins, the metrics on no trades, and the FINNIFTY settings.
- R25's tests still pass (`research/hunt/r25/test_r25.py`).
- The brain checks: `python -m pytest research/brain/tests -q` and `python -m research.brain.features catalog --check`.
- `python research/experiments/validate.py`, and `python3 research/knowledge/build_pack.py` then `--check`.
