"""publish_gdrive groups lake files by dataset, writes a manifest, and copies then checks each dataset."""
import json, os, sys, types

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import publish_gdrive as pg


def make_lake(tmp_path, files):
    os.makedirs(tmp_path / "_qa")
    for name, ds, rows in files:
        (tmp_path / name).write_bytes(b"x" * rows)
        json.dump(dict(file=name, dataset=ds, sha256="h" + name, bytes=rows, qa=dict(rows=rows)),
                  open(tmp_path / "_qa" / f"{name}.json", "w"))
    return str(tmp_path)


class Runner:
    def __init__(self, fail_on=()):
        self.calls, self.fail_on = [], fail_on

    def __call__(self, cmd):
        files = open(cmd[cmd.index("--files-from") + 1]).read().split() if "--files-from" in cmd else []
        self.calls.append((cmd, files))
        return types.SimpleNamespace(returncode=1 if any(f in cmd for f in self.fail_on) else 0)


def test_files_are_grouped_by_dataset_and_missing_files_are_skipped(tmp_path):
    lake = make_lake(tmp_path, [("a_2024.parquet", "index_1m", 3), ("a_2025.parquet", "index_1m", 4),
                                ("d.parquet", "index_daily", 5), ("gone.parquet", "index_daily", 1)])
    os.remove(os.path.join(lake, "gone.parquet"))

    g = pg.lake_files(lake)

    assert {k: [e["file"] for e in v] for k, v in g.items()} == {
        "index_1m": ["a_2024.parquet", "a_2025.parquet"], "index_daily": ["d.parquet"]}


def test_manifest_lists_path_rows_sha_and_bytes():
    m = pg.manifest({"index_daily": [dict(file="d.parquet", rows=5, sha256="abc", bytes=9)]})

    assert m.splitlines()[1] == "index_daily/d.parquet\t5\tabc\t9"


def test_each_dataset_is_copied_then_checked_into_its_own_folder(tmp_path):
    lake = make_lake(tmp_path, [("a.parquet", "index_1m", 3), ("d.parquet", "index_daily", 5)])
    run = Runner()

    rc = pg.main([lake, "--remote", "gd:L"], run=run)

    assert rc == 0
    steps = [(c[1], c[3], f) for c, f in run.calls]
    assert steps[:4] == [("copy", "gd:L/index_1m", ["a.parquet"]), ("check", "gd:L/index_1m", ["a.parquet"]),
                         ("copy", "gd:L/index_daily", ["d.parquet"]), ("check", "gd:L/index_daily", ["d.parquet"])]
    assert "--checksum" in run.calls[0][0] and "--one-way" in run.calls[1][0]
    assert steps[4][:2] == ("copyto", "gd:L/MANIFEST.txt")
    assert os.path.exists(os.path.join(lake, "MANIFEST.txt"))


def test_a_failed_copy_skips_the_check_reports_failure_and_continues(tmp_path):
    lake = make_lake(tmp_path, [("a.parquet", "index_1m", 3), ("d.parquet", "index_daily", 5)])
    run = Runner(fail_on=("gd:L/index_1m",))

    rc = pg.main([lake, "--remote", "gd:L"], run=run)

    assert rc == 1
    assert [c[1] for c, _ in run.calls] == ["copy", "copy", "check", "copyto"]


def test_only_selected_datasets_are_uploaded_and_unknown_ones_refused(tmp_path):
    lake = make_lake(tmp_path, [("a.parquet", "index_1m", 3), ("d.parquet", "index_daily", 5)])
    run = Runner()

    assert pg.main([lake, "--remote", "gd:L", "--dataset", "index_daily"], run=run) == 0
    assert {c[3] for c, _ in run.calls if c[1] == "copy"} == {"gd:L/index_daily"}
    try:
        pg.main([lake, "--dataset", "nope"], run=run)
        raise AssertionError("expected SystemExit")
    except SystemExit as e:
        assert "nope" in str(e)


def test_extra_folders_are_uploaded_as_they_are_and_listed_with_sha256(tmp_path):
    lake = make_lake(tmp_path / "lake", [("d.parquet", "index_daily", 5)])
    bhav = tmp_path / "bhav"
    os.makedirs(bhav / "idx")
    (bhav / "2024-01-01.csv.zip").write_bytes(b"PK")
    (bhav / "idx" / "2024-01-01.csv").write_bytes(b"")
    run = Runner()

    assert pg.main([lake, "--remote", "gd:L", "--extra", f"nse_bhavcopy={bhav}"], run=run) == 0

    copy = [(c, f) for c, f in run.calls if c[1] == "copy" and c[3] == "gd:L/nse_bhavcopy"][0]
    assert copy[0][2] == str(bhav) and copy[1] == ["2024-01-01.csv.zip", "idx/2024-01-01.csv"]
    man = open(os.path.join(lake, "MANIFEST.txt")).read()
    empty_sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    assert f"nse_bhavcopy/idx/2024-01-01.csv\t-\t{empty_sha}\t0" in man
