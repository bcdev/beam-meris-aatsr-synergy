/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
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
import org.esa.beam.synergy.util.AerosolHelpers;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Operator for SDR retrieval over land within MERIS/AATSR Synergy project.
 *
 * @author Andreas Heckel, Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 18:54:34 +0100 (Mi, 27 Jan 2010) $
 *
 */
@OperatorMetadata(alias = "synergy.LandSurfaceRefl",
                  version = "1.0-SNAPSHOT",
                  authors = "Andreas Heckel, Olaf Danne",
                  copyright = "(c) 2009 by A. Heckel",
                  description = "Retrieve Surface Reflectance over Land.")
public class RetrieveSurfaceReflOp extends Operator {
    @SourceProduct(alias = "aerosol",
                   label = "Name (Synergy aerosol product)",
                   description = "Select a Synergy aerosol product.")
    private Product aerosolProduct;

    @SourceProduct(alias = "synergy",
                   label = "Name (Collocated MERIS AATSR product)",
                   description = "Select a collocated MERIS AATSR product.")
    private Product synergyProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    @Parameter(defaultValue = "true",
               description = "Compute ocean AODs",
               label = "Retrieve AODs over ocean")
    boolean computeOcean;

    @Parameter(defaultValue = "true",
               description = "Compute land AODs",
               label = "Retrieve AODs over land")
    boolean computeLand;

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
               defaultValue = RetrieveAerosolConstants.LUT_LAND_PATH_PARAM_DEFAULT,
               description = RetrieveAerosolConstants.LUT_PATH_PARAM_DESCRIPTION,
               label = RetrieveAerosolConstants.LUT_LAND_PATH_PARAM_LABEL)
    private String lutPath;


    private String productName = "SYNERGY SDR";
    private String productType = "SYNERGY SDR";

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

    private int rasterWidth;
    private int rasterHeight;

    private boolean validPixel;

    private final String validBandName = "validFlag";
    private List<ReflectanceBinLUT> toaLutList;

    @Override
    public void initialize() throws OperatorException {

        deactivateComputeTileMethod();

        merisBandList = new ArrayList<Band>();
        aatsrBandListNad = new ArrayList<Band>();
        aatsrBandListFwd = new ArrayList<Band>();
        merisGeometryBandList = new ArrayList<RasterDataNode>();
        aatsrGeometryBandList = new ArrayList<RasterDataNode>();
        toaLut = null;
        validPixel = true;

        toaLutList = new ArrayList<ReflectanceBinLUT>();

        rasterWidth = aerosolProduct.getSceneRasterWidth();
        rasterHeight = aerosolProduct.getSceneRasterHeight();

        getSpectralBandList(synergyProduct, RetrieveAerosolConstants.INPUT_BANDS_PREFIX_MERIS,
                RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_MERIS,
                RetrieveAerosolConstants.EXCLUDE_INPUT_BANDS_MERIS, merisBandList);
        getSpectralBandList(synergyProduct, RetrieveAerosolConstants.INPUT_BANDS_PREFIX_AATSR_NAD,
                RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR,
                RetrieveAerosolConstants.EXCLUDE_INPUT_BANDS_AATSR, aatsrBandListNad);
        getSpectralBandList(synergyProduct, RetrieveAerosolConstants.INPUT_BANDS_PREFIX_AATSR_FWD,
                RetrieveAerosolConstants.INPUT_BANDS_SUFFIX_AATSR,
                RetrieveAerosolConstants.EXCLUDE_INPUT_BANDS_AATSR, aatsrBandListFwd);
        getGeometryBandList(synergyProduct, "MERIS", merisGeometryBandList);
        getGeometryBandList(synergyProduct, "AATSR", aatsrGeometryBandList);

        merisWvl = new float[merisBandList.size()];
        aatsrWvl = new float[aatsrBandListNad.size()];
        merisToaReflec = new float[merisBandList.size()];
        aatsrToaReflec = new float[2][aatsrBandListNad.size()];

        readWavelength(merisBandList, merisWvl);
        readWavelength(aatsrBandListNad, aatsrWvl);

        aardvarc = new Aardvarc(aatsrWvl, merisWvl);


        if (soilSurfSpec == null) {
            soilSurfSpec = new SurfaceSpec(soilSpecName, merisWvl).getSpec();
        }
        if (vegSurfSpec == null) {
            vegSurfSpec = new SurfaceSpec(vegSpecName, merisWvl).getSpec();
        }
        aardvarc.setSpecSoil(soilSurfSpec);
        aardvarc.setSpecVeg(vegSurfSpec);

        createTargetProduct();
    }

    private void createTargetProduct() {

        targetProduct = new Product(productName, productType, rasterWidth, rasterHeight);

        ProductUtils.copyGeoCoding(synergyProduct, targetProduct);
        ProductUtils.copyMetadata(synergyProduct, targetProduct);
        ProductUtils.copyTiePointGrids(aerosolProduct, targetProduct);
        AerosolHelpers.copySynergyFlagBands(synergyProduct, targetProduct);

        ProductUtils.copyBand(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME, aerosolProduct, targetProduct);
        ProductUtils.copyBand(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME, aerosolProduct, targetProduct);
        ProductUtils.copyBand(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME, aerosolProduct, targetProduct);

        createTargetProductBands();

        targetProduct.setPreferredTileSize(128, 128);
        setTargetProduct(targetProduct);

    }

    private void createTargetProductBands() {

        Band targetBand;

        if (computeLand) {
            for (int iWL = 0; iWL < merisWvl.length; iWL++) {
                targetBand = new Band("SynSDR" + String.format("_%d", (iWL + 1)) + "_MERIS",
                                      ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight);
                targetBand.setDescription("Surface Reflectance Band");
                targetBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
                targetBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE_USED);
                targetBand.setValidPixelExpression(targetBand.getName() + ">= 0 AND " + targetBand.getName() + "<= 1");
                targetProduct.addBand(targetBand);
            }

            for (int iWL = 0; iWL < aatsrWvl.length; iWL++) {
                targetBand = new Band("SynSDR_nadir" + String.format("_%d", (iWL + 1)) + "_AATSR",
                                      ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight);
                targetBand.setDescription("Surface Reflectance Band");
                targetBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
                targetBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE_USED);
                targetBand.setValidPixelExpression(targetBand.getName() + ">= 0 AND " + targetBand.getName() + "<= 1");
                targetProduct.addBand(targetBand);
            }

            for (int iWL = 0; iWL < aatsrWvl.length; iWL++) {
                targetBand = new Band("SynSDR_fward" + String.format("_%d", (iWL + 1)) + "_AATSR",
                                      ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight);
                targetBand.setDescription("Surface Reflectance Band");
                targetBand.setNoDataValue(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
                targetBand.setNoDataValueUsed(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE_USED);
                targetBand.setValidPixelExpression(targetBand.getName() + ">= 0 AND " + targetBand.getName() + "<= 1");
                targetProduct.addBand(targetBand);
            }
        }
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {

         if (computeLand) {
            // read source tiles
            Tile[] merisTiles = getSpecTiles(merisBandList, targetRectangle);

            Tile[][] aatsrTiles = new Tile[2][0];
            aatsrTiles[0] = getSpecTiles(aatsrBandListNad, targetRectangle);
            aatsrTiles[1] = getSpecTiles(aatsrBandListFwd, targetRectangle);

            Tile[] geometryTiles = getGeometryTiles(merisGeometryBandList, aatsrGeometryBandList, targetRectangle);

            Tile pressureTile = getSourceTile(synergyProduct.getTiePointGrid("atm_press"), targetRectangle, ProgressMonitor.NULL);
            Tile aotTile = getSourceTile(aerosolProduct.getBand("aot"), targetRectangle, ProgressMonitor.NULL);
            Tile aeroModelTile = getSourceTile(aerosolProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME), targetRectangle, ProgressMonitor.NULL);

            for (int iY = targetRectangle.y; iY < targetRectangle.y + targetRectangle.height; iY++) {
                for (int iX = targetRectangle.x; iX < targetRectangle.x + targetRectangle.width; iX++) {
                    int aeroModel = aeroModelTile.getSampleInt(iX, iY);
                    validPixel = isValidAeroModel(aeroModel);

                    if (validPixel) {
                        float[] geometry = getGeometries(geometryTiles, iX, iY);

                        merisToaReflec = getSpectra(merisTiles, iX, iY);
                        aatsrToaReflec = getSpectra(aatsrTiles, iX, iY);
                        setToaLut(aeroModel);

                        float aveMerisPressure = pressureTile.getSampleFloat(iX, iY);

                        // this setup is the same as for pure AOD retrieval
                        int iSza = 0;
                        int iSaa = 1;
                        int iVza = 2;
                        int iVaa = 3;
                        int offset = 0; // MERIS geometry
                        toaLut.subsecLUT("meris", aveMerisPressure, geometry[iVza + offset], geometry[iVaa + offset],
                                geometry[iSza + offset], geometry[iSaa + offset], merisWvl, lutSubsecMeris);
                        offset = 4; // AATSR NADIR geometry
                        toaLut.subsecLUT("aatsr", aveMerisPressure, geometry[iVza + offset], geometry[iVaa + offset],
                                geometry[iSza + offset], geometry[iSaa + offset], aatsrWvl, lutSubsecAatsr[0]);
                        offset = 8; // AATSR FWARD geometry
                        toaLut.subsecLUT("aatsr", aveMerisPressure, geometry[iVza + offset], geometry[iVaa + offset],
                                geometry[iSza + offset], geometry[iSaa + offset], aatsrWvl, lutSubsecAatsr[1]);

                        aardvarc.setSza(geometry[0], geometry[4], geometry[8]);
                        aardvarc.setToaReflMeris(merisToaReflec);
                        aardvarc.setToaReflAatsr(aatsrToaReflec);
                        aardvarc.setLutReflAatsr(lutSubsecAatsr);
                        aardvarc.setLutReflMeris(lutSubsecMeris);
                        aardvarc.setAlbDim(lutAlbedo);
                        aardvarc.setAotDim(lutAot);

                        // now get the SDRs...

                        //
                        // MERIS
                        //
                        toaLut.subsecLUT("meris", aveMerisPressure, geometry[iVza + offset], geometry[iVaa + offset],
                                geometry[iSza + offset], geometry[iSaa + offset], merisWvl, lutSubsecMeris);
                        float[] toaReflSpec = new float[merisWvl.length];
                        for (int i = 0; i < toaReflSpec.length; i++) {
                            toaReflSpec[i] = merisTiles[i].getSampleFloat(iX, iY);
                        }
                        float[] surfrefl = new float[merisWvl.length];
                        aardvarc.invLut(aotTile.getSampleFloat(iX, iY), lutSubsecMeris, toaReflSpec, surfrefl);
                        for (int i = 0; i < toaReflSpec.length; i++) {
                            Tile targetTile = targetTiles.get(targetProduct.getBand("SynSDR" + String.format("_%d", (i + 1)) + "_MERIS"));
                            targetTile.setSample(iX, iY, surfrefl[i]);
                        }
                        //
                        // AATSR
                        //
                        offset = 4; // AATSR NADIR geometry
                        toaLut.subsecLUT("aatsr", aveMerisPressure, geometry[iVza + offset], geometry[iVaa + offset],
                                geometry[iSza + offset], geometry[iSaa + offset], aatsrWvl, lutSubsecAatsr[0]);
                        offset = 8; // AATSR FWARD geometry
                        toaLut.subsecLUT("aatsr", aveMerisPressure, geometry[iVza + offset], geometry[iVaa + offset],
                                geometry[iSza + offset], geometry[iSaa + offset], aatsrWvl, lutSubsecAatsr[1]);
                        float[][] toaReflSpecAATSR = new float[2][aatsrWvl.length];
                        for (int i = 0; i < aatsrWvl.length; i++) {
                            toaReflSpecAATSR[0][i] = aatsrTiles[0][i].getSampleFloat(iX, iY);
                            toaReflSpecAATSR[1][i] = aatsrTiles[1][i].getSampleFloat(iX, iY);
                        }
                        float[][] surfreflAATSR = new float[2][aatsrWvl.length];
                        aardvarc.invLut(aotTile.getSampleFloat(iX, iY), lutSubsecAatsr, toaReflSpecAATSR, surfreflAATSR);
                        for (int i = 0; i < aatsrWvl.length; i++) {
                            Tile targetTile = targetTiles.get(targetProduct.getBand("SynSDR_nadir" + String.format("_%d", (i + 1)) + "_AATSR"));
                            targetTile.setSample(iX, iY, surfreflAATSR[0][i]);
                            targetTile = targetTiles.get(targetProduct.getBand("SynSDR_fward" + String.format("_%d", (i + 1)) + "_AATSR"));
                            targetTile.setSample(iX, iY, surfreflAATSR[1][i]);
                        }
                    } else {

                        for (int i = 0; i < merisWvl.length; i++) {
                            Tile targetTile = targetTiles.get(targetProduct.getBand("SynSDR" + String.format("_%d", (i + 1)) + "_MERIS"));
                            targetTile.setSample(iX, iY, RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
                        }
                        for (int i = 0; i < aatsrWvl.length; i++) {
                            Tile targetTile = targetTiles.get(targetProduct.getBand("SynSDR_nadir" + String.format("_%d", (i + 1)) + "_AATSR"));
                            targetTile.setSample(iX, iY, RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
                            targetTile = targetTiles.get(targetProduct.getBand("SynSDR_fward" + String.format("_%d", (i + 1)) + "_AATSR"));
                            targetTile.setSample(iX, iY, RetrieveAerosolConstants.OUTPUT_AOT_BAND_NODATAVALUE);
                        }
                    }
                    pm.worked(1);
                }
            }
        }
        // we need these because target product is slightly smaller than Synergy input product due to integer averaging
        // --> the ProductUtils.copyBand and ProductUtils.copyFlagBands cannot be used
        writeAerosolBands(targetTiles, pm, targetRectangle);
        writeSynergyFlagBands(targetTiles, pm, targetRectangle);
    }

    private void writeAerosolBands(Map<Band, Tile> targetTiles, ProgressMonitor pm, Rectangle rectangle) {

        Tile aotTile = getSourceTile(aerosolProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME), rectangle, pm);
        Tile aotErrorTile = getSourceTile(aerosolProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME), rectangle, pm);
        Tile aerosolModelTile = null;
        if (computeLand) {
            aerosolModelTile = getSourceTile(aerosolProduct.getBand(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME), rectangle, pm);
        }

        for (Band targetBand : targetProduct.getBands()) {
            Tile targetTile = targetTiles.get(targetBand);
            if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOT_BAND_NAME)) {
                for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                    for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                        targetTile.setSample(x, y, aotTile.getSampleFloat(x, y));
                    }
                }
            }
            if (targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOTERR_BAND_NAME)) {
                for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                    for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                        targetTile.setSample(x, y, aotErrorTile.getSampleFloat(x, y));
                    }
                }
            }
            if (computeLand && targetBand.getName().equals(RetrieveAerosolConstants.OUTPUT_AOTMODEL_BAND_NAME)) {
                for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                    for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                        targetTile.setSample(x, y, aerosolModelTile.getSampleInt(x, y));
                    }
                }
            }
        }
    }

    private void writeSynergyFlagBands(Map<Band, Tile> targetTiles, ProgressMonitor pm, Rectangle rectangle) {

        // we need these because Synergy collocated product is slightly larger than target product due to integer averaging
        int xMax = synergyProduct.getSceneRasterWidth();
        int yMax = synergyProduct.getSceneRasterHeight();

        for (Band targetBand : targetProduct.getBands()) {
            if (targetBand.isFlagBand()) {
                Tile targetTile = targetTiles.get(targetBand);

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
                            for (int i = 0; i < aatsrConfidNadirFlagCoding.getNumAttributes(); i++) {
                                targetTile.setSample(x, y, i, aatsrConfidFlagNadirTile.getSampleBit(x, y, i));
                            }
                        }
                        if (targetBand.getName().equals(RetrieveAerosolConstants.CONFID_FWARD_FLAGS_AATSR)) {
                            for (int i = 0; i < aatsrConfidFwardFlagCoding.getNumAttributes(); i++) {
                                targetTile.setSample(x, y, i, aatsrConfidFlagFwardTile.getSampleBit(x, y, i));
                            }
                        }
                        if (targetBand.getName().equals(RetrieveAerosolConstants.CLOUD_NADIR_FLAGS_AATSR)) {
                            for (int i = 0; i < aatsrCloudNadirFlagCoding.getNumAttributes(); i++) {
                                targetTile.setSample(x, y, i, aatsrCloudFlagNadirTile.getSampleBit(x, y, i));
                            }
                        }
                        if (targetBand.getName().equals(RetrieveAerosolConstants.CLOUD_FWARD_FLAGS_AATSR)) {
                            for (int i = 0; i < aatsrCloudFwardFlagCoding.getNumAttributes(); i++) {
                                targetTile.setSample(x, y, i, aatsrCloudFlagFwardTile.getSampleBit(x, y, i));
                            }
                        }
                        if (targetBand.getName().equals(RetrieveAerosolConstants.L1_FLAGS_MERIS)) {
                            for (int i = 0; i < merisL1FlagCoding.getNumAttributes(); i++) {
                                targetTile.setSample(x, y, i, merisL1FlagsTile.getSampleBit(x, y, i));
                            }
                        }
                        if (targetBand.getName().equals(RetrieveAerosolConstants.CLOUD_FLAG_MERIS)) {
                            for (int i = 0; i < merisCloudFlagCoding.getNumAttributes(); i++) {
                                targetTile.setSample(x, y, i, merisCloudFlagTile.getSampleBit(x, y, i));
                            }
                        }
                        pm.worked(1);
                    }
                }
            }
        }
    }

    private boolean isValidAeroModel(int modelNumber) {
        return (modelNumber >= 1 && modelNumber <= 40);
    }

    private void setToaLut(int aeroModel) {
        boolean lutExists = false;
        for (ReflectanceBinLUT lut:toaLutList) {
            if (lut.getAerosolModel() == aeroModel) {
                toaLut = lut;
                lutExists = true;
            }
        }
        // not yet in list --> provide complete LUT:
        if (!lutExists) {
            toaLut = new ReflectanceBinLUT(lutPath, aeroModel, merisWvl, aatsrWvl);
            toaLutList.add(toaLut);
        }
        lutAlbedo = toaLut.getAlbDim();
        lutAot = toaLut.getAotDim();

        lutSubsecMeris = new float[merisWvl.length][lutAlbedo.length][lutAot.length];
        lutSubsecAatsr = new float[2][aatsrWvl.length][lutAlbedo.length][lutAot.length];
    }

    private float[] getGeometries(Tile[] geometryTiles, int iX, int iY) {

        float[] geometry = new float[geometryTiles.length];
        for (int ig = 0; ig < geometry.length; ig++) {
            geometry[ig] = geometryTiles[ig].getSampleFloat(iX, iY);
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

    private void readWavelength(ArrayList<Band> bandList, float[] wvl) {
        for (int i = 0; i < bandList.size(); i++) {
            wvl[i] = bandList.get(i).getSpectralWavelength();
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RetrieveSurfaceReflOp.class);
        }
    }
}
