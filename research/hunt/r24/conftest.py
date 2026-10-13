"""R24 tests reuse the brain's fixture cube (the committed 4-session BANKNIFTY slice of the research-data release)."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from research.brain.tests.conftest import adapter, built  # noqa: E402,F401
