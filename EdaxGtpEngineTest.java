import java.nio.file.Path;
import java.nio.file.Paths;

public final class EdaxGtpEngineTest {

    private static final Path EXECUTABLE = Paths.get(
        "benchmark",
        "edax",
        "wEdax-x86-64-v3.exe"
    );
    private static final Path EVALUATION = Paths.get(
        "benchmark",
        "edax",
        "data",
        "eval.dat"
    );

    private EdaxGtpEngineTest() {
    }

    public static void main(String[] args) throws Exception {
        testCoordinateRoundTrip();
        testLiveGtpMoves();
        System.out.println("EdaxGtpEngineTest: PASS");
    }

    private static void testCoordinateRoundTrip() {
        for (int square = 0; square < 64; square++) {
            String coordinate =
                EdaxGtpEngine.serverSquareToEdaxCoordinate(square);
            int restored =
                EdaxGtpEngine.edaxCoordinateToServerSquare(coordinate);
            if (restored != square) {
                throw new AssertionError(
                    "Edax座標の往復に失敗しました: square=" + square
                );
            }
        }
        int serverD6 = CoordinateConverter.xyToSquare(3, 5);
        if (!"e6".equals(
            EdaxGtpEngine.serverSquareToEdaxCoordinate(serverD6)
        )) {
            throw new AssertionError("初期盤面の左右反転座標が不正です。");
        }
    }

    private static void testLiveGtpMoves() throws Exception {
        BitBoardPosition position = BitBoardPosition.initial();
        try (EdaxGtpEngine engine = new EdaxGtpEngine(
            EXECUTABLE,
            EVALUATION,
            2,
            1
        )) {
            int blackSquare = engine.generateMove(1);
            assertLegal(position, 1, blackSquare);
            position = apply(position, 1, blackSquare);

            long whiteMoves = BitBoard.legalMoves(
                position.white(),
                position.black()
            );
            int whiteSquare = Long.numberOfTrailingZeros(whiteMoves);
            engine.play(-1, whiteSquare);
            position = apply(position, -1, whiteSquare);

            int secondBlackSquare = engine.generateMove(1);
            assertLegal(position, 1, secondBlackSquare);
        }
    }

    private static void assertLegal(
        BitBoardPosition position,
        int color,
        int square
    ) {
        if (square < 0 || !BitBoard.isLegalMove(
            position.player(color),
            position.opponent(color),
            1L << square
        )) {
            throw new AssertionError(
                "Edaxが非合法手を返しました: color="
                    + color + ", square=" + square
            );
        }
    }

    private static BitBoardPosition apply(
        BitBoardPosition position,
        int color,
        int square
    ) {
        long player = position.player(color);
        long opponent = position.opponent(color);
        long move = 1L << square;
        long flips = BitBoard.flips(player, opponent, move);
        long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
        long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
        return color == 1
            ? new BitBoardPosition(nextPlayer, nextOpponent)
            : new BitBoardPosition(nextOpponent, nextPlayer);
    }
}
