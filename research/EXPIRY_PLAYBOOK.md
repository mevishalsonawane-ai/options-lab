# I10 Expiry-day playbook: theta, straddle decay, max pain, pin risk and the last hour

*13 Oct 2026. Pre-registration: `research/context/PREREG.md` (I10). Code: `research/context/expiry_playbook.py`;
numbers: `research/context/results/expiry_playbook.json`. Data: the `research-data` release - the expiring contract
itself is in the data on 53 NIFTY expiry days (Apr 2025 - Apr 2026) and 11 BANKNIFTY monthly expiry days (Feb 2025 -
Feb 2026). Re-runs on the 6-year Dhan lake with one switch (`IRAALGO_CONTEXT_SOURCE=lake`); until then every number below
is from about one year of expiries. Advisory only: nothing here trades.*

## The playbook (read this first)

1. **Time value melts fastest after lunch, and is almost gone by 15:00.** Of the ATM option's time value at 09:20
   (strike fixed at 09:20; time value = straddle minus what is already in the money), the median left was:

   | time | NIFTY (53 expiries) | BANKNIFTY (11 expiries) |
   |---|---|---|
   | 10:00 | 61% | 62% |
   | 11:00 | 49% | 54% |
   | 12:00 | 35% | 50% |
   | 13:00 | 24% | 43% |
   | 14:00 | 11% | 19% |
   | 14:30 | 6% | 18% |
   | 15:00 | 2% | 4% |
   | 15:20 | about 0% | about 8% (noise of thin prices) |

   An option bought on expiry day pays for this decay every minute it is held; after 14:00 almost all of what is left
   is the move itself. For the Hero arm and expiry exits: a position that is not working by 14:00 has nothing left to
   wait for.
2. **The straddle as a whole did not decay like that**, because the index moves away from the 09:20 strike: the ATM
   straddle at 15:20 was a median 81% of its 09:20 value on NIFTY (quartiles 33% - 120%) and 33% on BANKNIFTY (25% - 60%).
   At 09:20 it priced a move of 0.43% (NIFTY) / 0.50% (BANKNIFTY) of the index.
3. **The last hour moves more on expiry days.** |14:30 to 15:25| median 0.15% on NIFTY expiry days vs 0.10% on other
   days (53 vs 446 sessions, Mann-Whitney p = 0.004); BANKNIFTY 0.33% vs 0.12% (11 vs 487, p = 0.01). Expiry exits
   should not assume a quiet close.
4. **Max pain is not a forecast** (R16 found this on 4 years of bhavcopy OI; this is a re-check). From the 09:20 OI, the
   close was on average 0.58% from max pain against 0.50% from the open (NIFTY, 53); BANKNIFTY 0.54% vs 0.45% (11). The
   close ended within 0.25% of max pain on 25% / 27% of expiries and within 0.25% of the open on 34% / 45%. Using max
   pain as a target is worse than assuming no change.
5. **Pin risk is small and not predictable from the morning's OI.** The close landed within half a strike step of the
   09:20 largest-OI call or put strike on 11% of NIFTY expiries (53) and 9% of BANKNIFTY ones (11).
6. **Selling the straddle into the close is not shown to pay.** Pre-registered checks X1-X4 (short ATM straddle sold at
   10:00, 12:00, 13:30 or 14:30, bought back 15:20, no stop, after charges and slippage, 1 lot): on this release the
   design period holds only 0-1 expiries with the expiring contract, so **nothing could be tested on design** and no
   rule is picked. The holdout was read once, for information:

   | sell at | NIFTY (52) mean / win % / worst | p | BANKNIFTY (11) mean / win % / worst | p |
   |---|---|---|---|---|
   | 10:00 | Rs 355 / 62% / Rs -30,933 | 0.36 | Rs 3,437 / 82% / Rs -10,926 | 0.07 |
   | 12:00 | Rs -326 / 64% / Rs -21,898 | 0.66 | Rs 2,430 / 82% / Rs -13,655 | 0.15 |
   | 13:30 | Rs 433 / 60% / Rs -12,614 | 0.26 | Rs 1,978 / 73% / Rs -12,808 | 0.20 |
   | 14:30 | Rs 288 / 46% / Rs -11,026 | 0.30 | Rs 772 / 73% / Rs -15,158 | 0.34 |

   Frequent small wins and rare large losses, as option selling always shows; none is significant, and no app rule
   sells this straddle. Re-test on the lake with a fresh holdout before any use.

## The expiry-day card in the app

On an expiry day of NIFTY, BANKNIFTY, FINNIFTY or SENSEX the app shows a card with: how much of the ATM option's time
value is typically left at this time of day (the table above, with the sample size), that the last hour moves more,
that max pain is not a forecast (R16), and the 15:05 square-off. It is information only and changes nothing.

## Method

- Strike: the index's 09:20 close rounded to the strike step; the expiring contract's call and put minute closes (the
  release pads untraded minutes with the last price). Expiry day: the contract in the data expires that day.
- Costs for X1-X4: the app's paper fills (+-5 bps) plus a modelled half-spread (1 tick, 2-4 when thin) on each leg both
  ways, and SandboxCosts charges (Rs 20 brokerage an order, STT 0.15% of the sell, exchange 0.03553%, SEBI, stamp,
  GST 18%); lot size by date.
- Max pain at 09:20 from the expiring contract's OI on the release's strikes (about +-1,000 points of the index), not the
  full chain; R16's version used the previous evening's full bhavcopy OI and found the same.
- BH over the 8 X tests could not be applied (no design p-values); the experiment log counts all 8.
