import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GameRecord {

    private final String blackName;
    private final String whiteName;
    private final List<GameMove> moves;

    public GameRecord(
        String blackName,
        String whiteName,
        List<GameMove> moves
    ) {
        if (blackName == null || blackName.isEmpty()) {
            throw new IllegalArgumentException("blackName must not be empty");
        }
        if (whiteName == null || whiteName.isEmpty()) {
            throw new IllegalArgumentException("whiteName must not be empty");
        }
        if (moves == null) {
            throw new NullPointerException("moves");
        }

        this.blackName = blackName;
        this.whiteName = whiteName;
        this.moves = Collections.unmodifiableList(new ArrayList<>(moves));
    }

    public String blackName() {
        return blackName;
    }

    public String whiteName() {
        return whiteName;
    }

    public List<GameMove> moves() {
        return moves;
    }
}
