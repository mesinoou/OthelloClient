import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class WthorImporter {

    private static final int HEADER_BYTES = 16;
    private static final int GAME_BYTES = 68;
    private static final int MOVE_BYTES = 60;

    private WthorImporter() {
    }

    public static List<WthorGame> read(Path path) throws IOException {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".zip")) {
            return readZip(path);
        }
        return readBytes(Files.readAllBytes(path), path.toString());
    }

    private static List<WthorGame> readZip(Path path) throws IOException {
        List<WthorGame> games = new ArrayList<>();
        int members = 0;
        try (ZipInputStream input = new ZipInputStream(
            Files.newInputStream(path)
        )) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()
                    || !entry.getName().toLowerCase(Locale.ROOT)
                        .endsWith(".wtb")) {
                    continue;
                }
                members++;
                games.addAll(readBytes(
                    input.readAllBytes(),
                    path + "!" + entry.getName()
                ));
            }
        }
        if (members == 0) {
            throw new IOException("WTHOR ZIP contains no WTB archive: " + path);
        }
        return games;
    }

    private static List<WthorGame> readBytes(byte[] bytes, String source)
        throws IOException {
        if (bytes.length < HEADER_BYTES) {
            throw new IOException("truncated WTHOR header: " + source);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int gameCount = buffer.getInt(4);
        int boardSize = Byte.toUnsignedInt(bytes[12]);
        int gameType = Byte.toUnsignedInt(bytes[13]);
        if (gameCount < 0) {
            throw new IOException("invalid WTHOR game count: " + source);
        }
        if (boardSize != 0 && boardSize != 8) {
            throw new IOException("unsupported WTHOR board size: " + boardSize);
        }
        if (gameType != 0) {
            throw new IOException("WTHOR file is not a game archive: " + source);
        }

        long expectedSize = HEADER_BYTES + (long) gameCount * GAME_BYTES;
        if (bytes.length != expectedSize) {
            throw new IOException(
                "invalid WTHOR file size: expected=" + expectedSize
                    + ", actual=" + bytes.length
            );
        }

        List<WthorGame> games = new ArrayList<>(gameCount);
        for (int gameIndex = 0; gameIndex < gameCount; gameIndex++) {
            int offset = HEADER_BYTES + gameIndex * GAME_BYTES;
            int blackScore = Byte.toUnsignedInt(bytes[offset + 6]);
            if (blackScore > 64) {
                throw new IOException("invalid WTHOR black score: " + blackScore);
            }

            int moveCount = 0;
            while (moveCount < MOVE_BYTES
                && bytes[offset + 8 + moveCount] != 0) {
                moveCount++;
            }
            byte[] moves = new byte[moveCount];
            System.arraycopy(bytes, offset + 8, moves, 0, moveCount);
            games.add(new WthorGame(blackScore, moves));
        }
        return games;
    }

    public static int squareFromMoveCode(int moveCode) {
        int x = moveCode % 10 - 1;
        int y = moveCode / 10 - 1;
        if (x < 0 || x >= 8 || y < 0 || y >= 8) {
            throw new IllegalArgumentException(
                "invalid WTHOR move code: " + moveCode
            );
        }
        return CoordinateConverter.xyToSquare(x, y);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println(
                "使い方: java WthorImporter <WTH_YYYY.WTB-or-ZIP> [...]"
            );
            return;
        }

        for (String argument : args) {
            Path path = Paths.get(argument);
            try {
                List<WthorGame> games = read(path);
                System.out.println(path + ": games=" + games.size());
            } catch (IOException | IllegalArgumentException e) {
                System.err.println(path + ": ERROR " + e.getMessage());
            }
        }
    }
}
