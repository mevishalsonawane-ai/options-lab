"""Tests for research/data/crypto_options_lake.py (13 Oct 2026): paging + resume, de-duplication, instrument names, IV and
price sanity, Parquet round trip, Delta candles and products, the catalog, and Drive steps (rclone faked)."""
from __future__ import annotations

import argparse
import io
import json
import os
import sys
import urllib.error
from pathlib import Path

import pyarrow.parquet as pq
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import crypto_options_lake as L  # noqa: E402

T0 = L.month_bounds("2025-09")[0]


def trade(i, ts, name="BTC-26SEP25-110000-C", price=0.02, iv=45.0, **kw):
    return dict(trade_id=str(1000 + i), trade_seq=i, timestamp=ts, instrument_name=name, price=price,
                mark_price=price, iv=iv, index_price=108000.0, amount=0.1, contracts=0.1, direction="buy",
                tick_direction=1, **kw)


class FakeDeribit:
    """history.deribit.com get_last_trades_by_currency_and_time over a fixed trade list (ascending, count-limited)."""

    def __init__(self, trades):
        self.trades = sorted(trades, key=lambda t: t["timestamp"])
        self.calls = []

    def __call__(self, url, params=None):
        self.calls.append(dict(params))
        assert url.endswith("get_last_trades_by_currency_and_time")
        s, e, n = params["start_timestamp"], params["end_timestamp"], params["count"]
        hit = [t for t in self.trades if s <= t["timestamp"] <= e]
        return dict(result=dict(trades=hit[:n], has_more=len(hit) > n))


# ---------------------------------------------------------------- names

def test_parse_deribit_name():
    p = L.parse_deribit_name("BTC-27DEC24-100000-C")
    assert p["underlying"] == "BTC" and p["strike"] == 100000 and p["option_type"] == "C"
    assert L.utc(p["expiry_ts"]) == "2024-12-27T08:00Z"
    assert L.parse_deribit_name("ETH-1DEC23-2000-P")["option_type"] == "P"                 # one-digit day
    assert L.parse_deribit_name("XRP_USDC-30JAN26-0d625-C")["strike"] == 0.625            # 'd' decimal strike
    for bad in ("BTC-PERPETUAL", "BTC-27DEC24", "BTC-31FEB24-100-C", "BTC-27XYZ24-100-C", "", None):
        assert L.parse_deribit_name(bad) is None


def test_parse_delta_symbol():
    p = L.parse_delta_symbol("C-BTC-85400-131026")
    assert p == dict(option_type="C", underlying="BTC", strike=85400.0, expiry_ts=p["expiry_ts"])
    assert L.utc(p["expiry_ts"]) == "2026-10-13T12:00Z"                                  # 17:30 IST
    assert L.parse_delta_symbol("P-ETH-2500-010126")["option_type"] == "P"
    for bad in ("BTCUSD", "C-BTC-85400-321026", "X-BTC-1-131026", None):
        assert L.parse_delta_symbol(bad) is None


def test_month_helpers():
    assert L.month_range("2024-11", "2025-02") == ["2024-11", "2024-12", "2025-01", "2025-02"]
    s, e = L.month_bounds("2024-02")
    assert (e - s) == 29 * L.DAY_MS                                                       # leap year


# ---------------------------------------------------------------- paging + resume + dedup

def test_paging_covers_every_trade_once_across_same_millisecond_page_edges():
    # many trades share milliseconds so page edges fall inside a millisecond
    ts = [T0 + (i // 3) * 1000 for i in range(50)]
    trades = [trade(i, t) for i, t in enumerate(ts)]
    api = FakeDeribit(trades)
    got = [t for new, *_ in L.page_trades(api, "BTC", T0, T0 + L.DAY_MS, count=7) for t in new]
    assert sorted(t["trade_id"] for t in got) == sorted(t["trade_id"] for t in trades)
    assert len(got) == len(trades)                                                        # no repeats
    assert all(c["start_timestamp"] >= T0 and c["end_timestamp"] == T0 + L.DAY_MS - 1 for c in api.calls)


def test_paging_saturated_page_steps_one_ms_and_is_flagged():
    trades = [trade(i, T0) for i in range(5)] + [trade(9, T0 + 5)]
    pages = list(L.page_trades(FakeDeribit(trades), "BTC", T0, T0 + 100, count=3))
    assert pages[0][3] is True and pages[0][1] == T0 + 1                                 # saturated → +1 ms
    assert [t["trade_id"] for t in pages[-1][0]] == ["1009"]


def test_paging_empty_window_and_window_edges():
    assert list(L.page_trades(FakeDeribit([]), "BTC", T0, T0 + 10)) == []
    trades = [trade(0, T0 - 1), trade(1, T0), trade(2, T0 + 9), trade(3, T0 + 10)]
    got = [t["trade_id"] for new, *_ in L.page_trades(FakeDeribit(trades), "BTC", T0, T0 + 10) for t in new]
    assert got == ["1001", "1002"]                                                        # [start, end)


def test_build_month_resumes_from_checkpoint(tmp_path, monkeypatch):
    month = "2025-09"
    trades = [trade(i, T0 + i * 60_000) for i in range(40)]
    api = FakeDeribit(trades)
    now = L.month_bounds(month)[1] + 1

    calls = {"n": 0}
    real = L.page_trades

    def dying(*a, **k):        # crash after 3 pages (checkpoint every 2 pages)
        for x in real(*a, **k):
            calls["n"] += 1
            if calls["n"] > 3:
                raise ConnectionError("box died")
            yield x
    monkeypatch.setattr(L, "page_trades", dying)
    with pytest.raises(ConnectionError):
        L.build_trades_month(api, "BTC", month, str(tmp_path), now, flush_pages=2, count=5)
    parts = tmp_path / "options/deribit/trades/BTC/2025/2025-09.parquet.parts"
    st = json.loads((parts / "cursor.json").read_text())
    assert st["cursor"] is not None and st["parts"] == 1
    monkeypatch.setattr(L, "page_trades", real)
    api.calls.clear()
    out, rel, qa = L.build_trades_month(api, "BTC", month, str(tmp_path), now, flush_pages=2, count=5)
    assert api.calls[0]["start_timestamp"] == st["cursor"]                                # resumed, not restarted
    t = pq.read_table(out)
    assert t.num_rows == 40 and qa["rows"] == 40 and qa["dup_ids"] == 0 and qa["ok"]
    assert t.column("trade_id").to_pylist() == [str(1000 + i) for i in range(40)]
    assert not parts.exists()
    assert rel == "options/deribit/trades/BTC/2025/2025-09.parquet"


def test_finish_trades_sorts_and_drops_duplicate_ids():
    tbl = L.trades_table([trade(2, T0 + 5), trade(1, T0 + 1), trade(2, T0 + 5), trade(3, T0 + 1)])
    out, dropped = L.finish_trades(tbl)
    assert dropped == 1
    assert out.column("trade_id").to_pylist() == ["1001", "1003", "1002"]                # stable within the ms
    assert out.column("timestamp").to_pylist() == sorted(out.column("timestamp").to_pylist())


# ---------------------------------------------------------------- QA

def test_qa_flags_iv_and_price_problems():
    rows = [trade(0, T0, iv=45), trade(1, T0 + 1, iv=0), trade(2, T0 + 2, iv=650), trade(3, T0 + 3, iv=None),
            trade(4, T0 + 4, price=1.2), trade(5, T0 + 5, name="BTC-PERPETUAL")]
    qa = L.trades_qa(L.trades_table(rows), (T0, T0 + L.DAY_MS))
    assert qa["iv_out_of_range"] == 2 and qa["iv_null"] == 1 and not qa["iv_ok"]
    assert qa["iv_zero"] == 1 and qa["iv_over_500"] == 1
    assert qa["price_above_bound"] == 1 and qa["unparsed_names"] == 1 and not qa["ok"]


def test_qa_deep_itm_put_above_one_coin_is_legal_but_not_above_strike():
    # 200,000 put, index 108,000: worth ~0.85 BTC intrinsic; at index 83,000 it is ~1.41 BTC. Both fine.
    ok = [trade(0, T0, name="BTC-26MAR27-200000-P", price=1.353), trade(1, T0 + 1, name="BTC-26MAR27-200000-P",
                                                                          price=0.9)]
    for t in ok:
        t["index_price"] = 83245.6
    qa = L.trades_qa(L.trades_table(ok), (T0, T0 + L.DAY_MS))
    assert qa["price_ge_coin"] == 1 and qa["price_above_bound"] == 0 and qa["ok"]
    bad = trade(2, T0 + 2, name="BTC-26MAR27-50000-P", price=0.7)       # 0.7 × 108,000 = 75,600 > 50,000 strike
    assert L.trades_qa(L.trades_table([bad]), (T0, T0 + L.DAY_MS))["price_above_bound"] == 1


def test_qa_clean_month_and_empty_days():
    rows = [trade(0, T0), trade(1, T0 + 2 * L.DAY_MS)]
    qa = L.trades_qa(L.trades_table(rows), (T0, T0 + 3 * L.DAY_MS))
    assert qa["ok"] and qa["iv_ok"] and qa["days"] == 3 and qa["days_without_trades"] == 1
    assert qa["empty_days"] == ["2025-09-02"]
    empty = L.trades_qa(L.TRADE_SCHEMA.empty_table(), (T0, T0 + L.DAY_MS))
    assert empty["rows"] == 0 and empty["first_ts"] is None and empty["ok"]


# ---------------------------------------------------------------- Parquet round trip

def test_parquet_round_trip_keeps_every_field(tmp_path):
    rows = [trade(0, T0, block_trade_id="BLOCK-1", block_trade_leg_count=6, block_rfq_id=13766, liquidation="M",
                  combo_id="BTC-CS-1", combo_trade_id=99, brand_new_field="x")]
    tbl = L.trades_table(rows)
    p = tmp_path / "x.parquet"
    L.write_parquet(tbl, str(p))
    back = pq.read_table(p)
    assert back.schema.equals(L.TRADE_SCHEMA)
    r = back.to_pylist()[0]
    assert r["block_trade_id"] == "BLOCK-1" and r["block_trade_leg_count"] == 6 and r["liquidation"] == "M"
    assert r["combo_trade_id"] == "99" and json.loads(r["extra"]) == {"brand_new_field": "x"}
    assert r["strike"] == 110000 and r["option_type"] == "C" and r["timestamp"] == T0
    assert pq.ParquetFile(p).metadata.row_group(0).column(0).compression == "ZSTD"


# ---------------------------------------------------------------- HTTP back-off

def test_http_backs_off_on_429_and_deribit_10028():
    seq = [urllib.error.HTTPError("u", 429, "slow", {}, io.BytesIO(b"")),
           json.dumps(dict(error=dict(code=10028, message="too_many_requests"))).encode(),
           json.dumps(dict(result=1)).encode()]
    slept = []

    def opener(url, t):
        x = seq.pop(0)
        if isinstance(x, Exception):
            raise x
        return x
    h = L.Http(min_interval=0, opener=opener, sleep=slept.append)
    assert h("https://x", dict(a=1)) == dict(result=1)
    assert h.backoffs == 2 and len([s for s in slept if s >= 1]) == 2


def test_http_raises_on_other_errors():
    h = L.Http(min_interval=0, opener=lambda u, t: json.dumps(dict(error=dict(code=1, message="bad"))).encode(),
               sleep=lambda s: None)
    with pytest.raises(L.ApiError):
        h("https://x")


# ---------------------------------------------------------------- Deribit reference data

def test_fetch_dvol_walks_back_with_continuation():
    pts = [[T0 + i * 60_000, 50, 51, 49, 50.5] for i in range(25)]

    def get(url, p):
        hit = [x for x in pts if p["start_timestamp"] <= x[0] <= p["end_timestamp"]][-10:]
        return dict(result=dict(data=hit, continuation=hit[0][0] - 1 if len(hit) == 10 else None))
    df = L.fetch_dvol(get, "BTC", T0, T0 + 25 * 60_000)
    assert len(df) == 25 and df["timestamp"].is_monotonic_increasing and df["timestamp"].is_unique


def test_fetch_delivery_pages():
    data = [dict(date=f"2026-01-{d:02d}", delivery_price=100.0 + d) for d in range(31, 0, -1)]

    def get(url, p):
        return dict(result=dict(data=data[p["offset"]:p["offset"] + p["count"]], records_total=len(data)))
    df = L.fetch_delivery(get, "btc_usd", page=7)
    assert len(df) == 31 and df["timestamp"].is_monotonic_increasing
    assert L.utc(int(df["timestamp"].iloc[0])) == "2026-01-01T08:00Z"


# ---------------------------------------------------------------- Delta India

def product(pid, sym, settle="2026-10-13T12:00:00Z", launch="2026-10-11T12:00:00Z", strike="85400"):
    return dict(id=pid, symbol=sym, contract_type="call_options" if sym[0] == "C" else "put_options",
                state="expired", strike_price=strike, settlement_time=settle, launch_time=launch,
                contract_value="0.001", tick_size="0.1", maker_commission_rate="0.0001",
                taker_commission_rate="0.0001", underlying_asset=dict(symbol=sym.split("-")[1]),
                quoting_asset=dict(symbol="USD"), settling_asset=dict(symbol="USD"),
                product_specs=dict(premium_commission_rate=0.035))


def test_products_frame_merge_and_fees():
    raw = [product(1, "C-BTC-85400-131026"), product(2, "P-BTC-85400-131026"),
           product(1, "C-BTC-85400-131026")]                                                # repeat across pages
    df = L.products_frame(raw, inr_per_usd=88.0, fetched_ms=1)
    assert len(df) == 2 and L.utc(int(df["expiry_ts"].iloc[0])) == "2026-10-13T12:00Z"
    assert df["notional_inr_at_strike"].iloc[0] == pytest.approx(0.001 * 85400 * 88.0)
    old = L.products_frame([product(9, "C-ETH-2500-010126", settle="2026-01-01T12:00:00Z", strike="2500")])
    merged = L.merge_products(old, df)
    assert sorted(merged["id"].tolist()) == [1, 2, 9]                                     # delisted rows kept
    fees = L.fee_schedule(raw, "2026-10-13", 88.0)
    assert fees["fetched"] == "2026-10-13" and fees["schedules"][0]["products"] == 3
    assert fees["schedules"][0]["first_expiry"] == "2026-10-13" and fees["current"] == []
    assert fees["schedules"][0]["premium_commission_cap"] == 0.035


def test_page_products_follows_after_cursor():
    pages = {None: dict(result=[product(1, "C-BTC-1-131026")], meta=dict(after="A")),
             "A": dict(result=[product(2, "C-BTC-2-131026")], meta=dict(after=None))}
    seen = []

    def get(url, p):
        seen.append(p.get("after"))
        return pages[p.get("after")]
    out = L.page_products(get, ["expired"])
    assert [p["id"] for p in out] == [1, 2] and seen == [None, "A"]


def test_fetch_candles_pages_backwards_in_seconds():
    mins = [T0 + i * 60_000 for i in range(9000)]

    def get(url, p):
        hit = [m for m in mins if p["start"] * 1000 <= m <= p["end"] * 1000][::-1][:4000]     # newest first
        return dict(result=[dict(time=m // 1000, open=1, high=2, low=0.5, close=1.5, volume=3) for m in hit])
    rows = L.fetch_candles(get, "C-BTC-1-131026", T0, T0 + 9000 * 60_000)
    assert len(rows) == 9000 and rows[0]["timestamp"] == T0
    assert all(b["timestamp"] - a["timestamp"] == 60_000 for a, b in zip(rows, rows[1:]))


def test_build_candles_day_drops_post_expiry_rows(tmp_path):
    exp = L.iso_ms("2026-10-13T12:00:00Z")
    p = L.products_frame([product(1, "C-BTC-85400-131026")]).to_dict("records")

    def get(url, q):
        ts = [exp - 120_000, exp - 60_000, exp, exp + 60_000]
        return dict(result=[dict(time=t // 1000, open=2, high=2, low=2, close=2, volume=1) for t in ts
                            if q["start"] * 1000 <= t <= q["end"] * 1000])
    out, qa = L.build_candles_day(get, p, str(tmp_path), "options/delta_india/candles/BTC/2026/2026-10-13.parquet")
    t = pq.read_table(out).to_pandas()
    assert len(t) == 2 and qa["dropped_after_expiry"] == 2 and qa["products_with_candles"] == 1
    assert (t["timestamp"] < exp).all() and set(t["option_type"]) == {"C"} and t["strike"].iloc[0] == 85400


def test_snapshot_frame_flattens_quotes_and_greeks():
    tick = dict(symbol="C-BTC-99000-271126", product_id=151077, contract_type="call_options", strike_price="99000",
                mark_price="564.1", oi="3.377", timestamp=1791918199278906, greeks=dict(delta="0.11", vega="54.9"),
                quotes=dict(best_bid="542", best_ask="586", mark_iv="0.383"))
    df = L.snapshot_frame([tick], 1791918200000)
    r = df.iloc[0]
    assert r["greek_delta"] == pytest.approx(0.11) and r["best_ask"] == 586 and r["mark_iv"] == pytest.approx(0.383)
    assert r["ticker_ts"] == 1791918199278 and L.utc(int(r["expiry_ts"])) == "2026-11-27T12:00Z"


# ---------------------------------------------------------------- Black-Scholes

def test_implied_vol_inverts_bs_and_rejects_impossible_prices():
    p = L.bs_price(100_000, 105_000, 7 / 365, 0.5, "C")
    assert L.implied_vol(p, 100_000, 105_000, 7 / 365, "C") == pytest.approx(0.5, abs=1e-6)
    q = L.bs_price(100_000, 95_000, 1 / 365, 0.4, "P")
    assert L.implied_vol(q, 100_000, 95_000, 1 / 365, "P") == pytest.approx(0.4, abs=1e-6)
    assert L.implied_vol(200_000, 100_000, 105_000, 0.1, "C") is None                     # above the spot
    assert L.implied_vol(1.0, 100_000, 105_000, 0, "C") is None                           # expired


# ---------------------------------------------------------------- catalog + driver (rclone faked)

class FakeRclone:
    """Records rclone commands; `copyto` local→remote copies into a dict 'drive'; lsjson lists it."""

    def __init__(self, fail_check=False):
        self.cmds, self.drive, self.fail_check = [], {}, fail_check

    def __call__(self, cmd, capture_output=False, text=False):
        self.cmds.append(cmd)
        op = cmd[1]
        rc, out = 0, ""
        if op == "copyto" and not cmd[2].startswith("gdrive:") and os.path.exists(cmd[2]):
            rel = cmd[3].split("gdrive:IraAlgo-data-crypto/", 1)[1]
            b = open(cmd[2], "rb").read()
            self.drive[rel] = (len(b), L.hashlib.md5(b).hexdigest())
        elif op == "copyto":
            rc = 1
        elif op == "check":
            rc = 1 if self.fail_check else 0
        elif op == "lsjson":
            folder = cmd[-1].split("gdrive:IraAlgo-data-crypto/", 1)[1]
            out = json.dumps([dict(Path=k[len(folder) + 1:], Size=s, Hashes=dict(md5=m))
                              for k, (s, m) in self.drive.items() if k.startswith(folder + "/")])
        return argparse.Namespace(returncode=rc, stdout=out, stderr="")


def test_trades_driver_uploads_catalogs_and_skips_on_rerun(tmp_path):
    month_trades = [trade(i, T0 + i * 3_600_000) for i in range(10)]
    api, rc = FakeDeribit(month_trades), FakeRclone()
    now = L.month_bounds("2025-10")[0] + 5
    argv = ["trades", "--currency", "BTC", "--since", "2025-09", "--until", "2025-09", "--work", str(tmp_path / "w"),
            "--catalog", str(tmp_path / "cat" / "options_catalog.json")]
    assert L.main(argv, run=rc, deribit=api, delta=None, now_ms=now) == 0
    rel = "options/deribit/trades/BTC/2025/2025-09.parquet"
    assert rel in rc.drive and not (tmp_path / "w" / rel).exists()                        # uploaded, then deleted
    cat = json.loads((tmp_path / "cat" / "options_catalog.json").read_text())
    e = cat["files"][0]
    assert e["path"] == rel and e["rows"] == 10 and e["complete"] and len(e["sha256"]) == 64
    assert (tmp_path / "cat" / "options_catalog.csv").read_text().startswith("path,source,dataset")
    assert any(c[1] == "check" for c in rc.cmds)
    api.calls.clear()
    assert L.main(argv, run=rc, deribit=api, delta=None, now_ms=now) == 0
    assert api.calls == []                                                                # already on Drive


def test_failed_check_is_recorded_and_retried(tmp_path):
    api, rc = FakeDeribit([trade(0, T0)]), FakeRclone(fail_check=True)
    now = L.month_bounds("2025-10")[0] + 5
    argv = ["trades", "--currency", "BTC", "--since", "2025-09", "--until", "2025-09", "--work", str(tmp_path / "w"),
            "--catalog", str(tmp_path / "options_catalog.json")]
    assert L.main(argv, run=rc, deribit=api, now_ms=now) == 1
    cat = json.loads((tmp_path / "options_catalog.json").read_text())
    assert cat["files"] == [] and "rclone check" in cat["failures"][0]["error"]


def test_catalog_merge_keeps_another_process_rows_and_never_writes_back_stale_ones(tmp_path):
    path = str(tmp_path / "options_catalog.json")
    row = lambda p, n: dict(path=p, bytes=n, rows=n, source="s", dataset="d", key="k", period="p")  # noqa: E731
    # process A loaded {a: 1, d: 1} at start; meanwhile process B rebuilt a (2), added c, and fixed failure x
    L.save_catalog({"a": row("a", 2), "c": row("c", 3), "d": row("d", 1)}, {}, path)
    files = {"a": row("a", 1), "d": row("d", 1), "b": row("b", 5)}
    fails = {"x": dict(path="x", error="old"), "y": dict(path="y", error="mine")}
    L.save_catalog(files, fails, path, mine={"b", "y"})
    got, gf = L.load_catalog(path)
    assert set(got) == {"a", "b", "c", "d"}
    assert got["a"]["rows"] == 2                                                          # B's newer row wins
    assert set(gf) == {"y"}                                                               # stale x not resurrected


def test_priority_plan_newest_two_years_first():
    now = L.iso_ms("2026-10-13T19:00:00Z")
    recent, older = L.priority_plan(now)
    assert recent["BTC"][0] == "2026-10" and recent["BTC"][-1] == "2024-10" and len(recent["BTC"]) == 25
    assert older["BTC"][0] == "2024-09" and older["BTC"][-1] == "2016-11"
    assert older["ETH"][-1] == "2019-03"


def test_status_summarises(tmp_path):
    e = dict(path="p", source="deribit", dataset="trades", key="BTC", period="2025-09", rows=5, bytes=10,
             first_ts=T0, last_ts=T0 + 1, ok=True, iv_out_of_range=0)
    s = L.status({"p": e}, {})
    assert "deribit/trades/BTC" in s and "2025-09-01" in s and "failures: 0" in s


def test_snapshot_uploads_without_touching_the_catalog(tmp_path):
    tick = dict(symbol="C-BTC-99000-271126", product_id=1, contract_type="call_options", strike_price="99000",
                mark_price="564.1", timestamp=1791918199278906, greeks={}, quotes={})
    rc = FakeRclone()
    cat = tmp_path / "options_catalog.json"
    argv = ["snapshot", "--work", str(tmp_path / "w"), "--catalog", str(cat)]
    assert L.main(argv, run=rc, delta=lambda url, p: dict(result=[tick] if "call" in p["contract_types"] else [])) == 0
    assert len(rc.drive) == 1 and next(iter(rc.drive)).startswith("options/delta_india/snapshots/")
    assert not cat.exists() and not any(c[1] == "copyto" and "catalog" in c[-2] for c in rc.cmds)
    assert not any((tmp_path / "w").rglob("*.parquet"))                                  # deleted after upload


def test_http_retries_a_cut_off_response():
    import http.client
    seq = [http.client.IncompleteRead(b"x", 10), json.dumps(dict(result=2)).encode()]

    def opener(url, t):
        x = seq.pop(0)
        if isinstance(x, Exception):
            raise x
        return x
    assert L.Http(min_interval=0, opener=opener, sleep=lambda s: None)("https://x") == dict(result=2)


def test_inr_per_usd_reads_delta_settings():
    assert L.inr_per_usd(lambda u, p=None: dict(result=dict(fiat_to_usd=dict(asset_to_fiat_value=85)))) == 85.0
    assert L.inr_per_usd(lambda u, p=None: dict(result={})) is None


def test_crosscheck_matches_strike_and_expiry_and_compares_iv(tmp_path):
    day0 = L.iso_ms("2026-10-12T00:00:00Z")
    exp = L.iso_ms("2026-10-13T12:00:00Z")
    spot, k = 83_000.0, 84_000.0
    minute = day0 + 10 * 3_600_000
    t_years = (exp - minute) / (365 * L.DAY_MS)
    delta_px = L.bs_price(spot, k, t_years, 0.40, "C")
    der = trade(0, minute + 60_000, name="BTC-13OCT26-84000-C", price=0.004, iv=41.0)
    der["index_price"] = spot

    def delta(url, p):
        if url.endswith("/products"):
            return dict(result=[product(1, "C-BTC-84000-131026", strike="84000")] if p["states"] == "expired" else [],
                        meta={})
        return dict(result=[dict(time=minute // 1000, open=delta_px, high=delta_px, low=delta_px, close=delta_px,
                                 volume=5)])
    a = argparse.Namespace(work=str(tmp_path), remote="gdrive:IraAlgo-data-crypto", dry_run=True,
                           catalog=str(tmp_path / "c.json"))
    lake = L.Lake(a, run=FakeRclone(), deribit=FakeDeribit([der]), delta=delta, now_ms=exp + L.DAY_MS)
    res = L.crosscheck(lake, "2026-10-12", "BTC")
    assert res["matched_strike_expiry"] == 1 and res["comparisons"] == 1
    assert res["sample"][0]["delta_iv"] == pytest.approx(40.0, abs=0.01)
    assert res["median_iv_diff_pts"] == pytest.approx(-1.0, abs=0.01)
