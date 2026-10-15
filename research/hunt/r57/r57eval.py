"""R57 reference evaluator: scores one feature row with an exported model JSON, in pure Python (no numpy).

This is the spec a Kotlin port is parity-tested against (golden vectors in results/models/*_golden.json). It reads no
file, clock or network: the caller loads the JSON once (at start-up) and passes the parsed dict.

Model JSON (r57model.export):
  kind        "hgb_clf"   sklearn HistGradientBoostingClassifier: raw = baseline + sum of leaf values; score = sigmoid
              "gb_reg"    sklearn GradientBoostingRegressor (median-imputed, inputs rounded to float32 as sklearn's
                          trees compare them): raw = init + lr x sum of leaf values; score = raw
              "logit"     median-imputed, standardised logistic regression: score = sigmoid(b + sum w x z)
  features    the input names, in order
  trees       [[node, ...], ...]; node = [feature, threshold, missing_left (0/1), left, right, leaf_value, is_leaf]
              a row goes left when x <= threshold (a missing x goes left when missing_left = 1)
"""
from __future__ import annotations

import math
import struct


def f32(x: float) -> float:
    """Round a double to the nearest float32, as sklearn's DecisionTree does with its inputs."""
    return struct.unpack("f", struct.pack("f", x))[0]


def _missing(x) -> bool:
    return x is None or (isinstance(x, float) and math.isnan(x))


def _tree(nodes, row) -> float:
    i = 0
    while True:
        f, thr, miss_left, left, right, val, leaf = nodes[i]
        if leaf:
            return val
        x = row[f]
        if _missing(x):
            i = left if miss_left else right
        else:
            i = left if x <= thr else right


def _sigmoid(z: float) -> float:
    if z >= 0:
        return 1.0 / (1.0 + math.exp(-z))
    e = math.exp(z)
    return e / (1.0 + e)


def score(model: dict, values) -> float:
    """values: a list in model["features"] order, or a dict name -> value (missing names = missing values)."""
    names = model["features"]
    if isinstance(values, dict):
        row = [values.get(n) for n in names]
    else:
        row = list(values)
    if len(row) != len(names):
        raise ValueError(f"expected {len(names)} features, got {len(row)}")
    row = [None if _missing(x) else float(x) for x in row]
    kind = model["kind"]
    if kind == "hgb_clf":
        raw = model["baseline"]
        for t in model["trees"]:
            raw += _tree(t, row)
        return _sigmoid(raw)
    if kind == "gb_reg":
        med = model["medians"]
        row = [f32(med[i] if x is None else x) for i, x in enumerate(row)]
        s = 0.0
        for t in model["trees"]:
            s += _tree(t, row)
        return model["init"] + model["lr"] * s
    if kind == "logit":
        med, mu, sc, w = model["medians"], model["mean"], model["scale"], model["coef"]
        z = model["intercept"]
        for i, x in enumerate(row):
            v = med[i] if x is None else x
            z += w[i] * (v - mu[i]) / sc[i]
        return _sigmoid(z)
    raise ValueError(f"unknown model kind {kind}")


def take(model: dict, values) -> bool:
    """The pre-registered take rule stored with the model: score >= threshold (rule ">=") or > threshold (rule ">")."""
    s = score(model, values)
    return s > model["threshold"] if model.get("rule") == ">" else s >= model["threshold"]
