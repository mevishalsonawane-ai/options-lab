"""R103 deep forward test (PREREG_FWD.md): the NIFTY expiry-day strangle with a combined stop and a profit lock, the
90-variant enhancement search on history, the mechanical choice of finalists, and the forward scorer.

    python3 -I research/hunt/r103_selling/r103fwd.py search    # history only: results/fwd_search.csv, fwd_rules.json
    python3 -I research/hunt/r103_selling/r103fwd.py score     # forward days only (from 20 Oct 2026): results/fwd_score.csv

Nothing here trades. The scorer refuses every day before FORWARD_START.
"""
from __future__ import annotations

import itertools
import json
import sys
from dataclasses import asdict, dataclass
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r103data as R  # noqa: E402
import r103sim as S  # noqa: E402

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
DESIGN_A = (date(2024, 11, 20), date(2026, 7, 14))
DESIGN_B = (date(2021, 8, 5), date(2024, 11, 19))
FORWARD_START = date(2026, 10, 20)
WIDTHS = (1.0, 1.25, 1.5)
ENTRIES = {"1015": R.minute(10, 15), "1100": R.minute(11, 0), "1200": R.minute(12, 0)}
EXITS = {"1505": R.minute(15, 5), "1430": R.minute(14, 30)}
FILTERS = ("none", "GAP", "RANGE", "VIX", "ALL")
STOP, LOCK = "X3", (0.3, 0.2)
MAX_W = 10                       # the lake's ATM+-10 window
GAP_PCT, VIX_UP = 0.006, 0.05
MIN_TRADES = {"A": 40, "B": 60}
MIN_WIN, MONTH_STOP = 0.70, -13000.0
FWD_MIN_EXPIRIES, FWD_WIN, FWD_P, FWD_Q = 40, 0.65, 0.05, 0.10


@dataclass(frozen=True)
class Rule:
    width: float = 1.0
    entry: str = "1015"
    exit: str = "1505"
    filt: str = "none"

    @property
    def name(self) -> str:
        return f"w{self.width:g}-{self.entry}-{self.exit}-{self.filt}"


BASE = Rule()


def skip(day: R.Day, t: int, filt: str) -> bool:
    """PREREG_FWD section 2: the skip filters, read only from index / VIX bars up to and including row t."""
    if filt == "none":
        return False
    ix = day.ix[:t + 1].astype(np.float64)
    checks = []
    if filt in ("GAP", "ALL"):
        o = ix[0, 0]
        checks.append(bool(np.isfinite(o) and np.isfinite(day.prev_close) and abs(o / day.prev_close - 1) > GAP_PCT))
    if filt in ("RANGE", "ALL"):
        legs_em = None
        step = R.STEP[day.und]
        s = S.spot_at(day, t)
        if np.isfinite(s):
            k0 = round(s / step) * step
            c, p = day.opts.get((k0, "C")), day.opts.get((k0, "P"))
            if c is not None and p is not None:
                legs_em = S.ffill(c[:, R.C])[t] + S.ffill(p[:, R.C])[t]
        rng = np.nanmax(ix[:, 1]) - np.nanmin(ix[:, 2]) if np.isfinite(ix[:, 1]).any() else np.nan
        checks.append(bool(legs_em is not None and np.isfinite(legs_em) and np.isfinite(rng) and rng > legs_em))
    if filt in ("VIX", "ALL"):
        v = S.ffill(day.vix[:t + 1], 5)[-1] if day.vix is not None else np.nan
        checks.append(bool(np.isfinite(v) and np.isfinite(day.vix_prev) and v > day.vix_prev * (1 + VIX_UP)))
    return any(checks)


def trade(day: R.Day, rule: Rule) -> S.Trade | None:
    t = ENTRIES[rule.entry]
    if skip(day, t, rule.filt):
        return None
    legs = S.structure(day, t, "STG", width_mult=rule.width, max_w=MAX_W)
    if legs is None:
        return None
    return S.simulate(day, t, "STG", STOP, "T0", legs=legs, lock=LOCK, close_row=EXITS[rule.exit])


def expiry_days(days: list[R.Day], frm: date, to: date, blackout: set) -> list[R.Day]:
    return [d for d in days if frm <= d.day <= to and d.expiry and d.day not in blackout]


def summary(trades: list[S.Trade]) -> dict:
    if not trades:
        return dict(trades=0, win_rate=0.0, net=0.0, per_trade=0.0, avg_win=0.0, avg_loss=0.0, worst_month=0.0, max_dd=0.0)
    st = S.stats(trades)
    return dict(trades=st["trades"], win_rate=st["win_rate"], net=st["net"], per_trade=st["avg"], avg_win=st["avg_win"],
                avg_loss=st["avg_loss"], worst_month=st["worst_month"], max_dd=st["max_dd"], worst_day=st["worst_day"])


def all_rules() -> list[Rule]:
    return [Rule(w, e, x, f) for w, e, x, f in itertools.product(WIDTHS, ENTRIES, EXITS, FILTERS)]


def select(df: pd.DataFrame) -> list[str]:
    """PREREG_FWD section 2: the mechanical choice of up to 3 finalists other than the base."""
    ok = df[(df.trades_A >= MIN_TRADES["A"]) & (df.trades_B >= MIN_TRADES["B"]) & (df.win_rate_A >= MIN_WIN)
            & (df.win_rate_B >= MIN_WIN) & (df.net_A > 0) & (df.net_B > 0) & (df.worst_month_A >= MONTH_STOP)
            & (df.worst_month_B >= MONTH_STOP) & (df.rule != BASE.name)].copy()
    ok["min_win"] = ok[["win_rate_A", "win_rate_B"]].min(axis=1)
    ok["min_pt"] = ok[["per_trade_A", "per_trade_B"]].min(axis=1)
    return list(ok.sort_values(["min_win", "min_pt"], ascending=False).rule.head(3))


def search() -> None:
    blackout = S.events(HERE / "events.csv")
    days = [d for d in R.load("NIFTY") if d.day <= DESIGN_A[1]]
    sets = {"A": expiry_days(days, *DESIGN_A, blackout), "B": expiry_days(days, *DESIGN_B, blackout)}
    rows = []
    for rule in all_rules():
        row = dict(rule=rule.name, **asdict(rule))
        for w, ds in sets.items():
            trs = [x for x in (trade(d, rule) for d in ds) if x is not None]
            for k, v in summary(trs).items():
                row[f"{k}_{w}"] = v
        rows.append(row)
    df = pd.DataFrame(rows)
    RESULTS.mkdir(exist_ok=True)
    df.to_csv(RESULTS / "fwd_search.csv", index=False)
    fin = select(df)
    rules = [BASE.name] + fin
    (RESULTS / "fwd_rules.json").write_text(json.dumps(
        {"rules": rules, "base": BASE.name, "finalists": fin, "selected_on": str(date.today()),
         "forward_start": str(FORWARD_START)}, indent=1), encoding="utf-8")
    print("expiries", {w: len(v) for w, v in sets.items()}, "finalists", fin)


def parse(name: str) -> Rule:
    w, e, x, f = name.split("-")
    return Rule(float(w[1:]), e, x, f)


def binom_p(k: int, n: int) -> float:
    """One-sided P(X >= k) for X ~ Binomial(n, 0.5)."""
    from math import comb
    return float(sum(comb(n, i) for i in range(k, n + 1)) / 2 ** n) if n else 1.0


def score(days: list[R.Day] | None = None, rules: list[str] | None = None) -> pd.DataFrame:
    """PREREG_FWD section 3. Only expiry days on or after FORWARD_START are read."""
    blackout = S.events(HERE / "events.csv")
    rules = rules or json.loads((RESULTS / "fwd_rules.json").read_text(encoding="utf-8"))["rules"]
    days = R.load("NIFTY") if days is None else days
    fwd = [d for d in days if d.day >= FORWARD_START and d.expiry and d.day not in blackout]
    rows = []
    for name in rules:
        trs = [x for x in (trade(d, parse(name)) for d in fwd) if x is not None]
        s = summary(trs)
        wins = int(round(s["win_rate"] * s["trades"]))
        rows.append(dict(rule=name, **s, p_win=binom_p(wins, s["trades"])))
    out = pd.DataFrame(rows)
    out["q_win"] = S.bh(out.p_win.values) if len(out) else []
    out["decision_due"] = out.trades >= FWD_MIN_EXPIRIES
    out["passes"] = (out.decision_due & (out.win_rate >= FWD_WIN) & (out.p_win <= FWD_P) & (out.q_win <= FWD_Q)
                     & (out.net > 0) & (out.worst_month >= MONTH_STOP) & (out.max_dd >= -40000.0)
                     & (out.avg_loss.abs() <= 3 * out.avg_win.clip(lower=1e-9)))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1:2]
    if cmd == ["search"]:
        search()
    elif cmd == ["score"]:
        out = score()
        RESULTS.mkdir(exist_ok=True)
        out.to_csv(RESULTS / "fwd_score.csv", index=False)
        print(out.to_string())
    else:
        print(__doc__)
