# EVAL-004 move-ranking evaluation

## Decision

`model rejected, ranking infrastructure accepted`。

深さ8/10の全合法手探索から兄弟局面順位教師を作り、pairwise lossと最善手listwise lossで既存6-patternモデルをファインチューニングした。Hybrid初期値のlistwise候補はtest順位指標を改善したが、Hybridとの固定深さ100局で深さ6が37.5%、深さ8が34.5%へ低下した。10 epochに制限した候補も深さ6で41.5%、opening-pair bootstrap 95%区間が`[34.0%,49.0%]`だったため、モデルを統合しない。

局面抽出、教師探索、静的モデル後付け採点、順位診断、CUDA順位学習、MPC比較スイッチは再利用可能な実験基盤として残す。`data/evaluation-tables.bin`は変更していない。

## Implementation

- `EvaluationMatchRunner`へ同一条件のmodel-vs-model対戦とMPC明示スイッチを追加
- v4 datasetからtrain/validation/test・phase別に2合法手以上の局面を抽出
- LMR、MPC、WLDを無効化した深さ8/10探索で全合法手を採点
- top-1、pairwise accuracy、Spearman順位相関、deep-score regretを集計
- 既存float modelを初期値にpairwise logistic lossとbest-move listwise lossでCUDA学習
- 元モデルの出力・パラメータをanchorとして破壊的更新を抑制
- 候補モデルを教師探索なしで既存TSVへ後付け採点

実装コミットは`bf08c61`、`f4f6fd1`、`7ae8447`、`22aa3dc`、`a83fd83`である。

## Direct model comparison

評価関数だけを比較するため、両者のMPCを無効化し、50個の8-ply openingを先後交換した100局を固定深さで実行した。1 thread、opening seedは`20260803`である。scoreは左側モデルの値を示す。

| Left model | Right model | Depth 4 | Depth 6 | Depth 8 |
|---|---|---:|---:|---:|
| Current | MSE | 40.0% | 44.5% | 45.5% |
| Current | Hybrid | 40.0% | 40.0% | 46.0% |
| MSE | Hybrid | 49.0% | 44.0% | 44.5% |

MSEとHybridはEdax L7ではともに47.0%で現行55.5%を下回ったが、固定深さの直接対戦では現行を上回った。評価関数単体の劣化ではなく、探索深度・時間制探索との相互作用があることを確認した。

## Ranking teacher

- Source dataset: `.training/datasets/combined-evaluation-v4`
- Positions: train 1,024、validation 256、test 512、合計1,792
- Legal moves: 15,225、平均8.496手/局面
- Teacher: current model、parent depth 8/10、1 thread
- Selective search: LMR off、MPC off、WLD off、exact solver on
- Generation time: 1,607.5秒
- Depth 8/10 top-1 stability: 全体81.47%、test 81.84%
- Deep exact rate: 全合法手の14.56%、test phase 3は100%

testにおける深さ8教師自体の深さ10 top-1一致率は81.84%、pairwise accuracyは92.28%、順位相関は0.906だった。深さ10を最終教師、深さ8とのtop-1一致を高信頼局面条件として学習に使った。

## Baseline ranking

未使用test 512局面で、各合法手を静的評価した結果である。regretは深さ10最善手scoreからモデル選択手scoreを引いた値で、低い方が良い。

| Model | Top-1 | Pairwise | Rank corr. | Mean regret | Median regret |
|---|---:|---:|---:|---:|---:|
| Current | 45.51% | 73.71% | 0.570 | 3,233 | 5.5 |
| MSE | 46.09% | 73.92% | 0.573 | 3,421 | 4.0 |
| Hybrid | 47.27% | 74.52% | 0.585 | 2,473 | 4.0 |

Hybridが総合的に最良で、phase 0、2、3の順位指標に強かった。MSEはphase 1 top-1が47.66%でHybridの40.63%を上回った。

## Ranking training

安定局面だけを使い、MSE/Hybrid初期値、pairwiseのみ、pairwise+listwise、100/10 epochを比較した。

| Candidate | Test top-1 | Pairwise | Rank corr. | Mean regret |
|---|---:|---:|---:|---:|
| MSE base | 46.09% | 73.92% | 0.573 | 3,421 |
| MSE pairwise e100 | 43.55% | 75.33% | 0.607 | 3,609 |
| Hybrid base | 47.27% | 74.52% | 0.585 | 2,473 |
| Hybrid pairwise e100 | 47.07% | 76.38% | 0.627 | 2,245 |
| Hybrid listwise e100 | 47.85% | 75.89% | 0.612 | 2,053 |
| Hybrid listwise e10 | 47.85% | 75.03% | 0.594 | 2,262 |

listwise e100はvalidationでもHybrid比でtop-1を46.09%から46.88%、mean regretを5,781から3,809へ改善したため対戦候補とした。e10は更新量確認用で、test局面におけるHybridからの評価差RMSはphase 0から3で143、208、323、487だった。静的最善手が変わった局面は5.5%から8.6%に留まった。

## Fixed-depth gate

Hybrid baseを右側、順位候補を左側にして、直接比較と同じ50 opening・100局を実行した。

| Candidate | Depth | W-D-L | Score | Mean margin | Pair bootstrap score 95% CI |
|---|---:|---:|---:|---:|---:|
| Listwise e100 | 4 | 48-3-49 | 49.5% | -1.78 | - |
| Listwise e100 | 6 | 36-3-61 | 37.5% | -6.36 | [29.5%,45.5%] |
| Listwise e100 | 8 | 33-3-64 | 34.5% | -7.71 | [26.5%,42.5%] |
| Listwise e10 | 6 | 39-5-56 | 41.5% | -2.18 | [34.0%,49.0%] |

e100の深さ6・8ではscoreと平均石差の95%区間がともに0差を含まない。e10もscore上限が49.0%で、更新量を抑えても非劣性を示せなかった。Edax戦、10秒4 thread戦、候補専用MPC校正は停止条件に従って実施していない。

## MPC isolation

現行モデルのEdax L7 100局を同じseed `20260721`でMPCだけ無効にした。

| Current model | W-D-L | Score | Mean margin | Average depth |
|---|---:|---:|---:|---:|
| MPC on (EVAL-003) | 53-5-42 | 55.5% | -2.43 | 8.95 |
| MPC off | 52-6-42 | 55.0% | -2.04 | 8.95 |

差は0.5 pointであり、現行とEVAL-003候補の8.5 point差をMPCだけでは説明できない。時間制探索での現行評価関数の性質が主因である。

## Interpretation

- 兄弟局面の静的順位改善は、同じ関数を全葉へ再帰的に使うminimaxの改善を保証しない
- 教師はcurrent評価を用いた深さ10探索なので、current固有のhorizon biasを候補へ蒸留している
- 根で5〜9%の最善手しか変わらない小更新でも、探索木の全葉で反復されると手順選択差が増幅する
- Pairwise lossは広い順位相関を改善したが、実着手のtop-1と探索整合性を十分に拘束しなかった
- Listwise lossでtop-1とregretを改善しても、固定深さゲームが悪化したためoffline順位だけを採用条件にできない

次の評価関数実験では、静的な直後局面だけでなく実探索の葉分布を採取し、深さを変えたときの評価整合性を目的へ加える。または順位モデルを葉評価へ置換せず、root move ordering専用の補助信号として隔離して試す。

## Verification and throughput

- Python unit tests: 19/19 PASS
- `javac --release 11 -encoding UTF-8 -Xlint:all`: PASS
- Java regression tests: 12/12 PASS
- `LearnedEvaluatorTest` for ranking candidates: PASS
- Illegal moves, incomplete fixed-depth games: 0
- 5,000,000評価の再測定: Hybrid 431.3 ns/eval、listwise e10 429.3 ns/eval

runtime表構造は変わらず、測定上の評価速度退行はない。

## Reproducibility

- Branch: `codex/eval-004-ranking`
- Position seed: `20260804`
- Opening seed: `20260803`
- Current SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- MSE SHA-256: `1f435e449f899e52d964c1335722ec352878488b3bd333c3ca1f910dfdb76a91`
- Hybrid SHA-256: `11857c5b8d0f8433c2c6e6e696ada757ba6beb6452369ad18e130275b9d18187`
- Listwise e100 SHA-256: `114987adbd8d6ac75d9f5bea417385916cd47bbb5b4d6b3707f30093067feaba`
- Listwise e10 SHA-256: `6a872e248b80919b72ea43b50c83fd38f9591177d0a0e48aa17c9bc592ae5362`
- Positions SHA-256: `6b66991e9e4cb5329b9c8d521e9085bd2ece622e72e66c462ce8748b03378d74`
- Teacher scores SHA-256: `e357fef244296e5a0ea13c31fc1dd03f8bce614274528818d14a6ac519d1a24f`
- GPU: NVIDIA GeForce RTX 3070 Ti
- PyTorch: 2.11.0+cu128、CUDA runtime 12.8

## Artifacts

- `benchmark/results/eval-004-ranking-metrics-2026-07-23.json`
- `benchmark/results/eval-004-ranking-e10-metrics-2026-07-23.json`
- `benchmark/results/eval-004-direct-d{4,6,8}-*-2026-07-23.txt`
- `benchmark/results/eval-004-listwise-*-2026-07-23.txt`
- `benchmark/results/eval-004-current-mpc-off-edax-l7-100-2026-07-23.txt`
- `training/model-eval-004-ranking-hybrid-listwise.json`
- `training/model-eval-004-ranking-hybrid-listwise-e10.json`
