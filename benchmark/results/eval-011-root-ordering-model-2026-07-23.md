# EVAL-011 root ordering model

## Decision

`rejected`。

EVAL-010反復2のEdax教師モデルを、現行評価器の代替ではなくルート着手の順序付けだけに使用した。固定深さのminimax値は現行評価器から計算し、候補評価値は非終盤のルート子局面のpriorityだけへ加えた。

32局面の固定深さ9で全best move・scoreが一致したが、総ノードは0.012%しか減らず、経過時間は0.390%増えた。時限制探索またはEdax対局へ進める効果量ではない。

## Result

| Metric | Baseline | Root ordering | Change |
|---|---:|---:|---:|
| Positions | 32 | 32 | - |
| Best move / score agreement | - | 32 / 32 | PASS |
| Total nodes | 5,693,752 | 5,693,052 | -0.012% |
| Total elapsed | 4,139.391 ms | 4,155.518 ms | +0.390% |
| Positions with fewer / equal / more nodes | - | 12 / 11 / 9 | - |

局面ごとのnode変化率は平均-0.030%、中央値0.000%、95% t区間`[-0.080%, +0.020%]`だった。実用上の改善はなく、符号も統計的に確定していない。

## Interpretation

反復深化では前回反復のbest moveをpriority 1,000,000で最初に探索している。候補モデルが変更できるのは主に残りのルート手だけであり、ルートPVSの探索量にはほとんど影響しなかった。

候補モデルの順位情報を活用するなら、枝刈りが頻発する上位内部nodeへ適用する必要がある。ただし評価呼び出し回数が増えるため、対象深さと費用を別実験で測る。

## Conditions

- Runtime evaluator: `data/evaluation-tables.bin`
- Runtime model SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- Ordering candidate: EVAL-010 iteration 2
- Ordering model SHA-256: `cbee474cbee0592e627ef631bb0535f39545b0d1c12e8147613cdcd8174ef805`
- Seed: `20260723`
- Positions: 32
- Search: depth 9, 1 thread, one measured repetition
- TT capacity: 262,144 entries
- Stability cutoff: enabled
- Multi-ProbCut: enabled

## Verification

- `javac --release 11 -encoding UTF-8 -Xlint:all -d .build *.java`: PASS
- Java test mains: 12 / 12 PASS
- `EvaluationMatchRunner` ordering-model CLI smoke: 2 / 2 games completed
- Fixed-depth best move and score: 32 / 32 identical
- Runtime model changed: no

## Artifacts

- Baseline: `benchmark/results/eval-011-root-ordering-baseline-d9-32-2026-07-23.csv`
- Candidate: `benchmark/results/eval-011-root-ordering-candidate-d9-32-2026-07-23.csv`
- Branch: `codex/eval-011-root-ordering-model`
- Parent: `751e459`
