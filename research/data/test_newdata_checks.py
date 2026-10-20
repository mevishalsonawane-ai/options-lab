"""newdata_checks: bad-tick checks on new lake rows (20 Oct 2026)."""
import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import newdata_checks as nc

IST = "Asia/Kolkata"


def book(start, n=30, spot=1000.0):
    """One CE strike, a clean price path, plus one stray open and one one-minute spike."""
    ts = pd.date_range(f"2026-10-19 {start}", periods=n, freq="1min", tz=IST)
    c = np.linspace(50, 59, n)
    df = pd.DataFrame(dict(ts=ts, offset=np.int8(0), side="C", open=c, high=c + 0.5, low=c - 0.5, close=c,
                           iv=np.float32(20), volume=10, strike=1000.0, oi=100, spot=spot))
    df.loc[10, "close"] = df.loc[10, "high"] = 200.0          # spike: neighbours ~53, then back
    df.loc[20, "open"] = df.loc[20, "high"] = 90.0            # stray open far above the neighbours' closes
    return df


def test_stock_book_uses_its_own_spot_not_all_foreign(tmp_path):
    fn = tmp_path / "stkopt_INFY_MONTH.parquet"
    book("09:15").to_parquet(fn)
    r = nc.option_file(str(tmp_path), str(fn))
    c = r["lakeclean"]
    assert c["foreign"] == 0 and c["foreign_ref"] == "median spot of the book"
    assert c["stray_open"] >= 1 and 0 < c["bad_tick_share"] < 0.2
    assert r["spikes"]["spikes"] == 1


def test_mcx_book_and_since(tmp_path):
    fn = tmp_path / "opt_ZINC_MONTH_2026.parquet"
    book("21:00").to_parquet(fn)
    r = nc.option_file(str(tmp_path), str(fn))
    assert r["lakeclean"]["foreign"] == 0 and r["spikes"]["early_window"] == "09:00-10:44"
    assert nc.option_file(str(tmp_path), str(fn), since="2026-10-20")["rows"] == 0


def test_index_book_reads_index_closes_and_flags_foreign_rows(tmp_path):
    ts = pd.date_range("2026-10-19 09:15", periods=30, freq="1min", tz=IST)
    pd.DataFrame(dict(ts=ts, open=1000.0, high=1001.0, low=999.0, close=1000.0, volume=0)).to_parquet(
        tmp_path / "index_1m_NIFTY_2026.parquet")
    df = book("09:15")
    df.loc[:4, "spot"] = 1200.0                                # rows of another underlying (20% away)
    df.to_parquet(tmp_path / "opt_NIFTY_WEEK_2026.parquet")
    c = nc.option_file(str(tmp_path), str(tmp_path / "opt_NIFTY_WEEK_2026.parquet"))["lakeclean"]
    assert c["foreign"] == 5 and c["foreign_ref"] == "index_1m close"


def test_futures_checks(tmp_path):
    ts = pd.date_range("2026-10-19 09:00", periods=6, freq="1min", tz=IST)
    c = [100.0, 100.0, 103.0, 100.2, 100.2, 100.3]           # 103: >2% from both neighbours within 0.5%
    v = [5, 0, 5, 5, 0, 5]                                      # minute 1 and 4: flat, zero volume, same close
    df = pd.DataFrame(dict(symbol="ZINC", expiry="2026-10-30", security_id=1, ts=ts, open=c, high=c, low=c,
                           close=c, volume=v, oi=1))
    df.to_parquet(tmp_path / "fut_1m_live_mcx.parquet")
    r = nc.futures_file(str(tmp_path / "fut_1m_live_mcx.parquet"))
    assert r["spikes_2pct"] == 1 and r["per_symbol"]["ZINC"]["padded"] == 2 and r["spikes_50pct"] == 0


def test_main_writes_json(tmp_path):
    book("09:15").to_parquet(tmp_path / "stkopt_INFY_MONTH.parquet")
    nc.main([str(tmp_path), str(tmp_path / "o.json"), "--since", "2026-10-01", "stkopt_INFY_MONTH.parquet"])
    assert json.load(open(tmp_path / "o.json"))[0]["rows"] == 30
