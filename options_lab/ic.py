"""Panel information coefficient, with its null band and its controls attached.

An IC reported alone is not evidence. Two of the most widely published option
"signals" fail exactly here: PCR's raw IC of -0.33 at 60 minutes collapses to
-0.086 +/- 0.057 once the index level is controlled, and max pain's +0.58 is
entirely a monotone transform of minus-the-index - a control containing no
option data at all ("distance to the day's open") beats it, +0.644 vs +0.584.

So `ICRow.format()` raises unless controls were supplied. The omission is the
failure mode, so the type refuses to make it.

Significance is day-clustered. Every observation inside a session shares one
underlying path; the iid interval is reported beside the clustered one so the
gap is visible rather than assumed away.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

NULL_DRAWS = 20          # a band well inside the decision width; 200 buys a decimal
DECILES = 10


class UncontrolledIC(RuntimeError):
    """A raw IC has no reportable meaning without its partial beside it."""


def _spearman(a: pd.Series, b: pd.Series) -> float:
    ok = a.notna() & b.notna()
    a, b = a[ok], b[ok]
    if len(a) < 3 or a.nunique() < 2 or b.nunique() < 2:
        return float("nan")
    return float(a.rank().corr(b.rank()))


# Below this share of the original variance, a residual is numerical dust and
# any correlation computed on it is an artefact of the solver, not information.
DEGENERATE_VAR_RATIO = 1e-12


def _residualise(y: pd.Series, controls: pd.DataFrame) -> pd.Series:
    """Least-squares residual of y on controls plus an intercept.

    Returns all-NaN when the controls explain y essentially completely. Two such
    residual vectors are proportional floating-point dust, and their rank
    correlation comes out near +/-1 - a spurious partial IC precisely where the
    honest answer is "the controls already explain this feature".
    """
    X = np.column_stack([np.ones(len(y)), controls.to_numpy(dtype="float64")])
    yv = y.to_numpy(dtype="float64")
    beta, *_ = np.linalg.lstsq(X, yv, rcond=None)
    resid = yv - X @ beta

    var_y = float(np.var(yv))
    if var_y > 0 and float(np.var(resid)) / var_y < DEGENERATE_VAR_RATIO:
        return pd.Series(np.nan, index=y.index)
    return pd.Series(resid, index=y.index)


@dataclass(frozen=True)
class ICRow:
    feature: str
    horizon: int
    n_obs: int
    n_sessions: int
    ic: float
    daily_ic_mean: float
    daily_ic_lo: float
    daily_ic_hi: float
    ic_iid_lo: float
    ic_iid_hi: float
    null_ic_hi: float
    decile_spread_pts: float
    breakeven_pts: float
    edge_over_cost: float
    partial_ic: float = float("nan")
    controlled: bool = field(default=False)
    # A directional trade earns its own decile's mean, not D10 minus D1.
    # Dividing the full long-short spread by one round trip overstates a
    # single-leg strategy by ~2x.
    single_leg_edge_pts: float = float("nan")
    null_is_daily: bool = True
    # Sessions that actually yielded an IC. A session with no rank variance
    # is dropped, so daily_ic_mean and its interval rest on FEWER sessions
    # than n_sessions. Reporting only the total overstates the evidence.
    n_sessions_measured: int = 0

    def format(self) -> str:
        if not self.controlled:
            raise UncontrolledIC(
                f"{self.feature}: refusing to report a raw IC of {self.ic:.4f} with "
                "no partial beside it. Supply controls - at minimum the index's "
                "contemporaneous return and the lagged return. PCR and max pain "
                "both look real until this is done."
            )
        return (
            f"{self.feature} h={self.horizon} n={self.n_obs} "
            f"sessions={self.n_sessions_measured}/{self.n_sessions} "
            f"ic={self.ic:+.4f} "
            f"partial_ic={self.partial_ic:+.4f} "
            f"daily[{self.daily_ic_lo:+.4f},{self.daily_ic_hi:+.4f}] "
            f"iid[{self.ic_iid_lo:+.4f},{self.ic_iid_hi:+.4f}] "
            f"null_hi={self.null_ic_hi:.4f} "
            f"spread={self.decile_spread_pts:+.2f}pts "
            f"leg={self.single_leg_edge_pts:+.2f}pts "
            f"breakeven={self.breakeven_pts:.2f}pts "
            f"edge_over_cost={self.edge_over_cost:.2f}x"
            f"{'' if self.monotone else ' [NON-MONOTONE: tails disagree with IC]'}"
        )

    @property
    def monotone(self) -> bool:
        """Do the tails agree with the rank correlation?

        The decile spread and the IC are different measurements, and they can
        disagree - a non-monotone relationship where the extremes behave
        opposite to the overall ranking. When they disagree, `edge_over_cost`
        is a magnitude with no coherent direction to trade, so it must not be
        read as edge.
        """
        if np.isnan(self.ic) or np.isnan(self.decile_spread_pts):
            return False
        return np.sign(self.ic) == np.sign(self.decile_spread_pts)

    @property
    def clears_cost(self) -> bool:
        return self.edge_over_cost >= 2.0 and self.monotone

    @property
    def beats_null(self) -> bool:
        """Compared against the DAILY-mean null, matching daily_ic_lo/hi.

        These were previously mismatched: the null band was built from pooled
        Spearmans while the interval was built from per-session ones, so the
        two scored different statistics and were nonetheless compared.
        """
        return abs(self.daily_ic_mean) > self.null_ic_hi


def panel_ic(
    *,
    feature: pd.Series,
    forward: pd.Series,
    sessions: pd.Series,
    controls: pd.DataFrame | None = None,
    name: str = "feature",
    horizon: int = 1,
    breakeven_pts: float = 1.0,
    null_draws: int = NULL_DRAWS,
    seed: int = 0,
) -> ICRow:
    """IC of `feature` against `forward`, clustered by `sessions`."""
    n = len(feature)
    if not (len(forward) == len(sessions) == n):
        raise ValueError(
            f"length mismatch: feature={n} forward={len(forward)} "
            f"sessions={len(sessions)} - align before measuring, never truncate"
        )

    feature = feature.reset_index(drop=True)
    forward = forward.reset_index(drop=True)
    sessions = sessions.reset_index(drop=True)

    overall = _spearman(feature, forward)

    # Day-clustered: one IC per session, then a mean and a percentile interval
    # over sessions. This is the interval that respects the shared path.
    per_day = pd.Series(
        {s: _spearman(feature[m], forward[m])
         for s, m in feature.groupby(sessions).groups.items()
         for m in [sessions == s]}
    ).dropna()
    daily_mean = float(per_day.mean()) if len(per_day) else float("nan")
    if len(per_day) >= 2:
        se = float(per_day.std(ddof=1) / np.sqrt(len(per_day)))
        daily_lo, daily_hi = daily_mean - 1.96 * se, daily_mean + 1.96 * se
    else:
        daily_lo = daily_hi = float("nan")

    # The iid interval, reported only so the gap to the clustered one is visible.
    se_iid = 1.0 / np.sqrt(max(n - 3, 1))
    ic_iid_lo, ic_iid_hi = overall - 1.96 * se_iid, overall + 1.96 * se_iid

    # Null: permute the feature's SIGN within each session. Magnitude
    # distribution and time-of-day profile survive; only direction is destroyed.
    rng = np.random.default_rng(seed)
    draws = []
    for _ in range(null_draws):
        flipped = feature.copy()
        for _s, idx in feature.groupby(sessions).groups.items():
            signs = rng.choice([-1.0, 1.0], size=len(idx))
            flipped.loc[idx] = feature.loc[idx].to_numpy() * signs
        per_day_null = [_spearman(flipped[m], forward[m])
                        for m in (sessions == s for s in sessions.unique())]
        draws.append(abs(np.nanmean(per_day_null)))
    null_hi = float(np.nanpercentile(draws, 95)) if draws else float("nan")

    # Decile spread, in the same units as `forward`.
    try:
        buckets = pd.qcut(feature.rank(method="first"), DECILES, labels=False)
        by = forward.groupby(buckets).mean()
        spread = float(by.iloc[-1] - by.iloc[0])
    except (ValueError, IndexError):
        spread = float("nan")

    partial = float("nan")
    if controls is not None and not controls.empty:
        c = controls.reset_index(drop=True)
        partial = _spearman(_residualise(feature, c), _residualise(forward, c))

    single_leg = spread / 2.0 if not np.isnan(spread) else float("nan")

    return ICRow(
        feature=name, horizon=horizon, n_obs=n, n_sessions=int(sessions.nunique()),
        n_sessions_measured=int(len(per_day)),
        ic=overall, daily_ic_mean=daily_mean, daily_ic_lo=daily_lo,
        daily_ic_hi=daily_hi, ic_iid_lo=ic_iid_lo, ic_iid_hi=ic_iid_hi,
        null_ic_hi=null_hi, decile_spread_pts=spread, breakeven_pts=breakeven_pts,
        edge_over_cost=(abs(single_leg) / breakeven_pts
                        if breakeven_pts else float("nan")),
        partial_ic=partial, controlled=controls is not None and not controls.empty,
        single_leg_edge_pts=single_leg,
    )
