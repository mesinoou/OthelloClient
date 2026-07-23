import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SearchEngine {

    public static final int MIN_ENDGAME_THRESHOLD = 12;
    public static final int MAX_ENDGAME_THRESHOLD = 18;
    public static final int MIN_WLD_THRESHOLD = 14;
    public static final int MAX_WLD_THRESHOLD = 20;

    private static final int INFINITY = 1_000_000;
    private static final int STOP_CHECK_MASK = 1023;
    private static final int PARALLEL_MINIMUM_DEPTH = 3;
    private static final int MINIMUM_TT_DEPTH = 2;
    private static final int LMR_MINIMUM_DEPTH = 5;
    private static final int LMR_MINIMUM_MOVE_INDEX = 4;
    private static final int ENDGAME_FALLBACK_DEPTH = 4;
    private static final int WLD_TRIAL_PERCENT = 65;
    private static final int MIN_STABILITY_EMPTIES = 5;
    private static final int MAX_EDGE_STABLE_DISCS = 28;
    private static final int ODD_REGION_BONUS = 1;
    private static final int NO_STABILITY_CUTOFF = Integer.MIN_VALUE;
    private static final int NO_MPC_CUTOFF = Integer.MIN_VALUE;
    private static final int MAX_EDGE_LOWER_SCORE =
        Evaluator.terminalScoreForDifference(
            2 * MAX_EDGE_STABLE_DISCS - 64
        );
    private static final int MIN_EDGE_UPPER_SCORE =
        Evaluator.terminalScoreForDifference(
            64 - 2 * MAX_EDGE_STABLE_DISCS
        );
    private static final long CORNERS = 0x8100000000000081L;

    private final PositionEvaluator evaluator;
    private final PositionEvaluator moveOrderingEvaluator;
    private final int moveOrderingMinimumDepth;
    private final TranspositionTable table;
    private final boolean endgameOrderingEnabled;
    private final int endgameThresholdOverride;
    private final boolean lmrEnabled;
    private final boolean exactLastNSolverEnabled;
    private final boolean stabilityCutoffEnabled;
    private final boolean multiProbCutEnabled;
    private final boolean wldEnabled;
    private final SearchContext context = new SearchContext();
    private final ThreadLocal<SearchContext> workerContexts =
        ThreadLocal.withInitial(SearchContext::new);
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean externalStopRequested = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final CopyOnWriteArrayList<Future<?>> activeTasks =
        new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Thread> workerThreads =
        new CopyOnWriteArrayList<>();
    private final ParallelMetrics parallelMetrics = new ParallelMetrics();
    private final Object poolLock = new Object();

    private ExecutorService workerPool;
    private int workerThreadCount;
    private long deadlineNanos;
    private boolean exactSearchActive;
    private boolean wldSearchActive;

    public SearchEngine() {
        this(new Evaluator(), new TranspositionTable(1 << 18));
    }

    SearchEngine(PositionEvaluator evaluator, TranspositionTable table) {
        this(evaluator, table, true);
    }

    SearchEngine(
        PositionEvaluator evaluator,
        TranspositionTable table,
        boolean endgameOrderingEnabled
    ) {
        this(evaluator, table, endgameOrderingEnabled, 0);
    }

    SearchEngine(
        PositionEvaluator evaluator,
        TranspositionTable table,
        boolean endgameOrderingEnabled,
        int endgameThresholdOverride
    ) {
        this(
            evaluator,
            table,
            endgameOrderingEnabled,
            endgameThresholdOverride,
            true
        );
    }

    SearchEngine(
        PositionEvaluator evaluator,
        TranspositionTable table,
        boolean endgameOrderingEnabled,
        int endgameThresholdOverride,
        boolean lmrEnabled
    ) {
        this(
            evaluator,
            table,
            endgameOrderingEnabled,
            endgameThresholdOverride,
            lmrEnabled,
            true
        );
    }

    SearchEngine(
        PositionEvaluator evaluator,
        TranspositionTable table,
        boolean endgameOrderingEnabled,
        int endgameThresholdOverride,
        boolean lmrEnabled,
        boolean exactLastNSolverEnabled
    ) {
        this(
            evaluator,
            table,
            endgameOrderingEnabled,
            endgameThresholdOverride,
            lmrEnabled,
            exactLastNSolverEnabled,
            true
        );
    }

    SearchEngine(
        PositionEvaluator evaluator,
        TranspositionTable table,
        boolean endgameOrderingEnabled,
        int endgameThresholdOverride,
        boolean lmrEnabled,
        boolean exactLastNSolverEnabled,
        boolean stabilityCutoffEnabled
    ) {
        this(
            evaluator,
            table,
            endgameOrderingEnabled,
            endgameThresholdOverride,
            lmrEnabled,
            exactLastNSolverEnabled,
            stabilityCutoffEnabled,
            true,
            true
        );
    }

    SearchEngine(
        PositionEvaluator evaluator,
        TranspositionTable table,
        boolean endgameOrderingEnabled,
        int endgameThresholdOverride,
        boolean lmrEnabled,
        boolean exactLastNSolverEnabled,
        boolean stabilityCutoffEnabled,
        boolean multiProbCutEnabled
    ) {
        this(
            evaluator,
            table,
            endgameOrderingEnabled,
            endgameThresholdOverride,
            lmrEnabled,
            exactLastNSolverEnabled,
            stabilityCutoffEnabled,
            multiProbCutEnabled,
            true
        );
    }

    SearchEngine(
        PositionEvaluator evaluator,
        TranspositionTable table,
        boolean endgameOrderingEnabled,
        int endgameThresholdOverride,
        boolean lmrEnabled,
        boolean exactLastNSolverEnabled,
        boolean stabilityCutoffEnabled,
        boolean multiProbCutEnabled,
        boolean wldEnabled
    ) {
        this(
            evaluator,
            table,
            endgameOrderingEnabled,
            endgameThresholdOverride,
            lmrEnabled,
            exactLastNSolverEnabled,
            stabilityCutoffEnabled,
            multiProbCutEnabled,
            wldEnabled,
            null,
            0
        );
    }

    SearchEngine(
        PositionEvaluator evaluator,
        TranspositionTable table,
        boolean endgameOrderingEnabled,
        int endgameThresholdOverride,
        boolean lmrEnabled,
        boolean exactLastNSolverEnabled,
        boolean stabilityCutoffEnabled,
        boolean multiProbCutEnabled,
        boolean wldEnabled,
        PositionEvaluator moveOrderingEvaluator,
        int moveOrderingMinimumDepth
    ) {
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        if (moveOrderingEvaluator == null) {
            if (moveOrderingMinimumDepth != 0) {
                throw new IllegalArgumentException(
                    "ordering depth requires an ordering evaluator"
                );
            }
        } else if (moveOrderingMinimumDepth < 1
            || moveOrderingMinimumDepth > 64) {
            throw new IllegalArgumentException(
                "ordering depth must be between 1 and 64"
            );
        }
        this.evaluator = evaluator;
        this.moveOrderingEvaluator = moveOrderingEvaluator;
        this.moveOrderingMinimumDepth = moveOrderingMinimumDepth;
        this.table = table;
        this.endgameOrderingEnabled = endgameOrderingEnabled;
        if (endgameThresholdOverride < 0
            || endgameThresholdOverride > MAX_WLD_THRESHOLD) {
            throw new IllegalArgumentException("invalid endgame threshold");
        }
        this.endgameThresholdOverride = endgameThresholdOverride;
        this.lmrEnabled = lmrEnabled;
        this.exactLastNSolverEnabled = exactLastNSolverEnabled;
        this.stabilityCutoffEnabled = stabilityCutoffEnabled;
        this.multiProbCutEnabled = multiProbCutEnabled
            && MultiProbCut.supports(evaluator);
        this.wldEnabled = wldEnabled;
    }

    public String evaluatorDescription() {
        return evaluator.description();
    }

    public synchronized SearchResult search(
        BitBoardPosition position,
        int color,
        SearchLimits limits
    ) {
        if (position == null) {
            throw new NullPointerException("position");
        }
        if (limits == null) {
            throw new NullPointerException("limits");
        }

        long player = position.player(color);
        long opponent = position.opponent(color);
        return search(player, opponent, limits);
    }

    public void stop() {
        externalStopRequested.set(true);
        stopRequested.set(true);
        cancelActiveTasks();
    }

    boolean hasTransposition(BitBoardPosition position, int color) {
        if (position == null) {
            throw new NullPointerException("position");
        }
        if (table == null) {
            return false;
        }
        long probe = table.probe(
            position.player(color),
            position.opponent(color)
        );
        if (TranspositionTable.probeFound(probe)) {
            return true;
        }
        long wldProbe = table.probe(
            position.player(color),
            position.opponent(color),
            TranspositionTable.WLD_MODE
        );
        return TranspositionTable.probeFound(wldProbe);
    }

    public void shutdown() {
        stop();
        synchronized (poolLock) {
            if (workerPool != null) {
                workerPool.shutdownNow();
                workerPool = null;
                workerThreadCount = 0;
                workerThreads.clear();
            }
        }
    }

    void prestartWorkerThreads() {
        synchronized (poolLock) {
            if (workerPool instanceof ThreadPoolExecutor) {
                ((ThreadPoolExecutor) workerPool).prestartAllCoreThreads();
            }
        }
    }

    Thread[] workerThreadsSnapshot() {
        return workerThreads.toArray(new Thread[0]);
    }

    private SearchResult search(
        long player,
        long opponent,
        SearchLimits limits
    ) {
        stopRequested.set(false);
        externalStopRequested.set(false);
        timedOut.set(false);
        exactSearchActive = false;
        wldSearchActive = false;
        activeTasks.clear();
        context.reset();
        parallelMetrics.reset();
        if (table != null) {
            table.newSearch();
        }

        long startNanos = System.nanoTime();
        long durationNanos = limits.timeMillis() >= Long.MAX_VALUE / 1_000_000L
            ? Long.MAX_VALUE / 4L
            : limits.timeMillis() * 1_000_000L;
        long hardDeadlineNanos = startNanos + durationNanos;
        deadlineNanos = hardDeadlineNanos;

        long legalMoves = BitBoard.legalMoves(player, opponent);
        boolean rootPass = legalMoves == 0L;
        if (rootPass && BitBoard.legalMoves(opponent, player) == 0L) {
            return result(
                -1,
                terminalScore(player, opponent),
                0,
                startNanos,
                false,
                true,
                0,
                false,
                false,
                0L,
                0L
            );
        }

        if (limits.threads() > 1) {
            ensureWorkerPool(limits.threads() - 1);
        }

        int bestSquare = rootPass
            ? -1
            : Long.numberOfTrailingZeros(legalMoves);
        int bestScore = evaluator.evaluate(player, opponent);
        int completedDepth = 0;
        boolean aborted = false;
        boolean exactSolution = false;
        boolean wldAttempted = false;
        boolean wldSolution = false;
        long wldNodes = 0L;
        long wldElapsedNanos = 0L;
        int emptyCount = BitBoard.countEmpty(player, opponent);
        int maximumDepth = Math.min(limits.maxDepth(), emptyCount);
        int endgameThreshold = endgameThresholdOverride > 0
            ? endgameThresholdOverride
            : wldEnabled
                ? wldThresholdFor(limits.timeMillis())
                : endgameThresholdFor(limits.timeMillis());
        boolean endgameSearch = emptyCount <= endgameThreshold
            && maximumDepth >= emptyCount;
        int endgameEmpties = endgameSearch ? emptyCount : 0;

        try {
            checkStop(true, context);
            int iterativeDepth = endgameSearch
                ? Math.min(ENDGAME_FALLBACK_DEPTH, emptyCount - 1)
                : maximumDepth;
            for (int depth = 1; depth <= iterativeDepth; depth++) {
                searchRootIteration(
                    player,
                    opponent,
                    legalMoves,
                    rootPass,
                    depth,
                    limits.threads(),
                    bestSquare
                );
                bestSquare = context.rootBestSquare;
                bestScore = context.rootScore;
                completedDepth = depth;
                checkStop(true, context);
            }

            if (endgameSearch && wldEnabled) {
                long trialDeadline = wldTrialDeadline(
                    startNanos,
                    durationNanos,
                    hardDeadlineNanos
                );
                if (System.nanoTime() - trialDeadline < 0L) {
                    wldAttempted = true;
                    long wldStarted = System.nanoTime();
                    long nodesBefore = totalNodes();
                    deadlineNanos = trialDeadline;
                    exactSearchActive = true;
                    wldSearchActive = true;
                    try {
                        searchRootIteration(
                            player,
                            opponent,
                            legalMoves,
                            rootPass,
                            emptyCount,
                            limits.threads(),
                            bestSquare
                        );
                        bestSquare = context.rootBestSquare;
                        bestScore = context.rootScore;
                        completedDepth = emptyCount;
                        exactSolution = true;
                        wldSolution = true;
                    } catch (SearchAbortedException abortedWld) {
                        boolean trialTimedOut = timedOut.get()
                            && !externalStopRequested.get()
                            && System.nanoTime() - hardDeadlineNanos < 0L;
                        if (!trialTimedOut) {
                            throw abortedWld;
                        }
                        cancelActiveTasks();
                        activeTasks.clear();
                        stopRequested.set(false);
                        timedOut.set(false);
                        context.timedOut = false;
                    } finally {
                        wldNodes += totalNodes() - nodesBefore;
                        wldElapsedNanos += System.nanoTime() - wldStarted;
                        exactSearchActive = false;
                        wldSearchActive = false;
                        deadlineNanos = hardDeadlineNanos;
                    }
                }

                if (!wldSolution) {
                    for (int depth = iterativeDepth + 1;
                        depth <= maximumDepth;
                        depth++) {
                        searchRootIteration(
                            player,
                            opponent,
                            legalMoves,
                            rootPass,
                            depth,
                            limits.threads(),
                            bestSquare
                        );
                        bestSquare = context.rootBestSquare;
                        bestScore = context.rootScore;
                        completedDepth = depth;
                        checkStop(true, context);
                    }
                }
            } else if (endgameSearch) {
                exactSearchActive = true;
                searchRootIteration(
                    player,
                    opponent,
                    legalMoves,
                    rootPass,
                    emptyCount,
                    limits.threads(),
                    bestSquare
                );
                bestSquare = context.rootBestSquare;
                bestScore = context.rootScore;
                completedDepth = emptyCount;
                exactSolution = true;
            } else if (completedDepth >= emptyCount) {
                exactSolution = true;
            }
        } catch (SearchAbortedException ignored) {
            aborted = true;
        } finally {
            exactSearchActive = false;
            wldSearchActive = false;
            deadlineNanos = hardDeadlineNanos;
            cancelActiveTasks();
            activeTasks.clear();
        }

        return result(
            bestSquare,
            bestScore,
            completedDepth,
            startNanos,
            aborted,
            exactSolution,
            endgameEmpties,
            wldAttempted,
            wldSolution,
            wldNodes,
            wldElapsedNanos
        );
    }

    private void searchRootIteration(
        long player,
        long opponent,
        long legalMoves,
        boolean rootPass,
        int depth,
        int threads,
        int previousBestSquare
    ) {
        if (rootPass) {
            searchPassedRoot(player, opponent, depth);
        } else if (shouldSearchParallel(legalMoves, depth, threads)) {
            searchRootParallel(
                player,
                opponent,
                depth,
                previousBestSquare
            );
        } else {
            searchRoot(player, opponent, depth, previousBestSquare);
        }
    }

    public static int endgameThresholdFor(long timeMillis) {
        if (timeMillis >= 20_000L) {
            return 18;
        }
        if (timeMillis >= 3_000L) {
            return 16;
        }
        if (timeMillis >= 1_000L) {
            return 14;
        }
        return MIN_ENDGAME_THRESHOLD;
    }

    public static int wldThresholdFor(long timeMillis) {
        if (timeMillis >= 8_000L) {
            return MAX_WLD_THRESHOLD;
        }
        if (timeMillis >= 3_000L) {
            return 18;
        }
        if (timeMillis >= 1_000L) {
            return 16;
        }
        return MIN_WLD_THRESHOLD;
    }

    static long wldTrialNanos(long durationNanos) {
        long whole = durationNanos / 100L;
        long remainder = durationNanos % 100L;
        return whole * WLD_TRIAL_PERCENT
            + remainder * WLD_TRIAL_PERCENT / 100L;
    }

    private static long wldTrialDeadline(
        long startNanos,
        long durationNanos,
        long hardDeadlineNanos
    ) {
        long candidate = startNanos + wldTrialNanos(durationNanos);
        return Math.min(candidate, hardDeadlineNanos);
    }

    private long totalNodes() {
        return context.nodes + parallelMetrics.nodes.get();
    }

    private SearchResult result(
        int bestSquare,
        int score,
        int completedDepth,
        long startNanos,
        boolean aborted,
        boolean exactSolution,
        int endgameEmpties,
        boolean wldAttempted,
        boolean wldSolution,
        long wldNodes,
        long wldElapsedNanos
    ) {
        long workerNodes = parallelMetrics.nodes.get();
        boolean searchTimedOut = timedOut.get();
        boolean stopped = aborted && !searchTimedOut;
        return new SearchResult(
            bestSquare,
            score,
            completedDepth,
            context.nodes + workerNodes,
            System.nanoTime() - startNanos,
            context.transpositionHits
                + parallelMetrics.transpositionHits.get(),
            context.betaCutoffs + parallelMetrics.betaCutoffs.get(),
            context.pvsResearches + parallelMetrics.pvsResearches.get(),
            context.lmrSearches + parallelMetrics.lmrSearches.get(),
            context.lmrResearches + parallelMetrics.lmrResearches.get(),
            workerNodes,
            parallelMetrics.tasks.get(),
            searchTimedOut,
            stopped,
            false,
            0,
            0,
            exactSolution,
            endgameEmpties,
            parallelMetrics.workerNodesSnapshot(),
            context.stabilityChecks
                + parallelMetrics.stabilityChecks.get(),
            context.stabilityCuts + parallelMetrics.stabilityCuts.get(),
            context.mpcAttempts + parallelMetrics.mpcAttempts.get(),
            context.mpcHighCuts + parallelMetrics.mpcHighCuts.get(),
            context.mpcLowCuts + parallelMetrics.mpcLowCuts.get(),
            context.mpcProbeNodes + parallelMetrics.mpcProbeNodes.get(),
            wldAttempted,
            wldSolution,
            wldNodes,
            wldElapsedNanos
        );
    }

    private boolean shouldSearchParallel(
        long legalMoves,
        int depth,
        int threads
    ) {
        return threads > 1
            && workerPool != null
            && depth >= PARALLEL_MINIMUM_DEPTH
            && BitBoard.count(legalMoves) > 2;
    }

    private void searchPassedRoot(
        long player,
        long opponent,
        int depth
    ) {
        checkStop(true, context);
        context.nodes++;
        int score = -searchRootChild(
            opponent,
            player,
            depth,
            -INFINITY,
            INFINITY,
            1,
            context
        );
        context.rootBestSquare = -1;
        context.rootScore = score;
        store(
            player,
            opponent,
            depth,
            score,
            TranspositionTable.EXACT,
            -1
        );
    }

    private void searchRoot(
        long player,
        long opponent,
        int depth,
        int previousBestSquare
    ) {
        checkStop(true, context);
        context.nodes++;

        int tableBestSquare = tableBestSquare(
            player,
            opponent,
            depth,
            context
        );
        long legalMoves = BitBoard.legalMoves(player, opponent);
        int moveCount = prepareMoves(
            player,
            opponent,
            legalMoves,
            depth,
            0,
            previousBestSquare,
            tableBestSquare,
            context
        );

        int alpha = -INFINITY;
        int beta = INFINITY;
        int bestScore = -INFINITY;
        int bestSquare = -1;
        int firstMoveScore = -INFINITY;

        for (int index = 0; index < moveCount; index++) {
            checkStop(true, context);
            long move = context.moves[0][index];
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);

            int score;
            if (index == 0) {
                score = -searchRootChild(
                    nextOpponent,
                    nextPlayer,
                    depth - 1,
                    -beta,
                    -alpha,
                    1,
                    context
                );
                firstMoveScore = score;
            } else {
                int probeAlpha = lmrEnabled ? firstMoveScore : alpha;
                score = -searchRootChild(
                    nextOpponent,
                    nextPlayer,
                    depth - 1,
                    -probeAlpha - 1,
                    -probeAlpha,
                    1,
                    context
                );
                if (score > probeAlpha && score < beta) {
                    context.pvsResearches++;
                    score = -searchRootChild(
                        nextOpponent,
                        nextPlayer,
                        depth - 1,
                        -beta,
                        -probeAlpha,
                        1,
                        context
                    );
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestSquare = Long.numberOfTrailingZeros(move);
            }
            if (score > alpha) {
                alpha = score;
            }
            if (wldSearchActive && bestScore == Evaluator.WIN_SCORE) {
                break;
            }
        }

        commitRootResult(
            player,
            opponent,
            depth,
            bestScore,
            bestSquare
        );
    }

    private void searchRootParallel(
        long player,
        long opponent,
        int depth,
        int previousBestSquare
    ) {
        checkStop(true, context);
        context.nodes++;

        int tableBestSquare = tableBestSquare(
            player,
            opponent,
            depth,
            context
        );
        long legalMoves = BitBoard.legalMoves(player, opponent);
        int moveCount = prepareMoves(
            player,
            opponent,
            legalMoves,
            depth,
            0,
            previousBestSquare,
            tableBestSquare,
            context
        );
        if (moveCount <= 2) {
            searchRoot(player, opponent, depth, previousBestSquare);
            return;
        }

        long firstMove = context.moves[0][0];
        int firstSquare = Long.numberOfTrailingZeros(firstMove);
        int firstScore = searchRootMove(
            player,
            opponent,
            firstMove,
            depth,
            -INFINITY,
            INFINITY,
            context
        );
        if (wldSearchActive && firstScore == Evaluator.WIN_SCORE) {
            commitRootResult(
                player,
                opponent,
                depth,
                firstScore,
                firstSquare
            );
            return;
        }
        AtomicLong sharedBest = new AtomicLong(
            packBest(firstScore, 0, firstSquare)
        );

        CountDownLatch workersDone = new CountDownLatch(moveCount - 1);
        List<Future<RootMoveResult>> futures = new ArrayList<>(moveCount - 1);
        List<WorkerCompletion> completions = new ArrayList<>(moveCount - 1);
        boolean completed = false;
        try {
            for (int index = 1; index < moveCount; index++) {
                final int moveIndex = index;
                final long move = context.moves[0][index];
                WorkerCompletion completion = new WorkerCompletion(
                    workersDone
                );
                completions.add(completion);
                Future<RootMoveResult> future = workerPool.submit(
                    () -> searchParallelRootMove(
                        player,
                        opponent,
                        move,
                        moveIndex,
                        depth,
                        firstScore,
                        sharedBest,
                        completion
                    )
                );
                futures.add(future);
                activeTasks.add(future);
            }

            for (Future<RootMoveResult> future : futures) {
                RootMoveResult moveResult = await(future);
                if (moveResult.aborted) {
                    stopRequested.set(true);
                    throw SearchAbortedException.INSTANCE;
                }
            }
            completed = true;
        } finally {
            if (!completed) {
                cancel(futures, completions);
            }
            awaitWorkers(workersDone);
            activeTasks.removeAll(futures);
        }

        long best = sharedBest.get();
        int bestScore = unpackBestScore(best);
        int bestSquare = unpackBestSquare(best);
        commitRootResult(
            player,
            opponent,
            depth,
            bestScore,
            bestSquare
        );
    }

    private RootMoveResult searchParallelRootMove(
        long player,
        long opponent,
        long move,
        int moveIndex,
        int depth,
        int probeAlpha,
        AtomicLong sharedBest,
        WorkerCompletion completion
    ) {
        if (!completion.start()) {
            return new RootMoveResult(true);
        }
        SearchContext workerContext = workerContexts.get();
        workerContext.reset();
        boolean aborted = false;
        try {
            checkStop(true, workerContext);
            int score = searchRootMove(
                player,
                opponent,
                move,
                depth,
                probeAlpha,
                probeAlpha + 1,
                workerContext
            );

            if (rootProbeFailedHigh(probeAlpha, score)) {
                workerContext.pvsResearches++;
                score = searchRootMove(
                    player,
                    opponent,
                    move,
                    depth,
                    probeAlpha,
                    INFINITY,
                    workerContext
                );
            }

            int square = Long.numberOfTrailingZeros(move);
            updateSharedBest(sharedBest, score, moveIndex, square);
        } catch (SearchAbortedException ignored) {
            aborted = true;
        } finally {
            parallelMetrics.add(workerContext);
            completion.finish();
        }
        return new RootMoveResult(aborted);
    }

    static boolean rootProbeFailedHigh(int probeAlpha, int score) {
        return score > probeAlpha;
    }

    private int searchRootMove(
        long player,
        long opponent,
        long move,
        int depth,
        int alpha,
        int beta,
        SearchContext searchContext
    ) {
        long flips = BitBoard.flips(player, opponent, move);
        long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
        long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
        return -searchRootChild(
            nextOpponent,
            nextPlayer,
            depth - 1,
            -beta,
            -alpha,
            1,
            searchContext
        );
    }

    private int searchRootChild(
        long player,
        long opponent,
        int depth,
        int alpha,
        int beta,
        int ply,
        SearchContext searchContext
    ) {
        if (depth >= 2
            && depth <= 4
            && exactSearchActive
            && exactLastNSolverEnabled) {
            countExactNode(searchContext);
            return solveExactLastN(
                player,
                opponent,
                depth,
                alpha,
                beta,
                searchContext
            );
        }
        return pvs(
            player,
            opponent,
            depth,
            alpha,
            beta,
            ply,
            searchContext
        );
    }

    private void commitRootResult(
        long player,
        long opponent,
        int depth,
        int bestScore,
        int bestSquare
    ) {
        context.rootBestSquare = bestSquare;
        context.rootScore = bestScore;
        store(
            player,
            opponent,
            depth,
            bestScore,
            TranspositionTable.EXACT,
            bestSquare
        );
    }

    private int pvs(
        long player,
        long opponent,
        int depth,
        int alpha,
        int beta,
        int ply,
        SearchContext searchContext
    ) {
        searchContext.nodes++;
        if ((searchContext.nodes & STOP_CHECK_MASK) == 0L) {
            checkStop(false, searchContext);
        }
        if (ply >= SearchContext.MAX_PLY) {
            return evaluator.evaluate(player, opponent);
        }
        if (specializedLeafDepth(depth)) {
            return searchLeaf(
                player,
                opponent,
                depth,
                alpha,
                beta,
                ply,
                searchContext,
                false
            );
        }

        int originalAlpha = alpha;
        int originalBeta = beta;
        boolean nullWindow = beta == alpha + 1;
        int tableBestSquare = -1;
        if (table != null && ttEligible(depth)) {
            long probe = table.probe(
                player,
                opponent,
                transpositionMode()
            );
            if (TranspositionTable.probeFound(probe)) {
                searchContext.transpositionHits++;
                tableBestSquare = TranspositionTable.probeBestSquare(probe);
                if (TranspositionTable.probeDepth(probe) >= depth) {
                    int tableValue = TranspositionTable.probeValue(probe);
                    byte bound = TranspositionTable.probeBound(probe);
                    if (bound == TranspositionTable.EXACT) {
                        return tableValue;
                    }
                    if (bound == TranspositionTable.LOWER_BOUND) {
                        alpha = Math.max(alpha, tableValue);
                    } else if (bound == TranspositionTable.UPPER_BOUND) {
                        beta = Math.min(beta, tableValue);
                    }
                    if (alpha >= beta) {
                        return tableValue;
                    }
                }
            }
        }

        int empties = BitBoard.countEmpty(player, opponent);
        if (stabilityCutoffEnabled
            && !wldSearchActive
            && stabilityEligible(
                exactSearchActive,
                empties,
                nullWindow,
                alpha,
                beta
            )) {
            int stabilityScore = stabilityCutoff(
                player,
                opponent,
                depth,
                alpha,
                beta,
                searchContext
            );
            if (stabilityScore != NO_STABILITY_CUTOFF) {
                return stabilityScore;
            }
        }

        if (depth == 4 && exactSearchActive && exactLastNSolverEnabled) {
            return solveExactLastN(
                player,
                opponent,
                depth,
                alpha,
                beta,
                searchContext
            );
        }

        long legalMoves = BitBoard.legalMoves(player, opponent);
        if (legalMoves == 0L) {
            if (BitBoard.legalMoves(opponent, player) == 0L) {
                int terminal = terminalScore(player, opponent);
                store(
                    player,
                    opponent,
                    depth,
                    terminal,
                    TranspositionTable.EXACT,
                    -1
                );
                return terminal;
            }

            int score = -pvs(
                opponent,
                player,
                depth,
                -beta,
                -alpha,
                ply + 1,
                searchContext
            );
            storeBound(
                player,
                opponent,
                depth,
                score,
                originalAlpha,
                originalBeta,
                -1
            );
            return score;
        }

        if (depth <= 0) {
            int value = evaluator.evaluate(player, opponent);
            store(
                player,
                opponent,
                0,
                value,
                TranspositionTable.EXACT,
                -1
            );
            return value;
        }

        int mpcScore = multiProbCut(
            player,
            opponent,
            depth,
            empties,
            alpha,
            beta,
            nullWindow,
            ply,
            searchContext
        );
        if (mpcScore != NO_MPC_CUTOFF) {
            return mpcScore;
        }

        if (empties == 1) {
            long move = legalMoves & -legalMoves;
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            int score = -terminalScore(
                nextOpponent,
                nextPlayer
            );
            storeBound(
                player,
                opponent,
                depth,
                score,
                originalAlpha,
                originalBeta,
                Long.numberOfTrailingZeros(move)
            );
            return score;
        }

        int orderingTableBestSquare = lmrEnabled
            && nullWindow
            && depth >= LMR_MINIMUM_DEPTH
            && empties > MAX_ENDGAME_THRESHOLD
                ? -1
                : tableBestSquare;
        int moveCount = prepareMoves(
            player,
            opponent,
            legalMoves,
            depth,
            ply,
            -1,
            orderingTableBestSquare,
            searchContext
        );
        int bestScore = -INFINITY;
        int bestSquare = -1;
        boolean hasUnverifiedReduction = false;

        for (int index = 0; index < moveCount; index++) {
            long move = searchContext.moves[ply][index];
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);

            int score;
            if (index == 0) {
                score = -pvs(
                    nextOpponent,
                    nextPlayer,
                    depth - 1,
                    -beta,
                    -alpha,
                    ply + 1,
                    searchContext
                );
            } else {
                boolean reduce = shouldReduceLateMove(
                    depth,
                    index,
                    move,
                    empties,
                    nullWindow,
                    nextPlayer,
                    nextOpponent
                );
                if (reduce) {
                    searchContext.lmrSearches++;
                    score = -pvs(
                        nextOpponent,
                        nextPlayer,
                        depth - 2,
                        -alpha - 1,
                        -alpha,
                        ply + 1,
                        searchContext
                    );
                    if (score > alpha) {
                        searchContext.lmrResearches++;
                        score = -pvs(
                            nextOpponent,
                            nextPlayer,
                            depth - 1,
                            -alpha - 1,
                            -alpha,
                            ply + 1,
                            searchContext
                        );
                    } else {
                        hasUnverifiedReduction = true;
                    }
                } else {
                    score = -pvs(
                        nextOpponent,
                        nextPlayer,
                        depth - 1,
                        -alpha - 1,
                        -alpha,
                        ply + 1,
                        searchContext
                    );
                }
                if (score > alpha && score < beta) {
                    searchContext.pvsResearches++;
                    score = -pvs(
                        nextOpponent,
                        nextPlayer,
                        depth - 1,
                        -beta,
                        -alpha,
                        ply + 1,
                        searchContext
                    );
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestSquare = Long.numberOfTrailingZeros(move);
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                searchContext.betaCutoffs++;
                break;
            }
            if (wldSearchActive && bestScore == Evaluator.WIN_SCORE) {
                break;
            }
        }

        if (lmrBoundCanBeStored(
            hasUnverifiedReduction,
            bestScore,
            originalBeta
        )) {
            storeBound(
                player,
                opponent,
                depth,
                bestScore,
                originalAlpha,
                originalBeta,
                bestSquare
            );
        }
        return bestScore;
    }

    private int multiProbCut(
        long player,
        long opponent,
        int depth,
        int empties,
        int alpha,
        int beta,
        boolean nullWindow,
        int ply,
        SearchContext searchContext
    ) {
        if (!multiProbCutEnabled
            || exactSearchActive
            || searchContext.mpcProbeActive
            || !nullWindow) {
            return NO_MPC_CUTOFF;
        }
        MultiProbCut.Parameters parameters = MultiProbCut.parametersFor(
            depth,
            empties
        );
        if (parameters == null) {
            return NO_MPC_CUTOFF;
        }

        searchContext.mpcAttempts++;
        long nodesBefore = searchContext.nodes;
        searchContext.mpcProbeActive = true;
        try {
            if (evaluator.evaluate(player, opponent) >= beta) {
                int highThreshold = MultiProbCut.failHighThreshold(
                    beta,
                    parameters
                );
                int highScore = pvs(
                    player,
                    opponent,
                    depth - 4,
                    highThreshold - 1,
                    highThreshold,
                    ply,
                    searchContext
                );
                if (highScore >= highThreshold) {
                    searchContext.mpcHighCuts++;
                    return beta;
                }
            } else {
                int lowThreshold = MultiProbCut.failLowThreshold(
                    alpha,
                    parameters
                );
                int lowScore = pvs(
                    player,
                    opponent,
                    depth - 4,
                    lowThreshold,
                    lowThreshold + 1,
                    ply,
                    searchContext
                );
                if (lowScore <= lowThreshold) {
                    searchContext.mpcLowCuts++;
                    return alpha;
                }
            }
            return NO_MPC_CUTOFF;
        } finally {
            searchContext.mpcProbeActive = false;
            searchContext.mpcProbeNodes += searchContext.nodes - nodesBefore;
        }
    }

    private int stabilityCutoff(
        long player,
        long opponent,
        int depth,
        int alpha,
        int beta,
        SearchContext searchContext
    ) {
        searchContext.stabilityChecks++;
        long bounds = stabilityScoreBounds(player, opponent);
        int lowerScore = stabilityLowerScore(bounds);
        if (lowerScore >= beta) {
            searchContext.stabilityCuts++;
            store(
                player,
                opponent,
                depth,
                lowerScore,
                TranspositionTable.LOWER_BOUND,
                -1
            );
            return lowerScore;
        }

        int upperScore = stabilityUpperScore(bounds);
        if (upperScore <= alpha) {
            searchContext.stabilityCuts++;
            store(
                player,
                opponent,
                depth,
                upperScore,
                TranspositionTable.UPPER_BOUND,
                -1
            );
            return upperScore;
        }
        return NO_STABILITY_CUTOFF;
    }

    private int solveExactLastN(
        long player,
        long opponent,
        int depth,
        int alpha,
        int beta,
        SearchContext searchContext
    ) {
        long empty = ~(player | opponent);
        if (depth == 4) {
            return solve4(
                player,
                opponent,
                empty,
                alpha,
                beta,
                false,
                searchContext
            );
        }
        if (depth == 3) {
            return solve3(
                player,
                opponent,
                empty,
                alpha,
                beta,
                false,
                searchContext
            );
        }
        return solve2(
            player,
            opponent,
            empty,
            alpha,
            beta,
            false,
            searchContext
        );
    }

    private int solve4(
        long player,
        long opponent,
        long empty,
        int alpha,
        int beta,
        boolean passed,
        SearchContext searchContext
    ) {
        int bestScore = -INFINITY;
        boolean hasLegalMove = false;
        long oddMoves = EndgameRegionAnalyzer.oddRegionMask(empty) & empty;
        long remaining = oddMoves != 0L ? oddMoves : empty;
        long deferred = empty & ~remaining;

        while (remaining != 0L || deferred != 0L) {
            if (remaining == 0L) {
                remaining = deferred;
                deferred = 0L;
            }
            long move = remaining & -remaining;
            remaining ^= move;
            long flips = BitBoard.flips(player, opponent, move);
            if (flips == 0L) {
                continue;
            }
            hasLegalMove = true;
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            countExactNode(searchContext);
            int score = -solve3(
                nextOpponent,
                nextPlayer,
                empty ^ move,
                -beta,
                -alpha,
                false,
                searchContext
            );
            bestScore = Math.max(bestScore, score);
            alpha = Math.max(alpha, score);
            if (wldSearchActive && bestScore == Evaluator.WIN_SCORE) {
                return bestScore;
            }
            if (alpha >= beta) {
                searchContext.betaCutoffs++;
                return bestScore;
            }
        }
        if (hasLegalMove) {
            return bestScore;
        }
        if (passed) {
            return terminalScore(player, opponent);
        }
        countExactNode(searchContext);
        return -solve4(
            opponent,
            player,
            empty,
            -beta,
            -alpha,
            true,
            searchContext
        );
    }

    private int solve3(
        long player,
        long opponent,
        long empty,
        int alpha,
        int beta,
        boolean passed,
        SearchContext searchContext
    ) {
        int bestScore = -INFINITY;
        boolean hasLegalMove = false;
        long remaining = empty;
        while (remaining != 0L) {
            long move = remaining & -remaining;
            remaining ^= move;
            long flips = BitBoard.flips(player, opponent, move);
            if (flips == 0L) {
                continue;
            }
            hasLegalMove = true;
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            countExactNode(searchContext);
            int score = -solve2(
                nextOpponent,
                nextPlayer,
                empty ^ move,
                -beta,
                -alpha,
                false,
                searchContext
            );
            bestScore = Math.max(bestScore, score);
            alpha = Math.max(alpha, score);
            if (wldSearchActive && bestScore == Evaluator.WIN_SCORE) {
                return bestScore;
            }
            if (alpha >= beta) {
                searchContext.betaCutoffs++;
                return bestScore;
            }
        }
        if (hasLegalMove) {
            return bestScore;
        }
        if (passed) {
            return terminalScore(player, opponent);
        }
        countExactNode(searchContext);
        return -solve3(
            opponent,
            player,
            empty,
            -beta,
            -alpha,
            true,
            searchContext
        );
    }

    private int solve2(
        long player,
        long opponent,
        long empty,
        int alpha,
        int beta,
        boolean passed,
        SearchContext searchContext
    ) {
        int bestScore = -INFINITY;
        boolean hasLegalMove = false;
        long remaining = empty;
        while (remaining != 0L) {
            long move = remaining & -remaining;
            remaining ^= move;
            long flips = BitBoard.flips(player, opponent, move);
            if (flips == 0L) {
                continue;
            }
            hasLegalMove = true;
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            countExactNode(searchContext);
            int score = -solve1(
                nextOpponent,
                nextPlayer,
                empty ^ move,
                -beta,
                -alpha,
                false,
                searchContext
            );
            bestScore = Math.max(bestScore, score);
            alpha = Math.max(alpha, score);
            if (wldSearchActive && bestScore == Evaluator.WIN_SCORE) {
                return bestScore;
            }
            if (alpha >= beta) {
                searchContext.betaCutoffs++;
                return bestScore;
            }
        }
        if (hasLegalMove) {
            return bestScore;
        }
        if (passed) {
            return terminalScore(player, opponent);
        }
        countExactNode(searchContext);
        return -solve2(
            opponent,
            player,
            empty,
            -beta,
            -alpha,
            true,
            searchContext
        );
    }

    private int solve1(
        long player,
        long opponent,
        long empty,
        int alpha,
        int beta,
        boolean passed,
        SearchContext searchContext
    ) {
        long move = empty & -empty;
        long flips = BitBoard.flips(player, opponent, move);
        if (flips != 0L) {
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            return -terminalScore(nextOpponent, nextPlayer);
        }
        if (passed) {
            return terminalScore(player, opponent);
        }
        countExactNode(searchContext);
        return -solve1(
            opponent,
            player,
            empty,
            -beta,
            -alpha,
            true,
            searchContext
        );
    }

    private void countExactNode(SearchContext searchContext) {
        searchContext.nodes++;
        if ((searchContext.nodes & STOP_CHECK_MASK) == 0L) {
            checkStop(false, searchContext);
        }
    }

    private int searchLeaf(
        long player,
        long opponent,
        int depth,
        int alpha,
        int beta,
        int ply,
        SearchContext searchContext,
        boolean countNode
    ) {
        if (countNode) {
            searchContext.nodes++;
            if ((searchContext.nodes & STOP_CHECK_MASK) == 0L) {
                checkStop(false, searchContext);
            }
        }
        if (ply >= SearchContext.MAX_PLY) {
            return evaluator.evaluate(player, opponent);
        }

        long legalMoves = BitBoard.legalMoves(player, opponent);
        if (legalMoves == 0L) {
            if (BitBoard.legalMoves(opponent, player) == 0L) {
                return terminalScore(player, opponent);
            }
            return -searchLeaf(
                opponent,
                player,
                depth,
                -beta,
                -alpha,
                ply + 1,
                searchContext,
                true
            );
        }

        if (depth <= 0) {
            return evaluator.evaluate(player, opponent);
        }

        if (BitBoard.countEmpty(player, opponent) == 1) {
            long move = legalMoves & -legalMoves;
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            return -terminalScore(nextOpponent, nextPlayer);
        }

        int bestScore = -INFINITY;
        long remaining = legalMoves;
        while (remaining != 0L) {
            long move = remaining & -remaining;
            remaining ^= move;
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            int score = -searchLeaf(
                nextOpponent,
                nextPlayer,
                0,
                -beta,
                -alpha,
                ply + 1,
                searchContext,
                true
            );

            bestScore = Math.max(bestScore, score);
            alpha = Math.max(alpha, score);
            if (alpha >= beta) {
                searchContext.betaCutoffs++;
                break;
            }
        }
        return bestScore;
    }

    static boolean specializedLeafDepth(int depth) {
        return depth <= 1;
    }

    static boolean exactLastNEligible(int depth, int empties) {
        return depth >= 2 && depth <= 4 && depth == empties;
    }

    static boolean stabilityEligible(
        boolean exactSearch,
        int empties,
        boolean nullWindow,
        int alpha,
        int beta
    ) {
        return exactSearch
            && empties >= MIN_STABILITY_EMPTIES
            && empties <= MAX_ENDGAME_THRESHOLD
            && nullWindow
            && (beta <= MAX_EDGE_LOWER_SCORE
                || alpha >= MIN_EDGE_UPPER_SCORE);
    }

    static long stabilityScoreBounds(long player, long opponent) {
        long occupied = player | opponent;
        int stablePlayer = Long.bitCount(
            Evaluator.stableEdgeDiscs(player, occupied)
        );
        int stableOpponent = Long.bitCount(
            Evaluator.stableEdgeDiscs(opponent, occupied)
        );
        int lowerScore = Evaluator.terminalScoreForDifference(
            2 * stablePlayer - 64
        );
        int upperScore = Evaluator.terminalScoreForDifference(
            64 - 2 * stableOpponent
        );
        return ((long) lowerScore << 32)
            | ((long) upperScore & 0xffff_ffffL);
    }

    static int stabilityLowerScore(long bounds) {
        return (int) (bounds >> 32);
    }

    static int stabilityUpperScore(long bounds) {
        return (int) bounds;
    }

    static int wldScoreForDifference(int difference) {
        if (difference > 0) {
            return Evaluator.WIN_SCORE;
        }
        if (difference < 0) {
            return -Evaluator.WIN_SCORE;
        }
        return 0;
    }

    private int terminalScore(long player, long opponent) {
        if (!wldSearchActive) {
            return evaluator.terminalScore(player, opponent);
        }
        return wldScoreForDifference(
            BitBoard.count(player) - BitBoard.count(opponent)
        );
    }

    static boolean lmrBoundCanBeStored(
        boolean hasUnverifiedReduction,
        int bestScore,
        int originalBeta
    ) {
        return !hasUnverifiedReduction || bestScore >= originalBeta;
    }

    static boolean ttEligible(int depth) {
        return depth >= MINIMUM_TT_DEPTH;
    }

    static boolean lmrEligible(
        int depth,
        int moveIndex,
        long move,
        int empties,
        boolean nullWindow,
        boolean opponentHasMove
    ) {
        return depth >= LMR_MINIMUM_DEPTH
            && moveIndex >= LMR_MINIMUM_MOVE_INDEX
            && (move & CORNERS) == 0L
            && empties > MAX_ENDGAME_THRESHOLD
            && nullWindow
            && opponentHasMove;
    }

    private boolean shouldReduceLateMove(
        int depth,
        int moveIndex,
        long move,
        int empties,
        boolean nullWindow,
        long nextPlayer,
        long nextOpponent
    ) {
        if (exactSearchActive || !lmrEnabled || !lmrEligible(
            depth,
            moveIndex,
            move,
            empties,
            nullWindow,
            true
        )) {
            return false;
        }
        return BitBoard.legalMoves(nextOpponent, nextPlayer) != 0L;
    }

    private int prepareMoves(
        long player,
        long opponent,
        long legalMoves,
        int depth,
        int ply,
        int preferredSquare,
        int tableBestSquare,
        SearchContext searchContext
    ) {
        int count = 0;
        long remaining = legalMoves;
        boolean endgameOrdering = endgameOrderingEnabled
            && BitBoard.countEmpty(player, opponent) <= MAX_WLD_THRESHOLD;
        boolean learnedOrdering = moveOrderingEvaluator != null
            && depth >= moveOrderingMinimumDepth
            && !exactSearchActive
            && BitBoard.countEmpty(player, opponent) > MAX_WLD_THRESHOLD;
        long oddRegions = endgameOrdering
            ? EndgameRegionAnalyzer.oddRegionMask(~(player | opponent))
            : 0L;
        while (remaining != 0L) {
            long move = remaining & -remaining;
            remaining ^= move;
            int square = Long.numberOfTrailingZeros(move);
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            long opponentMoves = BitBoard.legalMoves(
                nextOpponent,
                nextPlayer
            );
            int opponentMobility = BitBoard.count(opponentMoves);

            int priority = -100 * opponentMobility + BitBoard.count(flips);
            if (learnedOrdering) {
                priority -= moveOrderingEvaluator.evaluate(
                    nextOpponent,
                    nextPlayer
                );
            }
            if (endgameOrdering) {
                if ((move & oddRegions) != 0L) {
                    priority += ODD_REGION_BONUS;
                }
            }
            if ((move & CORNERS) != 0L) {
                priority += 100_000;
            }
            if (square == tableBestSquare) {
                priority += 500_000;
            }
            if (square == preferredSquare) {
                priority += 1_000_000;
            }

            searchContext.moves[ply][count] = move;
            searchContext.priorities[ply][count] = priority;
            count++;
        }

        for (int left = 0; left < count - 1; left++) {
            int best = left;
            for (int right = left + 1; right < count; right++) {
                if (searchContext.priorities[ply][right]
                    > searchContext.priorities[ply][best]) {
                    best = right;
                }
            }
            if (best != left) {
                int priority = searchContext.priorities[ply][left];
                searchContext.priorities[ply][left] =
                    searchContext.priorities[ply][best];
                searchContext.priorities[ply][best] = priority;

                long move = searchContext.moves[ply][left];
                searchContext.moves[ply][left] =
                    searchContext.moves[ply][best];
                searchContext.moves[ply][best] = move;
            }
        }
        return count;
    }

    private int tableBestSquare(
        long player,
        long opponent,
        int depth,
        SearchContext searchContext
    ) {
        if (table == null || !ttEligible(depth)) {
            return -1;
        }
        long probe = table.probe(
            player,
            opponent,
            transpositionMode()
        );
        if (!TranspositionTable.probeFound(probe)) {
            return -1;
        }
        searchContext.transpositionHits++;
        return TranspositionTable.probeBestSquare(probe);
    }

    private void storeBound(
        long player,
        long opponent,
        int depth,
        int score,
        int originalAlpha,
        int originalBeta,
        int bestSquare
    ) {
        byte bound;
        if (score <= originalAlpha) {
            bound = TranspositionTable.UPPER_BOUND;
        } else if (score >= originalBeta) {
            bound = TranspositionTable.LOWER_BOUND;
        } else {
            bound = TranspositionTable.EXACT;
        }
        store(player, opponent, depth, score, bound, bestSquare);
    }

    private void store(
        long player,
        long opponent,
        int depth,
        int score,
        byte bound,
        int bestSquare
    ) {
        if (table != null && ttEligible(depth)) {
            table.store(
                player,
                opponent,
                depth,
                score,
                bound,
                bestSquare,
                transpositionMode()
            );
        }
    }

    private byte transpositionMode() {
        return wldSearchActive
            ? TranspositionTable.WLD_MODE
            : TranspositionTable.SCORE_MODE;
    }

    private void checkStop(boolean force, SearchContext searchContext) {
        if (Thread.currentThread().isInterrupted() || stopRequested.get()) {
            throw SearchAbortedException.INSTANCE;
        }
        if (force || (searchContext.nodes & STOP_CHECK_MASK) == 0L) {
            if (System.nanoTime() - deadlineNanos >= 0L) {
                searchContext.timedOut = true;
                timedOut.set(true);
                throw SearchAbortedException.INSTANCE;
            }
        }
    }

    private void ensureWorkerPool(int requestedWorkers) {
        synchronized (poolLock) {
            if (workerPool != null && workerThreadCount == requestedWorkers) {
                return;
            }
            if (workerPool != null) {
                workerPool.shutdownNow();
            }
            workerThreads.clear();
            AtomicInteger workerId = new AtomicInteger();
            ThreadFactory factory = task -> {
                Thread thread = new Thread(
                    task,
                    "othello-search-worker-" + workerId.incrementAndGet()
                );
                thread.setDaemon(true);
                workerThreads.add(thread);
                return thread;
            };
            workerPool = Executors.newFixedThreadPool(
                requestedWorkers,
                factory
            );
            workerThreadCount = requestedWorkers;
        }
    }

    private void cancelActiveTasks() {
        for (Future<?> task : activeTasks) {
            task.cancel(true);
        }
    }

    private static void cancel(
        List<? extends Future<?>> futures,
        List<WorkerCompletion> completions
    ) {
        for (int index = 0; index < futures.size(); index++) {
            futures.get(index).cancel(true);
            completions.get(index).cancelBeforeStart();
        }
    }

    private static RootMoveResult await(Future<RootMoveResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SearchAbortedException.INSTANCE;
        } catch (CancellationException e) {
            throw SearchAbortedException.INSTANCE;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SearchAbortedException) {
                throw SearchAbortedException.INSTANCE;
            }
            throw new IllegalStateException("並列探索ワーカーが失敗しました。", cause);
        }
    }

    private static void awaitWorkers(CountDownLatch workersDone) {
        boolean interrupted = false;
        while (workersDone.getCount() != 0L) {
            try {
                workersDone.await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long packBest(int score, int moveIndex, int square) {
        return ((long) score << 32)
            | ((long) moveIndex & 0x00ff_ffffL) << 8
            | ((long) square & 0xffL);
    }

    private static int unpackBestScore(long best) {
        return (int) (best >> 32);
    }

    private static int unpackBestIndex(long best) {
        return (int) ((best >>> 8) & 0x00ff_ffffL);
    }

    private static int unpackBestSquare(long best) {
        return (int) (best & 0xffL);
    }

    private static void updateSharedBest(
        AtomicLong sharedBest,
        int score,
        int moveIndex,
        int square
    ) {
        while (true) {
            long current = sharedBest.get();
            int currentScore = unpackBestScore(current);
            int currentIndex = unpackBestIndex(current);
            if (score < currentScore
                || (score == currentScore && moveIndex >= currentIndex)) {
                return;
            }
            long updated = packBest(score, moveIndex, square);
            if (sharedBest.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    private static final class RootMoveResult {

        private final boolean aborted;

        private RootMoveResult(boolean aborted) {
            this.aborted = aborted;
        }
    }

    private static final class WorkerCompletion {

        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;
        private static final int CANCELLED_BEFORE_START = 3;

        private final CountDownLatch workersDone;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private WorkerCompletion(CountDownLatch workersDone) {
            this.workersDone = workersDone;
        }

        private boolean start() {
            return state.compareAndSet(QUEUED, RUNNING);
        }

        private void finish() {
            if (state.compareAndSet(RUNNING, FINISHED)) {
                workersDone.countDown();
            }
        }

        private void cancelBeforeStart() {
            if (state.compareAndSet(QUEUED, CANCELLED_BEFORE_START)) {
                workersDone.countDown();
            }
        }
    }

    private static final class ParallelMetrics {

        private final AtomicLong nodes = new AtomicLong();
        private final AtomicLong transpositionHits = new AtomicLong();
        private final AtomicLong betaCutoffs = new AtomicLong();
        private final AtomicLong pvsResearches = new AtomicLong();
        private final AtomicLong lmrSearches = new AtomicLong();
        private final AtomicLong lmrResearches = new AtomicLong();
        private final AtomicLong stabilityChecks = new AtomicLong();
        private final AtomicLong stabilityCuts = new AtomicLong();
        private final AtomicLong mpcAttempts = new AtomicLong();
        private final AtomicLong mpcHighCuts = new AtomicLong();
        private final AtomicLong mpcLowCuts = new AtomicLong();
        private final AtomicLong mpcProbeNodes = new AtomicLong();
        private final AtomicInteger tasks = new AtomicInteger();
        private final ConcurrentHashMap<String, AtomicLong> workerNodes =
            new ConcurrentHashMap<>();

        void reset() {
            nodes.set(0L);
            transpositionHits.set(0L);
            betaCutoffs.set(0L);
            pvsResearches.set(0L);
            lmrSearches.set(0L);
            lmrResearches.set(0L);
            stabilityChecks.set(0L);
            stabilityCuts.set(0L);
            mpcAttempts.set(0L);
            mpcHighCuts.set(0L);
            mpcLowCuts.set(0L);
            mpcProbeNodes.set(0L);
            tasks.set(0);
            workerNodes.clear();
        }

        void add(SearchContext searchContext) {
            nodes.addAndGet(searchContext.nodes);
            transpositionHits.addAndGet(searchContext.transpositionHits);
            betaCutoffs.addAndGet(searchContext.betaCutoffs);
            pvsResearches.addAndGet(searchContext.pvsResearches);
            lmrSearches.addAndGet(searchContext.lmrSearches);
            lmrResearches.addAndGet(searchContext.lmrResearches);
            stabilityChecks.addAndGet(searchContext.stabilityChecks);
            stabilityCuts.addAndGet(searchContext.stabilityCuts);
            mpcAttempts.addAndGet(searchContext.mpcAttempts);
            mpcHighCuts.addAndGet(searchContext.mpcHighCuts);
            mpcLowCuts.addAndGet(searchContext.mpcLowCuts);
            mpcProbeNodes.addAndGet(searchContext.mpcProbeNodes);
            tasks.incrementAndGet();
            workerNodes.computeIfAbsent(
                Thread.currentThread().getName(),
                ignored -> new AtomicLong()
            ).addAndGet(searchContext.nodes);
        }

        long[] workerNodesSnapshot() {
            return workerNodes.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .mapToLong(entry -> entry.getValue().get())
                .toArray();
        }
    }

    private static final class SearchAbortedException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private static final SearchAbortedException INSTANCE =
            new SearchAbortedException();

        private SearchAbortedException() {
            super(null, null, false, false);
        }
    }
}
