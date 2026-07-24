# EVAL-015 robust policy mixture

## Decision

`training infrastructure retained, candidate model rejected`。

EVAL-010の世代更新で生じた方策分布への過適合を抑えるため、現行、反復1、反復2の3方策が訪れた探索葉を混合した。全局面を標準モデルで再評価し、datasetごとの総重みを等しくしたうえで、validation Huber lossの最悪source比が最小になるcheckpointを選んだ。

量子化済み候補は3分布すべてのtest MSEを17.16〜18.35%削減した。現行との固定深さ6では55.5%だったが、深さ8では51.0%まで縮小し、Edax L8 100局は36.0%で現行37.5%を下回った。探索方策間の回帰安定性は得たが対局強度へ変換されず、候補を標準モデルへ統合しない。

## Training

- Teacher: Edax 4.6 level 9
- Base evaluator: standard `data/evaluation-tables.bin`
- Sources: current leaves, EVAL-010 iteration 1 leaves, iteration 2 leaves
- Train selection: source-balanced weighted Huber
- Checkpoint selection: worst validation source Huber ratio
- Output anchor: `0.1`
- Epoch limit: 640
- Patience: 60
- Device: CUDA
- Seed: `20260805`

各datasetの保存済み`static_score`は生成世代が異なる。PythonのJava-table評価器で標準モデルへrebaseし、元の生成モデルに対する再現確認では3 testセットとも不一致0件だった。

output anchor `0.1, 0.5, 1.0`を160 epochで比較した。最悪sourceのtest MSE削減は順に13.21%、8.36%、5.06%だったため、`0.1`だけを320、640 epochへ延長した。640を事前の最終上限とし、それ以上は探索しなかった。

| Phase | Trained epochs | Best epoch | Worst validation ratio |
|---|---:|---:|---:|
| 0 | 640 | 640 | 0.6940 |
| 1 | 640 | 639 | 0.8311 |
| 2 | 298 | 238 | 0.9048 |
| 3 | 307 | 247 | 0.9627 |

## Offline Results

| Search-policy distribution | Float MSE reduction | Quantized MSE reduction |
|---|---:|---:|
| Current leaves | 17.20% | 17.16% |
| Iteration 1 leaves | 18.40% | 18.35% |
| Iteration 2 leaves | 17.62% | 17.58% |

量子化損失は最大0.05 pointだった。位相別削減率も全12区分で正で、7.35〜37.62%だった。

Java読込、CRC、色交換、盤面対称性試験は通過した。評価器のtable構造とlookup数は標準モデルと同じであり、1,000,000回benchmarkは標準モデル500.9 ns/eval、候補458.8 ns/evalだった。この単発差はモデル内容による速度改善とは扱わない。

## Match Results

固定深さ対局は定石off、MPC off、1 thread、8-ply opening 50組、seed `20260810`で行った。

| Match | W-D-L | Score | Mean margin | Pair bootstrap 95% score |
|---|---:|---:|---:|---:|
| vs current, depth 6 | 54-3-43 | 55.5% | +2.53 | 47.5〜63.5% |
| vs current, depth 8 | 47-8-45 | 51.0% | +1.94 | 44.5〜57.5% |

Edax L8は100 ms、1 thread、MPC off、opening seed `20260721`で比較した。

| Model | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| EVAL-015 candidate | 35-2-63 | 36.0% | -9.77 |
| Current | 36-3-61 | 37.5% | -9.28 |
| EVAL-010 iteration 2 | 38-7-55 | 41.5% | -8.77 |

現行との対応差はscore -1.5 point、95%区間`[-14.0,+11.5]`、平均石差-0.49、区間`[-4.65,+3.74]`だった。統計的な優劣は確定しないが、point scoreと石差がともに悪化し、L11進級線の現行比+5 pointを満たさない。

## Interpretation

- source balancingとworst-source選択は、EVAL-010で見られた世代間MSE崩壊を防いだ
- 深さ6の改善が深さ8でほぼ消えたため、補正は収集葉の局所値へ適合しても、より深いminimaxで有効な順位を保っていない
- 独立局面の点ごとのMSEは、兄弟手の相対順位、誤差相関、探索window付近の判断を直接評価しない
- Edax葉値へ近づける補正を追加しても、探索が訪れる局面とprincipal variationが変わり、対局結果は単調に改善しない
- 追加epoch、追加DAgger世代、追加局所パターンを同じ目的関数へ投入する優先度は低い

次の評価関数実験では、実探索の兄弟手をgroupとして保存し、Edax best moveとの不一致時regretを測る。オフライン採用条件を葉MSEから、held-out root/upper-nodeのtop-1一致率、pairwise順位、Edax score regretへ移す。新モデルは標準評価からの変化量を制約し、固定深さ6と8の双方を通過してからEdax対局へ進める。

## Verification

- Python tests: 30/30 PASS
- Java compilation with `--release 11`: PASS
- Java regression test mains: 11/11 PASS
- `LearnedEvaluatorTest` with candidate: PASS
- Rebased Java score reproduction: mismatch 0 on all 3 source tests
- Illegal moves and incomplete games: 0
- Runtime source changes: none
- Standard model changes: none
- Standard model SHA-256 remains `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`

## Reproducibility

- Branch: `codex/eval-015-robust-policy-mixture`
- Parent: `751e459`
- Candidate SHA-256: `79bcc1e5263682759b33bf75da30a6d02577b3bc53c54c01c64e6f86c9ccce34`
- Current-leaf dataset metadata SHA-256: `b1fa0d407ed13d03ae327546e1e62b7559022ab015dc1a3868c336d1d7b9de6c`
- Iteration-1-leaf dataset metadata SHA-256: `2f46c2dce07780cc7492c0be8a4f33bba46afcd8a1a449757e0462743098ec8e`
- Iteration-2-leaf dataset metadata SHA-256: `e031d3d5184dae1b56c75517e3b390a091feb47fa5e1766f4be43f597f68b893`
