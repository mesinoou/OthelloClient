# SEARCH-001 root worker loop

## Decision

`rejected`。固定ワーカーループとメインスレッド参加は2スレッドでは有効だったが、大会設定の4スレッドでは改善が小さく、8スレッドで退行したため基準版へ統合しない。

## Change

- Experimental commit: `e6c7491`
- Base: `benchmark/parallel-v1-20260721` (`ce75770`)
- 指し手ごとのFutureを、ワーカー数で上限化した持続タスクへ変更
- 各タスクが共有インデックスから次のルート手を取得
- 最初の手の探索後、メインスレッドも残りの手を探索
- 並行中に古いalphaでfail-highした手を必ず再探索するよう修正

最後の修正は性能変更から分離すべき正当性修正であるため、`FIX-001`として別途検証する。

## Method

- Java 21、Windows 11、20 logical processors
- 学習モデル、局面seed、局面集合、TT容量はBENCH-001と同一
- 8局面、2反復、固定深さ9と500 ms探索
- 基準版と実験版を同じ作業時間帯に再実行
- Baseline raw CSV: `search-001-baseline-ab-2026-07-21.csv`
- Experiment raw CSV: `search-001-worker-loop-2026-07-21.csv`

## Fixed-depth results

| Threads | Baseline | Experiment | Wall-time change | Baseline tasks | Experiment tasks |
|---:|---:|---:|---:|---:|---:|
| 1 | 372.479 ms | 380.848 ms | +2.25% | 0.0 | 0.0 |
| 2 | 373.484 ms | 273.818 ms | -26.69% | 81.4 | 7.0 |
| 4 | 218.552 ms | 214.080 ms | -2.05% | 81.4 | 21.0 |
| 8 | 181.510 ms | 201.098 ms | +10.79% | 81.4 | 48.1 |

負の変化率は高速化を表す。全64行で逐次探索との指し手・評価値一致を確認した。

## Timed results

| Threads | Baseline depth | Experiment depth | Difference |
|---:|---:|---:|---:|
| 1 | 9.25 | 9.25 | 0.00 |
| 2 | 9.25 | 9.44 | +0.19 |
| 4 | 9.63 | 9.63 | 0.00 |
| 8 | 10.00 | 9.88 | -0.12 |

固定ワーカーループにより平均タスク数は大きく減った。一方、メインスレッドを加えた同時探索数の増加と再探索ノードにより、4スレッド以上ではタスク削減が実時間短縮へ十分結び付かなかった。

## Validation

- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass
- Fixed-depth move/score mismatches after correction: 0
- Illegal or missing timed moves: 0
- Worker-node aggregate mismatches: 0

予備測定では、並行中に共有alphaが更新されると古いalphaでfail-highした手の再探索を省略し、評価値が逐次版と不一致になる事例を1件検出した。再探索条件を修正してから上記測定を行っている。
