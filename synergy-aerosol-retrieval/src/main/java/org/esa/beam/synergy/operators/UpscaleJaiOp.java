package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.synergy.util.AerosolHelpers;

import javax.media.jai.Interpolation;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.ScaleDescriptor;
import java.awt.Rectangle;
import java.awt.image.RenderedImage;

/**
 * This operator provides an upscaling of AOTs to original resolution
 * by bicubic interpolation.
 *
 * @author Olaf Danne
 * @version $Revision: 8041 $ $Date: 2010-01-20 17:23:15 +0100 (Mi, 20 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.AotUpscale",
                  version = "1.0-SNAPSHOT",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "AOT upscaling of interpolated data.")
public class UpscaleJaiOp extends Operator {

    @SourceProduct(alias = "synergy",
                   label = "Name (Collocated MERIS AATSR product)",
                   description = "Select a Collocated MERIS AATSR product.")
    private Product synergyProduct;

    @SourceProduct(alias = "aerosol",
                   label = "Name (Synergy aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product aerosolProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(alias = "scalingfactor",
               defaultValue = "10.0f",
               description = "Scaling factor",
               label = "Scaling factor")
    private float scalingFactor;

    private static String productName = "SYNERGY UPSCALED";
    private static String productType = "SYNERGY UPSCALED";

    private int sourceRasterWidth;
    private int sourceRasterHeight;

    @Override
    public void initialize() throws OperatorException {

        sourceRasterWidth = aerosolProduct.getSceneRasterWidth();
        sourceRasterHeight = aerosolProduct.getSceneRasterHeight();
        createTargetProduct();

        for (Band band : aerosolProduct.getBands()) {
            Interpolation interpolation = null;
            if (!band.getName().equals("land_aerosol_model")) {
                interpolation = Interpolation.getInstance(Interpolation.INTERP_BICUBIC);
            }
            if (!band.isFlagBand()) {
                RenderedImage sourceImage = band.getSourceImage();
                System.out.printf("Source, size: %d x %d\n", sourceImage.getWidth(), sourceImage.getHeight());
                RenderedOp upscaledImage = ScaleDescriptor.create(sourceImage,
                                                                  scalingFactor,
                                                                  scalingFactor,
                                                                  0.0f, 0.0f,
                                                                  interpolation,
                                                                  null);
                System.out.printf("Upscaled, size: %d x %d\n", upscaledImage.getWidth(), upscaledImage.getHeight());

                Band upscaledBand = targetProduct.getBand(band.getName());
                upscaledBand.setSourceImage(upscaledImage);
            }
        }
    }

    private void createTargetProduct() {

        //TODO: check wether to copy TIEs from source or aerosol Product
        targetProduct = new Product(productName, productType,
                                    (int)scalingFactor*sourceRasterWidth, (int)scalingFactor*sourceRasterHeight);

        AerosolHelpers.copySynergyFlagBands(synergyProduct, targetProduct);

        createTargetProductBands();

        setTargetProduct(targetProduct);

    }

    private void createTargetProductBands() {
        for (Band band: aerosolProduct.getBands()) {
            if (!band.isFlagBand()) {
                Band targetBand = new Band(band.getName(), band.getDataType(),
                                           (int)scalingFactor*sourceRasterWidth, (int)scalingFactor*sourceRasterHeight);
                targetBand.setDescription(band.getDescription());
                targetBand.setNoDataValue(band.getNoDataValue());
                targetBand.setNoDataValueUsed(true);
                targetProduct.addBand(targetBand);
            }
        }

        AerosolHelpers.copyRescaledTiePointGrids(synergyProduct, targetProduct,
                                                 (int)scalingFactor*sourceRasterWidth, (int)scalingFactor*sourceRasterHeight);
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        Rectangle rectangle = targetTile.getRectangle();

        pm.beginTask("Processing frame...", rectangle.height);

        try {
            int xMax = synergyProduct.getSceneRasterWidth();
            int yMax = synergyProduct.getSceneRasterHeight();

            if (targetBand.isFlagBand()) {
                writeSynergyFlagBands(targetBand, targetTile, pm, rectangle, xMax, yMax);
            }
//            else if (targetBand.getName().equals("land_aerosol_model")) {
//                final Rectangle tr = targetTile.getRectangle();
//                Rectangle aerosolRectangle = new Rectangle((int) (tr.x/scalingFactor),
//                        (int) (tr.y/scalingFactor),
//                        (int) (tr.width/scalingFactor),
//                        (int) (tr.height/scalingFactor));
//                System.out.println("target rect: " + tr);
//                System.out.println("aero rect: " + aerosolRectangle);
//                Tile aerosolModelTile = getSourceTile(aerosolProduct.getBand("land_aerosol_model"), aerosolRectangle, pm);
//                int numAve = (int) scalingFactor+1;
//                for (int y = rectangle.y; y < Math.min(yMax, rectangle.y + rectangle.height); y++) {
//                    for (int x = rectangle.x; x < Math.min(xMax, rectangle.x + rectangle.width); x++) {
//                        int xAer = x/numAve;
//                        int yAer = y/numAve;
//                        final int aerosolModel = aerosolModelTile.getSampleInt(xAer, yAer);
//                        targetTile.setSample(x, y, aerosolModel);
//                    }
//                }
//            }
        } catch (Exception e) {
            throw new OperatorException("Failed to merge land/ocean aerosol products:\n" + e.getMessage(), e);
        } finally {
            pm.done();
        }
    }

    private void writeSynergyFlagBands(Band targetBand, Tile targetTile, ProgressMonitor pm, Rectangle rectangle, int xMax, int yMax) {
        Tile aatsrConfidFlagNadirTile = getSourceTile(synergyProduct.getBand(RetrieveAerosolConstants.CONFID_NADIR_FLAGS_AATSR), rectangle, pm);
        Tile aatsrConfidFlagFwardTile = getSourceTile(synergyProduct.getBand(RetrieveAerosolConstants.CONFID_FWARD_FLAGS_AATSR), rectangle, pm);
        Tile aatsrCloudFlagNadirTile = getSourceTile(synergyProduct.getBand(RetrieveAerosolConstants.CLOUD_NADIR_FLAGS_AATSR), rectangle, pm);
        Tile aatsrCloudFlagFwardTile = getSourceTile(synergyProduct.getBand(RetrieveAerosolConstants.CLOUD_FWARD_FLAGS_AATSR), rectangle, pm);
        Tile merisL1FlagsTile = getSourceTile(synergyProduct.getBand(RetrieveAerosolConstants.L1_FLAGS_MERIS), rectangle, pm);
        Tile merisCloudFlagTile = getSourceTile(synergyProduct.getBand(RetrieveAerosolConstants.CLOUD_FLAG_MERIS), rectangle, pm);

        FlagCoding aatsrConfidNadirFlagCoding = synergyProduct.getFlagCodingGroup().get(RetrieveAerosolConstants.CONFID_NADIR_FLAGS_AATSR);
        FlagCoding aatsrConfidFwardFlagCoding = synergyProduct.getFlagCodingGroup().get(RetrieveAerosolConstants.CONFID_FWARD_FLAGS_AATSR);
        FlagCoding aatsrCloudNadirFlagCoding = synergyProduct.getFlagCodingGroup().get(RetrieveAerosolConstants.CLOUD_NADIR_FLAGS_AATSR);
        FlagCoding aatsrCloudFwardFlagCoding = synergyProduct.getFlagCodingGroup().get(RetrieveAerosolConstants.CLOUD_FWARD_FLAGS_AATSR);
        FlagCoding merisL1FlagCoding = synergyProduct.getFlagCodingGroup().get(RetrieveAerosolConstants.L1_FLAGS_MERIS);
        FlagCoding merisCloudFlagCoding = synergyProduct.getFlagCodingGroup().get(RetrieveAerosolConstants.CLOUD_FLAG_MERIS);


        for (int y = rectangle.y; y < Math.min(yMax, rectangle.y + rectangle.height); y++) {
            for (int x = rectangle.x; x < Math.min(xMax, rectangle.x + rectangle.width); x++) {
                if (targetBand.getName().equals(RetrieveAerosolConstants.CONFID_NADIR_FLAGS_AATSR)) {
                    for (int i=0; i<aatsrConfidNadirFlagCoding.getNumAttributes(); i++) {
                        targetTile.setSample(x, y, i, aatsrConfidFlagNadirTile.getSampleBit(x, y, i));
                    }
                }
                if (targetBand.getName().equals(RetrieveAerosolConstants.CONFID_FWARD_FLAGS_AATSR)) {
                    for (int i=0; i<aatsrConfidFwardFlagCoding.getNumAttributes(); i++) {
                        targetTile.setSample(x, y, i, aatsrConfidFlagFwardTile.getSampleBit(x, y, i));
                    }
                }
                if (targetBand.getName().equals(RetrieveAerosolConstants.CLOUD_NADIR_FLAGS_AATSR)) {
                    for (int i=0; i<aatsrCloudNadirFlagCoding.getNumAttributes(); i++) {
                        targetTile.setSample(x, y, i, aatsrCloudFlagNadirTile.getSampleBit(x, y, i));
                    }
                }
                if (targetBand.getName().equals(RetrieveAerosolConstants.CLOUD_FWARD_FLAGS_AATSR)) {
                    for (int i=0; i<aatsrCloudFwardFlagCoding.getNumAttributes(); i++) {
                        targetTile.setSample(x, y, i, aatsrCloudFlagFwardTile.getSampleBit(x, y, i));
                    }
                }
                if (targetBand.getName().equals(RetrieveAerosolConstants.L1_FLAGS_MERIS)) {
                    for (int i=0; i<merisL1FlagCoding.getNumAttributes(); i++) {
                        targetTile.setSample(x, y, i, merisL1FlagsTile.getSampleBit(x, y, i));
                    }
                }
                if (targetBand.getName().equals(RetrieveAerosolConstants.CLOUD_FLAG_MERIS)) {
                    for (int i=0; i<merisCloudFlagCoding.getNumAttributes(); i++) {
                        targetTile.setSample(x, y, i, merisCloudFlagTile.getSampleBit(x, y, i));
                    }
                }
                pm.worked(1);
            }
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(UpscaleJaiOp.class);
        }
    }
}