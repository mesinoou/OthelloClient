# SEARCH-007 shallow TT access gating

## Decision

`accepted`。depth 0・1のTT probe/storeを省略すると固定深さのnode数は約6〜7%増えたが、TT access費用の削減が上回り、Standard、Validation、Depth 10のすべてのthread数で固定深さ時間が短縮した。大会用4TではStandard 500 ms平均深さが+0.25 plyとなり、事前に定めた性能ゲートを通過した。

## Change

- Base behavior and baseline classes: `9855a1e95067b3527be551dcdffc54b3701e1c08`
- Experiment setup: `4b1d0ae`
- Implementation: `1d02758028f3d7a9d4c42fbe23798218c776b44d`
- `depth >= 2`の場合だけTTをprobe/storeする
- rootのTT best move参照にも同じdepth gateを適用する
- TT容量、hash、striped lock、置換規則、move orderingは変更しない
- depth 0・1・2の境界を`SearchEngineTest`で固定する

## Standard suite

8局面、seed `20260721`、固定深さ9と500 ms、各2反復で同時点baselineと比較した。

| Threads | Baseline fixed ms | Candidate fixed ms | Time change | Node change | Baseline 500 ms depth | Candidate 500 ms depth |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 304.19 | 278.54 | -8.43% | +7.09% | 9.75 | 9.88 |
| 2 | 294.35 | 283.05 | -3.84% | +7.09% | 9.69 | 9.88 |
| 4 | 177.58 | 174.21 | -1.90% | +7.05% | 10.13 | 10.38 |
| 8 | 148.82 | 144.43 | -2.95% | +7.12% | 10.31 | 10.38 |

4Tのfixed nodes/sは1,347,074から1,469,930へ9.12%増加した。事前条件の「500 ms平均深さ+0.25 ply」を満たす。1Tまたは8Tの10%以上退行はない。

## Validation and depth 10

Validationは別seed `20260722`の32局面、深さ8、2反復。Depth 10は標準8局面、1反復で測定した。

| Suite | Threads | Baseline ms | Candidate ms | Time change | Node change |
|---|---:|---:|---:|---:|---:|
| Validation | 1 | 83.78 | 79.32 | -5.33% | +6.39% |
| Validation | 2 | 85.83 | 81.87 | -4.62% | +6.39% |
| Validation | 4 | 53.09 | 51.01 | -3.92% | +6.24% |
| Validation | 8 | 45.29 | 43.98 | -2.91% | +6.08% |
| Depth 10 | 1 | 601.62 | 555.88 | -7.60% | +6.18% |
| Depth 10 | 2 | 593.00 | 559.39 | -5.67% | +6.18% |
| Depth 10 | 4 | 398.37 | 367.27 | -7.81% | +6.19% |
| Depth 10 | 8 | 335.16 | 317.96 | -5.13% | +6.05% |

4Tのnodes/sはValidationで10.58%、Depth 10で15.18%増加した。TT hit数はStandardで約84%、Validationで約87%、Depth 10で約82%減少しており、浅い再利用を失ったためnode数は増える。一方、leaf近傍でのhash、monitor取得、probe pack/store費用の削減が時間面で上回ったと判断する。

## Correctness and determinism

- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass
- Baseline comparison: fixed-depth best move and score mismatches 0 of 352
- Candidate stress: thread consistency failures 0 of 384
- Fixed-depth timeout or incomplete search: 0
- Illegal move or runtime error: 0

性能だけを変え、固定深さの全結果がbaselineと一致したため、Edax L7 100局の棋力ゲートは`TEST_PLAN.md`の短縮条件により省略した。

## Tournament-budget smoke test

10,000 ms/手、4T、Edax L7、8手openingを先後交換して2局実行した。

- Result: 2-0
- Margins: +2, +12
- Average move time: 6419.906 ms
- Average depth: 12.46
- Nodes/s: 1,225,364
- Exact endgame moves: 19
- Budget stops: 33
- Illegal moves, runtime errors, unfinished games: 0

2局は棋力推定には使わず、時間管理、4T、終盤完全読みへの移行確認として扱う。

## Environment

- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Java runtime: 21, compile target: 11
- OS: Windows 11 10.0
- Available processors: 20
- Standard suite SHA-256: `18CD490619DC45974F8D968E05E457D1F2DFA2B1BD58DE10B5E2938CA074E9BA`
- Validation suite SHA-256: `31B2984F20F474CB1326134F525C78B87AEEDBD85C239BDC7B38B62534081A45`

baseline classesは`9855a1e`でコンパイルし、候補branch上から実行した。benchmarkは実行時worktreeのrevisionを読むため、baseline CSV 3本の`gitRevision`列を実際のclass生成元`9855a1e95067b3527be551dcdffc54b3701e1c08`へ補正した。その他の列は未変更である。

## Artifacts

- `search-007-baseline-standard-2026-07-21.csv`
- `search-007-shallow-tt-standard-2026-07-21.csv`
- `search-007-baseline-validation-2026-07-21.csv`
- `search-007-shallow-tt-validation-2026-07-21.csv`
- `search-007-baseline-depth10-2026-07-21.csv`
- `search-007-shallow-tt-depth10-2026-07-21.csv`
- `search-007-shallow-tt-stress-2026-07-21.csv`
- `search-007-shallow-tt-tournament-2026-07-21.txt`
