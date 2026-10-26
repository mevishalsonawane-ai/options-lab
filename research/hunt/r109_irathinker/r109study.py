"""R109 study (PREREG.md): IraThinker's rules on real prints.

    python research/hunt/r109_irathinker/r109study.py design    # history + design (+ walk-forward window) -> results/design.json
    python research/hunt/r109_irathinker/r109study.py final     # ONE read of 1 Apr - 13 Oct (+ 14-19 Oct) -> results/final.json
    python research/hunt/r109_irathinker/r109study.py decide    # gates + BH -> results/decision.json

Trade tables go to <R109_OUT>/trades_<phase>.pkl (never in git).
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timedelta, timezone

import numpy as np

import r109cfg as K
import r109data as DA
import r109engine as E

TWIN_LO, TWIN_HI = K.row(10, 16), K.row(14, 30)          # mom60 twin entry window (the rule's entry minutes)
STW_LO, STW_HI = K.row(9, 31), K.row(14, 30)             # strangle twin entry window
PHASES = {"design": ("history", "design"), "final": ("final", "post")}


# ------------------------------------------------------------------------------------------------ thresholds
def hist_q90(und: str) -> tuple[float, int]:
    """q90 of |ret60| over every eligible signal bar (start 10:15-14:29) of the history period, index bars only."""
    days = DA.load(und, "MONTH")
    vals = []
    for d in sorted(days):
        if not (K.START <= d <= K.HIST_END):
            continue
        r = E.ret60(days[d].idx[3])[K.FIRST_SIG_ROW: K.LAST_SIG_ROW + 1]
        vals.append(np.abs(r[np.isfinite(r)]))
    v = np.concatenate(vals) if vals else np.array([])
    return float(np.quantile(v, 0.90)), int(len(v))


def threshold(test: dict) -> float:
    t = test["thr"]
    if t == "hist":
        return hist_q90(test["und"])[0]
    return K.THRESH[t]


# ------------------------------------------------------------------------------------------------ mom60
def run_mom60(name: str, test: dict, periods: tuple, rng) -> dict:
    und, series = test["und"], test["series"]
    days = DA.load(und, series)
    exps = DA.expiry_dates(und, series)
    thr = threshold(test)
    rows, skipped = [], 0
    for d in sorted(days):
        D = days[d]
        per = K.period_of(d)
        if per not in periods or d < K.START or D.atm is None:
            continue
        expiry = E.nearest_on_or_after(exps, d)
        miss = [0]

        def trade(i, call, D=D, d=d, expiry=expiry, miss=miss):
            t = E.mom60_trade_at(D, und, d, expiry, i, call)
            if t is None:
                miss[0] += 1
                return False, None, None
            return True, t["dec"], t

        fired = E.mom60_day(D.idx[3], thr, trade)
        skipped += miss[0]
        for i, call, t in fired:
            t.update(day=d, period=per, dte=(expiry - d).days if expiry else None)
            rows.append(t)
    # IraThinker's BS tape on the same signals (monthly rule only; the weekly variant is not IraThinker's)
    iv_cache = {}
    for t in rows:
        if series != "MONTH":
            t["bs_pct"] = None
            continue
        d = t["day"]
        if d not in iv_cache:
            iv_cache[d] = DA.rv20_before(und, d)
        r = E.mom60_bs(days[d].idx[3], t["sig"], t["call"], K.STEP[und], d, iv_cache[d], und in K.BSE)
        t["bs_pct"], t["bs_reason"] = (r if r is not None else (None, None))
    # twins: same day, same direction, random entry minute, same exits
    for t in rows:
        if t["period"] == "history":                  # never gated: no twins
            t["twins"] = np.full(K.POOL, np.nan)
            continue
        D = days[t["day"]]
        pool = []
        tries = 0
        while len(pool) < K.POOL and tries < 20 * K.POOL:
            tries += 1
            e = int(rng.integers(TWIN_LO, TWIN_HI + 1))
            k = E.atm_at(D, und, e - 1)
            con = D.con.get(("C" if t["call"] else "P", float(k))) if np.isfinite(k) else None
            if con is None:
                continue
            tw = E.long_trade(con, D.idx, e, K.lot(und, t["day"]), t["day"], E.nearest_on_or_after(exps, t["day"]),
                              und in K.BSE, k, t["call"])
            if tw is not None:
                pool.append(tw["net"])
        t["twins"] = np.array(pool + [np.nan] * (K.POOL - len(pool)))
    return dict(rows=rows, thr=thr, skipped=skipped)


# ------------------------------------------------------------------------------------------------ strangle
def run_strangle(name: str, test: dict, periods: tuple, rng) -> dict:
    und = test["und"]
    days = DA.load(und, "MONTH")
    exps = DA.expiry_dates(und, "MONTH")
    sess = sorted(days)
    rows, skipped = [], []
    first = min(d for d in sess if d >= K.START and days[d].atm is not None)
    mon = first + timedelta(days=(7 - first.weekday()) % 7)
    while mon <= K.END:
        expiry = E.nearest_on_or_after(exps, mon)
        thu = mon + timedelta(days=3)
        tgt = expiry if (expiry is not None and expiry <= thu) else thu
        inside = [x for x in sess if mon <= x <= tgt]
        exit_day = inside[-1] if inside else None              # calendar only: no price is read here
        if exit_day is None or K.period_of(exit_day) not in periods:
            mon += timedelta(days=7)
            continue
        if mon not in days:
            skipped.append((str(mon), "Monday holiday"))
            mon += timedelta(days=7)
            continue
        w = E.strangle_week(days, mon, expiry, K.lot(und, mon))
        if w is None:
            skipped.append((str(mon), "no 09:30 bar or a leg without a print"))
            mon += timedelta(days=7)
            continue
        w["period"] = K.period_of(exit_day)
        w["dte"] = (expiry - mon).days
        bsr = E.strangle_bs(days, mon, DA.rv20_before(und, mon))
        w["bs_pct"] = bsr["net_pct"] if bsr else None
        w["bs_gross_pct"] = bsr["gross_pct"] if bsr else None
        pool, tries = [], 0
        while w["period"] != "history" and len(pool) < K.POOL and tries < 20 * K.POOL:
            tries += 1
            e = int(rng.integers(STW_LO, STW_HI + 1))
            tw = E.strangle_week(days, mon, expiry, K.lot(und, mon), entry_row=e)
            if tw is not None:
                pool.append(tw["net"])
        w["twins"] = np.array(pool + [np.nan] * (K.POOL - len(pool)))
        rows.append(w)
        mon += timedelta(days=7)
    return dict(rows=rows, skipped=skipped, thr=None)


# ------------------------------------------------------------------------------------------------ overlap with R72 / R51
def arm_signals() -> dict:
    """{'R72'|'R51': {und: {day: [(entry_row, side)]}}} from R101's signal code on our index bars (signals, not trades)."""
    R, r51data = DA.r51()
    import fam_r44
    sys.path.insert(0, str(K.HERE.parent / "r72"))
    import r72rules as P72
    unds = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
    bars, out = {}, {"R72": {}, "R51": {}}
    for u in unds:                                    # one store in memory at a time (index bars only are read)
        r51data._CACHE[u] = DA.load(u, "MONTH")
        bars[u] = fam_r44.bars5(u)
        sig = fam_r44.signal_list(u, "donchian20")
        out["R51"][u] = {dd: [(5 * (bb + 1), side) for bb, side in v] for dd, v in sig.items()}
        r51data._CACHE.pop(u, None)
        DA.drop(u, "MONTH")
    panel = P72.Panel(bars)
    four = P72.breadth_breakout(panel, 4)
    for u in unds:
        d, b = panel.f[u]["d"], panel.f[u]["b"]
        per = {}
        for i, s, _ in four[u]:
            per.setdefault(d[i], []).append((5 * (int(b[i]) + 1), "C" if s > 0 else "P"))
        out["R72"][u] = per
    return out


def overlap(rows: list, und: str, sigs: dict) -> dict:
    out = {}
    lo, hi = K.row(9, 20), K.row(15, 10)
    for arm in ("R72", "R51"):
        S = sigs[arm].get(und, {})
        same_day = near = 0
        for t in rows:
            side = "C" if t["call"] else "P"
            ss = [er for er, sd in S.get(t["day"], []) if sd == side and lo <= er <= hi]
            if ss:
                same_day += 1
            if any(t["f"] - 60 <= er <= t["xr"] for er in ss):
                near += 1
        out[arm] = dict(n=len(rows), same_day_same_way=same_day, within_60m_or_hold=near)
    return out


# ------------------------------------------------------------------------------------------------ summaries
def summarise(name: str, test: dict, res: dict, periods: tuple, rng) -> dict:
    rows = res["rows"]
    out = dict(thr=res.get("thr"), skipped=res.get("skipped") if test["kind"] == "mom60" else len(res["skipped"]),
               periods={})
    sets = {p: [r for r in rows if r["period"] == p] for p in periods}
    if "design" in periods:
        sets["wf"] = [r for r in rows if r["period"] == "design" and r["day"] >= K.WF_START]
    for p, rr in sets.items():
        s = E.period_stats([dict(r, day=r["day"]) for r in rr])
        if rr and p != "history":
            nets = np.array([r["net"] for r in rr])
            pool = np.vstack([r["twins"] for r in rr])
            s["twin_p"] = E.twin_p(nets, pool, K.DRAWS, rng)
            s["twin_mean"] = float(np.nanmean(pool))
            s["twin_median_total"] = float(np.nanmedian(pool) * len(rr))
            if test["kind"] == "strangle":
                s["boot_p"] = E.boot_p(nets, rng)
        bs = [r["bs_pct"] for r in rr if r.get("bs_pct") is not None]
        s["bs_mean_pct"] = float(np.mean(bs)) if bs else None
        s["bs_win"] = float(np.mean(np.array(bs) > 0)) if bs else None
        if test["kind"] == "mom60":
            g = [r["gross_pct"] for r in rr]
            s["real_gross_pct"] = float(np.mean(g)) if g else None
            s["real_gross_minus_07"] = float(np.mean(g) - 0.7) if g else None
            s["real_net_pct"] = float(np.mean([r["net"] / (r["raw_e"] * K.lot(test["und"], r["day"])) * 100
                                              for r in rr])) if rr else None
            s["calls"] = int(sum(r["call"] for r in rr))
            s["dte_0_2"] = _sub(rr, lambda r: r["dte"] is not None and r["dte"] <= 2)
            s["dte_3_7"] = _sub(rr, lambda r: r["dte"] is not None and 3 <= r["dte"] <= 7)
            s["after_1510"] = int(sum(r["xr"] > K.row(15, 10) for r in rr))
        else:
            s["net_pct_mean"] = float(np.mean([r["net_pct"] for r in rr])) if rr else None
            s["gross_pct_mean"] = float(np.mean([r["gross_pct"] for r in rr])) if rr else None
            s["pooled_net_pct"] = (float(sum(r["net"] for r in rr) / sum(r["credit"] * r["lot"] for r in rr) * 100)
                                   if rr else None)
            s["margin_mean"] = float(np.mean([r["margin"] for r in rr])) if rr else None
            s["worst5"] = sorted([(str(r["day"]), round(r["net"]), round(r["net_pct"], 1)) for r in rr],
                                 key=lambda x: x[1])[:5]
            s["weeks_le_13k"] = int(sum(r["net"] <= -13_000 for r in rr))
            s["weeks_le_40k"] = int(sum(r["net"] <= -40_000 for r in rr))
            s["exp_weeks"] = int(sum(r["exp_week"] for r in rr))
            s["stale_legs"] = int(sum(r["stale"] for r in rr))
        if p == "wf":
            s["quarters"] = [round(sum(r["net"] for r in rr if lo <= r["day"] <= hi)) for lo, hi in K.WF_Q]
        out["periods"][p] = s
    return out


def _sub(rr, f) -> dict:
    x = [r for r in rr if f(r)]
    return dict(n=len(x), net=round(sum(r["net"] for r in x)), gross_pct=(float(np.mean([r["gross_pct"] for r in x]))
                                                                        if x else None))


def jsonable(x):
    if isinstance(x, dict):
        return {str(k): jsonable(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [jsonable(v) for v in x]
    if isinstance(x, (np.floating, float)):
        return None if not np.isfinite(x) else round(float(x), 6)
    if isinstance(x, (np.integer,)):
        return int(x)
    if isinstance(x, (date,)):
        return str(x)
    return x


def run_phase(phase: str) -> dict:
    periods = PHASES[phase]
    rng = np.random.default_rng(K.SEED + (0 if phase == "design" else 1))
    out = {"_meta": dict(phase=phase, run=datetime.now(timezone.utc).isoformat(timespec="seconds"), draws=K.DRAWS,
                         pool=K.POOL, seed=K.SEED, n_prior=K.N_PRIOR)}
    trades = {}
    sigs = arm_signals()
    for name, test in K.TESTS.items():
        res = run_mom60(name, test, periods, rng) if test["kind"] == "mom60" else run_strangle(name, test, periods, rng)
        s = summarise(name, test, res, periods, rng)
        if test["kind"] == "mom60":
            s["overlap"] = {p: overlap([r for r in res["rows"] if r["period"] == p], test["und"], sigs)
                            for p in periods}
        out[name] = s
        trades[name] = [{k: v for k, v in r.items() if k != "twins"} for r in res["rows"]]
        if test["kind"] == "strangle":
            out[name]["skipped_weeks"] = res["skipped"]
        DA.drop(test["und"], test["series"])
        print(name, json.dumps(jsonable({p: {k: s["periods"][p].get(k) for k in ("n", "net", "win", "twin_p")}
                                         for p in s["periods"]})), flush=True)
    if phase == "design":
        out["_meta"]["hist_q90"] = {u: hist_q90(u) for u in ("NIFTY", "MIDCPNIFTY")}
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / f"trades_{phase}.pkl", "wb") as f:
        pickle.dump(trades, f)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(jsonable(out), indent=1))
    return out


# ------------------------------------------------------------------------------------------------ gates
def decide() -> dict:
    D = json.loads((K.RESULTS / "design.json").read_text())
    F = json.loads((K.RESULTS / "final.json").read_text())
    names = list(K.TESTS)
    ps, rows = [], {}
    for n in names:
        t = K.TESTS[n]
        d, w, f = D[n]["periods"].get("design", {}), D[n]["periods"].get("wf", {}), F[n]["periods"].get("final", {})
        fails = []
        for lab, s in (("design", d), ("walk-forward", w), ("final", f)):
            if not (s.get("n", 0) and s.get("net", 0) > 0):
                fails.append(f"{lab}: net not above 0")
            if not (s.get("twin_p") is not None and s["twin_p"] < K.GATE_P):
                fails.append(f"{lab}: does not beat its twin (p {s.get('twin_p')})")
        if d.get("n", 0) < K.MIN_DESIGN:
            fails.append(f"design trades {d.get('n', 0)} < {K.MIN_DESIGN}")
        if f.get("n", 0) < K.MIN_FINAL:
            fails.append(f"final trades {f.get('n', 0)} < {K.MIN_FINAL}")
        pp = [s.get("twin_p") if s.get("twin_p") is not None else 1.0 for s in (d, w, f)]
        if t["kind"] == "strangle":
            bp = d.get("boot_p", 1.0)
            pp.append(bp if bp is not None else 1.0)
            if not (bp is not None and bp < K.GATE_P):
                fails.append(f"design bootstrap p {bp} >= 0.05")
            for lab, s in (("design", d), ("walk-forward", w), ("final", f)):
                if s.get("worst_month") is not None and s["worst_month"] < K.ACCOUNT_MONTH:
                    fails.append(f"{lab}: worst month Rs {s['worst_month']:,.0f} below -Rs 13,000")
                if s.get("max_dd", 0) >= K.ACCOUNT_DD:
                    fails.append(f"{lab}: drawdown Rs {s['max_dd']:,.0f} >= Rs 40,000")
        p = max(pp)
        ps.append(p)
        rows[n] = dict(p=p, fails=fails)
    q_all = E.bh(ps, len(ps) + K.N_PRIOR)
    q_own = E.bh(ps)
    q_var = E.bh(ps, len(ps) + K.N_ALL_VARIANTS)
    for i, n in enumerate(names):
        r = rows[n]
        r.update(q=float(q_all[i]), q_r109=float(q_own[i]), q_all_variants=float(q_var[i]))
        if q_all[i] > K.GATE_Q:
            r["fails"].append(f"BH q {q_all[i]:.3g} > 0.10 (m = {len(ps) + K.N_PRIOR:,})")
        r["verdict"] = "PASS" if not r["fails"] else "FAIL"
    out = dict(tests=rows, m=len(ps) + K.N_PRIOR, n_prior=K.N_PRIOR)
    (K.RESULTS / "decision.json").write_text(json.dumps(jsonable(out), indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        run_phase("design")
    elif cmd == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("the final window was already read (results/FINAL_READ); a second read is refused")
        if not (K.RESULTS / "design.json").exists():
            raise SystemExit("run design first")
        K.RESULTS.mkdir(parents=True, exist_ok=True)
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
        run_phase("final")
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
