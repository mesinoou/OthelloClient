import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class OpeningBook {

    static final int MAGIC = 0x4f54424b;
    static final int VERSION = 1;
    static final int HEADER_BYTES = 24;
    static final int ENTRY_BYTES = 40;

    private static final int MAX_ENTRIES = 1_000_000;

    private final long[] players;
    private final long[] opponents;
    private final byte[] bestSquares;
    private final int[] evaluations;
    private final int[] games;
    private final int[] wins;
    private final int[] draws;
    private final int[] discDifferenceSums;
    private final int sourceGames;
    private final int maximumPly;

    private OpeningBook(
        long[] players,
        long[] opponents,
        byte[] bestSquares,
        int[] evaluations,
        int[] games,
        int[] wins,
        int[] draws,
        int[] discDifferenceSums,
        int sourceGames,
        int maximumPly
    ) {
        this.players = players;
        this.opponents = opponents;
        this.bestSquares = bestSquares;
        this.evaluations = evaluations;
        this.games = games;
        this.wins = wins;
        this.draws = draws;
        this.discDifferenceSums = discDifferenceSums;
        this.sourceGames = sourceGames;
        this.maximumPly = maximumPly;
    }

    public static OpeningBook empty() {
        return new OpeningBook(
            new long[0],
            new long[0],
            new byte[0],
            new int[0],
            new int[0],
            new int[0],
            new int[0],
            new int[0],
            0,
            0
        );
    }

    public static OpeningBook loadDefault() {
        Path path = Paths.get("data", "opening-book.bin");
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        try {
            return load(path);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(
                "定石データを読み込めないため通常探索を使用します: "
                    + e.getMessage()
            );
            return empty();
        }
    }

    public static OpeningBook load(Path path) throws IOException {
        long fileSize = Files.size(path);
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(path))
        )) {
            if (input.readInt() != MAGIC) {
                throw new IOException("invalid opening book magic");
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw new IOException(
                    "unsupported opening book version: " + version
                );
            }

            int entryCount = input.readInt();
            int sourceGames = input.readInt();
            int maximumPly = input.readInt();
            int reserved = input.readInt();
            if (entryCount < 0 || entryCount > MAX_ENTRIES) {
                throw new IOException("invalid opening book entry count");
            }
            if (sourceGames < 0 || maximumPly < 0 || maximumPly > 60) {
                throw new IOException("invalid opening book metadata");
            }
            if (reserved != 0) {
                throw new IOException("invalid opening book header");
            }
            long expectedSize = HEADER_BYTES + (long) entryCount * ENTRY_BYTES;
            if (fileSize != expectedSize) {
                throw new IOException("invalid opening book file size");
            }

            long[] players = new long[entryCount];
            long[] opponents = new long[entryCount];
            byte[] bestSquares = new byte[entryCount];
            int[] evaluations = new int[entryCount];
            int[] games = new int[entryCount];
            int[] wins = new int[entryCount];
            int[] draws = new int[entryCount];
            int[] discDifferenceSums = new int[entryCount];

            for (int index = 0; index < entryCount; index++) {
                players[index] = input.readLong();
                opponents[index] = input.readLong();
                int square = input.readUnsignedByte();
                if (input.readUnsignedByte() != 0
                    || input.readUnsignedByte() != 0
                    || input.readUnsignedByte() != 0) {
                    throw new IOException("invalid opening book entry padding");
                }
                bestSquares[index] = (byte) square;
                evaluations[index] = input.readInt();
                games[index] = input.readInt();
                wins[index] = input.readInt();
                draws[index] = input.readInt();
                discDifferenceSums[index] = input.readInt();

                validateEntry(
                    players,
                    opponents,
                    bestSquares,
                    games,
                    wins,
                    draws,
                    index
                );
            }

            if (input.read() != -1) {
                throw new IOException("unexpected trailing opening book data");
            }
            return new OpeningBook(
                players,
                opponents,
                bestSquares,
                evaluations,
                games,
                wins,
                draws,
                discDifferenceSums,
                sourceGames,
                maximumPly
            );
        } catch (EOFException e) {
            throw new IOException("truncated opening book", e);
        }
    }

    static void write(
        Path path,
        long[] players,
        long[] opponents,
        byte[] bestSquares,
        int[] evaluations,
        int[] games,
        int[] wins,
        int[] draws,
        int[] discDifferenceSums,
        int sourceGames,
        int maximumPly
    ) throws IOException {
        int count = players.length;
        if (opponents.length != count
            || bestSquares.length != count
            || evaluations.length != count
            || games.length != count
            || wins.length != count
            || draws.length != count
            || discDifferenceSums.length != count) {
            throw new IllegalArgumentException("opening book array size mismatch");
        }

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (DataOutputStream output = new DataOutputStream(
            new BufferedOutputStream(Files.newOutputStream(path))
        )) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(count);
            output.writeInt(sourceGames);
            output.writeInt(maximumPly);
            output.writeInt(0);

            for (int index = 0; index < count; index++) {
                output.writeLong(players[index]);
                output.writeLong(opponents[index]);
                output.writeByte(bestSquares[index]);
                output.writeByte(0);
                output.writeByte(0);
                output.writeByte(0);
                output.writeInt(evaluations[index]);
                output.writeInt(games[index]);
                output.writeInt(wins[index]);
                output.writeInt(draws[index]);
                output.writeInt(discDifferenceSums[index]);
            }
        }
    }

    public OpeningBookMove find(BitBoardPosition position, int color) {
        return find(position.player(color), position.opponent(color));
    }

    public OpeningBookMove find(long player, long opponent) {
        CanonicalPosition canonical = PositionCanonicalizer.canonicalize(
            player,
            opponent
        );
        PositionKey key = canonical.key();
        int index = findIndex(key.player(), key.opponent());
        if (index < 0) {
            return null;
        }

        int canonicalSquare = bestSquares[index];
        int square = canonical.fromCanonicalSquare(canonicalSquare);
        long move = 1L << square;
        if (!BitBoard.isLegalMove(player, opponent, move)) {
            return null;
        }
        return new OpeningBookMove(
            square,
            evaluations[index],
            games[index],
            wins[index],
            draws[index],
            discDifferenceSums[index]
        );
    }

    public int size() {
        return players.length;
    }

    public int sourceGames() {
        return sourceGames;
    }

    public int maximumPly() {
        return maximumPly;
    }

    private int findIndex(long player, long opponent) {
        int low = 0;
        int high = players.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = PositionKey.compare(
                players[middle],
                opponents[middle],
                player,
                opponent
            );
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -1;
    }

    private static void validateEntry(
        long[] players,
        long[] opponents,
        byte[] bestSquares,
        int[] games,
        int[] wins,
        int[] draws,
        int index
    ) throws IOException {
        if ((players[index] & opponents[index]) != 0L) {
            throw new IOException("overlapping opening book position");
        }
        int square = bestSquares[index];
        if (square < 0 || square >= 64) {
            throw new IOException("invalid opening book move");
        }
        if (games[index] < 1
            || wins[index] < 0
            || draws[index] < 0
            || wins[index] + draws[index] > games[index]) {
            throw new IOException("invalid opening book statistics");
        }
        if (!BitBoard.isLegalMove(
            players[index],
            opponents[index],
            1L << square
        )) {
            throw new IOException("non-legal opening book move");
        }
        if (index > 0 && PositionKey.compare(
            players[index - 1],
            opponents[index - 1],
            players[index],
            opponents[index]
        ) >= 0) {
            throw new IOException("opening book entries are not sorted");
        }
    }
}
