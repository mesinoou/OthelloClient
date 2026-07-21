# SEARCH-016 specialized depth-0/1 leaf search

## Decision

`accepted`。通常探索のdepth 0・1を専用関数へ分岐すると、固定深さnode数は7〜12%増えたが、move配列、priority計算、selection sort、PVS再探索の省略が大きく上回った。大会用4TではStandard固定深さ時間が29.56%短縮し、500 ms平均深さが+0.44 plyとなった。ValidationとDepth 10でも効果が再現し、固定深さの探索結果はbaselineと完全一致した。

## Change

- Base: `baseline/shallow-tt-20260721` (`b5b064886a87584a007424a6296a339622b5e001`)
- Experiment setup: `43a2ccb`
- Implementation: `6e6e3f5799e228ed30a24327d648a06ba449e13a`
- depth 0・1をTTなしの専用`searchLeaf`へ分岐
- depth 0でも合法手、pass、両者pass終局を従来と同じ順序で判定
- depth 1はmove配列とsortを使わず、合法手bitを直接反復
- 各子のdepth 0評価を一度だけ実行し、alpha-beta cutoffを維持
- leaf子nodeとpass nodeのnode計数、停止確認、最大ply確認を維持
- 評価関数、TT、LMR、深さ2以上のPVSは変更しない

## Standard suite

8局面、seed `20260721`、固定深さ9と500 ms、各2反復で同時点baselineと比較した。

| Threads | Baseline fixed ms | Candidate fixed ms | Time change | Node change | Baseline 500 ms depth | Candidate 500 ms depth |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 279.05 | 195.47 | -29.95% | +12.62% | 9.88 | 10.19 |
| 2 | 284.38 | 197.68 | -30.49% | +12.62% | 9.88 | 10.19 |
| 4 | 172.43 | 121.46 | -29.56% | +12.47% | 10.31 | 10.75 |
| 8 | 145.23 | 102.36 | -29.52% | +12.37% | 10.38 | 10.75 |

4Tのfixed nodes/sは1,485,947から2,372,677へ59.67%増加した。node増加は、従来のdepth 1 move orderingを省いたためcutoffまでに調べる手が増えたことによる。1 node当たりの費用削減が十分大きく、全thread数で時間が短縮した。

## Validation and depth 10

Validationは別seed `20260722`の32局面、深さ8、2反復。Depth 10は標準8局面、1反復で測定した。

| Suite | Threads | Baseline ms | Candidate ms | Time change | Node change |
|---|---:|---:|---:|---:|---:|
| Validation | 1 | 83.47 | 53.72 | -35.64% | +7.14% |
| Validation | 2 | 86.41 | 57.19 | -33.82% | +7.14% |
| Validation | 4 | 53.93 | 36.86 | -31.64% | +7.12% |
| Validation | 8 | 47.57 | 31.16 | -34.49% | +7.12% |
| Depth 10 | 1 | 576.07 | 429.55 | -25.43% | +11.31% |
| Depth 10 | 2 | 567.00 | 436.19 | -23.07% | +11.31% |
| Depth 10 | 4 | 369.80 | 281.47 | -23.89% | +11.19% |
| Depth 10 | 8 | 317.88 | 238.93 | -24.84% | +11.19% |

4Tのnodes/sはValidationで56.71%、Depth 10で46.08%増加した。Standardだけに偏らず、別seedと深い探索でも性能向上が維持された。

## Correctness and determinism

- Java 11 target compilation with `-Xlint:all`: pass
- Regression tests: 9 of 9 pass
- depth 0・1・2の分岐境界test: pass
- 初期局面とmidgameのdepth 1 reference negamax比較: pass
- pass、連続pass終局、最大ply、外部停止test: pass
- Baseline comparison: fixed-depth best move and score mismatches 0 of 352
- Candidate stress: thread consistency failures 0 of 384
- Fixed-depth timeout or incomplete search: 0
- Illegal move or runtime error: 0

性能だけを変え、固定深さの全結果がbaselineと一致したため、Edax L7 100局の棋力ゲートは`TEST_PLAN.md`の短縮条件により省略した。

## Tournament-budget smoke test

10,000 ms/手、4T、Edax L7、8手openingを先後交換して2局実行した。

- Result: 2-0
- Margins: +2, +36
- Average move time: 6290.092 ms
- Average depth: 12.87
- Nodes/s: 1,762,275
- Exact endgame moves: 21
- Budget stops: 32
- Illegal moves, runtime errors, unfinished games: 0

前基準SEARCH-007の同条件smoke testに対し、平均深さは12.46から12.87へ+0.41 ply、nodes/sは43.82%増加した。2局だけなので棋力差の根拠には使わない。

## Environment

- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Java runtime: 21, compile target: 11
- OS: Windows 11 10.0
- Available processors: 20
- Standard suite SHA-256: `18CD490619DC45974F8D968E05E457D1F2DFA2B1BD58DE10B5E2938CA074E9BA`
- Validation suite SHA-256: `31B2984F20F474CB1326134F525C78B87AEEDBD85C239BDC7B38B62534081A45`

baseline classesは`b5b0648`でコンパイルし、候補branch上から実行した。benchmarkは実行時worktreeのrevisionを読むため、baseline CSV 3本の`gitRevision`列を実際のclass生成元`b5b064886a87584a007424a6296a339622b5e001`へ補正した。その他の列は未変更である。

## Artifacts

- `search-016-baseline-standard-2026-07-21.csv`
- `search-016-specialized-leaf-standard-2026-07-21.csv`
- `search-016-baseline-validation-2026-07-21.csv`
- `search-016-specialized-leaf-validation-2026-07-21.csv`
- `search-016-baseline-depth10-2026-07-21.csv`
- `search-016-specialized-leaf-depth10-2026-07-21.csv`
- `search-016-specialized-leaf-stress-2026-07-21.csv`
- `search-016-specialized-leaf-tournament-2026-07-21.txt`
