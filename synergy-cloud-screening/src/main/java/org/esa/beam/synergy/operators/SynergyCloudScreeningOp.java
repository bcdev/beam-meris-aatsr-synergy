package org.esa.beam.synergy.operators;

import java.util.HashMap;
import java.util.Map;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.util.ProductUtils;

// TODO: remove not used UIs
// TODO: sort bands in final product
// TODO: press the eclipse 'tasks' button and do the todo's

@OperatorMetadata(alias = "synergy.SynergyCloudScreening",
                  version = "1.0",
                  authors = "Jordi Munyoz-Mari, Luis Gomez-Chova",
                  copyright = "(c) 2009 IPL-UV",
                  description = "Create a synergy product with cloud flags and index.")

public class SynergyCloudScreeningOp extends Operator {
    
    @SourceProduct(alias = "source",
                   label ="Name (Synergy product)",
                   description = "Select a Synergy product")
    private Product sourceProduct;
    
    @TargetProduct(description = "The target product. Contains a synergy product with cloud screening masks.")
    private Product targetProduct;

	@Parameter(defaultValue = "true",
            label = "Use the AATSR forward view when classifying",
            description = "Use the AATSR forward view when classifying.")
    private boolean useForwardView;
    
    @Parameter(defaultValue = "true",
            label = "Compute cloud abundance",
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
	    
    @Override
    public void initialize() throws OperatorException {
    	
    	// Classify features params
        Map<String, Object> cloudParams = new HashMap<String, Object>(4);
        cloudParams.put("useForwardView", useForwardView);
        cloudParams.put("computeSF", computeSF);
        cloudParams.put("computeSH", computeSH);
        cloudParams.put("computeCOT", computeCOT);
        // Classified product
        final Product cloudProduct =
            GPF.createProduct(
            		OperatorSpi.getOperatorAlias(ClassifyFeaturesOp.class),
            		cloudParams, sourceProduct);

        // Create an empty targetProduct
        targetProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType() + "_CS",
                                    sourceProduct.getSceneRasterWidth(), sourceProduct.getSceneRasterHeight());
        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());
        targetProduct.setDescription(sourceProduct.getDescription());
        
        // Copy cloudProduct bands to targetProduct
        
        ProductUtils.copyFlagBands(cloudProduct, targetProduct);
        targetProduct.addBand(cloudProduct.getBand(SynergyConstants.B_CLOUDINDEX));
        // Copy contents
        targetProduct.getBand(SynergyConstants.B_CLOUDFLAGS).
            setSourceImage(cloudProduct.getBand(SynergyConstants.B_CLOUDFLAGS).getSourceImage());
        targetProduct.getBand(SynergyConstants.B_CLOUDINDEX).
            setSourceImage(cloudProduct.getBand(SynergyConstants.B_CLOUDINDEX).getSourceImage());
        
        // And copy the sourceProduct to targetProduct
        
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        ProductUtils.copyFlagBands(sourceProduct, targetProduct);
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        
        for (Band sourceBand : sourceProduct.getBands()) {
            Band targetBand;
            if (sourceBand.getFlagCoding() == null) {
                targetBand = ProductUtils.copyBand(sourceBand.getName(), sourceProduct, targetProduct);
                ProductUtils.copySpectralBandProperties(sourceBand, targetBand);                
            }
            else {
                targetBand = targetProduct.getBand(sourceBand.getName());
            }
            // Copy band data
            targetBand.setSourceImage(sourceBand.getSourceImage());
        }
    }
    
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(SynergyCloudScreeningOp.class);
        }
    }
}
