# Algorithm experiment roadmap

## Goal

現在の`baseline/lmr-20260721`から、1実験につき1つの探索仮説だけを追加し、`benchmark/TEST_PLAN.md`の停止条件に従って採否を決める。実施順は、探索結果を変えない低リスクな高速化、正確な枝刈り、選択的探索、並列探索の順とする。

過去に単独で不採用となったhistory heuristic、killer heuristic、aspiration windowは再実装しない。null-move pruningは、パスがありzugzwang的な局面も生じるOthelloでは安全性を保証しにくいため候補外とする。

## Execution order

| Order | ID | Algorithm | Expected effect | Result risk | Required levels |
|---:|---|---|---|---|---|
| 1 | SEARCH-007 | Shallow TT access gating | synchronized TTアクセス削減 | none | L0-L3, L5, L7 |
| 2 | EVAL-001 | Chunked ternary pattern indexing | 学習評価の高速化 | none | L0-L4, L6, L7 |
| 3 | SEARCH-008 | Exact last-N solver | 終盤完全読みの高速化 | none | L0-L3, L5, L7 |
| 4 | SEARCH-009 | Two-way bucket transposition table | 衝突削減とTT hit増加 | none | L0-L3, L5, L7 |
| 5 | SEARCH-010 | Enhanced Transposition Cutoff | 子局面TT boundによる枝刈り | none | L0-L3, L5, L7 |
| 6 | SEARCH-011 | Stability bound cutoff | 終盤の安全なwindow縮小 | none | L0-L3, L5, L7 |
| 7 | SEARCH-012 | Adaptive LMR | 後順位の低価値手を追加削減 | selective | L0-L5, L7 |
| 8 | SEARCH-013 | Calibrated Multi-ProbCut | 統計的な浅い探索による枝刈り | selective | L0-L6, L7 |
| 9 | SEARCH-014 | Interior YBWC split points | 不均衡な部分木で4T利用率向上 | scheduling | L0-L5, L7 |
| 10 | SEARCH-015 | Timed-search Lazy SMP helper | 反復深化中のTT先行生成 | selective scheduling | L0-L5, L7 |

各実験は、その時点の最新採用ベースラインから新しいブランチを作る。不採用実験の実装を次の候補へ引き継がない。前段が不採用でも、明示した依存関係がない次候補は実施できる。

## SEARCH-007: Shallow TT access gating

### Hypothesis

現在はdepth 0を含む全`pvs`ノードでstriped lock付きTTをprobeする。浅いノードでは再利用による節約よりhash、lock、pack処理の費用が大きい可能性がある。まず`depth >= 2`のノードだけprobe/storeし、終局値は従来どおり直接計算する。

```text
useTable = depth >= 2
if useTable:
    probe current position
...
if useTable:
    store completed bound
```

初期値は`minimumTtDepth=2`で固定する。閾値0・1・3は正式結果を見た後に追加しない。再試験する場合は別実験IDと別seedを使う。

### Decision data

- baselineと最善手・評価値が全suiteで一致
- `transpositionHits`, fixed-depth elapsed time, nodes/s, 500 ms到達深さ
- 4Tで到達深さ+0.25 ply、または固定深さ時間5%以上短縮を採用候補
- ノード増加が15%以上、またはDeep suiteで時間が悪化した場合は不採用

## EVAL-001: Chunked ternary pattern indexing

### Hypothesis

`LearnedEvaluator`は毎回64マスのstate配列を作り、各patternの各マスから3進indexを組み立てる。盤面を8 byteに分割し、byte内のplayer/opponent配置を`0..6560`の3進状態へ変換する表と、patternごとのchunk寄与表を事前計算すれば、同じ評価値を少ない分岐と乗算で得られる。

```text
byteState[playerByte | (opponentByte << 8)] -> ternaryByteIndex
patternIndex = sum(
    patternChunkContribution[pattern][chunk][ternaryByteIndex]
)
score += learnedTable[patternIndex]
```

重なったplayer/opponent bitは入力不正としてテストで拒否する。事前計算表はモデルから独立させ、JVM起動時に一度だけ構築する。目標追加メモリは8 MiB以下とする。

### Decision data

- 既存モデルの対称性・色反転を含む評価値完全一致
- `EvaluationBenchmark`でlearned評価時間15%以上短縮
- Standard/Validationで500 ms到達深さが悪化しない
- 起動時間、追加heap、4スレッド時の共有表アクセスも記録

## SEARCH-008: Exact last-N solver

### Hypothesis

完全読みの葉の大半を占める残り1〜4空きマスでは、汎用`pvs`のmove配列、selection sort、TT、反復的な合法手生成の費用が相対的に大きい。`solve1`から`solve4`までの専用関数へ分岐し、empty bitを直接試す。

```text
if exactSearch and empties <= 4:
    return solveN(player, opponent, empty, alpha, beta, passed)

solveN:
    for square in ordered empty bits:
        flips = flips(player, opponent, square)
        skip if flips == 0
        score = -solveNMinus1(nextOpponent, nextPlayer, ...)
        apply alpha-beta cutoff
    if no legal move:
        pass once or return terminal score
```

残り4手だけ既存`oddRegionMask`を使い、1・1・2の領域構成では奇数領域を先に試す。専用solver内ではLMRや評価関数を使わない。

### Decision data

- 0〜8空きのrandom positionと既存終盤問題で汎用PVSとの完全一致
- pass、連続pass、1空き未着手終局、wipeoutを個別テスト
- `EndgameSearchBenchmark`の12・14・16・18空きで4T時間10%以上短縮を目標
- 1〜4空き単体で遅くなる、または1件でもscore不一致なら不採用

## SEARCH-009: Two-way bucket transposition table

### Hypothesis

現行TTはdirect-mapped 1 entryであり、同一generationの深いentryと衝突すると浅い結果を捨てる一方、有用な別局面も失う。総entry数と概算memoryを維持したまま2-way set associativeにし、各bucketの2候補から置換先を決める。

```text
probe both slots in bucket
if same position: update according to depth and bound
else if empty slot: use it
else replace in this order:
    older generation
    shallower depth
    non-EXACT bound
    deterministic higher slot index
```

striped lockはbucket単位とする。世代、深さ、EXACT優先度以外の履歴heuristicは混ぜない。

### Decision data

- 同じ総entry数で比較し、heap増加は5%以内
- 最善手・評価値一致、parallel mismatch 0件
- TT hit率、collision/replacement counters、固定深さノード数
- 4Tノード5%以上削減、または500 ms到達深さ+0.25 ply

## SEARCH-010: Enhanced Transposition Cutoff

### Hypothesis

親局面がTTにない場合でも、合法手を適用した子局面の十分深いTT upper boundから、その手が親で確実にfail-highすると証明できる。初版は安全なbeta cutoffだけを実装し、全子のboundを組み合わせるfail-low判定は含めない。

```text
for move in legalMoves when depth >= 5:
    child = apply(move)
    entry = TT.probe(child, requiredDepth=depth-1)
    if entry is EXACT or UPPER_BOUND:
        parentLowerBound = -entry.value
        if parentLowerBound >= beta:
            store parent LOWER_BOUND
            return parentLowerBound
```

probe済み子局面の確定度をmove orderingへ使う変更は別実験とし、SEARCH-010には混ぜない。LMRでfull-depth未確認のboundは現行規則どおりETCへ利用しない。

### Decision data

- `etcProbes`, `etcHits`, `etcCutoffs`, probe当たり削減node
- 全固定深さsuiteでbaselineと最善手・評価値一致
- 4Tノード5%以上削減し、TT probe増加による時間悪化がない
- SEARCH-009採用時はその最新版をbaseとし、不採用なら現行TTで試す

## SEARCH-011: Stability bound cutoff

### Hypothesis

終盤完全読みでは、現在確定している安定石数から最終石差の安全な上下限を得られる。最初は高速に求められるedge stable discsだけを使い、探索windowを狭めるかalpha/beta cutoffする。

```text
lowerDisc = 2 * stablePlayer - 64
upperDisc = 64 - 2 * stableOpponent
lowerScore = terminalEncoding(lowerDisc)
upperScore = terminalEncoding(upperDisc)
alpha = max(alpha, lowerScore)
beta  = min(beta, upperScore)
cut if alpha >= beta
```

対象は完全読み中かつ空き5〜18、null window、window端が安定石boundに近い場合に限定する。より高価な内部安定石計算は初版へ入れない。

### Decision data

- 汎用完全読みとのscore完全一致
- `stabilityChecks`, `stabilityCuts`, 計算時間、削減node
- 12〜18空き終盤で4T時間5%以上短縮
- cutoff 0件または計算費用で時間が悪化する場合は不採用

## SEARCH-012: Adaptive LMR

### Hypothesis

採用済みLMRは全候補を一律1 ply削減する。深いnull-window nodeで、十分後順位かつ着手後の相手可動数が多い手だけ2 ply削減すれば、低い再探索率を維持したまま追加削減できる。

```text
reduction = 1
if depth >= 8 and moveIndex >= 8 and opponentMobility >= 6:
    reduction = 2
reducedDepth = max(0, depth - 1 - reduction)
if reduced score > alpha:
    re-search at full depth
```

現行の隅、相手pass、空き18以下、PV node除外を維持する。TT best、history、killerは条件に加えない。初期閾値`8/8/6`を正式測定前に固定する。

### Decision data

- reduction別の試行数、再探索率、誤削減により変化したroot結果
- 4Tノード10%以上削減、または500 ms到達深さ+0.50 ply
- Edax L7 100局で同時点baselineに対する非劣性
- 再探索率8%以上、Validation/Deepの一方だけに偏る効果は不採用

## SEARCH-013: Calibrated Multi-ProbCut

### Hypothesis

浅い探索値と深い探索値の相関を学習し、深いnull-window探索がfail-highまたはfail-lowする可能性が十分高い場合だけ浅い探索で枝を切る。固定マージンを手調整せず、学習用局面とholdout局面を分離する。

```text
deepScore = a[phase, depth] * shallowScore + b[phase, depth] + error
sigma = residualStdDev[phase, depth]

if shallowScore >= (beta - b + t * sigma) / a: fail high
if shallowScore <= (alpha - b - t * sigma) / a: fail low
```

初版は`depth >= 6`、`shallowDepth = depth - 2`、`t = 2.5`、非PV node、空き19以上に限定する。係数は学習データから生成したversioned binaryへ保存し、model SHA-256を結果へ残す。完全読みには適用しない。

### Decision data

- train/validation game単位分割、各phase/depthの`a`, `b`, `sigma`, sample数
- holdoutでfalse cut率1%未満を開始条件とする
- `mpcAttempts`, `mpcCuts`, verification sampleの誤差
- 4Tノード15%以上削減または到達深さ+0.75 plyとEdax非劣性を要求
- false cut率超過、特定phaseのsample不足、棋力悪化で不採用

## SEARCH-014: Interior YBWC split points

### Hypothesis

現行のroot-only並列化はルート合法手数が少ない局面や、一部の手だけが重い局面でthreadが遊ぶ。最初の手を逐次探索してwindowを狭めるYBWC split pointを浅い内部nodeにも追加する。

初版は`depth >= 7`, `ply <= 3`, `remainingMoves >= 4`で有効化する。split workerは新しいsplitを作らず、non-blocking permitを取れた場合だけ既存poolへ投入する。これによりnested waitによるdeadlockを避ける。

```text
search first move sequentially
if split conditions and worker permit available:
    publish immutable split state
    workers claim remaining move indices
    shared alpha only increases
    fail-high move is verified with current full window
else:
    continue sequential PVS
```

### Decision data

- 1・2・4・8Tの最善手・評価値一致、stress 3反復
- worker utilization、idle time、split数、再探索、parallel overhead
- 大会用4Tで500 ms到達深さ+0.50 plyまたは10秒条件の平均深さ+0.50 ply
- 1T不変、8T退行10%未満、timeout/stop latency 50 ms未満
- nondeterminism、deadlock、worker leakが1件でもあれば即不採用

## SEARCH-015: Timed-search Lazy SMP helper

### Hypothesis

YBWCを導入しても、反復深化の境界やルート手数が少ない局面では一部threadが待機する。時間制限探索に限り、1本の補助探索が主探索より先の局面を共有TTへ登録すれば、主探索の手順付けとcutoffを助けられる可能性がある。

主探索だけが最終着手を決定し、helperの評価値を直接採用しない。初版は4T以上でhelperを1本だけ起動し、主探索と異なる合法なroot orderingを使う。helperは主探索より深い探索へ進まず、stop要求を共有する。固定深さmodeでは無効化する。

```text
start canonical timed iterative deepening as authority
if threads >= 4 and one helper permit is available:
    helper searches same iterations with deterministic rotated root order
    helper stores only verified TT bounds
main search publishes the move from its deepest completed iteration
```

### Decision data

- 同じseedで主探索の結果が再現可能で、違法手・未完了iteration 0件
- helper node数、TT contribution、主探索のTT hit増加、stop latency
- 4Tの500 ms平均深さ+0.25 ply、または10秒平均深さ+0.50 ply
- 2Tでは無効、1T不変、8T退行10%未満
- helperなしのSEARCH-014採用版と直接比較し、固定深さ性能は採否に使わない
- SEARCH-014が不採用なら、現行root並列との組み合わせを別実験IDで再設計する

## Holdout policy

閾値を調整するSEARCH-012とSEARCH-013では、`20260721`を調整用、`20260722`をvalidation、未使用の`20260723`を最終holdoutとする。holdout結果を見た後のパラメータ変更は新しい実験IDで行う。Edax対局の50 opening pairも調整用と最終確認用でseedを分ける。

## Deferred ideas

- history、killer、aspirationの組み合わせ再試験: 個別効果が小さく、当面は行わない
- 内部安定石を含む完全stability計算: SEARCH-011のedge-only版にcut実績がある場合だけ検討
- 全子boundを使うETC fail-low: SEARCH-010の安全なfail-high版が採用された後に分離して検討
- 60 phase評価モデル、追加pattern、定石再生成: 探索実験と混ぜず、学習データ拡張後の別系列とする
- MCTS/PV-MCTSへの全面移行: 現行の学習評価、完全読み、PVS資産を活用できないため今回の系列外

## References

- Michael Buro, [Improving heuristic mini-max search by supervised learning](https://doi.org/10.1016/S0004-3702(01)00093-5), Artificial Intelligence 134, 2002.
- Egaroucid, [Technical Explanation](https://www.egaroucid.nyanyan.dev/en/technology/explanation/), ETC, stability cutoff, YBWC, Multi-ProbCut, final N-move optimization.
- Edax Reversi, [official source repository](https://github.com/abulmo/edax-reversi).
