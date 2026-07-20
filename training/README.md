# 評価関数学習プログラム

このディレクトリには、公開自己対局棋譜と定石生成に用いたWTHOR棋譜から学習データセットを作成し、局所パターン評価モデルを学習するためのオフラインツールを収録している。

学習にはPythonとNumPyを使用する。対局クライアントはJavaのままであり、最終的には学習結果を整数の固定配列として読み込むため、対局環境にPythonやニューラルネット実行環境は必要ない。

## 全体の流れ

リポジトリ直下のPowerShellで、次の順に実行する。

```powershell
python -m training.build_dataset
python -m training.verify_dataset
python -m training.train_model
```

処理内容は次のとおりである。

1. 公開自己対局棋譜20,000局とWTHOR棋譜58,252局を取得する
2. 全着手を合法手検証しながら再生する
3. 訓練・検証・テストデータを生成する
4. データセットのハッシュと特徴量を検証する
5. 4つの局面段階ごとに評価モデルを学習する
6. 学習モデルをJavaで利用可能な整数表へ量子化する

## 1. 必要環境

- Python 3.11以上
- NumPy 2.x
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

以後のコマンドは、必ずリポジトリ直下で実行する。スクリプトファイルを直接指定するのではなく、`python -m training...`形式を使用する。

## 2. データセットの生成

### 初回生成

```powershell
python -m training.build_dataset
```

既定では、次の78,252局を取得する。

- `Nyanyan/OthelloAI_Textbook`の固定コミットにある自己対局20,000局
- `data/opening-book.bin`の生成に使ったWTHOR 58,252局

WTHOR側は`2001-2015`、`2024`、`2025`の3アーカイブである。定石表に登録された4,252局面だけを加えるのではなく、元になった全棋譜から8手目から52手目までの局面を採取する。取得元、固定条件、利用条件は`THIRD_PARTY.md`に記録している。

入力棋譜は次の場所へ保存される。

```text
.training/sources/OthelloAI_Textbook/
.training/sources/wthor/
```

生成されたデータセットは次の場所へ保存される。

```text
.training/datasets/combined-evaluation-v2/
├─ train.npz
├─ validation.npz
├─ test.npz
└─ metadata.json
```

`.training/`はGitの追跡対象外である。生棋譜や大きな学習データをGitHubへ登録しない。

### 生成済みデータ

既定条件で生成済みのデータは次のとおりである。

| 集合 | 棋譜数 | 採用局面数 |
|---|---:|---:|
| 訓練 | 62,611 | 2,816,098 |
| 検証 | 7,800 | 197,683 |
| テスト | 7,841 | 201,143 |

採用局面のソース別内訳は次のとおりである。

| 集合 | 自己対局 | WTHOR |
|---|---:|---:|
| 訓練 | 719,010 | 2,097,088 |
| 検証 | 3,737 | 193,946 |
| テスト | 3,230 | 197,913 |

同一棋譜内の局面が複数の集合へ分かれないよう、棋譜単位で80%・10%・10%に分割する。さらに、訓練集合ですでに出現した同一局面は検証・テスト集合から除外する。

生成条件、WTHOR ZIPの固定ハッシュ、NPZファイルのSHA-256は`dataset-v2.json`にも記録している。旧自己対局のみのデータは`dataset-v1.json`に記録したまま残している。

### データセット生成オプション

| オプション | 既定値 | 内容 |
|---|---|---|
| `--source-dir` | `.training/sources/OthelloAI_Textbook` | 入力棋譜の保存場所 |
| `--wthor-source-dir` | `.training/sources/wthor` | WTHOR ZIPの保存場所 |
| `--output-dir` | `.training/datasets/combined-evaluation-v2` | データセット出力先 |
| `--source-files` | `20` | 読み込む公開棋譜ファイル数 |
| `--max-games` | 制限なし | 読み込む自己対局の最大棋譜数。動作確認用 |
| `--max-wthor-games` | 制限なし | 読み込むWTHORの最大棋譜数。動作確認用 |
| `--exclude-wthor` | 無効 | WTHORを除外し、自己対局だけで生成する |
| `--start-ply` | `8` | 採取を始める手数 |
| `--end-ply` | `52` | 採取を終える手数 |
| `--stride` | `1` | 何手ごとに局面を採取するか |
| `--seed` | `20260720` | 棋譜分割用シード |
| `--split` | `80,10,10` | 訓練・検証・テストの比率 |
| `--no-download` | 無効 | 既存の入力棋譜だけを使用する |
| `--overwrite` | 無効 | 既存の出力ディレクトリを作り直す |

すべてのオプションは次で確認できる。

```powershell
python -m training.build_dataset --help
```

### 小規模な動作確認用データ

最初の100局だけを別の場所へ出力する例である。

```powershell
python -m training.build_dataset `
  --max-games 100 `
  --max-wthor-games 100 `
  --output-dir .training/datasets/smoke `
  --overwrite
```

### ダウンロード済み棋譜から再生成

```powershell
python -m training.build_dataset --no-download --overwrite
```

`--overwrite`を指定すると、指定した`--output-dir`は削除してから再生成される。必要なモデルや独自データが同じディレクトリにないことを確認して使用する。

WTHORを除外した比較データを作る場合は、別の出力先を指定する。

```powershell
python -m training.build_dataset `
  --exclude-wthor `
  --output-dir .training/datasets/self-play-only-v2 `
  --overwrite
```

## 3. データセットの検証

```powershell
python -m training.verify_dataset
```

次の項目を検証する。

- `metadata.json`に記録されたSHA-256との一致
- 各配列の長さと形状
- 黒石と白石のビットボードが重なっていないこと
- 石数と手数の整合性
- パターンインデックスが`3^8`または`3^10`の範囲内であること
- 訓練・検証・テスト間に同一局面がないこと
- 抽出局面のモビリティ、フロンティア、パターン値の再計算結果

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
  --dataset-dir .training/datasets/combined-evaluation-v2 `
  --output-dir .training/models/pattern-evaluation-v1 `
  --epochs 20 `
  --batch-size 1024 `
  --learning-rate 0.001 `
  --l2 0.00001 `
  --patience 5 `
  --seed 20260720 `
  --label filled `
  --phase-starts 20,30,40,50 `
  --score-scale 6400
```

出力先がすでに存在する場合、誤上書きを防ぐため処理は停止する。作り直す場合だけ`--overwrite`を追加する。

### 学習モデル

各局面段階に次の共有ネットワークを持つ。

- 8マス対角線: `16入力 → 16 → 16 → 1`
- 辺と2個のXマス: `20入力 → 16 → 16 → 1`
- 角三角形: `20入力 → 16 → 16 → 1`
- モビリティと黒白フロンティア: `3入力 → 8 → 1`
- 活性化関数: `LeakyReLU(alpha=0.01)`
- 最終層: 4分岐の線形結合

学習にはAdam、平均二乗誤差、L2正則化を使用する。

### 局面段階

既定の`--phase-starts 20,30,40,50`では、データを次の4モデルへ割り当てる。

| フェーズ | 手数 |
|---|---|
| 0 | 29手目まで。20手未満もこのモデルへ含む |
| 1 | 30～39手目 |
| 2 | 40～49手目 |
| 3 | 50手目以降 |

後でJavaへ組み込む際は、境界付近で隣接フェーズを補間する。

### 教師値

`--label`で教師値を選択する。

| 値 | 内容 |
|---|---|
| `filled` | 勝者へ終局時の空きマスを加算した石差。既定値で、参照記事と同じ方式 |
| `disc` | 盤面上に実際に残った黒石数から白石数を引いた値 |

教師値は黒視点で`-64`から`64`までを取り、学習時は64で割って正規化する。Java探索へ組み込む際に手番側視点へ変換する。

### 学習オプション

| オプション | 既定値 | 内容 |
|---|---|---|
| `--dataset-dir` | `.training/datasets/combined-evaluation-v2` | 入力データセット |
| `--output-dir` | `.training/models/pattern-evaluation-v1` | モデル出力先 |
| `--epochs` | `20` | 最大エポック数 |
| `--batch-size` | `1024` | ミニバッチ局面数 |
| `--learning-rate` | `0.001` | Adam学習率 |
| `--l2` | `0.00001` | 重みのL2正則化係数 |
| `--patience` | `5` | 検証損失が改善しない場合の早期終了待機数 |
| `--seed` | `20260720` | 初期重みとシャッフルの乱数シード |
| `--label` | `filled` | 教師値の種類 |
| `--phase-starts` | `20,30,40,50` | 4フェーズの開始手数 |
| `--score-scale` | `6400` | Java用整数評価値への倍率 |
| `--max-samples-per-phase` | 制限なし | 各フェーズの最大局面数。指定時はスモーク扱い |
| `--overwrite` | 無効 | 既存の出力ディレクトリを作り直す |

すべてのオプションは次で確認できる。

```powershell
python -m training.train_model --help
```

## 6. 学習中の表示

各フェーズ・各エポックについて次が表示される。

```text
phase 0: {"epoch": 1, "train_mse": ..., "train_mae": ..., "validation_mse": ..., "validation_mae": ...}
```

- `train_mse`: 訓練集合の平均二乗誤差
- `train_mae`: 訓練集合の平均絶対誤差
- `validation_mse`: 検証集合の平均二乗誤差
- `validation_mae`: 検証集合の平均絶対誤差

`validation_mse`が`--patience`回連続で改善しない場合、そのフェーズは早期終了し、最も検証損失が低かった重みへ戻る。

訓練損失だけが下がり、検証損失が上がる場合は過学習の可能性がある。学習率、L2係数、パターン構成、データの重複や偏りを確認する。

## 7. 学習結果

本学習の出力は次のとおりである。

```text
.training/models/pattern-evaluation-v1/
├─ model-float.npz
├─ evaluation-tables.npz
└─ metadata.json
```

### `model-float.npz`

NumPy学習モデルの全重みを`float32`で保存する。再評価、量子化方法の変更、学習結果の分析に使用する。Javaクライアントが直接読むファイルではない。

### `evaluation-tables.npz`

すべてのパターン状態を事前計算した`int16`表を保存する。

- 対角線: `3^8 = 6,561`要素
- 辺と2X: `3^10 = 59,049`要素
- 角三角形: `3^10 = 59,049`要素
- 追加特徴: モビリティと黒白フロンティアの3次元表
- 各フェーズのバイアス

Java組み込み段階では、この内容をバージョン付きバイナリへ変換して読み込む。

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
- モデルの順伝播・逆伝播結果が有限値であること

## 9. 再現性と再実行

同じ公開棋譜、同じ分割シード、同じ採取条件を使用すると、同じデータセットNPZとSHA-256が生成される。WTHORは同名ファイルが更新される可能性があるため、定石生成時の3アーカイブのSHA-256をコードと`dataset-v2.json`に固定している。不一致時は生成を中止する。

学習も同じNumPy環境、乱数シード、学習条件では再現するよう設計している。ただし、NumPyやBLAS実装が異なる環境では浮動小数演算順序により末尾の値が異なる可能性がある。

現在の学習器は途中チェックポイントからの再開には対応していない。中断した場合は同じコマンドを`--overwrite`付きで再実行する。本学習結果を残したまま別条件を試す場合は、必ず別の`--output-dir`を指定する。

例:

```powershell
python -m training.train_model `
  --learning-rate 0.0005 `
  --l2 0.0001 `
  --output-dir .training/models/experiment-lr0005-l2-0001
```

## 10. 現在の適用範囲

この段階で完成しているのは、データ生成、検証、学習、テスト評価、整数表出力までである。

`evaluation-tables.npz`はまだJavaの`Evaluator`へ接続していない。既定AIは引き続き`v0.1.0`の手設計評価関数を使用する。次段階でバイナリ形式、Javaローダー、フェーズ補間、手番視点変換、フォールバック、速度測定、ベースライン対局比較を実装する。

## 出典とライセンス

公開棋譜とパターン形状の出典、固定コミット、利用条件は`THIRD_PARTY.md`を参照する。MITライセンス本文は`third_party/OthelloAI_Textbook-LICENSE.txt`に保存している。WTHORの生ZIP/WTBは再配布せず、ローカルの`.training/sources/wthor/`だけに保存する。
