import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

public final class MpcCalibrationRunner {

    private static final int[][] PHASE_PLY_RANGES = {
        {20, 29},
        {30, 39},
        {40, 41}
    };
    private static final int[] DEPTHS = {6, 8, 10};
    private static final int MAX_ATTEMPTS = 1_000_000;

    private MpcCalibrationRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException(
                "Usage: java MpcCalibrationRunner <model> <output.csv> "
                    + "[samplesPerPhase] [seed]"
            );
        }
        Path modelPath = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);
        int samplesPerPhase = args.length >= 3
            ? Integer.parseInt(args[2])
            : 64;
        long seed = args.length >= 4
            ? Long.parseLong(args[3])
            : 20260721L;
        if (samplesPerPhase < 1 || samplesPerPhase > 10_000) {
            throw new IllegalArgumentException(
                "samplesPerPhase must be 1..10000"
            );
        }
        run(modelPath, outputPath, samplesPerPhase, seed);
    }

    private static void run(
        Path modelPath,
        Path outputPath,
        int samplesPerPhase,
        long seed
    ) throws Exception {
        PositionEvaluator evaluator = LearnedEvaluator.load(modelPath);
        Path parent = outputPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter output = Files.newBufferedWriter(
            outputPath,
            StandardCharsets.UTF_8
        )) {
            output.write(
                "seed,phase,sample,ply,player,opponent,depth,"
                    + "shallowDepth,shallowScore,deepScore"
            );
            output.newLine();
            for (int phase = 0; phase < PHASE_PLY_RANGES.length; phase++) {
                collectPhase(
                    evaluator,
                    output,
                    phase,
                    samplesPerPhase,
                    seed
                );
            }
        }
    }

    private static void collectPhase(
        PositionEvaluator evaluator,
        BufferedWriter output,
        int phase,
        int sampleCount,
        long seed
    ) throws Exception {
        Random random = new Random(
            seed + 0x9e3779b97f4a7c15L * (phase + 1L)
        );
        Set<String> seen = new HashSet<>();
        SearchEngine engine = new SearchEngine(evaluator, null);
        try {
            int generated = 0;
            int attempts = 0;
            while (generated < sampleCount) {
                if (++attempts > MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                        "could not generate phase " + phase + " positions"
                    );
                }
                int minimumPly = PHASE_PLY_RANGES[phase][0];
                int maximumPly = PHASE_PLY_RANGES[phase][1];
                int targetPly = minimumPly + random.nextInt(
                    maximumPly - minimumPly + 1
                );
                PositionToMove sample = randomPlayout(random, targetPly);
                if (sample == null) {
                    continue;
                }
                long player = sample.position.player(sample.color);
                long opponent = sample.position.opponent(sample.color);
                if (Long.bitCount(BitBoard.legalMoves(player, opponent)) < 3) {
                    continue;
                }
                String key = player + ":" + opponent;
                if (!seen.add(key)) {
                    continue;
                }
                int[] scores = new int[11];
                for (int depth = 4; depth <= 10; depth += 2) {
                    SearchResult result = engine.search(
                        sample.position,
                        sample.color,
                        new SearchLimits(30_000L, depth, 1)
                    );
                    if (result.completedDepth() != depth
                        || result.timedOut()) {
                        throw new IllegalStateException(
                            "calibration search did not complete"
                        );
                    }
                    scores[depth] = result.score();
                }
                for (int depth : DEPTHS) {
                    output.write(String.format(
                        Locale.ROOT,
                        "%d,%d,%d,%d,%016x,%016x,%d,%d,%d,%d%n",
                        seed,
                        phase,
                        generated,
                        targetPly,
                        player,
                        opponent,
                        depth,
                        depth - 2,
                        scores[depth - 2],
                        scores[depth]
                    ));
                }
                generated++;
                if (generated % 16 == 0 || generated == sampleCount) {
                    System.out.printf(
                        Locale.ROOT,
                        "phase=%d samples=%d/%d%n",
                        phase,
                        generated,
                        sampleCount
                    );
                }
            }
        } finally {
            engine.shutdown();
        }
    }

    private static PositionToMove randomPlayout(
        Random random,
        int targetPly
    ) {
        BitBoardPosition position = BitBoardPosition.initial();
        int color = 1;
        int played = 0;
        while (played < targetPly) {
            long player = position.player(color);
            long opponent = position.opponent(color);
            long legalMoves = BitBoard.legalMoves(player, opponent);
            if (legalMoves == 0L) {
                if (BitBoard.legalMoves(opponent, player) == 0L) {
                    return null;
                }
                color = -color;
                continue;
            }
            int selected = random.nextInt(Long.bitCount(legalMoves));
            long remaining = legalMoves;
            while (selected-- > 0) {
                remaining &= remaining - 1L;
            }
            long move = remaining & -remaining;
            long flips = BitBoard.flips(player, opponent, move);
            long nextPlayer = BitBoard.applyPlayerBoard(player, move, flips);
            long nextOpponent = BitBoard.applyOpponentBoard(opponent, flips);
            long black = color == 1 ? nextPlayer : nextOpponent;
            long white = color == 1 ? nextOpponent : nextPlayer;
            position = new BitBoardPosition(black, white);
            color = -color;
            played++;
        }
        return new PositionToMove(position, color);
    }

    private static final class PositionToMove {
        private final BitBoardPosition position;
        private final int color;

        private PositionToMove(BitBoardPosition position, int color) {
            this.position = position;
            this.color = color;
        }
    }
}
