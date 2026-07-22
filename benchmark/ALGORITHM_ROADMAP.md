# Algorithm experiment roadmap

## Goal

現在の`baseline/multi-probcut-20260721`から、1実験につき1つの探索仮説だけを追加し、`benchmark/TEST_PLAN.md`の停止条件に従って採否を決める。実施順は、探索結果を変えない低リスクな高速化、正確な枝刈り、選択的探索、実行環境適応、相手手番探索、並列探索の順とする。

過去に単独で不採用となったhistory heuristic、killer heuristic、aspiration windowは再実装しない。null-move pruningは、パスがありzugzwang的な局面も生じるOthelloでは安全性を保証しにくいため候補外とする。

## User-proposed improvements

| Proposal | Assessment | Experiment |
|---|---|---|
| 相手手番待ち時間の活用 | 探索時間を実質的に追加できる有力案。通信処理と探索を分離し、結果ではなく共有TTだけを再利用する | CLIENT-001 |
| 実行環境診断 | 本番機でのthread過不足と固定TT容量を補正できる。明示設定を常に優先し、接続前だけ診断する | RUNTIME-001 |
| 葉ノード探索の簡略化 | 通常探索depth 0/1の高頻度overheadを除く案として採用。終盤last-N solverとは別実験にする | SEARCH-016 |
| ハッシュテーブル自前実装 | 現在もprimitive配列、独自hash、striped lockの自前TTである。浅いaccess省略と2-way bucket化を先に評価する | SEARCH-007, SEARCH-009 |

相手手番探索は規則上許可されていることを大会前に再確認し、規則変更時は設定一つで無効化できるようにする。環境診断とponderは性能測定条件を変えるため、通常の固定thread benchmarkでは無効化する。

## Execution order

| Order | ID | Algorithm | Expected effect | Result risk | Required levels |
|---:|---|---|---|---|---|
| 1 | SEARCH-007 | Shallow TT access gating | synchronized TTアクセス削減 | none | L0-L3, L5, L7 |
| 2 | SEARCH-016 | Specialized depth-0/1 leaf search | 通常探索の葉処理簡略化 | none | L0-L3, L5, L7 |
| 3 | EVAL-001 | Chunked ternary pattern indexing | 学習評価の高速化 | none | L0-L4, L6, L7 |
| 4 | SEARCH-008 | Exact last-N solver | 終盤完全読みの高速化 | none | L0-L3, L5, L7 |
| 5 | SEARCH-009 | Two-way bucket transposition table | 衝突削減とTT hit増加 | none | L0-L3, L5, L7 |
| 6 | SEARCH-010 | Enhanced Transposition Cutoff | 子局面TT boundによる枝刈り | none | L0-L3, L5, L7 |
| 7 | SEARCH-011 | Stability bound cutoff | 終盤の安全なwindow縮小 | none | L0-L3, L5, L7 |
| 8 | SEARCH-012 | Adaptive LMR | 後順位の低価値手を追加削減 | selective | L0-L5, L7 |
| 9 | SEARCH-013 | Calibrated Multi-ProbCut | 統計的な浅い探索による枝刈り | selective | L0-L6, L7 |
| 10 | RUNTIME-001 | Environment profiling and auto-sizing | 本番CPU・heapへの適応 | configuration | L0, L1, L3, L5, L7 |
| 11 | CLIENT-001 | Opponent-turn pondering | 相手思考時間で共有TTを予熱 | timing | L0, L1, L4, L5, L7 |
| 12 | SEARCH-017 | WLD endgame proof | 勝率に不要な終局石差比較を省略 | none for outcome | L0-L5, L7 |
| 13 | SEARCH-014 | Interior YBWC split points | 不均衡な部分木で4T利用率向上 | scheduling | L0-L5, L7 |
| 14 | SEARCH-015 | Timed-search Lazy SMP helper | 反復深化中のTT先行生成 | selective scheduling | L0-L5, L7 |

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

## SEARCH-016: Specialized depth-0/1 leaf search

### Hypothesis

通常探索のdepth 0と1は呼び出し回数が多いが、現在は汎用`pvs`を通り、TT、move配列、priority計算、selection sort、再帰呼び出しの費用を負う。SEARCH-007採用後の浅いTT省略を前提に、同じpass・終局規則を持つ専用関数へ分岐する。

```text
if depth == 0:
    return evaluateLeafWithPassAndTerminal(player, opponent)
if depth == 1:
    return searchOnePlyLeaf(player, opponent, alpha, beta)

searchOnePlyLeaf:
    iterate legal move bits without move arrays or sorting
    apply move
    evaluate child with pass and terminal handling
    apply alpha-beta cutoff
```

depth 0でも、手番側に合法手がなく相手に合法手がある場合は視点を反転して評価する。両者に合法手がなければ`terminalScore`を返す。この既存意味論を変えず、手順付けheuristicやLMRは追加しない。

### Decision data

- pass、連続pass、wipeout、通常葉を含む汎用PVSとの評価値完全一致
- `leaf0Calls`, `leaf1Calls`, leaf当たり時間、評価呼出し数
- Standard/Validation/Deepで最善手・評価値一致
- 4T固定深さ時間10%以上短縮、または500 ms到達深さ+0.25 ply
- 葉の評価呼出しが15%以上増える場合は、不採用または軽量手順付けを別IDで検討

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

## RUNTIME-001: Environment profiling and auto-sizing

### Hypothesis

本番計算機ではlogical processor数、heap上限、memory帯域が異なる。現在の既定値`availableProcessors() - 1`と固定`2^18` entry TTでは、CPUを使い切れない場合とheapに対してTTが小さすぎる場合がある。接続前に環境を診断し、明示指定がない項目だけを保守的に自動選択する。

```text
profile = {
    logicalProcessors,
    maxHeapBytes,
    osName,
    osArch,
    javaVersion
}
ttBudget = clamp(maxHeapBytes / 16, 8 MiB, 128 MiB)
ttEntries = largestPowerOfTwoFitting(ttBudget)
threadCandidates = powersOfTwoUpTo(min(logicalProcessors, 8))
```

`threads=auto`のときだけ、接続前の固定局面で候補thread数を短時間測定し、500 ms相当の到達深さ、同深さならelapsed timeで選ぶ。測定上限は合計2秒とする。数値を明示した場合は必ずその値を優先し、自動調整を無効化する。TT容量はCLIまたはsystem propertyで上書き可能にする。

### Frozen implementation contract (2026-07-22)

- Baseは`baseline/multi-probcut-20260721`、実験branchは`codex/runtime-auto-sizing`とする。
- 自動調整対象は総探索thread数とTT entry数だけとする。思考時間、完全読み閾値、評価関数、定石、LMR、MPC係数は変更しない。
- 既存の第4位置引数は正整数に加えて`auto`を受け付け、省略時も`auto`とする。正整数を指定した場合はthread測定を実行しない。
- TTは`--tt auto|N`で指定する。優先順位はCLI、`othello.tt.entries` system property、自動算出の順とする。`N`は2の累乗だけを許可する。
- 自動TT予算は`maxHeap / 16`を基本とし、8 MiB以上、128 MiB以下、かつ`maxHeap / 4`以下に制限する。現行配列構成の概算31 bytes/entryと配列headerを含め、予算内に収まる最大の2の累乗を選ぶ。
- thread候補はlogical processor数以下の`1,2,4,8`とする。候補が同等なら少ないthreadを選び、通信処理とOSへ余裕を残す。
- 診断は固定した中盤局面、定石なし、最終TTとは別の一時engine/tableで行う。JIT warmupを含む全診断のwall timeを2,000 ms以下に制限し、診断TTを実戦へ持ち込まない。
- 順位は完了深度、同深度なら固定深さelapsed time、差が5%未満なら少ないthreadの順とする。
- 診断失敗時は`min(4, logicalProcessors)` threadsと`2^18` entriesへfallbackし、理由を標準エラーへ出す。
- 起動時にprofile、全候補測定値、採用値、TT推定bytes、各値がautoかexplicitかを表示する。
- `ParallelSearchBenchmark`、`EvaluationMatchRunner`などの正式測定APIには自動設定を入れず、明示されたthreadsとTTを維持する。

### Decision data

- 起動時にprofile、候補測定値、採用threads、TT entry数、推定TT bytesを表示
- `-XX:ActiveProcessorCount=1,2,4,8`と`-Xmx64m,256m,1g`の組み合わせをテスト
- 選択された構成でOOM、過剰thread、2秒超過がない
- 現PCとLinux本番機の双方で、固定4T設定より明確に悪化する構成を選ばない
- 正式探索benchmarkでは従来どおりthreadsとTT容量を固定し、自動選択を混ぜない

## CLIENT-001: Opponent-turn pondering

### Hypothesis

大会規則が相手手番中の計算を許可しているため、`TURN opponent`と最新`BOARD`を受信した時点から相手視点で探索し、共有TTへ予想応手とその子局面を登録できる。相手の実着手を受信したら即座に停止し、自手番探索は温まった同じTTを再利用する。

```text
on opponent TURN with a valid board:
    start ponder search as opponent
    ponderBudget = min(8000 ms, ownMoveBudget * 0.80)
    never send PUT from ponder result

on changed BOARD, own TURN, END, ERROR, or CLOSE:
    request ponder stop
    wait for controller handoff
    start authoritative own-turn search
```

`OthelloAI`の同じ`SearchEngine`をsingle search controller上で使い、探索間でTTだけを保持する。定石が見つかってもponderを即終了せず、通常探索を行って定石後の部分木を生成する。自手番の着手は従来のauthoritative searchだけが送信する。

### Frozen implementation contract (2026-07-22)

- Baseは採用済みRUNTIME-001、実験branchは`codex/opponent-turn-pondering`とする。RUNTIME-001が不採用なら`baseline/multi-probcut-20260721`から分岐する。
- `--ponder on|off`を追加し、初期実装と最初の統合では既定値を`off`とする。大会起動例では明示的に`--ponder on`を指定する。既定値変更は別の決定として扱う。
- `--ponder-ratio R`の初期値を`0.80`、許容範囲を`0 < R <= 1`とする。ponder budgetは`min(8000 ms, floor(ownMoveBudget * R))`とし、計算機診断でこの比率を変更しない。
- ponder threadsはauthoritative searchと同じ採用thread数を使う。ponder専用thread増減は初回実験へ含めない。
- 自分の`PUT`送信直後は盤面をstaleとし、サーバから更新済み`BOARD`を受信するまでponderを開始しない。古い盤面から自分の着手を推測適用する処理は入れない。
- 最新`BOARD`と相手色の`TURN`がそろった場合だけ相手視点で通常探索を開始する。盤面が未受信またはstaleなら`BOARD`を要求し、探索しない。
- ponderはopening bookを迂回する専用入口から`SearchEngine.search`を呼び、結果の手は記録だけして破棄する。ネットワーク送信権限はauthoritative searchだけが持つ。
- changed `BOARD`、own `TURN`、`END`、`ERROR`、`CLOSE`でstopを要求する。既存single-thread search controllerと同期化された`SearchEngine.search`により、ponder停止後だけauthoritative searchを開始する。
- 探索間で再利用するのは同じTransposition Tableだけとし、root result、deadline、stop state、探索counterは毎回初期化する。
- 開始数、完了数、中断数、時間、node、深さ、予測相手手一致率、handoff latency、自手探索の初期TT hit、誤PUT数を記録する。
- MPCを含むTT boundの再利用、誤予測によるTT汚染、連続CPU使用によるclock低下は対局gateで判定し、悪化時のratio/thread調整は別実験IDに分ける。

### Decision data

- ponder開始数、実時間、探索node、完了深さ、予想相手手一致率
- 自手番開始時のTT hit増分、到達深さ、着手送信までの時間
- 相手待ち時間0/50/500/2000/8000 msのmock server試験
- `BOARD`受信からponder停止まで50 ms未満、PUT二重送信・相手手番PUT 0件
- ponderあり・なしを同じserver棋譜と相手待ち時間で100局比較
- CPU温度やclock低下で自手番探索が悪化する場合は割合を別IDで再評価

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

## SEARCH-017: WLD endgame proof

### Hypothesis

大会順位が勝敗だけで決まり石差を使わない場合、終盤探索値を勝ち・引分・負けの3値へ限定すると、同じ勝敗となる終局石差の比較を省略できる。既存PVS、終盤4手solver、parity orderingは再利用し、WLD中は選択的なLMRとMulti-ProbCutを無効にする。

初期設定は`1000 ms未満=14空き`、`1000 ms以上=16空き`、`3000 ms以上=18空き`、大会内部時間`8000 ms以上=20空き`とする。通常探索depth 4を先に確保し、WLD試行は総時間の65%で打ち切る。証明できない計算機・難局面では残り35%を通常の反復深化へ戻し、WLD途中値は採用しない。

TTは追加配列を持たず、WLD盤面キーを補数化して通常評価値と分離する。WLDは理論上の最大値である勝ちを見つけた時点で、そのnodeの残り手を探索しない。

### Decision data

- 2〜10空き54局面で石差完全読みの符号と一致し、1T・4TのWLD結果不一致0件
- 12・14・16・18・20空きの固定局面で勝敗不一致0件
- 8秒4Tで18空き12/12、20空き11/12以上をWLD完読
- WLD失敗時の合法手、総時間上限、通常探索復帰を確認
- Edax L7 100局は同じopening seedのv1.0.0比較に対してスコア率5 point超の低下を不採用条件とする。平均石差は記録するが採否には使わない
- 8秒または10秒4Tの先後2局で違法手、未完了、時間超過0件

### Result

採用。8秒4Tで12〜20空き60/60をWLD完読し、比較可能54局面の勝敗不一致0。20空きは平均596 ms、最大2,483 msだった。Edax L7同一100局は46.5%でv1.0.0の44.5%に対して+2.0 point、実サーバ2局もWLD 40/40、timeoutと誤PUT 0で完走した。詳細は`benchmark/results/search-017-wld-endgame-search-2026-07-22.md`を参照する。

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
- compact/stamped lock-free TT: 現在の`TranspositionTable`はすでにprimitive配列の自前実装である。SEARCH-007とSEARCH-009後もJFRでTT accessがCPU時間の5%以上を占める場合だけ、seqlock方式を新しい実験IDへ昇格する
- 内部安定石を含む完全stability計算: SEARCH-011のedge-only版にcut実績がある場合だけ検討
- 全子boundを使うETC fail-low: SEARCH-010の安全なfail-high版が採用された後に分離して検討
- 60 phase評価モデル、追加pattern、定石再生成: 探索実験と混ぜず、学習データ拡張後の別系列とする
- MCTS/PV-MCTSへの全面移行: 現行の学習評価、完全読み、PVS資産を活用できないため今回の系列外

## References

- Michael Buro, [Improving heuristic mini-max search by supervised learning](https://doi.org/10.1016/S0004-3702(01)00093-5), Artificial Intelligence 134, 2002.
- Egaroucid, [Technical Explanation](https://www.egaroucid.nyanyan.dev/en/technology/explanation/), ETC, stability cutoff, YBWC, Multi-ProbCut, final N-move optimization.
- Edax Reversi, [official source repository](https://github.com/abulmo/edax-reversi).
