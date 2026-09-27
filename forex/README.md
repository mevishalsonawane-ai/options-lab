# forex: XAU/USD (gold) study

A standalone study, not part of the app.

- `fetch.py` downloads 3 years of data into `data/`:
  - hourly spot XAU/USD from Dukascopy (Yahoo gold futures if that fails), with daily candles
    built on the New York 17:00 close;
  - the dollar index, US 10-year yield, silver, S&P 500 and bitcoin, for context.
- `train.py` studies the charts (including sessions and cross-asset links), then trains small
  numpy neural networks walk-forward and writes `results/results.json`.
- `index.html` is the results page.

CI (`.github/workflows/markets.yml`) runs both scripts and publishes `data/` and `results/` to the
`market-data` branch.
