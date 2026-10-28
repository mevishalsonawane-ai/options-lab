"""Tests for research/data/read_diagnostics.py: it reads the sample the phone's schema test checks (DiagDayTest)."""
from __future__ import annotations

import gzip
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import read_diagnostics as rd  # noqa: E402

SAMPLE = Path(__file__).resolve().parent / "diag_sample.json"
SAMPLE_V1 = Path(__file__).resolve().parent / "diag_sample_v1.json"


@pytest.fixture()
def data() -> dict:
    return json.loads(SAMPLE.read_text(encoding="utf-8"))


def test_reads_the_sample_gzipped_and_plain(tmp_path, data):
    gz = tmp_path / "diag-2026-10-13.json.gz"
    gz.write_bytes(gzip.compress(SAMPLE.read_bytes()))
    assert rd.load(gz) == data
    assert rd.load(SAMPLE) == data
    assert rd.day_of(gz.name) == "2026-10-13"
    assert rd.day_of("diag-2026-10-13.json.gz.new") is None
    assert rd.day_of("live-2026-10-13.irlive") is None


def test_arm_rows_today_and_all_days(data):
    rows = {r["arm"]: r for r in rd.arm_rows(data)}
    liq = rows["Liquidity 15+5"]
    assert (liq["signals"], liq["taken"], liq["skipped"], liq["exits"]) == (3, 1, 2, 2)
    assert liq["points_today"] == 20.0 and liq["avg_pct_today"] == 2.5
    assert liq["trades_all_days"] == 3
    assert rows["ORB"]["state"] == "parked"
    assert rows["ORB"]["avg_pct_today"] is None


def test_skips_by_arm_and_reason(data):
    sk = rd.skips(data)
    assert sk[("ORB", "kill_switch")] == 1
    assert sk[("Liquidity 15m", "liquidity")] == 1
    assert sk[("Liquidity 5m", "day_lock")] == 1
    assert ("ORB Sweep", "no_signal") not in sk     # waiting, not a skipped signal


def test_summary_mentions_the_day_arms_and_health(data):
    s = rd.summary(data)
    assert "2026-10-13" in s and "Liquidity 15+5: armed" in s
    assert "stream drops 2" in s and "sync failed 1, skipped 1" in s
    assert "changes: 2 (1 by Boss)" in s


def test_no_rupee_or_id_field_in_the_sample(data):
    keys: set[str] = set()

    def walk(x):
        if isinstance(x, dict):
            for k, v in x.items():
                keys.add(k)
                walk(v)
        elif isinstance(x, list):
            for v in x:
                walk(v)

    walk(data)
    assert not {k for k in keys if "rupee" in k or "pnl" in k or k in {"id", "order_id", "account_id", "client_id"}}


@pytest.mark.parametrize("leak", [
    "github_pat_11ABCDEFG0a1b2c3d4e5",
    "eyJhbGciOiJIUzI1NiJ9.x",
    "order 251013000123456",
    "loss ₹ 40,000",
    "Rs 500 a lot",
    "boss@example.com",
])
def test_refuses_a_file_with_a_secret_or_rupees(data, leak):
    data["arms"][0]["status"] = leak
    with pytest.raises(rd.DiagError):
        rd.check(data)


def test_a_numeric_month_weekly_symbol_is_not_a_secret(data):
    # NIFTY2510725000PE (a January-September weekly) has a 10-digit run inside the symbol: kept on the phone, read here.
    data["orders"][0]["symbol"] = "NIFTY2510725000PE"
    data["decisions"][0]["verdict"] = "entered NIFTY2510725000PE"
    rd.check(data)


def test_refuses_other_files_and_schemas(data):
    with pytest.raises(rd.DiagError):
        rd.check({"kind": "something else"})
    with pytest.raises(rd.DiagError):
        rd.check({**data, "schema": 3})
    bad = dict(data)
    del bad["timeline"]
    with pytest.raises(rd.DiagError):
        rd.check(bad)


def test_command_line(tmp_path, capsys):
    gz = tmp_path / "diag-2026-10-13.json.gz"
    gz.write_bytes(gzip.compress(SAMPLE.read_bytes()))
    assert rd.main([str(gz)]) == 0
    assert "Liquidity 15+5" in capsys.readouterr().out
    assert rd.main(["--json", str(gz)]) == 0
    assert json.loads(capsys.readouterr().out)["day"] == "2026-10-13"
    bad = tmp_path / "x.json"
    bad.write_text("{}")
    assert rd.main([str(bad)]) == 1


def test_reads_schema_1_still(tmp_path):
    old = rd.load(SAMPLE_V1)
    assert old["schema"] == 1
    sev = rd.severities(old)
    assert sev["errors"] == {"missing required field: quantity": 2, "order # failed": 1}
    assert "health: 3 errors in 2 kinds, 0 warnings, 0 notices" in rd.summary(old)


def test_schema_2_severities_are_read_as_written(data):
    sev = rd.severities(data)
    assert sum(sev["warnings"].values()) == 1 and sum(sev["notices"].values()) == 1
    assert "3 errors in 2 kinds, 1 warnings, 1 notices" in rd.summary(data)
    assert data["health"]["stream"]["times"] == ["11:00:00", "11:30:00"]


def test_a_schema_1_file_of_19_oct_is_sorted_by_severity():
    # The kinds of Boss's 19 Oct file (schema 1): 34 "errors", of which only Zerodha's failing requests are errors.
    errors = {
        "live price stream dropped (no data from zerodha for # s); reconnecting": 13,
        "problem: zerodha's requests: zerodha's requests are failing (# of the last #). i": 7,
        "that passphrase is too easy to guess: make it longer, or mix words, digits and s": 5,
        "live price stream dropped (the phone's network changed); reconnecting": 2,
        "short put is in the money: nifty # is below the # strike. the loss is unbounded ": 2,
        "good morning boss · # things need you: ✓ zerodha logged in for today ✓ nse holid": 1,
        "daily loss limit reached: today's paper p&l rs -# reached the rs # daily loss li": 1,
        "index nearing your breakeven: nifty # is #% above the # breakeven of the # put.": 1,
        "day report · mon # oct: liquidity #+#: # trades · −₹# what i learned today: # co": 1,
    }
    data = json.loads(SAMPLE_V1.read_text(encoding="utf-8"))
    data["health"]["errors"] = errors
    sev = rd.severities(data)
    assert sev["errors"] == {"problem: zerodha's requests: zerodha's requests are failing (# of the last #). i": 7}
    assert sum(sev["warnings"].values()) == 19
    assert sum(sev["notices"].values()) == 7


@pytest.mark.parametrize("kind,want", [
    ("", "error"), ("  Good Morning Boss", "notice"), ("positions not protected", "error"),
    ("short put is in the money", "warning"), ("problem: the live price stream", "error"),
])
def test_classify_edges(kind, want):
    assert rd.classify(kind) == want


def test_time_machine_rows_since_28_oct():
    """28 Oct (shadow logging): the time machine's checks; an older file has none."""
    data = rd.load(SAMPLE)
    rows = rd.time_machine_rows(data)
    assert len(rows) == 1 and rows[0]["day"] == data["day"] and rows[0]["verdict"] == "coin flip"
    assert "ref" not in rows[0] and rows[0]["stop_points"] == 61.23
    assert "time machine: 1 checks (coin flip 1)" in rd.summary(data)
    assert rd.time_machine_rows(rd.load(SAMPLE_V1)) == []
