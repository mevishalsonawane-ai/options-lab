"""R148 study (PREREG.md): enhancing Night: big-gap nights (A), a risk cap across indices (B), resting exits (C).

    python -m research.hunt.r147_night_plus.data build NIFTY BANKNIFTY FINNIFTY SENSEX   # R147 stores + R148 fields
    python -m research.hunt.r148_night_enhance.study                                    # results/results.json

DESIGN = R147's design years (2022-24); CHECK = R147's holdout (Jan 2025 - Oct 2026), a second look (PREREG 2).
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r147_night_plus import cfg as K7
from research.hunt.r147_night_plus import study as S7

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
BOOK = {"NIFTY": "r3", "BANKNIFTY": "r3", "FINNIFTY": "r3", "SENSEX": "s17"}
L_VIX = 16.0
STOPS = {"C1": 0.20, "C2": 0.40}
TARGET = 0.40
SEED = 148
TWINS = 2000


def prereg_sha() -> str:
    return hashlib.sha256((HERE / "PREREG.md").read_bytes()).hexdigest()


# ================================================================================================ pricing
def costs_net(und: str, qty: int, e: float, x: float, stop: bool = False) -> float:
    ef, xf = float(S7.FILL.buy(e)), float(S7.FILL.sell(x, stop=stop))
    bse = und in K7.BSE
    sp = K7.HALF_SPREAD[und] * (e + x) * qty
    return float((xf - ef) * qty - S7.COST.charge(True, ef, qty, None, bse) - S7.COST.charge(False, xf, qty, None, bse) - sp)


def exit_price(e: float, x16: float, extra: tuple, stop_pct: float | None, tgt_pct: float | None) -> tuple[float, bool, str]:
    """(raw exit, is_stop_fill, why) under a resting stop and / or target (PREREG C). extra = (o15, h15, l15, c15,
    low_after_entry). Stop checked first (conservative); the 09:15 high is never trusted."""
    o15, _h15, l15, c15, low_after = extra
    stop = e * (1 - stop_pct) if stop_pct is not None else None
    tgt = e * (1 + tgt_pct) if tgt_pct is not None else None
    if stop is not None and np.isfinite(low_after) and low_after <= stop:
        return stop, True, "stop_same_day"
    if stop is not None and np.isfinite(o15) and o15 <= stop:
        return o15, True, "stop_gap_through"
    if tgt is not None and np.isfinite(o15) and o15 >= tgt:
        return o15, False, "target_at_open"
    if stop is not None and np.isfinite(l15) and l15 <= stop:
        return stop, True, "stop_0915"
    if tgt is not None and np.isfinite(c15) and c15 >= tgt:
        return tgt, False, "target_0915"
    return x16, False, "x16"


def is_L(df: pd.DataFrame) -> np.ndarray:
    return (df.wkend.to_numpy(bool) | (df.vix >= L_VIX).fillna(False).to_numpy(bool))


# ================================================================================================ book
def book(part: str) -> tuple[pd.DataFrame, dict]:
    """NB's trades across the 4 indices (one row per trade) and the per-index night frames."""
    rows, frames = [], {}
    for und, rule in BOOK.items():
        df = S7.nights(und, part)
        frames[und] = df
        side = S7.night_side(df, rule)
        m = (side != 0) & S7.priced(df)
        for i in np.flatnonzero(m):
            r = df.iloc[i]
            s = int(side[i])
            k, e, x16, why = r.legs[(s, 1)]
            ex = r.extra.get((s, 1), (np.nan,) * 5) if isinstance(r.get("extra"), dict) else (np.nan,) * 5
            base = costs_net(und, r.qty, e, x16)
            rec = {"d": r.d, "und": und, "side": s, "qty": r.qty, "e": e, "x16": x16, "net": base,
                   "strength": abs(r["loc"] - 0.5), "L": bool(is_L(df.iloc[[i]])[0])}
            for cid, sp in STOPS.items():
                px, st, w = exit_price(e, x16, ex, sp, None)
                rec[f"net_{cid}"], rec[f"why_{cid}"] = costs_net(und, r.qty, e, px, st), w
            px, st, w = exit_price(e, x16, ex, None, TARGET)
            rec["net_C3"], rec["why_C3"] = costs_net(und, r.qty, e, px, st), w
            px, st, w = exit_price(e, x16, ex, STOPS["C1"], TARGET)
            rec["net_C4"], rec["why_C4"] = costs_net(und, r.qty, e, px, st), w
            rows.append(rec)
    return pd.DataFrame(rows).sort_values(["d", "und"]).reset_index(drop=True), frames


def by_year(dates, x) -> dict:
    return {int(k): float(v) for k, v in pd.Series(np.asarray(x, float), index=[d.year for d in dates]).groupby(level=0).sum().items()}


def nightly(b: pd.DataFrame, col: str = "net") -> pd.Series:
    return b.groupby("d")[col].sum().sort_index()


def risk(series: pd.Series) -> dict:
    mon = series.groupby([f"{d.year}-{d.month:02d}" for d in series.index]).sum()
    return {"total": float(series.sum()), "max_dd": S7.max_dd(series.to_numpy()), "worst_month": float(mon.min()),
            "worst_night": float(series.min()), "nights": int(len(series)), "months_positive": int((mon > 0).sum()),
            "months": int(len(mon))}


# ================================================================================================ ideas
def idea_A1(b: pd.DataFrame, rng) -> dict:
    x = b.net.to_numpy()
    m = b.L.to_numpy(bool)
    real = float(x[m].sum())
    sims = np.array([x[rng.choice(len(x), int(m.sum()), replace=False)].sum() for _ in range(TWINS)])
    return {"id": "A1", "rule": "2nd lot on likely-big nights (weekend/holiday or VIX >= 16)", "trades": int(m.sum()),
            "extra_total": real, "extra_per_trade": float(x[m].mean()) if m.any() else None,
            "by_year": by_year(b.d[m], x[m]), "p": float((1 + (sims >= real - 1e-9).sum()) / (1 + TWINS)),
            "metric": "extra_total"}


def straddles(frames: dict, only_no_trade: bool) -> pd.DataFrame:
    rows = []
    for und, rule in BOOK.items():
        df = frames[und]
        ok = df.tradable.to_numpy(bool) & df.net_c0.notna().to_numpy() & df.net_p0.notna().to_numpy()
        side = S7.night_side(df, rule)
        for i in np.flatnonzero(ok):
            rows.append({"d": df.d.iloc[i], "und": und, "net": float(df.net_c0.iloc[i] + df.net_p0.iloc[i]),
                         "L": bool(is_L(df.iloc[[i]])[0]), "nb_trade": bool(side[i] != 0)})
    t = pd.DataFrame(rows)
    return t if not only_no_trade else t[~t.nb_trade]


def idea_A2(frames: dict, only_no_trade: bool, rng) -> dict:
    t = straddles(frames, only_no_trade)
    m = t.L.to_numpy(bool)
    real = float(t.net[m].sum())
    sims = []
    for _ in range(TWINS):
        tot = 0.0
        for und, g in t.groupby("und"):
            k = int(g.L.sum())
            tot += g.net.to_numpy()[rng.choice(len(g), k, replace=False)].sum() if k else 0.0
        sims.append(tot)
    sims = np.array(sims)
    iid = "A3" if only_no_trade else "A2"
    return {"id": iid, "rule": "ATM straddle 15:20 -> 09:16 on likely-big nights" + (" where Night has no trade" if only_no_trade else ""),
            "trades": int(m.sum()), "net_total": real, "net_per_trade": float(t.net[m].mean()) if m.any() else None,
            "by_year": by_year(t.d[m], t.net[m]), "per_index": {u: float(g.net[g.L].sum()) for u, g in t.groupby("und")},
            "random_nights_mean_total": float(sims.mean()),
            "p": float((1 + (sims >= real - 1e-9).sum()) / (1 + TWINS)), "metric": "net_total"}


def idea_C(b: pd.DataFrame, cid: str, rng) -> dict:
    d = (b[f"net_{cid}"] - b.net).to_numpy()
    real = float(d.sum())
    sims = (np.where(rng.random((TWINS, len(d))) < 0.5, 1, -1) * d).sum(axis=1)
    rule = {"C1": "resting stop -20%", "C2": "resting stop -40%", "C3": "resting target +40%",
            "C4": "stop -20% + target +40%"}[cid]
    return {"id": cid, "rule": rule, "trades": int(len(d)), "diff_total": real, "diff_per_trade": float(d.mean()),
            "by_year": by_year(b.d, d), "exits": b[f"why_{cid}"].value_counts().to_dict(),
            "p": float((1 + (sims >= real - 1e-9).sum()) / (1 + TWINS)),
            "risk_base": risk(nightly(b)), "risk_alt": risk(nightly(b, f"net_{cid}")), "metric": "diff_total"}


def cap(b: pd.DataFrame, k: int) -> np.ndarray:
    """Keep mask: on nights with >= 3 trades keep the k strongest (abs(loc - 0.5)); ties by index order."""
    keep = np.ones(len(b), bool)
    for _d, g in b.groupby("d"):
        if len(g) >= 3:
            order = g.sort_values("strength", ascending=False, kind="stable").index
            keep[order[k:]] = False
    return keep


def idea_B(b: pd.DataFrame, k: int, rng) -> dict:
    keep = cap(b, k)
    base, alt = risk(nightly(b)), risk(nightly(b[keep]))
    crowded = b.groupby("d").und.transform("size").to_numpy() >= 3
    drops = int((~keep).sum())
    dds = []
    idx_crowded = np.flatnonzero(crowded)
    for _ in range(TWINS):
        kk = np.ones(len(b), bool)
        if drops:
            kk[rng.choice(idx_crowded, drops, replace=False)] = False
        dds.append(S7.max_dd(nightly(b[kk]).to_numpy()))
    dds = np.array(dds)
    return {"id": f"B{1 if k == 2 else 2}", "rule": f"at most {k} index{'es' if k > 1 else ''} a night (strongest close)",
            "trades": int(len(b)), "dropped": drops, "crowded_nights": int(b[crowded].d.nunique()), "base": base,
            "capped": alt, "net_given_up": base["total"] - alt["total"],
            "dd_better_than_twins_share": float((dds <= alt["max_dd"] + 1e-9).mean()),
            "by_year_capped_minus_base": {y: float(v) for y, v in (pd.Series(np.where(keep, 0.0, -b.net.to_numpy()), index=[d.year for d in b.d]).groupby(level=0).sum()).items()}}


# ================================================================================================ run
def run_part(part: str, rng) -> dict:
    b, frames = book(part)
    out = {"part": part, "book": {"trades": int(len(b)), "per_index": b.groupby("und").net.agg(["size", "mean", "sum"]).round(1).to_dict("index"),
                                  "risk": risk(nightly(b)), "L_share": float(b.L.mean())}}
    out["A"] = [idea_A1(b, rng), idea_A2(frames, False, rng), idea_A2(frames, True, rng)]
    out["C"] = [idea_C(b, c, rng) for c in ("C1", "C2", "C3", "C4")]
    out["B"] = [idea_B(b, 2, rng), idea_B(b, 1, rng)]
    return out


def gate(des: dict, chk: dict) -> None:
    tests = des["A"] + des["C"]
    for r, q in zip(tests, S7.bh([r["p"] for r in tests])):
        r["bh_q"] = q
    chk_by = {r["id"]: r for r in chk["A"] + chk["C"]}
    for r in tests:
        c = chk_by[r["id"]]
        met = r["metric"]
        yrs = sum(1 for y in K7.DESIGN_YEARS if r["by_year"].get(y, 0) > 0)
        r["gate"] = {"design_pos": r[met] > 0, "years_2of3": yrs >= 2, "design_p": r["p"] <= 0.05,
                     "bh": r["bh_q"] <= 0.10, "check_pos": c[met] > 0, "check_p": c["p"] <= 0.05}
        r["pass"] = all(r["gate"].values())
    chk_b = {r["id"]: r for r in chk["B"]}
    for r in des["B"]:
        c = chk_b[r["id"]]
        g = {}
        for nm, x in (("design", r), ("check", c)):
            g[f"{nm}_dd_cut_10pct"] = x["capped"]["max_dd"] >= 0.9 * x["base"]["max_dd"]
            g[f"{nm}_worst_month_not_worse"] = x["capped"]["worst_month"] >= x["base"]["worst_month"]
            g[f"{nm}_net_given_up_le_15pct"] = x["net_given_up"] <= 0.15 * max(x["base"]["total"], 1e-9)
            g[f"{nm}_dd_beats_95pct_twins"] = x["dd_better_than_twins_share"] >= 0.95
        r["gate"] = g
        r["pass"] = all(g.values())


def _dump(obj, path) -> None:
    def conv(o):
        if isinstance(o, np.integer):
            return int(o)
        if isinstance(o, (np.floating, float)):
            return None if not np.isfinite(o) else float(o)
        if isinstance(o, np.bool_):
            return bool(o)
        return str(o)
    path.write_text(json.dumps(obj, indent=1, default=conv).replace("NaN", "null"))


def main() -> None:
    rng = np.random.default_rng(SEED)
    des = run_part("design", rng)
    chk = run_part("hold", rng)
    gate(des, chk)
    RESULTS.mkdir(parents=True, exist_ok=True)
    _dump({"prereg_sha256": prereg_sha(), "run": datetime.now().isoformat(timespec="seconds"), "design": des,
           "check": chk}, RESULTS / "results.json")


if __name__ == "__main__":
    main()
