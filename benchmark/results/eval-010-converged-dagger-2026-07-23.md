# EVAL-010 converged Edax correction and DAgger

## Decision

`iteration 2 is the best candidate, held but not integrated`。

EVAL-006の80 epoch学習が早期phaseで未収束だったため、同じ4-phase Edax L9残差補正を160 epochまで学習し、候補自身の探索葉を再採点するDAgger型反復を3世代まで検証した。

反復2は現行との固定深さ6で56.5%、深さ8で56.0%、Edax L8で41.5%を得た。現行Edax L8の37.5%より4 point高いが、事前に固定したL11進級線の+5 pointへ届かず、対応差の95%区間も0をまたぐ。このため`data/evaluation-tables.bin`へ統合せず、L11正式試験へ進めない。

反復3は反復2に59.5%で勝ちながらEdax L8では26.5%へ崩壊した。自己・候補間対局に強い非推移性があるため、反復2より先へ累積補正しない。

## Iteration 1

現行探索葉28,656件をEdax L9教師へ近づけた。80 epoch版との違いは最大160 epoch、patience 15だけである。

| Match | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| vs current, depth 6 | 48-3-49 | 49.5% | -1.78 |
| vs Edax L8 | 38-5-57 | 40.5% | -8.50 |

80 epoch候補のEdax L8 31.0%から大きく改善した。test MSE削減も6.29%から8.46%へ伸び、学習収束不足が実戦劣化の一因だった。

## Iteration 2

反復1候補が訪れた28,656葉を新規にEdax L9で採点した。

- Edax nodes: 8,983,994,822
- Edax elapsed: 498.6 s
- Exact positions: 18,365
- Candidate SHA-256: `cbee474cbee0592e627ef631bb0535f39545b0d1c12e8147613cdcd8174ef805`

| Match | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| vs current, depth 6 | 56-1-43 | 56.5% | +2.33 |
| vs current, depth 8 | 53-6-41 | 56.0% | +1.47 |
| vs iteration 1, depth 6 | 51-4-45 | 53.0% | -0.56 |
| vs Edax L8 | 38-7-55 | 41.5% | -8.77 |

直接対局のbootstrap 95%区間は、深さ6 score`[48.0%,65.0%]`・石差`[-0.58,+5.30]`、深さ8 score`[49.5%,63.0%]`・石差`[-0.88,+3.87]`だった。

Edax L8の現行対応差はscore +4.0 point、95%区間`[-7.0,+14.5]`、平均石差+0.51、区間`[-2.74,+3.68]`だった。point estimateは最良だが、+5 point進級線と統計的確認の双方を満たさない。

## Iteration 3

反復2候補の新規28,656葉を再びEdax L9で採点し、同条件で三つ目の補正を加えた。

| Match | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| vs current, depth 6 | 49-4-47 | 51.0% | +1.10 |
| vs iteration 2, depth 6 | 57-5-38 | 59.5% | +1.98 |
| vs Edax L8 | 23-7-70 | 26.5% | -11.89 |

Edax L8の現行対応差は-11.0 point、95%区間`[-22.0,-0.5]`で、score劣化が統計的にも確認された。反復2への直接勝利は共通アンカー上の強さを意味しない。

## Interpretation

- 収束前の学習モデルを比較すると、phase数やモデル構造の効果を誤認する
- 候補自身の葉で再学習する反復は1回目には有効だったが、単調改善ではない
- 評価関数同士の対局は循環し得るため、現行への直接勝率だけで世代選択してはいけない
- 28k葉、同じ6-pattern、点ごとのscore回帰を累積する系列は反復2で飽和した
- 次は反復回数ではなく、兄弟手の相対順位を保つ方策用途、または教師数とパターン容量を同時に増やす必要がある

反復2は葉評価として統合しないが、現行を葉評価に保ったまま上位nodeの手順付けだけへ使う候補として再利用できる。これなら固定深さのminimax値を変えず、教師の順位情報だけを探索効率へ移せる。

## Verification

- Candidate Java load、CRC、色交換、盤面対称性: PASS
- Illegal moves and incomplete games: 0
- Runtime model changed: no
- Standard model SHA-256 remains `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`

## Reproducibility

- Branch: `codex/eval-010-converged-edax-correction`
- Parent: `a43e7ea`
- Iteration 1 SHA-256: `2fea9eefafd427fbe50b5587215a49e7fe8ec7178fcc6deada301cb302add4ee`
- Iteration 2 teacher TSV SHA-256: `1fee1af572c2875044ed631e6f5e3f4322643a821171700d12dc97e2a2eb6e21`
- Iteration 2 SHA-256: `cbee474cbee0592e627ef631bb0535f39545b0d1c12e8147613cdcd8174ef805`
- Iteration 3 teacher TSV SHA-256: `f7213c0df3c4b2fab68c7e55638267152b61c0c65d2f08c0da6e387cd678958d`
- Iteration 3 SHA-256: `8736504da373e9a098add4baf020798b697474af1698cf9400fd73ddd195ae4a`
