import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

public final class EvaluationMatchRunner {

    private static final long DEFAULT_OPENING_SEED = 20260721L;
    private static final int TABLE_CAPACITY = 1 << 18;
    private static final Path EDAX_EXECUTABLE = Paths.get(
        "benchmark",
        "edax",
        "wEdax-x86-64-v3.exe"
    );
    private static final Path EDAX_EVALUATION = Paths.get(
        "benchmark",
        "edax",
        "data",
        "eval.dat"
    );

    private EvaluationMatchRunner() {
    }

    public static void main(String[] args) {
        try {
            Settings settings = Settings.parse(args);
            run(settings);
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("error: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void run(Settings settings) throws IOException {
        PositionEvaluator learned = LearnedEvaluator.load(settings.modelPath);
        PositionEvaluator modelOpponent = settings.opponent == Opponent.MODEL
            ? LearnedEvaluator.load(settings.opponentModelPath)
            : null;
        List<Opening> openings = generateOpenings(
            settings.pairs,
            settings.openingPlies,
            settings.openingSeed
        );
        MatchStatistics statistics = new MatchStatistics(settings);

        System.out.println("evaluation match");
        System.out.println("model=" + learned.description());
        System.out.println(
            "opponent=" + settings.opponent
                + (settings.opponent == Opponent.EDAX
                    ? ", edaxLevel=" + settings.edaxLevel
                    : settings.opponent == Opponent.MODEL
                        ? ", model=" + modelOpponent.description()
                        : "")
        );
        System.out.println(
            "pairs=" + settings.pairs
                + ", games=" + settings.pairs * 2
                + ", openingPlies=" + settings.openingPlies
                + ", openingSeed=" + settings.openingSeed
        );
        System.out.println(
            "searchTimeMillis=" + settings.timeMillis
                + ", maxDepth=" + settings.maxDepth
                + ", threads=" + settings.threads
                + ", ponderMillis=" + settings.ponderMillis
                + ", multiProbCut="
                + (settings.opponent == Opponent.MODEL
                    ? "off(model-match)"
                    : settings.multiProbCutEnabled)
                + ", openingBook=off"
        );

        int gameNumber = 0;
        for (int openingIndex = 0;
            openingIndex < openings.size();
            openingIndex++) {
            Opening opening = openings.get(openingIndex);
            for (int learnedColor : new int[] {1, -1}) {
                gameNumber++;
                GameRun run = playOneGame(
                    settings,
                    learned,
                    modelOpponent,
                    opening,
                    learnedColor
                );
                statistics.add(run);
                String outcome = run.learnedMargin > 0
                    ? "WIN"
                    : run.learnedMargin < 0 ? "LOSS" : "DRAW";
                System.out.printf(
                    Locale.ROOT,
                    "game=%d/%d opening=%d learned=%s result=%s "
                        + "margin=%+d discs=%d-%d moves=%d elapsed=%.3fs%n",
                    gameNumber,
                    settings.pairs * 2,
                    openingIndex + 1,
                    colorName(learnedColor),
                    outcome,
                    run.learnedMargin,
                    run.blackDiscs,
                    run.whiteDiscs,
                    run.moves,
                    run.elapsedNanos / 1_000_000_000.0
                );
            }
        }
        statistics.print();
    }

    private static GameRun playOneGame(
        Settings settings,
        PositionEvaluator learnedEvaluator,
        PositionEvaluator modelOpponent,
        Opening opening,
        int learnedColor
    ) throws IOException {
        SearchMatchPlayer learned = new SearchMatchPlayer(
            "learned",
            learnedColor,
            learnedEvaluator,
            settings
        );
        MatchPlayer opponent = createOpponent(
            settings,
            -learnedColor,
            modelOpponent,
            opening
        );
        MatchPlayer black = learnedColor == 1 ? learned : opponent;
        MatchPlayer white = learnedColor == -1 ? learned : opponent;
        long started = System.nanoTime();
        try {
            BitBoardPosition position = opening.position;
            int color = opening.nextColor;
            int moves = opening.moves.size();

            while (true) {
                long player = position.player(color);
                long other = position.opponent(color);
                long legalMoves = BitBoard.legalMoves(player, other);
                if (legalMoves == 0L) {
                    if (BitBoard.legalMoves(other, player) == 0L) {
                        break;
                    }
                    color = -color;
                    continue;
                }

                MatchPlayer current = color == 1 ? black : white;
                if (color != learnedColor) {
                    learned.ponder(position, color);
                }
                int square = current.chooseMove(position, color);
                if (square < 0
                    || square >= 64
                    || (legalMoves & (1L << square)) == 0L) {
                    throw new IOException(
                        current.name() + " returned illegal square "
                            + square + " at move " + moves
                    );
                }
                position = apply(position, color, square);
                black.observeMove(color, square);
                white.observeMove(color, square);
                moves++;
                color = -color;
            }

            int blackDiscs = Long.bitCount(position.black());
            int whiteDiscs = Long.bitCount(position.white());
            int blackMargin = blackDiscs - whiteDiscs;
            return new GameRun(
                learnedColor == 1 ? blackMargin : -blackMargin,
                blackDiscs,
                whiteDiscs,
                moves,
                System.nanoTime() - started,
                learned.metrics(),
                opponent.metrics()
            );
        } finally {
            learned.close();
            opponent.close();
        }
    }

    private static MatchPlayer createOpponent(
        Settings settings,
        int assignedColor,
        PositionEvaluator modelOpponent,
        Opening opening
    ) throws IOException {
        if (settings.opponent == Opponent.HANDCRAFTED) {
            return new SearchMatchPlayer(
                "handcrafted",
                assignedColor,
                new Evaluator(),
                settings
            );
        }
        if (settings.opponent == Opponent.MODEL) {
            return new SearchMatchPlayer(
                "opponentModel",
                assignedColor,
                modelOpponent,
                settings
            );
        }
        return new EdaxMatchPlayer(
            assignedColor,
            settings.edaxLevel,
            settings.threads,
            opening
        );
    }

    private static List<Opening> generateOpenings(
        int count,
        int plies,
        long openingSeed
    ) {
        List<Opening> openings = new ArrayList<>(count);
        Set<String> positions = new HashSet<>();
        int attempt = 0;
        while (openings.size() < count) {
            Random random = new Random(
                openingSeed + 0x9e3779b97f4a7c15L * attempt
            );
            attempt++;
            Opening opening = generateOpening(random, plies);
            String key = Long.toUnsignedString(opening.position.black(), 16)
                + ":"
                + Long.toUnsignedString(opening.position.white(), 16)
                + ":"
                + opening.nextColor;
            if (positions.add(key)) {
                openings.add(opening);
            }
        }
        return openings;
    }

    private static Opening generateOpening(Random random, int plies) {
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        List<PlayedMove> moves = new ArrayList<>(plies);
        while (moves.size() < plies) {
            long player = position.player(color);
            long opponent = position.opponent(color);
            long legalMoves = BitBoard.legalMoves(player, opponent);
            if (legalMoves == 0L) {
                if (BitBoard.legalMoves(opponent, player) == 0L) {
                    break;
                }
                color = -color;
                continue;
            }
            int selected = random.nextInt(Long.bitCount(legalMoves));
            long remaining = legalMoves;
            while (selected-- > 0) {
                remaining &= remaining - 1L;
            }
            int square = Long.numberOfTrailingZeros(remaining);
            moves.add(new PlayedMove(color, square));
            position = apply(position, color, square);
            color = -color;
        }
        return new Opening(position, color, moves);
    }

    private static BitBoardPosition apply(
        BitBoardPosition position,
        int color,
        int square
    ) {
        long player = position.player(color);
        long opponent = position.opponent(color);
        long move = 1L << square;
        long flips = BitBoard.flips(player, opponent, move);
        long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
        long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
        return color == 1
            ? new BitBoardPosition(nextPlayer, nextOpponent)
            : new BitBoardPosition(nextOpponent, nextPlayer);
    }

    private static String colorName(int color) {
        return color == 1 ? "black" : "white";
    }

    private enum Opponent {
        HANDCRAFTED,
        MODEL,
        EDAX
    }

    private interface MatchPlayer extends AutoCloseable {
        String name();

        int chooseMove(BitBoardPosition position, int color) throws IOException;

        void observeMove(int color, int square) throws IOException;

        PlayerMetrics metrics();

        @Override
        void close();
    }

    private static final class SearchMatchPlayer implements MatchPlayer {
        private final String name;
        private final int assignedColor;
        private final SearchEngine engine;
        private final SearchLimits limits;
        private final SearchLimits ponderLimits;
        private final PlayerMetrics metrics = new PlayerMetrics(true);

        private SearchMatchPlayer(
            String name,
            int assignedColor,
            PositionEvaluator evaluator,
            Settings settings
        ) {
            this.name = name;
            this.assignedColor = assignedColor;
            TranspositionTable table = new TranspositionTable(TABLE_CAPACITY);
            engine = new SearchEngine(
                evaluator,
                table,
                true,
                0,
                true,
                true,
                true,
                settings.opponent != Opponent.MODEL
                    && settings.multiProbCutEnabled,
                true
            );
            limits = new SearchLimits(
                settings.timeMillis,
                settings.maxDepth,
                settings.threads
            );
            ponderLimits = settings.ponderMillis == 0L
                ? null
                : new SearchLimits(
                    settings.ponderMillis,
                    settings.maxDepth,
                    settings.threads
                );
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int chooseMove(BitBoardPosition position, int color) {
            if (color != assignedColor) {
                throw new IllegalArgumentException("search player color mismatch");
            }
            boolean initialTtHit = engine.hasTransposition(position, color);
            SearchResult result = engine.search(position, color, limits);
            metrics.add(result, initialTtHit);
            return result.bestSquare();
        }

        private void ponder(BitBoardPosition position, int color) {
            if (ponderLimits == null) {
                return;
            }
            SearchResult result = engine.search(position, color, ponderLimits);
            metrics.addPonder(result);
        }

        @Override
        public void observeMove(int color, int square) {
        }

        @Override
        public PlayerMetrics metrics() {
            return metrics;
        }

        @Override
        public void close() {
            engine.shutdown();
        }
    }

    private static final class EdaxMatchPlayer implements MatchPlayer {
        private final int assignedColor;
        private final EdaxGtpEngine engine;
        private final PlayerMetrics metrics = new PlayerMetrics(false);

        private EdaxMatchPlayer(
            int assignedColor,
            int level,
            int threads,
            Opening opening
        ) throws IOException {
            this.assignedColor = assignedColor;
            engine = new EdaxGtpEngine(
                EDAX_EXECUTABLE,
                EDAX_EVALUATION,
                level,
                threads
            );
            for (PlayedMove move : opening.moves) {
                engine.play(move.color, move.square);
            }
        }

        @Override
        public String name() {
            return "edax";
        }

        @Override
        public int chooseMove(BitBoardPosition position, int color)
            throws IOException {
            if (color != assignedColor) {
                throw new IllegalArgumentException("Edax player color mismatch");
            }
            long started = System.nanoTime();
            int square = engine.generateMove(color);
            metrics.addExternal(System.nanoTime() - started);
            return square;
        }

        @Override
        public void observeMove(int color, int square) throws IOException {
            if (color != assignedColor) {
                engine.play(color, square);
            }
        }

        @Override
        public PlayerMetrics metrics() {
            return metrics;
        }

        @Override
        public void close() {
            engine.close();
        }
    }

    private static final class PlayerMetrics {
        private final boolean searchMetrics;
        private long moves;
        private long elapsedNanos;
        private long nodes;
        private long depthSum;
        private long budgetStops;
        private long exactMoves;
        private long wldAttempts;
        private long wldSolutions;
        private long wldNodes;
        private long wldElapsedNanos;
        private long initialTtHits;
        private long ponderMoves;
        private long ponderElapsedNanos;
        private long ponderNodes;
        private long ponderDepthSum;

        private PlayerMetrics(boolean searchMetrics) {
            this.searchMetrics = searchMetrics;
        }

        private void add(SearchResult result, boolean initialTtHit) {
            moves++;
            elapsedNanos += result.elapsedNanos();
            nodes += result.nodes();
            depthSum += result.completedDepth();
            if (result.timedOut()) {
                budgetStops++;
            }
            if (result.exactSolution()) {
                exactMoves++;
            }
            if (result.wldSearch()) {
                wldAttempts++;
                wldNodes += result.wldNodes();
                wldElapsedNanos += result.wldElapsedNanos();
                if (result.wldSolution()) {
                    wldSolutions++;
                }
            }
            if (initialTtHit) {
                initialTtHits++;
            }
        }

        private void addPonder(SearchResult result) {
            ponderMoves++;
            ponderElapsedNanos += result.elapsedNanos();
            ponderNodes += result.nodes();
            ponderDepthSum += result.completedDepth();
        }

        private void addExternal(long elapsed) {
            moves++;
            elapsedNanos += elapsed;
        }

        private void merge(PlayerMetrics other) {
            moves += other.moves;
            elapsedNanos += other.elapsedNanos;
            nodes += other.nodes;
            depthSum += other.depthSum;
            budgetStops += other.budgetStops;
            exactMoves += other.exactMoves;
            wldAttempts += other.wldAttempts;
            wldSolutions += other.wldSolutions;
            wldNodes += other.wldNodes;
            wldElapsedNanos += other.wldElapsedNanos;
            initialTtHits += other.initialTtHits;
            ponderMoves += other.ponderMoves;
            ponderElapsedNanos += other.ponderElapsedNanos;
            ponderNodes += other.ponderNodes;
            ponderDepthSum += other.ponderDepthSum;
        }

        private void print(String label) {
            double averageMillis = moves == 0
                ? 0.0
                : elapsedNanos / 1_000_000.0 / moves;
            System.out.printf(
                Locale.ROOT,
                "%s: moves=%d avgMove=%.3fms",
                label,
                moves,
                averageMillis
            );
            if (searchMetrics) {
                double averageDepth = moves == 0
                    ? 0.0
                    : (double) depthSum / moves;
                double nodesPerSecond = elapsedNanos == 0
                    ? 0.0
                    : nodes * 1_000_000_000.0 / elapsedNanos;
                System.out.printf(
                    Locale.ROOT,
                    " avgDepth=%.2f nodes=%d nodesPerSecond=%.0f "
                        + "budgetStops=%d exactMoves=%d initialTtHits=%d "
                        + "wldAttempts=%d wldSolutions=%d wldNodes=%d "
                        + "wldSeconds=%.3f",
                    averageDepth,
                    nodes,
                    nodesPerSecond,
                    budgetStops,
                    exactMoves,
                    initialTtHits,
                    wldAttempts,
                    wldSolutions,
                    wldNodes,
                    wldElapsedNanos / 1_000_000_000.0
                );
                if (ponderMoves > 0L) {
                    System.out.printf(
                        Locale.ROOT,
                        " ponderMoves=%d ponderAvg=%.3fms "
                            + "ponderAvgDepth=%.2f ponderNodes=%d",
                        ponderMoves,
                        ponderElapsedNanos / 1_000_000.0 / ponderMoves,
                        (double) ponderDepthSum / ponderMoves,
                        ponderNodes
                    );
                }
            }
            System.out.println();
        }
    }

    private static final class MatchStatistics {
        private final Settings settings;
        private final PlayerMetrics learned = new PlayerMetrics(true);
        private final PlayerMetrics opponent;
        private int games;
        private int wins;
        private int draws;
        private int losses;
        private long marginSum;
        private long elapsedNanos;

        private MatchStatistics(Settings settings) {
            this.settings = settings;
            opponent = new PlayerMetrics(
                settings.opponent != Opponent.EDAX
            );
        }

        private void add(GameRun run) {
            games++;
            marginSum += run.learnedMargin;
            elapsedNanos += run.elapsedNanos;
            if (run.learnedMargin > 0) {
                wins++;
            } else if (run.learnedMargin < 0) {
                losses++;
            } else {
                draws++;
            }
            learned.merge(run.learnedMetrics);
            opponent.merge(run.opponentMetrics);
        }

        private void print() {
            double points = wins + draws * 0.5;
            double scoreRate = games == 0 ? 0.0 : points * 100.0 / games;
            double averageMargin = games == 0
                ? 0.0
                : (double) marginSum / games;
            System.out.println("summary");
            System.out.printf(
                Locale.ROOT,
                "games=%d wins=%d draws=%d losses=%d "
                    + "scoreRate=%.2f%% averageMargin=%+.2f elapsed=%.3fs%n",
                games,
                wins,
                draws,
                losses,
                scoreRate,
                averageMargin,
                elapsedNanos / 1_000_000_000.0
            );
            learned.print("learned");
            opponent.print(
                settings.opponent == Opponent.HANDCRAFTED
                    ? "handcrafted"
                    : settings.opponent == Opponent.MODEL
                        ? "opponentModel"
                        : "edax"
            );
        }
    }

    private static final class Settings {
        private final Path modelPath;
        private final Path opponentModelPath;
        private final Opponent opponent;
        private final int pairs;
        private final int openingPlies;
        private final long timeMillis;
        private final int maxDepth;
        private final int threads;
        private final int edaxLevel;
        private final long openingSeed;
        private final long ponderMillis;
        private final boolean multiProbCutEnabled;

        private Settings(
            Path modelPath,
            Path opponentModelPath,
            Opponent opponent,
            int pairs,
            int openingPlies,
            long timeMillis,
            int maxDepth,
            int threads,
            int edaxLevel,
            long openingSeed,
            long ponderMillis,
            boolean multiProbCutEnabled
        ) {
            this.modelPath = modelPath;
            this.opponentModelPath = opponentModelPath;
            this.opponent = opponent;
            this.pairs = pairs;
            this.openingPlies = openingPlies;
            this.timeMillis = timeMillis;
            this.maxDepth = maxDepth;
            this.threads = threads;
            this.edaxLevel = edaxLevel;
            this.openingSeed = openingSeed;
            this.ponderMillis = ponderMillis;
            this.multiProbCutEnabled = multiProbCutEnabled;
        }

        private static Settings parse(String[] args) {
            if (args.length < 2 || args.length > 11) {
                throw new IllegalArgumentException(
                    "Usage: java EvaluationMatchRunner <model> "
                        + "<handcrafted|edax|model=path> [pairs] "
                        + "[openingPlies] "
                        + "[timeMillis] [maxDepth] [threads] [edaxLevel] "
                        + "[openingSeed] [ponderMillis] [multiProbCut]"
                );
            }
            Path modelPath = Paths.get(args[0]);
            Opponent opponent;
            Path opponentModelPath = null;
            if ("handcrafted".equalsIgnoreCase(args[1])) {
                opponent = Opponent.HANDCRAFTED;
            } else if ("edax".equalsIgnoreCase(args[1])) {
                opponent = Opponent.EDAX;
            } else if (args[1].regionMatches(true, 0, "model=", 0, 6)
                && args[1].length() > 6) {
                opponent = Opponent.MODEL;
                opponentModelPath = Paths.get(args[1].substring(6));
            } else {
                throw new IllegalArgumentException(
                    "opponent must be handcrafted, edax, or model=path"
                );
            }
            int pairs = integerArg(args, 2, 10);
            int openingPlies = integerArg(args, 3, 8);
            long timeMillis = longArg(args, 4, 100L);
            int maxDepth = integerArg(args, 5, 64);
            int threads = integerArg(args, 6, 1);
            int edaxLevel = integerArg(args, 7, 4);
            long openingSeed = longArg(args, 8, DEFAULT_OPENING_SEED);
            long ponderMillis = longArg(args, 9, 0L);
            boolean multiProbCutEnabled = booleanArg(args, 10, true);
            if (pairs < 1 || pairs > 1000) {
                throw new IllegalArgumentException("pairs must be 1..1000");
            }
            if (openingPlies < 0 || openingPlies > 40) {
                throw new IllegalArgumentException(
                    "openingPlies must be 0..40"
                );
            }
            if (timeMillis < 1L) {
                throw new IllegalArgumentException(
                    "timeMillis must be positive"
                );
            }
            if (maxDepth < 1 || maxDepth > 64) {
                throw new IllegalArgumentException("maxDepth must be 1..64");
            }
            if (threads < 1) {
                throw new IllegalArgumentException("threads must be positive");
            }
            if (edaxLevel < 0 || edaxLevel > 60) {
                throw new IllegalArgumentException("edaxLevel must be 0..60");
            }
            if (ponderMillis < 0L || ponderMillis > 8000L) {
                throw new IllegalArgumentException(
                    "ponderMillis must be 0..8000"
                );
            }
            return new Settings(
                modelPath,
                opponentModelPath,
                opponent,
                pairs,
                openingPlies,
                timeMillis,
                maxDepth,
                threads,
                edaxLevel,
                openingSeed,
                ponderMillis,
                multiProbCutEnabled
            );
        }

        private static int integerArg(
            String[] args,
            int index,
            int defaultValue
        ) {
            return args.length > index
                ? Integer.parseInt(args[index])
                : defaultValue;
        }

        private static long longArg(
            String[] args,
            int index,
            long defaultValue
        ) {
            return args.length > index
                ? Long.parseLong(args[index])
                : defaultValue;
        }

        private static boolean booleanArg(
            String[] args,
            int index,
            boolean defaultValue
        ) {
            if (args.length <= index) {
                return defaultValue;
            }
            if ("true".equalsIgnoreCase(args[index])) {
                return true;
            }
            if ("false".equalsIgnoreCase(args[index])) {
                return false;
            }
            throw new IllegalArgumentException(
                "boolean argument must be true or false"
            );
        }
    }

    private static final class Opening {
        private final BitBoardPosition position;
        private final int nextColor;
        private final List<PlayedMove> moves;

        private Opening(
            BitBoardPosition position,
            int nextColor,
            List<PlayedMove> moves
        ) {
            this.position = position;
            this.nextColor = nextColor;
            this.moves = moves;
        }
    }

    private static final class PlayedMove {
        private final int color;
        private final int square;

        private PlayedMove(int color, int square) {
            this.color = color;
            this.square = square;
        }
    }

    private static final class GameRun {
        private final int learnedMargin;
        private final int blackDiscs;
        private final int whiteDiscs;
        private final int moves;
        private final long elapsedNanos;
        private final PlayerMetrics learnedMetrics;
        private final PlayerMetrics opponentMetrics;

        private GameRun(
            int learnedMargin,
            int blackDiscs,
            int whiteDiscs,
            int moves,
            long elapsedNanos,
            PlayerMetrics learnedMetrics,
            PlayerMetrics opponentMetrics
        ) {
            this.learnedMargin = learnedMargin;
            this.blackDiscs = blackDiscs;
            this.whiteDiscs = whiteDiscs;
            this.moves = moves;
            this.elapsedNanos = elapsedNanos;
            this.learnedMetrics = learnedMetrics;
            this.opponentMetrics = opponentMetrics;
        }
    }
}
