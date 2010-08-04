/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
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
import org.esa.beam.gpf.operators.standard.BandMathsOp;
import org.esa.beam.synergy.util.AerosolHelpers;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.synergy.util.SynergyUtils;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Operator for Aerosol retrieval over land within MERIS/AATSR Synergy project.
 *
 * @author Andreas Heckel, Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 17:54:34 +0000 (Mi, 27 Jan 2010) $
 * 
 */
@OperatorMetadata(alias = "synergy.RetrieveAerosolLand",
                  version = "1.1",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "Retrieve Aerosol over Land.", internal=true)
public class RetrieveAerosolLandOp extends Operator{
    @SourceProduct(alias = "source",
                   label = "Name (Collocated MERIS AATSR product)",
                   description = "Select a collocated MERIS AATSR product.")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(alias = SynergyConstants.SOIL_SPEC_PARAM_NAME,
               defaultValue = SynergyConstants.SOIL_SPEC_PARAM_DEFAULT,
               description = SynergyConstants.SOIL_SPEC_PARAM_DESCRIPTION,
                label = SynergyConstants.SOIL_SPEC_PARAM_LABEL)
    private String soilSpecName;
    
    @Parameter(alias = SynergyConstants.VEG_SPEC_PARAM_NAME,
               defaultValue = SynergyConstants.VEG_SPEC_PARAM_DEFAULT,
               description = SynergyConstants.VEG_SPEC_PARAM_DESCRIPTION,
                label = SynergyConstants.VEG_SPEC_PARAM_LABEL)
    private String vegSpecName;
    
    private String auxdataPath = SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT + File.separator +
            "aerosolLUTs" + File.separator + "land";

    @Parameter(defaultValue = "false")
    private boolean useCustomLandAerosol = false;

    @Parameter(alias = SynergyConstants.AEROSOL_MODEL_PARAM_NAME,
               defaultValue = SynergyConstants.AEROSOL_MODEL_PARAM_DEFAULT,
               description = SynergyConstants.AEROSOL_MODEL_PARAM_DESCRIPTION,
               label = SynergyConstants.AEROSOL_MODEL_PARAM_LABEL)
    private String customLandAerosol;
    private List<Integer> aerosolModels;
    
    private String productName = SynergyConstants.OUTPUT_PRODUCT_NAME_DEFAULT;
    private String productType = SynergyConstants.OUTPUT_PRODUCT_TYPE_DEFAULT;

    @Parameter(defaultValue="11",
               label="Pixels to average (n x n, with n odd number) for AOD retrieval",
               interval = "[1, 100]")
    private int aveBlock;


    private int rasterWidth;
    private int rasterHeight;
    
    private ArrayList<Band> merisBandList;
    private ArrayList<Band> aatsrBandListNad;
    private ArrayList<Band> aatsrBandListFwd;
    private ArrayList<RasterDataNode> merisGeometryBandList;
    private ArrayList<RasterDataNode> aatsrGeometryBandList;
    private float[] merisWvl;
    private float[] aatsrWvl;
    private float[] soilSurfSpec;
    private float[] vegSurfSpec;

    private float[] lutAlbedo;
    private float[] lutAot;
    private float[][][] lutSubsecMeris;
    private float[][][][] lutSubsecAatsr;
    
    //private int minNAve;
    //private float noDataVal;
    private Product synergyProduct;

    private final String virtNdviName = "synergyNdvi";
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

    private final String aerosolFlagCodingName = SynergyConstants.aerosolFlagCodingName;
    private final int oceanMask = SynergyConstants.oceanMask;
    private final int cloudyMask = SynergyConstants.cloudyMask;
    private final int successMask = SynergyConstants.successMask;
    private final int borderMask = SynergyConstants.borderMask;
    private final int negMetricMask = SynergyConstants.negMetricMask;
    private final int aotLowMask = SynergyConstants.aotLowMask;
    private final int errHighMask = SynergyConstants.errHighMask;


    @Override
    public void initialize() throws OperatorException {
       
        synergyProduct = sourceProduct;

        deactivateComputeTileMethod();

        aerosolModels = SynergyUtils.readAerosolLandModelNumbers(useCustomLandAerosol, customLandAerosol);

        merisBandList = new ArrayList<Band>();
        aatsrBandListNad = new ArrayList<Band>();
        aatsrBandListFwd = new ArrayList<Band>();
        merisGeometryBandList = new ArrayList<RasterDataNode>();
        aatsrGeometryBandList = new ArrayList<RasterDataNode>();

        scalingFactor = aveBlock;
        aveBlock /= 2;

        rasterWidth = synergyProduct.getSceneRasterWidth();
        rasterHeight = synergyProduct.getSceneRasterHeight();

        createTargetProduct();
        
        AerosolHelpers.getSpectralBandList(synergyProduct, SynergyConstants.INPUT_BANDS_PREFIX_MERIS,
                SynergyConstants.INPUT_BANDS_SUFFIX_MERIS,
                SynergyConstants.EXCLUDE_INPUT_BANDS_MERIS, merisBandList);
        AerosolHelpers.getSpectralBandList(synergyProduct, SynergyConstants.INPUT_BANDS_PREFIX_AATSR_NAD,
                SynergyConstants.INPUT_BANDS_SUFFIX_AATSR,
                SynergyConstants.EXCLUDE_INPUT_BANDS_AATSR, aatsrBandListNad);
        AerosolHelpers.getSpectralBandList(synergyProduct, SynergyConstants.INPUT_BANDS_PREFIX_AATSR_FWD,
                SynergyConstants.INPUT_BANDS_SUFFIX_AATSR,
                SynergyConstants.EXCLUDE_INPUT_BANDS_AATSR, aatsrBandListFwd);
        AerosolHelpers.getGeometryBandList(synergyProduct, "MERIS", merisGeometryBandList);
        AerosolHelpers.getGeometryBandList(synergyProduct, "AATSR", aatsrGeometryBandList);

        //QUESTION: should I really add a band to the input product????
        if (! synergyProduct.containsBand(virtNdviName)) {
            String vNdviExpression = createNdviExpression(merisBandList);
            VirtualBand virtNDVI = new VirtualBand(virtNdviName, ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight, vNdviExpression);
            synergyProduct.addBand(virtNDVI);
        }

        BandMathsOp bandArithmOp = BandMathsOp.createBooleanExpressionBand(landFlagExpression, synergyProduct);
        isLandBand = bandArithmOp.getTargetProduct().getBandAt(0);
        bandArithmOp = BandMathsOp.createBooleanExpressionBand(cloudyFlagExpression, synergyProduct);
        isCloudyBand = bandArithmOp.getTargetProduct().getBandAt(0);
        bandArithmOp = BandMathsOp.createBooleanExpressionBand(validFlagExpression, sourceProduct);
        isValidBand = bandArithmOp.getTargetProduct().getBandAt(0);

        merisWvl = new float[merisBandList.size()];
        aatsrWvl = new float[aatsrBandListNad.size()];

        readWavelength(merisBandList, merisWvl);
        readWavelength(aatsrBandListNad, aatsrWvl);

        if (soilSurfSpec == null) {
            if (soilSpecName.equals(SynergyConstants.SOIL_SPEC_PARAM_DEFAULT)) {
                soilSpecName = SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT  + File.separator +
                               SynergyConstants.SOIL_SPEC_PARAM_DEFAULT;
            }
            soilSurfSpec = new SurfaceSpec(soilSpecName, merisWvl).getSpec();
        }
        if (vegSurfSpec == null) {
            if (vegSpecName.equals(SynergyConstants.VEG_SPEC_PARAM_DEFAULT)) {
                vegSpecName = SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT  + File.separator +
                               SynergyConstants.VEG_SPEC_PARAM_DEFAULT;
            }
            vegSurfSpec = new SurfaceSpec(vegSpecName, merisWvl).getSpec();
        }
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {

        pm.beginTask("aerosol retrieval", aerosolModels.size() * targetRectangle.width * targetRectangle.height + 4);
        System.out.printf("   Aerosol Retrieval @ Tile %s\n", targetRectangle.toString());

        // define bigger Rectangle for binning of source tiles
        final int bigWidth = (int) ((2*aveBlock+1)*targetRectangle.getWidth());
        final int bigHeight = (int) ((2*aveBlock+1)*targetRectangle.getHeight());
        final int bigX = (int) ((2*aveBlock+1)*targetRectangle.getX());
        final int bigY = (int) ((2*aveBlock+1)*targetRectangle.getY());
        final Rectangle big = new Rectangle(bigX, bigY, bigWidth, bigHeight);

        // read source tiles
        final Tile[] merisTiles = getSpecTiles(merisBandList, big);

        Tile[][] aatsrTiles = new Tile[2][0];
        aatsrTiles[0] = getSpecTiles(aatsrBandListNad, big);
        aatsrTiles[1] = getSpecTiles(aatsrBandListFwd, big);
        
        final Tile[] geometryTiles = getGeometryTiles(merisGeometryBandList, aatsrGeometryBandList, big);
        
        final Tile pressureTile = getSourceTile(
                                synergyProduct.getTiePointGrid(SynergyConstants.INPUT_PRESSURE_BAND_NAME),
                                big,
                                SubProgressMonitor.create(pm, 1));
        final Tile ozoneTile = getSourceTile(
                                synergyProduct.getTiePointGrid(SynergyConstants.INPUT_OZONE_BAND_NAME),
                                big,
                                SubProgressMonitor.create(pm, 1));

        final Tile isValidTile = getSourceTile(isValidBand, big, SubProgressMonitor.create(pm, 1));
        final Tile isLandTile = getSourceTile(isLandBand, big, SubProgressMonitor.create(pm, 1));
        final Tile isCloudyTile = getSourceTile(isCloudyBand, big, SubProgressMonitor.create(pm, 1));

        // define target tiles
        Tile vNdviTile = getSourceTile(synergyProduct.getBand(virtNdviName), big, SubProgressMonitor.create(pm, 1));

        Tile aerosolTile = targetTiles.get(targetProduct.getBand(SynergyConstants.OUTPUT_AOT_BAND_NAME));
        Tile aerosolModelTile = targetTiles.get(targetProduct.getBand(SynergyConstants.OUTPUT_AOTMODEL_BAND_NAME));
        Tile aerosolErrTile = targetTiles.get(targetProduct.getBand(SynergyConstants.OUTPUT_AOTERR_BAND_NAME));
        Tile aerosolFlagTile = targetTiles.get(targetProduct.getBand(aerosolFlagCodingName));

        float[] merisToaReflec = new float[merisBandList.size()];
        float[][] aatsrToaReflec = new float[2][aatsrBandListNad.size()];

        ReflectanceBinLUT toaLut = null;

        final Aardvarc aardvarc = new Aardvarc(aatsrWvl, merisWvl);
        aardvarc.setDoAATSR(true);
        aardvarc.setDoMERIS(true);
        aardvarc.setSpecSoil(soilSurfSpec);
        aardvarc.setSpecVeg(vegSurfSpec);

        double[][] minErr = new double[targetRectangle.height][targetRectangle.width];

        // initialize target aot tile
        for (int iy=targetRectangle.y; iy<targetRectangle.y + targetRectangle.height; iy++) {
            for (int ix=targetRectangle.x; ix<targetRectangle.x + targetRectangle.width;  ix++) {
                aerosolTile.setSample(ix, iy, SynergyConstants.OUTPUT_AOT_BAND_NODATAVALUE);
                aerosolErrTile.setSample(ix, iy, SynergyConstants.OUTPUT_AOTERR_BAND_NODATAVALUE);
                aerosolModelTile.setSample(ix, iy, SynergyConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
                minErr[iy-targetRectangle.y][ix-targetRectangle.x] = SynergyConstants.OUTPUT_AOTERR_BAND_NODATAVALUE;
            }
        }

        for (int iAM = 0; iAM < aerosolModels.size(); iAM++) {
            
            final int aeroModel = aerosolModels.get(iAM).intValue();

            if ((toaLut == null) || (toaLut.getAerosolModel() != aeroModel)) {
                // provide complete LUT:
                toaLut = new ReflectanceBinLUT(auxdataPath, aeroModel, merisWvl, aatsrWvl);
                lutAlbedo = toaLut.getAlbDim();
                lutAot = toaLut.getAotDim();

                lutSubsecMeris = new float[merisWvl.length][lutAlbedo.length][lutAot.length];
                lutSubsecAatsr = new float[2][aatsrWvl.length][lutAlbedo.length][lutAot.length];
            }

            boolean validPixel = true;
            for (int iY = targetRectangle.y; iY < targetRectangle.y + targetRectangle.height; iY++) {
                for (int iX = targetRectangle.x; iX < targetRectangle.x + targetRectangle.width; iX++) {
                    checkForCancellation(pm);
                    final int iSrcX = (2*aveBlock+1)*iX + aveBlock;
                    final int iSrcY = (2*aveBlock+1)*iY + aveBlock;

                    int flagPixel = 0;
                    final boolean isBorder = (iSrcY+aveBlock >= rasterHeight || iSrcX+aveBlock >= rasterWidth);
                    if (isBorder) flagPixel |= borderMask;
                    
                    final boolean isLand = evaluateFlagPixel(isLandTile, iSrcX, iSrcY, true);
                    final boolean isCloudy = evaluateFlagPixel(isCloudyTile, iSrcX, iSrcY, false);
                    if (!isLand)  flagPixel |= oceanMask;
                    if (isCloudy) flagPixel |= cloudyMask;
                    // keep previous success
                    final boolean prevSuccess = aerosolFlagTile.getSampleBit(iX, iY, 2);
                    if (prevSuccess) flagPixel |= successMask;
                    
                    validPixel = isLand && !isCloudy;

                    float[] geometry = null;
                    float aveMerisPressure = 0;
                    float aveMerisOzone = 0;
                    float aveNdvi = 0;

                    if (validPixel) {
                        geometry = getAvePixel(geometryTiles, iSrcX, iSrcY, isValidTile, validPixel);

                        merisToaReflec = getAvePixel(merisTiles, iSrcX, iSrcY, isValidTile, validPixel);
                        aatsrToaReflec = getAvePixel(aatsrTiles, iSrcX, iSrcY, isValidTile, validPixel);

                        aveMerisPressure = getAvePixel(pressureTile, iSrcX, iSrcY, isValidTile, validPixel);
                        aveMerisOzone = getAvePixel(ozoneTile, iSrcX, iSrcY, isValidTile, validPixel);
                        aveNdvi = getAvePixel(vNdviTile, iSrcX, iSrcY, isValidTile, validPixel);
                    }
                    if (validPixel) {

                        final int iSza = 0; int iSaa = 1; int iVza = 2; int iVaa = 3;
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

                        // and these are the retrieval results:
                        boolean retrievalFailed = aardvarc.isFailed();
                        final float aot = aardvarc.getOptAOT();    // AOT (tau_550)
                        final float errMetric = aardvarc.getOptErr();    // E
                        final float retrievalError = aardvarc.getRetrievalErr();
                        retrievalFailed = retrievalFailed || aot < 1e-3 || (aot > 0.1 && (retrievalError/aot) > 5);
                        if (!retrievalFailed) flagPixel |= successMask;
                        if (aardvarc.isFailed()) flagPixel |= negMetricMask;
                        if (aot < 1e-5) flagPixel |= aotLowMask;
                        if (aot > 0.1 && (retrievalError/aot) > 5) flagPixel |= errHighMask;

                        final double errTemp = minErr[iY-targetRectangle.y][iX-targetRectangle.x];
                        if ( (Double.compare(errTemp, SynergyConstants.OUTPUT_AOTERR_BAND_NODATAVALUE) == 0
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
        final String redName = bandList.get(iRED).getName();
        final String irName = bandList.get(iIR).getName();

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
        
        targetProduct.setPreferredTileSize(128,128);
        setTargetProduct(targetProduct);
        
    }

    private void createTargetProductBands() {

        Band targetBand = new Band(SynergyConstants.OUTPUT_AOT_BAND_NAME, ProductData.TYPE_FLOAT32, downscaledRasterWidth, downscaledRasterHeight);
        targetBand.setDescription("best fitting aot Band");
        targetBand.setNoDataValue(SynergyConstants.OUTPUT_AOT_BAND_NODATAVALUE);
        targetBand.setNoDataValueUsed(SynergyConstants.OUTPUT_AOT_BAND_NODATAVALUE_USED);
        targetBand.setValidPixelExpression(targetBand.getName() + ">= 0 AND " + targetBand.getName() + "<= 1");
        targetBand.setUnit("dl");
        targetProduct.addBand(targetBand);

        targetBand = new Band(SynergyConstants.OUTPUT_AOTERR_BAND_NAME, ProductData.TYPE_FLOAT32, downscaledRasterWidth, downscaledRasterHeight);
        targetBand.setDescription("uncertainty Band of best fitting aot");
        targetBand.setNoDataValue(SynergyConstants.OUTPUT_AOTERR_BAND_NODATAVALUE);
        targetBand.setNoDataValueUsed(SynergyConstants.OUTPUT_AOTERR_BAND_NODATAVALUE_USED);
        targetBand.setUnit("dl");
        targetProduct.addBand(targetBand);

        targetBand = new Band(SynergyConstants.OUTPUT_AOTMODEL_BAND_NAME, ProductData.TYPE_INT32, downscaledRasterWidth, downscaledRasterHeight);
        targetBand.setDescription("aerosol model number Band");
        targetBand.setNoDataValue(SynergyConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE);
        targetBand.setNoDataValueUsed(SynergyConstants.OUTPUT_AOTMODEL_BAND_NODATAVALUE_USED);
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

    private float getAvePixel(Tile inputTile, int iTarX, int iTarY, Tile flags, boolean validPixel) {

        double value = 0;
        final double noDataValue = inputTile.getRasterDataNode().getNoDataValue();
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

    private float[] getAvePixel(Tile[] tileArr, int iTarX, int iTarY, Tile flags, boolean validPixel) {
        
        float[] valueArr = new float[tileArr.length];
        
        for (int i = 0; i < valueArr.length; i++) {
            valueArr[i] = getAvePixel(tileArr[i], iTarX, iTarY, flags, validPixel);
        }
        
        return valueArr;
    }

    private float[][] getAvePixel(Tile[][] tileArr2, int iTarX, int iTarY, Tile flags, boolean validPixel) {
        float[][] valueArr2 = new float[2][0];
        valueArr2[0] = getAvePixel(tileArr2[0], iTarX, iTarY, flags, validPixel);
        valueArr2[1] = getAvePixel(tileArr2[1], iTarX, iTarY, flags, validPixel);

        return valueArr2;
    }

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

    private void readWavelength(ArrayList<Band> bandList, float[] wvl) {
        for (int i = 0; i < bandList.size(); i++) {
            wvl[i] = bandList.get(i).getSpectralWavelength();
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RetrieveAerosolLandOp.class);
        }
    }
}
