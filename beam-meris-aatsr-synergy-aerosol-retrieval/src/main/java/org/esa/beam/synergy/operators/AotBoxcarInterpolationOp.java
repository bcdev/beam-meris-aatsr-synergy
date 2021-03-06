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
 * NOT USED ANY MORE (replaced by {@link AotExtrapOp}).
 *
 * @author Andreas Heckel, Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 18:54:34 +0100 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.AotBoxcarInterpolation",
                  version = "1.2",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "AOT interpolation of missing data.", internal = true)
public class AotBoxcarInterpolationOp extends Operator {

    private static final String PRODUCT_NAME = "SYNERGY INTERPOLATED";
    private static final String PRODUCT_TYPE = "SYNERGY INTERPOLATED";

    @SourceProduct(alias = "source",
                   label = "Name (Synergy aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private int sourceRasterWidth;
    private int sourceRasterHeight;

    @Override

    public void initialize() throws OperatorException {

        sourceRasterWidth = sourceProduct.getSceneRasterWidth();
        sourceRasterHeight = sourceProduct.getSceneRasterHeight();
        createTargetProduct();

        final int boxWidth = 5;
        final int boxHeight = 5;
        final Dimension boxDimension = new Dimension(boxWidth / 2, boxHeight / 2);

        for (Band band : sourceProduct.getBands()) {
            if (!band.isFlagBand()) {

                final RenderedImage sourceImage = band.getSourceImage();
                System.out.printf("Source, size: %d x %d\n", sourceImage.getWidth(), sourceImage.getHeight());

                final RenderedOp extSourceImage = getImageWithZeroBorderExtension(sourceImage, boxDimension);
                System.out.printf("Extended Source, size: %d x %d\n", extSourceImage.getWidth(),
                                  extSourceImage.getHeight());

                double[] low = new double[1];
                double[] high = new double[1];
                double[] map = new double[1];

                // todo: clean up this!
                low[0] = SynergyConstants.OUTPUT_AOT_BAND_NODATAVALUE - 0.5;
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
                ParameterBlock args = new ParameterBlock();
                args.addSource(dstImage);
                args.add(boxWidth);
                args.add(boxHeight);
                args.add(boxWidth / 2);
                args.add(boxHeight / 2);

                final RenderedOp boxImage = JAI.create("boxfilter", args);
                System.out.printf("Boximage, size: %d x %d\n", boxImage.getWidth(), boxImage.getHeight());

                Band interpolBand = targetProduct.getBand(band.getName());
                if ("land_aerosol_model".equals(band.getName())) {
                    interpolBand.setSourceImage(extSourceImage);
                } else {
                    interpolBand.setSourceImage(boxImage);
                }
            }
        }
    }


    private void createTargetProduct() {

        targetProduct = new Product(PRODUCT_NAME, PRODUCT_TYPE, sourceRasterWidth, sourceRasterHeight);

        ProductUtils.copyFlagBands(sourceProduct, targetProduct);
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);

        createTargetProductBands();

        setTargetProduct(targetProduct);

    }

    private void createTargetProductBands() {
        for (Band band : sourceProduct.getBands()) {
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
