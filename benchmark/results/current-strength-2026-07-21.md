# Current baseline strength verification

## Conclusion

`baseline/stability-cutoff-20260721`の現在版は、100 ms・1 thread・定石なしの同一opening suiteでEdax 4.6 level 6を上回り、level 7と互角圏、level 8を下回った。現時点の強さは**Edax level 7付近**と評価する。

手製評価版には20局で90.0%を記録し、学習評価の明確な優位も維持した。大会条件の10,000 ms・4 threadではEdax L7との先後2局を正常完走した。ただし2局は棋力推定には使用しない。

## Subject

- Git revision: `815b8be3948522dc520a9b4c5e466e78bbbfacd8`
- Tag: `baseline/stability-cutoff-20260721`
- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Java runtime: 21, compile target: 11
- OS: Windows NT 10.0.26200.0
- Available processors: 20
- Worktree was clean before measurement

Java 11 target compilation with `-Xlint:all` passed. Required regression tests passed 9 of 9 before the matches.

## Method

- Edax 4.6, levels 6, 7, and 8
- 50 deterministic random eight-ply openings per level
- Each opening played twice with colors exchanged
- Opening seed `20260721`
- Opening books disabled for both engines
- Current engine: 100 ms/move, maximum depth 64, one thread
- Tournament smoke: 10,000 ms/move, maximum depth 64, four threads
- Win/loss determined by final disc count

The intervals below are approximate Wilson 95% intervals with draws counted as half a point, matching the earlier learned-evaluator report.

## Results

| Opponent | Games | W-D-L | Score | Approx. 95% interval | Avg margin | Avg depth | Nodes/s |
|---|---:|---:|---:|---:|---:|---:|---:|
| Handcrafted | 20 | 18-0-2 | 90.0% | 69.9-97.2% | +23.55 | 8.31 | 1,372,050 |
| Edax L6 | 100 | 63-2-35 | 64.0% | 54.2-72.7% | +4.71 | 8.58 | 1,358,589 |
| Edax L7 | 100 | 51-4-45 | 53.0% | 43.3-62.5% | -0.02 | 8.66 | 1,383,367 |
| Edax L8 | 100 | 35-4-61 | 37.0% | 28.2-46.8% | -6.00 | 8.69 | 1,357,859 |

L6の区間は50%を上回り、L8の区間は50%を下回る。L7は50%を含み、平均石差もほぼ0なので、L7との優劣は確定できない。

## Color and opening-pair checks

| Opponent | Black score / margin | White score / margin | Pair margin positive-equal-negative |
|---|---:|---:|---:|
| Edax L6 | 63.0% / +6.16 | 65.0% / +3.26 | 33-3-14 |
| Edax L7 | 53.0% / +1.46 | 53.0% / -1.50 | 22-2-26 |
| Edax L8 | 37.0% / -6.44 | 37.0% / -5.56 | 12-2-36 |

L7のscore rateは両色53.0%で、今回の結果に大きな色偏りはない。pair合計石差は22勝26敗相当で、game score 53.0%だけから優位を主張すべきではない。

## Comparison with the LMR baseline

同じモデル、Edax L7、opening seed、100 ms条件の`SEARCH-006`採用直後と比較した。

| Version | W-D-L | Score | Avg margin | Avg depth | Nodes/s |
|---|---:|---:|---:|---:|---:|
| LMR baseline | 44-4-52 | 46.0% | -0.78 | 8.36 | 839,950 |
| Current | 51-4-45 | 53.0% | -0.02 | 8.66 | 1,383,367 |

Currentはnodes/sが64.70%、平均深さが0.30 ply増えた。game marginは44局改善、22局同値、34局悪化、opening pairでは26組改善、3組同値、21組悪化だった。速度向上は明確だが、100局の時限探索だけでは棋力向上の統計的確定には足りない。

## Tournament-budget smoke

Edax L7、10,000 ms/move、4 threads、同一openingの先後2局を実行した。

- Result: 1-0-1, score rate 50.0%
- Margins: -12, +36; average +12.00
- Average move time: 6,343.136 ms
- Average completed depth: 12.96
- Nodes/s: 1,904,406
- Budget stops: 32
- Exact endgame moves: 21
- Illegal moves, GTP errors, runtime errors, unfinished games: 0

これは時間管理、並列探索、完全読み移行の確認であり、棋力推定ではない。

## Statistical limits

- 100 msは大会の10秒条件を代表しない。
- Edax levelは等時間またはElo尺度ではない。
- random eight-ply openingsは再現可能だが、均衡した競技opening suiteではない。
- 100局でも数pointの差を確定するには不足する。
- 時限探索は同じ実装でもOS負荷とthread schedulingで着手が変化する。
- 大会条件の棋力評価には、未使用opening seedで最低20 paired gamesが必要である。

## Artifacts

- `current-strength-edax-l6-100-2026-07-21.txt`
- `current-strength-edax-l7-100-2026-07-21.txt`
- `current-strength-edax-l8-100-2026-07-21.txt`
- `current-strength-handcrafted-20-2026-07-21.txt`
- `current-strength-edax-l7-tournament-2-2026-07-21.txt`
