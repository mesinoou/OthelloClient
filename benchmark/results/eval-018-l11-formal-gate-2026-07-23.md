# EVAL-018 Edax L11 formal gate

## Decision

EVAL-016で選んだphase 3無効化候補を、標準モデルへ即時統合せず、後続改善の基準候補として保持する。大会条件のEdax L11正式100局は44勝3分53敗、score 45.50%、平均石差-10.25で、事前に固定した「opening-pair bootstrap 95% score下限が50%以上」を満たさなかった。

95%区間はscore `[38.5, 52.5]%`、平均石差`[-12.57, -7.99]`だった。互角を区間内に含むだけでなく、石差は区間全体が負である。一方、Edax L8の同一100局では標準モデルよりscore +5.0 point、平均石差+1.91の点推定改善がある。改善の95%区間は0を含むため統計的には未確定だが、候補を棄却する根拠にもならない。

したがってEVAL-018の判定は「L11絶対gate不通過、ベースライン比改善候補としてheld」とする。候補を`data/evaluation-tables.bin`へ統合せず、標準モデル、標準MPC、標準opening bookを変更しない。

このheld判定は2026-07-24の再検討で確定した。測定値とL11 gate判定は変更せず、「gate不通過」と「候補そのものの棄却」を分離したものである。

## Conditions

| Setting | Value |
|---|---:|
| Candidate SHA-256 | `d3b3b7b62848371f9fd3c950b7c7eb9abec79f9cdf09aa7e9c6b13647f628042` |
| Edax | 4.6, level 11 |
| Time per AI move | 10,000 ms |
| Threads | 8 |
| Opening pairs / games | 50 / 100 |
| Opening plies / seed | 8 / `20260902` |
| Max depth | 64 |
| Candidate MPC | off |
| Candidate opening book | off |
| Candidate pondering | off |

試験条件と採用基準はEVAL-017で結果確認前に固定した。試験時間は16,070.263秒だった。

## Result

| Games | W-D-L | Score | Score 95% CI | Mean margin | Margin 95% CI |
|---:|---:|---:|---:|---:|---:|
| 100 | 44-3-53 | 45.50% | [38.5, 52.5]% | -10.25 | [-12.57, -7.99] |

bootstrapは50 opening pairを単位として50,000回、seed `20260902`で行った。

| Candidate color | Games | W-D-L | Score | Mean margin |
|---|---:|---:|---:|---:|
| Black | 50 | 28-2-20 | 58.0% | -7.80 |
| White | 50 | 16-1-33 | 33.0% | -12.70 |

黒番でも大敗の影響で平均石差は負だった。白番scoreは黒番より25 point低い。評価関数は色交換反対称なので単純な色定数では説明できず、後手側の戦型、探索葉分布、手順選択の誤差、または50組で残る標本変動を切り分ける必要がある。

## Integrity

- game行100件、game番号100種類
- opening 50種類、各opening 2局
- candidate黒50局、白50局
- illegal move、timeout、exception、unfinished gameは0
- opening book着手は0

生ログは`eval-018-no-p3-edax-l11-t10000-formal-100-2026-07-23.txt`、bootstrap出力は`eval-018-no-p3-edax-l11-t10000-formal-100-analysis-2026-07-23.json`へ保存した。

## Search Profile

| Metric | Value |
|---|---:|
| Candidate moves | 2,567 |
| Average move time | 6,247.617 ms |
| Average completed depth | 13.07 |
| Nodes | 37,131,800,777 |
| Nodes/s | 2,315,292 |
| Budget stops | 1,598 |
| Exact moves | 969 |
| WLD attempts / solutions | 967 / 967 |
| Edax average move time | 12.288 ms |

候補は平均13 plyを完了し、終盤WLDは全attemptを解いた。したがって今回の敗因を、単純な探索未完了やWLD失敗だけへ帰すことはできない。平均深度が高くてもEdax L11へのscoreへ変換できないという従来の観測が正式100局でも残った。

## Interpretation

5 opening pairのpreflightは65.0%だったが、未使用50 pairでは45.5%へ低下した。少数preflightの上振れと候補選択の影響が大きく、今後はL11進級判定へ使う前段のEdax L8ゲートを強める。

EVAL-010系の点ごとのEdax葉回帰はL8で標準モデルより改善したが、L11互角を証明できる水準には到達しなかった。phase 3無効化だけでは大敗を抑えきれず、pointwise MSEや平均探索深度を主指標にしたモデル選択の限界が確認された。ただしベースライン比の改善傾向は残るため、補正を撤去せず次構造の出発点とする。

## Next Experiment

EVAL-019ではEVAL-018 held候補の16 lookup表を高速な基礎評価として維持し、phase 1-2だけへ小型の量子化可能な反対称interaction residual headを追加する。phase 0はheld候補の補正を維持し、phase 3は標準値のままとする。

- 入力: 16表成分、mobility、frontier、disc、corner、corner move、stable、parityの差分と、mobility/frontier合計、ply
- 構造: hidden 16を第一候補とする整数ReLU head。hidden 0の線形controlを同じsplitで学習する
- 主目的: 同一親局面の兄弟手pairwise順位、Edax best-move top-1、選択手regret
- 補助目的: source-balanced Huber leaf loss。phase、色、生成方策別のworst-groupでcheckpointを選ぶ
- 分割: gameおよびopening単位でtrain/validation/testを分離し、兄弟局面のsplit漏洩を禁止する
- control: 標準モデル、EVAL-018 held候補、同じsplitで学習するhidden 0の線形head
- オフラインgate: hidden headが線形controlをtop-1とregretで上回り、held候補に対して黒白・phase別worst-groupを悪化させない
- 速度gate: Java量子化後の評価時間増加15%以内、10秒探索の平均完了深度低下0.125 ply以内
- 対局gate: held候補との固定深さ6/8をともに50%以上、Edax L8 100局でscore 45%以上かつ平均石差-6以上
- 最終gate: 上記通過時だけEdax L11の10 pair preflightを行い、その後に別seedの正式100局へ進める

L11正式試験は費用が高いため、EVAL-019では色別worst-groupとL8の進級条件を満たさない候補を早期停止する。
