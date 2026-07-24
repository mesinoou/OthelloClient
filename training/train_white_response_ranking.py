"""Train a phase-limited WLD ranking correction from Edax sibling scores."""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
from typing import Any, Iterable

import numpy as np

from training.export_java_model import export_java_model
from training.java_model import (
    evaluate_java_model_features,
    merge_java_models,
    read_java_model,
)
from training.materialize_dataset import add_features
from training.train_model import (
    PatternModel,
    TorchPatternModel,
    export_tables,
    save_float_models,
    torch,
)
from training.train_search_correction import (
    model_batch,
    resolve_device,
    zero_output_layers,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Train a lightweight phase 0-1 correction that ranks white "
            "responses by Edax WLD class."
        ),
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--input-tsv",
        type=Path,
        action="append",
        required=True,
        help="Edax-scored sibling TSV; may be repeated",
    )
    parser.add_argument(
        "--base-model",
        type=Path,
        default=Path("data/evaluation-tables-tournament.bin"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/models/eval-019-white-response-ranking"),
    )
    parser.add_argument(
        "--train-phases",
        default="0,1",
        help="comma-separated runtime phases to correct",
    )
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--patience", type=int, default=15)
    parser.add_argument("--progress-interval", type=int, default=5)
    parser.add_argument("--learning-rate", type=float, default=2.0e-4)
    parser.add_argument("--rank-logit-scale", type=float, default=6.0)
    parser.add_argument("--wld-pair-weight", type=float, default=1.0)
    parser.add_argument("--wld-top1-weight", type=float, default=0.5)
    parser.add_argument("--margin-pair-weight", type=float, default=0.05)
    parser.add_argument("--output-anchor", type=float, default=0.5)
    parser.add_argument("--soft-limit", type=float, default=0.15)
    parser.add_argument("--soft-limit-weight", type=float, default=5.0)
    parser.add_argument("--l2", type=float, default=1.0e-6)
    parser.add_argument("--validation-fraction", type=float, default=1.0 / 6.0)
    parser.add_argument("--test-fraction", type=float, default=1.0 / 6.0)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--seed", type=int, default=20260724)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames is None:
            raise ValueError(f"{path}: TSV has no header")
        required = {
            "decision_id",
            "game",
            "opening",
            "learned_color",
            "ply",
            "candidate_move",
            "selected",
            "black",
            "white",
            "player",
            "edax_score",
        }
        missing = required - set(reader.fieldnames)
        if missing:
            raise ValueError(f"{path}: missing columns {sorted(missing)}")
        return list(reader)


def wld_class(score: int | np.integer[Any]) -> int:
    return int(score > 0) - int(score < 0)


def runtime_phase(ply: int, phase_starts: tuple[int, ...]) -> int:
    return int(
        np.searchsorted(
            np.asarray(phase_starts[1:], dtype=np.int16),
            ply,
            side="right",
        )
    )


def materialize_siblings(
    paths: Iterable[Path],
    base_model: Any,
) -> tuple[dict[str, np.ndarray], dict[str, int]]:
    records: list[dict[str, Any]] = []
    decision_numbers: dict[tuple[int, str], int] = {}
    decision_rows: dict[int, list[int]] = defaultdict(list)
    source_counts: dict[str, int] = {}

    for source, path in enumerate(paths):
        source_rows = read_tsv(path)
        accepted = 0
        for row in source_rows:
            if row["learned_color"] != "white":
                continue
            child_player = int(row["player"])
            if child_player != 1:
                raise ValueError(
                    f"{path}: white response child must have black to move"
                )
            key = (source, row["decision_id"])
            parent_id = decision_numbers.setdefault(key, len(decision_numbers))
            teacher_score = -int(row["edax_score"])
            records.append(
                {
                    "black": int(row["black"], 16),
                    "white": int(row["white"], 16),
                    "player": child_player,
                    "ply": int(row["ply"]) + 1,
                    "parent_ply": int(row["ply"]),
                    "parent_id": parent_id,
                    "game": int(row["game"]),
                    "opening": int(row["opening"]),
                    "source_id": source,
                    "candidate_move": int(row["candidate_move"]),
                    "selected": row["selected"].lower() == "true",
                    "teacher_score": teacher_score,
                    "teacher_wld": wld_class(teacher_score),
                    "teacher_exact": (
                        row.get("edax_score_bound", "exact") == "exact"
                    ),
                }
            )
            decision_rows[parent_id].append(len(records) - 1)
            accepted += 1
        source_counts[str(path)] = accepted

    if not records:
        raise ValueError("no white-response candidates found")
    for parent_id, indices in decision_rows.items():
        selected = sum(bool(records[index]["selected"]) for index in indices)
        if selected != 1:
            raise ValueError(
                f"decision {parent_id} must contain exactly one selected move"
            )
        openings = {records[index]["opening"] for index in indices}
        plies = {records[index]["parent_ply"] for index in indices}
        if len(openings) != 1 or len(plies) != 1:
            raise ValueError(f"decision {parent_id} has inconsistent metadata")

    integer_types = {
        "black": np.uint64,
        "white": np.uint64,
        "player": np.int8,
        "ply": np.uint8,
        "parent_ply": np.uint8,
        "parent_id": np.int32,
        "game": np.int32,
        "opening": np.int32,
        "source_id": np.int16,
        "candidate_move": np.uint8,
        "teacher_score": np.int16,
        "teacher_wld": np.int8,
    }
    data = {
        name: np.asarray(
            [record[name] for record in records],
            dtype=dtype,
        )
        for name, dtype in integer_types.items()
    }
    data["selected"] = np.asarray(
        [record["selected"] for record in records],
        dtype=np.bool_,
    )
    data["teacher_exact"] = np.asarray(
        [record["teacher_exact"] for record in records],
        dtype=np.bool_,
    )
    data = add_features(data, "white-response siblings")
    child_static = evaluate_java_model_features(base_model, data)
    data["base_parent_score"] = (
        -child_static.astype(np.float32) / float(base_model.score_scale)
    )
    data["phase"] = np.asarray(
        [
            runtime_phase(int(ply), base_model.phase_starts)
            for ply in data["ply"]
        ],
        dtype=np.int8,
    )
    return data, source_counts


def split_openings(
    openings: np.ndarray,
    validation_fraction: float,
    test_fraction: float,
    seed: int,
) -> dict[str, tuple[int, ...]]:
    unique = np.unique(openings).astype(np.int32)
    if len(unique) < 5:
        raise ValueError("at least five distinct openings are required")
    shuffled = unique.copy()
    np.random.default_rng(seed).shuffle(shuffled)
    validation_count = max(1, int(round(len(unique) * validation_fraction)))
    test_count = max(1, int(round(len(unique) * test_fraction)))
    while validation_count + test_count >= len(unique):
        if test_count >= validation_count and test_count > 1:
            test_count -= 1
        elif validation_count > 1:
            validation_count -= 1
        else:
            raise ValueError("fractions leave no training openings")
    return {
        "test": tuple(sorted(int(value) for value in shuffled[:test_count])),
        "validation": tuple(
            sorted(
                int(value)
                for value in shuffled[
                    test_count : test_count + validation_count
                ]
            )
        ),
        "train": tuple(
            sorted(
                int(value)
                for value in shuffled[test_count + validation_count :]
            )
        ),
    }


def select_indices(
    data: dict[str, np.ndarray],
    openings: tuple[int, ...],
    phase: int | None = None,
) -> np.ndarray:
    selected = np.isin(
        data["opening"],
        np.asarray(openings, dtype=np.int32),
    )
    if phase is not None:
        selected &= data["phase"] == phase
    return np.flatnonzero(selected)


def grouped_local_rows(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
) -> list[np.ndarray]:
    parents: dict[int, list[int]] = defaultdict(list)
    for local, index in enumerate(indices):
        parents[int(data["parent_id"][index])].append(local)
    return [
        np.asarray(rows, dtype=np.int64)
        for _, rows in sorted(parents.items())
    ]


def ranking_pairs(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    device: Any,
) -> tuple[tuple[Any, Any, Any], tuple[Any, Any, Any]]:
    cross_winners: list[int] = []
    cross_losers: list[int] = []
    cross_weights: list[float] = []
    margin_winners: list[int] = []
    margin_losers: list[int] = []
    margin_weights: list[float] = []
    scores = data["teacher_score"][indices]
    classes = data["teacher_wld"][indices]
    exact = data["teacher_exact"][indices]
    for rows in grouped_local_rows(data, indices):
        for first_offset, first in enumerate(rows):
            for second in rows[first_offset + 1 :]:
                first_class = int(classes[first])
                second_class = int(classes[second])
                first_score = int(scores[first])
                second_score = int(scores[second])
                if first_class != second_class:
                    winner, loser = (
                        (first, second)
                        if first_class > second_class
                        else (second, first)
                    )
                    cross_winners.append(int(winner))
                    cross_losers.append(int(loser))
                    cross_weights.append(
                        float(abs(first_class - second_class))
                    )
                elif (
                    exact[first]
                    and exact[second]
                    and first_score != second_score
                ):
                    winner, loser = (
                        (first, second)
                        if first_score > second_score
                        else (second, first)
                    )
                    margin_winners.append(int(winner))
                    margin_losers.append(int(loser))
                    difference = abs(first_score - second_score)
                    margin_weights.append(
                        float(np.clip(np.sqrt(difference / 8.0), 0.25, 2.0))
                    )

    def tensors(
        winners: list[int],
        losers: list[int],
        weights: list[float],
    ) -> tuple[Any, Any, Any]:
        return (
            torch.as_tensor(winners, dtype=torch.long, device=device),
            torch.as_tensor(losers, dtype=torch.long, device=device),
            torch.as_tensor(weights, dtype=torch.float32, device=device),
        )

    return (
        tensors(cross_winners, cross_losers, cross_weights),
        tensors(margin_winners, margin_losers, margin_weights),
    )


def ranking_groups(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    device: Any,
) -> list[tuple[Any, Any]]:
    classes = data["teacher_wld"][indices]
    groups = []
    for rows in grouped_local_rows(data, indices):
        best_class = classes[rows].max()
        best = np.flatnonzero(classes[rows] == best_class)
        groups.append(
            (
                torch.as_tensor(rows, dtype=torch.long, device=device),
                torch.as_tensor(best, dtype=torch.long, device=device),
            )
        )
    return groups


def pairwise_loss(
    prediction: Any,
    pairs: tuple[Any, Any, Any],
    scale: float,
) -> Any:
    winners, losers, weights = pairs
    if len(winners) == 0:
        return prediction.sum() * 0.0
    difference = prediction[winners, 0] - prediction[losers, 0]
    losses = torch.nn.functional.softplus(-scale * difference)
    return (losses * weights).sum() / weights.sum()


def top1_wld_loss(
    prediction: Any,
    groups: list[tuple[Any, Any]],
    scale: float,
) -> Any:
    losses = []
    for rows, best in groups:
        logits = prediction[rows, 0] * scale
        losses.append(
            torch.logsumexp(logits, dim=0)
            - torch.logsumexp(logits[best], dim=0)
        )
    if not losses:
        return prediction.sum() * 0.0
    return torch.stack(losses).mean()


def ranking_metrics(
    prediction: np.ndarray,
    data: dict[str, np.ndarray],
    indices: np.ndarray,
) -> dict[str, float | int]:
    scores = data["teacher_score"][indices]
    classes = data["teacher_wld"][indices]
    exact_top1 = []
    wld_top1 = []
    wld_downgrades = []
    class_regrets = []
    disc_regrets = []
    major_regrets = []
    for rows in grouped_local_rows(data, indices):
        estimates = prediction[rows]
        estimate_top = rows[np.flatnonzero(estimates == estimates.max())]
        teacher_scores = scores[rows]
        teacher_classes = classes[rows]
        best_score = int(teacher_scores.max())
        best_class = int(teacher_classes.max())
        chosen_score = int(scores[estimate_top].max())
        chosen_class = int(classes[estimate_top].max())
        exact_top1.append(chosen_score == best_score)
        wld_top1.append(chosen_class == best_class)
        downgrade = chosen_class < best_class
        wld_downgrades.append(downgrade)
        class_regrets.append(best_class - chosen_class)
        regret = best_score - chosen_score
        disc_regrets.append(regret)
        major_regrets.append(regret >= 4)
    if not exact_top1:
        return {
            "decisions": 0,
            "exact_top1_accuracy": 0.0,
            "wld_top1_accuracy": 0.0,
            "wld_downgrade_rate": 0.0,
            "mean_class_regret": 0.0,
            "mean_disc_regret": 0.0,
            "major_regret_rate": 0.0,
        }
    return {
        "decisions": len(exact_top1),
        "exact_top1_accuracy": float(np.mean(exact_top1)),
        "wld_top1_accuracy": float(np.mean(wld_top1)),
        "wld_downgrade_rate": float(np.mean(wld_downgrades)),
        "mean_class_regret": float(np.mean(class_regrets)),
        "mean_disc_regret": float(np.mean(disc_regrets)),
        "major_regret_rate": float(np.mean(major_regrets)),
    }


def metric_selection_key(
    metrics: dict[str, float | int],
    correction_rms: float,
) -> tuple[float, ...]:
    """Prefer WLD safety, then margin quality, then smaller corrections."""
    return (
        float(metrics["wld_downgrade_rate"]),
        float(metrics["mean_class_regret"]),
        float(metrics["major_regret_rate"]),
        float(metrics["mean_disc_regret"]),
        correction_rms,
    )


def phase_objective(
    prediction: Any,
    cross_pairs: tuple[Any, Any, Any],
    margin_pairs: tuple[Any, Any, Any],
    groups: list[tuple[Any, Any]],
    args: argparse.Namespace,
) -> tuple[Any, dict[str, Any]]:
    cross = pairwise_loss(prediction, cross_pairs, args.rank_logit_scale)
    margin = pairwise_loss(prediction, margin_pairs, args.rank_logit_scale)
    top1 = top1_wld_loss(prediction, groups, args.rank_logit_scale)
    objective = (
        args.wld_pair_weight * cross
        + args.wld_top1_weight * top1
        + args.margin_pair_weight * margin
    )
    return objective, {
        "wld_pair_loss": cross,
        "wld_top1_loss": top1,
        "margin_pair_loss": margin,
    }


def train_phase(
    phase: int,
    data: dict[str, np.ndarray],
    splits: dict[str, tuple[int, ...]],
    args: argparse.Namespace,
    rng: np.random.Generator,
    device: Any,
) -> tuple[PatternModel, list[dict[str, float | int]], dict[str, int]]:
    train_indices = select_indices(data, splits["train"], phase)
    validation_indices = select_indices(data, splits["validation"], phase)
    if len(train_indices) == 0 or len(validation_indices) == 0:
        raise ValueError(f"phase {phase} has an empty train or validation set")

    train_batch = model_batch(data, train_indices, device)
    validation_batch = model_batch(data, validation_indices, device)
    train_base = torch.as_tensor(
        data["base_parent_score"][train_indices, None],
        device=device,
    )
    validation_base = torch.as_tensor(
        data["base_parent_score"][validation_indices, None],
        device=device,
    )
    train_pairs = ranking_pairs(data, train_indices, device)
    validation_pairs = ranking_pairs(data, validation_indices, device)
    train_groups = ranking_groups(data, train_indices, device)
    validation_groups = ranking_groups(data, validation_indices, device)
    if len(train_pairs[0][0]) == 0 and len(train_pairs[1][0]) == 0:
        raise ValueError(f"phase {phase} training set has no ranking pairs")

    template = PatternModel(rng)
    zero_output_layers(template)
    model = TorchPatternModel(template).to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=args.learning_rate)
    best_state = {
        name: value.detach().cpu().clone()
        for name, value in model.state_dict().items()
    }
    with torch.no_grad():
        baseline_rank, _ = phase_objective(
            validation_base,
            *validation_pairs,
            validation_groups,
            args,
        )
    baseline_metrics = ranking_metrics(
        validation_base[:, 0].cpu().numpy(),
        data,
        validation_indices,
    )
    best_key = metric_selection_key(baseline_metrics, 0.0)
    best_epoch = 0
    best_objective = float(baseline_rank.item())
    stale = 0
    history: list[dict[str, float | int]] = []
    for epoch in range(1, args.epochs + 1):
        model.train()
        optimizer.zero_grad(set_to_none=True)
        correction = model(train_batch)
        prediction = train_base - correction
        rank_objective, losses = phase_objective(
            prediction,
            *train_pairs,
            train_groups,
            args,
        )
        anchor = torch.square(correction).mean()
        overflow = torch.square(
            torch.relu(torch.abs(correction) - args.soft_limit)
        ).mean()
        weight_decay = sum(
            torch.square(value).mean()
            for name, value in model.values.items()
            if "_w" in name
        )
        loss = (
            rank_objective
            + args.output_anchor * anchor
            + args.soft_limit_weight * overflow
            + args.l2 * weight_decay
        )
        loss.backward()
        optimizer.step()

        model.eval()
        with torch.no_grad():
            validation_correction = model(validation_batch)
            validation_prediction = validation_base - validation_correction
            validation_rank, validation_losses = phase_objective(
                validation_prediction,
                *validation_pairs,
                validation_groups,
                args,
            )
            validation_anchor = torch.square(validation_correction).mean()
            validation_overflow = torch.square(
                torch.relu(
                    torch.abs(validation_correction) - args.soft_limit
                )
            ).mean()
            validation_objective = float(
                (
                    validation_rank
                    + args.output_anchor * validation_anchor
                    + args.soft_limit_weight * validation_overflow
                ).item()
            )
            metrics = ranking_metrics(
                validation_prediction[:, 0].cpu().numpy(),
                data,
                validation_indices,
            )
        result: dict[str, float | int] = {
            "epoch": epoch,
            "train_objective": float(loss.item()),
            "train_wld_pair_loss": float(losses["wld_pair_loss"].item()),
            "train_wld_top1_loss": float(losses["wld_top1_loss"].item()),
            "train_margin_pair_loss": float(losses["margin_pair_loss"].item()),
            "train_correction_rms": float(torch.sqrt(anchor).item()),
            "validation_objective": validation_objective,
            "validation_wld_pair_loss": float(
                validation_losses["wld_pair_loss"].item()
            ),
            "validation_wld_top1_loss": float(
                validation_losses["wld_top1_loss"].item()
            ),
            "validation_margin_pair_loss": float(
                validation_losses["margin_pair_loss"].item()
            ),
            "validation_correction_rms": float(
                torch.sqrt(validation_anchor).item()
            ),
            **metrics,
        }
        history.append(result)
        if epoch == 1 or epoch % args.progress_interval == 0:
            print(f"phase {phase}: {json.dumps(result)}")
        selection_key = metric_selection_key(
            metrics,
            result["validation_correction_rms"],
        )
        if selection_key < best_key:
            best_key = selection_key
            best_epoch = epoch
            best_objective = validation_objective
            best_state = {
                name: value.detach().cpu().clone()
                for name, value in model.state_dict().items()
            }
            stale = 0
        else:
            stale += 1
            if stale >= args.patience:
                print(f"phase {phase}: early stop at epoch {epoch}")
                break

    model.load_state_dict(best_state)
    result_model = PatternModel(np.random.default_rng(0))
    for name, value in model.values.items():
        result_model.parameters[name][...] = value.detach().cpu().numpy()
    return (
        result_model,
        history,
        {
            "train_candidates": len(train_indices),
            "validation_candidates": len(validation_indices),
            "train_decisions": len(grouped_local_rows(data, train_indices)),
            "validation_decisions": len(
                grouped_local_rows(data, validation_indices)
            ),
            "best_epoch": best_epoch,
            "baseline_validation": baseline_metrics,
            "best_validation_key": best_key,
            "best_validation_objective": best_objective,
        },
    )


def zero_model(rng: np.random.Generator) -> PatternModel:
    model = PatternModel(rng)
    zero_output_layers(model)
    return model


def predictions_for_models(
    models: list[PatternModel],
    data: dict[str, np.ndarray],
    indices: np.ndarray,
) -> np.ndarray:
    prediction = data["base_parent_score"][indices].copy()
    phases = data["phase"][indices]
    for phase, model in enumerate(models):
        selected = np.flatnonzero(phases == phase)
        if len(selected) == 0:
            continue
        correction, _ = model.forward(data, indices[selected], cache=False)
        prediction[selected] -= correction[:, 0]
    return prediction


def evaluate_split(
    name: str,
    models: list[PatternModel],
    data: dict[str, np.ndarray],
    openings: tuple[int, ...],
    train_phases: tuple[int, ...],
) -> dict[str, Any]:
    result: dict[str, Any] = {"split": name, "phases": []}
    all_indices = []
    for phase in train_phases:
        indices = select_indices(data, openings, phase)
        all_indices.append(indices)
        base = ranking_metrics(
            data["base_parent_score"][indices],
            data,
            indices,
        )
        corrected = ranking_metrics(
            predictions_for_models(models, data, indices),
            data,
            indices,
        )
        result["phases"].append(
            {"phase": phase, "base": base, "corrected_float": corrected}
        )
    combined = np.concatenate(all_indices)
    result["base"] = ranking_metrics(
        data["base_parent_score"][combined],
        data,
        combined,
    )
    result["corrected_float"] = ranking_metrics(
        predictions_for_models(models, data, combined),
        data,
        combined,
    )
    return result


def add_quantized_metrics(
    result: dict[str, Any],
    candidate_model: Any,
    data: dict[str, np.ndarray],
    openings: tuple[int, ...],
    train_phases: tuple[int, ...],
) -> None:
    for phase_result in result["phases"]:
        phase = int(phase_result["phase"])
        indices = select_indices(data, openings, phase)
        scores = -evaluate_java_model_features(candidate_model, data)[indices]
        phase_result["corrected_quantized"] = ranking_metrics(
            scores.astype(np.float32),
            data,
            indices,
        )
    combined = np.concatenate(
        [select_indices(data, openings, phase) for phase in train_phases]
    )
    scores = -evaluate_java_model_features(candidate_model, data)[combined]
    result["corrected_quantized"] = ranking_metrics(
        scores.astype(np.float32),
        data,
        combined,
    )


def parse_train_phases(
    value: str,
    phase_count: int,
) -> tuple[int, ...]:
    phases = tuple(sorted({int(item) for item in value.split(",")}))
    if not phases:
        raise ValueError("at least one train phase is required")
    if phases[0] < 0 or phases[-1] >= phase_count:
        raise ValueError(f"train phases must be between 0 and {phase_count - 1}")
    return phases


def validate_args(args: argparse.Namespace) -> None:
    if args.epochs < 1 or args.patience < 1 or args.progress_interval < 1:
        raise ValueError("epoch controls must be positive")
    if args.learning_rate <= 0.0 or args.rank_logit_scale <= 0.0:
        raise ValueError("learning rate and rank scale must be positive")
    weights = (
        args.wld_pair_weight,
        args.wld_top1_weight,
        args.margin_pair_weight,
        args.output_anchor,
        args.soft_limit_weight,
        args.l2,
    )
    if any(weight < 0.0 for weight in weights):
        raise ValueError("loss weights cannot be negative")
    if args.wld_pair_weight + args.wld_top1_weight <= 0.0:
        raise ValueError("at least one WLD loss weight must be positive")
    if args.soft_limit <= 0.0:
        raise ValueError("soft limit must be positive")
    if not 0.0 < args.validation_fraction < 0.5:
        raise ValueError("validation fraction must be between 0 and 0.5")
    if not 0.0 < args.test_fraction < 0.5:
        raise ValueError("test fraction must be between 0 and 0.5")
    if args.validation_fraction + args.test_fraction >= 0.8:
        raise ValueError("validation and test fractions leave too little train")


def main() -> int:
    args = parse_args()
    validate_args(args)
    if args.output_dir.exists():
        if not args.overwrite:
            raise FileExistsError(
                f"output directory exists; pass --overwrite: {args.output_dir}"
            )
        shutil.rmtree(args.output_dir)
    args.output_dir.mkdir(parents=True)

    base_model = read_java_model(args.base_model)
    if base_model.score_divisor != 1:
        raise ValueError("base model score divisor must be 1")
    train_phases = parse_train_phases(
        args.train_phases,
        len(base_model.phase_starts),
    )
    device = resolve_device(args.device)
    torch.manual_seed(args.seed)
    if device.type == "cuda":
        torch.cuda.manual_seed_all(args.seed)
    print(f"white-response ranking device: {device}")

    data, source_counts = materialize_siblings(args.input_tsv, base_model)
    splits = split_openings(
        data["opening"],
        args.validation_fraction,
        args.test_fraction,
        args.seed,
    )
    print(f"opening split: {json.dumps(splits)}")
    rng = np.random.default_rng(args.seed)
    models = []
    histories = []
    selections = []
    for phase in range(len(base_model.phase_starts)):
        if phase in train_phases:
            model, history, selection = train_phase(
                phase,
                data,
                splits,
                args,
                rng,
                device,
            )
        else:
            model = zero_model(rng)
            history = []
            selection = {
                "train_candidates": 0,
                "validation_candidates": 0,
                "train_decisions": 0,
                "validation_decisions": 0,
                "best_epoch": 0,
            }
        models.append(model)
        histories.append({"phase": phase, "epochs": history})
        selections.append({"phase": phase, **selection})

    float_path = args.output_dir / "correction-float.npz"
    tables_path = args.output_dir / "correction-tables.npz"
    correction_path = args.output_dir / "correction-tables.bin"
    candidate_path = args.output_dir / "evaluation-tables.bin"
    save_float_models(
        float_path,
        models,
        base_model.phase_starts,
        base_model.score_scale,
    )
    clipped = export_tables(
        tables_path,
        models,
        base_model.phase_starts,
        base_model.score_scale,
        4096,
        True,
    )
    correction_export = export_java_model(tables_path, correction_path)
    candidate_export = merge_java_models(
        args.base_model,
        correction_path,
        candidate_path,
    )
    candidate_model = read_java_model(candidate_path)

    metrics = []
    for split_name in ("train", "validation", "test"):
        result = evaluate_split(
            split_name,
            models,
            data,
            splits[split_name],
            train_phases,
        )
        add_quantized_metrics(
            result,
            candidate_model,
            data,
            splits[split_name],
            train_phases,
        )
        metrics.append(result)
    artifacts = (float_path, tables_path, correction_path, candidate_path)
    metadata = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": (
            "EVAL-019 phase 0-1 WLD ranking correction for white responses "
            "against strong black play"
        ),
        "base_model": {
            "path": str(args.base_model),
            "sha256": sha256_file(args.base_model),
        },
        "inputs": [
            {
                "path": str(path),
                "sha256": sha256_file(path),
                "accepted_candidates": source_counts[str(path)],
            }
            for path in args.input_tsv
        ],
        "arguments": vars(args)
        | {
            "input_tsv": [str(path) for path in args.input_tsv],
            "base_model": str(args.base_model),
            "output_dir": str(args.output_dir),
        },
        "phase_starts": base_model.phase_starts,
        "train_phases": train_phases,
        "opening_splits": splits,
        "candidates": len(data["player"]),
        "decisions": len(np.unique(data["parent_id"])),
        "selections": selections,
        "histories": histories,
        "metrics": metrics,
        "quantization_clipped": clipped,
        "correction_export": correction_export,
        "candidate_export": candidate_export,
        "artifacts": {
            path.name: {
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
            for path in artifacts
        },
        "deployment_status": "experimental-not-tournament",
    }
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"white-response candidate written: {candidate_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
