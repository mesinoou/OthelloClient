import java.util.concurrent.atomic.AtomicReference;

public final class SearchEngineTest {

    private static final Evaluator EVALUATOR = new Evaluator();

    private SearchEngineTest() {
    }

    public static void main(String[] args) throws Exception {
        testAgainstReferenceNegamax();
        testEndgameThresholdSelection();
        testWldScores();
        testTranspositionTableDepthGate();
        testSpecializedLeafSearch();
        testExactLastNSolverEligibility();
        testExactLastNSolverMatchesGeneric();
        testExactLastNSolverEdgeCases();
        testStabilityBounds();
        testStabilityCutoffMatchesBaseline();
        testTranspositionTableConsistency();
        testLearnedMoveOrdering();
        testRootProbeResearchDecision();
        testLateMoveReductionEligibility();
        testLateMoveReductionActivation();
        testMultiProbCutCalibration();
        testParallelMatchesSequential();
        testExactEndgame();
        testWldMatchesExactOutcome();
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
        assertEquals(14, SearchEngine.wldThresholdFor(999L), "999ms WLD threshold");
        assertEquals(16, SearchEngine.wldThresholdFor(1_000L), "1s WLD threshold");
        assertEquals(18, SearchEngine.wldThresholdFor(3_000L), "3s WLD threshold");
        assertEquals(20, SearchEngine.wldThresholdFor(8_000L), "8s WLD threshold");
        if (SearchEngine.wldTrialNanos(10_000_000_000L)
            != 6_500_000_000L) {
            throw new AssertionError("WLD trial budget is invalid");
        }
    }

    private static void testWldScores() {
        assertEquals(
            Evaluator.WIN_SCORE,
            SearchEngine.wldScoreForDifference(1),
            "WLD win"
        );
        assertEquals(0, SearchEngine.wldScoreForDifference(0), "WLD draw");
        assertEquals(
            -Evaluator.WIN_SCORE,
            SearchEngine.wldScoreForDifference(-1),
            "WLD loss"
        );
    }

    private static void testLearnedMoveOrdering() {
        PositionToMove sample = createMidgame(20);
        CountingOrderingEvaluator disabled = new CountingOrderingEvaluator();
        SearchEngine belowThreshold = moveOrderingEngine(disabled, 8);
        SearchResult belowResult = belowThreshold.search(
            sample.position,
            sample.color,
            new SearchLimits(30_000L, 7, 1)
        );
        assertLegalBestMove(
            sample.position,
            sample.color,
            belowResult.bestSquare()
        );
        assertEquals(0, disabled.calls, "ordering below threshold");

        CountingOrderingEvaluator ordering = new CountingOrderingEvaluator();
        SearchEngine baseline = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 16),
            true,
            0,
            false
        );
        SearchEngine ordered = moveOrderingEngine(ordering, 5);
        SearchLimits limits = new SearchLimits(30_000L, 7, 1);
        SearchResult expected = baseline.search(
            sample.position,
            sample.color,
            limits
        );
        SearchResult actual = ordered.search(
            sample.position,
            sample.color,
            limits
        );
        assertEquals(
            expected.completedDepth(),
            actual.completedDepth(),
            "learned ordering completed depth"
        );
        assertEquals(
            expected.score(),
            actual.score(),
            "learned ordering exact score"
        );
        assertLegalBestMove(
            sample.position,
            sample.color,
            actual.bestSquare()
        );
        int rootMoves = BitBoard.count(
            BitBoard.legalMoves(
                sample.position.player(sample.color),
                sample.position.opponent(sample.color)
            )
        );
        if (ordering.calls <= rootMoves) {
            throw new AssertionError(
                "ordering evaluator was not used below root"
            );
        }
    }

    private static SearchEngine moveOrderingEngine(
        PositionEvaluator ordering,
        int minimumDepth
    ) {
        return new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 16),
            true,
            0,
            false,
            true,
            true,
            true,
            true,
            ordering,
            minimumDepth
        );
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
            enabled,
            true,
            true,
            false
        );
    }

    private static void testStabilityBounds() {
        if (SearchEngine.stabilityEligible(true, 4, true, 100_008, 100_009)
            || SearchEngine.stabilityEligible(
                true,
                19,
                true,
                100_008,
                100_009
            )
            || SearchEngine.stabilityEligible(
                true,
                12,
                false,
                100_008,
                100_009
            )
            || SearchEngine.stabilityEligible(true, 12, true, 0, 1)
            || !SearchEngine.stabilityEligible(
                true,
                12,
                true,
                100_008,
                100_009
            )
            || !SearchEngine.stabilityEligible(
                true,
                12,
                true,
                -100_010,
                -100_009
            )) {
            throw new AssertionError("stability cutoff eligibility is invalid");
        }

        long player = 0x0000_0000_0000_00ffL;
        long opponent = 0xff00_0000_0000_0000L;
        long bounds = SearchEngine.stabilityScoreBounds(player, opponent);
        assertEquals(
            Evaluator.terminalScoreForDifference(-48),
            SearchEngine.stabilityLowerScore(bounds),
            "stability lower score"
        );
        assertEquals(
            Evaluator.terminalScoreForDifference(48),
            SearchEngine.stabilityUpperScore(bounds),
            "stability upper score"
        );
        long swapped = SearchEngine.stabilityScoreBounds(opponent, player);
        assertEquals(
            SearchEngine.stabilityLowerScore(bounds),
            -SearchEngine.stabilityUpperScore(swapped),
            "stability bound antisymmetry"
        );
    }

    private static void testStabilityCutoffMatchesBaseline() {
        SearchEngine baseline = stabilityEngine(false);
        SearchEngine candidate = stabilityEngine(true);
        for (int empties = 5; empties <= 9; empties++) {
            for (int seed = 1; seed <= 4; seed++) {
                PositionToMove sample = createEndgame(empties, seed);
                SearchLimits limits = new SearchLimits(60_000L, empties, 1);
                SearchResult expected = baseline.search(
                    sample.position,
                    sample.color,
                    limits
                );
                SearchResult actual = candidate.search(
                    sample.position,
                    sample.color,
                    limits
                );
                String label = "stability " + empties + " empties seed " + seed;
                assertEquals(expected.score(), actual.score(), label + " score");

                long bounds = SearchEngine.stabilityScoreBounds(
                    sample.position.player(sample.color),
                    sample.position.opponent(sample.color)
                );
                int lower = SearchEngine.stabilityLowerScore(bounds);
                int upper = SearchEngine.stabilityUpperScore(bounds);
                if (actual.score() < lower || actual.score() > upper) {
                    throw new AssertionError(label + " escaped stability bounds");
                }
            }
        }
    }

    private static SearchEngine stabilityEngine(boolean enabled) {
        return new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 16),
            true,
            SearchEngine.MAX_ENDGAME_THRESHOLD,
            true,
            true,
            enabled,
            true,
            false
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
        TranspositionTable table = new TranspositionTable(16);
        table.store(1L, 2L, 4, 123, TranspositionTable.EXACT, 7);
        if (TranspositionTable.probeFound(
            table.probe(1L, 2L, TranspositionTable.WLD_MODE)
        )) {
            throw new AssertionError("通常探索のTT値がWLDへ混入しています。");
        }
        table.store(
            1L,
            2L,
            4,
            Evaluator.WIN_SCORE,
            TranspositionTable.EXACT,
            7,
            TranspositionTable.WLD_MODE
        );
        long wldProbe = table.probe(1L, 2L, TranspositionTable.WLD_MODE);
        if (!TranspositionTable.probeFound(wldProbe)
            || TranspositionTable.probeValue(wldProbe) != Evaluator.WIN_SCORE) {
            throw new AssertionError("WLDのTT値を参照できません。");
        }
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

    private static void testMultiProbCutCalibration() {
        MultiProbCut.Parameters phaseZero = MultiProbCut.parametersFor(8, 31);
        MultiProbCut.Parameters phaseZeroDeep = MultiProbCut.parametersFor(
            10,
            31
        );
        MultiProbCut.Parameters phaseOne = MultiProbCut.parametersFor(8, 30);
        if (phaseZero == null || phaseZeroDeep == null || phaseOne == null) {
            throw new AssertionError("MPC calibration group is missing");
        }
        if (MultiProbCut.parametersFor(6, 31) != null
            || MultiProbCut.parametersFor(6, 30) != null
            || MultiProbCut.parametersFor(8, 20) != null
            || MultiProbCut.parametersFor(8, 18) != null) {
            throw new AssertionError("uncalibrated MPC group is enabled");
        }
        int high = MultiProbCut.failHighThreshold(101, phaseZero);
        int low = MultiProbCut.failLowThreshold(100, phaseZero);
        if (high <= 101 || low >= 100 || high <= low) {
            throw new AssertionError("MPC threshold margins are invalid");
        }
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
        assertEquals(
            Integer.signum(expected) * Evaluator.WIN_SCORE,
            result.score(),
            "WLD endgame score"
        );
        assertLegalBestMove(endgame.position, endgame.color, result.bestSquare());
        if (!result.exactSolution()) {
            throw new AssertionError("完全読み結果として記録されていません。");
        }
        if (!result.wldSearch() || !result.wldSolution()
            || result.wldNodes() == 0L || result.wldElapsedNanos() == 0L) {
            throw new AssertionError("WLD完全読みの計測値が記録されていません。");
        }
        assertEquals(empties, result.endgameEmpties(), "endgame empties");
    }

    private static void testWldMatchesExactOutcome() {
        SearchEngine exact = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 17),
            true,
            SearchEngine.MIN_ENDGAME_THRESHOLD,
            true,
            true,
            true,
            true,
            false
        );
        SearchEngine wld = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 17),
            true,
            SearchEngine.MIN_ENDGAME_THRESHOLD
        );
        SearchEngine parallelWld = new SearchEngine(
            EVALUATOR,
            new TranspositionTable(1 << 17),
            true,
            SearchEngine.MIN_ENDGAME_THRESHOLD
        );
        try {
            for (int empties = 2; empties <= 10; empties++) {
                for (int seed = 1; seed <= 6; seed++) {
                    PositionToMove sample = createEndgame(empties, seed);
                    SearchLimits limits = new SearchLimits(60_000L, 64, 1);
                    SearchResult exactResult = exact.search(
                        sample.position,
                        sample.color,
                        limits
                    );
                    SearchResult wldResult = wld.search(
                        sample.position,
                        sample.color,
                        limits
                    );
                    SearchResult parallelResult = parallelWld.search(
                        sample.position,
                        sample.color,
                        new SearchLimits(60_000L, 64, 4)
                    );
                    String label = "WLD outcome " + empties
                        + " empties seed " + seed;
                    assertEquals(
                        Integer.signum(exactResult.score())
                            * Evaluator.WIN_SCORE,
                        wldResult.score(),
                        label
                    );
                    assertEquals(
                        wldResult.score(),
                        parallelResult.score(),
                        label + " parallel"
                    );
                    if (!exactResult.exactSolution()
                        || !wldResult.wldSolution()
                        || !parallelResult.wldSolution()) {
                        throw new AssertionError(label + " was not solved");
                    }
                    long legalMoves = BitBoard.legalMoves(
                        sample.position.player(sample.color),
                        sample.position.opponent(sample.color)
                    );
                    if (wldResult.bestSquare() < 0 && legalMoves != 0L) {
                        throw new AssertionError(
                            label + " returned pass, score=" + wldResult.score()
                        );
                    }
                    if (legalMoves == 0L) {
                        assertEquals(
                            -1,
                            wldResult.bestSquare(),
                            label + " pass"
                        );
                    } else {
                        assertLegalBestMove(
                            sample.position,
                            sample.color,
                            wldResult.bestSquare()
                        );
                    }
                }
            }
        } finally {
            exact.shutdown();
            wld.shutdown();
            parallelWld.shutdown();
        }
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

    private static final class CountingOrderingEvaluator
        implements PositionEvaluator {

        private int calls;

        @Override
        public int evaluate(long player, long opponent) {
            calls++;
            long mixed = player * 0x9E3779B97F4A7C15L
                ^ Long.rotateLeft(opponent, 23);
            return (int) (mixed ^ (mixed >>> 32));
        }

        @Override
        public int terminalScore(long player, long opponent) {
            return EVALUATOR.terminalScore(player, opponent);
        }

        @Override
        public String description() {
            return "counting-ordering-test";
        }
    }
}
