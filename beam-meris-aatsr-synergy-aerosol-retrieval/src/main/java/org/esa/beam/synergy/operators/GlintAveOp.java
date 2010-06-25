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
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.gpf.operators.standard.BandMathsOp;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;
import java.io.File;

/**
 * Operator for FUB Glint processing for Synergy Ocean Aerosol Retrieval
 * (modified 'Glint' algorithm). Provides mechanism to average over given
 * number of pixels.
 *
 * @author Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 18:54:34 +0100 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.GlintAve",
                  version = "1.1",
                  authors = "Olaf Danne",
                  copyright = "(c) 2009 by Brockmann Consult",
                  description = "Glint Processor for Synergy Ocean Aerosol Retrieval.", internal=true)
public class GlintAveOp extends Operator {
    @SourceProduct(alias = "l1bSynergy",
                   description = "MERIS/AATSR synergy product.")
    private Product synergyProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private String auxdataPath = SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT + File.separator +
            "aerosolLUTs" + File.separator + "ocean";

    @Parameter(defaultValue = "10", label = "Pixels to average (n x n) for AOD retrieval", interval = "[1, 100]")
    private int aveBlock;

    private float scalingFactor;
    private int minNAve;

    private static final String INVALID_EXPRESSION = "l1_flags" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_MERIS + ".INVALID";
    private Band invalidBand;

    /* AATSR L1 Cloud Flags (just the ones needed) */
    final int AATSR_L1_CF_LAND = 0;
    final int AATSR_L1_CF_CLOUDY = 1;
    final int AATSR_L1_CF_SUNGLINT = 2;

    public static final String CONFID_NADIR_FLAGS = "confid_flags_nadir" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + "";
    public static final String CONFID_FWARD_FLAGS = "confid_flags_fward" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + "";
    public static final String CLOUD_NADIR_FLAGS = "cloud_flags_nadir" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + "";
    public static final String CLOUD_FWARD_FLAGS = "cloud_flags_fward" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + "";

    // final results
    public static final String RESULT_WINDSPEED_NAME = "windspeed";
    public static final String RESULT_GLINT_SYNERGY_MERIS12_NAME = "glint_mer12";
    public static final String RESULT_GLINT_SYNERGY_MERIS13_NAME = "glint_mer13";
    public static final String RESULT_GLINT_SYNERGY_MERIS14_NAME = "glint_mer14";
    public static final String RESULT_GLINT_SYNERGY_AATSR_16_NADIR_NAME = "glint_aatsr16_nadir";
    public static final String RESULT_GLINT_SYNERGY_AATSR_16_FWARD_NAME = "glint_aatsr16_fward";
    public static final String RESULT_GLINT_SYNERGY_AATSR_87_NADIR_NAME = "glint_aatsr87_nadir";
    public static final String RESULT_GLINT_SYNERGY_AATSR_87_FWARD_NAME = "glint_aatsr87_fward";

    private GlintSolarPart37 solarPart37;
    private GlintRetrieval glintRetrieval;

    private float solarIrradiance37;

    private Tile vaMerisTileComplete;
    private Tile vaAatsrNadirTileComplete;

    private double[][] synergyWindspeed;
    private double[][][] synergyGlint;
//    private float[] refractiveIndex;

    @Override
    public void initialize() throws OperatorException {

        solarPart37 = new GlintSolarPart37();
        glintRetrieval = new GlintRetrieval();

        scalingFactor = aveBlock;
        aveBlock /= 2;
        minNAve = (int) (scalingFactor*scalingFactor - 1);

        try {
            glintRetrieval.loadGaussParsLut(auxdataPath);
            glintRetrieval.loadGlintAuxData();
        } catch (Exception e) {
            throw new OperatorException("Failed to load Glint auxdata:\n" + e.getMessage());
        }
        createTargetProduct();

        // get solar irradiance for day of year
        String startTime = synergyProduct.getMetadataRoot().getElement(
                "MPH").getAttribute("PRODUCT")
                .getData().getElemString().substring(14);     // e.g., 20030614

        final int dayOfYear = GlintPreparation.getDayOfYear(startTime);

        solarIrradiance37 = GlintPreparation.computeSolarIrradiance37(dayOfYear);

        // correction of azimuth discontinuity:
        // set up tiles for MERIS and AATSR which cover the whole scene...
        int sceneWidth = synergyProduct.getSceneRasterWidth();
        int sceneHeight = synergyProduct.getSceneRasterHeight();
        Rectangle rect = new Rectangle(0, 0, sceneWidth, sceneHeight);
        vaMerisTileComplete = getSourceTile(synergyProduct.getTiePointGrid("view_azimuth"), rect, null);
        vaAatsrNadirTileComplete = getSourceTile(synergyProduct.getBand("view_azimuth_nadir" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rect, null);

        // correct azimuths in these tiles for later usage...
        GlintPreparation.correctViewAzimuthLinear(vaMerisTileComplete, rect);
        GlintPreparation.correctViewAzimuthLinear(vaAatsrNadirTileComplete, rect);

        synergyWindspeed = new double[sceneWidth][sceneHeight];

        synergyGlint = new double[7][sceneWidth][sceneHeight];

        for (int j = 0; j < sceneWidth; j++) {
            for (int k = 0; k < sceneHeight; k++) {
                synergyWindspeed[j][k] = -1.0;
                for (int i = 0; i < 7; i++) {
                    synergyGlint[i][j][k] = -1.0;
                }
            }
        }

    }

    //
    // This method creates the target product
    //
    private void createTargetProduct() {
        final String productType = synergyProduct.getProductType();
        final String productName = synergyProduct.getName();
        final int sceneWidth = synergyProduct.getSceneRasterWidth();
        final int sceneHeight = synergyProduct.getSceneRasterHeight();

        final int downscaledRasterWidth = (int) (Math.ceil((float) (sceneWidth / scalingFactor) - 0.5));
        final int downscaledRasterHeight = (int) (Math.ceil((float) (sceneHeight/ scalingFactor) - 0.5));

        targetProduct = new Product(productName, productType, downscaledRasterWidth, downscaledRasterHeight);
        targetProduct.setPreferredTileSize(128, 128);

        ProductUtils.copyTiePointGrids(synergyProduct, targetProduct);
        ProductUtils.copyGeoCoding(synergyProduct, targetProduct);
        ProductUtils.copyMetadata(synergyProduct, targetProduct);

        final BandMathsOp bandArithmeticOp =
                BandMathsOp.createBooleanExpressionBand(INVALID_EXPRESSION, synergyProduct);
        invalidBand = bandArithmeticOp.getTargetProduct().getBandAt(0);

        setTargetBands();
    }

    private void setTargetBands() {
        Band resultWindspeedBand = targetProduct.addBand(RESULT_WINDSPEED_NAME, ProductData.TYPE_FLOAT32);
        resultWindspeedBand.setUnit("m/s");
        resultWindspeedBand.setNoDataValue(SynergyConstants.OUTPUT_WS_BAND_NODATAVALUE);
        resultWindspeedBand.setNoDataValueUsed(SynergyConstants.OUTPUT_WS_BAND_NODATAVALUE_USED);

        // currently not needed - ocean aerosol retrieval only needs windspeed
//        Band glintMer13Band = targetProduct.addBand(RESULT_GLINT_SYNERGY_MERIS13_NAME, ProductData.TYPE_FLOAT32);
//        glintMer13Band.setUnit("1/sr");
//        glintMer13Band.setNoDataValue(SynergyPreprocessingConstants.OUTPUT_GLINT_BAND_NODATAVALUE);
//        glintMer13Band.setNoDataValueUsed(SynergyPreprocessingConstants.OUTPUT_GLINT_BAND_NODATAVALUE_USED);
//
//        Band glintAatsr87NadirBand = targetProduct.addBand(RESULT_GLINT_SYNERGY_AATSR_87_NADIR_NAME, ProductData.TYPE_FLOAT32);
//        glintAatsr87NadirBand.setUnit("1/sr");
//        glintAatsr87NadirBand.setNoDataValue(SynergyPreprocessingConstants.OUTPUT_GLINT_BAND_NODATAVALUE);
//        glintAatsr87NadirBand.setNoDataValueUsed(SynergyPreprocessingConstants.OUTPUT_GLINT_BAND_NODATAVALUE_USED);
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        final Rectangle rectangle = targetTile.getRectangle();
        final int bigWidth = (int) (scalingFactor*rectangle.getWidth());
        final int bigHeight = (int) (scalingFactor*rectangle.getHeight());
        final int bigX = (int) (scalingFactor*rectangle.getX());
        final int bigY = (int) (scalingFactor*rectangle.getY());
        final Rectangle big = new Rectangle(bigX, bigY, bigWidth, bigHeight);

        if (targetBand.isFlagBand()) {
            // no computations
            return;
        }

        pm.beginTask("Processing frame...", rectangle.height);

        try {
            final Tile szMerisTile = getSourceTile(synergyProduct.getTiePointGrid("sun_zenith"), big, pm);
            final Tile vzMerisTile = getSourceTile(synergyProduct.getTiePointGrid("view_zenith"), big, pm);
            final Tile saMerisTile = getSourceTile(synergyProduct.getTiePointGrid("sun_azimuth"), big, pm);
            final Tile zonalWindTile = getSourceTile(synergyProduct.getTiePointGrid("zonal_wind"), big, pm);
            final Tile meridWindTile = getSourceTile(synergyProduct.getTiePointGrid("merid_wind"), big, pm);

            final Tile seAatsrNadirTile = getSourceTile(synergyProduct.getBand("sun_elev_nadir" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            final Tile veAatsrNadirTile = getSourceTile(synergyProduct.getBand("view_elev_nadir" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            final Tile saAatsrNadirTile = getSourceTile(synergyProduct.getBand("sun_azimuth_nadir" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);

            final Tile cfAatsrNadirTile = getSourceTile(synergyProduct.getBand("cloud_flags_nadir" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);

            final Tile merisRad14Tile = getSourceTile(synergyProduct.getBand("radiance_14" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_MERIS + ""), big, pm);
            final Tile merisRad15Tile = getSourceTile(synergyProduct.getBand("radiance_15" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_MERIS + ""), big, pm);
            final Tile aatsrBTNadir0370Tile = getSourceTile(synergyProduct.getBand("btemp_nadir_0370" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            final Tile aatsrBTNadir1100Tile = getSourceTile(synergyProduct.getBand("btemp_nadir_1100" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            final Tile aatsrBTNadir1200Tile = getSourceTile(synergyProduct.getBand("btemp_nadir_1200" + "_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);

            final Tile isInvalid = getSourceTile(invalidBand, rectangle, pm);

            final float[] windspeed = new float[2];

            final int targetBandIndex = getTargetBandIndex(targetBand);

            for (int iY = rectangle.y; iY < rectangle.y + rectangle.height; iY++) {
                for (int iX = rectangle.x; iX < rectangle.x + rectangle.width; iX++) {

                    final int iTarX = (int) (scalingFactor*iX + aveBlock);
                    final int iTarY = (int) (scalingFactor*iY + aveBlock);
                    checkForCancelation(pm);

                    if ((targetBandIndex != -1 && synergyGlint[targetBandIndex][iX][iY] == -1.0) ||
                            synergyWindspeed[iX][iY] == -1.0) {

                        final boolean cloudFlagNadirLand = cfAatsrNadirTile.getSampleBit(iTarX, iTarY, AATSR_L1_CF_LAND);
                        final boolean cloudFlagNadirCloudy = cfAatsrNadirTile.getSampleBit(iTarX, iTarY, AATSR_L1_CF_CLOUDY);
                        final boolean cloudFlagNadirSunglint = cfAatsrNadirTile.getSampleBit(iTarX, iTarY, AATSR_L1_CF_SUNGLINT);
                        final float aatsrViewElevationNadir = getAvePixel(veAatsrNadirTile, iTarX, iTarY);
                        final float aatsrSunElevationNadir = getAvePixel(seAatsrNadirTile, iTarX, iTarY);
                        final float aatsrBt37 = getAvePixel(aatsrBTNadir0370Tile, iTarX, iTarY);
                        if (isInvalid.getSampleBoolean(iX, iY)
                                || !GlintPreparation.isUsefulPixel(cloudFlagNadirLand, cloudFlagNadirCloudy, cloudFlagNadirSunglint, aatsrViewElevationNadir, aatsrBt37))
                        {
                            targetTile.setSample(iX, iY, SynergyConstants.OUTPUT_GLINT_BAND_NODATAVALUE);
                        } else {

                            // 1. The solar part of 3.7
                            // 1.a. Thermal extrapolation of 11/12 to 3.7
                            final float aatsrBTThermalPart37 =
                                    solarPart37.extrapolateTo37(getAvePixel(aatsrBTNadir1100Tile, iTarX, iTarY), getAvePixel(aatsrBTNadir1200Tile, iTarX, iTarY));

                            // 1.b.1 Calculation of water vapour
                            final float zonalWind = getAvePixel(zonalWindTile, iTarX, iTarY);
                            final float meridWind = getAvePixel(meridWindTile, iTarX, iTarY);
                            float merisViewAzimuth = getAvePixel(vaMerisTileComplete, iTarX, iTarY);
                            float merisSunAzimuth = getAvePixel(saMerisTile, iTarX, iTarY);
                            float merisAzimuthDifference = GlintPreparation.removeAzimuthDifferenceAmbiguity(merisViewAzimuth,
                                                                                                        merisSunAzimuth);
                            final float merisViewZenith = getAvePixel(vzMerisTile, iTarX, iTarY);
                            final float merisSunZenith = getAvePixel(szMerisTile, iTarX, iTarY);
                            final float merisRad14 = getAvePixel(merisRad14Tile, iTarX, iTarY);
                            final float merisRad15 = getAvePixel(merisRad15Tile, iTarX, iTarY);

                            // 1.b.2 Calculation of transmission
//                                                                                       90.0f - aatsrSunElevationNadir, 90.0f - aatsrViewElevationNadir);
                            final float[] aatsrTrans37Info = solarPart37.computeTransmission(merisRad14, merisRad15);

                            // 1.c Conversion of BT to normalized radiance
                            final float aatsrRad37 = solarPart37.convertBT2Radiance(aatsrBt37) / solarIrradiance37;
                            final float aatsrRadianceThermalPart37 = solarPart37.convertBT2Radiance(aatsrBTThermalPart37) / solarIrradiance37;

                            // 1.d Compute the solar part
                            final float[] aatsrSolarPart37 = solarPart37.computeSolarPart(aatsrRad37, aatsrRadianceThermalPart37, aatsrTrans37Info);

                            // 2. The geometrical conversion
                            // 2.a AATSR - MERIS conversion
                            final float aatsrViewAzimuthNadir = getAvePixel(vaAatsrNadirTileComplete, iTarX, iTarY);
                            final float aatsrSunAzimuthNadir = getAvePixel(saAatsrNadirTile, iTarX, iTarY);

                            final float aatsrAzimuthDifferenceNadir = GlintPreparation.removeAzimuthDifferenceAmbiguity(aatsrViewAzimuthNadir,
                                                                                                             aatsrSunAzimuthNadir);

                            final float[][] merisNormalizedRadianceResultMatrix =
                                    glintRetrieval.convertAatsrRad37ToMerisRad(aatsrSolarPart37, merisSunZenith,
                                                                               merisViewZenith, 180.0f - aatsrAzimuthDifferenceNadir, 180.0f - merisAzimuthDifference);

                            // 2.b Ambiuguity reduction and final output
                            if (glintRetrieval.windspeedFound(merisNormalizedRadianceResultMatrix) > 0) {
                                final float[] finalResultWindspeedRadiance = glintRetrieval.getAmbiguityReducedRadiance
                                        (merisNormalizedRadianceResultMatrix, zonalWind, meridWind);

                                for (int i = 0; i < 2; i++) {
                                    windspeed[i] = merisNormalizedRadianceResultMatrix[i][0];
                                }

                                // these are the final results:
                                
                                // windspeed:
                                synergyWindspeed[iX][iY] = finalResultWindspeedRadiance[0];
                                // glme13
                                setGlintResult(1, iX, iY, merisAzimuthDifference, merisViewZenith, merisSunZenith);
                                // glaatsr87_nadir
                                setGlintResult(4, iX, iY, aatsrAzimuthDifferenceNadir, 90.0f - aatsrViewElevationNadir, 90.0f - aatsrSunElevationNadir);
                            } else {
                                synergyWindspeed[iX][iY] = SynergyConstants.OUTPUT_GLINT_BAND_NODATAVALUE;
                                if (targetBandIndex != -1) {
                                    synergyGlint[targetBandIndex][iX][iY] = SynergyConstants.OUTPUT_GLINT_BAND_NODATAVALUE;
                                }
                            }
                        }
                    }
                    writeResultToTile(targetBand, targetTile, targetBandIndex, iY, iX);
                }
                pm.worked(1);
            }
        } catch (Exception e) {
            throw new OperatorException("Failed to process Glint algorithm:\n" + e.getMessage(), e);
        } finally {
            pm.done();
        }
    }

    private void writeResultToTile(Band targetBand, Tile targetTile, int targetBandIndex, int y, int x) {
        if (targetBand.getName().equals(RESULT_WINDSPEED_NAME)) {
            targetTile.setSample(x, y, synergyWindspeed[x][y]);
        }
        else if (targetBandIndex != -1) {
            targetTile.setSample(x, y, synergyGlint[targetBandIndex][x][y]);
        }
    }

    private int getTargetBandIndex(Band targetBand) {
        int index = -1;
        // glme12
        if (targetBand.getName().equals(RESULT_GLINT_SYNERGY_MERIS12_NAME)) {
            index = 0;
        }
        // glme13
        if (targetBand.getName().equals(RESULT_GLINT_SYNERGY_MERIS13_NAME)) {
            index = 1;
        }
        // glme14
        if (targetBand.getName().equals(RESULT_GLINT_SYNERGY_MERIS14_NAME)) {
            index = 2;
        }
        // glaatsr16_nadir
        if (targetBand.getName().equals(RESULT_GLINT_SYNERGY_AATSR_16_NADIR_NAME)) {
            index = 3;
        }
        // glaatsr87_nadir
        if (targetBand.getName().equals(RESULT_GLINT_SYNERGY_AATSR_87_NADIR_NAME)) {
            index = 4;
        }
        // glaatsr16_fward
        if (targetBand.getName().equals(RESULT_GLINT_SYNERGY_AATSR_16_FWARD_NAME)) {
            index = 5;
        }
        // glaatsr87_fward
        if (targetBand.getName().equals(RESULT_GLINT_SYNERGY_AATSR_87_FWARD_NAME)) {
            index = 6;
        }
        return index;
    }

    private float getAvePixel(Tile inputTile, int iTarX, int iTarY) {

        double value = 0;
        double noDataValue = 0;
        int n = 0;

        final int minX = Math.max(0, iTarX - aveBlock);
        final int minY = Math.max(0, iTarY - aveBlock);
        final int maxX = Math.min(synergyProduct.getSceneRasterWidth() - 1, iTarX + aveBlock);
        final int maxY = Math.min(synergyProduct.getSceneRasterHeight() - 1, iTarY + aveBlock);

        for (int iy = minY; iy <= maxY; iy++) {
            for (int ix = minX; ix <= maxX; ix++) {
                double val = inputTile.getSampleDouble(ix, iy);
                noDataValue = inputTile.getRasterDataNode().getNoDataValue();
                boolean valid = (Double.compare(val, noDataValue) != 0);
                if (valid) {
                    n++;
                    value += val;
                }
            }
        }
        if (!(n < minNAve)) {
            value /= n;
        } else {
            value = noDataValue;
        }

        return (float) value;
    }

    private void setGlintResult(int index, int x, int y, float azimuthDifference, float viewZenith, float sunZenith) {
        synergyGlint[index][x][y] = GlintRetrieval.calcGlintAnalytical(sunZenith, viewZenith, 180.0f - azimuthDifference, SynergyConstants.refractiveIndex[index],
                         (float) synergyWindspeed[x][y], SynergyConstants.rhoFoam[index]);
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(GlintAveOp.class);
        }
    }
}