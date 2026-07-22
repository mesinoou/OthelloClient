# SEARCH-004 killer move ordering

## Decision

`rejected`。弱い補助値では大会用4スレッドの固定深さノード数を削減できず、標準500 ms探索の到達深さも低下したため統合しない。

## Change

- Experimental commit: `a2c2129`
- Base: `benchmark/parallel-v2-20260721` (`e6354ec`)
- 探索plyごとに直近2件のbeta cutoff手を`SearchContext`へ記録
- 最新手をprimary、直前手をsecondaryとして管理し、同じ手の重複登録を抑止
- 既存の可動性優先度へprimary `256`、secondary `128`を加点
- 反復深化の優先手、置換表手、隅はkillerより上位を維持
- ワーカー間では共有せず、各探索コンテキスト内だけで利用

予備設定のprimary `10,000`、secondary `5,000`では、標準固定深さのノード数が1/2/4/8スレッドでそれぞれ10.70%、10.66%、8.78%、6.72%増加した。同一plyの同一升を強制的に昇格する効果が強すぎると判断し、可動性2から3手分の補助値へ一度だけ縮小して正式評価した。

## Standard suite

| Threads | Baseline nodes | Killer nodes | Node change | Baseline 500 ms depth | Killer 500 ms depth |
|---:|---:|---:|---:|---:|---:|
| 1 | 292,268 | 282,796 | -3.24% | 9.31 | 9.25 |
| 2 | 292,268 | 294,927 | +0.91% | 9.31 | 9.31 |
| 4 | 307,016 | 306,217 | -0.26% | 9.69 | 9.56 |
| 8 | 318,453 | 306,713 | -3.69% | 9.94 | 9.75 |

## Validation suite

標準とは異なるseed `20260722`から32局面を生成し、固定深さ8、2反復で基準版と比較した。

| Threads | Baseline nodes | Killer nodes | Node change | Baseline time | Killer time | Time change |
|---:|---:|---:|---:|---:|---:|
| 1 | 82,186 | 81,160 | -1.25% | 104.662 ms | 99.193 ms | -5.23% |
| 2 | 82,186 | 82,559 | +0.45% | 106.294 ms | 102.981 ms | -3.12% |
| 4 | 82,265 | 82,742 | +0.58% | 66.115 ms | 64.377 ms | -2.63% |
| 8 | 82,604 | 83,065 | +0.56% | 55.841 ms | 54.093 ms | -3.13% |

負の変化率は削減または高速化を表す。別日に採った壁時計は参考値とし、採否は決定的な固定深さノード数と同一実行の500 ms到達深さを優先した。

## Conclusion

killer手は1スレッドと標準8スレッドでは一部ノードを削減したが、主対象の4スレッドでは標準セットが0.26%減、別seedが0.58%増で実質的な改善を示さなかった。さらに500 ms到達深さは4スレッドで9.69から9.56へ低下した。Othelloでは同じply・同じ升でも兄弟局面間で戦術的意味が安定しにくく、現在の可動性順序を置き換えるほどの予測力はないと判断した。

性能ゲート未通過のためEdax対局は実施しなかった。次のSEARCH-005では反復深化の前回評価値を利用するaspiration windowを、historyとkillerを含めず単独評価する。

## Validation

- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass
- Standard fixed-depth move/score mismatches: 0 of 64
- Validation fixed-depth move/score mismatches: 0 of 256
- Illegal or missing timed moves: 0 of 64

## Artifacts

- Standard killer CSV: `search-004-killer-standard-2026-07-21.csv`
- Validation killer CSV: `search-004-killer-validation-2026-07-21.csv`
- Standard baseline CSV: `search-002-contention-standard-2026-07-21.csv`
- Validation baseline CSV: `search-003-baseline-validation-2026-07-21.csv`
