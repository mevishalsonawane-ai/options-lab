# dhan-data (reference data only)

Market data downloaded from Dhan's data API on 2026-10-06 for research (zero-to-hero / arm studies).
Not used by the app. Index expired options: `dhan-data/options/<UNDERLYING>/<WEEK|MONTH>/<CALL|PUT>/<YYYY>.parquet`
(1-minute, ATM-10..ATM+10, columns incl. open/high/low/close/volume/oi/iv/strike/spot; OI and volume in units, not lots).
Coverage from ~2020-09 (Dhan's limit) to 2026-10-05. More (index/VIX candles, stocks, futures, manifest) added later.
