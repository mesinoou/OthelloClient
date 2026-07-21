import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class RuntimeConfiguration {

    static final String TT_ENTRIES_PROPERTY = "othello.tt.entries";

    private static final long MIB = 1024L * 1024L;
    private static final long MINIMUM_TT_BYTES = 8L * MIB;
    private static final long MAXIMUM_TT_BYTES = 128L * MIB;
    private static final long DIAGNOSTIC_LIMIT_NANOS = 2_000_000_000L;
    private static final long WARMUP_MILLIS = 100L;
    private static final long TIMED_PROBE_MILLIS = 180L;
    private static final long FIXED_PROBE_MILLIS = 220L;
    private static final int TEMPORARY_TT_LIMIT = 1 << 16;
    private static final int DEFAULT_TT_ENTRIES = 1 << 18;
    private static final BitBoardPosition PROBE_POSITION =
        new BitBoardPosition(
            0x3F362F263A020200L,
            0x4048501904000000L
        );
    private static final int PROBE_COLOR = -1;

    private final RuntimeProfile profile;
    private final int threads;
    private final int ttEntries;
    private final boolean automaticThreads;
    private final boolean automaticTt;
    private final long diagnosticNanos;
    private final List<ThreadMeasurement> measurements;
    private final String fallbackReason;

    private RuntimeConfiguration(
        RuntimeProfile profile,
        int threads,
        int ttEntries,
        boolean automaticThreads,
        boolean automaticTt,
        long diagnosticNanos,
        List<ThreadMeasurement> measurements,
        String fallbackReason
    ) {
        this.profile = profile;
        this.threads = threads;
        this.ttEntries = ttEntries;
        this.automaticThreads = automaticThreads;
        this.automaticTt = automaticTt;
        this.diagnosticNanos = diagnosticNanos;
        this.measurements = Collections.unmodifiableList(
            new ArrayList<>(measurements)
        );
        this.fallbackReason = fallbackReason;
    }

    static RuntimeConfiguration resolve(
        PositionEvaluator evaluator,
        String threadSpec,
        String cliTtSpec
    ) {
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        RuntimeProfile profile = RuntimeProfile.capture();
        String propertyTtSpec = System.getProperty(TT_ENTRIES_PROPERTY);
        String effectiveTtSpec = cliTtSpec != null
            ? cliTtSpec
            : propertyTtSpec;
        boolean automaticTt = effectiveTtSpec == null
            || "auto".equalsIgnoreCase(effectiveTtSpec);
        int ttEntries = automaticTt
            ? autoTtEntries(profile.maxHeapBytes)
            : parseTtEntries(effectiveTtSpec, profile.maxHeapBytes);

        boolean automaticThreads = "auto".equalsIgnoreCase(threadSpec);
        if (!automaticThreads) {
            int explicitThreads = parseThreads(threadSpec);
            return new RuntimeConfiguration(
                profile,
                explicitThreads,
                ttEntries,
                false,
                automaticTt,
                0L,
                Collections.emptyList(),
                null
            );
        }

        long started = System.nanoTime();
        try {
            AutoTuneResult tuned = tuneThreads(
                evaluator,
                profile.logicalProcessors,
                ttEntries,
                started
            );
            return new RuntimeConfiguration(
                profile,
                tuned.threads,
                ttEntries,
                true,
                automaticTt,
                System.nanoTime() - started,
                tuned.measurements,
                null
            );
        } catch (RuntimeException error) {
            int fallbackThreads = Math.max(
                1,
                Math.min(4, profile.logicalProcessors)
            );
            return new RuntimeConfiguration(
                profile,
                fallbackThreads,
                ttEntries,
                true,
                automaticTt,
                System.nanoTime() - started,
                Collections.emptyList(),
                error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage()
            );
        }
    }

    int threads() {
        return threads;
    }

    int ttEntries() {
        return ttEntries;
    }

    long diagnosticMillis() {
        return diagnosticNanos / 1_000_000L;
    }

    void print(PrintStream output) {
        output.println(
            "実行環境: processors=" + profile.logicalProcessors
                + ", maxHeapMiB=" + formatMiB(profile.maxHeapBytes)
                + ", os=" + profile.osName
                + ", arch=" + profile.osArch
                + ", java=" + profile.javaVersion
        );
        for (ThreadMeasurement measurement : measurements) {
            output.println("自動thread候補: " + measurement.describe());
        }
        if (fallbackReason != null) {
            System.err.println(
                "実行環境診断をfallbackしました: " + fallbackReason
            );
        }
        output.println(
            "実行時設定: threads=" + threads
                + " (" + (automaticThreads ? "auto" : "explicit") + ")"
                + ", ttEntries=" + ttEntries
                + " (" + (automaticTt ? "auto" : "explicit") + ")"
                + ", ttEstimatedMiB="
                + formatMiB(TranspositionTable.estimatedBytes(ttEntries))
                + ", diagnosticMillis=" + diagnosticMillis()
        );
    }

    static int autoTtEntries(long maxHeapBytes) {
        if (maxHeapBytes < 1L) {
            throw new IllegalArgumentException("maxHeapBytes must be positive");
        }
        long heapQuarter = Math.max(1L * MIB, maxHeapBytes / 4L);
        long upper = Math.min(MAXIMUM_TT_BYTES, heapQuarter);
        long preferred = Math.max(MINIMUM_TT_BYTES, maxHeapBytes / 16L);
        long budget = Math.min(preferred, upper);
        int capacity = 1;
        while (capacity <= (1 << 29)) {
            int next = capacity << 1;
            if (next <= 0
                || TranspositionTable.estimatedBytes(next) > budget) {
                break;
            }
            capacity = next;
        }
        return capacity;
    }

    static List<Integer> threadCandidates(int logicalProcessors) {
        if (logicalProcessors < 1) {
            throw new IllegalArgumentException(
                "logicalProcessors must be positive"
            );
        }
        List<Integer> result = new ArrayList<>();
        for (int candidate = 1;
            candidate <= 8 && candidate <= logicalProcessors;
            candidate <<= 1) {
            result.add(candidate);
        }
        return result;
    }

    static int chooseThreads(List<ThreadMeasurement> measurements) {
        if (measurements.isEmpty()) {
            throw new IllegalArgumentException("measurements are empty");
        }
        int maximumDepth = 0;
        for (ThreadMeasurement measurement : measurements) {
            maximumDepth = Math.max(maximumDepth, measurement.timedDepth);
        }
        ThreadMeasurement chosen = null;
        for (ThreadMeasurement measurement : measurements) {
            if (measurement.timedDepth != maximumDepth
                || !measurement.fixedCompleted) {
                continue;
            }
            if (chosen == null) {
                chosen = measurement;
                continue;
            }
            if (measurement.fixedNanos * 100L
                <= chosen.fixedNanos * 95L) {
                chosen = measurement;
            }
        }
        if (chosen != null) {
            return chosen.threads;
        }
        for (ThreadMeasurement measurement : measurements) {
            if (measurement.timedDepth == maximumDepth) {
                return measurement.threads;
            }
        }
        return measurements.get(0).threads;
    }

    private static AutoTuneResult tuneThreads(
        PositionEvaluator evaluator,
        int logicalProcessors,
        int ttEntries,
        long started
    ) {
        int temporaryCapacity = Math.min(ttEntries, TEMPORARY_TT_LIMIT);
        runProbe(
            evaluator,
            1,
            temporaryCapacity,
            WARMUP_MILLIS,
            4
        );
        List<ThreadMeasurement> measurements = new ArrayList<>();
        for (int candidate : threadCandidates(logicalProcessors)) {
            requireDiagnosticTime(started, TIMED_PROBE_MILLIS);
            SearchResult result = runProbe(
                evaluator,
                candidate,
                temporaryCapacity,
                TIMED_PROBE_MILLIS,
                64
            );
            measurements.add(new ThreadMeasurement(
                candidate,
                result.completedDepth(),
                result.nodes(),
                0L,
                false
            ));
        }

        int maximumDepth = 0;
        for (ThreadMeasurement measurement : measurements) {
            maximumDepth = Math.max(maximumDepth, measurement.timedDepth);
        }
        for (int index = 0; index < measurements.size(); index++) {
            ThreadMeasurement timed = measurements.get(index);
            if (timed.timedDepth != maximumDepth) {
                continue;
            }
            requireDiagnosticTime(started, FIXED_PROBE_MILLIS);
            SearchResult fixed = runProbe(
                evaluator,
                timed.threads,
                temporaryCapacity,
                FIXED_PROBE_MILLIS,
                maximumDepth
            );
            measurements.set(index, new ThreadMeasurement(
                timed.threads,
                timed.timedDepth,
                timed.timedNodes,
                fixed.elapsedNanos(),
                fixed.completedDepth() == maximumDepth && !fixed.timedOut()
            ));
        }
        int selected = chooseThreads(measurements);
        if (System.nanoTime() - started > DIAGNOSTIC_LIMIT_NANOS) {
            throw new IllegalStateException("diagnostic exceeded 2000 ms");
        }
        return new AutoTuneResult(selected, measurements);
    }

    private static SearchResult runProbe(
        PositionEvaluator evaluator,
        int threads,
        int ttEntries,
        long timeMillis,
        int maximumDepth
    ) {
        SearchEngine engine = new SearchEngine(
            evaluator,
            new TranspositionTable(ttEntries)
        );
        try {
            return engine.search(
                PROBE_POSITION,
                PROBE_COLOR,
                new SearchLimits(timeMillis, maximumDepth, threads)
            );
        } finally {
            engine.shutdown();
        }
    }

    private static void requireDiagnosticTime(
        long started,
        long nextBudgetMillis
    ) {
        long elapsed = System.nanoTime() - started;
        long required = nextBudgetMillis * 1_000_000L;
        if (elapsed + required >= DIAGNOSTIC_LIMIT_NANOS) {
            throw new IllegalStateException("diagnostic time budget exhausted");
        }
    }

    private static int parseThreads(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                "threadsにはautoまたは正整数を指定してください。",
                error
            );
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(
                "threadsは1以上で指定してください。"
            );
        }
        return parsed;
    }

    private static int parseTtEntries(String value, long maxHeapBytes) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                "TT entry数はautoまたは正整数で指定してください。",
                error
            );
        }
        if (parsed < 1 || (parsed & (parsed - 1)) != 0) {
            throw new IllegalArgumentException(
                "TT entry数は2の累乗で指定してください。"
            );
        }
        if (TranspositionTable.estimatedBytes(parsed) > maxHeapBytes / 2L) {
            throw new IllegalArgumentException(
                "指定TTは最大heapの半分を超えます。"
            );
        }
        return parsed;
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (double) MIB);
    }

    static final class RuntimeProfile {
        final int logicalProcessors;
        final long maxHeapBytes;
        final String osName;
        final String osArch;
        final String javaVersion;

        RuntimeProfile(
            int logicalProcessors,
            long maxHeapBytes,
            String osName,
            String osArch,
            String javaVersion
        ) {
            if (logicalProcessors < 1 || maxHeapBytes < 1L) {
                throw new IllegalArgumentException("invalid runtime profile");
            }
            this.logicalProcessors = logicalProcessors;
            this.maxHeapBytes = maxHeapBytes;
            this.osName = osName;
            this.osArch = osArch;
            this.javaVersion = javaVersion;
        }

        static RuntimeProfile capture() {
            Runtime runtime = Runtime.getRuntime();
            return new RuntimeProfile(
                runtime.availableProcessors(),
                runtime.maxMemory(),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                System.getProperty("java.version", "unknown")
            );
        }
    }

    static final class ThreadMeasurement {
        final int threads;
        final int timedDepth;
        final long timedNodes;
        final long fixedNanos;
        final boolean fixedCompleted;

        ThreadMeasurement(
            int threads,
            int timedDepth,
            long timedNodes,
            long fixedNanos,
            boolean fixedCompleted
        ) {
            this.threads = threads;
            this.timedDepth = timedDepth;
            this.timedNodes = timedNodes;
            this.fixedNanos = fixedNanos;
            this.fixedCompleted = fixedCompleted;
        }

        String describe() {
            return "threads=" + threads
                + ", timedDepth=" + timedDepth
                + ", timedNodes=" + timedNodes
                + ", fixedMillis="
                + (fixedCompleted
                    ? String.format(
                        Locale.ROOT,
                        "%.3f",
                        fixedNanos / 1_000_000.0
                    )
                    : fixedNanos == 0L ? "not-measured" : "incomplete");
        }
    }

    private static final class AutoTuneResult {
        private final int threads;
        private final List<ThreadMeasurement> measurements;

        private AutoTuneResult(
            int threads,
            List<ThreadMeasurement> measurements
        ) {
            this.threads = threads;
            this.measurements = measurements;
        }
    }
}
