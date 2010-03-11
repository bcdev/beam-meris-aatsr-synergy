package org.esa.beam.synergy.operators;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.esa.beam.dataio.envisat.EnvisatConstants;
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
import org.esa.beam.util.ProductUtils;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.jnn.Jnn;
import com.bc.jnn.JnnException;
import com.bc.jnn.JnnNet;

@OperatorMetadata(alias = "synergy.ClassifyFeaturesNN",
                  version = "1.0-SNAPSHOT",
                  authors = "Jordi Munyoz-Mari, Luis Gomez-Chova",
                  copyright = "(c) 2008 by Brockmann Consult and IPL-UV",
                  description = "Internal neural network classifier for synergy products.")

public class ClassifyFeaturesNNOp extends Operator {

    @SourceProduct(alias = "source",
                   label ="Name (Synergy product)",
                   description = "Select a synergy product")
    private Product sourceProduct;
    
    @SourceProduct(alias = "features",
            label ="Name (Synergy feature product)",
            description = "Select a synergy feature product")
    private Product featProduct;
    
    @TargetProduct(description = "The target product result of the neural network classification")
    private Product targetProduct;
    
	@Parameter(defaultValue = "true",
            label = "Use the AATSR forward view when classifying",
            description = "Use the AATSR forward view when classifying.")
    private boolean useForwardView;

    @Parameter(defaultValue = "true",
            label = "Compute the cloud abundance",
            description = "Compute the cloud abundance.")
    private boolean computeCOT;
	
    // Source bands
    private transient Band[] bMeris;
    private transient Band[] bAatsrNadir;
    private transient Band[] bAatsrFward;
    // Source flag bands
    private transient Band l1Flags;
    private transient Band confid_flags_nadir;
    private transient Band confid_flags_fward;
    // Flags number
    private transient int flagLandMask;
    private transient int flagMerisInvalid;
    private transient int flagNadirInvalid;
    private transient int flagFwardInvalid;
    
    private transient Band tbNNcloudmask;
    private transient Band tbNNsnowmask;
    private transient Band tbNNcloud;
    private transient Band tbNNsnow;
    private transient Band tbNNabun;
    
    private Map<String,JnnNet> nnMap = new HashMap<String,JnnNet>
        (SynergyConstants.nn_synergy.length + SynergyConstants.nn_single.length);
    private Map<String,Band> tgtBandMap = new HashMap<String,Band>
        (SynergyConstants.nn_synergy.length + SynergyConstants.nn_single.length);

    /*
    final static double[] nn_weigths = { 0.088726197040742, 0.283243340585966, 0.149129120872092, 0.219486840936770 };
    final static double nn_threshold = 0.4;
    final static double[] nn_weigths_nadir = { 0.341411914064634, 0.403683637228184 };
    */
    
    @Override
    public void initialize() throws OperatorException {
        // Get bands and flags from synergy source product and make them ready
        prepareSourceBands();
        prepareFlags();
        
        // Load NNs
        try {
            nnMap.clear();
            // Synergy
            for (String s : SynergyConstants.nn_synergy) loadNeuralNet(s);
            // Single instruments
            for (String s : SynergyConstants.nn_single) loadNeuralNet(s);
        } catch (Exception e) {
            SynergyUtils.info("Error loading neural networks");
            e.printStackTrace();
        }

        // Construct target product
        final String type = sourceProduct.getProductType() + "_CLASS";
        targetProduct = new Product("Synergy_NN_outputs", type,
                    sourceProduct.getSceneRasterWidth(),
                    sourceProduct.getSceneRasterHeight());

        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());
        
        // Add target bands for nn outputs
        prepareTargetBands();
        
        tbNNcloudmask = targetProduct.addBand(SynergyConstants.B_CLOUDMASK, ProductData.TYPE_INT8);
        tbNNsnowmask = targetProduct.addBand(SynergyConstants.B_SNOWMASK, ProductData.TYPE_INT8);
        tbNNcloud = targetProduct.addBand(SynergyConstants.B_CLOUD_COMB, ProductData.TYPE_FLOAT32);
        tbNNcloud.setNoDataValue(SynergyConstants.NODATAVALUE);
        tbNNcloud.setNoDataValueUsed(true);
        tbNNsnow = targetProduct.addBand(SynergyConstants.B_SNOW_COMB, ProductData.TYPE_FLOAT32);
        tbNNsnow.setNoDataValue(SynergyConstants.NODATAVALUE);
        tbNNsnow.setNoDataValueUsed(true);
        if (computeCOT) {
        	tbNNabun = targetProduct.addBand(SynergyConstants.B_CLOUDINDEX, ProductData.TYPE_FLOAT32);
        	tbNNabun.setNoDataValue(SynergyConstants.NODATAVALUE);
        	tbNNabun.setNoDataValueUsed(true);
        }
        
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        targetProduct.setPreferredTileSize(32,32);
    }

    private void loadNeuralNet(final String netName) throws IOException, JnnException {
        // Check if already loaded
        if (nnMap.containsKey(netName)) return;        
        // Read it
        final InputStream inputStream = 
            ClassifyFeaturesNNOp.class.getResourceAsStream("nna/"+netName+".nna");
        final InputStreamReader reader = new InputStreamReader(inputStream);
        
        try {
            Jnn.setOptimizing(true);
            nnMap.put(netName, Jnn.readNna(reader));
        } finally {
            reader.close();
        }
    }
    
    private void prepareSourceBands() {
        
        final ArrayList<Band> merisList = new ArrayList<Band>(15);
        final ArrayList<Band> aatsrNadirList = new ArrayList<Band>(7);
        final ArrayList<Band> aatsrFwardList = new ArrayList<Band>(7);
        
        for (Band b : sourceProduct.getBands()) {
            if (b.getName().startsWith(SynergyConstants.MERIS_REFLECTANCE)) merisList.add(b);
            else if (b.getName().startsWith(SynergyConstants.AATSR_REFLEC_NADIR)) aatsrNadirList.add(b);
            else if (b.getName().startsWith(SynergyConstants.AATSR_BTEMP_NADIR)) aatsrNadirList.add(b);
            else if (b.getName().startsWith(SynergyConstants.AATSR_REFLEC_FWARD)) aatsrFwardList.add(b);
            else if (b.getName().startsWith(SynergyConstants.AATSR_BTEMP_FWARD)) aatsrFwardList.add(b);
            else if (b.getName().startsWith(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME)) l1Flags = b;
            else if (b.getName().startsWith(EnvisatConstants.AATSR_L1B_CONFID_FLAGS_NADIR_BAND_NAME)) confid_flags_nadir = b;
            else if (b.getName().startsWith(EnvisatConstants.AATSR_L1B_CONFID_FLAGS_FWARD_BAND_NAME)) confid_flags_fward = b;
        }
        
        if (merisList.isEmpty() || aatsrNadirList.isEmpty() || aatsrFwardList.isEmpty()) {
            throw new OperatorException("Unable to detect MERIS or AATSR bands");
        }
            
        bMeris      = merisList.toArray(new Band[merisList.size()]);
        bAatsrNadir = aatsrNadirList.toArray(new Band[aatsrNadirList.size()]);
        bAatsrFward = aatsrFwardList.toArray(new Band[aatsrFwardList.size()]);
        
        // Sort them by wavelength
        final SynergyUtils.BandComparator bandComp = new SynergyUtils.BandComparator();
        java.util.Arrays.sort(bMeris, bandComp);
        java.util.Arrays.sort(bAatsrNadir, bandComp);
        java.util.Arrays.sort(bAatsrFward, bandComp);
    }
    
    private void prepareFlags() {
        flagLandMask = l1Flags.getFlagCoding().getFlagMask(SynergyConstants.FLAG_LAND_OCEAN);
        flagMerisInvalid = l1Flags.getFlagCoding().getFlagMask(SynergyConstants.FLAG_INVALID);
        flagNadirInvalid = 
            confid_flags_nadir.getFlagCoding().getFlagMask(SynergyConstants.FLAG_SCAN_ABSENT) |
            confid_flags_nadir.getFlagCoding().getFlagMask(SynergyConstants.FLAG_ABSENT) |
            confid_flags_nadir.getFlagCoding().getFlagMask(SynergyConstants.FLAG_NOT_DECOMPR) |
            confid_flags_nadir.getFlagCoding().getFlagMask(SynergyConstants.FLAG_NO_SIGNAL) |
            confid_flags_nadir.getFlagCoding().getFlagMask(SynergyConstants.FLAG_OUT_OF_RANGE) |
            confid_flags_nadir.getFlagCoding().getFlagMask(SynergyConstants.FLAG_NO_CALIB_PARAM) |
            confid_flags_nadir.getFlagCoding().getFlagMask(SynergyConstants.FLAG_UNFILLED);
        flagFwardInvalid = 
            confid_flags_fward.getFlagCoding().getFlagMask(SynergyConstants.FLAG_SCAN_ABSENT) |
            confid_flags_fward.getFlagCoding().getFlagMask(SynergyConstants.FLAG_ABSENT) |
            confid_flags_fward.getFlagCoding().getFlagMask(SynergyConstants.FLAG_NOT_DECOMPR) |
            confid_flags_fward.getFlagCoding().getFlagMask(SynergyConstants.FLAG_NO_SIGNAL) |
            confid_flags_fward.getFlagCoding().getFlagMask(SynergyConstants.FLAG_OUT_OF_RANGE) |
            confid_flags_fward.getFlagCoding().getFlagMask(SynergyConstants.FLAG_NO_CALIB_PARAM) |
            confid_flags_fward.getFlagCoding().getFlagMask(SynergyConstants.FLAG_UNFILLED);
    }
    
    private void prepareTargetBands() {
        tgtBandMap.clear();
    	// Synergy NN outputs
        for (String s : SynergyConstants.nn_synergy) {
            if (s.contains("ocean")) continue; // Skip ocean names (land/ocean is shared)
            final String name = s.replace("land", "local");
            final Band band = targetProduct.addBand(name, ProductData.TYPE_FLOAT32);
            band.setDescription(name);
            band.setUnit("dl");
            tgtBandMap.put(name, band);
        }
        // Single instrument NN outputs
        for (String s : SynergyConstants.nn_single) {
            if (s.contains("ocean")) continue; // Skip ocean names (land/ocean is shared)
            final String name = s.replace("land", "local");
            final Band band = targetProduct.addBand(name, ProductData.TYPE_FLOAT32);
            band.setDescription(name);
            band.setUnit("dl");
            tgtBandMap.put(name, band);
        }
    }
    
    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle,
                                 ProgressMonitor pm) throws OperatorException {

        pm.beginTask("Processing frame...", targetRectangle.height);

        try {
            // Source tiles
            final Tile[] srcMeris = SynergyUtils.getSourceTiles(bMeris, targetRectangle, pm, this);
            final Tile[] srcAatsrNadir = SynergyUtils.getSourceTiles(bAatsrNadir, targetRectangle, pm, this);
            final Tile[] srcAatsrFward = SynergyUtils.getSourceTiles(bAatsrFward, targetRectangle, pm, this);
            
            // Target tiles
            final Tile tgtCloudmask = targetTiles.get(tbNNcloudmask);
            final Tile tgtSnowmask = targetTiles.get(tbNNsnowmask);
            final Tile tgtNNcloud = targetTiles.get(tbNNcloud);
            final Tile tgtNNsnow = targetTiles.get(tbNNsnow);
            Tile tgtNNabun = null;
            if (computeCOT) tgtNNabun = targetTiles.get(tbNNabun);
            
            // Flags tiles
            final Tile l1Tile = getSourceTile(l1Flags, targetRectangle, pm);
            final Tile confid_flags_nadir_tile = getSourceTile(confid_flags_nadir, targetRectangle, pm);
            final Tile confid_flags_fward_tile = getSourceTile(confid_flags_fward, targetRectangle, pm);
            
            // SZA tile
            final Tile szaTile = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME), targetRectangle, pm);

            // Source tiles map
            // (in NN inputs order, but caution, the HashMap DOES NOT store them in this order)
            HashMap<String,Tile> tileMap = new HashMap<String,Tile>(9);
            tileMap.put(SynergyConstants.F_WHITENESS_VIS,
                        getSourceTile(featProduct.getBand(SynergyConstants.F_WHITENESS_VIS), targetRectangle, pm));
            tileMap.put(SynergyConstants.F_WATER_VAPOR_ABS,
                        getSourceTile(featProduct.getBand(SynergyConstants.F_WATER_VAPOR_ABS), targetRectangle, pm));
            tileMap.put(SynergyConstants.F_SURF_PRESS,
                        getSourceTile(featProduct.getBand(SynergyConstants.F_SURF_PRESS), targetRectangle, pm));
            tileMap.put(SynergyConstants.F_443_754_RATIO,
                        getSourceTile(featProduct.getBand(SynergyConstants.F_443_754_RATIO), targetRectangle, pm));
            tileMap.put(SynergyConstants.F_761_754_865_RATIO,
                        getSourceTile(featProduct.getBand(SynergyConstants.F_761_754_865_RATIO), targetRectangle, pm));
            tileMap.put(SynergyConstants.F_865_890_NDSI,
                        getSourceTile(featProduct.getBand(SynergyConstants.F_865_890_NDSI), targetRectangle, pm));
            tileMap.put(SynergyConstants.F_11_12_DIFF,
                        getSourceTile(featProduct.getBand(SynergyConstants.F_11_12_DIFF), targetRectangle, pm));
            tileMap.put(SynergyConstants.F_555_1600_NDSI,
                        getSourceTile(featProduct.getBand(SynergyConstants.F_555_1600_NDSI), targetRectangle, pm));
            tileMap.put(SynergyConstants.F_870_670_RATIO,
                        getSourceTile(featProduct.getBand(SynergyConstants.F_870_670_RATIO), targetRectangle, pm));
            
            for (int y=targetRectangle.y; y<targetRectangle.y + targetRectangle.height; y++) {                
                for (int x=targetRectangle.x; x<targetRectangle.x + targetRectangle.width; x++) {
                    
                    checkForCancelation(pm);
                    
                    // Compute neural network only if:
                    // 1. Solar zenith angle is less than 80º (day only)
                    // 2. Both meris and aatsr bands have data values
                    if (szaTile.getSampleFloat(x, y) < 80.0 &&
                            bMeris[0].isPixelValid(x, y) && bAatsrFward[0].isPixelValid(x, y)) {
                    
                        final boolean isLand =
                            ((l1Tile.getSampleInt(x, y) & flagLandMask) == flagLandMask) ? true : false;
                        final boolean isMerisInvalid =
                            ((l1Tile.getSampleInt(x, y) & flagMerisInvalid) != 0) ? true : false;
                        final boolean isNadirInvalid =
                            ((confid_flags_nadir_tile.getSampleInt(x, y) & flagNadirInvalid) != 0) ? true : false;
                        final boolean isFwardInvalid =
                            ((confid_flags_fward_tile.getSampleInt(x, y) & flagFwardInvalid) != 0) ? true : false;
                        
                        // Synergy neural networks
                        for(String nnName : SynergyConstants.nn_synergy) {
                            // Skip either land or ocean
                            if (!isLand && nnName.contains("land")) continue;
                            if (isLand && nnName.contains("ocean")) continue;
                            if (!computeCOT && nnName.startsWith("index")) continue;
                            // Process NN 
                        	final double out = nnProcess(nnName, x, y,
                        	                             srcMeris, srcAatsrNadir, srcAatsrFward, tileMap);
                        	// Change 'land' and 'ocean' by 'local
                        	if (nnName.contains("land")) nnName = nnName.replace("land", "local");
                        	if (nnName.contains("ocean")) nnName = nnName.replace("ocean", "local");
                            targetTiles.get(tgtBandMap.get(nnName)).setSample(x, y, out);
                        }
                        
                        // Single instrument neural networks
                        for(String nnName : SynergyConstants.nn_single) {
                            // Skip either land or ocean
                            if (!isLand && nnName.contains("land")) continue;
                            if (isLand && nnName.contains("ocean")) continue;
                            // Process NN 
                            final double out = nnSIProcess(nnName, x, y,
                                                           srcMeris, srcAatsrNadir, srcAatsrFward, tileMap);
                        	// Change 'land' and 'ocean' by 'local
                        	if (nnName.contains("land")) nnName = nnName.replace("land", "local");
                        	if (nnName.contains("ocean")) nnName = nnName.replace("ocean", "local");
                            targetTiles.get(tgtBandMap.get(nnName)).setSample(x, y, out);
                        }
                        
                        // NN combinations
                        double nnCloud;
                        double nnSnow;
                        if (isNadirInvalid) {
                            // use only meris
                            nnCloud = 0.5 * (
                              targetTiles.get(tgtBandMap.get(SynergyConstants.nn_global_meris_nadir)).getSampleDouble(x,y) +
                              targetTiles.get(tgtBandMap.get(SynergyConstants.nn_local_meris_nadir)).getSampleDouble(x,y)
                            ); 
                            nnSnow =
                              targetTiles.get(tgtBandMap.get(SynergyConstants.nn_snow_meris_nadir)).getSampleDouble(x,y);
                        }
                        else {
                            if (isFwardInvalid || !useForwardView) {
                                if (isMerisInvalid) {
                                    // meris and fward invalid: no synergy, use nadir
                                    nnCloud = 0.5 * (
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_global_aatsr_nadir)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_local_aatsr_nadir)).getSampleDouble(x,y)
                                    ); 
                                    nnSnow =
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_snow_aatsr_nadir)).getSampleDouble(x,y);
                                }
                                else {
                                    // use meris/nadir synergy
                                    nnCloud = 0.5 * (
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_global_synergy_nadir)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_local_synergy_nadir)).getSampleDouble(x,y)
                                    );
                                    nnSnow =
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_snow_synergy_nadir)).getSampleDouble(x,y);
                                }
                            }
                            else {
                                if (isMerisInvalid) {
                                    // no meris, but we have dualview
                                    nnCloud = 0.25 * (
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_global_aatsr_nadir)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_local_aatsr_nadir)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_global_aatsr_dual)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_local_aatsr_dual)).getSampleDouble(x,y)
                                    );
                                    nnSnow = 0.5 * (
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_snow_aatsr_nadir)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_snow_aatsr_dual)).getSampleDouble(x,y)
                                    );
                                }
                                else {
                                    // All ok: synergy
                                    nnCloud = 0.25 * (
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_global_synergy_nadir)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_local_synergy_nadir)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_global_synergy_dual)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_local_synergy_dual)).getSampleDouble(x,y)
                                    ); 
                                    nnSnow = 0.5 * (
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_snow_synergy_nadir)).getSampleDouble(x,y) +
                                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_snow_synergy_dual)).getSampleDouble(x,y)
                                   );
                                }
                            }
                        }
                                            
                        // NN outputs
                        tgtNNcloud.setSample(x, y, nnCloud);
                        tgtNNsnow.setSample(x, y, nnSnow);
                        // Masks
                        tgtCloudmask.setSample(x, y, (nnCloud > 0.5) ? 1 : 0);
                        tgtSnowmask.setSample(x, y, (nnSnow > 0.5) ? 1 : 0);
    
                        if (computeCOT) {
    	                    double nnAbundance = 0.5 * (
    	                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_index_synergy_nadir)).getSampleDouble(x,y) +
    	                      targetTiles.get(tgtBandMap.get(SynergyConstants.nn_index_synergy_dual)).getSampleDouble(x,y)
    	                    );
    	                    tgtNNabun.setSample(x, y, nnAbundance);
                        }
                                        
                    }
                    
                    else {
                        
                        // Either the solar zenith angle is to high (night) or there is no data values
                        
                        // NN outputs
                        tgtNNcloud.setSample(x, y, tbNNcloud.getNoDataValue());
                        tgtNNsnow.setSample(x, y, tbNNsnow.getNoDataValue());
                        // Masks
                        tgtCloudmask.setSample(x, y, 0);
                        tgtSnowmask.setSample(x, y, 0);
                        // Cloud optical thickness
                        if (computeCOT) tgtNNabun.setSample(x, y, tbNNabun.getNoDataValue());
                        
                    }

                }
                
                pm.worked(1);
            }
        }
        finally {
            pm.done();
        }
    }
    
    /**
     * nnProces
     *   This function calls the neural network 'nnName'. It automatically organizes
     *   the NN inputs according NN name, and in proper order (the way they were trained).
     * 
     * @param nnName:      neural network name
     * @param x:           x coord
     * @param y:           y coord
     * @param tMeris:      meris tiles
     * @param tAatsrNadir: aatsr nadir tiles
     * @param tAatsFwardr: aatsr fward tiles
     * @param tileMap:     map of tiles containing several features
     * 
     * @return             the NN output
     */
    private double nnProcess(final String nnName, final int x, final int y,
            final Tile[] tMeris, final Tile[] tAatsrNadir, final Tile[] tAatsrFward,
            HashMap<String,Tile> tileMap) {
        
        final Double[] nnIn;
        final double[] nnOut = new double[1];
        ArrayList<Double> in = new ArrayList<Double>(40);
        
        // MERIS (except 11)
        for (int i=0; i<tMeris.length; i++) {
            if (i == 10) continue;
            in.add(tMeris[i].getSampleDouble(x, y));
        }
        // AATSR 1-3
        in.add(12, tAatsrNadir[2].getSampleDouble(x, y));
        in.add(6,  tAatsrNadir[1].getSampleDouble(x, y));
        in.add(4,  tAatsrNadir[0].getSampleDouble(x, y));        
        // AATSR Nadir 4-7, except 5
        for(int i=3; i<tAatsrNadir.length; i++) {
            if (i == 4) continue;
            in.add(tAatsrNadir[i].getSampleDouble(x, y));
        }
        // AATSR Fward all, except 5
        if (nnName.endsWith("_dual")) {
            for(int i=0; i<tAatsrFward.length; i++) {
                if (i == 4) continue;            
                in.add(tAatsrFward[i].getSampleDouble(x,y));
            }
        }
        // MERIS 11
        in.add(tMeris[10].getSampleDouble(x, y));
        
        // Features
        in.add(tileMap.get(SynergyConstants.F_WHITENESS_VIS).getSampleDouble(x, y));
        in.add(tileMap.get(SynergyConstants.F_WATER_VAPOR_ABS).getSampleDouble(x, y));
        in.add(tileMap.get(SynergyConstants.F_SURF_PRESS).getSampleDouble(x, y));
        in.add(tileMap.get(SynergyConstants.F_443_754_RATIO).getSampleDouble(x, y));
        in.add(tileMap.get(SynergyConstants.F_761_754_865_RATIO).getSampleDouble(x, y));
        in.add(tileMap.get(SynergyConstants.F_865_890_NDSI).getSampleDouble(x, y));
        in.add(tileMap.get(SynergyConstants.F_11_12_DIFF).getSampleDouble(x, y));
        in.add(tileMap.get(SynergyConstants.F_555_1600_NDSI).getSampleDouble(x, y));            
        in.add(tileMap.get(SynergyConstants.F_870_670_RATIO).getSampleDouble(x, y));
        
        // Finally we have all the NN inputs
        nnIn = in.toArray(new Double[in.size()]);
        
        // Copy the Double array to a double array
        final double[] nnInd = new double[nnIn.length];
        for (int i=0; i<nnIn.length; i++) nnInd[i] = nnIn[i];
        
        // Neural network process
        final JnnNet nn = nnMap.get(nnName);
        if (nn == null) throw new OperatorException("Network " +nnName+ " not loaded");
        nn.process(nnInd, nnOut);
        
        return nnOut[0];        
    }
    
    /**
     * nnSIProcess
     *   The same as {@nnProcess} but for SI (single instrument) neural networks.
     */
    private double nnSIProcess(final String nnName, final int x, final int y,
            final Tile[] tMeris, final Tile[] tAatsrNadir, final Tile[] tAatsrFward,
            final HashMap<String,Tile> tileMap) {
        
        final Double[] nnIn;
        final double[] nnOut = new double[1];
        ArrayList<Double> in = new ArrayList<Double>(40);
        
        // MERIS bands
        if (nnName.contains("_meris_")) {
            for(int i=0; i<tMeris.length; i++) {
                if (i == 10) continue;
                in.add(tMeris[i].getSampleDouble(x,y));                        
            }
            // Now add the M11
            in.add(tMeris[10].getSampleDouble(x,y));
            
            // Add features
            in.add(tileMap.get(SynergyConstants.F_WHITENESS_VIS).getSampleDouble(x, y));
            in.add(tileMap.get(SynergyConstants.F_WATER_VAPOR_ABS).getSampleDouble(x, y));
            in.add(tileMap.get(SynergyConstants.F_SURF_PRESS).getSampleDouble(x, y));
            in.add(tileMap.get(SynergyConstants.F_443_754_RATIO).getSampleDouble(x, y));
            in.add(tileMap.get(SynergyConstants.F_761_754_865_RATIO).getSampleDouble(x, y));
            in.add(tileMap.get(SynergyConstants.F_865_890_NDSI).getSampleDouble(x, y));
            in.add(tileMap.get(SynergyConstants.F_870_670_RATIO).getSampleDouble(x, y));
        }
        else { // Must be AATSR
            // Add nadir bands (except A5)
            for (int i=0; i<tAatsrNadir.length; i++) {
                if (i == 4) continue;
                in.add(tAatsrNadir[i].getSampleDouble(x, y));
            }
            if (nnName.endsWith("_dual")) {
                // Add fward bands (except Af5) 
                for (int i=0; i<tAatsrFward.length; i++) {
                    if (i == 4) continue;
                    in.add(tAatsrNadir[i].getSampleDouble(x, y));
                }
            }
            // And features
            in.add(tileMap.get(SynergyConstants.F_11_12_DIFF).getSampleDouble(x, y));
            in.add(tileMap.get(SynergyConstants.F_555_1600_NDSI).getSampleDouble(x, y));            
        }
        
        // Finally we have all the NN inputs
        nnIn = in.toArray(new Double[in.size()]);
        
        // Copy the Double array to a double array
        final double[] nnInd = new double[nnIn.length];
        for (int i=0; i<nnIn.length; i++) nnInd[i] = nnIn[i];
        
        // Neural network process
        final JnnNet nn = nnMap.get(nnName);
        if (nn == null) throw new OperatorException("Network " +nnName+ " not loaded");
        nn.process(nnInd, nnOut);
        
        return nnOut[0];        
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(ClassifyFeaturesNNOp.class);
        }
    } 
}
