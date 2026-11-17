"""R185 runner (PREREG.md): absorption and exhaustion (R184's rules, BVC flow from the index price and the near-ATM option
volume) on two years of 1-minute data, trading the ATM option.

    python -I research/hunt/r185_absorption_year/r185.py --und NIFTY --stage select
    python -I research/hunt/r185_absorption_year/r185.py --und NIFTY --stage final     # needs results/FINAL_READ_NIFTY

select: the selection window only (20 Nov 2024 - 19 Oct 2025, halves H1 / H2): all 64 variants, twins per half, the
        choice per setup -> results/selection_<IDX>.json. It refuses any final-year day.
final : refuses unless results/FINAL_READ_<IDX> and results/selection_<IDX>.json exist and results/final_<IDX>.json does
        not; the chosen variant per setup on 20 Oct 2025 - 19 Oct 2026, read once, plus the recent weeks (descriptive)
        -> results/final_<IDX>.json and results/decision.json (all indices read so far, BH m = 4,852).

Per-trade rows go to ~/.cache/iraalgo/r185/ (outside git); the repo gets aggregates. Plain-English notes, 16 Nov 2026.
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
sys.path.insert(0, str(HERE))
import r185lib as L  # noqa: E402
import r185load as LD  # noqa: E402

RESULTS = HERE / "results"
CACHE = LD.CACHE
SEED = 20261116
INDICES = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
SETUPS = ("ABS", "EXH")
M_PRIOR, N_VARIANTS, N_TESTS = 4512, 64 * 5, 2 * 2 * 5
M_TOTAL = M_PRIOR + N_VARIANTS + N_TESTS          # 4,852
SEL = (date(2024, 11, 20), date(2025, 10, 19))
H1_END = date(2025, 4, 30)
FIN = (date(2025, 10, 20), date(2026, 10, 19))
RECENT = (date(2026, 10, 20), date(2026, 11, 13))
GATE_P = {"early": (date(2025, 10, 20), date(2026, 3, 31)), "final": (date(2026, 4, 1), date(2026, 10, 19)),
          "six": (date(2026, 4, 20), date(2026, 10, 19)), "year": FIN}


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def in_(d: str, a: date, b: date) -> bool:
    return a <= date.fromisoformat(d) <= b


def periods(days: dict) -> dict:
    ds = sorted(days)
    return dict(H1=[d for d in ds if in_(d, SEL[0], H1_END)], H2=[d for d in ds if in_(d, H1_END, SEL[1]) and d > H1_END.isoformat()],
                final=[d for d in ds if in_(d, *FIN)], recent=[d for d in ds if in_(d, *RECENT)])


def run_variant(days: dict, dlist: list, v: dict, sig_cache: dict | None = None):
    trades, n_sig, unpriced = [], 0, {}
    skey = tuple(sorted((k, x) for k, x in v.items() if k != "T"))
    for d in dlist:
        ck = (d, skey)
        if sig_cache is not None and ck in sig_cache:
            sigs = sig_cache[ck]
        else:
            sigs = L.signals(days[d].bars, v)
            if sig_cache is not None:
                sig_cache[ck] = sigs
        n_sig += len(sigs)
        tr, up = L.take_day(sigs, days[d], v["T"])
        for t in tr:
            t.update(setup=v["setup"], vid=L.vid(v))
        trades += tr
        for k, n in up.items():
            unpriced[k] = unpriced.get(k, 0) + n
    return trades, n_sig, unpriced


def day_sums(trades: list, dlist: list) -> list:
    s = {d: 0.0 for d in dlist}
    for t in trades:
        s[t["day"]] += t["net"]
    return [s[d] for d in dlist]


def dump(name: str, rows: list):
    CACHE.mkdir(parents=True, exist_ok=True)
    with open(CACHE / name, "w") as f:
        for r in rows:
            f.write(json.dumps(r, default=_j) + "\n")


def _j(o):
    return o.item() if hasattr(o, "item") else str(o)


def _short(s: dict) -> dict:
    return {k: s[k] for k in ("n", "win", "net", "net2", "mean", "mean2", "avg_win", "avg_loss", "worst_day", "max_dd")
            } | dict(worst_month=s["worst_month"])


# ================================================================================================ selection
def stage_select(und: str, out: Path = RESULTS) -> dict:
    days, meta = LD.load(und)
    P = periods(days)
    for d in P["H1"] + P["H2"]:
        if not in_(d, *SEL):
            raise SystemExit("selection refused: a day outside the selection window")
    sel_days = {d: days[d] for d in P["H1"] + P["H2"]}     # the final year is never handed to the selection
    rng = np.random.default_rng(SEED + INDICES.index(und))
    sig_cache: dict = {}
    res = dict(und=und, prereg_sha256=sha(HERE / "PREREG.md"), m_total=M_TOTAL, signal_source=SIGNAL_SOURCE,
               halves={h: [P[h][0], P[h][-1], len(P[h])] for h in ("H1", "H2")},
               data=dict(days_total=meta["days"], expiry_days_in_selection=sum(d in P["H1"] + P["H2"] for d in meta["expiry_days"]),
                         steps=meta["steps"],
                         dropped=[k for k, v in meta["quality"].items() if "dropped" in v]),
               cells={})
    for setup in SETUPS:
        rows, all_tr = [], []
        for i, v in enumerate(L.GRID[setup]):
            row = dict(i=i, vid=L.vid(v), params=v)
            n_all, net_all, sig_all, up_all = 0, 0.0, 0, {}
            for h in ("H1", "H2"):
                tr, ns, up = run_variant(sel_days, P[h], v, sig_cache)
                pools = L.twin_pools(tr, sel_days, P[h], v["T"], rng)
                s = L.summary(tr)
                tm = L.twin_mean(pools)
                row[h] = dict(n=s["n"], net=s["net"], mean=s["mean"], win=s["win"], net2=s["net2"],
                              twin_mean=None if tm is None else round(tm, 1), signals=ns, unpriced=up)
                n_all += s["n"]
                net_all += s["net"]
                sig_all += ns
                for k, x in up.items():
                    up_all[k] = up_all.get(k, 0) + x
                for t in tr:
                    t["half"] = h
                all_tr += tr
            row.update(n=n_all, net=round(net_all, 0), signals=sig_all, unpriced=up_all, eligible=L.eligible(row))
            rows.append(row)
            print(und, row["vid"], n_all, round(net_all), "eligible" if row["eligible"] else "", flush=True)
        ch = L.select(rows)
        v = L.GRID[setup][ch["i"]]
        tr = [t for t in all_tr if t["vid"] == L.vid(v)]
        S = L.summary(tr)
        pools = L.twin_pools(tr, sel_days, P["H1"] + P["H2"], v["T"], np.random.default_rng(SEED + 100))
        tt = L.twin_test([t["net"] for t in tr], pools, np.random.default_rng(SEED + 200))
        res["cells"][f"{setup} {und}"] = dict(
            variants=rows, n_eligible=sum(r["eligible"] for r in rows),
            choice=dict(i=ch["i"], vid=ch["vid"], params=v, passed_selection=ch["passed_selection"],
                        summary=S, twins_descriptive=tt, H1=ch["H1"], H2=ch["H2"]))
        dump(f"select_{und}_{setup}.jsonl", all_tr)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"selection_{und}.json").write_text(json.dumps(res, indent=1, default=_j))
    return res


# ================================================================================================ final
def stage_final(und: str, out: Path = RESULTS) -> dict:
    flag, selp, fp = out / f"FINAL_READ_{und}", out / f"selection_{und}.json", out / f"final_{und}.json"
    if not flag.exists() or not selp.exists():
        raise SystemExit(f"final refused: results/FINAL_READ_{und} and results/selection_{und}.json must exist first")
    if fp.exists():
        raise SystemExit(f"final refused: results/final_{und}.json exists (the final is read once)")
    sel = json.loads(selp.read_text())
    days, meta = LD.load(und)
    P = periods(days)
    rng = np.random.default_rng(SEED + 1000 + INDICES.index(und))
    cells, all_tr = {}, []
    for setup in SETUPS:
        key = f"{setup} {und}"
        ch = sel["cells"][key]["choice"]
        v = ch["params"]
        tr, ns, up = run_variant(days, P["final"], v)
        pools = L.twin_pools(tr, days, P["final"], v["T"], rng)
        t1 = L.twin_test([t["net"] for t in tr], pools, rng)
        p2 = L.zero_test(day_sums(tr, P["final"]), rng, n_trades=len(tr))
        S = L.summary(tr)
        per = {}
        for name, (a, b) in GATE_P.items():
            idx = [k for k, t in enumerate(tr) if in_(t["day"], a, b)]
            s = L.summary([tr[k] for k in idx])
            tm = L.twin_mean([pools[k] for k in idx])
            per[name] = dict(s, twin_mean=None if tm is None else round(tm, 1))
        rtr, rns, rup = run_variant(days, P["recent"], v)
        for t in tr + rtr:
            t["und"] = und
        all_tr += tr + rtr
        cells[key] = dict(vid=ch["vid"], params=v, passed_selection=ch["passed_selection"], signals=ns, unpriced=up,
                          year=S, periods={k: _short(x) | dict(twin_mean=x["twin_mean"], n=x["n"]) for k, x in per.items()},
                          per_full=per, twins=t1, p_twin=round(t1["p"], 4), p_zero=round(p2, 4),
                          power=L.power(S.get("sd"), S["n"], M_TOTAL),
                          selection=dict(summary=_short(ch["summary"]), H1=ch["H1"], H2=ch["H2"]),
                          recent=dict(days=len(P["recent"]), signals=rns, unpriced=rup, summary=L.summary(rtr)))
    res = dict(und=und, read_at=datetime.now(timezone.utc).isoformat(), final_read_flag=flag.read_text().strip(),
               prereg_sha256=sha(HERE / "PREREG.md"), selection_sha256=sha(selp),
               code_sha256={f: sha(HERE / f) for f in ("r185.py", "r185lib.py", "r185load.py")},
               final_days=[P["final"][0], P["final"][-1], len(P["final"])],
               recent_days=[P["recent"][0], P["recent"][-1], len(P["recent"])] if P["recent"] else None,
               m_total=M_TOTAL, signal_source=SIGNAL_SOURCE, cells=cells)
    dump(f"final_{und}.jsonl", all_tr)
    fp.write_text(json.dumps(res, indent=1, default=_j))
    decide(out)
    return json.loads(fp.read_text())


def decide(out: Path = RESULTS) -> dict:
    """BH over every R185 test read so far (m = 4,852; the others taken as larger); verdicts; results/decision.json.
    Also writes the q values and verdicts back into each final_<IDX>.json."""
    finals = {u: json.loads((out / f"final_{u}.json").read_text()) for u in INDICES if (out / f"final_{u}.json").exists()}
    tests = []
    for u, F in finals.items():
        for key, c in F["cells"].items():
            tests += [dict(id=f"{key} T1 twins", p=c["p_twin"]), dict(id=f"{key} T2 zero", p=c["p_zero"])]
    q_all, q_in = L.bh_with_prior([t["p"] for t in tests], M_TOTAL)
    for t, a, b in zip(tests, q_all, q_in):
        t["q_all"], t["q_within"] = round(float(a), 4), round(float(b), 4)
    qm = {t["id"]: t for t in tests}
    dec = {}
    for u, F in finals.items():
        for key, c in F["cells"].items():
            c["q_twin"], c["q_zero"] = qm[f"{key} T1 twins"]["q_all"], qm[f"{key} T2 zero"]["q_all"]
            g = L.gates(c["passed_selection"], c["per_full"], c["q_twin"], c["q_zero"])
            c["gates"] = g
            c["verdict"] = L.verdict(c["passed_selection"], g, c["year"], c["twins"].get("mean"), c["p_twin"])
            dec[key] = dict(vid=c["vid"], passed_selection=c["passed_selection"], verdict=c["verdict"],
                            n=c["year"]["n"], win=c["year"]["win"], net=c["year"]["net"], net2=c["year"]["net2"],
                            mean=c["year"]["mean"], twin_mean=c["twins"].get("mean"), p_twin=c["p_twin"],
                            q_twin=c["q_twin"], p_zero=c["p_zero"], q_zero=c["q_zero"],
                            worst_month=c["year"]["worst_month"], max_dd=c["year"]["max_dd"], gate_fails=g["fails"])
        (out / f"final_{u}.json").write_text(json.dumps(F, indent=1, default=_j))
    dec["_m"] = dict(prior=M_PRIOR, variants=N_VARIANTS, tests=N_TESTS, total=M_TOTAL, tests_read=len(tests),
                     indices_read=list(finals))
    dec["_tests"] = tests
    (out / "decision.json").write_text(json.dumps(dec, indent=1, default=_j))
    return dec


SIGNAL_SOURCE = ("the index's own 1-minute price (the underlying); each minute's buy / sell split estimated by BVC from "
                 "the index price change, sized by the summed volume of the ATM-2..ATM+2 calls and puts "
                 "(option-volume context, OVC); no rule reads an option's own price chart")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--und", required=True, choices=INDICES)
    ap.add_argument("--stage", required=True, choices=("select", "final", "decide"))
    a = ap.parse_args(argv)
    if a.stage == "select":
        r = stage_select(a.und)
        for k, c in r["cells"].items():
            ch = c["choice"]
            print(k, ch["vid"], "passed" if ch["passed_selection"] else "FALLBACK", c["n_eligible"], "eligible",
                  json.dumps({x: ch["summary"][x] for x in ("n", "win", "net", "mean")}), "twin p",
                  ch["twins_descriptive"]["p"])
    elif a.stage == "final":
        r = stage_final(a.und)
        for k, c in r["cells"].items():
            print(k, c["vid"], c["verdict"], json.dumps({x: c["year"][x] for x in ("n", "win", "net", "mean", "net2")}),
                  "twins", c["twins"].get("mean"), "p", c["p_twin"], "q", c["q_twin"], "p0", c["p_zero"])
    else:
        print(json.dumps(decide(), indent=1, default=_j)[:3000])


if __name__ == "__main__":
    main()
