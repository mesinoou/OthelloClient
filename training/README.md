# 評価関数学習プログラム

このディレクトリには、公開自己対局棋譜と定石生成に用いたWTHOR棋譜から学習データセットを作成し、局所パターン評価モデルを学習するためのオフラインツールを収録している。

学習にはPythonを使用する。CPUではNumPy、CUDA対応GPUでは任意依存のPyTorchを使用できる。対局クライアントはJavaのままであり、最終的には学習結果を整数の固定配列として読み込むため、対局環境にPythonやニューラルネット実行環境は必要ない。

## 全体の流れ

リポジトリ直下のPowerShellで、次の順に実行する。

```powershell
python -m training.build_corpus
python -m training.verify_corpus
python -m training.materialize_dataset
python -m training.verify_dataset --dataset-dir .training/datasets/combined-evaluation-v4
python -m training.train_model
```

処理内容は次のとおりである。

1. 公開自己対局20,000局と公式WTHOR 137,548局を取得する
2. 全着手を合法手検証し、モデル非依存コーパスを生成する
3. コーパスから重複集約済みの現行6パターン特徴を生成する
4. ハッシュ、分割、教師値、特徴量を検証する
5. 4つの局面段階ごとに評価モデルを学習する
6. 学習モデルをJavaで利用可能な整数表へ量子化する
7. CRC付きのJava実行用バイナリへ変換する

### Linux・CUDA計算機

Linuxでは`python`を`python3`へ読み替える。学習済みモデルからJava用ファイルだけを生成する今回のコマンドは次のとおりである。

```bash
python3 -m training.export_java_model \
  --input .training/models/pattern-evaluation-v4/evaluation-tables.npz \
  --output .training/models/pattern-evaluation-v4/evaluation-tables.bin \
  --overwrite
```

今後`training.train_model`を実行した場合は`evaluation-tables.bin`も自動生成されるため、この変換コマンドを別途実行する必要はない。

## 1. 必要環境

- Python 3.11以上
- NumPy 2.x
- CUDA学習時のみ、CUDA対応版PyTorch 2.xとNVIDIA GPU
- 初回の棋譜取得時のみインターネット接続

環境を確認する。

```powershell
python --version
python -c "import numpy; print(numpy.__version__)"
```

NumPyがない場合は次を実行する。

```powershell
python -m pip install -r training/requirements.txt
```

CUDA学習を使用する場合は、使用するCUDA環境に対応したPyTorchを導入する。まず共通依存を導入し、[PyTorch公式のStart Locally](https://pytorch.org/get-started/locally/)でWindows、Pip、使用するCUDA版を選んで表示されたインストールコマンドを実行する。`requirements-cuda.txt`には学習器が対応するPyTorchのバージョン範囲を記録している。

```powershell
python -m pip install -r training/requirements.txt
python -c "import torch; print(torch.__version__, torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU')"
```

最後の確認で`True`とGPU名が表示されればCUDAを利用できる。PyTorchは導入されていても`torch.cuda.is_available()`が`False`の場合、`--device auto`はNumPy CPUへフォールバックする。

以後のコマンドは、必ずリポジトリ直下で実行する。スクリプトファイルを直接指定するのではなく、`python -m training...`形式を使用する。

## 2. データセットの生成

データは、モデルに依存しないコーパスと、特定モデル用の派生データセットに分けて管理する。パターン形状や追加入力、学習器を変更しても、生棋譜の取得と再生を繰り返す必要はない。

### 2.1 コーパス生成

```powershell
python -m training.build_corpus
python -m training.verify_corpus
```

既定では次の157,548局を入力として読み込む。

- `Nyanyan/OthelloAI_Textbook`の固定コミットにある自己対局20,000局
- FFOが公開している1977～2025年のWTHOR 137,548局

WTHORは`1977-1989`統合版と1990～2025年の年別ZIPを使う。全37アーカイブの件数とSHA-256は`wthor_sources.json`に固定している。従来の`2001-2015`統合版は年別ファイルの合計より137局少ないため、新コーパスには混ぜない。

合法手を残したまま棋譜が終了する記録は推測で補完せず、`rejected-games.jsonl.gz`へ理由付きで隔離する。2026-07-22の基準生成では古いWTHOR 413局を除外し、自己対局20,000局とWTHOR 137,135局を採用した。

入力と生成物の配置は次のとおりである。

```text
.training/sources/OthelloAI_Textbook/
.training/sources/wthor/
.training/corpora/othello-public-v1/
├─ games.jsonl.gz
├─ positions-00000.npz
├─ positions-00001.npz ...
└─ metadata.json
```

`games.jsonl.gz`には出典、完全な着手列、実戦結果、WTHOR理論値、分割情報を保存する。局面シャードには全意思決定局面の黒白ビットボード、手番、手数、棋譜ID、出典、実戦教師値、理論教師値だけを保存し、局所パターンなどのモデル固有特徴は保存しない。

WTHORの24空き理論値は該当局面に付与する。分割は12手目の盤面を8対称で正規化した定石系列単位で80%・10%・10%へ割り当てる。

主なオプションは次で確認できる。

```powershell
python -m training.build_corpus --help
```

ダウンロード済みの入力だけで再生成する場合は次を使う。

```powershell
python -m training.build_corpus --no-download --overwrite
```

### 2.2 現行モデル用データセット

```powershell
python -m training.materialize_dataset
python -m training.verify_dataset `
  --dataset-dir .training/datasets/combined-evaluation-v4
```

生成物は次の場所に保存される。

```text
.training/datasets/combined-evaluation-v4/
├─ train.npz
├─ validation.npz
├─ test.npz
└─ metadata.json
```

現行派生データセットは8～52手目を採用する。同一局面の複数観測は1行に集約するが、`sample_count`、黒勝ち・引き分け・白勝ちの件数、出典ビットマスクを保持し、重複回数とW/D/L分布を残す。`teacher_disc`と`teacher_filled`はWTHOR理論値がある局面では理論値を、ない局面では実戦平均を使う。検証・テストに訓練済みの完全同一局面があれば除外する。

モデル構造を変更する場合は`materialize_dataset.py`を変更するか、同じコーパスを読む別の変換器を追加する。コーパス自体は作り直さない。

### 2.4 着手順位データ

評価値の平均二乗誤差だけでなく、各合法手の順位を検証する場合は、v4から分割・フェーズ別に局面を抽出し、Java探索器で全合法手を採点する。

```powershell
python -m training.sample_ranking_positions --overwrite

javac --release 11 -encoding UTF-8 -d .build *.java
java -cp .build RankingTeacherGenerator `
  .training/ranking/positions.tsv `
  .training/ranking/scores-d8-d10.tsv `
  data/evaluation-tables.bin 8 10 30000 `
  current=data/evaluation-tables.bin `
  candidate=.training/models/candidate/evaluation-tables.bin

python -m training.evaluate_ranking `
  .training/ranking/scores-d8-d10.tsv `
  --output .training/ranking/metrics-d8-d10.json
```

抽出器は合法手が2手以上ある局面だけを選ぶ。教師探索はLMR、Multi-ProbCut、WLDを無効化し、深さ8と10の最善手一致率を教師安定性として記録する。完全読みが成立した局面では完全読みの石差を使用する。評価スクリプトはtop-1一致率、一対比較正解率、Spearman順位相関、深さ10の最善手に対するregretを分割・フェーズ別に出力する。

深さ8と10で最善手が一致した高信頼局面を使い、既存モデルを順位損失でファインチューニングする手順は次のとおりである。元の4百万局面学習を維持するため、既存モデルの出力とパラメータをアンカーとして使用する。

```powershell
python -m training.materialize_ranking_dataset `
  .training/ranking/scores-d8-d10.tsv `
  --output-dir .training/datasets/ranking-v1-d8-d10 `
  --overwrite

python -m training.train_ranking_model `
  --dataset-dir .training/datasets/ranking-v1-d8-d10 `
  --base-model .training/models/baseline/model-float.npz `
  --output-dir .training/models/eval-ranking `
  --device cuda `
  --overwrite
```

順位学習は候補モデルを別ディレクトリへ出力し、`data/evaluation-tables.bin`を直接変更しない。深さ安定率、順位指標、固定深さ対戦、Edax戦の順に採用判定する。

学習済み候補を教師探索の再実行なしで同じTSVへ追加採点する場合は次を使う。

```powershell
java -cp .build RankingModelScorer `
  .training/ranking/scores-d8-d10.tsv `
  .training/ranking/scores-with-candidate.tsv `
  ranking=.training/models/eval-ranking/evaluation-tables.bin
```

### 2.5 実探索葉の整合性補正

通常探索が評価関数を実際に呼び出した葉局面を収集し、その局面を追加探索した値との差を学習できる。収集探索ではLMRを有効、Multi-ProbCutを無効にし、評価呼び出しが探索葉だけになるようにする。各親局面では盤面ハッシュの小さい順に一定数を選ぶため、同じ入力と設定から同じ葉集合を得られる。

```powershell
javac --release 11 -encoding UTF-8 -d .build *.java
java -cp .build SearchEvaluationTeacherGenerator `
  .training/ranking/positions-v1.tsv `
  .training/search-evaluation-v1-d8-d4.tsv `
  data/evaluation-tables.bin `
  8 4 30000 16

python -m training.materialize_search_evaluation_dataset `
  .training/search-evaluation-v1-d8-d4.tsv `
  --output-dir .training/datasets/search-evaluation-v1-d8-d4 `
  --overwrite

python -u -m training.train_search_correction `
  --dataset-dir .training/datasets/search-evaluation-v1-d8-d4 `
  --base-model data/evaluation-tables.bin `
  --output-dir .training/models/eval-005-search-correction-e80 `
  --epochs 80 `
  --patience 10 `
  --device cuda `
  --overwrite
```

学習器は現行評価値と追加探索値の残差だけを、色交換で符号が反転する6-patternモデルへ学習する。補正モデルの出力層は0から開始し、現行runtime tableへ量子化補正を加算する。現行モデルに対応する`model-float.npz`は不要である。候補は指定した出力ディレクトリへ生成され、`data/evaluation-tables.bin`を変更しない。

補正量を縮小した候補は学習をやり直さず生成できる。

```powershell
python -m training.scale_search_correction `
  --base-model data/evaluation-tables.bin `
  --correction-model `
    .training/models/eval-005-search-correction-e80/correction-tables.bin `
  --output-dir .training/models/eval-005-search-correction-e80-s50 `
  --scale 0.5 `
  --overwrite
```

対応するopeningを1単位として対局結果のbootstrap区間を計算する例である。

```powershell
python -m training.analyze_match_pairs `
  benchmark/results/candidate.txt `
  --baseline benchmark/results/baseline.txt `
  --output benchmark/results/analysis.json
```

EVAL-005では固定深さ対戦を改善したがEdax L8を改善しなかったため、候補モデルは不採用とした。実験条件と考察は`benchmark/results/eval-005-search-leaf-correction-2026-07-23.md`に記録している。

### 2.6 Edax教師値

実探索葉TSVをEdax 4.6のOBF batch solverへ渡し、独立したscore教師を追加できる。Edax座標系に合わせるため盤面を左右反転し、入力順、出力件数、深さ、score、node数を検証する。

```powershell
python -m training.generate_edax_teacher `
  .training/search-evaluation-v1-d8-d4.tsv `
  .training/edax/search-evaluation-l9.tsv `
  --edax-executable benchmark/edax/wEdax-x86-64-v3.exe `
  --level 9 `
  --overwrite

python -m training.evaluate_edax_teacher `
  .training/edax/search-evaluation-l9.tsv `
  --reference .training/edax/search-evaluation-l11.tsv

python -m training.materialize_search_evaluation_dataset `
  .training/edax/search-evaluation-l9.tsv `
  --output-dir .training/datasets/edax-search-evaluation-l9 `
  --overwrite

python -u -m training.train_search_correction `
  --dataset-dir .training/datasets/edax-search-evaluation-l9 `
  --base-model data/evaluation-tables.bin `
  --output-dir .training/models/eval-edax-l9 `
  --teacher edax `
  --epochs 80 `
  --patience 10 `
  --output-anchor 0.5 `
  --device cuda `
  --overwrite
```

`generate_edax_teacher`は入力を変更せず、`edax_level`、`edax_depth`、`edax_score`、`edax_time_ms`、`edax_nodes`、`edax_pv`を追加する。`.obf`と実行条件・全SHA-256を含む同名の`.json`も同じ場所へ保存する。EVAL-006では候補自身が訪れた葉を再採点する反復で初回モデルの劣化を解消したが、Edax L8に対する改善は統計的に確認できず、モデルは不採用とした。

### 2.7 小規模確認

```powershell
python -m training.build_corpus `
  --exclude-wthor `
  --max-games 100 `
  --output-dir .training/corpora/smoke `
  --overwrite
python -m training.verify_corpus --corpus-dir .training/corpora/smoke
python -m training.materialize_dataset `
  --corpus-dir .training/corpora/smoke `
  --output-dir .training/datasets/smoke `
  --overwrite
```

`.training/`はGitの追跡対象外である。生棋譜、コーパス、派生データセット、学習モデルをGitHubへ登録しない。

2026-07-22に生成・検証した基準コーパスとv4派生データセットの件数、条件、SHA-256は`dataset-v4.json`に固定している。

## 3. データセットの検証

```powershell
python -m training.verify_dataset
```

次の項目を検証する。

- `metadata.json`に記録されたSHA-256との一致
- 各配列の長さと形状
- 黒石と白石のビットボードが重なっていないこと
- 石数と手数の整合性
- 各パターンインデックスが、そのパターン長に対応する`3^N`の範囲内であること
- 訓練・検証・テスト間に同一局面がないこと
- 抽出局面の合法手数、フロンティア、石差、角、安定辺、パリティ、パターン値の再計算結果

成功時の最後の行は次のようになる。

```text
dataset verification: PASS
```

検証する特徴量の局面数を変更する場合は次を使用する。

```powershell
python -m training.verify_dataset --sample-size 20000
```

別のデータセットを検証する例である。

```powershell
python -m training.verify_dataset `
  --dataset-dir .training/datasets/smoke
```

## 4. 学習前のスモークテスト

最初から全局面を学習する前に、少数局面・少数エポックで処理全体を確認する。

```powershell
python -m training.train_model `
  --epochs 2 `
  --max-samples-per-phase 4096 `
  --output-dir .training/models/smoke `
  --overwrite
```

この処理では次を確認できる。

- 4フェーズすべてで順伝播と逆伝播が動く
- 検証損失を計算できる
- 浮動小数モデルを保存できる
- 全パターンの整数評価表を生成できる
- テスト集合で浮動小数版と量子化版を比較できる

`--max-samples-per-phase`を指定したモデルは、`metadata.json`で`"smoke_only": true`となる。このモデルは動作確認専用であり、Javaクライアントへ組み込まない。

## 5. 本学習

既定条件で全データを学習する。

```powershell
python -m training.train_model
```

既定値を明示した同等のコマンドは次のとおりである。

```powershell
python -m training.train_model `
  --dataset-dir .training/datasets/combined-evaluation-v4 `
  --output-dir .training/models/pattern-evaluation-v4 `
  --epochs 20 `
  --batch-size 1024 `
  --learning-rate 0.001 `
  --l2 0.00001 `
  --patience 5 `
  --seed 20260720 `
  --label filled `
  --phase-starts 20,30,40,50 `
  --score-scale 6400 `
  --device auto
```

出力先がすでに存在する場合、誤上書きを防ぐため処理は停止する。作り直す場合だけ`--overwrite`を追加する。

### v4教師値モデルの実行例

2026-07-22に、このPCのRTX 3070 Tiを使用して次の条件で候補モデルを学習した。

```powershell
python -u -m training.train_model `
  --dataset-dir .training/datasets/combined-evaluation-v4 `
  --output-dir .training/models/pattern-evaluation-v4-teacher-cuda-e40 `
  --epochs 40 `
  --patience 7 `
  --train-metrics-samples 262144 `
  --batch-size 4096 `
  --device cuda `
  --amp `
  --label teacher-filled `
  --no-progress `
  --overwrite
```

学習時間は約35分42秒である。各フェーズの検証・テスト誤差、PyTorch/CUDA環境、Java読込テスト、出力ファイルのSHA-256は`model-v4-teacher-cuda-e40.json`に固定している。`--train-metrics-samples`は各エポック後の訓練指標計算だけを262,144局面に制限し、学習に使う局面数は減らさない。

この出力は対局比較前の候補であり、現行の`data/evaluation-tables.bin`はまだ置き換えない。大会条件のA/B対局で勝率を確認してから統合する。

### 学習モデル

各局面段階に次の共有ネットワークを持つ。隠れ層にだけ`LeakyReLU(alpha=0.01)`を使用し、各分岐の最後は正負を自由に出力できる線形層である。

- 8マス対角線: `16入力 → 16 → 16 → 1`
- 辺と2個のXマス: `20入力 → 16 → 16 → 1`
- 角三角形: `20入力 → 16 → 16 → 1`
- 内部8マス直線: `19入力 → 16 → 16 → 1`。`line2`、`line3`、`line4`のクラス入力を含む
- 長さ7・6の短対角線: `16入力 → 16 → 16 → 1`。長さクラス入力を含む
- 角3×3: `18入力 → 16 → 16 → 1`
- 自分と相手の合法手数: `2入力 → 8 → 1`
- 自分と相手のフロンティア: `2入力 → 8 → 1`
- 石差、角差、角合法手差、安定辺差、パリティ: それぞれ`1入力 → 4 → 1`
- 最終層: 全分岐の加算

学習にはAdam、平均二乗誤差、L2正則化を使用する。

盤面特徴と教師値は手番側視点へ変換する。黒番・白番のどちらでも`own`が自分、`opponent`が相手となり、教師値は終局黒石差へ手番色の符号を掛けた値である。さらに各分岐を`0.5 × (f(own, opponent) - f(opponent, own))`として学習し、色交換時に評価値の符号が必ず反転する構造にする。

### CPU・CUDAの選択

`--device auto`が既定である。CUDA対応PyTorchとGPUを検出した場合はCUDA、それ以外はNumPy CPUを使用する。

```powershell
# CUDAを必須とし、利用できなければエラーにする
python -m training.train_model --device cuda

# 2台目のGPUを指定する
python -m training.train_model --device cuda:1

# CUDAで半精度演算も使用する
python -m training.train_model --device cuda --amp

# PyTorchの有無にかかわらずNumPy CPUを使用する
python -m training.train_model --device cpu
```

CUDA学習後も`model-float.npz`へ同じ`float32`重みを保存し、評価表の生成と量子化はNumPyで行う。そのためCPU学習とCUDA学習の出力形式は共通である。

GPUメモリに余裕がある場合は`--batch-size 4096`または`8192`も比較する。大きすぎる値でメモリ不足になった場合は1024へ戻す。`--amp`はメモリ使用量と学習時間を減らせる一方、最終誤差が変化する可能性があるため、通常精度との対局比較を残す。

### 局面段階

既定の`--phase-starts 20,30,40,50`では、データを次の4モデルへ割り当てる。

| フェーズ | 手数 |
|---|---|
| 0 | 29手目まで。20手未満もこのモデルへ含む |
| 1 | 30～39手目 |
| 2 | 40～49手目 |
| 3 | 50手目以降 |

Javaクライアントも学習・テスト時と同じ30、40、50手目で表を切り替える。境界補間は別の対局実験で有効性を確認してから導入する。

### 教師値

`--label`で教師値を選択する。

| 値 | 内容 |
|---|---|
| `filled` | 勝者へ終局時の空きマスを加算した石差。既定値で、参照記事と同じ方式 |
| `disc` | 盤面上に実際に残った黒石数から白石数を引いた値 |
| `teacher-filled` | WTHOR理論値があればそれを使い、なければ`filled`の実戦平均を使う |
| `teacher-disc` | WTHOR理論値があればそれを使い、なければ`disc`の実戦平均を使う |

保存される教師値は黒視点だが、学習時に手番色を掛けて手番側視点へ変換する。値は`-64`から`64`までを取り、64で割って正規化する。v4では同一局面の複数結果を平均した浮動小数値を使用できる。

### 損失関数

`--loss`で学習目的を選択する。

| 値 | 内容 |
|---|---|
| `mse` | 正規化終局石差の平均二乗誤差。従来互換 |
| `huber` | 正規化終局石差のSmooth L1誤差 |
| `wld` | 同一局面の勝・分・敗件数から求めた手番側soft scoreへのBCE。出力はlogit |
| `hybrid` | 石差尺度の出力へHuberを適用し、`--wld-logit-scale`倍した値へWLD BCEも適用 |

`hybrid`の既定値は`wld_logit = 4.0 * normalized_margin`、石差Huber重み1.0、Huber境界0.25である。WLD教師は`black_wins`、`draws`、`white_wins`を使い、引分けを0.5点として手番側視点へ変換する。色交換時には評価値の符号と勝率がそれぞれ反転する。

```powershell
python -m training.train_model `
  --loss hybrid `
  --label filled `
  --wld-logit-scale 4.0 `
  --margin-loss-weight 1.0 `
  --huber-delta 0.25
```

### 学習オプション

| オプション | 既定値 | 内容 |
|---|---|---|
| `--dataset-dir` | `.training/datasets/combined-evaluation-v4` | 入力データセット |
| `--output-dir` | `.training/models/pattern-evaluation-v4` | モデル出力先 |
| `--epochs` | `20` | 最大エポック数 |
| `--batch-size` | `1024` | ミニバッチ局面数 |
| `--learning-rate` | `0.001` | Adam学習率 |
| `--l2` | `0.00001` | 重みのL2正則化係数 |
| `--patience` | `5` | 検証損失が改善しない場合の早期終了待機数 |
| `--seed` | `20260720` | 初期重みとシャッフルの乱数シード |
| `--label` | `filled` | 教師値の種類 |
| `--loss` | `mse` | `mse`、`huber`、`wld`、`hybrid` |
| `--margin-loss-weight` | `1.0` | `hybrid`の石差Huber項の重み |
| `--huber-delta` | `0.25` | 正規化石差に対するSmooth L1境界 |
| `--wld-logit-scale` | `4.0` | `hybrid`で石差尺度を勝率logitへ変換する倍率 |
| `--phase-starts` | `20,30,40,50` | 4フェーズの開始手数 |
| `--score-scale` | `6400` | Java用整数評価値への倍率 |
| `--max-samples-per-phase` | 制限なし | 各フェーズの最大局面数。指定時はスモーク扱い |
| `--train-metrics-samples` | 制限なし | 学習後の訓練指標だけを指定局面数へ制限。学習局面数は減らさない |
| `--device` | `auto` | `auto`、`cpu`、`cuda`、`cuda:N` |
| `--amp` | 無効 | CUDA自動混合精度を使用する |
| `--no-progress` | 無効 | コマンドライン進捗バーを非表示にする |
| `--overwrite` | 無効 | 既存の出力ディレクトリを作り直す |

すべてのオプションは次で確認できる。

```powershell
python -m training.train_model --help
```

## 6. 学習中の表示

各フェーズ・各エポックについて、学習、訓練指標計算、検証、評価表生成の進捗率とETAが表示される。

```text
phase 0 epoch 1/20 train [############------------] 50.0% 688/1377 ETA 12.4s
```

エポック完了後には次の指標が表示される。

```text
phase 0: {"epoch": 1, "train_mse": ..., "train_mae": ..., "validation_mse": ..., "validation_mae": ...}
```

- `train_mse`: 訓練集合の平均二乗誤差
- `train_mae`: 訓練集合の平均絶対誤差
- `validation_mse`: 検証集合の平均二乗誤差
- `validation_mae`: 検証集合の平均絶対誤差

`validation_loss`が`--patience`回連続で改善しない場合、そのフェーズは早期終了し、最も検証損失が低かった重みへ戻る。`validation_mse`と`validation_mae`に加え、v4データでは`validation_wld_log_loss`と`validation_wld_brier`も記録する。

訓練損失だけが下がり、検証損失が上がる場合は過学習の可能性がある。学習率、L2係数、パターン構成、データの重複や偏りを確認する。

## 7. 学習結果

本学習の出力は次のとおりである。

```text
.training/models/pattern-evaluation-v4/
├─ model-float.npz
├─ evaluation-tables.npz
├─ evaluation-tables.bin
└─ metadata.json
```

今回のv4教師値モデルは`.training/models/pattern-evaluation-v4-teacher-cuda-e40/`に保存する。生成物はGitの追跡対象外であり、再現に必要な設定とハッシュだけを`model-v4-teacher-cuda-e40.json`で管理する。

### `model-float.npz`

CPUまたはCUDAで学習した全重みを共通の`float32`形式で保存する。再評価、量子化方法の変更、学習結果の分析に使用する。Javaクライアントが直接読むファイルではない。

### `evaluation-tables.npz`

すべてのパターン状態を事前計算した`int16`表を保存する。

- 主対角線: `3^8 = 6,561`要素
- 辺と2X、角三角形: それぞれ`3^10 = 59,049`要素
- 内部直線: `line2`、`line3`、`line4`ごとに`3^8`要素
- 短対角線: `3^7`と`3^6`要素
- 角3×3: `3^9 = 19,683`要素
- 合法手数とフロンティア: それぞれ2次元表
- 石差、角、安定辺、パリティ: それぞれ1次元表
- 各フェーズのゼロ基準値。反対称性を保つため現在は常に0

この内容は学習終了時に`evaluation-tables.bin`へ変換される。逆向きに読んだ同一パターンの表は変換時に合成され、Javaでの局所パターン参照は68回から42回へ減る。

### `evaluation-tables.bin`

Javaクライアントが直接読み込む、約1.4MBの実行用モデルである。16個の`int16`表を4フェーズ分格納し、形式バージョン、パターン配置バージョン、評価値上限、CRC32を持つ。起動時にサイズ、全表長、評価値上限、CRC32を検査する。

Linuxでモデルを明示してクライアントを起動する例は次のとおりである。

```bash
mkdir -p .build
javac --release 11 -encoding UTF-8 -Xlint:all -d .build *.java
java -cp .build OthelloClient 127.0.0.1 25033 Player 4 8000 \
  .training/models/pattern-evaluation-v4/evaluation-tables.bin
```

### `metadata.json`

次を記録する。

- ネットワーク構造
- 学習条件と乱数シード
- 各フェーズの学習履歴
- 訓練・検証に使用した局面数
- 浮動小数モデルと整数表のテスト誤差
- 量子化時に`int16`範囲を超えた要素数
- スモークモデルかどうか

本学習では`"smoke_only": false`であることを確認する。また、`quantization.clipped_entries`はすべて`0`であることが望ましい。

`float_mae`と`quantized_mae`がほぼ同じなら、整数化による精度低下は小さい。差が大きい場合は`--score-scale`または量子化方式を見直す。

## 8. 学習器自体のテスト

```powershell
python -m unittest training.test_pipeline -v
```

次を確認する。

- Java盤面と公開棋譜の左右反転座標
- 公開棋譜先頭手順の合法性
- WTHORのZIP/WTB解析とサーバー座標への変換
- WTHOR記録スコアと終局再生結果の一致
- パターンインデックスの範囲
- フロンティア特徴の色対称性
- 安定辺・パリティ特徴と手番視点教師値
- 自分と相手を交換したとき評価値の符号が反転すること
- コマンドライン進捗表示
- モデルの順伝播・逆伝播結果が有限値であること

## 9. 再現性と再実行

同じ公開棋譜、同じ分割シード、同じ採取条件を使用すると、同じコーパスと派生データセットが生成される。現行v4で使用するWTHOR 37アーカイブの件数とSHA-256は`wthor_sources.json`に、生成済みコーパスとデータセットの件数・SHA-256は`dataset-v4.json`に固定している。入力ハッシュの不一致時は生成を中止する。旧v2で使用した3アーカイブの記録は`dataset-v2.json`に残している。

CPU学習も同じNumPy環境、乱数シード、学習条件では再現するよう設計している。ただし、NumPyやBLAS実装が異なる環境では浮動小数演算順序により末尾の値が異なる可能性がある。CUDA学習はGPU、CUDA、PyTorch、混合精度の違いにより同一シードでも末尾の値が一致しない場合がある。

現在の学習器は途中チェックポイントからの再開には対応していない。中断した場合は同じコマンドを`--overwrite`付きで再実行する。本学習結果を残したまま別条件を試す場合は、必ず別の`--output-dir`を指定する。

例:

```powershell
python -m training.train_model `
  --learning-rate 0.0005 `
  --l2 0.0001 `
  --output-dir .training/models/experiment-lr0005-l2-0001
```

## 10. 現在の適用範囲

この段階で、データ生成、検証、学習、量子化、Java用変換、Javaクライアントでの表参照まで接続している。モデル未配置時だけ`v0.1.0`の手設計評価関数へフォールバックする。次段階では本学習モデルを用いたベースライン対局比較を行い、探索時間内の実効深さと勝率を確認する。

## 出典とライセンス

公開棋譜とパターン形状の出典、固定コミット、利用条件は`THIRD_PARTY.md`を参照する。MITライセンス本文は`third_party/OthelloAI_Textbook-LICENSE.txt`に保存している。WTHORの生ZIP/WTBは再配布せず、ローカルの`.training/sources/wthor/`だけに保存する。
