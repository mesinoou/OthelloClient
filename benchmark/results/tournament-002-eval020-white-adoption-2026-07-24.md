# TOURNEY-002 EVAL-020 white adoption

## Decision

大会構成を黒EVAL-018 held・白EVAL-020 25%補正へ更新する。EVAL-020は
白番限定で使用し、黒番の評価モデルは変更しない。探索は8,000 ms、
threads/TT auto、ponder offを維持する。

## Evidence

| Test | EVAL-018 white | EVAL-020 25% white | Difference |
|---|---:|---:|---:|
| 固定深さ8、20 opening | 40.0% | 50.0% | +10.0 point |
| Edax L11、8局、500 ms | 25.0% | 25.0% | 0.0 point |
| 同Edax L11平均石差 | -15.25 | -14.00 | +1.25 |

固定深さでは改善し、短いEdax L11でも平均石差は悪化しなかった。一方、
L11は8局だけで勝率差もないため、統計的に改善が確定したとは扱わない。
大会直前の判断として、変更範囲を白番だけに限定して採用する。

## Frozen configuration

- Black: `data/evaluation-tables-tournament.bin`
- Black SHA-256:
  `D3B3B7B62848371F9FD3C950B7C7EB9ABEC79F9CDF09AA7E9C6B13647F628042`
- White: `data/evaluation-tables-tournament-white.bin`
- White SHA-256:
  `68AD6F139AE7F7A5C0AA023E2198C8AFC8DF7FB2C1BF2EA63DFBB13BEF04F15C`
- Opening book: `data/opening-book.bin`
- Opening book SHA-256:
  `02AEDFC4B435BD66EE1F9509626EC60A3B811041C8823B7972044F72FCF9F42B`
- Internal time: 8,000 ms
- Threads / TT: auto
- Ponder: off

モデル本体は従来方針どおりGit追跡外とし、
`data/tournament-models.sha256`で配置内容を固定する。

```powershell
java -cp .build OthelloClient HOST PORT NICKNAME auto 8000 `
  data/evaluation-tables-tournament.bin `
  --white-model data/evaluation-tables-tournament-white.bin `
  --tt auto --ponder off
```

## Verification

- SHA-256 manifest: 3/3一致
- `javac --release 11 -Xlint:all`: PASS
- Java test classes: 12/12 PASS
- 黒・白モデルの色反対称性と盤面対称性: PASS
- 色別モデル指定の短縮対局: 4/4完走
- OthelloServer 1.17通信対局: 60着手で完走、37-27
- 黒クライアントの`active=black`、白クライアントの`active=white`: 確認
- timeout敗北: 0
- server/client stderr: 0 byte

実サーバはEND後にクライアントのCLOSEを受けて`ERROR 4`を返したが、
勝敗確定と両クライアントのEND受信後であり、対局結果には影響しない。
