"""R113 study (PREREG.md): every arm's trades by entry / exit time, holding time, weekday, expiry, gap, VIX and day shape;
patterns across arms; the phone's paper record; and the pre-registered Part D entry filters.

    python research/hunt/r113_time_of_day/adapters/core.py export      # the four paper index arms' trades
    (the other arms' adapters in adapters/ write the rest of ~/.cache/iraalgo/r113/trades)
    python research/hunt/r113_time_of_day/r113study.py describe        # Parts A-B -> results/describe.json, cross.json
    python research/hunt/r113_time_of_day/r113study.py paper DIAG_DIR  # Part C -> results/paper.json
    python research/hunt/r113_time_of_day/r113study.py select          # Part D: which rules qualify (design only)
    python research/hunt/r113_time_of_day/r113study.py design          # Part D: design + walk-forward of those rules
    python research/hunt/r113_time_of_day/r113study.py final           # Part D: the ONE final read (refuses a second)

26 Oct 2026.
"""
from __future__ import annotations

import gzip
import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import r113cfg as K  # noqa: E402
import r113lib as L  # noqa: E402


def dump(name: str, obj) -> None:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


# ================================================================================================ inputs
def load_arms() -> dict:
    out = {}
    for f in sorted(K.TRADES.glob("*.csv")):
        df = pd.read_csv(f, dtype={"expiry": str})
        if df.empty:
            continue
        missing = [c for c in L.COLS if c not in df.columns]
        if missing:
            print("skip", f.name, "missing", missing)
            continue
        out[f.stem] = df[L.COLS].copy()
    return out


def load_meta() -> dict:
    out = {}
    for f in sorted(K.TRADES.glob("*.json")):
        try:
            out[f.stem.split(".")[0]] = out.get(f.stem.split(".")[0], {}) | json.loads(f.read_text())
        except (ValueError, TypeError):
            pass
    return out


def day_table() -> dict:
    """{(und, day): (open, high, low, close, prev_close)} for the indices (index_daily) and MCX (near-month futures)."""
    out = {}
    a = pd.read_parquet(K.LAKE / "index_daily.parquet")
    b = pd.read_parquet(K.LAKE / "fut_daily_continuous.parquet")
    b = b[b.symbol.isin(K.MCX_UNDS)]
    for df in (a, b):
        df = df.sort_values(["symbol", "date"])
        df["pc"] = df.groupby("symbol").close.shift(1)
        for s, d, o, h, l, c, pc in zip(df.symbol, pd.to_datetime(df.date).dt.date, df.open, df.high, df.low, df.close,
                                        df.pc):
            out[(str(s).upper(), d)] = (float(o), float(h), float(l), float(c), float(pc))
    # MCX names the arms trade by their mini contracts
    for (s, d), v in list(out.items()):
        if s == "SILVERM":
            out[("SILVERMIC", d)] = v
    return out


def vix_series() -> pd.Series:
    a = pd.read_parquet(K.LAKE / "index_daily.parquet")
    v = a[a.symbol == "INDIAVIX"].copy()
    v["d"] = pd.to_datetime(v.date).dt.date
    return v.set_index("d").close.sort_index()


# ================================================================================================ Parts A and B
def common(df: pd.DataFrame) -> pd.DataFrame:
    """The R101-R112 window (20 Nov 2024 - 13 Oct 2026), the one every arm shares."""
    return df[(df.day >= K.START) & (df.day <= K.FINAL_END)]


def cmd_describe() -> None:
    arms = load_arms()
    meta = load_meta()
    days, vix = day_table(), vix_series()
    cuts = L.vix_cuts(vix)
    lab = {a: L.label_trades(df, days, vix, cuts) for a, df in arms.items()}
    desc, overview = {}, {}
    for a, df in lab.items():
        desc[a] = {}
        for per in ("design", "final", "all"):
            g = df if per == "all" else df[df.period == per]
            if len(g):
                desc[a][per] = L.describe_all(g)
        cw = common(df)
        desc[a]["common"] = L.describe_all(cw) if len(cw) else {}
        nets = df.net_rs.values
        overview[a] = dict(
            name=K.ARM_NAME.get(a, a), trades=int(len(df)), net=round(float(nets.sum())),
            win=round(float((nets > 0).mean()), 3), first=str(df.day.min()), last=str(df.day.max()),
            periods={p: dict(trades=int((df.period == p).sum()), net=round(float(df[df.period == p].net_rs.sum())))
                     for p in sorted(df.period.unique())},
            common=dict(trades=int(len(cw)), net=round(float(cw.net_rs.sum()))),
            first_hour_share=L.first_hour_share(df), top10_days=L.top_share(L.daily_series(df).values),
            top10_trades=L.top_share(nets), unlabelled=int(df.gap_b.isna().sum()),
            source=meta.get(a, {}).get("source") or meta.get(a, {}).get("source_code"),
            parity=meta.get(a, {}).get("parity") or meta.get(a, {}).get("periods"),
            signal=meta.get(a, {}).get("signal") or meta.get(a, {}).get("signal_source"))
    dump("describe.json", dict(written=datetime.now(timezone.utc).isoformat(), vix_cuts=cuts, arms=desc))
    dump("overview.json", overview)
    dump("cross.json", cross(lab))
    print(json.dumps({a: (o["trades"], o["net"]) for a, o in overview.items()}))


def cross(lab: dict) -> dict:
    """Part B on the common window (all arms) and the arm's whole record."""
    out = {"best_worst": {}, "sign": {}, "corr": {}, "concentration": {}}
    for a, df in lab.items():
        rows = [r for r in L.describe_all(df)["entry_b"] if r["trades"]]
        if rows:
            best, worst = max(rows, key=lambda r: r["net"]), min(rows, key=lambda r: r["net"])
            out["best_worst"][a] = dict(best=best, worst=worst, first_hour_share=L.first_hour_share(df))
        cw = common(df)
        out["sign"][a] = {r["bucket"]: (r["net"], r["trades"]) for r in L.describe_all(cw)["entry_b"]} if len(cw) else {}
        out["concentration"][a] = dict(top10_days=L.top_share(L.daily_series(df).values),
                                       top10_trades=L.top_share(df.net_rs.values), days=int(df.day.nunique()))
    names = sorted(lab)
    for per, lo, hi in (("design", K.START, K.DESIGN_END), ("final", K.FINAL_START, K.FINAL_END)):
        ser = {a: L.daily_series(lab[a][(lab[a].day >= lo) & (lab[a].day <= hi)]) for a in names}
        ser = {a: s for a, s in ser.items() if len(s) >= 10}
        m = {}
        for i, a in enumerate(sorted(ser)):
            for b in sorted(ser)[i + 1:]:
                r, n = L.pair_corr(ser[a], ser[b])
                m[f"{a}|{b}"] = (None if np.isnan(r) else round(r, 3), n)
        out["corr"][per] = m
    # shared winning days of the four paper index arms (common window)
    core = [a for a in K.CORE_ARMS if a in lab]
    if core:
        ser = {a: L.daily_series(common(lab[a])) for a in core}
        idx = sorted(set().union(*[set(s.index) for s in ser.values()]))
        tot = pd.Series({d: sum(float(ser[a].get(d, 0.0)) for a in core) for d in idx})
        top = tot.sort_values(ascending=False).index[: max(1, int(np.ceil(0.1 * len(tot))))]
        green = [sum(1 for a in core if float(ser[a].get(d, 0.0)) > 0) for d in top]
        worst = tot.sort_values().index[: max(1, int(np.ceil(0.1 * len(tot))))]
        red = [sum(1 for a in core if float(ser[a].get(d, 0.0)) < 0) for d in worst]
        out["shared_days"] = dict(days=len(tot), top_days=len(top), mean_green_arms_on_top_days=round(float(np.mean(green)), 2),
                                  top_days_all_green=int(sum(1 for g in green if g == len(core))),
                                  mean_red_arms_on_worst_days=round(float(np.mean(red)), 2),
                                  top10_share_of_book=L.top_share(tot.values), arms=core)
    return out


# ================================================================================================ Part C
def cmd_paper(diag_dir: str) -> None:
    trips = []
    for f in sorted(Path(diag_dir).glob("diag-*.json.gz")):
        try:
            d = json.load(gzip.open(f))
        except (OSError, ValueError):
            continue
        trips += L.pair_orders(d.get("orders"), str(d.get("day")))
    rows = {}
    for t in trips:
        b = L.bucket(L.hhmm_minute(t["entry_at"]), L.time_buckets("SILVERM" if "SILVER" in t["symbol"] or "NATURALGAS"
                                                                   in t["symbol"] else "NIFTY"))
        r = rows.setdefault(t["source"], {}).setdefault(b, dict(trades=0, wins=0, pct=[]))
        r["trades"] += 1
        r["wins"] += int(t["points"] > 0)
        r["pct"].append(t["pct"])
    out = {s: {b: dict(trades=r["trades"], wins=r["wins"], avg_pct=round(float(np.nanmean(r["pct"])), 2))
               for b, r in bs.items()} for s, bs in rows.items()}
    dump("paper.json", dict(days=sorted({t["day"] for t in trips}), round_trips=len(trips), by_source=out,
                            note="phone diagnostics (untrusted, redacted); points and % only, too few to judge"))
    print(json.dumps(out, indent=1))


# ================================================================================================ Part D
def core():
    sys.path.insert(0, str(HERE / "adapters"))
    import core as C
    return C


def cmd_select() -> None:
    """Which rules qualify: the design net of the entries each rule removes, from the B0 design trades only."""
    arms = load_arms()
    out = {}
    for rid, sp in K.RULES.items():
        for a in sp["arms"]:
            df = arms[a]
            d = df[df.period == "design"]
            m = pd.to_datetime(d.entry_ts).dt.hour * 60 + pd.to_datetime(d.entry_ts).dt.minute
            gone = d[[L.in_drop(x, sp["drop"]) for x in m]]
            out[f"{rid}-{a}"] = dict(rule=rid, arm=a, removed_trades=int(len(gone)), removed_net=round(float(gone.net_rs.sum())),
                                     qualifies=L.qualifies(float(gone.net_rs.sum())))
    dump("partD_select.json", dict(written=datetime.now(timezone.utc).isoformat(), tests=out))
    print(json.dumps(out, indent=1))


def _run(C, a, lo, hi, drop):
    us = C.units(a, lo, hi)
    b0 = C.walk(a, us)
    tr = C.walk(a, C.drop_rule(us, drop))
    return us, b0, tr


def cmd_design() -> None:
    sel = json.loads((K.RESULTS / "partD_select.json").read_text())["tests"]
    C = core()
    out = {}
    for tid, s in sel.items():
        if not s["qualifies"]:
            continue
        drop = K.RULES[s["rule"]]["drop"]
        row = {}
        for per, lo, hi in (("design", K.START, K.DESIGN_END), ("wf", K.WF_START, K.DESIGN_END)):
            us, b0, tr = _run(C, s["arm"], lo, hi, drop)
            row[per] = L.summary(tr)
            row["b0_" + per] = L.summary(b0)
        out[tid] = row
        print(tid, row, flush=True)
    dump("partD_design.json", out)


def cmd_final() -> None:
    if K.FINAL_FLAG.exists():
        raise SystemExit("the final was already read once (results/FINAL_READ)")
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    sel = json.loads((K.RESULTS / "partD_select.json").read_text())["tests"]
    des = json.loads((K.RESULTS / "partD_design.json").read_text())
    C = core()
    C.open_final()
    print("final B0 lists:", json.dumps(C.export(("final",)), default=str), flush=True)
    rows, ps = {}, []
    for tid in des:
        s = sel[tid]
        drop = K.RULES[s["rule"]]["drop"]
        us, b0, tr = _run(C, s["arm"], K.FINAL_START, K.FINAL_END, drop)
        k = C.n_removed(us, drop)
        tw = C.twins(s["arm"], us, k)
        f = L.summary(tr)
        p = L.twin_p(f["net"], tw)
        rows[tid] = dict(final=f, b0_final=L.summary(b0), removed=k, twin_p=round(p, 4),
                         twin_median=round(float(np.median(tw))), **des[tid])
        ps.append(p)
        print(tid, rows[tid], flush=True)
    n_own = len(sel)
    q_all = L.bh(ps, K.N_PRIOR + n_own) if ps else []
    q_own = L.bh(ps, n_own) if ps else []
    for (tid, r), qa, qo in zip(rows.items(), q_all, q_own):
        r["q_all"], r["q_r113"] = round(float(qa), 4), round(float(qo), 4)
        r["gates"] = L.gates(r["design"], r["wf"], r["final"], r["b0_wf"], r["b0_final"], float(qa))
    dump("partD_final.json", dict(written=datetime.now(timezone.utc).isoformat(), m_total=K.N_PRIOR + n_own,
                                  tests_counted=n_own, rows=rows))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "describe":
        cmd_describe()
    elif cmd == "paper":
        cmd_paper(sys.argv[2])
    elif cmd == "select":
        cmd_select()
    elif cmd == "design":
        cmd_design()
    elif cmd == "final":
        cmd_final()
    else:
        print(__doc__)
