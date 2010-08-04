/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.RasterDataNode;
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
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Operator for SDR retrieval over land within MERIS/AATSR Synergy project.
 *
 * @author Andreas Heckel, Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 17:54:34 +0000 (Mi, 27 Jan 2010) $
 *
 */
@OperatorMetadata(alias = "synergy.RetrieveSdrLand",
                  version = "1.1",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "Retrieve Surface Reflectance over Land.", internal=true)
public class RetrieveSdrLandOp extends Operator {

    @SourceProduct(alias = "aerosol",
                   label = "Name (Synergy aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product aerosolProduct;

    @SourceProduct(alias = "synergy",
                   label = "Name (Synergy aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product synergyProduct;

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

    @Parameter(defaultValue = "true", label = "dump pixel")
    boolean dumpPixel;
    @Parameter(defaultValue = "10", label = "dump pixel X")
    int dumpPixelX;
    @Parameter(defaultValue = "10", label = "dump pixel Y")
    int dumpPixelY;

    private String productName = "SYNERGY SDR";
    private String productType = "SYNERGY SDR";

    private ArrayList<Band> merisBandList;
    private ArrayList<Band> aatsrBandListNad;
    private ArrayList<Band> aatsrBandListFwd;
    private ArrayList<RasterDataNode> merisGeometryBandList;
    private ArrayList<RasterDataNode> aatsrGeometryBandList;
    private float[] merisWvl;
    private float[] aatsrWvl;
    private float[] merisBandWidth;
    private float[] aatsrBandWidth;

    private float[] soilSurfSpec;
    private float[] vegSurfSpec;

    private int rasterWidth;
    private int rasterHeight;

    private final String validFlagExpression = "( l1_flags_MERIS.LAND_OCEAN && " +
//            "!(cloud_flags_synergy.CLOUD || cloud_flags_synergy.CLOUD_FILLED || cloud_flags_synergy.SHADOW ))";
            "!(cloud_flags_synergy.CLOUD || cloud_flags_synergy.CLOUD_FILLED))";
    private Band validBand;

    private String[] sdrMerisBandNames;
    private String[][] sdrAatsrBandNames;


    @Override
    public void initialize() throws OperatorException {

        rasterWidth = aerosolProduct.getSceneRasterWidth();
        rasterHeight = aerosolProduct.getSceneRasterHeight();

        merisBandList = new ArrayList<Band>();
        aatsrBandListNad = new ArrayList<Band>();
        aatsrBandListFwd = new ArrayList<Band>();
        merisGeometryBandList = new ArrayList<RasterDataNode>();
        aatsrGeometryBandList = new ArrayList<RasterDataNode>();

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

        merisWvl = new float[merisBandList.size()];
        aatsrWvl = new float[aatsrBandListNad.size()];
        merisBandWidth = new float[merisBandList.size()];
        aatsrBandWidth = new float[aatsrBandListNad.size()];

        sdrMerisBandNames = new String[merisBandList.size()];
        sdrAatsrBandNames = new String[2][aatsrBandListNad.size()];

        readWavelengthBandw(merisBandList, merisWvl, merisBandWidth);
        readWavelengthBandw(aatsrBandListNad, aatsrWvl, aatsrBandWidth);

        if (soilSurfSpec == null) {
            soilSurfSpec = new SurfaceSpec(soilSpecName, merisWvl).getSpec();
        }
        if (vegSurfSpec == null) {
            vegSurfSpec = new SurfaceSpec(vegSpecName, merisWvl).getSpec();
        }

        final BandMathsOp validBandOp = BandMathsOp.createBooleanExpressionBand(validFlagExpression, aerosolProduct);
        validBand = validBandOp.getTargetProduct().getBandAt(0);

        createTargetProduct();
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {

        pm.beginTask("SDR retrieval", targetRectangle.width*targetRectangle.height);
        System.out.printf("   SDR Retrieval @ Tile %s\n", targetRectangle.toString());

        // read source tiles
        final Tile[] merisTiles = getSpecTiles(merisBandList, targetRectangle);

        Tile[][] aatsrTiles = new Tile[2][0];
        aatsrTiles[0] = getSpecTiles(aatsrBandListNad, targetRectangle);
        aatsrTiles[1] = getSpecTiles(aatsrBandListFwd, targetRectangle);

        final Tile[] geometryTiles = getGeometryTiles(merisGeometryBandList, aatsrGeometryBandList, targetRectangle);

        final Tile pressureTile = getSourceTile(synergyProduct.getTiePointGrid(SynergyConstants.INPUT_PRESSURE_BAND_NAME), targetRectangle, ProgressMonitor.NULL);
        final Tile ozoneTile = getSourceTile(synergyProduct.getTiePointGrid(SynergyConstants.INPUT_OZONE_BAND_NAME), targetRectangle, ProgressMonitor.NULL);
        final Tile aotTile = getSourceTile(aerosolProduct.getBand(SynergyConstants.OUTPUT_AOT_BAND_NAME+"_filter"), targetRectangle, ProgressMonitor.NULL);
        final Tile aeroModelTile = getSourceTile(aerosolProduct.getBand(SynergyConstants.OUTPUT_AOTMODEL_BAND_NAME+"_filled"), targetRectangle, ProgressMonitor.NULL);
        final Tile validPixelTile = getSourceTile(validBand, targetRectangle, ProgressMonitor.NULL);

        float[] merisToaReflec;
        float[][] aatsrToaReflec;

        final Aardvarc aardvarc = new Aardvarc(aatsrWvl, merisWvl);
        aardvarc.setSpecSoil(soilSurfSpec);
        aardvarc.setSpecVeg(vegSurfSpec);

        List<ReflectanceBinLUT> toaLutList = new ArrayList<ReflectanceBinLUT>();


        for (int iY = targetRectangle.y; iY < targetRectangle.y + targetRectangle.height; iY++) {
            for (int iX = targetRectangle.x; iX < targetRectangle.x + targetRectangle.width; iX++) {
                checkForCancellation(pm);
                final int aeroModel = aeroModelTile.getSampleInt(iX, iY);

                if (validPixelTile.getSampleBoolean(iX, iY) &&  isValidAeroModel(aeroModel)) {
                    final float[] geometry = getGeometries(geometryTiles, iX, iY);

                    merisToaReflec = getSpectra(merisTiles, iX, iY);
                    aatsrToaReflec = getSpectra(aatsrTiles, iX, iY);

                    ReflectanceBinLUT toaLut = setToaLut(aeroModel, toaLutList);
                    final float[] lutAlbedo = toaLut.getAlbDim();
                    final float[] lutAot = toaLut.getAotDim();

                    float[][][] lutSubsecMeris = new float[merisWvl.length][lutAlbedo.length][lutAot.length];
                    float[][][][] lutSubsecAatsr = new float[2][aatsrWvl.length][lutAlbedo.length][lutAot.length];

                    final float aveMerisPressure = pressureTile.getSampleFloat(iX, iY);
                    final float aveMerisOzone = ozoneTile.getSampleFloat(iX, iY);

                    // this setup is the same as for pure AOD retrieval
                    final int iSza = 0; int iSaa = 1; int iVza = 2; int iVaa = 3;
                    int offset = 0; // MERIS geometry
                    toaLut.subsecLUT("meris", aveMerisPressure, aveMerisOzone, geometry[iVza + offset], geometry[iVaa + offset],
                            geometry[iSza + offset], geometry[iSaa + offset], merisWvl, lutSubsecMeris);
                    offset = 4; // AATSR NADIR geometry
                    toaLut.subsecLUT("aatsr", aveMerisPressure, aveMerisOzone, geometry[iVza + offset], geometry[iVaa + offset],
                            geometry[iSza + offset], geometry[iSaa + offset], aatsrWvl, lutSubsecAatsr[0]);
                    offset = 8; // AATSR FWARD geometry
                    toaLut.subsecLUT("aatsr", aveMerisPressure, aveMerisOzone, geometry[iVza + offset], geometry[iVaa + offset],
                            geometry[iSza + offset], geometry[iSaa + offset], aatsrWvl, lutSubsecAatsr[1]);

                    aardvarc.setSza(geometry[0], geometry[4], geometry[8]);
                    aardvarc.setToaReflMeris(merisToaReflec);
                    aardvarc.setToaReflAatsr(aatsrToaReflec);
                    aardvarc.setLutReflAatsr(lutSubsecAatsr);
                    aardvarc.setLutReflMeris(lutSubsecMeris);
                    aardvarc.setAlbDim(lutAlbedo);
                    aardvarc.setAotDim(lutAot);

                    aardvarc.setNdvi(0.8f);
                    aardvarc.setSurfPres(aveMerisPressure);
                    if (dumpPixel && (iX == dumpPixelX) && (iY == dumpPixelY))
                        aardvarc.dumpParameter("p:/aardvarc_sdr.dump", aotTile.getSampleFloat(iX, iY));

                    // now get the SDRs...

                    //
                    // MERIS
                    //
                    float[] surfrefl = new float[merisWvl.length];
                    aardvarc.invLut(aotTile.getSampleFloat(iX, iY), lutSubsecMeris, merisToaReflec, surfrefl);
                    for (int i = 0; i < merisWvl.length; i++) {
                        Tile targetTile = targetTiles.get(targetProduct.getBand(sdrMerisBandNames[i]));
                        targetTile.setSample(iX, iY, surfrefl[i]);
                    }
                    //
                    // AATSR
                    //
                    float[][] surfreflAATSR = new float[2][aatsrWvl.length];
                    aardvarc.invLut(aotTile.getSampleFloat(iX, iY), lutSubsecAatsr, aatsrToaReflec, surfreflAATSR);
                    for (int i = 0; i < aatsrWvl.length; i++) {
                        Tile targetTile = targetTiles.get(targetProduct.getBand(sdrAatsrBandNames[0][i]));
                        targetTile.setSample(iX, iY, surfreflAATSR[0][i]);
                        targetTile = targetTiles.get(targetProduct.getBand(sdrAatsrBandNames[1][i]));
                        targetTile.setSample(iX, iY, surfreflAATSR[1][i]);
                    }
                } else {

                    for (int i = 0; i < merisWvl.length; i++) {
                        Tile targetTile = targetTiles.get(targetProduct.getBand(sdrMerisBandNames[i]));
                        targetTile.setSample(iX, iY, SynergyConstants.OUTPUT_SDR_BAND_NODATAVALUE);
                    }
                    for (int i = 0; i < aatsrWvl.length; i++) {
                        Tile targetTile = targetTiles.get(targetProduct.getBand(sdrAatsrBandNames[0][i]));
                        targetTile.setSample(iX, iY, SynergyConstants.OUTPUT_SDR_BAND_NODATAVALUE);
                        targetTile = targetTiles.get(targetProduct.getBand(sdrAatsrBandNames[1][i]));
                        targetTile.setSample(iX, iY, SynergyConstants.OUTPUT_SDR_BAND_NODATAVALUE);
                    }
                }
                pm.worked(1);
            }
        }
        pm.done();
    }

    private void createTargetProduct() {

        targetProduct = new Product(productName, productType, rasterWidth, rasterHeight);

        ProductUtils.copyMetadata(aerosolProduct, targetProduct);
        ProductUtils.copyGeoCoding(aerosolProduct, targetProduct);
        targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("latitude"));
        targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("longitude"));
        ProductUtils.copyTiePointGrids(aerosolProduct, targetProduct);
        ProductUtils.copyBitmaskDefsAndOverlays(aerosolProduct, targetProduct);
        ProductUtils.copyFlagBands(aerosolProduct, targetProduct);
        for (Band srcBand : aerosolProduct.getBands()) {
            if (!srcBand.isFlagBand()) {
                ProductUtils.copyBand(srcBand.getName(), aerosolProduct, targetProduct);
            }
            Band tarBand = targetProduct.getBand(srcBand.getName());
            tarBand.setSourceImage(srcBand.getSourceImage());
        }

        createTargetProductBands();
        targetProduct.setPreferredTileSize(128, 128);
        setTargetProduct(targetProduct);

    }

    private void createTargetProductBands() {

        Band targetBand;

        for (int iWL = 0; iWL < merisWvl.length; iWL++) {
            sdrMerisBandNames[iWL] = SynergyConstants.OUTPUT_SDR_BAND_NAME
                                     + String.format("_%d", (iWL + 1)) + "_MERIS";
            targetBand = new Band(sdrMerisBandNames[iWL], ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight);
            targetBand.setDescription(SynergyConstants.OUTPUT_SDR_BAND_DESCRIPTION);
            targetBand.setNoDataValue(SynergyConstants.OUTPUT_SDR_BAND_NODATAVALUE);
            targetBand.setNoDataValueUsed(SynergyConstants.OUTPUT_SDR_BAND_NODATAVALUE_USED);
            targetBand.setValidPixelExpression(sdrMerisBandNames[iWL] + ">= 0 AND " + sdrMerisBandNames[iWL] + "<= 1");
            //targetBand.setSpectralBandIndex(iWL);
            targetBand.setSpectralBandwidth(merisBandWidth[iWL]);
            targetBand.setSpectralWavelength(merisWvl[iWL]);
            targetProduct.addBand(targetBand);
        }

        String[] viewString = {"_nadir", "_fward"};
        for (int iView = 0; iView < 2; iView++) {
            for (int iWL = 0; iWL < aatsrWvl.length; iWL++) {
                sdrAatsrBandNames[iView][iWL] = SynergyConstants.OUTPUT_SDR_BAND_NAME
                                                + viewString[iView]
                                                + String.format("_%d", (iWL + 1)) + "_AATSR";
                targetBand = new Band(sdrAatsrBandNames[iView][iWL], ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight);
                targetBand.setDescription(SynergyConstants.OUTPUT_SDR_BAND_DESCRIPTION);
                targetBand.setNoDataValue(SynergyConstants.OUTPUT_SDR_BAND_NODATAVALUE);
                targetBand.setNoDataValueUsed(SynergyConstants.OUTPUT_SDR_BAND_NODATAVALUE_USED);
                targetBand.setValidPixelExpression(sdrAatsrBandNames[iView][iWL] + ">= 0 AND " + sdrAatsrBandNames[iView][iWL] + "<= 1");
                targetBand.setSpectralBandwidth(aatsrBandWidth[iWL]);
                targetBand.setSpectralWavelength(aatsrWvl[iWL]);
                targetProduct.addBand(targetBand);
            }
        }
    }

    private boolean isValidAeroModel(int modelNumber) {
        return (modelNumber >= 1 && modelNumber <= 40);
    }

    private ReflectanceBinLUT setToaLut(int aeroModel, List<ReflectanceBinLUT> toaLutList) {
        boolean lutExists = false;
        ReflectanceBinLUT toaLut = null;
        for (ReflectanceBinLUT lut:toaLutList) {
            if (lut.getAerosolModel() == aeroModel) {
                toaLut = lut;
                lutExists = true;
            }
        }
        // not yet in list --> provide complete LUT:
        if (!lutExists) {
            toaLut = new ReflectanceBinLUT(auxdataPath, aeroModel, merisWvl, aatsrWvl);
            toaLutList.add(toaLut);
        }

        return toaLut;
    }

    private float[] getGeometries(Tile[] geometryTiles, int iX, int iY) {

        float[] geometry = new float[geometryTiles.length];
        for (int ig = 0; ig < geometry.length; ig++) {
            geometry[ig] = geometryTiles[ig].getSampleFloat(iX, iY);
            if (geometryTiles[ig].getRasterDataNode().getName().matches(".*elev.*")) {
                geometry[ig] = 90.0f - geometry[ig];
            }
        }
        return geometry;
    }

    private float[][] getSpectra(Tile[][] specTiles, int iTarX, int iTarY) {
        float[][] spectrum = new float[2][0];
        spectrum[0] = getSpectra(specTiles[0], iTarX, iTarY);
        spectrum[1] = getSpectra(specTiles[1], iTarX, iTarY);

        return spectrum;
    }

    private float[] getSpectra(Tile[] specTiles, int iTarX, int iTarY) {
        return getGeometries(specTiles, iTarX, iTarY);
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

    private void readWavelengthBandw(ArrayList<Band> bandList, float[] wvl, float[] bwidth) {
        for (int i = 0; i < bandList.size(); i++) {
            wvl[i] = bandList.get(i).getSpectralWavelength();
            bwidth[i] = bandList.get(i).getSpectralBandwidth();
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RetrieveSdrLandOp.class);
        }
    }
}
