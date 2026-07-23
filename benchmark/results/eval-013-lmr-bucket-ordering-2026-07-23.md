# EVAL-013 LMR-bucket-preserving learned ordering

## Decision

`rejected before match gate`。

EVAL-012ではlearned orderingがLMRの非削減手と削減手の所属を変え、探索効率を上げながらEdax棋力を下げた。EVAL-013では現行heuristicによるLMR groupを固定し、各group内だけEVAL-010反復2で再整列した。

結果差は大幅に減ったが、候補評価の実行費用を回収できるほどのnode削減は残らなかった。別seed・深さ10でも時間短縮が事前ゲートの5%未満だったため、直接対局、500 ms探索、Edax対局を省略した。

## Ordering contract

現行priorityで全合法手を整列した後、次の範囲だけを候補評価値で再整列する。

1. index 0: 固定
2. index 1〜3: group内再整列
3. index 4以降: group内再整列

これによりLMRの`moveIndex >= 4`境界をまたぐ移動を禁止する。TT手、前反復best、corner、mobilityが決めた先頭手とgroup所属は維持する。完全読み・WLD領域ではlearned orderingを使用しない。

## Fixed depth 9

64局面、1 thread、seed `20260728`。

| Minimum depth | Node change | Time change | Same best | Same score |
|---:|---:|---:|---:|---:|
| 4 | -4.17% | +7.25% | 63/64 | 62/64 |
| 5 | -2.99% | +1.24% | 63/64 | 62/64 |
| 7 | -3.75% | -0.72% | 64/64 | 63/64 |

閾値4/5は候補評価回数が多く、node削減より評価費用が大きかった。閾値7だけがわずかに時間を短縮した。

## Fixed depth 10

閾値7、32局面、1 thread、別seed `20260729`。

| Metric | Baseline | Candidate | Change |
|---|---:|---:|---:|
| Total nodes | 16,448,130 | 15,877,755 | -3.47% |
| Average elapsed | 371.330 ms | 355.134 ms | -4.36% |
| Same best | - | 31/32 | - |
| Same score | - | 30/32 | - |

速度差は改善方向だが、事前に定めた5%ゲートへ届かない。深さ9ではほぼ0だったため、異なる局面集合へ安定して一般化したとも判断できない。

## Interpretation

- EVAL-012の大きなnode削減の一部は、候補順位がLMRの削減対象を変更した結果だった
- LMR groupを固定すると、その棋力リスクと同時に大部分の速度利得も消えた
- 候補LearnedEvaluatorの追加費用は、group内だけの小さなcutoff改善には重い
- この候補を探索順序へ転用する系列はEVAL-011〜013で打ち切る

次の評価関数改善では、探索順序への転用ではなく、独立Edax教師を増やし、より表現力のある局所patternと値・順位のmulti-task学習をオフライン指標から再設計する必要がある。

## Verification

- Java compilation with `--release 11 -Xlint:all`: PASS
- Java test mains: 12 / 12 PASS
- index 0 and LMR bucket membership preservation: PASS
- Runtime model changed: no

## Reproducibility

- Branch: `codex/eval-013-lmr-bucket-ordering`
- Parent: `751e459`
- Runtime SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- Ordering SHA-256: `cbee474cbee0592e627ef631bb0535f39545b0d1c12e8147613cdcd8174ef805`
- Detailed CSV files use the `eval-013-` prefix in this directory
