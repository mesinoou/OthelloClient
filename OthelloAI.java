import java.io.IOException;
import java.nio.file.Path;

public final class OthelloAI {

    private final SearchEngine searchEngine;
    private final OpeningBook openingBook;

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
            )
        );
    }

    OthelloAI(OpeningBook openingBook, SearchEngine searchEngine) {
        if (openingBook == null) {
            throw new NullPointerException("openingBook");
        }
        if (searchEngine == null) {
            throw new NullPointerException("searchEngine");
        }
        this.openingBook = openingBook;
        this.searchEngine = searchEngine;
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
