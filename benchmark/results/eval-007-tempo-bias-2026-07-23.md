# EVAL-007 phase tempo bias

## Decision

`rejected`。現行モデルは変更しない。

Edax L9教師に対する`Edax score - current static score`の平均が、train、validation、testの全分割でphaseごとに同じ正方向だった。この平均を「現在の色交換反対称モデルが表現できない手番テンポ」と仮定し、パターン表を一切変えずphase biasだけを加えた。

全量・半量とも現行との固定深さ6で50.0%だったが、全量候補はEdax L8で現行37.5%から35.5%へ低下し、平均石差も-9.28から-10.41へ悪化した。残差平均は実戦へ戻すべき局面価値ではなく、探索葉の選択分布に由来する条件付き偏りと判断する。

## Residual stability

単位は最終石差である。

| Split | Phase 0 | Phase 1 | Phase 2 | Phase 3 |
|---|---:|---:|---:|---:|
| Train | +0.99 | +2.65 | +3.69 | +2.41 |
| Validation | +0.91 | +2.58 | +4.16 | +1.97 |
| Test | +1.02 | +2.89 | +4.10 | +2.56 |

分割間で平均が安定していたため、train平均をJava scoreへ変換した`+99,+265,+369,+241`を全量候補へ加えた。半量候補は各値を0.5倍して丸めた。既存16表、score scale、phase境界は同一である。

## Direct gate

50 opening pair、固定深さ6、1 thread、MPC off、seed `20260808`。

| Candidate | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| Full bias | 49-2-49 | 50.0% | +0.12 |
| Half bias | 49-2-49 | 50.0% | +0.26 |

phase biasは葉の探索深度偶奇へ作用するが、この条件では現行方策を大きく崩さなかった。

## Edax gate

50 opening pair、100 ms、1 thread、Edax L8、MPC off、seed `20260721`。

| Model | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| Current | 36-3-61 | 37.5% | -9.28 |
| Full bias | 34-3-63 | 35.5% | -10.41 |

opening pair対応差はscore -2.0 point、bootstrap 95%区間`[-12.0,+8.0]`、平均石差-1.13、区間`[-4.29,+1.88]`だった。統計的に劣化を確定する規模ではないが、Edax L11目標へ進むための改善証拠がなく、point estimateも両指標で悪化したため停止条件を満たす。半量候補のEdax戦は実行しない。

## Interpretation

- 実探索葉は一様標本ではなく、alpha-beta cutoff、LMR、親局面、深度偶奇で選ばれた条件付き分布である
- 条件付き残差の平均がholdoutで安定していても、根からの方策改善を意味しない
- 色交換反対称性を外す根拠は得られなかった。次モデルでもこの制約を維持する
- phase数増加を検討する際も、ply別平均を直接biasとして学ぶのではなく、独立局面集合の兄弟手順位と実戦ゲートで確認する

## Reproducibility

- Branch: `codex/eval-007-tempo-bias`
- Parent: `a958b7c`
- Current SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- Full candidate SHA-256: `a6b3d69147a590f320b6228d55ec55447dcdf17c7da145426a7d23c44ef3e5ac`
- Half candidate SHA-256: `5f6a364b626102e217d2e5096066a917534f125063b3dbf9a83faebae9626afb`
- Bootstrap samples: 100,000

## Artifacts

- `training/adjust_model_bias.py`
- `training/model-eval-007-tempo-bias.json`
- `benchmark/results/eval-007-tempo-bias-*-2026-07-23.txt`
- `benchmark/results/eval-007-tempo-bias-full-edax-l8-analysis-2026-07-23.json`
