import java.util.LinkedHashSet;
import java.util.Set;

public final class ParallelSearchBenchmark {

    private ParallelSearchBenchmark() {
    }

    public static void main(String[] args) {
        int depth = args.length >= 1 ? Integer.parseInt(args[0]) : 9;
        int repetitions = args.length >= 2 ? Integer.parseInt(args[1]) : 3;
        if (depth < 1 || depth > 64 || repetitions < 1) {
            throw new IllegalArgumentException(
                "depth must be 1..64 and repetitions must be positive"
            );
        }

        PositionToMove sample = createMidgame(18);
        warmUp(sample);

        int available = Runtime.getRuntime().availableProcessors();
        Set<Integer> configurations = new LinkedHashSet<>();
        configurations.add(1);
        configurations.add(2);
        configurations.add(4);
        configurations.add(8);
        configurations.add(available);

        System.out.println(
            "threads,depth,completedDepth,avgMillis,avgNodes,"
                + "avgWorkerNodes,avgParallelTasks"
        );
        for (int threads : configurations) {
            benchmark(sample, depth, repetitions, threads);
        }
    }

    private static void warmUp(PositionToMove sample) {
        SearchEngine engine = new SearchEngine();
        engine.search(
            sample.position,
            sample.color,
            new SearchLimits(10_000L, 5, 1)
        );
        engine.shutdown();
    }

    private static void benchmark(
        PositionToMove sample,
        int depth,
        int repetitions,
        int threads
    ) {
        long elapsedNanos = 0L;
        long nodes = 0L;
        long workerNodes = 0L;
        long tasks = 0L;
        int completedDepth = 0;
        for (int repetition = 0; repetition < repetitions; repetition++) {
            SearchEngine engine = new SearchEngine();
            SearchResult result = engine.search(
                sample.position,
                sample.color,
                new SearchLimits(60_000L, depth, threads)
            );
            engine.shutdown();
            elapsedNanos += result.elapsedNanos();
            nodes += result.nodes();
            workerNodes += result.parallelNodes();
            tasks += result.parallelTasks();
            completedDepth = Math.min(
                completedDepth == 0 ? result.completedDepth() : completedDepth,
                result.completedDepth()
            );
        }

        System.out.println(
            threads + ","
                + depth + ","
                + completedDepth + ","
                + elapsedNanos / repetitions / 1_000_000L + ","
                + nodes / repetitions + ","
                + workerNodes / repetitions + ","
                + tasks / repetitions
        );
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

    private static final class PositionToMove {

        private final BitBoardPosition position;
        private final int color;

        private PositionToMove(BitBoardPosition position, int color) {
            this.position = position;
            this.color = color;
        }
    }
}
