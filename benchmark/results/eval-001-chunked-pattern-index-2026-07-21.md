# EVAL-001 chunked ternary pattern indexing

## Decision

`rejected`。既存評価との完全一致と追加表8 MiB以下は満たしたが、learned評価の中央値が59.26%悪化し、大会用4スレッド探索も固定深度時間が15.65%増、500 ms到達深さが0.313 ply低下したため統合しない。

## Change

- Experimental commit: `20e2ad3`
- Base: `baseline/specialized-leaf-20260721` (`82483df`)
- 盤面を8 byteへ分割し、各byteのplayer/opponent配置を三進状態`0..6560`へ変換
- 42個の局所patternについて、使用するbyte chunkごとのindex寄与表をJVM起動時に構築
- モデル形式、学習済みtable、追加特徴、探索処理は変更しない
- 旧index計算をテスト用に残し、実棋譜の各plyで新旧pattern scoreを比較

## Evaluation benchmark

`EvaluationBenchmark`を5,000,000評価、ベースラインと候補を交互に3回実行した。checksumは全実行で`1817378868`となり一致した。

| Implementation | learned ns/eval | eval/s | Change |
|---|---:|---:|---:|
| Baseline median | 514.0 | 1,945,498 | - |
| Chunked median | 818.6 | 1,221,641 | +59.26% time |

目標は15%以上の短縮だったが、実測は逆方向だった。

## Standard suite

8局面、深さ9、500 ms、2反復で測定した。負のNPS変化は低速化を表す。

| Threads | Fixed time change | Fixed NPS change | Baseline depth | Chunked depth | Timed NPS change |
|---:|---:|---:|---:|---:|---:|
| 1 | +22.59% | -15.51% | 10.188 | 10.000 | -12.91% |
| 2 | +23.16% | -17.71% | 10.125 | 9.875 | -14.30% |
| 4 | +15.65% | -14.86% | 10.688 | 10.375 | -10.70% |
| 8 | +14.75% | -14.45% | 10.750 | 10.750 | -8.68% |

固定深度64結果の最善手・評価値不一致は0件だった。性能ゲート未通過が明確なため、Validation、Deep、Edax対局は早期終了した。

## Resource impact

- 寄与表の生データ量: 2,729,426 bytes（2.60 MiB、配列headerを除く）
- 追加メモリ目標8 MiB: pass
- JVMプロセス起動とstatic初期化の10回中央値: 81.344 msから117.621 msへ増加（+44.60%）
- Environment: Java 21、Windows 11、available processors 20
- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`

候補は42 patternを求めるために198個のchunk寄与値を複数の`char[]`から参照する。旧実装の単純な64-square state配列走査と三進index演算より、間接参照、bounds check、非連続メモリアクセスの費用が大きくなったと判断する。

## Validation

- Java 11 target compilation with `-Xlint:all`: pass、警告0件
- Regression tests: 9 of 9 pass
- 実棋譜各plyの旧・新pattern score: 一致
- Evaluation checksum: 一致
- Standard fixed-depth move/score mismatches: 0 of 64
- Illegal or missing timed moves: 0 of 64

ベースラインCSVは`.build-baseline`のクラスを現行worktreeから起動したため、生成時のrevision列を実際のクラスに対応する`82483df996dab1a2fce5bdb9dca3fd4325690955`へ補正した。候補CSVの`-dirty`は先に生成した未追跡ベースラインCSVだけが原因だったため、実装コミット`20e2ad39b439664fa4e14b2304002a41589f3f80`へ補正した。

## Conclusion

三進寄与表は算術命令を減らしたが、参照回数と局所性を悪化させた。現在のJava実装では採用余地がない。次の実験は予定どおり`SEARCH-008 Exact last-N solver`とし、EVAL-001のコードは基準ブランチへ統合しない。

## Artifacts

- Baseline standard CSV: `eval-001-baseline-standard.csv`
- Chunked standard CSV: `eval-001-candidate-standard.csv`
