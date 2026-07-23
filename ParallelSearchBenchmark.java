import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class ParallelSearchBenchmark {

    private static final String BENCHMARK_VERSION = "parallel-search-v4";
    private static final int MIN_POSITION_PLY = 16;
    private static final int MAX_POSITION_PLY = 32;
    private static final int PRIME_DEPTH = 3;
    private static final int MAX_GENERATION_ATTEMPTS = 100_000;

    private final Config config;
    private final PositionEvaluator evaluator;
    private final PositionEvaluator moveOrderingEvaluator;
    private final String modelSha256;
    private final String orderingModelSha256;
    private final List<PositionToMove> positions;
    private final String suiteSha256;
    private final String generatedAtUtc;
    private final String gitRevision;
    private final ThreadMXBean contentionBean;
    private final Map<String, Aggregate> aggregates = new LinkedHashMap<>();

    private PrintWriter output;
    private boolean closeOutput;

    private ParallelSearchBenchmark(Config config) throws Exception {
        this.config = config;
        if (config.modelPath == null) {
            evaluator = new Evaluator();
            modelSha256 = "handcrafted";
        } else {
            Path model = config.modelPath.toAbsolutePath().normalize();
            evaluator = LearnedEvaluator.load(model);
            modelSha256 = sha256(model);
        }
        if (config.orderingModelPath == null) {
            moveOrderingEvaluator = null;
            orderingModelSha256 = "off";
        } else {
            Path model = config.orderingModelPath.toAbsolutePath().normalize();
            moveOrderingEvaluator = LearnedEvaluator.load(model);
            orderingModelSha256 = sha256(model);
        }
        positions = generatePositions(config.positionCount, config.seed);
        suiteSha256 = hashPositions(positions);
        generatedAtUtc = Instant.now().toString();
        gitRevision = gitRevision();
        contentionBean = createContentionBean(config.contentionMetrics);
    }

    public static void main(String[] args) throws Exception {
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException error) {
            System.err.println("Error: " + error.getMessage());
            Config.printUsage(System.err);
            return;
        }
        if (config.help) {
            Config.printUsage(System.out);
            return;
        }
        new ParallelSearchBenchmark(config).run();
    }

    private void run() throws Exception {
        openOutput();
        try {
            printHeader();
            warmUp();
            if (config.mode.includesFixed()) {
                runFixedDepth();
            }
            if (config.mode.includesTimed()) {
                runTimed();
            }
        } finally {
            output.flush();
            if (closeOutput) {
                output.close();
            }
        }
        printSummary();
        if (config.outputPath != null) {
            System.err.println(
                "CSV written to "
                    + config.outputPath.toAbsolutePath().normalize()
            );
        }
    }

    private void openOutput() throws IOException {
        if (config.outputPath == null) {
            output = new PrintWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8),
                true
            );
            return;
        }

        Path normalized = config.outputPath.toAbsolutePath().normalize();
        if (Files.exists(normalized) && !config.overwrite) {
            throw new IOException(
                "output already exists; use --overwrite to replace it: "
                    + normalized
            );
        }
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        output = new PrintWriter(
            Files.newBufferedWriter(normalized, StandardCharsets.UTF_8)
        );
        closeOutput = true;
    }

    private void printHeader() {
        csv(
            "benchmarkVersion",
            "generatedAtUtc",
            "gitRevision",
            "modelSha256",
            "orderingModelSha256",
            "javaVersion",
            "osName",
            "osVersion",
            "availableProcessors",
            "evaluator",
            "moveOrderingEvaluator",
            "orderingMinimumDepth",
            "suiteSha256",
            "seed",
            "mode",
            "threads",
            "position",
            "repetition",
            "targetPly",
            "black",
            "white",
            "color",
            "depthLimit",
            "timeLimitMillis",
            "completedDepth",
            "bestSquare",
            "score",
            "elapsedMillis",
            "nodes",
            "nodesPerSecond",
            "mainThreadNodes",
            "parallelNodes",
            "workerShare",
            "parallelTasks",
            "parallelWorkerNodes",
            "workerMonitorBlocks",
            "workerMonitorBlockedMillis",
            "stabilityEnabled",
            "stabilityChecks",
            "stabilityCuts",
            "transpositionHits",
            "betaCutoffs",
            "pvsResearches",
            "lmrSearches",
            "lmrResearches",
            "mpcAttempts",
            "mpcHighCuts",
            "mpcLowCuts",
            "mpcProbeNodes",
            "timedOut",
            "consistent"
        );
    }

    private void warmUp() {
        int warmupDepth = Math.min(6, config.fixedDepth);
        for (int threads : config.threads) {
            SearchEngine engine = createPreparedEngine(threads);
            try {
                for (int warmup = 0; warmup < config.warmups; warmup++) {
                    PositionToMove sample = positions.get(
                        warmup % positions.size()
                    );
                    engine.search(
                        sample.position,
                        sample.color,
                        new SearchLimits(30_000L, warmupDepth, threads)
                    );
                }
            } finally {
                engine.shutdown();
            }
        }
    }

    private void runFixedDepth() {
        List<ExpectedResult> expected = fixedDepthReferences();
        runMode(
            "fixed",
            config.fixedDepth,
            config.fixedTimeoutMillis,
            expected
        );
    }

    private void runTimed() {
        runMode("timed", 64, config.timedMillis, null);
    }

    private void runMode(
        String mode,
        int depth,
        long timeMillis,
        List<ExpectedResult> expected
    ) {
        for (int repetition = 0; repetition < config.repetitions; repetition++) {
            Map<Integer, SearchEngine> engines = createEngines();
            try {
                for (int positionIndex = 0;
                    positionIndex < positions.size();
                    positionIndex++) {
                    for (int offset = 0;
                        offset < config.threads.size();
                        offset++) {
                        int threadIndex = (
                            repetition + positionIndex + offset
                        ) % config.threads.size();
                        int threads = config.threads.get(threadIndex);
                        PositionToMove sample = positions.get(positionIndex);
                        SearchMeasurement measurement = measuredSearch(
                            engines.get(threads),
                            sample.position,
                            sample.color,
                            new SearchLimits(timeMillis, depth, threads)
                        );
                        SearchResult result = measurement.result;
                        validateLegalMove(sample, result);

                        boolean consistent = true;
                        if (expected != null) {
                            ExpectedResult reference = expected.get(positionIndex);
                            consistent = result.completedDepth() == depth
                                && !result.timedOut()
                                && result.score() == reference.score
                                && result.bestSquare() == reference.bestSquare;
                            if (!consistent) {
                                throw new AssertionError(
                                    "fixed-depth mismatch: position="
                                        + positionIndex
                                        + ", threads=" + threads
                                        + ", expectedDepth=" + depth
                                        + ", actualDepth="
                                        + result.completedDepth()
                                        + ", expectedMove="
                                        + reference.bestSquare
                                        + ", actualMove="
                                        + result.bestSquare()
                                        + ", expectedScore="
                                        + reference.score
                                        + ", actualScore=" + result.score()
                                );
                            }
                        }

                        writeResult(
                            mode,
                            threads,
                            positionIndex,
                            repetition,
                            sample,
                            depth,
                            timeMillis,
                            result,
                            measurement,
                            expected == null ? "" : Boolean.toString(consistent)
                        );
                        aggregate(mode, threads).add(result, measurement);
                    }
                }
            } finally {
                for (SearchEngine engine : engines.values()) {
                    engine.shutdown();
                }
            }
        }
    }

    private List<ExpectedResult> fixedDepthReferences() {
        List<ExpectedResult> expected = new ArrayList<>(positions.size());
        SearchEngine engine = createPreparedEngine(1);
        try {
            for (int index = 0; index < positions.size(); index++) {
                PositionToMove sample = positions.get(index);
                SearchResult result = engine.search(
                    sample.position,
                    sample.color,
                    new SearchLimits(
                        config.fixedTimeoutMillis,
                        config.fixedDepth,
                        1
                    )
                );
                validateLegalMove(sample, result);
                if (result.completedDepth() != config.fixedDepth
                    || result.timedOut()) {
                    throw new AssertionError(
                        "single-thread reference did not complete depth "
                            + config.fixedDepth + " at position " + index
                    );
                }
                expected.add(
                    new ExpectedResult(result.bestSquare(), result.score())
                );
            }
        } finally {
            engine.shutdown();
        }
        return expected;
    }

    private Map<Integer, SearchEngine> createEngines() {
        Map<Integer, SearchEngine> engines = new LinkedHashMap<>();
        for (int threads : config.threads) {
            engines.put(threads, createPreparedEngine(threads));
        }
        return engines;
    }

    private SearchEngine createPreparedEngine(int threads) {
        SearchEngine engine = new SearchEngine(
            evaluator,
            new TranspositionTable(config.transpositionCapacity),
            true,
            0,
            true,
            true,
            config.stabilityCutoff,
            true,
            true,
            moveOrderingEvaluator,
            moveOrderingEvaluator == null
                ? 0
                : config.orderingMinimumDepth
        );
        engine.search(
            BitBoardPosition.initial(),
            1,
            new SearchLimits(30_000L, PRIME_DEPTH, threads)
        );
        engine.prestartWorkerThreads();
        return engine;
    }

    private SearchMeasurement measuredSearch(
        SearchEngine engine,
        BitBoardPosition position,
        int color,
        SearchLimits limits
    ) {
        MonitorSnapshot before = monitorSnapshot(engine);
        SearchResult result = engine.search(position, color, limits);
        MonitorSnapshot after = monitorSnapshot(engine);
        return new SearchMeasurement(
            result,
            Math.max(0L, after.blockedCount - before.blockedCount),
            Math.max(0L, after.blockedMillis - before.blockedMillis)
        );
    }

    private MonitorSnapshot monitorSnapshot(SearchEngine engine) {
        if (contentionBean == null) {
            return MonitorSnapshot.ZERO;
        }
        long blockedCount = 0L;
        long blockedMillis = 0L;
        for (Thread worker : engine.workerThreadsSnapshot()) {
            ThreadInfo info = contentionBean.getThreadInfo(worker.getId());
            if (info != null) {
                blockedCount += info.getBlockedCount();
                blockedMillis += Math.max(0L, info.getBlockedTime());
            }
        }
        return new MonitorSnapshot(blockedCount, blockedMillis);
    }

    private void writeResult(
        String mode,
        int threads,
        int positionIndex,
        int repetition,
        PositionToMove sample,
        int depth,
        long timeMillis,
        SearchResult result,
        SearchMeasurement measurement,
        String consistent
    ) {
        long elapsedNanos = Math.max(1L, result.elapsedNanos());
        long nodesPerSecond = Math.round(
            result.nodes() * 1_000_000_000.0 / elapsedNanos
        );
        long mainThreadNodes = result.nodes() - result.parallelNodes();
        double workerShare = result.nodes() == 0L
            ? 0.0
            : (double) result.parallelNodes() / result.nodes();
        csv(
            BENCHMARK_VERSION,
            generatedAtUtc,
            gitRevision,
            modelSha256,
            orderingModelSha256,
            System.getProperty("java.version"),
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            Integer.toString(Runtime.getRuntime().availableProcessors()),
            evaluator.description(),
            moveOrderingEvaluator == null
                ? "off"
                : moveOrderingEvaluator.description(),
            Integer.toString(moveOrderingEvaluator == null
                ? 0
                : config.orderingMinimumDepth),
            suiteSha256,
            Long.toString(config.seed),
            mode,
            Integer.toString(threads),
            Integer.toString(positionIndex),
            Integer.toString(repetition),
            Integer.toString(sample.targetPly),
            hex64(sample.position.black()),
            hex64(sample.position.white()),
            Integer.toString(sample.color),
            Integer.toString(depth),
            Long.toString(timeMillis),
            Integer.toString(result.completedDepth()),
            Integer.toString(result.bestSquare()),
            Integer.toString(result.score()),
            formatDouble(elapsedNanos / 1_000_000.0),
            Long.toString(result.nodes()),
            Long.toString(nodesPerSecond),
            Long.toString(mainThreadNodes),
            Long.toString(result.parallelNodes()),
            formatDouble(workerShare),
            Integer.toString(result.parallelTasks()),
            join(result.parallelWorkerNodes()),
            Long.toString(measurement.workerMonitorBlocks),
            Long.toString(measurement.workerMonitorBlockedMillis),
            Boolean.toString(config.stabilityCutoff),
            Long.toString(result.stabilityChecks()),
            Long.toString(result.stabilityCuts()),
            Long.toString(result.transpositionHits()),
            Long.toString(result.betaCutoffs()),
            Long.toString(result.pvsResearches()),
            Long.toString(result.lmrSearches()),
            Long.toString(result.lmrResearches()),
            Long.toString(result.mpcAttempts()),
            Long.toString(result.mpcHighCuts()),
            Long.toString(result.mpcLowCuts()),
            Long.toString(result.mpcProbeNodes()),
            Boolean.toString(result.timedOut()),
            consistent
        );
    }

    private void printSummary() {
        System.err.println(
            "mode threads samples avgDepth avgMillis nodesPerSecond "
                + "workerShare monitorBlocks blockedMillis fixedSpeedup"
        );
        Aggregate fixedBaseline = aggregates.get("fixed:1");
        for (Aggregate aggregate : aggregates.values()) {
            String speedup = "";
            if (fixedBaseline != null && "fixed".equals(aggregate.mode)) {
                speedup = formatDouble(
                    fixedBaseline.averageMillis() / aggregate.averageMillis()
                );
            }
            System.err.printf(
                Locale.ROOT,
                "%s %d %d %.2f %.3f %.0f %.3f %d %d %s%n",
                aggregate.mode,
                aggregate.threads,
                aggregate.samples,
                aggregate.averageDepth(),
                aggregate.averageMillis(),
                aggregate.nodesPerSecond(),
                aggregate.workerShare(),
                aggregate.workerMonitorBlocks,
                aggregate.workerMonitorBlockedMillis,
                speedup
            );
        }
    }

    private Aggregate aggregate(String mode, int threads) {
        String key = mode + ":" + threads;
        Aggregate aggregate = aggregates.get(key);
        if (aggregate == null) {
            aggregate = new Aggregate(mode, threads);
            aggregates.put(key, aggregate);
        }
        return aggregate;
    }

    private void csv(String... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                output.print(',');
            }
            output.print(csvValue(values[index]));
        }
        output.println();
    }

    private static String csvValue(String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') < 0
            && safe.indexOf('"') < 0
            && safe.indexOf('\n') < 0
            && safe.indexOf('\r') < 0) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String join(long[] values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                result.append(';');
            }
            result.append(values[index]);
        }
        return result.toString();
    }

    private static void validateLegalMove(
        PositionToMove sample,
        SearchResult result
    ) {
        long player = sample.position.player(sample.color);
        long opponent = sample.position.opponent(sample.color);
        long legalMoves = BitBoard.legalMoves(player, opponent);
        int square = result.bestSquare();
        if (square < 0
            || square >= 64
            || (legalMoves & (1L << square)) == 0L) {
            throw new AssertionError(
                "search returned an illegal move: " + square
            );
        }
    }

    private static List<PositionToMove> generatePositions(
        int count,
        long seed
    ) {
        Random random = new Random(seed);
        List<PositionToMove> generated = new ArrayList<>(count);
        Set<String> seen = new HashSet<>();
        int attempts = 0;
        while (generated.size() < count) {
            if (++attempts > MAX_GENERATION_ATTEMPTS) {
                throw new IllegalStateException(
                    "could not generate enough benchmark positions"
                );
            }
            int targetPly = MIN_POSITION_PLY + random.nextInt(
                MAX_POSITION_PLY - MIN_POSITION_PLY + 1
            );
            PositionToMove sample = randomPlayout(random, targetPly);
            if (sample == null) {
                continue;
            }
            long moves = BitBoard.legalMoves(
                sample.position.player(sample.color),
                sample.position.opponent(sample.color)
            );
            if (Long.bitCount(moves) < 3) {
                continue;
            }
            String key = sample.position.black()
                + ":" + sample.position.white() + ":" + sample.color;
            if (seen.add(key)) {
                generated.add(sample);
            }
        }
        return Collections.unmodifiableList(generated);
    }

    private static PositionToMove randomPlayout(Random random, int targetPly) {
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        int played = 0;
        while (played < targetPly) {
            long player = position.player(color);
            long opponent = position.opponent(color);
            long moves = BitBoard.legalMoves(player, opponent);
            if (moves == 0L) {
                if (BitBoard.legalMoves(opponent, player) == 0L) {
                    return null;
                }
                color = -color;
                continue;
            }
            int selected = random.nextInt(Long.bitCount(moves));
            long remaining = moves;
            while (selected-- > 0) {
                remaining &= remaining - 1L;
            }
            long move = remaining & -remaining;
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            position = color == 1
                ? new BitBoardPosition(nextPlayer, nextOpponent)
                : new BitBoardPosition(nextOpponent, nextPlayer);
            color = -color;
            played++;
        }
        return new PositionToMove(position, color, targetPly);
    }

    private static String hashPositions(List<PositionToMove> samples)
        throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        for (PositionToMove sample : samples) {
            buffer.clear();
            buffer.putLong(sample.position.black());
            buffer.putLong(sample.position.white());
            buffer.putInt(sample.color);
            digest.update(buffer.array());
        }
        return hex(digest.digest());
    }

    private static String sha256(Path path)
        throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        }
        return result.toString();
    }

    private static String hex64(long value) {
        return String.format(Locale.ROOT, "%016X", value);
    }

    private static String gitRevision() {
        try {
            String commit = commandOutput("git", "rev-parse", "HEAD");
            String status = commandOutput("git", "status", "--porcelain");
            return status.isEmpty() ? commit : commit + "-dirty";
        } catch (IOException | InterruptedException error) {
            return "unknown";
        }
    }

    private static ThreadMXBean createContentionBean(boolean enabled) {
        if (!enabled) {
            return null;
        }
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadContentionMonitoringSupported()) {
            throw new IllegalStateException(
                "thread contention monitoring is not supported"
            );
        }
        if (!bean.isThreadContentionMonitoringEnabled()) {
            bean.setThreadContentionMonitoringEnabled(true);
        }
        return bean;
    }

    private static String commandOutput(String... command)
        throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        byte[] bytes = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("command failed: " + Arrays.toString(command));
        }
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private enum Mode {
        FIXED,
        TIMED,
        ALL;

        boolean includesFixed() {
            return this == FIXED || this == ALL;
        }

        boolean includesTimed() {
            return this == TIMED || this == ALL;
        }

        static Mode parse(String value) {
            if ("fixed".equalsIgnoreCase(value)) {
                return FIXED;
            }
            if ("timed".equalsIgnoreCase(value)) {
                return TIMED;
            }
            if ("all".equalsIgnoreCase(value)) {
                return ALL;
            }
            throw new IllegalArgumentException(
                "mode must be fixed, timed, or all"
            );
        }
    }

    private static final class Config {

        private Path modelPath = Paths.get("data", "evaluation-tables.bin");
        private Path orderingModelPath;
        private Path outputPath;
        private Mode mode = Mode.ALL;
        private List<Integer> threads = Arrays.asList(1, 2, 4, 8);
        private int positionCount = 8;
        private int fixedDepth = 9;
        private long fixedTimeoutMillis = 60_000L;
        private long timedMillis = 500L;
        private int repetitions = 2;
        private int warmups = 1;
        private long seed = 20260721L;
        private int transpositionCapacity = 1 << 18;
        private int orderingMinimumDepth = 6;
        private boolean stabilityCutoff = true;
        private boolean contentionMetrics;
        private boolean overwrite;
        private boolean help;

        static Config parse(String[] args) {
            Config config = new Config();
            for (int index = 0; index < args.length; index++) {
                String option = args[index];
                if ("--help".equals(option) || "-h".equals(option)) {
                    config.help = true;
                } else if ("--model".equals(option)) {
                    config.modelPath = Paths.get(value(args, ++index, option));
                } else if ("--handcrafted".equals(option)) {
                    config.modelPath = null;
                } else if ("--ordering-model".equals(option)) {
                    config.orderingModelPath = Paths.get(
                        value(args, ++index, option)
                    );
                } else if ("--ordering-min-depth".equals(option)) {
                    config.orderingMinimumDepth = parseInt(
                        args,
                        ++index,
                        option
                    );
                } else if ("--output".equals(option)) {
                    config.outputPath = Paths.get(value(args, ++index, option));
                } else if ("--overwrite".equals(option)) {
                    config.overwrite = true;
                } else if ("--contention-metrics".equals(option)) {
                    config.contentionMetrics = true;
                } else if ("--disable-stability".equals(option)) {
                    config.stabilityCutoff = false;
                } else if ("--mode".equals(option)) {
                    config.mode = Mode.parse(value(args, ++index, option));
                } else if ("--threads".equals(option)) {
                    config.threads = parseThreads(value(args, ++index, option));
                } else if ("--positions".equals(option)) {
                    config.positionCount = parseInt(args, ++index, option);
                } else if ("--depth".equals(option)) {
                    config.fixedDepth = parseInt(args, ++index, option);
                } else if ("--fixed-timeout-ms".equals(option)) {
                    config.fixedTimeoutMillis = parseLong(args, ++index, option);
                } else if ("--time-ms".equals(option)) {
                    config.timedMillis = parseLong(args, ++index, option);
                } else if ("--repetitions".equals(option)) {
                    config.repetitions = parseInt(args, ++index, option);
                } else if ("--warmups".equals(option)) {
                    config.warmups = parseInt(args, ++index, option);
                } else if ("--seed".equals(option)) {
                    config.seed = parseLong(args, ++index, option);
                } else if ("--tt-capacity".equals(option)) {
                    config.transpositionCapacity = parseInt(
                        args,
                        ++index,
                        option
                    );
                } else {
                    throw new IllegalArgumentException(
                        "unknown option: " + option
                    );
                }
            }
            config.validate();
            return config;
        }

        private void validate() {
            require(positionCount > 0, "positions must be positive");
            require(
                fixedDepth >= 1 && fixedDepth <= 64,
                "depth must be between 1 and 64"
            );
            require(
                fixedTimeoutMillis > 0L,
                "fixed timeout must be positive"
            );
            require(timedMillis > 0L, "time must be positive");
            require(repetitions > 0, "repetitions must be positive");
            require(warmups >= 0, "warmups must not be negative");
            require(
                transpositionCapacity > 0
                    && (transpositionCapacity
                        & (transpositionCapacity - 1)) == 0,
                "tt capacity must be a power of two"
            );
            require(
                threads.contains(1),
                "thread configurations must include 1"
            );
            require(
                orderingMinimumDepth >= 1
                    && orderingMinimumDepth <= 64,
                "ordering minimum depth must be between 1 and 64"
            );
        }

        static void printUsage(java.io.PrintStream stream) {
            stream.println(
                "Usage: java -cp .build ParallelSearchBenchmark [options]"
            );
            stream.println("  --model PATH             learned model (default data/evaluation-tables.bin)");
            stream.println("  --ordering-model PATH    learned model used only for move ordering");
            stream.println("  --ordering-min-depth N   remaining depth where learned ordering starts (default 6)");
            stream.println("  --handcrafted            use the handcrafted evaluator");
            stream.println("  --mode fixed|timed|all   benchmark mode (default all)");
            stream.println("  --threads LIST           comma-separated counts (default 1,2,4,8)");
            stream.println("  --positions N            deterministic positions (default 8)");
            stream.println("  --depth N                fixed-search depth (default 9)");
            stream.println("  --fixed-timeout-ms N     fixed-depth safety timeout (default 60000)");
            stream.println("  --time-ms N              timed-search budget (default 500)");
            stream.println("  --repetitions N          suite repetitions (default 2)");
            stream.println("  --warmups N              warmups per thread count (default 1)");
            stream.println("  --seed N                 position seed (default 20260721)");
            stream.println("  --tt-capacity N          power-of-two entries (default 262144)");
            stream.println("  --disable-stability      disable stability bound cutoff");
            stream.println("  --output PATH            write detailed CSV to PATH");
            stream.println("  --contention-metrics     record worker monitor blocking");
            stream.println("  --overwrite              replace an existing output file");
        }

        private static List<Integer> parseThreads(String value) {
            LinkedHashSet<Integer> parsed = new LinkedHashSet<>();
            for (String item : value.split(",")) {
                int threads;
                try {
                    threads = Integer.parseInt(item.trim());
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException(
                        "invalid thread count: " + item,
                        error
                    );
                }
                require(threads > 0, "thread counts must be positive");
                parsed.add(threads);
            }
            require(!parsed.isEmpty(), "threads must not be empty");
            return Collections.unmodifiableList(new ArrayList<>(parsed));
        }

        private static int parseInt(
            String[] args,
            int index,
            String option
        ) {
            try {
                return Integer.parseInt(value(args, index, option));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                    "invalid integer for " + option,
                    error
                );
            }
        }

        private static long parseLong(
            String[] args,
            int index,
            String option
        ) {
            try {
                return Long.parseLong(value(args, index, option));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                    "invalid integer for " + option,
                    error
                );
            }
        }

        private static String value(
            String[] args,
            int index,
            String option
        ) {
            if (index >= args.length) {
                throw new IllegalArgumentException(
                    "missing value for " + option
                );
            }
            return args[index];
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static final class PositionToMove {

        private final BitBoardPosition position;
        private final int color;
        private final int targetPly;

        private PositionToMove(
            BitBoardPosition position,
            int color,
            int targetPly
        ) {
            this.position = position;
            this.color = color;
            this.targetPly = targetPly;
        }
    }

    private static final class ExpectedResult {

        private final int bestSquare;
        private final int score;

        private ExpectedResult(int bestSquare, int score) {
            this.bestSquare = bestSquare;
            this.score = score;
        }
    }

    private static final class SearchMeasurement {

        private final SearchResult result;
        private final long workerMonitorBlocks;
        private final long workerMonitorBlockedMillis;

        private SearchMeasurement(
            SearchResult result,
            long workerMonitorBlocks,
            long workerMonitorBlockedMillis
        ) {
            this.result = result;
            this.workerMonitorBlocks = workerMonitorBlocks;
            this.workerMonitorBlockedMillis = workerMonitorBlockedMillis;
        }
    }

    private static final class MonitorSnapshot {

        private static final MonitorSnapshot ZERO = new MonitorSnapshot(0L, 0L);

        private final long blockedCount;
        private final long blockedMillis;

        private MonitorSnapshot(long blockedCount, long blockedMillis) {
            this.blockedCount = blockedCount;
            this.blockedMillis = blockedMillis;
        }
    }

    private static final class Aggregate {

        private final String mode;
        private final int threads;

        private long samples;
        private long completedDepth;
        private long elapsedNanos;
        private long nodes;
        private long parallelNodes;
        private long workerMonitorBlocks;
        private long workerMonitorBlockedMillis;

        private Aggregate(String mode, int threads) {
            this.mode = mode;
            this.threads = threads;
        }

        private void add(
            SearchResult result,
            SearchMeasurement measurement
        ) {
            samples++;
            completedDepth += result.completedDepth();
            elapsedNanos += result.elapsedNanos();
            nodes += result.nodes();
            parallelNodes += result.parallelNodes();
            workerMonitorBlocks += measurement.workerMonitorBlocks;
            workerMonitorBlockedMillis +=
                measurement.workerMonitorBlockedMillis;
        }

        private double averageDepth() {
            return samples == 0L ? 0.0 : (double) completedDepth / samples;
        }

        private double averageMillis() {
            return samples == 0L
                ? 0.0
                : elapsedNanos / 1_000_000.0 / samples;
        }

        private double nodesPerSecond() {
            return elapsedNanos == 0L
                ? 0.0
                : nodes * 1_000_000_000.0 / elapsedNanos;
        }

        private double workerShare() {
            return nodes == 0L ? 0.0 : (double) parallelNodes / nodes;
        }
    }
}
