public final class BitBoard {

    private static final long A_FILE = 0x0101010101010101L;
    private static final long H_FILE = 0x8080808080808080L;

    private static final int NORTH = 0;
    private static final int NORTH_EAST = 1;
    private static final int EAST = 2;
    private static final int SOUTH_EAST = 3;
    private static final int SOUTH = 4;
    private static final int SOUTH_WEST = 5;
    private static final int WEST = 6;
    private static final int NORTH_WEST = 7;
    private static final int DIRECTION_COUNT = 8;

    private BitBoard() {
    }

    public static long legalMoves(long player, long opponent) {
        long empty = ~(player | opponent);
        long moves = 0L;

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            long captured = shift(player, direction) & opponent;
            long frontier = captured;

            while (frontier != 0L) {
                frontier = shift(frontier, direction) & opponent;
                captured |= frontier;
            }

            moves |= shift(captured, direction) & empty;
        }

        return moves;
    }

    public static long flips(long player, long opponent, long move) {
        if (move == 0L
            || (move & (move - 1L)) != 0L
            || ((player | opponent) & move) != 0L) {
            return 0L;
        }

        long allFlips = 0L;
        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            long captured = 0L;
            long cursor = shift(move, direction);

            while ((cursor & opponent) != 0L) {
                captured |= cursor;
                cursor = shift(cursor, direction);
            }

            if ((cursor & player) != 0L) {
                allFlips |= captured;
            }
        }

        return allFlips;
    }

    public static boolean isLegalMove(
        long player,
        long opponent,
        long move
    ) {
        return flips(player, opponent, move) != 0L;
    }

    public static long applyPlayerBoard(
        long player,
        long move,
        long flips
    ) {
        return player | move | flips;
    }

    public static long applyOpponentBoard(long opponent, long flips) {
        return opponent & ~flips;
    }

    public static int count(long board) {
        return Long.bitCount(board);
    }

    public static int countEmpty(long player, long opponent) {
        return Long.bitCount(~(player | opponent));
    }

    public static boolean isGameOver(long player, long opponent) {
        return legalMoves(player, opponent) == 0L
            && legalMoves(opponent, player) == 0L;
    }

    public static long transformBoard(long board, Symmetry symmetry) {
        requireSymmetry(symmetry);
        long transformed = 0L;
        long remaining = board;

        while (remaining != 0L) {
            long bit = remaining & -remaining;
            remaining ^= bit;
            int square = Long.numberOfTrailingZeros(bit);
            transformed |= 1L << transformSquare(square, symmetry);
        }

        return transformed;
    }

    public static int transformSquare(int square, Symmetry symmetry) {
        requireSymmetry(symmetry);
        int x = CoordinateConverter.squareToX(square);
        int y = CoordinateConverter.squareToY(square);
        int transformedX;
        int transformedY;

        switch (symmetry) {
            case IDENTITY:
                transformedX = x;
                transformedY = y;
                break;
            case ROTATE_90:
                transformedX = 7 - y;
                transformedY = x;
                break;
            case ROTATE_180:
                transformedX = 7 - x;
                transformedY = 7 - y;
                break;
            case ROTATE_270:
                transformedX = y;
                transformedY = 7 - x;
                break;
            case MIRROR_HORIZONTAL:
                transformedX = x;
                transformedY = 7 - y;
                break;
            case MIRROR_VERTICAL:
                transformedX = 7 - x;
                transformedY = y;
                break;
            case MIRROR_MAIN_DIAGONAL:
                transformedX = y;
                transformedY = x;
                break;
            case MIRROR_ANTI_DIAGONAL:
                transformedX = 7 - y;
                transformedY = 7 - x;
                break;
            default:
                throw new AssertionError("unknown symmetry: " + symmetry);
        }

        return CoordinateConverter.xyToSquare(transformedX, transformedY);
    }

    public static int inverseTransformSquare(
        int square,
        Symmetry symmetry
    ) {
        requireSymmetry(symmetry);
        switch (symmetry) {
            case ROTATE_90:
                return transformSquare(square, Symmetry.ROTATE_270);
            case ROTATE_270:
                return transformSquare(square, Symmetry.ROTATE_90);
            default:
                return transformSquare(square, symmetry);
        }
    }

    private static long shift(long board, int direction) {
        switch (direction) {
            case NORTH:
                return board >>> 8;
            case NORTH_EAST:
                return (board & ~H_FILE) >>> 7;
            case EAST:
                return (board & ~H_FILE) << 1;
            case SOUTH_EAST:
                return (board & ~H_FILE) << 9;
            case SOUTH:
                return board << 8;
            case SOUTH_WEST:
                return (board & ~A_FILE) << 7;
            case WEST:
                return (board & ~A_FILE) >>> 1;
            case NORTH_WEST:
                return (board & ~A_FILE) >>> 9;
            default:
                throw new AssertionError("unknown direction: " + direction);
        }
    }

    private static void requireSymmetry(Symmetry symmetry) {
        if (symmetry == null) {
            throw new NullPointerException("symmetry");
        }
    }
}
