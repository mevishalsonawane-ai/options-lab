"""The Candle agent's seed keeps the pack's numbers exactly and stays small (20 Oct 2026)."""
import json

from research.hunt.r87_candle_anatomy import app_seed as S


def _pack():
    return json.loads(S.PACK.read_text())


def test_seed_keeps_every_family_and_number():
    p = _pack()
    s = S.seed(p)
    assert [f["k"] for f in s["families"]] == [f["family"] for f in p["families"]]
    f = next(x for x in s["families"] if x["k"] == "big_lower_wick")
    src = next(x for x in p["families"] if x["family"] == "big_lower_wick")
    row = next(r for r in f["s"] if r[0] == "NIFTY" and r[1] == 15)
    st = next(x for x in src["stats"] if x["und"] == "NIFTY" and x["tf"] == 15)
    assert row[2] == st["n"] and row[3] == round(st["up_rate_5"], 3) and row[7] == round(st["q"], 3)
    assert s["held"] == 2 and s["findings"] == 17
    assert "MCX" in s["mcx"] and s["r87b"]


def test_held_flag_is_three_way():
    p = {"families": [{"family": "x", "name": "X", "definition": "d", "mechanism_hypothesis": "m", "verdict": "v",
                       "stats": [dict(und="NIFTY", tf=1, n=5, up_rate_5=0.5, base_up_rate_5=0.5, eff_atr_5=0.0, move_pts_5=0.0,
                                      q=1.0, holds_oos=h) for h in (True, False, None)]}],
         "date": "d", "doc": "doc", "design": "a", "holdout": "b", "holdout_check": {"held": 0, "design_findings": 0},
         "design_tests": {"P1": {"tested": 3}}}
    s = S.seed(p)
    assert [r[-1] for r in s["families"][0]["s"]] == [1, 0, -1]
    assert s["families"][0]["s"][0][8] is None          # a missing wick number stays missing, never zero


def test_seed_is_small_and_matches_the_app_asset():
    s = json.dumps(S.seed(_pack()), separators=(",", ":"), ensure_ascii=False) + "\n"
    assert len(s) < 120_000
    if S.OUT.exists():
        assert S.OUT.read_text() == s, "the app asset is stale: run python -m research.hunt.r87_candle_anatomy.app_seed"
