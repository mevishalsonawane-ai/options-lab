# Liquidity 15+5: why the paper record is worse than the backtest (27 Oct 2026)

Boss, 27 Oct: test why Liquidity 15+5's paper record is worse than its backtest. The R4 report (research/reports/r4/)
found the paper trades since 1 Oct at the 2nd percentile of the backtest: 37 trades, 12 won, sum -76% of premium.
This check replays the research rule on every October session and compares it, trade by trade, with what the app did.
Nothing live changed. One paper-only bug is fixed (section 4).

Signal source of every rule here: the index (liquidity-pool breaks on 15-, 30- and 5-minute index bars). The 1-ITM
option is only bought; the -15% stop, the 20-minute rule and the 15:10 exit sit on the option premium; the index stop,
target, failed-break and new-level exits read the index.

## 1. The answer

**Mostly not a bug.** On the same days, the research rule itself made only -1.6% of premium over 65 trades (bad
luck: a poor month for the rule). The paper record was 74 points worse than that. Of those 74 points:
- **61 are deliberate app rules**, above all the account-wide paper daily loss limit on 23 Oct;
- **8 are one app bug** (one trade), fixed here, paper only;
- **5 are execution, feed and the 1-16 Oct days that cannot be matched one by one.**

The 37 paper trades fall into two parts:

- **1-16 Oct: 31 paper trades, sum -12.6% of premium (11 won).** The research rule on the same sessions made 43 trades,
  sum -9.5% before costs (-33.7% after costs; 16 won). The phone kept only a counter for these days (the first
  diagnostics file is 19 Oct), so they cannot be matched one by one. In total, the paper record stayed within about 3
  points of the research. **This part is the rule's own poor fortnight (bad luck), not the app.**
- **19-26 Oct: 6 paper trades, sum -63.4%.** The research rule on the same five sessions: 22 trades, sum +7.9%. The
  gap of 71.3 points of premium splits like this:

| class | trades | app minus research, % of premium | rupees (1 lot, on the research premium) |
|---|---|---|---|
| deliberate rule: paper daily loss limit (all arms) | 8 | -65.9 (23 Oct alone: -72.4) | -Rs 7,393 |
| deliberate rule: no-twin | 1 | -29.3 | -Rs 3,197 |
| deliberate rule: same-direction cap (incl. the place kept for R72) | 6 | +34.0 (it refused 4 losers) | +Rs 3,965 |
| **app bug: paper capped-limit fill on a stale price** | 1 | **-8.1** | **-Rs 1,202** |
| data / feed: "no price to fill at" (21 Oct) | 1 | +12.4 (a -12.4% loser skipped) | +Rs 1,929 |
| data / feed: the research priced an exit on a stale print (19 Oct) | 1 | -10.4 (the backtest was too kind) | -Rs 1,524 |
| execution: same trade, live book, seconds of delay | 4 | -4.1 | -Rs 746 |
| **total** | 22 | **-71.3** | **-Rs 6,768** |

The rupees are estimates on the research trade's own premium (1 lot), not the paper P&L.

So the -76% over 37 paper trades is:
- -1.6 points: the research rule's own result on the same sessions (65 trades: -9.5 in 1-16 Oct, +7.9 in 19-26 Oct);
- -3.1 points: 1-16 Oct, paper against research, not attributable trade by trade;
- -61.2 points: deliberate rules. The single biggest item is **23 Oct**:
  - the account-wide paper daily loss limit, hit by the other arms, stopped every bot at 09:49 with no Liquidity trade
    open;
  - minutes later the research rule took its two best trades of the month: a FINNIFTY put +45.3% and a BANKNIFTY put
    +46.1%;
  - the no-twin rule would also have dropped a third, +29.3%, as a twin of the BANKNIFTY put;
  - the cap worked the other way: it refused six signals, four of them losers (+34 points);
- -8.1 points: the app bug (one trade, 26 Oct 10:10);
- -2.0 points: feed and execution together (rounding: the parts add to -76.0).

How October compares with the backtest: over all 65 research trades of 1-26 Oct the rule made -1.6% before costs
(-Rs 11,647 after costs, 1 lot: -Rs 179 a trade). In the backtest's final period it made +Rs 11,155 on 357 trades
(+Rs 31 a trade; results/backtest.json of R4). The rule's own October was poor before the app did anything.

## 2. Did the app trade the same thing as the research?

Yes, on every day that can be checked (19, 21, 22, 23 and 26 Oct):

- **Same signals, same bars.** Each of the 22 research signals has an app decision for the same book at the same bar
  close (taken, refused or day-locked). The app took no trade that the research did not have, and it showed no signal
  the research did not have. Its "no room" skips (21 and 22 Oct) were signals the research also skipped.
- **Same strikes.** Where the orders show the contract (MIDCPNIFTY 13250 PE on 22 Oct, FINNIFTY 24400 CE twice on
  26 Oct), it is the research's 1-ITM strike. On 19 Oct the fills (489.75 and 246.70) sit on the research's contracts
  (489.55 and 244.90).
- **Entries.** Five of the six fills were within 0.4-2.2% of the research fill (the second BANKNIFTY fill on 19 Oct came
  11 seconds later, in a fast minute). The sixth was the bug below.
- **Exits.** The stop, index stop and 20-minute rule fired on the same minutes as the research. The 22 Oct time stop
  sold at 164.00 at 09:40:02 against the research's 168.00 close of 09:39: the option fell 3 points over the minute
  change, plus the bid.

## 3. Every Liquidity trade that can be checked (19-26 Oct)

The research % is before costs (raw fill to raw exit), like the app's %. "app minus research" counts a missed research
trade as minus its %, so a skipped loser shows as a plus.

| # | day | book | option | signal bar closes | research: entry → exit (why), % | app | app % | app − research | class: why |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 19 Oct | BN 15m | 55500 PE | 09:30 | 489.55 at 09:30 → 416.12 at 09:32 (stop), -15.00 | took it | 489.75 → 414.6 (stop), -15.34 | -0.34 | execution: same trade; live book and seconds of delay |
| 2 | 19 Oct | BN 5m | 55500 PE | 09:30 | 489.55 at 09:30 → 416.12 at 09:32 (stop), -15.00 | took it | 500.15 → 423.7 (stop), -15.29 | -0.29 | execution: same trade; live book and seconds of delay |
| 3 | 19 Oct | FIN 5m | 25150 PE | 09:30 | 244.9 at 09:30 → 230.6 at 09:33 (missing), -5.84 | took it | 246.7 → 206.7 (index_stop), -16.21 | -10.37 | data / feed: research exit on a stale print (contract stopped trading) |
| 4 | 19 Oct | FIN 5m | 25100 PE | 11:40 | 229.95 at 11:40 → 213.6 at 11:45 (rule_exit), -7.11 | missed |  | +7.11 | deliberate rule: paper daily loss limit (all arms) had stopped the day |
| 5 | 19 Oct | BN 5m | 55500 PE | 13:25 | 464.5 at 13:25 → 465.0 at 13:44 (time), +0.11 | missed |  | -0.11 | deliberate rule: paper daily loss limit (all arms) had stopped the day |
| 6 | 19 Oct | MIDCP 5m | 13650 PE | 13:25 | 167.85 at 13:25 → 169.75 at 13:44 (time), +1.13 | missed |  | -1.13 | deliberate rule: paper daily loss limit (all arms) had stopped the day |
| 7 | 19 Oct | FIN 5m | 25000 PE | 13:45 | 222.25 at 13:45 → 220.75 at 13:50 (rule_exit), -0.67 | missed |  | +0.67 | deliberate rule: paper daily loss limit (all arms) had stopped the day |
| 8 | 21 Oct | BN 5m | 55700 CE | 09:30 | 518.45 at 09:30 → 454.15 at 09:49 (time), -12.40 | missed |  | +12.40 | data / feed: no price to fill at (feed) |
| 9 | 21 Oct | FIN 5m | 24850 PE | 11:40 | 245.25 at 11:41 → 244.65 at 11:51 (rule_exit), -0.24 | missed |  | +0.24 | deliberate rule: same-direction cap |
| 10 | 22 Oct | MIDCP 5m | 13250 PE | 09:20 | 172.3 at 09:20 → 168.0 at 09:39 (time), -2.50 | took it | 172.95 → 164.0 (time_stop), -5.17 | -2.67 | execution: same trade; live book and seconds of delay |
| 11 | 22 Oct | BN 15m | 54700 PE | 09:30 | 383.1 at 09:30 → 395.35 at 09:49 (time), +3.20 | missed |  | -3.20 | deliberate rule: same-direction cap |
| 12 | 23 Oct | FIN 5m | 24600 PE | 09:55 | 192.7 at 09:55 → 280.0 at 10:09 (rule_exit), +45.30 | missed |  | -45.30 | deliberate rule: paper daily loss limit (all arms) had stopped the day |
| 13 | 23 Oct | BN 15m | 54500 PE | 10:00 | 364.15 at 10:00 → 532.05 at 15:10 (squareoff), +46.11 | missed |  | -46.11 | deliberate rule: paper daily loss limit (all arms) had stopped the day |
| 14 | 23 Oct | BN 5m | 54500 PE | 10:00 | 364.15 at 10:00 → 470.7 at 11:35 (rule_exit), +29.26 | missed |  | -29.26 | deliberate rule: no-twin (another book held this option) |
| 15 | 23 Oct | FIN 5m | 24450 PE | 10:10 | 209.1 at 10:10 → 194.9 at 10:29 (time), -6.79 | missed |  | +6.79 | deliberate rule: paper daily loss limit (all arms) had stopped the day |
| 16 | 23 Oct | BN 5m | 54200 PE | 12:50 | 328.55 at 12:50 → 288.45 at 13:39 (rule_exit), -12.21 | missed |  | +12.21 | deliberate rule: paper daily loss limit (all arms) had stopped the day |
| 17 | 26 Oct | FIN 5m | 24250 PE | 09:25 | 159.5 at 09:25 → 135.57 at 09:29 (stop), -15.00 | missed |  | +15.00 | deliberate rule: same-direction cap |
| 18 | 26 Oct | MIDCP 5m | 12900 PE | 09:25 | 121.65 at 09:25 → 103.4 at 09:29 (stop), -15.00 | missed |  | +15.00 | deliberate rule: same-direction cap |
| 19 | 26 Oct | FIN 5m | 24400 CE | 10:00 | 223.6 at 10:00 → 236.15 at 10:02 (rule_exit), +5.61 | took it | 225.4 → 236.35 (index_stop), +4.86 | -0.75 | execution: same trade; live book and seconds of delay |
| 20 | 26 Oct | MIDCP 5m | 13025 CE | 10:00 | 125.5 at 10:00 → 130.5 at 10:19 (time), +3.98 | missed |  | -3.98 | deliberate rule: same-direction cap (place kept for R72) |
| 21 | 26 Oct | FIN 5m | 24400 CE | 10:10 | 246.65 at 10:10 → 226.5 at 10:14 (rule_exit), -8.17 | took it | 256.9 → 215.05 (stop), -16.29 | -8.12 | **app bug**: paper fill above the minute's high (capped limit filled on a stale last price) |
| 22 | 26 Oct | MIDCP 5m | 13075 CE | 13:25 | 97.15 at 13:25 → 86.55 at 13:34 (rule_exit), -10.91 | missed |  | +10.91 | deliberate rule: same-direction cap |

Not in the table:
- 1-16 Oct (31 paper trades): per-trade rows were not uploaded (diagnostics start on 19 Oct; there is no earlier
  diagnostics release; the backups and recordings are sealed with Boss's passphrase). Only the totals above can be
  compared.
- 20 Oct was a holiday (Dussehra); 24-25 Oct a weekend. 27 Oct is the October expiry, so Liquidity skips it (research
  and app). Its diagnostics file had not arrived when this ran.

## 4. The bug, fixed here (paper only)

**26 Oct 10:10, FINNIFTY 24400 CE, Liquidity 5m FINNIFTY.** The paper buy filled at 256.90. The exchange's 10:10
minute traded only 237.80-246.65 (open 246.65); the 10:09 minute's high was 255.35. The spread check had logged a
0.75% half-spread.

Why: since 26 Oct (h14) a paper entry is a LIMIT at a cap. The paper broker (`Sandbox.kt`) decided whether a LIMIT was
marketable from the last traded price. It then filled at that last price plus the half-spread, held to the limit,
even when the quote had a real ask. The last price on the quote was about 254.95, a 10:09 print. 254.95 x 1.0075 =
256.86, rounded up to the tick, is 256.90. A MARKET order with the same quote would have paid the ask (about 248.50).

What it cost: the -15% stop sat at 218.35 instead of about 211.25. The 10:13 low of 211.65 hit the first and not the
second. The trade lost 16.29% instead of about 8-9% (the research: -8.17%, out on the index rule at 10:14).

The fix (`android/engine/src/main/kotlin/com/optionslab/engine/sandbox/Sandbox.kt`, honest-paper mode only):
- a paper BUY LIMIT that finds a real book is marketable only when the ask is inside the limit;
- it then fills at that ask, like a MARKET order. With no usable book it keeps the old rule;
- sales keep the old rule. A paper LIMIT sale can be an exit or a Hero target, and the reviewer found that judging it on
  the bid could hold back a later exit check, so a wide bid must never delay an exit. Every paper BUY LIMIT in the app
  is an entry (Liquidity, Hero, Hero ticket, ntfy, Ft3), and an unfilled one is cancelled.
- side effect: ntfy's paper buy limit is the last price + 1%. When the ask sits above that (a very wide book), the
  entry now waits and is retried, instead of filling above the ask. That is what the exchange would do.

Live orders do not use this code. Nothing on the order or tick path reads disk or the network. Tests:
`PaperSpreadTest`, four new cases, including this trade's numbers.

## 5. Recommended next steps (not done here; each is Boss's call)

1. **The account-wide paper daily loss limit** (Rs 6,000 to 25 Oct, Rs 10,000 since) stopped Liquidity on 19, 23 and
   26 Oct. On 23 Oct Liquidity had no trade open, and the stop removed its best day.
   - Paper costs nothing, so a per-arm paper loss limit (or none for the passed arms on paper) would keep each arm's
     record clean for the live decision.
   - This is a rule change, so it is listed and not done.
   - Keep the plain-rules record and the record after changes apart (CLAUDE.md).
2. **The same-direction cap** helped Liquidity in October (+34 points over 6 refusals, of which 4 were losers). R108's
   backtest found the cap costs the book a lot over months (CAP2 Rs 15,955 against NOCAP Rs 1,38,560). Six refusals are
   too few to judge; keep watching.
3. **No-twin** cost +29.3% on 23 Oct. R4's backtest found it helps over months (337 trades, +Rs 13,110, against 357
   trades, +Rs 11,155). One twin decides nothing.
4. **The cap's price** comes from the stream's last tick of any age (`Paper.bookNow`). A quote counts as fresh by when
   the packet arrived, not by the last trade's time (`HotPath`). With today's fix the fill no longer uses that price,
   but the cap itself still can. A check on `lastTradeTime` is worth adding.
5. **The unused fill-window rule.** `LiquidityRules.window` (`FILL_SLACK_MINUTES`) is defined but nothing calls it. An
   entry delayed by a bar held back for feed lag, or by the cap's 3-minute wait, is still taken. The research always
   buys the next minute's open.
6. **The research engine is too kind on illiquid exits.** When the option has no trade in the exit minute, R51's engine
   uses the next print within 3 minutes, else the last known price. On 19 Oct the FINNIFTY 25150 PE stopped trading
   at 09:31 (230.60) while the index rallied. Its next trade was 171.70 at 09:37. The research booked 230.60; the app
   sold at 206.70.
   - 5 of the 65 October research trades ended this way (+20 points in total).
   - A research fix (price such an exit at the next real trade) would make every Liquidity backtest a little lower and
     more honest.
7. **Stops in paper** fill at min(trigger, first price) and then pay 10 bps or the half-spread more. The research pays
   0.1% and twice the half-spread on its stop fill. That is close enough; no change suggested.

## 6. How this was done, and its limits

- **Data.** The research lake (Dhan) ends 19 Oct and the Dhan token has expired. The Drive copy could not be reached from
  this session. So every session from 1 to 26 Oct was replayed on Upstox's public 1-minute candles (no login, no
  secret): the three indices from 14 Sep (for the 10-day levels), and every 27 Oct contract near the money.
  - Upstox fills a minute with no trade with a flat zero-volume candle; those minutes are dropped, as the lake leaves
    them empty.
  - The replay was not cross-checked against the lake for 1-13 Oct (no lake access here). R51's stray-print cleaning
    is not applied.
- **Research rule.** The research's own code, unchanged:
  - signals: `hunt/r51/fam_liq.py` (H4's port of `LiquidityRules.kt`);
  - pricing: R51's engine (next-minute open, R17 slippage, stops on the minute low, dated charges);
  - one position per book: R101's walk;
  - books: BANKNIFTY 15+5 (index stop 30), FINNIFTY 30+5 (15), MIDCPNIFTY 15+5 (8).
- **App.** The phone's redacted diagnostics (release `diag-2026-10` of the private data repo, 19-26 Oct; read only,
  only parsed). Per-trade rows give entry, exit, reason and % of premium; orders give the contract; decisions give
  every refusal with its reason.
- **Code.** `research/reports/liqpaper/`:
  - `lpdata.py` fetches and stores the candles (outside git);
  - `lpreplay.py` replays the rule;
  - `lpcompare.py` matches and classifies, writing paper results outside the repository;
  - `tests/` has 13 tests, including a look-ahead guard: a signal does not change when later minutes are scrambled.
- No new rule is tested, nothing is tuned and no holdout is read. This check only describes October against rules
  fixed before it.

To rebuild:

```
python research/reports/liqpaper/lpdata.py fetch
python research/reports/liqpaper/lpreplay.py /tmp/research.json
python -I research/reports/liqpaper/lpcompare.py <folder with diag-*.json.gz> /tmp/research.json /tmp/compare.json
python -m pytest research/reports/liqpaper/tests -q
```
