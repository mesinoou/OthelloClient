# SEARCH-013 calibrated Multi-ProbCut

## Decision

`accepted`。Adaptive LMRとの比較ではMulti-ProbCut (MPC) の効果が高かった。MPCは深さ10の4T nodeを5.90%削減し、10秒探索の4T平均完了深さを14.000から14.125へ改善した。Edax L7 100局でもスコア率は43.0%から44.0%、平均石差は-3.20から-2.12となり、非劣性条件を通過した。

ただし、選択的探索の既定L3 gateであるnode 10%削減または500 msで+0.50 plyには達していない。利用者からAdaptive LMRとMPCを直接比較して高い方を採用する指示を受けたため、L3単独判定を比較試験へ変更してL4とL5まで実施した。100局の差も統計的に有意ではなく、採用は大幅な棋力向上の主張ではなく、比較した2方式のうちMPCが一貫して上だったことに基づく。

## Change

- Base: `e5b1304` (`codex/algorithm-roadmap-v1`)
- Calibration sampler: `207d941`, `f848d1e`
- Runtime implementation: `2e76c18`
- Calibration selection fix: `a45b764`
- null-window nodeだけを対象とし、4 ply浅い探索を1回行う
- static evaluationがbeta以上ならfail-high、未満ならfail-low側だけを調べる
- MPC probe内の再帰MPC、18空き以下の完全読み、未校正phase/depthを除外する
- 学習モデルSHA-256が校正対象と一致しない場合はMPC全体を無効化する
- attempt、high/low cut、probe nodeを探索結果とCSVへ記録する

Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`

## Calibration

合法なrandom playoutからphaseごとに局面を生成し、1 thread、TTなし、MPCなしで深さ4、6、8、10を探索した。developmentはseed `20260721`から`20260724`の各phase 512局面、最終holdoutは未使用seed `20260725`の各phase 128局面である。線形回帰後、development residualの片側99% conformal marginを使用した。

| Phase / depth | Slope | Intercept | High / low margin | Holdout false high / low | Enabled |
|---|---:|---:|---:|---:|---|
| ply 20-29 / d8 | 0.944003 | -30.24 | 1876.8 / 1702.5 | 1 / 1 of 128 | yes |
| ply 20-29 / d10 | 0.979572 | +2.77 | 1592.0 / 1559.9 | 0 / 0 of 128 | yes |
| ply 30-39 / d8 | 1.004858 | +74.88 | 1462.1 / 1484.4 | 0 / 0 of 128 | yes |
| ply 30-39 / d10 | 1.008432 | +44.06 | 1 / 3 of 128 | no |
| ply 40-41 / d8 | 1.065543 | +21.67 | 2 / 1 of 128 | no |
| ply 40-41 / d10 | 0.942588 | -11.57 | 7 / 2 of 128 | no |

Development CSV SHA-256: `677CA94CB1C90CCBBD8FA7341B9888CCD27F67D58AEFF05B4DEF9C71CEA9CBDF`

Final holdout CSV SHA-256: `497B2D99DE7CDEF52EF75E7D95030F1974EA2916D1F4B94FDBE773DA7FC93B6F`

Parameter JSON SHA-256: `57C5BEC3ED81E2E2C31604A0BDA439A7CCD977B7F77D73B5D34D4B67DC017108`

2 ply reductionの予備版はprobe costが削減量を上回ったため、正式試験前に4 ply reductionへ変更した。後半phaseはholdout tail riskが1%を超えたため無効化した。

## Search performance

Windows 11、Java 21、20 logical processors、学習済み評価モデルを使用した。主判定は4 threads。壁時計は別JVM、逐次実行、OS負荷の影響を受けるためnodeと完了深さを優先する。

| Suite | Baseline nodes | MPC nodes | Node change | Baseline ms | MPC ms | MPC attempts / cuts | Probe nodes |
|---|---:|---:|---:|---:|---:|---:|---:|
| Standard depth 9 | 288,212 | 287,204 | -0.35% | 127.643 | 132.349 | 186 / 26 | 40,748 |
| Validation depth 8 | 82,069 | 82,073 | +0.00% | 39.311 | 36.765 | 0 / 0 | 0 |
| Depth 10 | 574,588 | 540,681 | -5.90% | 280.793 | 243.569 | 291 / 33 | 45,265 |
| Holdout depth 8 | 83,778 | 83,800 | +0.03% | 38.746 | 37.511 | 0 / 0 | 0 |

固定深さの最善手・評価値不一致は0/608。MPCは校正depth 8/10だけで動作するため、depth 8 suiteの局面phaseによってはattemptが0になる。Standard 500 msの4T平均完了深さは10.625から10.688で+0.063 plyだった。

未使用seed `20260727`の10秒探索では、1T平均深さが13.25から13.50、4Tが14.000から14.125となった。4Tの8局面中1局で1 ply深くなり、7局は同値だった。

## Adaptive LMR comparison

| Candidate | Standard d9 node | Depth 10 node | 500 ms 4T depth | 10 s 4T depth | Decision |
|---|---:|---:|---:|---:|---|
| Adaptive LMR | -0.08% | -5.80% | +0.000 | not run | rejected |
| Multi-ProbCut | -0.35% | -5.90% | +0.063 | +0.125 | accepted |

Adaptive LMRは通常局面で効果がなく、深さ10でもMPCを上回らなかった。MPCも効果は小さいが、大会時間まで方向が一貫した。

## Strength gate

Edax 4.6 level 7、100 ms/手、1 thread、8手random opening 50組を先後交換した。opening seedは未使用の`20260728`、両者のopening bookは無効。

| Version | W-D-L | Score rate | Avg margin | Avg depth | Nodes/s |
|---|---:|---:|---:|---:|---:|
| Baseline | 41-4-55 | 43.0% | -3.20 | 8.65 | 1,372,134 |
| MPC | 43-2-55 | 44.0% | -2.12 | 8.66 | 1,361,299 |

同一100局の石差はMPCが22局改善、64局同値、14局悪化した。先後2局をまとめた50 opening pairでは19組改善、21組同値、10組悪化。平均差は+1.08石だが、対応差の近似95%区間は-0.76から+2.92でゼロを含む。棋力向上は未確定だが、スコア率5 point以内、平均石差、pair比較の非劣性条件を満たす。

## Tournament gate

Edax L7、10,000 ms/手、4 threads、opening seed `20260729`の先後2局を完走した。

- Result: 2-0-0、平均石差+19.00
- 平均着手時間: 6,054.004 ms
- 平均完了深さ: 13.09
- Nodes/s: 1,725,340
- Budget stops: 32、完全読み手: 22
- 違法手、GTP error、時間超過、未完了局: 0

2局の勝敗は棋力推定には使用しない。

## Residual risk

MPCは確率的枝刈りなので、固定suiteで一致しても完全探索と同値ではない。MPC cut自身は現在nodeのTT entryへ直接保存しないが、その返値から祖先nodeのboundが保存される可能性はある。モデル変更時はSHA gateで停止する一方、同一モデルでも探索条件やphase分布が校正集合から外れる可能性がある。今後の探索変更後はEdax非劣性とholdout tailを再確認する。

## Verification

- Java 11 target compilation with `-Xlint:all`: pass
- Required regression tests: 9 of 9 pass
- Fixed-depth move / score mismatches: 0 of 608
- Candidate 1/2/4/8T consistency failures: 0
- Timeout、illegal move、runtime error、unfinished game: 0

## Artifacts

- `search-013-{baseline,mpc}-{standard,validation,depth10,holdout}-2026-07-21.csv`
- `search-013-{baseline,mpc}-tournament-performance-2026-07-21.csv`
- `search-013-{baseline,mpc}-edax-l7-{preflight-20,100}-2026-07-21.txt`
- `search-013-mpc-tournament-2-2026-07-21.txt`
- `search-013-mpc-calibration-{development,holdout}-2026-07-21.csv`
- `search-013-mpc-parameters-2026-07-21.json`
