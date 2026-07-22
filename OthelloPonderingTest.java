import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class OthelloPonderingTest {

    private static final long[] WAIT_MILLIS = {0L, 50L, 500L, 2000L, 8000L};

    private OthelloPonderingTest() {
    }

    public static void main(String[] args) throws Exception {
        long starts = 0L;
        long initialTtHits = 0L;
        long worstStopP95 = 0L;

        for (long waitMillis : WAIT_MILLIS) {
            PonderMetrics.Snapshot metrics = runCase(waitMillis);
            if (metrics.erroneousPuts() != 0L) {
                throw new AssertionError("ponder PUT was recorded");
            }
            if (metrics.ownSearches() != 2L) {
                throw new AssertionError(
                    "authoritative search count: " + metrics.ownSearches()
                );
            }
            starts += metrics.starts();
            initialTtHits += metrics.ownInitialTtHits();
            worstStopP95 = Math.max(
                worstStopP95,
                metrics.stopLatencyP95Nanos()
            );
        }

        if (starts < WAIT_MILLIS.length - 1L) {
            throw new AssertionError("ponder did not start reliably: " + starts);
        }
        if (initialTtHits == 0L) {
            throw new AssertionError("ponder never warmed an own-turn root");
        }
        if (worstStopP95 >= 50_000_000L) {
            throw new AssertionError(
                "ponder stop p95 exceeded 50 ms: "
                    + worstStopP95 / 1_000_000.0
            );
        }

        System.out.println(
            "OthelloPonderingTest: PASS (starts=" + starts
                + ", ownInitialTtHits=" + initialTtHits
                + ", stopP95Millis=" + worstStopP95 / 1_000_000.0
                + ")"
        );
    }

    private static PonderMetrics.Snapshot runCase(long waitMillis)
        throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread server = new Thread(
                () -> runServer(serverSocket, waitMillis, serverFailure),
                "ponder-test-server-" + waitMillis
            );
            server.start();

            OthelloAI ai = OthelloAI.create(
                LearnedEvaluator.loadDefault(),
                1 << 18
            );
            OthelloClient client = new OthelloClient(
                "127.0.0.1",
                serverSocket.getLocalPort(),
                "PonderTest",
                2,
                100L,
                ai,
                true,
                0.80
            );
            client.start();
            server.join(waitMillis + 5000L);

            if (server.isAlive()) {
                throw new AssertionError(
                    "ponder test server did not stop for wait=" + waitMillis
                );
            }
            if (serverFailure.get() != null) {
                throw new AssertionError(
                    "ponder protocol failed for wait=" + waitMillis,
                    serverFailure.get()
                );
            }
            return client.ponderMetricsSnapshot();
        }
    }

    private static void runServer(
        ServerSocket serverSocket,
        long waitMillis,
        AtomicReference<Throwable> failure
    ) {
        try (Socket socket = serverSocket.accept()) {
            socket.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    socket.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );
            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(
                    socket.getOutputStream(),
                    StandardCharsets.UTF_8
                ),
                true
            );

            assertLine("NICK PonderTest", reader.readLine());
            BitBoardPosition initial = BitBoardPosition.initial();
            writer.println("START 1");
            writer.println(boardMessage(initial));
            writer.println("TURN 1");

            int blackSquare = parseLegalPut(reader.readLine(), initial, 1);
            BitBoardPosition afterBlack = applyMove(initial, 1, blackSquare);

            // TURN arrives before the board containing our own PUT.
            writer.println("TURN -1");
            assertLine("BOARD", reader.readLine());
            writer.println(boardMessage(afterBlack));

            assertNoLineFor(socket, reader, waitMillis);

            int whiteSquare = Long.numberOfTrailingZeros(
                BitBoard.legalMoves(
                    afterBlack.player(-1),
                    afterBlack.opponent(-1)
                )
            );
            BitBoardPosition afterWhite = applyMove(
                afterBlack,
                -1,
                whiteSquare
            );
            writer.println(boardMessage(afterWhite));
            writer.println("TURN 1");
            parseLegalPut(reader.readLine(), afterWhite, 1);

            writer.println("END PONDER_TEST");
            assertLine("CLOSE", reader.readLine());
        } catch (Throwable error) {
            failure.set(error);
        }
    }

    private static void assertNoLineFor(
        Socket socket,
        BufferedReader reader,
        long waitMillis
    ) throws IOException {
        if (waitMillis <= 0L) {
            return;
        }
        socket.setSoTimeout((int) waitMillis);
        try {
            String unexpected = reader.readLine();
            if (unexpected != null) {
                throw new AssertionError(
                    "message during opponent turn: " + unexpected
                );
            }
        } catch (SocketTimeoutException expected) {
            // Pondering has no network authority.
        } finally {
            socket.setSoTimeout(3000);
        }
    }

    private static int parseLegalPut(
        String message,
        BitBoardPosition position,
        int color
    ) {
        if (message == null) {
            throw new AssertionError("connection ended before PUT");
        }
        String[] parts = message.trim().split("\\s+");
        if (parts.length != 3 || !"PUT".equals(parts[0])) {
            throw new AssertionError("invalid PUT: " + message);
        }
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int square = CoordinateConverter.xyToSquare(x, y);
        long move = 1L << square;
        if (!BitBoard.isLegalMove(
            position.player(color),
            position.opponent(color),
            move
        )) {
            throw new AssertionError("illegal PUT: " + message);
        }
        return square;
    }

    private static BitBoardPosition applyMove(
        BitBoardPosition position,
        int color,
        int square
    ) {
        long move = 1L << square;
        long player = position.player(color);
        long opponent = position.opponent(color);
        long flips = BitBoard.flips(player, opponent, move);
        if (flips == 0L) {
            throw new AssertionError("test attempted an illegal move");
        }
        long movedPlayer = BitBoard.applyPlayerBoard(player, move, flips);
        long movedOpponent = BitBoard.applyOpponentBoard(opponent, flips);
        return color == 1
            ? new BitBoardPosition(movedPlayer, movedOpponent)
            : new BitBoardPosition(movedOpponent, movedPlayer);
    }

    private static String boardMessage(BitBoardPosition position) {
        StringBuilder message = new StringBuilder("BOARD");
        for (int serverIndex = 0; serverIndex < 64; serverIndex++) {
            int square = CoordinateConverter.serverIndexToSquare(serverIndex);
            long bit = 1L << square;
            int value = (position.black() & bit) != 0L
                ? 1
                : (position.white() & bit) != 0L ? -1 : 0;
            message.append(' ').append(value);
        }
        return message.toString();
    }

    private static void assertLine(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "expected=\"" + expected + "\", actual=\"" + actual + "\""
            );
        }
    }
}
