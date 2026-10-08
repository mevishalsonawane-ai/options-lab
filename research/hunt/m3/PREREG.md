# M3 pre-registration: MCX intraday rules (written 8 Oct 2026, before any P&L)

Boss: MCX via Zerodha, Rs 1 lakh, fixed lots (1 lot), option BUYING preferred; mini futures studied and flagged.

## Data
- Dhan `/charts/rollingoption`, MCX_COMM, OPTFUT, MONTH, expiryCode 1 (near month), ATM-3..ATM+3, CALL+PUT, 1-min,
  fields OHLC, IV, OI, volume, strike, spot. Aug 2025 -> 8 Oct 2026.
  Underlyings: CRUDEOIL 294 (reused from scratchpad/hunt/strad_crude/cache, not refetched), NATURALGAS 401,
  NATGASMINI 596, GOLD 114, GOLDM 117, SILVER 115, SILVERM 122, COPPER 152 (whatever is served).
- `spot` = near-month futures minute price (continuous series, rolls with the option expiry).
- Option price of a held strike = that strike's row, whichever ATM offset it sits at that minute. If one strike appears
  twice in a minute, keep the row with volume > 0 (X3 issue #5). If the strike leaves ATM±3, the leg is closed at its
  last seen price at that minute (flagged; counted).
- Spreads: live Dhan option-chain snapshots (bid/ask) of each commodity, 9 Oct 2026 09:01-10:20 IST (morning) and
  STRAD-CRUDE's crude evening snapshots (8 Oct 22:24-23:28 IST). Evening spread for non-crude = morning spread x
  (crude evening / crude morning), floored at the crude evening relative spread.

## Contract multipliers (Zerodha, 1 lot)
CRUDEOIL 100, CRUDEOILM 10, NATURALGAS 1250, NATGASMINI 250, GOLD 100 (1 kg, price per 10 g), GOLDM 10 (100 g),
SILVER 30 (30 kg, price per kg), SILVERM 5, COPPER 2500. Options carry the same lot as the futures of that name.

## Session
MCX 09:00 - 23:30 IST (US daylight time) / 23:55 IST (US standard time). Bars folded from 09:00.
Entries 09:05 .. 22:30 IST (Liquidity, h18). All positions closed at the last minute <= session end - 10 min.
Skip option-expiry days (the near option expires that day) for option trades. Rolls: levels and bar history are reset at
each change of the near option expiry (no history across a roll).

## Instruments per signal
- OPT: buy 1 lot of the 1-ITM option (CE one strike below ATM for up, PE one strike above for down), near month, at the
  next minute's OPEN after the signal (the bar that decided closes at t; we buy at the open of minute t).
  Big contracts (CRUDEOIL, NATURALGAS, GOLD, SILVER, COPPER) and minis (GOLDM, SILVERM, NATGASMINI) are reported
  separately. A trade whose premium x lot > Rs 1,00,000 is reported as "not affordable" (counted, P&L shown both
  with and without such trades; headline = affordable only).
- FUT (flagged, Boss allows study only): 1 lot mini futures (CRUDEOILM, NATGASMINI, GOLDM, SILVERM), both directions,
  entry next minute open (spot series of the mini's own option chain; CRUDEOILM uses CRUDEOIL's spot, same price).

## Rules (all fixed now; no tuning)
1. **LIQ** - the app's Liquidity 15+5 ported faithfully (LiquidityRules.kt): swing lookback 20, pools 2 contacts 5 apart,
   10 confirmation bars, max age 300; entry when the last completed bar's close takes a POOL overlapping an active SWING
   zone of the same side; room filter (target >= 1 index-stop unit ahead); two books, 15-min and 5-min, one position
   each. Exits, first of: option low <= 85% of fill (fill at the stop, or at the minute open if it gapped below); futures
   trades 1 index-stop unit back through the broken level; after 20 min, option close < 105% of fill; futures touches the
   next liquidity level (target); a completed bar closes back through the level (failed break); a new level forms on the
   trade's side (new liquidity); session cut-off.
   **Index-stop unit for MCX** (the indices use ~0.065% of BANKNIFTY ~= 0.4 x its typical 15-min bar range): 0.4 x the
   median 15-minute bar range (high-low of the futures) over the previous 10 sessions.
2. **USORB** - US-session opening-range break. US open per commodity in New York time: crude & natgas 09:00 ET
   (NYMEX), gold & silver 08:20 ET (COMEX), copper 08:10 ET (COMEX); converted to IST with the US DST calendar
   (2 Nov 2025 -> standard, 8 Mar 2026 -> daylight, 1 Nov 2026). Range = first 15 minutes after the open. Entry: first
   1-min close beyond the range within 120 min. Exits: futures touches the range midpoint (stop); futures touches break
   edge + 1 range width (target); 60 min after entry (time); option -15% stop as in LIQ; cut-off.
3. **INORB** - Indian-morning range: 09:00-09:30 IST range, entries 09:30-12:00, same exits as USORB.
4. **EVT** - event trades. Crude: EIA weekly petroleum (Wed 10:30 ET, official exceptions from STRAD-CRUDE's
   eia_ts.csv). Natgas: EIA storage (Thu 10:30 ET; holiday weeks shifted per EIA - approximated as Thu, or Fri when
   Thu is a US holiday). Range = the 15 min before the release. Entry: first 1-min close beyond the range within 30 min
   after the release. Exits: range midpoint (stop), break edge + 1 range width (target), 30 min (time), option -15%.
   API (Tue 16:30 ET = 02:00/03:00 IST Wed) falls after MCX closes - not tradable on MCX; reported as such.
5. **H60** - h18-style: at each 5-min bar close, close > highest high (or < lowest low) of the previous 60 minutes
   (12 bars, same session) -> buy in the break direction. Exits: LIQ's option -15% stop, index-stop unit back through
   the level, 20-min time stop (+5%), failed break on 5-min close, 60-min cap, cut-off. One position at a time;
   a new entry needs a fresh break after exit.

Futures versions of every rule: same signal and futures-based exits; option-% exits replaced by: futures stop =
1 index-stop unit beyond the level (LIQ, H60) or range midpoint (ORB/EVT); LIQ/H60 time stop = after 20 min exit if the
futures is not ahead by >= 0.25 index-stop units.

## Costs (Zerodha, checked by NN-CRUDE on zerodha.com 8 Oct 2026)
- Options: Rs 20/order, CTT 0.05% of sell premium, MCX txn 0.0418% of premium (both sides), SEBI Rs 10/crore,
  stamp 0.003% on buy, GST 18% on brokerage + txn + SEBI. Plus half the measured relative spread on each side.
- Futures: brokerage min(Rs 20, 0.03%), CTT 0.01% on sell, MCX 0.0021%, SEBI, stamp 0.002% on buy, GST; plus half the
  measured futures spread each side (crude Rs 2/bbl per NN-CRUDE; others from snapshot quotes, else 1 tick... set to
  max(1 tick, 0.01% of price)).
- Exits use option 1-minute HIGH/LOW for the stop; other exits at the minute close.

## Statistics
- Design = 5 Aug 2025 - 7 Jul 2026 (monthly breakdown = walk-forward by month; rules are fixed so nothing is refit).
- LOCKED HOLDOUT = 8 Jul 2026 - 8 Oct 2026, run ONCE after design results are written to the log.
  Caveat: NN-CRUDE and STRAD-CRUDE already opened Apr-Oct 2026 for crude (different rules).
- Random baseline: for every trade, 200 random entries on a random session of the same month at the same time of day
  (+/-30 min), random side, same instrument and exits. p = share of random sets with mean net >= the rule's.
- Families: every (rule x commodity x instrument) cell counts as one variant. BH at q 0.10 on random p; Hansen SPA
  (stationary bootstrap of daily net, mean block 5) on the family of cells with >= 30 trades.
- Gates to call a cell "candidate" (design): net > 0, random p < 0.05, BH q < 0.10, >= 60% green months, >= 30 trades.
  Holdout gate: net > 0 and random p < 0.10.
- Report: trades/day, win rate, gross & net Rs/trade and /day at 1 lot, max drawdown, green months, random p,
  capital fit at Rs 1 lakh. Best hours: mean |1h move|, option volume share and random-entry gross by IST hour.

## Amendment 1 (8 Oct 2026, ~19:00 UTC) - lead's request, ideas from M1 (research/MCX_GUIDE.md)
Honesty note: before this amendment, a pipeline test printed CRUDEOIL design results for rules 1-5 (all negative,
with provisional spreads). Nothing in rules 1-5 was changed. The rules below were added on the lead's request; no
result of them had been seen. No result of any other commodity had been seen.
6. **EVE** - evening breakout: range = 17:00-19:00 IST futures; entry on the first 1-min close beyond it, 19:00-22:00;
   exits: range midpoint (stop), break edge + 1 range width (target), 120 min, option -15%, **flat by 23:15**.
7. **MOMA** - intraday momentum on the MCX clock: sign of the return from the previous session's last futures price to
   19:30 IST; at 22:30 buy in that direction; exit 23:30 or the session cut-off (23:20 in US summer), whichever is
   first. No other exit. Days with a zero return skipped.
8. **MOMB** - crude only, EIA days: sign of the futures return over the first 30 minutes after the release; buy in
   that direction 60 minutes before the session end (22:30 / 22:55 IST); exit at the session cut-off.
Same instruments, costs, random baseline, BH/SPA family (the family grows by these cells) and holdout.
EIA WPSR shifts for 15 Oct / 12 Nov 2026 fall after the data and do not matter.
Futures versions: EVE uses the midpoint stop; MOMA/MOMB pure time exits.

## Amendment 2 (8 Oct 2026, ~19:35 UTC) - stale quotes; made AFTER seeing provisional design results
Seen before this change: the design table of all cells with provisional spreads (all-rows pricing). COPPER options,
GOLDM LIQ15/INORB and a few others looked positive. Then I measured that 77% of COPPER (GOLD 80%, SILVER 77%, SILVERM
50%, GOLDM 44%; CRUDEOIL 5%, NATURALGAS 6%) near-ATM option minutes have volume 0 - flat copies of the last trade.
A futures break followed by a buy at a stale option price is a look-ahead fill. So option prices now come from
**printed minutes only** (volume > 0), as h23/h24 did:
- entry = the open of the first printed minute in the signal's next 3 minutes (no print -> no trade);
- the option stop triggers only on printed lows; other exits sell at that minute's print, or at the next printed open
  within 10 minutes, else the last print (flagged).
This is a data-hygiene fix applied to every commodity, rule and the random baseline alike; no rule changed.
Both versions are reported; prints-only is the headline. The old version is in work/allrows/.
