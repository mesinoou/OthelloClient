import java.util.Random;

public final class BitBoardTest {

    private BitBoardTest() {
    }

    public static void main(String[] args) {
        testInitialLegalMoves();
        testAllFlipDirections();
        testMultipleDirectionFlip();
        testBoardEdgeDoesNotWrap();
        testApplyMove();
        testPassAndGameOver();
        testCoordinates();
        testSymmetries();
        testPositionValidation();
        testRandomizedReference();
        System.out.println("BitBoardTest: PASS");
    }

    private static void testInitialLegalMoves() {
        BitBoardPosition position = initialPosition();
        long expectedBlack = bits(4, 2, 5, 3, 2, 4, 3, 5);
        long expectedWhite = bits(3, 2, 2, 3, 5, 4, 4, 5);

        assertEquals(
            expectedBlack,
            BitBoard.legalMoves(position.black(), position.white()),
            "initial black legal moves"
        );
        assertEquals(
            expectedWhite,
            BitBoard.legalMoves(position.white(), position.black()),
            "initial white legal moves"
        );
    }

    private static void testAllFlipDirections() {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }

                long move = bit(3, 3);
                long opponent = bit(3 + dx, 3 + dy);
                long player = bit(3 + 2 * dx, 3 + 2 * dy);
                assertEquals(
                    opponent,
                    BitBoard.flips(player, opponent, move),
                    "flip direction dx=" + dx + ", dy=" + dy
                );
            }
        }
    }

    private static void testMultipleDirectionFlip() {
        long player = 0L;
        long opponent = 0L;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                opponent |= bit(3 + dx, 3 + dy);
                player |= bit(3 + 2 * dx, 3 + 2 * dy);
            }
        }

        assertEquals(
            opponent,
            BitBoard.flips(player, opponent, bit(3, 3)),
            "multiple direction flip"
        );
    }

    private static void testBoardEdgeDoesNotWrap() {
        long player = bit(6, 3);
        long opponent = bit(7, 3);
        long wrappedSquare = bit(0, 4);

        assertFalse(
            BitBoard.isLegalMove(player, opponent, wrappedSquare),
            "east edge must not wrap to the next row"
        );
        assertEquals(
            0L,
            BitBoard.legalMoves(player, opponent) & wrappedSquare,
            "wrapped square must not appear in legal moves"
        );
    }

    private static void testApplyMove() {
        BitBoardPosition position = initialPosition();
        long move = bit(4, 2);
        long flips = BitBoard.flips(position.black(), position.white(), move);
        long black = BitBoard.applyPlayerBoard(position.black(), move, flips);
        long white = BitBoard.applyOpponentBoard(position.white(), flips);

        assertEquals(bit(4, 3), flips, "initial move flips");
        assertEquals(4, BitBoard.count(black), "black count after move");
        assertEquals(1, BitBoard.count(white), "white count after move");
        assertEquals(59, BitBoard.countEmpty(black, white), "empty count");
    }

    private static void testPassAndGameOver() {
        long black = bit(0, 0);
        long white = bit(1, 0);

        assertEquals(
            0L,
            BitBoard.legalMoves(white, black),
            "white must pass"
        );
        assertTrue(
            (BitBoard.legalMoves(black, white) & bit(2, 0)) != 0L,
            "black must have a legal move"
        );
        assertFalse(BitBoard.isGameOver(white, black), "pass is not game over");
        assertTrue(BitBoard.isGameOver(-1L, 0L), "full board is game over");
    }

    private static void testCoordinates() {
        assertEquals(0, CoordinateConverter.serverIndexToSquare(0), "server 0");
        assertEquals(56, CoordinateConverter.serverIndexToSquare(7), "server 7");
        assertEquals(1, CoordinateConverter.serverIndexToSquare(8), "server 8");
        assertEquals(63, CoordinateConverter.serverIndexToSquare(63), "server 63");

        for (int square = 0; square < 64; square++) {
            int serverIndex = CoordinateConverter.squareToServerIndex(square);
            assertEquals(
                square,
                CoordinateConverter.serverIndexToSquare(serverIndex),
                "coordinate round trip " + square
            );
        }
    }

    private static void testSymmetries() {
        int square = CoordinateConverter.xyToSquare(1, 2);
        assertSquare(1, 2, square, Symmetry.IDENTITY);
        assertSquare(5, 1, square, Symmetry.ROTATE_90);
        assertSquare(6, 5, square, Symmetry.ROTATE_180);
        assertSquare(2, 6, square, Symmetry.ROTATE_270);
        assertSquare(1, 5, square, Symmetry.MIRROR_HORIZONTAL);
        assertSquare(6, 2, square, Symmetry.MIRROR_VERTICAL);
        assertSquare(2, 1, square, Symmetry.MIRROR_MAIN_DIAGONAL);
        assertSquare(5, 6, square, Symmetry.MIRROR_ANTI_DIAGONAL);

        long board = bits(0, 0, 2, 1, 5, 3, 1, 7);
        for (Symmetry symmetry : Symmetry.values()) {
            long transformed = BitBoard.transformBoard(board, symmetry);
            long restored = 0L;
            long remaining = transformed;
            while (remaining != 0L) {
                long transformedBit = remaining & -remaining;
                remaining ^= transformedBit;
                int transformedSquare =
                    Long.numberOfTrailingZeros(transformedBit);
                int originalSquare = BitBoard.inverseTransformSquare(
                    transformedSquare,
                    symmetry
                );
                restored |= 1L << originalSquare;
            }

            assertEquals(board, restored, "symmetry inverse " + symmetry);
            assertEquals(
                BitBoard.count(board),
                BitBoard.count(transformed),
                "symmetry count " + symmetry
            );
        }
    }

    private static void testPositionValidation() {
        BitBoardPosition position = initialPosition();
        assertEquals(position.black(), position.player(1), "black player view");
        assertEquals(position.white(), position.player(-1), "white player view");
        assertEquals(60, position.emptyCount(), "initial empty count");

        try {
            new BitBoardPosition(1L, 1L);
            throw new AssertionError("overlapping position must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void testRandomizedReference() {
        Random random = new Random(0x4f7468656c6c6fL);
        for (int sample = 0; sample < 2000; sample++) {
            long player = random.nextLong();
            long opponent = random.nextLong() & ~player;
            long expectedMoves = slowLegalMoves(player, opponent);
            long actualMoves = BitBoard.legalMoves(player, opponent);
            assertEquals(expectedMoves, actualMoves, "random legal " + sample);

            long empty = ~(player | opponent);
            while (empty != 0L) {
                long move = empty & -empty;
                empty ^= move;
                assertEquals(
                    slowFlips(player, opponent, move),
                    BitBoard.flips(player, opponent, move),
                    "random flips " + sample + ":"
                        + Long.numberOfTrailingZeros(move)
                );
            }
        }
    }

    private static long slowLegalMoves(long player, long opponent) {
        long moves = 0L;
        long empty = ~(player | opponent);
        while (empty != 0L) {
            long move = empty & -empty;
            empty ^= move;
            if (slowFlips(player, opponent, move) != 0L) {
                moves |= move;
            }
        }
        return moves;
    }

    private static long slowFlips(long player, long opponent, long move) {
        int square = Long.numberOfTrailingZeros(move);
        int x = CoordinateConverter.squareToX(square);
        int y = CoordinateConverter.squareToY(square);
        long flips = 0L;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }

                int nextX = x + dx;
                int nextY = y + dy;
                long captured = 0L;
                while (isInside(nextX, nextY)) {
                    long bit = bit(nextX, nextY);
                    if ((opponent & bit) != 0L) {
                        captured |= bit;
                    } else {
                        if ((player & bit) != 0L) {
                            flips |= captured;
                        }
                        break;
                    }
                    nextX += dx;
                    nextY += dy;
                }
            }
        }
        return flips;
    }

    private static BitBoardPosition initialPosition() {
        return BitBoardPosition.initial();
    }

    private static long bits(int... coordinates) {
        long board = 0L;
        for (int index = 0; index < coordinates.length; index += 2) {
            board |= bit(coordinates[index], coordinates[index + 1]);
        }
        return board;
    }

    private static long bit(int x, int y) {
        return 1L << CoordinateConverter.xyToSquare(x, y);
    }

    private static boolean isInside(int x, int y) {
        return x >= 0 && x < 8 && y >= 0 && y < 8;
    }

    private static void assertSquare(
        int expectedX,
        int expectedY,
        int square,
        Symmetry symmetry
    ) {
        int transformed = BitBoard.transformSquare(square, symmetry);
        assertEquals(
            CoordinateConverter.xyToSquare(expectedX, expectedY),
            transformed,
            "square transform " + symmetry
        );
        assertEquals(
            square,
            BitBoard.inverseTransformSquare(transformed, symmetry),
            "square inverse " + symmetry
        );
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(
                message + ": expected=0x" + Long.toHexString(expected)
                    + ", actual=0x" + Long.toHexString(actual)
            );
        }
    }
}
