public interface PositionEvaluator {

    int evaluate(long player, long opponent);

    int terminalScore(long player, long opponent);

    String description();
}
