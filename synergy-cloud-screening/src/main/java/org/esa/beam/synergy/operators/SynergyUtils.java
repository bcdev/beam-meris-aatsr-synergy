package org.esa.beam.synergy.operators;

import java.awt.Rectangle;
import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.text.MessageFormat;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.dataio.envisat.EnvisatConstants;

import com.bc.ceres.core.ProgressMonitor;


public class SynergyUtils {
    
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

    public static void validateMerisProduct(final Product merisProduct) {
        final String missedBand = validateMerisProductBands(merisProduct);
        if (!missedBand.isEmpty()) {
            String message = MessageFormat.format("Missing required band in product {0}: {1}",
                                                  merisProduct.getName(), missedBand);
            throw new OperatorException(message);
        }
        final String missedTPG = validateMerisProductTpgs(merisProduct);
        if (!missedTPG.isEmpty()) {
            String message = MessageFormat.format("Missing required tie-point grid in product {0}: {1}",
                                                  merisProduct.getName(), missedTPG);
            throw new OperatorException(message);
        }
    }

    public static void validateAatsrProduct(final Product aatsrProduct) {
        if (aatsrProduct != null) {
            final String missedBand = validateAatsrProductBands(aatsrProduct);
            if (!missedBand.isEmpty()) {
                String message = MessageFormat.format("Missing required band in product {0}: {1}",
                                                      aatsrProduct.getName(), missedBand);
                throw new OperatorException(message);
            }
            final String missedTPG = validateAatsrProductTpgs(aatsrProduct);
            if (!missedTPG.isEmpty()) {
                String message = MessageFormat.format("Missing required tie-point grid in product {0}: {1}",
                                                      aatsrProduct.getName(), missedTPG);
                throw new OperatorException(message);
            }
        }
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
