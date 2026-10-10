# HUNT h45: "1 lot, exit at 5% profit or 5% loss"

Written 8 Oct 2026. Option BUYING only, 1 lot, ATM and 1-ITM, nearest expiry. Code: `research/hunt/h45/`
(`PREREG.md` written before any P&L, `run.py`, `analyse.py`). Logs and tables: `scratchpad/hunt/h45/`
(`pre.csv`, `hold.csv`, `pre.log`, `hold.log`). Holdout (1 Oct 2025 onward) locked and read once, at the end.

## Verdict (plain language)

**NO. A +5% / -5% bracket loses about Rs 100-140 on every trade after costs, whatever the entry. Rs 5,000/day: NO.**

- **The +5% side wins about half the time (44-52%). After costs it would need to win about 61%.**
  - One round trip costs about **1.1% of the premium** (app charges, app fills and the real spread).
    For BANKNIFTY 1-ITM that is about Rs 115 a trade before the holdout and Rs 190 in the holdout.
  - With a 5% bracket, about a quarter of the possible gain (1.1 of 5 points) goes to costs. So you must win
    (5 + 1.1) / 10 = **about 61%** just to break even.
  - The options move up 5% about as often as they move down 5%. Liquidity hits +5% 50% of the time, h18 52%,
    the CHAMPION 44% and random entries 46%.
  - So before costs a ±5% bracket is roughly a coin flip (gross Rs -29 to +40 a trade). After costs it is
    **about Rs -100 to -140 a trade**.
- **It turns the one good entry into a loser.** Liquidity 15+5 with the app's own exits makes money (h36: +Rs 65/day
  before the holdout and +Rs 167/day in the holdout, BANKNIFTY 1 lot). With ±5% it loses **Rs -104 a trade
  (Rs -76/day) before the holdout and Rs -259 a trade (Rs -255/day) in the holdout.**
  - Liquidity earns from a few big breaks. A +5% target sells every winner at +5%, while the losers still cost
    -5% plus charges.
- **No bracket and no time stop rescues it.**
  - 24 exits x 2 strikes x 11 entry groups = 528 real variants. Before the holdout only 4 are positive, all on
    MIDCPNIFTY Liquidity with a ±10% bracket (best t = 0.6). BH q <= 0.10: **0 variants**.
  - Tighter is worse: ±3% loses Rs 110-140 a trade, because costs are then 1.1 of 3 points.
- **Same-minute ties hardly matter.** Both +5% and -5% were touched in the same minute in **2.0% of trades**
  (5.9% at ±3%, 0.3% at ±10%). The test takes the stop first, which is the cautious choice.
  - If those ties were split 50/50, a trade would improve by only about **Rs 3-10**.
- **The holdout says the same.** ±5%/15:10 1-ITM loses on every entry group except MIDCPNIFTY Liquidity
  (+Rs 100 a trade, +Rs 93/day, t = 0.4).
  - That one is noise. The same rule lost Rs -114 a trade on MIDCPNIFTY before the holdout.
- **Trades/day needed for Rs 5,000/day: no number works.** The net per trade is negative, so more trades only lose
  faster.
  - Even a +Rs 50 a trade version would need 100 trades a day at 1 lot.
  - Random entries with ±5% lose **Rs -129 a trade** (pre) and **Rs -223** (holdout). The Boss's bracket on random
    timing is the same as these strategy entries, minus a few rupees.

**What to do:** do not use a ±5% (or ±3/7/10%) premium bracket. Keep Liquidity 15+5 with the app's own exits.

## The small table (1-ITM, nearest expiry, +5% / -5%, out by 15:10, 1 lot, NET after all costs)

| entry | +5% hit rate (pre / hold) | break-even hit rate | gross Rs/trade (pre) | **net Rs/trade pre** | **net Rs/trade holdout** | net Rs/day pre / hold | max drawdown pre / hold | trades/day for Rs 5k |
|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 BANKNIFTY | 50% / 47% | 61% / 59% | +11 | **-104** | **-259** | -76 / -255 | -83k / -67k | never (loses) |
| Liquidity FINNIFTY | 52% / 57% | 67% / 64% | +41 | -114 | -35 | -55 / -23 | -59k / -18k | never |
| Liquidity MIDCPNIFTY | 52% / 55% | 61% / 59% | +37 | -114 | +100 | -65 / +93 | -45k / -43k | (50 at the holdout figure; pre-holdout loses) |
| Liquidity BN+FIN+MIDCP | 51% / 52% | 62% / 60% | +26 | -109 | -71 | -173 / -184 | -178k / -88k | never |
| h18 60-min break, 5 indices | 52% / 48% | 62% / 60% | +13 | -109 | -191 | -398 / -955 | -510k / -239k | never |
| h43 CHAMPION (BN EMA 8/21 + ADX) | 44% / 47% | 61% / 59% | -29 | -140 | -170 | -178 / -243 | -187k / -63k | never |
| random entries, 5 indices | 46% / 46% | 64% / 61% | -22 | -129 | -223 | (per trade only) | — | never |

- **Pre-holdout:** Liquidity Oct 2021 - Sep 2025; h18 and the CHAMPION from 2021; random entries 2020/21 - Sep 2025.
  **Holdout:** 1 Oct 2025 - 5 Oct 2026.
- Rs/day = net divided by every trading day of that entry's indices (days with no trade count as zero). Lot = the
  lot size in force on each date.
- Break-even hit rate = (stop % + cost %) / (target % + stop %), with cost % = the average round-trip cost as a
  share of the premium.
- ATM gives the same picture: BANKNIFTY Liquidity ±5% loses Rs -108 a trade before the holdout and Rs -260 in it.

**Context: other brackets.** NET Rs/trade, 1-ITM, pre-holdout, for Liquidity BN / Liquidity all 3 / h18 / CHAMPION / random:

| bracket (out at 15:10) | Liquidity BN | Liquidity all 3 | h18 all 5 | CHAMPION | random |
|---|---|---|---|---|---|
| +3% / -3% | -114 | -123 | -142 | -131 | -122 |
| **+5% / -5%** | **-104** | **-109** | **-109** | **-140** | **-129** |
| +7% / -7% | -120 | -89 | -96 | -139 | -133 |
| +10% / -10% | -112 | -55 | -113 | -160 | -145 |
| +5% / -3% | -98 | -105 | -124 | -131 | -121 |
| +10% / -5% | -108 | -84 | -97 | -143 | -131 |

- Time stops of 15, 30 or 60 minutes move these by only about Rs 10-50. The best is Liquidity BN +10/-10 with a
  30-minute time stop: Rs -61 a trade, still a loss.
- The full grid is in `scratchpad/hunt/h45/pre.csv` and `hold.csv`.

## How it was tested

- **Engine:** the validated `research/obuy` engine, with h34's cost code.
  - Entry is at the option's next-minute open, with app fills (+5 bps).
  - The target is a resting order filled at +X% on the option's 1-minute HIGH. The stop is an SL-M on the 1-minute
    LOW, -10 bps; a gap through the stop fills at the bar's open.
  - If both are hit in the same minute, the stop is taken.
  - The signal's own index stop and target are off: this is a pure % bracket plus a time stop.
  - All trades are out by 15:10.
- **Costs:** app charges at the rates in force on each date, plus the real half-spread on entry and exit
  (BN 0.16%, NIFTY 0.16%, MIDCP 0.21%, FIN 0.42%, SENSEX 0.20%). GROSS = mid-price points x quantity.
- **Entries were not changed.**
  - Liquidity uses the app port (BANKNIFTY + FINNIFTY) plus h4's port for MIDCPNIFTY.
  - h18_r60 trades all 5 indices, at most 3 a day.
  - The CHAMPION (h43) is the BANKNIFTY 5-minute EMA 8/21 + ADX > 15 rule, at most 2 a day.
  - Each takes one position at a time. Expiry days are skipped, as each rule does.
- **Random baseline:** 12 random minutes per index-day (09:16-15:04, coin-flip side) on all 5 indices, with the
  same exits.
  - Each strategy is also compared with random entries reweighted to its own index and hour mix (the `rand_t`
    column).
  - Liquidity and h18 beat matched random by about Rs 20-45 a trade, but not by enough to pay the costs.
  - The CHAMPION does not beat random at all (Rs -140 against Rs -133).
- **Ties:** a trade counts as a tie when it is stopped in a minute whose HIGH also reached the target and whose
  open was between the stop and the target.
  - The 50/50 column gives half of those trades the target fill instead.
  - Trades whose bar opened above the target are also counted as tie-or-better and credited in full; there were
    almost none.
- **Multiple testing:** 528 real variants, one-sided t-test on daily net, Benjamini-Hochberg. 0 pass. No variant
  was chosen, so nothing was "selected" on the holdout. The holdout was computed once.

## Caveats

- The h45 Liquidity uses a market entry at the next open, not h14's +0.5% limit entry, and no h10 impact model.
  Neither would change a Rs 100+ a trade loss into a gain.
- A 5% move on a 1-ITM BANKNIFTY option (about Rs 460 before the holdout, Rs 700 in it) is only about 23-35 premium
  points. These results agree with h34: small fixed targets give away the big winners and pay costs on every trade.
