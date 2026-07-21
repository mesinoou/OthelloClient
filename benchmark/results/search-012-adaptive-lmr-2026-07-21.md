# SEARCH-012 adaptive late move reduction

## Decision

`rejected`。現行LMRのうち、depth 8以上・9番目以降・着手後の相手可動数6以上を2 ply削減した。深さ10では4T nodeを5.80%削減したが、Standard深さ9では0.08%に留まり、500 ms平均深さも改善しなかった。選択探索の事前採用条件であるnode 10%削減または+0.50 plyを満たさないため、Edax棋力試験へ進まず実験ブランチへ保存する。

## Change

- Base: `e5b1304` (`codex/algorithm-roadmap-v1`)
- Initial implementation: `dc71fbb`
- Hot-path mobility fix: `0907725`
- 通常LMR対象は従来どおり1 ply削減
- depth 8以上、move index 8以上、相手可動数6以上だけ2 ply削減
- 隅、相手pass、PV node、空き18以下は従来どおり除外
- reduced searchがalphaを超えた場合は全深度で再探索
- TT best move除外と未確認UPPER bound保存抑止を維持
- 2 ply試行数と再探索数を個別に記録

初期版は全LMR候補で相手可動数を数え、2 ply削減が発生しないdepth 8 suiteでも4〜7%遅くなった。最終版ではpass判定後、depthとmove indexが2 ply条件を満たす場合だけbit countする。

## Four-thread results

| Suite | Baseline nodes | Adaptive nodes | Node change | Baseline ms | Adaptive ms | Time change | 2-ply attempts | Retry rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Standard depth 9 | 288,212 | 287,996 | -0.08% | 127.643 | 134.598 | +5.45% | 58 | 3.45% |
| Validation depth 8 | 82,069 | 82,058 | -0.01% | 39.311 | 40.223 | +2.32% | 0 | 0.00% |
| Depth 10 | 574,588 | 541,239 | -5.80% | 280.793 | 280.382 | -0.15% | 445 | 0.90% |
| Holdout depth 8 | 69,859 | 69,854 | -0.01% | 35.526 | 38.694 | +8.92% | 0 | 0.00% |

Standard 500 msの平均完了深さは4Tで10.625から10.625の同値、8Tでは10.750から10.688へ低下した。timingはbaselineを先にcandidateを後に測定しており、温度・JIT・OS負荷を含むため、小差の採否根拠には使わない。node削減と到達深さだけでも性能gate未達である。

## Correctness

- Java 11 target compilation with `-Xlint:all`: pass
- Required regression tests: 9 of 9 pass
- Baseline/candidate fixed-depth move mismatches: 0 of 608
- Baseline/candidate fixed-depth score mismatches: 0 of 608
- Candidate 1/2/4/8T consistency failures: 0
- Fixed-depth timeout, illegal move, runtime error: 0
- Maximum measured 2-ply retry rate: 3.45%

選択探索なので一般にはbaselineとの一致を保証しない。今回のsuiteでは2 ply削減した手がroot結果を変えなかっただけであり、正確な探索への昇格を意味しない。

## Strength gate

事前に定めたL3性能gateで停止したため、Edax予備20局、100局、10秒条件は実行していない。低効果の候補へ対局結果を後付けして採用判断を変えない。

## Artifacts

- `search-012-baseline-standard-2026-07-21.csv`
- `search-012-adaptive-standard-2026-07-21.csv`
- `search-012-baseline-validation-2026-07-21.csv`
- `search-012-adaptive-validation-2026-07-21.csv`
- `search-012-baseline-depth10-2026-07-21.csv`
- `search-012-adaptive-depth10-2026-07-21.csv`
- `search-012-baseline-holdout-2026-07-21.csv`
- `search-012-adaptive-holdout-2026-07-21.csv`
