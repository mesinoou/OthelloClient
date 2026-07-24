import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RankingTeacherGenerator {

    private static final int TABLE_CAPACITY = 1 << 20;

    private RankingTeacherGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.parse(args);
        run(settings);
    }

    private static void run(Settings settings) throws Exception {
        PositionEvaluator teacher = LearnedEvaluator.load(
            settings.teacherModel
        );
        List<NamedEvaluator> evaluators = new ArrayList<>();
        for (ModelArgument model : settings.models) {
            evaluators.add(new NamedEvaluator(
                model.name,
                LearnedEvaluator.load(model.path)
            ));
        }
        Path parent = settings.output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        SearchEngine shallowEngine = createTeacherEngine(teacher);
        SearchEngine deepEngine = createTeacherEngine(teacher);
        long started = System.nanoTime();
        int positions = 0;
        int moves = 0;
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
            writeHeader(output, evaluators);
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                PositionRow row = PositionRow.parse(line);
                moves += scorePosition(
                    row,
                    settings,
                    evaluators,
                    shallowEngine,
                    deepEngine,
                    output
                );
                positions++;
                if (positions % 16 == 0) {
                    printProgress(positions, moves, started);
                }
            }
            printProgress(positions, moves, started);
        } finally {
            shallowEngine.shutdown();
            deepEngine.shutdown();
        }
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

    private static int scorePosition(
        PositionRow row,
        Settings settings,
        List<NamedEvaluator> evaluators,
        SearchEngine shallowEngine,
        SearchEngine deepEngine,
        BufferedWriter output
    ) throws IOException {
        BitBoardPosition position = new BitBoardPosition(row.black, row.white);
        require(
            Long.bitCount(row.black | row.white) == row.ply + 4,
            "occupied count mismatch for parent " + row.parentId
        );
        long player = position.player(row.player);
        long opponent = position.opponent(row.player);
        long legalMoves = BitBoard.legalMoves(player, opponent);
        require(
            Long.bitCount(legalMoves) >= 2,
            "parent must have at least two legal moves: " + row.parentId
        );

        int moveCount = 0;
        long remaining = legalMoves;
        while (remaining != 0L) {
            long move = remaining & -remaining;
            remaining ^= move;
            int square = Long.numberOfTrailingZeros(move);
            BitBoardPosition child = applyMove(position, row.player, move);
            SearchResult shallow = searchChild(
                shallowEngine,
                child,
                -row.player,
                settings.shallowDepth - 1,
                settings.timeMillis
            );
            SearchResult deep = searchChild(
                deepEngine,
                child,
                -row.player,
                settings.deepDepth - 1,
                settings.timeMillis
            );
            writeResult(
                output,
                row,
                child,
                square,
                shallow,
                deep,
                evaluators
            );
            moveCount++;
        }
        return moveCount;
    }

    private static SearchResult searchChild(
        SearchEngine engine,
        BitBoardPosition child,
        int color,
        int depth,
        long timeMillis
    ) {
        SearchResult result = engine.search(
            child,
            color,
            new SearchLimits(timeMillis, depth, 1)
        );
        require(!result.timedOut(), "teacher search timed out");
        require(!result.stopped(), "teacher search stopped");
        require(
            result.exactSolution() || result.completedDepth() == depth,
            "teacher search did not complete depth " + depth
        );
        return result;
    }

    private static BitBoardPosition applyMove(
        BitBoardPosition position,
        int color,
        long move
    ) {
        long player = position.player(color);
        long opponent = position.opponent(color);
        long flips = BitBoard.flips(player, opponent, move);
        require(flips != 0L, "illegal move in sampled position");
        long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
        long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
        return color == 1
            ? new BitBoardPosition(nextPlayer, nextOpponent)
            : new BitBoardPosition(nextOpponent, nextPlayer);
    }

    private static void writeHeader(
        BufferedWriter output,
        List<NamedEvaluator> evaluators
    ) throws IOException {
        output.write(
            "parent_id\tsplit\tphase\tply\tparent_black\tparent_white"
                + "\tplayer\tsample_count\ttheoretical_count\tmove"
                + "\tchild_black\tchild_white\tshallow_score"
                + "\tdeep_score\tshallow_completed_depth"
                + "\tdeep_completed_depth\tshallow_nodes\tdeep_nodes"
                + "\tshallow_exact\tdeep_exact"
        );
        for (NamedEvaluator evaluator : evaluators) {
            output.write("\tstatic_" + evaluator.name);
        }
        output.newLine();
    }

    private static void writeResult(
        BufferedWriter output,
        PositionRow row,
        BitBoardPosition child,
        int square,
        SearchResult shallow,
        SearchResult deep,
        List<NamedEvaluator> evaluators
    ) throws IOException {
        output.write(String.format(
            Locale.ROOT,
            "%d\t%s\t%d\t%d\t%016x\t%016x\t%d\t%d\t%d\t%d"
                + "\t%016x\t%016x\t%d\t%d\t%d\t%d\t%d\t%d\t%b\t%b",
            row.parentId,
            row.split,
            row.phase,
            row.ply,
            row.black,
            row.white,
            row.player,
            row.sampleCount,
            row.theoreticalCount,
            square,
            child.black(),
            child.white(),
            -shallow.score(),
            -deep.score(),
            shallow.completedDepth(),
            deep.completedDepth(),
            shallow.nodes(),
            deep.nodes(),
            shallow.exactSolution(),
            deep.exactSolution()
        ));
        for (NamedEvaluator evaluator : evaluators) {
            int staticScore = evaluator.evaluator.evaluate(
                child.player(row.player),
                child.opponent(row.player)
            );
            output.write("\t" + staticScore);
        }
        output.newLine();
    }

    private static void printProgress(
        int positions,
        int moves,
        long started
    ) {
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf(
            Locale.ROOT,
            "ranking teacher positions=%d moves=%d elapsed=%.1fs%n",
            positions,
            moves,
            seconds
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static final class NamedEvaluator {
        private final String name;
        private final PositionEvaluator evaluator;

        private NamedEvaluator(String name, PositionEvaluator evaluator) {
            this.name = name;
            this.evaluator = evaluator;
        }
    }

    private static final class ModelArgument {
        private final String name;
        private final Path path;

        private ModelArgument(String name, Path path) {
            this.name = name;
            this.path = path;
        }

        private static ModelArgument parse(String value) {
            int separator = value.indexOf('=');
            require(separator > 0 && separator < value.length() - 1,
                "model must be name=path");
            String name = value.substring(0, separator);
            require(name.matches("[A-Za-z][A-Za-z0-9_]*"),
                "invalid model name: " + name);
            return new ModelArgument(
                name,
                Paths.get(value.substring(separator + 1))
            );
        }
    }

    private static final class PositionRow {
        private final long parentId;
        private final String split;
        private final int phase;
        private final int ply;
        private final long black;
        private final long white;
        private final int player;
        private final long sampleCount;
        private final long theoreticalCount;

        private PositionRow(
            long parentId,
            String split,
            int phase,
            int ply,
            long black,
            long white,
            int player,
            long sampleCount,
            long theoreticalCount
        ) {
            this.parentId = parentId;
            this.split = split;
            this.phase = phase;
            this.ply = ply;
            this.black = black;
            this.white = white;
            this.player = player;
            this.sampleCount = sampleCount;
            this.theoreticalCount = theoreticalCount;
        }

        private static PositionRow parse(String line) {
            String[] values = line.split("\\t", -1);
            require(values.length == 9, "invalid position row");
            int player = Integer.parseInt(values[6]);
            require(player == 1 || player == -1, "invalid player");
            return new PositionRow(
                Long.parseLong(values[0]),
                values[1],
                Integer.parseInt(values[2]),
                Integer.parseInt(values[3]),
                Long.parseUnsignedLong(values[4], 16),
                Long.parseUnsignedLong(values[5], 16),
                player,
                Long.parseLong(values[7]),
                Long.parseLong(values[8])
            );
        }
    }

    private static final class Settings {
        private final Path input;
        private final Path output;
        private final Path teacherModel;
        private final int shallowDepth;
        private final int deepDepth;
        private final long timeMillis;
        private final List<ModelArgument> models;

        private Settings(
            Path input,
            Path output,
            Path teacherModel,
            int shallowDepth,
            int deepDepth,
            long timeMillis,
            List<ModelArgument> models
        ) {
            this.input = input;
            this.output = output;
            this.teacherModel = teacherModel;
            this.shallowDepth = shallowDepth;
            this.deepDepth = deepDepth;
            this.timeMillis = timeMillis;
            this.models = models;
        }

        private static Settings parse(String[] args) {
            if (args.length < 7) {
                throw new IllegalArgumentException(
                    "Usage: java RankingTeacherGenerator <positions.tsv>"
                        + " <output.tsv> <teacher-model> <shallow-depth>"
                        + " <deep-depth> <time-millis> <name=model>..."
                );
            }
            int shallowDepth = Integer.parseInt(args[3]);
            int deepDepth = Integer.parseInt(args[4]);
            long timeMillis = Long.parseLong(args[5]);
            require(shallowDepth >= 2, "shallow depth must be at least 2");
            require(deepDepth > shallowDepth, "deep depth must be greater");
            require(timeMillis >= 1L, "time must be positive");
            List<ModelArgument> models = new ArrayList<>();
            for (int index = 6; index < args.length; index++) {
                models.add(ModelArgument.parse(args[index]));
            }
            return new Settings(
                Paths.get(args[0]),
                Paths.get(args[1]),
                Paths.get(args[2]),
                shallowDepth,
                deepDepth,
                timeMillis,
                models
            );
        }
    }
}
