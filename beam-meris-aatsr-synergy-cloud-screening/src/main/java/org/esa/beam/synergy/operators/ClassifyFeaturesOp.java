package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.jai.GeneralFilterFunction;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.synergy.util.SynergyUtils;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.RectangleExtender;
import org.esa.beam.util.math.MathUtils;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * Operator for extracting cloud features from TOA reflectances.
 * <p/>
 * The classification process involves several operators:
 * <p/>
 * - ExtractFeatures:
 * Extracts the features needed by the NN classifier, and the coastline which
 * will be used to improve results in those areas.
 * <p/>
 * - ClassifyFeaturesNN:
 * Implements the neural networks processing and gives a product with two bands,
 * the nn_cloudmask and nn_cloudmask_nadir.
 * <p/>
 * - ClassifyFeaturesCloudCoastRemover:
 * - Coastline dilation::
 * Takes two products, the product generated by ExtractFeatures and the
 * one generated by ClassifyFeaturesNN. From the first one, the coastline band
 * is filtered using a spatial max 5x5 filter, and combined with the cloud masks
 * from the NN classifier.
 * <p/>
 * - Coastline expansion:
 * Applies a spatial sum filter to previous dilated bands.
 * <p/>
 * - Coastline erosion:
 * Finally, taking all previous products and applying new spatial filters the
 * cloudmask false positives given by coastlines are removed.
 * <p/>
 * - ClassifyFeatures:
 * This one calls ExtractFeatures, ClassifyFeaturesNN and ClassifyFeaturesCloudCoastRemover,
 * implementing in one operator the whole classification process.
 *
 * @author Ralf Quast, Olaf Danne, Jordi Munyoz-Mari, Luis Gomez-Chova
 * @version $Revision: 8155 $ $Date: 2010-01-29 17:30:33 +0100 (vie, 29 ene 2010) $
 */
@OperatorMetadata(alias = "synergy.ClassifyFeatures",
                  version = "1.2",
                  authors = "Ralf Quast, Olaf Danne, Jordi Munyoz-Mari, Luis Gomez-Chova",
                  copyright = "(c) 2008 by Brockmann Consult and IPL-UV",
                  description = "Classifies features extracted from Synergy TOA reflectance products.", internal = true)

public class ClassifyFeaturesOp extends Operator {

    @SourceProduct(alias = "source",
                   label = "Name (Synergy product)",
                   description = "Select a Synergy product")
    private Product sourceProduct;

    @TargetProduct(description = "The target product. Contains cloud screening masks.")
    private Product targetProduct;

    @Parameter(defaultValue = "true",
               label = "Use the AATSR forward view when classifying",
               description = "Use the AATSR forward view when classifying.")
    private boolean useForwardView;

    @Parameter(defaultValue = "true",
               label = "Compute the cloud abundance",
               description = "Compute the cloud abundance.")
    private boolean computeCOT;

    @Parameter(defaultValue = "false",
               label = "Compute snow risk flag",
               description = "Compute snow risk flag.")
    private boolean computeSF;

    @Parameter(defaultValue = "false",
               label = "Compute cloud shadow risk flag",
               description = "Compute cloud shadow risk flag.")
    private boolean computeSH;

    @Parameter(defaultValue = "false",
               label = "Return bands with neural network outputs",
               description = "Return bands with neural network outputs.")
    private boolean outputNN;

    @Parameter(defaultValue = "5",
               label = "Shadow width in pixels",
               description = "Sets the shadow width in computations.")
    private int shadowWidth;

    // Constants and variables for the cloud shadow risk computation
    private static final int MEAN_EARTH_RADIUS = 6372000;
    private static final int MAX_ITER = 5;
    private static final double DIST_THRESHOLD = 1 / 740.0;

    private RectangleExtender rectCalculator;
    private GeoCoding geoCoding;
    private RasterDataNode altitudeRDN;
    private Band ctpBand = null;

    // Source bands: cloud comb band and snow comb band
    private transient Band[] sBand = new Band[2];
    // Spatially filtered bands
    private transient Band[] sBand_cmcr_med3x3 = new Band[sBand.length];
    // Abundances source band
    private transient Band sBand_abun;

    // Target bands
    private transient Band tBand_flags;
    private transient Band tBand_abun = null;

    @Override
    public void initialize() throws OperatorException {

        // Extract features
        Map<String, Object> featParams = new HashMap<String, Object>(12);
        featParams.put("extractVis", true);
        featParams.put("extractWv", true);
        featParams.put("extract_761_754_865_ratio", true);
        featParams.put("extract_870_670_ratio", true);
        featParams.put("extract_443_754_ratio", true);
        featParams.put("extract_11_12_diff", true);
        featParams.put("extract_865_890_ndsi", true);
        featParams.put("extract_555_1600_ndsi", true);
        featParams.put("extract_spr", true);
        featParams.put("extract_sprd", false);
        featParams.put("extract_coastline", true);
        featParams.put("straylightCorr", true);
        //featParams.put("tropicalAtmosphere", true);
        final Product featProduct =
                GPF.createProduct(OperatorSpi.getOperatorAlias(ExtractFeaturesOp.class), featParams, sourceProduct);

        // Classify product using the neural networks
        Map<String, Object> nnClassParams = new HashMap<String, Object>(2);
        nnClassParams.put("useForwardView", useForwardView);
        nnClassParams.put("computeCOT", computeCOT);
        Map<String, Product> nnClassInputs = new HashMap<String, Product>(2);
        nnClassInputs.put("sourceProduct", sourceProduct);
        nnClassInputs.put("features", featProduct);
        final Product nnClassProduct =
                GPF.createProduct(OperatorSpi.getOperatorAlias(ClassifyFeaturesNNOp.class), nnClassParams,
                                  nnClassInputs);

        // Check if there is coastline
        final Band bCoastline = featProduct.getBand(SynergyConstants.F_COASTLINE);

        if (bCoastline.getStx().getMaximum() > 0) {
            // Coastline present, remove false artifacts
            final Operator operator = new ClassifyFeaturesCloudCoastRemoverOp();
            operator.setSourceProduct("features", featProduct);
            operator.setSourceProduct("nnProduct", nnClassProduct);
            sBand[0] = operator.getTargetProduct().getBand(SynergyConstants.B_COAST_ERODED);
            operator.dispose();
        } else {
            // No coastline, use the original cloudmask
            sBand[0] = nnClassProduct.getBand(SynergyConstants.B_CLOUDMASK);
        }
        // The snow mask as-is (no coast removal is needed)
        sBand[1] = nnClassProduct.getBand(SynergyConstants.B_SNOWMASK);

        // Norman example to do spatial filtering using JAI
        //RenderedImage srcIm = sourceProduct.getBand("radiance_8").getGeophysicalImage();
        //KernelJAI kernel = createGaussianKernel(5, 5);
        //RenderedImage convIm = ConvolveDescriptor.create(srcIm, kernel, null);
        //targetProduct.addBand("radiance_8", ProductData.TYPE_FLOAT32).setSourceImage(convIm);

        // Generate 3x3 median bands
        for (int i = 0; i < sBand.length; i++) {
//            sBand_cmcr_med3x3[i] = new GeneralFilterBand("median", sBand[i], 3, GeneralFilterBand.MEDIAN);
            // adjusted for BEAM 5:
            sBand_cmcr_med3x3[i] = new GeneralFilterBand("median", sBand[i], GeneralFilterBand.OpType.MEDIAN,
                    new Kernel(3, 3, new double[]{1, 1, 1, 1, 1, 1, 1, 1, 1}), 1);

        }

        // Abundances source band
        sBand_abun = nnClassProduct.getBand(SynergyConstants.B_CLOUDINDEX);

        if (computeSH) {
            rectCalculator = new RectangleExtender(new Rectangle(sourceProduct.getSceneRasterWidth(),
                                                                 sourceProduct.getSceneRasterHeight()),
                                                   shadowWidth, shadowWidth);
            geoCoding = sourceProduct.getGeoCoding();

            altitudeRDN = SynergyUtils.searchBand(sourceProduct, SynergyConstants.DEM_ELEVATION);
            if (altitudeRDN != null) {
                if (SynergyUtils.isFSG(sourceProduct) && shadowWidth == 0) {
                    shadowWidth = 16;
                }
            } else {
                // Create it
                try {
                    final String productType = sourceProduct.getProductType();
                    if (SynergyUtils.isFR(sourceProduct)) {
                        sourceProduct.setProductType(EnvisatConstants.MERIS_FR_L1B_PRODUCT_TYPE_NAME);
                    } else {
                        sourceProduct.setProductType(EnvisatConstants.MERIS_RR_L1B_PRODUCT_TYPE_NAME);
                    }
                    final Product demProduct = GPF.createProduct("synergy.CreateElevationBand", GPF.NO_PARAMS,
                                                                 sourceProduct);
                    sourceProduct.setProductType(productType);
                    altitudeRDN = demProduct.getBand(SynergyConstants.DEM_ELEVATION);
                } catch (OperatorException e) {
                    SynergyUtils.info("  " + e.getMessage());
                    SynergyUtils.info("  Shadow Risk Flag will be computed from tie point grid altitude");
                    altitudeRDN = null;
                }

                if (altitudeRDN == null) {
                    altitudeRDN = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_DEM_ALTITUDE_DS_NAME);
                    if (shadowWidth == 0) {
                        shadowWidth = 64;
                    }
                } else if (shadowWidth == 0) {
                    shadowWidth = 16;
                }
            }

            ctpBand = featProduct.getBand(SynergyConstants.F_SURF_PRESS);
        }

        // Construct target product
        targetProduct = new Product("Synergy_CLASSIFICATION", sourceProduct.getProductType() + "_CLASS",
                                    sourceProduct.getSceneRasterWidth(), sourceProduct.getSceneRasterHeight());

        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());
        targetProduct.setDescription(sourceProduct.getDescription() + " classified");

        // Flag codings
        final FlagCoding flagCoding = new FlagCoding(SynergyConstants.B_CLOUDFLAGS);
        flagCoding.addFlag(SynergyConstants.FLAGNAME_CLOUD,
                           SynergyConstants.FLAGMASK_CLOUD, "Cloudy");
        flagCoding.addFlag(SynergyConstants.FLAGNAME_CLOUD_FILLED,
                           SynergyConstants.FLAGMASK_CLOUD_FILLED, "Cloud (filled)");
        if (computeSF) {
            flagCoding.addFlag(SynergyConstants.FLAGNAME_SNOW,
                               SynergyConstants.FLAGMASK_SNOW, "Snow/Ice risk");
            flagCoding.addFlag(SynergyConstants.FLAGNAME_SNOW_FILLED,
                               SynergyConstants.FLAGMASK_SNOW_FILLED, "Snow/Ice risk (filled)");
        }
        if (computeSH) {
            flagCoding.addFlag(SynergyConstants.FLAGNAME_SHADOW,
                               SynergyConstants.FLAGMASK_SHADOW, "Cloud shadow risk");
        }
        targetProduct.getFlagCodingGroup().add(flagCoding);

        // Bitmasks
        targetProduct.getMaskGroup().
                add(Mask.BandMathsType.create("cloud_synergy",
                                              flagCoding.getAttribute(SynergyConstants.FLAGNAME_CLOUD).getDescription(),
                                              targetProduct.getSceneRasterWidth(), targetProduct.getSceneRasterHeight(),
                                              flagCoding.getName() + "." + SynergyConstants.FLAGNAME_CLOUD,
                                              new Color(240, 240, 0), 0.5F));
        targetProduct.getMaskGroup().
                add(Mask.BandMathsType.create("cloud_filled_synergy",
                                              flagCoding.getAttribute(
                                                      SynergyConstants.FLAGNAME_CLOUD_FILLED).getDescription(),
                                              targetProduct.getSceneRasterWidth(), targetProduct.getSceneRasterHeight(),
                                              flagCoding.getName() + "." + SynergyConstants.FLAGNAME_CLOUD_FILLED,
                                              new Color(200, 200, 0), 0.5F));
        if (computeSF) {
            targetProduct.getMaskGroup().
                    add(Mask.BandMathsType.create("snow_risk_synergy",
                                                  flagCoding.getAttribute(
                                                          SynergyConstants.FLAGNAME_SNOW).getDescription(),
                                                  targetProduct.getSceneRasterWidth(),
                                                  targetProduct.getSceneRasterHeight(),
                                                  flagCoding.getName() + "." + SynergyConstants.FLAGNAME_SNOW,
                                                  Color.cyan, 0.5F));
            targetProduct.getMaskGroup().
                    add(Mask.BandMathsType.create("snow_risk_filled_synergy",
                                                  flagCoding.getAttribute(
                                                          SynergyConstants.FLAGNAME_SNOW_FILLED).getDescription(),
                                                  targetProduct.getSceneRasterWidth(),
                                                  targetProduct.getSceneRasterHeight(),
                                                  flagCoding.getName() + "." + SynergyConstants.FLAGNAME_SNOW_FILLED,
                                                  Color.blue, 0.5F));
        }
        if (computeSH) {
            targetProduct.getMaskGroup().
                    add(Mask.BandMathsType.create("cloud_shadow_risk_synergy",
                                                  flagCoding.getAttribute(
                                                          SynergyConstants.FLAGNAME_SHADOW).getDescription(),
                                                  targetProduct.getSceneRasterWidth(),
                                                  targetProduct.getSceneRasterHeight(),
                                                  flagCoding.getName() + "." + SynergyConstants.FLAGNAME_SHADOW,
                                                  Color.blue, 0.5F));
        }
        /*
        targetProduct.addBitmaskDef(
                      new BitmaskDef("cloud_synergy",
                                     flagCoding.getAttribute(SynergyConstants.FLAGNAME_CLOUD).getDescription(),
                                     flagCoding.getName() + "." + SynergyConstants.FLAGNAME_CLOUD,
                                     new Color(240,240,0), 0.5F));
        targetProduct.addBitmaskDef(
                      new BitmaskDef("cloud_filled_synergy",
                                     flagCoding.getAttribute(SynergyConstants.FLAGNAME_CLOUD_FILLED).getDescription(),
                                     flagCoding.getName() + "." + SynergyConstants.FLAGNAME_CLOUD_FILLED,
                                     new Color(200,200,0), 0.5F));
        if (computeSF) {
            targetProduct.addBitmaskDef(
                          new BitmaskDef("snow_risk_synergy",
                                         flagCoding.getAttribute(SynergyConstants.FLAGNAME_SNOW).getDescription(),
                                         flagCoding.getName() + "." + SynergyConstants.FLAGNAME_SNOW,
                                         Color.cyan, 0.5F));
            targetProduct.addBitmaskDef(
                          new BitmaskDef("snow_risk_filled_synergy",
                                         flagCoding.getAttribute(SynergyConstants.FLAGNAME_SNOW_FILLED).getDescription(),
                                         flagCoding.getName() + "." + SynergyConstants.FLAGNAME_SNOW_FILLED,
                                         Color.blue, 0.5F));
        }
        if (computeSH) {
            targetProduct.addBitmaskDef(
                          new BitmaskDef("cloud_shadow_risk_synergy",
                                         flagCoding.getAttribute(SynergyConstants.FLAGNAME_SHADOW).getDescription(),
                                         flagCoding.getName() + "." + SynergyConstants.FLAGNAME_SHADOW,
                                         Color.magenta, 0.5F));
            
            // TODO: if we've create dem_altitude band, we can add it to final product
        }
        */

        // The flags band containing everything
        tBand_flags = targetProduct.addBand(SynergyConstants.B_CLOUDFLAGS, ProductData.TYPE_UINT8);
        tBand_flags.setDescription("MERIS/AATSR synergy cloud flags");
        tBand_flags.setSampleCoding(flagCoding);

        // Abundances target band
        if (computeCOT) {
            tBand_abun = targetProduct.addBand(SynergyConstants.B_CLOUDINDEX, ProductData.TYPE_FLOAT32);
            tBand_abun.setUnit("dl");
            tBand_abun.setDescription("MERIS/AATSR synergy cloud index (0: cloud free, 1: cloudy)");
            tBand_abun.setNoDataValue(SynergyConstants.NODATAVALUE);
            tBand_abun.setNoDataValueUsed(true);
        }

        if (outputNN) {
            // Copy all bands except the ones starting by cloud, snow or abun
            // ie: the outputs of the neural networks
            for (Band b : nnClassProduct.getBands()) {
                if (!b.getName().startsWith("cloud") &&
                    !b.getName().startsWith("snow") &&
                    !b.getName().startsWith("abun")) {
                    targetProduct.addBand(b);
                }
            }
        }

        //ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        // Before BEAM 4.7 copyTiePointGrids (above) does not copy units and description fields
        for (TiePointGrid tpg : sourceProduct.getTiePointGrids()) {
            targetProduct.addTiePointGrid(tpg.cloneTiePointGrid());
            String tpgName = tpg.getName();
            targetProduct.getTiePointGrid(tpgName).setUnit(tpg.getUnit());
            targetProduct.getTiePointGrid(tpgName).setDescription(tpg.getDescription());
        }

        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        targetProduct.setPreferredTileSize(32, 32);
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle,
                                 ProgressMonitor pm) throws OperatorException {

        pm.beginTask("Processing frame...", targetRectangle.height);

        try {
            // Source tiles
            final Tile[] sTiles = SynergyUtils.getSourceTiles(sBand, targetRectangle, this);
            final Tile[] sTile_filled = SynergyUtils.getSourceTiles(sBand_cmcr_med3x3, targetRectangle, this);
            Tile sTile_abun = null;
            // Target tiles
            final Tile tTile_flags = targetTiles.get(tBand_flags);
            Tile tTile_abun = null;
            if (computeCOT) {
                sTile_abun = getSourceTile(sBand_abun, targetRectangle);
                tTile_abun = targetTiles.get(tBand_abun);
            }

            // Shadow risk stuff
            Tile szaTile = null;
            Tile saaTile = null;
            Tile vzaTile = null;
            Tile vaaTile = null;
            Tile altTile = null;
            Tile ctpTile = null;
            if (computeSH) {
                final Rectangle sourceRectangle = rectCalculator.extend(targetRectangle);
                szaTile = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME),
                                        sourceRectangle);
                saaTile = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_AZIMUTH_DS_NAME),
                                        sourceRectangle);
                vzaTile = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_ZENITH_DS_NAME),
                                        sourceRectangle);
                vaaTile = getSourceTile(sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_VIEW_AZIMUTH_DS_NAME),
                                        sourceRectangle);
                altTile = getSourceTile(altitudeRDN, sourceRectangle);
                ctpTile = getSourceTile(ctpBand, sourceRectangle);
            }

            for (int y = targetRectangle.y; y < targetRectangle.y + targetRectangle.height; y++) {
                for (int x = targetRectangle.x; x < targetRectangle.x + targetRectangle.width; x++) {

                    checkForCancellation();

                    int flags = 0;

                    // Flags
                    if (sTiles[0].getSampleBoolean(x, y)) {
                        flags |= SynergyConstants.FLAGMASK_CLOUD;
                    }
                    if (sTiles[0].getSampleBoolean(x, y) ||
                        sTile_filled[0].getSampleBoolean(x, y)) {
                        flags |= SynergyConstants.FLAGMASK_CLOUD_FILLED;
                    }
                    if (computeSF) {
                        if (sTiles[1].getSampleBoolean(x, y)) {
                            flags |= SynergyConstants.FLAGMASK_SNOW;
                        }
                        if (sTiles[1].getSampleBoolean(x, y) ||
                            sTile_filled[1].getSampleBoolean(x, y)) {
                            flags |= SynergyConstants.FLAGMASK_SNOW_FILLED;
                        }
                    }

                    tTile_flags.setSample(x, y, flags);

                    if (computeSH) {
                        if (sTiles[0].getSampleBoolean(x, y)) {
                            final float sza = szaTile.getSampleFloat(x, y) * MathUtils.DTOR_F;
                            final float vza = vzaTile.getSampleFloat(x, y) * MathUtils.DTOR_F;
                            final float saa = saaTile.getSampleFloat(x, y) * MathUtils.DTOR_F;
                            final float vaa = vaaTile.getSampleFloat(x, y) * MathUtils.DTOR_F;

                            PixelPos pixelPos = new PixelPos(x, y);
                            final GeoPos geoPos = geoCoding.getGeoPos(pixelPos, null);
                            float ctp = ctpTile.getSampleFloat(x, y);
                            if (ctp > 0) {
                                float cloudAlt = computeHeightFromPressure(ctp);
                                GeoPos shadowPos = getCloudShadow(altTile, sza, saa, vza, vaa, cloudAlt, geoPos);
                                if (shadowPos != null) {
                                    pixelPos = geoCoding.getPixelPos(shadowPos, pixelPos);
                                    if (targetRectangle.contains(pixelPos)) {
                                        final int pixelX = MathUtils.floorInt(pixelPos.x);
                                        final int pixelY = MathUtils.floorInt(pixelPos.y);
                                        if (!sTiles[0].getSampleBoolean(pixelX, pixelY)) {
                                            //flags |= SynergyConstants.FLAGMASK_SHADOW;
                                            int temp = tTile_flags.getSampleInt(pixelX, pixelY);
                                            temp |= SynergyConstants.FLAGMASK_SHADOW;
                                            tTile_flags.setSample(pixelX, pixelY, temp);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (computeCOT) {
                        if (sBand_abun.isPixelValid(x, y)) {
                            tTile_abun.setSample(x, y,
                                                 sTile_abun.getSampleFloat(x, y) *
                                                 sTile_filled[0].getSampleFloat(x, y));
                        } else {
                            tTile_abun.setSample(x, y, sBand_abun.getNoDataValue());
                        }
                    }
                    pm.worked(1);
                }
            }
        }
        // TODO: remove this catch when isPixelValid is fixed
        catch (java.lang.ArrayIndexOutOfBoundsException ignored) {
        } finally {
            pm.done();
        }
    }

    private float computeHeightFromPressure(float pressure) {
        return (float) (-8000 * Math.log(pressure / 1013.0f));
    }

    private GeoPos getCloudShadow(Tile altTile, float sza, float saa, float vza, float vaa,
                                  float cloudAlt, GeoPos appCloud) {

        double surfaceAlt = getAltitude(altTile, appCloud);

        // deltaX and deltaY are the corrections to apply to get the
        // real cloud position from the apparent one
        // deltaX/deltyY are in meters
        final double deltaX = -(cloudAlt - surfaceAlt) * Math.tan(vza) * Math.sin(vaa);
        final double deltaY = -(cloudAlt - surfaceAlt) * Math.tan(vza) * Math.cos(vaa);

        // distLat and distLon are in degrees
        double distLat = -(deltaY / MEAN_EARTH_RADIUS) * MathUtils.RTOD;
        double distLon = -(deltaX / (MEAN_EARTH_RADIUS *
                                     Math.cos(appCloud.getLat() * MathUtils.DTOR))) * MathUtils.RTOD;

        double latCloud = appCloud.getLat() + distLat;
        double lonCloud = appCloud.getLon() + distLon;

        // Once the cloud position is know, iterate to get shadow position
        int iter = 0;
        double dist = 2 * DIST_THRESHOLD;
        surfaceAlt = 0;
        double lat0, lon0;
        double lat = latCloud;
        double lon = lonCloud;
        GeoPos pos = new GeoPos();

        while ((iter < MAX_ITER) && (dist > DIST_THRESHOLD) && (surfaceAlt < cloudAlt)) {
            lat0 = lat;
            lon0 = lon;
            pos.setLocation((float) lat, (float) lon);
            PixelPos pixelPos = geoCoding.getPixelPos(pos, null);
            if (!(pixelPos.isValid() && altTile.getRectangle().contains(pixelPos))) {
                return null;
            }
            surfaceAlt = getAltitude(altTile, pos);

            double deltaProjX = (cloudAlt - surfaceAlt) * Math.tan(sza) * Math.sin(saa);
            double deltaProjY = (cloudAlt - surfaceAlt) * Math.tan(sza) * Math.cos(saa);

            // distLat and distLon are in degrees
            distLat = -(deltaProjY / MEAN_EARTH_RADIUS) * MathUtils.RTOD;
            lat = latCloud + distLat;
            distLon = -(deltaProjX / (MEAN_EARTH_RADIUS * Math.cos(lat * MathUtils.DTOR))) * MathUtils.RTOD;
            lon = lonCloud + distLon;

            dist = Math.max(Math.abs(lat - lat0), Math.abs(lon - lon0));
            iter++;
        }

        if (surfaceAlt < cloudAlt && iter < MAX_ITER && dist < DIST_THRESHOLD) {
            return new GeoPos((float) lat, (float) lon);
        }

        return null;
    }

    private float getAltitude(Tile altTile, GeoPos geoPos) {
        final PixelPos pixelPos = geoCoding.getPixelPos(geoPos, null);
        Rectangle rectangle = altTile.getRectangle();
        final int x = MathUtils.roundAndCrop(pixelPos.x, rectangle.x, rectangle.x + rectangle.width - 1);
        final int y = MathUtils.roundAndCrop(pixelPos.y, rectangle.y, rectangle.y + rectangle.height - 1);
        return altTile.getSampleFloat(x, y);
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(ClassifyFeaturesOp.class);
        }
    }
}
