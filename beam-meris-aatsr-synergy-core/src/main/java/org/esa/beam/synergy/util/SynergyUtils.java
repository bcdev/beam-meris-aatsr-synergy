package org.esa.beam.synergy.util;

import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.Tile;
import ucar.nc2.NetcdfFile;

import javax.swing.JOptionPane;
import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * Utility class for whole Synergy project
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class SynergyUtils {

    /**
     * Validation of auxiliary data (i.e., LUTs)
     *
     * @param useCustomLandAerosol    - true if custom models are used instead of defaults
     * @param customLandAerosolString - string with model numbers (comma separated list)
     *
     * @return boolean
     */
    public static boolean validateAuxdata(boolean useCustomLandAerosol, String customLandAerosolString) {

        String auxdataPathRoot =
                SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT;
        String auxdataPathAerosolOcean =
                SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT + File.separator + "aerosolLUTs" + File.separator + "ocean";
        String auxdataPathAerosolLand =
                SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT + File.separator + "aerosolLUTs" + File.separator + "land";
        String auxdataPathAerosolLandMeris = auxdataPathAerosolLand + File.separator + "MERIS";
        String auxdataPathAerosolLandAatsr = auxdataPathAerosolLand + File.separator + "AATSR";

        // surface reflectance spectra
        String soilSpecFileString = auxdataPathRoot + File.separator + SynergyConstants.SOIL_SPEC_PARAM_DEFAULT;
        String vegSpecFileString = auxdataPathRoot + File.separator + SynergyConstants.VEG_SPEC_PARAM_DEFAULT;
        try {
            BufferedReader soilReader = new BufferedReader(new FileReader(soilSpecFileString));
            BufferedReader vegReader = new BufferedReader(new FileReader(vegSpecFileString));
        } catch (IOException e) {
            SynergyUtils.logErrorMessage(SynergyConstants.AUXDATA_ERROR_MESSAGE);
            return false;
        }

        // Gauss and Mie LUTs
        try {
            final NetcdfFile netcdfFileMie =
                    NetcdfFile.open(
                            auxdataPathAerosolOcean + File.separator + SynergyConstants.AEROSOL_MODEL_FILE_NAME);
        } catch (IOException e) {
            SynergyUtils.logErrorMessage(SynergyConstants.AUXDATA_ERROR_MESSAGE);
            return false;
        }

        // Ocean aerosol LUTs
        DecimalFormat df2 = new DecimalFormat("00");
        final int NUMBER_OCEAN_AEROSOL_MODELS = 40;
        final int NUMBER_OCEAN_AEROSOL_AATSR_WAVELENGTHS = 4;
        final String[] oceanAerosolAatsrWavelengths = {"00778", "00865", "00885", "01610"};
        for (int i = 1; i <= NUMBER_OCEAN_AEROSOL_MODELS; i++) {
            for (int j = 0; j < NUMBER_OCEAN_AEROSOL_AATSR_WAVELENGTHS; j++) {
                String modelIndex = (df2.format((long) i));
                String inputFileString = "aer" + modelIndex + "_wvl" + oceanAerosolAatsrWavelengths[j] + ".nc";
                try {
                    final NetcdfFile netcdfFileOceanLuts =
                            NetcdfFile.open(auxdataPathAerosolOcean + File.separator + inputFileString);
                } catch (IOException e) {
                    SynergyUtils.logErrorMessage(SynergyConstants.AUXDATA_ERROR_MESSAGE);
                    return false;
                }
            }
        }

        List<Integer> landAerosolModels = readAerosolLandModelNumbers(useCustomLandAerosol, customLandAerosolString);
        // MERIS land aerosol LUTs
        final int NUMBER_LAND_AEROSOL_MODELS = landAerosolModels.size();
        final int NUMBER_LAND_AEROSOL_MERIS_WAVELENGTHS = 13;
        final String[] landAerosolMerisWavelengths = {
                "00412", "00442", "00490", "00510", "00560", "00620", "00665", "00681",
                "00708", "00753", "00778", "00865", "00885"
        };
        for (int i = 0; i < NUMBER_LAND_AEROSOL_MODELS; i++) {
            for (int j = 0; j < NUMBER_LAND_AEROSOL_MERIS_WAVELENGTHS; j++) {
                String modelIndex = (df2.format((long) landAerosolModels.get(i)));
                String inputFileString = "MERIS_" + landAerosolMerisWavelengths[j] + ".00_" + modelIndex;
                try {
                    BufferedReader merisReader = new BufferedReader(
                            new FileReader(auxdataPathAerosolLandMeris + File.separator + inputFileString));
                } catch (IOException e) {
                    SynergyUtils.logErrorMessage(SynergyConstants.AUXDATA_ERROR_MESSAGE);
                    return false;
                }
            }
        }

        // AATSR land aerosol LUTs
        final int NUMBER_LAND_AEROSOL_AATSR_WAVELENGTHS = 4;
        final String[] landAerosolAatsrWavelengths = {"00550", "00665", "00865", "01610"};
        for (int i = 0; i < NUMBER_LAND_AEROSOL_MODELS; i++) {
            for (int j = 0; j < NUMBER_LAND_AEROSOL_AATSR_WAVELENGTHS; j++) {
                String modelIndex = (df2.format((long) landAerosolModels.get(i)));
                String inputFileString = "AATSR_" + landAerosolAatsrWavelengths[j] + ".00_" + modelIndex;
                try {
                    BufferedReader aatsrReader = new BufferedReader(
                            new FileReader(auxdataPathAerosolLandAatsr + File.separator + inputFileString));
                } catch (IOException e) {
                    SynergyUtils.logErrorMessage(SynergyConstants.AUXDATA_ERROR_MESSAGE);
                    return false;
                }
            }
        }


        return true;
    }

    /**
     * Read land aerosol model numbers from string and provide as list
     *
     * @param useCustomLandAerosol    - true if custom models are used instead of defaults
     * @param customLandAerosolString - string with model numbers (comma separated list)
     *
     * @return List<Integer>
     */
    public static List<Integer> readAerosolLandModelNumbers(boolean useCustomLandAerosol,
                                                            String customLandAerosolString) {
        List<Integer> aerosolModels;
        String landAerosolModelString = SynergyConstants.AEROSOL_MODEL_PARAM_DEFAULT;
        if (useCustomLandAerosol) {
            landAerosolModelString = customLandAerosolString;
        }
        landAerosolModelString = landAerosolModelString.trim();
        aerosolModels = new ArrayList<Integer>();
        try {
            StringTokenizer st = new StringTokenizer(landAerosolModelString, ",", false);
            while (st.hasMoreTokens()) {
                int iAerMo = Integer.parseInt(st.nextToken());
                if (iAerMo < 1 || iAerMo > 40) {
                    throw new OperatorException("Invalid aerosol model number: " + iAerMo + "\n");
                }
                aerosolModels.add(iAerMo);
            }
        } catch (Exception e) {
            throw new OperatorException("Could not parse input list of aerosol models: \n" + e.getMessage(), e);
        }
        return aerosolModels;
    }

    public static void logInfoMessage(String msg) {
        if (System.getProperty("gpfMode") != null && System.getProperty("gpfMode").equals("GUI")) {
            JOptionPane.showOptionDialog(null, msg, "MERIS/(A)ATSR Synergy - Info Message", JOptionPane.DEFAULT_OPTION,
                                         JOptionPane.INFORMATION_MESSAGE, null, null, null);
        } else {
            info(msg);
        }
    }

    public static void logErrorMessage(String msg) {
        if (System.getProperty("gpfMode") != null && System.getProperty("gpfMode").equals("GUI")) {
            JOptionPane.showOptionDialog(null, msg, "MERIS/(A)ATSR Synergy - Error Message", JOptionPane.DEFAULT_OPTION,
                                         JOptionPane.ERROR_MESSAGE, null, null, null);
        } else {
            info(msg);
        }
    }

    private static java.util.logging.Logger logger = java.util.logging.Logger.getLogger("synergy");

    private static final String[] REQUIRED_MERIS_TPG_NAMES = {
            EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME,
            EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME,
            EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME,
            EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME,
            EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME,
            "atm_press",
            "ozone",
    };

    private static final String[] REQUIRED_AATSR_TPG_NAMES =
            EnvisatConstants.AATSR_TIE_POINT_GRID_NAMES;

    /*
     * Implementation of comparator for bands using the wavelength
     */
    public static final class BandComparator implements java.util.Comparator<Band> {

        public int compare(Band b1, Band b2) {
            final float dif = b1.getSpectralWavelength() - b2.getSpectralWavelength();
            if (dif < 0) {
                return -1;
            } else if (dif > 0) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    /*
     * Does the same getTargetTile, but for arrays
     */
    public static Tile[] getTargetTiles(Band[] bands, Map<Band, Tile> targetTiles) {
        final Tile[] tile = new Tile[bands.length];
        for (int i = 0; i < tile.length; i++) {
            tile[i] = targetTiles.get(bands[i]);
        }
        return tile;
    }

    /*
     * The same that getSourceTile, but for arrays
     */
    public static Tile[] getSourceTiles(Band[] bands, Rectangle targetRectangle, Operator op) {
        final Tile[] sourceTiles = new Tile[bands.length];

        for (int i = 0; i < bands.length; ++i) {
            sourceTiles[i] = op.getSourceTile(bands[i], targetRectangle);
        }
        return sourceTiles;
    }

    /*
     * Searches for a band containing 'str' and returns it
     */
    public static Band searchBand(Product product, String str) {
        for (Band b : product.getBands()) {
            if (b.getName().contains(str)) {
                return b;
            }
        }
        return null;
    }

    public static boolean isRR(final Product product) {
        return (product.getProductType().indexOf(SynergyConstants.RR_STR) > 0);
    }

    public static boolean isFR(final Product product) {
        return (product.getProductType().indexOf(SynergyConstants.FR_STR) > 0);
    }

    public static boolean isFS(final Product product) {
        return (product.getProductType().indexOf(SynergyConstants.FS_STR) > 0);
    }

    public static boolean isFSG(final Product product) {
        return (product.getProductType().indexOf(SynergyConstants.FSG_STR) > 0);
    }

    public static void info(final String msg) {
        logger.info(msg);
        System.out.println(msg);
    }

    public static void validateCloudScreeningProduct(final Product cloudScreeningProduct) {
        validatePreprocessedProduct(cloudScreeningProduct);
        List<String> sourceBandNameList = Arrays.asList(cloudScreeningProduct.getBandNames());
        if (!sourceBandNameList.contains(SynergyConstants.B_CLOUDFLAGS)) {
            String message = MessageFormat.format(
                    "Missing required flag band in input product: {0} . Not a preprocessed product with cloud flags?",
                    SynergyConstants.B_CLOUDFLAGS);
            throw new OperatorException(message);
        }
    }

    public static void validatePreprocessedProduct(final Product preprocessedProduct) {
        final String missedBand = validatePreprocessedProductBands(preprocessedProduct);
        if (!missedBand.isEmpty()) {
            String message = MessageFormat.format(
                    "Missing required band in input product: {0} . Not a preprocessed product?",
                    missedBand + "_MERIS");
            throw new OperatorException(message);
        }
        final String missedTPG = validateMerisProductTpgs(preprocessedProduct);
        if (!missedTPG.isEmpty()) {
            String message = MessageFormat.format(
                    "Missing required tie-point grid in input product: {0} . Not a preprocessed product?",
                    missedTPG);
            throw new OperatorException(message);
        }
    }


    public static void validateMerisProduct(final Product merisProduct) {
        final String missedBand = validateMerisProductBands(merisProduct);
        if (!missedBand.isEmpty()) {
            String message = MessageFormat.format(
                    "Missing required band in MERIS input product: {0} . Not a L1b product?",
                    missedBand);
            throw new OperatorException(message);
        }
        final String missedTPG = validateMerisProductTpgs(merisProduct);
        if (!missedTPG.isEmpty()) {
            String message = MessageFormat.format(
                    "Missing required tie-point grid in MERIS input product: {0} . Not a L1b product?",
                    missedTPG);
            throw new OperatorException(message);
        }
    }

    public static void validateAatsrProduct(final Product aatsrProduct) {
        if (aatsrProduct != null) {
            final String missedBand = validateAatsrProductBands(aatsrProduct);
            if (!missedBand.isEmpty()) {
                String message = MessageFormat.format(
                        "Missing required band in AATSR input product: {0} . Not a L1b product?",
                        missedBand);
                throw new OperatorException(message);
            }
            final String missedTPG = validateAatsrProductTpgs(aatsrProduct);
            if (!missedTPG.isEmpty()) {
                String message = MessageFormat.format(
                        "Missing required tie-point grid in AATSR input product: {0} . Not a L1b product?",
                        missedTPG);
                throw new OperatorException(message);
            }
        }
    }

    private static String validatePreprocessedProductBands(Product product) {
        List<String> sourceBandNameList = Arrays.asList(product.getBandNames());
        for (String bandName : EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES) {
            if (!sourceBandNameList.contains(bandName + "_MERIS")) {
                return bandName;
            }
        }
        if (!sourceBandNameList.contains(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME + "_MERIS")) {
            return EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME;
        }

        for (String bandName : EnvisatConstants.AATSR_L1B_BAND_NAMES) {
            if (!sourceBandNameList.contains(bandName + "_AATSR")) {
                return bandName;
            }
        }

        return "";
    }


    private static String validateMerisProductBands(Product product) {
        List<String> sourceBandNameList = Arrays.asList(product.getBandNames());
        for (String bandName : EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES) {
            if (!sourceBandNameList.contains(bandName)) {
                return bandName;
            }
        }
        if (!sourceBandNameList.contains(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME)) {
            return EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME;
        }

        return "";
    }

    private static String validateAatsrProductBands(Product product) {
        List<String> sourceBandNameList = Arrays.asList(product.getBandNames());
        for (String bandName : EnvisatConstants.AATSR_L1B_BAND_NAMES) {
            if (!sourceBandNameList.contains(bandName)) {
                return bandName;
            }
        }

        return "";
    }

    private static String validateMerisProductTpgs(Product product) {
        List<String> sourceTpgNameList = Arrays.asList(product.getTiePointGridNames());
        for (String tpgName : REQUIRED_MERIS_TPG_NAMES) {
            if (!sourceTpgNameList.contains(tpgName)) {
                return tpgName;
            }
        }

        return "";
    }

    private static String validateAatsrProductTpgs(Product product) {
        List<String> sourceTpgNameList = Arrays.asList(product.getTiePointGridNames());
        for (String tpgName : REQUIRED_AATSR_TPG_NAMES) {
            if (!sourceTpgNameList.contains(tpgName)) {
                return tpgName;
            }
        }

        return "";
    }

}
