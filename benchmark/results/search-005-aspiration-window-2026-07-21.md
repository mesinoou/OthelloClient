# SEARCH-005 aspiration window

## Decision

`rejected`。標準局面ではノードを約4%削減したが、別seedでは大会用4スレッドの削減率が1.19%に縮み、標準500 ms探索の到達深さも改善しなかったため統合しない。

## Change

- Experimental commit: `6306a05`
- Base: `benchmark/parallel-v2-20260721` (`e6354ec`)
- 深さ4以降で前回反復深化の評価値を中心とする±512の窓を使用
- fail-lowまたはfail-high時は直ちに全窓で再探索
- 狭窓のルート結果をEXACTではなくUPPER_BOUNDまたはLOWER_BOUNDとして置換表へ保存
- 逐次ルート、パス局面、並列ルートで同じ窓境界を使用
- exact endgame本探索は全窓を維持
- aspiration探索回数と再探索回数を`SearchResult`とベンチマークCSVへ追加

標準8局面の深さ間評価差を事前確認したところ、幅512は深さ4から9の48遷移中41件（85.4%）を窓内に収めた。幅256は24件（50.0%）だけだったため採用せず、幅512だけを正式評価した。

## Standard suite

| Threads | Baseline nodes | Aspiration nodes | Node change | Retry rate | Baseline 500 ms depth | Aspiration 500 ms depth |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 292,268 | 281,006 | -3.85% | 14.6% | 9.31 | 9.25 |
| 2 | 292,268 | 281,006 | -3.85% | 14.6% | 9.31 | 9.13 |
| 4 | 307,016 | 293,319 | -4.46% | 14.6% | 9.69 | 9.63 |
| 8 | 318,453 | 304,087 | -4.51% | 14.6% | 9.94 | 10.00 |

## Validation suite

標準とは異なるseed `20260722`から32局面を生成し、固定深さ8、2反復で比較した。

| Threads | Baseline nodes | Aspiration nodes | Node change | Retry rate | Baseline time | Aspiration time | Time change |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 82,186 | 80,970 | -1.48% | 11.9% | 104.662 ms | 97.846 ms | -6.51% |
| 2 | 82,186 | 80,970 | -1.48% | 11.9% | 106.294 ms | 101.246 ms | -4.75% |
| 4 | 82,265 | 81,288 | -1.19% | 11.9% | 66.115 ms | 62.859 ms | -4.92% |
| 8 | 82,604 | 81,667 | -1.13% | 11.9% | 55.841 ms | 51.786 ms | -7.26% |

負の変化率は削減または高速化を表す。別日に採った壁時計は参考値とし、採否は固定深さノード数と同一条件の500 ms到達深さを優先した。

## Depth 10 suite

大会の10秒制限ではより深い探索になるため、標準8局面を固定深さ10でも追加確認した。

| Threads | Baseline nodes | Aspiration nodes | Node change | Retry rate |
|---:|---:|---:|---:|---:|
| 1 | 772,888 | 748,569 | -3.15% | 14.3% |
| 2 | 772,888 | 748,569 | -3.15% | 14.3% |
| 4 | 835,878 | 809,154 | -3.20% | 14.3% |
| 8 | 816,114 | 791,702 | -2.99% | 14.3% |

## Conclusion

aspiration windowは全測定でノード数を減らしたが、主対象4スレッドの削減率は標準深さ9で4.46%、別seed深さ8で1.19%、深さ10で3.20%だった。期待した5%以上の削減や500 ms到達深さの改善には届かず、効果も局面集合に依存した。実装はルート探索と結果形式に広く影響するため、この利益では保守コストを正当化できないと判断した。

性能ゲート未通過のためEdax対局は実施しなかった。次のSEARCH-006ではlate move reductionを単独評価し、選択的探索になるため性能ゲート通過後のEdax対局を必須とする。

## Validation

- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass
- Aspiration/full-window score comparisons: pass for sequential and 4 threads, including forced retries
- Standard fixed-depth move/score mismatches: 0 of 64
- Validation fixed-depth move/score mismatches: 0 of 256
- Depth 10 fixed-depth move/score mismatches: 0 of 32
- Illegal or missing timed moves: 0 of 64

## Artifacts

- Standard aspiration CSV: `search-005-aspiration-standard-2026-07-21.csv`
- Validation aspiration CSV: `search-005-aspiration-validation-2026-07-21.csv`
- Depth 10 aspiration CSV: `search-005-aspiration-depth10-2026-07-21.csv`
- Standard baseline CSV: `search-002-contention-standard-2026-07-21.csv`
- Validation baseline CSV: `search-003-baseline-validation-2026-07-21.csv`
- Depth 10 baseline CSV: `search-002-contention-depth10-2026-07-21.csv`
