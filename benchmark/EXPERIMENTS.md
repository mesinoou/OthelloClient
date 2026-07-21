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
- `planned`: 実装前
- `running`: 実装または測定中
- `accepted`: 基準版への統合対象
- `rejected`: 結果を保存して統合しない

## Experiments

| ID | Status | Base | Branch | Single change | Validation / benchmark | Result | Decision |
|---|---|---|---|---|---|---|---|
| BASE-20260721 | frozen | `50313ca` | `feature/learned-evaluation` | CUDA e80学習評価を含む現行基準 | `benchmark/results/learned-e80-2026-07-21.md` | Edax L7 100局で50-1-49 | `baseline/learned-e80-20260721`として固定 |
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
| EVAL-001 | planned | latest accepted | `codex/chunked-pattern-index` | byte単位の3進寄与表で学習評価indexを構築 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-007後 |
| SEARCH-008 | planned | latest accepted | `codex/exact-last-n` | 残り1〜4手を専用終盤solverで探索 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | EVAL-001後 |
| SEARCH-009 | planned | latest accepted | `codex/two-way-tt` | 同じentry数で2-way bucket TTへ変更 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-008後 |
| SEARCH-010 | planned | latest accepted | `codex/enhanced-tt-cutoff` | 十分深い子局面TT boundによる安全なbeta cutoff | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-009後 |
| SEARCH-011 | planned | latest accepted | `codex/stability-cutoff` | edge stable discsによる終盤window縮小 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-010後 |
| RUNTIME-001 | planned | latest accepted | `codex/runtime-auto-sizing` | 接続前診断でthreadsとTT容量を自動選択 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | 正確な探索高速化後 |
| CLIENT-001 | planned | latest accepted | `codex/opponent-turn-pondering` | 相手手番探索で共有TTを予熱 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | RUNTIME-001後 |
| SEARCH-012 | planned | latest accepted | `codex/adaptive-lmr` | 深さ・手順・相手可動数に応じてLMRを2 ply化 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-011後 |
| SEARCH-013 | planned | latest accepted | `codex/multi-probcut` | holdout校正したMulti-ProbCut | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-012後 |
| SEARCH-014 | planned | latest accepted | `codex/interior-ybwc` | 浅いplyのYBWC split pointを追加 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-013後 |
| SEARCH-015 | planned | latest accepted | `codex/lazy-smp-helper` | 時間制限探索へ1本の決定的TT helperを追加 | `benchmark/ALGORITHM_ROADMAP.md` | 未実施 | SEARCH-014採用後 |

## Model baseline

- Runtime file: `data/evaluation-tables.bin`（Git追跡外）
- SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Training output: `.training/models/pattern-evaluation-v2-cuda-e80/evaluation-tables.bin`
