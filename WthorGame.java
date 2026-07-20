public final class WthorGame {

    private final int blackScore;
    private final byte[] moves;

    public WthorGame(int blackScore, byte[] moves) {
        if (blackScore < 0 || blackScore > 64) {
            throw new IllegalArgumentException("invalid black score");
        }
        if (moves == null) {
            throw new NullPointerException("moves");
        }
        this.blackScore = blackScore;
        this.moves = moves.clone();
    }

    public int blackScore() {
        return blackScore;
    }

    public int moveCount() {
        return moves.length;
    }

    public int moveCode(int index) {
        return Byte.toUnsignedInt(moves[index]);
    }
}
