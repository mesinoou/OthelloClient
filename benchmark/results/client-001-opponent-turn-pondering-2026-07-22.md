# CLIENT-001 opponent-turn pondering

## Decision

`accepted`。相手手番中に相手視点の通常探索を行い、自手番探索へ同じTransposition Table (TT)だけを引き継ぐ。100局比較ではscore rateが0.5 point低下した一方、平均石差は+1.25、自手平均深度は+0.19改善した。実サーバ2局では誤PUT 0、停止p95最大5.025 msで、事前に固定した非劣性・通信安全性条件を通過した。

初回統合では既定値を`off`のまま維持する。大会利用時だけ`--ponder on`を明示し、Linux本番機でCPU温度と8秒条件を再確認する。

## Change

- Base: `b82af89` (`baseline/runtime-auto-sizing-20260722`)
- Experiment start: `27b0471`
- Client implementation: `122e264`
- Match benchmark: `4af1cc4`
- Nonblocking handoff fix: `dd1be8a`
- `--ponder on|off`と`--ponder-ratio`を追加。既定はoff、ratioは0.80
- budgetは`min(8000 ms, floor(ownMoveBudget * ratio))`
- 自分のPUT後はBOARDをstaleとし、更新BOARD受信前は探索しない
- ponderはopening bookを迂回して同じ`SearchEngine`を使用する
- single-thread controllerでponder停止後だけauthoritative searchを開始する
- PUT送信処理はauthoritative pathにだけ存在する
- 開始、完了、中断、時間、node、深度、TT hit、予測一致、停止p95、誤PUTを集計する

Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`

## Regression and protocol

Java 11 targetの警告込みコンパイルと必須回帰テスト11件を通過した。`OthelloPonderingTest`は相手待ち`0/50/500/2000/8000 ms`を実行し、自分のPUT後にTURNがBOARDより先着する順序も含む。

- Ponder starts: 5/5 cases
- Own-turn root TT warmed: 4/5 cases
- Stop p95: 4.278 ms
- Opponent-turn PUT、double PUT、delayed PUT、erroneous PUT: 0
- 更新BOARD前のponder: 0
- unfinished search / connection: 0

固定深さvalidationはseed `20260722`、32局面、depth 8、2反復、1/2/4/8 threadsの256結果で、最善手・評価値・候補内consistencyの不一致0件だった。`--ponder off`の既存通信テストもPASSし、通常の固定探索benchmarkへponderは混入しない。

## 100-game comparison

同じ50 random openingを先後交換し、学習評価対手設計評価を100局実行した。opening seedは`20260730`、8手opening、定石off、1 thread、自手20 msである。Ponder版だけ相手の各合法手番に16 msの探索時間を与えた。

| Version | W-D-L | Score rate | Avg margin | Own avg depth | Initial TT hits |
|---|---:|---:|---:|---:|---:|
| Off | 92-1-7 | 92.50% | +23.78 | 7.19 | 2451 / 2708 (90.5%) |
| On | 92-0-8 | 92.00% | +25.03 | 7.38 | 2532 / 2702 (93.7%) |

同一100局の石差はponder版が42局改善、19局同値、39局悪化した。先後をまとめた50 pairでは25組改善、2組同値、23組悪化。対応差は平均+1.25石、近似95%区間は-1.74から+4.24であり、優越は主張しない。score rate低下は5 point以内で、平均石差と探索深度はともに改善したため非劣性と判定した。

Ponder版は2,495回、平均13.013 ms、平均depth 7.69、54,367,259 nodesを相手手番に探索した。自手番の平均時間はoff 15.132 ms、on 15.093 msで、handoffによる自手予算超過は見られなかった。

## Real server

実サーバを`-timeout 10 -debug -trans`で起動し、4 threads、1,000 ms/手、ponder 800 msのクライアント同士で2局を完走した。結果は40-24と36-28。4クライアント合計でponder開始120、予測一致99/108 (91.7%)、自手番root TT hit 108/120 (90.0%)、誤PUT 0だった。各クライアントの停止p95は4.125-5.025 msで、50 ms gateを通過した。client/server stderr、timeout敗北、未完了局も0だった。

最初の予備対局では停止p95が2.48/4.84秒となり不合格だった。探索自体は800 ms以内に終了しており、ponderごとの詳細出力と盤面の多数回`print`による出力ロック待ちがcontroller復帰を遅らせていた。`dd1be8a`でponderを終了集計だけにし、盤面を1回のwriteへまとめた後、上記2局を取り直した。

## Residual risk

100局比較は短時間の手設計評価相手であり、Edaxや大会8秒条件での優越を示さない。連続ponderはCPU温度とclockへ影響し得るため、本番Linux機では`--ponder off`を対照として8秒条件20局以上を再測定する。MPC由来のTT boundと誤予測局面のentryが自手探索へ残るが、今回のscore、石差、深度には同時悪化がなかった。既定offをonへ変える判断はこの実験に含めない。

## Verification

- Java 11 target compilation with `-Xlint:all`: pass
- Required regression tests: 11 of 11 pass
- Fixed-depth consistency failures: 0 of 256
- Mock wait cases: 5 of 5 pass
- 100-game non-inferiority: pass
- Real-server games: 2 of 2 complete
- Illegal、double、opponent-turn、delayed PUT: 0
- Real-server maximum stop p95: 5.025 ms

## Artifacts

- `client-001-validation-2026-07-22.csv`
- `client-001-ponder-{off,on}-handcrafted-100-2026-07-22.txt`
- `client-001-server-smoke-2026-07-22.txt`
- `client-001-server-game-{1,2}-2026-07-22.txt`
