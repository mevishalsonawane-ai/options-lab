# HUNT R30: every parameter at once for buying CE / PE, 14 Oct 2026

Boss (14 Oct 2026): "use all other combination too whatever you think of, and also combination of all other parameters
too, which play a good role in trading", and "always check the regular index, use all indicators and data on it, and on
that basis buy call / put".

- Code: `research/hunt/r30/`.
- Pre-registration: `research/hunt/r30/PREREG.md`, commit a570202, written before any outcome. Amendment 1 (c97e8d4)
  was also made before any outcome.
- Full table: `research/hunt/r30/results/table.md`. Details: `results/walkforward.json`.
- Big tables on Drive: `IraAlgo-data-lake/derived/r30`.
- Research only: nothing in the app changed.

## Verdict for Boss

**Not yet. 0 of 30 strategy × index tests passed the full gate, so nothing goes to paper automatically. One came close
and is worth watching live.**

- **What was tested.** At every 5-minute bar close from 09:30 to 14:45, the index plus everything known about it at
  that moment was used to decide CE, PE or no trade:
  - 164 Data Brain indicators, including the 77 EMA-ribbon features;
  - option-chain data: ATM IV and its change, IV skew, PCR and its change, CE / PE OI build-up, straddle price,
    option volume, max-OI strikes;
  - VIX, expiry and calendar;
  - the other indices.

  That is 249,414 decision moments on five indices. Six ways of combining it all:
  - M1: a model that predicts the profit of a CE and of a PE;
  - M2: the same for a debit spread;
  - M3: a move-size model plus a direction model;
  - M4: a model that predicts the chance a trade ends in profit;
  - C1: the best 2-parameter combinations;
  - C2: EMA × every other parameter, in pairs and triples.

  Everything was learned on earlier years and tested on later ones it had never seen: Jul–Dec 2024, 2025, and
  Jan–Oct 2026. Every trade paid real charges and spread, with your +Rs 1,000-then-keep-Rs 500 lock as the exit.
- **The closest: M4 on NIFTY.** The model scores, for a CE and a PE, the chance the trade ends in profit, and buys
  only the top 5%.
  - Over the three unseen periods: 77 trades, 61% winners, average win Rs 1,118 vs average loss Rs 1,040.
  - +Rs 21,337 in total. It was positive in **every** test period: +Rs 48 a trade (14 trades), +Rs 536 (28) and
    +Rs 162 (35). At double the spread it was still +Rs 18,110. Worst day Rs −3,551, max drawdown Rs −7,920.
  - It beat random entries on the same days (p 0.025). It missed only the multiple-testing bar: q 0.109, where the gate
    needs 0.10. With 30 tests, one result this good can still be luck.
  - It trades rarely: about one trade every 7 NIFTY sessions, about +Rs 38 a day per lot on average.
- **One more that was positive in every test period, but weaker: M1 on SENSEX**, +Rs 85 / +43 / +43 a trade (149
  trades, p 0.19). Its first period (Jul–Dec 2024) was traded by a model that had no SENSEX training rows (see the
  caveats). M4 on SENSEX was positive overall (+Rs 96 a trade, 83 trades, p 0.08), but it lost in 2025.
- **Everything else lost money out of sample.** C1 and C2 lost Rs 77–111 a trade. C2 is the "EMA × everything"
  combinations: the cells kept looked excellent on the training years (Rs 290–548 per row, ATM, lock) and lost on
  the next year. That is
  what over-fitting looks like.
- **What the models lean on (permutation importance, varies by period and model):**
  - option-chain inputs rank high most often: IV skew is in the top 2 for M1 and M4 in every period, and the ATM
    straddle price is the top input of M4 in 2025 and 2026, with PCR next;
  - but not always: the 2026 M1 model's top input is the weekday, M3 leans on VIX, and EMA-ribbon slopes rank 3rd to
    4th in two models.

  So the option market's own pricing looks more useful than EMAs, but this is a lead, not a finding.
- **What I recommend next:**
  - Run M4-NIFTY (the frozen model trained to 2025) **forward on paper, logged only, for 40 sessions** from 15 Oct, and
    pre-register that test now. If it stays positive and beats random entries over those unseen days, it earns a paper
    arm behind a switch. Never live from this study.
  - Build the next study around IV skew + straddle + PCR, the inputs the models found most useful.
  - Neither is done yet; both wait on your go-ahead.

## Results (out of sample, all three test periods together; 1 lot; Rs)

| strategy | NIFTY | BANKNIFTY | FINNIFTY | MIDCPNIFTY | SENSEX | all trades |
|---|---|---|---|---|---|---|
| M1 profit model (ATM, lock) | **+64** (161) | −108 (275) | −146 (375) | −69 (331) | **+50** (149) | −69 (1,291) |
| M2 profit model (debit spread) | −39 (128) | −227 (86) | −333 (191) | −265 (137) | −75 (148) | −196 (690) |
| M3 size + direction | −226 (255) | −74 (530) | −205 (498) | −78 (765) | −138 (266) | −127 (2,314) |
| M4 win-chance model | **+277** (77) | −1 (379) | −150 (135) | −89 (609) | **+96** (83) | −35 (1,283) |
| C1 best pairs | −106 (1,198) | −55 (1,173) | −76 (1,044) | −133 (1,132) | −20 (1,207) | −77 (5,754) |
| C2 EMA × everything | −82 (740) | −92 (799) | −140 (793) | −164 (842) | −69 (765) | −111 (3,939) |

Net per trade (trades in brackets). The test periods have 566 sessions for every index. For each strategy and index, `results/table.md` has:
- trades a day, win rate, average win / loss, P&L a day, % green days, worst day, max drawdown, big winners cut short;
- the result per fold, random twins, p, BH q and the 2× spread net.

Gate (PREREG):
- out-of-sample net > 0, and > 0 in every test period;
- p < 0.05 against random entries;
- BH q ≤ 0.10 over the 30 tests;
- still > 0 at 2× spread.

Results closest to it:

| strategy × index | net > 0 | every period > 0 | p < 0.05 | q ≤ 0.10 | 2× spread > 0 |
|---|---|---|---|---|---|
| M4 NIFTY | yes (+21,337) | yes (+48 / +536 / +162) | yes (0.025) | **no (0.109)** | yes |
| M1 SENSEX | yes (+7,499) | yes | no (0.193) | no | yes |
| M1 NIFTY | yes (+10,325) | no (−42, −0, +139) | no (0.065) | no | yes |
| M4 SENSEX | yes (+7,972) | no (2025 −360) | no (0.080) | no | yes |

## Combining with the other studies (BH)

- R30's own family is 30 tests. With R29's 195 and F1's tests (not yet published), M4-NIFTY's q would be higher still.
- The honest reading: M4-NIFTY is a lead to test forward, not a proven edge.

## Method (short)

- **Data:** the Dhan lake for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX: index minutes, India VIX, and
  nearest-expiry options ATM±10 with OI, IV and volume.
- **Periods:** Aug 2021 – Dec 2022 and Jul 2024 – 12 Oct 2026. **No decision row, feature or label comes from
  F1's locked holdout (Jan 2023 – Jun 2024).** Its index and chain rows are dropped on read, and the guard asserts it.
  The trade simulator's option file still holds those days. Only the expiry calendar reads it: the 29 Dec 2022 expiry
  flag uses the 2 Jan 2023 session, and expiry dates are published by NSE in advance.
- **Decision grid:** 64 bar closes a day. Every feature uses only data up to that minute:
  - the brain features are covered by the look-ahead guard;
  - the option-chain features have their own test with the future deleted or perturbed (`test_r30.py`).
- **Labels** (the future, never inputs): the net of buying the ATM CE or PE at the next minute's open, with fills and
  charges as in R29. Exits:
  - X1, your lock: stop −30%, after +Rs 1,000 keep at least +Rs 500, out after 60 minutes;
  - X4: out after 30 minutes;
  - SPR, the debit spread: buy ATM, sell the strike 2 steps out of the money; stop −40% of the debit, target +60%, out
    after 45 minutes.
- **Models:** gradient-boosted trees with fixed settings and no tuning, trained on all indices together.
- **Combinations:** tercile cells with at least 300 rows and 60 days, positive in every training year. The top 20 per
  side are kept.
- **Trading rules:** one trade at a time per strategy and index, at most 3 a day.
- **Walk-forward folds:**
  - F1: trained on 2021-22, tested Jul–Dec 2024;
  - F2: also trained on Jul–Dec 2024, tested on 2025;
  - F3: also trained on 2025, tested Jan–Oct 2026.

  Training sets: 68,554, 108,554 and 187,974 rows. Test sets: 40,000, 79,420 and 61,440 rows (125, 249 and 192
  sessions).
- **Random twins:** the same day and side at a random grid minute, 1,000 sets.

## Honest caveats

- **Departures from PREREG found by the review** (none changes a verdict):
  - SENSEX was also traded in the first test period (Jul–Dec 2024), by models with no SENSEX training rows (PREREG said
    its first test fold is F2). MIDCPNIFTY had only 44 training days in that fold.
  - The gate ignores test periods with no trades.
  - M2's "cut short" column reuses the option-buy columns, so it means nothing for the spread.
  - Feature importance is read from the CE model only.
- Random-entry trades lose Rs 45–385 each here, so even losing strategies can beat them (p 0.003–0.004, e.g. M4
  BANKNIFTY at Rs −1 a trade). Those small p-values also lower M4-NIFTY's q. Beating random is necessary, not
  sufficient.
- Raw EMA price levels (ema9 … ema200) are model inputs. Across pooled indices they partly act as an index/era tag.

- Many earlier studies read 2024-26 (R29 included), and I saw R29's results before writing R30's plan. The plan and
  every setting were fixed before R30's own outcomes, but only a forward test is truly unseen.
- The lake has no bid / ask, so the spread is modelled; the 2× spread check covers part of that.
- The debit spread's one-trade-at-a-time gate uses its 45-minute limit as the busy time.
- A few lake minutes are stamped twice. The later row of the minute is kept.
- 77 trades is a small sample: the 95% range of M4-NIFTY's average trade is wide, about Rs −21 to +559 (bootstrap).

## Files

- `research/hunt/r30/`:
  - `r30cfg.py`;
  - `r30data.py`: minutes, chain features, labels, table;
  - `models.py`: M1-M4, C1, C2, walk-forward, gate;
  - `test_r30.py`;
  - `results/`.
- Experiment entries: `research/experiments/entries/r30-*.yaml`.
