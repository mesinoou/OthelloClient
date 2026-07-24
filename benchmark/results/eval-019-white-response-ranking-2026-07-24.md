# EVAL-019 White-response WLD ranking

## 結論

白番敗因解析で最初のW/D/L降格がphase 0-1へ集中したため、白番の全合法手を
Edaxで採点し、WLD順位を直接改善する量子化補正を実装した。しかし候補は
学習分布と現行AIとの直接対戦には適合した一方、新規openingのEdax L11戦で
明確に悪化した。runtimeモデルへは統合せず、学習・診断基盤だけを保存する。

## 仮説と実装

- 親が白、子が黒手番となる兄弟局面を、親視点へ符号反転して順位学習する。
- 石差より先に`勝ち > 引分 > 負け`をpairwise/listwise lossで学習する。
- 敗因が集中したphase 0-1だけを補正し、phase 2-3はゼロ補正とする。
- 既存6-pattern表は固定し、ゼロ出力から開始した反対称補正表だけを加算する。
- opening単位でtrain/validation/testへ分割し、系列リークを防ぐ。
- 無補正をepoch 0として、WLD安全性と小さい補正を優先してepochを選ぶ。

実装は`training/train_white_response_ranking.py`、回帰テストは
`training/test_white_response_ranking.py`である。候補は
`data/evaluation-tables-tournament.bin`を変更せず、`.training/models/`
以下へ出力した。

## データ

### L11敗局限定候補

- EVAL-018の均衡opening白敗局12局
- 250判断、2,407兄弟候補
- Edax 4.6 level 11教師
- opening split:
  - train: 9, 15, 17, 26, 27, 34, 36, 47
  - validation: 2, 31
  - test: 28, 41
- base SHA-256:
  `D3B3B7B62848371F9FD3C950B7C7EB9ABEC79F9CDF09AA7E9C6B13647F628042`
- candidate SHA-256:
  `4AE43C0264C1D523CF4BC2E29956222D5CD964A3933A4FB87BAC9E333E3B911B`

### L8混合候補

過適合抑制のため、白番50局、10,903兄弟候補のEdax L8教師を追加し、
L11敗局データを4倍相当で混合した。合計20,531候補である。
candidate SHA-256は
`41A7188D68FD48D79CA85B9E75D2B5142598F50BD13803A1AE842F7C8F1C8587`
である。

## オフライン結果

L11敗局限定候補の量子化後結果:

| split | 指標 | base | candidate |
|---|---:|---:|---:|
| validation 30判断 | WLD降格率 | 20.0% | 16.7% |
| validation 30判断 | 平均石差後悔 | 1.20 | 0.97 |
| test 30判断 | WLD降格率 | 13.3% | 10.0% |
| test 30判断 | 平均石差後悔 | 2.93 | 2.60 |
| test 30判断 | 重大後悔率 | 30.0% | 26.7% |

L8混合候補はtest 196判断でWLD降格率が10.7%から11.7%へ悪化した。
教師強度の異なるデータを単純混合しても汎化改善にはならなかった。

## Runtime検証

- Java 13テスト: 全件PASS
- Python 41テスト: 全件PASS
- 候補モデルの色反対称性・盤面対称性: PASS
- 5,000,000回評価:
  - base: 410.2 ns/eval
  - candidate: 412.4 ns/eval

表数と参照回数は変わらず、速度差は約0.5%で測定揺らぎの範囲である。

## 対局結果

### 現行モデルとの固定深さ8、40局

全色候補に対する現行側の結果は21-4-15、57.5%、平均石差+2.35だった。
候補を色別に見ると:

- candidate white vs base black: 11-1-8、57.5%
- candidate black vs base white: 4-3-13、27.5%

白向けの学習信号は出たが、黒へ適用すると大きく悪化した。白番限定構成では
同じ20 openingでcandidate whiteが11-1-8、base whiteが8-1-11となった。
ただし対現行の改善は独立した棋力改善を保証しない。

### 新規opening、Edax L11、白番8局

条件はopening seed 20260725、8手opening、500 ms/手、8 threads、
book off、ponder off、MPC onである。

| model | W-D-L | score | 平均石差 | 平均深度 |
|---|---:|---:|---:|---:|
| base | 2-0-6 | 25.0% | -15.25 | 10.69 |
| candidate | 0-0-8 | 0.0% | -24.13 | 10.59 |

候補は勝率-25 point、平均石差-8.88であり、小標本でも採用停止に十分な
悪化である。

## 考察

敗局限定データは「現行が既に選び損ねた局面」へ強く条件付けされている。
この分布でWLD順位を改善すると、現行モデルとの直接対戦では差が出ても、
通常の対局で遭遇する白番局面全体には一般化しない。さらにL8教師の単純混合は
教師誤差を増やし、L11の勝敗境界を保てなかった。

次回はEVAL-018の全50白番について、勝ち・引分・負けを含む全兄弟手を
同一Edax L11条件で採点する。結果別・opening評価帯別のworst-groupを
validation選択へ追加し、敗局だけの改善を進級条件にしない。候補は白番限定で
評価し、未見openingのEdaxゲートを固定深さ対現行より先に通す。

## 採否

- 学習器、opening分割、量子化比較、WLD指標: 保存
- L11敗局限定モデル: 不採用
- L8混合モデル: 不採用
- 大会用`data/evaluation-tables-tournament.bin`: 変更なし
