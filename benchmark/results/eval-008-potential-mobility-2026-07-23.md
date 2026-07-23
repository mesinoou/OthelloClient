# EVAL-008 potential mobility

## Decision

`rejected before Java runtime implementation`。

潜在的モビリティを「相手石に隣接する空きマス数」と定義し、手番側と相手側の組へ色交換反対称な65x65残差表をphase別に学習した。Edax L9実葉の独立testではphase 0・1に改善がなく、phase 2はMSE 0.10%、phase 3は1.20%の改善にとどまった。phase 3は通常対局でWLD・完全読みに入る終盤と重なるため、追加計算とモデル形式拡張に見合う効果がない。

Java評価器、標準モデル、`data/evaluation-tables.bin`は変更しない。潜在的モビリティ特徴の生成とオフライン表当ては、将来より大きな独立データで再評価できる基盤として保存する。

## Feature

```text
ownPotential = popcount(empty & neighbors(opponent))
opponentPotential = popcount(empty & neighbors(own))
```

4つのphaseごとに`T[ownPotential][opponentPotential]`を持ち、`T[a][b] = -T[b][a]`を強制した。教師は`Edax score - current static score`、標本重みは`min(sqrt(occurrences), 8)`である。未観測セルは0、観測セルはridge縮小した条件付き平均を使った。

## Regularization

最初の候補`1,4,16,64`では全phaseが最大64を選び、過学習側の境界だった。そのため測定前の停止判断として`64,256,1024,4096`へ拡張した。

| Phase | Selected ridge | Validation direction | Test MSE reduction | Test MAE before | Test MAE after |
|---:|---:|---|---:|---:|---:|
| 0 | 4096 | worse | -0.006% | 8.543 | 8.543 |
| 1 | 4096 | neutral | +0.002% | 8.058 | 8.058 |
| 2 | 1024 | slight improve | +0.102% | 8.718 | 8.711 |
| 3 | 64 | improve | +1.197% | 7.395 | 7.330 |

MAEは石差単位である。phase 0・1の補正RMSはJava scoreで1.32、1.83、すなわち0.02石未満まで縮み、実質的にゼロだった。

## Interpretation

- 現行の局所パターン、実モビリティ、フロンティアが潜在的モビリティの情報を大部分すでに代理している
- 28,656葉に対する65x65表は疎で、強いridgeなしでは条件付き平均を再現できない
- 唯一の改善が終盤phaseへ偏り、通常探索の主要な中盤葉へ効かない
- 独立特徴として無効とは断定しないが、現在のデータ量と実行方式では優先度が低い

次は1特徴の追加ではなく、局面段階の粗さと既存6-pattern表の表現容量を検証する。runtime変更前に、増やしたphaseで独立test誤差と兄弟手順位の両方が改善することを要求する。

## Verification

- Python tests: 27/27 PASS
- Dataset materialization: train 16,384、validation 4,096、test 8,176
- Color antisymmetry table test: PASS
- Java runtime changes: none

## Reproducibility

- Branch: `codex/eval-008-potential-mobility`
- Parent: `f7f555e`
- Current model SHA-256: `6e118c928729e003e89742d3b57fe6ed35fa9233a38dbe8af852041ebee39457`
- Dataset metadata SHA-256: `b9838d0df2ef7ae796f0f86a1b32180437cb9d29b8732b345b2b52b61bbfcb6c`
- Table archive SHA-256: `ccdf0997316eca84b55740de8b1eb1c2c9858eecfd99640220ea16cc20b948a2`
