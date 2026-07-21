import java.nio.file.Path;
import java.nio.file.Paths;

public final class LearnedEvaluatorTest {

    private LearnedEvaluatorTest() {
    }

    public static void main(String[] args) throws Exception {
        testPatternEncoding();
        testChunkedLookupBounds();
        if (args.length >= 1) {
            testLoadedModel(Paths.get(args[0]));
        }
        System.out.println("LearnedEvaluatorTest: PASS");
    }

    private static void testPatternEncoding() {
        long player = (1L << 0) | (1L << 2);
        long opponent = 1L << 1;
        int actual = LearnedEvaluator.encodePattern(
            player,
            opponent,
            new int[] {0, 1, 2, 3}
        );
        assertEquals(48, actual, "base-3 pattern encoding");
    }

    private static void testChunkedLookupBounds() {
        assertEquals(
            16,
            LearnedEvaluator.ternaryByteCode(0b0000_0101, 0b0000_0010),
            "little-endian byte encoding"
        );
        long maximumBytes = 8L * 1024L * 1024L;
        if (LearnedEvaluator.patternLookupDataBytes() > maximumBytes) {
            throw new AssertionError(
                "chunked pattern lookup exceeds 8 MiB: "
                    + LearnedEvaluator.patternLookupDataBytes()
            );
        }
    }

    private static void testLoadedModel(Path path) throws Exception {
        LearnedEvaluator evaluator = LearnedEvaluator.load(path);
        long player = BitBoardPosition.initial().black();
        long opponent = BitBoardPosition.initial().white();

        for (int ply = 0; ply < 56; ply++) {
            assertEquals(
                evaluator.referencePatternScore(player, opponent),
                evaluator.chunkedPatternScore(player, opponent),
                "chunked pattern score at ply " + ply
            );
            assertEquals(
                evaluator.evaluate(player, opponent),
                -evaluator.evaluate(opponent, player),
                "color antisymmetry at ply " + ply
            );
            for (Symmetry symmetry : Symmetry.values()) {
                assertEquals(
                    evaluator.evaluate(player, opponent),
                    evaluator.evaluate(
                        BitBoard.transformBoard(player, symmetry),
                        BitBoard.transformBoard(opponent, symmetry)
                    ),
                    "board symmetry at ply " + ply + " for " + symmetry
                );
            }

            long legalMoves = BitBoard.legalMoves(player, opponent);
            if (legalMoves == 0L) {
                long opponentMoves = BitBoard.legalMoves(opponent, player);
                if (opponentMoves == 0L) {
                    break;
                }
                long swap = player;
                player = opponent;
                opponent = swap;
                legalMoves = opponentMoves;
            }
            long move = Long.highestOneBit(legalMoves);
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            player = nextOpponent;
            opponent = nextPlayer;
        }
    }

    private static void assertEquals(
        int expected,
        int actual,
        String message
    ) {
        if (expected != actual) {
            throw new AssertionError(
                message + ": expected " + expected + ", got " + actual
            );
        }
    }
}
