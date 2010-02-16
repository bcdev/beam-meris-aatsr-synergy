package org.esa.beam.synergy.util.math;

import junit.framework.TestCase;
import org.esa.beam.synergy.operators.GlintRetrieval;

import java.io.IOException;

import com.bc.jnn.JnnException;

/**
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class SplineTest extends TestCase {
    private Spline objectUnderTest;

    protected void setUp() {
    }

    public void testSpline() {
        // y(x) := x, [0.0, 5.0]
        double[] y1 = new double[]{0.0, 1.0, 2.0, 3.0, 4.0, 5.0};
        objectUnderTest = new Spline(y1);

        double result = objectUnderTest.fn(1,0.5);
        assertEquals(1.5, result);
        result = objectUnderTest.fn(1,0.987);
        assertEquals(1.987, result);

        // y(x) := x^3, [0.0, 125.0]
        double[] y2 = new double[]{0.0, 1.0, 8.0, 27.0, 64.0, 125.0};
        objectUnderTest = new Spline(y2);

        result = objectUnderTest.fn(1,0.5);
        assertEquals(3.375, result, 0.05);
    }
}
