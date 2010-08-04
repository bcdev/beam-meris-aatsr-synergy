package org.esa.beam.synergy.operators;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.esa.beam.synergy.util.SynergyLookupTable;
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
public class RetrieveAerosolTest extends TestCase {

    private String lutPath;

    protected void setUp() {
        try {
            final URL url = GlintRetrieval.class.getResource("");
            lutPath = URLDecoder.decode(url.getPath(), "UTF-8");
        } catch (IOException e) {
            fail("Auxdata cloud not be loaded: " + e.getMessage());
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

        final SynergyLookupTable lut = new SynergyLookupTable(values, dimension);
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

        final SynergyLookupTable lut = new SynergyLookupTable(values, dimensions);
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

        final SynergyLookupTable lut = new SynergyLookupTable(values, dimensions);
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

        SynergyLookupTable[][] aerosolLookupTables;
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
}