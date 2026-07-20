public final class PositionKey {

    private final long player;
    private final long opponent;

    public PositionKey(long player, long opponent) {
        if ((player & opponent) != 0L) {
            throw new IllegalArgumentException("position boards overlap");
        }
        this.player = player;
        this.opponent = opponent;
    }

    public long player() {
        return player;
    }

    public long opponent() {
        return opponent;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PositionKey)) {
            return false;
        }
        PositionKey that = (PositionKey) other;
        return player == that.player && opponent == that.opponent;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(player);
        return 31 * result + Long.hashCode(opponent);
    }

    public static int compare(
        long leftPlayer,
        long leftOpponent,
        long rightPlayer,
        long rightOpponent
    ) {
        int playerComparison = Long.compareUnsigned(
            leftPlayer,
            rightPlayer
        );
        if (playerComparison != 0) {
            return playerComparison;
        }
        return Long.compareUnsigned(leftOpponent, rightOpponent);
    }
}
