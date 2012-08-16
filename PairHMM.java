/*
 * Copyright (c) 2012, The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */


import java.util.Arrays;

/**
 * Hidden Markov Model for Haplotype assembly
 *
 * @author Mauricio Carneiro
 * @since 8/15/2012
 */

public class PairHMM {
    private static final int MAX_CACHED_QUAL = (int) Byte.MAX_VALUE;
    private static final byte DEFAULT_GOP = (byte) 45;
    private static final byte DEFAULT_GCP = (byte) 10;
    public final static byte MIN_USABLE_Q_SCORE = 6;
    private static double[] qualToErrorProbLog10Cache = new double[256];
    private static double[] qualToProbLog10Cache = new double[256];
    public static final double[] log10Cache;
    public static final double[] log10FactorialCache;
    private static final double[] jacobianLogTable;
    private static final double JACOBIAN_LOG_TABLE_STEP = 0.001;
    private static final double JACOBIAN_LOG_TABLE_INV_STEP = 1.0 / 0.001;
    private static final double MAX_JACOBIAN_TOLERANCE = 8.0;
    private static final int JACOBIAN_LOG_TABLE_SIZE = (int) (MAX_JACOBIAN_TOLERANCE / JACOBIAN_LOG_TABLE_STEP) + 1;
    private static final int MAXN = 50000;
    private static final int LOG10_CACHE_SIZE = 4 * MAXN;  // we need to be able to go up to 2*(2N) when calculating some of the coefficients

    static {
        for (int i = 0; i < 256; i++) qualToErrorProbLog10Cache[i] = qualToErrorProbLog10Raw(i);
        for (int i = 0; i < 256; i++) qualToProbLog10Cache[i] = qualToProbLog10Raw(i);

        log10Cache = new double[LOG10_CACHE_SIZE];
        log10FactorialCache = new double[LOG10_CACHE_SIZE];
        jacobianLogTable = new double[JACOBIAN_LOG_TABLE_SIZE];

        log10Cache[0] = Double.NEGATIVE_INFINITY;
        for (int k = 1; k < LOG10_CACHE_SIZE; k++) {
            log10Cache[k] = Math.log10(k);
            log10FactorialCache[k] = log10FactorialCache[k - 1] + log10Cache[k];
        }

        for (int k = 0; k < JACOBIAN_LOG_TABLE_SIZE; k++) {
            jacobianLogTable[k] = Math.log10(1.0 + Math.pow(10.0, -((double) k) * JACOBIAN_LOG_TABLE_STEP));

        }
    }

    public static void initializeArrays(final double[][] matchMetricArray, final double[][] XMetricArray, final double[][] YMetricArray, final int X_METRIC_LENGTH, final int Y_METRIC_LENGTH) {

        for (int i = 0; i < X_METRIC_LENGTH; i++) {
            Arrays.fill(matchMetricArray[i], Double.NEGATIVE_INFINITY);
            Arrays.fill(XMetricArray[i], Double.NEGATIVE_INFINITY);
            Arrays.fill(YMetricArray[i], Double.NEGATIVE_INFINITY);
        }
        // the initial condition
        matchMetricArray[1][1] = 0.0; // Math.log10(1.0);

        final double[] d = {qualToProbLog10((byte) (DEFAULT_GOP + DEFAULT_GOP)), qualToErrorProbLog10(DEFAULT_GOP), 0.0};
        final double[] e = {qualToProbLog10(DEFAULT_GCP), qualToErrorProbLog10(DEFAULT_GCP), 0.0};

        for (int i = 2; i < Y_METRIC_LENGTH; i++) {
            updateCell(1, i, 0.0, d, e, matchMetricArray, XMetricArray, YMetricArray);
        }
    }

    public static double finalizeArrays(final double[][] matchMetricArray, final double[][] XMetricArray, final double[][] YMetricArray,
                                      final byte[] insertionGOP, final byte[] deletionGOP, final byte[] overallGCP, int hapStartIndex,
                                      final byte[] haplotypeBases, final byte[] readBases, final byte[] readQuals,
                                      final int X_METRIC_LENGTH, final int Y_METRIC_LENGTH) {
        final int i = X_METRIC_LENGTH - 1;
        final int qualIndexGOP = Math.min(insertionGOP[i-2] + deletionGOP[i-2], MAX_CACHED_QUAL);
        final double d[] = new double[3];
        final double e[] = new double[3];
        d[0] = qualToProbLog10((byte) qualIndexGOP);
        e[0] = qualToProbLog10(overallGCP[i-2]);
        d[1] = qualToErrorProbLog10(insertionGOP[i-2]);
        e[1] = qualToErrorProbLog10(overallGCP[i-2]);
        d[2] = 0.0;
        e[2] = 0.0;
        final byte x = readBases[i-2];
        final byte qual = readQuals[i-2];
        for (int j = Math.max(2, hapStartIndex + 1); j < Y_METRIC_LENGTH; j++) {
            final byte y = haplotypeBases[j-2];
            final double pBaseReadLog10 = (x == y || x == (byte) 'N' || y == (byte) 'N' ? qualToProbLog10(qual) : qualToErrorProbLog10(qual));
            updateCell(i, j, pBaseReadLog10, d, e, matchMetricArray, XMetricArray, YMetricArray);
        }

        // final probability is the log10 sum of the last element in all three state arrays
        final int endI = X_METRIC_LENGTH - 1;
        final int endJ = Y_METRIC_LENGTH - 1;
        return approximateLog10SumLog10(matchMetricArray[endI][endJ], XMetricArray[endI][endJ], YMetricArray[endI][endJ]);

    }
    public double computeReadLikelihoodGivenHaplotype(final byte[] haplotypeBases, final byte[] readBases, final byte[] readQuals,
                                                      final byte[] insertionGOP, final byte[] deletionGOP, final byte[] overallGCP) {

        // ensure that all the qual scores have valid values
        for (int i = 0; i < readQuals.length; i++) {
            readQuals[i] = (readQuals[i] < MIN_USABLE_Q_SCORE ? MIN_USABLE_Q_SCORE : (readQuals[i] > MAX_CACHED_QUAL ? MAX_CACHED_QUAL : readQuals[i]));
        }

        // M, X, and Y arrays are of size read and haplotype + 1 because of an extra column for initial conditions and + 1 to consider the final base in a non-global alignment
        final int X_METRIC_LENGTH = readBases.length + 2;
        final int Y_METRIC_LENGTH = haplotypeBases.length + 2;

        // initial arrays to hold the probabilities of being in the match, insertion and deletion cases
        final double[][] matchMetricArray = new double[X_METRIC_LENGTH][Y_METRIC_LENGTH];
        final double[][] XMetricArray = new double[X_METRIC_LENGTH][Y_METRIC_LENGTH];
        final double[][] YMetricArray = new double[X_METRIC_LENGTH][Y_METRIC_LENGTH];

        initializeArrays(matchMetricArray, XMetricArray, YMetricArray, X_METRIC_LENGTH, Y_METRIC_LENGTH);
        computeReadLikelihoodGivenHaplotype(haplotypeBases, readBases, readQuals, insertionGOP, deletionGOP, overallGCP, 0, matchMetricArray, XMetricArray, YMetricArray);
        return finalizeArrays(matchMetricArray, XMetricArray, YMetricArray, insertionGOP, deletionGOP, overallGCP, 0, haplotypeBases, readBases, readQuals, X_METRIC_LENGTH, Y_METRIC_LENGTH);
    }

    public void computeReadLikelihoodGivenHaplotype(final byte[] haplotypeBases, final byte[] readBases, final byte[] readQuals,
                                                      final byte[] insertionGOP, final byte[] deletionGOP, final byte[] overallGCP, final int hapStartIndex,
                                                      final double[][] matchMetricArray, final double[][] XMetricArray, final double[][] YMetricArray) {

        // M, X, and Y arrays are of size read and haplotype + 1 because of an extra column for initial conditions and + 1 to consider the final base in a non-global alignment
        final int X_METRIC_LENGTH = readBases.length + 2;
        final int Y_METRIC_LENGTH = haplotypeBases.length + 2;

		// simple rectangular version of update loop, slow
		for (int i = 2; i < X_METRIC_LENGTH - 1; i++) {

            final int qualIndexGOP = Math.min(insertionGOP[i-2] + deletionGOP[i-2], MAX_CACHED_QUAL);
            final double d[] = new double[3];
            final double e[] = new double[3];
            d[0] = qualToProbLog10((byte) qualIndexGOP);
            e[0] = qualToProbLog10(overallGCP[i-2]);
            d[1] = qualToErrorProbLog10(insertionGOP[i-2]);
            e[1] = qualToErrorProbLog10(overallGCP[i-2]);
            d[2] = qualToErrorProbLog10(deletionGOP[i-2]);
            e[2] = qualToErrorProbLog10(overallGCP[i-2]);

            // the emission probability is applied when leaving the state
            final byte x = readBases[i-2];
            final byte qual = readQuals[i-2];

            // In case hapStart > 0, we will unnecessarily call this method (avoiding an if statement)
            updateCell(i, 1, 0.0, d, e, matchMetricArray, XMetricArray, YMetricArray);

            for (int j = Math.max(2, hapStartIndex + 1); j < Y_METRIC_LENGTH; j++) {
                final byte y = haplotypeBases[j-2];
                final double pBaseReadLog10 = (x == y || x == (byte) 'N' || y == (byte) 'N' ? qualToProbLog10(qual) : qualToErrorProbLog10(qual));
                updateCell(i, j, pBaseReadLog10, d, e, matchMetricArray, XMetricArray, YMetricArray);
			}
		}
    }

    /**
     * Updates a cell in the HMM matrix
     *
     * The read and haplotype indices are offset by one because the state arrays have an extra column to hold the initial conditions
     */

    private static void updateCell(final int indI, final int indJ, double pBaseReadLog10, double[] d, double[] e, final double[][] matchMetricArray, final double[][] XMetricArray, final double[][] YMetricArray) {
        matchMetricArray[indI][indJ] = pBaseReadLog10 + approximateLog10SumLog10(matchMetricArray[indI - 1][indJ - 1] + d[0], XMetricArray[indI - 1][indJ - 1] + e[0], YMetricArray[indI - 1][indJ - 1] + e[0]);
        XMetricArray[indI][indJ] = approximateLog10SumLog10(matchMetricArray[indI - 1][indJ] + d[1], XMetricArray[indI - 1][indJ] + e[1]);
        YMetricArray[indI][indJ] = approximateLog10SumLog10(matchMetricArray[indI][indJ - 1] + d[2], YMetricArray[indI][indJ - 1] + e[2]);
    }



    /**************************************************************************
     * MATH UTILS
     *************************************************************************/
    static private double qualToProbLog10Raw(int qual) {
        return Math.log10(1.0 - qualToErrorProbRaw(qual));
    }

    static public double qualToProbLog10(byte qual) {
        return qualToProbLog10Cache[(int) qual & 0xff]; // Map: 127 -> 127; -128 -> 128; -1 -> 255; etc.
    }

    /**
     * Convert a quality score to a probability of error.  This is the Phred-style
     * conversion, *not* the Illumina-style conversion (though asymptotically, they're the same).
     *
     * @param qual a quality score (0 - 255)
     * @return a probability (0.0 - 1.0)
     */
    static private double qualToErrorProbRaw(int qual) {
        return qualToErrorProb((double) qual);
    }

    public static double qualToErrorProb(final double qual) {
        return Math.pow(10.0, (qual) / -10.0);
    }

    static private double qualToErrorProbLog10Raw(int qual) {
        return ((double) qual) / -10.0;
    }

    static public double qualToErrorProbLog10(byte qual) {
        return qualToErrorProbLog10Cache[(int) qual & 0xff]; // Map: 127 -> 127; -128 -> 128; -1 -> 255; etc.
    }

    // A fast implementation of the Math.round() method.  This method does not perform
    // under/overflow checking, so this shouldn't be used in the general case (but is fine
    // if one is already make those checks before calling in to the rounding).
    public static int fastRound(double d) {
        return (d > 0.0) ? (int) (d + 0.5d) : (int) (d - 0.5d);
    }

    public static double approximateLog10SumLog10(double a, double b, double c) {
        return approximateLog10SumLog10(a, approximateLog10SumLog10(b, c));
    }

    public static double approximateLog10SumLog10(double small, double big) {
        // make sure small is really the smaller value
        if (small > big) {
            final double t = big;
            big = small;
            small = t;
        }

        if (small == Double.NEGATIVE_INFINITY)
            return big;

        final double diff = big - small;
        if (diff >= MAX_JACOBIAN_TOLERANCE)
            return big;

        // OK, so |y-x| < tol: we use the following identity then:
        // we need to compute log10(10^x + 10^y)
        // By Jacobian logarithm identity, this is equal to
        // max(x,y) + log10(1+10^-abs(x-y))
        // we compute the second term as a table lookup with integer quantization
        // we have pre-stored correction for 0,0.1,0.2,... 10.0
        final int ind = fastRound(diff * JACOBIAN_LOG_TABLE_INV_STEP); // hard rounding
        return big + jacobianLogTable[ind];
    }


}
