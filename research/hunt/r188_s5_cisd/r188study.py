"""R188 study (PREREG.md). Plain-English notes, 17 Nov 2026.

    python research/hunt/r188_s5_cisd/r188study.py parity   # the faithful replays vs R123 / R128 / R133 -> results/parity.json
    python research/hunt/r188_s5_cisd/r188study.py design   # base + every lever on the design window, NTHR, COMBO -> results/design.json
    python research/hunt/r188_s5_cisd/r188study.py final    # the ONE final read (writes results/FINAL_READ first) + the new month + twins + gates

Nothing here reads a final-period number before `final`: `design` simulates design days only.
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r188cfg as C  # noqa: E402
import r188sim as S  # noqa: E402
import r188data as RD  # noqa: E402
import r123rules as R3  # noqa: E402

FINAL_FLAG = C.RESULTS / "FINAL_READ"


def _date(df: pd.DataFrame, col="day") -> pd.DataFrame:
    df = df.copy()
    df[col] = pd.to_datetime(df[col]).dt.date
    return df


def load_inputs():
    P = RD.load_preds()
    cands = _date(pd.read_parquet(C.TRADES / "cisd_cands.parquet"))
    return P, cands


def write(name: str, obj) -> None:
    C.RESULTS.mkdir(parents=True, exist_ok=True)
    (C.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


# ------------------------------------------------------------------------------------------------ parity
def parity() -> dict:
    P, cands = load_inputs()
    st = S.Store()
    out = {}
    # S5 (a): R123's own S5 trade list from the saved predictions
    sv = pd.concat([_date(pd.read_parquet(C.R123 / f"trades_N1-S5_{p}.parquet")) for p in ("wf", "holdout")])
    PP = P[P.fold != "new"]
    T = R3.trades(PP, "S5")
    a = set(zip(T.und, T.day, T.row, T.j))
    b = set(zip(sv.und, sv.day, sv.row, sv.j))
    out["s5_r123_list"] = dict(rebuilt=len(a), saved=len(b), same=a == b)
    # S5 (b): my fillable picks vs R123's candidates (validates the new month's picks)
    pk = S.s5_picks(PP, st)
    rc = R3.candidates(PP, "S5")
    out["s5_picks"] = dict(rebuilt=len(pk), r123=len(rc),
                           same=set(zip(pk.und, pk.day, pk.row, pk.j)) == set(zip(rc.und, rc.day, rc.row, rc.j)))
    # S5 (c): R128 T2-S5x1 on R123's entries, square-off 15:20
    res_var = S.Var("R128", S.Ex(sq=C.RES_SQ))
    X = S.s5_run(pk, st, res_var, entries=sv.sort_values(["day", "row"]))
    t2 = _date(pd.read_parquet(C.REF / "t2.parquet"))
    t2 = t2[t2.rule == "T2-S5x1"]
    M = X.merge(t2, on=["und", "day", "row"], suffixes=("", "_r128"))
    diff = np.abs(M.net - M.net_r128)
    out["s5_r128"] = dict(replayed=len(X), r128=len(t2), matched=len(M), share_within_1=float((diff <= 1).mean()),
                          max_diff=float(diff.max()))
    for name, sel_m, sel_r in (("wf", X.period == "design", t2.period != "holdout"),
                               ("holdout", X.period == "final", t2.period == "holdout")):
        s = S.money(X[sel_m])
        out["s5_r128"][name] = dict(n=s["n"], win=s["win"], avg_net=s["avg_net"], worst_month=s["worst_month"],
                                    worst_month_name=s["worst_month_name"], r128_n=int(sel_r.sum()),
                                    r128_avg_net=float(t2[sel_r].net.mean()), r128_win=float((t2[sel_r].net > 0).mean()))
    # CISD: R131 S3-V2 + R132 E3 + N-EV, square-off 15:20
    views = S.view_ev(P)
    cvar = S.Var("R133", S.Ex(sq=C.RES_SQ))
    Y = S.cisd_run(cands[cands.period.isin(["design", "final"]) if "period" in cands else cands.day.map(C.period_of)
                         .isin(["design", "final"])], st, views, cvar)
    ref = pd.concat([_date(pd.read_parquet(C.REF / "trades_design.parquet")).assign(part="design"),
                     _date(pd.read_parquet(C.REF / "trades_holdout_seen.parquet")).assign(part="final")])
    ref = ref[ref.v == "S3-V2-E3"]
    ref = ref[[C.period_of(d) in ("design", "final") for d in ref.day]]
    Mm = Y.merge(ref, on=["und", "day", "entry"], suffixes=("", "_r133"), how="outer", indicator=True)
    both = Mm[Mm._merge == "both"]
    dc = np.abs(both.net - both.net_r133)
    out["cisd_r133"] = dict(replayed_trades=len(Y), r133_trades=len(ref), matched=len(both),
                            only_replay=int((Mm._merge == "left_only").sum()),
                            only_r133=int((Mm._merge == "right_only").sum()),
                            share_within_1=float((dc <= 1).mean()) if len(dc) else 0.0,
                            max_diff=float(dc.max()) if len(dc) else None)
    for part in ("design", "final"):
        k = Y[(Y.period == part) & Y.kept]
        s = S.money(k)
        out["cisd_r133"][part] = dict(n=s["n"], win=s["win"], avg_net=s["avg_net"], all_setups=int((Y.period == part).sum()))
    write("parity.json", out)
    return out


# ------------------------------------------------------------------------------------------------ runs
def run_arm(arm: str, v: S.Var, st, P, cands, views, periods) -> pd.DataFrame:
    """The variant's TAKEN trades (kept) on the given periods, with all simulated rows for the twins."""
    if arm == "S5":
        pk = S.s5_picks(P[P.day.map(C.period_of).isin(periods)], st)
        T = S.s5_run(pk, st, v)
        if len(T):
            T["kept"] = ~T.skipped
        return T
    cc = cands[cands.day.map(C.period_of).isin(periods)]
    return S.cisd_run(cc, st, views, v)


def kept(T: pd.DataFrame) -> pd.DataFrame:
    return T[T.kept] if len(T) else T


def summary(T: pd.DataFrame, periods) -> dict:
    return {p: S.money(kept(T)[kept(T).period == p]) if len(T) else S.money(None) for p in periods}


def design() -> dict:
    if FINAL_FLAG.exists():
        print("note: the final has been read; design.json is not rewritten")
        return json.loads((C.RESULTS / "design.json").read_text())
    P, cands = load_inputs()
    st = S.Store()
    views = S.view_ev(P)
    out = {}
    for arm in ("S5", "CISD"):
        res = {}
        base = run_arm(arm, S.variant("base"), st, P, cands, views, ["design"])
        res["base"] = summary(base, ["design"])["design"]
        for lv in C.LEVERS[arm]:
            if lv == "NTHR":
                grid = {}
                for th in C.NTHR_GRID:
                    T = run_arm(arm, S.variant("NTHR", nthr=th), st, P, cands, views, ["design"])
                    grid[th] = summary(T, ["design"])["design"]
                b = res["base"]
                ok = {th: s for th, s in grid.items() if s["win"] > b["win"]}
                pool = ok or grid
                pick = max(pool, key=lambda th: (pool[th]["total"], -abs(th)))
                res["NTHR_grid"] = {str(k): dict(n=s["n"], win=s["win"], total=s["total"]) for k, s in grid.items()}
                res["NTHR_chosen"] = pick
                res["NTHR"] = grid[pick]
                continue
            T = run_arm(arm, S.variant(lv), st, P, cands, views, ["design"])
            res[lv] = summary(T, ["design"])["design"]
        passing = [lv for lv in C.LEVERS[arm] if all(S.part_gates(res[lv], res["base"]).values())]
        ex = [lv for lv in passing if lv in C.EXIT_LEVERS]
        parts = ([max(ex, key=lambda x: res[x]["total"])] if ex else []) + [lv for lv in passing if lv not in C.EXIT_LEVERS]
        res["design_passing"] = passing
        res["COMBO_parts"] = parts if len(parts) >= 2 else []
        if len(parts) >= 2:
            v = S.combine("COMBO", parts, res.get("NTHR_chosen", 0.0))
            res["COMBO"] = summary(run_arm(arm, v, st, P, cands, views, ["design"]), ["design"])["design"]
        out[arm] = res
    write("design.json", out)
    return out


# ------------------------------------------------------------------------------------------------ twins
def exit_nets(a, entry, base_exit, lot, d, u):
    """Net of selling at each minute's close from the entry minute to the base's exit (inclusive); NaN where no close."""
    p0 = float(a[entry, S.O_])
    buy = float(S.FILLS.buy(np.array([p0]), np.array([S.vol5(a, entry, lot)]))[0])
    nets = []
    for r in range(entry, base_exit + 1):
        c = a[r, S.C_]
        if not np.isfinite(c):
            nets.append(np.nan)
            continue
        nets.append(S.result(buy, p0, float(c), S.sell(float(c), S.vol5(a, r, lot), False), lot, d, u, r, "twin")["net"])
    return np.array(nets)


def bar_of(arm, st, row):
    u = row.und
    if arm == "S5":
        g = st.g[u]
        i = st.pos[u][row.day]
        k = float(S.D3.atm(S.D3._last(g.c[i][:row.row + 1]), C.STEP[u]))
        return st.bars[u].get(("WEEK", row.day, "C" if row.side > 0 else "P", k))
    return None


def twin_p(arm, st, B: pd.DataFrame, V: pd.DataFrame, pool: pd.DataFrame, cands_bars) -> float:
    """PREREG section 4 gate 4 over design + final pooled. B / V: the base's / the variant's simulated rows."""
    rng = np.random.default_rng(C.SEED)
    key = ["und", "day", "entry"]
    Bk, Vk = kept(B), kept(V)
    bset = {tuple(x) for x in Bk[key].values}
    vset = {tuple(x) for x in Vk[key].values}
    removed = len(bset - vset)
    added = len(vset - bset)
    # trades in both whose exit changed
    M = Bk.merge(Vk, on=key, suffixes=("", "_v"))
    ch = M[(M.exit != M.exit_v) | (np.abs(M.net - M.net_v) > 0.5)]
    alt = []
    for r in ch.itertuples(index=False):
        a = cands_bars(r)
        if a is None:
            alt.append(np.array([r.net]))
            continue
        x = exit_nets(a, int(r.entry), int(r.exit), C.LOT[r.und], r.day, r.und)
        x = x[np.isfinite(x)]
        alt.append(x if len(x) else np.array([r.net]))
    base_net = Bk.net.values
    base_tot = float(base_net.sum())
    ch_base = ch.net.values
    pool_net = pool.net.values if len(pool) else np.zeros(0)
    obs = float(Vk.net.sum())
    tw = np.empty(C.TWINS)
    for k in range(C.TWINS):
        tot = base_tot
        for i, xs in enumerate(alt):
            tot += xs[rng.integers(len(xs))] - ch_base[i]
        if removed:
            tot -= base_net[rng.choice(len(base_net), min(removed, len(base_net)), replace=False)].sum()
        if added and len(pool_net):
            tot += pool_net[rng.choice(len(pool_net), min(added, len(pool_net)), replace=False)].sum()
        tw[k] = tot
    return float((1 + (tw >= obs).sum()) / (1 + C.TWINS))


def final() -> dict:
    d = json.loads((C.RESULTS / "design.json").read_text())
    if FINAL_FLAG.exists():
        raise SystemExit("refused: the final was already read (results/FINAL_READ)")
    FINAL_FLAG.write_text(json.dumps(dict(read_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                                          note="R188's one read of the levers on 1 Apr - 19 Oct 2026")))
    P, cands = load_inputs()
    st = S.Store()
    views = S.view_ev(P)
    periods = ["design", "final", "new"]
    out, tests = {}, []
    for arm in ("S5", "CISD"):
        nthr = d[arm].get("NTHR_chosen", 0.0)
        specs = {"base": S.variant("base")}
        for lv in C.LEVERS[arm]:
            specs[lv] = S.variant(lv, nthr=nthr)
        if d[arm]["COMBO_parts"]:
            specs["COMBO"] = S.combine("COMBO", d[arm]["COMBO_parts"], nthr)
        runs = {n: run_arm(arm, v, st, P, cands, views, periods) for n, v in specs.items()}
        B = runs["base"]
        Bdf = B[B.period.isin(["design", "final"])]
        if arm == "S5":
            pk = S.s5_picks(P[P.day.map(C.period_of).isin(["design", "final"])], st)
            taken = {tuple(x) for x in kept(Bdf)[["und", "day", "row"]].values}
            rest = pk[[tuple(x) not in taken for x in pk[["und", "day", "row"]].values]]
            pool = S.s5_run(rest, st, S.variant("base"), entries=rest) if len(rest) else pd.DataFrame(columns=["net"])
            barf = lambda r: bar_of("S5", st, r)  # noqa: E731
        else:
            pool = Bdf[~Bdf.kept]
            barf = lambda r: st.bars[r.und].get((r.series, r.day, "C" if r.side > 0 else "P",  # noqa: E731
                                                 _strike(cands, r)))
        res = {}
        for n, T in runs.items():
            res[n] = summary(T, periods)
            if n != "base":
                Tdf = T[T.period.isin(["design", "final"])]
                res[n]["twin_p"] = twin_p(arm, st, Bdf, Tdf, pool, barf)
                tests.append((arm, n))
        out[arm] = res
        # trades for the report's new-month list
        kb = kept(B)
        out[arm]["new_month_base_trades"] = kb[kb.period == "new"][["und", "day", "entry", "side", "net", "why"]] \
            .to_dict("records")
    out["_m"] = dict(tests=len(C.TESTS), prior=C.M_PRIOR, total=C.M_TOTAL)
    out["_design"] = d
    write("final.json", gate(out))
    return out


def gate(out: dict) -> dict:
    """Gates 1-4 from the stored numbers (no data is read). Tests not formed (COMBO) still count in m with p = 1."""
    ps, ids = [], []
    for arm, lv in C.TESTS:
        ids.append((arm, lv))
        ps.append(out[arm].get(lv, {}).get("twin_p", 1.0))
    qs = S.bh_q(ps)
    for (arm, lv), q in zip(ids, qs):
        if lv not in out[arm]:
            out[arm][lv] = dict(not_formed=True, q=q)
            continue
        r = out[arm][lv]
        b = out[arm]["base"]
        gd = S.part_gates(r["design"], b["design"])
        gf = S.part_gates(r["final"], b["final"])
        r["q"] = q
        r["gates"] = dict(design=gd, final=gf, final_n=r["final"]["n"] >= C.MIN_FINAL_N, q=q <= 0.10)
        r["passes_1_3"] = all(gd.values()) and all(gf.values()) and r["final"]["n"] >= C.MIN_FINAL_N
        r["passes"] = r["passes_1_3"] and q <= 0.10
    return out


def regate() -> dict:
    """Re-derive the gates in results/final.json from its stored numbers (17 Nov: the paisa comparison fix)."""
    out = json.loads((C.RESULTS / "final.json").read_text())
    for arm in ("S5", "CISD"):
        for lv in list(out[arm]):
            if isinstance(out[arm][lv], dict) and out[arm][lv].get("not_formed"):
                del out[arm][lv]
    out = gate(out)
    out["_regated"] = "gates re-derived from the stored numbers with the paisa comparison (17 Nov); no data re-read"
    write("final.json", out)
    return {a: {k: v.get("passes_1_3") for k, v in out[a].items() if isinstance(v, dict) and "q" in v}
            for a in ("S5", "CISD")}


def _strike(cands: pd.DataFrame, r) -> float:
    x = cands[(cands.und == r.und) & (cands.day == r.day) & (cands.entry == r.entry)]
    return float(x.strike.iloc[0]) if len(x) else np.nan


if __name__ == "__main__":
    cmd = sys.argv[1]
    res = {"parity": parity, "design": design, "final": final, "regate": regate}[cmd]()
    print(json.dumps(res, indent=1, default=str)[:6000])
