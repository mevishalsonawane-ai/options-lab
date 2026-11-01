# R144c part C: before / after backtests of the five changes proposed in part B (Boss, 1 Nov: "Backtest all and provide me before and after results")

Written 1 Nov 2026, before any part-C number. Research only; nothing in the app changes (any change needs Boss's
approval, and live changes start in shadow, CLAUDE.md).

**Honest frame:** every period below was already read in parts A and B, and the changes came from looking at them. These
are hindsight before / after numbers, not proof. The only fresh check left is new days (a forward log).

## The changes (fixed now; no tuning afterwards)

| id | arm | change | how it is run |
|---|---|---|---|
| C1 | Hero Rs 1,000 ticket | switch it off | its trades removed (after = Rs 0) |
| C2 | VIX divergence | NIFTY held to 15:10 like BANKNIFTY (no -15% stop, no 20-minute rule) | R1's own engine, variant `N13_VIXDIV|a0.002|EOD` for NIFTY; BANKNIFTY unchanged |
| C3 | FT3 | no tickets on BANKNIFTY, FINNIFTY, MIDCPNIFTY expiries from 20 Nov 2024 (monthly-only expiries) | those tickets removed |
| C4a / C4b / C4c | Flow | premium stop 30% / 40% / none (index stop, 2R index target and 15:15 stay) | `flow_port`, same entries, only the stop changes |
| C5 | FT3 | skip "long unwinding" (option price and OI both fell in the 15 minutes before the mark) | part A's frozen rule |
| C6 | Night | skip when India VIX is down more than 3.13% today at 15:19 (part B's design cut) | part B's rule |

Plus each arm with all its changes together (FT3: C3 + C5).

## Reported (unseen 1 Dec 2022 - 19 Nov 2024, design 20 Nov 2024 - 31 Mar 2026, final 1 Apr - 19 Oct 2026, and all three)

Trades, win rate (against Boss's 65%), net, average win / loss, sum of losing days, max drawdown, worst day, worst month.
For the skips (C3, C5, C6): 2,000 random twins (skip as many trades at random in each period) on the sum of losing days
over the three periods; p reported, plainly a hindsight p. Results: `results/before_after.json`.
