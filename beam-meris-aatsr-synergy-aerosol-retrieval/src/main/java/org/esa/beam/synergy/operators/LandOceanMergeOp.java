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
import org.esa.beam.gpf.operators.standard.BandMathsOp;
import org.esa.beam.synergy.util.AerosolHelpers;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;

/**
 * This operator merges the land and ocean aerosol products which were
 * computed by different algorithms.
 *
 * @author Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 17:54:34 +0000 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.LandOceanMerge",
                  version = "1.1",
                  authors = "Olaf Danne",
                  copyright = "(c) 2009 by Brockmann Consult",
                  description = "Retrieve Aerosol over ocean and land.", internal=true)
public class LandOceanMergeOp extends Operator {

    private static final String LAND_EXPRESSION = "l1_flags" + "_" +
            SynergyConstants.INPUT_BANDS_SUFFIX_MERIS + ".LAND_OCEAN";
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

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private void createTargetProduct() {

        targetProduct = new Product(oceanProduct.getName(), oceanProduct.getProductType(),
                                    oceanProduct.getSceneRasterWidth(), oceanProduct.getSceneRasterHeight());

        ProductUtils.copyGeoCoding(synergyProduct, targetProduct);
        ProductUtils.copyMetadata(synergyProduct, targetProduct);

        BandMathsOp bandArithmeticOp =
                BandMathsOp.createBooleanExpressionBand(LAND_EXPRESSION, oceanProduct);
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
        Band targetBand = new Band(SynergyConstants.OUTPUT_AOTMODEL_BAND_NAME, ProductData.TYPE_INT32,
                                           landProduct.getSceneRasterWidth(), landProduct.getSceneRasterHeight());
        targetBand.setDescription("aerosol model number Band");
        targetBand.setNoDataValue(SynergyConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
        targetBand.setNoDataValueUsed(SynergyConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE_USED);
        targetBand.setNoDataValue(SynergyConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
        targetProduct.addBand(targetBand);

        AerosolHelpers.addAerosolFlagBand(targetProduct, oceanProduct.getSceneRasterWidth(), oceanProduct.getSceneRasterHeight());
        
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        if (targetBand.isFlagBand()
                && !targetBand.getName().equals(SynergyConstants.aerosolFlagCodingName)) {
            // no computations
            System.out.println(targetBand.getName());
            return;
        }

        Rectangle rectangle = targetTile.getRectangle();

        pm.beginTask("Processing frame...", 1);

        try {
            Tile oceanTile;
            Tile landTile;
            Tile isLand = getSourceTile(isLandBand, rectangle, pm);
            if (targetBand.getName().equals(SynergyConstants.OUTPUT_AOT_BAND_NAME)) {
                oceanTile = getSourceTile(oceanProduct.getBand(SynergyConstants.OUTPUT_AOT_BAND_NAME), rectangle, pm);
                landTile = getSourceTile(landProduct.getBand(SynergyConstants.OUTPUT_AOT_BAND_NAME), rectangle, pm);
                mergeTileFloat(targetTile, isLand, oceanTile, landTile);
            }
            else if (targetBand.getName().equals(SynergyConstants.OUTPUT_AOTERR_BAND_NAME)) {
                oceanTile = getSourceTile(oceanProduct.getBand(SynergyConstants.OUTPUT_AOTERR_BAND_NAME), rectangle, pm);
                landTile = getSourceTile(landProduct.getBand(SynergyConstants.OUTPUT_AOTERR_BAND_NAME), rectangle, pm);
                mergeTileFloat(targetTile, isLand, oceanTile, landTile);
            }
            else if (targetBand.getName().equals(SynergyConstants.OUTPUT_AOTMODEL_BAND_NAME)) {
                landTile = getSourceTile(landProduct.getBand(SynergyConstants.OUTPUT_AOTMODEL_BAND_NAME), rectangle, pm);
                boolean isOceanConst = true;
                mergeTileInt(targetTile, isLand, landTile, isOceanConst, SynergyConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
            }
            else if (targetBand.getName().equals(SynergyConstants.aerosolFlagCodingName)) {
                landTile = getSourceTile(landProduct.getBand(SynergyConstants.aerosolFlagCodingName), rectangle, pm);
                mergeFlagTile(targetTile, isLand, landTile);
            }
            else {
                oceanTile = getSourceTile(oceanProduct.getBand(targetBand.getName()), rectangle, pm);
                boolean isOceanConst = false;
                mergeTileFloat(targetTile, isLand, oceanTile, isOceanConst, (float) targetBand.getNoDataValue());
            }
            pm.worked(1);
        } catch (Exception e) {
            throw new OperatorException("Failed to merge land/ocean aerosol products:\n" + e.getMessage(), e);
        } finally {
            pm.done();
        }
    }

    private void mergeTileFloat(Tile targetTile, Tile isLand, Tile oceanTile, Tile landTile) {
        Rectangle rectangle = isLand.getRectangle();
        for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
            for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                if (testCoast(isLand, x, y)){
                    targetTile.setSample(x, y, (float)targetTile.getRasterDataNode().getNoDataValue());
                }
                else if (isLand.getSampleBoolean(x, y)) {
                    targetTile.setSample(x, y, landTile.getSampleFloat(x, y));
                }
                else {
                    targetTile.setSample(x, y, oceanTile.getSampleFloat(x, y));
                }
            }
        }
    }

    private void mergeTileFloat(Tile targetTile, Tile isLand, Tile srcTile, boolean isOceanConst, float srcConst) {
        Rectangle rectangle = isLand.getRectangle();
        float oceanValue;
        float landValue;
        for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
            for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                oceanValue = (isOceanConst) ? srcConst : srcTile.getSampleFloat(x,y);
                landValue = (!isOceanConst) ? srcConst : srcTile.getSampleFloat(x,y);
                if (isLand.getSampleBoolean(x, y)) {
                    targetTile.setSample(x, y, landValue);
                }
                else {
                    targetTile.setSample(x, y, oceanValue);
                }
            }
        }
    }

    private void mergeTileInt(Tile targetTile, Tile isLand, Tile srcTile, boolean isOceanConst, int srcConst) {
        Rectangle rectangle = isLand.getRectangle();
        int oceanValue;
        int landValue;
        for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
            for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                oceanValue = (isOceanConst) ? srcConst : srcTile.getSampleInt(x,y);
                landValue = (!isOceanConst) ? srcConst : srcTile.getSampleInt(x,y);
                if (isLand.getSampleBoolean(x, y)) {
                    targetTile.setSample(x, y, landValue);
                }
                else {
                    targetTile.setSample(x, y, oceanValue);
                }
            }
        }
    }

    private void mergeFlagTile(Tile targetTile, Tile isLand, Tile landTile) {
        final Rectangle rectangle = isLand.getRectangle();
        int oceanValue = SynergyConstants.oceanMask;
        oceanValue |= SynergyConstants.successMask;
        int coastValue = SynergyConstants.coastMask;
        int landValue;
        for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
            for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                landValue = landTile.getSampleInt(x,y);
                if (testCoast(isLand, x, y)) {
                    if((landValue & SynergyConstants.cloudyMask) == SynergyConstants.cloudyMask) {
                        coastValue |= SynergyConstants.cloudyMask;
                    }
                    targetTile.setSample(x, y, coastValue);
                }
                else if (isLand.getSampleBoolean(x, y)) {
                    targetTile.setSample(x, y, landTile.getSampleInt(x, y));
                }
                else {
                    targetTile.setSample(x, y, oceanValue);
                }
            }
        }
    }

    private boolean testCoast(Tile isLand, int x, int y) {
        boolean isCoast = false;
        final boolean isLandPixel = isLand.getSampleBoolean(x, y);
        for (int dy = y - 1; dy <= y + 1; dy++) {
            for (int dx = x - 1; dx <= x + 1; dx++) {
                if (dx >= isLand.getMinX() && dx <= isLand.getMaxX()
                        && dy >= isLand.getMinY() && dy <= isLand.getMaxY()) {
                    isCoast = isCoast || (isLandPixel != isLand.getSampleBoolean(dx, dy));
                }
            }
        }
        return isCoast;
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
