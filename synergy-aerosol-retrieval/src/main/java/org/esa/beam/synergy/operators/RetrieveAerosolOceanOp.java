package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.synergy.util.AerosolHelpers;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.LookupTable;
import org.esa.beam.util.math.MathUtils;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator for Aerosol retrieval over ocean within MERIS/AATSR Synergy project.
 *
 * @author Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 18:54:34 +0100 (Mi, 27 Jan 2010) $
 */
@OperatorMetadata(alias = "synergy.RetrieveAerosolOcean",
                  version = "1.0-SNAPSHOT",
                  authors = "Olaf Danne",
                  copyright = "(c) 2009 by Brockmann Consult",
                  description = "Retrieve Aerosol over Ocean.")
public class RetrieveAerosolOceanOp extends Operator {

    @SourceProduct(alias = "source",
                   label = "Name (Collocated MERIS AATSR product)",
                   description = "Select a collocated MERIS AATSR product.")
    private Product synergyProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(alias = RetrieveAerosolConstants.LUT_PATH_PARAM_NAME,
               defaultValue = RetrieveAerosolConstants.LUT_OCEAN_PATH_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.LUT_PATH_PARAM_DESCRIPTION,
               label = RetrieveAerosolConstants.LUT_OCEAN_PATH_PARAM_LABEL)
    private String lutPath;

    @Parameter(defaultValue = "11", label = "Pixels to average (n x n, with n odd number) for AOD retrieval", interval = "[1, 100]")
    private int aveBlock;

    @Parameter(defaultValue = "true", label = "Retrieve AODs over land")
    private boolean computeLand;

    public static final String RESULT_GLINT_NAME = "glint";

    private static final String INVALID_EXPRESSION = "l1_flags" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_MERIS + ".INVALID";
    private Band invalidBand;
    private float noDataVal;
    private int minNAve;

    private Tile vaMerisTileComplete;
    private Tile vaAatsrNadirTileComplete;

    private Product glintProduct;

    private AerosolAuxData.AerosolClassTable aerosolClassTable;
    private AerosolAuxData.AerosolModelTable aerosolModelTable;
    private LookupTable[][] aerosolLookupTables;

    private float[] wvl;
    private float[] wvlWeight;
    private int[] wvlIndex;

    private double[][][] interpol5DResultLow;     // 'biglut' in breadboard  [nmod, nwvl, ntauLut]
    private double[][][] interpol5DResultHigh;     // 'minilut' in breadboard  [nmod, nwvl, ntau]
    private float[][][] interpolAngResult;    // tlut in breadboard     [nwvl, ntau, nang]
    private float[][][] costFunction;         // cost in breadboard     [nwvl, ntau, nang]

    private float[][] aot550Result;
    private float[][] angResult;
    private float[][] aot550ErrorResult;
    private float[][] angErrorResult;
    private float[][] glintResult;
    private float[][] wsResult;

    private static int nTau = 201;
//    private static int nTau = 51;
    private static int nAng = 91;
    private int nMod;
    private int nWvl;
    private int nTauLut;
    private double[] vectorTauLut;
    private double[] vectorTauLutHigh;
    private AerosolHelpers.AngstroemParameters[] angstroemParameters;
    private float scalingFactor;


    public void initialize() throws OperatorException {
        System.out.println("starting...");

        noDataVal = (float) RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE;

        // get the glint product...
        Map<String, Product> glintInput = new HashMap<String, Product>(3);
        glintInput.put("l1bSynergy", synergyProduct);
        Map<String, Object> glintAveParams = new HashMap<String, Object>(2);
        glintAveParams.put("lutPath", lutPath);
        glintAveParams.put("aveBlock", aveBlock);
        glintProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(GlintAveOp.class), glintAveParams, glintInput);

        scalingFactor = aveBlock;
        aveBlock /= 2;
        minNAve = (int) (scalingFactor*scalingFactor - 1);
        noDataVal = (float) RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE;

        createTargetProduct();
//        targetProduct = glintProduct;       // test

        // correction of azimuth discontinuity:
        // set up tiles for MERIS and AATSR which cover the whole scene...
        int sceneWidth = synergyProduct.getSceneRasterWidth();
        int sceneHeight = synergyProduct.getSceneRasterHeight();
        Rectangle rect = new Rectangle(0, 0, sceneWidth, sceneHeight);
        vaMerisTileComplete = getSourceTile(synergyProduct.getTiePointGrid("view_azimuth"), rect, null);
        vaAatsrNadirTileComplete = getSourceTile(synergyProduct.getBand("view_azimuth_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), rect, null);

        aot550Result = new float[sceneWidth][sceneHeight];
        angResult = new float[sceneWidth][sceneHeight];
        aot550ErrorResult = new float[sceneWidth][sceneHeight];
        angErrorResult = new float[sceneWidth][sceneHeight];
        glintResult = new float[sceneWidth][sceneHeight];
        wsResult = new float[sceneWidth][sceneHeight];

        // read aerosol class table
        try {
            aerosolClassTable = AerosolAuxData.getInstance().createAerosolClassTable();
        } catch (IOException e) {
            throw new OperatorException("Failed to read aerosol class table:\n" + e.getMessage(), e);
        }

        // read aerosol models
        try {
            aerosolModelTable = AerosolAuxData.getInstance().createAerosolModelTable(lutPath);
        } catch (IOException e) {
            throw new OperatorException("Failed to read aerosol class table:\n" + e.getMessage(), e);
        }

//        wvl=[ 865,  885,1610,   885,1610,   885]
        wvl = new float[]{865.0f, 885.0f, 1610.0f, 885.0f, 1610.0f, 885.0f};
        wvlWeight = new float[]{1.0f, 1.0f, 3.0f, 1.0f, 3.0f, 3.0f};
        wvlIndex = new int[]{0, 3, 5};
        // at this point, just use 1 MERIS and 1 AATSR channel...
        // todo: clarify with RP which we should finally use

        // find model indices belonging to aerosol classes...
        final List<Integer> modelIndices = aerosolModelTable.getMaritimeAndDesertIndices();
        nMod = modelIndices.size();
        nWvl = wvlIndex.length;

        try {
            aerosolLookupTables = AerosolAuxData.getInstance().createAerosolOceanLookupTables(lutPath, modelIndices, wvl, wvlIndex);
        } catch (IOException e) {
            throw new OperatorException("Failed to create aerosol lookup tables:\n" + e.getMessage(), e);
        }
        nTauLut = aerosolLookupTables[0][0].getDimensions()[4].getSequence().length;

        interpol5DResultLow = new double[nMod][nWvl][nTauLut];
        interpol5DResultHigh = new double[nMod][nWvl][nTau];
        interpolAngResult = new float[nWvl][nTau][nAng];
        costFunction = new float[nWvl][nTau][nAng];

        vectorTauLut = new double[nTauLut];
        for (int i=0; i<nTauLut; i++) {
            vectorTauLut[i] = i*2.0/(nTauLut-1);
        }
        vectorTauLutHigh = new double[nTau];
        for (int i=0; i<nTau; i++) {
            vectorTauLutHigh[i] = i*2.0/(nTau-1);
        }

        float[] angArray = aerosolModelTable.getAngArray(modelIndices, 0);
        angstroemParameters = AerosolHelpers.getInstance().getAngstroemParameters(angArray, nAng);


        // read corresponding small LUTs and make a big LUT...

        // correct azimuths in these tiles for later usage...
        GlintPreparation.correctViewAzimuthLinear(vaMerisTileComplete, rect);
        GlintPreparation.correctViewAzimuthLinear(vaAatsrNadirTileComplete, rect);

    }

    /**
     * This method creates the target product
     */
    private void createTargetProduct() {

        String productType = synergyProduct.getProductType();
        String productName = synergyProduct.getName();
        int sceneWidth = synergyProduct.getSceneRasterWidth();
        int sceneHeight = synergyProduct.getSceneRasterHeight();

        int downscaledRasterWidth = (int) (Math.ceil((float) (sceneWidth / scalingFactor) - 0.5));
        int downscaledRasterHeight = (int) (Math.ceil((float) (sceneHeight/ scalingFactor) - 0.5));

        targetProduct = new Product(productName, productType, downscaledRasterWidth, downscaledRasterHeight);
        targetProduct.setPreferredTileSize(128, 128);

        ProductUtils.copyGeoCoding(synergyProduct, targetProduct);
        ProductUtils.copyMetadata(synergyProduct, targetProduct);
        AerosolHelpers.copyDownscaledTiePointGrids(synergyProduct, targetProduct, scalingFactor);
        AerosolHelpers.copyDownscaledFlagBands(synergyProduct, targetProduct, scalingFactor);

        BandArithmeticOp bandArithmeticOp =
                BandArithmeticOp.createBooleanExpressionBand(INVALID_EXPRESSION, synergyProduct);
        invalidBand = bandArithmeticOp.getTargetProduct().getBandAt(0);

        setTargetBands();
    }

    private void setTargetBands() {
        Band aot550Band = targetProduct.addBand(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME, ProductData.TYPE_FLOAT32);
        aot550Band.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
        aot550Band.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE_USED);
        aot550Band.setUnit("dl");
        Band aot550ErrBand = targetProduct.addBand(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME, ProductData.TYPE_FLOAT32);
        aot550ErrBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
        aot550ErrBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE_USED);
        aot550ErrBand.setUnit("dl");

        if (!computeLand) {
            // write more bands specific for ocean retrieval
            Band angBand = targetProduct.addBand(RetrieveAerosolConstants.OUTPUT_ANG_BAND_NAME, ProductData.TYPE_FLOAT32);
            angBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_ANG_BAND_NODATAVALUE);
            angBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_ANG_BAND_NODATAVALUE_USED);
            Band angErrBand = targetProduct.addBand(RetrieveAerosolConstants.OUTPUT_ANGERR_BAND_NAME, ProductData.TYPE_FLOAT32);
            angErrBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_ANG_BAND_NODATAVALUE);
            angErrBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_ANG_BAND_NODATAVALUE_USED);

            Band glintBand = targetProduct.addBand(RetrieveAerosolConstants.OUTPUT_GLINT_BAND_NAME, ProductData.TYPE_FLOAT32);
            glintBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_GLINT_BAND_NODATAVALUE);
            glintBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_GLINT_BAND_NODATAVALUE_USED);
            Band wsBand = targetProduct.addBand(RetrieveAerosolConstants.OUTPUT_WS_BAND_NAME, ProductData.TYPE_FLOAT32);
            wsBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_WS_BAND_NODATAVALUE);
            wsBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_WS_BAND_NODATAVALUE_USED);
        }
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        if (targetBand.isFlagBand()) {
            // no computations
            return;
        }

        Rectangle rectangle = targetTile.getRectangle();
        int bigWidth = (int) (scalingFactor*rectangle.getWidth());
        int bigHeight = (int) (scalingFactor*rectangle.getHeight());
        int bigX = (int) (scalingFactor*rectangle.getX());
        int bigY = (int) (scalingFactor*rectangle.getY());
        Rectangle big = new Rectangle(bigX, bigY, bigWidth, bigHeight);

        pm.beginTask("Processing frame...", rectangle.height);

        try {
            // todo: clean up the tiles which are not finally needed  (depends on how many channels are used)
            Tile szMerisTile = getSourceTile(synergyProduct.getTiePointGrid("sun_zenith"), big, pm);
            Tile vzMerisTile = getSourceTile(synergyProduct.getTiePointGrid("view_zenith"), big, pm);
            Tile saMerisTile = getSourceTile(synergyProduct.getTiePointGrid("sun_azimuth"), big, pm);
            Tile pressureTile = getSourceTile(synergyProduct.getTiePointGrid("atm_press"), big, pm);

            Tile seAatsrNadirTile = getSourceTile(synergyProduct.getBand("sun_elev_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            Tile veAatsrNadirTile = getSourceTile(synergyProduct.getBand("view_elev_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            Tile saAatsrNadirTile = getSourceTile(synergyProduct.getBand("sun_azimuth_nadir" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            Tile seAatsrFwardTile = getSourceTile(synergyProduct.getBand("sun_elev_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            Tile veAatsrFwardTile = getSourceTile(synergyProduct.getBand("view_elev_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            Tile saAatsrFwardTile = getSourceTile(synergyProduct.getBand("sun_azimuth_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            Tile vaAatsrFwardTile = getSourceTile(synergyProduct.getBand("view_azimuth_fward" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + ""), big, pm);
            Tile merisRad13Tile = getSourceTile(synergyProduct.getBand("radiance_13" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_MERIS + ""), big, pm);
            Tile merisRad14Tile = getSourceTile(synergyProduct.getBand("radiance_14" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_MERIS + ""), big, pm);

            final Band reflecNadir16Band = synergyProduct.getBand("reflec_nadir_1600" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + "");
            Tile aatsrReflNadir1600Tile = getSourceTile(reflecNadir16Band, big, pm);
            final Band reflecNadir87Band = synergyProduct.getBand("reflec_nadir_0870" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + "");
            Tile aatsrReflNadir0870Tile = getSourceTile(reflecNadir87Band, big, pm);
            final Band reflecFward16Band = synergyProduct.getBand("reflec_fward_1600" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + "");
            Tile aatsrReflFward1600Tile = getSourceTile(reflecFward16Band, big, pm);
            final Band reflecFward87Band = synergyProduct.getBand("reflec_fward_0870" + "_" + RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR + "");
            Tile aatsrReflFward0870Tile = getSourceTile(reflecFward87Band, big, pm);

            Tile wsTile = getSourceTile(glintProduct.getBand(GlintAveOp.RESULT_WINDSPEED_NAME), rectangle, pm);

            // Flags tiles
            final Band merisCloudFlagsBand = synergyProduct.getBand(RetrieveAerosolConstants.CLOUD_FLAG_MERIS);
//            final Tile merisCloudFlagsTile = getSourceTile(merisCloudFlagsBand, big, pm);
            final Band aatsrCloudFlagsNadirBand = synergyProduct.getBand(RetrieveAerosolConstants.CLOUD_NADIR_FLAGS_AATSR);
//            final Tile aatsrCloudFlagsNadirTile = getSourceTile(aatsrCloudFlagsNadirBand, big, pm);
            final Band aatsrCloudFlagsFwardBand = synergyProduct.getBand(RetrieveAerosolConstants.CLOUD_FWARD_FLAGS_AATSR);
//            final Tile aatsrCloudFlagsFwardTile = getSourceTile(aatsrCloudFlagsFwardBand, big, pm);

            Tile isInvalid = getSourceTile(invalidBand, rectangle, pm);
            
            for (int iY = rectangle.y; iY < rectangle.y + rectangle.height; iY++) {
                for (int iX = rectangle.x; iX < rectangle.x + rectangle.width; iX++) {

                    int iTarX = (int) (scalingFactor*iX + aveBlock);
                    int iTarY = (int) (scalingFactor*iY + aveBlock);
//                    System.out.println("iTarX, iTarY, iX, iY: " +
//                         iTarX + "," + iTarY + "," + iX + "," + iY);
                    if (pm.isCanceled()) {
                        break;
                    }

//                    if (iX == 70 && iY == 60)
//                        System.out.println("halt!");

                    final float aatsrViewElevationNadir = getAvePixel(veAatsrNadirTile, iTarX, iTarY);
                    final float aatsrSunElevationNadir = getAvePixel(seAatsrNadirTile, iTarX, iTarY);
                    final float aatsrViewElevationFward = getAvePixel(veAatsrFwardTile, iTarX, iTarY);
                    final float aatsrSunElevationFward = getAvePixel(seAatsrFwardTile, iTarX, iTarY);

//                    int indexCloudfreeMeris = merisCloudFlagsBand.getFlagCoding().getFlagMask("cloudfree");
//                    final boolean isCloudfreeMeris = getAveFlag(merisCloudFlagsTile, iTarX, iTarX, indexCloudfreeMeris);
//                    int indexCloudyAatsrNadir = merisCloudFlagsBand.getFlagCoding().getFlagMask("CLOUDY");
//                    final boolean isCloudyAatsrNadir = getAveFlag(aatsrCloudFlagsNadirTile, iTarX, iTarX, indexCloudyAatsrNadir);

                     // just use one windspeed (the 'closer to ECMWF' one from Glint retrieval)
                     float ws = wsTile.getSampleFloat(iX, iY);

                    if (isInvalid.getSampleBoolean(iX, iY)
//                            || !isCloudfreeMeris || isCloudyAatsrNadir
                            || ws == RetrieveAerosolConstants.OUTPUT_WS_BAND_NODATAVALUE
                              ) {
                        targetTile.setSample(iX, iY, noDataVal);
                    } else if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME) && (aot550Result[iX][iY] > 0.0 ||
                            aot550Result[iX][iY] == RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE)) {
                        targetTile.setSample(iX, iY, aot550Result[iX][iY]);
                    } else if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_ANG_BAND_NAME) && (angResult[iX][iY] > 0.0 ||
                            angResult[iX][iY] == RetrieveAerosolConstants.OUTPUT_ANG_BAND_NODATAVALUE)) {
                        targetTile.setSample(iX, iY, angResult[iX][iY]);
                    } else if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME) && (aot550ErrorResult[iX][iY] > 0.0 ||
                            aot550ErrorResult[iX][iY] == RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NODATAVALUE)) {
                        targetTile.setSample(iX, iY, aot550ErrorResult[iX][iY]);
                    } else if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_ANGERR_BAND_NAME) && (angErrorResult[iX][iY] > 0.0 ||
                            angErrorResult[iX][iY] == RetrieveAerosolConstants.OUTPUT_ANGERR_BAND_NODATAVALUE)) {
                        targetTile.setSample(iX, iY, aot550ErrorResult[iX][iY]);
                    } else if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_GLINT_BAND_NAME) && (glintResult[iX][iY] > 0.0 ||
                            glintResult[iX][iY] == RetrieveAerosolConstants.OUTPUT_GLINT_BAND_NODATAVALUE)) {
                        targetTile.setSample(iX, iY, glintResult[iX][iY]);
                    } else if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_WS_BAND_NAME) && (wsResult[iX][iY] > 0.0 ||
                            wsResult[iX][iY] == RetrieveAerosolConstants.OUTPUT_WS_BAND_NODATAVALUE)) {
                        targetTile.setSample(iX, iY, wsResult[iX][iY]);
                    } else {
                        float merisViewAzimuth = getAvePixel(vaMerisTileComplete, iTarX, iTarY);
                        float merisSunAzimuth = getAvePixel(saMerisTile, iTarX, iTarY);
                        float merisAzimuthDifference = GlintPreparation.removeAzimuthDifferenceAmbiguity(merisViewAzimuth,
                                                                                                    merisSunAzimuth);
                        final float vzOrig = vzMerisTile.getSampleFloat(iTarX, iTarY);
                        final float merisViewZenith = getAvePixel(vzMerisTile, iTarX, iTarY);
                        float szOrig = szMerisTile.getSampleFloat(iTarX, iTarY);
                        final float merisSunZenith = getAvePixel(szMerisTile, iTarX, iTarY);
                        final float merisRad13 = getAvePixel(merisRad13Tile, iTarX, iTarY)/RetrieveAerosolConstants.MERIS_13_SOLAR_FLUX;
                        final float merisRad14 = getAvePixel(merisRad14Tile, iTarX, iTarY)/RetrieveAerosolConstants.MERIS_14_SOLAR_FLUX;
                        final double aatsrSeNadir = getAvePixel(seAatsrNadirTile, iTarX, iTarY);
                        final double aatsrSeFward = getAvePixel(seAatsrFwardTile, iTarX, iTarY);

                        // for RP test data (unit '%'), we need to divide AATSR reflectances by 100.
                        // however, the correct AATSR units should be 'dl', as for the Synergy products created
                        // in the Synergy module
                        float aatsrUnitCorrFactor = 1.0f;
                        if (reflecNadir87Band.getUnit().equals("%")) {
                            // check for one band should be enough
                            aatsrUnitCorrFactor = 100.0f;
                        }
                        final float aatsrReflNadir87 = (float) (getAvePixel(aatsrReflNadir0870Tile, iTarX, iTarY) /
                                (Math.PI* Math.cos(MathUtils.DTOR * (90.0 - aatsrSeNadir)) * aatsrUnitCorrFactor));
                        final float aatsrReflNadir16 = (float) (getAvePixel(aatsrReflNadir1600Tile, iTarX, iTarY) /
                                (Math.PI* Math.cos(MathUtils.DTOR * (90.0 - aatsrSeNadir)) * aatsrUnitCorrFactor));
                        final float aatsrReflFward87 = (float) (getAvePixel(aatsrReflFward0870Tile, iTarX, iTarY) /
                                (Math.PI* Math.cos(MathUtils.DTOR * (90.0 - aatsrSeFward)) * aatsrUnitCorrFactor));
                        final float aatsrReflFward16 = (float) (getAvePixel(aatsrReflFward1600Tile, iTarX, iTarY) /
                                (Math.PI* Math.cos(MathUtils.DTOR * (90.0 - aatsrSeFward)) * aatsrUnitCorrFactor));

                        float aatsrViewAzimuthNadir = getAvePixel(vaAatsrNadirTileComplete, iTarX, iTarY);
                        float aatsrSunAzimuthNadir = getAvePixel(saAatsrNadirTile, iTarX, iTarY);
                        float aatsrViewAzimuthFward = vaAatsrFwardTile.getSampleFloat(iTarX, iTarY);
                        float aatsrSunAzimuthFward = saAatsrFwardTile.getSampleFloat(iTarX, iTarY);

                        float aatsrAzimuthDifferenceNadir = GlintPreparation.removeAzimuthDifferenceAmbiguity(aatsrViewAzimuthNadir,
                                                                                                         aatsrSunAzimuthNadir);
                        float aatsrAzimuthDifferenceFward = aatsrViewAzimuthFward - aatsrSunAzimuthFward;
                        // negative pressures were stored in LUT to ensure ascending sequence
                        float surfacePressure = -1.0f*getAvePixel(pressureTile, iTarX, iTarY);

                        // breadboard begin STEP 1
                        float[] glintArray = doSynAOStep1(iY, iX,
                                    aatsrViewElevationNadir, aatsrViewElevationFward,
                                    aatsrSunElevationNadir, aatsrSunElevationFward,
                                    aatsrAzimuthDifferenceNadir, aatsrAzimuthDifferenceFward,
                                    merisViewZenith, merisSunZenith,
                                    merisAzimuthDifference,
                                    surfacePressure, ws);
                        glintResult[iX][iY] = glintArray[0];
                        wsResult[iX][iY] = ws;
                        // breadboard end STEP 1

                        // breadboard begin STEP 2
                        doSynAOStep2();
                        // breadboard end STEP 2

                        // breadboard begin STEP 3
                        doSynAOStep3(iY, iX, merisRad13, merisRad14,
                                     aatsrReflNadir16, aatsrReflNadir87, aatsrReflFward16, aatsrReflFward87);

                        // breadboard end STEP 3

                        if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME)) {
                            targetTile.setSample(iX, iY, aot550Result[iX][iY]);
                        }
                        if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_ANG_BAND_NAME)) {
                            targetTile.setSample(iX, iY, angResult[iX][iY]);
                        }
                        if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME)) {
                            targetTile.setSample(iX, iY, aot550ErrorResult[iX][iY]);
                        }
                        if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_ANGERR_BAND_NAME)) {
                            targetTile.setSample(iX, iY, angErrorResult[iX][iY]);
                        }
                        if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_GLINT_BAND_NAME)) {
                            targetTile.setSample(iX, iY, glintResult[iX][iY]);
                        }
                         if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_WS_BAND_NAME)) {
                            targetTile.setSample(iX, iY, wsResult[iX][iY]);
                        }
                    }
                    pm.worked(1);
                }
            }
        } catch (Exception e) {
            throw new OperatorException("Failed to process ocean aerosol algorithm:\n" + e.getMessage(), e);
        } finally {
            pm.done();
        }
    }

    private float[] doSynAOStep1(int iY, int iX,
                                 float aatsrViewElevationNadir, float aatsrViewElevationFward,
                                 float aatsrSunElevationNadir,  float aatsrSunElevationFward,
                                 float aatsrAzimuthDifferenceNadir, float aatsrAzimuthDifferenceFward,
                                 float merisViewZenith, float merisSunZenith,
                                 float merisAzimuthDifference,
                                 float surfacePressure, float ws) {

        float[] glint = new float[nWvl];
        float[] iSun = new float[nWvl];
        float[] iView = new float[nWvl];
        float[] iAzi = new float[nWvl];
        for (int j = 0; j < nWvl; j++) {
            // todo: clean up cases for finally unused channels
            switch (wvlIndex[j]) {
                case 0:
                case 1:
                    iSun[j] = merisSunZenith;
                    iView[j] = merisViewZenith;
                    iAzi[j] = (float) (180.0 - merisAzimuthDifference);
                    break;
                case 2:
                case 3:
                    iSun[j] = (float) (90.0 - aatsrSunElevationNadir);
                    iView[j] = (float) (90.0 - aatsrViewElevationNadir);
                    iAzi[j] = (float) (180.0 - aatsrAzimuthDifferenceNadir);
                    break;
                case 4:
                case 5:
                    iSun[j] = (float) (90.0 - aatsrSunElevationFward);
                    iView[j] = (float) (90.0 - aatsrViewElevationFward);
                    iAzi[j] = (float) (180.0 - aatsrAzimuthDifferenceFward);
                    break;
                default:
                    break;
            }
//            if (iX == 11 && iY == 22)
//                System.out.println("halt!");
//            if (ws == 1.0) {
//               glint[j] = 0.0f;
//            } else {
//                glint[j] = GlintRetrieval.calcGlintAnalytical(iSun[j], iView[j],
//                                                          iAzi[j], RetrieveAerosolConstants.refractiveIndex[wvlIndex[j]],
//                                                          ws, RetrieveAerosolConstants.rhoFoam[wvlIndex[j]]);
//            }
            glint[j] = GlintRetrieval.calcGlintAnalytical(iSun[j], iView[j],
                                                          iAzi[j], RetrieveAerosolConstants.refractiveIndex[wvlIndex[j]],
                                                          ws, RetrieveAerosolConstants.rhoFoam[wvlIndex[j]]);
        }

        vectorTauLutHigh = AerosolHelpers.interpolateArray(vectorTauLut, nTau);

        for (int i = 0; i < nMod; i++) {
            for (int j = 0; j < nWvl; j++) {
                // todo: clean up cases for finally unused channels

                for (int k = 0; k < nTauLut; k++) {
                    float wsToUse = ws;
//                    if (ws == 1.0) {
//                        wsToUse = 7.0f;
//                    }
                    double[] interpol5DLowInput =
                            new double[]{iAzi[j], iView[j], iSun[j], wsToUse, vectorTauLut[k], surfacePressure};

                    // interpol5DResultLow = 'minilut' in breadboard:
                    //  minilut=fltarr(nmod,nwvl,ntau)
                    interpol5DResultLow[i][j][k] =             // 'minilut' in breadboard
                            aerosolLookupTables[i][j].getValue(interpol5DLowInput);
                }
                //  interpol5DResultLow --> interpol5DResultHigh
                interpol5DResultHigh[i][j] = AerosolHelpers.interpolateArray(interpol5DResultLow[i][j], nTau);
                for (int k = 0; k < nTau; k++) {
                    interpol5DResultHigh[i][j][k] += glint[j];
                }
            }
        }
        return glint;
    }

    private void doSynAOStep2() {
        for (int i = 0; i < nWvl; i++) {
            for (int j = 0; j < nTau; j++) {
                for (int k = 0; k < nAng; k++) {
                    int angParIdx0 = angstroemParameters[k].getIndexPairs()[0];
                    int angParIdx1 = angstroemParameters[k].getIndexPairs()[1];
                    double angParwgt0 = angstroemParameters[k].getWeightPairs()[0];
                    double angParwgt1 = angstroemParameters[k].getWeightPairs()[1];

                    // interpolAngResult = 'tlut' in breadboard:
                    // tlut=fltarr(nwvl,ntau,nang)
                    interpolAngResult[i][j][k] =     // tlut in breadboard
                            (float) (interpol5DResultHigh[angParIdx0][i][j] * angParwgt0 +
                                    interpol5DResultHigh[angParIdx1][i][j] * angParwgt1);
                }
            }
        }
    }

    private void doSynAOStep3(int iY, int iX, float merisRad13, float merisRad14,
                              float aatsrReflNadir16,
                              float aatsrReflNadir87,
                              float aatsrReflFward16,
                              float aatsrReflFward87) {
        for (int i = 0; i < nWvl; i++) {
            for (int j = 0; j < nTau; j++) {
                for (int k = 0; k < nAng; k++) {
                    // todo: clean up cost function for finally unused channels
                    // costFunction = 'cost' in breadboard:
                    // cost=fltarr(nwvl,ntau,nang)
    //                                costFunction[0][j][k] = (interpolAngResult[0][j][k] - merisRad12) * wvlWeight[0];
                     switch (wvlIndex[i]) {
                        case 0:
                            costFunction[i][j][k] = (interpolAngResult[i][j][k] - merisRad13) * wvlWeight[wvlIndex[i]];
                            break;
                        case 1:
                            costFunction[i][j][k] = (interpolAngResult[i][j][k] - merisRad14) * wvlWeight[wvlIndex[i]];
                            break;
                        case 2:
                            costFunction[i][j][k] = (interpolAngResult[i][j][k] - aatsrReflNadir16) * wvlWeight[wvlIndex[i]];
                            break;
                        case 3:
                            costFunction[i][j][k] = (interpolAngResult[i][j][k] - aatsrReflNadir87) * wvlWeight[wvlIndex[i]];
                            break;
                        case 4:
                             costFunction[i][j][k] = (interpolAngResult[i][j][k] - aatsrReflFward16) * wvlWeight[wvlIndex[i]];
                            break;
                        case 5:
                            costFunction[i][j][k] = (interpolAngResult[i][j][k] - aatsrReflFward87) * wvlWeight[wvlIndex[i]];
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        // for each wvl, which tau (j) and ang (k) minimize costFunction ?
        // --> retrieve nWvl values of 'best' optical thicknesses
        // ATBD SYNAO, eq. 3.12

        float[][] costFunctionAllWvls = new float[nTau][nAng];
        for (int j = 0; j < nTau; j++) {
            for (int k = 0; k < nAng; k++) {
                costFunctionAllWvls[j][k] = 0.0f;
                for (int i = 0; i < nWvl; i++) {
                    costFunctionAllWvls[j][k] += costFunction[i][j][k] * costFunction[i][j][k];
                }
            }
        }

        double epsilon = Double.MAX_VALUE;
        double bestTau = noDataVal;
        int bestTauIndex = -1;
        int bestAngIndex = -1;
        if (iX == 21 && iY == 21)
                System.out.println("halt!");
        for (int j = 0; j < nTau; j++) {
            for (int k = 0; k < nAng; k++) {
                double diff = costFunctionAllWvls[j][k];
                if (diff < epsilon) {
                    epsilon = diff;
                    bestTau = vectorTauLutHigh[j];
                    bestTauIndex = j;
                    bestAngIndex = k;
                }
            }
        }
        
        boolean isInsideLut;
            isInsideLut = bestTauIndex > 0 && bestTauIndex < nTau - 1 &&
                    bestAngIndex > 0 && bestAngIndex < nAng - 1;
        // compute derivatives around best AOT solutions, and AOT error
        double[] dRadTodTau = new double[nWvl];
        double[] dRadTodAng = new double[nWvl];
        double deltaTau;
        for (int i = 0; i < nWvl; i++) {
            final int tauLowerIndex = Math.max(0, bestTauIndex-1);
            final int tauUpperIndex = Math.min(nTau-1, bestTauIndex+1);
            final int angLowerIndex = Math.max(0, bestAngIndex-1);
            final int angUpperIndex = Math.min(nAng-1, bestAngIndex+1);
            final float dRad = Math.abs(interpolAngResult[i][tauUpperIndex][angUpperIndex] -
                    interpolAngResult[i][tauLowerIndex][angLowerIndex]);
            final double dTau = Math.abs(vectorTauLutHigh[tauUpperIndex] - vectorTauLutHigh[tauLowerIndex]);
            final double dAng = Math.abs(angstroemParameters[angUpperIndex].getValue() -
                    angstroemParameters[angLowerIndex].getValue());
            dRadTodTau[i] = dRad / dTau;
            dRadTodAng[i] = dRad / dAng;

            deltaTau = 0.0;
            if (bestTau != noDataVal && isInsideLut) {
                deltaTau = Math.abs(epsilon/dRadTodTau[i]);
            }
        }

        aot550Result[iX][iY] = noDataVal;
        angResult[iX][iY] = noDataVal;
        aot550ErrorResult[iX][iY] = noDataVal;
        angErrorResult[iX][iY] = noDataVal;

        if (bestTauIndex != -1 && bestAngIndex != -1) {
            double sumDeltaTau = 0.0;
            double sumDeltaAng = 0.0;
            for (int i = 0; i < nWvl; i++) {
                sumDeltaTau += Math.pow(wvlWeight[wvlIndex[i]]/costFunction[i][bestTauIndex][bestAngIndex], 2.0) *
                               Math.pow(dRadTodTau[i], 2.0);
                sumDeltaAng += Math.pow(wvlWeight[wvlIndex[i]]/costFunction[i][bestTauIndex][bestAngIndex], 2.0) *
                               Math.pow(dRadTodAng[i], 2.0);
            }

            // FINAL RESULT 1:
            aot550Result[iX][iY] = (float) bestTau;
            if (aot550Result[iX][iY] != noDataVal) {
                // FINAL RESULTS 2, 3:
                angResult[iX][iY] = (float) angstroemParameters[bestAngIndex].getValue();
//                angResult[iX][iY] = (float) bestAngIndex;
                aot550ErrorResult[iX][iY] = (float) (1.0/Math.sqrt(sumDeltaTau));
                angErrorResult[iX][iY] = (float) (1.0/Math.sqrt(sumDeltaAng));
            }
        }
    }

    private float getAvePixel(Tile inputTile, int iTarX, int iTarY) {

        double value = 0;
        double noDataValue = 0;
        int n = 0;

        final int minX = Math.max(0,iTarX-aveBlock);
        final int minY = Math.max(0,iTarY-aveBlock);
        final int maxX = Math.min(synergyProduct.getSceneRasterWidth()-1,iTarX+aveBlock);
        final int maxY = Math.min(synergyProduct.getSceneRasterHeight()-1,iTarY+aveBlock);

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
        if (!(n<minNAve)) {
            value /= n;
        }
        else {
            value = noDataValue;
        }

        return (float) value;
    }

    private boolean getAveFlag(Tile inputTile, int iTarX, int iTarY, int bitIndex) {

        double noDataValue = 0;
        int numberPixels = 0;
        int numberFlagIsSet = 0;

        final int minX = Math.max(0,iTarX-aveBlock);
        final int minY = Math.max(0,iTarY-aveBlock);
        final int maxX = Math.min(synergyProduct.getSceneRasterWidth()-1,iTarX+aveBlock);
        final int maxY = Math.min(synergyProduct.getSceneRasterHeight()-1,iTarY+aveBlock);

        for (int iy = minY; iy <= maxY; iy++) {
            for (int ix = minX; ix <= maxX; ix++) {
                int val = inputTile.getSampleInt(ix, iy);
                noDataValue = inputTile.getRasterDataNode().getNoDataValue();
//                boolean valid = (Double.compare((double)val, noDataValue) != 0);
                numberPixels++;
                if (val != noDataValue) {
//                    boolean flagIsSet = inputTile.getSampleBit(ix, iy, bitIndex);
                    boolean flagIsSet = inputTile.getSampleBit(ix, iy, 1);
                    if (flagIsSet) {
                        numberFlagIsSet++;
                    }
                }
            }
        }
        return (numberFlagIsSet*1.0/numberPixels > 0.5);
    }


    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RetrieveAerosolOceanOp.class);
        }
    }
}
