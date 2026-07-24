import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RankingModelScorer {

    private RankingModelScorer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                "Usage: java RankingModelScorer <input.tsv> <output.tsv>"
                    + " <name=model>..."
            );
        }
        Path inputPath = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);
        List<NamedEvaluator> evaluators = new ArrayList<>();
        for (int index = 2; index < args.length; index++) {
            evaluators.add(NamedEvaluator.parse(args[index]));
        }
        Path parent = outputPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        score(inputPath, outputPath, evaluators);
    }

    private static void score(
        Path inputPath,
        Path outputPath,
        List<NamedEvaluator> evaluators
    ) throws Exception {
        try (BufferedReader input = Files.newBufferedReader(
            inputPath,
            StandardCharsets.UTF_8
        ); BufferedWriter output = Files.newBufferedWriter(
            outputPath,
            StandardCharsets.UTF_8
        )) {
            String header = input.readLine();
            if (header == null) {
                throw new IllegalArgumentException("input TSV is empty");
            }
            List<String> fields = Arrays.asList(header.split("\\t", -1));
            int blackIndex = requiredIndex(fields, "child_black");
            int whiteIndex = requiredIndex(fields, "child_white");
            int playerIndex = requiredIndex(fields, "player");
            output.write(header);
            for (NamedEvaluator evaluator : evaluators) {
                String column = "static_" + evaluator.name;
                if (fields.contains(column)) {
                    throw new IllegalArgumentException(
                        "column already exists: " + column
                    );
                }
                output.write("\t" + column);
            }
            output.newLine();

            String line;
            long rows = 0L;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] values = line.split("\\t", -1);
                if (values.length != fields.size()) {
                    throw new IllegalArgumentException(
                        "invalid column count at data row " + (rows + 1L)
                    );
                }
                long black = Long.parseUnsignedLong(
                    values[blackIndex],
                    16
                );
                long white = Long.parseUnsignedLong(
                    values[whiteIndex],
                    16
                );
                int player = Integer.parseInt(values[playerIndex]);
                BitBoardPosition position = new BitBoardPosition(black, white);
                output.write(line);
                for (NamedEvaluator evaluator : evaluators) {
                    int result = evaluator.evaluator.evaluate(
                        position.player(player),
                        position.opponent(player)
                    );
                    output.write("\t" + result);
                }
                output.newLine();
                rows++;
            }
            System.out.println("ranking model scores written: " + rows);
        }
    }

    private static int requiredIndex(List<String> fields, String name) {
        int index = fields.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException("missing column: " + name);
        }
        return index;
    }

    private static final class NamedEvaluator {
        private final String name;
        private final PositionEvaluator evaluator;

        private NamedEvaluator(String name, PositionEvaluator evaluator) {
            this.name = name;
            this.evaluator = evaluator;
        }

        private static NamedEvaluator parse(String value) throws Exception {
            int separator = value.indexOf('=');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("model must be name=path");
            }
            String name = value.substring(0, separator);
            if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(
                    "invalid model name: " + name
                );
            }
            return new NamedEvaluator(
                name,
                LearnedEvaluator.load(
                    Paths.get(value.substring(separator + 1))
                )
            );
        }
    }
}
