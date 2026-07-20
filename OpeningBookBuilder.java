import java.io.IOException;
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
    private final SearchEngine teacherEngine;
    private final SearchLimits teacherLimits;
    private final Map<PositionKey, PositionStatistics> positions =
        new HashMap<>();

    private int sourceGames;
    private int acceptedGames;
    private int rejectedGames;
    private int reevaluatedPositions;
    private int changedSelections;
    private long teacherNodes;

    public OpeningBookBuilder(
        int maximumPly,
        int minimumMoveGames,
        int teacherDepth
    ) {
        if (maximumPly < 1 || maximumPly > 40) {
            throw new IllegalArgumentException("maximumPly must be between 1 and 40");
        }
        if (minimumMoveGames < 1) {
            throw new IllegalArgumentException("minimumMoveGames must be positive");
        }
        if (teacherDepth < 2 || teacherDepth > 8) {
            throw new IllegalArgumentException("teacherDepth must be between 2 and 8");
        }
        this.maximumPly = maximumPly;
        this.minimumMoveGames = minimumMoveGames;
        this.teacherDepth = teacherDepth;
        this.teacherEngine = new SearchEngine(
            new Evaluator(),
            new TranspositionTable(1 << 18)
        );
        this.teacherLimits = new SearchLimits(
            1_000L,
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

    public void write(Path output) throws IOException {
        List<BookEntry> entries = new ArrayList<>();
        for (Map.Entry<PositionKey, PositionStatistics> position
            : positions.entrySet()) {
            MoveStatistics best = selectBestMove(
                position.getKey(),
                position.getValue()
            );
            if (best != null) {
                entries.add(new BookEntry(position.getKey(), best));
            }
        }
        entries.sort((left, right) -> PositionKey.compare(
            left.key.player(),
            left.key.opponent(),
            right.key.player(),
            right.key.opponent()
        ));

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
            evaluations[index] = entry.move.optimizedQuality();
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
        System.out.println(
            "opening book: entries=" + count
                + ", sourceGames=" + sourceGames
                + ", acceptedGames=" + acceptedGames
                + ", rejectedGames=" + rejectedGames
                + ", maximumPly=" + maximumPly
                + ", minimumMoveGames=" + minimumMoveGames
                + ", teacherDepth=" + teacherDepth
                + ", reevaluatedPositions=" + reevaluatedPositions
                + ", changedSelections=" + changedSelections
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

        MoveStatistics statisticalBest = bestCandidate(candidates, false);
        if (candidates.size() > 1) {
            reevaluatedPositions++;
            for (MoveStatistics candidate : candidates) {
                candidate.teacherScore = evaluateCandidate(key, candidate.square);
            }
        }

        MoveStatistics optimizedBest = bestCandidate(candidates, true);
        if (optimizedBest.square != statisticalBest.square) {
            changedSelections++;
        }
        return optimizedBest;
    }

    private int evaluateCandidate(PositionKey key, int square) {
        long move = 1L << square;
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
        return -result.score();
    }

    private static MoveStatistics bestCandidate(
        List<MoveStatistics> candidates,
        boolean optimized
    ) {
        MoveStatistics best = null;
        for (MoveStatistics candidate : candidates) {
            int quality = optimized
                ? candidate.optimizedQuality()
                : candidate.statisticalQuality();
            int bestQuality = best == null
                ? Integer.MIN_VALUE
                : optimized
                    ? best.optimizedQuality()
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
        BitBoardPosition position = wthorInitialPosition();
        int color = 1;
        int blackDiscDifference = game.blackScore() * 2 - 64;

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

            if (ply < maximumPly) {
                int discDifference = color == 1
                    ? blackDiscDifference
                    : -blackDiscDifference;
                record(player, opponent, square, discDifference);
            }

            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            position = color == 1
                ? new BitBoardPosition(nextPlayer, nextOpponent)
                : new BitBoardPosition(nextOpponent, nextPlayer);
            color = -color;
        }
        return true;
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

    private static List<Path> collectArchives(String[] args, int start)
        throws IOException {
        List<Path> archives = new ArrayList<>();
        for (int index = start; index < args.length; index++) {
            Path path = Paths.get(args[index]);
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
        archives.sort(Comparator.comparing(Path::toString));
        return archives;
    }

    private static boolean isWthorArchive(Path path) {
        return path.getFileName().toString()
            .toLowerCase(Locale.ROOT)
            .endsWith(".wtb");
    }

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println(
                "使い方: java OpeningBookBuilder <output.bin> "
                    + "<maximumPly> <minimumMoveGames> <teacherDepth> "
                    + "<WTB-or-directory> [...]"
            );
            return;
        }

        try {
            Path output = Paths.get(args[0]);
            int maximumPly = Integer.parseInt(args[1]);
            int minimumMoveGames = Integer.parseInt(args[2]);
            int teacherDepth = Integer.parseInt(args[3]);
            List<Path> archives = collectArchives(args, 4);
            if (archives.isEmpty()) {
                throw new IllegalArgumentException("WTHOR archives were not found");
            }

            OpeningBookBuilder builder = new OpeningBookBuilder(
                maximumPly,
                minimumMoveGames,
                teacherDepth
            );
            for (Path archive : archives) {
                builder.addArchive(archive);
                System.out.println("loaded: " + archive);
            }
            builder.write(output);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("opening book build failed: " + e.getMessage());
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

    private static final class MoveStatistics {

        private final int square;
        private int games;
        private int wins;
        private int draws;
        private int discDifferenceSum;
        private int teacherScore;

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

        int optimizedQuality() {
            int boundedTeacherScore = Math.max(
                -2000,
                Math.min(2000, teacherScore)
            );
            return statisticalQuality() + boundedTeacherScore / 8;
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
