# OthelloClient v1.0.0 final verification

## Decision

現在の採用済み構成を`v1.0.0`として固定する。Java 11対象ビルド、回帰11件、学習パイプライン11件、実モデル、固定深さ736結果、時限64結果、Edax 300局、10秒4T先後2局、実サーバ8秒2局を完了し、クラッシュ、違法手、固定探索不一致、時間切れ、未完了局は0だった。

未使用opening seedの100 ms対局ではEdax L6に49.5%、L7に36.5%、L8に36.0%だった。L6の95%区間は50%を含み、L7とL8は50%を下回るため、この条件での保守的な強さは**Edax L6付近**とする。過去の単一seedで得た「L7付近」という評価はv1.0.0の代表値には採用しない。

## Subject

- Release candidate: `b6f6b096e579bcfef3bb84d913bfe0082dcd3c1f`
- Search/performance subject: `6debea4a9e0cb6a5b6e71a23039642e4b474e087`
- Integrated algorithm base: `cf175804ff19a4eeedcabd1646dfde1a06c23237`
- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Opening book SHA-256: `02AEDFC4B435BD66EE1F9509626EC60A3B811041C8823B7972044F72FCF9F42B`
- Java runtime: Oracle JDK 21, compile target: Java 11
- OS: Windows 11 amd64, logical processors: 20, max heap: 8,160 MiB
- Server: OthelloServer 1.17, `-timeout 10 -debug -trans`

`b6f6b09`は`6debea4`にCI試験3件を追加しただけで、Javaソースと測定対象は同一である。実行モデル本体、再配布禁止のサーバー、仮対戦AIはGit追跡外である。

## Frozen configuration

| Area | v1.0.0 configuration |
|---|---|
| Board | 64-bit bitboard、bit演算による合法手生成と着手 |
| Evaluation | 6局所パターン、7追加特徴、4 phase、CRC付き整数参照表 |
| Opening | WTHOR 58,252局、最大18手、4,252 entries、教師探索depth 5 |
| Base search | 反復深化alpha-beta/PVS、対称形正規化TT、root並列探索 |
| Accepted search | parallel fail-high再探索、LMR、depth 0/1 TT省略、専用leaf探索 |
| Endgame | 残り4手専用solver、edge安定石bound、時間連動完全読み |
| Selective search | 学習モデルSHAでgateしたholdout校正済みMulti-ProbCut |
| Runtime | threads既定`auto`、TT既定`auto`、診断上限2秒、最大8 threads |
| Time | サーバ10秒制限に対しクライアント内部8,000 msを推奨 |
| Ponder | 既定`off`、大会時は`--ponder on --ponder-ratio 0.8`を明示 |

不採用のhistory、killer、aspiration window、Adaptive LMR、2-way TT、自前TT lock rewriteなどはv1.0.0へ混入していない。採否と測定値は実験台帳および各結果レポートに保存している。

## Correctness and regression

| Check | Result |
|---|---|
| Java 11 target compile with `-Xlint:all` | PASS |
| Required Java regression | 11 / 11 PASS |
| Python training pipeline | 11 / 11 PASS (Python 3.12.13) |
| Real learned model CRC/color inversion/symmetry | PASS |
| Model manifest SHA-256 | MATCH |
| Standard fixed depth 9 | 64 / 64 consistent |
| Validation seed 20260722 depth 8 | 256 / 256 consistent |
| Deep depth 10 | 32 / 32 consistent |
| Stress, 32 positions x 3 repetitions | 384 / 384 consistent |
| Fixed-depth timeout / illegal / incomplete | 0 / 0 / 0 |
| Timed-search completed results | 64 / 64 |

固定深さの合計736結果は1/2/4/8 threadsで最善手と評価値が一致した。時限探索はthread schedulingで到達深度と着手が変わり得るため、固定深さ一致判定には含めない。

## Search performance

学習評価、seed `20260721`、8局面、TT 262,144 entriesを使用した。固定深さはdepth 9、時限探索は500 ms、各2反復である。

| Threads | Fixed nodes/s | Fixed speedup | Timed avg depth | Timed nodes/s |
|---:|---:|---:|---:|---:|
| 1 | 1,706,100 | 1.00x | 10.50 | 1,624,129 |
| 2 | 1,672,196 | 0.98x | 10.38 | 1,610,517 |
| 4 | 2,866,551 | 1.68x | 10.81 | 2,173,433 |
| 8 | 3,318,311 | 1.94x | 10.94 | 2,324,679 |

4Tの1T比speedupはvalidation 1.56x、depth 10で1.61x、stressで1.58xだった。8Tはそれぞれ1.77x、1.81x、1.82xである。2TはこのCPUでは1Tをわずかに下回るため、auto診断の保守的選択を維持する。

評価器単体は手設計190.5 ns/eval、学習評価426.6 ns/evalで、学習評価は約234万eval/sだった。学習評価は遅いが、事前計算表参照により探索内で実用的な速度を維持している。

## Runtime auto-sizing

最終profileは905 msで8 threads、4,194,304 TT entries、推定124 MiBを選択した。候補1/2/4/8Tの時限到達深度は10/10/10/11だった。診断時間は2,000 ms gate内であり、processor上限、8T上限、heap予算を超えていない。

統合時の別seed 10秒比較では、auto 8T/4,194,304 entriesが固定4T/262,144 entriesに対し平均深度14.500対14.125で、8局面すべての最善手が一致していた。Linux本番機ではCPUとheapが変わるため、起動時profileを保存して再確認する。

## Strength

Edax 4.6、100 ms/手、1 thread、定石off、8手random opening 50組を先後交換した。最終seedは未使用の`20260731`である。区間はdrawを0.5勝として計算したWilson近似95%区間。

| Opponent | W-D-L | Score | 95% interval | Avg margin | Avg depth | Nodes/s | Positive-equal-negative pairs |
|---|---:|---:|---:|---:|---:|---:|---:|
| Edax L6 | 46-7-47 | 49.5% | 39.9-59.1% | +1.28 | 8.80 | 1,624,637 | 24-2-24 |
| Edax L7 | 35-3-62 | 36.5% | 27.7-46.3% | -3.01 | 8.92 | 1,639,037 | 19-1-30 |
| Edax L8 | 34-4-62 | 36.0% | 27.3-45.8% | -4.93 | 8.86 | 1,649,338 | 13-2-35 |

L6は黒55%、白44%だが平均石差は+1.22/+1.34で、pair集計も24-2-24だった。L7は黒36%、白37%、L8は両色36%であり、L7/L8の劣勢を色偏りだけでは説明できない。

同じMPC探索核を使ったseed `20260728`のL7 100局は43-2-55、44.0%、平均石差-2.12だった。今回と合わせた200局は78-5-117、40.25%、平均石差-2.57である。opening seed間の分散は大きいが、総合してもL7互角を支持しない。

## Tournament budget and real server

Edax L7、10,000 ms、4T、seed `20260801`の先後2局は2-0-0、平均石差+14、平均着手6,172.850 ms、平均深度13.29、2,069,807 nodes/sだった。budget stop 32、完全読み20、違法手、GTP error、未完了は0。2局の勝敗は棋力推定には使わない。

実サーバでは固定4T、内部8,000 ms、TT auto、ponder 0.8で2局を連続実行した。両局33-31で完走し、4クライアントの終了コードと全stderrは0だった。

- Ponder starts/completed/interrupted: 120 / 96 / 24
- Average ponder depth: 12.94
- Prediction matches: 94 / 96 (97.9%)
- Own-turn initial TT hits: 112 / 120 (93.3%)
- Maximum stop p95: 4.998 ms
- Erroneous, opponent-turn, double, delayed PUT: 0
- Server timeout defeat, client-received ERROR, unfinished game: 0

サーバは各局の両クライアント`CLOSE`後に終了済み接続へ`ERROR 4`を1回送ろうとした。これはEND後のserver-side close処理であり、クライアント受信、対局結果、終了コードには影響していない。

## Statistical and operational limits

- 100 ms対局は大会8秒の棋力を直接表さない。
- Edax levelは等時間比較でもElo尺度でもない。
- 100局の区間は約18-20 point幅があり、小差の優越判断には不足する。
- random eight-ply openingは再現可能だが、均衡opening suiteではない。
- Windows上のauto/thermal確認は合格したが、Linux本番機のprofileは未測定である。
- PonderとMPCは共有TTで相互作用する。今回の実サーバ試験では異常0だが、本番機で長時間20局以上のon/off比較を行う余地がある。
- モデル本体はGit追跡外であり、異なるSHAではMPCが無効化され、同じ性能構成にならない。

## Artifacts

- `release-v1.0.0-{standard,validation,depth10,stress}-2026-07-22.csv`
- `release-v1.0.0-edax-l{6,7,8}-100-2026-07-22.txt`
- `release-v1.0.0-edax-l7-{preflight-20,tournament-2}-2026-07-22.txt`
- `release-v1.0.0-{evaluator,runtime-auto-profile}-2026-07-22.txt`
- `release-v1.0.0-client-{a,b}-game-{1,2}-2026-07-22.txt`
- `release-v1.0.0-server-debug-game-{1,2}-2026-07-22.txt`
- `release-v1.0.0-gamelog-game-{1,2}-2026-07-22.txt`
