public final class SearchLimits {

    private final long timeMillis;
    private final int maxDepth;
    private final int threads;

    public SearchLimits(long timeMillis, int maxDepth, int threads) {
        if (timeMillis < 1L) {
            throw new IllegalArgumentException("timeMillis must be positive");
        }
        if (maxDepth < 1 || maxDepth > 64) {
            throw new IllegalArgumentException("maxDepth must be between 1 and 64");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }
        this.timeMillis = timeMillis;
        this.maxDepth = maxDepth;
        this.threads = threads;
    }

    public long timeMillis() {
        return timeMillis;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int threads() {
        return threads;
    }
}
