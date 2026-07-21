import java.util.concurrent.atomic.AtomicReference;

public final class SearchEngineTest {

    private static final Evaluator EVALUATOR = new Evaluator();

    private SearchEngineTest() {
    }

    public static void main(String[] args) throws Exception {
        testAgainstReferenceNegamax();
        testEndgameThresholdSelection();
        testTranspositionTableDepthGate();
        testTwoWayTranspositionTable();
        testSpecializedLeafSearch();
        testExactLastNSolverEligibility();
        testExactLastNSolverMatchesGeneric();
        testExactLastNSolverEdgeCases();
        testTranspositionTableConsistency();
        testRootProbeResearchDecision();
        testLateMoveReductionEligibility();
        testLateMoveReductionActivation();
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

    private static void testExactLastNSolverEligibility() {
        if (!SearchEngine.exactLastNEligible(2, 2)
            || !SearchEngine.exactLastNEligible(3, 3)
            || !SearchEngine.exactLastNEligible(4, 4)) {
            throw new AssertionError("exact last-N eligibility was rejected");
        }
        if (SearchEngine.exactLastNEligible(1, 1)
            || SearchEngine.exactLastNEligible(4, 3)
            || SearchEngine.exactLastNEligible(5, 5)) {
            throw new AssertionError("invalid exact last-N eligibility");
        }
    }

    private static void testExactLastNSolverMatchesGeneric() {
        SearchEngine specialized = exactSolverEngine(true);
        SearchEngine generic = exactSolverEngine(false);
        assertExactSearchMatches(
            new BitBoardPosition(-1L, 0L),
            1,
            specialized,
            generic,
            "full board"
        );
        for (int empties = 1; empties <= 8; empties++) {
            for (int seed = 1; seed <= 16; seed++) {
                PositionToMove sample = createEndgame(empties, seed);
                assertExactSearchMatches(
                    sample.position,
                    sample.color,
                    specialized,
                    generic,
                    empties + " empties seed " + seed
                );
            }
        }
        PositionToMove threshold = createEndgame(
            SearchEngine.MIN_ENDGAME_THRESHOLD
        );
        assertExactSearchMatches(
            threshold.position,
            threshold.color,
            specialized,
            generic,
            "existing 12-empty endgame"
        );
    }

    private static void testExactLastNSolverEdgeCases() {
        SearchEngine specialized = exactSolverEngine(true);
        SearchEngine generic = exactSolverEngine(false);

        long oneEmpty = 1L << 2;
        long passOpponent = 1L;
        long passPlayer = ~(oneEmpty | passOpponent);
        if (BitBoard.legalMoves(passPlayer, passOpponent) != 0L
            || BitBoard.legalMoves(passOpponent, passPlayer) == 0L) {
            throw new AssertionError("one-empty pass fixture is invalid");
        }
        assertExactSearchMatches(
            new BitBoardPosition(passPlayer, passOpponent),
            1,
            specialized,
            generic,
            "one-empty pass"
        );

        long terminalEmpty = 1L;
        long terminalPlayer = ~terminalEmpty;
        if (BitBoard.legalMoves(terminalPlayer, 0L) != 0L
            || BitBoard.legalMoves(0L, terminalPlayer) != 0L) {
            throw new AssertionError("unplayed terminal fixture is invalid");
        }
        assertExactSearchMatches(
            new BitBoardPosition(terminalPlayer, 0L),
            1,
            specialized,
            generic,
            "one-empty terminal"
        );

        long twoEmpty = 3L;
        long consecutivePassPlayer = ~twoEmpty;
        assertExactSearchMatches(
            new BitBoardPosition(consecutivePassPlayer, 0L),
            1,
            specialized,
            generic,
            "two-empty consecutive pass"
        );

        long wipeoutEmpty = 1L << 2;
        long wipeoutOpponent = 1L << 1;
        long wipeoutPlayer = ~(wipeoutEmpty | wipeoutOpponent);
        long wipeoutFlips = BitBoard.flips(
            wipeoutPlayer,
            wipeoutOpponent,
            wipeoutEmpty
        );
        if (wipeoutFlips == 0L || BitBoard.applyOpponentBoard(
            wipeoutOpponent,
            wipeoutFlips
        ) != 0L) {
            throw new AssertionError("wipeout fixture is invalid");
        }
        assertExactSearchMatches(
            new BitBoardPosition(wipeoutPlayer, wipeoutOpponent),
            1,
            specialized,
            generic,
            "wipeout"
        );
    }

    private static SearchEngine exactSolverEngine(boolean enabled) {
        return new SearchEngine(
            EVALUATOR,
            null,
            true,
            SearchEngine.MAX_ENDGAME_THRESHOLD,
            true,
            enabled
        );
    }

    private static void assertExactSearchMatches(
        BitBoardPosition position,
        int color,
        SearchEngine specialized,
        SearchEngine generic,
        String message
    ) {
        int empties = position.emptyCount();
        SearchLimits limits = new SearchLimits(
            60_000L,
            Math.max(1, empties),
            1
        );
        SearchResult expected = generic.search(position, color, limits);
        SearchResult actual = specialized.search(position, color, limits);
        assertEquals(expected.score(), actual.score(), message + " score");
        assertEquals(
            expected.bestSquare(),
            actual.bestSquare(),
            message + " best square"
        );
        if (!expected.exactSolution() || !actual.exactSolution()) {
            throw new AssertionError(message + " was not solved exactly");
        }
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

    private static void testTranspositionTableDepthGate() {
        if (SearchEngine.ttEligible(0) || SearchEngine.ttEligible(1)) {
            throw new AssertionError("浅いnodeでTTが有効です。");
        }
        if (!SearchEngine.ttEligible(2)) {
            throw new AssertionError("depth 2でTTが無効です。");
        }
    }

    private static void testTwoWayTranspositionTable() {
        long player1 = 1L;
        long opponent1 = 11L;
        long player2 = 2L;
        long opponent2 = 22L;
        long player3 = 3L;
        long opponent3 = 33L;

        TranspositionTable table = new TranspositionTable(2, 2, true);
        assertEquals(2, table.ways(), "two-way count");
        assertEquals(2, table.capacity(), "two-way capacity");
        assertEquals(1, table.bucketCount(), "two-way buckets");
        table.store(player1, opponent1, 8, 801, TranspositionTable.EXACT, 8);
        table.store(
            player2,
            opponent2,
            3,
            302,
            TranspositionTable.UPPER_BOUND,
            3
        );
        assertTableEntry(table, player1, opponent1, true, 8, 801, 8);
        assertTableEntry(table, player2, opponent2, true, 3, 302, 3);

        table.store(
            player3,
            opponent3,
            5,
            503,
            TranspositionTable.LOWER_BOUND,
            5
        );
        assertTableEntry(table, player1, opponent1, true, 8, 801, 8);
        assertTableEntry(table, player2, opponent2, false, 0, 0, -1);
        assertTableEntry(table, player3, opponent3, true, 5, 503, 5);
        TranspositionTable.Stats stats = table.snapshotStats();
        assertEquals(1, (int) stats.collisions(), "two-way collisions");
        assertEquals(1, (int) stats.replacements(), "two-way replacements");

        TranspositionTable exactPriority = new TranspositionTable(2, 2, false);
        exactPriority.store(
            player1,
            opponent1,
            4,
            401,
            TranspositionTable.EXACT,
            4
        );
        exactPriority.store(
            player2,
            opponent2,
            4,
            402,
            TranspositionTable.LOWER_BOUND,
            4
        );
        exactPriority.store(
            player3,
            opponent3,
            4,
            403,
            TranspositionTable.UPPER_BOUND,
            4
        );
        assertTableEntry(
            exactPriority,
            player1,
            opponent1,
            true,
            4,
            401,
            4
        );
        assertTableEntry(
            exactPriority,
            player2,
            opponent2,
            false,
            0,
            0,
            -1
        );

        TranspositionTable deterministic = new TranspositionTable(2, 2, false);
        deterministic.store(
            player1,
            opponent1,
            4,
            401,
            TranspositionTable.LOWER_BOUND,
            4
        );
        deterministic.store(
            player2,
            opponent2,
            4,
            402,
            TranspositionTable.LOWER_BOUND,
            4
        );
        deterministic.store(
            player3,
            opponent3,
            4,
            403,
            TranspositionTable.LOWER_BOUND,
            4
        );
        assertTableEntry(deterministic, player1, opponent1, true, 4, 401, 4);
        assertTableEntry(deterministic, player2, opponent2, false, 0, 0, -1);

        TranspositionTable generation = new TranspositionTable(2, 2, false);
        generation.store(
            player1,
            opponent1,
            8,
            801,
            TranspositionTable.EXACT,
            8
        );
        generation.store(
            player2,
            opponent2,
            7,
            702,
            TranspositionTable.EXACT,
            7
        );
        generation.newSearch();
        generation.store(
            player1,
            opponent1,
            8,
            811,
            TranspositionTable.EXACT,
            8
        );
        generation.store(
            player3,
            opponent3,
            1,
            103,
            TranspositionTable.UPPER_BOUND,
            1
        );
        assertTableEntry(generation, player1, opponent1, true, 8, 811, 8);
        assertTableEntry(generation, player2, opponent2, false, 0, 0, -1);

        TranspositionTable samePosition = new TranspositionTable(2, 2, true);
        samePosition.store(
            player1,
            opponent1,
            8,
            801,
            TranspositionTable.EXACT,
            8
        );
        samePosition.store(
            player1,
            opponent1,
            8,
            899,
            TranspositionTable.UPPER_BOUND,
            9
        );
        assertTableEntry(samePosition, player1, opponent1, true, 8, 801, 8);
        assertEquals(
            1,
            (int) samePosition.snapshotStats().rejectedStores(),
            "same-position rejected stores"
        );

        TranspositionTable oneWay = new TranspositionTable(1 << 10, 1, false);
        TranspositionTable twoWay = new TranspositionTable(1 << 10, 2, false);
        if (oneWay.estimatedStorageBytes() != twoWay.estimatedStorageBytes()) {
            throw new AssertionError("two-way entry storage changed");
        }
        assertEquals(2, new TranspositionTable(2).ways(), "default TT ways");
    }

    private static void assertTableEntry(
        TranspositionTable table,
        long player,
        long opponent,
        boolean expectedFound,
        int expectedDepth,
        int expectedValue,
        int expectedSquare
    ) {
        long probe = table.probe(player, opponent);
        if (TranspositionTable.probeFound(probe) != expectedFound) {
            throw new AssertionError("TT entry presence mismatch");
        }
        if (!expectedFound) {
            return;
        }
        assertEquals(
            expectedDepth,
            TranspositionTable.probeDepth(probe),
            "TT entry depth"
        );
        assertEquals(
            expectedValue,
            TranspositionTable.probeValue(probe),
            "TT entry value"
        );
        assertEquals(
            expectedSquare,
            TranspositionTable.probeBestSquare(probe),
            "TT entry square"
        );
    }

    private static void testSpecializedLeafSearch() {
        if (!SearchEngine.specializedLeafDepth(0)
            || !SearchEngine.specializedLeafDepth(1)
            || SearchEngine.specializedLeafDepth(2)) {
            throw new AssertionError("leaf探索のdepth境界が不正です。");
        }

        PositionToMove[] positions = {
            new PositionToMove(BitBoardPosition.initial(), 1),
            createMidgame(7),
            createMidgame(19)
        };
        for (int index = 0; index < positions.length; index++) {
            PositionToMove sample = positions[index];
            SearchEngine engine = new SearchEngine(
                EVALUATOR,
                new TranspositionTable(1 << 14)
            );
            SearchResult result = engine.search(
                sample.position,
                sample.color,
                new SearchLimits(10_000L, 1, 1)
            );
            int expected = referenceNegamax(
                sample.position.player(sample.color),
                sample.position.opponent(sample.color),
                1
            );
            assertEquals(expected, result.score(), "leaf score " + index);
            assertEquals(
                expected,
                referenceMoveScore(
                    sample.position,
                    sample.color,
                    result.bestSquare(),
                    1
                ),
                "leaf best move " + index
            );
            engine.shutdown();
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

    private static void testLateMoveReductionEligibility() {
        long ordinaryMove = 1L << 20;
        if (!SearchEngine.lmrEligible(5, 4, ordinaryMove, 19, true, true)) {
            throw new AssertionError("LMR対象手を除外しています。");
        }
        if (SearchEngine.lmrEligible(4, 4, ordinaryMove, 19, true, true)) {
            throw new AssertionError("浅い探索でLMRを適用しています。");
        }
        if (SearchEngine.lmrEligible(5, 3, ordinaryMove, 19, true, true)) {
            throw new AssertionError("上位手へLMRを適用しています。");
        }
        if (SearchEngine.lmrEligible(5, 4, 1L, 19, true, true)) {
            throw new AssertionError("隅へLMRを適用しています。");
        }
        if (SearchEngine.lmrEligible(5, 4, ordinaryMove, 18, true, true)) {
            throw new AssertionError("終盤探索でLMRを適用しています。");
        }
        if (SearchEngine.lmrEligible(5, 4, ordinaryMove, 19, true, false)) {
            throw new AssertionError("パスを発生させる手へLMRを適用しています。");
        }
        if (SearchEngine.lmrEligible(5, 4, ordinaryMove, 19, false, true)) {
            throw new AssertionError("PVノードでLMRを適用しています。");
        }
        if (SearchEngine.lmrBoundCanBeStored(true, 99, 100)) {
            throw new AssertionError("未検証LMRのUPPER boundを保存しています。");
        }
        if (!SearchEngine.lmrBoundCanBeStored(true, 100, 100)) {
            throw new AssertionError("LMRのLOWER boundを破棄しています。");
        }
        if (!SearchEngine.lmrBoundCanBeStored(false, 99, 100)) {
            throw new AssertionError("全深度確認済みのboundを破棄しています。");
        }
    }

    private static void testLateMoveReductionActivation() {
        PositionToMove sample = createMidgame(18);
        SearchLimits sequentialLimits = new SearchLimits(10_000L, 7, 1);
        SearchEngine firstEngine = new SearchEngine();
        SearchEngine secondEngine = new SearchEngine();
        SearchResult first = firstEngine.search(
            sample.position,
            sample.color,
            sequentialLimits
        );
        SearchResult second = secondEngine.search(
            sample.position,
            sample.color,
            sequentialLimits
        );

        assertEquals(first.score(), second.score(), "LMR repeat score");
        assertEquals(
            first.bestSquare(),
            second.bestSquare(),
            "LMR repeat best square"
        );
        if (first.lmrSearches() == 0L) {
            throw new AssertionError("LMR探索が一度も実行されていません。");
        }
        if (first.lmrResearches() > first.lmrSearches()) {
            throw new AssertionError("LMR再探索回数が探索回数を超えています。");
        }

        SearchEngine parallelEngine = new SearchEngine();
        SearchResult parallel = parallelEngine.search(
            sample.position,
            sample.color,
            new SearchLimits(10_000L, 7, 4)
        );
        assertEquals(first.score(), parallel.score(), "LMR parallel score");
        assertEquals(
            first.bestSquare(),
            parallel.bestSquare(),
            "LMR parallel best square"
        );
        if (parallel.lmrSearches() == 0L) {
            throw new AssertionError("並列LMR探索が実行されていません。");
        }

        firstEngine.shutdown();
        secondEngine.shutdown();
        parallelEngine.shutdown();
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

    private static PositionToMove createEndgame(
        int maximumEmpties,
        int seed
    ) {
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        int played = 0;
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

            int choice = Math.floorMod(
                played * 11 + seed * 7,
                BitBoard.count(moves)
            );
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
