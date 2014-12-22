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
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.synergy.util.SynergyUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Main operator for aerosol and SDR retrievals within MERIS/AATSR Synergy project.
 *
 * @author Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 17:54:34 +0000 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.RetrieveAerosol",
        version = "1.1",
        authors = "Olaf Danne",
        copyright = "(c) 2009 by Brockmann Consult",
        description = "Retrieves aerosol over ocean and land.")
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

    @Parameter(defaultValue = "false",
            label = "Retrieve surface directional reflectances (over land only)",
            description = "Retrieve surface directional reflectances AODs")
    boolean computeSurfaceReflectances;

    @Parameter(defaultValue = SynergyConstants.SOIL_SPEC_PARAM_DEFAULT,
            description = SynergyConstants.SOIL_SPEC_PARAM_DESCRIPTION,
            label = SynergyConstants.SOIL_SPEC_PARAM_LABEL)
    private String soilSpecName;

    @Parameter(defaultValue = SynergyConstants.VEG_SPEC_PARAM_DEFAULT,
            description = SynergyConstants.VEG_SPEC_PARAM_DESCRIPTION,
            label = SynergyConstants.VEG_SPEC_PARAM_LABEL)
    private String vegSpecName;

    @Parameter(defaultValue = "false")
    private boolean useCustomLandAerosol = false;

    @Parameter(alias = SynergyConstants.AEROSOL_MODEL_PARAM_NAME,
            defaultValue = SynergyConstants.AEROSOL_MODEL_PARAM_DEFAULT)
    private String customLandAerosol;

    @Parameter(defaultValue = "7",
            description = "Pixels to average (n x n, with n odd number) for AOD retrieval",
            label = "N x N average for AOD retrieval",
            interval = "[1, 99]")
    private int aveBlock;

    boolean doAodInterpolation = true;

    boolean rescaleToOriginalResolution = true;


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
            landParams.put("soilSpecName", soilSpecName);
            landParams.put("vegSpecName", vegSpecName);
            landParams.put("aveBlock", aveBlock);
            landParams.put("useCustomLandAerosol", useCustomLandAerosol);
            landParams.put("customLandAerosol", customLandAerosol);
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
            landOceanAerosolProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(LandOceanMergeOp.class), landOceanParams, landOceanInput);
        } else if (!computeLand) {
            landOceanAerosolProduct = oceanProduct;
        } else {
            landOceanAerosolProduct = landProduct;
        }

        Product landOceanInterpolatedProduct;
        if (doAodInterpolation && computeLand) {
            Map<String, Product> landOceanInterpolatedInput = new HashMap<String, Product>(2);
            landOceanInterpolatedInput.put("synergy", synergyProduct);
            landOceanInterpolatedInput.put("source", landOceanAerosolProduct);
            Map<String, Object> landOceanInterpolatedParams = new HashMap<String, Object>();
            landOceanInterpolatedParams.put("aveBlock", aveBlock);
            landOceanInterpolatedProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(AotExtrapOp.class), landOceanInterpolatedParams, landOceanInterpolatedInput);
        } else {
            landOceanInterpolatedProduct = landOceanAerosolProduct;
        }

        Map<String, Product> landOceanUpscaledInput = new HashMap<String, Product>(2);
        landOceanUpscaledInput.put("synergy", synergyProduct);
        landOceanUpscaledInput.put("aerosol", landOceanInterpolatedProduct);
        Map<String, Object> landOceanUpscaledParams = new HashMap<String, Object>(1);
        landOceanUpscaledParams.put("scalingFactor", aveBlock);
        Product landOceanUpscaledProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(UpscaleOp.class), landOceanUpscaledParams, landOceanUpscaledInput);

//        Product landOceanUpscaledProduct = landOceanAerosolProduct;

        Product surfaceReflectanceProduct;
        if (computeSurfaceReflectances && computeLand) {
            Map<String, Product> surfaceReflectanceInput = new HashMap<String, Product>(2);
            surfaceReflectanceInput.put("synergy", synergyProduct);
            surfaceReflectanceInput.put("aerosol", landOceanUpscaledProduct);
            Map<String, Object> surfaceReflectanceParams = new HashMap<String, Object>(5);
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
