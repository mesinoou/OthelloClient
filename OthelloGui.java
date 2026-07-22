import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

@SuppressWarnings("serial")
public final class OthelloGui extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_TIME_MILLIS = 1_000;
    private static final Color WINDOW_BACKGROUND = new Color(0xF3F5F4);
    private static final Color BOARD_COLOR = new Color(0x2F8F5B);
    private static final Color GRID_COLOR = new Color(0x173D2B);
    private static final Color LEGAL_COLOR = new Color(0xF4B942);
    private static final Color LAST_MOVE_COLOR = new Color(0xE26D3F);

    private final OthelloAI ai;
    private final int searchThreads;
    private final ExecutorService aiController;
    private final HumanVsAiGame game;
    private final BoardPanel boardPanel;
    private final JComboBox<ColorChoice> colorChoice;
    private final JComboBox<TimeChoice> timeChoice;
    private final JButton undoButton;
    private final JLabel scoreLabel;
    private final JLabel statusLabel;
    private final JProgressBar thinkingIndicator;

    private Future<?> aiTask;
    private long gameGeneration;
    private boolean thinking;
    private boolean closed;

    private OthelloGui(
        OthelloAI ai,
        int searchThreads,
        int initialHumanColor,
        int initialTimeMillis
    ) {
        super("Othello - Human vs AI");
        this.ai = ai;
        this.searchThreads = searchThreads;
        this.aiController = Executors.newSingleThreadExecutor(
            new AiControllerThreadFactory()
        );
        this.game = new HumanVsAiGame(initialHumanColor);
        this.boardPanel = new BoardPanel();
        this.colorChoice = new JComboBox<>(new ColorChoice[] {
            new ColorChoice(HumanVsAiGame.BLACK, "黒（先手）"),
            new ColorChoice(HumanVsAiGame.WHITE, "白（後手）")
        });
        this.timeChoice = createTimeChoice(initialTimeMillis);
        this.undoButton = new JButton("1手戻す");
        this.scoreLabel = new JLabel();
        this.statusLabel = new JLabel(" ");
        this.thinkingIndicator = new JProgressBar();

        colorChoice.setSelectedIndex(
            initialHumanColor == HumanVsAiGame.BLACK ? 0 : 1
        );
        configureWindow();
        startNewGame();
    }

    public static void main(String[] args) {
        GuiOptions options;
        try {
            options = GuiOptions.parse(args);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            printUsage();
            return;
        }
        if (options.help) {
            printUsage();
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("GUIを表示できる画面環境がありません。");
            return;
        }

        PositionEvaluator evaluator;
        try {
            evaluator = OthelloAI.loadEvaluator(options.modelPath);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            return;
        }
        RuntimeConfiguration runtime;
        try {
            runtime = RuntimeConfiguration.resolve(
                evaluator,
                options.threadSpec,
                options.ttSpec
            );
        } catch (IllegalArgumentException error) {
            System.err.println("実行時設定が不正です: " + error.getMessage());
            return;
        }
        runtime.print(System.out);
        OthelloAI ai = OthelloAI.create(evaluator, runtime.ttEntries());

        SwingUtilities.invokeLater(() -> {
            setSystemLookAndFeel();
            OthelloGui gui = new OthelloGui(
                ai,
                runtime.threads(),
                options.humanColor,
                options.timeMillis
            );
            gui.setVisible(true);
        });
    }

    private void configureWindow() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(560, 650));
        setSize(new Dimension(720, 790));
        setLocationByPlatform(true);
        getContentPane().setBackground(WINDOW_BACKGROUND);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(createToolbar(), BorderLayout.NORTH);
        getContentPane().add(boardPanel, BorderLayout.CENTER);
        getContentPane().add(createStatusBar(), BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closeResources();
            }
        });
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(WINDOW_BACKGROUND);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xCDD3D0)),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.setOpaque(false);
        controls.add(new JLabel("あなた:"));
        controls.add(colorChoice);
        controls.add(new JLabel("思考時間:"));
        controls.add(timeChoice);

        JButton newGameButton = new JButton("新しい対局");
        newGameButton.addActionListener(event -> startNewGame());
        controls.add(newGameButton);
        undoButton.addActionListener(event -> undoHumanTurn());
        controls.add(undoButton);

        scoreLabel.setFont(scoreLabel.getFont().deriveFont(Font.BOLD, 15.0f));
        scoreLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 4));
        toolbar.add(controls, BorderLayout.CENTER);
        toolbar.add(scoreLabel, BorderLayout.EAST);
        return toolbar;
    }

    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout(12, 0));
        statusBar.setBackground(WINDOW_BACKGROUND);
        statusBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xCDD3D0)),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));
        statusLabel.setFont(statusLabel.getFont().deriveFont(14.0f));
        thinkingIndicator.setPreferredSize(new Dimension(130, 20));
        thinkingIndicator.setStringPainted(true);
        thinkingIndicator.setString("待機中");
        thinkingIndicator.setValue(0);
        statusBar.add(statusLabel, BorderLayout.CENTER);
        statusBar.add(thinkingIndicator, BorderLayout.EAST);
        return statusBar;
    }

    private void startNewGame() {
        cancelAiSearch();
        ColorChoice selected = (ColorChoice) colorChoice.getSelectedItem();
        int humanColor = selected == null
            ? HumanVsAiGame.BLACK
            : selected.color;
        game.reset(humanColor);
        refreshBoard();
        if (game.isHumanTurn()) {
            setStatus("あなたの手番です。黄色の印をクリックしてください。");
        } else {
            startAiTurn(0);
        }
    }

    private void undoHumanTurn() {
        if (!game.canUndo()) {
            return;
        }
        cancelAiSearch();
        if (!game.undoHumanTurn()) {
            return;
        }
        refreshBoard();
        if (game.isHumanTurn()) {
            setStatus("直前のあなたの手まで戻しました。");
        } else {
            startAiTurn(0);
        }
    }

    private void handleBoardClick(int square) {
        if (thinking || !game.isHumanTurn()) {
            return;
        }
        long move = 1L << square;
        if ((game.legalMoves() & move) == 0L) {
            return;
        }
        HumanVsAiGame.MoveOutcome outcome = game.playHuman(square);
        refreshBoard();
        continueAfterMove(outcome);
    }

    private void continueAfterMove(HumanVsAiGame.MoveOutcome outcome) {
        if (outcome.gameOver()) {
            showGameOver();
            return;
        }
        if (game.isHumanTurn()) {
            if (outcome.passedColor() != 0) {
                setStatus("AIはパスです。続けてあなたの手番です。");
            } else {
                setStatus("あなたの手番です。");
            }
            return;
        }
        startAiTurn(outcome.passedColor());
    }

    private void startAiTurn(int passedColor) {
        if (closed || game.isGameOver() || game.isHumanTurn()) {
            return;
        }
        thinking = true;
        refreshControls();
        if (passedColor == game.humanColor()) {
            setStatus("あなたはパスです。AIが続けて考えています。");
        } else {
            setStatus("AIが考えています...");
        }

        BitBoardPosition snapshot = game.position();
        int color = game.currentColor();
        long generation = gameGeneration;
        SearchLimits limits = new SearchLimits(
            selectedTimeMillis(),
            64,
            searchThreads
        );
        aiTask = aiController.submit(() -> {
            SearchResult result;
            try {
                result = ai.chooseMove(snapshot, color, limits);
            } catch (RuntimeException error) {
                SwingUtilities.invokeLater(
                    () -> handleAiFailure(generation, error)
                );
                return;
            }
            SwingUtilities.invokeLater(
                () -> applyAiResult(generation, snapshot, color, result)
            );
        });
    }

    private void applyAiResult(
        long generation,
        BitBoardPosition searchedPosition,
        int searchedColor,
        SearchResult result
    ) {
        if (closed
            || generation != gameGeneration
            || !game.position().equals(searchedPosition)
            || game.currentColor() != searchedColor) {
            return;
        }
        aiTask = null;
        thinking = false;
        int square = result.bestSquare();
        if (result.stopped()
            || square < 0
            || (game.legalMoves() & (1L << square)) == 0L) {
            refreshControls();
            setStatus("AI探索を完了できませんでした。新しい対局を開始してください。");
            return;
        }

        HumanVsAiGame.MoveOutcome outcome = game.playAi(square);
        refreshBoard();
        continueAfterMove(outcome);
    }

    private void handleAiFailure(long generation, RuntimeException error) {
        if (closed || generation != gameGeneration) {
            return;
        }
        aiTask = null;
        thinking = false;
        refreshControls();
        setStatus("AI探索でエラーが発生しました。");
        JOptionPane.showMessageDialog(
            this,
            error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage(),
            "AI探索エラー",
            JOptionPane.ERROR_MESSAGE
        );
    }

    private void showGameOver() {
        int humanDiscs = game.humanColor() == HumanVsAiGame.BLACK
            ? game.blackDiscs()
            : game.whiteDiscs();
        int aiDiscs = game.humanColor() == HumanVsAiGame.BLACK
            ? game.whiteDiscs()
            : game.blackDiscs();
        String result = humanDiscs > aiDiscs
            ? "あなたの勝ち"
            : humanDiscs < aiDiscs ? "AIの勝ち" : "引き分け";
        setStatus(
            "対局終了: " + result + "（あなた " + humanDiscs
                + " - " + aiDiscs + " AI）"
        );
        refreshControls();
    }

    private void cancelAiSearch() {
        gameGeneration++;
        ai.stop();
        Future<?> task = aiTask;
        if (task != null) {
            task.cancel(true);
            aiTask = null;
        }
        thinking = false;
        refreshControls();
    }

    private void closeResources() {
        if (closed) {
            return;
        }
        closed = true;
        cancelAiSearch();
        aiController.shutdownNow();
        ai.shutdown();
    }

    private void refreshBoard() {
        scoreLabel.setText(
            "黒 " + game.blackDiscs() + "  白 " + game.whiteDiscs()
        );
        boardPanel.repaint();
        refreshControls();
    }

    private void refreshControls() {
        undoButton.setEnabled(game.canUndo());
        thinkingIndicator.setIndeterminate(thinking);
        thinkingIndicator.setString(thinking ? "AI思考中" : "待機中");
        boardPanel.setEnabled(!thinking && game.isHumanTurn());
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private int selectedTimeMillis() {
        TimeChoice selected = (TimeChoice) timeChoice.getSelectedItem();
        return selected == null ? DEFAULT_TIME_MILLIS : selected.millis;
    }

    private static JComboBox<TimeChoice> createTimeChoice(int selectedMillis) {
        int[] standardMillis = {250, 500, 1_000, 2_000, 4_000, 8_000};
        JComboBox<TimeChoice> combo = new JComboBox<>();
        boolean selectedAdded = false;
        for (int millis : standardMillis) {
            if (!selectedAdded && selectedMillis < millis) {
                combo.addItem(new TimeChoice(selectedMillis));
                selectedAdded = true;
            }
            combo.addItem(new TimeChoice(millis));
            if (selectedMillis == millis) {
                selectedAdded = true;
            }
        }
        if (!selectedAdded) {
            combo.addItem(new TimeChoice(selectedMillis));
        }
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index).millis == selectedMillis) {
                combo.setSelectedIndex(index);
                break;
            }
        }
        return combo;
    }

    private static void setSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException
            | javax.swing.UnsupportedLookAndFeelException ignored) {
            // The cross-platform look and feel remains usable.
        }
    }

    private static void printUsage() {
        System.out.println(
            "使い方: java -cp .build OthelloGui [options]\n"
                + "  --color black|white  人間側の色（既定: black）\n"
                + "  --time-ms N          AIの1手の思考時間（既定: 1000）\n"
                + "  --model PATH         学習済み評価モデル\n"
                + "  --threads auto|N     探索スレッド数（既定: auto）\n"
                + "  --tt auto|N          TTエントリ数（既定: auto）\n"
                + "  --help               この説明を表示"
        );
    }

    private final class BoardPanel extends JPanel {

        private static final long serialVersionUID = 1L;
        private static final int BOARD_MARGIN = 30;

        private BoardPanel() {
            setPreferredSize(new Dimension(640, 640));
            setMinimumSize(new Dimension(480, 480));
            setBackground(new Color(0x252A28));
            setToolTipText("黄色の印が現在の合法手です");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    int square = squareAt(event.getX(), event.getY());
                    if (square >= 0) {
                        handleBoardClick(square);
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                );
                Rectangle board = boardBounds();
                int cell = board.width / CoordinateConverter.BOARD_SIZE;
                paintCoordinates(g, board, cell);
                g.setColor(BOARD_COLOR);
                g.fillRect(board.x, board.y, board.width, board.height);
                paintGrid(g, board, cell);
                paintMovesAndDiscs(g, board, cell);
            } finally {
                g.dispose();
            }
        }

        private void paintCoordinates(Graphics2D g, Rectangle board, int cell) {
            g.setColor(new Color(0xE8ECEA));
            g.setFont(getFont().deriveFont(Font.BOLD, 13.0f));
            FontMetrics metrics = g.getFontMetrics();
            for (int index = 0; index < CoordinateConverter.BOARD_SIZE; index++) {
                String file = Character.toString((char) ('A' + index));
                int fileX = board.x + index * cell
                    + (cell - metrics.stringWidth(file)) / 2;
                g.drawString(file, fileX, board.y - 9);

                String rank = Integer.toString(index + 1);
                int rankX = board.x - metrics.stringWidth(rank) - 10;
                int rankY = board.y + index * cell
                    + (cell + metrics.getAscent()) / 2 - 2;
                g.drawString(rank, rankX, rankY);
            }
        }

        private void paintGrid(Graphics2D g, Rectangle board, int cell) {
            g.setColor(GRID_COLOR);
            g.setStroke(new BasicStroke(1.2f));
            for (int index = 0; index <= CoordinateConverter.BOARD_SIZE; index++) {
                int offset = index * cell;
                g.drawLine(
                    board.x + offset,
                    board.y,
                    board.x + offset,
                    board.y + board.height
                );
                g.drawLine(
                    board.x,
                    board.y + offset,
                    board.x + board.width,
                    board.y + offset
                );
            }
        }

        private void paintMovesAndDiscs(Graphics2D g, Rectangle board, int cell) {
            long legal = isEnabled() && game.isHumanTurn()
                ? game.legalMoves()
                : 0L;
            for (int square = 0;
                square < CoordinateConverter.SQUARE_COUNT;
                square++) {
                int x = CoordinateConverter.squareToX(square);
                int y = CoordinateConverter.squareToY(square);
                int cellX = board.x + x * cell;
                int cellY = board.y + y * cell;
                long bit = 1L << square;
                if ((game.position().black() & bit) != 0L) {
                    paintDisc(g, cellX, cellY, cell, Color.BLACK, square);
                } else if ((game.position().white() & bit) != 0L) {
                    paintDisc(g, cellX, cellY, cell, Color.WHITE, square);
                } else if ((legal & bit) != 0L) {
                    int dot = Math.max(8, cell / 7);
                    g.setColor(LEGAL_COLOR);
                    g.fillOval(
                        cellX + (cell - dot) / 2,
                        cellY + (cell - dot) / 2,
                        dot,
                        dot
                    );
                }
            }
        }

        private void paintDisc(
            Graphics2D g,
            int x,
            int y,
            int cell,
            Color color,
            int square
        ) {
            int inset = Math.max(5, cell / 11);
            int diameter = cell - inset * 2;
            g.setColor(new Color(0, 0, 0, 65));
            g.fillOval(x + inset + 2, y + inset + 3, diameter, diameter);
            g.setColor(color);
            g.fillOval(x + inset, y + inset, diameter, diameter);
            g.setColor(color == Color.BLACK
                ? new Color(0x4B504E)
                : new Color(0xCCD2CF));
            g.drawOval(x + inset, y + inset, diameter, diameter);

            if (square == game.lastSquare()) {
                int marker = Math.max(8, cell / 8);
                g.setColor(LAST_MOVE_COLOR);
                g.setStroke(new BasicStroke(2.2f));
                g.drawOval(
                    x + (cell - marker) / 2,
                    y + (cell - marker) / 2,
                    marker,
                    marker
                );
            }
        }

        private int squareAt(int mouseX, int mouseY) {
            Rectangle board = boardBounds();
            if (!board.contains(mouseX, mouseY)) {
                return -1;
            }
            int cell = board.width / CoordinateConverter.BOARD_SIZE;
            int x = (mouseX - board.x) / cell;
            int y = (mouseY - board.y) / cell;
            return CoordinateConverter.xyToSquare(x, y);
        }

        private Rectangle boardBounds() {
            int available = Math.min(getWidth(), getHeight())
                - 2 * BOARD_MARGIN;
            int cell = Math.max(
                1,
                available / CoordinateConverter.BOARD_SIZE
            );
            int size = cell * CoordinateConverter.BOARD_SIZE;
            return new Rectangle(
                (getWidth() - size) / 2,
                (getHeight() - size) / 2,
                size,
                size
            );
        }
    }

    private static final class ColorChoice {

        private final int color;
        private final String label;

        private ColorChoice(int color, String label) {
            this.color = color;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class TimeChoice {

        private final int millis;

        private TimeChoice(int millis) {
            this.millis = millis;
        }

        @Override
        public String toString() {
            if (millis < 1_000) {
                return millis + " ms";
            }
            return millis % 1_000 == 0
                ? (millis / 1_000) + " 秒"
                : String.format("%.2f 秒", millis / 1_000.0);
        }
    }

    private static final class GuiOptions {

        private int humanColor = HumanVsAiGame.BLACK;
        private int timeMillis = DEFAULT_TIME_MILLIS;
        private Path modelPath;
        private String threadSpec = "auto";
        private String ttSpec = "auto";
        private boolean help;

        private static GuiOptions parse(String[] args) {
            GuiOptions options = new GuiOptions();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    options.help = true;
                    continue;
                }
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException(
                        "値がありません: " + argument
                    );
                }
                String value = args[++index];
                switch (argument) {
                    case "--color":
                        options.humanColor = parseColor(value);
                        break;
                    case "--time-ms":
                        options.timeMillis = parseTime(value);
                        break;
                    case "--model":
                        options.modelPath = Path.of(value);
                        break;
                    case "--threads":
                        options.threadSpec = value;
                        break;
                    case "--tt":
                        options.ttSpec = value;
                        break;
                    default:
                        throw new IllegalArgumentException(
                            "不明なオプションです: " + argument
                        );
                }
            }
            return options;
        }

        private static int parseColor(String value) {
            if ("black".equalsIgnoreCase(value)) {
                return HumanVsAiGame.BLACK;
            }
            if ("white".equalsIgnoreCase(value)) {
                return HumanVsAiGame.WHITE;
            }
            throw new IllegalArgumentException(
                "--colorはblackまたはwhiteを指定してください"
            );
        }

        private static int parseTime(String value) {
            try {
                int millis = Integer.parseInt(value);
                if (millis < 50 || millis > 60_000) {
                    throw new NumberFormatException();
                }
                return millis;
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                    "--time-msは50から60000の整数で指定してください"
                );
            }
        }
    }

    private static final class AiControllerThreadFactory
        implements ThreadFactory {

        @Override
        public Thread newThread(Runnable action) {
            Thread thread = new Thread(action, "othello-gui-ai-controller");
            thread.setDaemon(true);
            return thread;
        }
    }
}
