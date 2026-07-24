# EVAL-017 candidate MPC recalibration and L11 preflight

## Decision

EVAL-016のphase 3無効化候補を、MPC offのまま大会条件Edax L11正式100局へ進める。候補専用MPCは棄却し、標準runtimeと標準モデルは変更しない。

候補は未使用seedのEdax L11 10局で6勝1分3敗、score 65.0%、平均石差-2.80だった。opening pair bootstrap 95% score区間は`[50.0,85.0]%`で、preflight進級条件を満たした。ただし5 opening pairは最終目標の「統計的に互角以上」を証明する規模ではない。

## MPC Calibration

評価モデル変更後に標準モデル専用MPCを流用しないため、候補SHA `d3b3b7b62848371f9fd3c950b7c7eb9abec79f9cdf09aa7e9c6b13647f628042`で再校正した。

データの役割は次のように分離した。

| Role | Seeds | Samples per phase |
|---|---|---:|
| Regression train | 20260820-20260823 | 512 |
| Conformal calibration | 20260824 | 128 |
| Group selection validation | 20260825 | 128 |
| Final holdout | 20260826 | 128 |

各局面はMPC off、1 threadで深さ4/6/8/10を探索した。4 ply reductionを対象とし、validationとholdoutの片側false-cut率がともに1%以下の群だけを有効化した。

| Phase / depth | Slope | Intercept | High / low margin | Validation false high / low | Holdout false high / low | Enabled |
|---|---:|---:|---:|---:|---:|---|
| 0 / 8 | 0.956843 | -8.12 | 1946.9 / 2178.1 | 1 / 1 | 0 / 0 | yes |
| 0 / 10 | 0.978661 | +19.83 | 1753.6 / 2687.8 | 1 / 0 | 0 / 0 | yes |
| 1 / 8 | 1.007027 | +89.16 | 1608.3 / 1980.1 | 1 / 0 | 1 / 0 | yes |
| 1 / 10 | 1.003062 | +51.18 | 1214.0 / 1005.9 | 0 / 0 | 0 / 3 | no |
| 2 / 8 | 1.077452 | +35.28 | 1427.8 / 995.2 | 0 / 2 | 0 / 5 | no |
| 2 / 10 | 0.894307 | -42.45 | 1513.0 / 911.4 | 0 / 5 | 0 / 4 | no |

独立conformal calibrationの標本数が128なので99% finite-sample rankは最大誤差となる。marginは標準MPCより広く、安全性を優先した校正である。

## MPC Performance

候補MPC offとonを同一局面で比較した。

| Metric | MPC off | MPC on | Change |
|---|---:|---:|---:|
| Depth 10 nodes, 1T | 5,005,122 | 4,979,665 | -0.51% |
| Depth 10 nodes, 4T | 5,013,727 | 4,984,997 | -0.57% |
| 500 ms depth, 1T | 10.375 | 10.625 | +0.250 |
| 500 ms depth, 4T | 10.875 | 11.000 | +0.125 |
| 10 s depth, 1T | 13.500 | 13.625 | +0.125 |
| 10 s depth, 4T | 13.750 | 13.875 | +0.125 |

固定深さ10の最善手・評価値不一致は0/16だった。10秒で深さ改善を再現したためEdax L8へ進めた。

| MPC | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| off | 38-9-53 | 42.5% | -7.37 |
| on | 38-4-58 | 40.0% | -8.12 |

対応差はscore -2.5 point、95%区間`[-7.5,+2.5]`、平均石差-0.75、区間`[-2.47,+0.98]`だった。深さ改善が勝率へ変換されず、point estimateが両指標で悪化したため候補専用MPCを採用しない。

## Edax L11 Preflight

条件は1手10,000 ms、8 threads、Edax 4.6 level 11、8-ply opening、定石off、ponder off、MPC offである。

先後1組pilotは2勝0敗、両局+2石だった。同じopening seed `20260807`の現行モデルpilotは2敗だったが、1組なので正式推定へ混ぜない。

未使用seed `20260831`の5 opening pairは次の結果となった。

| Games | W-D-L | Score | Score 95% CI | Mean margin | Margin 95% CI |
|---:|---:|---:|---:|---:|---:|
| 10 | 6-1-3 | 65.0% | 50.0-85.0% | -2.80 | -11.2-+4.6 |

黒番は3勝0分2敗、白番は3勝1分1敗で大きな色偏りはない。opening pairの合計石差は`+6,+10,+10,-34,-20`で3組改善、2組悪化だった。

3敗はすべて-40石以下で、勝率が65%でも平均石差は負となった。大会順位が勝敗のみならscoreを優先できるが、未知openingに対する脆さは残る。平均完了深さ13.32はEdax level番号と直接比較できない。

## Formal Gate

正式評価条件を結果確認前に次のように固定する。

- Opening seed: `20260902`
- Opening pairs: 50、100 games
- 1手10,000 ms、8 threads、Edax level 11
- 8-ply opening、定石off、ponder off、MPC off
- 主判定: opening-pair bootstrap 95% score下限が50%以上
- 副判定: 色別score、pair合計石差、違法手、未完局、時間管理

100局の推定所要時間は約4時間25分である。正式gateを通過するまで候補モデルを`data/evaluation-tables.bin`へ統合しない。

## Verification

- Python tests: 33/33 PASS
- Java 11 compilation: PASS
- `SearchEngineTest`: PASS
- Candidate `LearnedEvaluatorTest`: PASS
- Calibration CSV: 7 seeds、各1,152 rows、stderr 0
- L8/L11 illegal moves and unfinished games: 0
- Standard runtime model and MPC source: unchanged
