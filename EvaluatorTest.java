import java.util.Random;

public final class EvaluatorTest {

    private static final Evaluator EVALUATOR = new Evaluator();

    private EvaluatorTest() {
    }

    public static void main(String[] args) {
        testInitialPositionIsNeutral();
        testColorAntisymmetryAndBoardSymmetry();
        testCornerContext();
        testStableEdgeDiscs();
        testParityRegions();
        testPhaseInterpolation();
        testTerminalScores();
        System.out.println("EvaluatorTest: PASS");
    }

    private static void testInitialPositionIsNeutral() {
        BitBoardPosition initial = BitBoardPosition.initial();
        assertEquals(
            0,
            EVALUATOR.evaluate(initial.black(), initial.white()),
            "initial evaluation"
        );
    }

    private static void testColorAntisymmetryAndBoardSymmetry() {
        Random random = new Random(0x5eed5eedL);
        for (int game = 0; game < 40; game++) {
            BitBoardPosition position = BitBoardPosition.initial();
            int color = 1;

            for (int ply = 0; ply < 60; ply++) {
                long player = position.player(color);
                long opponent = position.opponent(color);
                int score = EVALUATOR.evaluate(player, opponent);
                assertEquals(
                    score,
                    -EVALUATOR.evaluate(opponent, player),
                    "color antisymmetry"
                );

                for (Symmetry symmetry : Symmetry.values()) {
                    long transformedPlayer = BitBoard.transformBoard(
                        player,
                        symmetry
                    );
                    long transformedOpponent = BitBoard.transformBoard(
                        opponent,
                        symmetry
                    );
                    assertEquals(
                        score,
                        EVALUATOR.evaluate(
                            transformedPlayer,
                            transformedOpponent
                        ),
                        "board symmetry " + symmetry
                    );
                }

                long moves = BitBoard.legalMoves(player, opponent);
                if (moves == 0L) {
                    if (BitBoard.legalMoves(opponent, player) == 0L) {
                        break;
                    }
                    color = -color;
                    continue;
                }

                int selected = random.nextInt(BitBoard.count(moves));
                long move = selectMove(moves, selected);
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
                position = color == 1
                    ? new BitBoardPosition(nextPlayer, nextOpponent)
                    : new BitBoardPosition(nextOpponent, nextPlayer);
                color = -color;
            }
        }
    }

    private static void testCornerContext() {
        long xSquare = bit(1, 1);
        int emptyCornerScore = Evaluator.cornerContextScore(
            xSquare,
            0L,
            10
        );
        int ownedCornerScore = Evaluator.cornerContextScore(
            bit(0, 0) | xSquare,
            0L,
            10
        );

        if (emptyCornerScore >= 0) {
            throw new AssertionError("空き角のXマスが減点されていません。");
        }
        if (ownedCornerScore <= 0) {
            throw new AssertionError("取得済み角のXマスが一律減点されています。");
        }
        assertEquals(
            emptyCornerScore,
            -Evaluator.cornerContextScore(0L, xSquare, 10),
            "corner context antisymmetry"
        );
    }

    private static void testStableEdgeDiscs() {
        long connected = bit(0, 0)
            | bit(1, 0)
            | bit(2, 0)
            | bit(0, 1)
            | bit(0, 2);
        long separated = bit(4, 0);
        long stable = Evaluator.stableEdgeDiscs(
            connected | separated,
            connected | separated
        );
        assertEquals(connected, stable, "corner-connected stable discs");

        long fullTopEdge = 0xffL;
        long alternatingPlayer = 0x55L;
        long fullEdgeStable = Evaluator.stableEdgeDiscs(
            alternatingPlayer,
            fullTopEdge
        );
        assertEquals(
            alternatingPlayer,
            fullEdgeStable,
            "full edge stable discs"
        );
    }

    private static void testParityRegions() {
        long player = bit(1, 0);
        long opponent = bit(0, 0);
        long playerMoves = BitBoard.legalMoves(player, opponent);
        long opponentMoves = BitBoard.legalMoves(opponent, player);
        int parity = Evaluator.parityAccessDifference(
            player,
            opponent,
            playerMoves,
            opponentMoves
        );
        if (parity == 0) {
            throw new AssertionError("空き領域パリティが識別されていません。");
        }
        assertEquals(
            parity,
            -Evaluator.parityAccessDifference(
                opponent,
                player,
                opponentMoves,
                playerMoves
            ),
            "parity antisymmetry"
        );
    }

    private static void testPhaseInterpolation() {
        assertEquals(
            10,
            Evaluator.interpolatedWeight(4, 10, 30, 90),
            "opening weight"
        );
        assertEquals(
            30,
            Evaluator.interpolatedWeight(20, 10, 30, 90),
            "middle weight"
        );
        assertEquals(
            90,
            Evaluator.interpolatedWeight(50, 10, 30, 90),
            "late weight"
        );
        assertEquals(
            20,
            Evaluator.interpolatedWeight(12, 10, 30, 90),
            "opening-middle interpolation"
        );
    }

    private static void testTerminalScores() {
        long player = -1L ^ bit(0, 0);
        long opponent = bit(0, 0);
        int score = EVALUATOR.terminalScore(player, opponent);
        if (score < Evaluator.WIN_SCORE) {
            throw new AssertionError("終局勝利が通常評価より優先されません。");
        }
        assertEquals(
            score,
            -EVALUATOR.terminalScore(opponent, player),
            "terminal antisymmetry"
        );
        assertEquals(0, EVALUATOR.terminalScore(0x55L, 0xaaL), "draw");
        assertEquals(
            Evaluator.WIN_SCORE + 12,
            Evaluator.terminalScoreForDifference(12),
            "positive terminal difference"
        );
        assertEquals(
            -Evaluator.WIN_SCORE - 12,
            Evaluator.terminalScoreForDifference(-12),
            "negative terminal difference"
        );
    }

    private static long selectMove(long moves, int index) {
        long remaining = moves;
        for (int current = 0; current < index; current++) {
            remaining ^= remaining & -remaining;
        }
        return remaining & -remaining;
    }

    private static long bit(int x, int y) {
        return 1L << CoordinateConverter.xyToSquare(x, y);
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(
                label + ": expected=" + expected + ", actual=" + actual
            );
        }
    }

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(
                label
                    + ": expected=0x" + Long.toHexString(expected)
                    + ", actual=0x" + Long.toHexString(actual)
            );
        }
    }
}
