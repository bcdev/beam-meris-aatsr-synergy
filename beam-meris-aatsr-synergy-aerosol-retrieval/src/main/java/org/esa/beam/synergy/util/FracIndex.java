package org.esa.beam.synergy.util;

/**
 * The class {@code FracIndex} is a simple representation of
 * an index with an integral and a fractional component.
 */
final class FracIndex {

    /**
     * The integral component.
     */
    public int i;
    /**
     * The fractional component.
     */
    public double f;

    /**
     * Creates an array of type {@code FracIndex[]}.
     *
     * @param length the length of the array being created.
     * @return the created array.
     */
    public static FracIndex[] createArray(int length) {
        final FracIndex[] fracIndexes = new FracIndex[length];

        for (int i = 0; i < length; i++) {
            fracIndexes[i] = new FracIndex();
        }

        return fracIndexes;
    }

    /**
     * Sets the fractional component to 0.0 if it is less than
     * zero, and to 1.0 if it is greater than unity.
     */
    public final void truncate() {
        if (f < 0.0) {
            f = 0.0;
        } else if (f > 1.0) {
            f = 1.0;
        }
    }
}

