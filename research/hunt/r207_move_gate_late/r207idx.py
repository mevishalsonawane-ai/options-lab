"""R207 / R208 index runner (PREREG.md): index-only, on R206's build (index minutes 8 Oct 2021 - 5 Oct 2026, the arms' own
signals).

    python3 -I research/hunt/r207_move_gate_late/r207idx.py part1    # R207 Part 1 on R206's two eras -> results/index_part1.json
    python3 -I research/hunt/r207_move_gate_late/r207idx.py fresh    # R208 3a: 1 Nov 2021 - 31 Mar 2022, read once -> results/index_fresh.json

Pure parts in r207lib.py / r206lib.py. Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r207cfg as K  # noqa: E402

sys.path.insert(0, str(K.R206))
import numpy as np  # noqa: E402

import r206cfg as K6  # noqa: E402
import r206study as S6i  # noqa: E402  (sets R206's environment and imports the stack)
import r206lib as L6  # noqa: E402
import r207lib as L  # noqa: E402


def with_x(rows: list) -> list:
    """Adds x (the expected move), ratio and the range the index really made in the next 30 minutes (a share of p0)."""
    load = S6i.mods()["r51data"].load
    order, cache = {}, {}
    out = []
    for s in rows:
        u = s["und"]
        if u not in order:
            order[u] = sorted(load(u))
        k = (u, s["day"], s["er"])
        if k not in cache:
            days = load(u)
            i = order[u].index(s["day"])
            idx = days[s["day"]].idx
            f = L.features(idx, [days[d].idx for d in order[u][max(0, i - K.PREV_N):i]], s["er"])
            p0 = L.last_close(idx, s["er"])
            fr = L.fwd_range(idx, s["er"])
            f["real30"] = fr / p0 if fr == fr and p0 == p0 and p0 > 0 else float("nan")
            cache[k] = f
        out.append(dict(s, **cache[k]))
    return out


def part1() -> dict:
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), note="descriptive; R206's two eras, already read")}
    for era, (lo, hi) in K6.ERAS.items():
        rows = with_x(S6i.label(S6i.in_span(S6i.all_signals(), lo, hi)))
        o = {}
        for src in K6.SOURCES:
            rs = [s for s in rows if s["src"] == src and s["x"] == s["x"] and s["real30"] == s["real30"] and s["m15"] == s["m15"]]
            x = np.array([s["x"] for s in rs])
            cuts = np.quantile(x, [1 / 3, 2 / 3]) if len(x) else [0, 0]
            thirds = {}
            for name, m in (("low", x < cuts[0]), ("mid", (x >= cuts[0]) & (x < cuts[1])), ("high", x >= cuts[1])):
                g = [s for s, k in zip(rs, m) if k]
                thirds[name] = dict(signals=len(g), expected_bp=round(float(np.mean([s["x"] for s in g])) * 1e4, 1),
                                    real_range_bp=round(float(np.mean([s["real30"] for s in g])) * 1e4, 1),
                                    abs_move15_bp=round(float(np.mean([abs(s["m15"]) for s in g])) * 1e4, 1),
                                    move15_bp=round(float(np.mean([s["m15"] for s in g])) * 1e4, 2),
                                    right15=round(float(np.mean([s["m15"] > 0 for s in g])), 4)) if g else None
            o[src] = dict(signals=len(rs), rank_corr=round(L.spearman(x, [s["real30"] for s in rs]), 3), thirds=thirds)
        out[era] = o
        print(era, {s: (o[s]["signals"], o[s]["rank_corr"]) for s in o}, flush=True)
    (K.RESULTS / "index_part1.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def fresh() -> dict:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if K.FRESH_FLAG.exists() and (K.RESULTS / "index_fresh.json").exists():
        raise SystemExit("R208 fresh index era already read once: " + K.FRESH_FLAG.read_text())
    if not K.FRESH_FLAG.exists():
        K.FRESH_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    lo, hi = K.FRESH
    rows = S6i.label(S6i.in_span(S6i.all_signals(), lo, hi))
    load = S6i.mods()["r51data"].load
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), era=f"{lo} .. {hi}", boot=K.BOOT, seed=K.SEED_LATE,
                         signals={s: sum(1 for r in rows if r["src"] == s) for s in K6.SOURCES}), "tests": {}}
    for hi_, h in enumerate(K.INDEX_TESTS):
        src = K.INDEX_SRC[h]
        rs = [s for s in rows if s["src"] == src and s["m15"] == s["m15"]]
        days = sorted({s["day"] for s in rs})
        did = {d: i for i, d in enumerate(days)}

        def judge(g, seed):
            return L.boot_rate_above([s["m15"] > 0 for s in g], [did[s["day"]] for s in g], K.BOOT,
                                     np.random.default_rng([K.SEED_LATE, hi_, seed]))
        late = [s for s in rs if L.is_late(s["er"])]
        early = [s for s in rs if not L.is_late(s["er"])]
        to1525 = [L6.move(load(s["und"])[s["day"]].idx, s["er"], s["side"], 371 - s["er"]) for s in late]
        to1525 = [v for v in to1525 if v == v]
        out["tests"][h] = dict(source=src, late=judge(late, 0), early=judge(early, 1),
                               late_move15_bp=round(float(np.mean([s["m15"] for s in late])) * 1e4, 2) if late else None,
                               late_right_to_1525=round(float(np.mean([v > 0 for v in to1525])), 4) if to1525 else None,
                               late_move_to_1525_bp=round(float(np.mean(to1525)) * 1e4, 2) if to1525 else None)
        print(h, {k: (v if not isinstance(v, dict) else {a: (round(b, 4) if isinstance(b, float) else b) for a, b in v.items()})
                  for k, v in out["tests"][h].items()}, flush=True)
    (K.RESULTS / "index_fresh.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    {"part1": part1, "fresh": fresh}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
