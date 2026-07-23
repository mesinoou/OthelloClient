# EVAL-012 upper-node learned move ordering

## Decision

`rejected`。

EVAL-010反復2のEdax教師モデルを、現行評価器の代替ではなく上位内部nodeの着手順序付けに使用した。固定深さのnode数と固定時間の到達深さは改善したが、Edax L8への得点率と平均石差がともに悪化したため統合しない。

## Method

候補モデルは指定した残り深さ以上の非終盤nodeで各子局面を評価する。親手番から見た符号へ反転し、既存のmobility・flip priorityへ加算した。

- 葉とminimax値: 現行モデル
- 着手順序だけ: EVAL-010 iteration 2
- TT手、前反復best、corner優先: 維持
- 完全読み・WLD領域: learned ordering無効
- Multi-ProbCut: 現行評価器の対応条件を維持

LMRを無効にした正確探索では、内部nodeまで順序を変更してもbaselineとscoreが一致した。実運用ではLMRがmove index 4以降を削減するため、順序変更は削減対象も変更する。

## Fixed-depth threshold sweep

深さ9、64局面、1 thread、seed `20260724`。

| Minimum depth | Node change | Time change | Same best | Same score |
|---:|---:|---:|---:|---:|
| 4 | -12.08% | -2.23% | 55/64 | 48/64 |
| 5 | -10.20% | -3.32% | 55/64 | 48/64 |
| 6 | -4.06% | -1.68% | 59/64 | 53/64 |
| 7 | -5.21% | -11.56% | 62/64 | 60/64 |
| 8 | -3.53% | -1.52% | 64/64 | 62/64 |

別seed `20260725`、深さ10、32局面でもnode削減は再現した。

| Minimum depth | Node change | Time change | Same best | Same score |
|---:|---:|---:|---:|---:|
| 4 | -22.99% | -21.01% | 29/32 | 25/32 |
| 5 | -20.12% | -22.99% | 29/32 | 25/32 |
| 7 | -13.66% | -18.19% | 31/32 | 30/32 |

閾値4/5は速いがLMRの探索結果を広く変えた。閾値7は速度改善を残し、結果差を最小化したため実戦候補に選んだ。

## Direct match

現行モデル同士、片側だけlearned orderingを有効化した。20 opening pair、深さ8、1 thread、MPC off。

| Minimum depth | W-D-L | Score | Mean margin |
|---:|---:|---:|---:|
| 4 | 18-3-19 | 48.75% | -1.43 |
| 5 | 18-3-19 | 48.75% | -1.43 |
| 7 | 20-0-20 | 50.00% | +0.40 |

閾値7だけが棋力中立だった。

## Timed search

32局面を2反復、各500 ms、seed `20260727`。

| Threads | Baseline depth | Candidate depth | Delta |
|---:|---:|---:|---:|
| 1 | 9.98 | 10.20 | +0.22 |
| 4 | 10.28 | 10.44 | +0.16 |
| 8 | 10.48 | 10.67 | +0.19 |

候補評価の追加によりNPSは約5〜8%低下したが、順序改善によるnode削減が上回った。

## Edax L8 gate

事前ゲートと同じ50 opening pair、100局、100 ms、1 thread、MPC off、book offで閾値7を評価した。

| Model | W-D-L | Score | Mean margin | Average depth |
|---|---:|---:|---:|---:|
| Current | 36-3-61 | 37.50% | -9.28 | 8.69 |
| Upper-node ordering | 32-5-63 | 34.50% | -10.73 | 8.74 |

opening pair対応差はscore -3.0 point、bootstrap 95%区間`[-11.5,+5.5]`、平均石差-1.45、区間`[-4.03,+1.14]`だった。劣化は統計的に確定しないが、L11進級に必要な改善方向ではない。

## Interpretation

候補モデルは探索木を小さくする順位を与えたが、強い手を先に読む順位とは一致しなかった。特にLMRと組み合わせると、候補順位がmove index 4の境界をまたいで探索深さを変える。速度改善と棋力悪化が同時に起きた主因と考えられる。

次に順位情報を使う場合は、既存heuristicで決めたLMRの非削減groupと削減groupを維持し、各group内だけを再整列する必要がある。これなら候補モデルがLMRの探索深さを変更せず、alpha-betaのcutoff順序だけを改善できる。

## Verification

- Java compilation with `--release 11 -Xlint:all`: PASS
- Java test mains: 12 / 12 PASS
- LMR-off exact-score comparison: PASS
- Illegal moves / incomplete games: 0
- Runtime model changed: no

## Reproducibility

- Branch: `codex/eval-012-upper-node-ordering`
- Parent: `751e459`
- Runtime SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- Ordering SHA-256: `cbee474cbee0592e627ef631bb0535f39545b0d1c12e8147613cdcd8174ef805`
- Pair bootstrap samples: 200,000
- Pair bootstrap seed: `20260805`
- Detailed CSV, match logs, and paired analysis use the `eval-012-` prefix in this directory
