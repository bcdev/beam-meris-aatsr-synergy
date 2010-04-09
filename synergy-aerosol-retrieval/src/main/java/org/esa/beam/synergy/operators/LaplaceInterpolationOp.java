/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

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
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.synergy.util.math.LaplaceInterpolation;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CURRENTLY NOT USED!
 *
 * @author Andreas Heckel
 * @version $Revision: 8034 $ $Date: 2010-01-20 15:47:34 +0100 (Mi, 20 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.PerformInterpol",
                  version = "1.0-SNAPSHOT",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "Perform Laplace interpolation of fields")
public class LaplaceInterpolationOp extends Operator {

    @SourceProduct(alias = "source",
                   label = "Name (Synergy intermediate product)",
                   description = "Select a Synergy intermediate aerosol product")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(alias = SynergyConstants.OUTPUT_PRODUCT_NAME_NAME,
              defaultValue = SynergyConstants.OUTPUT_PRODUCT_NAME_DEFAULT,
              description = SynergyConstants.OUTPUT_PRODUCT_NAME_DESCRIPTION,
              label = SynergyConstants.OUTPUT_PRODUCT_NAME_LABEL)
    private String productName;

    @Parameter(alias = SynergyConstants.OUTPUT_PRODUCT_TYPE_NAME,
              defaultValue = SynergyConstants.OUTPUT_PRODUCT_TYPE_DEFAULT,
              description = SynergyConstants.OUTPUT_PRODUCT_TYPE_DESCRITPION,
              label = SynergyConstants.OUTPUT_PRODUCT_TYPE_LABEL)
    private String productType;

    private String aotBandName = "aot";
    private String errBandName = "aot_uncertainty";
    private String modelBandName = "aerosol_model";


    private int rasterWidth;
    private int rasterHeight;



    @Override
    public void initialize() throws OperatorException {

        rasterWidth = sourceProduct.getSceneRasterWidth();
        rasterHeight = sourceProduct.getSceneRasterHeight();

        createTargetProduct();

    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        int aveBlock = 100;
        Rectangle targetRectangle = targetTile.getRectangle();
        Rectangle big = new Rectangle(targetRectangle.x-aveBlock,targetRectangle.y-aveBlock,targetRectangle.width+2*aveBlock,targetRectangle.height+2*aveBlock);

        int x1 = targetRectangle.x;
        int x2 = targetRectangle.x + targetRectangle.width - 1;
        int y1 = targetRectangle.y;
        int y2 = targetRectangle.y + targetRectangle.height - 1;

        int end = targetBand.getName().indexOf("_intp");
        String srcBandName = targetBand.getName().substring(0, end);
        System.out.println(srcBandName);
        final Band sourceBand = sourceProduct.getBand(srcBandName);
        Tile sT = getSourceTile(sourceBand, big, ProgressMonitor.NULL);

        double[][] dataArr = new double[big.height][big.width];
        double noDataValue = sT.getRasterDataNode().getNoDataValue();

        for (int iy=0; iy<big.height; iy++) {
            for (int ix=0; ix<big.width; ix++) {
                boolean outside = ((big.x+ix < 0) || (big.x+ix >= rasterWidth)
                                || (big.y+iy < 0) || (big.y+iy >= rasterHeight));
                dataArr[iy][ix] = outside ? noDataValue : (double) sT.getSampleFloat(big.x+ix, big.y+iy);
            }
        }

        LaplaceInterpolation lp = new LaplaceInterpolation(dataArr, noDataValue);
        try {
            lp.solveInterp();
        } catch (Exception ex) {
            Logger.getLogger(LaplaceInterpolationOp.class.getName()).log(Level.SEVERE, null, ex);
        }

        for (int iy=y1; iy<=y2; iy++) {
            for (int ix=x1; ix<=x2; ix++) {
                targetTile.setSample(ix, iy, (float) dataArr[iy-targetRectangle.y+aveBlock][ix-targetRectangle.x+aveBlock]);
            }
        }
        
    }

    private void createTargetProduct() {
        targetProduct = new Product(productName, productType, rasterWidth, rasterHeight);

        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);

        createTargetProductBands();

        targetProduct.setPreferredTileSize(128,128);
        setTargetProduct(targetProduct);
    }

    private void createTargetProductBands() {
        //String bandName = SynergyPreprocessingConstants.OUTPUT_AOT_BAND_NAME
        //                  + String.format("_%02d", aerosolModels.get(iAM));

        final Band aotBand = new Band(aotBandName+"_intp", ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight);
        aotBand.setDescription(SynergyConstants.OUTPUT_AOT_BAND_DESCRIPTION);
        aotBand.setNoDataValue(SynergyConstants.OUTPUT_AOT_BAND_NODATAVALUE);
        aotBand.setNoDataValueUsed(SynergyConstants.OUTPUT_AOT_BAND_NODATAVALUE_USED);
        aotBand.setValidPixelExpression(aotBand.getName() + ">= 0 AND " + aotBand.getName() + "<= 1");
        targetProduct.addBand(aotBand);

        final Band errBand = new Band(errBandName+"_intp", ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight);
        errBand.setDescription(SynergyConstants.OUTPUT_AOTERR_BAND_DESCRIPTION);
        errBand.setNoDataValue(SynergyConstants.OUTPUT_AOTERR_BAND_NODATAVALUE);
        errBand.setNoDataValueUsed(SynergyConstants.OUTPUT_AOTERR_BAND_NODATAVALUE_USED);
        errBand.setValidPixelExpression(errBand.getName() + ">= 0 AND " + errBand.getName() + "<= 1");
        targetProduct.addBand(errBand);

        final Band modelBand = new Band(modelBandName+"_intp", ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight);
        modelBand.setDescription(SynergyConstants.OUTPUT_AOTMODEL_BAND_DESCRIPTION);
        modelBand.setNoDataValue(SynergyConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
        modelBand.setNoDataValueUsed(SynergyConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE_USED);
        modelBand.setValidPixelExpression(modelBand.getName() + ">= 0 AND " + modelBand.getName() + "<= 1");
        targetProduct.addBand(modelBand);
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(LaplaceInterpolationOp.class);
        }
    }

}
