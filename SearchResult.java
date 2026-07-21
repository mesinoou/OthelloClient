import java.util.Arrays;

public final class SearchResult {

    private final int bestSquare;
    private final int score;
    private final int completedDepth;
    private final long nodes;
    private final long elapsedNanos;
    private final long transpositionHits;
    private final long betaCutoffs;
    private final long pvsResearches;
    private final long aspirationSearches;
    private final long aspirationResearches;
    private final long parallelNodes;
    private final int parallelTasks;
    private final long[] parallelWorkerNodes;
    private final boolean timedOut;
    private final boolean stopped;
    private final boolean openingBookMove;
    private final int openingBookGames;
    private final int openingBookWinRatePermille;
    private final boolean exactSolution;
    private final int endgameEmpties;

    public SearchResult(
        int bestSquare,
        int score,
        int completedDepth,
        long nodes,
        long elapsedNanos,
        long transpositionHits,
        long betaCutoffs,
        long pvsResearches,
        boolean timedOut,
        boolean stopped
    ) {
        this(
            bestSquare,
            score,
            completedDepth,
            nodes,
            elapsedNanos,
            transpositionHits,
            betaCutoffs,
            pvsResearches,
            0L,
            0,
            timedOut,
            stopped,
            false,
            0,
            0
        );
    }

    public SearchResult(
        int bestSquare,
        int score,
        int completedDepth,
        long nodes,
        long elapsedNanos,
        long transpositionHits,
        long betaCutoffs,
        long pvsResearches,
        boolean timedOut,
        boolean stopped,
        boolean openingBookMove,
        int openingBookGames,
        int openingBookWinRatePermille
    ) {
        this(
            bestSquare,
            score,
            completedDepth,
            nodes,
            elapsedNanos,
            transpositionHits,
            betaCutoffs,
            pvsResearches,
            0L,
            0,
            timedOut,
            stopped,
            openingBookMove,
            openingBookGames,
            openingBookWinRatePermille,
            false,
            0
        );
    }

    public SearchResult(
        int bestSquare,
        int score,
        int completedDepth,
        long nodes,
        long elapsedNanos,
        long transpositionHits,
        long betaCutoffs,
        long pvsResearches,
        long parallelNodes,
        int parallelTasks,
        boolean timedOut,
        boolean stopped,
        boolean openingBookMove,
        int openingBookGames,
        int openingBookWinRatePermille
    ) {
        this(
            bestSquare,
            score,
            completedDepth,
            nodes,
            elapsedNanos,
            transpositionHits,
            betaCutoffs,
            pvsResearches,
            parallelNodes,
            parallelTasks,
            timedOut,
            stopped,
            openingBookMove,
            openingBookGames,
            openingBookWinRatePermille,
            false,
            0
        );
    }

    public SearchResult(
        int bestSquare,
        int score,
        int completedDepth,
        long nodes,
        long elapsedNanos,
        long transpositionHits,
        long betaCutoffs,
        long pvsResearches,
        long parallelNodes,
        int parallelTasks,
        boolean timedOut,
        boolean stopped,
        boolean openingBookMove,
        int openingBookGames,
        int openingBookWinRatePermille,
        boolean exactSolution,
        int endgameEmpties
    ) {
        this(
            bestSquare,
            score,
            completedDepth,
            nodes,
            elapsedNanos,
            transpositionHits,
            betaCutoffs,
            pvsResearches,
            parallelNodes,
            parallelTasks,
            timedOut,
            stopped,
            openingBookMove,
            openingBookGames,
            openingBookWinRatePermille,
            exactSolution,
            endgameEmpties,
            new long[0]
        );
    }

    public SearchResult(
        int bestSquare,
        int score,
        int completedDepth,
        long nodes,
        long elapsedNanos,
        long transpositionHits,
        long betaCutoffs,
        long pvsResearches,
        long parallelNodes,
        int parallelTasks,
        boolean timedOut,
        boolean stopped,
        boolean openingBookMove,
        int openingBookGames,
        int openingBookWinRatePermille,
        boolean exactSolution,
        int endgameEmpties,
        long[] parallelWorkerNodes
    ) {
        this(
            bestSquare,
            score,
            completedDepth,
            nodes,
            elapsedNanos,
            transpositionHits,
            betaCutoffs,
            pvsResearches,
            0L,
            0L,
            parallelNodes,
            parallelTasks,
            timedOut,
            stopped,
            openingBookMove,
            openingBookGames,
            openingBookWinRatePermille,
            exactSolution,
            endgameEmpties,
            parallelWorkerNodes
        );
    }

    public SearchResult(
        int bestSquare,
        int score,
        int completedDepth,
        long nodes,
        long elapsedNanos,
        long transpositionHits,
        long betaCutoffs,
        long pvsResearches,
        long aspirationSearches,
        long aspirationResearches,
        long parallelNodes,
        int parallelTasks,
        boolean timedOut,
        boolean stopped,
        boolean openingBookMove,
        int openingBookGames,
        int openingBookWinRatePermille,
        boolean exactSolution,
        int endgameEmpties,
        long[] parallelWorkerNodes
    ) {
        this.bestSquare = bestSquare;
        this.score = score;
        this.completedDepth = completedDepth;
        this.nodes = nodes;
        this.elapsedNanos = elapsedNanos;
        this.transpositionHits = transpositionHits;
        this.betaCutoffs = betaCutoffs;
        this.pvsResearches = pvsResearches;
        this.aspirationSearches = aspirationSearches;
        this.aspirationResearches = aspirationResearches;
        this.parallelNodes = parallelNodes;
        this.parallelTasks = parallelTasks;
        this.parallelWorkerNodes = parallelWorkerNodes == null
            ? new long[0]
            : Arrays.copyOf(parallelWorkerNodes, parallelWorkerNodes.length);
        this.timedOut = timedOut;
        this.stopped = stopped;
        this.openingBookMove = openingBookMove;
        this.openingBookGames = openingBookGames;
        this.openingBookWinRatePermille = openingBookWinRatePermille;
        this.exactSolution = exactSolution;
        this.endgameEmpties = endgameEmpties;
    }

    public int bestSquare() {
        return bestSquare;
    }

    public int score() {
        return score;
    }

    public int completedDepth() {
        return completedDepth;
    }

    public long nodes() {
        return nodes;
    }

    public long elapsedNanos() {
        return elapsedNanos;
    }

    public long transpositionHits() {
        return transpositionHits;
    }

    public long betaCutoffs() {
        return betaCutoffs;
    }

    public long pvsResearches() {
        return pvsResearches;
    }

    public long aspirationSearches() {
        return aspirationSearches;
    }

    public long aspirationResearches() {
        return aspirationResearches;
    }

    public long parallelNodes() {
        return parallelNodes;
    }

    public int parallelTasks() {
        return parallelTasks;
    }

    public long[] parallelWorkerNodes() {
        return Arrays.copyOf(parallelWorkerNodes, parallelWorkerNodes.length);
    }

    public boolean timedOut() {
        return timedOut;
    }

    public boolean stopped() {
        return stopped;
    }

    public boolean openingBookMove() {
        return openingBookMove;
    }

    public int openingBookGames() {
        return openingBookGames;
    }

    public int openingBookWinRatePermille() {
        return openingBookWinRatePermille;
    }

    public boolean exactSolution() {
        return exactSolution;
    }

    public boolean endgameSearch() {
        return endgameEmpties > 0;
    }

    public int endgameEmpties() {
        return endgameEmpties;
    }
}
