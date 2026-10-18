"""R78 study runner (PREREG.md). Design first; the final window is read once, by `final`, which refuses a second read.

    python3 -m research.hunt.r78.r78study design <xau_filled.csv.gz> [trades_dir]
    python3 -m research.hunt.r78.r78study final  <xau_filled.csv.gz> [trades_dir]

Money: USD per 1 standard lot (100 oz) after spread, commission and stop slippage. Signal source: XAUUSD itself.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from . import r78engine as E

HERE = Path(__file__).resolve().parent
RES = HERE / "results"
DESIGN = ("2023-10-02", "2026-03-31")
FINAL = ("2026-04-01", "2026-10-16")
WF_QUARTERS = [(f"{y}-{m:02d}-01", (pd.Timestamp(f"{y}-{m:02d}-01") + pd.offsets.QuarterEnd(0)).strftime("%Y-%m-%d"))
               for y, m in [(2024, 4), (2024, 7), (2024, 10), (2025, 1), (2025, 4), (2025, 7), (2025, 10), (2026, 1)]]
SIDES = {"long": 1, "short": -1}
N_TWINS = 1000


def months(lo, hi) -> float:
    return (pd.Timestamp(hi) - pd.Timestamp(lo)).days / 30.44 + 1 / 30.44


def in_period(tr: pd.DataFrame, lo, hi) -> pd.DataFrame:
    return tr[(tr.day >= pd.Timestamp(lo)) & (tr.day <= pd.Timestamp(hi))]


def bar_mask(B: E.Bars, lo, hi) -> np.ndarray:
    d = B.t.astype("datetime64[D]")
    return (d >= np.datetime64(lo)) & (d <= np.datetime64(hi))


def all_trades(B: E.Bars) -> dict:
    pdh, pdl = E.daily_levels(B)
    return {(v.vid, s): E.run_variant(B, v, sg, pdh, pdl) for v in E.VARIANTS for s, sg in SIDES.items()}


def line_trades(T: dict, vid: str, side: str) -> pd.DataFrame:
    if side == "combined":
        return pd.concat([T[(vid, "long")], T[(vid, "short")]]).sort_values("entry_time").reset_index(drop=True)
    return T[(vid, side)]


def twin_p(B, T, vid, side, lo, hi, seed) -> tuple:
    v = next(x for x in E.VARIANTS if x.vid == vid)
    mask = bar_mask(B, lo, hi)
    books = np.zeros(N_TWINS)
    real = 0.0
    for s, sg in SIDES.items():
        if side not in (s, "combined"):
            continue
        tr = in_period(T[(vid, s)], lo, hi)
        real += tr.net.sum()
        books += E.twin_nets(B, tr, v, sg, mask, N_TWINS, seed + (0 if s == "long" else 1))
    p = (1 + int((books >= real).sum())) / (N_TWINS + 1)
    return p, float(books.mean()), float(np.percentile(books, 90))


def clean(d):
    if isinstance(d, dict):
        return {str(k): clean(v) for k, v in d.items()}
    if isinstance(d, (list, tuple)):
        return [clean(x) for x in d]
    if isinstance(d, (np.floating, float)):
        return None if not np.isfinite(d) else round(float(d), 4)
    if isinstance(d, np.integer):
        return int(d)
    return d


def examples(tr: pd.DataFrame, k=3) -> list:
    if tr.empty:
        return []
    picks = []
    for want in ("target", "stop", "time 20:40"):
        s = tr[tr.reason == want]
        if len(s):
            picks.append(s.iloc[len(s) // 2])
    cols = ["side", "break_time", "green_time", "entry_time", "exit_time", "prev_level", "entry_px", "stop_px",
            "target_px", "exit_px", "reason", "net", "r_mult"]
    return [{c: (str(p[c]) if "time" in c else p[c]) for c in cols} for p in picks[:k]]


def design(path, trades_dir=None):
    B = E.Bars(E.read_bid(path))
    T = all_trades(B)
    T = {k: in_period(v, *DESIGN) for k, v in T.items()}          # nothing after 31 Mar 2026 is kept or shown
    lines, ps = {}, []
    seed = 7800
    for v in E.VARIANTS:
        for side in ("long", "short", "combined"):
            tr = line_trades(T, v.vid, side)
            st = E.stats(tr, months(*DESIGN))
            q_nets = [float(in_period(tr, a, b).net.sum()) for a, b in WF_QUARTERS]
            q_ns = [int(len(in_period(tr, a, b))) for a, b in WF_QUARTERS]
            wf = in_period(tr, WF_QUARTERS[0][0], WF_QUARTERS[-1][1])
            seed += 2
            p, twin_mean, twin_p90 = twin_p(B, T, v.vid, side, *DESIGN, seed)
            ps.append(p)
            lines[f"{v.vid} {side}"] = dict(variant=v.vid, label=v.label, side=side, design=st,
                                            wf=E.stats(wf, months(WF_QUARTERS[0][0], WF_QUARTERS[-1][1])),
                                            wf_quarters=dict(nets=q_nets, trades=q_ns,
                                                             positive=int(sum(x > 0 for x in q_nets))),
                                            twin=dict(p=p, mean=twin_mean, p90=twin_p90),
                                            examples=examples(tr))
    q = E.bh(np.array(ps))
    for (key, ln), qq in zip(lines.items(), q):
        ln["twin"]["q_bh"] = float(qq)
    # re-tune line: before each quarter, the best variant on all earlier design data (ties -> V1)
    retune = {}
    for side in ("long", "short", "combined"):
        tot, picks = 0.0, []
        for a, b in WF_QUARTERS:
            best, best_net = "V1", -np.inf
            for v in E.VARIANTS:
                n = float(in_period(line_trades(T, v.vid, side), DESIGN[0],
                                    (pd.Timestamp(a) - pd.Timedelta(days=1)).strftime("%Y-%m-%d")).net.sum())
                if n > best_net + 1e-9:
                    best, best_net = v.vid, n
            got = float(in_period(line_trades(T, best, side), a, b).net.sum())
            picks.append((a, best, got))
            tot += got
        retune[side] = dict(net=tot, picks=picks)
    out = dict(period=DESIGN, wf_quarters=WF_QUARTERS, n_twins=N_TWINS, lines=lines, retune=retune,
               bars=int(bar_mask(B, *DESIGN).sum()))
    RES.mkdir(exist_ok=True)
    (RES / "design.json").write_text(json.dumps(clean(out), indent=1))
    if trades_dir:
        Path(trades_dir).mkdir(parents=True, exist_ok=True)
        for (vid, s), tr in T.items():
            tr.to_csv(Path(trades_dir) / f"design_{vid}_{s}.csv", index=False)
    return out


def final(path, trades_dir=None):
    flag = RES / "FINAL_READ"
    if flag.exists():
        raise SystemExit("R78's final window was already read once (results/FINAL_READ). Refusing a second read.")
    des = json.loads((RES / "design.json").read_text())
    flag.write_text(f"final window {FINAL[0]} .. {FINAL[1]} read on {pd.Timestamp.now('UTC'):%Y-%m-%d %H:%M} UTC\n")
    B = E.Bars(E.read_bid(path))
    T = {k: in_period(v, *FINAL) for k, v in all_trades(B).items()}
    lines = {}
    seed = 7900
    for v in E.VARIANTS:
        for side in ("long", "short", "combined"):
            tr = line_trades(T, v.vid, side)
            seed += 2
            p, tm, t90 = twin_p(B, T, v.vid, side, *FINAL, seed)
            d = des["lines"][f"{v.vid} {side}"]
            fs = E.stats(tr, months(*FINAL))
            gates = dict(design_net=d["design"]["net"] > 0, wf_net=d["wf"]["net"] > 0, final_net=fs["net"] > 0,
                         bh_q=d["twin"]["q_bh"] <= 0.10, design_trades=d["design"]["n"] >= 100)
            lines[f"{v.vid} {side}"] = dict(variant=v.vid, side=side, final=fs, twin=dict(p=p, mean=tm, p90=t90),
                                            gates=gates, passed=all(gates.values()), examples=examples(tr))
    out = dict(period=FINAL, lines=lines, bars=int(bar_mask(B, *FINAL).sum()),
               last_bar=str(B.t[-1]))
    (RES / "final.json").write_text(json.dumps(clean(out), indent=1))
    if trades_dir:
        Path(trades_dir).mkdir(parents=True, exist_ok=True)
        for (vid, s), tr in T.items():
            tr.to_csv(Path(trades_dir) / f"final_{vid}_{s}.csv", index=False)
    return out


if __name__ == "__main__":
    mode, data = sys.argv[1], sys.argv[2]
    td = sys.argv[3] if len(sys.argv) > 3 else None
    {"design": design, "final": final}[mode](data, td)
    print("done", mode)
