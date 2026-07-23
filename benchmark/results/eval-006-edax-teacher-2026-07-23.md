# EVAL-006 Edax teacher correction

## Decision

`model rejected, Edax teacher infrastructure accepted`。

現行探索の実葉へEdax 4.6のscoreを付与し、現行6-pattern表へ残差を加えるモデルを学習した。Edax level 9と11の教師値は小規模holdoutで高い一致を示し、反復2では現行モデルとの固定深さ100局を50.0%まで回復した。しかしEdax L8 100局は現行37.5%に対して38.5%にとどまり、対応差の95%区間は`[-10.5,+12.5] point`だった。目標を大会10秒条件のEdax L11以上とするため、この差ではモデルを採用しない。

OBF変換、Edax batch採点、教師深度比較、Edax scoreを使う残差学習、phase別補正縮小は再利用する。`data/evaluation-tables.bin`は変更しない。

## Teacher calibration

EVAL-005の実葉から各phase 192局面、合計768局面を取り、同一局面をEdax level 9と11で採点した。

| Metric | L9 vs L11 |
|---|---:|
| Score correlation | 0.995439 |
| MAE | 0.9414 discs |
| RMSE | 2.1124 discs |
| Exact score agreement | 63.0% |
| Within 2 discs | 89.3% |
| Sign agreement | 97.53% |

L11で完全読みになった394局面ではL9と100%一致した。phase 1のMAEは2.38 discsで他phaseより大きいが、全体としてL9は大量採点用教師として十分安定と判断した。

## Iteration 1

- Source leaves: 28,656
- Edax L9 nodes: 8,898,448,767
- Edax elapsed: 471.6 s
- Mean Edax depth: 8.566
- Exact samples: 18,369
- Training: 80 epochs、patience 10、output anchor 0.5

現行静的評価とEdax scoreの相関は0.898925、MAEは7.8965 discs、sign一致は86.88%だった。候補は未使用testで全phaseの残差MSEを削減したが、実戦結果へ変換できなかった。

| Match | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| Current, direct depth 6 | reference | 50.0% | 0.00 |
| Iteration 1 vs current, depth 6 | 45-3-52 | 46.5% | +0.79 |
| Current vs Edax L8 | 36-3-61 | 37.5% | -9.28 |
| Iteration 1 vs Edax L8 | 29-4-67 | 31.0% | -10.16 |

phase 0、1、2、3の補正だけを有効にした候補は、現行との深さ6でそれぞれ49.0%、50.0%、51.0%、49.0%だった。各phase単独はほぼ中立なのに全phase同時では46.5%となり、探索中に異なるphaseの値を連鎖利用する非線形な方策変化が確認された。

## Iteration 2

初回候補で同じ親局面を探索し、候補自身が訪れた28,655葉をEdax L9で再採点した。初回候補をbaseとして二つ目の補正を学習するDAgger型の反復で、学習分布と候補の探索分布のずれを減らした。

- Edax L9 nodes: 9,020,940,042
- Edax elapsed: 390.4 s
- Train / validation / test: 16,384 / 4,096 / 8,175
- Candidate SHA-256: `11cb09756e2e933d9aae4983b80c88c7f1230308d52f0cf0b5c7e344fb2cd070`

| Match | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| Iteration 2 vs current, depth 6 | 49-2-49 | 50.0% | +1.47 |
| Iteration 2 vs Edax L8 | 35-7-58 | 38.5% | -7.95 |

現行との対応opening差はscore `+1.0 point`、bootstrap 95%区間`[-10.5,+12.5]`、平均石差`+1.33`、区間`[-1.96,+4.66]`だった。初回の大幅劣化は解消したが、Edax改善は統計的に確認できない。

## Interpretation

- 独立した強教師を使っても、28k葉と現行6-pattern補正ではEdax scoreのMAE改善が小さく、教師情報の大半を表現できない
- 初回候補の探索分布外で学んだ補正は方策を崩した。候補自身の葉で再学習すると回復したため、探索分布ずれは実在する
- phase別単独補正の結果は、局面単体MSEや一つのphaseだけの改善を足し合わせても探索全体の棋力を予測できないことを示す
- L9とL11の教師差は候補と現行の実戦差より十分小さい。今回の主な制約は教師深度ではなく、モデル容量、目的関数、標本分布である
- 40局preflightでは初回候補42.5%だったが100局では31.0%へ落ちた。以後、40局は明白な破綻の停止判定だけに使い、採用判断は100局以上で行う

次は同じ補正を反復するのではなく、探索葉を増やした独立holdout、phase表現力、兄弟局面間の相対誤差を同時に扱えるモデル構造を先に検証する。大会10秒・Edax L11の正式評価は、L8で明確な改善を示した候補だけへ実施する。

## Verification

- Java learned model load: PASS
- Python unit tests before final integration: 24/24 PASS
- Illegal moves and incomplete games: 0
- Edax L8 conditions: 100 ms、1 thread、8-ply opening、50 pair、seed `20260721`、MPC off
- Direct conditions: fixed depth 6、1 thread、8-ply opening、50 pair、seed `20260806`、MPC off

## Reproducibility

- Branch: `codex/eval-006-edax-teacher`
- Edax binary SHA-256: `d85b7555879387cd20cee2dab28bcbbdb09631f245459e48b1b0b34b4558800c`
- Edax eval SHA-256: `f8b2299612d9fa4414157e70e932636e33111c2602d0c2fc382a7d90ef21b792`
- Current model SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- Iteration 1 TSV SHA-256: `294b2e269cc1a646cd10c142c8f6569841a9c3bfd009460141a7e437c64544fb`
- Iteration 1 model SHA-256: `663e08b65383d5004fd56113d1a2204c76809181f92158922d7e4aa4b62bcc97`
- Iteration 2 TSV SHA-256: `d3cd8b8b2073b5b313b061f2d1291aa4e48e57fc3351abbaac2c9f900db38ae1`
- Iteration 2 model SHA-256: `11cb09756e2e933d9aae4983b80c88c7f1230308d52f0cf0b5c7e344fb2cd070`

## Artifacts

- `training/model-eval-006-edax-teacher.json`
- `training/generate_edax_teacher.py`
- `training/evaluate_edax_teacher.py`
- `benchmark/results/eval-006-*-2026-07-23.txt`
- `benchmark/results/eval-006-edax-l9-iter2-a050-edax-l8-analysis-2026-07-23.json`
