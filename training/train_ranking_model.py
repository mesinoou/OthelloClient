"""Fine-tune an existing evaluator with pairwise move-ranking loss."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
from typing import Any

import numpy as np

from training.export_java_model import export_java_model
from training.train_model import (
    BRANCH_NAMES,
    PAIR_BRANCHES,
    PatternModel,
    SCALAR_BRANCHES,
    TorchPatternModel,
    export_tables,
    save_float_models,
    torch,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fine-tune phase models on deep-search move rankings.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path(".training/datasets/ranking-v1-d8-d10"),
    )
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/models/eval-004-ranking"),
    )
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--patience", type=int, default=12)
    parser.add_argument("--learning-rate", type=float, default=1.0e-4)
    parser.add_argument("--l2", type=float, default=1.0e-6)
    parser.add_argument("--output-anchor", type=float, default=0.20)
    parser.add_argument("--parameter-anchor", type=float, default=1.0e-3)
    parser.add_argument("--rank-logit-scale", type=float, default=4.0)
    parser.add_argument("--pairwise-loss-weight", type=float, default=1.0)
    parser.add_argument("--top1-loss-weight", type=float, default=0.0)
    parser.add_argument("--all-parents", action="store_true")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--seed", type=int, default=20260804)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def load_dataset(path: Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as archive:
        return {name: archive[name] for name in archive.files}


def load_base_models(
    path: Path,
) -> tuple[list[PatternModel], tuple[int, ...], int]:
    with np.load(path, allow_pickle=False) as archive:
        phase_starts = tuple(int(value) for value in archive["phase_starts"])
        score_scale = int(archive["score_scale"][0])
        models = []
        for phase in range(len(phase_starts)):
            model = PatternModel(np.random.default_rng(0))
            for name in model.parameters:
                model.parameters[name][...] = archive[f"phase{phase}_{name}"]
            models.append(model)
    return models, phase_starts, score_scale


def model_batch(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    device: Any,
) -> dict[str, Any]:
    fields = (
        "player",
        *BRANCH_NAMES,
        *(field for specs in PAIR_BRANCHES.values() for field, _, _ in specs),
        *SCALAR_BRANCHES,
    )
    return {
        name: torch.as_tensor(data[name][indices], device=device)
        for name in fields
    }


def ranking_pairs(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    score_scale: int,
    device: Any,
) -> tuple[Any, Any, Any]:
    local = {int(index): offset for offset, index in enumerate(indices)}
    parents: dict[int, list[int]] = {}
    for index in indices:
        parents.setdefault(int(data["parent_id"][index]), []).append(int(index))
    left = []
    right = []
    weights = []
    for rows in parents.values():
        for first_offset, first in enumerate(rows):
            for second in rows[first_offset + 1 :]:
                difference = int(data["deep_score"][first]) - int(
                    data["deep_score"][second]
                )
                if difference == 0:
                    continue
                winner, loser = (
                    (first, second) if difference > 0 else (second, first)
                )
                left.append(local[winner])
                right.append(local[loser])
                normalized = abs(difference) / score_scale
                weights.append(float(np.clip(np.sqrt(normalized), 0.25, 4.0)))
    if not left:
        raise ValueError("selection has no non-tied ranking pairs")
    return (
        torch.as_tensor(left, dtype=torch.long, device=device),
        torch.as_tensor(right, dtype=torch.long, device=device),
        torch.as_tensor(weights, dtype=torch.float32, device=device),
    )


def ranking_groups(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    device: Any,
) -> list[tuple[Any, Any]]:
    local = {int(index): offset for offset, index in enumerate(indices)}
    parents: dict[int, list[int]] = {}
    for index in indices:
        parents.setdefault(int(data["parent_id"][index]), []).append(int(index))
    groups = []
    for rows in parents.values():
        target = np.asarray([int(data["deep_score"][row]) for row in rows])
        best = np.flatnonzero(target == target.max())
        groups.append(
            (
                torch.as_tensor(
                    [local[row] for row in rows],
                    dtype=torch.long,
                    device=device,
                ),
                torch.as_tensor(best, dtype=torch.long, device=device),
            )
        )
    return groups


def select_phase(
    data: dict[str, np.ndarray],
    phase: int,
    stable_only: bool,
) -> np.ndarray:
    selected = data["phase"] == phase
    if stable_only:
        selected &= data["teacher_stable"]
    return np.flatnonzero(selected)


def ranking_loss(
    prediction: Any,
    pairs: tuple[Any, Any, Any],
    scale: float,
) -> Any:
    left, right, weights = pairs
    difference = prediction[left, 0] - prediction[right, 0]
    return (torch.nn.functional.softplus(-scale * difference) * weights).mean()


def top1_loss(
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
    return torch.stack(losses).mean()


def validation_metrics(
    prediction: np.ndarray,
    data: dict[str, np.ndarray],
    indices: np.ndarray,
) -> dict[str, float]:
    top1 = []
    regrets = []
    parents: dict[int, list[int]] = {}
    local = {int(index): offset for offset, index in enumerate(indices)}
    for index in indices:
        parents.setdefault(int(data["parent_id"][index]), []).append(int(index))
    for rows in parents.values():
        target = np.asarray([int(data["deep_score"][row]) for row in rows])
        estimate = np.asarray([prediction[local[row]] for row in rows])
        target_top = set(np.flatnonzero(target == target.max()).tolist())
        estimate_top = set(np.flatnonzero(estimate == estimate.max()).tolist())
        top1.append(bool(target_top & estimate_top))
        regrets.append(int(target.max()) - int(target[list(estimate_top)].max()))
    return {
        "top1_accuracy": float(np.mean(top1)),
        "mean_regret": float(np.mean(regrets)),
        "median_regret": float(np.median(regrets)),
    }


def fine_tune_phase(
    phase: int,
    base: PatternModel,
    train: dict[str, np.ndarray],
    validation: dict[str, np.ndarray],
    args: argparse.Namespace,
    score_scale: int,
    device: Any,
) -> tuple[PatternModel, list[dict[str, float]]]:
    stable_only = not args.all_parents
    train_indices = select_phase(train, phase, stable_only)
    validation_indices = select_phase(validation, phase, stable_only)
    train_batch = model_batch(train, train_indices, device)
    validation_batch = model_batch(validation, validation_indices, device)
    train_pairs = ranking_pairs(
        train,
        train_indices,
        score_scale,
        device,
    )
    validation_pairs = ranking_pairs(
        validation,
        validation_indices,
        score_scale,
        device,
    )
    train_groups = ranking_groups(train, train_indices, device)
    validation_groups = ranking_groups(
        validation,
        validation_indices,
        device,
    )

    model = TorchPatternModel(base).to(device)
    model.train()
    with torch.no_grad():
        base_train_prediction = model(train_batch).detach()
    base_parameters = {
        name: value.detach().clone() for name, value in model.values.items()
    }
    optimizer = torch.optim.Adam(model.parameters(), lr=args.learning_rate)
    best_state = {
        name: value.detach().cpu().clone()
        for name, value in model.state_dict().items()
    }
    best_loss = float("inf")
    stale = 0
    history = []
    for epoch in range(1, args.epochs + 1):
        model.train()
        optimizer.zero_grad(set_to_none=True)
        prediction = model(train_batch)
        pair_loss = ranking_loss(
            prediction,
            train_pairs,
            args.rank_logit_scale,
        )
        best_move_loss = top1_loss(
            prediction,
            train_groups,
            args.rank_logit_scale,
        )
        output_anchor = torch.square(
            prediction - base_train_prediction
        ).mean()
        parameter_anchor = sum(
            torch.square(value - base_parameters[name]).mean()
            for name, value in model.values.items()
        )
        weight_decay = sum(
            torch.square(value).mean()
            for name, value in model.values.items()
            if "_w" in name
        )
        loss = (
            args.pairwise_loss_weight * pair_loss
            + args.top1_loss_weight * best_move_loss
            + args.output_anchor * output_anchor
            + args.parameter_anchor * parameter_anchor
            + args.l2 * weight_decay
        )
        loss.backward()
        optimizer.step()

        model.eval()
        with torch.no_grad():
            validation_prediction = model(validation_batch)
            validation_pair_loss = ranking_loss(
                validation_prediction,
                validation_pairs,
                args.rank_logit_scale,
            )
            validation_top1_loss = top1_loss(
                validation_prediction,
                validation_groups,
                args.rank_logit_scale,
            )
            validation_loss = float(
                (
                    args.pairwise_loss_weight * validation_pair_loss
                    + args.top1_loss_weight * validation_top1_loss
                ).item()
            )
            metrics = validation_metrics(
                validation_prediction[:, 0].detach().cpu().numpy(),
                validation,
                validation_indices,
            )
        result = {
            "epoch": epoch,
            "train_pairwise_loss": float(pair_loss.item()),
            "train_top1_loss": float(best_move_loss.item()),
            "validation_objective": validation_loss,
            "validation_pairwise_loss": float(validation_pair_loss.item()),
            "validation_top1_loss": float(validation_top1_loss.item()),
            **metrics,
        }
        history.append(result)
        if epoch == 1 or epoch % 5 == 0:
            print(f"phase {phase}: {json.dumps(result)}")
        if validation_loss < best_loss - 1.0e-7:
            best_loss = validation_loss
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
    return result_model, history


def resolve_device(name: str) -> Any:
    if torch is None:
        raise ValueError("ranking fine-tuning requires PyTorch")
    if name == "auto":
        name = "cuda" if torch.cuda.is_available() else "cpu"
    if name.startswith("cuda") and not torch.cuda.is_available():
        raise ValueError("CUDA requested but unavailable")
    return torch.device(name)


def main() -> int:
    args = parse_args()
    if args.epochs < 1 or args.patience < 1:
        raise ValueError("epochs and patience must be positive")
    if args.pairwise_loss_weight < 0.0 or args.top1_loss_weight < 0.0:
        raise ValueError("ranking loss weights must be non-negative")
    if args.pairwise_loss_weight + args.top1_loss_weight == 0.0:
        raise ValueError("at least one ranking loss weight must be positive")
    if args.output_dir.exists():
        if not args.overwrite:
            raise FileExistsError(
                f"output directory exists; pass --overwrite: {args.output_dir}"
            )
        shutil.rmtree(args.output_dir)
    args.output_dir.mkdir(parents=True)
    device = resolve_device(args.device)
    torch.manual_seed(args.seed)
    if device.type == "cuda":
        torch.cuda.manual_seed_all(args.seed)
    print(f"ranking training device: {device}")

    train = load_dataset(args.dataset_dir / "train.npz")
    validation = load_dataset(args.dataset_dir / "validation.npz")
    base_models, phase_starts, score_scale = load_base_models(args.base_model)
    models = []
    histories = []
    for phase, base in enumerate(base_models):
        model, history = fine_tune_phase(
            phase,
            base,
            train,
            validation,
            args,
            score_scale,
            device,
        )
        models.append(model)
        histories.append({"phase": phase, "epochs": history})

    float_path = args.output_dir / "model-float.npz"
    tables_path = args.output_dir / "evaluation-tables.npz"
    binary_path = args.output_dir / "evaluation-tables.bin"
    save_float_models(float_path, models, phase_starts, score_scale)
    clipped = export_tables(
        tables_path,
        models,
        phase_starts,
        score_scale,
        4096,
        True,
    )
    java_export = export_java_model(tables_path, binary_path)
    metadata = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "base_model": str(args.base_model),
        "ranking_dataset": str(args.dataset_dir),
        "phase_starts": phase_starts,
        "score_scale": score_scale,
        "stable_only": not args.all_parents,
        "arguments": vars(args) | {
            "dataset_dir": str(args.dataset_dir),
            "base_model": str(args.base_model),
            "output_dir": str(args.output_dir),
        },
        "histories": histories,
        "clipped": clipped,
        "java_export": java_export,
    }
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"ranking model written: {binary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
