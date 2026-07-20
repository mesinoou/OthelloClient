import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class EdaxGtpEngine implements AutoCloseable {

    private final Process process;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final Object commandLock = new Object();
    private final Object writeLock = new Object();
    private int nextCommandId = 1;
    private volatile boolean closed;

    public EdaxGtpEngine(
        Path executable,
        Path evaluationFile,
        int level,
        int threads
    ) throws IOException {
        if (executable == null || !Files.isRegularFile(executable)) {
            throw new IOException("Edax実行ファイルが見つかりません: " + executable);
        }
        if (evaluationFile == null || !Files.isRegularFile(evaluationFile)) {
            throw new IOException(
                "Edax評価データが見つかりません: " + evaluationFile
            );
        }
        if (level < 0 || level > 60) {
            throw new IllegalArgumentException("level must be between 0 and 60");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }

        Path absoluteExecutable = executable.toAbsolutePath().normalize();
        Path absoluteEvaluation = evaluationFile.toAbsolutePath().normalize();
        List<String> command = new ArrayList<>();
        command.add(absoluteExecutable.toString());
        command.add("-gtp");
        command.add("-q");
        command.add("-level");
        command.add(Integer.toString(level));
        command.add("-n-tasks");
        command.add(Integer.toString(threads));
        command.add("-ponder");
        command.add("off");
        command.add("-book-usage");
        command.add("off");
        command.add("-eval-file");
        command.add(absoluteEvaluation.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        Path parent = absoluteExecutable.getParent();
        if (parent != null) {
            builder.directory(parent.toFile());
        }
        process = builder.start();
        reader = new BufferedReader(
            new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
            )
        );
        writer = new PrintWriter(
            new OutputStreamWriter(
                process.getOutputStream(),
                StandardCharsets.UTF_8
            ),
            true
        );
        startErrorDrainer(process);

        try {
            command("protocol_version");
            command("set_game Othello");
            command("boardsize 8");
            reset();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public void reset() throws IOException {
        command("clear_board");
    }

    public void play(int serverColor, int serverSquare) throws IOException {
        checkColor(serverColor);
        String color = serverColor == 1 ? "b" : "w";
        command(
            "play " + color + " "
                + serverSquareToEdaxCoordinate(serverSquare)
        );
    }

    public int generateMove(int serverColor) throws IOException {
        checkColor(serverColor);
        String color = serverColor == 1 ? "b" : "w";
        String response = command("genmove " + color).trim();
        if (response.equalsIgnoreCase("pass")) {
            return -1;
        }
        return edaxCoordinateToServerSquare(response);
    }

    public void stop() {
        if (closed || !process.isAlive()) {
            return;
        }
        writeLine("stop");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (process.isAlive()) {
            writeLine("quit");
        }
        writer.close();
        try {
            if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                process.destroy();
            }
            if (process.isAlive()
                && !process.waitFor(1L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    static String serverSquareToEdaxCoordinate(int serverSquare) {
        checkSquare(serverSquare);
        int serverX = CoordinateConverter.squareToX(serverSquare);
        int y = CoordinateConverter.squareToY(serverSquare);
        int edaxX = 7 - serverX;
        return Character.toString((char) ('a' + edaxX)) + (y + 1);
    }

    static int edaxCoordinateToServerSquare(String coordinate) {
        if (coordinate == null) {
            throw new IllegalArgumentException("coordinate");
        }
        String normalized = coordinate.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() != 2) {
            throw new IllegalArgumentException(
                "Edax着手座標が不正です: " + coordinate
            );
        }
        int edaxX = normalized.charAt(0) - 'a';
        int y = normalized.charAt(1) - '1';
        if (edaxX < 0 || edaxX >= 8 || y < 0 || y >= 8) {
            throw new IllegalArgumentException(
                "Edax着手座標が範囲外です: " + coordinate
            );
        }
        return CoordinateConverter.xyToSquare(7 - edaxX, y);
    }

    private String command(String command) throws IOException {
        synchronized (commandLock) {
            if (closed || !process.isAlive()) {
                throw new IOException("Edaxプロセスが終了しています。");
            }
            int id = nextCommandId++;
            writeLine(id + " " + command);
            return readResponse(id);
        }
    }

    private String readResponse(int expectedId) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            char status = line.charAt(0);
            if (status != '=' && status != '?') {
                continue;
            }

            String body = line.substring(1).trim();
            int separator = body.indexOf(' ');
            String idText = separator < 0 ? body : body.substring(0, separator);
            int responseId;
            try {
                responseId = Integer.parseInt(idText);
            } catch (NumberFormatException e) {
                throw new IOException("Edax GTP応答IDが不正です: " + line, e);
            }
            if (responseId != expectedId) {
                throw new IOException(
                    "Edax GTP応答IDが一致しません: expected="
                        + expectedId + ", actual=" + responseId
                );
            }

            StringBuilder response = new StringBuilder();
            if (separator >= 0) {
                response.append(body.substring(separator + 1).trim());
            }
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (response.length() > 0) {
                    response.append('\n');
                }
                response.append(line);
            }
            if (status == '?') {
                throw new IOException("Edax GTPエラー: " + response);
            }
            return response.toString();
        }
        throw new IOException("Edax GTP応答を受信できませんでした。");
    }

    private void writeLine(String line) {
        synchronized (writeLock) {
            writer.println(line);
        }
    }

    private static void startErrorDrainer(Process process) {
        Thread drainer = new Thread(() -> {
            try (BufferedReader errors = new BufferedReader(
                new InputStreamReader(
                    process.getErrorStream(),
                    StandardCharsets.UTF_8
                )
            )) {
                String line;
                while ((line = errors.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        System.err.println("[Edax] " + line);
                    }
                }
            } catch (IOException ignored) {
                // The stream is expected to close with the Edax process.
            }
        }, "edax-error-reader");
        drainer.setDaemon(true);
        drainer.start();
    }

    private static void checkColor(int color) {
        if (color != 1 && color != -1) {
            throw new IllegalArgumentException("color must be 1 or -1");
        }
    }

    private static void checkSquare(int square) {
        if (square < 0 || square >= 64) {
            throw new IllegalArgumentException("square must be between 0 and 63");
        }
    }
}
