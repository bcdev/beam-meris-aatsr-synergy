package org.esa.beam.synergy.operators;

import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.util.math.IntervalPartition;
import org.esa.beam.util.math.LookupTable;
import ucar.ma2.Array;
import ucar.ma2.InvalidRangeException;
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
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Class providing aerosol retrieval auxdata.
 *
 * @author Olaf Danne
 * @version $Revision: 8092 $ $Date: 2010-01-26 19:08:16 +0100 (Di, 26 Jan 2010) $
 */
public class AerosolAuxData {
    private static AerosolAuxData instance;

    private static final String AEROSOL_CLASS_FILE_NAME = "aerosol_classes.d";
    private static final int AEROSOL_CLASS_TABLE_MAXLENGTH = 20;

    private static final String AEROSOL_MODEL_FILE_NAME = "all_mie.nc";

    public static AerosolAuxData getInstance() {
        if (instance == null) {
            instance = new AerosolAuxData();
        }

        return instance;
    }

    /**
     * This method provides an {@link AerosolClassTable}: a table holding aerosol class names
     *
     * @return aerosolClassTable
     * @throws IOException
     */
    public AerosolClassTable createAerosolClassTable() throws IOException {
        final InputStream inputStream = GlintAveOp.class.getResourceAsStream(AEROSOL_CLASS_FILE_NAME);
        AerosolClassTable aerosolClassTable = new AerosolClassTable();

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringTokenizer st;
        try {
            int i = 0;
            String line;
            while ((line = bufferedReader.readLine()) != null && i < AEROSOL_CLASS_TABLE_MAXLENGTH) {
                line = line.trim();
                st = new StringTokenizer(line, "   ", false);

                if (st.hasMoreTokens()) {
                    // aerosol class name
                    aerosolClassTable.setClassName(i, st.nextToken());
                }
                i++;
            }
        } catch (IOException e) {
            throw new OperatorException("Failed to load AerosolClassTable Table: \n" + e.getMessage(), e);
        } catch (NumberFormatException e) {
            throw new OperatorException("Failed to load AerosolClassTable Table: \n" + e.getMessage(), e);
        } finally {
            inputStream.close();
        }
        return aerosolClassTable;
    }

    /**
     * This method provides an {@link AerosolModelTable}: a table holding aerosol model info
     *
     * @return aerosolModelTable
     * @throws IOException
     */
    public AerosolModelTable createAerosolModelTable(String lutPath) throws IOException {
        AerosolModelTable aerosolModelTable = new AerosolModelTable();
        try {
            final NetcdfFile netcdfFile = NetcdfFile.open(lutPath + File.separator + AEROSOL_MODEL_FILE_NAME);

            // the variables in the netcdf file are defined like this (as obtained from an ncdump):
            //       String MODEL (MODEL_dimension_1=40);
            //       float ANG(ANG_dimension_1=40, ANG_dimension_2=3);
            //       float GGS(GGS_dimension_1=40, GGS_dimension_2=2);
            //       float WOS(WOS_dimension_1=40, WOS_dimension_2=2);

            Variable model = netcdfFile.findVariable("MODEL");
            Variable ang = netcdfFile.findVariable("ANG");
            Variable ggs = netcdfFile.findVariable("GGS");
            Variable wos = netcdfFile.findVariable("WOS");


            int[] modelShape = model.getShape();
            int[] modelOrigin = new int[2];
            int[] modelSize = new int[] {modelShape[0], modelShape[1]};
            Array modelArray;
            try {
                modelArray = model.read(modelOrigin, modelSize);
            } catch (InvalidRangeException e) {
                throw new OperatorException(e.getMessage());
            }

            char[][] cArray = new char[modelShape[0]][modelShape[1]];
            String[] modelJavaArray = new String[modelShape[0]];
            for (int i=0; i<modelShape[0]; i++) {
                for (int j=0; j<modelShape[1]; j++) {
                    cArray[i][j] = (char) modelArray.getByte(modelArray.getIndex().set(i, j));
                }
                modelJavaArray[i] = new String(cArray[i]).trim();
            }
            aerosolModelTable.setModelArray(modelJavaArray);

            float[][] angArray = getJavaFloat2DFromNetcdfVariable(ang);
            aerosolModelTable.setAngArray(angArray);
            float[][] ggsArray = getJavaFloat2DFromNetcdfVariable(ggs);
            aerosolModelTable.setGgsArray(ggsArray);
            float[][] wosArray = getJavaFloat2DFromNetcdfVariable(wos);
            aerosolModelTable.setWosArray(wosArray);


        } catch (UnsupportedEncodingException e) {
            throw new OperatorException("Failed to read aerosol properties from netcdf file.\n");
        }
        return aerosolModelTable;

    }

    /**
     *
     * This method reads all LUT files for ocean aerosol retrieval and creates
     * corresponding {@link LookupTable} objects.
     *
     * @param inputPath - file input path
     * @param modelIndices - aerosol model indices
     * @param wvl - array with wavelengths
     * @return  LookupTable[][]
     * @throws IOException
     */
    public LookupTable[][] createAerosolOceanLookupTables(String inputPath, List<Integer> modelIndices, float[] wvl, int[] wvlIndex) throws IOException {

        DecimalFormat df2 = new DecimalFormat("00");
        DecimalFormat df5 = new DecimalFormat("00000");

        int nModels = modelIndices.size();
        int nWvl = wvlIndex.length;
        LookupTable[][] aerosolLookupTables = new LookupTable[nModels][nWvl];
        for (int i=0; i<nModels; i++) {
            for (int j=0; j<nWvl; j++) {
                String sb2=(df2.format((long)modelIndices.get(i)));
                String sb5=(df5.format((long)wvl[wvlIndex[j]]));
                String inputFileString = "aer" + sb2 + "_wvl" + sb5 + ".nc";

                try {
                    final NetcdfFile netcdfFile = NetcdfFile.open(inputPath + File.separator + inputFileString);

                    List variables = netcdfFile.getVariables();

                    // the variables in the netcdf file are defined like this (as obtained from an ncdump):
                    //       float PRS(ANG_dimension_1=3);
                    //       float TAU(ANG_dimension_1=9);
                    //       float WSP(ANG_dimension_1=8);
                    //       float SUN(ANG_dimension_1=14);
                    //       float VIE(ANG_dimension_1=11);
                    //       float AZI(ANG_dimension_1=19);
                    //       float DATA(ANG_dimension_1=3, ANG_dimension_2=9, ANG_dimension_3=8,
                    //                  ANG_dimension_4=14, ANG_dimension_5=11, ANG_dimension_6=19);

                    Variable prs = netcdfFile.findVariable("PRS");
                    Variable tau = netcdfFile.findVariable("TAU");
                    Variable wsp = netcdfFile.findVariable("WSP");
                    Variable sun = netcdfFile.findVariable("SUN");
                    Variable vie = netcdfFile.findVariable("VIE");
                    Variable azi = netcdfFile.findVariable("AZI");
                    Variable data = netcdfFile.findVariable("DATA");

                    float[] prsArray = getJavaFloat1DFromNetcdfVariable(prs);
                    for (int k=0; k<prsArray.length; k++) {
                        // take negative value to get increasing sequence for LUT creation
                        prsArray[k] = -prsArray[k];
                    }
                    float[] tauArray = getJavaFloat1DFromNetcdfVariable(tau);
                    float[] wspArray = getJavaFloat1DFromNetcdfVariable(wsp);
                    float[] sunArray = getJavaFloat1DFromNetcdfVariable(sun);
                    float[] vieArray = getJavaFloat1DFromNetcdfVariable(vie);
                    float[] aziArray = getJavaFloat1DFromNetcdfVariable(azi);

                    Array dataArrayNc = data.read();
                    int dataSize = 1;
                    for (int k=0; k<6; k++) {
                        dataSize *= data.getDimension(k).getLength();
                    }
                    Object storage = dataArrayNc.getStorage();
                    float[] dataArray = new float[dataSize];
                    System.arraycopy(storage, 0, dataArray, 0, dataSize);

                    // set up LUT
                    final IntervalPartition[] dimensions = IntervalPartition.createArray(
                            aziArray, vieArray, sunArray, wspArray, tauArray, prsArray);

                    aerosolLookupTables[i][j] = new LookupTable(dataArray, dimensions);
                } catch (UnsupportedEncodingException e) {
                    throw new OperatorException("Failed to read aerosol properties from netcdf file.\n");
                }
            }
        }

        return aerosolLookupTables;
    }

    private float[] getJavaFloat1DFromNetcdfVariable(Variable f) throws IOException {
        Array fArrayNc = f.read();
        int fSize = (int) fArrayNc.getSize();
        Object storage = fArrayNc.getStorage();
        float[] fArray = new float[fSize];
        System.arraycopy(storage, 0, fArray, 0, fSize);

        return fArray;
    }

    private float[][] getJavaFloat2DFromNetcdfVariable(Variable f) throws IOException {
        float[][] result;

        int[] fShape = f.getShape();
        int[] fOrigin = new int[2];
        int[] fSize = new int[] {fShape[0], fShape[1]};
        Array fArray;
        try {
            fArray = f.read(fOrigin, fSize);
        } catch (InvalidRangeException e) {
            throw new OperatorException("Failed to read float 2D variable from netcdf file.\n");
        }
        result = new float[fShape[0]][fShape[1]];
        for (int i=0; i<fShape[0]; i++) {
            for (int j=0; j<fShape[1]; j++) {
                result[i][j] = fArray.getFloat(fArray.getIndex().set(i,j));
            }
        }

        return result;
    }

    public class AerosolClassTable {
        private String[] className = new String[AEROSOL_CLASS_TABLE_MAXLENGTH];

        public String[] getClassName() {
            return className;
        }

        public void setClassName(int index, String value) {
            className[index] = value;
        }
    }

    public class AerosolModelTable {
        private String[] modelArray;
        private float[][] angArray;
        private float[][] ggsArray;
        private float[][] wosArray;

        public String[] getModelArray() {
            return modelArray;
        }

        public void setModelArray(int index, String value) {
            modelArray[index] = value;
        }

        public void setModelArray(String[] array) {
            modelArray = array;
        }

        public float[][] getAngArray() {
            return angArray;
        }

        public float[] getAngArray(int index) {
            return angArray[index];
        }

        public float[] getAngArray(List<Integer> indices, int index) {
            float[] result = new float[indices.size()];
            for (int i=0; i<indices.size()-1; i++) {
                result[i] = angArray[index][indices.get(i)];
            }
            return result;
        }

        public void setAngArray(int index1, int index2, float value) {
            angArray[index1][index2] = value;
        }

        public void setAngArray(float[][] array) {
            angArray = array;
        }

        public float[][] getGgsArray() {
            return ggsArray;
        }

        public void setGgsArray(int index1, int index2, float value) {
            ggsArray[index1][index2] = value;
        }

        public void setGgsArray(float[][] array) {
            ggsArray = array;
        }

        public float[][] getWosArray() {
            return wosArray;
        }

        public void setWosArray(int index1, int index2, float value) {
            wosArray[index1][index2] = value;
        }

        public void setWosArray(float[][] array) {
            wosArray = array;
        }

        public List<Integer> getMaritimeIndices() {
            List<Integer> maritimeIndexList = new ArrayList<Integer>();
            String[] models = getModelArray();
            for (int i=0; i<models.length; i++) {
                if (models[i].toLowerCase().startsWith("maritime")) {
                    maritimeIndexList.add(new Integer(i));
                }
            }

            return maritimeIndexList;
        }

        public List<Integer> getMaritimeAndDesertIndices() {
            List<Integer> maritimeAndDesertIndexList = new ArrayList<Integer>();
            String[] models = getModelArray();
            for (int i=0; i<models.length; i++) {
                if (models[i].toLowerCase().startsWith("maritime") ||
                        models[i].toLowerCase().startsWith("desert") ||
                        models[i].toLowerCase().startsWith("continental")
                        ) {
                    maritimeAndDesertIndexList.add(new Integer(i));
                }
            }
            return maritimeAndDesertIndexList;
        }
    }

}
