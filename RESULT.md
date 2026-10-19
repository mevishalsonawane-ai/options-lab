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

## Boss, 20 Oct: "Get data only from November 2024"

Every fetch now asks only for windows ending on or after 1 Nov 2024 (`--since 2024-11-01`). This cut the stock-option
work from 60,357 to about 22,800 chunks. MCX options start in mid-2025 anyway; MCX futures start at each contract's
listing (2025-10 or later).

## Also done

- Index: 1-minute index and futures, and all 10 index option books (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX;
  WEEK and MONTH) to 16 Oct; 19 Oct follows once Dhan publishes it.
- MCX options: all 10 books fetched to **19 Oct**, 0 failed chunks.

## Running

- MCX options (ATM±5, nearest monthly): the 6 existing books brought to date, and 4 new books (CRUDEOILM, NATGASMINI,
  COPPER, ZINC). Dhan answers MCX option history slowly (15–30 s a request), so this takes hours.
- Index data and options to date (5 indices).

## Found

- Dhan's intraday endpoint returns the newest day's minutes only when `toDate` is a later day. Fixed in
  `lake_fetch.to_date`.
- Dhan's `rollingoption` had no 19 Oct minutes at 00:30 IST on 20 Oct. The newest windows are fetched again later.
