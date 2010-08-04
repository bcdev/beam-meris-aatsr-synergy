package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.jnn.Jnn;
import com.bc.jnn.JnnException;
import com.bc.jnn.JnnNet;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.meris.l2auxdata.L2AuxData;
import org.esa.beam.meris.l2auxdata.L2AuxdataProvider;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.synergy.util.SynergyUtils;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.MathUtils;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Map;

import static java.lang.Math.*;

/**
 * Operator for extracting cloud features from TOA reflectances.
 *
 * @author Ralf Quast, Jordi Munyoz
 * @version $Revision: 8155 $ $Date: 2010-01-29 17:30:33 +0100 (vie, 29 ene 2010) $
 */
@OperatorMetadata(alias = "synergy.ExtractFeatures",
                  version = "1.1",
                  authors = "Ralf Quast, Olaf Danne, Jordi Munoz-Mari, Luis Gomez-Chova",
                  copyright = "(c) 2008-09 by Brockmann Consult and IPL-UV",
                  description = "Extracts cloud features from Synergy TOA reflectance products.", internal=true)
public class ExtractFeaturesOp extends Operator {

	private static final double INVERSE_SCALING_FACTOR = 10000.0;
	
    @SourceProduct(alias = "source",
                   label = "Name (Synergy product)",
                   description = "Select a Synergy product")
    private Product sourceProduct;
    
	@TargetProduct(description = "The target product. Contains all extracted features.")
    private Product targetProduct;

    @Parameter(defaultValue = "true",
               label = "Extract VIS brightness and whiteness")
    boolean extractVis;

    @Parameter(defaultValue = "false",
               label = "Extract NIR brightness and whiteness")
    boolean extractNir;

    @Parameter(defaultValue = "true",
               label = "Extract water vapour absorption feature")
    boolean extractWv;
    
    @Parameter(defaultValue = "true",
               label = "Extract 761-754-865 ratio")
    boolean extract_761_754_865_ratio;

    @Parameter(defaultValue = "true",
               label = "Extract 870-670 ratio")
    boolean extract_870_670_ratio;
    
    @Parameter(defaultValue = "true",
               label = "Extract 443-754 ratio")
    boolean extract_443_754_ratio;    
    
    @Parameter(defaultValue = "true",
               label = "Extract 11-12 difference")
    boolean extract_11_12_diff;

    @Parameter(defaultValue = "true",
               label = "Extract 865-890 NDSI")
    boolean extract_865_890_ndsi;
    
    @Parameter(defaultValue = "true",
               label = "Extract 555-1600 NDSI")
    boolean extract_555_1600_ndsi;
    
    @Parameter(defaultValue = "true",
               label = "Extract surface pressure")
    boolean extract_spr;

    @Parameter(defaultValue = "true",
               label = "Apply strayligth correction to surface pressure extraction",
               description="If 'true' the algorithm will apply straylight correction.")
    public boolean straylightCorr;
    
    /*
    @Parameter(defaultValue = "true",
               label = "Use a tropical or a USS atmosphere model to surface pressure extraction",
               description="If 'true' the algorithm will apply Tropical instead of USS atmosphere model.")
               */
    public boolean tropicalAtmosphere = true;

    @Parameter(defaultValue = "false",
               label = "Extract surface pressure difference")
    boolean extract_sprd;
    
    @Parameter(defaultValue = "true",
               label = "Extract band with coast line")
    boolean extract_coastline;
    
    /*
    @Parameter(defaultValue = "true",
               label = "Extract thermal features")
    boolean extractThermalFeatures;

    @Parameter(defaultValue = "false",
               label = "Extract oxygen absorption feature")
    boolean extractO2;
    */

    // Target product bands
    private transient Band visBr;
    private transient Band visWh;
    private transient Band nirBr;
    private transient Band nirWh;
    private transient Band wvabs;
    private transient Band b761_754_865_ratio;
    private transient Band b443_754_ratio;
    private transient Band b870_670_ratio;
    private transient Band b11_12_diff;
    private transient Band b865_890_ndsi;
    private transient Band b555_1600_ndsi;
    private transient Band sprBand;
    private transient Band sprdBand;
    private transient Band coastline;
    
    // Vars for the SPR FUB method
    private static final String NEURAL_NET_TRP_FILE_NAME = "SP_FUB_trp.nna";
    private static final String NEURAL_NET_USS_FILE_NAME = "SP_FUB_uss.nna";  // changed to US standard atm., 18/03/2009
    private static final String STRAYLIGHT_COEFF_FILE_NAME = "stray_ratio.d";
    private static final String STRAYLIGHT_CORR_WAVELENGTH_FILE_NAME = "lambda.d";
    private float[] straylightCoefficients = new float[L2AuxData.RR_DETECTOR_COUNT]; // reduced resolution only!
    private float[] straylightCorrWavelengths = new float[L2AuxData.RR_DETECTOR_COUNT];
    private JnnNet neuralNet;
	private L2AuxData auxData;

    // Synergy source product bands
    private transient Band[] merisRefBands;
    private transient Band[] merisRadBands;
    private transient Band[] aatsrNadirBands;
    private transient Band[] aatsrFwardBands;
    
    private transient Band demBand = null;
    private transient Band l1fBand = null;

    private transient TiePointGrid demAltTpg = null;
    private transient TiePointGrid atmPreTpg = null;

    // Bands and tie point grids for the SPR extraction     
    private transient Band detIdxBand = null;
    private transient TiePointGrid szaTpg = null;
    private transient TiePointGrid saaTpg = null;
    private transient TiePointGrid vzaTpg = null;
    private transient TiePointGrid vaaTpg = null;
    
    @Override
    public void initialize() throws OperatorException {
        
        // Setup MERIS, AATSR, L1F, detector index and DEM elevation bands
        prepareBands();
        // Prepare L2 auxiliary data
        initAuxData();
        
        // Tie points
        szaTpg = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME);
        saaTpg = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME);
        vzaTpg = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME);
        vaaTpg = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME);
        // For SPRD extraction
        demAltTpg = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME);
        atmPreTpg = sourceProduct.getTiePointGrid("atm_press");

        // Check bands and TPGs
        if ((szaTpg == null|| saaTpg == null || vzaTpg == null || vaaTpg == null) && extract_spr) {
            SynergyUtils.info("Sun/View zenith or azimuth angles not available, extraction of surface pressure disabled");
            extract_spr = false;
        }
        if (detIdxBand == null && extract_spr) {
            SynergyUtils.info("Detector index band not found, extraction of surface pressure disabled");
            extract_spr = false;            
        }
        if (!extract_spr && extract_sprd) {
            SynergyUtils.info("Surface pressure not extracted, extraction of surface pressure difference disabled");
            extract_sprd = false;
        }
        if ((demBand == null || demAltTpg == null || atmPreTpg == null) && extract_sprd) {
            SynergyUtils.info("DEM elevation or tie points not found, extraction of surface pressure difference disabled");
            extract_sprd = false;
        }
        if (l1fBand == null) {
            SynergyUtils.info("L1FLAGS not found, extraction of coast line disabled");
            extract_coastline = false;
        }
        
        // Construct target product
        final String type = sourceProduct.getProductType() + "_FEAT";
        targetProduct = new Product("Synergy_FEATURES", type,
        			sourceProduct.getSceneRasterWidth(),
        			sourceProduct.getSceneRasterHeight());
        targetProduct.setDescription("SYNERGY extracted features product");

        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());
        
        if (extractVis) {
            visBr = targetProduct.addBand(SynergyConstants.F_BRIGHTENESS_VIS, ProductData.TYPE_INT16);
            visBr.setDescription("Brightness for visual bands");
            visBr.setUnit("dl");
            visBr.setScalingFactor(1.0 / INVERSE_SCALING_FACTOR);
    
            visWh = targetProduct.addBand(SynergyConstants.F_WHITENESS_VIS, ProductData.TYPE_INT16);
            visWh.setDescription("Whiteness for visual bands");
            visWh.setUnit("dl");
            visWh.setScalingFactor(1.0 / INVERSE_SCALING_FACTOR);
        }        
        if (extractNir) {
        	nirBr = targetProduct.addBand(SynergyConstants.F_BRIGHTENESS_NIR, ProductData.TYPE_INT16);
            nirBr.setDescription("Brightness for NIR bands");
            nirBr.setUnit("dl");
            nirBr.setScalingFactor(1.0 / INVERSE_SCALING_FACTOR);

        	nirWh = targetProduct.addBand(SynergyConstants.F_WHITENESS_NIR, ProductData.TYPE_INT16);
            nirWh.setDescription("Whiteness for NIR bands");
            nirWh.setUnit("dl");
            nirWh.setScalingFactor(1.0 / INVERSE_SCALING_FACTOR);
        }
        if (extractWv) {
            wvabs = targetProduct.addBand(SynergyConstants.F_WATER_VAPOR_ABS, ProductData.TYPE_FLOAT32);
            wvabs.setDescription("Water Vapor Absorption");
            wvabs.setUnit("dl");
        }
        if (extract_761_754_865_ratio) {
            b761_754_865_ratio = targetProduct.addBand(SynergyConstants.F_761_754_865_RATIO, ProductData.TYPE_FLOAT32);
            b761_754_865_ratio.setDescription("761-754-865 ratio");
            b761_754_865_ratio.setUnit("dl");
        }
        if (extract_443_754_ratio) {
            b443_754_ratio = targetProduct.addBand(SynergyConstants.F_443_754_RATIO, ProductData.TYPE_FLOAT32);
            b443_754_ratio.setDescription("443-754 ratio");
            b443_754_ratio.setUnit("dl");
        }
        if (extract_870_670_ratio) {
            b870_670_ratio = targetProduct.addBand(SynergyConstants.F_870_670_RATIO, ProductData.TYPE_FLOAT32);
            b870_670_ratio.setDescription("870-670 ratio");
            b870_670_ratio.setUnit("dl");
        }
        if (extract_11_12_diff) {
            b11_12_diff = targetProduct.addBand(SynergyConstants.F_11_12_DIFF, ProductData.TYPE_FLOAT32);
            b11_12_diff.setDescription("11-12 difference");
            b11_12_diff.setUnit("K");
        }
        if (extract_865_890_ndsi) {
            b865_890_ndsi = targetProduct.addBand(SynergyConstants.F_865_890_NDSI, ProductData.TYPE_FLOAT32);
            b865_890_ndsi.setDescription("865-890 NDSI");
            b865_890_ndsi.setUnit("dl");
        }
        if (extract_555_1600_ndsi) {
            b555_1600_ndsi = targetProduct.addBand(SynergyConstants.F_555_1600_NDSI, ProductData.TYPE_FLOAT32);
            b555_1600_ndsi.setDescription("555-1600 NDSI");
            b555_1600_ndsi.setUnit("dl");
        }
        if (extract_spr) {
            initSPR();
            sprBand = targetProduct.addBand(SynergyConstants.F_SURF_PRESS, ProductData.TYPE_FLOAT32);
            sprBand.setDescription("Surface pressure");
            sprBand.setUnit("hPa");
            
            if (extract_sprd) {
                sprdBand = targetProduct.addBand(SynergyConstants.F_SURF_PRESS_DIFF, ProductData.TYPE_FLOAT32);
                sprdBand.setDescription("Surface pressure difference");
                sprdBand.setUnit("hPa");
            }
        }
        if (extract_coastline) {
            coastline = targetProduct.addBand(SynergyConstants.F_COASTLINE, ProductData.TYPE_INT8);
            coastline.setDescription("Coast line band");
            coastline.setUnit("dl");
        }
        
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        targetProduct.setPreferredTileSize(32,32);
    }

    private void prepareBands() {
        final ArrayList<Band> merisRefList = new ArrayList<Band>(15);
        final ArrayList<Band> merisRadList = new ArrayList<Band>(15);
        final ArrayList<Band> aatsrNadirList = new ArrayList<Band>(7);
        final ArrayList<Band> aatsrFwardList = new ArrayList<Band>(7);
        
        for (Band b : sourceProduct.getBands()) {
            if (b.getName().startsWith(SynergyConstants.MERIS_REFLECTANCE)) merisRefList.add(b);
            else if (b.getName().startsWith(SynergyConstants.MERIS_RADIANCE)) merisRadList.add(b);
            else if (b.getName().startsWith(SynergyConstants.AATSR_REFLEC_NADIR)) aatsrNadirList.add(b);
            else if (b.getName().startsWith(SynergyConstants.AATSR_BTEMP_NADIR)) aatsrNadirList.add(b);
            else if (b.getName().startsWith(SynergyConstants.AATSR_REFLEC_FWARD)) aatsrFwardList.add(b);
            else if (b.getName().startsWith(SynergyConstants.AATSR_BTEMP_FWARD)) aatsrFwardList.add(b);
            else if (b.getName().startsWith(SynergyConstants.DEM_ELEVATION)) demBand = b;
            else if (b.getName().startsWith(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME)) l1fBand = b;
            else if (b.getName().startsWith(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME)) detIdxBand = b;
        }
        
        if (merisRefList.size() != EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS &&
            merisRadList.size() != EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS) {
            throw new OperatorException("Unable to detect required MERIS radiance or reflectance bands");
        }
        
        if (aatsrNadirList.size() != EnvisatConstants.AATSR_WAVELENGTHS.length ||
            aatsrFwardList.size() != EnvisatConstants.AATSR_WAVELENGTHS.length) {
            throw new OperatorException("Unable to detect required AATSR bands");
        }
        
        merisRefBands = merisRefList.toArray(new Band[merisRefList.size()]);
        merisRadBands = merisRadList.toArray(new Band[merisRadList.size()]);
        aatsrNadirBands = aatsrNadirList.toArray(new Band[aatsrNadirList.size()]);
        aatsrFwardBands = aatsrFwardList.toArray(new Band[aatsrFwardList.size()]);

        final SynergyUtils.BandComparator bandComp = new SynergyUtils.BandComparator();
        java.util.Arrays.sort(merisRefBands, bandComp);
        java.util.Arrays.sort(merisRadBands, bandComp);
        java.util.Arrays.sort(aatsrNadirBands, bandComp);
        java.util.Arrays.sort(aatsrFwardBands, bandComp);
    }
    
    private void initSPR() {
        if ((straylightCorr) && !SynergyUtils.isRR(sourceProduct)) {
            SynergyUtils.info("Straylight correction not possible for full resolution products, disabled.");
            straylightCorr = false;
        }

        // Read neural network
        try {
            loadSPRFUBNeuralNet();
        } catch (Exception e) {
            if (tropicalAtmosphere) {
                throw new OperatorException("Failed to load neural net SP_FUB_trp.nna:\n" + e.getMessage());
            } else {
                throw new OperatorException("Failed to load neural net SP_FUB_uss.nna:\n" + e.getMessage());
            }
        }
        // Read straylight correction coefficients
        try {
            readFileData(straylightCoefficients, STRAYLIGHT_COEFF_FILE_NAME);
            readFileData(straylightCorrWavelengths, STRAYLIGHT_CORR_WAVELENGTH_FILE_NAME);
        } catch (Exception e) {
            throw new OperatorException("Failed to load aux data:\n" + e.getMessage());
        }
    }
    
    private void loadSPRFUBNeuralNet() throws IOException, JnnException {
        InputStream inputStream = null;
        inputStream = (tropicalAtmosphere) ? 
            ExtractFeaturesOp.class.getResourceAsStream("nna/"+NEURAL_NET_TRP_FILE_NAME) :
        	ExtractFeaturesOp.class.getResourceAsStream("nna/"+NEURAL_NET_USS_FILE_NAME);
        
        final InputStreamReader reader = new InputStreamReader(inputStream);
        try {
            Jnn.setOptimizing(true);
            neuralNet = Jnn.readNna(reader);
        } finally {
            reader.close();
        }
    }
    
    /**
     * This method reads a file into a float array
     * 
     * @throws IOException
     */
    private void readFileData(final float[] data, final String fileName) throws IOException {
        
        final InputStream inputStream = ExtractFeaturesOp.class.getResourceAsStream(fileName);
        
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        for (int i = 0; i < data.length; i++) {
            String line = bufferedReader.readLine();
            line = line.trim();
            data[i] = Float.parseFloat(line);
        }
        inputStream.close();
    }
    
    private void initAuxData() {
        try {
        	L2AuxdataProvider auxdataProvider = L2AuxdataProvider.getInstance();
            auxData = auxdataProvider.getAuxdata(sourceProduct);
        } catch (Exception e) {
            throw new OperatorException("Failed to load L2AuxData:\n" + e.getMessage(), e);
        }
    }
    
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle,
                                 ProgressMonitor pm) throws OperatorException {
        
        pm.beginTask("computing features ...", targetRectangle.height);
        JnnNet clonedNeuralNet = neuralNet.clone();
        
        try {
            // Target tiles
            Tile tTileVisBr = null;
            Tile tTileVisWh = null;
            Tile tTileNirBr = null;
            Tile tTileNirWh = null;
            Tile tTileWVabs = null;
            Tile tTile_761_754_865_r = null;
            Tile tTile_443_754_r = null;
            Tile tTile_870_760_r = null;
            Tile tTile_11_12_d = null;
            Tile tTile_865_890_ndsi = null;
            Tile tTile_555_1600_ndsi = null;
            Tile tTileSPR = null;
            Tile tTileSPRD = null;
            Tile tTileCoastline = null;
            
            // Source tiles
            final Tile[] sTileMeris = SynergyUtils.getSourceTiles(merisRefBands, targetRectangle, pm, this);
            final Tile[] sTileAatsrNadir = SynergyUtils.getSourceTiles(aatsrNadirBands, targetRectangle, pm, this);
            Tile[] sTileVirMeris = null;
            Tile[] sTileNirMeris = null;
            Tile[] sTileWVabs = null;
            Tile sTileDEM = null;
            Tile sTileL1F = null;
            // Source tiles for the SPR
            Tile sTileDetIdx = null;
            final Tile sTileR[] = new Tile[3];
            
            // Coastline and invalid masks
            int coastMask = 0;
            int invalidMask = 0;
            if (l1fBand != null) {
                coastMask = l1fBand.getFlagCoding().getFlagMask(SynergyConstants.FLAG_COASTLINE);
                invalidMask = l1fBand.getFlagCoding().getFlagMask(SynergyConstants.FLAG_INVALID);
            }
            
            double[] visWavelengths = null;
            double[] nirWavelengths = null;
            if (extractVis) {
                final Band[] meriVisBands = subBandArray(SynergyConstants.MERIS_VIS_BANDS, merisRefBands);
                sTileVirMeris = SynergyUtils.getSourceTiles(meriVisBands, targetRectangle, pm, this);
                visWavelengths = getSpectralWavelengths(meriVisBands);
                tTileVisBr = targetTileMap.get(visBr);
                tTileVisWh = targetTileMap.get(visWh);
            }
            if (extractNir) {
                final Band[] merisNirBands = subBandArray(SynergyConstants.MERIS_NIR_BANDS, merisRefBands);
                sTileNirMeris = SynergyUtils.getSourceTiles(merisNirBands, targetRectangle, pm, this);
                nirWavelengths = getSpectralWavelengths(merisNirBands);
                tTileNirBr = targetTileMap.get(nirBr);
                tTileNirWh = targetTileMap.get(nirWh);
            }
            if (extractWv) {
                sTileWVabs = SynergyUtils.getSourceTiles(subBandArray(new int[] {14,15}, merisRefBands), targetRectangle, pm, this);
                tTileWVabs = targetTileMap.get(wvabs);
            }
            if (extract_761_754_865_ratio) tTile_761_754_865_r = targetTileMap.get(b761_754_865_ratio);
            if (extract_443_754_ratio)     tTile_443_754_r     = targetTileMap.get(b443_754_ratio);
            if (extract_870_670_ratio)     tTile_870_760_r     = targetTileMap.get(b870_670_ratio);
            if (extract_11_12_diff)        tTile_11_12_d       = targetTileMap.get(b11_12_diff);
            if (extract_865_890_ndsi)      tTile_865_890_ndsi  = targetTileMap.get(b865_890_ndsi);
            if (extract_555_1600_ndsi)     tTile_555_1600_ndsi = targetTileMap.get(b555_1600_ndsi);
            if (extract_spr) {
                sTileDetIdx = getSourceTile(detIdxBand, targetRectangle, pm);
                final Band[] merisBands = (merisRadBands.length > 0) ? merisRadBands : merisRefBands;
                for (int i=0; i<3; i++) { // Bands 10, 11 and 12 (0-based in the arrays)
                    sTileR[i] = getSourceTile(merisBands[9+i], targetRectangle, pm);
                }
                
                tTileSPR = targetTileMap.get(sprBand);
                if (extract_sprd) {                    
                    sTileDEM = getSourceTile(demBand, targetRectangle, pm);
                    tTileSPRD = targetTileMap.get(sprdBand);
                }
            }
            if (extract_coastline) {
                sTileL1F = getSourceTile(l1fBand, targetRectangle, pm);
                tTileCoastline = targetTileMap.get(coastline);
            }

            for (int y=targetRectangle.y; y<targetRectangle.y + targetRectangle.height; y++) {
                for (int x=targetRectangle.x; x<targetRectangle.x + targetRectangle.width; x++) {
                    checkForCancellation(pm);
                    
                    final double[] merisRef   = getSamples(x, y, sTileMeris);
                    final double[] aatsrNadir = getSamples(x, y, sTileAatsrNadir);
                    
                    // Visible brightness and whiteness 
                    if (extractVis) {
                        final double[] reflectance = getSamples(x, y, sTileVirMeris); 
                        final double b = brightness(visWavelengths, reflectance);
                        final double w = whiteness(visWavelengths, reflectance);
                        tTileVisBr.setSample(x,y,b);
                        tTileVisWh.setSample(x,y,w);
                    }
                    // NIR brightness and whiteness
                    if (extractNir) {
                        final double[] reflectance = getSamples(x, y, sTileNirMeris); 
                        final double b = brightness(nirWavelengths, reflectance);
                        final double w = whiteness(nirWavelengths, reflectance);
                        tTileNirBr.setSample(x,y,b);
                        tTileNirWh.setSample(x,y,w);
                    }
                    // Water vapor absorption
                    if (extractWv) {
                        final double wva = waterVaporAbs(x, y, sTileWVabs);
                        tTileWVabs.setSample(x, y, wva);
                    }
                    // Ratios
                    if (extract_761_754_865_ratio) {
                        final double ratio = merisRef[9] / merisRef[10] / merisRef[12];
                        tTile_761_754_865_r.setSample(x, y, ratio);
                    }
                    if (extract_443_754_ratio) {
                        final double ratio = merisRef[1] / merisRef[9];
                        tTile_443_754_r.setSample(x, y, ratio);
                    }
                    if (extract_870_670_ratio) {
                        final double ratio = aatsrNadir[2] / aatsrNadir[1];
                        tTile_870_760_r.setSample(x, y, ratio);
                    }
                    // Differences
                    if (extract_11_12_diff) {
                        final double diff = aatsrNadir[5] - aatsrNadir[6];
                        tTile_11_12_d.setSample(x, y, diff);
                    }
                    // NDSI
                    if (extract_865_890_ndsi) {
                        final double ndsi = (merisRef[12] - merisRef[13]) / (merisRef[12] + merisRef[13]);
                        tTile_865_890_ndsi.setSample(x, y, ndsi);
                    }
                    if (extract_555_1600_ndsi) {
                        final double ndsi = (aatsrNadir[0] - aatsrNadir[3]) / (aatsrNadir[0] + aatsrNadir[3]);
                        tTile_555_1600_ndsi.setSample(x, y, ndsi);                        
                    }
                    // SPR
                    if (extract_spr) {
                        double spr = 0;
                        if ((sTileL1F.getSampleInt(x,y) & invalidMask) == 0) {
                            // Valid pixel, compute SPR
                            spr = computeSPR(x, y, sTileDetIdx, sTileR, clonedNeuralNet);
                        }
                        tTileSPR.setSample(x, y, spr);
                        // SPRD
                        if (extract_sprd) {
                            final double sprd = computeSPRD(x, y, spr, sTileDEM);
                            tTileSPRD.setSample(x, y, sprd);
                        }
                    }
                    // COAST LINE
                    if (extract_coastline) {
                        tTileCoastline.setSample(x,y,
                                               (sTileL1F.getSampleInt(x,y) & coastMask) != 0 ? 1 : 0);
                    }
                    pm.worked(1);
                }
            }
        }
        finally {
            pm.done();
        }
    }

    private static Band[] subBandArray(int[] idx, Band[] bands) {
        final Band[] b = new Band[idx.length];        
        for (int i=0; i<idx.length; i++) b[i] = bands[idx[i]-1];
        return b;
    }
     
    private static double[] getSpectralWavelengths(Band[] bands) {
        final double[] wavelengths = new double[bands.length];

        for (int i = 0; i < bands.length; ++i) {
            wavelengths[i] = bands[i].getSpectralWavelength();
        }
        return wavelengths;
    }
    
    private static double[] getSamples(int x, int y, Tile[] tiles) {
        final double[] samples = new double[tiles.length];

        for (int i = 0; i < samples.length; i++) {
            samples[i] = tiles[i].getSampleDouble(x, y);
        }

        return samples;
    }

    private static double brightness(double[] wavelengths, double[] reflectances) {
        double sum = 0.0;

        for (int i = 1; i < reflectances.length; i++) {
            sum += 0.5 * (reflectances[i] + reflectances[i-1]) * (wavelengths[i] - wavelengths[i-1]);
        }

        return sum / (wavelengths[wavelengths.length - 1] - wavelengths[0]);
    }

    private static double whiteness(double[] wavelengths, double[] reflectances) {
        double sum = 0.0;
        
        // Calculate the reflectances norm
        double norm = (reflectances[0] * reflectances[0]);
        for (int i = 1; i < reflectances.length; i++) {
            norm += (reflectances[i] * reflectances[i]);  
        }
        norm = sqrt(norm);
        if (norm == 0) norm = 1.0;
        
        for (int i = 1; i < reflectances.length; i++) {
            
            final double a = reflectances[i-1]/norm - 1/sqrt(reflectances.length);
            final double b = reflectances[i]  /norm - 1/sqrt(reflectances.length);
            
            // Integration
            if ( (a * b) >= 0) {
                // Both are >0 or <0, calculate trapezoid area
                sum += 0.5 * (abs(a) + abs(b)) * (wavelengths[i] - wavelengths[i-1]);
            }
            else {
                // Different signs, the slope crosses the 'origin', find that wavelength
                final double lambda = (a * (wavelengths[i] - wavelengths[i-1]) / (a-b)) + wavelengths[i-1];
                // Sum the two triangles
                sum += 0.5 * ( abs(a)*(lambda - wavelengths[i-1]) + abs(b)*(wavelengths[i] - lambda) );
            }
        }

        return sum / (wavelengths[wavelengths.length - 1] - wavelengths[0]);
    }
    
    /*
     * Calculates waver vapor absorption
     */
    private double waterVaporAbs(final int x, final int y, final Tile[] sourceTiles)
    {
        final double SZA = szaTpg.getPixelDouble(x,y);
        final double VZA = vzaTpg.getPixelDouble(x,y);
        final double mu = 1.0 / ( 1.0/cos(SZA*MathUtils.DTOR) + 1.0/cos(VZA*MathUtils.DTOR) );
        // Calculate ratio
        final double[] reflectances = getSamples(x,y,sourceTiles);                
        final double wva = -mu / SynergyConstants.TAU_ATM * log( reflectances[1]/reflectances[0] );
        return wva;
    }
    
    // TODO: decide whether to use radiances or reflectances. It would be nice to get ride of radiances!
    /*
     * Calculates the SPR
     */
    private double computeSPR(final int x, final int y, final Tile detector, final Tile[] tile, final JnnNet neuralNet) {
        
        final int detectorXY = detector.getSampleInt(x,y);
        
        final double szaRadXY = szaTpg.getPixelDouble(x,y) * MathUtils.DTOR; // degrees to radians
        final double vzaRadXY = vzaTpg.getPixelDouble(x,y) * MathUtils.DTOR;
        final double vaaDegXY = vaaTpg.getPixelDouble(x,y);
        final double saaDegXY = saaTpg.getPixelDouble(x,y);
        
        double lambda = auxData.central_wavelength[L2AuxData.bb760][detectorXY];
        final double fraction = (lambda - 753.75)/(778.0 - 753.75);
        double toar10XY;
        double toar11XY;
        double toar12XY;        
        if (merisRadBands.length > 0) {
            // Working with radiance bands
            toar10XY = tile[0].getSampleDouble(x,y) / auxData.detector_solar_irradiance[9][detectorXY];
            toar11XY = tile[1].getSampleDouble(x,y) / auxData.detector_solar_irradiance[10][detectorXY];
            toar12XY = tile[2].getSampleDouble(x,y) / auxData.detector_solar_irradiance[11][detectorXY];
        }
        else {
            // Working with reflectance bands
            final double ref2rad = Math.cos(szaRadXY) / Math.PI * auxData.seasonal_factor;
            toar10XY = tile[0].getSampleDouble(x,y) * ref2rad;
            toar11XY = tile[1].getSampleDouble(x,y) * ref2rad;
            toar12XY = tile[2].getSampleDouble(x,y) * ref2rad;
        }
        final double toar11XY_na = (1.0 - fraction)*toar10XY + fraction*toar12XY;
        
        double stray = 0.0;
        if (straylightCorr) {
            // apply FUB straylight correction...
            stray = straylightCoefficients[detectorXY] * toar10XY;
            lambda = straylightCorrWavelengths[detectorXY];
        }
        
        final double toar11XY_corrected = toar11XY + stray;
        
        final double[] nnIn = new double[7];
        final double[] nnOut = new double[1];
        
        // Apply FUB NN...
        nnIn[0] = toar10XY;
        nnIn[1] = toar11XY_corrected / toar11XY_na;
        nnIn[2] = 0.15; // AOT
        nnIn[3] = Math.cos(szaRadXY);
        nnIn[4] = Math.cos(vzaRadXY);
        nnIn[5] = Math.sin(vzaRadXY) * Math.cos((vaaDegXY - saaDegXY) * MathUtils.DTOR);
        nnIn[6] = lambda;

        neuralNet.process(nnIn, nnOut);
        return nnOut[0];
    }
    
    /*
     * Calculates the signal pressure ratio difference
     */
    private double computeSPRD(final int x, final int y, final double spr, final Tile sTileDEM)
    {
        double ap = atmPreTpg.getPixelDouble(x,y);
        double da = demAltTpg.getPixelDouble(x,y);
        double dem = sTileDEM.getSampleDouble(x,y);
        // Cannot be negative (da could be < 0 over the sea)
        if (da < 0 || dem < 0) {
            da = 0;
            dem = 0;
        }
        // Reference value for the surface pressure
        final double sprd = spr - ( ap * exp( (da - dem)/7400.0d ) );
        return sprd;
    }
    
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(ExtractFeaturesOp.class);
        }
    }
}
