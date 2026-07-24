public final class ColorSelectableEvaluatorTest {

    private ColorSelectableEvaluatorTest() {
    }

    public static void main(String[] args) {
        testColorSelection();
        testAiColorSelection();
        testClientOptions();
        testTranspositionClear();
        System.out.println("ColorSelectableEvaluatorTest: PASS");
    }

    private static void testColorSelection() {
        ColorSelectableEvaluator evaluator = new ColorSelectableEvaluator(
            new ConstantEvaluator(11, "black"),
            new ConstantEvaluator(22, "white")
        );
        assertEquals(1, evaluator.selectedColor(), "initial color");
        assertEquals(11, evaluator.evaluate(1L, 2L), "black value");
        if (evaluator.selectColor(1)) {
            throw new AssertionError("same color must not report a change");
        }
        if (!evaluator.selectColor(-1)) {
            throw new AssertionError("white selection must report a change");
        }
        assertEquals(-1, evaluator.selectedColor(), "selected white");
        assertEquals(22, evaluator.evaluate(1L, 2L), "white value");
        assertEquals(1022, evaluator.terminalScore(1L, 2L), "white terminal");
        try {
            evaluator.selectColor(0);
            throw new AssertionError("invalid color was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void testTranspositionClear() {
        TranspositionTable table = new TranspositionTable(16);
        table.store(1L, 2L, 4, 7, TranspositionTable.EXACT, 3);
        if (!TranspositionTable.probeFound(table.probe(1L, 2L))) {
            throw new AssertionError("stored entry was not found");
        }
        table.clear();
        if (TranspositionTable.probeFound(table.probe(1L, 2L))) {
            throw new AssertionError("clear retained an entry");
        }
    }

    private static void testAiColorSelection() {
        OthelloAI ai = OthelloAI.create(
            new ConstantEvaluator(11, "black"),
            new ConstantEvaluator(22, "white"),
            16
        );
        try {
            if (!ai.evaluatorDescription().contains("active=black")) {
                throw new AssertionError("AI did not start with black model");
            }
            ai.selectPlayerColor(-1);
            if (!ai.evaluatorDescription().contains("active=white")) {
                throw new AssertionError("AI did not select white model");
            }
        } finally {
            ai.shutdown();
        }
    }

    private static void testClientOptions() {
        ClientOptions options = ClientOptions.parse(new String[] {
            "127.0.0.1",
            "25033",
            "Player",
            "auto",
            "8000",
            "default.bin",
            "--black-model",
            "black.bin",
            "--white-model=white.bin"
        });
        if (!"black.bin".equals(options.blackEvaluationModel.toString())) {
            throw new AssertionError("black model option was not parsed");
        }
        if (!"white.bin".equals(options.whiteEvaluationModel.toString())) {
            throw new AssertionError("white model option was not parsed");
        }
    }

    private static void assertEquals(
        int expected,
        int actual,
        String message
    ) {
        if (expected != actual) {
            throw new AssertionError(
                message + ": expected " + expected + ", got " + actual
            );
        }
    }

    private static final class ConstantEvaluator
        implements PositionEvaluator {

        private final int value;
        private final String name;

        private ConstantEvaluator(int value, String name) {
            this.value = value;
            this.name = name;
        }

        @Override
        public int evaluate(long player, long opponent) {
            return value;
        }

        @Override
        public int terminalScore(long player, long opponent) {
            return 1000 + value;
        }

        @Override
        public String description() {
            return name;
        }
    }
}
