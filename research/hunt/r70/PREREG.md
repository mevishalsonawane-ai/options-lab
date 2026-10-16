# R70 pre-registration: Boss's selected lock (+5% once +25%) on the other arms

Written 16 Oct 2026, **before any R70 result was computed**. Research only (no `android/**`); the app change is Boss's
decision, made in the parent session.

Boss, 16 Oct: *"And also let me know, what if we add this same in all the other arms and strategies too"*, then, after
R69: *"Select the best."* The best of the day's 96 tests was R66 **L25: once the option has traded +25% above the fill,
the stop rises to +5%** (locks only rise; set by a minute's high, active from the next minute; gap fills at the worse
open). Donchian, OOV and Solo 2.0 were tested in R66. R70 tests the same rule on every other arm that the R51 engine can
re-price.

## 1. Arms tested (signals unchanged, on the index; each arm's own exits kept)

| arm | signal (source) | own exits (B0) | trades from |
|---|---|---|---|
| **Liquidity 15+5** | index level breaks (swing + pool zones) on 15- and 5-minute index bars (BANKNIFTY), 30 + 5 (FINNIFTY, parked), 15 + 5 (MIDCPNIFTY); 1-ITM | -15% premium stop; 20-minute time stop unless +5%; the index exits (index stop, next-level target, failed break); 15:10 | `hunt/r51/fam_liq.py make(und, books)` on R51's clean stores, exactly as R63 ran it (MIDCPNIFTY index stop 8 points) |
| **Solo (midday)** | the 12:00 index move >= 0.5 ATR (R57 setup S1), NIFTY / BANKNIFTY / FINNIFTY, the strongest one a day; 4-ITM | index stop 0.3 ATR, breakeven at 0.45 ATR, 14:30; no premium stop or target | `hunt/r57/r57study.py plain_solo` on R57's stores |

Not tested, and why:
- **ORB, ORB Fresh, ORB Sweep, Range Fade** (hidden since 15 Oct) already have a premium ladder at 25/50/75% of their
  target and use their own engine (`hunt/r3orb`, `r49`). R49: 0 of 336 lock variants passed.
- **Night**: bought 15:20 and sold 09:16 the next day, so there is no intraday run to lock.
- **Hero / Hero ticket**: lottery-style tickets with their own trail (25% trail armed at 2x).
- **MCX arms**: their own engine, closes only, and lake data only from Aug 2025. **Gold**: no option premium.
- **Telegram calls, forge ideas, Pine, Jarvis**: no fixed rule to replay.
- **VIX divergence**: off by default, failed its luck checks, and its engine (`hunt/r1`) is not ported.

## 2. Variants (2 per arm, 4 tests)

| id | rule |
|---|---|
| **B0** | the arm's own exits (what it runs today) |
| **L25** | B0 + the stop rises to +5% once the option has traded +25% |

Each arm's trades are generated once with its own exits. The same trades (same entry row, same index-exit row) are then
priced again with L25, so only the exit changes. Limit: an earlier exit could let Liquidity take a later signal it
skipped; that is not modelled. The number of such cases is reported.

## 3. Measurement, periods, gates, decision

R51 engine (held contract, liq slippage, stops on the minute low with gap fills at the worse open, locks from the next
minute, `Costs('dated')`, 1 lot). Design 20 Nov 2024 - 31 Mar 2026 with the walk-forward quarters from Apr 2025; final 1
Apr - 13 Oct 2026 (already read by R51 / R63 and others: weak evidence), read once (`results/FINAL_READ`). Reported as in
R65: trades, win rate, net, per trade, average win and loss, biggest loss, worst day, max drawdown, green days, and
trade by trade against B0 (Rs given up, Rs saved).

Random-exit twins (500, seed 70): as many random early exits as L25 changed trades, at a uniform minute after the fill
and up to the plain exit; BH across the 2 arms. A paired day bootstrap (2,000, seed 70) of L25 - B0. **Decision:** L25
is recommended for an arm only if its net is at least B0's in design, walk-forward and final AND its exit-twin q <=
0.10. Otherwise the arm keeps its exits, and L25 is reported as harmless (it never lost in any period) or not.

Liquidity BANKNIFTY is the go-live candidate (26 Oct). Any change to it would be paper-only and labelled, so that its
plain-rules record stays separable (CLAUDE.md).
