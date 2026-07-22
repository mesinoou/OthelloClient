public final class WldEndgameBenchmark {

    private static final int DEFAULT_SAMPLES = 12;
    private static final long DEFAULT_TIME_LIMIT_MILLIS = 8_000L;

    private WldEndgameBenchmark() {
    }

    public static void main(String[] args) {
        int empties = args.length >= 1
            ? Integer.parseInt(args[0])
            : 16;
        int repetitions = args.length >= 2
            ? Integer.parseInt(args[1])
            : 1;
        long timeLimitMillis = args.length >= 3
            ? Long.parseLong(args[2])
            : DEFAULT_TIME_LIMIT_MILLIS;
        int sampleCount = args.length >= 4
            ? Integer.parseInt(args[3])
            : DEFAULT_SAMPLES;
        int threads = args.length >= 5
            ? Integer.parseInt(args[4])
            : 4;

        if (empties < 1 || empties > SearchEngine.MAX_WLD_THRESHOLD) {
            throw new IllegalArgumentException(
                "empties must be between 1 and "
                    + SearchEngine.MAX_WLD_THRESHOLD
            );
        }
        if (repetitions < 1 || timeLimitMillis < 1L
            || sampleCount < 1 || threads < 1) {
            throw new IllegalArgumentException("invalid benchmark setting");
        }

        PositionToMove[] samples = new PositionToMove[sampleCount];
        for (int index = 0; index < sampleCount; index++) {
            samples[index] = createEndgame(empties, index + 1);
        }
        warmUp(samples[0]);

        int[] exactOutcomes = new int[sampleCount];
        for (int index = 0; index < exactOutcomes.length; index++) {
            exactOutcomes[index] = Integer.MIN_VALUE;
        }

        System.out.println(
            "mode,threads,empties,solved,searches,avgMillis,maxMillis,"
                + "avgNodes,wldAttempts,wldSolutions,"
                + "outcomeCompared,outcomeMismatches,scoreChecksum"
        );
        runMode(
            samples,
            exactOutcomes,
            repetitions,
            timeLimitMillis,
            threads,
            false
        );
        runMode(
            samples,
            exactOutcomes,
            repetitions,
            timeLimitMillis,
            threads,
            true
        );
    }

    private static void warmUp(PositionToMove sample) {
        SearchEngine engine = new SearchEngine(
            new Evaluator(),
            new TranspositionTable(1 << 16),
            true,
            Math.min(sample.position.emptyCount(), 12)
        );
        try {
            engine.search(
                sample.position,
                sample.color,
                new SearchLimits(2_000L, 8, 1)
            );
        } finally {
            engine.shutdown();
        }
    }

    private static void runMode(
        PositionToMove[] samples,
        int[] exactOutcomes,
        int repetitions,
        long timeLimitMillis,
        int threads,
        boolean wld
    ) {
        long elapsedNanos = 0L;
        long maximumElapsedNanos = 0L;
        long nodes = 0L;
        long scoreChecksum = 1L;
        int solved = 0;
        int searches = 0;
        int wldAttempts = 0;
        int wldSolutions = 0;
        int outcomeCompared = 0;
        int outcomeMismatches = 0;

        for (int repetition = 0; repetition < repetitions; repetition++) {
            for (int index = 0; index < samples.length; index++) {
                PositionToMove sample = samples[index];
                SearchEngine engine = new SearchEngine(
                    new Evaluator(),
                    new TranspositionTable(1 << 18),
                    true,
                    sample.position.emptyCount(),
                    true,
                    true,
                    true,
                    true,
                    wld
                );
                SearchResult result;
                try {
                    result = engine.search(
                        sample.position,
                        sample.color,
                        new SearchLimits(timeLimitMillis, 64, threads)
                    );
                } finally {
                    engine.shutdown();
                }

                if (result.exactSolution()) {
                    solved++;
                    int outcome = Integer.signum(result.score());
                    if (!wld) {
                        exactOutcomes[index] = outcome;
                    } else if (exactOutcomes[index] != Integer.MIN_VALUE) {
                        outcomeCompared++;
                        if (exactOutcomes[index] != outcome) {
                            outcomeMismatches++;
                        }
                    }
                }
                if (result.wldSearch()) {
                    wldAttempts++;
                }
                if (result.wldSolution()) {
                    wldSolutions++;
                }

                elapsedNanos += result.elapsedNanos();
                maximumElapsedNanos = Math.max(
                    maximumElapsedNanos,
                    result.elapsedNanos()
                );
                nodes += result.nodes();
                scoreChecksum = 31L * scoreChecksum + result.score();
                searches++;
            }
        }

        System.out.println(
            (wld ? "wld" : "exact-score")
                + "," + threads
                + "," + samples[0].position.emptyCount()
                + "," + solved
                + "," + searches
                + "," + elapsedNanos / searches / 1_000_000L
                + "," + maximumElapsedNanos / 1_000_000L
                + "," + nodes / searches
                + "," + wldAttempts
                + "," + wldSolutions
                + "," + outcomeCompared
                + "," + outcomeMismatches
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
                    throw new AssertionError(
                        "game ended before benchmark position"
                    );
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

    private static final class PositionToMove {

        private final BitBoardPosition position;
        private final int color;

        private PositionToMove(BitBoardPosition position, int color) {
            this.position = position;
            this.color = color;
        }
    }
}
