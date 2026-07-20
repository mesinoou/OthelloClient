import java.util.Arrays;

public final class TranspositionTable {

    public static final byte EXACT = 1;
    public static final byte LOWER_BOUND = 2;
    public static final byte UPPER_BOUND = 3;

    private static final int LOCK_COUNT = 256;
    private static final long FOUND_MASK = Long.MIN_VALUE;

    private final long[] players;
    private final long[] opponents;
    private final int[] depths;
    private final int[] values;
    private final int[] generations;
    private final byte[] bounds;
    private final byte[] bestSquares;
    private final byte[] occupied;
    private final Object[] locks;
    private final int mask;

    private volatile int generation = 1;

    public TranspositionTable(int capacity) {
        if (capacity < 1 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        players = new long[capacity];
        opponents = new long[capacity];
        depths = new int[capacity];
        values = new int[capacity];
        generations = new int[capacity];
        bounds = new byte[capacity];
        bestSquares = new byte[capacity];
        occupied = new byte[capacity];
        locks = new Object[Math.min(LOCK_COUNT, capacity)];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        mask = capacity - 1;
    }

    public synchronized void newSearch() {
        generation++;
        if (generation == 0) {
            Arrays.fill(generations, 0);
            generation = 1;
        }
    }

    public long probe(long player, long opponent) {
        int index = index(player, opponent);
        synchronized (lock(index)) {
            if (occupied[index] == 0
                || players[index] != player
                || opponents[index] != opponent) {
                return 0L;
            }
            return packProbe(
                depths[index],
                values[index],
                bounds[index],
                bestSquares[index]
            );
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
        int index = index(player, opponent);
        synchronized (lock(index)) {
            boolean samePosition = occupied[index] != 0
                && players[index] == player
                && opponents[index] == opponent;
            if (samePosition) {
                if (depth < depths[index] && bound != EXACT) {
                    return;
                }
            } else if (occupied[index] != 0
                && generations[index] == generation
                && depth < depths[index]) {
                return;
            }

            players[index] = player;
            opponents[index] = opponent;
            depths[index] = depth;
            values[index] = value;
            generations[index] = generation;
            bounds[index] = bound;
            bestSquares[index] = (byte) bestSquare;
            occupied[index] = 1;
        }
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

    private Object lock(int index) {
        return locks[index & (locks.length - 1)];
    }

    private int index(long player, long opponent) {
        long hash = mix64(player) ^ Long.rotateLeft(mix64(opponent), 29);
        return ((int) hash) & mask;
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
