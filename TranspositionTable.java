import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

public final class TranspositionTable {

    public static final byte EXACT = 1;
    public static final byte LOWER_BOUND = 2;
    public static final byte UPPER_BOUND = 3;

    private static final int LOCK_COUNT = 256;
    private static final int DEFAULT_WAYS = 2;
    private static final long FOUND_MASK = Long.MIN_VALUE;
    private static final long ESTIMATED_ENTRY_BYTES = 31L;

    private final long[] players;
    private final long[] opponents;
    private final int[] depths;
    private final int[] values;
    private final int[] generations;
    private final byte[] bounds;
    private final byte[] bestSquares;
    private final byte[] occupied;
    private final Object[] locks;
    private final int ways;
    private final int bucketMask;
    private final MetricCounters metricCounters;

    private volatile int generation = 1;

    public TranspositionTable(int capacity) {
        this(capacity, capacity == 1 ? 1 : DEFAULT_WAYS, false);
    }

    TranspositionTable(int capacity, int ways, boolean metricsEnabled) {
        if (capacity < 1 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        if (ways != 1 && ways != 2) {
            throw new IllegalArgumentException("ways must be 1 or 2");
        }
        if (capacity < ways) {
            throw new IllegalArgumentException("capacity must cover all ways");
        }
        int bucketCount = capacity / ways;
        players = new long[capacity];
        opponents = new long[capacity];
        depths = new int[capacity];
        values = new int[capacity];
        generations = new int[capacity];
        bounds = new byte[capacity];
        bestSquares = new byte[capacity];
        occupied = new byte[capacity];
        locks = new Object[Math.min(LOCK_COUNT, bucketCount)];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        this.ways = ways;
        bucketMask = bucketCount - 1;
        metricCounters = metricsEnabled ? new MetricCounters() : null;
    }

    public synchronized void newSearch() {
        generation++;
        if (generation == 0) {
            Arrays.fill(generations, 0);
            generation = 1;
        }
    }

    public long probe(long player, long opponent) {
        int bucket = bucketIndex(player, opponent);
        synchronized (lock(bucket)) {
            recordProbe();
            int first = bucket * ways;
            int limit = first + ways;
            for (int index = first; index < limit; index++) {
                if (occupied[index] != 0
                    && players[index] == player
                    && opponents[index] == opponent) {
                    recordHit();
                    return packProbe(
                        depths[index],
                        values[index],
                        bounds[index],
                        bestSquares[index]
                    );
                }
            }
            return 0L;
        }
    }

    public static boolean probeFound(long probe) {
        return (probe & FOUND_MASK) != 0L;
    }

    public static int probeDepth(long probe) {
        return (int) ((probe >>> 32) & 0x7fL);
    }

    public static int probeValue(long probe) {
        return (int) probe;
    }

    public static byte probeBound(long probe) {
        return (byte) ((probe >>> 40) & 0xffL);
    }

    public static int probeBestSquare(long probe) {
        return (int) ((probe >>> 48) & 0x7fL) - 1;
    }

    public void store(
        long player,
        long opponent,
        int depth,
        int value,
        byte bound,
        int bestSquare
    ) {
        int bucket = bucketIndex(player, opponent);
        synchronized (lock(bucket)) {
            recordStore();
            if (ways == 1) {
                storeOneWay(
                    bucket,
                    player,
                    opponent,
                    depth,
                    value,
                    bound,
                    bestSquare
                );
                return;
            }
            storeTwoWay(
                bucket,
                player,
                opponent,
                depth,
                value,
                bound,
                bestSquare
            );
        }
    }

    int ways() {
        return ways;
    }

    int capacity() {
        return players.length;
    }

    int bucketCount() {
        return bucketMask + 1;
    }

    long estimatedStorageBytes() {
        return ESTIMATED_ENTRY_BYTES * players.length;
    }

    int bucketIndexForTest(long player, long opponent) {
        return bucketIndex(player, opponent);
    }

    Stats snapshotStats() {
        if (metricCounters == null) {
            return Stats.ZERO;
        }
        return metricCounters.snapshot();
    }

    private void storeOneWay(
        int index,
        long player,
        long opponent,
        int depth,
        int value,
        byte bound,
        int bestSquare
    ) {
        boolean samePosition = samePosition(index, player, opponent);
        if (samePosition) {
            recordSamePositionUpdate();
            if (depth < depths[index] && bound != EXACT) {
                recordRejectedStore();
                return;
            }
        } else if (occupied[index] != 0) {
            recordCollision();
            if (generations[index] == generation && depth < depths[index]) {
                recordRejectedStore();
                return;
            }
            recordReplacement();
        }
        write(
            index,
            player,
            opponent,
            depth,
            value,
            bound,
            bestSquare
        );
    }

    private void storeTwoWay(
        int bucket,
        long player,
        long opponent,
        int depth,
        int value,
        byte bound,
        int bestSquare
    ) {
        int first = bucket << 1;
        int second = first + 1;
        if (samePosition(first, player, opponent)) {
            updateSamePosition(
                first,
                player,
                opponent,
                depth,
                value,
                bound,
                bestSquare
            );
            return;
        }
        if (samePosition(second, player, opponent)) {
            updateSamePosition(
                second,
                player,
                opponent,
                depth,
                value,
                bound,
                bestSquare
            );
            return;
        }

        int target;
        if (occupied[first] == 0) {
            target = first;
        } else if (occupied[second] == 0) {
            target = second;
        } else {
            recordCollision();
            target = replacementIndex(first, second);
            recordReplacement();
        }
        write(
            target,
            player,
            opponent,
            depth,
            value,
            bound,
            bestSquare
        );
    }

    private void updateSamePosition(
        int index,
        long player,
        long opponent,
        int depth,
        int value,
        byte bound,
        int bestSquare
    ) {
        recordSamePositionUpdate();
        if ((depth < depths[index] && bound != EXACT)
            || (depth == depths[index]
                && bounds[index] == EXACT
                && bound != EXACT)) {
            recordRejectedStore();
            return;
        }
        write(
            index,
            player,
            opponent,
            depth,
            value,
            bound,
            bestSquare
        );
    }

    private int replacementIndex(int first, int second) {
        int firstAge = generation - generations[first];
        int secondAge = generation - generations[second];
        int ageComparison = Integer.compareUnsigned(firstAge, secondAge);
        if (ageComparison != 0) {
            return ageComparison > 0 ? first : second;
        }
        if (depths[first] != depths[second]) {
            return depths[first] < depths[second] ? first : second;
        }
        boolean firstExact = bounds[first] == EXACT;
        boolean secondExact = bounds[second] == EXACT;
        if (firstExact != secondExact) {
            return firstExact ? second : first;
        }
        return second;
    }

    private boolean samePosition(int index, long player, long opponent) {
        return occupied[index] != 0
            && players[index] == player
            && opponents[index] == opponent;
    }

    private void write(
        int index,
        long player,
        long opponent,
        int depth,
        int value,
        byte bound,
        int bestSquare
    ) {
        players[index] = player;
        opponents[index] = opponent;
        depths[index] = depth;
        values[index] = value;
        generations[index] = generation;
        bounds[index] = bound;
        bestSquares[index] = (byte) bestSquare;
        occupied[index] = 1;
    }

    private static long packProbe(
        int depth,
        int value,
        byte bound,
        byte bestSquare
    ) {
        return FOUND_MASK
            | ((long) (bestSquare + 1) & 0x7fL) << 48
            | ((long) bound & 0xffL) << 40
            | ((long) depth & 0x7fL) << 32
            | ((long) value & 0xffff_ffffL);
    }

    private Object lock(int bucket) {
        return locks[bucket & (locks.length - 1)];
    }

    private int bucketIndex(long player, long opponent) {
        long hash = mix64(player) ^ Long.rotateLeft(mix64(opponent), 29);
        return ((int) hash) & bucketMask;
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private void recordProbe() {
        if (metricCounters != null) {
            metricCounters.probes.increment();
        }
    }

    private void recordHit() {
        if (metricCounters != null) {
            metricCounters.hits.increment();
        }
    }

    private void recordStore() {
        if (metricCounters != null) {
            metricCounters.stores.increment();
        }
    }

    private void recordCollision() {
        if (metricCounters != null) {
            metricCounters.collisions.increment();
        }
    }

    private void recordReplacement() {
        if (metricCounters != null) {
            metricCounters.replacements.increment();
        }
    }

    private void recordRejectedStore() {
        if (metricCounters != null) {
            metricCounters.rejectedStores.increment();
        }
    }

    private void recordSamePositionUpdate() {
        if (metricCounters != null) {
            metricCounters.samePositionUpdates.increment();
        }
    }

    static final class Stats {

        private static final Stats ZERO = new Stats(0L, 0L, 0L, 0L, 0L, 0L, 0L);

        private final long probes;
        private final long hits;
        private final long stores;
        private final long collisions;
        private final long replacements;
        private final long rejectedStores;
        private final long samePositionUpdates;

        private Stats(
            long probes,
            long hits,
            long stores,
            long collisions,
            long replacements,
            long rejectedStores,
            long samePositionUpdates
        ) {
            this.probes = probes;
            this.hits = hits;
            this.stores = stores;
            this.collisions = collisions;
            this.replacements = replacements;
            this.rejectedStores = rejectedStores;
            this.samePositionUpdates = samePositionUpdates;
        }

        long probes() {
            return probes;
        }

        long hits() {
            return hits;
        }

        long stores() {
            return stores;
        }

        long collisions() {
            return collisions;
        }

        long replacements() {
            return replacements;
        }

        long rejectedStores() {
            return rejectedStores;
        }

        long samePositionUpdates() {
            return samePositionUpdates;
        }
    }

    private static final class MetricCounters {

        private final LongAdder probes = new LongAdder();
        private final LongAdder hits = new LongAdder();
        private final LongAdder stores = new LongAdder();
        private final LongAdder collisions = new LongAdder();
        private final LongAdder replacements = new LongAdder();
        private final LongAdder rejectedStores = new LongAdder();
        private final LongAdder samePositionUpdates = new LongAdder();

        private Stats snapshot() {
            return new Stats(
                probes.sum(),
                hits.sum(),
                stores.sum(),
                collisions.sum(),
                replacements.sum(),
                rejectedStores.sum(),
                samePositionUpdates.sum()
            );
        }
    }
}
