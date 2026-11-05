"""R150 chart (PREREG section 2): every 3-month support / resistance level at once, per index, to today.

    python -m research.hunt.r150_sr_levels.chart        # results/levels_<UND>.png, results/levels_all.png, results/levels_now.json

Data: the lake's 1-minute index bars to its end (16 Oct 2026), then Yahoo 15-minute bars (fetched to
~/.cache/iraalgo/r150/y15_<UND>.csv) for the sessions after it. Levels: levels.py, known at today's last bar.
"""
from __future__ import annotations

import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from research.hunt.r147_night_plus import data as D7  # noqa: E402

from . import levels as V  # noqa: E402

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
Y15 = Path.home() / ".cache" / "iraalgo" / "r150"
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
# the reference palette's first two categorical slots (validated adjacent pair), text inks, surface
SUPPORT, RESIST, INK, INK2, GRID, SURFACE = "#2a78d6", "#eb6834", "#0b0b0b", "#52514e", "#e6e5e0", "#fcfcfb"


def day_arrays(und: str) -> tuple[list, list]:
    """(dates, (4, 375) arrays): the lake's days, then Yahoo's 15-minute bars as one-minute rows at each bar's start."""
    lake = D7.index_days(und)
    dates = sorted(lake)
    arrs = [lake[d].T for d in dates]                 # lake grids are (375, 4) -> (4, 375)
    y = pd.read_csv(Y15 / f"y15_{und}.csv", parse_dates=["ts"])
    y = y[(y.ts.dt.hour * 60 + y.ts.dt.minute) <= 15 * 60 + 15]
    for d, g in y.groupby(y.ts.dt.date):
        if d <= dates[-1]:
            continue
        a = np.full((4, V.N), np.nan)
        for r in g.itertuples():
            m = r.ts.hour * 60 + r.ts.minute - (9 * 60 + 15)
            a[:, m] = [r.open, r.high, r.low, r.close]
        dates.append(d)
        arrs.append(a)
    return dates, arrs


def levels_today(und: str, k: int = 12) -> dict:
    dates, arrs = day_arrays(und)
    b = V.bars15(arrs)
    piv = V.pivots(b["h"], b["l"], k)
    last_day = len(dates) - 1
    lv = V.levels_at(b, piv, last_day, V.N, window=V.WINDOW)
    first_day = max(0, last_day - V.WINDOW + 1)
    m = b["day"] >= first_day
    return {"und": und, "k": k, "dates": dates, "bars": {k: v[m] for k, v in b.items()}, "first_day": first_day,
            "last": float(b["c"][-1]), "levels": lv}


def draw(ax, L: dict) -> None:
    b = L["bars"]
    x = np.arange(len(b["c"]))
    last = L["last"]
    ax.plot(x, b["c"], color=INK, lw=1.0, zorder=2)
    ax.scatter([x[-1]], [last], s=18, color=INK, zorder=3)
    strong = []
    for lv, n in L["levels"]:
        if n < 2:
            continue
        col = RESIST if lv > last else SUPPORT
        if n >= 3:
            ax.plot([0, x[-1]], [lv, lv], color=col, lw=1.6, zorder=1, solid_capstyle="round")
            strong.append((lv, n, col))
        else:
            ax.plot([0, x[-1]], [lv, lv], color=col, lw=0.8, alpha=0.4, ls="--", zorder=1)
    lo, hi = min(np.nanmin(b["l"]), min([s[0] for s in strong], default=last)), max(np.nanmax(b["h"]), max([s[0] for s in strong], default=last))
    pad = (hi - lo) * 0.04
    ax.set_ylim(lo - pad, hi + pad)
    # labels in the right margin, nudged apart so none overlaps (the line runs to the label's left)
    gap = (hi - lo + 2 * pad) * 0.028
    ys = []
    for lv, n, col in sorted(strong):
        y = lv if not ys or lv - ys[-1] >= gap else ys[-1] + gap
        ys.append(y)
        ax.plot([x[-1], x[-1] + 8], [lv, y], color=col, lw=0.8, zorder=1)
        ax.text(x[-1] + 10, y, f"{lv:,.0f}  ×{n}", va="center", fontsize=7.5, color=INK2)
    dates, days = L["dates"], b["day"]
    first_of = {}
    for i, di in enumerate(days):
        first_of.setdefault(di, i)
    keys = sorted(first_of)[::10]
    ax.set_xticks([first_of[k] for k in keys])
    ax.set_xticklabels([dates[k].strftime("%d %b") for k in keys], fontsize=7.5, color=INK2)
    ax.tick_params(axis="y", labelsize=7.5, colors=INK2)
    ax.grid(axis="y", color=GRID, lw=0.6)
    ax.set_xlim(-2, len(x) + 95)
    for sp in ("top", "right"):
        ax.spines[sp].set_visible(False)
    for sp in ("left", "bottom"):
        ax.spines[sp].set_color(GRID)
    n3 = sum(1 for _, n in L["levels"] if n >= 3)
    n2 = sum(1 for _, n in L["levels"] if n == 2)
    kind = "major levels (turns held 3 hours each side)" if L["k"] == 12 else "dense levels (turns held 1 hour each side)"
    ax.set_title(f"{L['und']}, {kind}: {dates[L['first_day']]:%d %b} – {dates[-1]:%d %b %Y}, last 15-min close {last:,.0f} · "
                 f"{n3} levels with 3+ turns (solid), {n2} with 2 (dashed)", fontsize=9, color=INK, loc="left")


def legend(fig) -> None:
    from matplotlib.lines import Line2D
    h = [Line2D([], [], color=RESIST, lw=1.6, label="resistance (above today's price), 3+ turns"),
         Line2D([], [], color=SUPPORT, lw=1.6, label="support (below today's price), 3+ turns"),
         Line2D([], [], color=INK2, lw=0.8, ls="--", alpha=0.6, label="2 turns (fainter)"),
         Line2D([], [], color=INK, lw=1.0, label="index, 15-minute close")]
    fig.legend(handles=h, loc="upper center", ncol=4, fontsize=8, frameon=False)


def main() -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    now = {}
    allL = []
    for u in UNDS:
        dense = levels_today(u, V.K_SIDE)
        fig, ax = plt.subplots(figsize=(13, 6.5), dpi=150, facecolor=SURFACE)
        ax.set_facecolor(SURFACE)
        draw(ax, dense)
        legend(fig)
        fig.tight_layout(rect=(0, 0, 1, 0.95))
        fig.savefig(RESULTS / f"levels_dense_{u}.png", facecolor=SURFACE)
        plt.close(fig)
        L = levels_today(u, 12)
        allL.append(L)
        now[u] = {"dense_3plus": sum(1 for _, n in dense["levels"] if n >= 3), "as_of": str(L["dates"][-1]), "last": round(L["last"], 2),
                  "levels": [{"level": round(lv, 2), "turns": n, "role": "resistance" if lv > L["last"] else "support"}
                             for lv, n in L["levels"] if n >= 2]}
        fig, ax = plt.subplots(figsize=(13, 6.5), dpi=150, facecolor=SURFACE)
        ax.set_facecolor(SURFACE)
        draw(ax, L)
        legend(fig)
        fig.tight_layout(rect=(0, 0, 1, 0.95))
        fig.savefig(RESULTS / f"levels_{u}.png", facecolor=SURFACE)
        plt.close(fig)
    fig, axs = plt.subplots(5, 1, figsize=(13, 26), dpi=110, facecolor=SURFACE)
    for ax, L in zip(axs, allL):
        ax.set_facecolor(SURFACE)
        draw(ax, L)
    legend(fig)
    fig.tight_layout(rect=(0, 0, 1, 0.985))
    fig.savefig(RESULTS / "levels_all.png", facecolor=SURFACE)
    plt.close(fig)
    (RESULTS / "levels_now.json").write_text(json.dumps(now, indent=1))


if __name__ == "__main__":
    main()
