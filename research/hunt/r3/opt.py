"""R3 part 2: GOLDM option-level simulation of EMA(8)/EMA(21)+ADX(14)>15, 1 lot (10 x premium), real charges + spread.
Rules (the outside report's): CE on bullish cross / PE on bearish; entry next minute after the signal bar closes;
25% stop, 50% target on the option's 1-min LOW/HIGH (traded minutes only; stop first if both in one minute; gap fills at
the minute open); 60-min max hold (exit at the last traded close <= entry+60); 23:15 square-off; one position at a time;
max 2 entries a day. Filters: ALL, AM (signal bar starts 09:00-14:55), PM (15:00+), each run as its own system.
Fill hygiene (CLEAN): the entry minute must have a trade (volume > 0); else the next 2 minutes are tried; else no trade.
RAW: entry at the open of the next minute whatever its volume (closer to a naive backtest).
Charges = McxCosts.kt (Zerodha) per leg; spread = full relative spread SP_AM before 17:00 / SP_PM after, half per side.
Usage: python3 -P opt.py -> scratchpad/hunt/r3/work/trades_*.parquet"""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/r3")
from r3lib import *
W = R3 / "work"
MULT = 10
CUT = 23 * 60 + 15


def charges(prem_rs, buy):
    t = prem_rs
    brk = 20.0
    ctt = 0 if buy else t * 0.0005
    txn = t * 0.000418
    sebi = t * 10 / 1e7
    stamp = t * 0.00003 if buy else 0
    gst = (brk + txn + sebi) * 0.18
    return brk + ctt + txn + sebi + stamp + gst


def load_opts():
    a = pd.read_parquet(SP / "m3" / "raw" / "opt_GOLDM.parquet", columns=["ts", "k", "cp", "strike", "open", "high", "low", "close", "volume"])
    b = pd.read_parquet(R3 / "raw" / "goldm_opt_tail.parquet", columns=["ts", "k", "cp", "strike", "open", "high", "low", "close", "volume"])
    b = b[b.ts > a.ts.max()]
    o = pd.concat([a, b], ignore_index=True)
    o["ts"] = pd.DatetimeIndex(o.ts).tz_convert("Asia/Kolkata")
    o["day"] = o.ts.dt.tz_localize(None).dt.normalize().dt.date
    o["tod"] = (o.ts.dt.hour * 60 + o.ts.dt.minute).astype(np.int16)
    o = o.sort_values(["ts", "volume"]).drop_duplicates(["ts", "cp", "strike"], keep="last")  # keep traded row
    for c in ["open", "high", "low", "close", "volume", "strike"]:
        o[c] = o[c].astype("float64")
    return o


class Book:
    def __init__(self, o):
        self.atm = {}   # (day, tod, cp, k) -> strike
        z = o[o.k.isin([-1, 0, 1])]
        self.atm = dict(zip(zip(z.day, z.tod, z.cp, z.k), z.strike))
        self.path = {key: g[["tod", "open", "high", "low", "close", "volume"]].values for key, g in o.groupby(["day", "cp", "strike"])}


def simulate(sig, bk, filt, strike_mode, sp_am, sp_pm, raw=False, skip_days=()):
    sig = sig.sort_values(["day", "sig_min"])
    if filt == "AM":
        sig = sig[sig.start < 15 * 60]
    elif filt == "PM":
        sig = sig[sig.start >= 15 * 60]
    out, busy_until, nday = [], {}, {}
    for r in sig.itertuples():
        d = r.day
        if d in skip_days or nday.get(d, 0) >= 2 or busy_until.get(d, -1) >= r.sig_min + 1:
            continue
        cp = 0 if r.side == 1 else 1
        t_e = r.sig_min + 1
        if t_e >= CUT:
            continue
        kk = 0 if strike_mode == "ATM" else (-1 if cp == 0 else 1)
        K = bk.atm.get((d, t_e, cp, kk)) or bk.atm.get((d, r.sig_min, cp, kk))
        if K is None:
            continue
        P = bk.path.get((d, cp, K))
        if P is None:
            continue
        tod = P[:, 0]
        i0 = None
        for t in range(t_e, t_e + (1 if raw else 3)):
            j = np.searchsorted(tod, t)
            if j < len(tod) and tod[j] == t and (raw or P[j, 5] > 0):
                i0 = j; break
        if i0 is None:
            out.append(dict(day=d, start=r.start, sig_min=r.sig_min, side=r.side, K=K, status="nofill")); continue
        E = P[i0, 1]
        if not E > 0:
            continue
        t_end = min(int(tod[i0]) + 60, CUT)
        stop, tgt = 0.75 * E, 1.5 * E
        X, why, tx = None, "time", None
        last = E
        for j in range(i0, len(tod)):
            t = tod[j]
            if t > t_end:
                break
            o_, h_, l_, c_, v_ = P[j, 1:]
            if v_ <= 0:
                continue
            if l_ <= stop:
                X, why, tx = (min(o_, stop) if j > i0 else stop), "stop", t; break
            if h_ >= tgt:
                X, why, tx = (max(o_, tgt) if j > i0 else tgt), "target", t; break
            last, tx = c_, t
        if X is None:
            X = last; tx = tx if tx is not None else tod[i0]
            why = "squareoff" if t_end == CUT and tx > CUT - 61 and (tod[i0] + 60 > CUT) else "time"
        s_in = sp_am if tod[i0] < 17 * 60 else sp_pm
        s_out = sp_am if tx < 17 * 60 else sp_pm
        gross = (X - E) * MULT
        spread = (E * s_in / 2 + X * s_out / 2) * MULT
        ch = charges(E * MULT, True) + charges(X * MULT, False)
        out.append(dict(day=d, start=r.start, sig_min=r.sig_min, side=r.side, K=K, status="ok", t_in=int(tod[i0]), E=E,
                        t_out=int(tx), X=X, why=why, gross=gross, charges=ch, spread=spread, net=gross - ch - spread,
                        prem_lot=E * MULT))
        nday[d] = nday.get(d, 0) + 1
        busy_until[d] = int(tx)
    return pd.DataFrame(out)


if __name__ == "__main__":
    o = load_opts()
    print("options", len(o), o.ts.min(), o.ts.max(), flush=True)
    bk = Book(o)
    days_all = sorted(o.day.unique())
    exp_days = set(GOLDM_EXP) | {dt.date(2026, 3, 26)}
    res = []
    for src in ["GOLDM_C", "GOLDM_NOV"]:
        m = pd.read_parquet(W / f"min_{src}.parquet")
        for tf in (5, 15, 60):
            if src == "GOLDM_NOV" and tf != 5:
                continue
            B = make_bars(m, tf)
            S = signals(B)
            S = S[S.sig_min + 1 < CUT]
            for strike in ("ATM", "ITM1"):
                for filt in ("ALL", "AM", "PM"):
                    for spn, (a, p) in {"base": (0.008, 0.004), "m4": (0.006, 0.003), "nospread": (0.0, 0.0)}.items():
                        for raw in (False, True):
                            if raw and spn != "nospread":
                                continue
                            for skip in ("skipexp", "allday"):
                                T = simulate(S, bk, filt, strike, a, p, raw=raw, skip_days=exp_days if skip == "skipexp" else ())
                                T["src"], T["tf"], T["strike"], T["filt"], T["spread_case"], T["fill"], T["days"] = src, tf, strike, filt, spn, ("RAW" if raw else "CLEAN"), skip
                                res.append(T)
            print(src, tf, "done", flush=True)
    R = pd.concat(res, ignore_index=True)
    R.to_parquet(W / "trades_all.parquet")
    pd.Series([str(d) for d in days_all]).to_frame("day").to_parquet(W / "opt_days.parquet")
    print("saved", len(R))
