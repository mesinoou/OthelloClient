# TOURNEY-001 white-side model selection

## Decision

大会構成はEVAL-018 heldモデル単独、既存定石、内部8,000 ms、threads/TT auto、ponder offとする。黒held・白standardの色別構成は任意機能として保持するが、大会既定にはしない。

色別構成はEdax L8の独立seedで標準比+6.0 point、held比+3.0 pointだった。一方、大会条件に近いEdax L11・8秒では総合37.5%、白25.0%となり、白改善が探索強度を上げた場合に再現しなかった。held単独には別seed・10秒・定石offながらL11正式100局45.5%の実績があるため、直前の構成変更より既存候補を優先する。

## Resource Discipline

検証機は20 logical CPU、利用可能メモリ約32 GBだった。

- L8の2構成同時比較は各Java 1 thread、各Edax 1 threadとし、合計約4 threadsに制限した。
- L11・8秒試験は8 threadsの1 processだけを実行した。
- 実サーバ2局は別portで、4 clients x 4 threadsの合計16 threadsに制限した。
- 高負荷L11と他benchmarkは重ねなかった。

100 msの時限探索は小さな実行時間差で手が変わる。seed `20260907`では色別構成を単独、対照2本を低負荷並列で測ったため、対応差にはtiming条件差が残る。採用判断はこのL8点推定だけで行わず、単独L11を優先した。

## L8 Screening

共通条件はEdax L8、100 ms、1 thread、4-ply opening、既存定石on、MPC/ponder off、各100局である。

| Seed | Model | Total | Black | White | Mean margin |
|---:|---|---:|---:|---:|---:|
| 20260905 | Standard | 38.0% | 35.0% | 41.0% | -10.33 |
| 20260905 | Held | 38.0% | 42.0% | 34.0% | -8.12 |
| 20260906 | Standard | 42.0% | 29.0% | 55.0% | -7.38 |
| 20260906 | Held | 40.5% | 42.0% | 39.0% | -8.38 |

2 seedを合わせるとstandardは黒32.0%・白48.0%、heldは黒42.0%・白36.5%だった。このため黒held・白standardを候補にした。

未使用seed `20260907`で色別構成を直接測定した。

| Model | Total | Black | White | Mean margin |
|---|---:|---:|---:|---:|
| Standard | 30.5% | 26.0% | 35.0% | -10.92 |
| Held | 33.5% | 35.0% | 32.0% | -9.66 |
| Black held / white standard | 36.5% | 34.0% | 39.0% | -9.81 |

色別構成の対応差はstandard比+6.0 point、95% CI `[-1.5,+13.5]`、held比+3.0 point、CI `[-5.0,+11.5]`だった。改善は統計的に確定していない。

## L11 Preflight

条件はEdax L11、8,000 ms、8 threads、4-ply opening、既存定石on、MPC/ponder off、seed `20260908`、10 pair・20局である。

| Games | W-D-L | Score | Mean margin | Black | White |
|---:|---:|---:|---:|---:|---:|
| 20 | 6-3-11 | 37.5% | -14.55 | 50.0% | 25.0% |

平均完了深さ12.51、平均思考時間4,915.98 ms、book move 21、WLD 193/193成功、違法手・未完局0だった。白は2勝1分7敗で、L8の改善を再現しなかった。

## Server Smoke

OthelloServer 1.17を`-timeout 10 -debug`で2 process起動し、held単一モデルの通信clientを各portへ2つ接続した。4 clientsは4 threads、8,000 ms、TT auto、ponder offである。

| Game | Result |
|---:|---|
| 1 | Smoke1a 34-30 Smoke1b |
| 2 | Smoke2b 34-30 Smoke2a |

4 clientsすべてexit code 0、各25 searches、最大8,000 ms、client/server stderr 0、違法手・通信ERROR・timeout敗北0だった。

## Tournament Configuration

- Black/white evaluator: `data/evaluation-tables-tournament.bin`
- Evaluator SHA-256: `d3b3b7b62848371f9fd3c950b7c7eb9abec79f9cdf09aa7e9c6b13647f628042`
- Opening book: `data/opening-book.bin`
- Opening book SHA-256: `02aedfc4b435bd66ee1f9509626ec60a3b811041c8823b7972044f72fcf9f42b`
- Internal time: 8,000 ms
- Threads / TT: auto
- Ponder: off
- Candidate MPC: automatically unsupported for this model SHA

```powershell
java -cp .build OthelloClient HOST PORT NICKNAME auto 8000 `
  data/evaluation-tables-tournament.bin --tt auto --ponder off
```

起動時に評価モデルSHA相当のdescription、定石entries 4,252、runtime診断結果を保存する。大会直前には追加の棋力変更を行わない。
