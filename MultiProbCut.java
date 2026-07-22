final class MultiProbCut {

    static final String MODEL_SHA256 =
        "6E118C928729E003E89742D3B57FE6ED35FA9233A38DBE8AF852041EBEE39457";

    private static final Parameters PHASE_0_DEPTH_8 = new Parameters(
        0.944002830632989,
        -30.2433689868432,
        1876.77130281511,
        1702.49703088399
    );
    private static final Parameters PHASE_0_DEPTH_10 = new Parameters(
        0.979571718808301,
        2.77044610302994,
        1591.99461106601,
        1559.93638444647
    );
    private static final Parameters PHASE_1_DEPTH_8 = new Parameters(
        1.00485821385319,
        74.8789575161307,
        1462.11711588625,
        1484.42372995897
    );

    private MultiProbCut() {
    }

    static boolean supports(PositionEvaluator evaluator) {
        return evaluator instanceof LearnedEvaluator
            && MODEL_SHA256.equals(
                ((LearnedEvaluator) evaluator).modelSha256()
            );
    }

    static Parameters parametersFor(int depth, int empties) {
        if (empties <= SearchEngine.MAX_ENDGAME_THRESHOLD) {
            return null;
        }
        int ply = 60 - empties;
        if (ply < 30) {
            if (depth == 8) {
                return PHASE_0_DEPTH_8;
            }
            return depth == 10 ? PHASE_0_DEPTH_10 : null;
        }
        if (ply < 40) {
            return depth == 8 ? PHASE_1_DEPTH_8 : null;
        }
        return null;
    }

    static int failHighThreshold(int beta, Parameters parameters) {
        return clamp((int) Math.ceil(
            (beta - parameters.intercept + parameters.falseHighMargin)
                / parameters.slope
        ));
    }

    static int failLowThreshold(int alpha, Parameters parameters) {
        return clamp((int) Math.floor(
            (alpha - parameters.intercept - parameters.falseLowMargin)
                / parameters.slope
        ));
    }

    private static int clamp(int value) {
        return Math.max(-999_999, Math.min(999_999, value));
    }

    static final class Parameters {
        private final double slope;
        private final double intercept;
        private final double falseHighMargin;
        private final double falseLowMargin;

        private Parameters(
            double slope,
            double intercept,
            double falseHighMargin,
            double falseLowMargin
        ) {
            this.slope = slope;
            this.intercept = intercept;
            this.falseHighMargin = falseHighMargin;
            this.falseLowMargin = falseLowMargin;
        }
    }
}
