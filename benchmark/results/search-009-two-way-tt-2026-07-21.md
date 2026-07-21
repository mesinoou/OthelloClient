# SEARCH-009 two-way bucket transposition table

## Decision

`rejected`。総entry数を維持した2-way bucket化により、深さ10のTT hit率は約1.1ポイント上昇し、collision率は約8.0ポイント低下した。しかし大会用4Tの固定深さnode削減はStandard 0.45%、Validation 0.07%、Depth 10 0.57%に留まり、500 ms平均到達深さも変わらなかった。事前条件の「4T node 5%以上削減、または500 ms深度+0.25 ply」を満たさないため統合しない。

## Change

- Base: `baseline/exact-last-n-20260721` (`1ec9e7561d1be365140cedaf6905a459b3e57b81`)
- Experiment setup: `2e44e63`
- Implementation: `36d0b9e`
- Benchmark instrumentation: `2d79102`, `8ae13b8`
- 同じ総entry数を1-wayまたは2-way bucketとして実行時に切替可能
- probeはbucket内の2 slotを順に確認
- 同一局面、空slot、古いgeneration、浅いdepth、非EXACT、slot 1の順で決定的に更新・置換
- striped lockはentryではなくbucket単位
- 通常実行では計測counterを生成せず、診断時だけ`LongAdder`を有効化

## Storage

production容量262,144 entryで、局面配列8本と256個のlockは1-way/2-wayで同数である。配列payloadの概算は両方式とも8,126,464 bytesで差は0 bytes。2-way化で増える固定fieldは`ways`とnullable counter参照だけであり、heap増加5%条件を十分下回る。

## Standard suite

8局面、seed `20260721`、固定深さ9と500 ms、各2反復。同じclassを`--tt-ways 1`と`2`で切り替えて比較した。

| Threads | 1-way fixed ms | 2-way fixed ms | Time change | Node change | 1-way 500 ms depth | 2-way 500 ms depth |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 220.981 | 206.503 | -6.55% | -0.41% | 10.188 | 10.188 |
| 2 | 221.929 | 213.905 | -3.62% | -0.41% | 10.125 | 10.250 |
| 4 | 135.098 | 127.292 | -5.78% | -0.45% | 10.625 | 10.625 |
| 8 | 115.455 | 108.133 | -6.34% | -0.52% | 10.750 | 10.750 |

固定深さ時間はStandardで短縮したが、採否指標である4T node削減と500 ms深度向上は未達だった。

## Validation and depth 10

Validationは別seed `20260722`の32局面、深さ8、2反復。Depth 10は標準8局面、1反復で測定した。

| Suite | Threads | 1-way ms | 2-way ms | Time change | Node change |
|---|---:|---:|---:|---:|---:|
| Validation | 1 | 67.629 | 65.976 | -2.44% | -0.07% |
| Validation | 2 | 79.892 | 67.816 | -15.11% | -0.07% |
| Validation | 4 | 44.132 | 42.955 | -2.67% | -0.07% |
| Validation | 8 | 36.766 | 37.359 | +1.61% | -0.08% |
| Depth 10 | 1 | 421.366 | 428.256 | +1.64% | -0.60% |
| Depth 10 | 2 | 474.443 | 444.653 | -6.28% | -0.60% |
| Depth 10 | 4 | 277.547 | 292.967 | +5.56% | -0.57% |
| Depth 10 | 8 | 240.511 | 245.041 | +1.88% | -0.61% |

深さ10では4T時間が5.56%悪化した。2 slot probeの追加費用に対し、再利用増加による探索木削減が小さすぎると判断する。

## TT diagnostics

深さ10、8局面、1反復でcounterを有効化した。hit率は`hits/probes`、collision率は`collisions/stores`である。

| Threads | Ways | Probes | Hit rate | Stores | Collision rate | Replacements |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 431,730 | 33.081% | 383,249 | 30.249% | 105,738 |
| 1 | 2 | 430,629 | 34.170% | 381,610 | 22.200% | 84,717 |
| 4 | 1 | 432,476 | 33.081% | 383,917 | 30.206% | 105,737 |
| 4 | 2 | 431,327 | 34.192% | 382,148 | 22.163% | 84,696 |

2-way化そのものは機能し、4Tでhit率+1.111ポイント、collision率-8.043ポイント、replacement約20%減となった。しかし保存された追加entryが最終的なnode数へ与える効果は0.6%未満だった。

## Correctness and determinism

- Java 11 target compilation with `-Xlint:all`: pass、警告0件
- Regression test classes: 9 of 9 pass
- 1-way/2-way固定深さの最善手・評価値不一致: 0 of 352
- 2-way stress: 32局面、3反復、1/2/4/8Tの不一致0 of 384
- 固定深さtimeout、欠落手、runtime error: 0
- 置換優先順位、同一局面更新、総storage一致をunit testで固定

性能ゲート未達のためL5対戦とL7大会時間smoke testは実施しない。

## Environment

- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Java runtime: 21、compile target: 11
- OS: Windows 11 10.0
- Available processors: 20
- Standard/Depth 10 suite SHA-256: `18CD490619DC45974F8D968E05E457D1F2DFA2B1BD58DE10B5E2938CA074E9BA`
- Validation suite SHA-256: `31B2984F20F474CB1326134F525C78B87AEEDBD85C239BDC7B38B62534081A45`
- Stress suite SHA-256: `0D65813BA09BEBF1E95D590FDE7AAD808F79BD112B827C3E4182EA0DF8F46817`

## Artifacts

- `search-009-one-way-standard-2026-07-21.csv`
- `search-009-two-way-standard-2026-07-21.csv`
- `search-009-one-way-validation-2026-07-21.csv`
- `search-009-two-way-validation-2026-07-21.csv`
- `search-009-one-way-depth10-2026-07-21.csv`
- `search-009-two-way-depth10-2026-07-21.csv`
- `search-009-one-way-tt-metrics-2026-07-21.csv`
- `search-009-two-way-tt-metrics-2026-07-21.csv`
- `search-009-two-way-stress-2026-07-21.csv`
