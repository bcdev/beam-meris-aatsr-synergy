/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.gpf.operators.standard.BandMathsOp;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;
import java.util.Map;


/**
 * @author akheckel
 */
@OperatorMetadata(alias = "synergy.BoxAve",
                  version = "1.2.2",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "boxcar Averaging excluding NoDataPixel", internal = true)
public class BoxAveOp extends Operator {

    @SourceProduct(alias = "source",
                   label = "Name (Synergy aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private String aotBandName = SynergyConstants.OUTPUT_AOT_BAND_NAME;
    private String errBandName = SynergyConstants.OUTPUT_AOTERR_BAND_NAME;
    private String modelBandName = SynergyConstants.OUTPUT_AOTMODEL_BAND_NAME;
    private String aotExtrpName = SynergyConstants.OUTPUT_AOT_BAND_NAME + "_filled";
    private String errExtrpName = SynergyConstants.OUTPUT_AOTERR_BAND_NAME + "_filled";
    private String modelExtrpName = SynergyConstants.OUTPUT_AOTMODEL_BAND_NAME + "_filled";

    @Parameter(defaultValue = "3", label = "BoxSize in Pixel (n x n)", interval = "[1, 100]")
    private int aveBlock;
    private int aveBHalf;

    private static String productName = "SYNERGY INTERPOLATED";
    private static String productType = "SYNERGY INTERPOLATED";

    private int rasterWidth;
    private int rasterHeight;

    private final String aerosolFlagName = SynergyConstants.aerosolFlagCodingName;
    //private final String validPixelExpression = "true";
    private final String validPixelExpression = "(" + aerosolFlagName + "." + SynergyConstants.flagSuccessName
                                                + " || " + aerosolFlagName + "." + SynergyConstants.flagFilledName + ")";

    private Band origAotBand;
    private Band aotSrcBand;
    private Band errSrcBand;
    private Band modelSrcBand;
    private Band flagSrcBand;

    private Product validPixelProduct;


    @Override
    public void initialize() throws OperatorException {

        aveBHalf = aveBlock / 2;
        rasterWidth = sourceProduct.getSceneRasterWidth();
        rasterHeight = sourceProduct.getSceneRasterHeight();

        origAotBand = sourceProduct.getBand(aotBandName);
        aotSrcBand = (sourceProduct.containsBand(aotExtrpName)) ? sourceProduct.getBand(
                aotExtrpName) : sourceProduct.getBand(aotBandName);
        errSrcBand = (sourceProduct.containsBand(errExtrpName)) ? sourceProduct.getBand(
                errExtrpName) : sourceProduct.getBand(errBandName);
        modelSrcBand = (sourceProduct.containsBand(modelExtrpName)) ? sourceProduct.getBand(
                modelExtrpName) : sourceProduct.getBand(modelBandName);
        flagSrcBand = sourceProduct.getBand(SynergyConstants.aerosolFlagCodingName);

        BandMathsOp validPixelOp = BandMathsOp.createBooleanExpressionBand(validPixelExpression, sourceProduct);
        validPixelProduct = validPixelOp.getTargetProduct();

/*
        String validExpression = validPixelExpression + " ? " + aotSrcBand.getName() + " : " + String.valueOf(aotSrcBand.getNoDataValue());
        validAotSrcBand = new VirtualBand("validAotSrcBand", aotSrcBand.getDataType(), rasterWidth, rasterHeight, validExpression);
        sourceProduct.addBand(validAotSrcBand);

        validExpression = validPixelExpression + " ? " + errSrcBand.getName() + " : " + String.valueOf(errSrcBand.getNoDataValue());
        validErrSrcBand = new VirtualBand("validErrSrcBand", errSrcBand.getDataType(), rasterWidth, rasterHeight, validExpression);
        sourceProduct.addBand(validErrSrcBand);
        
        validExpression = validPixelExpression + " ? " + modelSrcBand.getName() + " : " + String.valueOf(modelSrcBand.getNoDataValue());
        validModelSrcBand = new VirtualBand("validModelSrcBand", modelSrcBand.getDataType(), rasterWidth, rasterHeight, validExpression);
        sourceProduct.addBand(validModelSrcBand);
*/
        createTargetProduct();

    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle tarRec, ProgressMonitor pm) throws
                                                                                                    OperatorException {

        pm.beginTask("aot extrapolation", tarRec.width * tarRec.height + 5);

        int srcX = tarRec.x - aveBHalf;
        int srcY = tarRec.y - aveBHalf;
        int srcWidth = tarRec.width + 2 * aveBHalf;
        int srcHeight = tarRec.height + 2 * aveBHalf;
        Rectangle srcRec = new Rectangle(srcX, srcY, srcWidth, srcHeight);

        Tile origAotTile = getSourceTile(origAotBand, srcRec);
        Tile origTarTile = targetTiles.get(targetProduct.getBand(aotBandName));
        // didn't work! why?
        //origTarTile.setRawSamples(origAotTile.getRawSamples());

        Tile aotSrcTile = getSourceTile(aotSrcBand, srcRec);
        Tile aotTarTile = targetTiles.get(targetProduct.getBand(aotExtrpName));

        Tile errSrcTile = getSourceTile(errSrcBand, srcRec);
        Tile errTarTile = targetTiles.get(targetProduct.getBand(errExtrpName));

        Tile modelSrcTile = getSourceTile(modelSrcBand, srcRec);
        Tile modelTarTile = targetTiles.get(targetProduct.getBand(modelExtrpName));

        Tile flagSrcTile = getSourceTile(flagSrcBand, srcRec);
        Tile flagTarTile = targetTiles.get(targetProduct.getBand(aerosolFlagName));

        Tile validPixelTile = getSourceTile(validPixelProduct.getBandAt(0), srcRec);

        int tarX = tarRec.x;
        int tarY = tarRec.y;
        int tarWidth = tarRec.width;
        int tarHeight = tarRec.height;

        for (int iTarY = tarY; iTarY < tarHeight; iTarY++) {
            for (int iTarX = tarX; iTarX < tarWidth; iTarX++) {
                checkForCancellation();
                float origPixel = origAotTile.getSampleFloat(iTarX, iTarY);
                int flagPixel = flagSrcTile.getSampleInt(iTarX, iTarY);
                origTarTile.setSample(iTarX, iTarY, origPixel);
                //if (origPixel == noDataValue) {
                if (!validPixelTile.getSampleBoolean(iTarX, iTarY)) {
                    float pixel = getAvePixel(aotSrcTile, iTarX, iTarY, validPixelTile);
                    aotTarTile.setSample(iTarX, iTarY, pixel);
                    if (pixel != aotSrcTile.getRasterDataNode().getNoDataValue()) {
                        flagPixel |= SynergyConstants.filledMask;
                    }

                    pixel = getAvePixel(errSrcTile, iTarX, iTarY, validPixelTile);
                    errTarTile.setSample(iTarX, iTarY, pixel);

                    pixel = getNearestPixel(modelSrcTile, iTarX, iTarY, validPixelTile);
                    modelTarTile.setSample(iTarX, iTarY, pixel);
                } else {
                    aotTarTile.setSample(iTarX, iTarY, aotSrcTile.getSampleFloat(iTarX, iTarY));
                    errTarTile.setSample(iTarX, iTarY, errSrcTile.getSampleFloat(iTarX, iTarY));
                    modelTarTile.setSample(iTarX, iTarY, modelSrcTile.getSampleFloat(iTarX, iTarY));
                }
                flagTarTile.setSample(iTarX, iTarY, flagPixel);
                pm.worked(1);
            }
        }
        pm.done();
    }

    private void createTargetProduct() {

        targetProduct = new Product(productName, productType, rasterWidth, rasterHeight);

        FlagCoding aerosolFlagCoding = sourceProduct.getFlagCodingGroup().get(aerosolFlagName);
        ProductUtils.copyFlagCoding(aerosolFlagCoding, targetProduct);
        Band aerosolFlagBand = ProductUtils.copyBand(aerosolFlagName, sourceProduct, targetProduct);
        aerosolFlagBand.setSampleCoding(aerosolFlagCoding);

        createTargetProductBands();

        targetProduct.setPreferredTileSize(100, 100);

        setTargetProduct(targetProduct);

    }

    private void createTargetProductBands() {

        ProductUtils.copyBand(aotBandName, sourceProduct, targetProduct);

        if (sourceProduct.containsBand(aotExtrpName)) {
            ProductUtils.copyBand(aotExtrpName, sourceProduct, targetProduct);
            ProductUtils.copyBand(errExtrpName, sourceProduct, targetProduct);
            ProductUtils.copyBand(modelExtrpName, sourceProduct, targetProduct);
        } else {
            Band srcBand = sourceProduct.getBand(aotBandName);
            Band targetBand = new Band(aotExtrpName, srcBand.getDataType(), rasterWidth, rasterHeight);
            targetBand.setDescription(srcBand.getDescription());
            targetBand.setNoDataValue(srcBand.getNoDataValue());
            targetBand.setNoDataValueUsed(true);
            targetProduct.addBand(targetBand);

            srcBand = sourceProduct.getBand(errBandName);
            targetBand = new Band(errExtrpName, srcBand.getDataType(), rasterWidth, rasterHeight);
            targetBand.setDescription(srcBand.getDescription());
            targetBand.setNoDataValue(srcBand.getNoDataValue());
            targetBand.setNoDataValueUsed(true);
            targetProduct.addBand(targetBand);

            srcBand = sourceProduct.getBand(modelBandName);
            targetBand = new Band(modelExtrpName, srcBand.getDataType(), rasterWidth, rasterHeight);
            targetBand.setDescription(srcBand.getDescription());
            targetBand.setNoDataValue(srcBand.getNoDataValue());
            targetBand.setNoDataValueUsed(true);
            targetProduct.addBand(targetBand);
        }

    }

    private float getAvePixel(Tile inputTile, int iTarX, int iTarY, Tile validPixelTile) {

        double ave = 0;
        double noDataValue = inputTile.getRasterDataNode().getNoDataValue();
        int n = 0;
        for (int iy = iTarY - aveBHalf; iy <= iTarY + aveBHalf; iy++) {
            for (int ix = iTarX - aveBHalf; ix <= iTarX + aveBHalf; ix++) {
                if (iy >= 0 && iy < rasterHeight && ix >= 0 && ix < rasterWidth) {
                    double val = inputTile.getSampleDouble(ix, iy);
                    if (validPixelTile.getSampleBoolean(ix, iy)
                        && Double.compare(val, noDataValue) != 0) {
                        n++;
                        ave += val;
                    }
                }
            }
        }
        if (n >= 2) {
            ave /= n;
        } else {
            ave = noDataValue;
        }

        return (float) ave;
    }

    private float getNearestPixel(Tile inputTile, int iTarX, int iTarY, Tile validPixelTile) {

        double noDataValue = inputTile.getRasterDataNode().getNoDataValue();
        double result = noDataValue;
        double minDist = 99999;
        for (int iy = iTarY - aveBHalf; iy <= iTarY + aveBHalf; iy++) {
            for (int ix = iTarX - aveBHalf; ix <= iTarX + aveBHalf; ix++) {
                if (validPixelTile.getSampleBoolean(ix, iy)
                    && iy >= 0 && iy < rasterHeight
                    && ix >= 0 && ix < rasterWidth) {
                    double val = inputTile.getSampleDouble(ix, iy);
                    double dist = (ix - iTarX) * (ix - iTarX) + (iy - iTarY) * (iy - iTarY);
                    if (Double.compare(val, noDataValue) != 0 && minDist > dist) {
                        result = val;
                        minDist = dist;
                    }
                }
            }
        }

        return (float) result;
    }


    public static class Spi extends OperatorSpi {

        public Spi() {
            super(BoxAveOp.class);
        }
    }
}
