"""R62 study (PREREG sections 6-8): candidates per instrument and volume source, the 16 variants on each book, design
statistics, the ONE final read with random-entry twins and Benjamini-Hochberg, and the tables.

    python research/hunt/r62/r62study.py design       # results/design.json (design sessions only; the guard is closed)
    python research/hunt/r62/r62study.py final        # writes results/FINAL_READ first, then results/final.json; once
    python research/hunt/r62/r62study.py report       # results/report.md from the two JSON files
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

import r62cfg as K
import r62data as DATA
import r62sim as Z
import r62smc as SMC

K51 = K.point_r51()
import r51data  # noqa: E402

BOOKS = {
    # book: (instruments, volume key, kind)
    "IDX": (K.IDX, "vol_a", "idx"),
    "STK": (K.STOCKS, "vol", "cash"),
    "IDX-b": (("NIFTY", "BANKNIFTY"), "vol_b", "idx"),
    "IDX-c": (K.IDX, "vol_c", "idx"),
    "STK-c": (K.STOCKS, "vol_c", "cash"),
    "STK-sw": (K.STOCKS, "vol", "swing"),
}
SIGNAL_SOURCE = {"idx": "the index (spot 1-minute bars); the 1-ITM nearest-expiry option is only what is bought",
                 "cash": "the stock (1-minute bars)", "swing": "the stock (1-minute bars)"}

_GRIDS: dict = {}


def grid(name: str) -> dict:
    if name not in _GRIDS:
        _GRIDS[name] = DATA.load_grid(name)
    return _GRIDS[name]


def last_session(days: list, hi: date) -> int:
    return int(np.searchsorted(np.array(days), hi, side="right") - 1)


def cands_for(inst: str, vkey: str, part: str) -> tuple[list, dict]:
    """Candidates of one instrument on the grid cut at the part's last day (design never sees a final bar)."""
    hi = K.DESIGN_END if part == "design" else K.END
    lo = K.START if part == "design" else K.FINAL_START
    K.check_range(lo, hi)
    path = K.OUT / f"cands_{inst}_{vkey}_{part}.pkl"
    if path.exists():
        with open(path, "rb") as f:
            return pickle.load(f)
    g = grid(inst)
    n = last_session(g["days"], hi) + 1
    cands, cnt = SMC.candidates(g["M"][:n], g[vkey][:n])
    days = g["days"]
    keep = []
    for c in cands:
        d = days[c.di]
        if not (lo <= d <= hi):
            continue
        if part == "final" and days[c.act_di] < K.FINAL_START:
            continue                     # PREREG 1: no final trade from a leg confirmed before 1 Apr 2026
        keep.append(c)
    out = (keep, cnt)
    with open(path, "wb") as f:
        pickle.dump(out, f, protocol=pickle.HIGHEST_PROTOCOL)
    return out


def select(cands: list, variant: str, long_only: bool = False) -> list:
    v = K.parse_variant(variant)
    out = []
    for c in cands:
        if c.zone != v["zone"] or (v["conf"] and not c.conf) or (v["htf"] and not c.htf):
            continue
        if long_only and c.side < 0:
            continue
        out.append(c)
    return out


def swing_last(sym: str, days: list, di: int, part: str) -> int:
    hi = K.DESIGN_END if part == "design" else K.END
    last = min(di + K.SWING_HOLD - 1, last_session(days, hi))
    for e in K.EXDATES.get(sym, ()):
        if days[di] < e:
            last = min(last, int(np.searchsorted(np.array(days), e, side="left") - 1))
    return last


def run(book: str, variant: str, part: str) -> list:
    insts, vkey, kind = BOOKS[book]
    tgt = K.parse_variant(variant)["target"]
    trades = []
    for inst in insts:
        cands, _ = cands_for(inst, vkey, part)
        cs = select(cands, variant, long_only=(kind == "swing"))
        g = grid(inst)
        days = g["days"]
        store = r51data.load(inst) if kind == "idx" else None
        qty = Z.latest_lot(inst) if kind != "idx" else 0
        busy, used = -1, set()
        for c in cs:
            f_in = c.di * K.NROWS + c.s + 1
            if f_in <= busy or c.leg in used:
                continue
            if kind == "idx":
                D = store.get(days[c.di])
                if D is None or D.atm is None:
                    continue
                t = Z.index_trade(inst, D, c.side, c.s, c.stop, None, tgt, c.st_tgt)
                if t is None:
                    continue
                busy = c.di * K.NROWS + t["xr"]
                t["exit_day"] = t["day"]
            else:
                if kind == "swing":
                    ld = swing_last(inst, days, c.di, part)
                    if ld < c.di:
                        continue
                    t = Z.cash_trade(inst, g, c.di, c.s, c.side, c.stop, tgt, c.st_tgt, qty, swing=True, last_di=ld)
                else:
                    t = Z.cash_trade(inst, g, c.di, c.s, c.side, c.stop, tgt, c.st_tgt, qty)
                if t is None:
                    continue
                busy = t["xf"]
                t["und"] = inst
            used.add(c.leg)
            t.update(inst=inst, di=c.di, s=c.s, leg=c.leg, zone=c.zone, conf=c.conf, htf=c.htf, zlo=c.zlo, zhi=c.zhi)
            trades.append(t)
    trades.sort(key=lambda t: (t["day"], t["s"], t["inst"]))
    return trades


# ------------------------------------------------------------------------------------------------ statistics
def max_dd(daily: np.ndarray) -> float:
    if not len(daily):
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(daily)])
    return float((np.maximum.accumulate(eq) - eq).max())


def stats(tr: list, key: str = "net") -> dict:
    if not tr:
        return dict(trades=0, net=0.0)
    T = pd.DataFrame(tr)
    net = T[key].to_numpy(float)
    daily = T.groupby("exit_day")[key].sum().sort_index()
    month = T.assign(m=[str(d)[:7] for d in T.exit_day]).groupby("m")[key].sum()
    w, lo = net[net > 0], net[net <= 0]
    tot = float(net.sum())
    top5 = float(np.sort(net)[::-1][:5].sum())
    q = T.assign(q=[K.quarter(d) for d in T.day]).groupby("q")[key].sum()
    per = T.groupby("inst")[key].agg(["count", "sum"])
    return dict(trades=int(len(T)), win=round(float((net > 0).mean()), 3), net=round(tot), per_trade=round(tot / len(T)),
                avg_win=round(float(w.mean())) if len(w) else None, avg_loss=round(float(lo.mean())) if len(lo) else None,
                r_mult=round(float(np.nanmean(T.r_mult)), 3), r_mult_median=round(float(np.nanmedian(T.r_mult)), 3),
                worst_day=round(float(daily.min())), max_dd=round(max_dd(daily.to_numpy())),
                months_green=f"{int((month > 0).sum())}/{len(month)}",
                top5_share=round(top5 / tot, 3) if tot > 0 else None,
                quarters={k: round(float(v)) for k, v in q.items()},
                inst={k: [int(r["count"]), round(float(r["sum"]))] for k, r in per.iterrows()},
                why={k: int(v) for k, v in T.why.value_counts().items()},
                longs=int((T.side > 0).sum()), shorts=int((T.side < 0).sum()))


def design_gate(s: dict) -> bool:
    if not s.get("trades"):
        return False
    q = [s["quarters"].get(x, 0) for x in K.QUARTERS]
    return s["net"] > 0 and sum(v > 0 for v in q) >= 3


def risk_info(tr: list, kind: str) -> dict:
    """1% of Rs 1 lakh at risk (information only, PREREG 7)."""
    if not tr:
        return {}
    if kind == "idx":
        lots = [int(K.RISK_CAPITAL * K.RISK_FRAC // (t["risk_pts"] * t["qty"] * K.ASSUMED_DELTA))
                if t["risk_pts"] > 0 else 0 for t in tr]
        net = sum(t["net"] * n for t, n in zip(tr, lots))
        return dict(zero_lot_share=round(float(np.mean([n == 0 for n in lots])), 3), median_lots=float(np.median(lots)),
                    net_risk1=round(net))
    return dict(net_risk1=round(sum(t["net_risk1"] for t in tr)), median_shares=float(np.median([t["qty_risk1"] for t in tr])),
                median_risk_pct=round(float(np.median([t["risk_pct"] for t in tr])) * 100, 3))


def bundle(book: str, variant: str, part: str) -> tuple[dict, list]:
    tr = run(book, variant, part)
    s1, s2 = stats(tr, "net"), stats(tr, "net2")
    out = dict(x1=s1, x2=dict(net=s2.get("net", 0), win=s2.get("win")), risk1=risk_info(tr, BOOKS[book][2]))
    if part == "design":
        out["design_gate"] = design_gate(s1)
    return out, tr


def dump(tr: list, name: str):
    p = K.OUT / "trades"
    p.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(tr).to_pickle(p / f"{name}.pkl")


def counts(part: str) -> dict:
    out = {}
    for book, (insts, vkey, _) in BOOKS.items():
        for inst in insts:
            c, cnt = cands_for(inst, vkey, part)
            out[f"{book}:{inst}"] = dict(cnt, candidates=len(c))
    return out


def design():
    res = {}
    for book in BOOKS:
        for v in K.VARIANTS:
            b, tr = bundle(book, v, "design")
            res[f"{book}/{v}"] = b
            dump(tr, f"design_{book}_{v}")
            print(book, v, b["x1"].get("trades"), b["x1"].get("net"), b.get("design_gate"), flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(dict(results=res, counts=counts("design")), indent=1, default=str))


# ------------------------------------------------------------------------------------------------ the final read
def twin_p(book: str, tr: list, n: int = K.TWINS, seed: int = K.SEED) -> tuple[float | None, float | None]:
    if not tr:
        return None, None
    rng = np.random.default_rng(seed)
    kind = BOOKS[book][2]
    tot = np.zeros(n)
    for t in tr:
        if kind == "idx":
            D = r51data.load(t["inst"])[t["day"]]
            tot += Z.twin_index_nets(t, D, n, rng)
        else:
            g = grid(t["inst"])
            ld = swing_last(t["inst"], g["days"], t["di"], "final") if kind == "swing" else t["di"]
            tot += Z.twin_cash_nets(t, g, t["di"], n, rng, kind == "swing", ld)
    actual = float(sum(t["net"] for t in tr))
    return round((1 + int((tot >= actual).sum())) / (n + 1), 4), round(float(tot.mean()))


def bh(p: list) -> list:
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out.tolist()


def verdict(f: dict, d: dict) -> str:
    s = f["x1"]
    fails = []
    if not s.get("trades") or s["net"] <= 0:
        fails.append("net <= 0")
    if f.get("p") is None or f["p"] >= K.GATE_P:
        fails.append("p >= 0.05")
    if f.get("q") is None or f["q"] > K.GATE_Q:
        fails.append("q > 0.10")
    if s.get("trades", 0) < K.GATE_MIN_TRADES:
        fails.append("< 40 trades")
    if not (s.get("top5_share") is not None and s["top5_share"] < 1.0):
        fails.append("top 5 >= net")
    if not d.get("design_gate"):
        fails.append("design gate")
    return "PASS" if not fails else "FAIL: " + ", ".join(fails)


def final():
    if K.FINAL_FLAG.exists():
        raise SystemExit("R62's final test was already read (results/FINAL_READ). It is read once.")
    des = json.loads((K.RESULTS / "design.json").read_text())["results"]
    K.FINAL_FLAG.write_text(f"final test read once on {date.today()} by r62study.py final\n")
    K.open_final()
    res = {}
    for book in BOOKS:
        for v in K.VARIANTS:
            b, tr = bundle(book, v, "final")
            b["p"], b["twin_mean"] = twin_p(book, tr)
            res[f"{book}/{v}"] = b
            dump(tr, f"final_{book}_{v}")
            print(book, v, b["x1"].get("trades"), b["x1"].get("net"), b["p"], flush=True)
    keys = [f"{b}/{v}" for b in K.PRIMARY for v in K.VARIANTS]
    ps = [res[k]["p"] if res[k]["p"] is not None else 1.0 for k in keys]
    for k, q in zip(keys, bh(ps)):
        res[k]["q"] = round(q, 4)
        res[k]["verdict"] = verdict(res[k], des[k])
    for book in K.SECONDARY:                        # context only: their own BH, never a PASS
        ks = [f"{book}/{v}" for v in K.VARIANTS if f"{book}/{v}" in res]
        if not ks:
            continue
        qs = bh([res[k]["p"] if res[k]["p"] is not None else 1.0 for k in ks])
        for k, q in zip(ks, qs):
            res[k]["q_own"] = round(q, 4)
            res[k]["verdict"] = "context (not gated): " + verdict(dict(res[k], q=q), des[k])
    (K.RESULTS / "final.json").write_text(json.dumps(dict(results=res, counts=counts("final")), indent=1, default=str))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "report":
        import r62report
        r62report.main()
    else:
        raise SystemExit(__doc__)

