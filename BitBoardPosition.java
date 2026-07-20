public final class BitBoardPosition {

    private final long black;
    private final long white;

    public BitBoardPosition(long black, long white) {
        if ((black & white) != 0L) {
            throw new IllegalArgumentException(
                "black and white bitboards overlap"
            );
        }
        this.black = black;
        this.white = white;
    }

    public static BitBoardPosition initial() {
        long black = bit(3, 3) | bit(4, 4);
        long white = bit(4, 3) | bit(3, 4);
        return new BitBoardPosition(black, white);
    }

    public long black() {
        return black;
    }

    public long white() {
        return white;
    }

    public long player(int color) {
        checkColor(color);
        return color == 1 ? black : white;
    }

    public long opponent(int color) {
        checkColor(color);
        return color == 1 ? white : black;
    }

    public int emptyCount() {
        return BitBoard.countEmpty(black, white);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BitBoardPosition)) {
            return false;
        }
        BitBoardPosition that = (BitBoardPosition) other;
        return black == that.black && white == that.white;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(black);
        return 31 * result + Long.hashCode(white);
    }

    private static void checkColor(int color) {
        if (color != 1 && color != -1) {
            throw new IllegalArgumentException("color must be 1 or -1");
        }
    }

    private static long bit(int x, int y) {
        return 1L << CoordinateConverter.xyToSquare(x, y);
    }
}
