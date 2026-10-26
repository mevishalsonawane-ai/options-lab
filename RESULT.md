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
