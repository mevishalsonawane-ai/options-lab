# NR7 breakout on crude (R96 R3A): full design results (21 Oct 2026)

Research only. **This rule has NOT passed the gates** (it fails the multiple-testing gate: BH q 0.126 > 0.10 over 27
ideas), so under CLAUDE.md it stays a note: it is not on paper or live. The locked holdout (15 Jul - 19 Oct 2026) is
unread. Signal source: **the crude future** (MCX near-month CRUDEOIL future, minute closes); the trade buys an option.

## 1. The rule, exactly

1. **NR7 day:** today's high - low on the CRUDEOIL daily future is smaller than each of the previous 6 days' ranges.
2. **Levels:** that day's high and low of the near future (from minute prices).
3. **Entry (next session only):** the first one-minute close of the future above the high (-> buy a CALL) or below the low
   (-> buy a PUT), between 09:15 and 22:00. Buy at the next minute, at the first real option print within 15 minutes.
4. **Strike:** 1 in the money: one strike below the ATM for a call (50-point strikes), one above for a put.
5. **Exits (first one hit):**
   - **stop on the future:** the future closes back beyond the NR7 day's opposite extreme (low for a call, high for a put);
   - **premium stop:** option -40% from the buy price;
   - **profit lock ladder:** +30% peak -> lock breakeven, +60% -> lock +30%, +100% -> lock +60%, then trail 40% behind;
   - **take-profit ("edge"):** sell as soon as the held strike becomes the deepest in-the-money strike the data stores
     (ATM+/-3). This is a data limit, see s5. In practice that is two 50-point strikes, i.e. about a 1-2% move of crude in your favour;
   - **time:** square off after 3 sessions (entry day = 1), 15 minutes before the close; never into the session before
     the option expiry; no entry within 3 sessions of expiry or on expiry day.
6. One position per book at a time. Costs: Zerodha MCX option charges + half-spread 0.60% per fill before 17:00,
   0.30% from 17:00.

## 2. Headline (design 5 Aug 2025 - 14 Jul 2026, 1 lot)

| | CRUDEOIL (100 bbl) | CRUDEOILM (10 bbl) | both |
|---|---|---|---|
| trades | 23 | 23 | 46 |
| win rate | 74% | 78% | 76% |
| gross | Rs 56,918 | Rs 5,860 | Rs 62,779 |
| costs | Rs 11,748 | Rs 2,156 | Rs 13,904 |
| **net** | **Rs 45,170** | **Rs 3,705** | **Rs 48,875** |
| net at 2x spreads | Rs 35,882 | Rs 2,772 | Rs 38,655 |
| median time held | 5.8 h | 6.3 h | |
| median premium paid (1 lot) | Rs 21,970 | Rs 2,204 | |
| median stop risk (40% + costs) -> capital at 2% | Rs 9,149 -> Rs 4.6 lakh | Rs 950 -> Rs 47,500 | |

Both books trade the same signal days (CRUDEOILM is a tenth of the size), so they are not two independent tests.

Against chance: random-session copies (500 sets, same book, month, clock and stop width) averaged **-Rs 7,067**; the
rule's +Rs 48,875 beat about 99% of them (twin p 0.014). 2012 - Jul 2025 crude futures: +0.58% per trade vs +0.41% for
random sessions (329 trades, p 0.26).

## 3. By quarter (walk-forward folds) and month

| fold | trades | net |
|---|---|---|
| Aug - Oct 2025 | 14 | +Rs 12,721 |
| Nov 2025 - Jan 2026 | 10 | +Rs 4,353 |
| Feb - Apr 2026 | 10 | +Rs 13,445 |
| May - 14 Jul 2026 | 12 | +Rs 18,356 |

| month | CRUDEOIL | CRUDEOILM | total |
|---|---|---|---|
| Aug 2025 | -8,753 | -965 | **-9,718** (worst) |
| Sep 2025 | +1,172 | +8 | +1,180 |
| Oct 2025 | +19,419 | +1,841 | +21,260 |
| Nov 2025 | +4,536 | +404 | +4,940 |
| Dec 2025 | -3,626 | -403 | -4,029 |
| Jan 2026 | +3,208 | +234 | +3,442 |
| Feb 2026 | +2,353 | +199 | +2,552 |
| Mar 2026 | +6,509 | +665 | +7,174 |
| Apr 2026 | +3,462 | +257 | +3,719 |
| May 2026 | +2,448 | +105 | +2,553 |
| Jun 2026 | +12,066 | +1,205 | +13,271 |
| Jul 2026 (to 14th) | +2,376 | +156 | +2,532 |

10 of 12 months positive; max drawdown -Rs 11,854; net without the best 3 trades +Rs 23,093.

## 4. How the trades ended

| exit | CRUDEOIL: trades, net | CRUDEOILM: trades, net |
|---|---|---|
| take-profit (edge) | 17, +Rs 47,857 (avg +Rs 2,815) | 17, +Rs 4,193 |
| stop on the future | 5, -Rs 19,218 (avg -Rs 3,844) | 5, -Rs 2,085 |
| time (3 sessions) | 1, +Rs 16,532 | 1, +Rs 1,597 |

Calls: CRUDEOIL 13 trades +Rs 38,950; puts 10 trades +Rs 6,220. Entries after 17:00 were only 3 per book
(CRUDEOIL +Rs 25,654), the rest in the day session (+Rs 19,516 on 20).

**Caution: the one time exit (22 Oct 2025, CALL 5100, held into 24 Oct) is priced by the model, not a real print** (its
strike left the stored window). It is +Rs 16,532, i.e. 37% of CRUDEOIL's net. On real prints only, CRUDEOIL made
+Rs 28,639 on 22 trades (CRUDEOILM +Rs 2,108).

The premium paid changed a lot with crude's price and volatility: Rs 12,000-25,000 a lot until Feb 2026, Rs 70,000-
1,04,000 in Mar-May 2026 (the crude spike; one lot then risked up to Rs 42,930 at the -40% stop), Rs 21,000-44,000
in Jun-Jul. The 14 CRUDEOIL trades with a premium under Rs 30,000 made +Rs 20,686.

## 5. What limits this test

- The lake stores only ATM+/-3 strikes, so winners are sold when the strike reaches the edge of that window (17 of 23).
  Real winners could run further, or give it back; we can't see it. A fresh Dhan token (ATM+/-10) would fix this.
- 11.3 months, near-month options only, assumed spreads (no bid/ask data).
- Crude was picked after round 2, so the twin p is optimistic; only the locked holdout can confirm.

## 6. Every trade

Prices are option premiums per unit; net is 1 lot after all costs. "(model)" = exit priced by Black-76.

| # | book | entry | exit | side | strike | buy | sell | exit reason | net Rs |
|---|---|---|---|---|---|---|---|---|---|
| 1 | CRUDEOIL | 06 Aug 2025 12:37 | 06 Aug 21:26 | CALL | 5750 | 161.60 | 116.90 | stop on future | -4,669 |
| 2 | CRUDEOILM | 06 Aug 2025 12:38 | 06 Aug 21:25 | CALL | 5750 | 164.95 | 121.95 | stop on future | -493 |
| 3 | CRUDEOIL | 20 Aug 2025 09:16 | 21 Aug 12:09 | PUT | 5450 | 218.80 | 161.30 | stop on future | -6,053 |
| 4 | CRUDEOILM | 20 Aug 2025 09:16 | 21 Aug 11:46 | PUT | 5450 | 219.10 | 162.45 | stop on future | -639 |
| 5 | CRUDEOILM | 22 Aug 2025 10:05 | 25 Aug 15:39 | CALL | 5500 | 220.45 | 245.00 | take-profit (edge) | +167 |
| 6 | CRUDEOIL | 22 Aug 2025 10:06 | 25 Aug 15:39 | CALL | 5500 | 219.70 | 243.00 | take-profit (edge) | +1,969 |
| 7 | CRUDEOIL | 11 Sep 2025 18:27 | 12 Sep 09:01 | PUT | 5600 | 121.80 | 170.80 | take-profit (edge) | +4,690 |
| 8 | CRUDEOILM | 11 Sep 2025 18:28 | 12 Sep 09:01 | PUT | 5600 | 123.80 | 166.60 | take-profit (edge) | +365 |
| 9 | CRUDEOILM | 18 Sep 2025 10:57 | 18 Sep 17:08 | PUT | 5650 | 220.80 | 192.00 | stop on future | -357 |
| 10 | CRUDEOIL | 18 Sep 2025 11:18 | 18 Sep 17:08 | PUT | 5650 | 222.50 | 190.00 | stop on future | -3,518 |
| 11 | CRUDEOIL | 06 Oct 2025 09:16 | 08 Oct 17:42 | CALL | 5450 | 155.30 | 186.40 | take-profit (edge) | +2,887 |
| 12 | CRUDEOILM | 06 Oct 2025 09:16 | 08 Oct 17:43 | CALL | 5450 | 157.10 | 188.00 | take-profit (edge) | +244 |
| 13 | CRUDEOIL | 22 Oct 2025 17:01 | 24 Oct 23:14 | CALL | 5100 | 209.90 | 379.08 (model) | time (3 sessions) | +16,532 |
| 14 | CRUDEOILM | 22 Oct 2025 17:01 | 24 Oct 23:14 | CALL | 5100 | 213.65 | 381.45 (model) | time (3 sessions) | +1,597 |
| 15 | CRUDEOILM | 04 Nov 2025 13:28 | 05 Nov 22:14 | PUT | 5450 | 179.05 | 204.60 | take-profit (edge) | +188 |
| 16 | CRUDEOIL | 04 Nov 2025 13:32 | 05 Nov 22:14 | PUT | 5450 | 184.40 | 204.90 | take-profit (edge) | +1,801 |
| 17 | CRUDEOIL | 27 Nov 2025 10:55 | 27 Nov 17:41 | CALL | 5150 | 204.70 | 234.80 | take-profit (edge) | +2,735 |
| 18 | CRUDEOILM | 27 Nov 2025 10:57 | 27 Nov 17:12 | CALL | 5150 | 207.50 | 236.05 | take-profit (edge) | +215 |
| 19 | CRUDEOIL | 24 Dec 2025 09:16 | 26 Dec 20:49 | CALL | 5200 | 192.70 | 158.80 | stop on future | -3,626 |
| 20 | CRUDEOILM | 24 Dec 2025 09:16 | 26 Dec 20:49 | CALL | 5200 | 192.90 | 159.20 | stop on future | -403 |
| 21 | CRUDEOIL | 02 Jan 2026 09:55 | 02 Jan 11:59 | PUT | 5250 | 149.50 | 138.40 | stop on future | -1,352 |
| 22 | CRUDEOILM | 02 Jan 2026 11:42 | 02 Jan 14:21 | CALL | 5200 | 139.00 | 126.30 | stop on future | -192 |
| 23 | CRUDEOIL | 20 Jan 2026 09:32 | 20 Jan 20:51 | CALL | 5400 | 250.70 | 299.60 | take-profit (edge) | +4,560 |
| 24 | CRUDEOILM | 20 Jan 2026 09:32 | 20 Jan 20:58 | CALL | 5400 | 254.75 | 305.00 | take-profit (edge) | +426 |
| 25 | CRUDEOIL | 11 Feb 2026 12:47 | 11 Feb 17:18 | CALL | 5800 | 198.80 | 225.00 | take-profit (edge) | +2,353 |
| 26 | CRUDEOILM | 11 Feb 2026 12:47 | 11 Feb 17:18 | CALL | 5800 | 202.10 | 229.00 | take-profit (edge) | +199 |
| 27 | CRUDEOIL | 18 Mar 2026 09:17 | 18 Mar 09:22 | PUT | 8800 | 1036.80 | 1049.50 | take-profit (edge) | -188 |
| 28 | CRUDEOILM | 18 Mar 2026 09:18 | 18 Mar 09:27 | PUT | 8750 | 1011.50 | 1037.35 | take-profit (edge) | +73 |
| 29 | CRUDEOIL | 26 Mar 2026 17:01 | 26 Mar 17:19 | CALL | 8800 | 851.60 | 903.00 | take-profit (edge) | +4,432 |
| 30 | CRUDEOILM | 26 Mar 2026 17:01 | 26 Mar 17:19 | CALL | 8800 | 854.70 | 908.35 | take-profit (edge) | +423 |
| 31 | CRUDEOIL | 27 Mar 2026 12:45 | 27 Mar 13:48 | CALL | 8950 | 905.00 | 940.60 | take-profit (edge) | +2,264 |
| 32 | CRUDEOILM | 27 Mar 2026 12:51 | 27 Mar 13:10 | CALL | 8950 | 909.50 | 943.70 | take-profit (edge) | +169 |
| 33 | CRUDEOILM | 28 Apr 2026 09:16 | 28 Apr 11:04 | CALL | 9150 | 709.60 | 749.85 | take-profit (edge) | +257 |
| 34 | CRUDEOIL | 28 Apr 2026 09:18 | 28 Apr 11:04 | CALL | 9150 | 702.00 | 746.90 | take-profit (edge) | +3,462 |
| 35 | CRUDEOIL | 20 May 2026 14:22 | 20 May 16:05 | PUT | 9950 | 820.50 | 856.80 | take-profit (edge) | +2,448 |
| 36 | CRUDEOILM | 20 May 2026 14:23 | 20 May 16:05 | PUT | 9950 | 839.00 | 865.75 | take-profit (edge) | +105 |
| 37 | CRUDEOILM | 04 Jun 2026 15:16 | 04 Jun 17:09 | PUT | 9100 | 445.60 | 482.00 | take-profit (edge) | +268 |
| 38 | CRUDEOIL | 04 Jun 2026 15:17 | 04 Jun 17:09 | PUT | 9100 | 439.00 | 472.70 | take-profit (edge) | +2,848 |
| 39 | CRUDEOILM | 19 Jun 2026 09:53 | 19 Jun 11:04 | CALL | 7100 | 437.15 | 485.55 | take-profit (edge) | +374 |
| 40 | CRUDEOIL | 19 Jun 2026 10:03 | 19 Jun 11:04 | CALL | 7100 | 435.10 | 478.50 | take-profit (edge) | +3,674 |
| 41 | CRUDEOILM | 22 Jun 2026 11:57 | 22 Jun 19:09 | PUT | 7150 | 418.00 | 461.30 | take-profit (edge) | +340 |
| 42 | CRUDEOIL | 22 Jun 2026 16:37 | 22 Jun 19:09 | PUT | 7150 | 419.80 | 456.00 | take-profit (edge) | +3,117 |
| 43 | CRUDEOILM | 24 Jun 2026 09:18 | 24 Jun 14:03 | PUT | 6950 | 364.40 | 396.45 | take-profit (edge) | +222 |
| 44 | CRUDEOIL | 24 Jun 2026 09:24 | 24 Jun 14:03 | PUT | 6950 | 358.70 | 388.50 | take-profit (edge) | +2,427 |
| 45 | CRUDEOIL | 03 Jul 2026 09:16 | 07 Jul 19:34 | CALL | 6550 | 214.90 | 241.50 | take-profit (edge) | +2,376 |
| 46 | CRUDEOILM | 03 Jul 2026 09:16 | 07 Jul 19:34 | CALL | 6550 | 221.50 | 244.25 | take-profit (edge) | +156 |
