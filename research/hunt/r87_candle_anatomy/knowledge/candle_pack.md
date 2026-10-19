# Candle knowledge pack (R87)

*For Jarvis and the agents to quote. Every number: n = candles, effect in ATR over the next 5 candles vs matched candles (same instrument, timeframe, time of day, trend, volatility regime, colour), q = BH. The 'reason' is the textbook hypothesis; the numbers say whether the follow-through happens. Signal source: the index candle. Source: research/HUNT_R87_CANDLE_ANATOMY.md.*

## Doji

- What: body <= 10% of the range: open and close almost equal.
- Reason (hypothesis): buyers and sellers ended even inside the candle: a pause or indecision, not a direction.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 7,077; up after 5 candles 52% vs 52% matched; effect -0.005 ATR (-0.2 pts raw), q 1.0; low broken within 10: 80% vs 78%; high broken: 82% vs 79%
- NIFTY 15m: n 2,130; up after 5 candles 52% vs 52% matched; effect +0.007 ATR (+0.4 pts raw), q 1.0; low broken within 10: 76% vs 74%; high broken: 79% vs 75%
- BANKNIFTY 5m: n 7,592; up after 5 candles 50% vs 50% matched; effect -0.002 ATR (+0.3 pts raw), q 1.0; low broken within 10: 82% vs 78%; high broken: 82% vs 78%
- BANKNIFTY 15m: n 2,269; up after 5 candles 49% vs 51% matched; effect -0.021 ATR (-3.8 pts raw), q 0.9004; low broken within 10: 78% vs 75%; high broken: 78% vs 75%
- SENSEX 5m: n 6,717; up after 5 candles 51% vs 51% matched; effect +0.003 ATR (-0.5 pts raw), q 1.0; low broken within 10: 81% vs 77%; high broken: 83% vs 79%
- SENSEX 15m: n 2,141; up after 5 candles 49% vs 51% matched; effect -0.010 ATR (-2.7 pts raw), q 1.0; low broken within 10: 77% vs 74%; high broken: 79% vs 76%
- NIFTY 15m wick levels (design): low broken within 10 candles 76% vs 76% for any candle whose low is as far from the close; high 79% vs 79%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 78% vs 78% for any candle whose low is as far from the close; high 78% vs 78%
- SENSEX 15m wick levels (design): low broken within 10 candles 77% vs 77% for any candle whose low is as far from the close; high 79% vs 79%

## Dragonfly doji

- What: doji with a lower wick >= 60% and almost no upper wick.
- Reason (hypothesis): sellers pushed down, buyers bought it all back to the open: demand below (a rejection of lower prices).
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 724; up after 5 candles 52% vs 51% matched; effect +0.047 ATR (+0.8 pts raw), q 0.8142; low broken within 10: 62% vs 78%; high broken: 94% vs 78%
- NIFTY 15m: n 211; up after 5 candles 50% vs 52% matched; effect -0.102 ATR (-3.3 pts raw), q 0.8125; low broken within 10: 62% vs 73%; high broken: 93% vs 75%
- BANKNIFTY 5m: n 733; up after 5 candles 47% vs 50% matched; effect -0.018 ATR (+0.9 pts raw), q 1.0; low broken within 10: 69% vs 78%; high broken: 94% vs 78%
- BANKNIFTY 15m: n 201; up after 5 candles 47% vs 50% matched; effect -0.059 ATR (-13.3 pts raw), q 0.9022; low broken within 10: 63% vs 74%; high broken: 91% vs 74%
- SENSEX 5m: n 779; up after 5 candles 48% vs 51% matched; effect -0.052 ATR (-4.8 pts raw), q 0.8019; low broken within 10: 65% vs 77%; high broken: 94% vs 80%
- SENSEX 15m: n 233; up after 5 candles 50% vs 51% matched; effect +0.040 ATR (+2.8 pts raw), q 0.9876; low broken within 10: 62% vs 75%; high broken: 93% vs 75%
- NIFTY 15m wick levels (design): low broken within 10 candles 62% vs 58% for any candle whose low is as far from the close; high 93% vs 94%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 63% vs 60% for any candle whose low is as far from the close; high 91% vs 94%
- SENSEX 15m wick levels (design): low broken within 10 candles 62% vs 59% for any candle whose low is as far from the close; high 93% vs 94%

## Gravestone doji

- What: doji with an upper wick >= 60% and almost no lower wick.
- Reason (hypothesis): buyers pushed up, sellers sold it all back to the open: supply above.
- Verdict: 2 instrument/timeframe finding(s), more down than matched candles; 0 held out of sample.
- NIFTY 5m: n 654; up after 5 candles 52% vs 52% matched; effect -0.018 ATR (+0.5 pts raw), q 1.0; low broken within 10: 94% vs 77%; high broken: 69% vs 79%
- NIFTY 15m: n 175; up after 5 candles 52% vs 51% matched; effect -0.068 ATR (-1.8 pts raw), q 0.9312; low broken within 10: 92% vs 74%; high broken: 63% vs 75%
- BANKNIFTY 5m: n 647; up after 5 candles 55% vs 50% matched; effect +0.100 ATR (+6.7 pts raw), q 0.5027; low broken within 10: 94% vs 78%; high broken: 70% vs 78%
- BANKNIFTY 15m: n 170; up after 5 candles 49% vs 50% matched; effect -0.089 ATR (-12.4 pts raw), q 0.8212; low broken within 10: 98% vs 77%; high broken: 62% vs 76%
- SENSEX 5m: n 709; up after 5 candles 56% vs 51% matched; effect +0.092 ATR (+5.7 pts raw), q 0.6194; low broken within 10: 96% vs 77%; high broken: 72% vs 80%
- SENSEX 15m: n 205; up after 5 candles 43% vs 51% matched; effect -0.295 ATR (-39.5 pts raw), q 0.0933; low broken within 10: 97% vs 74%; high broken: 58% vs 75% — FINDING, did NOT hold out of sample
- NIFTY 15m wick levels (design): low broken within 10 candles 92% vs 94% for any candle whose low is as far from the close; high 63% vs 64%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 98% vs 94% for any candle whose low is as far from the close; high 62% vs 62%
- SENSEX 15m wick levels (design): low broken within 10 candles 97% vs 94% for any candle whose low is as far from the close; high 58% vs 64%

## Long-legged doji

- What: doji with both wicks >= 30% and range >= 1 ATR.
- Reason (hypothesis): a wide two-way fight that ended even: high uncertainty, often around news or a level.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 626; up after 5 candles 49% vs 51% matched; effect -0.125 ATR (-2.5 pts raw), q 0.5027; low broken within 10: 75% vs 77%; high broken: 70% vs 77%
- NIFTY 15m: n 164; up after 5 candles 53% vs 51% matched; effect +0.225 ATR (+6.1 pts raw), q 0.4051; low broken within 10: 65% vs 71%; high broken: 66% vs 70%
- BANKNIFTY 5m: n 701; up after 5 candles 49% vs 50% matched; effect -0.049 ATR (-2.6 pts raw), q 0.8467; low broken within 10: 74% vs 77%; high broken: 74% vs 77%
- BANKNIFTY 15m: n 192; up after 5 candles 53% vs 50% matched; effect -0.131 ATR (-15.7 pts raw), q 0.7859; low broken within 10: 72% vs 70%; high broken: 63% vs 72%
- SENSEX 5m: n 543; up after 5 candles 48% vs 51% matched; effect -0.064 ATR (-2.9 pts raw), q 0.8347; low broken within 10: 77% vs 76%; high broken: 72% vs 77%
- SENSEX 15m: n 157; up after 5 candles 52% vs 50% matched; effect +0.178 ATR (+8.0 pts raw), q 0.7296; low broken within 10: 65% vs 69%; high broken: 64% vs 70%

## Spinning top

- What: small body (10-35%) with both wicks >= 25%.
- Reason (hypothesis): neither side in control; momentum fading.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 7,349; up after 5 candles 50% vs 52% matched; effect -0.004 ATR (-0.4 pts raw), q 1.0; low broken within 10: 81% vs 78%; high broken: 82% vs 79%
- NIFTY 15m: n 2,278; up after 5 candles 53% vs 52% matched; effect +0.043 ATR (+1.3 pts raw), q 0.7296; low broken within 10: 76% vs 74%; high broken: 79% vs 76%
- BANKNIFTY 5m: n 8,242; up after 5 candles 50% vs 51% matched; effect -0.032 ATR (-1.8 pts raw), q 0.5161; low broken within 10: 81% vs 78%; high broken: 80% vs 78%
- BANKNIFTY 15m: n 2,555; up after 5 candles 51% vs 50% matched; effect -0.009 ATR (-2.0 pts raw), q 1.0; low broken within 10: 78% vs 74%; high broken: 78% vs 75%
- SENSEX 5m: n 6,505; up after 5 candles 52% vs 51% matched; effect +0.018 ATR (+0.1 pts raw), q 0.8347; low broken within 10: 80% vs 78%; high broken: 81% vs 79%
- SENSEX 15m: n 2,069; up after 5 candles 53% vs 52% matched; effect +0.022 ATR (+2.8 pts raw), q 0.9022; low broken within 10: 77% vs 74%; high broken: 77% vs 76%

## Green marubozu

- What: body >= 85% of the range, range >= 0.8 ATR, green.
- Reason (hypothesis): buyers in control from open to close with no pullback: urgency / aggressive buying.
- Verdict: 2 instrument/timeframe finding(s), more up than matched candles; 1 held out of sample.
- NIFTY 5m: n 2,919; up after 5 candles 52% vs 51% matched; effect +0.064 ATR (+0.8 pts raw), q 0.3845; low broken within 10: 46% vs 66%; high broken: 97% vs 89%
- NIFTY 15m: n 674; up after 5 candles 54% vs 52% matched; effect +0.082 ATR (+3.4 pts raw), q 0.727; low broken within 10: 38% vs 59%; high broken: 95% vs 86%
- BANKNIFTY 5m: n 2,420; up after 5 candles 50% vs 50% matched; effect +0.077 ATR (+3.4 pts raw), q 0.2752; low broken within 10: 44% vs 67%; high broken: 96% vs 88%
- BANKNIFTY 15m: n 585; up after 5 candles 52% vs 50% matched; effect -0.025 ATR (-2.2 pts raw), q 0.9876; low broken within 10: 38% vs 61%; high broken: 95% vs 84%
- SENSEX 5m: n 3,498; up after 5 candles 52% vs 51% matched; effect +0.046 ATR (+2.6 pts raw), q 0.6194; low broken within 10: 46% vs 67%; high broken: 97% vs 89%
- SENSEX 15m: n 798; up after 5 candles 52% vs 51% matched; effect +0.029 ATR (+4.0 pts raw), q 0.9419; low broken within 10: 40% vs 61%; high broken: 96% vs 86%
- NIFTY 15m wick levels (design): low broken within 10 candles 38% vs 42% for any candle whose low is as far from the close; high 95% vs 95%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 38% vs 40% for any candle whose low is as far from the close; high 95% vs 94%
- SENSEX 15m wick levels (design): low broken within 10 candles 40% vs 43% for any candle whose low is as far from the close; high 96% vs 95%

## Red marubozu

- What: body >= 85%, range >= 0.8 ATR, red.
- Reason (hypothesis): sellers in control from open to close: urgency / aggressive selling.
- Verdict: 2 instrument/timeframe finding(s), more down than matched candles; 0 held out of sample.
- NIFTY 5m: n 2,833; up after 5 candles 51% vs 52% matched; effect -0.019 ATR (-0.3 pts raw), q 0.9406; low broken within 10: 96% vs 88%; high broken: 47% vs 67%
- NIFTY 15m: n 683; up after 5 candles 52% vs 51% matched; effect +0.084 ATR (+1.8 pts raw), q 0.7296; low broken within 10: 95% vs 84%; high broken: 37% vs 60%
- BANKNIFTY 5m: n 2,307; up after 5 candles 53% vs 52% matched; effect +0.016 ATR (+1.4 pts raw), q 0.9494; low broken within 10: 96% vs 88%; high broken: 46% vs 68%
- BANKNIFTY 15m: n 549; up after 5 candles 51% vs 51% matched; effect -0.037 ATR (-5.8 pts raw), q 0.9273; low broken within 10: 96% vs 84%; high broken: 38% vs 62%
- SENSEX 5m: n 3,405; up after 5 candles 51% vs 52% matched; effect -0.045 ATR (-2.5 pts raw), q 0.7296; low broken within 10: 96% vs 88%; high broken: 48% vs 68%
- SENSEX 15m: n 736; up after 5 candles 49% vs 51% matched; effect -0.084 ATR (-15.2 pts raw), q 0.7296; low broken within 10: 95% vs 84%; high broken: 38% vs 61%
- NIFTY 15m wick levels (design): low broken within 10 candles 95% vs 94% for any candle whose low is as far from the close; high 37% vs 39%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 96% vs 95% for any candle whose low is as far from the close; high 38% vs 39%
- SENSEX 15m wick levels (design): low broken within 10 candles 95% vs 94% for any candle whose low is as far from the close; high 38% vs 40%

## Big green body

- What: green body >= 1.2 ATR.
- Reason (hypothesis): a strong push up; often a breakout or news.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 2,660; up after 5 candles 52% vs 51% matched; effect +0.083 ATR (+1.3 pts raw), q 0.3326; low broken within 10: 37% vs 65%; high broken: 92% vs 88%
- NIFTY 15m: n 647; up after 5 candles 52% vs 52% matched; effect -0.002 ATR (-1.4 pts raw), q 1.0; low broken within 10: 30% vs 56%; high broken: 90% vs 84%
- BANKNIFTY 5m: n 2,528; up after 5 candles 49% vs 49% matched; effect +0.071 ATR (+2.6 pts raw), q 0.5027; low broken within 10: 37% vs 66%; high broken: 90% vs 87%
- BANKNIFTY 15m: n 732; up after 5 candles 52% vs 50% matched; effect +0.013 ATR (-1.6 pts raw), q 1.0; low broken within 10: 31% vs 58%; high broken: 88% vs 83%
- SENSEX 5m: n 2,907; up after 5 candles 52% vs 50% matched; effect +0.078 ATR (+4.2 pts raw), q 0.3408; low broken within 10: 37% vs 66%; high broken: 92% vs 88%
- SENSEX 15m: n 717; up after 5 candles 52% vs 51% matched; effect +0.026 ATR (-2.5 pts raw), q 1.0; low broken within 10: 29% vs 57%; high broken: 90% vs 84%

## Big red body

- What: red body >= 1.2 ATR.
- Reason (hypothesis): a strong push down; often a breakdown or news.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 3,245; up after 5 candles 52% vs 51% matched; effect -0.031 ATR (-0.4 pts raw), q 0.8467; low broken within 10: 91% vs 88%; high broken: 39% vs 65%
- NIFTY 15m: n 925; up after 5 candles 50% vs 50% matched; effect -0.001 ATR (-2.4 pts raw), q 1.0; low broken within 10: 88% vs 83%; high broken: 30% vs 57%
- BANKNIFTY 5m: n 2,700; up after 5 candles 52% vs 51% matched; effect -0.034 ATR (-1.8 pts raw), q 0.8347; low broken within 10: 90% vs 88%; high broken: 38% vs 66%
- BANKNIFTY 15m: n 806; up after 5 candles 49% vs 50% matched; effect -0.089 ATR (-16.1 pts raw), q 0.7296; low broken within 10: 87% vs 83%; high broken: 30% vs 58%
- SENSEX 5m: n 3,372; up after 5 candles 51% vs 51% matched; effect -0.050 ATR (-1.9 pts raw), q 0.7558; low broken within 10: 91% vs 88%; high broken: 39% vs 66%
- SENSEX 15m: n 949; up after 5 candles 49% vs 50% matched; effect -0.053 ATR (-12.0 pts raw), q 0.8467; low broken within 10: 88% vs 83%; high broken: 30% vs 58%

## Hammer

- What: lower wick >= 60%, body 10-35%, upper wick <= 15%, after a down trend.
- Reason (hypothesis): sellers pushed lower but buyers absorbed it and closed near the top: a possible bottom (stop run then reclaim).
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 1,103; up after 5 candles 49% vs 50% matched; effect +0.006 ATR (-0.9 pts raw), q 1.0; low broken within 10: 65% vs 78%; high broken: 90% vs 76%
- NIFTY 15m: n 301; up after 5 candles 48% vs 49% matched; effect -0.071 ATR (-4.6 pts raw), q 0.8467; low broken within 10: 65% vs 77%; high broken: 88% vs 74%
- BANKNIFTY 5m: n 1,077; up after 5 candles 52% vs 51% matched; effect +0.065 ATR (+3.2 pts raw), q 0.7161; low broken within 10: 66% vs 79%; high broken: 92% vs 78%
- BANKNIFTY 15m: n 266; up after 5 candles 50% vs 49% matched; effect -0.029 ATR (-2.9 pts raw), q 1.0; low broken within 10: 61% vs 76%; high broken: 91% vs 74%
- SENSEX 5m: n 1,209; up after 5 candles 48% vs 50% matched; effect -0.080 ATR (-7.5 pts raw), q 0.4737; low broken within 10: 69% vs 78%; high broken: 90% vs 77%
- SENSEX 15m: n 325; up after 5 candles 48% vs 50% matched; effect -0.093 ATR (-17.1 pts raw), q 0.7869; low broken within 10: 64% vs 76%; high broken: 87% vs 73%
- NIFTY 15m wick levels (design): low broken within 10 candles 65% vs 62% for any candle whose low is as far from the close; high 88% vs 90%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 61% vs 63% for any candle whose low is as far from the close; high 91% vs 90%
- SENSEX 15m wick levels (design): low broken within 10 candles 64% vs 63% for any candle whose low is as far from the close; high 87% vs 90%

## Hanging man

- What: the hammer shape after an UP trend.
- Reason (hypothesis): the same shape at a top: selling appeared inside the candle; the textbook calls it a warning.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 1,446; up after 5 candles 53% vs 53% matched; effect -0.018 ATR (+0.5 pts raw), q 0.9722; low broken within 10: 65% vs 76%; high broken: 92% vs 81%
- NIFTY 15m: n 468; up after 5 candles 52% vs 54% matched; effect +0.018 ATR (+0.3 pts raw), q 1.0; low broken within 10: 63% vs 73%; high broken: 90% vs 78%
- BANKNIFTY 5m: n 1,356; up after 5 candles 52% vs 50% matched; effect -0.010 ATR (-0.8 pts raw), q 1.0; low broken within 10: 68% vs 78%; high broken: 93% vs 80%
- BANKNIFTY 15m: n 439; up after 5 candles 49% vs 52% matched; effect -0.076 ATR (-2.7 pts raw), q 0.7633; low broken within 10: 64% vs 75%; high broken: 89% vs 76%
- SENSEX 5m: n 1,524; up after 5 candles 51% vs 52% matched; effect -0.034 ATR (+0.3 pts raw), q 0.8467; low broken within 10: 68% vs 77%; high broken: 91% vs 81%
- SENSEX 15m: n 467; up after 5 candles 49% vs 53% matched; effect -0.148 ATR (-17.6 pts raw), q 0.3326; low broken within 10: 65% vs 74%; high broken: 92% vs 79%

## Hammer shape, no trend

- What: the hammer shape with no clear trend before.
- Reason (hypothesis): a rejected dip in a range.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 313; up after 5 candles 50% vs 51% matched; effect +0.017 ATR (-2.3 pts raw), q 1.0; low broken within 10: 66% vs 76%; high broken: 93% vs 76%
- NIFTY 15m: n 96; up after 5 candles 45% vs 50% matched; effect -0.178 ATR (-9.2 pts raw), q 0.7296; low broken within 10: 66% vs 73%; high broken: 86% vs 70%
- BANKNIFTY 5m: n 316; up after 5 candles 48% vs 50% matched; effect -0.092 ATR (-3.2 pts raw), q 0.7613; low broken within 10: 66% vs 77%; high broken: 89% vs 76%
- BANKNIFTY 15m: n 99; up after 5 candles 55% vs 48% matched; effect -0.063 ATR (-5.0 pts raw), q 0.9273; low broken within 10: 55% vs 72%; high broken: 92% vs 72%
- SENSEX 5m: n 358; up after 5 candles 48% vs 50% matched; effect -0.030 ATR (-5.6 pts raw), q 0.9876; low broken within 10: 67% vs 77%; high broken: 91% vs 76%
- SENSEX 15m: n 89; up after 5 candles 44% vs 51% matched; effect -0.163 ATR (-40.6 pts raw), q 0.8111; low broken within 10: 62% vs 74%; high broken: 88% vs 68%

## Inverted hammer

- What: upper wick >= 60%, body 10-35%, lower wick <= 15%, after a down trend.
- Reason (hypothesis): buyers tried to lift price after a fall; the textbook calls it an early bottom attempt.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 1,030; up after 5 candles 50% vs 50% matched; effect +0.003 ATR (-1.0 pts raw), q 1.0; low broken within 10: 93% vs 79%; high broken: 67% vs 76%
- NIFTY 15m: n 262; up after 5 candles 48% vs 49% matched; effect -0.009 ATR (-1.7 pts raw), q 1.0; low broken within 10: 90% vs 75%; high broken: 63% vs 74%
- BANKNIFTY 5m: n 1,045; up after 5 candles 54% vs 51% matched; effect +0.101 ATR (+6.1 pts raw), q 0.3278; low broken within 10: 92% vs 79%; high broken: 70% vs 78%
- BANKNIFTY 15m: n 270; up after 5 candles 51% vs 50% matched; effect +0.048 ATR (+5.2 pts raw), q 0.9477; low broken within 10: 90% vs 74%; high broken: 61% vs 74%
- SENSEX 5m: n 1,124; up after 5 candles 51% vs 50% matched; effect +0.025 ATR (+1.2 pts raw), q 0.9494; low broken within 10: 92% vs 79%; high broken: 69% vs 78%
- SENSEX 15m: n 290; up after 5 candles 53% vs 50% matched; effect +0.144 ATR (+18.6 pts raw), q 0.7161; low broken within 10: 88% vs 75%; high broken: 66% vs 75%

## Shooting star

- What: the inverted-hammer shape after an UP trend.
- Reason (hypothesis): buyers pushed up, sellers rejected it and closed near the low: a possible top.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 1,290; up after 5 candles 56% vs 53% matched; effect +0.077 ATR (+1.8 pts raw), q 0.5027; low broken within 10: 92% vs 76%; high broken: 73% vs 82%
- NIFTY 15m: n 325; up after 5 candles 54% vs 54% matched; effect +0.009 ATR (+1.4 pts raw), q 1.0; low broken within 10: 91% vs 73%; high broken: 67% vs 77%
- BANKNIFTY 5m: n 1,123; up after 5 candles 50% vs 50% matched; effect -0.004 ATR (-1.2 pts raw), q 1.0; low broken within 10: 93% vs 78%; high broken: 67% vs 80%
- BANKNIFTY 15m: n 337; up after 5 candles 58% vs 51% matched; effect +0.161 ATR (+17.1 pts raw), q 0.2655; low broken within 10: 89% vs 75%; high broken: 69% vs 76%
- SENSEX 5m: n 1,336; up after 5 candles 56% vs 52% matched; effect +0.039 ATR (+2.5 pts raw), q 0.8212; low broken within 10: 91% vs 77%; high broken: 73% vs 81%
- SENSEX 15m: n 354; up after 5 candles 52% vs 53% matched; effect -0.013 ATR (-1.2 pts raw), q 1.0; low broken within 10: 92% vs 73%; high broken: 67% vs 77%
- NIFTY 15m wick levels (design): low broken within 10 candles 91% vs 91% for any candle whose low is as far from the close; high 67% vs 66%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 89% vs 91% for any candle whose low is as far from the close; high 69% vs 64%
- SENSEX 15m wick levels (design): low broken within 10 candles 92% vs 90% for any candle whose low is as far from the close; high 67% vs 66%

## Inverted shape, no trend

- What: the inverted-hammer shape with no trend.
- Reason (hypothesis): a rejected rally in a range.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 282; up after 5 candles 52% vs 51% matched; effect +0.100 ATR (+1.5 pts raw), q 0.7859; low broken within 10: 92% vs 75%; high broken: 65% vs 78%
- NIFTY 15m: n 65; up after 5 candles 62% vs 52% matched; effect +0.230 ATR (+10.6 pts raw), q 0.7296; low broken within 10: 88% vs 71%; high broken: 61% vs 74%
- BANKNIFTY 5m: n 282; up after 5 candles 52% vs 50% matched; effect -0.063 ATR (-2.6 pts raw), q 0.8964; low broken within 10: 94% vs 78%; high broken: 64% vs 76%
- BANKNIFTY 15m: n 74; up after 5 candles 49% vs 48% matched; effect -0.077 ATR (-16.8 pts raw), q 0.9876; low broken within 10: 91% vs 72%; high broken: 53% vs 70%
- SENSEX 5m: n 286; up after 5 candles 54% vs 51% matched; effect +0.132 ATR (+2.5 pts raw), q 0.7296; low broken within 10: 91% vs 75%; high broken: 68% vs 78%
- SENSEX 15m: n 61; up after 5 candles 54% vs 50% matched; effect +0.158 ATR (+24.3 pts raw), q 0.8347; low broken within 10: 86% vs 72%; high broken: 67% vs 73%

## Big head (big upper wick)

- What: upper wick >= 50% of a candle >= 1 ATR.
- Reason (hypothesis): price went up and was sold back: supply / profit taking / a failed breakout above.
- Verdict: 1 instrument/timeframe finding(s), more down than matched candles; 0 held out of sample.
- NIFTY 5m: n 2,421; up after 5 candles 51% vs 51% matched; effect +0.018 ATR (+0.0 pts raw), q 0.9494; low broken within 10: 85% vs 77%; high broken: 62% vs 77%
- NIFTY 15m: n 592; up after 5 candles 53% vs 51% matched; effect +0.071 ATR (+2.3 pts raw), q 0.8125; low broken within 10: 80% vs 72%; high broken: 52% vs 71%
- BANKNIFTY 5m: n 2,720; up after 5 candles 53% vs 50% matched; effect +0.079 ATR (+4.3 pts raw), q 0.2752; low broken within 10: 83% vs 77%; high broken: 61% vs 78%
- BANKNIFTY 15m: n 683; up after 5 candles 52% vs 50% matched; effect +0.038 ATR (+0.2 pts raw), q 0.9416; low broken within 10: 81% vs 72%; high broken: 51% vs 71%
- SENSEX 5m: n 2,292; up after 5 candles 51% vs 51% matched; effect +0.001 ATR (+0.6 pts raw), q 1.0; low broken within 10: 85% vs 77%; high broken: 62% vs 77%
- SENSEX 15m: n 578; up after 5 candles 50% vs 51% matched; effect +0.002 ATR (-2.0 pts raw), q 1.0; low broken within 10: 81% vs 71%; high broken: 52% vs 71%
- NIFTY 15m wick levels (design): low broken within 10 candles 80% vs 80% for any candle whose low is as far from the close; high 52% vs 51%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 81% vs 79% for any candle whose low is as far from the close; high 51% vs 51%
- SENSEX 15m wick levels (design): low broken within 10 candles 81% vs 80% for any candle whose low is as far from the close; high 52% vs 52%

## Big tail (big lower wick)

- What: lower wick >= 50% of a candle >= 1 ATR.
- Reason (hypothesis): price went down and was bought back: demand / short covering / a failed breakdown below.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 3,153; up after 5 candles 51% vs 51% matched; effect -0.045 ATR (-1.3 pts raw), q 0.7306; low broken within 10: 59% vs 77%; high broken: 85% vs 78%
- NIFTY 15m: n 949; up after 5 candles 51% vs 52% matched; effect -0.029 ATR (-2.7 pts raw), q 0.9494; low broken within 10: 55% vs 72%; high broken: 79% vs 71%
- BANKNIFTY 5m: n 3,204; up after 5 candles 49% vs 50% matched; effect -0.073 ATR (-4.7 pts raw), q 0.2273; low broken within 10: 62% vs 78%; high broken: 83% vs 77%
- BANKNIFTY 15m: n 887; up after 5 candles 50% vs 50% matched; effect -0.013 ATR (-6.6 pts raw), q 1.0; low broken within 10: 53% vs 72%; high broken: 76% vs 71%
- SENSEX 5m: n 2,991; up after 5 candles 50% vs 51% matched; effect -0.068 ATR (-5.2 pts raw), q 0.4051; low broken within 10: 61% vs 76%; high broken: 84% vs 78%
- SENSEX 15m: n 875; up after 5 candles 47% vs 51% matched; effect -0.092 ATR (-17.2 pts raw), q 0.727; low broken within 10: 55% vs 72%; high broken: 76% vs 70%
- NIFTY 15m wick levels (design): low broken within 10 candles 55% vs 50% for any candle whose low is as far from the close; high 79% vs 79%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 53% vs 52% for any candle whose low is as far from the close; high 76% vs 79%
- SENSEX 15m wick levels (design): low broken within 10 candles 55% vs 51% for any candle whose low is as far from the close; high 76% vs 78%

## Big head AND big tail

- What: both wicks >= 30% of a candle >= 1 ATR.
- Reason (hypothesis): both sides were rejected: a wide, undecided, two-way market (often volatile).
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 1,675; up after 5 candles 51% vs 51% matched; effect -0.070 ATR (-1.4 pts raw), q 0.6194; low broken within 10: 74% vs 77%; high broken: 70% vs 77%
- NIFTY 15m: n 467; up after 5 candles 52% vs 52% matched; effect +0.068 ATR (+0.7 pts raw), q 0.8125; low broken within 10: 66% vs 71%; high broken: 65% vs 71%
- BANKNIFTY 5m: n 1,906; up after 5 candles 50% vs 50% matched; effect -0.050 ATR (-3.1 pts raw), q 0.7558; low broken within 10: 74% vs 77%; high broken: 72% vs 77%
- BANKNIFTY 15m: n 503; up after 5 candles 48% vs 50% matched; effect -0.060 ATR (-10.0 pts raw), q 0.8467; low broken within 10: 70% vs 72%; high broken: 62% vs 71%
- SENSEX 5m: n 1,404; up after 5 candles 50% vs 51% matched; effect -0.009 ATR (-2.3 pts raw), q 1.0; low broken within 10: 76% vs 77%; high broken: 72% vs 77%
- SENSEX 15m: n 418; up after 5 candles 50% vs 51% matched; effect +0.066 ATR (-1.8 pts raw), q 0.8399; low broken within 10: 66% vs 70%; high broken: 64% vs 71%
- NIFTY 15m wick levels (design): low broken within 10 candles 66% vs 64% for any candle whose low is as far from the close; high 65% vs 65%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 70% vs 65% for any candle whose low is as far from the close; high 62% vs 64%
- SENSEX 15m wick levels (design): low broken within 10 candles 66% vs 64% for any candle whose low is as far from the close; high 64% vs 66%

## Small head and small tail

- What: both wicks <= 10%, range >= 0.5 ATR (almost all body).
- Reason (hypothesis): one side controlled the whole candle: a clean push.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 5,729; up after 5 candles 51% vs 51% matched; effect +0.022 ATR (+0.4 pts raw), q 0.8162; low broken within 10: 72% vs 76%; high broken: 74% vs 78%
- NIFTY 15m: n 1,345; up after 5 candles 52% vs 52% matched; effect +0.051 ATR (+1.4 pts raw), q 0.7859; low broken within 10: 66% vs 72%; high broken: 70% vs 74%
- BANKNIFTY 5m: n 4,603; up after 5 candles 52% vs 50% matched; effect +0.043 ATR (+2.0 pts raw), q 0.5027; low broken within 10: 71% vs 77%; high broken: 74% vs 78%
- BANKNIFTY 15m: n 1,163; up after 5 candles 50% vs 50% matched; effect -0.029 ATR (-2.5 pts raw), q 0.9022; low broken within 10: 67% vs 73%; high broken: 70% vs 74%
- SENSEX 5m: n 6,763; up after 5 candles 51% vs 51% matched; effect +0.002 ATR (+0.2 pts raw), q 1.0; low broken within 10: 72% vs 77%; high broken: 74% vs 79%
- SENSEX 15m: n 1,532; up after 5 candles 52% vs 51% matched; effect +0.041 ATR (+4.8 pts raw), q 0.8125; low broken within 10: 67% vs 72%; high broken: 72% vs 75%
- NIFTY 15m wick levels (design): low broken within 10 candles 66% vs 69% for any candle whose low is as far from the close; high 70% vs 71%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 67% vs 68% for any candle whose low is as far from the close; high 70% vs 70%
- SENSEX 15m wick levels (design): low broken within 10 candles 67% vs 69% for any candle whose low is as far from the close; high 72% vs 72%

## Tiny candle

- What: range < 0.5 ATR.
- Reason (hypothesis): a quiet minute / lull: low participation.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 4,713; up after 5 candles 52% vs 52% matched; effect +0.028 ATR (+1.1 pts raw), q 0.7306; low broken within 10: 86% vs 77%; high broken: 86% vs 79%
- NIFTY 15m: n 1,810; up after 5 candles 52% vs 53% matched; effect -0.019 ATR (-0.2 pts raw), q 0.9126; low broken within 10: 86% vs 76%; high broken: 89% vs 80%
- BANKNIFTY 5m: n 4,539; up after 5 candles 51% vs 51% matched; effect +0.011 ATR (+1.3 pts raw), q 0.9494; low broken within 10: 88% vs 79%; high broken: 88% vs 79%
- BANKNIFTY 15m: n 1,617; up after 5 candles 52% vs 51% matched; effect +0.037 ATR (+5.4 pts raw), q 0.7869; low broken within 10: 87% vs 78%; high broken: 88% vs 79%
- SENSEX 5m: n 5,435; up after 5 candles 53% vs 52% matched; effect +0.047 ATR (+4.0 pts raw), q 0.3186; low broken within 10: 87% vs 78%; high broken: 89% vs 80%
- SENSEX 15m: n 1,939; up after 5 candles 52% vs 52% matched; effect -0.009 ATR (+0.8 pts raw), q 1.0; low broken within 10: 87% vs 77%; high broken: 88% vs 80%

## Huge candle

- What: range >= 2 ATR.
- Reason (hypothesis): a shock: news, a level break or forced orders (stops).
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 3,388; up after 5 candles 51% vs 51% matched; effect -0.038 ATR (-0.4 pts raw), q 0.8347; low broken within 10: 64% vs 78%; high broken: 58% vs 74%
- NIFTY 15m: n 1,141; up after 5 candles 49% vs 51% matched; effect -0.038 ATR (-2.6 pts raw), q 0.9494; low broken within 10: 62% vs 72%; high broken: 52% vs 67%
- BANKNIFTY 5m: n 3,176; up after 5 candles 50% vs 50% matched; effect -0.013 ATR (-0.1 pts raw), q 1.0; low broken within 10: 62% vs 77%; high broken: 57% vs 75%
- BANKNIFTY 15m: n 1,129; up after 5 candles 50% vs 50% matched; effect -0.045 ATR (-9.5 pts raw), q 0.9022; low broken within 10: 56% vs 70%; high broken: 53% vs 68%
- SENSEX 5m: n 3,423; up after 5 candles 51% vs 50% matched; effect -0.049 ATR (-1.9 pts raw), q 0.7869; low broken within 10: 63% vs 77%; high broken: 59% vs 74%
- SENSEX 15m: n 1,159; up after 5 candles 49% vs 50% matched; effect -0.031 ATR (-8.1 pts raw), q 0.9707; low broken within 10: 59% vs 71%; high broken: 53% vs 67%

## Any green candle

- What: close > open.
- Reason (hypothesis): the baseline for colour.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 36,705; up after 5 candles 51% vs 51% matched; effect +0.000 ATR (-0.2 pts raw), q 1.0; low broken within 10: 67% vs 67%; high broken: 89% vs 89%
- NIFTY 15m: n 10,604; up after 5 candles 52% vs 52% matched; effect +0.000 ATR (+0.4 pts raw), q 1.0; low broken within 10: 63% vs 63%; high broken: 87% vs 87%
- BANKNIFTY 5m: n 36,051; up after 5 candles 50% vs 50% matched; effect -0.000 ATR (-0.4 pts raw), q 1.0; low broken within 10: 68% vs 68%; high broken: 88% vs 88%
- BANKNIFTY 15m: n 10,351; up after 5 candles 50% vs 50% matched; effect -0.000 ATR (+0.1 pts raw), q 1.0; low broken within 10: 64% vs 64%; high broken: 86% vs 86%
- SENSEX 5m: n 36,627; up after 5 candles 50% vs 50% matched; effect +0.000 ATR (-0.4 pts raw), q 1.0; low broken within 10: 68% vs 68%; high broken: 89% vs 89%
- SENSEX 15m: n 10,560; up after 5 candles 52% vs 52% matched; effect -0.000 ATR (+0.9 pts raw), q 1.0; low broken within 10: 64% vs 64%; high broken: 87% vs 87%

## Any red candle

- What: close < open.
- Reason (hypothesis): the baseline for colour.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 35,496; up after 5 candles 52% vs 52% matched; effect +0.000 ATR (+0.0 pts raw), q 1.0; low broken within 10: 88% vs 88%; high broken: 68% vs 68%
- NIFTY 15m: n 10,034; up after 5 candles 51% vs 51% matched; effect -0.000 ATR (-1.0 pts raw), q 1.0; low broken within 10: 84% vs 84%; high broken: 63% vs 63%
- BANKNIFTY 5m: n 35,906; up after 5 candles 52% vs 52% matched; effect +0.000 ATR (+0.3 pts raw), q 1.0; low broken within 10: 88% vs 88%; high broken: 69% vs 69%
- BANKNIFTY 15m: n 10,188; up after 5 candles 51% vs 51% matched; effect +0.000 ATR (-1.8 pts raw), q 1.0; low broken within 10: 85% vs 85%; high broken: 64% vs 64%
- SENSEX 5m: n 35,395; up after 5 candles 52% vs 52% matched; effect -0.000 ATR (+0.2 pts raw), q 1.0; low broken within 10: 88% vs 88%; high broken: 68% vs 68%
- SENSEX 15m: n 9,994; up after 5 candles 52% vs 52% matched; effect +0.000 ATR (-2.2 pts raw), q 1.0; low broken within 10: 85% vs 85%; high broken: 64% vs 64%

## Inside bar

- What: high and low inside the previous candle's.
- Reason (hypothesis): compression: the market waits.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 9,233; up after 5 candles 51% vs 52% matched; effect -0.025 ATR (-0.4 pts raw), q 0.7296; low broken within 10: 82% vs 77%; high broken: 83% vs 78%
- NIFTY 15m: n 2,740; up after 5 candles 53% vs 52% matched; effect +0.033 ATR (+1.0 pts raw), q 0.7859; low broken within 10: 79% vs 74%; high broken: 82% vs 76%
- BANKNIFTY 5m: n 10,056; up after 5 candles 50% vs 50% matched; effect -0.021 ATR (-1.0 pts raw), q 0.7385; low broken within 10: 83% vs 78%; high broken: 83% vs 78%
- BANKNIFTY 15m: n 3,033; up after 5 candles 51% vs 50% matched; effect +0.005 ATR (+0.1 pts raw), q 1.0; low broken within 10: 80% vs 75%; high broken: 80% vs 75%
- SENSEX 5m: n 8,882; up after 5 candles 52% vs 51% matched; effect +0.002 ATR (+0.8 pts raw), q 1.0; low broken within 10: 82% vs 78%; high broken: 83% vs 79%
- SENSEX 15m: n 2,708; up after 5 candles 52% vs 52% matched; effect +0.039 ATR (+3.2 pts raw), q 0.7296; low broken within 10: 79% vs 74%; high broken: 82% vs 76%

## Outside bar

- What: high above and low below the previous candle's.
- Reason (hypothesis): expansion both ways: a stop run on both sides.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 6,996; up after 5 candles 51% vs 52% matched; effect -0.031 ATR (-0.7 pts raw), q 0.7025; low broken within 10: 73% vs 78%; high broken: 73% vs 79%
- NIFTY 15m: n 1,820; up after 5 candles 52% vs 52% matched; effect +0.027 ATR (+0.7 pts raw), q 0.8759; low broken within 10: 69% vs 74%; high broken: 68% vs 75%
- BANKNIFTY 5m: n 7,378; up after 5 candles 50% vs 51% matched; effect -0.035 ATR (-0.9 pts raw), q 0.4301; low broken within 10: 73% vs 78%; high broken: 74% vs 79%
- BANKNIFTY 15m: n 1,802; up after 5 candles 51% vs 51% matched; effect -0.006 ATR (-0.3 pts raw), q 1.0; low broken within 10: 69% vs 75%; high broken: 70% vs 75%
- SENSEX 5m: n 7,318; up after 5 candles 51% vs 51% matched; effect -0.011 ATR (-1.0 pts raw), q 0.9419; low broken within 10: 74% vs 78%; high broken: 75% vs 79%
- SENSEX 15m: n 1,866; up after 5 candles 51% vs 52% matched; effect +0.007 ATR (-0.3 pts raw), q 1.0; low broken within 10: 70% vs 75%; high broken: 69% vs 75%

## Bullish engulfing

- What: a green body covering the previous red body.
- Reason (hypothesis): buyers overwhelmed the previous sellers.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 4,647; up after 5 candles 51% vs 51% matched; effect +0.027 ATR (-0.0 pts raw), q 0.7859; low broken within 10: 60% vs 67%; high broken: 91% vs 89%
- NIFTY 15m: n 1,295; up after 5 candles 53% vs 52% matched; effect +0.032 ATR (+1.6 pts raw), q 0.8467; low broken within 10: 55% vs 64%; high broken: 90% vs 88%
- BANKNIFTY 5m: n 4,499; up after 5 candles 48% vs 50% matched; effect +0.003 ATR (-0.2 pts raw), q 1.0; low broken within 10: 62% vs 69%; high broken: 90% vs 88%
- BANKNIFTY 15m: n 1,174; up after 5 candles 50% vs 50% matched; effect -0.018 ATR (-1.5 pts raw), q 0.9494; low broken within 10: 56% vs 64%; high broken: 89% vs 86%
- SENSEX 5m: n 4,779; up after 5 candles 50% vs 50% matched; effect -0.021 ATR (-1.3 pts raw), q 0.8212; low broken within 10: 61% vs 68%; high broken: 92% vs 89%
- SENSEX 15m: n 1,348; up after 5 candles 50% vs 51% matched; effect -0.063 ATR (-5.1 pts raw), q 0.6194; low broken within 10: 56% vs 64%; high broken: 89% vs 87%

## Bearish engulfing

- What: a red body covering the previous green body.
- Reason (hypothesis): sellers overwhelmed the previous buyers.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 4,198; up after 5 candles 53% vs 52% matched; effect +0.016 ATR (+0.7 pts raw), q 0.9022; low broken within 10: 90% vs 88%; high broken: 61% vs 69%
- NIFTY 15m: n 1,212; up after 5 candles 52% vs 52% matched; effect +0.019 ATR (+0.0 pts raw), q 0.9494; low broken within 10: 86% vs 84%; high broken: 57% vs 64%
- BANKNIFTY 5m: n 4,071; up after 5 candles 53% vs 52% matched; effect +0.025 ATR (+2.0 pts raw), q 0.7859; low broken within 10: 90% vs 88%; high broken: 62% vs 69%
- BANKNIFTY 15m: n 1,179; up after 5 candles 50% vs 51% matched; effect +0.013 ATR (+1.3 pts raw), q 1.0; low broken within 10: 87% vs 85%; high broken: 58% vs 64%
- SENSEX 5m: n 4,656; up after 5 candles 52% vs 52% matched; effect +0.014 ATR (+1.3 pts raw), q 0.9398; low broken within 10: 90% vs 88%; high broken: 62% vs 69%
- SENSEX 15m: n 1,332; up after 5 candles 51% vs 52% matched; effect +0.001 ATR (-2.8 pts raw), q 1.0; low broken within 10: 86% vs 85%; high broken: 55% vs 64%

## Bullish harami

- What: a small green body inside a big previous red body.
- Reason (hypothesis): the selling stalled.
- Verdict: 2 instrument/timeframe finding(s), more up than matched candles; 0 held out of sample.
- NIFTY 5m: n 2,363; up after 5 candles 50% vs 50% matched; effect +0.006 ATR (-0.6 pts raw), q 1.0; low broken within 10: 68% vs 67%; high broken: 88% vs 88%
- NIFTY 15m: n 625; up after 5 candles 52% vs 52% matched; effect +0.008 ATR (+0.1 pts raw), q 1.0; low broken within 10: 64% vs 62%; high broken: 86% vs 87%
- BANKNIFTY 5m: n 2,271; up after 5 candles 52% vs 49% matched; effect +0.049 ATR (+1.9 pts raw), q 0.6202; low broken within 10: 68% vs 68%; high broken: 87% vs 88%
- BANKNIFTY 15m: n 614; up after 5 candles 50% vs 50% matched; effect +0.078 ATR (+9.2 pts raw), q 0.7296; low broken within 10: 63% vs 64%; high broken: 84% vs 85%
- SENSEX 5m: n 2,561; up after 5 candles 53% vs 50% matched; effect +0.013 ATR (-1.4 pts raw), q 0.9873; low broken within 10: 68% vs 68%; high broken: 89% vs 89%
- SENSEX 15m: n 659; up after 5 candles 55% vs 52% matched; effect +0.140 ATR (+17.5 pts raw), q 0.1998; low broken within 10: 65% vs 63%; high broken: 88% vs 87%

## Bearish harami

- What: a small red body inside a big previous green body.
- Reason (hypothesis): the buying stalled.
- Verdict: 2 instrument/timeframe finding(s), more down than matched candles; 0 held out of sample.
- NIFTY 5m: n 2,546; up after 5 candles 51% vs 52% matched; effect +0.003 ATR (+0.4 pts raw), q 1.0; low broken within 10: 88% vs 87%; high broken: 71% vs 69%
- NIFTY 15m: n 571; up after 5 candles 53% vs 52% matched; effect +0.096 ATR (+3.4 pts raw), q 0.5906; low broken within 10: 84% vs 84%; high broken: 69% vs 63%
- BANKNIFTY 5m: n 2,439; up after 5 candles 48% vs 51% matched; effect -0.032 ATR (-1.8 pts raw), q 0.7869; low broken within 10: 88% vs 88%; high broken: 68% vs 69%
- BANKNIFTY 15m: n 575; up after 5 candles 51% vs 51% matched; effect +0.056 ATR (+2.8 pts raw), q 0.8111; low broken within 10: 82% vs 84%; high broken: 62% vs 63%
- SENSEX 5m: n 2,625; up after 5 candles 51% vs 52% matched; effect -0.019 ATR (+0.4 pts raw), q 0.9022; low broken within 10: 88% vs 88%; high broken: 72% vs 69%
- SENSEX 15m: n 636; up after 5 candles 52% vs 52% matched; effect +0.055 ATR (+2.2 pts raw), q 0.8142; low broken within 10: 85% vs 84%; high broken: 68% vs 64%

## Tweezer bottom

- What: red then green with equal lows.
- Reason (hypothesis): the same low defended twice.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 3,002; up after 5 candles 52% vs 51% matched; effect +0.007 ATR (-0.3 pts raw), q 1.0; low broken within 10: 68% vs 68%; high broken: 89% vs 89%
- NIFTY 15m: n 767; up after 5 candles 52% vs 52% matched; effect +0.033 ATR (+2.3 pts raw), q 0.8964; low broken within 10: 65% vs 65%; high broken: 89% vs 88%
- BANKNIFTY 5m: n 3,051; up after 5 candles 49% vs 50% matched; effect -0.021 ATR (-1.3 pts raw), q 0.832; low broken within 10: 71% vs 69%; high broken: 88% vs 88%
- BANKNIFTY 15m: n 836; up after 5 candles 46% vs 50% matched; effect -0.116 ATR (-6.1 pts raw), q 0.2373; low broken within 10: 69% vs 65%; high broken: 84% vs 86%
- SENSEX 5m: n 3,007; up after 5 candles 52% vs 50% matched; effect +0.025 ATR (+1.3 pts raw), q 0.8212; low broken within 10: 67% vs 68%; high broken: 91% vs 89%
- SENSEX 15m: n 849; up after 5 candles 51% vs 52% matched; effect -0.076 ATR (-6.7 pts raw), q 0.6194; low broken within 10: 67% vs 65%; high broken: 87% vs 88%

## Tweezer top

- What: green then red with equal highs.
- Reason (hypothesis): the same high defended twice.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 3,333; up after 5 candles 51% vs 53% matched; effect -0.053 ATR (-0.9 pts raw), q 0.3525; low broken within 10: 89% vs 88%; high broken: 68% vs 69%
- NIFTY 15m: n 980; up after 5 candles 54% vs 52% matched; effect +0.046 ATR (+1.1 pts raw), q 0.7869; low broken within 10: 84% vs 85%; high broken: 67% vs 66%
- BANKNIFTY 5m: n 3,373; up after 5 candles 50% vs 52% matched; effect -0.016 ATR (+0.8 pts raw), q 0.9022; low broken within 10: 90% vs 88%; high broken: 70% vs 70%
- BANKNIFTY 15m: n 959; up after 5 candles 51% vs 52% matched; effect +0.019 ATR (+2.4 pts raw), q 0.9494; low broken within 10: 86% vs 86%; high broken: 68% vs 66%
- SENSEX 5m: n 3,398; up after 5 candles 50% vs 52% matched; effect -0.027 ATR (-1.8 pts raw), q 0.7876; low broken within 10: 89% vs 88%; high broken: 69% vs 69%
- SENSEX 15m: n 1,003; up after 5 candles 53% vs 52% matched; effect +0.047 ATR (+3.8 pts raw), q 0.7859; low broken within 10: 85% vs 85%; high broken: 67% vs 66%

## Three white soldiers

- What: three green candles, each closing higher, solid bodies.
- Reason (hypothesis): steady buying.
- Verdict: 1 instrument/timeframe finding(s), more up than matched candles; 0 held out of sample.
- NIFTY 5m: n 893; up after 5 candles 55% vs 52% matched; effect +0.173 ATR (+3.9 pts raw), q 0.0404; low broken within 10: 58% vs 65%; high broken: 94% vs 89% — FINDING, did NOT hold out of sample
- NIFTY 15m: n 229; up after 5 candles 58% vs 53% matched; effect +0.119 ATR (+4.3 pts raw), q 0.7558; low broken within 10: 53% vs 62%; high broken: 92% vs 88%
- BANKNIFTY 5m: n 660; up after 5 candles 50% vs 50% matched; effect +0.096 ATR (+6.5 pts raw), q 0.7114; low broken within 10: 55% vs 67%; high broken: 91% vs 88%
- BANKNIFTY 15m: n 171; up after 5 candles 53% vs 50% matched; effect +0.130 ATR (+1.9 pts raw), q 0.8125; low broken within 10: 48% vs 62%; high broken: 87% vs 85%
- SENSEX 5m: n 1,000; up after 5 candles 53% vs 51% matched; effect +0.128 ATR (+9.3 pts raw), q 0.2058; low broken within 10: 58% vs 67%; high broken: 93% vs 89%
- SENSEX 15m: n 238; up after 5 candles 56% vs 52% matched; effect +0.029 ATR (+2.4 pts raw), q 1.0; low broken within 10: 53% vs 63%; high broken: 93% vs 88%

## Three black crows

- What: three red candles, each closing lower, solid bodies.
- Reason (hypothesis): steady selling.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 835; up after 5 candles 51% vs 51% matched; effect -0.076 ATR (-1.9 pts raw), q 0.8142; low broken within 10: 92% vs 88%; high broken: 58% vs 66%
- NIFTY 15m: n 193; up after 5 candles 54% vs 50% matched; effect +0.030 ATR (-2.3 pts raw), q 1.0; low broken within 10: 91% vs 86%; high broken: 53% vs 62%
- BANKNIFTY 5m: n 601; up after 5 candles 49% vs 52% matched; effect -0.160 ATR (-5.3 pts raw), q 0.3326; low broken within 10: 91% vs 88%; high broken: 55% vs 67%
- BANKNIFTY 15m: n 139; up after 5 candles 50% vs 50% matched; effect -0.176 ATR (-42.3 pts raw), q 0.7859; low broken within 10: 89% vs 85%; high broken: 55% vs 62%
- SENSEX 5m: n 916; up after 5 candles 50% vs 52% matched; effect -0.084 ATR (-5.8 pts raw), q 0.7859; low broken within 10: 89% vs 88%; high broken: 59% vs 67%
- SENSEX 15m: n 195; up after 5 candles 49% vs 50% matched; effect -0.103 ATR (-24.8 pts raw), q 0.8125; low broken within 10: 89% vs 86%; high broken: 49% vs 63%

## Morning star

- What: big red, small candle, green closing above the red's middle.
- Reason (hypothesis): a three-candle bottom turn.
- Verdict: 1 instrument/timeframe finding(s), more up than matched candles; 0 held out of sample.
- NIFTY 5m: n 492; up after 5 candles 50% vs 50% matched; effect -0.104 ATR (-1.8 pts raw), q 0.6769; low broken within 10: 56% vs 67%; high broken: 92% vs 88%
- NIFTY 15m: n 112; up after 5 candles 55% vs 53% matched; effect +0.152 ATR (+5.9 pts raw), q 0.7869; low broken within 10: 48% vs 61%; high broken: 88% vs 86%
- BANKNIFTY 5m: n 451; up after 5 candles 51% vs 50% matched; effect +0.067 ATR (+4.1 pts raw), q 0.8111; low broken within 10: 55% vs 68%; high broken: 91% vs 88%
- BANKNIFTY 15m: n 90; up after 5 candles 62% vs 50% matched; effect +0.406 ATR (+44.3 pts raw), q 0.1261; low broken within 10: 47% vs 63%; high broken: 95% vs 86%
- SENSEX 5m: n 533; up after 5 candles 50% vs 50% matched; effect -0.060 ATR (-2.5 pts raw), q 0.8212; low broken within 10: 57% vs 68%; high broken: 92% vs 89%
- SENSEX 15m: n 114; up after 5 candles 57% vs 51% matched; effect +0.262 ATR (+41.4 pts raw), q 0.5161; low broken within 10: 44% vs 62%; high broken: 90% vs 86%

## Evening star

- What: big green, small candle, red closing below the green's middle.
- Reason (hypothesis): a three-candle top turn.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 503; up after 5 candles 49% vs 52% matched; effect -0.063 ATR (-1.4 pts raw), q 0.8212; low broken within 10: 90% vs 87%; high broken: 55% vs 68%
- NIFTY 15m: n 89; up after 5 candles 48% vs 52% matched; effect -0.137 ATR (-6.7 pts raw), q 0.8167; low broken within 10: 88% vs 83%; high broken: 46% vs 61%
- BANKNIFTY 5m: n 502; up after 5 candles 49% vs 51% matched; effect -0.100 ATR (-7.5 pts raw), q 0.7296; low broken within 10: 92% vs 88%; high broken: 57% vs 68%
- BANKNIFTY 15m: n 110; up after 5 candles 56% vs 52% matched; effect -0.137 ATR (-10.8 pts raw), q 0.7859; low broken within 10: 86% vs 84%; high broken: 51% vs 62%
- SENSEX 5m: n 537; up after 5 candles 50% vs 52% matched; effect -0.040 ATR (-2.1 pts raw), q 0.9419; low broken within 10: 90% vs 88%; high broken: 55% vs 68%
- SENSEX 15m: n 108; up after 5 candles 49% vs 52% matched; effect -0.100 ATR (-21.0 pts raw), q 0.894; low broken within 10: 88% vs 83%; high broken: 44% vs 62%

## Absorption (effort without result)

- What: option activity >= 2x usual but range <= 0.7 ATR.
- Reason (hypothesis): big orders traded but price did not move: someone large absorbed the other side (a limit wall).
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 2,266; up after 5 candles 52% vs 52% matched; effect +0.049 ATR (+1.0 pts raw), q 0.7859; low broken within 10: 84% vs 77%; high broken: 86% vs 80%
- NIFTY 15m: n 736; up after 5 candles 50% vs 52% matched; effect -0.020 ATR (-1.1 pts raw), q 1.0; low broken within 10: 82% vs 76%; high broken: 85% vs 78%

## Climax candle

- What: activity >= 2.5x usual and range >= 1.5 ATR.
- Reason (hypothesis): everybody traded at once: capitulation or a news burst.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 1,386; up after 5 candles 53% vs 51% matched; effect +0.067 ATR (+1.3 pts raw), q 0.8142; low broken within 10: 65% vs 77%; high broken: 64% vs 76%
- NIFTY 15m: n 345; up after 5 candles 52% vs 52% matched; effect -0.027 ATR (-1.2 pts raw), q 1.0; low broken within 10: 63% vs 72%; high broken: 57% vs 69%

## Stop run above the day's high

- What: a wick above the day's high so far that closes back below it.
- Reason (hypothesis): stops above the high were triggered, then sellers took over (or the buying dried up).
- Verdict: 1 instrument/timeframe finding(s), more up than matched candles; 0 held out of sample.
- NIFTY 5m: n 3,481; up after 5 candles 55% vs 53% matched; effect +0.047 ATR (+1.2 pts raw), q 0.6194; low broken within 10: 82% vs 78%; high broken: 75% vs 78%
- NIFTY 15m: n 1,666; up after 5 candles 57% vs 53% matched; effect +0.129 ATR (+5.0 pts raw), q 0.0649; low broken within 10: 76% vs 74%; high broken: 73% vs 75% — FINDING, did NOT hold out of sample
- BANKNIFTY 5m: n 2,985; up after 5 candles 50% vs 50% matched; effect +0.021 ATR (+1.0 pts raw), q 0.9126; low broken within 10: 84% vs 79%; high broken: 71% vs 78%
- BANKNIFTY 15m: n 1,489; up after 5 candles 51% vs 51% matched; effect +0.108 ATR (+8.8 pts raw), q 0.2373; low broken within 10: 79% vs 75%; high broken: 70% vs 74%
- SENSEX 5m: n 3,324; up after 5 candles 52% vs 52% matched; effect +0.003 ATR (-0.1 pts raw), q 1.0; low broken within 10: 83% vs 78%; high broken: 74% vs 78%
- SENSEX 15m: n 1,634; up after 5 candles 55% vs 52% matched; effect +0.114 ATR (+12.0 pts raw), q 0.1776; low broken within 10: 78% vs 74%; high broken: 72% vs 75%
- NIFTY 15m wick levels (design): low broken within 10 candles 76% vs 78% for any candle whose low is as far from the close; high 73% vs 71%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 79% vs 79% for any candle whose low is as far from the close; high 70% vs 69%
- SENSEX 15m wick levels (design): low broken within 10 candles 78% vs 79% for any candle whose low is as far from the close; high 72% vs 71%

## Stop run below the day's low

- What: a wick below the day's low so far that closes back above it.
- Reason (hypothesis): stops below the low were triggered, then buyers took over.
- Verdict: 3 instrument/timeframe finding(s), more up than matched candles; 1 held out of sample.
- NIFTY 5m: n 3,198; up after 5 candles 51% vs 50% matched; effect +0.034 ATR (-0.1 pts raw), q 0.8125; low broken within 10: 69% vs 76%; high broken: 84% vs 79%
- NIFTY 15m: n 1,596; up after 5 candles 51% vs 51% matched; effect -0.042 ATR (-2.4 pts raw), q 0.8212; low broken within 10: 66% vs 73%; high broken: 79% vs 76%
- BANKNIFTY 5m: n 3,009; up after 5 candles 50% vs 50% matched; effect +0.009 ATR (-0.6 pts raw), q 1.0; low broken within 10: 69% vs 77%; high broken: 82% vs 78%
- BANKNIFTY 15m: n 1,527; up after 5 candles 47% vs 50% matched; effect -0.072 ATR (-8.1 pts raw), q 0.6085; low broken within 10: 68% vs 74%; high broken: 78% vs 74%
- SENSEX 5m: n 3,201; up after 5 candles 49% vs 50% matched; effect -0.004 ATR (-1.7 pts raw), q 1.0; low broken within 10: 69% vs 76%; high broken: 84% vs 79%
- SENSEX 15m: n 1,581; up after 5 candles 49% vs 51% matched; effect -0.061 ATR (-7.8 pts raw), q 0.7296; low broken within 10: 68% vs 73%; high broken: 79% vs 76%
- NIFTY 15m wick levels (design): low broken within 10 candles 66% vs 64% for any candle whose low is as far from the close; high 79% vs 78%
- BANKNIFTY 15m wick levels (design): low broken within 10 candles 68% vs 66% for any candle whose low is as far from the close; high 78% vs 77%
- SENSEX 15m wick levels (design): low broken within 10 candles 68% vs 65% for any candle whose low is as far from the close; high 79% vs 79%

## Heavy-volume tail

- What: lower wick >= 40% that carries >= 50% of the candle's activity.
- Reason (hypothesis): the activity happened at the low and price came back: buying at the low.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 5,314; up after 5 candles 51% vs 51% matched; effect -0.042 ATR (-1.3 pts raw), q 0.6427; low broken within 10: 66% vs 77%; high broken: 85% vs 78%
- NIFTY 15m: n 1,348; up after 5 candles 51% vs 52% matched; effect -0.024 ATR (-2.5 pts raw), q 0.9494; low broken within 10: 61% vs 73%; high broken: 80% vs 72%
- NIFTY 15m wick levels (design): low broken within 10 candles 61% vs 59% for any candle whose low is as far from the close; high 80% vs 80%

## Heavy-volume head

- What: upper wick >= 40% that carries >= 50% of the candle's activity.
- Reason (hypothesis): the activity happened at the high and price came back: selling at the high.
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 4,769; up after 5 candles 52% vs 51% matched; effect +0.025 ATR (+0.4 pts raw), q 0.8212; low broken within 10: 84% vs 78%; high broken: 70% vs 78%
- NIFTY 15m: n 1,098; up after 5 candles 56% vs 52% matched; effect +0.111 ATR (+3.2 pts raw), q 0.3295; low broken within 10: 77% vs 71%; high broken: 65% vs 74%
- NIFTY 15m wick levels (design): low broken within 10 candles 77% vs 79% for any candle whose low is as far from the close; high 65% vs 62%

## Red candle, buying inside

- What: red candle whose minute-rule delta share > +0.2.
- Reason (hypothesis): price fell but most active minutes rose: hidden buying (proxy, not true delta).
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 1,520; up after 5 candles 52% vs 52% matched; effect -0.012 ATR (-0.6 pts raw), q 1.0; low broken within 10: 86% vs 88%; high broken: 76% vs 69%
- NIFTY 15m: n 227; up after 5 candles 49% vs 52% matched; effect -0.144 ATR (-7.5 pts raw), q 0.7306; low broken within 10: 82% vs 84%; high broken: 69% vs 63%

## Green candle, selling inside

- What: green candle whose minute-rule delta share < -0.2.
- Reason (hypothesis): price rose but most active minutes fell: hidden selling (proxy, not true delta).
- Verdict: no edge: after it, price moves like any matched candle (same time, trend, volatility, colour).
- NIFTY 5m: n 1,492; up after 5 candles 49% vs 51% matched; effect -0.042 ATR (-1.0 pts raw), q 0.8111; low broken within 10: 75% vs 67%; high broken: 86% vs 89%
- NIFTY 15m: n 154; up after 5 candles 44% vs 53% matched; effect -0.074 ATR (+0.2 pts raw), q 0.9126; low broken within 10: 71% vs 62%; high broken: 84% vs 87%

