# EVAL-003 hybrid objective evaluation

## Decision

`model rejected, training feature accepted`。

WLDと石差Huberを組み合わせたHybrid lossは、全phaseでMSE対照よりtest WLD log-lossを改善し、固定深さ40局でもMSE対照を上回った。しかしEdax L7 100局はMSE、Hybridとも47.0%で、現行55.5%から8.5 point低下した。事前に定めた5 pointの非劣性幅を超えたため、Hybridモデルを`data/evaluation-tables.bin`へ統合しない。10秒・4 thread試験とMPC再校正も停止条件に従い実施しない。

loss実装、CUDA/CPU両backendの目的値計測、Java量子化尺度の修正は今後の評価関数実験に再利用できるためコードとして採用する。

## Implementation

- `mse`、`huber`、`wld`、`hybrid`の4 lossを追加
- WLD教師は重複局面の勝・分・敗数から`(win + 0.5 * draw) / samples`を作り、手番側視点へ変換
- Hybridは予測石差`p`に対するHuber lossと、logit `4p`に対するbinary cross entropyの和
- validation objectiveで早期停止し、MSE、MAE、WLD log-loss、Brier scoreをCPU/CUDAで同じ定義に統一
- Javaがscore divisorを使用するモデルでもPython量子化後指標へ同じ除数を適用
- WLD教師の手番反転、lossの色反転対称性、Java除数を回帰テストへ追加

65,536局面/phaseの事前校正では純WLD、MSE、Hybridを比較した。純WLDはWLD指標を改善したが石差MSEを大きく崩したため除外し、フルデータ学習はMSE対照とHybridだけを実行した。

## Models and environment

- Branch: `codex/eval-003-objective`
- Loss implementation: `b4fb97e`
- Quantized metric correction: `9249d36`
- Dataset: `.training/datasets/combined-evaluation-v4`
- Current SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- MSE SHA-256: `1f435e449f899e52d964c1335722ec352878488b3bd333c3ca1f910dfdb76a91`
- Hybrid SHA-256: `11857c5b8d0f8433c2c6e6e696ada757ba6beb6452369ad18e130275b9d18187`
- GPU: NVIDIA GeForce RTX 3070 Ti
- PyTorch: 2.11.0+cu128、CUDA runtime 12.8、AMP on
- Training: maximum 80 epochs、patience 10、batch 4096、seed 20260720
- MSE elapsed: 2954.7秒、Hybrid elapsed: 1699.8秒

新モデルは現行モデルとSHAが異なるため、現行専用に校正されたMulti-ProbCutは安全機構により無効となる。これは候補をそのまま統合した場合の構成であり、評価関数単体gateを通過した候補だけMPCを再校正する方針である。

## Offline metrics

量子化後Java runtime相当値をtest splitで比較した。

| Phase | MSE model MSE | Hybrid MSE | MSE model WLD log-loss | Hybrid WLD log-loss |
|---:|---:|---:|---:|---:|
| 0 | 0.120428 | 0.121655 | 0.623623 | 0.599724 |
| 1 | 0.080910 | 0.084999 | 0.587282 | 0.536726 |
| 2 | 0.051594 | 0.056483 | 0.538835 | 0.453135 |
| 3 | 0.029960 | 0.038407 | 0.503993 | 0.387930 |

HybridはWLD log-lossをphase 0から3でそれぞれ約3.8%、8.6%、15.9%、23.0%改善した。一方MSEは約1.0%、5.1%、9.5%、28.2%増えた。終盤ほど勝敗境界を強く優先するモデルになった。

## Verification and throughput

- Python unit tests: 17/17 PASS
- `javac --release 11 -encoding UTF-8 -Xlint:all`: PASS
- Java regression tests: 12/12 PASS
- `LearnedEvaluatorTest` for MSE and Hybrid: PASS
- Illegal moves, Edax GTP errors, incomplete games: 0

`EvaluationBenchmark <model> 3000000`:

| Model | ns/eval | eval/s |
|---|---:|---:|
| Current | 427.0 | 2,341,925 |
| MSE | 414.0 | 2,415,367 |
| Hybrid | 412.8 | 2,422,682 |

追加lossは学習時だけ使用し、runtime表構造は同じである。測定上も速度退行はない。

## Fixed-depth evaluator check

- Opponent: handcrafted evaluator
- 20 unique 8-ply openings、colors exchanged、40 games
- Maximum depth 4、1 thread、opening book off
- Opening seed `20260721`

| Model | W-D-L | Score | Average margin | Average depth | nodes/s |
|---|---:|---:|---:|---:|---:|
| Current | 37-1-2 | 93.75% | +29.90 | 3.86 | 1,758,862 |
| MSE | 35-0-5 | 87.50% | +23.95 | 3.85 | 1,772,206 |
| Hybrid | 36-0-4 | 90.00% | +29.60 | 3.84 | 1,782,400 |

HybridはMSE比+2.5 point、平均石差+5.65であり、目的関数変更の効果を確認できた。現行比はscore -3.75 point、平均石差-0.30で、5 point非劣性gateを通過した。

## Edax L7 preflight

- 10 unique 8-ply openings、colors exchanged、20 games
- 100 ms/move、maximum depth 64、1 thread
- Edax 4.6 level 7、opening book and pondering off

| Model | W-D-L | Score | Average margin | Average depth |
|---|---:|---:|---:|---:|
| Current | 9-0-11 | 45.00% | -8.85 | 9.01 |
| MSE | 10-0-10 | 50.00% | -6.80 | 8.80 |
| Hybrid | 10-1-9 | 52.50% | -5.70 | 8.76 |

Hybridがscore、平均石差とも最良だったため100局へ進めた。

## Edax L7 strength gate

- 50 unique 8-ply openings、colors exchanged、100 games
- 100 ms/move、maximum depth 64、1 thread
- Edax 4.6 level 7、opening book and pondering off
- Opening seed `20260721`

| Model | W-D-L | Score | Average margin | Average depth | nodes/s |
|---|---:|---:|---:|---:|---:|
| Current | 53-5-42 | 55.50% | -2.43 | 8.95 | 1,625,209 |
| MSE | 43-8-49 | 47.00% | -4.82 | 8.89 | 1,622,879 |
| Hybrid | 45-4-51 | 47.00% | -5.00 | 8.84 | 1,633,937 |
| Hybrid change from current | - | -8.50 point | -2.57 | -0.11 | +0.54% |

Hybridと現行の対応比較:

| Unit | Metric | Improved | Tied | Worse |
|---|---|---:|---:|---:|
| 100 games | Result score | 18 | 56 | 26 |
| 100 games | Disc margin | 42 | 7 | 51 |
| 50 opening pairs | Result score | 11 | 21 | 18 |
| 50 opening pairs | Mean disc margin | 18 | 1 | 31 |

MSEと現行の対応比較ではgame scoreが14/60/26、opening-pair scoreが8/22/20、pair平均石差が17/2/31だった。HybridはMSEよりpair scoreが12/26/12、pair平均石差が21/4/25で、100局では明確な改善を残せなかった。

50 opening pairをclusterとして200,000回bootstrapしたHybrid対現行の95%区間は、score差が`[-21.0,+4.0] point`、平均石差差が`[-7.45,+2.46]`だった。MSE対現行はscore差`[-18.0,+1.0] point`、平均石差差`[-5.62,+0.90]`だった。区間は0を含むため100局だけで劣等性が確定したとは言えないが、採用に必要な非劣性も示せていない。

20局preflightは最初の10 openingだけを使うため、今回のように100局結果と方向が逆転し得る。preflightは明確な失敗を早く止める用途に限定し、採用判断には使わない。

## Interpretation

- WLD log-lossの改善だけでは探索中の兄弟局面順位改善を保証しない
- binary WLDは勝敗確率の絶対校正を改善しても、同じ勝敗側にある候補手の順位情報を弱める
- phase 2と3で石差MSEを大きく犠牲にしたため、alpha-beta境界付近の細かな順序を崩した可能性がある
- Hybridの固定深さ結果はMSEより良かったため、WLD目的自体に効果はある。ただし現構造・重みでは現行を置き換えるほどではない
- 候補ではMPCが無効だが、MSEとHybridは同条件であり、両者が100局同率だったことは目的関数だけでは不足することを示す

次の評価関数実験ではこのloss基盤を残し、同じモデルで重みを細かく動かすより、兄弟局面のpairwise ranking教師、理論値局面のphase別被覆、またはN-tuple表を直接学習する構造を優先する。

## Artifacts

- `training/model-eval-003-mse-filled-cuda-e80.json`
- `training/model-eval-003-hybrid-filled-cuda-e80.json`
- `eval-003-{current,mse,hybrid}-evaluation-benchmark-2026-07-22.txt`
- `eval-003-{current,mse,hybrid}-handcrafted-depth4-2026-07-22.txt`
- `eval-003-{current,mse,hybrid}-edax-l7-preflight-2026-07-22.txt`
- `eval-003-{current,mse,hybrid}-edax-l7-100-2026-07-22.txt`
