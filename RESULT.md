# R130: the sweep → MSS → FVG video setup fails, with closes and wick to wick (research only; nothing in the app changed)

1. Boss's video setup (sweep a recent low, break the recent high, buy the pullback into the FVG on an engulfing candle) was tested on all 5 indices, Aug 2021 to 19 Oct 2026. The signal was read on the index; the ATM option was bought, after real charges and slippage.
2. As stated (V1, 5-min, MSS by close, body engulfing): 218 design trades, 45% won, -Rs 307 a trade, -Rs 66,966 on 1 lot. Holdout (read once): 36 trades, 39% won, -Rs 750 a trade.
3. All 6 versions lost in the walk-forward and in the holdout. None beat random entries (best BH q 0.36); none came near Boss's 65% rule.
4. Wick to wick, with no candle opens or closes anywhere (Amendment 1, added after the results at Boss's request): MSS by a wick 1 tick beyond the swing; engulfing by wicks.
5. W1 (wick-only, as stated): 485 design trades, 44% won, -Rs 26 a trade; holdout 61 trades, 43% won, -Rs 258 a trade. All 4 wick versions lose; none reaches 65%.
6. W2 (wick-only, enter on the first touch of the FVG) beat random entries in design (q 0.02) but still lost after costs (-Rs 27), then lost Rs 321 a trade in the holdout.
7. "Target before stop" holds on the index only when the target is close. Wick-only, the index result is a coin flip (W1 average win 35.9 points vs loss 36.2), and costs turn it into a loss.
8. "A sweep makes the break stronger" is false with closes (50.7% vs 52.9%) and with wicks (54.3% vs 55.2%, n 10,773).
9. Every version's drawdown on 1 lot breaks the -Rs 40k account stop. No paper arm is proposed.
10. Write-up: research/HUNT_R130_SWEEP_MSS_FVG.md. Pre-registrations: PREREG.md (66d2467) and PREREG_WICK.md (fad9c7c). Entries: research/experiments/entries/r130-*.yaml.
