import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EdaxOthelloClient {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 25033;
    private static final String DEFAULT_NICKNAME = "Edax46L6";
    private static final int DEFAULT_LEVEL = 6;
    private static final int DEFAULT_THREADS = 1;
    private static final Path DEFAULT_EXECUTABLE = Paths.get(
        "benchmark",
        "edax",
        "wEdax-x86-64-v3.exe"
    );
    private static final Path DEFAULT_EVALUATION = Paths.get(
        "benchmark",
        "edax",
        "data",
        "eval.dat"
    );

    private final String host;
    private final int port;
    private final String nickname;
    private final EdaxGtpEngine engine;
    private final Object stateLock = new Object();
    private final ExecutorService searchController;
    private final List<ObservedMove> gameMoves = new ArrayList<>();
    private final AtomicBoolean engineThinking = new AtomicBoolean();

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private BitBoardPosition position = new BitBoardPosition(0L, 0L);
    private BitBoardPosition enginePosition = BitBoardPosition.initial();
    private ObservedMove pendingGeneratedMove;
    private int engineMoveCount;
    private int myColor;
    private int currentTurn;
    private int boardRevision;
    private int actedBoardRevision = -1;
    private long searchGeneration;
    private boolean hasBoard;
    private boolean engineSynchronized = true;
    private Future<?> activeSearch;
    private volatile boolean running = true;

    public EdaxOthelloClient(
        String host,
        int port,
        String nickname,
        Path executable,
        Path evaluationFile,
        int level,
        int threads
    ) throws IOException {
        this.host = host;
        this.port = port;
        this.nickname = nickname;
        this.engine = new EdaxGtpEngine(
            executable,
            evaluationFile,
            level,
            threads
        );
        this.searchController = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "edax-search-control");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        try {
            connect();
            System.out.println("Edax評価クライアントを接続しました: " + host + ":" + port);
            send("NICK " + nickname);
            receiveLoop();
        } catch (IOException e) {
            if (running) {
                System.err.println("Edax評価クライアントでエラーが発生しました。");
                System.err.println(e.getMessage());
            }
        } finally {
            close();
        }
    }

    private void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5_000);
        reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
        );
        writer = new PrintWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
            true
        );
    }

    private void receiveLoop() throws IOException {
        String message;
        while (running && (message = reader.readLine()) != null) {
            handleMessage(message.trim());
        }
    }

    private void handleMessage(String message) throws IOException {
        if (message.startsWith("START ")) {
            handleStart(message);
        } else if (message.startsWith("BOARD ")) {
            handleBoard(message);
        } else if (message.startsWith("TURN ")) {
            handleTurn(message);
        } else if (message.startsWith("END")) {
            System.out.println(message);
            stopSearch();
            running = false;
        } else if (message.equals("CLOSE")) {
            stopSearch();
            running = false;
        } else if (message.startsWith("ERROR")) {
            System.err.println(message);
            rollbackPendingMove();
        } else if (message.startsWith("SAY ")) {
            System.out.println(message);
        }
    }

    private void handleStart(String message) throws IOException {
        int color = parseColor(message, "START");
        stopSearch();
        engine.reset();
        synchronized (stateLock) {
            myColor = color;
            currentTurn = 0;
            position = new BitBoardPosition(0L, 0L);
            enginePosition = BitBoardPosition.initial();
            gameMoves.clear();
            pendingGeneratedMove = null;
            engineMoveCount = 0;
            boardRevision = 0;
            actedBoardRevision = -1;
            hasBoard = false;
            engineSynchronized = true;
        }
        System.out.println("Edaxの担当色: " + colorName(color));
    }

    private void handleBoard(String message) throws IOException {
        String[] parts = message.split("\\s+");
        if (parts.length != 65) {
            throw new IOException("BOARDの要素数が不正です: " + parts.length);
        }

        long black = 0L;
        long white = 0L;
        for (int serverIndex = 0; serverIndex < 64; serverIndex++) {
            int value;
            try {
                value = Integer.parseInt(parts[serverIndex + 1]);
            } catch (NumberFormatException e) {
                throw new IOException("BOARDの値を解析できません。", e);
            }
            int square = CoordinateConverter.serverIndexToSquare(serverIndex);
            if (value == 1) {
                black |= 1L << square;
            } else if (value == -1) {
                white |= 1L << square;
            } else if (value != 0) {
                throw new IOException("BOARDに不正な石が含まれています: " + value);
            }
        }

        BitBoardPosition received = new BitBoardPosition(black, white);
        synchronized (stateLock) {
            if (hasBoard && !received.equals(position)) {
                recordObservedMove(position, received);
            }
            if (!received.equals(position)) {
                position = received;
                boardRevision++;
                actedBoardRevision = -1;
                currentTurn = 0;
            }
            hasBoard = true;
        }
    }

    private void handleTurn(String message) throws IOException {
        int color = parseColor(message, "TURN");
        synchronized (stateLock) {
            currentTurn = color;
        }
        if (color == myColor) {
            startSearchIfReady();
        } else {
            stopSearch();
        }
    }

    private void startSearchIfReady() {
        synchronized (stateLock) {
            if (!running
                || myColor == 0
                || currentTurn != myColor
                || !hasBoard
                || actedBoardRevision == boardRevision) {
                return;
            }
            final BitBoardPosition snapshot = position;
            final int color = myColor;
            final int revision = boardRevision;
            final long generation = searchGeneration;
            activeSearch = searchController.submit(
                () -> chooseAndSendMove(snapshot, color, revision, generation)
            );
        }
    }

    private void chooseAndSendMove(
        BitBoardPosition snapshot,
        int color,
        int revision,
        long generation
    ) {
        int square;
        try {
            synchronizeEngine(snapshot);
            synchronized (stateLock) {
                if (Thread.currentThread().isInterrupted()
                    || !running
                    || generation != searchGeneration
                    || revision != boardRevision
                    || currentTurn != myColor
                    || color != myColor) {
                    return;
                }
                engineThinking.set(true);
            }
            try {
                square = engine.generateMove(color);
            } finally {
                engineThinking.set(false);
            }
        } catch (IOException | IllegalArgumentException e) {
            if (running) {
                System.err.println("Edaxの着手取得に失敗しました: " + e.getMessage());
            }
            return;
        }

        synchronized (stateLock) {
            if (Thread.currentThread().isInterrupted()
                || !running
                || generation != searchGeneration
                || revision != boardRevision
                || currentTurn != myColor
                || color != myColor
                || actedBoardRevision == boardRevision) {
                engineSynchronized = false;
                return;
            }

            if (square < 0) {
                actedBoardRevision = boardRevision;
                System.out.println("Edax: pass");
                return;
            }
            long move = 1L << square;
            if (!BitBoard.isLegalMove(
                position.player(color),
                position.opponent(color),
                move
            )) {
                engineSynchronized = false;
                System.err.println("Edaxが非合法手を返しました: square=" + square);
                return;
            }

            ObservedMove generated = new ObservedMove(color, square);
            gameMoves.add(generated);
            pendingGeneratedMove = generated;
            engineMoveCount++;
            enginePosition = applyMove(enginePosition, color, square);
            actedBoardRevision = boardRevision;

            int x = CoordinateConverter.squareToX(square);
            int y = CoordinateConverter.squareToY(square);
            System.out.println("Edaxの着手: PUT " + x + " " + y);
            send("PUT " + x + " " + y);
        }
    }

    private void synchronizeEngine(BitBoardPosition authoritative)
        throws IOException {
        List<ObservedMove> history;
        boolean canContinue;
        int appliedMoves;
        BitBoardPosition tracked;
        synchronized (stateLock) {
            history = new ArrayList<>(gameMoves);
            canContinue = engineSynchronized;
            appliedMoves = engineMoveCount;
            tracked = enginePosition;
        }

        if (!canContinue || appliedMoves > history.size()) {
            engine.reset();
            tracked = BitBoardPosition.initial();
            appliedMoves = 0;
        }
        for (int index = appliedMoves; index < history.size(); index++) {
            ObservedMove move = history.get(index);
            engine.play(move.color, move.square);
            tracked = applyMove(tracked, move.color, move.square);
        }
        if (!tracked.equals(authoritative)) {
            engine.reset();
            tracked = BitBoardPosition.initial();
            for (ObservedMove move : history) {
                engine.play(move.color, move.square);
                tracked = applyMove(tracked, move.color, move.square);
            }
        }
        if (!tracked.equals(authoritative)) {
            throw new IOException("サーバー盤面とEdax棋譜を同期できません。");
        }

        synchronized (stateLock) {
            enginePosition = tracked;
            engineMoveCount = history.size();
            engineSynchronized = true;
        }
    }

    private void recordObservedMove(
        BitBoardPosition previous,
        BitBoardPosition current
    ) {
        long added = (current.black() | current.white())
            & ~(previous.black() | previous.white());
        if (Long.bitCount(added) != 1) {
            engineSynchronized = false;
            return;
        }
        int square = Long.numberOfTrailingZeros(added);
        int color = (current.black() & added) != 0L ? 1 : -1;
        ObservedMove observed = new ObservedMove(color, square);
        if (pendingGeneratedMove != null
            && pendingGeneratedMove.equalsMove(observed)) {
            pendingGeneratedMove = null;
            return;
        }
        gameMoves.add(observed);
    }

    private void rollbackPendingMove() {
        synchronized (stateLock) {
            if (pendingGeneratedMove != null && !gameMoves.isEmpty()) {
                ObservedMove last = gameMoves.get(gameMoves.size() - 1);
                if (last.equalsMove(pendingGeneratedMove)) {
                    gameMoves.remove(gameMoves.size() - 1);
                }
            }
            pendingGeneratedMove = null;
            engineSynchronized = false;
        }
    }

    private void stopSearch() {
        Future<?> search;
        synchronized (stateLock) {
            searchGeneration++;
            search = activeSearch;
            activeSearch = null;
        }
        if (search != null && !search.isDone()) {
            if (engineThinking.get()) {
                engine.stop();
            }
            search.cancel(true);
        }
    }

    private synchronized boolean send(String message) {
        if (writer == null || message.indexOf('\r') >= 0 || message.indexOf('\n') >= 0) {
            return false;
        }
        writer.println(message);
        if (writer.checkError()) {
            running = false;
            return false;
        }
        return true;
    }

    private void close() {
        running = false;
        stopSearch();
        searchController.shutdownNow();
        engine.close();
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The connection is already ending.
            }
        }
    }

    private static BitBoardPosition applyMove(
        BitBoardPosition source,
        int color,
        int square
    ) {
        long player = source.player(color);
        long opponent = source.opponent(color);
        long move = 1L << square;
        long flips = BitBoard.flips(player, opponent, move);
        if (flips == 0L) {
            throw new IllegalArgumentException(
                "棋譜に非合法手があります: color=" + color + ", square=" + square
            );
        }
        long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
        long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
        return color == 1
            ? new BitBoardPosition(nextPlayer, nextOpponent)
            : new BitBoardPosition(nextOpponent, nextPlayer);
    }

    private static int parseColor(String message, String command)
        throws IOException {
        String[] parts = message.split("\\s+");
        if (parts.length != 2 || !parts[0].equals(command)) {
            throw new IOException(command + "メッセージが不正です。");
        }
        try {
            int color = Integer.parseInt(parts[1]);
            if (color != 1 && color != -1) {
                throw new IOException(command + "の色が不正です: " + color);
            }
            return color;
        } catch (NumberFormatException e) {
            throw new IOException(command + "の色を解析できません。", e);
        }
    }

    private static String colorName(int color) {
        return color == 1 ? "黒" : "白";
    }

    public static void main(String[] args) {
        if (args.length > 7) {
            printUsage();
            return;
        }
        String host = args.length >= 1 ? args[0] : DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String nickname = args.length >= 3 ? args[2] : DEFAULT_NICKNAME;
        int level = DEFAULT_LEVEL;
        int threads = DEFAULT_THREADS;
        Path executable = args.length >= 6
            ? Paths.get(args[5])
            : DEFAULT_EXECUTABLE;
        Path evaluation = args.length >= 7
            ? Paths.get(args[6])
            : DEFAULT_EVALUATION;

        try {
            if (args.length >= 2) {
                port = Integer.parseInt(args[1]);
            }
            if (args.length >= 4) {
                level = Integer.parseInt(args[3]);
            }
            if (args.length >= 5) {
                threads = Integer.parseInt(args[4]);
            }
            if (port < 1 || port > 65_535
                || level < 0 || level > 60 || threads < 1) {
                throw new IllegalArgumentException("起動引数が範囲外です。");
            }
            new EdaxOthelloClient(
                host,
                port,
                nickname,
                executable,
                evaluation,
                level,
                threads
            ).start();
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
        }
    }

    private static void printUsage() {
        System.err.println(
            "使い方: java EdaxOthelloClient <host> <port> "
                + "[nickname] [level] [threads] [edaxExe] [evalFile]"
        );
    }

    private static final class ObservedMove {

        private final int color;
        private final int square;

        private ObservedMove(int color, int square) {
            this.color = color;
            this.square = square;
        }

        private boolean equalsMove(ObservedMove other) {
            return other != null && color == other.color && square == other.square;
        }
    }
}
