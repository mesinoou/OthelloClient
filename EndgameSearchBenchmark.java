public final class EndgameSearchBenchmark {

    private static final int SAMPLE_COUNT = 12;
    private static final long DEFAULT_TIME_LIMIT_MILLIS = 30_000L;

    private EndgameSearchBenchmark() {
    }

    public static void main(String[] args) {
        int empties = args.length >= 1
            ? Integer.parseInt(args[0])
            : SearchEngine.MIN_ENDGAME_THRESHOLD;
        int repetitions = args.length >= 2
            ? Integer.parseInt(args[1])
            : 3;
        long timeLimitMillis = args.length >= 3
            ? Long.parseLong(args[2])
            : DEFAULT_TIME_LIMIT_MILLIS;
        int sampleCount = args.length >= 4
            ? Integer.parseInt(args[3])
            : SAMPLE_COUNT;
        int selectedThreads = args.length >= 5
            ? Integer.parseInt(args[4])
            : 0;
        boolean stabilityEnabled = args.length < 6
            || Boolean.parseBoolean(args[5]);
        if (empties < 1 || empties > SearchEngine.MAX_ENDGAME_THRESHOLD) {
            throw new IllegalArgumentException(
                "empties must be between 1 and "
                    + SearchEngine.MAX_ENDGAME_THRESHOLD
            );
        }
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions must be positive");
        }
        if (timeLimitMillis < 1L) {
            throw new IllegalArgumentException("time limit must be positive");
        }
        if (sampleCount < 1 || selectedThreads < 0) {
            throw new IllegalArgumentException("invalid sample or thread count");
        }

        PositionToMove[] samples = new PositionToMove[sampleCount];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = createEndgame(empties, index + 1);
        }

        warmUp(samples[0]);
        int[] expectedScores = new int[samples.length];
        for (int index = 0; index < expectedScores.length; index++) {
            expectedScores[index] = Integer.MIN_VALUE;
        }

        System.out.println(
            "ordering,stability,threads,empties,solved,samples,avgMillis,"
                + "maxMillis,avgNodes,avgParallelTasks,avgStabilityChecks,"
                + "avgStabilityCuts,scoreChecksum"
        );
        if (selectedThreads > 0) {
            runConfiguration(
                samples,
                expectedScores,
                repetitions,
                true,
                stabilityEnabled,
                selectedThreads,
                timeLimitMillis
            );
        } else {
            runConfiguration(
                samples,
                expectedScores,
                repetitions,
                false,
                stabilityEnabled,
                1,
                timeLimitMillis
            );
            int[] threadCounts = {1, 2, 4, 8};
            for (int threads : threadCounts) {
                runConfiguration(
                    samples,
                    expectedScores,
                    repetitions,
                    true,
                    stabilityEnabled,
                    threads,
                    timeLimitMillis
                );
            }
        }
    }

    private static void warmUp(PositionToMove sample) {
        SearchEngine engine = new SearchEngine();
        engine.search(
            sample.position,
            sample.color,
            new SearchLimits(DEFAULT_TIME_LIMIT_MILLIS, 64, 1)
        );
        engine.shutdown();
    }

    private static void runConfiguration(
        PositionToMove[] samples,
        int[] expectedScores,
        int repetitions,
        boolean ordering,
        boolean stabilityEnabled,
        int threads,
        long timeLimitMillis
    ) {
        long elapsedNanos = 0L;
        long nodes = 0L;
        long parallelTasks = 0L;
        long stabilityChecks = 0L;
        long stabilityCuts = 0L;
        long scoreChecksum = 1L;
        long maximumElapsedNanos = 0L;
        int searches = 0;
        int solved = 0;

        for (int repetition = 0; repetition < repetitions; repetition++) {
            for (int index = 0; index < samples.length; index++) {
                PositionToMove sample = samples[index];
                SearchEngine engine = new SearchEngine(
                    new Evaluator(),
                    new TranspositionTable(1 << 18),
                    ordering,
                    samples[index].position.emptyCount(),
                    true,
                    true,
                    stabilityEnabled
                );
                SearchResult result = engine.search(
                    sample.position,
                    sample.color,
                    new SearchLimits(timeLimitMillis, 64, threads)
                );
                engine.shutdown();

                if (result.exactSolution()) {
                    solved++;
                    if (expectedScores[index] == Integer.MIN_VALUE) {
                        expectedScores[index] = result.score();
                    } else if (expectedScores[index] != result.score()) {
                        throw new AssertionError(
                            "score mismatch: sample=" + index
                                + ", expected=" + expectedScores[index]
                                + ", actual=" + result.score()
                        );
                    }
                }

                elapsedNanos += result.elapsedNanos();
                maximumElapsedNanos = Math.max(
                    maximumElapsedNanos,
                    result.elapsedNanos()
                );
                nodes += result.nodes();
                parallelTasks += result.parallelTasks();
                stabilityChecks += result.stabilityChecks();
                stabilityCuts += result.stabilityCuts();
                scoreChecksum = 31L * scoreChecksum + result.score();
                searches++;
            }
        }

        System.out.println(
            (ordering ? "endgame" : "generic")
                + "," + stabilityEnabled
                + "," + threads
                + "," + samples[0].position.emptyCount()
                + "," + solved
                + "," + searches
                + "," + elapsedNanos / searches / 1_000_000L
                + "," + maximumElapsedNanos / 1_000_000L
                + "," + nodes / searches
                + "," + parallelTasks / searches
                + "," + stabilityChecks / searches
                + "," + stabilityCuts / searches
                + "," + scoreChecksum
        );
    }

    private static PositionToMove createEndgame(int maximumEmpties, int seed) {
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        int played = 0;

        while (position.emptyCount() > maximumEmpties) {
            long player = position.player(color);
            long opponent = position.opponent(color);
            long moves = BitBoard.legalMoves(player, opponent);
            if (moves == 0L) {
                if (BitBoard.legalMoves(opponent, player) == 0L) {
                    throw new AssertionError("game ended before benchmark position");
                }
                color = -color;
                continue;
            }

            int choice = Math.floorMod(played * 11 + seed * 7, BitBoard.count(moves));
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

    private static final class PositionToMove {

        private final BitBoardPosition position;
        private final int color;

        private PositionToMove(BitBoardPosition position, int color) {
            this.position = position;
            this.color = color;
        }
    }
}
