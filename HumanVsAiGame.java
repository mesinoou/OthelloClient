import java.util.ArrayDeque;
import java.util.Deque;

public final class HumanVsAiGame {

    public static final int BLACK = 1;
    public static final int WHITE = -1;

    private final Deque<State> history = new ArrayDeque<>();

    private BitBoardPosition position;
    private int currentColor;
    private int humanColor;
    private int lastSquare;
    private int lastColor;
    private int passedColor;
    private int humanMoves;
    private boolean gameOver;

    public HumanVsAiGame(int humanColor) {
        reset(humanColor);
    }

    public void reset(int newHumanColor) {
        checkColor(newHumanColor);
        position = BitBoardPosition.initial();
        currentColor = BLACK;
        humanColor = newHumanColor;
        lastSquare = -1;
        lastColor = 0;
        passedColor = 0;
        humanMoves = 0;
        gameOver = false;
        history.clear();
    }

    public MoveOutcome playHuman(int square) {
        if (currentColor != humanColor) {
            throw new IllegalStateException("it is not the human turn");
        }
        return play(square);
    }

    public MoveOutcome playAi(int square) {
        if (currentColor == humanColor || currentColor == 0) {
            throw new IllegalStateException("it is not the AI turn");
        }
        return play(square);
    }

    public boolean undoHumanTurn() {
        if (humanMoves == 0 || history.isEmpty()) {
            return false;
        }
        int targetHumanMoves = humanMoves - 1;
        while (!history.isEmpty()) {
            restore(history.pop());
            if (humanMoves == targetHumanMoves
                && currentColor == humanColor) {
                return true;
            }
        }
        return false;
    }

    public BitBoardPosition position() {
        return position;
    }

    public int currentColor() {
        return currentColor;
    }

    public int humanColor() {
        return humanColor;
    }

    public int lastSquare() {
        return lastSquare;
    }

    public int lastColor() {
        return lastColor;
    }

    public int passedColor() {
        return passedColor;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public boolean isHumanTurn() {
        return !gameOver && currentColor == humanColor;
    }

    public boolean canUndo() {
        return humanMoves > 0;
    }

    public long legalMoves() {
        if (gameOver) {
            return 0L;
        }
        return BitBoard.legalMoves(
            position.player(currentColor),
            position.opponent(currentColor)
        );
    }

    public int blackDiscs() {
        return Long.bitCount(position.black());
    }

    public int whiteDiscs() {
        return Long.bitCount(position.white());
    }

    public int winner() {
        if (!gameOver) {
            return 0;
        }
        return Integer.compare(blackDiscs(), whiteDiscs());
    }

    private MoveOutcome play(int square) {
        if (gameOver) {
            throw new IllegalStateException("the game is over");
        }
        if (square < 0 || square >= CoordinateConverter.SQUARE_COUNT) {
            throw new IllegalArgumentException("square out of range");
        }

        int playedColor = currentColor;
        long player = position.player(playedColor);
        long opponent = position.opponent(playedColor);
        long move = 1L << square;
        long flips = BitBoard.flips(player, opponent, move);
        if (flips == 0L) {
            throw new IllegalArgumentException("illegal move: " + square);
        }

        history.push(snapshot());
        long movedPlayer = BitBoard.applyPlayerBoard(player, move, flips);
        long movedOpponent = BitBoard.applyOpponentBoard(opponent, flips);
        position = playedColor == BLACK
            ? new BitBoardPosition(movedPlayer, movedOpponent)
            : new BitBoardPosition(movedOpponent, movedPlayer);
        if (playedColor == humanColor) {
            humanMoves++;
        }
        lastSquare = square;
        lastColor = playedColor;
        passedColor = 0;

        int nextColor = -playedColor;
        long nextMoves = legalMovesFor(nextColor);
        if (nextMoves != 0L) {
            currentColor = nextColor;
        } else if (legalMovesFor(playedColor) != 0L) {
            passedColor = nextColor;
            currentColor = playedColor;
        } else {
            currentColor = 0;
            gameOver = true;
        }
        return new MoveOutcome(
            playedColor,
            square,
            passedColor,
            gameOver
        );
    }

    private long legalMovesFor(int color) {
        return BitBoard.legalMoves(
            position.player(color),
            position.opponent(color)
        );
    }

    private State snapshot() {
        return new State(
            position,
            currentColor,
            lastSquare,
            lastColor,
            passedColor,
            humanMoves,
            gameOver
        );
    }

    private void restore(State state) {
        position = state.position;
        currentColor = state.currentColor;
        lastSquare = state.lastSquare;
        lastColor = state.lastColor;
        passedColor = state.passedColor;
        humanMoves = state.humanMoves;
        gameOver = state.gameOver;
    }

    private static void checkColor(int color) {
        if (color != BLACK && color != WHITE) {
            throw new IllegalArgumentException("color must be 1 or -1");
        }
    }

    public static final class MoveOutcome {

        private final int playedColor;
        private final int square;
        private final int passedColor;
        private final boolean gameOver;

        private MoveOutcome(
            int playedColor,
            int square,
            int passedColor,
            boolean gameOver
        ) {
            this.playedColor = playedColor;
            this.square = square;
            this.passedColor = passedColor;
            this.gameOver = gameOver;
        }

        public int playedColor() {
            return playedColor;
        }

        public int square() {
            return square;
        }

        public int passedColor() {
            return passedColor;
        }

        public boolean gameOver() {
            return gameOver;
        }
    }

    private static final class State {

        private final BitBoardPosition position;
        private final int currentColor;
        private final int lastSquare;
        private final int lastColor;
        private final int passedColor;
        private final int humanMoves;
        private final boolean gameOver;

        private State(
            BitBoardPosition position,
            int currentColor,
            int lastSquare,
            int lastColor,
            int passedColor,
            int humanMoves,
            boolean gameOver
        ) {
            this.position = position;
            this.currentColor = currentColor;
            this.lastSquare = lastSquare;
            this.lastColor = lastColor;
            this.passedColor = passedColor;
            this.humanMoves = humanMoves;
            this.gameOver = gameOver;
        }
    }
}
