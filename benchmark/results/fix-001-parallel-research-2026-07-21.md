# FIX-001 parallel root re-search

## Decision

`accepted`。並列ルート探索の正当性修正として基準ブランチへ統合する。

## Defect

各ワーカーは共有alphaを読み、その値を使ったnull-window探索を行う。探索中に別ワーカーが共有alphaを更新すると、従来コードは探索後の新しいalphaとscoreを比較していた。

この場合、古いalphaに対してfail-highしていても、新しいalpha以下であれば再探索が省略される。null-window結果は下限値であり真の評価値ではないため、より良い手を見落としてルート評価値を過小評価する可能性がある。SEARCH-001の予備測定では、逐次版`1059`に対して並列版`1004`となる事例を検出した。

## Change

- Commit: `05650f5`
- Base: `benchmark/parallel-v1-20260721`
- 再探索判定を、探索後の共有alphaではなく、そのnull-window探索に実際に使用したalphaとの比較へ変更
- 再探索窓には引き続き最新の共有alphaを使用
- fail-high判定を固定する回帰テストを追加

## Validation

- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass
- Standard fixed-depth results: 64; move/score mismatches: 0
- Stress fixed-depth results: 384; move/score mismatches: 0
- Illegal or missing timed moves: 0
- Worker-node aggregate mismatches: 0

Stress条件はseed `20260721`から生成した32局面、固定深さ8、1・2・4・8スレッド、3反復である。

## Performance impact

| Threads | Baseline fixed nodes | Fixed nodes after fix | Node change | Baseline timed depth | Timed depth after fix |
|---:|---:|---:|---:|---:|---:|
| 1 | 292,268 | 292,268 | 0.00% | 9.25 | 9.25 |
| 2 | 292,268 | 292,268 | 0.00% | 9.25 | 9.25 |
| 4 | 292,391 | 306,739 | +4.91% | 9.63 | 9.63 |
| 8 | 299,715 | 317,662 | +5.99% | 10.00 | 9.88 |

再探索を正しく実行するため4・8スレッドでは探索量が増える。4スレッドの500 ms到達深さは維持され、8スレッドでは平均0.12 ply低下した。誤った枝刈りによる速度は採用できないため、このコストを受け入れる。

## Artifacts

- Standard raw CSV: `fix-001-parallel-research-2026-07-21.csv`
- Stress raw CSV: `fix-001-parallel-research-stress-2026-07-21.csv`
- Comparison baseline: `search-001-baseline-ab-2026-07-21.csv`
