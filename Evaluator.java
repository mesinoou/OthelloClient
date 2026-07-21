public final class Evaluator implements PositionEvaluator {

    public static final int WIN_SCORE = 100_000;

    private static final long A_FILE = 0x0101010101010101L;
    private static final long H_FILE = 0x8080808080808080L;
    private static final long TOP_EDGE = 0x00000000000000ffL;
    private static final long BOTTOM_EDGE = 0xff00000000000000L;
    private static final long CORNERS = 0x8100000000000081L;

    private static final int[] CORNER_SQUARES = {0, 7, 56, 63};
    private static final int[] X_SQUARES = {9, 14, 49, 54};
    private static final int[] C_SQUARES_A = {1, 6, 48, 55};
    private static final int[] C_SQUARES_B = {8, 15, 57, 62};

    @Override
    public int evaluate(long player, long opponent) {
        long occupied = player | opponent;
        int empties = BitBoard.countEmpty(player, opponent);
        int occupiedCount = 64 - empties;
        int discDifference = BitBoard.count(player) - BitBoard.count(opponent);
        long playerMoves = BitBoard.legalMoves(player, opponent);
        long opponentMoves = BitBoard.legalMoves(opponent, player);
        int mobilityDifference = BitBoard.count(playerMoves)
            - BitBoard.count(opponentMoves);
        int cornerDifference = BitBoard.count(player & CORNERS)
            - BitBoard.count(opponent & CORNERS);
        int cornerMoveDifference = BitBoard.count(playerMoves & CORNERS)
            - BitBoard.count(opponentMoves & CORNERS);

        long empty = ~occupied;
        long adjacentToEmpty = neighbors(empty);
        int frontierDifference = BitBoard.count(player & adjacentToEmpty)
            - BitBoard.count(opponent & adjacentToEmpty);
        int stableDifference = BitBoard.count(
            stableEdgeDiscs(player, occupied)
        ) - BitBoard.count(stableEdgeDiscs(opponent, occupied));

        int score = weighted(
            discDifference,
            occupiedCount,
            1,
            3,
            18
        );
        score += weighted(
            mobilityDifference,
            occupiedCount,
            24,
            20,
            8
        );
        score += weighted(
            cornerDifference,
            occupiedCount,
            180,
            220,
            260
        );
        score += weighted(
            cornerMoveDifference,
            occupiedCount,
            100,
            120,
            80
        );
        score += weighted(
            frontierDifference,
            occupiedCount,
            -12,
            -14,
            -8
        );
        score += weighted(
            stableDifference,
            occupiedCount,
            60,
            80,
            100
        );
        score += cornerContextScore(player, opponent, occupiedCount);

        if (empties <= 20) {
            score += weighted(
                parityAccessDifference(
                    player,
                    opponent,
                    playerMoves,
                    opponentMoves
                ),
                occupiedCount,
                0,
                2,
                18
            );
        }

        return score;
    }

    @Override
    public int terminalScore(long player, long opponent) {
        int difference = BitBoard.count(player) - BitBoard.count(opponent);
        if (difference > 0) {
            return WIN_SCORE + difference;
        }
        if (difference < 0) {
            return -WIN_SCORE + difference;
        }
        return 0;
    }

    @Override
    public String description() {
        return "handcrafted-v0.1.0";
    }

    static int cornerContextScore(
        long player,
        long opponent,
        int occupiedCount
    ) {
        int score = 0;
        for (int index = 0; index < CORNER_SQUARES.length; index++) {
            long corner = 1L << CORNER_SQUARES[index];
            long xSquare = 1L << X_SQUARES[index];
            long cSquares = (1L << C_SQUARES_A[index])
                | (1L << C_SQUARES_B[index]);

            if (((player | opponent) & corner) == 0L) {
                int xDifference = contains(player, xSquare)
                    - contains(opponent, xSquare);
                int cDifference = BitBoard.count(player & cSquares)
                    - BitBoard.count(opponent & cSquares);
                score += weighted(
                    xDifference,
                    occupiedCount,
                    -90,
                    -70,
                    -20
                );
                score += weighted(
                    cDifference,
                    occupiedCount,
                    -45,
                    -35,
                    -10
                );
            } else if ((player & corner) != 0L) {
                score += weighted(
                    contains(player, xSquare),
                    occupiedCount,
                    8,
                    12,
                    20
                );
                score += weighted(
                    BitBoard.count(player & cSquares),
                    occupiedCount,
                    6,
                    10,
                    16
                );
            } else {
                score -= weighted(
                    contains(opponent, xSquare),
                    occupiedCount,
                    8,
                    12,
                    20
                );
                score -= weighted(
                    BitBoard.count(opponent & cSquares),
                    occupiedCount,
                    6,
                    10,
                    16
                );
            }
        }
        return score;
    }

    static long stableEdgeDiscs(long board, long occupied) {
        long stable = 0L;

        stable |= edgeRun(board, 0, 1);
        stable |= edgeRun(board, 0, 8);
        stable |= edgeRun(board, 7, -1);
        stable |= edgeRun(board, 7, 8);
        stable |= edgeRun(board, 56, 1);
        stable |= edgeRun(board, 56, -8);
        stable |= edgeRun(board, 63, -1);
        stable |= edgeRun(board, 63, -8);

        if ((occupied & TOP_EDGE) == TOP_EDGE) {
            stable |= board & TOP_EDGE;
        }
        if ((occupied & BOTTOM_EDGE) == BOTTOM_EDGE) {
            stable |= board & BOTTOM_EDGE;
        }
        if ((occupied & A_FILE) == A_FILE) {
            stable |= board & A_FILE;
        }
        if ((occupied & H_FILE) == H_FILE) {
            stable |= board & H_FILE;
        }
        return stable;
    }

    static int parityAccessDifference(
        long player,
        long opponent,
        long playerMoves,
        long opponentMoves
    ) {
        long remaining = ~(player | opponent);
        int difference = 0;

        while (remaining != 0L) {
            long region = connectedRegion(remaining & -remaining, remaining);
            remaining &= ~region;

            boolean playerCanEnter = (playerMoves & region) != 0L;
            boolean opponentCanEnter = (opponentMoves & region) != 0L;
            if (playerCanEnter == opponentCanEnter) {
                continue;
            }

            int regionPreference = (BitBoard.count(region) & 1) == 1
                ? 1
                : -1;
            difference += playerCanEnter
                ? regionPreference
                : -regionPreference;
        }
        return difference;
    }

    static int interpolatedWeight(
        int occupiedCount,
        int opening,
        int middle,
        int late
    ) {
        if (occupiedCount <= 4) {
            return opening;
        }
        if (occupiedCount < 20) {
            return opening
                + (middle - opening) * (occupiedCount - 4) / 16;
        }
        if (occupiedCount < 50) {
            return middle
                + (late - middle) * (occupiedCount - 20) / 30;
        }
        return late;
    }

    private static int weighted(
        int feature,
        int occupiedCount,
        int opening,
        int middle,
        int late
    ) {
        return feature * interpolatedWeight(
            occupiedCount,
            opening,
            middle,
            late
        );
    }

    private static long edgeRun(long board, int start, int step) {
        long run = 0L;
        int square = start;
        for (int index = 0; index < 8; index++) {
            long bit = 1L << square;
            if ((board & bit) == 0L) {
                break;
            }
            run |= bit;
            square += step;
        }
        return run;
    }

    private static long connectedRegion(long seed, long empty) {
        long region = seed;
        long frontier = seed;
        while (frontier != 0L) {
            frontier = orthogonalNeighbors(frontier) & empty & ~region;
            region |= frontier;
        }
        return region;
    }

    private static long orthogonalNeighbors(long board) {
        return ((board & ~H_FILE) << 1)
            | ((board & ~A_FILE) >>> 1)
            | (board << 8)
            | (board >>> 8);
    }

    private static int contains(long board, long bit) {
        return (board & bit) == 0L ? 0 : 1;
    }

    static long neighbors(long board) {
        long eastWest = ((board & ~H_FILE) << 1)
            | ((board & ~A_FILE) >>> 1);
        long vertical = (board << 8) | (board >>> 8);
        long diagonals = ((board & ~H_FILE) << 9)
            | ((board & ~H_FILE) >>> 7)
            | ((board & ~A_FILE) << 7)
            | ((board & ~A_FILE) >>> 9);
        return eastWest | vertical | diagonals;
    }
}
