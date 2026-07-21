# SEARCH-002 transposition contention

## Decision

置換表の同期方式変更は`rejected`。モニタ競合の計測機能は今後の並列実験に利用できるため、ベンチマーク基盤として採用する。

## Hypothesis

`TranspositionTable`は256個のロックを使い、`probe`と`store`を`synchronized`で保護している。4・8スレッドでこのロック待機が大きければ、読み取り側のロック削減またはストライプ数変更により探索速度を改善できると予想した。

## Measurement

- Instrumentation commit: `c96094b`
- Base: `benchmark/parallel-v1.1-20260721` (`5ed559b`)
- JVM `ThreadMXBean`のcontention monitoringを使用
- 各探索前後でSearchEngineワーカーのmonitor blocked count/timeを取得
- 探索本体と置換表実装は変更なし
- Java 21、Windows 11、20 logical processors
- 学習モデル、seed、局面集合、TT容量はBENCH-001と同一

## Standard results

| Mode | Threads | Samples | Avg time | Monitor blocks | Blocked time |
|---|---:|---:|---:|---:|---:|
| Fixed depth 9 | 1 | 16 | 323.595 ms | 0 | 0 ms |
| Fixed depth 9 | 2 | 16 | 331.460 ms | 0 | 0 ms |
| Fixed depth 9 | 4 | 16 | 208.271 ms | 59 | 0 ms |
| Fixed depth 9 | 8 | 16 | 177.947 ms | 78 | 2 ms |
| Timed 500 ms | 1 | 16 | 500.613 ms | 0 | 0 ms |
| Timed 500 ms | 2 | 16 | 500.807 ms | 0 | 0 ms |
| Timed 500 ms | 4 | 16 | 500.926 ms | 65 | 1 ms |
| Timed 500 ms | 8 | 16 | 500.738 ms | 123 | 0 ms |

## Depth 10 results

| Threads | Samples | Avg time | Monitor blocks | Blocked time | Blocked / wall time |
|---:|---:|---:|---:|---:|---:|
| 1 | 8 | 959.750 ms | 0 | 0 ms | 0.00% |
| 2 | 8 | 961.686 ms | 0 | 0 ms | 0.00% |
| 4 | 8 | 626.389 ms | 33 | 14 ms | 0.28% |
| 8 | 8 | 537.732 ms | 66 | 3 ms | 0.07% |

`Blocked / wall time`は全サンプルのワーカー累計blocked timeを全サンプルのwall timeで割った参考値である。JVMの待機時間はミリ秒単位なので、短い競合は0 msとして記録される。

## Conclusion

最も大きい測定でも待機時間はwall timeの0.28%であり、モニタ待機を完全に除去しても今回の採用基準へ届かない。ロックフリー化や`StampedLock`化はメモリ可視性、書き込み整合性、単一スレッド性能へのリスクが相対的に大きいため実装しない。

この計測はモニタのBLOCKED状態を対象とし、共有配列のキャッシュコヒーレンスやメモリ帯域は直接測定しない。4スレッドの伸び悩みの主因は、置換表のモニタ待機以外にあると判断する。

## Validation

- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass
- Standard fixed-depth move/score mismatches: 0
- Depth 10 fixed-depth move/score mismatches: 0
- Illegal or missing timed moves: 0

## Artifacts

- Standard raw CSV: `search-002-contention-standard-2026-07-21.csv`
- Depth 10 raw CSV: `search-002-contention-depth10-2026-07-21.csv`
