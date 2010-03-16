/* 
 * Copyright (C) 2002-2008 by Brockmann Consult
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.synergy.operators;

import java.util.HashMap;
import java.util.Map;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.meris.brr.CloudClassificationOp;
import org.esa.beam.meris.brr.GaseousCorrectionOp;
import org.esa.beam.meris.brr.LandClassificationOp;
import org.esa.beam.meris.brr.Rad2ReflOp;

/**
 * Operator for Land/water reclassification.
 *
 * @author Olaf Danne
 * @version $Revision: 5849 $ $Date: 2009-07-02 15:07:05 +0200 (Do, 02 Jul 2009) $
 */
@OperatorMetadata(alias = "synergy.LandWaterReclassification",
        version = "1.0-SNAPSHOT",
        authors = "Olaf Danne",
        copyright = "(c) 2008 by Brockmann Consult",
        description = "This operator just calls a chain of other operators.")
public class LandWaterReclassificationOp extends Operator {
	@SourceProduct(alias = "source", 
			label = "Name (MERIS L1b product)",
			description = "Select a MERIS L1b product.")
    Product sourceProduct;

    @TargetProduct(description = "The target product.")
    Product targetProduct;
    
	@Override
    public void initialize() throws OperatorException {
        sourceProduct.setPreferredTileSize(128, 128);
        
        // Radiance to Reflectance
        Map<String, Object> emptyParams = new HashMap<String, Object>();
        Product rad2reflProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(Rad2ReflOp.class), emptyParams, sourceProduct);
 
        // Cloud Top Pressure
        Product ctpProduct = GPF.createProduct("Meris.CloudTopPressureOp", emptyParams, sourceProduct);
        
        // Cloud Classification
        Map<String, Product> cloudInput = new HashMap<String, Product>(3);
        cloudInput.put("l1b", sourceProduct);
        cloudInput.put("rhotoa", rad2reflProduct);
        cloudInput.put("ctp", ctpProduct);
        Product cloudProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(CloudClassificationOp.class), emptyParams, cloudInput);
        
        // Gaseous Correction
        Map<String, Product> gasInput = new HashMap<String, Product>(3);
        gasInput.put("l1b", sourceProduct);
        gasInput.put("rhotoa", rad2reflProduct);
        gasInput.put("cloud", cloudProduct);
        Map<String, Object> gasParameters = new HashMap<String, Object>(2);
        gasParameters.put("correctWater", true);
        gasParameters.put("exportTg", true);
        Product gasProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(GaseousCorrectionOp.class), gasParameters, gasInput);

        // Land Water Reclassification
        Map<String, Product> landInput = new HashMap<String, Product>(2);
        landInput.put("l1b", sourceProduct);
        landInput.put("gascor", gasProduct);
        Product landProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(LandClassificationOp.class), emptyParams, landInput);
        
        String productType = sourceProduct.getProductType();
        String productName = sourceProduct.getName();
        int sceneWidth = sourceProduct.getSceneRasterWidth();
        int sceneHeight = sourceProduct.getSceneRasterHeight();
        
        targetProduct = new Product(productName, productType, sceneWidth, sceneHeight);
        // targetProduct = createCompatibleProduct(sourceProduct, "MER", "MER_L2");
       
        FlagCoding flagCoding = LandClassificationOp.createFlagCoding();
    	targetProduct.getFlagCodingGroup().add(flagCoding);
        for (Band band:landProduct.getBands()) {
        	if (band.getName().equals(LandClassificationOp.LAND_FLAGS)) {
        		band.setSampleCoding(flagCoding);
        	}
        	targetProduct.addBand(band);
        }
    }
    
    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(LandWaterReclassificationOp.class);
        }
    }
}
