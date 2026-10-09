"""R3: summarise option-level trades (trades_all.parquet)."""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/r3")
from r3lib import *
W = R3 / "work"
R = pd.read_parquet(W / "trades_all.parquet")
odays = [dt.date.fromisoformat(x) for x in pd.read_parquet(W / "opt_days.parquet").day]
odays = [d for d in odays if d.weekday() < 5]
BLOCKS = {"Aug-Sep25": (dt.date(2025, 8, 1), dt.date(2025, 9, 30)), "Oct25-Oct26": (dt.date(2025, 10, 1), dt.date(2026, 10, 8)),
          "RptWin": (dt.date(2026, 9, 24), dt.date(2026, 10, 8)), "May-Oct26": (dt.date(2026, 5, 7), dt.date(2026, 10, 8))}


def summ(T, blk, col="net"):
    a, b = BLOCKS[blk]
    nd = sum(1 for d in odays if a <= d <= b)
    t = T[(T.status == "ok") & (T.day >= a) & (T.day <= b)].sort_values(["day", "t_in"])
    if not len(t):
        return dict(n=0)
    eq = t[col].cumsum().values
    dd = (eq - np.maximum.accumulate(np.r_[0, eq])[1:]).min()
    mo = t.groupby([d.strftime("%Y-%m") for d in t.day])[col].sum()
    return dict(n=len(t), win=100 * (t[col] > 0).mean(), per_trade=t[col].mean(), total=t[col].sum(), per_day=t[col].sum() / nd,
                maxdd=dd, green_months=f"{(mo > 0).sum()}/{len(mo)}", nofill=int((T[(T.day >= a) & (T.day <= b)].status == "nofill").sum()),
                gross_pt=t.gross.mean(), cost_pt=(t.charges + t.spread).mean(), prem=t.prem_lot.median(),
                ci=boot_ci(t[col].values, t.day.values))


if __name__ == "__main__":
    key = ["src", "tf", "strike", "spread_case", "fill", "days"]
    for blk in BLOCKS:
        print(f"\n##### block {blk}")
        for k, g in R.groupby(key):
            if k[3] not in ("base",) and not (k[4] == "RAW"):
                continue
            if k[0] == "GOLDM_NOV" and blk not in ("RptWin", "May-Oct26"):
                continue
            line = []
            for f in ("ALL", "AM", "PM"):
                s = summ(g[g.filt == f], blk)
                if s["n"] == 0:
                    line.append(f"{f}: n=0"); continue
                line.append(f"{f}: n={s['n']} win {s['win']:.0f}% {s['per_trade']:+.0f}/tr [{s['ci'][1]:+.0f},{s['ci'][2]:+.0f}] {s['per_day']:+.0f}/day tot {s['total']:+.0f} dd {s['maxdd']:+.0f} gm {s['green_months']} gross {s['gross_pt']:+.0f} cost {s['cost_pt']:.0f}")
            print(" ", "/".join(map(str, k)))
            for x in line:
                print("     ", x)
