/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.synergy.util.AerosolHelpers;
import org.esa.beam.util.ProductUtils;

import java.util.HashMap;
import java.util.Map;


/**
 *
 * @author akheckel
 */
@OperatorMetadata(alias = "synergy.AotExtrap",
                  version = "1.0-SNAPSHOT",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "AOT extrapolation of missing data.")
public class AotExtrapOp extends Operator {

    @SourceProduct(alias = "synergy",
                   label = "Name (Synergy cloud srceening product)",
                   description = "Select a Synergy aerosol product.")
    private Product synergyProduct;

     @SourceProduct(alias = "source",
                   label = "Name (Synergy aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(defaultValue = "7", label = "Pixels to average for AOD retrieval", interval = "[1, 100]")
    private int aveBlock;

    private Product aveAotProd;


    @Override
    public void initialize() throws OperatorException {

        Map<String, Product> aveInputProd = new HashMap<String, Product>(1);
        aveInputProd.put("source", sourceProduct);
        Map<String, Object> aveParam = new HashMap<String, Object>(3);
        aveParam.put("aveBlock", 3);
        aveAotProd = GPF.createProduct(OperatorSpi.getOperatorAlias(BoxAveOp.class), aveParam, aveInputProd);

        int[] aveBlocks = {3,3,3,3,3,5,7,9,11,13,15,17,19,21};
        for (int i=0; i<aveBlocks.length; i++) {
            aveInputProd.put("source", aveAotProd);
            aveParam.put("aveBlock", aveBlocks[i]);
            aveAotProd = GPF.createProduct(OperatorSpi.getOperatorAlias(BoxAveOp.class), aveParam, aveInputProd);
        }

        Map<String, Object> emptyParam = new HashMap<String, Object>();
        targetProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(MedianOp.class),emptyParam, aveAotProd);

        //copy source bands, TPs, geocoding and MetaData

        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("latitude"));
        targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("longitude"));
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
//        ProductUtils.copyFlagCodings(sourceProduct, targetProduct);
        ProductUtils.copyBitmaskDefs(sourceProduct, targetProduct);
        AerosolHelpers.copyDownscaledFlagBands(synergyProduct, targetProduct, aveBlock*1.0f);
        for (String srcBandName : sourceProduct.getBandNames()) {
            if (!targetProduct.containsBand(srcBandName)) {
                ProductUtils.copyBand(srcBandName, sourceProduct, targetProduct);
                FlagCoding srcFlagCoding = sourceProduct.getBand(srcBandName).getFlagCoding();
                if (srcFlagCoding != null) {
                    FlagCoding tarFlagCoding = targetProduct.getFlagCodingGroup().get(srcFlagCoding.getName());
                    targetProduct.getBand(srcBandName).setSampleCoding(tarFlagCoding);
                }
                targetProduct.getBand(srcBandName).setSourceImage(sourceProduct.getBand(srcBandName).getSourceImage());
            }
        }

    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(AotExtrapOp.class);
        }
    }
}
