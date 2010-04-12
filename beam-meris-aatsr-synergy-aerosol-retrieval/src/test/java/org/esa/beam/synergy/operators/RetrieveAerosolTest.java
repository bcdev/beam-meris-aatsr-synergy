package org.esa.beam.synergy.operators;

import com.bc.jnn.JnnException;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.esa.beam.util.math.LookupTable;
import ucar.ma2.Array;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Test class for Aerosol retrieval over water within Synergy project.
 *
 * @author Olaf Danne
 * @version $Revision: 8092 $ $Date: 2010-01-26 19:08:16 +0100 (Di, 26 Jan 2010) $
 */

/**
 * Unit test for simple App.
 */
public class RetrieveAerosolTest
        extends TestCase {
    private GlintRetrieval glintGeometricalConversionUnderTest;
    private String lutPath;

    protected void setUp() {

        glintGeometricalConversionUnderTest = new GlintRetrieval();
        try {
            glintGeometricalConversionUnderTest.loadGlintAuxData();
            final URL url = GlintRetrieval.class.getResource("");
            lutPath = URLDecoder.decode(url.getPath(), "UTF-8");
            glintGeometricalConversionUnderTest.loadGaussParsLut(lutPath);
        } catch (IOException e) {
            fail("Auxdata cloud not be loaded: " + e.getMessage());
        } catch (JnnException e) {
            fail("Neural net cloud not be loaded: " + e.getMessage());
        }
    }

    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public RetrieveAerosolTest(String testName) {
        super(testName);
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite() {
        return new TestSuite(RetrieveAerosolTest.class);
    }

    /**
     * Rigourous Test :-)
     */
    public void testApp() {
        assertTrue(true);
    }

    public void testLutInterpolation1D() {
        final double[] dimension = new double[]{0, 1};
        final double[] values = new double[]{0, 1};

        final LookupTable lut = new LookupTable(values, dimension);
        assertEquals(1, lut.getDimensionCount());

        assertEquals(0.0, lut.getDimension(0).getMin(), 0.0);
        assertEquals(1.0, lut.getDimension(0).getMax(), 0.0);

        assertEquals(0.0, lut.getValue(0.0), 0.0);
        assertEquals(1.0, lut.getValue(1.0), 0.0);
        assertEquals(0.5, lut.getValue(0.5), 0.0);
        assertEquals(0.12345, lut.getValue(0.12345), 0.0);
    }

    public void testLutInterpolation2D() {
        final double[][] dimensions = new double[][]{{0, 1}, {0, 1}};
        final double[] values = new double[]{0, 1, 2, 3};

        final LookupTable lut = new LookupTable(values, dimensions);
        assertEquals(2, lut.getDimensionCount());

        assertEquals(0.0, lut.getDimension(0).getMin(), 0.0);
        assertEquals(1.0, lut.getDimension(0).getMax(), 0.0);
        assertEquals(0.0, lut.getDimension(1).getMin(), 0.0);
        assertEquals(1.0, lut.getDimension(1).getMax(), 0.0);

        assertEquals(0.0, lut.getValue(0.0, 0.0), 0.0);
        assertEquals(1.0, lut.getValue(0.0, 1.0), 0.0);
        assertEquals(2.0, lut.getValue(1.0, 0.0), 0.0);
        assertEquals(3.0, lut.getValue(1.0, 1.0), 0.0);

        assertEquals(0.5, lut.getValue(0.0, 0.5), 0.0);
        assertEquals(1.5, lut.getValue(0.5, 0.5), 0.0);
        assertEquals(2.5, lut.getValue(1.0, 0.5), 0.0);
    }

    public void testLutInterpolation3D() {
        final double[][] dimensions = new double[][]{{0, 1}, {0, 1}, {0, 1}};
        final double[] values = new double[]{0, 1, 1, 2, 1, 2, 2, 3};

        final LookupTable lut = new LookupTable(values, dimensions);
        assertEquals(3, lut.getDimensionCount());

        assertEquals(0.0, lut.getDimension(0).getMin(), 0.0);
        assertEquals(1.0, lut.getDimension(0).getMax(), 0.0);
        assertEquals(0.0, lut.getDimension(1).getMin(), 0.0);
        assertEquals(1.0, lut.getDimension(1).getMax(), 0.0);
        assertEquals(0.0, lut.getDimension(2).getMin(), 0.0);
        assertEquals(1.0, lut.getDimension(2).getMax(), 0.0);

        assertEquals(0.0, lut.getValue(0.0, 0.0, 0.0), 0.0);
        assertEquals(1.0, lut.getValue(0.0, 1.0, 0.0), 0.0);
        assertEquals(2.0, lut.getValue(1.0, 1.0, 0.0), 0.0);
        assertEquals(3.0, lut.getValue(1.0, 1.0, 1.0), 0.0);

        assertEquals(0.5, lut.getValue(0.0, 0.5, 0.0), 0.0);
        assertEquals(1.5, lut.getValue(0.0, 0.5, 1.0), 0.0);
        assertEquals(2.5, lut.getValue(0.5, 1.0, 1.0), 0.0);
    }

    public void testReadLutFromNetcdf() throws IOException {
        final URL url = RetrieveAerosolTest.class.getResource("test_lut.nc");
        assertNotNull(url);
        assertEquals("file", url.getProtocol());

        final String path = URLDecoder.decode(url.getPath(), "UTF-8");
        assertTrue(path.endsWith("test_lut.nc"));

        final File file = new File(path);
        assertEquals(file.getName(), "test_lut.nc");

//        String inputPath = "C:/test_lut.nc";
//        String outputPath = "C:/test_lut.ascii";
//        FileOutputStream out = new FileOutputStream(new File(outputPath));
//        NCdump.print(inputPath, out);

        String inputPath = file.getAbsolutePath();
        final NetcdfFile netcdfFile = NetcdfFile.open(inputPath);
        List attributes = netcdfFile.getGlobalAttributes();
        List variables = netcdfFile.getVariables();
        String info = netcdfFile.getDetailInfo();
        System.out.println("");

        // variables:
//       float P(P_dimension_4=5, P_dimension_3=13, P_dimension_2=11, P_dimension_1=15);
//       float WSP(WSP_dimension_1=15);
//       float NRE(NRE_dimension_1=11);
//       float COS_SUN(COS_SUN_dimension_1=13);

        Variable p = (Variable) variables.get(0);
        Variable wsp = (Variable) variables.get(1);
        Variable nre = (Variable) variables.get(2);
        Variable cosSun = (Variable) variables.get(3);

        Class pClassType = p.getDataType().getPrimitiveClassType();
        assertEquals(Float.TYPE, pClassType);

        assertEquals(4, p.getDimensions().size());

        Dimension d1 = (Dimension) p.getDimensions().get(0);
        assertEquals("P_dimension_4", d1.getName());
        assertEquals(5, d1.getLength());

        Array pArray = p.read();
        long pSize = pArray.getSize();
        assertEquals(15 * 11 * 13 * 5L, pSize);

        Array wspArray = wsp.read();
        long wspSize = wspArray.getSize();
        assertEquals(15, wspSize);

        Array nreArray = nre.read();
        long nreSize = nreArray.getSize();
        assertEquals(11, nreSize);

        Array cosSunArray = cosSun.read();
        long cosSunSize = cosSunArray.getSize();
        assertEquals(13, cosSunSize);

    }

    public void testCreateAerosolLutsFromNetcdf() throws IOException {
        List<Integer> modelIndices = new ArrayList<Integer>();
        modelIndices.add(1);
        float[] wvl = new float[]{778.0f};
        int[] wvlIndex = new int[]{0};

        LookupTable[][] aerosolLookupTables;
        try {
            aerosolLookupTables = AerosolAuxData.getInstance().createAerosolOceanLookupTables(lutPath, modelIndices, wvl, wvlIndex);
            assertNotNull(aerosolLookupTables);
            assertEquals(1, aerosolLookupTables.length);
            assertEquals(1, aerosolLookupTables[0].length);
            assertNotNull(aerosolLookupTables[0][0]);
            assertNotNull(aerosolLookupTables[0][0].getDimensions());
            assertEquals(6,aerosolLookupTables[0][0].getDimensions().length);
            assertEquals(19,aerosolLookupTables[0][0].getDimensions()[0].getSequence().length);
            assertEquals(11,aerosolLookupTables[0][0].getDimensions()[1].getSequence().length);
            assertEquals(14,aerosolLookupTables[0][0].getDimensions()[2].getSequence().length);
            assertEquals(8,aerosolLookupTables[0][0].getDimensions()[3].getSequence().length);
            assertEquals(9,aerosolLookupTables[0][0].getDimensions()[4].getSequence().length);
            assertEquals(3,aerosolLookupTables[0][0].getDimensions()[5].getSequence().length);
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    public void testComputeGlintFromLUT() {
        float merisSunZenith = 24.1335f;
        float merisViewZenith = 7.43065f;
        float aatsrAzimuthDifference = 17.5067f;
        float refractiveIndex = 1.329f;
        float windspeed = 1.95333f;

        double glint = glintGeometricalConversionUnderTest.computeGlintFromLUT(merisSunZenith, merisViewZenith,
                                                                               aatsrAzimuthDifference, refractiveIndex, windspeed);

        assertEquals(0.0209641, glint, 0.0001);
    }

    public void testGetValueLUT() {
        // p_i(ws_index=0, ri_index=0, cSun_index=0)
        float mCosMerisSunZenith = -0.999062f;
        float refractiveIndex = 1.3f;
        float windspeed = 1.0f;

        double[] values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-1.787f, values[0], 0.001);        // p_0(0)
        assertEquals(0.1277f, values[1], 0.001);
        assertEquals(0.1278f, values[2], 0.001);
        assertEquals(0.0448f, values[3], 0.001);

        // p_i(ws_index=0, ri_index=0, cSun_index=1)
        mCosMerisSunZenith = -0.9926098f;
        refractiveIndex = 1.3f;
        windspeed = 1.0f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-1.78137f, values[0], 0.001);            // p_0(1*11*15)

        // p_i(ws_index=0, ri_index=1, cSun_index=0)
        mCosMerisSunZenith = -0.999062f;
        refractiveIndex = 1.31f;
        windspeed = 1.0f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-1.7304095f, values[0], 0.001);     // p_0(1*1*15)  !!!!

        // p_i(ws_index=1, ri_index=0, cSun_index=0)
        mCosMerisSunZenith = -0.999062f;
        refractiveIndex = 1.30f;
        windspeed = 2.0f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-2.2743595f, values[0], 0.001);     // p_0(2*1*1)

        // p_i(ws_index=1, ri_index=1, cSun_index=0)
        mCosMerisSunZenith = -0.999062f;
        refractiveIndex = 1.31f;
        windspeed = 2.0f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-2.2174518f, values[0], 0.001);     // p_0(1 + 1*1*15)

        // p_i(ws_index=1, ri_index=1, cSun_index=1)
        mCosMerisSunZenith = -0.9926098f;
        refractiveIndex = 1.31f;
        windspeed = 2.0f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-2.21098566, values[0], 0.001);     // p_0(1 + 15 + 1*11*15)


        // p_i(ws_index=0, ri_index=1, cSun_index=1)
        mCosMerisSunZenith = -0.9926098f;
        refractiveIndex = 1.31f;
        windspeed = 1.0f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-1.7244766, values[0], 0.001);         // p_0(15 + 1*11*15)


        // interpolation tsets:

        // p_i(ws_index=0, ri_index=0.5, cSun_index=0)
        mCosMerisSunZenith = -0.999062f;
        refractiveIndex = 1.305f;
        windspeed = 1.0f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-1.758f, values[0], 0.001);   // 0.5*(p_0(0) + p_0(1*1*15))

        // p_i(ws_index=0, ri_index=0, cSun_index=0.5)
        mCosMerisSunZenith = -0.9958f;
        refractiveIndex = 1.30f;
        windspeed = 1.0f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-1.7842f, values[0], 0.001);   // 0.5*(p_0(0) + p_0(1*11*15))

        // p_i(ws_index=0.5, ri_index=0, cSun_index=0)
        mCosMerisSunZenith = -0.999062f;
        refractiveIndex = 1.30f;
        windspeed = 1.5f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        double expected = 0.5 * (-1.7873033 - 2.2743595);
        assertEquals(expected, values[0], 0.001);   // 0.5*(p_0(0) + p_0(1*1*2))

        // p_i(ws_index=0.5, ri_index=0.5, cSun_index=0)
        mCosMerisSunZenith = -0.999062f;
        refractiveIndex = 1.305f;
        windspeed = 1.5f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-2.0024, values[0], 0.001);

        // p_i(ws_index=0.5, ri_index=0, cSun_index=0.5)
        mCosMerisSunZenith = -0.9958f;
        refractiveIndex = 1.30f;
        windspeed = 1.5f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-2.02775, values[0], 0.001);

        // p_i(ws_index=0, ri_index=0.5, cSun_index=0.5)
        mCosMerisSunZenith = -0.9958f;
        refractiveIndex = 1.305f;
        windspeed = 1.0f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-1.75589, values[0], 0.001);

        // p_i(ws_index=0.5, ri_index=0.5, cSun_index=0.5)
        mCosMerisSunZenith = -0.9958f;
        refractiveIndex = 1.305f;
        windspeed = 1.5f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-1.99930, values[0], 0.001);

        // 'real' values:
        mCosMerisSunZenith = -0.912595f;
        refractiveIndex = 1.329f;
        windspeed = 1.95333f;

        values = glintGeometricalConversionUnderTest.
                getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);

        assertEquals(-1.98064, values[0], 0.001);
    }

    public void testGetValueLUTEfficieny() {
        // p_i(ws_index=0, ri_index=0, cSun_index=0)
        float mCosMerisSunZenith = -0.912595f;
        float refractiveIndex = 1.329f;
        float windspeed = 1.95333f;

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 1121 * 1137; i++) {
            double[] values = glintGeometricalConversionUnderTest.
                    getGaussParsFromLUT(mCosMerisSunZenith, refractiveIndex, windspeed);
        }

        long t1 = System.currentTimeMillis();
        System.out.println("interpol time: " + (t1 - t0));
    }
}