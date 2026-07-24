import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class OpeningBookTest {

    private OpeningBookTest() {
    }

    public static void main(String[] args) throws Exception {
        testCanonicalPositionAndMove();
        testOptimizationConditions();
        testGeneratedBook();
        testBookSequenceAndFallback();
        testWthorImporter();
        testWthorZipImporter();
        testIncompleteWthorGameIsRejectedByBuilder();
        testCorruptBookIsRejected();
        System.out.println("OpeningBookTest: PASS");
    }

    private static void testOptimizationConditions() {
        assertEquals(
            64,
            OpeningBookBuilder.requiredMoveGames(0, 8),
            "early support"
        );
        assertEquals(
            24,
            OpeningBookBuilder.requiredMoveGames(6, 8),
            "middle opening support"
        );
        assertEquals(
            8,
            OpeningBookBuilder.requiredMoveGames(12, 8),
            "late opening support"
        );

        int smallSample = OpeningBookBuilder.confidenceLowerPermille(
            10,
            6,
            0
        );
        int largeSample = OpeningBookBuilder.confidenceLowerPermille(
            100,
            60,
            0
        );
        if (largeSample <= smallSample) {
            throw new AssertionError(
                "同じ勝率で大標本の信頼下限が高くなっていません。"
            );
        }
    }

    private static void testCanonicalPositionAndMove() {
        PositionToMove asymmetric = createAsymmetricPosition();
        long player = asymmetric.position.player(asymmetric.color);
        long opponent = asymmetric.position.opponent(asymmetric.color);
        long legalMoves = BitBoard.legalMoves(player, opponent);
        int square = Long.numberOfTrailingZeros(legalMoves);

        CanonicalPosition expected = PositionCanonicalizer.canonicalize(
            player,
            opponent
        );
        int expectedSquare = expected.toCanonicalSquare(square);
        for (Symmetry symmetry : Symmetry.values()) {
            long transformedPlayer = BitBoard.transformBoard(player, symmetry);
            long transformedOpponent = BitBoard.transformBoard(
                opponent,
                symmetry
            );
            int transformedSquare = BitBoard.transformSquare(square, symmetry);
            CanonicalPosition actual = PositionCanonicalizer.canonicalize(
                transformedPlayer,
                transformedOpponent
            );

            assertEquals(
                expected.key().player(),
                actual.key().player(),
                "canonical player"
            );
            assertEquals(
                expected.key().opponent(),
                actual.key().opponent(),
                "canonical opponent"
            );
            assertEquals(
                expectedSquare,
                actual.toCanonicalSquare(transformedSquare),
                "canonical move " + symmetry
            );
            assertEquals(
                transformedSquare,
                actual.fromCanonicalSquare(expectedSquare),
                "inverse move " + symmetry
            );
        }
    }

    private static void testGeneratedBook() throws IOException {
        OpeningBook book = OpeningBook.load(
            Paths.get("data", "opening-book.bin")
        );
        assertEquals(4252, book.size(), "book entries");
        assertEquals(58252, book.sourceGames(), "source games");
        assertEquals(18, book.maximumPly(), "maximum ply");

        BitBoardPosition initial = BitBoardPosition.initial();
        OpeningBookMove move = book.find(initial, 1);
        if (move == null) {
            throw new AssertionError("初期局面が定石に登録されていません。");
        }
        assertLegal(initial, 1, move.square());
        if (move.games() < 50_000) {
            throw new AssertionError("初手の集計局数が不足しています。");
        }

        for (Symmetry symmetry : Symmetry.values()) {
            long player = BitBoard.transformBoard(initial.black(), symmetry);
            long opponent = BitBoard.transformBoard(initial.white(), symmetry);
            OpeningBookMove transformed = book.find(player, opponent);
            if (transformed == null) {
                throw new AssertionError(
                    "対称初期局面を検索できません: " + symmetry
                );
            }
            long bit = 1L << transformed.square();
            if (!BitBoard.isLegalMove(player, opponent, bit)) {
                throw new AssertionError(
                    "対称初期局面の定石手が非合法です: " + symmetry
                );
            }
        }
    }

    private static void testBookSequenceAndFallback() throws IOException {
        OpeningBook book = OpeningBook.load(
            Paths.get("data", "opening-book.bin")
        );
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        int hits = 0;

        for (int ply = 0; ply < 18; ply++) {
            long player = position.player(color);
            long opponent = position.opponent(color);
            if (BitBoard.legalMoves(player, opponent) == 0L) {
                color = -color;
            }

            OpeningBookMove bookMove = book.find(position, color);
            if (bookMove == null) {
                break;
            }
            assertLegal(position, color, bookMove.square());
            position = apply(position, color, bookMove.square());
            color = -color;
            hits++;
        }
        if (hits < 10) {
            throw new AssertionError(
                "主要定石系列が短すぎます: hits=" + hits
            );
        }

        OthelloAI withoutBook = new OthelloAI(
            OpeningBook.empty(),
            new SearchEngine()
        );
        SearchResult fallback = withoutBook.chooseMove(
            BitBoardPosition.initial(),
            1,
            new SearchLimits(1L, 64, 1)
        );
        if (fallback.openingBookMove()) {
            throw new AssertionError("空の定石表で定石手が返されました。");
        }
        assertLegal(BitBoardPosition.initial(), 1, fallback.bestSquare());
    }

    private static void testWthorImporter() throws IOException {
        byte[] bytes = oneGameWthorBytes();

        Path path = Files.createTempFile("wthor-test", ".wtb");
        try {
            Files.write(path, bytes);
            List<WthorGame> games = WthorImporter.read(path);
            assertEquals(1, games.size(), "WTHOR game count");
            assertEquals(32, games.get(0).blackScore(), "WTHOR score");
            assertEquals(1, games.get(0).moveCount(), "WTHOR move count");
            assertEquals(
                CoordinateConverter.xyToSquare(5, 4),
                WthorImporter.squareFromMoveCode(games.get(0).moveCode(0)),
                "WTHOR move"
            );
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void testWthorZipImporter() throws IOException {
        Path path = Files.createTempFile("wthor-test", ".zip");
        try {
            try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(path)
            )) {
                output.putNextEntry(new ZipEntry("WTH_TEST.wtb"));
                output.write(oneGameWthorBytes());
                output.closeEntry();
                output.putNextEntry(new ZipEntry("WTHOR.TRN"));
                output.write(new byte[] {1, 2, 3});
                output.closeEntry();
            }
            List<WthorGame> games = WthorImporter.read(path);
            assertEquals(1, games.size(), "zipped WTHOR game count");
            assertEquals(32, games.get(0).blackScore(), "zipped WTHOR score");
            assertEquals(1, games.get(0).moveCount(), "zipped WTHOR moves");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static byte[] oneGameWthorBytes() {
        byte[] bytes = new byte[16 + 68];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4, 1);
        bytes[12] = 8;
        bytes[13] = 0;
        bytes[16 + 6] = 32;
        bytes[16 + 8] = 56;
        return bytes;
    }

    private static void testIncompleteWthorGameIsRejectedByBuilder()
        throws IOException {
        Path archive = Files.createTempFile("wthor-incomplete", ".wtb");
        Path output = Files.createTempFile("opening-book-incomplete", ".bin");
        try {
            Files.write(archive, oneGameWthorBytes());
            OpeningBookBuilder builder = new OpeningBookBuilder(8, 1, 2);
            builder.addArchive(archive);
            builder.write(output);
            OpeningBook book = OpeningBook.load(output);
            assertEquals(0, book.sourceGames(), "accepted incomplete games");
            assertEquals(0, book.size(), "entries from incomplete games");
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(output);
        }
    }

    private static void testCorruptBookIsRejected() throws IOException {
        Path path = Files.createTempFile("opening-book-test", ".bin");
        try {
            Files.write(path, new byte[OpeningBook.HEADER_BYTES]);
            try {
                OpeningBook.load(path);
                throw new AssertionError("破損した定石表を読み込みました。");
            } catch (IOException expected) {
                // Rejection is the expected behavior.
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static PositionToMove createAsymmetricPosition() {
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        for (int ply = 0; ply < 7; ply++) {
            long moves = BitBoard.legalMoves(
                position.player(color),
                position.opponent(color)
            );
            if (moves == 0L) {
                color = -color;
                continue;
            }
            int moveIndex = Math.min(ply % 3, BitBoard.count(moves) - 1);
            long move = selectMove(moves, moveIndex);
            int square = Long.numberOfTrailingZeros(move);
            position = apply(position, color, square);
            color = -color;
        }
        return new PositionToMove(position, color);
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

    private static long selectMove(long moves, int index) {
        long remaining = moves;
        for (int current = 0; current < index; current++) {
            remaining ^= remaining & -remaining;
        }
        return remaining & -remaining;
    }

    private static void assertLegal(
        BitBoardPosition position,
        int color,
        int square
    ) {
        if (square < 0 || square >= 64 || !BitBoard.isLegalMove(
            position.player(color),
            position.opponent(color),
            1L << square
        )) {
            throw new AssertionError("定石または探索結果が非合法です: " + square);
        }
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

    private static final class PositionToMove {

        private final BitBoardPosition position;
        private final int color;

        PositionToMove(BitBoardPosition position, int color) {
            this.position = position;
            this.color = color;
        }
    }
}
