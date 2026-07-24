import java.io.IOException;
import java.nio.file.Path;

public final class OthelloAI {

    private final SearchEngine searchEngine;
    private final OpeningBook openingBook;
    private final ColorSelectableEvaluator colorSelectableEvaluator;

    public OthelloAI() {
        this(LearnedEvaluator.loadDefault(), 1 << 18);
    }

    public OthelloAI(Path evaluationModel) {
        this(loadRequiredEvaluator(evaluationModel), 1 << 18);
    }

    private OthelloAI(PositionEvaluator evaluator, int ttEntries) {
        this(
            OpeningBook.loadDefault(),
            new SearchEngine(
                evaluator,
                new TranspositionTable(ttEntries)
            ),
            evaluator instanceof ColorSelectableEvaluator
                ? (ColorSelectableEvaluator) evaluator
                : null
        );
    }

    OthelloAI(OpeningBook openingBook, SearchEngine searchEngine) {
        this(openingBook, searchEngine, null);
    }

    private OthelloAI(
        OpeningBook openingBook,
        SearchEngine searchEngine,
        ColorSelectableEvaluator colorSelectableEvaluator
    ) {
        if (openingBook == null) {
            throw new NullPointerException("openingBook");
        }
        if (searchEngine == null) {
            throw new NullPointerException("searchEngine");
        }
        this.openingBook = openingBook;
        this.searchEngine = searchEngine;
        this.colorSelectableEvaluator = colorSelectableEvaluator;
    }

    public SearchResult chooseMove(
        BitBoardPosition position,
        int color,
        SearchLimits limits
    ) {
        OpeningBookMove bookMove = openingBook.find(position, color);
        if (bookMove != null) {
            return new SearchResult(
                bookMove.square(),
                bookMove.evaluation(),
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                false,
                false,
                true,
                bookMove.games(),
                bookMove.winRatePermille()
            );
        }
        return searchEngine.search(position, color, limits);
    }

    public SearchResult ponder(
        BitBoardPosition position,
        int color,
        SearchLimits limits
    ) {
        return searchEngine.search(position, color, limits);
    }

    boolean hasTransposition(BitBoardPosition position, int color) {
        return searchEngine.hasTransposition(position, color);
    }

    public int openingBookSize() {
        return openingBook.size();
    }

    public int openingBookSourceGames() {
        return openingBook.sourceGames();
    }

    public int openingBookMaximumPly() {
        return openingBook.maximumPly();
    }

    public String evaluatorDescription() {
        return searchEngine.evaluatorDescription();
    }

    public void selectPlayerColor(int color) {
        if (colorSelectableEvaluator == null
            || colorSelectableEvaluator.selectedColor() == color) {
            return;
        }
        searchEngine.clearTranspositionTable();
        colorSelectableEvaluator.selectColor(color);
    }

    public void stop() {
        searchEngine.stop();
    }

    public void shutdown() {
        searchEngine.shutdown();
    }

    static OthelloAI create(PositionEvaluator evaluator, int ttEntries) {
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        return new OthelloAI(evaluator, ttEntries);
    }

    static OthelloAI create(
        PositionEvaluator blackEvaluator,
        PositionEvaluator whiteEvaluator,
        int ttEntries
    ) {
        return new OthelloAI(
            new ColorSelectableEvaluator(blackEvaluator, whiteEvaluator),
            ttEntries
        );
    }

    static PositionEvaluator loadEvaluator(Path path) {
        return path == null
            ? LearnedEvaluator.loadDefault()
            : loadRequiredEvaluator(path);
    }

    private static PositionEvaluator loadRequiredEvaluator(Path path) {
        try {
            return LearnedEvaluator.load(path);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                "学習済み評価モデルを読み込めません: " + error.getMessage(),
                error
            );
        }
    }
}
