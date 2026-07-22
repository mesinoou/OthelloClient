import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class PonderMetrics {

    private long starts;
    private long completions;
    private long interruptions;
    private long totalNanos;
    private long totalNodes;
    private long totalDepth;
    private long predictions;
    private long predictionMatches;
    private long ownSearches;
    private long ownInitialTtHits;
    private long erroneousPuts;
    private final List<Long> stopLatenciesNanos = new ArrayList<>();

    synchronized void recordStart() {
        starts++;
    }

    synchronized void recordResult(
        SearchResult result,
        long stopRequestedNanos
    ) {
        totalNanos += result.elapsedNanos();
        totalNodes += result.nodes();
        totalDepth += result.completedDepth();
        if (result.stopped() || Thread.currentThread().isInterrupted()) {
            interruptions++;
        } else {
            completions++;
        }
        if (stopRequestedNanos > 0L) {
            stopLatenciesNanos.add(
                Math.max(0L, System.nanoTime() - stopRequestedNanos)
            );
        }
    }

    synchronized void recordFailure(long stopRequestedNanos) {
        interruptions++;
        if (stopRequestedNanos > 0L) {
            stopLatenciesNanos.add(
                Math.max(0L, System.nanoTime() - stopRequestedNanos)
            );
        }
    }

    synchronized void recordPrediction(boolean matched) {
        predictions++;
        if (matched) {
            predictionMatches++;
        }
    }

    synchronized void recordOwnSearch(boolean initialTtHit) {
        ownSearches++;
        if (initialTtHit) {
            ownInitialTtHits++;
        }
    }

    synchronized void recordErroneousPut() {
        erroneousPuts++;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
            starts,
            completions,
            interruptions,
            totalNanos,
            totalNodes,
            totalDepth,
            predictions,
            predictionMatches,
            ownSearches,
            ownInitialTtHits,
            erroneousPuts,
            percentile95(stopLatenciesNanos)
        );
    }

    private static long percentile95(List<Long> samples) {
        if (samples.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, index));
    }

    static final class Snapshot {
        private final long starts;
        private final long completions;
        private final long interruptions;
        private final long totalNanos;
        private final long totalNodes;
        private final long totalDepth;
        private final long predictions;
        private final long predictionMatches;
        private final long ownSearches;
        private final long ownInitialTtHits;
        private final long erroneousPuts;
        private final long stopLatencyP95Nanos;

        private Snapshot(
            long starts,
            long completions,
            long interruptions,
            long totalNanos,
            long totalNodes,
            long totalDepth,
            long predictions,
            long predictionMatches,
            long ownSearches,
            long ownInitialTtHits,
            long erroneousPuts,
            long stopLatencyP95Nanos
        ) {
            this.starts = starts;
            this.completions = completions;
            this.interruptions = interruptions;
            this.totalNanos = totalNanos;
            this.totalNodes = totalNodes;
            this.totalDepth = totalDepth;
            this.predictions = predictions;
            this.predictionMatches = predictionMatches;
            this.ownSearches = ownSearches;
            this.ownInitialTtHits = ownInitialTtHits;
            this.erroneousPuts = erroneousPuts;
            this.stopLatencyP95Nanos = stopLatencyP95Nanos;
        }

        long starts() {
            return starts;
        }

        long completions() {
            return completions;
        }

        long interruptions() {
            return interruptions;
        }

        long predictions() {
            return predictions;
        }

        long predictionMatches() {
            return predictionMatches;
        }

        long ownSearches() {
            return ownSearches;
        }

        long ownInitialTtHits() {
            return ownInitialTtHits;
        }

        long erroneousPuts() {
            return erroneousPuts;
        }

        long stopLatencyP95Nanos() {
            return stopLatencyP95Nanos;
        }

        @Override
        public String toString() {
            double averageMillis = starts == 0L
                ? 0.0
                : totalNanos / 1_000_000.0 / starts;
            double averageDepth = starts == 0L
                ? 0.0
                : (double) totalDepth / starts;
            double predictionRate = predictions == 0L
                ? 0.0
                : 100.0 * predictionMatches / predictions;
            return String.format(
                Locale.ROOT,
                "starts=%d, completed=%d, interrupted=%d, "
                    + "avgMillis=%.3f, nodes=%d, avgDepth=%.2f, "
                    + "prediction=%d/%d (%.1f%%), ownInitialTtHit=%d/%d, "
                    + "stopP95Millis=%.3f, erroneousPuts=%d",
                starts,
                completions,
                interruptions,
                averageMillis,
                totalNodes,
                averageDepth,
                predictionMatches,
                predictions,
                predictionRate,
                ownInitialTtHits,
                ownSearches,
                stopLatencyP95Nanos / 1_000_000.0,
                erroneousPuts
            );
        }
    }
}
