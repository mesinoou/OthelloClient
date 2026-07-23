"""Train a small search-consistency correction for a runtime evaluator."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
from typing import Any

import numpy as np

from training.export_java_model import TERMINAL_SCORE, export_java_model
from training.java_model import merge_java_models, read_java_model
from training.train_model import (
    AUXILIARY_BRANCH_NAMES,
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
        description="Train a residual evaluator on actual search leaves.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path(".training/datasets/search-evaluation-v1"),
    )
    parser.add_argument(
        "--base-model",
        type=Path,
        default=Path("data/evaluation-tables.bin"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/models/eval-005-search-correction"),
    )
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--patience", type=int, default=10)
    parser.add_argument("--learning-rate", type=float, default=2.0e-4)
    parser.add_argument("--huber-delta", type=float, default=0.10)
    parser.add_argument("--output-anchor", type=float, default=0.10)
    parser.add_argument("--l2", type=float, default=1.0e-6)
    parser.add_argument("--residual-clip", type=float, default=1.0)
    parser.add_argument("--occurrence-weight-cap", type=float, default=8.0)
    parser.add_argument("--max-samples-per-phase", type=int, default=None)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--seed", type=int, default=20260805)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_dataset(path: Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as archive:
        required = {
            "phase",
            "static_score",
            "deep_score",
            "occurrences",
            "player",
            *BRANCH_NAMES,
            *(field for specs in PAIR_BRANCHES.values() for field, _, _ in specs),
            *SCALAR_BRANCHES,
        }
        missing = required - set(archive.files)
        if missing:
            raise ValueError(f"{path}: missing arrays {sorted(missing)}")
        return {name: archive[name] for name in archive.files}


def teacher_scores_normalized(
    scores: np.ndarray,
    score_scale: int,
) -> np.ndarray:
    values = scores.astype(np.float32)
    terminal = np.abs(scores.astype(np.int64)) >= TERMINAL_SCORE
    if np.any(terminal):
        signs = np.sign(scores[terminal]).astype(np.float32)
        differences = values[terminal] - signs * TERMINAL_SCORE
        values[terminal] = differences * (score_scale / 64.0)
    return values / float(score_scale)


def residual_targets(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    score_scale: int,
    residual_clip: float,
) -> np.ndarray:
    deep = teacher_scores_normalized(data["deep_score"][indices], score_scale)
    static = data["static_score"][indices].astype(np.float32) / score_scale
    residual = deep - static
    return np.clip(residual, -residual_clip, residual_clip)[:, None]


def sample_weights(
    data: dict[str, np.ndarray],
    indices: np.ndarray,
    cap: float,
) -> np.ndarray:
    weights = np.sqrt(data["occurrences"][indices].astype(np.float32))
    weights = np.minimum(weights, cap)
    return weights / weights.mean()


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


def zero_output_layers(model: PatternModel) -> None:
    for branch in (*BRANCH_NAMES, *AUXILIARY_BRANCH_NAMES):
        layers = [
            int(name.rsplit("_w", 1)[1])
            for name in model.parameters
            if name.startswith(branch + "_w")
        ]
        last = max(layers)
        model.parameters[f"{branch}_w{last}"].fill(0.0)
        model.parameters[f"{branch}_b{last}"].fill(0.0)


def choose_phase_indices(
    data: dict[str, np.ndarray],
    phase: int,
    maximum: int | None,
    rng: np.random.Generator,
) -> np.ndarray:
    indices = np.flatnonzero(data["phase"] == phase)
    if maximum is not None and len(indices) > maximum:
        indices = np.sort(rng.choice(indices, maximum, replace=False))
    return indices


def weighted_huber(
    prediction: Any,
    target: Any,
    weights: Any,
    delta: float,
) -> Any:
    losses = torch.nn.functional.huber_loss(
        prediction,
        target,
        reduction="none",
        delta=delta,
    )[:, 0]
    return (losses * weights).mean()


def correction_metrics(
    correction: np.ndarray,
    target: np.ndarray,
    weights: np.ndarray,
) -> dict[str, float]:
    difference = correction.reshape(-1) - target.reshape(-1)
    baseline = target.reshape(-1)
    return {
        "baseline_mse": float(np.average(baseline**2, weights=weights)),
        "baseline_mae": float(np.average(np.abs(baseline), weights=weights)),
        "corrected_mse": float(np.average(difference**2, weights=weights)),
        "corrected_mae": float(
            np.average(np.abs(difference), weights=weights)
        ),
        "correction_rms": float(np.sqrt(np.mean(correction.reshape(-1) ** 2))),
    }


def train_phase(
    phase: int,
    train: dict[str, np.ndarray],
    validation: dict[str, np.ndarray],
    args: argparse.Namespace,
    score_scale: int,
    rng: np.random.Generator,
    device: Any,
) -> tuple[PatternModel, list[dict[str, float]], dict[str, int]]:
    train_indices = choose_phase_indices(
        train,
        phase,
        args.max_samples_per_phase,
        rng,
    )
    validation_indices = choose_phase_indices(
        validation,
        phase,
        args.max_samples_per_phase,
        rng,
    )
    if len(train_indices) == 0 or len(validation_indices) == 0:
        raise ValueError(f"phase {phase} has an empty train or validation set")

    train_batch = model_batch(train, train_indices, device)
    validation_batch = model_batch(validation, validation_indices, device)
    train_target_np = residual_targets(
        train,
        train_indices,
        score_scale,
        args.residual_clip,
    )
    validation_target_np = residual_targets(
        validation,
        validation_indices,
        score_scale,
        args.residual_clip,
    )
    train_weights_np = sample_weights(
        train,
        train_indices,
        args.occurrence_weight_cap,
    )
    validation_weights_np = sample_weights(
        validation,
        validation_indices,
        args.occurrence_weight_cap,
    )
    train_target = torch.as_tensor(train_target_np, device=device)
    validation_target = torch.as_tensor(validation_target_np, device=device)
    train_weights = torch.as_tensor(train_weights_np, device=device)
    validation_weights = torch.as_tensor(validation_weights_np, device=device)

    template = PatternModel(rng)
    zero_output_layers(template)
    model = TorchPatternModel(template).to(device)
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
        fit_loss = weighted_huber(
            prediction,
            train_target,
            train_weights,
            args.huber_delta,
        )
        output_anchor = torch.square(prediction).mean()
        weight_decay = sum(
            torch.square(value).mean()
            for name, value in model.values.items()
            if "_w" in name
        )
        loss = (
            fit_loss
            + args.output_anchor * output_anchor
            + args.l2 * weight_decay
        )
        loss.backward()
        optimizer.step()

        model.eval()
        with torch.no_grad():
            validation_prediction = model(validation_batch)
            validation_loss = weighted_huber(
                validation_prediction,
                validation_target,
                validation_weights,
                args.huber_delta,
            )
            metrics = correction_metrics(
                validation_prediction[:, 0].detach().cpu().numpy(),
                validation_target_np[:, 0],
                validation_weights_np,
            )
        result = {
            "epoch": epoch,
            "train_objective": float(loss.item()),
            "train_fit_loss": float(fit_loss.item()),
            "validation_objective": float(validation_loss.item()),
            **metrics,
        }
        history.append(result)
        if epoch == 1 or epoch % 5 == 0:
            print(f"phase {phase}: {json.dumps(result)}")
        if result["validation_objective"] < best_loss - 1.0e-7:
            best_loss = result["validation_objective"]
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
            "train": len(train_indices),
            "validation": len(validation_indices),
        },
    )


def evaluate_test(
    models: list[PatternModel],
    test: dict[str, np.ndarray],
    args: argparse.Namespace,
    score_scale: int,
) -> list[dict[str, float]]:
    results = []
    for phase, model in enumerate(models):
        indices = np.flatnonzero(test["phase"] == phase)
        prediction, _ = model.forward(test, indices, cache=False)
        target = residual_targets(
            test,
            indices,
            score_scale,
            args.residual_clip,
        )
        weights = sample_weights(
            test,
            indices,
            args.occurrence_weight_cap,
        )
        results.append(
            {
                "phase": phase,
                "samples": len(indices),
                **correction_metrics(prediction[:, 0], target[:, 0], weights),
            }
        )
    return results


def resolve_device(name: str) -> Any:
    if torch is None:
        raise ValueError("search correction training requires PyTorch")
    if name == "auto":
        name = "cuda" if torch.cuda.is_available() else "cpu"
    if name.startswith("cuda") and not torch.cuda.is_available():
        raise ValueError("CUDA requested but unavailable")
    return torch.device(name)


def validate_args(args: argparse.Namespace) -> None:
    if args.epochs < 1 or args.patience < 1:
        raise ValueError("epochs and patience must be positive")
    if args.learning_rate <= 0.0:
        raise ValueError("learning rate must be positive")
    if args.huber_delta <= 0.0 or args.residual_clip <= 0.0:
        raise ValueError("Huber delta and residual clip must be positive")
    if args.output_anchor < 0.0 or args.l2 < 0.0:
        raise ValueError("regularization coefficients cannot be negative")
    if args.occurrence_weight_cap < 1.0:
        raise ValueError("occurrence weight cap must be at least 1")
    if (
        args.max_samples_per_phase is not None
        and args.max_samples_per_phase < 1
    ):
        raise ValueError("max samples per phase must be positive")


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

    base = read_java_model(args.base_model)
    if base.score_divisor != 1:
        raise ValueError("base model score divisor must be 1")
    device = resolve_device(args.device)
    torch.manual_seed(args.seed)
    if device.type == "cuda":
        torch.cuda.manual_seed_all(args.seed)
    print(f"search correction training device: {device}")

    train = load_dataset(args.dataset_dir / "train.npz")
    validation = load_dataset(args.dataset_dir / "validation.npz")
    test = load_dataset(args.dataset_dir / "test.npz")
    rng = np.random.default_rng(args.seed)
    models = []
    histories = []
    selections = []
    for phase in range(len(base.phase_starts)):
        model, history, selection = train_phase(
            phase,
            train,
            validation,
            args,
            base.score_scale,
            rng,
            device,
        )
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
        base.phase_starts,
        base.score_scale,
    )
    clipped = export_tables(
        tables_path,
        models,
        base.phase_starts,
        base.score_scale,
        4096,
        True,
    )
    correction_export = export_java_model(tables_path, correction_path)
    candidate_export = merge_java_models(
        args.base_model,
        correction_path,
        candidate_path,
    )
    test_metrics = evaluate_test(models, test, args, base.score_scale)

    metadata = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "base_model": {
            "path": str(args.base_model),
            "sha256": sha256_file(args.base_model),
        },
        "dataset": {
            "path": str(args.dataset_dir),
            "metadata_sha256": sha256_file(
                args.dataset_dir / "metadata.json"
            ),
        },
        "arguments": vars(args) | {
            "dataset_dir": str(args.dataset_dir),
            "base_model": str(args.base_model),
            "output_dir": str(args.output_dir),
        },
        "phase_starts": base.phase_starts,
        "score_scale": base.score_scale,
        "selections": selections,
        "histories": histories,
        "test_metrics": test_metrics,
        "quantization_clipped": clipped,
        "correction_export": correction_export,
        "candidate_export": candidate_export,
        "artifacts": {
            path.name: {
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
            for path in (
                float_path,
                tables_path,
                correction_path,
                candidate_path,
            )
        },
        "smoke_only": args.max_samples_per_phase is not None,
    }
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"search-corrected Java model written: {candidate_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
