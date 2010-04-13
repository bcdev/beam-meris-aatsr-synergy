/* 
 * Copyright (C) 2002-2008 by Brockmann Consult
 * Copyright (C) 2008-2009 by IPL, University of Valencia
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.GeoPos;
import org.esa.beam.framework.datamodel.PixelPos;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.dataop.dem.ElevationModel;
import org.esa.beam.framework.dataop.dem.ElevationModelDescriptor;
import org.esa.beam.framework.dataop.dem.ElevationModelRegistry;
import org.esa.beam.framework.dataop.dem.Orthorectifier;
import org.esa.beam.framework.dataop.resamp.Resampling;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.synergy.util.SynergyUtils;

import java.awt.Rectangle;
import java.util.Map;

/**
 * Operator for create an elevation band.
 *
 * @author Jordi Munyoz-Mari and Luis Gomez-Chova
 * @version $Revision: 7481 $ $Date: 2009-12-11 20:03:20 +0100 (Fr, 11 Dez 2009) $
 */
@OperatorMetadata(alias = "synergy.CreateElevationBand",
        version = "1.1",
        authors = "Jordi Munyoz-Mari and Luis Gomez-Chova",
        copyright = "(c) 2008-09 by IPL, University of Valencia",
        description = "This operator creates and elevation band for a product.")

public class CreateElevationBandOp extends Operator {

    @SourceProduct(alias = "source", description = "The source product.")
    Product sourceProduct;

    @TargetProduct(description = "The target product.")
    Product targetProduct;

    final static String ORTHORECT_LATITUDE_BANDNAME = "ortho_corr_lat";
    final static String ORTHORECT_LONGITUDE_BANDNAME = "ortho_corr_lon";
    // Target product bands
    Band demBand;
    Band latBand = null;
    Band lonBand = null;
    // DEM and orthorectifier
    ElevationModel DEM;
    Orthorectifier orthorectifier = null;
    
    @Override
    public void initialize() throws OperatorException {
        
        final ElevationModelDescriptor demDescriptor = getDEMDescriptor("GETASSE30");
        if (demDescriptor == null) {
            throw new OperatorException("Unable to get GETASSE30 descriptor");
        }
        // Bilinear interpolation was the default resampling used by the deprecated method createDem()
        DEM = demDescriptor.createDem(Resampling.BILINEAR_INTERPOLATION);
        if (DEM == null) {
            throw new OperatorException("Couldn't create DEM instance");
        }
        
        // Create output product
        String productType = sourceProduct.getProductType();
        String productName = sourceProduct.getName();
        int sceneWidth = sourceProduct.getSceneRasterWidth();
        int sceneHeight = sourceProduct.getSceneRasterHeight();
        
        targetProduct = new Product(productName, productType, sceneWidth, sceneHeight);
        targetProduct.setDescription(sourceProduct.getDescription());
        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());
        
        // Add new elevation band
        demBand = targetProduct.addBand(SynergyConstants.DEM_ELEVATION, ProductData.TYPE_INT16);
        demBand.setSynthetic(true);
        demBand.setNoDataValue(demDescriptor.getNoDataValue());
        demBand.setUnit("meters");
        demBand.setDescription(demDescriptor.getName());

        // Add orthorectified bands
        if (sourceProduct.canBeOrthorectified()) {
            SynergyUtils.info("  Product can be orthorectified");
            orthorectifier =
                new Orthorectifier(sourceProduct.getSceneRasterWidth(),
                                   sourceProduct.getSceneRasterHeight(),
                                   sourceProduct.getRasterDataNode(sourceProduct.getBandNames()[0]).getPointing(),
                                   DEM, 10);

            latBand = targetProduct.addBand(ORTHORECT_LATITUDE_BANDNAME, ProductData.TYPE_FLOAT32);
            latBand.setSynthetic(true);
            latBand.setUnit("degree");
            latBand.setDescription("Orthorectification corrected latitude");
            lonBand = targetProduct.addBand(ORTHORECT_LONGITUDE_BANDNAME, ProductData.TYPE_FLOAT32);
            lonBand.setSynthetic(true);
            lonBand.setUnit("degree");
            lonBand.setDescription("Orthorectification corrected longitude");            
        }
        else SynergyUtils.info("  Product cannot be orthorectified");
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle rect,
            ProgressMonitor pm) throws OperatorException {
        
        final Tile demTile = targetTiles.get(demBand);
        Tile latTile = null;
        Tile lonTile = null;
        if (latBand != null) latTile = targetTiles.get(latBand);
        if (lonBand != null) lonTile = targetTiles.get(lonBand);
        
        final GeoPos geoPos = new GeoPos();
        final PixelPos pixelPos = new PixelPos();
        float elevation;
        final float noDataValue = DEM.getDescriptor().getNoDataValue();
        
        pm.beginTask("Processing frame ...", rect.height);
        
        for (int y=rect.y; y<rect.y+rect.height; y++) {
            for (int x=rect.x; x<rect.x+rect.width; x++) {               
                pixelPos.setLocation(x + 0.5f, y + 0.5f);
                // Get geo position
                if (orthorectifier != null) { // Always true for correction
                    orthorectifier.getGeoPos(pixelPos, geoPos);
                }
                else { // Only for DEM
                    sourceProduct.getGeoCoding().getGeoPos(pixelPos, geoPos);
                }
                // Add elevation for DEM
                try {
                    elevation = DEM.getElevation(geoPos);
                } catch (Exception e) {
                    elevation = noDataValue;
                }
                demTile.setSample(x, y, elevation);
                // Latitude and longitude
                if (latTile != null) latTile.setSample(x, y, geoPos.lat);
                if (lonTile != null) lonTile.setSample(x, y, geoPos.lon);
                
                pm.worked(1);
            }
        }
        
        pm.done();
    }
    
    /**
     * Gets an elevation model descriptor.
     * 
     * @param demName the DEM name.
     * @return the elevation model descriptor.
     */
    private ElevationModelDescriptor getDEMDescriptor(final String demName)
    {
        final ElevationModelRegistry elevationModelRegistry = ElevationModelRegistry.getInstance();
        final ElevationModelDescriptor demDescriptor = elevationModelRegistry.getDescriptor(demName);
        
        if (demDescriptor == null) {
            SynergyUtils.info("The DEM '" + demName + "' is not supported.");
            return null;
        }
        if (demDescriptor.isInstallingDem()) {
            SynergyUtils.info("The DEM '" + demName + "' is currently being installed.");
            return null;
        }
        if (!demDescriptor.isDemInstalled()) {
            SynergyUtils.info("  " + demName + " is not installed");
            return null;
        }
        return demDescriptor;
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CreateElevationBandOp.class);
        }
    }    
}
