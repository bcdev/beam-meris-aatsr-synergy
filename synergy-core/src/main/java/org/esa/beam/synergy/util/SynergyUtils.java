package org.esa.beam.synergy.util;

import org.esa.beam.util.logging.BeamLogManager;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import java.util.logging.Level;
import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.awt.Rectangle;
import java.text.MessageFormat;

import com.bc.ceres.core.ProgressMonitor;

/**
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class SynergyUtils {

    public static void logInfoMessage(String msg) {
        if (System.getProperty("synergyToolboxMode") != null && System.getProperty("synergyToolboxMode").equals("GUI")) {
            JLabel label = new JLabel(msg);
            JOptionPane.showOptionDialog(null, label, "MERIS/(A)ATSR Synergy - Info Message", JOptionPane.DEFAULT_OPTION,
                                         JOptionPane.INFORMATION_MESSAGE, null, null, null);
        } else {
            BeamLogManager.getSystemLogger().log(Level.ALL, msg);
        }
    }

    public static void logErrorMessage(String msg) {
        if (System.getProperty("synergyToolboxMode") != null && System.getProperty("synergyToolboxMode").equals("GUI")) {
            JLabel label = new JLabel(msg);
            JOptionPane.showOptionDialog(null, label, "MERIS/(A)ATSR Synergy - Error Message", JOptionPane.DEFAULT_OPTION,
                                         JOptionPane.ERROR_MESSAGE, null, null, null);
        } else {
            BeamLogManager.getSystemLogger().log(Level.ALL, msg);
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
            if      (dif < 0) return -1;
            else if (dif > 0) return  1;
            else              return  0;
        }
    }

    /*
     * Does the same getTargetTile, but for arrays
     */
    public static final Tile[] getTargetTiles(Band[] bands, Map<Band,Tile> targetTiles) {
    	final Tile[] tile = new Tile[bands.length];
    	for (int i=0; i<tile.length; i++) tile[i] = targetTiles.get(bands[i]);
    	return tile;
    }

    /*
     * The same that getSourceTile, but for arrays
     */
    public static final Tile[] getSourceTiles(Band[] bands, Rectangle targetRectangle, ProgressMonitor pm, Operator op) {
        final Tile[] sourceTiles = new Tile[bands.length];

        for (int i = 0; i < bands.length; ++i) {
            sourceTiles[i] = op.getSourceTile(bands[i], targetRectangle, pm);
        }
        return sourceTiles;
    }

    /*
     * Searches for a band containing 'str' and returns it
     */
    public static Band searchBand(Product product, String str) {
        for (Band b : product.getBands()) {
            if (b.getName().indexOf(str) >= 0) return b;
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

    public static final void info(final String msg) {
    	logger.info(msg);
    	System.out.println(msg);
    }

    public static void validateCloudScreeningProduct(final Product cloudScreeningProduct) {
        validatePreprocessedProduct(cloudScreeningProduct);
        List<String> sourceBandNameList = Arrays.asList(cloudScreeningProduct.getBandNames());
        if (!sourceBandNameList.contains(SynergyConstants.B_CLOUDFLAGS)) {
            String message = MessageFormat.format("Missing required flag band in input product: {0} . Not a preprocessed product with cloud flags?",
                                                  SynergyConstants.B_CLOUDFLAGS);
            throw new OperatorException(message);
        }
    }

    public static void validatePreprocessedProduct(final Product preprocessedProduct) {
        final String missedBand = validatePreprocessedProductBands(preprocessedProduct);
        if (!missedBand.isEmpty()) {
            String message = MessageFormat.format("Missing required band in input product: {0} . Not a preprocessed product?",
                                                  missedBand + "_MERIS");
            throw new OperatorException(message);
        }
        final String missedTPG = validateMerisProductTpgs(preprocessedProduct);
        if (!missedTPG.isEmpty()) {
            String message = MessageFormat.format("Missing required tie-point grid in input product: {0} . Not a preprocessed product?",
                                                  missedTPG);
            throw new OperatorException(message);
        }
    }


    public static void validateMerisProduct(final Product merisProduct) {
        final String missedBand = validateMerisProductBands(merisProduct);
        if (!missedBand.isEmpty()) {
            String message = MessageFormat.format("Missing required band in MERIS input product: {0} . Not a L1b product?",
                                                  missedBand);
            throw new OperatorException(message);
        }
        final String missedTPG = validateMerisProductTpgs(merisProduct);
        if (!missedTPG.isEmpty()) {
            String message = MessageFormat.format("Missing required tie-point grid in MERIS input product: {0} . Not a L1b product?",
                                                  missedTPG);
            throw new OperatorException(message);
        }
    }

    public static void validateAatsrProduct(final Product aatsrProduct) {
        if (aatsrProduct != null) {
            final String missedBand = validateAatsrProductBands(aatsrProduct);
            if (!missedBand.isEmpty()) {
                String message = MessageFormat.format("Missing required band in AATSR input product: {0} . Not a L1b product?",
                                                  missedBand);
                throw new OperatorException(message);
            }
            final String missedTPG = validateAatsrProductTpgs(aatsrProduct);
            if (!missedTPG.isEmpty()) {
                String message = MessageFormat.format("Missing required tie-point grid in AATSR input product: {0} . Not a L1b product?",
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
