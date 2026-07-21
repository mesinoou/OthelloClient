# SEARCH-006 late move reduction

## Decision

`accepted`。大会用4スレッドで固定深さノード数と500 ms到達深さが大きく改善し、同一環境で取り直したEdax L7 100局でも基準版からの棋力低下が観測されなかったため統合する。

## Final change

- Initial implementation: `7bda6f8`
- Null-window restriction: `ac3d459`
- Parallel stabilization: `c6b983f`
- Final root-probe alignment: `a510e9f`
- Base: `benchmark/parallel-v2-20260721` (`e6354ec`)
- 深さ5以上、手順5番目以降、空き19以上の非PV手を1 ply削減
- 隅と相手をパスさせる手は削減しない
- reduced searchがalphaを超えた場合は同じnull windowで全深度再探索
- 全深度再探索後もalphaを超える場合は既存PVSのfull-window再探索を実施
- 空き18以下の終盤完全読みではLMRを無効化
- LMR探索回数と再探索回数を`SearchResult`とベンチマークCSVへ記録

## Determinism fixes

最初の実装はPVノードにもLMRを適用し、別seed深さ8で逐次版と4スレッド版の最善手・評価値不一致を1件検出した。非PVノードだけに制限した後も、深さ10で1点差の不一致を1件検出したため、次を追加した。

- full-depth未確認のreduced手を含むfail-low結果は、深いUPPER boundとして置換表へ保存しない
- LMR候補ノードでは共有TTのbest moveを手順順位へ使わず、盤面固有の可動性順を維持
- 並列ルートの全ワーカーは最初のルート手の評価値を固定probe alphaとして使用
- 逐次ルートも同じ固定probe alphaを使い、fail-high手を全窓で評価

最終版では標準、別seed、深さ10の全結果で1・2・4・8スレッドの最善手と評価値が一致した。

## Standard suite

| Threads | Baseline nodes | LMR nodes | Node change | Retry rate | Baseline 500 ms depth | LMR 500 ms depth |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 292,268 | 238,725 | -18.32% | 2.24% | 9.31 | 9.88 |
| 2 | 292,268 | 238,725 | -18.32% | 2.24% | 9.31 | 9.88 |
| 4 | 307,016 | 239,360 | -22.04% | 2.23% | 9.69 | 10.31 |
| 8 | 318,453 | 239,635 | -24.75% | 2.22% | 9.94 | 10.50 |

## Validation suite

標準とは異なるseed `20260722`から32局面を生成し、固定深さ8、2反復で比較した。

| Threads | Baseline nodes | LMR nodes | Node change | Retry rate |
|---:|---:|---:|---:|---:|
| 1 | 82,186 | 71,934 | -12.47% | 3.24% |
| 2 | 82,186 | 71,934 | -12.47% | 3.24% |
| 4 | 82,265 | 72,186 | -12.25% | 3.26% |
| 8 | 82,604 | 72,255 | -12.53% | 3.25% |

## Depth 10 suite

| Threads | Baseline nodes | LMR nodes | Node change | Retry rate |
|---:|---:|---:|---:|---:|
| 1 | 772,888 | 485,538 | -37.18% | 1.95% |
| 2 | 772,888 | 485,538 | -37.18% | 1.95% |
| 4 | 835,878 | 486,949 | -41.74% | 1.95% |
| 8 | 816,114 | 488,000 | -40.20% | 1.95% |

選択的探索のため基準版との評価値一致は要求しない。標準8局面では基準版との最善手・評価値変更0件、別seed32局面では最善手2件・評価値6件、深さ10の8局面では最善手・評価値各1件が変化した。

## Edax L7

100 ms/手、1スレッド、50個の8手オープニングを先後交換し、Edax 4.6 level 7と100局対戦した。

| Engine | W-D-L | Score rate | Average margin | Average depth |
|---|---:|---:|---:|---:|
| LMR candidate | 44-4-52 | 46.0% | -0.78 | 8.36 |
| Same-run baseline | 44-1-55 | 44.5% | -2.66 | 8.28 |
| Historical baseline | 50-1-49 | 50.5% | -0.89 | 8.48 |

同一環境の基準版に対して候補はスコア率+1.5ポイント、平均石差+1.88だった。100局を直接比較すると、候補の石差が改善39局、同値23局、悪化38局だった。先後2局をまとめた50オープニングでは改善27組、同値5組、悪化18組だった。100局では小差を断定できないが、少なくとも速度向上と引き換えの棋力低下は観測されなかった。

## Tournament-budget smoke test

大会条件の10,000 ms/手、4スレッドで同一オープニングの先後2局をEdax L7と対戦した。

- Result: 2-0
- Margins: +4, +30
- Average depth: 12.58
- Exact endgame moves: 20
- Illegal moves or runtime errors: 0

2局だけなので棋力推定には用いず、時間管理、4スレッド探索、終盤完全読みへの移行確認として扱う。

## Validation

- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass
- Standard fixed-depth parallel mismatches: 0 of 64
- Validation fixed-depth parallel mismatches: 0 of 256
- Depth 10 fixed-depth parallel mismatches: 0 of 32
- Illegal or missing timed moves: 0 of 64
- Edax games completed: 202 of 202
- Illegal moves or GTP errors: 0

## Artifacts

- Standard CSV: `search-006-lmr-standard-2026-07-21.csv`
- Validation CSV: `search-006-lmr-validation-2026-07-21.csv`
- Depth 10 CSV: `search-006-lmr-depth10-2026-07-21.csv`
- LMR Edax L7 100-game log: `search-006-lmr-edax-l7-100-2026-07-21.txt`
- Same-run baseline Edax L7 log: `search-006-baseline-edax-l7-100-2026-07-21.txt`
- Tournament-budget log: `search-006-lmr-edax-l7-tournament-2-2026-07-21.txt`
