# EVAL-016 combination and architecture audit

## Decision

EVAL-010補正のphase 3だけを無効化した候補を、Edax L11大会条件の正式試験へ進める。標準モデルへの統合はまだ行わない。

候補はEdax L8 100局で42.5%、平均石差-7.37だった。現行37.5%に対して+5.0 point、EVAL-010原型41.5%に対して+1.0 pointで、事前に定めたL11進級線へ到達した。ただし現行との対応差95%区間はscore `[-6.0,+16.0] point`、平均石差`[-1.62,+5.58]`であり、改善は統計的に確定していない。

Adaptive LMRとMPCの併用は、MPC単独から深さ10のnodeを約4.3%削減したが、500 ms・4 threadの平均深さは+0.0625 plyに留まったため棄却する。

## Combination Audit

過去候補のうち、作用が補完的で未検証だった次の組み合わせを優先した。

- EVAL-010補正の縮小: EVAL-005で有効だった過剰補正抑制を強いEVAL-010へ適用
- phase選択: EVAL-010の終盤補正だけを外し、序中盤の利得を残す
- EVAL-010とEVAL-015の混合: 実戦強度と方策分布robustnessの補完性を確認
- Adaptive LMRとMPC: 異なる選択的探索を併用した場合の追加利得を確認

potential mobilityと追加phaseは単独効果が小さく同じ終盤領域へ作用する。tempo biasは悪化し、upper-node/bucket orderingは既存順序付けと競合した。このため組み合わせ試験の優先対象から外した。

## Evaluation Blends

固定深さ対局は現行モデル、MPC off、1 thread、8-ply opening 20組、seed `20260810`で行った。

| Candidate | Depth 6 score | Margin | Depth 8 score | Margin |
|---|---:|---:|---:|---:|
| EVAL-010 correction 25% | 56.25% | +1.75 | 61.25% | +1.73 |
| EVAL-010 correction 50% | 60.00% | +2.05 | 48.75% | +0.85 |
| EVAL-010 correction 75% | 65.00% | +3.88 | 48.75% | -1.43 |
| EVAL-010 without phase 3 | 61.25% | +2.85 | 52.50% | +3.18 |
| EVAL-010 plus 25% EVAL-015 | 55.00% | +1.75 | 63.75% | +2.78 |

縮小率は深さに対して非単調だった。75%は深さ6で最良だが深さ8で崩れ、25%だけが両深さで安定した。EVAL-015混合は深さ8で最良となり、オフラインの補完性は見えた。

Edax L8の40局preflightは100 ms、1 thread、MPC off、seed `20260721`で行った。

| Candidate | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| EVAL-010 correction 25% | 13-2-25 | 35.00% | -12.08 |
| EVAL-010 without phase 3 | 13-7-20 | 41.25% | -5.18 |
| EVAL-010 plus 25% EVAL-015 | 11-9-20 | 38.75% | -8.55 |

固定深さの改善をEdax戦まで維持したのはphase 3無効化だけだった。

## Full Edax Gate

| Model | W-D-L | Score | Mean margin |
|---|---:|---:|---:|
| EVAL-010 without phase 3 | 38-9-53 | 42.50% | -7.37 |
| EVAL-010 iteration 2 | 38-7-55 | 41.50% | -8.77 |
| Standard | 36-3-61 | 37.50% | -9.28 |

phase 3無効化とEVAL-010原型の対応差はscore +1.0 point、95%区間`[-7.0,+9.5]`、平均石差+1.40、区間`[-1.17,+4.00]`だった。終盤補正が平均的に有害だった可能性は高まったが、100局では原型との差を確定できない。

候補SHA-256は`d3b3b7b62848371f9fd3c950b7c7eb9abec79f9cdf09aa7e9c6b13647f628042`。標準モデルは変更していない。

## Architecture Audit

現行runtimeは4 phase、16個の独立lookup表の和である。各表は任意の局所形状を表現できる一方、角や辺の価値をmobility、frontier、進行度、他パターンの状態に応じて変える相互作用を直接表現できない。

同一3方策dataset、同一split、source-balanced weighted Huber、最悪source checkpoint選択で次を比較した。

- Linear: 16表出力と7 global差分の再重み付け、全phase合計104 parameter
- Interaction: 上記23 signed入力と3 color-invariant context、32 hidden、全phase合計3,588 parameter
- 色交換した2 forwardの差を取り、出力の色反対称性を構造的に保証

標準モデル基準のtest MSE削減率は次のとおり。

| Source | Linear | Interaction | EVAL-015 full additive tables |
|---|---:|---:|---:|
| Current leaves | 13.58% | 17.79% | 17.16% |
| Iteration 1 leaves | 13.74% | 18.16% | 18.35% |
| Iteration 2 leaves | 13.38% | 17.71% | 17.58% |

Interactionは線形再重み付けを一貫して上回ったが、全lookup表を再学習したEVAL-015とはほぼ同値だった。標準モデルに対しては、加算構造の限界を確定する証拠ではない。

EVAL-010を基準にすると、Linearの削減率5.02〜5.24%に対してInteractionは10.62〜11.05%だった。位相別ではphase 0が約12.8%でLinearとの差は小さいが、Interactionはphase 1で約11.9%、phase 2で約9.9%、phase 3で約10.2%を削減した。EVAL-010後にも中終盤の条件付き信号が残ることは確認できた。

ただしEVAL-015では葉MSE改善が対局強度へ変換されず、今回もphase 3の加算補正を外すことで勝率が上がった。したがって、Interactionを同じpointwise MSEだけで学習してruntimeへ載せるのは妥当でない。

## Next Model Structure

次のモデル候補は16表を高速な基礎評価として維持し、その集約出力へ小型の量子化可能なinteraction residual headを加える。

- 入力: 16表成分、mobility/frontier/disc/corner/corner-move/stable/parity差、mobility/frontier合計、ply
- 構造: phase 1〜3のみ、16または32 hidden、整数ReLU系、反対称出力
- phase 0は効果差が小さいため既存表を維持
- phase 3は出力を強く制約し、初期候補では無効化との直接比較を必須にする
- 学習目的は兄弟手pairwise順位、Edax best-move top-1、選択手regretを主とし、葉MSEを補助へ下げる
- 同parameter・同データの加算型controlを置き、固定深さ6/8の両方を通過させる
- Java化前に1評価あたりの追加コストを測り、探索node低下を含む時間損失を制限する

## Search Combination

Adaptive 2-ply LMRと校正済みMPCを標準モデルで比較した。

| Metric | MPC | Adaptive LMR + MPC | Change |
|---|---:|---:|---:|
| Depth 10 nodes, 1T | 2,933,015 | 2,806,719 | -4.31% |
| Depth 10 nodes, 4T | 2,933,125 | 2,807,295 | -4.29% |
| Depth 10 time, 4T | 173.000 ms | 174.180 ms | +0.68% |
| 500 ms depth, 1T | 10.6875 | 11.0000 | +0.3125 |
| 500 ms depth, 4T | 10.9375 | 11.0000 | +0.0625 |

固定深さの最善手・評価値不一致は0/16だった。node削減はあるが、事前gateの+0.50 plyを満たさず、4 threadではほぼ消失した。MPCとLMRは同じ深部null-window枝へ作用し、削減効果と並列効率が重複していると判断する。

## Verification

- Python tests: 32/32 PASS
- Java 11 current-source regression tests: 12/12 PASS
- Java 11 temporary Adaptive LMR + MPC build: PASS
- `SearchEngineTest`: PASS
- Edax illegal moves / unfinished games: 0
- Standard runtime source changes: none
- Standard model changes: none
