# Experiment ledger

探索・評価関数の改善を、一度に一つの仮説として比較するための台帳である。結果ファイルは原則として変更せず、再測定は新しい実験IDまたは新しい結果ファイルへ記録する。

## Rules

1. 評価済みコミットへ注釈付きベースラインタグを付ける。
2. 各実験ブランチは採用済みベースラインから作り、不採用実験を引き継がない。
3. 計測基盤、実装変更、測定結果を別コミットにする。
4. 乱数シード、モデルSHA-256、局面集合SHA-256、実行環境、対局条件を結果へ残す。
5. 正当性検証に失敗した実験は性能値にかかわらず不採用とする。
6. 採否決定前の実験ブランチはrebaseやforce pushで履歴を書き換えない。

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
| SEARCH-002 | planned | FIX-001統合後 | `codex/transposition-contention` | 置換表の競合を計測し、同期方式または配置を改善 | BENCH-001、固定深さ一致、Edax L7 | pending | 競合計測を先に実装 |

## Model baseline

- Runtime file: `data/evaluation-tables.bin`（Git追跡外）
- SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Training output: `.training/models/pattern-evaluation-v2-cuda-e80/evaluation-tables.bin`
