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
import java.io.File;

/**
 * Master operator for MERIS/AATSR Synergy toolbox.
 *
 * @author Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 17:54:34 +0000 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.Master",
                  version = "1.1",
                  authors = "Olaf Danne",
                  copyright = "(c) 2009 by Brockmann Consult",
                  description = "The master operator for the MERIS/(A)ATSR toolbox.")
public class MasterOp extends Operator {

    @SourceProduct(alias = "MERIS",
                   description = "MERIS source product.")
    Product merisSourceProduct;

    @SourceProduct(alias = "AATSR",
                   description = "AATSR source product.")
    Product aatsrSourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(defaultValue = "false",
               description = "Create preprocessing product only",
               label = "Create preprocessing product only")
    boolean createPreprocessingProduct;

    @Parameter(defaultValue = "false",
               description = "Create cloud screening product only",
               label = "Create cloud screening product only")
    boolean createCloudScreeningProduct;

    @Parameter(defaultValue = "true",
               description = "Create aerosol and atmospheric correction product",
               label = "Create aerosol and atmospheric correction product")
    boolean createAerosolProduct;

    // cloud screening parameters...
    @Parameter(defaultValue = "true",
            label = "Use the AATSR forward view when classifying",
            description = "Use the AATSR forward view when classifying.")
    private boolean useForwardView;

    @Parameter(defaultValue = "true",
            label = "Compute cloud index",
            description = "Compute cloud index.")
    private boolean computeCOT;

	@Parameter(defaultValue = "false",
            label = "Compute snow risk flag",
            description = "Compute snow risk flag.")
    private boolean computeSF;

    @Parameter(defaultValue = "false",
               label = "Compute cloud shadow risk flag",
               description = "Compute cloud shadow risk flag.")
    private boolean computeSH;


    // aerosol retrieval parameters
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

    @Parameter(defaultValue = "7",
               description = "Pixels to average (n x n, with n odd number) for AOD retrieval",
               label = "N x N average for AOD retrieval",
               interval = "[1, 99]")
    private int aveBlock;

    @Parameter(defaultValue = "false")
    private boolean useCustomLandAerosol = false;

    @Parameter(alias = SynergyConstants.AEROSOL_MODEL_PARAM_NAME,
               defaultValue = SynergyConstants.AEROSOL_MODEL_PARAM_DEFAULT)
    private String customLandAerosol;


    @Override
    public void initialize() throws OperatorException {

        SynergyUtils.validateMerisProduct(merisSourceProduct);
        SynergyUtils.validateAatsrProduct(aatsrSourceProduct);

        if (!SynergyUtils.validateAuxdata(useCustomLandAerosol, customLandAerosol)) {
            return;
        }

        // get the colocated 'preprocessing' product...
        Product preprocessingProduct = null;
        Map<String, Product> preprocessingInput = new HashMap<String, Product>(2);
        preprocessingInput.put("MERIS", merisSourceProduct);
        preprocessingInput.put("AATSR", aatsrSourceProduct);
        Map<String, Object> preprocessingParams = new HashMap<String, Object>();
        preprocessingProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(CreateSynergyOp.class), preprocessingParams, preprocessingInput);

        targetProduct = preprocessingProduct;

        // get the cloud screening product..
        Product cloudScreeningProduct = null;
        if (createCloudScreeningProduct || createAerosolProduct) {
            Map<String, Product> cloudScreeningInput = new HashMap<String, Product>(1);
            cloudScreeningInput.put("source", preprocessingProduct);
            Map<String, Object> cloudScreeningParams = new HashMap<String, Object>(4);
            cloudScreeningParams.put("useForwardView", useForwardView);
            cloudScreeningParams.put("computeCOT", computeCOT);
            cloudScreeningParams.put("computeSF", computeSF);
            cloudScreeningParams.put("computeSH", computeSH);
            cloudScreeningProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(SynergyCloudScreeningOp.class), cloudScreeningParams, cloudScreeningInput);
            targetProduct = cloudScreeningProduct;
        }

        // get the aerosol / atmospheric correction product...
        Product landOceanAerosolProduct;
        if (createAerosolProduct) {
            Map<String, Product> landOceanInput = new HashMap<String, Product>(1);
            landOceanInput.put("source", cloudScreeningProduct);
            Map<String, Object> landOceanParams = new HashMap<String, Object>();
            landOceanParams.put("computeOcean", computeOcean);
            landOceanParams.put("computeLand", computeLand);
            landOceanParams.put("computeSurfaceReflectances", computeSurfaceReflectances);
            landOceanParams.put("soilSpecName", soilSpecName);
            landOceanParams.put("vegSpecName", vegSpecName);
            landOceanParams.put("aveBlock", aveBlock);
            landOceanParams.put("useCustomLandAerosol", useCustomLandAerosol);
            landOceanParams.put("customLandAerosol", customLandAerosol);

            landOceanAerosolProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(RetrieveAerosolOp.class), landOceanParams, landOceanInput);
            targetProduct = landOceanAerosolProduct;
        }
    }

    
    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(MasterOp.class);
        }
    }
}
