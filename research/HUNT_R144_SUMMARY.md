# R144 summary: Benjamini-Hochberg across all four groups (coordinator, 2 Nov 2026)

Each group (R144a-d) applied BH within itself. This table puts every test's raw p-value from the six results/pvalues.json
files together (22 tests) and applies BH across all of them. A filter that passes within its group but has q > 0.10 here
is reported as not passing across all. Where a file gave both a final-period and an unseen-period p, the group's own
'p_used' (its pre-registered deciding p) is used. Research only; nothing here changes the app.

| group | test | arm | raw p | BH q (all groups) | passes across all (q <= 0.10) |
|---|---|---|---|---|---|
| r144a_fresh_2021 | FB-R72-A10 | R72 | 2.053e-05 | 0.000452 | yes |
| r144a_fresh_2021 | R144A-72-V4 | R72 | 0.0004998 | 0.0055 | yes |
| r144c_loss_expiry_other | r144c-night-vix-prev-high |  | 0.002999 | 0.022 | yes |
| r144c_loss_expiry_other | r144c-herotkt-morning-range-low |  | 0.01549 | 0.0852 | yes |
| r144a_fresh_2021 | NP5-52 | R52 | 0.02699 | 0.119 | no |
| r144c_loss_expiry_other | r144c-ft3-quadrant-cat |  | 0.04898 | 0.18 | no |
| r144a_loss_breakout | R144A-51-F4 | R51 | 0.07196 | 0.226 | no |
| r144c_loss_expiry_other | r144c-herotkt-iv-rel-high |  | 0.1364 | 0.375 | no |
| r144c_loss_expiry_other | r144c-night-vix-chg-high |  | 0.2189 | 0.483 | no |
| r144a_fresh_2021 | R144A-51-F4 | R51 | 0.2194 | 0.483 | no |
| r144a_loss_breakout | R144A-72-V4 | R72 | 0.2724 | 0.545 | no |
| r144a_loss_breakout | R144A-72-Q2 | R72 | 0.4673 | 0.762 | no |
| r144c_loss_expiry_other | r144c-ft3-vix-chg-low |  | 0.4768 | 0.762 | no |
| r144c_loss_expiry_other | r144c-vixdiv-spread-proxy-high |  | 0.5082 | 0.762 | no |
| r144a_fresh_2021 | BBX-72 | R72 | 0.5197 | 0.762 | no |
| r144c_loss_expiry_other | r144c-vixdiv-iv-rel-high |  | 0.5602 | 0.77 | no |
| r144c_loss_expiry_other | r144c-ft3-iv-rel-high |  | 0.6892 | 0.853 | no |
| r144a_loss_breakout | R144A-51-F2 | R51 | 0.6982 | 0.853 | no |
| r144c_loss_expiry_other | r144c-vixdiv-gap-low |  | 0.7466 | 0.865 | no |
| r144c_loss_expiry_other | r144c-herotkt-oi-chg-low |  | 0.8266 | 0.909 | no |
| r144c_loss_expiry_other | r144c-night-trend-high |  | 0.8851 | 0.927 | no |
| r144a_fresh_2021 | BBX-51 | R51 | 0.985 | 0.985 | no |

Reading it:
- The lose-less gates (losing days, drawdown, worst day and month no worse, still in profit) are judged in each group's
  write-up; a low q here is necessary but not sufficient.
- FB-R72-A10 (R72 skip after a very wide day) and NP5-52 (R52 09:25 no-progress exit) are the retests on the fresh year
  Nov 2021 - Nov 2022 (R144a); both passed their gates in every period tested. Every other filter failed its own gates.
- Across all groups NP5-52 has q 0.119 (just above 0.10); R144A-72-V4 and r144c-night-vix-prev-high have low q here but each
  failed its own lose-less gates in another period (see their write-ups), so neither passes.
