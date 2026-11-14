#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then
  echo 'Usage: bash research/neural_v2/run.sh /absolute/path/to/lake' >&2
  exit 2
fi
research_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$research_root"
python -m pytest research/neural_v2/test_pipeline.py -q
for attempt in 1 2 3; do
  if python -m research.neural_v2.pipeline --lake "$1"; then
    exit 0
  fi
  echo "Research attempt $attempt failed; matching epoch checkpoints will resume." >&2
done
exit 1
