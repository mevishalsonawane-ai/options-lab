"""R61 feature rows for a candidate (PREREG section 4): A2's 29 (R57 r57model.row_a, unchanged) + R61's 30.

Every value is read at the signal row frow = entry row - 1 (the bar that closes at the fill minute's open).
"""
from __future__ import annotations

import numpy as np

import r61cfg as K
import r57model as M57
import r57setups as S57
import r61chain as C
import r61data

A2 = M57.names_a(False)

DOC = {   # one line per R61 feature, exported with a model so the phone (and Jarvis) can explain it
    "oi_near_log": "log10(1 + call + put OI at the 5 strikes nearest the money)",
    "oi_near_chg_pct": "% change of call + put OI (11 strikes nearest the money) since the day's first chain minute",
    "writer_tilt30": "put OI % change minus call OI % change over 30 min (11 near strikes), x side",
    "buildup": "build-up over 30 min: +2 long build-up, +1 short covering, -1 long unwinding, -2 short build-up, x side",
    "pcr_oi_chg30": "put/call OI ratio (11 near strikes) now minus 30 min ago, x side",
    "pcr_vol": "(put volume / call volume since 09:15, 11 near strikes) - 1, x side",
    "pcr_rank5": "(rank of today's put/call OI ratio among the last 5 sessions at this minute) - 0.5, x side",
    "pcr_rank20": "(the same over 20 sessions) - 0.5, x side",
    "maxpain_dist_pct": "(max-pain strike - index) / index x 100, x side",
    "wall_ahead_pct": "% distance to the biggest OI wall on the trade's way (call: call-OI wall above; put: put wall "
                      "below)",
    "wall_behind_pct": "% distance to the other side's biggest OI wall (behind the trade)",
    "iv_rank60": "ATM IV rank (min-max) among the last 60 sessions at this minute",
    "iv_chg_day": "ATM IV now minus at the day's first chain minute (IV points)",
    "smile_curv": "(put IV 3 strikes below + call IV 3 strikes above) / 2 - ATM IV",
    "term_slope": "NIFTY only: monthly ATM IV - weekly ATM IV",
    "gex_norm": "net gamma exposure / gross (calls +, puts -), the 21-strike chain",
    "gex_sign": "sign of gex_norm",
    "flip_dist_pct": "% distance from the index to the gamma-flip level (+-3% grid), x side",
    "synth_basis_bps": "synthetic futures premium from put-call parity at the ATM strike, basis points",
    "delta_abs": "|delta| of the contract the setup buys",
    "gamma_1pct": "change in that contract's delta for a 1% index move",
    "theta_pct_day": "that contract's theta per session as % of its premium",
    "vega_pct": "that contract's premium change for +1 IV point, % of premium",
    "vix_rank60": "India VIX rank among the last 60 sessions at this minute",
    "va_pos": "(index - previous day's POC) / (VAH - VAL), x side",
    "event_rbi_budget": "1 on an RBI policy or Union Budget day",
    "post_us_event": "1 on the first session after a US FOMC decision or US CPI release",
    "other_expiry_today": "1 when another of NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY expires today",
    "fii_fut_long_share": "FII index-futures long share, previous session's NSE participant OI",
    "fii_fut_long_chg": "its change from the session before",
}
assert sorted(DOC) == sorted(K.NEW)


def names(blocks=()) -> list:
    out = list(A2)
    for b in ("CH", "GK", "CX"):
        if b in blocks:
            out += K.BLOCKS[b]
    return out


def new_features(und: str, D, frow: int, side: str, strike, chain_day: dict | None, ctx_day: dict | None,
                 lev=None) -> dict:
    """R61's 30 features for one candidate (signed by side where PREREG says so); NaN where unknown."""
    s = 1.0 if side == "C" else -1.0
    out = {n: np.nan for n in K.NEW}
    px = C.ffill(D.idx[3, :frow + 1])
    px = float(px[-1]) if len(px) else np.nan
    if chain_day is not None:
        f = chain_day["f"]
        for n in ("oi_near_log", "oi_near_chg_pct", "writer_tilt30", "buildup", "pcr_oi_chg30", "pcr_vol",
                  "maxpain_dist_pct", "iv_chg_day", "smile_curv", "term_slope", "gex_norm", "gex_sign",
                  "flip_dist_pct", "synth_basis_bps"):
            if n in f:
                out[n] = float(f[n][frow])
        cw, pw = float(f["call_wall_pct"][frow]), float(f["put_wall_pct"][frow])
        out["wall_ahead_pct"], out["wall_behind_pct"] = (cw, pw) if s > 0 else (pw, cw)
        if strike is not None:
            out.update(C.held_greeks(dict(snap=chain_day["snap"]), side, strike, frow, px, float(D.dte)))
    if ctx_day is not None:
        for n in ("pcr_rank5", "pcr_rank20", "iv_rank60", "vix_rank60"):
            out[n] = float(ctx_day[n][frow])
        for n in ("other_expiry_today", "event_rbi_budget", "post_us_event", "fii_fut_long_share",
                  "fii_fut_long_chg"):
            out[n] = float(ctx_day[n])
    if lev is not None and np.isfinite(lev).all() and lev[1] > lev[2] and np.isfinite(px):
        out["va_pos"] = (px - lev[0]) / (lev[1] - lev[2])
    for n in K.SIGNED_NEW:
        if np.isfinite(out[n]):
            out[n] = (out[n] - K.CENTRE.get(n, 0.0)) * s
    return out


def add(rows: list) -> list:
    """Adds R61's features to R57 family-A rows (r57study.a_rows output) in place: r['f'] gains the 30 names."""
    ctx = r61data.load_ctx()
    for r in rows:
        und, d = r["und"], r["day"]
        D = r61data.r57data.load(und)[d]
        frow = r["er"] - 1
        lev = S57.prev_value_area(und).get(d)
        r["f"].update(new_features(und, D, frow, r["side"], r.get("strike"), r61data.load_chain(und).get(d),
                                   ctx.get(und, {}).get(d), lev))
    return rows
