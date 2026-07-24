# EVAL-020 Broad L11 white-response ranking

## 結論

EVAL-019の敗局限定過適合を避けるため、実戦Edax L8評価100局のうち
白番50局から得た全兄弟手をEdax L11で再採点した。全量補正は未見集合で
悪化したが、25%補正は未見WLDを維持し、固定深さ白番と短いEdax L11の
平均石差を改善した。統計量は採用に不足するため、白番限定のheld候補として
保存し、大会用モデルには統合しない。

## データ監査

最初にEVAL-018 opening診断traceから15,333候補を採点したが、このtraceは
opening抽出用の1 ms級対局で、全局`WIN`、探索深度2前後だった。通常対局分布
ではないため学習から完全に除外した。

採用した入力は`held-l8-seed20260907-white-siblings-input.tsv`である。

- 白番50局
- 1,368判断
- 10,903兄弟候補
- 元対局結果別候補行: WIN 3,432、DRAW 1,347、LOSS 6,124
- Edax 4.6 level 11、8 threads
- 採点時間209.5秒
- Edax nodes 26,662,241,663
- 全10,903件がexact score
- scored TSV SHA-256:
  `E588B554095ABA561960DD69A460C16CEFB0A722C60523162A6F8E717243DFA3`

opening単位で34 train、8 validation、8 testへ分割した。

## 境界score対応

別の診断採点中にEdaxが`<-63`、`<-60`を返したため、教師生成器へ
`edax_score_bound`を追加した。

- 通常整数: `exact`
- `<-N`: 負け確定の`upper`
- `>+N`: 勝ち確定の`lower`
- WLD pairには使用可能
- 同一WLD内の石差pairからは除外
- 符号が確定しない境界表記はエラー

この変更は学習データ生成だけに作用し、Java探索には影響しない。

## 学習結果

baseはEVAL-018 heldモデルで、SHA-256は
`D3B3B7B62848371F9FD3C950B7C7EB9ABEC79F9CDF09AA7E9C6B13647F628042`
である。保守的epoch選択によりphase 0はepoch 16、phase 1-3はepoch 0
となった。

全量候補のtest 136判断:

| 指標 | base | 100% correction |
|---|---:|---:|
| WLD降格率 | 16.91% | 17.65% |
| 平均class後悔 | 0.294 | 0.316 |
| 平均石差後悔 | 4.20 | 4.54 |
| 重大後悔率 | 35.29% | 36.03% |

100%補正は不採用とした。補正縮小後:

| scale | WLD降格率 | 平均class後悔 | 平均石差後悔 | 重大後悔率 |
|---:|---:|---:|---:|---:|
| base | 16.91% | 0.294 | 4.20 | 35.29% |
| 25% | 16.91% | 0.294 | 4.00 | 35.29% |
| 50% | 16.18% | 0.279 | 4.16 | 33.82% |
| 75% | 16.18% | 0.287 | 4.54 | 35.29% |
| 100% | 17.65% | 0.316 | 4.54 | 36.03% |

50%はWLD指標、25%は石差指標が最良だったため、両方を短い対局ゲートへ
進めた。

## 固定深さ8

条件は20 opening、先後40局、1 thread、MPC off、book offである。
黒はbase固定、白だけを候補へ変更した。

| white model | white W-D-L | white score |
|---|---:|---:|
| base | 7-2-11 | 40.0% |
| 25% correction | 10-0-10 | 50.0% |
| 50% correction | 8-1-11 | 42.5% |

25%補正がbase比+10 pointで最良だった。

## Edax L11白番

条件はopening seed 20260725、8 opening、500 ms/手、8 threads、
MPC on、book off、ponder offである。

| white model | W-D-L | score | 平均石差 | 平均深度 |
|---|---:|---:|---:|---:|
| base | 2-0-6 | 25.0% | -15.25 | 10.69 |
| 25% correction | 2-0-6 | 25.0% | -14.00 | 10.57 |
| 50% correction | 2-0-6 | 25.0% | -17.50 | 10.48 |

25%補正は勝率同値、平均石差+1.25だった。50%補正は平均石差-2.25のため
棄却した。

## 検証

- Python unittest: 43/43 PASS
- 25%候補の色反対称性・盤面対称性: PASS
- 25% candidate SHA-256:
  `68AD6F139AE7F7A5C0AA023E2198C8AFC8DF7FB2C1BF2EA63DFBB13BEF04F15C`

## 採否

- Edax境界score対応: 採用
- 広域L11教師データ: 今後の再学習へ保存
- 100%、50%、75%候補: 不採用
- 25%候補: 白番限定held
- 大会用`data/evaluation-tables-tournament.bin`: 変更なし

次の正式判定では、25%候補を未使用openingで白番20局以上、可能なら大会時間
10秒でbaseと対応比較する。勝率だけでなく対応石差と探索深度の非劣化を必須とする。
