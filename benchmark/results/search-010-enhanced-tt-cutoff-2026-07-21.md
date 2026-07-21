# SEARCH-010 enhanced transposition cutoff

## Decision

`rejected`。depth 5以上のnull-window nodeで、十分深い子局面の`EXACT`または`UPPER_BOUND`から安全な親fail-highを証明した。固定深さの最善手・評価値はbaselineと352/352一致し、parallel stressも384/384一致した。しかし大会用4Tのnode削減はStandard 1.54%、Validation 0.32%、Depth 10 0.89%に留まり、500 ms平均深度も同値だった。事前条件の4T node 5%以上削減を満たさず、Depth 10の4T時間は6.18%悪化したため統合しない。

## Change

- Base behavior: `baseline/exact-last-n-20260721` (`1ec9e7561d1be365140cedaf6905a459b3e57b81`)
- Experiment setup: `7b655a3`
- ETC implementation: `696c348`
- Benchmark metrics: `604321f`
- Final null-window gate: `a9a57d9`
- 子entryのdepthが`parent depth - 1`以上の場合だけ利用
- 子`EXACT/UPPER_BOUND`を否定して親lower boundへ変換
- 親lower boundがbeta以上の場合だけ親`LOWER_BOUND`を保存してcutoff
- LMRの未検証縮約boundは現行規則によりTTへ保存されないため利用対象外
- move ordering、TT構造、評価関数は変更しない

## Preliminary condition

初版はdepth 5以上の全window nodeでETCを試した。Standard 4Tで143,309 probe、803 usable hit、286 cutoffとなり、nodeは1.51%減ったが時間は1.62%悪化した。null-window限定では137,864 probe、749 usable hit、288 cutoffとなり、cutoffを維持しながらprobeを3.80%削減したため、こちらを最終実装とした。

## Standard suite

8局面、seed `20260721`、固定深さ9と500 ms、各2反復。同じclassを`--disable-etc`で切り替えて比較した。

| Threads | Baseline fixed ms | ETC fixed ms | Time change | Node change | Baseline 500 ms depth | ETC 500 ms depth |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 172.718 | 165.891 | -3.95% | -1.52% | 10.312 | 10.375 |
| 2 | 171.092 | 171.771 | +0.40% | -1.52% | 10.312 | 10.312 |
| 4 | 101.416 | 101.619 | +0.20% | -1.54% | 10.750 | 10.750 |
| 8 | 91.930 | 87.824 | -4.47% | -1.41% | 10.812 | 10.875 |

4Tでは137,864 probe、749 usable hit、288 cutoffだった。baselineとの差分は70,789 nodeで、probe当たり削減nodeは0.5135である。追加probeと盤面適用の費用を安定して上回るには不足していた。

## Validation and depth 10

Validationは別seed `20260722`の32局面、深さ8、2反復。Depth 10は標準8局面、1反復で測定した。

| Suite | Threads | Baseline ms | ETC ms | Time change | Node change |
|---|---:|---:|---:|---:|---:|
| Validation | 1 | 51.125 | 51.534 | +0.80% | -0.34% |
| Validation | 2 | 54.613 | 53.415 | -2.19% | -0.34% |
| Validation | 4 | 33.893 | 33.281 | -1.81% | -0.32% |
| Validation | 8 | 29.472 | 28.975 | -1.69% | -0.27% |
| Depth 10 | 1 | 345.473 | 349.478 | +1.16% | -0.90% |
| Depth 10 | 2 | 358.168 | 372.673 | +4.05% | -0.90% |
| Depth 10 | 4 | 222.385 | 236.124 | +6.18% | -0.89% |
| Depth 10 | 8 | 203.480 | 201.285 | -1.08% | -0.82% |

4Tの診断値はValidationで133,216 probe・445 hit・155 cutoff・0.1261 node/probe、Depth 10で158,694 probe・663 hit・297 cutoff・0.2588 node/probeだった。局面集合を変えても5% node削減へ近づかず、深い探索では追加probe費用による時間悪化が表れた。

## Correctness and determinism

- Java 11 target compilation with `-Xlint:all`: pass、警告0件
- Required regression test classes: 9 of 9 pass
- child bound種別、required depth、符号反転、ETC depth/null-window境界をunit testで固定
- baseline/ETC固定深さの最善手・評価値不一致: 0 of 352
- ETC stress: 32局面、3反復、1/2/4/8Tの不一致0 of 384
- 固定深さtimeout、欠落手、runtime error: 0
- 500 ms探索64件の合法手欠落: 0

性能ゲート未達の停止条件によりL5対戦とL7統合検証は実施しない。

## Environment

- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Java runtime: 21、compile target: 11
- OS: Windows 11 10.0
- Available processors: 20
- Standard/Depth 10 suite SHA-256: `18CD490619DC45974F8D968E05E457D1F2DFA2B1BD58DE10B5E2938CA074E9BA`
- Validation suite SHA-256: `31B2984F20F474CB1326134F525C78B87AEEDBD85C239BDC7B38B62534081A45`
- Stress suite SHA-256: `0D65813BA09BEBF1E95D590FDE7AAD808F79BD112B827C3E4182EA0DF8F46817`

Standardのbaseline/ETCはcleanな最終実装commitから測定した。以後のCSVは先に生成した未追跡CSVが存在したため`gitRevision`に`-dirty`が付くが、class生成元はすべて`a9a57d9a1c8a2085c85b1fc4217a6d9d1a97628e`である。

## Artifacts

- `search-010-baseline-standard-2026-07-21.csv`
- `search-010-etc-standard-2026-07-21.csv`
- `search-010-baseline-validation-2026-07-21.csv`
- `search-010-etc-validation-2026-07-21.csv`
- `search-010-baseline-depth10-2026-07-21.csv`
- `search-010-etc-depth10-2026-07-21.csv`
- `search-010-etc-stress-2026-07-21.csv`
