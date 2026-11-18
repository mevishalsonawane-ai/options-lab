# R197 pre-registration: can option selling reach an 80% win rate AND make money? (side study, 18 Nov 2026)

Written 18 Nov 2026, **before any R197 profit or loss was computed**; hash and time in `results/PREREG_STAMP.txt`.
Owner: Boss (they/them). Boss: "Do whatever you can try to get me 80% winrate, and survay says, 60-70% people win in
option selling, so i want to get 80% with more precision. Do all the combination/profility, math, and all the study we
have done until now, use all dont miss anything". **Research only. The app stays buy-only.**

## 1. The math, said first

* For a rule with win rate W, average win a and average loss b (rupees, after costs), the net per trade is
  W x a - (1 - W) x b. It makes money only if **b / a < W / (1 - W)**. At W = 80% that means **the average loss can be
  at most 4x the average win**; at 60%, 1.5x.
* A seller can raise W at will: take profit early (a small target), sell further out, or widen the stop. Each move
  shrinks a or grows b. **The question is not "can we get 80%" (we can) but "is there an 80% rule whose b / a stays
  under 4 after costs, out of sample".**
* **The survey:** a 60-70% win rate per trade is what sellers see (R195: 55-69% on the straddles). SEBI's FY25 study
  of individual F&O traders found about 91% of them lost money over the year (net losses about Rs 1.06 lakh crore,
  after costs). Win rate per trade and money made are different things.
* **"Precision":** every win rate is reported with its Wilson 95% interval and its sample size.

## 2. Data, engine, costs, periods: R195 / R196's, unchanged

R195's stores and engine (stale-leg Black-Scholes marks, real entry prints, 'liq' slippage with stop ticks doubled,
dated charges, today's lot, R195's margin model) and R196's pair loop. **New:** a profit target in the pair loop
(exit both legs when the pair's P&L >= target x credit), and a two-leg spread loop with a stop and a target
(`r197engine.py`). Both reduce to the R195 / R196 engines when the target is off (tested). Design 2 Aug 2021 -
31 Dec 2025. 2026 (1 Jan - 13 Nov) is a **second look** (R195 read it). The forward window from 16 Nov 2026 is the
clean holdout, read once at 40+ trades.

## 3. The grid (45 tests, all on NIFTY, every one logged)

**G1, straddle / strangle with stops (naked + stop; signal source none directional, strikes from the index):** 09:20
decision, sell C(K0 + o) and P(K0 - o), o in **{0, 2, 4} steps**; a stop on each leg when its mark >= **(1 + s) x its
fill**, s in **{0.25, 0.50, 1.00}**; a target on the pair: exit both when the pair's P&L >= **t x credit**, t in
**{none, 0.20, 0.35, 0.50}**; else square-off 15:10 (15:05 on expiry day). 3 x 3 x 4 = **36**.

**G2, bull put spread (fits Rs 56k on non-expiry days; signal source none directional):** non-expiry NIFTY days, 09:20,
sell P(K0 - o), buy P(K0 - o - 4 steps), o in **{2, 4, 6} steps**; stop at a loss of 50% of max loss; target
t in **{none, 0.30, 0.50} x credit**. 3 x 3 = **9**.

## 4. The selection (fixed now; design data only)

* **Pick-N** = the G1 test with design win >= 80% and design net > 0 that has the highest design net.
  **Pick-A** = the same for G2. If no test qualifies, the pick is "none" and that is the answer for that family.
* **Filters from every earlier study, on each pick (6 tests):** R195 S2's VIX filter (VIX at 09:20 >= its 250-session
  median), R195 S3's implied-vs-realised filter (IV >= 1.2 x RV20), R196 A6's shock-day skip.
* **Other indices (5 tests):** Pick-N on BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; Pick-A on BANKNIFTY.
* m for BH = 4,821 (entries after R196) + 45 + 6 + 5 = **4,877**, counted even when a pick is "none".

## 5. Gates for an "80% candidate" (the picks and their extensions)

All of R196's gates 1-8 (design and post-Nov-2024 net > 0; 2026 net > 0; 40+ trades each; > 0 without the 5 best trades
in each; twin p < 0.05: timing twin for G1, side twin for G2, random-day twin from the pick for filters; BH q <= 0.10;
the account stop, worst month >= -Rs 13k and drawdown <= Rs 40k over design + 2026; > 0 at 2x slippage), and
**9. win rate >= 80% in design AND in 2026.** BH p for grid tests that are not picks = their day-bootstrap p.

A pass is "provisional" until the forward read. "Possible at Rs 56k" is reported as in R195.

## 6. Everything we did, in one table (descriptive)

`r197frontier.py` puts every option-selling rule tested so far (R103's 24 published results; R195's 23; R196's 14;
R197's grid) on one win-rate vs net-per-trade table, with b / a and the break-even win rate W* = b / (a + b). This is a
summary, not a test.
