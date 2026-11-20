#!/usr/bin/env python3
"""Create a strict analysis bundle for the current 1D Parent paired experiment."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path

import matplotlib
import numpy as np
import pandas as pd
from scipy import stats

matplotlib.use("Agg")
import matplotlib.pyplot as plt


CONFIG_ORDER = ["hybrid-410000", "hybrid-290002", "parent-4", "parent-1"]
COMPARISONS = [
    ("hybrid-290002-vs-410000", "hybrid-410000", "hybrid-290002"),
    ("parent-4-vs-1", "parent-1", "parent-4"),
]
COLORS = {"baseline": "#0072B2", "candidate": "#D55E00"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--expected", required=True)
    parser.add_argument("--status", required=True)
    parser.add_argument("--data-dir", required=True)
    parser.add_argument("--analysis-dir", required=True)
    return parser.parse_args()


def scene_key(value: str) -> int:
    return int(value.removeprefix("ID"))


def mean_ci(values: np.ndarray) -> tuple[float, float, float, float]:
    n = len(values)
    mean = float(np.mean(values))
    std = float(np.std(values, ddof=1)) if n > 1 else 0.0
    if n < 2 or std == 0.0:
        return mean, std, mean, mean
    half_width = float(stats.t.ppf(0.975, n - 1) * std / math.sqrt(n))
    return mean, std, mean - half_width, mean + half_width


def holm_adjust(p_values: list[float]) -> list[float]:
    adjusted = [math.nan] * len(p_values)
    ordered = sorted(enumerate(p_values), key=lambda item: item[1])
    previous = 0.0
    total = len(p_values)
    for rank, (index, p_value) in enumerate(ordered):
        current = min(1.0, (total - rank) * p_value)
        previous = max(previous, current)
        adjusted[index] = previous
    return adjusted


def rank_biserial(differences: np.ndarray) -> float:
    nonzero = differences[np.abs(differences) > 1e-12]
    if len(nonzero) == 0:
        return 0.0
    ranks = stats.rankdata(np.abs(nonzero))
    positive = float(np.sum(ranks[nonzero > 0]))
    negative = float(np.sum(ranks[nonzero < 0]))
    return (positive - negative) / (positive + negative)


def paired_test(differences: np.ndarray) -> dict[str, float | int | str | None]:
    n = len(differences)
    mean, std, ci_low, ci_high = mean_ci(differences)
    nonzero = differences[np.abs(differences) > 1e-12]
    wins = int(np.sum(differences > 1e-12))
    losses = int(np.sum(differences < -1e-12))
    ties = n - wins - losses
    if len(nonzero) == 0:
        return {
            "n": n, "mean_delta": mean, "std_delta": std, "ci95_low": ci_low,
            "ci95_high": ci_high, "wins": wins, "ties": ties, "losses": losses,
            "shapiro_w": None, "shapiro_p": None, "test": "all-tied",
            "statistic": 0.0, "p_value": 1.0, "effect_size": 0.0,
            "effect_size_name": "rank-biserial correlation", "sign_test_p": 1.0,
        }
    shapiro = stats.shapiro(differences) if n >= 3 else None
    normal = shapiro is not None and float(shapiro.pvalue) >= 0.05
    if normal:
        t_result = stats.ttest_1samp(differences, popmean=0.0)
        effect = mean / std if std > 0.0 else 0.0
        test = "paired t-test"
        statistic = float(t_result.statistic)
        p_value = float(t_result.pvalue)
        effect_name = "Cohen dz"
    else:
        w_result = stats.wilcoxon(nonzero, alternative="two-sided", method="auto")
        test = "Wilcoxon signed-rank"
        statistic = float(w_result.statistic)
        p_value = float(w_result.pvalue)
        effect = rank_biserial(differences)
        effect_name = "rank-biserial correlation"
    sign_p = float(stats.binomtest(min(wins, losses), wins + losses, 0.5, alternative="two-sided").pvalue)
    return {
        "n": n, "mean_delta": mean, "std_delta": std, "ci95_low": ci_low,
        "ci95_high": ci_high, "wins": wins, "ties": ties, "losses": losses,
        "shapiro_w": None if shapiro is None else float(shapiro.statistic),
        "shapiro_p": None if shapiro is None else float(shapiro.pvalue),
        "test": test, "statistic": statistic, "p_value": p_value,
        "effect_size": effect, "effect_size_name": effect_name,
        "sign_test_p": sign_p,
    }


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def build_scene_summary(details: pd.DataFrame) -> list[dict]:
    rows = []
    for (scene_id, scene_name, config_key), group in details.groupby(
        ["sceneId", "sceneName", "configKey"], sort=False
    ):
        profit = group["profitRatio"].to_numpy(dtype=float)
        elapsed = group["elapsedSeconds"].to_numpy(dtype=float)
        mean, std, ci_low, ci_high = mean_ci(profit)
        rows.append({
            "scene_id": scene_id,
            "scene_name": scene_name,
            "config_key": config_key,
            "n": len(group),
            "mean_profit_ratio": mean,
            "std_profit_ratio": std,
            "ci95_low": ci_low,
            "ci95_high": ci_high,
            "mean_elapsed_seconds": float(np.mean(elapsed)),
            "std_elapsed_seconds": float(np.std(elapsed, ddof=1)) if len(elapsed) > 1 else 0.0,
        })
    return sorted(rows, key=lambda row: (scene_key(row["scene_id"]), CONFIG_ORDER.index(row["config_key"])))


def validate_inputs(details: pd.DataFrame, expected: pd.DataFrame, statuses: pd.DataFrame) -> dict:
    expected_keys = set(expected["runKey"])
    detail_keys = set(details["runKey"])
    status_keys = set(statuses["runKey"])
    pairing = details.groupby(["sceneId", "perRun"], sort=False)
    invalid_groups = []
    for (scene_id, per_run), group in pairing:
        if len(group) != 4:
            invalid_groups.append(f"{scene_id}/perRun={per_run}: count={len(group)}")
            continue
        if set(group["configKey"]) != set(CONFIG_ORDER):
            invalid_groups.append(f"{scene_id}/perRun={per_run}: config set mismatch")
        if group["effectiveSeed"].nunique() != 1:
            invalid_groups.append(f"{scene_id}/perRun={per_run}: seed mismatch")
    return {
        "expected_runs": int(len(expected)),
        "detail_rows": int(len(details)),
        "unique_detail_keys": int(len(detail_keys)),
        "status_rows": int(len(statuses)),
        "missing_run_keys": sorted(expected_keys - detail_keys),
        "unexpected_run_keys": sorted(detail_keys - expected_keys),
        "missing_status_keys": sorted(expected_keys - status_keys),
        "failed_attempts": int((statuses["status"] != "ok").sum()),
        "invalid_pairing_groups": invalid_groups,
    }


def draw_figures(scene_rows: list[dict], details: pd.DataFrame, analysis_dir: Path) -> list[str]:
    figure_dir = analysis_dir / "figures"
    figure_dir.mkdir(parents=True, exist_ok=True)
    scene_df = pd.DataFrame(scene_rows)
    scene_ids = sorted(scene_df["scene_id"].unique(), key=scene_key)
    x = np.arange(len(scene_ids))

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2), constrained_layout=True)
    for axis, (label, baseline, candidate) in zip(axes, COMPARISONS):
        baseline_rows = scene_df[scene_df["config_key"] == baseline].set_index("scene_id").loc[scene_ids]
        candidate_rows = scene_df[scene_df["config_key"] == candidate].set_index("scene_id").loc[scene_ids]
        for rows, color, name, offset in (
            (baseline_rows, COLORS["baseline"], baseline, -0.08),
            (candidate_rows, COLORS["candidate"], candidate, 0.08),
        ):
            means = rows["mean_profit_ratio"].to_numpy()
            errors = np.maximum(0.0, rows["ci95_high"].to_numpy() - means)
            axis.errorbar(x + offset, means, yerr=errors, fmt="o-", color=color, label=name, capsize=3)
        axis.set_title(label)
        axis.set_xticks(x, scene_ids)
        axis.set_xlabel("Scene")
        axis.set_ylabel("Profit ratio (%)")
        axis.grid(axis="y", alpha=0.25)
        axis.legend(frameon=False, fontsize=8)
    main_png = figure_dir / "figure-01-scene-means.png"
    main_svg = figure_dir / "figure-01-scene-means.svg"
    fig.savefig(main_png, dpi=300)
    fig.savefig(main_svg)
    plt.close(fig)

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2), constrained_layout=True)
    for axis, (label, baseline, candidate) in zip(axes, COMPARISONS):
        pair_df = details.pivot(index=["sceneId", "perRun"], columns="configKey", values="profitRatio")
        seed_delta = (pair_df[candidate] - pair_df[baseline]).reset_index(name="delta")
        grouped = seed_delta.groupby("sceneId")["delta"]
        means = grouped.mean().reindex(scene_ids).to_numpy()
        counts = grouped.count().reindex(scene_ids).to_numpy()
        stds = grouped.std(ddof=1).fillna(0.0).reindex(scene_ids).to_numpy()
        errors = np.array([
            stats.t.ppf(0.975, count - 1) * std / math.sqrt(count) if count > 1 and std > 0 else 0.0
            for count, std in zip(counts, stds)
        ])
        axis.bar(x, means, yerr=errors, color=COLORS["candidate"], capsize=3)
        axis.axhline(0.0, color="black", linewidth=0.8)
        axis.set_title(f"Delta: {label}")
        axis.set_xticks(x, scene_ids)
        axis.set_xlabel("Scene")
        axis.set_ylabel("Candidate - baseline (pp)")
        axis.grid(axis="y", alpha=0.25)
    delta_png = figure_dir / "figure-02-scene-deltas.png"
    delta_svg = figure_dir / "figure-02-scene-deltas.svg"
    fig.savefig(delta_png, dpi=300)
    fig.savefig(delta_svg)
    plt.close(fig)
    return [str(path) for path in (main_png, main_svg, delta_png, delta_svg)]


def main() -> int:
    args = parse_args()
    details = pd.read_csv(args.input)
    expected = pd.read_csv(args.expected)
    statuses = pd.read_csv(args.status)
    details["profitRatio"] = pd.to_numeric(details["profitRatio"], errors="raise")
    details["elapsedSeconds"] = pd.to_numeric(details["elapsedSeconds"], errors="raise")
    integrity = validate_inputs(details, expected, statuses)
    if any((integrity["missing_run_keys"], integrity["unexpected_run_keys"], integrity["missing_status_keys"], integrity["invalid_pairing_groups"])):
        raise SystemExit(json.dumps(integrity, indent=2))

    data_dir = Path(args.data_dir)
    analysis_dir = Path(args.analysis_dir)
    scene_rows = build_scene_summary(details)
    scene_path = data_dir / "2026-07-24-current-1d-parent-paired-scene-summary.csv"
    scene_fields = [
        "scene_id", "scene_name", "config_key", "n", "mean_profit_ratio", "std_profit_ratio",
        "ci95_low", "ci95_high", "mean_elapsed_seconds", "std_elapsed_seconds",
    ]
    write_csv(scene_path, scene_rows, scene_fields)

    scene_df = pd.DataFrame(scene_rows)
    comparison_rows = []
    summary = {"integrity": integrity, "comparisons": [], "config_macro_means": {}}
    for config_key in CONFIG_ORDER:
        values = scene_df[scene_df["config_key"] == config_key]["mean_profit_ratio"].to_numpy(dtype=float)
        summary["config_macro_means"][config_key] = {
            "macro_mean_profit_ratio": float(np.mean(values)),
            "scene_std": float(np.std(values, ddof=1)),
            "n_scenes": len(values),
        }
    for label, baseline, candidate in COMPARISONS:
        scene_pivot = scene_df.pivot(index="scene_id", columns="config_key", values="mean_profit_ratio")
        scene_pivot = scene_pivot.loc[sorted(scene_pivot.index, key=scene_key)]
        primary = paired_test((scene_pivot[candidate] - scene_pivot[baseline]).to_numpy(dtype=float))
        seed_pivot = details.pivot(index=["sceneId", "perRun"], columns="configKey", values="profitRatio")
        sensitivity = paired_test((seed_pivot[candidate] - seed_pivot[baseline]).to_numpy(dtype=float))
        record = {
            "comparison": label,
            "baseline": baseline,
            "candidate": candidate,
            "primary_scene_mean": primary,
            "seed_level_sensitivity": sensitivity,
        }
        summary["comparisons"].append(record)
        comparison_rows.append({
            "comparison": label, "baseline": baseline, "candidate": candidate,
            "n_scenes": primary["n"], "wins": primary["wins"], "ties": primary["ties"],
            "losses": primary["losses"], "mean_delta_profit_ratio": primary["mean_delta"],
            "std_delta_profit_ratio": primary["std_delta"], "ci95_low": primary["ci95_low"],
            "ci95_high": primary["ci95_high"], "test": primary["test"],
            "statistic": primary["statistic"], "p_value": primary["p_value"],
            "effect_size": primary["effect_size"], "effect_size_name": primary["effect_size_name"],
            "sign_test_p": primary["sign_test_p"], "shapiro_p": primary["shapiro_p"],
            "seed_sensitivity_n": sensitivity["n"], "seed_sensitivity_p": sensitivity["p_value"],
        })
    adjusted = holm_adjust([float(row["p_value"]) for row in comparison_rows])
    for row, record, adjusted_p in zip(comparison_rows, summary["comparisons"], adjusted):
        row["holm_p_value"] = adjusted_p
        record["primary_scene_mean"]["holm_p_value"] = adjusted_p
    comparison_path = data_dir / "2026-07-24-current-1d-parent-paired-comparisons.csv"
    comparison_fields = list(comparison_rows[0].keys())
    write_csv(comparison_path, comparison_rows, comparison_fields)
    summary["source"] = {
        "run_details": str(Path(args.input)), "expected_runs": str(Path(args.expected)),
        "run_status": str(Path(args.status)), "primary_unit": "scene-level mean across five paired perRun values",
        "metric": "profitRatio, higher is better", "holm_contrasts": len(COMPARISONS),
    }
    analysis_dir.mkdir(parents=True, exist_ok=True)
    summary_path = analysis_dir / "analysis-summary.json"
    summary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    figures = draw_figures(scene_rows, details, analysis_dir)
    print(json.dumps({"summary": str(summary_path), "scene_csv": str(scene_path), "comparison_csv": str(comparison_path), "figures": figures}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
