# Improvement test plan

## Purpose

探索、評価関数、定石、通信の改良を同じ順序と判断基準で評価する。現在の比較基準は`baseline/lmr-20260721`である。各実験はこのタグ、またはその後に採用された最新ベースラインから分岐する。

測定後に条件を有利な方向へ変更しない。異なる条件を試す場合は予備測定として明記し、正式測定は最終実装コミットから取り直す。

## Test levels

### L0: Preparation

1. 実験ID、単一の変更仮説、基準タグ、作業ブランチを`benchmark/EXPERIMENTS.md`へ記録する。
2. `git status --short`が意図した差分だけであることを確認する。
3. モデルSHA-256、局面seed、Java、OS、CPU数を結果へ残す。
4. Java 11を対象に警告込みでコンパイルする。

```powershell
javac --release 11 -encoding UTF-8 -Xlint:all -d .build *.java
```

コンパイル失敗、モデル不一致、dirty状態を説明できない場合は測定へ進まない。

### L1: Required regression

すべての変更で次の9テストを実行する。

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
  "EdaxGtpEngineTest"
)
foreach ($test in $tests) { java -cp .build $test }
```

合格条件は9件すべてPASSである。失敗した実験は性能値にかかわらず停止する。

### L2: Search correctness and determinism

探索、置換表、並列化、手順付け、枝刈りを変更した場合は必須とする。

| Suite | Seed | Positions | Depth | Repetitions | Threads |
|---|---:|---:|---:|---:|---|
| Standard | 20260721 | 8 | 9 | 2 | 1,2,4,8 |
| Validation | 20260722 | 32 | 8 | 2 | 1,2,4,8 |
| Deep | 20260721 | 8 | 10 | 1 | 1,2,4,8 |

```powershell
java -cp .build ParallelSearchBenchmark `
  --model data/evaluation-tables.bin `
  --mode fixed `
  --threads 1,2,4,8 `
  --positions 32 `
  --seed 20260722 `
  --depth 8 `
  --repetitions 2 `
  --warmups 1 `
  --contention-metrics `
  --output .build/<experiment>-validation.csv
```

合格条件:

- 同じ実装の1・2・4・8スレッドで最善手と評価値の不一致0件
- 固定深さ未達、非合法手、ワーカーノード集計不一致0件
- 同じコミットとseedで再実行したときに結果が変わらない
- パス、終局、残り1手、完全読み閾値、timeout、外部停止を回帰テストで通過

並列探索、共有TT、選択的探索を変更した場合は、32局面・3反復のstress測定も追加する。選択的探索では旧基準との評価値一致は要求しないが、同じ候補実装のスレッド間一致は必須とする。

### L3: Performance gate

L2通過後に、固定深さのノード数と固定時間の到達深さを分けて測る。

```powershell
java -cp .build ParallelSearchBenchmark `
  --model data/evaluation-tables.bin `
  --mode all `
  --threads 1,2,4,8 `
  --positions 8 `
  --depth 9 `
  --time-ms 500 `
  --repetitions 2 `
  --warmups 1 `
  --contention-metrics `
  --output .build/<experiment>-standard.csv
```

主判定は大会用4スレッドとする。壁時計は同一実行の値を優先し、別日に測った値は参考扱いとする。

既定の性能ゲート:

- 正確な探索最適化: 4Tノード5%以上削減、または500 ms平均深さ0.25 ply以上改善
- 選択的探索: 4Tノード10%以上削減、または500 ms平均深さ0.50 ply以上改善
- ValidationとDeepの双方で重大な逆行がない
- 4Tを改善しても1Tまたは8Tが10%以上逆行する場合は保留

基準未達なら棋力対局を省略して不採用とする。閾値の例外は測定前に台帳へ記録する。

### L4: Strength gate

評価関数、定石、選択的探索、探索結果を変える変更で必須とする。性能だけを変え、固定深さ結果が旧基準と完全一致する変更では短縮できる。

1. Edax L7、20局で違法手、クラッシュ、大幅な棋力低下を予備確認する。
2. 通過後、Edax L7、100局を候補と最新基準の両方で同じPC・同じ時点に実行する。
3. 8手オープニングを50組生成し、各組で先後を交換する。
4. 定石は無効、100 ms/手、1スレッド、最大深さ64を標準条件とする。

```powershell
java -cp .build EvaluationMatchRunner `
  data/evaluation-tables.bin edax 50 8 100 64 1 7
```

記録項目:

- W-D-L、スコア率、平均石差
- 平均深さ、総ノード、nodes/s、timeout終了手、完全読み手
- 同一100局における候補の改善・同値・悪化数
- 先後2局をまとめた50組の改善・同値・悪化数
- 違法手、GTPエラー、未完了局

既定の非劣性条件は、同時点基準に対してスコア率が5ポイントを超えて低下せず、平均石差と先後ペア比較の双方が明確に悪化しないこととする。100局の小差だけで優越を断定しない。スコア率と平均石差がともに悪化した場合は追加対局または不採用とする。

### L5: Tournament-budget gate

採用候補は大会条件の10,000 ms/手、4スレッドで確認する。

```powershell
java -cp .build EvaluationMatchRunner `
  data/evaluation-tables.bin edax 1 8 10000 64 4 7
```

先後2局は棋力推定ではなく、時間管理、4スレッド動作、終盤完全読みへの移行を確認するsmoke testである。違法手、クラッシュ、10秒超過、未完了局は0件を必須とする。

大会前の最終候補では、実サーバを`-timeout 10 -debug -trans`で起動し、通信クライアント同士で最低2局完走させる。長時間の棋力確認が必要な場合は別計算機で10秒条件20局以上を実行する。

#### RUNTIME-001 configuration gate

- `-XX:ActiveProcessorCount=1,2,4,8`と`-Xmx64m,256m,1g`を組み合わせたsubprocess testを行う。
- auto選択値がlogical processor数、8 threads、TT memory budgetを超えないことを確認する。
- 明示threads、CLI TT、system property TTがそれぞれauto診断を正しく上書きすることを確認する。
- profile開始から設定確定まで2,000 ms以下、OOMとfallback後の起動失敗0件を必須とする。
- 診断用TTが実戦用TTへ混入せず、固定設定のbenchmark結果が変更されないことを確認する。
- 現PCとLinux本番機でauto構成と固定4Tを比較し、平均完了深さの明確な悪化があれば不採用とする。

#### CLIENT-001 ponder gate

- mock serverの相手待ち時間`0/50/500/2000/8000 ms`で、ponder開始・停止・自手探索handoffを確認する。
- 自分の`PUT`後、更新済み`BOARD`受信前のponder開始0件を必須とする。
- 相手手番PUT、二重PUT、違法手、停止後の遅延PUT、unfinished searchをすべて0件とする。
- changed `BOARD`受信からponder終了までのp95 latencyを50 ms未満とする。
- ponderあり・なしを同一opening、同一相手待ち時間で100局比較し、スコア率5 point超の低下、平均石差と到達深さの同時悪化がないことを確認する。
- `--ponder off`で既存通信・探索動作と一致し、通常の固定探索benchmarkへponderが混入しないことを確認する。
- 最終候補は実サーバ`-timeout 10 -debug -trans`、4 threads、`--ponder on`で先後2局以上を完走する。

### L6: Model and opening data

評価モデル、学習データ、特徴量、定石を変更した場合だけ追加する。

- `training.verify_dataset`でmanifest、件数、SHA-256を検証
- 学習履歴、best epoch、validation MSE/MAE、量子化前後誤差を保存
- `LearnedEvaluatorTest <model>`で実モデル読込、色反転、対称性、CRCを検証
- `EvaluationBenchmark`で評価速度と旧モデル比を記録
- 定石はentries、sourceGames、maximumPly、対象棋譜SHA-256を記録
- 定石あり・なしを分離して棋力測定し、探索改良の効果と混同しない

### L7: Integration verification

採用時は次の順序を守る。

1. 実装コミットと結果コミットを分離する。
2. 生CSV、対局ログ、比較表、失敗した予備条件を結果レポートへ保存する。
3. 実験ブランチをpushする。
4. 基準ブランチへ`--no-ff`で統合する。
5. 統合後にL0とL1を再実行する。
6. 成功したマージコミットへ`baseline/<name>-<date>`の注釈付きタグを付ける。
7. 基準ブランチとタグをpushし、worktreeがcleanであることを確認する。

## Required levels by change type

| Change type | Required levels |
|---|---|
| 文書、ログ、計測列だけ | L0, L1, L7 |
| 振る舞いを変えないリファクタ | L0, L1, L2, L7 |
| 正確な探索高速化、並列化、TT | L0-L3, L5, L7 |
| 選択的探索、枝刈り、探索深度変更 | L0-L5, L7 |
| 評価関数、学習モデル、定石 | L0-L7 |
| TCPプロトコル、時間管理 | L0, L1, L5, L7 |

## Stop conditions

次の条件を一つでも満たした時点で、その段階より高コストな試験へ進まない。

- コンパイルまたは回帰テスト失敗
- 固定深さのスレッド間不一致、非合法手、未完了探索
- 事前に定めた性能ゲート未達
- Edax予備20局でクラッシュ、違法手、明白な棋力低下
- 大会条件で10秒超過、通信エラー、完全読みへの移行失敗

## Artifact naming

成果物名は`<experiment-id>-<slug>-<suite>-YYYY-MM-DD.<ext>`とし、既存ファイルを上書きしない。

- `standard.csv`: 標準固定深さ・固定時間
- `validation.csv`: 別seed局面
- `depth10.csv`: 深い固定探索
- `edax-l7-100.txt`: 棋力ゲート
- `tournament.txt`: 10秒・4T smoke test
- `<experiment-id>-<slug>-YYYY-MM-DD.md`: 採否レポート

正式CSVには最終実装コミット、モデルSHA-256、局面集合SHA-256、seed、実行環境を含める。予備測定は`.build/`へ置き、採用・不採用の根拠に使った正式成果物だけを`benchmark/results/`へ保存する。
