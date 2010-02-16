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
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;

/**
 * Operator for FUB Glint processing for Synergy Ocean Aerosol Retrieval
 * (modified 'Glint' algorithm).
 *
 * CURRENTLY NOT USED - GlintAveOp instead!
 *
 * @author Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 18:54:34 +0100 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.Glint",
                  version = "1.0-SNAPSHOT",
                  authors = "Olaf Danne",
                  copyright = "(c) 2009 by Brockmann Consult",
                  description = "Glint Processor for Synergy Ocean Aerosol Retrieval.")
public class GlintOp extends Operator {
    @SourceProduct(alias = "l1bSynergy",
                   description = "MERIS/AATSR synergy product.")
    private Product synergyProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(alias = RetrieveAerosolConstants.LUT_PATH_PARAM_NAME,
               defaultValue = RetrieveAerosolConstants.LUT_OCEAN_PATH_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.LUT_PATH_PARAM_DESCRIPTION,
               label = RetrieveAerosolConstants.LUT_OCEAN_PATH_PARAM_LABEL)
    private String lutPath;

    private static final String INVALID_EXPRESSION = "l1_flags" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_MERIS + ".INVALID";
    private Band invalidBand;

    /* AATSR L1 Cloud Flags (just the ones needed) */
    final int AATSR_L1_CF_LAND = 0;
    final int AATSR_L1_CF_CLOUDY = 1;
    final int AATSR_L1_CF_SUNGLINT = 2;

    public static final String CONFID_NADIR_FLAGS = "confid_flags_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + "";
    public static final String CONFID_FWARD_FLAGS = "confid_flags_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + "";
    public static final String CLOUD_NADIR_FLAGS = "cloud_flags_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + "";
    public static final String CLOUD_FWARD_FLAGS = "cloud_flags_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + "";

    // final results
    public static final String RESULT_WINDSPEED_1_NAME = "windspeed_1";
    public static final String RESULT_WINDSPEED_2_NAME = "windspeed_2";
    public static final String RESULT_GLINT_FLINT_NAME = "glint_flint";
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

    private double[][] windspeed1;
//    private double[][] windspeed2;
    private double[][][] synergyGlint;
    private float[] refractiveIndex;


    public void initialize() throws OperatorException {

        solarPart37 = new GlintSolarPart37();
        glintRetrieval = new GlintRetrieval();


        refractiveIndex = new float[]{1.33f, 1.3295f, 1.329f, 1.31f, 1.3295f, 1.31f, 1.3295f};

        try {
            solarPart37.loadGlintAuxData();
            glintRetrieval.loadGaussParsLut(lutPath);
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
        vaAatsrNadirTileComplete = getSourceTile(synergyProduct.getBand("view_azimuth_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rect, null);

        // correct azimuths in these tiles for later usage...
        GlintPreparation.correctViewAzimuthLinear(vaMerisTileComplete, rect);
        GlintPreparation.correctViewAzimuthLinear(vaAatsrNadirTileComplete, rect);

        windspeed1 = new double[sceneWidth][sceneHeight];
//        windspeed2 = new double[sceneWidth][sceneHeight];

        synergyGlint = new double[7][sceneWidth][sceneHeight];

        for (int j = 0; j < sceneWidth; j++) {
            for (int k = 0; k < sceneHeight; k++) {
                windspeed1[j][k] = -1.0;
//                windspeed2[j][k] = -1.0;
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
        String productType = synergyProduct.getProductType();
        String productName = synergyProduct.getName();
        int sceneWidth = synergyProduct.getSceneRasterWidth();
        int sceneHeight = synergyProduct.getSceneRasterHeight();

        targetProduct = new Product(productName, productType, sceneWidth, sceneHeight);

        ProductUtils.copyTiePointGrids(synergyProduct, targetProduct);
        ProductUtils.copyGeoCoding(synergyProduct, targetProduct);
        ProductUtils.copyMetadata(synergyProduct, targetProduct);

        BandArithmeticOp bandArithmeticOp =
                BandArithmeticOp.createBooleanExpressionBand(INVALID_EXPRESSION, synergyProduct);
        invalidBand = bandArithmeticOp.getTargetProduct().getBandAt(0);

        setTargetBands();
    }

    private void setTargetBands() {
        Band resultWindspeed1Band = targetProduct.addBand(RESULT_WINDSPEED_1_NAME, ProductData.TYPE_FLOAT32);
        resultWindspeed1Band.setUnit("m/s");
//        Band resultWindspeed2Band = targetProduct.addBand(RESULT_WINDSPEED_2_NAME, ProductData.TYPE_FLOAT32);
//        resultWindspeed2Band.setUnit("m/s");

//        Band glintMer12Band = targetProduct.addBand(RESULT_GLINT_SYNERGY_MERIS12_NAME, ProductData.TYPE_FLOAT32);
//        glintMer12Band.setUnit("1/sr");
        Band glintMer13Band = targetProduct.addBand(RESULT_GLINT_SYNERGY_MERIS13_NAME, ProductData.TYPE_FLOAT32);
        glintMer13Band.setUnit("1/sr");
//        Band glintMer14Band = targetProduct.addBand(RESULT_GLINT_SYNERGY_MERIS14_NAME, ProductData.TYPE_FLOAT32);
//        glintMer14Band.setUnit("1/sr");
//        Band glintAatsr16NadirBand = targetProduct.addBand(RESULT_GLINT_SYNERGY_AATSR_16_NADIR_NAME, ProductData.TYPE_FLOAT32);
//        glintAatsr16NadirBand.setUnit("1/sr");
        Band glintAatsr87NadirBand = targetProduct.addBand(RESULT_GLINT_SYNERGY_AATSR_87_NADIR_NAME, ProductData.TYPE_FLOAT32);
        glintAatsr87NadirBand.setUnit("1/sr");
//        Band glintAatsr16FwardBand = targetProduct.addBand(RESULT_GLINT_SYNERGY_AATSR_16_FWARD_NAME, ProductData.TYPE_FLOAT32);
//        glintAatsr16FwardBand.setUnit("1/sr");
//        Band glintAatsr87FwardBand = targetProduct.addBand(RESULT_GLINT_SYNERGY_AATSR_87_FWARD_NAME, ProductData.TYPE_FLOAT32);
//        glintAatsr87FwardBand.setUnit("1/sr");
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        Rectangle rectangle = targetTile.getRectangle();

        if (targetBand.isFlagBand()) {
            // no computations
            return;
        }

        pm.beginTask("Processing frame...", rectangle.height);

        try {
            Tile szMerisTile = getSourceTile(synergyProduct.getTiePointGrid("sun_zenith"), rectangle, pm);
            Tile vzMerisTile = getSourceTile(synergyProduct.getTiePointGrid("view_zenith"), rectangle, pm);
            Tile saMerisTile = getSourceTile(synergyProduct.getTiePointGrid("sun_azimuth"), rectangle, pm);
            Tile zonalWindTile = getSourceTile(synergyProduct.getTiePointGrid("zonal_wind"), rectangle, pm);
            Tile meridWindTile = getSourceTile(synergyProduct.getTiePointGrid("merid_wind"), rectangle, pm);

            Tile seAatsrNadirTile = getSourceTile(synergyProduct.getBand("sun_elev_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);
            Tile veAatsrNadirTile = getSourceTile(synergyProduct.getBand("view_elev_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);
            Tile saAatsrNadirTile = getSourceTile(synergyProduct.getBand("sun_azimuth_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);
            Tile seAatsrFwardTile = getSourceTile(synergyProduct.getBand("sun_elev_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);
            Tile veAatsrFwardTile = getSourceTile(synergyProduct.getBand("view_elev_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);
            Tile saAatsrFwardTile = getSourceTile(synergyProduct.getBand("sun_azimuth_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);
            Tile vaAatsrFwardTile = getSourceTile(synergyProduct.getBand("view_azimuth_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);

            Tile cfAatsrNadirTile = getSourceTile(synergyProduct.getBand("cloud_flags_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);

            Tile merisRad14Tile = getSourceTile(synergyProduct.getBand("radiance_14" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_MERIS + ""), rectangle, pm);
            Tile merisRad15Tile = getSourceTile(synergyProduct.getBand("radiance_15" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_MERIS + ""), rectangle, pm);
            Tile aatsrBTNadir0370Tile = getSourceTile(synergyProduct.getBand("btemp_nadir_0370" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);
            Tile aatsrBTNadir1100Tile = getSourceTile(synergyProduct.getBand("btemp_nadir_1100" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);
            Tile aatsrBTNadir1200Tile = getSourceTile(synergyProduct.getBand("btemp_nadir_1200" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rectangle, pm);

            Tile isInvalid = getSourceTile(invalidBand, rectangle, pm);

            final float[] windspeed = new float[2];

            int targetBandIndex = getTargetBandIndex(targetBand);

            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                    if (pm.isCanceled()) {
                        break;
                    }

                    if ((targetBandIndex != -1 && synergyGlint[targetBandIndex][x][y] == -1.0) ||
                            windspeed1[x][y] == -1.0) {
//                            || windspeed2[x][y] == -1.0) {

                        final boolean cloudFlagNadirLand = cfAatsrNadirTile.getSampleBit(x, y, AATSR_L1_CF_LAND);
                        final boolean cloudFlagNadirCloudy = cfAatsrNadirTile.getSampleBit(x, y, AATSR_L1_CF_CLOUDY);
                        final boolean cloudFlagNadirSunglint = cfAatsrNadirTile.getSampleBit(x, y, AATSR_L1_CF_SUNGLINT);
                        final float aatsrViewElevationNadir = veAatsrNadirTile.getSampleFloat(x, y);
                        final float aatsrSunElevationNadir = seAatsrNadirTile.getSampleFloat(x, y);
                        final float aatsrViewElevationFward = veAatsrFwardTile.getSampleFloat(x, y);
                        final float aatsrSunElevationFward = seAatsrFwardTile.getSampleFloat(x, y);
                        final float aatsrBt37 = aatsrBTNadir0370Tile.getSampleFloat(x, y);
                        if (isInvalid.getSampleBoolean(x, y)
                                || !GlintPreparation.isUsefulPixel(cloudFlagNadirLand, cloudFlagNadirCloudy, cloudFlagNadirSunglint, aatsrViewElevationNadir, aatsrBt37))
//                                                    || (x < 500 || x > 600)
//                                                    || (y < 500 || y > 700))    // test!!
                        {
                            targetTile.setSample(x, y, RetrieveAerosolConstants.OUTPUT_GLINT_BAND_NODATAVALUE);
                        } else {

                            // 1. The solar part of 3.7
                            // 1.a. Thermal extrapolation of 11/12 to 3.7
                            final float aatsrBTThermalPart37 =
                                    solarPart37.extrapolateTo37(aatsrBTNadir1100Tile.getSampleFloat(x, y), aatsrBTNadir1200Tile.getSampleFloat(x, y));

                            // 1.b.1 Calculation of water vapour
                            final float zonalWind = zonalWindTile.getSampleFloat(x, y);
                            final float meridWind = meridWindTile.getSampleFloat(x, y);
                            float merisViewAzimuth = vaMerisTileComplete.getSampleFloat(x, y);
                            float merisSunAzimuth = saMerisTile.getSampleFloat(x, y);
                            float merisAzimuthDifference = GlintPreparation.removeAzimuthDifferenceAmbiguity(merisViewAzimuth,
                                                                                                        merisSunAzimuth);
                            final float merisViewZenith = vzMerisTile.getSampleFloat(x, y);
                            final float merisSunZenith = szMerisTile.getSampleFloat(x, y);
                            final float merisRad14 = merisRad14Tile.getSampleFloat(x, y);
                            final float merisRad15 = merisRad15Tile.getSampleFloat(x, y);

                            float waterVapourColumn = solarPart37.computeWaterVapour(zonalWind, meridWind, merisAzimuthDifference,
                                                                                     merisViewZenith, merisSunZenith, merisRad14, merisRad15);

                            // 1.b.2 Calculation of transmission
                            final float aatsrTrans37 = solarPart37.computeTransmissionOld(37, waterVapourColumn,
                                                                                       90.0f - aatsrSunElevationNadir, 90.0f - aatsrViewElevationNadir);
                            final float aatsrTrans16 = solarPart37.computeTransmissionOld(16, waterVapourColumn,
                                                                                       90.0f - aatsrSunElevationNadir, 90.0f - aatsrViewElevationNadir);

                            // 1.c Conversion of BT to normalized radiance
                            final float aatsrRad37 = solarPart37.convertBT2Radiance(aatsrBt37) / solarIrradiance37;
                            final float aatsrRadianceThermalPart37 = solarPart37.convertBT2Radiance(aatsrBTThermalPart37) / solarIrradiance37;

                            // 1.d Compute the solar part
                            final float aatsrSolarPart37 =
                                    solarPart37.computeSolarPartOld(aatsrRad37, aatsrRadianceThermalPart37, aatsrTrans37);
                            final float aatsrSolarPart37a =
                                    solarPart37.convertToAatsrUnits(aatsrSolarPart37, aatsrSunElevationNadir);

                            // 2. The geometrical conversion
                            // 2.a AATSR - MERIS conversion
                            float aatsrViewAzimuthNadir = vaAatsrNadirTileComplete.getSampleFloat(x, y);
                            float aatsrSunAzimuthNadir = saAatsrNadirTile.getSampleFloat(x, y);
                            float aatsrViewAzimuthFward = vaAatsrFwardTile.getSampleFloat(x, y);
                            float aatsrSunAzimuthFward = saAatsrFwardTile.getSampleFloat(x, y);

                            float aatsrAzimuthDifferenceNadir = GlintPreparation.removeAzimuthDifferenceAmbiguity(aatsrViewAzimuthNadir,
                                                                                                             aatsrSunAzimuthNadir);
                            float aatsrAzimuthDifferenceFward = GlintPreparation.removeAzimuthDifferenceAmbiguity(aatsrViewAzimuthFward,
                                                                                                             aatsrSunAzimuthFward);

                            final float[][] merisNormalizedRadianceResultMatrix =
                                    glintRetrieval.convertAatsrRad37ToMerisRadOld(aatsrSolarPart37, merisSunZenith,
                                                                               merisViewZenith, 180.0f - aatsrAzimuthDifferenceNadir, 180.0f - merisAzimuthDifference);

                            // 2.b Ambiuguity reduction and final output
                            if (glintRetrieval.windspeedFound(merisNormalizedRadianceResultMatrix) > 0) {
                                final float[] finalResultWindspeedRadiance = glintRetrieval.getAmbiguityReducedRadiance
                                        (merisNormalizedRadianceResultMatrix, zonalWind, meridWind);

                                for (int i = 0; i < 2; i++) {
                                    windspeed[i] = merisNormalizedRadianceResultMatrix[i][0];
                                }

                                // these are the final results
//                                windspeed1[x][y] = windspeed[0];
                                windspeed1[x][y] = finalResultWindspeedRadiance[0];
//                                windspeed2[x][y] = windspeed[1];

                                // glme12
//                                setGlintResult(targetTile, 0, windspeed, x, y, merisAzimuthDifference, merisViewZenith, merisSunZenith);
                                // glme13
                                setGlintResult(1, x, y, merisAzimuthDifference, merisViewZenith, merisSunZenith);
                                // glme14
//                                setGlintResult(targetTile, 2, windspeed, x, y, merisAzimuthDifference, merisViewZenith, merisSunZenith);
                                // glaatsr16_nadir
//                                setGlintResult(targetTile, 3, windspeed, x, y, aatsrAzimuthDifferenceNadir, 90.0f - aatsrViewElevationNadir, 90.0f - aatsrSunElevationNadir);
                                // glaatsr87_nadir
                                setGlintResult(4, x, y, aatsrAzimuthDifferenceNadir, 90.0f - aatsrViewElevationNadir, 90.0f - aatsrSunElevationNadir);
                                // glaatsr16_fward
//                                setGlintResult(targetTile, 5, windspeed, x, y, aatsrAzimuthDifferenceFward, 90.0f - aatsrViewElevationFward, 90.0f - aatsrSunElevationFward);
                                // glaatsr87_fward
//                                setGlintResult(targetTile, 6, windspeed, x, y, 180.0f - aatsrAzimuthDifferenceFward, 90.0f - aatsrViewElevationFward, 90.0f - aatsrSunElevationFward);
                            } else {
                                windspeed1[x][y] = RetrieveAerosolConstants.OUTPUT_GLINT_BAND_NODATAVALUE;
                                if (targetBandIndex != -1) {
                                    synergyGlint[targetBandIndex][x][y] = RetrieveAerosolConstants.OUTPUT_GLINT_BAND_NODATAVALUE;
                                }
                            }
                        }
                    }
                    writeResultToTile(targetBand, targetTile, targetBandIndex, y, x);
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
        if (targetBand.getName().equals(RESULT_WINDSPEED_1_NAME)) {
            targetTile.setSample(x, y, windspeed1[x][y]);
        }
//        else if (targetBand.getName().equals(RESULT_WINDSPEED_2_NAME)) {
//            targetTile.setSample(x, y, windspeed2[x][y]);
//        } 
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

//    private void setGlintResult(Tile targetTile, int index, float[] windspeed, int x, int y, float azimuthDifference, float viewZenith, float sunZenith) {
//        double[] glint = new double[2];
//        for (int i = 0; i < 2; i++) {
//            if (i == 0 || (i == 1 && Math.abs(windspeed[1] - windspeed[0]) < 0.1)) {
//                glint[i] = glintRetrieval.computeGlintFromLUT
//                        (sunZenith, viewZenith, 180.0f - azimuthDifference, refractiveIndex[index],
//                         windspeed[i]);
//            } else {
//                glint[1] = glint[0];
//            }
//        }
//
//        synergyGlint[index][x][y] = 0.5*(glint[0] + glint[1]);
//    }

    private void setGlintResult(int index, int x, int y, float azimuthDifference, float viewZenith, float sunZenith) {
        synergyGlint[index][x][y] = glintRetrieval.computeGlintFromLUT
                        (sunZenith, viewZenith, 180.0f - azimuthDifference, refractiveIndex[index],
                         (float) windspeed1[x][y]);
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(GlintOp.class);
        }
    }
}
