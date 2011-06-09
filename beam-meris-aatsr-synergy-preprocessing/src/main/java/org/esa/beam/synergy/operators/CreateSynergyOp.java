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

import org.esa.beam.collocation.CollocateOp;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.gpf.operators.standard.SubsetOp;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.synergy.util.SynergyUtils;
import org.esa.beam.util.ProductUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Operator for computing the synergy product.
 *
 * @author Jordi Munyoz-Mari and Luis Gomez-Chova
 * @version $Revision: 7988 $ $Date: 2010-01-14 12:51:28 +0100 (Do, 14 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.CreateSynergy",
                  version = "1.2",
                  authors = "Jordi Munyoz-Mari and Luis Gomez-Chova",
                  copyright = "(c) 2008 by IPL",
                  description = "Creates a MERIS/AATSR colocated ('Synergy') product.")

public class CreateSynergyOp extends Operator {

    @SourceProduct(alias = "MERIS",
                   description = "MERIS source product.")
    Product merisSourceProduct;

    @SourceProduct(alias = "AATSR",
                   description = "AATSR source product.")
    Product aatsrSourceProduct;

    @TargetProduct(description = "SYNERGY target product.")
    Product targetProduct;

    //    @Parameter(defaultValue = "true",
//               description = "Copy the original MERIS radiance bands",
//               label = "Copy MERIS TOA radiance bands")
//    boolean copyToaRadiances;
    boolean copyToaRadiances = true;

    // options 2-5 removed for final version, 2010/03/12
//    @Parameter(defaultValue = "true",
//               description = "Adds a band with MERIS cloud probability",
//               label = "Copy MERIS Cloud Probability (BEAM-FUB)")
//    boolean copyCloudProbability;
    boolean copyCloudProbability = false;

    //    @Parameter(defaultValue = "false",
//               description = "Add two bands containing MERIS top preassure and cloud mask",
//               label = "Copy MERIS Cloud Top Pressure and Mask (GLOBCOVER)")
//    boolean copyCloudTopPreassureAndMask;
    boolean copyCloudTopPreassureAndMask = false;

    //    @Parameter(defaultValue = "false",
//               description = "Adds a band with Land Water Reclassification",
//               label = "Copy Land Water Reclassification")
//    boolean copyLandWaterReclass;
    boolean copyLandWaterReclass = false;

    //    @Parameter(defaultValue = "false",
//               description = "Create DEM elevation and orthorectify",
//               label = "Create DEM elevation and orthorectify")
//    boolean createDEMelevation;
    boolean createDEMelevation = false;

    @Parameter(defaultValue = "true",
               description = "Crop MERIS/AATSR non-overlapping areas",
               label = "Crop MERIS/AATSR non-overlapping areas")
    boolean subsetOvAreas;
//    boolean subsetOvAreas = true;

    @Override
    public void initialize() throws OperatorException {

        // MERIS product
        Map<String, Object> merisParams = new HashMap<String, Object>(3);
        merisParams.put("copyToaRadiances", copyToaRadiances);
        merisParams.put("copyCloudProbability", copyCloudProbability);
        merisParams.put("copyCloudTopPreassureAndMask", copyCloudTopPreassureAndMask);
        merisParams.put("copyLandWaterReclass", copyLandWaterReclass);
        SynergyUtils.validateMerisProduct(merisSourceProduct);
        Product merisProduct =
                GPF.createProduct(OperatorSpi.getOperatorAlias(CreateMerisOp.class), merisParams, merisSourceProduct);


        // AATSR product
        SynergyUtils.validateAatsrProduct(aatsrSourceProduct);
        Product aatsrProduct =
                GPF.createProduct(OperatorSpi.getOperatorAlias(CreateAatsrOp.class), GPF.NO_PARAMS, aatsrSourceProduct);


        // TODO: MERIS_FRG and MERIS_FSG products are already orthorectified. Detect this kind
        // of products and save the work of having to create the elevation and orthorectified bands.

        // Create altitude band and add it to MERIS product
        if (createDEMelevation) {
            try {
                final Product demProduct =
                        GPF.createProduct(OperatorSpi.getOperatorAlias(CreateElevationBandOp.class),
                                          GPF.NO_PARAMS, merisProduct);
                // Add created bands
                for (Band b : demProduct.getBands()) {
                    merisProduct.addBand(b);
                }
            } catch (OperatorException e) {
                SynergyUtils.info("Could not create DEM elevation bands");
            }
        }

        // Collocation
        Map<String, Product> collocateInput = new HashMap<String, Product>(2);
        collocateInput.put("masterProduct", merisProduct);
        collocateInput.put("slaveProduct", aatsrProduct);
        Map<String, Object> collocateParams = new HashMap<String, Object>(2);
        collocateParams.put("masterComponentPattern", "${ORIGINAL_NAME}_MERIS");
        collocateParams.put("slaveComponentPattern", "${ORIGINAL_NAME}_AATSR");
        Product collocateProduct =
                GPF.createProduct(OperatorSpi.getOperatorAlias(CollocateOp.class), collocateParams, collocateInput);

        // Fix collocation output (tie point grids lost their units and descriptions)
        for (TiePointGrid tpg : collocateProduct.getTiePointGrids()) {
            tpg.setUnit(merisSourceProduct.getTiePointGrid(tpg.getName()).getUnit());
            tpg.setDescription(merisSourceProduct.getTiePointGrid(tpg.getName()).getDescription());
        }

        if (subsetOvAreas) {
            // The SubsetOp fails if used by GPF (see bellow)
            java.awt.Rectangle rect = findCommonArea(collocateProduct);
            SubsetOp op = new SubsetOp();
            op.setSourceProduct(collocateProduct);
            op.setRegion(rect);
            op.setCopyMetadata(true);
            targetProduct = op.getTargetProduct();
            /* This code throws a NullPointerException, we don't know why
            // Subset
            Map<String, Object> subsetParams = new HashMap<String, Object>(2);
            java.awt.Rectangle rect = createSubset(collocateProduct);
            subsetParams.put("region", rect);
            subsetParams.put("copyMetadata", true);
            targetProduct =
                GPF.createProduct(OperatorSpi.getOperatorAlias(SubsetOp.class), subsetParams, collocateProduct);
            */
        } else {
            targetProduct = collocateProduct;
        }

        // Save information about reduced or full resolution
        if (SynergyUtils.isRR(merisSourceProduct)) {
            targetProduct.setProductType(SynergyConstants.SYNERGY_RR_PRODUCT_TYPE_NAME);
        } else if (SynergyUtils.isFR(merisSourceProduct)) {
            targetProduct.setProductType(SynergyConstants.SYNERGY_FR_PRODUCT_TYPE_NAME);
        } else if (SynergyUtils.isFS(merisSourceProduct)) {
            targetProduct.setProductType(SynergyConstants.SYNERGY_FS_PRODUCT_TYPE_NAME);
        }
        targetProduct.setDescription("SYNERGY product");
        ProductUtils.copyMetadata(merisSourceProduct, targetProduct);

        // Copy the pointing factory ("orthorectificability") of the source product
        //targetProduct.setPointingFactory(merisSourceProduct.getPointingFactory());
    }

    /**
     * Finds the actual size of a band (discarding invalid pixels).
     *
     * @param band the Band
     *
     * @return a Rectangle with the limits found (x/y coordinates, not width/height)
     */
    @SuppressWarnings("deprecation")
    private java.awt.Rectangle findLimits(final Band band) {
        final int width = band.getRasterWidth();
        final int height = band.getRasterHeight();
        // We user a rectangle, but width and height will mean the end x/y
        // coordinates, not the actual width/height
        final java.awt.Rectangle r = new java.awt.Rectangle(0, 0, width, height);

        if (!band.isValidMaskUsed()) {
            SynergyUtils.info("No data mask available");
            return r;
        }

        // Caution: we use r.width and r.height as the last x/y coordinates

        // Find limits
        int x;
        int y;

        //SynergyPreprocessingUtils.info("  Starting finding a valid pixel for " +r+ " ...");
        // Find first valid pixel at upper left corner
        findPixel:
        for (y = 0; y < height; y++) {
            for (x = 0; x < width; x++) {
                if (band.isPixelValid(x, y)) {
                    r.x = x;
                    r.y = y;
                    break findPixel;
                }
            }
        }
        //SynergyPreprocessingUtils.info("  Found " +r.x+ " " +r.y);

        // Find width
        for (x = (width - 1); x > r.x; x--) {
            if (band.isPixelValid(x, r.y + 5)) { // +5 to be sure
                r.width = x;
                break;
            }
        }
        // Find height
        for (y = (height - 1); y > r.y; y--) {
            if (band.isPixelValid(r.x + 5, y)) { // +5 to be sure
                r.height = y;
                break;
            }
        }

        return r;
    }

    /**
     * Creates a spatial subset of product using the common area of MERIS and AATSR bands.
     *
     * @param product the Product
     *
     * @return a new Product with the spatial subset
     */
    private java.awt.Rectangle findCommonArea(Product product) {
        // Find one reflect MERIS band and one reflect AATSR band
        Band merisBand = null;
        Band aatsrBand = null;
        for (Band band : product.getBands()) {
            if (merisBand != null && aatsrBand != null) {
                break;
            }
            if (merisBand == null && band.getName().startsWith("reflectance")) {
                merisBand = band;
                continue;
            }
            if (aatsrBand == null && band.getName().startsWith(
                    EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0550_BAND_NAME)) {
                aatsrBand = band;
            }
        }
        if (merisBand == null || aatsrBand == null) {
            throw new OperatorException
                    ("Error finding MERIS or AATSR bands: meris: " + merisBand + " aatsr: " + aatsrBand);
        }
        // Find MERIS and AATSR limits
        java.awt.Rectangle r_meris = findLimits(merisBand);
        java.awt.Rectangle r_aatsr = findLimits(aatsrBand);
        SynergyUtils.info("    MERIS size " + r_meris.x + " " + r_meris.y + " " + r_meris.width + " " + r_meris.height);
        SynergyUtils.info("    AATSR size " + r_aatsr.x + " " + r_aatsr.y + " " + r_aatsr.width + " " + r_aatsr.height);
        // Set minimum limit to r_meris
        if (r_aatsr.x > r_meris.x) {
            r_meris.x = r_aatsr.x;
        }
        if (r_aatsr.y > r_meris.y) {
            r_meris.y = r_aatsr.y;
        }
        if (r_aatsr.width < r_meris.width) {
            r_meris.width = r_aatsr.width;
        }
        if (r_aatsr.height < r_meris.height) {
            r_meris.height = r_aatsr.height;
        }
        // Convert limits to widths
        r_meris.width = r_meris.width - r_meris.x + 1;
        r_meris.height = r_meris.height - r_meris.y + 1;
        SynergyUtils.info(
                "    Synergy size " + r_meris.x + " " + r_meris.y + " " + r_meris.width + " " + r_meris.height);

        return r_meris;
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(CreateSynergyOp.class);
        }
    }
}

