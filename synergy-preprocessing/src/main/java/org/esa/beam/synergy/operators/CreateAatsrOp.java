/*
 * Copyright (C) 2002-2008 by Brockmann Consult
 * Copyright (C) 2008 by IPL
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
import org.esa.beam.aatsrrecalibration.operators.RecalibrateAATSRReflectancesOp;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.RsMathUtils;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * Operator for creating an AATSR product.
 *
 * @author Jordi Munyoz-Mari and Luis Gomez-Chova
 * @version $Revision: 7988 $ $Date: 2010-01-14 12:51:28 +0100 (Do, 14 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.CreateAatsr",
        version = "1.0-SNAPSHOT",
        authors = "Jordi Munyoz-Mari and Luis Gomez-Chova",
        copyright = "(c) 2008-90 by IPL",
        description = "This operator prepares the AATRS product.")

public class CreateAatsrOp extends Operator {

    @SourceProduct(alias = "source", description = "The source product.")
    Product sourceProduct;

    @TargetProduct(description = "The target product.")
    Product targetProduct;

    Product recalProduct;
    TiePointGrid sunZenithBandNadir;
    TiePointGrid sunZenithBandFward;

    @Override
    public void initialize() throws OperatorException {

        //Dimension dim = sourceProduct.getPreferredTileSize();

        // Recalibrate AATSR reflectances
        Map<String, Object> emptyParams = new HashMap<String, Object>();
        recalProduct =
            GPF.createProduct(OperatorSpi.getOperatorAlias(RecalibrateAATSRReflectancesOp.class), emptyParams, sourceProduct);

        // Recover initial tile size
        //sourceProduct.setPreferredTileSize(dim);

        // Create output product
        String productType = sourceProduct.getProductType();
        String productName = sourceProduct.getName();
        int sceneWidth = sourceProduct.getSceneRasterWidth();
        int sceneHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(productName, productType, sceneWidth, sceneHeight);
        targetProduct.setDescription(sourceProduct.getDescription());
        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());

        //ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        for (TiePointGrid tpg : sourceProduct.getTiePointGrids()) {
            targetProduct.addTiePointGrid(tpg.cloneTiePointGrid());
            String tpgName = tpg.getName();
            targetProduct.getTiePointGrid(tpgName).setUnit(tpg.getUnit());
            targetProduct.getTiePointGrid(tpgName).setDescription(tpg.getDescription());
        }

        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyFlagBands(sourceProduct, targetProduct);

        // Copy bands
        for (Band sourceBand : recalProduct.getBands()) {
            final String bandName = sourceBand.getName();
            if (bandName.startsWith("reflec")) {
                //SynergyPreprocessingUtils.info("  Copying band " + bandName);
                final Band targetBand = ProductUtils.copyBand(bandName, recalProduct, targetProduct);
                /*
                Band targetBand = new Band(sourceBand.getName(),
                                           ProductData.TYPE_INT16,
                                           sourceBand.getRasterWidth(),
                                           sourceBand.getRasterHeight());
                ProductUtils.copyRasterDataNodeProperties(sourceBand, targetBand);
                targetProduct.addBand(targetBand);
                */
                ProductUtils.copySpectralBandProperties(sourceBand, targetBand);
                targetBand.setScalingFactor(1.0/10000.0);
                targetBand.setUnit("dl");
            }
            else if (bandName.startsWith("btemp")) {
                //SynergyPreprocessingUtils.info("  Adding  band " + bandName);
                targetProduct.addBand(sourceBand);
            }
        }

        sunZenithBandNadir = recalProduct.getTiePointGrid(EnvisatConstants.AATSR_SUN_ELEV_NADIR_DS_NAME);
        sunZenithBandFward = recalProduct.getTiePointGrid(EnvisatConstants.AATSR_SUN_ELEV_FWARD_DS_NAME);
        if (sunZenithBandNadir == null)
            throw new OperatorException("Unable to get tie point grid for AATSR Sun Elevation nadir");
        if (sunZenithBandFward == null)
            throw new OperatorException("Unable to get tie point grid for AATSR Sun Elevation fward");
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm)
            throws OperatorException {


        Rectangle rect = targetTile.getRectangle();

        pm.beginTask("Processing frame ...", rect.height);

        //SynergyPreprocessingUtils.info("Processing " + targetBand.getName() + " " +
        //                   rect.x + " " + rect.y + " " + rect.width + " " + rect.height);

        final String bandName = targetBand.getName();
        Band band = recalProduct.getBand(bandName);
        Tile sourceTile = getSourceTile(band, rect, pm);

        if (bandName.startsWith("reflec")) {
            short[] sourceData = sourceTile.getDataBufferShort();
            short[] targetData = targetTile.getDataBufferShort();
            final float[] rad = new float[1];
            final float[] sea = new float[1];
            TiePointGrid sunZenithBand =
                bandName.startsWith("reflec_nadir") ? sunZenithBandNadir : sunZenithBandFward;

            // Calculate cosine correction and write new band
            for (int y = rect.y; y < rect.y+rect.height; y++) {
                for (int x = rect.x; x < rect.x+rect.width; x++) {
                    int pos = targetTile.getDataBufferIndex(x, y);
                    rad[0] = sourceData[pos];
                    sea[0] = 90 - sunZenithBand.getPixelFloat(x,y); // 90d correction
                    RsMathUtils.radianceToReflectance(rad, sea, (float)(java.lang.Math.PI), rad);
                    targetData[pos] = (short) rad[0];
                }
                pm.worked(1);
            }
        }
        else {
            // Just copy it
            targetTile.setRawSamples(sourceTile.getRawSamples());
            pm.worked(rect.height);
        }

        pm.done();
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CreateAatsrOp.class);
        }
    }
}
