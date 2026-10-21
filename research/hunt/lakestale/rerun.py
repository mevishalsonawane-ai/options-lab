"""Stale repeated prints: re-run the studies' own code on the stores rebuilt with lakeclean step 3 (PREREG.md, "What will
be measured" 2). Research only. 18 Oct 2026.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                 # Amendment A stores  (mode clean)
    R51_CLEAN=1 R51_STALE=1 python research/hunt/r51/r51data.py build     # with step 3         (mode stale)
    python research/hunt/lakestale/rerun.py MODE STUDY PHASE

    MODE   clean | stale   (clean re-runs the committed code on the Amendment A stores, to show it reproduces the
                            committed results here; stale is the measurement)
    STUDY  r51 (Amendment A: the passing rule and its 6 near-misses) | r72 | r73 | r76 | r77
    PHASE  design | final  (r51: final only; it runs Amendment A's design and final together)

Every study runs its own, unchanged functions; only the folders change. Results go to
$LAKESTALE_OUT/<mode>/results/<study>/ (default /dev/shm/out/lakestale) and trade caches beside them, never to the
studies' committed results/ folders. A study's FINAL_READ guard is kept by the study itself: these re-runs re-price a
final period that was already read, with the same rules, grids, seeds, twins and gates (PREREG.md), and their own copy
of the guard lives in the scratch results folder. R77's code is on branch claude/r77-absorption; point $R77_DIR at a
checkout of its research/hunt/r77 (default /home/user/r77wt/research/hunt/r77). Its shared modules (r51, r64, r71) are
taken from this tree, which differs from that branch only by the opt-in stale step.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
MODES, STUDIES, PHASES = ("clean", "stale"), ("r51", "r72", "r73", "r76", "r77"), ("design", "final")


def setup(mode: str, study: str) -> Path:
    """Environment and folders for one run; returns the scratch results folder. Must run before any study import."""
    root = Path(os.environ.get("LAKESTALE_OUT", "/dev/shm/out/lakestale")) / mode
    os.environ["R51_CLEAN"] = "1"
    os.environ["R51_STALE"] = "1" if mode == "stale" else "0"
    os.environ.setdefault("R51_OUT", "/dev/shm/out/r51")
    for s in ("r72", "r73", "r76", "r77"):
        os.environ[f"{s.upper()}_OUT"] = str(root / "trades" / s)
    res = root / "results" / study
    res.mkdir(parents=True, exist_ok=True)
    for p in (HUNT / "r51", HUNT / "r71"):
        sys.path.insert(0, str(p))
    import r51cfg as K                       # this tree's, so the stale switch is honoured everywhere
    assert K.CLEAN and K.STALE == (mode == "stale"), (K.CLEAN, K.STALE)
    assert Path(K.__file__).resolve().parent == HUNT / "r51"
    assert K.OUT.name == ("clean_stale" if mode == "stale" else "clean"), K.OUT
    return res


def _fresh_flag(flag: Path):
    """The scratch copy of a study's FINAL_READ guard (the study writes it again when it reads)."""
    if flag.exists():
        flag.unlink()


def run_r51(res: Path, phase: str):
    if phase != "final":
        raise SystemExit("r51: Amendment A's `run` does design and final together; use PHASE final")
    import amend
    amend.OUT_JSON = res / "amend.json"
    amend.check_key = lambda check_git=True: None      # the committed key guards the ONE Amendment A read; this re-run
    amend.run()                                        # writes only to the scratch folder


def run_r72(res: Path, phase: str):
    sys.path.insert(0, str(HUNT / "r72"))
    import r72cfg as R
    import r72study as M
    M.use_r72()
    R.RESULTS, R.FINAL_FLAG, R.OUT = res, res / "FINAL_READ", Path(os.environ["R72_OUT"])
    if phase == "final":
        _fresh_flag(R.FINAL_FLAG)
    getattr(M.S, phase)()


def run_r73(res: Path, phase: str):
    sys.path.insert(0, str(HUNT / "r73"))
    import r73cfg as R
    import r73study as M
    R.RESULTS, R.FINAL_FLAG, R.OUT = res, res / "FINAL_READ", Path(os.environ["R73_OUT"])
    if phase == "final":
        _fresh_flag(R.FINAL_FLAG)
    getattr(M, phase)()


def run_r76(res: Path, phase: str):
    sys.path.insert(0, str(HUNT / "r76"))
    import r76study as M
    M.RESULTS, M.FINAL_FLAG, M.OUT = res, res / "FINAL_READ", Path(os.environ["R76_OUT"])
    if phase == "final":
        _fresh_flag(M.FINAL_FLAG)
        (res / "final.json").unlink(missing_ok=True)
        M.FINAL_FLAG.write_text("lakestale re-price of a final already read\n")
    M.run(phase)
    if phase == "final":
        M.summarise()


def run_r77(res: Path, phase: str):
    d = Path(os.environ.get("R77_DIR", "/home/user/r77wt/research/hunt/r77")).resolve()
    import r71cfg  # noqa: F401  (this tree's r71, loaded before r77study puts the other checkout's r71 on the path)
    import r71study  # noqa: F401
    sys.path.insert(0, str(d))
    import r77study as M
    assert Path(M.S.__file__).resolve().parent == HUNT / "r71"
    M.RESULTS, M.FINAL_FLAG, M.OUT = res, res / "FINAL_READ", Path(os.environ["R77_OUT"])
    if phase == "final":
        _fresh_flag(M.FINAL_FLAG)
    getattr(M, phase)()


def main(argv):
    if len(argv) != 3 or argv[0] not in MODES or argv[1] not in STUDIES or argv[2] not in PHASES:
        raise SystemExit(__doc__)
    mode, study, phase = argv
    res = setup(mode, study)
    globals()[f"run_{study}"](res, phase)
    K = sys.modules["r51cfg"]
    assert K.STALE == (mode == "stale") and Path(K.__file__).resolve().parent == HUNT / "r51"
    print("done", mode, study, phase, "->", res, flush=True)


if __name__ == "__main__":
    main(sys.argv[1:])
