package org.esa.beam.synergy.operators;

import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.synergy.util.SynergyLookupTable;
import org.esa.beam.util.math.IntervalPartition;
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
     * @param lutPath
     * @throws IOException
     */
    public AerosolModelTable createAerosolModelTable(String lutPath) throws IOException {
        AerosolModelTable aerosolModelTable = new AerosolModelTable();
        try {
            final NetcdfFile netcdfFile = NetcdfFile.open(lutPath + File.separator + SynergyConstants.AEROSOL_MODEL_FILE_NAME);

            // the variables in the netcdf file are defined like this (as obtained from an ncdump):
            //       String MODEL (MODEL_dimension_1=40);
            //       float ANG(ANG_dimension_1=40, ANG_dimension_2=3);
            //       float GGS(GGS_dimension_1=40, GGS_dimension_2=2);
            //       float WOS(WOS_dimension_1=40, WOS_dimension_2=2);

            final Variable model = netcdfFile.findVariable("MODEL");
            final Variable ang = netcdfFile.findVariable("ANG");
            final Variable ggs = netcdfFile.findVariable("GGS");
            final Variable wos = netcdfFile.findVariable("WOS");


            final int[] modelShape = model.getShape();
            final int[] modelOrigin = new int[2];
            final int[] modelSize = new int[] {modelShape[0], modelShape[1]};
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

            final float[][] angArray = getJavaFloat2DFromNetcdfVariable(ang);
            aerosolModelTable.setAngArray(angArray);
            final float[][] ggsArray = getJavaFloat2DFromNetcdfVariable(ggs);
            aerosolModelTable.setGgsArray(ggsArray);
            final float[][] wosArray = getJavaFloat2DFromNetcdfVariable(wos);
            aerosolModelTable.setWosArray(wosArray);


        } catch (UnsupportedEncodingException e) {
            throw new OperatorException("Failed to read aerosol properties from netcdf file.\n");
        }
        return aerosolModelTable;

    }

    /**
     *
     * This method reads all LUT files for ocean aerosol retrieval and creates
     * corresponding {@link SynergyLookupTable} objects.
     *
     * @param inputPath - file input path
     * @param modelIndices - aerosol model indices
     * @param wvl - array with wavelengths
     * @param wvlIndex - wavelengths index
     * @return  LookupTable[][]
     * @throws IOException
     */
    public SynergyLookupTable[][] createAerosolOceanLookupTables(String inputPath, List<Integer> modelIndices, float[] wvl, int[] wvlIndex) throws IOException {

        final DecimalFormat df2 = new DecimalFormat("00");
        final DecimalFormat df5 = new DecimalFormat("00000");

        final int nModels = modelIndices.size();
        final int nWvl = wvlIndex.length;
        SynergyLookupTable[][] aerosolLookupTables = new SynergyLookupTable[nModels][nWvl];
        for (int i=0; i<nModels; i++) {
            for (int j=0; j<nWvl; j++) {
                final String sb2=(df2.format((long)modelIndices.get(i)));
                final String sb5=(df5.format((long)wvl[wvlIndex[j]]));
                final String inputFileString = "aer" + sb2 + "_wvl" + sb5 + ".nc";

                try {
                    final NetcdfFile netcdfFile = NetcdfFile.open(inputPath + File.separator + inputFileString);

                    // the variables in the netcdf file are defined like this (as obtained from an ncdump):
                    //       float PRS(ANG_dimension_1=3);
                    //       float TAU(ANG_dimension_1=9);
                    //       float WSP(ANG_dimension_1=8);
                    //       float SUN(ANG_dimension_1=14);
                    //       float VIE(ANG_dimension_1=11);
                    //       float AZI(ANG_dimension_1=19);
                    //       float DATA(ANG_dimension_1=3, ANG_dimension_2=9, ANG_dimension_3=8,
                    //                  ANG_dimension_4=14, ANG_dimension_5=11, ANG_dimension_6=19);

                    final Variable prs = netcdfFile.findVariable("PRS");
                    final Variable tau = netcdfFile.findVariable("TAU");
                    final Variable wsp = netcdfFile.findVariable("WSP");
                    final Variable sun = netcdfFile.findVariable("SUN");
                    final Variable vie = netcdfFile.findVariable("VIE");
                    final Variable azi = netcdfFile.findVariable("AZI");
                    final Variable data = netcdfFile.findVariable("DATA");

                    final float[] prsArray = getJavaFloat1DFromNetcdfVariable(prs);
                    for (int k=0; k<prsArray.length; k++) {
                        // take negative value to get increasing sequence for LUT creation
                        prsArray[k] = -prsArray[k];
                    }
                    final float[] tauArray = getJavaFloat1DFromNetcdfVariable(tau);
                    final float[] wspArray = getJavaFloat1DFromNetcdfVariable(wsp);
                    final float[] sunArray = getJavaFloat1DFromNetcdfVariable(sun);
                    final float[] vieArray = getJavaFloat1DFromNetcdfVariable(vie);
                    final float[] aziArray = getJavaFloat1DFromNetcdfVariable(azi);

                    final Array dataArrayNc = data.read();
                    int dataSize = 1;
                    for (int k=0; k<6; k++) {
                        dataSize *= data.getDimension(k).getLength();
                    }
                    final Object storage = dataArrayNc.getStorage();
                    float[] dataArray = new float[dataSize];
                    System.arraycopy(storage, 0, dataArray, 0, dataSize);

                    // set up LUT
                    final IntervalPartition[] dimensions = IntervalPartition.createArray(
                            aziArray, vieArray, sunArray, wspArray, tauArray, prsArray);

                    aerosolLookupTables[i][j] = new SynergyLookupTable(dataArray, dimensions);
                } catch (UnsupportedEncodingException e) {
                    throw new OperatorException("Failed to read aerosol properties from netcdf file.\n");
                }
            }
        }

        return aerosolLookupTables;
    }

    private float[] getJavaFloat1DFromNetcdfVariable(Variable f) throws IOException {
        final Array fArrayNc = f.read();
        final int fSize = (int) fArrayNc.getSize();
        final Object storage = fArrayNc.getStorage();
        final float[] fArray = new float[fSize];
        System.arraycopy(storage, 0, fArray, 0, fSize);

        return fArray;
    }

    private float[][] getJavaFloat2DFromNetcdfVariable(Variable f) throws IOException {
        float[][] result;

        final int[] fShape = f.getShape();
        final int[] fOrigin = new int[2];
        final int[] fSize = new int[] {fShape[0], fShape[1]};
        final Array fArray;
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
            final String[] models = getModelArray();
            for (int i=0; i<models.length; i++) {
                if (models[i].toLowerCase().startsWith("maritime")) {
                    maritimeIndexList.add(new Integer(i));
                }
            }

            return maritimeIndexList;
        }

        public List<Integer> getMaritimeAndDesertIndices() {
            List<Integer> maritimeAndDesertIndexList = new ArrayList<Integer>();
            final String[] models = getModelArray();
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
