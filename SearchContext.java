final class SearchContext {

    static final int MAX_PLY = 128;

    final long[][] moves = new long[MAX_PLY][64];
    final int[][] priorities = new int[MAX_PLY][64];

    long nodes;
    long transpositionHits;
    long betaCutoffs;
    long pvsResearches;
    long lmrSearches;
    long lmrResearches;
    long stabilityChecks;
    long stabilityCuts;
    long mpcAttempts;
    long mpcHighCuts;
    long mpcLowCuts;
    long mpcProbeNodes;
    boolean mpcProbeActive;
    int rootBestSquare;
    int rootScore;
    boolean timedOut;

    void reset() {
        nodes = 0L;
        transpositionHits = 0L;
        betaCutoffs = 0L;
        pvsResearches = 0L;
        lmrSearches = 0L;
        lmrResearches = 0L;
        stabilityChecks = 0L;
        stabilityCuts = 0L;
        mpcAttempts = 0L;
        mpcHighCuts = 0L;
        mpcLowCuts = 0L;
        mpcProbeNodes = 0L;
        mpcProbeActive = false;
        rootBestSquare = -1;
        rootScore = 0;
        timedOut = false;
    }
}
