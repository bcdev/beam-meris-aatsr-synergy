/*
 * Copyright (C) 2002-2008 by Brockmann Consult
 * Copyright (C) 2008 by IPL
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

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.meris.brr.CloudClassificationOp;
import org.esa.beam.meris.brr.GaseousCorrectionOp;
import org.esa.beam.meris.brr.LandClassificationOp;
import org.esa.beam.meris.brr.Rad2ReflOp;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * Operator for creating a MERIS product.
 *
 * @author Jordi Munyoz-Mari and Luis Gomez-Chova
 * @version $Revision: 7481 $ $Date: 2009-12-11 20:03:20 +0100 (Fr, 11 Dez 2009) $
 */
@OperatorMetadata(alias = "synergy.CreateMeris",
                  version = "1.2",
                  authors = "Jordi Munyoz-Mari and Luis Gomez-Chova",
                  copyright = "(c) 2008-09 by IPL",
                  description = "This operator calls a chain of operators to prepare the MERIS product.",
                  internal = true)

public class CreateMerisOp extends Operator {

    @SourceProduct(alias = "source", description = "The source product.")
    Product sourceProduct;

    @TargetProduct(description = "The target product.")
    Product targetProduct;

    @Parameter(defaultValue = "true",
               description = "Copy MERIS original radiance bands",
               label = "Copy MERIS TOA radiance bands")
    boolean copyToaRadiances;

    @Parameter(defaultValue = "false",
               description = "Add two bands containing MERIS top preassure and the cloud mask",
               label = "Copy MERIS Cloud Top Pressure and Mask (GLOBCOVER)")
    boolean copyCloudTopPreassureAndMask;

    @Parameter(defaultValue = "true",
               description = "Adds a band with MERIS cloud probability",
               label = "Copy MERIS Cloud Probability (BEAM-FUB)")
    boolean copyCloudProbability;

    @Parameter(defaultValue = "false",
               description = "Adds a band with Land Water Reclassification",
               label = "Copy Land Water Reclassification")
    boolean copyLandWaterReclass;

    // Map to rename reflectance bands
    Map<String, String> renameMap = new HashMap<String, String>(EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS);
    // New scaling factor
    final static double scalingFactor = 10000.0;
    private transient Product rad2reflProduct;

    @Override
    public void initialize() throws OperatorException {

        // Declare possible output products
        Product cloudProbabilityProduct = null;
        Product cloudProduct = null;
        Product landProduct = null;

        // Q: is it faster calling operators directly instead of through GPF?
        // A: (after asking Norman): No. Besides, operators don't allow to pass parameters

        // BEAM-FUB cloud probability
        if (copyCloudProbability) {
            Map<String, Product> pInput = new HashMap<String, Product>(1);
            pInput.put("input", sourceProduct);
            cloudProbabilityProduct = GPF.createProduct("Meris.CloudProbability", GPF.NO_PARAMS, pInput);
//            cloudProbabilityProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(CloudProbabilityOp.class), GPF.NO_PARAMS, pInput);
        }

        // Radiance to Reflectance
        rad2reflProduct =
                GPF.createProduct(OperatorSpi.getOperatorAlias(Rad2ReflOp.class), GPF.NO_PARAMS, sourceProduct);

        if (copyCloudTopPreassureAndMask || copyLandWaterReclass) {
            // Cloud Top Pressure
            final Product ctpProduct = GPF.createProduct("Meris.CloudTopPressureOp", GPF.NO_PARAMS, sourceProduct);

            // Cloud Classification
            Map<String, Product> cloudInput = new HashMap<String, Product>(3);
            cloudInput.put("l1b", sourceProduct);
            cloudInput.put("rhotoa", rad2reflProduct);
            cloudInput.put("ctp", ctpProduct);
            cloudProduct =
                    GPF.createProduct(OperatorSpi.getOperatorAlias(CloudClassificationOp.class), GPF.NO_PARAMS,
                                      cloudInput);

            if (copyLandWaterReclass) {
                // Gaseous Correction
                Map<String, Product> gasInput = new HashMap<String, Product>(3);
                gasInput.put("l1b", sourceProduct);
                gasInput.put("rhotoa", rad2reflProduct);
                gasInput.put("cloud", cloudProduct);
                Map<String, Object> gasParameters = new HashMap<String, Object>(2);
                gasParameters.put("correctWater", true);
                gasParameters.put("exportTg", true);
                final Product gasProduct =
                        GPF.createProduct(OperatorSpi.getOperatorAlias(GaseousCorrectionOp.class), gasParameters,
                                          gasInput);

                // Land Water Reclassification
                Map<String, Product> landInput = new HashMap<String, Product>(2);
                landInput.put("l1b", sourceProduct);
                landInput.put("gascor", gasProduct);
                landProduct =
                        GPF.createProduct(OperatorSpi.getOperatorAlias(LandClassificationOp.class), GPF.NO_PARAMS,
                                          landInput);
            }
        }

        // Create output product
        String productType = sourceProduct.getProductType();
        String productName = sourceProduct.getName();
        int sceneWidth = sourceProduct.getSceneRasterWidth();
        int sceneHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(productName, productType, sceneWidth, sceneHeight);
        targetProduct.setDescription(sourceProduct.getDescription().replace("Radiance", "Reflectance"));
        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());

        //ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        for (TiePointGrid tpg : sourceProduct.getTiePointGrids()) {
            targetProduct.addTiePointGrid(tpg.cloneTiePointGrid());
            String tpgName = tpg.getName();
            targetProduct.getTiePointGrid(tpgName).setUnit(tpg.getUnit());
            targetProduct.getTiePointGrid(tpgName).setDescription(tpg.getDescription());
        }

        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);

        // Reflectance bands (they will be converted to int16)
        for (Band sourceBand : rad2reflProduct.getBands()) {
            String oldName = sourceBand.getName();
            String newName = oldName.replace("rho_toa", "reflectance");
            Band targetBand = targetProduct.addBand(newName, ProductData.TYPE_INT16);
            targetBand.setDescription(oldName.replace("rho_toa_", "TOA reflectance band "));
            targetBand.setUnit("dl");
            targetBand.setScalingFactor(1.0 / scalingFactor);
            targetBand.setValidPixelExpression(newName + ">=0");
            ProductUtils.copySpectralBandProperties(sourceBand, targetBand);
            renameMap.put(newName, oldName);
        }

        // Copy flags after reflectances
        ProductUtils.copyFlagBands(sourceProduct, targetProduct);

        // Copy cloudProbabilityProduct bands
        if (copyCloudProbability && cloudProbabilityProduct != null) {
            for (Band b : cloudProbabilityProduct.getBands()) {
                targetProduct.addBand(b);
            }
        }

        // Copy cloudProduct bands
        if (copyCloudTopPreassureAndMask) {
            for (Band b : cloudProduct.getBands()) {
                targetProduct.addBand(b);
            }
        }

        // Copy land water flags
        if (copyLandWaterReclass) {
            FlagCoding flagCoding = LandClassificationOp.createFlagCoding();
            targetProduct.getFlagCodingGroup().add(flagCoding);
            for (Band band : landProduct.getBands()) {
                if (band.getName().equals(LandClassificationOp.LAND_FLAGS)) {
                    band.setSampleCoding(flagCoding);
                }
                targetProduct.addBand(band);
            }
        }

        // Copy original bands to targetProduct
        for (Band sourceBand : sourceProduct.getBands()) {
            Band targetBand;
            if (sourceBand.getFlagCoding() == null) { // Copy only no flag coding bands
                // Copy band unless it is a radiance band, in which case it depends on copyToaRadiances flag
                boolean copyBand = (sourceBand.getName().startsWith("radiance")) ? copyToaRadiances : true;
                if (copyBand) {
                    targetBand = ProductUtils.copyBand(sourceBand.getName(), sourceProduct, targetProduct);
                    ProductUtils.copySpectralBandProperties(sourceBand, targetBand);
                    targetBand.setSourceImage(sourceBand.getSourceImage());
                }
            } else {
                targetBand = targetProduct.getBand(sourceBand.getName());
                targetBand.setSourceImage(sourceBand.getSourceImage()); // copy band data
            }
        }

        // Keep the "orthorectificability" of the source product
        targetProduct.setPointingFactory(sourceProduct.getPointingFactory());
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm)
            throws OperatorException {

        final Rectangle rect = targetTile.getRectangle();
        final String bandName = targetBand.getName();

        pm.beginTask("starting", rect.height);

        if (bandName.startsWith("reflectance")) {
            // Convert float band to int16
            final Band sourceBand = rad2reflProduct.getBand(renameMap.get(bandName));
            final Tile sourceTile = getSourceTile(sourceBand, rect);
            final float[] sourceData = sourceTile.getDataBufferFloat();
            final short[] targetData = targetTile.getDataBufferShort();

            for (int y = rect.y; y < rect.y + rect.height; y++) {
                for (int x = rect.x; x < rect.x + rect.width; x++) {
                    final int pos = targetTile.getDataBufferIndex(x, y);
                    targetData[pos] = (short) (scalingFactor * sourceData[pos]);
                }
                pm.worked(1);
            }
        }

        pm.done();
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(CreateMerisOp.class);
        }
    }
}
