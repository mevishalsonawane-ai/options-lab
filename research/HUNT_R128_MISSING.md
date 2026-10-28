# R128: what were we missing? Deep research, the fixes, and the tests (option buying only)

28 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss: *"We are missing something. Deep
research using internet, pdf study we had, and find out what are we missing and add it or fix it and test it"*, then
*"only options buying"*.

## The answer, first

1. **What we were missing.** Three independent reviews agreed:
   - Internet research: the published evidence.
   - The repo's book and PDF studies.
   - A skeptical audit of R123-R127.

   The things they found:
   - **The option's price.** We forecast the move but never compared it with the move the option already prices.
   - **Exits that let a bought option win big.** Every rule used fixed, symmetric targets and stops.
   - **Rules that could fire.** Absolute probability cuts meant some rules almost never traded.

   No bug turned a profit into a loss, and the cost model is if anything slightly optimistic.
2. **Fix 1, timing straddles by forecast-vs-price, does not help.**
   - Options here price about **twice** the intraday move that actually comes: the realised 60-minute range was about
     0.5 of the implied range.
   - So buying a straddle loses even when it is cheapest by our ranking. Walk-forward, the cheapest tenth lost Rs 142
     a trade (426 trades), about the same as the dearest.
   - The money goes to time decay first and to costs second (see the table below).
3. **Fix 2, exits that keep the big wins, roughly doubles to quadruples R123's morning put lead. It is real but
   fragile, so it is a forward-test candidate, not a strategy yet.**
   - The same S4/S5 entries are held to the 15:20 close, keeping the -Rs 2,000 stop:

     | entries | design walk-forward (2023 - Mar 2026) | holdout (Apr - Oct 2026) |
     |---|---|---|
     | S5 | 555 trades, +Rs 172 → **+Rs 373 a trade** (q 0.031) | 41 trades, +Rs 579 → **+Rs 1,797** |
     | S4 | 1,085 trades, +Rs 78 → +Rs 317 | 52 trades, +Rs 345 → +Rs 1,242 |

   - Same entries with a random side lose (p 0.001-0.02), so the network's side choice matters.
   - But:
     - The **best 5 days give 115-134% of the profit**: without them it loses.
     - **476 of S5's 555 design trades are in 2024**, and early 2026 lost.
     - It breaches the account stop on 1 lot: the worst month is -Rs 55k (S5) / -Rs 95k (S4).
     - Only 27% of trades win; winners average about Rs 6,700 against losers of about Rs 1,950.
   - **It is a "catch the rare big fall" trade:** a morning ATM put, held all day, with a stop.
4. **Fix 3, late-day momentum** (published abroad), loses in India with ITM options: -Rs 104 / -Rs 162 a trade.
5. **The R127 random-twin slip is fixed.** CPR's p-values changed (V1 0.43 → 0.25); every conclusion stands.

**Nothing passes every gate.** The holdout needs ≥ 100 trades and the account stop, so nothing goes on paper.

**One proposal:** a *shadow* forward test, logged only, behind a switch that is off by default. It would run on
sessions after 19 Oct 2026: S5's morning put pick with the trailing exit, the calmer of the two exits.
- Its worst month in the walk-forward was -Rs 18.6k, against -Rs 55k for hold-to-close.
- It needs about 100 logged trades, roughly a year at its rate, before any paper decision.
- It was not built here.

## S5 Trail on paper (28 Oct, Boss's exception)

Boss: *"Add s5 trail to arms directly, if it does not perform I will turn it off"*. S5 with the trailing exit now runs as a
PAPER arm in the app (`S5Trail.kt`, `S5Arm.kt`). It failed the gates, so it is labelled "Failed research gates (R128): Boss's
exception" everywhere, starts on, is undone in one tap, and has no live path (CLAUDE.md, 28 Oct).

- **The network on the phone.** R123's N1 holdout fit is exported by `export_s5.py`. The export refuses to write unless its
  forward pass reproduces the saved holdout predictions (it did, to 1.4e-7). The engine's tests check the Kotlin port against 8
  golden rows (1e-4) and its 42 inputs and 120-minute window against R123's own Python (`android/tools/s5_parity_fixture.py`).
- **IV.** The network learned Dhan's IV. The app computes its own (Black-76 on the index, rate 0). Dhan's sits about 1.5 points
  lower for calls and 1.3 higher for puts (about 30,000 samples, 2024-25 design period, no outcome read), and that moves the
  skew input. The app adds that measured offset.
- **The app's version, backtested** (`s5_app_check.py`; same networks and exits, only the IV changed):

  | IV | walk-forward (2023 - Mar 2026) | holdout (Apr - Oct 2026) |
  |---|---|---|
  | Dhan's (as tested) | 556 trades, 58% won, +Rs 209 | 40 trades, 85% won, +Rs 625 |
  | the app's + offset (what runs) | 540 trades, 58% won, +Rs 161; 2025 -Rs 14.8k (38 trades) | 33 trades, 79% won, +Rs 509 |
  | the app's, no offset | 780 trades, 58% won, +Rs 135 | 65 trades, 71% won, +Rs 164 |

  Expect the paper record to be weaker than R128's numbers.
- **Other differences from R128:**
  - Out at 15:15 (the paper book's MIS square-off), not 15:20: 13 of 596 R128 trades ended at the close.
  - One open trade per index: a second pick on an index waits until its trade closes. R123's portfolio freed the index at the
    original cell's exit. `s5_app_check.py` already uses the app's rule, so its 540 / 33 trades include this.
  - The profit lock reads the price at the first look in each minute: the minute lane's or the 15-second check's, close to the
    minute's close.
  - The chain comes from Upstox's intraday candles (each minute's ATM ±6 strikes over the session so far) instead of Dhan's. OI
    and volume are used only as ratios, so units do not matter.
  - The stop trigger carries research's buffer (2 ticks and 10 bps), so a filled stop nets about the cell's −Y.

## Where an option buyer's money goes (T4)

Every ATM straddle bought at 10:00 and held 60 minutes, walk-forward plus holdout: 4,194 straddles over five indices.

| per straddle, 1 lot | average |
|---|---|
| premium paid | Rs 22,707 |
| result before charges and slippage | **-Rs 87** (time decay beats the move) |
| charges | -Rs 138 |
| slippage | -Rs 35 |
| **net** | **-Rs 260** |
| index move over the hour | 73 points |
| straddle cost in points | 499 points |

Two things sink the buyer:
- **Decay is priced before any cost.** The option prices more movement than comes within the hour, so even a
  zero-cost buyer loses Rs 87.
- **Costs then add twice that.** About Rs 173 a round trip of two legs.

R118 found the same at expiry: the straddle out-prices the move 65% of the time.

## T1: straddles timed by forecast vs price

- **Q** = our network's forecast 60-minute range ÷ the range the ATM option's implied volatility implies (×1.6, the
  random-walk range factor).
- **Rule:** buy when Q is in the top 10% of the index's last 60 sessions.
- **Results:**

  | exit | walk-forward | holdout |
  |---|---|---|
  | T1a: after 60 minutes | 4,260 trades, -Rs 165 | 761 trades, -Rs 200 |
  | T1b: to the close | 2,276 trades, -Rs 310 | 498 trades, -Rs 445 |
  | T1c: stop -35%, trail after +20% | 2,685 trades, -Rs 283 | 540 trades, -Rs 454 |

- **By tenth of Q** (walk-forward, 426 trades each): from the cheapest tenth to the dearest, -Rs 115, -158, -211,
  -206, -214, -196, -128, -141, -141, -142. There is no slope.
- In the holdout only the top tenth was positive (+Rs 75, 76 trades), which is too few to mean anything.
- The realised range was 0.44-0.54 of the straddle cost in every tenth.
- NIFTY+SENSEX (still weekly) lost Rs 182 a trade; the monthly-expiry indices after Nov 2024 lost Rs 153.
- **Why:** the forecast ranks the move well, but the option's price already contains that information. The literature
  says the same: intraday option returns are roughly fair, and option sellers earn their premium overnight (Bhat,
  Pandey & Rao 2024, on NIFTY; Muravyev & Ni 2020). A buyer has no cheap volatility to find intraday.

## T3: late-day momentum (Gao et al. 2018; Baltussen et al. 2021)

- **Rule:** if the move so far today (to 15:00) is in the top 30% of the last 250 sessions, buy the 2-strike ITM
  option its way at 15:00 and sell at 15:19.
  - Walk-forward: 893 trades, -Rs 104.
  - Holdout: 161 trades, -Rs 338.
- **The opening half-hour version:** 862 trades, -Rs 162; holdout 219 trades, -Rs 314.
- The last 20 minutes' move is too small for an option's costs, as the research report predicted.

## How it was done

- **Pre-registration:** `research/hunt/r128_missing/PREREG.md`, commit `605b8ea`, before any number.
- **Code and tests:** `research/hunt/r128_missing/r128.py`, `test_r128.py` (4 tests); R127's fix is in
  `research/hunt/r127_cpr/r127cpr.py`.
- **Results:** `results/results.json`. Trade lists are on Drive (`derived/r128/`).
- **Data:**
  - R123's out-of-sample network forecasts (walk-forward 2023 - 15 Mar 2026 and the Apr - Oct 2026 holdout);
  - ATM IV from the lake's option chain;
  - option bars cleaned (lakeclean steps 1-3; minutes with no trade never priced);
  - 1 lot at today's lots, dated charges, liquidity-aware slippage, and 2x slippage;
  - every trade buys and is closed the same day.
- **Twins:**
  - T1: random rows of the same index-day (100 a trade, resampled to 1,000);
  - T2 and T3: the same entries with a random side;
  - BH over the 9 rules.
- **Gates (R123's):**
  - walk-forward: ≥ 300 trades, positive at 1x and 2x, 3 of 4 folds, q ≤ 0.10;
  - holdout: ≥ 100 trades, positive, q ≤ 0.10, the account stop.

## The three reviews' other findings (kept for the next studies)

- **R124/R125's direction rules could not fire.** The network's direction share stays within 0.39-0.54. Rank-based
  selection should replace absolute cuts.
- **R124's move labels did not match its trades.** Even perfect foresight of the label paid only about +Rs 600, so a
  direction model needs about 56% accuracy given a move.
- **Weekly vs monthly expiries since Nov 2024.** BANKNIFTY, FINNIFTY and MIDCPNIFTY now trade monthlies, with about
  twice the premium. Report them separately.
- **Data still missing:**
  - real tick order flow (the phone's recordings, from 12 Oct);
  - futures volume and OI history (the lake has them only from Jul 2026);
  - a second expiry series for event trades;
  - an event calendar beyond budget days.
- **Not worth more time:**
  - order-flow imbalance at retail speed (its edge lasts seconds);
  - PCR as a contrarian signal;
  - GIFT Nifty for intraday direction (the gap is priced at the open).

## Honest notes

1. **The holdout (Apr - Oct 2026) had been read by R123-R127.** T2's entries were chosen in R123 before that read,
   and only the exits are new. Still, a fresh forward test is the only honest next step.
2. **T2's holdout profit comes from a few strong down days for puts.** Five days give 115-127% of it. A calm half-year
   could easily show losses.
3. **T4 compares a 60-minute move with the whole straddle premium.** The premium is not lost in an hour, so "move
   beats cost 1.3%" is not a breakeven. The P&L lines in the table are exact.
