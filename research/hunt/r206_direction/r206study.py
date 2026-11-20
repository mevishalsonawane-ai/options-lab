"""R206 study (PREREG.md): how often the index goes the way the arms' signals say (R72, R51, Liquidity 15+5), and whether a
call that other signals agree with is right more often. Index-only.

    python3 -I research/hunt/r206_direction/r206study.py build     # R51 day stores from the index files only
    python3 -I research/hunt/r206_direction/r206study.py recent    # 21 Nov 2024 - 5 Oct 2026 -> results/recent.json
    python3 -I research/hunt/r206_direction/r206study.py old       # 1 Apr 2022 - 19 Nov 2024, read once -> results/old.json
    python3 -I research/hunt/r206_direction/r206study.py decide    # the gate -> results/decision.json

The signals come from the arms' own code, unchanged (r101arms.r72_signals, r112study.r51_st_signals, fam_liq._day_signals).
Pure parts in r206lib.py. Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r206cfg as K  # noqa: E402

K.set_env()
sys.path.insert(0, str(K.HUNT / "r186_fixed_profit"))
import numpy as np  # noqa: E402

import r206lib as L  # noqa: E402

_M: dict = {}


def mods() -> dict:
    """The R51 / R101 / R112 stack on this study's index-only stores."""
    if not _M:
        import r186study as Z6  # noqa: F401  (R179 -> R177 -> R176 -> R152 -> R112 -> R106 -> R101 -> R51)
        M = Z6.R7.mods()
        k = M["r51data"].K
        k.WARM_START, k.START, k.END = K.STORE
        A = M["A"]
        _M.update(r51data=M["r51data"], A=A, AM=A.mods(), S12=M["S12"])
    return _M


def build():
    M = mods()
    import r51data  # noqa: F401  (pickle the Day class under its module name)
    for u in K.UNDS:
        try:
            print(M["r51data"].build(u), flush=True)
        except ValueError as e:                               # no option days by design: the store is written first
            print(u, "index-only store written:", e, flush=True)


# ================================================================================================ signals
def liq_signals(und: str, tf: int) -> dict:
    """{day: [signal]}: fam_liq.signals' own loop (10 calendar days of index minutes folded into tf-minute bars) without
    its option-data conditions, so every session is treated alike (PREREG 2)."""
    M = mods()
    fam = M["AM"]["fam_liq"]
    days = M["r51data"].load(und)
    step, istop = M["r51data"].K.STEP[und], fam.IDX_STOP[und]
    hist, out = [], {}
    for d in sorted(days):
        a = days[d].idx
        ok = np.isfinite(a[3])
        today1 = [(d, fam.OPEN_M + r, float(a[0, r]), float(a[1, r]), float(a[2, r]), float(a[3, r])) for r in np.flatnonzero(ok)]
        hist = [hh for hh in hist if hh[0] >= d - timedelta(days=10)]
        if hist:
            bars = fam.H4.fold([b for hh in hist for b in hh[1]] + today1, tf)
            if len(bars) >= 2 * fam.H4.L + 2:
                out[d] = fam._day_signals(d, bars, tf, istop, step, 1.0)
        hist.append((d, today1))
    return out


def all_signals() -> list:
    """[dict(src, und, day, er, side[, count])], entry minute in 09:20 .. 15:10, one row per (source, index, day, minute,
    side) (two Liquidity books firing the same call count once)."""
    M = mods()
    rows, seen = [], set()

    def add(src, und, d, er, side, **kw):
        er = int(er)
        k = (src, und, d, er, side)
        if K.FIRST_ROW <= er <= K.LAST_ROW and k not in seen:
            seen.add(k)
            rows.append(dict(src=src, und=und, day=d, er=er, side=side, **kw))

    for und, per in M["A"].r72_signals().items():
        for d, v in per.items():
            for er, side, extra in v:
                add("R72", und, d, er, side, count=int(extra["f_count"]))
    for und, per in M["S12"].r51_st_signals().items():
        for d, v in per.items():
            for er, side in v:
                add("R51", und, d, er, side)
    for und, books in K.LIQ_BOOKS.items():
        for tf in books:
            for d, v in liq_signals(und, tf).items():
                for s in v:
                    add("LIQ", und, d, s["er"], s["side"])
    return rows


def label(rows: list) -> list:
    """Adds m15, m30 (the move the signal's way), day (with the day), mkt (with the market) and the agreement flags."""
    M = mods()
    load = M["r51data"].load
    mk = load(K.MARKET)
    mdays = sorted(mk)
    c15 = np.concatenate([L.bars15_close(mk[d].idx) for d in mdays])
    e15 = L.ema(c15, K.EMA_N)
    start = {d: i * (K.NROWS // 15) for i, d in enumerate(mdays)}
    by_src = defaultdict(set)
    for s in rows:
        by_src[s["src"]].add(L.key(s))
    out = []
    for s in rows:
        idx = load(s["und"])[s["day"]].idx
        k = L.key(s)
        t = dict(s, m15=L.move(idx, s["er"], s["side"], K.H1), m30=L.move(idx, s["er"], s["side"], K.H2),
                 day_=L.with_day(idx, s["er"], s["side"]),
                 mkt=L.with_market(c15, e15, start[s["day"]], s["er"], s["side"]) if s["day"] in start else None,
                 r72=k in by_src["R72"], r51=k in by_src["R51"], donch=(k in by_src["R72"]) or (k in by_src["R51"]),
                 five=(s.get("count") == 5) if s["src"] == "R72" else None)
        out.append(t)
    return out


# ================================================================================================ measuring
def in_span(rows: list, lo, hi) -> list:
    return [s for s in rows if lo <= s["day"] <= hi]


def group_of(s: dict, what: str):
    return s["day_"] if what == "day" else s[what]


def rate_row(rows: list, field: str, rng) -> dict:
    ok = [s for s in rows if s[field] == s[field]]
    days = sorted({s["day"] for s in ok})
    did = {d: i for i, d in enumerate(days)}
    r = L.boot_rate(np.array([s[field] > 0 for s in ok], bool), np.array([did[s["day"]] for s in ok], int), len(days), K.BOOT, rng)
    r["days"] = len(days)
    return r


def part1(rows: list, era: str, rng) -> dict:
    out = {}
    lo, hi = K.ERAS[era]
    for src in K.SOURCES:
        rs = in_span([s for s in rows if s["src"] == src], lo, hi)
        m = np.array([s["m15"] for s in rs if s["m15"] == s["m15"]], float)
        o = dict(signals=len(rs), right15=rate_row(rs, "m15", rng), right30=rate_row(rs, "m30", rng),
                 avg_move15_bp=round(float(m.mean()) * 1e4, 2) if len(m) else None,
                 years={y: dict(right15=rate_row(in_span(rs, a, b), "m15", rng), right30=rate_row(in_span(rs, a, b), "m30", rng))
                        for y, (a, b) in K.YEARS[era].items()},
                 by_index={u: rate_row([s for s in rs if s["und"] == u], "m15", rng) for u in K.UNDS
                           if any(s["und"] == u for s in rs)},
                 by_hour={str(h): rate_row([s for s in rs if (555 + s["er"]) // 60 == h], "m15", rng) for h in range(9, 16)
                          if any((555 + s["er"]) // 60 == h for s in rs)},
                 by_side={sd: rate_row([s for s in rs if s["side"] == sd], "m15", rng) for sd in ("C", "P")})
        out[src] = o
    return out


def one_diff(rs: list, what: str, field: str, rng, boot: bool = True) -> dict:
    """d = share right in A - share right in B over `rs` (signals with a known label and a known move)."""
    ok = [s for s in rs if s[field] == s[field] and group_of(s, what) is not None]
    a = np.array([bool(group_of(s, what)) for s in ok], bool)
    right = np.array([s[field] > 0 for s in ok], bool)
    days = sorted({s["day"] for s in ok})
    did = {d: i for i, d in enumerate(days)}
    t = L.day_table(right, a, np.array([did[s["day"]] for s in ok], int), len(days)) if ok else np.zeros((0, 4))
    r = L.boot_diff(t, K.BOOT, rng) if boot else dict(d=None if not len(t) or L.diff(t) != L.diff(t) else L.diff(t))
    mv = np.array([s["m15"] for s in ok], float)
    r.update(n_a=int(a.sum()), n_b=int((~a).sum()), days=len(days),
             rate_a=float(right[a].mean()) if a.any() else None, rate_b=float(right[~a].mean()) if (~a).any() else None,
             rate_src=float(right.mean()) if len(right) else None,
             move_a_bp=round(float(mv[a].mean()) * 1e4, 2) if field == "m15" and a.any() else None,
             move_b_bp=round(float(mv[~a].mean()) * 1e4, 2) if field == "m15" and (~a).any() else None)
    return r


def part2(rows: list, era: str, rng) -> dict:
    out = {}
    lo, hi = K.ERAS[era]
    for hid, (src, what, _) in K.TESTS.items():
        rs = in_span([s for s in rows if s["src"] == src], lo, hi)
        out[hid] = dict(at15=one_diff(rs, what, "m15", rng), at30=one_diff(rs, what, "m30", rng),
                        years={y: one_diff(in_span(rs, a, b), what, "m15", rng, boot=False) for y, (a, b) in K.YEARS[era].items()})
    return out


def run(era: str) -> dict:
    lo, hi = K.ERAS[era]
    rows = label(in_span(all_signals(), lo, hi))              # nothing outside the era is labelled or measured in this run
    rng = np.random.default_rng([K.SEED, list(K.ERAS).index(era)])
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), era=era, span=f"{lo} .. {hi}", boot=K.BOOT,
                         seed=K.SEED, signals={s: sum(1 for r in rows if r["src"] == s) for s in K.SOURCES}),
           "part1": part1(rows, era, rng), "part2": part2(rows, era, rng)}
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / f"{era}.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def recent() -> dict:
    return run("recent")


def old() -> dict:
    if not (K.RESULTS / "recent.json").exists():
        raise SystemExit("recent first (PREREG 7)")
    if K.OLD_FLAG.exists() and (K.RESULTS / "old.json").exists():
        raise SystemExit("R206 OLD era already read once: " + K.OLD_FLAG.read_text())
    if not K.OLD_FLAG.exists():
        K.OLD_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    return run("old")


def decide() -> dict:
    o = json.loads((K.RESULTS / "old.json").read_text())
    r = json.loads((K.RESULTS / "recent.json").read_text())
    ids = list(K.TESTS)
    ps = [o["part2"][h]["at15"]["p_normal"] for h in ids]
    qs = L.bh_total(ps, K.M_TOTAL)
    q8 = L.bh_total(ps, len(ids))
    out = {"m_total": K.M_TOTAL, "tests": {}}
    for h, q, qf in zip(ids, qs, q8):
        a = o["part2"][h]["at15"]
        g = L.gate(a, {y: v["d"] for y, v in o["part2"][h]["years"].items()}, r["part2"][h]["at15"], q)
        g.update(source=K.TESTS[h][0], what=K.TESTS[h][2], p=a["p"], p_normal=a["p_normal"], q_family=qf)
        out["tests"][h] = g
    out["more_reliable"] = [h for h in ids if out["tests"][h]["more_reliable"]]
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    {"build": build, "recent": recent, "old": old, "decide": decide}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
