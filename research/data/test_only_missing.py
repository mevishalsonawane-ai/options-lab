"""lake_fetch --only-missing: published option files become raw chunks, so only the rest is requested."""
import datetime as dt, hashlib, json, os, subprocess, sys

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dhan_client as dh
import lake_fetch as lf
import make_catalog as mc

HERE = os.path.dirname(os.path.abspath(__file__))


def jobs(raw, und="ABC", offs=(0, 1), starts=("2021-08-01", "2021-08-31", "2021-09-30")):
    J = []
    for off in offs:
        for side in "CP":
            for a in starts:
                b = (dt.date.fromisoformat(a) + dt.timedelta(days=29)).isoformat()
                J.append(lf.Job(f"{raw}/stkopt/{und}/MONTH/c1/{off:+03d}{side}/{a}", "/charts/rollingoption",
                                dict(fromDate=a, toDate=b), key="ce"))
    return J


def published(lake, und="ABC"):
    """ATM call and put with rows in the first two windows; ATM+1 call only in the first window."""
    ts = pd.to_datetime(["2021-08-02 09:15", "2021-08-02 09:16", "2021-09-01 10:00", "2021-10-03 09:15"]).tz_localize(
        "Asia/Kolkata")
    rows = []
    for off, side, idx in ((0, "C", [0, 1, 2]), (0, "P", [0, 2]), (1, "C", [0])):
        for i in idx:
            rows.append(dict(offset=off, side=side, open=1.0 + i, high=2.0, low=0.5, close=1.5, iv=20.0, volume=10,
                             strike=100.0, oi=5, spot=99.0, ts=ts[i]))
    # the last published day (3 Oct) is inside the third window: that window must be fetched again
    rows.append(dict(rows[0], ts=ts[3]))
    df = pd.DataFrame(rows)
    df["side"] = df["side"].astype("category")
    os.makedirs(f"{lake}/_qa", exist_ok=True)
    df.to_parquet(f"{lake}/stkopt_{und}_MONTH.parquet")
    json.dump({"qa": {"rows_dropped_series_total": {"outside_session": 7},
                      "duplicates_dropped_series_total": {"identical": 2, "conflicting_removed": 0}}},
              open(f"{lake}/_qa/stkopt_{und}_MONTH.parquet.json", "w"))
    return df


def test_seed_writes_only_covered_chunks_and_keeps_the_rows(tmp_path):
    raw, lake = str(tmp_path / "raw"), str(tmp_path / "lake")
    pub = published(lake)
    J = jobs(raw)
    st = lf.seed(raw, lake, "stkopt", J)
    assert st == {"seeded": 5, "missing": 7}
    left = lf.todo(J)
    assert len(left) == 7
    done = {j.path.split("/c1/")[1] for j in J if j.done()}
    assert done == {"+00C/2021-08-01", "+00C/2021-08-31", "+00P/2021-08-01", "+00P/2021-08-31", "+01C/2021-08-01"}
    ch = pd.read_parquet(f"{raw}/stkopt/ABC/MONTH/c1/+00C/2021-08-01.parquet")
    assert list(ch.columns) == ["timestamp", *lf.RD]
    back = pd.to_datetime(ch.timestamp, unit="s", utc=True).dt.tz_convert("Asia/Kolkata")
    assert list(back) == list(pub.ts[:2]) and list(ch.open) == [1.0, 2.0]
    sq = json.load(open(f"{raw}/stkopt/ABC/MONTH/seed_qa.json"))
    assert sq["rows_dropped"] == {"outside_session": 7} and sq["duplicates_dropped"]["identical"] == 2
    # seeding again changes nothing; the run then only asks for the missing chunks
    assert lf.seed(raw, lake, "stkopt", J) == {"seeded": 0, "missing": 7}


def test_seed_without_published_file_leaves_everything_to_fetch(tmp_path):
    raw, lake = str(tmp_path / "raw"), str(tmp_path / "lake")
    os.makedirs(lake)
    J = jobs(raw)
    assert lf.seed(raw, lake, "stkopt", J) == {"seeded": 0, "missing": len(J)}


def test_before_and_since_select_windows_by_their_last_day(tmp_path):
    J = jobs(str(tmp_path))
    assert len(lf.todo(J, dt.date(2021, 10, 29))) == 8   # the third window ends on 29 Oct
    assert len(lf.todo(J, dt.date(2021, 10, 30))) == 12
    assert len(lf.todo(J, since=dt.date(2021, 10, 29))) == 4   # only the third window
    assert len(lf.todo(J, dt.date(2021, 10, 29), dt.date(2021, 9, 29))) == 4


def test_build_counts_the_seeded_files_dropped_rows(tmp_path):
    raw, lake, out = str(tmp_path / "raw"), str(tmp_path / "lake"), str(tmp_path / "out")
    published(lake)
    lf.seed(raw, lake, "stkopt", jobs(raw))
    days = pd.to_datetime(["2021-08-02", "2021-09-01", "2021-10-03"]).tz_localize("Asia/Kolkata")
    os.makedirs(f"{raw}/idx_daily/NIFTY")
    pd.DataFrame({"timestamp": (days - pd.Timestamp(0, tz="UTC")) // pd.Timedelta(seconds=1), "open": 1.0, "high": 1.0,
                  "low": 1.0, "close": 1.0}).to_parquet(f"{raw}/idx_daily/NIFTY/all.parquet")
    subprocess.run([sys.executable, "-I", f"{HERE}/lake_build.py", raw, out, "stkopt"], check=True)
    m = json.load(open(f"{out}/_qa/stkopt_ABC_MONTH.parquet.json"))
    assert m["qa"]["rows"] == 6        # 3 + 2 + 1 seeded rows; the 3 Oct row's window was not seeded
    assert m["qa"]["rows_dropped_series_total"]["outside_session"] == 7
    assert m["qa"]["duplicates_dropped_series_total"]["identical"] == 2


def test_pacer_pauses_grow_and_reset_after_a_success():
    p, slept = dh.Pacer(0), []
    for _ in range(4):
        p.limited = p.BREAK_N
        p.wait(sleep=slept.append)
    assert slept == [600, 1200, 2400, 3600]
    p.ok()
    p.limited = p.BREAK_N
    p.wait(sleep=slept.append)
    assert slept[-1] == 600


def test_restore_qa_from_catalog(tmp_path):
    lake = str(tmp_path)
    pd.DataFrame({"a": [1]}).to_parquet(f"{lake}/x.parquet")
    sha = hashlib.sha256(open(f"{lake}/x.parquet", "rb").read()).hexdigest()
    cat = {"files": [dict(file="x.parquet", dataset="d", rows=1, first="f", last="l", bytes=1, sha256=sha,
                          source="s", columns=["a"], qa={"weekend_rows": 0}),
                     dict(file="y.parquet", dataset="d", rows=1, first="f", last="l", bytes=1, sha256="0",
                          source="s", columns=["a"], qa={})]}
    assert mc.restore_qa(lake, cat) == 1
    m = json.load(open(f"{lake}/_qa/x.parquet.json"))
    assert m["dataset"] == "d" and m["qa"] == {"weekend_rows": 0, "rows": 1, "first": "f", "last": "l"}
