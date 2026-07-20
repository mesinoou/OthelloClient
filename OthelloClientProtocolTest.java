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

public final class OthelloClientProtocolTest {

    private OthelloClientProtocolTest() {
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread server = new Thread(
                () -> runServer(serverSocket, serverFailure),
                "test-server"
            );
            server.start();

            OthelloClient client = new OthelloClient(
                "127.0.0.1",
                serverSocket.getLocalPort(),
                "TestAI",
                2,
                100L
            );
            client.start();
            server.join(5000L);

            if (server.isAlive()) {
                throw new AssertionError("テストサーバが終了しませんでした。");
            }
            if (serverFailure.get() != null) {
                throw new AssertionError("通信テストに失敗しました。", serverFailure.get());
            }
        }

        System.out.println("OthelloClientProtocolTest: PASS");
    }

    private static void runServer(
        ServerSocket serverSocket,
        AtomicReference<Throwable> failure
    ) {
        try (Socket socket = serverSocket.accept()) {
            socket.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );
            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                true
            );

            assertLine("NICK TestAI", reader.readLine());

            writer.println("START 1");
            writer.println("TURN 1");
            assertLine("BOARD", reader.readLine());

            String board = initialBoardMessage();
            writer.println(board);
            assertLegalPut(reader.readLine(), initialBoard(), 1);

            // The real server sends the changed BOARD before the next TURN.
            String afterBlackMove = afterBlackMoveMessage();
            writer.println(afterBlackMove);
            assertNoLine(socket, reader);
            writer.println("TURN -1");
            assertNoLine(socket, reader);

            String afterWhiteMove = afterWhiteMoveMessage();
            writer.println(afterWhiteMove);
            assertNoLine(socket, reader);
            writer.println("TURN 1");
            assertLegalPut(reader.readLine(), afterWhiteMoveBoard(), 1);

            // ERROR 2 must not resend the rejected move indefinitely.
            writer.println("ERROR 2");
            assertLine("BOARD", reader.readLine());
            writer.println(afterWhiteMove);
            assertNoLine(socket, reader);

            socket.setSoTimeout(3000);
            writer.println("END TEST");
            assertLine("CLOSE", reader.readLine());
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    private static String initialBoardMessage() {
        return boardMessage(initialBoard());
    }

    private static String afterBlackMoveMessage() {
        int[][] board = initialBoard();
        board[4][2] = 1;
        board[4][3] = 1;
        return boardMessage(board);
    }

    private static String afterWhiteMoveMessage() {
        return boardMessage(afterWhiteMoveBoard());
    }

    private static int[][] afterWhiteMoveBoard() {
        int[][] board = initialBoard();
        board[4][2] = 1;
        board[4][3] = 1;
        board[3][2] = -1;
        board[3][3] = -1;
        return board;
    }

    private static int[][] initialBoard() {
        int[][] board = new int[8][8];
        board[3][3] = 1;
        board[4][4] = 1;
        board[3][4] = -1;
        board[4][3] = -1;
        return board;
    }

    private static String boardMessage(int[][] board) {
        StringBuilder message = new StringBuilder("BOARD");
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                message.append(' ').append(board[x][y]);
            }
        }
        return message.toString();
    }

    private static void assertNoLine(
        Socket socket,
        BufferedReader reader
    ) throws IOException {
        socket.setSoTimeout(250);
        try {
            String unexpected = reader.readLine();
            if (unexpected != null) {
                throw new AssertionError(
                    "TURN前に予期しないメッセージを受信しました: "
                        + unexpected
                );
            }
        } catch (SocketTimeoutException expected) {
            // Silence is the expected result.
        } finally {
            socket.setSoTimeout(3000);
        }
    }

    private static void assertLine(String expected, String actual) throws IOException {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "expected=\"" + expected + "\", actual=\"" + actual + "\""
            );
        }
    }

    private static void assertLegalPut(
        String message,
        int[][] board,
        int color
    ) {
        if (message == null) {
            throw new AssertionError("PUTを受信する前に接続が終了しました。");
        }
        String[] parts = message.trim().split("\\s+");
        if (parts.length != 3 || !"PUT".equals(parts[0])) {
            throw new AssertionError("PUTの書式が不正です: " + message);
        }

        int x;
        int y;
        try {
            x = Integer.parseInt(parts[1]);
            y = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new AssertionError("PUTの座標が数値ではありません: " + message, e);
        }
        if (x < 0 || x >= 8 || y < 0 || y >= 8) {
            throw new AssertionError("PUTの座標が盤外です: " + message);
        }

        long black = 0L;
        long white = 0L;
        for (int boardX = 0; boardX < 8; boardX++) {
            for (int boardY = 0; boardY < 8; boardY++) {
                long bit = 1L << CoordinateConverter.xyToSquare(boardX, boardY);
                if (board[boardX][boardY] == 1) {
                    black |= bit;
                } else if (board[boardX][boardY] == -1) {
                    white |= bit;
                }
            }
        }

        BitBoardPosition position = new BitBoardPosition(black, white);
        long move = 1L << CoordinateConverter.xyToSquare(x, y);
        if (!BitBoard.isLegalMove(
            position.player(color),
            position.opponent(color),
            move
        )) {
            throw new AssertionError("盤面に対して非合法なPUTです: " + message);
        }
    }
}
