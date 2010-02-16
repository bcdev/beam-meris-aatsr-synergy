package org.esa.beam.synergy.operators;

import junit.framework.TestCase;

import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;

import com.bc.jnn.JnnException;

/**
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class GlintTest extends TestCase {

    private GlintSolarPart37 solarPartUnderTest;
    private GlintRetrieval glintRetrievalUnderTest;
    private String lutPath;

    protected void setUp() {

        glintRetrievalUnderTest = new GlintRetrieval();
        solarPartUnderTest = new GlintSolarPart37();
        try {
            glintRetrievalUnderTest.loadGlintAuxData();
            final URL url = GlintRetrieval.class.getResource("");
            lutPath = URLDecoder.decode(url.getPath(), "UTF-8");
            glintRetrievalUnderTest.loadGaussParsLut(lutPath);
        } catch (IOException e) {
            fail("Auxdata cloud not be loaded: " + e.getMessage());
        } catch (JnnException e) {
            fail("Neural net cloud not be loaded: " + e.getMessage());
        }
    }

    public void testComputeTransmission() {
        double meris14 = 0.0;
        double meris15 = 0.0;
        float[] transmissionInfo = solarPartUnderTest.computeTransmission(meris14, meris15);
        assertEquals(0.0f, transmissionInfo[0]);

        meris14 = 0.1;
        meris15 = 0.05;
        transmissionInfo = solarPartUnderTest.computeTransmission(meris14, meris15);
        assertEquals(0.5951f, transmissionInfo[0], 1.E-4);
        assertEquals(0.5592f, transmissionInfo[1], 1.E-4);

        meris14 = 0.001;
        meris15 = 0.05;
        transmissionInfo = solarPartUnderTest.computeTransmission(meris14, meris15);
        assertEquals(0.0f, transmissionInfo[0]);
    }

    public void testCalcGlintReflectionAnalytical() {
        float sunZenith = 20.0f;
        float viewZenith = 30.0f;
        float azimDiff = 170.0f;
        float refractiveIndex = 1.33f;
        float windspeed = 7.0f;

        float reflAnalytical =
                glintRetrievalUnderTest.calcGlintReflectionAnalytical(sunZenith, viewZenith, azimDiff, refractiveIndex, windspeed);
        assertEquals(0.0030784, reflAnalytical, 1.E-6);

        azimDiff = 10.0f;
        reflAnalytical =
                glintRetrievalUnderTest.calcGlintReflectionAnalytical(sunZenith, viewZenith, azimDiff, refractiveIndex, windspeed);
        assertEquals(0.1307, reflAnalytical, 1.E-4);

    }

    public void testCalcGlintAnalytical() {
        float sunZenith = 20.0f;
        float viewZenith = 30.0f;
        float azimDiff = 170.0f;
        float refractiveIndex = 1.33f;
        float windspeed = 7.0f;
        float rhoFoam = 0.2f;

        float glintAnalytical =
                glintRetrievalUnderTest.calcGlintAnalytical(sunZenith, viewZenith, azimDiff, refractiveIndex, windspeed, rhoFoam);
        assertEquals(0.001017, glintAnalytical, 1.E-6);

        azimDiff = 10.0f;
        glintAnalytical =
                glintRetrievalUnderTest.calcGlintAnalytical(sunZenith, viewZenith, azimDiff, refractiveIndex, windspeed, rhoFoam);
        assertEquals(0.03912, glintAnalytical, 1.E-4);

    }
}
