# Edax L11 tournament-budget pilot

## Purpose

最終目標を「大会条件でEdax L11以上」と定める前に、現行モデルの1手10秒対局に必要な時間と到達深度を測った。先後2局だけなので、勝率推定や棋力の採否には使わない。

## Conditions

- Model SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- Opponent: Edax 4.6 level 11
- Search budget: 10,000 ms/move
- Search threads: 8
- Openings: 1 pair、8 ply、seed `20260807`
- Opening book: off
- Ponder: off
- Multi-ProbCut: on

## Result

| Games | W-D-L | Score | Mean margin | Elapsed |
|---:|---:|---:|---:|---:|
| 2 | 0-0-2 | 0.0% | -22.0 | 322.474 s |

現行AIは平均6,421 ms/手、平均完了深度12.92、約1.80M nodes/sだった。Edax L11は平均25.7 ms/手だった。現行AIの表示深度が11を上回っても2局とも敗れたため、Edax levelの数字と自AIの選択的探索深度を直接比較して棋力を判断してはならない。

同じ実測速度なら100局は約16,124秒、4時間29分を要する。正式対局は候補が低コストゲートを通過した後にだけ実行する。

## Interpretation

- 2局の0%は標本が小さすぎ、現行勝率の推定値として扱わない
- 時間管理、8スレッド、MPC、WLD移行は2局とも完走した
- 平均探索深度が十分でも負けた事実は、次の主対象を評価関数と手順品質に置く判断を支持する
- 最終目標の判定には、固定した50 opening pairの100局と対応bootstrap区間を使う
