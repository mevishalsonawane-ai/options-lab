"""R27 study (PREREG.md). Run in this order; each step writes research/hunt/r27/results/<step>.json (+ .md).

    python3 research/hunt/r27/study.py rebuild     # step 1: R3's nights rebuilt; checks; B0 by year (design only)
    python3 research/hunt/r27/study.py describe    # step 2: the held option after 09:16, DESC block only (<= 2022)
    python3 research/hunt/r27/study.py test        # step 4a: walk-forward test years 2023, 2024, 2025 (Jan-Sep)
    python3 research/hunt/r27/study.py holdout     # step 4b: the locked holdout, ONCE (writes a flag; refuses again)

`test` and `holdout` refuse to run unless PREREG.md is committed and unchanged (its sha256 is recorded in the output).
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone

import numpy as np

import r27cfg as K
import r27data as D
import r27lib as R

PREREG = K.HERE / "PREREG.md"
FLAG = K.RESULTS / "HOLDOUT_READ.flag"


def _dump(name: str, obj, md: str | None = None):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / f"{name}.json").write_text(json.dumps(obj, indent=1, default=str))
    if md is not None:
        (K.RESULTS / f"{name}.md").write_text(md)


def _all(parts):
    return {u: D.load(u, parts) for u in K.UNDS}


# ------------------------------------------------------------------------------------------------ step 1
def rebuild():
    out = {}
    for u in K.UNDS:
        ns = D.load(u, ("desc", "wf", "hold"))
        sig = Counter()
        for n in ns:
            y = str(n.d.year) if K.part(n.d) != "hold" else "hold"
            sig[(y, "nights")] += 1
            sig[(y, "r17")] += n.r17 != 0
            sig[(y, "r3_before_contract_rules")] += (R_side := D.r3_side(n.r17, n.buz)) != 0
            if R_side != 0 and not n.tradable:
                sig[(y, "blocked_" + n.expiry_ok_reason)] += 1
            sig[(y, "r3")] += n.r3 != 0
            sig[(y, "no_breadth")] += not np.isfinite(n.breadth)
            sig[(y, "no_z")] += not np.isfinite(n.buz)
            if n.r3 != 0:
                t = R.trade(n, n.r3, R.B0)
                sig[(y, "r3_priced")] += t is not None
                sig[(y, "r3_calls")] += n.r3 > 0
        years = sorted({k[0] for k in sig})
        out[u] = {y: {k[1]: int(v) for k, v in sig.items() if k[0] == y} for y in years}
    # B0 by year, design only (the holdout's B0 is read with the holdout step)
    b0 = {}
    for u in K.UNDS:
        for n in D.load(u, ("desc", "wf")):
            if n.r3 == 0:
                continue
            t = R.trade(n, n.r3, R.B0)
            if t is None:
                continue
            k = f"{u} {n.d.year}"
            b = b0.setdefault(k, dict(trades=0, net=0.0, gross=0.0))
            b["trades"] += 1
            b["net"] += t.net
            b["gross"] += t.gross
    for v in b0.values():
        v["net"], v["gross"] = round(v["net"]), round(v["gross"])
    # expiry calendar check: the data's expiry days vs the brain's NSE weekday rules
    from research.brain import calendar as CAL
    cal = {}
    for u in K.UNDS:
        ns = D.load(u, ("desc", "wf", "hold"))
        blocked = sorted({str(n.d) for n in ns if n.expiry_ok_reason == "entry_on_expiry_day"})
        sess = {n.d for n in ns} | {n.nd for n in ns}
        rule = CAL.expiries(u, ns[0].d, ns[-1].d, sess)
        rule_days = {str(d) for d in rule.date} if len(rule) else set()
        cal[u] = dict(data_expiry_days=len(blocked), rule_days=len(rule_days),
                      in_data_not_rule=sorted(set(blocked) - rule_days)[:30], in_rule_not_data=sorted(rule_days - set(blocked))[:30])
    res = dict(signals=out, b0_design_by_year=b0, expiry_check=cal,
               x2_reference=dict(pre_trades=dict(NIFTY=393, BANKNIFTY=332), pre_by_year_net_both={
                   "2020": 11000, "2021": 48000, "2022": 41000, "2023": 46000, "2024": 39000, "2025_jan_sep": 78000},
                   hold_trades=155, note="X2_CONNECTIONS.md: NIFTY from 2020 (h31 data), BANKNIFTY from Aug 2021"))
    _dump("rebuild", res)
    print(json.dumps(res, indent=1, default=str))


# ------------------------------------------------------------------------------------------------ step 2
WIN = {"09:30": D.minute(9, 30), "10:00": D.minute(10, 0), "10:30": D.minute(10, 30), "11:00": D.minute(11, 0)}


def path_stats(n, side):
    """MFE / MAE of the held option after the 09:16 open (premium Rs per unit and Rs on the lot) and of the index in
    the position's favour (points), to each window end."""
    b0 = R.b0_exit(n, side)
    if b0 is None:
        return None
    opt, ix = n.opt[side], n.ix
    p0, k0, i0 = b0.raw, b0.row, ix[0, C]
    out = {}
    for w, t in WIN.items():
        h, lo = opt[k0:t, H], opt[k0:t, L]
        ih, il = ix[1:t, H], ix[1:t, L]
        fav = (ih - i0) if side > 0 else (i0 - il)
        adv = (il - i0) if side > 0 else (i0 - ih)
        cl = D.last_valid(opt[:, C], t - 1, 10)
        out[w] = dict(mfe_p=float(np.nanmax(h) - p0), mae_p=float(np.nanmin(lo) - p0), end_p=float(cl - p0),
                      mfe_i=float(np.nanmax(fav)), mae_i=float(np.nanmin(adv)))
    return out


O, C, H, L = D.O, D.C, D.H, D.L


def describe():
    rows = []
    for u in K.UNDS:
        for n in D.load(u, ("desc",)):
            if n.r3 == 0:
                continue
            ps = path_stats(n, n.r3) if n.r3 in n.opt else None
            if ps is None or not np.isfinite(n.entry.get(n.r3, np.nan)):
                continue
            m = R.morning(n, n.r3)
            ix = n.ix
            f5 = n.r3 * (ix[4, C] / ix[0, O] - 1) if np.isfinite(ix[4, C]) and np.isfinite(ix[0, O]) else np.nan
            scale = n.x / n.x_nifty if u == "BANKNIFTY" else 1.0
            rows.append(dict(und=u, d=n.d, qty=n.qty, scale=scale, **m, first5=f5, **{f"{w}_{k}": v for w, x in ps.items() for k, v in x.items()}))
    import pandas as pd
    df = pd.DataFrame(rows)

    def summar(g):
        o = dict(n=len(g))
        for w in WIN:
            o[f"{w} MFE prem (median)"] = round(float(g[f"{w}_mfe_p"].median()), 1)
            o[f"{w} MAE prem (median)"] = round(float(g[f"{w}_mae_p"].median()), 1)
            o[f"{w} end-start prem (mean)"] = round(float(g[f"{w}_end_p"].mean()), 1)
            o[f"{w} end Rs/lot (mean)"] = round(float((g[f"{w}_end_p"] * g.qty).mean()))
            o[f"{w} MFE idx pts (median, NIFTY-scaled)"] = round(float((g[f"{w}_mfe_i"] / g.scale).median()), 1)
            o[f"{w} MAE idx pts (median, NIFTY-scaled)"] = round(float((g[f"{w}_mae_i"] / g.scale).median()), 1)
        for p in (20, 30):
            o[f"reach +{p} idx (NIFTY-scaled) by 10:30 %"] = round(100 * float((g["10:30_mfe_i"] / g.scale >= p).mean()), 1)
            o[f"reach +{p} prem by 10:30 %"] = round(100 * float((g["10:30_mfe_p"] >= p).mean()), 1)
            o[f"fall -{p} prem by 10:30 %"] = round(100 * float((g["10:30_mae_p"] <= -p).mean()), 1)
        return o

    splits = {
        "all": df.index == df.index,
        "gap with": df.gap > 0, "gap against": df.gap <= 0,
        "09:15 bar with": df["first"] > 0, "09:15 bar against": df["first"] <= 0,
        "gap and 09:15 bar with": (df.gap > 0) & (df["first"] > 0),
        "first 5 min with (post-09:16 info)": df.first5 > 0, "first 5 min against (post-09:16 info)": df.first5 <= 0,
        "VIX up at 09:15": df.vix_chg > 0, "VIX down at 09:15": df.vix_chg <= 0,
        "held option OI up (09:15 vs 15:29)": df.oi_chg > 0, "held option OI down": df.oi_chg <= 0,
        "09:15 beyond yesterday's high/low (with)": df.vs_high > 0, "09:15 inside yesterday's range": df.vs_high <= 0,
        "09:15 above/below yesterday's close (with)": df.vs_close > 0, "09:15 back through yesterday's close": df.vs_close <= 0,
    }
    res = {}
    for u in ("ALL",) + K.UNDS:
        g0 = df if u == "ALL" else df[df.und == u]
        res[u] = {k: summar(g0[m[g0.index]]) for k, m in splits.items() if m[g0.index].sum() >= 5}
    meta = dict(period=f"DESC block only: lake start .. {K.DESC_END}", trades=len(df),
                by_und=df.und.value_counts().to_dict(), note="premium = option Rs per unit vs the 09:16 open; index points in "
                "the position's favour vs the 09:15 close, BANKNIFTY divided by its BN/NIFTY level ratio (NIFTY-scaled)")
    _dump("describe", dict(meta=meta, splits=res))
    lines = [f"# R27 step 2: descriptive, DESC block only ({meta['period']}), {len(df)} R3 trades {meta['by_und']}", ""]
    keys = ["n", "10:00 MFE prem (median)", "10:00 MAE prem (median)", "10:30 end-start prem (mean)", "10:30 end Rs/lot (mean)",
            "10:30 MFE idx pts (median, NIFTY-scaled)", "10:30 MAE idx pts (median, NIFTY-scaled)", "reach +20 idx (NIFTY-scaled) by 10:30 %",
            "reach +30 idx (NIFTY-scaled) by 10:30 %", "reach +20 prem by 10:30 %", "fall -20 prem by 10:30 %"]
    for u, sp in res.items():
        lines += [f"## {u}", "", "| split | " + " | ".join(keys) + " |", "|" + "---|" * (len(keys) + 1)]
        for k, o in sp.items():
            lines.append(f"| {k} | " + " | ".join(str(o[x]) for x in keys) + " |")
        lines.append("")
    w = ["n"] + [f"{w} {s}" for w in WIN for s in ("end-start prem (mean)", "end Rs/lot (mean)")]
    lines += ["## By window (ALL)", "", "| split | " + " | ".join(w) + " |", "|" + "---|" * (len(w) + 1)]
    for k, o in res["ALL"].items():
        lines.append(f"| {k} | " + " | ".join(str(o[x]) for x in w) + " |")
    md = "\n".join(lines) + "\n"
    (K.RESULTS / "describe.md").write_text(md)
    print(md)


# ------------------------------------------------------------------------------------------------ steps 4a / 4b
def _prereg_sha() -> str:
    st = subprocess.run(["git", "status", "--porcelain", str(PREREG)], capture_output=True, text=True, cwd=K.ROOT).stdout
    if not PREREG.exists() or st.strip():
        sys.exit("PREREG.md must exist and be committed unchanged before this step")
    return hashlib.sha256(PREREG.read_bytes()).hexdigest()


def _sessions(parts, year=None) -> int:
    days = set()
    for u in K.UNDS:
        for n in D.load(u, parts):
            if year is None or n.d.year == year:
                days.add(n.d)
    return len(days)


def run_period(nights: list, sessions: int, twins: bool = True) -> dict:
    out = {}
    trades_b0 = [t for t in (R.trade(n, n.r3, R.B0) for n in nights) if t is not None]
    out["B0"] = dict(metrics=R.metrics(trades_b0, sessions))
    for s in R.VARIANTS:
        tr = [t for t in (R.trade(n, n.r3, s) for n in nights) if t is not None]
        o = dict(metrics=R.metrics(tr, sessions), p=R.paired_p(tr), why=dict(Counter(t.why for t in tr)))
        o["by_und"] = {u: R.metrics([t for t in tr if t.und == u], sessions) for u in K.UNDS}
        if twins and s.hold not in ("always", "never"):
            rate = o["metrics"]["held"] / max(o["metrics"]["trades"], 1)
            o["twin"] = R.twin_p(nights, s, sum(t.net for t in tr), rate)
        out[s.id] = o
    q = R.bh([out[s.id]["p"] for s in R.VARIANTS])
    for s, qq in zip(R.VARIANTS, q):
        out[s.id]["bh_q8"] = qq
    return out


def _family_count() -> int:
    """Tries already logged in R3's family ('overnight': h31's 4,080 + X2's 5) before R27's entries."""
    r = subprocess.run([sys.executable, str(K.ROOT / "research/experiments/validate.py"), "--json"], capture_output=True,
                       text=True, cwd=K.ROOT)
    j = json.loads(r.stdout)
    n = int(j["overnight"]["variants"])
    return n - sum(1 for f in (K.ROOT / "research/experiments/entries").glob("r27-*.yaml"))


def test():
    sha = _prereg_sha()
    res = dict(prereg_sha256=sha, years={}, pooled=None)
    for y in K.WF_YEARS:
        ns = [n for u in K.UNDS for n in R.r3_nights(D.load(u, ("wf",))) if n.d.year == y]
        res["years"][y] = run_period(ns, _sessions(("wf",), y), twins=False)
    ns_wf = [n for u in K.UNDS for n in R.r3_nights(D.load(u, ("wf",)))]
    res["pooled"] = run_period(ns_wf, _sessions(("wf",)))
    ns_desc = [n for u in K.UNDS for n in R.r3_nights(D.load(u, ("desc",)))]
    res["desc_in_sample"] = run_period(ns_desc, _sessions(("desc",)), twins=False)
    # anchored walk-forward pick: each test year trades the variant (or B0) with the best total diff vs B0 on ALL
    # earlier years (DESC block included); B0 when no variant beat it
    by_year = {"desc": {s.id: res["desc_in_sample"][s.id]["metrics"]["diff"] for s in R.VARIANTS}}
    for y in K.WF_YEARS:
        by_year[y] = {s.id: res["years"][y][s.id]["metrics"]["diff"] for s in R.VARIANTS}
    picks, tot = [], 0
    keys = ["desc"] + list(K.WF_YEARS)
    for i, y in enumerate(K.WF_YEARS):
        past = {s.id: sum(by_year[k][s.id] for k in keys[: i + 1]) for s in R.VARIANTS}
        best = max(past, key=past.get)
        pick = best if past[best] > 0 else "B0"
        gain = by_year[y][pick] if pick != "B0" else 0
        picks.append(dict(year=y, pick=pick, past_diff=past.get(best), diff_that_year=gain))
        tot += gain
    res["wf_pick"] = dict(picks=picks, total_diff_vs_b0=tot)
    # PASS gates (PREREG.md)
    fam = _family_count()
    res["family_tries_before"] = fam
    qf = R.bh([res["pooled"][s.id]["p"] for s in R.VARIANTS] + [1.0] * fam)[: len(R.VARIANTS)]
    for s, q_fam in zip(R.VARIANTS, qf):
        o = res["pooled"][s.id]
        o["bh_q_with_family"] = q_fam
        m = o["metrics"]
        yrs = [res["years"][y][s.id]["metrics"]["diff"] for y in K.WF_YEARS]
        b0m = res["pooled"]["B0"]["metrics"]
        gates = dict(
            g1_diff_pos=m["diff"] > 0,
            g2_bh_q8=o["bh_q8"] <= 0.10,
            g3_years=sum(1 for x in yrs if x > 0) >= 2,
            g4_stress=(m["net15"] - res["pooled"]["B0"]["metrics"]["net15"]) > 0,
            g5_risk=m["max_dd"] >= 1.10 * b0m["max_dd"] and m["worst_day"] >= 1.10 * b0m["worst_day"],
            g6_twin=(o["twin"]["p"] <= 0.05) if "twin" in o else None,
            g7_n=m["trades"] >= 100,
        )
        o["gates"] = gates
        o["PASS_WF"] = all(v for v in gates.values() if v is not None)
    _dump("test", res, _md_period(res, "R27 step 4a: walk-forward test years 2023, 2024, 2025 (Jan-Sep)"))
    print((K.RESULTS / "test.md").read_text())


def holdout():
    sha = _prereg_sha()
    if FLAG.exists():
        sys.exit("the holdout was read once already: " + FLAG.read_text())
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    FLAG.write_text(f"read {datetime.now(timezone.utc).isoformat(timespec='seconds')} prereg {sha}\n")
    ns = [n for u in K.UNDS for n in R.r3_nights(D.load(u, ("hold",)))]
    res = dict(prereg_sha256=sha, period=f"{K.HOLD_START} .. lake end", pooled=run_period(ns, _sessions(("hold",))))
    wf = json.loads((K.RESULTS / "test.json").read_text())
    passed = [s.id for s in R.VARIANTS if wf["pooled"][s.id]["PASS_WF"]]
    res["wf_passed"] = passed
    res["holdout_pass"] = {}
    if passed:
        q = R.bh([res["pooled"][v]["p"] for v in passed])
        res["holdout_bh_over_passed"] = dict(zip(passed, q))
        for v, qq in zip(passed, q):
            o = res["pooled"][v]
            tw = o.get("twin", {}).get("p")
            res["holdout_pass"][v] = bool(o["metrics"]["diff"] > 0 and qq <= 0.05 and (tw is None or tw <= 0.05)
                                          and o["metrics"]["trades"] >= 30)
    _dump("holdout", res, _md_period(res, "R27 step 4b: LOCKED HOLDOUT (read once)", hold=True))
    print((K.RESULTS / "holdout.md").read_text())


def _md_period(res, title, hold=False):
    P = res["pooled"]
    cols = ["trades", "held_pct", "win_pct", "avg_win", "avg_loss", "per_trade", "per_day", "net", "diff", "net15",
            "green_days_pct", "worst_day", "max_dd", "gave_back", "rescued", "helped", "hurt", "big_gain_b0_kept"]
    L = [f"# {title}", "", f"PREREG sha256 {res['prereg_sha256']}", "", "| variant | " + " | ".join(cols) +
         " | p (vs B0) | BH q (8) | twin p |", "|" + "---|" * (len(cols) + 4)]
    for k in ["B0"] + [s.id for s in R.VARIANTS]:
        m = P[k]["metrics"]
        L.append(f"| {k} | " + " | ".join(str(m.get(c)) for c in cols) +
                 f" | {P[k].get('p', '')} | {P[k].get('bh_q8', '')} | {P[k].get('twin', {}).get('p', '')} |")
    if not hold:
        L += ["", "## By year: total diff vs B0 (Rs) / trades", "", "| variant | " + " | ".join(str(y) for y in K.WF_YEARS) + " | DESC (in-sample) |",
              "|---|" + "---|" * (len(K.WF_YEARS) + 1)]
        for s in R.VARIANTS:
            L.append(f"| {s.id} | " + " | ".join(f"{res['years'][y][s.id]['metrics']['diff']} / {res['years'][y][s.id]['metrics']['trades']}"
                                                 for y in K.WF_YEARS) + f" | {res['desc_in_sample'][s.id]['metrics']['diff']} |")
        L += ["", f"B0 by year: " + ", ".join(f"{y} {res['years'][y]['B0']['metrics']['net']} ({res['years'][y]['B0']['metrics']['trades']} trades)" for y in K.WF_YEARS),
              "", f"Anchored walk-forward pick: {res['wf_pick']}", "", "## Gates", ""]
        for s in R.VARIANTS:
            L.append(f"- {s.id}: {P[s.id]['gates']} -> PASS_WF {P[s.id]['PASS_WF']}")
    L += ["", "## By index", ""]
    for s in R.VARIANTS:
        L.append(f"- {s.id}: " + "; ".join(f"{u} trades {P[s.id]['by_und'][u].get('trades')}, diff {P[s.id]['by_und'][u].get('diff')}, "
                                        f"gave back {P[s.id]['by_und'][u].get('gave_back')}" for u in K.UNDS) + f"; exits {P[s.id]['why']}")
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"rebuild": rebuild, "describe": describe, "test": test, "holdout": holdout}.get(cmd, lambda: sys.exit(__doc__))()
