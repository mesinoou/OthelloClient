import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class SearchEvaluationTeacherGenerator {

    private static final int TABLE_CAPACITY = 1 << 20;

    private SearchEvaluationTeacherGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.parse(args);
        run(settings);
    }

    private static void run(Settings settings) throws Exception {
        PositionEvaluator base = LearnedEvaluator.load(settings.model);
        SamplingEvaluator sampling = new SamplingEvaluator(
            base,
            settings.samplesPerPosition
        );
        SearchEngine sourceEngine = createSourceEngine(sampling);
        SearchEngine teacherEngine = createTeacherEngine(base);
        Set<PositionKey> emitted = new HashSet<>();
        long started = System.nanoTime();
        int positions = 0;
        int samples = 0;
        long sourceNodes = 0L;
        long teacherNodes = 0L;

        Path parent = settings.output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedReader input = Files.newBufferedReader(
            settings.input,
            StandardCharsets.UTF_8
        ); BufferedWriter output = Files.newBufferedWriter(
            settings.output,
            StandardCharsets.UTF_8
        )) {
            String header = input.readLine();
            require(
                (
                    "parent_id\tsplit\tphase\tply\tblack\twhite\tplayer"
                        + "\tsample_count\ttheoretical_count"
                ).equals(header),
                "unexpected position header"
            );
            writeHeader(output);
            String line;
            long sampleId = 0L;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (settings.maximumPositions > 0
                    && positions >= settings.maximumPositions) {
                    break;
                }
                PositionRow row = PositionRow.parse(line);
                BitBoardPosition position = new BitBoardPosition(
                    row.black,
                    row.white
                );
                long player = position.player(row.player);
                long opponent = position.opponent(row.player);
                sampling.start(player, opponent);
                SearchResult source = sourceEngine.search(
                    position,
                    row.player,
                    new SearchLimits(
                        settings.timeMillis,
                        settings.searchDepth,
                        1
                    )
                );
                requireComplete(source, settings.searchDepth, "source");
                sourceNodes += source.nodes();

                for (TraceEntry entry : sampling.finish()) {
                    PositionKey key = new PositionKey(
                        entry.player,
                        entry.opponent
                    );
                    if (!emitted.add(key)) {
                        continue;
                    }
                    BitBoardPosition leaf = new BitBoardPosition(
                        entry.player,
                        entry.opponent
                    );
                    SearchResult teacher = teacherEngine.search(
                        leaf,
                        1,
                        new SearchLimits(
                            settings.timeMillis,
                            settings.teacherDepth,
                            1
                        )
                    );
                    requireComplete(
                        teacher,
                        settings.teacherDepth,
                        "teacher"
                    );
                    writeSample(
                        output,
                        sampleId,
                        row,
                        entry,
                        settings,
                        teacher
                    );
                    sampleId++;
                    samples++;
                    teacherNodes += teacher.nodes();
                }
                positions++;
                if (positions % 8 == 0) {
                    printProgress(
                        positions,
                        samples,
                        sourceNodes,
                        teacherNodes,
                        started
                    );
                }
            }
            printProgress(
                positions,
                samples,
                sourceNodes,
                teacherNodes,
                started
            );
        } finally {
            sourceEngine.shutdown();
            teacherEngine.shutdown();
        }
    }

    private static SearchEngine createSourceEngine(
        PositionEvaluator evaluator
    ) {
        return new SearchEngine(
            evaluator,
            new TranspositionTable(TABLE_CAPACITY),
            true,
            0,
            true,
            true,
            true,
            false,
            false
        );
    }

    private static SearchEngine createTeacherEngine(
        PositionEvaluator evaluator
    ) {
        return new SearchEngine(
            evaluator,
            new TranspositionTable(TABLE_CAPACITY),
            true,
            0,
            false,
            true,
            true,
            false,
            false
        );
    }

    private static void requireComplete(
        SearchResult result,
        int depth,
        String name
    ) {
        require(!result.timedOut(), name + " search timed out");
        require(!result.stopped(), name + " search stopped");
        require(
            result.exactSolution() || result.completedDepth() == depth,
            name + " search did not complete depth " + depth
        );
    }

    private static void writeHeader(BufferedWriter output)
        throws IOException {
        output.write(
            "sample_id\tsplit\tsource_parent_id\tleaf_phase\tleaf_ply"
                + "\tblack\twhite\tplayer\toccurrences\tstatic_score"
                + "\tdeep_score\tsource_depth\tteacher_depth"
                + "\tteacher_completed_depth\tteacher_nodes"
                + "\tteacher_exact"
        );
        output.newLine();
    }

    private static void writeSample(
        BufferedWriter output,
        long sampleId,
        PositionRow source,
        TraceEntry entry,
        Settings settings,
        SearchResult teacher
    ) throws IOException {
        int ply = Long.bitCount(entry.player | entry.opponent) - 4;
        require(ply >= 0 && ply <= 60, "invalid leaf ply");
        output.write(String.format(
            Locale.ROOT,
            "%d\t%s\t%d\t%d\t%d\t%016x\t%016x\t1\t%d\t%d\t%d"
                + "\t%d\t%d\t%d\t%d\t%b",
            sampleId,
            source.split,
            source.parentId,
            phaseForPly(ply),
            ply,
            entry.player,
            entry.opponent,
            entry.occurrences,
            entry.staticScore,
            teacher.score(),
            settings.searchDepth,
            settings.teacherDepth,
            teacher.completedDepth(),
            teacher.nodes(),
            teacher.exactSolution()
        ));
        output.newLine();
    }

    private static int phaseForPly(int ply) {
        if (ply < 30) {
            return 0;
        }
        if (ply < 40) {
            return 1;
        }
        if (ply < 50) {
            return 2;
        }
        return 3;
    }

    private static void printProgress(
        int positions,
        int samples,
        long sourceNodes,
        long teacherNodes,
        long started
    ) {
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf(
            Locale.ROOT,
            "search leaves positions=%d samples=%d source_nodes=%d"
                + " teacher_nodes=%d elapsed=%.1fs%n",
            positions,
            samples,
            sourceNodes,
            teacherNodes,
            seconds
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static final class SamplingEvaluator
        implements PositionEvaluator {

        private final PositionEvaluator delegate;
        private final int capacity;
        private final Map<PositionKey, TraceEntry> selected = new HashMap<>();
        private final PriorityQueue<TraceEntry> worstFirst =
            new PriorityQueue<>(
                (left, right) -> Long.compareUnsigned(
                    right.hash,
                    left.hash
                )
            );
        private PositionKey excluded;

        private SamplingEvaluator(
            PositionEvaluator delegate,
            int capacity
        ) {
            this.delegate = delegate;
            this.capacity = capacity;
        }

        private void start(long player, long opponent) {
            selected.clear();
            worstFirst.clear();
            excluded = new PositionKey(player, opponent);
        }

        private List<TraceEntry> finish() {
            List<TraceEntry> result = new ArrayList<>(selected.values());
            result.sort(
                (left, right) -> {
                    int hashComparison = Long.compareUnsigned(
                        left.hash,
                        right.hash
                    );
                    if (hashComparison != 0) {
                        return hashComparison;
                    }
                    return PositionKey.compare(
                        left.player,
                        left.opponent,
                        right.player,
                        right.opponent
                    );
                }
            );
            return result;
        }

        @Override
        public int evaluate(long player, long opponent) {
            int score = delegate.evaluate(player, opponent);
            PositionKey key = new PositionKey(player, opponent);
            if (key.equals(excluded)) {
                return score;
            }
            TraceEntry existing = selected.get(key);
            if (existing != null) {
                existing.occurrences++;
                return score;
            }

            long hash = sampleHash(player, opponent);
            if (selected.size() < capacity) {
                add(key, player, opponent, score, hash);
            } else {
                TraceEntry worst = worstFirst.peek();
                if (worst != null
                    && Long.compareUnsigned(hash, worst.hash) < 0) {
                    worstFirst.remove();
                    selected.remove(worst.key);
                    add(key, player, opponent, score, hash);
                }
            }
            return score;
        }

        private void add(
            PositionKey key,
            long player,
            long opponent,
            int score,
            long hash
        ) {
            TraceEntry entry = new TraceEntry(
                key,
                player,
                opponent,
                score,
                hash
            );
            selected.put(key, entry);
            worstFirst.add(entry);
        }

        @Override
        public int terminalScore(long player, long opponent) {
            return delegate.terminalScore(player, opponent);
        }

        @Override
        public String description() {
            return delegate.description() + " + search-leaf-sampler";
        }

        private static long sampleHash(long player, long opponent) {
            return mix64(player) ^ Long.rotateLeft(mix64(opponent), 29);
        }

        private static long mix64(long value) {
            value ^= value >>> 30;
            value *= 0xbf58476d1ce4e5b9L;
            value ^= value >>> 27;
            value *= 0x94d049bb133111ebL;
            return value ^ (value >>> 31);
        }
    }

    private static final class TraceEntry {
        private final PositionKey key;
        private final long player;
        private final long opponent;
        private final int staticScore;
        private final long hash;
        private int occurrences = 1;

        private TraceEntry(
            PositionKey key,
            long player,
            long opponent,
            int staticScore,
            long hash
        ) {
            this.key = key;
            this.player = player;
            this.opponent = opponent;
            this.staticScore = staticScore;
            this.hash = hash;
        }
    }

    private static final class PositionRow {
        private final long parentId;
        private final String split;
        private final int ply;
        private final long black;
        private final long white;
        private final int player;

        private PositionRow(
            long parentId,
            String split,
            int ply,
            long black,
            long white,
            int player
        ) {
            this.parentId = parentId;
            this.split = split;
            this.ply = ply;
            this.black = black;
            this.white = white;
            this.player = player;
        }

        private static PositionRow parse(String line) {
            String[] values = line.split("\\t", -1);
            require(values.length == 9, "invalid position row");
            int player = Integer.parseInt(values[6]);
            require(player == 1 || player == -1, "invalid player");
            int ply = Integer.parseInt(values[3]);
            long black = Long.parseUnsignedLong(values[4], 16);
            long white = Long.parseUnsignedLong(values[5], 16);
            require(
                Long.bitCount(black | white) == ply + 4,
                "occupied count mismatch"
            );
            return new PositionRow(
                Long.parseLong(values[0]),
                values[1],
                ply,
                black,
                white,
                player
            );
        }
    }

    private static final class Settings {
        private final Path input;
        private final Path output;
        private final Path model;
        private final int searchDepth;
        private final int teacherDepth;
        private final long timeMillis;
        private final int samplesPerPosition;
        private final int maximumPositions;

        private Settings(
            Path input,
            Path output,
            Path model,
            int searchDepth,
            int teacherDepth,
            long timeMillis,
            int samplesPerPosition,
            int maximumPositions
        ) {
            this.input = input;
            this.output = output;
            this.model = model;
            this.searchDepth = searchDepth;
            this.teacherDepth = teacherDepth;
            this.timeMillis = timeMillis;
            this.samplesPerPosition = samplesPerPosition;
            this.maximumPositions = maximumPositions;
        }

        private static Settings parse(String[] args) {
            if (args.length < 7 || args.length > 8) {
                throw new IllegalArgumentException(
                    "Usage: java SearchEvaluationTeacherGenerator"
                        + " <positions.tsv> <output.tsv> <model>"
                        + " <search-depth> <teacher-depth> <time-millis>"
                        + " <samples-per-position> [maximum-positions]"
                );
            }
            int searchDepth = Integer.parseInt(args[3]);
            int teacherDepth = Integer.parseInt(args[4]);
            long timeMillis = Long.parseLong(args[5]);
            int samples = Integer.parseInt(args[6]);
            int maximum = args.length == 8
                ? Integer.parseInt(args[7])
                : 0;
            require(searchDepth >= 2, "search depth must be at least 2");
            require(teacherDepth >= 1, "teacher depth must be positive");
            require(timeMillis >= 1L, "time must be positive");
            require(samples >= 1, "samples per position must be positive");
            require(maximum >= 0, "maximum positions cannot be negative");
            return new Settings(
                Paths.get(args[0]),
                Paths.get(args[1]),
                Paths.get(args[2]),
                searchDepth,
                teacherDepth,
                timeMillis,
                samples,
                maximum
            );
        }
    }
}
