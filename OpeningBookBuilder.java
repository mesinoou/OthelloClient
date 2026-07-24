import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class OpeningBookBuilder {

    private final int maximumPly;
    private final int minimumMoveGames;
    private final int teacherDepth;
    private final long teacherTimeMillis;
    private final int teacherDivisor;
    private final int teacherScoreBound;
    private final String teacherDescription;
    private final String teacherId;
    private final SearchEngine teacherEngine;
    private final SearchLimits teacherLimits;
    private final Map<PositionKey, PositionStatistics> positions =
        new HashMap<>();
    private final Map<PositionKey, Map<Integer, CachedTeacher>> teacherCache =
        new HashMap<>();

    private int sourceGames;
    private int acceptedGames;
    private int rejectedGames;
    private int reevaluatedPositions;
    private int changedSelections;
    private int incompleteTeacherSearches;
    private int teacherCacheHits;
    private int teacherCacheMisses;
    private int minimumCompletedTeacherDepth = Integer.MAX_VALUE;
    private long teacherNodes;

    public OpeningBookBuilder(
        int maximumPly,
        int minimumMoveGames,
        int teacherDepth
    ) {
        this(
            maximumPly,
            minimumMoveGames,
            teacherDepth,
            1_000L,
            8,
            2_000,
            new Evaluator(),
            1 << 18
        );
    }

    OpeningBookBuilder(
        int maximumPly,
        int minimumMoveGames,
        int teacherDepth,
        long teacherTimeMillis,
        int teacherDivisor,
        int teacherScoreBound,
        PositionEvaluator teacherEvaluator,
        int teacherTableEntries
    ) {
        if (maximumPly < 1 || maximumPly > 40) {
            throw new IllegalArgumentException("maximumPly must be between 1 and 40");
        }
        if (minimumMoveGames < 1) {
            throw new IllegalArgumentException("minimumMoveGames must be positive");
        }
        if (teacherDepth < 2 || teacherDepth > 20) {
            throw new IllegalArgumentException("teacherDepth must be between 2 and 20");
        }
        if (teacherTimeMillis < 1L || teacherTimeMillis > 600_000L) {
            throw new IllegalArgumentException(
                "teacherTimeMillis must be between 1 and 600000"
            );
        }
        if (teacherDivisor < 1) {
            throw new IllegalArgumentException("teacherDivisor must be positive");
        }
        if (teacherScoreBound < 1
            || teacherScoreBound >= Evaluator.WIN_SCORE) {
            throw new IllegalArgumentException("invalid teacherScoreBound");
        }
        if (teacherEvaluator == null) {
            throw new NullPointerException("teacherEvaluator");
        }
        if (Integer.bitCount(teacherTableEntries) != 1
            || teacherTableEntries < (1 << 10)) {
            throw new IllegalArgumentException(
                "teacherTableEntries must be a power of two >= 1024"
            );
        }
        this.maximumPly = maximumPly;
        this.minimumMoveGames = minimumMoveGames;
        this.teacherDepth = teacherDepth;
        this.teacherTimeMillis = teacherTimeMillis;
        this.teacherDivisor = teacherDivisor;
        this.teacherScoreBound = teacherScoreBound;
        this.teacherDescription = teacherEvaluator.description();
        this.teacherId = teacherEvaluator instanceof LearnedEvaluator
            ? ((LearnedEvaluator) teacherEvaluator).modelSha256()
            : teacherDescription;
        this.teacherEngine = new SearchEngine(
            teacherEvaluator,
            new TranspositionTable(teacherTableEntries),
            true,
            0,
            false,
            true,
            true,
            false,
            false
        );
        this.teacherLimits = new SearchLimits(
            teacherTimeMillis,
            teacherDepth - 1,
            1
        );
    }

    public void addArchive(Path path) throws IOException {
        List<WthorGame> games = WthorImporter.read(path);
        sourceGames += games.size();
        for (WthorGame game : games) {
            if (addGame(game)) {
                acceptedGames++;
            } else {
                rejectedGames++;
            }
        }
    }

    public void loadTeacherCache(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(
            path,
            StandardCharsets.UTF_8
        )) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("teacher cache is empty: " + path);
            }
            String[] header = headerLine.split("\t", -1);
            int playerColumn = column(header, "player");
            int opponentColumn = column(header, "opponent");
            int squareColumn = column(header, "square");
            int scoreColumn = column(header, "teacher_score");
            int depthColumn = column(header, "teacher_depth");
            int nodesColumn = column(header, "teacher_nodes");
            int timedOutColumn = column(header, "teacher_timed_out");
            int idColumn = column(header, "teacher_id");
            int targetDepthColumn = column(header, "teacher_target_depth");

            int loaded = 0;
            int ignored = 0;
            int idMismatches = 0;
            int targetDepthMismatches = 0;
            int timedOutEntries = 0;
            int shallowEntries = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (!fields[idColumn].equalsIgnoreCase(teacherId)) {
                    idMismatches++;
                    ignored++;
                    continue;
                }
                if (Integer.parseInt(fields[targetDepthColumn])
                    != teacherDepth) {
                    targetDepthMismatches++;
                    ignored++;
                    continue;
                }
                if (Boolean.parseBoolean(fields[timedOutColumn])) {
                    timedOutEntries++;
                    ignored++;
                    continue;
                }
                if (Integer.parseInt(fields[depthColumn]) < teacherDepth) {
                    shallowEntries++;
                    ignored++;
                    continue;
                }
                PositionKey key = new PositionKey(
                    Long.parseUnsignedLong(fields[playerColumn], 16),
                    Long.parseUnsignedLong(fields[opponentColumn], 16)
                );
                int square = Integer.parseInt(fields[squareColumn]);
                CachedTeacher cached = new CachedTeacher(
                    Integer.parseInt(fields[scoreColumn]),
                    Integer.parseInt(fields[depthColumn]),
                    Long.parseLong(fields[nodesColumn])
                );
                teacherCache.computeIfAbsent(
                    key,
                    ignoredKey -> new HashMap<>()
                ).put(square, cached);
                loaded++;
            }
            System.out.println(
                "teacher cache: loaded=" + loaded
                    + ", ignored=" + ignored
                    + ", idMismatches=" + idMismatches
                    + ", targetDepthMismatches=" + targetDepthMismatches
                    + ", timedOutEntries=" + timedOutEntries
                    + ", shallowEntries=" + shallowEntries
                    + ", expectedTeacherId=" + teacherId
                    + ", path=" + path
            );
        } catch (IllegalArgumentException
            | ArrayIndexOutOfBoundsException error) {
            throw new IOException(
                "invalid teacher cache " + path + ": " + error.getMessage(),
                error
            );
        }
    }

    public void write(Path output) throws IOException {
        write(output, null);
    }

    public void write(Path output, Path auditOutput) throws IOException {
        List<BookEntry> entries = new ArrayList<>();
        List<Map.Entry<PositionKey, PositionStatistics>> orderedPositions =
            new ArrayList<>(positions.entrySet());
        orderedPositions.sort((left, right) -> PositionKey.compare(
            left.getKey().player(),
            left.getKey().opponent(),
            right.getKey().player(),
            right.getKey().opponent()
        ));
        long rankingStarted = System.nanoTime();
        for (int index = 0; index < orderedPositions.size(); index++) {
            Map.Entry<PositionKey, PositionStatistics> position =
                orderedPositions.get(index);
            MoveStatistics best = selectBestMove(
                position.getKey(),
                position.getValue()
            );
            if (best != null) {
                entries.add(new BookEntry(position.getKey(), best));
            }
            int completed = index + 1;
            if (completed % 5_000 == 0
                || completed == orderedPositions.size()) {
                double elapsedSeconds = (
                    System.nanoTime() - rankingStarted
                ) / 1_000_000_000.0;
                System.out.printf(
                    Locale.ROOT,
                    "ranked: %d/%d positions, entries=%d, "
                        + "reevaluated=%d, nodes=%d, elapsed=%.1fs%n",
                    completed,
                    orderedPositions.size(),
                    entries.size(),
                    reevaluatedPositions,
                    teacherNodes,
                    elapsedSeconds
                );
            }
        }

        int count = entries.size();
        long[] players = new long[count];
        long[] opponents = new long[count];
        byte[] bestSquares = new byte[count];
        int[] evaluations = new int[count];
        int[] games = new int[count];
        int[] wins = new int[count];
        int[] draws = new int[count];
        int[] discDifferenceSums = new int[count];

        for (int index = 0; index < count; index++) {
            BookEntry entry = entries.get(index);
            players[index] = entry.key.player();
            opponents[index] = entry.key.opponent();
            bestSquares[index] = (byte) entry.move.square;
            evaluations[index] = entry.move.optimizedQuality(
                teacherDivisor,
                teacherScoreBound
            );
            games[index] = entry.move.games;
            wins[index] = entry.move.wins;
            draws[index] = entry.move.draws;
            discDifferenceSums[index] = entry.move.discDifferenceSum;
        }

        OpeningBook.write(
            output,
            players,
            opponents,
            bestSquares,
            evaluations,
            games,
            wins,
            draws,
            discDifferenceSums,
            acceptedGames,
            maximumPly
        );
        if (auditOutput != null) {
            writeAudit(auditOutput, entries);
        }
        System.out.println(
            "opening book: entries=" + count
                + ", sourceGames=" + sourceGames
                + ", acceptedGames=" + acceptedGames
                + ", rejectedGames=" + rejectedGames
                + ", maximumPly=" + maximumPly
                + ", minimumMoveGames=" + minimumMoveGames
                + ", teacherDepth=" + teacherDepth
                + ", teacherTimeMillis=" + teacherTimeMillis
                + ", teacherDivisor=" + teacherDivisor
                + ", teacherScoreBound=" + teacherScoreBound
                + ", teacher=" + teacherDescription
                + ", reevaluatedPositions=" + reevaluatedPositions
                + ", changedSelections=" + changedSelections
                + ", teacherCacheHits=" + teacherCacheHits
                + ", teacherCacheMisses=" + teacherCacheMisses
                + ", incompleteTeacherSearches=" + incompleteTeacherSearches
                + ", minimumCompletedTeacherDepth="
                + (minimumCompletedTeacherDepth == Integer.MAX_VALUE
                    ? 0
                    : minimumCompletedTeacherDepth)
                + ", teacherNodes=" + teacherNodes
        );
    }

    private MoveStatistics selectBestMove(
        PositionKey key,
        PositionStatistics statistics
    ) {
        int ply = BitBoard.count(key.player() | key.opponent()) - 4;
        int requiredGames = requiredMoveGames(ply, minimumMoveGames);
        List<MoveStatistics> candidates = statistics.candidates(requiredGames);
        if (candidates.isEmpty()) {
            return null;
        }

        MoveStatistics statisticalBest = bestStatisticalCandidate(candidates);
        if (candidates.size() > 1) {
            reevaluatedPositions++;
            for (MoveStatistics candidate : candidates) {
                evaluateCandidate(key, candidate);
            }
        }

        MoveStatistics optimizedBest = bestCandidate(
            candidates,
            teacherDivisor,
            teacherScoreBound
        );
        if (optimizedBest.square != statisticalBest.square) {
            changedSelections++;
        }
        return optimizedBest;
    }

    private void evaluateCandidate(PositionKey key, MoveStatistics candidate) {
        Map<Integer, CachedTeacher> cachedPosition = teacherCache.get(key);
        CachedTeacher cached = cachedPosition == null
            ? null
            : cachedPosition.get(candidate.square);
        if (cached != null) {
            teacherCacheHits++;
            candidate.teacherScore = cached.score;
            candidate.teacherDepth = cached.depth;
            candidate.teacherNodes = cached.nodes;
            minimumCompletedTeacherDepth = Math.min(
                minimumCompletedTeacherDepth,
                candidate.teacherDepth
            );
            return;
        }
        teacherCacheMisses++;
        long move = 1L << candidate.square;
        long flips = BitBoard.flips(key.player(), key.opponent(), move);
        long movedPlayer = BitBoard.applyPlayerBoard(
            key.player(),
            move,
            flips
        );
        long movedOpponent = BitBoard.applyOpponentBoard(
            key.opponent(),
            flips
        );
        BitBoardPosition child = new BitBoardPosition(
            movedOpponent,
            movedPlayer
        );
        SearchResult result = teacherEngine.search(child, 1, teacherLimits);
        teacherNodes += result.nodes();
        candidate.teacherScore = -result.score();
        candidate.teacherDepth = result.completedDepth() + 1;
        candidate.teacherNodes = result.nodes();
        candidate.teacherTimedOut = result.timedOut();
        minimumCompletedTeacherDepth = Math.min(
            minimumCompletedTeacherDepth,
            candidate.teacherDepth
        );
        if (candidate.teacherDepth < teacherDepth) {
            incompleteTeacherSearches++;
        }
    }

    private static MoveStatistics bestStatisticalCandidate(
        List<MoveStatistics> candidates
    ) {
        MoveStatistics best = null;
        for (MoveStatistics candidate : candidates) {
            int quality = candidate.statisticalQuality();
            int bestQuality = best == null
                ? Integer.MIN_VALUE
                : best.statisticalQuality();
            if (best == null
                || quality > bestQuality
                || (quality == bestQuality && candidate.games > best.games)
                || (quality == bestQuality
                    && candidate.games == best.games
                    && candidate.square < best.square)) {
                best = candidate;
            }
        }
        return best;
    }

    private static MoveStatistics bestCandidate(
        List<MoveStatistics> candidates,
        int teacherDivisor,
        int teacherScoreBound
    ) {
        MoveStatistics best = null;
        for (MoveStatistics candidate : candidates) {
            int quality = candidate.optimizedQuality(
                teacherDivisor,
                teacherScoreBound
            );
            int bestQuality = best == null
                ? Integer.MIN_VALUE
                : best.optimizedQuality(teacherDivisor, teacherScoreBound);
            if (best == null
                || quality > bestQuality
                || (quality == bestQuality && candidate.games > best.games)
                || (quality == bestQuality
                    && candidate.games == best.games
                    && candidate.square < best.square)) {
                best = candidate;
            }
        }
        return best;
    }

    static int requiredMoveGames(int ply, int baseMinimum) {
        if (ply < 6) {
            return Math.max(baseMinimum, 64);
        }
        if (ply < 12) {
            return Math.max(baseMinimum, 24);
        }
        return baseMinimum;
    }

    static int confidenceLowerPermille(
        int games,
        int wins,
        int draws
    ) {
        if (games < 1) {
            return 0;
        }
        double probability = (wins + draws * 0.5) / games;
        double z = 1.2815515655446004;
        double zSquared = z * z;
        double denominator = 1.0 + zSquared / games;
        double center = probability + zSquared / (2.0 * games);
        double margin = z * Math.sqrt(
            probability * (1.0 - probability) / games
                + zSquared / (4.0 * games * games)
        );
        double lower = (center - margin) / denominator;
        return (int) Math.round(Math.max(0.0, lower) * 1000.0);
    }

    private boolean addGame(WthorGame game) {
        if (!isValidCompleteGame(game)) {
            return false;
        }
        recordGame(game);
        return true;
    }

    private static boolean isValidCompleteGame(WthorGame game) {
        BitBoardPosition position = wthorInitialPosition();
        int color = 1;

        for (int ply = 0; ply < game.moveCount(); ply++) {
            long player = position.player(color);
            long opponent = position.opponent(color);
            long legalMoves = BitBoard.legalMoves(player, opponent);
            if (legalMoves == 0L) {
                if (BitBoard.legalMoves(opponent, player) == 0L) {
                    return false;
                }
                color = -color;
                player = position.player(color);
                opponent = position.opponent(color);
                legalMoves = BitBoard.legalMoves(player, opponent);
            }

            int square;
            try {
                square = WthorImporter.squareFromMoveCode(
                    game.moveCode(ply)
                );
            } catch (IllegalArgumentException e) {
                return false;
            }
            long move = 1L << square;
            if ((legalMoves & move) == 0L) {
                return false;
            }

            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            position = color == 1
                ? new BitBoardPosition(nextPlayer, nextOpponent)
                : new BitBoardPosition(nextOpponent, nextPlayer);
            color = -color;
        }

        if (BitBoard.legalMoves(position.black(), position.white()) != 0L
            || BitBoard.legalMoves(position.white(), position.black()) != 0L) {
            return false;
        }
        int blackDiscs = BitBoard.count(position.black());
        int whiteDiscs = BitBoard.count(position.white());
        int difference = blackDiscs - whiteDiscs;
        int empties = 64 - blackDiscs - whiteDiscs;
        int filledDifference = difference > 0
            ? difference + empties
            : difference < 0
                ? difference - empties
                : 0;
        return filledDifference == game.blackScore() * 2 - 64;
    }

    private void recordGame(WthorGame game) {
        BitBoardPosition position = wthorInitialPosition();
        int color = 1;
        int blackDiscDifference = game.blackScore() * 2 - 64;

        for (int ply = 0; ply < game.moveCount(); ply++) {
            long player = position.player(color);
            long opponent = position.opponent(color);
            long legalMoves = BitBoard.legalMoves(player, opponent);
            if (legalMoves == 0L) {
                color = -color;
                player = position.player(color);
                opponent = position.opponent(color);
            }

            int square = WthorImporter.squareFromMoveCode(game.moveCode(ply));
            long move = 1L << square;
            if (ply < maximumPly) {
                int discDifference = color == 1
                    ? blackDiscDifference
                    : -blackDiscDifference;
                record(player, opponent, square, discDifference);
            }

            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(
                opponent,
                flips
            );
            position = color == 1
                ? new BitBoardPosition(nextPlayer, nextOpponent)
                : new BitBoardPosition(nextOpponent, nextPlayer);
            color = -color;
        }
    }

    private void record(
        long player,
        long opponent,
        int square,
        int discDifference
    ) {
        CanonicalPosition canonical = PositionCanonicalizer.canonicalize(
            player,
            opponent
        );
        PositionKey key = canonical.key();
        int canonicalSquare = canonical.toCanonicalSquare(square);
        PositionStatistics position = positions.computeIfAbsent(
            key,
            ignored -> new PositionStatistics()
        );
        position.record(canonicalSquare, discDifference);
    }

    private static BitBoardPosition wthorInitialPosition() {
        long black = bit(4, 3) | bit(3, 4);
        long white = bit(3, 3) | bit(4, 4);
        return new BitBoardPosition(black, white);
    }

    private static long bit(int x, int y) {
        return 1L << CoordinateConverter.xyToSquare(x, y);
    }

    private void writeAudit(Path output, List<BookEntry> entries)
        throws IOException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Map<PositionKey, MoveStatistics> selected = new HashMap<>();
        for (BookEntry entry : entries) {
            selected.put(entry.key, entry.move);
        }

        List<Map.Entry<PositionKey, PositionStatistics>> ordered =
            new ArrayList<>(positions.entrySet());
        ordered.sort((left, right) -> PositionKey.compare(
            left.getKey().player(),
            left.getKey().opponent(),
            right.getKey().player(),
            right.getKey().opponent()
        ));

        try (BufferedWriter writer = Files.newBufferedWriter(
            output,
            StandardCharsets.UTF_8
        )) {
            writer.write(
                "player\topponent\tply\tsquare\tselected\tstatistical_best"
                    + "\tgames\twins\tdraws\tdisc_difference_sum"
                    + "\tstatistical_quality\tteacher_score"
                    + "\tteacher_depth\tteacher_nodes\tteacher_timed_out"
                    + "\tteacher_id\tteacher_target_depth\tranking_quality"
            );
            writer.newLine();
            for (Map.Entry<PositionKey, PositionStatistics> position
                : ordered) {
                PositionKey key = position.getKey();
                int ply = BitBoard.count(key.player() | key.opponent()) - 4;
                List<MoveStatistics> candidates = position.getValue()
                    .candidates(requiredMoveGames(ply, minimumMoveGames));
                if (candidates.isEmpty()) {
                    continue;
                }
                candidates.sort(Comparator.comparingInt(move -> move.square));
                MoveStatistics statisticalBest = bestStatisticalCandidate(
                    candidates
                );
                MoveStatistics selectedMove = selected.get(key);
                for (MoveStatistics move : candidates) {
                    writer.write(String.format(
                        Locale.ROOT,
                        "%016x\t%016x\t%d\t%d\t%b\t%b\t%d\t%d\t%d\t%d"
                            + "\t%d\t%d\t%d\t%d\t%b\t%s\t%d\t%d",
                        key.player(),
                        key.opponent(),
                        ply,
                        move.square,
                        move == selectedMove,
                        move == statisticalBest,
                        move.games,
                        move.wins,
                        move.draws,
                        move.discDifferenceSum,
                        move.statisticalQuality(),
                        move.teacherScore,
                        move.teacherDepth,
                        move.teacherNodes,
                        move.teacherTimedOut,
                        teacherId,
                        teacherDepth,
                        move.optimizedQuality(
                            teacherDivisor,
                            teacherScoreBound
                        )
                    ));
                    writer.newLine();
                }
            }
        }
    }

    private static int column(String[] header, String name) {
        for (int index = 0; index < header.length; index++) {
            if (header[index].equals(name)) {
                return index;
            }
        }
        throw new IllegalArgumentException("missing column " + name);
    }

    private static List<Path> collectArchives(List<Path> inputs)
        throws IOException {
        List<Path> archives = new ArrayList<>();
        for (Path path : inputs) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    stream.filter(Files::isRegularFile)
                        .filter(OpeningBookBuilder::isWthorArchive)
                        .forEach(archives::add);
                }
            } else {
                archives.add(path);
            }
        }
        removeObsoleteOmnibusWhenAnnualArchivesExist(archives);
        archives.sort(Comparator.comparing(Path::toString));
        return archives;
    }

    private static boolean isWthorArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".wtb") || name.endsWith(".zip");
    }

    private static void removeObsoleteOmnibusWhenAnnualArchivesExist(
        List<Path> archives
    ) {
        boolean[] annual = new boolean[15];
        for (Path archive : archives) {
            String name = archive.getFileName().toString()
                .toUpperCase(Locale.ROOT);
            for (int year = 2001; year <= 2015; year++) {
                if (name.equals("WTH_" + year + ".ZIP")
                    || name.equals("WTH_" + year + ".WTB")) {
                    annual[year - 2001] = true;
                }
            }
        }
        for (boolean present : annual) {
            if (!present) {
                return;
            }
        }
        archives.removeIf(path -> path.getFileName().toString()
            .equalsIgnoreCase("WTH_2001-2015.ZIP"));
    }

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println(
                "使い方: java OpeningBookBuilder <output.bin> "
                    + "<maximumPly> <minimumMoveGames> <teacherDepth> "
                    + "[--teacher-model <path|handcrafted>] "
                    + "[--teacher-time-ms <ms>] [--teacher-divisor <n>] "
                    + "[--teacher-score-bound <score>] "
                    + "[--teacher-table-entries <power-of-two>] "
                    + "[--teacher-cache <ranking.tsv>] "
                    + "[--audit <output.tsv>] <WTB-ZIP-or-directory> [...]"
            );
            return;
        }

        try {
            BuilderOptions options = BuilderOptions.parse(args);
            List<Path> archives = collectArchives(options.inputs);
            if (archives.isEmpty()) {
                throw new IllegalArgumentException("WTHOR archives were not found");
            }

            PositionEvaluator teacherEvaluator = options.teacherModel == null
                ? new Evaluator()
                : LearnedEvaluator.load(options.teacherModel);
            OpeningBookBuilder builder = new OpeningBookBuilder(
                options.maximumPly,
                options.minimumMoveGames,
                options.teacherDepth,
                options.teacherTimeMillis,
                options.teacherDivisor,
                options.teacherScoreBound,
                teacherEvaluator,
                options.teacherTableEntries
            );
            if (options.teacherCache != null) {
                builder.loadTeacherCache(options.teacherCache);
            }
            for (Path archive : archives) {
                builder.addArchive(archive);
                System.out.println("loaded: " + archive);
            }
            builder.write(options.output, options.auditOutput);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("opening book build failed: " + e.getMessage());
        }
    }

    private static final class BuilderOptions {

        private final Path output;
        private final int maximumPly;
        private final int minimumMoveGames;
        private final int teacherDepth;
        private final List<Path> inputs = new ArrayList<>();
        private long teacherTimeMillis = 1_000L;
        private int teacherDivisor = 8;
        private int teacherScoreBound = 2_000;
        private int teacherTableEntries = 1 << 18;
        private Path teacherModel;
        private Path teacherCache;
        private Path auditOutput;

        private BuilderOptions(
            Path output,
            int maximumPly,
            int minimumMoveGames,
            int teacherDepth
        ) {
            this.output = output;
            this.maximumPly = maximumPly;
            this.minimumMoveGames = minimumMoveGames;
            this.teacherDepth = teacherDepth;
        }

        static BuilderOptions parse(String[] args) {
            BuilderOptions options = new BuilderOptions(
                Paths.get(args[0]),
                Integer.parseInt(args[1]),
                Integer.parseInt(args[2]),
                Integer.parseInt(args[3])
            );
            for (int index = 4; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                    case "--teacher-model":
                        String model = requireValue(args, ++index, argument);
                        if (!model.equalsIgnoreCase("handcrafted")) {
                            options.teacherModel = Paths.get(model);
                        }
                        break;
                    case "--teacher-time-ms":
                        options.teacherTimeMillis = Long.parseLong(
                            requireValue(args, ++index, argument)
                        );
                        break;
                    case "--teacher-divisor":
                        options.teacherDivisor = Integer.parseInt(
                            requireValue(args, ++index, argument)
                        );
                        break;
                    case "--teacher-score-bound":
                        options.teacherScoreBound = Integer.parseInt(
                            requireValue(args, ++index, argument)
                        );
                        break;
                    case "--teacher-table-entries":
                        options.teacherTableEntries = Integer.parseInt(
                            requireValue(args, ++index, argument)
                        );
                        break;
                    case "--audit":
                        options.auditOutput = Paths.get(
                            requireValue(args, ++index, argument)
                        );
                        break;
                    case "--teacher-cache":
                        options.teacherCache = Paths.get(
                            requireValue(args, ++index, argument)
                        );
                        break;
                    default:
                        if (argument.startsWith("--")) {
                            throw new IllegalArgumentException(
                                "unknown option: " + argument
                            );
                        }
                        options.inputs.add(Paths.get(argument));
                        break;
                }
            }
            if (options.inputs.isEmpty()) {
                throw new IllegalArgumentException(
                    "at least one WTHOR input is required"
                );
            }
            return options;
        }

        private static String requireValue(
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
    }

    private static final class PositionStatistics {

        private final Map<Integer, MoveStatistics> moves = new HashMap<>();

        void record(int square, int discDifference) {
            MoveStatistics move = moves.computeIfAbsent(
                square,
                MoveStatistics::new
            );
            move.record(discDifference);
        }

        List<MoveStatistics> candidates(int minimumGames) {
            List<MoveStatistics> candidates = new ArrayList<>();
            for (MoveStatistics move : moves.values()) {
                if (move.games >= minimumGames) {
                    candidates.add(move);
                }
            }
            return candidates;
        }
    }

    private static final class CachedTeacher {

        private final int score;
        private final int depth;
        private final long nodes;

        CachedTeacher(int score, int depth, long nodes) {
            this.score = score;
            this.depth = depth;
            this.nodes = nodes;
        }
    }

    private static final class MoveStatistics {

        private final int square;
        private int games;
        private int wins;
        private int draws;
        private int discDifferenceSum;
        private int teacherScore;
        private int teacherDepth;
        private long teacherNodes;
        private boolean teacherTimedOut;

        MoveStatistics(int square) {
            this.square = square;
        }

        void record(int discDifference) {
            games++;
            discDifferenceSum += discDifference;
            if (discDifference > 0) {
                wins++;
            } else if (discDifference == 0) {
                draws++;
            }
        }

        int statisticalQuality() {
            int confidence = confidenceLowerPermille(
                games,
                wins,
                draws
            );
            int discQuality = discDifferenceSum * 4 / (games + 32);
            int supportQuality = Math.min(
                80,
                10 * (31 - Integer.numberOfLeadingZeros(games))
            );
            return confidence + discQuality + supportQuality;
        }

        int optimizedQuality(int teacherDivisor, int teacherScoreBound) {
            int boundedTeacherScore = Math.max(
                -teacherScoreBound,
                Math.min(teacherScoreBound, teacherScore)
            );
            return statisticalQuality() + boundedTeacherScore / teacherDivisor;
        }
    }

    private static final class BookEntry {

        private final PositionKey key;
        private final MoveStatistics move;

        BookEntry(PositionKey key, MoveStatistics move) {
            this.key = key;
            this.move = move;
        }
    }
}
