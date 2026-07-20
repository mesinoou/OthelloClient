# OthelloClient

TCP通信でOthelloサーバーに接続し、ゲーム開始から終了まで自動対局するJavaクライアントである。`v0.1.0`は、今後の探索・評価関数・定石改良を比較するための初回ベースラインとする。

## 現在の構成

- ビットボードによる盤面表現と合法手生成
- 反復深化alpha-beta探索、置換表、対称形正規化、ルート並列探索
- 盤面位置、確定石、可動性、潜在可動性、辺・隅などによる局面評価
- WTHOR棋譜と固定深さ教師探索から生成した18手目までの定石
- 空きマス数と残り時間に応じた終盤完全読み
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

## 対局

大会の1手10秒制限を有効にしてサーバーを起動する。

```powershell
java -jar OthelloServer.jar -port 25033 -timeout 10 -debug -trans
```

別のターミナルからクライアントを起動する。最後の `8000` は1手あたりの内部思考時間（ミリ秒）で、通信とスケジューリングの余裕を2秒確保する。

```powershell
java -cp .build OthelloClient 127.0.0.1 25033 Player 4 8000
```

引数は順に `host port nickname threads timeMillis` である。

## Edaxとの比較

`benchmark/edax`には、GPLv3で公開されているEdax 4.6のWindows x86-64版とライセンス・出典を収録している。

```powershell
java -cp .build EdaxOthelloClient 127.0.0.1 25033 Edax46L6 6 1
```

## テスト

ビルド後、次の回帰テストを実行する。

```powershell
$tests = @(
  "BitBoardTest",
  "GameReplayerTest",
  "EvaluatorTest",
  "OpeningBookTest",
  "EndgameRegionAnalyzerTest",
  "SearchEngineTest",
  "OthelloClientProtocolTest",
  "EdaxGtpEngineTest"
)
foreach ($test in $tests) { java -cp .build $test }
```

終盤探索のみを測定する場合は次を用いる。

```powershell
java -cp .build EndgameSearchBenchmark 16 2 8000 12 4
```

## データと外部ソフトウェア

- `data/opening-book.bin`: Federation Francaise d'OthelloのWTHOR棋譜から生成した定石。生成条件は `data/data-version.txt` に記録している。
- `benchmark/edax`: Edax 4.6の未改変Windowsバイナリ、評価データ、問題データ。GNU GPL version 3。詳細は同ディレクトリの `README.md` と `LICENSE` を参照する。

## 評価関数の学習

`training`には、公開自己対局棋譜と定石生成に用いたWTHOR棋譜から学習データを生成し、記事方式の局所パターンモデルを学習するPythonツールを収録している。対角線、辺とXマス、角三角形の3種パターンを小規模ニューラルネットで学習し、全状態を整数表へ事前計算するため、対局時のJavaクライアントはPythonやニューラルネット実行環境を必要としない。

```powershell
python -m training.build_dataset
python -m training.verify_dataset
python -m training.train_model
```

公開自己対局20,000局とWTHOR 58,252局から生成した`combined-evaluation-v2`の再現条件、局面数、SHA-256は`training/dataset-v2.json`に記録している。旧自己対局版の記録は`training/dataset-v1.json`に残している。ダウンロード元と利用条件は`training/THIRD_PARTY.md`を参照する。生棋譜、生成データセット、学習モデルは`.training/`へ保存され、Gitの追跡対象には含まれない。詳しい操作は`training/README.md`に記載している。

## バージョン管理

- `main`: 対局と回帰テストが完了した基準版
- 作業ブランチ: 改良ごとに `feature/<内容>` または `experiment/<内容>` を作成
- 公開版: `VERSION`、`CHANGELOG.md`を更新し、`vX.Y.Z`形式の注釈付きタグを付与
- 比較実験: 対戦条件、乱数・定石条件、勝敗をコミットまたはIssueへ記録

初回ベースラインは `v0.1.0` である。
