/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;
import java.util.Arrays;

/**
 *
 * @author akheckel
 */
@OperatorMetadata(alias = "synergy.Median",
                  version = "1.1",
                  authors = "Andreas Heckel",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "")
public class MedianOp extends Operator {

    @SourceProduct(alias = "source",
                   label = "Name (Synergy aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private static String productName = "SYNERGY MEDIAN";
    private static String productType = "SYNERGY MEDIAN";

    private String srcBandName = SynergyConstants.OUTPUT_AOT_BAND_NAME + "_filled";
    private String tarBandName = SynergyConstants.OUTPUT_AOT_BAND_NAME + "_filter";
    private int medBox = 3;
    private int medBoxHalf;

    private int rasterWidth;
    private int rasterHeight;

    @Override
    public void initialize() throws OperatorException {

        medBoxHalf = medBox / 2;
        rasterWidth = sourceProduct.getSceneRasterWidth();
        rasterHeight = sourceProduct.getSceneRasterHeight();

        createTargetProduct();
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        Rectangle tarRec = targetTile.getRectangle();
        pm.beginTask("filter aot", tarRec.width*tarRec.height + 1);

        if (targetBand.getName().equals(tarBandName)) {
            int srcX = tarRec.x - medBoxHalf;
            int srcY = tarRec.y - medBoxHalf;
            int srcWidth = tarRec.width + 2 * medBoxHalf;
            int srcHeight = tarRec.height + 2 * medBoxHalf;
            Rectangle srcRec = new Rectangle(srcX, srcY, srcWidth, srcHeight);

            Band srcBand = sourceProduct.getBand(srcBandName);
            Tile srcTile = getSourceTile(srcBand, srcRec, SubProgressMonitor.create(pm, 1));
            double noDataValue = srcTile.getRasterDataNode().getNoDataValue();

            int tarX = tarRec.x;
            int tarY = tarRec.y;
            int tarWidth = tarRec.width;
            int tarHeight = tarRec.height;
            for (int iTarY = tarY; iTarY < tarHeight; iTarY++) {
                for (int iTarX = tarX; iTarX < tarWidth; iTarX++) {
                    checkForCancelation(pm);
                    float srcPixel = srcTile.getSampleFloat(iTarX, iTarY);
                    if (srcPixel != noDataValue) {
                        float medianPixel = getMedianPixel(srcTile, iTarX, iTarY);
                        //float medianPixel = getBoxAvePixel(srcTile, iTarX, iTarY);
                        targetTile.setSample(iTarX, iTarY, medianPixel);
                    } else {
                        targetTile.setSample(iTarX, iTarY, noDataValue);
                    }
                    pm.worked(1);
                }
            }
        }
        else {
            Band srcBand = sourceProduct.getBand(targetBand.getName());
            Tile srcTile = getSourceTile(srcBand, tarRec, SubProgressMonitor.create(pm, 1));
            targetTile.setRawSamples(srcTile.getRawSamples());
            pm.worked(tarRec.width*tarRec.height);
        }
        pm.done();
    }

    private void createTargetProduct() {

        targetProduct = new Product(productName, productType, rasterWidth, rasterHeight);

        ProductUtils.copyFlagCodings(sourceProduct, targetProduct);
        ProductUtils.copyBitmaskDefs(sourceProduct, targetProduct);
        for (String bandName : sourceProduct.getBandNames()){
            ProductUtils.copyBand(bandName, sourceProduct, targetProduct);
            FlagCoding flagCoding = sourceProduct.getBand(bandName).getFlagCoding();
            if (flagCoding != null) {
                FlagCoding tarFlagCoding = targetProduct.getFlagCodingGroup().get(flagCoding.getName());
                targetProduct.getBand(bandName).setSampleCoding(tarFlagCoding);
            }
        }

        createTargetProductBands();

        targetProduct.setPreferredTileSize(100, 100);

        setTargetProduct(targetProduct);

    }

    private void createTargetProductBands() {
        Band srcBand = sourceProduct.getBand(srcBandName);
        Band targetBand = new Band(tarBandName, srcBand.getDataType(), rasterWidth, rasterHeight);
        targetBand.setDescription(srcBand.getDescription());
        targetBand.setNoDataValue(srcBand.getNoDataValue());
        targetBand.setNoDataValueUsed(true);
        targetProduct.addBand(targetBand);
    }

    private float getMedianPixel(Tile inputTile, int iTarX, int iTarY) {

        double noDataValue = inputTile.getRasterDataNode().getNoDataValue();
        double median;
        double[] tmp = new double[9];
        int n = 0;

        for (int iy = iTarY - 1; iy <= iTarY + 1; iy++) {
            for (int ix = iTarX - 1; ix <= iTarX + 1; ix++) {
                if (iy >= 0 && iy < rasterHeight && ix >= 0 && ix < rasterWidth) {
                    double val = (double) inputTile.getSampleFloat(ix, iy);
                    if (Double.compare(val, noDataValue) != 0) {
                        tmp[n] = val;
                        n++;
                    }
                }
            }
        }
        if (n >= 2) {
            Arrays.sort(tmp, 0, n);
            int n2 = n/2;
            if (n == n2*2) {
                median = (tmp[n2]+tmp[n2-1])/2;
            }
            else {
                median = tmp[n2];
            }
        }
        else median = noDataValue;
        return (float) median;
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(MedianOp.class);
        }
    }
}
