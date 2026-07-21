import java.util.concurrent.atomic.AtomicReference;

public final class SearchEngineTest {

    private static final Evaluator EVALUATOR = new Evaluator();

    private SearchEngineTest() {
    }

    public static void main(String[] args) throws Exception {
        testAgainstReferenceNegamax();
        testEndgameThresholdSelection();
        testTranspositionTableConsistency();
        testRootProbeResearchDecision();
        testKillerMoveTracking();
        testParallelMatchesSequential();
        testExactEndgame();
        testExactEndgameAtThreshold();
        testEndgameTimeoutFallback();
        testParallelEndgameTimeout();
        testPassAndTerminalPositions();
        testTimeLimitReturnsLegalFallback();
        testExternalStop();
        testParallelExternalStop();
        System.out.println("SearchEngineTest: PASS");
    }

    private static void testAgainstReferenceNegamax() {
        BitBoardPosition position = BitBoardPosition.initial();
        int depth = 4;
        int expected = referenceNegamax(
            position.black(),
            position.white(),
            depth
        );

        SearchEngine engine = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 14)
        );
        SearchResult result = engine.search(
            position,
            1,
            new SearchLimits(10_000L, depth, 1)
        );

        assertEquals(depth, result.completedDepth(), "completed depth");
        assertEquals(expected, result.score(), "reference score");
        assertLegalBestMove(position, 1, result.bestSquare());
        assertEquals(
            expected,
            referenceMoveScore(position, 1, result.bestSquare(), depth),
            "best move score"
        );
    }

    private static void testEndgameThresholdSelection() {
        assertEquals(12, SearchEngine.endgameThresholdFor(999L), "999ms threshold");
        assertEquals(14, SearchEngine.endgameThresholdFor(1_000L), "1s threshold");
        assertEquals(16, SearchEngine.endgameThresholdFor(3_000L), "3s threshold");
        assertEquals(16, SearchEngine.endgameThresholdFor(8_000L), "8s threshold");
        assertEquals(18, SearchEngine.endgameThresholdFor(20_000L), "20s threshold");
    }

    private static void testTranspositionTableConsistency() {
        BitBoardPosition position = BitBoardPosition.initial();
        SearchLimits limits = new SearchLimits(10_000L, 5, 1);
        SearchEngine withoutTable = new SearchEngine(EVALUATOR, null);
        SearchEngine withTable = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 15)
        );

        SearchResult plain = withoutTable.search(position, 1, limits);
        SearchResult cached = withTable.search(position, 1, limits);
        SearchResult repeated = withTable.search(position, 1, limits);

        assertEquals(plain.score(), cached.score(), "TT score");
        assertEquals(cached.score(), repeated.score(), "repeat score");
        assertEquals(
            cached.bestSquare(),
            repeated.bestSquare(),
            "repeat best square"
        );
        if (cached.transpositionHits() == 0L) {
            throw new AssertionError("置換表が一度も参照されていません。");
        }
    }

    private static void testParallelMatchesSequential() {
        PositionToMove[] positions = {
            new PositionToMove(BitBoardPosition.initial(), 1),
            createMidgame(10),
            createMidgame(18)
        };

        for (int index = 0; index < positions.length; index++) {
            PositionToMove sample = positions[index];
            int depth = 5;
            SearchEngine sequentialEngine = new SearchEngine();
            SearchEngine parallelEngine = new SearchEngine();
            SearchResult sequential = sequentialEngine.search(
                sample.position,
                sample.color,
                new SearchLimits(10_000L, depth, 1)
            );
            SearchResult parallel = parallelEngine.search(
                sample.position,
                sample.color,
                new SearchLimits(10_000L, depth, 4)
            );

            assertEquals(
                sequential.completedDepth(),
                parallel.completedDepth(),
                "parallel completed depth " + index
            );
            assertEquals(
                sequential.score(),
                parallel.score(),
                "parallel score " + index
            );
            assertLegalBestMove(
                sample.position,
                sample.color,
                parallel.bestSquare()
            );
            assertEquals(
                sequential.score(),
                referenceMoveScore(
                    sample.position,
                    sample.color,
                    parallel.bestSquare(),
                    depth
                ),
                "parallel best move score " + index
            );
            if (parallel.parallelTasks() == 0
                || parallel.parallelNodes() == 0L) {
                throw new AssertionError(
                    "並列探索ワーカーが使用されていません: sample=" + index
                );
            }
            long measuredWorkerNodes = 0L;
            for (long workerNodes : parallel.parallelWorkerNodes()) {
                measuredWorkerNodes += workerNodes;
            }
            if (measuredWorkerNodes != parallel.parallelNodes()) {
                throw new AssertionError(
                    "ワーカー別ノード数の合計が一致しません: sample="
                        + index
                );
            }
            sequentialEngine.shutdown();
            parallelEngine.shutdown();
        }
    }

    private static void testRootProbeResearchDecision() {
        if (!SearchEngine.rootProbeFailedHigh(100, 101)) {
            throw new AssertionError(
                "探索に使用したalphaを超えた手が再探索されません。"
            );
        }
        if (SearchEngine.rootProbeFailedHigh(100, 100)) {
            throw new AssertionError(
                "fail-highしていない手が再探索されます。"
            );
        }
    }

    private static void testKillerMoveTracking() {
        SearchContext context = new SearchContext();
        context.reset();

        context.recordKiller(5, 12);
        context.recordKiller(5, 20);
        assertEquals(20, context.killerSquares[5][0], "primary killer");
        assertEquals(12, context.killerSquares[5][1], "secondary killer");

        context.recordKiller(5, 20);
        assertEquals(20, context.killerSquares[5][0], "duplicate killer");
        assertEquals(12, context.killerSquares[5][1], "duplicate secondary");

        context.reset();
        assertEquals(-1, context.killerSquares[5][0], "reset primary killer");
        assertEquals(-1, context.killerSquares[5][1], "reset secondary killer");
    }

    private static void testExactEndgame() {
        PositionToMove endgame = createEndgame(6);
        int empties = endgame.position.emptyCount();
        int expected = referenceNegamax(
            endgame.position.player(endgame.color),
            endgame.position.opponent(endgame.color),
            empties
        );

        SearchResult result = new SearchEngine().search(
            endgame.position,
            endgame.color,
            new SearchLimits(10_000L, empties, 1)
        );
        assertEquals(empties, result.completedDepth(), "exact depth");
        assertEquals(expected, result.score(), "exact endgame score");
        assertLegalBestMove(endgame.position, endgame.color, result.bestSquare());
        if (!result.exactSolution()) {
            throw new AssertionError("完全読み結果として記録されていません。");
        }
        assertEquals(empties, result.endgameEmpties(), "endgame empties");
    }

    private static void testExactEndgameAtThreshold() {
        int threshold = SearchEngine.MIN_ENDGAME_THRESHOLD;
        PositionToMove endgame = createEndgame(threshold);
        int empties = endgame.position.emptyCount();
        SearchLimits sequentialLimits = new SearchLimits(20_000L, 64, 1);
        SearchLimits parallelLimits = new SearchLimits(20_000L, 64, 4);
        SearchEngine sequentialEngine = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 18),
            true,
            threshold
        );
        SearchEngine parallelEngine = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 18),
            true,
            threshold
        );
        SearchEngine genericOrderingEngine = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 18),
            false,
            threshold
        );

        SearchResult sequential = sequentialEngine.search(
            endgame.position,
            endgame.color,
            sequentialLimits
        );
        SearchResult parallel = parallelEngine.search(
            endgame.position,
            endgame.color,
            parallelLimits
        );
        SearchResult genericOrdering = genericOrderingEngine.search(
            endgame.position,
            endgame.color,
            sequentialLimits
        );

        assertEquals(
            threshold,
            empties,
            "threshold position empties"
        );
        assertEquals(empties, sequential.completedDepth(), "threshold depth");
        assertEquals(sequential.score(), parallel.score(), "parallel exact score");
        assertEquals(
            sequential.score(),
            genericOrdering.score(),
            "endgame ordering exact score"
        );
        if (!sequential.exactSolution() || !parallel.exactSolution()) {
            throw new AssertionError("12空きの完全読みが完了していません。");
        }
        assertLegalBestMove(
            endgame.position,
            endgame.color,
            sequential.bestSquare()
        );
        assertLegalBestMove(
            endgame.position,
            endgame.color,
            parallel.bestSquare()
        );
        sequentialEngine.shutdown();
        parallelEngine.shutdown();
        genericOrderingEngine.shutdown();
    }

    private static void testEndgameTimeoutFallback() {
        int threshold = SearchEngine.MIN_ENDGAME_THRESHOLD;
        PositionToMove endgame = createEndgame(threshold);
        SearchEngine engine = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 18),
            true,
            threshold
        );
        SearchResult result = engine.search(
            endgame.position,
            endgame.color,
            new SearchLimits(1L, 64, 1)
        );

        assertLegalBestMove(endgame.position, endgame.color, result.bestSquare());
        assertEquals(
            threshold,
            result.endgameEmpties(),
            "timeout endgame empties"
        );
        if (!result.exactSolution() && !result.timedOut()) {
            throw new AssertionError("未完了完全読みの時間切れが記録されていません。");
        }
    }

    private static void testParallelEndgameTimeout() {
        int threshold = SearchEngine.MIN_ENDGAME_THRESHOLD;
        PositionToMove endgame = createEndgame(threshold);
        for (int attempt = 0; attempt < 3; attempt++) {
            SearchEngine engine = new SearchEngine(
                EVALUATOR,
                new TranspositionTable(1 << 18),
                true,
                threshold
            );
            long startNanos = System.nanoTime();
            SearchResult result = engine.search(
                endgame.position,
                endgame.color,
                new SearchLimits(2L, 64, 2)
            );
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            engine.shutdown();

            assertLegalBestMove(
                endgame.position,
                endgame.color,
                result.bestSquare()
            );
            if (!result.exactSolution() && !result.timedOut()) {
                throw new AssertionError(
                    "並列完全読みの時間切れが記録されていません。"
                );
            }
            if (elapsedMillis > 2_000L) {
                throw new AssertionError(
                    "並列完全読みの取消に時間がかかりすぎました: "
                        + elapsedMillis + "ms"
                );
            }
        }
    }

    private static void testPassAndTerminalPositions() {
        long black = bit(1, 0);
        long white = bit(0, 0);
        BitBoardPosition pass = new BitBoardPosition(black, white);
        if (BitBoard.legalMoves(black, white) != 0L
            || BitBoard.legalMoves(white, black) == 0L) {
            throw new AssertionError("パステスト盤面の前提が不正です。");
        }

        SearchResult passResult = new SearchEngine().search(
            pass,
            1,
            new SearchLimits(10_000L, 4, 1)
        );
        assertEquals(-1, passResult.bestSquare(), "pass best square");
        assertEquals(4, passResult.completedDepth(), "pass completed depth");
        assertEquals(
            referenceNegamax(black, white, 4),
            passResult.score(),
            "pass search score"
        );

        BitBoardPosition terminal = new BitBoardPosition(-1L, 0L);
        SearchResult terminalResult = new SearchEngine().search(
            terminal,
            1,
            new SearchLimits(100L, 4, 1)
        );
        assertEquals(-1, terminalResult.bestSquare(), "terminal best square");
        if (!terminalResult.exactSolution()) {
            throw new AssertionError("終局局面が完全結果として記録されていません。");
        }
        if (terminalResult.score() < Evaluator.WIN_SCORE) {
            throw new AssertionError("終局勝利の評価値が小さすぎます。");
        }
    }

    private static void testTimeLimitReturnsLegalFallback() {
        BitBoardPosition position = BitBoardPosition.initial();
        SearchResult result = new SearchEngine().search(
            position,
            1,
            new SearchLimits(1L, 64, 1)
        );
        assertLegalBestMove(position, 1, result.bestSquare());
        if (!result.timedOut() && result.completedDepth() < 60) {
            throw new AssertionError("時間制限終了が記録されていません。");
        }
    }

    private static void testExternalStop() throws Exception {
        SearchEngine engine = new SearchEngine();
        AtomicReference<SearchResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(engine.search(
            BitBoardPosition.initial(),
            1,
            new SearchLimits(60_000L, 64, 1)
        )), "search-stop-test");

        worker.start();
        Thread.sleep(20L);
        engine.stop();
        worker.join(2_000L);

        if (worker.isAlive()) {
            throw new AssertionError("停止要求後も探索が終了しませんでした。");
        }
        if (result.get() == null || !result.get().stopped()) {
            throw new AssertionError("外部停止が探索結果に記録されていません。");
        }
    }

    private static void testParallelExternalStop() throws Exception {
        SearchEngine engine = new SearchEngine();
        AtomicReference<SearchResult> result = new AtomicReference<>();
        Thread controller = new Thread(() -> result.set(engine.search(
            createMidgame(16).position,
            1,
            new SearchLimits(60_000L, 64, 4)
        )), "parallel-search-stop-test");

        controller.start();
        Thread.sleep(20L);
        engine.stop();
        controller.join(2_000L);

        if (controller.isAlive()) {
            throw new AssertionError(
                "停止要求後も並列探索が終了しませんでした。"
            );
        }
        if (result.get() == null || !result.get().stopped()) {
            throw new AssertionError(
                "外部停止が並列探索結果に記録されていません。"
            );
        }
        engine.shutdown();
    }

    private static int referenceMoveScore(
        BitBoardPosition position,
        int color,
        int square,
        int depth
    ) {
        long player = position.player(color);
        long opponent = position.opponent(color);
        long move = 1L << square;
        long flips = BitBoard.flips(player, opponent, move);
        long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
        long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
        return -referenceNegamax(nextOpponent, nextPlayer, depth - 1);
    }

    private static int referenceNegamax(
        long player,
        long opponent,
        int depth
    ) {
        long moves = BitBoard.legalMoves(player, opponent);
        if (moves == 0L) {
            if (BitBoard.legalMoves(opponent, player) == 0L) {
                return EVALUATOR.terminalScore(player, opponent);
            }
            return -referenceNegamax(opponent, player, depth);
        }
        if (depth <= 0) {
            return EVALUATOR.evaluate(player, opponent);
        }

        int best = Integer.MIN_VALUE;
        long remaining = moves;
        while (remaining != 0L) {
            long move = remaining & -remaining;
            remaining ^= move;
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            int score = -referenceNegamax(
                nextOpponent,
                nextPlayer,
                depth - 1
            );
            best = Math.max(best, score);
        }
        return best;
    }

    private static PositionToMove createEndgame(int maximumEmpties) {
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        while (position.emptyCount() > maximumEmpties) {
            long player = position.player(color);
            long opponent = position.opponent(color);
            long moves = BitBoard.legalMoves(player, opponent);
            if (moves == 0L) {
                if (BitBoard.legalMoves(opponent, player) == 0L) {
                    break;
                }
                color = -color;
                continue;
            }

            long move = moves & -moves;
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            position = color == 1
                ? new BitBoardPosition(nextPlayer, nextOpponent)
                : new BitBoardPosition(nextOpponent, nextPlayer);
            color = -color;
        }
        return new PositionToMove(position, color);
    }

    private static PositionToMove createMidgame(int plies) {
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        int played = 0;
        while (played < plies) {
            long player = position.player(color);
            long opponent = position.opponent(color);
            long moves = BitBoard.legalMoves(player, opponent);
            if (moves == 0L) {
                if (BitBoard.legalMoves(opponent, player) == 0L) {
                    break;
                }
                color = -color;
                continue;
            }

            int choice = played % BitBoard.count(moves);
            long move = moves;
            while (choice-- > 0) {
                move &= move - 1L;
            }
            move &= -move;
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            position = color == 1
                ? new BitBoardPosition(nextPlayer, nextOpponent)
                : new BitBoardPosition(nextOpponent, nextPlayer);
            color = -color;
            played++;
        }
        return new PositionToMove(position, color);
    }

    private static void assertLegalBestMove(
        BitBoardPosition position,
        int color,
        int square
    ) {
        if (square < 0 || square >= 64) {
            throw new AssertionError("合法手があるのに着手がありません。");
        }
        long move = 1L << square;
        if (!BitBoard.isLegalMove(
            position.player(color),
            position.opponent(color),
            move
        )) {
            throw new AssertionError("探索結果が非合法手です: " + square);
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(
                label + ": expected=" + expected + ", actual=" + actual
            );
        }
    }

    private static long bit(int x, int y) {
        return 1L << CoordinateConverter.xyToSquare(x, y);
    }

    private static final class PositionToMove {

        private final BitBoardPosition position;
        private final int color;

        private PositionToMove(BitBoardPosition position, int color) {
            this.position = position;
            this.color = color;
        }
    }
}
