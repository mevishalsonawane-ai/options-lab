"""Strategy = signal function + parameter grids (signal params x strike rules x exit sets) + execution + position rules."""
from __future__ import annotations

import itertools
from dataclasses import dataclass, field
from typing import Callable

from ..engine import Execution, Exits, StrikeRule


@dataclass
class Variant:
    vid: str             # '<strategy>|s<i>|r<j>|x<k>'
    strategy: str
    si: int
    ri: int
    xi: int
    sig: dict
    rule: StrikeRule
    exits: Exits

    def label(self):
        sp = ",".join(f"{k}={v}" for k, v in self.sig.items())
        return f"{self.strategy} [{sp}] {self.rule.label()} {self.exits.label()}"


@dataclass
class Strategy:
    name: str
    signal_fn: Callable                    # (mk, **sig_params) -> signals DataFrame (see engine.py)
    sig_grid: list = field(default_factory=lambda: [{}])
    rules: list = field(default_factory=lambda: [StrikeRule()])
    exits: list = field(default_factory=lambda: [Exits()])
    exe: Execution = field(default_factory=Execution)
    pos: dict = field(default_factory=lambda: dict(one_at_a_time=True))
    window: tuple | None = None            # entry-minute window for the random-entry baseline (default: the signals')
    family: str = ""
    doc: str = ""
    pool_same_side: bool = False

    def variants(self):
        out = []
        for (si, s), (ri, r), (xi, x) in itertools.product(enumerate(self.sig_grid), enumerate(self.rules), enumerate(self.exits)):
            out.append(Variant(f"{self.name}|s{si}|r{ri}|x{xi}", self.name, si, ri, xi, s, r, x))
        return out

    def n_variants(self):
        return len(self.sig_grid) * len(self.rules) * len(self.exits)
