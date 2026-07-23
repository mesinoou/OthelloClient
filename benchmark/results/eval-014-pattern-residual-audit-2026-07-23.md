# EVAL-014 pattern residual audit

## Decision

`candidate patterns rejected before runtime integration`。

EVAL-010反復2モデルに残るEdax L9残差について、既存6-patternへ追加する局所形状をオフライン比較した。候補は短い対角線2種、corner2x5、edge8である。モデル容量を増やしただけで得られる再補正効果を分離するため、既存corner3x3を同じ小型MLPで再学習する対照も置いた。

edge8とcorner2x5は各探索葉分布で残差MSEを約3%改善したが、既存corner3x3対照も同程度以上に改善した。候補が対照を上回る分布は一貫せず、追加形状固有の信号とは判定できない。Java形式、実行時lookup、標準モデルは変更せず、固定深さ・Edax対局へ進めない。

## Setup

- Teacher: Edax 4.6 level 9
- Base evaluator: EVAL-010 iteration 2
- Test positions: each distribution 8,176
- Audit model: candidate pattern only, color-antisymmetric `2 * digits -> 16 -> 16 -> 1` MLP
- Epochs: 300
- Patience: 30
- Output anchor sweep: `0.1, 0.5, 1.0`
- L2: `0.0001`
- Device: CUDA
- Seed: `20260805`

候補ごと、phaseごとに独立モデルを学習し、validation lossでoutput anchorとepochを選択した。testは選択に使っていない。色交換反対称性とD4対称性は候補定義の単体試験で確認した。

## Results

値はEVAL-010反復2モデルに対する、候補補正後のtest MSE削減率である。

| Candidate | Current leaves | Iteration 1 leaves | Iteration 2 leaves |
|---|---:|---:|---:|
| diagonal5 | 0.298% | 0.592% | 0.290% |
| diagonal4 | 0.373% | 0.251% | 0.195% |
| corner2x5 | 3.876% | 2.678% | 3.256% |
| edge8 | 2.555% | 3.575% | 3.118% |
| existing corner3x3 control | 3.462% | 2.568% | 4.524% |

edge8から対照を引いた固有差は`-0.907, +1.007, -1.406 point`、corner2x5は`+0.414, +0.110, -1.268 point`だった。いずれも符号が分布で反転する。

単純な状態別lookup表も先に試した。corner2x5は疎な状態を記憶してtest MSEを6.901%悪化させたため、上表は全候補を同じ小型MLPへ揃えた結果である。

## Interpretation

- edge8は序盤と中盤前半で大きく改善する分布があり、既存edge+2X表を粗く共有する平滑化表現として理論的には妥当である
- ただし反復2の葉で対照を1.007 point上回った効果は、他の2分布で再現しなかった
- corner2x5も既存corner3x3より広い辺文脈を持つが、追加容量の効果と分離できなかった
- diagonal4/5の0.2〜0.6%は形式とlookup数を増やす根拠として小さい
- 既存パターン対照だけでも2.6〜4.5%改善するため、主な未利用信号は形状不足より探索葉分布への再補正にある
- EVAL-010反復3がholdout MSEを改善しながらEdax対局で崩壊した事実から、単一世代の点ごとの残差低下を採用条件にはできない

次はEVAL-010の複数探索方策から得た葉を混合し、分布別holdoutを同時に悪化させないrobust correctionを試す。追加パターンは、その混合基準に対する上積みが複数分布で再現した場合だけ再検討する。

## Verification

- Python tests: 30/30 PASS
- Java evaluator reproduction of dataset `static_score`: exact
- Candidate color swap involution: PASS
- Candidate D4 pattern count and symmetry: PASS
- Runtime source changes: none
- Runtime model changes: none
- Standard model SHA-256 remains `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`

## Reproducibility

- Branch: `codex/eval-014-pattern-residual-audit`
- Parent: `751e459`
- Current-leaf dataset metadata SHA-256: `b1fa0d407ed13d03ae327546e1e62b7559022ab015dc1a3868c336d1d7b9de6c`
- Iteration-1-leaf dataset metadata SHA-256: `2f46c2dce07780cc7492c0be8a4f33bba46afcd8a1a449757e0462743098ec8e`
- Iteration-2-leaf dataset metadata SHA-256: `e031d3d5184dae1b56c75517e3b390a091feb47fa5e1766f4be43f597f68b893`
- Audit result SHA-256: `4d3f1de3ad6969efd6f4af6ff2b43a99a03c91d74e0cf7529396d75a2f37b8b4`, `691ec9b05a96d1df26f129ab1c37bca9aceeb05786638f5ac4a80708ae392b29`, `4730d06ab3e7c9d2173528aef8ad837b0190e1f75c30c03e1f9d8a73d035f0b5`
