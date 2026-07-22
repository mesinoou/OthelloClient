# OthelloClient

TCP通信でOthelloサーバーに接続し、ゲーム開始から終了まで自動対局するJavaクライアントである。`v1.0.0`は、学習済み評価と採用済み探索改良、実行環境自動調整、任意の相手手番探索を統合した最初の競技用基準版である。

## 現在の構成

- ビットボードによる盤面表現と合法手生成
- 反復深化alpha-beta探索、置換表、対称形正規化、ルート並列探索
- 盤面位置、確定石、可動性、潜在可動性、辺・隅などによる局面評価
- 6種の局所パターンと7種の追加特徴を事前計算表で参照する学習済み評価
- WTHOR棋譜と固定深さ教師探索から生成した18手目までの定石
- 勝率専用WLD終盤読みと、計算機が間に合わない場合の通常探索fallback
- 起動時の短時間診断による探索スレッド数と置換表容量の自動調整
- 明示的に有効化できる相手手番探索と自手番への共有TT handoff
- サーバーを使わず人間がAIと直接対局できる簡易Swing GUI
- Edax 4.6との比較対局、棋譜再生、探索ベンチマーク

各クラスは責務ごとに別ファイルへ分割されている。Javaではコンパイル後にクラス参照がバイトコード上で解決されるため、この分割が探索速度へ与える影響は実質的に無視できる。

## 必要環境

- JDK 11以上
- 配布元から取得した `OthelloServer.jar`

`OthelloServer.jar`は再配布禁止のため、このリポジトリには含めない。ローカル実行時はリポジトリ直下へ配置する。仮対戦用の `OthelloMonteAI.class`も追跡対象外としている。

## ビルド

PowerShellで次を実行する。

```powershell
New-Item -ItemType Directory -Force .build | Out-Null
javac --release 11 -encoding UTF-8 -Xlint:all -d .build *.java
```

## 人間とのGUI対局

サーバーを起動せず、次のコマンドだけでAIとの対局画面を開ける。

```powershell
java -cp .build OthelloGui
```

画面上部で人間側の色とAIの1手あたりの思考時間を選び、`新しい対局`を押す。盤上の黄色い印が合法手で、直前の人間の手からやり直す場合は`1手戻す`を使う。AIの探索は別スレッドで行うため、思考中も画面操作を妨げない。

GUIも通常クライアントと同じ定石、評価関数、探索、WLD終盤読みを使用する。既定の思考時間は遊びやすさを優先した1秒で、探索スレッド数とTT容量は起動時に計算機へ合わせて自動調整される。学習モデルが標準位置にない場合などは、次のように明示できる。

```powershell
java -cp .build OthelloGui `
  --color white --time-ms 2000 `
  --model data/evaluation-tables.bin `
  --threads auto --tt auto
```

全オプションは`java -cp .build OthelloGui --help`で確認できる。

## 対局

大会の1手10秒制限を有効にしてサーバーを起動する。

```powershell
java -jar OthelloServer.jar -port 25033 -timeout 10 -debug -trans
```

別のターミナルからクライアントを起動する。最後の `8000` は1手あたりの内部思考時間（ミリ秒）で、通信とスケジューリングの余裕を2秒確保する。

```powershell
java -cp .build OthelloClient 127.0.0.1 25033 Player auto 8000 `
  data/evaluation-tables.bin --tt auto `
  --ponder on --ponder-ratio 0.8
```

比較実験などで構成を固定する場合は従来どおり数値を指定する。

```powershell
java -cp .build OthelloClient 127.0.0.1 25033 Player 4 8000 `
  data/evaluation-tables.bin
```

引数は順に `host port nickname threads timeMillis evaluationModel` である。`threads`は正整数または`auto`で、省略時は`auto`となる。TT entry数は`--tt auto|N`で指定し、CLI、`-Dothello.tt.entries=N`、ヒープ上限からの自動算出の順で優先する。`N`には2の累乗を指定する。自動診断は接続前に最大2秒で完了し、選択したthreads、TT容量、各候補の測定値を標準出力へ表示する。

相手手番探索は`--ponder on|off`で指定し、既定値は安全側の`off`である。`--ponder-ratio R`は自手思考時間に対する上限比で、既定値は`0.8`、実時間上限は8,000 msとなる。自分のPUT後は更新済みBOARDを受信するまで開始せず、ponder結果からPUTを送信することはない。終了時の`相手手番探索集計`で停止p95、予測一致、TT hit、誤PUT数を確認できる。

第6引数を省略した場合は、`-Dothello.eval.file=<path>`、環境変数`OTHELLO_EVAL_FILE`、`data/evaluation-tables.bin`の順に探す。いずれも存在しなければ従来の手設計評価を使用する。起動時の`評価関数:`行で実際に選ばれた評価器を確認できる。

v1.0.0の性能検証に用いたモデルのSHA-256は`6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457`である。モデル本体はGit追跡外のため、`data/evaluation-tables.bin`へ配置し、`data/evaluation-model-v1.0.0.sha256`と照合する。

## Edaxとの比較

`benchmark/edax`には、GPLv3で公開されているEdax 4.6のWindows x86-64版とライセンス・出典を収録している。

```powershell
java -cp .build EdaxOthelloClient 127.0.0.1 25033 Edax46L6 6 1
```

評価関数を再現可能な複数局で比較する場合は`EvaluationMatchRunner`を使用する。定石を無効にし、固定シードから生成した8手オープニングごとに学習評価の先後を交換する。

```powershell
# 同一探索時間で従来評価と20局
java -cp .build EvaluationMatchRunner `
  data/evaluation-tables.bin handcrafted 10 8 100 64 1

# Edax 4.6 level 6と20局
java -cp .build EvaluationMatchRunner `
  data/evaluation-tables.bin edax 10 8 100 64 1 6

# 2つのモデルをMPCなし・固定深さで直接比較
java -cp .build EvaluationMatchRunner `
  data/evaluation-tables.bin `
  model=.training/models/candidate/evaluation-tables.bin `
  10 8 10000 8 1
```

引数は順に`model opponent pairs openingPlies timeMillis maxDepth threads edaxLevel openingSeed ponderMillis multiProbCut`である。`pairs=10`は各オープニングで先後2局、合計20局を表す。末尾の`multiProbCut`は省略時`true`で、`false`を指定するとモデル固有MPCの効果を除外できる。`opponent`へ`model=<path>`を指定すると、両者のMulti-ProbCutを強制的に無効化し、同じ探索条件で評価モデルだけを比較する。2026-07-21の本学習モデル評価は[benchmark/results/learned-e80-2026-07-21.md](benchmark/results/learned-e80-2026-07-21.md)に記録している。

## テスト

改良実験の実行順、変更種別ごとの必須項目、性能・棋力の合格条件は[benchmark/TEST_PLAN.md](benchmark/TEST_PLAN.md)にまとめている。v1.0.0の構成、最終性能、統計上の限界は[benchmark/results/release-v1.0.0-final-verification-2026-07-22.md](benchmark/results/release-v1.0.0-final-verification-2026-07-22.md)に記録している。次に試す探索・評価アルゴリズムの仕様と順序は[benchmark/ALGORITHM_ROADMAP.md](benchmark/ALGORITHM_ROADMAP.md)を参照する。以下は個別コマンドの参照用である。

ビルド後、次の回帰テストを実行する。

```powershell
$tests = @(
  "BitBoardTest",
  "GameReplayerTest",
  "EvaluatorTest",
  "LearnedEvaluatorTest",
  "OpeningBookTest",
  "EndgameRegionAnalyzerTest",
  "SearchEngineTest",
  "OthelloClientProtocolTest",
  "EdaxGtpEngineTest",
  "RuntimeConfigurationTest",
  "OthelloPonderingTest",
  "HumanVsAiGameTest"
)
foreach ($test in $tests) { java -cp .build $test }
```

実モデルの読込、色反転、盤面対称性を検証する。

```powershell
java -cp .build LearnedEvaluatorTest `
  .training/models/pattern-evaluation-v2/evaluation-tables.bin
```

評価速度を比較する場合は次を実行する。

```powershell
java -cp .build EvaluationBenchmark `
  .training/models/pattern-evaluation-v2/evaluation-tables.bin 3000000
```

同一局面に対する1、2、4、8スレッド探索を比較する場合は、並列探索ベンチマークを実行する。固定深さでは逐次探索と指し手・評価値が一致しないと失敗し、固定時間では到達深さとスループットを測定する。CSVにはコミットのdirty状態、モデルと局面集合のSHA-256、実行環境、ワーカー別ノード数を記録する。

```powershell
java -cp .build ParallelSearchBenchmark `
  --model data/evaluation-tables.bin `
  --mode all `
  --threads 1,2,4,8 `
  --positions 8 `
  --depth 9 `
  --time-ms 500 `
  --repetitions 2 `
  --output benchmark/results/parallel-search-v1-20260721.csv
```

既存の出力ファイルは誤上書きを防ぐため拒否される。試験的に置換する場合だけ`--overwrite`を指定する。全オプションは`java -cp .build ParallelSearchBenchmark --help`で確認できる。

置換表などの`synchronized`モニタ競合を調べる場合は`--contention-metrics`を追加する。JVMが記録したワーカースレッドの待機回数と待機時間がCSVの`workerMonitorBlocks`、`workerMonitorBlockedMillis`へ出力される。このオプションは計測用であり、通常の対局では使用しない。

終盤の石差完全読みとWLDを同一局面で比較する場合は次を用いる。引数は順に`empties repetitions timeMillis samples threads`である。

```powershell
java -cp .build WldEndgameBenchmark 20 1 8000 12 4
```

WLDは勝ち、引分、負けだけを最適化し、最終石差は比較しない。内部8,000 msでは20空きまでを候補とし、総予算の65%で証明できなければWLD途中値を捨て、残り時間で通常探索へ戻る。石差を順位やtie-breakに使う大会ではこの設定を使用しない。

## データと外部ソフトウェア

- `data/opening-book.bin`: Federation Francaise d'OthelloのWTHOR棋譜から生成した定石。生成条件は `data/data-version.txt` に記録している。
- `benchmark/edax`: Edax 4.6の未改変Windowsバイナリ、評価データ、問題データ。GNU GPL version 3。詳細は同ディレクトリの `README.md` と `LICENSE` を参照する。

## 評価関数の学習

`training`には、公開自己対局棋譜と公式WTHOR全年度からモデル非依存コーパスを作り、そこから6種の局所パターンと既存評価特徴を生成して小規模ニューラルネットを学習するPythonツールを収録している。全状態を整数表へ事前計算するため、対局時のJavaクライアントはPython、CUDA、ニューラルネット実行環境を必要としない。

```powershell
python -m training.build_corpus
python -m training.verify_corpus
python -m training.materialize_dataset
python -m training.verify_dataset
python -m training.train_model
```

現行コーパスは公開自己対局20,000局と公式WTHOR 137,548局を入力とし、合法終局できない古い記録413局を除外している。WTHORの年別件数と固定SHA-256は`training/wthor_sources.json`、生成済みv4の統計とハッシュは`training/dataset-v4.json`に記録している。旧v1/v2データセットの記録も互換性確認用に残している。ダウンロード元と利用条件は`training/THIRD_PARTY.md`を参照する。生棋譜、コーパス、派生データセット、学習モデルは`.training/`へ保存され、Gitの追跡対象には含まれない。詳しい操作は`training/README.md`に記載している。

## バージョン管理

- `main`: 対局と回帰テストが完了した基準版
- ベースライン: 評価済みコミットに`baseline/<名称>-<日付>`形式の注釈付きタグを付与
- 作業ブランチ: 改良仮説ごとに`codex/<実験名>`を作成し、不採用実験から次の実験を分岐しない
- 公開版: `VERSION`、`CHANGELOG.md`を更新し、`vX.Y.Z`形式の注釈付きタグを付与
- 比較実験: `benchmark/EXPERIMENTS.md`へ基準コミット、変更、測定条件、結果、採否を記録
- コミット: 計測基盤、アルゴリズム変更、評価結果を分離し、採用が決まるまで共有履歴をforce pushしない
- ローカルモデル: バイナリは追跡せず、モデルSHA-256と生成条件を評価結果へ記録

公開版は`v1.0.0`、現在の競技用探索ベースラインは`baseline/wld-endgame-20260722`、初回ネットワーククライアントのベースラインは`v0.1.0`である。
