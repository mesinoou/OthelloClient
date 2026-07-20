import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GameReplayerTest {

    private GameReplayerTest() {
    }

    public static void main(String[] args) throws Exception {
        testParser();
        testSyntheticPass();
        testIllegalMove();
        testIncompleteGame();
        testGeneratedCompleteGame();
        System.out.println("GameReplayerTest: PASS");
    }

    private static void testParser() throws Exception {
        GameRecord game = GameReplayer.read(
            new StringReader("B:BlackAI,W:WhiteAI\n4,2,1\n"),
            "memory"
        );
        assertEquals("BlackAI", game.blackName(), "black name");
        assertEquals("WhiteAI", game.whiteName(), "white name");
        assertEquals(1, game.moves().size(), "parsed move count");
        assertEquals(4, game.moves().get(0).x(), "parsed x");
        assertEquals(2, game.moves().get(0).y(), "parsed y");
        assertEquals(1, game.moves().get(0).color(), "parsed color");
    }

    private static void testSyntheticPass() {
        BitBoardPosition position = new BitBoardPosition(bit(0, 0), bit(1, 0));
        GameRecord game = new GameRecord(
            "Black",
            "White",
            Collections.singletonList(new GameMove(2, 0, 1))
        );

        GameReplayResult result = GameReplayer.replay(game, position, -1);
        assertEquals(1, result.passCount(), "pass count");
        assertEquals(3, result.blackCount(), "pass final black count");
        assertEquals(0, result.whiteCount(), "pass final white count");
        assertEquals("Black", result.winnerName(), "pass winner");
    }

    private static void testIllegalMove() {
        GameRecord game = new GameRecord(
            "Black",
            "White",
            Collections.singletonList(new GameMove(0, 0, 1))
        );
        assertRejected(() -> GameReplayer.replay(game), "illegal move");
    }

    private static void testIncompleteGame() {
        GameRecord game = new GameRecord(
            "Black",
            "White",
            Collections.singletonList(new GameMove(4, 2, 1))
        );
        assertRejected(() -> GameReplayer.replay(game), "ended before");
    }

    private static void testGeneratedCompleteGame() {
        BitBoardPosition position = BitBoardPosition.initial();
        long black = position.black();
        long white = position.white();
        int color = 1;
        int generatedPasses = 0;
        List<GameMove> moves = new ArrayList<>();

        while (!BitBoard.isGameOver(
            color == 1 ? black : white,
            color == 1 ? white : black
        )) {
            long player = color == 1 ? black : white;
            long opponent = color == 1 ? white : black;
            long legalMoves = BitBoard.legalMoves(player, opponent);
            if (legalMoves == 0L) {
                generatedPasses++;
                color = -color;
                continue;
            }

            long move = legalMoves & -legalMoves;
            int square = Long.numberOfTrailingZeros(move);
            moves.add(new GameMove(
                CoordinateConverter.squareToX(square),
                CoordinateConverter.squareToY(square),
                color
            ));

            long flips = BitBoard.flips(player, opponent, move);
            long movedPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long movedOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            if (color == 1) {
                black = movedPlayer;
                white = movedOpponent;
            } else {
                white = movedPlayer;
                black = movedOpponent;
            }
            color = -color;
        }

        GameRecord game = new GameRecord("Black", "White", moves);
        GameReplayResult result = GameReplayer.replay(game);
        assertEquals(moves.size(), result.moveCount(), "generated moves");
        assertEquals(generatedPasses, result.passCount(), "generated passes");
        assertEquals(black, result.finalPosition().black(), "generated black");
        assertEquals(white, result.finalPosition().white(), "generated white");
    }

    private static long bit(int x, int y) {
        return 1L << CoordinateConverter.xyToSquare(x, y);
    }

    private static void assertRejected(Runnable action, String expectedText) {
        try {
            action.run();
            throw new AssertionError("expected replay rejection: " + expectedText);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains(expectedText)) {
                throw new AssertionError(
                    "unexpected rejection: " + e.getMessage(),
                    e
                );
            }
        }
    }

    private static void assertEquals(
        String expected,
        String actual,
        String message
    ) {
        if (!expected.equals(actual)) {
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
}
