final class ColorSelectableEvaluator implements PositionEvaluator {

    private final PositionEvaluator blackEvaluator;
    private final PositionEvaluator whiteEvaluator;
    private volatile PositionEvaluator selectedEvaluator;
    private volatile int selectedColor;

    ColorSelectableEvaluator(
        PositionEvaluator blackEvaluator,
        PositionEvaluator whiteEvaluator
    ) {
        if (blackEvaluator == null) {
            throw new NullPointerException("blackEvaluator");
        }
        if (whiteEvaluator == null) {
            throw new NullPointerException("whiteEvaluator");
        }
        this.blackEvaluator = blackEvaluator;
        this.whiteEvaluator = whiteEvaluator;
        selectedEvaluator = blackEvaluator;
        selectedColor = 1;
    }

    boolean selectColor(int color) {
        if (color != 1 && color != -1) {
            throw new IllegalArgumentException("color must be 1 or -1");
        }
        if (selectedColor == color) {
            return false;
        }
        selectedEvaluator = color == 1 ? blackEvaluator : whiteEvaluator;
        selectedColor = color;
        return true;
    }

    int selectedColor() {
        return selectedColor;
    }

    @Override
    public int evaluate(long player, long opponent) {
        return selectedEvaluator.evaluate(player, opponent);
    }

    @Override
    public int terminalScore(long player, long opponent) {
        return selectedEvaluator.terminalScore(player, opponent);
    }

    @Override
    public String description() {
        return "color-selectable(active="
            + (selectedColor == 1 ? "black" : "white")
            + ", black=" + blackEvaluator.description()
            + ", white=" + whiteEvaluator.description() + ")";
    }
}
