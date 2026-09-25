"""Shared helpers for the options-lab fixture generators."""
import builtins
import contextlib
import datetime as _dt
import json
import math
import os

import env  # noqa: F401  (sandboxes IraAlgo's import-time side effects)

OUT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # resources/options/
os.makedirs(OUT, exist_ok=True)
IST = _dt.timezone(_dt.timedelta(hours=5, minutes=30))


def frozen_datetime(now_ist: _dt.datetime):
    """A datetime subclass whose now() is pinned to an IST instant."""

    class Frozen(_dt.datetime):
        @classmethod
        def now(cls, tz=None):
            if tz is None:  # naive "local" time: the server is assumed to run in IST
                w = now_ist.astimezone(IST)
                return cls(w.year, w.month, w.day, w.hour, w.minute, w.second, w.microsecond)
            return cls.fromtimestamp(now_ist.timestamp(), tz)

    return Frozen


@contextlib.contextmanager
def patched(obj, name, value):
    missing = object()
    old = getattr(obj, name, missing)
    setattr(obj, name, value)
    try:
        yield
    finally:
        if old is missing:
            delattr(obj, name)
        else:
            setattr(obj, name, old)


def clean(x):
    """JSON-safe: tuples to lists, non-finite floats to strings the Kotlin reader understands."""
    if isinstance(x, float):
        if math.isnan(x):
            return "NaN"
        if math.isinf(x):
            return "Infinity" if x > 0 else "-Infinity"
        return x
    if isinstance(x, dict):
        return {str(k): clean(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [clean(v) for v in x]
    if hasattr(x, "item"):
        return clean(x.item())
    return x


def dump(name, obj):
    with open(os.path.join(OUT, name), "w") as f:
        json.dump(clean(obj), f, indent=1, sort_keys=False)
    print("wrote", name)


identity_round = lambda x, n=None: x if n is not None else builtins.round(x)  # noqa: E731
