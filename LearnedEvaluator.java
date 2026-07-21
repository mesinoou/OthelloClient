import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.zip.CRC32;

public final class LearnedEvaluator implements PositionEvaluator {

    static final int MAGIC = 0x4f544556;
    static final int FORMAT_VERSION = 1;
    static final int PATTERN_LAYOUT_VERSION = 1;

    private static final int PHASE_COUNT = 4;
    private static final int TABLE_COUNT = 16;
    private static final int LOCAL_PATTERN_TABLE_COUNT = 9;
    private static final int BOARD_BYTE_COUNT = 8;
    private static final int TERNARY_BYTE_STATES = 6561;
    private static final int[] POWERS_OF_THREE = {
        1, 3, 9, 27, 81, 243, 729, 2187, 6561, 19683
    };
    private static final int DIAGONAL = 0;
    private static final int EDGE_2X = 1;
    private static final int CORNER = 2;
    private static final int LINE_2 = 3;
    private static final int LINE_3 = 4;
    private static final int LINE_4 = 5;
    private static final int SHORT_DIAGONAL_7 = 6;
    private static final int SHORT_DIAGONAL_6 = 7;
    private static final int CORNER_3X3 = 8;
    private static final int MOBILITY = 9;
    private static final int FRONTIER = 10;
    private static final int DISC_DIFFERENCE = 11;
    private static final int CORNER_DIFFERENCE = 12;
    private static final int CORNER_MOVE_DIFFERENCE = 13;
    private static final int STABLE_EDGE_DIFFERENCE = 14;
    private static final int PARITY_ACCESS_DIFFERENCE = 15;

    private static final int[] TABLE_LENGTHS = {
        6561,
        59049,
        59049,
        6561,
        6561,
        6561,
        2187,
        729,
        19683,
        65 * 65,
        65 * 65,
        129,
        9,
        9,
        57,
        65
    };
    private static final int[] TABLE_INSTANCES = {
        2, 4, 8, 4, 4, 4, 4, 4, 8, 1, 1, 1, 1, 1, 1, 1
    };
    private static final int MODEL_BYTES = modelBytes();

    private static final long CORNERS = 0x8100000000000081L;

    private static final int[][] DIAGONAL_PATTERNS = {
        {0, 9, 18, 27, 36, 45, 54, 63},
        {7, 14, 21, 28, 35, 42, 49, 56}
    };
    private static final int[][] EDGE_2X_PATTERNS = {
        {9, 0, 1, 2, 3, 4, 5, 6, 7, 14},
        {9, 0, 8, 16, 24, 32, 40, 48, 56, 49},
        {49, 56, 57, 58, 59, 60, 61, 62, 63, 54},
        {54, 63, 55, 47, 39, 31, 23, 15, 7, 14}
    };
    private static final int[][] CORNER_PATTERNS = {
        {0, 1, 2, 3, 8, 9, 10, 16, 17, 24},
        {0, 8, 16, 24, 1, 9, 17, 2, 10, 3},
        {7, 6, 5, 4, 15, 14, 13, 23, 22, 31},
        {7, 15, 23, 31, 6, 14, 22, 5, 13, 4},
        {63, 62, 61, 60, 55, 54, 53, 47, 46, 39},
        {63, 55, 47, 39, 62, 54, 46, 61, 53, 60},
        {56, 57, 58, 59, 48, 49, 50, 40, 41, 32},
        {56, 48, 40, 32, 57, 49, 41, 58, 50, 59}
    };
    private static final int[][] LINE_2_PATTERNS = straightLines(1);
    private static final int[][] LINE_3_PATTERNS = straightLines(2);
    private static final int[][] LINE_4_PATTERNS = straightLines(3);
    private static final int[][] SHORT_DIAGONAL_7_PATTERNS = {
        {1, 10, 19, 28, 37, 46, 55},
        {8, 17, 26, 35, 44, 53, 62},
        {6, 13, 20, 27, 34, 41, 48},
        {15, 22, 29, 36, 43, 50, 57}
    };
    private static final int[][] SHORT_DIAGONAL_6_PATTERNS = {
        {2, 11, 20, 29, 38, 47},
        {16, 25, 34, 43, 52, 61},
        {5, 12, 19, 26, 33, 40},
        {23, 30, 37, 44, 51, 58}
    };
    private static final int[][] CORNER_3X3_PATTERNS = {
        {0, 1, 2, 8, 9, 10, 16, 17, 18},
        {0, 8, 16, 1, 9, 17, 2, 10, 18},
        {7, 6, 5, 15, 14, 13, 23, 22, 21},
        {7, 15, 23, 6, 14, 22, 5, 13, 21},
        {63, 62, 61, 55, 54, 53, 47, 46, 45},
        {63, 55, 47, 62, 54, 46, 61, 53, 45},
        {56, 57, 58, 48, 49, 50, 40, 41, 42},
        {56, 48, 40, 57, 49, 41, 58, 50, 42}
    };
    private static final char[] BYTE_TERNARY_CODES = buildByteTernaryCodes();
    private static final PatternIndexLookup[] PATTERN_INDEX_LOOKUPS =
        buildPatternIndexLookups();

    private final int[] phaseStarts;
    private final PhaseTables[] phases;
    private final int scoreScale;
    private final int scoreDivisor;
    private final int maximumAbsoluteScoreBound;
    private final Path sourcePath;
    private final ThreadLocal<byte[]> patternStates = ThreadLocal.withInitial(
        () -> new byte[64]
    );
    private final ThreadLocal<int[]> patternChunkStates = ThreadLocal.withInitial(
        () -> new int[BOARD_BYTE_COUNT]
    );

    private LearnedEvaluator(
        int[] phaseStarts,
        PhaseTables[] phases,
        int scoreScale,
        int scoreDivisor,
        int maximumAbsoluteScoreBound,
        Path sourcePath
    ) {
        this.phaseStarts = phaseStarts;
        this.phases = phases;
        this.scoreScale = scoreScale;
        this.scoreDivisor = scoreDivisor;
        this.maximumAbsoluteScoreBound = maximumAbsoluteScoreBound;
        this.sourcePath = sourcePath;
    }

    public static PositionEvaluator loadDefault() {
        String configured = System.getProperty("othello.eval.file");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("OTHELLO_EVAL_FILE");
        }
        Path path = configured == null || configured.isBlank()
            ? Paths.get("data", "evaluation-tables.bin")
            : Paths.get(configured);
        if (!Files.isRegularFile(path)) {
            if (configured != null && !configured.isBlank()) {
                System.err.println(
                    "学習済み評価モデルが見つからないため従来評価を使用します: "
                        + path
                );
            }
            return new Evaluator();
        }
        try {
            return load(path);
        } catch (IOException | IllegalArgumentException error) {
            System.err.println(
                "学習済み評価モデルを読み込めないため従来評価を使用します: "
                    + error.getMessage()
            );
            return new Evaluator();
        }
    }

    public static LearnedEvaluator load(Path path) throws IOException {
        if (path == null) {
            throw new NullPointerException("path");
        }
        long fileSize = Files.size(path);
        if (fileSize != MODEL_BYTES) {
            throw new IOException("invalid learned model file size: " + fileSize);
        }
        byte[] data = Files.readAllBytes(path);
        int payloadLength = data.length - Integer.BYTES;
        long expectedChecksum = Integer.toUnsignedLong(
            ByteBuffer.wrap(data, payloadLength, Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt()
        );
        CRC32 crc32 = new CRC32();
        crc32.update(data, 0, payloadLength);
        if (crc32.getValue() != expectedChecksum) {
            throw new IOException("learned model CRC32 mismatch");
        }

        ByteBuffer input = ByteBuffer.wrap(data, 0, payloadLength)
            .order(ByteOrder.BIG_ENDIAN);
        require(input.getInt() == MAGIC, "invalid learned model magic");
        int version = input.getInt();
        require(
            version == FORMAT_VERSION,
            "unsupported learned model version: " + version
        );
        require(
            input.getInt() == PATTERN_LAYOUT_VERSION,
            "unsupported learned model pattern layout"
        );
        require(input.getInt() == PHASE_COUNT, "invalid learned model phases");
        require(input.getInt() == TABLE_COUNT, "invalid learned model tables");
        int scoreScale = input.getInt();
        int scoreDivisor = input.getInt();
        int maximumBound = input.getInt();
        require(scoreScale > 0, "invalid learned model score scale");
        require(scoreDivisor > 0, "invalid learned model score divisor");
        require(maximumBound >= 0, "invalid learned model score bound");
        require(
            maximumBound / scoreDivisor < Evaluator.WIN_SCORE,
            "learned model score overlaps terminal score"
        );

        int[] phaseStarts = new int[PHASE_COUNT];
        for (int phase = 0; phase < PHASE_COUNT; phase++) {
            phaseStarts[phase] = input.getInt();
            if (phase > 0) {
                require(
                    phaseStarts[phase] > phaseStarts[phase - 1],
                    "learned model phase starts are not ascending"
                );
            }
        }

        PhaseTables[] phases = new PhaseTables[PHASE_COUNT];
        int actualMaximumBound = 0;
        for (int phase = 0; phase < PHASE_COUNT; phase++) {
            int bias = input.getInt();
            long phaseBound = Math.abs((long) bias);
            short[][] tables = new short[TABLE_COUNT][];
            for (int table = 0; table < TABLE_COUNT; table++) {
                int length = input.getInt();
                require(
                    length == TABLE_LENGTHS[table],
                    "invalid learned model table length at phase "
                        + phase + ", table " + table
                );
                require(
                    input.remaining() >= length * Short.BYTES,
                    "truncated learned model table"
                );
                short[] values = new short[length];
                int tableMaximum = 0;
                for (int index = 0; index < length; index++) {
                    values[index] = input.getShort();
                    tableMaximum = Math.max(
                        tableMaximum,
                        Math.abs((int) values[index])
                    );
                }
                tables[table] = values;
                phaseBound += (long) tableMaximum * TABLE_INSTANCES[table];
            }
            require(
                phaseBound <= Integer.MAX_VALUE,
                "learned model score bound overflows int32"
            );
            actualMaximumBound = Math.max(actualMaximumBound, (int) phaseBound);
            phases[phase] = new PhaseTables(bias, tables);
        }
        require(!input.hasRemaining(), "unexpected learned model trailing data");
        require(
            actualMaximumBound == maximumBound,
            "learned model score bound mismatch"
        );
        return new LearnedEvaluator(
            phaseStarts,
            phases,
            scoreScale,
            scoreDivisor,
            maximumBound,
            path.toAbsolutePath().normalize()
        );
    }

    @Override
    public int evaluate(long player, long opponent) {
        long occupied = player | opponent;
        int occupiedCount = Long.bitCount(occupied);
        int empties = 64 - occupiedCount;
        PhaseTables phase = phases[phaseForPly(occupiedCount - 4)];
        short[][] tables = phase.tables;

        int score = phase.bias;
        score += chunkedPatternScore(
            tables,
            player,
            opponent,
            patternChunkStates.get()
        );

        long playerMoves = BitBoard.legalMoves(player, opponent);
        long opponentMoves = BitBoard.legalMoves(opponent, player);
        int playerMobility = Long.bitCount(playerMoves);
        int opponentMobility = Long.bitCount(opponentMoves);
        score += tables[MOBILITY][playerMobility * 65 + opponentMobility];

        long adjacentToEmpty = Evaluator.neighbors(~occupied);
        int playerFrontier = Long.bitCount(player & adjacentToEmpty);
        int opponentFrontier = Long.bitCount(opponent & adjacentToEmpty);
        score += tables[FRONTIER][playerFrontier * 65 + opponentFrontier];

        int discDifference = Long.bitCount(player) - Long.bitCount(opponent);
        int cornerDifference = Long.bitCount(player & CORNERS)
            - Long.bitCount(opponent & CORNERS);
        int cornerMoveDifference = Long.bitCount(playerMoves & CORNERS)
            - Long.bitCount(opponentMoves & CORNERS);
        int stableEdgeDifference = Long.bitCount(
            Evaluator.stableEdgeDiscs(player, occupied)
        ) - Long.bitCount(Evaluator.stableEdgeDiscs(opponent, occupied));
        int parityDifference = empties <= 20
            ? Evaluator.parityAccessDifference(
                player,
                opponent,
                playerMoves,
                opponentMoves
            )
            : 0;
        score += tables[DISC_DIFFERENCE][clamp(discDifference, -64, 64) + 64];
        score += tables[CORNER_DIFFERENCE][clamp(cornerDifference, -4, 4) + 4];
        score += tables[CORNER_MOVE_DIFFERENCE][
            clamp(cornerMoveDifference, -4, 4) + 4
        ];
        score += tables[STABLE_EDGE_DIFFERENCE][
            clamp(stableEdgeDifference, -28, 28) + 28
        ];
        score += tables[PARITY_ACCESS_DIFFERENCE][
            clamp(parityDifference, -32, 32) + 32
        ];
        return scoreDivisor == 1 ? score : score / scoreDivisor;
    }

    @Override
    public int terminalScore(long player, long opponent) {
        int difference = Long.bitCount(player) - Long.bitCount(opponent);
        if (difference > 0) {
            return Evaluator.WIN_SCORE + difference;
        }
        if (difference < 0) {
            return -Evaluator.WIN_SCORE + difference;
        }
        return 0;
    }

    @Override
    public String description() {
        return "learned-table-v" + FORMAT_VERSION
            + " (scoreScale=" + scoreScale
            + ", divisor=" + scoreDivisor
            + ", bound=" + maximumAbsoluteScoreBound
            + ", path=" + sourcePath + ")";
    }

    static int encodePattern(
        long player,
        long opponent,
        int[] squares
    ) {
        int index = 0;
        for (int square : squares) {
            long bit = 1L << square;
            int state = (player & bit) != 0L
                ? 1
                : (opponent & bit) != 0L ? 2 : 0;
            index = index * 3 + state;
        }
        return index;
    }

    static int ternaryByteCode(int playerByte, int opponentByte) {
        if ((playerByte & ~0xff) != 0 || (opponentByte & ~0xff) != 0) {
            throw new IllegalArgumentException("byte board is out of range");
        }
        return BYTE_TERNARY_CODES[playerByte | (opponentByte << 8)];
    }

    static long patternLookupDataBytes() {
        long bytes = (long) BYTE_TERNARY_CODES.length * Character.BYTES;
        for (PatternIndexLookup lookup : PATTERN_INDEX_LOOKUPS) {
            bytes += lookup.dataBytes();
        }
        return bytes;
    }

    int chunkedPatternScore(long player, long opponent) {
        PhaseTables phase = phases[phaseForPly(
            Long.bitCount(player | opponent) - 4
        )];
        return chunkedPatternScore(
            phase.tables,
            player,
            opponent,
            patternChunkStates.get()
        );
    }

    int referencePatternScore(long player, long opponent) {
        PhaseTables phase = phases[phaseForPly(
            Long.bitCount(player | opponent) - 4
        )];
        byte[] states = patternStates.get();
        fillPatternStates(states, player, opponent);
        return referencePatternScore(phase.tables, states);
    }

    private int phaseForPly(int ply) {
        if (ply < phaseStarts[1]) {
            return 0;
        }
        if (ply < phaseStarts[2]) {
            return 1;
        }
        if (ply < phaseStarts[3]) {
            return 2;
        }
        return 3;
    }

    private static int patternScore(
        short[] table,
        byte[] states,
        int[][] patterns
    ) {
        int score = 0;
        for (int[] pattern : patterns) {
            int tableIndex = 0;
            for (int square : pattern) {
                tableIndex = tableIndex * 3 + states[square];
            }
            score += table[tableIndex];
        }
        return score;
    }

    private static int referencePatternScore(
        short[][] tables,
        byte[] states
    ) {
        return patternScore(tables[DIAGONAL], states, DIAGONAL_PATTERNS)
            + patternScore(tables[EDGE_2X], states, EDGE_2X_PATTERNS)
            + patternScore(tables[CORNER], states, CORNER_PATTERNS)
            + patternScore(tables[LINE_2], states, LINE_2_PATTERNS)
            + patternScore(tables[LINE_3], states, LINE_3_PATTERNS)
            + patternScore(tables[LINE_4], states, LINE_4_PATTERNS)
            + patternScore(
                tables[SHORT_DIAGONAL_7],
                states,
                SHORT_DIAGONAL_7_PATTERNS
            )
            + patternScore(
                tables[SHORT_DIAGONAL_6],
                states,
                SHORT_DIAGONAL_6_PATTERNS
            )
            + patternScore(
                tables[CORNER_3X3],
                states,
                CORNER_3X3_PATTERNS
            );
    }

    private static int chunkedPatternScore(
        short[][] tables,
        long player,
        long opponent,
        int[] chunkStates
    ) {
        for (int chunk = 0; chunk < BOARD_BYTE_COUNT; chunk++) {
            int shift = chunk * Byte.SIZE;
            int playerByte = (int) (player >>> shift) & 0xff;
            int opponentByte = (int) (opponent >>> shift) & 0xff;
            chunkStates[chunk] = BYTE_TERNARY_CODES[
                playerByte | (opponentByte << 8)
            ];
        }

        int score = 0;
        for (PatternIndexLookup lookup : PATTERN_INDEX_LOOKUPS) {
            score += tables[lookup.table][lookup.index(chunkStates)];
        }
        return score;
    }

    private static char[] buildByteTernaryCodes() {
        char[] result = new char[1 << 16];
        for (int key = 0; key < result.length; key++) {
            int playerByte = key & 0xff;
            int opponentByte = key >>> 8;
            int code = 0;
            for (int offset = 0; offset < Byte.SIZE; offset++) {
                int mask = 1 << offset;
                int state = (opponentByte & mask) != 0
                    ? 2
                    : (playerByte & mask) != 0 ? 1 : 0;
                code += state * POWERS_OF_THREE[offset];
            }
            result[key] = (char) code;
        }
        return result;
    }

    private static PatternIndexLookup[] buildPatternIndexLookups() {
        int count = 0;
        for (int table = 0; table < LOCAL_PATTERN_TABLE_COUNT; table++) {
            count += TABLE_INSTANCES[table];
        }
        PatternIndexLookup[] result = new PatternIndexLookup[count];
        int offset = 0;
        offset = appendPatternLookups(
            result,
            offset,
            DIAGONAL,
            DIAGONAL_PATTERNS
        );
        offset = appendPatternLookups(
            result,
            offset,
            EDGE_2X,
            EDGE_2X_PATTERNS
        );
        offset = appendPatternLookups(
            result,
            offset,
            CORNER,
            CORNER_PATTERNS
        );
        offset = appendPatternLookups(
            result,
            offset,
            LINE_2,
            LINE_2_PATTERNS
        );
        offset = appendPatternLookups(
            result,
            offset,
            LINE_3,
            LINE_3_PATTERNS
        );
        offset = appendPatternLookups(
            result,
            offset,
            LINE_4,
            LINE_4_PATTERNS
        );
        offset = appendPatternLookups(
            result,
            offset,
            SHORT_DIAGONAL_7,
            SHORT_DIAGONAL_7_PATTERNS
        );
        offset = appendPatternLookups(
            result,
            offset,
            SHORT_DIAGONAL_6,
            SHORT_DIAGONAL_6_PATTERNS
        );
        offset = appendPatternLookups(
            result,
            offset,
            CORNER_3X3,
            CORNER_3X3_PATTERNS
        );
        if (offset != result.length) {
            throw new AssertionError("pattern lookup count mismatch");
        }
        return result;
    }

    private static int appendPatternLookups(
        PatternIndexLookup[] target,
        int offset,
        int table,
        int[][] patterns
    ) {
        for (int[] pattern : patterns) {
            target[offset++] = new PatternIndexLookup(table, pattern);
        }
        return offset;
    }

    private static void fillPatternStates(
        byte[] states,
        long player,
        long opponent
    ) {
        Arrays.fill(states, (byte) 0);
        long remaining = player;
        while (remaining != 0L) {
            long bit = remaining & -remaining;
            states[Long.numberOfTrailingZeros(bit)] = 1;
            remaining ^= bit;
        }
        remaining = opponent;
        while (remaining != 0L) {
            long bit = remaining & -remaining;
            states[Long.numberOfTrailingZeros(bit)] = 2;
            remaining ^= bit;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int[][] straightLines(int distance) {
        int[][] result = new int[4][8];
        int low = distance;
        int high = 7 - distance;
        for (int index = 0; index < 8; index++) {
            result[0][index] = low * 8 + index;
            result[1][index] = high * 8 + index;
            result[2][index] = index * 8 + low;
            result[3][index] = index * 8 + high;
        }
        return result;
    }

    private static int modelBytes() {
        int tableDataBytes = 0;
        for (int length : TABLE_LENGTHS) {
            tableDataBytes += length * Short.BYTES;
        }
        int headerBytes = 8 * Integer.BYTES
            + PHASE_COUNT * Integer.BYTES;
        int phaseBytes = Integer.BYTES
            + TABLE_COUNT * Integer.BYTES
            + tableDataBytes;
        return headerBytes + PHASE_COUNT * phaseBytes + Integer.BYTES;
    }

    private static void require(boolean condition, String message)
        throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    private static final class PhaseTables {
        private final int bias;
        private final short[][] tables;

        private PhaseTables(int bias, short[][] tables) {
            this.bias = bias;
            this.tables = tables;
        }
    }

    private static final class PatternIndexLookup {

        private final int table;
        private final byte[] chunks;
        private final char[][] contributions;

        private PatternIndexLookup(int table, int[] pattern) {
            this.table = table;
            int[][] weights = new int[BOARD_BYTE_COUNT][Byte.SIZE];
            boolean[] usedChunks = new boolean[BOARD_BYTE_COUNT];
            int usedCount = 0;
            for (int index = 0; index < pattern.length; index++) {
                int square = pattern[index];
                int chunk = square >>> 3;
                int offset = square & 7;
                weights[chunk][offset] = POWERS_OF_THREE[
                    pattern.length - index - 1
                ];
                if (!usedChunks[chunk]) {
                    usedChunks[chunk] = true;
                    usedCount++;
                }
            }

            chunks = new byte[usedCount];
            contributions = new char[usedCount][];
            int target = 0;
            for (int chunk = 0; chunk < BOARD_BYTE_COUNT; chunk++) {
                if (!usedChunks[chunk]) {
                    continue;
                }
                chunks[target] = (byte) chunk;
                contributions[target] = buildContributions(weights[chunk]);
                target++;
            }
        }

        private int index(int[] chunkStates) {
            int index = 0;
            for (int i = 0; i < chunks.length; i++) {
                int chunk = Byte.toUnsignedInt(chunks[i]);
                index += contributions[i][chunkStates[chunk]];
            }
            return index;
        }

        private long dataBytes() {
            long bytes = chunks.length;
            for (char[] contribution : contributions) {
                bytes += (long) contribution.length * Character.BYTES;
            }
            return bytes;
        }

        private static char[] buildContributions(int[] weights) {
            char[] result = new char[TERNARY_BYTE_STATES];
            for (int code = 0; code < result.length; code++) {
                int remaining = code;
                int contribution = 0;
                for (int offset = 0; offset < Byte.SIZE; offset++) {
                    int state = remaining % 3;
                    remaining /= 3;
                    contribution += state * weights[offset];
                }
                result[code] = (char) contribution;
            }
            return result;
        }
    }
}
