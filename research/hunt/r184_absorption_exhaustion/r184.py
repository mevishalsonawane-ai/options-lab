"""R184 runner (PREREG.md): absorption and exhaustion on the index future, trading the recorded ATM option.

    python -I research/hunt/r184_absorption_exhaustion/r184.py --rec2 <1-second export dir> --stage design \
        --cache ~/.cache/iraalgo/r184
    python -I research/hunt/r184_absorption_exhaustion/r184.py --rec2 <dir> --diag <diagnostics dir> --stage holdout \
        --cache ~/.cache/iraalgo/r184

design : the first 13 days only (15 Oct - 3 Nov 2026): all 128 variants, the selection -> results/selection.json
holdout: refuses unless results/FINAL_READ and results/selection.json exist; the chosen variant per setup and index on the
         last 7 days (4 - 13 Nov), read once -> results/holdout.json, results/decision.json

Per-trade rows go to the cache folder (outside git); the repo gets aggregates. Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path[:0] = [str(HERE), str(ROOT)]
import r184lib as L  # noqa: E402
import r184load as LD  # noqa: E402

RESULTS = HERE / "results"
SEED = 20261116
N_DESIGN = 13
M_PRIOR = 4376
N_VARIANTS = 128
N_TESTS = 8
M_TOTAL = M_PRIOR + N_VARIANTS + N_TESTS
SETUPS = ("ABS", "EXH")
EXPIRY = {"NIFTY": {"2026-10-19", "2026-10-27", "2026-11-03", "2026-11-09"}, "BANKNIFTY": {"2026-10-27"}}
EXIT_NORMAL = 6 * 3600            # 15:15 from 09:15
EXIT_EXPIRY = 5 * 3600 + 50 * 60  # 15:05


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def split(dir2: str):
    days = LD.days_of(dir2)
    if len(days) != 20 or days[N_DESIGN - 1] != "2026-11-03" or days[N_DESIGN] != "2026-11-04":
        raise SystemExit(f"unexpected day list {days}")
    return days[:N_DESIGN], days[N_DESIGN:]


def end_of(D: dict, und: str) -> int:
    return D["t0"] + (EXIT_EXPIRY if D["day"] in EXPIRY[und] else EXIT_NORMAL)


def run_variant(loaded: dict, days: list, und: str, v: dict, sig_cache: dict | None = None):
    """All trades of one variant on one index over the given days; plus signal and unpriced counts."""
    trades, n_sig, unpriced = [], 0, {}
    skey = {k: x for k, x in v.items() if k != "T"}
    for day in days:
        D = loaded[day]
        U = D[und]
        ck = (day, und, json.dumps(skey, sort_keys=True))
        if sig_cache is not None and ck in sig_cache:
            sigs = sig_cache[ck]
        else:
            sigs = L.signals(U["bars"], v)
            if sig_cache is not None:
                sig_cache[ck] = sigs
        n_sig += len(sigs)
        tr, up = L.take_day(sigs, U, D["t0"], end_of(D, und), und, date.fromisoformat(day), v["T"])
        for t in tr:
            t.update(day=day, und=und, setup=v["setup"], vid=L.vid(v))
        trades += tr
        for k, n in up.items():
            unpriced[k] = unpriced.get(k, 0) + n
    return trades, n_sig, unpriced


def twins_for(loaded: dict, days: list, trades: list, T: int) -> list:
    """For each rule trade: its twin's net on every OTHER day of the same period (None = not priced, dropped)."""
    out = []
    for t in trades:
        nets = []
        for day in days:
            if day == t["day"]:
                continue
            D = loaded[day]
            res = L.twin_trade(D[t["und"]], D["t0"], end_of(D, t["und"]), t["und"], date.fromisoformat(day), t["r"],
                               t["side"], t["dist"], T)
            if res is not None:
                nets.append(res["net"])
        out.append(nets)
    return out


def day_sums(trades: list, days: list) -> list:
    s = {d: 0.0 for d in days}
    for t in trades:
        s[t["day"]] += t["net"]
    return [s[d] for d in days]


def dump_rows(cache: str, name: str, rows: list):
    if not cache:
        return
    os.makedirs(cache, exist_ok=True)
    with open(os.path.join(cache, name), "w") as f:
        for r in rows:
            f.write(json.dumps(r, default=lambda o: o.item() if hasattr(o, "item") else str(o)) + "\n")


def stage_design(dir2: str, cache: str, out_dir: Path) -> dict:
    design, _ = split(dir2)
    loaded = {d: LD.load_day(dir2, d, cache) for d in design}
    rng = np.random.default_rng(SEED)
    sig_cache: dict = {}
    res = dict(prereg_sha256=sha(HERE / "PREREG.md"), design_days=design, m_total=M_TOTAL,
               quality={d: loaded[d]["quality"] for d in design}, cells={})
    all_rows = []
    for setup in SETUPS:
        for und in LD.INDICES:
            rows = []
            for i, v in enumerate(L.GRID[setup]):
                tr, ns, up = run_variant(loaded, design, und, v, sig_cache)
                all_rows += tr
                s = L.summary(tr)
                rows.append(dict(i=i, vid=L.vid(v), params=v, signals=ns, unpriced=up, **{k: s[k] for k in (
                    "n", "win", "net", "net2", "net_ba", "mean", "mean2", "avg_win", "avg_loss", "worst_day")}))
            ch = L.select([dict(i=r["i"], vid=r["vid"], n=r["n"], net=r["net"]) for r in rows])
            v = L.GRID[setup][ch["i"]]
            tr, ns, up = run_variant(loaded, design, und, v, sig_cache)
            tw = twins_for(loaded, design, tr, v["T"])
            tt = L.twin_test([t["net"] for t in tr], tw, rng)
            res["cells"][f"{setup} {und}"] = dict(
                variants=rows, choice=dict(i=ch["i"], vid=ch["vid"], params=v, signals=ns, unpriced=up,
                                           summary=L.summary(tr), twins_descriptive=tt,
                                           any_with_12=any(r["n"] >= 12 for r in rows)))
    dump_rows(cache, "design_trades.jsonl", all_rows)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "selection.json").write_text(json.dumps(res, indent=1, default=_j))
    return res


def _j(o):
    return o.item() if hasattr(o, "item") else str(o)


def verdict(S: dict, design_mean, p1: float, q1: float, q2: float, twin_mean) -> str:
    n = S["n"]
    if n < 10:
        return "too few trades"
    m2 = S["mean2"]
    dm = design_mean is not None and design_mean > 0
    if (n >= 40 and m2 > 0 and q1 <= 0.10 and q2 <= 0.10 and dm and S["worst_day"] is not None
            and S["worst_day"] > -13000):
        return "passes the gates"
    if m2 > 0 and twin_mean is not None and S["mean"] > twin_mean and p1 <= 0.20 and dm:
        return "worth a forward test"
    return "no evidence"


def overlap(diag: str, trades: list, window_s: int = 900) -> dict:
    """PREREG s7 (descriptive): R184 trades with an arm's paper entry on the same index within 15 minutes."""
    r182 = HERE.parent / "r182_delta_study"
    sys.path.insert(0, str(r182))
    import r182lib  # noqa: E402
    import r182load  # noqa: E402
    orders = r182load.load_orders(diag)
    arm_tr, stats = r182lib.rebuild_trades(orders)
    arms = [dict(arm=a["arm"], und=a["und"], right=a["right"], day=a["day"],
                 s=r182lib.epoch_of(a["day"], a["entry_at"])) for a in arm_tr if a["und"] in LD.INDICES]
    arm_days = sorted({a["day"] for a in arm_tr})
    out = {}
    for cell in sorted({(t["setup"], t["und"]) for t in trades}):
        tc = [t for t in trades if (t["setup"], t["und"]) == cell and arm_days and arm_days[0] <= t["day"] <= arm_days[-1]]
        same, opp, by_arm = 0, 0, {}
        for t in tc:
            right = "CE" if t["side"] == "L" else "PE"
            near = [a for a in arms if a["day"] == t["day"] and a["und"] == t["und"] and abs(a["s"] - t["entry_s"]) <= window_s]
            if any(a["right"] == right for a in near):
                same += 1
                for a in near:
                    if a["right"] == right:
                        by_arm[a["arm"]] = by_arm.get(a["arm"], 0) + 1
            if any(a["right"] != right for a in near):
                opp += 1
        out[f"{cell[0]} {cell[1]}"] = dict(trades_in_arm_days=len(tc), same_direction_within_15m=same,
                                           opposite_within_15m=opp, same_by_arm=by_arm)
    out["_arms"] = dict(arm_trades_nifty_banknifty=len(arms), arm_days=[arm_days[0], arm_days[-1]] if arm_days else None,
                        rebuild=stats)
    return out


def stage_holdout(dir2: str, diag: str | None, cache: str, out_dir: Path) -> dict:
    flag, selp = out_dir / "FINAL_READ", out_dir / "selection.json"
    if not flag.exists() or not selp.exists():
        raise SystemExit("holdout refused: results/FINAL_READ and results/selection.json must exist first")
    sel = json.loads(selp.read_text())
    design, hold = split(dir2)
    loaded = {d: LD.load_day(dir2, d, cache) for d in design + hold}
    rng = np.random.default_rng(SEED + 1)
    cells, tests, all_hold, chosen_all = {}, [], [], []
    for setup in SETUPS:
        for und in LD.INDICES:
            key = f"{setup} {und}"
            v = sel["cells"][key]["choice"]["params"]
            dsum = sel["cells"][key]["choice"]["summary"]
            tr, ns, up = run_variant(loaded, hold, und, v)
            all_hold += tr
            dtr, _, _ = run_variant(loaded, design, und, v)
            chosen_all += dtr + tr
            tw = twins_for(loaded, hold, tr, v["T"])
            t1 = L.twin_test([t["net"] for t in tr], tw, rng)
            p2 = L.zero_test(day_sums(tr, hold), rng, n_trades=len(tr))
            S = L.summary(tr)
            cells[key] = dict(vid=L.vid(v), params=v, signals=ns, unpriced=up, holdout=S, design=dsum, twins=t1,
                              p_twin=round(t1["p"], 4), p_zero=round(p2, 4), power=L.power(S.get("sd"), S["n"]),
                              day_sums={d: round(x, 0) for d, x in zip(hold, day_sums(tr, hold))})
            tests += [dict(id=f"{key} T1 twins", p=t1["p"]), dict(id=f"{key} T2 zero", p=p2)]
    q_all, q_in = L.bh_with_prior([t["p"] for t in tests], M_TOTAL)
    for t, a, b in zip(tests, q_all, q_in):
        t["p"], t["q_all"], t["q_within"] = round(float(t["p"]), 4), round(float(a), 4), round(float(b), 4)
    qmap = {t["id"]: t for t in tests}
    for key, c in cells.items():
        c["q_twin"] = qmap[f"{key} T1 twins"]["q_all"]
        c["q_zero"] = qmap[f"{key} T2 zero"]["q_all"]
        c["verdict"] = verdict(c["holdout"], c["design"]["mean"], c["p_twin"], c["q_twin"], c["q_zero"], c["twins"]["mean"])
    pooled = {s: L.summary([t for t in all_hold if t["setup"] == s]) for s in SETUPS}
    res = dict(read_at=datetime.now(timezone.utc).isoformat(), final_read_flag=flag.read_text().strip(),
               prereg_sha256=sha(HERE / "PREREG.md"), selection_sha256=sha(selp),
               code_sha256={f: sha(HERE / f) for f in ("r184.py", "r184lib.py", "r184load.py")},
               holdout_days=hold, m_total=M_TOTAL, cells=cells, tests=tests, pooled=pooled,
               quality={d: loaded[d]["quality"] for d in hold})
    if diag:
        res["overlap"] = overlap(diag, chosen_all)
    dump_rows(cache, "holdout_trades.jsonl", all_hold)
    dump_rows(cache, "chosen_all_trades.jsonl", chosen_all)
    (out_dir / "holdout.json").write_text(json.dumps(res, indent=1, default=_j))
    dec = {k: dict(vid=c["vid"], verdict=c["verdict"], n=c["holdout"]["n"], p_twin=c["p_twin"], q_twin=c["q_twin"],
                   p_zero=c["p_zero"], q_zero=c["q_zero"]) for k, c in cells.items()}
    dec["_m"] = dict(prior=M_PRIOR, selection=N_VARIANTS, tests=N_TESTS, total=M_TOTAL)
    (out_dir / "decision.json").write_text(json.dumps(dec, indent=1, default=_j))
    return res


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec2", required=True)
    ap.add_argument("--diag", default=None)
    ap.add_argument("--cache", default=os.path.expanduser("~/.cache/iraalgo/r184"))
    ap.add_argument("--stage", choices=("design", "holdout"), required=True)
    ap.add_argument("--out", default=str(RESULTS))
    a = ap.parse_args(argv)
    out = Path(a.out)
    if a.stage == "design":
        r = stage_design(a.rec2, a.cache, out)
        for k, c in r["cells"].items():
            ch = c["choice"]
            print(k, ch["vid"], json.dumps({x: ch["summary"][x] for x in ("n", "win", "net", "mean", "net2")}),
                  "twin p", ch["twins_descriptive"]["p"])
    else:
        r = stage_holdout(a.rec2, a.diag, a.cache, out)
        for k, c in r["cells"].items():
            print(k, c["vid"], c["verdict"], json.dumps({x: c["holdout"][x] for x in ("n", "win", "net", "mean", "net2")}),
                  c["p_twin"], c["q_twin"], c["p_zero"])


if __name__ == "__main__":
    main()
