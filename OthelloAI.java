public final class OthelloAI {

    private final SearchEngine searchEngine;
    private final OpeningBook openingBook;

    public OthelloAI() {
        this(OpeningBook.loadDefault(), new SearchEngine());
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

    public void stop() {
        searchEngine.stop();
    }

    public void shutdown() {
        searchEngine.shutdown();
    }
}
