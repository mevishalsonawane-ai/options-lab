# IraThinker check (26 Oct 2026): summary for Boss

1. Secrets: clean. No tokens, keystore, topic names, Dhan keys or personal data. It never places orders.
2. Harness: 847 of 855 pass on fresh Yahoo data. The 8 failures are golden numbers tied to your original data file. After the fixes: 870 pass, same 8, 0 other failures.
3. APK builds (target SDK 36), using a stand-in icon (your PNGs aren't committed) and a newer d8. Not run on a phone.
4. Fixed (with tests):
   - Expiries now skip holidays: Nov monthly is Mon 23 Nov (24 Nov is Guru Nanak Jayanti); the Diwali-week weekly is Mon 9 Nov.
   - No ORB/fade alert more than 10 minutes after its bar.
   - No alerts on VIX, USD/INR, MIDCAP 50, SENSEX or commodities.
   - MIDCAP 50 chain fix; square-off reminder only 15:20–15:35.
5. Biggest open risk: on Android 15/16 the watchdog gets 6 hours a day (it dies about 14:30), can't start at boot, and uses no wake lock. Alerts can stop or come late. Needs a rework (listed).
6. SELL_STRANGLE refusal: correct, because IraAlgo only buys. IraAlgo should show it as "information", not an error.
7. INFY 1130 CE: the expiry (27 Oct) was right. The strike was a guess on the wrong grid. IraThinker should send only verified strikes.
8. "8 minutes old": IraAlgo read late (before build 29ba08f). IraThinker stamps each message at send time.
9. IraAlgo buys mom60 "paper" signals and ignores their 30% stop and 60-minute exit. It should honour them or skip them.
10. Strategies: none was tested in this repo's research. ORB, gap fade and SMC versions failed there; the strangle is option selling, and R103 found 0 of 24 selling tests confirmed. Full report: irathinker-app/REVIEW_2026-10-26.md.
