"""R26 tables: results/results.json + results/describe.json -> results/report.md

    python3 research/hunt/r26/report.py
"""
from __future__ import annotations

import json

import r26cfg as K26


def f(x, d=0):
    return "—" if x is None else (f"{x:+,.{d}f}" if isinstance(x, (int, float)) else str(x))


def main():
    r = json.loads((K26.RESULTS / "results.json").read_text())
    ds = json.loads((K26.RESULTS / "describe.json").read_text())
    R = r["rules"]
    L = ["# R26 tables: the OI wall blast framework on every expiry of the lake", "",
         f"Expiries: {r['n_expiries']} ({', '.join(f'{k} {v}' for k, v in r['expiries'].items())}); R25 blocks "
         f"{', '.join(f'{k} {v}' for k, v in r['blocks'].items())}. Budget Rs 2,000 a trade. BH family {r['bh_size']} "
         "(R21 9 + R25 9 + R26 10).", "",
         "## Every rule, all expiries", "",
         "| rule | trades (UP / DOWN) | hit % | ≥ 2× % | ≥ 4× % | avg win / loss | net Rs | per expiry | per Rs 1,000 risked | green expiries % | worst day | best day | max DD | losing streak | best day's share | net w/o best day | 2× spread | out-of-range 0 | twin mean (5-95%) | p | q | passes |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for k, v in R.items():
        m, tw = v["m"], v["twins"]
        L.append(f"| {k} | {m['trades']} ({m['up']} / {m['down']}) | {m['hit_pct']} | {m['x2_pct']} | {m['x4_pct']} | "
                 f"{f(m['avg_win'])} / {f(m['avg_loss'])} | **{f(m['net'])}** | {f(m['per_expiry'], 1)} | {f(m['per_1000_risked'])} | "
                 f"{m['green_expiries_pct']} | {f(m['worst_day'])} | {f(m['best_day'])} | {f(m['max_dd'])} | {m['losing_streak_trades']} | "
                 f"{m['best_day_share_pct'] if m['best_day_share_pct'] is not None else '—'}% | {f(m['net_without_best_day'])} | "
                 f"{f(v['x2_spread'])} | {f(v['oor_zero'])} | {f(tw['mean'])} ({f(tw['p05'])} .. {f(tw['p95'])}) | {tw['p']} | {v['q']} | "
                 f"{'yes' if v['passes'] else 'no'} |")
    L += ["", "Notes: 'green expiries %' is over all the rule's expiries (most have no trade). Hit = net > 0. ≥ 2× / ≥ 4× = a "
          "confirmed high before the exit. Twin = random-time twins (same expiry, direction, strike rule, filter, size, exit; 1,000 sets).", "",
          "## Gates", "", "| rule | " + " | ".join(next(iter(R.values()))["gates"].keys()) + " |",
          "|---|" + "---|" * len(next(iter(R.values()))["gates"])]
    for k, v in R.items():
        L.append(f"| {k} | " + " | ".join("pass" if g else "FAIL" for g in v["gates"].values()) + " |")
    L += ["", "## R25 blocks and years (trades, net Rs)", "",
          "| rule | 2021-22 | 2023-26 | " + " | ".join(str(y) for y in range(2021, 2027)) + " |", "|---|---|---|" + "---|" * 6]
    for k, v in R.items():
        b, y = v["blocks"], v["years"]
        L.append(f"| {k} | " + " | ".join(f"{b[x]['trades']} / {f(b[x]['net'])}" for x in ("2021-22", "2023-26")) + " | "
                 + " | ".join((f"{y[str(yy)]['trades']} / {f(y[str(yy)]['net'])}" if str(yy) in y else "—") for yy in range(2021, 2027)) + " |")
    L += ["", "## By index and direction (trades, net Rs)", "", "| rule | NIFTY | BANKNIFTY | SENSEX | FINNIFTY | UP (CE) | DOWN (PE) |",
          "|---|---|---|---|---|---|---|"]
    for k, v in R.items():
        ix, dr = v["index"], v["direction"]
        L.append(f"| {k} | " + " | ".join((f"{ix[u]['trades']} / {f(ix[u]['net'])}" if u in ix else "—") for u in K26.UNDS)
                 + " | " + " | ".join(f"{dr[x]['trades']} / {f(dr[x]['net'])}" for x in ("UP (CE)", "DOWN (PE)")) + " |")
    L += ["", "## Exit grid (the rule's own entries with each exit, net Rs)", "", "| rule | E1 15:05 | E1b 15:20 | E2 4× | E3 2× + step lock |",
          "|---|---|---|---|---|"]
    for k, v in R.items():
        g = v["exit_grid"]
        L.append(f"| {k} | " + " | ".join(f(g[e]) for e in ("E1", "E1b", "E2", "E3")) + " |")
    e = r["b1_extra"]
    L += ["", f"**Baseline (b):** B1 (no OI conditions) took {e['trades']} trades on expiries P did not trade: net {f(e['net'])} "
          f"(hit {e['hit_pct']}%).", "", "## P's trades", "",
          "| index | day | side | strike | signal | entry | lots | risked | peak × | exit | net |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for t in R["P"]["trades_list"]:
        L.append(f"| {t['und']} | {t['day']} | {t['side']} | {t['strike']:g} | {t['signal']} | {t['entry']} | {t['lots']} | "
                 f"{t['risked']:,.0f} | {t['peak_mult']} | {t['exit']} | {f(t['net'])} |")
    L += ["", "## A3's trades (no price filter)", "",
          "| index | day | side | strike | signal | entry | lots | risked | peak × | exit | net |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for t in R["A3"]["trades_list"]:
        L.append(f"| {t['und']} | {t['day']} | {t['side']} | {t['strike']:g} | {t['signal']} | {t['entry']} | {t['lots']} | "
                 f"{t['risked']:,.0f}{' (1 lot over budget)' if t['over_budget'] else ''} | {t['peak_mult']} | {t['exit']} | {f(t['net'])} |")
    w = ds["walls"]
    L += ["", "## Descriptive check", "",
          f"**Walls at 13:00** ({w['n']} expiry-sides): inside the stored range {w.get('inside', 0)}, the outermost stored strike "
          f"(the true wall may be outside) {w.get('outermost', 0)}, no OI at all {w.get('no wall', 0)}.", "",
          "**Funnel** (expiry-sides on which the step happened at some bar close 13:45-14:30; P's conditions, no price filter):", "",
          "| step | all | " + " | ".join(K26.UNDS) + " |", "|---|---|" + "---|" * len(K26.UNDS)]
    for k, v in ds["funnel"].items():
        L.append(f"| {k} | {v} | " + " | ".join(str(ds['funnel_by_index'][u].get(k, 0)) for u in K26.UNDS) + " |")
    L += ["", "**Blasts** (ATM±1 at the 13:45 close reaching a confirmed 4× before 15:05) and the OI over the move "
          "(13:45 → the first 4× minute; non-blasts 13:45 → 15:00):", "", "| group | n | wall OI down ≥ θ | down < θ | rising / flat | no OI | own-strike OI down ≥ θ | down < θ | rising / flat |",
          "|---|---|---|---|---|---|---|---|---|"]
    for g in ("blast", "no blast"):
        a, o = ds["blast_wall_oi"][g], ds["blast_own_oi"][g]
        L.append(f"| {g} | {a['n']} | {a.get('falling >= theta', 0)} | {a.get('falling < theta', 0)} | {a.get('rising or flat', 0)} | "
                 f"{a.get('no OI', 0)} | {o.get('falling >= theta', 0)} | {o.get('falling < theta', 0)} | {o.get('rising or flat', 0)} |")
    L += ["", "**Point-in-time at 13:45** (the wall's OI vs its peak since 12:00) and the blast rate after it:", "",
          "| wall OI at 13:45 | expiry-sides | blasts | rate % |", "|---|---|---|---|"]
    for k, v in ds["wall_oi_at_1345_vs_blast"].items():
        L.append(f"| {k.replace('rising or flat', 'at its peak')} | {v['n']} | {v['blast']} | {v['rate_pct']} |")
    L += ["", f"Blasts: {len(ds['blasts'])}; after a held wall break in the window: {sum(b['broke'] for b in ds['blasts'])}; "
          f"with the full trigger (no price filter): {sum(b['trigger'] for b in ds['blasts'])}.", "",
          "## BH family", "", "| id | p | q |", "|---|---|---|"]
    for k, v in r["bh_family"].items():
        L.append(f"| {k} | {v['p']} | {v['q']} |")
    (K26.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    print("\n".join(L[:40]))


if __name__ == "__main__":
    main()
