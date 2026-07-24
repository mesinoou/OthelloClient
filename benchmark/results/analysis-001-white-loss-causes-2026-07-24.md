# ANALYSIS-001 white-side loss causes

## Decision

EVAL-018 heldの白番低下は、開始局面の黒有利だけでは説明できない。
主因候補は、強い黒番Edaxの着手後に到達する応手分布での序中盤の手順位誤差である。
phase 3のWLD solver、held補正単独、既存定石単独を主因とは判定しない。

次の評価関数学習では、強い相手が黒番で生成した白手番局面を明示的に増やし、
phase 0-1の兄弟手WLD順位とregretを主目的にする。phase 2の石差regretは
WLDで勝敗が確定した局面を除外または低く重み付けし、phase 3は変更しない。

## Formal L11 color split

EVAL-018正式試験はEdax L11、10,000 ms、8 threads、定石off、
8-ply random opening、50 opening pairである。

| Learned color | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| Black | 28-2-20 | 58.0% | -7.80 |
| White | 16-1-33 | 33.0% | -12.70 |
| Combined | 44-3-53 | 45.5% | -10.25 |

Edax L11で50開始局面を採点すると、黒視点scoreは平均+1.32、
範囲-28から+27だった。開始局面scoreと最終石差の相関はAI黒番で
`+0.771`、AI白番で`-0.737`であり、開始局面の色有利は結果へ強く作用する。

| Initial Edax evaluation | Openings | AI black | AI white | Paired |
|---|---:|---:|---:|---:|
| Black advantage, score >= +4 | 23 | 82.61% | 6.52% | 44.57% |
| Balanced, score -3..+3 | 16 | 56.25% | 25.00% | 40.63% |
| White advantage, score <= -4 | 11 | 9.09% | 100.00% | 54.55% |

中立16局面でも31.25 pointの色差が残るため、開始局面biasだけではない。

## Move-level reproduction

正式試験で白が負けた中立12 openingだけを、held、Edax L11、
2,000 ms、8 threads、定石offで再試合した。これは敗局を条件に選んだ
診断集合であり、棋力推定には用いない。

- Held: 1-1-10、12.50%、平均石差-21.33、平均深度11.92
- Standard control: 1-0-11、8.33%、平均石差-17.00、平均深度11.88
- Heldの301着手から全2,407兄弟手を生成し、Edax L11で同一level採点
- 10敗局は全て最初の白手番でEdax best scoreが-3以上であり、
  初期時点で明確な負け局面ではなかった

Standardはscoreでheldを上回らず、石差だけ改善した。したがってheld補正を
外す、または白だけstandardへ替える根拠にはならない。

## Regret location

`regret`は全合法手のEdax L11子局面scoreの最大値と実着手値の差である。
4石以上をmajor regret、W/D/Lクラスが悪化した選択をoutcome downgradeとした。

| Phase | Loss decisions | Mean regret | Major regret | Outcome downgrade |
|---|---:|---:|---:|---:|
| 0, ply < 30 | 110 | 0.51 | 2 / 110 | 1 / 110 |
| 1, ply 30-39 | 50 | 0.62 | 4 / 50 | 2 / 50 |
| 2, ply 40-49 | 50 | 2.96 | 13 / 50 | 2 / 50 |
| 3, ply >= 50 | 40 | 1.00 | 4 / 40 | 0 / 40 |

10敗局中9局にmajor regretがあった。最初のmajor regretはphase 0が2局、
phase 1が3局、phase 2が4局で、phase 3は0局だった。最初のW/D/L
downgradeはphase 0が1局、phase 1が2局で、phase 2-3は0局だった。

phase 2の30/50着手とphase 3の40/40着手ではWLD searchが解を返した。
この領域の大きな石差regretの多くは同じ勝敗クラス内の差であり、
勝率目的の改善対象としてはphase 0-1より優先度が低い。phase 3に
outcome downgradeがないことは、現行終盤WLDを維持する根拠になる。

## L8 book control

held、Edax L8、100 ms、1 thread、既存定石あり、seed `20260907`を
100局再現した。黒白ともscore 34.0%で、白番固有のscore低下は再現せず、
白平均石差-10.64、黒-8.24だった。

白50局の1,368着手、全10,903兄弟手をEdax L8で採点した。定石62着手は
4石以上のmajor regretが0件だった一方、小さいW/D/L downgradeは存在し、
30白敗局中4局で最初のdowngradeが定石手だった。定石は正式L11白低下の
原因ではないが、別実験で深い順位付けを行う余地は残る。

## Implementation

`EvaluationMatchRunner`へ既定offのTSV trace、色選択、opening番号選択を追加した。
各着手について親子盤面、着手元、探索score、深度、時間、WLD状態を記録する。
`training.analyze_loss_trace`は全兄弟局面の生成、Edax採点入力、regret集計、
開始局面bias集計を行う。

既定引数では従来どおり全opening、両色、traceなしであり、対局動作は変わらない。

## Reproducibility

- Held model SHA-256:
  `d3b3b7b62848371f9fd3c950b7c7eb9abec79f9cdf09aa7e9c6b13647f628042`
- Formal L11 log SHA-256:
  `be7518531ad45c06188e4c083644dea796d5175d854f7e14037682a0dd3cff2e`
- L8 trace SHA-256:
  `25b4a07bc5a6ea4d57a133789f64e4c13d5b9ccb5e3e0a2296e02e0486b9ed07`
- Balanced L11 trace SHA-256:
  `e69ea9454ab1b590a829f627c51abf1b53cac31ecd80546f253e181b8f18c8f7`
- Balanced sibling Edax L11 TSV SHA-256:
  `6f9e3d804f10b67c0107f606c7a7e235ee4e3222a13e6af2275f0393ca313430`

Raw analysis files are generated under
`.training/analysis/white-loss-20260724/` and remain untracked.

## Limitations

- Move-level L11 reproduction used 2,000 ms rather than the formal 10,000 ms.
- The 12-opening diagnostic set was selected from prior white losses and is intentionally
  pessimistic; its score rate is not an unbiased strength estimate.
- Edax L11 sibling score is a strong finite search teacher, not a proof for every midgame
  position.
- Color weakness is better interpreted as a policy-distribution weakness against strong
  black play than as an absolute-color term missing from the evaluator.
