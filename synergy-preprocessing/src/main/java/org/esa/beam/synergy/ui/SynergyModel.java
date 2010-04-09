package org.esa.beam.synergy.ui;

import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.ParameterDescriptorFactory;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.synergy.util.SynergyConstants;
import com.bc.ceres.binding.PropertyContainer;

import java.util.Map;
import java.util.HashMap;

/**
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class SynergyModel {
    
    // MasterOp parameters:
    @Parameter(defaultValue = "false",
               description = "Create preprocessing product only",
               label = "Create preprocessing product only")
    boolean createPreprocessingProduct = false;

    @Parameter(defaultValue = "false",
               description = "Create cloud screening product only",
               label = "Create cloud screening product only")
    boolean createCloudScreeningProduct = false;

    @Parameter(defaultValue = "true",
               description = "Create aerosol and atmospheric correction product",
               label = "Create aerosol and atmospheric correction product")
    boolean createAerosolProduct = true;

    // Preprocessing parameters:
    // no user options required

    // Cloud screening parameters:
    @Parameter(defaultValue = "true",
            label = "Use the AATSR forward view when classifying",
            description = "Use the AATSR forward view when classifying.")
    private boolean useForwardView = true;

    @Parameter(defaultValue = "true",
            label = "Compute cloud index",
            description = "Compute cloud index.")
    private boolean computeCOT = true;

	@Parameter(defaultValue = "false",
            label = "Compute snow risk flag",
            description = "Compute snow risk flag.")
    private boolean computeSF = false;

    @Parameter(defaultValue = "false",
               label = "Compute cloud shadow risk flag",
               description = "Compute cloud shadow risk flag.")
    private boolean computeSH = false;


    // Aerosol retrieval parameters:
   @Parameter(defaultValue = "true",
               description = "Compute ocean AODs",
               label = "Retrieve AODs over ocean")
    boolean computeOcean = true;

    @Parameter(defaultValue = "true",
               description = "Compute land AODs",
               label = "Retrieve AODs over land")
    boolean computeLand = true;

    @Parameter(defaultValue = "false",
               label = "Retrieve surface directional reflectances (over land only)",
               description = "Retrieve surface directional reflectances AODs")
    boolean computeSurfaceReflectances = false;

    @Parameter(defaultValue = SynergyConstants.SOIL_SPEC_PARAM_DEFAULT,
               description = SynergyConstants.SOIL_SPEC_PARAM_DESCRIPTION,
                label = SynergyConstants.SOIL_SPEC_PARAM_LABEL)
    private String soilSpecName = SynergyConstants.SOIL_SPEC_PARAM_DEFAULT;

    @Parameter(defaultValue = SynergyConstants.VEG_SPEC_PARAM_DEFAULT,
               description = SynergyConstants.VEG_SPEC_PARAM_DESCRIPTION,
                label = SynergyConstants.VEG_SPEC_PARAM_LABEL)
    private String vegSpecName = SynergyConstants.VEG_SPEC_PARAM_DEFAULT;

    @Parameter(defaultValue = "false")
    private boolean useCustomLandAerosol = false;

    @Parameter(alias = SynergyConstants.AEROSOL_MODEL_PARAM_NAME,
               defaultValue = SynergyConstants.AEROSOL_MODEL_PARAM_DEFAULT)
    private String customLandAerosol = SynergyConstants.AEROSOL_MODEL_PARAM_DEFAULT;

    @Parameter(defaultValue = "7",
               description = "Pixels to average (n x n, with n odd number) for AOD retrieval",
               label = "N x N average for AOD retrieval",
               interval = "[1, 99]")
    private int aveBlock = 7;

    private Product sourceProduct;

    private PropertyContainer propertyContainer;

    public SynergyModel() {
        propertyContainer = PropertyContainer.createObjectBacked(this, new ParameterDescriptorFactory());
    }

    public Product getSourceProduct() {
        return sourceProduct;
    }



    public Map<String, Object> getAllSynergyParameters() {
        HashMap<String, Object> params = new HashMap<String, Object>();
        configMaster(params);
        configSynergyOp(params);
        configCloudScreeningOp(params);
        configAerosolOp(params);
        return params;
    }

    public Map<String, Object> getPreprocessingParameters() {
        HashMap<String, Object> params = new HashMap<String, Object>();
        configSynergyOp(params);
        return params;
    }

    public Map<String, Object> getCloudScreeningParameters() {
        HashMap<String, Object> params = new HashMap<String, Object>();
        configCloudScreeningOp(params);
        return params;
    }

    public Map<String, Object> getAerosolParameters() {
        HashMap<String, Object> params = new HashMap<String, Object>();
        configAerosolOp(params);
        return params;
    }

    public Map<String, Object> getMasterParameters() {
        HashMap<String, Object> params = new HashMap<String, Object>();
        configMaster(params);
        return params;
    }

    ///////////////// END OF PUBLIC ////////////////////////////////////////

    private void configMaster(HashMap<String, Object> params) {
        params.put("createPreprocessingProduct", createPreprocessingProduct);
        params.put("createCloudScreeningProduct", createCloudScreeningProduct);
        params.put("createAerosolProduct", createAerosolProduct);
    }

    private void configSynergyOp(HashMap<String, Object> params) {
       // no user options required
    }

    private void configCloudScreeningOp(HashMap<String, Object> params) {
        params.put("useForwardView", useForwardView);
        params.put("computeCOT", computeCOT);
        params.put("computeSF", computeSF);
        params.put("computeSH", computeSH);
    }

    private void configAerosolOp(HashMap<String, Object> params) {
        params.put("computeOcean", computeOcean);
        params.put("computeLand", computeLand);
        params.put("computeSurfaceReflectances", computeSurfaceReflectances);
        params.put("soilSpecName", soilSpecName);
        params.put("vegSpecName", vegSpecName);
//        params.put("aerosolModelString", aerosolModelString);
        params.put("useCustomLandAerosol", useCustomLandAerosol);
        params.put("customLandAerosol", customLandAerosol);
        params.put("aveBlock", aveBlock);
    }

    public String getSoilSpecName() {
        return soilSpecName;
    }

    public void setSoilSpecName(String soilSpecName) {
        this.soilSpecName = soilSpecName;
    }

    public String getVegSpecName() {
        return vegSpecName;
    }

    public void setVegSpecName(String vegSpecName) {
        this.vegSpecName = vegSpecName;
    }

    public PropertyContainer getPropertyContainer() {
        return propertyContainer;
    }
}
