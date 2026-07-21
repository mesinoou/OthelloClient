# SEARCH-011 stability bound cutoff

## Decision

`accepted`。完全読み中のedge安定石から安全な最終石差の上下限を作り、null-windowのfail-high/fail-lowを証明した。12・14・16・18空きの4T完全読み全体で平均時間を8.35%短縮し、14〜18空きは6.86〜18.04%短縮した。12空き時間は+0.44%だが差は30 usで、nodeは11.92%減っている。48局面のbaseline/candidate score checksum、1/2/4/8T checksum、通常固定深さ352比較がすべて一致したため統合する。

## Change

- Base behavior: `baseline/exact-last-n-20260721` (`1ec9e7561d1be365140cedaf6905a459b3e57b81`)
- Experiment setup: `975b79b`
- Stability implementation: `9fd28ea`
- Benchmark counters: `40c2332`
- Score checksum: `95d1867`
- Microsecond timing: `74a7283`
- edge安定石だけを使い、内部安定石は計算しない
- 完全読み中、5〜18空き、null-windowだけを対象とする
- edge安定石は最大28枚のため、`beta <= terminal(-8)`または`alpha >= terminal(+8)`の場合だけ計算する
- lower boundがbeta以上なら親`LOWER_BOUND`、upper boundがalpha以下なら親`UPPER_BOUND`を保存する
- 通常探索、評価関数、手順整列、TT構造は変更しない

## Safety

現在手番の安定石数を`stablePlayer`、相手を`stableOpponent`とすると、最終石差は必ず次の範囲に入る。

```text
lowerDisc = 2 * stablePlayer - 64
upperDisc = 64 - 2 * stableOpponent
```

終局score変換は石差に対して単調なので、この石差範囲をscoreへ変換しても安全なlower/upper boundになる。full-windowの縮小や推測値は使わず、null-windowを越えたことが証明できる場合だけcutoffする。5〜9空き20局面のunit testでも完全読みscoreとbound包含を直接検証した。

## Endgame benchmark

各12局面、3反復、4T、30秒上限。同一の最終benchmark classをstability無効/有効で切り替えた。全条件36/36を完全読みし、各空き数のscore checksumはbaseline/candidateで一致した。

| Empties | Baseline us | Stability us | Time change | Node change | Checks/search | Cuts/search |
|---:|---:|---:|---:|---:|---:|---:|
| 12 | 6,828 | 6,858 | +0.44% | -11.92% | 2,392 | 53 |
| 14 | 24,836 | 20,669 | -16.78% | -8.14% | 12,153 | 189 |
| 16 | 205,230 | 168,197 | -18.04% | -20.18% | 140,320 | 3,402 |
| 18 | 1,433,966 | 1,335,613 | -6.86% | -8.31% | 1,267,156 | 11,497 |

各空き数の検索数が同じため平均時間を合算すると、417,715 usから382,834 usへ8.35%短縮した。安定石計算時間はend-to-end時間へ含めており、nodeごとの`nanoTime`計測は探索自体を歪めるため入れていない。cutoff率は低いが、深い部分木を落とせるため14〜18空きで計算費用を上回った。

16空き1反復では1Tが333,320 usから284,173 usへ14.74%、8Tが158,652 usから130,926 usへ17.47%短縮した。1/2/4/8Tのscore checksumはすべて`2756962113639585535`で一致した。

## Normal search suites

通常探索では全CSVの`stabilityChecks`と`stabilityCuts`が0だった。Standardは8局面、深さ9と500 ms、2反復。Validationは別seedの32局面、深さ8、2反復。Depth 10は8局面、1反復である。

| Suite | Threads | Baseline ms | Stability ms | Time change | Node change |
|---|---:|---:|---:|---:|---:|
| Standard | 1 | 175.453 | 171.664 | -2.16% | 0.00% |
| Standard | 2 | 179.283 | 176.551 | -1.52% | 0.00% |
| Standard | 4 | 102.989 | 101.496 | -1.45% | +0.03% |
| Standard | 8 | 88.471 | 84.584 | -4.39% | 0.00% |
| Validation | 1 | 49.412 | 51.465 | +4.15% | 0.00% |
| Validation | 2 | 50.824 | 51.747 | +1.82% | 0.00% |
| Validation | 4 | 31.192 | 32.705 | +4.85% | +0.02% |
| Validation | 8 | 27.241 | 28.737 | +5.49% | 0.00% |
| Depth 10 | 1 | 353.496 | 345.047 | -2.39% | 0.00% |
| Depth 10 | 2 | 359.023 | 351.222 | -2.17% | 0.00% |
| Depth 10 | 4 | 220.586 | 218.365 | -1.01% | -0.07% |
| Depth 10 | 8 | 196.410 | 193.086 | -1.69% | -0.03% |

normal suiteのnode差は並列root scheduleの揺らぎで、1Tは完全同値、固定深さの結果不一致は0/352だった。Standard 500 ms平均深度は4T・8Tで同値、1T・2Tで-0.125だったが、stability計算0件の通常探索に固有の動作差はない。

## Correctness and regression

- Java 11 target compilation with `-Xlint:all`: pass、警告0件
- Required regression test classes: 9 of 9 pass
- 終局score変換、eligibility境界、上下bound、色反転をunit testで固定
- 5〜9空き20局面でstability有効/無効の完全読みscore一致
- 12〜18空き48局面のscore checksum一致
- 16空き1/2/4/8Tのscore checksum一致
- Standard、Validation、Depth 10の最善手・評価値不一致0/352
- 固定深さtimeout、欠落手、runtime error: 0

探索結果を変えない正確な枝刈りであるため、100 msのEdax L7棋力ゲートは短縮した。

## Tournament-budget smoke test

10,000 ms/手、4T、Edax L7、8手openingを先後交換して2局実行した。

- Result: 1-0-1、score rate 50.00%
- Margins: -12、+36、average +12.00
- Average move time: 6,281.240 ms
- Average depth: 13.08
- Nodes/s: 2,155,928
- Exact endgame moves: 21
- Budget stops: 32
- Illegal moves, runtime errors, unfinished games: 0

## Environment

- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Java runtime: 21、compile target: 11
- OS: Windows 11 10.0
- Available processors: 20
- Standard/Depth 10 suite SHA-256: `18CD490619DC45974F8D968E05E457D1F2DFA2B1BD58DE10B5E2938CA074E9BA`
- Validation suite SHA-256: `31B2984F20F474CB1326134F525C78B87AEEDBD85C239BDC7B38B62534081A45`

終盤の最終class生成元は`74a7283`、通常CSVは`95d1867`である。両commit間の差はEndgameSearchBenchmarkの時間表示精度だけで、SearchEngineは同一である。正式結果生成前から未追跡artifactが存在したため通常CSVの`gitRevision`には`-dirty`が付くが、探索sourceの未commit差分はない。

## Artifacts

- `search-011-baseline-endgame-{12,14,16,18}-2026-07-21.txt`
- `search-011-stability-endgame-{12,14,16,18}-2026-07-21.txt`
- `search-011-stability-thread-consistency-2026-07-21.txt`
- `search-011-baseline-standard-2026-07-21.csv`
- `search-011-stability-standard-2026-07-21.csv`
- `search-011-baseline-validation-2026-07-21.csv`
- `search-011-stability-validation-2026-07-21.csv`
- `search-011-baseline-depth10-2026-07-21.csv`
- `search-011-stability-depth10-2026-07-21.csv`
- `search-011-stability-tournament-2026-07-21.txt`
