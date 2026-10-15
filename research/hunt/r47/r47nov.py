"""R47 amendment 4 (15 Oct 2026, LABELLED): the "Nov 2024+ view" Boss asked for ("don't test any data before that").

    python3 research/hunt/r47/r47nov.py            # -> results/nov24.json (statistics only, no market data)
    python3 research/hunt/r47/r47nov.py --report   # -> results/nov24_report.md

What it re-runs, with NO trade before 20 Nov 2024:
  * the 10 frozen candidates C1-C10 (results/candidates.json) and the O1 option-chart rule that would have been rank 10
    after the look-ahead fix (results/candidates_v3.json, NIFTY premium ORB; non-default: option-chart signal);
  * the per-family best variants of the exploration (results/design_v3.json, "best single variant (100+ trades)");
  * an honest Nov-2024-only walk-forward re-selection inside each of those (family, index, kind) groups: the best
    variant of N1 (20 Nov 2024 - 30 Jun 2025) traded in N2 (1 Jul 2025 - 31 Mar 2026), and the best of N1+N2 traded in
    the final test.
Spans: design 20 Nov 2024 - 31 Mar 2026; final test 1 Apr - 13 Oct 2026 (the lake's last session).

Honest limits, said up front:
  * The final test OVERLAPS R47's holdout (10 Nov 2025 - 13 Oct 2026), which was already read once; and the fixed
    rules were chosen on design data up to 31 Oct 2025, which covers most of this view's design span. So the fixed
    rules' numbers here are a re-cut of seen data, not a new test. Only the re-selection's final-test numbers are out of
    sample for the re-selection (still on a span R47 had seen).
  * Rows before 20 Nov 2024 (from 1 Jun 2024) are read only as indicator warm-up (previous closes, 10-55 session
    channels, 60-session PCR ranks, VIX); no trade, selection or model training uses them.
Measurement unchanged from R47: one contract per leg from entry to exit, R17 'liq' fills, dated charges, the
stray-print guard (amendment 1) and the foreign-rows filter (amendment 2).
"""
from __future__ import annotations

import json
import os
import pickle
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from datetime import date

import numpy as np
import pandas as pd

import r47cfg as K
import r47data as D
import r47ml as ML
import r47run as R
import r47sig as S
from r47holdout import bh, exit_tuple, stats

NOV_START = date(2024, 11, 20)
WARMUP = date(2024, 6, 1)
N1 = (date(2024, 11, 20), date(2025, 6, 30))
N2 = (date(2025, 7, 1), date(2026, 3, 31))
DESIGN = (N1[0], N2[1])
FINAL = (date(2026, 4, 1), date(2026, 10, 13))
SPANS = {"N1": N1, "N2": N2, "design": DESIGN, "final": FINAL}
OUT_JSON = K.RESULTS / "nov24.json"
T0 = time.time()


def say(*a):
    print(f"[{time.time() - T0:6.0f}s]", *a, flush=True)


def span_mask(days_ord, span) -> np.ndarray:
    d = np.asarray(days_ord, dtype=np.int64)
    return (d >= span[0].toordinal()) & (d <= span[1].toordinal())


# ------------------------------------------------------------------------------------------------ data
def load_nov(und: str, cache: bool | None = None) -> D.Und:
    """Index panel and fixed-contract stores from WARMUP to the lake's end, foreign rows dropped (amendment 2).
    Cached in R47_OUT unless R47_NOV_NOCACHE is set (short of disk)."""
    if cache is None:
        cache = not os.environ.get("R47_NOV_NOCACHE")
    path = K.OUT / f"r47_{und}_nov24.pkl"
    if cache and path.exists():
        return pickle.loads(path.read_bytes())
    old = D.WARMUP_FROM
    D.WARMUP_FROM = WARMUP
    try:
        end = date(2099, 1, 1)
        days, (O, H, L, C) = D.index_panel(und, end)
        stores = {s: D.build_store(und, s, days, end, C) for s in K.SERIES[und]}
        vixC = D._vix_on(days, end)
    finally:
        D.WARMUP_FROM = old
    u = D.Und(und, "nov24", days, O, H, L, C, D.daily_close(days, C), vixC, D.daily_close(days, vixC), stores,
              len(days) - 1)
    if cache:
        K.OUT.mkdir(parents=True, exist_ok=True)
        path.write_bytes(pickle.dumps(u, protocol=pickle.HIGHEST_PROTOCOL))
    return u


def only_from(u, ev: S.Events, first: date = NOV_START) -> S.Events:
    keep = u.days[ev.di] >= first.toordinal()
    return S.Events(ev.di[keep], ev.m[keep], ev.d[keep], ev.cid[keep], ev.win, ev.days)


# ------------------------------------------------------------------------------------------------ events
def m1_probs(u, f):
    """M1 trees trained on Nov-2024+ sessions only: N2 rows predicted by a model fitted on N1, final rows by one fitted
    on N1+N2. N1 rows get no probability (nothing earlier may be used)."""
    di, m, X, y = ML.feature_rows(u, f)
    d = u.days[di]
    p = np.full(len(di), np.nan)
    for tr_span, te_span in ((N1, N2), (DESIGN, FINAL)):
        q = ML.fit_predict(di, X, y, span_mask(d, tr_span), span_mask(d, te_span))
        p = np.where(np.isfinite(q), q, p)
    return di, m, p


def events(u, fam: str, params: dict, f, cache: dict, instr: str | None = None) -> S.Events:
    if fam == "M1_gbm":
        if "m1" not in cache:
            cache["m1"] = m1_probs(u, f)
        di, m, pr = cache["m1"]
        ev = ML.events_from_probs(di, m, pr, params["th"])
    elif fam == "E2_expiry_vol":
        ev = S.e2_expiry_vol(u, tuple(params["at"]), R.series_of(instr or "straddle"))
    else:
        p = {k: (tuple(v) if isinstance(v, list) else v) for k, v in params.items()}
        ev = S.SIGNALS[fam](u, **p)
    return only_from(u, ev)


# ------------------------------------------------------------------------------------------------ twins
def twin_net(u, rule, ev, trades, span, rng, n_twins=K.N_TWINS):
    """R47's twins inside one span: each trade re-entered on a random session of the span (expiry sessions for expiry
    families) at a random minute of the family window, same direction, instrument, exits and sizing."""
    sess = np.flatnonzero(span_mask(u.days, span))
    if ev.days == "expiry":
        ex = S.expiry_set(u, R.series_of(rule["instr"]))
        sess = np.array([d for d in sess if d in ex], dtype=np.int64)
    n = len(trades)
    if not n or not len(sess):
        return np.zeros(n_twins)
    lo, hi = ev.win
    di = sess[rng.integers(0, len(sess), n * n_twins)]
    m = rng.integers(lo, hi + 1, n * n_twins)
    d = np.tile(trades.d.to_numpy().astype(np.int8), n_twins)
    cid = np.full(n * n_twins, -1, dtype=np.int64)
    if rule["fam"].startswith("O"):
        st = u.stores["WEEK"]
        cid = np.array([S.open_atm(st, int(a), 0 if s > 0 else 1) for a, s in zip(di, d)], dtype=np.int64)
    tw = np.repeat(np.arange(n_twins), n)
    tev = S.Events(di.astype(np.int64), m.astype(np.int64), d, cid, ev.win, ev.days)
    ttr = R.price(u, tev, rule["instr"], exit_tuple(rule["exit_spec"]), lots=R.lots_of(u), sequential=False)
    return np.bincount(tw[ttr.ev.to_numpy()], weights=ttr.net.to_numpy(), minlength=n_twins)


# ------------------------------------------------------------------------------------------------ fixed rules
def fixed_rules() -> list[dict]:
    out = []
    for c in json.loads(K.CANDIDATES.read_text())["candidates"]:
        out.append(dict(c, id=c["id"], group="candidate"))
    for c in json.loads((K.RESULTS / "candidates_v3.json").read_text())["candidates"]:
        if c["fam"] == "O1_prem_orb" and c["und"] == "NIFTY":
            out.append(dict(c, id="O1-NIFTY (v3 rank 10, never sent)", group="candidate"))
    fams = json.loads((K.RESULTS / "design_v3.json").read_text())["by_signal_family"]
    for fam, v in fams.items():
        b = v.get("best_100")
        if not b:
            continue
        out.append(dict(id=f"best {fam} {b['und']}", fam=fam, label=K.FAMILIES[fam]["label"], und=b["und"],
                        kind=R.kind_of(b["instr"]), instr=b["instr"], exit_spec=b["exit"], filt="all",
                        params=json.loads(b["params"]), signal_source=K.SIGNAL_SOURCE[K.FAMILIES[fam]["label"]],
                        group="family best"))
    return out


def run_fixed(und: str) -> list[dict]:
    u = load_nov(und)
    f = S.day_features(u)
    lots = R.lots_of(u)
    rng = np.random.default_rng(K.SEED)
    res, cache = [], {}
    for rule in [r for r in fixed_rules() if r["und"] == und]:
        ev = events(u, rule["fam"], rule["params"], f, cache, rule["instr"])
        tr = R.price(u, ev, rule["instr"], exit_tuple(rule["exit_spec"]), lots=lots)
        tr = tr[S.filter_mask(rule["filt"], f, tr.di.to_numpy())].reset_index(drop=True)
        rec = dict(id=rule["id"], group=rule["group"], fam=rule["fam"], und=und, kind=rule["kind"],
                   instr=rule["instr"], exit_spec=rule["exit_spec"], filt=rule["filt"], params=rule["params"],
                   signal_source=rule["signal_source"])
        for name, sp in SPANS.items():
            x = tr[span_mask(tr.day.to_numpy(), sp)]
            st = stats(x)
            st.pop("months", None)
            if name in ("design", "final"):
                tw = twin_net(u, rule, ev, x, sp, rng)
                st["twin_mean_npt"] = round(float(tw.mean() / max(len(x), 1)), 1)
                st["p"] = round((1 + int((tw >= st["net"]).sum())) / (len(tw) + 1), 4) if len(x) else 1.0
            rec[name] = st
        res.append(rec)
        say(und, rule["id"], {k: (rec[k].get("trades"), rec[k].get("npt"), rec[k].get("p")) for k in SPANS})
    return res


# ------------------------------------------------------------------------------------------------ re-selection
def tstat(x: np.ndarray) -> float:
    n = len(x)
    if n < 2 or np.std(x, ddof=1) == 0:
        return 0.0
    return float(np.mean(x) / (np.std(x, ddof=1) / np.sqrt(n)))


def run_reselect(und: str) -> list[dict]:
    """For each (family, kind) of this index among the fixed rules: price every variant of that group on Nov-2024+
    sessions, pick on N1 -> trade N2, pick on N1+N2 -> trade the final test (R47's pick rule: 40+ training trades,
    positive training net, highest t)."""
    u = load_nov(und)
    f = S.day_features(u)
    lots = R.lots_of(u)
    rng = np.random.default_rng(K.SEED + 1)
    groups = sorted({(r["fam"], r["kind"]) for r in fixed_rules() if r["und"] == und})
    out, cache = [], {}
    for fam, kind in groups:
        variants = []
        for p in K.FAMILIES[fam]["params"]:
            for instr in R.instruments_for(fam, und):
                if R.kind_of(instr) != kind:
                    continue
                ev = events(u, fam, p, f, cache, instr)
                if not len(ev):
                    continue
                legs = R.legs_for(u, ev, instr)
                for xi, ex in enumerate(R.exits_for(fam, p)):
                    tr = R.price(u, ev, instr, ex, legs=legs, lots=lots)
                    if not len(tr):
                        continue
                    for filt in K.FILTERS:
                        x = tr[S.filter_mask(filt, f, tr.di.to_numpy())]
                        variants.append(dict(params=p, instr=instr, exit=list(ex) if not isinstance(ex, list) else ex,
                                             filt=filt, day=x.day.to_numpy(), net=x.net.to_numpy(), ev=ev))
        rec = dict(fam=fam, und=und, kind=kind, variants=len(variants))
        for pick_span, test_span, tag in ((N1, N2, "N1->N2"), (DESIGN, FINAL, "design->final")):
            best, bt = None, -np.inf
            for v in variants:
                x = v["net"][span_mask(v["day"], pick_span)]
                if len(x) >= K.WF_MIN_TRAIN and x.sum() > 0 and tstat(x) > bt:
                    best, bt = v, tstat(x)
            if best is None:
                rec[tag] = dict(picked=False)
                continue
            xt = best["net"][span_mask(best["day"], test_span)]
            r = dict(picked=True, params=best["params"], instr=best["instr"], exit=best["exit"], filt=best["filt"],
                     train_n=int(span_mask(best["day"], pick_span).sum()), train_t=round(bt, 2),
                     trades=int(len(xt)), net=round(float(xt.sum()), 0),
                     npt=round(float(xt.mean()), 1) if len(xt) else None, t=round(tstat(xt), 2),
                     win=round(float((xt > 0).mean() * 100), 1) if len(xt) else None)
            if tag == "design->final" and len(xt):
                rule = dict(fam=fam, instr=best["instr"], exit_spec=best["exit"])
                ev = best["ev"]
                tr = R.price(u, ev, best["instr"], exit_tuple(best["exit"]), lots=lots)
                tr = tr[S.filter_mask(best["filt"], f, tr.di.to_numpy())]
                x = tr[span_mask(tr.day.to_numpy(), FINAL)]
                tw = twin_net(u, rule, ev, x, FINAL, rng)
                r["p"] = round((1 + int((tw >= x.net.sum()).sum())) / (len(tw) + 1), 4)
            rec[tag] = r
        out.append(rec)
        say(und, "reselect", fam, kind, len(variants), {k: rec[k].get("npt") for k in ("N1->N2", "design->final")})
    return out


def gates(rec: dict) -> dict:
    h, d = rec["final"], rec["design"]
    g = dict(design_net_positive=d.get("net", 0) > 0, G1_net_positive=h.get("net", 0) > 0,
             G2_trades_40=h.get("trades", 0) >= K.GATE_MIN_TRADES, G3_p=h.get("p", 1) < K.GATE_P,
             G4_q=(rec.get("q_final") or 1) <= K.BH_Q,
             G5_top5=h.get("trades", 0) > 0 and h["net"] - h["top5"] > 0,
             G6_best_month=h.get("trades", 0) > 0 and h["net"] - h["best_month"] > 0)
    return {k: bool(v) for k, v in g.items()}


def main(argv):
    if "--report" in argv:
        return report()
    with ProcessPoolExecutor(max_workers=2) as ex:
        fixed = [r for part in ex.map(run_fixed, K.UNDS) for r in part]
    with ProcessPoolExecutor(max_workers=2) as ex:
        resel = [r for part in ex.map(run_reselect, K.UNDS) for r in part]
    for part in ("design", "final"):
        q = bh([r[part].get("p", 1.0) for r in fixed])
        for r, qq in zip(fixed, q):
            r[f"q_{part}"] = round(float(qq), 4)
        cand = [r for r in fixed if r["group"] == "candidate"]
        q = bh([r[part].get("p", 1.0) for r in cand])
        for r, qq in zip(cand, q):
            r[f"q_{part}_candidates_only"] = round(float(qq), 4)
    for r in fixed:
        r["gates"] = gates(r)
        r["passes"] = all(r["gates"].values())
    picked = [r for r in resel if r["design->final"].get("picked") and "p" in r["design->final"]]
    q = bh([r["design->final"]["p"] for r in picked])
    for r, qq in zip(picked, q):
        r["design->final"]["q"] = round(float(qq), 4)
    res = dict(amendment="4: Nov 2024+ view (Boss, 15 Oct)", spans={k: [str(a), str(b)] for k, (a, b) in SPANS.items()},
               warmup_from=str(WARMUP), fixed=fixed, reselect=resel, any_pass=any(r["passes"] for r in fixed))
    OUT_JSON.write_text(json.dumps(res, indent=1, default=str))
    say("wrote", OUT_JSON)
    return 0


def report() -> int:
    res = json.loads(OUT_JSON.read_text())
    full = {c["id"]: c for c in json.loads(K.HOLDOUT_JSON.with_name("holdout_amended.json").read_text())["candidates"]}
    cands = {c["id"]: c for c in json.loads(K.CANDIDATES.read_text())["candidates"]}
    L = ["# R47 amendment 4: the Nov 2024+ view (generated by r47nov.py --report; statistics only)", "",
         "Design 20 Nov 2024 - 31 Mar 2026 (blocks N1 to 30 Jun 2025, N2 from 1 Jul 2025); final test 1 Apr - 13 Oct "
         "2026. No trade before 20 Nov 2024. Rs, net of dated charges, R47 sizing (Rs 20,000 premium budget). p = 500 "
         "random-entry twins inside the span; q = BH across all rules below (q cand = across the 11 candidates only). "
         "The final test overlaps R47's already-read holdout, and the fixed rules were chosen on data through Oct 2025.",
         "", "## Fixed rules", "",
         "| rule | signal | N1 trades, Rs/trade | N2 trades, Rs/trade | design trades, Rs/trade, p, q | final trades, "
         "Rs/trade, win, p, q, q cand | passes | R47 full span: design Rs/trade (n); holdout Rs/trade (n), p, q |",
         "|---|---|---|---|---|---|---|---|"]
    for r in res["fixed"]:
        n1, n2, d, h = r["N1"], r["N2"], r["design"], r["final"]
        c, fh = cands.get(r["id"]), full.get(r["id"])
        if r["group"] == "candidate" and c and fh:
            prior = (f"{c['npt']:+,.0f} ({c['n']}); {fh['holdout'].get('npt', 0):+,.0f} ({fh['holdout'].get('trades', 0)}),"
                     f" {fh['p']}, {fh['q']}")
        elif r["id"].startswith("O1-NIFTY"):
            prior = "+1,777 (140); never sent to the holdout"
        else:
            prior = "best variant of the family (design only)"
        L.append(f"| {r['id']}: {r['fam']} {r['und']} {r['instr']} {r['filt']} | {r['signal_source'].split(' -')[0]} | "
                 f"{n1.get('trades', 0)}, {n1.get('npt', 0):+,.0f} | {n2.get('trades', 0)}, {n2.get('npt', 0):+,.0f} | "
                 f"{d.get('trades', 0)}, {d.get('npt', 0):+,.0f}, {d.get('p')}, {r.get('q_design')} | "
                 f"{h.get('trades', 0)}, {h.get('npt', 0):+,.0f}, {h.get('win', '-')}%, {h.get('p')}, {r.get('q_final')}, "
                 f"{r.get('q_final_candidates_only', '-')} | {'PASS' if r['passes'] else 'no'} | {prior} |")
    L += ["", "## Re-selection inside Nov 2024+ only (walk-forward)", "",
          "| group | variants | N1 pick -> N2: trades, Rs/trade, t | design pick -> final: rule | final trades, Rs/trade, "
          "t, p, q |", "|---|---|---|---|---|"]
    for r in res["reselect"]:
        a, b = r["N1->N2"], r["design->final"]
        sa = f"{a['trades']}, {a['npt'] if a['npt'] is not None else '-'}, {a['t']}" if a.get("picked") else "no pick"
        rule = f"{b['instr']} {b['exit']} {b['filt']} {b['params']}" if b.get("picked") else "no pick"
        sb = (f"{b['trades']}, {b['npt'] if b['npt'] is not None else '-'}, {b['t']}, {b.get('p', '-')}, "
              f"{b.get('q', '-')}") if b.get("picked") else "-"
        L.append(f"| {r['fam']} {r['und']} {r['kind']} | {r['variants']} | {sa} | {rule} | {sb} |")
    L += ["", f"Any fixed rule passing every gate: {res['any_pass']}", ""]
    (K.RESULTS / "nov24_report.md").write_text("\n".join(L) + "\n")
    print("\n".join(L))
    return 0


if __name__ == "__main__":
    import r47nov  # noqa: E402  (pickles name the module, never __main__)
    sys.exit(r47nov.main(sys.argv[1:]))
