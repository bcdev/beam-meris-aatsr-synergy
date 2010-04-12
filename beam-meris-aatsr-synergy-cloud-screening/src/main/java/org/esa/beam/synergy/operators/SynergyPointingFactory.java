package org.esa.beam.synergy.operators;

import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Pointing;
import org.esa.beam.framework.datamodel.PointingFactory;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.datamodel.TiePointGridPointing;
import org.esa.beam.synergy.util.SynergyConstants;

/*
 * This class is needed to assign a PointingFactory to our Synergy products,
 * which in turn is needed for our products to be orthorectificable.
 */

public class SynergyPointingFactory implements PointingFactory {

    private static final String[] PRODUCT_TYPES = new String[]{
    	SynergyConstants.SYNERGY_RR_PRODUCT_TYPE_NAME,
    	SynergyConstants.SYNERGY_FR_PRODUCT_TYPE_NAME,
    	SynergyConstants.SYNERGY_FS_PRODUCT_TYPE_NAME,
    	SynergyConstants.SYNERGY_RR_PRODUCT_TYPE_NAME + "_CS",
    	SynergyConstants.SYNERGY_FR_PRODUCT_TYPE_NAME + "_CS",
    	SynergyConstants.SYNERGY_FS_PRODUCT_TYPE_NAME + "_CS",
    };
	
	public String[] getSupportedProductTypes() {
		return PRODUCT_TYPES;
	}
	
	public Pointing createPointing(RasterDataNode raster) {
        final Product product = raster.getProduct();
        // These and MERIS ones are the same
        return new TiePointGridPointing(raster.getGeoCoding(),
                                        product.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME),
                                        product.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME),
                                        product.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME),
                                        product.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME),
                                        product.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME));
	}
}
