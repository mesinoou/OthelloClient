# EVAL-009 phase granularity

## Decision

`rejected before runtime format change`。

局面段階を4から8へ増やせば、同じ盤面パターンの時期依存性を細かく表現できるという仮説を検証した。Edax L9実葉、同じ6-pattern補正器、同じseedと正則化で4段階と8段階を160 epochまで学習した結果、8段階の追加改善はtest MSE削減で0.142 point、MAEで0.44%にとどまった。

モデル容量とファイルサイズを約2倍にし、runtime形式、loader、exporter、学習データのphase定義を変更する根拠として不足する。Java実装と対局試験へ進めず、現行4段階を維持する。

## Setup

- Teacher: Edax 4.6 level 9
- Dataset: train 16,384、validation 4,096、test 8,176
- Model: current 6-pattern residual correction
- Epochs: 160
- Patience: 15
- Output anchor: 0.5
- Device: CUDA
- Seed: `20260805`

8段階境界は`14,25,30,35,40,45,50,56`とし、test件数は`463,675,601,841,1060,1075,1887,1574`だった。全段階に独立testがあり、空区間はない。

## Fair comparison

最初の80 epoch比較では4段階MSE削減6.29%、8段階7.33%だった。ただし両モデルの早期phaseがepoch上限で最良値を更新中だったため、同じ160 epoch・patience 15で取り直した。

| Model | Test corrected MSE | MSE reduction | Test corrected MAE |
|---|---:|---:|---:|
| 4 phases, 80 epochs | 0.025032637 | 6.291% | 0.121168752 |
| 8 phases, 80 epochs | 0.024763163 | 7.330% | 0.120097166 |
| 4 phases, 160 epochs | 0.024453340 | 8.459% | 0.119638441 |
| 8 phases, 160 epochs | 0.024423202 | 8.602% | 0.119114323 |

収束条件を揃えると8段階のMSEは4段階より0.000030だけ低く、MSE削減率の差は0.142 pointだった。80 epochで見えた差の大半はphase数ではなく学習不足である。

## Interpretation

- 4段階内の時期差は存在するが、現在の教師数では分割による標本減少とほぼ相殺される
- 既存6-pattern表の主制約はphase境界の粗さではなく、教師分布、パターン容量、目的関数にある
- epoch上限に達した履歴を異なるモデル容量の証拠として比較してはいけない
- phase増加を再検討するなら、各新phaseに現在の数倍の独立教師を用意し、兄弟手順位も改善することを先に示す

今回追加した`--phase-starts`と`--analysis-only`は、任意の2〜16段階をJava形式変更なしで比較するための計測基盤として残す。custom phaseをJava候補へ誤出力しようとすると停止する。

## Verification

- Python tests: 28/28 PASS
- 8 phases all non-empty in train、validation、test
- Java runtime changes: none
- Runtime model changes: none

## Reproducibility

- Branch: `codex/eval-009-phase-granularity`
- Parent: `63582a8`
- Dataset metadata SHA-256: `b9838d0df2ef7ae796f0f86a1b32180437cb9d29b8732b345b2b52b61bbfcb6c`
- 4-phase float SHA-256: `8a3095b0d93f5b1de93ce9ab0cad961fbd17c8ff44ae99ef8cc4e8f37e4db5cd`
- 8-phase float SHA-256: `515ccd0a591b16c0518ae8cf6c9ff60e503176375986ae53f16a44e85656f4c6`
