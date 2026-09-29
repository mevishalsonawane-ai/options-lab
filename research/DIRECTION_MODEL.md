## Direction model, BANKNIFTY 2024-02-13 .. 2026-02-23 (498 days, 30378 decision points)

Walk-forward: each month predicted by a model trained only on earlier months (first 6 months are training only).
Features available: tod, dow, dte, r5, r15, r30, r60, r_open, gap, prev_ret, rv30, day_pos, vwap_d, up_wall_d, dn_wall_d, wall_pos, pcr, pcr_d30, ce_oi_d30, pe_oi_d30, strad, skew, adv, heavy, nifty_r15, nifty_r5, vix, vix_d30, basis, basis_d30, lead_HDFCBANK, lead_ICICIBANK, lead_SBIN, lead_AXISBANK, lead_KOTAKBANK

### 5 minutes ahead

Base rate (share up): 50.0%. Coin flip = 50%.

| selectivity | moments | per day | direction correct | 1st / 2nd half | avg move our way (pts) |
|---|---|---|---|---|---|
| all moments | 23469 | 61.0 | 51.4% | 51.5% / 51.4% | +0.1 |
| top 30% most confident | 7041 | 19.2 | 52.6% | 52.8% / 52.1% | +0.4 |
| top 20% | 4694 | 14.6 | 52.5% | 52.8% / 51.7% | +0.3 |
| top 10% | 2347 | 10.3 | 52.3% | 51.7% / 54.3% | -0.3 |
| top 5% | 1174 | 8.6 | 51.1% | 50.7% / 52.4% | -1.6 |

**Classic rules at the same horizon**

| rule | moments | correct |
|---|---|---|
| follow last 5 min | 23453 | 48.2% |
| follow last 15 min | 23465 | 48.1% |
| follow the day so far | 23468 | 49.6% |
| fade last 15 min | 23465 | 51.9% |
| PCR rising -> up (pcr_d30) | 23469 | 49.8% |
| closer to the put wall -> up (wall_pos < 0.5) | 23468 | 50.0% |
| NIFTY led up in last 5 min | 23469 | 49.9% |
| VIX falling 30 min -> up | 22428 | 49.4% |
| basis rising 30 min -> up | 23453 | 51.2% |
| breadth: 8+ advancing -> up | 13940 | 49.9% |
| HDFC & ICICI both led the index up/down in last 5 min | 6970 | 50.7% |
| top-5 banks: majority led up/down in last 5 min | 15418 | 50.2% |

Most useful inputs (last 3 months): r5 0.016, r15 0.012, lead_KOTAKBANK 0.007, r30 0.004, nifty_r15 0.003, dn_wall_d 0.003, skew 0.002, basis_d30 0.002

### 15 minutes ahead

Base rate (share up): 50.3%. Coin flip = 50%.

| selectivity | moments | per day | direction correct | 1st / 2nd half | avg move our way (pts) |
|---|---|---|---|---|---|
| all moments | 23478 | 61.0 | 51.4% | 51.7% / 51.1% | +0.4 |
| top 30% most confident | 7044 | 19.1 | 52.2% | 52.2% / 52.2% | +0.6 |
| top 20% | 4696 | 13.5 | 51.8% | 51.0% / 52.8% | -0.1 |
| top 10% | 2348 | 8.7 | 52.9% | 52.4% / 53.7% | +0.5 |
| top 5% | 1174 | 6.3 | 52.1% | 51.0% / 54.0% | -2.2 |

**Classic rules at the same horizon**

| rule | moments | correct |
|---|---|---|
| follow last 5 min | 23462 | 48.7% |
| follow last 15 min | 23474 | 48.1% |
| follow the day so far | 23477 | 49.7% |
| fade last 15 min | 23474 | 51.9% |
| PCR rising -> up (pcr_d30) | 23478 | 49.8% |
| closer to the put wall -> up (wall_pos < 0.5) | 23477 | 49.6% |
| NIFTY led up in last 5 min | 23478 | 49.9% |
| VIX falling 30 min -> up | 22438 | 49.5% |
| basis rising 30 min -> up | 23462 | 51.6% |
| breadth: 8+ advancing -> up | 13946 | 50.0% |
| HDFC & ICICI both led the index up/down in last 5 min | 6975 | 50.2% |
| top-5 banks: majority led up/down in last 5 min | 15426 | 50.1% |

Most useful inputs (last 3 months): vix 0.012, rv30 0.011, r15 0.010, gap 0.006, basis 0.005, lead_KOTAKBANK 0.004, pcr 0.004, strad 0.003

### 30 minutes ahead

Base rate (share up): 50.4%. Coin flip = 50%.

| selectivity | moments | per day | direction correct | 1st / 2nd half | avg move our way (pts) |
|---|---|---|---|---|---|
| all moments | 23473 | 61.0 | 51.7% | 52.4% / 51.0% | +0.9 |
| top 30% most confident | 7042 | 19.1 | 52.2% | 51.5% / 53.5% | -0.1 |
| top 20% | 4695 | 14.6 | 52.7% | 51.7% / 54.2% | +0.5 |
| top 10% | 2348 | 10.8 | 52.3% | 50.9% / 54.6% | -0.9 |
| top 5% | 1174 | 8.2 | 53.7% | 52.0% / 55.9% | +0.0 |

**Classic rules at the same horizon**

| rule | moments | correct |
|---|---|---|
| follow last 5 min | 23457 | 48.7% |
| follow last 15 min | 23469 | 48.3% |
| follow the day so far | 23472 | 50.3% |
| fade last 15 min | 23469 | 51.7% |
| PCR rising -> up (pcr_d30) | 23473 | 50.3% |
| closer to the put wall -> up (wall_pos < 0.5) | 23472 | 49.0% |
| NIFTY led up in last 5 min | 23473 | 50.0% |
| VIX falling 30 min -> up | 22432 | 48.8% |
| basis rising 30 min -> up | 23457 | 51.6% |
| breadth: 8+ advancing -> up | 13946 | 50.2% |
| HDFC & ICICI both led the index up/down in last 5 min | 6972 | 50.2% |
| top-5 banks: majority led up/down in last 5 min | 15427 | 50.1% |

Most useful inputs (last 3 months): pcr 0.018, up_wall_d 0.016, vix 0.016, wall_pos 0.014, gap 0.010, tod 0.008, vwap_d 0.008, basis_d30 0.008

### 60 minutes ahead

Base rate (share up): 50.2%. Coin flip = 50%.

| selectivity | moments | per day | direction correct | 1st / 2nd half | avg move our way (pts) |
|---|---|---|---|---|---|
| all moments | 23480 | 61.0 | 50.9% | 50.2% / 51.6% | +1.4 |
| top 30% most confident | 7044 | 21.0 | 51.8% | 49.6% / 55.1% | +1.8 |
| top 20% | 4696 | 16.8 | 51.9% | 47.9% / 57.4% | -0.4 |
| top 10% | 2348 | 13.2 | 51.6% | 48.7% / 54.9% | -5.5 |
| top 5% | 1176 | 9.8 | 51.5% | 49.1% / 54.5% | -5.5 |

**Classic rules at the same horizon**

| rule | moments | correct |
|---|---|---|
| follow last 5 min | 23464 | 49.6% |
| follow last 15 min | 23476 | 49.2% |
| follow the day so far | 23479 | 50.2% |
| fade last 15 min | 23476 | 50.8% |
| PCR rising -> up (pcr_d30) | 23480 | 50.1% |
| closer to the put wall -> up (wall_pos < 0.5) | 23479 | 48.9% |
| NIFTY led up in last 5 min | 23480 | 49.7% |
| VIX falling 30 min -> up | 22439 | 48.7% |
| basis rising 30 min -> up | 23464 | 50.4% |
| breadth: 8+ advancing -> up | 13947 | 50.3% |
| HDFC & ICICI both led the index up/down in last 5 min | 6972 | 50.4% |
| top-5 banks: majority led up/down in last 5 min | 15428 | 50.5% |

Most useful inputs (last 3 months): pcr 0.011, basis 0.011, heavy 0.008, prev_ret 0.007, vix 0.007, wall_pos 0.007, dn_wall_d 0.006, r60 0.006
