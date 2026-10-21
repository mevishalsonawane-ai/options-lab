"""R62b study (PREREG sections 4-7): candidates per index, timeframe and fib factor, the 16 variants on the pooled
five-index book, design statistics, the ONE final read with random-entry twins and Benjamini-Hochberg, and the report.

    python research/hunt/r62b/r62bstudy.py design     # results/design.json (design sessions only; the guard is closed)
    python research/hunt/r62b/r62bstudy.py final      # writes results/FINAL_READ first, then results/final.json; once
    python research/hunt/r62b/r62bstudy.py report     # results/report.md from the two JSON files
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

import r62bcfg as K
import r62bdata as DATA
import r62bmsb as S
import r62bsim as Z

K51 = K.point_r51()
import r51data  # noqa: E402

FIBS = (K.FIB,) + K.FIB_SENS
_GRIDS: dict = {}


def grid(name: str) -> dict:
    if name not in _GRIDS:
        _GRIDS[name] = DATA.load_grid(name)
    return _GRIDS[name]


def last_session(days: list, hi: date) -> int:
    return int(np.searchsorted(np.array(days), hi, side="right") - 1)


def cands_for(inst: str, tf: int, fib: float, part: str) -> tuple[list, dict]:
    """Candidates of one index on bars cut at the part's last day (the design never sees a final bar)."""
    hi = K.DESIGN_END if part == "design" else K.END
    lo = K.START if part == "design" else K.FINAL_START
    K.check_range(lo, hi)
    path = K.OUT / f"cands_{inst}_{tf}_{fib}_{part}.pkl"
    if path.exists():
        with open(path, "rb") as f:
            return pickle.load(f)
    g = grid(inst)
    n = last_session(g["days"], hi) + 1
    cands, cnt = S.candidates(g["M"][:n], tf, fib)
    days = g["days"]
    keep = [c for c in cands if lo <= days[c.di] <= hi and not (part == "final" and days[c.act_di] < K.FINAL_START)]
    out = (keep, cnt)
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        pickle.dump(out, f, protocol=pickle.HIGHEST_PROTOCOL)
    return out


def run(variant: str, part: str, fib: float = K.FIB) -> list:
    v = K.parse_variant(variant)
    hold = K.HOLD_BARS * v["tf"]
    rr = K.RR_MIN if v["target"] == "rr" else None
    trades = []
    for inst in K.IDX:
        cands, _ = cands_for(inst, v["tf"], fib, part)
        cs = [c for c in cands if c.conf == v["conf"] and (c.htf or not v["htf"])]
        days = grid(inst)["days"]
        store = r51data.load(inst)
        busy = -1
        for c in cs:
            if c.di * K.NROWS + c.s + 1 <= busy:
                continue
            D = store.get(days[c.di])
            if D is None or D.atm is None:
                continue
            t = Z.index_trade(inst, D, c.side, c.s, c.stop, c.tgt, hold, rr)
            if t is None:
                continue
            busy = c.di * K.NROWS + t["xr"]
            t.update(inst=inst, exit_day=t["day"], di=c.di, s=c.s, zone=c.zone, zlo=c.zlo, zhi=c.zhi, htf=c.htf)
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
        return dict(trades=0, net=0.0, win=None)
    T = pd.DataFrame(tr)
    net = T[key].to_numpy(float)
    daily = T.groupby("exit_day")[key].sum().sort_index()
    month = T.assign(m=[str(d)[:7] for d in T.exit_day]).groupby("m")[key].sum()
    w, lo = net[net > 0], net[net <= 0]
    tot = float(net.sum())
    top5 = float(np.sort(net)[::-1][:5].sum())
    q = T.assign(q=[K.quarter(d) for d in T.day]).groupby("q")[key].sum()
    per = {}
    for inst, g in T.groupby("inst"):
        x = g[key].to_numpy(float)
        per[inst] = dict(trades=int(len(x)), win=round(float((x > 0).mean()), 3), net=round(float(x.sum())),
                         max_dd=round(max_dd(g.groupby("exit_day")[key].sum().sort_index().to_numpy())))
    return dict(trades=int(len(T)), win=round(float((net > 0).mean()), 3), net=round(tot), per_trade=round(tot / len(T)),
                avg_win=round(float(w.mean())) if len(w) else None, avg_loss=round(float(lo.mean())) if len(lo) else None,
                r_mult=round(float(np.nanmean(T.r_mult)), 3), r_mult_median=round(float(np.nanmedian(T.r_mult)), 3),
                index_win=round(float((T.r_mult > 0).mean()), 3),
                planned_rr=round(float(np.median(T.rr)), 2),
                worst_day=round(float(daily.min())), max_dd=round(max_dd(daily.to_numpy())),
                months_green=f"{int((month > 0).sum())}/{len(month)}",
                top5_share=round(top5 / tot, 3) if tot > 0 else None,
                quarters={k: round(float(v)) for k, v in q.items()}, inst=per,
                why={k: int(v) for k, v in T.why.value_counts().items()},
                longs=int((T.side > 0).sum()), shorts=int((T.side < 0).sum()))


def design_gate(s: dict) -> bool:
    if not s.get("trades"):
        return False
    q = [s["quarters"].get(x, 0) for x in K.QUARTERS]
    return s["net"] > 0 and sum(v > 0 for v in q) >= 3


def bundle(variant: str, part: str, fib: float) -> tuple[dict, list]:
    tr = run(variant, part, fib)
    s1, s2 = stats(tr, "net"), stats(tr, "net2")
    out = dict(x1=s1, x2=dict(net=s2.get("net", 0), win=s2.get("win"), max_dd=s2.get("max_dd")))
    if part == "design":
        out["design_gate"] = design_gate(s1)
    return out, tr


def dump(tr: list, name: str):
    p = K.OUT / "trades"
    p.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(tr).to_pickle(p / f"{name}.pkl")


def counts(part: str) -> dict:
    out = {}
    for fib in FIBS:
        for tf in K.TFS:
            for inst in K.IDX:
                c, cnt = cands_for(inst, tf, fib, part)
                out[f"{fib}:{tf}m:{inst}"] = dict(cnt, candidates=len(c))
    return out


def key(v: str, fib: float) -> str:
    return v if fib == K.FIB else f"{v}@{fib}"


def design():
    res = {}
    for fib in FIBS:
        for v in K.VARIANTS:
            b, tr = bundle(v, "design", fib)
            res[key(v, fib)] = b
            dump(tr, f"design_{key(v, fib)}")
            print(key(v, fib), b["x1"].get("trades"), b["x1"].get("win"), b["x1"].get("net"), b.get("design_gate"),
                  flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(dict(results=res, counts=counts("design")), indent=1, default=str))


# ------------------------------------------------------------------------------------------------ the final read
def twin_p(tr: list, n: int = K.TWINS, seed: int = K.SEED) -> tuple[float | None, float | None]:
    if not tr:
        return None, None
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    for t in tr:
        D = r51data.load(t["inst"])[t["day"]]
        tot += Z.twin_index_nets(t, D, n, rng)
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
        raise SystemExit("R62b's final test was already read (results/FINAL_READ). It is read once.")
    des = json.loads((K.RESULTS / "design.json").read_text())["results"]
    K.FINAL_FLAG.write_text(f"final test read once on {date.today()} by r62bstudy.py final\n")
    K.open_final()
    res = {}
    for fib in FIBS:
        for v in K.VARIANTS:
            b, tr = bundle(v, "final", fib)
            if fib == K.FIB:
                b["p"], b["twin_mean"] = twin_p(tr)
            res[key(v, fib)] = b
            dump(tr, f"final_{key(v, fib)}")
            print(key(v, fib), b["x1"].get("trades"), b["x1"].get("win"), b["x1"].get("net"), b.get("p"), flush=True)
    ps = [res[v]["p"] if res[v]["p"] is not None else 1.0 for v in K.VARIANTS]
    for v, q in zip(K.VARIANTS, bh(ps)):
        res[v]["q"] = round(q, 4)
        res[v]["verdict"] = verdict(res[v], des[v])
    for fib in K.FIB_SENS:                          # information only: never a PASS
        for v in K.VARIANTS:
            res[key(v, fib)]["verdict"] = "information only (sensitivity, not gated)"
    (K.RESULTS / "final.json").write_text(json.dumps(dict(results=res, counts=counts("final")), indent=1, default=str))


# ------------------------------------------------------------------------------------------------ extras
def extras():
    """Per-index statistics at 2x costs, from the trade dumps the design and final runs wrote (no re-run, no new read):
    results/extras.json."""
    out = {}
    for part in ("design", "final"):
        for v in K.VARIANTS:
            p = K.OUT / "trades" / f"{part}_{v}.pkl"
            tr = pd.read_pickle(p).to_dict("records") if p.exists() else []
            out[f"{part}/{v}"] = stats(tr, "net2").get("inst", {}) if tr else {}
    (K.RESULTS / "extras.json").write_text(json.dumps(out, indent=1))


# ------------------------------------------------------------------------------------------------ report
def _row(name, s, s2=None):
    if not s.get("trades"):
        return f"| {name} | 0 | - | - | - | - | - | - | - | - |"
    two = f"{s2['net']:+,}" if s2 else "-"
    return (f"| {name} | {s['trades']} | {s['win']:.0%} | {s['avg_win'] or 0:+,} / {s['avg_loss'] or 0:+,} | "
            f"{s['r_mult']:+.2f} | {s['net']:+,} | {two} | {s['max_dd']:,} | {s['months_green']} | "
            f"{','.join(f'{k} {v}' for k, v in sorted(s['why'].items()))} |")


HEAD = ("| variant | trades | win | avg win / loss (Rs) | realised R | net 1x (Rs) | net 2x (Rs) | max DD (Rs) | "
        "months green | exits |\n|---|---|---|---|---|---|---|---|---|---|")


def report():
    des = json.loads((K.RESULTS / "design.json").read_text())["results"]
    fin = json.loads((K.RESULTS / "final.json").read_text())["results"] if (K.RESULTS / "final.json").exists() else {}
    L = ["# R62b results (generated by r62bstudy.py report)", "",
         "Signal: EmreKb's MSB & Order Block, ported line by line, on the INDEX's 5- / 15-minute bars; traded: the "
         "1-ITM nearest-expiry option, one held contract, 1 lot, real fills and dated charges. Sessions 20 Nov 2024 "
         "onwards only. Claimed win rate in the video: 75-80%.", ""]
    for part, R in (("Design (20 Nov 2024 - 31 Mar 2026)", des), ("Final test (1 Apr 2026 - 14 Oct 2026, read once)", fin)):
        if not R:
            continue
        L += [f"## {part}, fib 0.273 (the 16 gated variants)", "", HEAD]
        for v in K.VARIANTS:
            L.append(_row(v, R[v]["x1"], R[v]["x2"]))
        L.append("")
        if R is fin:
            L += ["| variant | design gate | p (twins) | twin mean (Rs) | q (BH, 16) | top-5 share | verdict |",
                  "|---|---|---|---|---|---|---|"]
            for v in K.VARIANTS:
                f = R[v]
                L.append(f"| {v} | {des[v]['design_gate']} | {f['p']} | {f['twin_mean']} | {f['q']} | "
                         f"{f['x1'].get('top5_share')} | {f['verdict']} |")
            L.append("")
        L += [f"### {part}: per index (fib 0.273): trades / win / net Rs", "",
              "| variant | " + " | ".join(K.IDX) + " |", "|---|" + "---|" * len(K.IDX)]
        for v in K.VARIANTS:
            per = R[v]["x1"].get("inst", {})
            cells = [f"{per[i]['trades']} / {per[i]['win']:.0%} / {per[i]['net']:+,}" if i in per else "0" for i in K.IDX]
            L.append(f"| {v} | " + " | ".join(cells) + " |")
        L.append("")
        ext = json.loads((K.RESULTS / "extras.json").read_text()) if (K.RESULTS / "extras.json").exists() else {}
        tag = "design" if R is des else "final"
        if ext:
            L += [f"### {part}: per index at 2x costs (fib 0.273): net Rs", "",
                  "| variant | " + " | ".join(K.IDX) + " |", "|---|" + "---|" * len(K.IDX)]
            for v in K.VARIANTS:
                per = ext.get(f"{tag}/{v}", {})
                L.append(f"| {v} | " + " | ".join(f"{per[i]['net']:+,}" if i in per else "0" for i in K.IDX) + " |")
            L.append("")
        for fib in K.FIB_SENS:
            L += [f"### {part}: sensitivity fib {fib} (information only, not gated)", "", HEAD]
            for v in K.VARIANTS:
                L.append(_row(v, R[key(v, fib)]["x1"], R[key(v, fib)]["x2"]))
            L.append("")
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "report":
        extras()
        report()
    else:
        raise SystemExit(__doc__)
