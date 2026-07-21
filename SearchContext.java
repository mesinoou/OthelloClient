final class SearchContext {

    static final int MAX_PLY = 128;

    final long[][] moves = new long[MAX_PLY][64];
    final int[][] priorities = new int[MAX_PLY][64];
    final int[][] killerSquares = new int[MAX_PLY][2];

    long nodes;
    long transpositionHits;
    long betaCutoffs;
    long pvsResearches;
    int rootBestSquare;
    int rootScore;
    boolean timedOut;

    void reset() {
        nodes = 0L;
        transpositionHits = 0L;
        betaCutoffs = 0L;
        pvsResearches = 0L;
        rootBestSquare = -1;
        rootScore = 0;
        timedOut = false;
        for (int ply = 0; ply < MAX_PLY; ply++) {
            killerSquares[ply][0] = -1;
            killerSquares[ply][1] = -1;
        }
    }

    void recordKiller(int ply, int square) {
        if (killerSquares[ply][0] == square) {
            return;
        }
        killerSquares[ply][1] = killerSquares[ply][0];
        killerSquares[ply][0] = square;
    }
}
