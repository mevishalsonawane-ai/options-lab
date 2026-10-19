# Data fill, 19–20 Oct 2026: RESULT (in progress)

Status: **running**. This file is updated as each part finishes.

- Dhan token: works (checked with one daily NIFTY call, 19 Oct 18:35 UTC).
- Lake: Google Drive `gdrive:IraAlgo-data-lake` is reachable; it was downloaded to the session's local disk for
  `--only-missing`.

## Done so far

- **MCX futures, 1 minute, 11 commodities** (GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI,
  COPPER, ZINC, ALUMINIUM): every contract in Dhan's scrip master was fetched from its listing to **19 Oct 23:30**,
  0 errors. 64 contracts have minutes.
- MCX continuous daily futures (expiry code 0) for all 11, and index daily, refreshed.

## Running

- MCX options (ATM±5, nearest monthly): the 6 existing books brought to date, and 4 new books (CRUDEOILM, NATGASMINI,
  COPPER, ZINC). Dhan answers MCX option history slowly (15–30 s a request), so this takes hours.
- Index data and options to date (5 indices).

## Found

- Dhan's intraday endpoint returns the newest day's minutes only when `toDate` is a later day. Fixed in
  `lake_fetch.to_date`.
- Dhan's `rollingoption` had no 19 Oct minutes at 00:30 IST on 20 Oct. The newest windows are fetched again later.
