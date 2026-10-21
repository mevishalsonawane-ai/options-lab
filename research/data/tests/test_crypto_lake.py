"""Tests for research/data/crypto_lake.py on tiny synthetic Binance-style zips (no network, no Drive)."""
from __future__ import annotations

import datetime as dt
import hashlib
import io
import json
import os
import sys
import types
import zipfile
from pathlib import Path

import pandas as pd
import pyarrow.parquet as pq
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import crypto_lake as cl  # noqa: E402

TODAY = dt.date(2026, 10, 13)
FEB = 1_706_745_600_000                  # 2024-02-01T00:00Z in ms
MIN = 60_000


def kline_csv(start_ms, n, header=False, unit=1, skip=(), extra=""):
    lines = [",".join(cl.KLINE_COLS)] if header else []
    for i in range(n):
        if i in skip:
            continue
        t = start_ms + i * MIN
        o = 100 + i
        lines.append(f"{t * unit},{o},{o + 2},{o - 1},{o + 1},1.5,{(t + MIN - 1) * unit + (unit - 1)},150,3,0.5,50,0")
    return "\n".join(lines) + "\n" + extra


def make_zip(text, name="X.csv"):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        z.writestr(name, text)
    return b.getvalue()


class Archive:
    """Fake archive: key → zip bytes; serves .CHECKSUM files (optionally wrong) and counts downloads."""

    def __init__(self, files, bad_checksum=()):
        self.files, self.bad, self.downloads = files, set(bad_checksum), []

    def get(self, url):
        key = url[len(cl.ARCHIVE):]
        assert key.endswith(".CHECKSUM")
        key = key[:-len(".CHECKSUM")]
        h = "0" * 64 if key in self.bad else hashlib.sha256(self.files[key]).hexdigest()
        return f"{h}  {os.path.basename(key)}\n".encode()

    def download(self, url, dest):
        key = url[len(cl.ARCHIVE):]
        self.downloads.append(key)
        Path(dest).write_bytes(self.files[key])
        return dest


SPOT_M = "data/spot/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-2024-02.zip"
SPOT_1D = "data/spot/monthly/klines/BTCUSDT/1d/BTCUSDT-1d-2024-02.zip"


def day_zip(closes):
    """1d klines for Feb 2024 whose close on day d is closes.get(d, 0)."""
    rows = []
    for d in range(29):
        t = FEB + d * 86_400_000
        rows.append(f"{t},1,2,0.5,{closes.get(d + 1, 0)},1,{t + 86_399_999},1,1,1,1,0")
    return make_zip("\n".join(rows) + "\n")


# ---------------------------------------------------------------- parsing

def test_headerless_and_header_files_parse_the_same(tmp_path):
    for i, hdr in enumerate((False, True)):
        p = tmp_path / f"{i}.zip"
        p.write_bytes(make_zip(kline_csv(FEB, 3, header=hdr)))
        df = cl.read_zip(str(p), "klines")
        assert list(df["open_time"]) == [FEB, FEB + MIN, FEB + 2 * MIN]
        assert df["open_time"].dtype == "int64" and df["close"].dtype == "float64"
        assert "ignore" not in df.columns


def test_empty_file_gives_an_empty_typed_frame(tmp_path):
    for i, text in enumerate(("", ",".join(cl.KLINE_COLS) + "\n")):
        p = tmp_path / f"{i}.zip"
        p.write_bytes(make_zip(text))
        df = cl.read_zip(str(p), "klines")
        assert df.empty and df["open_time"].dtype == "int64"


def test_metrics_datetime_strings_become_utc_ms(tmp_path):
    text = ("create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,"
            "sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio\n"
            "2024-02-01 00:05:00,BTCUSDT,1,2,3,4,5,\n")
    p = tmp_path / "m.zip"
    p.write_bytes(make_zip(text))
    df = cl.read_zip(str(p), "metrics")
    assert df["create_time"].tolist() == [FEB + 5 * MIN]
    assert "symbol" not in df.columns and pd.isna(df["sum_taker_long_short_vol_ratio"].iloc[0])


def test_aggtrades_booleans_parse(tmp_path):
    p = tmp_path / "a.zip"
    p.write_bytes(make_zip(f"1,100.5,0.1,10,11,{FEB},True\n2,100.6,0.2,12,12,{FEB + 5},false\n"))
    df = cl.read_zip(str(p), "aggtrades")
    assert df["is_buyer_maker"].tolist() == [True, False]


# ---------------------------------------------------------------- timestamp unit

def test_microsecond_timestamps_are_converted_to_ms():
    df = pd.DataFrame(dict(open_time=[FEB * 1000, FEB + MIN], close_time=[(FEB + MIN - 1) * 1000 + 999, FEB + 2 * MIN - 1]))
    n = cl.fix_time_units(df, ["open_time", "close_time"])
    assert n == 1
    assert df["open_time"].tolist() == [FEB, FEB + MIN]
    assert df["close_time"].tolist() == [FEB + MIN - 1, FEB + 2 * MIN - 1]


def test_month_in_microseconds_builds_in_ms_and_is_reported(tmp_path):
    arch = Archive({SPOT_M: make_zip(kline_csv(FEB, 29 * 1440, unit=1000)), SPOT_1D: day_zip({})})
    e = cl.build_month("spot", "klines_1m", "BTCUSDT", "2024-02", [SPOT_M], str(tmp_path), TODAY,
                       get=arch.get, download=arch.download)
    t = pq.read_table(tmp_path / e["path"]).to_pandas()
    assert t["open_time"].iloc[0] == FEB and t["close_time"].iloc[0] == FEB + MIN - 1
    assert e["ts_unit_fixed"] == 29 * 1440 and e["first_ts"] == FEB
    assert e["missing"] == 0 and e["rows"] == e["expected"] == 29 * 1440


# ---------------------------------------------------------------- checksum

def test_checksum_mismatch_raises_after_one_retry_and_writes_nothing(tmp_path):
    arch = Archive({SPOT_M: make_zip(kline_csv(FEB, 10))}, bad_checksum=[SPOT_M])
    with pytest.raises(cl.ChecksumError):
        cl.build_month("spot", "klines_1m", "BTCUSDT", "2024-02", [SPOT_M], str(tmp_path), TODAY,
                       get=arch.get, download=arch.download)
    assert arch.downloads == [SPOT_M, SPOT_M]
    assert not list(tmp_path.rglob("*.parquet")) and not list(tmp_path.rglob("*.zip"))


def test_good_checksum_passes(tmp_path):
    arch = Archive({SPOT_M: b"abc"})
    p = cl.fetch_verified(SPOT_M, str(tmp_path), arch.get, arch.download)
    assert Path(p).read_bytes() == b"abc"


# ---------------------------------------------------------------- QA

def test_gaps_listed_not_filled_and_duplicates_dropped(tmp_path):
    dup = kline_csv(FEB, 4).splitlines()[3] + "\n"                     # an exact copy of minute 3
    text = kline_csv(FEB, 29 * 1440, skip={10, 11, 12, 500}) + dup
    arch = Archive({SPOT_M: make_zip(text), SPOT_1D: day_zip({15: 100 + 14 * 1440 + 1439 + 1})})
    e = cl.build_month("spot", "klines_1m", "BTCUSDT", "2024-02", [SPOT_M], str(tmp_path), TODAY,
                       check_daily=True, get=arch.get, download=arch.download)
    t = pq.read_table(tmp_path / e["path"]).to_pandas()
    assert len(t) == 29 * 1440 - 4 == e["rows"]                        # not filled
    assert t["open_time"].is_monotonic_increasing and t["open_time"].is_unique
    assert e["dup_exact"] == 1 and e["dup_conflict"] == 0
    assert e["missing"] == 4 and e["gap_count"] == 2
    assert e["gaps"][0] == "2024-02-01T00:10Z/2024-02-01T00:12Z (3)"
    assert e["ohlc_bad"] == 0 and e["ok"] and e["complete"]
    assert e["daily_check"]["date"] == "2024-02-15" and e["daily_check"]["ok"]
    assert e["sources"] == ["BTCUSDT-1m-2024-02.zip"]
    assert e["sha256"] == hashlib.sha256((tmp_path / e["path"]).read_bytes()).hexdigest()


def test_daily_close_mismatch_is_flagged(tmp_path):
    arch = Archive({SPOT_M: make_zip(kline_csv(FEB, 29 * 1440)), SPOT_1D: day_zip({15: 1.0})})
    e = cl.build_month("spot", "klines_1m", "BTCUSDT", "2024-02", [SPOT_M], str(tmp_path), TODAY,
                       check_daily=True, get=arch.get, download=arch.download)
    assert e["daily_check"]["ok"] is False and e["daily_check"]["close_1d"] == 1.0


def test_unsorted_conflicting_and_bad_ohlc_rows_are_reported():
    df = pd.DataFrame(dict(open_time=[FEB + MIN, FEB, FEB], open=[1.0, 1.0, 1.0], high=[0.5, 2.0, 3.0],
                           low=[0.9, 0.5, 0.5], close=[1.0, 1.0, 1.0], close_time=[0, 0, 0]))
    out, qa = cl.clean_and_check(df, "klines", MIN, (FEB, FEB + 3 * MIN))
    assert qa["resorted"] and qa["dup_conflict"] == 1 and qa["ohlc_bad"] == 1 and not qa["ok"]
    assert out["open_time"].tolist() == [FEB, FEB + MIN] and qa["missing"] == 1


def test_rows_outside_the_month_are_dropped():
    df = pd.DataFrame(dict(create_time=[FEB - 5 * MIN, FEB, FEB + 5 * MIN], x=[1.0, 2.0, 3.0]))
    out, qa = cl.clean_and_check(df, "metrics", 5 * MIN, (FEB, FEB + 10 * MIN))
    assert qa["outside_dropped"] == 1 and qa["rows"] == 2 and qa["expected"] == 2 and qa["missing"] == 0


def test_empty_month_does_not_crash():
    df = pd.DataFrame(dict(calc_time=pd.Series(dtype="int64"), funding_interval_hours=pd.Series(dtype="int64"),
                           last_funding_rate=pd.Series(dtype="float64")))
    out, qa = cl.clean_and_check(df, "funding", None, (FEB, FEB + 86_400_000))
    assert qa["rows"] == 0 and qa["first_ts"] is None


# ---------------------------------------------------------------- planning

def fake_lister(keys):
    return lambda prefix: [k for k in keys if k.startswith(prefix)]


def test_plan_uses_monthly_then_daily_for_the_current_month_and_skips_stray_files():
    root = "data/futures/um/{}/klines/BTCUSDT/1m/"
    keys = [root.format("monthly") + f"BTCUSDT-1m-2026-0{m}.zip" for m in (8, 9)]
    keys += [root.format("monthly") + "part-00000-abc.zip"]
    keys += [root.format("daily") + f"BTCUSDT-1m-2026-09-{d:02d}.zip" for d in (29, 30)]
    keys += [root.format("daily") + f"BTCUSDT-1m-2026-10-{d:02d}.zip" for d in (1, 2)]
    plan = cl.plan_series("um", "klines_1m", "BTCUSDT", TODAY, fake_lister(keys))
    assert list(plan) == ["2026-08", "2026-09", "2026-10"]
    assert [os.path.basename(k) for k in plan["2026-09"]] == ["BTCUSDT-1m-2026-09.zip"]
    assert len(plan["2026-10"]) == 2
    assert not cl.complete_month("2026-10", plan["2026-10"], TODAY)
    assert cl.complete_month("2026-09", plan["2026-09"], TODAY)
    s, e = cl.window_for("2026-10", plan["2026-10"], None, False, MIN)
    assert (e - s) == 2 * 86_400_000                                    # up to the end of the last daily zip


def test_daily_only_dataset_groups_days_by_month_and_knows_when_a_month_is_complete():
    root = "data/futures/um/daily/metrics/BTCUSDT/"
    keys = [root + f"BTCUSDT-metrics-2024-02-{d:02d}.zip" for d in range(1, 30)]
    keys += [root + f"BTCUSDT-metrics-2024-03-{d:02d}.zip" for d in range(1, 30)]   # 2 days missing
    plan = cl.plan_series("um", "metrics", "BTCUSDT", TODAY, fake_lister(keys))
    assert {m: len(k) for m, k in plan.items()} == {"2024-02": 29, "2024-03": 29}
    assert cl.complete_month("2024-02", plan["2024-02"], TODAY)
    assert not cl.complete_month("2024-03", plan["2024-03"], TODAY)


def test_funding_has_no_daily_fallback():
    keys = ["data/futures/um/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-2026-09.zip"]
    calls = []

    def lister(prefix):
        calls.append(prefix)
        return [k for k in keys if k.startswith(prefix)]

    assert list(cl.plan_series("um", "funding", "BTCUSDT", TODAY, lister)) == ["2026-09"]
    assert not any("/daily/" in c for c in calls)


def test_first_month_window_starts_at_the_listing_minute():
    s, e = cl.window_for("2024-02", ["x-2024-02.zip"], FEB + 3 * MIN + 7, True, MIN)
    assert s == FEB + 3 * MIN and e == cl.month_bounds("2024-02")[1]


def test_drive_path_layout():
    assert cl.drive_path("um", "funding", "BTCUSDT", "2024-02") == \
        "futures_um/funding/BTCUSDT/2024/BTCUSDT-funding-2024-02.parquet"


# ---------------------------------------------------------------- resume + upload

class Rclone:
    def __init__(self, listing=None, fail=()):
        self.calls, self.listing, self.fail = [], listing or [], set(fail)

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)
        rc = 1 if cmd[1] in self.fail else 0
        out = json.dumps(self.listing) if cmd[1] == "lsjson" else ""
        return types.SimpleNamespace(returncode=rc, stdout=out)


def args(tmp_path, **kw):
    a = dict(remote="gd:C", work=str(tmp_path / "w"), catalog=str(tmp_path / "cat" / "catalog.json"), workers=2,
             batch=24, dry_run=False, since=None, limit=0)
    a.update(kw)
    os.makedirs(a["work"], exist_ok=True)
    return types.SimpleNamespace(**a)


def series_archive():
    return Archive({SPOT_M: make_zip(kline_csv(FEB, 29 * 1440)), SPOT_1D: day_zip({})})


def test_run_builds_uploads_checks_catalogs_and_deletes_local(tmp_path):
    arch, rc = series_archive(), Rclone()
    a = args(tmp_path)
    files, fails = {}, {}
    cl.run_series("spot", "klines_1m", "BTCUSDT", a, files, fails, TODAY, rc, fake_lister([SPOT_M]),
                  arch.get, arch.download)
    verbs = [c[1] for c in rc.calls]
    assert verbs[:3] == ["lsjson", "copy", "check"] and "--checksum" in rc.calls[1] and "--one-way" in rc.calls[2]
    rel = cl.drive_path("spot", "klines_1m", "BTCUSDT", "2024-02")
    assert rel in files and not fails
    assert not (Path(a.work) / rel).exists()                            # deleted after the check
    cat = json.load(open(a.catalog))
    assert cat["totals"]["files"] == 1 and cat["files"][0]["rows"] == 29 * 1440
    assert (Path(a.catalog).parent / "catalog.csv").read_text().splitlines()[0].startswith("path,market")


def test_month_already_verified_on_drive_is_skipped(tmp_path):
    arch = series_archive()
    a = args(tmp_path)
    files, fails = {}, {}
    cl.run_series("spot", "klines_1m", "BTCUSDT", a, files, fails, TODAY, Rclone(), fake_lister([SPOT_M]),
                  arch.get, arch.download)
    e = next(iter(files.values()))
    listing = [dict(Path=e["path"].split("BTCUSDT/", 1)[1], Size=e["bytes"], Hashes=dict(md5=e["md5"]))]
    arch.downloads.clear()
    rc = Rclone(listing)
    cl.run_series("spot", "klines_1m", "BTCUSDT", a, files, fails, TODAY, rc, fake_lister([SPOT_M]),
                  arch.get, arch.download)
    assert arch.downloads == [] and [c[1] for c in rc.calls] == ["lsjson"]


def test_changed_md5_on_drive_rebuilds(tmp_path):
    arch = series_archive()
    a = args(tmp_path)
    files, fails = {}, {}
    cl.run_series("spot", "klines_1m", "BTCUSDT", a, files, fails, TODAY, Rclone(), fake_lister([SPOT_M]),
                  arch.get, arch.download)
    e = next(iter(files.values()))
    listing = [dict(Path=e["path"].split("BTCUSDT/", 1)[1], Size=e["bytes"], Hashes=dict(md5="different"))]
    arch.downloads.clear()
    cl.run_series("spot", "klines_1m", "BTCUSDT", a, files, fails, TODAY, Rclone(listing), fake_lister([SPOT_M]),
                  arch.get, arch.download)
    assert SPOT_M in arch.downloads


def test_failed_check_keeps_month_out_of_the_catalog(tmp_path):
    arch = series_archive()
    a = args(tmp_path)
    files, fails = {}, {}
    cl.run_series("spot", "klines_1m", "BTCUSDT", a, files, fails, TODAY, Rclone(fail={"check"}),
                  fake_lister([SPOT_M]), arch.get, arch.download)
    assert files == {} and list(fails.values())[0]["error"] == "upload or rclone check failed"


def test_checksum_failure_is_recorded_and_other_months_continue(tmp_path):
    other = "data/spot/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-2024-03.zip"
    arch = Archive({SPOT_M: make_zip(kline_csv(FEB, 10)), other: make_zip(kline_csv(FEB + 29 * 86_400_000, 10)),
                    SPOT_1D: day_zip({})}, bad_checksum=[other])
    a = args(tmp_path)
    files, fails = {}, {}
    cl.run_series("spot", "klines_1m", "BTCUSDT", a, files, fails, TODAY, Rclone(), fake_lister([SPOT_M, other]),
                  arch.get, arch.download)
    assert len(files) == 1 and "ChecksumError" in list(fails.values())[0]["error"]


def test_dry_run_touches_nothing(tmp_path):
    arch, rc = series_archive(), Rclone()
    a = args(tmp_path, dry_run=True)
    files, fails = {}, {}
    cl.run_series("spot", "klines_1m", "BTCUSDT", a, files, fails, TODAY, rc, fake_lister([SPOT_M]),
                  arch.get, arch.download)
    assert rc.calls == [] and arch.downloads == [] and files == {}


def test_status_summarises_the_catalog(tmp_path):
    arch = series_archive()
    a = args(tmp_path)
    files, fails = {}, {}
    cl.run_series("spot", "klines_1m", "BTCUSDT", a, files, fails, TODAY, Rclone(), fake_lister([SPOT_M]),
                  arch.get, arch.download)
    s = cl.status(files, fails)
    assert "spot/klines_1m/BTCUSDT" in s and "2024-02" in s and "failures: 0" in s


# ---------------------------------------------------------------- all symbols, threads, catalog from Drive

def listing_xml(prefixes, truncated=False, next_marker=None):
    body = "".join(f"<CommonPrefixes><Prefix>{p}</Prefix></CommonPrefixes>" for p in prefixes)
    nm = f"<NextMarker>{next_marker}</NextMarker>" if next_marker else ""
    return (f"<ListBucketResult><Prefix>data/futures/um/monthly/klines/</Prefix>"
            f"<IsTruncated>{'true' if truncated else 'false'}</IsTruncated>{nm}{body}</ListBucketResult>").encode()


def test_list_symbols_follows_pages_and_keeps_only_usdt():
    root = "data/futures/um/monthly/klines/"
    pages = [listing_xml([root + "AAAUSDT/", root + "BTCUSDT_230331/", root + "BTCBUSD/"], True, root + "BTCBUSD/"),
             listing_xml([root + "ZZZUSDT/"])]
    seen = []

    def get(url):
        seen.append(url)
        return pages[len(seen) - 1]

    assert cl.list_symbols("um", "klines_1m", get) == ["AAAUSDT", "ZZZUSDT"]
    assert seen[1].endswith("marker=" + root + "BTCBUSD/")


def rank_archive(volumes):
    """Fake archive for ranking: symbol folders plus a Sep-2026 1d klines zip per symbol with the given volume."""
    files = {}
    for s, v in volumes.items():
        if v is not None:
            t = 1_788_220_800_000                                        # 2026-09-01
            files[f"data/futures/um/monthly/klines/{s}/1d/{s}-1d-2026-09.zip"] = make_zip(
                f"{t},1,2,0.5,1,1,{t + 86_399_999},{v},1,1,1,0\n")
    arch = Archive(files)
    base_get = arch.get

    def get(url):
        if url.startswith(cl.LISTING):
            root = url.split("prefix=")[1].split("&")[0]
            return listing_xml([root + s + "/" for s in volumes])
        return base_get(url)

    def download(url, dest):
        if url[len(cl.ARCHIVE):] not in files:
            raise OSError("404")
        return arch.download(url, dest)

    return get, download


def test_rank_symbols_orders_by_volume_skips_expiring_and_unlisted():
    get, dl = rank_archive({"BTCUSDT": 9e9, "AAAUSDC": 5e9, "ZZZUSDT": 1e6, "BTCUSDT_261226": 8e9, "OLDUSDT": None})
    assert cl.rank_symbols("2026-09", get, dl) == [("BTCUSDT", 9e9), ("AAAUSDC", 5e9), ("ZZZUSDT", 1e6)]


def test_tier4_takes_the_top_n_of_all_symbols_and_skips_series_already_in_tiers(tmp_path):
    get, dl = rank_archive({"BTCUSDT": 9e9, "AAAUSDC": 5e9, "NEWUSDT": 3e9, "ZZZUSDT": 1e6})
    top = tmp_path / "top.csv"
    t4 = cl.tier4(3, TODAY, get, dl, str(tmp_path), str(top))
    assert {s for _, _, s in t4} == {"AAAUSDC", "NEWUSDT"}               # BTCUSDT is in tier 1; ZZZ is 4th
    assert ("um", "funding", "NEWUSDT") in t4 and ("spot", "klines_1m", "AAAUSDC") in t4
    assert top.read_text().splitlines()[2] == "1,BTCUSDT,9000000000"


def test_plan_does_not_list_daily_zips_before_since_for_a_delisted_symbol():
    calls = []

    def lister(prefix):
        calls.append(prefix)
        return ["data/spot/monthly/klines/OLDUSDT/1m/OLDUSDT-1m-2021-05.zip"] if "/monthly/" in prefix else []

    cl.plan_series("spot", "klines_1m", "OLDUSDT", TODAY, lister, since="2023-10")
    assert len([c for c in calls if "/daily/" in c]) == 37               # 2023-10 .. 2026-10, not from 2021


def test_main_runs_series_in_parallel_threads_and_writes_series_summary(tmp_path):
    eth = "data/spot/monthly/klines/ETHUSDT/1m/ETHUSDT-1m-2024-02.zip"
    eth1d = "data/spot/monthly/klines/ETHUSDT/1d/ETHUSDT-1d-2024-02.zip"
    arch = Archive({SPOT_M: make_zip(kline_csv(FEB, 1440)), SPOT_1D: day_zip({}),
                    eth: make_zip(kline_csv(FEB, 1440)), eth1d: day_zip({})})
    cat = tmp_path / "c" / "catalog.json"
    rc = Rclone()
    code = cl.main(["run", "--series", "spot/klines_1m/BTCUSDT", "--series", "spot/klines_1m/ETHUSDT",
                    "--parallel", "2", "--work", str(tmp_path / "w"), "--catalog", str(cat), "--remote", "gd:C"],
                   run=rc, lister=fake_lister([SPOT_M, eth]), get=arch.get, download=arch.download, today=TODAY)
    assert code == 0
    c = json.load(open(cat))
    assert sorted(e["symbol"] for e in c["files"]) == ["BTCUSDT", "ETHUSDT"]
    summary = (tmp_path / "c" / "catalog_series.csv").read_text().splitlines()
    assert summary[0].startswith("market,dataset,symbol") and len(summary) == 3
    assert any(c_[1] == "copyto" and c_[-2].endswith("/catalog_series.csv") for c_ in rc.calls)


def test_missing_local_catalog_is_fetched_from_drive(tmp_path):
    path = str(tmp_path / "catalog.json")

    def run(cmd, **kw):
        if cmd[1] == "copyto":
            Path(cmd[3]).write_text(json.dumps(dict(files=[dict(path="p", sources=[])], failures=[])))
        return types.SimpleNamespace(returncode=0, stdout="")

    files, fails = cl.load_catalog(path, "gd:C", run)
    assert list(files) == ["p"] and fails == {}


# ---------------------------------------------------------------- aggTrades (streamed)

AGG = "data/futures/um/monthly/aggTrades/BTCUSDT/BTCUSDT-aggTrades-2024-02.zip"


def agg_csv(n, header=False, unit=1, swap=None):
    rows = ["agg_trade_id,price,quantity,first_trade_id,last_trade_id,transact_time,is_buyer_maker"] if header else []
    ids = list(range(1, n + 1))
    if swap:
        i, j = swap
        ids[i], ids[j] = ids[j], ids[i]
    for k, i in enumerate(ids):
        rows.append(f"{i},100.5,0.01,{i * 2},{i * 2 + 1},{(FEB + k * 1000) * unit},{'true' if i % 2 else 'false'}")
    return "\n".join(rows) + "\n"


@pytest.mark.parametrize("header,unit", [(False, 1), (True, 1000)])
def test_aggtrades_are_streamed_in_chunks_with_ms_times(tmp_path, header, unit):
    arch = Archive({AGG: make_zip(agg_csv(50, header, unit))})
    out = str(tmp_path / "a.parquet")
    qa = cl.stream_aggtrades([AGG], str(tmp_path), out, cl.month_bounds("2024-02"), arch.get, arch.download, chunk=7)
    t = pq.read_table(out)
    assert t.schema == cl.AGG_SCHEMA and t.num_rows == 50 == qa["rows"]
    assert t.column("transact_time")[0].as_py() == FEB and qa["first_ts"] == FEB
    assert qa["ts_unit_fixed"] == (50 if unit == 1000 else 0) and qa["ok"]
    assert t.column("is_buyer_maker").to_pylist()[:2] == [True, False]


def test_aggtrades_order_breaks_are_counted_across_chunks(tmp_path):
    arch = Archive({AGG: make_zip(agg_csv(20, swap=(6, 7)))})          # rows 6/7 straddle the chunk boundary
    qa = cl.stream_aggtrades([AGG], str(tmp_path), str(tmp_path / "a.parquet"), cl.month_bounds("2024-02"),
                             arch.get, arch.download, chunk=7)
    assert qa["id_not_increasing"] == 1 and not qa["ok"]


def test_failed_aggtrades_month_leaves_no_partial_file(tmp_path):
    other = AGG.replace("2024-02.zip", "2024-02-02.zip")
    arch = Archive({AGG: make_zip(agg_csv(5)), other: make_zip(agg_csv(5))}, bad_checksum=[other])
    with pytest.raises(cl.ChecksumError):
        cl.build_month("um", "aggtrades", "BTCUSDT", "2024-02", [AGG, other], str(tmp_path), TODAY,
                       get=arch.get, download=arch.download)
    assert not list(tmp_path.rglob("*.parquet")) and not list(tmp_path.rglob("*.zip"))


def test_aggtrades_month_builds_a_catalog_entry(tmp_path):
    arch = Archive({AGG: make_zip(agg_csv(10))})
    e = cl.build_month("um", "aggtrades", "BTCUSDT", "2024-02", [AGG], str(tmp_path), TODAY,
                       get=arch.get, download=arch.download)
    assert e["rows"] == 10 and e["path"].startswith("futures_um/aggtrades/BTCUSDT/2024/") and e["ok"]
