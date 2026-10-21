"""The IraAlgo data repository's files staged in tools/iraalgo-data-repo (13 Oct review): its workflow pins the checker and
its tests by sha256, so a phone token (Contents write, never Workflows) cannot change the code the nightly job runs. These
hashes must match the files, or the nightly job refuses to run."""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

STAGE = Path(__file__).resolve().parents[3] / "tools" / "iraalgo-data-repo"


def test_the_workflow_pins_the_checker_it_ships_with():
    yml = (STAGE / ".github" / "workflows" / "verify-uploads.yml").read_text()
    pins = dict((name, h) for h, name in re.findall(r'"([0-9a-f]{64})" (\S+)', yml))
    assert set(pins) == {"verify_uploads.py", "tests/test_verify_uploads.py"}
    for name, h in pins.items():
        assert hashlib.sha256((STAGE / name).read_bytes()).hexdigest() == h, f"update the pinned hash of {name}"


def test_no_passphrase_secret_and_never_options_lab():
    yml = (STAGE / ".github" / "workflows" / "verify-uploads.yml").read_text()
    assert "PASSPHRASE" not in yml
    assert "push --force" not in yml and "push -f" not in yml
