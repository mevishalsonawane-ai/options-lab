#!/usr/bin/env python3
"""R92 study steps (PREREG.md). Research only, 21 Oct 2026.

    python3 research/hunt/r92_orb_time/r92study.py tables design   # outcome tables + signals per session (cache)
    python3 research/hunt/r92_orb_time/r92study.py design          # the 257 cells on DESIGN (Aug 2021 - Mar 2026)
    python3 research/hunt/r92_orb_time/r92study.py holdout         # the ONE read of the HOLDOUT (1 Apr - 19 Oct 2026)

`holdout` writes results/HOLDOUT_READ first and refuses to run when it exists.
"""
from __future__ import annotations

import json
import pickle
import sys
import zlib
from collections import defaultdict
from datetime import datetime, timezone
from multiprocessing import Pool

import numpy as np

import r92cfg as K
import r92data as D
import r92engine as G

CELLS = G.cells()


# ------------------------------------------------------------------------------------------------ per-session cache
def _tab_path(part: str, und: str):
    return K.OUT / f"tab_{part}_{und}.pkl"


def session_records(und: str, part: str) -> list[dict]:
    """Per session: its keys, day type, every arm's signals for every range length, and the outcome tables."""
    ss = D.sessions(und, part)
    dts = G.day_types(D.calendar(und))
    need = G.tables_needed(CELLS)
    ms = sorted({c.m for c in CELLS})
    out = []
    for s in ss:
        s.opts = {k: a.astype(np.float64) for k, a in s.opts.items()}
        tabs = {}
        for tm, x in need:
            bars = range(G.FIRST_BAR, G.LAST_BAR + 1)
            if x != "15:10":
                last = max(b for b in range(G.NB) if G.entry_min(b) <= G.clock(x) - 15)
                bars = range(G.FIRST_BAR, last + 1)
            tabs[(tm, x)] = G.outcome_table(s, tm, G.SQ[x], bars)
        sigs = {(a, m): G.signals(s, a, m) for a in G.ARMS for m in ms}
        out.append(dict(und=und, day=s.day, expiry=bool(s.expiry), lot=s.lot, dt=dts.get(s.day), tabs=tabs, sigs=sigs))
    return out


def _build(args):
    und, part = args
    recs = session_records(und, part)
    _tab_path(part, und).write_bytes(pickle.dumps(recs))
    return und, len(recs)


def build_tables(part: str):
    K.OUT.mkdir(parents=True, exist_ok=True)
    with Pool(min(4, len(K.UNDS))) as p:
        for und, n in p.imap_unordered(_build, [(u, part) for u in K.UNDS]):
            print(part, und, n, "sessions", flush=True)


def load_records(part: str) -> list[dict]:
    if part == "holdout" and not K.HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout is locked (PREREG A2)")
    out = []
    for u in K.UNDS:
        out += pickle.loads(_tab_path(part, u).read_bytes())
    return out


# ------------------------------------------------------------------------------------------------ one cell
def cell_trades(c: G.Cell, recs: list[dict], unds=None) -> list[dict]:
    unds = unds or c.unds
    out = []
    for i, r in enumerate(recs):
        if r["und"] not in unds:
            continue
        tab = r["tabs"][(c.tmult, c.exit)]
        sig = r["sigs"][(c.arm, c.m)]
        for side, b in G.cell_trades(c, _S(r), sig, tab, r["dt"]):
            j = 0 if side > 0 else 1
            out.append(dict(i=i, und=r["und"], day=r["day"], side=side, j=j, bar=b, net=float(tab.net[j, b]),
                            pts=float(tab.pts[j, b]), why=G.WHY[int(tab.why[j, b])], expiry=r["expiry"]))
    return out


class _S:
    """The session fields a cell's day filter reads."""
    def __init__(self, r):
        self.expiry = r["expiry"]


def summarize(tr: list[dict], c: G.Cell, recs: list[dict], twins: int, seed: int) -> dict:
    nets = np.array([t["net"] for t in tr]) if tr else np.zeros(0)
    by_day = defaultdict(float)
    for t in tr:
        by_day[(t["und"], t["day"])] += t["net"]
    months, months_u = defaultdict(float), defaultdict(float)
    years, unds = defaultdict(lambda: [0.0, 0]), defaultdict(lambda: [0.0, 0])
    for t in tr:
        months[(t["day"].year, t["day"].month)] += t["net"]
        months_u[(t["und"], t["day"].year, t["day"].month)] += t["net"]
        years[t["day"].year][0] += t["net"]
        years[t["day"].year][1] += 1
        unds[t["und"]][0] += t["net"]
        unds[t["und"]][1] += 1
    wm = min(months.items(), key=lambda x: x[1]) if months else None
    wmu = {}
    for (u, y, m), v in months_u.items():
        if u not in wmu or v < wmu[u][1]:
            wmu[u] = (f"{y}-{m:02d}", round(v))
    srt = np.sort(nets)[::-1]
    wins, losses = nets[nets > 0], nets[nets <= 0]
    out = dict(
        id=c.id, family=c.family, arm=c.arm, mode=c.mode, label=c.label, slot=list(c.slot), ext=c.ext, m=c.m,
        exit=c.exit, day=list(c.day), unds=list(c.unds),
        trades=len(tr), sessions=sum(1 for r in recs if r["und"] in c.unds), traded_sessions=len(by_day),
        win_rate=float((nets > 0).mean()) if len(nets) else 0.0,
        net=float(nets.sum()), exp=float(nets.mean()) if len(nets) else 0.0,
        avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
        net_ex_top5=float(srt[5:].sum()) if len(srt) else 0.0,
        top5_share=float(srt[:5].sum() / nets.sum()) if len(nets) and nets.sum() > 0 else None,
        pts_mean=float(np.mean([t["pts"] for t in tr])) if tr else 0.0,
        pts_win=float(np.mean([t["pts"] > 0 for t in tr])) if tr else 0.0,
        per_und={u: [round(v[0]), v[1]] for u, v in sorted(unds.items())},
        per_year={str(y): [round(v[0]), v[1]] for y, v in sorted(years.items())},
        worst_month=[f"{wm[0][0]}-{wm[0][1]:02d}", round(wm[1])] if wm else None,
        worst_month_und=wmu,
        months_green=sum(v > 0 for v in months.values()), months=len(months),
        exits={w: sum(t["why"] == w for t in tr) for w in G.WHY},
        money_p=G.money_p(np.array(list(by_day.values()))),
    )
    # twins
    rng = np.random.default_rng(seed)
    pool = G.twin_pool(c)
    cache = {}
    real = []
    for t in tr:
        if t["i"] not in cache:
            tab = recs[t["i"]]["tabs"][(c.tmult, c.exit)]
            cache[t["i"]] = (tab.net, tab.exit_row >= 0)
        real.append((t["i"], t["j"], t["bar"], True))
    tw = G.twins([cache[i] for i, _, _, _ in real], [(k, j, b, x) for k, (_, j, b, x) in enumerate(real)], pool, twins,
                 rng) if real else np.zeros(twins)
    out["twin_mean"] = float(tw.mean())
    out["twin_p"] = float((1 + (tw >= out["net"]).sum()) / (1 + twins))
    return out


def seed_of(cid: str) -> int:
    return K.SEED * 100003 + zlib.crc32(cid.encode()) % 100000


def evaluate(part: str, recs: list[dict]) -> list[dict]:
    res = []
    for c in CELLS:
        tr = cell_trades(c, recs)
        res.append(summarize(tr, c, recs, K.TWINS, seed_of(c.id)))
    q = G.bh([r["money_p"] for r in res])
    qt = G.bh([r["twin_p"] for r in res])
    for r, a, b in zip(res, q, qt):
        r["q"], r["q_twin"] = float(a), float(b)
    return res


# ------------------------------------------------------------------------------------------------ gates
def design_gates(r: dict) -> dict:
    yrs = r["per_year"]
    full = [yrs.get(str(y), [0, 0])[0] for y in (2022, 2023, 2024, 2025)]
    und_pos = sum(v[0] > 0 for v in r["per_und"].values())
    need_u = 3 if len(r["unds"]) == 5 else 2
    g = dict(
        G1=r["net"] > 0 and r["trades"] >= K.MIN_DESIGN_TRADES,
        G2=r["q"] <= K.BH_Q,
        G3=r["twin_p"] < 0.05,
        G4=sum(v > 0 for v in full) >= 3 and und_pos >= need_u,
        G5=r["net_ex_top5"] > 0,
        G6=all(v[1] >= K.MONTH_STOP for v in r["worst_month_und"].values()),
    )
    if r["family"] == "H12":
        g = dict(G1=r["net"] > 0, G2=r["q"] <= K.BH_Q, G3=r["money_p"] < 0.05)
    g["pass"] = all(g.values())
    return g


def holdout_gates(r: dict) -> dict:
    if r["family"] == "H12":
        g = dict(H1=r["net"] > 0)
    else:
        g = dict(H1=r["net"] > 0, H2=r["trades"] >= K.MIN_HOLDOUT_TRADES, H3=r["twin_p"] < 0.10,
                 H4=all(v[1] >= K.MONTH_STOP for v in r["worst_month_und"].values()))
    g["pass"] = all(g.values())
    return g


def nested_wf(res: list[dict]) -> list[dict]:
    """For each test year, the cell with the best net over the earlier design years, traded in that year."""
    out = []
    for y in (2023, 2024, 2025, 2026):
        def past(r):
            return sum(v[0] for k, v in r["per_year"].items() if int(k) < y)
        best = max(res, key=past)
        nxt = best["per_year"].get(str(y), [0, 0])
        out.append(dict(year=y, pick=best["id"], label=best["label"], past=round(past(best)), test_net=nxt[0],
                        test_trades=nxt[1]))
    return out


# ------------------------------------------------------------------------------------------------ steps
def design():
    recs = load_records("design")
    res = evaluate("design", recs)
    for r in res:
        r["gates"] = design_gates(r)
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), part="design", m=len(CELLS),
               sessions={u: sum(1 for r in recs if r["und"] == u) for u in K.UNDS}, cells=res, nested_wf=nested_wf(res),
               passers=[r["id"] for r in res if r["gates"]["pass"]])
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    print("design cells", len(res), "passers", out["passers"], flush=True)


def holdout():
    if K.HOLDOUT_FLAG.exists():
        raise SystemExit("the holdout was already read (results/HOLDOUT_READ): R92 reads it once")
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    K.HOLDOUT_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    build_tables("holdout")
    recs = load_records("holdout")
    res = evaluate("holdout", recs)
    dsg = {r["id"]: r for r in json.loads((K.RESULTS / "design.json").read_text())["cells"]}
    # 13-19 Oct 2026: the sessions no ORB-family study read (reported, never gated)
    unseen = [r for r in recs if r["day"] >= K.UNSEEN_START]
    for r in res:
        c = next(x for x in CELLS if x.id == r["id"])
        tr = [t for t in cell_trades(c, unseen, K.UNDS if c.family == "H12" else None)]
        r["unseen"] = [round(sum(t["net"] for t in tr)), len(tr)]
        if c.family == "H12":       # its holdout: FINNIFTY + MIDCPNIFTY + SENSEX Apr-Oct, plus BANKNIFTY / NIFTY 13-19 Oct
            extra = [t for t in tr if t["und"] in K.R3_HOUR_UNDS]
            r["h12_holdout"] = [round(r["net"] + sum(t["net"] for t in extra)), r["trades"] + len(extra)]
        r["gates"] = holdout_gates(r if c.family != "H12" else dict(r, net=r["h12_holdout"][0]))
        r["design_pass"] = dsg[r["id"]]["gates"]["pass"]
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), part="holdout", m=len(CELLS),
               sessions={u: sum(1 for r in recs if r["und"] == u) for u in K.UNDS},
               unseen_sessions={u: sum(1 for r in unseen if r["und"] == u) for u in K.UNDS}, cells=res,
               passers=[r["id"] for r in res if r["design_pass"] and r["gates"]["pass"]])
    (K.RESULTS / "holdout.json").write_text(json.dumps(out, indent=1, default=str))
    print("holdout cells", len(res), "passers", out["passers"], flush=True)


if __name__ == "__main__":
    cmd = sys.argv[1:2]
    if cmd == ["tables"]:
        part = sys.argv[2]
        if part != "design":
            raise SystemExit("only `holdout` builds the holdout tables")
        build_tables(part)
    elif cmd == ["design"]:
        design()
    elif cmd == ["holdout"]:
        holdout()
    elif cmd == ["count"]:
        from collections import Counter
        print(len(CELLS), Counter(c.family for c in CELLS))
    else:
        print(__doc__)
