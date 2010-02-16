package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.synergy.util.AerosolHelpers;

import java.awt.Rectangle;

/**
 * This operator merges the land and ocean aerosol products which were
 * computed by different algorithms.
 *
 * @author Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 18:54:34 +0100 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.LandOceanMerge",
                  version = "1.0-SNAPSHOT",
                  authors = "Olaf Danne",
                  copyright = "(c) 2009 by Brockmann Consult",
                  description = "Retrieve Aerosol over ocean and land.")
public class LandOceanMergeOp extends Operator {

    private static final String LAND_EXPRESSION = "l1_flags" + "_" +
            RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_MERIS + ".LAND_OCEAN";
    private Band isLandBand;

    @Override
    public void initialize() throws OperatorException {
        createTargetProduct();
    }

    @SourceProduct(alias = "source",
                   label = "Name (Collocated MERIS AATSR product)",
                   description = "Select a collocated MERIS AATSR product.")
    private Product synergyProduct;

    @SourceProduct(alias = "ocean",
                   label = "Name (Synergy aerosol ocean product)",
                   description = "Select a Synergy aerosol ocean product.")
    private Product oceanProduct;

    @SourceProduct(alias = "land",
                   label = "Name (Synergy aerosol land product)",
                   description = "Select a Synergy aerosol land product.")
    private Product landProduct;

    @Parameter(defaultValue = "11", label = "Pixels to average (n x n, with n odd number) for AOD retrieval", interval = "[1, 100]")
    private int aveBlock;


    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private void createTargetProduct() {

        targetProduct = new Product(oceanProduct.getName(), oceanProduct.getProductType(),
                                    oceanProduct.getSceneRasterWidth(), oceanProduct.getSceneRasterHeight());

        ProductUtils.copyGeoCoding(synergyProduct, targetProduct);
        ProductUtils.copyMetadata(synergyProduct, targetProduct);
        AerosolHelpers.copyDownscaledTiePointGrids(synergyProduct, targetProduct, aveBlock);
        AerosolHelpers.copyDownscaledFlagBands(synergyProduct, targetProduct, aveBlock);

        BandArithmeticOp bandArithmeticOp =
                BandArithmeticOp.createBooleanExpressionBand(LAND_EXPRESSION, oceanProduct);
        isLandBand = bandArithmeticOp.getTargetProduct().getBandAt(0);

        createTargetProductBands();

        setTargetProduct(targetProduct);

    }

    private void createTargetProductBands() {
        for (Band band:oceanProduct.getBands()) {
            if (!band.isFlagBand()) {
                Band targetBand = new Band(band.getName(), band.getDataType(),
                                           oceanProduct.getSceneRasterWidth(), oceanProduct.getSceneRasterHeight());
                targetBand.setDescription(band.getDescription());
                targetBand.setNoDataValue(band.getNoDataValue());
                targetBand.setNoDataValueUsed(true);
                targetProduct.addBand(targetBand);
            }
        }
        // also add aerosol model number (defined for land product)
        Band targetBand = new Band(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME, ProductData.TYPE_INT32,
                                           landProduct.getSceneRasterWidth(), landProduct.getSceneRasterHeight());
        targetBand.setDescription("aerosol model number Band");
        targetBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
        targetBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE_USED);
        targetBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
        targetProduct.addBand(targetBand);
        
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        if (targetBand.isFlagBand()) {
            // no computations
            return;
        }

        Rectangle rectangle = targetTile.getRectangle();

        pm.beginTask("Processing frame...", rectangle.height);

        try {
            Tile aotOceanTile = getSourceTile(oceanProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME), rectangle, pm);
            Tile aotErrorOceanTile = getSourceTile(oceanProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME), rectangle, pm);
            Tile aotLandTile = getSourceTile(landProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME), rectangle, pm);
            Tile aotErrorLandTile = getSourceTile(landProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME), rectangle, pm);
            Tile aerosolModelLandTile = getSourceTile(landProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME), rectangle, pm);

            Tile isLand = getSourceTile(isLandBand, rectangle, pm);

            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {

                    float aot;
                    float aotUncertainty;
                    int landAerosolModelNumber;
                    if (isLand.getSampleBoolean(x, y)) {
                        aot = aotLandTile.getSampleFloat(x,y);
                        aotUncertainty = aotErrorLandTile.getSampleFloat(x,y);
                        landAerosolModelNumber = aerosolModelLandTile.getSampleInt(x,y);
                    } else {
                        aot = aotOceanTile.getSampleFloat(x,y);
                        aotUncertainty = aotErrorOceanTile.getSampleFloat(x,y);
                        landAerosolModelNumber = RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE;
                    }

                    if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME)) {
                        targetTile.setSample(x, y, aot);
                    }
                    if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME)) {
                        targetTile.setSample(x, y, aotUncertainty);
                    }
                    if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME)) {
                        targetTile.setSample(x, y, landAerosolModelNumber);
                    }
                    pm.worked(1);
                }
            }
        } catch (Exception e) {
            throw new OperatorException("Failed to merge land/ocean aerosol products:\n" + e.getMessage(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(LandOceanMergeOp.class);
        }
    }
}
