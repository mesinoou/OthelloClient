public final class HumanVsAiGameTest {

    private HumanVsAiGameTest() {
    }

    public static void main(String[] args) {
        testInitialPositionAndHumanMove();
        testAiMoveAndUndoRound();
        testHumanAsWhite();
        testCompleteGame();
        testIllegalMoveRejected();
        System.out.println("HumanVsAiGameTest: PASS");
    }

    private static void testInitialPositionAndHumanMove() {
        HumanVsAiGame game = new HumanVsAiGame(HumanVsAiGame.BLACK);
        assertTrue(game.isHumanTurn(), "black human starts");
        assertEquals(4, Long.bitCount(game.legalMoves()), "initial moves");
        assertEquals(2, game.blackDiscs(), "initial black discs");
        assertEquals(2, game.whiteDiscs(), "initial white discs");

        int square = firstSquare(game.legalMoves());
        HumanVsAiGame.MoveOutcome outcome = game.playHuman(square);
        assertEquals(HumanVsAiGame.BLACK, outcome.playedColor(), "played color");
        assertEquals(square, game.lastSquare(), "last square");
        assertEquals(4, game.blackDiscs(), "black discs after move");
        assertEquals(1, game.whiteDiscs(), "white discs after move");
        assertTrue(!game.isHumanTurn(), "AI follows human");
        assertTrue(game.canUndo(), "human move can be undone");
    }

    private static void testAiMoveAndUndoRound() {
        HumanVsAiGame game = new HumanVsAiGame(HumanVsAiGame.BLACK);
        BitBoardPosition initial = game.position();
        game.playHuman(firstSquare(game.legalMoves()));
        game.playAi(firstSquare(game.legalMoves()));

        assertTrue(game.isHumanTurn(), "human turn after AI");
        assertTrue(game.undoHumanTurn(), "undo succeeds");
        assertEquals(initial, game.position(), "undo restores initial board");
        assertEquals(-1, game.lastSquare(), "undo restores last move");
        assertTrue(!game.canUndo(), "no human moves remain");
    }

    private static void testHumanAsWhite() {
        HumanVsAiGame game = new HumanVsAiGame(HumanVsAiGame.WHITE);
        assertTrue(!game.isHumanTurn(), "AI black starts");
        game.playAi(firstSquare(game.legalMoves()));
        assertTrue(game.isHumanTurn(), "white human follows AI");
        assertTrue(!game.canUndo(), "AI opening alone is not undoable");
    }

    private static void testCompleteGame() {
        HumanVsAiGame game = new HumanVsAiGame(HumanVsAiGame.BLACK);
        int moves = 0;
        int passes = 0;
        while (!game.isGameOver()) {
            int before = game.currentColor();
            int square = firstSquare(game.legalMoves());
            HumanVsAiGame.MoveOutcome outcome = before == game.humanColor()
                ? game.playHuman(square)
                : game.playAi(square);
            moves++;
            if (outcome.passedColor() != 0) {
                passes++;
                assertEquals(before, game.currentColor(), "turn after pass");
            }
            if (moves > 60) {
                throw new AssertionError("game did not terminate");
            }
        }
        assertTrue(moves > 0, "moves played");
        assertEquals(0, game.legalMoves(), "no legal moves after game");
        assertEquals(
            0,
            BitBoard.legalMoves(game.position().black(), game.position().white()),
            "black has no move"
        );
        assertEquals(
            0,
            BitBoard.legalMoves(game.position().white(), game.position().black()),
            "white has no move"
        );
        assertTrue(passes <= moves, "pass count is valid");
    }

    private static void testIllegalMoveRejected() {
        HumanVsAiGame game = new HumanVsAiGame(HumanVsAiGame.BLACK);
        try {
            game.playHuman(0);
            throw new AssertionError("illegal move was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static int firstSquare(long moves) {
        if (moves == 0L) {
            throw new AssertionError("expected a legal move");
        }
        return Long.numberOfTrailingZeros(moves);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(
                message + ": expected=" + expected + ", actual=" + actual
            );
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(
                message + ": expected=" + expected + ", actual=" + actual
            );
        }
    }

    private static void assertEquals(
        Object expected,
        Object actual,
        String message
    ) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                message + ": expected=" + expected + ", actual=" + actual
            );
        }
    }
}
