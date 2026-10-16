"""R71 report: results/design.json + results/final.json -> results/report.md (and the rows HUNT_R71 quotes). Pure
rendering; `write()` reads and writes the two files.
"""
from __future__ import annotations

import json
from pathlib import Path

import r71cfg as R
import r71rules as P

EXL = {"XB": "-15% / +30% / 30 min", "XL": "-15% + ladder + L25, 30 min", "XI": "index stop + ladder + L25, 60 min"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(float(x))
    s = f"{abs(x):,}"
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.0f}%"


def setting(rule: str, gp: str) -> str:
    p, ex = gp.split("|")
    return f"{P.RULES[rule][1]}={p}, {ex}"


def final_table(fin: dict, des: dict) -> list:
    rows = ["| test | setting | final trades | final net | win | avg win / loss | worst day | max DD | green days | "
            "trades / month | design net | walk-forward net | twin p | BH q | twin mean (no skill) | corr. with Donchian | "
            "same trade as Donchian | verdict |",
            "|---" * 18 + "|"]
    for k in [f"{r}-{x}" for r in P.RULES for x in ("O", "RT")]:
        t = fin["tests"][k]
        s = t["final"]
        rows.append(f"| {k} {P.NAMES[t['rule']]} | {setting(t['rule'], t['setting'])} | {s['trades']} | {rs(s['net'])} | "
                    f"{pct(s.get('win'))} | {rs(s.get('avg_win'))} / {rs(s.get('avg_loss'))} | {rs(s.get('worst_day'))} | "
                    f"{rs(s.get('max_dd'))} | {pct(s.get('green_days'))} | {s.get('per_month')} | {rs(t['design_net'])} | "
                    f"{rs(t['wf_net'])} | {t['p']} | {t['q']} | {rs(t['twin_mean'])} | {t['corr_donchian']} | "
                    f"{pct(t['overlap_donchian'])} | {t['verdict']}: {', '.join(t['fails']) or 'all gates'} |")
    return rows


def donchian_rows(fin: dict, des: dict) -> list:
    rows = ["| Donchian 20 (reference, not gated) | design trades | design net | final trades | final net | win | "
            "avg win / loss | worst day | max DD | green days | trades / month |", "|---" * 11 + "|"]
    for ex in ("XB", "XL"):
        d, f = des["donchian"][ex]["design"], fin["donchian"][ex]
        rows.append(f"| {ex} {EXL[ex]} | {d['trades']} | {rs(d['net'])} | {f['trades']} | {rs(f['net'])} | "
                    f"{pct(f['win'])} | {rs(f['avg_win'])} / {rs(f['avg_loss'])} | {rs(f['worst_day'])} | "
                    f"{rs(f['max_dd'])} | {pct(f['green_days'])} | {f['per_month']} |")
    return rows


def grid_rows(des: dict) -> list:
    rows = ["| rule | grid point | design trades | design net | win | worst day | max DD | fold nets (Apr-Jun 25, Jul-Sep, "
            "Oct-Dec, Jan-Mar 26) |", "|---" * 8 + "|"]
    for rule, r in des["rules"].items():
        for gp, v in r["grid"].items():
            s = v["design"]
            mark = " (original)" if gp == r["original"] else ""
            mark += " (final pick)" if gp == r["final_pick"] else ""
            rows.append(f"| {rule} | {setting(rule, gp)}{mark} | {s['trades']} | {rs(s['net'])} | {pct(s.get('win'))} | "
                        f"{rs(s.get('worst_day'))} | {rs(s.get('max_dd'))} | {' / '.join(rs(x) for x in v['folds'])} |")
    return rows


def wf_rows(des: dict) -> list:
    rows = ["| rule | walk-forward picks (4 folds) | walk-forward trades | walk-forward net | final pick |",
            "|---" * 5 + "|"]
    for rule, r in des["rules"].items():
        rows.append(f"| {rule} {r['name']} | {', '.join(r['wf_picks'])} | {r['wf']['trades']} | {rs(r['wf']['net'])} | "
                    f"{r['final_pick']} |")
    return rows


def combined_rows(fin: dict) -> list:
    a = fin["donchian_alone"]
    rows = ["| book (final, 1 lot each) | net | max DD | worst day |", "|---|---|---|---|",
            f"| Donchian XB alone | {rs(a['net'])} | {rs(a['max_dd'])} | {rs(a['worst_day'])} |"]
    for k, t in fin["tests"].items():
        c = t["with_donchian"]
        rows.append(f"| Donchian + {k} | {rs(c['net'])} | {rs(c['max_dd'])} | {rs(c['worst_day'])} |")
    return rows


def render(des: dict, fin: dict) -> str:
    passed = [k for k, t in fin["tests"].items() if t["verdict"] == "PASS"]
    out = ["# R71 report: chart patterns on the index (tables)", "",
           f"Final period 1 Apr - {fin['end']} ({fin['sessions']} sessions), read once at {fin['read']}. "
           f"Tests gated: {len(fin['tests'])} (BH across all). Passed: {', '.join(passed) or 'none'}.", "",
           "## Final period, every gated test", ""] + final_table(fin, des) + \
          ["", "## Donchian on the same days (same engine, stores and costs)", ""] + donchian_rows(fin, des) + \
          ["", "## Combined with Donchian (final)", ""] + combined_rows(fin) + \
          ["", "## Walk-forward (design)", ""] + wf_rows(des) + \
          ["", "## Every design grid point", ""] + grid_rows(des) + [""]
    return "\n".join(out)


def write():
    des = json.loads((R.RESULTS / "design.json").read_text())
    fin = json.loads((R.RESULTS / "final.json").read_text())
    (R.RESULTS / "report.md").write_text(render(des, fin))
    print("wrote", R.RESULTS / "report.md")


if __name__ == "__main__":
    write()
