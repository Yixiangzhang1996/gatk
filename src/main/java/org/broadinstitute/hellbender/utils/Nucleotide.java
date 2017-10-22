package org.broadinstitute.hellbender.utils;

import java.util.Arrays;
import java.util.stream.LongStream;

/**
 * Represents the nucleotide alphabet.
 *
 * <p>
 *    This enumeration not only contains concrete nucleotides, but also
 *    values to represent ambiguous and invalid codes.
 * </p>
 *
 * @author Valentin Ruano-Rubio &lt;valentin@broadinstitute.org&gt;
 */
public enum Nucleotide {
    A, C, G, T, R, Y, S, W, K, M, B, D, H, V, N, X, INVALID;

    private static final Nucleotide[] baseToValue = new Nucleotide[Byte.MAX_VALUE + 1];

    static {
        Arrays.fill(baseToValue, INVALID);
        baseToValue['a'] = baseToValue['A'] = A;
        baseToValue['c'] = baseToValue['C'] = C;
        baseToValue['g'] = baseToValue['G'] = G;
        baseToValue['t'] = baseToValue['T'] = T;
        baseToValue['u'] = baseToValue['U'] = T;
        baseToValue['r'] = baseToValue['R'] = R;
        baseToValue['y'] = baseToValue['Y'] = Y;
        baseToValue['s'] = baseToValue['S'] = S;
        baseToValue['w'] = baseToValue['W'] = W;
        baseToValue['k'] = baseToValue['K'] = K;
        baseToValue['m'] = baseToValue['M'] = M;
        baseToValue['b'] = baseToValue['B'] = B;
        baseToValue['d'] = baseToValue['D'] = D;
        baseToValue['h'] = baseToValue['H'] = H;
        baseToValue['v'] = baseToValue['V'] = V;
        baseToValue['n'] = baseToValue['N'] = N;
        baseToValue['x'] = baseToValue['X'] = X;
        baseToValue['n'] = baseToValue['N'] = N;
    }

    /**
     * Returns the base that corresponds to this nucleotide.
     * <p>
     *    The base is returned in uppercase.
     * </p>
     * <p>
     *     The {@link #INVALID} nucleotide does not have an actual base then resulting in an exception.
     * </p>
     * @throws UnsupportedOperationException if this nucleotide does not have a byte representation such
     *  as {@link #INVALID}.
     * @return a positive byte value.
     */
    public byte toBase() {
        if (this == INVALID) {
            throw new UnsupportedOperationException("the invalid nucleotide does not have a base byte");
        } else {
            return (byte) name().charAt(0);
        }
    }

    /**
     * Returns the nucleotide that corresponds to a particular {@code byte} typed base code.
     * @param base the query base code.
     * @throws IllegalArgumentException if {@code base} is negative.
     * @return never {@code null}, but {@link #INVALID} if the base code does not
     * correspond to a valid nucleotide specification.
     */
    public static Nucleotide decode(final byte base) {
        return baseToValue[Utils.validIndex(base, baseToValue.length)];
    }

    /**
     * Checks whether the nucleotide refer to a concrete (rather than ambiguous) base.
     * @return {@code true} iff this is a concrete nucleotide.
     */
    public boolean isConcrete() {
        return ordinal() <= T.ordinal();
    }

    /**
     * Checks whether the nucleotide refer to an ambiguous base.
     * @return {@code true} iff this is an ambiguous nucleotide.
     */
    public boolean isAmbiguous() {
        return ordinal() > T.ordinal() && this != INVALID;
    }

    /**
     * Helper class to count the number of occurrences of each nucleotide in
     * a sequence.
     */
    public static class Counter {
        private final long[] counts;

        /**
         * Creates a new counter with all counts set to 0.
         */
        public Counter() {
            counts = new long[Nucleotide.values().length];
        }

        /**
         * Increases by 1 the count for a nucleotide.
         * @param nucleotide the target nucleotide.
         * @throws IllegalArgumentException if nucleotide is {@code null}.
         */
        public void add(final Nucleotide nucleotide) {
            counts[Utils.nonNull(nucleotide).ordinal()]++;
        }

        /**
         * Increases the nucleotide that corresponds to the input base own count by 1.
         * @param base the base code.
         * @throws IllegalArgumentException if {@code base} is {@code negative}.
         */
        public void add(final byte base) {
            add(decode(base));
        }

        /**
         * Returns the current count for a given nucleotide.
         * @param nucleotide the query nucleotide.
         * @throws IllegalArgumentException if {@code nucleotide} is {@code null}.
         * @return 0 or greater.
         */
        public long get(final Nucleotide nucleotide) {
            return counts[Utils.nonNull(nucleotide).ordinal()];
        }

        /**
         * Increase by one the count for a nucleotide for each
         * occurrence of such in the input byte array base codes.
         * @param bases the input base codes.
         * @throws IllegalArgumentException if {@code bases} are null or
         * it contains negative values.
         */
        public void addAll(final byte[] bases) {
            Utils.nonNull(bases);
            for (final byte base : bases) {
                add(base);
            }
        }

        /**
         * Reset all the counts to 0.
         */
        public void clear() {
            Arrays.fill(counts, 0);
        }

        public long sum() {
            return LongStream.of(counts).sum();
        }
    }
}
