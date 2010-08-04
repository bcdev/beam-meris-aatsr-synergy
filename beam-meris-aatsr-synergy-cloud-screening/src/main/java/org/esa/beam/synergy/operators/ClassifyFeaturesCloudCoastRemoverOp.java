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
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.synergy.util.SynergyUtils;
import org.esa.beam.util.ProductUtils;

import javax.media.jai.KernelJAI;
import javax.media.jai.operator.AndDescriptor;
import javax.media.jai.operator.ConvolveDescriptor;
import javax.media.jai.operator.MaxFilterDescriptor;
import java.awt.Rectangle;
import java.awt.image.RenderedImage;
import java.util.Map;

@OperatorMetadata(alias = "synergy.ClassifyFeaturesCloudCoastRemover",
        version = "1.1",
        authors = "Jordi Munyoz-Mari, Luis Gomez-Chova",
        copyright = "(c) 2009 IPL-UV",
        description = "Remove false positives due to coastlines.", internal=true)

public class ClassifyFeaturesCloudCoastRemoverOp extends Operator {

    @SourceProduct(alias = "features",
            label = "Synergy features product",
            description = "Synergy features product.")
    private Product featProduct;
    
    @SourceProduct(alias = "nnProduct",
            label = "Synergy NN classified product",
            description = "The synergy product classified using the NN operator.")
    private Product nnProduct;    
    
	@TargetProduct(description = "A product with the coastline/cloudmask false positives removed.")
    private Product targetProduct;        
    
    // Bands
    private transient Band[] sBand_nnCm = new Band[1];
    private transient Band[] sBand_dilated = new Band[sBand_nnCm.length];
    private transient Band[] sBand_max7x7  = new Band[sBand_nnCm.length];
    private transient Band[] tBand_eroded  = new Band[sBand_nnCm.length];
    
	@Override
	public void initialize() throws OperatorException {
    	// Get coastline
    	final RenderedImage coastIm = featProduct.getBand(SynergyConstants.F_COASTLINE).getGeophysicalImage();
    	// Apply max 5x5 filter
    	final RenderedImage max5x5Im = MaxFilterDescriptor.create(coastIm, MaxFilterDescriptor.MAX_MASK_SQUARE, 5, null);

    	// Get cloudmasks
    	sBand_nnCm[0] = nnProduct.getBand(SynergyConstants.B_CLOUDMASK);
    	// It doesn't make sense to remove coast line from snow areas
    	//sBand_nnCm[1] = nnProduct.getBand(SynergyCloudScreeningConstants.B_SNOWMASK);
    	
    	// Intermediate images
    	final RenderedImage[] nnCmIm     = new RenderedImage[sBand_nnCm.length];
    	final RenderedImage[] dilatedIm  = new RenderedImage[nnCmIm.length];
    	final RenderedImage[] expandedIm = new RenderedImage[nnCmIm.length];
    	final RenderedImage[] max7x7Im   = new RenderedImage[nnCmIm.length];
    	
    	// Create kernel for sum filter
    	final float[] data = new float[5*5];
    	for (int i=0; i<data.length; i++) data[i] = 1f;
    	KernelJAI kernel = new KernelJAI(5,5,data);
    	
    	for (int i=0; i<sBand_nnCm.length; i++) {
    		// Get NN cloudmask images
        	nnCmIm[i] = sBand_nnCm[i].getGeophysicalImage(); 
        	// 'and' cloudmask and max5x5Im
    		dilatedIm[i]  = AndDescriptor.create(max5x5Im, nnCmIm[i], null);
    		// Expand dilated image
    		expandedIm[i] = ConvolveDescriptor.create(dilatedIm[i], kernel, null);
    		// Max expanded image
    		max7x7Im[i]   =  MaxFilterDescriptor.create(expandedIm[i], MaxFilterDescriptor.MAX_MASK_SQUARE, 7, null);
    		
        	sBand_dilated[i] = new Band("max5x5", ProductData.TYPE_FLOAT32,
        			sBand_nnCm[i].getRasterWidth(), sBand_nnCm[i].getRasterHeight());
        	sBand_dilated[i].setSourceImage(dilatedIm[i]);
        	
        	sBand_max7x7[i] = new Band("max7x7", ProductData.TYPE_FLOAT32,
        			sBand_nnCm[i].getRasterWidth(), sBand_nnCm[i].getRasterHeight());
        	sBand_max7x7[i].setSourceImage(max7x7Im[i]);
    	}
    	
        // Construct target product
        final String type = nnProduct.getProductType() + "_ERODED";
        targetProduct = new Product("Synergy coastline eroded", type,
                                    nnProduct.getSceneRasterWidth(),
                                    nnProduct.getSceneRasterHeight());

        targetProduct.setStartTime(nnProduct.getStartTime());
        targetProduct.setEndTime(nnProduct.getEndTime());

        tBand_eroded[0] = targetProduct.addBand(SynergyConstants.B_COAST_ERODED, ProductData.TYPE_INT8);
        //tBand_eroded[1] = targetProduct.addBand(SynergyCloudScreeningConstants.B_COAST_ERODED_NADIR, ProductData.TYPE_INT8);
        
        ProductUtils.copyMetadata(featProduct, targetProduct);
        targetProduct.setPreferredTileSize(32,32);
	}
	
    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle,
                                 ProgressMonitor pm) throws OperatorException {

        pm.beginTask("Processing frames ...", targetRectangle.height);

        try {
            // Source tiles
            final Tile[] sTile_nnCm    = SynergyUtils.getSourceTiles(sBand_nnCm, targetRectangle, pm, this);
            final Tile[] sTile_dilated = SynergyUtils.getSourceTiles(sBand_dilated, targetRectangle, pm, this);
            final Tile[] sTile_max7x7  = SynergyUtils.getSourceTiles(sBand_max7x7, targetRectangle, pm, this);
            // Target tiles
            final Tile[] tTile_cloudmask_coast_eroded = SynergyUtils.getTargetTiles(tBand_eroded, targetTiles);
            
            for (int y=targetRectangle.y; y<targetRectangle.y + targetRectangle.height; y++) {                
                for (int x=targetRectangle.x; x<targetRectangle.x + targetRectangle.width; x++) {
                    
                    checkForCancellation(pm);

                    for (int i=0; i<sTile_nnCm.length; i++) {
	                    final int m = sTile_nnCm[i].getSampleInt(x, y);
	                    final int v = sTile_dilated[i].getSampleInt(x, y) * sTile_max7x7[i].getSampleInt(x, y);
	                    tTile_cloudmask_coast_eroded[i].setSample(x, y, (v>0 && v<10 ? 0 : m));
                    }
                }
                
                pm.worked(1);
            }
        }
        finally {
            pm.done();
        }        
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(ClassifyFeaturesCloudCoastRemoverOp.class);
        }
    }
}
