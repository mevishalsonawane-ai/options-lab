"""stkopt_coverage counts finished chunks per stock and offset, CALL and PUT together."""
import os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lake_fetch as lf
import stkopt_coverage as sc


def test_fraction_per_stock_and_offset(tmp_path):
    raw = str(tmp_path)
    jobs = [lf.Job(f"{raw}/stkopt/{s}/MONTH/c1/{o}{side}/{w}", "/x", {})
            for s in ("AAA", "BBB") for o in ("-01", "+00", "+01") for side in "CP" for w in ("2021-08-01", "2021-08-31")]
    done = {f"{raw}/stkopt/AAA/MONTH/c1/+00C/2021-08-01": ".parquet", f"{raw}/stkopt/AAA/MONTH/c1/+00P/2021-08-01": ".empty",
            f"{raw}/stkopt/AAA/MONTH/c1/+00C/2021-08-31": ".err", f"{raw}/stkopt/BBB/MONTH/c1/-01P/2021-08-31": ".parquet"}
    for p, ext in done.items():
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p + ext, "w").close()
    cov = sc.coverage(jobs)
    assert cov == {"AAA": {"-1": 0.0, "0": 0.5, "1": 0.0}, "BBB": {"-1": 0.25, "0": 0.0, "1": 0.0}}
    assert list(cov["AAA"]) == ["-1", "0", "1"]
