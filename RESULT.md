# R105 for Boss: Donchian giveback (26 Oct 2026)

1. **No change to the exits.** 10 new exits aimed at "goes +Rs 1,000-2,000, then loses" (2 from the books) were run on Breadth Donchian (R72) and R51. All 20 tests fail the pre-registered gate. Every rule that saves those trades cuts the winners by more, and none beat random exit times (best twin p 0.13; BH q 1.0 over 294 Donchian tests).
2. **Why:** +Rs 1,000 on 1 lot is a median **+6% of the premium** (about +4% on BANKNIFTY / FINNIFTY / MIDCPNIFTY, +10% on NIFTY / SENSEX), which is ordinary noise. In the final period 590 of 1,202 R72 trades reached +Rs 1,000: 472 won and 118 lost (-Rs 1,08,736), and the 590 together made **+Rs 8.30 lakh**.
3. **Your exact idea (breakeven at +Rs 1,000)** cuts the giveback from -Rs 1,08,736 to -Rs 12,039, but cuts the winners by Rs 1,46,248. Final net Rs 1,34,226 vs Rs 1,71,366; design Rs 64,794 vs Rs 2,16,929.
4. **Most losses are breaks that fail at once:** 372 of 651 losing R72 trades never got even +Rs 500 (-Rs 4.86 lakh). The giveback is 14% of the losses. 19 Oct (-Rs 18,450, 10 trades) had none.
5. **The books didn't help.** Trader Tom's breakeven-then-bar-trail came closest (+Rs 5k final, but -Rs 18.6k design). Carli / Douglas scale-out (1 of 2 lots at +15%) was the worst (-Rs 58k final).
6. **Paper R72 is normal bad luck:** -Rs 3,583 over 11 trades sits at the 22nd-31st percentile of its backtest. R51's 19 trades (-Rs 11,997) sit at the 5th-12th percentile. R51's 14 Supertrend-filtered trades (-Rs 16,188) are rare (0.5th-2nd percentile), against -Rs 5,591 for the same 14 on plain rules, so check the phone's Supertrend calculation.
7. **The app differs from the backtest:**
   - the same-direction cap (from 19 Oct) refuses most of R72's 4-5-index breaks: 11 real + 15 would-be trades, -Rs 7,896 together;
   - the daily loss stop refused the 23 Oct 13:45 breaks.
8. **Possible bugs for the app session:**
   - 4 entries missed with "fill window passed" (21 and 22 Oct);
   - stops / locks checked seconds apart while positions were open (8 times, 21-23 Oct). On 21 Oct BANKNIFTY sold Rs 216 below its +5% lock;
   - a late 12:11 entry;
   - R51 called Wed 21 Oct "NIFTY's expiry day" (NIFTY expired Tue 20 Oct).
9. **Nothing goes on paper or live from R105.** Today's exits stay (-15%, +5% once +25%, breakeven once +30%, 30/60/100, 30 minutes, 15:15). The knowledge pack, which lives in `android/**`, was not rebuilt; this was research only.
10. Details: `research/HUNT_R105_DONCHIAN_GIVEBACK.md`; tables: `research/hunt/r105_donchian_giveback/results/report.md`; 20 entries `research/experiments/entries/r105-*.yaml`. The final window was read for the ~25th time (weak evidence); only 4 unseen sessions (14-19 Oct) exist.

# R105B for Boss: the books' direction rules on the arms (26 Oct 2026)

1. **No change.** 10 untested book direction rules × 4 paper arms = 40 tests, and none passed. The rules: Trader Tom's weekly swing and weekly 89 MA, Dow HH/HL daily and hourly, Porwal's prior-day trend, the 200-day SMA, ADX > 30, the first 15-minute candle, and the gap side.
2. **The books' trend filters hurt these arms.** None was better in the walk-forward year on any arm. On Breadth Donchian's final period (1,202 trades, Rs 1,71,366 today), the 200-day SMA filter gave Rs 47,394 and the weekly swing filter Rs 66,537. These arms make money on breaks that often go against the bigger trend.
3. **Breadth (4 of 5 indices together) is already the best direction check for R72.** Every book rule added on top was worse in the final.
4. **One note to watch:** the first 15-minute candle on R51 + Supertrend was better in every period (final Rs 1,71,587 vs Rs 1,34,743, smaller drawdown) and beat random skips. It failed only the multiple-testing check (q 0.29), and it lost on R72, so it stays a note.
5. Details: `research/HUNT_R105B_DIRECTION.md`; 40 entries `research/experiments/entries/r105b-*.yaml`. Nothing goes on paper or live.

# R105C for Boss: a 60% win rate (26 Oct 2026)

1. **60% is reachable only by selling at +5%, and then the arms lose money.** R72: 61% wins but −Rs 1,18,373 in the final period (today: 46%, +Rs 1,71,366). R51: 61% but −Rs 1,38,114 (today: 45%, +Rs 1,34,743).
2. **With your 10% stop, even +5% gives only 58–59% and still loses about Rs 1.3 lakh.** The 10% stop alone keeps most of the profit (R72 Rs 1,45,098) with a smaller worst day, but at 43% wins.
3. **Only R52 Open outside value gets 60%+ with a profit:** +Rs 1,500 target, 63% wins, +Rs 31,274 (R106).
4. Details: `research/HUNT_R105C_WINRATE60.md`; 38 entries `research/experiments/entries/r105c-*.yaml`. Nothing switched on.
