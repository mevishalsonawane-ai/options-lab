"""R4 report tests (26 Oct 2026): the statistics, the paper reader, the page builder and a look-ahead guard.
Pure: no lake, no network, no real paper data (hand-made trades and a hand-made diagnostics file)."""
from __future__ import annotations

import gzip
import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))

import r4backtest as BT  # noqa: E402
import r4page as PG  # noqa: E402
import r4paper as PP  # noqa: E402
import r4stats as R  # noqa: E402

D1, D2, D3 = date(2026, 4, 1), date(2026, 4, 2), date(2026, 4, 3)


def tr(day, net, **k):
    t = dict(day=day, net=net, und="NIFTY", side="C", er=10, strike=100.0)
    t.update(k)
    return t


# ------------------------------------------------------------------------------------------------ stats
def test_stats_empty_period():
    s = R.stats([], [D1, D2])
    assert s["trades"] == 0 and s["net"] == 0 and s["win"] is None and s["green_days"] is None
    assert s["worst_day"] == 0 and s["max_dd"] == 0 and s["small"]


def test_stats_counts_days_without_trades_and_outside_trades():
    trades = [tr(D1, 1000), tr(D1, -400), tr(D3, -2000), tr(date(2026, 3, 31), 99999)]   # last one outside the period
    s = R.stats(trades, [D1, D2, D3])
    assert s["trades"] == 3 and s["net"] == -1400
    assert s["per_day"] == round(-1400 / 3)
    assert s["days_traded"] == 2 and s["green_days"] == 0.5
    assert s["worst_day"] == -2000 and s["worst_trade"] == -2000
    assert s["avg_win"] == 1000 and s["avg_loss"] == -1200
    assert s["max_dd"] == 2000                                   # +600, +600, -1400: fell 2000 from its best


def test_max_drawdown_from_zero_and_gaps():
    assert R.max_drawdown([]) == 0
    assert R.max_drawdown([-500, 0, 0]) == 500                    # a loss from the start counts
    assert R.max_drawdown([100, -50, 200, -300, 10]) == 300


def test_big_cut_floor_rupees():
    big_sold_low = tr(D1, 100, peak=0.8, ret=0.05, E=100.0, qty=65)
    big_sold_high = tr(D1, 3000, peak=0.9, ret=0.5, E=100.0, qty=65)
    small = tr(D1, -500, peak=0.2, ret=-0.15, E=100.0, qty=65)
    nan_peak = tr(D1, 0, peak=float("nan"), ret=0.0, E=100.0, qty=65)
    b = R.big_cut([big_sold_low, big_sold_high, small, nan_peak])
    assert b == dict(big=2, cut=1, left=round((0.30 - 0.05) * 100 * 65))


def test_paired_counts_ran_and_cut_on_the_same_signal():
    before = [tr(D1, 3000, er=10), tr(D1, 500, er=20), tr(D2, 2500, er=10), tr(D3, 100, er=30)]
    after = [tr(D1, 1000, er=10), tr(D1, 4000, er=20), tr(D2, 2500, er=10), tr(D3, 50, er=40)]
    p = R.paired(before, after)
    assert p["both"] == 3 and p["only_before"] == 1 and p["only_after"] == 1
    assert (p["cut"], p["cut_rs"]) == (1, -2000) and (p["ran"], p["ran_rs"]) == (1, 3500)
    assert p["same_signals_change"] == 1500


def test_month_breaches():
    trades = [tr(date(2026, 4, 1), -13000), tr(date(2026, 5, 4), -12999), tr(date(2026, 6, 1), -20000),
              tr(date(2026, 6, 2), 8000)]
    sess = [date(2026, 4, 1), date(2026, 5, 4), date(2026, 6, 1), date(2026, 6, 2)]
    assert R.month_breaches(trades, sess) == 1                     # April only (at the limit); June nets -12,000


def test_percentile_is_seeded_and_ordered():
    pool = list(range(-100, 101))
    a = R.percentile_of(-500, pool, 10)
    assert a == R.percentile_of(-500, pool, 10)
    assert R.percentile_of(-900, pool, 10)["pct"] <= a["pct"] <= R.percentile_of(0, pool, 10)["pct"]
    assert R.percentile_of(0, [], 5)["pct"] is None and R.percentile_of(0, pool, 0)["pct"] is None


def test_reading_words():
    assert R.reading(None) == "no sample"
    assert R.reading(1).startswith("outside") and R.reading(5).startswith("low end")
    assert R.reading(50).startswith("inside") and R.reading(99).startswith("above")


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_no_twin_never_looks_ahead():
    """A Liquidity entry is kept or made a twin only from trades that entered before it: changing or adding any later
    trade never changes an earlier decision."""
    base = [dict(day=D1, und="BANKNIFTY", side="P", strike=55000.0, er=e, xr=x, book=b, net=n)
            for e, x, b, n in ((10, 30, 15, -100), (20, 25, 5, 50), (40, 60, 5, 70), (45, 50, 15, -20))]
    kept, twins = BT.no_twin(base)
    assert [t["er"] for t in twins] == [20, 45]
    for cut in range(1, len(base)):
        later = [dict(t, xr=t["xr"] + 100, strike=t["strike"]) for t in base[cut:]] + \
                [dict(base[0], er=99, xr=120, book=5)]
        k2, w2 = BT.no_twin(base[:cut] + later)
        early = lambda xs: [(t["er"], t["book"]) for t in xs if t["er"] < base[cut]["er"]]  # noqa: E731
        assert early(k2) == early(kept) and early(w2) == early(twins)


def test_no_twin_tie_keeps_the_longer_book():
    a = dict(day=D1, und="BANKNIFTY", side="P", strike=55000.0, er=10, xr=30, book=5, net=-100)
    b = dict(a, book=15, net=200)
    kept, twins = BT.no_twin([a, b])
    assert [t["book"] for t in kept] == [15] and [t["book"] for t in twins] == [5]


def test_no_twin_other_option_or_day_is_free():
    a = dict(day=D1, und="FINNIFTY", side="C", strike=24400.0, er=10, xr=30, book=30)
    assert len(BT.no_twin([a, dict(a, strike=24450.0, book=5)])[0]) == 2
    assert len(BT.no_twin([a, dict(a, side="P", book=5)])[0]) == 2
    assert len(BT.no_twin([a, dict(a, day=D2, book=5)])[0]) == 2
    assert len(BT.no_twin([a, dict(a, er=31, book=5)])[0]) == 2           # the first one closed at 30


# ------------------------------------------------------------------------------------------------ paper reader
def order(at, src, sym, side, avg, qty=65, status="complete", filled=None, account="paper", typ="MARKET"):
    return dict(at=at, account=account, source=src, symbol=sym, side=side, qty=qty,
                filled=qty if filled is None else filled, status=status, type=typ, avg=avg, message="")


def test_pair_orders_matches_legs_and_skips_noise():
    o = [order("09:20:03", "R72 Breadth Donchian", "NIFTY27OCT2622500PE", "BUY", 137.8),
         order("09:20:03", "Liquidity 15+5", "MIDCPNIFTY27OCT2613250PE", "SELL", 0.0, status="cancelled", filled=0, typ="SL-M"),
         order("09:30:32", "R72 Breadth Donchian", "NIFTY27OCT2622500PE", "SELL", 116.3),
         order("09:05:00", "US-night silver", "SILVERMIC30NOV26FUT", "SELL", 198912.0, qty=1),
         order("10:00:00", "R51 Donchian 20", "NIFTY27OCT2622600CE", "BUY", 200.0),       # never sold: left out
         order("10:01:00", "Manual", "BANKNIFTY27OCT2654000CE", "SELL", 300.0, qty=30)]     # a sell with no buy
    trips = PP.pair_orders(o)
    assert len(trips) == 1
    t = trips[0]
    assert t["arm"] == "R72" and t["qty"] == 65 and t["gross"] == round((116.3 - 137.8) * 65, 2)
    assert t["net_est"] == round(t["gross"] - PP.CHARGES_PER_TRADE, 2) and t["pct"] == -15.6


def test_pair_orders_first_in_first_out_per_source():
    o = [order("10:00", "R72 Breadth Donchian", "X26CE", "BUY", 100), order("10:01", "R51 Donchian 20", "X26CE", "BUY", 101),
         order("10:05", "R72 Breadth Donchian", "X26CE", "SELL", 110), order("10:06", "R51 Donchian 20", "X26CE", "SELL", 90)]
    t = {x["arm"]: x["gross"] for x in PP.pair_orders(o)}
    assert t == {"R72": 650.0, "R51": -715.0}


def test_counters_read_the_arm_lines():
    line = ("R72 Breadth Donchian: on (paper only) · open 0 · 32 closed (net hidden) after charges · of them 17 would-be "
            "(net hidden) · Forward test: 15 of 40 paper trades")
    c = PP.counters(dict(other_arms=[line, "Solo 2.0: on (paper only) · open 0 · 2 closed (net hidden)", "Watchlist: x"]))
    assert c["R72"] == dict(state="on (paper only)", closed=32, would_be=17, forward=15)
    assert c["SOLO"]["would_be"] == 0 and c["SOLO"]["forward"] is None and "Watchlist" not in str(c)


def diag(day, **k):
    d = dict(schema=2, kind="iraalgo-diagnostics", day=day, mode=dict(live=False), arms=[], other_arms=[], decisions=[],
             orders=[], health={}, timeline=[])
    d.update(k)
    return d


def test_summarise_and_load_folder(tmp_path):
    liq = dict(key="liquidity", trades=[dict(account="paper", right="CE", entry_at="10:00:00", exit_at="10:02:00",
                                             why="index_stop", points=10.95, pct=4.86, open=False),
                                        dict(account="paper", right="CE", entry_at="10:10:00", exit_at="", why="",
                                             points=0, pct=0, open=True)],
               all_days=dict(trades=37, since="2026-10-01", wins=12, sum_pct=-76.02, avg_pct=-2.05))
    d1 = diag("2026-10-26", arms=[liq], decisions=[dict(kind="order_refused", verdict="order_refused: same-direction cap: 2 puts")],
              health=dict(errors={"stop check: the stops were looked at # s or more apart": 19}, stream=dict(drops=38)))
    d0 = diag("2026-10-24")                                          # a weekend file: no decisions, not a session
    (tmp_path / "a.json.gz").write_bytes(gzip.compress(json.dumps(d1).encode()))
    (tmp_path / "b.json").write_text(json.dumps(d0))
    (tmp_path / "c.txt").write_text("not data")
    (tmp_path / "d.json").write_text(json.dumps({"kind": "something else"}))
    days = PP.load_folder(tmp_path)
    assert [d["day"] for d in days] == ["2026-10-24", "2026-10-26"]
    s = PP.summarise(days)
    assert s["sessions"] == ["2026-10-26"] and len(s["liquidity"]["trades"]) == 1
    assert s["liquidity"]["all_days"]["trades"] == 37 and s["refusals"] == [dict(day="2026-10-26", cap=1)]
    assert s["health"]["2026-10-26"]["slow_stop_checks"] == 19 and s["health"]["2026-10-26"]["stream_drops"] == 38
    assert s["health"]["2026-10-26"]["daily_loss_stop"] == 0


def test_daily_loss_stop_read_from_errors_or_warnings():
    old = diag("2026-10-19", decisions=[{}], health=dict(errors={"daily loss limit reached: today's paper p&l": 1}))
    new = diag("2026-10-26", decisions=[{}], health=dict(warnings={"daily loss limit reached: today's paper nse": 1}))
    h = PP.summarise([old, new])["health"]
    assert h["2026-10-19"]["daily_loss_stop"] == 1 and h["2026-10-26"]["daily_loss_stop"] == 1


def test_paper_and_page_refuse_the_repository(tmp_path):
    (tmp_path / "x.json").write_text(json.dumps(diag("2026-10-26")))
    with pytest.raises(SystemExit):
        PP.main(["r4paper", str(tmp_path), str(PP.REPO / "research" / "paper.json")])
    with pytest.raises(SystemExit):
        PG.main(["r4page", "p.json", "q.json", str(PG.REPO / "page.html")])


# ------------------------------------------------------------------------------------------------ page
def test_inr_indian_grouping():
    assert PG.inr(171366) == "Rs 1,71,366" and PG.inr(-1128786) == "-Rs 11,28,786"
    assert PG.inr(999) == "Rs 999" and PG.inr(0) == "Rs 0" and PG.inr(1500, True) == "+Rs 1,500"
    assert PG.inr(None) == "-" and PG.num(-22571) == "-22,571" and PG.num(40786) == "40,786"


def _stats(net):
    return dict(trades=10, sessions=3, days_traded=2, net=net, per_day=net // 3, green_days=0.5, win=0.4, avg_win=900,
                avg_loss=-500, worst_day=-800, worst_trade=-600, max_dd=900, small=True,
                big_cut=dict(big=2, cut=1, left=300))


def test_page_builds_from_hand_made_inputs():
    rows = {k: dict(before=_stats(100), after=_stats(200),
                    paired=dict(both=9, ran=1, ran_rs=50, cut=1, cut_rs=-20, only_before=1, only_after=0,
                                only_before_net=5, only_after_net=0)) for k in ("LIQ", "R72", "R51", "R52")}
    rows["LIQ"]["after_notwin"] = _stats(150)
    port = dict(before=dict(_stats(1000), bad_months=2), after_nocap=dict(_stats(800), bad_months=1),
                after=dict(_stats(300), bad_months=2, cap_refused_big=dict(big=4, refused=2, rupees=7000)),
                after_alone=_stats(1200))
    bt = dict(generated="now", period=dict(sessions=3, months=1), rows=dict(rows, PORT=port))
    pools = dict(pools={k: list(np.linspace(-1000, 1500, 50)) for k in ("LIQ", "R72", "R51", "R52", "PORT")},
                 pools_ret={"LIQ": list(np.linspace(-0.15, 0.4, 50))})
    paper = dict(rebuilt={"R72": dict(trips=2, net_est=-300, wins=1, days=["2026-10-26"])},
                 counters={"R72": dict(closed=5, would_be=2)},
                 liquidity=dict(all_days=dict(trades=4, since="2026-10-01", wins=1, sum_pct=-20.0, avg_pct=-5.0)),
                 trips=[dict(day="2026-10-26", arm="R72", net_est=-300.0)], health={"2026-10-26": dict(
                     slow_stop_checks=1, unprotected=0, stream_drops=2, daily_loss_stop=1)},
                 sessions=["2026-10-26"], files=["2026-10-26"])
    h = PG.page(bt, pools, paper)
    assert h.startswith("<title>IraAlgo Before and After</title>")
    assert "Rs 300" in h and "small sample" in h and "{" not in h.split("</style>")[1]
    assert "Boss decides" in h
