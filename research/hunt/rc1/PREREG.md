# RC1 pre-registration: the best way to put a small amount into BTC / ETH from India, after tax

Written 13 Oct 2026, BEFORE any data for this study was downloaded and before any test was run. This file is committed on
its own; the commit hash is quoted in the write-up (`research/HUNT_RC1_CRYPTO_SMALL_ACCOUNT.md`). Nothing below may
change after that commit. A change needs a new entry, a new pre-registration and a fresh holdout.

Boss, 13 Oct 2026: "Find the best way to trade crypto, BTC and ETH, using a small amount." Research only: no app code,
no exchange accounts, no orders. Boss is an Indian resident; accounts of Rs 25,000 and Rs 1,00,000 are tested.

Honesty note: the general shape of BTC/ETH history (2018 and 2022 bear markets, the 2020-21 and 2024 bull runs) is
public knowledge and was known when this was written. The trend rules below use the textbook settings (100- and 200-day
moving averages, a 50-day Donchian channel, 20/10-bar 4h breakouts, 2 ATR stops); none of them was tuned on this data.

## Data

- Binance public archive (data.binance.vision; api.binance.com is geo-blocked here), streamed and deleted, cached as small
  parquet files OUTSIDE git:
  - spot klines BTCUSDT and ETHUSDT, 1d and 1h, from the first month listed (Aug 2017);
  - USD-M perpetual klines BTCUSDT and ETHUSDT, 1d and 1h, from the first month listed (Sep / Nov 2019);
  - USD-M funding rates (8-hourly prints), from the first month in the archive.
- USD/INR daily (ECB reference rate via frankfurter.dev), carried forward over weekends and holidays.
- Every price is turned into rupees as USDT price x USD/INR of that day. Indian exchanges quote INR pairs at a small and
  varying premium to this; it is ignored (it moves buy and sell prices together) and stated in the write-up.
- The sibling crypto lake (branch claude/crypto-lake) is not published today, so it is not used.

## The holdout (LOCKED now)

- **Holdout: 13 Oct 2025 to 12 Oct 2026 (the last 12 months), read ONCE**, after the design results are written down,
  by one run with `RC1_READ_HOLDOUT=1`, which writes `research/hunt/rc1/results/holdout_read.json` and refuses to run a
  second time. If the archive does not yet have the last days (for example funding for October 2026), the holdout ends on
  the last day the archive has, and the write-up says so.
- **Design: everything before 13 Oct 2025.** The design loader cuts the data at 12 Oct 2025 23:59 UTC; code paths that
  are not the one holdout run cannot see later bars.
- Indicators need warm-up, so the holdout run is given the design bars before 13 Oct 2025 as history only (no trade is
  counted before 13 Oct 2025). Accounts start flat (all cash) on 13 Oct 2025.

## Walk-forward

- Spot ideas: the first trade can happen once the 200-day average exists (5 Mar 2018). Anchored yearly walk-forward:
  each out-of-sample (OOS) year runs from 13 Oct to 12 Oct. The first training span is 5 Mar 2018 - 12 Oct 2019; OOS
  years are 13 Oct 2019 ... 12 Oct 2025 (6 years). "Walk-forward" numbers below are the stitched OOS span 13 Oct 2019 -
  12 Oct 2025 run as ONE account (a rule switch at a year boundary trades like any other signal).
- Where an idea has a choice of rule (idea c: MA100 / MA200 / Donchian 50), the rule for each OOS year is the one with the
  highest after-tax (base costs, VDA) final wealth on ALL earlier design data from 5 Mar 2018; ties go to the slower rule.
  The holdout uses the rule chosen on the whole design span.
- Fixed rules (no choice) are simply run over the OOS span; their earlier years are reported too.
- Perpetual-future ideas start 1 Jan 2020 (the funding archive and both perps exist); their walk-forward span is
  1 Jan 2020 - 12 Oct 2025 and their baseline is buy-and-hold over the same span.

## Costs (assumptions, labelled as such in the write-up; per side, as a share of the trade's value)

| venue | case | taker fee | GST on fee | half-spread | per side |
|---|---|---|---|---|---|
| Indian spot (CoinDCX / CoinSwitch / Mudrex / ZebPay) | low | 0.10% | 18% | 0.05% | 0.168% |
| Indian spot | **base** | 0.25% | 18% | 0.15% | **0.445%** |
| Indian spot | high | 0.50% | 18% | 0.30% | 0.890% |
| INR-settled perp (Delta Exchange India / CoinDCX futures) | low (maker) | 0.02% | 18% | 0.01% | 0.034% |
| INR-settled perp | **base** | 0.05% | 18% | 0.02% | **0.079%** |
| INR-settled perp | high | 0.05% | 18% | 0.05% | 0.109% |

- Funding on perps: Binance's real 8-hourly prints (Delta India's run slightly higher, per research/NN_CRYPTO.md), charged
  on the day's notional at that day's open; positive funding = longs PAY, shorts RECEIVE.
- Perp contract sizes (Delta India): BTC 0.001 BTC, ETH 0.01 ETH; whole contracts only. This is the only place the
  Rs 25,000 and Rs 1,00,000 accounts can differ (spot can be bought in any rupee amount above about Rs 100).
- Perp margin is isolated. Liquidation price: long entry x (1 - 1/L + 0.5%), short entry x (1 + 1/L - 0.5%), with
  L = leverage. If the day's (4h bar's, for idea g) low (long) or high (short) touches it, the position is closed there
  and its whole margin is lost. Leverage tested: 1x and 3x (3x is the maximum for this study). No margin top-ups.
- Delta India is younger than most of the data; Binance perps stand in for it (1-hour return correlation 0.987 BTC /
  0.994 ETH, research/NN_CRYPTO.md).

## Tax and TDS (modelling assumptions; Boss should confirm with a CA)

- **Spot (VDA, sec 115BBH):** each sale order is one disposal. Its gain = sale value at the fill price - the FIFO cost of
  the coins sold at their buy fill prices. Fees are NOT deductible. A positive gain is taxed at 31.2% (30% + 4% cess); a
  negative gain is ignored: no set-off against other gains and no carry-forward.
- Tax for an Indian financial year (1 Apr - 31 Mar) is paid from cash at the 31 Mar close. If cash is short, holdings
  are sold pro rata to pay it (those sales are taxed too). Unpaid tax on gains realised so far in the year is a liability
  in the after-tax equity curve.
- **TDS:** 1% of every spot sale's value is withheld and returned 365 days later (a credit, not a cost; it only locks cash).
  The TDS drag = after-tax CAGR with the lock - without it.
- **Perps, reading A (VDA):** each closed position is a disposal; its gain = price P&L + net funding (fees not deductible);
  31.2% on a positive gain, losses ignored.
- **Perps, reading B (business income):** each financial year's net = price P&L + funding - fees over all perp trades; tax
  31.2% (the top slab with cess; a lower slab only helps) on a positive net after losses carried forward up to 8 years.
  Reading B never sets perp losses against spot (VDA) gains (115BBH forbids it). No TDS on INR-settled perps.
- "Pre-tax" = the same run with tax 0 and no TDS (fees still paid). "Gross" = no fees, no tax.
- At the end of every window the account sells everything (fees and tax charged), so hold-type ideas also pay their tax.

## Ideas (10 entries; the active ones c-g are the BH family)

All spot ideas decide on the daily close (00:00 UTC = 05:30 IST) and trade at the NEXT bar's open. Prices for signals
are spot closes; perp ideas fill at perp opens.

| id | idea | rule |
|---|---|---|
| rc1-a1 | baseline: buy and hold | the whole pot into BTC, ETH, or 50/50 on day 1; never rebalanced |
| rc1-a2 | baseline: DCA | the pot split into 12 monthly buys (1st of each month) or 52 weekly buys (Mondays), then held; also a running SIP of pot/12 every month for the whole window (reported as money-weighted IRR); BTC, ETH, 50/50 |
| rc1-b | rebalanced 50/50 | 50/50 BTC/ETH bought on day 1; rebalanced to 50/50 at each month's first open, vs only when a weight leaves 40-60% (checked daily) |
| rc1-c | spot trend | per coin, hold half the pot (on entry: buy 0.5 x the account's equity then, capped by cash; never resized while held) when the daily close is above its 100-day SMA / above its 200-day SMA / (Donchian) enter on a close above the prior 50-day high and exit on a close below the prior 50-day low; else cash. Rule chosen by walk-forward |
| rc1-c-perp | trend on INR perps | rc1-c's walk-forward rule, executed long-only on perps at 1x and 3x; funding paid; tax readings A and B |
| rc1-d | vol-targeted trend | rc1-c's rule (same walk-forward pick) with the coin's weight = 0.5 x min(1, 40% / annualised 30-day realised vol of daily log returns); the position is only resized when the target differs from the current weight by more than 0.2 x 0.5 = 0.10 of the pot (a no-trade band to limit taxed sales) |
| rc1-e | day-of-week | design data only first: per coin, mean daily return of each weekday vs all other days (Welch t), and Sat+Sun vs Mon-Fri: 16 tests, BH at q = 0.05. A strategy is built and tested further ONLY if a test passes: hold on the significantly positive day(s), or always except the significantly negative day(s). If none passes: logged as failed at design, no holdout read |
| rc1-f | funding carry | per coin with half the pot: when the mean of the last 9 funding prints (3 days), annualised (x 3 x 365), is above 10%, buy spot and short the same notional of perp (1x: spot = perp margin = half the sleeve; 3x: spot 3/4, margin 1/4); exit both legs when that mean falls below 0. Spot leg taxed as VDA; perp leg readings A and B. Practicality flagged (two exchanges, capital split, tax on each leg) |
| rc1-g | 4h breakout (short-term) | 4h bars (UTC 00, 04 ...). Long when a 4h close is above the highest high of the prior 20 bars; stop = entry - 2 x ATR(14) at the signal bar (fills at the stop, or the open if it gaps through); also exit at the next open after a close below the lowest low of the prior 10 bars. Half the pot per coin. Spot |
| rc1-g-perp | 4h breakout on perps | rc1-g on perps at 1x and 3x, readings A and B |

## Primary test per idea, twins and BH

- Primary version: 50/50 BTC + ETH, Rs 1,00,000, base costs, after tax (VDA; perps reading A at 1x), walk-forward span.
- **Random-entry twins** (c, c-perp, d, e if built, f, g, g-perp): 1,000 twin sets, seed 20261013. Each twin keeps, per coin,
  the strategy's in-market spells (their number and lengths) and its out-of-market gaps, and shuffles their order at
  random; same sizing rule (d: vol weights inside the twin's spells), costs, tax and exits. For rc1-b the twin rebalances
  on the same NUMBER of randomly chosen days. p = (1 + twins with after-tax final wealth >= the idea's) / 1,001.
- **BH at q = 0.05 across all active ideas' p-values** (b, c, c-perp, d, f, g, g-perp, and e if built).

## Gates (an active idea passes only if ALL hold)

1. Edge: BH q < 0.05 (twins, walk-forward span, after tax).
2. Worth it: on the walk-forward span, after tax, its Calmar (CAGR / |max drawdown|) beats buy-and-hold 50/50 over the
   same span AND its max drawdown is smaller. For rc1-f (market-neutral) instead: after-tax CAGR above 7% (a rough Indian
   fixed-deposit rate) with a max drawdown under 10%.
3. Holdout (read once): its after-tax final wealth beats the median of 1,000 twins on the holdout.
4. Costs: gate 2 still holds with the high cost case.

## Reported for every idea (design years, walk-forward, holdout separately; Rs 25,000 and Rs 1,00,000)

Gross / pre-tax / after tax+TDS: CAGR, max drawdown, worst calendar month, % green months, trades (orders) per year, days
to recover from the worst drawdown (trough back to the old peak; "not yet" if never), total tax paid vs the no-tax
version, TDS drag; per coin and 50/50; cost sensitivity (low / base / high); perps at 1x and 3x with liquidations
counted; sample sizes (days, months, trades) with every number.

## If an idea passes

Research only. Nothing in the app trades crypto (TODO C: knowledge only). A pass becomes a FINDINGS bullet and a knowledge
fact tagged crypto; it never arms, sizes or places anything. This is research, not financial advice.
