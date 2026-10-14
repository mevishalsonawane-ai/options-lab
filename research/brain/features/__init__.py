"""L4 feature store: definitions (one module per family), point-in-time compute, incremental Parquet store."""
from __future__ import annotations

from .registry import PENDING, REGISTRY, Ctx, Feature, compute, feature, pending, signature  # noqa: F401


def load_all():
    from . import (breadth, calendar_f, candles, gex_f, indicators, levels_f, market, options,  # noqa: F401
                   options_x, price, profile_f, structure, volume)
    return REGISTRY
