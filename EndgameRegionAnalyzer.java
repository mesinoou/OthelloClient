final class EndgameRegionAnalyzer {

    private static final long A_FILE = 0x0101010101010101L;
    private static final long H_FILE = 0x8080808080808080L;

    private EndgameRegionAnalyzer() {
    }

    static long oddRegionMask(long empty) {
        long remaining = empty;
        long oddRegions = 0L;

        while (remaining != 0L) {
            long region = remaining & -remaining;
            long frontier = region;

            while (frontier != 0L) {
                long next = orthogonalNeighbors(frontier)
                    & remaining
                    & ~region;
                region |= next;
                frontier = next;
            }

            if ((Long.bitCount(region) & 1) != 0) {
                oddRegions |= region;
            }
            remaining &= ~region;
        }
        return oddRegions;
    }

    static int regionCount(long empty) {
        int count = 0;
        long remaining = empty;
        while (remaining != 0L) {
            long region = remaining & -remaining;
            long frontier = region;
            while (frontier != 0L) {
                long next = orthogonalNeighbors(frontier)
                    & remaining
                    & ~region;
                region |= next;
                frontier = next;
            }
            remaining &= ~region;
            count++;
        }
        return count;
    }

    private static long orthogonalNeighbors(long board) {
        return (board >>> 8)
            | (board << 8)
            | ((board & ~H_FILE) << 1)
            | ((board & ~A_FILE) >>> 1);
    }
}
