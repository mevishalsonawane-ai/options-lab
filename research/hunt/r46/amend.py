"""R46 amendments run (amend_cfg.py says what and why). Research only; nothing in the app trades crypto.

    python -m research.hunt.r46.amend            -> results/amend.json (statistics only, no market data)
    python -m research.hunt.r46.amend_report     -> results/amend_report.md

R46's own results are not touched. Pools and model outputs are cached in R46_OUT (never git).
"""
from __future__ import annotations

import json
import sys
import time

import numpy as np
import pandas as pd

from . import amend_cfg as A
from . import book as B
from . import costs as C
from . import data as D
from . import dip as DP
from . import ens as EN
from . import nn
from . import panel as PN
from . import r46cfg as K
from . import run as RN

T0 = time.time()
TWIN_SCHEMES = (A.MAIN_SCHEME, "cs_nogst")          # twin p under Delta (the gate) and under the cheapest CoinSwitch
PERP_TWIN_SCHEMES = (A.MAIN_SCHEME, "cs_maker_nogst")


def say(*a):
    print(f"[{time.time() - T0:7.0f}s]", *a, flush=True)


def days(span) -> int:
    return int((span[1] - span[0]) / pd.Timedelta(days=1))


def cut(T: pd.DataFrame, span) -> pd.DataFrame:
    if not len(T):
        return T
    return T[(T.index >= span[0]) & (T.index < span[1])]


def read_variant(T: pd.DataFrame, pool: pd.DataFrame, span, scale, recost, schemes, twin_schemes) -> dict:
    """Trades T and pool (both Delta-costed, indexed by entry) inside span: summaries per cost scheme, twin p."""
    Tw = cut(T, span)
    Pw = cut(pool, span)
    out = {"days": days(span)}
    for s in schemes:
        Ts = recost(Tw, s)
        r = B.summary(Ts, days(span), scale)
        if s in twin_schemes and len(Ts):
            p, tw = B.twin_p(Ts, recost(Pw, s))
            r["twin_p"] = round(float(p), 4)
            r["twin_mean"] = round(float(np.nanmean(tw)) * scale, 2) if len(tw) else None
        out[s] = r
    return out


def read_all(T, pool, view, scale, recost, schemes, twin_schemes, claim=None) -> dict:
    v = A.VIEWS[view]
    res = {"design": read_variant(T, pool, v["design"], scale, recost, schemes, twin_schemes),
           "final": read_variant(T, pool, v["final"], scale, recost, schemes, twin_schemes)}
    if claim is not None:
        res["claim_window"] = read_variant(T, pool, claim, scale, recost, schemes, ())
    return res


# --- amendment 1: R46 v1 under CoinSwitch costs, both views -----------------------------------------------------------
def v1(spot, perp, S, F, y, P, PP) -> dict:
    out = {}
    q = (S.call | S.put)
    ent = pd.DatetimeIndex(S.entry)
    months = list(pd.date_range(K.DESIGN[0], A.FINAL[1] - pd.Timedelta(days=1), freq="MS"))
    for view in A.VIEWS:
        Fv = F.copy()
        if view == "nov24":
            Fv.loc[ent < A.NOV_START] = np.nan             # Boss: nothing before 20 Nov 2024, training included
        prob, log = nn.walk_forward(Fv, y, ent, months, q)
        lo = A.VIEWS[view]["design"][0]
        inside = np.asarray(ent >= lo) & np.asarray(ent < A.FINAL[1])
        res = {"nn_log": log}
        for side, col in (("C", "call"), ("P", "put")):
            base = S[col].to_numpy() & inside
            nn_ok = (prob > K.NN_THRESHOLD) if side == "C" else (prob < 1 - K.NN_THRESHOLD)
            sig = {"A": ent[base], "B": ent[base & prob.notna().to_numpy() & nn_ok.to_numpy()]}
            for sv, es in sig.items():
                for ex in K.OPT_EXITS:
                    pool = P[(P.side == side) & (P["mode"] == "real") & (P.exit == ex)].set_index("entry")
                    T = B.select(es, pool)
                    key = f"{sv}-{'call' if side == 'C' else 'put'}-{ex}"
                    res[key] = read_all(T, pool, view, K.CLAIM_CONTRACTS, C.recost_opt, A.OPT_SCHEMES, TWIN_SCHEMES)
        for side, pool in PP.items():
            es = ent[S["call" if side == "L" else "put"].to_numpy() & inside]
            T = B.select(es, pool)
            res[f"A-perp{'long' if side == 'L' else 'short'}"] = read_all(T, pool, view, 1.0, C.recost_perp,
                                                                          A.PERP_SCHEMES, PERP_TWIN_SCHEMES)
        out[view] = res
        say("v1", view, "done")
    return out


# --- amendment 2: v2 ensemble -------------------------------------------------------------------------------------------
def switch_stats(meta: pd.DataFrame, span) -> dict:
    m = meta[(meta.ts >= span[0]) & (meta.ts < span[1]) & np.isfinite(meta.y_roll)]
    if not len(m):
        return {}
    sw = m.switch.astype(bool)
    both = m[np.isfinite(m.y_held)]
    return dict(rows=int(len(m)), switch_share=round(float(sw.mean()), 4),
                up_given_switch=round(float(m.y_roll[sw].mean()), 4) if sw.any() else None,
                up_given_no_switch=round(float(m.y_roll[~sw].mean()), 4) if (~sw).any() else None,
                up_labels_with_switch=round(float(sw[m.y_roll == 1].mean()), 4),
                mixed_feature_share=round(float(m.mixed_feat.astype(bool).mean()), 4),
                held_up_rate=round(float(both.y_held.mean()), 4) if len(both) else None,
                roll_up_rate=round(float(m.y_roll.mean()), 4),
                labels_agree=round(float((both.y_roll == both.y_held).mean()), 4) if len(both) else None)


def v2(S, P, pan) -> dict:
    out = {"switch": {}, "auc": {}, "wf_log": {}, "trades": {}}
    call_entries = set(pd.DatetimeIndex(S.entry[S.call]))
    meta = pan["held"][2]
    for view in A.VIEWS:
        v = A.VIEWS[view]
        out["switch"][view] = {p: switch_stats(meta, v[p]) for p in ("design", "final")}
    for src in ("roll", "held"):
        X, y, meta = pan[src]
        ts = pd.DatetimeIndex(meta.ts)
        for view in A.VIEWS:
            v = A.VIEWS[view]
            path = K.OUT / f"amend_ens_{src}_{view}.parquet"
            if path.exists():
                prob = pd.read_parquet(path).prob.to_numpy(float)
                log = json.loads((K.OUT / f"amend_ens_{src}_{view}.json").read_text())
            else:
                prob, log = EN.walk_forward(X, y, ts, view, log_cb=lambda r: say("ens", src, view, r))
                pd.DataFrame({"prob": prob}).to_parquet(path, index=False)
                (K.OUT / f"amend_ens_{src}_{view}.json").write_text(json.dumps(log))
            out["wf_log"][f"{src}-{view}"] = log
            rnd_path = K.OUT / f"amend_ens_random_{src}_{view}.json"
            if rnd_path.exists():
                rnd = json.loads(rnd_path.read_text())
            else:
                rnd = EN.random_split_auc(X, y, ts, v["design"])
                rnd_path.write_text(json.dumps(rnd))
            a = {"random_split_design": rnd}
            for part in ("design", "final"):
                w = np.asarray((ts >= v[part][0]) & (ts < v[part][1])) & np.isfinite(prob)
                a[f"time_split_{part}"] = dict(n=int((w & np.isfinite(y)).sum()), auc=round(EN.auc(y[w], prob[w]), 4),
                                               share_above=round(float((prob[w] > A.ENS_THRESHOLD).mean()), 4)
                                               if w.any() else None)
                if src == "roll":       # a model fitted on rolling labels, judged on the held contract's own move
                    a[f"time_split_{part}_vs_held_label"] = round(EN.auc(meta.y_held.to_numpy(float)[w], prob[w]), 4)
            out["auc"][f"{src}-{view}"] = a
            # trades: the R46 call signal AND the ensemble > 0.65, held-contract pricing (R46 pool), L1 and L2
            okp = np.isfinite(prob) & (prob > A.ENS_THRESHOLD)
            es = pd.DatetimeIndex([e for e in pd.DatetimeIndex(meta.entry[okp]) if e in call_entries])
            for ex in A.ENS_EXITS:
                pool = P[(P.side == "C") & (P["mode"] == "real") & (P.exit == ex)].set_index("entry")
                T = B.select(es, pool)
                out["trades"][f"v2-{src}-{ex}-{view}"] = read_all(
                    T, pool, view, K.CLAIM_CONTRACTS, C.recost_opt, A.OPT_SCHEMES, TWIN_SCHEMES,
                    claim=A.CLAIM_V2 if view == A.PRIMARY else None)
            say("v2", src, view, "done")
    return out


# --- amendment 3: dip buy -----------------------------------------------------------------------------------------------
def v3(pan_raw: dict) -> dict:
    out = {"reproduction": {}, "held": {}}
    a, b = A.CLAIM_DIP
    lit = DP.literal(pan_raw["roll_C"], pan_raw["roll_P"], a, b)
    out["reproduction"]["literal_rows"] = lit["rows"]
    for side in ("C", "P"):
        out["reproduction"][f"literal_{side}"] = DP.summarise_their(lit[side])
        out["reproduction"][f"rolling15_{side}"] = DP.summarise_their(DP.rolling15(pan_raw[f"roll_{side}"], a, b))
    for side in ("C", "P"):
        pool_all = DP.size(pan_raw[f"dip_{side}"])
        for ex in A.DIP_EXITS:
            pool = pool_all[pool_all.exit == ex].set_index("ts")
            sig = pd.DatetimeIndex(pool.index[(pool.bar_chg <= A.DIP_DROP).to_numpy()])
            T = DP.select_one_at_a_time(sig, pool)
            for view in A.VIEWS:
                lo = A.VIEWS[view]["design"][0]
                Tv = T[T.index >= lo]
                out["held"][f"dip-{'call' if side == 'C' else 'put'}-{ex}-{view}"] = read_all(
                    Tv, pool, view, 1.0, C.recost_opt, A.OPT_SCHEMES, TWIN_SCHEMES,
                    claim=A.CLAIM_DIP if view == A.PRIMARY else None)
        # how many held-contract dips exist at all, and how many rolling-series dips were not a held-contract dip
        p1 = pool_all[pool_all.exit == A.DIP_EXITS[0]]
        w = (p1.ts >= a) & (p1.ts < b)
        out["held"][f"count_{side}"] = dict(bar_closes=int(w.sum()), dips=int((w & (p1.bar_chg <= A.DIP_DROP)).sum()),
                                            dips_filled=int((w & (p1.bar_chg <= A.DIP_DROP) & p1.filled).sum()))
        say("dip", side, "done")
    return out


# --- BH and gates -------------------------------------------------------------------------------------------------------
def family(res: dict, view: str) -> dict:
    """The 22-variant family: R46's 14 (amendment 1 reading), v2 x 4, dip x 4 -> {name: view result}."""
    fam = {}
    for k, r in res["v1"][view].items():
        if k != "nn_log":
            fam[f"v1 {k}"] = r
    for k, r in res["v2"]["trades"].items():
        if k.endswith(f"-{view}"):
            fam[k[:-len(view) - 1]] = r
    for k, r in res["v3"]["held"].items():
        if k.endswith(f"-{view}"):
            fam[k[:-len(view) - 1]] = r
    return fam


def schemes_of(name: str) -> tuple[str, str]:
    """(gate scheme, cheapest CoinSwitch scheme) of a family member."""
    return PERP_TWIN_SCHEMES if "perp" in name else TWIN_SCHEMES


def gates(res: dict) -> dict:
    """BH over the 22 per view, part and cost reading (Delta = the gate; the cheapest CoinSwitch beside it), and the
    R46 gate per variant: 40+ trades and profit after tax in design AND final, design p < 0.05 and q <= 0.10."""
    out = {}
    for view in A.VIEWS:
        fam = family(res, view)
        names = list(fam)
        for part in ("design", "final"):
            for j in (0, 1):
                ps = [fam[n][part].get(schemes_of(n)[j], {}).get("twin_p") for n in names]
                q = B.bh([np.nan if p is None else p for p in ps])
                for n, qq in zip(names, q):
                    r = fam[n][part].get(schemes_of(n)[j])
                    if r is not None:
                        r["q"] = None if not np.isfinite(qq) else round(float(qq), 4)
        g = {}
        for n in names:
            for key in schemes_of(n):
                d, f = fam[n]["design"].get(key, {}), fam[n]["final"].get(key, {})
                why = []
                if d.get("trades", 0) < K.MIN_TRADES:
                    why.append(f"design trades {d.get('trades', 0)}")
                if (d.get("after_tax") or 0) <= 0:
                    why.append("design loses after tax")
                if not (d.get("twin_p") is not None and d["twin_p"] < K.P_MAX):
                    why.append(f"design p {d.get('twin_p')}")
                if not (d.get("q") is not None and d["q"] <= K.Q_MAX):
                    why.append(f"design q {d.get('q')}")
                if f.get("trades", 0) < K.MIN_TRADES:
                    why.append(f"final trades {f.get('trades', 0)}")
                if (f.get("after_tax") or 0) <= 0:
                    why.append("final loses after tax")
                g[f"{n} | {key}"] = "PASS" if not why else "; ".join(why)
        out[view] = g
    return out


def main(argv=None) -> int:
    K.OUT.mkdir(parents=True, exist_ok=True)
    spot, perp, b5, S, F, y = RN.underlying(A.FINAL[1])
    say("underlying loaded", len(spot))
    P = pd.concat([RN.option_pools(spot, "design"), RN.option_pools(spot, "holdout")], ignore_index=True)
    say("option pools", len(P))
    PP = {}
    for part in ("design", "holdout"):
        for k, v in RN.perp_pools(perp, part).items():
            PP.setdefault(k, []).append(v)
    PP = {k: pd.concat(v).sort_index() for k, v in PP.items()}
    say("perp pools")
    res = {"views": {k: {kk: [str(x) for x in vv] if isinstance(vv, tuple) else str(vv) for kk, vv in v.items()}
                     for k, v in A.VIEWS.items()}, "primary": A.PRIMARY}
    v1_path = K.OUT / "amend_v1.json"                  # cached: the MLP walk-forward takes ~15 minutes
    if v1_path.exists():
        res["v1"] = json.loads(v1_path.read_text())
    else:
        res["v1"] = v1(spot, perp, S, F, y, P, PP)
        v1_path.write_text(json.dumps(res["v1"], default=str))
    raw = PN.load_or_build(spot, pd.Timestamp("2023-12-29"), A.FINAL[1])
    say("panel", {k: len(v) for k, v in raw.items()})
    pan = EN.panels(raw["held_C"], raw["roll_C"], spot.close)
    res["v2"] = v2(S, P, pan)
    res["v3"] = v3(raw)
    res["gates"] = gates(res)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "amend.json").write_text(json.dumps(res, indent=1, default=str))
    say("wrote results/amend.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
