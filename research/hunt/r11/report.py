"""R11: labels (PREREG rules) + the feature table for HUNT_R11_MOVE_ANATOMY.md.

    python3 -I research/hunt/r11/report.py   -> <scratch>/hunt/r11/table.md, labels.csv
Reads tests_design.csv (labels), tests_hold.csv / hits_hold.csv / recall_hold.csv / lags_hold.csv (holdout columns).
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r11"
UNDS = ["BANKNIFTY", "NIFTY"]
NAMES = {
    "absret": "Realised volatility (|1-min return|)", "range30": "30-min range (squeeze/expansion)",
    "ret": "Price drift in the move's direction", "optvol": "Option volume ATM+-10",
    "flow": "Signed option flow (tick rule)", "ce_pe_vol": "CE vs PE volume ATM+-2",
    "oi_build": "OI: put writing minus call writing ATM+-2", "oi_activity": "OI activity ATM+-2 (any change)",
    "prem_spread": "ATM CE minus PE premium move", "iv_chg": "ATM IV change", "basis_chg": "Option-implied forward minus index",
    "vix_chg": "India VIX change", "gamma_conc": "OI concentration near spot (ATM+-2 / ATM+-10)",
    "delta_proxy": "Delta PROXY (return sign x option volume)", "vwap_z": "Distance to VWAP (SD bands, signed)",
    "vwap_absz": "Distance to VWAP (SD bands, absolute)", "hw_vol": "Heavyweights' cash volume",
    "hw_lead": "Heavyweights ahead of the index print", "fut_vol": "Futures volume", "fut_oi_chg": "Futures OI change",
    "fut_oi_abs": "Futures OI activity", "fut_delta": "Futures delta proxy", "fut_basis_chg": "Futures basis change",
    "round_dist": "Distance to a round number", "va_pos": "Above VAH / below VAL (prior day)",
    "poc_dist": "Distance to prior-day POC", "lvn_dist": "Distance to prior-day LVN", "hvn_dist": "Distance to prior-day HVN",
    "ib_pos": "Outside the initial balance", "ib_first_break": "First IB break this minute", "open_type_dir": "Opening type direction",
    "ib_width": "IB width vs 20-day average", "gex_negative": "Dealer gamma negative (prior day, convention A)",
    "flip_dist": "Distance to zero-gamma flip", "above_flip": "Above the zero-gamma flip",
}


def label(T, feat):
    x = T[(T.feat == feat) & (T.ctl == "other_day") & (T.kind.isin(["m1_top1", "m1_top5"]))]
    if x.empty or x.n.max() < 30:
        return "n/a", None, None

    def passes(w, kind):
        r = x[(x.win == w) & (x.kind == kind)]
        if len(r) < 2:
            return False, np.nan
        ok = (r.q <= 0.05).all() and (r.eff.abs() >= 0.10).all() and (np.sign(r.eff).nunique() == 1)
        return bool(ok), float(r.eff.mean())
    best = None
    for kind in ("m1_top1", "m1_top5"):
        for w in ("W15", "W5"):
            ok, e = passes(w, kind)
            if ok:
                ok30, e30 = passes("W30", kind)
                lab = "STATE" if ok30 and abs(e30) >= 0.8 * abs(passes("W5", kind)[1] or e) else "LEADS"
                return lab, kind, w
    for kind in ("m1_top1", "m1_top5"):
        if passes("W1", kind)[0]:
            return "1-MIN LEAD", kind, "W1"
    for kind in ("m1_top1", "m1_top5"):
        if passes("EV", kind)[0]:
            return "COINCIDENT", kind, "EV"
    return "NONE", None, None


def main():
    Td = pd.read_csv(os.path.join(OUT, "tests_design.csv"))
    Th = pd.read_csv(os.path.join(OUT, "tests_hold.csv"))
    Th = Th[Th.hold]
    H = pd.read_csv(os.path.join(OUT, "hits_hold.csv"))
    R = pd.read_csv(os.path.join(OUT, "recall_hold.csv"))
    rows = []
    for f in NAMES:
        lab, kind, w = label(Td, f)

        def eff(T, kind_, w_, ctl="other_day"):
            r = T[(T.feat == f) & (T.ctl == ctl) & (T.kind == kind_) & (T.win == w_)]
            return {u: (float(r[r.und == u].eff.iloc[0]), float(r[r.und == u].p.iloc[0]), int(r[r.und == u].n.iloc[0]))
                    if (r.und == u).any() else (np.nan, np.nan, 0) for u in UNDS}
        k = kind or "m1_top1"
        lead_w = w if lab in ("LEADS", "STATE", "1-MIN LEAD") else "W5"
        d_pre, h_pre = eff(Td, k, lead_w), eff(Th, k, lead_w)
        d_ev, h_ev = eff(Td, "m1_top1", "EV"), eff(Th, "m1_top1", "EV")
        d_sd = eff(Td, k, lead_w, "same_day")
        conf = None
        if lab in ("LEADS", "STATE", "1-MIN LEAD", "COINCIDENT"):
            ww = lead_w if lab != "COINCIDENT" else "EV"
            kk = k
            dd, hh = eff(Td, kk, ww), eff(Th, kk, ww)
            conf = all(np.sign(hh[u][0]) == np.sign(dd[u][0]) and hh[u][1] < 0.05 for u in UNDS)

        def hit(hold, lvl="top1", h=15, quiet=False):
            r = H[(H.feat == f) & (H.hold == hold) & (H.lvl == lvl) & (H.h == h) & (H.quiet == quiet)]
            if r.empty or r.spikes.sum() == 0:
                return np.nan, np.nan, np.nan, 0
            hits_ = (r.hit * r.spikes).sum() / r.spikes.sum()
            base = (r.base * r.spikes).sum() / r.spikes.sum()
            return hits_, base, hits_ / base if base > 0 else np.nan, int(r.spikes.sum())
        rec = R[(R.feat == f) & (R.kind == "m1_top1") & (R.h == 15)]
        rec_d = (rec[~rec.hold].recall * rec[~rec.hold].n).sum() / max(rec[~rec.hold].n.sum(), 1) if len(rec) else np.nan
        rows.append(dict(feat=f, name=NAMES[f], label=lab, kind=kind, win=w,
                         d_pre_BN=d_pre["BANKNIFTY"][0], d_pre_NF=d_pre["NIFTY"][0],
                         sd_pre_BN=d_sd["BANKNIFTY"][0], sd_pre_NF=d_sd["NIFTY"][0],
                         h_pre_BN=h_pre["BANKNIFTY"][0], h_pre_NF=h_pre["NIFTY"][0],
                         h_p_BN=h_pre["BANKNIFTY"][1], h_p_NF=h_pre["NIFTY"][1],
                         d_ev_BN=d_ev["BANKNIFTY"][0], d_ev_NF=d_ev["NIFTY"][0], h_ev_BN=h_ev["BANKNIFTY"][0], h_ev_NF=h_ev["NIFTY"][0],
                         confirmed=conf,
                         hit_d=hit(False)[0], base_d=hit(False)[1], lift_d=hit(False)[2], spikes_d=hit(False)[3],
                         hit_h=hit(True)[0], base_h=hit(True)[1], lift_h=hit(True)[2], spikes_h=hit(True)[3],
                         hitq_d=hit(False, quiet=True)[0], liftq_d=hit(False, quiet=True)[2],
                         hitq_h=hit(True, quiet=True)[0], liftq_h=hit(True, quiet=True)[2],
                         hit5_d=hit(False, "top5", 15)[0], lift5_d=hit(False, "top5", 15)[2],
                         hit5_h=hit(True, "top5", 15)[0], lift5_h=hit(True, "top5", 15)[2],
                         recall_d=rec_d))
    L = pd.DataFrame(rows)
    L.to_csv(os.path.join(OUT, "labels.csv"), index=False)
    f2 = lambda v: "" if pd.isna(v) else f"{v:+.2f}"  # noqa: E731
    pc = lambda v: "" if pd.isna(v) else f"{100 * v:.0f}%"  # noqa: E731
    lines = ["| feature | label (design) | window | before: BN / NF (design) | same-day ctl BN / NF | before: BN / NF (holdout) "
             "| during 1-min top-1%: BN / NF design -> holdout | holdout confirms? | hit 15 min top-1% (design / holdout) "
             "| base | lift (design / holdout) | false alarm (holdout) | lift after a quiet 15 min (design / holdout) |",
             "|" + "---|" * 13]
    for r in L.itertuples():
        lines.append(f"| {r.name} | {r.label} | {r.win or ''} | {f2(r.d_pre_BN)} / {f2(r.d_pre_NF)} | {f2(r.sd_pre_BN)} / {f2(r.sd_pre_NF)} "
                     f"| {f2(r.h_pre_BN)} / {f2(r.h_pre_NF)} | {f2(r.d_ev_BN)} / {f2(r.d_ev_NF)} -> {f2(r.h_ev_BN)} / {f2(r.h_ev_NF)} "
                     f"| {'' if r.confirmed is None else ('yes' if r.confirmed else 'no')} | {pc(r.hit_d)} / {pc(r.hit_h)} | {pc(r.base_h)} "
                     f"| {'' if pd.isna(r.lift_d) else f'{r.lift_d:.2f}'} / {'' if pd.isna(r.lift_h) else f'{r.lift_h:.2f}'} "
                     f"| {pc(1 - r.hit_h) if pd.notna(r.hit_h) else ''} "
                     f"| {'' if pd.isna(r.liftq_d) else f'{r.liftq_d:.2f}'} / {'' if pd.isna(r.liftq_h) else f'{r.liftq_h:.2f}'} |")
    open(os.path.join(OUT, "table.md"), "w").write("\n".join(lines) + "\n")
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 50)
    print(L[["feat", "label", "kind", "win", "d_pre_BN", "d_pre_NF", "sd_pre_BN", "sd_pre_NF", "h_pre_BN", "h_pre_NF", "h_p_BN", "h_p_NF",
             "confirmed"]].round(3).to_string())
    print(L[["feat", "d_ev_BN", "d_ev_NF", "h_ev_BN", "h_ev_NF", "hit_d", "hit_h", "base_h", "lift_d", "lift_h", "spikes_h",
             "liftq_d", "liftq_h", "lift5_d", "lift5_h", "recall_d"]].round(3).to_string())


if __name__ == "__main__":
    main()
