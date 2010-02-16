package org.esa.beam.synergy.operators;

import java.awt.Rectangle;
import java.util.Map;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.Tile;

import com.bc.ceres.core.ProgressMonitor;


public class SynergyUtils {
    
	private static java.util.logging.Logger logger = java.util.logging.Logger.getLogger("synergy");
	
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
}
