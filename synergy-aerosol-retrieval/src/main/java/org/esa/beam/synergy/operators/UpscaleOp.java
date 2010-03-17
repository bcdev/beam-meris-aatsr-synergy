/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.glevel.MultiLevelImage;
import java.awt.Rectangle;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.ParameterBlock;
import java.awt.image.renderable.RenderableImage;
import javax.media.jai.JAI;
import javax.media.jai.RenderedOp;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.BitmaskDef;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductNodeGroup;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.util.ProductUtils;

/**
 *
 * @author akheckel
 */
public class UpscaleOp extends Operator {

    @SourceProduct(alias = "aerosol",
                   label = "Name (Downscaled aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product sourceProduct;

    @SourceProduct(alias = "synergy",
                   label = "Name (Original Synergy product)",
                   description = "Select a Synergy product.")
    private Product originalProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(defaultValue = "7", label = "BoxSize to invert in Pixel (n x n)", interval = "[1, 100]")
    private int scalingFactor;
    private int offset;

    private static String productName = "SYNERGY UPSCALED AOT";
    private static String productType = "SYNERGY UPSCALED AOT";

    private String aotName = RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME;
    private String errName = RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME;
    private String modelName = RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME;

    private int sourceRasterWidth;
    private int sourceRasterHeight;
    private int targetRasterWidth;
    private int targetRasterHeight;


    @Override
    public void initialize() throws OperatorException {
        
        offset = scalingFactor / 2;
        sourceRasterWidth = sourceProduct.getSceneRasterWidth();
        sourceRasterHeight = sourceProduct.getSceneRasterHeight();
        targetRasterWidth = originalProduct.getSceneRasterWidth();
        targetRasterHeight = originalProduct.getSceneRasterHeight();
        
        createTargetProduct();
        targetProduct.setPreferredTileSize(1100, 1100);
        setTargetProduct(targetProduct);
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        Rectangle tarRec = targetTile.getRectangle();

        int srcX = (tarRec.x - offset) / scalingFactor;
        int srcY = (tarRec.y - offset) / scalingFactor;
        int srcWidth = tarRec.width / scalingFactor + 1;
        int srcHeight = tarRec.height / scalingFactor + 1;
        if (srcX >= sourceRasterWidth) {
            srcX = sourceRasterWidth - 2;
            srcWidth = 2;
        }
        if (srcY >= sourceRasterHeight) {
            srcY = sourceRasterHeight - 2;
            srcHeight = 2;
        }
        Rectangle srcRec = new Rectangle(srcX, srcY, srcWidth, srcHeight);

        Band srcBand = null;
        Tile srcTile = null;
        if (originalProduct.containsBand(targetBand.getName())) {
            srcBand = originalProduct.getBand(targetBand.getName());
            if (srcBand != null) {
                srcTile = getSourceTile(srcBand, tarRec, ProgressMonitor.NULL);
                targetTile.setRawSamples(srcTile.getRawSamples());
            }
        }
        else if (sourceProduct.containsBand(targetBand.getName())) {
            srcBand = sourceProduct.getBand(targetBand.getName());
            srcTile = getSourceTile(srcBand, srcRec, ProgressMonitor.NULL);
            if (targetBand.getName().equals(aotName)
                    || targetBand.getName().equals(errName)
                    || targetBand.getName().startsWith(modelName)
                    || targetBand.isFlagBand()) {
                
                upscaleTileCopy(srcTile, targetTile, tarRec, pm);
            } else {
                upscaleTileBilinear(srcTile, targetTile, tarRec, pm);
            }
        }
    }

    private void createTargetProduct() {
        
        targetProduct = new Product(productName, productType, targetRasterWidth, targetRasterHeight);

        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        ProductUtils.copyGeoCoding(originalProduct, targetProduct);
        targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("latitude"));
        targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("longitude"));
        ProductUtils.copyTiePointGrids(originalProduct, targetProduct);
        ProductUtils.copyFlagBands(originalProduct, targetProduct);

        for (String fcName : sourceProduct.getFlagCodingGroup().getNodeNames()) {
            if (!targetProduct.getFlagCodingGroup().contains(fcName)) {
                FlagCoding srcFlagCoding = sourceProduct.getFlagCodingGroup().get(fcName);
                ProductUtils.copyFlagCoding(srcFlagCoding, targetProduct);
            }
        }
        for (String bmName : sourceProduct.getBitmaskDefNames()) {
            if (!targetProduct.containsBitmaskDef(bmName)) {
                BitmaskDef srcBitmaskDef = sourceProduct.getBitmaskDef(bmName);
                targetProduct.addBitmaskDef(srcBitmaskDef);
            }
        }

        Band targetBand;
        for (Band srcBand : sourceProduct.getBands()) {
            String bandName = srcBand.getName();
            if (originalProduct.containsBand(bandName)) {
                if (!originalProduct.getBand(bandName).isFlagBand()) {
                    ProductUtils.copyBand(bandName, originalProduct, targetProduct);
                }
            }
            else {
                targetBand = new Band(bandName, srcBand.getDataType(), targetRasterWidth, targetRasterHeight);
                targetBand.setDescription(srcBand.getDescription());
                targetBand.setNoDataValue(srcBand.getNoDataValue());
                targetBand.setNoDataValueUsed(true);
                FlagCoding srcFlagCoding = srcBand.getFlagCoding();
                if (srcFlagCoding != null) {
                    FlagCoding tarFlagCoding = targetProduct.getFlagCodingGroup().get(srcFlagCoding.getName());
                    targetBand.setSampleCoding(tarFlagCoding);
                }
                targetProduct.addBand(targetBand);
            }
        }
    }

    private void upscaleTileBilinear(Tile srcTile, Tile tarTile, Rectangle tarRec, ProgressMonitor pm) {
        
        int tarX = tarRec.x;
        int tarY = tarRec.y;
        int tarWidth = tarRec.width;
        int tarHeight = tarRec.height;

        for (int iTarY = tarY; iTarY < tarY + tarHeight; iTarY++) {
            int iSrcY = (iTarY - offset) / scalingFactor;
            if (iSrcY >= sourceRasterHeight - 1) iSrcY = sourceRasterHeight - 2;
            float yFac = (float) (iTarY - offset) / scalingFactor - iSrcY;
            for (int iTarX = tarX; iTarX < tarX + tarWidth; iTarX++) {
                checkForCancelation(pm);
                int iSrcX = (iTarX - offset) / scalingFactor;
                if (iSrcX >= sourceRasterWidth - 1) iSrcX = sourceRasterWidth - 2;
                float xFrac = (float) (iTarX - offset) / scalingFactor - iSrcX;
                float erg = (1.0f - xFrac) * (1.0f - yFac) * srcTile.getSampleFloat(iSrcX, iSrcY);
                erg +=        (xFrac) * (1.0f - yFac) * srcTile.getSampleFloat(iSrcX+1, iSrcY);
                erg += (1.0f - xFrac) *        (yFac) * srcTile.getSampleFloat(iSrcX, iSrcY+1);
                erg +=        (xFrac) *        (yFac) * srcTile.getSampleFloat(iSrcX+1, iSrcY+1);
                tarTile.setSample(iTarX, iTarY, erg);
            }
        }
    }

    private void upscaleTileCopy(Tile srcTile, Tile tarTile, Rectangle tarRec, ProgressMonitor pm) {

        int tarX = tarRec.x;
        int tarY = tarRec.y;
        int tarWidth = tarRec.width;
        int tarHeight = tarRec.height;

        for (int iTarY = tarY; iTarY < tarY + tarHeight; iTarY++) {
            int iSrcY = iTarY / scalingFactor;
            if (iSrcY >= sourceRasterHeight) iSrcY = sourceRasterHeight - 1;
            for (int iTarX = tarX; iTarX < tarX + tarWidth; iTarX++) {
                if (pm.isCanceled()) {
                    break;
                }
                int iSrcX = iTarX / scalingFactor;
                if (iSrcX >= sourceRasterWidth) iSrcX = sourceRasterWidth - 1;
                float erg = srcTile.getSampleFloat(iSrcX, iSrcY);
                tarTile.setSample(iTarX, iTarY, erg);
            }
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(UpscaleOp.class);
        }
    }
}
