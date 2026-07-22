# SEARCH-017: WLD endgame proof

## Decision

**採用**。勝率だけを目的とする大会条件に合わせ、終盤の石差完全読みを勝ち・引分・負けの3値WLD証明へ変更する。正当性、通常探索回帰、終盤性能、Edax非劣性、大会時間、実サーバの全ゲートを通過した。

100局の直接比較は候補46.5%、v1.0.0 44.5%で+2.0 pointだった。ただしpaired差の近似95%区間は-4.0〜+8.0 pointであり、優越を統計的に確定する結果ではない。事前に固定した非劣性条件を通過し、理論上不要な石差比較を大幅に削減できたことを採用根拠とする。

## Subject

- Base: `v1.0.0` (`681ace4`、treeは`7dc9e34`のmainと同一)
- Search implementation: `78a6eae`
- Measured commit: `b8b33c6bb07e9a24719c32a324e377258dc2c8f6`
- Branch: `codex/wld-endgame-search`
- Model SHA-256: `6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`
- Java runtime: Oracle JDK 21、compile target Java 11
- OS: Windows 11 amd64、logical processors 20

## Configuration

- WLD score: win `+100000`、draw `0`、loss `-100000`
- Threshold: `<1000 ms=14`、`>=1000 ms=16`、`>=3000 ms=18`、`>=8000 ms=20` empty squares
- 通常探索depth 4を先に確保し、WLD試行は総予算の65%で打ち切る
- WLD未完了時は途中結果を捨て、残り35%で通常の反復深化を再開する
- WLD中はLMR、Multi-ProbCut、石差用stability cutoffを無効化する
- parity ordering、root parallel search、残り4手専用solverは再利用する
- 勝ちを証明したnodeでは残りの同価値手を探索しない
- TTはWLD盤面キーを補数化して通常値と分離し、追加配列と追加entry memoryを持たない

## Correctness

- Java 11 `-Xlint:all` compile: PASS
- 必須Java回帰: 11 / 11 PASS
- 2〜10空き54局面: WLDと石差完全読みの勝敗不一致0
- 同54局面の1T・4T WLD不一致0
- Standard/Validation/Depth10固定探索: 352 / 352完了、スレッド間不一致0
- 固定時間探索: 64 / 64完了
- illegal move、固定探索timeout、worker集計異常: 0

## Endgame benchmark

条件は手設計評価、固定12局面、8,000 ms、4 threads、各1反復。時間は壁時計の整数msであり、小さい値の比率は参考値とする。

| Empties | Exact score solved | WLD solved | Exact avg ms | WLD avg ms | Exact avg nodes | WLD avg nodes | Node reduction | Compared mismatch |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 12 | 12/12 | 12/12 | 5 | 1 | 31,920 | 2,496 | 92.2% | 0/12 |
| 14 | 12/12 | 12/12 | 23 | 7 | 174,147 | 49,118 | 71.8% | 0/12 |
| 16 | 12/12 | 12/12 | 159 | 18 | 1,569,415 | 150,008 | 90.4% | 0/12 |
| 18 | 11/12 | 12/12 | 1,258 | 55 | 13,579,696 | 380,079 | 97.2% | 0/11 |
| 20 | 7/12 | 12/12 | 4,532 | 596 | 37,208,763 | 4,208,528 | 88.7% | 0/7 |

20空きWLDの最大時間は2,483 msで、12局面すべてを65%の5,200 ms枠内に完読した。比較元の石差探索が完読できなかった6局面は勝敗照合数へ含めていない。

100 ms・20空きを強制したfallback試験ではWLD 6/12完読、未完読6件、違法手0だった。完読6件を除いた通常探索の平均復帰深度は9.33 plyで、全体時間は最大100 msだった。

## Normal search performance

WLD対象外の学習評価局面ではv1.0.0と同じ探索結果を維持した。

| Suite | 1T | 4T | 8T | 4T speedup | 8T speedup |
|---|---:|---:|---:|---:|---:|
| Validation depth 8 | 47.672 ms | 29.810 ms | 26.357 ms | 1.60x | 1.81x |
| Standard depth 9 | 166.824 ms | 99.745 ms | 85.208 ms | 1.67x | 1.96x |
| Deep depth 10 | 316.590 ms | 197.747 ms | 173.583 ms | 1.60x | 1.82x |

Standard 500 msの平均深度は1T 10.50、2T 10.50、4T 10.75、8T 11.00で、v1.0.0の10.50、10.38、10.81、10.94と同水準だった。

## Strength

Edax 4.6 L7、100 ms/手、1 thread、定石off、8手opening 50組を先後交換し、未使用seed `20260802`を両実装へ使用した。

| Subject | W-D-L | Score rate | Average margin | Average depth |
|---|---:|---:|---:|---:|
| v1.0.0 | 42-5-53 | 44.5% | -0.56 | 8.77 |
| WLD | 45-3-52 | 46.5% | -3.55 | 8.81 |

- Game comparison: improved 7、equal 88、worse 5
- Opening-pair comparison: improved 6、equal 41、worse 3
- Candidate point change: black +0.5、white +1.5
- WLD: 691 attempts / 691 solutions、1,026,066 nodes、0.246 sec total

平均石差は悪化したが、大会目的が勝率のみであるため採否には使用しない。勝率+2 pointは非劣性を示す一方、100局では優越を断定できない。

## Tournament and server

10,000 ms、4 threads、Edax L7先後2局は1-0-1、WLD 20/20完読、WLD合計1.161 sec、違法手・未完了・10秒超過0だった。

実サーバ1.17を`-timeout 10 -debug -trans`で起動し、候補4クライアントを4 threads、内部8,000 ms、ponder 0.8で接続した。並行2局はいずれも37-27で完走した。

- Client exit code: 4 / 4 zero
- Server normal game end: 2 / 2
- Client WLD: 40 / 40 solved、fallback 0
- Client maximum measured search: 8,001 ms、server timeout defeat 0
- Client received ERROR: 0、stderr bytes: 0
- Ponder stop p95 maximum: 7.120 ms
- Erroneous PUT: 0 / 4 clients

サーバは1局目の両クライアント`CLOSE`後、終了済み接続へ`ERROR 4`を送ろうとした。v1.0.0検証時と同じserver-side close処理であり、クライアント受信、対局結果、exit codeには影響していない。

## Limits

- 20空きの石差比較は7局面だけであり、残り5局面は石差側が8秒以内に完読していない
- 100局の+2 pointは統計的に確定した改善ではない
- WLDは最終石差を最適化しないため、石差を順位やtie-breakへ使う規則には適さない
- 65%試行制限は遅い計算機でもfallback時間を残すが、Linux本番機で20空き完読率を再測定する必要がある
- 並行2局ではCPU競合下でも全WLDが成功したが、異なるCPU、heap、thermal条件を網羅していない

## Artifacts

- `search-017-wld-endgame-{12,14,16,18,20}-2026-07-22.csv`
- `search-017-wld-fallback-2026-07-22.csv`
- `search-017-wld-{standard,validation,depth10}-2026-07-22.csv`
- `search-017-{v1.0.0,wld}-edax-l7-100-2026-07-22.txt`
- `search-017-wld-edax-l7-{preflight-20,tournament-2}-2026-07-22.txt`
- `search-017-wld-client-{1,2,3,4}-2026-07-22.txt`
- `search-017-wld-server-debug-2026-07-22.txt`
- `search-017-wld-gamelog-game-{1,2}-2026-07-22.txt`
