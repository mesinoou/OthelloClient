import java.nio.file.Path;
import java.nio.file.Paths;

final class ClientOptions {

    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 9999;
    static final String DEFAULT_NICKNAME = "Player";
    static final long DEFAULT_TIME_MILLIS = 8000L;

    final String host;
    final int port;
    final String nickname;
    final String threadSpec;
    final long timeMillis;
    final Path evaluationModel;
    final String ttSpec;

    private ClientOptions(
        String host,
        int port,
        String nickname,
        String threadSpec,
        long timeMillis,
        Path evaluationModel,
        String ttSpec
    ) {
        this.host = host;
        this.port = port;
        this.nickname = nickname;
        this.threadSpec = threadSpec;
        this.timeMillis = timeMillis;
        this.evaluationModel = evaluationModel;
        this.ttSpec = ttSpec;
    }

    static ClientOptions parse(String[] args) {
        int positionalCount = 0;
        while (positionalCount < args.length
            && !args[positionalCount].startsWith("--")) {
            positionalCount++;
        }
        if (positionalCount > 6) {
            throw new IllegalArgumentException("位置引数が多すぎます。");
        }

        String host = positionalCount >= 1 ? args[0] : DEFAULT_HOST;
        int port = positionalCount >= 2
            ? parseInt(args[1], "port")
            : DEFAULT_PORT;
        String nickname = positionalCount >= 3
            ? args[2]
            : DEFAULT_NICKNAME;
        String threadSpec = positionalCount >= 4 ? args[3] : "auto";
        long timeMillis = positionalCount >= 5
            ? parseLong(args[4], "timeMillis")
            : DEFAULT_TIME_MILLIS;
        Path evaluationModel = positionalCount >= 6
            ? Paths.get(args[5])
            : null;
        String ttSpec = null;

        for (int index = positionalCount; index < args.length; index++) {
            String option = args[index];
            if ("--tt".equals(option)) {
                if (++index >= args.length) {
                    throw new IllegalArgumentException(
                        "--ttにはautoまたはentry数が必要です。"
                    );
                }
                ttSpec = args[index];
            } else if (option.startsWith("--tt=")) {
                ttSpec = option.substring("--tt=".length());
            } else {
                throw new IllegalArgumentException(
                    "未対応の起動オプションです: " + option
                );
            }
        }

        if (host.isEmpty()
            || port < 1
            || port > 65535
            || timeMillis < 1L) {
            throw new IllegalArgumentException("起動引数の値が範囲外です。");
        }
        if (threadSpec.isEmpty()) {
            throw new IllegalArgumentException(
                "threadsにはautoまたは正整数を指定してください。"
            );
        }
        return new ClientOptions(
            host,
            port,
            nickname,
            threadSpec,
            timeMillis,
            evaluationModel,
            ttSpec
        );
    }

    static void printUsage() {
        System.err.println(
            "使い方: java OthelloClient <host> <port> "
                + "[nickname] [threads|auto] [timeMillis] "
                + "[evaluationModel] [--tt auto|entries]"
        );
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                name + "は整数で指定してください。",
                error
            );
        }
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                name + "は整数で指定してください。",
                error
            );
        }
    }
}
