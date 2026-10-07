"""h21: the 33 pre-registered variants (PREREG.md) and the position engines (single arm; combined with the app's guard)."""
from __future__ import annotations

import numpy as np
import pandas as pd

import sim

S = sim.Spec
SPECS = {
    "fixed": S("fixed"),
    "atr1": S("atr1", tgt="atr", tgt_atr=4.0, ladder="atr", lad_atr=((1, 0), (2, 1), (3, 2))),
    "atrh": S("atrh", tgt="atr", tgt_atr=2.0, ladder="atr", lad_atr=((0.5, 0), (1, 0.5), (1.5, 1))),
    "after10": S("after10", lock_after=10),
    "after20": S("after20", lock_after=20),
    "trail1": S("trail1", ladder="none", trail=(1.0, 1.0)),
    "trail2nt": S("trail2nt", tgt="none", ladder="none", trail=(1.0, 2.0)),
    "trail15nt": S("trail15nt", tgt="none", ladder="none", trail=(1.0, 1.5)),
}

BREAK = ("orb", "orb_fresh")
PKG = dict(maxday=2, loss2=True, cd=30)


def V(spec="fixed", maxday=None, loss2=False, cd=0, filt=None):
    return dict(spec=spec, maxday=maxday, loss2=loss2, cd=cd, filt=filt)


VARIANTS = {
    "V01_base_fixed": V(),
    "V02_max1": V(maxday=1), "V03_max2": V(maxday=2), "V04_max3": V(maxday=3),
    "V05_2loss_stop": V(loss2=True), "V06_cd30": V(cd=30), "V07_cd60": V(cd=60),
    "V08_PKG": V(**PKG),
    "V10_skip_narrowOR": V(**PKG, filt=("orw_lo",)), "V11_skip_wideOR": V(**PKG, filt=("orw_hi",)),
    "V12_midOR": V(**PKG, filt=("orw_lo", "orw_hi")),
    "V13_s30_aligned": V(**PKG, filt=("s30al",)), "V14_s30_dir": V(**PKG, filt=("s30dir",)),
    "V15_vix_notfalling": V(**PKG, filt=("vixup",)), "V16_vix_aligned": V(**PKG, filt=("vixal",)),
    "V17_gamma_aligned": V(**PKG, filt=("gamal",)), "V18_gamma_skiphigh": V(**PKG, filt=("gamhi",)),
    "V19_daily_trend": V(**PKG, filt=("dird",)), "V20_60m_trend": V(**PKG, filt=("dir60",)),
    "V21_until1130": V(**PKG, filt=("early",)), "V22_after1130": V(**PKG, filt=("late",)),
    "V23_atr_ladder": V("atr1", **PKG), "V24_halfatr_ladder": V("atrh", **PKG),
    "V25_lock_after10": V("after10", **PKG), "V26_lock_after20": V("after20", **PKG),
    "V27_trail1A": V("trail1", **PKG), "V28_trail2A_notgt": V("trail2nt", **PKG), "V29_trail15A_notgt": V("trail15nt", **PKG),
    "V30_PKG_s30al_gam_atr": V("atr1", **PKG, filt=("s30al", "gamal")),
    "V31_PKG_s30dir_dird_trail": V("trail1", **PKG, filt=("s30dir", "dird")),
    "V32_max1_s30al_early": V(maxday=1, filt=("s30al", "early")),
}
# V09 (guard) is a combined-book comparison; counted as a trial in the combined family.
N_VARIANTS = len(VARIANTS) + 1


def filt_mask(f: pd.DataFrame, arm: str, names) -> np.ndarray:
    """True = the candidate may be taken. Missing feature -> passes."""
    ok = np.ones(len(f), bool)
    brk = arm in BREAK
    side = f.side.values
    for n in names or ():
        if n == "orw_lo":
            ok &= ~(f.orw_rk.values < 1 / 3)
        elif n == "orw_hi":
            ok &= ~(f.orw_rk.values > 2 / 3)
        elif n == "s30al":
            r = f.s30_rk.values
            ok &= np.isnan(r) | ((r >= 0.5) if brk else (r < 0.5))
        elif n == "s30dir":
            s = np.sign(f.s30.values)
            ok &= np.isnan(s) | (s == 0) | (s == side)
        elif n == "vixup":
            ok &= ~(f.vchg.values < 0)
        elif n == "vixal":
            v = f.vchg.values
            ok &= np.isnan(v) | ((v >= 0) if brk else (v <= 0))
        elif n == "gamal":
            g = f.gter.values
            ok &= np.isnan(g) | ((g != 2) if brk else (g != 0))
        elif n == "gamhi":
            ok &= ~(f.gter.values == 2)
        elif n == "dird":
            s = f.dird.values
            ok &= np.isnan(s) | (s == 0) | (s == side)
        elif n == "dir60":
            s = f.dir60.values
            ok &= np.isnan(s) | (s == 0) | (s == side)
        elif n == "early":
            ok &= f.sig_min.values <= 11 * 60 + 30
        elif n == "late":
            ok &= f.sig_min.values > 11 * 60 + 30
        else:
            raise KeyError(n)
    return ok


class ArmState:
    def __init__(self, arm, v):
        self.arm = arm
        self.cap = min(sim.ARMS[arm][3], v["maxday"] or 99)
        self.loss2, self.cd = v["loss2"], v["cd"]
        self.reset()

    def reset(self):
        self.block, self.n, self.streak, self.until, self.dead = -1, 0, 0, -1, False

    def can(self, sig_min):
        bar_start = sig_min - 4
        return not (self.dead or self.n >= self.cap or bar_start <= self.block or bar_start < self.until)

    def took(self, exit_min, why, net):
        self.n += 1
        self.block = exit_min - ((exit_min - sim.C.OPEN_M) % 5)
        self.streak = self.streak + 1 if net < 0 else 0
        if self.loss2 and self.streak >= 2:
            self.dead = True
        if self.cd and why == "stop":
            self.until = exit_min + self.cd


def arm_positions(tr: pd.DataFrame, arm: str, v) -> pd.DataFrame:
    """tr: the arm's candidates with outcomes (already filtered), any order."""
    t = tr.sort_values(["day", "sig_min", "cand"], kind="stable")
    keep = np.zeros(len(t), bool)
    st = ArmState(arm, v)
    cur = None
    for i, (d, sm, xm, why, net) in enumerate(zip(t.day.values, t.sig_min.values, t.exit_min.values, t.why.values, t.net.values)):
        if d != cur:
            cur = d
            st.reset()
        if st.can(sm):
            keep[i] = True
            st.took(xm, why, net)
    return t[keep]


def combined_positions(trs: dict, v, guard=True, liq=None):
    """trs: {arm: candidates with outcomes (filtered)}; liq: Liquidity BN trades (fixed) in the guard.
    The app's AutoExposure: at most one automatic BN position at a time when guard (no opposite, max one per side).
    Returns (kept old-arm trades, kept liq trades)."""
    parts = []
    for j, arm in enumerate(sim.ARM_ORDER):
        t = trs[arm]
        parts.append(pd.DataFrame(dict(day=t.day.values, sig_min=t.sig_min.values, pri=j, entry_min=t.entry_min.values,
                                       exit_min=t.exit_min.values, side=t.side.values, why=t.why.values,
                                       net=t.net.values, idx=t.index.values, src=arm)))
    if liq is not None and len(liq):
        parts.append(pd.DataFrame(dict(day=liq.day.values, sig_min=liq.entry_min.values - 1, pri=-1,
                                       entry_min=liq.entry_min.values, exit_min=liq.exit_min.values, side=liq.side.values,
                                       why=liq.why.values, net=liq.net.values, idx=liq.index.values, src="liq")))
    ev = pd.concat(parts, ignore_index=True).sort_values(["day", "sig_min", "pri"], kind="stable")
    states = {a: ArmState(a, v) for a in sim.ARM_ORDER}
    keep = np.zeros(len(ev), bool)
    held = []          # (exit_min, side)
    cur = None
    E = ev.to_records(index=False)
    for i, r in enumerate(E):
        if r.day != cur:
            cur = r.day
            for s in states.values():
                s.reset()
            held = []
        if r.src != "liq" and not states[r.src].can(r.sig_min):
            continue
        if guard:
            held = [h for h in held if h[0] > r.entry_min]
            if held:
                continue
        keep[i] = True
        held.append((r.exit_min, r.side))
        if r.src != "liq":
            states[r.src].took(r.exit_min, r.why, r.net)
    k = ev[keep]
    out = {a: trs[a].loc[k[k.src == a].idx.values] for a in sim.ARM_ORDER}
    lk = liq.loc[k[k.src == "liq"].idx.values] if liq is not None and len(liq) else None
    return out, lk
