public final class ExactLastNBenchmark {

    private static final int DEFAULT_REPETITIONS = 200;
    private static final int DEFAULT_SAMPLES = 32;
    private static final int ROUNDS = 5;

    private ExactLastNBenchmark() {
    }

    public static void main(String[] args) {
        int repetitions = args.length >= 1
            ? Integer.parseInt(args[0])
            : DEFAULT_REPETITIONS;
        int sampleCount = args.length >= 2
            ? Integer.parseInt(args[1])
            : DEFAULT_SAMPLES;
        if (repetitions < 1 || sampleCount < 1) {
            throw new IllegalArgumentException(
                "repetitions and samples must be positive"
            );
        }

        System.out.println(
            "round,mode,empties,searches,avgNanos,avgNodes,checksum"
        );
        for (int empties = 1; empties <= 4; empties++) {
            PositionToMove[] samples = createSamples(empties, sampleCount);
            verifyEquivalent(samples);
            measure(samples, 2, false);
            measure(samples, 2, true);

            for (int round = 0; round < ROUNDS; round++) {
                boolean specializedFirst = (round & 1) != 0;
                if (specializedFirst) {
                    print(round, empties, "specialized", measure(
                        samples,
                        repetitions,
                        true
                    ));
                    print(round, empties, "generic", measure(
                        samples,
                        repetitions,
                        false
                    ));
                } else {
                    print(round, empties, "generic", measure(
                        samples,
                        repetitions,
                        false
                    ));
                    print(round, empties, "specialized", measure(
                        samples,
                        repetitions,
                        true
                    ));
                }
            }
        }
    }

    private static void verifyEquivalent(PositionToMove[] samples) {
        SearchEngine generic = engine(false);
        SearchEngine specialized = engine(true);
        for (int index = 0; index < samples.length; index++) {
            PositionToMove sample = samples[index];
            SearchLimits limits = limits(sample);
            SearchResult expected = generic.search(
                sample.position,
                sample.color,
                limits
            );
            SearchResult actual = specialized.search(
                sample.position,
                sample.color,
                limits
            );
            if (expected.bestSquare() != actual.bestSquare()
                || expected.score() != actual.score()) {
                throw new AssertionError(
                    "result mismatch at sample " + index
                );
            }
        }
        generic.shutdown();
        specialized.shutdown();
    }

    private static Measurement measure(
        PositionToMove[] samples,
        int repetitions,
        boolean specialized
    ) {
        SearchEngine engine = engine(specialized);
        long elapsedNanos = 0L;
        long nodes = 0L;
        long checksum = 0L;
        int searches = 0;
        for (int repetition = 0; repetition < repetitions; repetition++) {
            for (PositionToMove sample : samples) {
                SearchResult result = engine.search(
                    sample.position,
                    sample.color,
                    limits(sample)
                );
                elapsedNanos += result.elapsedNanos();
                nodes += result.nodes();
                checksum = checksum * 31L
                    + result.score() * 67L
                    + result.bestSquare();
                searches++;
            }
        }
        engine.shutdown();
        return new Measurement(elapsedNanos, nodes, checksum, searches);
    }

    private static SearchEngine engine(boolean specialized) {
        return new SearchEngine(
            new Evaluator(),
            null,
            true,
            SearchEngine.MAX_ENDGAME_THRESHOLD,
            true,
            specialized
        );
    }

    private static SearchLimits limits(PositionToMove sample) {
        return new SearchLimits(
            60_000L,
            Math.max(1, sample.position.emptyCount()),
            1
        );
    }

    private static void print(
        int round,
        int empties,
        String mode,
        Measurement measurement
    ) {
        System.out.println(
            round + "," + mode
                + "," + empties
                + "," + measurement.searches
                + "," + measurement.elapsedNanos / measurement.searches
                + "," + measurement.nodes / measurement.searches
                + "," + measurement.checksum
        );
    }

    private static PositionToMove[] createSamples(
        int empties,
        int sampleCount
    ) {
        PositionToMove[] result = new PositionToMove[sampleCount];
        int accepted = 0;
        for (int seed = 1; accepted < sampleCount; seed++) {
            PositionToMove sample = createEndgame(empties, seed);
            if (sample.position.emptyCount() == empties) {
                result[accepted++] = sample;
            }
        }
        return result;
    }

    private static PositionToMove createEndgame(int empties, int seed) {
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        int played = 0;
        while (position.emptyCount() > empties) {
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

    private static final class Measurement {

        private final long elapsedNanos;
        private final long nodes;
        private final long checksum;
        private final int searches;

        private Measurement(
            long elapsedNanos,
            long nodes,
            long checksum,
            int searches
        ) {
            this.elapsedNanos = elapsedNanos;
            this.nodes = nodes;
            this.checksum = checksum;
            this.searches = searches;
        }
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
