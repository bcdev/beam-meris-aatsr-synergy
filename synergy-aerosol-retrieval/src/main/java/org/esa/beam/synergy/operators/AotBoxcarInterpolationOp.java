package org.esa.beam.synergy.operators;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.util.ProductUtils;

import javax.media.jai.JAI;
import javax.media.jai.RenderedOp;
import java.awt.Dimension;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.ParameterBlock;

/**
 * This operator provides a boxcar interpolation to fill missing data
 * in source product.
 *
 * @author Andreas Heckel, Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 18:54:34 +0100 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.AotInterpol",
                  version = "1.0-SNAPSHOT",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "AOT interpolation of missing data.")
public class AotBoxcarInterpolationOp extends Operator {

    @SourceProduct(alias = "source",
                   label = "Name (Synergy aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private static String productName = "SYNERGY INTERPOLATED";
    private static String productType = "SYNERGY INTERPOLATED";

    private int sourceRasterWidth;
    private int sourceRasterHeight;

    @Override

    public void initialize() throws OperatorException {

        sourceRasterWidth = sourceProduct.getSceneRasterWidth();
        sourceRasterHeight = sourceProduct.getSceneRasterHeight();
        createTargetProduct();

        final int boxWidth =  5;
        final int boxHeight =  5;
        final Dimension boxDimension = new Dimension(boxWidth / 2, boxHeight / 2);

        for (Band band:sourceProduct.getBands()) {
            if (!band.isFlagBand()) {

                RenderedImage sourceImage = band.getSourceImage();
                System.out.printf("Source, size: %d x %d\n", sourceImage.getWidth(), sourceImage.getHeight());

                RenderedOp extSourceImage = getImageWithZeroBorderExtension(sourceImage, boxDimension);
                System.out.printf("Extended Source, size: %d x %d\n", extSourceImage.getWidth(), extSourceImage.getHeight());

                double[] low, high, map;

                low = new double[1];
                high = new double[1];
                map = new double[1];

                // todo: clean up this!
                low[0] =  SynergyConstants.OUTPUT_AOT_BAND_NODATAVALUE - 0.5;
                high[0] = SynergyConstants.OUTPUT_AOT_BAND_NODATAVALUE + 0.5;
                map[0] = 0.0;

                // threshold operation.
                ParameterBlock pb = new ParameterBlock();
                pb.addSource(extSourceImage);
                pb.add(low);
                pb.add(high);
                pb.add(map);
                RenderedImage dstImage = JAI.create("threshold", pb);

                // upscale
                System.out.printf("Dst, size: %d x %d\n", dstImage.getWidth(), dstImage.getHeight());

                RenderedOp boxImage = JAI.create("boxfilter", dstImage,
                                            boxWidth, boxHeight,
                                            boxWidth / 2, boxHeight / 2);
                System.out.printf("Boximage, size: %d x %d\n", boxImage.getWidth(), boxImage.getHeight());

                Band interpolBand = targetProduct.getBand(band.getName());
                if (band.getName().equals("land_aerosol_model")) {
                    interpolBand.setSourceImage(extSourceImage);
                } else {
                    interpolBand.setSourceImage(boxImage);
                }
            }
        }
    }


    private void createTargetProduct() {

        targetProduct = new Product(productName, productType, sourceRasterWidth, sourceRasterHeight);

        ProductUtils.copyFlagBands(sourceProduct, targetProduct);
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);

        createTargetProductBands();

        setTargetProduct(targetProduct);

    }

    private void createTargetProductBands() {
        for (Band band:sourceProduct.getBands()) {
            if (!band.isFlagBand()) {
                Band targetBand = new Band(band.getName(), band.getDataType(), sourceRasterWidth, sourceRasterHeight);
                targetBand.setDescription(band.getDescription());
                targetBand.setNoDataValue(band.getNoDataValue());
                targetBand.setNoDataValueUsed(true);
                targetProduct.addBand(targetBand);
            }
        }
    }

    public RenderedOp getImageWithZeroBorderExtension(RenderedImage img,
                                        Dimension border) {
        ParameterBlock pb = new ParameterBlock();
        pb.addSource(img);
        pb.add(border.width);
        pb.add(border.height);
        pb.add(border.width);
        pb.add(border.height);
        return JAI.create("border", pb);
    }


    public static class Spi extends OperatorSpi {
        public Spi() {
            super(AotBoxcarInterpolationOp.class);
        }
    }
}
