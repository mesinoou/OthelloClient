# Parallel search benchmark v1

## Purpose

ルート並列探索を変更する前に、同一局面・同一深さでスレッド数だけを変えた比較基準を固定する。BENCH-001は計測基盤の追加であり、探索アルゴリズム自体は変更していない。

## Reproducibility

- Baseline tag: `baseline/learned-e80-20260721` (`50313ca`)
- Benchmark revision: `c3e89bea2bdaa20c1c4e525163ebc4a6e4a5b922`
- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Position seed: `20260721`
- Position suite SHA-256: `18CD490619DC45974F8D968E05E457D1F2DFA2B1BD58DE10B5E2938CA074E9BA`
- Runtime: Java 21, Windows 11, 20 logical processors
- Transposition table: 262,144 entries
- Positions: 8 deterministic random positions at ply 16 through 32, each with at least 3 legal moves
- Repetitions: 2; warmups per thread configuration: 1
- Raw results: `parallel-search-v1-2026-07-21.csv` (128 rows)

## Command

```powershell
java -cp .build ParallelSearchBenchmark `
  --model data/evaluation-tables.bin `
  --mode all `
  --threads 1,2,4,8 `
  --positions 8 `
  --depth 9 `
  --time-ms 500 `
  --repetitions 2 `
  --warmups 1 `
  --output benchmark/results/parallel-search-v1-2026-07-21.csv
```

## Results

| Mode | Threads | Samples | Avg depth | Avg time | Nodes/s | Worker share | Fixed-depth speedup |
|---|---:|---:|---:|---:|---:|---:|---:|
| Fixed depth 9 | 1 | 16 | 9.00 | 290.934 ms | 1,004,586 | 0.000 | 1.00x |
| Fixed depth 9 | 2 | 16 | 9.00 | 297.466 ms | 982,526 | 0.730 | 0.98x |
| Fixed depth 9 | 4 | 16 | 9.00 | 173.661 ms | 1,674,863 | 0.753 | 1.68x |
| Fixed depth 9 | 8 | 16 | 9.00 | 154.105 ms | 2,100,619 | 0.760 | 1.89x |
| Timed 500 ms | 1 | 16 | 9.38 | 500.677 ms | 977,519 | 0.000 | - |
| Timed 500 ms | 2 | 16 | 9.38 | 500.602 ms | 983,459 | 0.498 | - |
| Timed 500 ms | 4 | 16 | 10.00 | 500.838 ms | 1,425,357 | 0.562 | - |
| Timed 500 ms | 8 | 16 | 10.06 | 500.866 ms | 1,619,683 | 0.592 | - |

## Validation

- Fixed-depth result rows: 64; sequential move/score mismatches: 0
- Timed result rows: 64; illegal or missing moves: 0
- Worker-node rows with aggregate mismatch: 0
- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass

## Interpretation

現行方式は4スレッドで約1.68倍、8スレッドで約1.89倍に留まり、2スレッドでは固定深さがわずかに遅い。ワーカー比率も固定深さで約76%が上限であるため、メインスレッドの待機、指し手単位Future、共有置換表の競合を次の実験で個別に調べる価値がある。

この値は短時間の同一PC内比較であり、棋力や大会8秒設定での速度向上を直接示すものではない。SEARCH-001では同じCSV条件を再実行し、正当性一致を維持したまま固定深さ時間と500ms到達深さを比較する。
