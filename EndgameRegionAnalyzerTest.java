public final class EndgameRegionAnalyzerTest {

    private EndgameRegionAnalyzerTest() {
    }

    public static void main(String[] args) {
        testEmptyBoardMask();
        testOddAndEvenRegions();
        testDiagonalCellsAreSeparate();
        testFilesDoNotWrap();
        System.out.println("EndgameRegionAnalyzerTest: PASS");
    }

    private static void testEmptyBoardMask() {
        assertEquals(0L, EndgameRegionAnalyzer.oddRegionMask(0L), "empty mask");
        assertEquals(0, EndgameRegionAnalyzer.regionCount(0L), "empty count");
    }

    private static void testOddAndEvenRegions() {
        long singleton = bit(0, 0);
        long pair = bit(4, 4) | bit(5, 4);
        long empty = singleton | pair;

        assertEquals(
            singleton,
            EndgameRegionAnalyzer.oddRegionMask(empty),
            "odd region mask"
        );
        assertEquals(2, EndgameRegionAnalyzer.regionCount(empty), "region count");
    }

    private static void testDiagonalCellsAreSeparate() {
        long empty = bit(2, 2) | bit(3, 3);
        assertEquals(
            empty,
            EndgameRegionAnalyzer.oddRegionMask(empty),
            "diagonal odd regions"
        );
        assertEquals(
            2,
            EndgameRegionAnalyzer.regionCount(empty),
            "diagonal region count"
        );
    }

    private static void testFilesDoNotWrap() {
        long empty = bit(7, 0) | bit(0, 1);
        assertEquals(
            empty,
            EndgameRegionAnalyzer.oddRegionMask(empty),
            "file boundary odd regions"
        );
        assertEquals(
            2,
            EndgameRegionAnalyzer.regionCount(empty),
            "file boundary region count"
        );
    }

    private static long bit(int x, int y) {
        return 1L << CoordinateConverter.xyToSquare(x, y);
    }

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(
                label + ": expected=" + expected + ", actual=" + actual
            );
        }
    }
}
