public final class GameMove {

    private final int x;
    private final int y;
    private final int color;

    public GameMove(int x, int y, int color) {
        CoordinateConverter.xyToSquare(x, y);
        if (color != 1 && color != -1) {
            throw new IllegalArgumentException("color must be 1 or -1");
        }
        this.x = x;
        this.y = y;
        this.color = color;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int color() {
        return color;
    }

    public int square() {
        return CoordinateConverter.xyToSquare(x, y);
    }

    public long bit() {
        return 1L << square();
    }

    @Override
    public String toString() {
        return x + "," + y + "," + color;
    }
}
