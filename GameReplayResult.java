public final class GameReplayResult {

    private final GameRecord game;
    private final BitBoardPosition finalPosition;
    private final int passCount;

    public GameReplayResult(
        GameRecord game,
        BitBoardPosition finalPosition,
        int passCount
    ) {
        this.game = game;
        this.finalPosition = finalPosition;
        this.passCount = passCount;
    }

    public GameRecord game() {
        return game;
    }

    public BitBoardPosition finalPosition() {
        return finalPosition;
    }

    public int moveCount() {
        return game.moves().size();
    }

    public int passCount() {
        return passCount;
    }

    public int blackCount() {
        return BitBoard.count(finalPosition.black());
    }

    public int whiteCount() {
        return BitBoard.count(finalPosition.white());
    }

    public String winnerName() {
        if (blackCount() > whiteCount()) {
            return game.blackName();
        }
        if (whiteCount() > blackCount()) {
            return game.whiteName();
        }
        return "DRAW";
    }
}
