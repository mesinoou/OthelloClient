# EVAL-005 search-leaf correction

## Decision

`model rejected, search-leaf training infrastructure accepted`。

現行探索が実際に評価した葉局面へ追加4手探索の値を教師として与え、現行runtime tableへ加算する小さな補正モデルを学習した。未使用testでは追加探索値とのMSEを全phaseで削減し、現行との固定深さ100局でも深さ4、6、8のすべてで50%を上回った。しかし目標相手に近いEdax L8 100局では現行37.5%に対して全量補正31.5%、50%補正34.5%であり、改善を再現できなかった。`data/evaluation-tables.bin`は変更しない。

実葉の決定的標本化、追加探索教師、float重みがないruntime tableへの補正加算、補正縮小、opening-pair bootstrapは次の評価関数実験で再利用する。

## Hypothesis

EVAL-004では根の兄弟局面順位を改善してもminimax内で評価差が増幅し、固定深さ対戦が悪化した。EVAL-005は学習分布を実探索の葉へ移し、静的評価`V(s)`を同じ評価関数による追加4手探索`T4[V](s)`へ近づければ、探索深度を変えたときの評価整合性が上がるという仮説を検証した。

## Data generation

- Source parents: EVAL-004と同じtrain 1,024、validation 256、test 512
- Source search: depth 8、1 thread、LMR on、MPC off、WLD off
- Sampling: 各親から盤面hash下位16葉を決定的に抽出
- Teacher: current model、additional depth 4、LMR/MPC/WLD off
- Output: 28,656 unique leaves、65,865,300 source nodes、12,541,511 teacher nodes
- Generation time: 62.0秒

| Split | Leaves | Source parents | Evaluation occurrences | Exact teachers |
|---|---:|---:|---:|---:|
| train | 16,384 | 1,024 | 18,918 | 3,195 |
| validation | 4,096 | 256 | 4,653 | 775 |
| test | 8,176 | 511 | 9,378 | 1,574 |

探索葉は手番側をblackとして保存する。終端scoreは`WIN_SCORE`を除去して最終石差へ戻し、通常評価と同じ`score_scale=6400`へ変換する。分割をまたぐ同一局面は後の分割から除外した。

## Model

現行バイナリに対応する`model-float.npz`がこのPCにないため、現行表を再学習しない。6-patternネットワークの出力層を0で初期化し、`deep_score - current_static_score`だけをHuber lossで学習した。色交換時の符号反転は既存の反対称構造で保証する。

- Epochs 80、patience 10
- Adam、learning rate `2e-4`
- Huber delta `0.10`
- Output anchor `0.10`、L2 `1e-6`
- Occurrence weight `min(sqrt(count), 8)`
- Residual clip `[-1, 1]`
- CUDA、seed `20260805`

補正を量子化した後に現行16表へ加算する。候補の形式、CRC32、int16範囲、最大評価値境界は通常の`LearnedEvaluator`で検証した。補正量25%と50%の候補は学習済み表を丸めて縮小した。

## Offline result

| Phase | Test leaves | Baseline MSE | Corrected MSE | Reduction | Baseline MAE | Corrected MAE |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 1,138 | 0.018373 | 0.016426 | 10.60% | 0.098212 | 0.091881 |
| 1 | 1,442 | 0.016423 | 0.015437 | 6.00% | 0.097837 | 0.094431 |
| 2 | 2,135 | 0.016996 | 0.015739 | 7.39% | 0.099774 | 0.095921 |
| 3 | 3,461 | 0.021049 | 0.020351 | 3.32% | 0.113567 | 0.111929 |

追加探索値への近似は全phaseで改善した。補正RMSはphase 0から3で0.0292、0.0270、0.0261、0.0177であり、Java scoreでは約187、173、167、113に相当する。

## Fixed-depth gate

50個の8-ply openingを先後交換した100局、1 thread、MPC off、seed `20260805`で現行モデルと直接比較した。

| Candidate | Depth | W-D-L | Score | Mean margin |
|---|---:|---:|---:|---:|
| Full correction | 4 | 53-1-46 | 53.5% | +1.92 |
| Full correction | 6 | 50-5-45 | 52.5% | +0.82 |
| Full correction | 8 | 53-2-45 | 54.0% | +0.20 |
| 25% correction | 6 | 50-4-46 | 52.0% | +1.69 |
| 50% correction | 6 | 50-6-44 | 53.0% | +1.50 |

Full correction depth 8のopening-pair bootstrap 95%区間はscore`[46.0%,62.0%]`、平均石差`[-2.69,+3.13]`である。点推定は全深度で改善方向だが、100局では優位を統計的に確定できない。

## Edax gate

100ms、1 thread、8-ply opening、50 pair、seed `20260721`で比較した。候補hashは既存MPC校正の対象外なので、現行もMPC offで揃えた。

| Model | Edax level | W-D-L | Score | Mean margin | Average depth |
|---|---:|---:|---:|---:|---:|
| Current, MPC off | 7 | 52-6-42 | 55.0% | -2.04 | 8.95 |
| Full correction | 7 | 51-3-46 | 52.5% | -3.29 | 8.64 |
| Current, MPC off | 8 | 36-3-61 | 37.5% | -9.28 | 8.69 |
| Full correction | 8 | 29-5-66 | 31.5% | -10.37 | 8.67 |
| 50% correction | 8 | 30-9-61 | 34.5% | -10.07 | 8.66 |

50%補正と現行の対応差はscore -3.0 point、bootstrap 95%区間`[-12.0,+5.5] point`、平均石差 -0.79、区間`[-3.55,+1.89]`だった。劣化も統計的には確定しないが、Edax L11到達を目標とする採用条件に必要な改善証拠がない。

## Interpretation

- 実探索葉へ学習分布を合わせることで、EVAL-004で起きた固定深さの大幅な崩壊は解消した
- 自分自身の深さ4探索を教師にする自己蒸留は、現行評価のhorizon biasを超える情報を追加しない
- `V(s)`を`T4[V](s)`へ近づけると現行同士の探索整合性は上がるが、より強いEdaxの方策に対する着手品質は保証されない
- 補正量を半減してもEdax L8の改善は再現せず、単純な過補正だけが原因ではない
- 次は同じ実葉へ、現行より強い独立教師を与える必要がある

次の評価関数実験は、Edaxまたは十分深い独立探索のscoreを実葉へ付け、現行自己教師と強教師の一致・不一致を分離する。強教師を直接全葉へ使う前に、小規模holdoutで深度安定性と現行探索への適合を確認する。

## Verification

- Java compile with `--release 11 -Xlint:all`: PASS
- Java learned-model load: PASS
- Python unit tests: 22/22 PASS
- Candidate model size: 1,405,604 bytes
- Candidate score divisor: 1
- Candidate maximum bound: 75,886
- Evaluator speed: current 522.3 ns/eval、candidate 513.3 ns/eval
- Illegal moves and incomplete fixed-depth games: 0

## Reproducibility

- Branch: `codex/eval-005-search-leaf`
- Parent position seed: `20260804`
- Training and bootstrap seed: `20260805`
- Current SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- Search-leaf TSV SHA-256: `7805d6cf7e18dd50f03e75bb225d579138c63efa6dd43b56078401701831cd4f`
- Full candidate SHA-256: `b3c1d7f94619c417931f4c7aac53f8b0bce260c6c5712e3803ea2ba270313cea`
- 50% candidate SHA-256: `6af7978baff78370e0efa796aa12d9aa0f9ca9430a4c3ae69b7b5c1eb890a498`

## Artifacts

- `training/model-eval-005-search-correction-e80.json`
- `benchmark/results/eval-005-search-correction-*-2026-07-23.txt`
- `benchmark/results/eval-005-search-correction-d8-analysis-2026-07-23.json`
- `benchmark/results/eval-005-search-correction-s50-edax-l8-analysis-2026-07-23.json`
