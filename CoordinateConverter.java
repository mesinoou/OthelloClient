public final class CoordinateConverter {

    public static final int BOARD_SIZE = 8;
    public static final int SQUARE_COUNT = BOARD_SIZE * BOARD_SIZE;

    private CoordinateConverter() {
    }

    public static int serverIndexToSquare(int serverIndex) {
        checkSquare(serverIndex, "serverIndex");
        int x = serverIndex / BOARD_SIZE;
        int y = serverIndex % BOARD_SIZE;
        return xyToSquare(x, y);
    }

    public static int squareToServerIndex(int square) {
        checkSquare(square, "square");
        return squareToX(square) * BOARD_SIZE + squareToY(square);
    }

    public static int xyToSquare(int x, int y) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
            throw new IllegalArgumentException(
                "coordinates out of range: x=" + x + ", y=" + y
            );
        }
        return y * BOARD_SIZE + x;
    }

    public static int squareToX(int square) {
        checkSquare(square, "square");
        return square % BOARD_SIZE;
    }

    public static int squareToY(int square) {
        checkSquare(square, "square");
        return square / BOARD_SIZE;
    }

    private static void checkSquare(int value, String name) {
        if (value < 0 || value >= SQUARE_COUNT) {
            throw new IllegalArgumentException(
                name + " out of range: " + value
            );
        }
    }
}
