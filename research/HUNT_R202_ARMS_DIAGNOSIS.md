# R72, R51 and Liquidity 15+5: a deep look at today's rules, and the issues found (20 Nov 2026)

Boss, 20 Nov: *"I want you to analyse R51, R72 and Liquidity, and if there are any issues"* ... *"Deep analyse it."*

This is a **diagnosis, not a test**: it slices the baseline trades of R202 (today's rules V5, 1 lot, after real charges and
slippage, 21 Nov 2024 - 5 Oct 2026: R72 2,157 trades, R51 2,822, Liquidity 1,208). Every slice was looked at after the
holdout was read, so a pattern here is a **lead**, not proof. Code: `research/hunt/r202_trend_exit/r202diag.py`; every
number: `results/diagnosis.json`. Data limit as R202: the `dhan-data` branch, to 5 Oct 2026. Nothing in the app changed.

## The issues, most important first

### 1. R51 has no edge of its own: all of its profit is R72's trades

| R51's trades | trades | win rate | net | a trade |
|---|---|---|---|---|
| the same option at the same minute as R72 | 1,137 | 66.0% | Rs 2,13,228 | Rs 188 |
| **R51 alone** (R72 did not take it) | 1,685 | 59.5% | **-Rs 21,863** | -Rs 13 |
| for comparison, R72 alone (R51 did not take it) | 1,020 | 62.4% | Rs 1,18,151 | Rs 116 |

- R51 alone lost in both periods (design 1,328 trades, -Rs 6,949; holdout 357 trades, -Rs 14,913) and in 3 of the 4
  half-years. By index: BANKNIFTY +Rs 42,422 (351 trades), FINNIFTY +Rs 19,009 (324); NIFTY -Rs 16,279 (255),
  SENSEX -Rs 28,233 (279), MIDCPNIFTY -Rs 38,781 (476).
- What the app does with a shared break (`PassedArmsHost`): **on paper** R72 decides first and holds the option; R51
  records a would-be trade. So R51's real paper book is mostly the losing "R51 alone" part. **In Live** (when R51's
  trade can be mirrored) both buy: **two lots of the same option**, one from each arm.
- So R51 today is "a second lot on R72's breaks, plus 1,685 trades of its own that lose".

### 2. Together the arms are one big position, far past the account stop rule

| what is counted | net | max drawdown | worst month | worst day |
|---|---|---|---|---|
| R72 alone | Rs 3,31,379 | Rs 1,00,582 | -Rs 26,420 | -Rs 30,007 |
| R72 + R51, both buying (Live) | Rs 5,22,745 | Rs 1,72,369 | -Rs 48,768 | -Rs 39,731 |
| all three arms, each trade counted | Rs 7,65,411 | Rs 1,48,047 | **-Rs 76,231** (Apr 2026) | -Rs 40,097 |
| one position per option (shared trades once) | Rs 3,88,343 | Rs 1,01,221 | -Rs 31,796 | -Rs 30,062 |

- R72 and R51 move together (daily correlation 0.62); Liquidity less (0.25). 355 of Liquidity's 1,208 trades are also
  the same option at the same minute as an R51 / R72 trade, and they carry Rs 1,42,798 of its Rs 2,42,666.
- In April 2026 all three lost at once (R72 -Rs 13,690, R51 -Rs 29,871, Liquidity -Rs 32,670), mostly on MIDCPNIFTY
  and SENSEX.
- Your account stop rule (-Rs 40,000 drawdown or -Rs 13,000 a month) is breached by **every arm on its own** and by
  every combination, at 1 lot. Four months were worse than -Rs 13,000 for the three together (Feb, Mar, Jun 2025,
  Apr 2026). Since 19 Nov the automatic stops no longer act on these arms by your choice, so nothing would have cut
  these months short.

### 3. The backtest assumes money for every trade

Up to **13 positions and Rs 3,54,120 of premium at once**. The day's peak was above Rs 56,000 on 344 of 461 days and
above Rs 1 lakh on 242 (median day Rs 1,05,922). With less free cash than that, the funds check and the lots cut decide
which trades are taken, and the result will differ from every table here (R193's funded table had the same caveat).

### 4. Liquidity is fading, and its profit sits in a few days

- A trade made Rs 327 in Oct 2025 - Mar 2026 and Rs 34 in Apr - Oct 2026 (330 trades, Rs 11,087, win rate 43.9%,
  drawdown Rs 59,441). April and May 2026 lost Rs 47,327.
- Its 5 best days made Rs 1,49,689 of the Rs 2,42,666 total (62%); 23 trades held to the square-off made
  Rs 1,47,531. Without a few big trend days it is about flat.
- Puts made Rs 2,09,228 (602 trades), calls Rs 33,438 (606).
- The **MIDCPNIFTY 5-minute book made nothing**: 293 trades, Rs 412 (holdout: 81 trades, -Rs 7,765). BANKNIFTY 5-minute
  and FINNIFTY 30-minute were also negative in the holdout (-Rs 1,754 on 93 trades; -Rs 4,138 on 14).

### 5. R51's edge is thinner than its costs

| | net a trade | charges a trade | charges as a share of gross profit | one more tick of slippage each way |
|---|---|---|---|---|
| R72 | Rs 154 | Rs 86 | 36% | -4% of net |
| R51 | Rs 68 | Rs 86 | 56% | -10% of net |
| Liquidity | Rs 201 | Rs 94 | 32% | -4% of net |

Average win Rs 1,051 - 1,119 against average loss Rs 1,543 - 1,582 on R51 / R72: they need about 59% wins to break
even and run at 62 - 64% (60.4% in the holdout). A small drop in win rate or a little more slippage turns R51 negative.

### 6. Where the losses come from (R72 / R51)

| exit | R72 | R51 |
|---|---|---|
| step lock (the winners) | 1,177 trades, +Rs 13,12,840 | 1,447 trades, +Rs 15,63,726 |
| -15% stop | 311 trades, -Rs 7,16,552 (-Rs 2,304 each) | 409 trades, -Rs 9,32,949 (-Rs 2,281 each) |
| 30-minute time stop | 565 trades, -Rs 2,53,439 | 859 trades, -Rs 4,29,776 |
| ... of which never up Rs 250 a lot | 153 trades, -Rs 1,86,094 | 254 trades, -Rs 3,27,057 |

- **37% of the losers were once up Rs 500 a lot or more** and still lost: R72 289 of 771 (-Rs 4,10,282), R51 384 of
  1,070 (-Rs 5,52,954), Liquidity 192 of 616 (-Rs 1,53,753). The breakeven only arms at +10% and the lock at +Rs 1,000.
- Winners were on average Rs 1,759 - 1,844 up at their best and kept Rs 1,051 - 1,119.
- The stops themselves hold: only 3 - 4 trades lost more than 20% (the worst -36.7%, one gap).

### 7. It depends on the kind of day and the kind of market

- Nov 2024 - Mar 2025: R72 -Rs 39,831 (496 trades), R51 -Rs 39,419 (584), although the picker was trained on those months.
- Quiet days lose: days with 1 - 3 trades cost R72 Rs 56,253 (98 days), R51 Rs 22,504 (104), Liquidity Rs 45,189 (248).
  Busy days pay. About 44 - 54% of trading days end down.
- Entries at 11:00 - 11:59 lost on all three arms (R72 -Rs 37,192 on 277 trades; R51 -Rs 37,150 on 376; Liquidity
  -Rs 13,509 on 148), and calls earn much less than puts on all three. But R203 already tested "skip the worst hour"
  walk-forward and it failed, so this is not a rule.

### 8. Smaller notes

- R51 by index: NIFTY Rs 21 a trade, MIDCPNIFTY Rs 21, SENSEX -Rs 74; its profit is BANKNIFTY and FINNIFTY.
- MIDCPNIFTY has the biggest loss a trade (-Rs 2,005 on R51, -Rs 1,863 on R72): its lot costs the most (median Rs 27,500).
- Two trades an arm ended on a missing price (data gaps); they do not change any conclusion.
- No sign of the stops or locks misbehaving in the simulation. This look is at the trade record, not at the app's code.

## What I would do about it (your decisions; nothing is built)

1. **R51 (issues 1, 2, 5).** The cleanest risk cut on the table: switch **R51's Trade live off** (your own switch, one
   tap, it only lowers risk). R72 alone: drawdown Rs 1,00,582 instead of Rs 1,72,369, worst month -Rs 26,420 instead of
   -Rs 48,768. It gives up R51's Rs 1,91,366, which is the second lot on R72's breaks. If you want that second lot, R72
   at 2 lots gets it without R51's own losing trades (rough: about Rs 6.6 lakh against Rs 5.2 lakh, drawdown about Rs 2.0
   lakh against Rs 1.7 lakh; same fills assumed, not re-priced at 2 lots). A middle road is R51 only on BANKNIFTY and
   FINNIFTY, but that is a hindsight slice and needs a test.
2. **Liquidity (issue 4).** Switch the MIDCPNIFTY 5-minute book off (one tap), and judge Liquidity on Apr 2026 onward,
   not on its total.
3. **Account risk (issues 2, 3).** Decide the lots and how many arms run live from the combined numbers, not from each
   arm's own table; tell me the account size and I will re-run with the money actually available.
4. **Two tests worth pre-registering** (issue 6; neither is tested on today's rules): an earlier breakeven (once a trade
   is up Rs 500 a lot), and an earlier time stop for trades that never got going (R142 tested a no-progress exit on the
   old exits and it failed, so expectations are low).
