#!/usr/bin/env python3
"""Analyze the corrected native perRun paired ablation experiment.

The primary inferential unit is one five-run mean per scene. The script writes
the canonical Evidence data table, deterministic analysis summary, figures,
and reproducibility manifest required by the repository result contract.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import itertools
import json
import math
import subprocess
import sys
from pathlib import Path
from typing import Any

import matplotlib
import numpy as np
import pandas as pd
import scipy
from scipy import stats

matplotlib.use("Agg")
import matplotlib.pyplot as plt


EXPERIMENT_ID = "paired-native-perrun0-4-10scene-20260724-r01"
ANALYSIS_DATE = "2026-07-25"
TARGET = "hybrid-410000"
CONFIG_ORDER = [TARGET, "hybrid-290002", "parent-4", "parent-1"]
CONFIG_SPECS = {
    TARGET: {
        "entrypoint": "main.RunSingleHybridConfig",
        "config": "4,1,0,0,0,0",
        "label": "410000",
    },
    "hybrid-290002": {
        "entrypoint": "main.RunSingleHybridConfig",
        "config": "2,9,0,0,0,2",
        "label": "290002",
    },
    "parent-4": {
        "entrypoint": "main.RunSingleParentConfig",
        "config": "4",
        "label": "Parent 4 (DE/rand/1)",
    },
    "parent-1": {
        "entrypoint": "main.RunSingleParentConfig",
        "config": "1",
        "label": "Parent 1 (PSO)",
    },
}
COMPARATORS = ["hybrid-290002", "parent-4", "parent-1"]
REGIONAL_SCENES = {"ID1", "ID3", "ID5", "ID7", "ID9", "ID10"}
GLOBAL_SCENES = {"ID2", "ID4", "ID6", "ID8"}
DATA_FIELDS = [
    "scene_id",
    "scene_name",
    "distribution",
    "per_run",
    "effective_index",
    "effective_seed",
    "config_key",
    "entrypoint",
    "config",
    "profit_ratio",
    "elapsed_seconds",
]
FORBIDDEN_DATA_FIELDS = {
    "log_file",
    "logPath",
    "num_tasks",
    "num_satellites",
    "num_satellite",
    "numsat",
}
COLORS = {
    TARGET: "#0072B2",
    "hybrid-290002": "#D55E00",
    "parent-4": "#009E73",
    "parent-1": "#CC79A7",
}
MARKERS = {TARGET: "o", "hybrid-290002": "s", "parent-4": "^", "parent-1": "D"}
LINESTYLES = {TARGET: "-", "hybrid-290002": "--", "parent-4": "-.", "parent-1": ":"}
BOOTSTRAP_RESAMPLES = 100_000
ZERO_TOLERANCE = 1e-9


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--experiment-id", default=EXPERIMENT_ID)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    return parser.parse_args()


def scene_number(scene_id: str) -> int:
    return int(scene_id.removeprefix("ID"))


def relative_path(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_output(root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=root, check=True, capture_output=True, text=True, encoding="utf-8"
    )
    return result.stdout.strip()


def json_ready(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): json_ready(item) for key, item in value.items()}
    if isinstance(value, list):
        return [json_ready(item) for item in value]
    if isinstance(value, tuple):
        return [json_ready(item) for item in value]
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        return float(value)
    return value


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(json_ready(payload), indent=2, ensure_ascii=False, allow_nan=False) + "\n",
        encoding="utf-8",
    )


def write_csv(path: Path, rows: list[dict[str, Any]], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="raise")
        writer.writeheader()
        writer.writerows(rows)
    with path.open("r", newline="", encoding="utf-8") as handle:
        header = next(csv.reader(handle))
    if header != fields:
        raise ValueError(f"Unexpected CSV header: {header}")
    forbidden = FORBIDDEN_DATA_FIELDS.intersection(header)
    if forbidden:
        raise ValueError(f"Forbidden CSV fields: {sorted(forbidden)}")


def load_and_validate(
    output_dir: Path,
) -> tuple[pd.DataFrame, dict[str, Any], dict[str, Any], dict[str, Any]]:
    details_path = output_dir / "run-details.csv"
    expected_path = output_dir / "expected-runs.csv"
    status_path = output_dir / "run-status.csv"
    progress_path = output_dir / "progress.json"
    contract_path = output_dir / "experiment-contract.json"

    details = pd.read_csv(details_path, dtype={"effectiveSeed": "int64", "runSeedBase": "int64"})
    expected = pd.read_csv(expected_path)
    statuses = pd.read_csv(status_path)
    progress = json.loads(progress_path.read_text(encoding="utf-8"))
    contract = json.loads(contract_path.read_text(encoding="utf-8"))

    required = {
        "runKey",
        "sceneId",
        "sceneName",
        "perRun",
        "effectiveRunIndex",
        "effectiveSeed",
        "configKey",
        "entrypoint",
        "config",
        "profitRatio",
        "elapsedSeconds",
    }
    missing_columns = sorted(required - set(details.columns))
    if missing_columns:
        raise ValueError(f"run-details.csv missing columns: {missing_columns}")

    details["perRun"] = pd.to_numeric(details["perRun"], errors="raise").astype(int)
    details["effectiveRunIndex"] = pd.to_numeric(
        details["effectiveRunIndex"], errors="raise"
    ).astype(int)
    details["effectiveSeed"] = pd.to_numeric(details["effectiveSeed"], errors="raise").astype(
        "int64"
    )
    details["profitRatio"] = pd.to_numeric(details["profitRatio"], errors="raise")
    details["elapsedSeconds"] = pd.to_numeric(details["elapsedSeconds"], errors="raise")

    expected_keys = set(expected["runKey"])
    detail_keys = set(details["runKey"])
    status_ok = statuses[statuses["status"] == "ok"]
    ok_keys = set(status_ok["runKey"])
    failures = statuses[statuses["status"] != "ok"]

    errors: list[str] = []
    if len(details) != 200:
        errors.append(f"detail row count={len(details)}, expected 200")
    if len(detail_keys) != 200:
        errors.append(f"unique run key count={len(detail_keys)}, expected 200")
    if expected_keys != detail_keys:
        errors.append("expected and completed run-key sets differ")
    if expected_keys != ok_keys:
        errors.append("not every expected run has an ok status")
    if not failures.empty:
        errors.append(f"non-ok status rows={len(failures)}")
    if progress.get("status") != "completed" or int(progress.get("completedRuns", -1)) != 200:
        errors.append(f"progress is not completed: {progress}")
    if contract.get("experimentId") != output_dir.name:
        errors.append("experiment ID differs between directory and contract")
    if set(details["sceneId"]) != REGIONAL_SCENES | GLOBAL_SCENES:
        errors.append(f"unexpected scene set={sorted(set(details['sceneId']))}")
    if set(details["perRun"]) != set(range(5)):
        errors.append(f"unexpected perRun set={sorted(set(details['perRun']))}")
    if set(details["configKey"]) != set(CONFIG_ORDER):
        errors.append(f"unexpected config set={sorted(set(details['configKey']))}")
    if details[["profitRatio", "elapsedSeconds"]].isna().any().any():
        errors.append("profitRatio or elapsedSeconds contains missing values")

    pairing_groups = 0
    seed_mismatches = 0
    config_mismatches = 0
    index_mismatches = 0
    for (scene_id, per_run), group in details.groupby(["sceneId", "perRun"], sort=False):
        pairing_groups += 1
        if len(group) != 4 or set(group["configKey"]) != set(CONFIG_ORDER):
            config_mismatches += 1
        if group["effectiveSeed"].nunique() != 1:
            seed_mismatches += 1
        if set(group["effectiveRunIndex"]) != {int(per_run) + 9}:
            index_mismatches += 1
        for config_key, spec in CONFIG_SPECS.items():
            row = group[group["configKey"] == config_key]
            if len(row) != 1:
                continue
            if row.iloc[0]["entrypoint"] != spec["entrypoint"] or str(row.iloc[0]["config"]) != spec[
                "config"
            ]:
                config_mismatches += 1

    if pairing_groups != 50:
        errors.append(f"pairing group count={pairing_groups}, expected 50")
    if seed_mismatches:
        errors.append(f"seed mismatch groups={seed_mismatches}")
    if config_mismatches:
        errors.append(f"config mismatch groups={config_mismatches}")
    if index_mismatches:
        errors.append(f"effective-index mismatch groups={index_mismatches}")
    if errors:
        raise ValueError("; ".join(errors))

    fifth_id1 = details[
        (details["sceneId"] == "ID1")
        & (details["perRun"] == 4)
        & (details["configKey"] == TARGET)
    ].iloc[0]
    integrity = {
        "expected_runs": int(len(expected)),
        "completed_runs": int(len(details)),
        "unique_run_keys": int(len(detail_keys)),
        "failure_attempts": int(len(failures)),
        "pairing_groups": pairing_groups,
        "seed_mismatch_groups": seed_mismatches,
        "config_mismatch_groups": config_mismatches,
        "effective_index_mismatch_groups": index_mismatches,
        "effective_indices": sorted(int(value) for value in details["effectiveRunIndex"].unique()),
        "id1_fifth_run": {
            "per_run": 4,
            "effective_index": int(fifth_id1["effectiveRunIndex"]),
            "effective_seed": int(fifth_id1["effectiveSeed"]),
            "profit_ratio": float(fifth_id1["profitRatio"]),
        },
    }
    return details, progress, contract, integrity


def build_canonical_data(details: pd.DataFrame) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    ordered = details.assign(
        _scene_order=details["sceneId"].map(scene_number),
        _config_order=details["configKey"].map(CONFIG_ORDER.index),
    ).sort_values(["_scene_order", "perRun", "_config_order"])
    for item in ordered.itertuples(index=False):
        distribution = "Regional" if item.sceneId in REGIONAL_SCENES else "Global"
        rows.append(
            {
                "scene_id": item.sceneId,
                "scene_name": item.sceneName,
                "distribution": distribution,
                "per_run": int(item.perRun),
                "effective_index": int(item.effectiveRunIndex),
                "effective_seed": int(item.effectiveSeed),
                "config_key": item.configKey,
                "entrypoint": item.entrypoint,
                "config": str(item.config),
                "profit_ratio": f"{float(item.profitRatio):.2f}",
                "elapsed_seconds": f"{float(item.elapsedSeconds):.2f}",
            }
        )
    return rows


def bootstrap_mean_ci(values: np.ndarray, seed: int) -> tuple[float, float]:
    rng = np.random.default_rng(seed)
    samples = rng.choice(values, size=(BOOTSTRAP_RESAMPLES, len(values)), replace=True)
    boot_means = samples.mean(axis=1)
    low, high = np.percentile(boot_means, [2.5, 97.5])
    return float(low), float(high)


def exact_wilcoxon(differences: np.ndarray) -> tuple[float, float, float, int]:
    nonzero = differences[np.abs(differences) > ZERO_TOLERANCE]
    if len(nonzero) == 0:
        return 0.0, 1.0, 0.0, 0
    ranks = stats.rankdata(np.abs(nonzero), method="average")
    total_rank = float(ranks.sum())
    positive_rank = float(ranks[nonzero > 0].sum())
    negative_rank = float(ranks[nonzero < 0].sum())
    observed = min(positive_rank, negative_rank)
    extreme = 0
    assignments = 0
    for signs in itertools.product((-1, 1), repeat=len(nonzero)):
        candidate_positive = float(ranks[np.asarray(signs) > 0].sum())
        candidate_statistic = min(candidate_positive, total_rank - candidate_positive)
        extreme += int(candidate_statistic <= observed + 1e-12)
        assignments += 1
    p_value = min(1.0, extreme / assignments)
    rank_biserial = (positive_rank - negative_rank) / total_rank
    return observed, p_value, rank_biserial, len(nonzero)


def holm_adjust(p_values: list[float]) -> list[float]:
    adjusted = [math.nan] * len(p_values)
    previous = 0.0
    count = len(p_values)
    for rank, (index, p_value) in enumerate(sorted(enumerate(p_values), key=lambda item: item[1])):
        current = min(1.0, (count - rank) * p_value)
        previous = max(previous, current)
        adjusted[index] = previous
    return adjusted


def win_tie_loss(values: np.ndarray) -> dict[str, int]:
    return {
        "wins": int(np.sum(values > ZERO_TOLERANCE)),
        "ties": int(np.sum(np.abs(values) <= ZERO_TOLERANCE)),
        "losses": int(np.sum(values < -ZERO_TOLERANCE)),
    }


def contrast_statistics(differences: np.ndarray, seed: int) -> dict[str, Any]:
    differences = differences.copy()
    differences[np.abs(differences) <= ZERO_TOLERANCE] = 0.0
    wilcoxon_stat, wilcoxon_p, effect, nonzero_n = exact_wilcoxon(differences)
    sign_counts = win_tie_loss(differences)
    sign_n = sign_counts["wins"] + sign_counts["losses"]
    sign_p = (
        1.0
        if sign_n == 0
        else float(stats.binomtest(sign_counts["wins"], sign_n, 0.5, alternative="two-sided").pvalue)
    )
    ci_low, ci_high = bootstrap_mean_ci(differences, seed)
    shapiro = stats.shapiro(differences)
    q1, q3 = np.percentile(differences, [25, 75])
    return {
        "n_scenes": int(len(differences)),
        "nonzero_n": nonzero_n,
        "mean_delta_pp": float(np.mean(differences)),
        "std_delta_pp": float(np.std(differences, ddof=1)),
        "bootstrap_mean_ci95_pp": [ci_low, ci_high],
        "bootstrap_resamples": BOOTSTRAP_RESAMPLES,
        "median_delta_pp": float(np.median(differences)),
        "iqr_delta_pp": float(q3 - q1),
        **sign_counts,
        "wilcoxon_statistic": wilcoxon_stat,
        "wilcoxon_exact_p": wilcoxon_p,
        "rank_biserial": effect,
        "sign_test_exact_p": sign_p,
        "shapiro_w": float(shapiro.statistic),
        "shapiro_p": float(shapiro.pvalue),
    }


def build_analysis(details: pd.DataFrame, integrity: dict[str, Any]) -> dict[str, Any]:
    scene_stats = (
        details.groupby(["sceneId", "sceneName", "configKey"], sort=False)
        .agg(
            mean_profit_ratio=("profitRatio", "mean"),
            std_profit_ratio=("profitRatio", "std"),
            min_profit_ratio=("profitRatio", "min"),
            max_profit_ratio=("profitRatio", "max"),
            mean_elapsed_seconds=("elapsedSeconds", "mean"),
            std_elapsed_seconds=("elapsedSeconds", "std"),
        )
        .reset_index()
    )
    scene_stats["distribution"] = scene_stats["sceneId"].map(
        lambda item: "Regional" if item in REGIONAL_SCENES else "Global"
    )
    scene_stats["_scene_order"] = scene_stats["sceneId"].map(scene_number)
    scene_stats["_config_order"] = scene_stats["configKey"].map(CONFIG_ORDER.index)
    scene_stats = scene_stats.sort_values(["_scene_order", "_config_order"])

    macro: dict[str, Any] = {}
    runtime: dict[str, Any] = {}
    for config_key in CONFIG_ORDER:
        config_scene = scene_stats[scene_stats["configKey"] == config_key]
        config_runs = details[details["configKey"] == config_key]
        scene_values = config_scene["mean_profit_ratio"].to_numpy(dtype=float)
        macro[config_key] = {
            "label": CONFIG_SPECS[config_key]["label"],
            "n_runs": int(len(config_runs)),
            "n_scenes": int(len(scene_values)),
            "scene_macro_mean_profit_ratio": float(np.mean(scene_values)),
            "scene_macro_std": float(np.std(scene_values, ddof=1)),
            "scene_macro_median": float(np.median(scene_values)),
            "run_level_mean_profit_ratio": float(config_runs["profitRatio"].mean()),
        }
        runtime[config_key] = {
            "mean_elapsed_seconds": float(config_runs["elapsedSeconds"].mean()),
            "median_elapsed_seconds": float(config_runs["elapsedSeconds"].median()),
            "p95_elapsed_seconds": float(config_runs["elapsedSeconds"].quantile(0.95)),
            "sum_elapsed_seconds": float(config_runs["elapsedSeconds"].sum()),
        }

    scene_pivot = scene_stats.pivot(index="sceneId", columns="configKey", values="mean_profit_ratio")
    scene_pivot = scene_pivot.loc[sorted(scene_pivot.index, key=scene_number)]
    runtime_pivot = details.pivot(
        index=["sceneId", "perRun"], columns="configKey", values="elapsedSeconds"
    )
    comparisons: list[dict[str, Any]] = []
    for index, comparator in enumerate(COMPARATORS):
        differences = (scene_pivot[TARGET] - scene_pivot[comparator]).to_numpy(dtype=float)
        comparison = {
            "target": TARGET,
            "comparator": comparator,
            "direction": "target-minus-comparator",
            **contrast_statistics(differences, 20260725 + index),
            "subgroups": {},
        }
        for subgroup_name, scene_ids in (
            ("Regional", REGIONAL_SCENES),
            ("Global", GLOBAL_SCENES),
        ):
            subgroup_ids = [item for item in scene_pivot.index if item in scene_ids]
            subgroup_diff = (
                scene_pivot.loc[subgroup_ids, TARGET] - scene_pivot.loc[subgroup_ids, comparator]
            ).to_numpy(dtype=float, copy=True)
            subgroup_diff[np.abs(subgroup_diff) <= ZERO_TOLERANCE] = 0.0
            subgroup_ci = bootstrap_mean_ci(subgroup_diff, 20260825 + index)
            comparison["subgroups"][subgroup_name] = {
                "n_scenes": len(subgroup_diff),
                "mean_delta_pp": float(np.mean(subgroup_diff)),
                "median_delta_pp": float(np.median(subgroup_diff)),
                "bootstrap_mean_ci95_pp": list(subgroup_ci),
                **win_tie_loss(subgroup_diff),
            }
        elapsed_ratios = (runtime_pivot[TARGET] / runtime_pivot[comparator]).to_numpy(dtype=float)
        target_elapsed = float(details[details["configKey"] == TARGET]["elapsedSeconds"].mean())
        comparator_elapsed = float(
            details[details["configKey"] == comparator]["elapsedSeconds"].mean()
        )
        comparison["runtime"] = {
            "target_mean_seconds": target_elapsed,
            "comparator_mean_seconds": comparator_elapsed,
            "mean_seconds_delta": target_elapsed - comparator_elapsed,
            "aggregate_relative_percent": 100.0 * (target_elapsed / comparator_elapsed - 1.0),
            "median_paired_run_ratio": float(np.median(elapsed_ratios)),
        }
        comparisons.append(comparison)

    holm = holm_adjust([float(item["wilcoxon_exact_p"]) for item in comparisons])
    for item, adjusted in zip(comparisons, holm):
        item["wilcoxon_holm_p"] = adjusted

    friedman_inputs = [scene_pivot[key].to_numpy(dtype=float) for key in CONFIG_ORDER]
    friedman = stats.friedmanchisquare(*friedman_inputs)

    fifth_run = details[details["perRun"] == 4].copy()
    fifth_run["_scene_order"] = fifth_run["sceneId"].map(scene_number)
    fifth_run["_config_order"] = fifth_run["configKey"].map(CONFIG_ORDER.index)
    fifth_run = fifth_run.sort_values(["_scene_order", "_config_order"])
    fifth_rows = [
        {
            "scene_id": row.sceneId,
            "config_key": row.configKey,
            "effective_seed": int(row.effectiveSeed),
            "profit_ratio": float(row.profitRatio),
            "elapsed_seconds": float(row.elapsedSeconds),
        }
        for row in fifth_run.itertuples(index=False)
    ]

    scene_records = [
        {
            "scene_id": row.sceneId,
            "scene_name": row.sceneName,
            "distribution": row.distribution,
            "config_key": row.configKey,
            "mean_profit_ratio": float(row.mean_profit_ratio),
            "std_profit_ratio": float(row.std_profit_ratio),
            "min_profit_ratio": float(row.min_profit_ratio),
            "max_profit_ratio": float(row.max_profit_ratio),
            "mean_elapsed_seconds": float(row.mean_elapsed_seconds),
            "std_elapsed_seconds": float(row.std_elapsed_seconds),
        }
        for row in scene_stats.itertuples(index=False)
    ]

    return {
        "experiment_id": EXPERIMENT_ID,
        "analysis_date": ANALYSIS_DATE,
        "metric": {"name": "profitRatio", "unit": "percent", "higher_is_better": True},
        "primary_unit": "five-run mean within each scene (n=10 paired scenes)",
        "secondary_unit": "individual paired run for descriptive and runtime summaries only",
        "integrity": integrity,
        "config_summaries": macro,
        "runtime_summaries": runtime,
        "friedman": {
            "n_scenes": 10,
            "conditions": CONFIG_ORDER,
            "chi_square": float(friedman.statistic),
            "degrees_of_freedom": 3,
            "p_value": float(friedman.pvalue),
        },
        "comparisons": comparisons,
        "scene_statistics": scene_records,
        "fifth_run_rows": fifth_rows,
        "statistical_contract": {
            "planned_contrasts": [f"{TARGET} vs {item}" for item in COMPARATORS],
            "primary_test": "two-sided exact Wilcoxon signed-rank test on scene means",
            "zero_handling": "zero differences excluded from signed ranks and retained as ties",
            "multiple_comparison_correction": "Holm correction over three planned contrasts",
            "effect_size": "matched-pairs rank-biserial correlation; positive favors target",
            "supporting_test": "two-sided exact sign test",
            "assumption_check": "Shapiro-Wilk on ten scene-level paired differences",
            "confidence_interval": (
                "percentile bootstrap 95% CI for mean paired difference; scenes resampled; "
                f"{BOOTSTRAP_RESAMPLES} resamples"
            ),
        },
    }


def draw_scene_means(analysis: dict[str, Any], figure_dir: Path) -> list[Path]:
    scene_df = pd.DataFrame(analysis["scene_statistics"])
    scene_ids = sorted(scene_df["scene_id"].unique(), key=scene_number)
    x = np.arange(len(scene_ids))
    fig, axis = plt.subplots(figsize=(10.2, 5.3), constrained_layout=True)
    offsets = np.linspace(-0.18, 0.18, len(CONFIG_ORDER))
    for offset, config_key in zip(offsets, CONFIG_ORDER):
        rows = scene_df[scene_df["config_key"] == config_key].set_index("scene_id").loc[scene_ids]
        axis.errorbar(
            x + offset,
            rows["mean_profit_ratio"].to_numpy(dtype=float),
            yerr=rows["std_profit_ratio"].to_numpy(dtype=float),
            fmt=MARKERS[config_key],
            linestyle=LINESTYLES[config_key],
            linewidth=1.5,
            markersize=5.5,
            capsize=2.8,
            color=COLORS[config_key],
            label=CONFIG_SPECS[config_key]["label"],
        )
    axis.set_xticks(x, scene_ids)
    axis.set_xlabel("Scenario")
    axis.set_ylabel("Optimized profit ratio (%)")
    axis.set_ylim(0, 105)
    axis.grid(axis="y", alpha=0.25, linewidth=0.7)
    axis.legend(frameon=False, ncol=2, loc="lower left", fontsize=9)
    axis.text(
        0.99,
        0.02,
        "Points: five-run means; error bars: SD",
        transform=axis.transAxes,
        ha="right",
        va="bottom",
        fontsize=8.5,
        color="#444444",
    )
    base = figure_dir / "figure-01-scene-profit-means"
    paths = [base.with_suffix(".png"), base.with_suffix(".svg")]
    fig.savefig(paths[0], dpi=360, bbox_inches="tight")
    fig.savefig(paths[1], bbox_inches="tight")
    plt.close(fig)
    return paths


def draw_scene_deltas(analysis: dict[str, Any], details: pd.DataFrame, figure_dir: Path) -> list[Path]:
    pair = details.pivot(index=["sceneId", "perRun"], columns="configKey", values="profitRatio")
    width = 0.23
    fig, axes = plt.subplots(1, 2, figsize=(10.8, 4.8), sharey=True, constrained_layout=True)
    panels = [
        ("Regional / non-saturated", sorted(REGIONAL_SCENES, key=scene_number)),
        ("Global / saturated", sorted(GLOBAL_SCENES, key=scene_number)),
    ]
    for axis, (panel_title, scene_ids) in zip(axes, panels):
        x = np.arange(len(scene_ids))
        for comparator_index, comparator in enumerate(COMPARATORS):
            means: list[float] = []
            stds: list[float] = []
            for scene_id in scene_ids:
                rows = pair.loc[scene_id]
                delta = (rows[TARGET] - rows[comparator]).to_numpy(dtype=float)
                means.append(float(np.mean(delta)))
                stds.append(float(np.std(delta, ddof=1)))
            offset = (comparator_index - 1) * width
            axis.bar(
                x + offset,
                means,
                width=width,
                yerr=stds,
                capsize=2.5,
                color=COLORS[comparator],
                label=f"vs {CONFIG_SPECS[comparator]['label']}",
                alpha=0.9,
            )
        axis.axhline(0.0, color="black", linewidth=0.9)
        axis.set_xticks(x, scene_ids)
        axis.set_xlabel("Scenario")
        axis.set_title(panel_title, fontsize=11)
        axis.grid(axis="y", alpha=0.25, linewidth=0.7)
    axes[0].set_ylabel("410000 - comparator (percentage points)")
    axes[0].legend(frameon=False, fontsize=8.5, loc="upper left")
    axes[1].text(
        0.5,
        0.12,
        "All four scenarios tie at saturation",
        transform=axes[1].transAxes,
        ha="center",
        fontsize=8.5,
        color="#444444",
    )
    base = figure_dir / "figure-02-paired-deltas"
    paths = [base.with_suffix(".png"), base.with_suffix(".svg")]
    fig.savefig(paths[0], dpi=360, bbox_inches="tight")
    fig.savefig(paths[1], bbox_inches="tight")
    plt.close(fig)
    return paths


def build_manifest(
    root: Path,
    output_dir: Path,
    evidence_dir: Path,
    script_path: Path,
    summary_path: Path,
    data_path: Path,
    figure_paths: list[Path],
    progress: dict[str, Any],
    contract: dict[str, Any],
    integrity: dict[str, Any],
    git_status_before: list[str],
) -> dict[str, Any]:
    source_validation: dict[str, Any] = {}
    for source_name, expected_hash in contract["sourceHashes"].items():
        source_path = root / source_name
        current_hash = sha256(source_path)
        source_validation[source_name.replace("\\", "/")] = {
            "experiment_hash": expected_hash,
            "current_hash": current_hash,
            "matches": current_hash == expected_hash,
        }
    if not all(item["matches"] for item in source_validation.values()):
        raise ValueError("Current experiment source hashes do not match the completed-run contract")

    artifacts = {
        "data": {
            "path": relative_path(data_path, root),
            "sha256": sha256(data_path),
            "rows": 200,
            "headers": DATA_FIELDS,
        },
        "figures": [
            {"path": relative_path(path, root), "sha256": sha256(path)} for path in figure_paths
        ],
    }
    return {
        "schema_version": 1,
        "experiment_id": output_dir.name,
        "experiment_date": "2026-07-24",
        "analysis_date": ANALYSIS_DATE,
        "git_head": contract["gitHead"],
        "current_git_head": git_output(root, "rev-parse", "HEAD"),
        "git_status_before_analysis": git_status_before,
        "experiment": {
            "status": progress["status"],
            "expected_runs": progress["expectedRuns"],
            "completed_runs": progress["completedRuns"],
            "failure_attempts": progress["failureAttempts"],
            "scenes": [f"ID{index}" for index in range(1, 11)],
            "per_run_values": [0, 1, 2, 3, 4],
            "effective_indices": integrity["effective_indices"],
            "configs": {key: CONFIG_SPECS[key] for key in CONFIG_ORDER},
            "seed_contract": {
                "mode": contract["runSeedMode"],
                "reference_config_key": contract["referenceConfigKey"],
                "native_anchor_per_run": contract["nativeAnchorPerRun"],
                "native_anchor_index": contract["nativeAnchorIndex"],
                "scene_run_seed_bases": contract["sceneRunSeedBases"],
                "pairing_validation": {
                    "groups": integrity["pairing_groups"],
                    "seed_mismatches": integrity["seed_mismatch_groups"],
                    "config_mismatches": integrity["config_mismatch_groups"],
                    "effective_index_mismatches": integrity["effective_index_mismatch_groups"],
                },
                "id1_fifth_run": integrity["id1_fifth_run"],
            },
            "deterministic": contract["deterministic"],
            "process_priority": contract["processPriority"],
            "logical_processors": contract["logicalProcessors"],
            "source_hash_validation": source_validation,
            "runtime_class_hashes": contract["runtimeClassHashes"],
        },
        "sources": {
            "run_details": {
                "path": relative_path(output_dir / "run-details.csv", root),
                "sha256": sha256(output_dir / "run-details.csv"),
            },
            "experiment_contract": {
                "path": relative_path(output_dir / "experiment-contract.json", root),
                "sha256": sha256(output_dir / "experiment-contract.json"),
            },
            "progress": {
                "path": relative_path(output_dir / "progress.json", root),
                "sha256": sha256(output_dir / "progress.json"),
            },
            "analysis_summary": {
                "path": relative_path(summary_path, root),
                "sha256": sha256(summary_path),
            },
        },
        "analysis": {
            "script": {"path": relative_path(script_path, root), "sha256": sha256(script_path)},
            "python": sys.version.split()[0],
            "packages": {
                "pandas": pd.__version__,
                "numpy": np.__version__,
                "scipy": scipy.__version__,
                "matplotlib": matplotlib.__version__,
            },
            "primary_metric": "profitRatio (higher is better)",
            "primary_unit": "ten paired scene means; each mean uses five paired native perRun values",
            "planned_contrasts": [f"{TARGET} vs {item}" for item in COMPARATORS],
            "test": "two-sided exact Wilcoxon signed-rank",
            "correction": "Holm across three planned contrasts",
            "effect_size": "matched-pairs rank-biserial correlation",
            "bootstrap": {
                "unit": "scene",
                "resamples": BOOTSTRAP_RESAMPLES,
                "interval": "percentile 95% CI",
            },
        },
        "artifacts": artifacts,
        "provenance_notes": [
            "The earlier wrong-seed batch and its derived analysis artifacts were deleted before this run.",
            (
                "Deletion ACL checkpoint retained at "
                "outputs/deletion-checkpoints/20260724-184000-wrong-seed-batch-acl.json."
            ),
            "No old wrong-seed result is included in this Evidence package.",
        ],
        "evidence_directory": relative_path(evidence_dir, root),
    }


def main() -> int:
    args = parse_args()
    root = args.repo_root.resolve()
    output_dir = root / "outputs" / args.experiment_id
    evidence_dir = root / "vault" / "Results" / "Evidence" / args.experiment_id
    figure_dir = evidence_dir / "figures"
    script_path = Path(__file__).resolve()
    git_status_before = git_output(root, "status", "--porcelain=v1").splitlines()

    details, progress, contract, integrity = load_and_validate(output_dir)
    if contract["gitHead"] != git_output(root, "rev-parse", "HEAD"):
        raise ValueError("Current Git HEAD differs from the completed-run contract")

    evidence_dir.mkdir(parents=True, exist_ok=True)
    canonical_rows = build_canonical_data(details)
    data_path = evidence_dir / "data.csv"
    write_csv(data_path, canonical_rows, DATA_FIELDS)

    analysis = build_analysis(details, integrity)
    summary_path = output_dir / "analysis-summary.json"
    write_json(summary_path, analysis)

    figure_dir.mkdir(parents=True, exist_ok=True)
    figure_paths = draw_scene_means(analysis, figure_dir)
    figure_paths.extend(draw_scene_deltas(analysis, details, figure_dir))

    manifest = build_manifest(
        root,
        output_dir,
        evidence_dir,
        script_path,
        summary_path,
        data_path,
        figure_paths,
        progress,
        contract,
        integrity,
        git_status_before,
    )
    manifest_path = evidence_dir / "manifest.json"
    write_json(manifest_path, manifest)

    print(
        json.dumps(
            {
                "experiment_id": args.experiment_id,
                "integrity": integrity,
                "summary": relative_path(summary_path, root),
                "data": relative_path(data_path, root),
                "manifest": relative_path(manifest_path, root),
                "figures": [relative_path(path, root) for path in figure_paths],
            },
            indent=2,
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
