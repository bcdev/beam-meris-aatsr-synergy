package org.esa.beam.synergy.operators;

import com.bc.jnn.Jnn;
import com.bc.jnn.JnnException;
import com.bc.jnn.JnnNet;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.synergy.util.GlintHelpers;
import org.esa.beam.util.math.IntervalPartition;
import org.esa.beam.util.math.LookupTable;
import ucar.ma2.Array;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Utility class for Glint retrieval auxdata.
 *
 * @author Olaf Danne
 * @version $Revision: 8064 $ $Date: 2010-01-21 18:19:59 +0100 (Do, 21 Jan 2010) $
 */
public class GlintAuxData {
    private static GlintAuxData instance;

    private static final String AATSR_SPECTRAL_RESPONSE37_FILE_NAME = "aatsr_ir37.dat";
    // make sure that the following value corresponds to the file above
    private static final int AATSR_SPECTRAL_RESPONSE37_TABLE_LENGTH = 255;
    private static final int AATSR_SPECTRAL_RESPONSE37_TABLE_HEADER_LINES = 3;

    private static final String CAHALAN_FILE_NAME = "cahalan.d";
    // make sure that the following value corresponds to the file above
    private static final int CAHALAN_TABLE_LENGTH = 2496;

    private static final String TEMP2RAD_FILE_NAME = "temp_to_rad_36.d";
    // make sure that the following value corresponds to the file above
    private static final int TEMP2RAD_TABLE_LENGTH = 200;

    private static final String A_COEFF_0370_FILE_NAME = "ck_flex_cd_AATSR_sfp1000_03700.00.and.5.ck.koeff.d";
    private static final String A_WEIGHT_0370_FILE_NAME = "ck_flex_cd_AATSR_sfp1000_03700.00.and.5.ck.weight.d";
    private static final String H_COEFF_0370_FILE_NAME = "ck_flex_cd_AATSR_sfp1000_03700.00.h2o.5.ck.koeff.d";
    private static final String H_WEIGHT_0370_FILE_NAME = "ck_flex_cd_AATSR_sfp1000_03700.00.h2o.5.ck.weight.d";

    private static final String A_COEFF_1600_FILE_NAME = "ck_flex_cd_AATSR_sfp1000_01600.00.and.4.ck.koeff.d";
    private static final String A_WEIGHT_1600_FILE_NAME = "ck_flex_cd_AATSR_sfp1000_01600.00.and.4.ck.weight.d";
    private static final String H_COEFF_1600_FILE_NAME = "ck_flex_cd_AATSR_sfp1000_01600.00.h2o.4.ck.koeff.d";
    private static final String H_WEIGHT_1600_FILE_NAME = "ck_flex_cd_AATSR_sfp1000_01600.00.h2o.4.ck.weight.d";

    public static final String NEURAL_NET_WV_OCEAN_MERIS_FILE_NAME = "wv_ocean_meris.nna";
    public static final String NEURAL_NET_WINDSPEED_FILE_NAME = "cm_ws_to_gauss2d.nna";

    public static final String GAUSS_PARS_LUT_FILE_NAME = "gauss_lut.nc";


    public static GlintAuxData getInstance() {
        if (instance == null) {
            instance = new GlintAuxData();
        }

        return instance;
    }

    /**
     * This method reads a neural net file.
     *
     * @param filename  - NN file
     * @return JnnNet - the NN object (see {@link JnnNet})
     * @throws IOException
     * @throws JnnException
     */
    public JnnNet loadNeuralNet(String filename) throws IOException, JnnException {
        InputStream inputStream = getClass().getResourceAsStream(filename);
        final InputStreamReader reader = new InputStreamReader(inputStream);

        JnnNet neuralNet= null;

        try {
            Jnn.setOptimizing(true);
            neuralNet = Jnn.readNna(reader);
        } finally {
            reader.close();
        }

        return neuralNet;
    }

    /**
     * This method reads the Gaussian parameters LUT for Glint retrieval.
     *
     * @return LookupTable
     * @throws IOException
     */
    public LookupTable[] readGaussParsLuts(String lutPath) throws IOException {
        LookupTable[] gaussParsLuts = new LookupTable[5];
        try {
            final NetcdfFile netcdfFile = NetcdfFile.open(lutPath + File.separator + GAUSS_PARS_LUT_FILE_NAME);
            List variables = netcdfFile.getVariables();

            // the variables in the netcdf file are defined like this (as obtained from an ncdump):
            //       float P(P_dimension_4=5, P_dimension_3=13, P_dimension_2=11, P_dimension_1=15);
            //       float WSP(WSP_dimension_1=15);
            //       float NRE(NRE_dimension_1=11);
            //       float COS_SUN(COS_SUN_dimension_1=13);

            Variable p = (Variable) variables.get(0);
            Variable wsp = (Variable) variables.get(1);
            Variable nre = (Variable) variables.get(2);
            Variable cosSun = (Variable) variables.get(3);


            Array wspArrayNc = wsp.read();
            int wspSize = (int) wspArrayNc.getSize();
            Object storage = wspArrayNc.getStorage();
            float[] wspArray = new float[wspSize];
            System.arraycopy(storage, 0, wspArray, 0, wspSize);

            Array nreArrayNc = nre.read();
            int nreSize = (int) nreArrayNc.getSize();
            storage = nreArrayNc.getStorage();
            float[] nreArray = new float[nreSize];
            System.arraycopy(storage, 0, nreArray, 0, nreSize);

            Array cosSunArrayNc = cosSun.read();
            int cosSunSize = (int) cosSunArrayNc.getSize();
            storage = cosSunArrayNc.getStorage();
            float[] cosSunArray = new float[cosSunSize];
            for (int i = 0; i < cosSunArray.length; i++) {
                float[] storageArray = (float[]) storage;
                cosSunArray[i] = -storageArray[i];  // take negative value to get increasing sequence for LUT creation
            }

            Array pArrayNc = p.read();
            int pSize = p.getDimension(1).getLength() *
                        p.getDimension(2).getLength() *
                        p.getDimension(3).getLength();
            storage = pArrayNc.getStorage();
            float[][] pArray = new float[5][pSize];
            for (int i = 0; i < pArray.length; i++) {
                System.arraycopy(storage, i*pSize, pArray[i], 0, pSize);
            }

            // set up LUTs for each pArray
            final IntervalPartition[] dimensions = IntervalPartition.createArray(
                    cosSunArray, nreArray, wspArray);

            for (int i = 0; i < 5; i++) {
                gaussParsLuts[i] = new LookupTable(pArray[i], dimensions);
            }

        } catch (UnsupportedEncodingException e) {
            throw new OperatorException("Failed to read gaussPars LUT from netcdf file.\n");
        }

        return gaussParsLuts;
    }

    /**
     * This methos reads water vapour coefficients from file.
     *
     * @param channel - the channel
     * @param index - the index (H or A)
     * @return float[][]
     * @throws IOException
     */
    public float[][] readWaterVapourCoefficients(int channel, String index) throws IOException {
       InputStream inputStream;

       float[][] tmpCoeffs;
       float[] coeffs;
       int COEFF_ROWS;
       int COEFF_COLUMNS = 8;

       if (channel == 37) {
            COEFF_ROWS = 45;
            tmpCoeffs = new float[COEFF_ROWS][COEFF_COLUMNS];
            coeffs = new float[COEFF_ROWS*COEFF_COLUMNS]; // final result
            if (index.toUpperCase().equals("A")) {
                inputStream = getClass().getResourceAsStream(A_COEFF_0370_FILE_NAME);
            } else if (index.toUpperCase().equals("H")) {
                inputStream = getClass().getResourceAsStream(H_COEFF_0370_FILE_NAME);
            } else {
                throw new OperatorException("Failed to read WV coefficients - index must be 'H' or 'A'.\n");
            }
       } else if (channel == 16) {
           COEFF_ROWS = 54;
            tmpCoeffs = new float[COEFF_ROWS][COEFF_COLUMNS];
            coeffs = new float[COEFF_ROWS*COEFF_COLUMNS]; // final result
            if (index.toUpperCase().equals("A")) {
                inputStream = getClass().getResourceAsStream(A_COEFF_1600_FILE_NAME);
            } else if (index.toUpperCase().equals("H")) {
                inputStream = getClass().getResourceAsStream(H_COEFF_1600_FILE_NAME);
            } else {
                throw new OperatorException("Failed to read WV coefficients - index must be 'H' or 'A'.\n");
            }
       } else {
           throw new OperatorException("Failed to read WV coefficients - channel must be '16' or '37'.\n");
       }

       BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
       StringTokenizer st;
       try {
           int lineIndex = 0;
           String line;
           while ((line = bufferedReader.readLine()) != null && lineIndex < COEFF_ROWS) {
               line = line.trim();
               st = new StringTokenizer(line, "   ", false);
               int colIndex = 0;
               while (st.hasMoreTokens() && colIndex < 8) {
                   tmpCoeffs[lineIndex][colIndex] = Float.parseFloat(st.nextToken());
                   colIndex++;
               }
               lineIndex++;
           }

           for (int i=0; i<COEFF_COLUMNS; i++) {
               for (int j=0; j<COEFF_ROWS; j++) {
                    coeffs[i*COEFF_ROWS + j] = tmpCoeffs[j][i];
               }
           }
       } catch (NumberFormatException e) {
           throw new OperatorException("Failed to load WV coefficients: \n" + e.getMessage(), e);
       } finally {
           inputStream.close();
       }
        return tmpCoeffs;
   }


    /**
     * This methos reads transmission weights from file.
     *
     * @param channel - the channel
     * @param index - the index (H or A)
     * @return float[][]
     * @throws IOException
     */
    public float[] readTransmissionWeights(int channel, String index) throws IOException {
       InputStream inputStream;

       float[] coeffs;
       int COEFF_ROWS;

       if (channel == 37) {
            COEFF_ROWS = 45;
            coeffs = new float[COEFF_ROWS]; // result
            if (index.toUpperCase().equals("A")) {
                inputStream = getClass().getResourceAsStream(A_WEIGHT_0370_FILE_NAME);
            } else if (index.toUpperCase().equals("H")) {
                inputStream = getClass().getResourceAsStream(H_WEIGHT_0370_FILE_NAME);
            } else {
                throw new OperatorException("Failed to read WV weights - index must be 'H' or 'A'.\n");
            }
       } else if (channel == 16) {
            COEFF_ROWS = 54;
            coeffs = new float[COEFF_ROWS]; // result
            if (index.toUpperCase().equals("A")) {
                inputStream = getClass().getResourceAsStream(A_WEIGHT_1600_FILE_NAME);
            } else if (index.toUpperCase().equals("H")) {
                inputStream = getClass().getResourceAsStream(H_WEIGHT_1600_FILE_NAME);
            } else {
                throw new OperatorException("Failed to read WV weights - index must be 'H' or 'A'.\n");
            }
       } else {
           throw new OperatorException("Failed to read WV weights - channel must be '16' or '37'.\n");
       }

       BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
       StringTokenizer st;
       try {
           int lineIndex = 0;
           String line;
           while ((line = bufferedReader.readLine()) != null && lineIndex < COEFF_ROWS) {
               line = line.trim();
               st = new StringTokenizer(line, "   ", false);
               if (st.hasMoreTokens()) {
                   coeffs[lineIndex] = Float.parseFloat(st.nextToken());
               }
               lineIndex++;
           }
       } catch (IOException e) {
           throw new OperatorException("Failed to load WV weights: \n" + e.getMessage(), e);
       } catch (NumberFormatException e) {
           throw new OperatorException("Failed to load WV weights: \n" + e.getMessage(), e);
       } finally {
           inputStream.close();
       }
        return coeffs;
   }


    /**
     * This method creates a {@link AatsrSpectralResponse37Table} with spectral response
     * data for AATSR.
     *
     * @return  AatsrSpectralResponse37Table
     * @throws IOException
     */
    public AatsrSpectralResponse37Table createAatsrSpectralResponse37Table() throws IOException {
        final InputStream inputStream = getClass().getResourceAsStream(AATSR_SPECTRAL_RESPONSE37_FILE_NAME);
        AatsrSpectralResponse37Table aatsrSpectralResponse37Table = new AatsrSpectralResponse37Table();

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringTokenizer st;
        try {

            // skip header lines
            for (int i = 0; i < AATSR_SPECTRAL_RESPONSE37_TABLE_HEADER_LINES; i++) {
                bufferedReader.readLine();
            }

            int i = 0;
            String line;
            while ((line = bufferedReader.readLine()) != null && i < AATSR_SPECTRAL_RESPONSE37_TABLE_LENGTH) {
                line = line.substring(1); // skip one blank
                line = line.trim();
                st = new StringTokenizer(line, "   ", false);

                if (st.hasMoreTokens()) {
                    // wavelengthh
                    aatsrSpectralResponse37Table.setWavelength(i, Double.parseDouble(st.nextToken()));
                }
                if (st.hasMoreTokens()) {
                    // response
                    aatsrSpectralResponse37Table.setResponse(i, Double.parseDouble(st.nextToken()));
                }
                i++;
            }
        } catch (IOException e) {
            throw new OperatorException("Failed to load AATSR Spectral Response Table: \n" + e.getMessage(), e);
        } catch (NumberFormatException e) {
            throw new OperatorException("Failed to load AATSR Spectral Response Table: \n" + e.getMessage(), e);
        } finally {
            inputStream.close();
        }
        return aatsrSpectralResponse37Table;
    }

    /**
     * This method creates a {@link CahalanTable} with data for AATSR.
     *
     * @return  CahalanTable
     * @throws IOException
     */
    public CahalanTable createCahalanTable() throws IOException {
        final InputStream inputStream = getClass().getResourceAsStream(CAHALAN_FILE_NAME);
        CahalanTable cahalanTable = new CahalanTable();

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringTokenizer st;
        try {
            int i = 0;
            String line;
            while ((line = bufferedReader.readLine()) != null && i < CAHALAN_TABLE_LENGTH) {
                line = line.trim();
                st = new StringTokenizer(line, "   ", false);

                if (st.hasMoreTokens()) {
                    // x (whatever that is)
                    cahalanTable.setX(i, Double.parseDouble(st.nextToken()));
                }
                if (st.hasMoreTokens()) {
                    // y
                    cahalanTable.setY(i, Double.parseDouble(st.nextToken()));
                }
                i++;
            }
        } catch (IOException e) {
            throw new OperatorException("Failed to load Cahalan Table: \n" + e.getMessage(), e);
        } catch (NumberFormatException e) {
            throw new OperatorException("Failed to load Cahalan Table: \n" + e.getMessage(), e);
        } finally {
            inputStream.close();
        }
        return cahalanTable;
    }

    /**
     * This method creates a {@link Temp2RadianceTable} with data for temperature-radiance conversion.
     *
     * @return  Temp2RadianceTable
     * @throws IOException
     */
    public Temp2RadianceTable createTemp2RadianceTable() throws IOException {
        final InputStream inputStream = getClass().getResourceAsStream(TEMP2RAD_FILE_NAME);
        Temp2RadianceTable temp2radTable = new Temp2RadianceTable();

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringTokenizer st;
        try {
            int i = 0;
            String line;
            while ((line = bufferedReader.readLine()) != null && i < TEMP2RAD_TABLE_LENGTH) {
                line = line.trim();
                st = new StringTokenizer(line, "   ", false);

                if (st.hasMoreTokens()) {
                    // temperature (K)
                    temp2radTable.setTemp(i, Double.parseDouble(st.nextToken()));
                }
                if (st.hasMoreTokens()) {
                    // radiance
                    temp2radTable.setRad(i, Double.parseDouble(st.nextToken()));
                }
                i++;
            }
        } catch (IOException e) {
            throw new OperatorException("Failed to load Temp2RadianceTable Table: \n" + e.getMessage(), e);
        } catch (NumberFormatException e) {
            throw new OperatorException("Failed to load Temp2RadianceTable Table: \n" + e.getMessage(), e);
        } finally {
            inputStream.close();
        }
        return temp2radTable;
    }

    /**
     * This method provides the nearest index in {@link CahalanTable} for given
     * input wavelength
     *
     * @param wavelength - input wavelength
     * @param tableWavelengths - table wavelengths
     * @return int
     */
    public int getNearestCahalanTableIndex(double wavelength, double[] tableWavelengths) {
       return GlintHelpers.getNearestValueIndexInDescendingDoubleArray(wavelength, tableWavelengths);
    }

    /**
     * This method provides the nearest index in {@link Temp2RadianceTable} for given
     * input temp
     *
     * @param temp - input temp
     * @param tableTemps - table temps
     * @return int
     */
    public int getNearestTemp2RadianceTableIndex(double temp, double[] tableTemps) {
       return GlintHelpers.getNearestValueIndexInAscendingDoubleArray(temp, tableTemps);
    }


    /**
     * This method provides an integration of a function y(x) over the interval [x1, x3]
     * following Simpson's rule for a constant stepsize h in x direction.
     * It must be made sure that y2 = y(x2)!
     *
     * @param y1 , y(x1)
     * @param y2 , y(x2)
     * @param y3 , y(x3)
     * @param  intervalSize, x3-x1
     *
     * @return double
     */
    public double getSimpsonIntegral(double y1, double y2, double y3, double intervalSize) {
        double h = intervalSize/6.0;
        return h*(y1 + 4.0*y2 + y3);
    }

    /**
     * Class providing a AATSR spectral response data table
     */
    public class AatsrSpectralResponse37Table {
        private double[] wavelength = new double[AATSR_SPECTRAL_RESPONSE37_TABLE_LENGTH];
        private double[] response = new double[AATSR_SPECTRAL_RESPONSE37_TABLE_LENGTH];

        public double[] getWavelength() {
            return wavelength;
        }

        public void setWavelength(int index, double value) {
            wavelength[index] = value;
        }

        public double[] getResponse() {
            return response;
        }

        public void setResponse(int index, double value) {
            response[index] = value;
        }
    }

    /**
     * Class providing a Cahalan data table
     */
    public class CahalanTable {
        // todo: clarify the meaning of the columns and give proper names
        private double[] x = new double[CAHALAN_TABLE_LENGTH];
        private double[] y = new double[CAHALAN_TABLE_LENGTH];

        public double[] getX() {
            return x;
        }

        public void setX(int index, double value) {
            x[index] = value;
        }

        public double[] getY() {
            return y;
        }

        public void setY(int index, double value) {
            y[index] = value;
        }
    }

    /**
     * Class providing a temperature-radiance conversion data table
     */
    public class Temp2RadianceTable {
        // todo: clarify the meaning of the columns and give proper names
        private double[] temp = new double[TEMP2RAD_TABLE_LENGTH];
        private double[] rad = new double[TEMP2RAD_TABLE_LENGTH];

        public double[] getTemp() {
            return temp;
        }

        public void setTemp(int index, double value) {
            temp[index] = value;
        }

        public double[] getRad() {
            return rad;
        }

        public void setRad(int index, double value) {
            rad[index] = value;
        }
    }

}
