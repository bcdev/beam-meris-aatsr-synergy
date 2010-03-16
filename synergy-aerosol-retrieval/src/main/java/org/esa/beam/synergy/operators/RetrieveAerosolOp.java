package org.esa.beam.synergy.operators;

import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;

import java.util.HashMap;
import java.util.Map;
import java.io.File;

/**
 * Main operator for aerosol and SDR retrievals within MERIS/AATSR Synergy project.
 *
 * @author Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 17:54:34 +0000 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.RetrieveAerosol",
                  version = "1.0-SNAPSHOT",
                  authors = "Olaf Danne",
                  copyright = "(c) 2009 by Brockmann Consult",
                  description = "Retrieve Aerosol over ocean and land.")
public class RetrieveAerosolOp extends Operator {

    @SourceProduct(alias = "source",
                   label = "Name (Preprocessed product with cloud flags)",
                   description = "Select a collocated MERIS AATSR product obtained from preprocessing AND cloudscreening.")
    private Product synergyProduct;


    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(defaultValue = "true",
               description = "Compute ocean AODs",
               label = "Retrieve AODs over ocean")
    boolean computeOcean;

    @Parameter(defaultValue = "true",
               description = "Compute land AODs",
               label = "Retrieve AODs over land")
    boolean computeLand;

    @Parameter(defaultValue = RetrieveAerosolConstants.LUT_PATH_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.LUT_PATH_PARAM_DESCRIPTION,
               label = RetrieveAerosolConstants.LUT_PATH_PARAM_LABEL)
    private String lutPath;

//    @Parameter(defaultValue = RetrieveAerosolConstants.LUT_LAND_PATH_PARAM_DEFAULT,
//               description = RetrieveAerosolConstants.LUT_PATH_PARAM_DESCRIPTION,
//               label = RetrieveAerosolConstants.LUT_LAND_PATH_PARAM_LABEL)
//    private String landLutPath;

    @Parameter(defaultValue = RetrieveAerosolConstants.SOIL_SPEC_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.SOIL_SPEC_PARAM_DESCRIPTION,
                label = RetrieveAerosolConstants.SOIL_SPEC_PARAM_LABEL)
    private String soilSpecName;

    @Parameter(defaultValue = RetrieveAerosolConstants.VEG_SPEC_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.VEG_SPEC_PARAM_DESCRIPTION,
                label = RetrieveAerosolConstants.VEG_SPEC_PARAM_LABEL)
    private String vegSpecName;

    @Parameter(alias = RetrieveAerosolConstants.AEROSOL_MODEL_PARAM_NAME,
               defaultValue = RetrieveAerosolConstants.AEROSOL_MODEL_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.AEROSOL_MODEL_PARAM_DESCRIPTION,
               label = RetrieveAerosolConstants.AEROSOL_MODEL_PARAM_LABEL)
    private String aerosolModelString;

    @Parameter(defaultValue = "7",
               description = "Pixels to average (n x n, with n odd number) for AOD retrieval",
               label = "N x N average for AOD retrieval",
               interval = "[1, 99]")
    private int aveBlock;

//    @Parameter(defaultValue = "true",
//               description = "Interpolate AOD gaps",
//               label = "Interpolate AOD gaps")
//    boolean doAodInterpolation;
    boolean doAodInterpolation = true;

    @Parameter(defaultValue = "true",
               description = "Rescale to original resolution after interpolation of AOD gaps",
               label = "Rescale AOD to original resolution after interpolation")
    boolean rescaleToOriginalResolution;

    @Parameter(defaultValue = "true",
               label = "Retrieve surface directional reflectances (over land only)",
               description = "Retrieve surface directional reflectances AODs")
    boolean computeSurfaceReflectances;

    //@Parameter(defaultValue = "true", label = "Use Cld Screening")
    boolean doCldScreen = true;
    //@Parameter(defaultValue = "true", label = "AATSR")
    boolean doAATSR = true;
    //@Parameter(defaultValue = "true", label = "MERIS")
    boolean doMERIS = true;

    //@Parameter(defaultValue = "false", label = "dump pixel")
    boolean dumpPixel = false;
    //@Parameter(label = "dump pixel X")
    int dumpPixelX = 213;
    //@Parameter(label = "dump pixel Y")
    int dumpPixelY = 80;


    @Override
    public void initialize() throws OperatorException {

        SynergyUtils.validateCloudScreeningProduct(synergyProduct);

        // make sure aveBlock is a odd number
        if ((aveBlock % 2) == 0) {
            aveBlock += 1;
        }

        // get the ocean product...
        Product oceanProduct = null;
        if (computeOcean) {
            Map<String, Product> oceanInput = new HashMap<String, Product>(1);
            oceanInput.put("source", synergyProduct);
            Map<String, Object> oceanParams = new HashMap<String, Object>(3);
            oceanParams.put("lutPath", lutPath);
            oceanParams.put("aveBlock", aveBlock);
            oceanParams.put("computeLand", computeLand);
            oceanProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(RetrieveAerosolOceanOp.class), oceanParams, oceanInput);
        }

        // get the land product..
        Product landProduct = null;
        if (computeLand) {
            Map<String, Product> landInput = new HashMap<String, Product>(1);
            landInput.put("source", synergyProduct);
            Map<String, Object> landParams = new HashMap<String, Object>(6);
            landParams.put("lutPath", lutPath);
            landParams.put("soilSpecName", soilSpecName);
            landParams.put("vegSpecName", vegSpecName);
            landParams.put("aerosolModelString", aerosolModelString);
            landParams.put("aveBlock", aveBlock);
            landParams.put("doCldScreen", doCldScreen);
            landParams.put("doAATSR", doAATSR);
            landParams.put("doMERIS", doMERIS);
            landParams.put("dumpPixel", dumpPixel);
            landParams.put("dumpPixelX", dumpPixelX/aveBlock);
            landParams.put("dumpPixelY", dumpPixelY/aveBlock);
            landProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(RetrieveAerosolLandOp.class), landParams, landInput);
        }

        // get the merged land/ocean product...
        Product landOceanAerosolProduct = null;
        if (computeOcean && computeLand) {
            Map<String, Product> landOceanInput = new HashMap<String, Product>(2);
            landOceanInput.put("source", synergyProduct);
            landOceanInput.put("ocean", oceanProduct);
            landOceanInput.put("land", landProduct);
            Map<String, Object> landOceanParams = new HashMap<String, Object>(1);
            //landOceanParams.put("aveBlock", aveBlock);
            landOceanAerosolProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(LandOceanMergeOp.class), landOceanParams, landOceanInput);
        } else if (!computeLand) {
            landOceanAerosolProduct = oceanProduct;
        } else if (!computeOcean) {
            landOceanAerosolProduct = landProduct;
        }

        Product landOceanInterpolatedProduct;
        if (doAodInterpolation && !(computeOcean &&!computeLand)) {
            Map<String, Product> landOceanInterpolatedInput = new HashMap<String, Product>(1);
            landOceanInterpolatedInput.put("source", landOceanAerosolProduct);
            Map<String, Object> landOceanInterpolatedParams = new HashMap<String, Object>();
            //landOceanInterpolatedProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(AotBoxcarInterpolationOp.class), landOceanInterpolatedParams, landOceanInterpolatedInput);
            landOceanInterpolatedProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(AotExtrapOp.class), landOceanInterpolatedParams, landOceanInterpolatedInput);
        } else {
            landOceanInterpolatedProduct = landOceanAerosolProduct;
        }

        Product landOceanUpscaledProduct;
        if (rescaleToOriginalResolution) {
            Map<String, Product> landOceanUpscaledInput = new HashMap<String, Product>(2);
            landOceanUpscaledInput.put("synergy", synergyProduct);
            landOceanUpscaledInput.put("aerosol", landOceanInterpolatedProduct);
            Map<String, Object> landOceanUpscaledParams = new HashMap<String, Object>(1);
            landOceanUpscaledParams.put("scalingFactor", aveBlock);
            landOceanUpscaledProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(UpscaleOp.class), landOceanUpscaledParams, landOceanUpscaledInput);
        } else {
            landOceanUpscaledProduct = landOceanInterpolatedProduct;
        }

        Product surfaceReflectanceProduct;
        if (computeSurfaceReflectances && computeLand) {
            Map<String, Product> surfaceReflectanceInput = new HashMap<String, Product>(2);
            surfaceReflectanceInput.put("synergy", synergyProduct);
            surfaceReflectanceInput.put("aerosol", landOceanUpscaledProduct);
            Map<String, Object> surfaceReflectanceParams = new HashMap<String, Object>(5);
            surfaceReflectanceParams.put("lutPath", lutPath);
            surfaceReflectanceParams.put("soilSpecName", soilSpecName);
            surfaceReflectanceParams.put("vegSpecName", vegSpecName);
            surfaceReflectanceParams.put("dumpPixel", dumpPixel);
            surfaceReflectanceParams.put("dumpPixelX", dumpPixelX);
            surfaceReflectanceParams.put("dumpPixelY", dumpPixelY);
            surfaceReflectanceProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(RetrieveSdrLandOp.class), surfaceReflectanceParams, surfaceReflectanceInput);

        } else {
            surfaceReflectanceProduct = landOceanUpscaledProduct;
        }

        targetProduct = surfaceReflectanceProduct;
    }


    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RetrieveAerosolOp.class);
        }
    }
}
