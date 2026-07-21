import java.util.Arrays;

final class SearchContext {

    static final int MAX_PLY = 128;
    private static final int MAX_HISTORY = 32_768;
    private static final int MAX_HISTORY_BONUS = 1_024;
    static final int HISTORY_PRIORITY_DIVISOR = 256;

    final long[][] moves = new long[MAX_PLY][64];
    final int[][] priorities = new int[MAX_PLY][64];
    final int[] history = new int[64];

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
        Arrays.fill(history, 0);
    }

    void rewardHistory(int square, int depth) {
        int bonus = Math.min(MAX_HISTORY_BONUS, depth * depth);
        if (history[square] + bonus >= MAX_HISTORY) {
            for (int index = 0; index < history.length; index++) {
                history[index] /= 2;
            }
        }
        history[square] += bonus;
    }
}
