# R83 E7 / E10 implementation notes and deviations (liquidity / regime worker, 18 Oct 2026)

Code: `liq.py` (E7), `eve.py` (E10, plus shared minute helpers), `mcxreal.py` (real-MCX replay at the final read),
tests `test_liq_eve.py`. Written before any design number was seen.

## E7: Liquidity on the WTI proxy (1h primary, 15m)

- Book = `research/liquidity_gold.py` `simulate` (carry off) with `indicator/liquidity.py` swing_zones(20, "full") +
  pool_zones(2, 5, 10), levels continuous across sessions. Bars cut every tf minutes from 09:00 IST, last bar of a day
  may be short. Days with < 60% of the MCX minutes are dropped; missing minutes forward-filled (as liquidity_gold).
- Entry window: the next bar's first minute between 09:15 IST and 2 hours before the MCX close.
- Fills: entry = that minute's open (proxy bid; spread charged by `common.fut_net`, not by bid/ask series — the gold
  code used a separate ask file). "Next liquidity" fills at the level (gold code subtracted a half-spread there; here the
  spread is in the cost model). Both sides traded (PREREG), shorts = short CRUDEOILM.
- Hard stop (R83 addition, PREREG): entry -/+ 1 x ATR(14) of the book's bars, ATR known at the signal bar, checked on
  minute CLOSES, before the target in the same minute; fills at that close + stop slippage.
- Cut-off = 15 minutes before the MCX close (`common.flat_min`), not liquidity_gold's session cut.
- Twins: same side, the same clock minute (first proxy minute at or after it, within 10 minutes) on a random day of
  the same year, same stop and target distances as a fraction of the fill, held at most the actual trade's minutes,
  exits on closes (the target is checked on closes in the twin, on highs/lows in the rule; a small conservative bias
  in the rule's favour of at most the high-close gap).
- Real-MCX replay: the lake series is CLOSE-only, so zones are built from o = h = l = c minute closes; the option
  version buys the 1-ITM CRUDEOIL option at the same signal (R53 sim_option, first print within 2 minutes) and sells
  at the futures trade's exit minute; option days on expiry are skipped.

## E10: ATR-percentile regime on EVE (calm primary, busy)

- EVE as `McxEveRules`, on Henry Hub proxy minutes inside MCX hours: entry at the next minute's OPEN (the app buys at
  the next minute's open; R53's close-only replay filled at that minute's close). Stop / target / time / flat on closes.
- Regime: daily bars = IST calendar days of proxy minutes (all hours of the CFD), ATR14 = simple mean of true range
  (`common.atr`); the percentile of the previous day's ATR among the 250 ATR values ending on that day. The first
  ~265 proxy days (2016 to early 2017) have no regime, so the filtered variants cannot trade there while the
  baseline does; the walk-forward folds (2019-2022) are unaffected.
- Real-MCX replay: R53's MA1 path (range_break, flat 23:15, 120 min, option -15% on its low, 1-ITM, expiry days
  skipped for options) re-priced with R83 costs; regime percentile taken from the proxy series (the MCX price is the
  proxy times USD/INR, so the ATR rank is almost the same).
