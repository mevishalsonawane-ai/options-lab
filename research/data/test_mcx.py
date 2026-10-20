"""MCX loader, session rule, continuous futures (roll without look-ahead) and the futures-contract registry
(19 Oct 2026)."""
import datetime as dt
import json
import os
import sys

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lake_fetch as lf
import mcx

D = dt.date


def bars(symbol, expiry, sid, day, vol, close, start="09:00", n=3):
    t0 = pd.Timestamp(f"{day} {start}", tz=mcx.IST)
    ts = [t0 + pd.Timedelta(minutes=i) for i in range(n)]
    c = np.full(n, float(close))
    return pd.DataFrame(dict(symbol=symbol, expiry=pd.Timestamp(expiry).date(), security_id=sid, ts=ts, open=c,
                             high=c + 1, low=c - 1, close=c, volume=np.full(n, vol // n), oi=np.full(n, 10)))


def fut_two_contracts():
    """Oct contract (expires 19 Oct) busy until 14 Oct; Nov gets more volume on 15 Oct; holiday-free week."""
    rows = []
    for day, vo, vn in [("2026-10-13", 900, 30), ("2026-10-14", 900, 60), ("2026-10-15", 300, 600),
                        ("2026-10-16", 30, 900), ("2026-10-19", 3, 900), ("2026-10-20", 0, 900)]:
        if day <= "2026-10-19":
            rows.append(bars("CRUDEOIL", "2026-10-19", 1, day, vo, 100))
        rows.append(bars("CRUDEOIL", "2026-11-19", 2, day, vn, 103))
    return pd.concat(rows, ignore_index=True)


def test_session_close_follows_us_clock_change():
    assert mcx.close_min(D(2026, 10, 30)) == 23 * 60 + 30       # Friday before the US change (1 Nov)
    assert mcx.close_min(D(2026, 11, 2)) == 23 * 60 + 55        # first Monday after: winter close
    assert mcx.close_min(D(2027, 3, 8)) == 23 * 60 + 55         # second Sunday of March 2027 is 14 Mar
    assert mcx.close_min(D(2027, 3, 12)) == 23 * 60 + 55
    assert mcx.close_min(D(2027, 3, 15)) == 23 * 60 + 30


def test_in_session_edges_and_weekend():
    ts = pd.Series(pd.to_datetime(["2026-10-19 08:59", "2026-10-19 09:00", "2026-10-19 23:29", "2026-10-19 23:30",
                                   "2026-11-02 23:54", "2026-11-02 23:55", "2026-10-17 12:00"]).tz_localize(mcx.IST))
    assert mcx.in_session(ts).tolist() == [False, True, True, False, True, False, False]


def test_volume_roll_uses_previous_day_only():
    m, r = mcx.continuous(fut_two_contracts(), "CRUDEOIL")
    held = m.groupby(m.ts.dt.date).expiry.first()
    # 15 Oct: Nov traded more ON 15 Oct, but the choice uses 14 Oct volume (Oct) -> still Oct; switch on 16 Oct
    assert held[D(2026, 10, 15)] == D(2026, 10, 19)
    assert held[D(2026, 10, 16)] == D(2026, 11, 19)
    assert held[D(2026, 10, 19)] == D(2026, 11, 19)              # Oct traded less on 16 Oct: stays on Nov
    assert len(r) == 1 and r.day.iloc[0] == D(2026, 10, 16)
    assert r.gap.iloc[0] == pytest.approx(3.0) and r.from_security_id.iloc[0] == 1 and r.to_security_id.iloc[0] == 2
    assert m.roll.sum() == 1 and m.loc[m.roll, "ts"].iloc[0].date() == D(2026, 10, 16)


def test_expiry_rule_holds_nearest_until_expiry_day():
    m, r = mcx.continuous(fut_two_contracts(), "CRUDEOIL", rule="expiry")
    held = m.groupby(m.ts.dt.date).expiry.first()
    assert held[D(2026, 10, 19)] == D(2026, 10, 19)              # expiry day itself is still the old contract
    assert held[D(2026, 10, 20)] == D(2026, 11, 19)
    assert list(r.day) == [D(2026, 10, 20)]


def test_back_adjust_removes_the_roll_jump_and_keeps_newest_prices():
    m, r = mcx.continuous(fut_two_contracts(), "CRUDEOIL")
    a = mcx.back_adjust(m, r)
    assert a.close.iloc[-1] == m.close.iloc[-1] == 103
    assert (a.close == 103).all()                                # old 100 + gap 3 = 103: no jump left


def test_roll_with_no_shared_minute_has_unknown_gap_and_adjusts_nothing():
    f = pd.concat([bars("GOLD", "2026-10-05", 1, "2026-10-01", 900, 100, n=2),
                   bars("GOLD", "2026-12-04", 2, "2026-10-01", 30, 110, start="10:00", n=2),
                   bars("GOLD", "2026-12-04", 2, "2026-10-06", 900, 111, n=2)], ignore_index=True)
    m, r = mcx.continuous(f, "GOLD")
    assert len(r) == 1 and np.isnan(r.gap.iloc[0])
    assert (mcx.back_adjust(m, r).close.to_numpy() == m.close.to_numpy()).all()


def test_empty_and_unknown_symbol():
    m, r = mcx.continuous(fut_two_contracts(), "ZINC")
    assert len(m) == 0 and len(r) == 0
    assert len(mcx.contracts(fut_two_contracts().iloc[:0])) == 0


def test_contracts_table():
    c = mcx.contracts(fut_two_contracts())
    oct_ = c[c.expiry == D(2026, 10, 19)].iloc[0]
    assert oct_.minutes == 15 and oct_.traded_minutes == 15      # 5 days x 3 bars; 19 Oct volume 3//3 = 1 > 0
    assert str(oct_["last"].date()) == "2026-10-19"


def test_build_and_load_roundtrip(tmp_path):
    f = fut_two_contracts()
    late = bars("CRUDEOIL", "2026-11-19", 2, "2026-10-20", 5, 103, start="23:30", n=2)  # after the 23:30 close
    f = pd.concat([f, late], ignore_index=True)
    os.makedirs(tmp_path / "fut_1m_live_mcx")
    f.assign(expiry=f.expiry.astype(str)).to_parquet(tmp_path / "fut_1m_live_mcx" / "fut_1m_live_mcx.parquet")
    pd.DataFrame(dict(symbol="CRUDEOIL", expiry_code=0, date=pd.to_datetime(["2026-10-13"]), volume=[930.0])
                 ).to_parquet(tmp_path / "fut_daily_continuous.parquet")
    st = mcx.build(str(tmp_path))
    assert st["CRUDEOIL"]["rolls"] == 1 and st["CRUDEOIL"]["near_month_from"] == "2026-10-13"
    qa = json.load(open(tmp_path / "_qa" / f"{mcx.CONT_FILE}.json"))
    assert qa["qa"]["rows_dropped"]["outside_mcx_session"] == 2
    m, r = mcx.load_continuous(str(tmp_path), "CRUDEOIL")
    m2, r2 = mcx.load_continuous(str(tmp_path), "CRUDEOIL", rule="expiry")
    assert len(r) == 1 and r.day.iloc[0] == D(2026, 10, 16) and m.expiry.iloc[0] == D(2026, 10, 19)
    assert len(m2) > 0 and list(r2.day) == [D(2026, 10, 20)]
    assert (mcx.load_continuous(str(tmp_path), "CRUDEOIL", adjust=True)[0].close == 103).all()
    assert len(mcx.load_futures(str(tmp_path), "CRUDEOIL", session_only=True)) == len(f) - 2


def test_load_options_by_year(tmp_path):
    for y in (2025, 2026):
        pd.DataFrame(dict(ts=pd.to_datetime([f"{y}-07-01 09:00"]).tz_localize(mcx.IST), offset=[0], side=["C"],
                          close=[1.0])).to_parquet(tmp_path / f"opt_GOLDM_MONTH_{y}.parquet")
    assert len(mcx.load_options(str(tmp_path), "GOLDM")) == 2
    assert len(mcx.load_options(str(tmp_path), "GOLDM", 2026)) == 1
    assert len(mcx.load_options(str(tmp_path), "ZINC")) == 0


def test_missing_futures_file_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        mcx.load_futures(str(tmp_path))


def test_registry_keeps_contracts_that_left_the_master(tmp_path, monkeypatch):
    reg = str(tmp_path / "FUT_CONTRACTS.json")
    a = ("CRUDEOIL", "2026-10-19", "569900", "FUTCOM", "MCX")
    b = ("CRUDEOIL", "2026-11-19", "573422", "FUTCOM", "MCX")
    assert sorted(lf.known_contracts([a, b], reg)) == [a, b]
    assert sorted(lf.known_contracts([b], reg)) == [a, b]          # a expired and left the master: still known
    assert len(json.load(open(reg))) == 2


def test_fut_jobs_include_registry_contracts_and_stop_at_expiry(tmp_path, monkeypatch):
    raw = tmp_path / "raw"
    os.makedirs(raw / "master")
    hdr = "EXCH_ID,SEGMENT,SECURITY_ID,INSTRUMENT,UNDERLYING_SYMBOL,SM_EXPIRY_DATE\n"
    (raw / "master" / "api-scrip-master-detailed.csv").write_text(
        hdr + "MCX,M,573422,FUTCOM,CRUDEOIL,2026-11-19 14:30:00\nNSE,D,1,FUTIDX,NIFTY,2026-10-27 14:30:00\n")
    reg = tmp_path / "reg.json"
    reg.write_text(json.dumps([dict(symbol="CRUDEOIL", expiry="2026-10-19", security_id="569900", instrument="FUTCOM",
                                    exchange="MCX", first_seen="2026-10-14")]))
    monkeypatch.setattr(lf, "CONTRACTS", str(reg))
    monkeypatch.setattr(lf, "TODAY", D(2026, 10, 25))
    J = lf.jobs_for("fut_1m_mcx", str(raw))
    sids = {j.body["securityId"] for j in J}
    assert sids == {"569900", "573422"}                         # MCX only, the expired one included
    old = [j for j in J if j.body["securityId"] == "569900"]
    assert max(lf.window(j)[1] for j in old) == D(2026, 10, 19)  # windows stop at expiry
    assert max(lf.window(j)[1] for j in J if j.body["securityId"] == "573422") == D(2026, 10, 26)  # see to_date
    assert len(J) == len({j.path for j in J})                    # master expiry with a time part: no duplicates
    assert {j.body["securityId"] for j in lf.jobs_for("fut_1m_index", str(raw))} == {"1"}


def test_mcxopt_subset_group(tmp_path):
    J = lf.jobs_for("mcxopt_COPPER,ZINC", str(tmp_path))
    assert {j.body["securityId"] for j in J} == {152, 378}
    offs = [abs(int(j.path.split("/")[-2][:-1])) for j in J]
    assert offs == sorted(offs)                                  # nearest strikes first
    assert {j.path.split("/")[-5] for j in J} == {"COPPER", "ZINC"}


def test_to_date_asks_one_day_past_today_only_for_the_newest_window(monkeypatch):
    monkeypatch.setattr(lf, "TODAY", D(2026, 10, 19))
    assert lf.to_date(D(2026, 10, 19)) == "2026-10-20 23:59:59"
    assert lf.to_date(D(2026, 10, 16)) == "2026-10-16 23:59:59"
    assert lf.to_date(D(2026, 10, 25)) == "2026-10-26 23:59:59"


def test_roll_to_a_contract_with_no_earlier_bar_has_unknown_gap():
    f = pd.concat([bars("ZINC", "2026-10-30", 1, "2026-10-30", 90, 250),
                   bars("ZINC", "2026-11-30", 2, "2026-11-02", 90, 252)], ignore_index=True)
    m, r = mcx.continuous(f, "ZINC", rule="expiry")
    assert list(r.day) == [D(2026, 11, 2)] and np.isnan(r.gap.iloc[0]) and pd.isna(r["at"].iloc[0])
    m, r = mcx.continuous(f, "ZINC")
    assert len(r) == 1 and np.isnan(r.gap.iloc[0])


def test_near_share_marks_far_month_days():
    m, _ = mcx.continuous(fut_two_contracts(), "CRUDEOIL")
    daily = pd.DataFrame(dict(date=pd.to_datetime(["2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16"]),
                              volume=[900 * 10, 900, 900, 900]))     # 13 Oct: the near month (not held) traded 10x
    m["near_share"] = mcx.near_share(m, daily)
    by = m.groupby(m.ts.dt.date).near_share.first()
    assert by[D(2026, 10, 13)] == pytest.approx(0.1) and by[D(2026, 10, 14)] == pytest.approx(1.0)
    assert np.isnan(by[D(2026, 10, 19)])                             # no daily row that day
    assert mcx.near_month_from(m) == D(2026, 10, 14)
    assert mcx.near_share(m, None).isna().all()


def test_volume_rule_returns_to_the_busy_near_month():
    """Early history: only a far month exists, then the near month appears and trades more: the series must move to
    it (on the real lake COPPER held Mar-2027 with 9 lots a day under the earlier never-step-back rule)."""
    f = pd.concat([bars("COPPER", "2027-03-31", 9, "2026-09-01", 30, 900),
                   bars("COPPER", "2027-03-31", 9, "2026-09-02", 9, 901),
                   bars("COPPER", "2026-10-30", 5, "2026-09-02", 9000, 880),
                   bars("COPPER", "2027-03-31", 9, "2026-09-03", 9, 902),
                   bars("COPPER", "2026-10-30", 5, "2026-09-03", 9000, 881)], ignore_index=True)
    m, r = mcx.continuous(f, "COPPER")
    held = m.groupby(m.ts.dt.date).expiry.first()
    assert held[D(2026, 9, 2)] == D(2027, 3, 31) and held[D(2026, 9, 3)] == D(2026, 10, 30)
    assert list(r.day) == [D(2026, 9, 3)] and r.gap.iloc[0] == pytest.approx(880 - 901)


class _Resp:
    status_code, ok = 504, False

    def json(self):
        return {}


def test_dhan_retries_follow_the_setting(monkeypatch):
    import dhan_client as dh
    calls = []

    class S:
        def post(self, *a, **k):
            calls.append(1)
            return _Resp()

    monkeypatch.setattr(dh, "_TOK", "t"); monkeypatch.setattr(dh, "_CID", "c")
    monkeypatch.setattr(dh._S, "s", S(), raising=False)
    monkeypatch.setattr(dh.time, "sleep", lambda s: None)
    monkeypatch.setattr(dh, "PACE", dh.Pacer(0))
    monkeypatch.setattr(dh, "RETRIES", 2)
    st, js, err = dh.post("/x", {})
    assert st == -1 and "504" in err and len(calls) == 2
    calls.clear()
    dh.post("/x", {}, retries=3)
    assert len(calls) == 3


def test_hysteresis_keeps_the_held_contract_unless_clearly_busier():
    rows = []
    for day, va, vb in [("2026-07-01", 300, 90), ("2026-07-02", 300, 360), ("2026-07-03", 300, 420),
                        ("2026-07-06", 300, 480), ("2026-07-07", 300, 480)]:
        rows += [bars("GOLDM", "2026-11-05", 1, day, va, 100), bars("GOLDM", "2026-12-04", 2, day, vb, 101)]
    m, r = mcx.continuous(pd.concat(rows, ignore_index=True), "GOLDM")
    held = m.groupby(m.ts.dt.date).expiry.first()
    # 2 and 3 Jul: Dec traded 1.2x / 1.4x of Nov the day before -> stay; 6 Jul uses 3 Jul (1.4x) -> stay;
    # 7 Jul uses 6 Jul (1.6x) -> switch
    assert list(held) == [D(2026, 11, 5)] * 4 + [D(2026, 12, 4)]
    assert list(r.day) == [D(2026, 7, 7)]


def test_option_window_that_times_out_is_asked_again_in_10_day_pieces(tmp_path, monkeypatch):
    asked = []

    def post(endpoint, body):
        a, b = body["fromDate"], body["toDate"]
        asked.append((a, b))
        if (D.fromisoformat(b) - D.fromisoformat(a)).days >= 10:
            return -1, None, "HTTP 504 "
        t = int(pd.Timestamp(a, tz=mcx.IST).timestamp()) + 9 * 3600
        return 200, {"data": {"ce": {"timestamp": [t, t + 60], "close": [1.0, 2.0]}}}, ""

    monkeypatch.setattr(lf.dh, "post", post)
    j = lf.Job(str(tmp_path / "s" / "2026-05-07"), "/charts/rollingoption",
               dict(fromDate="2026-05-07", toDate="2026-06-05"), key="ce")
    assert j.run() == ("ok", 6)
    assert asked == [("2026-05-07", "2026-06-05"), ("2026-05-07", "2026-05-16"), ("2026-05-17", "2026-05-26"),
                     ("2026-05-27", "2026-06-05")]
    assert len(pd.read_parquet(str(tmp_path / "s" / "2026-05-07.parquet"))) == 6


def test_a_piece_that_fails_leaves_the_chunk_as_err(tmp_path, monkeypatch):
    def post(endpoint, body):
        return (-1, None, "HTTP 504 ") if body["fromDate"] != "2026-05-07" or body["toDate"] != "2026-05-16" else \
            (200, {"data": {"ce": {"timestamp": [1], "close": [1.0]}}}, "")

    monkeypatch.setattr(lf.dh, "post", post)
    j = lf.Job(str(tmp_path / "s" / "2026-05-07"), "/charts/rollingoption",
               dict(fromDate="2026-05-07", toDate="2026-06-05"), key="ce")
    assert j.run()[0] == "err" and os.path.exists(j.path + ".err") and not j.done()
    j2 = lf.Job(str(tmp_path / "t" / "x"), "/charts/intraday", dict(fromDate="2026-05-07", toDate="2026-06-05"))
    assert j2.run()[0] == "err"                                   # only option windows are split
