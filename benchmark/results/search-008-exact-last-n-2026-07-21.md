# SEARCH-008 exact last-N solver

## Decision

`accepted`。完全読みの残り4手以下を専用solverへ移すと、4スレッドの12〜18空き完全読み時間を49〜62%短縮した。通常suiteの最大逆行は6.31%で保留基準10%未満、固定深度の最善手・評価値不一致は0/352だったため統合する。

## Change

- Base: `baseline/specialized-leaf-20260721` (`82483df996dab1a2fce5bdb9dca3fd4325690955`)
- Experiment setup: `3664a63`
- Final search implementation: `64a109f559a3a23c20fb4903d1bc4968f1410036`
- Extended correctness test: `da84900`
- 完全読みの最終反復だけで専用solverを有効化
- 深い終盤探索はPVSのdepth 4で一度だけ`solve4`へ移行
- 開始局面が3〜4空きの場合はroot子から`solve2/3`へ直接移行
- 空きbitを直接反復し、move配列、selection sort、TT、LMR、評価関数を使わない
- passは同じ手数solverで視点反転し、連続passで`terminalScore`を返す
- `solve4`だけ奇数空き領域を先に試す
- node計数、alpha-beta cutoff、停止確認を維持

初期実装は全PVS nodeのdepth 2〜4でdispatchし、通常Standardを5〜7%低速化した。空き数の重複計算を除去した後も影響が残ったため、完全読み状態を明示し、PVS本体のdispatchをdepth 4の短い分岐へ縮小した。最終版では専用判定を通常のdepth 2・3 nodeから除外している。

## Last-N microbenchmark

32局面、1,000反復、5ラウンドの中央値。各空き数のchecksumは有効・無効で一致した。

| Empties | Generic ns/search | Specialized ns/search | Time change | Generic nodes | Specialized nodes |
|---:|---:|---:|---:|---:|---:|
| 1 | 843 | 893 | +5.93% | 2 | 2 |
| 2 | 3,203 | 3,136 | -2.09% | 7 | 7 |
| 3 | 10,654 | 8,643 | -18.88% | 22 | 21 |
| 4 | 28,447 | 25,389 | -10.75% | 64 | 62 |

1空きは専用solverを通らず両modeが同一コードを実行する。測定範囲はgeneric 714〜1,060 ns、specialized 843〜1,246 nsと重なっており、差はこの時間幅での揺らぎと判断した。2〜4空きに固有の逆行はない。

## Endgame benchmark

各12局面、3反復、4スレッド、30秒上限。全条件36/36を完全読みし、各反復のscore不一致は0件だった。

| Empties | Baseline ms | Candidate ms | Time change | Baseline max ms | Candidate max ms |
|---:|---:|---:|---:|---:|---:|
| 12 | 10 | 5 | -50.00% | 24 | 18 |
| 14 | 59 | 30 | -49.15% | 171 | 153 |
| 16 | 528 | 203 | -61.55% | 1,959 | 673 |
| 18 | 3,287 | 1,532 | -53.39% | 18,820 | 8,920 |

候補の計上nodeは11.9〜24.8%増えた。残り4手以下でTT lookupと手順整列を省いたため、node単価の低下がnode増加を大きく上回った。

## Standard suites

通常探索への副作用を確認した。Standardは8局面、深さ9と500 ms、2反復。Validationは別seed 32局面、深さ8、2反復。Deepは8局面、深さ10、1反復である。

| Suite | Threads | Baseline fixed ms | Candidate fixed ms | Time change | Baseline timed depth | Candidate timed depth |
|---|---:|---:|---:|---:|---:|---:|
| Standard | 1 | 203.561 | 210.374 | +3.35% | 10.188 | 10.250 |
| Standard | 2 | 197.836 | 203.154 | +2.69% | 10.188 | 10.250 |
| Standard | 4 | 118.106 | 125.560 | +6.31% | 10.750 | 10.750 |
| Standard | 8 | 100.757 | 102.147 | +1.38% | 10.812 | 10.812 |
| Validation | 4 | 34.765 | 34.523 | -0.70% | - | - |
| Deep | 4 | 245.895 | 241.782 | -1.67% | - | - |

Standardの固定node平均は1・2スレッドで基準と同一、並列4・8スレッドではそれぞれ-0.03%、+0.06%だった。500 ms平均深度は1・2スレッドで+0.063、4・8スレッドで同値だった。ValidationとDeepを含め10%を超える通常探索の逆行はない。

## Correctness and determinism

- Java 11 target compilation with `-Xlint:all`: pass、警告0件
- Regression tests: 9 of 9 pass
- 0〜8空きの129局面と既存12空きfixtureで汎用PVSとのscore・最善手一致
- 1空きpass、空きを残した連続pass終局、wipeout fixture: pass
- Standard fixed-depth mismatches: 0 of 64
- Validation fixed-depth mismatches: 0 of 256
- Deep fixed-depth mismatches: 0 of 32
- Candidate thread consistency failures: 0 of 352
- Illegal or missing timed moves: 0 of 64

性能だけを変え、固定深さ結果がbaselineと完全一致するため、Edax L7 100局の棋力ゲートは短縮条件により省略した。

## Tournament-budget smoke test

10,000 ms/手、4スレッド、Edax L7、8手openingを先後交換して2局実行した。

- Result: 1-0-1、score rate 50.00%
- Margins: -14、+36、average +11.00
- Average move time: 6,304.928 ms
- Average depth: 13.02
- Nodes/s: 1,999,258
- Exact endgame moves: 21
- Budget stops: 32
- Illegal moves, runtime errors, unfinished games: 0

2局は時間管理と完全読み移行のsmoke testであり、棋力差の根拠には使わない。

## Environment

- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Java runtime: 21、compile target: 11
- OS: Windows 11 10.0
- Available processors: 20
- Standard/Deep suite SHA-256: `18CD490619DC45974F8D968E05E457D1F2DFA2B1BD58DE10B5E2938CA074E9BA`
- Validation suite SHA-256: `31B2984F20F474CB1326134F525C78B87AEEDBD85C239BDC7B38B62534081A45`

baseline classesは`82483df`、候補classesは`64a109f`でコンパイルした。benchmarkは実行時worktreeのrevisionを読むため、baseline CSVの`gitRevision`を実際のclass生成元`82483df996dab1a2fce5bdb9dca3fd4325690955`へ、候補CSVを`64a109f559a3a23c20fb4903d1bc4968f1410036`へ補正した。その他の列は未変更である。

## Artifacts

- `search-008-exact-last-n-micro-2026-07-21.csv`
- `search-008-endgame-4t-2026-07-21.txt`
- `search-008-baseline-standard-2026-07-21.csv`
- `search-008-exact-last-n-standard-2026-07-21.csv`
- `search-008-baseline-validation-2026-07-21.csv`
- `search-008-exact-last-n-validation-2026-07-21.csv`
- `search-008-baseline-depth10-2026-07-21.csv`
- `search-008-exact-last-n-depth10-2026-07-21.csv`
- `search-008-exact-last-n-tournament-2026-07-21.txt`
