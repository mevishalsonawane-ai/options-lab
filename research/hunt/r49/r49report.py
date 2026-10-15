"""R49 report tables (results/report.md) from results/design.json + results/final.json, with R3's full-data numbers
(research/hunt/r3orb/results) as the OLD verdict.

    python3 research/hunt/r49/r49study.py report
"""
from __future__ import annotations

import json

import r49cfg as K
import r49engine as R
import r3cfg as K3

SCOPES = ("pooled",) + K.UNDS
R3_ID = {"r49-orb": "base-orb", "r49-orb-fresh": "base-orb_fresh", "r49-orb-sweep": "base-orb_sweep",
         "r49-range-fade": "base-range_fade"}
for _a, _aid in R.ARM_ID.items():
    for _x in "FESC":
        R3_ID[f"r49-r3-{_aid}-{_x.lower()}"] = f"r3-{_a}-{_x}"
# R23's release numbers (HUNT_R23_ORB_RANGE.md T1: BANKNIFTY + NIFTY, Feb 2024 - Apr 2026, all design data)
R23_OLD = {"r49-r23-r22-f2": (390, 16158), "r49-r23-r22-f1": (472, 15593), "r49-r23-orb-f2": (349, -12461)}
NAME = {"orb": "ORB", "orb_fresh": "ORB Fresh", "orb_sweep": "ORB Sweep", "range_fade": "Range Fade"}


def load():
    des = json.loads((K.RESULTS / "design.json").read_text())
    fin = json.loads((K.RESULTS / "final.json").read_text())
    r3d = json.loads((K3.RESULTS / "design.json").read_text())["results"]
    r3h = json.loads((K3.RESULTS / "holdout.json").read_text())["results"]
    r3r = json.loads((K3.RESULTS / "rebuild.json").read_text())
    return des, fin, r3d, r3h, r3r


def old(rid: str, scope: str, r3d, r3h, r3r) -> str:
    """R3's full-data verdict (2021-2026): trades and net Rs; '-' when R3 did not run the rule."""
    if rid in R23_OLD:
        n, net = R23_OLD[rid]
        return f"{n:,} / {net:+,.0f} (release, BN+NIFTY)" if scope == "pooled" else "-"
    if rid == "r49-orb-uncapped":
        x = r3r["orb_uncapped"]
        if scope == "pooled":
            return f"{x['all']['trades']:,} / {x['all']['net']:+,.0f} (BN+NIFTY)"
        return f"{x[scope]['trades']:,} / {x[scope]['net']:+,.0f}" if scope in x else "-"
    i = R3_ID.get(rid)
    if i is None:
        return "-"
    if scope == "pooled":
        a, b = r3d[i]["all"], r3h[i]["all"]
        return f"{a['trades'] + b['trades']:,} / {a['net'] + b['net']:+,.0f}"
    src = r3d[i] if scope in r3d[i] else r3h[i]
    return f"{src[scope]['trades']:,} / {src[scope]['net']:+,.0f}" if scope in src else "-"


def fmt_now(x):
    return f"{x['trades']:,} | {x['win_rate']:.0%} | {x['net']:+,.0f} | {x['per_day']:+,.0f}"


def fmt_fin(x):
    return (f"{x['trades']:,} | {x['win_rate']:.0%} | {x['net']:+,.0f} | {x['per_day']:+,.0f} | "
            f"{x['avg_win']:+,.0f} / {x['avg_loss']:+,.0f} | {x['worst_day']:+,.0f} | {x['max_dd']:+,.0f} | "
            f"{x['twin_p']:.3f} | {x['q']:.2f}")


HEAD = ("| rule | scope | OLD 2021-26 trades / net | NOW design trades | win | net | net/day | FINAL trades | win | net | "
        "net/day | avg win / loss | worst day | max DD | p | q | verdict |\n|" + "---|" * 17)


def gates_str(x):
    g = x["gates"]
    return "".join("y" if g[k] else "n" for k in ("net", "p", "q", "trades", "top5", "design"))


def row(rid, sc, des, fin, r3d, r3h, r3r):
    d, f = des["results"][rid][sc], fin["results"][rid][sc]
    return (f"| `{rid}` | {sc} | {old(rid, sc, r3d, r3h, r3r)} | {fmt_now(d)} | {fmt_fin(f)} | "
            f"{f['verdict']} ({gates_str(f)}) |")


def main():
    des, fin, r3d, r3h, r3r = load()
    rs = R.rules()
    md = ["# R49 results: the ORB family on 20 Nov 2024 - 13 Oct 2026 only\n",
          f"Design 21 Nov 2024 - 31 Mar 2026; final test 1 Apr - 13 Oct 2026, read once ({fin['read']}). Rs per 1 lot of "
          "the day's contract after dated charges and liq fills. p = random-entry twins (500); q = Benjamini-Hochberg over "
          f"m = {fin['m']} (56 rules x 6 scopes). Gates in the verdict: net, p, q, 40+ trades, top-5, design.\n",
          "OLD = R3 on all five indices 2021-2026 (BANKNIFTY + NIFTY design, FINNIFTY + MIDCPNIFTY + SENSEX unseen), at "
          "R3's charges (today's rates); R23 rows: R23's release numbers.\n"]
    passes = [(r.id, sc) for r in rs for sc in SCOPES if fin["results"][r.id][sc]["verdict"] == "PASS"]
    md.append(f"**Passes: {len(passes)} of {fin['m']}.** " + (", ".join(f"`{a}` {b}" for a, b in passes) or "none") + "\n")
    for arm in R.ARMS:
        md.append(f"\n## {NAME[arm]}\n\n{HEAD}")
        old_id = f"r49-{R.ARM_ID[arm]}"
        for sc in SCOPES:
            md.append(row(old_id, sc, des, fin, r3d, r3h, r3r))
        extra = [r for r in rs if r.arm == arm and r.id != old_id and (r.group != "R23")]
        if arm == "orb":
            extra += [r for r in rs if r.group == "R23"]
        for r in extra:
            md.append(row(r.id, "pooled", des, fin, r3d, r3h, r3r))
        wf = des["walk_forward"][arm]
        md.append(f"\nRe-tune (design only): pick and walk-forward net by scope: " + "; ".join(
            f"{sc} `{wf[sc]['retuned_pick']}` (design {wf[sc]['retuned_design_net']:+,}, walk-forward {wf[sc]['net']:+,}, "
            f"final {fin['results'][wf[sc]['retuned_pick']][sc]['net']:+,.0f})" for sc in SCOPES))
    md.append("\n## Every rule on every index (final test)\n\n| rule | " + " | ".join(SCOPES) + " |\n|---|" + "---|" * len(SCOPES))
    for r in rs:
        md.append(f"| `{r.id}` | " + " | ".join(
            f"{fin['results'][r.id][sc]['trades']} / {fin['results'][r.id][sc]['net']:+,.0f} / p {fin['results'][r.id][sc]['twin_p']:.2f}"
            for sc in SCOPES) + " |")
    md.append("\n## Every rule on every index (design)\n\n| rule | " + " | ".join(SCOPES) + " |\n|---|" + "---|" * len(SCOPES))
    for r in rs:
        md.append(f"| `{r.id}` | " + " | ".join(
            f"{des['results'][r.id][sc]['trades']} / {des['results'][r.id][sc]['net']:+,.0f}" for sc in SCOPES) + " |")
    (K.RESULTS / "report.md").write_text("\n".join(md) + "\n")
    print("\n".join(md[:4]))
    print(passes)


if __name__ == "__main__":
    main()
