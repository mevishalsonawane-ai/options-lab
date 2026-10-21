"""R53 crypto book runner (PREREG.md, 15 Oct 2026).

    python -m research.hunt.r53.crypto.run design    # design (20 Nov 2024 - 31 Mar 2026): every version, twins, WF
    python -m research.hunt.r53.crypto.run final     # ONCE, after the design json: the original + the design pick
    python -m research.hunt.r53.crypto.run repro     # signal reproduction checks vs the original studies (design only)

Outputs: results/crypto_design.json, results/crypto_final.json (and the lock results/final_read_crypto.json),
small per-trade CSVs in results/trades/ (larger ones in the cache). No BH and no gates here (applied globally).
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import date

import numpy as np
import pandas as pd

from .. import common as RC
from . import baselines, data, rules as R

BOOKS = tuple(RC.CRYPTO_BOOKS)
POOL = {("option", "design"): 800, ("option", "final"): 400, ("perp", "design"): 4000, ("perp", "final"): 2000}
DATA_END = date(2026, 10, 12)          # the lake's last day (PREREG: the final test runs to 13 Oct; data ends 12 Oct)
TRADES_DIR = RC.RESULTS / "trades"
CSV_LIMIT = 200_000


def period_days(part: str) -> int:
    a, b = (RC.DESIGN if part == "design" else (RC.FINAL[0], min(RC.FINAL[1], DATA_END)))
    return (b - a).days + 1


def add_tax(B: pd.DataFrame) -> pd.DataFrame:
    if B.empty:
        return B
    B = B.copy()
    for b in BOOKS:
        B[f"net_{b}_at"] = RC.after_tax(B[f"net_{b}"].to_numpy(float))
    return B


def trades_list(B: pd.DataFrame, key: str) -> list[dict]:
    if B.empty:
        return []
    return [{"entry_ts": t, "net": float(x)} for t, x in zip(B.entry_ts, B[key])]


def pool_key(rule: R.CC) -> str:
    """Rules with the same entry window share their random entries (CC4/CC5/CC6/CC7: 00:00-09:00 IST decisions)."""
    return rule.signal if rule.kind in ("rc2opt", "rc1brk") else "f1c-window"


def rng_for(rule: R.CC, part: str, coin: str) -> np.random.Generator:
    tag = f"{pool_key(rule)}|{part}|{coin}"
    return np.random.default_rng([RC.SEED, sum(ord(ch) * (i + 1) for i, ch in enumerate(tag))])


def twins(rule: R.CC, variants, mkts: dict, part: str, rule_trades: dict) -> dict:
    """PREREG section 3: 500 random-entry twins per version; each twin = as many random entries per coin as the rule
    had trades there (drawn with replacement from a pool of independent random-entry trades), net = after-tax Delta.
    Returns {variant: dict(p, twin_mean, twin_q05, twin_q95, pool sizes)}."""
    n_pool = 10**9 if rule.kind == "rc1brk" else POOL[(rule.venue, part)]
    pools = {}
    for c, mk in mkts.items():
        ents = R.twin_entries(rule, mk, part, n_pool, rng_for(rule, part, c))
        pools[c] = {v: add_tax(B) for v, B in R.twin_pool(rule, variants, mk, ents).items()}
    out = {}
    for v in variants:
        B = rule_trades[v]
        net = float(B.net_delta_at.sum()) if len(B) else 0.0
        rng = np.random.default_rng([RC.SEED, int(rule.rid[2:]), len(v), 1 if part == "design" else 2])
        sims = np.zeros(RC.N_TWINS)
        ok = True
        sizes = {}
        for c in mkts:
            n_c = int((B.coin == c).sum()) if len(B) else 0
            P = pools[c][v]
            x = P.net_delta_at.to_numpy(float) if len(P) else np.array([])
            sizes[c] = dict(rule_trades=n_c, pool=int(len(x)))
            if n_c == 0:
                continue
            if len(x) == 0:
                ok = False
                continue
            sims += x[rng.integers(0, len(x), size=(RC.N_TWINS, n_c))].sum(axis=1)
        out[v] = dict(p=RC.twin_p(net, sims) if ok and len(B) else None, twin_mean=float(sims.mean()),
                      twin_q05=float(np.quantile(sims, 0.05)), twin_q95=float(np.quantile(sims, 0.95)), sizes=sizes)
    return out


def quarterly(B: pd.DataFrame, key: str) -> dict:
    if B.empty:
        return {}
    q = pd.DatetimeIndex(B.entry_ts).tz_convert("UTC").tz_localize(None).to_period("Q").astype(str)
    g = B.groupby(q)
    return {k: dict(trades=int(len(x)), net=float(x[key].sum())) for k, x in g}


def describe(B: pd.DataFrame, part: str) -> dict:
    days = period_days(part)
    out = {"trades": int(len(B)), "by_coin": {c: int((B.coin == c).sum()) for c in data.COINS} if len(B) else {},
           "period_days": days}
    for b in BOOKS:
        for tag, key in (("pre_tax", f"net_{b}"), ("after_tax", f"net_{b}_at")):
            s = RC.summary(trades_list(B, key))
            s["net_per_period_day"] = s["net"] / days
            out[f"{b}_{tag}"] = s
    out["tds_locked"] = RC.tds_locked(B.sale_inr) if len(B) else 0.0
    out["exit_reasons"] = B.reason.value_counts().to_dict() if len(B) else {}
    out["quarters_delta_after_tax"] = quarterly(B, "net_delta_at")
    out["quarters_delta_pre_tax"] = quarterly(B, "net_delta")
    return out


def save_trades(rule: R.CC, v: str, part: str, B: pd.DataFrame) -> str | None:
    if B.empty:
        return None
    cols = [c for c in ("coin", "T", "entry_ts", "exit_ts", "side", "expiry", "K", "spot", "mid", "buy", "level",
                        "sell", "entry", "exit", "q", "lots", "fx", "reason", "gross", "funding", "sale_inr")
            if c in B] + [f"net_{b}" for b in BOOKS] + [f"net_{b}_at" for b in BOOKS]
    name = f"crypto_{part}_{rule.rid}_{v}.csv"
    text = B[cols].to_csv(index=False, float_format="%.6g")
    if len(text.encode()) <= CSV_LIMIT:
        TRADES_DIR.mkdir(parents=True, exist_ok=True)
        p = TRADES_DIR / name
    else:
        p = RC.cache_dir() / "trades" / name
        p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)
    return str(p)


def evaluate(rule: R.CC, variants, mkts: dict, part: str) -> dict:
    t0 = time.time()
    res, trades = {}, {}
    for v in variants:
        B, skips = R.run_rule(rule, v, mkts, part)
        B = add_tax(B)
        trades[v] = B
        res[v] = describe(B, part)
        res[v]["skips"] = skips
        res[v]["trades_file"] = save_trades(rule, v, part, B)
    tw = twins(rule, variants, mkts, part, trades)
    for v in variants:
        res[v]["twins"] = tw[v]
        res[v]["p"] = tw[v]["p"]
    print(f"{rule.rid} {part} done in {time.time() - t0:.0f}s: " +
          ", ".join(f"{v} n={res[v]['trades']} delta_at={res[v]['delta_after_tax']['net']:+,.0f} p={res[v]['p']}"
                    for v in variants), flush=True)
    return res, trades


def markets():
    return {c: R.Market(c) for c in data.COINS}


def design(only=None) -> dict:
    mkts = markets()
    out = {"written": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()), "part": "design",
           "window": [str(RC.DESIGN[0]), str(RC.DESIGN[1])], "books": list(BOOKS), "rules": {}}
    for rid, rule in R.RULES.items():
        if only and rid not in only:
            continue
        res, trades = evaluate(rule, rule.variants, mkts, "design")
        wf = RC.walk_forward({v: trades_list(trades[v], "net_delta_at") for v in rule.variants}, rule.original)
        out["rules"][rid] = dict(name=rule.name, kind=rule.kind, original=rule.original, variants=res,
                                 walk_forward_delta_after_tax=wf, design_pick=wf["design_pick"])
    out["baselines"] = baselines.run("design")
    if not only:
        RC.save("crypto_design.json", out)
    return out


def final() -> dict:
    p = RC.RESULTS / "crypto_design.json"
    if not p.exists():
        sys.exit("Run the design first: results/crypto_design.json is missing.")
    if RC.final_lock_path("crypto").exists():
        sys.exit("The crypto final test was already read (results/final_read_crypto.json). A re-run is refused.")
    dz = json.loads(p.read_text())
    plan = {}
    for rid, rule in R.RULES.items():
        pick = dz["rules"][rid]["design_pick"]
        plan[rid] = [rule.original] + ([pick] if pick and pick != rule.original else [])
    ideas = [f"crypto-{rid}:{v}" for rid, vs in plan.items() for v in vs]
    mkts = markets()
    # data only (no profit or loss is computed): fetch the final-period option contracts before the one read
    for rid, rule in R.RULES.items():
        if rule.venue != "option":
            continue
        for c, mk in mkts.items():
            ents = R.signals(rule, mk)
            ents = ents[R.in_part(rule, ents["T"], "final")]
            pool = R.twin_entries(rule, mk, "final", POOL[("option", "final")], rng_for(rule, "final", c))
            n = R.prefetch(rule, mk, pd.concat([ents, pool], ignore_index=True))
            print(f"prefetch {rid} {c}: {n} contracts", flush=True)
    RC.open_final("crypto", ideas)
    out = {"written": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()), "part": "final",
           "window": [str(RC.FINAL[0]), str(DATA_END)], "ideas": ideas, "books": list(BOOKS), "rules": {}}
    for rid, rule in R.RULES.items():
        res, _ = evaluate(rule, plan[rid], mkts, "final")
        out["rules"][rid] = dict(name=rule.name, kind=rule.kind, original=rule.original,
                                 design_pick=dz["rules"][rid]["design_pick"], variants=res)
    out["baselines"] = baselines.run("final")
    RC.save("crypto_final.json", out)
    return out


def main(argv=None) -> int:
    a = argv or sys.argv[1:]
    cmd = a[0] if a else ""
    if cmd == "design":
        design(a[1:] or None)
    elif cmd == "final":
        final()
    elif cmd == "repro":
        from . import repro
        repro.main()
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
