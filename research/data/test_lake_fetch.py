"""lake_fetch stops a group (and the run) once Dhan refuses many requests in a row (DH-905 cap, expired token)."""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lake_fetch as lf


class FakeJob:
    def __init__(self, path, result):
        self.path, self.result, self.ran = path, result, False

    def run(self):
        self.ran = True
        time.sleep(0.01)  # a real request takes far longer
        return self.result


def test_fetch_stops_after_cap_n_dh905_in_a_row(tmp_path, monkeypatch):
    monkeypatch.setattr(lf, "CAP_N", 3)
    J = [FakeJob(str(tmp_path / f"j{i}"), ("err", "HTTP 400 {'errorCode': 'DH-905'}")) for i in range(100)]
    st = lf.fetch(str(tmp_path), "g", J, 1)
    assert st["capped"] and st["err"] >= 3
    assert sum(j.ran for j in J) < 10      # the rest were cancelled, not sent


def test_other_errors_and_successes_do_not_trip_the_breaker(tmp_path, monkeypatch):
    monkeypatch.setattr(lf, "CAP_N", 3)
    res = [("err", "HTTP 400 DH-905"), ("err", "HTTP 400 DH-905"), ("ok", 5)] * 4 + [("err", "HTTP -1 429")] * 5
    J = [FakeJob(str(tmp_path / f"j{i}"), r) for i, r in enumerate(res)]
    st = lf.fetch(str(tmp_path), "g", J, 1)
    assert "capped" not in st and st == {"ok": 4, "empty": 0, "err": 13}
    assert all(j.ran for j in J)


def test_expired_token_also_stops_the_run(tmp_path, monkeypatch):
    monkeypatch.setattr(lf, "CAP_N", 3)
    J = [FakeJob(str(tmp_path / f"j{i}"), ("err", "HTTP 401 {'errorCode': 'DH-901'}")) for i in range(100)]
    st = lf.fetch(str(tmp_path), "g", J, 1)
    assert st["capped"] and sum(j.ran for j in J) < 10
