"""R113 report: results/*.json -> results/report.md (every table) and two small PNG charts.

    python research/hunt/r113_time_of_day/r113report.py

26 Oct 2026.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import r113cfg as K  # noqa: E402

TITLES = {"entry_b": "entry time", "exit_b": "exit time", "hold_b": "holding time", "weekday": "weekday",
          "expiry_b": "expiry day", "gap_b": "opening gap", "vix_b": "VIX (previous close) tercile",
          "body_b": "day shape (after the fact)"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = float(x)
    s = f"{abs(x):,.0f}"
    return ("-" if x < 0 else "") + "Rs " + s


def table(rows: list) -> str:
    out = ["| bucket | trades | win | net | avg | 90% band |", "|---|---:|---:|---:|---:|---|"]
    for r in rows:
        flag = " (small)" if r["small"] else (" **sure**" if r["sure"] else "")
        out.append(f"| {r['bucket']}{flag} | {r['trades']} | "
                   f"{'-' if r['win'] is None else f'{r['win'] * 100:.0f}%'} | {rs(r['net'])} | {rs(r['avg'])} | "
                   f"{rs(r['band'][0])} .. {rs(r['band'][1])} |")
    return "\n".join(out)


def load(name):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else None


def charts(desc: dict, order: list) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np

    nse = [b[0] for b in K.NSE_BUCKETS]
    arms = [a for a in order if desc[a].get("common", {}).get("entry_b") and
            desc[a]["common"]["entry_b"][0]["bucket"] in nse and sum(r["trades"] for r in desc[a]["common"]["entry_b"]) >= 30]
    if arms:
        m = np.array([[r["avg"] if r["trades"] >= K.SMALL_N else np.nan for r in desc[a]["common"]["entry_b"]]
                      for a in arms], float)
        fig, ax = plt.subplots(figsize=(8, 0.38 * len(arms) + 1.4), dpi=80)
        lim = np.nanpercentile(np.abs(m), 95) if np.isfinite(m).any() else 1
        im = ax.imshow(m, cmap="RdYlGn", vmin=-lim, vmax=lim, aspect="auto")
        ax.set_xticks(range(len(nse)), nse, rotation=30, ha="right", fontsize=8)
        ax.set_yticks(range(len(arms)), [K.ARM_NAME.get(a, a) for a in arms], fontsize=8)
        for i in range(m.shape[0]):
            for j in range(m.shape[1]):
                if np.isfinite(m[i, j]):
                    ax.text(j, i, f"{m[i, j]:.0f}", ha="center", va="center", fontsize=6)
        ax.set_title("Average net per trade (Rs) by entry time, 20 Nov 2024 - 13 Oct 2026 (blank: < 30 trades)", fontsize=8)
        fig.colorbar(im, ax=ax, shrink=0.7)
        fig.tight_layout()
        fig.savefig(K.RESULTS / "entry_heatmap.png")
        plt.close(fig)
    core = [a for a in K.CORE_ARMS if a in desc]
    if core:
        fig, axs = plt.subplots(1, len(core), figsize=(3 * len(core), 2.8), dpi=80, sharey=False)
        for ax, a in zip(np.atleast_1d(axs), core):
            for k, (per, col) in enumerate((("design", "#4a7bb7"), ("final", "#e08a3c"))):
                rows = desc[a].get(per, {}).get("entry_b", [])
                ax.bar(np.arange(len(rows)) + (k - 0.5) * 0.4, [r["net"] / 1000 for r in rows], 0.4, color=col, label=per)
            ax.set_xticks(range(len(nse)), [b[:5] for b in nse], rotation=60, fontsize=7)
            ax.axhline(0, color="k", lw=0.6)
            ax.set_title(K.ARM_NAME[a], fontsize=8)
            ax.tick_params(axis="y", labelsize=7)
        np.atleast_1d(axs)[0].set_ylabel("net, Rs thousand", fontsize=7)
        np.atleast_1d(axs)[0].legend(fontsize=6)
        fig.tight_layout()
        fig.savefig(K.RESULTS / "core_entry_bars.png")
        plt.close(fig)


def main() -> None:
    d = load("describe.json")
    ov = load("overview.json") or {}
    cr = load("cross.json") or {}
    desc = d["arms"]
    order = [a for a in K.ARM_NAME if a in desc] + sorted(a for a in desc if a not in K.ARM_NAME)
    L = ["# R113 results: every table", "", f"VIX tercile cuts (design days): {d['vix_cuts']}", ""]
    L += ["## Overview", "", "| arm | trades | net | win | first | last | first-hour share | top-10% days share |",
          "|---|---:|---:|---:|---|---|---:|---:|"]
    for a in order:
        o = ov.get(a, {})
        fh = o.get("first_hour_share")
        t10 = o.get("top10_days")
        L.append(f"| {K.ARM_NAME.get(a, a)} | {o.get('trades')} | {rs(o.get('net'))} | {o.get('win', 0) * 100:.0f}% | "
                 f"{o.get('first')} | {o.get('last')} | {'-' if fh is None else f'{fh * 100:.0f}%'} | "
                 f"{'-' if t10 is None else f'{t10 * 100:.0f}%'} |")
    for a in order:
        L += ["", f"## {K.ARM_NAME.get(a, a)} (`{a}`)", ""]
        for per in ("design", "final", "all", "common"):
            if not desc[a].get(per):
                continue
            L += [f"### {per}", ""]
            for col, t in TITLES.items():
                rows = desc[a][per].get(col) or []
                if rows and any(r["trades"] for r in rows):
                    L += [f"**by {t}**", "", table(rows), ""]
    if cr:
        L += ["## Cross-arm", "", "### Daily P&L correlation (|r| >= 0.2 shown)", ""]
        for per, m in cr.get("corr", {}).items():
            big = sorted(((k, v) for k, v in m.items() if v[0] is not None and abs(v[0]) >= 0.2), key=lambda x: -abs(x[1][0]))
            L += [f"**{per}**: " + (", ".join(f"{k} {v[0]:+.2f} (n {v[1]})" for k, v in big) or "none at |r| >= 0.2"), ""]
        L += ["### Shared days (four paper index arms)", "", "```", json.dumps(cr.get("shared_days"), indent=1), "```"]
    for name in ("partD_select.json", "partD_design.json", "partD_final.json", "paper.json"):
        x = load(name)
        if x:
            L += ["", f"## {name}", "", "```", json.dumps(x, indent=1)[:6000], "```"]
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    charts(desc, order)


if __name__ == "__main__":
    main()
