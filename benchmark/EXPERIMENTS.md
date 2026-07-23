# Experiment ledger

探索・評価関数の改善を、一度に一つの仮説として比較するための台帳である。結果ファイルは原則として変更せず、再測定は新しい実験IDまたは新しい結果ファイルへ記録する。

## Rules

1. 評価済みコミットへ注釈付きベースラインタグを付ける。
2. 各実験ブランチは採用済みベースラインから作り、不採用実験を引き継がない。
3. 計測基盤、実装変更、測定結果を別コミットにする。
4. 乱数シード、モデルSHA-256、局面集合SHA-256、実行環境、対局条件を結果へ残す。
5. 正当性検証に失敗した実験は性能値にかかわらず不採用とする。
6. 採否決定前の実験ブランチはrebaseやforce pushで履歴を書き換えない。
7. テスト範囲と停止条件は`benchmark/TEST_PLAN.md`に従い、例外は測定前に実験行へ記録する。

## Status

- `frozen`: 比較基準として固定済み
- `infrastructure`: 計測基盤であり、棋力変更を含まない
- `specified`: 実装前の契約、既定値、試験条件を固定済み
- `planned`: 実装前
- `running`: 実装または測定中
- `accepted`: 基準版への統合対象
- `rejected`: 結果を保存して統合しない
- `measurement`: 実装を変更せず、固定した基準版の性能を再測定

## Experiments

| ID | Status | Base | Branch | Single change | Validation / benchmark | Result | Decision |
|---|---|---|---|---|---|---|---|
| RELEASE-001 | frozen | `baseline/opponent-turn-pondering-20260722` | `codex/release-v1.0.0` | 採用済み構成をv1.0.0として最終検証 | `benchmark/results/release-v1.0.0-final-verification-2026-07-22.md` | 回帰11/11、固定深さ736/736整合、Edax L6 49.5%・L7 36.5%・L8 36.0%、10秒4Tと実サーバ8秒2局完走 | `v1.0.0`として固定 |
| BASE-20260721 | frozen | `50313ca` | `feature/learned-evaluation` | CUDA e80学習評価を含む現行基準 | `benchmark/results/learned-e80-2026-07-21.md` | Edax L7 100局で50-1-49 | `baseline/learned-e80-20260721`として固定 |
| BENCH-002 | measurement | `baseline/stability-cutoff-20260721` | `codex/algorithm-roadmap-v1` | 現在版の対Edax強度をL6/L7/L8各100局で再測定 | `benchmark/results/current-strength-2026-07-21.md` | L6 64.0%、L7 53.0%、L8 37.0%、大会条件2局完走 | 現在の強さをEdax L7付近と判定 |
| BENCH-003 | measurement | `9a41d2a` | `codex/bench-edax-l11-tournament` | Edax L11・10秒条件の費用と現行到達深度を先後2局で測定 | `benchmark/results/bench-edax-l11-tournament-pilot-2026-07-23.md` | 0-0-2、平均石差-22、平均深度12.92、322.5秒。100局推定4時間29分 | 棋力推定には使わず、L11最終ゲートと費用見積もりを固定 |
| BENCH-001 | infrastructure | `baseline/learned-e80-20260721` | `codex/parallel-benchmark-v1` | 並列探索の再現可能な固定深さ・固定時間計測を追加 | `benchmark/results/parallel-search-v1-2026-07-21.md` | 固定深さ128結果で不一致0件、4T 1.68倍、8T 1.89倍 | 計測基盤として採用、探索動作は変更なし |
| SEARCH-001 | rejected | `benchmark/parallel-v1-20260721` | `codex/root-parallel-worker-loop` | 指し手単位Futureを固定ワーカーループへ変更し、メインスレッドも参加 | `benchmark/results/search-001-root-worker-loop-2026-07-21.md` | 2Tは26.7%短縮、4Tは2.0%短縮、8Tは10.8%悪化 | 大会用4Tの改善が小さく8T退行のため不採用 |
| FIX-001 | accepted | `benchmark/parallel-v1-20260721` | `codex/root-parallel-research-fix` | 並行中の共有alpha更新後もfail-high手を再探索する | `benchmark/results/fix-001-parallel-research-2026-07-21.md` | 固定深さ448結果で不一致0件、4Tノード約4.9%増 | 正当性修正として採用 |
| SEARCH-002 | rejected | `benchmark/parallel-v1.1-20260721` | `codex/transposition-contention` | 置換表のモニタ競合を計測し、同期方式変更の必要性を判定 | `benchmark/results/search-002-transposition-contention-2026-07-21.md` | 深さ10の待機時間は4Tで0.28%、8Tで0.07% | ロック変更は不採用、競合計測機能だけ採用 |
| SEARCH-003 | rejected | `benchmark/parallel-v2-20260721` | `codex/history-ordering` | scaled history heuristicでbeta cutoff手を優先 | `benchmark/results/search-003-history-ordering-2026-07-21.md` | 別seedの4Tでノード1.84%、時間0.96%短縮、標準500ms深さは微減 | 効果が小さいため不採用 |
| SEARCH-004 | rejected | `benchmark/parallel-v2-20260721` | `codex/killer-ordering` | killer heuristicだけを追加して手順整列を強化 | `benchmark/results/search-004-killer-ordering-2026-07-21.md` | 別seedの4Tでノード0.58%増、標準500 ms深さは9.69から9.56へ低下 | 性能ゲート未通過のため不採用 |
| SEARCH-005 | rejected | `benchmark/parallel-v2-20260721` | `codex/aspiration-window` | 反復深化の前回評価値を中心にaspiration windowを適用 | `benchmark/results/search-005-aspiration-window-2026-07-21.md` | 標準4Tでノード4.46%減、別seedで1.19%減、500 ms深さは微減 | 効果が小さく局面依存のため不採用 |
| SEARCH-006 | accepted | `benchmark/parallel-v2-20260721` | `codex/late-move-reduction` | 後順位の非PV手を浅く探索し、fail-high時だけ全深度で再探索 | `benchmark/results/search-006-late-move-reduction-2026-07-21.md` | 4T深さ9でノード22.04%減、500 ms深さ+0.62、同時点Edax基準比+1.5pt | `baseline/lmr-20260721`として統合 |
| SEARCH-007 | accepted | `9855a1e` | `codex/shallow-tt-gating` | depth 2未満のTT probe/storeを省略 | `benchmark/results/search-007-shallow-tt-gating-2026-07-21.md` | 4T 500 ms深さ+0.25、Validation時間-3.92%、Depth 10時間-7.81%、不一致0件 | 統合対象 |
| SEARCH-016 | accepted | `baseline/shallow-tt-20260721` | `codex/specialized-leaf-search` | 通常探索depth 0/1を専用関数へ分岐 | `benchmark/results/search-016-specialized-leaf-search-2026-07-21.md` | 4T固定深さ-29.56%、500 ms深さ+0.44、Validation-31.64%、不一致0件 | 統合対象 |
| EVAL-001 | rejected | `baseline/specialized-leaf-20260721` | `codex/chunked-pattern-index` | byte単位の3進寄与表で学習評価indexを構築 | [experiment report](https://github.com/mesinoou/OthelloClient/blob/codex/chunked-pattern-index/benchmark/results/eval-001-chunked-pattern-index-2026-07-21.md) | 評価中央値+59.26%、4T固定時間+15.65%、評価値不一致0件 | 実験ブランチへ保存し不採用 |
| EVAL-002 | rejected | `885c126` | local working tree | v4コーパスとWTHOR理論値で6-pattern評価を再学習 | `benchmark/results/eval-002-v4-teacher-model-2026-07-22.md` | 固定深さscore -6.25pt・平均石差-4.40、Edax L7 100局47.5%対現行53.5% | 非劣性幅5ptを超え、平均石差とpair比較も悪化したためモデル統合を中止 |
| EVAL-003 | rejected | `f08d7f7` | `codex/eval-003-objective` | 6-pattern構造を固定し、石差MSEをWLD・石差hybrid lossへ変更 | `benchmark/results/eval-003-hybrid-objective-2026-07-22.md` | Hybridは固定深さでMSE比+2.5pt、Edax preflight 52.5%だが100局は47.0%で現行55.5%から-8.5pt | 学習機能は採用、モデルは非劣性幅5ptを超えたため統合しない |
| EVAL-004 | rejected | `ac0ea15` | `codex/eval-004-ranking` | 深さ8/10の全合法手順位をpairwise・listwise lossで既存6-pattern評価へ蒸留 | `benchmark/results/eval-004-move-ranking-2026-07-23.md` | test順位は改善したがHybrid比100局でe100は深さ6 37.5%・深さ8 34.5%、e10も深さ6 41.5% | 順位実験基盤は採用、静的順位とminimaxが不整合なためモデルは統合しない |
| EVAL-005 | rejected | `4f9acda` | `codex/eval-005-search-leaf` | 現行探索の実葉を追加4手探索へ整合させるruntime table補正 | `benchmark/results/eval-005-search-leaf-correction-2026-07-23.md` | test残差MSEをphase別3.3〜10.6%削減し現行との固定深さ100局はd4 53.5%・d6 52.5%・d8 54.0%、Edax L8は全量31.5%・50%補正34.5%対現行37.5% | 葉収集・補正・table合成・対応bootstrap基盤は採用、モデルはEdax改善を示さず統合しない |
| EVAL-006 | rejected | `df97c14` | `codex/eval-006-edax-teacher` | 現行探索の実葉を独立したEdax L9 scoreへ近づけ、候補自身の葉でDAgger型再学習 | `benchmark/results/eval-006-edax-teacher-2026-07-23.md` | L9/L11教師相関0.995、反復2は現行との深さ6で50.0%、Edax L8 38.5%対現行37.5%、対応差95% CI `[-10.5,+12.5]pt` | Edax採点・比較・反復学習基盤は採用、モデル差は統計的に不十分なため統合しない |
| EVAL-007 | rejected | `a958b7c` | `codex/eval-007-tempo-bias` | Edax教師へのphase別平均残差を手番側biasとして現行表へ加算 | `benchmark/results/eval-007-tempo-bias-2026-07-23.md` | 現行との深さ6は50.0%、Edax L8は35.5%・石差-10.41対現行37.5%・-9.28 | 残差平均は探索葉の選択biasであり局面価値ではないため不採用 |
| EVAL-008 | rejected | `f7f555e` | `codex/eval-008-potential-mobility` | 相手石隣接空き数を色交換反対称なphase別65x65表で補正 | `benchmark/results/eval-008-potential-mobility-2026-07-23.md` | test MSE改善はphase 0〜3で-0.006%、0.002%、0.102%、1.197%。改善は完全読み領域へ偏った | runtime実装前のオフラインゲートで停止 |
| EVAL-009 | rejected | `63582a8` | `codex/eval-009-phase-granularity` | Edax残差補正を4 phaseから8 phaseへ増やし、同一160 epochで比較 | `benchmark/results/eval-009-phase-granularity-2026-07-23.md` | MSE削減は4 phase 8.459%、8 phase 8.602%、差0.142pt。MAE差0.44% | 容量・形式2倍に見合わずruntime実装前に停止、analysis基盤は保存 |
| EVAL-010 | held | `a43e7ea` | `codex/eval-010-converged-edax-correction` | 4-phase補正を160 epochへ収束させ、候補葉をEdax再採点するDAggerを3反復 | `benchmark/results/eval-010-converged-dagger-2026-07-23.md` | 反復2は現行へd6 56.5%・d8 56.0%、Edax L8 41.5%対37.5%。反復3は候補間59.5%でもEdax 26.5%へ崩壊 | 反復2を最良候補として保存するが+5pt進級線未達で葉評価へ統合せず、反復3は不採用 |
| EVAL-015 | rejected | `751e459` | `codex/eval-015-robust-policy-mixture` | 3世代の探索葉を標準モデルへrebaseし、source-balanced・worst-source選択で単一補正を学習 | `benchmark/results/eval-015-robust-policy-mixture-2026-07-23.md` | 3分布の量子化test MSEを17.16〜18.35%削減。現行へd6 55.5%、d8 51.0%だがEdax L8は36.0%対現行37.5% | 混合学習基盤は保存、点ごとのEdax葉回帰は対局改善へ変換されずモデル不採用 |
| SEARCH-008 | accepted | `baseline/specialized-leaf-20260721` | `codex/exact-last-n` | 残り1〜4手を専用終盤solverで探索 | `benchmark/results/search-008-exact-last-n-2026-07-21.md` | 4T完全読み時間を12〜18空きで49〜62%短縮、不一致0/352 | 統合対象 |
| SEARCH-009 | rejected | `baseline/exact-last-n-20260721` | `codex/two-way-tt` | 同じentry数で2-way bucket TTへ変更 | [experiment report](https://github.com/mesinoou/OthelloClient/blob/codex/two-way-tt/benchmark/results/search-009-two-way-tt-2026-07-21.md) | 4T node削減0.07〜0.57%、500 ms深度同値、不一致0/352 | 性能ゲート未達のため実験ブランチへ保存 |
| SEARCH-010 | rejected | `baseline/exact-last-n-20260721` | `codex/enhanced-tt-cutoff` | 十分深い子局面TT boundによる安全なbeta cutoff | [experiment report](https://github.com/mesinoou/OthelloClient/blob/codex/enhanced-tt-cutoff/benchmark/results/search-010-enhanced-tt-cutoff-2026-07-21.md) | 4T node削減0.32〜1.54%、500 ms深度同値、不一致0/352 | 性能ゲート未達のため実験ブランチへ保存 |
| SEARCH-011 | accepted | `baseline/exact-last-n-20260721` | `codex/stability-cutoff` | edge stable discsによる終盤window縮小 | `benchmark/results/search-011-stability-cutoff-2026-07-21.md` | 12〜18空き4T平均時間-8.35%、checksum一致、通常不一致0/352 | `baseline/stability-cutoff-20260721`として統合 |
| RUNTIME-001 | accepted | `baseline/multi-probcut-20260721` | `codex/runtime-auto-sizing` | 接続前診断でthreadsとTT容量だけを自動選択 | `benchmark/results/runtime-001-auto-sizing-2026-07-22.md` | 現PCで8T/4194304 entriesを1.094秒で選択、固定4T比10秒深度+0.375、固定探索不一致0/32 | `baseline/runtime-auto-sizing-20260722`として統合対象 |
| CLIENT-001 | accepted | `baseline/runtime-auto-sizing-20260722` | `codex/opponent-turn-pondering` | 相手手番探索で共有TTだけを予熱 | `benchmark/results/client-001-opponent-turn-pondering-2026-07-22.md` | 100局でscore -0.5 point、平均石差+1.25、自手深度+0.19、実サーバ2局でstop p95最大5.03 ms・誤PUT 0 | 既定offのまま`baseline/opponent-turn-pondering-20260722`として統合対象 |
| SEARCH-012 | rejected | `e5b1304` | `codex/adaptive-lmr` | 深さ・手順・相手可動数に応じてLMRを2 ply化 | `benchmark/results/search-012-adaptive-lmr-2026-07-21.md` | 深さ10 node -5.80%、標準depth 9 -0.08%、500 ms深さ同値 | 効果不足のため実験ブランチへ保存 |
| SEARCH-013 | accepted | `e5b1304` | `codex/multi-probcut` | holdout校正した4 ply Multi-ProbCut | `benchmark/results/search-013-multi-probcut-2026-07-21.md` | 深さ10 node -5.90%、10秒4T +0.125 ply、Edax L7 44.0%で非劣性 | 比較した2方式のうち効果が高いため統合対象 |
| SEARCH-017 | accepted | `v1.0.0` | `codex/wld-endgame-search` | 終盤の石差完全読みを勝敗引分の3値WLD証明へ変更 | `benchmark/results/search-017-wld-endgame-search-2026-07-22.md` | 20空き12/12完読、比較可能54局面不一致0、Edax L7 46.5%対44.5%、実サーバ2局完走 | `baseline/wld-endgame-20260722`として統合対象 |
| SEARCH-014 | planned | latest accepted | `codex/interior-ybwc` | 浅いplyのYBWC split pointを追加 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-013後 |
| SEARCH-015 | planned | latest accepted | `codex/lazy-smp-helper` | 時間制限探索へ1本の決定的TT helperを追加 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-014採用後 |

## Model baseline

- Runtime file: `data/evaluation-tables.bin`（Git追跡外）
- SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Training output: `.training/models/pattern-evaluation-v2-cuda-e80/evaluation-tables.bin`
