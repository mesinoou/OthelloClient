public final class PositionCanonicalizer {

    private static final Symmetry[] SYMMETRIES = Symmetry.values();

    private PositionCanonicalizer() {
    }

    public static CanonicalPosition canonicalize(
        long player,
        long opponent
    ) {
        if ((player & opponent) != 0L) {
            throw new IllegalArgumentException("position boards overlap");
        }

        long bestPlayer = player;
        long bestOpponent = opponent;
        Symmetry bestSymmetry = Symmetry.IDENTITY;

        for (int index = 1; index < SYMMETRIES.length; index++) {
            Symmetry symmetry = SYMMETRIES[index];
            long transformedPlayer = BitBoard.transformBoard(
                player,
                symmetry
            );
            long transformedOpponent = BitBoard.transformBoard(
                opponent,
                symmetry
            );
            if (PositionKey.compare(
                transformedPlayer,
                transformedOpponent,
                bestPlayer,
                bestOpponent
            ) < 0) {
                bestPlayer = transformedPlayer;
                bestOpponent = transformedOpponent;
                bestSymmetry = symmetry;
            }
        }

        return new CanonicalPosition(
            new PositionKey(bestPlayer, bestOpponent),
            bestSymmetry
        );
    }
}
