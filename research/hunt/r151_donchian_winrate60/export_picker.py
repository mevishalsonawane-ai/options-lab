"""R151 -> app: the R72 logistic picker (R72-20-4-M2, Amendment C threshold 0.4656) for the app's SHADOW log.

    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r151_donchian_winrate60/export_picker.py fit
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r151_donchian_winrate60/study_c.py run   # must match the committed c_r72_picker.json
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r151_donchian_winrate60/export_picker.py export

`fit` trains the model exactly as `study.py design` did (all design trades, clean features) and freezes it to the cache.
`export` writes the model's numbers (results/r72_picker_model.json) and the parity fixtures the app's tests read
(android/engine/src/test/resources/r72picker/*.json.gz): for a few days, every input the app's function gets and the
features and score Python gives.
"""
from __future__ import annotations

import gzip
import json
import pickle
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
CMD = (sys.argv[1:] or [""])[0]
ARG = (sys.argv[2:] or ["R72"])[0]
sys.argv = [sys.argv[0], "design"]
import numpy as np  # noqa: E402

import study as T  # noqa: E402

S, K, B = T.S, T.K, T.B
PID, ARM, PARAMS, THR = {"R72": ("R72-20-4-M2", "R72", (20, 4), 0.4656), "R51": ("R51-20-3.0-M2", "R51", (20, 3.0), 0.4469)}[ARG]
FIX = HERE.parents[2] / "android" / "engine" / "src" / "test" / "resources" / "r72picker"
DAYS = (date(2026, 4, 7), date(2026, 6, 24), date(2026, 9, 1))     # final-period days with R72 candidates (checked below)
SYMS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
HIST_SESSIONS = 25


def fit() -> None:
    us = T.units(ARM, PARAMS, K.START, K.DESIGN_END)
    fx = T.feats_of_units(us)
    trades = S.walk(ARM, us)
    tk = [T.cand_key(t["und"], t["day"], t["er_sig"], t["side"]) for t in trades]
    y = np.array([t["net"] > 0 for t in trades], int)
    mdl = T.make_model("M2").fit(T.matrix(tk, fx), y)
    T.CACHE.mkdir(parents=True, exist_ok=True)
    p = T.CACHE / "models.pkl"
    models = pickle.loads(p.read_bytes()) if p.exists() else {}
    models[PID] = (mdl, list(PARAMS))
    p.write_bytes(pickle.dumps(models))
    print("fit on", len(trades), "design trades; positives", int(y.sum()))


def model_numbers(mdl) -> dict:
    return T.PK.model_numbers(mdl, T.FEATS, THR)


def _bars5(und, day_i, n_sessions):
    """The earlier sessions' 5-min bars (oldest first) as [date, b, o, h, l, c] from R150's continuous series."""
    x = B.lv(und)
    lo = max(0, day_i - n_sessions)
    js = np.flatnonzero((x.b["day"] >= lo) & (x.b["day"] < day_i))
    return [[str(x.dates[int(x.b["day"][j])]), int(x.b["end"][j]) // 5, *[float(x.b[k][j]) for k in ("o", "h", "l", "c")]] for j in js]


def _minutes(arr):
    """(4, 375) O H L C -> [[o, h, l, c] or None per row]."""
    return [None if not np.isfinite(arr[3][r]) else [float(arr[0][r]), float(arr[1][r]) if np.isfinite(arr[1][r]) else None,
            float(arr[2][r]) if np.isfinite(arr[2][r]) else None, float(arr[3][r])] for r in range(arr.shape[1])]


def export() -> None:
    assert ARG == "R72", "only the R72 picker is exported to the app"
    S.mods()["C"].open_final()
    mdl, _ = pickle.loads((T.CACHE / "models.pkl").read_bytes())[PID]
    nums = model_numbers(mdl)
    (T.RESULTS / "r72_picker_model.json").write_text(json.dumps(nums, indent=1))
    FIX.mkdir(parents=True, exist_ok=True)
    daily = S.mods()["A"].feats()["daily"]           # R101's daily frames (index_daily: date, open, close)
    vixm = T.vix_days()
    for day in DAYS:
        us = [u for u in T.units(ARM, PARAMS, day, day) if u[0][1] == day]
        assert us, f"no R72 candidates on {day}"
        fx = T.feats_of_units(us)
        cands = []
        for key, cs, _, _ in us:
            for n, c in enumerate(sorted(cs, key=lambda c: int(c["er"]))):
                k = T.cand_key(key[0], day, c["er"], c["side"])
                f = fx[k]
                row = np.array([[f.get(n_, np.nan) for n_ in T.FEATS]], float)
                cands.append({"und": key[0], "er": int(c["er"]), "side": c["side"], "earlier": n, "f_count": c.get("f_count"),
                              "features": {n_: (None if not np.isfinite(v) else float(v)) for n_, v in zip(T.FEATS, row[0])},
                              "score": float(mdl.predict_proba(row)[0, 1])})
        syms = {}
        for u in SYMS:
            x = B.lv(u)
            if day not in x.pos:
                continue
            i = x.pos[day]
            dd = daily[u]
            syms[u] = {"hist5": _bars5(u, i, HIST_SESSIONS), "today": _minutes(x.idx[day]),
                       "daily": [[str(r.date), float(r.open), float(r.close)] for r in dd[dd.date < day].tail(300).itertuples()],
                       "today_daily_open": float(dd[dd.date == day].open.iloc[0]) if (dd.date == day).any() else None}
        vd = sorted(d for d in vixm if d < day)[-HIST_SESSIONS:]
        vv = daily["INDIAVIX"]
        vt = vixm.get(day)
        syms["INDIAVIX"] = {"prev_closes": [[str(d), float(vixm[d][:, 3][np.isfinite(vixm[d][:, 3])][-1])] for d in vd],
                            "today": None if vt is None else [None if not np.isfinite(vt[r, 3]) else
                                                              [float(vt[r, 0]), float(vt[r, 1]), float(vt[r, 2]), float(vt[r, 3])] for r in range(375)],
                            "daily": [[str(r.date), float(r.open), float(r.close)] for r in vv[vv.date < day].tail(300).itertuples()]}
        out = {"day": str(day), "candidates": cands, "symbols": syms}
        with gzip.open(FIX / f"{day}.json.gz", "wt") as fh:
            json.dump(out, fh, separators=(",", ":"))
        print(day, len(cands), "candidates")


if __name__ == "__main__":
    {"fit": fit, "export": export}[CMD]()
