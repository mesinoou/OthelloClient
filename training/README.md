# Evaluation model training

This directory contains the offline data and model pipeline for the learned
Othello evaluator. The game client remains pure Java; Python and NumPy are used
only before deployment.

The model follows the approach described in Nyanyan's pattern-evaluation
article: shared neural branches evaluate diagonal, edge-plus-X, and corner
patterns, while another branch evaluates mobility and frontier counts. Every
possible pattern state is evaluated after training and exported as an integer
lookup table, so no neural-network runtime is required during search.

## Requirements

- Python 3.11 or newer
- NumPy 2.x
- Internet access for the initial public-record download

Install the only dependency when it is not already available:

```powershell
python -m pip install -r training/requirements.txt
```

## Build the dataset

Run this command from the repository root:

```powershell
python -m training.build_dataset
```

The command downloads the 20,000 MIT-licensed self-play records at the pinned
upstream commit and creates:

```text
.training/datasets/nyanyan-self-play-v1/
  train.npz
  validation.npz
  test.npz
  metadata.json
```

Games, rather than individual positions, are assigned to the 80/10/10 splits.
Exact positions already present in training are removed from validation and
test. Source hashes, split seed, replay settings, feature definitions, and
dataset hashes are written to `metadata.json`.

Useful development options:

```powershell
python -m training.build_dataset --max-games 100 --overwrite
python -m training.build_dataset --no-download --overwrite
```

Verify hashes, shapes, split isolation, and a deterministic sample of extracted
features:

```powershell
python -m training.verify_dataset
```

## Train

The default command trains all four game phases and writes both floating-point
weights and quantized lookup tables:

```powershell
python -m training.train_model
```

Outputs:

```text
.training/models/pattern-evaluation-v1/
  model-float.npz
  evaluation-tables.npz
  metadata.json
```

For a quick pipeline check:

```powershell
python -m training.train_model `
  --epochs 2 `
  --max-samples-per-phase 4096 `
  --output-dir .training/models/smoke `
  --overwrite
```

An output trained with `--max-samples-per-phase` is marked `smoke_only` and
must not be promoted to the Java client. Full training uses all available
samples and early stopping on the validation split. The test split is read
only after all phases have selected their best validation checkpoint.

## Test the pipeline

```powershell
python -m unittest training.test_pipeline
```

See `THIRD_PARTY.md` for source, license, and attribution details.
