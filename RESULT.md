# R103: how option selling works, and does it work for us? (22 Oct 2026)

Boss asked: "understand deeply how options selling work." The full guide is `research/DEEP_OPTIONS_SELLING.md`, with
every rule checked online against SEBI, NSE and Zerodha on 22 Oct 2026. The pre-registered test is
`research/HUNT_R103_OPTIONS_SELLING.md` (code and tables in `research/hunt/r103_sell/`). Research only: nothing in the
app changed, and nothing goes to paper or live.

## The 15 things to know before selling an option

1. **A seller is an insurer.** They take the premium now. The most they can make is that premium. The most they can lose
   is unlimited when naked, or the wing width minus the premium when hedged.
2. **Selling wins often by design.** The 09:20 ATM straddle is beaten by the close on only 7% of normal NIFTY days
   (n 961), and India VIX over-states NIFTY's next month 81% of the time (n 4,507). A 60-80% win rate is normal, and on
   its own it means nothing.
3. **The rare day decides everything.** On expiry day the close beats the 09:20 straddle 40% of the time (n 259). Of
   cheap expiry options, 97% die, but 1% go 10x and one went 178x. Selling that 178x option for Rs 5,000 on 13 Oct 2026
   lost about Rs 95,000.
4. **Theta is the pay, gamma is the bill.** A normal day burns 12-14% of the 09:20 straddle. Expiry day burns 87% by 15:15,
   most of it after 14:30. That is the same hour when one 100-point move swings a neutral short straddle by 0.8 of a
   futures lot. Those figures are for the rolling ATM straddle. The 09:20 strikes a seller actually holds were still worth
   a median 83% of their value at 15:05 on NIFTY expiry days (n 87), because the move adds to one leg what time takes
   from both.
5. **Naked selling breaks your account rule.** A naked 09:20 BANKNIFTY straddle lost Rs 29,257 on one lot on 4 Jun 2024.
   That is more than two months of your -Rs 13k stop in one afternoon. Only hedged structures (iron fly, condor, credit
   spreads) are even eligible.
6. **Holding overnight is a different risk.** On 23 Mar 2020 NIFTY opened 9.1% down. A short straddle held from the
   Friday lost about Rs 74,000 a lot at 2020 prices, about Rs 1.9 lakh at today's. Intraday-only and hedged are the two
   rules that are never negotiable.
7. **Margin is the real capital.** A hedged lot needs about 2.5% of the short legs' value: Rs 75k on NIFTY and Rs 83k on
   BANKNIFTY today. On expiry day it is 4.5%, because of SEBI's extra 2% from Nov 2024. A naked lot needs about Rs 1.5 lakh.
8. **Costs are the same per round trip for a buyer and a seller.** STT is 0.15% of every sell. A hedged four-leg trade is
   eight orders, about Rs 250-300 a trade on one lot. That is the single biggest reason our hedged tests lost.
9. **The rules changed in 2024-26:**
   - one weekly expiry per exchange: NIFTY on Tuesday, SENSEX on Thursday, BANKNIFTY monthly only;
   - lots of 65 for NIFTY and 30 for BANKNIFTY;
   - the expiry-day 2% margin, no calendar-spread benefit on expiry day, and buyers pay the premium upfront;
   - delta-based position limits with intraday snapshots;
   - STT up to 0.15% from 1 Apr 2026.
10. **Order of legs matters.** Buy the wings first and close the shorts first. Exiting the hedge first breaks the margin
    benefit, and Zerodha then doubles the margin as a penalty. The app already closes shorts first.
11. **A stop must sit inside the structure's worst case.** Our stop at 50% of the credit was beyond the iron fly's max loss
    on most days, so it fired on 6 of 998. On a hedged trade, set the stop on the max loss.
12. **Puts always cost more than calls**, by 2.6 IV points on NIFTY on 91% of days (n 1,222). The market prices the crash.
    The extra credit on the put side is that price, not free money.
13. **Selling into an event is selling insurance just before the claim.** Budget day doubles the day's range, and the
    2024 election count moved NIFTY -5.9%. Those days need a blackout.
14. **The data can lie on far strikes.** Our lake keeps only ATM +-10 strikes. A far wing that the market moves away from
    stops printing, and an old price makes a seller look rich. That is exactly what happened here (item 15).
15. **One hedged lot is the most this account can carry.** Its worst day is Rs 7-15k on NIFTY and its margin Rs 75k. Your
    -Rs 40k drawdown stop is about half of one lot's margin.

## The test results (pre-registered; 1 lot; after real charges and slippage)

We tested 24 versions: NIFTY and BANKNIFTY, expiry days and other days, three structures (iron fly, wide iron fly, iron
condor), and two exits (a stop alone, or the stop plus a profit lock and a target). Every trade sells at 09:21 around the
index's ATM strike and is closed by 15:10, or 15:05 on expiry day.

| group | tests | win rate | net, as first priced | net, far wings priced fairly | verdict |
|---|---|---|---|---|---|
| BANKNIFTY, normal days | 6 | 62-70% | +Rs 1.9 to +Rs 5.4 lakh (design, about 1,000 trades each) | **-Rs 2.9 to -Rs 3.3 lakh** | rejected |
| ... the same six, holdout quarter | 6 | 81-86% | +Rs 34k to +Rs 54k (64 trades) | **-Rs 18.6k to -Rs 21.0k** | rejected |
| NIFTY, normal days | 6 | 45-56% | -Rs 35k to -Rs 1.16 lakh (about 960 trades each) | -Rs 2.1 lakh | rejected |
| NIFTY, expiry days | 6 | 37-52% | -Rs 59k to +Rs 28k (249 trades each) | about the same | rejected |
| BANKNIFTY, expiry days | 6 | 40-51% | -Rs 19k to +Rs 15k (175 trades each) | about the same | rejected |
| buying the same four legs instead | 24 | 2-59% | loses on all 24 | | rejected |

- **Win rate and net side by side tell the story.** The best-looking test won 70% of days and still lost money once the
  far wings were priced fairly.
- **Why the first BANKNIFTY profits were wrong.** On about half those days, a wing the index moved away from stopped
  printing in our data, and it was valued at an old, higher price. We caught this before reading the holdout and wrote
  the extra check down first.
- **The repricing errs in the seller's favour.** Tested on wings that did keep printing, the method runs 1-5 points high.
- **The holdout failed even as first priced.** Its win rate jumped by more than the allowed 15 points.

## A second test: the list in Boss's brief (480 cells, `research/HUNT_R103_DEEP_SELLING.md`)

A second session tested what the brief listed: the NAKED short straddle and strangle as well as the iron condor and iron
fly; entries at 09:20, 09:45 and 10:15; per-leg stops at 1.5x and 2x, a combined stop at 100% of the credit or 3% of
margin; a 50% target; expiry-day variants; random-entry twins; and the buy side. It was pre-registered separately, with
its own code and costs, on 20 Nov 2024 - 14 Jul 2026 (394 trades a cell on all days, 86 NIFTY and 20 BANKNIFTY expiry
days), one BH family of 480.

| 09:20, all days, 1 lot | win rate | net | worst day | months below -Rs 13k | significant? |
|---|---|---|---|---|---|
| NIFTY naked straddle, no stop | 63% | +Rs 71,535 | -Rs 30,676 | 3 | no (p 0.43) |
| NIFTY naked straddle, stop at 1x credit | 62% | +Rs 88,578 | -Rs 17,929 | 1 | no (p 0.28) |
| NIFTY naked strangle, stop at 1x credit | 64% | +Rs 80,641 | -Rs 10,101 | 1 | no (p 0.12) |
| NIFTY iron condor, stop at 1x credit | 48% | -Rs 102,929 | -Rs 5,777 | 2 | yes, a loss |
| BANKNIFTY naked straddle, no stop | 61% | +Rs 77,090 | -Rs 12,079 | 3 | no (p 0.20) |
| BANKNIFTY iron fly, stop at 1x credit | 21% | -Rs 250,260 | -Rs 9,847 | 11 | yes, a loss |
| NIFTY straddle BOUGHT instead, no stop | 33% | -Rs 216,050 | -Rs 12,838 | - | yes, a loss (p 0.02) |

- **219 of 480 cells made money, 8 of them significantly after BH, and 3 passed every gate:** NIFTY short strangles on
  expiry days (09:45 and 10:15). In the locked holdout, read once (14 expiry days): -Rs 3,987, +Rs 694 and +Rs 1,293.
  That is Rs 50-92 a trade for the two that stayed positive, which is noise (p 0.83-0.85). **Nothing is confirmed.**
- **The naked straddle's profit is not stable.** The same NIFTY 09:20 straddle lost -Rs 60,282 over Aug 2021 - Nov 2024
  (786 days).
- **Every hedged cell on all days lost, in both periods; every buy-side mirror lost.** Both studies agree.
- **Stops trade profit for survival.** A 2x per-leg stop cut NIFTY's straddle from +Rs 71,535 to +Rs 27,467; a combined stop
  at 100% of the credit kept the profit and cut the worst day to -Rs 17,929, but still left a month below -Rs 13k.

**Follow-ups at Boss's request (22 Oct).** A profit lock did not raise the profit (it beat the plain cell in 23 of 54
versions). With win rate as the goal, one version holds at least 70% wins and a profit in both periods: the NIFTY
expiry-day strangle at 10:15 with a combined stop and a 30/20 lock (74% / 70% win, +Rs 123 / +Rs 75 a trade). A
pre-registered search of 90 enhancements added only 1-3 win-rate points at half the profit. The base and three finalists
now go to a forward test from 20 Oct 2026, with a decision after 40 expiries (`research/hunt/r103_selling/PREREG_FWD.md`).
Nothing trades meanwhile.

**Two more ideas, tested after Boss said selling has many routes (22 Oct).** Selling only when options are expensive
(implied volatility above the index's recent realised volatility) helped in 2024-26 but not in 2021-24: 0 of 144 pass.
Selling a credit spread in the direction of the Liquidity and Donchian signals lost in 17 of 18 versions. The lesson
from all four tests: an option seller is paid for holding time, mostly overnight, and intraday selling collects very
little of it except on expiry day. The one strong idea left untested is a hedged multi-day hold
(`research/HUNT_R103_DEEP_SELLING.md`).

## Verdict

**No option-selling rule is ready for the app, and both tests say so.** Before costs, these hedged structures roughly
break even. The eight orders a trade make them losers, and naked selling makes money only by risking days that break your
account stop.
Nothing goes to paper.

## What a selling arm would need in the app before any paper trade

This applies only if a future pre-registered test passes:

1. A basket margin check before the first leg goes. It must use the hedge benefit and the 2% expiry-day margin, and it
   must refuse the whole basket if any leg would be refused.
2. Wings bought first on entry, so a half-filled basket is never a naked short. Shorts closed first on exit already
   works.
3. A basket stop and profit lock read on the credit received, with the stop set inside the structure's max loss.
4. Broker-resting stop orders on the short legs, so a dead phone cannot miss a stop. A resting order is cancelled when
   the app closes that leg itself.
5. An event and VIX blackout: no entry on Budget, election-result or RBI days, after a Fed or US CPI print, or above a
   set VIX.
6. Expiry-day rules: no new short after 14:30, plus the existing 15:05 square-off.
7. Restart recovery that restores the four legs as one position with its stop and lock, watched first, as CLAUDE.md
   requires for open positions.
8. A credit-position P&L view: credit minus the cost to close, with the margin shown separately.

## Checks run

- **R103 tests:** 19 tests in `research/hunt/r103_sell/tests/test_r103.py`, covering leg pricing and charges, stop, lock
  and target paths, the look-ahead guard, both holdout locks, BH, the bootstrap, the BUY mirror, the margin estimate, the
  stale-leg repricing and the tail table.
- **Experiment log:** `python -m research.experiments.validate` passes with the 24 new r103 entries.
- **Secrets and data:** the CLAUDE.md secret grep prints nothing. No raw data or `__pycache__` is in git, and the
  results folder is 116 KB.
- **Not run:** `research/brain/tests` and `features catalog --check`. Boss declined those runs in this session, and R103
  does not touch `research/brain`.
- **Second study (`research/hunt/r103_selling/`):** 31 tests in `test_r103.py`, including the look-ahead guard on synthetic
  and real lake days. `research/brain/tests` (183 passed), `features catalog --check`, the experiment-log validator with 8
  new `r103-deep-*` entries, and the secret grep were all run and clean. The results folder is under 1 MB.
