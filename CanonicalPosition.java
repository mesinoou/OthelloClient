public final class CanonicalPosition {

    private final PositionKey key;
    private final Symmetry symmetry;

    public CanonicalPosition(PositionKey key, Symmetry symmetry) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (symmetry == null) {
            throw new NullPointerException("symmetry");
        }
        this.key = key;
        this.symmetry = symmetry;
    }

    public PositionKey key() {
        return key;
    }

    public Symmetry symmetry() {
        return symmetry;
    }

    public int toCanonicalSquare(int square) {
        return BitBoard.transformSquare(square, symmetry);
    }

    public int fromCanonicalSquare(int square) {
        return BitBoard.inverseTransformSquare(square, symmetry);
    }
}
