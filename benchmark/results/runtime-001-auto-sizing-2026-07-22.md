# RUNTIME-001 environment profiling and auto-sizing

## Decision

`accepted`。接続前の短時間診断で総探索thread数を選び、heap上限からTransposition Table (TT) entry数を算出する。現PCでは1.094秒で8 threads、4,194,304 entriesを選択した。従来の固定4T、262,144 entriesに対し、10秒探索の平均完了深度は14.125から14.500へ改善し、最善手の不一致は0/8だった。

Linux本番機そのものでは未測定であるため、配備時に同じ環境診断と固定4T比較を再実行する。明示指定による即時上書きが可能であり、この未測定はWindows上の採用を妨げない。

## Change

- Base: `bfed61f` (`plan/runtime-ponder-20260722`)
- Experiment start: `aac15fd`
- Runtime implementation: `3271243`
- 第4位置引数に`auto`を追加し、省略時もautoとする
- `--tt auto|N`を追加し、CLI、`othello.tt.entries`、autoの優先順位とする
- TT予算をheapの1/16、8-128 MiB、かつheapの1/4以下に制限する
- 1/2/4/8 threadsの固定中盤局面診断を2秒以内に行う
- 診断用engine/TTと実戦用engine/TTを分離する
- 既存の固定設定benchmark APIと直接constructorは変更しない

Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`

## Correctness

Java 11 targetで警告込みコンパイル後、必須回帰テスト10件はすべてPASSした。RUNTIME-001実装後の固定深さ8局面、1/2/4/8 threadsをMPC基準版と比較し、最善手・評価値の不一致は0/32、候補内のconsistency failureも0だった。1T node数は8/8局面で完全一致した。並列node数は実行順序で変動するが、結果には影響しなかった。

CLI TT、system property TT、明示threadsの上書きも確認した。明示threads指定時の診断時間は0 msである。

## Environment matrix

Windows 11、Java 21上で`-XX:ActiveProcessorCount=1,2,4,8`と`-Xmx64m,256m,1g`の12組をsubprocess実行した。

| Heap | CPU range | Selected threads | TT entries | Diagnostic range |
|---|---:|---:|---:|---:|
| 64 MiB | 1-8 | 1-8 | 262,144 | 284-897 ms |
| 256 MiB | 1-8 | 1-8 | 262,144-524,288 | 288-1,063 ms |
| 1 GiB | 1-8 | 1-4 | 1,048,576-2,097,152 | 287-1,072 ms |

OOM、processor上限超過、8 threads超過、2秒超過、起動失敗は0件だった。候補差が5%未満なら少ないthreadを選ぶため、同じCPU数でも測定負荷に応じて保守的な構成になる。

## Performance

学習済み評価、seed `20260721`の8局面を使用した。Autoは現PCで選択された8T、4,194,304 entries、baselineは固定4T、262,144 entriesである。

| Time | Baseline depth | Auto depth | Change | Auto nodes/s | Better/equal/worse positions |
|---|---:|---:|---:|---:|---:|
| 500 ms | 10.875 | 10.938 | +0.063 | 2,483,483 | 1 / 15 / 0 |
| 10,000 ms | 14.125 | 14.500 | +0.375 | 2,580,976 | 3 / 5 / 0 |

500 msは各局面2反復、10秒は各局面1反復である。10秒条件では8局面すべてで最善手が固定4Tと一致した。自動設定の目的は計算機ごとの資源利用改善であり、探索アルゴリズムや評価値は変更していない。

## Residual risk

短時間診断はCPU負荷、thermal state、JIT状態の影響を受ける。5% tie ruleで過剰threadを抑えるが、長時間連続対局のclock低下までは診断できない。Linux本番機では起動表示を保存し、固定4Tとの10秒比較と実サーバ2局を再確認する。TT entry概算は現在のprimitive array構成に依存するため、配列構成変更時は31 bytes/entryの見積もりを更新する。

## Verification

- Java 11 target compilation with `-Xlint:all`: pass
- Required regression tests: 10 of 10 pass
- Fixed-depth move / score mismatches: 0 of 32
- Candidate consistency failures: 0
- Environment matrix: 12 of 12 pass
- OOM、timeout、invalid configuration: 0

## Artifacts

- `runtime-001-fixed-correctness-2026-07-22.csv`
- `runtime-001-environment-matrix-2026-07-22.csv`
- `runtime-001-{baseline,auto}-500ms-2026-07-22.csv`
- `runtime-001-{baseline,auto}-10s-2026-07-22.csv`
