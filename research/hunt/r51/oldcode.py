"""Load modules of earlier studies by file path, so R51 reuses their signal code unchanged instead of copying it.

Many study folders hold modules with the same names (engine.py, sim.py, data.py, ...). While one study's module runs
its imports, its own folder comes first on sys.path and any same-named module already loaded from elsewhere is hidden;
afterwards those shared names are removed again, so no study ever imports another study's (or R51's) file. Modules
with a name unique across research/hunt keep their plain name (pickles of the old study refer to it, e.g.
r27data.Night)."""
from __future__ import annotations

import importlib.util
import sys
from functools import lru_cache
from pathlib import Path

import r51cfg as K

HUNT = K.ROOT / "research" / "hunt"
_MODS: dict = {}


@lru_cache(maxsize=1)
def _shared_names() -> frozenset:
    seen, shared = set(), set()
    for p in HUNT.glob("*/*.py"):
        (shared if p.stem in seen else seen).add(p.stem)
    return frozenset(shared)


def _from(folder: Path, mod) -> bool:
    f = getattr(mod, "__file__", None) or ""
    return Path(f).parent == folder


def load(study: str, module: str):
    key = f"{study}.{module}"
    if key in _MODS:
        return _MODS[key]
    folder = HUNT / study
    shared = _shared_names()
    hidden = {k: sys.modules.pop(k) for k in list(shared) if k in sys.modules and not _from(folder, sys.modules[k])}
    sys.path.insert(0, str(folder))
    name = f"_r51old_{study}_{module}"
    spec = importlib.util.spec_from_file_location(name, folder / f"{module}.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod                   # dataclasses look their module up here
    if module not in shared:
        sys.modules[module] = mod             # the plain name, for the old study's pickles
    try:
        spec.loader.exec_module(mod)
    finally:
        sys.path.remove(str(folder))
        if str(folder) not in sys.path:
            sys.path.append(str(folder))      # late imports of unique names still resolve; R51 keeps priority
        for k in list(shared):
            if k in sys.modules and _from(folder, sys.modules[k]):
                sys.modules[f"_r51old_{study}_{k}"] = sys.modules.pop(k)
        sys.modules.update(hidden)
    _no_numba_cache(mod)
    _MODS[key] = mod
    return mod


def _no_numba_cache(mod):
    """numba's on-disk cache keys a function by its module's name; a module loaded under a made-up name cannot reload
    it, so the old study's jitted functions compile in memory instead (results identical)."""
    try:
        from numba.core.caching import NullCache
        from numba.core.registry import CPUDispatcher
    except ImportError:
        return
    for v in vars(mod).values():
        if isinstance(v, CPUDispatcher):
            v._cache = NullCache()
