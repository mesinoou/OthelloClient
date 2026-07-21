import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class EvaluationBenchmark {

    private static final int POSITION_COUNT = 512;
    private static volatile long resultSink;

    private EvaluationBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args.length > 2) {
            System.err.println(
                "Usage: java EvaluationBenchmark <evaluationModel> [iterations]"
            );
            return;
        }
        Path path = Paths.get(args[0]);
        int iterations = args.length >= 2
            ? Integer.parseInt(args[1])
            : 1_000_000;
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be positive");
        }

        long[][] positions = generatePositions(POSITION_COUNT);
        PositionEvaluator handcrafted = new Evaluator();
        PositionEvaluator learned = LearnedEvaluator.load(path);
        benchmark(handcrafted, positions, Math.min(iterations, 100_000));
        benchmark(learned, positions, Math.min(iterations, 100_000));

        printResult("handcrafted", handcrafted, positions, iterations);
        printResult("learned", learned, positions, iterations);
    }

    private static void printResult(
        String label,
        PositionEvaluator evaluator,
        long[][] positions,
        int iterations
    ) {
        long started = System.nanoTime();
        long checksum = benchmark(evaluator, positions, iterations);
        long elapsed = System.nanoTime() - started;
        double nanosecondsPerEvaluation = (double) elapsed / iterations;
        double evaluationsPerSecond = 1_000_000_000.0
            / nanosecondsPerEvaluation;
        System.out.printf(
            "%s: %.1f ns/eval, %.0f eval/s, checksum=%d%n",
            label,
            nanosecondsPerEvaluation,
            evaluationsPerSecond,
            checksum
        );
    }

    private static long benchmark(
        PositionEvaluator evaluator,
        long[][] positions,
        int iterations
    ) {
        long checksum = 0L;
        for (int index = 0; index < iterations; index++) {
            long[] position = positions[index % positions.length];
            checksum += evaluator.evaluate(position[0], position[1]);
        }
        resultSink = checksum;
        return checksum;
    }

    private static long[][] generatePositions(int count) {
        Random random = new Random(20260721L);
        List<long[]> positions = new ArrayList<>(count);
        while (positions.size() < count) {
            long player = BitBoardPosition.initial().black();
            long opponent = BitBoardPosition.initial().white();
            while (positions.size() < count) {
                positions.add(new long[] {player, opponent});
                long legalMoves = BitBoard.legalMoves(player, opponent);
                if (legalMoves == 0L) {
                    long opponentMoves = BitBoard.legalMoves(opponent, player);
                    if (opponentMoves == 0L) {
                        break;
                    }
                    long swap = player;
                    player = opponent;
                    opponent = swap;
                    continue;
                }
                int selected = random.nextInt(Long.bitCount(legalMoves));
                long remaining = legalMoves;
                while (selected-- > 0) {
                    remaining &= remaining - 1L;
                }
                long move = remaining & -remaining;
                long flips = BitBoard.flips(player, opponent, move);
                long nextPlayer = BitBoard.applyPlayerBoard(
                    player,
                    move,
                    flips
                );
                long nextOpponent = BitBoard.applyOpponentBoard(
                    opponent,
                    flips
                );
                player = nextOpponent;
                opponent = nextPlayer;
            }
        }
        return positions.toArray(new long[0][]);
    }
}
