import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class OthelloClient {

    private static final int BOARD_SIZE = 8;
    private final String host;
    private final int port;
    private final String nickname;
    private final int searchThreads;
    private final long timeMillis;
    private final Object stateLock = new Object();
    private final ExecutorService searchController;
    private final OthelloAI ai;
    private final SearchLimits searchLimits;

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    private BitBoardPosition position = new BitBoardPosition(0L, 0L);

    private int myColor;
    private int currentTurn;
    private int boardRevision;
    private int actedBoardRevision = -1;
    private long searchGeneration;
    private boolean hasBoard;
    private boolean boardRequested;
    private boolean searchAfterBoard;
    private Future<?> activeSearch;
    private volatile boolean running = true;

    public OthelloClient(
        String host,
        int port,
        String nickname,
        int searchThreads,
        long timeMillis
    ) {
        this(
            host,
            port,
            nickname,
            searchThreads,
            timeMillis,
            (Path) null
        );
    }

    public OthelloClient(
        String host,
        int port,
        String nickname,
        int searchThreads,
        long timeMillis,
        Path evaluationModel
    ) {
        this(
            host,
            port,
            nickname,
            searchThreads,
            timeMillis,
            evaluationModel == null
                ? new OthelloAI()
                : new OthelloAI(evaluationModel)
        );
    }

    OthelloClient(
        String host,
        int port,
        String nickname,
        int searchThreads,
        long timeMillis,
        OthelloAI ai
    ) {
        if (ai == null) {
            throw new NullPointerException("ai");
        }
        this.host = host;
        this.port = port;
        this.nickname = nickname;
        this.searchThreads = searchThreads;
        this.timeMillis = timeMillis;
        this.ai = ai;
        this.searchLimits = new SearchLimits(timeMillis, 64, searchThreads);
        this.searchController = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "othello-search-control");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        try {
            connect();

            System.out.println("サーバとの接続が確立しました。");
            System.out.println("接続先: " + host + ":" + port);
            System.out.println(
                "探索設定: threads=" + searchThreads
                    + ", timeMillis=" + timeMillis
                    + ", endgameThreshold="
                    + SearchEngine.endgameThresholdFor(timeMillis)
                    + " (ルート並列探索)"
            );
            System.out.println("評価関数: " + ai.evaluatorDescription());
            if (ai.openingBookSize() > 0) {
                System.out.println(
                    "定石データ: entries=" + ai.openingBookSize()
                        + ", sourceGames=" + ai.openingBookSourceGames()
                        + ", maximumPly=" + ai.openingBookMaximumPly()
                );
            } else {
                System.out.println("定石データ: なし（通常探索を使用）");
            }

            if (!nickname.isEmpty()) {
                send("NICK " + nickname);
            }

            startConsoleThread();
            receiveLoop();
        } catch (IOException e) {
            if (running) {
                System.err.println("通信中にエラーが発生しました。");
                System.err.println(e.getMessage());
            }
        } finally {
            close();
        }
    }

    private void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);

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
            System.out.println("[受信] " + message);
            processMessage(message);
        }

        if (running) {
            System.out.println("サーバとの接続が終了しました。");
        }
    }

    private void processMessage(String message) {
        if (message.startsWith("START ")) {
            processStart(message);
        } else if (message.startsWith("BOARD ")) {
            processBoard(message);
        } else if (message.startsWith("TURN ")) {
            processTurn(message);
        } else if (message.startsWith("SAY ")) {
            System.out.println("[チャット] " + message.substring(4));
        } else if (message.startsWith("ERROR ")) {
            processError(message);
        } else if (message.equals("END") || message.startsWith("END ")) {
            System.out.println("ゲーム終了");
            System.out.println(message);
            stopSearch();
            send("CLOSE");
            running = false;
        } else if (message.equals("CLOSE")) {
            System.out.println("サーバとの接続が終了しました。");
            stopSearch();
            running = false;
        } else {
            System.out.println("未対応のメッセージ: " + message);
        }
    }

    private void processStart(String message) {
        Integer color = parseColorMessage("START", message);
        if (color == null) {
            return;
        }

        stopSearch();
        synchronized (stateLock) {
            myColor = color;
            currentTurn = 0;
            hasBoard = false;
            boardRequested = false;
            searchAfterBoard = false;
            boardRevision++;
            actedBoardRevision = -1;
        }

        System.out.println(
            "ゲーム開始: あなたは" + colorName(color) + "です。"
        );
    }

    private void processBoard(String message) {
        String[] parts = message.trim().split("\\s+");
        if (parts.length != 65) {
            System.err.println(
                "BOARDの要素数が不正です: " + (parts.length - 1)
            );
            return;
        }

        long black = 0L;
        long white = 0L;
        try {
            for (int serverIndex = 0; serverIndex < 64; serverIndex++) {
                int value = Integer.parseInt(parts[serverIndex + 1]);
                if (value != -1 && value != 0 && value != 1) {
                    System.err.println(
                        "BOARDに不正な盤面値があります: " + value
                    );
                    return;
                }

                int square = CoordinateConverter.serverIndexToSquare(serverIndex);
                long bit = 1L << square;
                if (value == 1) {
                    black |= bit;
                } else if (value == -1) {
                    white |= bit;
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("BOARDに不正な数値があります。");
            return;
        }

        stopSearch();
        BitBoardPosition receivedPosition = new BitBoardPosition(black, white);
        boolean resumeSearch;
        synchronized (stateLock) {
            boolean changed = !hasBoard || !position.equals(receivedPosition);
            boolean boardWasRequestedForTurn =
                searchAfterBoard && currentTurn == myColor;

            position = receivedPosition;
            hasBoard = true;
            boardRequested = false;
            searchAfterBoard = false;
            if (changed) {
                boardRevision++;
            }

            /*
             * The real server sends BOARD before the next TURN. A changed
             * board therefore invalidates the previously announced turn.
             */
            if (changed && !boardWasRequestedForTurn) {
                currentTurn = 0;
            }
            resumeSearch = boardWasRequestedForTurn
                || (!changed && currentTurn == myColor);
        }

        printBoard();
        if (resumeSearch) {
            startSearchIfReady();
        }
    }

    private void processTurn(String message) {
        Integer color = parseColorMessage("TURN", message);
        if (color == null) {
            return;
        }

        stopSearch();
        boolean requestBoard = false;
        synchronized (stateLock) {
            currentTurn = color;
            searchAfterBoard = false;
            if (currentTurn == myColor && !hasBoard) {
                searchAfterBoard = true;
                if (!boardRequested) {
                    boardRequested = true;
                    requestBoard = true;
                }
            }
        }

        System.out.println("現在の手番: " + colorName(color));
        if (requestBoard) {
            System.out.println("盤面が未受信のためBOARDを要求します。");
            send("BOARD");
        } else {
            startSearchIfReady();
        }
    }

    private Integer parseColorMessage(String command, String message) {
        String[] parts = message.trim().split("\\s+");
        if (parts.length != 2) {
            System.err.println(command + "メッセージの書式が不正です。");
            return null;
        }

        try {
            int color = Integer.parseInt(parts[1]);
            if (color != 1 && color != -1) {
                System.err.println(command + "で不正な色が指定されました。");
                return null;
            }
            return color;
        } catch (NumberFormatException e) {
            System.err.println(command + "メッセージを解析できません。");
            return null;
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

            final int color = myColor;
            final int revision = boardRevision;
            final long generation = searchGeneration;
            final BitBoardPosition snapshot = position;

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
        SearchResult result = ai.chooseMove(snapshot, color, searchLimits);
        if (Thread.currentThread().isInterrupted() || result.stopped()) {
            return;
        }

        int bestSquare = result.bestSquare();
        long chosenMove = bestSquare < 0 ? 0L : 1L << bestSquare;
        if (result.openingBookMove()) {
            System.out.println(
                "定石結果: games=" + result.openingBookGames()
                    + ", winRatePermille="
                    + result.openingBookWinRatePermille()
                    + ", evaluation=" + result.score()
            );
        } else {
            System.out.println(
                "探索結果: depth=" + result.completedDepth()
                    + ", score=" + result.score()
                    + ", nodes=" + result.nodes()
                    + ", timeMillis=" + result.elapsedNanos() / 1_000_000L
                    + ", ttHits=" + result.transpositionHits()
                    + ", workerNodes=" + result.parallelNodes()
                    + ", parallelTasks=" + result.parallelTasks()
                    + (result.endgameSearch()
                        ? ", endgameEmpties=" + result.endgameEmpties()
                        : "")
                    + (result.exactSolution() ? ", exact" : "")
                    + (result.timedOut() ? ", timeout" : "")
            );
        }

        synchronized (stateLock) {
            if (Thread.currentThread().isInterrupted()
                || !running
                || generation != searchGeneration
                || revision != boardRevision
                || currentTurn != myColor
                || color != myColor
                || actedBoardRevision == boardRevision) {
                return;
            }

            if (chosenMove == 0L) {
                actedBoardRevision = boardRevision;
                System.out.println("この盤面では置ける場所がありません。");
                return;
            }

            // Recheck against the latest accepted board before PUT.
            if (!BitBoard.isLegalMove(
                position.player(myColor),
                position.opponent(myColor),
                chosenMove
            )) {
                return;
            }

            int square = Long.numberOfTrailingZeros(chosenMove);
            int chosenX = CoordinateConverter.squareToX(square);
            int chosenY = CoordinateConverter.squareToY(square);
            actedBoardRevision = boardRevision;
            System.out.println("AIの着手: PUT " + chosenX + " " + chosenY);
            send("PUT " + chosenX + " " + chosenY);
        }
    }

    private void stopSearch() {
        ai.stop();
        Future<?> search;
        synchronized (stateLock) {
            searchGeneration++;
            search = activeSearch;
            activeSearch = null;
        }
        if (search != null) {
            search.cancel(true);
        }
    }

    private void printBoard() {
        synchronized (stateLock) {
            System.out.println();
            System.out.println("    0 1 2 3 4 5 6 7");
            System.out.println("   -----------------");
            for (int y = 0; y < BOARD_SIZE; y++) {
                System.out.print(y + " | ");
                for (int x = 0; x < BOARD_SIZE; x++) {
                    long bit = 1L << CoordinateConverter.xyToSquare(x, y);
                    char mark = (position.black() & bit) != 0L
                        ? 'B'
                        : (position.white() & bit) != 0L ? 'W' : '.';
                    System.out.print(mark + " ");
                }
                System.out.println();
            }
            System.out.println();
        }
    }

    private void processError(String message) {
        String[] parts = message.trim().split("\\s+");
        if (parts.length != 2) {
            System.err.println("不明なERRORメッセージ: " + message);
            return;
        }

        switch (parts[1]) {
            case "1":
                System.err.println("ERROR 1: 命令の書式が不正です。");
                break;
            case "2":
                System.err.println("ERROR 2: その場所には置けません。");
                stopSearch();
                requestBoardIfNeeded();
                break;
            case "3":
                System.err.println("ERROR 3: 相手のターンです。");
                stopSearch();
                synchronized (stateLock) {
                    currentTurn = 0;
                }
                break;
            case "4":
                System.err.println("ERROR 4: 処理できない命令です。");
                break;
            default:
                System.err.println("不明なエラー: " + message);
                break;
        }
    }

    private void requestBoardIfNeeded() {
        boolean request = false;
        synchronized (stateLock) {
            if (!boardRequested) {
                boardRequested = true;
                request = true;
            }
        }
        if (request) {
            send("BOARD");
        }
    }

    private void startConsoleThread() {
        Thread thread = new Thread(() -> {
            BufferedReader consoleReader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            try {
                String input;
                while (running && (input = consoleReader.readLine()) != null) {
                    processConsoleInput(input);
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("コンソール入力でエラーが発生しました。");
                }
            }
        }, "othello-console");
        thread.setDaemon(true);
        thread.start();
    }

    private void processConsoleInput(String input) {
        if (input.startsWith("/say ")) {
            send("SAY " + input.substring(5));
        } else if (input.startsWith("/nick ")) {
            send("NICK " + input.substring(6));
        } else if (input.equals("/board")) {
            requestBoardIfNeeded();
        } else if (input.equals("/close")) {
            stopSearch();
            send("CLOSE");
            running = false;
            close();
        } else if (!input.trim().isEmpty()) {
            send("SAY " + input);
        }
    }

    private synchronized boolean send(String message) {
        if (writer == null || message.indexOf('\r') >= 0 || message.indexOf('\n') >= 0) {
            return false;
        }

        System.out.println("[送信] " + message);
        writer.println(message);
        if (writer.checkError()) {
            System.err.println("サーバへの送信に失敗しました。");
            running = false;
            return false;
        }
        return true;
    }

    private void close() {
        running = false;
        stopSearch();
        ai.shutdown();
        searchController.shutdownNow();

        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("ソケットを閉じる際にエラーが発生しました。");
            }
        }
    }

    private static String colorName(int color) {
        return color == 1 ? "黒" : "白";
    }

    public static void main(String[] args) {
        try {
            ClientOptions options = ClientOptions.parse(args);
            PositionEvaluator evaluator = OthelloAI.loadEvaluator(
                options.evaluationModel
            );
            RuntimeConfiguration runtime = RuntimeConfiguration.resolve(
                evaluator,
                options.threadSpec,
                options.ttSpec
            );
            runtime.print(System.out);
            OthelloAI ai = OthelloAI.create(
                evaluator,
                runtime.ttEntries()
            );
            System.out.println(
                "サーバに接続を試みます: "
                    + options.host + ":" + options.port
            );
            new OthelloClient(
                options.host,
                options.port,
                options.nickname,
                runtime.threads(),
                options.timeMillis,
                ai
            ).start();
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            ClientOptions.printUsage();
        }
    }
}
