# SEARCH-003 history ordering

## Decision

`rejected`。ノード削減は二つの局面集合で再現したが、大会用4スレッドでの効果が小さく、標準500 ms探索の到達深さがわずかに低下したため統合しない。

## Change

- Experimental commit: `54eb7f1`
- Base: `benchmark/parallel-v2-20260721` (`e6354ec`)
- SearchContextごとに64マスのhistory表を追加
- beta cutoff時に`depth * depth`を加算し、飽和前に全履歴を半減
- 既存の可動性優先度を壊さないよう、履歴値を`1 / 256`に縮小して加点
- ワーカー間で履歴を共有せず、新しい探索コンテキストごとに初期化

縮小前の予備設定は1スレッドの固定ノードを2.81%増加させたため不採用とし、縮小設定だけを正式評価した。

## Standard suite

| Threads | Baseline nodes | History nodes | Node change | Baseline 500 ms depth | History 500 ms depth |
|---:|---:|---:|---:|---:|---:|
| 1 | 292,268 | 277,154 | -5.17% | 9.31 | 9.31 |
| 2 | 292,268 | 285,586 | -2.29% | 9.31 | 9.25 |
| 4 | 307,016 | 300,728 | -2.05% | 9.69 | 9.63 |
| 8 | 318,453 | 310,682 | -2.44% | 9.94 | 9.88 |

## Validation suite

標準とは異なるseed `20260722`から32局面を生成し、固定深さ8、2反復で基準版と直接比較した。

| Threads | Baseline nodes | History nodes | Node change | Baseline time | History time | Time change |
|---:|---:|---:|---:|---:|---:|
| 1 | 82,186 | 78,589 | -4.38% | 104.662 ms | 98.286 ms | -6.09% |
| 2 | 82,186 | 80,757 | -1.74% | 106.294 ms | 103.303 ms | -2.81% |
| 4 | 82,265 | 80,753 | -1.84% | 66.115 ms | 65.477 ms | -0.96% |
| 8 | 82,604 | 81,275 | -1.61% | 55.841 ms | 53.394 ms | -4.38% |

負の変化率は削減または高速化を表す。全固定深さ結果で逐次版との指し手・評価値一致を確認した。

## Conclusion

historyは1スレッドでは4〜5%のノードを削減したが、ルートワーカーのSearchContextが指し手ごとに初期化される現構造では並列側の学習期間が短く、4スレッドの削減率は約2%に留まった。500 ms到達深さも改善しないため、Edax対局の前に性能ゲート不合格とした。

killer heuristicは探索plyごとに直近のcutoff手を直接優先でき、短いワーカー探索でも効果が出る可能性があるため、SEARCH-004でhistoryを含めず検証する。

## Validation

- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass
- Standard fixed-depth move/score mismatches: 0
- Validation fixed-depth move/score mismatches: 0
- Illegal or missing timed moves: 0

## Artifacts

- Standard history CSV: `search-003-history-standard-2026-07-21.csv`
- Validation history CSV: `search-003-history-validation-2026-07-21.csv`
- Validation baseline CSV: `search-003-baseline-validation-2026-07-21.csv`
