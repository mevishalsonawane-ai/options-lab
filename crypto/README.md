# crypto: BTC study

A standalone study, not part of the app.

- `fetch.py` downloads 3 years of data into `data/`:
  - BTCUSDT daily and hourly candles (Binance);
  - the Deribit DVOL implied-volatility index;
  - sampled Deribit option trades, summarised per day: ATM IV, 90/110 skew and put/call ratio;
  - today's full option chain;
  - S&P 500, dollar index and gold, for context.
- `train.py` studies the charts, then trains small numpy neural networks walk-forward and writes
  `results/results.json`. The networks predict next-day direction, next-hour direction and
  next-5-day volatility.
- `index.html` is the results page.

CI (`.github/workflows/markets.yml`) runs both scripts and publishes `data/` and `results/` to the
`market-data` branch.
