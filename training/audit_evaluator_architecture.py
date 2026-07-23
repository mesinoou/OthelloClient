"""Compare additive reweighting with a small antisymmetric interaction head."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import shutil
from typing import Any

import numpy as np

from training.java_model import (
    evaluate_java_model_components,
    evaluate_java_model_features,
    read_java_model,
)
from training.train_model import torch
from training.train_search_correction import (
    concatenate_datasets,
    load_dataset,
    sample_weights,
)


SIGNED_FIELDS = (
    ("mobility_own", "mobility_opponent", 32.0),
    ("frontier_own", "frontier_opponent", 64.0),
)
SIGNED_SCALARS = (
    ("disc_difference", 64.0),
    ("corner_difference", 4.0),
    ("corner_move_difference", 4.0),
    ("stable_edge_difference", 28.0),
    ("parity_access_difference", 32.0),
)
CONTEXT_FIELDS = (
    ("mobility_own", "mobility_opponent", 32.0),
    ("frontier_own", "frontier_opponent", 64.0),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path(".training/datasets/edax-search-evaluation-full-l9"),
    )
    parser.add_argument(
        "--extra-dataset-dir",
        type=Path,
        action="append",
        default=[],
    )
    parser.add_argument(
        "--base-model",
        type=Path,
        default=Path("data/evaluation-tables.bin"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(".training/models/eval-016-architecture-audit"),
    )
    parser.add_argument("--hidden-size", type=int, default=32)
    parser.add_argument("--epochs", type=int, default=400)
    parser.add_argument("--patience", type=int, default=50)
    parser.add_argument("--progress-interval", type=int, default=25)
    parser.add_argument("--learning-rate", type=float, default=2.0e-3)
    parser.add_argument("--linear-learning-rate", type=float, default=1.0e-2)
    parser.add_argument("--huber-delta", type=float, default=0.10)
    parser.add_argument("--output-anchor", type=float, default=0.10)
    parser.add_argument("--l2", type=float, default=1.0e-6)
    parser.add_argument("--residual-clip", type=float, default=1.0)
    parser.add_argument("--occurrence-weight-cap", type=float, default=8.0)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--seed", type=int, default=20260816)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def resolve_device(name: str) -> Any:
    if torch is None:
        raise ValueError("architecture audit requires PyTorch")
    if name == "auto":
        name = "cuda" if torch.cuda.is_available() else "cpu"
    if name.startswith("cuda") and not torch.cuda.is_available():
        raise ValueError("CUDA requested but unavailable")
    return torch.device(name)


def architecture_features(
    model: Any,
    data: dict[str, np.ndarray],
) -> tuple[np.ndarray, np.ndarray]:
    components = evaluate_java_model_components(model, data).astype(np.float32)
    signed_parts = [components / float(model.score_scale)]
    for own, opponent, divisor in SIGNED_FIELDS:
        signed_parts.append(
            (
                data[own].astype(np.float32)
                - data[opponent].astype(np.float32)
            )[:, None]
            / divisor
        )
    for name, divisor in SIGNED_SCALARS:
        signed_parts.append(data[name].astype(np.float32)[:, None] / divisor)

    context_parts = []
    for own, opponent, divisor in CONTEXT_FIELDS:
        context_parts.append(
            (
                data[own].astype(np.float32)
                + data[opponent].astype(np.float32)
            )[:, None]
            / divisor
        )
    context_parts.append(
        ((data["ply"].astype(np.float32) - 30.0) / 30.0)[:, None]
    )
    return (
        np.concatenate(signed_parts, axis=1),
        np.concatenate(context_parts, axis=1),
    )


def residual_targets(
    model: Any,
    data: dict[str, np.ndarray],
    clip: float,
) -> np.ndarray:
    base = evaluate_java_model_features(model, data).astype(np.float32)
    base /= float(model.score_scale)
    teacher = data["edax_score"].astype(np.float32) / 64.0
    return np.clip(teacher - base, -clip, clip)


def occurrence_weights(
    data: dict[str, np.ndarray],
    cap: float,
) -> np.ndarray:
    indices = np.arange(len(data["ply"]))
    return sample_weights(data, indices, cap, False).astype(np.float32)


def balance_source_weights(
    weights: np.ndarray,
    sources: np.ndarray,
) -> np.ndarray:
    balanced = weights.astype(np.float32, copy=True)
    for source in np.unique(sources):
        selected = sources == source
        balanced[selected] /= balanced[selected].mean()
        balanced[selected] /= np.count_nonzero(selected)
    return balanced / balanced.mean()


def weighted_huber_numpy(
    difference: np.ndarray,
    weights: np.ndarray,
    delta: float,
) -> float:
    absolute = np.abs(difference)
    losses = np.where(
        absolute <= delta,
        0.5 * difference**2,
        delta * (absolute - 0.5 * delta),
    )
    return float(np.average(losses, weights=weights))


@dataclass
class FeatureScale:
    signed: np.ndarray
    context_mean: np.ndarray
    context_scale: np.ndarray

    @classmethod
    def fit(
        cls,
        signed: np.ndarray,
        context: np.ndarray,
    ) -> FeatureScale:
        return cls(
            signed=np.maximum(signed.std(axis=0), 1.0e-3),
            context_mean=context.mean(axis=0),
            context_scale=np.maximum(context.std(axis=0), 1.0e-3),
        )

    def transform(
        self,
        signed: np.ndarray,
        context: np.ndarray,
    ) -> tuple[np.ndarray, np.ndarray]:
        return (
            signed / self.signed,
            (context - self.context_mean) / self.context_scale,
        )


if torch is not None:

    class AntisymmetricHead(torch.nn.Module):
        def __init__(
            self,
            signed_size: int,
            context_size: int,
            hidden_size: int,
        ) -> None:
            super().__init__()
            input_size = signed_size + context_size
            if hidden_size == 0:
                self.network = torch.nn.Linear(input_size, 1, bias=False)
                torch.nn.init.zeros_(self.network.weight)
            else:
                self.network = torch.nn.Sequential(
                    torch.nn.Linear(input_size, hidden_size),
                    torch.nn.SiLU(),
                    torch.nn.Linear(hidden_size, 1),
                )
                torch.nn.init.zeros_(self.network[-1].weight)
                torch.nn.init.zeros_(self.network[-1].bias)

        def forward(self, signed: Any, context: Any) -> Any:
            positive = torch.cat((signed, context), dim=1)
            negative = torch.cat((-signed, context), dim=1)
            return 0.5 * (self.network(positive) - self.network(negative))

else:

    class AntisymmetricHead:
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            raise ValueError("architecture audit requires PyTorch")


def phase_tensors(
    data: dict[str, np.ndarray],
    signed: np.ndarray,
    context: np.ndarray,
    target: np.ndarray,
    weights: np.ndarray,
    phase: int,
    scale: FeatureScale,
    device: Any,
) -> tuple[Any, Any, Any, Any, np.ndarray]:
    selected = data["phase"] == phase
    scaled_signed, scaled_context = scale.transform(
        signed[selected],
        context[selected],
    )
    balanced_weights = balance_source_weights(
        weights[selected],
        data["_source"][selected],
    )
    return (
        torch.as_tensor(scaled_signed, device=device),
        torch.as_tensor(scaled_context, device=device),
        torch.as_tensor(target[selected, None], device=device),
        torch.as_tensor(balanced_weights, device=device),
        selected,
    )


def source_metrics(
    prediction: np.ndarray,
    target: np.ndarray,
    weights: np.ndarray,
    sources: np.ndarray,
    delta: float,
    source_names: tuple[str, ...],
) -> list[dict[str, Any]]:
    results = []
    for source, name in enumerate(source_names):
        selected = sources == source
        if not np.any(selected):
            continue
        difference = prediction[selected] - target[selected]
        baseline_mse = float(
            np.average(target[selected] ** 2, weights=weights[selected])
        )
        corrected_mse = float(
            np.average(difference**2, weights=weights[selected])
        )
        baseline_huber = weighted_huber_numpy(
            -target[selected],
            weights[selected],
            delta,
        )
        corrected_huber = weighted_huber_numpy(
            difference,
            weights[selected],
            delta,
        )
        results.append(
            {
                "source": source,
                "name": name,
                "samples": int(np.count_nonzero(selected)),
                "baseline_mse": baseline_mse,
                "corrected_mse": corrected_mse,
                "mse_reduction": 1.0 - corrected_mse / baseline_mse,
                "huber_ratio": corrected_huber / baseline_huber,
            }
        )
    return results


def train_head(
    kind: str,
    phase: int,
    train: dict[str, Any],
    validation: dict[str, Any],
    args: argparse.Namespace,
    device: Any,
    source_names: tuple[str, ...],
) -> tuple[Any, FeatureScale, dict[str, Any]]:
    train_selected = train["data"]["phase"] == phase
    scale = FeatureScale.fit(
        train["signed"][train_selected],
        train["context"][train_selected],
    )
    train_tensors = phase_tensors(
        train["data"],
        train["signed"],
        train["context"],
        train["target"],
        train["weights"],
        phase,
        scale,
        device,
    )
    validation_tensors = phase_tensors(
        validation["data"],
        validation["signed"],
        validation["context"],
        validation["target"],
        validation["weights"],
        phase,
        scale,
        device,
    )
    train_signed, train_context, train_target, train_weights, _ = train_tensors
    val_signed, val_context, val_target, val_weights, val_selected = (
        validation_tensors
    )
    hidden_size = 0 if kind == "linear" else args.hidden_size
    head = AntisymmetricHead(
        train_signed.shape[1],
        train_context.shape[1],
        hidden_size,
    ).to(device)
    learning_rate = (
        args.linear_learning_rate
        if kind == "linear"
        else args.learning_rate
    )
    optimizer = torch.optim.Adam(head.parameters(), lr=learning_rate)
    best_state = {
        name: value.detach().cpu().clone()
        for name, value in head.state_dict().items()
    }
    best_objective = float("inf")
    best_epoch = 0
    stale = 0
    history = []
    val_sources = validation["data"]["_source"][val_selected]
    val_weights_np = validation["weights"][val_selected]
    val_target_np = validation["target"][val_selected]

    for epoch in range(1, args.epochs + 1):
        head.train()
        optimizer.zero_grad(set_to_none=True)
        prediction = head(train_signed, train_context)
        fit_losses = torch.nn.functional.huber_loss(
            prediction,
            train_target,
            reduction="none",
            delta=args.huber_delta,
        )[:, 0]
        fit_loss = (fit_losses * train_weights).mean()
        anchor = torch.square(prediction).mean()
        decay = sum(
            torch.square(parameter).mean()
            for parameter in head.parameters()
        )
        loss = fit_loss + args.output_anchor * anchor + args.l2 * decay
        loss.backward()
        optimizer.step()

        head.eval()
        with torch.no_grad():
            val_prediction = (
                head(val_signed, val_context)[:, 0].cpu().numpy()
            )
        metrics = source_metrics(
            val_prediction,
            val_target_np,
            val_weights_np,
            val_sources,
            args.huber_delta,
            source_names,
        )
        objective = max(item["huber_ratio"] for item in metrics)
        history.append(
            {
                "epoch": epoch,
                "train_loss": float(loss.item()),
                "validation_objective": objective,
                "validation_sources": metrics,
            }
        )
        if epoch == 1 or epoch % args.progress_interval == 0:
            ratios = ",".join(
                f"s{item['source']}={item['huber_ratio']:.4f}"
                for item in metrics
            )
            print(
                f"{kind} phase {phase} epoch {epoch}: "
                f"loss={loss.item():.7f} worst={objective:.4f} "
                f"[{ratios}]",
                flush=True,
            )
        if objective < best_objective - 1.0e-6:
            best_objective = objective
            best_epoch = epoch
            best_state = {
                name: value.detach().cpu().clone()
                for name, value in head.state_dict().items()
            }
            stale = 0
        else:
            stale += 1
            if stale >= args.patience:
                break

    head.load_state_dict(best_state)
    return (
        head,
        scale,
        {
            "kind": kind,
            "phase": phase,
            "epochs": len(history),
            "best_epoch": best_epoch,
            "best_validation_objective": best_objective,
            "history": history,
        },
    )


def predict_all(
    heads: list[Any],
    scales: list[FeatureScale],
    split: dict[str, Any],
    device: Any,
) -> np.ndarray:
    prediction = np.empty(len(split["target"]), dtype=np.float32)
    for phase, (head, scale) in enumerate(zip(heads, scales, strict=True)):
        tensors = phase_tensors(
            split["data"],
            split["signed"],
            split["context"],
            split["target"],
            split["weights"],
            phase,
            scale,
            device,
        )
        signed, context, _, _, selected = tensors
        head.eval()
        with torch.no_grad():
            prediction[selected] = head(signed, context)[:, 0].cpu().numpy()
    return prediction


def load_splits(
    model: Any,
    dataset_dirs: tuple[Path, ...],
    clip: float,
    cap: float,
) -> dict[str, dict[str, Any]]:
    result = {}
    for split_name in ("train", "validation", "test"):
        datasets = []
        for source, directory in enumerate(dataset_dirs):
            data = load_dataset(directory / f"{split_name}.npz")
            data["_source"] = np.full(
                len(data["ply"]),
                source,
                dtype=np.int8,
            )
            datasets.append(data)
        data = concatenate_datasets(datasets)
        signed, context = architecture_features(model, data)
        result[split_name] = {
            "data": data,
            "signed": signed,
            "context": context,
            "target": residual_targets(model, data, clip),
            "weights": occurrence_weights(data, cap),
        }
    return result


def validate_args(args: argparse.Namespace) -> None:
    if args.hidden_size < 1:
        raise ValueError("hidden size must be positive")
    if args.epochs < 1 or args.patience < 1 or args.progress_interval < 1:
        raise ValueError("epoch settings must be positive")
    if args.learning_rate <= 0.0 or args.linear_learning_rate <= 0.0:
        raise ValueError("learning rates must be positive")
    if args.huber_delta <= 0.0 or args.residual_clip <= 0.0:
        raise ValueError("loss settings must be positive")
    if args.output_anchor < 0.0 or args.l2 < 0.0:
        raise ValueError("regularization settings cannot be negative")


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

    device = resolve_device(args.device)
    torch.manual_seed(args.seed)
    if device.type == "cuda":
        torch.cuda.manual_seed_all(args.seed)
    dataset_dirs = (args.dataset_dir, *args.extra_dataset_dir)
    source_names = tuple(path.name for path in dataset_dirs)
    if len(set(source_names)) != len(source_names):
        raise ValueError("dataset directory names must be unique")
    model = read_java_model(args.base_model)
    splits = load_splits(
        model,
        dataset_dirs,
        args.residual_clip,
        args.occurrence_weight_cap,
    )
    print(
        f"architecture audit device={device} sources={len(dataset_dirs)} "
        f"signed={splits['train']['signed'].shape[1]} "
        f"context={splits['train']['context'].shape[1]}",
        flush=True,
    )

    all_results = {}
    for kind in ("linear", "interaction"):
        heads = []
        scales = []
        selections = []
        for phase in range(len(model.phase_starts)):
            head, scale, selection = train_head(
                kind,
                phase,
                splits["train"],
                splits["validation"],
                args,
                device,
                source_names,
            )
            heads.append(head)
            scales.append(scale)
            selections.append(selection)
        prediction = predict_all(heads, scales, splits["test"], device)
        test = splits["test"]
        metrics = source_metrics(
            prediction,
            test["target"],
            test["weights"],
            test["data"]["_source"],
            args.huber_delta,
            source_names,
        )
        phase_metrics = []
        for phase in range(len(model.phase_starts)):
            selected = test["data"]["phase"] == phase
            phase_metrics.append(
                {
                    "phase": phase,
                    "sources": source_metrics(
                        prediction[selected],
                        test["target"][selected],
                        test["weights"][selected],
                        test["data"]["_source"][selected],
                        args.huber_delta,
                        source_names,
                    ),
                }
            )
        all_results[kind] = {
            "selections": selections,
            "test_sources": metrics,
            "test_phases": phase_metrics,
            "parameters": sum(
                parameter.numel()
                for head in heads
                for parameter in head.parameters()
            ),
        }

    linear_by_source = {
        item["source"]: item for item in all_results["linear"]["test_sources"]
    }
    comparison = []
    for item in all_results["interaction"]["test_sources"]:
        linear = linear_by_source[item["source"]]
        comparison.append(
            {
                "source": item["source"],
                "name": item["name"],
                "interaction_mse_reduction_vs_linear": (
                    1.0
                    - item["corrected_mse"] / linear["corrected_mse"]
                ),
                "linear_mse_reduction": linear["mse_reduction"],
                "interaction_mse_reduction": item["mse_reduction"],
            }
        )

    output = {
        "base_model": str(args.base_model),
        "datasets": [str(path) for path in dataset_dirs],
        "device": str(device),
        "seed": args.seed,
        "signed_features": int(splits["train"]["signed"].shape[1]),
        "context_features": int(splits["train"]["context"].shape[1]),
        "hidden_size": args.hidden_size,
        "results": all_results,
        "comparison": comparison,
    }
    output_path = args.output_dir / "architecture-audit.json"
    output_path.write_text(
        json.dumps(output, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(comparison, indent=2), flush=True)
    print(f"wrote {output_path}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
