"""R152 second look (PREREG section 4, descriptive, never gated): the rules on the trades R151's app pickers take, Apr - Oct 2026.

    python research/hunt/r152_donchian_2k/r152picker.py          # after r152study.py final; writes results/picker.json

Each candidate is scored with the app's exported model numbers (R151 results/r72_picker_model.json, r51_picker_model.json;
pick.score_from_numbers = the app's R72Picker.score), from R151's features (the look-ahead wall at the decision minute).
As the app does (CLAUDE.md, 6 Nov), a skipped break still holds its side and counts in the day's caps: every candidate is
walked with the rule's prices and the skipped ones are then removed from the record. Parity first: R151's own walk (skipped
breaks removed before the walk) at 1 lot with today's exits must give R151's published picks.
"""
from __future__ import annotations

import json
import sys

import r152cfg as K

sys.path.insert(0, str(K.R151))
_argv = sys.argv
sys.argv = [sys.argv[0], "design"]
import study as T  # noqa: E402  (R151; reads sys.argv at import)

sys.argv = _argv
import r152study as R  # noqa: E402
from research.hunt.r151_donchian_winrate60 import pick as PK  # noqa: E402


def scores(arm: str, us: list) -> tuple:
    """({cand key: score}, threshold) with the app's model numbers."""
    fn, thr, _ = K.PICKER[arm]
    nums = json.loads((K.R151 / "results" / fn).read_text())
    assert abs(nums["threshold"] - thr) < 1e-12 and nums["feats"] == T.FEATS
    fx = T.feats_of_units(us)
    keys = list(fx)
    x = T.matrix(keys, fx)
    return {k: PK.score_from_numbers(nums, row) for k, row in zip(keys, x)}, thr


def kept(t: dict, sc: dict, thr: float) -> bool:
    """The app's rule: taken unless scored below the threshold (a break it cannot score is taken)."""
    s = sc.get(T.cand_key(t["und"], t["day"], t["er_sig"], t["side"]))
    return s is None or not (s < thr)


def run() -> dict:
    assert K.FINAL_FLAG.exists(), "the second look follows R152's one final read"
    M = R.mods()
    M["C"].open_final()
    lo, hi = K.FINAL_START, K.FINAL_END
    sess = R.sessions_in(lo, hi)
    out = {"_meta": dict(period=f"{lo} .. {hi}", sessions=len(sess),
                         note="second look: Apr - Oct 2026 was read many times; the pickers failed their gates")}
    for arm in K.ARMS:
        us = R.units(arm, lo, hi)
        sc, thr = scores(arm, us)
        par = T.kept_walk(arm, us, sc, thr)
        pub = K.PICKER[arm][2]
        n0, net0 = len(par), round(sum(t["net"] for t in par))
        a = {"parity_r151_walk": dict(trades=n0, net=net0, published=list(pub),
                                      reproduced=(n0 == pub[0] and abs(net0 - pub[1]) <= 1)),
             "candidates": sum(len(u[1]) for u in us), "scored_below": sum(1 for v in sc.values() if v < thr)}
        print(arm, "picker parity", a["parity_r151_walk"], flush=True)
        for lots in K.LOTS:
            for name in ("B0",) + K.RULE_IDS:
                spec = K.RULES.get(name, dict(target=None, stop=None))
                tr = [t for t in R.walk(us, R.priced(us, spec["target"], spec["stop"], lots)) if kept(t, sc, thr)]
                a[f"{name}@{lots}"] = dict(R.mods()["S"].stats(tr, len(sess)), mix=R.exit_mix(tr))
                print(arm, "picker", name, lots, len(tr), round(sum(t["net"] for t in tr)), flush=True)
        out[arm] = a
    (K.RESULTS / "picker.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    run()
