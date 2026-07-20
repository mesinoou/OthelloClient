import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GameReplayer {

    private GameReplayer() {
    }

    public static GameRecord read(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(
            path,
            StandardCharsets.UTF_8
        )) {
            return read(reader, path.toString());
        }
    }

    public static GameRecord read(Reader sourceReader, String sourceName)
        throws IOException {
        BufferedReader reader = sourceReader instanceof BufferedReader
            ? (BufferedReader) sourceReader
            : new BufferedReader(sourceReader);

        String header = reader.readLine();
        if (header == null) {
            throw formatError(sourceName, 1, "empty game log");
        }
        if (!header.isEmpty() && header.charAt(0) == '\ufeff') {
            header = header.substring(1);
        }

        int whiteMarker = header.indexOf(",W:");
        if (!header.startsWith("B:") || whiteMarker < 3) {
            throw formatError(
                sourceName,
                1,
                "expected header B:<black>,W:<white>"
            );
        }

        String blackName = header.substring(2, whiteMarker);
        String whiteName = header.substring(whiteMarker + 3);
        if (blackName.isEmpty() || whiteName.isEmpty()) {
            throw formatError(sourceName, 1, "player name is empty");
        }

        List<GameMove> moves = new ArrayList<>();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.trim().isEmpty()) {
                continue;
            }

            String[] parts = line.trim().split(",", -1);
            if (parts.length != 3) {
                throw formatError(
                    sourceName,
                    lineNumber,
                    "expected move x,y,color"
                );
            }

            try {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int color = Integer.parseInt(parts[2].trim());
                moves.add(new GameMove(x, y, color));
            } catch (NumberFormatException e) {
                throw formatError(
                    sourceName,
                    lineNumber,
                    "move contains a non-integer value"
                );
            } catch (IllegalArgumentException e) {
                throw formatError(sourceName, lineNumber, e.getMessage());
            }
        }

        return new GameRecord(blackName, whiteName, moves);
    }

    public static GameReplayResult replay(GameRecord game) {
        return replay(game, BitBoardPosition.initial(), 1);
    }

    static GameReplayResult replay(
        GameRecord game,
        BitBoardPosition initialPosition,
        int startingColor
    ) {
        if (game == null) {
            throw new NullPointerException("game");
        }
        if (initialPosition == null) {
            throw new NullPointerException("initialPosition");
        }
        if (startingColor != 1 && startingColor != -1) {
            throw new IllegalArgumentException(
                "startingColor must be 1 or -1"
            );
        }

        long black = initialPosition.black();
        long white = initialPosition.white();
        int currentColor = startingColor;
        int passCount = 0;

        for (int index = 0; index < game.moves().size(); index++) {
            GameMove move = game.moves().get(index);
            long player = currentColor == 1 ? black : white;
            long opponent = currentColor == 1 ? white : black;
            long legalMoves = BitBoard.legalMoves(player, opponent);

            if (legalMoves == 0L) {
                long opponentMoves = BitBoard.legalMoves(opponent, player);
                if (opponentMoves == 0L) {
                    throw replayError(index, "move appears after game over");
                }
                passCount++;
                currentColor = -currentColor;
                player = currentColor == 1 ? black : white;
                opponent = currentColor == 1 ? white : black;
                legalMoves = opponentMoves;
            }

            if (move.color() != currentColor) {
                throw replayError(
                    index,
                    "expected color " + currentColor
                        + " but found " + move.color()
                );
            }

            long moveBit = move.bit();
            if ((legalMoves & moveBit) == 0L) {
                throw replayError(index, "illegal move " + move);
            }

            long flips = BitBoard.flips(player, opponent, moveBit);
            long movedPlayer = BitBoard.applyPlayerBoard(
                player,
                moveBit,
                flips
            );
            long movedOpponent = BitBoard.applyOpponentBoard(opponent, flips);

            if (currentColor == 1) {
                black = movedPlayer;
                white = movedOpponent;
            } else {
                white = movedPlayer;
                black = movedOpponent;
            }
            currentColor = -currentColor;
        }

        if (!BitBoard.isGameOver(
            currentColor == 1 ? black : white,
            currentColor == 1 ? white : black
        )) {
            throw new IllegalArgumentException(
                "game log ended before the position reached game over"
            );
        }

        return new GameReplayResult(
            game,
            new BitBoardPosition(black, white),
            passCount
        );
    }

    private static IllegalArgumentException formatError(
        String source,
        int line,
        String message
    ) {
        return new IllegalArgumentException(
            source + ":" + line + ": " + message
        );
    }

    private static IllegalArgumentException replayError(
        int moveIndex,
        String message
    ) {
        return new IllegalArgumentException(
            "move " + (moveIndex + 1) + ": " + message
        );
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("使い方: java GameReplayer <gamelog.txt> [...]");
            return;
        }

        boolean failed = false;
        for (String argument : args) {
            Path path = Path.of(argument);
            try {
                GameReplayResult result = replay(read(path));
                System.out.println(
                    path + ": PASS"
                        + " moves=" + result.moveCount()
                        + " passes=" + result.passCount()
                        + " " + result.game().blackName()
                        + "=" + result.blackCount()
                        + " " + result.game().whiteName()
                        + "=" + result.whiteCount()
                        + " winner=" + result.winnerName()
                );
            } catch (IOException | IllegalArgumentException e) {
                failed = true;
                System.err.println(path + ": FAIL: " + e.getMessage());
            }
        }

        if (failed) {
            System.exit(1);
        }
    }
}
