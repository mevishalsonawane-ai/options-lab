"""R197 study (PREREG.md sections 3-5): the 45-test grid, the design-only selection, the extensions, the 2026 second
look, gates and BH.

    python3 -I research/hunt/r197_winrate80/r197study.py grid      # design trades of the 45 grid tests
    python3 -I research/hunt/r197_winrate80/r197study.py select    # Pick-N / Pick-A from design only -> selection.json
    python3 -I research/hunt/r197_winrate80/r197study.py extend    # the picks' 11 extensions + twins + 2x slippage
    python3 -I research/hunt/r197_winrate80/r197study.py second    # 2026 second look for every test
    python3 -I research/hunt/r197_winrate80/r197study.py final     # gates + BH + Wilson intervals -> final.json

Caches in ~/.cache/iraalgo/r197 (never in git).
"""
from __future__ import annotations

import json
import math
import pickle
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling", HERE.parent / "r196_selling_plus"):
    sys.path.insert(0, str(p))
import r195cfg as K  # noqa: E402
import r195rules as RU  # noqa: E402
import r195study as S5  # noqa: E402
import r196engine as E6  # noqa: E402
import r196study as S6  # noqa: E402
import r197engine as E7  # noqa: E402

OUT = K.OUT.parent / "r197"
RESULTS = HERE / "results"
M_PRIOR = 4821
G1 = [f"G1-o{o}-s{s}-t{t}" for o in (0, 2, 4) for s in (25, 50, 100) for t in ("N", 20, 35, 50)]
G2 = [f"G2-o{o}-t{t}" for o in (2, 4, 6) for t in ("N", 30, 50)]
GRID = G1 + G2
FILTERS = ("VIX", "IVRV", "SHOCK")
OTHER_N = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")


def parse(cid: str) -> dict:
    """'G1-o2-s50-t35' / 'G2-o4-tN', optionally 'F<filter>:<grid id>' or '<index>:<grid id>'."""
    flt, und = None, "NIFTY"
    if ":" in cid:
        pre, cid = cid.split(":", 1)
        if pre in FILTERS:
            flt = pre
        else:
            und = pre
    parts = cid.split("-")
    c = dict(fam=parts[0], flt=flt, und=und, id=cid)
    for x in parts[1:]:
        c[x[0]] = None if x[1:] == "N" else int(x[1:])
    return c


def trade(cid, d, decide=None, side=None, mult=1.0):
    c = parse(cid)
    und = c["und"]
    store, Dl, exp = S5.setup(und)
    D, i = store.get(d), Dl.pos.get(d)
    if D is None or D.atm is None or i is None:
        return None
    ex, xday = exp[d], bool(D.expiry)
    if c["flt"] == "VIX" and not RU.vix_ok(Dl, i):
        return None
    if c["flt"] == "IVRV" and not RU.iv_rv_ok(D, und, Dl, i, d, ex):
        return None
    if c["flt"] == "SHOCK" and S6.shock(Dl, i):
        return None
    r = RU.R0920 if decide is None else decide
    k0 = RU.atm(D, und, r)
    if k0 is None:
        return None
    st, o = K.STEP[und], c["o"]
    tgt = None if c["t"] is None else c["t"] / 100.0
    if c["fam"] == "G1":
        legs = [("C", k0 + o * st), ("P", k0 - o * st)]
        return E6.run_short_pair(D, und, d, legs, r, ex, xday, leg_stop=1 + c["s"] / 100.0, target=tgt, mult=mult, tag=cid)
    if xday:
        return None
    bull = True if side is None else side
    sh, lg = (("P", k0 - o * st), ("P", k0 - (o + 4) * st)) if bull else (("C", k0 + o * st), ("C", k0 + (o + 4) * st))
    return E7.run_spread(D, und, d, sh, lg, r, ex, False, stop=0.5, target=tgt, mult=mult, tag=cid)


def _work(args):
    cid, ds, mode, mult = args
    out = []
    for d in ds:
        if mode == "rule":
            out.append((d, trade(cid, d, mult=mult)))
        elif mode == "timing":
            out.append((d, {r: (t["net"] if (t := trade(cid, d, decide=r)) else None)
                            for r in range(RU.R0920, K.row(13, 0) + 1, 5)}))
        else:
            out.append((d, {s: (t["net"] if (t := trade(cid, d, side=s)) else None) for s in (True, False)}))
    return out


def pmap(cid, ds, mode="rule", mult=1.0):
    S5.setup(parse(cid)["und"])
    parts = [ds[i::24] for i in range(24)]
    with Pool(4) as p:
        res = p.map(_work, [(cid, x, mode, mult) for x in parts])
    return dict(sorted((d, v) for r in res for d, v in r))


def fname(prefix, cid):
    return f"{prefix}_{cid.replace(':', '_')}.pkl"


def save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / name, "wb") as f:
        pickle.dump(obj, f)


def load(name):
    with open(OUT / name, "rb") as f:
        return pickle.load(f)


def run(cid, part, mult=1.0):
    return [t for t in pmap(cid, S6.days_of(parse(cid)["und"], part), "rule", mult).values() if t]


def grid():
    for cid in GRID:
        tr = run(cid, "wf")
        save(fname("wf", cid), tr)
        s = S5.stats(tr)
        print(cid, s["n"], s["win"], s["net"], flush=True)


def select():
    out = {}
    for fam_, ids in (("N", G1), ("A", G2)):
        ok = []
        for cid in ids:
            s = S5.stats(load(fname("wf", cid)))
            if s.get("n", 0) and s["win"] >= 0.80 and s["net"] > 0:
                ok.append((s["net"], cid))
        out[f"Pick-{fam_}"] = max(ok)[1] if ok else None
        out[f"qualifying-{fam_}"] = [c for _, c in sorted(ok, reverse=True)]
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "selection.json").write_text(json.dumps(out, indent=1) + "\n")
    print(out)
    return out


def extensions(sel=None) -> list:
    sel = sel or json.loads((RESULTS / "selection.json").read_text())
    ext = []
    for p in (sel["Pick-N"], sel["Pick-A"]):
        if p:
            ext += [p] + [f"{f}:{p}" for f in FILTERS]
    if sel["Pick-N"]:
        ext += [f"{u}:{sel['Pick-N']}" for u in OTHER_N]
    if sel["Pick-A"]:
        ext += [f"BANKNIFTY:{sel['Pick-A']}"]
    return ext


def twin_kind(cid):
    c = parse(cid)
    return "days" if c["flt"] else ("timing" if c["fam"] == "G1" else "side")


def extend():
    for cid in extensions():
        if ":" in cid:
            tr = run(cid, "wf")
            save(fname("wf", cid), tr)
        tr = load(fname("wf", cid))
        save(fname("wf2x", cid), run(cid, "wf", 2.0))
        k = twin_kind(cid)
        if k != "days":
            save(fname("twin", cid), pmap(cid, [t["day"] for t in tr], k))
        print(cid, len(tr), round(sum(t["net"] for t in tr)), flush=True)


def second():
    for cid in GRID + [c for c in extensions() if ":" in c]:
        tr = run(cid, "ho")
        save(fname("ho", cid), tr)
        print(cid, len(tr), round(sum(t["net"] for t in tr)), flush=True)


def wilson(k, n, z=1.96):
    if n == 0:
        return (None, None)
    p = k / n
    den = 1 + z * z / n
    c = (p + z * z / (2 * n)) / den
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den
    return (round(c - h, 3), round(c + h, 3))


def ratio(tr):
    n = np.array([t["net"] for t in tr])
    w, l = n[n > 0], n[n <= 0]
    a, b = (w.mean() if len(w) else 0.0), (-l.mean() if len(l) else 0.0)
    return dict(avg_win=round(float(a)), avg_loss=round(float(b)), loss_to_win=round(float(b / a), 2) if a else None,
                breakeven_win=round(float(b / (a + b)), 3) if a + b else None,
                wilson=wilson(int((n > 0).sum()), len(n)))


def twin_p(cid, tr):
    rng = np.random.default_rng(K.SEED)
    real = sum(t["net"] for t in tr)
    sums = np.zeros(K.N_TWINS)
    if twin_kind(cid) == "days":
        base = np.array([t["net"] for t in load(fname("wf", cid.split(":", 1)[1]))])
        for j in range(K.N_TWINS):
            sums[j] = base[rng.choice(len(base), size=min(len(tr), len(base)), replace=False)].sum()
    else:
        for row in load(fname("twin", cid)).values():
            v = np.array([x for x in row.values() if x is not None])
            if len(v):
                sums += v[rng.integers(0, len(v), K.N_TWINS)]
    from scipy.stats import norm
    sd = float(sums.std(ddof=1))
    return dict(p_emp=round((1 + int((sums >= real).sum())) / (K.N_TWINS + 1), 4),
                p_norm=float(norm.sf((real - sums.mean()) / sd)) if sd > 0 else 1.0)


def final():
    sel = json.loads((RESULTS / "selection.json").read_text())
    ext = extensions(sel)
    m = M_PRIOR + len(GRID) + 6 + 5
    rows, ps = {}, {}
    for cid in GRID + [c for c in ext if ":" in c]:
        wf, ho = load(fname("wf", cid)), load(fname("ho", cid))
        d, h = S5.stats(wf), S5.stats(ho)
        row = dict(design=d, post_regime=S5.stats(wf, lo=K.REGIME), second_look_2026=h, all=S5.stats(wf + ho),
                   design_ratio=ratio(wf), ratio_2026=ratio(ho) if ho else None, boot_p=S5.boot_p(wf),
                   is_pick_or_extension=cid in ext)
        ps[cid] = row["boot_p"]
        if cid in ext:
            tw = twin_p(cid, wf)
            row["twin"] = tw
            ps[cid] = max(ps[cid], tw["p_norm"])
            wf2 = load(fname("wf2x", cid))
            row["net_2x_slip"] = round(sum(t["net"] for t in wf2))
        rows[cid] = row
    q = S5.bh(ps, m)
    for cid, row in rows.items():
        row["bh_q"] = q[cid]
        if not row["is_pick_or_extension"]:
            row["verdict"] = "grid (not selected)"
            continue
        d, h, al = row["design"], row["second_look_2026"], row["all"]
        f = []
        if not (d.get("net", 0) > 0 and row["post_regime"].get("net", 0) > 0):
            f.append("1 design")
        if not h.get("net", 0) > 0:
            f.append("2 2026")
        if d.get("n", 0) < 40 or h.get("n", 0) < 40:
            f.append("3 trades")
        if not (d.get("net_wo_top5", 0) > 0 and h.get("net_wo_top5", 0) > 0):
            f.append("4 without top 5")
        if row["twin"]["p_emp"] >= 0.05:
            f.append("5 twin")
        if q[cid] > 0.10:
            f.append("6 BH")
        if al.get("worst_month", 0) < K.MONTH_STOP or al.get("max_dd", 0) > K.DD_STOP:
            f.append("7 account stop")
        if not row["net_2x_slip"] > 0:
            f.append("8 2x slippage")
        if not (d.get("win", 0) >= 0.80 and h.get("win", 0) >= 0.80):
            f.append("9 win rate 80%")
        row["fails"] = f
        row["verdict"] = "provisional (needs the forward read)" if not f else "fail"
        row["possible_at_56k"] = bool(h.get("n", 0) and h.get("affordable_share", 0) >= 0.95)
    (RESULTS / "final.json").write_text(json.dumps(dict(m=m, selection=sel, tests=rows), indent=1, default=str) + "\n")
    return rows


if __name__ == "__main__":
    a = (sys.argv[1:] or [""])[0]
    if a in ("grid", "select", "extend", "second"):
        globals()[a]()
    elif a == "final":
        for k, v in final().items():
            if v["is_pick_or_extension"]:
                print(k, v["verdict"], v.get("fails"), flush=True)
    else:
        raise SystemExit(__doc__)
