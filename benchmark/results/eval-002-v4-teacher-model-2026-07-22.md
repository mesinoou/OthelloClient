# EVAL-002 v4 teacher model evaluation

## Decision

`rejected`。`pattern-evaluation-v4-teacher-cuda-e40`は現行モデルを置き換えない。

Edax L7 100局で候補は47.5%、現行は53.5%となり、事前に定めた5 pointの非劣性幅を6 point下回った。平均石差と先後pair比較も同時に悪化したため、`benchmark/TEST_PLAN.md`の停止条件に従って10秒・4 thread試験は実行しなかった。

## Models and environment

- Code: `885c126a34e5eb3d7280b6d14cd3f037f8782bae`。Java sourceの未commit差分なし
- Current model: `data/evaluation-tables.bin`
- Current SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Candidate model: `.training/models/pattern-evaluation-v4-teacher-cuda-e40/evaluation-tables.bin`
- Candidate SHA-256: `89A941FD98B2095905C9D1E2E0AD5547A96A67D5C0142C70897012CC91DF8247`
- Candidate training manifest: `training/model-v4-teacher-cuda-e40.json`
- Java: Oracle JDK 21 LTS, target `--release 11`
- OS: Windows NT 10.0.26200.0
- Logical processors: 20

候補モデルは現行モデルとSHAが異なるため、現行モデル専用に校正されたMulti-ProbCutは安全機構により自動的に無効となる。これは候補をそのまま統合した場合の実際の構成である。

## Verification

- `javac --release 11 -encoding UTF-8 -Xlint:all -d .build *.java`: PASS
- Java regression tests: 12/12 PASS
- `LearnedEvaluatorTest <candidate>`: PASS
- Illegal moves, Edax GTP errors, incomplete games: 0

## Evaluation throughput

`EvaluationBenchmark <model> 3000000`を同じセッションで順番に実行した。

| Model | ns/eval | eval/s | Change from current |
|---|---:|---:|---:|
| Current | 522.6 | 1,913,331 | - |
| Candidate | 535.5 | 1,867,414 | 2.47% slower |

別processの短時間測定なので小差には起動時変動を含むが、候補に速度改善は見られない。

## Fixed-depth evaluator check

- Opponent: handcrafted evaluator
- 20 unique 8-ply openings, colors exchanged, 40 games
- Maximum depth 4, 1 thread, opening book off
- Opening seed `20260721`

| Model | W-D-L | Score | Average margin | Average depth | nodes/s |
|---|---:|---:|---:|---:|---:|
| Current | 37-1-2 | 93.75% | +29.90 | 3.86 | 1,450,374 |
| Candidate | 35-0-5 | 87.50% | +25.50 | 3.84 | 1,438,847 |
| Candidate change | - | -6.25 point | -4.40 | -0.02 | -0.79% |

Multi-ProbCutはdepth 4では作動しないため、この結果はMPC校正差だけでは説明できない。候補評価関数は同じ深さでも現行より悪い指し手を選んだ局面が増えた。

## Edax L7 preflight

- 10 unique 8-ply openings, colors exchanged, 20 games
- 100 ms/move, maximum depth 64, 1 thread
- Edax 4.6 level 7, opening book and pondering off

| Model | W-D-L | Score | Average margin | Average depth | nodes/s |
|---|---:|---:|---:|---:|---:|
| Current | 11-0-9 | 55.00% | -7.10 | 8.63 | 1,268,998 |
| Candidate | 9-2-9 | 50.00% | -6.15 | 8.46 | 1,221,907 |

候補はscoreがちょうど5 point低い一方で平均石差が改善していたため、preflightでは停止せず100局へ進めた。

## Edax L7 strength gate

- 50 unique 8-ply openings, colors exchanged, 100 games
- 100 ms/move, maximum depth 64, 1 thread
- Edax 4.6 level 7, opening book and pondering off
- Opening seed `20260721`

| Model | W-D-L | Score | Average margin | Average depth | nodes/s |
|---|---:|---:|---:|---:|---:|
| Current | 51-5-44 | 53.50% | -4.15 | 8.71 | 1,297,206 |
| Candidate | 44-7-49 | 47.50% | -5.68 | 8.65 | 1,260,873 |
| Candidate change | - | -6.00 point | -1.53 | -0.06 | -2.80% |

同一ゲームの対応比較:

| Unit | Metric | Improved | Tied | Worse |
|---|---|---:|---:|---:|
| 100 games | Result score | 16 | 59 | 25 |
| 100 games | Disc margin | 40 | 8 | 52 |
| 50 opening pairs | Result score | 11 | 24 | 15 |
| 50 opening pairs | Mean disc margin | 21 | 3 | 26 |

50 opening pairをclusterとして200,000回bootstrapした対応差の95%区間は、score差が`[-17.0, +4.5] point`、平均石差差が`[-5.70, +2.80]`であり、ともに0を含む。この100局だけで候補の劣等性が統計的に確定したとは言えないが、採用に必要な非劣性も示せていない。事前規定のscore幅を超え、平均石差とpair方向も悪化したため不採用とする。

## Interpretation

テストMSEの低下は探索中の指し手順位改善を保証しない。今回考えられる要因は次のとおりである。

- MSEは終局石差の絶対誤差を最適化するが、探索では兄弟局面間の順位とalpha-beta境界付近の誤差が重要である
- `teacher-filled`の理論値は主に24空き局面に存在し、全phaseを均等には強化しない
- 重複局面の実戦結果平均はノイズを減らす一方、勝敗境界に重要な少数局面を弱く扱う可能性がある
- Phase 1と3は40 epoch目が最良で、学習が収束する前に最大epochへ到達した
- 候補では未校正MPCが無効になるため探索効率が少し下がる。ただしdepth 4でも悪化したため主因とは断定できない

次の評価関数学習は別実験IDとし、少なくとも長い学習、勝敗または順位を意識したloss、phase別の教師値被覆、MPCなし固定深さ比較を先に検証する。新モデルが評価関数単体gateを通過した後にだけMPCを再校正する。

## Artifacts

- `eval-002-v4-teacher-{current,candidate}-handcrafted-depth4-2026-07-22.txt`
- `eval-002-v4-teacher-{current,candidate}-edax-l7-preflight-20-2026-07-22.txt`
- `eval-002-v4-teacher-{current,candidate}-edax-l7-100-2026-07-22.txt`
- `training/model-v4-teacher-cuda-e40.json`
