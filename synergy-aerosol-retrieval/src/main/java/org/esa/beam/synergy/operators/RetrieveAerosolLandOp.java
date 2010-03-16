/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.BitmaskDef;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.datamodel.VirtualBand;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.synergy.util.AerosolHelpers;
import org.esa.beam.util.ProductUtils;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.io.File;

import org.esa.beam.synergy.util.WavelengthComparator;
import org.esa.beam.gpf.operators.standard.BandMathsOp;

/**
 * Operator for Aerosol retrieval over land within MERIS/AATSR Synergy project.
 *
 * @author Andreas Heckel, Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 17:54:34 +0000 (Mi, 27 Jan 2010) $
 * 
 */
@OperatorMetadata(alias = "synergy.AerosolLand",
                  version = "1.0-SNAPSHOT",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "Retrieve Aerosol over Land.")
public class RetrieveAerosolLandOp extends Operator{
    @SourceProduct(alias = "source",
                   label = "Name (Collocated MERIS AATSR product)",
                   description = "Select a collocated MERIS AATSR product.")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(alias = RetrieveAerosolConstants.SOIL_SPEC_PARAM_NAME,
               defaultValue = RetrieveAerosolConstants.SOIL_SPEC_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.SOIL_SPEC_PARAM_DESCRIPTION,
                label = RetrieveAerosolConstants.SOIL_SPEC_PARAM_LABEL)
    private String soilSpecName;
    
    @Parameter(alias = RetrieveAerosolConstants.VEG_SPEC_PARAM_NAME,
               defaultValue = RetrieveAerosolConstants.VEG_SPEC_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.VEG_SPEC_PARAM_DESCRIPTION,
                label = RetrieveAerosolConstants.VEG_SPEC_PARAM_LABEL)
    private String vegSpecName;
    
    @Parameter(alias = RetrieveAerosolConstants.LUT_PATH_PARAM_NAME,
               defaultValue = RetrieveAerosolConstants.LUT_PATH_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.LUT_PATH_PARAM_DESCRIPTION,
               label = RetrieveAerosolConstants.LUT_PATH_PARAM_LABEL)
    private String lutPath;

    @Parameter(alias = RetrieveAerosolConstants.AEROSOL_MODEL_PARAM_NAME,
               defaultValue = RetrieveAerosolConstants.AEROSOL_MODEL_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.AEROSOL_MODEL_PARAM_DESCRIPTION,
               label = RetrieveAerosolConstants.AEROSOL_MODEL_PARAM_LABEL)
    private String aerosolModelString;
    private List<Integer> aerosolModels;
    

    private String productName = RetrieveAerosolConstants.OUTPUT_PRODUCT_NAME_DEFAULT;
    private String productType = RetrieveAerosolConstants.OUTPUT_PRODUCT_TYPE_DEFAULT;

    @Parameter(defaultValue="11",
               label="Pixels to average (n x n, with n odd number) for AOD retrieval",
               interval = "[1, 100]")
    private int aveBlock;

    @Parameter(defaultValue = "true", label = "Do Cld Screening")
    boolean doCldScreen;
    @Parameter(label = "AATSR")
    boolean doAATSR;
    @Parameter(label = "MERIS")
    boolean doMERIS;

    @Parameter(defaultValue = "true", label = "dump pixel")
    boolean dumpPixel;
    @Parameter(defaultValue = "10", label = "dump pixel X")
    int dumpPixelX;
    @Parameter(defaultValue = "10", label = "dump pixel Y")
    int dumpPixelY;


    private int rasterWidth;
    private int rasterHeight;
    
    private ArrayList<Band> merisBandList;
    private ArrayList<Band> aatsrBandListNad;
    private ArrayList<Band> aatsrBandListFwd;
    private ArrayList<RasterDataNode> merisGeometryBandList;
    private ArrayList<RasterDataNode> aatsrGeometryBandList;
    private float[] merisWvl;
    private float[] aatsrWvl;
    private float[] merisToaReflec;
    private float[][] aatsrToaReflec;
    private Aardvarc aardvarc;
    private float[] soilSurfSpec;
    private float[] vegSurfSpec;
    private ReflectanceBinLUT toaLut;
    private float[] lutAlbedo;
    private float[] lutAot;
    private float[][][] lutSubsecMeris;
    private float[][][][] lutSubsecAatsr;
    
    private boolean validPixel;
    //private int minNAve;
    //private float noDataVal;
    private Product synergyProduct;

    private final String virtNdviName = "synergyNdvi";
    private final String validBandName = "validFlag";
    private final String landFlagExpression = " (l1_flags_MERIS.LAND_OCEAN) ";
    private Band isLandBand;
//    private final String cloudyFlagExpression = " ( false )";
    private final String fwdCloudFilter = " (((btemp_fward_1200_AATSR-btemp_nadir_1200_AATSR)/btemp_nadir_1200_AATSR)<-0.05) ";
//    private String cloudyFlagExpression = fwdCloudFilter + "|| (cloud_flags_synergy.CLOUD || cloud_flags_synergy.CLOUD_FILLED || cloud_flags_synergy.SHADOW)";
    private String cloudyFlagExpression = fwdCloudFilter + "|| (cloud_flags_synergy.CLOUD || cloud_flags_synergy.CLOUD_FILLED)";
    private Band isCloudyBand;
    private final String validFlagExpression = landFlagExpression + " && " + cloudyFlagExpression;
    private Band isValidBand;
    private int downscaledRasterWidth;
    private int downscaledRasterHeight;

    private float scalingFactor;

    private final String aerosolFlagCodingName = RetrieveAerosolConstants.aerosolFlagCodingName;
    private final String flagCloudyName = RetrieveAerosolConstants.flagCloudyName;
    private final String flagOceanName = RetrieveAerosolConstants.flagOceanName;
    private final String flagSuccessName = RetrieveAerosolConstants.flagSuccessName;
    private final String flagBorderName = RetrieveAerosolConstants.flagBorderName;
    private final String flagFilledName = RetrieveAerosolConstants.flagFilledName;
    private final String flagNegMetricName = RetrieveAerosolConstants.flagNegMetricName;
    private final String flagAotLowName = RetrieveAerosolConstants.flagAotLowName;
    private final String flagErrHighName = RetrieveAerosolConstants.flagErrHighName;
    private final String flagCloudyDesc = RetrieveAerosolConstants.flagCloudyDesc;
    private final String flagOceanDesc = RetrieveAerosolConstants.flagOceanDesc;
    private final String flagSuccessDesc = RetrieveAerosolConstants.flagSuccessDesc;
    private final String flagBorderDesc = RetrieveAerosolConstants.flagBorderDesc;
    private final String flagFilledDesc = RetrieveAerosolConstants.flagFilledDesc;
    private final String flagNegMetricDesc = RetrieveAerosolConstants.flagNegMetricDesc;
    private final String flagAotLowDesc = RetrieveAerosolConstants.flagAotLowDesc;
    private final String flagErrHighDesc = RetrieveAerosolConstants.flagErrHighDesc;
    private final int oceanMask = RetrieveAerosolConstants.oceanMask;
    private final int cloudyMask = RetrieveAerosolConstants.cloudyMask;
    private final int successMask = RetrieveAerosolConstants.successMask;
    private final int borderMask = RetrieveAerosolConstants.borderMask;
    private final int filledMask = RetrieveAerosolConstants.filledMask;
    private final int negMetricMask = RetrieveAerosolConstants.negMetricMask;
    private final int aotLowMask = RetrieveAerosolConstants.aotLowMask;
    private final int errHighMask = RetrieveAerosolConstants.errHighMask;


    @Override
    public void initialize() throws OperatorException {
       
        synergyProduct = sourceProduct;

        deactivateComputeTileMethod();

        readAerosolModelNumbers();

        // todo: add some validations of the other GUI input...

        merisBandList = new ArrayList<Band>();
        aatsrBandListNad = new ArrayList<Band>();
        aatsrBandListFwd = new ArrayList<Band>();
        merisGeometryBandList = new ArrayList<RasterDataNode>();
        aatsrGeometryBandList = new ArrayList<RasterDataNode>();
        toaLut = null;
        validPixel = true;

        scalingFactor = aveBlock;
        aveBlock /= 2;
        //minNAve = (int) (scalingFactor*scalingFactor);
        //noDataVal = (float) RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE;
        
        rasterWidth = synergyProduct.getSceneRasterWidth();
        rasterHeight = synergyProduct.getSceneRasterHeight();

        createTargetProduct();
        
        AerosolHelpers.getSpectralBandList(synergyProduct, RetrieveAerosolConstants.INPUT_BANDS_PREFIX_MERIS,
                RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_MERIS,
                RetrieveAerosolConstants.EXCLUDE_INPUT_BANDS_MERIS, merisBandList);
        AerosolHelpers.getSpectralBandList(synergyProduct, RetrieveAerosolConstants.INPUT_BANDS_PREFIX_AATSR_NAD,
                RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR,
                RetrieveAerosolConstants.EXCLUDE_INPUT_BANDS_AATSR, aatsrBandListNad);
        AerosolHelpers.getSpectralBandList(synergyProduct, RetrieveAerosolConstants.INPUT_BANDS_PREFIX_AATSR_FWD,
                RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR,
                RetrieveAerosolConstants.EXCLUDE_INPUT_BANDS_AATSR, aatsrBandListFwd);
        AerosolHelpers.getGeometryBandList(synergyProduct, "MERIS", merisGeometryBandList);
        AerosolHelpers.getGeometryBandList(synergyProduct, "AATSR", aatsrGeometryBandList);

        //QUESTION: should I really add a band to the input product????
        if (! synergyProduct.containsBand(virtNdviName)) {
            String vNdviExpression = createNdviExpression(merisBandList);
            VirtualBand virtNDVI = new VirtualBand(virtNdviName, ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight, vNdviExpression);
            synergyProduct.addBand(virtNDVI);
        }
/*
        if (! synergyProduct.containsBand(validBandName)) {
            VirtualBand validBand = new VirtualBand(validBandName, ProductData.TYPE_INT32, rasterWidth, rasterHeight, validFlagExpression);
            synergyProduct.addBand(validBand);
        }
*/
        // Alternative
/*        String ndviExpression = createNdviExpression(merisBandList);
        BandArithmeticOp arithmeticOp = BandArithmeticOp.createBooleanExpressionBand(ndviExpression, synergyProduct);
        ndviBand = arithmeticOp.getTargetProduct().getBandAt(0);

*/
        BandMathsOp bandArithmOp = BandMathsOp.createBooleanExpressionBand(landFlagExpression, synergyProduct);
        isLandBand = bandArithmOp.getTargetProduct().getBandAt(0);
        if (!doCldScreen) cloudyFlagExpression = "false";
        bandArithmOp = BandMathsOp.createBooleanExpressionBand(cloudyFlagExpression, synergyProduct);
        isCloudyBand = bandArithmOp.getTargetProduct().getBandAt(0);
        bandArithmOp = BandMathsOp.createBooleanExpressionBand(validFlagExpression, sourceProduct);
        isValidBand = bandArithmOp.getTargetProduct().getBandAt(0);

        merisWvl = new float[merisBandList.size()];
        aatsrWvl = new float[aatsrBandListNad.size()];
        merisToaReflec = new float[merisBandList.size()];
        aatsrToaReflec = new float[2][aatsrBandListNad.size()];

        readWavelength(merisBandList, merisWvl);
        readWavelength(aatsrBandListNad, aatsrWvl);

        aardvarc = new Aardvarc(aatsrWvl, merisWvl);
        aardvarc.setDoAATSR(doAATSR);
        aardvarc.setDoMERIS(doMERIS);


        if (soilSurfSpec == null) {
            soilSurfSpec = new SurfaceSpec(soilSpecName, merisWvl).getSpec();
        }
        if (vegSurfSpec == null) {
            vegSurfSpec = new SurfaceSpec(vegSpecName, merisWvl).getSpec();
        }
        aardvarc.setSpecSoil(soilSurfSpec);
        aardvarc.setSpecVeg(vegSurfSpec);



    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {

        pm.beginTask("aerosol retrieval", aerosolModels.size() * targetRectangle.width * targetRectangle.height + 4);
        System.out.printf("   Aerosol Retrieval @ Tile %s\n", targetRectangle.toString());

        // define bigger Rectangle for binning of source tiles
        int bigWidth = (int) ((2*aveBlock+1)*targetRectangle.getWidth());
        int bigHeight = (int) ((2*aveBlock+1)*targetRectangle.getHeight());
        int bigX = (int) ((2*aveBlock+1)*targetRectangle.getX());
        int bigY = (int) ((2*aveBlock+1)*targetRectangle.getY());
        Rectangle big = new Rectangle(bigX, bigY, bigWidth, bigHeight);

        // read source tiles
        Tile[] merisTiles = getSpecTiles(merisBandList, big);

        Tile[][] aatsrTiles = new Tile[2][0];
        aatsrTiles[0] = getSpecTiles(aatsrBandListNad, big);
        aatsrTiles[1] = getSpecTiles(aatsrBandListFwd, big);
        
        Tile[] geometryTiles = getGeometryTiles(merisGeometryBandList, aatsrGeometryBandList, big);
        
        Tile pressureTile = getSourceTile(
                                synergyProduct.getTiePointGrid(RetrieveAerosolConstants.INPUT_PRESSURE_BAND_NAME),
                                big,
                                SubProgressMonitor.create(pm, 1));
        Tile ozoneTile = getSourceTile(
                                synergyProduct.getTiePointGrid(RetrieveAerosolConstants.INPUT_OZONE_BAND_NAME),
                                big,
                                SubProgressMonitor.create(pm, 1));

        Tile isValidTile = getSourceTile(isValidBand, big, SubProgressMonitor.create(pm, 1));
        Tile isLandTile = getSourceTile(isLandBand, big, SubProgressMonitor.create(pm, 1));
        Tile isCloudyTile = getSourceTile(isCloudyBand, big, SubProgressMonitor.create(pm, 1));

        // define target tiles
        Tile vNdviTile = getSourceTile(synergyProduct.getBand(virtNdviName), big, SubProgressMonitor.create(pm, 1));
        //Tile vNdviTile = getSourceTile(ndviBand, big, SubProgressMonitor.create(pm, 1));
        //Tile[] aotTiles = new Tile[aerosolModels.size()];
        //Tile[] errTiles = new Tile[aerosolModels.size()];

        Tile aerosolTile = targetTiles.get(targetProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME));
        Tile aerosolModelTile = targetTiles.get(targetProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME));
        Tile aerosolErrTile = targetTiles.get(targetProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME));
        Tile aerosolFlagTile = targetTiles.get(targetProduct.getBand(aerosolFlagCodingName));

        double[][] minErr = new double[targetRectangle.height][targetRectangle.width];

        // initialize target aot tile
        for (int iy=targetRectangle.y; iy<targetRectangle.y + targetRectangle.height; iy++) {
            for (int ix=targetRectangle.x; ix<targetRectangle.x + targetRectangle.width;  ix++) {
                aerosolTile.setSample(ix, iy, RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
                aerosolErrTile.setSample(ix, iy, RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NODATAVALUE);
                aerosolModelTile.setSample(ix, iy, RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
                minErr[iy-targetRectangle.y][ix-targetRectangle.x] = RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NODATAVALUE;
            }
        }

        for (int iAM = 0; iAM < aerosolModels.size(); iAM++) {
            
            int aeroModel = aerosolModels.get(iAM).intValue();

            if ((toaLut == null) || (toaLut.getAerosolModel() != aeroModel)) {
                // provide complete LUT:
                toaLut = new ReflectanceBinLUT(lutPath, aeroModel, merisWvl, aatsrWvl);
                lutAlbedo = toaLut.getAlbDim();
                lutAot = toaLut.getAotDim();

                lutSubsecMeris = new float[merisWvl.length][lutAlbedo.length][lutAot.length];
                lutSubsecAatsr = new float[2][aatsrWvl.length][lutAlbedo.length][lutAot.length];
            }

            for (int iY = targetRectangle.y; iY < targetRectangle.y + targetRectangle.height; iY++) {
                for (int iX = targetRectangle.x; iX < targetRectangle.x + targetRectangle.width; iX++) {

                    int iSrcX = (2*aveBlock+1)*iX + aveBlock;
                    int iSrcY = (2*aveBlock+1)*iY + aveBlock;

                    int flagPixel = 0;
                    boolean isBorder = (iSrcY+aveBlock >= rasterHeight || iSrcX+aveBlock >= rasterWidth);
                    if (isBorder) flagPixel |= borderMask;
                    
                    boolean isLand = evaluateFlagPixel(isLandTile, iSrcX, iSrcY, true);
                    boolean isCloudy = evaluateFlagPixel(isCloudyTile, iSrcX, iSrcY, false);
                    if (!isLand)  flagPixel |= oceanMask;
                    if (isCloudy) flagPixel |= cloudyMask;
                    // keep previous success
                    boolean prevSuccess = aerosolFlagTile.getSampleBit(iX, iY, 2);
                    if (prevSuccess) flagPixel |= successMask;
                    
                    validPixel = isLand && !isCloudy;

                    float[] geometry = null;
                    float aveMerisPressure = 0;
                    float aveMerisOzone = 0;
                    float aveNdvi = 0;

                    if (validPixel) {
                        geometry = getAvePixel(geometryTiles, iSrcX, iSrcY, isValidTile);

                        merisToaReflec = getAvePixel(merisTiles, iSrcX, iSrcY, isValidTile);
                        aatsrToaReflec = getAvePixel(aatsrTiles, iSrcX, iSrcY, isValidTile);

                        aveMerisPressure = getAvePixel(pressureTile, iSrcX, iSrcY, isValidTile);
                        aveMerisOzone = getAvePixel(ozoneTile, iSrcX, iSrcY, isValidTile);
                        aveNdvi = getAvePixel(vNdviTile, iSrcX, iSrcY, isValidTile);
                    }
                    if (validPixel) {

                        int iSza = 0; int iSaa = 1; int iVza = 2; int iVaa = 3;
                        int offset = 0; // MERIS geometry
                        toaLut.subsecLUT("meris", aveMerisPressure, aveMerisOzone, geometry[iVza+offset], geometry[iVaa+offset],
                                          geometry[iSza+offset], geometry[iSaa+offset], merisWvl, lutSubsecMeris);
                        offset = 4; // AATSR NADIR geometry
                        toaLut.subsecLUT("aatsr", aveMerisPressure, aveMerisOzone, geometry[iVza+offset], geometry[iVaa+offset],
                                          geometry[iSza+offset], geometry[iSaa+offset], aatsrWvl, lutSubsecAatsr[0]);
                        offset = 8; // AATSR FWARD geometry
                        toaLut.subsecLUT("aatsr", aveMerisPressure, aveMerisOzone, geometry[iVza+offset], geometry[iVaa+offset],
                                          geometry[iSza+offset], geometry[iSaa+offset], aatsrWvl, lutSubsecAatsr[1]);

                        aardvarc.setSza(geometry[0],geometry[4],geometry[8]);
                        aardvarc.setSaa(geometry[1],geometry[5],geometry[9]);
                        aardvarc.setVza(geometry[2],geometry[6],geometry[10]);
                        aardvarc.setVaa(geometry[3],geometry[7],geometry[11]);
                        aardvarc.setNdvi(aveNdvi);
                        aardvarc.setSurfPres(aveMerisPressure);
                        aardvarc.setToaReflMeris(merisToaReflec);
                        aardvarc.setToaReflAatsr(aatsrToaReflec);
                        aardvarc.setLutReflAatsr(lutSubsecAatsr);
                        aardvarc.setLutReflMeris(lutSubsecMeris);
                        aardvarc.setAlbDim(lutAlbedo);
                        aardvarc.setAotDim(lutAot);

                        // now run the retrieval...
                        aardvarc.runAarvarc();
                        if (dumpPixel && (iX == dumpPixelX) && (iY == dumpPixelY)) {
                            aardvarc.dumpParameter("p:/aardvarc_aot.dump");
                            aardvarc.dumpErrorMetric("p:/aardvarc_err.dump");
                        }

                        // and these are the retrieval results:
                        boolean retrievalFailed = aardvarc.isFailed();
                        float aot = aardvarc.getOptAOT();    // AOT (tau_550)
                        float errMetric = aardvarc.getOptErr();    // E
                        float retrievalError = aardvarc.getRetrievalErr();
                        retrievalFailed = retrievalFailed || aot < 1e-3 || (aot > 0.1 && (retrievalError/aot) > 5);
                        if (!retrievalFailed) flagPixel |= successMask;
                        if (aardvarc.isFailed()) flagPixel |= negMetricMask;
                        if (aot < 1e-5) flagPixel |= aotLowMask;
                        if (aot > 0.1 && (retrievalError/aot) > 5) flagPixel |= errHighMask;

                        double errTemp = minErr[iY-targetRectangle.y][iX-targetRectangle.x];
                        if ( (Double.compare(errTemp, RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NODATAVALUE) == 0
                            || errMetric < errTemp)) {

                            minErr[iY-targetRectangle.y][iX-targetRectangle.x] = errMetric;
                            aerosolTile.setSample(iX, iY, aot);
                            aerosolErrTile.setSample(iX, iY, retrievalError);
                            aerosolModelTile.setSample(iX, iY, aerosolModels.get(iAM));
                        }

                    }

                    aerosolFlagTile.setSample(iX, iY, flagPixel);
                    pm.worked(1);
                }
            }
        }
        pm.done();
    }

    private String createNdviExpression(ArrayList<Band> bandList) {
        final float NDVI_RED_WVL = 680.0f;
        final float NDVI_IR_WVL = 880.0f;
        int iRED = 0;
        int iIR = 0;

        for (Band b : bandList) {
            float wvl = b.getSpectralWavelength();
            float redWvl = bandList.get(iRED).getSpectralWavelength();
            if (Math.abs(wvl-NDVI_RED_WVL) < Math.abs(redWvl-NDVI_RED_WVL)) {
                iRED = bandList.indexOf(b);
            }
            float irWvl = bandList.get(iIR).getSpectralWavelength();
            if (Math.abs(wvl-NDVI_IR_WVL) < Math.abs(irWvl-NDVI_IR_WVL)) {
                iIR = bandList.indexOf(b);
            }
        }
        String redName = bandList.get(iRED).getName();
        String irName = bandList.get(iIR).getName();

        return "(" + irName + " - " + redName + ")/(" + irName + " + " + redName + ")";
    }

    private void createTargetProduct() {

        downscaledRasterWidth = (int) (Math.ceil((float) (rasterWidth / scalingFactor) - 0.5));
        downscaledRasterHeight = (int) (Math.ceil((float) (rasterHeight/ scalingFactor) - 0.5));

        targetProduct = new Product(productName, productType, downscaledRasterWidth, downscaledRasterHeight);

        ProductUtils.copyMetadata(synergyProduct, targetProduct);
        ProductUtils.copyGeoCoding(synergyProduct, targetProduct);
        AerosolHelpers.copyDownscaledTiePointGrids(synergyProduct, targetProduct, scalingFactor);
        AerosolHelpers.copyDownscaledFlagBands(synergyProduct, targetProduct, scalingFactor);

        AerosolHelpers.addAerosolFlagBand(targetProduct, downscaledRasterWidth, downscaledRasterHeight);

        createTargetProductBands();
        
        targetProduct.setPreferredTileSize(100, 100);
        setTargetProduct(targetProduct);
        
    }

    private void createTargetProductBands() {

        //TODO: reduce output bands to 1 aot and 1 err band
/*
        for (int iAM=0; iAM<aerosolModels.size(); iAM++) {
            String bandName = RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME
                              + String.format("_%02d", aerosolModels.get(iAM));
            final Band aotBand = new Band(bandName, ProductData.TYPE_FLOAT32, downscaledRasterWidth, downscaledRasterHeight);
            aotBand.setDescription(RetrieveAerosolConstants.OUTPUT_AOT_BAND_DESCRIPTION);
            aotBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
            aotBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE_USED);
            aotBand.setValidPixelExpression(aotBand.getName() + "> 0 AND " + aotBand.getName() + "<= 2");
            aotBand.setUnit("dl");
            targetProduct.addBand(aotBand);

            bandName = RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME
                       + String.format("_%02d", aerosolModels.get(iAM));
            final Band errBand = new Band(bandName, ProductData.TYPE_FLOAT32, downscaledRasterWidth, downscaledRasterHeight);
            errBand.setDescription(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_DESCRIPTION);
            errBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NODATAVALUE);
            errBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NODATAVALUE_USED);
            errBand.setUnit("dl");
            targetProduct.addBand(errBand);
        }
*/

        Band targetBand = new Band(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME, ProductData.TYPE_FLOAT32, downscaledRasterWidth, downscaledRasterHeight);
        targetBand.setDescription("best fitting aot Band");
        targetBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
        targetBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE_USED);
        targetBand.setValidPixelExpression(targetBand.getName() + ">= 0 AND " + targetBand.getName() + "<= 1");
        targetBand.setUnit("dl");
        targetProduct.addBand(targetBand);

        targetBand = new Band(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME, ProductData.TYPE_FLOAT32, downscaledRasterWidth, downscaledRasterHeight);
        targetBand.setDescription("uncertainty Band of best fitting aot");
        targetBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NODATAVALUE);
        targetBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NODATAVALUE_USED);
        targetBand.setUnit("dl");
        targetProduct.addBand(targetBand);

        targetBand = new Band(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME, ProductData.TYPE_INT32, downscaledRasterWidth, downscaledRasterHeight);
        targetBand.setDescription("aerosol model number Band");
        targetBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
        targetBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE_USED);
        targetBand.setValidPixelExpression(targetBand.getName() + ">= 1 AND " + targetBand.getName() + "<= 40");
        targetBand.setUnit("dl");
        targetProduct.addBand(targetBand);
    }

    private boolean evaluateFlagPixel(Tile flagTile, int iTarX, int iTarY, boolean flag) {
        if (flag) {
            for (int iy = iTarY - aveBlock; iy <= iTarY + aveBlock; iy++) {
                for (int ix = iTarX - aveBlock; ix <= iTarX + aveBlock; ix++) {
                    if (iy < rasterHeight && ix < rasterWidth) {
                        flag = flag && flagTile.getSampleBoolean(ix, iy);
                    }
                }
            }
        } else {
            for (int iy = iTarY - aveBlock; iy <= iTarY + aveBlock; iy++) {
                for (int ix = iTarX - aveBlock; ix <= iTarX + aveBlock; ix++) {
                    if (iy < rasterHeight && ix < rasterWidth) {
                        flag = flag || flagTile.getSampleBoolean(ix, iy);
                    }
                }
            }
        }
        return flag;
    }

    private float getAvePixel(Tile inputTile, int iTarX, int iTarY, Tile flags) {

        double value = 0;
        double noDataValue = inputTile.getRasterDataNode().getNoDataValue();
        int n = 0;
        int minNAve = (iTarY + aveBlock >= rasterHeight) ? (rasterHeight - iTarY - aveBlock) : (int) scalingFactor;
        minNAve *= (iTarX+aveBlock >= rasterWidth) ? (rasterWidth-iTarX-aveBlock) : (int) scalingFactor;

        for (int iy = iTarY-aveBlock; iy <= iTarY+aveBlock; iy++) {
            for (int ix = iTarX-aveBlock; ix <= iTarX+aveBlock; ix++) {
                if (iy < rasterHeight && ix < rasterWidth) {
                    double val = inputTile.getSampleDouble(ix, iy);
                    boolean valid = (Double.compare(val, noDataValue) != 0);
                    if (valid) {
                        n++;
                        value += val;
                    }
                }
            }
        }
        validPixel = validPixel && (!(n<minNAve));
        if (validPixel) {
            value /= n;
            if (inputTile.getRasterDataNode().getName().matches(".*elev.*")) {
                value = 90.0f - value;
            }
        }
        else value = noDataValue;
        
        return (float) value;
    }

    private float[] getAvePixel(Tile[] tileArr, int iTarX, int iTarY, Tile flags) {
        
        float[] valueArr = new float[tileArr.length];
        
        for (int i = 0; i < valueArr.length; i++) {
            valueArr[i] = getAvePixel(tileArr[i], iTarX, iTarY, flags);
        }
        
        return valueArr;
    }

    private float[][] getAvePixel(Tile[][] tileArr2, int iTarX, int iTarY, Tile flags) {
        float[][] valueArr2 = new float[2][0];
        valueArr2[0] = getAvePixel(tileArr2[0], iTarX, iTarY, flags);
        valueArr2[1] = getAvePixel(tileArr2[1], iTarX, iTarY, flags);

        return valueArr2;
    }
/*
    private void getGeometryBandList(Product inputProduct, String instr, ArrayList<RasterDataNode> bandList) {
        String[] viewArr = {"nadir", "fward"};
        int nView = viewArr.length;
        String[] bodyArr = {"sun", "view"};
        String[] angArr = {"elev", "azimuth"};
        String bandName;
        
        if (instr.equals("MERIS")) {
            angArr[0] = "zenith";
            nView = 1;
        } 
        for (int iView = 0; iView < nView; iView++) {
            for (String body : bodyArr) {
                for(String ang : angArr) {
                    if (instr.equals("AATSR")) {
                        bandName = body + "_" + ang + "_" + viewArr[iView] + "_" +
                                RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR;
                        bandList.add(inputProduct.getBand(bandName));
                    } else {
                        bandName = body + "_" + ang;
                        bandList.add(inputProduct.getRasterDataNode(bandName));
                    }
                }
            }
        }
    }
*/
    private Tile[] getGeometryTiles(ArrayList<RasterDataNode> merisGeometryBandList, ArrayList<RasterDataNode> aatsrGeometryBandList, Rectangle rec) {
        ArrayList<RasterDataNode> bandList = new ArrayList<RasterDataNode>();
        bandList.addAll(merisGeometryBandList);
        bandList.addAll(aatsrGeometryBandList);
        Tile[] geometryTiles = new Tile[bandList.size()];
        for (int i = 0; i < bandList.size(); i++) {
            Tile sourceTile = getSourceTile(bandList.get(i), rec, ProgressMonitor.NULL);
            geometryTiles[i] = sourceTile;
        }
        return geometryTiles;
    }

    private Tile[] getSpecTiles(ArrayList<Band> sourceBandList, Rectangle rec) {
        Tile[] sourceTiles = new Tile[sourceBandList.size()];
        for (int i = 0; i < sourceTiles.length; i++) {
            sourceTiles[i] = getSourceTile(sourceBandList.get(i), rec, ProgressMonitor.NULL);
        }
        return sourceTiles;
    }
/*
    private void getSpectralBandList(Product inputProduct, String bandNamePrefix,  String bandNameSuffix,
            int[] excludeBandIndices, ArrayList<Band> bandList) {
        
        String[] bandNames = inputProduct.getBandNames();
        Comparator<Band> byWavelength = new WavelengthComparator();
        for (String name : bandNames) {
            if (name.startsWith(bandNamePrefix) && name.endsWith(bandNameSuffix)) {
                boolean exclude = false;
                if (excludeBandIndices != null) {
                    for (int i : excludeBandIndices) {
                        exclude = exclude || (i == inputProduct.getBand(name).getSpectralBandIndex()+1);
                    }
                }
                if (!exclude) bandList.add(inputProduct.getBand(name));
            }
        }
        Collections.sort(bandList,byWavelength);
    }
*/
    private void readWavelength(ArrayList<Band> bandList, float[] wvl) {
        for (int i = 0; i < bandList.size(); i++) {
            wvl[i] = bandList.get(i).getSpectralWavelength();
        }
    }

    private void readAerosolModelNumbers() {
        aerosolModelString.trim();
        aerosolModels = new ArrayList<Integer>();
        try {
            StringTokenizer st = new StringTokenizer(aerosolModelString, ",", false);
            while (st.hasMoreTokens()) {
                int iAerMo = Integer.parseInt(st.nextToken());
                if (iAerMo < 1 || iAerMo > 40) {
                    throw new OperatorException("Invalid aerosol model number: " + iAerMo + "\n");
                }
                aerosolModels.add(iAerMo);
            }
        } catch (Exception e) {
            throw new OperatorException("Could not parse input list of aerosol models: \n" + e.getMessage(), e);
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RetrieveAerosolLandOp.class);
        }
    }
}
