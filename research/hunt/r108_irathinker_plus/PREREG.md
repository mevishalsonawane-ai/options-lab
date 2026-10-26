# R108: enhancing IraThinker's strategies (pre-registration)

*Written 26 Oct 2026, before any R108 number was computed. Boss, 26 Oct, after R107: "Can we enhance it" → "Improve the
strategies".*

## Honesty first

- **I have already seen R107's design-period results.** A change chosen because it would have fixed R107's design losses
  is fitted to the design data. Each variant below is therefore tagged:
  - **ex-ante:** a standard change that would be tried anyway;
  - **post-hoc:** motivated by what R107's design results showed.
- **The holdout (1 Oct 2025 to the end of the data) is still unread for every R107 and R108 test.** It is the honest
  judge. A variant that passes design is read on the holdout once; a post-hoc variant counts only if it also passes there.
- The list is fixed now: 10 real-price variants. No variant is added, dropped or retuned after results. Nothing in
  IraThinker's signal code changes; only the trade built on each signal does.
- **The ORB alert and the gap fade are not retried.** ORB has now failed here (R107) and in R22, R58 and R92 ("0 of 257
  pass"). Retuning it on two years would only fit noise.

## Data, pricing, split and gates

These are the same as R107 (`research/hunt/r107_irathinker/PREREG.md` and Amendment 1):
- IraThinker's own signals (R107Runner.java output) on the research-data release;
- real NIFTY and BANKNIFTY options, `Costs("dated")`, 'liq' slippage, one lot;
- monthly-contract rules only where the nearest listed contract is the monthly;
- design to 2025-09-30, holdout from 2025-10-01 read once;
- gates: n ≥ 40; mean > 0 with day-bootstrap p < 0.05; BH q ≤ 0.10 over all 10 R108 tests (the descriptive strangle variants included, which only makes it stricter) (also reported over the 23 tests of
  R107 plus R108); > 0 at 2x slippage; > 0 without the 5 best days; worst month ≥ −₹13,000; then holdout mean > 0 on 10 or
  more trades.

The BANKNIFTY strangle cannot pass, and this is known in advance. Only 36 design Mondays have real monthly prices
(Nov 2024 – Sep 2025), so gate 1 (n ≥ 40) fails by construction. Its variants are run and reported as **descriptive
only**. To be judged properly it needs the 2021+ monthly option history (Drive lake `opt_BANKNIFTY_MONTH_*`).

## The variants

| ID | Strategy | Change | Tag | Why |
|---|---|---|---|---|
| E1 | strangle, BANKNIFTY | **stop:** close both legs when the cost to buy them back reaches 2x the credit (a loss equal to the credit), checked on minute closes, exit at the next minute's open | ex-ante | a naked short needs a stop; Boss's monthly stop rule |
| E2 | strangle, BANKNIFTY | **wings (iron condor):** also buy the CE at ATM+600 and the PE at ATM−600 | ex-ante | defined risk; cuts the margin and the gap tail |
| E3 | strangle, BANKNIFTY | **skip expiry weeks:** no entry on a Monday whose monthly expiry falls inside Mon–Thu | **post-hoc** | R107's three worst weeks were expiry-week settlements |
| E4 | mom60, NIFTY | **one strike in the money** (CE: strike − 50, PE: strike + 50) | ex-ante | more delta and less time decay per rupee, so more of the index move reaches the option |
| E5 | mom60, BANKNIFTY | one strike in the money (± 100) | ex-ante | same |
| E6 | mom60, NIFTY | **no 30% premium stop**, time exit at 60 minutes only | ex-ante | a 30% stop on a 60-minute hold may cut trades the index later rescues |
| E7 | mom60, BANKNIFTY | no 30% premium stop | ex-ante | same |
| E8 | mom60, NIFTY | **the nearest expiry (weekly)** instead of the monthly | ex-ante | prices all 636 NIFTY fires, not only monthly weeks |
| E9 | prediction engine, NIFTY | **strong votes only:** trade only when the 60-minute consensus is at or above its design-period median (94.1%) | ex-ante | a confidence filter is the natural first step for a voting ensemble |
| E10 | prediction engine, BANKNIFTY | strong votes only (design median 93.9%) | ex-ante | same |

The consensus medians were computed from the signal file (no outcome seen) before this file was written:
- NIFTY: 312 design votes, median 94.1;
- BANKNIFTY: 342 design votes, median 93.9.

E1–E3 are descriptive (see above). E4–E10 can pass.

FINNIFTY and SENSEX are left out: there are no option prices, and these changes act on the option, so index points
cannot show them.
