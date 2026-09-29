## Direction model, BANKNIFTY 2024-02-13 .. 2026-02-23 (498 days, 30378 decision points)

Walk-forward: each month predicted by a model trained only on earlier months (first 6 months are training only).
Features available: tod, dow, dte, r5, r15, r30, r60, r_open, gap, prev_ret, rv30, day_pos, vwap_d, up_wall_d, dn_wall_d, wall_pos, pcr, pcr_d30, ce_oi_d30, pe_oi_d30, strad, skew, adv, heavy, lead_HDFCBANK, lead_ICICIBANK, lead_SBIN, lead_AXISBANK, lead_KOTAKBANK

### 5 minutes ahead

Base rate (share up): 50.0%. Coin flip = 50%.

| selectivity | moments | per day | direction correct | 1st / 2nd half | avg move our way (pts) |
|---|---|---|---|---|---|
| all moments | 23469 | 61.0 | 51.7% | 51.5% / 51.9% | +0.4 |
| top 30% most confident | 7041 | 18.6 | 53.0% | 53.1% / 52.9% | +0.8 |
| top 20% | 4694 | 13.7 | 53.1% | 53.3% / 52.5% | +0.5 |
| top 10% | 2347 | 9.4 | 53.1% | 52.8% / 54.2% | +1.1 |
| top 5% | 1174 | 8.7 | 52.2% | 53.0% / 49.6% | -0.6 |

**Classic rules at the same horizon**

| rule | moments | correct |
|---|---|---|
| follow last 5 min | 23453 | 48.2% |
| follow last 15 min | 23465 | 48.1% |
| follow the day so far | 23468 | 49.6% |
| fade last 15 min | 23465 | 51.9% |
| PCR rising -> up (pcr_d30) | 23469 | 49.8% |
| closer to the put wall -> up (wall_pos < 0.5) | 23468 | 50.0% |
| breadth: 8+ advancing -> up | 13940 | 49.9% |
| HDFC & ICICI both led the index up/down in last 5 min | 6970 | 50.7% |
| top-5 banks: majority led up/down in last 5 min | 15418 | 50.2% |

Most useful inputs (last 3 months): r5 0.014, r30 0.008, lead_AXISBANK 0.008, r15 0.006, pe_oi_d30 0.006, day_pos 0.005, gap 0.004, prev_ret 0.004

### 15 minutes ahead

Base rate (share up): 50.3%. Coin flip = 50%.

| selectivity | moments | per day | direction correct | 1st / 2nd half | avg move our way (pts) |
|---|---|---|---|---|---|
| all moments | 23478 | 61.0 | 51.2% | 51.9% / 50.5% | +0.4 |
| top 30% most confident | 7044 | 19.0 | 52.0% | 52.6% / 51.1% | +0.5 |
| top 20% | 4696 | 13.8 | 52.1% | 52.5% / 51.6% | -0.1 |
| top 10% | 2348 | 8.9 | 52.8% | 52.0% / 53.8% | -0.3 |
| top 5% | 1174 | 6.5 | 52.0% | 51.0% / 53.2% | -1.5 |

**Classic rules at the same horizon**

| rule | moments | correct |
|---|---|---|
| follow last 5 min | 23462 | 48.7% |
| follow last 15 min | 23474 | 48.1% |
| follow the day so far | 23477 | 49.7% |
| fade last 15 min | 23474 | 51.9% |
| PCR rising -> up (pcr_d30) | 23478 | 49.8% |
| closer to the put wall -> up (wall_pos < 0.5) | 23477 | 49.6% |
| breadth: 8+ advancing -> up | 13946 | 50.0% |
| HDFC & ICICI both led the index up/down in last 5 min | 6975 | 50.2% |
| top-5 banks: majority led up/down in last 5 min | 15426 | 50.1% |

Most useful inputs (last 3 months): skew 0.011, prev_ret 0.010, strad 0.006, pe_oi_d30 0.005, gap 0.005, rv30 0.004, vwap_d 0.003, r60 0.003

### 30 minutes ahead

Base rate (share up): 50.4%. Coin flip = 50%.

| selectivity | moments | per day | direction correct | 1st / 2nd half | avg move our way (pts) |
|---|---|---|---|---|---|
| all moments | 23473 | 61.0 | 50.8% | 50.8% / 50.9% | +0.6 |
| top 30% most confident | 7042 | 19.2 | 52.3% | 51.6% / 53.6% | +2.1 |
| top 20% | 4695 | 14.8 | 52.9% | 51.3% / 55.4% | +2.5 |
| top 10% | 2349 | 10.3 | 54.1% | 52.8% / 56.0% | +2.3 |
| top 5% | 1174 | 8.1 | 55.4% | 56.0% / 54.6% | +2.5 |

**Classic rules at the same horizon**

| rule | moments | correct |
|---|---|---|
| follow last 5 min | 23457 | 48.7% |
| follow last 15 min | 23469 | 48.3% |
| follow the day so far | 23472 | 50.3% |
| fade last 15 min | 23469 | 51.7% |
| PCR rising -> up (pcr_d30) | 23473 | 50.3% |
| closer to the put wall -> up (wall_pos < 0.5) | 23472 | 49.0% |
| breadth: 8+ advancing -> up | 13946 | 50.2% |
| HDFC & ICICI both led the index up/down in last 5 min | 6972 | 50.2% |
| top-5 banks: majority led up/down in last 5 min | 15427 | 50.1% |

Most useful inputs (last 3 months): r30 0.015, wall_pos 0.015, tod 0.015, pcr 0.014, dn_wall_d 0.012, gap 0.011, up_wall_d 0.009, prev_ret 0.008

### 60 minutes ahead

Base rate (share up): 50.2%. Coin flip = 50%.

| selectivity | moments | per day | direction correct | 1st / 2nd half | avg move our way (pts) |
|---|---|---|---|---|---|
| all moments | 23480 | 61.0 | 50.5% | 50.1% / 50.8% | +1.0 |
| top 30% most confident | 7044 | 21.0 | 51.6% | 49.4% / 54.8% | +0.5 |
| top 20% | 4696 | 16.5 | 52.6% | 50.1% / 56.1% | +0.5 |
| top 10% | 2348 | 13.8 | 51.5% | 50.7% / 52.5% | -2.9 |
| top 5% | 1174 | 11.7 | 52.9% | 52.9% / 52.9% | +0.8 |

**Classic rules at the same horizon**

| rule | moments | correct |
|---|---|---|
| follow last 5 min | 23464 | 49.6% |
| follow last 15 min | 23476 | 49.2% |
| follow the day so far | 23479 | 50.2% |
| fade last 15 min | 23476 | 50.8% |
| PCR rising -> up (pcr_d30) | 23480 | 50.1% |
| closer to the put wall -> up (wall_pos < 0.5) | 23479 | 48.9% |
| breadth: 8+ advancing -> up | 13947 | 50.3% |
| HDFC & ICICI both led the index up/down in last 5 min | 6972 | 50.4% |
| top-5 banks: majority led up/down in last 5 min | 15428 | 50.5% |

Most useful inputs (last 3 months): strad 0.018, dn_wall_d 0.014, wall_pos 0.013, pcr 0.012, heavy 0.011, dte 0.009, vwap_d 0.009, rv30 0.008
