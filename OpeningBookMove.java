public final class OpeningBookMove {

    private final int square;
    private final int evaluation;
    private final int games;
    private final int wins;
    private final int draws;
    private final int discDifferenceSum;

    public OpeningBookMove(
        int square,
        int evaluation,
        int games,
        int wins,
        int draws,
        int discDifferenceSum
    ) {
        this.square = square;
        this.evaluation = evaluation;
        this.games = games;
        this.wins = wins;
        this.draws = draws;
        this.discDifferenceSum = discDifferenceSum;
    }

    public int square() {
        return square;
    }

    public int evaluation() {
        return evaluation;
    }

    public int games() {
        return games;
    }

    public int wins() {
        return wins;
    }

    public int draws() {
        return draws;
    }

    public int winRatePermille() {
        if (games == 0) {
            return 0;
        }
        return (wins * 1000 + draws * 500) / games;
    }

    public int averageDiscDifferenceTimes100() {
        if (games == 0) {
            return 0;
        }
        return discDifferenceSum * 100 / games;
    }
}
