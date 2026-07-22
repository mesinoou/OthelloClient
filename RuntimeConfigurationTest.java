import java.util.Arrays;
import java.util.List;

public final class RuntimeConfigurationTest {

    private RuntimeConfigurationTest() {
    }

    public static void main(String[] args) {
        testClientOptions();
        testTtSizing();
        testThreadCandidates();
        testConservativeSelection();
        System.out.println("RuntimeConfigurationTest: PASS");
    }

    private static void testClientOptions() {
        ClientOptions defaults = ClientOptions.parse(new String[0]);
        assertEquals("auto", defaults.threadSpec, "default threads");
        if (defaults.ttSpec != null) {
            throw new AssertionError("default TT must defer to property/auto");
        }

        ClientOptions explicit = ClientOptions.parse(new String[] {
            "localhost", "25033", "Test", "4", "8000", "model.bin",
            "--tt", "524288"
        });
        assertEquals("4", explicit.threadSpec, "explicit threads");
        assertEquals("524288", explicit.ttSpec, "explicit TT");
        assertEquals(25033, explicit.port, "port");

        expectFailure(() -> ClientOptions.parse(new String[] {
            "localhost", "25033", "Test", "4", "8000", "model.bin",
            "--unknown"
        }));
    }

    private static void testTtSizing() {
        int heap64 = RuntimeConfiguration.autoTtEntries(64L << 20);
        int heap256 = RuntimeConfiguration.autoTtEntries(256L << 20);
        int heap1024 = RuntimeConfiguration.autoTtEntries(1024L << 20);
        assertEquals(1 << 18, heap64, "64 MiB TT");
        assertEquals(1 << 19, heap256, "256 MiB TT");
        assertEquals(1 << 21, heap1024, "1 GiB TT");
        if (TranspositionTable.estimatedBytes(heap1024) > 64L << 20) {
            throw new AssertionError("1 GiB TT exceeds its budget");
        }
    }

    private static void testThreadCandidates() {
        assertListEquals(Arrays.asList(1), threadCandidates(1));
        assertListEquals(Arrays.asList(1, 2), threadCandidates(3));
        assertListEquals(Arrays.asList(1, 2, 4), threadCandidates(6));
        assertListEquals(Arrays.asList(1, 2, 4, 8), threadCandidates(32));
    }

    private static void testConservativeSelection() {
        RuntimeConfiguration.ThreadMeasurement one = measurement(
            1, 9, 100_000_000L
        );
        RuntimeConfiguration.ThreadMeasurement twoSmallGain = measurement(
            2, 9, 96_000_000L
        );
        assertEquals(
            1,
            RuntimeConfiguration.chooseThreads(Arrays.asList(
                one,
                twoSmallGain
            )),
            "under-five-percent tie"
        );

        RuntimeConfiguration.ThreadMeasurement twoFast = measurement(
            2, 9, 90_000_000L
        );
        RuntimeConfiguration.ThreadMeasurement fourDeeper = measurement(
            4, 10, 150_000_000L
        );
        assertEquals(
            4,
            RuntimeConfiguration.chooseThreads(Arrays.asList(
                one,
                twoFast,
                fourDeeper
            )),
            "deeper candidate"
        );
    }

    private static List<Integer> threadCandidates(int logicalProcessors) {
        return RuntimeConfiguration.threadCandidates(logicalProcessors);
    }

    private static RuntimeConfiguration.ThreadMeasurement measurement(
        int threads,
        int depth,
        long fixedNanos
    ) {
        return new RuntimeConfiguration.ThreadMeasurement(
            threads,
            depth,
            1L,
            fixedNanos,
            true
        );
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static void assertListEquals(
        List<Integer> expected,
        List<Integer> actual
    ) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "list mismatch expected=" + expected + " actual=" + actual
            );
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(
                label + " expected=" + expected + " actual=" + actual
            );
        }
    }

    private static void assertEquals(
        String expected,
        String actual,
        String label
    ) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                label + " expected=" + expected + " actual=" + actual
            );
        }
    }
}
