# The Rs 5,000/day hunt: final verdict

Written 8 Oct 2026. Boss's frame: option BUYING only, Rs 1,00,000 capital, FIXED lots (no Kelly, no growing size),
goal Rs 5,000 a day. Every study below used real Dhan option minute prices (2020-2026), the app's fills and charges,
the real bid/ask spread measured in h24, exits checked on the option's 1-minute high/low (the wicks), a random-entry
baseline, a correction for the number of things tried, and a locked last year (1 Oct 2025 - 6 Oct 2026) opened once.

## Verdict

**Rs 5,000 a day from option buying with Rs 1 lakh and fixed lots: NO.**

- **The one rule with a real, small edge is the app's Liquidity 15+5 on BANKNIFTY, 1 lot, with its own exits.**
  - It made about **Rs 65/day** after all costs before the locked year and **Rs 167/day** in it (h24, h36).
  - That is Rs 1,400-3,500 a month on average, with 40-50% of months losing and drawdowns of Rs 32-40k.
  - A Rs 5,000 day happens about once in 30-45 sessions; the best 10 days make the whole year.
- **Reaching Rs 5,000/day on average would need 30-77 BANKNIFTY lots,** about Rs 22-63 lakh of capital (h36).
  Rs 1 lakh safely carries 1 lot.
- **Direction signals are real but tiny.** Strong closes carry on overnight, OI build-up and FII flows lean the right
  way, option traders move a few minutes before the index. Each is worth a few hundredths of a percent; an option
  buyer pays several times that in spread, theta and charges on every trade.

## What was tested in this hunt (h22-h44), on top of the earlier catalog

| study | what | result |
|---|---|---|
| h22 | which strategies made Boss's +5k paper days | 1 Oct = Liquidity on its older rules; 5 Oct not replayable (Jarvis/manual/unknown Pine) |
| h23 | Liquidity on BANKEX, NIFTYNXT50; more indices at 1 lot | no; NXT50 untradeable, BANKEX no edge |
| h24 | real bid/ask spreads | BN 0.16%, MIDCP 0.21%, FIN 0.42%; BN 1 lot is the robust plan |
| h25 | all 61 candlestick patterns, 9 chart patterns, every MA type, 25 indicators (215,280 versions) | no; equal to random |
| h26 | volume and OI (option strikes, flows, heavyweight volume) | no; OI leans right by 1-2 bp, far below cost |
| h27 | global cues by 09:15 (US, Asia, GIFT, crude, FX, yields, ADRs) | no; all priced into the gap |
| h28 | calendar and scheduled events | no; intraday drift is negative, gains come overnight |
| h29 | implied vol, skew, "cheap" options | no; the option forward leads, but too late to pay |
| h30 | signals on the option premium's own chart | no; worse than random (buys the top) |
| h31 | overnight holds from 15:20 | weak, decaying edge (NIFTY "all agree"); paper only |
| h32 | working back from +15/+20/+25/+30 premium-point jumps | no; every warning sign also precedes drops |
| h33 | intraday news (59k timed headlines) and price shocks | no; price moves before the headline |
| h34 | every old strategy re-run with premium-point exits | no change; points hurt Liquidity badly |
| h35 | every support/resistance level the app computes | no; only Liquidity and ORB levels trade; others lose |
| h36 | the best honest plan, day by day | BN Liquidity 1 lot, Rs 65-167/day |
| h37 | Fibonacci, Gann, harmonics, Elliott, Renko, P&F, Market Profile, Wyckoff | no; equal to random |
| h38 | index futures with OI (Dhan + NSE bhavcopy fetched) | no; minute futures OI history does not exist for expired contracts |
| h39 | heavyweight stock options (fetched, 75.5M rows) | no; same-minute, no tradable lead |
| h40 | NSE archives: F&O/CM bhavcopy, FII stats, participant OI, NSDL FII cash | no; predicts the gap, not the day |
| h41 | why/when/how the market moves (MARKET_HOW.md) | timing is real, direction is not readable |
| h42 | 1,296 questions answered from the data | 327 true facts; none a profitable option buy |
| h43 | an outside 25-day "bake-off" and its champion | 25-day luck; all lose over 5 years |
| h44 | meta-labelling Liquidity with 49 features | not adopted; hurts BN in the locked year |

In all, well over 2 million strategy versions in this hunt alone, plus the earlier 26-million-rule search, the 55-strategy
catalog, SCALP17 (1.1 million scalps) and the ML study (h1).

## What to do

1. **Trade, if at all, Liquidity 15+5 BANKNIFTY 1 lot, with the app's own exits.** Keep it on paper until it has about
   300 more paper trades in line with the backtest.
2. **Keep MIDCPNIFTY, the four ORB arms and the other arms on paper only.** They do not pay after costs.
3. **Do not switch to point targets** (+20/-15 etc.) on Liquidity; they cut its rare big runners (h34).
4. **Record live data from now on** (spread and depth at each signal, live futures minutes with OI). It is the only way
   to test order-flow ideas later; no free history exists.
5. **Expect Rs 2-4k a month at best on Rs 1 lakh, with losing months.** Rs 5,000 a day needs tens of lakhs of capital
   at the same edge, or a source of edge that none of these tests found.

## Weak leads, paper only (none passed its own test)

- OI build-up against the trade as a skip filter on Liquidity (h26): +Rs 81/day in the locked year, q 0.13.
- NIFTY "all agree" overnight call/put (h31): +Rs 245/day in the locked year, decaying for years.
- MIDCPNIFTY futures OI build-up (h38): 25 trades only.
