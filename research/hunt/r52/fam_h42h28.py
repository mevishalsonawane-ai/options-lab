"""R52 family: H42 question-engine candidates, H28 calendar-day buys and H31 overnight (15:20 -> next morning) buys,
re-tested on Nov 2024 onwards (PREREG.md, FAMILY_BRIEF.md). Written 15 Oct 2026.

Every signal is read from the UNDERLYING index (its minute bars, its session calendar and the exchange's expiry
calendar); the option bought is the 1-ITM nearest-expiry contract, as in all three original studies.

What is reused from the original code (imported, not copied):
    H42  research/hunt/h42/holdout.py `events` (question -> event days / decision minute / side), the questions'
         own conditions from questions.csv / prereg.json, the side used from answers.csv, the 38-exit menu
         (common.py EXITS) and tables.py `dte_arr` / `prev_mean`. The day / minute feature tables themselves were built
         by tables.py `build_all` from deleted caches, so the few columns these rules read are ported below line by line
         (each cites its line in tables.py).
    H28  research/hunt/h28/build.py `EXITS` (E1..E6) and cal.py `DIWALI`; the session / flag rules of cal.py `build` and
         the entry / 'mom' rule of build.py `main` are ported (cal.py reads deleted raw pages and 2006+ daily candles).
    H31  research/hunt/h31/test.py `rule_sides` (the R01..R17 side rules); the 15:20 features of feat.py `main` and the
         exits / contract rule of build.py `main` are ported (feat.py reads deleted breadth / ES / FII files).

Conventions of the original studies, kept here:
    H42  decision on the CLOSE of column s (s = minutes after 09:15), fill at the open of s + 1 -> sig_min = 555 + s;
         1-ITM nearest expiry; expiry days INCLUDED (the tables had no expiry filter); one position at a time.
         A CE+PE answer is two single legs, each with its own exits (h42 priced each leg separately), so they are two
         books here, not an obuy straddle (whose exits act on the summed premium).
         Exits: 'time' = out at the open of entry + horizon (15 / 30 / 60 min) or 15:10 for horizon 'sq';
         'liq' = -15% resting stop, out after 20 min unless >= +5%, 15:10; 'TxSyHz' = +x / -y premium points, out at
         z minutes; all square off at 15:10 (build.py). The ORIGINAL exit is the pre-registered one (prereg.json) for
         H42-3 / H42-4 and, for the not-pre-registered answers (H42-6..9), the answer's best exit in answers.csv: the one
         the catalogue's numbers quote.
    H28  entry time T: signal on the bar closing at T - 1, fill at T's open; 1-ITM 'near'; expiry days allowed; side
         'prior' (fixed: PE / PE / CE as in the original's PRE runs - the walk-forward prior is the sign of the mean
         open->close on flag days since 2006, which needs the 2006+ daily candles that are not in the lake; 19+ years of
         history cannot be flipped by the 2024-26 sessions) or 'mom' (sign of close(T-1) - day open). Weekend sessions
         other than Budget days and Diwali (Muhurat) sessions are not sessions (cal.py `daily`).
    H31  decision on the 15:19 close, buy at the 15:20 open; never on an expiry day; the contract must expire AFTER the
         exit session (never held into its expiry); exits next session X0916 / X0930 / X1015 (time) or P15..P30
         (+-N premium points, time exit 10:15). Multi-day holds use exit_day / exit_min and Exits(sq_off=MD_SQ).

Deviations forced by the lake (stated, not hidden):
    * Previous close = the previous session's last 1-minute close (h42 used the official daily candle close for the gap
      and the month calendar; there are no daily index candles in the lake).
    * H31 P-exits: obuy's multi-day path also watches the points stop / target in the last 9 minutes of the entry day;
      h31 only watched the next session (its stop could not trigger before the next open).
    * H31 'W' vs 'M': h31's W = the nearest weekly, M = the nearest monthly. Here 'near' (weekly where it still exists:
      NIFTY, SENSEX; monthly for BANKNIFTY / FINNIFTY / MIDCPNIFTY, whose weeklies ended Nov 2024) and 'month'.

LEFT (not testable faithfully):
    * H31-5 R15 (CE on FOMC OR US-CPI nights): the US CPI release dates came from ALFRED raw pages saved in a deleted
      scratch folder (h28 cal.py `parse_uscpi`); there is no dated CPI list in the original code. Only R16 (FOMC nights,
      dated list obuy/strategies/gc_common.py `_FED`, the same scheduled-decision dates h28 parsed from the Fed's site)
      is tested.
    * H31 GX exit (sell at 09:16 if the next day's 09:15 bar is against the trade, else 10:15): the exit day / minute
      would have to be decided from the NEXT session's bar inside the signal row, which the signal format forbids
      (no look-ahead in a row). It is left out of the H31 grids; X0916 / X1015 bracket it.
    * H28-2 'prior' in the original grid for BANKNIFTY tom_first3 (H28-4 grid): the prior side needs the 2006+ daily
      history; only 'mom' variants are in the H28-4 grid.
"""
from __future__ import annotations

import importlib.util
import json
import sys
import warnings
from dataclasses import asdict
from datetime import date
from functools import lru_cache
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import r52cfg as K  # noqa: E402  (puts the repo root and research/ on sys.path)
from obuy import config as C  # noqa: E402
from obuy.multiday import MD_SQ, expiry_tables, next_on_or_after  # noqa: E402
from obuy.strategies import gc_common as GC  # noqa: E402

RES = K.ROOT / "research"
H42 = RES / "hunt" / "h42"
SQ10 = 15 * 60 + 10


# ------------------------------------------------------------------------------------------------ loading originals
def _load(name, path, local=()):
    """Import an original study file by path under a private name. `local` = sibling modules it imports by bare name
    (e.g. h42's `common`): loaded from the same folder for the duration of the import only, then the previous entries
    of sys.modules / sys.path are restored, so no other family's module of the same name is shadowed."""
    saved_path = list(sys.path)
    saved = {m: sys.modules.get(m) for m in local}
    saved_err, saved_warn = np.geterr(), list(warnings.filters)     # h42 tables.py silences numpy / warnings globally
    try:
        for m in local:
            sp = importlib.util.spec_from_file_location(m, Path(path).parent / f"{m}.py")
            mod = importlib.util.module_from_spec(sp)
            sys.modules[m] = mod
            sp.loader.exec_module(mod)
        sp = importlib.util.spec_from_file_location(name, path)
        mod = importlib.util.module_from_spec(sp)
        sp.loader.exec_module(mod)
        return mod
    finally:
        sys.path[:] = saved_path
        np.seterr(**saved_err)
        warnings.filters[:] = saved_warn
        for m, v in saved.items():
            if v is None:
                sys.modules.pop(m, None)
            else:
                sys.modules[m] = v


@lru_cache(None)
def h42_holdout():
    return _load("r52_h42_holdout", H42 / "holdout.py", local=("common",))


@lru_cache(None)
def h42_common():
    return _load("r52_h42_common", H42 / "common.py")


@lru_cache(None)
def h42_tables():
    return _load("r52_h42_tables", H42 / "tables.py")


@lru_cache(None)
def h31_test():
    return _load("r52_h31_test", RES / "hunt" / "h31" / "test.py")


@lru_cache(None)
def h28_build():
    return _load("r52_h28_build", RES / "hunt" / "h28" / "build.py")


@lru_cache(None)
def h28_cal():
    return _load("r52_h28_cal", RES / "hunt" / "h28" / "cal.py")


@lru_cache(None)
def h42_question(qid):
    """The question as h42 ran it: prereg.json for the pre-registered candidates, else questions.csv + the side
    answers.csv used (answer.py: side 'auto' -> the sign of the pre-holdout mean outcome)."""
    pre = {r["id"]: r for r in json.load(open(H42 / "prereg.json"))["rules"]}
    if qid in pre:
        r = pre[qid]
        return dict(id=qid, und=r["und"], table=r["table"], universe=r["universe"], cond=r["cond"], entry=r["entry"],
                    side_used=r["side_used"], horizon=r["horizon"], exit=r["exit"])
    Q = pd.read_csv(H42 / "questions.csv", dtype=str, keep_default_na=False).set_index("id")
    A = pd.read_csv(H42 / "answers.csv", dtype=str, keep_default_na=False).set_index("id")
    q, a = Q.loc[qid], A.loc[qid]
    return dict(id=qid, und=q.und, table=q.table, universe=q.universe, cond=q.cond, entry=q.entry,
                side_used=a.side_used, horizon=q.horizon, exit=a.best_exit)


# ------------------------------------------------------------------------------------------------ small helpers
def _ff(a):
    """forward-fill along each row (tables.py `ff`)."""
    return pd.DataFrame(a).T.ffill().T.values


def _last_valid(a, k, back=5):
    """h31 feat.py `last_valid`: the last finite value in a[k-back .. k]."""
    seg = a[max(0, k - back):k + 1]
    ok = np.isfinite(seg)
    return seg[ok][-1] if ok.any() else np.nan


def _prev_close(M):
    """per day row: the previous session's last close (NaN for the first row)."""
    c = M["c"]
    last = np.array([_last_valid(c[i], C.W - 1, C.W) for i in range(len(c))])
    return np.r_[np.nan, last[:-1]]


def _real(ix, d):
    return bool(ix.d[d].get("real", True))


def h42_exit(name, horizon):
    """An h42 exit name (common.EXITS) -> obuy Exits kwargs (build.py: all square off at the 15:10 open)."""
    if name == "time":
        if str(horizon) == "sq":
            return dict(sq_off=SQ10)
        return dict(time_stop=int(horizon), time_gain=None, sq_off=SQ10)
    if name == "liq":
        return dict(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ10)
    t, rest = name[1:].split("S")
    s, h = rest.split("H")
    return dict(tgt_pts=float(t), stop_pts=float(s), time_stop=int(h), time_gain=None, sq_off=SQ10)


# ------------------------------------------------------------------------------------------------ H42 tables (ports)
def h42_day_table(ix, dly=False):
    """The columns of h42's day table (tables.py `build_all`, DAY table) and dly table (`dly_table`) that the R52 rules
    read, one row per session: day = row index into ix.days. Day table rows: real index days with < 20% missing closes
    before 15:00 (build.py `has`, tables.py `keep`). dly rows: every session with bars."""
    M = ix.mat()
    n = len(ix.days)
    o, h, l, c = M["o"], M["h"], M["l"], M["c"]
    cf = _ff(c)
    with np.errstate(all="ignore"):
        o0 = np.array([o[i][np.isfinite(o[i])][0] if np.isfinite(o[i]).any() else np.nan for i in range(n)])   # l.182
        pc = _prev_close(M)
        exp = np.array([bool(ix.d[d]["exp"]) for d in ix.days])
        T = pd.DataFrame({"day": np.arange(n), "exp": exp.astype(int), "dte": h42_tables().dte_arr(exp)})        # l.179
        T["gap"] = (o0 / pc - 1) * 100                                                                          # l.184
        hcum = np.fmax.accumulate(np.nan_to_num(h, nan=-np.inf), axis=1)                                        # l.190
        lcum = np.fmin.accumulate(np.nan_to_num(l, nan=np.inf), axis=1)
        for m in (60, 120, 195, 285):                                                                          # l.212
            cm_ = cf[:, m]
            hs, ls = hcum[:, m], lcum[:, m]
            T[f"id{m}"] = (cm_ / o0 - 1) * 100
            T[f"pos{m}"] = (cm_ - ls) / np.where(hs > ls, hs - ls, np.nan)
            T[f"hiearly{m}"] = (np.nanargmax(np.nan_to_num(h[:, :m + 1], nan=-np.inf), axis=1) < 15).astype(int)
            T[f"loearly{m}"] = (np.nanargmin(np.nan_to_num(l[:, :m + 1], nan=np.inf), axis=1) < 15).astype(int)
    # tdme: trading day of the month counted from its end (tables.py `dly_table` l.109). A month counts only once a
    # later month's session exists, so the data's last, unfinished month is never mislabelled (the original's daily
    # candles always ran past the month it labelled). The exchange calendar is known in advance: no look-ahead.
    ym = np.array([d.year * 100 + d.month for d in ix.days])
    tdme = np.full(n, np.nan)
    if n:
        done = ym < ym[-1]
        s = pd.Series(ym)
        tdme = np.where(done, s.groupby(s).cumcount(ascending=False).values + 1, np.nan)
    T["tdme"] = tdme
    any_bar = np.isfinite(c).any(axis=1)
    if dly:
        keep = any_bar
    else:
        has = np.array([np.isnan(c[i, :345]).mean() <= 0.2 for i in range(n)]) if n else np.zeros(0, bool)
        keep = has & np.array([_real(ix, d) for d in ix.days], bool)
    return T[keep].reset_index(drop=True)


def h42_min_table(ix):
    """h42's minute table columns p5 / z5 (tables.py l.259-281): every 5-minute decision point s = 5 .. 340
    (09:20 .. 14:55 closes) of the day-table sessions; z5 = the 5-minute move in bps over the sd of 5-minute log
    moves averaged over the PREVIOUS 5 sessions (prev_mean, min 3)."""
    M = ix.mat()
    n = len(ix.days)
    c = M["c"]
    cf = _ff(c)
    grid = np.arange(5, 341, 5)
    with np.errstate(all="ignore"):
        lr = np.log(cf)
        k = 5
        r = lr[:, k::k] - lr[:, :-k:k][:, : lr[:, k::k].shape[1]]
        var = np.nanvar(r[:, :374 // k], axis=1) if n else np.zeros(0)
        sd = np.sqrt(h42_tables().prev_mean(var, 5)) * 1e4
    dt = h42_day_table(ix)
    rows = []
    for i in dt.day.values:
        cs = cf[i, grid]
        with np.errstate(all="ignore"):
            p5 = (cs / cf[i, np.maximum(grid - k, 0)] - 1) * 1e4
        rows.append(pd.DataFrame({"day": i, "s": grid, "p5": p5, "z5": p5 / sd[i]}))
    if not rows:
        return pd.DataFrame(columns=["day", "s", "p5", "z5"])
    return pd.concat(rows, ignore_index=True)


def h42_signals(mk, qid="Q0176", hz=None):
    """An h42 question's trades: h42 holdout.py `events` on the ported table, one row per leg (CE+PE answers = two
    books). sig_min = 555 + s (the decision bar), fill at the next open."""
    q = h42_question(qid)
    u = q["und"]
    ix = mk.index(u)
    df = h42_min_table(ix) if q["table"] == "min" else h42_day_table(ix, dly=q["table"] == "dly")
    out = []
    if len(df):
        days, s, sd = h42_holdout().events(q, df)
        for di, si, sdi in zip(days, s, sd):
            legs = (1, -1) if sdi == 0 else (int(sdi),)
            for side in legs:
                out.append(dict(und=u, day=ix.days[int(di)], sig_min=int(C.OPEN_M + si), side=side,
                                book=f"h42_{qid}_{u}" + ("" if sdi != 0 else ("_C" if side > 0 else "_P"))))
    return pd.DataFrame(out, columns=["und", "day", "sig_min", "side", "book"])


def _h42_grid(cands, n, seed=K.SEED):
    """At most n of the candidate overrides, a fixed random subset (seed r52cfg.SEED), in menu order - the same rule
    r52rules.py uses for the obuy catalogue grids above the cap."""
    if len(cands) <= n:
        return list(cands)
    rng = np.random.default_rng(seed)
    return [cands[i] for i in sorted(rng.choice(len(cands), n, replace=False))]


def _h42_spec(key, title, qid, why, extra_first=(), alt_q=()):
    q = h42_question(qid)
    exits = list(h42_common().EXITS)
    orig_x = q["exit"]
    cands = [dict(exits=h42_exit(x, q["horizon"])) for x in exits if x != orig_x]
    for aq in alt_q:                       # another question of the same catalogue key: its exits too
        a = h42_question(aq)
        cands += [dict(params=dict(qid=aq), exits=h42_exit(x, a["horizon"])) for x in exits if x != a["exit"]]
    first = list(extra_first)
    grid = first + _h42_grid(cands, 23 - len(first))
    return dict(
        key=key, title=title, source="index", why=why,
        orig=dict(fn=h42_signals, params=dict(qid=qid), money=1, series="near", unds=(q["und"],),
                  exits=h42_exit(orig_x, q["horizon"]), expiry="allow", pos=dict(one_at_a_time=True), window=None),
        grid=grid)


# ------------------------------------------------------------------------------------------------ H28 (calendar)
def h28_sessions(ix):
    """cal.py `daily`: weekend sessions other than Budget days, and Diwali (Muhurat) sessions, are not sessions."""
    budget = {date.fromisoformat(x) for x in GC._BUDGET}
    diwali = {date.fromisoformat(x) for x in h28_cal().DIWALI}
    return [d for d in ix.days if (d.weekday() < 5 or d in budget) and d not in diwali]


def h28_flag(ix, flag, sess):
    """{session: bool} for the calendar flags the R52 keys use (cal.py `build`): moy_MM (calendar month), exp_day (the
    data's expiry flag), tom_first3 (first 3 sessions of the calendar month)."""
    if flag.startswith("moy_"):
        m = int(flag[4:])
        return {d: d.month == m for d in sess}
    if flag == "exp_day":
        return {d: bool(ix.d[d]["exp"]) for d in sess}
    if flag == "tom_first3":
        out, rk, cur = {}, 0, None
        for d in sess:
            k = (d.year, d.month)
            rk = rk + 1 if k == cur else 0
            cur = k
            out[d] = rk < 3
        return out
    raise ValueError(flag)


def h28_signals(mk, legs=(("moy_01", "NIFTY", "0920", "prior", -1),)):
    """h28 Part B trades: each leg = (flag, index, entry time HHMM, mode, prior side). Signal on the bar closing at
    T - 1, fill at T's open (build.py `main`); mode 'prior' = the fixed walk-forward side, 'mom' = sign(close at T - 1
    - the day's open), no trade when 0."""
    out = []
    for flag, u, T, mode, prior in legs:
        ix = mk.index(u)
        M = ix.mat()
        sess = h28_sessions(ix)
        fl = h28_flag(ix, flag, sess)
        pos = {d: i for i, d in enumerate(ix.days)}
        sm = int(T[:2]) * 60 + int(T[2:]) - 1
        col = sm - C.OPEN_M
        for d in sess:
            if not fl[d]:
                continue
            p = pos[d]
            o = M["o"][p]
            ok = np.nonzero(np.isfinite(o))[0]
            if not len(ok):
                continue
            o0 = o[0] if np.isfinite(o[0]) else o[ok[0]]
            c = M["c"][p, :col + 1]
            c = c[np.isfinite(c)]
            if not len(c):
                continue
            side = int(prior) if mode == "prior" else int(np.sign(c[-1] - o0))
            if side == 0:
                continue
            out.append(dict(und=u, day=d, sig_min=sm, side=side, book=f"h28_{flag}_{u}_{T}"))
    return pd.DataFrame(out, columns=["und", "day", "sig_min", "side", "book"])


def h28_exit(name):
    return asdict(h28_build().EXITS[name])


def _h28_spec(key, title, flag, und, T, X, prior, why):
    leg = lambda t, m: dict(legs=((flag, und, t, m, prior),))  # noqa: E731
    others = [x for x in h28_build().EXITS if x != X]
    grid = [dict(exits=h28_exit(x)) for x in others]                                   # the other 5 exits
    grid += [dict(params=leg(t, "prior")) for t in ("0920", "1015", "1200", "1400") if t != T]
    grid += [dict(params=leg(t, "mom")) for t in ("1015", "1200", "1400")]              # 'mom' only after 09:20
    return dict(key=key, title=title, source="index (calendar)", why=why,
                orig=dict(fn=h28_signals, params=leg(T, "prior"), money=1, series="near", unds=(und,),
                          exits=h28_exit(X), expiry="allow", pos=dict(one_at_a_time=True), window=None),
                grid=grid)


# ------------------------------------------------------------------------------------------------ H31 (overnight)
S1519 = 15 * 60 + 19 - C.OPEN_M
S1419 = 14 * 60 + 19 - C.OPEN_M
X_MIN = {"X0916": 9 * 60 + 16, "X0930": 9 * 60 + 30, "X1015": 10 * 60 + 15}
P_EXITS = ("P15", "P20", "P25", "P30")


def h31_signals(mk, rule="R03", filt="F0", xit="X1015", series="near",
                unds=("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")):
    """h31 overnight buys: the 15:19 features of feat.py `main` (loc, LH, DAY; FOMC nights from gc_common._FED), the
    side from h31 test.py `rule_sides`, entry on the 15:19 close (15:20 open), out next session at xit (P-exits: time
    exit 10:15; the points stop / target are the rule's Exits). Not on expiry days; the contract (series) must expire
    after the exit session (build.py); F1 = one-night holds only, F2 = F1 and >= 7 calendar days to expiry after the
    exit day (test.py `pick`)."""
    fomc = sorted(date.fromisoformat(x) for x in GC._FED)
    xmin = X_MIN["X1015"] if xit in P_EXITS else X_MIN[xit]
    rows = []
    for u in unds:
        ix = mk.index(u)
        Mx = ix.mat()
        wk, mo = expiry_tables(ix)
        lst = mo if series == "month" else wk
        days = ix.days
        for i in range(1, len(days) - 1):
            d, nd = days[i], days[i + 1]
            if ix.d[d]["exp"]:
                continue
            c, h, l = Mx["c"][i], Mx["h"][i], Mx["l"][i]
            x = _last_valid(c, S1519)
            if not np.isfinite(x):
                continue
            with np.errstate(all="ignore"):
                hi, lo = np.nanmax(h[:S1519 + 1]), np.nanmin(l[:S1519 + 1])
            pc = _last_valid(Mx["c"][i - 1], C.W - 1, 30)
            j = int(np.searchsorted(np.array(fomc, dtype="datetime64[D]"), np.datetime64(d)))
            rows.append(dict(und=u, day=d, nday=nd, nights=(nd - d).days,
                             loc=(x - lo) / (hi - lo) if hi > lo else 0.5,
                             LH=x / _last_valid(c, S1419) - 1, DAY=x / pc - 1,
                             BR=np.nan, VIXCH=np.nan, VIXLOW=np.nan, ES=np.nan, FIIZ=np.nan,
                             fomc=any(d <= e < nd for e in fomc[j:j + 3]), uscpi=False,
                             cexp=next_on_or_after(lst, d)))
    cols = ["und", "day", "sig_min", "side", "book", "exit_day", "exit_min"]
    if not rows:
        return pd.DataFrame(columns=cols)
    f = pd.DataFrame(rows)
    side = h31_test().rule_sides(f)[rule].values
    keep = side != 0
    keep &= np.array([e is not None and e > nd for e, nd in zip(f.cexp, f.nday)])
    if filt in ("F1", "F2"):
        keep &= f.nights.values == 1
    if filt == "F2":
        keep &= np.array([e is not None and (e - nd).days >= 7 for e, nd in zip(f.cexp, f.nday)])
    g = f[keep]
    return pd.DataFrame(dict(und=g.und.values, day=g.day.values, sig_min=15 * 60 + 19, side=side[keep].astype(int),
                             book=[f"h31_{rule}_{u}" for u in g.und.values], exit_day=g.nday.values,
                             exit_min=xmin), columns=cols)


def h31_exit(xit):
    if xit in P_EXITS:
        n = float(xit[1:])
        return dict(stop_pts=n, tgt_pts=n, sq_off=MD_SQ)
    return dict(sq_off=MD_SQ)


def _h31_grid(rules):
    g = []
    for r in rules:
        base = dict(rule=r)
        if r != rules[0]:
            g.append(dict(params=dict(base)))                                         # the other rule, orig settings
        g += [dict(params=dict(base, xit=x), exits=h31_exit(x)) for x in ("X0916", "X0930") + P_EXITS]
        g += [dict(params=dict(base, filt=f)) for f in ("F1", "F2")]
        g.append(dict(params=dict(base, series="month"), series="month"))               # M contract
    return g


# ------------------------------------------------------------------------------------------------ SPECS
def _specs():
    S = []
    S.append(_h42_spec(
        "H42-3", "MIDCP 14:00 early-extreme fade (Q0176 / Q0177)", "Q0176",
        "original = Q0176 as pre-registered for h42's holdout (prereg.json: day high made in the first 15 min at 14:00 "
        "-> PE, 1-ITM near, +25 / -20 pts, 30 min). Q0177 (early low -> CE, out 15:10, also pre-registered) is the "
        "key's other half: one exit menu cannot carry both, so it is g01 and both questions' exits fill the grid "
        "(h42's 38-exit menu, fixed random subset).",
        extra_first=(dict(params=dict(qid="Q0177"), exits=h42_exit("time", "sq")),), alt_q=("Q0177",)))
    S.append(_h42_spec(
        "H42-4", "MIDCP day-before-expiry CE+PE (Q0378)", "Q0378",
        "original = Q0378 as pre-registered (prereg.json: dte == 1 -> buy 1-ITM CE and PE at 09:16, each leg +30 / -10 "
        "pts, 15 min); legs are separate books as h42 priced them. Grid: h42's 38-exit menu (fixed random subset)."))
    S.append(_h42_spec(
        "H42-6", "NIFTY 12:30 weak-day PE (Q0521)", "Q0521",
        "original = answers.csv #17 = Q0521 (12:30 down > 0.5% and in the bottom 20% of the range -> PE, 1-ITM near) "
        "with its best exit = its own horizon (hold to 15:10), the +288/tr the catalogue quotes. Grid: h42's 38 exits."))
    S.append(_h42_spec(
        "H42-7", "NIFTY last-3-days-of-month CE (Q1213)", "Q1213",
        "original = answers.csv #19 = Q1213 (last 3 trading days of the month -> CE at 09:16, 1-ITM near) with its "
        "best exit 'liq' (-15% stop, 20 min unless +5%, 15:10), the +275/tr the catalogue quotes. Grid: h42's 38 exits."))
    S.append(_h42_spec(
        "H42-8", "BANKNIFTY 5-min spike fade (Q0642)", "Q0642",
        "original = answers.csv #14 = Q0642 (5-min rise > 1.5 sd at a 5-minute decision point 09:20..14:55 -> PE, "
        "1-ITM near) with its best exit = own horizon (out after 15 min), the +93 gross / -41 net per trade quoted. "
        "One position at a time (h42's holdout rule). Grid: h42's 38 exits."))
    S[-1]["orig"]["window"] = (C.OPEN_M + 5, C.OPEN_M + 340)
    S.append(_h42_spec(
        "H42-9", "SENSEX gap-down bounce CE (Q0125)", "Q0125",
        "original = answers.csv #3 = Q0125 (gap down > 0.5% -> CE at 09:16, 1-ITM near) with its best exit 'liq', the "
        "+314/tr on 34-35 trades the catalogue quotes; its own horizon (out after 15 min) is in the grid."))
    S.append(_h28_spec(
        "H28-1", "January NIFTY PE at 09:20 (Liquidity exits)", "moy_01", "NIFTY", "0920", "E1_liq", -1,
        "original = h28 variant (moy_01, NIFTY, 09:20, E1_liq, prior = PE), the top PRE variant (HUNT_H28.md table). "
        "Grid: h28's own axes around it (other 5 exits, other entry times, 'mom' side)."))
    S.append(_h28_spec(
        "H28-2", "Expiry-day BANKNIFTY PE at 09:20, hold", "exp_day", "BANKNIFTY", "0920", "E6_close", -1,
        "original = h28 variant (exp_day, BANKNIFTY, 09:20, E6_close, prior = PE), the WF pick 2024-25. BANKNIFTY has "
        "monthly expiries only in this window (about one session a month)."))
    S.append(_h28_spec(
        "H28-3", "April BANKNIFTY CE at 09:20, hold", "moy_04", "BANKNIFTY", "0920", "E6_close", 1,
        "original = h28 variant (moy_04, BANKNIFTY, 09:20, E6_close, prior = CE). April 2025 is the only design April; "
        "April 2026 is in the final test."))
    legs4 = (("moy_01", "NIFTY", "1015", "mom", 0), ("tom_first3", "BANKNIFTY", "1400", "mom", 0))
    S.append(dict(
        key="H28-4", title="January NIFTY 10:15 mom + first-3-days BANKNIFTY 14:00 mom, hold", source="index (calendar)",
        why="original = the two h28 variants the catalogue row names, traded together (each its own book): (moy_01, "
            "NIFTY, 10:15, E6_close, mom) and (tom_first3, BANKNIFTY, 14:00, E6_close, mom). Grid: the other 5 exits "
            "and the other 'mom' entry times of each leg (prior side not computable, see LEFT).",
        orig=dict(fn=h28_signals, params=dict(legs=legs4), money=1, series="near", unds=("NIFTY", "BANKNIFTY"),
                  exits=h28_exit("E6_close"), expiry="allow", pos=dict(one_at_a_time=True), window=None),
        grid=[dict(exits=h28_exit(x)) for x in h28_build().EXITS if x != "E6_close"]
        + [dict(params=dict(legs=((legs4[0][0], legs4[0][1], t, "mom", 0), legs4[1]))) for t in ("1200", "1400")]
        + [dict(params=dict(legs=(legs4[0], (legs4[1][0], legs4[1][1], t, "mom", 0)))) for t in ("1015", "1200")]))
    U5 = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
    S.append(dict(
        key="H31-4", title="Overnight close-location follow (R03) / day-move follow (R08)", source="index",
        why="h31 froze no single variant. original = R03 (15:19 price in the top 25% of the day's range -> CE, bottom "
            "25% -> PE; the catalogue's first rule, 82% of its variants positive), all 5 indices, 1-ITM nearest "
            "contract that expires after the exit day, F0 (every night), out next session 10:15 (the exit every h31 "
            "walk-forward pick used). Grid: h31's own axes - R08 (day move vs previous close +-0.3%, follow), exits "
            "X0916 / X0930 / P15-P30, filters F1 / F2, monthly contract (GX left out, see LEFT).",
        orig=dict(fn=h31_signals, params=dict(rule="R03", filt="F0", xit="X1015", series="near", unds=U5), money=1,
                  series="near", unds=U5, exits=h31_exit("X1015"), expiry="skip", pos=dict(one_at_a_time=True),
                  window=None),
        grid=_h31_grid(("R03", "R08"))))
    S.append(dict(
        key="H31-5", title="Overnight CE on FOMC nights (R16)", source="index (calendar)",
        why="original = R16 (CE on the night after a scheduled FOMC decision; dates gc_common._FED), set up as H31-4's "
            "original (all 5 indices, 1-ITM nearest, F0, out 10:15). R15 (FOMC or US-CPI) is LEFT: no dated US-CPI "
            "list in the original code. About 8 nights a year per index. Grid: h31's exits / filters / monthly.",
        orig=dict(fn=h31_signals, params=dict(rule="R16", filt="F0", xit="X1015", series="near", unds=U5), money=1,
                  series="near", unds=U5, exits=h31_exit("X1015"), expiry="skip", pos=dict(one_at_a_time=True),
                  window=None),
        grid=_h31_grid(("R16",))))
    return S


SPECS = _specs()
